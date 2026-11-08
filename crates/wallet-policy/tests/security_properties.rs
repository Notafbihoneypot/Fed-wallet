use wallet_policy::{amount::*, connection::*};

#[test]
fn atomic_amounts_are_exact() {
    assert_eq!(parse_xmr("0.000000000001"), Ok(1));
    assert_eq!(parse_xmr("1.234567890123"), Ok(1_234_567_890_123));
    assert_eq!(parse_xmr("18446744.073709551615"), Ok(u64::MAX));
}

#[test]
fn unsafe_or_ambiguous_amounts_are_rejected() {
    for value in [
        "0",
        "0.000",
        "-1",
        "+1",
        "1e3",
        "1,5",
        " 1",
        "1 ",
        ".1",
        "1.",
        "1.0000000000001",
        "NaN",
        "inf",
        "١",
        "1..0",
    ] {
        assert!(parse_xmr(value).is_err(), "unexpectedly accepted {value:?}");
    }
    assert!(parse_xmr("18446744.073709551616").is_err());
}

#[test]
fn formatting_roundtrips_boundaries_and_many_atomic_values() {
    for value in [1, 9, 10, 999, ATOMIC_UNITS - 1, ATOMIC_UNITS, u64::MAX] {
        assert_eq!(parse_xmr(&format_xmr(value)), Ok(value));
    }
    for value in (1..=10_000).map(|v| v * 1_234_567_891) {
        assert_eq!(parse_xmr(&format_xmr(value)), Ok(value));
    }
}

#[test]
fn node_url_does_not_accept_credential_or_redirect_confusion() {
    for value in [
        "http://user:secret@example.com:18081",
        "http://example.com@evil.example:18081",
        "file:///tmp/wallet",
        "ftp://example.com",
        "http://example.com/rpc",
        "https://example.com?node=other",
        "http://example.com#other",
        "http://example.com:0",
        "http://example.com\\@evil.example",
        "http://example.com\n",
        "http://-bad.example",
        "http://short.onion:18081",
        "http://example..com",
        "http://",
    ] {
        assert!(
            NodeEndpoint::parse(value).is_err(),
            "unexpectedly accepted {value:?}"
        );
    }
}

#[test]
fn legitimate_node_forms_are_preserved() {
    for value in [
        "http://example.com:18081",
        "https://example.com:443",
        "http://[2001:db8::1]:18081",
        "http://127.0.0.1:18081",
    ] {
        assert!(NodeEndpoint::parse(value).is_ok());
    }
    let onion = format!("http://{}.onion:18081", "a".repeat(56));
    assert!(NodeEndpoint::parse(&onion).unwrap().is_onion());
}
