//! Offline signing with real wallet keys and a synthetic ring; no node.
use super::*;
use crate::tests::NoNetwork;
use curve25519_dalek::constants::ED25519_BASEPOINT_TABLE;
use monero_wallet::ed25519::Commitment;
use std::ops::RangeBounds;

const REAL_INDEX: u64 = 5_000;

struct Chain {
    real: [Point; 2],
    /// Decoy candidates (never the real output) to report as locked.
    locked: std::sync::Mutex<usize>,
    requests: std::sync::Mutex<Vec<Vec<u64>>>,
}
impl Chain {
    fn new(real: [Point; 2], locked: usize) -> Self {
        Self {
            real,
            locked: locked.into(),
            requests: Default::default(),
        }
    }
}
impl ProvidesBlockchainMeta for Chain {
    async fn latest_block_number(&self) -> Result<usize, InterfaceError> {
        Ok(1_000)
    }
}
impl ProvidesDecoys for Chain {
    async fn ringct_output_distribution(
        &self,
        _: impl Send + RangeBounds<usize>,
    ) -> Result<Vec<u64>, InterfaceError> {
        Ok((1..=1_001).map(|block| block * 10).collect())
    }
    async fn unlocked_ringct_outputs(
        &self,
        indexes: &[u64],
        _: EvaluateUnlocked,
    ) -> Result<Vec<Option<[Point; 2]>>, TransactionsError> {
        self.requests.lock().unwrap().push(indexes.to_vec());
        Ok(indexes
            .iter()
            .map(|&i| {
                if i == REAL_INDEX {
                    return Some(self.real);
                }
                let mut left = self.locked.lock().unwrap();
                if *left > 0 {
                    *left -= 1;
                    return None;
                }
                Some([point(), point()])
            })
            .collect())
    }
}
fn point() -> Point {
    Point::from(&Scalar::random(&mut OsRng).into() * ED25519_BASEPOINT_TABLE)
}
/// An output this wallet's spend key can sign for, in `WalletOutput`'s own encoding.
fn owned_output(engine: &WalletEngine<NoNetwork>, amount: u64) -> WalletOutput {
    let offset = Scalar::random(&mut OsRng);
    let secret = Zeroizing::new((**engine.spend.as_ref().unwrap()).into() + offset.into());
    let mut bytes = vec![9; 32]; // transaction hash
    bytes.extend(0u64.to_le_bytes()); // index in transaction
    bytes.extend(REAL_INDEX.to_le_bytes()); // index on blockchain
    bytes.extend(
        Point::from(&*secret * ED25519_BASEPOINT_TABLE)
            .compress()
            .to_bytes(),
    );
    offset.write(&mut bytes).unwrap();
    Commitment::new(Scalar::random(&mut OsRng), amount)
        .write(&mut bytes)
        .unwrap();
    bytes.extend([0, 0, 0, 0]); // no timelock, subaddress, payment ID or arbitrary data
    WalletOutput::read(&mut bytes.as_slice()).unwrap()
}

#[tokio::test]
async fn max_send_signs_and_only_the_exact_bytes_verify() {
    let wallet = OfflineWallet::restore_spend_key(
        KeyNetwork::Stagenet,
        Zeroizing::new(format!("01{}", "00".repeat(31))),
        0,
    )
    .unwrap();
    let dir = tempfile::tempdir().unwrap();
    let engine =
        WalletEngine::<NoNetwork>::new(&wallet, dir.path(), CancellationToken::new()).unwrap();
    let total = 1_000_000_000_000;
    let output = owned_output(&engine, total);
    let image: [u8; 32] = hex::decode(engine.image(&output).unwrap().unwrap())
        .unwrap()
        .try_into()
        .unwrap();
    let chain = Chain::new([output.key(), output.commitment().commit()], 0);
    let input = OutputWithDecoys::new(&mut OsRng, &chain, 16, 1_000, output)
        .await
        .unwrap();
    let recipient = MoneroAddress::from_str(engine.network, &wallet.details().address).unwrap();
    let rate = FeeRate::new(20_000, 10_000).unwrap();
    let signable = |amount| {
        SignableTransaction::new(
            RctType::ClsagBulletproofPlus,
            Zeroizing::new([7; 32]),
            vec![input.clone()],
            vec![(recipient, amount)],
            Change::new(engine.pair.clone(), None),
            vec![],
            rate,
        )
        .unwrap()
    };
    // Max probes the fee with a 1-atomic placeholder, as `prepare_payment` does;
    // the real amount must not change it, or amount + fee != inputs.
    let fee = signable(1).necessary_fee();
    let max = signable(total - fee);
    assert_eq!(max.necessary_fee(), fee);
    let intent = Eventuality::from(max.clone());
    let bytes = max
        .sign(&mut OsRng, engine.spend.as_ref().unwrap())
        .unwrap()
        .serialize();
    let images = BTreeSet::from([image]);
    let inputs = [input];
    assert!(verify_transaction(&bytes, intent.clone(), &inputs, &images, fee).is_ok());
    for offset in [0, bytes.len() / 2, bytes.len() - 1] {
        let mut changed = bytes.clone();
        changed[offset] ^= 1;
        assert!(verify_transaction(&changed, intent.clone(), &inputs, &images, fee).is_err());
    }
    assert!(verify_transaction(&bytes, intent.clone(), &inputs, &images, fee + 1).is_err());
    let wrong_images = BTreeSet::from([[255; 32]]);
    assert!(verify_transaction(&bytes, intent, &inputs, &wrong_images, fee).is_err());
}

#[tokio::test]
async fn a_node_reporting_many_unusable_candidates_cannot_force_more_requests() {
    let wallet = OfflineWallet::restore_spend_key(
        KeyNetwork::Stagenet,
        Zeroizing::new(format!("01{}", "00".repeat(31))),
        0,
    )
    .unwrap();
    let dir = tempfile::tempdir().unwrap();
    let engine =
        WalletEngine::<NoNetwork>::new(&wallet, dir.path(), CancellationToken::new()).unwrap();
    let output = owned_output(&engine, 1_000_000_000_000);
    let counts = std::sync::Arc::new((1..=1_001u64).map(|block| block * 10).collect::<Vec<_>>());
    for locked in 0..=6usize {
        let chain = Chain::new([output.key(), output.commitment().commit()], locked);
        let adapter = decoys::CachedDecoys::new(&chain, 1_000, counts.clone());
        let ring = OutputWithDecoys::new(&mut OsRng, &adapter, 16, 1_000, output.clone()).await;
        let requests = chain.requests.lock().unwrap().clone();
        // The real output travels only in the first request.
        assert!(requests[0].contains(&REAL_INDEX));
        assert!(requests[1..].iter().all(|r| !r.contains(&REAL_INDEX)));
        if locked <= decoys::MAX_UNUSABLE_CANDIDATES {
            // An honest node's occasional locked candidate still builds a ring,
            // and the node can rule out at most that many first-round members.
            assert_eq!(ring.unwrap().decoys().offsets().len(), 16);
            assert!(!adapter.refused());
            let replaced: usize = requests[1..].iter().map(Vec::len).sum();
            assert_eq!(replaced, locked);
        } else {
            // Beyond the limit the first reply is refused: no replacement
            // request is ever sent, so the node learns nothing more.
            assert!(ring.is_err());
            assert!(adapter.refused());
            assert_eq!(requests.len(), 1);
        }
    }
}

#[tokio::test]
async fn the_recorded_transaction_key_proves_the_payment_and_is_never_a_wrong_key() {
    let wallet = OfflineWallet::restore_spend_key(
        KeyNetwork::Stagenet,
        Zeroizing::new(format!("01{}", "00".repeat(31))),
        0,
    )
    .unwrap();
    let dir = tempfile::tempdir().unwrap();
    let mut engine =
        WalletEngine::<NoNetwork>::new(&wallet, dir.path(), CancellationToken::new()).unwrap();
    let view = wallet.with_secret_keys(|view, _| *view);
    let main = MoneroAddress::from_str(engine.network, &wallet.details().address).unwrap();
    let subaddress = engine
        .pair
        .subaddress(engine.network, SubaddressIndex::new(0, 1).unwrap());
    for recipient in [main, subaddress] {
        let output = owned_output(&engine, 1_000_000_000_000);
        let chain = Chain::new([output.key(), output.commitment().commit()], 0);
        let input = OutputWithDecoys::new(&mut OsRng, &chain, 16, 1_000, output)
            .await
            .unwrap();
        let outgoing = Zeroizing::new([7; 32]);
        let transaction = SignableTransaction::new(
            RctType::ClsagBulletproofPlus,
            outgoing.clone(),
            vec![input.clone()],
            vec![(recipient, 1_000_000)],
            Change::new(engine.pair.clone(), None),
            vec![],
            FeeRate::new(20_000, 10_000).unwrap(),
        )
        .unwrap()
        .sign(&mut OsRng, engine.spend.as_ref().unwrap())
        .unwrap();
        let key = payment::transaction_key(
            &outgoing,
            std::slice::from_ref(&input),
            &recipient,
            &transaction,
        )
        .unwrap();
        assert_eq!(key.len(), 64);
        let secret = curve25519_dalek::Scalar::from_canonical_bytes(
            hex::decode(key.as_str()).unwrap().try_into().unwrap(),
        )
        .unwrap();
        // What a verifier computes from the key and the recipient's address is
        // what the recipient computes from their view key and the transaction.
        let published =
            monero_wallet::extra::Extra::read(&mut transaction.prefix().extra.as_slice())
                .unwrap()
                .keys()
                .unwrap()
                .0[0];
        assert_eq!(secret * recipient.view().into(), view * published.into());
        // A different seed, or a different recipient kind, derives a key that
        // does not open this transaction; nothing is returned for it.
        assert!(payment::transaction_key(
            &Zeroizing::new([8; 32]),
            std::slice::from_ref(&input),
            &recipient,
            &transaction
        )
        .is_none());
        let other = if recipient.is_subaddress() {
            main
        } else {
            subaddress
        };
        assert!(payment::transaction_key(&outgoing, &[input], &other, &transaction).is_none());
    }
    // The key is read only by transaction ID, and only from a signing wallet.
    let mut recorded = engine.state.clone();
    for (id, tx_key) in [("0a", Some("0b".repeat(32))), ("0c", None)] {
        recorded.payments.push(PendingPayment {
            transaction: id.repeat(32),
            destination: wallet.details().address,
            amount_atomic: 1,
            fee_atomic: 1,
            mined_height: Some(1),
            bytes: "00".into(),
            inputs: vec!["0d".repeat(32)],
            tx_key,
        });
    }
    engine.state = recorded;
    assert_eq!(
        engine
            .transaction_key(&"0a".repeat(32))
            .unwrap()
            .unwrap()
            .as_str(),
        "0b".repeat(32)
    );
    assert!(engine.transaction_key(&"0c".repeat(32)).unwrap().is_none());
    assert!(engine.transaction_key(&"0e".repeat(32)).unwrap().is_none());
    let status = engine.status().unwrap();
    let has = |id: &str| {
        status
            .payments
            .iter()
            .find(|p| p.transaction == id.repeat(32))
            .unwrap()
            .has_transaction_key
    };
    assert!(has("0a") && !has("0c"));
    engine.spend = None;
    assert!(matches!(
        engine.transaction_key(&"0a".repeat(32)),
        Err(EngineError::ViewOnly)
    ));
}
