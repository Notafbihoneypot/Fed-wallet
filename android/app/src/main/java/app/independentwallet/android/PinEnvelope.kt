package app.independentwallet.android

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object PinEnvelope {
    private val magic="IWE2".toByteArray(Charsets.US_ASCII)
    fun isPin(bytes:ByteArray)=bytes.size>=4&&bytes.copyOfRange(0,4).contentEquals(magic)
    fun encrypt(bytes:ByteArray,key:SecretKey,purpose:String):ByteArray {
        require(bytes.size in 16..VaultEnvelope.MAX_PLAINTEXT)
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);check(c.iv.size==12)
        c.updateAAD(magic);c.updateAAD(purpose.toByteArray())
        return magic+c.iv+c.doFinal(bytes)
    }
    fun decrypt(bytes:ByteArray,key:SecretKey,purpose:String):ByteArray {
        require(isPin(bytes)&&bytes.size in 48..VaultEnvelope.MAX_ENVELOPE)
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes.copyOfRange(4,16)))
        c.updateAAD(magic);c.updateAAD(purpose.toByteArray());return c.doFinal(bytes.copyOfRange(16,bytes.size))
    }
}
