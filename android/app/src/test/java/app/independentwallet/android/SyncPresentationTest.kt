package app.independentwallet.android

import app.independentwallet.bindings.WalletSyncStatus
import org.junit.Assert.*
import org.junit.Test

class SyncPresentationTest {
    private fun status(next: ULong, target: ULong, synced: Boolean = false, start: ULong = 100uL) = WalletSyncStatus(
        hasScan = true, startHeight = start, nextHeight = next, targetHeight = target, synced = synced, viewOnly = true,
        receivedXmr = "0", balanceXmr = null, unlockedXmr = null, history = emptyList(), pending = null, payments = emptyList(), nextUnlockBlocks = null)
    private fun live(next: ULong, target: ULong, synced: Boolean = false) = OnlineUiState(
        networkEnabled = true, connected = true, syncing = !synced, status = status(next, target, synced))

    @Test fun connectionAnimationDoesNotInventAHeightOrPercentage() {
        val view = syncPresentation(OnlineUiState(networkEnabled = true, busy = true))
        assertEquals(SyncPhase.Connecting, view.phase); assertNull(view.percent); assertNull(view.fraction)
    }
    @Test fun scanningCountsOnlyTheRangeSinceTheRestoreHeight() {
        val view = syncPresentation(live(150uL, 200uL))
        assertEquals(SyncPhase.Scanning, view.phase); assertEquals(50, view.percent); assertEquals(50uL, view.remaining)
        assertEquals(0.5f, view.fraction!!, 0.0001f)
    }
    @Test fun reachingTheReportedTipAloneCannotShowOneHundredPercent() {
        assertEquals(SyncPhase.Checking, syncPresentation(live(200uL, 200uL)).phase)
        val almost = syncPresentation(live(9999uL, 10000uL))
        assertEquals(99, almost.percent)
        assertEquals(SyncPhase.Synced, syncPresentation(live(200uL, 200uL, true)).phase)
        assertEquals(100, syncPresentation(live(200uL, 200uL, true)).percent)
    }
    @Test fun cachedCompletionErrorsAndFreshTipChecksCannotPretendToBeSynced() {
        val complete = live(200uL, 200uL, true)
        assertEquals(SyncPhase.Paused, syncPresentation(complete.copy(networkEnabled = false)).phase)
        assertEquals(SyncPhase.Paused, syncPresentation(complete.copy(error = "public fixture error")).phase)
        assertEquals(SyncPhase.Connecting, syncPresentation(complete.copy(connected = false)).phase)
        assertEquals(SyncPhase.Checking, syncPresentation(complete.copy(syncing = true)).phase)
        val polling = complete.copy(polling = true, busy = true)
        assertEquals(SyncPhase.Checking, syncPresentation(polling).phase); assertNull(syncPresentation(polling).percent)
        assertNotNull(balanceExplanation(polling))
        assertEquals(SyncPhase.Paused, syncPresentation(live(20uL, 200uL).copy(syncing = false)).phase)
    }
    @Test fun unknownAndInconsistentHeightsDoNotProduceFakeCompletion() {
        assertEquals(SyncPhase.Checking, syncPresentation(live(0uL, 0uL)).phase)
        assertEquals(SyncPhase.Paused, syncPresentation(live(0uL, 0uL, true)).phase)
        assertEquals(SyncPhase.Checking, syncPresentation(live(10uL, 20uL)).phase)
        assertEquals(SyncPhase.Paused, syncPresentation(live(100uL, 20uL, true)).phase)
        assertEquals(0, syncPresentation(live(10uL, 200uL)).percent)
        assertEquals(SyncPhase.Paused, syncPresentation(live(10uL, 200uL, true)).phase)
    }
    @Test fun maximumUnsignedHeightsRemainBoundedWithoutOverflow() {
        val view = syncPresentation(live(ULong.MAX_VALUE - 1uL, ULong.MAX_VALUE))
        assertTrue(view.fraction!!.isFinite() && view.fraction in 0f..0.999f)
        assertTrue(view.percent!! in 0..99); assertEquals(1uL, view.remaining)
    }
    @Test fun reorgOrRescanCanMoveProgressBackwardsWithoutKeepingCompletion() {
        val before = syncPresentation(live(190uL, 200uL))
        val rewound = syncPresentation(live(150uL, 200uL))
        assertEquals(90, before.percent); assertEquals(50, rewound.percent)
        assertEquals(SyncPhase.Scanning, rewound.phase); assertEquals(50uL, rewound.remaining)
        assertTrue(rewound.fraction!! < before.fraction!!)
    }
    @Test fun activityTimesNeverGoNegativeOrSurviveAClockResetAsHugeDurations() {
        assertEquals(0L, elapsedSyncSeconds(10, null)); assertEquals(0L, elapsedSyncSeconds(10, 20))
        assertEquals(0L, elapsedSyncSeconds(10, -1)); assertEquals(15L, elapsedSyncSeconds(16000, 1000))
    }
    @Test fun pixelOrbitHasThirtyTwoDistinctBoundedCellsAndWrapsCleanly() {
        val cells = (0 until 32).map(::syncOrbitCell)
        assertEquals(32, cells.toSet().size)
        assertTrue(cells.all { it.first in 0f..1f && it.second in 0f..1f })
        assertEquals(syncOrbitCell(0), syncOrbitCell(32)); assertEquals(syncOrbitCell(31), syncOrbitCell(-1))
    }
}
