package app.independentwallet.android

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.independentwallet.bindings.KeyNetwork

@Composable
fun NodeSettingsPanel(state: WalletUiState, vm: WalletViewModel, wallet: OfflineUiState,
    offlineVm: OfflineWalletViewModel, authenticate: (VaultAction) -> Unit, openWallet: () -> Unit) {
    val context = LocalContext.current
    val editable = !state.loading && !state.saving && !state.settingsUnavailable && state.coreVersion != null
    Text("Your node. Your choice.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text("Save up to five of your own nodes. Choose which one to use and connect when you are ready.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    wallet.info?.let { Text(if (it.network == KeyNetwork.MAINNET) "Wallet network: Mainnet" else "Wallet network: Stagenet · use a stagenet node",
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
    if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text("Your saved nodes (${state.profiles.savedNodes.size}/5)", style = MaterialTheme.typography.titleMedium)
    if (state.profiles.savedNodes.isEmpty()) Text("Add your first Monero node below.", style = MaterialTheme.typography.bodySmall)
    state.profiles.savedNodes.forEach { saved ->
        val selected = state.profiles.activeSavedNode?.id == saved.id
        Surface(shape = PixelShape, color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(saved.name, fontWeight = FontWeight.SemiBold)
                Text(saved.kind.connectionLabel, style = MaterialTheme.typography.labelSmall)
                Text(saved.url, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(enabled = editable && !selected, onClick = {
                        vm.chooseSavedNode(saved.id) { offlineVm.autoConnectCandidate(null, state.nodeKind); offlineVm.disconnect() }
                    }) { Text(if (selected) "Selected" else "Choose node") }
                    TextButton(enabled = editable, onClick = {
                        vm.removeSavedNode(saved.id) { offlineVm.autoConnectCandidate(null, state.nodeKind); offlineVm.disconnect() }
                    }) { Text("Remove") }
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { offlineVm.autoConnectCandidate(null, state.nodeKind); vm.startAddingNode() },
            enabled = editable && !state.addingNode && state.profiles.savedNodes.size < NodeProfiles.MAX_NODES) { Text("Add node") }
        if (state.addingNode) TextButton(onClick = vm::cancelAddingNode, enabled = editable) { Text("Cancel new node") }
    }
    Text(if (state.addingNode) "New node" else "Edit selected node", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(value = state.draftName, onValueChange = { if (it != state.draftName) offlineVm.autoConnectCandidate(null, state.nodeKind); vm.editName(it) }, label = { Text("Node name") },
        singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth(), supportingText = { Text("Optional · up to 48 characters") })
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Use Tor", fontWeight = FontWeight.SemiBold)
            Text(if (state.editorKind == NodeKind.Tor) "Your onion or HTTPS endpoint through Tor" else "Your normal node · direct connection", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = state.editorKind == NodeKind.Tor, enabled = editable,
            onCheckedChange = {
                if (!state.addingNode) { offlineVm.autoConnectCandidate(null, state.nodeKind); offlineVm.disconnect() }
                vm.selectEditorKind(if (it) NodeKind.Tor else NodeKind.Direct)
            }, modifier = Modifier.semantics { contentDescription = "Use Tor" })
    }
    Text("Connection protocol", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NodeProtocol.entries.forEach { protocol ->
            FilterChip(selected = state.draftProtocol == protocol, enabled = editable,
                onClick = { if (protocol != state.draftProtocol) offlineVm.autoConnectCandidate(null, state.nodeKind); vm.selectProtocol(protocol) }, label = { Text(protocol.label) },
                modifier = Modifier.semantics { contentDescription = "Node protocol ${protocol.label}" })
        }
    }
    Text(if (state.draftProtocol == NodeProtocol.Http) "HTTP is unencrypted. Select the protocol supported by your node."
        else "HTTPS requires TLS support on the node's RPC port.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(value = state.draftNode, onValueChange = { if (it != state.draftNode) offlineVm.autoConnectCandidate(null, state.nodeKind); vm.editNode(it) }, modifier = Modifier.fillMaxWidth(),
        label = { Text(state.editorKind.label + " hostname or full URL") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        isError = state.nodeError != null, singleLine = true, enabled = editable,
        supportingText = { Text("Enter your hostname or IP and RPC port, or paste a full HTTP/HTTPS URL. A /json_rpc suffix is accepted. Node login is not supported yet.") })
    state.nodeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (state.settingsUnavailable) TextButton(onClick = { offlineVm.autoConnectCandidate(null, state.nodeKind); offlineVm.disconnect(); vm.forgetUnavailableSettings() }, enabled = !state.saving) { Text("Forget node settings") }
    else Button(onClick = { vm.saveNode { offlineVm.autoConnectCandidate(null, state.nodeKind); offlineVm.disconnect() } }, enabled = editable && state.draftNode.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text(if (state.addingNode || state.savedNode == null) "Save and select node" else "Save node")
    }
    if (state.hasNodeDraftChanges) Text("Save these node changes before connecting. New connections are paused until you save.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    Text("Choosing a different node disconnects the current session. Saved nodes never connect or fail over automatically.", style = MaterialTheme.typography.bodySmall)
    Surface(shape = PixelShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NetworkControls(wallet, offlineVm, state.connectionNode, state.nodeKind, null,
                if (!editable) null else TorChoice(state.profiles.tor != null, state.nodeKind == NodeKind.Tor) {
                    offlineVm.autoConnectCandidate(null, state.nodeKind); offlineVm.disconnect(); vm.selectNodeKind(NodeKind.Tor)
                })
            if (wallet.hasVault && wallet.stage == WalletStage.Locked) Button(onClick = { authenticate(VaultAction.Unlock) }, enabled = !wallet.busy) { Text("Unlock wallet to sync") }
            else if (!wallet.hasVault) TextButton(onClick = openWallet) { Text("Finish wallet setup") }
        }
    }
    if (wallet.online.error != null) {
        Text("Check your connection, the node address and the wallet's network. On GrapheneOS, allow Network access for this app in system settings.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))) }) { Text("Open app settings") }
    }
}
