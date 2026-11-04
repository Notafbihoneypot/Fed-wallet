package app.independentwallet.android

import app.independentwallet.bindings.WalletSyncStatus
import org.junit.Assert.*
import org.junit.Test

class SyncNotificationTest {
    private fun online(next: ULong, target: ULong, start: ULong = 0uL, synced: Boolean = false) = OnlineUiState(
        networkEnabled = true, connected = true, syncing = !synced, route = NodeKind.Direct,
        node = "https://private-node.invalid:18081/",
        status = WalletSyncStatus(hasScan = next > start, startHeight = start, nextHeight = next,
            targetHeight = target, synced = synced, viewOnly = false,
            receivedXmr = "123.456789", balanceXmr = "123.456789", unlockedXmr = "123.456789",
            history = emptyList(), pending = null, payments = emptyList(), nextUnlockBlocks = null))

    @Test fun earlyProgressIncludesExactCountersInsteadOfLookingStuckAtZeroPercent() {
        val result = syncNotification(online(32uL, 3_800_000uL))
        assertTrue(result.text.contains("<0.01%"))
        assertTrue(result.text.contains("Block 32 of 3800000"))
        assertTrue(result.detail.contains("3799968 blocks remaining"))
        val later = syncNotification(online(10_000uL, 3_800_000uL))
        assertTrue(later.progress!! > 0)
        assertFalse(later.text.contains("100%"))
        assertEquals(10_000, later.max)
    }

    @Test fun notificationProgressUsesTheSavedScanRangeAndNeverLeaksWalletOrNodeData() {
        val result = syncNotification(online(3_795_000uL, 3_800_000uL, 3_790_000uL))
        assertTrue(result.text.startsWith("50%"))
        assertEquals(5_000, result.progress)
        assertTrue(result.detail.contains("Scan starts at block 3790000"))
        assertFalse(result.toString().contains("private-node"))
        assertFalse(result.toString().contains("123.456789"))
    }

    @Test fun onlyVerifiedCompletionCanShowOneHundredPercent() {
        val checking = syncNotification(online(200uL, 200uL))
        assertNull(checking.progress)
        assertFalse(checking.text.contains("100%"))
        val complete = syncNotification(online(200uL, 200uL, synced = true))
        assertEquals(10_000, complete.progress)
        assertTrue(complete.text.startsWith("100%"))
        for (state in listOf(online(200uL, 200uL, synced = true).copy(networkEnabled = false),
            online(200uL, 200uL, synced = true).copy(error = "fixture"),
            OnlineUiState(networkEnabled = true))) {
            val result = syncNotification(state)
            assertNull(result.progress)
            assertFalse(result.text.contains("100%"))
        }
    }
}
