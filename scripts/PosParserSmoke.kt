// Portable source-only checks: kotlinc, no Android SDK or keys required.
package app.independentwallet.android

fun main() {
    val main = "4" + "1".repeat(94)
    val sub = "8" + "1".repeat(94)
    val stage = "5" + "1".repeat(94)
    var accepted = 0
    var rejected = 0
    fun accept(raw: String, network: String, amount: String) {
        val r = parsePosPaymentUri(raw, network)
        check(r.amountXmr == amount)
        accepted++
    }
    fun reject(raw: String, network: String = "Mainnet") {
        try { parsePosPaymentUri(raw, network) }
        catch (_: IllegalArgumentException) { rejected++; return }
        error("Unsafe POS payment accepted by parser")
    }
    accept("monero:$main?tx_amount=0002.340000000000&recipient_name=Shop&tx_description=Order%20123", "Mainnet", "2.34")
    accept("monero:$sub?tx_amount=0.000000000001", "Mainnet", "0.000000000001")
    accept("monero:$stage?tx_amount=0.01", "Stagenet", "0.01")
    for (bad in listOf(main, "monero:$main", "monero:$main?recipient_name=Shop",
        "monero:$main?tx_amount=0", "monero:$main?tx_amount=0.000000000000",
        "monero:$stage?tx_amount=1", "monero:$main?tx_amount=1&tx_amount=2",
        "monero:$main?tx_amount=1&spend_key=abc", "monero://$main?tx_amount=1",
        "monero:$main?tx_amount=-1", "monero:$main?tx_amount=0.0000000000001",
        "monero:$main?tx_amount=18446744.073709551616", "monero:$main?tx_amount=1e3",
        "monero:$main?tx_amount=1&unknown=2", "monero:$main?tx_amount=1#fragment")) reject(bad)
    val legacy = NfcPaymentProtocol.encode("Mainnet", main)
    check(NfcPaymentProtocol.decode(legacy, "Mainnet") == main)
    check(NfcPaymentProtocol.decode(NfcPaymentProtocol.responseData(legacy + NfcPaymentProtocol.ok), "Mainnet") == main)
    try { NfcPaymentProtocol.decode(legacy, "Stagenet"); error("Accepted wrong network") }
    catch (_: IllegalArgumentException) { rejected++ }
    try { NfcPaymentProtocol.encode("Mainnet", stage); error("Accepted wrong-network address") }
    catch (_: IllegalArgumentException) { rejected++ }
    println("PASS: $accepted valid POS URIs, $rejected invalid/untrusted cases, legacy NFC round-trip")
}
