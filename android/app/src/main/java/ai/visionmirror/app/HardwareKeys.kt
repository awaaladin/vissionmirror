package ai.visionmirror.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the volume keys (seen by the Activity) to the Mirror screen as a manual capture.
 * Keys are only swallowed while [captureEnabled]; everywhere else volume works normally.
 */
@Singleton
class HardwareKeys @Inject constructor() {
    @Volatile var captureEnabled: Boolean = false

    private val _volume = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val volume: SharedFlow<Unit> = _volume.asSharedFlow()

    fun onVolumeKey() { _volume.tryEmit(Unit) }
}
