//! Offline key material only. This crate has no socket, daemon or HTTP dependencies.
#![forbid(unsafe_code)]

pub mod keystone;

use curve25519_dalek::{constants::ED25519_BASEPOINT_TABLE, scalar::Scalar, traits::IsIdentity};
use monero::{cryptonote::hash::Hash, util::address::AddressType, Address, PublicKey};
use rand_core::{CryptoRng, OsRng, RngCore};
use std::str::FromStr;
use zeroize::{Zeroize, Zeroizing};

pub const MAX_SNAPSHOT_BYTES: usize = 4096;
const HEADER_LEN: usize = 16;

#[cfg(test)]
mod entropy_tests;
#[cfg(test)]
mod mainnet_copy_tests;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Network {
    Mainnet,
    Stagenet,
    Testnet,
}

impl Network {
    fn monero(self) -> monero::Network {
        match self {
            Self::Mainnet => monero::Network::Mainnet,
            Self::Stagenet => monero::Network::Stagenet,
            Self::Testnet => monero::Network::Testnet,
        }
    }
    fn tag(self) -> u8 {
        match self {
            Self::Mainnet => 0,
            Self::Stagenet => 1,
            Self::Testnet => 2,
        }
    }
    fn parse(tag: u8) -> Result<Self, KeyError> {
        match tag {
            0 => Ok(Self::Mainnet),
            1 => Ok(Self::Stagenet),
            2 => Ok(Self::Testnet),
            _ => Err(KeyError::InvalidSnapshot),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SeedFormat {
    Polyseed16,
    Legacy25,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    Software,
    ViewOnly,
    Keystone,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WalletDetails {
    pub network: Network,
    pub mode: Mode,
    pub address: String,
    pub seed_format: Option<SeedFormat>,
    pub restore_height: u64,
    /// Approximate date encoded by Polyseed; zero for other imports.
    pub birthday: u64,
}

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum KeyError {
    #[error("Invalid seed. Use the full English words and the matching format.")]
    InvalidSeed,
    #[error("Invalid private key. Use a nonzero canonical 32-byte scalar in hexadecimal.")]
    InvalidKey,
    #[error("The primary address, network and private view key must agree.")]
    InvalidViewOnly,
    #[error("This seed feature or passphrase format is unsupported.")]
    UnsupportedSeed,
    #[error("Operating-system randomness is unavailable.")]
    EntropyUnavailable,
    #[error("The device clock cannot supply a valid Polyseed birthday.")]
    InvalidClock,
    #[error("The encrypted wallet contains invalid or unsupported data.")]
    InvalidSnapshot,
    #[error("Restore height is outside the supported range.")]
    InvalidHeight,
    #[error("This wallet has no recovery phrase.")]
    NoRecoveryPhrase,
    #[error("Use Keystone's normal Monero / Feather connection QR. The pairing data is invalid or unsupported.")]
    InvalidKeystone,
    #[error("Only a stagenet software wallet can create a separate mainnet copy.")]
    UnsupportedNetworkCopy,
}

enum Origin {
    Polyseed(Zeroizing<String>),
    Legacy(Zeroizing<String>),
    SpendKey,
    ViewOnly,
    Keystone,
}

/// No Debug/Display/Clone: secrets must be requested deliberately.
pub struct OfflineWallet {
    details: WalletDetails,
    origin: Origin,
    spend: Option<Zeroizing<Scalar>>,
    view: Zeroizing<Scalar>,
}

/// Explicit owner-requested export only. Never log or serialize this record.
pub struct ExportedKeys {
    pub public_spend: String,
    pub public_view: String,
    pub private_spend: Option<Zeroizing<String>>,
    pub private_view: Zeroizing<String>,
}

fn public_key(secret: &Scalar) -> PublicKey {
    PublicKey {
        point: (secret * ED25519_BASEPOINT_TABLE).compress(),
    }
}

fn scalar(bytes: &[u8]) -> Result<Zeroizing<Scalar>, KeyError> {
    let mut fixed = Zeroizing::new([0u8; 32]);
    if bytes.len() != fixed.len() {
        return Err(KeyError::InvalidKey);
    }
    fixed.copy_from_slice(bytes);
    let value: Option<Scalar> = Scalar::from_canonical_bytes(*fixed).into();
    let value = Zeroizing::new(value.ok_or(KeyError::InvalidKey)?);
    if *value == Scalar::ZERO {
        return Err(KeyError::InvalidKey);
    }
    Ok(value)
}

fn hex_key(input: &str) -> Result<Zeroizing<Scalar>, KeyError> {
    if input.len() != 64 || !input.bytes().all(|c| c.is_ascii_hexdigit()) {
        return Err(KeyError::InvalidKey);
    }
    let mut bytes = Zeroizing::new([0u8; 32]);
    for (i, pair) in input.as_bytes().as_chunks::<2>().0.iter().enumerate() {
        let digit = |c: u8| {
            if c <= b'9' {
                c - b'0'
            } else {
                c.to_ascii_lowercase() - b'a' + 10
            }
        };
        bytes[i] = digit(pair[0]) * 16 + digit(pair[1]);
    }
    scalar(bytes.as_slice())
}

fn normalized_words(input: &str, count: usize) -> Result<Zeroizing<String>, KeyError> {
    if input.len() > MAX_SNAPSHOT_BYTES || !input.is_ascii() {
        return Err(KeyError::InvalidSeed);
    }
    let words: Vec<_> = input.split_ascii_whitespace().collect();
    if words.len() != count
        || words
            .iter()
            .any(|w| !w.bytes().all(|b| b.is_ascii_lowercase()) || w.len() > 32)
    {
        return Err(KeyError::InvalidSeed);
    }
    Ok(Zeroizing::new(words.join(" ")))
}

impl OfflineWallet {
    /// Explicit copy through the existing seed/key import adapters. The source
    /// wallet, backup, restore height and network remain unchanged. No secret
    /// leaves Rust, and viewing authority cannot acquire a spending key.
    pub fn copy_to_mainnet(&self, restore_height: u64) -> Result<Self, KeyError> {
        if self.details.network != Network::Stagenet || self.details.mode != Mode::Software {
            return Err(KeyError::UnsupportedNetworkCopy);
        }
        match &self.origin {
            Origin::Polyseed(words) => Self::restore_seed(
                Network::Mainnet,
                SeedFormat::Polyseed16,
                words.clone(),
                restore_height,
            ),
            Origin::Legacy(words) => Self::restore_seed(
                Network::Mainnet,
                SeedFormat::Legacy25,
                words.clone(),
                restore_height,
            ),
            Origin::SpendKey => Self::from_spend(
                Network::Mainnet,
                Origin::SpendKey,
                Zeroizing::new(**self.spend.as_ref().ok_or(KeyError::InvalidKey)?),
                None,
                restore_height,
                0,
            ),
            Origin::ViewOnly | Origin::Keystone => Err(KeyError::UnsupportedNetworkCopy),
        }
    }

    pub fn generate(network: Network, format: SeedFormat) -> Result<Self, KeyError> {
        let now = if format == SeedFormat::Polyseed16 {
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_err(|_| KeyError::InvalidClock)?
                .as_secs()
        } else {
            0
        };
        Self::generate_with_rng(network, format, &mut OsRng, now)
    }

    // Kept private: callers cannot substitute a password, timestamp or PRNG for
    // the production OS CSPRNG. Injection exists only for failure-path tests.
    fn generate_with_rng<R: RngCore + CryptoRng>(
        network: Network,
        format: SeedFormat,
        rng: &mut R,
        now: u64,
    ) -> Result<Self, KeyError> {
        match format {
            SeedFormat::Polyseed16 => {
                // Avoid Polyseed's birthday rollover and clocks predating its epoch.
                if !(1_635_768_000..1_635_768_000 + 1024 * 2_629_746).contains(&now) {
                    return Err(KeyError::InvalidClock);
                }
                let mut entropy = Zeroizing::new([0u8; 32]);
                rng.try_fill_bytes(entropy.as_mut())
                    .map_err(|_| KeyError::EntropyUnavailable)?;
                entropy[19..].fill(0);
                entropy[18] &= 0x3f;
                let seed = polyseed::Polyseed::from(polyseed::Language::English, 0, now, entropy)
                    .map_err(|_| KeyError::InvalidSeed)?;
                Self::restore_seed(network, format, seed.to_string(), 0)
            }
            SeedFormat::Legacy25 => {
                let mut entropy = Zeroizing::new([0u8; 64]);
                rng.try_fill_bytes(entropy.as_mut())
                    .map_err(|_| KeyError::EntropyUnavailable)?;
                let secret = Zeroizing::new(Scalar::from_bytes_mod_order_wide(&entropy));
                let seed = monero_seed::Seed::from_entropy(
                    monero_seed::Language::English,
                    Zeroizing::new(secret.to_bytes()),
                )
                .ok_or(KeyError::InvalidSeed)?;
                Self::restore_seed(network, format, seed.to_string(), 0)
            }
        }
    }

    pub fn restore_seed(
        network: Network,
        format: SeedFormat,
        words: Zeroizing<String>,
        restore_height: u64,
    ) -> Result<Self, KeyError> {
        let (origin, spend, birthday) = match format {
            SeedFormat::Polyseed16 => {
                let words = normalized_words(&words, 16)?;
                let seed =
                    polyseed::Polyseed::from_string(polyseed::Language::English, words.clone())
                        .map_err(|_| KeyError::InvalidSeed)?;
                if seed.features() != 0 {
                    return Err(KeyError::UnsupportedSeed);
                }
                let canonical = seed.to_string();
                if *canonical != *words {
                    return Err(KeyError::InvalidSeed);
                }
                let key = seed.key();
                let reduced = Zeroizing::new(Scalar::from_bytes_mod_order(*key));
                if *reduced == Scalar::ZERO {
                    return Err(KeyError::InvalidSeed);
                }
                (Origin::Polyseed(canonical), reduced, seed.birthday())
            }
            SeedFormat::Legacy25 => {
                let words = normalized_words(&words, 25)?;
                let seed =
                    monero_seed::Seed::from_string(monero_seed::Language::English, words.clone())
                        .map_err(|_| KeyError::InvalidSeed)?;
                let canonical = seed.to_string();
                if *canonical != *words {
                    return Err(KeyError::InvalidSeed);
                }
                let spend = scalar(seed.entropy().as_slice())?;
                (Origin::Legacy(canonical), spend, 0)
            }
        };
        Self::from_spend(
            network,
            origin,
            spend,
            Some(format),
            restore_height,
            birthday,
        )
    }

    pub fn restore_spend_key(
        network: Network,
        hex: Zeroizing<String>,
        restore_height: u64,
    ) -> Result<Self, KeyError> {
        Self::from_spend(
            network,
            Origin::SpendKey,
            hex_key(&hex)?,
            None,
            restore_height,
            0,
        )
    }

    fn from_spend(
        network: Network,
        origin: Origin,
        spend: Zeroizing<Scalar>,
        seed_format: Option<SeedFormat>,
        restore_height: u64,
        birthday: u64,
    ) -> Result<Self, KeyError> {
        if restore_height > u64::from(u32::MAX) {
            return Err(KeyError::InvalidHeight);
        }
        let bytes = Zeroizing::new(spend.to_bytes());
        // Use monero-rs' established Keccak-to-scalar implementation.
        let mut derived = Hash::hash_to_scalar(bytes.as_slice());
        let view = Zeroizing::new(derived.scalar);
        derived.scalar.zeroize();
        if *view == Scalar::ZERO {
            return Err(KeyError::InvalidKey);
        }
        let address = Address::standard(network.monero(), public_key(&spend), public_key(&view));
        Ok(Self {
            details: WalletDetails {
                network,
                mode: Mode::Software,
                address: address.to_string(),
                seed_format,
                restore_height,
                birthday,
            },
            origin,
            spend: Some(spend),
            view,
        })
    }

    pub fn restore_view_only(
        network: Network,
        address: &str,
        hex: Zeroizing<String>,
        restore_height: u64,
    ) -> Result<Self, KeyError> {
        if address.len() != 95 || !address.is_ascii() {
            return Err(KeyError::InvalidViewOnly);
        }
        let parsed = Address::from_str(address).map_err(|_| KeyError::InvalidViewOnly)?;
        let view = hex_key(&hex)?;
        if parsed.network != network.monero()
            || parsed.addr_type != AddressType::Standard
            || parsed.to_string() != address
            || parsed.public_view != public_key(&view)
        {
            return Err(KeyError::InvalidViewOnly);
        }
        // Reject identity/small-order/mixed-order public spend points.
        let point = parsed
            .public_spend
            .point
            .decompress()
            .ok_or(KeyError::InvalidViewOnly)?;
        if point.is_identity() || !point.is_torsion_free() {
            return Err(KeyError::InvalidViewOnly);
        }
        if restore_height > u64::from(u32::MAX) {
            return Err(KeyError::InvalidHeight);
        }
        Ok(Self {
            details: WalletDetails {
                network,
                mode: Mode::ViewOnly,
                address: address.to_owned(),
                seed_format: None,
                restore_height,
                birthday: 0,
            },
            origin: Origin::ViewOnly,
            spend: None,
            view,
        })
    }

    pub fn details(&self) -> WalletDetails {
        self.details.clone()
    }

    /// A dedicated hardware account contains only viewing authority. Its
    /// recovery phrase/spend key never enters this constructor or snapshot.
    pub fn restore_keystone(
        network: Network,
        address: &str,
        view: Zeroizing<String>,
        height: u64,
    ) -> Result<Self, KeyError> {
        let mut wallet = Self::restore_view_only(network, address, view, height)?;
        wallet.details.mode = Mode::Keystone;
        wallet.origin = Origin::Keystone;
        Ok(wallet)
    }
    pub fn has_local_spend_key(&self) -> bool {
        self.spend.is_some()
    }
    /// Rust-only engine boundary. These scalars must never cross the UI/FFI.
    pub fn with_secret_keys<T>(&self, use_keys: impl FnOnce(&Scalar, Option<&Scalar>) -> T) -> T {
        use_keys(&self.view, self.spend.as_deref())
    }
    pub fn export_keys(&self) -> Result<ExportedKeys, KeyError> {
        let address =
            Address::from_str(&self.details.address).map_err(|_| KeyError::InvalidSnapshot)?;
        Ok(ExportedKeys {
            public_spend: address.public_spend.to_string(),
            public_view: public_key(&self.view).to_string(),
            private_view: Zeroizing::new(hex::encode(self.view.to_bytes())),
            private_spend: if self.details.mode == Mode::Software {
                self.spend
                    .as_ref()
                    .map(|s| Zeroizing::new(hex::encode(s.to_bytes())))
            } else {
                None
            },
        })
    }
    pub fn recovery_phrase(&self) -> Result<Zeroizing<String>, KeyError> {
        match &self.origin {
            Origin::Polyseed(s) | Origin::Legacy(s) => Ok(s.clone()),
            _ => Err(KeyError::NoRecoveryPhrase),
        }
    }

    /// Plaintext for the authenticated Android vault only; never write directly to disk.
    pub fn snapshot(&self) -> Zeroizing<Vec<u8>> {
        let (tag, payload) = match &self.origin {
            Origin::Polyseed(s) => (1u8, Zeroizing::new(s.as_bytes().to_vec())),
            Origin::Legacy(s) => (2, Zeroizing::new(s.as_bytes().to_vec())),
            Origin::SpendKey => (
                3,
                Zeroizing::new(
                    self.spend
                        .as_ref()
                        .expect("spend origin")
                        .to_bytes()
                        .to_vec(),
                ),
            ),
            Origin::ViewOnly | Origin::Keystone => {
                // Reserve before copying the key: growing a secret Vec can free
                // its old allocation without Zeroizing ever seeing that buffer.
                let mut payload = Zeroizing::new(Vec::with_capacity(127));
                let view_bytes = Zeroizing::new(self.view.to_bytes());
                payload.extend_from_slice(view_bytes.as_slice());
                payload.extend_from_slice(self.details.address.as_bytes());
                (
                    if matches!(self.origin, Origin::Keystone) {
                        5
                    } else {
                        4
                    },
                    payload,
                )
            }
        };
        let mut result = Zeroizing::new(Vec::with_capacity(HEADER_LEN + payload.len()));
        result.extend_from_slice(b"IWK1");
        result.extend_from_slice(&[self.details.network.tag(), tag]);
        result.extend_from_slice(&self.details.restore_height.to_le_bytes());
        result.extend_from_slice(
            &u16::try_from(payload.len())
                .expect("bounded key payload")
                .to_le_bytes(),
        );
        result.extend_from_slice(&payload);
        result
    }

    pub fn from_snapshot(bytes: Zeroizing<Vec<u8>>) -> Result<Self, KeyError> {
        if bytes.len() < HEADER_LEN || bytes.len() > MAX_SNAPSHOT_BYTES || &bytes[..4] != b"IWK1" {
            return Err(KeyError::InvalidSnapshot);
        }
        let network = Network::parse(bytes[4])?;
        let height = u64::from_le_bytes(
            bytes[6..14]
                .try_into()
                .map_err(|_| KeyError::InvalidSnapshot)?,
        );
        let len = usize::from(u16::from_le_bytes(
            bytes[14..16]
                .try_into()
                .map_err(|_| KeyError::InvalidSnapshot)?,
        ));
        if len != bytes.len() - HEADER_LEN {
            return Err(KeyError::InvalidSnapshot);
        }
        let payload = &bytes[HEADER_LEN..];
        let restored = match bytes[5] {
            1 | 2 => {
                let words = Zeroizing::new(
                    std::str::from_utf8(payload)
                        .map_err(|_| KeyError::InvalidSnapshot)?
                        .to_owned(),
                );
                Self::restore_seed(
                    network,
                    if bytes[5] == 1 {
                        SeedFormat::Polyseed16
                    } else {
                        SeedFormat::Legacy25
                    },
                    words,
                    height,
                )
            }
            3 => Self::from_spend(network, Origin::SpendKey, scalar(payload)?, None, height, 0),
            4 | 5 if payload.len() == 127 => {
                let view = scalar(&payload[..32])?;
                let address =
                    std::str::from_utf8(&payload[32..]).map_err(|_| KeyError::InvalidSnapshot)?;
                // Encoding only, no additional derivation.
                let mut hex = Zeroizing::new(String::with_capacity(64));
                let view_bytes = Zeroizing::new(view.to_bytes());
                const DIGITS: &[u8] = b"0123456789abcdef";
                for b in view_bytes.iter() {
                    hex.push(char::from(DIGITS[usize::from(b >> 4)]));
                    hex.push(char::from(DIGITS[usize::from(b & 15)]));
                }
                if bytes[5] == 5 {
                    Self::restore_keystone(network, address, hex, height)
                } else {
                    Self::restore_view_only(network, address, hex, height)
                }
            }
            _ => Err(KeyError::InvalidSnapshot),
        };
        restored.map_err(|_| KeyError::InvalidSnapshot)
    }
}
