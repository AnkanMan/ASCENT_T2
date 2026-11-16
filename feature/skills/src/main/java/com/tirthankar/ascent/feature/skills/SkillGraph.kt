package com.tirthankar.ascent.feature.skills

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tirthankar.ascent.data.repo.SkillStatus
import com.tirthankar.ascent.designsystem.AscentColors
import com.tirthankar.ascent.designsystem.AscentTextStyles
import com.tirthankar.ascent.engine.SkillTree
import com.tirthankar.ascent.engine.UnlockCheck

/** Graph geometry in dp. */
internal object GraphMetrics {
    val NODE_W: Dp = 112.dp
    val NODE_H: Dp = 76.dp
    val LANE: Dp = 132.dp
    val TREE: Dp = 300.dp
    val ROW: Dp = 128.dp
    val PAD: Dp = 16.dp

    fun x(slot: NodeSlot): Dp = PAD + TREE * slot.treeIndex + LANE * slot.lane
    fun y(slot: NodeSlot): Dp = PAD + ROW * slot.row
    val width: Dp get() = PAD * 2 + TREE * SkillGraphLayout.TREES.size
    val height: Dp get() = PAD * 2 + ROW * 3 + NODE_H

    /** The x of a tree column, for the tree chips. */
    fun treeX(index: Int): Dp = TREE * index
}

/** How a node looks. */
internal enum class NodeLook { UNLOCKED, READY, LOCKED, SEALED }

internal fun SkillStatus.look(): NodeLook = when {
    def.tree == SkillTree.OBSERVER || def.locked -> NodeLook.SEALED
    unlocked -> NodeLook.UNLOCKED
    check == UnlockCheck.Ok -> NodeLook.READY
    else -> NodeLook.LOCKED
}

/**
 * The skill graph: three realm trees and the sealed Observer tree, with glowing links
 * between held skills. Drag to pan, pinch to zoom; [pan] and [zoom] are owned by the
 * screen so the tree chips can move the view.
 */
@Composable
internal fun SkillGraph(
    skills: List<SkillStatus>,
    pan: Offset,
    zoom: Float,
    onTransform: (pan: Offset, zoom: Float) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val slots = SkillGraphLayout.slots(skills.map { it.def })
    val byId = skills.associateBy { it.def.id }
    Box(
        modifier
            .clipToBounds()
            .pointerInput(Unit) { detectTransformGestures { _, p, z, _ -> onTransform(p, z) } },
    ) {
        Box(
            Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = pan.x
                    translationY = pan.y
                    scaleX = zoom
                    scaleY = zoom
                }
                .requiredSize(GraphMetrics.width, GraphMetrics.height),
        ) {
            Canvas(Modifier.requiredSize(GraphMetrics.width, GraphMetrics.height)) {
                skills.forEach { status ->
                    val to = slots[status.def.id] ?: return@forEach
                    status.def.prerequisites.forEach { pre ->
                        val from = slots[pre] ?: return@forEach
                        val start = Offset(
                            (GraphMetrics.x(from) + GraphMetrics.NODE_W / 2).toPx(),
                            (GraphMetrics.y(from) + GraphMetrics.NODE_H).toPx(),
                        )
                        val end = Offset((GraphMetrics.x(to) + GraphMetrics.NODE_W / 2).toPx(), GraphMetrics.y(to).toPx())
                        val held = byId[pre]?.unlocked == true && status.unlocked
                        val sealed = status.def.tree == SkillTree.OBSERVER
                        val color = when {
                            sealed -> AscentColors.ArcaneViolet.copy(alpha = 0.25f)
                            held -> AscentColors.GlowCyan
                            else -> AscentColors.SystemBlue.copy(alpha = 0.35f)
                        }
                        if (held) drawLine(color.copy(alpha = 0.25f), start, end, strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(color, start, end, strokeWidth = if (held) 2.dp.toPx() else 1.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
            }
            skills.forEach { status ->
                val slot = slots[status.def.id] ?: return@forEach
                SkillNode(
                    status,
                    onClick = { onSelect(status.def.id) },
                    modifier = Modifier.offset(GraphMetrics.x(slot), GraphMetrics.y(slot)),
                )
            }
        }
    }
}

@Composable
private fun SkillNode(status: SkillStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = status.look()
    val border = when (look) {
        NodeLook.UNLOCKED -> AscentColors.GlowCyan
        NodeLook.READY -> AscentColors.LegendGold
        NodeLook.LOCKED -> AscentColors.SystemBlue.copy(alpha = 0.5f)
        NodeLook.SEALED -> AscentColors.ArcaneViolet.copy(alpha = 0.5f)
    }
    val title = if (look == NodeLook.SEALED) "?" else status.def.name
    val sub = when (look) {
        NodeLook.SEALED -> stringResource(R.string.skills_observer_hint)
        NodeLook.UNLOCKED -> stringResource(R.string.skills_state_unlocked)
        NodeLook.READY -> stringResource(R.string.skills_state_ready_short)
        NodeLook.LOCKED -> stringResource(R.string.skills_state_locked)
    }
    val description = stringResource(R.string.skills_node_description, title, sub)
    Column(
        modifier
            .size(GraphMetrics.NODE_W, GraphMetrics.NODE_H)
            .alpha(if (look == NodeLook.LOCKED || look == NodeLook.SEALED) 0.55f else 1f)
            .background(AscentColors.PanelSolid)
            .border(if (look == NodeLook.UNLOCKED) 2.dp else 1.dp, border)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(6.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = AscentTextStyles.SystemLabel,
            color = if (look == NodeLook.UNLOCKED) AscentColors.GlowCyan else AscentColors.Mist,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(sub, style = AscentTextStyles.SystemLabel, color = border, maxLines = 1)
    }
}
