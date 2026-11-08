#![forbid(unsafe_code)]
//! Application policy only. This crate does not implement Monero cryptography.
//! It owns no sockets, seed phrases or spending keys.

pub mod amount;
pub mod connection;

use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Error {
    InvalidAmount,
    AmountOverflow,
    InvalidNode,
    InlineCredentials,
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let message = match self {
            Self::InvalidAmount => "Enter a positive XMR amount with at most 12 decimal places",
            Self::AmountOverflow => "Amount is outside the supported range",
            Self::InvalidNode => {
                "Enter an HTTP or HTTPS node URL without a path, query or fragment"
            }
            Self::InlineCredentials => "Keep node login credentials separate from its address",
        };
        f.write_str(message)
    }
}

impl std::error::Error for Error {}
