/*
 * Copyright (C) 2018-2023 Marko Topolnik
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.belotron.weatherradarhr

import android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent.*
import android.content.BroadcastReceiver
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
import com.belotron.weatherradarhr.UserReaction.PROCEED
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationResult.extractResult
import com.google.android.gms.location.LocationServices.getFusedLocationProviderClient
import com.google.android.gms.location.LocationServices.getSettingsClient
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.math.atan2
import kotlin.properties.Delegates.observable
import kotlin.reflect.KProperty

private const val ACTION_RECEIVE_LOCATION = "com.belotron.weatherradarhr.action.RECEIVE_LOCATION"
const val METERS_PER_DEGREE = 111_111
private const val WAIT_MILLISECONDS_BEFORE_ASKING = 2 * SECOND_IN_MILLIS
private const val CHECK_LOCATION_ENABLED_PERIOD_MILLIS = 1 * SECOND_IN_MILLIS

val sloShape = MapShape(
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

val hrKompozitShape = MapShape(
    topLat = 47.8,
    botLat = 41.52,
    topLeftLon = 11.73,
    botLeftLon = 11.75,
    topRightLon = 20.63,
    botRightLon = 20.54,
    leftImageX = 1,
    rightImageX = 718,
    topImageY = 1,
    botImageY = 718
)

val hrGradisteShape = MapShape(
    topLat = 47.29,
    botLat = 43.00,
    topLeftLon = 15.53,
    botLeftLon = 15.76,
    topRightLon = 21.82,
    botRightLon = 21.63,
    leftImageX = 1,
    rightImageX = 658,
    topImageY = 61,
    botImageY = 718
)

val hrBilogoraShape = MapShape(
    topLat = 48.06,
    botLat = 43.72,
    topLeftLon = 14.00,
    botLeftLon = 14.19,
    topRightLon = 20.43,
    botRightLon = 20.19,
    leftImageX = 1,
    rightImageX = 658,
    topImageY = 61,
    botImageY = 718
)

val hrGoliShape = MapShape(
    topLat = 47.16,
    botLat = 42.87,
    topLeftLon = 10.88,
    botLeftLon = 11.15,
    topRightLon = 17.28,
    botRightLon = 17.05,
    leftImageX = 1,
    rightImageX = 658,
    topImageY = 61,
    botImageY = 718
)

val hrDebeljakShape = MapShape(
    topLat = 46.20,
    botLat = 41.91,
    topLeftLon = 12.26,
    botLeftLon = 12.42,
    topRightLon = 18.52,
    botRightLon = 18.24,
    leftImageX = 1,
    rightImageX = 658,
    topImageY = 61,
    botImageY = 718
)

val hrUljenjeShape = MapShape(
    topLat = 45.03,
    botLat = 40.75,
    topLeftLon = 14.42,
    botLeftLon = 14.56,
    topRightLon = 20.52,
    botRightLon = 20.35,
    leftImageX = 1,
    rightImageX = 658,
    topImageY = 61,
    botImageY = 718
)

val zamgShape = MapShape(
    topLat = 63.3,
    botLat = 27.0,
    topLeftLon = -46.0,
    botLeftLon = -25.6,
    topRightLon = 57.6,
    botRightLon = 29.4,
    leftImageX = 7,
    rightImageX = 472,
    topImageY = 7,
    botImageY = 358
)

val locationRequestFg: LocationRequest = LocationRequest.Builder(1000)
    .setMinUpdateIntervalMillis(10)
    .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
    .build()

val locationRequestBg: LocationRequest = LocationRequest.Builder(MINUTE_IN_MILLIS)
    .setMinUpdateIntervalMillis(MINUTE_IN_MILLIS)
    .setPriority(Priority.PRIORITY_LOW_POWER)
    .build()

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
        val lastLocation = result.lastLocation ?: return
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

suspend fun MainFragment.checkAndCorrectPermissionsAndSettings() {
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
        info { "FG: started receiving location updates" }
    }
}

fun Context.stopReceivingLocationUpdatesFg() = ignoringException {
    if (LocationCallbackFg.locationState == null) return
    fusedLocationProviderClient.removeLocationUpdates(LocationCallbackFg)
    LocationCallbackFg.locationState = null
    info { "FG: asked to stop receiving location updates" }
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
    info { "BG: started receiving location updates" }
}

fun Context.stopReceivingLocationUpdatesBg() = ignoringException {
    fusedLocationProviderClient.removeLocationUpdates(intentToReceiveLocation())
    info { "BG: asked to stop receiving location updates" }
}

private fun Context.intentToReceiveLocation() =
        getBroadcast(this, 0,
                Intent(this, LocationBroadcastReceiver::class.java).also { it.action = ACTION_RECEIVE_LOCATION },
                FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) FLAG_MUTABLE else 0))


fun Activity.receiveAzimuthUpdates(locationState: LocationState) {
    val sensorManager = sensorManager ?: run {
        warn { "SensorManager not available" }
        return
    }
    locationState.clearAzimuthListener(sensorManager)
    val sensor = sensorManager.getDefaultSensor(TYPE_ROTATION_VECTOR) ?: run {
        warn { "Rotation vector sensor not available" }
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
        info { "Location is $ageString, can't refresh it from $groundString" +
                " due to lack of permissions/location settings" }
        deleteLocation()
        return
    }
    info { "Refreshing location because it's $ageString" }
    fusedLocationProviderClient.tryFetchLastLocation()?.also { storeLocation(it) }
}

val Context.locationIfFresh: Triple<Double, Double, Long>?
    get() {
        val now = System.currentTimeMillis()
        val locTriple = storedLocation
        val timestamp = locTriple.third
        if (timestamp == 0L) {
            warn { "Stored location not present" }
            return null
        }
        val age = now - timestamp
        return if (age < HOUR_IN_MILLIS)
            locTriple
        else run {
            warn { "Stored location is too old, age ${MILLISECONDS.toMinutes(age)} minutes" }
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

fun MainFragment.startIntentRequestLocationPermissionFg() = startIntentRequestLocationPermission(true)

fun MainFragment.startIntentRequestLocationPermissionBg() = startIntentRequestLocationPermission(false)

private fun MainFragment.startIntentRequestLocationPermission(requestFg: Boolean) = run {
    info { "startIntentRequestLocationPermission" }
    val permissionToAskFor =
        if (requestFg || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) ACCESS_COARSE_LOCATION
        else ACCESS_BACKGROUND_LOCATION
    permissionRequest.launch(permissionToAskFor)
}

fun MainFragment.startIntentResolveException(e: ResolvableApiException) =
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

class LocationBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        info { "LocationBroadcastReceiver.onReceive" }
        intent?.action?.takeIf { it == ACTION_RECEIVE_LOCATION } ?: return
        // implementation of extractResult explicitly returns null, contradicting the annotation:
        val location = extractResult(intent)?.lastLocation ?: return
        info { "Received location in the background: ${location.description}" }
        context?.applicationContext?.storeLocation(location)
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
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display
            else activity.windowManager.defaultDisplay
        val (matrixColumn, sense) = when (val rotation = display?.rotation) {
            Surface.ROTATION_0 -> Pair(0, 1)
            Surface.ROTATION_90 -> Pair(1, -1)
            Surface.ROTATION_180 -> Pair(0, -1)
            Surface.ROTATION_270 -> Pair(1, 1)
            null -> Pair(0, 1)
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
