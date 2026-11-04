package app.independentwallet.android

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.floor

@Composable
fun SyncStatusCard(online: OnlineUiState) {
    val view = syncPresentation(online)
    val percentText = syncPercentText(view)
    val colors = MaterialTheme.colorScheme
    val largeText = LocalDensity.current.fontScale > 1.3f
    val moving = view.phase.active && view.fraction == null && ValueAnimator.areAnimatorsEnabled()
    val frame: State<Float> = if (moving) rememberInfiniteTransition(label = "sync activity").animateFloat(
        initialValue = 0f, targetValue = 32f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "pixel orbit"
    ) else rememberUpdatedState(0f)
    val now by produceState(SystemClock.elapsedRealtime(), view.phase.active, online.startedAtMs) {
        value = SystemClock.elapsedRealtime()
        while (view.phase.active) { delay(1000); value = SystemClock.elapsedRealtime() }
    }
    Surface(shape = PixelShape, color = colors.surface, border = BorderStroke(1.dp,
        if (view.phase == SyncPhase.Synced) colors.primary else colors.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(view.phase.title, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Box(Modifier.size(if (largeText) 220.dp else 172.dp).semantics(mergeDescendants = true) {
                contentDescription = "Wallet sync progress"
                stateDescription = percentText?.let { "${view.phase.title}, $it" } ?: view.phase.title
                if (view.phase.active) progressBarRangeInfo = view.fraction?.let { ProgressBarRangeInfo(it, 0f..1f) } ?: ProgressBarRangeInfo.Indeterminate
            }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
                    val cell = size.minDimension * 0.035f
                    for (y in 0..5) for (x in 0..5) drawRect(colors.primary.copy(alpha = 0.08f),
                        Offset(size.width * (0.25f + x * 0.1f), size.height * (0.25f + y * 0.1f)), Size(cell / 2, cell / 2))
                    val runner = frame.value.toInt() % 32
                    val filled = floor((view.fraction ?: 0f) * 32).toInt()
                    for (i in 0 until 32) {
                        val (x, y) = syncOrbitCell(i)
                        val trail = (runner - i + 32) % 32
                        val color = when {
                            moving && trail < 3 -> colors.secondary.copy(alpha = 1f - trail * 0.25f)
                            i < filled -> colors.primary
                            else -> colors.outlineVariant
                        }
                        drawRect(color, Offset(x * size.width - cell / 2, y * size.height - cell / 2), Size(cell, cell))
                    }
                    if (view.phase == SyncPhase.Scanning && moving) {
                        val y = size.height * (0.28f + (frame.value / 32f) * 0.44f)
                        for (x in 0..5) drawRect(colors.primary.copy(alpha = 0.14f), Offset(size.width * (0.28f + x * 0.08f), y), Size(cell, cell / 2))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(percentText ?: if (view.phase == SyncPhase.Connecting && online.route == NodeKind.Tor) "TOR" else view.phase.center, fontFamily = FontFamily.Monospace,
                        fontSize = if ((percentText?.length ?: 0) > 5) (if (largeText) 20.sp else 28.sp)
                            else if (view.percent != null) (if (largeText) 28.sp else 36.sp) else 23.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, softWrap = false,
                        color = if (view.phase == SyncPhase.Synced) colors.primary else colors.onSurface)
                    Text(if (view.phase == SyncPhase.Scanning) "BLOCK SCAN" else if (view.phase == SyncPhase.Synced) "UP TO DATE" else "WALLET / SYNC",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            online.status?.takeIf { it.targetHeight > 0uL }?.let {
                Text("Block ${it.nextHeight} of ${it.targetHeight}", style = MaterialTheme.typography.bodyMedium)
            }
            view.remaining?.takeIf { it > 0uL }?.let { Text("$it blocks remaining", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            if (view.fraction != null) LinearProgressIndicator(progress = { view.fraction }, modifier = Modifier.fillMaxWidth().height(12.dp), color = colors.primary, trackColor = colors.outlineVariant)
            else if (view.phase.active) LinearProgressIndicator(Modifier.fillMaxWidth().height(12.dp), color = colors.secondary, trackColor = colors.outlineVariant)
            when (view.phase) {
                SyncPhase.Connecting -> Text("${if (online.route == NodeKind.Tor) "Tor startup and node checks" else "Node checks"} · ${elapsedSyncSeconds(now, online.startedAtMs)}s", style = MaterialTheme.typography.bodySmall)
                SyncPhase.Scanning -> {
                    val age = elapsedSyncSeconds(now, online.lastBlockAtMs ?: online.startedAtMs)
                    Text(if (age >= 15) "Waiting for the next batch · last progress ${age}s ago" else "Scanning locally through your selected node",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                SyncPhase.Checking -> Text("Checking the latest node state", style = MaterialTheme.typography.bodySmall)
                SyncPhase.Synced -> Text("Up to date with this node", style = MaterialTheme.typography.bodySmall, color = colors.primary)
                SyncPhase.Ready -> Text("Choose a scan start, or scan from the beginning", style = MaterialTheme.typography.bodySmall)
                SyncPhase.Paused -> Text("Saved scan progress is kept on this device", style = MaterialTheme.typography.bodySmall)
                SyncPhase.Offline -> Text("Enable network access to start syncing", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** UI-only choice; selecting a slot does not authorize a network connection. */
class TorChoice(val available: Boolean, val selected: Boolean, val open: () -> Unit)
