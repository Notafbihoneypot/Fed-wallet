use crate::Error;
use std::sync::Arc;
use url::Url;

/// Syntax validation only. Tor must validate the onion service identity.
/// This type intentionally has no Debug implementation to discourage URL logging.
#[derive(Clone, PartialEq, Eq)]
pub struct NodeEndpoint(Url);

impl NodeEndpoint {
    pub fn parse(value: &str) -> Result<Self, Error> {
        if value.is_empty()
            || value.len() > 2048
            || !value.is_ascii()
            || value
                .bytes()
                .any(|c| c.is_ascii_whitespace() || c.is_ascii_control())
            || value.contains('\\')
        {
            return Err(Error::InvalidNode);
        }
        let url = Url::parse(value).map_err(|_| Error::InvalidNode)?;
        if !matches!(url.scheme(), "http" | "https")
            || url.host_str().is_none()
            || !matches!(url.path(), "" | "/")
            || url.query().is_some()
            || url.fragment().is_some()
            || url.port_or_known_default() == Some(0)
        {
            return Err(Error::InvalidNode);
        }
        if !url.username().is_empty() || url.password().is_some() {
            return Err(Error::InlineCredentials);
        }
        let host = url.host_str().ok_or(Error::InvalidNode)?;
        if let Some(label) = host.strip_suffix(".onion") {
            if label.len() != 56
                || !label
                    .bytes()
                    .all(|c| c.is_ascii_lowercase() || (b'2'..=b'7').contains(&c))
            {
                return Err(Error::InvalidNode);
            }
        } else if matches!(url.host(), Some(url::Host::Domain(_)))
            && (host.len() > 253
                || host.split('.').any(|label| {
                    label.is_empty()
                        || label.len() > 63
                        || label.starts_with('-')
                        || label.ends_with('-')
                        || !label
                            .bytes()
                            .all(|c| c.is_ascii_alphanumeric() || c == b'-')
                }))
        {
            return Err(Error::InvalidNode);
        }
        Ok(Self(url))
    }

    pub fn as_str(&self) -> &str {
        self.0.as_str()
    }
    pub fn is_onion(&self) -> bool {
        self.0.host_str().is_some_and(|h| h.ends_with(".onion"))
    }

    /// A route is explicit and immutable for the lifetime of a connection.
    pub fn allows_route(&self, route: NetworkRoute) -> bool {
        match route {
            NetworkRoute::Direct => !self.is_onion(),
            NetworkRoute::Tor => self.is_onion() || self.0.scheme() == "https",
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum NetworkRoute {
    Direct,
    Tor,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnectionState {
    Offline,
    StartingTor,
    Ready,
    Paused,
}

/// This ticket is a freshness marker, not a security credential. Adapters must
/// still use a real Arti connection; the marker cannot prove network routing.
#[derive(Clone)]
pub struct ConnectionTicket {
    owner: Arc<()>,
    epoch: u64,
    node: NodeEndpoint,
}

pub struct NetworkGate {
    owner: Arc<()>,
    node: Option<NodeEndpoint>,
    epoch: u64,
    state: ConnectionState,
}

impl Default for NetworkGate {
    fn default() -> Self {
        Self {
            owner: Arc::new(()),
            node: None,
            epoch: 0,
            state: ConnectionState::Offline,
        }
    }
}

impl NetworkGate {
    pub fn node(&self) -> Option<&NodeEndpoint> {
        self.node.as_ref()
    }
    pub fn state(&self) -> ConnectionState {
        self.state
    }

    fn invalidate(&mut self, next: ConnectionState) -> Result<(), Error> {
        // Close the gate before checking for the impossible normal-use overflow.
        self.state = ConnectionState::Paused;
        self.epoch = self.epoch.checked_add(1).ok_or(Error::StaleSession)?;
        self.state = next;
        Ok(())
    }

    pub fn select_node(&mut self, value: &str) -> Result<(), Error> {
        let node = NodeEndpoint::parse(value)?;
        self.invalidate(ConnectionState::Offline)?;
        self.node = Some(node);
        Ok(())
    }

    /// Called only after an explicit Connect action. Selection alone never
    /// starts Tor, DNS, a daemon constructor or another network operation.
    pub fn begin_connect(&mut self) -> Result<ConnectionTicket, Error> {
        let node = self.node.clone().ok_or(Error::NodeRequired)?;
        self.invalidate(ConnectionState::StartingTor)?;
        Ok(ConnectionTicket {
            owner: self.owner.clone(),
            epoch: self.epoch,
            node,
        })
    }

    fn check_ticket(&self, ticket: &ConnectionTicket) -> Result<(), Error> {
        if !Arc::ptr_eq(&ticket.owner, &self.owner)
            || ticket.epoch != self.epoch
            || self.node.as_ref() != Some(&ticket.node)
        {
            return Err(Error::StaleSession);
        }
        Ok(())
    }

    /// Adapter-only operation: call after actual Arti bootstrap succeeds.
    /// This is deliberately not exported to the Android UI through UniFFI.
    pub fn tor_ready(&mut self, ticket: &ConnectionTicket) -> Result<(), Error> {
        self.check_ticket(ticket)?;
        if self.state != ConnectionState::StartingTor {
            return Err(Error::TransportNotReady);
        }
        self.state = ConnectionState::Ready;
        Ok(())
    }

    pub fn authorize_request(&self, ticket: &ConnectionTicket) -> Result<&str, Error> {
        self.check_ticket(ticket)?;
        if self.state != ConnectionState::Ready {
            return Err(Error::TransportNotReady);
        }
        self.node
            .as_ref()
            .map(NodeEndpoint::as_str)
            .ok_or(Error::NodeRequired)
    }

    pub fn pause(&mut self) -> Result<(), Error> {
        self.invalidate(ConnectionState::Paused)
    }

    pub fn clear_node(&mut self) -> Result<(), Error> {
        self.invalidate(ConnectionState::Offline)?;
        self.node = None;
        Ok(())
    }
}
