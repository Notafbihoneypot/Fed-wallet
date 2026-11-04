package app.independentwallet.android

import android.os.SystemClock
import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Local QR-only scanning. No image files, network scanner, or Google services. */
@Composable
fun KeystoneCamera(onFrame: (String) -> Unit, onUnavailable: () -> Unit, modifier: Modifier = Modifier) =
    LocalQrCamera(onFrame, onUnavailable, modifier)

@Composable
internal fun LocalQrCamera(onFrame: (String) -> Unit, onUnavailable: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val frame by rememberUpdatedState(onFrame)
    val unavailable by rememberUpdatedState(onUnavailable)
    val view = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    AndroidView(factory = { view }, modifier = modifier)
    DisposableEffect(view, lifecycle) {
        val closed = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
        val selector = ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
        val analysis = ImageAnalysis.Builder().setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        val decoder = LocalQrDecoder()
        val deliveryPending = AtomicBoolean(false)
        var lastFrame = 0L
        analysis.setAnalyzer(executor) { image ->
            var luminance: ByteArray? = null
            try {
                val now = SystemClock.elapsedRealtime()
                if (closed.get() || now - lastFrame < 180 || image.width > 1920 || image.height > 1920) return@setAnalyzer
                lastFrame = now
                val plane = image.planes[0]
                val data = plane.buffer.duplicate()
                val start = data.position()
                val width = image.width; val height = image.height
                val pixels = ByteArray(width * height); luminance = pixels
                for (y in 0 until height) for (x in 0 until width) {
                    pixels[y * width + x] = data.get(start + y * plane.rowStride + x * plane.pixelStride)
                }
                val text = decoder.decode(pixels, width, height) ?: return@setAnalyzer
                if (text.length <= 9000 && deliveryPending.compareAndSet(false, true)) main.execute {
                    try { if (!closed.get()) frame(text) } finally { deliveryPending.set(false) }
                }
            } catch (_: RuntimeException) {
                // Malformed frames never escape the analyzer or enter logs.
            } finally { luminance?.fill(0); image.close() }
        }
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!closed.get()) try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    unavailable(); return@addListener
                }
                val camera = future.get(); provider = camera
                camera.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) { if (!closed.get()) unavailable() }
        }, main)
        onDispose {
            closed.set(true)
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            executor.shutdownNow()
        }
    }
}
