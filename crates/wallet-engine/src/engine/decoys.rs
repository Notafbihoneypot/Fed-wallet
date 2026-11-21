//! Cache public cumulative counts only; never rings or per-output responses.
use super::*;
use std::{
    ops::{Bound, RangeBounds},
    sync::{
        atomic::{AtomicBool, AtomicUsize, Ordering},
        Arc,
    },
};
#[derive(Clone)]
pub(crate) struct DistributionCache {
    pub(crate) height: usize,
    pub(crate) hash: [u8; 32],
    pub(crate) counts: Arc<Vec<u64>>,
    pub(crate) complete: bool,
    pub(crate) source: [u8; 32],
}
pub(crate) const MAX_COUNTS: usize = (32 * 1024 * 1024) / 8;
// At most 1 MiB of counts per read, plus the upstream bounded EPEE overhead.
const PAGE_BLOCKS: usize = 128 * 1024;
const MIN_PAGE_BLOCKS: usize = 4 * 1024;
/// One year of blocks, the window upstream uses to estimate the output rate.
/// A history that ends before block 0 must be at least this long: a node
/// cannot pass off its most recent blocks as the whole RingCT history.
pub(crate) const MIN_HISTORY_BLOCKS: usize = 365 * 720;
/// Unusable candidates tolerated per input. Each one costs a replacement
/// request that cannot contain the real output, so a node reporting many of
/// them would learn which first-round candidates the real spend is among.
pub(super) const MAX_UNUSABLE_CANDIDATES: usize = 2;

impl DistributionCache {
    pub(crate) fn validate(&self) -> Result<(), EngineError> {
        if self.counts.is_empty()
            || self.counts.len() > MAX_COUNTS
            || self.height > u32::MAX as usize
            || self.counts.len() > self.height + 1
            || self.counts.windows(2).any(|w| w[0] > w[1])
        {
            return Err(EngineError::Node);
        }
        if self.complete && self.first_height() > 0 && self.counts.len() < MIN_HISTORY_BLOCKS {
            return Err(EngineError::DecoyData);
        }
        Ok(())
    }
    fn first_height(&self) -> usize {
        self.height + 1 - self.counts.len()
    }
    fn prepend(&mut self, from: usize, mut page: Vec<u64>) -> Result<(), EngineError> {
        let end = self.first_height();
        if page.is_empty() || page.len() > end - from + 1 || page.last() != self.counts.first() {
            return Err(EngineError::Node);
        }
        let complete = from == 0 || page.len() < end - from + 1;
        page.pop(); // The overlapped boundary count must agree exactly.
        if self.counts.len() + page.len() > MAX_COUNTS {
            return Err(EngineError::Node);
        }
        page.extend_from_slice(&self.counts);
        self.counts = Arc::new(page);
        self.complete = complete;
        self.validate()
    }
    fn append(&mut self, end: usize, page: Vec<u64>, hash: [u8; 32]) -> Result<(), EngineError> {
        if end <= self.height
            || page.len() != end - self.height + 1
            || page.first() != self.counts.last()
        {
            return Err(EngineError::Node);
        }
        if self.counts.len() + page.len() - 1 > MAX_COUNTS {
            return Err(EngineError::Node);
        }
        Arc::make_mut(&mut self.counts).extend_from_slice(&page[1..]);
        self.height = end;
        self.hash = hash;
        self.validate()
    }
}
/// Decoy data for one input: locally checked counts, and the node's replies
/// about candidate ring members with a limit on how many may be unusable.
pub(super) struct CachedDecoys<'a, P> {
    rpc: &'a P,
    height: usize,
    counts: Arc<Vec<u64>>,
    unusable: AtomicUsize,
    refused: AtomicBool,
}
impl<'a, P> CachedDecoys<'a, P> {
    pub(super) fn new(rpc: &'a P, height: usize, counts: Arc<Vec<u64>>) -> Self {
        Self {
            rpc,
            height,
            counts,
            unusable: AtomicUsize::new(0),
            refused: AtomicBool::new(false),
        }
    }
    /// True once this input's selection was stopped by the unusable-candidate limit.
    pub(super) fn refused(&self) -> bool {
        self.refused.load(Ordering::SeqCst)
    }
}
impl<P: ProvidesDecoys> ProvidesBlockchainMeta for CachedDecoys<'_, P> {
    async fn latest_block_number(&self) -> Result<usize, InterfaceError> {
        Ok(self.height)
    }
}
impl<P: ProvidesDecoys> ProvidesDecoys for CachedDecoys<'_, P> {
    async fn ringct_output_distribution(
        &self,
        range: impl Send + RangeBounds<usize>,
    ) -> Result<Vec<u64>, InterfaceError> {
        if !matches!(range.start_bound(), Bound::Unbounded | Bound::Included(0))
            || range.end_bound() != Bound::Included(&self.height)
        {
            return Err(InterfaceError::InvalidInterface(
                "unsupported cached count range".into(),
            ));
        }
        Ok(self.counts.as_ref().clone())
    }
    async fn unlocked_ringct_outputs(
        &self,
        indexes: &[u64],
        evaluate: EvaluateUnlocked,
    ) -> Result<Vec<Option<[Point; 2]>>, TransactionsError> {
        let outputs = self.rpc.unlocked_ringct_outputs(indexes, evaluate).await?;
        // Count what upstream will discard: locked or undecodable candidates,
        // and those it skips for torsion or an identity key.
        let unusable = outputs
            .iter()
            .filter(|output| match output {
                None => true,
                Some([key, commitment]) => {
                    !(*key).into().is_torsion_free()
                        || !(*commitment).into().is_torsion_free()
                        || (*key).into().is_identity()
                }
            })
            .count();
        if self.unusable.fetch_add(unusable, Ordering::SeqCst) + unusable > MAX_UNUSABLE_CANDIDATES
        {
            self.refused.store(true, Ordering::SeqCst);
            return Err(InterfaceError::InvalidInterface(
                "too many unusable decoy candidates".into(),
            )
            .into());
        }
        Ok(outputs)
    }
}
impl<T: HttpTransport + Send + 'static> WalletEngine<T> {
    /// Counts for decoy selection at the wallet's scanned tip, accepted only
    /// if they agree with what this wallet counted and holds itself.
    pub(super) async fn distribution_for_payment(
        &mut self,
        hash: [u8; 32],
    ) -> Result<Arc<Vec<u64>>, EngineError> {
        let result = self.load_distribution(hash).await;
        if matches!(result, Err(EngineError::DecoyData)) {
            // Keep nothing from a node whose counts contradict this wallet's scan.
            self.decoy_cache = None;
            let _ = self.store.clear_distribution();
        }
        result
    }
    /// The node's counts are public but unauthenticated. This wallet counted
    /// the RingCT outputs of its most recent scanned blocks itself and knows
    /// the chain index and block of every output it owns; counts that disagree
    /// with either would misplace decoys, so they are refused.
    fn check_counts(&self, cache: &DistributionCache) -> Result<(), EngineError> {
        let first = cache.first_height() as u64;
        let at = |height: u64| {
            height
                .checked_sub(first)
                .and_then(|position| usize::try_from(position).ok())
                .and_then(|position| cache.counts.get(position).copied())
        };
        let tip = self
            .state
            .checkpoints
            .last()
            .ok_or(EngineError::SyncRequired)?;
        if tip.height != cache.height as u64 {
            return Err(EngineError::SyncRequired);
        }
        for checkpoint in &self.state.checkpoints {
            let agrees = if checkpoint.height < first {
                // Nothing may have been counted before the claimed first block.
                checkpoint.next_output == 0
            } else {
                at(checkpoint.height) == Some(checkpoint.next_output)
            };
            if !agrees {
                return Err(EngineError::DecoyData);
            }
        }
        for record in self.state.outputs.values() {
            let index = decode_output(record)?.index_on_blockchain();
            let before = match record.height.checked_sub(1) {
                Some(previous) if previous >= first => at(previous),
                _ => Some(0),
            };
            let within = match (before, at(record.height)) {
                (Some(before), Some(through)) => before <= index && index < through,
                _ => false,
            };
            if !within {
                return Err(EngineError::DecoyData);
            }
        }
        Ok(())
    }
    async fn load_distribution(&mut self, hash: [u8; 32]) -> Result<Arc<Vec<u64>>, EngineError> {
        let height = usize::try_from(
            self.state
                .next_height
                .checked_sub(1)
                .ok_or(EngineError::SyncRequired)?,
        )
        .map_err(|_| EngineError::Node)?;
        // Optional acceleration data is separate from the funds/payment journal.
        // An invalid cache is a miss; it must never make that journal unreadable.
        let previous = self.decoy_cache.take().or_else(|| {
            self.decoy_source
                .and_then(|_| self.store.load_distribution().ok().flatten())
        });
        let mut cache = None;
        if let Some(old) = previous.filter(|c| {
            c.height <= height
                && c.source == self.decoy_source.unwrap_or([0; 32])
                && c.validate().is_ok()
        }) {
            let canonical = if old.height == height {
                hash
            } else {
                self.rpc()?
                    .block_hash(old.height)
                    .await
                    .map_err(EngineError::node_error)?
            };
            if canonical == old.hash {
                cache = Some(old);
            }
        }
        // Each save rewrites the whole file, so save at doubling sizes and once
        // complete. This optional cache must never block a payment: stop saving
        // after the first failure and keep the validated counts in memory.
        let mut persist = self.decoy_source.is_some();
        let mut saved_len = cache.as_ref().map_or(0, |c| c.counts.len());
        let mut page_blocks = PAGE_BLOCKS;
        loop {
            self.check()?;
            if let Some(old) = cache.as_ref() {
                self.preparation.saved_counts(old.counts.len());
                if old.height == height && old.complete {
                    self.check_counts(old)?;
                    let counts = old.counts.clone();
                    self.decoy_cache = cache;
                    return Ok(counts);
                }
            }
            // Start at the tip and work backwards. A range wholly before RingCT
            // activation is not accepted by the upstream RPC parser. A shortened
            // oldest page identifies the same activation boundary as a full read.
            let (from, end, forward) = match cache.as_ref() {
                Some(old) if old.height < height => {
                    (old.height, height.min(old.height + page_blocks - 1), true)
                }
                Some(old) => {
                    let end = old.first_height();
                    (end.saturating_sub(page_blocks - 1), end, false)
                }
                None => (height.saturating_sub(page_blocks - 1), height, false),
            };
            let page_hash = if end == height {
                hash
            } else {
                self.rpc()?
                    .block_hash(end)
                    .await
                    .map_err(EngineError::node_error)?
            };
            let page = match self.rpc()?.ringct_output_distribution(from..=end).await {
                Ok(page) => page,
                Err(error) => {
                    let error = EngineError::node_error(error);
                    if page_blocks > MIN_PAGE_BLOCKS
                        && matches!(
                            error,
                            EngineError::Transport(
                                crate::transport::NodeFailure::Timeout
                                    | crate::transport::NodeFailure::Size
                            )
                        )
                    {
                        // Retry only a public-count read, on the same node/route.
                        // Neither output/ring requests nor broadcasts are replayed.
                        page_blocks = (page_blocks / 2).max(MIN_PAGE_BLOCKS);
                        continue;
                    }
                    return Err(error);
                }
            };
            self.check()?;
            let anchor_height = if forward { end } else { height };
            let anchor_hash = if forward { page_hash } else { hash };
            if self
                .rpc()?
                .block_hash(anchor_height)
                .await
                .map_err(EngineError::node_error)?
                != anchor_hash
            {
                return Err(EngineError::SyncRequired);
            }
            match cache.as_mut() {
                Some(old) if forward => old.append(end, page, page_hash)?,
                Some(old) => old.prepend(from, page)?,
                None => {
                    let complete = from == 0 || page.len() < end - from + 1;
                    let first = DistributionCache {
                        height,
                        hash,
                        counts: Arc::new(page),
                        complete,
                        source: self.decoy_source.unwrap_or([0; 32]),
                    };
                    first.validate()?;
                    if first.counts.len() > end - from + 1 {
                        return Err(EngineError::Node);
                    }
                    cache = Some(first);
                }
            }
            self.check()?;
            let saved = cache.as_ref().ok_or(EngineError::Node)?;
            if persist && (saved.complete || saved.counts.len() >= saved_len.saturating_mul(2)) {
                persist = self.store.save_distribution(saved).is_ok();
                saved_len = saved.counts.len();
            }
            self.check()?;
            self.preparation.saved_counts(saved.counts.len());
        }
    }
}
