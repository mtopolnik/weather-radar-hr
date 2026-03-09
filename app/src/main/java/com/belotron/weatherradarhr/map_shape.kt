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

import android.location.Location
import kotlin.math.cos
import kotlin.math.sin

const val METERS_PER_DEGREE = 111_111

val sloShape = MapShapeSimple(
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

val hrKompozitShape = MapShapeSimple(
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

val hrGradisteShape = MapShapeSimple(
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

val hrBilogoraShape = MapShapeSimple(
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

val hrPuntijarkaShape = MapShapeSimple(
    topLat = 48.08,
    botLat = 43.75,
    topLeftLon = 12.80,
    botLeftLon = 12.95,
    topRightLon = 19.13,
    botRightLon = 18.96,
    leftImageX = 1,
    rightImageX = 658,
    topImageY = 61,
    botImageY = 718
)

val hrGoliShape = MapShapeSimple(
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

val hrDebeljakShape = MapShapeSimple(
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

val hrUljenjeShape = MapShapeSimple(
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

/*
 * Data for fitting:
 *   City; lot, lon;  imgX, imgY
 *   Reykyavik; 64.148, -22.024; 578, 154
 *   Alexandria; 31.190, 29.831; 1127, 561
 *   Lisbon; 38.705, -9.119; 595, 543
 *   Zagreb; 45.811, 16.000; 874, 418
 *   London; 51.494, -0.103 705, 357
 */
val metnoShape = MapShapeGeoSat(
    stdLat1 = 26.9,
    stdLat2 = 121.1,
    centralMeridionDeg = 1.7,
    affXx = 1000.7639, affXy = -35.8410, affX0 = 698.1696,
    affYx = -58.6309, affYy = -849.4068, affY0 = -163.4235,
    pixelSizeMeters = 7484f
)

interface MapProjection {
    val pixelSizeMeters: Float
    fun locationToPixel(location: Location, point: FloatArray) {
        locationToPixel(location.latitude, location.longitude, point)
    }
    fun locationToPixel(lat: Double, lon: Double, point: FloatArray)
}

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
class MapShapeSimple(
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
) : MapProjection {
    override val pixelSizeMeters: Float

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

    override fun locationToPixel(location: Location, point: FloatArray) {
        val (lat, lon) = location
        locationToPixel(lat, lon, point)
    }

    override fun locationToPixel(lat: Double, lon: Double, point: FloatArray) {
        val normY: Double = (topLat - lat) / (topLat - botLat)
        val xScaleAtY: Double = xScaleAtTop + (xScaleAtBot - xScaleAtTop) * normY
        point[0] = (zeroImageX + xScaleAtY * (lon - zeroLon)).toFloat()
        point[1] = topImageY + (imageHeightPixels * normY).toFloat()
    }
}

/**
 * Transforms a lat-lon location to a pixel on a satellite map image using an
 * Equidistant Conic projection with an affine pixel mapping.
 *
 * The Equidistant Conic projection preserves distances along meridians and is
 * suitable for continental-scale satellite imagery where the simple
 * converging-meridians model of [MapShapeSimple] breaks down.
 */
class MapShapeGeoSat(
    stdLat1: Double,
    stdLat2: Double,
    centralMeridionDeg: Double,
    private val affXx: Double,
    private val affXy: Double,
    private val affX0: Double,
    private val affYx: Double,
    private val affYy: Double,
    private val affY0: Double,
    override val pixelSizeMeters: Float,
) : MapProjection {
    private val n: Double
    private val g: Double
    private val lam0: Double = Math.toRadians(centralMeridionDeg)

    init {
        val phi1 = Math.toRadians(stdLat1)
        val phi2 = Math.toRadians(stdLat2)
        n = (cos(phi1) - cos(phi2)) / (phi2 - phi1)
        g = cos(phi1) / n + phi1
    }

    override fun locationToPixel(lat: Double, lon: Double, point: FloatArray) {
        val phi = Math.toRadians(lat)
        val rho = g - phi
        val theta = n * (Math.toRadians(lon) - lam0)
        val projX = rho * sin(theta)
        val projY = -rho * cos(theta)
        point[0] = (affXx * projX + affXy * projY + affX0).toFloat()
        point[1] = (affYx * projX + affYy * projY + affY0).toFloat()
    }
}
