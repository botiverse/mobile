package build.raft.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What [NotificationPermissionAutoPrompt.maybeAsk] did. */
internal enum class NotificationPermissionAutoPromptOutcome {
    /** The host asks on its own (iOS asks once a session is authenticated), or cannot ask. */
    Unsupported,

    /** Asked before (on any account): Settings › Notifications is the way to ask again. */
    AlreadyAsked,

    /** The permission is already granted, or notifications are off in system settings (not a prompt). */
    NotNeeded,

    /** The host could not report the permission state; try again next time. */
    StatusUnknown,

    /** The system prompt was requested. */
    Requested,

    /** The host refused to show the prompt. */
    RequestFailed,
}

/**
 * artin 10-27: a fresh Android 13+ install never asked for POST_NOTIFICATIONS (only Settings ›
 * Notifications did), so users got no notifications. Once the signed-in app shell shows, ask once
 * when the permission was never granted and the app never asked before. The "asked" flag is
 * written before the prompt opens, so a kill mid-prompt does not ask again; the system itself stops
 * showing the prompt after two denials.
 *
 * Logs one `SlockNotificationPermission` line for the decision and, after the user answered (the
 * next resume, see [onResumed]), one for the result.
 */
internal class NotificationPermissionAutoPrompt(
    private val preferences: NotificationPermissionPromptPreferenceStore,
    private val supported: Boolean,
    /** The host's `getNotificationPermissionStatus` code, or null when it has none. */
    private val loadStatusCode: suspend () -> String?,
    private val requestPermission: suspend () -> SystemNotificationActionResult,
    private val logger: (String) -> Unit = {},
) {
    private var awaitingResult = false
    private var settled: NotificationPermissionAutoPromptOutcome? = null

    /** Decides once per instance (the shell calls it each time it shows); only an unknown status retries. */
    suspend fun maybeAsk(): NotificationPermissionAutoPromptOutcome =
        ProcessLock.withLock {
            settled?.let { return@withLock it }
            val outcome = decideAndAsk()
            if (outcome != NotificationPermissionAutoPromptOutcome.StatusUnknown) settled = outcome
            // Unsupported / already asked are the steady state on every launch: not worth a line.
            if (outcome != NotificationPermissionAutoPromptOutcome.Unsupported &&
                outcome != NotificationPermissionAutoPromptOutcome.AlreadyAsked
            ) {
                logger("notification_permission_auto_prompt outcome=${outcome.name.lowercase()}")
            }
            outcome
        }

    private suspend fun decideAndAsk(): NotificationPermissionAutoPromptOutcome {
        if (!supported) return NotificationPermissionAutoPromptOutcome.Unsupported
        if (preferences.readAutoPromptAskedSync()) return NotificationPermissionAutoPromptOutcome.AlreadyAsked
        val code =
            runCatchingPreservingCancellation { loadStatusCode() }.getOrNull()
                ?: return NotificationPermissionAutoPromptOutcome.StatusUnknown
        // Only a never-granted runtime permission is a prompt. Granted-but-switched-off would make the
        // host open system settings, which the app must not do on its own.
        if (code != PERMISSION_NOT_DETERMINED) {
            return if (code in KNOWN_STATUS_CODES) {
                NotificationPermissionAutoPromptOutcome.NotNeeded
            } else {
                NotificationPermissionAutoPromptOutcome.StatusUnknown
            }
        }
        preferences.markAutoPromptAsked()
        val result =
            runCatchingPreservingCancellation { requestPermission() }.getOrElse {
                SystemNotificationActionResult(SystemNotificationActionStatus.Error, code = "request_threw")
            }
        if (result.status == SystemNotificationActionStatus.Requested || result.status == SystemNotificationActionStatus.Ok) {
            awaitingResult = result.status == SystemNotificationActionStatus.Requested
            return NotificationPermissionAutoPromptOutcome.Requested
        }
        logger("notification_permission_auto_prompt request_failed code=${result.code}")
        return NotificationPermissionAutoPromptOutcome.RequestFailed
    }

    /** After the prompt (it pauses the app): logs what the user chose. */
    suspend fun onResumed() {
        if (!awaitingResult) return
        awaitingResult = false
        val code = runCatchingPreservingCancellation { loadStatusCode() }.getOrNull()
        val result =
            when (code) {
                PERMISSION_AUTHORIZED -> "granted"
                null -> "unknown"
                else -> "not_granted"
            }
        logger("notification_permission_auto_prompt result=$result")
    }

    private companion object {
        /** One decision at a time per process: two shells (multi-window) must not both prompt. */
        val ProcessLock = Mutex()
        const val PERMISSION_NOT_DETERMINED = "permission_not_determined"
        const val PERMISSION_AUTHORIZED = "permission_authorized"
        val KNOWN_STATUS_CODES =
            setOf(
                PERMISSION_AUTHORIZED,
                "permission_provisional",
                "permission_ephemeral",
                "permission_denied",
            )
    }
}
