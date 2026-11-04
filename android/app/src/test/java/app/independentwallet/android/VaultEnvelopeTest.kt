package app.independentwallet.android

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class VaultEnvelopeTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val plaintext = ByteArray(128) { (it * 3).toByte() }

    @Test fun roundtripUsesFreshNonces() {
        val key = key()
        val first = VaultEnvelope.encrypt(plaintext, key)
        val second = VaultEnvelope.encrypt(plaintext, key)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(plaintext, VaultEnvelope.decrypt(first, key))
        assertArrayEquals(plaintext, VaultEnvelope.decrypt(second, key))
    }

    @Test fun wrongKeyAndTamperingFailAuthentication() {
        val key = key()
        val data = VaultEnvelope.encrypt(plaintext, key)
        assertThrows(Exception::class.java) { VaultEnvelope.decrypt(data, key()) }
        for (index in data.indices) {
            val corrupt = data.clone()
            corrupt[index] = (corrupt[index].toInt() xor 1).toByte()
            assertThrows(Exception::class.java) { VaultEnvelope.decrypt(corrupt, key) }
        }
    }

    @Test fun truncatedOversizedAndUnknownVersionsAreRejected() {
        val key = key()
        val data = VaultEnvelope.encrypt(plaintext, key)
        for (length in 0 until data.size) assertThrows(Exception::class.java) { VaultEnvelope.decrypt(data.copyOf(length), key) }
        assertThrows(Exception::class.java) { VaultEnvelope.decrypt(ByteArray(VaultEnvelope.MAX_ENVELOPE + 1), key) }
        val future = data.clone().also { it[3] = '2'.code.toByte() }
        assertThrows(Exception::class.java) { VaultEnvelope.decrypt(future, key) }
    }

    @Test fun encryptionBoundsAndMaximumRoundtrip() {
        val key = key()
        assertThrows(Exception::class.java) { VaultEnvelope.encrypt(ByteArray(15), key) }
        assertThrows(Exception::class.java) { VaultEnvelope.encrypt(ByteArray(VaultEnvelope.MAX_PLAINTEXT + 1), key) }
        val max = ByteArray(VaultEnvelope.MAX_PLAINTEXT)
        assertArrayEquals(max, VaultEnvelope.decrypt(VaultEnvelope.encrypt(max, key), key))
    }
}
