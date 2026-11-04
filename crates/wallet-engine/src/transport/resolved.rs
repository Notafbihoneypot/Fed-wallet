//! Bounded, session-only addresses from the Android system resolver.
use super::*;
use std::net::{IpAddr, SocketAddr};

impl NodeTransport {
    /// No I/O occurs here. The endpoint remains the sole HTTP/TLS authority.
    /// Addresses are never persisted or accepted for a Tor connection.
    pub fn direct_resolved(
        node: &str,
        addresses: &[String],
        cancel: CancellationToken,
    ) -> Result<Self, InterfaceError> {
        let endpoint = endpoint(node, NetworkRoute::Direct)?;
        if cancel.is_cancelled() {
            return Err(NodeFailure::Cancelled.interface());
        }
        if addresses.is_empty() || addresses.len() > 32 {
            return Err(NodeFailure::Address.interface());
        }
        let literal = match endpoint.host().ok_or_else(fail)? {
            Host::Ipv4(ip) => Some(IpAddr::V4(ip)),
            Host::Ipv6(ip) => Some(IpAddr::V6(ip)),
            Host::Domain(_) => None,
        };
        let port = endpoint.port_or_known_default().ok_or_else(fail)?;
        let mut sockets = Vec::with_capacity(addresses.len());
        for address in addresses {
            if address.len() > 45 {
                return Err(NodeFailure::Address.interface());
            }
            let ip = address
                .parse::<IpAddr>()
                .map_err(|_| NodeFailure::Address.interface())?;
            if literal.is_some_and(|expected| expected != ip) {
                return Err(NodeFailure::Address.interface());
            }
            let socket = SocketAddr::new(ip, port);
            if !sockets.contains(&socket) {
                sockets.push(socket);
            }
        }
        Ok(Self {
            connector: Connector::DirectResolved(sockets.into()),
            endpoint,
            cancel,
            pool: Default::default(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    #[tokio::test]
    async fn system_resolved_domain_connects_without_native_dns_and_keeps_http_authority() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let node = format!("http://selected-node.invalid:{port}/");
        let transport =
            NodeTransport::direct_resolved(&node, &["127.0.0.1".into()], CancellationToken::new())
                .unwrap();
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut bytes = [0; 4096];
            let n = stream.read(&mut bytes).await.unwrap();
            let request = String::from_utf8_lossy(&bytes[..n]).to_ascii_lowercase();
            assert!(request.starts_with("post /get_height http/1.1"));
            assert!(request.contains(&format!("host: selected-node.invalid:{port}\r\n")));
            stream
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 12\r\n\r\n{\"height\":2}")
                .await
                .unwrap();
        });
        let response = tokio::time::timeout(
            Duration::from_secs(3),
            transport.post("get_height", vec![], None),
        )
        .await
        .unwrap()
        .unwrap();
        assert_eq!(response, b"{\"height\":2}");
        server.await.unwrap();
    }

    #[tokio::test]
    async fn system_resolved_https_sends_tls_and_never_downgrades_to_http() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let node = format!(
            "https://selected-node.invalid:{}/",
            listener.local_addr().unwrap().port()
        );
        let transport =
            NodeTransport::direct_resolved(&node, &["127.0.0.1".into()], CancellationToken::new())
                .unwrap();
        assert_eq!(transport.endpoint.host_str(), Some("selected-node.invalid"));
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut record = [0; 5];
            stream.read_exact(&mut record).await.unwrap();
            assert_eq!(record[0], 0x16); // TLS handshake record, not an HTTP POST.
            let length = usize::from(u16::from_be_bytes([record[3], record[4]]));
            assert!(length <= 16 * 1024);
            let mut hello = vec![0; length];
            stream.read_exact(&mut hello).await.unwrap();
            // SNI must authenticate the selected domain, not the resolved IP.
            let name = b"selected-node.invalid";
            assert!(hello.windows(name.len()).any(|part| part == name));
            stream
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
                .await
                .unwrap();
        });
        let error = tokio::time::timeout(
            Duration::from_secs(3),
            transport.post("json_rpc", vec![], None),
        )
        .await
        .unwrap()
        .unwrap_err();
        assert_eq!(NodeFailure::from_interface(&error), Some(NodeFailure::Tls));
        server.await.unwrap();
    }

    #[test]
    fn resolved_addresses_cannot_change_literal_nodes_ports_or_routes() {
        let cancel = CancellationToken::new();
        for (node, addresses) in [
            ("http://node.invalid:18081/", vec![]),
            ("http://node.invalid:18081/", vec!["127.0.0.1".into(); 33]),
            ("http://127.0.0.1:18081/", vec!["127.0.0.2".into()]),
            ("http://[::1]:18081/", vec!["127.0.0.1".into()]),
            ("https://node.invalid/", vec!["other-node.invalid".into()]),
            ("http://node.invalid/", vec!["127.0.0.1:9999".into()]),
            ("http://node.invalid/", vec!["http://127.0.0.1/".into()]),
            ("http://node.invalid/", vec!["fe80::1%wlan0".into()]),
        ] {
            assert!(NodeTransport::direct_resolved(node, &addresses, cancel.clone()).is_err());
        }
        let onion = format!("http://{}.onion:18081/", "a".repeat(56));
        assert!(
            NodeTransport::direct_resolved(&onion, &["127.0.0.1".into()], cancel.clone()).is_err()
        );
        for (node, ip, port) in [
            ("http://127.0.0.1:18081/", "127.0.0.1", 18081),
            ("https://[::1]/", "::1", 443),
        ] {
            let transport =
                NodeTransport::direct_resolved(node, &[ip.into(), ip.into()], cancel.clone())
                    .unwrap();
            let Connector::DirectResolved(addresses) = transport.connector else {
                panic!()
            };
            assert_eq!(
                addresses.as_ref(),
                &[SocketAddr::new(ip.parse().unwrap(), port)]
            );
        }
        cancel.cancel();
        assert!(
            matches!(NodeTransport::direct_resolved("http://node.invalid/", &["127.0.0.1".into()], cancel), Err(error) if NodeFailure::from_interface(&error) == Some(NodeFailure::Cancelled))
        );
    }

    #[test]
    fn resolver_network_and_permission_errors_are_not_misreported_as_dns() {
        use std::io::{Error, ErrorKind};
        for (kind, expected) in [
            (ErrorKind::PermissionDenied, NodeFailure::Permission),
            (ErrorKind::TimedOut, NodeFailure::Timeout),
            (ErrorKind::NetworkDown, NodeFailure::Connect),
            (ErrorKind::NetworkUnreachable, NodeFailure::Connect),
            (ErrorKind::NotFound, NodeFailure::Dns),
        ] {
            assert_eq!(NodeFailure::resolver(&Error::from(kind)), expected);
        }
    }
}
