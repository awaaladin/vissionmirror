package ai.visionmirror.data.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

/** No auth header: used by the token flow itself (its own OkHttp client, so no re-auth loop). */
interface AuthApi {
    @POST("v1/auth/anon")
    suspend fun anon(@Body body: AnonAuthRequest): Response<TokenResponse>

    @POST("v1/auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<AccountAuthResponse>

    @POST("v1/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<AccountAuthResponse>
}

/** Authenticated endpoints. Responses are raw so the repository can read error bodies and headers. */
interface MirrorApi {
    @Multipart
    @POST("v1/describe")
    suspend fun describe(
        @Part image: MultipartBody.Part,
        @Part("detail_level") detailLevel: RequestBody,
        @Part("language") language: RequestBody,
        @Part("mode") mode: RequestBody,
        @Part("assistant") assistant: RequestBody,
    ): Response<DescribeResponse>

    /** Reads the writing in a photo: fixes scanner mistakes, completes cut-off words, explains abbreviations. */
    @Multipart
    @POST("v1/read")
    suspend fun read(
        @Part image: MultipartBody.Part,
        @Part("ocr_text") ocrText: RequestBody,
        @Part("language") language: RequestBody,
        @Part("assistant") assistant: RequestBody,
    ): Response<ReadResponse>

    @POST("v1/ask")
    suspend fun ask(@Body body: AskRequest): Response<AskResponse>

    /** Nigerian English audio (MP3) for [body]. 501 or any failure means: use the phone's own voice. */
    @POST("v1/speech")
    suspend fun speech(@Body body: SpeechRequest): Response<ResponseBody>

    /** Speech to text for Hausa, Yoruba and Igbo: the recording (audio/wav) is the body. */
    @POST("v1/transcribe")
    suspend fun transcribe(@retrofit2.http.Query("language") language: String, @Body audio: okhttp3.RequestBody): Response<TranscribeResponse>

    /** Weather and general questions, for the voice assistant. */
    @POST("v1/assist")
    suspend fun assist(@Body body: AssistRequest): Response<AssistResponse>

    @DELETE("v1/session/{id}")
    suspend fun deleteSession(@Path("id") id: String): Response<Unit>

    /** Permanently deletes the signed-in account on the server. */
    @DELETE("v1/auth/me")
    suspend fun deleteAccount(): Response<Unit>

    /** Is this account paid up, until when, and the website page to pay on. */
    @GET("v1/auth/subscription")
    suspend fun subscription(): Response<SubscriptionResponse>

    @GET("v1/health")
    suspend fun health(): Response<Unit>
}
