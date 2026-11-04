package app.independentwallet.android

import org.junit.Assert.*
import org.junit.Test

class ReceiptEventTest {
    @Test fun acknowledgmentDoesNotDeletePaymentDetailsOrCreateAnotherEvent() {
        val sent=OnlineUiState(lastPaymentId="fixture-id",submission=SubmissionState.Accepted,receiptToOpen="fixture-id")
        val shown=consumeReceiptEvent(sent,"fixture-id")
        assertNull(shown.receiptToOpen)
        assertEquals("fixture-id",shown.lastPaymentId)
        assertEquals(SubmissionState.Accepted,shown.submission)
        assertEquals(shown,consumeReceiptEvent(shown,"fixture-id"))
        assertNull(shown.copy(phase="Checking chain tip").receiptToOpen)
    }
    @Test fun staleAcknowledgmentCannotConsumeAnotherSubmission() {
        val newer=OnlineUiState(lastPaymentId="new",submission=SubmissionState.Unknown,receiptToOpen="new")
        assertEquals(newer,consumeReceiptEvent(newer,"old"))
        assertEquals(SubmissionState.Unknown,consumeReceiptEvent(newer,"new").submission)
    }
}
