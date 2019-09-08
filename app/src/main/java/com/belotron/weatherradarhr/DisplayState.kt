package com.belotron.weatherradarhr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

class DisplayState : CoroutineScope {
    var indexOfImgInFullScreen: Int? = null
        set(value) {
            field = value
            if (value == null) {
                isTrackingTouch = false
            }
        }
    var isTrackingTouch = false
    val isInFullScreen: Boolean get() = indexOfImgInFullScreen != null
    val imgBundles: List<ImageBundle> = (0..1).map { ImageBundle() }

    override val coroutineContext: CoroutineContext = Dispatchers.Main + SupervisorJob()

    fun destroy() {
        imgBundles.forEach { it.destroyViews() }
    }
}
