package ai.visionmirror.guide

/** Where something is in front of her, from the middle of its box in the picture. */
enum class Zone(val say: String) { Left("on your left"), Ahead("ahead"), Right("on your right") }

/**
 * Roughly how far, from how much of the picture its box fills. There is no depth sensor, so this is an estimate: a person
 * who fills most of the picture is close, one who fills a sliver is far.
 */
enum class Range(val say: String, val closeness: Float) {
    VeryClose("very close", 1f),
    Close("close", 0.7f),
    Near("a few steps away", 0.4f),
    Far("far away", 0.1f),
}

/** One detection, with its box as fractions (0..1) of the picture. */
data class Detected(val label: String, val left: Float, val top: Float, val right: Float, val bottom: Float, val score: Float)

data class Sighting(val name: String, val zone: Zone, val range: Range, val hazard: Boolean)

object GuideLogic {
    // Things that can move into her path or hurt her: they get spoken first and interrupt.
    private val hazards = setOf(
        "person", "car", "bus", "truck", "bicycle", "motorcycle", "train", "dog", "horse", "cow", "sheep", "bear",
        "fire hydrant", "stop sign", "parking meter", "bench", "chair", "couch", "bed", "dining table", "potted plant", "suitcase",
    )

    private val friendly = mapOf(
        "dining table" to "table", "couch" to "sofa", "tv" to "television", "cell phone" to "phone", "potted plant" to "plant",
        "fire hydrant" to "fire hydrant", "motorcycle" to "motorbike", "bicycle" to "bicycle", "laptop" to "laptop",
        "refrigerator" to "fridge", "sports ball" to "ball", "wine glass" to "glass", "hair drier" to "hair dryer",
    )

    fun zoneOf(centreX: Float): Zone = when {
        centreX < 0.38f -> Zone.Left
        centreX > 0.62f -> Zone.Right
        else -> Zone.Ahead
    }

    fun rangeOf(boxHeight: Float, boxWidth: Float): Range {
        val size = maxOf(boxHeight, boxWidth)
        return when {
            size >= 0.70f -> Range.VeryClose
            size >= 0.40f -> Range.Close
            size >= 0.20f -> Range.Near
            else -> Range.Far
        }
    }

    fun nameOf(label: String): String = friendly[label] ?: label

    fun sightings(detections: List<Detected>): List<Sighting> = detections.map {
        val width = it.right - it.left
        val height = it.bottom - it.top
        Sighting(nameOf(it.label), zoneOf((it.left + it.right) / 2f), rangeOf(height, width), it.label in hazards)
    }
}

/**
 * Decides what is worth saying. The picture changes many times a second, so it only speaks when something new appears,
 * something gets closer, or something very close is still there; and never more than once every second or so, because a
 * stream of chatter is worse than silence.
 */
class GuideAnnouncer(private val now: () -> Long = { System.currentTimeMillis() }) {
    data class Announcement(val text: String, val urgent: Boolean)

    private class Seen(var range: Range, var at: Long)

    private val seen = HashMap<String, Seen>()
    private var lastSpoke = 0L

    fun next(sightings: List<Sighting>): Announcement? {
        val t = now()
        if (t - lastSpoke < MIN_GAP_MS) return null
        // Forget what has been gone a while, so it is announced again if it comes back.
        seen.entries.removeAll { t - it.value.at > FORGET_MS }

        val worthIt = sightings.filter { it.range != Range.Far }.filter { s ->
            val key = key(s)
            val before = seen[key]
            when {
                before == null -> true
                s.range.closeness > before.range.closeness -> true // it got closer
                s.range == Range.VeryClose && t - before.at > REPEAT_VERY_CLOSE_MS -> true
                else -> false
            }
        }
        // What is still in view stays "seen", and its range follows it, so something that backs away and returns closer is news again.
        sightings.forEach { s -> if (s !in worthIt) seen[key(s)]?.let { it.at = t; it.range = s.range } }
        if (worthIt.isEmpty()) return null

        val ordered = worthIt.sortedWith(
            compareByDescending<Sighting> { it.range.closeness }.thenByDescending { it.hazard }.thenBy { if (it.zone == Zone.Ahead) 0 else 1 },
        )
        // Group the same thing in the same place and range ("two chairs on your left").
        val groups = ordered.groupBy { Triple(it.name, it.zone, it.range) }.entries.take(MAX_PER_MESSAGE)
        val parts = groups.map { (k, items) -> phrase(k.first, items.size, k.second, k.third) }
        worthIt.forEach { s -> seen[key(s)] = Seen(s.range, t) }
        lastSpoke = t
        val urgent = ordered.first().let { it.range == Range.VeryClose && (it.hazard || it.zone == Zone.Ahead) }
        return Announcement(parts.joinToString(". ") + ".", urgent)
    }

    fun reset() { seen.clear(); lastSpoke = 0L }

    private fun key(s: Sighting) = "${s.name}|${s.zone}"

    private fun phrase(name: String, count: Int, zone: Zone, range: Range): String {
        val subject = if (count == 1) name.replaceFirstChar { it.uppercase() } else "$count ${plural(name)}"
        return "$subject ${zone.say}, ${range.say}"
    }

    private fun plural(name: String): String = when {
        name == "person" -> "people"
        name.endsWith("s") || name.endsWith("sh") || name.endsWith("ch") -> name + "es"
        else -> name + "s"
    }

    private companion object {
        const val MIN_GAP_MS = 1_200L
        const val FORGET_MS = 8_000L
        const val REPEAT_VERY_CLOSE_MS = 3_000L
        const val MAX_PER_MESSAGE = 2
    }
}
