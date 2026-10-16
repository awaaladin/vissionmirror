package ai.visionmirror.audio

/** The handful of things she can say. Matching is deliberately forgiving: people say "um, retake please". */
enum class VoiceCommand { Continue, Ask, Retake, Repeat, Pause, Resume, Stop }

object VoiceCommands {
    private val words: Map<VoiceCommand, List<String>> = mapOf(
        VoiceCommand.Continue to listOf("continue", "next", "go on", "okay", "ok", "start"),
        VoiceCommand.Ask to listOf("ask", "question", "ask a question"),
        VoiceCommand.Retake to listOf("retake", "take another", "try again", "new photo", "redo"),
        VoiceCommand.Repeat to listOf("repeat", "say that again", "say again", "read again"),
        VoiceCommand.Pause to listOf("pause", "hold on", "wait"),
        VoiceCommand.Resume to listOf("resume", "carry on", "keep going"),
        VoiceCommand.Stop to listOf("stop", "quiet", "silence", "enough"),
    )

    /** Returns the command if the utterance is short and contains exactly one command phrase. */
    fun parse(utterance: String): VoiceCommand? {
        val text = utterance.lowercase().replace(Regex("[^a-z ]"), " ").trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty() || text.split(' ').size > 6) return null
        val padded = " $text "
        val hits = words.filter { (_, phrases) -> phrases.any { " $it " in padded } }.keys
        return hits.singleOrNull()
    }

    /** The key phrase that wakes the assistant. The microphone is never open for anything else but commands. */
    const val WAKE_PHRASE = "special request"

    /**
     * Null when [utterance] does not contain the key phrase. Otherwise whatever she said after it ("" if nothing),
     * which is the question when she says it all in one breath.
     */
    fun specialRequest(utterance: String): String? {
        val match = Regex("\\bspecial\\s+requests?\\b[\\s,.:;-]*", RegexOption.IGNORE_CASE).find(utterance) ?: return null
        return utterance.substring(match.range.last + 1).trim()
    }
}
