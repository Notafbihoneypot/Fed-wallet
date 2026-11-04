package app.independentwallet.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun NetworkControls(wallet: OfflineUiState, vm: OfflineWalletViewModel, node: String?, nodeKind: NodeKind, chooseNode: (() -> Unit)?, torChoice: TorChoice? = null) {
    val online = wallet.online
    val ready = wallet.stage == WalletStage.Open && wallet.hasVault && !wallet.busy && !online.busy && node != null
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.allowBackgroundSync(true) else vm.syncServiceFailed() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Network access", fontWeight = FontWeight.SemiBold)
                Text((online.route ?: nodeKind).connectionLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Switch(checked = online.networkEnabled || online.reconnectAfterUnlock, enabled = online.networkEnabled || online.reconnectAfterUnlock || ready,
                onCheckedChange = { if (it) node?.let { chosen -> vm.connect(chosen, nodeKind) } else vm.disableNetworkAccess() },
                modifier = Modifier.semantics { contentDescription = "Network access" })
        }
        Text(online.phase, style = MaterialTheme.typography.labelLarge)
        Text(if (online.reconnectAfterUnlock) "Network access is remembered for this wallet and node. Reconnects after you unlock."
            else "Enabling access remembers this wallet and node for your next unlock. Turning it off disables automatic reconnect.",
            style = MaterialTheme.typography.bodySmall)
        if (wallet.hasVault && wallet.stage == WalletStage.Open) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Allow background sync", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = online.backgroundAllowed, enabled = !wallet.busy && online.review == null,
                    onCheckedChange = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else vm.allowBackgroundSync(enabled)
                    }, modifier = Modifier.semantics { contentDescription = "Allow background sync" })
            }
            Text("Remembers your choice and checks for updates in batches after syncing. Keeps the active wallet in memory during the background session. The screen locks when you leave; return with device authentication. Stop from the notification. Android limits how long a background session can run.", style = MaterialTheme.typography.bodySmall)
            Text("Routine updates are checked about every five minutes; pending payments about every minute. Sending pauses automatic checks. Sync now updates immediately.", style = MaterialTheme.typography.bodySmall)
            if (!online.backgroundAllowed) Text("Enable background sync to show a progress notification while scanning.", style = MaterialTheme.typography.bodySmall)
        }
        if (wallet.hasVault && wallet.stage == WalletStage.Open) SyncStatusCard(online)
        if (wallet.hasVault && wallet.stage == WalletStage.Open) SyncStartPanel(wallet, vm)
        if (wallet.hasVault && wallet.stage == WalletStage.Open && syncPresentation(online).phase == SyncPhase.Synced && torChoice != null && !torChoice.selected) {
            Text("Sync complete. You can now switch to Tor for subsequent connections.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = torChoice.open, enabled = !wallet.busy && !online.busy && online.review == null && !online.composingPayment && !online.submitting) {
                Text(if (torChoice.available) "Switch to Tor" else "Set up Tor node")
            }
            Text("Opens node settings and pauses access. Enable it again when you are ready.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if ((online.route ?: nodeKind) == NodeKind.Direct) Text("Direct connection: your node sees your IP. HTTP traffic is unencrypted; use HTTPS when available.", style = MaterialTheme.typography.bodySmall)
        else Text("Tor only. A failed Tor connection stays offline; it never falls back to direct.", style = MaterialTheme.typography.bodySmall)
        if (online.networkEnabled && !online.connected && online.route == NodeKind.Tor) Text("Tor startup and node checks can take a few minutes.", style = MaterialTheme.typography.bodySmall)
        if (!wallet.hasVault) Text("Finish setup and save the wallet on this device before connecting.", style = MaterialTheme.typography.bodySmall)
        else if (wallet.stage != WalletStage.Open) Text("Unlock the wallet to enable syncing.", style = MaterialTheme.typography.bodySmall)
        else if (node == null) {
            if (chooseNode != null) TextButton(onClick = chooseNode) { Text("Choose your node") }
            else Text("Save a node address above to enable network access.", style = MaterialTheme.typography.bodySmall)
        }
        if (online.connected) OutlinedButton(onClick = vm::refresh, enabled = !online.busy && !wallet.busy && online.review == null && !online.composingPayment && !online.submitting) {
            Text(if (online.status?.let(::needsScanStartChoice) == true) "Scan from beginning" else "Sync now")
        }
        online.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (ready && !online.connected && online.error != null) OutlinedButton(onClick = { node?.let { vm.connect(it, nodeKind) } }) {
            Text("Retry connection")
        }
        Text("Without background permission, leaving pauses access. Stop and lock or a node change always disconnects.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
