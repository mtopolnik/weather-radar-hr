package com.belotron.weatherradarhr

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.Sensor.TYPE_ROTATION_VECTOR
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import androidx.core.content.PermissionChecker.PERMISSION_GRANTED
import androidx.core.content.PermissionChecker.checkSelfPermission
import androidx.fragment.app.Fragment
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sign
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

val Float.degrees get() = Math.toDegrees(this.toDouble()).toFloat()

class MapShape(
        val topLat: Double,
        val botLat: Double,
        val topLeftLon: Double,
        topRightLon: Double,
        botLeftLon: Double,
        botRightLon: Double,
        leftScreenX: Int,
        rightScreenX: Int,
        val topImageY: Int,
        botImageY: Int
) {
    // zeroLon satisfies the following:
    // (zeroLon - topLeftLon) / (topRightLon - zeroLon) ==
    // (zeroLon - botLeftLon) / (botRightLon - zeroLon)
    val zeroLon: Double
    val xScaleAtTop: Double
    val xScaleAtBot: Double
    val zeroImageX: Double
    val imageHeight: Int = botImageY - topImageY

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
        val normY: Double = (topLat - location.latitude) / (topLat - botLat)
        val xScaleAtY: Double = xScaleAtTop + (xScaleAtBot - xScaleAtTop) * normY
        point[0] = (zeroImageX + xScaleAtY * (location.longitude - zeroLon)).toFloat()
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

suspend fun Fragment.receiveLocationUpdates(
        callback: (Location) -> Unit
) {
    if (checkSelfPermission(context!!, ACCESS_FINE_LOCATION) != PERMISSION_GRANTED) {
        warn { "Our app has no permission to access fine location" }
        requestFineLocationPermission().also { grantResult ->
            if (grantResult != PERMISSION_GRANTED) {
                warn { "Result of requestPermissions: $grantResult" }
                return
            }
            info { "User has granted us the coarse location permission" }
        }
    }
    val locationRequest = LocationRequest().apply {
        interval = 1000
        fastestInterval = 10
        priority = PRIORITY_BALANCED_POWER_ACCURACY
    }
    try {
        LocationServices.getSettingsClient(context!!)
                .checkLocationSettings(LocationSettingsRequest.Builder()
                        .addLocationRequest(locationRequest).build())
                .await()
    } catch (e: ResolvableApiException) {
        warn { "ResolvableApiException for location request" }
        resolve(e).also { result ->
            if (result != Activity.RESULT_OK) {
                warn { "ResolvableApiException resolution failed with code $result" }
                return
            }
            info { "ResolvableApiException is now resolved" }
        }
    }
    val locationClient = LocationServices.getFusedLocationProviderClient(context!!)
    locationClient.requestLocationUpdates(locationRequest,
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) = callback(result.lastLocation)
            },
            null)
    info { "getLastLocation()" }
    locationClient.lastLocation.await()?.also {
        info { "Got response from getLastLocation()" }
        callback(it)
    } ?: warn { "getLastLocation() returned null" }
}

private suspend fun Fragment.requestFineLocationPermission(): Int = suspendCancellableCoroutine {
    requestPermissions(arrayOf(ACCESS_FINE_LOCATION), prepareToSuspend(it))
}

private suspend fun Fragment.resolve(e: ResolvableApiException): Int = suspendCancellableCoroutine {
    startIntentSenderForResult(e.resolution.intentSender, prepareToSuspend(it), null, 0, 0, 0, null)
}

fun Fragment.receiveAzimuthUpdates(
        azimuthChanged: (Float) -> Unit,
        accuracyChanged: (Int) -> Unit
) {
    val sensorManager = activity!!.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val sensor: Sensor? = sensorManager.getDefaultSensor(TYPE_ROTATION_VECTOR)
    sensorManager.registerListener(OrientationListener(azimuthChanged, accuracyChanged), sensor, 10_000)
}

private class OrientationListener(
        private val azimuthChanged: (Float) -> Unit,
        private val accuracyChanged: (Int) -> Unit
) : SensorEventListener {
    private val rotationMatrix = FloatArray(9)

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val x = rotationMatrix[0]
        val y = rotationMatrix[3]
        val azimuth = atan(x / y) - sign(y) * PI.toFloat() / 2
        azimuthChanged(azimuth)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        info { "Azimuth accuracy changed to $accuracy" }
        if (sensor.type == TYPE_ROTATION_VECTOR) {
            accuracyChanged(accuracy)
        }
    }
}
