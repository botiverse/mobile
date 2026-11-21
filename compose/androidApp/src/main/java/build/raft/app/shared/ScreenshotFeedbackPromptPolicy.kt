package build.raft.app.shared

import android.app.Activity
import android.os.Build
import androidx.annotation.RequiresApi
import build.raft.app.core.data.diagnostics.RaftAppLog
import java.util.concurrent.Executor

internal const val SCREENSHOT_FEEDBACK_MINIMUM_INTERVAL_MILLIS = 1_500L
internal const val SCREENSHOT_FEEDBACK_VISIBLE_MILLIS = 9_000L

internal fun shouldEnableScreenshotFeedback(
    buildEnabled: Boolean,
    runtimeEnabled: Boolean,
    sdkInt: Int,
): Boolean =
    buildEnabled &&
        runtimeEnabled &&
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

internal class ScreenshotFeedbackEventGate(
    private val minimumIntervalMillis: Long,
) {
    private var lastAcceptedAtMillis: Long? = null

    fun accept(nowMillis: Long): Boolean {
        val lastAccepted = lastAcceptedAtMillis
        if (lastAccepted != null && nowMillis - lastAccepted < minimumIntervalMillis) {
            return false
        }
        lastAcceptedAtMillis = nowMillis
        return true
    }
}

internal class ScreenshotFeedbackListeningGate {
    private var lastGeneration = 0L
    private var activeGeneration: Long? = null

    fun start(): Long =
        (++lastGeneration).also { generation -> activeGeneration = generation }

    fun stop() {
        activeGeneration = null
    }

    fun accepts(generation: Long): Boolean = activeGeneration == generation
}

internal interface ScreenCaptureObserver {
    fun start(generation: Long)

    fun stop()
}

internal class FailClosedScreenCaptureRegistration<T>(
    private val register: (T) -> Unit,
    private val unregister: (T) -> Unit,
) {
    private var registeredCallback: T? = null

    fun start(callback: T): Result<Boolean> {
        if (registeredCallback != null) return Result.success(false)
        return runCatching { register(callback) }
            .map {
                registeredCallback = callback
                true
            }
    }

    fun stop(): Result<Boolean> {
        val callback = registeredCallback ?: return Result.success(false)
        registeredCallback = null
        return runCatching { unregister(callback) }.map { true }
    }
}

/**
 * Defers [FailClosedScreenCaptureRegistration.start] past first frame so `Activity.onStart()` does
 * not incur a synchronous `registerScreenCaptureCallback` Binder IPC before Frame 1 draws.
 *
 * Cancels any pending registration if [stop] runs before the scheduled callback executes, and
 * re-checks both active generation and [canRegister] right before calling into the platform.
 */
internal class DeferredScreenCaptureRegistration<T>(
    private val registration: FailClosedScreenCaptureRegistration<T>,
    private val schedule: (task: () -> Unit) -> () -> Unit,
    private val canRegister: () -> Boolean = { true },
    private val onRegisterResult: (Result<Boolean>) -> Unit = {},
    private val onUnregisterResult: (Result<Boolean>) -> Unit = {},
) {
    private var activeGeneration: Long = 0L
    private var isStarted: Boolean = false
    private var cancelPending: (() -> Unit)? = null

    fun start(callback: T) {
        cancelPending?.invoke()
        cancelPending = null
        isStarted = true
        val token = ++activeGeneration
        var fired = false
        val cancel =
            schedule {
                fired = true
                if (!isStarted || activeGeneration != token) return@schedule
                cancelPending = null
                if (!canRegister()) return@schedule
                onRegisterResult(registration.start(callback))
            }
        if (!fired && isStarted && activeGeneration == token) {
            cancelPending = cancel
        }
    }

    fun stop() {
        isStarted = false
        activeGeneration += 1L
        cancelPending?.invoke()
        cancelPending = null
        onUnregisterResult(registration.stop())
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal class Android14ScreenCaptureObserver(
    private val activity: Activity,
    private val executor: Executor,
    private val onScreenCaptured: (Long) -> Unit,
) : ScreenCaptureObserver {
    private val firstFrameCoordinator = RaftPostFirstFrameResumeCoordinator()
    private val registration =
        DeferredScreenCaptureRegistration(
            registration =
                FailClosedScreenCaptureRegistration<Activity.ScreenCaptureCallback>(
                    register = { callback -> activity.registerScreenCaptureCallback(executor, callback) },
                    unregister = activity::unregisterScreenCaptureCallback,
                ),
            schedule = { onReady ->
                firstFrameCoordinator.scheduleAfterFirstFrame(activity.window?.decorView, onReady = onReady)
                val cancel: () -> Unit = { firstFrameCoordinator.cancel() }
                cancel
            },
            canRegister = { !activity.isFinishing && !activity.isDestroyed },
            onRegisterResult = { result ->
                result
                    .onSuccess { registered ->
                        if (registered) {
                            RaftAppLog.record("SlockScreenshotFeedback", "screen_capture_listener_started")
                        }
                    }.onFailure { throwable ->
                        RaftAppLog.record(
                            "SlockScreenshotFeedback",
                            "screen_capture_listener_start_failed error=${throwable.javaClass.simpleName}",
                        )
                    }
            },
            onUnregisterResult = { result ->
                result.onFailure { throwable ->
                    RaftAppLog.record(
                        "SlockScreenshotFeedback",
                        "screen_capture_listener_stop_failed error=${throwable.javaClass.simpleName}",
                    )
                }
            },
        )

    override fun start(generation: Long) {
        val callback = Activity.ScreenCaptureCallback { onScreenCaptured(generation) }
        registration.start(callback)
    }

    override fun stop() {
        registration.stop()
    }
}
