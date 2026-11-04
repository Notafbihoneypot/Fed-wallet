package app.independentwallet.android

import org.junit.Assert.*
import org.junit.Test

class PosPaymentInputTest {
    private val main = "4" + "1".repeat(94)
    private val stagenet = "5" + "1".repeat(94)

    @Test fun merchantRequestRetainsExactAmountAndRecipient() {
        val request = parsePosPaymentUri(
            "monero:$main?tx_amount=0002.340000000000&recipient_name=Shop&tx_description=Order%20123", "Mainnet")
        assertEquals(main, request.address)
        assertEquals("2.34", request.amountXmr)
    }

    @Test fun zeroOrMissingAmountNeverBecomesAnAcceptedInvoice() {
        for (input in listOf(main, "monero:$main", "monero:$main?recipient_name=Shop",
            "monero:$main?tx_amount=0", "monero:$main?tx_amount=0.000000000000")) {
            assertThrows(input, IllegalArgumentException::class.java) { parsePosPaymentUri(input, "Mainnet") }
        }
    }

    @Test fun rejectWrongNetworkDuplicateFieldsAndPrivateKeyAttempts() {
        for (input in listOf("monero:$stagenet?tx_amount=1", "monero:$main?tx_amount=1&tx_amount=2",
            "monero:$main?tx_amount=1&spend_key=deadbeef", "monero://$main?tx_amount=1",
            "monero:$main?tx_amount=-1", "monero:$main?tx_amount=0.0000000000001")) {
            assertThrows(input, IllegalArgumentException::class.java) { parsePosPaymentUri(input, "Mainnet") }
        }
    }
}
