package build.raft.app.network

import build.raft.app.RaftCoreInternalApi
import kotlin.concurrent.Volatile

/**
 * Hook for replacing the default [RaftHttpExecutor] from `:shared` (visual-fixture capture
 * backend `RaftCaptureBackend`) without pulling visual-fixture sources into `:shared-data`.
 */
@RaftCoreInternalApi
object RaftHttpExecutorOverrides {
    @Volatile
    var executorFactory: (() -> RaftHttpExecutor)? = null
}
