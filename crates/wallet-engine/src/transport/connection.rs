use super::*;
use std::time::Instant;

/// One HTTP/1.1 connection, owned by exactly one consented transport session.
/// A checked-out connection is never returned to the pool until its response
/// has been read completely. Dropping a request aborts its driver and socket.
pub(super) struct HttpConnection {
    sender: http1::SendRequest<Full<Bytes>>,
    _driver: AbortOnDropHandle<()>,
    last_used: Instant,
}

impl HttpConnection {
    pub(super) async fn open<S>(
        stream: S,
        cancel: CancellationToken,
    ) -> Result<Self, InterfaceError>
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        let (sender, connection) = http1::handshake(TokioIo::new(stream))
            .await
            .map_err(|_| fail())?;
        let driver = tokio::spawn(async move {
            // Cancellation also closes a pooled, idle socket without waiting
            // for another request or taking the pool/engine mutex.
            tokio::select! { biased;
                _ = cancel.cancelled() => {},
                _ = connection => {},
            }
        });
        Ok(Self {
            sender,
            _driver: AbortOnDropHandle::new(driver),
            last_used: Instant::now(),
        })
    }

    pub(super) async fn ready(&mut self) -> bool {
        self.last_used.elapsed() < Duration::from_secs(20) && self.sender.ready().await.is_ok()
    }

    pub(super) async fn post(
        &mut self,
        endpoint: &Url,
        route: &str,
        body: Vec<u8>,
        limit: usize,
    ) -> Result<(Vec<u8>, bool), InterfaceError> {
        let authority = endpoint[url::Position::BeforeHost..url::Position::AfterPort].to_owned();
        let request = Request::post(format!("/{route}"))
            .header("Host", authority)
            .header("Accept-Encoding", "identity")
            .header(
                "Content-Type",
                if route.ends_with(".bin") {
                    "application/octet-stream"
                } else {
                    "application/json"
                },
            )
            .body(Full::new(Bytes::from(body)))
            .map_err(|_| fail())?;
        // Once an application request is attempted, failures are returned to
        // the caller. In particular, NEVER automatically replay a broadcast.
        let response = self
            .sender
            .send_request(request)
            .await
            .map_err(|_| fail())?;
        if response.status() != hyper::StatusCode::OK {
            return Err(NodeFailure::Status(response.status().as_u16()).interface());
        }
        let reusable = !response
            .headers()
            .get_all("connection")
            .iter()
            .any(|value| {
                value.to_str().map_or(true, |value| {
                    value
                        .split(',')
                        .any(|token| token.trim().eq_ignore_ascii_case("close"))
                })
            });
        if response
            .headers()
            .get_all("content-encoding")
            .iter()
            .any(|value| !value.as_bytes().eq_ignore_ascii_case(b"identity"))
        {
            return Err(NodeFailure::Encoding.interface());
        }
        if response
            .headers()
            .get("content-length")
            .is_some_and(|value| {
                value
                    .to_str()
                    .ok()
                    .and_then(|s| s.parse::<u64>().ok())
                    .is_none_or(|n| n > limit as u64)
            })
        {
            return Err(NodeFailure::Size.interface());
        }
        let mut incoming = response.into_body();
        let mut result = vec![];
        while let Some(frame) = incoming.frame().await {
            if let Ok(bytes) = frame.map_err(|_| fail())?.into_data() {
                if bytes.len() > limit.saturating_sub(result.len()) {
                    return Err(NodeFailure::Size.interface());
                }
                result.extend_from_slice(&bytes);
            }
        }
        self.last_used = Instant::now();
        Ok((result, reusable && !self.sender.is_closed()))
    }
}
