package app.independentwallet.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.independentwallet.bindings.WalletSyncStatus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Public synthetic counters only; cannot run on a personal/physical device. */
@RunWith(AndroidJUnit4::class)
class SyncPresentationIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Before fun requireDisposableEmulator() {
        check(context.packageName == "app.independentwallet.android.syncpreview2")
        check(device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1")
    }
    private fun counters(synced: Boolean) = WalletSyncStatus(hasScan = true, startHeight = 100uL,
        nextHeight = if (synced) 200uL else 150uL, targetHeight = 200uL, synced = synced,
        viewOnly = true, receivedXmr = "0", balanceXmr = null, unlockedXmr = null, history = emptyList(), pending = null, payments = emptyList(), nextUnlockBlocks = null)

    @Test fun actualComposeCardShowsRealRangeProgressAndVerifiedCompletion() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { RetroWalletTheme {
                SyncStatusCard(OnlineUiState(networkEnabled = true, connected = true, syncing = true,
                    status = counters(false)))
            } } }
            assertNotNull(device.wait(Until.findObject(By.textContains("50%")), 10_000))
            assertNotNull(device.wait(Until.findObject(By.text("Block 150 of 200")), 10_000))
            assertFalse(device.hasObject(By.textContains("100%")))
            scenario.onActivity { it.setContent { RetroWalletTheme {
                SyncStatusCard(OnlineUiState(networkEnabled = true, connected = true,
                    status = counters(true)))
            } } }
            assertNotNull(device.wait(Until.findObject(By.text("Synced")), 10_000))
            assertNotNull(device.wait(Until.findObject(By.textContains("100%")), 10_000))
        }
    }
    @Test fun connectingWithoutAHeightShowsActivityWithoutInventedCompletion() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { RetroWalletTheme {
                SyncStatusCard(OnlineUiState(networkEnabled = true, busy = true))
            } } }
            assertNotNull(device.wait(Until.findObject(By.text("Connecting")), 10_000))
            assertNotNull(device.wait(Until.findObject(By.textContains("LINK")), 10_000))
            assertFalse(device.hasObject(By.textContains("100%")))
        }
    }

    @Test fun unscannedZeroHeightWalletOffersAStartChoiceWithoutFakeProgress() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { RetroWalletTheme {
                SyncStatusCard(OnlineUiState(networkEnabled = true, connected = true,
                    status = counters(false).copy(hasScan = false, startHeight = 0uL, nextHeight = 0uL)))
            } } }
            assertNotNull(device.wait(Until.findObject(By.text("Choose scan start")), 10_000))
            assertNotNull(device.wait(Until.findObject(By.text("START")), 10_000))
            assertFalse(device.hasObject(By.textContains("100%")))
        }
    }
}
