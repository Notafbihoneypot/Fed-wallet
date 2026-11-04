package app.independentwallet.android

import app.independentwallet.bindings.*
import org.junit.Assert.*
import org.junit.Test

class PreparationProgressTest {
    @Test fun progressIsLocalAndOwnerLockRevokesIt() {
        val directory = java.nio.file.Files.createTempDirectory("preparation-progress-fixture").toFile()
        val owner = OfflineWalletSession.importSpendKey(KeyNetwork.MAINNET, "01" + "00".repeat(31), 0uL)
        try {
            owner.openOnline("https://node.invalid:18081/", directory.absolutePath, WalletNetworkRoute.TOR).use { child ->
                repeat(100) {
                    val status = child.preparationStatus()
                    assertEquals(PreparationStage.IDLE, status.stage)
                    assertEquals(0u, status.cachedBytes)
                }
                assertFalse(directory.resolve("tor-state").exists())
                assertFalse(directory.resolve("tor-cache").exists())
                owner.lock()
                assertThrows(OnlineException.Closed::class.java) { child.preparationStatus() }
            }
        } finally { owner.lock(); owner.destroy(); directory.deleteRecursively() }
    }
}
