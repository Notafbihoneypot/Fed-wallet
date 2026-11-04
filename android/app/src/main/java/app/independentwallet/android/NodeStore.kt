package app.independentwallet.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted node configuration only. This is not seed/key storage.
 * Stored in noBackupFilesDir and excluded from automatic backups.
 */
class NodeStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "node-v1.bin"))
    private val alias = "independent-wallet-node-v1"
    private val aad = "independent-wallet-node-v1".toByteArray(Charsets.UTF_8)

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Saved node encryption key is unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private fun readLocked(): NodeProfiles? {
        val stream = try { file.openRead() } catch (_: java.io.FileNotFoundException) { return null }
        val data = stream.use {
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(result.size() + count <= NodeProfiles.MAX_BYTES + 64) { "Saved node configuration is too large" }
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        }
        require(data.size >= 29 && data[0] == 1.toByte()) { "Saved node configuration is invalid" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, data.copyOfRange(1, 13)))
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(data.copyOfRange(13, data.size))
        return try { NodeProfiles.decode(plaintext) } finally { plaintext.fill(0) }
    }

    fun read(): NodeProfiles? = synchronized(monitor) { readLocked() }

    /** Read-modify-write under one process-wide monitor preserves the other slot. */
    fun update(change: (NodeProfiles) -> NodeProfiles): NodeProfiles = synchronized(monitor) {
        val next = change(readLocked() ?: NodeProfiles())
        saveLocked(next)
        next
    }

    private fun saveLocked(profiles: NodeProfiles) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(aad)
        check(cipher.iv.size == 12)
        val payload = profiles.encode()
        val ciphertext = try { cipher.doFinal(payload) } finally { payload.fill(0) }
        val stream = file.startWrite()
        try {
            stream.write(byteArrayOf(1))
            stream.write(cipher.iv)
            stream.write(ciphertext)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    fun clear() = synchronized(monitor) {
        file.delete()
        check(listOf("", ".bak", ".new").none { File(file.baseFile.path + it).exists() })
    }

    private companion object { val monitor = Any() }
}
