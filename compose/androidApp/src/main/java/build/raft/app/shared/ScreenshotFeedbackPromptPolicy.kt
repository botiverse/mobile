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

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal class Android14ScreenCaptureObserver(
    private val activity: Activity,
    private val executor: Executor,
    private val onScreenCaptured: (Long) -> Unit,
) : ScreenCaptureObserver {
    private val registration =
        FailClosedScreenCaptureRegistration<Activity.ScreenCaptureCallback>(
            register = { callback -> activity.registerScreenCaptureCallback(executor, callback) },
            unregister = activity::unregisterScreenCaptureCallback,
        )

    override fun start(generation: Long) {
        val callback = Activity.ScreenCaptureCallback { onScreenCaptured(generation) }
        registration
            .start(callback)
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
    }

    override fun stop() {
        registration
            .stop()
            .onFailure { throwable ->
                RaftAppLog.record(
                    "SlockScreenshotFeedback",
                    "screen_capture_listener_stop_failed error=${throwable.javaClass.simpleName}",
                )
            }
    }
}
