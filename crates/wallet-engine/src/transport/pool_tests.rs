use super::*;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
};

async fn read_request(stream: &mut TcpStream) -> (String, Vec<u8>) {
    let mut header = vec![];
    while !header.ends_with(b"\r\n\r\n") {
        header.push(stream.read_u8().await.unwrap());
        assert!(header.len() <= 8192);
    }
    let header = String::from_utf8(header).unwrap();
    let length = header
        .lines()
        .find_map(|line| {
            line.to_ascii_lowercase()
                .strip_prefix("content-length:")
                .map(|value| value.trim().parse::<usize>().unwrap())
        })
        .unwrap_or(0);
    assert!(length < 8192);
    let mut body = vec![0; length];
    stream.read_exact(&mut body).await.unwrap();
    (header, body)
}

async fn reply(stream: &mut TcpStream, close: bool) {
    let close = if close { "Connection: close\r\n" } else { "" };
    stream
        .write_all(format!("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n{close}\r\n{{}}").as_bytes())
        .await
        .unwrap();
}

async fn node(listener: &TcpListener, cancel: CancellationToken) -> NodeTransport {
    NodeTransport::direct_resolved(
        &format!(
            "http://selected-node.invalid:{}/",
            listener.local_addr().unwrap().port()
        ),
        &["127.0.0.1".into()],
        cancel,
    )
    .unwrap()
}

async fn post(transport: &NodeTransport) -> Result<Vec<u8>, InterfaceError> {
    tokio::time::timeout(
        Duration::from_secs(3),
        transport.post("get_height", b"{}".to_vec(), None),
    )
    .await
    .unwrap()
}

#[tokio::test]
async fn clones_share_one_connection_and_lock_cancels_its_idle_socket() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let cancel = CancellationToken::new();
    let transport = node(&listener, cancel.clone()).await;
    let copy = transport.clone();
    let server = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.unwrap();
        for _ in 0..4 {
            let (header, body) = read_request(&mut stream).await;
            assert!(header.starts_with("POST /get_height HTTP/1.1"));
            assert!(header
                .to_ascii_lowercase()
                .contains("host: selected-node.invalid:"));
            assert!(!header.to_ascii_lowercase().contains("connection: close"));
            assert_eq!(body, b"{}");
            reply(&mut stream, false).await;
        }
        let mut byte = [0];
        assert_eq!(
            tokio::time::timeout(Duration::from_secs(2), stream.read(&mut byte))
                .await
                .unwrap()
                .unwrap(),
            0
        );
        assert!(
            tokio::time::timeout(Duration::from_millis(50), listener.accept())
                .await
                .is_err()
        );
    });
    let (a, b, c, d) = tokio::join!(post(&transport), post(&copy), post(&transport), post(&copy));
    for result in [a, b, c, d] {
        assert_eq!(result.unwrap(), b"{}");
    }
    cancel.cancel();
    assert_eq!(
        NodeFailure::from_interface(&post(&copy).await.unwrap_err()),
        Some(NodeFailure::Cancelled)
    );
    server.await.unwrap();
}

#[tokio::test]
async fn server_close_opens_a_new_socket_for_the_next_request() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let transport = node(&listener, CancellationToken::new()).await;
    let server = tokio::spawn(async move {
        for _ in 0..2 {
            let (mut stream, _) = listener.accept().await.unwrap();
            read_request(&mut stream).await;
            reply(&mut stream, true).await;
        }
    });
    assert_eq!(post(&transport).await.unwrap(), b"{}");
    assert_eq!(post(&transport).await.unwrap(), b"{}");
    server.await.unwrap();
}

#[tokio::test]
async fn dropped_partial_response_discards_socket_instead_of_reusing_its_bytes() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let transport = node(&listener, CancellationToken::new()).await;
    let server = tokio::spawn(async move {
        let (mut first, _) = listener.accept().await.unwrap();
        read_request(&mut first).await;
        first
            .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n{")
            .await
            .unwrap();
        let mut byte = [0];
        assert_eq!(
            tokio::time::timeout(Duration::from_secs(2), first.read(&mut byte))
                .await
                .unwrap()
                .unwrap(),
            0
        );
        let (mut second, _) = listener.accept().await.unwrap();
        read_request(&mut second).await;
        reply(&mut second, true).await;
    });
    assert!(tokio::time::timeout(
        Duration::from_millis(50),
        transport.post("get_height", vec![], None)
    )
    .await
    .is_err());
    assert_eq!(post(&transport).await.unwrap(), b"{}");
    server.await.unwrap();
}

#[tokio::test]
async fn a_broadcast_with_a_lost_reply_is_never_replayed_by_the_transport() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let transport = node(&listener, CancellationToken::new()).await;
    let server = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.unwrap();
        let (header, body) = read_request(&mut stream).await;
        assert!(header.starts_with("POST /send_raw_transaction HTTP/1.1"));
        assert_eq!(body, b"public-disposable-transaction-fixture");
        drop(stream);
        assert!(
            tokio::time::timeout(Duration::from_millis(150), listener.accept())
                .await
                .is_err()
        );
    });
    let result = tokio::time::timeout(
        Duration::from_secs(3),
        transport.post(
            "send_raw_transaction",
            b"public-disposable-transaction-fixture".to_vec(),
            None,
        ),
    )
    .await
    .unwrap();
    assert_eq!(
        NodeFailure::from_interface(&result.unwrap_err()),
        Some(NodeFailure::Http)
    );
    server.await.unwrap();
}

#[tokio::test]
async fn connection_reuse_benchmark_keeps_twenty_requests_on_one_socket() {
    let mut measured = vec![];
    for close in [true, false] {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let cancel = CancellationToken::new();
        let transport = node(&listener, cancel.clone()).await;
        let server = tokio::spawn(async move {
            let mut connections = 0;
            let mut remaining = 20;
            while remaining > 0 {
                let (mut stream, _) = listener.accept().await.unwrap();
                connections += 1;
                // Simulate connection setup cost, not per-request work.
                tokio::time::sleep(Duration::from_millis(25)).await;
                loop {
                    read_request(&mut stream).await;
                    reply(&mut stream, close).await;
                    remaining -= 1;
                    if close || remaining == 0 {
                        break;
                    }
                }
            }
            connections
        });
        let started = std::time::Instant::now();
        for _ in 0..20 {
            assert_eq!(post(&transport).await.unwrap(), b"{}");
        }
        let elapsed = started.elapsed();
        cancel.cancel();
        let connections = server.await.unwrap();
        assert_eq!(connections, if close { 20 } else { 1 });
        measured.push((connections, elapsed));
    }
    println!(
        "20-RPC connection setup benchmark, 25ms per socket: close {:?}; reuse {:?}",
        measured[0], measured[1]
    );
}
