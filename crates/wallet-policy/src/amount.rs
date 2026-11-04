use crate::Error;

pub const ATOMIC_UNITS: u64 = 1_000_000_000_000;

/// Parse an amount without floating point, rounding, signs, exponent notation,
/// grouping separators or locale-dependent behavior.
pub fn parse_xmr(value: &str) -> Result<u64, Error> {
    if value.is_empty() || value.len() > 40 || !value.is_ascii() {
        return Err(Error::InvalidAmount);
    }
    let mut parts = value.split('.');
    let whole = parts.next().ok_or(Error::InvalidAmount)?;
    let fractional = parts.next();
    if parts.next().is_some() || whole.is_empty() || !whole.bytes().all(|c| c.is_ascii_digit()) {
        return Err(Error::InvalidAmount);
    }
    let whole = whole.parse::<u64>().map_err(|_| Error::AmountOverflow)?;
    let mut total = whole
        .checked_mul(ATOMIC_UNITS)
        .ok_or(Error::AmountOverflow)?;
    if let Some(fractional) = fractional {
        if fractional.is_empty()
            || fractional.len() > 12
            || !fractional.bytes().all(|c| c.is_ascii_digit())
        {
            return Err(Error::InvalidAmount);
        }
        let fraction = fractional
            .parse::<u64>()
            .map_err(|_| Error::InvalidAmount)?;
        let scale = 10u64.pow(12 - fractional.len() as u32);
        total = total
            .checked_add(fraction * scale)
            .ok_or(Error::AmountOverflow)?;
    }
    if total == 0 {
        return Err(Error::InvalidAmount);
    }
    Ok(total)
}

pub fn format_xmr(atomic: u64) -> String {
    let whole = atomic / ATOMIC_UNITS;
    let remainder = atomic % ATOMIC_UNITS;
    if remainder == 0 {
        return whole.to_string();
    }
    let fraction = format!("{remainder:012}");
    format!("{whole}.{}", fraction.trim_end_matches('0'))
}
