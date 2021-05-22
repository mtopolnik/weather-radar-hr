package com.belotron.weatherradarhr

import android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.annotation.SuppressLint
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
import android.os.Looper
import android.text.format.DateUtils.*
import android.view.Surface
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.PermissionChecker.PERMISSION_GRANTED
import androidx.core.content.PermissionChecker.checkSelfPermission
import com.belotron.weatherradarhr.CcOption.CC_PRIVATE
import com.belotron.weatherradarhr.UserReaction.PROCEED
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.location.LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
import com.google.android.gms.location.LocationRequest.PRIORITY_LOW_POWER
import com.google.android.gms.location.LocationResult.extractResult
import com.google.android.gms.location.LocationServices.getFusedLocationProviderClient
import com.google.android.gms.location.LocationServices.getSettingsClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.math.atan2
import kotlin.properties.Delegates.observable
import kotlin.reflect.KProperty

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

val locationRequestFg: LocationRequest = LocationRequest.create().apply {
    interval = 1000
    fastestInterval = 10
    priority = PRIORITY_BALANCED_POWER_ACCURACY
}

val locationRequestBg: LocationRequest = LocationRequest.create().apply {
    interval = MINUTE_IN_MILLIS
    fastestInterval = MINUTE_IN_MILLIS
    priority = PRIORITY_LOW_POWER
}

val Float.toDegrees get() = Math.toDegrees(this.toDouble()).toFloat()
operator fun Location.component1() = latitude
operator fun Location.component2() = longitude
val Location.description
    get() = "lat: %.3f lon: %.3f acc: %.3f; brg: %.1f".format(latitude, longitude, accuracy, bearing) +
            (if (Build.VERSION.SDK_INT >= 26) " acc: %.1f".format(bearingAccuracyDegrees) else "")

/**
 * Transforms a lat-lon location to its corresponding pixel on the map's
 * bitmap.
 *
 * It uses a simple-minded lat-lon coordinate system as follows:
 * parallels are straight horizontal lines and meridians are straight lines
 * that meet in a common intersection point (outside the bitmap).
 *
 * The map's bitmap is a rectangle overlaid on this coordinate grid, such
 * that its top and bottom sides are segments of two parallels.
 */
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

    // zeroLon is the longitude of the "meridian" that is vertical.
    // It satisfies the following:
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

    private var azimuthListener: SensorEventListener? = null

    @Suppress("UNUSED_PARAMETER")
    private fun <T : Any> invalidateIfGotLocation(prop: KProperty<*>, old: T, new: T) {
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
        var prevLocationState: Boolean? = null
        while (true) {
            try {
                val canAccessLocation = activity.canAccessLocation(fromBg = false)
                if (canAccessLocation == prevLocationState) {
                    delay(CHECK_LOCATION_ENABLED_PERIOD_MILLIS)
                    continue
                }
                prevLocationState = canAccessLocation
                if (canAccessLocation) {
                    info { "Allowed to receive location in the foreground" }
                    val locationState = this
                    with(activity) {
                        refreshLocation(callingFromBg = false)
                        receiveLocationUpdatesFg(locationState)
                        receiveAzimuthUpdates(locationState)
                        if (anyWidgetInUse()) {
                            receiveLocationUpdatesBg()
                        }
                    }
                } else {
                    info { "Location not available" }
                    activity.deleteLocation()
                    location = null
                }
                redrawWidgetsInForeground()
                delay(CHECK_LOCATION_ENABLED_PERIOD_MILLIS)
            }
            catch (e: CancellationException) {
                throw e
            }
            catch (e: Exception) {
                severe(e) { "Exception in trackLocationEnablement" }
            }
        }
    }
}

object LocationCallbackFg : LocationCallback() {
    var locationState: LocationState? = null

    override fun onLocationResult(result: LocationResult) {
        val lastLocation = result.lastLocation
        info { "FG: received location ${lastLocation.description}" }
        locationState
            ?.apply { location = lastLocation }
            ?: warn { "LocationCallbackFg received an event while not in use" }
    }
}

suspend fun Context.canAccessLocation(fromBg: Boolean) =
    if (fromBg)
        appHasBgLocationPermission() && locationSettingsException(locationRequestBg) == null
    else
        appHasFgLocationPermission() && locationSettingsException(locationRequestFg) == null

suspend fun RadarImageFragment.checkAndCorrectPermissionsAndSettings() {
    try {
        with(context!!) {
            if (!appHasFgLocationPermission()) {
                warn { "FG: our app has no permission to access location" }
                delay(WAIT_MILLISECONDS_BEFORE_ASKING)
                if (!appHasFgLocationPermission()) {
                    if (mainPrefs.shouldShowFgLocationNotice) {
                        val userReaction = requireActivity().showFgLocationNotice()
                        mainPrefs.applyUpdate { setShouldShowFgLocationNotice(false) }
                        if (userReaction == PROCEED) {
                            startIntentRequestLocationPermissionFg()
                            return
                        }
                    } else {
                        startIntentRequestLocationPermissionFg()
                        return
                    }
                }
            } else if (anyWidgetInUse() && !appHasBgLocationPermission()) {
                warn { "BG: our app has no permission to access coarse location" }
                delay(WAIT_MILLISECONDS_BEFORE_ASKING)
                if (anyWidgetInUse() && !appHasBgLocationPermission()) {
                    if (mainPrefs.shouldShowBgLocationNotice) {
                        val userReaction = requireActivity().showBgLocationNotice()
                        mainPrefs.applyUpdate { setShouldShowBgLocationNotice(false) }
                        if (userReaction == PROCEED) {
                            startIntentRequestLocationPermissionBg()
                            return
                        }
                    } else {
                        startIntentRequestLocationPermissionBg()
                        return
                    }
                }
            }
            if (locationSettingsException(locationRequestFg, locationRequestBg) == null) return
            warn { "FG: ResolvableApiException for location request (probably location disabled)" }
            if (!mainPrefs.shouldAskToEnableLocation) return
            delay(WAIT_MILLISECONDS_BEFORE_ASKING)
            locationSettingsException(locationRequestFg, locationRequestBg)
                ?.let { it as? ResolvableApiException }
                ?.also { startIntentResolveException(it) }
        }
    }
    catch (e: CancellationException) {
        throw e
    }
    catch (e: Exception) {
        severe(e) { "Failed to check and correct permission settings" }
    }
}

@SuppressLint("MissingPermission")
suspend fun Context.receiveLocationUpdatesFg(locationState: LocationState) = ignoringException {
    fusedLocationProviderClient.apply {
        tryFetchLastLocation()?.also {
            info { "lastLocation: ${it.description}" }
            locationState.location = it
        }
        LocationCallbackFg.locationState = locationState
        requestLocationUpdates(locationRequestFg, LocationCallbackFg, Looper.getMainLooper()).await()
        info(CC_PRIVATE) { "FG: started receiving location updates" }
    }
}

fun Context.stopReceivingLocationUpdatesFg() = ignoringException {
    if (LocationCallbackFg.locationState == null) return
    fusedLocationProviderClient.removeLocationUpdates(LocationCallbackFg)
    LocationCallbackFg.locationState = null
    info(CC_PRIVATE) { "FG: asked to stop receiving location updates" }
}

@SuppressLint("MissingPermission")
suspend fun Context.receiveLocationUpdatesBg() = ignoringException {
    with(fusedLocationProviderClient) {
        tryFetchLastLocation()?.also {
            info { "BG: lastLocation = ${it.description}" }
            appContext.storeLocation(it)
        }
        requestLocationUpdates(locationRequestBg, intentToReceiveLocation()).await()
    }
    info(CC_PRIVATE) { "BG: started receiving location updates" }
}

fun Context.stopReceivingLocationUpdatesBg() = ignoringException {
    fusedLocationProviderClient.removeLocationUpdates(intentToReceiveLocation())
    info(CC_PRIVATE) { "BG: asked to stop receiving location updates" }
}

private fun Context.intentToReceiveLocation() =
    PendingIntent.getService(this, 0, Intent(this, ReceiveLocationService::class.java), 0)

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

suspend fun Context.refreshLocation(callingFromBg: Boolean) = ignoringException {
    val timestamp = storedLocation.third
    val age = System.currentTimeMillis() - timestamp
    if (age <= 5 * MINUTE_IN_MILLIS) return
    val ageString = if (timestamp != 0L) "stale (${MILLISECONDS.toMinutes(age)} minutes old)" else "absent"
    val groundString = if (callingFromBg) "background" else "foreground"
    if (!canAccessLocation(callingFromBg)) {
        info(CC_PRIVATE) { "Location is $ageString, can't refresh it from $groundString" +
                " due to lack of permissions/location settings" }
        deleteLocation()
        return
    }
    info(CC_PRIVATE) { "Refreshing location because it's $ageString" }
    fusedLocationProviderClient.tryFetchLastLocation()?.also { storeLocation(it) }
}

val Context.locationIfFresh: Triple<Double, Double, Long>?
    get() {
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

fun Context.appHasFgLocationPermission() =
    checkSelfPermission(this, ACCESS_COARSE_LOCATION) == PERMISSION_GRANTED

fun Context.appHasBgLocationPermission() =
    checkSelfPermission(this, ACCESS_COARSE_LOCATION) == PERMISSION_GRANTED
            && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || checkSelfPermission(this, ACCESS_BACKGROUND_LOCATION) == PERMISSION_GRANTED)

suspend fun Context.locationSettingsException(
    vararg locationRequests: LocationRequest
): ApiException? = try {
    getSettingsClient(this)
        .checkLocationSettings(
            LocationSettingsRequest.Builder()
                .addAllLocationRequests(locationRequests.asList()).build()
        )
        .await()
    null
} catch (e: ApiException) {
    e
}

fun RadarImageFragment.startIntentRequestLocationPermissionFg() = startIntentRequestLocationPermission(true)

fun RadarImageFragment.startIntentRequestLocationPermissionBg() = startIntentRequestLocationPermission(false)

private fun RadarImageFragment.startIntentRequestLocationPermission(requestFg: Boolean) = run {
    info { "startIntentRequestLocationPermission" }
    val permissionToAskFor =
        if (requestFg || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) ACCESS_COARSE_LOCATION
        else ACCESS_BACKGROUND_LOCATION
    permissionRequest.launch(permissionToAskFor)
}

fun RadarImageFragment.startIntentResolveException(e: ResolvableApiException) =
    resolveApiExceptionRequest.launch(IntentSenderRequest.Builder(e.resolution.intentSender).build())

private val Context.sensorManager get() = getSystemService(Context.SENSOR_SERVICE) as SensorManager?
private val Context.fusedLocationProviderClient get() = getFusedLocationProviderClient(this)

@SuppressLint("MissingPermission")
private suspend fun FusedLocationProviderClient.tryFetchLastLocation(): Location? = lastLocation.await()
    ?.also { info { "Got response from getLastLocation()" } }
    ?: run { warn { "getLastLocation() returned null" }; null }

private inline fun ignoringException(block: () -> Unit) {
    try {
        block()
    } catch (e: ApiException) {
        severe(e) { "Failed to complete a Location Service operation" }
    } catch (e: SecurityException) {
        severe(e) { "Failed to complete a Location Service operation" }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        severe(e) { "Location Service operation failed with an unexpected exception!" }
    }
}

class ReceiveLocationService : IntentService("Receive Location Updates") {
    override fun onHandleIntent(intent: Intent?) {
        val location = intent?.let { extractResult(it) } ?.lastLocation ?: return
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
            debug { "Azimuth changed to ${azimuth}, accuracy $azimuthAccuracy" }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == TYPE_ROTATION_VECTOR) {
            info { "Azimuth accuracy changed to $accuracy" }
            locationState.azimuthAccuracy = accuracy
        }
    }
}
