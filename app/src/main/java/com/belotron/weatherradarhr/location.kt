package com.belotron.weatherradarhr

val lradarShape = ScreenShape(
        topLat = 47.40,
        botLat = 44.71,
        topLeftLon = 12.10,
        botLeftLon = 12.19,
        topRightLon = 17.40,
        botRightLon = 17.30,
        leftScreenX = 10,
        rightScreenX = 810,
        topScreenY = 49,
        botScreenY = 649
)

val kradarShape = ScreenShape(
        topLat = 48.06,
        botLat = 43.44,
        topLeftLon = 13.05,
        botLeftLon = 13.15,
        topRightLon = 19.84,
        botRightLon = 19.74,
        leftScreenX = 1,
        rightScreenX = 478,
        topScreenY = 1,
        botScreenY = 478
)

class ScreenShape(
        val topLat: Double,
        val botLat: Double,
        val topLeftLon: Double,
        topRightLon: Double,
        botLeftLon: Double,
        botRightLon: Double,
        leftScreenX: Int,
        rightScreenX: Int,
        val topScreenY: Int,
        botScreenY: Int
) {
    // zeroLon satisfies the following:
    // (zeroLon - topLeftLon) / (topRightLon - zeroLon) ==
    // (zeroLon - botLeftLon) / (botRightLon - zeroLon)
    val zeroLon: Double = (topLeftLon * botRightLon - botLeftLon * topRightLon) /
            (topLeftLon - botLeftLon + botRightLon - topRightLon)
    val screenWidth: Int = rightScreenX - leftScreenX
    val xScaleAtTop: Double = screenWidth / (topRightLon - topLeftLon)
    val xScaleAtBot: Double = screenWidth / (botRightLon - botLeftLon)
    val zeroScreenX: Double = 0.5 + leftScreenX + xScaleAtTop * (zeroLon - topLeftLon)
    val screenHeight: Int = botScreenY - topScreenY

    fun toScreenCoords(
            lat: Double, lon: Double
    ): Pair<Int, Int> {
        val normY: Double = (topLat - lat) / (topLat - botLat)
        val xScaleAtY: Double = xScaleAtTop + (xScaleAtBot - xScaleAtTop) * normY
        return Pair(
                (zeroScreenX + xScaleAtY * (lon - zeroLon)).toInt(),
                topScreenY + (screenHeight * normY).toInt()
        )
    }
}
