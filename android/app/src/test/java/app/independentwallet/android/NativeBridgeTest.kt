package app.independentwallet.android

import app.independentwallet.bindings.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real host JNA/UniFFI integration. These public fixture keys must never hold funds.
 * This does not exercise the Android loader, UI or Keystore. */
class NativeBridgeTest {
    @Test fun closingTheOfflineOwnerRevokesItsOnlineChildWithoutConnecting() {
        val directory = java.nio.file.Files.createTempDirectory("wallet-online-fixture").toFile()
        val hot = OfflineWalletSession.importSpendKey(KeyNetwork.STAGENET, "01" + "00".repeat(31), 0uL)
        var online: OnlineWalletSession? = null
        try {
            val active = hot.openOnline("https://node.invalid:38081/", directory.absolutePath, WalletNetworkRoute.DIRECT)
            online = active
            assertFalse(active.status().synced)
            assertNotEquals(hot.details().address, active.receiveAddress(1u))
            hot.lock()
            assertThrows(OnlineException::class.java) { active.status() }
        } finally { online?.disconnect(); online?.destroy(); hot.lock(); hot.destroy(); directory.deleteRecursively() }
    }

    @Test fun bridgeRejectsClearnetHttpOverTorAndCannotPromoteViewOnlyToSigner() {
        val directory = java.nio.file.Files.createTempDirectory("wallet-view-fixture").toFile()
        val hot = OfflineWalletSession.importSpendKey(KeyNetwork.STAGENET, spend, 0uL)
        val watching = OfflineWalletSession.importViewOnly(KeyNetwork.STAGENET, hot.details().address, view, 0uL)
        var online: OnlineWalletSession? = null
        try {
            assertThrows(OnlineException::class.java) { watching.openOnline("http://node.invalid:38081/", directory.absolutePath, WalletNetworkRoute.TOR) }
            val active = watching.openOnline("https://node.invalid:38081/", directory.absolutePath, WalletNetworkRoute.DIRECT)
            online = active
            assertTrue(active.status().viewOnly)
            assertNull(active.status().balanceXmr)
            assertThrows(OnlineException::class.java) { active.prepare(hot.details().address, "1") }
        } finally { online?.disconnect(); online?.destroy(); watching.lock(); watching.destroy(); hot.lock(); hot.destroy(); directory.deleteRecursively() }
    }
    @Test fun nativeRouteValidationCannotSendOnionTrafficDirectly() {
        val directory = java.nio.file.Files.createTempDirectory("route-authority-fixture").toFile()
        val wallet = OfflineWalletSession.importSpendKey(KeyNetwork.STAGENET, "01" + "00".repeat(31), 0uL)
        try {
            assertThrows(OnlineException::class.java) {
                wallet.openOnline("http://" + "a".repeat(56) + ".onion:38081/", directory.absolutePath, WalletNetworkRoute.DIRECT)
            }
            wallet.openOnline("http://node.invalid:38081/", directory.absolutePath, WalletNetworkRoute.DIRECT).use { direct ->
                assertEquals(WalletNetworkRoute.DIRECT, direct.networkRoute())
                assertFalse(direct.status().synced)
                direct.disconnect()
            }
        } finally { wallet.lock(); wallet.destroy(); directory.deleteRecursively() }
    }

    @Test fun routeSwitchRequiresClosingThePreviousNativeChild() {
        val directory = java.nio.file.Files.createTempDirectory("route-switch-fixture").toFile()
        val wallet = OfflineWalletSession.importSpendKey(KeyNetwork.STAGENET, "01" + "00".repeat(31), 0uL)
        try {
            wallet.openOnline("https://node.invalid:38081/", directory.absolutePath, WalletNetworkRoute.DIRECT).use { direct ->
                assertThrows(OnlineException::class.java) {
                    wallet.openOnline("https://node.invalid:38081/", directory.absolutePath, WalletNetworkRoute.TOR)
                }
                direct.disconnect()
            }
            wallet.openOnline("https://node.invalid:38081/", directory.absolutePath, WalletNetworkRoute.TOR).use { tor ->
                assertEquals(WalletNetworkRoute.TOR, tor.networkRoute())
                val error = assertThrows(OnlineException.Failed::class.java) { tor.connectDirectResolved(listOf("127.0.0.1")) }
                assertEquals("NET_ADDRESS", nodeFailureCode(error.detail))
                assertFalse(tor.status().synced)
                tor.disconnect()
            }
        } finally { wallet.lock(); wallet.destroy(); directory.deleteRecursively() }
    }
    private val spend = "c0af65c0dd837e666b9d0dfed62745f4df35aed7ea619b2798a709f0fe545403"
    private val view = "513ba91c538a5a9069e0094de90e927c0cd147fa10428ce3ac1afd49f63e3b01"

    @Test fun generatedBackupAndLockCrossTheActualBridge() {
        val session = OfflineWalletSession.generate(KeyNetwork.STAGENET, RecoveryFormat.POLYSEED16)
        try {
            assertFalse(session.details().backupVerified)
            assertThrows(WalletException.BackupRequired::class.java) { session.snapshotForVault() }
            val phrase = session.recoveryPhrase().split(' ')
            val positions = session.backupChallenge()
            assertEquals(3, positions.distinct().size)
            session.confirmBackup(positions.map { phrase[it.toInt()] })
            val bytes = session.snapshotForVault()
            try {
                OfflineWalletSession.openSnapshot(bytes).use { reopened ->
                    assertEquals(session.details().address, reopened.details().address)
                    reopened.lock()
                }
            } finally { bytes.fill(0) }
            session.lock()
            assertThrows(WalletException.Locked::class.java) { session.recoveryPhrase() }
            assertThrows(WalletException.Locked::class.java) { session.snapshotForVault() }
        } finally { session.lock(); session.destroy() }
    }

    @Test fun viewOnlyAuthoritySurvivesBridgeStorageRoundtrip() {
        val hot = OfflineWalletSession.importSpendKey(KeyNetwork.STAGENET, spend, 0uL)
        try {
            val watching = OfflineWalletSession.importViewOnly(KeyNetwork.STAGENET, hot.details().address, view, 17uL)
            try {
                val bytes = watching.snapshotForVault()
                try {
                    OfflineWalletSession.openSnapshot(bytes).use { restored ->
                        assertEquals(KeyWalletMode.VIEW_ONLY, restored.details().mode)
                        assertEquals(17uL, restored.details().restoreHeight)
                        assertThrows(WalletException::class.java) { restored.recoveryPhrase() }
                        restored.lock()
                    }
                } finally { bytes.fill(0) }
            } finally { watching.lock(); watching.destroy() }
        } finally { hot.lock(); hot.destroy() }
    }

    @Test fun invalidBuffersAndUnicodeProduceTypedErrorsWithoutInputEchoes() {
        for (size in 0..256) {
            val bytes = ByteArray(size) { ((it * 31 + size) and 255).toByte() }
            try { assertThrows(WalletException::class.java) { OfflineWalletSession.openSnapshot(bytes) } }
            finally { bytes.fill(0) }
        }
        for (input in listOf("\u0000", "\u202ePRIVATE_FIXTURE", "\uD83D\uDD12", "x".repeat(4097))) {
            val error = assertThrows(WalletException::class.java) {
                OfflineWalletSession.restoreSeed(KeyNetwork.STAGENET, RecoveryFormat.POLYSEED16, input, 0uL)
            }
            assertFalse(error.message.orEmpty().contains(input))
        }
    }

    @Test fun concurrentCallsCannotReopenALockedNativeSession() {
        val session = OfflineWalletSession.importSpendKey(KeyNetwork.STAGENET, spend, 0uL)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0..3).map { worker -> Callable {
                repeat(50) {
                    if (worker == 0) session.lock()
                    else try { session.details() } catch (_: WalletException.Locked) { }
                }
            } }
            pool.invokeAll(tasks, 10, TimeUnit.SECONDS).forEach { it.get() }
            assertThrows(WalletException.Locked::class.java) { session.details() }
        } finally { pool.shutdownNow(); session.lock(); session.destroy() }
    }
}
