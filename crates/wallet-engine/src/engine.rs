use crate::{
    state::{Checkpoint, HistoryEntry, OutputRecord, PendingPayment},
    store::Store,
    transport::NodeTransport,
    verify::verify_transaction,
    EngineError, State,
};
use curve25519_dalek::traits::IsIdentity as _;
use monero_daemon_rpc::{HttpTransport, MoneroDaemon};
use monero_wallet::{
    address::{AddressType, MoneroAddress, Network, SubaddressIndex},
    block::{Block, BlockHeader},
    ed25519::{Point, Scalar},
    interface::prelude::*,
    ringct::RctType,
    send::{Change, Eventuality, SendError, SignableTransaction},
    transaction::{Input, Pruned, Timelock, Transaction},
    OutputWithDecoys, Scanner, ViewPair, WalletOutput,
};
use rand_core::{OsRng, RngCore};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::{Path, PathBuf},
    time::{Duration, Instant},
};
use tokio_util::sync::CancellationToken;
use wallet_keys::{Network as KeyNetwork, OfflineWallet};
use wallet_policy::amount;
use zeroize::Zeroizing;
mod addresses;
mod batch;
mod decoys;
pub(crate) use decoys::{DistributionCache, MAX_COUNTS};
mod payment;
mod preparation;
mod sync;
pub(crate) use addresses::valid_subaddress_label;
pub use addresses::{ReceivingAddressBook, SubaddressLabel, MAX_SUBADDRESSES};
pub use preparation::{PreparationProgress, PreparationSnapshot, PreparationStage};
const MAX_FEE: u64 = 50_000_000_000;
const MAX_FEE_PER_WEIGHT: u64 = 10_000_000;
pub struct SyncStatus {
    pub has_scan: bool,
    pub start_height: u64,
    pub next_height: u64,
    pub target_height: u64,
    pub synced: bool,
    pub view_only: bool,
    pub received_atomic: u64,
    pub balance_atomic: Option<u64>,
    pub unlocked_atomic: Option<u64>,
    pub next_unlock_blocks: Option<u64>,
    pub history: Vec<HistoryEntry>,
    pub pending: Option<PendingPayment>,
    pub payments: Vec<PaymentStatus>,
}
pub struct PaymentStatus {
    pub transaction: String,
    pub destination: String,
    pub amount_atomic: u64,
    pub fee_atomic: u64,
    pub total_atomic: u64,
    pub mined_height: Option<u64>,
    pub confirmations: u64,
}
pub struct PaymentReview {
    pub send_max: bool,
    pub review_id: String,
    pub destination: String,
    pub amount_atomic: u64,
    pub fee_atomic: u64,
    pub transaction: String,
}
struct Prepared {
    review_id: String,
    created: Instant,
    payment: PendingPayment,
    tip: [u8; 32],
    tip_height: u64,
}
pub struct WalletEngine<T: HttpTransport> {
    network: Network,
    pair: ViewPair,
    spend: Option<Zeroizing<Scalar>>,
    directory: PathBuf,
    store: Store,
    state: State,
    rpc: Option<MoneroDaemon<T>>,
    cancel: CancellationToken,
    target_height: u64,
    synced: bool,
    prepared: Option<Prepared>,
    decoy_cache: Option<decoys::DistributionCache>,
    decoy_source: Option<[u8; 32]>,
    batch_size: u64,
    batch_limit: u64,
    scanner_cache: Option<Scanner>,
    /// One block chunk downloading while the previous chunk is scanned.
    prefetch: Option<sync::Prefetch>,
    /// Time of the last full encrypted state write; quiet windows grow with it.
    save_cost: Duration,
    preparation: std::sync::Arc<PreparationProgress>,
}
impl<T: HttpTransport + Send + 'static> WalletEngine<T> {
    /// Local-only: no Tor instance, sockets, DNS, or daemon construction.
    pub fn new(
        wallet: &OfflineWallet,
        directory: &Path,
        cancel: CancellationToken,
    ) -> Result<Self, EngineError> {
        if cancel.is_cancelled() {
            return Err(EngineError::Disconnected);
        }
        let info = wallet.details();
        let network = match info.network {
            KeyNetwork::Stagenet => Network::Stagenet,
            KeyNetwork::Mainnet => Network::Mainnet,
            _ => return Err(EngineError::Network),
        };
        if info.mode != wallet_keys::Mode::Software && wallet.has_local_spend_key() {
            return Err(EngineError::ViewOnly);
        }
        let address =
            MoneroAddress::from_str(network, &info.address).map_err(|_| EngineError::Network)?;
        wallet.with_secret_keys(|view, spend| {
            let pair = ViewPair::new(address.spend(), Zeroizing::new(Scalar::from(*view)))
                .map_err(|_| EngineError::Verification)?;
            let namespace = format!(
                "{}:{}",
                info.address,
                if info.mode == wallet_keys::Mode::Keystone {
                    "keystone-view-v1"
                } else if spend.is_some() {
                    "software"
                } else {
                    "view-only"
                }
            );
            let store = Store::open(directory, &namespace, &Zeroizing::new(view.to_bytes()))?;
            let state = store
                .load()?
                .unwrap_or_else(|| State::new(info.address.clone(), info.restore_height));
            if state.address != info.address {
                return Err(EngineError::Storage);
            }
            if spend.is_none() && !state.payments.is_empty() {
                return Err(EngineError::Storage);
            }
            let mut result = Self {
                network,
                pair,
                spend: spend.map(|s| Zeroizing::new(Scalar::from(*s))),
                directory: directory.to_owned(),
                store,
                state,
                rpc: None,
                cancel,
                target_height: 0,
                synced: false,
                prepared: None,
                decoy_cache: None,
                decoy_source: None,
                batch_size: batch::INITIAL,
                batch_limit: batch::MAX,
                scanner_cache: None,
                prefetch: None,
                save_cost: Duration::ZERO,
                preparation: Default::default(),
            };
            let mut highest_subaddress = result.state.created_subaddress;
            for (key, record) in &result.state.outputs {
                result.check()?;
                let o = decode_output(record)?;
                if hex::encode(o.key().compress().to_bytes()) != *key
                    || result.image(&o)? != record.image
                {
                    return Err(EngineError::Storage);
                }
                if let Some(index) = o.subaddress() {
                    if index.account() != 0 || index.address() > MAX_SUBADDRESSES {
                        return Err(EngineError::Storage);
                    }
                    highest_subaddress = highest_subaddress.max(index.address());
                }
            }
            for p in &result.state.payments {
                result.check()?;
                // Settled payments keep no bytes (`State::strip_settled_payments`).
                if !p.bytes.is_empty()
                    && hex::encode(decode_transaction(&p.bytes)?.hash()) != p.transaction
                {
                    return Err(EngineError::Storage);
                }
            }
            // Old caches had no created-address counter. Include already-known
            // receiving addresses without requiring another blockchain scan.
            result.state.created_subaddress = highest_subaddress;
            result.state.subaddress_count = result.state.subaddress_count.max(highest_subaddress);
            result.check()?;
            Ok(result)
        })
    }
    pub fn is_cancelled(&self) -> bool {
        self.cancel.is_cancelled()
    }
    fn check(&self) -> Result<(), EngineError> {
        if self.cancel.is_cancelled() {
            Err(EngineError::Disconnected)
        } else {
            Ok(())
        }
    }
    fn rpc(&self) -> Result<&MoneroDaemon<T>, EngineError> {
        self.check()?;
        self.rpc.as_ref().ok_or(EngineError::Disconnected)
    }
    fn commit(&mut self, next: State) -> Result<(), EngineError> {
        let started = Instant::now();
        if let Err(e) = self.store.save(&next) {
            self.cancel.cancel();
            return Err(e);
        }
        self.save_cost = started.elapsed();
        self.state = next;
        Ok(())
    }
    pub async fn attach_transport(&mut self, transport: T) -> Result<(), EngineError> {
        self.check()?;
        let rpc = MoneroDaemon::new(transport)
            .await
            .map_err(EngineError::node_error)?;
        if rpc.block_hash(0).await.map_err(EngineError::node_error)? != genesis(self.network)? {
            return Err(EngineError::Network);
        }
        self.check()?;
        let target = rpc
            .latest_block_number()
            .await
            .map_err(EngineError::node_error)?;
        let target = u64::try_from(target)
            .map_err(|_| EngineError::Node)?
            .checked_add(1)
            .ok_or(EngineError::Node)?;
        if target > u64::from(u32::MAX) {
            return Err(EngineError::Node);
        }
        self.decoy_cache = None;
        self.prefetch = None;
        self.rpc = Some(rpc);
        self.decoy_source = None;
        self.target_height = target;
        self.synced = false;
        Ok(())
    }
    fn scanner(&self) -> Scanner {
        let mut s = Scanner::new(self.pair.clone());
        // All addresses this preview can create are covered even after a seed
        // restore loses local labels/count. Build this table once per session.
        for i in 1..=crate::MAX_SUBADDRESSES {
            if let Some(i) = SubaddressIndex::new(0, i) {
                s.register_subaddress(i);
            }
        }
        s
    }
    pub fn receive_address(&self, index: u32) -> Result<String, EngineError> {
        self.check()?;
        if index == 0 {
            return Ok(self.state.address.clone());
        }
        if index > self.state.subaddress_count {
            return Err(EngineError::Payment);
        }
        Ok(self
            .pair
            .subaddress(
                self.network,
                SubaddressIndex::new(0, index).ok_or(EngineError::Payment)?,
            )
            .to_string())
    }
    #[allow(clippy::op_ref)] // Keep secret scalar operands behind zeroizing references.
    fn image(&self, o: &WalletOutput) -> Result<Option<String>, EngineError> {
        let Some(spend) = &self.spend else {
            return Ok(None);
        };
        let secret = Zeroizing::new((**spend).into() + o.key_offset().into());
        if Point::from(&*secret * curve25519_dalek::constants::ED25519_BASEPOINT_TABLE) != o.key() {
            return Err(EngineError::Verification);
        }
        Ok(Some(hex::encode(
            Point::from(&*secret * Point::biased_hash(o.key().compress().to_bytes()).into())
                .compress()
                .to_bytes(),
        )))
    }
    async fn rollback_reorg(&mut self, tip: u64) -> Result<(), EngineError> {
        let Some(last) = self.state.checkpoints.last() else {
            return Ok(());
        };
        let rpc = self.rpc()?;
        if last.height < tip
            && rpc
                .block_hash(last.height as usize)
                .await
                .map_err(EngineError::node_error)?
                == last.hash
        {
            return Ok(());
        }
        let mut height = self.state.start_height;
        for c in self.state.checkpoints.iter().rev() {
            if c.height < tip
                && rpc
                    .block_hash(c.height as usize)
                    .await
                    .map_err(EngineError::node_error)?
                    == c.hash
            {
                height = c.height + 1;
                break;
            }
        }
        let mut next = self.state.clone();
        next.rollback(height);
        self.decoy_cache = None;
        self.prepared = None;
        self.commit(next)
    }
    fn spendable(&self, r: &OutputRecord, o: &WalletOutput) -> bool {
        unlock_height(r.height, r.coinbase, o.additional_timelock())
            .is_some_and(|height| self.state.next_height >= height)
    }
    pub fn status(&self) -> Result<SyncStatus, EngineError> {
        self.check()?;
        let (mut received, mut balance, mut unlocked) = (0u64, 0u64, 0u64);
        let mut next_unlock_blocks: Option<u64> = None;
        let reserved = self
            .state
            .payments
            .iter()
            .filter(|p| p.mined_height.is_none())
            .flat_map(|p| p.inputs.iter().map(String::as_str))
            .collect::<BTreeSet<_>>();
        for (key, r) in &self.state.outputs {
            let o = decode_output(r)?;
            let a = o.commitment().amount;
            received = received.checked_add(a).ok_or(EngineError::Storage)?;
            if a > 0 && r.spent_height.is_none() && !reserved.contains(key.as_str()) {
                balance = balance.checked_add(a).ok_or(EngineError::Storage)?;
                if self.spendable(r, &o) {
                    unlocked = unlocked.checked_add(a).ok_or(EngineError::Storage)?;
                } else if let Some(height) =
                    unlock_height(r.height, r.coinbase, o.additional_timelock())
                {
                    let blocks = height.saturating_sub(self.state.next_height);
                    next_unlock_blocks =
                        Some(next_unlock_blocks.map_or(blocks, |prior| prior.min(blocks)));
                }
            }
        }
        let mut history = self
            .state
            .history
            .iter()
            .rev()
            .take(20)
            .cloned()
            .collect::<Vec<_>>();
        for p in self
            .state
            .payments
            .iter()
            .rev()
            .filter(|p| p.mined_height.is_some())
            .take(20)
        {
            if let Some(e) = history.iter_mut().find(|e| e.transaction == p.transaction) {
                e.sent_atomic = Some(p.amount_atomic);
                e.destination = Some(p.destination.clone());
            } else {
                history.push(HistoryEntry {
                    transaction: p.transaction.clone(),
                    height: p.mined_height.ok_or(EngineError::Storage)?,
                    received_atomic: 0,
                    sent_atomic: Some(p.amount_atomic),
                    destination: Some(p.destination.clone()),
                });
            }
        }
        history.sort_by_key(|e| std::cmp::Reverse(e.height));
        history.truncate(20);
        Ok(SyncStatus {
            start_height: self.state.start_height,
            has_scan: !self.state.checkpoints.is_empty(),
            next_height: self.state.next_height,
            target_height: self.target_height,
            synced: self.synced && self.rpc.is_some(),
            view_only: self.spend.is_none(),
            received_atomic: received,
            balance_atomic: self.spend.as_ref().map(|_| balance),
            unlocked_atomic: self.spend.as_ref().map(|_| unlocked),
            next_unlock_blocks: self.spend.as_ref().and(next_unlock_blocks),
            history,
            payments: self
                .state
                .payments
                .iter()
                .rev()
                .take(20)
                .map(|p| {
                    Ok(PaymentStatus {
                        transaction: p.transaction.clone(),
                        destination: p.destination.clone(),
                        amount_atomic: p.amount_atomic,
                        fee_atomic: p.fee_atomic,
                        total_atomic: p
                            .amount_atomic
                            .checked_add(p.fee_atomic)
                            .ok_or(EngineError::Storage)?,
                        mined_height: p.mined_height,
                        confirmations: p
                            .mined_height
                            .map_or(0, |h| self.state.next_height.saturating_sub(h)),
                    })
                })
                .collect::<Result<Vec<_>, EngineError>>()?,
            pending: self
                .state
                .payments
                .iter()
                .rev()
                .find(|p| p.mined_height.is_none())
                .cloned(),
        })
    }
    async fn check_tip(&self) -> Result<[u8; 32], EngineError> {
        if !self.synced {
            return Err(EngineError::SyncRequired);
        }
        let rpc = self.rpc()?;
        let last = self
            .state
            .checkpoints
            .last()
            .ok_or(EngineError::SyncRequired)?;
        let tip = rpc
            .latest_block_number()
            .await
            .map_err(EngineError::node_error)?;
        if (tip as u64) < last.height
            || rpc
                .block_hash(last.height as usize)
                .await
                .map_err(EngineError::node_error)?
                != last.hash
        {
            return Err(EngineError::SyncRequired);
        }
        Ok(last.hash)
    }
    pub async fn confirm(&mut self, review_id: &str) -> Result<String, EngineError> {
        if self.spend.is_none() {
            return Err(EngineError::ViewOnly);
        }
        self.check()?;
        let p = self.prepared.take().ok_or(EngineError::StaleReview)?;
        if p.review_id != review_id || p.created.elapsed() > Duration::from_secs(300) {
            return Err(EngineError::StaleReview);
        }
        self.refresh_review_inputs(&p).await?;
        if self.state.payments.iter().any(|p| p.mined_height.is_none()) {
            return Err(EngineError::Pending);
        }
        let id = p.payment.transaction.clone();
        let mut next = self.state.clone();
        next.payments.push(p.payment);
        // Commit and fsync input reservations BEFORE any broadcast bytes.
        self.commit(next)?;
        self.publish_exact(&id).await
    }
    pub async fn retry_pending(&mut self, id: &str) -> Result<String, EngineError> {
        if self.spend.is_none() {
            return Err(EngineError::ViewOnly);
        }
        self.check_tip().await?;
        self.publish_exact(id).await
    }
    async fn publish_exact(&self, id: &str) -> Result<String, EngineError> {
        let p = self
            .state
            .payments
            .iter()
            .find(|p| p.transaction == id && p.mined_height.is_none())
            .ok_or(EngineError::StaleReview)?;
        let tx = decode_transaction(&p.bytes)?;
        if hex::encode(tx.hash()) != id {
            return Err(EngineError::Verification);
        }
        self.rpc()?
            .publish_transaction(&tx)
            .await
            .map_err(|_| EngineError::BroadcastUnknown)?;
        self.check()?;
        Ok(id.to_owned())
    }
    pub fn rescan(&mut self, height: u64) -> Result<(), EngineError> {
        self.check()?;
        if height > u64::from(u32::MAX) {
            return Err(EngineError::Height);
        }
        // Moving forward is an explicit fresh-wallet optimization. Never hide
        // locally known funds, history or a durable outbound reservation.
        if height > self.state.start_height
            && (!self.state.outputs.is_empty()
                || !self.state.history.is_empty()
                || !self.state.payments.is_empty())
        {
            return Err(EngineError::ScanHistory);
        }
        if self.target_height != 0 && height > self.target_height {
            return Err(EngineError::Height);
        }
        let mut next = State::new(self.state.address.clone(), height);
        next.subaddress_count = self.state.subaddress_count;
        next.created_subaddress = self.state.created_subaddress;
        next.selected_subaddress = self.state.selected_subaddress;
        next.subaddress_labels = self.state.subaddress_labels.clone();
        next.payments = self.state.payments.clone();
        for p in &mut next.payments {
            p.mined_height = None;
        }
        self.synced = false;
        self.prepared = None;
        self.decoy_cache = None;
        self.store.clear_distribution()?;
        self.batch_size = batch::INITIAL.min(self.batch_limit);
        self.commit(next)
    }
}
fn unlock_height(height: u64, coinbase: bool, timelock: Timelock) -> Option<u64> {
    let maturity = height.saturating_add(if coinbase { 60 } else { 10 });
    match timelock {
        Timelock::None => Some(maturity),
        Timelock::Block(h) => Some(maturity.max((h as u64).saturating_add(1))),
        // Keep time-based locks unavailable until the consensus-clock adapter exists.
        Timelock::Time(_) => None,
    }
}
impl WalletEngine<NodeTransport> {
    pub async fn connect_direct_resolved(
        &mut self,
        node: &str,
        addresses: &[String],
    ) -> Result<(), EngineError> {
        self.check()?;
        let transport = NodeTransport::direct_resolved(node, addresses, self.cancel.clone())
            .map_err(EngineError::node_error)?;
        self.attach_transport(transport).await?;
        self.decoy_source = Some(Self::distribution_source(
            node,
            wallet_policy::connection::NetworkRoute::Direct,
        )?);
        Ok(())
    }

    pub async fn connect(
        &mut self,
        node: &str,
        route: wallet_policy::connection::NetworkRoute,
    ) -> Result<(), EngineError> {
        self.check()?;
        let t = NodeTransport::connect(node, route, &self.directory, self.cancel.clone())
            .await
            .map_err(EngineError::node_error)?;
        self.attach_transport(t).await?;
        self.decoy_source = Some(Self::distribution_source(node, route)?);
        Ok(())
    }
}
impl<T: HttpTransport + Send + 'static> WalletEngine<T> {
    fn distribution_source(
        node: &str,
        route: wallet_policy::connection::NetworkRoute,
    ) -> Result<[u8; 32], EngineError> {
        use sha2::{Digest, Sha256};
        let endpoint =
            wallet_policy::connection::NodeEndpoint::parse(node).map_err(|_| EngineError::Node)?;
        let route = match route {
            wallet_policy::connection::NetworkRoute::Direct => b"direct".as_slice(),
            wallet_policy::connection::NetworkRoute::Tor => b"tor".as_slice(),
        };
        let mut digest = Sha256::new();
        digest.update(b"independent-wallet-count-source-v1\0");
        digest.update(route);
        digest.update(b"\0");
        digest.update(endpoint.as_str().as_bytes());
        Ok(digest.finalize().into())
    }
}
fn decode_output(r: &OutputRecord) -> Result<WalletOutput, EngineError> {
    let b = Zeroizing::new(hex::decode(&r.bytes).map_err(|_| EngineError::Storage)?);
    let mut reader = b.as_slice();
    let o = WalletOutput::read(&mut reader).map_err(|_| EngineError::Storage)?;
    if !reader.is_empty() {
        return Err(EngineError::Storage);
    }
    Ok(o)
}
fn decode_transaction(b: &str) -> Result<Transaction, EngineError> {
    if b.len() > 256_000 {
        return Err(EngineError::Storage);
    }
    let b = Zeroizing::new(hex::decode(b).map_err(|_| EngineError::Storage)?);
    let mut r = b.as_slice();
    let tx = Transaction::read(&mut r).map_err(|_| EngineError::Storage)?;
    if !r.is_empty() {
        return Err(EngineError::Storage);
    }
    Ok(tx)
}
fn genesis(network: Network) -> Result<[u8; 32], EngineError> {
    // Monero v0.18.5.4 src/cryptonote_config.h, mainnet/stagenet.
    let (tx, nonce) = match network {
        Network::Mainnet => ("013c01ff0001ffffffffffff03029b2e4c0281c0b02e7c53291a94d1d0cbff8883f8024f5142ee494ffbbd08807121017767aafcde9be00dcfd098715ebcf7f410daebc582fda69d24a28e9d0bc890d1", 10000),
        Network::Stagenet => ("013c01ff0001ffffffffffff0302df5d56da0c7d643ddd1ce61901c7bdc5fb1738bfe39fbe69c28a3a7032729c0f2101168d0c4ca86fb55a4cf6a36d31431be1c53a3bd7411bb24e8832410289fa6f3b", 10002),
        _ => return Err(EngineError::Network),
    };
    let b = hex::decode(tx).map_err(|_| EngineError::Verification)?;
    let miner = Transaction::read(&mut b.as_slice()).map_err(|_| EngineError::Verification)?;
    Ok(Block::new(
        BlockHeader {
            hardfork_version: 1,
            hardfork_signal: 0,
            timestamp: 0,
            previous: [0; 32],
            nonce,
        },
        miner,
        vec![],
    )
    .ok_or(EngineError::Verification)?
    .hash())
}
#[cfg(test)]
mod connection_tests;
#[cfg(test)]
mod decoy_tests;
#[cfg(test)]
mod payment_tests;
#[cfg(test)]
mod regtest;
#[cfg(test)]
mod scan_tests;

#[cfg(test)]
mod network_tests {
    use super::*;
    #[test]
    fn output_unlock_thresholds_preserve_maturity_and_additional_locks() {
        assert_eq!(unlock_height(100, false, Timelock::None), Some(110));
        assert_eq!(unlock_height(100, true, Timelock::None), Some(160));
        assert_eq!(unlock_height(100, false, Timelock::Block(105)), Some(110));
        assert_eq!(unlock_height(100, false, Timelock::Block(130)), Some(131));
        assert_eq!(
            unlock_height(100, false, Timelock::Time(1_700_000_000)),
            None
        );
    }
    #[test]
    fn official_genesis_identities_are_distinct() {
        assert_eq!(
            hex::encode(genesis(Network::Mainnet).unwrap()),
            "418015bb9ae982a1975da7d79277c2705727a56894ba0fb246adaabb1f4632e3"
        );
        assert_ne!(
            genesis(Network::Mainnet).unwrap(),
            genesis(Network::Stagenet).unwrap()
        );
        assert!(genesis(Network::Testnet).is_err());
    }
}
