package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h844dp")
class RaftMobileWebPanelUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun thirdPartyBottomBarUpdatesHostOnNavigationAndOffersOpenInBrowser() = runRobolectricComposeUiTest {
        var currentUrl by mutableStateOf("https://www.docs.example.com/push/ios-setup")
        val actions = mutableListOf<String>()

        setContent {
            RaftDesignSystemTheme {
                RaftMobileWebPanelBottomBar(
                    isRaftApp = false,
                    currentUrl = currentUrl,
                    appName = null,
                    appLogoUrl = null,
                    signedInAsName = null,
                    onClose = { actions += "close" },
                    onShare = { actions += "share" },
                    onCopyLink = { actions += "copy" },
                    onOpenInBrowser = { actions += "open_browser" },
                )
            }
        }

        // Layer 3 center shows host without www., updated live on navigation, with IDN punycode.
        onNodeWithText("docs.example.com").assertIsDisplayed()
        runOnIdle { currentUrl = "https://bücher.example/login" }
        onNodeWithText("xn--bcher-kva.example").assertIsDisplayed()

        // Overflow menu contains Share, Copy link, and Open in browser for third-party websites.
        onNodeWithTag(RAFT_WEB_PANEL_MORE_BUTTON_TAG).performClick()
        onNodeWithText("Share").assertIsDisplayed()
        onNodeWithText("Copy link").assertIsDisplayed()
        onNodeWithText("Open in browser").assertIsDisplayed().performClick()

        // Leading close button triggers close.
        onNodeWithTag(RAFT_WEB_PANEL_CLOSE_BUTTON_TAG).performClick()

        assertEquals(listOf("open_browser", "close"), actions)
    }

    @Test
    fun raftAppBottomBarShowsAppIdentitySignedInAsAndOmitsOpenInBrowser() = runRobolectricComposeUiTest {
        val actions = mutableListOf<String>()

        setContent {
            RaftDesignSystemTheme {
                RaftMobileWebPanelBottomBar(
                    isRaftApp = true,
                    currentUrl = "https://tracker.example/views/19",
                    appName = "Tracker",
                    appLogoUrl = null,
                    signedInAsName = "Mia",
                    onClose = { actions += "close" },
                    onShare = { actions += "share" },
                    onCopyLink = { actions += "copy" },
                    onOpenInBrowser = null,
                )
            }
        }

        onNodeWithTag(RAFT_WEB_PANEL_APP_NAME_TAG).assertIsDisplayed()
        onNodeWithText("Tracker").assertIsDisplayed()
        onNodeWithTag(RAFT_WEB_PANEL_SIGNED_IN_LABEL_TAG).assertIsDisplayed()
        onNodeWithText("Signed in as Mia").assertIsDisplayed()

        // Layer 2 Raft App overflow menu has Share and Copy link ONLY — NO "Open in browser".
        onNodeWithTag(RAFT_WEB_PANEL_MORE_BUTTON_TAG).performClick()
        onNodeWithText("Share").assertIsDisplayed()
        onNodeWithText("Copy link").assertIsDisplayed()
        onAllNodesWithText("Open in browser").assertCountEquals(0)
        onNodeWithText("Copy link").performClick()

        assertEquals(listOf("copy"), actions)
    }

    @Test
    fun sourceStripCollapseKeepsAliveInlineBadgeReopensAndCloseRemovesBadge() = runRobolectricComposeUiTest {
        val coordinator = RaftMobileWebPanelCoordinator(releaseEntryToken = { _, _ -> })
        val host = RaftMobileWebPanelHostController(hostId = "test-host", coordinator = coordinator)
        val msg1Source =
            RaftMobileWebPanelSource.Message(
                messageId = "msg-1",
                authorName = "Xiaofan",
                authorAvatarKind = RaftAvatarKind.Agent,
                previewText = "Check iOS push certificate docs",
                timestampLabel = "",
            )

        setContent {
            RaftDesignSystemTheme {
                CompositionLocalProvider(LocalRaftMobileWebPanelHost provides host) {
                    val session by coordinator.session.collectAsState()
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (session?.isExpanded == true) {
                            RaftMobileWebPanelSourceStrip(
                                source = session?.source,
                                topSafeArea = 0.dp,
                                onCollapse = { host.collapse(session!!.sessionId) },
                            )
                        }
                        ThreadMessageKeepOpenBadgeSlot(messageId = "msg-1")
                        ThreadMessageKeepOpenBadgeSlot(messageId = "msg-2")
                    }
                }
            }
        }

        // Open panel from msg-1 -> source strip visible, keep-open chip hidden while expanded.
        val opened =
            runOnIdle {
                host.setPendingSource(msg1Source)
                host.openLink("https://docs.example.com/push/ios-setup")
                coordinator.session.value!!
            }
        onNodeWithTag(RAFT_WEB_PANEL_SOURCE_STRIP_TAG).assertIsDisplayed()
        onNodeWithText("Xiaofan").assertIsDisplayed()
        onAllNodesWithText("docs.example.com · Page still open").assertCountEquals(0)

        // Tap source strip -> collapses panel keeping page alive; inline keep-open badge shows under msg-1.
        onNodeWithTag(RAFT_WEB_PANEL_SOURCE_STRIP_TAG).performClick()
        runOnIdle {
            assertTrue(coordinator.session.value?.isCollapsedKeptAlive == true)
        }
        onNodeWithTag(RAFT_WEB_PANEL_KEEP_OPEN_CHIP_TAG).assertIsDisplayed()
        onNodeWithText("docs.example.com · Page still open").assertIsDisplayed()

        // Tap inline keep-open chip -> re-expands exact live session immediately.
        onNodeWithTag(RAFT_WEB_PANEL_KEEP_OPEN_CHIP_TAG).performClick()
        runOnIdle {
            assertEquals(opened.sessionId, coordinator.session.value?.sessionId)
            assertTrue(coordinator.session.value?.isExpanded == true)
        }
        onNodeWithTag(RAFT_WEB_PANEL_SOURCE_STRIP_TAG).assertIsDisplayed()

        // Collapse again, then close -> destroys session and removes keep-open badge.
        onNodeWithTag(RAFT_WEB_PANEL_SOURCE_STRIP_TAG).performClick()
        onNodeWithTag(RAFT_WEB_PANEL_KEEP_OPEN_CHIP_TAG).assertIsDisplayed()
        runOnIdle { host.close(opened.sessionId) }
        runOnIdle { assertNull(coordinator.session.value) }
        onAllNodesWithText("docs.example.com · Page still open").assertCountEquals(0)
    }

    @Test
    fun surfaceSourceStripAndFloatingKeepOpenChipReopenAppEntry() = runRobolectricComposeUiTest {
        val coordinator = RaftMobileWebPanelCoordinator(releaseEntryToken = { _, _ -> })
        val host = RaftMobileWebPanelHostController(hostId = "apps-host", coordinator = coordinator)

        setContent {
            RaftDesignSystemTheme {
                val session by coordinator.session.collectAsState()
                Column(modifier = Modifier.fillMaxWidth()) {
                    val active = session
                    if (active != null && active.isExpanded) {
                        RaftMobileWebPanelSourceStrip(
                            source = active.source,
                            topSafeArea = 0.dp,
                            onCollapse = { host.collapse(active.sessionId) },
                        )
                    } else if (active != null && active.isCollapsedKeptAlive) {
                        RaftMobileWebPanelKeepOpenChip(
                            label = active.keepOpenLabel,
                            onClick = { host.reopen(active.sessionId) },
                            elevated = true,
                            modifier = Modifier.testTag(RAFT_WEB_PANEL_FLOATING_KEEP_OPEN_CHIP_TAG),
                        )
                    }
                }
            }
        }

        val appSession =
            runOnIdle {
                host.setPendingSource(RaftMobileWebPanelSource.Surface("Apps"))
                host.openAppEntry(
                    url = "https://tracker.example/views/1",
                    appEntryToken = "tok-1",
                    request =
                        RaftAppEntryRequest(
                            scopeOrigin = "https://tracker.example",
                            appName = "Tracker",
                            preferredServerId = "srv-1",
                        ),
                )
                coordinator.session.value!!
            }

        onNodeWithText("Apps").assertIsDisplayed()
        onNodeWithTag(RAFT_WEB_PANEL_SOURCE_STRIP_TAG).performClick()
        onNodeWithTag(RAFT_WEB_PANEL_FLOATING_KEEP_OPEN_CHIP_TAG).assertIsDisplayed()
        onNodeWithText("Tracker · Page still open").assertIsDisplayed().performClick()

        runOnIdle {
            assertEquals(appSession.sessionId, coordinator.session.value?.sessionId)
            assertTrue(coordinator.session.value?.isExpanded == true)
        }
        onNodeWithText("Apps").assertIsDisplayed()
    }
}
