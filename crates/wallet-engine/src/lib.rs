//! Local Monero scanning/signing with explicitly selected Direct or Tor transport.
#![forbid(unsafe_code)]
mod engine;
mod state;
mod store;
pub mod transport;
mod verify;
pub(crate) use engine::valid_subaddress_label;
pub use engine::*;
pub use state::{HistoryEntry, PendingPayment, State};
#[cfg(test)]
mod keystone_tests;
#[cfg(test)]
mod tests;

#[derive(Debug, thiserror::Error)]
pub enum EngineError {
    #[error("The wallet is disconnected. Enable network access to reconnect.")]
    Disconnected,
    #[error("Another session is using this wallet. Lock it before reconnecting.")]
    Busy,
    #[error("The node returned invalid Monero RPC data. Check that its address and port are for a Monero node RPC service. [NODE_RPC]")]
    Node,
    #[error("{0}")]
    Transport(transport::NodeFailure),
    #[error("The wallet and node use different networks. Select a node for this wallet's Mainnet or Stagenet network. [NET_NETWORK]")]
    Network,
    #[error("The encrypted scan state could not be read or saved. It was kept for recovery.")]
    Storage,
    #[error("Device randomness is unavailable.")]
    Entropy,
    #[error("Sync to the current node tip before preparing or sending.")]
    SyncRequired,
    #[error("This view-only wallet cannot sign payments.")]
    ViewOnly,
    #[error("Enter an address on this wallet's network and a positive XMR amount.")]
    Payment,
    #[error("Insufficient unlocked XMR for the amount and fee. Use Max or a smaller amount.")]
    Funds,
    #[error("The node fee exceeds the safety limit.")]
    FeeLimit,
    #[error("Max needs more than 16 inputs. Send a smaller amount first.")]
    InputLimit,
    #[error(
        "A payment is awaiting confirmation. Retry its exact transaction or sync to reconcile it."
    )]
    Pending,
    #[error("The review expired or belongs to another session. Prepare it again.")]
    StaleReview,
    #[error("The signed transaction failed local cryptographic or payment verification.")]
    Verification,
    #[error(
        "Broadcast was not confirmed. Inputs remain reserved; retry only this same transaction."
    )]
    BroadcastUnknown,
    #[error("The restore height is beyond this node's chain tip.")]
    Height,
    #[error("This wallet already has recorded activity. Choose the same or an earlier scan start so saved funds and payments stay covered.")]
    ScanHistory,
    #[error("Choose an existing receiving address and a label of at most 64 characters. Up to 1000 subaddresses are supported.")]
    Subaddress,
}

impl EngineError {
    fn node_error(error: monero_wallet::interface::InterfaceError) -> Self {
        match transport::NodeFailure::from_interface(&error) {
            Some(transport::NodeFailure::Cancelled) => Self::Disconnected,
            Some(failure) => Self::Transport(failure),
            None => Self::Node,
        }
    }
}
