package ai.visionmirror.assistant

/** Somewhere Adanna can take her, by voice. */
enum class AssistantAction { OpenMirror, OpenRead, OpenColour, OpenLook, OpenSettings, OpenHome }

/**
 * Turns what she said after "Adanna" into a place to go. Short requests ("read", "what colour is this") open a tool;
 * long ones are questions, and when there is a photo to ask about they are always questions
 * ("what colour is my shirt" must be answered, not open the colour tool).
 */
object AssistantIntents {
    private val knowledge = listOf(
        "weather", "temperature", "forecast", "rain", "umbrella", "news", "who is", "who was", "who are", "what is the", "what are the",
        "what was", "tell me about", "search", "look up", "research", "how many", "how much", "when did", "when was", "where is",
        "capital of", "define", "meaning of", "explain", "how do i", "how does", "why is", "why do", "what time", "what day", "what date",
    )
    // Words that mean she is pointing at the photo or herself, so "what is that" stays a question about the picture.
    private val aboutThePhoto = Regex("\\b(this|that|these|those|it|my|me|i am|im|wearing|photo|picture|in front|on me|here)\\b")

    /** A question about the world (weather, facts) rather than about the photo. */
    fun isKnowledgeQuestion(utterance: String): Boolean {
        val text = utterance.lowercase().replace(Regex("[^a-z ]"), " ").trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return false
        val padded = " $text "
        if (listOf("weather", "temperature", "forecast", "umbrella", "news").any { " $it " in padded }) return true
        if (aboutThePhoto.containsMatchIn(text)) return false
        return knowledge.any { " $it " in padded }
    }

    private val explicit = Regex("^(open|go to|go|start|switch to|show|use|take me to)\\b")

    fun parse(utterance: String, hasSession: Boolean): AssistantAction? {
        val text = utterance.lowercase().replace(Regex("[^a-z ]"), " ").trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return null
        val words = text.split(' ')
        val asked = explicit.containsMatchIn(text)
        if (!asked && (words.size > 6 || (hasSession && words.size > 2))) return null

        fun has(vararg phrases: String) = phrases.any { " $it " in " $text " }
        return when {
            has("settings", "setting", "preferences") -> AssistantAction.OpenSettings
            has("home", "menu", "main menu") -> AssistantAction.OpenHome
            has("look around", "around me", "in front of me", "surroundings", "whats here", "what is here", "describe", "what am i holding") ->
                AssistantAction.OpenLook
            has("read", "reading", "what does it say", "what does this say", "text", "label") -> AssistantAction.OpenRead
            has("colour", "color", "colours", "colors") -> AssistantAction.OpenColour
            has("mirror", "photo", "selfie", "outfit", "how do i look", "picture") -> AssistantAction.OpenMirror
            else -> null
        }
    }
}
