package ai.visionmirror.assistant

/** Somewhere Adanna can take her, by voice. */
enum class AssistantAction { OpenMirror, OpenRead, OpenColour, OpenLook, OpenSettings, OpenHome }

/**
 * Turns what she said after "Adanna" into a place to go. Short requests ("read", "what colour is this") open a tool;
 * long ones are questions, and when there is a photo to ask about they are always questions
 * ("what colour is my shirt" must be answered, not open the colour tool).
 */
object AssistantIntents {
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
