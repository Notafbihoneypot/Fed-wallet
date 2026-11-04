package app.independentwallet.android

import android.content.ClipData
import android.content.ClipboardManager
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
fun PaymentReceiptSheet(payment: WalletPayment, network: String, online: OnlineUiState, dismiss: () -> Unit, refresh: () -> Unit) {
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
            Text("Transaction ID", style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(payment.transaction, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Monero transaction ID", payment.transaction)) }) { Text("Copy transaction ID") }
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
