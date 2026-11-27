package build.raft.app

import build.raft.app.data.RaftBackgroundKillNotice
import build.raft.app.data.RaftBackgroundWhitelistGuide
import build.raft.app.data.RaftMinBuildGateDecision
import build.raft.app.settings.SETTINGS_NOTIFICATIONS_BACKGROUND_KEEP_ALIVE_ANCHOR
import build.raft.app.settings.SettingsNativeDetailPage
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertTrue(
            shouldShowBackgroundKillNotice(
                notice = sampleNotice,
                networkStatus = RaftAppShellNetworkStatus.Online,
                minBuildGateDecision = RaftMinBuildGateDecision.Banner(threshold = 12800000L, updateUrl = null),
                dismissedMinBuildBannerThreshold = 12800000L,
            )
        )
        assertFalse(
            shouldShowBackgroundKillNotice(
                notice = sampleNotice,
                networkStatus = RaftAppShellNetworkStatus.Online,
                minBuildGateDecision = RaftMinBuildGateDecision.Banner(threshold = 12800001L, updateUrl = null),
                dismissedMinBuildBannerThreshold = 12800000L,
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
                dismissedMinBuildBannerThreshold = 12800000L,
            )
        )
    }

    @Test
    fun bannerSettingsLinkRoutesToInAppNotificationsKeepAliveAnchor() {
        assertEquals("notifications", RaftBackgroundKillNoticeSettingsLink.webTab)
        assertEquals(SettingsNativeDetailPage.Notifications.key, RaftBackgroundKillNoticeSettingsLink.destinationKey)
        assertEquals(SETTINGS_NOTIFICATIONS_BACKGROUND_KEEP_ALIVE_ANCHOR, RaftBackgroundKillNoticeSettingsLink.section)
        assertEquals(SettingsNativeDetailPage.Notifications.key, RaftBackgroundKillNoticeSettingsTarget.destinationKey)
        assertEquals(null, RaftBackgroundKillNoticeSettingsTarget.switchToServerId)
    }
}
