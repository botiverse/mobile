package build.raft.app

import kotlin.concurrent.Volatile

/**
 * Host build-mode flags shared across `:core`, `:shared-data`, `:ui-cmp`, and `:shared`.
 *
 * Set by `RaftCmpRouter.configure(debugBuild)` at host startup so UI-free layers (`:shared-data`)
 * can check whether the running build is debug without depending on `:ui-cmp`.
 */
object RaftBuildFlags {
    @Volatile
    var isDebugBuild: Boolean = false
}
