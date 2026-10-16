package ai.visionmirror.audio

import java.util.Locale

/**
 * The language she hears. English is Nigerian English: the phone's Nigerian voice reads it and the backend
 * writes it the way a Nigerian friend would say it. The other four are translated by the backend (VoiceVerse).
 *
 * Phones rarely have a voice or recogniser for Hausa, Yoruba, Igbo or Ibibio, so [speech] and [listening] list what to
 * try, in order. The last entry is always something every phone can do.
 */
enum class ResponseLanguage(
    val label: String,
    /** Sent to the backend as `language`. */
    val apiTag: String,
    private val speech: List<Locale>,
    private val listening: String,
) {
    NigerianEnglish("English", "en-NG", listOf(Locale("en", "NG")), "en-NG"),
    Hausa("Hausa", "ha", listOf(Locale("ha", "NG"), Locale("ha")), "ha-NG"),
    Yoruba("Yoruba", "yo", listOf(Locale("yo", "NG"), Locale("yo")), "yo-NG"),
    Igbo("Igbo", "ig", listOf(Locale("ig", "NG"), Locale("ig")), "ig-NG"),
    // No phone recogniser or voice exists for Ibibio yet: it is read with the Nigerian English voice.
    Ibibio("Ibibio", "ibb", emptyList(), "en-NG");

    /** Voices to try, best first. */
    fun speechLocales(): List<Locale> = speech + Locale("en", "NG") + Locale.getDefault() + Locale.US

    /** Language tag for the speech recogniser. */
    fun recognitionTag(): String = listening

    companion object {
        fun fromApiTag(tag: String?): ResponseLanguage = entries.firstOrNull { it.apiTag == tag } ?: NigerianEnglish
    }
}
