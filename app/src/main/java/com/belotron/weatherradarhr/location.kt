package com.belotron.weatherradarhr

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.app.Activity
import android.app.IntentService
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.Sensor.TYPE_ROTATION_VECTOR
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.text.format.DateUtils
import android.text.format.DateUtils.HOUR_IN_MILLIS
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import android.view.Surface
import androidx.core.content.PermissionChecker.PERMISSION_GRANTED
import androidx.core.content.PermissionChecker.checkSelfPermission
import androidx.fragment.app.Fragment
import com.belotron.weatherradarhr.CcOption.CC_PRIVATE
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
import com.google.android.gms.location.LocationRequest.PRIORITY_LOW_POWER
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationResult.extractResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.text.DateFormat
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.atan2
import kotlin.properties.Delegates.observable
import kotlin.reflect.KProperty

val lradarShape = MapShape(
        topLat = 47.40,
        botLat = 44.71,
        topLeftLon = 12.10,
        botLeftLon = 12.19,
        topRightLon = 17.40,
        botRightLon = 17.30,
        leftScreenX = 10,
        rightScreenX = 810,
        topImageY = 49,
        botImageY = 649
)

val kradarShape = MapShape(
        topLat = 48.06,
        botLat = 43.44,
        topLeftLon = 13.05,
        botLeftLon = 13.15,
        topRightLon = 19.84,
        botRightLon = 19.74,
        leftScreenX = 1,
        rightScreenX = 478,
        topImageY = 1,
        botImageY = 478
)

val locationRequestFg = LocationRequest().apply {
    interval = 1000
    fastestInterval = 10
    priority = PRIORITY_BALANCED_POWER_ACCURACY
}

val locationRequestBg = LocationRequest().apply {
    interval = MINUTE_IN_MILLIS
    fastestInterval = MINUTE_IN_MILLIS
    priority = PRIORITY_LOW_POWER
}

val Float.degrees get() = Math.toDegrees(this.toDouble()).toFloat()
operator fun Location.component1() = latitude
operator fun Location.component2() = longitude
val Location.description get() = "lat: $latitude lon: $longitude accuracy: $accuracy bearing: $bearing"

class MapShape(
        private val topLat: Double,
        private val botLat: Double,
        topLeftLon: Double,
        topRightLon: Double,
        botLeftLon: Double,
        botRightLon: Double,
        leftScreenX: Int,
        rightScreenX: Int,
        private val topImageY: Int,
        botImageY: Int
) {
    // zeroLon satisfies the following:
    // (zeroLon - topLeftLon) / (topRightLon - zeroLon) ==
    // (zeroLon - botLeftLon) / (botRightLon - zeroLon)
    private val zeroLon: Double
    private val xScaleAtTop: Double
    private val xScaleAtBot: Double
    private val zeroImageX: Double
    private val imageHeight: Int = botImageY - topImageY

    init {
        val topLonWidth = topRightLon - topLeftLon
        val botLonWidth = botRightLon - botLeftLon
        zeroLon = (topRightLon * botLeftLon - topLeftLon * botRightLon) / (topLonWidth - botLonWidth)
        val screenWidth: Int = rightScreenX - leftScreenX
        xScaleAtTop = screenWidth / topLonWidth
        zeroImageX = 0.5 + leftScreenX + xScaleAtTop * (zeroLon - topLeftLon)
        xScaleAtBot = screenWidth / botLonWidth
    }

    fun locationToPixel(location: Location, point: FloatArray) {
        val (lat, lon) = location
        locationToPixel(lat, lon, point)
    }

    fun locationToPixel(lat: Double, lon: Double, point: FloatArray) {
        val normY: Double = (topLat - lat) / (topLat - botLat)
        val xScaleAtY: Double = xScaleAtTop + (xScaleAtBot - xScaleAtTop) * normY
        point[0] = (zeroImageX + xScaleAtY * (lon - zeroLon)).toFloat()
        point[1] = topImageY + (imageHeight * normY).toFloat()
    }
}

private var nextRequestCode = 0; get() = ++field
private var contRequestCode = 0
private var continuation: Continuation<Int>? = null

private fun prepareToSuspend(newContinuation: Continuation<Int>): Int {
    contRequestCode = nextRequestCode
    continuation = newContinuation
    return contRequestCode
}

fun resumeReceiveLocationUpdates(requestCode: Int, result: Int) {
    continuation?.takeIf { requestCode == contRequestCode }?.also {
        continuation = null
        contRequestCode = 0
        it.resume(result)
    }
}

class LocationState {
    lateinit var imageBundles: List<ImageBundle>
    var location by observable(null as Location?) { _, _, _ -> imageBundles.forEach { it.invalidateImgView() } }
    var azimuth by observable(0f, ::invalidateIfGotLocation)
    var azimuthAccuracy by observable(0, ::invalidateIfGotLocation)

    private fun <T: Any> invalidateIfGotLocation(prop: KProperty<*>, old: T, new: T) {
        if (location != null) imageBundles.forEach { it.invalidateImgView() }
    }
}

suspend fun Fragment.receiveLocationUpdatesFg(callback: (Location) -> Unit) {
    val locationClient = LocationServices.getFusedLocationProviderClient(context!!)
    locationClient.tryFetchLastLocation()?.also { callback(it) }
    locationClient.requestLocationUpdates(locationRequestFg,
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) = callback(result.lastLocation)
            },
            null)
            .await()
}

suspend fun Context.refreshLocation() {
    val timestamp = sharedPrefs.location.third
    if (System.currentTimeMillis() - timestamp > 20 * MINUTE_IN_MILLIS) {
        LocationServices.getFusedLocationProviderClient(this).tryFetchLastLocation()?.also {
            sharedPrefs.applyUpdate { setLocation(it) }
        }
    }
}

val Context.storedLocaton: Triple<Double, Double, Long> get() {
    val now = System.currentTimeMillis()
    val locTriple = sharedPrefs.location
    val timestamp = locTriple.third
    return if (now - timestamp < HOUR_IN_MILLIS)
        locTriple
    else run {
        val df = DateFormat.getDateTimeInstance()
        val storedTime = df.format(timestamp)
        val currTime = df.format(now)
        warn(CC_PRIVATE) { "Stored location is too old: $storedTime. Current time: $currTime" }
        Triple(0.0, 0.0, 0L)
    }
}

private suspend fun FusedLocationProviderClient.tryFetchLastLocation(): Location? {
    return suspendCancellableCoroutine { continuation ->
        lastLocation
                .addOnSuccessListener {
                    it?.also { info { "Got response from getLastLocation()" } }
                            ?: warn { "getLastLocation() returned null" }
                    continuation.resume(it)
                }
                .addOnCanceledListener { continuation.resumeWithException(CancellationException()) }
                .addOnFailureListener { continuation.resumeWithException(it) }
    }
}

suspend fun Fragment.ensureLocationPermissionsAndSettings() =
        ensureLocationPermission() && ensureLocationSettings(locationRequestFg, locationRequestBg)

private suspend fun Fragment.ensureLocationPermission(): Boolean {
    if (!context!!.appHasLocationPermission()) {
        warn { "fg: our app has no permission to access fine location" }
        requestFineLocationPermission().also { grantResult ->
            if (grantResult != PERMISSION_GRANTED) {
                warn { "Result of requestPermissions: $grantResult" }
                return false
            }
            info { "User has granted us the fine location permission" }
        }
    }
    return true
}

private suspend fun Fragment.ensureLocationSettings(vararg locationRequests: LocationRequest): Boolean {
    context!!.locationSettingsException(*locationRequests)?.also { resolvableApiException ->
        warn { "ResolvableApiException for location request" }
        resolve(resolvableApiException).also { result ->
            if (result != Activity.RESULT_OK) {
                warn { "ResolvableApiException resolution failed with code $result" }
                return false
            }
            info { "ResolvableApiException is now resolved" }
        }
    }
    return true
}

private suspend fun Context.locationSettingsException(
        vararg locationRequests: LocationRequest
): ResolvableApiException? = try {
    LocationServices.getSettingsClient(this)
            .checkLocationSettings(LocationSettingsRequest.Builder()
                    .addAllLocationRequests(locationRequests.asList()).build())
            .await()
    null
} catch (e: ResolvableApiException) { e }

private fun Context.appHasLocationPermission() =
        checkSelfPermission(this, ACCESS_FINE_LOCATION) == PERMISSION_GRANTED

private suspend fun Fragment.requestFineLocationPermission(): Int = suspendCancellableCoroutine {
    requestPermissions(arrayOf(ACCESS_FINE_LOCATION), prepareToSuspend(it))
}

private suspend fun Fragment.resolve(e: ResolvableApiException): Int = suspendCancellableCoroutine {
    startIntentSenderForResult(e.resolution.intentSender, prepareToSuspend(it), null, 0, 0, 0, null)
}

suspend fun Context.receiveLocationUpdatesBg() {
    if (!appHasLocationPermission() || locationSettingsException(locationRequestBg) != null) {
        warn { "bg: insufficient permissions or settings to receive location" }
        return
    }
    val context = this
    with(LocationServices.getFusedLocationProviderClient(context)) {
        tryFetchLastLocation()?.also {
            info { "bg: lastLocation = $it" }
            appContext.sharedPrefs.commitUpdate { setLocation(it) }
        }
        requestLocationUpdates(locationRequestBg,
                PendingIntent.getService(context, 0, Intent(context, ReceiveLocationService::class.java), 0))
                .await()
    }
    info(CC_PRIVATE) { "Started receiving location in the background" }
}

class ReceiveLocationService : IntentService("Receive Location Updates") {
    override fun onHandleIntent(intent: Intent) {
        val location = extractResult(intent)?.lastLocation ?: return
        info(CC_PRIVATE) { "Received location in the background: $location" }
        appContext.sharedPrefs.commitUpdate { setLocation(location) }
    }
}

fun Fragment.receiveAzimuthUpdates(
        azimuthChanged: (Float, Int) -> Unit,
        accuracyChanged: (Int) -> Unit
) {
    val sensorManager = activity!!.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val sensor = sensorManager.getDefaultSensor(TYPE_ROTATION_VECTOR)!!
    sensorManager.registerListener(OrientationListener(activity!!, azimuthChanged, accuracyChanged), sensor, 10_000)
}

private class OrientationListener(
        private val activity: Activity,
        private val azimuthChanged: (Float, Int) -> Unit,
        private val accuracyChanged: (Int) -> Unit
) : SensorEventListener {
    private val rotationMatrix = FloatArray(9)

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        // Each column of the rotation matrix has the components of the unit vector of
        // the corresponding axis of the device, described from the perspective of
        // Earth's coordinate system (y points to North):
        //
        // - column 0 describes the device's x-axis, pointing right
        // - column 1 describes the device's y-axis, pointing up
        //
        // When the device's screen is in the natural orientation (e.g., portrait
        // for phones), we use the x-axis to determine azimuth. It is the best
        // choice because it is usable when the device is both horizontal and
        // upright (it doesn't move with changing pitch). When the screen is
        // oriented sideways, we use the y-axis because in that orientation, it is
        // the one that doesn't get disturbed by pitch.
        //
        // The `sense` variable tells whether to use the chosen axis as-is or
        // with the opposite sense (e.g., x points left when opposite).
        val (matrixColumn, sense) = when (val rotation = activity.windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_0 -> Pair(0, 1)
            Surface.ROTATION_90 -> Pair(1, -1)
            Surface.ROTATION_180 -> Pair(0, -1)
            Surface.ROTATION_270 -> Pair(1, 1)
            else -> error("Invalid screen rotation value: $rotation")
        }
        val x = sense * rotationMatrix[matrixColumn]
        val y = sense * rotationMatrix[matrixColumn + 3]
        azimuthChanged(-atan2(y, x), event.accuracy)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == TYPE_ROTATION_VECTOR) {
            accuracyChanged(accuracy)
        }
    }
}
