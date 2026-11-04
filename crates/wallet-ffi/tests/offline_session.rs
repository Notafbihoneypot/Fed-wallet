use independent_wallet::{
    KeyNetwork, KeyWalletMode, OfflineWalletSession, RecoveryFormat, WalletError,
};

#[test]
fn a_generated_wallet_requires_the_backup_check_before_storage_export() {
    let session =
        OfflineWalletSession::generate(KeyNetwork::Stagenet, RecoveryFormat::Polyseed16).unwrap();
    assert!(!session.details().unwrap().backup_verified);
    assert!(matches!(
        session.snapshot_for_vault(),
        Err(WalletError::BackupRequired)
    ));
    assert!(session.confirm_backup(vec!["wrong".into(); 3]).is_err());
    let phrase = session.recovery_phrase().unwrap();
    let words: Vec<_> = phrase.split(' ').collect();
    let challenge = session.backup_challenge().unwrap();
    assert_eq!(challenge.len(), 3);
    assert!(challenge.windows(2).all(|w| w[0] < w[1]));
    session
        .confirm_backup(
            challenge
                .iter()
                .map(|i| words[*i as usize].into())
                .collect(),
        )
        .unwrap();
    let reopened =
        OfflineWalletSession::open_snapshot(session.snapshot_for_vault().unwrap()).unwrap();
    assert_eq!(
        session.details().unwrap().address,
        reopened.details().unwrap().address
    );
    assert!(reopened.details().unwrap().backup_verified);
}

#[test]
fn lock_revokes_all_secret_and_metadata_operations() {
    let session =
        OfflineWalletSession::generate(KeyNetwork::Stagenet, RecoveryFormat::Legacy25).unwrap();
    session.lock();
    assert!(matches!(session.details(), Err(WalletError::Locked)));
    assert!(matches!(
        session.recovery_phrase(),
        Err(WalletError::Locked)
    ));
    assert!(matches!(
        session.snapshot_for_vault(),
        Err(WalletError::Locked)
    ));
    assert!(matches!(
        session.confirm_backup(vec![]),
        Err(WalletError::Locked)
    ));
    session.lock();
}

#[test]
fn view_only_snapshot_stays_view_only_without_a_seed() {
    let hot = OfflineWalletSession::import_spend_key(
        KeyNetwork::Stagenet,
        "c0af65c0dd837e666b9d0dfed62745f4df35aed7ea619b2798a709f0fe545403".into(),
        0,
    )
    .unwrap();
    let view = OfflineWalletSession::import_view_only(
        KeyNetwork::Stagenet,
        hot.details().unwrap().address,
        "513ba91c538a5a9069e0094de90e927c0cd147fa10428ce3ac1afd49f63e3b01".into(),
        0,
    )
    .unwrap();
    assert_eq!(view.details().unwrap().mode, KeyWalletMode::ViewOnly);
    assert!(view.recovery_phrase().is_err());
    let restored = OfflineWalletSession::open_snapshot(view.snapshot_for_vault().unwrap()).unwrap();
    assert_eq!(restored.details().unwrap().mode, KeyWalletMode::ViewOnly);
    assert!(restored.recovery_phrase().is_err());
}

#[test]
fn concurrent_lock_is_final() {
    let session =
        OfflineWalletSession::generate(KeyNetwork::Stagenet, RecoveryFormat::Legacy25).unwrap();
    let worker = session.clone();
    let thread = std::thread::spawn(move || {
        for _ in 0..100 {
            let _ = worker.details();
        }
        worker.lock();
    });
    session.lock();
    thread.join().unwrap();
    assert!(session.details().is_err());
}
