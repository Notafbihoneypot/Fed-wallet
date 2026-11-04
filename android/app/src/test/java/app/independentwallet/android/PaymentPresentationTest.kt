package app.independentwallet.android

import app.independentwallet.bindings.WalletPayment
import app.independentwallet.bindings.PreparationStatus
import app.independentwallet.bindings.PreparationStage
import org.junit.Assert.*
import org.junit.Test

class PaymentPresentationTest {
    private val pending = WalletPayment(transaction = "public-test-id", destination = "public-fixture", amountXmr = "0.9",
        feeXmr = "0.1", totalXmr = "1", minedHeight = null, confirmations = 0uL)
    @Test fun nodeAcceptanceAndLostReplyAreNeverShownAsBlockConfirmation() {
        assertEquals("Submitted · awaiting confirmation", paymentStatusTitle(pending, SubmissionState.Accepted))
        assertEquals("Submission reply unknown", paymentStatusTitle(pending, SubmissionState.Unknown))
        assertEquals("Awaiting confirmation", paymentStatusTitle(pending, null))
        assertEquals("Awaiting confirmation", paymentStatusTitle(pending.copy(confirmations = 1uL), null))
    }
    @Test fun minedJournalEntryOverridesTransientSubmissionAcknowledgment() {
        val mined = pending.copy(minedHeight = 100uL, confirmations = 2uL)
        for (ack in listOf(null, SubmissionState.Unknown, SubmissionState.Accepted))
            assertEquals("Confirmed · 2 confirmations", paymentStatusTitle(mined, ack))
    }
    @Test fun torPreparationExplainsFirstLookupWithoutPromisingMiningSpeed() {
        assertTrue(paymentPreparationHint(NodeKind.Tor).contains("first payment"))
        assertTrue(paymentPreparationHint(NodeKind.Tor).contains("Completed parts are saved"))
        assertFalse(paymentPreparationHint(NodeKind.Direct).contains("Tor"))
    }
    @Test fun preparationShowsItsActualStageAndKeepsSubmissionUnknownSeparate() {
        val download = PreparationStatus(PreparationStage.FETCHING_DISTRIBUTION, 3u * 1024u * 1024u)
        assertTrue(preparationPhase(download, NodeKind.Tor)!!.contains("3 MB saved"))
        val failure = preparationFailure(download, "The node request timed out. [NET_TIMEOUT]")
        assertTrue(failure.contains("decoy data"))
        assertTrue(failure.contains("No payment was sent."))
        assertTrue(failure.endsWith("[NET_TIMEOUT]"))
        assertEquals("Broadcast unknown", preparationFailure(null, "Broadcast unknown"))
        assertNull(preparationPhase(download.copy(stage = PreparationStage.READY), NodeKind.Tor))
    }
}
