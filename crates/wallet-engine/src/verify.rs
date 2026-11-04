//! Verification of actual serialized bytes against private construction data.
use crate::EngineError;
use monero_wallet::{
    ed25519::{Commitment, CompressedPoint, Point, Scalar},
    ringct::{bulletproofs::BatchVerifier, RctPrunable},
    send::Eventuality,
    transaction::{Input, Pruned, Transaction},
    OutputWithDecoys,
};
use rand_core::OsRng;
use std::collections::{BTreeMap, BTreeSet};
pub(crate) fn verify_transaction(
    bytes: &[u8],
    intent: Eventuality,
    inputs: &[OutputWithDecoys],
    images: &BTreeSet<[u8; 32]>,
    fee: u64,
) -> Result<Transaction, EngineError> {
    let fail = || EngineError::Verification;
    let mut reader = bytes;
    let tx: Transaction = Transaction::read(&mut reader).map_err(|_| fail())?;
    if !reader.is_empty()
        || tx.serialize() != bytes
        || !intent.matches(&Transaction::<Pruned>::from(tx.clone()))
    {
        return Err(fail());
    }
    let Transaction::V2 {
        prefix,
        proofs: Some(proofs),
    } = &tx
    else {
        return Err(fail());
    };
    let RctPrunable::Clsag {
        bulletproof,
        clsags,
        pseudo_outs,
    } = &proofs.prunable
    else {
        return Err(fail());
    };
    if proofs.base.fee != fee
        || clsags.len() != inputs.len()
        || pseudo_outs.len() != inputs.len()
        || prefix.inputs.len() != inputs.len()
        || inputs.is_empty()
        || images.len() != inputs.len()
    {
        return Err(fail());
    }
    let mut rings = BTreeMap::new();
    for i in inputs {
        rings.insert(i.decoys().offsets().to_vec(), i.decoys().ring());
    }
    let mut actual = BTreeSet::new();
    let hash = tx.signature_hash().ok_or_else(fail)?;
    for ((input, clsag), pseudo) in prefix.inputs.iter().zip(clsags).zip(pseudo_outs) {
        let Input::ToKey {
            amount,
            key_offsets,
            key_image,
        } = input
        else {
            return Err(fail());
        };
        if amount.is_some() || key_offsets.len() != 16 || !actual.insert(key_image.to_bytes()) {
            return Err(fail());
        }
        let ring = rings.get(key_offsets).ok_or_else(fail)?;
        let ring: Vec<[CompressedPoint; 2]> = ring
            .iter()
            .map(|p| [p[0].compress(), p[1].compress()])
            .collect();
        clsag
            .verify(ring, key_image, pseudo, &hash)
            .map_err(|_| fail())?;
    }
    if &actual != images {
        return Err(fail());
    }
    let mut batch = BatchVerifier::new();
    if !bulletproof.batch_verify(&mut OsRng, &mut batch, &proofs.base.commitments)
        || !batch.verify()
    {
        return Err(fail());
    }
    let mut left = curve25519_dalek::EdwardsPoint::default();
    let mut right = Commitment::new(Scalar::ZERO, fee).commit().into();
    for p in pseudo_outs {
        left += p.decompress().ok_or_else(fail)?.into();
    }
    for p in &proofs.base.commitments {
        right += p.decompress().ok_or_else(fail)?.into();
    }
    if Point::from(left) != Point::from(right) {
        return Err(fail());
    }
    Ok(tx)
}
