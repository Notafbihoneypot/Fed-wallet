use super::*;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpListener,
};
use wallet_policy::connection::NetworkRoute;

// Public disposable key fixture. This server never contacts a real endpoint.
#[tokio::test]
async fn direct_engine_connects_through_identity_rpc_and_still_checks_genesis() {
    for (resolved, wrong_genesis) in [(false, false), (false, true), (true, false), (true, true)] {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let node = if resolved {
            format!(
                "http://selected-node.invalid:{}/",
                listener.local_addr().unwrap().port()
            )
        } else {
            format!("http://{}/", listener.local_addr().unwrap())
        };
        let server = tokio::spawn(async move {
            for index in 0..(if wrong_genesis { 2 } else { 3 }) {
                let (mut stream, _) = listener.accept().await.unwrap();
                let mut bytes = vec![];
                let (header, body) = loop {
                    let mut part = [0; 4096];
                    let n = stream.read(&mut part).await.unwrap();
                    assert!(n > 0);
                    bytes.extend_from_slice(&part[..n]);
                    assert!(bytes.len() < 8192);
                    if let Some(pos) = bytes.windows(4).position(|s| s == b"\r\n\r\n") {
                        let h = std::str::from_utf8(&bytes[..pos]).unwrap();
                        let length: usize = h
                            .lines()
                            .find_map(|l| {
                                l.to_ascii_lowercase()
                                    .strip_prefix("content-length:")
                                    .map(|s| s.trim().parse().unwrap())
                            })
                            .unwrap_or(0);
                        if bytes.len() >= pos + 4 + length {
                            break (h.to_owned(), bytes[pos + 4..pos + 4 + length].to_vec());
                        }
                    }
                };
                assert!(header
                    .to_ascii_lowercase()
                    .contains("accept-encoding: identity"));
                let request: serde_json::Value = if body.is_empty() {
                    serde_json::Value::Null
                } else {
                    serde_json::from_slice(&body).unwrap()
                };
                let response = if index == 0 {
                    assert!(request.is_array());
                    // Existing monerod releases reject JSON-RPC batches this way.
                    serde_json::json!({"jsonrpc":"2.0", "id":null, "error":{"code":-32700,"message":"Parse error"}})
                } else if index == 1 {
                    assert_eq!(request["method"], "on_get_block_hash");
                    let hash = if wrong_genesis { [0x42;32] } else { genesis(Network::Stagenet).unwrap() };
                    serde_json::json!({"jsonrpc":"2.0","id":request["id"],"result":hex::encode(hash)})
                } else {
                    assert!(header.starts_with("POST /get_height HTTP/1.1"));
                    serde_json::json!({"height": 2})
                }.to_string();
                let reply = format!("HTTP/1.1 200 OK\r\nContent-Encoding: identity\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",response.len(),response);
                stream.write_all(reply.as_bytes()).await.unwrap();
            }
        });
        let wallet = OfflineWallet::restore_spend_key(
            KeyNetwork::Stagenet,
            Zeroizing::new(format!("01{}", "00".repeat(31))),
            0,
        )
        .unwrap();
        let directory = tempfile::tempdir().unwrap();
        let mut engine =
            WalletEngine::<NodeTransport>::new(&wallet, directory.path(), CancellationToken::new())
                .unwrap();
        let result = tokio::time::timeout(Duration::from_secs(3), async {
            if resolved {
                engine
                    .connect_direct_resolved(&node, &["127.0.0.1".into()])
                    .await
            } else {
                engine.connect(&node, NetworkRoute::Direct).await
            }
        })
        .await
        .unwrap();
        if wrong_genesis {
            assert!(matches!(result, Err(EngineError::Network)));
        } else {
            result.unwrap();
            assert!(!engine.status().unwrap().synced);
            assert_eq!(engine.status().unwrap().target_height, 2);
        }
        server.await.unwrap();
    }
}
