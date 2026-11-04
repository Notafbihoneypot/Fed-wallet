//! Explicit direct or Tor transport. No redirects, compression or route fallback.
use arti_client::{config::TorClientConfigBuilder, TorClient};
use bytes::Bytes;
use http_body_util::{BodyExt, Full};
use hyper::{client::conn::http1, Request};
use hyper_util::rt::TokioIo;
use monero_daemon_rpc::HttpTransport;
use monero_wallet::interface::InterfaceError;
use std::{path::Path, sync::Arc, time::Duration};
use tokio::{
    io::{AsyncRead, AsyncWrite},
    net::{lookup_host, TcpStream},
};
use tokio_util::{
    compat::FuturesAsyncReadCompatExt, sync::CancellationToken, task::AbortOnDropHandle,
};
use tor_rtcompat::PreferredRuntime;
use url::{Host, Url};
use wallet_policy::connection::{NetworkRoute, NodeEndpoint};
mod connection;
use connection::HttpConnection;
mod failure;
#[cfg(test)]
mod pool_tests;
mod resolved;
pub use failure::NodeFailure;
const MAX_RESPONSE: usize = 32 * 1024 * 1024;
fn fail() -> InterfaceError {
    NodeFailure::Http.interface()
}
fn endpoint(value: &str, route: NetworkRoute) -> Result<Url, InterfaceError> {
    let node = NodeEndpoint::parse(value).map_err(|_| NodeFailure::Address.interface())?;
    if !node.allows_route(route) {
        return Err(NodeFailure::Address.interface());
    }
    Url::parse(node.as_str()).map_err(|_| fail())
}
fn route_allowed(route: &str) -> bool {
    matches!(
        route,
        "json_rpc"
            | "get_transactions"
            | "get_outs.bin"
            | "get_o_indexes.bin"
            | "get_blocks.bin"
            | "get_output_distribution.bin"
            | "get_height"
            | "send_raw_transaction"
    )
}
#[derive(Clone)]
pub struct NodeTransport {
    connector: Connector,
    endpoint: Url,
    cancel: CancellationToken,
    pool: Arc<tokio::sync::Mutex<Option<HttpConnection>>>,
}
#[derive(Clone)]
enum Connector {
    Direct,
    DirectResolved(Arc<[std::net::SocketAddr]>),
    Tor(Arc<TorClient<PreferredRuntime>>),
}
impl NodeTransport {
    pub async fn connect(
        node: &str,
        route: NetworkRoute,
        directory: &Path,
        cancel: CancellationToken,
    ) -> Result<Self, InterfaceError> {
        let endpoint = endpoint(node, route)?;
        if cancel.is_cancelled() {
            return Err(NodeFailure::Cancelled.interface());
        }
        // Direct startup must not construct an Arti client or bootstrap Tor.
        if route == NetworkRoute::Direct {
            return Ok(Self {
                connector: Connector::Direct,
                endpoint,
                cancel,
                pool: Default::default(),
            });
        }
        let config = TorClientConfigBuilder::from_directories(
            directory.join("tor-state"),
            directory.join("tor-cache"),
        )
        .build()
        .map_err(|_| NodeFailure::TorStart.interface())?;
        let tor = tokio::select! {biased;_=cancel.cancelled()=>return Err(NodeFailure::Cancelled.interface()),r=tokio::time::timeout(Duration::from_secs(180),TorClient::create_bootstrapped(config))=>r.map_err(|_|NodeFailure::Timeout.interface())?.map_err(|_|NodeFailure::TorStart.interface())?};
        Ok(Self {
            connector: Connector::Tor(tor.isolated_client()),
            endpoint,
            cancel,
            pool: Default::default(),
        })
    }
    async fn request(
        &self,
        route: &str,
        body: Vec<u8>,
        limit: usize,
    ) -> Result<Vec<u8>, InterfaceError> {
        // Keep the checkout local: cancellation, timeout, malformed/oversize
        // responses and HTTP errors all drop it instead of returning it to use.
        let mut pooled = self.pool.lock().await;
        let mut current = pooled.take();
        if let Some(connection) = current.as_mut() {
            if !connection.ready().await {
                current = None;
            }
        }
        let mut connection = match current {
            Some(connection) => connection,
            None => self.open().await?,
        };
        let (response, reusable) = connection.post(&self.endpoint, route, body, limit).await?;
        if self.cancel.is_cancelled() {
            return Err(NodeFailure::Cancelled.interface());
        }
        if reusable {
            *pooled = Some(connection);
        }
        Ok(response)
    }
    async fn open(&self) -> Result<HttpConnection, InterfaceError> {
        let host = match self.endpoint.host().ok_or_else(fail)? {
            Host::Domain(h) => h.to_owned(),
            Host::Ipv4(ip) => ip.to_string(),
            Host::Ipv6(ip) => ip.to_string(),
        };
        let port = self.endpoint.port_or_known_default().ok_or_else(fail)?;
        match &self.connector {
            Connector::Direct => {
                // Only the explicit direct route may use the system resolver.
                let addresses: Vec<_> = lookup_host((host.as_str(), port))
                    .await
                    .map_err(|e| NodeFailure::resolver(&e).interface())?
                    .take(32)
                    .collect();
                if addresses.is_empty() {
                    return Err(NodeFailure::Dns.interface());
                }
                let stream = TcpStream::connect(addresses.as_slice())
                    .await
                    .map_err(|e| NodeFailure::socket(&e).interface())?;
                self.authenticate(stream, host).await
            }
            Connector::DirectResolved(addresses) => {
                // The Android platform resolved this exact node for this live
                // session. Keep the original hostname for HTTP and TLS; never
                // perform a second, opaque libc lookup or choose another node.
                let stream = TcpStream::connect(addresses.as_ref())
                    .await
                    .map_err(|e| NodeFailure::socket(&e).interface())?;
                self.authenticate(stream, host).await
            }
            Connector::Tor(tor) => {
                // Hostname goes directly to Arti. Never resolve locally here.
                let stream = tor
                    .connect((host.as_str(), port))
                    .await
                    .map_err(|_| NodeFailure::TorNode.interface())?
                    .compat();
                self.authenticate(stream, host).await
            }
        }
    }
    async fn authenticate<S>(
        &self,
        stream: S,
        host: String,
    ) -> Result<HttpConnection, InterfaceError>
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        if self.endpoint.scheme() == "https" {
            let roots =
                rustls::RootCertStore::from_iter(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
            let tls = rustls::ClientConfig::builder_with_provider(Arc::new(
                rustls::crypto::ring::default_provider(),
            ))
            .with_safe_default_protocol_versions()
            .map_err(|_| fail())?
            .with_root_certificates(roots)
            .with_no_client_auth();
            let name = rustls::pki_types::ServerName::try_from(host).map_err(|_| fail())?;
            let stream = tokio_rustls::TlsConnector::from(Arc::new(tls))
                .connect(name, stream)
                .await
                .map_err(|_| NodeFailure::Tls.interface())?;
            HttpConnection::open(stream, self.cancel.clone()).await
        } else {
            HttpConnection::open(stream, self.cancel.clone()).await
        }
    }
    #[cfg(test)]
    async fn http<S>(
        endpoint: &Url,
        stream: S,
        route: &str,
        body: Vec<u8>,
        limit: usize,
    ) -> Result<Vec<u8>, InterfaceError>
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        HttpConnection::open(stream, CancellationToken::new())
            .await?
            .post(endpoint, route, body, limit)
            .await
            .map(|(body, _)| body)
    }
}
impl HttpTransport for NodeTransport {
    async fn post(
        &self,
        route: &str,
        body: Vec<u8>,
        response_size_limit: Option<usize>,
    ) -> Result<Vec<u8>, InterfaceError> {
        if !route_allowed(route) {
            return Err(fail());
        }
        let limit = response_size_limit
            .unwrap_or(MAX_RESPONSE)
            .min(MAX_RESPONSE);
        tokio::select! {biased;_=self.cancel.cancelled()=>Err(NodeFailure::Cancelled.interface()),r=tokio::time::timeout(Duration::from_secs(90),self.request(route,body,limit))=>r.map_err(|_|NodeFailure::Timeout.interface())?}
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    #[test]
    fn authenticated_endpoint_and_rpc_route_rules() {
        assert!(endpoint("http://node.example:38081", NetworkRoute::Direct).is_ok());
        assert!(endpoint("https://node.example:38081", NetworkRoute::Direct).is_ok());
        assert!(endpoint("http://node.example:38081", NetworkRoute::Tor).is_err());
        assert!(endpoint("https://node.example:38081", NetworkRoute::Tor).is_ok());
        let onion = format!("http://{}.onion:38081", "a".repeat(56));
        assert!(endpoint(&onion, NetworkRoute::Tor).is_ok());
        assert!(endpoint(&onion, NetworkRoute::Direct).is_err());
        for route in [
            "json_rpc",
            "get_blocks.bin",
            "get_o_indexes.bin",
            "get_outs.bin",
            "get_output_distribution.bin",
            "get_height",
            "get_transactions",
            "send_raw_transaction",
        ] {
            assert!(route_allowed(route));
        }
        for route in [
            "is_key_image_spent",
            "get_public_nodes",
            "../json_rpc",
            "https://other/",
        ] {
            assert!(!route_allowed(route));
        }
    }
    async fn response(bytes: &'static [u8], limit: usize) -> Result<Vec<u8>, InterfaceError> {
        let (client, mut server) = tokio::io::duplex(4096);
        let task = tokio::spawn(async move {
            let mut b = [0; 1024];
            let n = server.read(&mut b).await.unwrap();
            let request = String::from_utf8_lossy(&b[..n]);
            assert!(request.starts_with("POST /json_rpc HTTP/1.1"));
            assert!(request
                .to_ascii_lowercase()
                .contains("host: node.example:38081"));
            assert!(request
                .to_ascii_lowercase()
                .contains("accept-encoding: identity"));
            let _ = server.write_all(bytes).await;
            let _ = server.shutdown().await;
        });
        let result = NodeTransport::http(
            &Url::parse("https://node.example:38081/").unwrap(),
            client,
            "json_rpc",
            b"{}".to_vec(),
            limit,
        )
        .await;
        task.await.unwrap();
        result
    }
    #[tokio::test]
    async fn http_rejects_redirects_encoding_and_oversize_bodies() {
        assert_eq!(
            response(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}", 2)
                .await
                .unwrap(),
            b"{}"
        );
        assert!(response(
            b"HTTP/1.1 302 Found\r\nContent-Length: 0\r\nLocation: http://other/\r\n\r\n",
            2
        )
        .await
        .is_err());
        assert!(
            response(b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\n123", 2)
                .await
                .is_err()
        );
        assert!(response(
            b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\n123\r\n0\r\n\r\n",
            2
        )
        .await
        .is_err());
        assert!(response(
            b"HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 2\r\n\r\n{}",
            2
        )
        .await
        .is_err());
    }

    #[tokio::test]
    async fn uncompressed_identity_header_is_a_valid_node_response() {
        assert_eq!(
            response(
                b"HTTP/1.1 200 OK\r\nContent-Encoding: identity\r\nContent-Length: 2\r\n\r\n{}",
                2
            )
            .await
            .unwrap(),
            b"{}"
        );
    }

    #[tokio::test]
    async fn identity_does_not_bypass_coding_or_size_limits() {
        assert!(response(b"HTTP/1.1 200 OK\r\nContent-Encoding: identity\r\nContent-Encoding: gzip\r\nContent-Length: 2\r\n\r\n{}", 2).await.is_err());
        assert!(response(
            b"HTTP/1.1 200 OK\r\nContent-Encoding: identity, gzip\r\nContent-Length: 2\r\n\r\n{}",
            2
        )
        .await
        .is_err());
        let e = response(
            b"HTTP/1.1 200 OK\r\nContent-Encoding: identity\r\nContent-Length: 3\r\n\r\n123",
            2,
        )
        .await
        .unwrap_err();
        assert_eq!(NodeFailure::from_interface(&e), Some(NodeFailure::Size));
        let e = response(b"HTTP/1.1 200 OK\r\nContent-Encoding: identity\r\nTransfer-Encoding: chunked\r\n\r\n3\r\n123\r\n0\r\n\r\n", 2).await.unwrap_err();
        assert_eq!(NodeFailure::from_interface(&e), Some(NodeFailure::Size));
    }

    #[tokio::test]
    async fn http_failure_preserves_only_status_without_private_node_text() {
        let e = response(
            b"HTTP/1.1 403 private-error-text\r\nContent-Length: 16\r\n\r\nprivate-fixture!",
            100,
        )
        .await
        .unwrap_err();
        assert_eq!(
            NodeFailure::from_interface(&e),
            Some(NodeFailure::Status(403))
        );
        assert!(!e.to_string().contains("private-error-text"));
        assert!(!e.to_string().contains("private-fixture!"));
    }

    #[test]
    fn transport_diagnostics_are_closed_and_do_not_echo_untrusted_text() {
        for failure in [
            NodeFailure::Address,
            NodeFailure::Permission,
            NodeFailure::Dns,
            NodeFailure::Refused,
            NodeFailure::Connect,
            NodeFailure::Timeout,
            NodeFailure::Tls,
            NodeFailure::TorStart,
            NodeFailure::TorNode,
            NodeFailure::Http,
            NodeFailure::Status(401),
            NodeFailure::Status(429),
            NodeFailure::Encoding,
            NodeFailure::Size,
            NodeFailure::Cancelled,
        ] {
            assert_eq!(
                NodeFailure::from_interface(&failure.interface()),
                Some(failure)
            );
        }
        let injected = InterfaceError::InterfaceError(
            "https://private-fixture.invalid/password [NET_TLS]".into(),
        );
        assert!(NodeFailure::from_interface(&injected).is_none());
        assert!(!crate::EngineError::node_error(injected)
            .to_string()
            .contains("private-fixture"));
        assert_eq!(
            NodeFailure::socket(&std::io::Error::from(std::io::ErrorKind::PermissionDenied)),
            NodeFailure::Permission
        );
        assert_eq!(
            NodeFailure::socket(&std::io::Error::from(std::io::ErrorKind::ConnectionRefused)),
            NodeFailure::Refused
        );
    }

    #[tokio::test]
    async fn direct_uses_the_selected_socket_without_starting_tor() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let node = format!("http://{}/", listener.local_addr().unwrap());
        let directory = tempfile::tempdir().unwrap();
        let transport = NodeTransport::connect(
            &node,
            NetworkRoute::Direct,
            directory.path(),
            CancellationToken::new(),
        )
        .await
        .unwrap();
        assert!(matches!(transport.connector, Connector::Direct));
        assert_eq!(std::fs::read_dir(directory.path()).unwrap().count(), 0);
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut request = [0; 4096];
            let n = stream.read(&mut request).await.unwrap();
            assert!(request[..n].starts_with(b"POST /get_height HTTP/1.1"));
            stream
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 12\r\n\r\n{\"height\":1}")
                .await
                .unwrap();
        });
        assert_eq!(
            transport
                .post("get_height", b"{}".to_vec(), None)
                .await
                .unwrap(),
            b"{\"height\":1}"
        );
        server.await.unwrap();
        assert_eq!(std::fs::read_dir(directory.path()).unwrap().count(), 0);
    }

    #[tokio::test]
    async fn cancelled_direct_request_drops_its_socket_and_cannot_reconnect() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let node = format!("http://{}/", listener.local_addr().unwrap());
        let cancel = CancellationToken::new();
        let directory = tempfile::tempdir().unwrap();
        let transport = NodeTransport::connect(
            &node,
            NetworkRoute::Direct,
            directory.path(),
            cancel.clone(),
        )
        .await
        .unwrap();
        let (started, ready) = tokio::sync::oneshot::channel();
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut request = [0; 4096];
            assert!(stream.read(&mut request).await.unwrap() > 0);
            started.send(()).unwrap();
            let n = tokio::time::timeout(Duration::from_secs(2), stream.read(&mut request))
                .await
                .unwrap()
                .unwrap();
            assert_eq!(n, 0);
        });
        let worker = transport.clone();
        let request =
            tokio::spawn(async move { worker.post("get_height", b"{}".to_vec(), None).await });
        ready.await.unwrap();
        cancel.cancel();
        assert!(tokio::time::timeout(Duration::from_secs(2), request)
            .await
            .unwrap()
            .unwrap()
            .is_err());
        server.await.unwrap();
        assert!(transport
            .post("get_height", b"{}".to_vec(), None)
            .await
            .is_err());
    }

    #[tokio::test]
    async fn rejected_tor_route_never_falls_back_to_direct() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let node = format!("http://{}/", listener.local_addr().unwrap());
        let directory = tempfile::tempdir().unwrap();
        assert!(NodeTransport::connect(
            &node,
            NetworkRoute::Tor,
            directory.path(),
            CancellationToken::new()
        )
        .await
        .is_err());
        assert!(
            tokio::time::timeout(Duration::from_millis(50), listener.accept())
                .await
                .is_err()
        );
        let onion = format!("http://{}.onion:38081/", "a".repeat(56));
        assert!(NodeTransport::connect(
            &onion,
            NetworkRoute::Direct,
            directory.path(),
            CancellationToken::new()
        )
        .await
        .is_err());
        assert_eq!(std::fs::read_dir(directory.path()).unwrap().count(), 0);
    }

    #[tokio::test]
    async fn direct_https_does_not_downgrade_after_a_tls_failure() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let node = format!("https://{}/", listener.local_addr().unwrap());
        let directory = tempfile::tempdir().unwrap();
        let transport = NodeTransport::connect(
            &node,
            NetworkRoute::Direct,
            directory.path(),
            CancellationToken::new(),
        )
        .await
        .unwrap();
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut hello = [0; 4096];
            let n = stream.read(&mut hello).await.unwrap();
            assert!(n > 0 && hello[0] == 22); // TLS handshake, never plaintext RPC.
            stream
                .write_all(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n")
                .await
                .unwrap();
            drop(stream);
            assert!(
                tokio::time::timeout(Duration::from_millis(100), listener.accept())
                    .await
                    .is_err()
            );
        });
        assert!(tokio::time::timeout(
            Duration::from_secs(2),
            transport.post("get_height", b"{}".to_vec(), None)
        )
        .await
        .unwrap()
        .is_err());
        server.await.unwrap();
    }
}
