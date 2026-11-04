package app.independentwallet.android

import app.independentwallet.bindings.inspectNode
import org.junit.Assert.*
import org.junit.Test

class NodeEntryRegressionTest {
    private fun rejected(value: String, protocol: NodeProtocol, expected: NodeInputProblem) {
        val error = assertThrows(NodeInputException::class.java) { checkedNode(NodeKind.Direct, value, protocol) }
        assertEquals(expected, error.problem)
    }

    @Test fun bareHostnameAndIpUseTheVisiblySelectedProtocolAndKeepThePort() {
        for (host in listOf("NODE.example", "127.0.0.1", "[2001:db8::1]")) {
            for (protocol in NodeProtocol.entries) {
                val expected = protocol.prefix + host.lowercase() + ":18081/"
                val normalized = checkedNode(NodeKind.Direct, " \n$host:18081/json_rpc/\t", protocol)
                assertEquals(expected, normalized)
                assertEquals(expected, inspectNode(normalized).normalizedUrl)
                val restored = NodeProfiles.decode(NodeProfiles(direct = normalized).encode())
                assertEquals(expected, checkedNode(restored.selected, restored.active!!))
            }
        }
    }

    @Test fun explicitHttpsIsNeverDowngradedByAnHttpFormSelection() {
        assertEquals("https://node.example:18081/",
            checkedNode(NodeKind.Direct, "https://NODE.example:18081/json_rpc", NodeProtocol.Http))
        assertEquals(NodeProtocol.Https, protocolForNode(" \nHTTPS://NODE.example:18081/"))
        assertNull(protocolForNode("node.example:18081"))
    }

    @Test fun formProtocolDoesNotRepairMalformedSchemesOrInventARoute() {
        for (value in listOf("http:node.example:18081", "https:node.example", "ftp://node.example", "//node.example:18081", "file:/node.example"))
            rejected(value, NodeProtocol.Http, NodeInputProblem.Protocol)
        val onion = "a".repeat(56) + ".onion:18081"
        assertEquals("http://$onion/", checkedNode(NodeKind.Tor, onion, NodeProtocol.Http))
        val error = assertThrows(NodeInputException::class.java) { checkedNode(NodeKind.Direct, onion, NodeProtocol.Http) }
        assertEquals(NodeInputProblem.DirectOnion, error.problem)
        val torHttp = assertThrows(NodeInputException::class.java) { checkedNode(NodeKind.Tor, "node.example:18081", NodeProtocol.Http) }
        assertEquals(NodeInputProblem.TorHttp, torHttp.problem)
    }

    @Test fun bareInputStillEnforcesCredentialPathPortAndByteLimits() {
        rejected("user:secret@node.example:18081", NodeProtocol.Https, NodeInputProblem.Login)
        rejected("node.example:65536", NodeProtocol.Http, NodeInputProblem.Port)
        rejected("node.example:18081/private-path", NodeProtocol.Http, NodeInputProblem.Path)
        rejected("no de.example:18081", NodeProtocol.Http, NodeInputProblem.Characters)
        rejected("x".repeat(NodeProfiles.MAX_NODE), NodeProtocol.Https, NodeInputProblem.TooLong)
        rejected("2001:db8::1:18081", NodeProtocol.Http, NodeInputProblem.Ipv6)
    }

    private val saved = "https://original.example:18081/"
    private fun ready() = WalletUiState(draftNode = saved, draftName = NodeKind.Direct.label, draftProtocol = NodeProtocol.Https,
        profiles = NodeProfiles(direct = saved), coreVersion = "test-core", loading = false)

    @Test fun editedAddressCannotConnectToThePreviousSavedHostname() {
        val initial = ready()
        assertEquals(saved, initial.connectionNode)
        val edited = initial.copy(draftNode = "different.example:18081")
        assertTrue(edited.hasNodeDraftChanges)
        assertNull(edited.connectionNode)
        assertEquals(saved, edited.profiles.direct)
    }

    @Test fun changingProtocolAloneBlocksUseOfTheOldSavedNode() {
        val changed = ready().copy(draftProtocol = NodeProtocol.Http)
        assertTrue(changed.hasNodeDraftChanges)
        assertNull(changed.connectionNode)
        assertEquals(saved, changed.profiles.direct)
    }

    @Test fun onlyValidatedSavedReadyStateCanSupplyAConnectionEndpoint() {
        val state = ready()
        for (blocked in listOf(state.copy(loading = true), state.copy(saving = true),
            state.copy(settingsUnavailable = true), state.copy(coreVersion = null), state.copy(nodeError = "invalid input")))
            assertNull(blocked.connectionNode)
        val canonical = checkedNode(NodeKind.Direct, "new.example:18081/json_rpc", NodeProtocol.Https)
        val savedNew = state.copy(draftNode = canonical, profiles = NodeProfiles(direct = canonical))
        assertEquals(canonical, savedNew.connectionNode)
        assertFalse(savedNew.hasNodeDraftChanges)
    }

    @Test fun emptyTorSlotNeverFallsBackToTheSavedDirectEndpoint() {
        val tor = ready().copy(profiles = NodeProfiles(selected = NodeKind.Tor, direct = saved), draftNode = "")
        assertNull(tor.connectionNode)
        assertEquals(saved, tor.profiles.direct)
    }
}
