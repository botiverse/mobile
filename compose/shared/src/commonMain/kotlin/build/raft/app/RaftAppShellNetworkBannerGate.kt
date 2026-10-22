package build.raft.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Derives the offline-banner status from raw host network signals.
 *
 * Hands (artin 2026-10-22): a Wi-Fi/cellular handover briefly drops validation and the raw host
 * signal flips Offline -> Online within a second or two, which flashed the red banner. The banner
 * now shows only after the host has reported Offline continuously for [offlineGraceMillis]; an
 * Online signal hides it at once and cancels a pending show, so an Offline/Online/Offline flap
 * restarts the grace window.
 *
 * Shared so Android and iOS get the same behavior. Repeated signals with the same raw status are
 * no-ops: a second Offline does not restart the window, and [bannerStatus] never re-emits an
 * unchanged value.
 */
internal class RaftAppShellNetworkBannerGate(
    private val scope: CoroutineScope,
    private val offlineGraceMillis: Long = DefaultOfflineGraceMillis
) {
    private val mutableBannerStatus = MutableStateFlow(RaftAppShellNetworkStatus.Online)
    val bannerStatus: StateFlow<RaftAppShellNetworkStatus> = mutableBannerStatus.asStateFlow()

    private var rawStatus: RaftAppShellNetworkStatus = RaftAppShellNetworkStatus.Online
    private var pendingShow: Job? = null

    fun onHostStatus(status: RaftAppShellNetworkStatus) {
        if (status == rawStatus) return
        rawStatus = status
        pendingShow?.cancel()
        pendingShow = null
        when (status) {
            RaftAppShellNetworkStatus.Online -> mutableBannerStatus.value = RaftAppShellNetworkStatus.Online
            RaftAppShellNetworkStatus.Offline ->
                pendingShow =
                    scope.launch {
                        delay(offlineGraceMillis)
                        if (rawStatus == RaftAppShellNetworkStatus.Offline) {
                            mutableBannerStatus.value = RaftAppShellNetworkStatus.Offline
                        }
                    }
        }
    }

    companion object {
        const val DefaultOfflineGraceMillis: Long = 3_000L
    }
}
