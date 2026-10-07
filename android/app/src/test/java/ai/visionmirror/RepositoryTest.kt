package ai.visionmirror

import ai.visionmirror.data.api.AppError
import ai.visionmirror.data.api.AuthApi
import ai.visionmirror.data.api.DetailLevel
import ai.visionmirror.data.api.MirrorApi
import ai.visionmirror.data.api.Severity
import ai.visionmirror.data.auth.BearerInterceptor
import ai.visionmirror.data.auth.DeviceIdStore
import ai.visionmirror.data.auth.RefreshAuthenticator
import ai.visionmirror.data.auth.TokenProvider
import ai.visionmirror.data.repo.MirrorRepositoryImpl
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real token flow, error mapping and Retry-After handling against a local server. */
class RepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: MirrorRepositoryImpl
    private val tokensIssued = AtomicInteger()
    private val describeCalls = AtomicInteger()
    private val seenAuth = CopyOnWriteArrayList<String?>()

    private var describeBehaviour: (Int) -> MockResponse = { ok() }

    private val describeJson = """
        {"session_id":"b1c5f3c2-6a7e-4c0a-9a43-2f4d1b7a8e11",
         "image_quality":{"usable":true,"issue":null,"advice":null},
         "summary":"s","outfit":[{"item":"top","description":"Blouse","color":"white","pattern":null}],
         "hair_and_grooming":"","accessories":[],
         "issues":[{"severity":"medium","what":"mark","where":"collar","suggestion":"dab it"}],
         "colour_harmony":{"verdict":"good","explanation":"classic"},
         "spoken_text":"You look crisp.","confidence":0.7, "future_field": 1}
    """.trimIndent()

    private fun ok() = MockResponse().setBody(describeJson).setHeader("Content-Type", "application/json")

    private fun err(code: Int, body: String, vararg headers: Pair<String, String>) =
        MockResponse().setResponseCode(code).setBody(body).also { r -> headers.forEach { r.setHeader(it.first, it.second) } }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/v1/auth/anon") ->
                    MockResponse().setBody(
                        """{"access_token":"token-${tokensIssued.incrementAndGet()}","token_type":"bearer","expires_in":86400}""",
                    ).setHeader("Content-Type", "application/json")
                request.path!!.endsWith("/v1/describe") -> {
                    seenAuth += request.getHeader("Authorization")
                    describeBehaviour(describeCalls.incrementAndGet())
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
        fun retrofit(client: OkHttpClient) = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val plain = OkHttpClient()
        val tokens = TokenProvider(
            retrofit(plain).create(AuthApi::class.java),
            DeviceIdStore { "3f2b8c1e-9d4a-4c55-8a51-0e7d3c2b1a90" },
            TokenProvider.Clock { System.currentTimeMillis() },
        )
        val client = plain.newBuilder()
            .addInterceptor(BearerInterceptor(tokens))
            .authenticator(RefreshAuthenticator(tokens))
            .build()
        repo = MirrorRepositoryImpl(retrofit(client).create(MirrorApi::class.java), json)
    }

    @After fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test fun describeParsesTheContractAndIgnoresUnknownFields() = runTest {
        val r = repo.describe(byteArrayOf(1, 2, 3), DetailLevel.Standard).getOrThrow()
        assertEquals("You look crisp.", r.spokenText)
        assertEquals("b1c5f3c2-6a7e-4c0a-9a43-2f4d1b7a8e11", r.sessionId)
        assertEquals("white", r.outfit.single().color)
        assertEquals(0.7f, r.confidence, 0f)
        assertEquals(Severity.Medium, r.issues.single().severity)
        assertEquals("Bearer token-1", seenAuth.single())
    }

    @Test fun a401RefreshesTheTokenAndRetriesExactlyOnce() = runTest {
        describeBehaviour = { n ->
            if (n == 1) err(401, """{"code":"unauthorized","message":"m","spoken_text":"s"}""") else ok()
        }
        val r = repo.describe(byteArrayOf(1), DetailLevel.Brief)
        assertTrue(r.isSuccess)
        assertEquals(2, describeCalls.get())
        assertEquals(listOf("Bearer token-1", "Bearer token-2"), seenAuth.toList())
    }

    @Test fun aSecond401IsReportedNotLooped() = runTest {
        describeBehaviour = { err(401, """{"code":"unauthorized","message":"m","spoken_text":"Please sign in again."}""") }
        val e = repo.describe(byteArrayOf(1), DetailLevel.Brief).exceptionOrNull()
        assertTrue("was $e", e is AppError.Unauthorized)
        assertEquals(2, describeCalls.get())
    }

    @Test fun rateLimitCarriesRetryAfter() = runTest {
        describeBehaviour = {
            err(429, """{"code":"rate_limited","message":"m","spoken_text":"Slow down a little."}""", "Retry-After" to "7")
        }
        val e = repo.describe(byteArrayOf(1), DetailLevel.Brief).exceptionOrNull() as AppError.RateLimited
        assertEquals(7, e.retryAfterSeconds)
        assertEquals("Slow down a little.", e.spokenText)
    }

    @Test fun rateLimitWithoutHeaderUsesADefault() = runTest {
        describeBehaviour = { err(429, """{"code":"rate_limited","message":"m","spoken_text":""}""") }
        val e = repo.describe(byteArrayOf(1), DetailLevel.Brief).exceptionOrNull() as AppError.RateLimited
        assertEquals(AppError.DEFAULT_RETRY_SECONDS, e.retryAfterSeconds)
    }

    @Test fun serverSpokenTextIsUsedVerbatim() = runTest {
        describeBehaviour = {
            err(415, """{"code":"unsupported_image","message":"m","spoken_text":"I can not read that photo."}""")
        }
        val e = repo.describe(byteArrayOf(1), DetailLevel.Brief).exceptionOrNull()
        assertTrue(e is AppError.UnsupportedImage)
        assertEquals("I can not read that photo.", e!!.spokenText)
    }

    @Test fun missingSessionMapsToSessionExpired() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when {
                request.path!!.endsWith("/auth/anon") ->
                    MockResponse().setBody("""{"access_token":"t","token_type":"bearer","expires_in":86400}""")
                        .setHeader("Content-Type", "application/json")
                else -> err(404, """{"code":"session_not_found","message":"m","spoken_text":"That photo has expired."}""")
            }
        }
        val e = repo.ask("b1c5f3c2-6a7e-4c0a-9a43-2f4d1b7a8e11", "is it a stain?").exceptionOrNull()
        assertTrue(e is AppError.SessionExpired)
    }

    @Test fun noNetworkMapsToOffline() = runTest {
        server.shutdown()
        val e = repo.describe(byteArrayOf(1), DetailLevel.Brief).exceptionOrNull()
        assertTrue("was $e", e is AppError.Offline)
    }
}
