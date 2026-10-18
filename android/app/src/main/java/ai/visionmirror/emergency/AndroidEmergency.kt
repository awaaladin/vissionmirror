package ai.visionmirror.emergency

import ai.visionmirror.data.settings.EmergencyContact
import android.annotation.SuppressLint
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.ContactsContract
import android.telephony.SmsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** The phone's GPS: a fresh fix if there is one within a few seconds, otherwise the last position it knew. */
@Singleton
class AndroidLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationSource {

    @SuppressLint("MissingPermission")
    override suspend fun current(): GeoPoint? = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val fresh = if (Build.VERSION.SDK_INT >= 30) {
            enabled.firstNotNullOfOrNull { provider -> withTimeoutOrNull(FIX_TIMEOUT_MS) { fix(lm, provider) } }
        } else {
            null
        }
        val best = fresh ?: listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        best?.let { GeoPoint(it.latitude, it.longitude) }
    } catch (e: SecurityException) {
        null
    }

    @RequiresApi(30)
    @SuppressLint("MissingPermission")
    private suspend fun fix(lm: LocationManager, provider: String): Location? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { cont.resume(it) }
        }
    }

    private companion object { const val FIX_TIMEOUT_MS = 7_000L }
}

@Singleton
class AndroidAddressLookup @Inject constructor(
    @ApplicationContext private val context: Context,
) : AddressLookup {
    override suspend fun describe(point: GeoPoint): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val address = if (Build.VERSION.SDK_INT >= 33) {
            withTimeoutOrNull(6_000) {
                suspendCancellableCoroutine<Address?> { cont ->
                    geocoder.getFromLocation(point.lat, point.lon, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) { cont.resume(addresses.firstOrNull()) }
                        override fun onError(errorMessage: String?) { cont.resume(null) }
                    })
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocation(point.lat, point.lon, 1)?.firstOrNull() }.getOrNull()
            }
        }
        return address?.let { a ->
            listOfNotNull(a.thoroughfare ?: a.featureName, a.subLocality, a.locality).distinct().joinToString(", ")
                .ifBlank { a.getAddressLine(0) }
        }?.takeIf { it.isNotBlank() }
    }
}

@Singleton
class AndroidSmsSender @Inject constructor(
    @ApplicationContext private val context: Context,
) : SmsSender {
    @SuppressLint("MissingPermission")
    override fun send(number: String, text: String): Boolean = runCatching {
        val sms = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
        true
    }.getOrDefault(false)
}

@Singleton
class AndroidContactBook @Inject constructor(
    @ApplicationContext private val context: Context,
) : ContactBook {
    override suspend fun find(name: String): List<EmergencyContact> = withContext(Dispatchers.IO) {
        runCatching {
            val found = LinkedHashMap<String, EmergencyContact>()
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { c ->
                while (c.moveToNext() && found.size < 5) {
                    val person = c.getString(0).orEmpty()
                    val number = c.getString(1).orEmpty().filter { it.isDigit() || it == '+' }
                    if (number.isNotBlank()) found.putIfAbsent(number, EmergencyContact(person, number))
                }
            }
            found.values.toList()
        }.getOrDefault(emptyList())
    }
}

@Singleton
class AndroidPhoneActions @Inject constructor(
    @ApplicationContext private val context: Context,
) : PhoneActions {
    override fun dial(number: String) {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
