package ai.visionmirror.design.components

import ai.visionmirror.design.tokens.Motion
import ai.visionmirror.design.tokens.Radius
import ai.visionmirror.design.tokens.Spacing
import ai.visionmirror.design.tokens.Vm
import ai.visionmirror.design.tokens.forMotion
import ai.visionmirror.haptics.LocalHaptics
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Brief / Standard / Detailed. The brass indicator is a single shape that glides between segments
 * on a spring (translation only, so no layout work), and re-targets with its velocity if you tap
 * again mid-flight. Radii are concentric: inner = outer - padding.
 *
 * At large font scales the labels would not fit side by side, so the control stacks vertically
 * (and the indicator glides vertically) instead of clipping text.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = LocalDensity.current.fontScale >= 1.5f || options.size > 4,
) {
    val colors = Vm.colors
    val reduce = Vm.reduceMotion
    val haptics = LocalHaptics.current
    val outer = if (vertical) Radius.md else Radius.lg
    val pad = 4.dp
    val innerShape = Radius.shape(Radius.inner(outer, pad))

    val position by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = Motion.settle<Float>().forMotion(reduce),
        label = "segmentIndicator",
    )

    val rowHeight = Spacing.preferredTouch
    val container = modifier
        .then(if (vertical) Modifier.height(rowHeight * options.size + pad * 2) else Modifier.height(rowHeight))
        .clip(Radius.shape(outer))
        .background(colors.surface)
        .border(if (colors.isHighContrast) 2.dp else 1.dp, colors.hairline, Radius.shape(outer))
        .padding(pad)
        .selectableGroup()

    @Composable
    fun Option(i: Int, label: String, mod: Modifier) {
        val textColor by animateColorAsState(
            targetValue = if (i == selectedIndex) colors.onBrass else colors.bone,
            animationSpec = Motion.fade(),
            label = "segmentText",
        )
        Box(
            modifier = mod
                .clip(innerShape)
                .selectable(
                    selected = i == selectedIndex,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.RadioButton,
                    onClick = {
                        if (i != selectedIndex) {
                            haptics.tick()
                            onSelect(i)
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(label, style = Vm.type.label.copy(color = textColor, textAlign = TextAlign.Center), maxLines = 2)
        }
    }

    Box(container) {
        // Indicator: one segment in size, moved by translation only.
        Box(
            Modifier
                .then(
                    if (vertical) Modifier.fillMaxWidth().fillMaxHeight(1f / options.size)
                    else Modifier.fillMaxHeight().fillMaxWidth(1f / options.size),
                )
                .graphicsLayer {
                    if (vertical) translationY = position * size.height else translationX = position * size.width
                }
                .clip(innerShape)
                .background(colors.brassRamp),
        )
        if (vertical) {
            Column(Modifier.fillMaxWidth().fillMaxHeight()) {
                options.forEachIndexed { i, label -> Option(i, label, Modifier.weight(1f).fillMaxWidth()) }
            }
        } else {
            Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                options.forEachIndexed { i, label -> Option(i, label, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}
