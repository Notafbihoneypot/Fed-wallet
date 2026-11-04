package app.independentwallet.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/** Assertions only, after the app's duress path clears its disposable fixture. */
@RunWith(AndroidJUnit4::class)
class DuressResetDeviceTest {
    @Test fun clearedDataAndKeystore(){
        val c=ApplicationProvider.getApplicationContext<Context>()
        check(c.packageName=="app.independentwallet.android.syncpreview2")
        check(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand("getprop ro.kernel.qemu").trim()=="1")
        assertFalse(c.appLocks().configured());assertFalse(AppWiper.pending(c.noBackupFilesDir))
        assertTrue(c.noBackupFilesDir.listFiles().orEmpty().none{it.name.startsWith("wallet")||it.name.startsWith("setup")||it.name.startsWith("app-lock")||it.name=="chain-v1"})
        assertFalse(File(c.filesDir,"duress-fixture").exists())
        assertFalse(c.getSharedPreferences("duress-fixture",Context.MODE_PRIVATE).getBoolean("must-clear",false))
        val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};assertTrue(keys.aliases().toList().none{it.startsWith("independent-wallet-")})
    }
}
