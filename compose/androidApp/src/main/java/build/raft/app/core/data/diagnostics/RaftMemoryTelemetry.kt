package build.raft.app.core.data.diagnostics

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

/**
 * The last memory state of a process, as persisted for a later launch to read.
 *
 * A system kill (Hands fb-52cc0499: `reason=other` on a Huawei while the user scrolled) leaves only
 * an [android.app.ApplicationExitInfo] with `pss=0 rss=0` behind. This is what the app saw last.
 */
internal data class MemoryTelemetrySnapshot(
    val pid: Int,
    val processStartMillis: Long,
    val writtenAtMillis: Long,
    val foreground: Boolean,
    val foregroundChangedAtMillis: Long,
    /** -1 until the first sample. */
    val sampledAtMillis: Long = -1L,
    /** Resident set from /proc/self/statm, every sample (cheap). */
    val rssKb: Long = -1L,
    /** `Debug.getPss()`, only every [RaftMemoryTelemetry.PssIntervalMillis] and on trims (slow). */
    val pssKb: Long = -1L,
    val pssSampledAtMillis: Long = -1L,
    val javaHeapUsedKb: Long = -1L,
    val javaHeapMaxKb: Long = -1L,
    val nativeHeapKb: Long = -1L,
    val systemAvailKb: Long = -1L,
    val systemThresholdKb: Long = -1L,
    val systemLowMemory: Boolean = false,
    /** Highest `onTrimMemory` level since the process started; 0 when none. */
    val maxTrimLevel: Int = 0,
    /** The most recent `onTrimMemory` level; 0 when none. */
    val lastTrimLevel: Int = 0,
    val lastTrimAtMillis: Long = -1L,
) {
    fun encode(): String =
        listOf(
            EncodingVersion,
            pid,
            processStartMillis,
            writtenAtMillis,
            if (foreground) 1 else 0,
            foregroundChangedAtMillis,
            sampledAtMillis,
            rssKb,
            pssKb,
            pssSampledAtMillis,
            javaHeapUsedKb,
            javaHeapMaxKb,
            nativeHeapKb,
            systemAvailKb,
            systemThresholdKb,
            if (systemLowMemory) 1 else 0,
            maxTrimLevel,
            lastTrimLevel,
            lastTrimAtMillis,
        ).joinToString(",")

    /** `key=value` fields appended to `previous_process_exit`, relative to the exit time. */
    fun formatForExit(exitTimestampMillis: Long): String =
        "sinceLaunchMs=${exitTimestampMillis - processStartMillis} " +
            "foreground=$foreground " +
            "foregroundChangeAgeMs=${exitTimestampMillis - foregroundChangedAtMillis} " +
            "sampleAgeMs=${ageAt(exitTimestampMillis, sampledAtMillis)} " +
            "lastRssKb=$rssKb " +
            "lastPssKb=$pssKb " +
            "pssAgeMs=${ageAt(exitTimestampMillis, pssSampledAtMillis)} " +
            "heapUsedKb=$javaHeapUsedKb " +
            "heapMaxKb=$javaHeapMaxKb " +
            "nativeHeapKb=$nativeHeapKb " +
            "systemAvailKb=$systemAvailKb " +
            "systemThresholdKb=$systemThresholdKb " +
            "systemLowMemory=$systemLowMemory " +
            "trimLevel=$lastTrimLevel " +
            "trim=${trimMemoryLevelLabel(lastTrimLevel)} " +
            "maxTrimLevel=$maxTrimLevel " +
            "trimAgeMs=${ageAt(exitTimestampMillis, lastTrimAtMillis)}"

    companion object {
        private const val EncodingVersion = 2
        private const val FieldCount = 19

        internal fun ageAt(nowMillis: Long, atMillis: Long): Long = if (atMillis >= 0L) nowMillis - atMillis else -1L

        fun decode(encoded: String?): MemoryTelemetrySnapshot? {
            val parts = encoded?.split(',') ?: return null
            if (parts.size != FieldCount || parts[0].toIntOrNull() != EncodingVersion) return null
            val n = parts.map { it.toLongOrNull() ?: return null }
            return MemoryTelemetrySnapshot(
                pid = n[1].toInt(),
                processStartMillis = n[2],
                writtenAtMillis = n[3],
                foreground = n[4] == 1L,
                foregroundChangedAtMillis = n[5],
                sampledAtMillis = n[6],
                rssKb = n[7],
                pssKb = n[8],
                pssSampledAtMillis = n[9],
                javaHeapUsedKb = n[10],
                javaHeapMaxKb = n[11],
                nativeHeapKb = n[12],
                systemAvailKb = n[13],
                systemThresholdKb = n[14],
                systemLowMemory = n[15] == 1L,
                maxTrimLevel = n[16].toInt(),
                lastTrimLevel = n[17].toInt(),
                lastTrimAtMillis = n[18],
            )
        }
    }
}

/**
 * The persisted snapshots of the last few processes, one per process start.
 *
 * One slot was not enough: this launch overwrote it at once, so a kill report whose upload failed
 * lost its telemetry on retry, and a sticky-service restart between the kill and the user's next
 * launch overwrote it before anyone read it.
 */
internal object MemoryTelemetryRing {
    const val Capacity = 6

    fun decode(encoded: String?): List<MemoryTelemetrySnapshot> =
        encoded.orEmpty().split(';').mapNotNull(MemoryTelemetrySnapshot::decode)

    fun encode(snapshots: List<MemoryTelemetrySnapshot>): String = snapshots.joinToString(";") { it.encode() }

    /** [others] plus [current], replacing the entry of the same process, newest [Capacity] kept. */
    fun merge(others: List<MemoryTelemetrySnapshot>, current: MemoryTelemetrySnapshot): List<MemoryTelemetrySnapshot> =
        (others.filterNot { it.isSameProcess(current) } + current)
            .sortedBy { it.processStartMillis }
            .takeLast(Capacity)

    /**
     * The snapshot of the process that exited: same pid, started no later than the exit, and the
     * latest such start (a reused pid belongs to the newer process).
     */
    fun match(snapshots: List<MemoryTelemetrySnapshot>, pid: Int, exitTimestampMillis: Long): MemoryTelemetrySnapshot? =
        snapshots
            .filter { it.pid == pid && it.processStartMillis <= exitTimestampMillis }
            .maxByOrNull { it.processStartMillis }

    private fun MemoryTelemetrySnapshot.isSameProcess(other: MemoryTelemetrySnapshot): Boolean =
        pid == other.pid && processStartMillis == other.processStartMillis
}

@Suppress("DEPRECATION")
internal fun trimMemoryLevelLabel(level: Int): String =
    when (level) {
        0 -> "none"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "running_moderate"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "running_low"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "running_critical"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "ui_hidden"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "background"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "moderate"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "complete"
        else -> "level_$level"
    }

/**
 * When a telemetry change is worth a disk write. Periodic samples are written at most every
 * [minPeriodicIntervalMillis]; a foreground change, a first sample or a higher trim level than
 * already written are written at once, so the state at a kill is never more than one interval old.
 */
internal class MemoryTelemetryWriteGate(
    private val minPeriodicIntervalMillis: Long,
) {
    private var lastWriteMillis = Long.MIN_VALUE
    private var lastWrittenTrimLevel = 0

    enum class Cause { Periodic, ForegroundChange, Trim }

    @Synchronized
    fun shouldWrite(cause: Cause, nowMillis: Long, trimLevel: Int = 0): Boolean {
        val due =
            when (cause) {
                Cause.ForegroundChange -> true
                Cause.Trim -> trimLevel > lastWrittenTrimLevel ||
                    lastWriteMillis == Long.MIN_VALUE ||
                    nowMillis - lastWriteMillis >= minPeriodicIntervalMillis
                Cause.Periodic -> lastWriteMillis == Long.MIN_VALUE ||
                    nowMillis - lastWriteMillis >= minPeriodicIntervalMillis
            }
        if (due) {
            lastWriteMillis = nowMillis
            if (cause == Cause.Trim) lastWrittenTrimLevel = maxOf(lastWrittenTrimLevel, trimLevel)
        }
        return due
    }
}

/** Whether a sample should also read PSS (slow on kernels without smaps_rollup). */
internal fun shouldSamplePss(nowMillis: Long, lastPssMillis: Long, intervalMillis: Long, trim: Boolean): Boolean =
    trim || lastPssMillis < 0L || nowMillis - lastPssMillis >= intervalMillis

/**
 * Cheap foreground memory sampling that survives a kill.
 *
 * While an activity is started, every [SampleIntervalMillis] a background thread reads RSS from
 * /proc/self/statm, the Java and native heap and `ActivityManager.MemoryInfo`; `Debug.getPss()`
 * only every [PssIntervalMillis] and on a trim. Each change is committed as part of a small ring
 * of per-process snapshots ([MemoryTelemetryRing]). `onTrimMemory` levels and foreground changes
 * are written too (see [MemoryTelemetryWriteGate]); the move to background waits
 * [BackgroundDebounceMillis] so an activity restart (rotation: 1 -> 0 -> 1 started) is not one.
 * Nothing is sampled in the background.
 *
 * [install] reads the persisted ring on the telemetry thread; [previousSnapshots] completes with
 * the snapshots of earlier processes.
 */
internal object RaftMemoryTelemetry {
    private const val PreferencesName = "raft-memory-telemetry"
    private const val RingKey = "snapshot-ring"
    internal const val SampleIntervalMillis = 30_000L
    internal const val PssIntervalMillis = 5 * 60_000L
    internal const val BackgroundDebounceMillis = 2_000L

    private val installed = AtomicBoolean(false)
    val previousSnapshots = CompletableDeferred<List<MemoryTelemetrySnapshot>>()

    // Telemetry-thread state.
    private var others: List<MemoryTelemetrySnapshot> = emptyList()
    private var current: MemoryTelemetrySnapshot? = null
    // Half the sample interval: a sample posted a few ms early still writes; trims stay rate limited.
    private val writeGate = MemoryTelemetryWriteGate(minPeriodicIntervalMillis = SampleIntervalMillis / 2)
    private lateinit var preferences: SharedPreferences
    private lateinit var activityManager: ActivityManager

    // Main-thread state.
    private var startedActivities = 0
    private lateinit var handler: Handler

    /**
     * Starts telemetry. [onPreviousLoaded] runs on the telemetry thread with the earlier
     * processes' snapshots, before this process writes its own.
     */
    fun install(application: Application, onPreviousLoaded: (List<MemoryTelemetrySnapshot>) -> Unit) {
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            val processStartMillis =
                System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
            handler = Handler(HandlerThread("raft-memory-telemetry").apply { start() }.looper)
            handler.post {
                val previous =
                    runCatching {
                        preferences = application.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
                        activityManager = application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        MemoryTelemetryRing.decode(preferences.getString(RingKey, null))
                    }.getOrDefault(emptyList())
                others = previous
                previousSnapshots.complete(previous)
                runCatching { onPreviousLoaded(previous) }
                val now = System.currentTimeMillis()
                current =
                    MemoryTelemetrySnapshot(
                        pid = Process.myPid(),
                        processStartMillis = processStartMillis,
                        writtenAtMillis = now,
                        foreground = false,
                        foregroundChangedAtMillis = now,
                    )
                persist(MemoryTelemetryWriteGate.Cause.ForegroundChange)
            }
            application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
            application.registerComponentCallbacks(trimCallbacks)
        }.onFailure { error ->
            previousSnapshots.complete(emptyList())
            RaftAndroidLocalDiagnostics.recordLog(
                level = "warn",
                tag = "SlockMemoryTelemetry",
                message = "memory_telemetry_install_failed class=${error.javaClass.name}",
            )
        }
    }

    private val sampler =
        object : Runnable {
            override fun run() {
                sample(trim = false)
                persist(MemoryTelemetryWriteGate.Cause.Periodic)
                // Per-minute diagnostics summaries whose window has closed reach the ring on the
                // same cadence, even while the log is quiet.
                RaftAndroidLocalDiagnostics.sweepDiagnosticsSummaries()
                handler.postDelayed(this, SampleIntervalMillis)
            }
        }

    private val goBackground = Runnable { onForegroundChanged(false) }

    /** Runs on [handler]. */
    private fun onForegroundChanged(foreground: Boolean) {
        val snapshot = current ?: return
        if (snapshot.foreground == foreground) return
        current = snapshot.copy(foreground = foreground, foregroundChangedAtMillis = System.currentTimeMillis())
        handler.removeCallbacks(sampler)
        if (foreground) {
            sample(trim = false)
            handler.postDelayed(sampler, SampleIntervalMillis)
        }
        persist(MemoryTelemetryWriteGate.Cause.ForegroundChange)
    }

    /** Runs on [handler]. */
    private fun onTrim(level: Int) {
        val snapshot = current ?: return
        current =
            snapshot.copy(
                lastTrimLevel = level,
                lastTrimAtMillis = System.currentTimeMillis(),
                maxTrimLevel = maxOf(snapshot.maxTrimLevel, level),
            )
        // Sample on a running-* trim too: that is the system warning a foreground app.
        if (snapshot.foreground) sample(trim = true)
        persist(MemoryTelemetryWriteGate.Cause.Trim, level)
    }

    /** Runs on [handler]. */
    private fun sample(trim: Boolean) {
        val snapshot = current ?: return
        runCatching {
            val now = System.currentTimeMillis()
            val runtime = Runtime.getRuntime()
            val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
            val readPss = shouldSamplePss(now, snapshot.pssSampledAtMillis, PssIntervalMillis, trim)
            current =
                snapshot.copy(
                    sampledAtMillis = now,
                    rssKb = readStatmRssKb(),
                    pssKb = if (readPss) Debug.getPss() else snapshot.pssKb,
                    pssSampledAtMillis = if (readPss) now else snapshot.pssSampledAtMillis,
                    javaHeapUsedKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L,
                    javaHeapMaxKb = runtime.maxMemory() / 1024L,
                    nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024L,
                    systemAvailKb = memoryInfo.availMem / 1024L,
                    systemThresholdKb = memoryInfo.threshold / 1024L,
                    systemLowMemory = memoryInfo.lowMemory,
                )
        }
    }

    private fun readStatmRssKb(): Long =
        runCatching {
            val residentPages = parseStatmResidentPages(File("/proc/self/statm").readText())
            val pageKb = Os.sysconf(OsConstants._SC_PAGESIZE) / 1024L
            if (residentPages >= 0L) residentPages * pageKb else -1L
        }.getOrDefault(-1L)

    /** Runs on [handler]; `commit` so the value is on disk before a kill can take the process. */
    private fun persist(cause: MemoryTelemetryWriteGate.Cause, trimLevel: Int = 0) {
        val now = System.currentTimeMillis()
        if (!writeGate.shouldWrite(cause, now, trimLevel)) return
        val snapshot = current?.copy(writtenAtMillis = now) ?: return
        current = snapshot
        runCatching {
            preferences.edit().putString(RingKey, MemoryTelemetryRing.encode(MemoryTelemetryRing.merge(others, snapshot))).commit()
        }
    }

    private val lifecycleCallbacks =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities += 1
                if (startedActivities == 1) {
                    // A pending move to background is cancelled: the app never left.
                    handler.removeCallbacks(goBackground)
                    handler.post { onForegroundChanged(true) }
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) handler.postDelayed(goBackground, BackgroundDebounceMillis)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

            override fun onActivityResumed(activity: Activity) {}

            override fun onActivityPaused(activity: Activity) {}

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {}
        }

    private val trimCallbacks =
        object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                handler.post { onTrim(level) }
            }

            override fun onConfigurationChanged(newConfig: Configuration) {}

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                handler.post { onTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE) }
            }
        }
}

/** Second field of /proc/self/statm (resident pages); -1 when unreadable. */
internal fun parseStatmResidentPages(statm: String): Long =
    statm.trim().split(' ').getOrNull(1)?.toLongOrNull() ?: -1L
