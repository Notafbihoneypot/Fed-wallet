package app.independentwallet.android

import org.junit.Assert.*
import org.junit.Test

class SavedNodesTest {
    private fun url(n: Int) = "https://node$n.example:18081/"
    private fun grant(p: NodeProfiles) = p.withConnection(RememberedConnection(WalletSlot.Mainnet, "public-fixture-address", p.selected,
        RememberedConnection.fingerprint(p.active!!, p.selected), reconnect = true, background = true))

    @Test fun fiveUserNodesRoundTripAndSixthIsRejectedWithoutReplacingAnything() {
        var p = NodeProfiles()
        for (n in 1..5) p = p.withSavedNode(if (n == 5) NodeKind.Tor else NodeKind.Direct, url(n), "My node $n")
        assertEquals(5, p.savedNodes.size)
        assertEquals(p, NodeProfiles.decode(p.encode()))
        assertThrows(IllegalArgumentException::class.java) { p.withSavedNode(NodeKind.Direct, url(6), "Sixth") }
        assertEquals(url(5), p.active)
        assertThrows(IllegalArgumentException::class.java) { p.withSavedNode(NodeKind.Direct, url(1), "Duplicate") }
        assertFalse(p.toString().contains(url(1)))
        assertFalse(p.savedNodes[0].toString().contains(url(1)))
    }
    @Test fun migrationPreservesBothOldEndpointsAndConsentUntilAnActualSelectionChanges() {
        val legacy = grant(NodeProfiles(direct = url(1), tor = url(2)))
        val decoded = NodeProfiles.decode(legacy.encode())
        assertEquals(2, decoded.savedNodes.size)
        val renamed = decoded.withSavedNode(NodeKind.Direct, url(1), "Home", decoded.activeSavedNode!!.id)
        assertTrue(renamed.connections.single().reconnect)
        val added = renamed.withSavedNode(NodeKind.Direct, url(3), "Backup")
        assertEquals(listOf(url(1), url(2), url(3)), added.savedNodes.map { it.url })
        assertFalse(added.connections.single().reconnect)
        val selected = added.chooseNode(added.savedNodes[0].id)
        assertEquals(url(1), selected.active)
        assertFalse(selected.connections.single().reconnect)
    }
    @Test fun removingSelectedNodeNeverChoosesAnotherEndpointAndEmptyListStaysEmpty() {
        var p = NodeProfiles().withSavedNode(NodeKind.Direct, url(1), "One").withSavedNode(NodeKind.Direct, url(2), "Two")
        p = p.removeNode(p.activeSavedNode!!.id)
        assertNull(p.active); assertEquals(1, p.savedNodes.size)
        assertNull(NodeProfiles.decode(p.encode()).active)
        p = p.removeNode(p.savedNodes.single().id)
        assertTrue(p.savedNodes.isEmpty()); assertNull(p.active)
        assertEquals(p, NodeProfiles.decode(p.encode()))
    }
    @Test fun removingInactiveNodePreservesCurrentGrantButEndpointAndRouteChangesRevokeIt() {
        val two = grant(NodeProfiles().withSavedNode(NodeKind.Direct, url(1), "One").withSavedNode(NodeKind.Direct, url(2), "Two"))
        assertTrue(two.removeNode(two.savedNodes[0].id).connections.single().reconnect)
        val changed = two.withSavedNode(NodeKind.Tor, url(2), "Tor", two.activeSavedNode!!.id)
        assertFalse(changed.connections.single().reconnect)
        assertEquals(NodeKind.Tor, changed.selected)
        assertNull(changed.direct)
        assertNull(changed.select(NodeKind.Direct).active)
    }
    @Test fun malformedListsAndSelectedReferencesFailClosed() {
        val p = NodeProfiles().withSavedNode(NodeKind.Direct, url(1), "Home")
        assertThrows(IllegalArgumentException::class.java) { p.copy(selectedNodeId = "missing").encode() }
        assertThrows(IllegalArgumentException::class.java) { p.copy(nodes = p.savedNodes + p.savedNodes).encode() }
        assertThrows(IllegalArgumentException::class.java) { p.withSavedNode(NodeKind.Direct, url(2), "x".repeat(49)) }
        assertThrows(IllegalArgumentException::class.java) { p.withSavedNode(NodeKind.Direct, url(2), "bad\nname") }
        val bytes = p.encode()
        assertThrows(Exception::class.java) { NodeProfiles.decode(bytes + byteArrayOf(0)) }
        for (length in listOf(4, 5, 10, bytes.lastIndex)) assertThrows(Exception::class.java) { NodeProfiles.decode(bytes.copyOf(length)) }
    }
    @Test fun unfinishedNodeEditorCannotSupplyAnOldEndpointForANewConnection() {
        val profiles = NodeProfiles().withSavedNode(NodeKind.Direct, url(1), "Home")
        val ui = WalletUiState(draftNode = url(1), draftName = "Home", draftProtocol = NodeProtocol.Https,
            profiles = profiles, coreVersion = "test", loading = false)
        assertEquals(url(1), ui.connectionNode)
        assertNull(ui.copy(addingNode = true, draftNode = "").connectionNode)
        assertNull(ui.copy(draftName = "Renaming").connectionNode)
        assertNull(ui.copy(draftNode = "new.example").connectionNode)
    }
}
