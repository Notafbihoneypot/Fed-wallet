use crate::EngineError;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use zeroize::Zeroize;
#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct OutputRecord {
    pub bytes: String,
    pub height: u64,
    pub coinbase: bool,
    pub image: Option<String>,
    pub spent_height: Option<u64>,
}
impl Drop for OutputRecord {
    fn drop(&mut self) {
        self.bytes.zeroize();
    }
}
#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct Checkpoint {
    pub height: u64,
    pub hash: [u8; 32],
    pub next_output: u64,
}
#[derive(Clone, Serialize, Deserialize)]
pub struct HistoryEntry {
    pub transaction: String,
    pub height: u64,
    pub received_atomic: u64,
    pub sent_atomic: Option<u64>,
    pub destination: Option<String>,
}
#[derive(Clone, Serialize, Deserialize)]
pub struct PendingPayment {
    pub transaction: String,
    pub destination: String,
    pub amount_atomic: u64,
    pub fee_atomic: u64,
    pub mined_height: Option<u64>,
    pub(crate) bytes: String,
    pub(crate) inputs: Vec<String>,
}
impl Drop for PendingPayment {
    fn drop(&mut self) {
        self.bytes.zeroize();
    }
}
#[derive(Clone, Serialize, Deserialize)]
pub struct State {
    pub(crate) version: u8,
    pub(crate) address: String,
    pub(crate) start_height: u64,
    pub(crate) next_height: u64,
    pub(crate) next_output: Option<u64>,
    pub(crate) checkpoints: Vec<Checkpoint>,
    pub(crate) outputs: BTreeMap<String, OutputRecord>,
    pub(crate) history: Vec<HistoryEntry>,
    pub(crate) payments: Vec<PendingPayment>,
    pub(crate) subaddress_count: u32,
    #[serde(default = "first_subaddress")]
    pub(crate) created_subaddress: u32,
    #[serde(default = "first_subaddress")]
    pub(crate) selected_subaddress: u32,
    #[serde(default)]
    pub(crate) subaddress_labels: BTreeMap<u32, String>,
}
fn first_subaddress() -> u32 {
    1
}
impl State {
    pub(crate) fn new(address: String, start_height: u64) -> Self {
        Self {
            version: 1,
            address,
            start_height,
            next_height: start_height,
            next_output: (start_height == 0).then_some(0),
            checkpoints: vec![],
            outputs: BTreeMap::new(),
            history: vec![],
            payments: vec![],
            subaddress_count: 20,
            created_subaddress: 1,
            selected_subaddress: 1,
            subaddress_labels: BTreeMap::new(),
        }
    }
    pub(crate) fn validate(&self) -> Result<(), EngineError> {
        if self.version != 1
            || self.address.len() != 95
            || self.start_height > u64::from(u32::MAX)
            || self.next_height < self.start_height
            || self.next_height > u64::from(u32::MAX)
            || self.checkpoints.len() > 128
            || self.outputs.len() > 50_000
            || self.history.len() > 50_000
            || self.payments.len() > 50_000
            || !(20..=1000).contains(&self.subaddress_count)
            || !(1..=crate::MAX_SUBADDRESSES).contains(&self.created_subaddress)
            || self.selected_subaddress > self.created_subaddress
            || self.created_subaddress > self.subaddress_count
            || self.subaddress_labels.iter().any(|(index, label)| {
                *index > self.created_subaddress || !crate::valid_subaddress_label(label)
            })
        {
            return Err(EngineError::Storage);
        }
        for (key, o) in &self.outputs {
            if key.len() != 64
                || o.bytes.len() > 16_384
                || o.height >= self.next_height
                || o.image.as_ref().is_some_and(|s| s.len() != 64)
                || o.spent_height
                    .is_some_and(|h| h < o.height || h >= self.next_height)
            {
                return Err(EngineError::Storage);
            }
        }
        for p in &self.payments {
            if p.transaction.len() != 64
                || p.destination.len() > 196
                || p.amount_atomic == 0
                || p.inputs.is_empty()
                || p.inputs.len() > 16
                || p.inputs.iter().any(|s| s.len() != 64)
                || p.bytes.len() > 256_000
            {
                return Err(EngineError::Storage);
            }
        }
        if self
            .checkpoints
            .windows(2)
            .any(|p| p[0].height + 1 != p[1].height || p[0].next_output > p[1].next_output)
            || self.checkpoints.last().is_some_and(|p| {
                p.height + 1 != self.next_height || Some(p.next_output) != self.next_output
            })
        {
            return Err(EngineError::Storage);
        }
        Ok(())
    }
    pub(crate) fn rollback(&mut self, next_height: u64) {
        self.outputs.retain(|_, o| o.height < next_height);
        for o in self.outputs.values_mut() {
            if o.spent_height.is_some_and(|h| h >= next_height) {
                o.spent_height = None;
            }
        }
        self.history.retain(|e| e.height < next_height);
        for p in &mut self.payments {
            if p.mined_height.is_some_and(|h| h >= next_height) {
                p.mined_height = None;
            }
        }
        self.checkpoints.retain(|p| p.height < next_height);
        self.next_height = next_height;
        self.next_output = self
            .checkpoints
            .last()
            .map(|p| p.next_output)
            .or((next_height == 0).then_some(0));
    }
    pub(crate) fn reserved(&self, key: &str) -> bool {
        self.payments
            .iter()
            .any(|p| p.mined_height.is_none() && p.inputs.iter().any(|i| i == key))
    }
    /// Marks a saved output spent. A still-pending payment using that input
    /// lost to another transaction and can never be mined; drop it so it
    /// releases its other inputs and stops blocking new payments.
    pub(crate) fn mark_spent(&mut self, key: &str, height: u64) -> Result<(), EngineError> {
        self.outputs
            .get_mut(key)
            .ok_or(EngineError::Storage)?
            .spent_height = Some(height);
        self.payments
            .retain(|p| p.mined_height.is_some() || !p.inputs.iter().any(|i| i == key));
        Ok(())
    }
    /// Signed bytes are kept only for rebroadcast. Past the 128-block rollback
    /// window a confirmed payment keeps just its summary, so the journal stays small.
    pub(crate) fn strip_settled_payments(&mut self) {
        let settled = self.next_height.saturating_sub(128);
        for p in &mut self.payments {
            if p.mined_height.is_some_and(|h| h < settled) {
                p.bytes.zeroize();
            }
        }
    }
}
