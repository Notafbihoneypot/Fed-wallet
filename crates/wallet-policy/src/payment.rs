use crate::{Error, Network};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Recipient {
    // Addresses must already have been validated by the Monero backend,
    // including network and checksum. This crate is not an address parser.
    pub address: String,
    pub atomic: u64,
}

#[derive(Clone)]
pub struct PreparedPayment {
    pub wallet_ref: String,
    pub network: Network,
    pub session: u64,
    pub inputs: Vec<[u8; 32]>,
    pub recipients: Vec<Recipient>,
    pub change: Option<Recipient>,
    pub fee_atomic: u64,
}

/// Produced only by the trusted engine's signed-transaction decoder. An
/// arbitrary UI JSON/record must never be treated as a verified transaction.
pub struct SignedSummary {
    pub wallet_ref: String,
    pub network: Network,
    pub inputs: Vec<[u8; 32]>,
    pub recipients: Vec<Recipient>,
    pub change: Option<Recipient>,
    pub fee_atomic: u64,
}

/// A semantic match is necessary, but is NOT cryptographic verification.
/// The adapter must separately validate signatures/proofs and the transaction
/// format before returning data to this boundary.
pub struct MatchedPayment {
    wallet_ref: String,
    session: u64,
}

impl MatchedPayment {
    pub fn wallet_ref(&self) -> &str {
        &self.wallet_ref
    }
    pub fn session(&self) -> u64 {
        self.session
    }
}

fn payments(items: &[Recipient]) -> Result<BTreeMap<&str, u64>, Error> {
    if items.is_empty() || items.len() > 16 {
        return Err(Error::InvalidPlan);
    }
    let mut result = BTreeMap::new();
    for item in items {
        if item.address.is_empty() || item.address.len() > 256 || item.atomic == 0 {
            return Err(Error::InvalidPlan);
        }
        let amount = result.entry(item.address.as_str()).or_insert(0u64);
        *amount = amount.checked_add(item.atomic).ok_or(Error::InvalidPlan)?;
    }
    Ok(result)
}

fn inputs(items: &[[u8; 32]]) -> Result<BTreeSet<[u8; 32]>, Error> {
    if items.is_empty() || items.len() > 4096 {
        return Err(Error::InvalidPlan);
    }
    let set: BTreeSet<_> = items.iter().copied().collect();
    if set.len() != items.len() {
        return Err(Error::InvalidPlan);
    }
    Ok(set)
}

impl PreparedPayment {
    pub fn validate(&self) -> Result<(), Error> {
        if self.wallet_ref.is_empty() || self.wallet_ref.len() > 128 {
            return Err(Error::InvalidPlan);
        }
        payments(&self.recipients)?;
        inputs(&self.inputs)?;
        let mut total = self.fee_atomic;
        for recipient in &self.recipients {
            total = total
                .checked_add(recipient.atomic)
                .ok_or(Error::InvalidPlan)?;
        }
        if let Some(change) = &self.change {
            if change.address.is_empty() || change.address.len() > 256 || change.atomic == 0 {
                return Err(Error::InvalidPlan);
            }
            total.checked_add(change.atomic).ok_or(Error::InvalidPlan)?;
        }
        Ok(())
    }

    pub fn verify(
        &self,
        returned: &SignedSummary,
        active_session: u64,
    ) -> Result<MatchedPayment, Error> {
        self.validate()?;
        if self.session != active_session {
            return Err(Error::StaleSession);
        }
        if self.wallet_ref != returned.wallet_ref
            || self.network != returned.network
            || self.fee_atomic != returned.fee_atomic
            || self.change != returned.change
            || inputs(&self.inputs)? != inputs(&returned.inputs)?
            || payments(&self.recipients)? != payments(&returned.recipients)?
        {
            return Err(Error::PaymentMismatch);
        }
        Ok(MatchedPayment {
            wallet_ref: self.wallet_ref.clone(),
            session: self.session,
        })
    }
}
