#[derive(Clone, Copy, uniffi::Enum)]
pub enum PreparationStage {
    Idle,
    CheckingChain,
    FetchingFee,
    FetchingDistribution,
    SelectingDecoys,
    Signing,
    Verifying,
    Ready,
}

#[derive(uniffi::Record)]
pub struct PreparationStatus {
    pub stage: PreparationStage,
    pub cached_bytes: u32,
}

impl From<wallet_engine::PreparationSnapshot> for PreparationStatus {
    fn from(status: wallet_engine::PreparationSnapshot) -> Self {
        use wallet_engine::PreparationStage as S;
        Self {
            cached_bytes: status.cached_bytes,
            stage: match status.stage {
                S::Idle => PreparationStage::Idle,
                S::CheckingChain => PreparationStage::CheckingChain,
                S::FetchingFee => PreparationStage::FetchingFee,
                S::FetchingDistribution => PreparationStage::FetchingDistribution,
                S::SelectingDecoys => PreparationStage::SelectingDecoys,
                S::Signing => PreparationStage::Signing,
                S::Verifying => PreparationStage::Verifying,
                S::Ready => PreparationStage::Ready,
            },
        }
    }
}
