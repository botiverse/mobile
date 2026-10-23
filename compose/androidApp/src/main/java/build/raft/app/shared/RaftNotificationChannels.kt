package build.raft.app.shared

import android.content.Context

/**
 * Every notification channel the app posts to, created once at process start
 * ([build.raft.app.RaftApplication.onCreate]).
 *
 * A channel must exist before anything posts to it, and before any `startForeground`: a foreground
 * service whose notification names a missing channel crashes the app. Creating channels only on the path
 * that first posts, or only after POST_NOTIFICATIONS is granted, left gaps (the download service crashed
 * that way). Creating them all at start closes the whole class; it needs no permission, and it lets the
 * channels show in system settings before the user grants it. Feature `ensure*` calls stay as cheap,
 * idempotent safety and are no longer what makes a channel exist.
 */
object RaftNotificationChannels {
    fun ensureAll(context: Context) {
        val appContext = context.applicationContext
        AndroidUnreadNotificationPoster.ensureSharedNotificationChannels(appContext)
        RaftDownloadNotifications.ensureChannel(appContext)
        RaftBackgroundConnectionService.ensureChannel(appContext)
    }
}
