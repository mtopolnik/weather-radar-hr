package com.belotron.weatherradarhr

import android.os.Handler
import android.os.Looper
import android.os.Looper.myLooper
import kotlinx.coroutines.experimental.CancellableContinuation
import kotlinx.coroutines.experimental.CoroutineDispatcher
import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.experimental.Delay
import kotlinx.coroutines.experimental.DisposableHandle
import kotlinx.coroutines.experimental.Runnable
import kotlinx.coroutines.experimental.launch
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
class HandlerContext(
    private val handler: Handler,
    private val name: String? = null
) : CoroutineDispatcher(), Delay {

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (myLooper() == handler.looper) {
            block.run()
        } else {
            handler.post(block)
        }
    }

    override fun scheduleResumeAfterDelay(time: Long, unit: TimeUnit, continuation: CancellableContinuation<Unit>) {
        handler.postDelayed(
                { with(continuation) { resumeUndispatched(Unit) } },
                unit.toMillis(time).coerceAtMost(MAX_DELAY)
        )
    }

    override fun invokeOnTimeout(time: Long, unit: TimeUnit, block: Runnable): DisposableHandle {
        handler.postDelayed(block, unit.toMillis(time).coerceAtMost(MAX_DELAY))
        return object : DisposableHandle {
            override fun dispose() {
                handler.removeCallbacks(block)
            }
        }
    }

    override fun toString() = name ?: handler.toString()
    override fun equals(other: Any?): Boolean = other is HandlerContext && other.handler === handler
    override fun hashCode(): Int = System.identityHashCode(handler)
}
