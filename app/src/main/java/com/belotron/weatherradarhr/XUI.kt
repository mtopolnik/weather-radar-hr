package com.belotron.weatherradarhr

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.experimental.android.UI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.CoroutineContext

/**
 * Dispatches execution onto Android main UI thread and provides native
 * [delay][Delay.delay] support. When continuation.resume() is called from
 * the UI thread, executes it directly without dispatching.
 */
val XUI = HandlerContext(Handler(Looper.getMainLooper()), "XUI")

fun start(block: suspend CoroutineScope.() -> Unit) = launch(XUI, start = UNDISPATCHED, block = block)

/**
 * Represents an arbitrary [Handler] as a implementation of
 * [CoroutineDispatcher].
 */
fun Handler.asCoroutineDispatcher() = HandlerContext(this)

private const val MAX_DELAY = Long.MAX_VALUE / 2 // cannot delay for too long on Android

/**
 * Implements [CoroutineDispatcher] on top of an arbitrary Android
 * [Handler].
 * @param handler a handler.
 * @param name an optional name for debugging.
 */
public class HandlerContext(
    private val handler: Handler,
    private val name: String? = null
) : CoroutineDispatcher(), Delay {
    @Volatile
    private var _choreographer: Choreographer? = null

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (Looper.myLooper() == handler.looper) {
            block.run()
        } else {
            handler.post(block)
        }
    }

    override fun scheduleResumeAfterDelay(time: Long, unit: TimeUnit, continuation: CancellableContinuation<Unit>) {
        handler.postDelayed({
            with(continuation) { resumeUndispatched(Unit) }
        }, unit.toMillis(time).coerceAtMost(MAX_DELAY))
    }

    override fun invokeOnTimeout(time: Long, unit: TimeUnit, block: Runnable): DisposableHandle {
        handler.postDelayed(block, unit.toMillis(time).coerceAtMost(MAX_DELAY))
        return object : DisposableHandle {
            override fun dispose() {
                handler.removeCallbacks(block)
            }
        }
    }

    /**
     * Awaits the next animation frame and returns frame time in nanoseconds.
     */
    public suspend fun awaitFrame(): Long {
        // fast path when choreographer is already known
        val choreographer = _choreographer
        if (choreographer != null) {
            return suspendCancellableCoroutine { cont ->
                postFrameCallback(choreographer, cont)
            }
        }
        // post into looper thread thread to figure it out
        return suspendCancellableCoroutine { cont ->
           handler.post {
               updateChoreographerAndPostFrameCallback(cont)
           }
        }
    }

    private fun updateChoreographerAndPostFrameCallback(cont: CancellableContinuation<Long>) {
        val choreographer = _choreographer ?:
            Choreographer.getInstance()!!.also { _choreographer = it }
        postFrameCallback(choreographer, cont)
    }

    private fun postFrameCallback(choreographer: Choreographer, cont: CancellableContinuation<Long>) {
        choreographer.postFrameCallback { nanos ->
            with(cont) { resumeUndispatched(nanos) }
        }
    }

    override fun toString() = name ?: handler.toString()
    override fun equals(other: Any?): Boolean = other is HandlerContext && other.handler === handler
    override fun hashCode(): Int = System.identityHashCode(handler)
}
