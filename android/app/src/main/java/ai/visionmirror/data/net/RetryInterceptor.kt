package ai.visionmirror.data.net

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Phone networks drop packets, switch between wifi and mobile data, and wake up slowly. A request that
 * never reached the server (or got a gateway error) is safe to send again, so do that quietly instead of
 * telling the user it failed.
 *
 * Not retried: a read timeout, because the server may be halfway through a paid AI call (the view model
 * has its own, visible, retry for that), and anything while the call is cancelled.
 */
class RetryInterceptor(
    private val maxRetries: Int = 2,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        while (true) {
            try {
                val response = chain.proceed(chain.request())
                if (response.code !in GATEWAY_ERRORS || attempt >= maxRetries) return response
                response.close()
            } catch (e: IOException) {
                if (attempt >= maxRetries || chain.call().isCanceled() || !neverReachedServer(e)) throw e
            }
            sleep(BACKOFF_MS shl attempt)
            attempt++
        }
    }

    private fun neverReachedServer(e: IOException): Boolean =
        e !is SocketTimeoutException || e.message?.contains("connect", ignoreCase = true) == true

    private companion object {
        val GATEWAY_ERRORS = setOf(502, 503, 504)
        const val BACKOFF_MS = 500L
    }
}
