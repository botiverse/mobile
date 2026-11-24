package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlin.concurrent.Volatile

/**
 * Process-level image cache cleanup hook registered by the UI/image layer (`RaftImageLoader` /
 * `RaftSharedStoreRegistry`) so account logout and account switch in [SharedAuthDataSource] can
 * clear Coil memory/disk caches without introducing a Coil dependency into `:shared-data`.
 */
@RaftCoreInternalApi object RaftImageCacheHooks {
    @Volatile
    var clearImageCaches: suspend () -> Unit = {}
}
