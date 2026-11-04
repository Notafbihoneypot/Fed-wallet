package app.independentwallet.android

import org.junit.Assert.*
import org.junit.Test

class BackgroundSyncPolicyTest {
    private val scanning = OfflineUiState(stage = WalletStage.Open, hasVault = true,
        online = OnlineUiState(networkEnabled = true, connected = true, syncing = true,
            busy = true, backgroundAllowed = true, backgroundService = true))
    @Test fun onlyConsentedSavedScansCanSurviveLeaving() {
        assertTrue(canContinueBackground(scanning, false))
        assertTrue(canContinueBackground(scanning.copy(stage = WalletStage.Locked, online = scanning.online.copy(backgroundActive = true)), false))
        assertFalse(canContinueBackground(scanning.copy(stage = WalletStage.Locked), false))
        assertFalse(canContinueBackground(scanning, true))
        assertFalse(canContinueBackground(scanning.copy(hasVault = false), false))
        assertFalse(canContinueBackground(scanning.copy(stage = WalletStage.Recovery), false))
        assertFalse(canContinueBackground(scanning.copy(stage = WalletStage.Backup), false))
        assertFalse(canContinueBackground(scanning.copy(busy = true), false))
        for (online in listOf(scanning.online.copy(backgroundAllowed = false), scanning.online.copy(backgroundService = false),
            scanning.online.copy(networkEnabled = false), scanning.online.copy(error = "failed"),
            scanning.online.copy(syncing = false))) assertFalse(canContinueBackground(scanning.copy(online = online), false))
    }
    @Test fun completionRequiresAVerifiedLiveRange() {
        val status = app.independentwallet.bindings.WalletSyncStatus(hasScan = true, startHeight = 10uL,
            nextHeight = 20uL, targetHeight = 20uL, synced = true, viewOnly = true,
            receivedXmr = "0", balanceXmr = null, unlockedXmr = null, history = emptyList(), pending = null, payments = emptyList(), nextUnlockBlocks = null)
        val complete = scanning.online.copy(syncing = false, busy = false, status = status)
        assertTrue(backgroundScanFinished(complete))
        assertTrue(canContinueBackground(scanning.copy(online = complete), false))
        assertTrue(canPollNetwork(scanning.copy(online = complete), false))
        val locked = scanning.copy(stage = WalletStage.Locked, online = complete.copy(backgroundActive = true))
        assertTrue(canContinueBackground(locked, false)); assertTrue(canPollNetwork(locked, false))
        assertFalse(canPollNetwork(locked, true))
        assertFalse(canPollNetwork(locked.copy(online = locked.online.copy(backgroundService = false)), false))
        assertFalse(canPollNetwork(locked.copy(online = locked.online.copy(backgroundAllowed = false)), false))
        assertFalse(canPollNetwork(locked.copy(online = locked.online.copy(pauseRequested = true)), false))
        assertFalse(backgroundScanFinished(complete.copy(connected = false)))
        assertFalse(backgroundScanFinished(complete.copy(busy = true)))
        assertFalse(backgroundScanFinished(complete.copy(status = status.copy(targetHeight = 0uL))))
        assertFalse(backgroundScanFinished(complete.copy(status = status.copy(nextHeight = 19uL))))
        assertFalse(backgroundScanFinished(complete.copy(error = "failed")))
    }
    @Test fun connectionStartupCanContinueButIdleAndPaymentsCannot() {
        assertTrue(canContinueBackground(scanning.copy(online = scanning.online.copy(connected = false, syncing = false)), false))
        assertFalse(canContinueBackground(scanning.copy(online = scanning.online.copy(syncing = false, busy = false)), false))
        assertFalse(backgroundScanFinished(scanning.online))
        assertFalse(canContinueBackground(scanning.copy(online = scanning.online.copy(preparingPayment = true)), false))
    }
}
