package app.independentwallet.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** APDU logic only; this does not simulate NFC radio or physical devices. */
@RunWith(AndroidJUnit4::class)
class NfcServiceDeviceTest {
    @After fun clear() { NfcAddressOffer.clear() }
    @Test fun serviceRequiresAnActiveOfferAndConsumesItOnce() = InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val service = NfcPaymentService()
        assertArrayEquals(NfcPaymentProtocol.unavailable, service.processCommandApdu(NfcPaymentProtocol.select, null))
        val address = "4" + "1".repeat(94)
        NfcAddressOffer.start("Mainnet", address)
        assertArrayEquals(NfcPaymentProtocol.unavailable, service.processCommandApdu(NfcPaymentProtocol.read, null))
        assertArrayEquals(NfcPaymentProtocol.ok, service.processCommandApdu(NfcPaymentProtocol.select, null))
        val result = service.processCommandApdu(NfcPaymentProtocol.read, null)
        assertEquals(address, NfcPaymentProtocol.decode(NfcPaymentProtocol.responseData(result), "Mainnet"))
        assertArrayEquals(NfcPaymentProtocol.unavailable, service.processCommandApdu(NfcPaymentProtocol.select, null))
    }
    @Test fun cancelledOrDeactivatedOffersCannotBeReadWithoutSelection() = InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val service = NfcPaymentService()
        NfcAddressOffer.start("Mainnet", "4" + "1".repeat(94))
        assertArrayEquals(NfcPaymentProtocol.ok, service.processCommandApdu(NfcPaymentProtocol.select, null))
        service.onDeactivated(0)
        assertArrayEquals(NfcPaymentProtocol.unavailable, service.processCommandApdu(NfcPaymentProtocol.read, null))
        assertArrayEquals(NfcPaymentProtocol.ok, service.processCommandApdu(NfcPaymentProtocol.select, null))
        NfcAddressOffer.clear()
        assertArrayEquals(NfcPaymentProtocol.unavailable, service.processCommandApdu(NfcPaymentProtocol.read, null))
    }
}
