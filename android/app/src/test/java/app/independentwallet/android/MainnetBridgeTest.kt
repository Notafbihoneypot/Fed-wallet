package app.independentwallet.android

import app.independentwallet.bindings.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** Disposable public fixtures only. No live node or real funds. */
class MainnetBridgeTest {
    @Test fun verifiedBackupCopyKeepsOriginalAndCreatesAnIndependentMainnetOwner() {
        val dir = Files.createTempDirectory("mainnet-copy-bridge").toFile()
        OfflineWalletSession.importSpendKey(KeyNetwork.STAGENET, "01" + "00".repeat(31), 9uL).use { original ->
            val before = original.snapshotForVault()
            try {
                original.copyToMainnet(37uL).use { mainnet ->
                    assertEquals(KeyNetwork.STAGENET, original.details().network)
                    assertEquals(9uL, original.details().restoreHeight)
                    assertEquals(KeyNetwork.MAINNET, mainnet.details().network)
                    assertEquals(KeyWalletMode.SOFTWARE, mainnet.details().mode)
                    assertTrue(mainnet.details().backupVerified)
                    assertTrue(allowedPreviewWallet(mainnet.details()))
                    assertNotEquals(original.details().address, mainnet.details().address)
                    val after = original.snapshotForVault()
                    try { assertArrayEquals(before, after) } finally { after.fill(0) }
                    original.openOnline("http://selected.invalid:18089/", dir.absolutePath, WalletNetworkRoute.DIRECT).use { testOnline ->
                        mainnet.openOnline("http://selected.invalid:18089/", dir.absolutePath, WalletNetworkRoute.DIRECT).use { mainOnline ->
                            assertFalse(mainOnline.status().viewOnly)
                            assertEquals(37uL, mainOnline.status().startHeight)
                            assertThrows(OnlineException.Payment::class.java) { mainOnline.prepare(original.details().address, "1") }
                            assertThrows(OnlineException.Payment::class.java) { testOnline.prepare(mainnet.details().address, "1") }
                            original.lock()
                            assertThrows(OnlineException::class.java) { testOnline.status() }
                            assertFalse(mainOnline.status().viewOnly)
                            mainOnline.disconnect()
                        }
                    }
                    val snapshot = mainnet.snapshotForVault()
                    try { OfflineWalletSession.openSnapshot(snapshot).use { restored ->
                        assertEquals(KeyNetwork.MAINNET, restored.details().network)
                        assertEquals(mainnet.details().address, restored.details().address)
                    } } finally { snapshot.fill(0) }
                }
            } finally { before.fill(0); dir.deleteRecursively() }
        }
    }

    @Test fun everySupportedSeedKeepsTheSameBackupThroughMainnetCopy() {
        for (format in RecoveryFormat.entries) {
            OfflineWalletSession.generate(KeyNetwork.STAGENET, format).use { original ->
                assertThrows(WalletException.BackupRequired::class.java) { original.copyToMainnet(0uL) }
                val words = original.recoveryPhrase().split(' ')
                original.confirmBackup(original.backupChallenge().map { words[it.toInt()] })
                original.copyToMainnet(0uL).use { mainnet ->
                    assertTrue(mainnet.recoveryPhrase() == original.recoveryPhrase())
                    OfflineWalletSession.restoreSeed(KeyNetwork.MAINNET, format, words.joinToString(" "), 0uL).use { recovered ->
                        assertEquals(mainnet.details().address, recovered.details().address)
                    }
                    assertThrows(WalletException::class.java) { mainnet.copyToMainnet(0uL) }
                }
                original.lock()
                assertThrows(WalletException::class.java) { original.copyToMainnet(0uL) }
            }
        }
    }

    @Test fun freshMainnetSoftwareWalletsUseTheNormalBackupAndSnapshotFlow() {
        for (format in RecoveryFormat.entries) OfflineWalletSession.generate(KeyNetwork.MAINNET, format).use { wallet ->
            assertEquals(KeyNetwork.MAINNET, wallet.details().network)
            assertTrue(allowedPreviewWallet(wallet.details()))
            assertThrows(WalletException.BackupRequired::class.java) { wallet.snapshotForVault() }
            val words = wallet.recoveryPhrase().split(' ')
            wallet.confirmBackup(wallet.backupChallenge().map { words[it.toInt()] })
            val bytes = wallet.snapshotForVault()
            try { OfflineWalletSession.openSnapshot(bytes).use { reopened ->
                assertEquals(wallet.details().address, reopened.details().address)
                assertEquals(KeyWalletMode.SOFTWARE, reopened.details().mode)
            } } finally { bytes.fill(0) }
        }
    }

    @Test fun mainnetViewingCannotCreateSoftwareAuthority() {
        val spend = "c0af65c0dd837e666b9d0dfed62745f4df35aed7ea619b2798a709f0fe545403"
        val view = "513ba91c538a5a9069e0094de90e927c0cd147fa10428ce3ac1afd49f63e3b01"
        val dir = Files.createTempDirectory("mainnet-view-bridge").toFile()
        try { OfflineWalletSession.importSpendKey(KeyNetwork.MAINNET, spend, 0uL).use { hot ->
            OfflineWalletSession.importViewOnly(KeyNetwork.MAINNET, hot.details().address, view, 0uL).use { watching ->
                assertTrue(allowedPreviewWallet(watching.details()))
                assertThrows(WalletException::class.java) { watching.copyToMainnet(0uL) }
                watching.openOnline("http://selected.invalid:18089/", dir.absolutePath, WalletNetworkRoute.DIRECT).use { online ->
                    assertNull(online.status().balanceXmr)
                    assertThrows(OnlineException::class.java) { online.prepare(hot.details().address, "1") }
                    assertThrows(OnlineException::class.java) { online.confirm("untrusted-review") }
                    assertThrows(OnlineException::class.java) { online.retryPending("untrusted-id") }
                }
            }
        } } finally { dir.deleteRecursively() }
    }

    @Test fun originalStorageNamesStayCompatibleAndNewSlotsHaveSeparateKeysAndFiles() {
        assertEquals("wallet-v1.bin", WalletSlot.Original.vaultFile)
        assertEquals("setup-v1.bin", WalletSlot.Original.setupFile)
        assertEquals("independent-wallet-authenticated-vault-v1", WalletSlot.Original.vaultAlias)
        assertEquals("independent-wallet-setup-wrapping-v1", WalletSlot.Original.setupAlias)
        val names = WalletSlot.entries.flatMap { listOf(it.vaultFile, it.setupFile, it.vaultAlias, it.setupAlias) }
        assertEquals(names.size, names.distinct().size)
        assertEquals(KeyNetwork.MAINNET, OfflineUiState().creationNetwork)
        assertEquals("NET_NETWORK", nodeFailureCode("Wallet and node network mismatch. [NET_NETWORK]"))
        OfflineWalletSession.importSpendKey(KeyNetwork.TESTNET, "01" + "00".repeat(31), 0uL).use { test ->
            assertFalse(allowedPreviewWallet(test.details()))
        }
    }
}
