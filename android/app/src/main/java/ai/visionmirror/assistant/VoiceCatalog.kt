package ai.visionmirror.assistant

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class RawCommand(
    val id: String,
    val phrases: List<String> = emptyList(),
    val verbs: List<String> = emptyList(),
    val bare: Int = 6,
    @kotlinx.serialization.SerialName("doc_only") val docOnly: Boolean = false,
    val screens: List<String> = emptyList(),
    val objects: List<String> = emptyList(),
    val directions: List<String> = emptyList(),
)

@Serializable
private data class RawCatalog(val commands: List<RawCommand> = emptyList())

/**
 * Every voice command and the many ways of saying it, loaded from `voice-commands.json` (written by
 * `shared/build_voice_catalog.py`, which is also where the website and the documentation get the very same list).
 *
 * A sentence matches a command when it contains one of the command's phrases as whole words. A command that has verbs
 * ("switch to", "turn on"...) also needs one of them, unless the sentence is short, so "dark mode" works on its own. When
 * several commands match, the one with the longest phrase wins ("take a picture of me" beats "picture").
 */
object VoiceCatalog {
    private class Entry(
        val id: String,
        val phrases: List<String>,
        val verbs: List<String>,
        val bare: Int,
        val screens: List<String>,
        val docOnly: Boolean,
        val objects: List<String> = emptyList(),
        val directions: List<String> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var entries: List<Entry> = emptyList()

    val installed: Boolean get() = entries.isNotEmpty()

    fun normalise(s: String): String =
        s.lowercase().replace("'", "").replace("’", "").replace(Regex("[^a-z0-9 ]"), " ").trim().replace(Regex("\\s+"), " ")

    /** Loads the catalog (the contents of voice-commands.json). */
    fun install(text: String) {
        entries = json.decodeFromString<RawCatalog>(text).commands.map { c ->
            Entry(
                c.id,
                c.phrases.map(::normalise).filter { it.isNotEmpty() },
                c.verbs.map(::normalise).filter { it.isNotEmpty() },
                c.bare, c.screens, c.docOnly,
                c.objects.map(::normalise).filter { it.isNotEmpty() },
                c.directions.map(::normalise).filter { it.isNotEmpty() },
            )
        }
    }

    /** The id of the command [utterance] asks for ("theme.dark", "open.read"...), or null. [screen] is the open route. */
    fun match(utterance: String, screen: String? = null, only: Set<String>? = null): String? {
        val text = normalise(utterance)
        if (text.isEmpty()) return null
        val padded = " $text "
        val wordCount = text.count { it == ' ' } + 1
        var best: String? = null
        var bestScore = -1L
        for (e in entries) {
            if (only != null) { if (e.id !in only) continue } else if (e.docOnly) continue
            if (e.screens.isNotEmpty() && (screen == null || e.screens.none { screen.startsWith(it) })) continue
            var longest = ""
            for (p in e.phrases) {
                if (p.length > longest.length && (" $p " in padded)) longest = p
            }
            if (longest.isEmpty()) {
                // "increase the screen brightness a bit", "brightness higher please": an object and a direction, in any order.
                // Questions and "read ..." are left alone, so a question about a photo stays a question.
                if (e.objects.isNotEmpty() && wordCount in 2..9 && AXIS_EXCLUDE.none { " $it " in padded } &&
                    e.objects.any { " $it " in padded } && e.directions.any { " $it " in padded } && 1_000_000L > bestScore
                ) {
                    best = e.id
                    bestScore = 1_000_000L
                }
                continue
            }
            if (e.verbs.isNotEmpty() && wordCount > e.bare && e.verbs.none { " $it " in padded }) continue
            val words = longest.count { it == ' ' } + 1
            val score = words * 1_000_000L + longest.length * 10L + if (e.screens.isNotEmpty()) 1 else 0
            if (score > bestScore) {
                best = e.id
                bestScore = score
            }
        }
        return best
    }

    private val AXIS_EXCLUDE = setOf(
        "read", "reads", "reading", "say", "says", "said", "what", "which", "who", "whom", "where", "why", "how", "when",
        "is", "are", "was", "were", "does", "did", "do", "would", "should", "could",
    )

    /** All the phrases of the commands [ids] (live or not), for the short words that stop or resume her. */
    fun phrases(vararg ids: String): Set<String> =
        entries.filter { it.id in ids }.flatMapTo(HashSet()) { it.phrases }
}
