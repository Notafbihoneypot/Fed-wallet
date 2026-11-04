//! Regression checks, not a proof of entropy or a cryptographic certification.
use super::*;
use std::{collections::BTreeSet, num::NonZeroU32};

struct FailingRng {
    calls: usize,
}
impl CryptoRng for FailingRng {}
impl RngCore for FailingRng {
    fn next_u32(&mut self) -> u32 {
        panic!("generation must use the fallible OS byte interface")
    }
    fn next_u64(&mut self) -> u64 {
        panic!("generation must use the fallible OS byte interface")
    }
    fn fill_bytes(&mut self, _: &mut [u8]) {
        panic!("generation must not fall back to an infallible source")
    }
    fn try_fill_bytes(&mut self, dest: &mut [u8]) -> Result<(), rand_core::Error> {
        self.calls += 1;
        // Even partially written bytes must not escape as a generated wallet.
        let half = dest.len() / 2;
        dest[..half].fill(0x42);
        Err(rand_core::Error::from(
            NonZeroU32::new(rand_core::Error::CUSTOM_START).unwrap(),
        ))
    }
}

#[test]
fn both_seed_formats_fail_closed_after_partial_entropy_read_failure() {
    for format in [SeedFormat::Polyseed16, SeedFormat::Legacy25] {
        let mut rng = FailingRng { calls: 0 };
        let result =
            OfflineWallet::generate_with_rng(Network::Stagenet, format, &mut rng, 1_790_000_000);
        assert_eq!(result.err(), Some(KeyError::EntropyUnavailable));
        assert_eq!(rng.calls, 1, "no retry with a fallback source");
    }
}

#[test]
fn actual_os_rng_has_no_obvious_stuck_output_in_one_mib_smoke_sample() {
    let mut sample = Zeroizing::new(vec![0u8; 1024 * 1024]);
    OsRng.try_fill_bytes(&mut sample).unwrap();
    let mut histogram = [0usize; 256];
    let mut bits = 0usize;
    for b in sample.iter() {
        histogram[usize::from(*b)] += 1;
        bits += b.count_ones() as usize;
    }
    // Deliberately liberal thresholds. These only detect gross regressions;
    // a predictable generator could pass all of them.
    let total_bits = sample.len() * 8;
    assert!((total_bits * 47 / 100..=total_bits * 53 / 100).contains(&bits));
    assert!(histogram.iter().all(|n| (3000..=5200).contains(n)));
    let equal_neighbors = sample.windows(2).filter(|p| p[0] == p[1]).count();
    assert!((3000..=5200).contains(&equal_neighbors));
}

#[test]
fn production_seed_generation_is_fresh_and_restores_in_both_formats() {
    let mut addresses = BTreeSet::new();
    for format in [SeedFormat::Polyseed16, SeedFormat::Legacy25] {
        for _ in 0..64 {
            let wallet = OfflineWallet::generate(Network::Stagenet, format).unwrap();
            let details = wallet.details();
            let restored = OfflineWallet::restore_seed(
                Network::Stagenet,
                format,
                wallet.recovery_phrase().unwrap(),
                0,
            )
            .unwrap();
            assert_eq!(details.address, restored.details().address);
            assert!(addresses.insert(details.address));
        }
    }
    assert_eq!(addresses.len(), 128);
}
