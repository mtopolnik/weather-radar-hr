package com.belotron.weatherradarhr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

class DisplayState : CoroutineScope {
    var indexOfImgInFullScreen: Int? = null
    val isInFullScreen: Boolean get() = indexOfImgInFullScreen != null
    val imgBundles: List<ImageBundle> = (0..1).map { ImageBundle() }

    override lateinit var coroutineContext: CoroutineContext
        private set

    fun start(block: suspend CoroutineScope.() -> Unit) =
            this.launch(start = CoroutineStart.UNDISPATCHED, block = block)

    fun startCoroutineScope() {
        coroutineContext = Dispatchers.Main + SupervisorJob()
    }

    fun destroy() {
        imgBundles.forEach { it.destroyViews() }
        coroutineContext[Job]!!.cancel()
    }

}
