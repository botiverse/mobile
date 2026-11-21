package build.raft.app.shared

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver

/**
 * Defers non-first-frame resume work (widget refreshes, Play services / battery optimization
 * probes, notification permission / unread group summary checks, keep-alive start) until after
 * the resumed window draws its first frame, and cancels pending work if the activity pauses
 * before it runs.
 *
 * Keeping those Binder IPCs and SharedPreferences reads out of `Activity.onResume()` avoids
 * inflating resume `firstFrameMs` and blocking the opening scroll frames on return from
 * background (Hands 45eccbd9, #wg-mobile-feedback:#257).
 */
internal class RaftPostFirstFrameResumeCoordinator {
    private var generation: Long = 0L
    private var cancelPending: (() -> Unit)? = null

    /**
     * Schedules [onReady] after [decorView]'s next draw completes (via `decorView.post` from
     * `OnDrawListener`, never removing the listener inside `onDraw`), with a [fallbackDelayMillis]
     * timer in case resuming from a translucent dialog queues no draw. Any prior pending schedule
     * is cancelled first.
     */
    fun scheduleAfterFirstFrame(
        decorView: View?,
        fallbackDelayMillis: Long = DEFAULT_FALLBACK_DELAY_MILLIS,
        onReady: () -> Unit,
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        val postMain: (() -> Unit) -> CancelHandle = { task ->
            val runnable = Runnable(task)
            mainHandler.post(runnable)
            CancelHandle { mainHandler.removeCallbacks(runnable) }
        }
        val postMainDelayed: (Long, () -> Unit) -> CancelHandle = { delayMs, task ->
            val runnable = Runnable(task)
            mainHandler.postDelayed(runnable, delayMs)
            CancelHandle { mainHandler.removeCallbacks(runnable) }
        }
        if (decorView == null || !decorView.viewTreeObserver.isAlive) {
            schedule(
                attachDrawListener = null,
                postMain = postMain,
                postMainDelayed = postMainDelayed,
                fallbackDelayMillis = fallbackDelayMillis,
                onReady = onReady,
            )
            return
        }
        schedule(
            attachDrawListener = { onDrawCallback ->
                val listener =
                    object : ViewTreeObserver.OnDrawListener {
                        override fun onDraw() {
                            onDrawCallback()
                        }
                    }
                val observer = decorView.viewTreeObserver
                if (observer.isAlive) {
                    observer.addOnDrawListener(listener)
                }
                CancelHandle {
                    val currentObserver = decorView.viewTreeObserver
                    if (currentObserver.isAlive) {
                        runCatching { currentObserver.removeOnDrawListener(listener) }
                    }
                    if (observer !== currentObserver && observer.isAlive) {
                        runCatching { observer.removeOnDrawListener(listener) }
                    }
                }
            },
            postMain = postMain,
            postMainDelayed = postMainDelayed,
            fallbackDelayMillis = fallbackDelayMillis,
            onReady = onReady,
        )
    }

    /** Platform-free scheduling core for deterministic unit testing. */
    internal fun schedule(
        attachDrawListener: ((onDraw: () -> Unit) -> CancelHandle)?,
        postMain: (task: () -> Unit) -> CancelHandle,
        postMainDelayed: (delayMillis: Long, task: () -> Unit) -> CancelHandle,
        fallbackDelayMillis: Long = DEFAULT_FALLBACK_DELAY_MILLIS,
        onReady: () -> Unit,
    ) {
        cancel()
        val token = ++generation
        var drawHandle: CancelHandle? = null
        var postDrawHandle: CancelHandle? = null
        var fallbackHandle: CancelHandle? = null
        var drawObserved = false

        fun cleanup() {
            drawHandle?.cancel()
            drawHandle = null
            postDrawHandle?.cancel()
            postDrawHandle = null
            fallbackHandle?.cancel()
            fallbackHandle = null
        }

        fun fireIfCurrent() {
            if (generation != token) return
            generation += 1L
            cancelPending = null
            cleanup()
            onReady()
        }

        cancelPending = {
            cleanup()
        }

        if (attachDrawListener == null) {
            postDrawHandle = postMain { fireIfCurrent() }
            return
        }

        fallbackHandle = postMainDelayed(fallbackDelayMillis) { fireIfCurrent() }
        drawHandle =
            attachDrawListener {
                if (generation != token || drawObserved) return@attachDrawListener
                drawObserved = true
                // Android forbids removing an OnDrawListener from inside onDraw; post to the next
                // main-looper turn right after this frame finishes drawing.
                postDrawHandle = postMain { fireIfCurrent() }
            }
    }

    fun cancel() {
        generation += 1L
        val pending = cancelPending
        cancelPending = null
        pending?.invoke()
    }

    internal fun interface CancelHandle {
        fun cancel()
    }

    companion object {
        const val DEFAULT_FALLBACK_DELAY_MILLIS: Long = 100L
    }
}
