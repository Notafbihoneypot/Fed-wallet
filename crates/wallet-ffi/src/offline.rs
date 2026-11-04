use rand_core::{OsRng, RngCore};
use std::sync::{Arc, Mutex, MutexGuard, Weak};
use wallet_keys::{Network, OfflineWallet, SeedFormat};
use zeroize::Zeroizing;

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum KeyNetwork {
    Mainnet,
    Stagenet,
    Testnet,
}
impl From<KeyNetwork> for Network {
    fn from(n: KeyNetwork) -> Self {
        match n {
            KeyNetwork::Mainnet => Self::Mainnet,
            KeyNetwork::Stagenet => Self::Stagenet,
            KeyNetwork::Testnet => Self::Testnet,
        }
    }
}
impl From<Network> for KeyNetwork {
    fn from(n: Network) -> Self {
        match n {
            Network::Mainnet => Self::Mainnet,
            Network::Stagenet => Self::Stagenet,
            Network::Testnet => Self::Testnet,
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum RecoveryFormat {
    Polyseed16,
    Legacy25,
}
impl From<RecoveryFormat> for SeedFormat {
    fn from(f: RecoveryFormat) -> Self {
        match f {
            RecoveryFormat::Polyseed16 => Self::Polyseed16,
            RecoveryFormat::Legacy25 => Self::Legacy25,
        }
    }
}
impl From<SeedFormat> for RecoveryFormat {
    fn from(f: SeedFormat) -> Self {
        match f {
            SeedFormat::Polyseed16 => Self::Polyseed16,
            SeedFormat::Legacy25 => Self::Legacy25,
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum KeyWalletMode {
    Software,
    ViewOnly,
    Keystone,
}

#[derive(uniffi::Record)]
pub struct KeystoneScanProgress {
    pub complete: bool,
    pub frames: u32,
    pub fragments: u32,
}

#[derive(uniffi::Object)]
pub struct KeystonePairingSession {
    decoder: Mutex<wallet_keys::keystone::PairingDecoder>,
}
#[uniffi::export]
impl KeystonePairingSession {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            decoder: Mutex::new(Default::default()),
        })
    }
    pub fn receive_frame(&self, frame: String) -> Result<KeystoneScanProgress, WalletError> {
        let mut d = self.decoder.lock().map_err(|_| WalletError::Locked)?;
        d.receive(Zeroizing::new(frame))?;
        Ok(KeystoneScanProgress {
            complete: d.complete(),
            frames: d.received(),
            fragments: d.total(),
        })
    }
    pub fn finish(
        &self,
        restore_height: Option<u64>,
    ) -> Result<Arc<OfflineWalletSession>, WalletError> {
        let wallet = self
            .decoder
            .lock()
            .map_err(|_| WalletError::Locked)?
            .finish(restore_height)?;
        Ok(OfflineWalletSession::imported(wallet))
    }
}

#[derive(uniffi::Record)]
pub struct OfflineWalletInfo {
    pub network: KeyNetwork,
    pub mode: KeyWalletMode,
    pub address: String,
    pub seed_format: Option<RecoveryFormat>,
    pub restore_height: u64,
    pub birthday: u64,
    pub backup_verified: bool,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum WalletError {
    #[error("{detail}")]
    InvalidInput { detail: String },
    #[error("The wallet is locked.")]
    Locked,
    #[error("Check the requested recovery words before saving this wallet.")]
    BackupRequired,
}
impl From<wallet_keys::KeyError> for WalletError {
    fn from(e: wallet_keys::KeyError) -> Self {
        Self::InvalidInput {
            detail: e.to_string(),
        }
    }
}

struct Session {
    wallet: OfflineWallet,
    backup_verified: bool,
    challenge: Vec<u32>,
    online: Vec<Weak<crate::OnlineWalletSession>>,
}
impl Drop for Session {
    fn drop(&mut self) {
        for online in &self.online {
            if let Some(online) = online.upgrade() {
                online.disconnect();
            }
        }
    }
}

/// Sensitive material remains in Rust until explicit display or vault encryption.
/// Calling lock invalidates every further operation, including storage export.
#[derive(uniffi::Object)]
pub struct OfflineWalletSession {
    state: Mutex<Option<Session>>,
}

#[derive(uniffi::Record)]
pub struct ExportedWalletKeys {
    pub public_spend: String,
    pub public_view: String,
    pub private_spend: Option<String>,
    pub private_view: String,
}

impl OfflineWalletSession {
    fn imported(wallet: OfflineWallet) -> Arc<Self> {
        Arc::new(Self {
            state: Mutex::new(Some(Session {
                wallet,
                backup_verified: true,
                challenge: vec![],
                online: vec![],
            })),
        })
    }
    fn state(&self) -> Result<MutexGuard<'_, Option<Session>>, WalletError> {
        self.state.lock().map_err(|_| WalletError::Locked)
    }
    fn reserve_online(
        &self,
        node: String,
        directory: &str,
        route: crate::WalletNetworkRoute,
    ) -> Result<(Arc<crate::OnlineWalletSession>, Zeroizing<Vec<u8>>), crate::OnlineError> {
        let online = crate::OnlineWalletSession::uninitialized(node, directory, route)?;
        let mut guard = self.state.lock().map_err(|_| crate::OnlineError::Closed)?;
        let session = guard.as_mut().ok_or(crate::OnlineError::Closed)?;
        if !session.backup_verified {
            return Err(crate::OnlineError::Closed);
        }
        session.online.retain(|online| online.strong_count() > 0);
        if session
            .online
            .iter()
            .any(|online| online.upgrade().is_some())
        {
            return Err(crate::OnlineError::Failed {
                detail: "Disconnect the previous wallet session first.".into(),
            });
        }
        let snapshot = session.wallet.snapshot();
        session.online.push(Arc::downgrade(&online));
        Ok((online, snapshot))
    }
    fn with_receiving<T>(
        &self,
        directory: &str,
        operation: impl FnOnce(&crate::OnlineWalletSession) -> Result<T, crate::OnlineError>,
    ) -> Result<T, crate::OnlineError> {
        let (child, snapshot) = {
            let mut guard = self.state.lock().map_err(|_| crate::OnlineError::Closed)?;
            let owner = guard.as_mut().ok_or(crate::OnlineError::Closed)?;
            if !owner.backup_verified {
                return Err(crate::OnlineError::Closed);
            }
            owner.online.retain(|child| child.strong_count() > 0);
            if let Some(child) = owner.online.iter().find_map(Weak::upgrade) {
                if child.directory != directory {
                    return Err(crate::OnlineError::Closed);
                }
                (child, None)
            } else {
                let child = crate::OnlineWalletSession::local_uninitialized(directory)?;
                let snapshot = owner.wallet.snapshot();
                owner.online.push(Arc::downgrade(&child));
                (child, Some(snapshot))
            }
        };
        // Never hold the owner mutex over cache I/O or the engine mutex. Locking
        // the owner can immediately cancel this child, including a local one.
        if let Some(snapshot) = snapshot {
            child.initialize(snapshot, directory)?;
        }
        operation(&child)
    }
}

#[uniffi::export]
impl OfflineWalletSession {
    pub fn receiving_addresses(
        &self,
        directory: String,
    ) -> Result<crate::ReceivingAddressBook, crate::OnlineError> {
        self.with_receiving(&directory, |child| child.receiving_addresses())
    }
    pub fn create_subaddress(
        &self,
        directory: String,
        label: String,
    ) -> Result<crate::ReceivingAddressBook, crate::OnlineError> {
        self.with_receiving(&directory, |child| child.create_subaddress(label))
    }
    pub fn select_subaddress(
        &self,
        directory: String,
        index: u32,
    ) -> Result<crate::ReceivingAddressBook, crate::OnlineError> {
        self.with_receiving(&directory, |child| child.select_subaddress(index))
    }
    pub fn label_subaddress(
        &self,
        directory: String,
        index: u32,
        label: String,
    ) -> Result<crate::ReceivingAddressBook, crate::OnlineError> {
        self.with_receiving(&directory, |child| child.label_subaddress(index, label))
    }
    /// A new independent mainnet owner; never edits or relabels the source.
    pub fn copy_to_mainnet(&self, restore_height: u64) -> Result<Arc<Self>, WalletError> {
        let guard = self.state()?;
        let original = guard.as_ref().ok_or(WalletError::Locked)?;
        if !original.backup_verified {
            return Err(WalletError::BackupRequired);
        }
        Ok(Self::imported(
            original.wallet.copy_to_mainnet(restore_height)?,
        ))
    }

    #[uniffi::constructor]
    pub fn generate(network: KeyNetwork, format: RecoveryFormat) -> Result<Arc<Self>, WalletError> {
        let wallet = OfflineWallet::generate(network.into(), format.into())?;
        let count = match format {
            RecoveryFormat::Polyseed16 => 16,
            RecoveryFormat::Legacy25 => 25,
        };
        let mut challenge = Vec::with_capacity(3);
        while challenge.len() < 3 {
            let mut random = [0u8; 4];
            OsRng
                .try_fill_bytes(&mut random)
                .map_err(|_| wallet_keys::KeyError::EntropyUnavailable)?;
            let index = u32::from_le_bytes(random) % count;
            if !challenge.contains(&index) {
                challenge.push(index);
            }
        }
        challenge.sort_unstable();
        Ok(Arc::new(Self {
            state: Mutex::new(Some(Session {
                wallet,
                backup_verified: false,
                challenge,
                online: vec![],
            })),
        }))
    }

    #[uniffi::constructor]
    pub fn restore_seed(
        network: KeyNetwork,
        format: RecoveryFormat,
        words: String,
        restore_height: u64,
    ) -> Result<Arc<Self>, WalletError> {
        Ok(Self::imported(OfflineWallet::restore_seed(
            network.into(),
            format.into(),
            Zeroizing::new(words),
            restore_height,
        )?))
    }

    #[uniffi::constructor]
    pub fn import_spend_key(
        network: KeyNetwork,
        hex: String,
        restore_height: u64,
    ) -> Result<Arc<Self>, WalletError> {
        Ok(Self::imported(OfflineWallet::restore_spend_key(
            network.into(),
            Zeroizing::new(hex),
            restore_height,
        )?))
    }

    #[uniffi::constructor]
    pub fn import_view_only(
        network: KeyNetwork,
        address: String,
        view_key_hex: String,
        restore_height: u64,
    ) -> Result<Arc<Self>, WalletError> {
        Ok(Self::imported(OfflineWallet::restore_view_only(
            network.into(),
            &address,
            Zeroizing::new(view_key_hex),
            restore_height,
        )?))
    }

    #[uniffi::constructor]
    pub fn open_snapshot(bytes: Vec<u8>) -> Result<Arc<Self>, WalletError> {
        Ok(Self::imported(OfflineWallet::from_snapshot(
            Zeroizing::new(bytes),
        )?))
    }

    /// Resume an encrypted setup checkpoint without treating an unfinished
    /// generated backup as verified. This constructor never opens networking.
    #[uniffi::constructor]
    pub fn open_setup_snapshot(bytes: Vec<u8>) -> Result<Arc<Self>, WalletError> {
        let bytes = Zeroizing::new(bytes);
        let invalid = || WalletError::InvalidInput {
            detail: "The saved setup is invalid or unsupported.".into(),
        };
        if bytes.len() < 8
            || bytes.len() > wallet_keys::MAX_SNAPSHOT_BYTES + 20
            || &bytes[..4] != b"IWS1"
        {
            return Err(invalid());
        }
        let verified = match bytes[4] {
            0 => false,
            1 => true,
            _ => return Err(invalid()),
        };
        let count = usize::from(bytes[5]);
        if count != if verified { 0 } else { 3 } {
            return Err(invalid());
        }
        let length = usize::from(u16::from_le_bytes([bytes[6], bytes[7]]));
        let start = 8 + count * 4;
        if start + length != bytes.len() {
            return Err(invalid());
        }
        let wallet = OfflineWallet::from_snapshot(Zeroizing::new(bytes[start..].to_vec()))
            .map_err(|_| invalid())?;
        let mut challenge = Vec::with_capacity(count);
        if !verified {
            let words = match wallet.details().seed_format {
                Some(SeedFormat::Polyseed16) => 16,
                Some(SeedFormat::Legacy25) => 25,
                None => return Err(invalid()),
            };
            if !wallet.has_local_spend_key() {
                return Err(invalid());
            }
            for index in 0..count {
                let p = 8 + index * 4;
                let value = u32::from_le_bytes(bytes[p..p + 4].try_into().map_err(|_| invalid())?);
                if value >= words || challenge.last().is_some_and(|last| *last >= value) {
                    return Err(invalid());
                }
                challenge.push(value);
            }
        }
        Ok(Arc::new(Self {
            state: Mutex::new(Some(Session {
                wallet,
                backup_verified: verified,
                challenge,
                online: vec![],
            })),
        }))
    }

    pub fn details(&self) -> Result<OfflineWalletInfo, WalletError> {
        let guard = self.state()?;
        let session = guard.as_ref().ok_or(WalletError::Locked)?;
        let d = session.wallet.details();
        Ok(OfflineWalletInfo {
            network: d.network.into(),
            mode: match d.mode {
                wallet_keys::Mode::Software => KeyWalletMode::Software,
                wallet_keys::Mode::ViewOnly => KeyWalletMode::ViewOnly,
                wallet_keys::Mode::Keystone => KeyWalletMode::Keystone,
            },
            address: d.address,
            seed_format: d.seed_format.map(Into::into),
            restore_height: d.restore_height,
            birthday: d.birthday,
            backup_verified: session.backup_verified,
        })
    }

    /// Explicitly reveals a phrase. Kotlin/Compose strings cannot be reliably wiped;
    /// the Android layer must clear the display on backgrounding and never persist plaintext.
    pub fn recovery_phrase(&self) -> Result<String, WalletError> {
        let guard = self.state()?;
        Ok(guard
            .as_ref()
            .ok_or(WalletError::Locked)?
            .wallet
            .recovery_phrase()?
            .to_string())
    }

    /// Explicit authenticated display; Android must keep this ephemeral.
    pub fn export_keys(&self) -> Result<ExportedWalletKeys, WalletError> {
        let guard = self.state()?;
        let session = guard.as_ref().ok_or(WalletError::Locked)?;
        if !session.backup_verified {
            return Err(WalletError::BackupRequired);
        }
        let keys = session.wallet.export_keys()?;
        Ok(ExportedWalletKeys {
            public_spend: keys.public_spend,
            public_view: keys.public_view,
            private_spend: keys.private_spend.as_ref().map(|s| s.to_string()),
            private_view: keys.private_view.to_string(),
        })
    }

    /// Zero-based word positions. Only newly generated wallets require this check.
    pub fn backup_challenge(&self) -> Result<Vec<u32>, WalletError> {
        let guard = self.state()?;
        Ok(guard.as_ref().ok_or(WalletError::Locked)?.challenge.clone())
    }

    pub fn confirm_backup(&self, answers: Vec<String>) -> Result<(), WalletError> {
        let answers: Vec<_> = answers.into_iter().map(Zeroizing::new).collect();
        let mut guard = self.state()?;
        let session = guard.as_mut().ok_or(WalletError::Locked)?;
        if session.backup_verified {
            return Ok(());
        }
        let phrase = session.wallet.recovery_phrase()?;
        let words: Vec<_> = phrase.split_ascii_whitespace().collect();
        if answers.len() != 3
            || !session
                .challenge
                .iter()
                .zip(answers.iter())
                .all(|(index, answer)| words[*index as usize] == answer.trim())
        {
            return Err(WalletError::BackupRequired);
        }
        session.backup_verified = true;
        Ok(())
    }

    /// This is plaintext, intended solely for immediate authenticated encryption.
    /// The app must wipe its byte array in a finally block, including failed saves.
    pub fn snapshot_for_vault(&self) -> Result<Vec<u8>, WalletError> {
        let guard = self.state()?;
        let session = guard.as_ref().ok_or(WalletError::Locked)?;
        if !session.backup_verified {
            return Err(WalletError::BackupRequired);
        }
        Ok(session.wallet.snapshot().to_vec())
    }

    /// Plaintext only for immediate authenticated setup encryption. The backup
    /// status and original challenge are retained, never silently promoted.
    pub fn snapshot_for_setup(&self) -> Result<Vec<u8>, WalletError> {
        let guard = self.state()?;
        let session = guard.as_ref().ok_or(WalletError::Locked)?;
        let snapshot = session.wallet.snapshot();
        let count = if session.backup_verified {
            0
        } else {
            session.challenge.len()
        };
        let mut result = Zeroizing::new(Vec::with_capacity(8 + count * 4 + snapshot.len()));
        result.extend_from_slice(b"IWS1");
        result.push(u8::from(session.backup_verified));
        result.push(count as u8);
        result.extend_from_slice(&(snapshot.len() as u16).to_le_bytes());
        for index in session.challenge.iter().take(count) {
            result.extend_from_slice(&index.to_le_bytes());
        }
        result.extend_from_slice(&snapshot);
        Ok(result.to_vec())
    }

    pub fn lock(&self) {
        if let Ok(mut state) = self.state.lock() {
            *state = None;
        }
    }
    /// Keys are copied only within Rust. Setup/import never calls this.
    pub fn open_online(
        &self,
        node: String,
        directory: String,
        route: crate::WalletNetworkRoute,
    ) -> Result<Arc<crate::OnlineWalletSession>, crate::OnlineError> {
        let (online, snapshot) = self.reserve_online(node, &directory, route)?;
        online.initialize(snapshot, &directory)?;
        Ok(online)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn setup_resume_preserves_unverified_backup_and_original_challenge() {
        for format in [RecoveryFormat::Polyseed16, RecoveryFormat::Legacy25] {
            let original = OfflineWalletSession::generate(KeyNetwork::Stagenet, format).unwrap();
            let address = original.details().unwrap().address;
            let challenge = original.backup_challenge().unwrap();
            let phrase = Zeroizing::new(original.recovery_phrase().unwrap());
            let bytes = original.snapshot_for_setup().unwrap();
            original.lock();
            let restored = OfflineWalletSession::open_setup_snapshot(bytes).unwrap();
            assert!(restored.details().unwrap().address == address);
            assert!(!restored.details().unwrap().backup_verified);
            assert!(restored.backup_challenge().unwrap() == challenge);
            assert!(restored.recovery_phrase().unwrap() == *phrase);
            assert!(matches!(
                restored.snapshot_for_vault(),
                Err(WalletError::BackupRequired)
            ));
            let dir = tempfile::tempdir().unwrap();
            assert!(restored
                .open_online(
                    "https://node.invalid".into(),
                    dir.path().to_str().unwrap().into(),
                    crate::WalletNetworkRoute::Direct
                )
                .is_err());
            let words = phrase.split(' ').collect::<Vec<_>>();
            restored
                .confirm_backup(
                    challenge
                        .iter()
                        .map(|i| words[*i as usize].to_owned())
                        .collect(),
                )
                .unwrap();
            let ready =
                OfflineWalletSession::open_setup_snapshot(restored.snapshot_for_setup().unwrap())
                    .unwrap();
            assert!(ready.details().unwrap().backup_verified);
            assert!(ready.backup_challenge().unwrap().is_empty());
            assert!(ready.snapshot_for_vault().is_ok());
            ready.lock();
            restored.lock();
        }
    }

    #[test]
    fn setup_resume_rejects_corrupt_metadata_and_trailing_bytes() {
        let original =
            OfflineWalletSession::generate(KeyNetwork::Stagenet, RecoveryFormat::Legacy25).unwrap();
        let bytes = Zeroizing::new(original.snapshot_for_setup().unwrap());
        for index in [0, 4, 5, 6, 7, 8] {
            let mut bad = bytes.to_vec();
            bad[index] ^= 0xff;
            assert!(OfflineWalletSession::open_setup_snapshot(bad).is_err());
        }
        let mut trailing = bytes.to_vec();
        trailing.push(0);
        assert!(OfflineWalletSession::open_setup_snapshot(trailing).is_err());
        assert!(OfflineWalletSession::open_setup_snapshot(vec![0; 5000]).is_err());
        original.lock();
        assert!(matches!(
            original.snapshot_for_setup(),
            Err(WalletError::Locked)
        ));
    }

    #[test]
    fn imported_view_only_setup_never_acquires_a_seed_or_spending_authority() {
        let hot = OfflineWallet::restore_spend_key(
            Network::Stagenet,
            Zeroizing::new(format!("01{}", "00".repeat(31))),
            0,
        )
        .unwrap();
        let view = hot.with_secret_keys(|v, _| {
            v.to_bytes()
                .iter()
                .map(|b| format!("{b:02x}"))
                .collect::<String>()
        });
        let watching = OfflineWalletSession::import_view_only(
            KeyNetwork::Stagenet,
            hot.details().address,
            view,
            17,
        )
        .unwrap();
        let restored =
            OfflineWalletSession::open_setup_snapshot(watching.snapshot_for_setup().unwrap())
                .unwrap();
        assert!(restored.details().unwrap().mode == KeyWalletMode::ViewOnly);
        assert!(restored.details().unwrap().restore_height == 17);
        assert!(restored.recovery_phrase().is_err());
        restored.lock();
        watching.lock();
    }

    #[test]
    fn owner_lock_revokes_a_child_before_cache_initialization() {
        let owner = OfflineWalletSession::import_spend_key(
            KeyNetwork::Stagenet,
            "c0af65c0dd837e666b9d0dfed62745f4df35aed7ea619b2798a709f0fe545403".into(),
            0,
        )
        .unwrap();
        let directory = std::env::temp_dir().join("independent-wallet-cancel-before-init");
        let directory = directory.to_str().unwrap();
        let (child, snapshot) = owner
            .reserve_online(
                "https://example.invalid".into(),
                directory,
                crate::WalletNetworkRoute::Direct,
            )
            .unwrap();
        assert!(owner
            .reserve_online(
                "https://example.invalid".into(),
                directory,
                crate::WalletNetworkRoute::Direct
            )
            .is_err());
        owner.lock();
        assert!(matches!(
            child.initialize(snapshot, directory),
            Err(crate::OnlineError::Closed)
        ));
        assert!(matches!(child.status(), Err(crate::OnlineError::Closed)));
    }

    #[test]
    fn concurrent_initialization_cannot_escape_owner_lock() {
        for _ in 0..32 {
            let directory = tempfile::tempdir().unwrap();
            let owner = OfflineWalletSession::import_spend_key(
                KeyNetwork::Stagenet,
                "c0af65c0dd837e666b9d0dfed62745f4df35aed7ea619b2798a709f0fe545403".into(),
                0,
            )
            .unwrap();
            let directory = directory.path().to_str().unwrap().to_owned();
            let (child, snapshot) = owner
                .reserve_online(
                    "https://example.invalid".into(),
                    &directory,
                    crate::WalletNetworkRoute::Direct,
                )
                .unwrap();
            let worker = child.clone();
            let barrier = Arc::new(std::sync::Barrier::new(2));
            let worker_barrier = barrier.clone();
            let thread = std::thread::spawn(move || {
                worker_barrier.wait();
                let _ = worker.initialize(snapshot, &directory);
            });
            barrier.wait();
            owner.lock();
            thread.join().unwrap();
            assert!(matches!(child.status(), Err(crate::OnlineError::Closed)));
        }
    }
}
