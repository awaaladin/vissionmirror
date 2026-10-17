package ai.visionmirror.assistant

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
        val text = utterance.lowercase().replace("'", "").replace("’", "").replace(Regex("[^a-z0-9 ]"), " ").trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty() || text.split(' ').size > 10) return null
        fun has(vararg phrases: String) = phrases.any { " $it " in " $text " }
        val off = has("off", "disable", "stop", "no more", "without")

        fun set(say: String, change: (Settings) -> Settings): SettingChange = SettingChange.Set(say, change)

        return when {
            has("what can you do", "what can i say", "what can i ask") || (text == "help" || text == "help me") -> SettingChange.Help

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
            has("change voice", "different voice", "next voice", "another voice", "change your voice") -> SettingChange.NextVoice
            has("natural voice", "nigerian voice", "online voice", "cloud voice") ->
                if (off) set("I'll use your phone's voice.") { it.copy(cloudVoice = false) }
                else set("I'll use the natural Nigerian voice.") { it.copy(cloudVoice = true) }
            else -> languageChange(text)
        }
    }

    private fun languageChange(text: String): SettingChange? {
        val asked = Regex("\\b(speak|talk|use|switch to|change to|change language to|answer in|reply in|respond in|in)\\s+(?:to me in\\s+)?(nigerian english|english|hausa|yoruba|igbo|ibibio)\\b").find(text)
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
        "You can say my name and ask me to read, look around, or use the mirror. " +
            "You can also change anything: louder, slower, bigger text, brighter, dark mode, speak Hausa, or change voice."
}
