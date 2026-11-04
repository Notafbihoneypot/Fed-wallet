package app.independentwallet.android

import org.junit.Assert.*
import org.junit.Test

class NodeProfilesTest {
    private val https = "https://wallet-node.example:38081/"
    private val onion = "http://" + "a".repeat(56) + ".onion:38081/"

    @Test fun priorSingleNodeSettingsMigrateWithoutReplacingTheChosenEndpoint() {
        val clear = NodeProfiles.decode(https.toByteArray())
        assertEquals(NodeKind.Direct, clear.selected); assertEquals(https, clear.active); assertNull(clear.tor)
        val hidden = NodeProfiles.decode(onion.toByteArray())
        assertEquals(NodeKind.Tor, hidden.selected); assertEquals(onion, hidden.active); assertNull(hidden.direct)
    }
    @Test fun switchingToAnEmptyTorSlotNeverFallsBackToTheDirectNode() {
        val original = NodeProfiles(direct = https)
        val selected = original.copy(selected = NodeKind.Tor)
        assertNull(selected.active)
        assertEquals(https, NodeProfiles.decode(selected.encode()).direct)
        val both = selected.withNode(NodeKind.Tor, onion)
        assertEquals(both, NodeProfiles.decode(both.encode()))
        val forgotten = both.withNode(NodeKind.Tor, null)
        assertNull(forgotten.active); assertEquals(https, forgotten.direct)
        assertEquals(https, forgotten.copy(selected = NodeKind.Direct).active)
    }
    @Test fun existingBinaryNodeSlotsKeepTheirSelectionAndValues() {
        // Byte-compatible IWN2 record from v0.8, with its onion selection retained.
        val legacy = byteArrayOf(0x49, 0x57, 0x4e, 0x32, 1) +
            byteArrayOf(0, https.length.toByte()) + https.toByteArray() +
            byteArrayOf(0, onion.length.toByte()) + onion.toByteArray()
        val decoded = NodeProfiles.decode(legacy)
        assertEquals(NodeKind.Tor, decoded.selected)
        assertEquals(onion, decoded.active)
        assertEquals(https, decoded.direct)
        assertArrayEquals(legacy, decoded.encode())
        assertEquals(NodeKind.Direct, NodeProfiles().selected)
    }
    @Test fun malformedRecordsFailClosedWithStrictLengthsAndUtf8() {
        val good = NodeProfiles(NodeKind.Tor, https, onion).encode()
        for (size in listOf(0, 4, 5, 6, 8, good.lastIndex))
            assertThrows(Exception::class.java) { NodeProfiles.decode(good.copyOf(size)) }
        assertThrows(Exception::class.java) { NodeProfiles.decode(good + byteArrayOf(0)) }
        assertThrows(Exception::class.java) { NodeProfiles.decode(good.clone().also { it[4] = 8 }) }
        assertThrows(Exception::class.java) { NodeProfiles.decode(good.clone().also { it[5] = 0x7f }) }
        assertThrows(Exception::class.java) { NodeProfiles.decode(good.clone().also { it[7] = 0xff.toByte() }) }
        assertThrows(Exception::class.java) { NodeProfiles.decode(ByteArray(NodeProfiles.MAX_BYTES + 1)) }
        assertThrows(Exception::class.java) { NodeProfiles(direct = "x".repeat(NodeProfiles.MAX_NODE + 1)).encode() }
        assertThrows(Exception::class.java) { NodeProfiles.decode("not a node".toByteArray()) }
    }
    @Test fun diagnosticFormattingDoesNotExposeEitherPrivateEndpoint() {
        val profiles = NodeProfiles(NodeKind.Tor, https, onion)
        assertFalse(profiles.toString().contains(https)); assertFalse(profiles.toString().contains(onion))
    }
}
