package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLinkActionDescription
import build.raft.app.data.AppLinkActionResult
import build.raft.app.data.AppLinkApp
import build.raft.app.data.AppLinkExecuted
import build.raft.app.data.AppLinkPrepared
import build.raft.app.data.AppLinkResult
import build.raft.app.data.CardStack
import build.raft.app.data.RaftAppLinkCardV2
import build.raft.app.data.RaftLinkCardButtonAction
import build.raft.app.data.RaftLinkCardButtonVariant
import build.raft.app.data.RaftLinkCardV2Element
import build.raft.app.markdown.RaftAppLinkChipHost
import build.raft.app.markdown.RaftAppLinkHoverTag
import build.raft.app.markdown.RaftMarkdownAppLinkHoverCards
import build.raft.app.markdown.raftMarkdownAppLinkSpans
import build.raft.app.resources.Res
import build.raft.app.resources.stringResource
import build.raft.app.resources.thread_link_preview_connect_action
import build.raft.app.resources.thread_link_preview_open_link
import build.raft.designsystem.components.RaftPreviewCardState
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies mouse/trackpad hover App link cards ([RaftMarkdownAppLinkHoverCards] +
 * [ThreadAppLinkHoverCardHost], #proj-mobile:#415):
 * - v2 hover cards render App `execute` buttons, `read`/Refresh and `navigate` links (in `⋯`),
 *   execute actions via the row-scoped [ThreadAppLinkCardPress], and track hover engagement.
 * - `confirm: "prepare"` actions dismiss the hover card while keeping Raft's confirmation dialog
 *   open over the thread via [ThreadAppLinkCardPopoverHost].
 * - Link clicks route through `openThreadAppLinkAddress` (`onOpenAppEntry` for installed Raft Apps
 *   including cross-origin `lookupInstalledApp`, `onOpenInSystemBrowser` for `connect_account`)
 *   instead of raw `LocalUriHandler`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w840dp-h600dp-mdpi")
class RaftMarkdownAppLinkHoverCardUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val githubPrUrl = "https://github.com/botiverse/mobile/pull/5911"
    private val stampPrUrl = "https://stamp.build/github/botiverse/mobile/pull/5911"
    private val now = 100 * 60_000L

    private fun hoverAnnotatedText(url: String, label: String = "PR #5911"): AnnotatedString =
        buildAnnotatedString {
            pushStringAnnotation(RaftAppLinkHoverTag, url)
            append(label)
            pop()
        }

    @Test
    fun hoverCardRunsExecuteAndRefreshActionsAndRoutesInstalledAppInApp() =
        runRobolectricComposeUiTest {
            val executed = mutableListOf<String>()
            val rereadUrls = mutableListOf<String>()
            val openedAppEntries = mutableListOf<Pair<String, String?>>()
            val fallbackLinks = mutableListOf<String>()
            val lookedUpOrigins = mutableListOf<String>()
            var moreLabel = ""

            val card =
                AppLinkResult.Card(
                    url = githubPrUrl,
                    app = AppLinkApp("Stamp", null),
                    title = "Fix popover parity (#5911)",
                    cardV2 =
                        RaftAppLinkCardV2(
                            root = "root",
                            elements =
                                mapOf(
                                    "root" to CardStack(children = listOf("approve", "refresh", "open")),
                                    "approve" to
                                        RaftLinkCardV2Element.Button(
                                            label = "Approve",
                                            variant = RaftLinkCardButtonVariant.Default,
                                            action = RaftLinkCardButtonAction.Execute("approve", confirm = false, undoWindowMs = 0),
                                        ),
                                    "refresh" to
                                        RaftLinkCardV2Element.Button(
                                            label = "Refresh",
                                            variant = RaftLinkCardButtonVariant.Ghost,
                                            action = RaftLinkCardButtonAction.Read,
                                        ),
                                    "open" to
                                        RaftLinkCardV2Element.Button(
                                            label = "Open in Stamp",
                                            variant = RaftLinkCardButtonVariant.Ghost,
                                            action = RaftLinkCardButtonAction.Navigate(stampPrUrl),
                                        ),
                                ),
                        ),
                    handles = mapOf("approve" to "h-approve"),
                    readAtMillis = now,
                    businessRevision = "br-1",
                )
            val results = mapOf(githubPrUrl to card)
            val state = RaftPreviewCardState(openDelayMillis = 0L, closeDelayMillis = 0L)
            lateinit var threadCards: ThreadAppLinkCards

            setContent {
                val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                    RaftDesignSystemTheme {
                        moreLabel = RaftTheme.strings.moreActions
                        val actionHost =
                            remember {
                                ThreadAppLinkCardActionHost(
                                    prepare = { _, _ -> error("not a prepare action") },
                                    execute = { handle, _, _ ->
                                        executed += handle
                                        AppLinkActionResult.Ok(AppLinkExecuted("op-1", "done", null))
                                    },
                                    reread = { url ->
                                        rereadUrls += url
                                        true
                                    },
                                    toast = {},
                                    nowMillis = { now },
                                )
                            }
                        val cards =
                            rememberThreadAppLinkCards(
                                messageId = "m-hover-1",
                                preferredServerId = "srv-1",
                                onOpenLink = { fallbackLinks += it },
                                onOpenAppEntry = { url, request -> openedAppEntries += url to request.appName },
                                lookupInstalledApp = { origin ->
                                    lookedUpOrigins += origin
                                    if (origin == "https://stamp.build") {
                                        RaftInstalledAppSyncLookup.Matched(
                                            match = RaftInstalledAppOriginMatch(origin = origin, appName = "Stamp", logoUrl = null),
                                            serverId = "srv-1",
                                        )
                                    } else {
                                        RaftInstalledAppSyncLookup.KnownNotInstalled
                                    }
                                },
                            )
                        threadCards = cards
                        val presses = rememberThreadAppLinkChipCardPresses(results, cards, actionHost)
                        val currentPresses = rememberUpdatedState(presses)
                        val hoverHost = remember(cards) { ThreadAppLinkHoverCardHost(cards = cards, pressesState = currentPresses) }
                        val chipHost = remember(results, hoverHost) { RaftAppLinkChipHost(results = results, onAsk = {}, hoverCardHost = hoverHost) }
                        val hoverCards = remember { RaftMarkdownAppLinkHoverCards(hoverAnnotatedText(githubPrUrl).raftMarkdownAppLinkSpans(), listOf(state)) }

                        Box(Modifier.fillMaxSize()) {
                            hoverCards.Popups(chipHost)
                            ThreadAppLinkCardPopoverHost(results = results, cards = cards, actions = actionHost, presses = presses)
                        }
                    }
                }
            }

            // Open the hover card as a mouse pointer resting on the chip does.
            state.pin()
            waitForIdle()

            assertTrue(threadCards.hasOpenHoverCard, "open hover card must mark message row engaged")
            // Hover card title stays non-clickable (unlike phone tap popovers).
            onNode(hasText(card.title) and hasClickAction()).assertDoesNotExist()
            // App v2 Execute button is rendered in the row and executes via ThreadAppLinkCardActionHost.
            onNode(hasText("Approve")).assertExists()
            onNode(hasText("Approve")).performClick()
            waitForIdle()
            assertEquals(listOf("h-approve"), executed)

            // Clear post-execute reread so the Refresh assertion proves Refresh itself triggered reread.
            rereadUrls.clear()
            onNodeWithContentDescription(moreLabel).performClick()
            waitForIdle()
            onNode(hasText("Refresh")).performClick()
            waitForIdle()
            assertEquals(listOf(githubPrUrl), rereadUrls, "Refresh in hover card ⋯ menu must trigger reread")

            // Clicking cross-origin Open in Stamp dismisses the hover card and routes in-app via lookupInstalledApp -> onOpenAppEntry.
            onNodeWithContentDescription(moreLabel).performClick()
            waitForIdle()
            onNode(hasText("Open in Stamp")).performClick()
            waitForIdle()
            assertFalse(state.isOpen, "opening a link from the hover card must dismiss it")
            assertFalse(threadCards.hasOpenHoverCard, "dismissed hover card must clear hover engagement")
            assertEquals(listOf("https://stamp.build"), lookedUpOrigins)
            assertEquals(listOf<Pair<String, String?>>(stampPrUrl to "Stamp"), openedAppEntries)
            assertEquals(emptyList<String>(), fallbackLinks)
        }

    @Test
    fun prepareConfirmActionDismissesHoverCardAndKeepsConfirmationDialogAlive() =
        runRobolectricComposeUiTest {
            val preparedHandles = mutableListOf<String>()
            val executedHandles = mutableListOf<String>()

            val card =
                AppLinkResult.Card(
                    url = stampPrUrl,
                    app = AppLinkApp("Stamp", null),
                    title = "Deploy release (#5911)",
                    cardV2 =
                        RaftAppLinkCardV2(
                            root = "root",
                            elements =
                                mapOf(
                                    "root" to CardStack(children = listOf("close")),
                                    "close" to
                                        RaftLinkCardV2Element.Button(
                                            label = "Close PR",
                                            variant = RaftLinkCardButtonVariant.Default,
                                            action = RaftLinkCardButtonAction.Execute("close", confirm = true, undoWindowMs = 0),
                                        ),
                                ),
                        ),
                    handles = mapOf("close" to "h-close"),
                    readAtMillis = now,
                    businessRevision = "br-2",
                )
            val results = mapOf(stampPrUrl to card)
            val state = RaftPreviewCardState(openDelayMillis = 0L, closeDelayMillis = 0L)

            setContent {
                val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                    RaftDesignSystemTheme {
                        val actionHost =
                            remember {
                                ThreadAppLinkCardActionHost(
                                    prepare = { handle, _ ->
                                        preparedHandles += handle
                                        AppLinkActionResult.Ok(
                                            AppLinkPrepared(
                                                preparedActionId = "prep-1",
                                                preparedDigest = "digest-1",
                                                description =
                                                    AppLinkActionDescription(
                                                        title = "Confirm close",
                                                        body = "This closes PR #5911.",
                                                        confirmLabel = "Confirm close now",
                                                        cancelLabel = "Cancel",
                                                    ),
                                            ),
                                        )
                                    },
                                    execute = { handle, _, _ ->
                                        executedHandles += handle
                                        AppLinkActionResult.Ok(AppLinkExecuted("op-2", "done", null))
                                    },
                                    reread = { true },
                                    toast = {},
                                    nowMillis = { now },
                                )
                            }
                        val cards =
                            rememberThreadAppLinkCards(
                                messageId = "m-hover-2",
                                preferredServerId = "srv-1",
                                onOpenLink = {},
                            )
                        val presses = rememberThreadAppLinkChipCardPresses(results, cards, actionHost)
                        val currentPresses = rememberUpdatedState(presses)
                        val hoverHost = remember(cards) { ThreadAppLinkHoverCardHost(cards = cards, pressesState = currentPresses) }
                        val chipHost = remember(results, hoverHost) { RaftAppLinkChipHost(results = results, onAsk = {}, hoverCardHost = hoverHost) }
                        val hoverCards = remember { RaftMarkdownAppLinkHoverCards(hoverAnnotatedText(stampPrUrl).raftMarkdownAppLinkSpans(), listOf(state)) }

                        Box(Modifier.fillMaxSize()) {
                            hoverCards.Popups(chipHost)
                            ThreadAppLinkCardPopoverHost(results = results, cards = cards, actions = actionHost, presses = presses)
                        }
                    }
                }
            }

            state.pin()
            waitForIdle()

            onNode(hasText("Close PR")).performClick()
            waitForIdle()

            assertEquals(listOf("h-close"), preparedHandles)
            assertFalse(state.isOpen, "hover card must dismiss once Raft's confirmation dialog opens")
            // Confirmation dialog stays alive via row-scoped ThreadAppLinkCardPopoverHost even after hover card closes.
            onNode(hasText("Confirm close")).assertExists()
            onNode(hasText("Confirm close now")).performClick()
            waitForIdle()
            assertEquals(listOf("h-close"), executedHandles)
        }

    @Test
    fun connectAccountHoverCardRoutesToSystemBrowserAndCardWithoutActionsKeepsOpenLinkInRow() =
        runRobolectricComposeUiTest {
            val connectUrl = "https://stamp.build/connect"
            val systemBrowserUrls = mutableListOf<String>()
            val fallbackLinks = mutableListOf<String>()
            var connectLabel = ""
            var openLinkLabel = ""

            val connectCard =
                AppLinkResult.Card(
                    url = stampPrUrl,
                    app = AppLinkApp("Stamp", null),
                    title = "PR #5911",
                    connectUrl = connectUrl,
                )
            val plainUrl = "https://linear.app/botiverse/issue/ENG-42"
            val plainCard =
                AppLinkResult.Card(
                    url = plainUrl,
                    app = AppLinkApp("Linear", null),
                    title = "ENG-42",
                    cardV2 = RaftAppLinkCardV2(root = "root", elements = mapOf("root" to CardStack(children = emptyList()))),
                )
            val results = mapOf(stampPrUrl to connectCard, plainUrl to plainCard)
            val connectState = RaftPreviewCardState(openDelayMillis = 0L, closeDelayMillis = 0L)
            val plainState = RaftPreviewCardState(openDelayMillis = 0L, closeDelayMillis = 0L)
            val annotated =
                buildAnnotatedString {
                    pushStringAnnotation(RaftAppLinkHoverTag, stampPrUrl)
                    append("PR")
                    pop()
                    append(" ")
                    pushStringAnnotation(RaftAppLinkHoverTag, plainUrl)
                    append("ENG-42")
                    pop()
                }

            setContent {
                val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                    RaftDesignSystemTheme {
                        connectLabel = stringResource(Res.string.thread_link_preview_connect_action)
                        openLinkLabel = stringResource(Res.string.thread_link_preview_open_link)
                        val cards =
                            rememberThreadAppLinkCards(
                                messageId = "m-hover-3",
                                preferredServerId = "srv-1",
                                onOpenLink = { fallbackLinks += it },
                                onOpenInSystemBrowser = { systemBrowserUrls += it },
                            )
                        val presses = rememberThreadAppLinkChipCardPresses(results, cards, actions = null)
                        val currentPresses = rememberUpdatedState(presses)
                        val hoverHost = remember(cards) { ThreadAppLinkHoverCardHost(cards = cards, pressesState = currentPresses) }
                        val chipHost = remember(results, hoverHost) { RaftAppLinkChipHost(results = results, onAsk = {}, hoverCardHost = hoverHost) }
                        val hoverCards = remember { RaftMarkdownAppLinkHoverCards(annotated.raftMarkdownAppLinkSpans(), listOf(connectState, plainState)) }

                        Box(Modifier.fillMaxSize()) {
                            hoverCards.Popups(chipHost)
                        }
                    }
                }
            }

            connectState.pin()
            waitForIdle()
            onNode(hasText(connectLabel)).performClick()
            waitForIdle()
            assertEquals(listOf(connectUrl), systemBrowserUrls)
            assertFalse(connectState.isOpen)

            plainState.pin()
            waitForIdle()
            // Hover card with no App actions keeps Open Link directly in the button row.
            onNode(hasText(openLinkLabel)).performClick()
            waitForIdle()
            assertEquals(listOf(plainUrl), fallbackLinks)
            assertFalse(plainState.isOpen)
        }
}
