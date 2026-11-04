package app.independentwallet.android

import app.independentwallet.bindings.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SyncRangeBridgeTest {
    @Test fun switchingDirectToTorKeepsTheNativeScanCheckpointAndAuthority() {
        val directory = Files.createTempDirectory("sync-range-fixture")
        val wallet = OfflineWalletSession.importSpendKey(KeyNetwork.STAGENET, "01" + "00".repeat(31), 100uL)
        try {
            val first = wallet.openOnline("http://node.example:38081", directory.toString(), WalletNetworkRoute.DIRECT)
            try {
                assertEquals(WalletNetworkRoute.DIRECT, first.networkRoute())
                assertEquals(100uL, first.status().startHeight)
                first.rescan(40uL)
                assertEquals(40uL, first.status().startHeight)
            } finally { first.disconnect(); first.destroy() }
            val resumed = wallet.openOnline("http://" + "a".repeat(56) + ".onion:38081", directory.toString(), WalletNetworkRoute.TOR)
            try {
                assertEquals(100uL, wallet.details().restoreHeight)
                assertEquals(40uL, resumed.status().startHeight)
                assertEquals(40uL, resumed.status().nextHeight)
                assertFalse(resumed.status().synced)
                val range = resumed.status().copy(nextHeight = 120uL, targetHeight = 200uL)
                val view = syncPresentation(OnlineUiState(networkEnabled = true, connected = true, syncing = true, status = range))
                assertEquals(50, view.percent) // (120 - saved 40) / (200 - saved 40)
            } finally { resumed.disconnect(); resumed.destroy() }
        } finally {
            wallet.lock(); wallet.destroy()
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }
}
