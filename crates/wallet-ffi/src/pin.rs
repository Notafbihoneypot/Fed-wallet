//! Fixed bounded PIN KDF. Android additionally binds it to a device Keystore HMAC.
use argon2::{Algorithm, Argon2, Params, Version};
use zeroize::Zeroizing;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum PinError {
    #[error("Use an 8–12 digit PIN and a 16-byte salt")]
    InvalidInput,
    #[error("PIN derivation is unavailable")]
    Unavailable,
}

#[uniffi::export]
pub fn derive_pin_key(pin: Vec<u8>, salt: Vec<u8>) -> Result<Vec<u8>, PinError> {
    let pin = Zeroizing::new(pin);
    if !(8..=12).contains(&pin.len()) || !pin.iter().all(u8::is_ascii_digit) || salt.len() != 16 {
        return Err(PinError::InvalidInput);
    }
    let params = Params::new(65_536, 3, 1, Some(32)).map_err(|_| PinError::Unavailable)?;
    let mut output = Zeroizing::new([0u8; 32]);
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
        .hash_password_into(&pin, &salt, output.as_mut())
        .map_err(|_| PinError::Unavailable)?;
    Ok(output.to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn fixed_kdf_is_salted_repeatable_and_bounded() {
        let a = derive_pin_key(b"18472639".to_vec(), vec![7; 16]).unwrap();
        assert_eq!(a.len(), 32);
        assert_eq!(
            a,
            derive_pin_key(b"18472639".to_vec(), vec![7; 16]).unwrap()
        );
        assert_ne!(
            a,
            derive_pin_key(b"18472639".to_vec(), vec![8; 16]).unwrap()
        );
        assert!(derive_pin_key(b"short".to_vec(), vec![7; 16]).is_err());
        assert!(derive_pin_key(b"1847263x".to_vec(), vec![7; 16]).is_err());
        assert!(derive_pin_key(b"18472639".to_vec(), vec![7; 17]).is_err());
    }
}
