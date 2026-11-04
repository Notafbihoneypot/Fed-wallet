package app.independentwallet.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Only public dummy text; never run fixture tests on a personal/physical device. */
@RunWith(AndroidJUnit4::class)
class SeedClipboardIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Before fun requireDisposableEmulator() {
        check(context.packageName == "app.independentwallet.android.syncpreview2")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        check(device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1")
    }
    @Test fun sensitiveMetadataAndOwnershipSurviveTheAndroidBoundary() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                val manager = context.getSystemService(ClipboardManager::class.java)
                val copy = AndroidSeedClipboard(context)
                try {
                    assertTrue(copy.copy("public dummy text, not a recovery phrase"))
                    assertTrue(manager.primaryClipDescription!!.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
                    val restored = AndroidSeedClipboard(context)
                    assertEquals("public dummy text, not a recovery phrase", manager.primaryClip!!.getItemAt(0).text.toString())
                    val metadata = context.getSharedPreferences("seed-clipboard-metadata",Context.MODE_PRIVATE).all.values
                    assertFalse(metadata.any { it.toString().contains("public dummy text") })
                    restored.clear()
                    assertFalse(manager.hasPrimaryClip())
                    assertTrue(copy.copy("public dummy text, not a recovery phrase"))
                    manager.setPrimaryClip(ClipData.newPlainText("fixture", "unrelated public text"))
                    copy.clear()
                    assertEquals("unrelated public text", manager.primaryClip!!.getItemAt(0).text.toString())
                } finally { copy.clear(); manager.clearPrimaryClip() }
            }
        }
    }
}
