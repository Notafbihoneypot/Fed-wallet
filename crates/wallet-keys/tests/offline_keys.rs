//! Public upstream fixtures only. Never send funds to these test wallets.
use wallet_keys::{KeyError, Mode, Network, OfflineWallet, SeedFormat};
use zeroize::Zeroizing;

const LEGACY: &str = "washing thirsty occur lectures tuesday fainted toxic adapt abnormal memoir nylon mostly building shrugged online ember northern ruby woes dauntless boil family illness inroads northern";
const SPEND: &str = "c0af65c0dd837e666b9d0dfed62745f4df35aed7ea619b2798a709f0fe545403";
const VIEW: &str = "513ba91c538a5a9069e0094de90e927c0cd147fa10428ce3ac1afd49f63e3b01";
const POLY: &str = "raven tail swear infant grief assist regular lamp duck valid someone little harsh puppy airport language";
fn secret(s: &str) -> Zeroizing<String> {
    Zeroizing::new(s.to_owned())
}
fn legacy(network: Network) -> OfflineWallet {
    OfflineWallet::restore_seed(network, SeedFormat::Legacy25, secret(LEGACY), 123).unwrap()
}

#[test]
fn legacy_spend_and_view_match_upstream_vectors() {
    let wallet = legacy(Network::Mainnet);
    let spend = OfflineWallet::restore_spend_key(Network::Mainnet, secret(SPEND), 123).unwrap();
    assert_eq!(wallet.details().address, spend.details().address);
    let view = OfflineWallet::restore_view_only(
        Network::Mainnet,
        &wallet.details().address,
        secret(VIEW),
        123,
    )
    .unwrap();
    assert_eq!(view.details().address, wallet.details().address);
    assert!(!view.has_local_spend_key());
    assert_eq!(view.details().mode, Mode::ViewOnly);
    assert_eq!(
        view.recovery_phrase().err(),
        Some(KeyError::NoRecoveryPhrase)
    );
}

#[test]
fn polyseed_reference_entropy_and_birthday_roundtrip() {
    let wallet =
        OfflineWallet::restore_seed(Network::Stagenet, SeedFormat::Polyseed16, secret(POLY), 0)
            .unwrap();
    assert_eq!(wallet.details().birthday, 1_638_397_746);
    assert_eq!(&*wallet.recovery_phrase().unwrap(), POLY);
    let seed = polyseed::Polyseed::from_string(polyseed::Language::English, secret(POLY)).unwrap();
    let expected = [
        0xdd, 0x76, 0xe7, 0x35, 0x9a, 0x0d, 0xed, 0x37, 0xcd, 0x0f, 0xf0, 0xf3, 0xc8, 0x29, 0xa5,
        0xae, 0x01, 0x67, 0x33, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    ];
    assert_eq!(seed.entropy().as_slice(), expected);
    // OpenSSL/Python PBKDF2 fixture using tevador/polyseed's C test salt and entropy.
    let expected_key = [
        0x21, 0x26, 0x8a, 0x76, 0x04, 0x8a, 0x3b, 0x25, 0xa4, 0xa9, 0xac, 0x17, 0x9d, 0x86, 0xb1,
        0x2f, 0xab, 0x58, 0x00, 0xb8, 0xd8, 0x58, 0xda, 0x9f, 0xac, 0xf4, 0xb0, 0xa7, 0x78, 0xdc,
        0x28, 0x40,
    ];
    assert_eq!(seed.key().as_slice(), expected_key);
}

#[test]
fn generated_seeds_restore_the_same_wallet_without_a_daemon() {
    for format in [SeedFormat::Polyseed16, SeedFormat::Legacy25] {
        let first = OfflineWallet::generate(Network::Stagenet, format).unwrap();
        let second = OfflineWallet::generate(Network::Stagenet, format).unwrap();
        assert_ne!(first.details().address, second.details().address);
        let restored = OfflineWallet::restore_seed(
            Network::Stagenet,
            format,
            first.recovery_phrase().unwrap(),
            0,
        )
        .unwrap();
        assert_eq!(first.details(), restored.details());
    }
}

#[test]
fn snapshots_preserve_mode_and_never_add_spend_keys() {
    let hot = legacy(Network::Stagenet);
    let view = OfflineWallet::restore_view_only(
        Network::Stagenet,
        &hot.details().address,
        secret(VIEW),
        17,
    )
    .unwrap();
    let raw = OfflineWallet::restore_spend_key(Network::Stagenet, secret(SPEND), 99).unwrap();
    let poly =
        OfflineWallet::restore_seed(Network::Stagenet, SeedFormat::Polyseed16, secret(POLY), 0)
            .unwrap();
    for original in [hot, view, raw, poly] {
        let opened = OfflineWallet::from_snapshot(original.snapshot()).unwrap();
        assert_eq!(opened.details(), original.details());
        assert_eq!(opened.has_local_spend_key(), original.has_local_spend_key());
    }
}

#[test]
fn malformed_and_unknown_snapshots_fail_closed() {
    let wallet = legacy(Network::Stagenet);
    let good = wallet.snapshot();
    for len in 0..good.len() {
        assert!(OfflineWallet::from_snapshot(Zeroizing::new(good[..len].to_vec())).is_err());
    }
    for (offset, value) in [(0, 0), (4, 9), (5, 5), (14, 255), (15, 255)] {
        let mut bad = good.clone();
        bad[offset] = value;
        assert!(OfflineWallet::from_snapshot(bad).is_err());
    }
    let mut trailing = good.clone();
    trailing.push(0);
    assert!(OfflineWallet::from_snapshot(trailing).is_err());
    assert!(OfflineWallet::from_snapshot(Zeroizing::new(vec![0; 4097])).is_err());
}

#[test]
fn wrong_format_checksum_word_count_and_word_suffixes_are_rejected() {
    for input in [
        "",
        "abandon",
        &"raven ".repeat(17),
        &"raven ".repeat(15),
        &POLY.replace("raven", "ravexxx"),
        &POLY.replace("tail", "swear"),
        &"x".repeat(4097),
    ] {
        assert!(OfflineWallet::restore_seed(
            Network::Stagenet,
            SeedFormat::Polyseed16,
            secret(input),
            0
        )
        .is_err());
    }
    let legacy_24 = LEGACY.rsplit_once(' ').unwrap().0;
    for input in [
        legacy_24,
        &LEGACY.replace("washing", "washingxxx"),
        &LEGACY.replace("thirsty", "abducts"),
        POLY,
    ] {
        assert!(OfflineWallet::restore_seed(
            Network::Mainnet,
            SeedFormat::Legacy25,
            secret(input),
            0
        )
        .is_err());
    }
    let spaced = LEGACY.replace(' ', "\n\t");
    assert_eq!(
        legacy(Network::Mainnet).details(),
        OfflineWallet::restore_seed(Network::Mainnet, SeedFormat::Legacy25, secret(&spaced), 123)
            .unwrap()
            .details()
    );
}

#[test]
fn invalid_private_keys_and_heights_are_rejected() {
    for input in [
        "",
        &"0".repeat(64),
        &"f".repeat(64),
        &"g".repeat(64),
        &"1".repeat(63),
        &"1".repeat(65),
    ] {
        assert!(OfflineWallet::restore_spend_key(Network::Stagenet, secret(input), 0).is_err());
    }
    assert!(OfflineWallet::restore_spend_key(
        Network::Stagenet,
        secret(SPEND),
        u64::from(u32::MAX) + 1
    )
    .is_err());
}

#[test]
fn view_only_rejects_wrong_network_view_key_and_address_type() {
    use monero::{util::address::PaymentId, Address, PublicKey};
    use std::str::FromStr;
    let hot = legacy(Network::Mainnet);
    let address = Address::from_str(&hot.details().address).unwrap();
    assert!(OfflineWallet::restore_view_only(
        Network::Stagenet,
        &address.to_string(),
        secret(VIEW),
        0
    )
    .is_err());
    assert!(OfflineWallet::restore_view_only(
        Network::Mainnet,
        &address.to_string(),
        secret(SPEND),
        0
    )
    .is_err());
    let sub = Address::subaddress(address.network, address.public_spend, address.public_view);
    assert!(
        OfflineWallet::restore_view_only(Network::Mainnet, &sub.to_string(), secret(VIEW), 0)
            .is_err()
    );
    let integrated = Address::integrated(
        address.network,
        address.public_spend,
        address.public_view,
        PaymentId([0; 8]),
    );
    assert!(OfflineWallet::restore_view_only(
        Network::Mainnet,
        &integrated.to_string(),
        secret(VIEW),
        0
    )
    .is_err());
    let identity = PublicKey {
        point: curve25519_dalek::edwards::EdwardsPoint::default().compress(),
    };
    let bad_spend = Address::standard(address.network, identity, address.public_view);
    assert!(OfflineWallet::restore_view_only(
        Network::Mainnet,
        &bad_spend.to_string(),
        secret(VIEW),
        0
    )
    .is_err());
}

#[test]
fn upstream_polyseed_parser_rejects_all_wrong_lengths_without_panicking() {
    for count in 0..65 {
        if count != 16 {
            assert!(polyseed::Polyseed::from_string(
                polyseed::Language::English,
                secret(&vec!["abandon"; count].join(" "))
            )
            .is_err());
        }
    }
}

#[test]
fn upstream_legacy_parser_rejects_out_of_range_word_triples_without_panicking() {
    let list: &[&str] = include!("../../../vendor/seed/src/words/en.rs");
    let base = list.len() as u64;
    for value in (u64::from(u32::MAX) + 1)..(u64::from(u32::MAX) + 1000) {
        let a = value % base;
        let b = (value / base + a) % base;
        let c = (value / base / base + b) % base;
        let mut words = vec![list[0]; 24];
        for (i, index) in [a, b, c].into_iter().enumerate() {
            words[i] = list[index as usize];
        }
        assert!(monero_seed::Seed::from_string(
            monero_seed::Language::English,
            secret(&words.join(" "))
        )
        .is_err());
    }
}
