package build.raft.app.shared

import android.app.Activity
import android.app.Application
import android.os.Bundle
import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.data.RaftStoreReviewHost
import build.raft.app.data.RaftStoreReviewRequester
import com.google.android.play.core.review.ReviewManagerFactory
import java.lang.ref.WeakReference

/**
 * The Google Play In-App Review prompt behind the shared rating prompt
 * ([build.raft.app.data.RaftRatingPrompt] decides when, at most once per device). This source set
 * (and the Play In-App Review library) is in the `play` build type only: a build Google Play did not
 * install can never show the prompt. Every other build type compiles the no-op in src/noPlay.
 *
 * [RaftStoreReviewRequester.requestReview] runs on the main thread; it starts the review flow over
 * the resumed activity and returns true, or returns false when no activity is resumed (nothing is
 * recorded then). Whether Play shows anything is Play's decision and is never reported back.
 */
internal object RaftAndroidStoreReview {
    private const val LOG_TAG = "RaftStoreReview"

    @Volatile
    private var installed = false

    @Volatile
    private var resumedActivity: WeakReference<Activity>? = null

    fun install(application: Application) {
        synchronized(this) {
            if (installed) return
            installed = true
        }
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    resumedActivity = WeakReference(activity)
                }

                override fun onActivityPaused(activity: Activity) {
                    if (resumedActivity?.get() === activity) resumedActivity = null
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        )
        RaftStoreReviewHost.requester = RaftStoreReviewRequester { requestReview() }
    }

    private fun currentActivity(): Activity? =
        resumedActivity?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    private fun requestReview(): Boolean {
        val activity = currentActivity() ?: return false
        val manager = ReviewManagerFactory.create(activity.applicationContext)
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (!request.isSuccessful) {
                RaftAppLog.d(LOG_TAG, "store_review_flow_unavailable error=${request.exception?.javaClass?.simpleName}")
                return@addOnCompleteListener
            }
            // The person may have moved on meanwhile: launch over whatever is resumed now, or not at all.
            val host = currentActivity() ?: return@addOnCompleteListener
            manager.launchReviewFlow(host, request.result)
            RaftAppLog.d(LOG_TAG, "store_review_flow_launched")
        }
        return true
    }
}
