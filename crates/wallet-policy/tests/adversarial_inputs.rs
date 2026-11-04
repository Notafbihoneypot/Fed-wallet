//! Deterministic parser mutations, not a source of cryptographic randomness.
use wallet_policy::{
    amount::{format_xmr, parse_xmr},
    connection::NodeEndpoint,
};

fn mutate(state: &mut u64) -> u64 {
    *state ^= *state << 13;
    *state ^= *state >> 7;
    *state ^= *state << 17;
    *state
}

#[test]
fn mutated_urls_and_amounts_remain_bounded_and_canonical() {
    let mut state = 0x45fc_11a9_6712_e3db;
    const ALPHABET: &[u8] = b"abcXYZ012789:/@.?#%\\[] -_\x00\r\n\t";
    for case in 0..20_000 {
        let len = mutate(&mut state) as usize % 96;
        let mut input = String::with_capacity(112);
        if case % 2 == 0 {
            input.push_str("http://");
        }
        for _ in 0..len {
            input.push(char::from(
                ALPHABET[mutate(&mut state) as usize % ALPHABET.len()],
            ));
        }
        if let Ok(node) = NodeEndpoint::parse(&input) {
            let again = NodeEndpoint::parse(node.as_str()).unwrap();
            assert_eq!(node.as_str(), again.as_str());
            assert!(!node.as_str().contains('@'));
            assert!(!node.as_str().contains('?'));
            assert!(!node.as_str().contains('#'));
        }
        if let Ok(amount) = parse_xmr(&input) {
            assert_eq!(parse_xmr(&format_xmr(amount)), Ok(amount));
        }
        let amount = mutate(&mut state).max(1);
        assert_eq!(parse_xmr(&format_xmr(amount)), Ok(amount));
    }
}
