use wallet_policy::{amount::*, connection::*, payment::*, Error, Network, WalletMode};

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
fn no_node_exists_until_the_user_selects_one() {
    let mut gate = NetworkGate::default();
    assert!(gate.node().is_none());
    assert_eq!(gate.state(), ConnectionState::Offline);
    assert!(matches!(gate.begin_connect(), Err(Error::NodeRequired)));
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

#[test]
fn selection_does_not_authorize_networking() {
    let mut gate = NetworkGate::default();
    gate.select_node("http://example.com:18081").unwrap();
    assert_eq!(gate.state(), ConnectionState::Offline);
    let ticket = gate.begin_connect().unwrap();
    assert_eq!(
        gate.authorize_request(&ticket),
        Err(Error::TransportNotReady)
    );
    gate.tor_ready(&ticket).unwrap();
    assert_eq!(
        gate.authorize_request(&ticket),
        Ok("http://example.com:18081/")
    );
}

#[test]
fn tor_failure_closes_the_gate_and_does_not_change_nodes() {
    let mut gate = NetworkGate::default();
    gate.select_node("http://chosen.example:18081").unwrap();
    let ticket = gate.begin_connect().unwrap();
    gate.tor_ready(&ticket).unwrap();
    gate.pause().unwrap();
    assert_eq!(gate.authorize_request(&ticket), Err(Error::StaleSession));
    assert_eq!(
        gate.node().unwrap().as_str(),
        "http://chosen.example:18081/"
    );
}

#[test]
fn changing_nodes_rejects_late_callbacks() {
    let mut gate = NetworkGate::default();
    gate.select_node("http://first.example:18081").unwrap();
    let first = gate.begin_connect().unwrap();
    gate.select_node("http://second.example:18081").unwrap();
    assert_eq!(gate.tor_ready(&first), Err(Error::StaleSession));
    assert_eq!(gate.authorize_request(&first), Err(Error::StaleSession));
}

#[test]
fn reconnect_invalidates_the_previous_connection() {
    let mut gate = NetworkGate::default();
    gate.select_node("http://example.com:18081").unwrap();
    let old = gate.begin_connect().unwrap();
    let new = gate.begin_connect().unwrap();
    assert_eq!(gate.tor_ready(&old), Err(Error::StaleSession));
    gate.tor_ready(&new).unwrap();
    assert!(gate.authorize_request(&new).is_ok());
}

#[test]
fn independent_connection_gates_reject_each_others_tickets() {
    let mut first = NetworkGate::default();
    let mut second = NetworkGate::default();
    first.select_node("http://example.com:18081").unwrap();
    second.select_node("http://example.com:18081").unwrap();
    let first_ticket = first.begin_connect().unwrap();
    let second_ticket = second.begin_connect().unwrap();
    assert_eq!(second.tor_ready(&first_ticket), Err(Error::StaleSession));
    first.tor_ready(&first_ticket).unwrap();
    second.tor_ready(&second_ticket).unwrap();
    assert_eq!(
        second.authorize_request(&first_ticket),
        Err(Error::StaleSession)
    );
    assert_eq!(
        first.authorize_request(&second_ticket),
        Err(Error::StaleSession)
    );
    assert!(first.authorize_request(&first_ticket.clone()).is_ok());
    assert!(second.authorize_request(&second_ticket).is_ok());
}

fn plan() -> PreparedPayment {
    PreparedPayment {
        wallet_ref: "wallet-a".into(),
        network: Network::Stagenet,
        session: 42,
        inputs: vec![[1; 32], [2; 32]],
        recipients: vec![Recipient {
            address: "validated-recipient-a".into(),
            atomic: ATOMIC_UNITS,
        }],
        change: Some(Recipient {
            address: "validated-own-change".into(),
            atomic: 100,
        }),
        fee_atomic: 30,
    }
}
fn summary(p: &PreparedPayment) -> SignedSummary {
    SignedSummary {
        wallet_ref: p.wallet_ref.clone(),
        network: p.network,
        inputs: p.inputs.clone(),
        recipients: p.recipients.clone(),
        change: p.change.clone(),
        fee_atomic: p.fee_atomic,
    }
}

#[test]
fn matching_payment_and_input_order_are_accepted() {
    let p = plan();
    let mut s = summary(&p);
    s.inputs.reverse();
    let matched = p.verify(&s, 42).unwrap();
    assert_eq!(matched.wallet_ref(), "wallet-a");
    assert_eq!(matched.session(), 42);
}

#[test]
fn signed_payment_for_another_recipient_is_rejected() {
    let p = plan();
    let mut s = summary(&p);
    s.recipients[0].address = "validated-recipient-b".into();
    assert!(matches!(p.verify(&s, 42), Err(Error::PaymentMismatch)));
}

#[test]
fn altered_amount_fee_or_change_is_rejected() {
    let p = plan();
    let mut s = summary(&p);
    s.recipients[0].atomic += 1;
    assert!(p.verify(&s, 42).is_err());
    s = summary(&p);
    s.fee_atomic += 1;
    assert!(p.verify(&s, 42).is_err());
    s = summary(&p);
    s.change.as_mut().unwrap().address = "not-our-change".into();
    assert!(p.verify(&s, 42).is_err());
    s = summary(&p);
    s.change = None;
    assert!(p.verify(&s, 42).is_err());
}

#[test]
fn other_wallet_network_inputs_or_session_are_rejected() {
    let p = plan();
    let mut s = summary(&p);
    s.wallet_ref = "wallet-b".into();
    assert!(p.verify(&s, 42).is_err());
    s = summary(&p);
    s.network = Network::Mainnet;
    assert!(p.verify(&s, 42).is_err());
    s = summary(&p);
    s.inputs[0] = [3; 32];
    assert!(p.verify(&s, 42).is_err());
    assert!(matches!(
        p.verify(&summary(&p), 43),
        Err(Error::StaleSession)
    ));
}

#[test]
fn duplicate_inputs_and_overflowing_payments_are_rejected() {
    let mut p = plan();
    p.inputs.push([1; 32]);
    assert!(p.validate().is_err());
    p = plan();
    p.recipients = vec![
        Recipient {
            address: "same".into(),
            atomic: u64::MAX,
        },
        Recipient {
            address: "same".into(),
            atomic: 1,
        },
    ];
    assert!(p.validate().is_err());
}

#[test]
fn view_only_and_hardware_modes_never_authorize_local_signing() {
    assert!(WalletMode::Hot.allows_local_signing());
    assert!(!WalletMode::ViewOnly.allows_local_signing());
    assert!(!WalletMode::Hardware.allows_local_signing());
}

#[test]
fn aggregate_amounts_and_fees_cannot_overflow() {
    let mut p = plan();
    p.recipients = vec![
        Recipient {
            address: "first".into(),
            atomic: u64::MAX - 50,
        },
        Recipient {
            address: "second".into(),
            atomic: 51,
        },
    ];
    assert!(p.validate().is_err());
    p = plan();
    p.fee_atomic = u64::MAX;
    assert!(p.validate().is_err());
}
