package ai.visionmirror.assistant

import ai.visionmirror.audio.Persona
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the assistant said recently, so that when the microphone picks up her own voice she knows it is herself and not
 * the user. Every utterance is remembered here as it starts. Anything the speech recogniser hears that is (nearly) all
 * words she just said is treated as an echo and ignored.
 *
 * Names and the stop words are left out of the comparison on purpose: if the user says "Bola" or "stop" right after
 * she said it herself, that must still count.
 */
@Singleton
class OwnSpeech @Inject constructor() {
    private class Entry(val at: Long, val words: Set<String>)

    private val recent = ArrayDeque<Entry>()

    fun remember(text: String, now: Long = System.currentTimeMillis()) {
        val words = significantWords(text)
        synchronized(recent) {
            recent.addLast(Entry(now, words))
            while (recent.isNotEmpty() && now - recent.first().at > WINDOW_MS) recent.removeFirst()
        }
    }

    /** True when [heard] is mostly made of words the assistant said within the last half minute. */
    fun isEcho(heard: String, now: Long = System.currentTimeMillis()): Boolean {
        val words = significantWords(heard)
        if (words.isEmpty()) return false
        val pool = synchronized(recent) {
            recent.filter { now - it.at <= WINDOW_MS }.flatMapTo(HashSet()) { it.words }
        }
        if (pool.isEmpty()) return false
        val matching = words.count { it in pool }
        return matching.toDouble() / words.size >= ECHO_SHARE
    }

    private fun significantWords(text: String): Set<String> =
        text.lowercase().replace("'", "").replace("’", "").replace(Regex("[^a-z ]"), " ")
            .split(' ').filter { it.length > 1 && it !in IGNORED }.toSet()

    private companion object {
        const val WINDOW_MS = 30_000L
        const val ECHO_SHARE = 0.7
        val IGNORED = setOf("adanna", "bola", "stop", "pause", "wait", "quiet", "enough", "hold", "on", "special", "request")
    }
}

/**
 * Short fixed sentences the assistant says all the time. They are fetched in the active voice ahead of time and kept, so
 * "Listening." comes out instantly in Bola's voice when it is Bola, and in Adanna's when it is Adanna, instead of in the
 * phone's own (woman's) voice.
 */
object Phrases {
    fun all(p: Persona): List<String> = listOf(
        "Listening.",
        "One moment.",
        "Hold steady.",
        "Paused.",
        "Guiding again.",
        "Louder.",
        "Quieter.",
        "Faster.",
        "Slower.",
        "Brighter.",
        "Dimmer.",
        "I didn't catch that. Say my name and try again.",
        "I couldn't hear you just now. Say my name and try again.",
        "I am ${p.displayName}.",
        "I am ${p.displayName}. Listening.",
        "Hello, I'm ${p.displayName}.",
        "I'm here when you need me. Say ${p.displayName}.",
        "Your connection is slow. I'll keep trying.",
        "Trying again.",
    )
}
