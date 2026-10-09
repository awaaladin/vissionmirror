package ai.visionmirror.data.auth

import ai.visionmirror.data.api.AppError
import ai.visionmirror.data.api.AuthApi
import ai.visionmirror.data.api.ErrorBody
import ai.visionmirror.data.api.LoginRequest
import ai.visionmirror.data.api.MirrorApi
import ai.visionmirror.data.api.RegisterRequest
import ai.visionmirror.di.ApplicationScope
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** What is remembered about her account between launches. The token stays in this app's private storage. */
data class StoredAccount(
    val token: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    /** She chose "Continue as guest", so the sign-in screen is not shown again. */
    val guestChosen: Boolean = false,
    /** The server rejected her saved token, so she was signed out. Shown once as a note. */
    val expired: Boolean = false,
)

interface AccountStorage {
    val data: Flow<StoredAccount>
    suspend fun signIn(token: String, email: String, displayName: String?)
    suspend fun signOut(expired: Boolean = false)
    suspend fun chooseGuest()
    suspend fun clearExpiredNote()
}

private val Context.accountStore: DataStore<Preferences> by preferencesDataStore(name = "account")

@Singleton
class DataStoreAccountStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) : AccountStorage {
    private object Keys {
        val token = stringPreferencesKey("token")
        val email = stringPreferencesKey("email")
        val name = stringPreferencesKey("display_name")
        val guest = booleanPreferencesKey("guest_chosen")
        val expired = booleanPreferencesKey("expired")
    }

    override val data: Flow<StoredAccount> = context.accountStore.data.map {
        StoredAccount(it[Keys.token], it[Keys.email], it[Keys.name], it[Keys.guest] ?: false, it[Keys.expired] ?: false)
    }

    override suspend fun signIn(token: String, email: String, displayName: String?) {
        context.accountStore.edit {
            it[Keys.token] = token
            it[Keys.email] = email
            if (displayName != null) it[Keys.name] = displayName else it.remove(Keys.name)
            it[Keys.expired] = false
        }
    }

    override suspend fun signOut(expired: Boolean) {
        context.accountStore.edit {
            it.remove(Keys.token)
            it.remove(Keys.email)
            it.remove(Keys.name)
            it[Keys.expired] = expired
        }
    }

    override suspend fun chooseGuest() {
        context.accountStore.edit { it[Keys.guest] = true }
    }

    override suspend fun clearExpiredNote() {
        context.accountStore.edit { it[Keys.expired] = false }
    }
}

data class AccountState(
    /** False until the saved account has been read; the splash screen waits for this. */
    val loaded: Boolean = false,
    val email: String? = null,
    val displayName: String? = null,
    val guestChosen: Boolean = false,
    val expired: Boolean = false,
) {
    val signedIn: Boolean get() = email != null
}

/**
 * Sign up, sign in, sign out and delete account. While signed in, every request carries the account
 * token (see [TokenProvider]); otherwise the app works exactly as a guest, as before.
 */
@Singleton
class AccountRepository @Inject constructor(
    private val auth: AuthApi,
    private val api: MirrorApi,
    private val storage: AccountStorage,
    private val tokens: TokenProvider,
    private val json: Json,
    @ApplicationScope scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = _state

    init {
        tokens.onUserTokenRejected = { scope.launch { storage.signOut(expired = true) } }
        scope.launch {
            storage.data.collect { s ->
                tokens.setUserToken(s.token)
                _state.value = AccountState(true, s.email, s.displayName, s.guestChosen, s.expired)
            }
        }
    }

    suspend fun register(email: String, password: String, displayName: String?): Result<Unit> =
        authenticate { auth.register(RegisterRequest(email.trim(), password, displayName?.trim()?.ifBlank { null })) }

    suspend fun login(email: String, password: String): Result<Unit> =
        authenticate { auth.login(LoginRequest(email.trim(), password)) }

    private suspend fun authenticate(
        call: suspend () -> Response<ai.visionmirror.data.api.AccountAuthResponse>,
    ): Result<Unit> = guarded {
        val response = call()
        val body = response.body()
        if (!response.isSuccessful || body == null) return@guarded Result.failure(mapError(response))
        storage.signIn(body.accessToken, body.user.email, body.user.displayName)
        tokens.setUserToken(body.accessToken)
        Result.success(Unit)
    }

    /** Back to guest. The account itself is untouched and she can sign in again. */
    suspend fun signOut() {
        tokens.setUserToken(null)
        storage.signOut()
    }

    /** Deletes the account on the server first; only then forgets it here. */
    suspend fun deleteAccount(): Result<Unit> = guarded {
        val response = api.deleteAccount()
        if (!response.isSuccessful && response.code() != 401) return@guarded Result.failure(mapError(response))
        tokens.setUserToken(null)
        storage.signOut()
        Result.success(Unit)
    }

    suspend fun chooseGuest() = storage.chooseGuest()

    suspend fun dismissExpiredNote() = storage.clearExpiredNote()

    private suspend fun guarded(block: suspend () -> Result<Unit>): Result<Unit> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: java.net.SocketTimeoutException) {
        Result.failure(AppError.Server("That is taking longer than I expected. Please try again."))
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
            400, 401, 409 -> AppError.Rejected(spoken ?: "That didn't work. Please check what you typed and try again.")
            429 -> {
                val wait = response.headers()["Retry-After"]?.trim()?.toIntOrNull()?.coerceIn(1, 3_600)
                    ?: AppError.DEFAULT_RETRY_SECONDS
                AppError.RateLimited(wait, spoken ?: "Too many tries. Please wait $wait seconds.")
            }
            in 500..599 -> AppError.Server(spoken ?: "My servers are having a moment. Please try again shortly.")
            else -> AppError.Unexpected()
        }
    }
}
