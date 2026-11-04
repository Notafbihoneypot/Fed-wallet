package app.independentwallet.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.independentwallet.bindings.KeyWalletMode
import app.independentwallet.bindings.KeyNetwork
import app.independentwallet.bindings.SpendReview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletDashboard(wallet: OfflineUiState, vm: OfflineWalletViewModel, authenticate: (VaultAction) -> Unit,
    node: String?, nodeKind: NodeKind, chooseNode: () -> Unit, torChoice: TorChoice?) {
    val info = wallet.info ?: return
    val online = wallet.online
    val status = online.status
    val sync=syncPresentation(online)
    val busy = online.busy || wallet.busy || wallet.receivingBusy
    val watching = info.mode != KeyWalletMode.SOFTWARE
    val keystone = info.mode == KeyWalletMode.KEYSTONE
    val networkName = if (info.network == KeyNetwork.MAINNET) "Mainnet" else "Stagenet"
    var addressConfirmed by remember(info.address) { mutableStateOf(false) }
    val canSave = !keystone || addressConfirmed
    var receive by remember { mutableStateOf(false) }
    val send = online.composingPayment
    var receiptId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(online.receiptToOpen) {
        online.receiptToOpen?.let { receiptId = it; vm.acknowledgeReceiptOpened(it) }
    }
    val scanned = status?.takeIf { it.hasScan }
    val balance = if (watching) scanned?.receivedXmr else scanned?.balanceXmr

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("MONERO / WALLET", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(if (keystone) "Keystone · View-only" else if (watching) "View-only wallet" else "Software wallet", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = PixelShape) {
            Text(networkName.uppercase(), Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
    Surface(shape = PixelShape, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PixelWalletMark(Modifier.size(40.dp))
                Text(if (watching) "Received outputs" else "Balance", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(balance ?: "—", fontSize = if ((balance?.length ?: 0) > 15) 24.sp else 36.sp,
                    fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                Text("XMR", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.labelLarge)
            }
            if (watching) Text("Spent status unknown. This total can include spent funds and change.", style = MaterialTheme.typography.bodySmall)
            else Text("Spendable  ${scanned?.unlockedXmr ?: "—"} XMR", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!watching) balanceExplanation(online)?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text(if(online.connected)sync.phase.title+(syncPercentText(sync)?.let{" · $it"}?:"")+" · "+(online.route?:nodeKind).connectionLabel else if(sync.phase.active)sync.phase.title else "Offline",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick=chooseNode){Text(if(online.connected)"Connection"else"Connect")}
            }
            if(sync.phase.active){if(sync.fraction!=null)LinearProgressIndicator(progress={sync.fraction},modifier=Modifier.fillMaxWidth())else LinearProgressIndicator(Modifier.fillMaxWidth())}
            online.error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        }
    }
    if (!wallet.hasVault) {
        if (keystone) {
            Text("Verify this primary address on Keystone before saving.", style = MaterialTheme.typography.titleSmall)
            SelectionContainer { Text(info.address, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = addressConfirmed, onCheckedChange = { addressConfirmed = it }, enabled = !wallet.busy)
                Text("This address matches my Keystone", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Setup is saved encrypted on this phone. Save the completed wallet before connecting.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { authenticate(VaultAction.Save) }, enabled = !wallet.busy && canSave, modifier = Modifier.fillMaxWidth()) { Text("Save wallet on this device") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = vm::openSendComposer, enabled = !busy && !watching && online.connected && status?.hasScan == true && status.pending == null,
            shape = PixelShape, modifier = Modifier.weight(1f).height(58.dp)) { Text("↑   Send") }
        OutlinedButton(onClick = { receive = true }, enabled = wallet.hasVault && !wallet.busy && !online.composingPayment && !online.submitting && online.review == null, shape = PixelShape,
            modifier = Modifier.weight(1f).height(58.dp)) { Text("↓   Receive") }
    }
    if (keystone) Text("Viewing is available. Keystone signing and verified spent-status sync are not implemented in this preview.", style = MaterialTheme.typography.bodySmall)
    online.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    val pendingPayment = status?.payments?.firstOrNull { it.transaction == status.pending?.transaction }
    pendingPayment?.let { payment ->
        Surface(shape = PixelShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(paymentStatusTitle(payment, online.submission.takeIf { online.lastPaymentId == payment.transaction }), fontWeight = FontWeight.SemiBold)
                Text("${payment.amountXmr} XMR · fee ${payment.feeXmr} XMR")
                Text("Total ${payment.totalXmr} XMR")
                Text("Inputs remain reserved. Sync checks confirmation; retry sends this exact transaction.", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { receiptId = payment.transaction }) { Text("Payment details") }
                    TextButton(onClick = { authenticate(VaultAction.RetrySend) }, enabled = !busy && status?.synced == true) { Text("Authorize retry") }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Activity", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Latest 20", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (status?.history.isNullOrEmpty()) {
        Surface(shape = PixelShape, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (status?.synced == true) "No transactions yet" else "Your activity will appear here", fontWeight = FontWeight.Medium)
                Text(if (status?.synced == true) "No received outputs were found in the scanned range." else "Connect to your node and sync your wallet.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        Surface(shape = PixelShape, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                status!!.history.forEachIndexed { index, transfer ->
                    var details by remember(transfer.transaction) { mutableStateOf(false) }
                    TextButton(onClick = { details = !details }, contentPadding = PaddingValues(vertical = 16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(if (transfer.sentXmr == null) "↓  Received" else "↑  Sent", color = MaterialTheme.colorScheme.onSurface)
                            Text("Block ${transfer.height}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${transfer.sentXmr?.let { "− $it" } ?: "+ ${transfer.receivedXmr}"} XMR", style = MaterialTheme.typography.labelMedium)
                    }
                    if (details) {
                        val payment = status.payments.firstOrNull { it.transaction == transfer.transaction }
                        if (payment != null) TextButton(onClick = { receiptId = payment.transaction }) { Text("Fee, total and confirmations") }
                        else Text("${(if (status.nextHeight >= transfer.height) status.nextHeight - transfer.height else 0uL)} confirmations · last scanned", style = MaterialTheme.typography.bodySmall)
                        SelectionContainer { Text(transfer.transaction, Modifier.padding(bottom = 16.dp), style = MaterialTheme.typography.bodySmall) }
                    }
                    if (index < status.history.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = vm::lock) { Text(if (wallet.hasVault) "Lock" else "Pause setup") }
    }
    if (receive) {
        LaunchedEffect(info.address, online.status?.synced) { vm.loadReceivingAddresses() }
        ReceiveAddressesSheet(wallet, networkName, vm) { receive = false }
    }
    if (send && online.review == null) {
        ModalBottomSheet(onDismissRequest = { if (!busy) vm.closeSendComposer() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            var destination by remember { mutableStateOf("") }
            var amount by remember { mutableStateOf("") }
            var sendMax by remember { mutableStateOf(false) }
            // A merchant amount must never be silently edited into a different invoice.
            var posAmountLocked by remember { mutableStateOf(false) }
            var cameraActive by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Send Monero", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(if (info.network == KeyNetwork.MAINNET) "Mainnet · Monero" else "Stagenet · test coins", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(destination, { if (it.length <= 196) destination = it }, Modifier.fillMaxWidth(), label = { Text("Recipient address") }, enabled = !busy && !posAmountLocked,
                    minLines = 2, maxLines = 3, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false))
                PaymentQrScanControl(networkName, !busy, { cameraActive=it }) { request ->
                    destination=request.address; amount=request.amountXmr.orEmpty(); sendMax=false; posAmountLocked=false
                }
                NfcSendControl(networkName, !busy&&!cameraActive) { request ->
                    if(!cameraActive){
                        destination=request.address; amount=request.amountXmr.orEmpty(); sendMax=false
                        posAmountLocked=request.amountXmr!=null
                    }
                }
                if(posAmountLocked){
                    Text("POS request: destination and exact amount are locked. NFC does not authenticate the merchant or prove payment.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick={destination="";amount="";sendMax=false;posAmountLocked=false},enabled=!busy){Text("Clear POS request")}
                }
                Text("Spendable ${status?.unlockedXmr ?: "—"} XMR", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(if (sendMax) "" else amount, { if (it.length <= 32) { amount = it; sendMax = false } }, Modifier.fillMaxWidth(),
                    label = { Text(if (sendMax) "Max selected" else "Amount in XMR") }, enabled = !busy && !posAmountLocked,
                    trailingIcon = { TextButton(onClick = { sendMax = true }, enabled = !busy && !posAmountLocked) { Text("Max") } },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                online.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (sendMax) Text("Max sends all unlocked, unreserved XMR minus the exact fee. The recipient amount is calculated for review.", style = MaterialTheme.typography.bodySmall)
                if (online.busy) {
                    PaymentProgress(online)
                    Text(paymentPreparationHint(online.route), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.disableNetworkAccess() }) { Text("Stop preparation and disconnect") }
                }
                Button(onClick = { vm.prepareSpend(destination, amount, sendMax) }, enabled = !busy && destination.isNotBlank() && (sendMax || amount.isNotBlank()), modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Review payment") }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    receiptId?.let { id ->
        status?.payments?.firstOrNull { it.transaction == id }?.let { payment ->
            PaymentReceiptSheet(payment, networkName, online, { receiptId = null }, vm::refresh)
        }
    }
    online.review?.let { review ->
        PaymentReviewSheet(review, busy, networkName, online, onCancel = { vm.discardReview() }, onSend = { authenticate(VaultAction.Send) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentReviewSheet(review: SpendReview, busy: Boolean, networkName: String, online: OnlineUiState, onCancel: () -> Unit, onSend: () -> Unit) {
    ModalBottomSheet(onDismissRequest = { if (!busy) onCancel() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Review payment", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("$networkName · ${online.route?.connectionLabel.orEmpty()}", style = MaterialTheme.typography.labelMedium)
            online.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Text("Recipient gets", style = MaterialTheme.typography.labelLarge)
            Text("${review.amountXmr} XMR", style = MaterialTheme.typography.headlineLarge)
            if (review.sendMax) Text("Max · fee deducted from your unlocked balance", style = MaterialTheme.typography.bodySmall)
            Text("To", style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(review.destination, style = MaterialTheme.typography.bodyMedium) }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Network fee"); Text("${review.feeXmr} XMR") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Total", fontWeight = FontWeight.SemiBold); Text("${review.totalXmr} XMR", fontWeight = FontWeight.SemiBold) }
            Text("Authorize with your Android screen lock. The payment review expires after 5 minutes.", style = MaterialTheme.typography.bodySmall)
            if (busy) PaymentProgress(online)
            Button(onClick = onSend, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Authorize & send") }
            TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

