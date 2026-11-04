package app.independentwallet.android

import app.independentwallet.bindings.*
import org.junit.Assert.*
import org.junit.Test

class BalancePresentationTest {
    private val status = WalletSyncStatus(hasScan = true, startHeight = 100uL, nextHeight = 111uL,
        targetHeight = 111uL, synced = true, viewOnly = false, receivedXmr = "1", balanceXmr = "1",
        unlockedXmr = "0", nextUnlockBlocks = 9uL, history = emptyList(), pending = null, payments = emptyList())
    private val live = OnlineUiState(networkEnabled = true, connected = true, status = status)

    @Test fun receivedFundsExplainRemainingBlocksWithoutPretendingToBeSpendable() {
        assertTrue(balanceExplanation(live)!!.contains("9 blocks"))
        assertTrue(balanceExplanation(live.copy(status = status.copy(nextUnlockBlocks = 1uL)))!!.contains("1 block."))
        assertNull(balanceExplanation(live.copy(status = status.copy(nextUnlockBlocks = null, unlockedXmr = "1"))))
    }
    @Test fun disconnectedAndStaleStatesRequestFreshConfirmationData() {
        assertTrue(balanceExplanation(live.copy(connected = false))!!.startsWith("Saved balance"))
        assertTrue(balanceExplanation(live.copy(networkEnabled = false))!!.startsWith("Saved balance"))
        assertTrue(balanceExplanation(live.copy(status = status.copy(synced = false)))!!.startsWith("Sync to"))
        assertTrue(balanceExplanation(live.copy(error = "failed"))!!.startsWith("Sync to"))
        assertTrue(balanceExplanation(live.copy(syncing = true))!!.startsWith("Sync to"))
    }
    @Test fun additionalLocksRemainVisibleWhenNoHeightCanUnlockThem() {
        assertTrue(balanceExplanation(live.copy(status = status.copy(nextUnlockBlocks = null)))!!.contains("additional lock"))
    }
    @Test fun pendingInputsRemainReserved() {
        val pending = PendingTransfer("fixture-transaction", "fixture-destination", "1", "0.001")
        val reserved = status.copy(balanceXmr = "0", unlockedXmr = "0", nextUnlockBlocks = null, pending = pending)
        assertTrue(balanceExplanation(live.copy(status = reserved))!!.contains("remain reserved"))
    }
    @Test fun viewingAuthorityDoesNotClaimSpendableFunds() {
        assertTrue(balanceExplanation(live.copy(status = status.copy(viewOnly = true, balanceXmr = null, unlockedXmr = null)))!!.contains("unknown"))
        assertNull(balanceExplanation(OnlineUiState()))
    }
}
