package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.webcontainer.WebJsDialogKind
import build.raft.webcontainer.WebJsDialogRequest
import build.raft.webcontainer.WebJsDialogResult
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun outboundChildTopBarDisplaysOnlyDomainAndOffersOpenInBrowser() = runRobolectricComposeUiTest {
        var currentUrl by mutableStateOf("https://www.docs.example.com/push/ios-setup")
        val actions = mutableListOf<String>()

        setContent {
            RaftDesignSystemTheme {
                RaftAppBrowserTopBar(
                    isRaftApp = false,
                    isOutboundChild = true,
                    currentUrl = currentUrl,
                    appName = "IgnoredApp",
                    appLogoUrl = null,
                    signedInAsName = "IgnoredUser",
                    onBack = { actions += "back" },
                    onClosePage = { actions += "close" },
                    onReload = { actions += "reload" },
                    onShare = { actions += "share" },
                    onCopyLink = { actions += "copy" },
                    onOpenInBrowser = { actions += "open_browser" },
                )
            }
        }

        // Cross-origin outbound child page displays ONLY the formatted domain (no App name or Signed in as).
        onNodeWithText("docs.example.com").assertIsDisplayed()
        onNodeWithText("IgnoredApp").assertDoesNotExist()
        onNodeWithText("Signed in as IgnoredUser").assertDoesNotExist()
        onNodeWithTag(RAFT_APP_BROWSER_SWITCHER_BUTTON_TAG).assertDoesNotExist()

        runOnIdle { currentUrl = "https://bücher.example/login" }
        onNodeWithText("xn--bcher-kva.example").assertIsDisplayed()
        val spoofHost = "accounts.google.com.sign-in.account-recovery.evil-example.net"
        runOnIdle { currentUrl = "https://$spoofHost/oauth" }
        onNodeWithTag(RAFT_WEB_PANEL_HOST_LABEL_TAG).assertIsDisplayed()
        onNodeWithText(spoofHost).assertIsDisplayed()

        // Overflow menu contains Reload, Share, Copy link, Open in browser (and no Close page).
        onNodeWithTag(RAFT_WEB_PANEL_MORE_BUTTON_TAG).performClick()
        onNodeWithText("Reload").assertIsDisplayed()
        onNodeWithText("Share").assertIsDisplayed()
        onNodeWithText("Copy link").assertIsDisplayed()
        onNodeWithText("Close page").assertDoesNotExist()
        onNodeWithText("Open in browser").assertIsDisplayed().performClick()

        // Leading back arrow returns to parent App.
        onNodeWithTag(RAFT_APP_BROWSER_BACK_NAV_TAG).performClick()

        assertEquals(listOf("open_browser", "back"), actions)
    }

    @Test
    fun raftAppTopBarShowsAppIdentitySignedInAsTabCountSwitcherAndClosePage() = runRobolectricComposeUiTest {
        val actions = mutableListOf<String>()

        setContent {
            RaftDesignSystemTheme {
                RaftAppBrowserTopBar(
                    isRaftApp = true,
                    isOutboundChild = false,
                    currentUrl = "https://tracker.example/views/19",
                    appName = "Tracker",
                    appLogoUrl = null,
                    signedInAsName = "Mia",
                    openTabCount = 3,
                    onOpenSwitcher = { actions += "switcher" },
                    onBack = { actions += "suspend" },
                    onClosePage = { actions += "close_page" },
                    onReload = { actions += "reload" },
                    onForwardToRaft = { actions += "forward_to_raft" },
                    onShare = { actions += "share" },
                    onCopyLink = { actions += "copy" },
                    onOpenInBrowser = { actions += "open_browser" },
                )
            }
        }

        onNodeWithTag(RAFT_WEB_PANEL_APP_NAME_TAG).assertIsDisplayed()
        onNodeWithText("Tracker").assertIsDisplayed()
        onNodeWithTag(RAFT_WEB_PANEL_SIGNED_IN_LABEL_TAG).assertIsDisplayed()
        onNodeWithText("Signed in as Mia").assertIsDisplayed()

        // Multi-page [3] switcher button is shown when >= 2 pages are open.
        onNodeWithTag(RAFT_APP_BROWSER_SWITCHER_BUTTON_TAG).assertIsDisplayed().performClick()

        // Overflow menu includes Reload, Forward to Raft, Open in browser, and Close page.
        onNodeWithTag(RAFT_WEB_PANEL_MORE_BUTTON_TAG).performClick()
        onNodeWithText("Reload").assertIsDisplayed()
        onNodeWithText("Forward to Raft").assertIsDisplayed().performClick()

        onNodeWithTag(RAFT_WEB_PANEL_MORE_BUTTON_TAG).performClick()
        onNodeWithText("Close page").assertIsDisplayed().performClick()

        onNodeWithTag(RAFT_APP_BROWSER_BACK_NAV_TAG).performClick()

        assertEquals(listOf("switcher", "forward_to_raft", "close_page", "suspend"), actions)
    }

    @Test
    fun topBarAlignsWithNavStackShowingQuickExitOnlyWhenWebHistoryExists() = runRobolectricComposeUiTest {
        var canGoBack by mutableStateOf(false)
        var inColumn by mutableStateOf(false)
        val callbacks = mutableListOf<String>()

        setContent {
            RaftDesignSystemTheme {
                RaftAppBrowserTopBar(
                    isRaftApp = true,
                    isOutboundChild = false,
                    currentUrl = "https://stamp.build/pulls/42",
                    appName = "Stamp",
                    appLogoUrl = null,
                    signedInAsName = "artin",
                    inColumn = inColumn,
                    canGoBack = canGoBack,
                    onBack = {
                        if (canGoBack) {
                            callbacks += "web_back"
                            canGoBack = false
                        } else {
                            callbacks += "exit_to_raft"
                        }
                    },
                    onExitToHost = { callbacks += "quick_exit_to_raft" },
                    onClosePage = { callbacks += "close_page" },
                    onReload = { callbacks += "reload" },
                    onShare = { callbacks += "share" },
                    onCopyLink = { callbacks += "copy" },
                    onOpenInBrowser = { callbacks += "open_browser" },
                )
            }
        }

        // At root page on phone: only ← is shown (no redundant × next to it); tapping ← returns to Raft.
        onNodeWithTag(RAFT_APP_BROWSER_BACK_NAV_TAG).assertIsDisplayed()
        onNodeWithTag(RAFT_APP_BROWSER_EXIT_NAV_TAG).assertDoesNotExist()
        onNodeWithTag(RAFT_APP_BROWSER_BACK_NAV_TAG).performClick()

        // Inside sub-page with web history: both ← (web back) and compact × (1-tap return to Raft) appear.
        runOnIdle { canGoBack = true }
        onNodeWithTag(RAFT_APP_BROWSER_BACK_NAV_TAG).assertIsDisplayed()
        onNodeWithTag(RAFT_APP_BROWSER_EXIT_NAV_TAG).assertIsDisplayed().performClick()
        onNodeWithTag(RAFT_APP_BROWSER_BACK_NAV_TAG).performClick()
        onNodeWithTag(RAFT_APP_BROWSER_EXIT_NAV_TAG).assertDoesNotExist()

        // In wide side column: ← stays hidden at root and appears when canGoBack, while trailing × exits column.
        runOnIdle {
            inColumn = true
            canGoBack = false
        }
        onNodeWithTag(RAFT_APP_BROWSER_BACK_NAV_TAG).assertDoesNotExist()
        onNodeWithTag(RAFT_APP_BROWSER_EXIT_NAV_TAG).assertDoesNotExist()
        onNodeWithContentDescription("Close").assertIsDisplayed().performClick()

        runOnIdle { canGoBack = true }
        onNodeWithTag(RAFT_APP_BROWSER_BACK_NAV_TAG).assertIsDisplayed().performClick()
        onNodeWithTag(RAFT_APP_BROWSER_BACK_NAV_TAG).assertDoesNotExist()

        assertEquals(
            listOf("exit_to_raft", "quick_exit_to_raft", "web_back", "quick_exit_to_raft", "web_back"),
            callbacks,
        )
    }

    @Test
    fun floatingPillSingleAndMultiPageAndSwitcherSheetActionsWork() = runRobolectricComposeUiTest {
        val tab1 =
            RaftAppBrowserTab(
                tabId = 1L,
                launchUrl = "https://stamp.build/pulls/1",
                currentUrl = "https://stamp.build/pulls/1",
                pageTitle = "PR #1",
                appEntryToken = "tok-1",
                scopeOrigin = "https://stamp.build",
                appName = "Stamp",
                isLive = true,
                lastUsedSeq = 2L,
            )
        val tab2 =
            RaftAppBrowserTab(
                tabId = 2L,
                launchUrl = "https://deck.example/board",
                currentUrl = "https://deck.example/board",
                pageTitle = "Sprint Board",
                appEntryToken = "tok-2",
                scopeOrigin = "https://deck.example",
                appName = "Deck",
                isLive = false,
                lastUsedSeq = 1L,
            )
        var tabs by mutableStateOf(listOf(tab1))
        var sheetOpen by mutableStateOf(false)
        val events = mutableListOf<String>()

        setContent {
            RaftDesignSystemTheme {
                Column(modifier = Modifier.fillMaxWidth()) {
                    RaftFloatingBrowserTabPill(
                        tabs = tabs,
                        onClick = {
                            if (tabs.size == 1) {
                                events += "resume:${tabs.first().tabId}"
                            } else {
                                sheetOpen = true
                            }
                        },
                    )
                    if (sheetOpen) {
                        RaftAppBrowserSwitcherSheet(
                            tabs = tabs,
                            activeTabId = null,
                            onSelectTab = { selected ->
                                events += "select:${selected.tabId}"
                                sheetOpen = false
                            },
                            onCloseTab = { id ->
                                events += "close:$id"
                                tabs = tabs.filterNot { it.tabId == id }
                            },
                            onCloseAll = {
                                events += "close_all"
                                tabs = emptyList()
                                sheetOpen = false
                            },
                            onDismiss = { sheetOpen = false },
                        )
                    }
                }
            }
        }

        // Single suspended page: no count badge, tap immediately resumes.
        onNodeWithTag(RAFT_FLOATING_TAB_PILL_TAG).assertIsDisplayed()
        onNodeWithTag(RAFT_FLOATING_TAB_PILL_COUNT_TAG, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(RAFT_FLOATING_TAB_PILL_TAG).performClick()
        assertEquals(listOf("resume:1"), events)

        // Two suspended pages: count badge [2] visible, tap opens multi-page switcher sheet.
        runOnIdle { tabs = listOf(tab1, tab2) }
        onNodeWithTag(RAFT_FLOATING_TAB_PILL_COUNT_TAG, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(RAFT_FLOATING_TAB_PILL_TAG).performClick()

        onNodeWithTag(RAFT_APP_BROWSER_SWITCHER_SHEET_TAG).assertIsDisplayed()
        onNodeWithText("PR #1").assertIsDisplayed()
        onNodeWithText("Sprint Board").assertIsDisplayed()
        onNodeWithText("Active").assertIsDisplayed()
        onNodeWithText("Hibernated").assertIsDisplayed()

        // Close individual page via per-row ×, then Close all.
        onNodeWithTag(raftAppBrowserSwitcherCloseTag(2L)).performClick()
        onNodeWithTag(RAFT_APP_BROWSER_SWITCHER_CLOSE_ALL_TAG).performClick()
        assertEquals(listOf("resume:1", "close:2", "close_all"), events)
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
