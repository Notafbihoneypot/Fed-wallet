package app.independentwallet.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.independentwallet.bindings.WalletSyncStatus

/** A suggestion only. It never changes a wallet or starts network access. */
internal fun recentScanHeight(target: ULong): ULong = if (target > 1_440uL) target - 1_440uL else 0uL

internal fun needsScanStartChoice(status: WalletSyncStatus): Boolean =
    !status.hasScan && status.startHeight == 0uL && status.targetHeight > 0uL

internal fun validScanStart(value: String, status: WalletSyncStatus, confirmed: Boolean): ULong? =
    parseRestoreHeight(value)?.takeIf { it <= status.targetHeight && (it <= status.startHeight || confirmed) }

@Composable
fun SyncStartPanel(wallet: OfflineUiState, vm: OfflineWalletViewModel) {
    val online = wallet.online
    val status = online.status ?: return
    if (!online.connected || wallet.stage != WalletStage.Open) return
    var show by remember(wallet.generation, online.node) { mutableStateOf(false) }
    var height by remember(show) { mutableStateOf(status.startHeight.toString()) }
    var confirmed by remember(show) { mutableStateOf(false) }
    val editable = !wallet.busy && !online.busy && online.review == null
    Text("Scan starts at block ${status.startHeight}. The current block number is a chain height, not a count of blocks left.",
        style = MaterialTheme.typography.bodySmall)
    if (online.syncing) {
        OutlinedButton(onClick = vm::pauseSync, enabled = !wallet.busy && !online.pauseRequested) {
            Text(if (online.pauseRequested) "Pausing after this batch…" else "Pause to change scan start")
        }
    } else {
        OutlinedButton(onClick = { show = true }, enabled = editable) { Text("Change scan start") }
        if (needsScanStartChoice(status)) Text("New wallet? Choose recent blocks. Restoring an older wallet? Choose a height before its first payment, or scan from the beginning.",
            style = MaterialTheme.typography.bodySmall)
    }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text("Wallet scan start") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Choose a block before this wallet's first payment. Starting too late can hide funds. Recorded activity and pending payments cannot be skipped.")
            OutlinedTextField(value = height, onValueChange = { value ->
                if (value.length <= 10 && value.all { it in '0'..'9' }) { height = value; confirmed = false }
            }, label = { Text("Start block") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            TextButton(onClick = { height = recentScanHeight(status.targetHeight).toString(); confirmed = false }, enabled = editable) {
                Text("Wallet created today · use recent blocks")
            }
            Text("Recent blocks keeps a buffer of 1,440 blocks before this node's current height. Use an earlier height for an older wallet.", style = MaterialTheme.typography.bodySmall)
            if ((parseRestoreHeight(height) ?: 0uL) > status.startHeight) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = confirmed, onCheckedChange = { confirmed = it }, enabled = editable)
                Text("This height is before my wallet's first payment.", style = MaterialTheme.typography.bodySmall)
            }
        } },
        confirmButton = { TextButton(onClick = {
            validScanStart(height, status, confirmed)?.let { show = false; vm.rescan(it) }
        }, enabled = editable && validScanStart(height, status, confirmed) != null) { Text("Apply and sync") } },
        dismissButton = { TextButton(onClick = { show = false }) { Text("Keep current start") } })
}
