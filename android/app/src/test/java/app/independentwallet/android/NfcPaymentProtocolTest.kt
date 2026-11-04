package app.independentwallet.android

import org.junit.Assert.*
import org.junit.Test

class NfcPaymentProtocolTest {
    private val address = "4" + "1".repeat(94)
    @Test fun requestRoundTripPreservesOnlyNetworkAndAddress() {
        val wire = NfcPaymentProtocol.encode("Mainnet", address)
        assertEquals(address, NfcPaymentProtocol.decode(wire, "Mainnet"))
        assertEquals(address, NfcPaymentProtocol.decode(NfcPaymentProtocol.responseData(wire + NfcPaymentProtocol.ok), "Mainnet"))
    }
    @Test fun malformedOrWrongNetworkRequestsAreRejected() {
        val good = NfcPaymentProtocol.encode("Mainnet", address)
        assertThrows(IllegalArgumentException::class.java) { NfcPaymentProtocol.decode(good, "Stagenet") }
        assertThrows(IllegalArgumentException::class.java) { NfcPaymentProtocol.decode(good + "|amount=1".toByteArray(), "Mainnet") }
        assertThrows(IllegalArgumentException::class.java) { NfcPaymentProtocol.decode(ByteArray(512), "Mainnet") }
        assertThrows(IllegalArgumentException::class.java) { NfcPaymentProtocol.encode("Mainnet", "0".repeat(95)) }
        assertThrows(IllegalArgumentException::class.java) { NfcPaymentProtocol.encode("Mainnet", "5" + "1".repeat(94)) }
        assertThrows(IllegalArgumentException::class.java) { NfcPaymentProtocol.decode("IWM1|Mainnet|${"5" + "1".repeat(94)}".toByteArray(), "Mainnet") }
        assertThrows(IllegalArgumentException::class.java) { NfcPaymentProtocol.responseData(good + NfcPaymentProtocol.unavailable) }
    }
}
