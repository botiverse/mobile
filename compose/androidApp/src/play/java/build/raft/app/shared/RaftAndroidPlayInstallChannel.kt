package build.raft.app.shared

import android.app.Application
import build.raft.app.data.RaftInstallChannelLatest
import build.raft.app.data.RaftInstallChannelUpdates
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Google Play, the `play` build's install channel for the minimum build gate: the Play In-App
 * Update API's answer for this install (`availableVersionCode` when an update is available).
 * Update opens the app's Play listing. Every other build type compiles a copy that returns null.
 */
internal object RaftAndroidPlayInstallChannel {
    fun create(application: Application): RaftInstallChannelUpdates = PlayChannel(application)

    private class PlayChannel(private val application: Application) : RaftInstallChannelUpdates {
        override val name: String = "play"

        private val listingUrl: String
            get() = "https://play.google.com/store/apps/details?id=${application.packageName}"

        override suspend fun latest(): RaftInstallChannelLatest {
            val manager = AppUpdateManagerFactory.create(application)
            return suspendCancellableCoroutine { continuation ->
                manager.appUpdateInfo.addOnCompleteListener { task ->
                    val answer =
                        if (!task.isSuccessful) {
                            RaftInstallChannelLatest.Unknown
                        } else {
                            val info = task.result
                            when (info.updateAvailability()) {
                                UpdateAvailability.UPDATE_AVAILABLE ->
                                    info.availableVersionCode().toLong().takeIf { it > 0L }
                                        ?.let { RaftInstallChannelLatest.Available(build = it, updateUrl = listingUrl) }
                                        ?: RaftInstallChannelLatest.Unknown
                                UpdateAvailability.UPDATE_NOT_AVAILABLE -> RaftInstallChannelLatest.UpToDate
                                else -> RaftInstallChannelLatest.Unknown
                            }
                        }
                    if (continuation.isActive) continuation.resume(answer)
                }
            }
        }

        override fun fallbackUpdateUrl(): String = listingUrl
    }
}
