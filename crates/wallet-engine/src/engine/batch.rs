use std::time::Duration;

pub(super) const INITIAL: u64 = 128;
pub(super) const MIN: u64 = 32;
pub(super) const MAX: u64 = 512;

/// Bound memory and time between durable progress updates. Response-size and
/// request-time limits remain enforced by the transport and protocol adapter.
pub(super) fn next(current: u64, elapsed: Duration) -> u64 {
    let next = if elapsed < Duration::from_secs(1) {
        current.saturating_mul(2)
    } else if elapsed > Duration::from_secs(4) {
        current / 2
    } else {
        current
    };
    next.clamp(MIN, MAX)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fast_batches_grow_and_slow_batches_shrink_with_fixed_bounds() {
        let mut size = INITIAL;
        for _ in 0..8 {
            size = next(size, Duration::from_millis(200));
        }
        assert_eq!(size, MAX);
        for _ in 0..8 {
            size = next(size, Duration::from_secs(8));
        }
        assert_eq!(size, MIN);
        assert_eq!(next(INITIAL, Duration::from_secs(2)), INITIAL);
        assert_eq!(next(u64::MAX, Duration::ZERO), MAX);
    }
}
