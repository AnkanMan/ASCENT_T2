package com.tirthankar.ascent.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Rarity colours (SPEC "Equipment"): grey, blue, violet, gold, red-black. Index by rarity order. */
object RarityColors {
    val Common = AscentColors.Mist.copy(alpha = 0.65f)
    val Rare = AscentColors.SystemBlue
    val Epic = AscentColors.ArcaneViolet
    val Legendary = AscentColors.LegendGold
    val Mythic = AscentColors.PenaltyRed

    private val ordered = listOf(Common, Rare, Epic, Legendary, Mythic)

    /** 0 = Common … 4 = Mythic. */
    fun forLevel(level: Int): Color = ordered[level.coerceIn(0, ordered.lastIndex)]
}

/** One item in the chest reveal. */
data class ChestItem(val name: String, val detail: String, val color: Color)

/**
 * The chest opening (Task 23): a seal of light splits, a burst in the best item's
 * colour, then each item appears in its rarity colour. Instant with reduced motion.
 */
@Composable
fun ChestRevealOverlay(
    header: String,
    items: List<ChestItem>,
    dismissLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val dim = remember { Animatable(if (reduced) 0.9f else 0f) }
    val open = remember { Animatable(if (reduced) 1f else 0f) }
    val burst = remember { Animatable(if (reduced) 1f else 0f) }
    var shown by remember { mutableIntStateOf(if (reduced) items.size else 0) }
    val best = items.maxByOrNull { RarityRank.of(it.color) }?.color ?: AscentColors.GlowCyan
    val particles = remember { List(24) { Random.nextFloat() to (0.5f + 0.5f * Random.nextFloat()) } }

    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        dim.animateTo(0.9f, tween(180))
        open.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        launch { burst.animateTo(1f, tween(900, easing = LinearOutSlowInEasing)) }
        items.indices.forEach {
            delay(260)
            shown = it + 1
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AscentColors.Void.copy(alpha = dim.value))
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = shown == items.size, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // The seal: two halves of a light bar sliding apart.
            val barW = size.width * 0.6f
            val barH = 6.dp.toPx()
            val gap = barW / 2 * open.value
            drawRect(best.copy(alpha = 1f - open.value * 0.6f), Offset(center.x - barW / 2 - gap, center.y - 140.dp.toPx()), Size(barW / 2, barH))
            drawRect(best.copy(alpha = 1f - open.value * 0.6f), Offset(center.x + gap, center.y - 140.dp.toPx()), Size(barW / 2, barH))
            if (burst.value in 0.001f..0.999f) {
                val maxR = size.minDimension * 0.42f
                particles.forEach { (angle, speed) ->
                    val a = angle * 2 * PI
                    val d = maxR * speed * burst.value
                    drawCircle(best.copy(alpha = 1f - burst.value), 3.dp.toPx(), Offset(center.x + d * cos(a).toFloat(), center.y - 140.dp.toPx() + d * sin(a).toFloat()))
                }
            }
        }
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HoloPanel(Modifier.fillMaxWidth(), header = header, accent = best) {
                items.take(shown).forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(12.dp).background(item.color))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(item.name, style = MaterialTheme.typography.bodyLarge, color = item.color)
                            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = AscentColors.Mist.copy(alpha = 0.8f))
                        }
                    }
                }
                if (shown == items.size) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.padding(top = 12.dp).heightIn(min = 48.dp).align(Alignment.End)) {
                        Text(dismissLabel, color = best)
                    }
                }
            }
        }
    }
}

/** Orders colours by rarity so the reveal glows in the best item's colour. */
private object RarityRank {
    fun of(color: Color): Int = listOf(RarityColors.Common, RarityColors.Rare, RarityColors.Epic, RarityColors.Legendary, RarityColors.Mythic)
        .indexOf(color).coerceAtLeast(0)
}

/**
 * A System notice (achievement, lore line, chapter, Gate, boss): a glitch-in panel with
 * an uppercase header, a subject and optional detail. Tap anywhere to dismiss.
 */
@Composable
fun SystemNoticeOverlay(
    header: String,
    subject: String,
    detail: String?,
    accent: Color,
    dismissLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AscentColors.Void.copy(alpha = 0.6f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Materialize(Modifier.padding(24.dp)) {
            HoloPanel(Modifier.fillMaxWidth(), header = header, accent = accent) {
                Text(
                    subject,
                    style = MaterialTheme.typography.titleLarge,
                    color = AscentColors.Mist,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
                detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist.copy(alpha = 0.85f), modifier = Modifier.padding(top = 8.dp)) }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp).align(Alignment.End)) {
                    Text(dismissLabel, color = accent)
                }
            }
        }
    }
}

/**
 * A boss HP bar with phase markers (SPEC "Boss hit"): on each hit it shakes 6 dp and the
 * lost chunk flashes and breaks off. Instant with reduced motion.
 */
@Composable
fun BossHpBar(
    label: String,
    hp: Int,
    maxHp: Int,
    phaseMarkers: List<Float>,
    modifier: Modifier = Modifier,
    height: Dp = 18.dp,
) {
    val reduced = LocalReducedMotion.current
    val fraction = if (maxHp <= 0) 0f else hp.toFloat() / maxHp
    var previous by remember { mutableStateOf(fraction) }
    val chunk = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }
    var chunkFrom by remember { mutableStateOf(fraction) }
    LaunchedEffect(fraction) {
        if (fraction < previous && !reduced) {
            chunkFrom = previous
            chunk.snapTo(1f)
            launch {
                repeat(3) {
                    shake.animateTo(6f, tween(30))
                    shake.animateTo(-6f, tween(30))
                }
                shake.animateTo(0f, tween(30))
            }
            chunk.animateTo(0f, tween(500))
        }
        previous = fraction
    }
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "$label $hp / $maxHp" }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label.uppercase(), style = AscentTextStyles.SystemLabel, color = AscentColors.PenaltyRed)
            Text("$hp / $maxHp", style = AscentTextStyles.Number, color = AscentColors.Mist)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .padding(top = 4.dp)
                .graphicsLayer { translationX = shake.value.dp.toPx() }
                .border(1.dp, AscentColors.PenaltyRed.copy(alpha = 0.6f)),
        ) {
            drawRect(AscentColors.PenaltyRed.copy(alpha = 0.12f))
            drawRect(
                Brush.horizontalGradient(listOf(AscentColors.PenaltyRed.copy(alpha = 0.7f), AscentColors.PenaltyRed)),
                size = Size(size.width * fraction, size.height),
            )
            if (chunk.value > 0f && chunkFrom > fraction) {
                val start = size.width * fraction
                val w = size.width * (chunkFrom - fraction)
                drawRect(
                    AscentColors.LegendGold.copy(alpha = chunk.value),
                    topLeft = Offset(start, (1f - chunk.value) * size.height * 0.6f),
                    size = Size(w, size.height),
                )
            }
            phaseMarkers.forEach { m ->
                val x = size.width * m
                drawLine(AscentColors.Mist.copy(alpha = 0.8f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
        }
    }
}

/**
 * One objective with a long title: the title wraps on its own, the count sits right,
 * and a thin bar shows the fraction. TalkBack reads "[title] [valueText]".
 */
@Composable
fun ObjectiveProgress(title: String, valueText: String, fraction: Float, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$title $valueText" }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = AscentColors.Mist, modifier = Modifier.weight(1f))
            Text(valueText, style = AscentTextStyles.Number, color = AscentColors.GlowCyan, modifier = Modifier.padding(start = 8.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(4.dp).padding(top = 0.dp)) {
            drawRect(AscentColors.GlowCyan.copy(alpha = 0.15f))
            drawRect(AscentColors.GlowCyan, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
        }
    }
}

/** Gear slots the avatar draws. Feature code maps its item slots to these. */
enum class AvatarSlot { WEAPON, HEAD, BODY, HANDS, FEET, ACCESSORY }

/**
 * The hooded silhouette (D12): drawn in Canvas, each equipped slot adds an overlay in
 * its rarity colour, and Mythic gear adds aura particles. The aura colour follows rank.
 */
@Composable
fun AvatarFigure(
    gear: Map<AvatarSlot, Color>,
    aura: Color,
    mythicAura: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val drift = remember { Animatable(0f) }
    val sparks = remember { List(18) { Random.nextFloat() to Random.nextFloat() } }
    LaunchedEffect(mythicAura, reduced) {
        if (!mythicAura || reduced) return@LaunchedEffect
        while (true) {
            drift.snapTo(0f)
            drift.animateTo(1f, tween(4_000))
        }
    }
    Canvas(modifier.semantics { this.contentDescription = contentDescription }) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        // Aura glow behind the figure.
        drawCircle(Brush.radialGradient(listOf(aura.copy(alpha = 0.35f), Color.Transparent), Offset(cx, h * 0.45f), w * 0.55f), w * 0.55f, Offset(cx, h * 0.45f))
        if (mythicAura) {
            sparks.forEach { (x, y) ->
                val yy = (y - (if (reduced) 0f else drift.value)).let { if (it < 0) it + 1 else it }
                drawCircle(RarityColors.Mythic.copy(alpha = 0.8f), 2.5.dp.toPx(), Offset(w * (0.15f + 0.7f * x), h * yy))
            }
        }
        val body = AscentColors.PanelSolid
        val edge = AscentColors.SystemBlue.copy(alpha = 0.7f)
        // Hood and head.
        val hood = Path().apply {
            moveTo(cx, h * 0.06f)
            cubicTo(cx + w * 0.2f, h * 0.06f, cx + w * 0.22f, h * 0.28f, cx + w * 0.16f, h * 0.34f)
            lineTo(cx - w * 0.16f, h * 0.34f)
            cubicTo(cx - w * 0.22f, h * 0.28f, cx - w * 0.2f, h * 0.06f, cx, h * 0.06f)
            close()
        }
        // Cloaked body.
        val cloak = Path().apply {
            moveTo(cx - w * 0.16f, h * 0.32f)
            lineTo(cx + w * 0.16f, h * 0.32f)
            lineTo(cx + w * 0.3f, h * 0.86f)
            lineTo(cx - w * 0.3f, h * 0.86f)
            close()
        }
        drawPath(cloak, body)
        drawPath(cloak, edge, style = Stroke(1.5.dp.toPx()))
        drawPath(hood, body)
        drawPath(hood, edge, style = Stroke(1.5.dp.toPx()))
        // Slot overlays.
        gear[AvatarSlot.HEAD]?.let { drawPath(hood, it, style = Stroke(3.dp.toPx())) }
        gear[AvatarSlot.BODY]?.let { drawPath(cloak, it.copy(alpha = 0.35f)); drawPath(cloak, it, style = Stroke(2.5.dp.toPx())) }
        gear[AvatarSlot.HANDS]?.let { c ->
            drawRoundRect(c, Offset(cx - w * 0.29f, h * 0.6f), Size(w * 0.08f, h * 0.07f), CornerRadius(4.dp.toPx()))
            drawRoundRect(c, Offset(cx + w * 0.21f, h * 0.6f), Size(w * 0.08f, h * 0.07f), CornerRadius(4.dp.toPx()))
        }
        gear[AvatarSlot.FEET]?.let { c ->
            drawRect(c, Offset(cx - w * 0.2f, h * 0.87f), Size(w * 0.14f, h * 0.05f))
            drawRect(c, Offset(cx + w * 0.06f, h * 0.87f), Size(w * 0.14f, h * 0.05f))
        }
        gear[AvatarSlot.WEAPON]?.let { c ->
            drawLine(c, Offset(cx + w * 0.34f, h * 0.2f), Offset(cx + w * 0.28f, h * 0.8f), strokeWidth = 4.dp.toPx())
        }
        gear[AvatarSlot.ACCESSORY]?.let { c -> drawCircle(c, 5.dp.toPx(), Offset(cx, h * 0.42f)) }
        // Eyes in the hood's shadow.
        drawCircle(aura, 2.dp.toPx(), Offset(cx - w * 0.05f, h * 0.22f))
        drawCircle(aura, 2.dp.toPx(), Offset(cx + w * 0.05f, h * 0.22f))
    }
}
