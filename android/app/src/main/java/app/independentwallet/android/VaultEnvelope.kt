package app.independentwallet.android

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Versioned, authenticated envelope. Plaintext must never be written to disk. */
internal object VaultEnvelope {
    const val MAX_PLAINTEXT = 4096
    const val MAX_ENVELOPE = 4 + 12 + MAX_PLAINTEXT + 16
    private val magic = byteArrayOf(0x49, 0x57, 0x45, 0x31) // IWE1
    private val aad = "independent-wallet-key-vault-v1".toByteArray(Charsets.US_ASCII)

    fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        require(plaintext.size in 16..MAX_PLAINTEXT) { "Invalid wallet payload size" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        check(cipher.iv.size == 12) { "Unexpected vault IV size" }
        cipher.updateAAD(aad)
        cipher.updateAAD(magic)
        return magic + cipher.iv + cipher.doFinal(plaintext)
    }

    fun decrypt(envelope: ByteArray, key: SecretKey): ByteArray {
        require(envelope.size in 48..MAX_ENVELOPE && envelope.copyOfRange(0, 4).contentEquals(magic)) {
            "Invalid wallet envelope"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.copyOfRange(4, 16)))
        cipher.updateAAD(aad)
        cipher.updateAAD(magic)
        return cipher.doFinal(envelope.copyOfRange(16, envelope.size))
    }
}
