package app.independentwallet.android

import app.independentwallet.bindings.WalletSyncStatus
import org.junit.Assert.*
import org.junit.Test

class SyncStartTest {
    private fun status(start: ULong = 0uL, scanned: Boolean = false) = WalletSyncStatus(hasScan = scanned,
        startHeight = start, nextHeight = start, targetHeight = 3_800_000uL, synced = false, viewOnly = false,
        receivedXmr = "0", balanceXmr = "0", unlockedXmr = "0", history = emptyList(), pending = null, payments = emptyList(), nextUnlockBlocks = null)

    @Test fun newZeroHeightWalletAsksWhereToStartBeforeScanning() {
        assertTrue(needsScanStartChoice(status()))
        val view = syncPresentation(OnlineUiState(networkEnabled = true, connected = true, status = status()))
        assertEquals(SyncPhase.Ready, view.phase)
        assertNull(view.percent)
        assertFalse(needsScanStartChoice(status(scanned = true)))
        assertFalse(needsScanStartChoice(status(start = 3_700_000uL)))
        assertFalse(needsScanStartChoice(status().copy(targetHeight = 0uL)))
    }

    @Test fun recentShortcutHasABoundedBufferAndCannotUnderflow() {
        assertEquals(3_798_560uL, recentScanHeight(3_800_000uL))
        assertEquals(0uL, recentScanHeight(1_440uL))
        assertEquals(0uL, recentScanHeight(1uL))
    }

    @Test fun movingForwardRequiresAnExplicitConfirmationAndHeightCannotExceedTheTip() {
        assertNull(validScanStart("3798560", status(), false))
        assertEquals(3_798_560uL, validScanStart("3798560", status(), true))
        assertNull(validScanStart("3800001", status(), true))
        assertNull(validScanStart("4294967296", status(), true))
        assertNull(validScanStart("-1", status(), true))
        assertNull(validScanStart("", status(), true))
        assertEquals(0uL, validScanStart("0", status(start = 100uL), false))
        assertEquals(100uL, validScanStart("100", status(start = 100uL), false))
    }
}
