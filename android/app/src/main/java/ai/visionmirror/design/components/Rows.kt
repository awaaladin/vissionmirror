package ai.visionmirror.design.components

import ai.visionmirror.design.icons.VmIcons
import ai.visionmirror.design.tokens.Spacing
import ai.visionmirror.design.tokens.Vm
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 1 dp, slightly tinted line. Used instead of boxes around everything. */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Vm.colors.hairline))
}

/** One garment: what it is, then how it looks (colour is spoken in words, never a swatch alone). */
@Composable
fun OutfitRow(
    item: String,
    description: String,
    modifier: Modifier = Modifier,
    colourName: String? = null,
) {
    val colors = Vm.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.md).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(48.dp).background(colors.raised, CircleShape).border(1.dp, colors.hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) { VmIcon(VmIcons.Outfit, tint = colors.brass) }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            BasicText(item, style = Vm.type.label.copy(color = colors.bone))
            BasicText(
                text = if (colourName != null) "$colourName. $description" else description,
                style = Vm.type.body.copy(color = colors.boneMuted),
            )
        }
    }
}

enum class IssueSeverity(val spoken: String) {
    Low("Small thing"),
    Medium("Worth fixing"),
    High("Important"),
}

/**
 * A visible problem. Icon + words always: the severity is written out ("Important"), so the
 * Ember colour only reinforces it. High severity uses Ember; low/medium use Brass.
 */
@Composable
fun IssueRow(
    severity: IssueSeverity,
    what: String,
    where: String,
    suggestion: String,
    modifier: Modifier = Modifier,
) {
    val colors = Vm.colors
    val accent = if (severity == IssueSeverity.High) colors.ember else colors.brass
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.md).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(48.dp).border(2.dp, accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) { VmIcon(VmIcons.Warning, tint = accent) }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            BasicText("${severity.spoken}. $where", style = Vm.type.label.copy(color = accent))
            BasicText(what, style = Vm.type.body.copy(color = colors.bone))
            BasicText("Try: $suggestion", style = Vm.type.body.copy(color = colors.boneMuted))
        }
    }
}

/** How sure the AI is, in words first and a number second. The bar is decorative. */
@Composable
fun ConfidenceNote(confidence: Float, modifier: Modifier = Modifier) {
    val colors = Vm.colors
    val c = confidence.coerceIn(0f, 1f)
    val words = when {
        c >= 0.8f -> "I'm confident about this."
        c >= 0.5f -> "I'm fairly sure."
        else -> "I'm not very sure. Please double-check with someone you trust."
    }
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            VmIcon(VmIcons.Info, tint = colors.boneMuted, size = 24.dp)
            BasicText(words, style = Vm.type.body.copy(color = colors.boneMuted))
            BasicText("${(c * 100).toInt()}%", style = Vm.type.numeric.copy(color = colors.bone))
        }
        Box(
            Modifier.fillMaxWidth().height(4.dp).drawBehind {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(colors.hairline, size = size, cornerRadius = r)
                drawRoundRect(colors.boneMuted, size = Size(size.width * c, size.height), cornerRadius = r)
            },
        )
    }
}
