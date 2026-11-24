package build.raft.app.shared

import android.app.Application
import build.raft.app.data.RaftInstallChannelLatest
import build.raft.app.data.RaftInstallChannelUpdates
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Google Play, the `play` build's install channel for the minimum build gate and the gentle
 * new-version notice: the Play In-App Update API's answer for this install (`availableVersionCode`
 * when an update is available). Update opens the app's Play listing. Every other build type
 * compiles a copy that returns null.
 */
internal object RaftAndroidPlayInstallChannel {
    fun create(application: Application): RaftInstallChannelUpdates = PlayChannel(application)

    private class PlayChannel(private val application: Application) : RaftInstallChannelUpdates {
        override val name: String = "play"

        /**
         * Only an install Google Play made gets the new-version notice: a `play` build installed
         * any other way (Hands, sideload) has no Play update to offer. Read once per process.
         */
        private val installedFromPlay: Boolean by lazy { RaftAndroidInstallReferrer.installedFromGooglePlay(application) }

        override val offersStoreUpdateNotice: Boolean
            get() = installedFromPlay

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
                                UpdateAvailability.UPDATE_AVAILABLE,
                                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ->
                                    info.availableVersionCode().toLong().takeIf { it > 0L }
                                        ?.let {
                                            RaftInstallChannelLatest.Available(
                                                build = it,
                                                updateUrl = listingUrl,
                                                updatePriority = info.updatePriority().coerceAtLeast(0),
                                                stalenessDays = info.clientVersionStalenessDays()?.coerceAtLeast(0),
                                            )
                                        }
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
