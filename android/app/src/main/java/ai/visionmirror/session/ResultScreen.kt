package ai.visionmirror.session

import ai.visionmirror.audio.Speaker
import ai.visionmirror.audio.rememberSpeechLevel
import ai.visionmirror.data.api.DetailLevel
import ai.visionmirror.data.api.Severity
import ai.visionmirror.data.api.Verdict
import ai.visionmirror.design.components.ConfidenceNote
import ai.visionmirror.design.components.GhostAction
import ai.visionmirror.design.components.HairlineDivider
import ai.visionmirror.design.components.IconAction
import ai.visionmirror.design.components.IssueRow
import ai.visionmirror.design.components.IssueSeverity
import ai.visionmirror.design.components.ListeningOverlay
import ai.visionmirror.design.components.OutfitRow
import ai.visionmirror.design.components.PrimaryAction
import ai.visionmirror.design.components.SegmentedControl
import ai.visionmirror.design.components.SpokenErrorBanner
import ai.visionmirror.design.components.StatusCaption
import ai.visionmirror.design.components.VmIcon
import ai.visionmirror.design.components.reveal
import ai.visionmirror.design.halo.Halo
import ai.visionmirror.design.halo.HaloState
import ai.visionmirror.design.icons.VmIcons
import ai.visionmirror.design.tokens.Spacing
import ai.visionmirror.design.tokens.Vm
import ai.visionmirror.ui.ScreenScaffold
import ai.visionmirror.ui.highlightIn
import ai.visionmirror.ui.holdToTalk
import ai.visionmirror.ui.sharedHalo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The description, read aloud as soon as it arrives and shown in large type with the spoken word
 * highlighted. Press and hold anywhere to ask a question. Groups reveal in meaning order:
 * summary, outfit, issues, colours.
 */
@Composable
fun ResultScreen(
    session: SessionViewModel,
    speaker: Speaker,
    onRetake: () -> Unit,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val speech by speaker.state.collectAsStateWithLifecycle()
    val mic = session.micLevel.collectAsStateWithLifecycle()
    val speechLevel = rememberSpeechLevel(speaker)

    LaunchedEffect(Unit) {
        session.events.collect { onRetake() } // retake, or the photo expired: both mean "back to the mirror"
    }

    val haloState = when {
        state.ask == AskPhase.Listening -> HaloState.Listening
        state.ask == AskPhase.Thinking || state.refreshing -> HaloState.Analysing
        speech.isSpeaking -> HaloState.Speaking
        state.phase == Phase.Unusable -> HaloState.Idle
        else -> HaloState.Ready
    }

    ScreenScaffold {
        Box(Modifier.fillMaxSize().holdToTalk(onStart = session::startAsking, onEnd = session::finishAsking)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                SpokenErrorBanner(
                    message = state.banner.orEmpty(),
                    visible = state.banner != null,
                    modifier = Modifier.fillMaxWidth(),
                )

                Halo(
                    state = haloState,
                    level = { if (haloState == HaloState.Listening) mic.value else speechLevel() },
                    contentDescription = null,
                    modifier = Modifier.sharedHalo().size(180.dp),
                ) { PhotoInHalo(state.photo, Modifier.size(width = 84.dp, height = 112.dp)) }

                StatusCaption(
                    text = state.displayText,
                    highlight = speech.highlightIn(state.displayText),
                    announce = false, // spoken aloud already; TalkBack must not talk over it
                    modifier = Modifier.fillMaxWidth().reveal(0),
                )

                Controls(state, speech.isSpeaking, speech.isPaused, session)

                SegmentedControl(
                    options = DetailLevel.entries.map { it.label },
                    selectedIndex = state.detail.ordinal,
                    onSelect = { session.changeDetail(DetailLevel.entries[it]) },
                    modifier = Modifier.fillMaxWidth().alpha(if (state.refreshing) 0.6f else 1f),
                )

                if (state.phase == Phase.Unusable) {
                    PrimaryAction("Take another photo", session::retake, Modifier.fillMaxWidth(), icon = VmIcons.Retake)
                } else {
                    Details(state)
                    state.result?.let { ConfidenceNote(it.confidence, Modifier.reveal(8)) }
                }

                Conversation(state.conversation)

                BasicText(
                    "Press and hold anywhere to ask a question.",
                    style = Vm.type.body.copy(color = Vm.colors.boneMuted),
                )
            }

            if (state.ask != AskPhase.Idle) AskOverlay(state, { mic.value }, session)
        }
    }
}

@Composable
private fun Controls(state: SessionUiState, speaking: Boolean, paused: Boolean, session: SessionViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        if (paused) {
            IconAction(VmIcons.Play, "Resume reading", session::pauseOrResume)
        } else {
            IconAction(VmIcons.Pause, "Pause reading", session::pauseOrResume, enabled = speaking)
        }
        IconAction(VmIcons.Repeat, "Repeat", session::repeat, enabled = state.displayText.isNotBlank())
        IconAction(VmIcons.Ask, "Ask a question", session::startAsking, enabled = state.phase == Phase.Result)
        IconAction(VmIcons.Retake, "Retake photo", session::retake)
    }
}

/** Outfit, then issues, then colours: the order that matters most to her. */
@Composable
private fun Details(state: SessionUiState) {
    val r = state.result ?: return
    var i = 1 // index 0 is the caption
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (r.outfit.isNotEmpty()) {
            Heading("What you're wearing", Modifier.reveal(i++))
            r.outfit.forEach { item ->
                OutfitRow(item.item.replaceFirstChar { it.uppercase() }, item.description, Modifier.reveal(i++), item.color)
                HairlineDivider()
            }
        }
        if (r.hairAndGrooming.isNotBlank()) {
            OutfitRow("Hair and grooming", r.hairAndGrooming, Modifier.reveal(i++))
            HairlineDivider()
        }

        Heading("Things to fix", Modifier.reveal(i++))
        if (r.issues.isEmpty()) {
            BasicText(
                "Nothing needs fixing. You look good to go.",
                style = Vm.type.body.copy(color = Vm.colors.bone),
                modifier = Modifier.padding(vertical = Spacing.sm).reveal(i++),
            )
        } else {
            r.issues.forEach { issue ->
                IssueRow(
                    severity = when (issue.severity) {
                        Severity.Low -> IssueSeverity.Low
                        Severity.Medium -> IssueSeverity.Medium
                        Severity.High -> IssueSeverity.High
                    },
                    what = issue.what, where = issue.where, suggestion = issue.suggestion,
                    modifier = Modifier.reveal(i++),
                )
                HairlineDivider()
            }
        }

        Heading("Colours", Modifier.reveal(i++))
        Row(
            Modifier.fillMaxWidth().padding(vertical = Spacing.md).reveal(i++),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            VmIcon(VmIcons.Palette, tint = Vm.colors.brass)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                BasicText(
                    when (r.colourHarmony.verdict) {
                        Verdict.Good -> "These colours work well together."
                        Verdict.Mixed -> "The colours are a mixed match."
                        Verdict.Clashing -> "Some of these colours clash."
                        Verdict.Unsure -> "I'm not sure how well the colours match."
                    },
                    style = Vm.type.label.copy(color = Vm.colors.bone),
                )
                if (r.colourHarmony.explanation.isNotBlank()) {
                    BasicText(r.colourHarmony.explanation, style = Vm.type.body.copy(color = Vm.colors.boneMuted))
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text,
        style = Vm.type.title.copy(color = Vm.colors.brass),
        modifier = modifier.padding(top = Spacing.sm, bottom = Spacing.xxs),
    )
}

@Composable
private fun Conversation(items: List<QA>) {
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        HairlineDivider()
        Heading("You asked")
        items.forEach { qa ->
            BasicText(qa.question, style = Vm.type.label.copy(color = Vm.colors.boneMuted))
            BasicText(qa.answer, style = Vm.type.body.copy(color = Vm.colors.bone))
        }
    }
}

/** While she speaks: Quartz Halo rippling with the mic. Afterwards: a calm shimmer while thinking. */
@Composable
private fun AskOverlay(state: SessionUiState, level: () -> Float, session: SessionViewModel) {
    val colors = Vm.colors
    Box(Modifier.fillMaxSize()) {
        if (state.ask == AskPhase.Listening) {
            ListeningOverlay(
                level = level,
                caption = state.partialQuestion.ifBlank { "Listening. Let go when you're done." },
            )
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                PrimaryAction("Done", session::finishAsking, Modifier.fillMaxWidth(), icon = VmIcons.Check)
                GhostAction("Cancel", session::cancelAsking, Modifier.fillMaxWidth(), icon = VmIcons.Close)
            }
        } else {
            Box(Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    Halo(HaloState.Analysing, contentDescription = "Thinking", modifier = Modifier.size(220.dp))
                    StatusCaption("“${state.partialQuestion}”", announce = false)
                    BasicText("Let me look.", style = Vm.type.title.copy(color = colors.bone))
                }
            }
        }
    }
}
