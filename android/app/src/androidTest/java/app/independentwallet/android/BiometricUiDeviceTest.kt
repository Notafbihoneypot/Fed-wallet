package app.independentwallet.android

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.independentwallet.bindings.KeyNetwork
import app.independentwallet.bindings.OfflineWalletSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/** Public test wallet only. Requires an enrolled emulator fingerprint and host sensor events. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion=30)
class BiometricUiDeviceTest {
    @Test fun enableAndUnlockThroughRealBiometricPrompt(){
        val c=ApplicationProvider.getApplicationContext<Context>()
        val i=InstrumentationRegistry.getInstrumentation();val d=UiDevice.getInstance(i)
        check(c.packageName=="app.independentwallet.android.syncpreview2")
        check(d.executeShellCommand("getprop ro.kernel.qemu").trim()=="1")
        check(!c.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        check(c.getSystemService(android.hardware.biometrics.BiometricManager::class.java).canAuthenticate(15)==0){"Enroll an emulator fingerprint first"}
        i.runOnMainSync{(c.applicationContext as WalletApplication).viewModelStore.clear()}
        val locks=c.appLocks();locks.lock();c.noBackupFilesDir.listFiles()?.forEach{check(it.deleteRecursively())}
        c.getSharedPreferences("wallet-selection-v1",Context.MODE_PRIVATE).edit().clear().commit()
        KeyStore.getInstance("AndroidKeyStore").apply{load(null);aliases().toList().filter{it.startsWith("independent-wallet-")}.forEach{deleteEntry(it)}}
        val pin="18472639";val grant=locks.securityGrant();locks.setPin(pin,grant);locks.finishMigration(emptyList(),grant)
        val fixture=OfflineWalletSession.importSpendKey(KeyNetwork.MAINNET,"01"+"00".repeat(31),0uL)
        try{val bytes=fixture.snapshotForVault();try{WalletVault(c,WalletSlot.Mainnet).create(bytes)}finally{bytes.fill(0)}
            WalletCatalog(c).identify(WalletSlot.Mainnet,fixture.details().address,true)
        }finally{fixture.lock();fixture.destroy()}
        locks.lock()
        c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        fun click(text:String){
            repeat(7){
                val found=d.wait(Until.findObject(By.text(text)),3_000)
                if(found!=null){found.click();return}
                d.findObject(By.scrollable(true))?.scroll(Direction.DOWN,0.8f)
            }
            error("Missing UI action: $text")
        }
        fun enterPin(){checkNotNull(d.wait(Until.findObject(By.clazz("android.widget.EditText")),15_000)).setText(pin);click("Unlock")}
        click("Unlock wallet");enterPin();assertTrue(d.wait(Until.hasObject(By.text("Balance")),30_000))
        click("Settings");click("Unlock security settings");enterPin();click("Enable biometric unlock")
        println("FINGERPRINT_ENROLL_READY")
        assertTrue(d.wait(Until.hasObject(By.text("Disable biometric unlock")),45_000))
        assertTrue(locks.biometricEnabled())
        i.runOnMainSync{(c.applicationContext as WalletApplication).wallet.lock()}
        click("Wallet");click("Unlock wallet");click("Use biometric")
        println("FINGERPRINT_UNLOCK_READY")
        assertTrue(d.wait(Until.hasObject(By.text("Balance")),45_000))
        assertTrue(locks.biometricEnabled())
        i.runOnMainSync{(c.applicationContext as WalletApplication).wallet.lock()}
        click("Unlock wallet");click("Use biometric");click("Use app PIN")
        assertNotNull(d.wait(Until.findObject(By.clazz("android.widget.EditText")),15_000))
        assertThrows(IllegalStateException::class.java){locks.walletKey(WalletSlot.Mainnet,"wallet")}
    }
}
