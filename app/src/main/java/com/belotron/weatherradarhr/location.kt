package com.belotron.weatherradarhr

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.location.Location
import androidx.core.content.PermissionChecker.PERMISSION_GRANTED
import androidx.core.content.PermissionChecker.checkSelfPermission
import androidx.fragment.app.Fragment
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.Continuation

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

var requestLocationPermissionContinuation: Continuation<Int>? = null
    set(value) { field = field ?: value }
    get() = field?.also { field = null }

suspend fun Fragment.receiveLocationUpdates(
        locationClient: FusedLocationProviderClient,
        callback: (Location) -> Unit
) {
    if (checkSelfPermission(context!!, ACCESS_COARSE_LOCATION) != PERMISSION_GRANTED) {
        warn { "Our app has no permission to access coarse location" }
        val grantResult = suspendCancellableCoroutine<Int> {
            requestLocationPermissionContinuation = it
            requestPermissions(arrayOf(ACCESS_COARSE_LOCATION), REQUEST_CODE_LOCATION_PERMISSION)
        }
        if (grantResult != PERMISSION_GRANTED) {
            warn { "Result of requestPermissions: $grantResult" }
            return
        }
    }
    val locationRequest = LocationRequest().apply {
        interval = 10_000
        fastestInterval = 10
        priority = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
    }
    try {
        LocationServices.getSettingsClient(context!!)
                .checkLocationSettings(LocationSettingsRequest.Builder()
                        .addLocationRequest(locationRequest).build())
                .await()
    } catch (e: ResolvableApiException) {
        warn { "ResolvableApiException for location request" }
        val result = suspendCancellableCoroutine<Int> {
            requestLocationPermissionContinuation = it
            startIntentSenderForResult(e.resolution.intentSender, REQUEST_CODE_LOCATION_PERMISSION,
                    null, 0, 0, 0, null)
        }
        if (result != -1) {
            warn { "Result of ResolvableApiException resolution: $result" }
            return
        }
    }
    locationClient.lastLocation.await()?.also { callback(it) }
    locationClient.requestLocationUpdates(locationRequest,
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) = callback(result.lastLocation)
            },
            null)
}
