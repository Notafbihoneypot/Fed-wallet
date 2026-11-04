package app.independentwallet.android

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.independentwallet.bindings.SubaddressLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun receivingLabel(address: SubaddressLabel): String = address.label.ifBlank {
    if (address.index == 0u) "Primary address" else "Subaddress ${address.index}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReceiveAddressesSheet(wallet: OfflineUiState, networkName: String, vm: OfflineWalletViewModel, dismiss: () -> Unit) {
    val context = LocalContext.current
    val book = wallet.receiving
    val address = book?.selectedAddress
    var choosing by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var newLabel by remember { mutableStateOf("") }
    val selected = book?.addresses?.firstOrNull { it.index == book.selectedIndex }
    var label by remember(book?.selectedIndex, selected?.label) { mutableStateOf(selected?.label.orEmpty()) }
    // Tag every bitmap with its address. A changed selection must never display
    // the previous QR beside new address text while the replacement is computed.
    val frame by produceState<Pair<String, ImageBitmap>?>(null, address) {
        value = null
        if (address != null) {
            val image = withContext(Dispatchers.Default) {
                Bitmap.createBitmap(receiveQrPixels(address), QR_SIZE, QR_SIZE, Bitmap.Config.ARGB_8888).asImageBitmap()
            }
            value = address to image
        }
    }
    val qr = frame?.takeIf { it.first == address }?.second
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Receive Monero", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (book != null && selected != null) {
                Text("$networkName · ${receivingLabel(selected)}", style = MaterialTheme.typography.labelMedium)
                Text(if (book.selectedIndex == 0u) "Primary address" else "Subaddress ${book.selectedIndex}", style = MaterialTheme.typography.bodySmall)
                Surface(shape = PixelShape, color = Color.White) {
                    Box(Modifier.size(248.dp).padding(12.dp), contentAlignment = Alignment.Center) {
                        qr?.let { Image(it, "Receive address QR code", Modifier.fillMaxSize(), filterQuality = FilterQuality.None) }
                            ?: CircularProgressIndicator()
                    }
                }
                SelectionContainer { Text(book.selectedAddress, style = MaterialTheme.typography.bodySmall) }
                NfcReceiveControl(networkName, book.selectedAddress, !wallet.receivingBusy)
                Button(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Monero address", book.selectedAddress))
                    Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.fillMaxWidth().height(52.dp), enabled = !wallet.receivingBusy) { Text("Copy address") }
                OutlinedButton(onClick = { choosing = true }, enabled = !wallet.receivingBusy, modifier = Modifier.fillMaxWidth()) { Text("Choose receiving address") }
                OutlinedTextField(label, { if (it.length <= 64) label = it }, label = { Text("Address label · optional") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !wallet.receivingBusy)
                TextButton(onClick = { vm.labelSubaddress(book.selectedIndex, label) }, enabled = !wallet.receivingBusy && label != selected.label) { Text("Save label") }
                Button(onClick = { newLabel = ""; creating = true }, modifier = Modifier.fillMaxWidth(),
                    enabled = !wallet.receivingBusy && book.addresses.last().index < book.maximumSubaddresses) { Text("New subaddress") }
                Text("${book.addresses.size - 1} / ${book.maximumSubaddresses} subaddresses · account 0", style = MaterialTheme.typography.bodySmall)
                Text("All receiving addresses belong to this wallet. Creating one works offline. Labels stay on this phone; your seed restores addresses, not labels.", style = MaterialTheme.typography.bodySmall)
            } else if (!wallet.receivingBusy) {
                TextButton(onClick = vm::loadReceivingAddresses) { Text("Load receiving addresses") }
            }
            if (wallet.receivingBusy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Loading or saving receiving addresses…", style = MaterialTheme.typography.bodySmall) }
            wallet.receivingError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Monero $networkName", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
        }
    }
    if (choosing && book != null) AlertDialog(onDismissRequest = { choosing = false },
        title = { Text("Receiving addresses") }, text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(book.addresses, key = { it.index.toLong() }) { item ->
                    TextButton(onClick = { vm.selectSubaddress(item.index); choosing = false }, modifier = Modifier.fillMaxWidth(), enabled = !wallet.receivingBusy) {
                        RadioButton(selected = item.index == book.selectedIndex, onClick = null)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(receivingLabel(item))
                            Text(if (item.index == 0u) "Primary" else "Subaddress ${item.index}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { choosing = false }) { Text("Close") } })
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { Text("New subaddress") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Use a different receiving address for another person or purpose.")
            OutlinedTextField(newLabel, { if (it.length <= 64) newLabel = it }, label = { Text("Label · optional") }, singleLine = true)
        }
    }, confirmButton = { TextButton(onClick = { vm.createSubaddress(newLabel); creating = false }, enabled = !wallet.receivingBusy) { Text("Create") } },
        dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } })
}
