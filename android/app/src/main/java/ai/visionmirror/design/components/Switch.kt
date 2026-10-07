package ai.visionmirror.design.components

import ai.visionmirror.design.icons.VmIcons
import ai.visionmirror.design.tokens.Motion
import ai.visionmirror.design.tokens.Spacing
import ai.visionmirror.design.tokens.Vm
import ai.visionmirror.design.tokens.forMotion
import ai.visionmirror.haptics.LocalHaptics
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Custom switch: a pill track and a thumb that glides on a spring. State is never colour alone:
 * the thumb carries a check mark when on.
 */
@Composable
fun VmSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = Vm.colors
    val reduce = Vm.reduceMotion
    val travel by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = Motion.press<Float>().forMotion(reduce),
        label = "switchThumb",
    )
    val track by animateColorAsState(if (checked) colors.brass else colors.raised, Motion.fade(), label = "switchTrack")
    val thumb by animateColorAsState(if (checked) colors.onBrass else colors.bone, Motion.fade(), label = "switchThumbColor")
    Box(
        modifier
            .size(width = 68.dp, height = 40.dp)
            .background(track, CircleShape)
            .border(if (colors.isHighContrast) 2.dp else 1.dp, if (checked) colors.champagne else colors.hairline, CircleShape)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .graphicsLayer { translationX = travel * 28.dp.toPx() }
                .background(thumb, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) VmIcon(VmIcons.Check, tint = colors.brass, size = 20.dp)
        }
    }
}

/** A labelled on/off row. The whole row is the target, and TalkBack hears "Switch, on/off". */
@Composable
fun SettingToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val haptics = LocalHaptics.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Spacing.preferredTouch)
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onValueChange = {
                    haptics.tick()
                    onCheckedChange(it)
                },
            )
            .padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            BasicText(label, style = Vm.type.label.copy(color = Vm.colors.bone))
            if (description != null) BasicText(description, style = Vm.type.body.copy(color = Vm.colors.boneMuted))
        }
        VmSwitch(checked)
    }
}

/** Group title + content, divided by a hairline instead of a box. */
@Composable
fun SettingGroup(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        HairlineDivider()
        BasicText(title, style = Vm.type.title.copy(color = Vm.colors.brass))
        content()
    }
}
