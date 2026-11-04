package app.independentwallet.android

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only a public fixture on an isolated emulator. Success kills this test process. */
@RunWith(AndroidJUnit4::class)
class DuressUiTriggerDeviceTest {
    @Test fun enterFixtureDuressPin() {
        val c=ApplicationProvider.getApplicationContext<Context>()
        val d=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        check(c.packageName=="app.independentwallet.android.syncpreview2")
        check(d.executeShellCommand("getprop ro.kernel.qemu").trim()=="1")
        check(c.appLocks().configured() && WalletVault(c,WalletSlot.Mainnet).exists())
        check(c.getSharedPreferences("duress-fixture",Context.MODE_PRIVATE).getBoolean("must-clear",false))
        c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        val open=checkNotNull(d.wait(Until.findObject(By.text("Unlock wallet")),30_000));open.click()
        val field=checkNotNull(d.wait(Until.findObject(By.clazz("android.widget.EditText")),30_000));field.setText("93827164")
        val unlock=checkNotNull(d.wait(Until.findObject(By.text("Unlock")),30_000));unlock.click()
        // Let the submitted PIN finish; an actual reset may kill this process.
        android.os.SystemClock.sleep(20_000)
        // No claim of success: a separate process must verify the resulting files and keys.
    }
}
