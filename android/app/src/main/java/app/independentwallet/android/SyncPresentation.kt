package app.independentwallet.android

import kotlin.math.floor

internal enum class SyncPhase(val title: String, val center: String, val active: Boolean = false) {
    Offline("Wallet offline", "OFF"), Connecting("Connecting", "LINK", true),
    Ready("Choose scan start", "START"),
    Scanning("Syncing wallet", "SCAN", true), Checking("Checking chain tip", "CHECK", true),
    Synced("Synced", "100%"), Paused("Sync paused", "PAUSE")
}

internal data class SyncPresentation(val phase: SyncPhase, val fraction: Float? = null,
    val percent: Int? = null, val remaining: ULong? = null)

/** Activity animation is decorative. Only native status can produce completion. */
internal fun syncPresentation(online: OnlineUiState): SyncPresentation {
    val status = online.status
    if (!online.networkEnabled) return SyncPresentation(if (status?.hasScan == true) SyncPhase.Paused else SyncPhase.Offline)
    if (online.error != null) return SyncPresentation(SyncPhase.Paused)
    if (!online.connected) return SyncPresentation(SyncPhase.Connecting)
    if (status != null && status.startHeight > status.targetHeight)
        return SyncPresentation(if (online.syncing) SyncPhase.Checking else SyncPhase.Paused)
    if (!online.syncing && status?.let(::needsScanStartChoice) == true)
        return SyncPresentation(SyncPhase.Ready)
    if (status != null && status.targetHeight > 0uL && status.synced && !online.syncing && status.nextHeight >= status.targetHeight)
        return SyncPresentation(SyncPhase.Synced, 1f, 100, 0uL)
    if (!online.syncing) return SyncPresentation(SyncPhase.Paused)
    if (status == null || status.targetHeight == 0uL) return SyncPresentation(SyncPhase.Checking)
    if (online.syncing && status.nextHeight >= status.targetHeight) return SyncPresentation(SyncPhase.Checking)
    val restoreHeight = status.startHeight
    if (status.targetHeight <= restoreHeight) return SyncPresentation(SyncPhase.Checking)
    val done = if (status.nextHeight > restoreHeight) status.nextHeight - restoreHeight else 0uL
    val total = status.targetHeight - restoreHeight
    val fraction = (done.toDouble() / total.toDouble()).coerceIn(0.0, 0.999)
    return SyncPresentation(SyncPhase.Scanning, fraction.toFloat(), floor(fraction * 100).toInt().coerceAtMost(99),
        if (status.targetHeight > status.nextHeight) status.targetHeight - status.nextHeight else 0uL)
}

internal fun elapsedSyncSeconds(now: Long, then: Long?): Long =
    if (then == null || then < 0 || now < then) 0 else (now - then) / 1000

/** Clockwise stepped orbit, expressed as fractions of the drawing surface. */
internal fun syncOrbitCell(index: Int): Pair<Float, Float> {
    val normalized = ((index % 32) + 32) % 32
    val step = (normalized % 8) / 7f
    return when (normalized / 8) {
        0 -> (0.18f + step * 0.64f) to 0.14f
        1 -> 0.86f to (0.18f + step * 0.64f)
        2 -> (0.82f - step * 0.64f) to 0.86f
        else -> 0.14f to (0.82f - step * 0.64f)
    }
}
