package ai.visionmirror.assistant

/**
 * When someone asks to change brightness, volume, text size or speed in words the app did not recognise, the answer must never be
 * the AI saying "I can't change your phone" (the app CAN). This gives a short hint with words that work instead.
 */
object ControlHints {
    private val verbs = setOf(
        "increase", "decrease", "reduce", "raise", "lower", "change", "make", "turn", "set", "switch", "put", "adjust", "bring",
        "bump", "boost", "up", "down", "more", "less",
    )
    private val strong = setOf("increase", "decrease", "reduce", "raise", "lower", "turn", "adjust", "bump", "boost")
    private val notControls = setOf("read", "reads", "reading", "say", "says", "said", "what", "which", "who", "where", "why", "how", "when", "is", "are", "was", "were", "does", "did")

    /** [hasPhoto]: a photo is open, so only clear orders count (a question about the photo is a question). Null = not a control request. */
    fun forRequest(utterance: String, hasPhoto: Boolean): String? {
        val text = VoiceCatalog.normalise(utterance)
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.size !in 2..11 || words.any { it in notControls }) return null
        if (words.none { it in verbs }) return null
        if (hasPhoto && words.none { it in strong }) return null
        val padded = " $text "
        fun has(vararg w: String) = w.any { " $it " in padded }
        return when {
            has("brightness", "backlight") || (has("screen", "display") && has("bright", "brighter", "dim", "dimmer", "light")) ->
                "I can change the screen brightness. Say: brighter, dimmer, full brightness, or automatic brightness."
            has("volume", "sound", "louder", "quieter", "audio") ->
                "I can change the volume. Say: louder, quieter, or maximum volume."
            has("text", "font", "letters", "print", "writing") && has("size", "bigger", "smaller", "larger", "big", "small", "large") ->
                "I can change the text size. Say: bigger text, smaller text, huge text, or normal text size."
            has("speed", "pace", "faster", "slower") ->
                "I can change how fast I talk. Say: faster, slower, or normal speed."
            has("theme", "contrast", "colours", "colors") || (has("dark", "light", "night", "day") && has("mode", "theme")) ->
                "I can change the look. Say: dark mode, light mode, high contrast, or normal colours."
            else -> null
        }
    }
}
