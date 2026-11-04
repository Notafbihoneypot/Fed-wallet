use crate::{EngineError, WalletEngine};
use monero_daemon_rpc::HttpTransport;

pub const MAX_SUBADDRESSES: u32 = 1000;

pub struct SubaddressLabel {
    pub index: u32,
    pub label: String,
}

pub struct ReceivingAddressBook {
    pub selected_index: u32,
    pub selected_address: String,
    pub addresses: Vec<SubaddressLabel>,
    pub maximum_subaddresses: u32,
}
pub(crate) fn valid_subaddress_label(label: &str) -> bool {
    label.len() <= 256
        && label.chars().count() <= 64
        && !label.chars().any(|c| {
            c.is_control() || matches!(c, '\u{202a}'..='\u{202e}' | '\u{2066}'..='\u{2069}')
        })
}
impl<T: HttpTransport> WalletEngine<T> {
    pub fn receiving_addresses(&self) -> Result<ReceivingAddressBook, EngineError> {
        self.check()?;
        Ok(ReceivingAddressBook {
            selected_index: self.state.selected_subaddress,
            selected_address: self.receive_address(self.state.selected_subaddress)?,
            maximum_subaddresses: MAX_SUBADDRESSES,
            addresses: (0..=self.state.created_subaddress)
                .map(|index| SubaddressLabel {
                    index,
                    label: self
                        .state
                        .subaddress_labels
                        .get(&index)
                        .cloned()
                        .unwrap_or_default(),
                })
                .collect(),
        })
    }
    pub fn create_subaddress(&mut self, label: &str) -> Result<ReceivingAddressBook, EngineError> {
        self.check_address_edit(label)?;
        let index = self
            .state
            .created_subaddress
            .checked_add(1)
            .filter(|i| *i <= MAX_SUBADDRESSES)
            .ok_or(EngineError::Subaddress)?;
        let mut next = self.state.clone();
        next.created_subaddress = index;
        next.selected_subaddress = index;
        next.subaddress_count = next.subaddress_count.max(index);
        if !label.trim().is_empty() {
            next.subaddress_labels
                .insert(index, label.trim().to_owned());
        }
        // Persist the index/label before returning an address that can receive funds.
        self.commit(next)?;
        self.receiving_addresses()
    }
    pub fn select_subaddress(&mut self, index: u32) -> Result<ReceivingAddressBook, EngineError> {
        self.check_address_edit("")?;
        if index > self.state.created_subaddress {
            return Err(EngineError::Subaddress);
        }
        if index != self.state.selected_subaddress {
            let mut next = self.state.clone();
            next.selected_subaddress = index;
            self.commit(next)?;
        }
        self.receiving_addresses()
    }
    pub fn label_subaddress(
        &mut self,
        index: u32,
        label: &str,
    ) -> Result<ReceivingAddressBook, EngineError> {
        self.check_address_edit(label)?;
        if index > self.state.created_subaddress {
            return Err(EngineError::Subaddress);
        }
        let mut next = self.state.clone();
        if label.trim().is_empty() {
            next.subaddress_labels.remove(&index);
        } else {
            next.subaddress_labels
                .insert(index, label.trim().to_owned());
        }
        self.commit(next)?;
        self.receiving_addresses()
    }
    fn check_address_edit(&self, label: &str) -> Result<(), EngineError> {
        self.check()?;
        if self.prepared.is_some() {
            return Err(EngineError::Busy);
        }
        if !valid_subaddress_label(label) {
            return Err(EngineError::Subaddress);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tests::NoNetwork;
    use tokio_util::sync::CancellationToken;
    use wallet_keys::{Network, OfflineWallet};
    use zeroize::Zeroizing;

    fn wallet() -> OfflineWallet {
        OfflineWallet::restore_spend_key(
            Network::Mainnet,
            Zeroizing::new(format!("01{}", "00".repeat(31))),
            0,
        )
        .unwrap()
    }
    #[test]
    fn addresses_are_distinct_durable_and_do_not_need_a_node() {
        let d = tempfile::tempdir().unwrap();
        let w = wallet();
        let mut engine =
            WalletEngine::<NoNetwork>::new(&w, d.path(), CancellationToken::new()).unwrap();
        let first = engine.receiving_addresses().unwrap();
        assert_eq!(first.selected_index, 1);
        assert_eq!(first.addresses.len(), 2);
        let second = engine.create_subaddress("Customer A").unwrap();
        let address = second.selected_address.clone();
        assert_ne!(first.selected_address, address);
        assert_eq!(second.selected_index, 2);
        assert_eq!(second.addresses[2].label, "Customer A");
        assert!(engine.rpc.is_none() && engine.scanner_cache.is_none());
        drop(engine);
        let mut engine =
            WalletEngine::<NoNetwork>::new(&w, d.path(), CancellationToken::new()).unwrap();
        let reopened = engine.receiving_addresses().unwrap();
        assert_eq!(reopened.selected_address, address);
        assert_eq!(reopened.addresses[2].label, "Customer A");
        assert_eq!(
            engine.select_subaddress(0).unwrap().selected_address,
            w.details().address
        );
        engine.label_subaddress(2, "Updated").unwrap();
        engine.rescan(0).unwrap();
        assert_eq!(
            engine.select_subaddress(2).unwrap().selected_address,
            address
        );
        assert_eq!(
            engine.receiving_addresses().unwrap().addresses[2].label,
            "Updated"
        );
        assert!(engine.state.payments.is_empty());
    }
    #[test]
    fn legacy_cache_migrates_and_invalid_metadata_does_not_commit() {
        let d = tempfile::tempdir().unwrap();
        let w = wallet();
        let mut engine =
            WalletEngine::<NoNetwork>::new(&w, d.path(), CancellationToken::new()).unwrap();
        let mut old = serde_json::to_value(&engine.state).unwrap();
        let map = old.as_object_mut().unwrap();
        for name in [
            "created_subaddress",
            "selected_subaddress",
            "subaddress_labels",
        ] {
            map.remove(name);
        }
        let restored: crate::State = serde_json::from_value(old).unwrap();
        restored.validate().unwrap();
        assert_eq!(restored.created_subaddress, 1);
        engine.commit(restored).unwrap();
        let saves = engine.store.saves.load(std::sync::atomic::Ordering::SeqCst);
        for label in [
            "a".repeat(65),
            "bad\nlabel".into(),
            "bad\u{202e}label".into(),
        ] {
            assert!(matches!(
                engine.create_subaddress(&label),
                Err(EngineError::Subaddress)
            ));
        }
        assert!(engine.select_subaddress(2).is_err());
        assert!(engine.label_subaddress(2, "unknown").is_err());
        assert_eq!(
            engine.store.saves.load(std::sync::atomic::Ordering::SeqCst),
            saves
        );
        assert_eq!(engine.receiving_addresses().unwrap().addresses.len(), 2);
        let mut corrupt = engine.state.clone();
        corrupt.selected_subaddress = 99;
        assert!(corrupt.validate().is_err());
        corrupt = engine.state.clone();
        corrupt.subaddress_labels.insert(999, "invalid".into());
        assert!(corrupt.validate().is_err());
    }
    #[test]
    fn address_limit_and_cancelled_owner_fail_without_exposing_new_addresses() {
        let d = tempfile::tempdir().unwrap();
        let w = wallet();
        let token = CancellationToken::new();
        let mut engine = WalletEngine::<NoNetwork>::new(&w, d.path(), token.clone()).unwrap();
        let mut next = engine.state.clone();
        next.created_subaddress = MAX_SUBADDRESSES - 1;
        next.subaddress_count = MAX_SUBADDRESSES;
        engine.commit(next).unwrap();
        let last = engine.create_subaddress("Last").unwrap();
        assert_eq!(last.selected_index, MAX_SUBADDRESSES);
        assert_eq!(last.addresses.len(), MAX_SUBADDRESSES as usize + 1);
        assert!(matches!(
            engine.create_subaddress("overflow"),
            Err(EngineError::Subaddress)
        ));
        token.cancel();
        assert!(matches!(
            engine.receiving_addresses(),
            Err(EngineError::Disconnected)
        ));
        assert!(engine.select_subaddress(0).is_err());
    }
}
