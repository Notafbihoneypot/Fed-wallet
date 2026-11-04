package app.independentwallet.android

import android.app.KeyguardManager
import android.content.Context
import app.independentwallet.bindings.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/** Destructive fixtures: only this package on an authenticated disposable emulator. */
@RunWith(AndroidJUnit4::class)
class WalletSecurityDeviceTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val locks get()=context.appLocks()
    private val pin="18472639"
    private val duress="93827164"
    private val first=WalletSlot.Mainnet
    private val second=WalletSlot.fromId("ab".repeat(16))
    private val payload="public fixture data, contains no funds".toByteArray()
    @Before fun clearFixture(){
        check(context.packageName=="app.independentwallet.android.syncpreview2")
        check(device.executeShellCommand("getprop ro.kernel.qemu").trim()=="1")
        check(!context.getSystemService(KeyguardManager::class.java).isDeviceLocked){"Unlock the disposable emulator before testing"}
        InstrumentationRegistry.getInstrumentation().runOnMainSync{(context.applicationContext as WalletApplication).viewModelStore.clear()}
        locks.lock();context.noBackupFilesDir.listFiles()?.forEach{check(it.deleteRecursively())}
        context.getSharedPreferences("wallet-selection-v1",Context.MODE_PRIVATE).edit().clear().commit()
        KeyStore.getInstance("AndroidKeyStore").apply{load(null);aliases().toList().filter{it.startsWith("independent-wallet-")}.forEach{deleteEntry(it)}}
    }
    private fun configure(){val grant=locks.securityGrant();locks.setPin(pin,grant);locks.finishMigration(emptyList(),grant)}
    private fun unlock(value:String=pin){val r=locks.checkPin(value);assertTrue(r is PinCheck.Unlocked);(r as PinCheck.Unlocked).use{locks.activate(it.root)}}
    @Test fun pinAndDistinctWalletsSurviveLockAndPinChange(){
        configure();val a=WalletVault(context,first);val b=WalletVault(context,second);a.create(payload);b.create(payload+byteArrayOf(3))
        val path=File(context.noBackupFilesDir,second.vaultFile);val original=path.readBytes();assertTrue(PinEnvelope.isPin(original))
        path.writeBytes(File(context.noBackupFilesDir,first.vaultFile).readBytes());assertThrows(Exception::class.java){b.read()};path.writeBytes(original)
        locks.lock();assertThrows(Exception::class.java){a.read()};assertTrue(locks.checkPin("18472630") is PinCheck.Rejected)
        unlock();assertArrayEquals(payload,a.read());assertArrayEquals(payload+byteArrayOf(3),b.read())
        locks.setPin("28593740",locks.securityGrant());locks.lock();assertTrue(locks.checkPin(pin) is PinCheck.Rejected);unlock("28593740")
        assertArrayEquals(payload,a.read());assertArrayEquals(original,path.readBytes())
    }
    @Test fun legacyVaultMigratesToPinAndSurvivesReopen(){
        val vault=WalletVault(context,first);vault.create(payload)
        val old=File(context.noBackupFilesDir,first.vaultFile).readBytes()
        assertFalse(PinEnvelope.isPin(old));assertArrayEquals(payload,vault.read())
        val grant=locks.securityGrant();locks.setPin(pin,grant);assertTrue(locks.migrating())
        locks.finishMigration(listOf(first),grant);assertFalse(locks.migrating())
        assertTrue(PinEnvelope.isPin(File(context.noBackupFilesDir,first.vaultFile).readBytes()))
        locks.lock();unlock();assertArrayEquals(payload,vault.read())
        val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        assertFalse(keys.containsAlias(first.vaultAlias))
    }
    @Test fun duressIsSeparateAndRecognizedDuringNormalPinLockout(){
        configure();WalletVault(context,first).create(payload)
        assertThrows(IllegalArgumentException::class.java){locks.setDuress(pin,locks.securityGrant())}
        locks.setDuress(duress,locks.securityGrant());locks.lock()
        repeat(5){assertTrue(locks.checkPin("11111111") is PinCheck.Rejected)}
        assertTrue(locks.checkPin(pin) is PinCheck.Rejected);assertSame(PinCheck.Duress,locks.checkPin(duress))
        assertTrue(WalletVault(context,first).exists());assertFalse(AppWiper.pending(context.noBackupFilesDir))
    }
    @Test fun revokedGrantCannotChangePinAfterLock(){
        configure();val grant=locks.securityGrant();locks.lock()
        assertThrows(IllegalStateException::class.java){locks.setPin("28593740",grant)};unlock()
    }
    @Test fun missingPinConfigNeverReplacesRootWhenBackupVaultExists(){
        configure();val vault=WalletVault(context,first);vault.create(payload)
        val config=File(context.noBackupFilesDir,"app-lock-v1.bin");val saved=config.readBytes()
        val file=File(context.noBackupFilesDir,first.vaultFile);val backup=File(file.path+".bak")
        assertTrue(file.renameTo(backup));assertTrue(config.delete())
        assertThrows(IllegalStateException::class.java){locks.setPin("28593740",locks.securityGrant())}
        assertFalse(config.exists());assertTrue(backup.exists());config.writeBytes(saved)
        locks.lock();unlock();assertArrayEquals(payload,vault.read())
    }
    @Test fun prepareDuressFixture(){
        configure();val wallet=OfflineWalletSession.importSpendKey(KeyNetwork.MAINNET,"01"+"00".repeat(31),0uL)
        try{val bytes=wallet.snapshotForVault();try{WalletVault(context,first).create(bytes)}finally{bytes.fill(0)}
            WalletCatalog(context).identify(first,wallet.details().address,true)
        }finally{wallet.lock();wallet.destroy()}
        locks.setDuress(duress,locks.securityGrant());locks.lock()
        context.getSharedPreferences("duress-fixture",Context.MODE_PRIVATE).edit().putBoolean("must-clear",true).commit()
        File(context.filesDir,"duress-fixture").writeText("public fixture")
        if(InstrumentationRegistry.getArguments().getString("stage_wipe_marker")=="true")AppWiper.markPending(context)
    }

}
