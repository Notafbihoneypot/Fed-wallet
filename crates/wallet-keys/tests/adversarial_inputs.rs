//! Deterministic mutation tests using public fixtures. Never use this mutator
//! as a source of wallet entropy; production generation uses OsRng.
use wallet_keys::{Mode, Network, OfflineWallet, MAX_SNAPSHOT_BYTES};
use zeroize::Zeroizing;

const SPEND: &str = "c0af65c0dd837e666b9d0dfed62745f4df35aed7ea619b2798a709f0fe545403";
const VIEW: &str = "513ba91c538a5a9069e0094de90e927c0cd147fa10428ce3ac1afd49f63e3b01";

fn mutate(state: &mut u64) -> u64 {
    *state ^= *state << 13;
    *state ^= *state >> 7;
    *state ^= *state << 17;
    *state
}

fn view_only() -> OfflineWallet {
    let software =
        OfflineWallet::restore_spend_key(Network::Stagenet, Zeroizing::new(SPEND.into()), 0)
            .unwrap();
    OfflineWallet::restore_view_only(
        Network::Stagenet,
        &software.details().address,
        Zeroizing::new(VIEW.into()),
        0,
    )
    .unwrap()
}

#[test]
fn arbitrary_and_structured_snapshots_do_not_panic_or_break_roundtrips() {
    let mut state = 0x1a47_665e_f20c_9b11;
    for case in 0..12_000 {
        let len = mutate(&mut state) as usize % (MAX_SNAPSHOT_BYTES + 257);
        let mut input = Zeroizing::new(vec![0u8; len]);
        for byte in input.iter_mut() {
            *byte = mutate(&mut state) as u8;
        }
        if case % 2 == 0 && len >= 16 {
            input[..4].copy_from_slice(b"IWK1");
            input[4] %= 4;
            input[5] %= 7;
            input[10..14].fill(0);
            input[14..16].copy_from_slice(&((len - 16) as u16).to_le_bytes());
        }
        // Force a useful population of well-sized scalar inputs as well.
        if case % 7 == 0 {
            input.resize(48, 0);
            input[..6].copy_from_slice(b"IWK1\x01\x03");
            input[6..14].fill(0);
            input[14..16].copy_from_slice(&32u16.to_le_bytes());
        }
        if let Ok(wallet) = OfflineWallet::from_snapshot(input) {
            let restored = OfflineWallet::from_snapshot(wallet.snapshot()).unwrap();
            assert_eq!(wallet.details(), restored.details());
            assert_eq!(wallet.has_local_spend_key(), restored.has_local_spend_key());
            assert_eq!(
                wallet.details().mode == Mode::Software,
                wallet.has_local_spend_key()
            );
        }
    }
}

#[test]
fn mutated_view_snapshots_never_acquire_software_spend_authority() {
    let original = view_only();
    let snapshot = original.snapshot();
    let mut state = 0xfaa1_381b_309e_8dd7;
    for case in 0..4096 {
        let mut input = snapshot.clone();
        for _ in 0..=case % 4 {
            let index = mutate(&mut state) as usize % input.len();
            input[index] ^= (mutate(&mut state) as u8) | 1;
        }
        if let Ok(wallet) = OfflineWallet::from_snapshot(input) {
            assert_eq!(wallet.details().mode, Mode::ViewOnly);
            assert!(!wallet.has_local_spend_key());
            assert!(wallet.recovery_phrase().is_err());
        }
    }
}

#[test]
fn address_corruption_and_single_digit_view_key_changes_are_rejected() {
    let view = view_only();
    let address = view.details().address;
    for index in 0..address.len() {
        for &replacement in b"12" {
            let mut corrupted = address.as_bytes().to_vec();
            if corrupted[index] == replacement {
                continue;
            }
            corrupted[index] = replacement;
            let corrupted = String::from_utf8(corrupted).unwrap();
            assert!(OfflineWallet::restore_view_only(
                Network::Stagenet,
                &corrupted,
                Zeroizing::new(VIEW.into()),
                0
            )
            .is_err());
        }
    }
    for index in 0..64 {
        let mut corrupted = VIEW.as_bytes().to_vec();
        corrupted[index] = if corrupted[index] == b'0' { b'1' } else { b'0' };
        assert!(OfflineWallet::restore_view_only(
            Network::Stagenet,
            &address,
            Zeroizing::new(String::from_utf8(corrupted).unwrap()),
            0
        )
        .is_err());
    }
}
