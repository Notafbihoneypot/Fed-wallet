use super::*;

impl<T: HttpTransport> WalletEngine<T> {
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
        self.rollback_reorg(tip).await?;
        if self.state.next_height > tip {
            return Err(EngineError::Height);
        }
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
                let end = start.saturating_add(self.batch_size).min(window_end);
                match self
                    .rpc()?
                    .contiguous_scannable_blocks(start as usize..=(end - 1) as usize)
                    .await
                {
                    Ok(blocks) => break (end, blocks),
                    Err(error) => {
                        let error = EngineError::node_error(error);
                        if matches!(
                            error,
                            EngineError::Transport(crate::transport::NodeFailure::Size)
                        ) && self.batch_size > batch::MIN
                        {
                            // Retry only this read, on the same consented route/node,
                            // with fewer blocks and the original hard response cap.
                            self.batch_limit = (self.batch_size / 2).max(batch::MIN);
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
            for block in blocks {
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
                for p in &mut next.payments {
                    if block
                        .block
                        .transactions
                        .iter()
                        .any(|h| hex::encode(h) == p.transaction)
                    {
                        activity |= p.mined_height != Some(h);
                        p.mined_height = Some(h);
                    }
                }
                let mut received = BTreeMap::<String, u64>::new();
                for o in self
                    .scanner_cache
                    .as_mut()
                    .ok_or(EngineError::Storage)?
                    .scan(block)
                    .map_err(|_| EngineError::Node)?
                    .ignore_additional_timelock()
                {
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
                        let output = next.outputs.get_mut(&key).ok_or(EngineError::Storage)?;
                        activity = true;
                        output.spent_height = Some(h);
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
            // of two seconds; one slow node request can exceed that target.
            if activity || started.elapsed() >= Duration::from_secs(2) {
                break;
            }
        }
        self.check()?;
        self.commit(next)?;
        self.synced = self.state.next_height == tip;
        self.status()
    }
}
