use crate::Error;
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
