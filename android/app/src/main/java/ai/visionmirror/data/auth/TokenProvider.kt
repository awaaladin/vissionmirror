package ai.visionmirror.data.auth

import ai.visionmirror.data.api.AnonAuthRequest
import ai.visionmirror.data.api.AuthApi
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Where the anonymous device id lives. Generated once, never leaves the device except to /auth/anon. */
fun interface DeviceIdStore {
    fun deviceId(): String
}

@Singleton
class PrefsDeviceIdStore @Inject constructor(@ApplicationContext context: Context) : DeviceIdStore {
    private val prefs = context.getSharedPreferences("vm_device", Context.MODE_PRIVATE)

    @Synchronized
    override fun deviceId(): String =
        prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY, it).apply() }

    private companion object { const val KEY = "device_id" }
}

/**
 * Token flow: `POST /v1/auth/anon` once, cache the bearer token in memory, refresh transparently
 * shortly before expiry or when the server answers 401. Tokens are never written to disk.
 */
@Singleton
class TokenProvider @Inject constructor(
    private val authApi: AuthApi,
    private val deviceIds: DeviceIdStore,
    private val clock: Clock,
) {
    fun interface Clock { fun nowMs(): Long }

    private val mutex = Mutex()
    @Volatile private var token: String? = null
    @Volatile private var expiresAtMs: Long = 0

    /** [stale] is the token the caller just saw rejected; if we already hold a newer one, use that. */
    suspend fun token(stale: String? = null): String = mutex.withLock {
        val current = token
        val fresh = current != null && clock.nowMs() < expiresAtMs - REFRESH_MARGIN_MS
        if (fresh && current != stale) return current!!
        val response = authApi.anon(AnonAuthRequest(deviceIds.deviceId()))
        val body = response.body()
        if (!response.isSuccessful || body == null) throw java.io.IOException("auth failed: HTTP ${response.code()}")
        token = body.accessToken
        expiresAtMs = clock.nowMs() + body.expiresIn * 1000
        body.accessToken
    }

    fun cached(): String? = token

    private companion object { const val REFRESH_MARGIN_MS = 60_000L }
}
