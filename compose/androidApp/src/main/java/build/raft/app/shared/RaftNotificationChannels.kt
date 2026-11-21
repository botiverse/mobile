package build.raft.app.shared

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Every notification channel the app posts to, ensured at process start
 * ([build.raft.app.RaftApplication.onCreate]) on a background IO dispatcher.
 *
 * A channel must exist before anything posts to it, and before any `startForeground`: a foreground
 * service whose notification names a missing channel crashes the app. Channels persist across
 * launches and every feature path still retains an idempotent `ensure*` guard, so launching this
 * work on [Dispatchers.IO] avoids blocking cold start on NotificationManager Binder IPCs while
 * still making channels visible in system settings before POST_NOTIFICATIONS is granted.
 */
object RaftNotificationChannels {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    internal var onEnsureAllExecutedForTests: ((threadName: String) -> Unit)? = null

    fun ensureAllAsync(context: Context): Job {
        val appContext = context.applicationContext
        return scope.launch {
            runCatching {
                onEnsureAllExecutedForTests?.invoke(Thread.currentThread().name)
                ensureAll(appContext)
            }
        }
    }

    fun ensureAll(context: Context) {
        val appContext = context.applicationContext
        AndroidUnreadNotificationPoster.ensureSharedNotificationChannels(appContext)
        RaftDownloadNotifications.ensureChannel(appContext)
        RaftBackgroundConnectionService.ensureChannel(appContext)
    }

    internal fun resetForTests() {
        onEnsureAllExecutedForTests = null
    }
}
