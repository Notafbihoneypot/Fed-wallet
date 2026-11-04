package app.independentwallet.android

import org.junit.Assert.*
import org.junit.Test

class NodeProfilesBridgeTest {
    @Test fun actualNativeParserEnforcesTheSelectedNodeTypeWithoutNetworking() {
        val onion = "http://" + "a".repeat(56) + ".onion:38081/"
        assertEquals(onion, checkedNode(NodeKind.Tor, onion))
        assertEquals("https://node.example:38081/", checkedNode(NodeKind.Tor, "https://node.example:38081"))
        assertEquals("http://node.example:38081/", checkedNode(NodeKind.Direct, "http://node.example:38081"))
        assertEquals("https://node.example:38081/", checkedNode(NodeKind.Direct, "https://NODE.example:38081"))
        for ((kind, address) in listOf(NodeKind.Direct to onion, NodeKind.Tor to "http://node.example:38081", NodeKind.Direct to "https://user:password@node.example:38081"))
            assertThrows(Exception::class.java) { checkedNode(kind, address) }
    }
    @Test fun migratedEndpointsKeepTheirNativeNormalizationAfterTheNewRoundTrip() {
        val original = checkedNode(NodeKind.Direct, "https://NODE.example:38081")
        val profiles = NodeProfiles.decode(original.toByteArray())
        val restored = NodeProfiles.decode(profiles.encode())
        assertEquals(original, checkedNode(restored.selected, restored.active!!))
    }
}
