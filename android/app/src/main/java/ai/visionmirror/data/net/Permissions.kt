package ai.visionmirror.data.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface PermissionChecker {
    fun hasCamera(): Boolean
    fun hasMic(): Boolean
}

@Singleton
class SystemPermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) : PermissionChecker {
    private fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    override fun hasCamera() = has(Manifest.permission.CAMERA)
    override fun hasMic() = has(Manifest.permission.RECORD_AUDIO)
}
