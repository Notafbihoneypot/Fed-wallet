//! Authenticated encrypted checkpoints and a durable pending-payment journal.
use crate::{EngineError, State};
use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    ChaCha20Poly1305, Nonce,
};
use hkdf::Hkdf;
use rand_core::{OsRng, RngCore};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
};
use zeroize::Zeroizing;
const MAX_CACHE: u64 = 32 * 1024 * 1024;
const AAD: &[u8] = b"independent-wallet-stagenet-cache-v1";
mod decoys;
pub struct Store {
    path: PathBuf,
    key: Zeroizing<[u8; 32]>,
    _lease: File,
    #[cfg(test)]
    pub(crate) saves: std::sync::atomic::AtomicUsize,
}
impl Store {
    pub fn open(directory: &Path, address: &str, view: &[u8; 32]) -> Result<Self, EngineError> {
        fs::create_dir_all(directory).map_err(|_| EngineError::Storage)?;
        let id = hex::encode(Sha256::digest(address.as_bytes()));
        let path = directory.join(format!("scan-{id}.bin"));
        let lease = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .open(directory.join(format!("scan-{id}.lock")))
            .map_err(|_| EngineError::Storage)?;
        lease.try_lock().map_err(|_| EngineError::Busy)?;
        let mut key = Zeroizing::new([0; 32]);
        Hkdf::<Sha256>::new(Some(AAD), view)
            .expand(address.as_bytes(), key.as_mut())
            .map_err(|_| EngineError::Storage)?;
        Ok(Self {
            path,
            key,
            _lease: lease,
            #[cfg(test)]
            saves: std::sync::atomic::AtomicUsize::new(0),
        })
    }
    pub fn load(&self) -> Result<Option<State>, EngineError> {
        let f = match File::open(&self.path) {
            Ok(f) => f,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(_) => return Err(EngineError::Storage),
        };
        let mut b = vec![];
        f.take(MAX_CACHE + 1)
            .read_to_end(&mut b)
            .map_err(|_| EngineError::Storage)?;
        if b.len() < 32 || b.len() as u64 > MAX_CACHE || &b[..4] != b"IWS1" {
            return Err(EngineError::Storage);
        }
        let c = ChaCha20Poly1305::new((&*self.key).into());
        let p = Zeroizing::new(
            c.decrypt(
                Nonce::from_slice(&b[4..16]),
                Payload {
                    msg: &b[16..],
                    aad: AAD,
                },
            )
            .map_err(|_| EngineError::Storage)?,
        );
        let state: State = serde_json::from_slice(&p).map_err(|_| EngineError::Storage)?;
        state.validate()?;
        Ok(Some(state))
    }
    pub fn save(&self, state: &State) -> Result<(), EngineError> {
        state.validate()?;
        let p = Zeroizing::new(serde_json::to_vec(state).map_err(|_| EngineError::Storage)?);
        if p.len() as u64 + 32 > MAX_CACHE {
            return Err(EngineError::Storage);
        }
        let mut n = [0; 12];
        OsRng
            .try_fill_bytes(&mut n)
            .map_err(|_| EngineError::Entropy)?;
        let c = ChaCha20Poly1305::new((&*self.key).into());
        let b = c
            .encrypt(Nonce::from_slice(&n), Payload { msg: &p, aad: AAD })
            .map_err(|_| EngineError::Storage)?;
        let tmp = self.path.with_extension("tmp");
        let mut f = OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .open(&tmp)
            .map_err(|_| EngineError::Storage)?;
        f.write_all(b"IWS1")
            .and_then(|_| f.write_all(&n))
            .and_then(|_| f.write_all(&b))
            .and_then(|_| f.sync_all())
            .map_err(|_| EngineError::Storage)?;
        fs::rename(tmp, &self.path).map_err(|_| EngineError::Storage)?;
        File::open(self.path.parent().ok_or(EngineError::Storage)?)
            .and_then(|d| d.sync_all())
            .map_err(|_| EngineError::Storage)?;
        #[cfg(test)]
        self.saves.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        Ok(())
    }
}
