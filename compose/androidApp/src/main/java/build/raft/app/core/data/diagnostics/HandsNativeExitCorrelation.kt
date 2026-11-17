package build.raft.app.core.data.diagnostics

import java.io.File

/*
 * Native-crash exits and the Hands SDK's native ticket (task #243).
 *
 * Before: AndroidSystemKillReporter skipped every `crash_native` exit (and every `signaled` exit
 * with a crash signal) as "reported elsewhere", assuming HandsCrash had filed a ticket for it.
 * Nothing checked that it had. Now such an exit is only skipped on evidence that Hands accepted a
 * native ticket for that very exit; otherwise the app files its own `system_kill` ticket.
 *
 * Verified in hands-android-sdk 0.13.1 (classes.jar of the AAR the app builds with, by javap):
 * - `HandsNativeCrash.uploadPending(...)` returns Unit. It calls `HandsFeedback.submit`, which
 *   returns the ticket id (it throws "missing ticket id in response" without one), deletes the
 *   record on success and drops the id. No listener, callback or result reaches the app, and
 *   there is no logger hook. HandsNativeCrash itself never logs; HandsCrash logs only its Java
 *   uploads, to logcat.
 * - Records live in `.qnc` files under `filesDir/quiver/native-crashes`. A text format: first line `QNC1` or
 *   `QNC2`, header lines `pid <n>` and `crash_at <n>`. A record is also deleted when it does not
 *   parse, and when more than 5 are stored, so "the record is gone" never means "the ticket was
 *   filed".
 * - The SDK attaches an exit to a record only for reason `crash_native` (5), by equal pid and
 *   |exit timestamp - crash_at| <= 5000 ms. The native ticket then carries `crash_process_id`,
 *   `crash_at` and `crash_exit_timestamp`; for any other exit only `crash_process_id` and
 *   `crash_at`. It never carries `crash_exit_event_id` or a process start time, and the app
 *   cannot add fields to it. `HandsFeedback.submit` sends `device_id` with every ticket.
 *
 * Assumed (not verified):
 * - `crash_at` is wall-clock milliseconds (the SDK compares it with the exit timestamp in a
 *   millisecond window).
 * - The `.qnc` path and header format, which are SDK internals with no compatibility promise.
 *   They are used only to wait longer, never to skip: a wrong read means the app reports sooner.
 *
 * Rules:
 * - Matching ([matchesExit]): the same pid, a crash time within [NativeExitMatchWindowMillis] of
 *   the exit timestamp (the SDK's own window), and the same process start time within
 *   [ProcessStartToleranceMillis]. The start time is what defeats pid reuse; when either side has
 *   none, nothing matches and the exit is reported.
 * - Confirmed only: a [HandsNativeTicketConfirmation] holds the ticket id Hands returned. An
 *   upload that started, a record that is gone, or no record at all is not a confirmation. SDK
 *   0.13.1 provides no confirmation, so the reporter passes none
 *   ([HandsNativeTicketConfirmation.None]) and every native-crash exit ends up reported.
 * - Pending: while the SDK still holds a record that may be this exit's
 *   ([HandsNativePendingRecord.mayBelongTo]), the decision waits, at most
 *   [NativeExitMaxLaunchesWaited] launches and [NativeExitMaxWaitMillis]
 *   ([decideNativeExit]); the wait state is persisted with the queued report.
 * - No duplicate from the app: a report is captured once per exit, its first attempt is frozen
 *   and every retry resends the same `crash_exit_event_id`. Hands can pair it with the SDK's
 *   ticket by `device_id` + `crash_process_id` + `crash_exit_timestamp` (or the SDK's `crash_at`
 *   within 5 s of it); the app ticket says how it decided in `crash_native_correlation`.
 */

/** The SDK's exit-to-record window (`HandsNativeCrash.EXIT_MATCH_WINDOW_MS` in 0.13.1). */
internal const val NativeExitMatchWindowMillis = 5_000L

/**
 * How far two process start times may differ and still be one process. Both should come from the
 * app's own telemetry (exact); 1 s allows another wall-clock source. A pid is not reused while
 * its process lives, so a reused pid would need the earlier process to have lived under 1 s.
 */
internal const val ProcessStartToleranceMillis = 1_000L

/** A pending SDK record holds the decision for at most this many launches after the first. */
internal const val NativeExitMaxLaunchesWaited = 3

/** ... and at most this long after the app first saw the exit. */
internal const val NativeExitMaxWaitMillis = 24L * 60 * 60 * 1000

/** One process exit: pid, exit timestamp and process start ([MemoryTelemetrySnapshot]), null when unknown. */
internal data class NativeExitIdentity(val pid: Int, val exitTimestampMillis: Long, val processStartMillis: Long?)

/** A Hands native-crash ticket the server accepted: the id Hands returned, for the crash it describes. */
internal data class HandsNativeTicketConfirmation(
    val pid: Int,
    val crashAtMillis: Long,
    val processStartMillis: Long?,
    val ticketId: String,
) {
    /**
     * Whether this ticket is [exit]'s: the same pid, the same process (start times within
     * [ProcessStartToleranceMillis], both known) and a crash within [NativeExitMatchWindowMillis]
     * of the exit, not before the process started. No ticket id is no confirmation.
     */
    fun matchesExit(exit: NativeExitIdentity): Boolean {
        if (ticketId.isBlank() || pid != exit.pid) return false
        val start = processStartMillis ?: return false
        val exitStart = exit.processStartMillis ?: return false
        if (kotlin.math.abs(start - exitStart) > ProcessStartToleranceMillis) return false
        if (crashAtMillis < exitStart) return false
        return kotlin.math.abs(crashAtMillis - exit.exitTimestampMillis) <= NativeExitMatchWindowMillis
    }

    companion object {
        /** hands-android-sdk 0.13.1 returns no ticket id for native uploads (see the note above). */
        val None: List<HandsNativeTicketConfirmation> = emptyList()
    }
}

/** A native-crash record the SDK still holds on disk: not uploaded yet, or its upload failed. Not a ticket. */
internal data class HandsNativePendingRecord(val pid: Int, val crashAtMillis: Long) {
    /**
     * Whether this record may be [exit]'s: the same pid, a crash within
     * [NativeExitMatchWindowMillis] of the exit and, when the exit's start is known, not before
     * it. The record has no start time, so this can only delay a report, never skip one.
     */
    fun mayBelongTo(exit: NativeExitIdentity): Boolean {
        if (pid != exit.pid) return false
        val start = exit.processStartMillis
        if (start != null && crashAtMillis < start) return false
        return kotlin.math.abs(crashAtMillis - exit.exitTimestampMillis) <= NativeExitMatchWindowMillis
    }
}

/** The wait state of one native-crash exit's report, persisted with it ([QueuedSystemKillReport.nativeExit]). */
internal data class NativeExitWait(
    val identity: NativeExitIdentity,
    /** When the app first captured the exit. */
    val firstSeenMillis: Long,
    /** Launches that already decided to wait. */
    val launchesWaited: Int = 0,
)

internal sealed class NativeExitVerdict(val label: String) {
    /** Hands accepted a native ticket for this exit: the app files nothing. */
    data class SkipConfirmed(val ticketId: String) : NativeExitVerdict("skip_confirmed")

    /** The SDK still holds a record that may be this exit's: decide on a later launch. */
    data object Wait : NativeExitVerdict("wait_pending")

    /** No confirmation: the app files the exit; [correlation] goes on the ticket. */
    data class Report(val correlation: String) : NativeExitVerdict("report")
}

/** `crash_native_correlation`: no SDK record was pending and no ticket confirmed. */
internal const val NativeCorrelationUnconfirmed = "unconfirmed"

/** `crash_native_correlation`: an SDK record was pending and the wait ran out without a confirmation. */
internal const val NativeCorrelationWaitExpired = "wait_expired"

/**
 * What to do with a captured native-crash exit on this launch:
 * - a matching confirmation ([HandsNativeTicketConfirmation.matchesExit]): skip;
 * - else a pending record that may be its ([HandsNativePendingRecord.mayBelongTo]), with fewer than
 *   [NativeExitMaxLaunchesWaited] launches waited and less than [NativeExitMaxWaitMillis] since
 *   first seen (a clock set back counts as run out): wait;
 * - else report, `wait_expired` when a record is still pending, `unconfirmed` otherwise.
 */
internal fun decideNativeExit(
    wait: NativeExitWait,
    confirmations: List<HandsNativeTicketConfirmation>,
    pending: List<HandsNativePendingRecord>,
    nowMillis: Long,
): NativeExitVerdict {
    confirmations.firstOrNull { it.matchesExit(wait.identity) }?.let { return NativeExitVerdict.SkipConfirmed(it.ticketId) }
    if (pending.none { it.mayBelongTo(wait.identity) }) return NativeExitVerdict.Report(NativeCorrelationUnconfirmed)
    val waited = nowMillis - wait.firstSeenMillis
    val withinBound = wait.launchesWaited < NativeExitMaxLaunchesWaited && waited in 0 until NativeExitMaxWaitMillis
    return if (withinBound) NativeExitVerdict.Wait else NativeExitVerdict.Report(NativeCorrelationWaitExpired)
}

/** The records the SDK still holds, and how many `.qnc` files the app could not read. */
internal data class HandsNativePendingScan(val records: List<HandsNativePendingRecord>, val unreadable: Int)

/**
 * Reads the SDK's pending native-crash records ([dir], `filesDir/quiver/native-crashes` in 0.13.1).
 * Only the header `pid` and `crash_at` are read; nothing else leaves the file. A missing
 * directory is no records; a file without both is counted as unreadable.
 */
internal fun scanHandsNativePendingRecords(dir: File): HandsNativePendingScan {
    val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".qnc") }.orEmpty()
    var unreadable = 0
    val records =
        files.mapNotNull { file ->
            runCatching { parseHandsNativeRecordHeader(file.readText()) }.getOrNull()
                ?: run {
                    unreadable += 1
                    null
                }
        }
    return HandsNativePendingScan(records, unreadable)
}

/** `pid` and `crash_at` from a `QNC1`/`QNC2` record; null when the format or either value is missing. */
internal fun parseHandsNativeRecordHeader(text: String): HandsNativePendingRecord? {
    val lines = text.lineSequence().map(String::trim).toList()
    val format = lines.firstOrNull()
    if (format != "QNC1" && format != "QNC2") return null
    fun value(key: String): Long? =
        lines.firstOrNull { it.startsWith("$key ") }?.removePrefix(key)?.trim()?.toLongOrNull()
    val pid = value("pid")?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: return null
    val crashAt = value("crash_at") ?: return null
    return HandsNativePendingRecord(pid, crashAt)
}

/** The SDK's native-crash directory (hands-android-sdk 0.13.1 `HandsNativeCrash.crashDir`). */
internal fun handsNativeCrashDir(filesDir: File): File = File(filesDir, "quiver/native-crashes")
