package app.independentwallet.android

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.independentwallet.bindings.WalletPayment
import app.independentwallet.bindings.WalletTransfer
import kotlinx.coroutines.delay

@Composable
fun PaymentProgress(online: OnlineUiState) {
    var elapsed by remember(online.operationStartedAtMs) { mutableLongStateOf(0L) }
    LaunchedEffect(online.busy, online.operationStartedAtMs) {
        while (online.busy) {
            elapsed = online.operationStartedAtMs?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0) / 1000 } ?: 0
            delay(1000)
        }
    }
    LinearProgressIndicator(Modifier.fillMaxWidth())
    Text("${online.phase} · ${elapsed}s", style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentReceiptSheet(payment: WalletPayment, network: String, online: OnlineUiState, transactionKey: SensitiveTransactionKey?,
    clipboardMessage: String?, canRevealKey: Boolean, revealKey: () -> Unit, copyKey: () -> Unit, clearCopy: () -> Unit,
    hideKey: () -> Unit, dismiss: () -> Unit, refresh: () -> Unit) {
    val context = LocalContext.current
    val submitted = online.submission.takeIf { online.lastPaymentId == payment.transaction }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Payment details", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(network, style = MaterialTheme.typography.labelLarge)
            Text(paymentStatusTitle(payment, submitted), color = MaterialTheme.colorScheme.primary)
            Text("Recipient amount  ${payment.amountXmr} XMR")
            Text("Network fee  ${payment.feeXmr} XMR")
            Text("Total cost  ${payment.totalXmr} XMR", fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            Text("Recipient", style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(payment.destination, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { TransactionCopy.public(context, "Monero address", payment.destination, "Recipient address copied") }) { Text("Copy recipient address") }
            Text("Transaction ID", style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(payment.transaction, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { TransactionCopy.public(context, "Monero transaction ID", payment.transaction, "Transaction ID copied") }) { Text("Copy transaction ID") }
            HorizontalDivider()
            TransactionKeySection(payment, transactionKey, clipboardMessage, canRevealKey, revealKey, copyKey, clearCopy, hideKey)
            HorizontalDivider()
            if (payment.minedHeight != null) Text("Included in block ${payment.minedHeight}", style = MaterialTheme.typography.bodySmall)
            else Text("A node accepting the payment is not a block confirmation. Inputs stay reserved while confirmation is pending. If the submission reply was lost, retry only this exact payment.", style = MaterialTheme.typography.bodySmall)
            if (!online.connected || online.status?.synced != true) Text("Confirmation data is from the last completed scan. Connect and sync to update it.", style = MaterialTheme.typography.bodySmall)
            if (online.busy) PaymentProgress(online)
            Button(onClick = refresh, enabled = online.connected && !online.busy && online.review == null, modifier = Modifier.fillMaxWidth()) { Text("Check confirmation") }
            TextButton(onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * The transaction key proves a payment this wallet sent. It is read from the
 * encrypted journal only after device authentication, and leaves this screen
 * only through an explicit copy.
 */
@Composable
private fun TransactionKeySection(payment: WalletPayment, shown: SensitiveTransactionKey?, clipboardMessage: String?, canReveal: Boolean,
    reveal: () -> Unit, copy: () -> Unit, clearCopy: () -> Unit, hide: () -> Unit) {
    val key = shown?.takeIf { it.transaction == payment.transaction }?.text()?.takeIf { it.isNotEmpty() }
    Text("Transaction key", style = MaterialTheme.typography.labelLarge)
    when {
        !payment.hasTransactionKey -> Text(transactionKeyUnavailable, style = MaterialTheme.typography.bodySmall)
        key == null -> {
            Text(transactionKeyPurpose, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = reveal, enabled = canReveal, modifier = Modifier.fillMaxWidth()) { Text("Authenticate to show transaction key") }
        }
        else -> {
            // Not selectable: the system Copy action would bypass the sensitive, clearable clip.
            Text(key, style = MaterialTheme.typography.bodySmall)
            Text("Share it only with someone who should be able to confirm this payment. Clipboard apps may keep copies; Android is asked to hide the preview. This screen hides the key after one minute; a copy stays until you clear or replace it.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = copy) { Text("Copy transaction key") }
            clipboardMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = clearCopy) { Text("Clear copy") }
                TextButton(onClick = hide) { Text("Hide") }
            }
        }
    }
}

internal const val transactionKeyPurpose =
    "With the transaction ID and the recipient address, this key lets someone confirm the amount you sent to that address. It cannot spend funds and does not reveal your other payments. It is stored encrypted on this device."
internal const val transactionKeyUnavailable =
    "Not recorded for this payment. Payments sent before this version did not keep their key, and it cannot be rebuilt afterwards."

/** How many blocks include or follow the transfer, from the last completed scan. */
internal fun scannedConfirmations(nextHeight: ULong, height: ULong): ULong = if (nextHeight > height) nextHeight - height else 0uL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivedTransferSheet(transfer: WalletTransfer, network: String, nextHeight: ULong, current: Boolean, dismiss: () -> Unit) {
    val context = LocalContext.current
    val confirmations = scannedConfirmations(nextHeight, transfer.height)
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Transaction details", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(network, style = MaterialTheme.typography.labelLarge)
            Text("${if (transfer.sentXmr == null) "Received" else "Sent"} · $confirmations ${if (confirmations == 1uL) "confirmation" else "confirmations"}", color = MaterialTheme.colorScheme.primary)
            Text("Amount  ${transfer.sentXmr ?: transfer.receivedXmr} XMR", fontWeight = FontWeight.SemiBold)
            Text("Included in block ${transfer.height}", style = MaterialTheme.typography.bodySmall)
            if (!current) Text("Confirmation data is from the last completed scan. Connect and sync to update it.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Transaction ID", style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(transfer.transaction, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { TransactionCopy.public(context, "Monero transaction ID", transfer.transaction, "Transaction ID copied") }) { Text("Copy transaction ID") }
            HorizontalDivider()
            Text("Transaction key", style = MaterialTheme.typography.labelLarge)
            Text("Only the sender's wallet has the key for a payment you received. Ask the sender for it if you need proof of this payment.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            Spacer(Modifier.height(12.dp))
        }
    }
}
