use super::*;
use tokio_util::task::AbortOnDropHandle;

/// `(start, end, download)` of the next block chunk on the same session transport.
pub(super) type Prefetch = (
    u64,
    u64,
    AbortOnDropHandle<Result<Vec<ScannableBlock>, InterfaceError>>,
);
/// Scanning threads per chunk; capped to bound phone heat and battery use.
const SCAN_THREADS: usize = 4;

/// Scanning is per-block and the scanner is read-only, so split a chunk across
/// cores. Results stay in block order; errors are surfaced per block by the caller.
/// Workers stop at the next block once `cancel` fires, so Lock never waits for a chunk.
fn scan_blocks(
    scanner: &Scanner,
    blocks: &[ScannableBlock],
    cancel: &CancellationToken,
) -> Result<Vec<Result<Vec<WalletOutput>, monero_wallet::ScanError>>, EngineError> {
    let threads = std::thread::available_parallelism().map_or(1, |n| n.get().min(SCAN_THREADS));
    let per = blocks.len().div_ceil(threads).max(1);
    let found: Vec<_> = std::thread::scope(|s| {
        let parts: Vec<_> = blocks
            .chunks(per)
            .map(|part| {
                let mut scanner = scanner.clone();
                s.spawn(move || {
                    part.iter()
                        .map_while(|b| {
                            (!cancel.is_cancelled()).then(|| {
                                scanner
                                    .scan(b.clone())
                                    .map(|t| t.ignore_additional_timelock())
                            })
                        })
                        .collect::<Vec<_>>()
                })
            })
            .collect();
        parts
            .into_iter()
            .flat_map(|p| p.join().unwrap_or_else(|e| std::panic::resume_unwind(e)))
            .collect()
    });
    // A cancelled worker returns a short list; never pair it with the wrong blocks.
    if cancel.is_cancelled() {
        return Err(EngineError::Disconnected);
    }
    Ok(found)
}

impl<T: HttpTransport + Send + 'static> WalletEngine<T> {
    /// Read tip metadata only. A timer cannot discard an active payment review.
    pub async fn poll_updates(&mut self) -> Result<SyncStatus, EngineError> {
        self.check()?;
        if self.prepared.is_some() {
            return self.status();
        }
        let tip = u64::try_from(
            self.rpc()?
                .latest_block_number()
                .await
                .map_err(EngineError::node_error)?,
        )
        .map_err(|_| EngineError::Node)?
        .checked_add(1)
        .ok_or(EngineError::Node)?;
        if tip > u64::from(u32::MAX) {
            return Err(EngineError::Node);
        }
        let caught_up = if self.state.next_height == tip {
            if let Some(last) = self.state.checkpoints.last() {
                self.rpc()?
                    .block_hash(last.height as usize)
                    .await
                    .map_err(EngineError::node_error)?
                    == last.hash
            } else {
                false
            }
        } else {
            false
        };
        self.check()?;
        self.target_height = tip;
        self.synced = caught_up;
        self.status()
    }

    /// A scan result is published only after its entire window is durable.
    pub async fn sync_batch(&mut self) -> Result<SyncStatus, EngineError> {
        self.sync_window(2_000).await
    }

    /// Keep reads bounded independently of the durable checkpoint window.
    pub(super) async fn sync_window(&mut self, max_blocks: u64) -> Result<SyncStatus, EngineError> {
        let started = Instant::now();
        self.synced = false;
        self.prepared = None;
        let tip = u64::try_from(
            self.rpc()?
                .latest_block_number()
                .await
                .map_err(EngineError::node_error)?,
        )
        .map_err(|_| EngineError::Node)?
        .checked_add(1)
        .ok_or(EngineError::Node)?;
        if tip > u64::from(u32::MAX) {
            return Err(EngineError::Node);
        }
        self.target_height = tip;
        // A node behind saved progress must not trigger a rollback (worst case to
        // the restore height). Wait until it covers saved progress, then check reorgs.
        if self.state.next_height > tip {
            return Err(EngineError::Height);
        }
        self.rollback_reorg(tip).await?;
        if self.state.next_height == tip {
            self.synced = true;
            return self.status();
        }
        let window_end = self
            .state
            .next_height
            .saturating_add(max_blocks.clamp(1, 2_000))
            .min(tip);
        let mut next = self.state.clone();
        if self.scanner_cache.is_none() {
            self.scanner_cache = Some(self.scanner());
        }
        // Index unspent images once per durable window rather than visiting
        // every saved output again for every block in that window.
        let mut image_index = next
            .outputs
            .iter()
            .filter(|(_, o)| o.spent_height.is_none())
            .filter_map(|(key, o)| o.image.as_ref().map(|image| (image.clone(), key.clone())))
            .collect::<BTreeMap<_, _>>();
        let mut activity = false;
        while next.next_height < window_end {
            let chunk_started = Instant::now();
            let start = next.next_height;
            let (end, blocks) = loop {
                self.check()?;
                let prefetched = match self.prefetch.take() {
                    Some((from, to, download)) if from == start && to <= window_end => {
                        download.await.ok().map(|result| (to, result))
                    }
                    _ => None,
                };
                let (end, result) = match prefetched {
                    Some(fetched) => fetched,
                    None => {
                        let end = start.saturating_add(self.batch_size).min(window_end);
                        let result = self
                            .rpc()?
                            .contiguous_scannable_blocks(start as usize..=(end - 1) as usize)
                            .await;
                        (end, result)
                    }
                };
                match result {
                    Ok(blocks) => break (end, blocks),
                    Err(error) => {
                        let error = EngineError::node_error(error);
                        // A prefetched read may be larger than the current batch size.
                        let requested = end - start;
                        if matches!(
                            error,
                            EngineError::Transport(crate::transport::NodeFailure::Size)
                        ) && requested > batch::MIN
                        {
                            // Retry only this read, on the same consented route/node,
                            // with fewer blocks and the original hard response cap.
                            self.batch_limit = (requested / 2).max(batch::MIN);
                            self.batch_size = self.batch_limit;
                            continue;
                        }
                        return Err(error);
                    }
                }
            };
            self.check()?;
            if blocks.len() as u64 != end - start {
                return Err(EngineError::Node);
            }
            if next.next_output.is_none() {
                // Only the cumulative count immediately before this range is
                // needed. Fetching every historical count can download tens of MB
                // before a new wallet scans even its first recent block.
                let d = self
                    .rpc()?
                    .ringct_output_distribution(start.saturating_sub(1) as usize..start as usize)
                    .await
                    .map_err(EngineError::node_error)?;
                next.next_output = Some(*d.last().ok_or(EngineError::Node)?);
            }
            if end < window_end {
                // Download the next chunk while this one is scanned. It is only
                // used if it starts exactly where this window's progress ends.
                let to = end.saturating_add(self.batch_size).min(window_end);
                let rpc = self.rpc()?.clone();
                self.prefetch = Some((
                    end,
                    to,
                    AbortOnDropHandle::new(tokio::spawn(async move {
                        rpc.contiguous_scannable_blocks(end as usize..=(to - 1) as usize)
                            .await
                    })),
                ));
            }
            let found = scan_blocks(
                self.scanner_cache.as_ref().ok_or(EngineError::Storage)?,
                &blocks,
                &self.cancel,
            )?;
            for (block, found) in blocks.iter().zip(found) {
                self.check()?;
                let h = block.block.number() as u64;
                if h != next.next_height {
                    return Err(EngineError::Node);
                }
                if next
                    .checkpoints
                    .last()
                    .is_some_and(|c| block.block.header.previous != c.hash)
                {
                    return Err(EngineError::SyncRequired);
                }
                let hash = block.block.hash();
                let miner_hash = block.block.miner_transaction().hash();
                let miner = Transaction::<Pruned>::from(block.block.miner_transaction().clone());
                let mut count = 0u64;
                let mut spent = BTreeSet::new();
                for tx in std::iter::once(&miner).chain(block.transactions.iter()) {
                    if matches!(tx, Transaction::V2 { .. }) {
                        count = count
                            .checked_add(tx.prefix().outputs.len() as u64)
                            .ok_or(EngineError::Node)?;
                    }
                    for i in &tx.prefix().inputs {
                        if let Input::ToKey { key_image, .. } = i {
                            spent.insert(hex::encode(key_image.to_bytes()));
                        }
                    }
                }
                let first = next.next_output.ok_or(EngineError::Node)?;
                if count > 0 && block.output_index_for_first_ringct_output != Some(first) {
                    return Err(EngineError::Node);
                }
                if !next.payments.is_empty() {
                    // Encode each block hash once, not once per saved payment.
                    let mined: BTreeSet<String> =
                        block.block.transactions.iter().map(hex::encode).collect();
                    for p in &mut next.payments {
                        if mined.contains(&p.transaction) {
                            activity |= p.mined_height != Some(h);
                            p.mined_height = Some(h);
                        }
                    }
                }
                let mut received = BTreeMap::<String, u64>::new();
                for o in found.map_err(|_| EngineError::Node)? {
                    if let Some(index) = o.subaddress() {
                        if index.account() != 0 || index.address() > crate::MAX_SUBADDRESSES {
                            return Err(EngineError::Verification);
                        }
                        next.created_subaddress = next.created_subaddress.max(index.address());
                        next.subaddress_count = next.subaddress_count.max(index.address());
                    }
                    let key = hex::encode(o.key().compress().to_bytes());
                    if next.outputs.contains_key(&key) {
                        continue;
                    }
                    let id = hex::encode(o.transaction());
                    let amount = received.entry(id).or_default();
                    *amount = amount
                        .checked_add(o.commitment().amount)
                        .ok_or(EngineError::Node)?;
                    activity = true;
                    let image = self.image(&o)?;
                    if let Some(image) = &image {
                        image_index.insert(image.clone(), key.clone());
                    }
                    next.outputs.insert(
                        key,
                        OutputRecord {
                            bytes: hex::encode(o.serialize()),
                            height: h,
                            coinbase: o.transaction() == miner_hash,
                            image,
                            spent_height: None,
                        },
                    );
                }
                for (transaction, received_atomic) in received {
                    next.history.push(HistoryEntry {
                        transaction,
                        height: h,
                        received_atomic,
                        sent_atomic: None,
                        destination: None,
                    });
                }
                for image in spent {
                    if let Some(key) = image_index.remove(&image) {
                        activity = true;
                        next.mark_spent(&key, h)?;
                    }
                }
                let next_output = first.checked_add(count).ok_or(EngineError::Node)?;
                next.next_output = Some(next_output);
                next.checkpoints.push(Checkpoint {
                    height: h,
                    hash,
                    next_output,
                });
                if next.checkpoints.len() > 128 {
                    next.checkpoints.remove(0);
                }
                next.next_height = h + 1;
            }
            self.batch_size =
                batch::next(self.batch_size, chunk_started.elapsed()).min(self.batch_limit);
            // Receipt/spend/confirmation changes are checkpointed after this
            // read. Quiet history is coalesced up to 2,000 blocks or a target
            // of two seconds, stretched to 10x the last full-state save so a
            // large wallet spends at most ~10% of sync time rewriting its file.
            if activity || started.elapsed() >= Duration::from_secs(2).max(self.save_cost * 10) {
                break;
            }
        }
        self.check()?;
        next.strip_settled_payments();
        self.commit(next)?;
        self.synced = self.state.next_height == tip;
        self.status()
    }
}
