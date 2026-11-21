package build.raft.app.core.data.diagnostics

import java.io.File

/*
 * Native-crash exits and the Hands SDK's native ticket (task #243).
 *
 * Before: AndroidSystemKillReporter skipped every `crash_native` exit (and every `signaled` exit
 * with a crash signal) as "reported elsewhere", assuming HandsCrash had filed a ticket for it.
 * Nothing checked that it had. Now such an exit is always reported by the app, and two ways keep
 * it from becoming a second ticket:
 *
 * 1. Merge (hands-android-sdk 0.13.2+, the normal path). Each process has a random
 *    `HandsNativeCrash.processEventId`; the SDK files that process's native crash with it as
 *    `submission_id`. The app keeps the id with the process's own launch record
 *    ([MemoryTelemetrySnapshot.handsProcessEventId]) and reports the exit with the same
 *    `submission_id` ([nativeExitSubmissionId]). Hands merges a native-crash report and a
 *    non-native crash report under one `submission_id` into one ticket, in either order, so the
 *    app reports at once, without waiting for or confirming the SDK's upload
 *    ([NativeExitVerdict.Merge]). How the reporter handles Hands' answers (merged, 409, 503
 *    `submission_merge_conflict`): [runSystemKillReports] and [nativeMergeFailure].
 * 2. Conservative (an exit whose launch record has no event id: written by a build before
 *    0.13.2, or a record that does not check out). As in the first version of this change: skipped
 *    only on a confirmed Hands ticket for that very exit, held while the SDK still holds a record
 *    that may be the exit's, otherwise reported standalone.
 *
 * Verified in hands-android-sdk 0.13.2 (classes.jar of the AAR, by javap):
 * - `HandsNativeCrash.processEventId` is a lazy `UUID.randomUUID()` per process;
 *   `HandsNativeCrash.processStartAt` is `currentTimeMillis - (elapsedRealtime -
 *   Process.getStartElapsedRealtime())`, the formula of [MemoryTelemetrySnapshot.processStartMillis].
 *   The native upload passes the record's process event id as `submissionId` to
 *   `HandsFeedback.submitReport`, and adds `crash_process_event_id` / `crash_process_start_at`.
 * - `HandsFeedback.submitReport(message, kind, contact, attachments, extras, submissionId)`
 *   returns `Submission(ticketId: String?, merged: Boolean)`; `ticketId` may be null only when
 *   merged. A non-2xx answer throws `HandsFeedbackException(code, first 300 chars of the body)`;
 *   a submission id that is not a UUID (version 1-8, RFC variant) throws IllegalArgumentException.
 * - `HandsNativeCrash.uploadPending` is run by [RaftHandsLaunchUploads] (`uploadOnLaunch = false`),
 *   which drops its per-record results (`NativeCrashUpload`), so the app still gets no native
 *   ticket id: nothing is ever confirmed ([HandsNativeTicketConfirmation.None]), and with the merge
 *   none is needed.
 *
 * Verified in hands-android-sdk 0.13.1 and unchanged in 0.13.2 (the conservative path relies on it):
 * - Records live in `.qnc` files under `filesDir/quiver/native-crashes`. A text format: first line `QNC1` or
 *   `QNC2`, header lines `pid <n>` and `crash_at <n>`. A record is also deleted when it does not
 *   parse, and when more than 5 are stored, so "the record is gone" never means "the ticket was
 *   filed".
 * - The SDK attaches an exit to a record only for reason `crash_native` (5), by equal pid and
 *   |exit timestamp - crash_at| <= 5000 ms. The native ticket then carries `crash_process_id`,
 *   `crash_at` and `crash_exit_timestamp`; for any other exit only `crash_process_id` and
 *   `crash_at` of these (a 0.13.2+ record adds its event id and process start, above). It never
 *   carries `crash_exit_event_id`, and the app cannot add fields to it. `HandsFeedback.submit`
 *   sends `device_id` with every ticket.
 *
 * Since hands-android-sdk 0.14 (read in the 0.14.3 sources): each process also leaves its build in
 * `<process event id>.build` next to the records, and the native ticket names that build
 * (`version_name` / `version_code`), the one that crashed. The app reads only the `.qnc` files.
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
 *   upload that started, a record that is gone, or no record at all is not a confirmation. The
 *   SDK provides no confirmation to the app, so the reporter passes none
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

/** The SDK's exit-to-record window (`HandsNativeCrash.EXIT_MATCH_WINDOW_MS`, 0.13.1 and 0.13.2). */
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

/**
 * One process exit: pid, exit timestamp and process start ([MemoryTelemetrySnapshot]), null when
 * unknown, and the `submission_id` the SDK files that process's native crash under
 * ([nativeExitSubmissionId]), null when unknown (the conservative path).
 */
internal data class NativeExitIdentity(
    val pid: Int,
    val exitTimestampMillis: Long,
    val processStartMillis: Long?,
    val submissionId: String? = null,
)

/** `HandsFeedback.SUBMISSION_ID_PATTERN` (0.13.2): a UUID, version 1-8, RFC 4122 variant. */
private val HandsSubmissionIdPattern =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

/**
 * The `submission_id` to report [telemetry]'s process exit under: its
 * [MemoryTelemetrySnapshot.handsProcessEventId], only when it is a UUID the SDK accepts and the
 * SDK's process start agrees with the app's own (within [ProcessStartToleranceMillis]; both use
 * one formula, so a disagreement means the record is not what it seems). Null otherwise: no
 * record, a record written before 0.13.2, or one that does not check out; the exit then takes the
 * conservative path.
 */
internal fun nativeExitSubmissionId(telemetry: MemoryTelemetrySnapshot?): String? {
    telemetry ?: return null
    val id = telemetry.handsProcessEventId.takeIf { HandsSubmissionIdPattern.matches(it) } ?: return null
    val sdkStart = telemetry.handsProcessStartMillis.takeIf { it > 0L } ?: return null
    if (kotlin.math.abs(sdkStart - telemetry.processStartMillis) > ProcessStartToleranceMillis) return null
    return id.lowercase()
}

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
        /** The SDK gives the app no ticket id for native uploads (see the note above). */
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

    /**
     * The exit's process has a Hands event id: the app files it now under [submissionId], and
     * Hands merges it with the SDK's native ticket for that process, whichever arrives first.
     */
    data class Merge(val submissionId: String) : NativeExitVerdict("report_merge")
}

/** `crash_native_correlation`: no SDK record was pending and no ticket confirmed. */
internal const val NativeCorrelationUnconfirmed = "unconfirmed"

/** `crash_native_correlation`: an SDK record was pending and the wait ran out without a confirmation. */
internal const val NativeCorrelationWaitExpired = "wait_expired"

/** `crash_native_correlation`: sent under the process's `submission_id`, for Hands to merge. */
internal const val NativeCorrelationSubmissionId = "submission_id"

/**
 * `crash_native_correlation`: Hands refused the merge (409) on the first send under the
 * `submission_id`, so the report was sent again standalone, without it.
 */
internal const val NativeCorrelationMergeRejected = "merge_rejected"

/**
 * What to do with a captured native-crash exit on this launch:
 * - a matching confirmation ([HandsNativeTicketConfirmation.matchesExit]): skip;
 * - else a known `submission_id` ([NativeExitIdentity.submissionId]): report now under it, for
 *   Hands to merge; nothing to wait for;
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
    wait.identity.submissionId?.let { return NativeExitVerdict.Merge(it) }
    if (pending.none { it.mayBelongTo(wait.identity) }) return NativeExitVerdict.Report(NativeCorrelationUnconfirmed)
    val waited = nowMillis - wait.firstSeenMillis
    val withinBound = wait.launchesWaited < NativeExitMaxLaunchesWaited && waited in 0 until NativeExitMaxWaitMillis
    return if (withinBound) NativeExitVerdict.Wait else NativeExitVerdict.Report(NativeCorrelationWaitExpired)
}

/** The records the SDK still holds, and how many `.qnc` files the app could not read. */
internal data class HandsNativePendingScan(val records: List<HandsNativePendingRecord>, val unreadable: Int)

/**
 * Reads the SDK's pending native-crash records ([dir], `filesDir/quiver/native-crashes`).
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

/** The SDK's native-crash directory (`HandsNativeCrash.crashDir`, hands-android-sdk 0.13.1 and 0.13.2). */
internal fun handsNativeCrashDir(filesDir: File): File = File(filesDir, "quiver/native-crashes")

/** What Hands answered a system-kill report ([HandsFeedback.submitReport][build.hands.update.HandsFeedback.submitReport]). */
internal data class SystemKillSubmission(
    /** The ticket id; null only for a merge seen by a client without a key (Hands withholds it). */
    val ticketId: String?,
    /** True when Hands merged the report into the ticket already holding its `submission_id`. */
    val merged: Boolean = false,
)

/** How a send under a `submission_id` failed, when the failure is about the merge. */
internal enum class NativeMergeFailure {
    /**
     * 503 `submission_merge_conflict`: Hands was merging into the same ticket at that moment (the
     * SDK's native upload runs at launch too). Nothing was stored; send the same content again.
     */
    RetrySameContent,

    /**
     * 409: Hands will not merge this report under this id (two non-native reports under one id,
     * a native report of another process, a non-crash kind, or more than 5 merges).
     */
    Refused,
}

/** The [NativeMergeFailure] in [error], or null for any other failure. */
internal fun nativeMergeFailure(error: Throwable): NativeMergeFailure? {
    val failure = error as? build.hands.update.HandsFeedbackException ?: return null
    return when {
        failure.code == 409 -> NativeMergeFailure.Refused
        failure.code == 503 && failure.message.orEmpty().contains("submission_merge_conflict") -> NativeMergeFailure.RetrySameContent
        else -> null
    }
}

/** Sends after a 503 `submission_merge_conflict` within one launch, beyond the first; then the next launch. */
internal const val NativeMergeConflictRetries = 2

/** The wait before each [NativeMergeConflictRetries] retry. */
internal const val NativeMergeConflictRetryDelayMillis = 3_000L
