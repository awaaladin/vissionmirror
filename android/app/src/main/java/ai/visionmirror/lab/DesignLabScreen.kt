package ai.visionmirror.lab

import ai.visionmirror.audio.Earcon
import ai.visionmirror.audio.EarconPlayer
import ai.visionmirror.audio.SpeechManager
import ai.visionmirror.audio.say
import ai.visionmirror.audio.rememberSpeechLevel
import ai.visionmirror.design.components.EmptyState
import ai.visionmirror.design.components.ErrorState
import ai.visionmirror.design.components.GhostAction
import ai.visionmirror.design.components.HairlineDivider
import ai.visionmirror.design.components.IconAction
import ai.visionmirror.design.components.IssueRow
import ai.visionmirror.design.components.IssueSeverity
import ai.visionmirror.design.components.ConfidenceNote
import ai.visionmirror.design.components.ListeningOverlay
import ai.visionmirror.design.components.MirrorFrame
import ai.visionmirror.design.components.OutfitRow
import ai.visionmirror.design.components.PermissionCard
import ai.visionmirror.design.components.PressableSurface
import ai.visionmirror.design.components.PrimaryAction
import ai.visionmirror.design.components.SegmentedControl
import ai.visionmirror.design.components.SpokenErrorBanner
import ai.visionmirror.design.components.StatusCaption
import ai.visionmirror.design.components.VmIcon
import ai.visionmirror.design.components.reveal
import ai.visionmirror.design.halo.Halo
import ai.visionmirror.design.halo.HaloState
import ai.visionmirror.design.icons.VmIcons
import ai.visionmirror.design.tokens.Motion
import ai.visionmirror.design.tokens.Radius
import ai.visionmirror.design.tokens.Spacing
import ai.visionmirror.design.tokens.Vm
import ai.visionmirror.design.tokens.ThemeMode
import ai.visionmirror.design.tokens.VisionMirrorTheme
import ai.visionmirror.haptics.HapticsManager
import ai.visionmirror.haptics.proximityIntervalMs
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private const val SAMPLE_TEXT =
    "You're wearing a navy cotton shirt and light grey trousers. Your collar is folded under on the left side."

/**
 * Debug-only kitchen sink: every component in every state. Use the three controls at the top to
 * check reduce-motion, High Contrast and 200% font scale without leaving the screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesignLabScreen(speech: SpeechManager, haptics: HapticsManager, earcons: EarconPlayer) {
    var reduceMotion by rememberSaveable { mutableStateOf(false) }
    var highContrast by rememberSaveable { mutableStateOf(false) }
    var themeIndex by rememberSaveable { mutableIntStateOf(ThemeMode.Dark.ordinal) }
    var fontScale by rememberSaveable { mutableFloatStateOf(1f) }

    val baseDensity = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale)) {
        VisionMirrorTheme(themeMode = ThemeMode.entries[themeIndex], highContrast = highContrast, reduceMotion = reduceMotion, haptics = haptics) {
            val colors = Vm.colors
            Box(Modifier.fillMaxSize().background(colors.ink)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xl),
                ) {
                    item { LabHeader() }
                    item {
                        Controls(
                            themeIndex, { themeIndex = it },
                            reduceMotion, { reduceMotion = it },
                            highContrast, { highContrast = it },
                            fontScale, { fontScale = it },
                        )
                    }
                    item { HaloSection(speech) }
                    item { ActionsSection(haptics) }
                    item { SegmentedSection() }
                    item { CaptionSection(speech) }
                    item { RowsSection() }
                    item { MirrorSection() }
                    item { StatesSection() }
                    item { MotionSection() }
                    item { FeedbackSection(haptics, earcons) }
                    item { IconsSection() }
                    item { TokensSection() }
                }
            }
        }
    }
}

// ---- chrome ----------------------------------------------------------------------------------

@Composable
private fun LabHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        BasicText("Design Lab", style = Vm.type.display.copy(color = Vm.colors.bone))
        BasicText("Debug only. Every component, every state.", style = Vm.type.body.copy(color = Vm.colors.boneMuted))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        HairlineDivider()
        BasicText(title, style = Vm.type.title.copy(color = Vm.colors.brass))
        content()
    }
}

@Composable
private fun Caption(text: String) =
    BasicText(text, style = Vm.type.body.copy(color = Vm.colors.boneMuted))

@Composable
private fun Controls(
    themeIndex: Int, onTheme: (Int) -> Unit,
    reduce: Boolean, onReduce: (Boolean) -> Unit,
    contrast: Boolean, onContrast: (Boolean) -> Unit,
    fontScale: Float, onFontScale: (Float) -> Unit,
) {
    val colors = Vm.colors
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SegmentedControl(ThemeMode.entries.map { it.label }, themeIndex, onTheme, Modifier.fillMaxWidth())
        ToggleRow("Reduce motion", reduce, onReduce)
        ToggleRow("High contrast", contrast, onContrast)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            BasicText("Font scale", style = Vm.type.body.copy(color = colors.bone))
            BasicText("${(fontScale * 100).toInt()}%", style = Vm.type.numeric.copy(color = colors.brass))
        }
        Slider(
            value = fontScale, onValueChange = { onFontScale((Math.round(it * 20) / 20f)) },
            valueRange = 0.85f..2f,
            colors = SliderDefaults.colors(
                thumbColor = colors.brass, activeTrackColor = colors.brass, inactiveTrackColor = colors.hairline,
            ),
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = Vm.colors
    Row(Modifier.fillMaxWidth().height(Spacing.preferredTouch), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, style = Vm.type.body.copy(color = colors.bone), modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onBrass, checkedTrackColor = colors.brass,
                uncheckedThumbColor = colors.bone, uncheckedTrackColor = colors.raised,
                uncheckedBorderColor = colors.hairline,
            ),
        )
    }
}

// ---- sections --------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HaloSection(speech: SpeechManager) {
    var state by remember { mutableStateOf<HaloState>(HaloState.Idle) }
    val speechState by speech.state.collectAsStateWithLifecycle()
    val speechLevel = rememberSpeechLevel(speech)

    // Simulated signal for Guiding / Listening / Speaking (stands in for framing quality and mic RMS).
    val sim by rememberInfiniteTransition(label = "sim").animateFloat(
        0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "simLevel",
    )
    val live = speechState.isSpeaking
    val shown: HaloState = if (live) HaloState.Speaking else state

    Section("Halo") {
        Halo(
            state = shown,
            level = { if (live) speechLevel() else if (state == HaloState.Idle || state == HaloState.Analysing || state == HaloState.Ready) 0f else sim },
            contentDescription = "Halo, ${shown::class.simpleName}",
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        ) {
            VmIcon(VmIcons.Mirror, tint = Vm.colors.bone, size = 56.dp)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            listOf(
                "Idle" to HaloState.Idle, "Guiding" to HaloState.Guiding, "Speaking" to HaloState.Speaking,
                "Listening" to HaloState.Listening, "Analysing" to HaloState.Analysing, "Ready" to HaloState.Ready,
            ).forEach { (name, s) ->
                GhostAction(name, onClick = { state = s }, contentDescription = "Halo state $name")
            }
        }
        Caption("Tap Ready repeatedly to replay the bloom. Guiding/Listening/Speaking use a simulated signal here.")
    }
}

@Composable
private fun ActionsSection(haptics: HapticsManager) {
    var taps by remember { mutableIntStateOf(0) }
    Section("Actions") {
        PrimaryAction("Continue", onClick = { taps++ }, Modifier.fillMaxWidth(), icon = VmIcons.Check)
        PrimaryAction("Disabled", onClick = {}, Modifier.fillMaxWidth(), enabled = false)
        GhostAction("Retake photo", onClick = { taps++ }, Modifier.fillMaxWidth(), icon = VmIcons.Retake)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            IconAction(VmIcons.Play, "Play", { taps++ })
            IconAction(VmIcons.Pause, "Pause", { taps++ }, selected = true)
            IconAction(VmIcons.Repeat, "Repeat", { taps++ })
            IconAction(VmIcons.Mic, "Ask a question", { taps++ })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Taps", style = Vm.type.body.copy(color = Vm.colors.boneMuted))
            BasicText("$taps", style = Vm.type.numeric.copy(color = Vm.colors.bone))
        }
        Caption("Press and hold: it gives, then springs back. Tap quickly twice: it keeps its velocity.")
    }
}

@Composable
private fun SegmentedSection() {
    var selected by remember { mutableIntStateOf(1) }
    Section("Segmented control") {
        SegmentedControl(listOf("Brief", "Standard", "Detailed"), selected, { selected = it }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun CaptionSection(speech: SpeechManager) {
    val state by speech.state.collectAsStateWithLifecycle()
    Section("Status caption") {
        StatusCaption(
            text = SAMPLE_TEXT,
            highlight = if (state.isSpeaking && state.text == SAMPLE_TEXT) state.wordRange else null,
            announce = false,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            IconAction(VmIcons.Speaker, "Speak the sample", { speech.say(SAMPLE_TEXT, SpeechManager.Priority.Interrupt) })
            IconAction(
                if (state.isPaused) VmIcons.Play else VmIcons.Pause,
                if (state.isPaused) "Resume" else "Pause",
                { if (state.isPaused) speech.resume() else speech.pause() },
            )
            IconAction(VmIcons.Close, "Stop speaking", { speech.stop() })
        }
        StatusCaption("Move a little left.", announce = true)
    }
}

@Composable
private fun RowsSection() {
    var shown by remember { mutableStateOf(true) }
    Section("Outfit, issues, confidence") {
        GhostAction("Replay reveal", onClick = { shown = false; shown = true })
        Column(Modifier.fillMaxWidth()) {
            OutfitRow("Shirt", "Short-sleeved cotton, plain.", Modifier.reveal(0, shown), colourName = "Navy")
            HairlineDivider()
            OutfitRow("Trousers", "Straight leg, slightly creased.", Modifier.reveal(1, shown), colourName = "Light grey")
            HairlineDivider()
            IssueRow(IssueSeverity.High, "Collar is folded under.", "Left side of the neck", "Flip it out gently.", Modifier.reveal(2, shown))
            HairlineDivider()
            IssueRow(IssueSeverity.Low, "A loose thread.", "Right cuff", "Pinch it off or trim it.", Modifier.reveal(3, shown))
        }
        ConfidenceNote(0.86f)
        ConfidenceNote(0.62f)
        ConfidenceNote(0.31f)
    }
}

@Composable
private fun MirrorSection() {
    var quality by remember { mutableFloatStateOf(0.2f) }
    val animated by animateFloatAsState(quality, Motion.gentle(), label = "frameQuality")
    Section("Mirror frame") {
        MirrorFrame(
            modifier = Modifier.fillMaxWidth(0.7f).aspectRatio(0.78f),
            glow = { animated },
        ) {
            BasicText("Camera", style = Vm.type.body.copy(color = Vm.colors.boneMuted))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            GhostAction("Poor", { quality = 0.1f })
            GhostAction("Good", { quality = 1f })
        }
    }
}


@Composable
private fun StatesSection() {
    var bannerShown by remember { mutableStateOf(true) }
    var listening by remember { mutableStateOf(false) }
    val sim by rememberInfiniteTransition(label = "mic").animateFloat(
        0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "micLevel",
    )
    Section("States") {
        PermissionCard(
            VmIcons.Capture, "Camera access",
            "I use the front camera to see how you look. Photos are never stored.",
            "Allow camera", {},
        )
        EmptyState(VmIcons.Mirror, "Nothing yet", "Take a photo and I'll describe your outfit.")
        ErrorState("I can't reach the internet", "Position guidance still works. I'll describe you as soon as you're back online.", "Try again", {})
        SpokenErrorBanner("Couldn't hear you. Try again.", visible = bannerShown, actionLabel = "Dismiss", onAction = { bannerShown = false })
        GhostAction("Toggle banner", { bannerShown = !bannerShown })
        GhostAction("Show listening overlay", { listening = true })
    }
    if (listening) {
        Box(Modifier.fillMaxSize()) {
            PressableSurface(onClick = { listening = false }, Modifier.fillMaxSize(), shape = Radius.shape(0.dp), color = Color.Transparent, border = null, hapticOnPress = false, contentDescription = "Close listening overlay") {
                ListeningOverlay(level = { sim })
            }
        }
    }
}

@Composable
private fun MotionSection() {
    Section("Motion presets") {
        Caption("Tap each track. Compare overshoot and settle time. Tune these on a real phone.")
        SpringTrack("press", Motion.press())
        SpringTrack("settle", Motion.settle())
        SpringTrack("gentle", Motion.gentle())
        SpringTrack("critical", Motion.critical())
    }
}

@Composable
private fun SpringTrack(name: String, spec: androidx.compose.animation.core.FiniteAnimationSpec<Float>) {
    var right by remember { mutableStateOf(false) }
    val x by animateFloatAsState(if (right) 1f else 0f, spec, label = name)
    val colors = Vm.colors
    PressableSurface(
        onClick = { right = !right },
        modifier = Modifier.fillMaxWidth().height(Spacing.preferredTouch),
        contentDescription = "Spring $name. Tap to move the dot.",
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.md)) {
            BasicText(name, style = Vm.type.label.copy(color = colors.boneMuted), modifier = Modifier.align(Alignment.CenterStart))
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .graphicsLayer { translationX = 110.dp.toPx() + x * 160.dp.toPx() }
                    .size(20.dp)
                    .background(colors.brass, CircleShape),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedbackSection(haptics: HapticsManager, earcons: EarconPlayer) {
    Section("Haptics and earcons") {
        Caption("Haptics")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            GhostAction("Tick", { haptics.tick() })
            GhostAction("Low tick", { haptics.lowTick() })
            GhostAction("Click", { haptics.click() })
            GhostAction("Ready", { haptics.ready() })
            GhostAction("Error", { haptics.error() })
            GhostAction("Far", { haptics.proximity(0.1f) }, contentDescription = "Proximity far, ${proximityIntervalMs(0.1f)} milliseconds")
            GhostAction("Close", { haptics.proximity(1f) }, contentDescription = "Proximity close, ${proximityIntervalMs(1f)} milliseconds")
        }
        Caption("Earcons")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Earcon.entries.forEach { e -> GhostAction(e.name, { earcons.play(e) }, contentDescription = "Play ${e.name} sound") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconsSection() {
    Section("Icons (24 dp grid, 2 dp stroke)") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            VmIcons.all.forEach { icon ->
                Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Box(
                        Modifier.size(64.dp).background(Vm.colors.raised, Radius.shape(Radius.md)).border(1.dp, Vm.colors.hairline, Radius.shape(Radius.md)),
                        contentAlignment = Alignment.Center,
                    ) { VmIcon(icon, tint = Vm.colors.bone, size = 32.dp) }
                    BasicText(icon.name, style = Vm.type.body.copy(color = Vm.colors.boneMuted, fontSize = 22.sp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TokensSection() {
    val c = Vm.colors
    Section("Colour and type") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            listOf(
                "Ink" to c.ink, "Surface" to c.surface, "Raised" to c.raised, "Hairline" to c.hairline,
                "Bone" to c.bone, "Muted" to c.boneMuted, "Brass" to c.brass, "Champagne" to c.champagne,
                "Quartz" to c.quartz, "Ember" to c.ember,
            ).forEach { (name, color) ->
                Column(Modifier.width(96.dp), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Box(Modifier.size(96.dp, 56.dp).background(color, Radius.shape(Radius.xs)).border(1.dp, c.hairline, Radius.shape(Radius.xs)))
                    BasicText(name, style = Vm.type.body.copy(color = c.boneMuted, fontSize = 22.sp))
                }
            }
        }
        BasicText("Display 48", style = Vm.type.display.copy(color = c.bone))
        BasicText("Headline 34", style = Vm.type.headline.copy(color = c.bone))
        BasicText("Title 28", style = Vm.type.title.copy(color = c.bone))
        BasicText("Body large 26", style = Vm.type.bodyLarge.copy(color = c.bone))
        BasicText("Body 22. Il1 O0 rn m", style = Vm.type.body.copy(color = c.bone))
        BasicText("Label 22", style = Vm.type.label.copy(color = c.bone))
        BasicText("Numeric 0123456789", style = Vm.type.numeric.copy(color = c.bone))
    }
}
