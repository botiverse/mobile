package build.raft.app.core.data.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

internal data class HistoricalProcessExitSnapshot(
    val timestampMillis: Long,
    val reasonCode: Int,
    val status: Int,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
    val traceAvailable: Boolean
)

internal fun newestEligibleHistoricalProcessExit(
    exits: Iterable<HistoricalProcessExitSnapshot>,
    packageInstalledAtMillis: Long,
    lastReportedAtMillis: Long
): HistoricalProcessExitSnapshot? {
    val cutoff = maxOf(packageInstalledAtMillis, lastReportedAtMillis)
    return exits.filter { it.timestampMillis > cutoff }.maxByOrNull { it.timestampMillis }
}

internal fun formatHistoricalProcessExit(snapshot: HistoricalProcessExitSnapshot): String =
    "previous_process_exit " +
        "timestampMillis=${snapshot.timestampMillis} " +
        "reason=${historicalProcessExitReasonLabel(snapshot.reasonCode)} " +
        "reasonCode=${snapshot.reasonCode} " +
        "status=${snapshot.status} " +
        "importance=${snapshot.importance} " +
        "pssKb=${snapshot.pssKb} " +
        "rssKb=${snapshot.rssKb} " +
        "traceAvailable=${snapshot.traceAvailable}"

internal fun historicalProcessExitReasonLabel(reasonCode: Int): String =
    when (reasonCode) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        ApplicationExitInfo.REASON_FREEZER -> "freezer"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package_state_change"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package_updated"
        else -> "unknown"
    }

internal object AndroidHistoricalProcessExitDiagnostics {
    private const val Tag = "SlockAndroidProcessExit"
    private const val PreferencesName = "raft-process-exit-diagnostics"
    private const val LastReportedTimestampKey = "last-reported-timestamp-millis"
    private const val MaxHistoricalExits = 8

    fun recordLatest(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appContext = context.applicationContext
        runCatching {
            val activityManager =
                appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val packageInstalledAtMillis =
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime
            val preferences = appContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            val lastReportedAtMillis = preferences.getLong(LastReportedTimestampKey, 0L)
            val historicalExits =
                activityManager.getHistoricalProcessExitReasons(
                    appContext.packageName,
                    0,
                    MaxHistoricalExits
                )
            val snapshots =
                historicalExits.map { exit ->
                    HistoricalProcessExitSnapshot(
                        timestampMillis = exit.timestamp,
                        reasonCode = exit.reason,
                        status = exit.status,
                        importance = exit.importance,
                        pssKb = exit.pss,
                        rssKb = exit.rss,
                        traceAvailable = false
                    )
                }
            val latestMetadata =
                newestEligibleHistoricalProcessExit(
                    exits = snapshots,
                    packageInstalledAtMillis = packageInstalledAtMillis,
                    lastReportedAtMillis = lastReportedAtMillis
                )
            if (latestMetadata == null) {
                val newest = snapshots.maxByOrNull { it.timestampMillis }
                SlockAndroidLocalDiagnostics.recordLog(
                    level = "info",
                    tag = Tag,
                    message =
                        "previous_process_exit_not_reported " +
                            "records=${snapshots.size} " +
                            "newestTimestampMillis=${newest?.timestampMillis ?: -1L} " +
                            "newestReason=${newest?.let { historicalProcessExitReasonLabel(it.reasonCode) } ?: "none"} " +
                            "cutoff=${maxOf(packageInstalledAtMillis, lastReportedAtMillis)}"
                )
                return
            }
            val latestExit =
                historicalExits.first { exit ->
                    exit.timestamp == latestMetadata.timestampMillis &&
                        exit.reason == latestMetadata.reasonCode &&
                        exit.status == latestMetadata.status
                }
            val latest =
                latestMetadata.copy(
                    traceAvailable =
                        runCatching {
                            latestExit.traceInputStream?.use { true } ?: false
                        }.getOrDefault(false)
                )
            preferences.edit().putLong(LastReportedTimestampKey, latest.timestampMillis).apply()
            SlockAndroidLocalDiagnostics.recordLog(
                level = "warn",
                tag = Tag,
                message = formatHistoricalProcessExit(latest)
            )
        }.onFailure { error ->
            SlockAndroidLocalDiagnostics.recordLog(
                level = "warn",
                tag = Tag,
                message = "previous_process_exit_read_failed class=${error.javaClass.name}"
            )
        }
    }
}
