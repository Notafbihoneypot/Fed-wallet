package app.independentwallet.android

/**
 * A merchant NFC tag is untrusted input, never evidence of payment or merchant identity.
 * It may prefill the existing Send sheet, but must not initiate preparation or signing.
 *
 * Fed POS uses an NFC Forum NDEF URI record with a `monero:` payment request.
 * Unlike a generic QR scan, a POS tap must carry an explicit nonzero amount.
 */
internal fun parsePosPaymentUri(raw: String, network: String): MoneroPaymentRequest {
    require(raw.startsWith("monero:", ignoreCase = true)) {
        "The NFC tag does not contain a Monero payment request."
    }
    val request = parseMoneroPaymentRequest(raw, network)
    require(request.amountXmr != null) {
        "The merchant NFC request has no positive payment amount."
    }
    return request
}
