package build.raft.app.core.data.diagnostics

import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * How far a process got through startup, kept on disk so the next launch can say it for an exit
 * the system recorded for that process (#wg-mobile-feedback:#250: foreground SIGKILLs with no trace,
 * where "killed while starting" and "killed while in use" could not be told apart), and so subsequent
 * launches can distinguish system restarts (sticky service / background wake) from user launches and
 * compute outage duration until UI recovery (#proj-mobile:#220).
 *
 * Phases, in order: [Phase.AttachBaseContext] → [Phase.ApplicationCreated] (end of
 * `Application.onCreate`) → [Phase.ActivityCreated] (first Activity `onCreate`) → [Phase.FirstFrame]
 * (the first window drew its page). Each phase is written synchronously on the calling thread: a
 * kill a few seconds after start must still leave it. The write is `write()` + `rename()` of one
 * line per process: it reaches the kernel before the call returns, so a SIGKILL of the process
 * cannot lose it (only a power loss could, which is not what this is for). No `fsync`, so a phase
 * costs one small file write, not a flash flush.
 *
 * One file per process (`<dir>/<pid>`), written only by that process, so processes of the app
 * never race on a shared file. [prune] keeps the newest [MaxRecords] by process start.
 */
internal object RaftStartupPhaseMarker {
    const val MaxRecords = 8
    const val DirName = "raft-startup-phases"

    enum class Phase(val label: String) {
        AttachBaseContext("attach_base_context"),
        ApplicationCreated("application_created"),
        ActivityCreated("activity_created"),
        FirstFrame("first_frame"),
        ;

        companion object {
            fun fromLabel(label: String): Phase? = entries.firstOrNull { it.label == label }
        }
    }

    @Volatile private var dir: File? = null
    @Volatile private var record: StartupPhaseRecord? = null

    private val asyncWriter by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "RaftStartupPhaseWriter").apply { isDaemon = true }
        }
    }

    /** The current process's in-memory record, or null before [start] (or below API 30). */
    fun current(): StartupPhaseRecord? = record

    /** From `Application.attachBaseContext`; [filesDir] is the base context's. Never throws. */
    fun start(filesDir: File?, processName: String) {
        // Exit records (ApplicationExitInfo) and their reporter exist from API 30 only.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val target = File(filesDir ?: return, DirName)
            val nowWall = System.currentTimeMillis()
            val startWall =
                nowWall - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
            startInternal(
                targetDir = target,
                pid = Process.myPid(),
                processStartMillis = startWall,
                processName = processName,
                nowMillis = nowWall,
            )
        }
    }

    @Synchronized
    internal fun startForTest(
        filesDir: File,
        pid: Int,
        processStartMillis: Long,
        processName: String,
        nowMillis: Long,
    ) {
        startInternal(
            targetDir = File(filesDir, DirName),
            pid = pid,
            processStartMillis = processStartMillis,
            processName = processName,
            nowMillis = nowMillis,
        )
    }

    @Synchronized
    internal fun resetForTest() {
        dir = null
        record = null
    }

    internal fun flushPendingWritesForTest() {
        runCatching { asyncWriter.submit {}.get(5, TimeUnit.SECONDS) }
    }

    @Synchronized
    private fun startInternal(
        targetDir: File,
        pid: Int,
        processStartMillis: Long,
        processName: String,
        nowMillis: Long,
    ) {
        dir = targetDir
        record =
            StartupPhaseRecord(
                pid = pid,
                processStartMillis = processStartMillis,
                processName = processName,
                phase = Phase.AttachBaseContext,
                phaseAtMillis = nowMillis,
            )
        writeLocked()
    }

    /** Records [phase] when it is further than the one already written. Never throws. */
    fun reached(
        phase: Phase,
        nowMillis: Long = System.currentTimeMillis(),
        elapsedSinceProcessStartMillis: Long = defaultElapsedSinceProcessStartMillis(phase),
    ) {
        runCatching {
            updateLocked { current -> current.advanceTo(phase, nowMillis, elapsedSinceProcessStartMillis) }
        }
    }

    private fun defaultElapsedSinceProcessStartMillis(phase: Phase): Long {
        if (phase != Phase.ActivityCreated) return -1L
        return runCatching {
            (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()).coerceAtLeast(0L)
        }.getOrDefault(-1L)
    }

    /**
     * Marks this process as having entered `RaftBackgroundConnectionService`'s sticky-restart
     * stop path (`intent == null`). Updates the in-memory record synchronously and persists the
     * latest record on a single-thread background writer so `Service.onStartCommand` performs zero
     * main-thread disk I/O. Never throws.
     */
    fun markStickyServiceRestart() {
        runCatching {
            val changed = markStickyInMemoryLocked()
            if (changed) {
                asyncWriter.execute { runCatching { writeCurrentSynchronized() } }
            }
        }
    }

    @Synchronized
    private fun markStickyInMemoryLocked(): Boolean {
        val current = record ?: return false
        if (current.stickyServiceRestart) return false
        record = current.copy(stickyServiceRestart = true)
        return true
    }

    @Synchronized
    private fun writeCurrentSynchronized() {
        writeLocked()
    }

    @Synchronized
    private fun updateLocked(transform: (StartupPhaseRecord) -> StartupPhaseRecord?) {
        val current = record ?: return
        val next = transform(current) ?: return
        record = next
        writeLocked()
    }

    private fun writeLocked() {
        val target = dir ?: return
        val current = record ?: return
        if (!target.isDirectory && !target.mkdirs()) return
        val tmp = File(target, "${current.pid}.tmp")
        FileOutputStream(tmp).use { it.write(current.encode().toByteArray(Charsets.UTF_8)) }
        tmp.renameTo(File(target, current.pid.toString()))
    }

    /** All records on disk (unreadable ones skipped). Off the main thread. */
    fun readAll(filesDir: File): List<StartupPhaseRecord> {
        val target = File(filesDir, DirName)
        val files = target.listFiles { file -> file.isFile && !file.name.endsWith(".tmp") } ?: return emptyList()
        return files.mapNotNull { file -> runCatching { StartupPhaseRecord.decode(file.readText()) }.getOrNull() }
    }

    /** Deletes all but the newest [keep] records (by process start). Off the main thread. */
    fun prune(filesDir: File, keep: Int = MaxRecords, selfPid: Int = Process.myPid()) {
        val target = File(filesDir, DirName)
        val files = target.listFiles() ?: return
        files.filter { it.name.endsWith(".tmp") && it.name.removeSuffix(".tmp") != selfPid.toString() }
            .forEach { it.delete() }
        val records = files.filter { !it.name.endsWith(".tmp") }
            .map { it to runCatching { StartupPhaseRecord.decode(it.readText()) }.getOrNull() }
        val keepNames =
            newestStartupRecords(records.mapNotNull { it.second }, keep)
                .map { it.pid.toString() }
                .toSet() + selfPid.toString()
        records.filter { (file, _) -> file.name !in keepNames }.forEach { (file, _) -> file.delete() }
    }
}

/** One process's startup progress. Wall-clock millis (comparable with exit timestamps). */
internal data class StartupPhaseRecord(
    val pid: Int,
    val processStartMillis: Long,
    val processName: String,
    val phase: RaftStartupPhaseMarker.Phase,
    val phaseAtMillis: Long,
    /** Wall-clock time of the first [RaftStartupPhaseMarker.Phase.ActivityCreated] in this process; -1 when none. */
    val firstActivityAtMillis: Long = -1L,
    /** Monotonic elapsed millis from process start to first [RaftStartupPhaseMarker.Phase.ActivityCreated]; -1 when unknown. */
    val firstActivityAfterStartMs: Long = -1L,
    /** True when this process hit the sticky-service restart stop path (`intent == null`). */
    val stickyServiceRestart: Boolean = false,
) {
    /**
     * Advances to [nextPhase] when it is strictly further than [phase]; returns null otherwise.
     * [firstActivityAtMillis] and [firstActivityAfterStartMs] are captured once on the first
     * [RaftStartupPhaseMarker.Phase.ActivityCreated] and never overwritten by later phases
     * (including [RaftStartupPhaseMarker.Phase.FirstFrame]).
     */
    fun advanceTo(
        nextPhase: RaftStartupPhaseMarker.Phase,
        nowMillis: Long,
        elapsedSinceProcessStartMillis: Long = -1L,
    ): StartupPhaseRecord? {
        if (nextPhase.ordinal <= phase.ordinal) return null
        val capturingFirstActivity =
            nextPhase == RaftStartupPhaseMarker.Phase.ActivityCreated && firstActivityAtMillis < 0L
        val activityAt = if (capturingFirstActivity) nowMillis else firstActivityAtMillis
        val activityOffset =
            if (capturingFirstActivity && firstActivityAfterStartMs < 0L && elapsedSinceProcessStartMillis >= 0L) {
                elapsedSinceProcessStartMillis
            } else {
                firstActivityAfterStartMs
            }
        return copy(
            phase = nextPhase,
            phaseAtMillis = nowMillis,
            firstActivityAtMillis = activityAt,
            firstActivityAfterStartMs = activityOffset,
        )
    }

    /**
     * `3|pid|start|phase|phaseAt|firstActivityAt|firstActivityAfterStartMs|sticky|processName`
     * (process name last: it may contain anything but a newline, including `|`).
     */
    fun encode(): String =
        "3|$pid|$processStartMillis|${phase.label}|$phaseAtMillis|$firstActivityAtMillis|" +
            "$firstActivityAfterStartMs|${if (stickyServiceRestart) 1 else 0}|${processName.replace('\n', ' ')}"

    companion object {
        fun decode(text: String): StartupPhaseRecord? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val version = trimmed.substringBefore('|', "")
            return when (version) {
                "1" -> {
                    val parts = trimmed.split('|', limit = 6)
                    if (parts.size != 6) return null
                    StartupPhaseRecord(
                        pid = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null,
                        processStartMillis = parts[2].toLongOrNull() ?: return null,
                        phase = RaftStartupPhaseMarker.Phase.fromLabel(parts[3]) ?: return null,
                        phaseAtMillis = parts[4].toLongOrNull() ?: return null,
                        processName = parts[5],
                    )
                }
                "2" -> {
                    val parts = trimmed.split('|', limit = 8)
                    if (parts.size != 8) return null
                    val sticky =
                        when (parts[6]) {
                            "0" -> false
                            "1" -> true
                            else -> return null
                        }
                    StartupPhaseRecord(
                        pid = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null,
                        processStartMillis = parts[2].toLongOrNull() ?: return null,
                        phase = RaftStartupPhaseMarker.Phase.fromLabel(parts[3]) ?: return null,
                        phaseAtMillis = parts[4].toLongOrNull() ?: return null,
                        firstActivityAtMillis = parts[5].toLongOrNull()?.takeIf { it >= -1L } ?: return null,
                        stickyServiceRestart = sticky,
                        processName = parts[7],
                    )
                }
                "3" -> {
                    val parts = trimmed.split('|', limit = 9)
                    if (parts.size != 9) return null
                    val sticky =
                        when (parts[7]) {
                            "0" -> false
                            "1" -> true
                            else -> return null
                        }
                    StartupPhaseRecord(
                        pid = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null,
                        processStartMillis = parts[2].toLongOrNull() ?: return null,
                        phase = RaftStartupPhaseMarker.Phase.fromLabel(parts[3]) ?: return null,
                        phaseAtMillis = parts[4].toLongOrNull() ?: return null,
                        firstActivityAtMillis = parts[5].toLongOrNull()?.takeIf { it >= -1L } ?: return null,
                        firstActivityAfterStartMs = parts[6].toLongOrNull()?.takeIf { it >= -1L } ?: return null,
                        stickyServiceRestart = sticky,
                        processName = parts[8],
                    )
                }
                else -> null
            }
        }
    }
}

/** The newest [keep] records by process start (ties: higher pid first). */
internal fun newestStartupRecords(records: List<StartupPhaseRecord>, keep: Int): List<StartupPhaseRecord> =
    records.sortedWith(compareByDescending<StartupPhaseRecord> { it.processStartMillis }.thenByDescending { it.pid })
        .take(keep.coerceAtLeast(0))

/**
 * The record of the process an exit belongs to: same pid, started at or before the exit, and the
 * newest such one (a pid can be reused by a later process). Null when none matches.
 */
internal fun startupRecordForExit(
    records: List<StartupPhaseRecord>,
    pid: Int,
    exitTimestampMillis: Long,
): StartupPhaseRecord? =
    records.filter { it.pid == pid && it.processStartMillis <= exitTimestampMillis }
        .maxByOrNull { it.processStartMillis }
