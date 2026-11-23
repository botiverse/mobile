package build.raft.app.shared

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.annotation.RequiresApi
import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.data.PlatformFile
import build.raft.app.data.RaftPrivacyMode
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal const val SCREENSHOT_FEEDBACK_MINIMUM_INTERVAL_MILLIS = 1_500L
internal const val SCREENSHOT_FEEDBACK_MAX_DIMENSION_PX = 1_600
internal const val SCREENSHOT_FEEDBACK_JPEG_QUALITY = 85
internal const val SCREENSHOT_FEEDBACK_CACHE_DIR_NAME = "screenshot-feedback"
private const val SCREENSHOT_FEEDBACK_PIXEL_COPY_TIMEOUT_MILLIS = 2_000L

/**
 * Android screenshot feedback eligibility gate (#wg-mobile-feedback:#276).
 *
 * Activates only when:
 * `(serverFlagEnabled || buildAlphaEnabled) && signedIn && privacyMode == Off`
 * on API 34+ (`Activity.ScreenCaptureCallback`, zero storage/Photos permissions).
 */
internal fun shouldEnableScreenshotFeedback(
    serverFlagEnabled: Boolean,
    buildAlphaEnabled: Boolean,
    signedIn: Boolean,
    privacyMode: RaftPrivacyMode,
    sdkInt: Int,
): Boolean =
    (serverFlagEnabled || buildAlphaEnabled) &&
        signedIn &&
        privacyMode == RaftPrivacyMode.Off &&
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

/**
 * Captures the current [activity] window via `PixelCopy.request(window, bitmap, ...)`, downscales
 * to at most [maxDimensionPx] along the longest edge, and compresses JPEG off the main thread into
 * app-private `<cacheDir>/screenshot-feedback/<uuid>.jpg`. Returns null gracefully on any failure.
 */
internal suspend fun captureWindowScreenshotFeedbackFile(
    activity: Activity,
    cacheDir: File,
    maxDimensionPx: Int = SCREENSHOT_FEEDBACK_MAX_DIMENSION_PX,
    jpegQuality: Int = SCREENSHOT_FEEDBACK_JPEG_QUALITY,
): PlatformFile? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
    val window = activity.window ?: return null
    val decorView = window.decorView ?: return null
    val width = decorView.width
    val height = decorView.height
    if (width <= 0 || height <= 0 || activity.isFinishing || activity.isDestroyed) return null

    val capturedBitmap: Bitmap =
        try {
            val rawBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val copyResult =
                withTimeoutOrNull(SCREENSHOT_FEEDBACK_PIXEL_COPY_TIMEOUT_MILLIS) {
                    suspendCancellableCoroutine<Int> { continuation ->
                        try {
                            PixelCopy.request(
                                window,
                                rawBitmap,
                                { resultCode ->
                                    if (continuation.isActive) continuation.resume(resultCode)
                                },
                                Handler(Looper.getMainLooper()),
                            )
                        } catch (_: Throwable) {
                            if (continuation.isActive) continuation.resume(PixelCopy.ERROR_UNKNOWN)
                        }
                    }
                }
            if (copyResult != PixelCopy.SUCCESS) {
                rawBitmap.recycleSafely()
                RaftAppLog.record("SlockScreenshotFeedback", "pixel_copy_failed result=${copyResult ?: "timeout"}")
                return null
            }
            rawBitmap
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            RaftAppLog.record("SlockScreenshotFeedback", "pixel_copy_exception error=${throwable.javaClass.simpleName}")
            return null
        }

    return withContext(Dispatchers.IO) {
        var scaledBitmap: Bitmap? = null
        val uuid = UUID.randomUUID().toString()
        val outputDir = File(cacheDir, SCREENSHOT_FEEDBACK_CACHE_DIR_NAME)
        val outputFile = File(outputDir, "$uuid.jpg")
        val partFile = File(outputDir, "$uuid.jpg.part")
        var committed = false
        try {
            if (!outputDir.exists() && !outputDir.mkdirs()) return@withContext null
            val longestEdge = max(capturedBitmap.width, capturedBitmap.height)
            val targetBitmap =
                if (longestEdge > maxDimensionPx && maxDimensionPx > 0) {
                    val scale = maxDimensionPx.toFloat() / longestEdge.toFloat()
                    val targetWidth = (capturedBitmap.width * scale).roundToInt().coerceAtLeast(1)
                    val targetHeight = (capturedBitmap.height * scale).roundToInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(capturedBitmap, targetWidth, targetHeight, true).also {
                        scaledBitmap = it
                    }
                } else {
                    capturedBitmap
                }
            FileOutputStream(partFile).use { stream ->
                if (!targetBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, stream)) {
                    return@withContext null
                }
            }
            if (!partFile.isFile || partFile.length() <= 0L) return@withContext null
            if (!partFile.renameTo(outputFile)) return@withContext null
            committed = true
            val sizeBytes = outputFile.length().takeIf { it > 0L } ?: return@withContext null
            val fileUri = Uri.fromFile(outputFile).toString()
            PlatformFile(
                id = "screenshot-$uuid",
                fileName = "screenshot-${uuid.take(8)}.jpg",
                mimeType = "image/jpeg",
                sizeBytes = sizeBytes,
                previewUri = fileUri,
                fileRef = fileUri,
                source = "screenshot_feedback",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            RaftAppLog.record("SlockScreenshotFeedback", "screenshot_encode_failed error=${throwable.javaClass.simpleName}")
            null
        } finally {
            if (!committed) {
                runCatching { partFile.delete() }
                runCatching { outputFile.delete() }
            }
            if (scaledBitmap != null && scaledBitmap !== capturedBitmap) {
                scaledBitmap?.recycleSafely()
            }
            capturedBitmap.recycleSafely()
        }
    }
}

private fun Bitmap.recycleSafely() {
    runCatching {
        if (!isRecycled) recycle()
    }
}

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
