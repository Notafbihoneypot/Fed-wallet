package app.independentwallet.android

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal enum class SetupStep { Backup, Verify, Ready }

/** Plaintext exists only while converting the Rust checkpoint to encryption. */
internal class SetupRecord(val step: SetupStep, val snapshot: ByteArray, val answers: List<String>) : AutoCloseable {
    override fun toString(): String = "[private setup record]"
    override fun close() { snapshot.fill(0) }
    fun encode(): ByteArray {
        require(snapshot.size in 8..4096 && answers.size == 3)
        require(step == SetupStep.Verify || answers.all { it.isEmpty() })
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(MAGIC); d.writeByte(step.ordinal); d.writeShort(snapshot.size); d.write(snapshot)
            for (answer in answers) {
                require(answer.length <= 32)
                val bytes = answer.toByteArray(Charsets.UTF_8)
                try { require(bytes.size <= 128); d.writeByte(bytes.size); d.write(bytes) }
                finally { bytes.fill(0) }
            }
        }
        return out.toByteArray().also { require(it.size <= VaultEnvelope.MAX_PLAINTEXT) }
    }
    companion object {
        private const val MAGIC = 0x49575531 // IWU1
        fun decode(bytes: ByteArray): SetupRecord {
            require(bytes.size in 16..VaultEnvelope.MAX_PLAINTEXT)
            var snapshot: ByteArray? = null
            try {
                DataInputStream(ByteArrayInputStream(bytes)).use { d ->
                    require(d.readInt() == MAGIC)
                    val step = SetupStep.entries.getOrNull(d.readUnsignedByte()) ?: error("Invalid setup step")
                    val size = d.readUnsignedShort(); require(size in 8..4096 && size <= d.available() - 3)
                    val value = ByteArray(size); snapshot = value; d.readFully(value)
                    val answers = (0..2).map {
                        val length = d.readUnsignedByte(); require(length <= 128 && length <= d.available())
                        val text = ByteArray(length)
                        try {
                            d.readFully(text)
                            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(text)).toString().also { require(it.length <= 32) }
                        } finally { text.fill(0) }
                    }
                    require(d.available() == 0 && (step == SetupStep.Verify || answers.all { it.isEmpty() }))
                    snapshot = null
                    return SetupRecord(step, value, answers)
                }
            } finally { snapshot?.fill(0) }
        }
    }
}
