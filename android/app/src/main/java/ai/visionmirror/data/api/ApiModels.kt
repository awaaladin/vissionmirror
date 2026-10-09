package ai.visionmirror.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors docs/api.md. Field names are snake_case on the wire; `color` (US) vs `colour_harmony` (UK) are intentional. */

@Serializable
data class AnonAuthRequest(@SerialName("device_id") val deviceId: String)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("expires_in") val expiresIn: Long,
)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class UserOut(
    val id: String,
    val email: String,
    @SerialName("display_name") val displayName: String? = null,
)

/** Sign up and sign in both answer with a long-lived token for the account. */
@Serializable
data class AccountAuthResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("expires_in") val expiresIn: Long,
    val user: UserOut,
)

enum class DetailLevel(val wire: String, val label: String) {
    Brief("brief", "Brief"),
    Standard("standard", "Standard"),
    Detailed("detailed", "Detailed"),
}

@Serializable
data class ImageQuality(
    val usable: Boolean,
    val issue: String? = null,
    val advice: String? = null,
)

@Serializable
data class OutfitItem(
    val item: String,
    val description: String,
    val color: String? = null,
    val pattern: String? = null,
)

@Serializable
enum class Severity {
    @SerialName("low") Low,
    @SerialName("medium") Medium,
    @SerialName("high") High,
}

@Serializable
data class Issue(
    val severity: Severity = Severity.Medium,
    val what: String,
    val where: String,
    val suggestion: String,
)

@Serializable
enum class Verdict {
    @SerialName("good") Good,
    @SerialName("mixed") Mixed,
    @SerialName("clashing") Clashing,
    @SerialName("unsure") Unsure,
}

@Serializable
data class ColourHarmony(
    val verdict: Verdict = Verdict.Unsure,
    val explanation: String = "",
)

@Serializable
data class DescribeResponse(
    /** Null when [imageQuality].usable is false: nothing was stored, so there is nothing to ask about. */
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("image_quality") val imageQuality: ImageQuality,
    val summary: String = "",
    val outfit: List<OutfitItem> = emptyList(),
    @SerialName("hair_and_grooming") val hairAndGrooming: String = "",
    val accessories: List<String> = emptyList(),
    val issues: List<Issue> = emptyList(),
    @SerialName("colour_harmony") val colourHarmony: ColourHarmony = ColourHarmony(),
    @SerialName("spoken_text") val spokenText: String,
    val confidence: Float = 0f,
)

@Serializable
data class AskRequest(
    @SerialName("session_id") val sessionId: String,
    val question: String,
    val language: String = "en",
)

@Serializable
data class AskResponse(
    @SerialName("answer_text") val answerText: String,
    @SerialName("spoken_text") val spokenText: String,
    val confidence: Float = 0f,
)

/** Every non-422 error body. */
@Serializable
data class ErrorBody(
    val code: String,
    val message: String = "",
    @SerialName("spoken_text") val spokenText: String = "",
)
