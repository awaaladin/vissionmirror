package ai.visionmirror.data.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException

private fun Request.needsAuth(): Boolean {
    val path = url.encodedPath
    return !path.endsWith("/auth/anon") && !path.endsWith("/health")
}

/** Adds `Authorization: Bearer ...` to every endpoint that needs it. */
class BearerInterceptor(private val tokens: TokenProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.needsAuth()) return chain.proceed(request)
        val token = try {
            runBlocking { tokens.token() }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Could not get a token", e)
        }
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

/**
 * On 401 (`unauthorized`): fetch a new token and retry the call exactly once, as docs/api.md says.
 * Returning null after the retry stops OkHttp from looping.
 */
class RefreshAuthenticator(private val tokens: TokenProvider) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.header(RETRIED) != null) return null
        val rejected = response.request.header("Authorization")?.removePrefix("Bearer ")
        val fresh = try {
            runBlocking { tokens.token(stale = rejected) }
        } catch (e: Exception) {
            return null
        }
        return response.request.newBuilder()
            .header("Authorization", "Bearer $fresh")
            .header(RETRIED, "1")
            .build()
    }

    private companion object { const val RETRIED = "X-VM-Retried" }
}
