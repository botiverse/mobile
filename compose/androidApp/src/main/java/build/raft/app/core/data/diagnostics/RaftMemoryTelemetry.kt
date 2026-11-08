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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The last memory state of a process, as persisted for the next launch to read.
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
    val pssKb: Long = -1L,
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
            pssKb,
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
            "sampleAgeMs=${if (sampledAtMillis >= 0L) exitTimestampMillis - sampledAtMillis else -1L} " +
            "lastPssKb=$pssKb " +
            "heapUsedKb=$javaHeapUsedKb " +
            "heapMaxKb=$javaHeapMaxKb " +
            "nativeHeapKb=$nativeHeapKb " +
            "systemAvailKb=$systemAvailKb " +
            "systemThresholdKb=$systemThresholdKb " +
            "systemLowMemory=$systemLowMemory " +
            "trimLevel=$lastTrimLevel " +
            "trim=${trimMemoryLevelLabel(lastTrimLevel)} " +
            "maxTrimLevel=$maxTrimLevel " +
            "trimAgeMs=${if (lastTrimAtMillis >= 0L) exitTimestampMillis - lastTrimAtMillis else -1L}"

    companion object {
        private const val EncodingVersion = 1
        private const val FieldCount = 17

        fun decode(encoded: String?): MemoryTelemetrySnapshot? {
            val parts = encoded?.split(',') ?: return null
            if (parts.size != FieldCount || parts[0].toIntOrNull() != EncodingVersion) return null
            val numbers = parts.map { it.toLongOrNull() ?: return null }
            return MemoryTelemetrySnapshot(
                pid = numbers[1].toInt(),
                processStartMillis = numbers[2],
                writtenAtMillis = numbers[3],
                foreground = numbers[4] == 1L,
                foregroundChangedAtMillis = numbers[5],
                sampledAtMillis = numbers[6],
                pssKb = numbers[7],
                javaHeapUsedKb = numbers[8],
                javaHeapMaxKb = numbers[9],
                nativeHeapKb = numbers[10],
                systemAvailKb = numbers[11],
                systemThresholdKb = numbers[12],
                systemLowMemory = numbers[13] == 1L,
                maxTrimLevel = numbers[14].toInt(),
                lastTrimLevel = numbers[15].toInt(),
                lastTrimAtMillis = numbers[16],
            )
        }
    }
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

/**
 * Cheap foreground memory sampling that survives a kill.
 *
 * While an activity is started, every [SampleIntervalMillis] a background thread reads
 * `Debug.getPss()` (one /proc read), the Java and native heap, and `ActivityManager.MemoryInfo`,
 * and commits one short SharedPreferences string. `onTrimMemory` levels and foreground changes are
 * written too (see [MemoryTelemetryWriteGate]). Nothing is sampled in the background.
 *
 * [install] first takes the previous process's snapshot, which `previous_process_exit` and the
 * system-kill report read through [previousProcessSnapshot].
 */
internal object RaftMemoryTelemetry {
    private const val PreferencesName = "raft-memory-telemetry"
    private const val SnapshotKey = "last-snapshot"
    internal const val SampleIntervalMillis = 30_000L

    private val installed = AtomicBoolean(false)
    @Volatile private var previous: MemoryTelemetrySnapshot? = null
    @Volatile private var current: MemoryTelemetrySnapshot? = null
    // Half the sample interval: a sample posted a few ms early still writes; trims stay rate limited.
    private val writeGate = MemoryTelemetryWriteGate(minPeriodicIntervalMillis = SampleIntervalMillis / 2)
    private var startedActivities = 0
    private lateinit var handler: Handler
    private lateinit var preferences: SharedPreferences
    private lateinit var activityManager: ActivityManager

    fun previousProcessSnapshot(): MemoryTelemetrySnapshot? = previous

    /** Returns the previous process's snapshot. Call once, from `Application.onCreate`. */
    fun install(application: Application): MemoryTelemetrySnapshot? {
        if (!installed.compareAndSet(false, true)) return previous
        runCatching {
            preferences = application.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            previous = MemoryTelemetrySnapshot.decode(preferences.getString(SnapshotKey, null))
            activityManager = application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val now = System.currentTimeMillis()
            current =
                MemoryTelemetrySnapshot(
                    pid = Process.myPid(),
                    processStartMillis = now - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()),
                    writtenAtMillis = now,
                    foreground = false,
                    foregroundChangedAtMillis = now,
                )
            handler = Handler(HandlerThread("raft-memory-telemetry").apply { start() }.looper)
            handler.post { persist(MemoryTelemetryWriteGate.Cause.ForegroundChange) }
            application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
            application.registerComponentCallbacks(trimCallbacks)
        }.onFailure { error ->
            RaftAndroidLocalDiagnostics.recordLog(
                level = "warn",
                tag = "SlockMemoryTelemetry",
                message = "memory_telemetry_install_failed class=${error.javaClass.name}",
            )
        }
        return previous
    }

    private val sampler =
        object : Runnable {
            override fun run() {
                sample()
                persist(MemoryTelemetryWriteGate.Cause.Periodic)
                handler.postDelayed(this, SampleIntervalMillis)
            }
        }

    private fun onForegroundChanged(foreground: Boolean) {
        handler.post {
            val now = System.currentTimeMillis()
            current = current?.copy(foreground = foreground, foregroundChangedAtMillis = now)
            handler.removeCallbacks(sampler)
            if (foreground) {
                sample()
                handler.postDelayed(sampler, SampleIntervalMillis)
            }
            persist(MemoryTelemetryWriteGate.Cause.ForegroundChange)
        }
    }

    private fun onTrim(level: Int) {
        handler.post {
            val now = System.currentTimeMillis()
            current =
                current?.copy(
                    lastTrimLevel = level,
                    lastTrimAtMillis = now,
                    maxTrimLevel = maxOf(current?.maxTrimLevel ?: 0, level),
                )
            // Sample on a running-* trim too: that is the system warning a foreground app.
            if (current?.foreground == true) sample()
            persist(MemoryTelemetryWriteGate.Cause.Trim, level)
        }
    }

    /** Runs on [handler]. */
    private fun sample() {
        val snapshot = current ?: return
        runCatching {
            val runtime = Runtime.getRuntime()
            val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
            current =
                snapshot.copy(
                    sampledAtMillis = System.currentTimeMillis(),
                    pssKb = Debug.getPss(),
                    javaHeapUsedKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L,
                    javaHeapMaxKb = runtime.maxMemory() / 1024L,
                    nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024L,
                    systemAvailKb = memoryInfo.availMem / 1024L,
                    systemThresholdKb = memoryInfo.threshold / 1024L,
                    systemLowMemory = memoryInfo.lowMemory,
                )
        }
    }

    /** Runs on [handler]; `commit` so the value is on disk before a kill can take the process. */
    private fun persist(cause: MemoryTelemetryWriteGate.Cause, trimLevel: Int = 0) {
        val now = System.currentTimeMillis()
        if (!writeGate.shouldWrite(cause, now, trimLevel)) return
        val snapshot = current?.copy(writtenAtMillis = now) ?: return
        current = snapshot
        runCatching { preferences.edit().putString(SnapshotKey, snapshot.encode()).commit() }
    }

    private val lifecycleCallbacks =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities += 1
                if (startedActivities == 1) onForegroundChanged(true)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) onForegroundChanged(false)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

            override fun onActivityResumed(activity: Activity) {}

            override fun onActivityPaused(activity: Activity) {}

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {}
        }

    private val trimCallbacks =
        object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = onTrim(level)

            override fun onConfigurationChanged(newConfig: Configuration) {}

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = onTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        }
}
