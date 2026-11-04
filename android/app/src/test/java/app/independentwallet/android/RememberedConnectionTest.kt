package app.independentwallet.android

import app.independentwallet.bindings.*
import org.junit.Assert.*
import org.junit.Test

class RememberedConnectionTest {
    private val node = "http://chosen.example:18089/"
    private val other = "https://chosen.example:18089/"
    private val info = OfflineWalletSession.importSpendKey(KeyNetwork.MAINNET, "01" + "00".repeat(31), 0uL).use { it.details() }
    private val remembered = RememberedConnection(WalletSlot.Original, info.address, NodeKind.Direct,
        RememberedConnection.fingerprint(node, NodeKind.Direct), reconnect = true, background = true)
    private val wallet = OfflineUiState(walletSlot = WalletSlot.Original, stage = WalletStage.Open, hasVault = true, info = info)

    @Test fun encryptedPayloadRoundTripPreservesIndependentWalletPreferences() {
        val mainnet = remembered.copy(slot = WalletSlot.Mainnet, reconnect = false)
        val profiles = NodeProfiles(direct = node, connections = listOf(remembered, mainnet))
        assertEquals(profiles, NodeProfiles.decode(profiles.encode()))
        assertFalse(remembered.toString().contains(info.address))
        assertFalse(profiles.toString().contains(node))
    }

    @Test fun oldSettingsNeverAcquireConnectionConsentDuringMigration() {
        assertTrue(NodeProfiles.decode(node.toByteArray()).connections.isEmpty())
        assertTrue(NodeProfiles.decode(NodeProfiles(direct = node).encode()).connections.isEmpty())
    }

    @Test fun nodeAndRouteChangesRevokeReconnectWhilePreservingEndpoints() {
        val profiles = NodeProfiles(direct = node, connections = listOf(remembered))
        assertTrue(profiles.withNode(NodeKind.Direct, node).connections.single().reconnect)
        val changed = profiles.withNode(NodeKind.Direct, other)
        assertFalse(changed.connections.single().reconnect)
        assertTrue(changed.connections.single().background)
        val tor = profiles.select(NodeKind.Tor)
        assertFalse(tor.connections.single().reconnect)
        assertNull(tor.active)
        assertEquals(node, tor.direct)
        assertFalse(tor.select(NodeKind.Direct).connections.single().reconnect)
    }

    @Test fun onlyAnAuthenticatedVisibleMatchingWalletCanReconnect() {
        fun allowed(state: OfflineUiState = wallet, visible: Boolean = true, auth: Boolean = false,
            saved: RememberedConnection = remembered, endpoint: String = node, kind: NodeKind = NodeKind.Direct) =
            canReconnectAfterUnlock(state, visible, auth, saved, endpoint, kind)
        assertTrue(allowed())
        assertFalse(allowed(visible = false)); assertFalse(allowed(auth = true))
        for (stage in WalletStage.entries.filter { it != WalletStage.Open }) assertFalse(allowed(wallet.copy(stage = stage)))
        assertFalse(allowed(wallet.copy(hasVault = false)))
        assertFalse(allowed(wallet.copy(busy = true)))
        assertFalse(allowed(wallet.copy(info = null)))
        assertFalse(allowed(wallet.copy(walletSlot = WalletSlot.Mainnet)))
        assertFalse(allowed(wallet.copy(online = OnlineUiState(networkEnabled = true))))
        assertFalse(allowed(wallet.copy(online = OnlineUiState(busy = true))))
        assertFalse(allowed(wallet.copy(online = OnlineUiState(error = "failed"))))
        assertFalse(allowed(saved = remembered.copy(address = "different-wallet")))
        assertFalse(allowed(saved = remembered.copy(reconnect = false)))
        assertFalse(allowed(endpoint = other))
        assertFalse(allowed(kind = NodeKind.Tor))
    }

    @Test fun revocationRetainsTheSeparateBackgroundPreference() {
        val profiles = NodeProfiles(direct = node, connections = listOf(remembered))
        val revoked = profiles.withConnection(remembered.copy(reconnect = false))
        val after = NodeProfiles.decode(revoked.encode()).connections.single()
        assertFalse(after.reconnect); assertTrue(after.background)
        assertFalse(canReconnectAfterUnlock(wallet, true, false, after, node, NodeKind.Direct))
    }

    @Test fun malformedOrAmbiguousConsentRecordsFailClosed() {
        val profiles = NodeProfiles(direct = node, connections = listOf(remembered))
        val bytes = profiles.encode()
        assertThrows(Exception::class.java) { NodeProfiles.decode(bytes.copyOf(bytes.size - 1)) }
        assertThrows(Exception::class.java) { NodeProfiles.decode(bytes + byteArrayOf(0)) }
        assertThrows(Exception::class.java) { NodeProfiles.decode(bytes.clone().also { it[it.lastIndex] = 7 }) }
        assertThrows(Exception::class.java) { profiles.copy(connections = listOf(remembered, remembered)).encode() }
        assertThrows(Exception::class.java) { profiles.copy(connections = listOf(remembered.copy(nodeId = "invalid"))).encode() }
    }
}
