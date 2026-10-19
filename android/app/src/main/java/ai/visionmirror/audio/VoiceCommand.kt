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
        if (hits.size > 1) return null // "stop and ask": two commands, so neither
        // "Say that again", "I missed that", "start the photo again", "I have a question": every way listed on the website. These
        // name the thing precisely, so they win over a single short word inside them ("start" in "start the photo again").
        if (ai.visionmirror.assistant.VoiceCatalog.installed) {
            when (ai.visionmirror.assistant.VoiceCatalog.match(text, only = setOf("res.retake", "res.repeat", "res.ask"))) {
                "res.retake" -> return VoiceCommand.Retake
                "res.repeat" -> return VoiceCommand.Repeat
                "res.ask" -> return VoiceCommand.Ask
            }
        }
        return hits.singleOrNull()
    }
}
