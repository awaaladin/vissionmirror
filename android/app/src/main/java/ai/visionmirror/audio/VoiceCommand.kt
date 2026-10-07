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
}
