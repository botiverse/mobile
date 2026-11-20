package build.raft.app.core.data.diagnostics

import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream

/**
 * How far a process got through startup, kept on disk so the next launch can say it for an exit
 * the system recorded for that process (#wg-mobile-feedback:#250: foreground SIGKILLs with no trace,
 * where "killed while starting" and "killed while in use" could not be told apart).
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

    /** From `Application.attachBaseContext`; [filesDir] is the base context's. Never throws. */
    fun start(filesDir: File?, processName: String) {
        // Exit records (ApplicationExitInfo) and their reporter exist from API 30 only.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val target = File(filesDir ?: return, DirName)
            dir = target
            val nowWall = System.currentTimeMillis()
            val startWall =
                nowWall - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
            record =
                StartupPhaseRecord(
                    pid = Process.myPid(),
                    processStartMillis = startWall,
                    processName = processName,
                    phase = Phase.AttachBaseContext,
                    phaseAtMillis = nowWall,
                )
            write()
        }
    }

    /** Records [phase] when it is further than the one already written. Never throws. */
    fun reached(phase: Phase) {
        runCatching {
            val current = record ?: return
            if (phase.ordinal <= current.phase.ordinal) return
            record = current.copy(phase = phase, phaseAtMillis = System.currentTimeMillis())
            write()
        }
    }

    @Synchronized
    private fun write() {
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
) {
    /** `1|pid|start|phase|phaseAt|processName` (process name last: it may contain anything but a newline). */
    fun encode(): String = "1|$pid|$processStartMillis|${phase.label}|$phaseAtMillis|${processName.replace('\n', ' ')}"

    companion object {
        fun decode(text: String): StartupPhaseRecord? {
            val parts = text.trim().split('|', limit = 6)
            if (parts.size != 6 || parts[0] != "1") return null
            return StartupPhaseRecord(
                pid = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null,
                processStartMillis = parts[2].toLongOrNull() ?: return null,
                phase = RaftStartupPhaseMarker.Phase.fromLabel(parts[3]) ?: return null,
                phaseAtMillis = parts[4].toLongOrNull() ?: return null,
                processName = parts[5],
            )
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
