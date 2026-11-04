package app.independentwallet.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SeedBackupPanel(state: OfflineUiState, vm: OfflineWalletViewModel, saved: Boolean) {
    if (saved) BackHandler { vm.hideRecovery() }
    Text(if (saved) "Recovery phrase" else "Write your backup", style = MaterialTheme.typography.headlineSmall)
    Text(if (saved) "Private view · closes after 60 seconds" else "OFFLINE / KEEP THESE WORDS IN ORDER",
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Text("Anyone with these words can spend your funds. A written offline backup is safer than a clipboard copy.")
    val columns = if (LocalDensity.current.fontScale > 1.4f) 1 else 2
    Surface(shape = PixelShape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            state.phrase?.text()?.split(' ')?.chunked(columns)?.forEachIndexed { row, words ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    words.forEachIndexed { column, word ->
                        Text("${(row * columns + column + 1).toString().padStart(2, '0')}  $word",
                            modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (words.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    var confirmCopy by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { confirmCopy = true }, modifier = Modifier.weight(1f)) { Text("Copy seed") }
        TextButton(onClick = vm::clearSeedClipboard, modifier = Modifier.weight(1f)) { Text("Clear copy") }
    }
    state.clipboardMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
    if (saved) Button(onClick = vm::hideRecovery, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Close seed") }
    else {
        Text("Your setup is saved encrypted. You can leave the app and unlock to continue.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = vm::checkBackup, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Check written backup") }
        TextButton(onClick = vm::lock) { Text("Pause setup") }
    }
    if (confirmCopy) AlertDialog(
        onDismissRequest = { confirmCopy = false }, title = { Text("Copy recovery phrase?") },
        text = { Text("Clipboard apps may keep copies. Android will be asked to hide the preview. The app keeps your copy when you leave, lock or close this page. Use Clear copy or replace the clipboard when finished. Android may clear the clipboard independently.") },
        confirmButton = { TextButton(onClick = { confirmCopy = false; vm.copyRecoveryPhrase() }) { Text("Copy seed") } },
        dismissButton = { TextButton(onClick = { confirmCopy = false }) { Text("Cancel") } },
    )
}
