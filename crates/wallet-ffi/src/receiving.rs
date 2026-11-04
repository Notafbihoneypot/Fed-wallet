#[derive(uniffi::Record)]
pub struct SubaddressLabel {
    pub index: u32,
    pub label: String,
}
#[derive(uniffi::Record)]
pub struct ReceivingAddressBook {
    pub selected_index: u32,
    pub selected_address: String,
    pub addresses: Vec<SubaddressLabel>,
    pub maximum_subaddresses: u32,
}
impl From<wallet_engine::ReceivingAddressBook> for ReceivingAddressBook {
    fn from(book: wallet_engine::ReceivingAddressBook) -> Self {
        Self {
            selected_index: book.selected_index,
            selected_address: book.selected_address,
            addresses: book
                .addresses
                .into_iter()
                .map(|address| SubaddressLabel {
                    index: address.index,
                    label: address.label,
                })
                .collect(),
            maximum_subaddresses: book.maximum_subaddresses,
        }
    }
}
