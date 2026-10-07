package ai.visionmirror.data.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
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
}

/** Authenticated endpoints. Responses are raw so the repository can read error bodies and headers. */
interface MirrorApi {
    @Multipart
    @POST("v1/describe")
    suspend fun describe(
        @Part image: MultipartBody.Part,
        @Part("detail_level") detailLevel: RequestBody,
        @Part("language") language: RequestBody,
    ): Response<DescribeResponse>

    @POST("v1/ask")
    suspend fun ask(@Body body: AskRequest): Response<AskResponse>

    @DELETE("v1/session/{id}")
    suspend fun deleteSession(@Path("id") id: String): Response<Unit>

    @GET("v1/health")
    suspend fun health(): Response<Unit>
}
