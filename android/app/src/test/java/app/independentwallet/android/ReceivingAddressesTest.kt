package app.independentwallet.android

import app.independentwallet.bindings.*
import org.junit.Assert.*
import org.junit.Test

/** Actual host UniFFI; no Android device/Keystore execution is claimed. */
class ReceivingAddressesTest {
    @Test fun offlineCreationSelectionAndLabelsPersistThroughTheRealBridge() {
        val directory = java.nio.file.Files.createTempDirectory("receiving-fixture").toFile()
        val spend = "01" + "00".repeat(31)
        val owner = OfflineWalletSession.importSpendKey(KeyNetwork.MAINNET, spend, 0uL)
        var second = ""
        try {
            val initial = owner.receivingAddresses(directory.absolutePath)
            assertEquals(1u, initial.selectedIndex)
            val created = owner.createSubaddress(directory.absolutePath, "Invoices")
            second = created.selectedAddress
            assertEquals(2u, created.selectedIndex)
            assertNotEquals(initial.selectedAddress, second)
            assertEquals("Invoices", created.addresses.last().label)
            assertEquals(owner.details().address, owner.selectSubaddress(directory.absolutePath, 0u).selectedAddress)
            owner.labelSubaddress(directory.absolutePath, 2u, "Customer")
            assertThrows(OnlineException::class.java) { owner.createSubaddress(directory.absolutePath, "bad\nlabel") }
            owner.lock()
            assertThrows(OnlineException::class.java) { owner.receivingAddresses(directory.absolutePath) }
        } finally { owner.lock(); owner.destroy() }
        val restored = OfflineWalletSession.importSpendKey(KeyNetwork.MAINNET, spend, 0uL)
        try {
            val selected = restored.selectSubaddress(directory.absolutePath, 2u)
            assertEquals(second, selected.selectedAddress)
            assertEquals("Customer", selected.addresses.last().label)
        } finally { restored.lock(); restored.destroy(); directory.deleteRecursively() }
    }

    @Test fun liveChildSharesOneJournalAndOwnerLockRevokesAddressWork() {
        val directory = java.nio.file.Files.createTempDirectory("receiving-live-fixture").toFile()
        val owner = OfflineWalletSession.importSpendKey(KeyNetwork.MAINNET, "01" + "00".repeat(31), 0uL)
        try {
            owner.openOnline("https://node.invalid:18081/", directory.absolutePath, WalletNetworkRoute.DIRECT).use { child ->
                assertFalse(child.status().synced)
                val created = owner.createSubaddress(directory.absolutePath, "Shared")
                assertEquals(created.selectedAddress, child.receivingAddresses().selectedAddress)
                assertEquals(created.selectedAddress, child.receiveAddress(2u))
                owner.lock()
                assertThrows(OnlineException::class.java) { child.receivingAddresses() }
            }
        } finally { owner.lock(); owner.destroy(); directory.deleteRecursively() }
    }

    @Test fun unfinishedSetupCannotCreateAddressesOrOpenACache() {
        val directory = java.nio.file.Files.createTempDirectory("receiving-backup-fixture").toFile()
        val owner = OfflineWalletSession.generate(KeyNetwork.MAINNET, RecoveryFormat.POLYSEED16)
        try {
            assertThrows(OnlineException::class.java) { owner.receivingAddresses(directory.absolutePath) }
            assertThrows(OnlineException::class.java) { owner.createSubaddress(directory.absolutePath, "No") }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { owner.lock(); owner.destroy(); directory.deleteRecursively() }
    }
}
