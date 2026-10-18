package ai.visionmirror.audio

import ai.visionmirror.data.api.MirrorApi
import ai.visionmirror.data.api.SpeechRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** A natural Nigerian voice from the backend (Azure en-NG). Returns MP3 bytes, or null: speak with the phone instead. */
interface CloudVoice {
    suspend fun synthesize(text: String, rate: Float, male: Boolean = false, timeoutMs: Long? = null, language: String = "en"): ByteArray?
}

@Singleton
class BackendCloudVoice @Inject constructor(
    // Lazy: the voice is created at start-up, the network stack only when first needed.
    private val api: dagger.Lazy<MirrorApi>,
) : CloudVoice {
    private var failures = 0
    private var pausedUntil = 0L
    private var busyUntil = 0L

    override suspend fun synthesize(text: String, rate: Float, male: Boolean, timeoutMs: Long?, language: String): ByteArray? {
        if (System.currentTimeMillis() < maxOf(pausedUntil, busyUntil)) return null
        val bytes = try {
            // Past a few seconds the phone's own voice is the better answer.
            withTimeoutOrNull(timeoutMs ?: TIMEOUT_MS) {
                val response = api.get().speech(SpeechRequest(text, rate, if (male) "male" else "female", language))
                if (response.code() == 429) busyUntil = System.currentTimeMillis() + BUSY_PAUSE_MS
                if (response.isSuccessful) response.body()?.bytes() else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (bytes != null && bytes.isNotEmpty()) {
            failures = 0
            return bytes
        }
        // Offline, unconfigured or failing: stop trying for a while so every sentence is not delayed. ("Too many requests" only
        // pauses briefly, above; it is not an outage and must not push the voice onto the phone's own.)
        if (busyUntil <= System.currentTimeMillis() && ++failures >= 2) {
            failures = 0
            pausedUntil = System.currentTimeMillis() + COOLDOWN_MS
        }
        return null
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val COOLDOWN_MS = 120_000L
        const val BUSY_PAUSE_MS = 8_000L
    }
}
