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
import android.os.Build
import android.text.format.DateUtils.HOUR_IN_MILLIS
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import android.text.format.DateUtils.SECOND_IN_MILLIS
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
import com.google.android.gms.location.LocationServices.getFusedLocationProviderClient
import com.google.android.gms.location.LocationServices.getSettingsClient
import com.google.android.gms.location.LocationSettingsRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.atan2
import kotlin.properties.Delegates.observable
import kotlin.reflect.KProperty

const val CODE_REQUEST_FINE_LOCATION = 13
const val CODE_RESOLVE_API_EXCEPTION = 14
const val METERS_PER_DEGREE = 111_111
private const val WAIT_MILLISECONDS_BEFORE_ASKING = 2 * SECOND_IN_MILLIS
private const val CHECK_LOCATION_ENABLED_PERIOD_MILLIS = 1 * SECOND_IN_MILLIS

val lradarShape = MapShape(
        topLat = 47.40,
        botLat = 44.71,
        topLeftLon = 12.10,
        botLeftLon = 12.19,
        topRightLon = 17.40,
        botRightLon = 17.30,
        leftImageX = 10,
        rightImageX = 810,
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
        leftImageX = 1,
        rightImageX = 478,
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
val Float.radians get() = Math.toRadians(this.toDouble()).toFloat()
operator fun Location.component1() = latitude
operator fun Location.component2() = longitude
val Location.bearingAccuracyGuarded get() = if (Build.VERSION.SDK_INT >= 26) bearingAccuracyDegrees else 0f
val Location.description get() =
    "lat: %.3f lon: %.3f acc: %.3f; brg: %.1f".format(latitude, longitude, accuracy, bearing) +
            (if (Build.VERSION.SDK_INT >= 26) " acc: %.1f".format(bearingAccuracyDegrees) else "")

class MapShape(
        private val topLat: Double,
        private val botLat: Double,
        topLeftLon: Double,
        topRightLon: Double,
        botLeftLon: Double,
        botRightLon: Double,
        leftImageX: Int,
        rightImageX: Int,
        private val topImageY: Int,
        botImageY: Int
) {
    val pixelSizeMeters: Float

    // zeroLon satisfies the following:
    // (zeroLon - topLeftLon) / (topRightLon - zeroLon) ==
    // (zeroLon - botLeftLon) / (botRightLon - zeroLon)
    private val zeroLon: Double
    private val xScaleAtTop: Double
    private val xScaleAtBot: Double
    private val zeroImageX: Double
    private val imageHeightPixels: Int = botImageY - topImageY

    init {
        val topLonWidth = topRightLon - topLeftLon
        val botLonWidth = botRightLon - botLeftLon
        zeroLon = (topRightLon * botLeftLon - topLeftLon * botRightLon) / (topLonWidth - botLonWidth)
        val screenWidth: Int = rightImageX - leftImageX
        xScaleAtTop = screenWidth / topLonWidth
        zeroImageX = 0.5 + leftImageX + xScaleAtTop * (zeroLon - topLeftLon)
        xScaleAtBot = screenWidth / botLonWidth
        val imageHeightDegrees: Double = topLat - botLat
        pixelSizeMeters = (imageHeightDegrees * METERS_PER_DEGREE / imageHeightPixels).toFloat()
        println("Pixel size: $pixelSizeMeters meters")
    }

    fun locationToPixel(location: Location, point: FloatArray) {
        val (lat, lon) = location
        locationToPixel(lat, lon, point)
    }

    fun locationToPixel(lat: Double, lon: Double, point: FloatArray) {
        val normY: Double = (topLat - lat) / (topLat - botLat)
        val xScaleAtY: Double = xScaleAtTop + (xScaleAtBot - xScaleAtTop) * normY
        point[0] = (zeroImageX + xScaleAtY * (lon - zeroLon)).toFloat()
        point[1] = topImageY + (imageHeightPixels * normY).toFloat()
    }
}

class LocationState {
    lateinit var imageBundles: List<ImageBundle>
    var location by observable(null as Location?) { _, _, _ -> imageBundles.forEach { it.invalidateImgView() } }
    var azimuth by observable(0f, ::invalidateIfGotLocation)
    var azimuthAccuracy by observable(0, ::invalidateIfGotLocation)

    private var azimuthListener : SensorEventListener? = null

    private fun <T: Any> invalidateIfGotLocation(prop: KProperty<*>, old: T, new: T) {
        if (location != null) imageBundles.forEach { it.invalidateImgView() }
    }

    fun setAzimuthListener(listener: SensorEventListener) {
        azimuthListener = listener
    }

    fun clearAzimuthListener(sensorManager: SensorManager) {
        azimuthListener?.also {
            sensorManager.unregisterListener(it)
            azimuthListener = null
        }
    }

    suspend fun trackLocationEnablement(activity: Activity) {
        val onLocationDisabled: suspend () -> Unit = {
            info { "Location not available" }
            activity.deleteLocation()
            location = null
            redrawWidgetsInForeground()
        }
        val onLocationEnabled: suspend () -> Unit = {
            info { "Location available" }
            val locationState = this
            with(activity) {
                refreshLocation()
                receiveLocationUpdatesFg(locationState)
                receiveAzimuthUpdates(locationState)
                receiveLocationUpdatesBg()
            }
            redrawWidgetsInForeground()
        }
        val transitionActions = mapOf(false to onLocationDisabled, true to onLocationEnabled)
        var prevLocationState: Boolean? = null
        while (true) {
            val newLocationState = activity.canUseLocationFg()
            if (newLocationState != prevLocationState) {
                transitionActions[newLocationState]!!()
                prevLocationState = newLocationState
            }
            delay(CHECK_LOCATION_ENABLED_PERIOD_MILLIS)
        }
    }
}

object LocationCallbackFg : LocationCallback() {
    var locationState: LocationState? = null

    override fun onLocationResult(result: LocationResult) {
        val lastLocation = result.lastLocation
        info { "FG: received location ${lastLocation.description}" }
        locationState?.apply { location = lastLocation }
                ?: warn { "LocationCallbackFg received an event while not in use" }
    }
}

suspend fun Context.canUseLocationBg() =
        appHasLocationPermission() && locationSettingsException(locationRequestBg) == null

suspend fun Context.canUseLocationFg() =
        appHasLocationPermission() && locationSettingsException(locationRequestFg, locationRequestBg) == null

suspend fun Fragment.checkAndCorrectPermissionsAndSettings() {
    with(context!!) {
        if (!appHasLocationPermission()) {
            warn { "FG: our app has no permission to access fine location" }
            delay(WAIT_MILLISECONDS_BEFORE_ASKING)
            if (!appHasLocationPermission()) {
                startIntentRequestLocationPermission()
                return
            }
        }
        if (locationSettingsException(locationRequestFg, locationRequestBg) == null) return
        warn { "FG: ResolvableApiException for location request (probably location disabled)" }
        if (!mainPrefs.shouldAskToEnableLocation) return
        delay(WAIT_MILLISECONDS_BEFORE_ASKING)
        locationSettingsException(locationRequestFg, locationRequestBg)?.also {
            startIntentResolveException(it)
        }
    }
}

suspend fun Context.receiveLocationUpdatesFg(locationState: LocationState) {
    if (!canUseLocationFg()) {
        warn { "FG: insufficient permissions or settings to receive location" }
        return
    }
    fusedLocationProviderClient.apply {
        tryFetchLastLocation()?.also {
            info { "lastLocation: ${it.description}" }
            locationState.location = it
        }
        LocationCallbackFg.locationState = locationState
        requestLocationUpdates(locationRequestFg, LocationCallbackFg, null).await()
        info { "FG: started receiving location updates" }
    }
}

suspend fun Context.stopReceivingLocationUpdatesFg() {
    if (LocationCallbackFg.locationState == null) return
    fusedLocationProviderClient.removeLocationUpdates(LocationCallbackFg).apply {
        LocationCallbackFg.locationState = null
        await()
    }
}

suspend fun Context.receiveLocationUpdatesBg() {
    if (!canUseLocationBg()) {
        warn { "BG: insufficient permissions or settings to receive location" }
        return
    }
    val context = this
    with(fusedLocationProviderClient) {
        tryFetchLastLocation()?.also {
            info { "BG: lastLocation = ${it.description}" }
            appContext.storeLocation(it)
        }
        requestLocationUpdates(locationRequestBg,
                PendingIntent.getService(context, 0, Intent(context, ReceiveLocationService::class.java), 0))
                .await()
    }
    info(CC_PRIVATE) { "BG: started receiving location updates" }
}

fun Activity.receiveAzimuthUpdates(locationState: LocationState) {
    val sensorManager = sensorManager ?: run {
        warn(CC_PRIVATE) { "SensorManager not available" }
        return
    }
    locationState.clearAzimuthListener(sensorManager)
    val sensor = sensorManager.getDefaultSensor(TYPE_ROTATION_VECTOR) ?: run {
        warn(CC_PRIVATE) { "Rotation vector sensor not available" }
        return
    }
    val newListener = OrientationListener(this, locationState)
    sensorManager.registerListener(newListener, sensor, 10_000)
    locationState.setAzimuthListener(newListener)
    info { "FG: receiving azimuth updates" }
}

fun Context.stopReceivingAzimuthUpdates(locationState: LocationState) {
    sensorManager?.also { locationState.clearAzimuthListener(it) }
}

suspend fun Context.refreshLocation() {
    val timestamp = storedLocation.third
    val age = System.currentTimeMillis() - timestamp
    if (age <= 5 * MINUTE_IN_MILLIS) return
    val ageString = if (timestamp != 0L) "stale (${MILLISECONDS.toMinutes(age)} minutes old)" else "absent"
    if (!canUseLocationBg()) {
        info(CC_PRIVATE) { "Location is $ageString, can't refresh it due to lack of permissions/location settings" }
        deleteLocation()
        return
    }
    info(CC_PRIVATE) { "Refreshing location because it's $ageString" }
    getFusedLocationProviderClient(this).tryFetchLastLocation()?.also { storeLocation(it) }
}

val Context.locationIfFresh: Triple<Double, Double, Long>? get() {
    val now = System.currentTimeMillis()
    val locTriple = storedLocation
    val timestamp = locTriple.third
    if (timestamp == 0L) {
        warn(CC_PRIVATE) { "Stored location not present" }
        return null
    }
    val age = now - timestamp
    return if (age < HOUR_IN_MILLIS)
        locTriple
    else run {
        warn(CC_PRIVATE) { "Stored location is too old, age ${MILLISECONDS.toMinutes(age)} minutes" }
        null
    }
}

fun Context.appHasLocationPermission() =
        checkSelfPermission(this, ACCESS_FINE_LOCATION) == PERMISSION_GRANTED

suspend fun Context.locationSettingsException(
        vararg locationRequests: LocationRequest
): ResolvableApiException? = try {
    getSettingsClient(this)
            .checkLocationSettings(LocationSettingsRequest.Builder()
                    .addAllLocationRequests(locationRequests.asList()).build())
            .await()
    null
} catch (e: ResolvableApiException) { e }

fun Fragment.startIntentRequestLocationPermission() =
        requestPermissions(arrayOf(ACCESS_FINE_LOCATION), CODE_REQUEST_FINE_LOCATION)

fun Fragment.startIntentResolveException(e: ResolvableApiException) =
        startIntentSenderForResult(e.resolution.intentSender, CODE_RESOLVE_API_EXCEPTION, null, 0, 0, 0, null)

private val Context.sensorManager get() = getSystemService(Context.SENSOR_SERVICE) as SensorManager?
private val Context.fusedLocationProviderClient get() = getFusedLocationProviderClient(this)

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


class ReceiveLocationService : IntentService("Receive Location Updates") {
    override fun onHandleIntent(intent: Intent) {
        val location = extractResult(intent)?.lastLocation ?: return
        info(CC_PRIVATE) { "Received location in the background: ${location.description}" }
        application?.storeLocation(location)
    }
}

private class OrientationListener(
        private val activity: Activity,
        private val locationState: LocationState
) : SensorEventListener {
    private val rotationMatrix = FloatArray(9)

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // In the rotation matrix, each column has the components of the unit
        // vector of the device's corresponding axis, described from the
        // perspective of Earth's coordinate system (y points to North):
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
        val easting = sense * rotationMatrix[matrixColumn]
        val northing = sense * rotationMatrix[matrixColumn + 3]
        with(locationState) {
            azimuth = -atan2(northing, easting)
            azimuthAccuracy = event.accuracy
            debug { "Azimuth changed to ${azimuth}, accuracy ${azimuthAccuracy}" }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == TYPE_ROTATION_VECTOR) {
            info { "Azimuth accuracy changed to $accuracy" }
            locationState.azimuthAccuracy = accuracy
        }
    }
}
