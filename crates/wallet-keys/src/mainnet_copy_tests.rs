use super::*;

#[test]
fn mainnet_seed_copy_preserves_backup_and_original_snapshot() {
    for format in [SeedFormat::Polyseed16, SeedFormat::Legacy25] {
        let original = OfflineWallet::generate(Network::Stagenet, format).unwrap();
        let before = original.snapshot();
        let address = original.details().address;
        let copy = original.copy_to_mainnet(17).unwrap();
        assert_eq!(copy.details().network, Network::Mainnet);
        assert_eq!(copy.details().mode, Mode::Software);
        assert_eq!(copy.details().restore_height, 17);
        assert_eq!(original.details().address, address);
        assert!(*original.snapshot() == *before);
        assert!(*copy.recovery_phrase().unwrap() == *original.recovery_phrase().unwrap());
        assert!(
            original.with_secret_keys(|v, s| copy.with_secret_keys(|cv, cs| v == cv && s == cs))
        );
        assert_ne!(copy.details().address, address);
        let reopened = OfflineWallet::from_snapshot(copy.snapshot()).unwrap();
        assert_eq!(reopened.details(), copy.details());
        let recovered = OfflineWallet::restore_seed(
            Network::Mainnet,
            format,
            original.recovery_phrase().unwrap(),
            17,
        )
        .unwrap();
        assert_eq!(recovered.details().address, copy.details().address);
    }
}

#[test]
fn mainnet_private_key_copy_keeps_authority_and_rejects_an_invalid_height() {
    let original = OfflineWallet::restore_spend_key(
        Network::Stagenet,
        Zeroizing::new(format!("01{}", "00".repeat(31))),
        42,
    )
    .unwrap();
    let copied = original.copy_to_mainnet(0).unwrap();
    assert_eq!(original.details().restore_height, 42);
    assert_eq!(original.details().network, Network::Stagenet);
    assert!(copied.has_local_spend_key());
    assert!(original.with_secret_keys(|v, s| copied.with_secret_keys(|cv, cs| v == cv && s == cs)));
    assert!(original.copy_to_mainnet(u64::from(u32::MAX) + 1).is_err());
    assert!(copied.copy_to_mainnet(0).is_err());
}

#[test]
fn network_copy_cannot_promote_viewing_or_hardware_authority() {
    let original = OfflineWallet::restore_spend_key(
        Network::Stagenet,
        Zeroizing::new(format!("01{}", "00".repeat(31))),
        0,
    )
    .unwrap();
    let view = original.with_secret_keys(|v, _| {
        Zeroizing::new(
            v.to_bytes()
                .iter()
                .map(|b| format!("{b:02x}"))
                .collect::<String>(),
        )
    });
    let watching = OfflineWallet::restore_view_only(
        Network::Stagenet,
        &original.details().address,
        view.clone(),
        0,
    )
    .unwrap();
    let hardware =
        OfflineWallet::restore_keystone(Network::Stagenet, &original.details().address, view, 0)
            .unwrap();
    for wallet in [watching, hardware] {
        assert!(!wallet.has_local_spend_key());
        assert!(matches!(
            wallet.copy_to_mainnet(0),
            Err(KeyError::UnsupportedNetworkCopy)
        ));
    }
}
