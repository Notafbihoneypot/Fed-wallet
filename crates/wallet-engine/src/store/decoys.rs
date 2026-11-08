//! Disposable public counts, never rings, outputs, keys or transaction bytes.
//! Kept separate so a bad/missing acceleration cache cannot lose wallet state.
use super::*;
use crate::engine::{DistributionCache, MAX_COUNTS};
use std::sync::Arc;

const DISTRIBUTION_AAD: &[u8] = b"independent-wallet-public-decoy-counts-v1";
const MAX_DISTRIBUTION_BYTES: usize = MAX_COUNTS * 8 + 73 + 32;

impl Store {
    fn distribution_cipher(&self) -> Result<ChaCha20Poly1305, EngineError> {
        let mut key = Zeroizing::new([0u8; 32]);
        Hkdf::<Sha256>::new(Some(DISTRIBUTION_AAD), self.key.as_ref())
            .expand(b"counts", key.as_mut())
            .map_err(|_| EngineError::Storage)?;
        Ok(ChaCha20Poly1305::new((&*key).into()))
    }
    pub(crate) fn load_distribution(&self) -> Result<Option<DistributionCache>, EngineError> {
        let file = match File::open(self.path.with_extension("decoys")) {
            Ok(file) => file,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(_) => return Err(EngineError::Storage),
        };
        let mut bytes = Vec::new();
        file.take(MAX_DISTRIBUTION_BYTES as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| EngineError::Storage)?;
        if bytes.len() < 113 || bytes.len() > MAX_DISTRIBUTION_BYTES || &bytes[..4] != b"IWD1" {
            return Err(EngineError::Storage);
        }
        let plain = Zeroizing::new(
            self.distribution_cipher()?
                .decrypt(
                    Nonce::from_slice(&bytes[4..16]),
                    Payload {
                        msg: &bytes[16..],
                        aad: DISTRIBUTION_AAD,
                    },
                )
                .map_err(|_| EngineError::Storage)?,
        );
        if plain.len() < 81 || (plain.len() - 73) % 8 != 0 || plain[8] > 1 {
            return Err(EngineError::Storage);
        }
        let height = usize::try_from(u64::from_le_bytes(
            plain[..8].try_into().map_err(|_| EngineError::Storage)?,
        ))
        .map_err(|_| EngineError::Storage)?;
        let cache = DistributionCache {
            height,
            complete: plain[8] == 1,
            hash: plain[9..41].try_into().map_err(|_| EngineError::Storage)?,
            source: plain[41..73].try_into().map_err(|_| EngineError::Storage)?,
            counts: Arc::new(
                plain[73..]
                    .as_chunks::<8>()
                    .0
                    .iter()
                    .map(|b| u64::from_le_bytes(*b))
                    .collect(),
            ),
        };
        cache.validate().map_err(|_| EngineError::Storage)?;
        Ok(Some(cache))
    }
    pub(crate) fn save_distribution(&self, cache: &DistributionCache) -> Result<(), EngineError> {
        cache.validate()?;
        let mut plain = Zeroizing::new(Vec::with_capacity(73 + cache.counts.len() * 8));
        plain.extend_from_slice(&(cache.height as u64).to_le_bytes());
        plain.push(u8::from(cache.complete));
        plain.extend_from_slice(&cache.hash);
        plain.extend_from_slice(&cache.source);
        for count in cache.counts.iter() {
            plain.extend_from_slice(&count.to_le_bytes());
        }
        let mut nonce = [0u8; 12];
        OsRng
            .try_fill_bytes(&mut nonce)
            .map_err(|_| EngineError::Entropy)?;
        let bytes = self
            .distribution_cipher()?
            .encrypt(
                Nonce::from_slice(&nonce),
                Payload {
                    msg: &plain,
                    aad: DISTRIBUTION_AAD,
                },
            )
            .map_err(|_| EngineError::Storage)?;
        let path = self.path.with_extension("decoys");
        let tmp = self.path.with_extension("decoys.tmp");
        let mut file = OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .open(&tmp)
            .map_err(|_| EngineError::Storage)?;
        file.write_all(b"IWD1")
            .and_then(|_| file.write_all(&nonce))
            .and_then(|_| file.write_all(&bytes))
            .and_then(|_| file.sync_all())
            .map_err(|_| {
                // Do not leave a partial temporary file holding scarce space.
                let _ = fs::remove_file(&tmp);
                EngineError::Storage
            })?;
        fs::rename(tmp, path).map_err(|_| EngineError::Storage)?;
        File::open(self.path.parent().ok_or(EngineError::Storage)?)
            .and_then(|directory| directory.sync_all())
            .map_err(|_| EngineError::Storage)
    }
    pub(crate) fn clear_distribution(&self) -> Result<(), EngineError> {
        match fs::remove_file(self.path.with_extension("decoys")) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(_) => Err(EngineError::Storage),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn journal() -> State {
        let wallet = wallet_keys::OfflineWallet::restore_spend_key(
            wallet_keys::Network::Stagenet,
            Zeroizing::new(format!("01{}", "00".repeat(31))),
            0,
        )
        .unwrap();
        State::new(wallet.details().address, 0)
    }
    fn counts(length: usize) -> DistributionCache {
        DistributionCache {
            height: length + 100,
            hash: [42; 32],
            counts: Arc::new((0..length as u64).collect()),
            complete: false,
            source: [7; 32],
        }
    }
    #[test]
    fn maximum_count_cache_round_trips_and_is_domain_separated_from_wallet_state() {
        let directory = tempfile::tempdir().unwrap();
        let store = Store::open(directory.path(), "public-count-fixture", &[7; 32]).unwrap();
        let state = journal();
        store.save(&state).unwrap();
        let expected = counts(MAX_COUNTS);
        store.save_distribution(&expected).unwrap();
        assert_eq!(
            fs::metadata(store.path.with_extension("decoys"))
                .unwrap()
                .len() as usize,
            MAX_DISTRIBUTION_BYTES
        );
        let actual = store.load_distribution().unwrap().unwrap();
        assert_eq!(actual.counts, expected.counts);
        assert_eq!(actual.hash, expected.hash);
        assert_eq!(actual.height, expected.height);
        assert!(!actual.complete);
        assert_eq!(store.load().unwrap().unwrap().address, state.address);
    }
    #[test]
    fn changed_ciphertext_and_wrong_key_cannot_become_a_distribution_or_damage_the_journal() {
        let directory = tempfile::tempdir().unwrap();
        let store = Store::open(directory.path(), "public-count-fixture", &[7; 32]).unwrap();
        store.save(&journal()).unwrap();
        store.save_distribution(&counts(100)).unwrap();
        let path = store.path.with_extension("decoys");
        let original = fs::read(&path).unwrap();
        let mut changed = original.clone();
        changed[30] ^= 1;
        fs::write(&path, &changed).unwrap();
        assert!(store.load_distribution().is_err());
        assert!(store.load().unwrap().is_some());
        fs::write(&path, original).unwrap();
        drop(store);
        let wrong_key = Store::open(directory.path(), "public-count-fixture", &[8; 32]).unwrap();
        assert!(wrong_key.load_distribution().is_err());
    }
    #[test]
    fn invalid_cache_shape_is_rejected_before_replacing_the_last_good_file() {
        let directory = tempfile::tempdir().unwrap();
        let store = Store::open(directory.path(), "public-count-fixture", &[7; 32]).unwrap();
        let good = counts(100);
        store.save_distribution(&good).unwrap();
        let mut invalid = good.clone();
        Arc::make_mut(&mut invalid.counts)[10] = 0;
        assert!(store.save_distribution(&invalid).is_err());
        invalid = good.clone();
        invalid.height = 0;
        assert!(store.save_distribution(&invalid).is_err());
        assert_eq!(
            store.load_distribution().unwrap().unwrap().counts,
            good.counts
        );
        store.clear_distribution().unwrap();
        assert!(store.load_distribution().unwrap().is_none());
    }
}
