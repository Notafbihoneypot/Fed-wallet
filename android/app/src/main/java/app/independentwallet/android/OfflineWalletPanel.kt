package app.independentwallet.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.independentwallet.bindings.*

private enum class SetupMode(val label: String, val detail: String) {
    Software("Software wallet", "Generate fresh keys offline or import your own."),
    Hardware("Hardware wallet", "Connect your Keystone. Spending keys stay on the hardware.")
}
private enum class SoftwareAction(val label: String) {
    Create("New wallet"), Restore("Import seed")
}

@Composable
fun OfflineWalletPanel(state: OfflineUiState, vm: OfflineWalletViewModel, authenticate: (VaultAction) -> Unit,
    node: String?, nodeKind: NodeKind, torChoice: TorChoice?, chooseNode: () -> Unit) {
    LaunchedEffect(state.requestSetupAuthentication) {
        if (state.requestSetupAuthentication) authenticate(VaultAction.StartSetup)
    }
    LaunchedEffect(state.requestDiscardAuthentication) {
        if (state.requestDiscardAuthentication) authenticate(VaultAction.DiscardSetup)
    }
    var confirmDiscard by remember(state.generation) { mutableStateOf(false) }
    if (state.stage !in listOf(WalletStage.Open, WalletStage.Locked, WalletStage.Recovery, WalletStage.Keys)) {
        Text("Set up your wallet", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Mainnet · Choose software or connect your Keystone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (state.busy || state.stage == WalletStage.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    key(state.generation) {
        when (state.stage) {
            WalletStage.Loading -> Text("Opening local settings…")
            WalletStage.Setup -> SetupPanel(state, vm)
            WalletStage.Locked -> {
                Spacer(Modifier.height(50.dp))
                Text(if (state.hasVault) "Wallet locked" else "Setup paused", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Text(if (state.hasVault) "Unlock with PIN or biometrics." else "Your setup is saved encrypted on this phone. Unlock to continue where you left off.")
                if (state.backgroundSyncCompleted) Text("Background sync finished. Your progress is saved.", color = MaterialTheme.colorScheme.primary)
                if (state.online.backgroundActive) {
                    Text("Background sync continues", color = MaterialTheme.colorScheme.primary)
                    SyncStatusCard(state.online)
                    TextButton(onClick = vm::lock) { Text("Stop sync and lock") }
                } else if (state.online.busy) Text("Stopping the previous connection…")
                Button(onClick = { authenticate(VaultAction.Unlock) }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()) { Text(if (state.hasVault) "Unlock wallet" else "Resume setup") }
            }
            WalletStage.Backup -> SeedBackupPanel(state, vm, saved = false)
            WalletStage.Recovery -> SeedBackupPanel(state, vm, saved = true)
            WalletStage.Keys -> WalletKeysPanel(state,vm)
            WalletStage.Verify -> {
                Text("Check your backup", style = MaterialTheme.typography.titleLarge)
                Text("Enter these words from your written recovery phrase.")
                val answers = state.backupAnswers
                state.challenge.forEachIndexed { index, position ->
                    SecretInput(answers.getOrNull(index)?.text().orEmpty(), { vm.updateBackupAnswer(index, it) }, "Word ${position.toLong() + 1}", !state.busy, clearOnStop = false)
                }
                Button(onClick = {
                    vm.confirmBackup(answers.map { it.text() })
                }, enabled = !state.busy && answers.size == 3 && answers.all { it.text().isNotBlank() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Verify backup")
                }
                Text("This backup step is saved. Leaving pauses setup.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = vm::lock) { Text("Pause setup") }
            }
            WalletStage.Open -> {
                WalletDashboard(state, vm, authenticate, node, nodeKind, chooseNode, torChoice)
            }
        }
    }
    if (state.hasSetup && !state.hasVault && state.stage in listOf(WalletStage.Locked, WalletStage.Backup, WalletStage.Verify, WalletStage.Open)) {
        TextButton(onClick = { confirmDiscard = true }, enabled = !state.busy,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Discard setup") }
        if (confirmDiscard) AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this setup?") },
            text = { Text("Remove this unfinished wallet from this phone and start again? You will need its recovery phrase, private key or hardware wallet to recover it later. This cannot be undone. Clipboard copies are kept.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.requestDiscardSetup() }, enabled = !state.busy,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Discard and start over") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep setup") } },
        )
    }
}

@Composable
private fun SetupPanel(state: OfflineUiState, vm: OfflineWalletViewModel) {
    val busy = state.busy
    var mode by remember { mutableStateOf(SetupMode.Software) }
    Text("Choose a wallet type", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    SetupMode.entries.forEach { item ->
        Surface(onClick = { if (!busy) mode = item }, shape = PixelShape,
            color = if (mode == item) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, if (mode == item) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.label, fontWeight = FontWeight.SemiBold)
                Text(item.detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    key(mode) {
        when (mode) {
            SetupMode.Software -> SoftwareSetup(state, vm)
            SetupMode.Hardware -> KeystoneSetup(busy, vm)
        }
    }
}

@Composable
private fun SoftwareSetup(state: OfflineUiState, vm: OfflineWalletViewModel) {
    val busy=state.busy
    var action by remember{mutableStateOf(SoftwareAction.Create)}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){SoftwareAction.entries.forEach{item->FilterChip(selected=action==item,onClick={action=item},enabled=!busy,label={Text(item.label)})}}
    if(action==SoftwareAction.Create){
        Text("Create a new wallet and save its recovery phrase offline.")
        Button(onClick={vm.generate(RecoveryFormat.POLYSEED16)},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Create software wallet")}
    }else{
        var words by remember{mutableStateOf("")};var height by remember{mutableStateOf("0")};var advanced by remember{mutableStateOf(false)}
        Text("Import a 16-word Polyseed or 25-word Monero seed. Keep hardware-wallet seeds on the hardware.")
        SecretInput(words,{if(it.length<=4096)words=it},"Recovery phrase",!busy,multiline=true)
        TextButton(onClick={advanced=!advanced}){Text("Restore height")}
        if(advanced)RestoreHeight(height,{height=it},!busy)
        Button(onClick={val parsed=parseRestoreHeight(height)?:return@Button
            val input=words;val count=input.trim().split(Regex("\\s+")).size;val format=if(count==16)RecoveryFormat.POLYSEED16 else RecoveryFormat.LEGACY25
            words="";vm.restore(format,input,parsed)
        },enabled=!busy&&words.trim().split(Regex("\\s+")).size in listOf(16,25)&&validHeight(height),modifier=Modifier.fillMaxWidth()){Text("Import wallet")}
    }
}


private fun validHeight(value: String): Boolean = parseRestoreHeight(value) != null

@Composable
private fun RestoreHeight(value: String, change: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(value, { if (it.length <= 10 && it.all { digit -> digit in '0'..'9' }) change(it) }, Modifier.fillMaxWidth(),
        label = { Text("Restore height") }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = { Text("0 scans from the beginning. A later height must be before the wallet's first payment.") })
}

@Composable
private fun SecretInput(value: String, change: (String) -> Unit, label: String, enabled: Boolean, multiline: Boolean = false, clearOnStop: Boolean = true) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (clearOnStop) change("") }
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, enabled = enabled,
        singleLine = !multiline, minLines = if (multiline) 3 else 1, maxLines = if (multiline) 6 else 1,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
}
