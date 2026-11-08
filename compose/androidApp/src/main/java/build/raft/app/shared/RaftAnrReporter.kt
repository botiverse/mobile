package build.raft.app.shared

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import build.hands.update.HandsFeedback
import build.hands.update.HandsFeedbackException
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reports ANRs to Hands with the build that hung.
 *
 * Hands Android SDK 0.13.0 reports ANRs itself (`HandsAnr.uploadPending`), but it
 * deliberately sends them with no version_name/version_code: `ApplicationExitInfo`
 * carries no app version, and the SDK will not stamp the uploading install's version
 * onto an older exit. Those tickets cannot be version-filtered or retraced.
 *
 * The fix is the platform's own channel: every process writes its build into
 * [ActivityManager.setProcessStateSummary] at start ([stampProcess]); the system keeps
 * it in the exit record, so the next launch reads the exact build that hung. Exits
 * without a stamp (from builds before this one) still go up with no version, never
 * the current one. RaftApplication turns the SDK's ANR pass off (`captureAnrs = false`)
 * and runs this one instead. The ticket shape (message, `crash_*` extras, trace
 * attachment) matches the SDK's so server grouping is unchanged. Drop this once the
 * SDK reads a process-state summary itself.
 */
internal object RaftAnrReporter {
    private const val TAG = "RaftAnr"
    private const val PREFS_NAME = "raft_anr_reporter"
    private const val KEY_WATERMARK = "reported_until"

    // The SDK's watermark: seeds ours so ANRs it already reported are not sent again.
    private const val SDK_PREFS_NAME = "quiver_update"
    private const val SDK_KEY_WATERMARK = "hands_anr_reported_until"

    private const val MAX_EXITS_QUERIED = 32
    internal const val MAX_REPORTS_PER_LAUNCH = 3
    internal const val FIRST_RUN_LOOKBACK_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_TRACE_BYTES = 2L * 1024 * 1024
    private const val MAX_MAIN_FRAMES = 64

    /** ActivityManager.setProcessStateSummary rejects more than 128 bytes. */
    internal const val MAX_STAMP_BYTES = 128
    private const val STAMP_PREFIX = "raft-build:1\n"

    internal data class StampedBuild(val versionName: String, val versionCode: Long)

    internal data class AnrExit(val pid: Int, val timestamp: Long, val reason: Int)

    /** `raft-build:1\n<versionCode>\n<versionName>`, the name cut to fit [MAX_STAMP_BYTES]. */
    internal fun encodeStamp(versionName: String, versionCode: Long): ByteArray {
        val head = "$STAMP_PREFIX$versionCode\n".toByteArray(Charsets.UTF_8)
        var name = versionName.trim()
        while (head.size + name.toByteArray(Charsets.UTF_8).size > MAX_STAMP_BYTES) {
            name = name.dropLast(1)
        }
        return head + name.toByteArray(Charsets.UTF_8)
    }

    /** The build in an exit record's summary, or null when it was not written by [encodeStamp]. */
    internal fun decodeStamp(summary: ByteArray?): StampedBuild? {
        if (summary == null || summary.size > MAX_STAMP_BYTES) return null
        val text = summary.toString(Charsets.UTF_8)
        if (!text.startsWith(STAMP_PREFIX)) return null
        val lines = text.removePrefix(STAMP_PREFIX).split('\n', limit = 2)
        if (lines.size != 2) return null
        val code = lines[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val name = lines[1].trim().takeIf(String::isNotEmpty) ?: return null
        return StampedBuild(name, code)
    }

    /** version_name/version_code for one ANR ticket; empty when the exit carries no stamp. */
    internal fun versionExtras(build: StampedBuild?): Map<String, Any> = buildMap {
        build?.let {
            put("version_name", it.versionName)
            put("version_code", it.versionCode)
        }
    }

    /** Record this process's build in its future exit record. Call early in every process. */
    fun stampProcess(context: Context, versionName: String, versionCode: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.setProcessStateSummary(encodeStamp(versionName, versionCode))
        }.onFailure { Log.w(TAG, "Could not stamp process build", it) }
    }

    /** ANR exits to report, oldest first, newest kept when capped (same policy as the SDK). */
    internal fun selectPending(
        exits: List<AnrExit>,
        watermark: Long?,
        now: Long,
        limit: Int = MAX_REPORTS_PER_LAUNCH,
    ): List<AnrExit> {
        val floor = watermark ?: (now - FIRST_RUN_LOOKBACK_MS)
        return exits.asSequence()
            .filter { it.reason == ApplicationExitInfo.REASON_ANR }
            .filter { it.timestamp > floor }
            .distinctBy { it.pid to it.timestamp }
            .sortedBy { it.timestamp }
            .toList()
            .takeLast(limit)
    }

    /** Main-thread Java frames (`at …`) from an ART ANR trace, top first. */
    internal fun mainThreadFrames(trace: String): List<String> {
        val lines = trace.lineSequence().iterator()
        while (lines.hasNext()) {
            if (!lines.next().startsWith("\"main\" ")) continue
            val frames = mutableListOf<String>()
            while (lines.hasNext()) {
                val body = lines.next()
                if (body.isBlank()) break
                val trimmed = body.trim()
                if (trimmed.startsWith("at ")) {
                    frames += trimmed.removePrefix("at ").trim()
                    if (frames.size >= MAX_MAIN_FRAMES) break
                }
            }
            return frames
        }
        return emptyList()
    }

    private val FRAMEWORK_PREFIXES = listOf(
        "android.", "androidx.", "com.android.", "java.", "javax.", "kotlin.",
        "kotlinx.", "dalvik.", "libcore.", "sun.", "jdk.", "com.google.android.",
        "org.json.", "okhttp3.", "okio.",
    )

    internal fun groupingFrame(frames: List<String>): String =
        frames.firstOrNull { frame -> FRAMEWORK_PREFIXES.none { frame.startsWith(it) } }
            ?: frames.firstOrNull()
            ?: ""

    internal fun message(description: String?, frame: String): String = buildString {
        append("ANR")
        description?.trim()?.takeIf(String::isNotEmpty)?.let { append(": ").append(it.take(200)) }
        if (frame.isNotEmpty()) append("\nat ").append(frame)
    }

    internal fun isPermanentRejection(error: Throwable): Boolean =
        error is HandsFeedbackException && error.code in 400..499 && error.code != 408 && error.code != 429

    private val uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Report pending ANR exits in the background, once per process start. */
    fun start(context: Context, baseUrl: String, appSlug: String, channel: String?, clientKey: String?) {
        val appContext = context.applicationContext
        uploadScope.launch {
            runCatching { uploadPending(appContext, baseUrl, appSlug, channel, clientKey) }
                .onFailure { Log.w(TAG, "ANR upload pass failed", it) }
        }
    }

    /** Report pending ANR exits; call off the main thread. */
    suspend fun uploadPending(
        context: Context,
        baseUrl: String,
        appSlug: String,
        channel: String?,
        clientKey: String?,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val infos = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS_QUERIED)
        }.getOrElse {
            Log.w(TAG, "Exit history unavailable", it)
            return
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sdkPrefs = context.getSharedPreferences(SDK_PREFS_NAME, Context.MODE_PRIVATE)
        val watermark = when {
            prefs.contains(KEY_WATERMARK) -> prefs.getLong(KEY_WATERMARK, 0L)
            sdkPrefs.contains(SDK_KEY_WATERMARK) -> sdkPrefs.getLong(SDK_KEY_WATERMARK, 0L)
            else -> null
        }
        val now = System.currentTimeMillis()
        val byKey = infos.associateBy { it.pid to it.timestamp }
        val pending = selectPending(infos.map { AnrExit(it.pid, it.timestamp, it.reason) }, watermark, now)
        if (pending.isEmpty()) {
            if (watermark == null) advanceWatermark(prefs, sdkPrefs, now - FIRST_RUN_LOOKBACK_MS)
            return
        }
        // The ticket build comes from each exit's stamp (extras override the SDK's
        // metadata), never from this install.
        val feedback = HandsFeedback(
            context = context,
            baseUrl = baseUrl,
            appSlug = appSlug,
            versionName = null,
            versionCode = null,
            channel = channel,
            clientKey = clientKey,
        )
        val dir = File(context.cacheDir, "raft-anr").apply { mkdirs() }
        for (exit in pending) {
            val info = byKey[exit.pid to exit.timestamp] ?: continue
            val traceFile = File(dir, "anr-${exit.timestamp}-${exit.pid}.txt")
            val traceText = persistTrace(info, traceFile)
            val frame = groupingFrame(traceText?.let(::mainThreadFrames).orEmpty())
            val description = info.description?.take(8_192)?.takeIf(String::isNotBlank)
            val result = runCatching {
                feedback.submit(
                    message = message(description, frame),
                    kind = "crash",
                    attachments = listOfNotNull(traceFile.takeIf { traceText != null }),
                    extras = buildMap {
                        putAll(versionExtras(decodeStamp(info.processStateSummary)))
                        put("crash_type", "anr")
                        put("crash_reason", "anr")
                        put("crash_exception_class", "ANR")
                        put("crash_top_frame", frame)
                        put("crash_thread", "main")
                        put("crash_at", exit.timestamp)
                        put("crash_process_id", exit.pid)
                        put("crash_trace_attached", traceText != null)
                        put("crash_exit_reason", "anr")
                        put("crash_exit_status", info.status)
                        put("crash_exit_importance", info.importance)
                        put("crash_exit_pss", info.pss)
                        put("crash_exit_rss", info.rss)
                        put("crash_exit_timestamp", exit.timestamp)
                        description?.let { put("crash_exit_description", it) }
                    },
                )
            }
            traceFile.delete()
            val error = result.exceptionOrNull()
            if (error != null && !isPermanentRejection(error)) {
                Log.w(TAG, "ANR upload failed; will retry next launch", error)
                return
            }
            if (error != null) Log.w(TAG, "ANR ${exit.pid}@${exit.timestamp} rejected; skipping", error)
            advanceWatermark(prefs, sdkPrefs, exit.timestamp)
        }
    }

    /**
     * Moves our watermark and keeps the SDK's in step (never backwards), so turning the SDK's
     * own ANR pass back on (this reporter removed, or a rollback) does not re-send exits
     * already filed here.
     */
    private fun advanceWatermark(
        prefs: android.content.SharedPreferences,
        sdkPrefs: android.content.SharedPreferences,
        timestamp: Long,
    ) {
        prefs.edit().putLong(KEY_WATERMARK, timestamp).apply()
        val sdk = sdkPrefs.getLong(SDK_KEY_WATERMARK, Long.MIN_VALUE)
        if (timestamp > sdk) sdkPrefs.edit().putLong(SDK_KEY_WATERMARK, timestamp).apply()
    }

    private fun persistTrace(info: ApplicationExitInfo, destination: File): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val stream = info.traceInputStream ?: return null
            val bytes = stream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0L
                while (total < MAX_TRACE_BYTES) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), MAX_TRACE_BYTES - total).toInt())
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    total += read
                }
                out.toByteArray()
            }
            if (bytes.isEmpty()) return null
            destination.writeBytes(bytes)
            bytes.toString(Charsets.UTF_8)
        }.getOrElse {
            destination.delete()
            null
        }
    }
}
