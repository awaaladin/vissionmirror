package ai.visionmirror.assistant

/** Somewhere Adanna can take her, or something she can do on the open screen, by voice. */
enum class AssistantAction {
    OpenMirror, OpenRead, OpenLook, OpenGuide, OpenSettings, OpenHome,

    /** Act on the screen that is open. */
    TakePhoto, FlipCamera, LookAgain, GoBack,
}

/**
 * Turns what she said after "Adanna" into a place to go or a button to press. Short requests ("read", "look around")
 * open a tool; long ones are questions, and when there is a photo to ask about they are always questions
 * ("what is on my shirt" must be answered, not open a tool).
 *
 * [screen] is the route that is open ("mirror", "look/read"...), so "take it" means the photo on the mirror and
 * "again" means look again on the camera tools.
 */
object AssistantIntents {
    private val knowledge = listOf(
        "weather", "temperature", "forecast", "rain", "umbrella", "news", "who is", "who was", "who are", "what is the", "what are the",
        "what was", "tell me about", "search", "look up", "research", "how many", "how much", "when did", "when was", "where is",
        "capital of", "define", "meaning of", "explain", "how do i", "how does", "why is", "why do",
    )
    // Words that mean she is pointing at the photo or herself, so "what is that" stays a question about the picture.
    private val aboutThePhoto = Regex("\\b(this|that|these|those|it|my|me|i am|im|wearing|photo|picture|in front|on me|here)\\b")

    /** A question about the world (weather, facts) rather than about the photo. */
    fun isKnowledgeQuestion(utterance: String): Boolean {
        val text = normalise(utterance)
        if (text.isEmpty()) return false
        val padded = " $text "
        if (listOf("weather", "temperature", "forecast", "umbrella", "news").any { " $it " in padded }) return true
        if (aboutThePhoto.containsMatchIn(text)) return false
        return knowledge.any { " $it " in padded }
    }

    private val explicit = Regex("^(open|go to|go|start|switch to|show|use|take me to)\\b")

    private fun normalise(s: String) = s.lowercase().replace("'", "").replace("’", "").replace(Regex("[^a-z ]"), " ").trim().replace(Regex("\\s+"), " ")

    fun parse(utterance: String, hasSession: Boolean, screen: String? = null): AssistantAction? {
        val text = normalise(utterance)
        if (text.isEmpty()) return null
        val words = text.split(' ')
        fun has(vararg phrases: String) = phrases.any { " $it " in " $text " }
        val onMirror = screen == "mirror"
        val onLook = screen?.startsWith("look") == true

        // ---- buttons on the open screen
        if (words.size <= 6) {
            if (has("go back", "previous screen", "close this", "exit") || text == "back") return AssistantAction.GoBack
            if (has("take the photo", "take the picture", "take it now", "capture", "snap", "take photo now", "take a photo now") ||
                (onMirror && has("take a photo", "take photo", "take a picture", "photo", "picture", "now", "ready", "take it", "go"))
            ) return AssistantAction.TakePhoto
            if (has("flip the camera", "flip camera", "switch camera", "switch the camera", "other camera", "change camera", "change the camera") ||
                (onMirror && has("flip"))
            ) return AssistantAction.FlipCamera
            if (onLook && has("again", "try again", "scan again", "look again", "once more", "retry", "repeat")) return AssistantAction.LookAgain
        }

        // ---- places
        val asked = explicit.containsMatchIn(text)
        if (!asked && (words.size > 6 || (hasSession && words.size > 2))) return null
        return when {
            has("settings", "setting", "preferences") -> AssistantAction.OpenSettings
            has("home", "menu", "main menu") -> AssistantAction.OpenHome
            has("guide me", "start guide", "guide mode", "start guiding", "walk with me", "watch ahead", "watch for obstacles", "detect objects", "what is ahead of me") ->
                AssistantAction.OpenGuide
            has("look around", "around me", "in front of me", "surroundings", "whats here", "what is here", "describe", "what am i holding",
                "what colour is this", "what color is this", "what colour is that", "what color is that") -> AssistantAction.OpenLook
            has("read", "reading", "what does it say", "what does this say", "text", "label") -> AssistantAction.OpenRead
            has("mirror", "photo", "selfie", "outfit", "how do i look", "picture") -> AssistantAction.OpenMirror
            else -> null
        }
    }
}
