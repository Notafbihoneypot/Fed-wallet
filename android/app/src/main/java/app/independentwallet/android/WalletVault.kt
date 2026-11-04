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

/** Independent encrypted slots. Creation never replaces an existing wallet. */
internal class WalletVault(private val context: Context, private val slot: WalletSlot = WalletSlot.Original) {
    private val file = AtomicFile(File(context.noBackupFilesDir, slot.vaultFile))
    private val alias = slot.vaultAlias

    @Synchronized fun exists(): Boolean = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()

    private fun key(create: Boolean): SecretKey {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        check(keyguard.isDeviceSecure) { "Set an Android screen lock before saving a wallet" }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create && !exists()) { "The saved wallet encryption key is unavailable" }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setUnlockedDeviceRequired(true)
        if (Build.VERSION.SDK_INT >= 30) {
            spec.setUserAuthenticationParameters(30, KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            spec.setUserAuthenticationValidityDurationSeconds(30)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(spec.build())
        }.generateKey()
    }

    @Synchronized fun read(): ByteArray {
        val encrypted = file.openRead().use { input ->
            // Bound the read before allocation; never use unbounded readBytes().
            val buffer = ByteArray(VaultEnvelope.MAX_ENVELOPE + 1)
            var size = 0
            while (size < buffer.size) {
                val count = input.read(buffer, size, buffer.size - size)
                if (count < 0) break
                size += count
            }
            require(size <= VaultEnvelope.MAX_ENVELOPE) { "Wallet file is too large" }
            buffer.copyOf(size)
        }
        return if(PinEnvelope.isPin(encrypted)) PinEnvelope.decrypt(encrypted,context.appLocks().walletKey(slot,"wallet"),"wallet:${slot.name}")
        else VaultEnvelope.decrypt(encrypted, key(create = false))
    }

    fun create(plaintext: ByteArray) = VaultCreationGate.create(::exists) {
        check(!AppWiper.pending(context.noBackupFilesDir))
        val encrypted = if(context.appLocks().configured()) PinEnvelope.encrypt(plaintext,context.appLocks().walletKey(slot,"wallet"),"wallet:${slot.name}")
        else VaultEnvelope.encrypt(plaintext, key(create = true))
        val output = file.startWrite()
        try {
            output.write(encrypted)
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }
    @Synchronized fun migrateToPin(commit: (() -> Unit) -> Unit) = commit {
        if(!exists())return@commit
        val old=file.openRead().use{it.readNBytesCompat(VaultEnvelope.MAX_ENVELOPE+1)};require(old.size<=VaultEnvelope.MAX_ENVELOPE)
        if(PinEnvelope.isPin(old)){read().fill(0);return@commit}
        val plain=VaultEnvelope.decrypt(old,key(false))
        try{
            val encrypted=PinEnvelope.encrypt(plain,context.appLocks().walletKey(slot,"wallet"),"wallet:${slot.name}")
            val out=file.startWrite();try{out.write(encrypted);file.finishWrite(out)}catch(e:Throwable){file.failWrite(out);throw e}
            val checked=read();try{check(java.security.MessageDigest.isEqual(plain,checked))}finally{checked.fill(0)}
        }finally{plain.fill(0)}
    }

}
