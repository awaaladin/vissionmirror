package ai.visionmirror.di

import ai.visionmirror.BuildConfig
import ai.visionmirror.data.api.AuthApi
import ai.visionmirror.data.api.MirrorApi
import ai.visionmirror.data.auth.BearerInterceptor
import ai.visionmirror.data.auth.DeviceIdStore
import ai.visionmirror.data.auth.PrefsDeviceIdStore
import ai.visionmirror.data.auth.RefreshAuthenticator
import ai.visionmirror.data.auth.TokenProvider
import ai.visionmirror.data.net.Connectivity
import ai.visionmirror.data.net.ConnectivityMonitor
import ai.visionmirror.data.net.PermissionChecker
import ai.visionmirror.data.net.SystemPermissionChecker
import ai.visionmirror.data.repo.MirrorRepository
import ai.visionmirror.data.settings.DataStoreSettings
import ai.visionmirror.data.settings.SettingsStore
import ai.visionmirror.data.repo.MirrorRepositoryImpl
import ai.visionmirror.audio.EarconPlayer
import ai.visionmirror.audio.Earcons
import ai.visionmirror.audio.SpeechManager
import ai.visionmirror.audio.Speaker
import ai.visionmirror.audio.SystemVoiceInput
import ai.visionmirror.audio.VoiceInput
import ai.visionmirror.haptics.Haptics
import ai.visionmirror.haptics.HapticsManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class PlainClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    /** Client without auth, used only for /auth/anon so a failing token call can never recurse. */
    @Provides @Singleton @PlainClient
    fun plainClient(): OkHttpClient = baseClient().build()

    @Provides @Singleton
    fun authApi(@PlainClient client: OkHttpClient, json: Json): AuthApi = retrofit(client, json).create(AuthApi::class.java)

    @Provides @Singleton
    fun tokenClock(): TokenProvider.Clock = TokenProvider.Clock { System.currentTimeMillis() }

    @Provides @Singleton
    fun authedClient(@PlainClient plain: OkHttpClient, tokens: TokenProvider): OkHttpClient =
        plain.newBuilder()
            .addInterceptor(BearerInterceptor(tokens))
            .authenticator(RefreshAuthenticator(tokens))
            // The backend's describe takes ~10-15 s normally and longer if the AI provider retries; the user
            // hears reassurance meanwhile.
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

    @Provides @Singleton
    fun mirrorApi(client: OkHttpClient, json: Json): MirrorApi = retrofit(client, json).create(MirrorApi::class.java)

    @Provides @Singleton @ApplicationScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun baseClient() = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)

    // Base URL comes from BuildConfig (local.properties), never hard-coded.
    private fun retrofit(client: OkHttpClient, json: Json) = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun repository(impl: MirrorRepositoryImpl): MirrorRepository
    @Binds abstract fun deviceIds(impl: PrefsDeviceIdStore): DeviceIdStore
    @Binds abstract fun speaker(impl: SpeechManager): Speaker
    @Binds abstract fun haptics(impl: HapticsManager): Haptics
    @Binds abstract fun earcons(impl: EarconPlayer): Earcons
    @Binds abstract fun settings(impl: DataStoreSettings): SettingsStore
    @Binds abstract fun connectivity(impl: ConnectivityMonitor): Connectivity
    @Binds abstract fun permissions(impl: SystemPermissionChecker): PermissionChecker
    @Binds abstract fun voiceInput(impl: SystemVoiceInput): VoiceInput
}
