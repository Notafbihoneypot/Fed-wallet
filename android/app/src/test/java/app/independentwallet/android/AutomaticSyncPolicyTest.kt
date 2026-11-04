package app.independentwallet.android

import app.independentwallet.bindings.*
import org.junit.Assert.*
import org.junit.Test

class AutomaticSyncPolicyTest {
    private val status = WalletSyncStatus(hasScan = true, startHeight = 100uL, nextHeight = 110uL,
        targetHeight = 110uL, synced = true, viewOnly = false, receivedXmr = "1", balanceXmr = "1", unlockedXmr = "1",
        nextUnlockBlocks = null, history = emptyList(), pending = null, payments = emptyList())
    private val review = SpendReview(sendMax = false, reviewId = "public-review", destination = "public-address",
        amountXmr = "0.9", feeXmr = "0.1", totalXmr = "1", transaction = "public-transaction")
    private val idle = OfflineUiState(stage = WalletStage.Open, hasVault = true,
        online = OnlineUiState(networkEnabled = true, connected = true, backgroundAllowed = true,
            backgroundService = true, status = status))

    @Test fun editorReviewAndAuthenticationPauseWorkWithoutStoppingTheVisibleService() {
        for (ui in listOf(idle.copy(online = idle.online.copy(composingPayment = true)),
            idle.copy(online = idle.online.copy(review = review)), idle.copy(busy = true))) {
            assertTrue(canKeepSyncService(ui))
            assertFalse(canPollNetwork(ui, ui.busy))
            assertFalse(canContinueBackground(ui, ui.busy))
        }
        assertFalse(canPollNetwork(idle, true))
    }
    @Test fun approvedPublicationMayFinishButCannotCreateAnotherPaymentInBackground() {
        val submitting = idle.copy(online = idle.online.copy(submitting = true, busy = true, review = review))
        assertTrue(canKeepSyncService(submitting)); assertTrue(canContinueBackground(submitting, false))
        assertFalse(canPollNetwork(submitting, false)); assertFalse(canQuotePayment(submitting, true))
        val locked = submitting.copy(stage = WalletStage.Locked,
            online = submitting.online.copy(backgroundActive = true, review = null))
        assertTrue(canKeepSyncService(locked)); assertTrue(canContinueBackground(locked, false))
        assertFalse(canQuotePayment(locked, false)); assertFalse(canPollNetwork(locked, false))
        for (online in listOf(locked.online.copy(backgroundAllowed = false), locked.online.copy(backgroundService = false),
            locked.online.copy(networkEnabled = false), locked.online.copy(error = "failed")))
            assertFalse(canContinueBackground(locked.copy(online = online), false))
    }
    @Test fun idleTipChecksRetainTheirOwnerButCannotOverlapAnotherPoll() {
        val checking = idle.copy(stage = WalletStage.Locked,
            online = idle.online.copy(backgroundActive = true, polling = true, busy = true))
        assertTrue(canKeepSyncService(checking)); assertTrue(canContinueBackground(checking, false))
        assertFalse(canPollNetwork(checking, false))
        assertTrue(canPollNetwork(checking.copy(online = checking.online.copy(polling = false, busy = false)), false))
    }
    @Test fun normalUpdatesBatchFiveMinutesAndOnlyDurablePendingPaymentsPollSooner() {
        assertEquals(300_000L, syncPollIntervalMs(idle.online))
        val pending = PendingTransfer(transaction = "public-id", destination = "public-address", amountXmr = "0.9", feeXmr = "0.1")
        assertEquals(60_000L, syncPollIntervalMs(idle.online.copy(status = status.copy(pending = pending))))
        assertEquals(300_000L, syncPollIntervalMs(idle.online.copy(review = review)))
    }
    @Test fun quotingRequiresVisibleUnlockedForegroundAndNoPublication() {
        assertTrue(canQuotePayment(idle, true))
        assertFalse(canQuotePayment(idle, false))
        assertFalse(canQuotePayment(idle.copy(busy = true), true))
        assertFalse(canQuotePayment(idle.copy(receivingBusy = true), true))
        assertFalse(canPollNetwork(idle.copy(receivingBusy = true), false))
        assertTrue(canKeepSyncService(idle.copy(receivingBusy = true)))
        assertFalse(canQuotePayment(idle.copy(stage = WalletStage.Locked), true))
        assertFalse(canQuotePayment(idle.copy(online = idle.online.copy(backgroundActive = true)), true))
    }
    @Test fun stopPauseErrorsAndUnscannedStartChoiceCannotRetainAnIdleService() {
        assertFalse(canKeepSyncService(idle.copy(hasVault = false)))
        assertFalse(canKeepSyncService(idle.copy(stage = WalletStage.Locked)))
        assertFalse(canKeepSyncService(idle.copy(stage = WalletStage.Recovery)))
        for (online in listOf(idle.online.copy(backgroundAllowed = false), idle.online.copy(networkEnabled = false),
            idle.online.copy(pauseRequested = true), idle.online.copy(error = "failed"),
            idle.online.copy(status = status.copy(hasScan = false, startHeight = 0uL, nextHeight = 0uL))))
            assertFalse(canKeepSyncService(idle.copy(online = online)))
    }
    @Test fun serviceNotificationDoesNotReportCompletionWhileSubmittingOrReviewing() {
        for (online in listOf(idle.online.copy(composingPayment = true), idle.online.copy(review = review),
            idle.online.copy(submitting = true, busy = true))) {
            val notification = syncNotification(online)
            assertNull(notification.progress)
            val text = notification.title + notification.text + notification.detail
            assertFalse(text.contains("100%")); assertFalse(text.contains(review.destination))
            assertFalse(text.contains(review.transaction)); assertFalse(text.contains(review.amountXmr))
        }
    }

    @Test fun localPaymentInputErrorsCanKeepTheServiceButTransportErrorsCannot() {
        val local = idle.copy(online = idle.online.copy(composingPayment = true, paymentError = true, error = "Invalid amount"))
        assertTrue(canKeepSyncService(local)); assertFalse(canPollNetwork(local, false))
        assertFalse(canKeepSyncService(local.copy(online = local.online.copy(paymentError = false))))
    }

    @Test fun returningThroughDeviceAuthenticationKeepsExistingBackgroundOwnerIdle() {
        val unlocking = idle.copy(stage = WalletStage.Locked, busy = true,
            online = idle.online.copy(backgroundActive = true))
        assertTrue(canKeepSyncService(unlocking))
        assertFalse(canPollNetwork(unlocking, true)); assertFalse(canContinueBackground(unlocking, true))
        assertFalse(canQuotePayment(unlocking, true))
        assertFalse(canKeepSyncService(unlocking.copy(online = unlocking.online.copy(backgroundActive = false))))
        val publishing = unlocking.copy(online = unlocking.online.copy(submitting = true, busy = true))
        assertTrue(canKeepSyncService(publishing)); assertFalse(canPollNetwork(publishing, true))
    }

}
