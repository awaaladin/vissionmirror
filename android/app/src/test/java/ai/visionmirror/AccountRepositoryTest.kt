package ai.visionmirror

import ai.visionmirror.data.api.AppError
import ai.visionmirror.data.api.AuthApi
import ai.visionmirror.data.api.MirrorApi
import ai.visionmirror.data.auth.AccountRepository
import ai.visionmirror.data.auth.AccountStorage
import ai.visionmirror.data.auth.BearerInterceptor
import ai.visionmirror.data.auth.DeviceIdStore
import ai.visionmirror.data.auth.RefreshAuthenticator
import ai.visionmirror.data.auth.StoredAccount
import ai.visionmirror.data.auth.TokenProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** In-memory stand-in for the DataStore-backed storage. */
private class FakeAccountStorage : AccountStorage {
    val flow = MutableStateFlow(StoredAccount())
    override val data = flow
    override suspend fun signIn(token: String, email: String, displayName: String?) =
        flow.update { it.copy(token = token, email = email, displayName = displayName, expired = false) }
    override suspend fun signOut(expired: Boolean) =
        flow.update { it.copy(token = null, email = null, displayName = null, expired = expired) }
    override suspend fun chooseGuest() = flow.update { it.copy(guestChosen = true) }
    override suspend fun clearExpiredNote() = flow.update { it.copy(expired = false) }
}

/** The real token flow, error mapping and account lifecycle against a local server. */
class AccountRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var storage: FakeAccountStorage
    private lateinit var repo: AccountRepository
    private lateinit var tokens: TokenProvider
    private lateinit var api: MirrorApi

    private val guestTokens = AtomicInteger()
    private val seen = CopyOnWriteArrayList<String>() // "METHOD path auth"
    private var loginResponse: () -> MockResponse = { accountOk("user-token-1") }
    private var registerResponse: () -> MockResponse = { accountOk("user-token-1").setResponseCode(201) }
    private var deleteResponse: (String?) -> MockResponse = { MockResponse().setResponseCode(204) }

    private fun accountOk(token: String, email: String = "ada@example.com", name: String? = "Ada") = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"access_token":"$token","token_type":"bearer","expires_in":2592000,
               "user":{"id":"94cc3ab1-81b4-4ab8-a878-40b7d343c9a5","email":"$email"${name?.let { ""","display_name":"$it"""" }.orEmpty()}}}""",
        )

    private fun err(code: Int, body: String, vararg headers: Pair<String, String>) =
        MockResponse().setResponseCode(code).setBody(body).also { r -> headers.forEach { r.setHeader(it.first, it.second) } }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                seen += "${request.method} $path ${request.getHeader("Authorization")}"
                return when {
                    path.endsWith("/auth/anon") -> MockResponse().setHeader("Content-Type", "application/json").setBody(
                        """{"access_token":"guest-${guestTokens.incrementAndGet()}","token_type":"bearer","expires_in":86400}""",
                    )
                    path.endsWith("/auth/register") -> registerResponse()
                    path.endsWith("/auth/login") -> loginResponse()
                    path.endsWith("/auth/me") && request.method == "DELETE" -> deleteResponse(request.getHeader("Authorization"))
                    else -> MockResponse().setResponseCode(404)
                }
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
        val authApi = retrofit(plain).create(AuthApi::class.java)
        tokens = TokenProvider(authApi, DeviceIdStore { "3f2b8c1e-9d4a-4c55-8a51-0e7d3c2b1a90" }, TokenProvider.Clock { System.currentTimeMillis() })
        val authed = plain.newBuilder()
            .addInterceptor(BearerInterceptor(tokens))
            .authenticator(RefreshAuthenticator(tokens))
            .build()
        api = retrofit(authed).create(MirrorApi::class.java)
        storage = FakeAccountStorage()
        repo = AccountRepository(authApi, api, storage, tokens, json, CoroutineScope(Dispatchers.Unconfined))
    }

    @After fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test fun startsLoadedAndSignedOut() {
        val s = repo.state.value
        assertTrue(s.loaded)
        assertFalse(s.signedIn)
    }

    @Test fun registerSignsInAndFromThenOnRequestsCarryTheAccountToken() = runTest {
        assertTrue(repo.register(" ada@example.com ", "a long password", "Ada").isSuccess)
        assertTrue(repo.state.value.signedIn)
        assertEquals("ada@example.com", repo.state.value.email)
        assertEquals("Ada", repo.state.value.displayName)
        assertEquals("user-token-1", tokens.token()) // no guest token is fetched while signed in
        assertEquals(0, guestTokens.get())
    }

    @Test fun loginWithAWrongPasswordGivesTheServersSpokenSentence() = runTest {
        loginResponse = { err(401, """{"code":"invalid_credentials","message":"m","spoken_text":"That email or password isn't right. Please try again."}""") }
        val e = repo.login("ada@example.com", "nope").exceptionOrNull()
        assertTrue("was $e", e is AppError.Rejected)
        assertEquals("That email or password isn't right. Please try again.", e!!.message)
        assertFalse(repo.state.value.signedIn)
    }

    @Test fun anEmailThatIsAlreadyUsedIsReportedNotCrashed() = runTest {
        registerResponse = { err(409, """{"code":"email_taken","message":"m","spoken_text":"That email already has an account. Try signing in instead."}""") }
        val e = repo.register("ada@example.com", "a long password", null).exceptionOrNull()
        assertTrue(e is AppError.Rejected)
        assertEquals("That email already has an account. Try signing in instead.", (e as AppError).spokenText)
    }

    @Test fun lockedOutCarriesRetryAfter() = runTest {
        loginResponse = { err(429, """{"code":"rate_limited","message":"m","spoken_text":"Please wait 600 seconds."}""", "Retry-After" to "600") }
        val e = repo.login("ada@example.com", "x").exceptionOrNull() as AppError.RateLimited
        assertEquals(600, e.retryAfterSeconds)
    }

    @Test fun accountsUnavailableIsAServerErrorWithItsOwnSpokenLine() = runTest {
        loginResponse = { err(503, """{"code":"accounts_unavailable","message":"m","spoken_text":"Accounts aren't available right now. You can still use the app as a guest."}""") }
        val e = repo.login("ada@example.com", "x").exceptionOrNull()
        assertTrue(e is AppError.Server)
        assertTrue((e as AppError).spokenText.contains("guest"))
    }

    @Test fun noInternetIsReportedAsOffline() = runTest {
        server.shutdown()
        val e = repo.login("ada@example.com", "x").exceptionOrNull()
        assertTrue("was $e", e is AppError.Offline)
    }

    @Test fun signingOutGoesBackToAGuestToken() = runTest {
        repo.login("ada@example.com", "pw")
        repo.signOut()
        assertFalse(repo.state.value.signedIn)
        assertEquals("guest-1", tokens.token())
    }

    @Test fun aRejectedAccountTokenSignsHerOutAndCarriesOnAsAGuest() = runTest {
        repo.login("ada@example.com", "pw")
        deleteResponse = { err(401, """{"code":"unauthorized","message":"m","spoken_text":"Please sign in again."}""") }
        api.deleteAccount() // 401 with the account token -> refresh -> retried once as a guest
        assertFalse(repo.state.value.signedIn)
        assertTrue("note shown so she knows why", repo.state.value.expired)
        val calls = seen.filter { it.startsWith("DELETE") }
        assertEquals(listOf("DELETE /v1/auth/me Bearer user-token-1", "DELETE /v1/auth/me Bearer guest-1"), calls)
    }

    @Test fun deletingTheAccountCallsTheServerWithHerTokenThenForgetsHer() = runTest {
        repo.login("ada@example.com", "pw")
        assertTrue(repo.deleteAccount().isSuccess)
        assertTrue(seen.contains("DELETE /v1/auth/me Bearer user-token-1"))
        assertFalse(repo.state.value.signedIn)
        assertNull(storage.flow.value.token)
    }

    @Test fun ifTheServerRefusesToDeleteSheStaysSignedIn() = runTest {
        repo.login("ada@example.com", "pw")
        deleteResponse = { err(503, """{"code":"accounts_unavailable","message":"m","spoken_text":"Accounts aren't available right now."}""") }
        assertTrue(repo.deleteAccount().isFailure)
        assertTrue(repo.state.value.signedIn)
    }

    @Test fun chooseGuestIsRemembered() = runTest {
        repo.chooseGuest()
        assertTrue(repo.state.value.guestChosen)
    }
}
