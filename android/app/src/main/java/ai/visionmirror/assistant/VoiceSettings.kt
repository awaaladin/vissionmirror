package ai.visionmirror.assistant

import ai.visionmirror.audio.Persona
import ai.visionmirror.audio.ResponseLanguage
import ai.visionmirror.data.api.DetailLevel
import ai.visionmirror.data.settings.CameraFacing
import ai.visionmirror.data.settings.Settings
import ai.visionmirror.design.tokens.FontChoice
import ai.visionmirror.design.tokens.ThemeMode
import ai.visionmirror.settings.SpeechRate
import ai.visionmirror.settings.TextSize

/** Brightness she can ask for. Negative = follow the phone. */
object Brightness {
    const val SYSTEM = -1f
    const val MIN = 0.05f
    const val STEP = 0.2f

    fun up(current: Float): Float = if (current < 0f) 1f else (current + STEP).coerceAtMost(1f)
    fun down(current: Float): Float = if (current < 0f) 0.4f else (current - STEP).coerceAtLeast(MIN)
}

/** One thing she can change by voice: "Adanna, make the text bigger". */
sealed interface SettingChange {
    /** Changes a saved setting; the sentence is what Adanna says back. */
    class Set(val say: String, val change: (Settings) -> Settings) : SettingChange

    /** Phone volume: positive = louder. */
    data class Volume(val steps: Int) : SettingChange
    data object VolumeMax : SettingChange
    data object NextVoice : SettingChange
    data object Help : SettingChange
}

/**
 * Turns "make the text bigger" or "speak Hausa" into a [SettingChange]. Every setting in the app can be reached,
 * and nothing is changed unless the sentence clearly says so: a question about the photo that happens to mention
 * "text" must stay a question, so each rule needs its own wording.
 */
object VoiceSettings {
    fun parse(utterance: String): SettingChange? {
        val text = ai.visionmirror.audio.WakeWord.canonical(utterance).replace("'", "").replace("’", "").replace(Regex("[^a-z0-9 ]"), " ").trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty() || text.split(' ').size > 10) return null
        fun has(vararg phrases: String) = phrases.any { " $it " in " $text " }
        val off = has("off", "disable", "stop", "no more", "without")

        fun set(say: String, change: (Settings) -> Settings): SettingChange = SettingChange.Set(say, change)

        if (has("what can you do", "what can i say", "what can i ask", "what are the commands", "list commands", "show commands") || text == "help") {
            return SettingChange.Help
        }
        // "Read the larger text" is a request to read, not to change the text size.
        if (text.startsWith("read ")) return null
        // A question ("what does the bigger text on the label say") is not an order to change a setting.
        if (text.substringBefore(' ') in QUESTION_STARTS && text.split(' ').size > 3) return null
        // The catalog knows every way of saying each of these (dozens of synonyms each), so it is asked first.
        if (VoiceCatalog.installed) VoiceCatalog.match(text)?.let { id -> fromCatalog(id)?.let { return it } }

        return when {

            // ---- wake word / commands
            has("wake word", "listen for my name", "listening for my name") ->
                if (off) set("I won't listen for my name.") { it.copy(wakeWord = false) }
                else set("I'll listen for my name.") { it.copy(wakeWord = true) }
            has("voice commands") ->
                if (off) set("Voice commands are off.") { it.copy(voiceCommands = false) }
                else set("Voice commands are on.") { it.copy(voiceCommands = true) }

            // ---- volume
            has("max volume", "full volume", "loudest", "maximum volume") -> SettingChange.VolumeMax
            has("louder", "volume up", "turn it up", "speak up", "increase volume", "raise the volume") -> SettingChange.Volume(+1)
            has("quieter", "volume down", "turn it down", "softer", "lower the volume", "decrease volume", "lower volume") -> SettingChange.Volume(-1)

            // ---- screen brightness
            has("full brightness", "max brightness", "maximum brightness", "brightest") ->
                set("Brightness is at full.") { it.copy(brightness = 1f) }
            has("auto brightness", "automatic brightness", "normal brightness", "reset brightness", "default brightness") ->
                set("Brightness now follows your phone.") { it.copy(brightness = Brightness.SYSTEM) }
            has("brighter", "brightness up", "increase brightness", "raise brightness", "more brightness") ->
                set("Brighter.") { it.copy(brightness = Brightness.up(it.brightness)) }
            has("dimmer", "brightness down", "decrease brightness", "lower brightness", "less brightness", "dim the screen", "darker screen") ->
                set("Dimmer.") { it.copy(brightness = Brightness.down(it.brightness)) }

            // ---- how fast she talks
            has("normal speed", "normal pace", "speak normally", "default speed") ->
                set("Normal speed.") { it.copy(speechRate = SpeechRate.Normal.value) }
            has("slower", "slow down", "speak slowly", "talk slowly", "speed down", "too fast") ->
                set("Slower.") { it.copy(speechRate = stepRate(it.speechRate, -1)) }
            has("faster", "speed up", "speak quickly", "talk faster", "too slow", "hurry up") ->
                set("Faster.") { it.copy(speechRate = stepRate(it.speechRate, +1)) }

            // ---- text size
            has("huge text", "biggest text", "largest text", "maximum text") ->
                set("Text size: huge.") { it.copy(textScale = TextSize.Huge.scale) }
            has("normal text", "normal text size", "reset text", "default text size") ->
                set("Text size: normal.") { it.copy(textScale = TextSize.Normal.scale) }
            has("bigger text", "larger text", "increase text", "text bigger", "text larger", "make the text bigger", "make it bigger", "bigger font", "larger font") ->
                set("Bigger text.") { it.copy(textScale = stepText(it.textScale, +1)) }
            has("smaller text", "reduce text", "text smaller", "make the text smaller", "make it smaller", "smaller font") ->
                set("Smaller text.") { it.copy(textScale = stepText(it.textScale, -1)) }

            // ---- look
            has("dark mode", "dark theme") -> set("Dark mode.") { it.copy(themeMode = ThemeMode.Dark) }
            has("light mode", "light theme", "bright mode") -> set("Light mode.") { it.copy(themeMode = ThemeMode.Light) }
            has("system theme", "automatic theme", "auto theme", "follow my phone theme") ->
                set("The theme now follows your phone.") { it.copy(themeMode = ThemeMode.System) }
            has("high contrast") ->
                if (off) set("High contrast is off.") { it.copy(highContrast = false) }
                else set("High contrast is on.") { it.copy(highContrast = true) }
            has("reduce motion", "less motion", "calm screen", "calmer screen") ->
                if (off) set("Full motion is back.") { it.copy(reduceMotion = false) }
                else set("Reduced motion is on.") { it.copy(reduceMotion = true) }
            has("full motion", "motion on") -> set("Full motion is back.") { it.copy(reduceMotion = false) }
            has("dyslexia", "hyperlegible", "easy to read font", "easy font") ->
                set("Hyperlegible font.") { it.copy(fontChoice = FontChoice.Hyperlegible) }
            has("standard font", "normal font", "system font", "plain font") ->
                set("Standard font.") { it.copy(fontChoice = FontChoice.Standard) }
            has("vibration", "vibrate", "haptics", "haptic") ->
                if (off) set("Vibration is off.") { it.copy(haptics = false) }
                else set("Vibration is on.") { it.copy(haptics = true) }

            // ---- how much she says
            has("brief description", "brief descriptions", "short description", "short descriptions", "less detail", "shorter descriptions", "keep it short") ->
                set("Brief descriptions.") { it.copy(detailLevel = DetailLevel.Brief) }
            has("detailed description", "detailed descriptions", "long description", "more detail", "describe everything", "longer descriptions") ->
                set("Detailed descriptions.") { it.copy(detailLevel = DetailLevel.Detailed) }
            has("standard description", "normal detail", "normal description") ->
                set("Standard descriptions.") { it.copy(detailLevel = DetailLevel.Standard) }

            // ---- camera
            has("back camera", "rear camera") && (has("use", "switch", "change", "turn", "select") || text.split(' ').size <= 3) ->
                set("Using the back camera.") { it.copy(cameraFacing = CameraFacing.Back) }
            has("front camera", "selfie camera") && (has("use", "switch", "change", "turn", "select") || text.split(' ').size <= 3) ->
                set("Using the front camera.") { it.copy(cameraFacing = CameraFacing.Front) }

            // ---- voice and language
            has("switch to bola", "change to bola", "use bola", "bola voice", "male voice", "speak as bola", "be bola", "give me bola",
                "i want bola", "talk to bola", "put bola on", "let me talk to bola", "switch to the man", "man voice") ->
                set("Hello, I'm Bola.") { it.copy(persona = Persona.Bola, voiceName = null) }
            has("switch to adanna", "change to adanna", "use adanna", "adanna voice", "female voice", "be adanna", "go back to adanna",
                "i want adanna", "talk to adanna", "put adanna on", "let me talk to adanna", "woman voice", "switch to the woman") ->
                set("Hello, I'm Adanna.") { it.copy(persona = Persona.Adanna, voiceName = null) }
            has("change voice", "different voice", "next voice", "another voice", "change your voice") -> SettingChange.NextVoice
            has("natural voice", "nigerian voice", "online voice", "cloud voice") ->
                if (off) set("I'll use your phone's voice.") { it.copy(cloudVoice = false) }
                else set("I'll use the natural Nigerian voice.") { it.copy(cloudVoice = true) }
            else -> languageChange(text)
        }
    }

    private val QUESTION_STARTS = setOf("what", "which", "who", "whom", "where", "why", "how", "when", "is", "are", "was", "were", "does", "did")

    private fun set(say: String, change: (Settings) -> Settings): SettingChange = SettingChange.Set(say, change)

    /** What each setting command of the catalog does. */
    private fun fromCatalog(id: String): SettingChange? = when (id) {
        "vol.max" -> SettingChange.VolumeMax
        "vol.up" -> SettingChange.Volume(+1)
        "vol.down" -> SettingChange.Volume(-1)
        "rate.normal" -> set("Normal speed.") { it.copy(speechRate = SpeechRate.Normal.value) }
        "rate.slow" -> set("Slower.") { it.copy(speechRate = stepRate(it.speechRate, -1)) }
        "rate.fast" -> set("Faster.") { it.copy(speechRate = stepRate(it.speechRate, +1)) }
        "bright.full" -> set("Brightness is at full.") { it.copy(brightness = 1f) }
        "bright.auto" -> set("Brightness now follows your phone.") { it.copy(brightness = Brightness.SYSTEM) }
        "bright.up" -> set("Brighter.") { it.copy(brightness = Brightness.up(it.brightness)) }
        "bright.down" -> set("Dimmer.") { it.copy(brightness = Brightness.down(it.brightness)) }
        "text.huge" -> set("Text size: huge.") { it.copy(textScale = TextSize.Huge.scale) }
        "text.normal" -> set("Text size: normal.") { it.copy(textScale = TextSize.Normal.scale) }
        "text.bigger" -> set("Bigger text.") { it.copy(textScale = stepText(it.textScale, +1)) }
        "text.smaller" -> set("Smaller text.") { it.copy(textScale = stepText(it.textScale, -1)) }
        "font.hyper" -> set("Hyperlegible font.") { it.copy(fontChoice = FontChoice.Hyperlegible) }
        "font.standard" -> set("Standard font.") { it.copy(fontChoice = FontChoice.Standard) }
        "contrast.on" -> set("High contrast is on.") { it.copy(highContrast = true) }
        "contrast.off" -> set("High contrast is off.") { it.copy(highContrast = false) }
        "theme.light" -> set("Light mode.") { it.copy(themeMode = ThemeMode.Light) }
        "theme.dark" -> set("Dark mode.") { it.copy(themeMode = ThemeMode.Dark) }
        "theme.system" -> set("The theme now follows your phone.") { it.copy(themeMode = ThemeMode.System) }
        "motion.full" -> set("Full motion is back.") { it.copy(reduceMotion = false) }
        "motion.reduce" -> set("Reduced motion is on.") { it.copy(reduceMotion = true) }
        "haptics.on" -> set("Vibration is on.") { it.copy(haptics = true) }
        "haptics.off" -> set("Vibration is off.") { it.copy(haptics = false) }
        "detail.brief" -> set("Brief descriptions.") { it.copy(detailLevel = DetailLevel.Brief) }
        "detail.detailed" -> set("Detailed descriptions.") { it.copy(detailLevel = DetailLevel.Detailed) }
        "detail.standard" -> set("Standard descriptions.") { it.copy(detailLevel = DetailLevel.Standard) }
        "wake.on" -> set("I'll listen for my name.") { it.copy(wakeWord = true) }
        "wake.off" -> set("I won't listen for my name.") { it.copy(wakeWord = false) }
        "commands.on" -> set("Voice commands are on.") { it.copy(voiceCommands = true) }
        "quiet.on" -> set("Quiet listening is on. No more beeps, but I may mishear my name a little more.") { it.copy(quietListening = true) }
        "quiet.off" -> set("Normal listening. It may beep, but I hear you better.") { it.copy(quietListening = false) }
        "commands.off" -> set("Voice commands are off.") { it.copy(voiceCommands = false) }
        "barge.on" -> set("I'll stop when you say stop.") { it.copy(bargeIn = true) }
        "barge.off" -> set("I won't stop when you speak.") { it.copy(bargeIn = false) }
        "voice.bola" -> set("Hello, I'm Bola.") { it.copy(persona = Persona.Bola, voiceName = null) }
        "voice.adanna" -> set("Hello, I'm Adanna.") { it.copy(persona = Persona.Adanna, voiceName = null) }
        "voice.next" -> SettingChange.NextVoice
        "voice.local" -> set("I'll use your phone's voice.") { it.copy(cloudVoice = false) }
        "voice.cloud" -> set("I'll use the natural Nigerian voice.") { it.copy(cloudVoice = true) }
        else -> null
    }

    private fun languageChange(text: String): SettingChange? {
        // Just the name of the language ("Hausa") is a request too.
        val bare = when (text) {
            "hausa" -> ResponseLanguage.Hausa
            "yoruba" -> ResponseLanguage.Yoruba
            "igbo" -> ResponseLanguage.Igbo
            "ibibio" -> ResponseLanguage.Ibibio
            "english", "nigerian english" -> ResponseLanguage.NigerianEnglish
            else -> null
        }
        if (bare != null) return SettingChange.Set("${bare.label}.") { it.copy(language = bare, voiceName = null) }
        val asked = Regex("\\b(speak|talk|use|switch to|change to|change language to|switch language to|set language to|set the language to|answer in|reply in|respond in|say it in|tell me in|translate to|talk to me in|speak to me in|go to|i want|i prefer|please speak|please use|can you speak|can you talk|give me|read it in|describe in|in)\\s+(?:to me in\\s+)?(nigerian english|english|hausa|yoruba|igbo|ibibio)\\b").find(text)
            ?: return null
        val language = when (asked.groupValues[2]) {
            "hausa" -> ResponseLanguage.Hausa
            "yoruba" -> ResponseLanguage.Yoruba
            "igbo" -> ResponseLanguage.Igbo
            "ibibio" -> ResponseLanguage.Ibibio
            else -> ResponseLanguage.NigerianEnglish
        }
        // "in english" inside a longer question is not a request: only treat the short forms as commands.
        if (asked.groupValues[1] == "in" && text.split(' ').size > 3) return null
        return SettingChange.Set("${language.label}.") { it.copy(language = language, voiceName = null) }
    }

    private fun stepRate(current: Float, direction: Int): Float {
        val i = SpeechRate.nearest(current).ordinal + direction
        return SpeechRate.entries[i.coerceIn(0, SpeechRate.entries.size - 1)].value
    }

    private fun stepText(current: Float, direction: Int): Float {
        val i = TextSize.nearest(current).ordinal + direction
        return TextSize.entries[i.coerceIn(0, TextSize.entries.size - 1)].scale
    }

    const val HELP =
        "You can say my name and ask me to read, look around, guide you, or use the mirror. Ask where you are, or say I need help to text your location to your emergency contact. " +
            "You can also change anything: louder, slower, bigger text, brighter, dark mode, speak Hausa, or change voice. Say switch to Bola for a man's voice, or switch to Adanna for a woman's."
}
