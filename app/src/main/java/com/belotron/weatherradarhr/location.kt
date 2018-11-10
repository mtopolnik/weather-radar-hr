package com.belotron.weatherradarhr

val kradarShape = ScreenShape(
        topLat = Lat(48.07), // 47.89, 48.02
        botLat = Lat(43.37), // 43.38, 43.36
        topLeftLon = Lon(13.06),
        botLeftLon = Lon(13.15),
        topRightLon = Lon(19.86),
        botRightLon = Lon(19.76)
)

fun toScreenCoords(
        lat: Lat, lon: Lon,
        expectedScreenX: Int, expectedScreenY: Int,
        screenShape: ScreenShape
): Pair<Int, Int> = with(screenShape) {
    val midLon = (topLeftLon + topRightLon) / 2.0.Lon
    val x = lon - midLon
    val y = topLat - lat

    val normY = y / (topLat - botLat)

    val xScaleAtTop = 1 / (topRightLon - midLon).lon
    val xScaleAtBottom = 1 / (botRightLon - midLon).lon
    val xScaleAtY = (xScaleAtTop + (xScaleAtBottom - xScaleAtTop) * normY.lat).Lon
    val normX = x * xScaleAtY

    val screenX = (240 * (1 + normX.lon)).toInt()
    val screenY = (480 * normY.lat).toInt()

    println("($lon, $lat), ($normX, $normY): (${screenX - expectedScreenX}, ${screenY - expectedScreenY})")
    return Pair(screenX, screenY)
}

data class ScreenShape(
        val topLat: Lat,
        val botLat: Lat,
        val topLeftLon: Lon,
        val topRightLon: Lon,
        val botLeftLon: Lon,
        val botRightLon: Lon
)

inline class Lat(val lat: Double) {
    operator fun plus(that: Lat) = Lat(lat + that.lat)
    operator fun minus(that: Lat) = Lat(lat - that.lat)
    operator fun times(that: Lat) = Lat(lat * that.lat)
    operator fun div(that: Lat) = Lat(lat / that.lat)
    override fun toString() = "%04.2f".format(lat)
}

inline class Lon(val lon: Double) {
    operator fun plus(that: Lon) = Lon(lon + that.lon)
    operator fun minus(that: Lon) = Lon(lon - that.lon)
    operator fun times(that: Lon) = Lon(lon * that.lon)
    operator fun div(that: Lon) = Lon(lon / that.lon)
    override fun toString() = "%04.2f".format(lon)
}

inline val Double.Lon get() = Lon(this)
inline val Double.Lat get() = Lat(this)
