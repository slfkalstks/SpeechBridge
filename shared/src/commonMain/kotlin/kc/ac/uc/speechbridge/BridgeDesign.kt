package kc.ac.uc.speechbridge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Light and dark are authored pairs, not a color inversion or wallpaper-derived palette.
object BridgePalette {
    val primary = Color(0xFF3D5395)
    val onPrimary = Color(0xFFFFFFFF)
    val primaryContainer = Color(0xFFDFE7FF)
    val onPrimaryContainer = Color(0xFF273A70)
    val background = Color(0xFFF1F3F9)
    val surface = Color(0xFFFFFFFF)
    val ink = Color(0xFF232A3A)
    val muted = Color(0xFF566176)
    val outline = Color(0xFF747F93)
    val divider = Color(0xFFDEE3ED)
    val warning = Color(0xFF765008)
    val warningContainer = Color(0xFFFFEBC3)
    val danger = Color(0xFFAB3038)
    val translation = Color(0xFFEBEFF9)
}
object BridgeNightPalette {
    val primary = Color(0xFFB6C7FF)
    val onPrimary = Color(0xFF182B60)
    val primaryContainer = Color(0xFF304473)
    val onPrimaryContainer = Color(0xFFDCE5FF)
    val background = Color(0xFF121722)
    val surface = Color(0xFF1D2432)
    val ink = Color(0xFFE5E9F4)
    val muted = Color(0xFFB0BBCE)
    val outline = Color(0xFF8995AA)
    val divider = Color(0xFF354052)
    val warning = Color(0xFFFFD58C)
    val warningContainer = Color(0xFF48361A)
    val danger = Color(0xFFFFB3B9)
    val translation = Color(0xFF272F42)
}

@Composable fun BridgeTheme(appearance: Appearance = Appearance.SYSTEM, content: @Composable () -> Unit) {
    val dark = when(appearance) { Appearance.SYSTEM -> isSystemInDarkTheme(); Appearance.DARK -> true; Appearance.LIGHT -> false }
    val primary = if(dark) BridgeNightPalette.primary else BridgePalette.primary
    val surface = if(dark) BridgeNightPalette.surface else BridgePalette.surface
    val background = if(dark) BridgeNightPalette.background else BridgePalette.background
    val ink = if(dark) BridgeNightPalette.ink else BridgePalette.ink
    val muted = if(dark) BridgeNightPalette.muted else BridgePalette.muted
    val scheme = (if(dark) darkColorScheme() else lightColorScheme()).copy(
        primary = primary, onPrimary = if(dark) BridgeNightPalette.onPrimary else BridgePalette.onPrimary,
        primaryContainer = if(dark) BridgeNightPalette.primaryContainer else BridgePalette.primaryContainer,
        onPrimaryContainer = if(dark) BridgeNightPalette.onPrimaryContainer else BridgePalette.onPrimaryContainer,
        secondary = muted, onSecondary = surface,
        secondaryContainer = if(dark) BridgeNightPalette.translation else BridgePalette.translation,
        onSecondaryContainer = ink,
        tertiary = if(dark) BridgeNightPalette.warning else BridgePalette.warning,
        tertiaryContainer = if(dark) BridgeNightPalette.warningContainer else BridgePalette.warningContainer,
        onTertiaryContainer = if(dark) BridgeNightPalette.warning else BridgePalette.warning,
        background = background, onBackground = ink,
        surface = surface, onSurface = ink, surfaceVariant = background, onSurfaceVariant = muted,
        surfaceContainer = surface, surfaceContainerLow = surface, surfaceContainerHigh = surface,
        surfaceContainerHighest = background, surfaceContainerLowest = background, surfaceTint = primary,
        outline = if(dark) BridgeNightPalette.outline else BridgePalette.outline,
        outlineVariant = if(dark) BridgeNightPalette.divider else BridgePalette.divider,
        error = if(dark) BridgeNightPalette.danger else BridgePalette.danger,
    )
    MaterialTheme(colorScheme = scheme,
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp),
            headlineSmall = TextStyle(fontSize = 25.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
            titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 27.sp),
            bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
            bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
            labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
            labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
        ),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)), content = content)
}

@Composable internal fun Heading(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable internal fun Panel(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = color, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable internal fun StatusBadge(text: String, warning: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(color = if(warning) colors.tertiaryContainer else colors.primaryContainer,
        contentColor = if(warning) colors.onTertiaryContainer else colors.onPrimaryContainer,
        shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable internal fun InlineError(message: String) {
    Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

internal enum class Symbol { STOP, SPEAKER, MIC, RECORDS, TASKS, SETTINGS, MORE, DOWN, BACK, SWAP, KEYBOARD, SEND, SEARCH, CLOSE, FOLDER, SUN, MOON, SYSTEM }

@Composable internal fun BridgeIcon(page: Page) = AppSymbol(when(page) {
    Page.TALK -> Symbol.MIC; Page.HISTORY -> Symbol.RECORDS; Page.TASKS -> Symbol.TASKS; Page.SETTINGS -> Symbol.SETTINGS
})

/** A single authored 24-unit icon vocabulary; labels live on the containing native control. */
@Composable internal fun AppSymbol(symbol: Symbol, modifier: Modifier = Modifier.size(24.dp)) {
    val ink = LocalContentColor.current
    Canvas(modifier.clearAndSetSemantics {}) {
        val s = size.width / 24f
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(ink, Offset(x*s,y*s), Offset(x2*s,y2*s), 1.8f*s, StrokeCap.Round)
        fun box(x: Float, y: Float, w: Float, h: Float, radius: Float = 3f) =
            drawRoundRect(ink, Offset(x*s,y*s), Size(w*s,h*s), CornerRadius(radius*s), style = Stroke(1.8f*s))
        when(symbol) {
            Symbol.STOP -> drawRoundRect(ink, Offset(6*s,6*s), Size(12*s,12*s), CornerRadius(2*s))
            Symbol.SPEAKER -> {
                val speaker = Path().apply { moveTo(3*s,9*s); lineTo(7*s,9*s); lineTo(12*s,4*s); lineTo(12*s,20*s); lineTo(7*s,15*s); lineTo(3*s,15*s); close() }
                drawPath(speaker,ink,style = Stroke(1.8f*s, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                drawArc(ink,-55f,110f,false,Offset(10*s,7*s),Size(9*s,10*s),style = Stroke(1.8f*s,cap = StrokeCap.Round))
                drawArc(ink,-55f,110f,false,Offset(8*s,3*s),Size(15*s,18*s),style = Stroke(1.8f*s,cap = StrokeCap.Round))
            }
            Symbol.MIC -> {
                box(8f,2f,8f,13f,4f)
                drawArc(ink, 0f, 180f, false, Offset(5*s,6*s), Size(14*s,13*s), style = Stroke(1.8f*s, cap = StrokeCap.Round))
                line(12f,19f,12f,22f); line(8f,22f,16f,22f)
            }
            Symbol.RECORDS -> { box(5f,2f,15f,20f); line(9f,8f,16f,8f); line(9f,12f,16f,12f); line(9f,16f,13f,16f); line(2f,6f,2f,19f) }
            Symbol.TASKS -> { box(3f,3f,18f,18f,5f); line(7f,12f,10.5f,15.5f); line(10.5f,15.5f,17f,8.5f) }
            Symbol.SETTINGS -> {
                val gear = Path()
                repeat(8) { tooth ->
                    listOf(-0.5 to 8.0, -0.25 to 10.0, 0.25 to 10.0, 0.5 to 8.0).forEachIndexed { index, (step, radius) ->
                        val angle = (tooth + step) * kotlin.math.PI / 4
                        val x = (12 + kotlin.math.cos(angle) * radius).toFloat() * s
                        val y = (12 + kotlin.math.sin(angle) * radius).toFloat() * s
                        if(tooth == 0 && index == 0) gear.moveTo(x, y) else gear.lineTo(x, y)
                    }
                }
                gear.close()
                drawPath(gear, ink, style = Stroke(1.8f*s, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                drawCircle(ink, 3.2f*s, Offset(12*s,12*s), style = Stroke(1.8f*s))
            }
            Symbol.SUN -> {
                drawCircle(ink, 4*s, Offset(12*s,12*s), style = Stroke(1.8f*s))
                repeat(8) { ray ->
                    val angle = ray * kotlin.math.PI / 4
                    line((12 + kotlin.math.cos(angle)*7).toFloat(), (12 + kotlin.math.sin(angle)*7).toFloat(),
                        (12 + kotlin.math.cos(angle)*10).toFloat(), (12 + kotlin.math.sin(angle)*10).toFloat())
                }
            }
            Symbol.MOON -> {
                val moon = Path().apply {
                    moveTo(16*s,3*s)
                    cubicTo(3*s,-1*s,0*s,18*s,12*s,21*s)
                    cubicTo(17*s,22*s,21*s,18*s,21*s,14*s)
                    cubicTo(12*s,18*s,8*s,7*s,16*s,3*s)
                    close()
                }
                drawPath(moon, ink, style = Stroke(1.8f*s))
            }
            Symbol.SYSTEM -> { box(2f,3f,20f,14f,2f); line(12f,17f,12f,21f); line(7f,21f,17f,21f) }
            Symbol.MORE -> listOf(5f,12f,19f).forEach { drawCircle(ink,1.6f*s,Offset(it*s,12*s)) }
            Symbol.DOWN -> { line(6f,9f,12f,15f); line(12f,15f,18f,9f) }
            Symbol.BACK -> { line(14f,5f,7f,12f); line(7f,12f,14f,19f) }
            Symbol.SWAP -> { line(4f,8f,20f,8f); line(17f,5f,20f,8f); line(17f,11f,20f,8f); line(20f,16f,4f,16f); line(7f,13f,4f,16f); line(7f,19f,4f,16f) }
            Symbol.KEYBOARD -> { box(2f,5f,20f,14f); listOf(7f,12f,17f).forEach { x -> line(x,9f,x,9.2f); line(x,12f,x,12.2f) }; line(8f,16f,16f,16f) }
            Symbol.SEND -> { line(12f,20f,12f,4f); line(5f,11f,12f,4f); line(19f,11f,12f,4f) }
            Symbol.SEARCH -> { drawCircle(ink,7*s,Offset(10*s,10*s),style=Stroke(1.8f*s)); line(15f,15f,21f,21f) }
            Symbol.CLOSE -> { line(6f,6f,18f,18f); line(6f,18f,18f,6f) }
            Symbol.FOLDER -> { box(2f,7f,20f,14f); line(3f,7f,3f,3f); line(3f,3f,10f,3f); line(10f,3f,13f,7f) }
        }
    }
}
