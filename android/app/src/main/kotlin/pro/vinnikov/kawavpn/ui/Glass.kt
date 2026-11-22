package pro.vinnikov.kawavpn.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/*
 * Liquid Glass after Glisse (github.com/0kzh/glisse), drawn natively with
 * Kyant Backdrop: the glass blurs and saturates what lies behind it, bends it
 * through a rounded bezel at the rim (Android 13+), carries a thin light on
 * the rim lit from the top left, and a finger press grows it with a spring
 * that overshoots on release, as iOS 26 glass buttons do.
 */

/** What glass samples: the app background (and the globe/pages for the tab bar). */
internal val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

internal enum class GlassTone { Regular, Primary, Clear, Danger }

private val bezel = Build.VERSION.SDK_INT >= 33

/** Glisse's dark glass: rgb(40 40 40 / .62) over the blurred backdrop, a 0.5px white 8% edge. */
private fun DrawScope.glassFill(tone: GlassTone, enabled: Boolean) {
    if (Palette.light) {
        // Light theme (desktop light): white frosted glass, the primary is the dark pill.
        when (tone) {
            GlassTone.Primary -> drawRect(Brush.verticalGradient(listOf(Color(0xF03A424E), Color(0xF01D232B), Color(0xF011161D))), alpha = if (enabled) 1f else .35f)
            GlassTone.Danger -> drawRect(Color(0xDDCE2C31))
            GlassTone.Clear -> drawRect(Brush.verticalGradient(listOf(Color(0x8CFFFFFF), Color(0x52FFFFFF))))
            GlassTone.Regular -> drawRect(Brush.verticalGradient(listOf(Color(0xD9FFFFFF), Color(0xB8F3F6FA))))
        }
        return
    }
    when (tone) {
        GlassTone.Primary -> drawRect(Brush.verticalGradient(listOf(Color(0xF7FFFFFF), Color(0xEBE4E8EE), Color(0xE6C9D0DA))), alpha = if (enabled) 1f else .35f)
        GlassTone.Danger -> drawRect(Color(0xCCB3261E))
        GlassTone.Clear -> drawRect(Brush.verticalGradient(listOf(Color(0x1FFFFFFF), Color(0x08FFFFFF))))
        GlassTone.Regular -> { drawRect(Color(0x9E1C1C1E)); drawRect(Brush.verticalGradient(listOf(Color(0x1AFFFFFF), Color(0x05FFFFFF)))) }
    }
}

/** Press state shared by the glass layer (scale, stretch) and its touch glow. */
@Stable internal class GlassPress {
    val scale = Animatable(1f)
    val stretch = Animatable(Offset.Zero, Offset.VectorConverter)
    val glow = Animatable(0f)
    var point by mutableStateOf(Offset.Unspecified)
}

@Composable internal fun rememberGlassPress() = remember { GlassPress() }

/**
 * Touch handling with the iOS 26 glass feel (Glisse press.ts): the longer side
 * grows by ~15dp on a spring (k 515, c 27.3), follows the finger with a rubber
 * band, and springs back past rest (k 250, c 11.8) when released.
 */
internal fun Modifier.glassPress(press: GlassPress, enabled: Boolean, onClick: () -> Unit): Modifier = if (!enabled) this else pointerInput(press, onClick) {
    val grow = 15.dp.toPx()
    val down = spring<Float>(dampingRatio = 27.3f / (2f * kotlin.math.sqrt(515f)), stiffness = 515f)
    val release = spring<Float>(dampingRatio = 11.8f / (2f * kotlin.math.sqrt(250f)), stiffness = 250f)
    val releaseOffset = spring<Offset>(dampingRatio = 11.8f / (2f * kotlin.math.sqrt(250f)), stiffness = 250f)
    coroutineScope {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false)
            val start = first.position
            press.point = start
            val longer = max(size.width, size.height).toFloat().coerceAtLeast(1f)
            launch { press.scale.animateTo(1f + grow / longer, down) }
            launch { press.glow.snapTo(1f) }
            var inside = true
            var released = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == first.id } ?: break
                press.point = change.position
                val dx = change.position.x - start.x
                val dy = change.position.y - start.y
                val length = hypot(dx, dy)
                val h = size.height.toFloat().coerceAtLeast(1f)
                val rubber = 1f - 1f / (1f + length / h / 5f)
                if (length > 0f) launch { press.stretch.snapTo(Offset(dx / length * 8.dp.toPx() * rubber, dy / length * 8.dp.toPx() * rubber)) }
                inside = change.position.x in -24f..(size.width + 24f) && change.position.y in -24f..(size.height + 24f)
                if (!change.pressed) { released = true; change.consume(); break }
            }
            launch { press.scale.animateTo(1f, release) }
            launch { press.stretch.animateTo(Offset.Zero, releaseOffset) }
            launch { press.glow.animateTo(0f, tween(220)) }
            if (released && inside) onClick()
        }
    }
}

/** The glass itself, shaped and lit; content is drawn by the caller on top. */
@Composable internal fun Modifier.liquidGlass(
    shape: RoundedCornerShape,
    tone: GlassTone = GlassTone.Regular,
    press: GlassPress? = null,
    enabled: Boolean = true,
    refraction: Dp = 14.dp,
    blur: Dp = 2.dp,
    elevated: Boolean = true,
): Modifier {
    val backdrop = LocalBackdrop.current
    val layer: (GraphicsLayerScope.() -> Unit)? = press?.let { p -> {
        val s = p.scale.value
        val stretch = p.stretch.value
        val horizontal = abs(stretch.x) > abs(stretch.y)
        val amount = 1f + (hypot(stretch.x, stretch.y) / size.height.coerceAtLeast(1f)) * .12f
        scaleX = s * (if (horizontal) amount else 1f / amount)
        scaleY = s * (if (horizontal) 1f / amount else amount)
        translationX = stretch.x; translationY = stretch.y
    } }
    if (backdrop == null) {
        // Previews and tests without a backdrop: the same look, without sampling.
        val base = if (layer != null) graphicsLayer(layer) else this
        return base.clip(shape).drawBehind { drawRect(if (Palette.light) Color.White else Color(0xFF161618)); glassFill(tone, enabled) }.border(.6.dp, Brush.linearGradient(listOf(Color(0x66FFFFFF), Color(0x14FFFFFF), Color(0x40FFFFFF))), shape)
    }
    val rim = when (tone) { GlassTone.Primary -> .85f; GlassTone.Clear -> .45f; else -> .5f }
    val fallbackEdge = if (bezel) this else border(.6.dp, Brush.linearGradient(listOf(Color(0x59FFFFFF), Color(0x0FFFFFFF), Color(0x33FFFFFF))), shape)
    return fallbackEdge.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(blur.toPx())
            lens(refraction.toPx(), (refraction * 2f).toPx(), chromaticAberration = true)
        },
        highlight = { Highlight(width = .6.dp, style = HighlightStyle.Default(color = Color.White.copy(alpha = rim), angle = 45f, falloff = 1f)) },
        shadow = if (elevated) ({ Shadow(radius = 18.dp, offset = DpOffset(0.dp, 6.dp), color = Color.Black.copy(alpha = if (Palette.light) .12f else .45f)) }) else null,
        layerBlock = layer,
        onDrawSurface = {
            glassFill(tone, enabled)
            press?.let { p ->
                val glow = p.glow.value
                if (glow > 0f && p.point.isSpecified) drawRect(brush = Brush.radialGradient(colors = listOf(Color.White.copy(alpha = .16f * glow), Color.White.copy(alpha = .06f * glow), Color.Transparent), center = p.point, radius = 140.dp.toPx()))
            }
        },
    )
}

/** Pill button: primary is the white glass of the main action, the rest dark glass. */
@Composable internal fun GlassButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, icon: KawaIcon? = null, height: Dp = 52.dp, fontSize: Int = 14, onClick: () -> Unit) {
    val press = rememberGlassPress()
    val haptic = LocalHapticFeedback.current
    val ink = if (!enabled) Muted else if (primary) OnPrimary else Ink
    Box(modifier.heightIn(min = height)
        .liquidGlass(RoundedCornerShape(50), if (primary) GlassTone.Primary else GlassTone.Regular, press, enabled)
        .glassPress(press, enabled) { haptic.performHapticFeedback(HapticFeedbackType.VirtualKey); onClick() }
        .semantics { role = Role.Button; if (!enabled) disabled(); onClick(text) { if (enabled) onClick(); enabled } }
        .padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon?.let { KawaIconView(it, ink, Modifier.size(20.dp)) }
            androidx.compose.foundation.text.BasicText(text, style = androidx.compose.ui.text.TextStyle(color = ink, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, softWrap = false,
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = fontSize.sp))
        }
    }
}

/** Round glass button holding one icon (header actions, globe reset, row menus). */
@Composable internal fun GlassIconButton(icon: KawaIcon, label: String, modifier: Modifier = Modifier, size: Dp = 40.dp, tint: Color = Ink, enabled: Boolean = true, clear: Boolean = false, onClick: () -> Unit) {
    val press = rememberGlassPress()
    val haptic = LocalHapticFeedback.current
    Box(modifier.size(size)
        .liquidGlass(RoundedCornerShape(50), if (clear) GlassTone.Clear else GlassTone.Regular, press, enabled, refraction = 8.dp, elevated = !clear)
        .glassPress(press, enabled) { haptic.performHapticFeedback(HapticFeedbackType.VirtualKey); onClick() }
        .semantics { role = Role.Button; contentDescription = label; onClick(label) { if (enabled) onClick(); enabled } },
        contentAlignment = Alignment.Center) { KawaIconView(icon, if (enabled) tint else Muted, Modifier.size(size * .5f)) }
}

/** Filter chip: selected chips are the white glass, as on the desktop. */
@Composable internal fun GlassChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val press = rememberGlassPress()
    Box(modifier.height(38.dp)
        .liquidGlass(RoundedCornerShape(50), if (selected) GlassTone.Primary else GlassTone.Clear, press, refraction = 8.dp, elevated = selected)
        .glassPress(press, true, onClick)
        .semantics { role = Role.Tab; this.selected = selected; onClick(text) { onClick(); true } }
        .padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (selected) OnPrimary else Ink, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

/** Card / panel glass (desktop GlassPanel). */
@Composable internal fun GlassCard(modifier: Modifier = Modifier, padding: Dp = 18.dp, radius: Dp = 28.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.liquidGlass(RoundedCornerShape(radius), GlassTone.Regular, refraction = 18.dp, blur = 6.dp).padding(padding), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

/**
 * The app's backdrop: black, as on the desktop, with a faint smoky light so the
 * glass has something to bend at its rims.
 */
@Composable internal fun AmbientBackground(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Base)
        if (Palette.light) {
            drawRect(Brush.radialGradient(listOf(Color(0x55FFFFFF), Color.Transparent), Offset(size.width * .15f, size.height * .08f), size.width * .9f))
            drawRect(Brush.radialGradient(listOf(Color(0x22A9B8CC), Color.Transparent), Offset(size.width * .95f, size.height * .55f), size.width * .8f))
            return@Canvas
        }
        drawRect(Brush.radialGradient(listOf(Color(0x24B9C3D0), Color.Transparent), Offset(size.width * .15f, size.height * .08f), size.width * .9f))
        drawRect(Brush.radialGradient(listOf(Color(0x1A8E99A8), Color.Transparent), Offset(size.width * .95f, size.height * .55f), size.width * .8f))
        drawRect(Brush.radialGradient(listOf(Color(0x14FFFFFF), Color.Transparent), Offset(size.width * .3f, size.height * .98f), size.width * .7f))
    }
}

/** The desktop's icons (same SVG outlines, 24×24). */
internal enum class KawaIcon(val path: String, val filled: Boolean = false, val dots: List<Offset> = emptyList()) {
    Home("M3.5 10 12 3l8.5 7v10a1 1 0 0 1-1 1h-5v-7h-5v7h-5a1 1 0 0 1-1-1z"),
    Servers("M6 3.5h12a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2zM6 13.5h12a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2zM12 7h5M12 17h5", dots = listOf(Offset(7.5f, 7f), Offset(7.5f, 17f))),
    Statistics("M4 19.5h16M7 15.5v-5m5 5V4.5m5 11v-8"),
    Settings("M12 2.8 13.79 2.98 14.77 5.3 16.03 5.97 18.51 5.49 19.65 6.89 18.7 9.23 19.11 10.59 21.2 12 21.02 13.79 18.7 14.77 18.03 16.03 18.51 18.51 17.11 19.65 14.77 18.7 13.41 19.11 12 21.2 10.21 21.02 9.23 18.7 7.97 18.03 5.49 18.51 4.35 17.11 5.3 14.77 4.89 13.41 2.8 12 2.98 10.21 5.3 9.23 5.97 7.97 5.49 5.49 6.89 4.35 9.23 5.3 10.59 4.89ZM15.1 12a3.1 3.1 0 1 1-6.2 0 3.1 3.1 0 1 1 6.2 0z"),
    Report("M5 21V3m0 1c4-4 9 4 14 0v10c-5 4-10-4-14 0"),
    Refresh("M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z", filled = true),
    Star("m12 2 3 6 7 1-5 5 1 7-6-3-6 3 1-7-5-5 7-1z"),
    StarFilled("m12 2 3 6 7 1-5 5 1 7-6-3-6 3 1-7-5-5 7-1z", filled = true),
    Chevron("m9 5 7 7-7 7"),
    More("M6 10c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm12 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm-6 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z", filled = true),
    Shield("M12 3l8 3v6c0 4-4 7-8 9-4-2-8-5-8-9V6z"),
    Search("M17 10a7 7 0 1 1-14 0 7 7 0 1 1 14 0zM15 15l6 6"),
    Speed("m20.38 8.57-1.23 1.85a8 8 0 0 1-.22 7.58H5.07A8 8 0 0 1 15.58 6.85l1.85-1.23A10 10 0 0 0 3.35 19a2 2 0 0 0 1.72 1h13.85a2 2 0 0 0 1.74-1 10 10 0 0 0-.27-10.44zm-9.79 6.84a2 2 0 0 0 2.83 0l5.66-8.49-8.49 5.66a2 2 0 0 0 0 2.83z", filled = true),
    Add("M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z", filled = true),
    Close("M6 6l12 12M18 6 6 18"),
}

private val iconPaths = HashMap<KawaIcon, Path>()

@Composable internal fun KawaIconView(icon: KawaIcon, tint: Color = LocalContentColor.current, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val path = iconPaths.getOrPut(icon) { PathParser().parsePathString(icon.path).toPath() }
        val k = size.minDimension / 24f
        val dx = (size.width - 24f * k) / 2f
        val dy = (size.height - 24f * k) / 2f
        withTransform({ translate(dx, dy); scale(k, k, Offset.Zero) }) {
            if (icon.filled) drawPath(path, tint)
            if (!icon.filled || icon == KawaIcon.StarFilled) drawPath(path, tint, style = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            icon.dots.forEach { drawCircle(tint, .9f, it) }
        }
    }
}
