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
        if (!Geocoder.isPresent()) return lookUpOnline(point)
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
        // Never a "plus code" (XPXF+9Q Uyo): street, area, town and state, in words.
        val fromPhone = address?.let { a ->
            HumanPlace.compose(
                a.thoroughfare ?: a.featureName?.takeIf { !HumanPlace.isCodeOrNumber(it) },
                a.subLocality,
                a.locality ?: a.subAdminArea,
                a.adminArea,
            ) ?: HumanPlace.clean(a.getAddressLine(0))
        }
        // Many phones have no address service, or know only the state: ask OpenStreetMap, which names roads and neighbourhoods.
        if (fromPhone == null || fromPhone.count { it == ',' } < 1) {
            lookUpOnline(point)?.let { return it }
        }
        return fromPhone
    }

    /** OpenStreetMap's public reverse lookup (no key). Only runs when she asks where she is. */
    private suspend fun lookUpOnline(point: GeoPoint): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = java.net.URL("https://nominatim.openstreetmap.org/reverse?format=jsonv2&addressdetails=1&zoom=18&accept-language=en&lat=${point.lat}&lon=${point.lon}")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 6_000
                setRequestProperty("User-Agent", "VisionMirror/0.9 (voice assistant for blind and low-vision people)")
            }
            try {
                if (conn.responseCode != 200) return@runCatching null
                val a = org.json.JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONObject("address") ?: return@runCatching null
                fun pick(vararg keys: String) = keys.firstNotNullOfOrNull { k -> a.optString(k).takeIf { it.isNotBlank() } }
                HumanPlace.compose(
                    pick("road", "pedestrian", "footway"),
                    pick("suburb", "neighbourhood", "quarter", "hamlet"),
                    pick("city", "town", "village", "municipality", "county"),
                    pick("state"),
                )
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}

@Singleton
class AndroidSmsSender @Inject constructor(
    @ApplicationContext private val context: Context,
) : SmsSender {
    override val needsTap: Boolean get() = !ai.visionmirror.BuildConfig.DIRECT_SMS

    @SuppressLint("MissingPermission")
    override fun send(number: String, text: String): Boolean = runCatching {
        if (!ai.visionmirror.BuildConfig.DIRECT_SMS) {
            // Play build: the messaging app opens with the text ready; she (or her helper) taps send.
            context.startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).putExtra("sms_body", text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return@runCatching true
        }
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
