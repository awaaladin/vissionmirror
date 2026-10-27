package ai.visionmirror.data.repo

import ai.visionmirror.data.api.AppError
import ai.visionmirror.data.api.AskRequest
import ai.visionmirror.data.api.AssistRequest
import ai.visionmirror.data.api.AssistResponse
import ai.visionmirror.data.api.ReadResponse
import ai.visionmirror.data.api.AskResponse
import ai.visionmirror.data.api.DescribeResponse
import ai.visionmirror.data.api.DetailLevel
import ai.visionmirror.data.api.ErrorBody
import ai.visionmirror.data.api.MirrorApi
import ai.visionmirror.data.settings.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

interface MirrorRepository {
    /** [scene] = describe the surroundings ("look around") instead of the person's outfit. */
    suspend fun describe(jpeg: ByteArray, detail: DetailLevel, language: String = "en", scene: Boolean = false): Result<DescribeResponse>
    suspend fun ask(sessionId: String, question: String, language: String = "en"): Result<AskResponse>

    /** The weather, or an answer to a general question. */
    suspend fun assist(query: String, language: String = "en"): Result<AssistResponse>

    /** The writing in the photo, read aloud and made sense of. [ocrText] is what the phone's own scanner found (it may be wrong). */
    suspend fun read(jpeg: ByteArray, ocrText: String, language: String = "en"): Result<ReadResponse> =
        Result.failure(IllegalStateException("reading is not available"))

    /** Fire-and-forget cleanup. A 404 means "already gone" and counts as success. */
    suspend fun deleteSession(sessionId: String)
}

@Singleton
class MirrorRepositoryImpl @Inject constructor(
    private val api: MirrorApi,
    private val json: Json,
    private val settings: SettingsStore,
) : MirrorRepository {

    /** Who is talking (Adanna or Bola), so the AI answers as that name and never as the other. */
    private suspend fun assistantName(): String = settings.settings.first().persona.displayName


    override suspend fun describe(jpeg: ByteArray, detail: DetailLevel, language: String, scene: Boolean): Result<DescribeResponse> =
        call {
            // EXIF and location were already stripped when the JPEG was re-encoded (see ImageProcessor).
            val image = MultipartBody.Part.createFormData(
                "image", "photo.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()),
            )
            val text = "text/plain".toMediaType()
            api.describe(
                image, detail.wire.toRequestBody(text), language.toRequestBody(text),
                (if (scene) "scene" else "mirror").toRequestBody(text),
                assistantName().toRequestBody(text),
            )
        }

    override suspend fun ask(sessionId: String, question: String, language: String): Result<AskResponse> =
        call { api.ask(AskRequest(sessionId, question.take(MAX_QUESTION), language, assistantName())) }

    override suspend fun read(jpeg: ByteArray, ocrText: String, language: String): Result<ReadResponse> =
        call {
            val image = MultipartBody.Part.createFormData("image", "photo.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            val text = "text/plain".toMediaType()
            api.read(image, ocrText.take(3_500).toRequestBody(text), language.toRequestBody(text), assistantName().toRequestBody(text))
        }

    override suspend fun assist(query: String, language: String): Result<AssistResponse> =
        call { api.assist(AssistRequest(query.take(400), language, assistantName())) }

    override suspend fun deleteSession(sessionId: String) {
        try {
            api.deleteSession(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: sessions also expire on their own after 10 minutes.
        }
    }

    private suspend fun <T> call(block: suspend () -> Response<T>): Result<T> = try {
        val response = block()
        val body = response.body()
        if (response.isSuccessful && body != null) Result.success(body) else Result.failure(mapError(response))
    } catch (e: CancellationException) {
        throw e
    } catch (e: SocketTimeoutException) {
        // Connected but slow (the AI can take 10-15 s): not the same as being offline.
        Result.failure(AppError.NetworkTimeout("That is taking longer than I expected. Please try again."))
    } catch (e: IOException) {
        Result.failure(AppError.Offline(e))
    } catch (e: Exception) {
        Result.failure(AppError.Unexpected(e))
    }

    private fun mapError(response: Response<*>): AppError {
        val body = runCatching {
            response.errorBody()?.string()?.let { json.decodeFromString(ErrorBody.serializer(), it) }
        }.getOrNull()
        val spoken = body?.spokenText?.takeIf { it.isNotBlank() }
        return when (response.code()) {
            // Today's fair share is used up, or a subscription is needed: waiting a few seconds cannot help, so say it kindly and stop.
            402 -> AppError.SubscriptionRequired(spoken ?: "To keep using VisionMirror, please subscribe. Say subscribe and I'll open our website.")
            401 -> AppError.Unauthorized(spoken ?: "I couldn't sign in just now. Please try again.")
            404 -> AppError.SessionExpired(spoken ?: "That photo has expired. Let's take a new one.")
            413 -> AppError.ImageTooLarge(spoken ?: "That photo was too big. Let's take another.")
            415 -> AppError.UnsupportedImage(spoken ?: "I couldn't read that photo. Let's take another.")
            // The service's daily AI budget is spent: retrying in a couple of minutes cannot help, so don't.
            429 -> if (body?.code == "daily_limit_reached" || body?.code == "fair_use_limit") {
                AppError.Server(spoken ?: "I've reached my limit for today. Please try again tomorrow.")
            } else {
                val wait = response.headers()["Retry-After"]?.trim()?.toIntOrNull()
                    ?.coerceIn(1, MAX_RETRY_AFTER) ?: AppError.DEFAULT_RETRY_SECONDS
                AppError.RateLimited(wait, spoken ?: "I'm a little busy. I'll try again in $wait seconds.")
            }
            422 -> AppError.Unexpected()
            in 500..599 -> AppError.Server(spoken ?: "My servers are having a moment. Please try again shortly.")
            else -> AppError.Unexpected()
        }
    }

    private companion object {
        const val MAX_QUESTION = 500 // API limit
        const val MAX_RETRY_AFTER = 120
    }
}
