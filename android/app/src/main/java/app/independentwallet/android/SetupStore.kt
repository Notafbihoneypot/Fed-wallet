package app.independentwallet.android

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

internal interface SetupFile {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    fun delete()
}
internal interface SetupKeyWrapper {
    fun wrap(bytes: ByteArray): ByteArray
    fun unwrap(bytes: ByteArray): ByteArray
}

/** Envelope encryption: a credential-protected key wraps a random setup key.
 * The live setup key permits checkpoint writes after the Keystore auth window
 * expires; it is erased when the wallet locks. Resuming requires authentication. */
internal class SetupStore(private val file: SetupFile, private val wrapper: SetupKeyWrapper, private val monitor: Any) {
    fun exists(): Boolean = synchronized(monitor) { file.read() != null }
    fun create(plaintext: ByteArray): SetupLease = synchronized(monitor) {
        check(file.read() == null) { "An encrypted setup already exists" }
        val key = ByteArray(32)
        try {
            SecureRandom().nextBytes(key)
            val wrapped = wrapper.wrap(key); require(wrapped.size == WRAPPED_SIZE)
            val record = encode(wrapped, VaultEnvelope.encrypt(plaintext, SecretKeySpec(key, "AES")))
            file.write(record)
            SetupLease(key, wrapped, fingerprint(record)).also { /* ownership transfers on success */ }
        } catch (error: Throwable) { key.fill(0); throw error }
    }
    fun open(): OpenSetup = synchronized(monitor) {
        val record = checkNotNull(file.read()) { "Saved setup unavailable" }
        val (wrapped, encrypted) = split(record)
        val key = wrapper.unwrap(wrapped)
        try {
            require(key.size == 32)
            val plaintext = VaultEnvelope.decrypt(encrypted, SecretKeySpec(key, "AES"))
            OpenSetup(plaintext, SetupLease(key, wrapped, fingerprint(record)))
        } catch (error: Throwable) { key.fill(0); throw error }
    }
    /** Retire active leases before this atomic wrapping-key migration. */
    fun rewrap(commit: (() -> Unit) -> Unit = { it() })=synchronized(monitor){ commit {
        val current=file.read()?:return@commit
        val (wrapped,encrypted)=split(current);val key=wrapper.unwrap(wrapped)
        try{require(key.size==32);VaultEnvelope.decrypt(encrypted,SecretKeySpec(key,"AES")).fill(0);val next=wrapper.wrap(key);require(next.size==WRAPPED_SIZE)
            file.write(encode(next,encrypted))
            val (actual,_)=split(checkNotNull(file.read()));val verify=wrapper.unwrap(actual)
            try{check(MessageDigest.isEqual(key,verify))}finally{verify.fill(0)}
        }finally{key.fill(0)}
    } }
    /** Capture the exact draft being confirmed, without decrypting its keys. */
    fun prepareDiscard(): DiscardRequest = synchronized(monitor) {
        DiscardRequest(fingerprint(checkNotNull(file.read()) { "Saved setup unavailable" }))
    }
    internal inner class DiscardRequest(private val expected: ByteArray) {
        private var completed = false
        fun commit() = synchronized(monitor) {
            check(!completed) { "Discard request already used" }
            discardMatching(expected)
            completed = true
        }
        override fun toString(): String = "[setup discard request]"
    }
    private fun discardMatching(expected: ByteArray) {
        val current = file.read()
        if (current != null) {
            check(MessageDigest.isEqual(expected, fingerprint(current))) { "Saved setup changed in another session" }
            file.delete()
        }
        check(file.read() == null) { "Saved setup could not be removed" }
    }
    internal inner class SetupLease(private val key: ByteArray, private val wrapped: ByteArray, private var expected: ByteArray) : AutoCloseable {
        private var closed = false
        fun write(plaintext: ByteArray) = synchronized(monitor) {
            check(!closed)
            val current = checkNotNull(file.read()) { "Saved setup unavailable" }
            check(MessageDigest.isEqual(expected, fingerprint(current))) { "Saved setup changed in another session" }
            val next = encode(wrapped, VaultEnvelope.encrypt(plaintext, SecretKeySpec(key, "AES")))
            file.write(next)
            expected = fingerprint(next)
        }
        fun discard() = synchronized(monitor) {
            check(!closed)
            discardMatching(expected)
        }
        override fun close() = synchronized(monitor) { closed = true; key.fill(0) }
    }
    internal inner class OpenSetup(val plaintext: ByteArray, val lease: SetupLease) : AutoCloseable {
        override fun close() { plaintext.fill(0); lease.close() }
    }
    private fun fingerprint(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun encode(wrapped: ByteArray, encrypted: ByteArray): ByteArray = magic + wrapped + encrypted
    private fun split(bytes: ByteArray): Pair<ByteArray, ByteArray> {
        require(bytes.size in 116..MAX_RECORD && bytes.copyOfRange(0,4).contentEquals(magic))
        return bytes.copyOfRange(4,4+WRAPPED_SIZE) to bytes.copyOfRange(4+WRAPPED_SIZE,bytes.size)
    }
    companion object {
        const val WRAPPED_SIZE = 64 // IWE1 envelope of a 32-byte AES key
        const val MAX_RECORD = 4 + WRAPPED_SIZE + VaultEnvelope.MAX_ENVELOPE
        private val magic = byteArrayOf(0x49,0x57,0x50,0x31) // IWP1
    }
}
