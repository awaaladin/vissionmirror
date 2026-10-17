package ai.visionmirror.audio

/**
 * Who she is talking to: Adanna (the default, a woman's voice) or Bola (a man's voice). Both answer to their own name,
 * and "switch to Bola" or "switch to Adanna" changes the voice, the name she introduces herself with, and the name that
 * wakes her.
 */
enum class Persona(val displayName: String, val male: Boolean) {
    Adanna("Adanna", male = false),
    Bola("Bola", male = true),
    ;

    companion object {
        fun from(name: String?): Persona = entries.firstOrNull { it.name == name } ?: Adanna
    }
}
