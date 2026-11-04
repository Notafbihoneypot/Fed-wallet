// Keep generated FFI separate so its unsafe boundary remains explicit.
use wallet_policy::{amount, connection::NodeEndpoint};

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum PolicyError {
    #[error("{detail}")]
    InvalidInput { detail: String },
}

#[derive(uniffi::Record)]
pub struct NodeDetails {
    pub normalized_url: String,
    pub onion: bool,
}

#[uniffi::export]
pub fn inspect_node(value: String) -> Result<NodeDetails, PolicyError> {
    let node = NodeEndpoint::parse(&value).map_err(|e| PolicyError::InvalidInput {
        detail: e.to_string(),
    })?;
    Ok(NodeDetails {
        normalized_url: node.as_str().into(),
        onion: node.is_onion(),
    })
}

#[uniffi::export]
pub fn normalize_amount(value: String) -> Result<String, PolicyError> {
    let amount = amount::parse_xmr(&value).map_err(|e| PolicyError::InvalidInput {
        detail: e.to_string(),
    })?;
    Ok(amount::format_xmr(amount))
}

#[uniffi::export]
pub fn foundation_version() -> String {
    env!("CARGO_PKG_VERSION").into()
}

mod offline;
pub use offline::*;
mod online;
pub use online::*;
mod receiving;
pub use receiving::*;
mod preparation;
pub use preparation::*;

mod pin;
pub use pin::{derive_pin_key, PinError};
