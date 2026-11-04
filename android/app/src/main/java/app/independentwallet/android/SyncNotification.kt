package app.independentwallet.android

import java.util.Locale
import kotlin.math.floor

internal fun syncPercentText(progress: SyncPresentation): String? {
    val fraction = progress.fraction ?: return null
    if (progress.phase == SyncPhase.Synced) return "100%"
    val percent = floor(fraction.toDouble().coerceIn(0.0, 0.999) * 10_000.0) / 100.0
    return when {
        fraction > 0f && percent == 0.0 -> "<0.01%"
        percent == floor(percent) -> "${percent.toInt()}%"
        else -> String.format(Locale.ROOT, "%.2f%%", percent)
    }
}

internal data class SyncNotification(val title: String, val text: String, val detail: String,
    val progress: Int?, val max: Int = 10_000)

/** Public counters only: never include balances, addresses, keys or node URLs. */
internal fun syncNotification(online: OnlineUiState): SyncNotification {
    if (online.submitting) return SyncNotification("Submitting authorized payment", "Waiting for the selected node",
        "Automatic updates are paused while the approved request finishes.", null)
    if (online.composingPayment || online.review != null) return SyncNotification("Wallet updates paused", "Payment in progress",
        "Finish or cancel the payment to resume automatic updates.", null)
    val view = syncPresentation(online)
    val percent = syncPercentText(view)
    val title = when (view.phase) {
        SyncPhase.Scanning -> "Monero wallet syncing"
        SyncPhase.Connecting -> "Connecting to Monero node"
        SyncPhase.Checking -> "Checking Monero chain tip"
        SyncPhase.Synced -> "Monero wallet up to date"
        SyncPhase.Paused -> "Monero wallet sync paused"
        SyncPhase.Offline -> "Monero wallet offline"
        SyncPhase.Ready -> "Monero wallet ready to scan"
    }
    val status = online.status?.takeIf { online.connected && it.targetHeight > 0uL }
    val counts = status?.let { "Block ${it.nextHeight} of ${it.targetHeight}" }
    val remaining = view.remaining?.let { "$it blocks remaining" }
    val text = listOfNotNull(percent, counts).joinToString(" · ").ifEmpty { title }
    val detail = listOfNotNull(percent, counts, remaining, status?.let { "Scan starts at block ${it.startHeight}" },
        if (view.phase == SyncPhase.Synced && online.backgroundAllowed) "Watching for new blocks" else null,
        if (online.pauseRequested) "Pausing after the current batch" else online.route?.connectionLabel).joinToString("\n")
        .ifEmpty { title }
    val value = view.fraction?.let { if (view.phase == SyncPhase.Synced) 10_000 else floor(it.toDouble() * 10_000.0).toInt().coerceIn(0, 9_990) }
    return SyncNotification(title, text, detail, value)
}
