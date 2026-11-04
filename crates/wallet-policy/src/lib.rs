#![forbid(unsafe_code)]
//! Application policy only. This crate does not implement Monero cryptography.
//! It owns no sockets, seed phrases or spending keys.

pub mod amount;
pub mod connection;
pub mod payment;

use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Network {
    Mainnet,
    Stagenet,
    Testnet,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WalletMode {
    Hot,
    ViewOnly,
    Hardware,
}

impl WalletMode {
    pub fn allows_local_signing(self) -> bool {
        self == Self::Hot
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Error {
    InvalidAmount,
    AmountOverflow,
    InvalidNode,
    InlineCredentials,
    NodeRequired,
    TransportNotReady,
    StaleSession,
    InvalidPlan,
    PaymentMismatch,
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
            Self::NodeRequired => "Choose a node before connecting",
            Self::TransportNotReady => "The Tor transport is not ready",
            Self::StaleSession => "The connection or payment session has changed",
            Self::InvalidPlan => "The prepared payment is invalid",
            Self::PaymentMismatch => "The signed payment does not match the prepared payment",
        };
        f.write_str(message)
    }
}

impl std::error::Error for Error {}
