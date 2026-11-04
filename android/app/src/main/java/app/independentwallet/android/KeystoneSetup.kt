package app.independentwallet.android

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.independentwallet.bindings.KeystonePairingSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun KeystoneSetup(busy: Boolean, vm: OfflineWalletViewModel) {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    val heightValid = height.isEmpty() || parseRestoreHeight(height) != null
    var manual by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scanner = remember(attempt) { KeystonePairingSession() }
    val scope = rememberCoroutineScope()
    DisposableEffect(scanner) { onDispose { scanner.destroy() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { scanning = false; pasted = "" }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scanning = granted
        if (!granted) message = "Allow camera access to scan, or paste the connection data below."
    }
    fun receive(frame: String) {
        if (busy || processing || frame.length > 9000) return
        val restoreHeight = if (height.isEmpty()) null else parseRestoreHeight(height) ?: return
        processing = true
        scope.launch {
            try {
                val progress = withContext(Dispatchers.Default) { scanner.receiveFrame(frame) }
                if (progress.complete) {
                    scanning = false; pasted = ""; message = null
                    vm.importKeystone(scanner, restoreHeight)
                } else message = "Reading animated QR · ${progress.frames} frames received"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = "Use Keystone's normal Monero / Feather connection QR. Private QR mode and other QR types are unsupported."
            } finally { processing = false }
        }
    }
    Text("Connect Keystone", style = MaterialTheme.typography.titleLarge)
    Text("On Keystone, open the Monero connection QR under Feather. Use the normal connection QR; the hardware recovery phrase stays on Keystone.")
    Text("The phone stores viewing data and scans locally. This preview monitors mainnet or stagenet; hardware sending and spent-status sync are still unavailable.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(height, { if (it.length <= 10 && it.all { c -> c in '0'..'9' }) height = it },
        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy && !processing && !scanning,
        label = { Text("Restore height · optional") }, isError = !heightValid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = { Text("Leave blank to use Keystone's height. A known height before your first payment can speed up scanning.") })
    Button(onClick = {
        message = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
        else permission.launch(Manifest.permission.CAMERA)
    }, enabled = !busy && !processing && !scanning && heightValid, modifier = Modifier.fillMaxWidth()) { Text("Scan Keystone connection") }
    if (scanning && !busy) {
        KeystoneCamera(onFrame = ::receive, onUnavailable = {
            scanning = false; message = "The camera is unavailable. Paste the connection data or try scanning again."
        }, modifier = Modifier.fillMaxWidth().height(280.dp))
        TextButton(onClick = { scanning = false }) { Text("Stop camera") }
    }
    if (processing) LinearProgressIndicator(Modifier.fillMaxWidth())
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (message != null) TextButton(onClick = { if (!processing) { attempt++; message = null; pasted = "" } }, enabled = !busy && !processing) { Text("Restart scan") }
    TextButton(onClick = { manual = !manual; pasted = "" }, enabled = !busy) { Text(if (manual) "Hide manual import" else "Paste connection data") }
    if (manual) {
        OutlinedTextField(pasted, { if (it.length <= 9000) pasted = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Keystone connection data") }, enabled = !busy && !processing,
            visualTransformation = PasswordVisualTransformation(), minLines = 2, maxLines = 4,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
        Button(onClick = { val frame = pasted; pasted = ""; receive(frame) }, enabled = !busy && !processing && pasted.isNotBlank() && heightValid, modifier = Modifier.fillMaxWidth()) { Text("Import Keystone account") }
    }
}
