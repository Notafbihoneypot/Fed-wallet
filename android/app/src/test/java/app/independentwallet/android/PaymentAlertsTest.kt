package app.independentwallet.android

import app.independentwallet.bindings.WalletPayment
import app.independentwallet.bindings.WalletSyncStatus
import app.independentwallet.bindings.WalletTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PaymentAlertsTest {
    private val none = emptySet<PaymentAlert>()
    private fun received(id: String, height: ULong) = WalletTransfer(id, height, "0.5", null, null)
    private fun sent(id: String, height: ULong) = WalletTransfer(id, height, "0.1", "0.4", "fixture-destination")
    private fun payment(id: String, mined: ULong?) = WalletPayment(id, "fixture-destination", "0.4", "0.01", "0.41",
        mined, if (mined == null) 0uL else 1uL, false, emptyList())
    private fun status(next: ULong, target: ULong, history: List<WalletTransfer> = emptyList(), payments: List<WalletPayment> = emptyList()) =
        WalletSyncStatus(true, 0uL, next, target, next == target, false, "0.5", "0.5", "0.5", null, history, null, payments)

    @Test fun aPaymentInANewBlockAlertsEvenThoughTheTipCheckMarkedTheWalletUnsynced() {
        val tracker = PaymentAlertTracker()
        assertEquals(none, tracker.next(status(100uL, 100uL, listOf(received("old", 90uL)))))
        // A new block is seen: the wallet is briefly not synced, as in the real app.
        assertEquals(none, tracker.next(status(100uL, 101uL, listOf(received("old", 90uL)))))
        assertEquals(setOf(PaymentAlert.Received),
            tracker.next(status(101uL, 101uL, listOf(received("new", 100uL), received("old", 90uL)))))
        assertEquals(none, tracker.next(status(101uL, 101uL, listOf(received("new", 100uL), received("old", 90uL)))))
    }

    @Test fun paymentsFoundWhileCatchingUpNeverAlert() {
        val tracker = PaymentAlertTracker()
        assertEquals(none, tracker.next(status(50uL, 100uL)))
        assertEquals(none, tracker.next(status(80uL, 100uL, listOf(received("old", 70uL)))))
        assertEquals(none, tracker.next(status(100uL, 100uL, listOf(received("older", 95uL), received("old", 70uL)))))
    }

    @Test fun aRescanOrReopenDoesNotReplayOldPayments() {
        val tracker = PaymentAlertTracker()
        tracker.next(status(100uL, 100uL, listOf(received("old", 90uL))))
        // Rescan from an earlier block: history is rebuilt below the synced tip.
        tracker.next(status(60uL, 100uL))
        assertEquals(none, tracker.next(status(100uL, 100uL, listOf(received("old", 90uL)))))
        // Lock or wallet switch clears the baseline entirely.
        tracker.next(null)
        assertEquals(none, tracker.next(status(100uL, 100uL, listOf(received("old", 90uL)))))
    }

    @Test fun ownPaymentsAlertWhenTheyConfirm() {
        val tracker = PaymentAlertTracker()
        tracker.next(status(100uL, 100uL, payments = listOf(payment("mine", null))))
        tracker.next(status(100uL, 101uL, payments = listOf(payment("mine", null))))
        assertEquals(setOf(PaymentAlert.Confirmed),
            tracker.next(status(101uL, 101uL, listOf(sent("mine", 100uL)), listOf(payment("mine", 100uL)))))
    }

    @Test fun alertWordingNeverCarriesWalletDetails() {
        (PaymentAlert.entries.map { it.title } + ALERT_TEXT).forEach { text ->
            assertFalse(text, text.any { it.isDigit() })
            assertFalse(text, text.contains("XMR"))
        }
    }
}
