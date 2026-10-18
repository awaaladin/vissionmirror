package ai.visionmirror.assistant

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android only lets the open screen ask for permissions. When a voice request needs one (location to say where she is,
 * SMS to send it, contacts to find a name), the assistant tells her out loud and posts the request here; the activity
 * shows Android's permission box.
 */
@Singleton
class PermissionRequests @Inject constructor() {
    private val _flow = MutableSharedFlow<List<String>>(extraBufferCapacity = 2)
    val flow: SharedFlow<List<String>> = _flow.asSharedFlow()

    fun request(permissions: List<String>) { _flow.tryEmit(permissions) }
}
