package build.raft.app.shared

import android.content.Intent
import build.raft.app.data.RaftAppOpenSignal
import org.json.JSONObject

/**
 * Marks an activity intent with what opens it, for `app_opened.via`: the launcher intent is shared
 * by the launcher icon and some notifications, so those notifications say so.
 */
internal const val EXTRA_RAFT_APP_OPEN_SOURCE = "slockAppOpenSource"

/** A tap on a push (unread / mention) notification. */
internal const val RAFT_APP_OPEN_SOURCE_PUSH_NOTIFICATION = "push_notification"

/** A tap on one of the app's own status notifications (background connection, test): not a push. */
internal const val RAFT_APP_OPEN_SOURCE_APP_NOTIFICATION = "app_notification"

/**
 * What an activity launch intent says about how the app was opened. [opensNotificationTarget]: the
 * page data carries a push notification's target (notifications posted before the source extra).
 */
internal fun androidAppOpenSignal(
    action: String?,
    categories: Set<String>?,
    flags: Int,
    openSource: String?,
    opensNotificationTarget: Boolean,
): RaftAppOpenSignal =
    when {
        openSource == RAFT_APP_OPEN_SOURCE_PUSH_NOTIFICATION || opensNotificationTarget -> RaftAppOpenSignal.Notification
        openSource != null -> RaftAppOpenSignal.Unknown
        // Recents relaunching a task whose activities were gone carries the original launcher intent.
        flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0 -> RaftAppOpenSignal.Unknown
        action == Intent.ACTION_MAIN && categories?.contains(Intent.CATEGORY_LAUNCHER) == true -> RaftAppOpenSignal.Launcher
        else -> RaftAppOpenSignal.Unknown
    }

internal fun Intent?.raftAppOpenSignal(): RaftAppOpenSignal {
    val intent = this ?: return RaftAppOpenSignal.Unknown
    return runCatching {
        val opensNotificationTarget =
            intent.getStringExtra(RaftCmpActivity.KEY_PAGE_DATA)?.let { text ->
                runCatching {
                    JSONObject(RaftPageData.fromText(text).text)
                        .optString(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_THREAD_ID)
                        .isNotBlank()
                }.getOrDefault(false)
            } == true
        androidAppOpenSignal(
            action = intent.action,
            categories = intent.categories,
            flags = intent.flags,
            openSource = intent.getStringExtra(EXTRA_RAFT_APP_OPEN_SOURCE),
            opensNotificationTarget = opensNotificationTarget,
        )
    }.getOrDefault(RaftAppOpenSignal.Unknown)
}
