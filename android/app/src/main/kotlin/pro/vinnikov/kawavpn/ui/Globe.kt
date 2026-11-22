package pro.vinnikov.kawavpn.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import pro.vinnikov.kawavpn.domain.ConnectionState
import pro.vinnikov.kawavpn.vpn.TunnelStatus
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import kotlinx.coroutines.launch

private data class V(val x: Float, val y: Float, val z: Float) {
    operator fun plus(b: V) = V(x + b.x, y + b.y, z + b.z)
    operator fun times(n: Float) = V(x * n, y * n, z * n)
    fun dot(b: V) = x * b.x + y * b.y + z * b.z
    fun cross(b: V) = V(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x)
    fun unit() = this * (1f / sqrt(dot(this)).coerceAtLeast(.00001f))
}
private data class Q(val w: Float = 1f, val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    operator fun times(b: Q) = Q(w * b.w - x * b.x - y * b.y - z * b.z, w * b.x + x * b.w + y * b.z - z * b.y, w * b.y - x * b.z + y * b.w + z * b.x, w * b.z + x * b.y - y * b.x + z * b.w)
    fun unit(): Q { val n = sqrt(w * w + x * x + y * y + z * z).coerceAtLeast(.00001f); return Q(w / n, x / n, y / n, z / n) }
    fun rotate(v: V): V { val u = V(x, y, z); return v + u.cross(v) * (2f * w) + u.cross(u.cross(v)) * 2f }
}
/** The desktop's resting pose (globe.rs home()): Europe and Russia facing, north up. */
private val HOME = (Q(cos(.175f), sin(.175f), 0f, 0f) * Q(cos(-.15f), 0f, sin(-.15f), 0f)).unit()
private val ROUTE: Color get() = if (Palette.light) Color(0xFF16966E) else Color(0xFF4ACFA8)
private val ARRIVAL: Color get() = if (Palette.light) Color(0xFF128C66) else Color(0xFF3DCAA0)
private fun between(a: V, b: V): Q { val cross = a.cross(b); return Q(1 + a.dot(b), cross.x, cross.y, cross.z).unit() }
private fun vector(lat: Float, lon: Float): V { val a = lat * PI / 180; val b = lon * PI / 180; return V((cos(a) * sin(b)).toFloat(), sin(a).toFloat(), (cos(a) * cos(b)).toFloat()) }
private fun place(code: String?): V? = when (code) {
    "RU" -> vector(61f, 90f); "DE" -> vector(51f, 10f); "NL" -> vector(52f, 5f); "FI" -> vector(64f, 26f)
    "US" -> vector(39f, -98f); "GB" -> vector(55f, -3f); "FR" -> vector(46f, 2f); "PL" -> vector(52f, 19f)
    "TR" -> vector(39f, 35f); "SE" -> vector(62f, 15f); "JP" -> vector(36f, 138f); "SG" -> vector(1f, 104f)
    "CA" -> vector(56f, -106f); "KZ" -> vector(48f, 68f); "UA" -> vector(49f, 32f); else -> null
}
private fun nlerp(a: Q, b: Q, t: Float): Q {
    val d = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
    val c = if (d < 0) Q(-b.w, -b.x, -b.y, -b.z) else c0(b)
    return Q(a.w + (c.w - a.w) * t, a.x + (c.x - a.x) * t, a.y + (c.y - a.y) * t, a.z + (c.z - a.z) * t).unit()
}
private fun c0(q: Q) = q
private fun slerp(a: V, b: V, t: Float): V {
    val angle = acos(a.dot(b).coerceIn(-1f, 1f))
    if (angle < .001f) return (a * (1 - t) + b * t).unit()
    return a * (sin((1 - t) * angle) / sin(angle)) + b * (sin(t * angle) / sin(angle))
}

@Composable internal fun Globe(status: TunnelStatus, motion: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val points = remember {
        context.assets.open("globe-points.bin").use { input ->
            val buffer = ByteBuffer.wrap(input.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val spaced=ArrayList<V>()
            repeat(buffer.remaining()/8) {
                val point=vector(buffer.float,buffer.float)
                // Keep a consistent angular gap instead of crowding the poles.
                if(spaced.none { it.dot(point)>.9981f }) spaced.add(point)
            }
            spaced
        }
    }
    var rotation by remember { mutableStateOf(HOME) }
    val origin = remember(status.originCountry) { place(status.originCountry) }
    val destination = remember(status.exitCountry) { place(status.exitCountry) }
    // Projection is reused while only the reveal animation changes.
    val projected = remember(points,rotation) { points.map { rotation.rotate(it) }.filter { it.z>0f } }
    // Great-circle arc on the surface, as the desktop draws it; endpoints are countries, not locations.
    val route = remember(origin,destination) { if(origin!=null && destination!=null) List(161) { slerp(origin,destination,it/160f) } else emptyList() }
    val connected = status.state == ConnectionState.CONNECTED
    val reveal = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val turn = remember { Animatable(1f) }
    var from by remember { mutableStateOf(HOME) }
    // Back to the desktop pose with an eased turn (the desktop's go_home()).
    fun reset() { if (!motion) { rotation = HOME; return }; from = rotation; scope.launch { turn.snapTo(0f); turn.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) { rotation = nlerp(from, HOME, value) } } }
    LaunchedEffect(connected, origin, destination, motion) {
        reveal.snapTo(0f)
        if (connected && origin != null && destination != null) reveal.animateTo(1f, tween(if (motion) 1400 else 0, easing = FastOutSlowInEasing))
    }
    // A light runs along the finished route (desktop: route phase).
    val phase by rememberInfiniteTransition(label = "route").animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "phase")
    Box(modifier) {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Трёхмерный глобус. Маршрут показывает страны, а не точное местоположение." }.pointerInput(Unit) {
            fun ball(position: Offset): V {
                val radius = min(size.width, size.height) * .42f
                val x = (position.x - size.width / 2) / radius
                val y = -(position.y - size.height / 2) / radius
                val d = x * x + y * y
                return if (d <= 1) V(x, y, sqrt(1 - d)) else V(x, y, 0f).unit()
            }
            var previous = V(0f, 0f, 1f)
            detectDragGestures(onDragStart = { previous = ball(it) }) { change, _ ->
                val next = ball(change.position)
                rotation = (between(previous, next) * rotation).unit()
                previous = next; change.consume()
            }
        }) {
            val radius = size.minDimension * .42f
            val center = center
            // Static soft pool under the sphere (same as desktop): no animation.
            val pool = Size(radius * 1.25f, radius * .14f)
            val poolTop = Offset(center.x - pool.width / 2, center.y + radius * 1.08f - pool.height / 2)
            drawOval(Brush.radialGradient(listOf((if (Palette.light) Color.Black else Color.White).copy(alpha = .20f), (if (Palette.light) Color.Black else Color.White).copy(alpha = .06f), Color.Transparent), poolTop + Offset(pool.width / 2, pool.height / 2), pool.width / 2), poolTop, pool)
            val light = Palette.light
            drawCircle(if (light) Brush.radialGradient(listOf(Color(0xFFFFFFFF), Color(0xFFE9EDF2), Color(0xFFD5DCE4)), center - Offset(radius * .3f, radius * .4f), radius * 1.8f)
                else Brush.radialGradient(listOf(Color(0xFF272729), Color(0xFF111112), Color(0xFF060607)), center - Offset(radius * .3f, radius * .4f), radius * 1.8f), radius, center)
            drawCircle(if (light) Color(0xFFB0B8C2) else Color(0xFF333437), radius, center, style = Stroke(1.dp.toPx()))
            val dotInk = if (light) Color(0xFF3A4350) else Color.White
            projected.forEach { p ->
                if (p.z > 0) drawCircle(dotInk.copy(alpha = (.12f + p.z * .48f) * (p.z * 12).coerceAtMost(1f)), radius * .010f, center + Offset(p.x, -p.y) * radius)
            }
            if (connected && origin != null && destination != null) {
                fun at(v: V): Pair<Offset, Float> { val p = rotation.rotate(v); return (center + Offset(p.x, -p.y) * radius) to ((p.z - .06f) / .2f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) } }
                // Soft halo, then a crisp thin line, fading at the horizon.
                for (pass in 0..1) {
                    var previous: Offset? = null
                    for (i in route.indices) {
                        if (i / 160f > reveal.value) break
                        val (position, fade) = at(route[i])
                        if (fade > 0f) {
                            previous?.let { if (pass == 0) drawLine(ROUTE.copy(alpha = .16f * fade), it, position, radius * .05f, StrokeCap.Round) else drawLine(ROUTE.copy(alpha = .92f * fade), it, position, radius * .011f, StrokeCap.Round) }
                            previous = position
                        } else previous = null
                    }
                }
                if (reveal.value >= 1f && motion) {
                    val (head, fade) = at(slerp(origin, destination, phase))
                    if (fade > 0f) { drawCircle(Color(0xFFD7F9EC).copy(alpha = .25f * fade), radius * .03f, head); drawCircle(Color(0xFFEEFFF9).copy(alpha = .85f * fade), radius * .012f, head) }
                }
                (if(origin.dot(destination)>.9999f) listOf(Triple(origin, 0f, if (Palette.light) Color(0xFF3A424E) else Color(0xFFDEDEDE))) else listOf(Triple(origin, 0f, if (Palette.light) Color(0xFF3A424E) else Color(0xFFDEDEDE)), Triple(destination, 1f, ARRIVAL))).forEach { (v, start, color) ->
                    val (position, fade) = at(v)
                    if (fade > 0f && reveal.value >= start) {
                        drawCircle(color.copy(alpha = .10f * fade), radius * .038f, position)
                        drawCircle(color.copy(alpha = .22f * fade), radius * .021f, position)
                        drawCircle(color.copy(alpha = fade), radius * .012f, position)
                    }
                }
            }
        }
        GlassIconButton(KawaIcon.Refresh,"Вернуть маршрут в центр",Modifier.align(Alignment.TopEnd),size=38.dp,tint=Muted,clear=true) { reset() }
    }
}
