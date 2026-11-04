use std::{
    path::Path,
    sync::{Arc, Mutex, OnceLock},
};
use tokio::runtime::Runtime;
use tokio_util::sync::CancellationToken;
use wallet_engine::{transport::NodeTransport, EngineError, WalletEngine};
use wallet_policy::{
    amount::format_xmr,
    connection::{NetworkRoute, NodeEndpoint},
};

#[derive(Clone, Copy, uniffi::Enum)]
pub enum WalletNetworkRoute {
    Direct,
    Tor,
}
impl From<WalletNetworkRoute> for NetworkRoute {
    fn from(route: WalletNetworkRoute) -> Self {
        match route {
            WalletNetworkRoute::Direct => Self::Direct,
            WalletNetworkRoute::Tor => Self::Tor,
        }
    }
}
static RUNTIME: OnceLock<Runtime> = OnceLock::new();
fn runtime() -> Result<&'static Runtime, OnlineError> {
    if let Some(r) = RUNTIME.get() {
        return Ok(r);
    }
    let r = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .map_err(|_| OnlineError::Closed)?;
    let _ = RUNTIME.set(r);
    RUNTIME.get().ok_or(OnlineError::Closed)
}
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum OnlineError {
    #[error("The wallet connection is closed.")]
    Closed,
    #[error("{detail}")]
    Failed { detail: String },
    #[error("{detail}")]
    Payment { detail: String },
    #[error("The chain or review changed. Review the updated payment before authorizing again.")]
    ReviewChanged,
}
impl From<EngineError> for OnlineError {
    fn from(e: EngineError) -> Self {
        match e {
            EngineError::StaleReview | EngineError::SyncRequired => Self::ReviewChanged,
            EngineError::Payment
            | EngineError::Funds
            | EngineError::FeeLimit
            | EngineError::InputLimit
            | EngineError::Pending
            | EngineError::ViewOnly => Self::Payment {
                detail: e.to_string(),
            },
            _ => Self::Failed {
                detail: e.to_string(),
            },
        }
    }
}
#[derive(uniffi::Record)]
pub struct WalletTransfer {
    pub transaction: String,
    pub height: u64,
    pub received_xmr: String,
    pub sent_xmr: Option<String>,
    pub destination: Option<String>,
}
#[derive(uniffi::Record)]
pub struct PendingTransfer {
    pub transaction: String,
    pub destination: String,
    pub amount_xmr: String,
    pub fee_xmr: String,
}
#[derive(uniffi::Record)]
pub struct WalletPayment {
    pub transaction: String,
    pub destination: String,
    pub amount_xmr: String,
    pub fee_xmr: String,
    pub total_xmr: String,
    pub mined_height: Option<u64>,
    pub confirmations: u64,
}
#[derive(uniffi::Record)]
pub struct WalletSyncStatus {
    pub has_scan: bool,
    pub start_height: u64,
    pub next_height: u64,
    pub target_height: u64,
    pub synced: bool,
    pub view_only: bool,
    pub received_xmr: String,
    pub balance_xmr: Option<String>,
    pub unlocked_xmr: Option<String>,
    pub next_unlock_blocks: Option<u64>,
    pub history: Vec<WalletTransfer>,
    pub pending: Option<PendingTransfer>,
    pub payments: Vec<WalletPayment>,
}
impl From<wallet_engine::SyncStatus> for WalletSyncStatus {
    fn from(s: wallet_engine::SyncStatus) -> Self {
        Self {
            has_scan: s.has_scan,
            start_height: s.start_height,
            next_height: s.next_height,
            target_height: s.target_height,
            synced: s.synced,
            view_only: s.view_only,
            received_xmr: format_xmr(s.received_atomic),
            balance_xmr: s.balance_atomic.map(format_xmr),
            unlocked_xmr: s.unlocked_atomic.map(format_xmr),
            next_unlock_blocks: s.next_unlock_blocks,
            history: s
                .history
                .into_iter()
                .map(|e| WalletTransfer {
                    transaction: e.transaction,
                    height: e.height,
                    received_xmr: format_xmr(e.received_atomic),
                    sent_xmr: e.sent_atomic.map(format_xmr),
                    destination: e.destination,
                })
                .collect(),
            payments: s
                .payments
                .into_iter()
                .map(|p| WalletPayment {
                    transaction: p.transaction,
                    destination: p.destination,
                    amount_xmr: format_xmr(p.amount_atomic),
                    fee_xmr: format_xmr(p.fee_atomic),
                    total_xmr: format_xmr(p.total_atomic),
                    mined_height: p.mined_height,
                    confirmations: p.confirmations,
                })
                .collect(),
            pending: s.pending.as_ref().map(|p| PendingTransfer {
                transaction: p.transaction.clone(),
                destination: p.destination.clone(),
                amount_xmr: format_xmr(p.amount_atomic),
                fee_xmr: format_xmr(p.fee_atomic),
            }),
        }
    }
}
#[derive(uniffi::Record)]
pub struct SpendReview {
    pub send_max: bool,
    pub review_id: String,
    pub destination: String,
    pub amount_xmr: String,
    pub fee_xmr: String,
    pub transaction: String,
    pub total_xmr: String,
}
impl TryFrom<wallet_engine::PaymentReview> for SpendReview {
    type Error = EngineError;
    fn try_from(r: wallet_engine::PaymentReview) -> Result<Self, Self::Error> {
        Ok(Self {
            send_max: r.send_max,
            review_id: r.review_id,
            destination: r.destination,
            amount_xmr: format_xmr(r.amount_atomic),
            fee_xmr: format_xmr(r.fee_atomic),
            transaction: r.transaction,
            total_xmr: format_xmr(
                r.amount_atomic
                    .checked_add(r.fee_atomic)
                    .ok_or(EngineError::Verification)?,
            ),
        })
    }
}
#[derive(uniffi::Object)]
pub struct OnlineWalletSession {
    engine: Mutex<Option<WalletEngine<NodeTransport>>>,
    node: String,
    pub(crate) directory: String,
    route: WalletNetworkRoute,
    cancel: CancellationToken,
    preparation: OnceLock<Arc<wallet_engine::PreparationProgress>>,
}
impl OnlineWalletSession {
    /// Register this cancellable shell with its owner before loading any cache.
    pub(crate) fn uninitialized(
        node: String,
        directory: &str,
        route: WalletNetworkRoute,
    ) -> Result<Arc<Self>, OnlineError> {
        let e = NodeEndpoint::parse(&node).map_err(|_| OnlineError::Failed {
            detail: "Choose a valid node first.".into(),
        })?;
        if !e.allows_route(route.into()) {
            return Err(OnlineError::Failed{detail:"Direct requires a normal HTTP or HTTPS node. Tor requires a v3 onion or HTTPS node.".into()});
        }
        if directory.len() > 4096 || !Path::new(directory).is_absolute() {
            return Err(OnlineError::Closed);
        }
        Ok(Arc::new(Self {
            engine: Mutex::new(None),
            node,
            directory: directory.to_owned(),
            route,
            cancel: CancellationToken::new(),
            preparation: OnceLock::new(),
        }))
    }
    /// A cancellable local cache child, with no endpoint, runtime or daemon.
    pub(crate) fn local_uninitialized(directory: &str) -> Result<Arc<Self>, OnlineError> {
        if directory.len() > 4096 || !Path::new(directory).is_absolute() {
            return Err(OnlineError::Closed);
        }
        Ok(Arc::new(Self {
            engine: Mutex::new(None),
            node: String::new(),
            directory: directory.to_owned(),
            route: WalletNetworkRoute::Direct,
            cancel: CancellationToken::new(),
            preparation: OnceLock::new(),
        }))
    }
    pub(crate) fn initialize(
        &self,
        snapshot: zeroize::Zeroizing<Vec<u8>>,
        directory: &str,
    ) -> Result<(), OnlineError> {
        {
            let mut engine = self.engine.lock().map_err(|_| OnlineError::Closed)?;
            if self.cancel.is_cancelled() || engine.is_some() {
                return Err(OnlineError::Closed);
            }
            // This bounded copy stays in Rust and is wiped after reconstruction.
            // Neither seed derivation nor disk/cache validation holds the owner lock.
            let wallet = wallet_keys::OfflineWallet::from_snapshot(snapshot)
                .map_err(|_| OnlineError::Closed)?;
            let initialized =
                WalletEngine::new(&wallet, Path::new(directory), self.cancel.clone())?;
            self.preparation
                .set(initialized.preparation_progress())
                .map_err(|_| OnlineError::Closed)?;
            if self.cancel.is_cancelled() {
                return Err(OnlineError::Closed);
            }
            *engine = Some(initialized);
        }
        // Check after releasing the mutex: if disconnect's try_lock lost a race
        // during publication, this path still destroys the cancelled engine.
        if self.cancel.is_cancelled() {
            self.disconnect();
            return Err(OnlineError::Closed);
        }
        Ok(())
    }
    fn call<T>(
        &self,
        operation: impl FnOnce(&mut WalletEngine<NodeTransport>) -> Result<T, EngineError>,
    ) -> Result<T, OnlineError> {
        if self.cancel.is_cancelled() {
            return Err(OnlineError::Closed);
        }
        let mut g = self.engine.lock().map_err(|_| OnlineError::Closed)?;
        if self.cancel.is_cancelled() {
            return Err(OnlineError::Closed);
        }
        let result = operation(g.as_mut().ok_or(OnlineError::Closed)?);
        if self.cancel.is_cancelled() {
            *g = None;
            return Err(OnlineError::Closed);
        }
        result.map_err(Into::into)
    }
}
#[uniffi::export]
impl OnlineWalletSession {
    /// Addresses come from Android's resolver after explicit Direct consent.
    /// This API never changes a session's route or its HTTP/TLS authority.
    pub fn connect_direct_resolved(&self, addresses: Vec<String>) -> Result<(), OnlineError> {
        if !matches!(self.route, WalletNetworkRoute::Direct) {
            return Err(OnlineError::Failed {
                detail: wallet_engine::transport::NodeFailure::Address.to_string(),
            });
        }
        let rt = runtime()?;
        self.call(|engine| rt.block_on(engine.connect_direct_resolved(&self.node, &addresses)))
    }

    pub fn connect(&self) -> Result<(), OnlineError> {
        let rt = runtime()?;
        self.call(|e| rt.block_on(e.connect(&self.node, self.route.into())))
    }
    pub fn network_route(&self) -> WalletNetworkRoute {
        self.route
    }
    /// Local atomics only: never blocks on a network request/engine mutex.
    pub fn preparation_status(&self) -> Result<crate::PreparationStatus, OnlineError> {
        if self.cancel.is_cancelled() {
            return Err(OnlineError::Closed);
        }
        let status = self
            .preparation
            .get()
            .ok_or(OnlineError::Closed)?
            .snapshot();
        if self.cancel.is_cancelled() {
            return Err(OnlineError::Closed);
        }
        Ok(status.into())
    }
    pub fn poll_updates(&self) -> Result<WalletSyncStatus, OnlineError> {
        let rt = runtime()?;
        self.call(|e| rt.block_on(e.poll_updates()).map(Into::into))
    }
    pub fn sync_batch(&self) -> Result<WalletSyncStatus, OnlineError> {
        let rt = runtime()?;
        self.call(|e| rt.block_on(e.sync_batch()).map(Into::into))
    }
    pub fn status(&self) -> Result<WalletSyncStatus, OnlineError> {
        self.call(|e| e.status().map(Into::into))
    }
    pub fn receive_address(&self, index: u32) -> Result<String, OnlineError> {
        self.call(|e| e.receive_address(index))
    }
    pub fn receiving_addresses(&self) -> Result<crate::ReceivingAddressBook, OnlineError> {
        self.call(|e| e.receiving_addresses().map(Into::into))
    }
    pub fn create_subaddress(
        &self,
        label: String,
    ) -> Result<crate::ReceivingAddressBook, OnlineError> {
        self.call(|e| e.create_subaddress(&label).map(Into::into))
    }
    pub fn select_subaddress(
        &self,
        index: u32,
    ) -> Result<crate::ReceivingAddressBook, OnlineError> {
        self.call(|e| e.select_subaddress(index).map(Into::into))
    }
    pub fn label_subaddress(
        &self,
        index: u32,
        label: String,
    ) -> Result<crate::ReceivingAddressBook, OnlineError> {
        self.call(|e| e.label_subaddress(index, &label).map(Into::into))
    }
    pub fn prepare(&self, destination: String, xmr: String) -> Result<SpendReview, OnlineError> {
        let rt = runtime()?;
        self.call(|e| {
            let r = rt.block_on(e.prepare(&destination, &xmr))?;
            r.try_into()
        })
    }
    pub fn prepare_max(&self, destination: String) -> Result<SpendReview, OnlineError> {
        let rt = runtime()?;
        self.call(|e| rt.block_on(e.prepare_max(&destination))?.try_into())
    }
    pub fn discard_review(&self) -> Result<(), OnlineError> {
        self.call(|e| {
            e.discard_review();
            Ok(())
        })
    }
    pub fn confirm(&self, review_id: String) -> Result<String, OnlineError> {
        let rt = runtime()?;
        self.call(|e| rt.block_on(e.confirm(&review_id)))
    }
    pub fn retry_pending(&self, transaction: String) -> Result<String, OnlineError> {
        let rt = runtime()?;
        self.call(|e| rt.block_on(e.retry_pending(&transaction)))
    }
    pub fn rescan(&self, height: u64) -> Result<(), OnlineError> {
        self.call(|e| e.rescan(height))
    }
    /// Nonblocking cancellation; the in-flight operation drops node streams and keys.
    pub fn disconnect(&self) {
        self.cancel.cancel();
        if let Ok(mut g) = self.engine.try_lock() {
            *g = None;
        }
    }
}
impl Drop for OnlineWalletSession {
    fn drop(&mut self) {
        self.disconnect();
    }
}

#[cfg(test)]
mod progress_tests {
    use super::*;
    #[test]
    fn progress_does_not_wait_for_the_engine_mutex_and_is_revoked_on_disconnect() {
        let directory = tempfile::tempdir().unwrap();
        let session =
            OnlineWalletSession::local_uninitialized(directory.path().to_str().unwrap()).unwrap();
        assert!(session.preparation.set(Arc::default()).is_ok());
        let guard = session.engine.lock().unwrap();
        let (sender, receiver) = std::sync::mpsc::channel();
        let child = session.clone();
        let worker = std::thread::spawn(move || sender.send(child.preparation_status()).unwrap());
        let status = receiver.recv_timeout(std::time::Duration::from_secs(1));
        drop(guard);
        worker.join().unwrap();
        let status = status.unwrap().unwrap();
        assert!(matches!(status.stage, crate::PreparationStage::Idle));
        assert_eq!(status.cached_bytes, 0);
        session.disconnect();
        assert!(matches!(
            session.preparation_status(),
            Err(OnlineError::Closed)
        ));
    }
}
