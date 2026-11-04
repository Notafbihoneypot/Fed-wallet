//! Fixed diagnostics only: never forward node bodies, URLs, certificates or OS text.
use monero_wallet::interface::InterfaceError;

#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
pub enum NodeFailure {
    #[error("The node address is not supported for the selected route. [NET_ADDRESS]")]
    Address,
    #[error("Network access was blocked. Check Network permission for this app in the Android profile you are using. [NET_PERMISSION]")]
    Permission,
    #[error("The node hostname could not be resolved. Check its address and your network connection. [NET_DNS]")]
    Dns,
    #[error("The node refused the connection. Check its RPC port and whether it is running. [NET_REFUSED]")]
    Refused,
    #[error(
        "The node could not be reached. Check the network connection and RPC port. [NET_CONNECT]"
    )]
    Connect,
    #[error("The node request timed out. Saved sync progress was kept. [NET_TIMEOUT]")]
    Timeout,
    #[error("HTTPS setup failed. Check that this RPC port supports HTTPS and has a valid certificate. [NET_TLS]")]
    Tls,
    #[error(
        "Tor could not start. Check your connection and whether Tor is blocked. [NET_TOR_START]"
    )]
    TorStart,
    #[error("Tor could not reach this node. Check its address; direct fallback is disabled. [NET_TOR_NODE]")]
    TorNode,
    #[error("The node closed the connection or returned an invalid HTTP response. Check its RPC port. [NET_HTTP]")]
    Http,
    #[error("The node returned HTTP status {0}. Check its RPC endpoint, access restrictions or rate limit. [NET_STATUS]")]
    Status(u16),
    #[error("The node sent unsupported compressed data despite a request for uncompressed data. [NET_ENCODING]")]
    Encoding,
    #[error("The node response exceeded the bounded response limit. Saved sync progress was kept. [NET_SIZE]")]
    Size,
    #[error("The node connection was cancelled. [NET_CANCELLED]")]
    Cancelled,
}

impl NodeFailure {
    pub(super) fn interface(self) -> InterfaceError {
        // The payload is exclusively a value of this closed enum, never input text.
        InterfaceError::InterfaceError(self.to_string())
    }

    pub(crate) fn from_interface(error: &InterfaceError) -> Option<Self> {
        let InterfaceError::InterfaceError(message) = error else {
            return None;
        };
        for failure in [
            Self::Address,
            Self::Permission,
            Self::Dns,
            Self::Refused,
            Self::Connect,
            Self::Timeout,
            Self::Tls,
            Self::TorStart,
            Self::TorNode,
            Self::Http,
            Self::Encoding,
            Self::Size,
            Self::Cancelled,
        ] {
            if *message == failure.to_string() {
                return Some(failure);
            }
        }
        // Status codes are public bounded integers. Never forward a reason phrase/body.
        let code = message
            .strip_prefix("The node returned HTTP status ")?
            .split('.')
            .next()?
            .parse::<u16>()
            .ok()?;
        let failure = Self::Status(code);
        ((100..600).contains(&code) && *message == failure.to_string()).then_some(failure)
    }

    pub(super) fn socket(error: &std::io::Error) -> Self {
        match error.kind() {
            std::io::ErrorKind::PermissionDenied => Self::Permission,
            std::io::ErrorKind::ConnectionRefused => Self::Refused,
            std::io::ErrorKind::TimedOut => Self::Timeout,
            _ => Self::Connect,
        }
    }

    pub(super) fn resolver(error: &std::io::Error) -> Self {
        match error.kind() {
            std::io::ErrorKind::PermissionDenied => Self::Permission,
            std::io::ErrorKind::TimedOut => Self::Timeout,
            std::io::ErrorKind::NetworkDown | std::io::ErrorKind::NetworkUnreachable => {
                Self::Connect
            }
            _ => Self::Dns,
        }
    }
}
