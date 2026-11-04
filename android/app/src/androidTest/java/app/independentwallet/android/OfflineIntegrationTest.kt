package app.independentwallet.android

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import app.independentwallet.bindings.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/** Dedicated, disposable emulator only. These are public test fixtures. */
@RunWith(AndroidJUnit4::class)
class OfflineIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val legacy = "washing thirsty occur lectures tuesday fainted toxic adapt abnormal memoir nylon mostly building shrugged online ember northern ruby woes dauntless boil family illness inroads northern"
    private val testPin = "246810"

    @Before fun clearFixtureVault() {
        check(context.packageName == "app.independentwallet.android.syncpreview2")
        check(device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1") {
            "Fixture tests require a disposable emulator"
        }
        instrumentation.runOnMainSync {
            val app = context.applicationContext as WalletApplication
            app.wallet.lock(); app.viewModelStore.clear()
        }
        context.getSharedPreferences("wallet-selection-v1", Context.MODE_PRIVATE).edit().clear().commit()
        for (name in listOf("wallet-v1.bin", "setup-v1.bin", "wallet-mainnet-v1.bin", "setup-mainnet-v1.bin", "node-v1.bin")) for (suffix in listOf("", ".bak", ".new")) File(context.noBackupFilesDir, "$name$suffix").delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("independent-wallet-authenticated-vault-v1")
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("independent-wallet-setup-wrapping-v1")
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("independent-wallet-node-v1")
        for (slot in WalletSlot.entries) {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(slot.vaultAlias)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(slot.setupAlias)
        }
    }

    private fun waitFor(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue("Expected wallet state did not arrive", predicate())
    }

    private fun model(scenario: ActivityScenario<MainActivity>): OfflineWalletViewModel {
        lateinit var vm: OfflineWalletViewModel
        scenario.onActivity { vm = it.offlineVm }
        waitFor { vm.state.value.stage != WalletStage.Loading }
        return vm
    }


    private fun savedStagenetFixture(scenario: ActivityScenario<MainActivity>): OfflineWalletViewModel {
        val vm = model(scenario)
        scenario.onActivity {
            vm.selectCreationNetwork(KeyNetwork.STAGENET)
            vm.importSpendKey("01" + "00".repeat(31), 0uL)
        }
        enterCredential()
        waitFor { vm.state.value.stage == WalletStage.Open && !vm.state.value.busy }
        scenario.onActivity { it.authenticate(VaultAction.Save) }
        enterCredential()
        waitFor { vm.state.value.hasVault && !vm.state.value.busy }
        return vm
    }

    @Test fun mainnetCopyKeepsTheOriginalEncryptedWalletAndRestoresBothSlots() {
        assumeTrue(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = savedStagenetFixture(scenario)
            val original = File(context.noBackupFilesDir, WalletSlot.Original.vaultFile).readBytes()
            val address = vm.state.value.info!!.address
            scenario.onActivity {
                assertTrue(vm.prepareMainnetCopy(0uL))
                it.authenticate(VaultAction.MainnetCopy)
            }
            enterCredential()
            waitFor { vm.state.value.walletSlot == WalletSlot.Mainnet && vm.state.value.stage == WalletStage.Locked && !vm.state.value.busy }
            assertArrayEquals(original, File(context.noBackupFilesDir, WalletSlot.Original.vaultFile).readBytes())
            assertTrue(File(context.noBackupFilesDir, WalletSlot.Mainnet.vaultFile).exists())
            assertFalse(vm.state.value.online.networkEnabled)
            scenario.onActivity { it.authenticate(VaultAction.Unlock) }
            enterCredential()
            waitFor { vm.state.value.stage == WalletStage.Open && !vm.state.value.busy }
            assertEquals(KeyNetwork.MAINNET, vm.state.value.info!!.network)
            assertNotEquals(address, vm.state.value.info!!.address)
            scenario.recreate()
            assertEquals(WalletSlot.Mainnet, model(scenario).state.value.walletSlot)
            scenario.onActivity { vm.selectWalletSlot(WalletSlot.Original) }
            waitFor { vm.state.value.walletSlot == WalletSlot.Original && !vm.state.value.busy }
            scenario.onActivity { it.authenticate(VaultAction.Unlock) }
            enterCredential()
            waitFor { vm.state.value.stage == WalletStage.Open && !vm.state.value.busy }
            assertEquals(KeyNetwork.STAGENET, vm.state.value.info!!.network)
            assertEquals(address, vm.state.value.info!!.address)
            assertArrayEquals(original, File(context.noBackupFilesDir, WalletSlot.Original.vaultFile).readBytes())
            original.fill(0)
        }
    }

    @Test fun cancelledMainnetCopyAuthenticationKeepsSourceAndCreatesNoWallet() {
        assumeTrue(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = savedStagenetFixture(scenario)
            val original = File(context.noBackupFilesDir, WalletSlot.Original.vaultFile).readBytes()
            scenario.onActivity {
                assertTrue(vm.prepareMainnetCopy(0uL))
                it.authenticate(VaultAction.MainnetCopy)
            }
            waitFor { device.currentPackageName != context.packageName }
            cancelCredential()
            waitFor { !vm.state.value.busy }
            assertEquals(WalletSlot.Original, vm.state.value.walletSlot)
            assertEquals(KeyNetwork.STAGENET, vm.state.value.info!!.network)
            assertFalse(File(context.noBackupFilesDir, WalletSlot.Mainnet.vaultFile).exists())
            assertArrayEquals(original, File(context.noBackupFilesDir, WalletSlot.Original.vaultFile).readBytes())
            original.fill(0)
        }
    }

    @Test fun activityRecreationRetainsTheApplicationWalletWithoutReconnecting() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val original = model(scenario)
            scenario.recreate()
            assertSame(original, model(scenario))
            assertFalse(original.state.value.online.networkEnabled)
            assertFalse(original.state.value.online.backgroundAllowed)
        }
    }

    private fun enterCredential() {
        val entry = requireNotNull(device.wait(Until.findObject(By.clazz(java.util.regex.Pattern.compile(".*EditText"))), 30_000)) {
            "Android credential entry was not shown"
        }
        entry.click()
        device.executeShellCommand("input text $testPin")
        device.pressEnter()
    }

    private fun cancelCredential() {
        repeat(3) {
            if (device.currentPackageName == context.packageName) return
            device.pressBack() // the first back may only hide the keyboard
            device.waitForIdle()
        }
        assertEquals(context.packageName, device.currentPackageName)
    }

    @Test fun nativeSeedBackupAndBackgroundLock() {
        assumeTrue(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = model(scenario)
            scenario.onActivity { vm.generate(RecoveryFormat.LEGACY25) }
            enterCredential()
            waitFor { vm.state.value.stage == WalletStage.Backup }
            val words = vm.state.value.phrase!!.text().split(' ')
            scenario.recreate()
            assertEquals(WalletStage.Backup, vm.state.value.stage)
            assertNotNull(vm.state.value.phrase)
            scenario.onActivity { vm.checkBackup() }
            waitFor { vm.state.value.stage == WalletStage.Verify && !vm.state.value.busy }
            assertNull(vm.state.value.phrase)
            val answers = vm.state.value.challenge.map { words[it.toInt()] }
            scenario.onActivity { vm.confirmBackup(answers) }
            waitFor { vm.state.value.stage == WalletStage.Open && !vm.state.value.busy }
            assertEquals(WalletStage.Open, vm.state.value.stage)
            assertTrue(vm.state.value.info!!.backupVerified)
            scenario.moveToState(Lifecycle.State.CREATED)
            waitFor { vm.state.value.stage == WalletStage.Locked && !vm.state.value.busy }
            assertTrue(vm.state.value.hasSetup)
            assertNull(vm.state.value.info)
            assertNull(vm.state.value.phrase)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { it.authenticate(VaultAction.Unlock) }
            enterCredential()
            waitFor { vm.state.value.stage == WalletStage.Open && !vm.state.value.busy }
            assertTrue(vm.state.value.info!!.backupVerified)
        }
    }

    @Test fun savedNodeSlotsStayEncryptedAndNetworkRemainsOffUntilTheWalletIsReady() {
        val https = "https://wallet-node.example:38081/"
        val onion = "http://" + "a".repeat(56) + ".onion:38081/"
        val profiles = NodeProfiles(NodeKind.Direct, https, onion)
        NodeStore(context).update { profiles }
        val bytes = File(context.noBackupFilesDir, "node-v1.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains(https) || bytes.contains(onion))
        assertEquals(profiles, NodeStore(context).read())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val wallet = model(scenario)
            lateinit var nodes: WalletViewModel
            scenario.onActivity { nodes = ViewModelProvider(it)[WalletViewModel::class.java] }
            waitFor { !nodes.state.value.loading }
            assertTrue(https == nodes.state.value.savedNode)
            assertFalse(wallet.state.value.online.networkEnabled)
            requireNotNull(device.wait(Until.findObject(By.text("Node")), 10_000)).click()
            requireNotNull(device.wait(Until.findObject(By.desc("Use Tor")), 10_000)).click()
            waitFor { nodes.state.value.nodeKind == NodeKind.Tor && !nodes.state.value.saving }
            assertTrue(onion == nodes.state.value.savedNode)
            assertTrue(https == nodes.state.value.profiles.direct)
            UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("Network access")
            assertFalse(requireNotNull(device.wait(Until.findObject(By.desc("Network access")), 10_000)).isEnabled)
            scenario.onActivity { wallet.connect(onion, NodeKind.Tor) }
            assertFalse(wallet.state.value.online.networkEnabled || wallet.state.value.online.connected || wallet.state.value.online.busy)
        }
    }

    @Test fun staleAuthenticationCannotSaveOrResurrectTheWallet() {
        assumeTrue(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = model(scenario)
            scenario.onActivity { vm.restore(RecoveryFormat.LEGACY25, legacy, 0uL) }
            enterCredential()
            waitFor { vm.state.value.stage == WalletStage.Open }
            scenario.onActivity {
                assertNull(vm.beginAuthentication(VaultAction.RevealSeed))
                val ticket = vm.beginAuthentication(VaultAction.Save)!!
                vm.lock()
                vm.finishAuthentication(VaultAction.Save, ticket, true)
            }
            assertEquals(WalletStage.Locked, vm.state.value.stage)
            assertTrue(vm.state.value.hasSetup)
            assertFalse(vm.state.value.hasVault)
            assertFalse(WalletVault(context).exists())
        }
    }

    @Test fun copiedBackupResumesAfterActivityClosureWithoutCreatingDifferentKeys() {
        assumeTrue(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        var address = ""
        var phrase = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = model(scenario)
            scenario.onActivity { vm.generate(RecoveryFormat.LEGACY25) }
            enterCredential()
            waitFor { vm.state.value.stage == WalletStage.Backup && !vm.state.value.busy }
            address = vm.state.value.info!!.address; phrase = vm.state.value.phrase!!.text()
            scenario.onActivity { vm.copyRecoveryPhrase() }
            scenario.moveToState(Lifecycle.State.CREATED)
            waitFor { vm.state.value.stage == WalletStage.Locked && !vm.state.value.busy }
            assertTrue(vm.state.value.hasSetup)
            assertNull(vm.state.value.phrase)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = model(scenario)
            waitFor { vm.state.value.stage == WalletStage.Locked && !vm.state.value.busy }
            scenario.onActivity { it.authenticate(VaultAction.Unlock) }
            enterCredential()
            waitFor { vm.state.value.stage == WalletStage.Backup && !vm.state.value.busy }
            assertTrue(vm.state.value.info!!.address == address)
            assertTrue(vm.state.value.phrase!!.text() == phrase)
            assertFalse(vm.state.value.info!!.backupVerified)
            scenario.onActivity {
                val manager = context.getSystemService(android.content.ClipboardManager::class.java)
                assertTrue(manager.primaryClip!!.getItemAt(0).text.toString() == phrase)
                vm.clearSeedClipboard()
            }
        }
        phrase = ""
    }

    @Test fun aConflictingVaultNeverLabelsTheUnsavedSessionAsSaved() {
        assumeTrue(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = model(scenario)
            scenario.onActivity { vm.importSpendKey("c0af65c0dd837e666b9d0dfed62745f4df35aed7ea619b2798a709f0fe545403", 0uL) }
            enterCredential()
            waitFor { vm.state.value.stage == WalletStage.Open }
            val file = File(context.noBackupFilesDir, "wallet-v1.bin")
            // A pre-existing corrupt file must also be kept rather than overwritten.
            val prior = byteArrayOf(1, 2, 3, 4)
            file.writeBytes(prior)
            scenario.onActivity {
                val ticket = vm.beginAuthentication(VaultAction.Save)!!
                vm.finishAuthentication(VaultAction.Save, ticket, true)
            }
            waitFor { !vm.state.value.busy }
            assertEquals(WalletStage.Locked, vm.state.value.stage)
            assertNull(vm.state.value.info)
            assertTrue(vm.state.value.hasVault)
            assertNotNull(vm.state.value.error)
            assertArrayEquals(prior, file.readBytes())
        }
    }

    @Test fun discardCanBeCancelledAndConfirmedBeforeStartingANewWallet() {
        assumeTrue(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = model(scenario)
            scenario.onActivity { vm.generate(RecoveryFormat.LEGACY25) }
            enterCredential()
            waitFor { vm.state.value.stage == WalletStage.Backup && !vm.state.value.busy }
            val address = vm.state.value.info!!.address
            val file = File(context.noBackupFilesDir, "setup-v1.bin")
            UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("Discard setup")
            requireNotNull(device.wait(Until.findObject(By.text("Discard setup")), 10_000)).click()
            requireNotNull(device.wait(Until.findObject(By.text("Keep setup")), 10_000)).click()
            assertEquals(WalletStage.Backup, vm.state.value.stage)
            assertTrue(file.exists())
            scenario.onActivity { vm.requestDiscardSetup() }
            waitFor { vm.isAuthenticationPending() }
            requireNotNull(device.wait(Until.findObject(By.clazz(java.util.regex.Pattern.compile(".*EditText"))), 30_000))
            cancelCredential()
            waitFor { !vm.state.value.busy }
            assertEquals(WalletStage.Locked, vm.state.value.stage)
            assertTrue(vm.state.value.hasSetup && file.exists())
            scenario.onActivity { it.authenticate(VaultAction.Unlock) }
            enterCredential()
            waitFor { vm.state.value.stage == WalletStage.Backup && !vm.state.value.busy }
            assertTrue(address == vm.state.value.info!!.address)
            scenario.onActivity { vm.checkBackup() }
            waitFor { vm.state.value.stage == WalletStage.Verify && !vm.state.value.busy }
            UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("Discard setup")
            requireNotNull(device.wait(Until.findObject(By.text("Discard setup")), 10_000)).click()
            requireNotNull(device.wait(Until.findObject(By.text("Discard and start over")), 10_000)).click()
            enterCredential()
            waitFor { vm.state.value.stage == WalletStage.Setup && !vm.state.value.busy }
            assertFalse(vm.state.value.hasSetup || vm.state.value.hasVault || file.exists())
            scenario.onActivity { vm.generate(RecoveryFormat.LEGACY25) }
            enterCredential()
            waitFor { vm.state.value.stage == WalletStage.Backup && !vm.state.value.busy }
            assertTrue(address != vm.state.value.info!!.address)
        }
    }

    @Test fun staleDiscardAuthenticationCannotRemoveThePausedSetup() {
        assumeTrue(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = model(scenario)
            scenario.onActivity { vm.restore(RecoveryFormat.LEGACY25, legacy, 0uL) }
            enterCredential()
            waitFor { vm.state.value.stage == WalletStage.Open && !vm.state.value.busy }
            scenario.onActivity { vm.lock() }
            waitFor { !vm.state.value.busy }
            scenario.onActivity { vm.requestDiscardSetup() }
            waitFor { vm.isAuthenticationPending() }
            requireNotNull(device.wait(Until.findObject(By.clazz(java.util.regex.Pattern.compile(".*EditText"))), 30_000))
            instrumentation.runOnMainSync {
                val request = requireNotNull(vm.pendingAuthenticationRequest())
                assertEquals(VaultAction.DiscardSetup, request.first)
                vm.lock()
                vm.finishAuthentication(request.first, request.second, true)
            }
            cancelCredential()
            waitFor { vm.state.value.stage == WalletStage.Locked && !vm.state.value.busy }
            assertTrue(vm.state.value.hasSetup)
            assertTrue(File(context.noBackupFilesDir, "setup-v1.bin").exists())
        }
    }

    @Test fun viewOnlyAndPlatformProtectionsSurviveTheNativeBridge() {
        val hot = OfflineWalletSession.importSpendKey(KeyNetwork.STAGENET,
            "c0af65c0dd837e666b9d0dfed62745f4df35aed7ea619b2798a709f0fe545403", 0uL)
        val view = OfflineWalletSession.importViewOnly(KeyNetwork.STAGENET, hot.details().address,
            "513ba91c538a5a9069e0094de90e927c0cd147fa10428ce3ac1afd49f63e3b01", 0uL)
        val data = view.snapshotForVault()
        try {
            OfflineWalletSession.openSnapshot(data).use { restored ->
                assertEquals(KeyWalletMode.VIEW_ONLY, restored.details().mode)
                assertThrows(WalletException::class.java) { restored.recoveryPhrase() }
                restored.lock()
            }
        } finally { data.fill(0); hot.lock(); hot.destroy(); view.lock(); view.destroy() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertEquals(0, it.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    assertEquals(android.view.View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS,
                        it.window.decorView.importantForContentCapture)
                }
            }
        }
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(permissions.contains("android.permission.INTERNET"))
        assertTrue(permissions.contains("android.permission.CAMERA"))
    }

    @Test fun androidCredentialVaultRoundtripTamperingAndBackgroundLock() {
        assumeTrue(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = model(scenario)
            scenario.onActivity { vm.restore(RecoveryFormat.LEGACY25, legacy, 17uL) }
            enterCredential()
            waitFor { vm.state.value.stage == WalletStage.Open }
            val address = vm.state.value.info!!.address
            scenario.onActivity { it.authenticate(VaultAction.Save) }
            enterCredential()
            waitFor { !vm.state.value.busy }
            assertNull(vm.state.value.error)
            assertTrue(vm.state.value.hasVault)
            val protectedFile = File(context.noBackupFilesDir, "wallet-v1.bin")
            val protectedBytes = protectedFile.readBytes()
            scenario.onActivity {
                vm.requestDiscardSetup()
                assertNull(vm.beginAuthentication(VaultAction.DiscardSetup))
            }
            assertTrue(vm.state.value.hasVault)
            assertArrayEquals(protectedBytes, protectedFile.readBytes())
            scenario.onActivity { it.authenticate(VaultAction.RevealSeed) }
            enterCredential()
            waitFor { vm.state.value.stage == WalletStage.Recovery }
            assertEquals(legacy, vm.state.value.phrase!!.text())
            scenario.onActivity { vm.hideRecovery() }
            assertNull(vm.state.value.phrase)
            assertEquals(WalletStage.Open, vm.state.value.stage)
            val file = File(context.noBackupFilesDir, "wallet-v1.bin")
            val encrypted = file.readBytes()
            assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains(legacy))
            scenario.onActivity { vm.lock(); it.authenticate(VaultAction.Unlock) }
            enterCredential()
            waitFor { vm.state.value.stage == WalletStage.Open }
            assertEquals(address, vm.state.value.info!!.address)
            scenario.moveToState(Lifecycle.State.CREATED)
            waitFor { vm.state.value.stage == WalletStage.Locked }
            assertNull(vm.state.value.info)
            scenario.moveToState(Lifecycle.State.RESUMED)
            // Authentication failure must preserve the existing file and locked state.
            file.writeBytes(encrypted.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            scenario.onActivity { it.authenticate(VaultAction.Unlock) }
            enterCredential()
            waitFor { !vm.state.value.busy }
            assertEquals(WalletStage.Locked, vm.state.value.stage)
            assertNotNull(vm.state.value.error)
            assertTrue(file.exists())
        }
    }
}
