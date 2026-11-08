//! Offline signing with real wallet keys and a synthetic ring; no node.
use super::*;
use crate::tests::NoNetwork;
use curve25519_dalek::constants::ED25519_BASEPOINT_TABLE;
use monero_wallet::ed25519::Commitment;
use std::ops::RangeBounds;

const REAL_INDEX: u64 = 5_000;

struct Chain {
    real: [Point; 2],
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
        Ok(indexes
            .iter()
            .map(|&i| {
                Some(if i == REAL_INDEX {
                    self.real
                } else {
                    [point(), point()]
                })
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
    let chain = Chain {
        real: [output.key(), output.commitment().commit()],
    };
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
