package build.raft.app.shared

import android.app.Application

/**
 * Build type `release` (every build type except `play` has this same no-op; the Hands APKs): no store
 * rating prompt and no Play In-App Review library. Nothing is installed in
 * [build.raft.app.data.RaftStoreReviewHost], so the shared rating prompt never asks and records
 * nothing. The `play` build type's implementation is in src/play.
 */
internal object RaftAndroidStoreReview {
    @Suppress("UNUSED_PARAMETER")
    fun install(application: Application) = Unit
}
