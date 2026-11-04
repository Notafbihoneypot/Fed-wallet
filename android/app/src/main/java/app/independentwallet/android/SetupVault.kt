package app.independentwallet.android

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Separate from the completed wallet. Creation cannot replace an existing draft;
 * checkpoint updates require that draft's live, version-checked lease. */
internal class SetupVault(private val context: Context, private val slot: WalletSlot = WalletSlot.Original) {
    private val file = AtomicFile(File(context.noBackupFilesDir, slot.setupFile))
    private val alias = slot.setupAlias
    val store = SetupStore(object : SetupFile {
        override fun read(): ByteArray? {
            if (!existsOnDisk()) return null
            return file.openRead().use { input ->
                val buffer = ByteArray(SetupStore.MAX_RECORD + 1)
                var size = 0
                while (size < buffer.size) { val count = input.read(buffer, size, buffer.size-size); if (count < 0) break; size += count }
                require(size <= SetupStore.MAX_RECORD) { "Saved setup is too large" }
                buffer.copyOf(size)
            }
        }
        override fun write(bytes: ByteArray) {
            check(!AppWiper.pending(context.noBackupFilesDir))
            val output = file.startWrite()
            try { output.write(bytes); file.finishWrite(output) }
            catch (error: Throwable) { file.failWrite(output); throw error }
        }
        override fun delete() {
            file.delete()
            check(!existsOnDisk() && !File(file.baseFile.path + ".new").exists()) { "Saved setup could not be removed" }
        }
    }, object : SetupKeyWrapper {
        override fun wrap(bytes: ByteArray): ByteArray = if(context.appLocks().configured()) PinEnvelope.encrypt(bytes,context.appLocks().walletKey(slot,"setup"),"setup:${slot.name}") else VaultEnvelope.encrypt(bytes, key(create = true))
        override fun unwrap(bytes: ByteArray): ByteArray = if(PinEnvelope.isPin(bytes)) PinEnvelope.decrypt(bytes,context.appLocks().walletKey(slot,"setup"),"setup:${slot.name}") else VaultEnvelope.decrypt(bytes, key(create = false))
    }, monitor)

    private fun existsOnDisk(): Boolean = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    private fun key(create: Boolean): SecretKey {
        check(context.getSystemService(KeyguardManager::class.java).isDeviceSecure) { "Set an Android screen lock" }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create && !existsOnDisk()) { "Saved setup key is unavailable" }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true).setUnlockedDeviceRequired(true)
        if (Build.VERSION.SDK_INT >= 30) spec.setUserAuthenticationParameters(30, KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG)
        else { @Suppress("DEPRECATION") spec.setUserAuthenticationValidityDurationSeconds(30) }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec.build()) }.generateKey()
    }
    private companion object {
        val monitor = Any()
    }
}
