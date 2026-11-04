package app.independentwallet.android

import app.independentwallet.bindings.inspectNode
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real Rust parser across JNI, not a mock validation result. */
class NodeUrlInputRegressionTest {
    @Test fun copiedFullUrlAcceptsSurroundingWhitespace() {
        for (padding in listOf(" ", "\r\n", "\t", "\u00a0"))
            assertEquals("https://node.example:38081/",
                checkedNode(NodeKind.Direct, "${padding}https://NODE.example:38081/$padding"))
    }

    @Test fun standardJsonRpcUrlBecomesTheSameNodeBase() {
        for (suffix in listOf("/json_rpc", "/json_rpc/")) {
            val normalized = checkedNode(NodeKind.Direct, " \nhttp://node.example:18081$suffix\r\n")
            assertEquals("http://node.example:18081/", normalized)
            assertEquals(normalized, inspectNode(normalized).normalizedUrl)
            val profiles = NodeProfiles.decode(NodeProfiles(direct = normalized).encode())
            assertEquals(normalized, checkedNode(profiles.selected, profiles.active!!))
        }
    }

    @Test fun httpsAndBracketedIpv6KeepTheirProtocolAndPort() {
        assertEquals("https://node.example:18081/",
            checkedNode(NodeKind.Direct, "HTTPS://NODE.example:18081/json_rpc"))
        assertEquals("http://[2001:db8::1]:18081/",
            checkedNode(NodeKind.Direct, "http://[2001:db8::1]:18081/json_rpc/"))
        assertEquals("https://node.example/", checkedNode(NodeKind.Direct, "https://node.example:443/json_rpc"))
    }

    @Test fun onionAndHttpsInputKeepTorSelection() {
        val onion = "http://" + "a".repeat(56) + ".onion:18081/"
        assertEquals(onion, checkedNode(NodeKind.Tor, "\n${onion}json_rpc\n"))
        assertEquals("https://node.example:18081/",
            checkedNode(NodeKind.Tor, "https://node.example:18081/json_rpc"))
    }
}
