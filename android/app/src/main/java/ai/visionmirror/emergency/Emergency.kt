package ai.visionmirror.emergency

import ai.visionmirror.data.settings.EmergencyContact
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class GeoPoint(val lat: Double, val lon: Double) {
    val mapLink: String get() = "https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, lat, lon)
}

/** Where the phone is right now, or null when it cannot tell (location off, no fix, no permission). */
interface LocationSource { suspend fun current(): GeoPoint? }

/** A street address in words, or null when none is available (offline, nothing known there). */
interface AddressLookup { suspend fun describe(point: GeoPoint): String? }

interface SmsSender {
    /** True when the phone accepted the message for sending. */
    fun send(number: String, text: String): Boolean
}

interface ContactBook {
    /** People in the phone's contacts whose name contains [name]. */
    suspend fun find(name: String): List<EmergencyContact>
}

interface PhoneActions {
    /** Opens the dialer with [number] filled in. She taps call; nothing is dialled without that. */
    fun dial(number: String)
}

sealed interface ShareResult {
    data object NoContacts : ShareResult
    data object NoLocation : ShareResult
    data class Sent(val names: List<String>, val failed: List<String>, val where: String) : ShareResult
}

/**
 * Where am I, and tell someone. Nothing here decides when to send: the voice layer asks her first (or gives a spoken
 * countdown she can cancel), so a message is never sent by accident.
 */
@Singleton
class Emergency @Inject constructor(
    private val location: LocationSource,
    private val address: AddressLookup,
    private val sms: SmsSender,
) {
    /** What she hears for "where am I". */
    suspend fun whereAmI(): String {
        val point = location.current() ?: return NO_LOCATION
        val place = address.describe(point)
        return if (place != null) "You are near $place." else "I can see your position but not the street name. Your coordinates are ${coords(point)}."
    }

    suspend fun share(
        contacts: List<EmergencyContact>,
        reason: String,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): ShareResult {
        if (contacts.isEmpty()) return ShareResult.NoContacts
        val point = location.current() ?: return ShareResult.NoLocation
        val place = address.describe(point)
        val text = message(reason, place, point, now)
        val sent = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (c in contacts) if (sms.send(c.number, text)) sent += c.name else failed += c.name
        return ShareResult.Sent(sent, failed, place ?: coords(point))
    }

    companion object {
        const val NO_LOCATION = "I can't find your location. Check that location is switched on, and try near a window or outside."

        fun coords(p: GeoPoint) = "%.4f, %.4f".format(Locale.US, p.lat, p.lon)

        fun message(reason: String, place: String?, point: GeoPoint, now: ZonedDateTime): String {
            val time = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).format(now)
            val where = place?.let { "I am near $it. " } ?: ""
            return "$reason ${where}My location: ${point.mapLink} (sent by VisionMirror at $time)"
        }

        /** The last four digits, so she can confirm a number without it being read out in full. */
        fun endingIn(number: String): String = number.filter { it.isDigit() }.takeLast(4).toCharArray().joinToString(" ")

        fun say(result: ShareResult): String = when (result) {
            ShareResult.NoContacts -> "You have no emergency contact yet. Say: set my emergency contact to, and a name."
            ShareResult.NoLocation -> NO_LOCATION
            is ShareResult.Sent -> when {
                result.names.isEmpty() -> "I couldn't send the message. Check that this phone can send text messages."
                result.failed.isEmpty() -> "I sent your location to ${list(result.names)}. You are near ${result.where}."
                else -> "I sent your location to ${list(result.names)}, but couldn't reach ${list(result.failed)}."
            }
        }

        private fun list(names: List<String>): String = when (names.size) {
            1 -> names[0]
            2 -> "${names[0]} and ${names[1]}"
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
    }
}

/** What she asked about the emergency features, from her words. */
sealed interface EmergencyIntent {
    data object WhereAmI : EmergencyIntent
    data object Sos : EmergencyIntent
    data class Share(val name: String?) : EmergencyIntent
    data class SetContact(val name: String) : EmergencyIntent
    data object ListContacts : EmergencyIntent
    data object ClearContacts : EmergencyIntent
    data object CallEmergency : EmergencyIntent
}

object EmergencyIntents {
    private fun normalise(s: String) =
        s.lowercase().replace("'", "").replace("’", "").replace(Regex("[^a-z0-9 ]"), " ").trim().replace(Regex("\\s+"), " ")

    private val setContact = listOf(
        Regex("^(?:set|add|make|change|save) (?:my )?emergency contacts? (?:to|as) (.+)$"),
        Regex("^(?:add|make|save) (.+?) (?:as )?(?:my )?emergency contacts?$"),
        Regex("^my emergency contact is (.+)$"),
    )
    private val share = Regex("^(?:send|share|text|message) (?:my )?(?:location|position)(?: (?:to|with))?(?: (.+))?$")

    fun parse(utterance: String): EmergencyIntent? {
        val text = normalise(utterance)
        if (text.isEmpty() || text.split(' ').size > 12) return null
        fun has(vararg p: String) = p.any { " $it " in " $text " }

        for (r in setContact) r.find(text)?.let { return EmergencyIntent.SetContact(it.groupValues[1].trim()) }
        if (has("who is my emergency contact", "who are my emergency contacts", "read my emergency contacts", "list my emergency contacts")) {
            return EmergencyIntent.ListContacts
        }
        if (has("remove my emergency contact", "remove my emergency contacts", "delete my emergency contact", "delete my emergency contacts",
                "clear my emergency contacts", "clear emergency contacts", "remove emergency contacts")
        ) return EmergencyIntent.ClearContacts
        share.find(text)?.let { return EmergencyIntent.Share(it.groupValues[1].trim().ifBlank { null }) }
        if (has("call 112", "dial 112", "call 199", "call the police", "call emergency", "call an ambulance", "call the emergency number")) {
            return EmergencyIntent.CallEmergency
        }
        if (has("where am i", "what is my location", "whats my location", "my location", "what street am i on", "which place am i")) {
            return EmergencyIntent.WhereAmI
        }
        if (has("i need help", "help me", "emergency", "sos", "send help", "call for help", "i am in danger", "im in danger", "i have been attacked")) {
            return EmergencyIntent.Sos
        }
        return null
    }

    /** "yes", "yeah", "okay", "send it": agreement. Anything else means no. */
    fun isYes(reply: String): Boolean {
        val words = normalise(reply).split(' ').toSet()
        return words.any { it in setOf("yes", "yeah", "yep", "yup", "okay", "ok", "sure", "confirm", "correct", "please") } ||
            normalise(reply) in setOf("send it", "do it", "go ahead", "save it")
    }
}
