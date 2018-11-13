package com.belotron.weatherradarhr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DisplayState : CoroutineScope {
    var indexOfImgInFullScreen: Int? = null
    val isInFullScreen: Boolean get() = indexOfImgInFullScreen != null
    val imgBundles: List<ImageBundle> = (0..1).map { ImageBundle() }

    override var coroutineContext = newCoroCtx()
        private set

    fun start(block: suspend CoroutineScope.() -> Unit) = this.launch(start = CoroutineStart.UNDISPATCHED, block = block)

    fun destroy() {
        imgBundles.forEach { it.destroyViews() }
        coroutineContext[Job]!!.cancel()
        coroutineContext = newCoroCtx()
    }

    private fun newCoroCtx() = Dispatchers.Main + SupervisorJob()
}
