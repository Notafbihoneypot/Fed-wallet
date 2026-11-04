//! Cache public cumulative counts only; never rings or per-output responses.
use super::*;
use std::{
    ops::{Bound, RangeBounds},
    sync::Arc,
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
pub(super) struct CachedDecoys<'a, P> {
    pub rpc: &'a P,
    pub height: usize,
    pub counts: Arc<Vec<u64>>,
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
        self.rpc.unlocked_ringct_outputs(indexes, evaluate).await
    }
}
impl<T: HttpTransport> WalletEngine<T> {
    pub(super) async fn distribution_for_payment(
        &mut self,
        hash: [u8; 32],
    ) -> Result<Arc<Vec<u64>>, EngineError> {
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
        let mut page_blocks = PAGE_BLOCKS;
        loop {
            self.check()?;
            if let Some(old) = cache.as_ref() {
                self.preparation.saved_counts(old.counts.len());
                if old.height == height && old.complete {
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
            if self.decoy_source.is_some() {
                self.store.save_distribution(saved)?;
            }
            self.check()?;
            self.preparation.saved_counts(saved.counts.len());
        }
    }
}
