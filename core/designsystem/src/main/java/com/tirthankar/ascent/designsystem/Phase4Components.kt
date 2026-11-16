package com.tirthankar.ascent.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the layered avatar draws (PHASE4.md section 5). Plain values so any screen can
 * build one: [aura] is a token name from content/avatar.json, [cloak] and [sigil] are
 * class layer ids, [gear] maps slot names (WEAPON, HEAD, …) to rarity order.
 */
data class AvatarSpec(
    val aura: String,
    val particles: Int,
    val rings: Int,
    val cloak: String? = null,
    val sigil: String? = null,
    /** 0 base, 1 evolved (B), 2 final (S): evolutions brighten the class layer. */
    val stage: Int = 0,
    val gear: Map<String, Int> = emptyMap(),
)

/**
 * The layered avatar, back to front: aura and particles, orbit rings, the hooded
 * silhouette, the class layer (cloak cut and chest sigil), gear. All shapes original,
 * no letters. Particles drift slowly; static with reduced motion.
 */
@Composable
fun LayeredAvatar(spec: AvatarSpec, contentDescription: String, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val drift = if (reduced) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "aura")
        val value by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(9_000, easing = LinearEasing), RepeatMode.Restart), label = "drift")
        value
    }
    val aura = AuraPalette.forToken(spec.aura)
    val seeds = remember(spec.particles) { List(spec.particles) { i -> (i * 0.618034f) % 1f to ((i * 0.381966f + 0.17f) % 1f) } }
    Canvas(modifier.semantics { this.contentDescription = contentDescription }) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h * 0.47f
        // 1. Aura glow and particles.
        drawCircle(Brush.radialGradient(listOf(aura.copy(alpha = 0.42f), Color.Transparent), Offset(cx, cy), w * 0.62f), w * 0.62f, Offset(cx, cy))
        seeds.forEach { (a, r) ->
            val angle = 2 * PI * (a + drift * (0.3f + 0.4f * r))
            val radius = w * (0.18f + 0.32f * r)
            drawCircle(
                aura.copy(alpha = 0.35f + 0.5f * r),
                (1.2f + 1.6f * r).dp.toPx(),
                Offset(cx + radius * cos(angle).toFloat(), cy + radius * 0.8f * sin(angle).toFloat()),
            )
        }
        // 2. Orbit rings, one per ten levels.
        repeat(spec.rings.coerceIn(0, 10)) { i ->
            val rx = w * (0.30f + 0.02f * i)
            val ry = h * (0.07f + 0.004f * i)
            drawOval(
                aura.copy(alpha = 0.18f + 0.05f * (i % 3)),
                Offset(cx - rx, h * (0.62f - 0.035f * i) - ry),
                Size(2 * rx, 2 * ry),
                style = Stroke(0.8.dp.toPx()),
            )
        }
        // 3. Silhouette, with the class's cloak cut.
        val body = AscentColors.PanelSolid
        val edge = AscentColors.SystemBlue.copy(alpha = 0.7f)
        val hood = hoodPath(cx, w, h)
        val cloak = cloakPath(spec.cloak, cx, w, h)
        drawPath(cloak, body)
        drawPath(cloak, edge, style = Stroke(1.5.dp.toPx()))
        drawPath(hood, body)
        drawPath(hood, edge, style = Stroke(1.5.dp.toPx()))
        // 4. Class layer: the cloak edge and chest sigil glow in the aura, brighter when evolved.
        if (spec.cloak != null) {
            val glow = aura.copy(alpha = 0.45f + 0.25f * spec.stage.coerceIn(0, 2))
            drawPath(cloak, glow, style = Stroke((1.5f + spec.stage).dp.toPx()))
        }
        spec.sigil?.let { drawSigil(it, Offset(cx, h * 0.5f), w * 0.09f, aura.copy(alpha = 0.6f + 0.2f * spec.stage.coerceIn(0, 2))) }
        // 5. Gear.
        val gear = spec.gear.mapNotNull { (slot, rarity) -> runCatching { AvatarSlot.valueOf(slot) }.getOrNull()?.let { it to RarityColors.forLevel(rarity) } }.toMap()
        gear[AvatarSlot.HEAD]?.let { drawPath(hood, it, style = Stroke(3.dp.toPx())) }
        gear[AvatarSlot.BODY]?.let { drawPath(cloak, it.copy(alpha = 0.3f)); drawPath(cloak, it, style = Stroke(2.5.dp.toPx())) }
        gear[AvatarSlot.HANDS]?.let { c ->
            drawRoundRect(c, Offset(cx - w * 0.29f, h * 0.6f), Size(w * 0.08f, h * 0.07f), CornerRadius(4.dp.toPx()))
            drawRoundRect(c, Offset(cx + w * 0.21f, h * 0.6f), Size(w * 0.08f, h * 0.07f), CornerRadius(4.dp.toPx()))
        }
        gear[AvatarSlot.FEET]?.let { c ->
            drawRect(c, Offset(cx - w * 0.2f, h * 0.87f), Size(w * 0.14f, h * 0.05f))
            drawRect(c, Offset(cx + w * 0.06f, h * 0.87f), Size(w * 0.14f, h * 0.05f))
        }
        gear[AvatarSlot.WEAPON]?.let { c -> drawLine(c, Offset(cx + w * 0.34f, h * 0.2f), Offset(cx + w * 0.28f, h * 0.8f), strokeWidth = 4.dp.toPx()) }
        gear[AvatarSlot.ACCESSORY]?.let { c -> drawCircle(c, 5.dp.toPx(), Offset(cx, h * 0.38f)) }
        // Eyes in the hood's shadow.
        drawCircle(aura, 2.dp.toPx(), Offset(cx - w * 0.05f, h * 0.22f))
        drawCircle(aura, 2.dp.toPx(), Offset(cx + w * 0.05f, h * 0.22f))
    }
}

private fun hoodPath(cx: Float, w: Float, h: Float) = Path().apply {
    moveTo(cx, h * 0.06f)
    cubicTo(cx + w * 0.2f, h * 0.06f, cx + w * 0.22f, h * 0.28f, cx + w * 0.16f, h * 0.34f)
    lineTo(cx - w * 0.16f, h * 0.34f)
    cubicTo(cx - w * 0.22f, h * 0.28f, cx - w * 0.2f, h * 0.06f, cx, h * 0.06f)
    close()
}

/** Six original cloak cuts (avatar.json `cloaks`); null is the plain onboarding cloak. */
private fun cloakPath(cut: String?, cx: Float, w: Float, h: Float) = Path().apply {
    val top = h * 0.32f
    val hem = h * 0.86f
    when (cut) {
        "long_split" -> { // split down the front to the hem
            moveTo(cx - w * 0.16f, top); lineTo(cx + w * 0.16f, top); lineTo(cx + w * 0.32f, hem + h * 0.04f)
            lineTo(cx + w * 0.03f, hem + h * 0.04f); lineTo(cx, h * 0.62f); lineTo(cx - w * 0.03f, hem + h * 0.04f)
            lineTo(cx - w * 0.32f, hem + h * 0.04f)
        }
        "layered_short" -> { // two tiers, shorter hem
            moveTo(cx - w * 0.16f, top); lineTo(cx + w * 0.16f, top); lineTo(cx + w * 0.26f, h * 0.58f)
            lineTo(cx + w * 0.22f, h * 0.6f); lineTo(cx + w * 0.28f, h * 0.8f); lineTo(cx - w * 0.28f, h * 0.8f)
            lineTo(cx - w * 0.22f, h * 0.6f); lineTo(cx - w * 0.26f, h * 0.58f)
        }
        "heavy_shouldered" -> { // broad square shoulders
            moveTo(cx - w * 0.3f, top + h * 0.02f); lineTo(cx + w * 0.3f, top + h * 0.02f); lineTo(cx + w * 0.3f, h * 0.42f)
            lineTo(cx + w * 0.3f, hem); lineTo(cx - w * 0.3f, hem); lineTo(cx - w * 0.3f, h * 0.42f)
        }
        "plain_wrap" -> { // narrow, straight
            moveTo(cx - w * 0.15f, top); lineTo(cx + w * 0.15f, top); lineTo(cx + w * 0.22f, hem); lineTo(cx - w * 0.22f, hem)
        }
        "travel_hooded" -> { // asymmetric hem for walking
            moveTo(cx - w * 0.16f, top); lineTo(cx + w * 0.16f, top); lineTo(cx + w * 0.3f, h * 0.78f)
            lineTo(cx, hem); lineTo(cx - w * 0.3f, h * 0.9f)
        }
        "panelled" -> { // three panels, a stepped hem
            moveTo(cx - w * 0.16f, top); lineTo(cx + w * 0.16f, top); lineTo(cx + w * 0.3f, hem); lineTo(cx + w * 0.1f, hem)
            lineTo(cx + w * 0.1f, hem + h * 0.03f); lineTo(cx - w * 0.1f, hem + h * 0.03f); lineTo(cx - w * 0.1f, hem); lineTo(cx - w * 0.3f, hem)
        }
        else -> {
            moveTo(cx - w * 0.16f, top); lineTo(cx + w * 0.16f, top); lineTo(cx + w * 0.3f, hem); lineTo(cx - w * 0.3f, hem)
        }
    }
    close()
}

/** Six geometric chest sigils (avatar.json `sigils`): circles, lines and points, never letters. */
private fun DrawScope.drawSigil(id: String, c: Offset, r: Float, color: Color) {
    val s = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round)
    when (id) {
        "circle_bisected" -> { drawCircle(color, r, c, style = s); drawLine(color, Offset(c.x - r, c.y), Offset(c.x + r, c.y), s.width) }
        "nested_circles" -> { drawCircle(color, r, c, style = s); drawCircle(color, r * 0.55f, c, style = s); drawCircle(color, r * 0.15f, c) }
        "triangle_in_circle" -> {
            drawCircle(color, r, c, style = s)
            val p = Path().apply {
                repeat(3) { i ->
                    val a = 2 * PI * i / 3 - PI / 2
                    val x = c.x + r * 0.8f * cos(a).toFloat()
                    val y = c.y + r * 0.8f * sin(a).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            drawPath(p, color, style = s)
        }
        "point_and_ring" -> { drawCircle(color, r, c, style = s); drawCircle(color, r * 0.2f, c) }
        "crossing_paths" -> {
            drawLine(color, Offset(c.x - r, c.y - r * 0.6f), Offset(c.x + r, c.y + r * 0.6f), s.width)
            drawLine(color, Offset(c.x - r, c.y + r * 0.6f), Offset(c.x + r, c.y - r * 0.6f), s.width)
            drawCircle(color, r * 0.18f, c)
        }
        "six_points" -> repeat(6) { i ->
            val a = 2 * PI * i / 6
            drawCircle(color, r * 0.16f, Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()))
        }
    }
}

/**
 * The rank-up sequence (SPEC: "the new rank glyph is forged from light"), about 3.5 s:
 * lines of light converge, the rank glyph forms and flares, then the avatar in its new
 * aura and Continue. Instant with reduced motion.
 */
@Composable
fun RankUpOverlay(
    rank: String,
    accent: Color,
    header: String,
    message: String,
    dismissLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    avatar: @Composable () -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    val dim = remember { Animatable(if (reduced) 0.92f else 0f) }
    val converge = remember { Animatable(if (reduced) 1f else 0f) }
    val glyph = remember { Animatable(if (reduced) 1f else 0f) }
    val flash = remember { Animatable(0f) }
    val reveal = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        dim.animateTo(0.92f, tween(300))
        converge.animateTo(1f, tween(1_300, easing = FastOutSlowInEasing))
        glyph.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
        flash.animateTo(1f, tween(120))
        flash.animateTo(0f, tween(380))
        reveal.animateTo(1f, tween(500))
    }
    Box(
        modifier
            .fillMaxSize()
            .background(AscentColors.Void.copy(alpha = dim.value))
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = reveal.value >= 1f, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val glyphCenter = Offset(center.x, center.y - 160.dp.toPx())
            val lines = 12
            repeat(lines) { i ->
                val a = 2 * PI * i / lines
                val far = size.maxDimension * 0.75f
                val near = 46.dp.toPx()
                val d = far + (near - far) * converge.value
                val start = Offset(glyphCenter.x + d * cos(a).toFloat(), glyphCenter.y + d * sin(a).toFloat())
                val end = Offset(glyphCenter.x + (d + 40.dp.toPx() * (1 - converge.value)) * cos(a).toFloat(), glyphCenter.y + (d + 40.dp.toPx() * (1 - converge.value)) * sin(a).toFloat())
                if (converge.value < 1f) drawLine(accent.copy(alpha = 0.8f), start, end, 2.dp.toPx(), StrokeCap.Round)
            }
            if (glyph.value > 0f) {
                drawCircle(accent.copy(alpha = 0.18f * glyph.value + 0.5f * flash.value), 70.dp.toPx() * (0.6f + 0.4f * glyph.value), glyphCenter)
                drawCircle(accent.copy(alpha = glyph.value), 58.dp.toPx(), glyphCenter, style = Stroke(2.dp.toPx()))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(24.dp)) {
            Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                Text(
                    rank,
                    style = AscentTextStyles.NumberLarge.copy(fontSize = 64.sp),
                    color = accent,
                    modifier = Modifier.alpha(glyph.value).scale(0.6f + 0.4f * glyph.value),
                )
            }
            Box(Modifier.size(width = 120.dp, height = 170.dp).alpha(reveal.value)) { avatar() }
            Text(
                header,
                style = AscentTextStyles.SystemHeader,
                color = accent,
                modifier = Modifier.alpha(reveal.value).semantics { liveRegion = LiveRegionMode.Assertive },
            )
            Text(message, style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist, textAlign = TextAlign.Center, modifier = Modifier.alpha(reveal.value))
            if (reveal.value >= 1f) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(dismissLabel, color = accent) }
            }
        }
    }
}

/**
 * The `◆ NEW RECORD` toast (PHASE4.md section 6): the lore line types itself out, waits,
 * then [onFinished]. It never blocks touches. With reduced motion the text appears whole.
 */
@Composable
fun RecordToast(header: String, title: String, text: String, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val finish by rememberUpdatedState(onFinished)
    var shown by remember(title, text) { mutableIntStateOf(if (reduced) text.length else 0) }
    LaunchedEffect(title, text) {
        if (!reduced) {
            while (shown < text.length) {
                delay(TYPE_MS)
                shown++
            }
        }
        delay(RECORD_HOLD_MS)
        finish()
    }
    HoloPanel(modifier.fillMaxWidth(), header = header, accent = AscentColors.ArcaneViolet) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = AscentColors.LegendGold)
        Text(
            text.take(shown),
            style = MaterialTheme.typography.bodyMedium,
            color = AscentColors.Mist,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "$title. $text" },
        )
    }
}

private const val TYPE_MS = 18L
private const val RECORD_HOLD_MS = 2_600L

@Preview(name = "Layered avatar — E to S", widthDp = 420, heightDp = 320)
@Composable
private fun LayeredAvatarPreview() {
    AscentTheme {
        SystemBackground {
            androidx.compose.foundation.layout.Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    AvatarSpec("FAINT_GREY_BLUE", 8, 0),
                    AvatarSpec("SYSTEM_BLUE", 13, 1, "long_split", "circle_bisected"),
                    AvatarSpec("GLOW_CYAN", 20, 2, "heavy_shouldered", "triangle_in_circle"),
                    AvatarSpec("ARCANE_VIOLET", 30, 4, "panelled", "six_points", stage = 1, gear = mapOf("BODY" to 3, "WEAPON" to 2)),
                    AvatarSpec("LEGEND_GOLD", 44, 7, "travel_hooded", "crossing_paths", stage = 1),
                    AvatarSpec("WHITE_BLUE_BLAZE", 58, 10, "plain_wrap", "point_and_ring", stage = 2),
                ).forEach { LayeredAvatar(it, "Avatar", Modifier.size(width = 64.dp, height = 140.dp)) }
            }
        }
    }
}

@Preview(name = "Rank up", widthDp = 400, heightDp = 760)
@Composable
private fun RankUpPreview() {
    AscentTheme {
        RankUpOverlay(
            rank = "C",
            accent = AscentColors.GlowCyan,
            header = "RANK UP",
            message = "Metastable. A deeper well holds you now.",
            dismissLabel = "Continue",
            onDismiss = {},
            avatar = { LayeredAvatar(AvatarSpec("GLOW_CYAN", 20, 2, "long_split", "circle_bisected"), "Avatar", Modifier.fillMaxSize()) },
        )
    }
}

@Preview(name = "New record toast", widthDp = 400, heightDp = 200)
@Composable
private fun RecordToastPreview() {
    AscentTheme {
        SystemBackground {
            RecordToast("◆ NEW RECORD", "First excited state", "You left the ground state and stayed out.", onFinished = {}, modifier = Modifier.padding(16.dp))
        }
    }
}
