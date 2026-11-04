//! Read-only progress must never wait for the engine's network/scan mutex.
use std::sync::atomic::{AtomicU32, AtomicU8, Ordering};

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
#[repr(u8)]
pub enum PreparationStage {
    #[default]
    Idle,
    CheckingChain,
    FetchingFee,
    FetchingDistribution,
    SelectingDecoys,
    Signing,
    Verifying,
    Ready,
}

pub struct PreparationSnapshot {
    pub stage: PreparationStage,
    pub cached_bytes: u32,
}

#[derive(Default)]
pub struct PreparationProgress {
    stage: AtomicU8,
    cached_bytes: AtomicU32,
}

impl PreparationProgress {
    pub(super) fn reset(&self) {
        self.cached_bytes.store(0, Ordering::Relaxed);
        self.set_stage(PreparationStage::Idle);
    }
    pub(super) fn set_stage(&self, stage: PreparationStage) {
        self.stage.store(stage as u8, Ordering::Release);
    }
    pub(super) fn saved_counts(&self, count: usize) {
        self.cached_bytes
            .store((count * 8) as u32, Ordering::Release);
    }
    pub fn snapshot(&self) -> PreparationSnapshot {
        let stage = match self.stage.load(Ordering::Acquire) {
            1 => PreparationStage::CheckingChain,
            2 => PreparationStage::FetchingFee,
            3 => PreparationStage::FetchingDistribution,
            4 => PreparationStage::SelectingDecoys,
            5 => PreparationStage::Signing,
            6 => PreparationStage::Verifying,
            7 => PreparationStage::Ready,
            _ => PreparationStage::Idle,
        };
        PreparationSnapshot {
            stage,
            cached_bytes: self.cached_bytes.load(Ordering::Acquire),
        }
    }
}

impl<T: super::HttpTransport> super::WalletEngine<T> {
    pub fn preparation_progress(&self) -> std::sync::Arc<PreparationProgress> {
        self.preparation.clone()
    }
}
