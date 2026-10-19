package ai.visionmirror.emergency

/** What she is typing into the emergency contact page by voice, so the page can show it as it is said. */
data class ContactDraft(val name: String = "", val number: String = "", val step: String = "")

/**
 * Understands the way people say names and numbers to a voice assistant: names spelled out ("m a r y"), digits said one at
 * a time ("zero eight zero three", "double five"), and the short answers "yes", "no", "save" and "cancel".
 */
object SpokenInput {
    private fun words(s: String): List<String> =
        s.lowercase().replace("'", "").replace("’", "").replace(Regex("[^a-z0-9+ ]"), " ").trim().split(' ').filter { it.isNotEmpty() }

    private val digitWords = mapOf(
        "zero" to '0', "oh" to '0', "o" to '0', "nought" to '0', "naught" to '0', "nil" to '0',
        "one" to '1', "won" to '1', "two" to '2', "to" to '2', "too" to '2', "three" to '3', "tree" to '3',
        "four" to '4', "for" to '4', "fore" to '4', "five" to '5', "six" to '6', "seven" to '7',
        "eight" to '8', "ate" to '8', "nine" to '9', "nein" to '9',
    )

    private fun digitOf(w: String): Char? = digitWords[w] ?: w.singleOrNull()?.takeIf { it.isDigit() }

    /** The digits in what she said, in order. A leading "plus" or "+" is kept ("plus two three four" is +234). */
    fun digits(spoken: String): String {
        val ws = words(spoken)
        val out = StringBuilder()
        var i = 0
        while (i < ws.size) {
            val w = ws[i]
            if ((w == "double" || w == "triple") && i + 1 < ws.size) {
                val d = digitOf(ws[i + 1])
                if (d != null) {
                    repeat(if (w == "double") 2 else 3) { out.append(d) }
                    i += 2
                    continue
                }
            }
            when {
                w == "plus" || w == "+" -> if (out.isEmpty()) out.append('+')
                w.all { it.isDigit() } -> out.append(w)               // "08034123456" or "0803" said as a number
                w.startsWith("+") && w.drop(1).all { it.isDigit() } -> if (out.isEmpty()) out.append(w)
                else -> digitOf(w)?.let { out.append(it) }
            }
            i++
        }
        return out.toString()
    }

    /** "0 8 0 3 4 1 2": the digits with gaps, so the voice reads them one at a time. */
    fun spaced(number: String): String = number.map { if (it == '+') "plus" else it.toString() }.joinToString(" ")

    private val letterNames = mapOf(
        "bee" to 'b', "be" to 'b', "cee" to 'c', "see" to 'c', "sea" to 'c', "dee" to 'd', "gee" to 'g', "jay" to 'j', "kay" to 'k',
        "el" to 'l', "em" to 'm', "en" to 'n', "pee" to 'p', "queue" to 'q', "cue" to 'q', "are" to 'r', "ar" to 'r', "ess" to 's',
        "tee" to 't', "tea" to 't', "you" to 'u', "vee" to 'v', "ex" to 'x', "why" to 'y', "wy" to 'y', "zed" to 'z', "zee" to 'z',
        "ay" to 'a', "eye" to 'i', "oh" to 'o', "ef" to 'f', "eff" to 'f', "aitch" to 'h',
    )

    /**
     * A name spelled letter by letter ("m a r y", "em ay ar why"), as a capitalised word; null when it was not spelled.
     * "double u" and "double you" are the letter w.
     */
    fun spelled(spoken: String): String? {
        val cleaned = spoken.lowercase().replace(Regex("\\b(spell it|spelled|spelling|it is|its|the name is|is spelled|spell)\\b"), " ")
            .replace("double you", "w").replace("double u", "w")
        val ws = words(cleaned)
        if (ws.size < 2) return null
        val letters = ws.map { w -> if (w.length == 1 && w[0].isLetter()) w[0] else letterNames[w] ?: return null }
        return letters.joinToString("").replaceFirstChar { it.uppercase() }
    }

    private val namePrefix = Regex("^(?:it is|its|it should be|the name is|his name is|her name is|their name is|call him|call her|name|my emergency contact is|set it to|my contact is|i want|please|it is called|called)\\s+")

    /** "it is Mary Okon" -> "Mary Okon". */
    fun cleanName(spoken: String): String {
        var t = spoken.trim().replace(Regex("[^\\p{L} '\\-]"), " ").replace(Regex("\\s+"), " ").trim()
        while (true) {
            val next = namePrefix.replace(t.lowercase(), "")
            if (next.length == t.length) break
            t = t.takeLast(next.length)
        }
        return t.split(' ').filter { it.isNotBlank() }.take(4).joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    private fun has(spoken: String, vararg any: String): Boolean {
        val ws = words(spoken)
        val padded = " ${ws.joinToString(" ")} "
        return any.any { " $it " in padded }
    }

    fun isYes(spoken: String) = has(spoken, "yes", "yeah", "yep", "yup", "correct", "right", "that is right", "thats right", "sure", "okay", "ok", "confirm", "exactly", "perfect")
    fun isNo(spoken: String) = has(spoken, "no", "nope", "wrong", "incorrect", "not right", "not correct", "thats wrong", "that is wrong", "again", "change it", "redo", "mistake", "not that")
    fun isSave(spoken: String) = has(spoken, "save", "save it", "yes", "yeah", "yep", "confirm", "okay", "ok", "do it", "go ahead", "correct", "right")
    fun isCancel(spoken: String) = has(spoken, "cancel", "stop", "forget it", "never mind", "nevermind", "abort", "leave it", "dont save", "do not save", "exit", "quit")
    fun wantsNameChange(spoken: String) = has(spoken, "change the name", "wrong name", "name is wrong", "fix the name", "correct the name", "spell the name", "change name")
    fun wantsNumberChange(spoken: String) = has(spoken, "change the number", "wrong number", "number is wrong", "fix the number", "correct the number", "change number")
}

/**
 * Turns an address into something that can be said aloud. Phone address services often answer with a "plus code" such as
 * "XPXF+9Q Uyo" for a place, which sounds like gibberish read out; those are dropped, leaving street, area, town and state.
 */
object HumanPlace {
    private const val CODE = "[23456789CFGHJMPQRVWX]"
    private val leadingPlusCode = Regex("^$CODE{2,8}\\+$CODE{0,4}\\s*,?\\s*", RegexOption.IGNORE_CASE)

    /** [s] without any plus code in front, or null when nothing readable is left (empty, only a code, or only a number). */
    fun clean(s: String?): String? {
        val t = s?.trim().orEmpty().replace(leadingPlusCode, "").trim().trim(',').trim()
        if (t.isEmpty() || t.all { it.isDigit() || it == ' ' || it == '-' }) return null
        return t
    }

    /** "Ikot Ekpene Road, Uyo, Akwa Ibom State", from whichever parts are known; null when none are. */
    fun compose(street: String?, area: String?, city: String?, state: String?): String? {
        val parts = mutableListOf<String>()
        for (p in listOf(street, area, city, state)) {
            val c = clean(p) ?: continue
            if (parts.none { it.equals(c, ignoreCase = true) }) parts += c
        }
        return parts.take(4).joinToString(", ").ifBlank { null }
    }
}
