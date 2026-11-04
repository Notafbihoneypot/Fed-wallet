//! Offline Feather-compatible Keystone pairing. No signing or network access.
use crate::{KeyError, Network, OfflineWallet};
use monero::Address;
use serde::Deserialize;
use std::{
    collections::BTreeSet,
    str::FromStr,
    time::{Duration, Instant},
};
use zeroize::{Zeroize, ZeroizeOnDrop, Zeroizing};

const MAX_JSON: usize = 4096;
const MAX_FRAME: usize = 9000;
const MAX_PARTS: u32 = 64;
const MAX_FRAMES: usize = 256;
const DEADLINE: Duration = Duration::from_secs(120);

#[derive(Deserialize, Zeroize, ZeroizeOnDrop)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Pairing {
    #[serde(default)]
    version: u32,
    primary_address: String,
    private_view_key: String,
    #[serde(default)]
    restore_height: u64,
    #[serde(default)]
    encrypted: bool,
    source: String,
    #[serde(default)]
    wallet_name: String,
}

pub fn import_pairing(bytes: &[u8]) -> Result<OfflineWallet, KeyError> {
    if bytes.is_empty() || bytes.len() > MAX_JSON {
        return Err(KeyError::InvalidKeystone);
    }
    // Typed deserialization rejects duplicate fields, unsupported fields, wrong
    // types and trailing JSON. Error text never contains QR contents.
    let p: Pairing = serde_json::from_slice(bytes).map_err(|_| KeyError::InvalidKeystone)?;
    if p.version != 0 || p.encrypted || p.source != "Keystone" || p.wallet_name.len() > 80 {
        return Err(KeyError::InvalidKeystone);
    }
    let parsed = Address::from_str(&p.primary_address).map_err(|_| KeyError::InvalidKeystone)?;
    let network = match parsed.network {
        monero::Network::Mainnet => Network::Mainnet,
        monero::Network::Stagenet => Network::Stagenet,
        _ => return Err(KeyError::InvalidKeystone),
    };
    OfflineWallet::restore_keystone(
        network,
        &p.primary_address,
        Zeroizing::new(p.private_view_key.clone()),
        p.restore_height,
    )
}

#[derive(Default)]
pub struct PairingDecoder {
    decoder: ur::Decoder,
    started: Option<Instant>,
    identity: Option<(u32, u32, u32, usize)>,
    seen: BTreeSet<u32>,
    frames: usize,
    wallet: Option<OfflineWallet>,
}
impl PairingDecoder {
    pub fn complete(&self) -> bool {
        self.wallet.is_some()
    }
    pub fn received(&self) -> u32 {
        self.seen.len() as u32
    }
    pub fn total(&self) -> u32 {
        self.identity.map_or(0, |i| i.0)
    }
    pub fn finish(&mut self, restore_height: Option<u64>) -> Result<OfflineWallet, KeyError> {
        if self.started.is_some_and(|t| t.elapsed() >= DEADLINE) {
            *self = Self::default();
            return Err(KeyError::InvalidKeystone);
        }
        if restore_height.is_some_and(|h| h > u64::from(u32::MAX)) {
            return Err(KeyError::InvalidHeight);
        }
        let mut wallet = self.wallet.take().ok_or(KeyError::InvalidKeystone)?;
        if let Some(height) = restore_height {
            wallet.details.restore_height = height;
        }
        Ok(wallet)
    }
    pub fn receive(&mut self, frame: Zeroizing<String>) -> Result<(), KeyError> {
        if frame.len() > MAX_FRAME
            || !frame.is_ascii()
            || self.started.is_some_and(|t| t.elapsed() >= DEADLINE)
            || self.frames >= MAX_FRAMES
        {
            *self = Self::default();
            return Err(KeyError::InvalidKeystone);
        }
        if self.complete() {
            return Ok(());
        }
        self.started.get_or_insert_with(Instant::now);
        self.frames += 1;
        let text = frame.trim();
        if text.starts_with('{') {
            if self.identity.is_some() {
                return Err(KeyError::InvalidKeystone);
            }
            self.wallet = Some(import_pairing(text.as_bytes())?);
            return Ok(());
        }
        let lower = Zeroizing::new(text.to_ascii_lowercase());
        if !lower.starts_with("ur:bytes/") {
            return Err(KeyError::InvalidKeystone);
        }
        let (kind, data) = ur::decode(&lower).map_err(|_| KeyError::InvalidKeystone)?;
        let data = Zeroizing::new(data);
        if kind == ur::ur::Kind::SinglePart {
            if self.identity.is_some() {
                return Err(KeyError::InvalidKeystone);
            }
            self.wallet = Some(import_pairing(cbor_bytes(&data)?)?);
            return Ok(());
        }
        let mut d = minicbor::Decoder::new(&data);
        let bad = |_| KeyError::InvalidKeystone;
        if d.array().map_err(bad)? != Some(5) {
            return Err(KeyError::InvalidKeystone);
        }
        let seq = d.u32().map_err(bad)?;
        let total = d.u32().map_err(bad)?;
        let len = d.u32().map_err(bad)?;
        let checksum = d.u32().map_err(bad)?;
        let fragment = d.bytes().map_err(bad)?;
        // Bound every declared allocation before handing data to the fountain
        // decoder. Require exactly the fragment geometry the encoder produces.
        if seq == 0
            || seq > 65_535
            || total == 0
            || total > MAX_PARTS
            || len == 0
            || len as usize > MAX_JSON + 3
            || fragment.is_empty()
            || fragment.len() > MAX_JSON + 3
            || d.position() != data.len()
            || (len as usize).div_ceil(fragment.len()) != total as usize
        {
            return Err(KeyError::InvalidKeystone);
        }
        let indices = lower.split('/').nth(1).ok_or(KeyError::InvalidKeystone)?;
        if indices != format!("{seq}-{total}") {
            return Err(KeyError::InvalidKeystone);
        }
        let identity = (total, len, checksum, fragment.len());
        if self.identity.is_some_and(|i| i != identity) {
            return Err(KeyError::InvalidKeystone);
        }
        self.identity = Some(identity);
        if !self.seen.insert(seq) {
            return Ok(());
        }
        self.decoder
            .receive(&lower)
            .map_err(|_| KeyError::InvalidKeystone)?;
        if let Some(message) = self
            .decoder
            .message()
            .map_err(|_| KeyError::InvalidKeystone)?
        {
            let message = Zeroizing::new(message);
            self.wallet = Some(import_pairing(cbor_bytes(&message)?)?);
            self.decoder = ur::Decoder::default();
        }
        Ok(())
    }
}
fn cbor_bytes(data: &[u8]) -> Result<&[u8], KeyError> {
    if data.len() > MAX_JSON + 3 {
        return Err(KeyError::InvalidKeystone);
    }
    let mut d = minicbor::Decoder::new(data);
    let bytes = d.bytes().map_err(|_| KeyError::InvalidKeystone)?;
    if d.position() != data.len() || bytes.len() > MAX_JSON {
        return Err(KeyError::InvalidKeystone);
    }
    Ok(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Mode;
    fn fixture(network: Network) -> String {
        let w = OfflineWallet::restore_spend_key(
            network,
            Zeroizing::new(format!("01{}", "00".repeat(31))),
            0,
        )
        .unwrap();
        let view = w.with_secret_keys(|v, _| {
            v.to_bytes()
                .iter()
                .map(|b| format!("{b:02x}"))
                .collect::<String>()
        });
        format!(
            r#"{{"version":0,"primaryAddress":"{}","privateViewKey":"{}","restoreHeight":123,"encrypted":false,"source":"Keystone"}}"#,
            w.details().address,
            view
        )
    }
    #[test]
    fn official_shape_imports_with_no_spending_authority_and_survives_snapshot() {
        for n in [Network::Mainnet, Network::Stagenet] {
            let w = import_pairing(fixture(n).as_bytes()).unwrap();
            assert_eq!(w.details().mode, Mode::Keystone);
            assert_eq!(w.details().network, n);
            assert_eq!(w.details().restore_height, 123);
            assert!(!w.has_local_spend_key());
            assert!(w.recovery_phrase().is_err());
            let restored = OfflineWallet::from_snapshot(w.snapshot()).unwrap();
            assert_eq!(restored.details(), w.details());
            assert!(!restored.has_local_spend_key());
        }
    }
    #[test]
    fn hostile_pairings_fail_without_echoing_input() {
        let f = fixture(Network::Mainnet);
        for s in [
            f.replace("\"version\":0", "\"version\":1"),
            f.replace("\"encrypted\":false", "\"encrypted\":true"),
            f.replace("\"restoreHeight\":123", "\"restoreHeight\":-1"),
            f.replace("\"restoreHeight\":123", "\"restoreHeight\":4294967296"),
            f.replace("\"version\":0", "\"version\":0,\"version\":0"),
            f.replace("\"source\":\"Keystone\"", "\"source\":\"Other\""),
            f.replace(
                "\"version\":0",
                "\"version\":0,\"spendKey\":\"do-not-echo\"",
            ),
            format!("{f}{{}}"),
            fixture(Network::Testnet),
            "x".repeat(MAX_JSON + 1),
        ] {
            let e = import_pairing(s.as_bytes()).err().unwrap();
            assert!(!e.to_string().contains("do-not-echo"));
        }
        let wrong = f.replacen(
            "\"privateViewKey\":\"",
            &format!("\"privateViewKey\":\"{}", "00".repeat(32)),
            1,
        );
        assert!(import_pairing(wrong.as_bytes()).is_err());
    }
    #[test]
    fn static_and_animated_bytes_round_trip_with_duplicate_and_reordered_frames() {
        let text = fixture(Network::Mainnet);
        let mut cbor = Vec::new();
        minicbor::Encoder::new(&mut cbor)
            .bytes(text.as_bytes())
            .unwrap();
        let single = ur::encode(&cbor, &ur::Type::Bytes);
        let mut d = PairingDecoder::default();
        d.receive(Zeroizing::new(single.to_uppercase())).unwrap();
        assert_eq!(d.finish(None).unwrap().details().mode, Mode::Keystone);
        assert!(d.finish(None).is_err());
        let mut e = ur::Encoder::bytes(&cbor, 60).unwrap();
        let mut parts = (0..e.fragment_count())
            .map(|_| e.next_part().unwrap())
            .collect::<Vec<_>>();
        parts.reverse();
        let mut d = PairingDecoder::default();
        d.receive(Zeroizing::new(parts[0].clone())).unwrap();
        d.receive(Zeroizing::new(parts[0].clone())).unwrap();
        assert_eq!(d.received(), 1);
        for p in parts {
            d.receive(Zeroizing::new(p)).unwrap();
        }
        assert!(d.complete());
        assert!(!d.finish(None).unwrap().has_local_spend_key());
    }
    #[test]
    fn scanner_rejects_wrong_types_and_bounded_fountain_lies() {
        let mut d = PairingDecoder::default();
        for s in ["ur:xmr-txsigned/abcd".to_owned(), "x".repeat(MAX_FRAME + 1)] {
            assert!(d.receive(Zeroizing::new(s)).is_err());
        }
        let mut cbor = Vec::new();
        let mut e = minicbor::Encoder::new(&mut cbor);
        e.array(5)
            .unwrap()
            .u32(1)
            .unwrap()
            .u32(u32::MAX)
            .unwrap()
            .u32(4096)
            .unwrap()
            .u32(0)
            .unwrap()
            .bytes(&[1])
            .unwrap();
        let body = ur::bytewords::encode(&cbor, ur::bytewords::Style::Minimal);
        assert!(d
            .receive(Zeroizing::new(format!("ur:bytes/1-65535/{body}")))
            .is_err());
        d.started = Some(Instant::now() - DEADLINE);
        assert!(d
            .receive(Zeroizing::new(fixture(Network::Mainnet)))
            .is_err());
    }

    #[test]
    fn explicit_restore_height_is_bounded_and_does_not_change_authority() {
        let mut d = PairingDecoder::default();
        d.receive(Zeroizing::new(fixture(Network::Mainnet)))
            .unwrap();
        assert_eq!(
            d.finish(Some(u64::MAX)).err(),
            Some(KeyError::InvalidHeight)
        );
        let w = d.finish(Some(456)).unwrap();
        assert_eq!(w.details().restore_height, 456);
        assert_eq!(w.details().mode, Mode::Keystone);
        assert!(!w.has_local_spend_key());
        assert_eq!(
            OfflineWallet::from_snapshot(w.snapshot())
                .unwrap()
                .details()
                .restore_height,
            456
        );
    }
}
