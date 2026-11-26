package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.theme.RaftTheme
import build.raft.webcontainer.WebJsDialogKind
import build.raft.webcontainer.WebJsDialogRequest
import build.raft.webcontainer.WebJsDialogResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
        val spoofHost = "accounts.google.com.sign-in.account-recovery.evil-example.net"
        runOnIdle { currentUrl = "https://$spoofHost/oauth" }
        onNodeWithTag(RAFT_WEB_PANEL_HOST_LABEL_TAG).assertIsDisplayed()
        onNodeWithText(spoofHost).assertIsDisplayed()

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

    @Test
    fun backdropOpaqueMasksUnderlyingHeaderInDefault88AndFullscreen() = runRobolectricComposeUiTest {
        var fullscreen by mutableStateOf(false)
        var collapses = 0
        var expectedCanvas = Color.Unspecified

        setContent {
            RaftDesignSystemTheme {
                expectedCanvas = RaftTheme.colors.layerCanvas
                Box(modifier = Modifier.fillMaxSize()) {
                    // Simulated underlying channel header with bright magenta fill; any translucency
                    // in the backdrop lets this bleed through behind the source strip or top safe area.
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(96.dp)
                                .background(Color.Magenta),
                    )
                    RaftMobileWebPanelBackdrop(
                        slideProgress = 1f,
                        fullscreen = fullscreen,
                        source = RaftMobileWebPanelSource.Surface("Apps"),
                        topSafeArea = 24.dp,
                        onCollapse = { collapses += 1 },
                    )
                }
            }
        }
        waitForIdle()

        val sampleX = with(density) { 360.dp.toPx() }.toInt()
        val safeAreaY = with(density) { 12.dp.toPx() }.toInt()
        val stripRowY = with(density) { 44.dp.toPx() }.toInt()

        // Default88: source strip is visible and backdrop completely masks the magenta header.
        onNodeWithTag(RAFT_WEB_PANEL_SOURCE_STRIP_TAG).assertIsDisplayed()
        val defaultPixels = onRoot().captureToImage().toPixelMap()
        assertEquals(expectedCanvas, defaultPixels[sampleX, safeAreaY])
        assertEquals(expectedCanvas, defaultPixels[sampleX, stripRowY])

        // Tapping the backdrop collapses the panel.
        onNodeWithTag(RAFT_WEB_PANEL_BACKDROP_TAG).performClick()
        assertEquals(1, collapses)

        // Fullscreen: source strip hides and backdrop still completely masks the top safe area.
        runOnIdle { fullscreen = true }
        waitForIdle()
        onNodeWithTag(RAFT_WEB_PANEL_SOURCE_STRIP_TAG).assertDoesNotExist()
        val fullscreenPixels = onRoot().captureToImage().toPixelMap()
        assertEquals(expectedCanvas, fullscreenPixels[sampleX, safeAreaY])
        assertEquals(expectedCanvas, fullscreenPixels[sampleX, stripRowY])
    }

    @Test
    fun bottomBarBackAndForwardReflectHistoryAndInvokeCallbacks() = runRobolectricComposeUiTest {
        val history = mutableListOf("https://deck.example/page-1")
        var index by mutableStateOf(0)
        val callbacks = mutableListOf<String>()

        setContent {
            RaftDesignSystemTheme {
                val canGoBack = index > 0
                val canGoForward = index < history.lastIndex
                RaftMobileWebPanelBottomBar(
                    isRaftApp = true,
                    currentUrl = history[index],
                    appName = "Deck",
                    appLogoUrl = null,
                    signedInAsName = "artin",
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                    onGoBack = {
                        callbacks += "back"
                        if (index > 0) index -= 1
                    },
                    onGoForward = {
                        callbacks += "forward"
                        if (index < history.lastIndex) index += 1
                    },
                    onClose = { callbacks += "close" },
                    onShare = {},
                    onCopyLink = {},
                    onOpenInBrowser = null,
                )
            }
        }

        // Initial page 1: both Back and Forward are visible and disabled; app identity remains visible.
        onNodeWithTag(RAFT_WEB_PANEL_APP_NAME_TAG).assertIsDisplayed()
        onNodeWithTag(RAFT_WEB_PANEL_SIGNED_IN_LABEL_TAG).assertIsDisplayed()
        onNodeWithTag(RAFT_WEB_PANEL_BACK_BUTTON_TAG).assertIsDisplayed()
        onNodeWithTag(RAFT_WEB_PANEL_FORWARD_BUTTON_TAG).assertIsDisplayed()
        onNodeWithContentDescription("Back").assertIsDisplayed().assertIsNotEnabled()
        onNodeWithContentDescription("Forward").assertIsDisplayed().assertIsNotEnabled()
        onNodeWithContentDescription("Back").performClick()
        onNodeWithContentDescription("Forward").performClick()
        assertEquals(emptyList(), callbacks)

        // Navigate from page 1 -> page 2: Back enabled, Forward disabled.
        runOnIdle {
            history += "https://deck.example/page-2"
            index = 1
        }
        onNodeWithContentDescription("Back").assertIsEnabled()
        onNodeWithContentDescription("Forward").assertIsNotEnabled()

        // Tap Back -> returns to page 1; Back disabled, Forward enabled.
        onNodeWithContentDescription("Back").performClick()
        runOnIdle { assertEquals("https://deck.example/page-1", history[index]) }
        onNodeWithContentDescription("Back").assertIsNotEnabled()
        onNodeWithContentDescription("Forward").assertIsEnabled()

        // Tap Forward -> returns to page 2; Back enabled, Forward disabled.
        onNodeWithContentDescription("Forward").performClick()
        runOnIdle { assertEquals("https://deck.example/page-2", history[index]) }
        onNodeWithContentDescription("Back").assertIsEnabled()
        onNodeWithContentDescription("Forward").assertIsNotEnabled()

        assertEquals(listOf("back", "forward"), callbacks)
    }

    @Test
    fun overflowMenuExposesReloadAndForwardToRaftActions() = runRobolectricComposeUiTest {
        val actions = mutableListOf<String>()

        setContent {
            RaftDesignSystemTheme {
                RaftMobileWebPanelBottomBar(
                    isRaftApp = true,
                    currentUrl = "https://deck.example/views/19",
                    appName = "Deck",
                    appLogoUrl = null,
                    signedInAsName = "artin",
                    onClose = { actions += "close" },
                    onShare = { actions += "share" },
                    onCopyLink = { actions += "copy" },
                    onOpenInBrowser = null,
                    onReload = { actions += "reload" },
                    onForwardToRaft = { actions += "forward_to_raft" },
                )
            }
        }

        onNodeWithTag(RAFT_WEB_PANEL_MORE_BUTTON_TAG).performClick()
        onNodeWithText("Reload").assertIsDisplayed().performClick()

        onNodeWithTag(RAFT_WEB_PANEL_MORE_BUTTON_TAG).performClick()
        onNodeWithText("Forward to Raft").assertIsDisplayed().performClick()

        assertEquals(listOf("reload", "forward_to_raft"), actions)
    }

    @Test
    fun jsDialogHostHandlesAlertConfirmAndPromptSingleShot() = runRobolectricComposeUiTest {
        val state = RaftWebJsDialogState(initialVisible = true)
        val outcomes = mutableListOf<WebJsDialogResult>()

        setContent {
            RaftDesignSystemTheme {
                RaftWebJsDialogHost(state = state)
            }
        }

        // 1. alert() -> shows OK only, clicking OK settles with AlertDismissed once.
        runOnIdle {
            state.show(
                WebJsDialogRequest(
                    kind = WebJsDialogKind.Alert,
                    originUrl = "https://docs.example.com/guide",
                    message = "Changes saved",
                    onResult = { outcomes += it },
                ),
            )
        }
        onNodeWithTag(RAFT_WEB_JS_DIALOG_TAG).assertIsDisplayed()
        onNodeWithText("docs.example.com").assertIsDisplayed()
        onNodeWithText("Changes saved").assertIsDisplayed()
        onNodeWithTag(RAFT_WEB_JS_DIALOG_CANCEL_TAG).assertDoesNotExist()
        onNodeWithTag(RAFT_WEB_JS_DIALOG_OK_TAG).performClick()
        onNodeWithTag(RAFT_WEB_JS_DIALOG_TAG).assertDoesNotExist()

        // 2. confirm() -> clicking Cancel settles with Confirm(false).
        runOnIdle {
            state.show(
                WebJsDialogRequest(
                    kind = WebJsDialogKind.Confirm,
                    originUrl = "https://docs.example.com/guide",
                    message = "Delete draft?",
                    onResult = { outcomes += it },
                ),
            )
        }
        onNodeWithTag(RAFT_WEB_JS_DIALOG_CANCEL_TAG).assertIsDisplayed().performClick()
        onNodeWithTag(RAFT_WEB_JS_DIALOG_TAG).assertDoesNotExist()

        // 3. prompt() -> edits default value and confirms with updated text.
        runOnIdle {
            state.show(
                WebJsDialogRequest(
                    kind = WebJsDialogKind.Prompt,
                    originUrl = "https://docs.example.com/guide",
                    message = "Rename file",
                    defaultPromptText = "old.txt",
                    onResult = { outcomes += it },
                ),
            )
        }
        onNodeWithTag(RAFT_WEB_JS_DIALOG_INPUT_TAG).assertIsDisplayed().performTextReplacement("new.txt")
        onNodeWithTag(RAFT_WEB_JS_DIALOG_OK_TAG).performClick()
        onNodeWithTag(RAFT_WEB_JS_DIALOG_TAG).assertDoesNotExist()

        assertEquals(
            listOf(
                WebJsDialogResult.AlertDismissed,
                WebJsDialogResult.Confirm(false),
                WebJsDialogResult.Prompt("new.txt"),
            ),
            outcomes,
        )
    }
}
