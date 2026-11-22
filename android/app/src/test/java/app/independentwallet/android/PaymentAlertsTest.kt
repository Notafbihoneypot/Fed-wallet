package app.independentwallet.android

import app.independentwallet.bindings.WalletPayment
import app.independentwallet.bindings.WalletSyncStatus
import app.independentwallet.bindings.WalletTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PaymentAlertsTest {
    private fun received(id: String) = WalletTransfer(id, 10uL, "0.5", null, null)
    private fun sent(id: String) = WalletTransfer(id, 10uL, "0.1", "0.4", "fixture-destination")
    private fun payment(id: String, mined: ULong?) = WalletPayment(id, "fixture-destination", "0.4", "0.01", "0.41",
        mined, if (mined == null) 0uL else 1uL, false, emptyList())
    private fun status(synced: Boolean, history: List<WalletTransfer> = emptyList(), payments: List<WalletPayment> = emptyList()) =
        WalletSyncStatus(true, 0uL, 11uL, 11uL, synced, false, "0.5", "0.5", "0.5", null, history, null, payments)

    @Test fun aNewIncomingPaymentAfterCatchUpAlertsOnce() {
        val before = status(true, listOf(received("old")))
        val after = status(true, listOf(received("new"), received("old")))
        assertEquals(setOf(PaymentAlert.Received), paymentAlerts(before, after))
        assertEquals(emptySet<PaymentAlert>(), paymentAlerts(after, after))
    }

    @Test fun oldPaymentsFoundWhileCatchingUpNeverAlert() {
        val catchingUp = status(false)
        assertEquals(emptySet<PaymentAlert>(), paymentAlerts(catchingUp, status(false, listOf(received("old")))))
        assertEquals(emptySet<PaymentAlert>(), paymentAlerts(catchingUp, status(true, listOf(received("old")))))
        assertEquals(emptySet<PaymentAlert>(), paymentAlerts(null, status(true, listOf(received("old")))))
    }

    @Test fun ownPaymentsAlertOnlyWhenTheyConfirm() {
        val pending = status(true, payments = listOf(payment("mine", null)))
        val mined = status(true, listOf(sent("mine")), listOf(payment("mine", 11uL)))
        assertEquals(setOf(PaymentAlert.Confirmed), paymentAlerts(pending, mined))
        assertEquals(emptySet<PaymentAlert>(), paymentAlerts(mined, mined))
    }

    @Test fun alertWordingNeverCarriesWalletDetails() {
        (PaymentAlert.entries.map { it.title } + ALERT_TEXT).forEach { text ->
            assertFalse(text, text.any { it.isDigit() })
            assertFalse(text, text.contains("XMR"))
        }
    }
}
