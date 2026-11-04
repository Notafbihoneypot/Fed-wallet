package app.independentwallet.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

/** Original stepped geometry; no downloaded fonts, images or rendering dependencies. */
object PixelShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { 8.dp.toPx() }.coerceAtMost(minOf(size.width, size.height) / 4)
        val s = c / 2; val w = size.width; val h = size.height
        return Outline.Generic(Path().apply {
            moveTo(c, 0f); lineTo(w-c, 0f); lineTo(w-c, s); lineTo(w-s, s); lineTo(w-s, c); lineTo(w, c)
            lineTo(w, h-c); lineTo(w-s, h-c); lineTo(w-s, h-s); lineTo(w-c, h-s); lineTo(w-c, h)
            lineTo(c, h); lineTo(c, h-s); lineTo(s, h-s); lineTo(s, h-c); lineTo(0f, h-c)
            lineTo(0f, c); lineTo(s, c); lineTo(s, s); lineTo(c, s); close()
        })
    }
}

@Composable
fun RetroWalletTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val type = base.copy(
        headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Monospace),
        headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Monospace),
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
        titleMedium = base.titleMedium.copy(fontFamily = FontFamily.Monospace),
        labelLarge = base.labelLarge.copy(fontFamily = FontFamily.Monospace),
        labelMedium = base.labelMedium.copy(fontFamily = FontFamily.Monospace),
        labelSmall = base.labelSmall.copy(fontFamily = FontFamily.Monospace),
    )
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFB1DC87), onPrimary = Color(0xFF14200D), primaryContainer = Color(0xFF25391E), onPrimaryContainer = Color(0xFFD5F5B5),
            secondary = Color(0xFFE7A36A), onSecondary = Color(0xFF2C190C), secondaryContainer = Color(0xFF392B1C), onSecondaryContainer = Color(0xFFFFDBB8),
            background = Color(0xFF0B100D), onBackground = Color(0xFFE2EAD9), surface = Color(0xFF141D16), onSurface = Color(0xFFE2EAD9),
            surfaceVariant = Color(0xFF1D2A20), onSurfaceVariant = Color(0xFFA6B6A0), surfaceContainer = Color(0xFF18231B),
            outline = Color(0xFF69805F), outlineVariant = Color(0xFF31452F), error = Color(0xFFFFB0A3), errorContainer = Color(0xFF50251F), onErrorContainer = Color(0xFFFFDAD3),
        ), typography = type,
        shapes = Shapes(extraSmall = CutCornerShape(4.dp), small = CutCornerShape(4.dp), medium = CutCornerShape(6.dp), large = CutCornerShape(8.dp), extraLarge = CutCornerShape(8.dp)),
        content = content,
    )
}

@Composable
fun PixelWalletMark(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.secondary
    val rows = intArrayOf(255, 129, 165, 189, 165, 165, 129, 255)
    Canvas(modifier) {
        val cell = minOf(size.width, size.height) / 8
        rows.forEachIndexed { y, row ->
            for (x in 0..7) if (row and (1 shl (7-x)) != 0) {
                drawRect(if (x in 2..5 && y in 2..5) accent else primary,
                    topLeft = androidx.compose.ui.geometry.Offset(x*cell, y*cell), size = Size(cell, cell))
            }
        }
    }
}
