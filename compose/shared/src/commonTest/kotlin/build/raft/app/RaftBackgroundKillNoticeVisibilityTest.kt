package build.raft.app

import build.raft.app.data.RaftBackgroundKillNotice
import build.raft.app.data.RaftBackgroundWhitelistGuide
import build.raft.app.data.RaftMinBuildGateDecision
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftBackgroundKillNoticeVisibilityTest {
    private val sampleNotice =
        RaftBackgroundKillNotice(
            exitTimestampMillis = 1_000_000L,
            guide = RaftBackgroundWhitelistGuide.LenovoZui,
        )

    @Test
    fun noticeShowsOnlyWhenPresentOnlineAndMinBuildGateInactive() {
        assertTrue(
            shouldShowBackgroundKillNotice(
                notice = sampleNotice,
                networkStatus = RaftAppShellNetworkStatus.Online,
                minBuildGateDecision = RaftMinBuildGateDecision.None,
            )
        )
        assertFalse(
            shouldShowBackgroundKillNotice(
                notice = null,
                networkStatus = RaftAppShellNetworkStatus.Online,
                minBuildGateDecision = RaftMinBuildGateDecision.None,
            )
        )
        assertFalse(
            shouldShowBackgroundKillNotice(
                notice = sampleNotice,
                networkStatus = RaftAppShellNetworkStatus.Offline,
                minBuildGateDecision = RaftMinBuildGateDecision.None,
            )
        )
        assertFalse(
            shouldShowBackgroundKillNotice(
                notice = sampleNotice,
                networkStatus = RaftAppShellNetworkStatus.Online,
                minBuildGateDecision = RaftMinBuildGateDecision.Banner(threshold = 12800000L, updateUrl = null),
            )
        )
        assertFalse(
            shouldShowBackgroundKillNotice(
                notice = sampleNotice,
                networkStatus = RaftAppShellNetworkStatus.Online,
                minBuildGateDecision =
                    RaftMinBuildGateDecision.Block(
                        threshold = 12800000L,
                        latestBuild = 12800001L,
                        updateUrl = "https://hands.build/apps/raft-android/latest",
                    ),
            )
        )
    }
}
