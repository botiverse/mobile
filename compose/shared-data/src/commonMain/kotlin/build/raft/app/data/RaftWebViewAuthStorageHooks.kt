package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlin.concurrent.Volatile

/**
 * UI-layer hook registered by `:shared` (`RaftSharedStoreRegistry`) so `:shared-data` auth
 * sign-out / account-switch flows can clear embedded WebView cookies/storage without depending
 * on WebView UI classes.
 */
@RaftCoreInternalApi
object RaftWebViewAuthStorageHooks {
    @Volatile
    var clearAuthStorage: (suspend () -> Unit)? = null
}
