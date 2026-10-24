package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLinkApp
import build.raft.app.data.AppLinkLoaded
import build.raft.app.data.AppLinkPromptApp
import build.raft.app.data.AppLinkResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftAppLinkPreviewApi
import build.raft.app.data.SharedAppLinkPreviewState
import build.raft.app.data.SharedAppLinkPreviewStore
import build.raft.app.data.appLinkAppKey
import build.raft.app.data.appLinkConversationKey
import build.raft.app.data.appLinkReadScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin #20 lane L: App links in a message, light and dark. Row one: a link an App previews, a
 * cyan chip with the App's icon (the generic App glyph: the fixture loads no image), its title
 * and the link's domain, next to a link no App previews. Row two: the first-view question, a
 * grey chip with the shortened address. Row three: just allowed, the App's links wait as
 * pending chips. Under them the question's sheet (its surface, without the window). Nothing
 * moves: the pending chip has no spinner and the sheet is idle. Reports land in
 * `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class ThreadAppLinkPreviewsScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun appLinkPreviewsLight() = capture("thread.app-link-previews", RaftAppAppearance.Light)

    @Test
    fun appLinkPreviewsDark() = capture("thread.app-link-previews.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            val host = remember { appLinkFixtureHost() }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalThreadAppLinkPreviewHost provides host,
            ) {
                RaftDesignSystemTheme { AppLinkPreviewRows() }
            }
        }
    }
}

private const val FixtureViewer = "user-niko:server-1"
private const val FixtureConversation = "channel-1"
private const val CardMessage = "0b5a1f8e-6d0c-4c3e-9a51-0e1b2c3d4e01"
private const val ConsentMessage = "0b5a1f8e-6d0c-4c3e-9a51-0e1b2c3d4e02"
private const val PendingMessage = "0b5a1f8e-6d0c-4c3e-9a51-0e1b2c3d4e03"

private const val StampPr = "https://stamp.build/botiverse/slock/pull/10449"
private const val LinearIssue = "https://linear.app/raft/issue/RAFT-812"
private const val LinearIssueTwo = "https://linear.app/raft/issue/RAFT-813"

private val cardBody = "CI is green on $StampPr — the spec is https://raft-design.botiverse.build/message-item"
private val consentBody = "Tracked in $LinearIssue"
private val pendingBody = "Both are $LinearIssue and $LinearIssueTwo"

private val linear = AppLinkPromptApp(id = "client-linear", name = "Linear", icon = null)

/** Answers already read; a read again (the pending row) never comes back, so the row holds still. */
private fun appLinkFixtureHost(): ThreadAppLinkPreviewHost {
    val conversation = appLinkConversationKey(FixtureViewer, FixtureConversation)
    val consent = AppLinkResult.Consent(LinearIssue, linear)
    val state =
        SharedAppLinkPreviewState(
            entries =
                mapOf(
                    appLinkReadScope(FixtureViewer, CardMessage, cardBody) to
                        AppLinkLoaded(
                            results = mapOf(StampPr to AppLinkResult.Card(StampPr, AppLinkApp("Stamp", null), "Fix the composer tray (#3654)")),
                            epochs = emptyMap(),
                        ),
                    appLinkReadScope(FixtureViewer, ConsentMessage, consentBody) to
                        AppLinkLoaded(results = mapOf(LinearIssue to consent), epochs = emptyMap()),
                ),
            claims = mapOf("$conversation:${linear.id}" to ConsentMessage),
        )
    val store =
        SharedAppLinkPreviewStore(
            api = HangingAppLinkPreviewApi,
            scope = CoroutineScope(Dispatchers.Unconfined),
            initialState = state,
        )
    return ThreadAppLinkPreviewHost(
        store = store,
        viewerKey = FixtureViewer,
        serverId = "server-1",
        conversationId = FixtureConversation,
        toast = {},
    )
}

private object HangingAppLinkPreviewApi : RaftAppLinkPreviewApi {
    override suspend fun readPreviews(messageId: String, serverId: String): HomeNetworkResponse = awaitCancellation()

    override suspend fun allow(appId: String, serverId: String): HomeNetworkResponse = awaitCancellation()

    override suspend fun decline(appId: String, serverId: String): HomeNetworkResponse = awaitCancellation()
}

@Composable
private fun AppLinkPreviewRows() {
    val rows =
        listOf(
            CardMessage to cardBody,
            ConsentMessage to consentBody,
        ).mapIndexed { index, (id, body) -> fixtureMessage(id, body, index) }
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        rows.forEachIndexed { index, message ->
            AppLinkFixtureCard(message, suppress = shouldSuppressCompactThreadAuthor(rows.getOrNull(index - 1), message, localOffsetMinutes = 0))
        }
        // Row three: Linear just allowed (in the fixture's other conversation), its links read again.
        val pendingHost = pendingFixtureHost()
        CompositionLocalProvider(LocalThreadAppLinkPreviewHost provides pendingHost) {
            AppLinkFixtureCard(fixtureMessage(PendingMessage, pendingBody, 2), suppress = true)
        }
        ThreadAppLinkConsentPrompt(
            app = linear,
            url = LinearIssue,
            status = null,
            onDismiss = {},
            onAllow = {},
            onDecline = {},
            onOpenLink = {},
            inWindow = false,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun pendingFixtureHost(): ThreadAppLinkPreviewHost =
    remember {
        val linearKey = appLinkAppKey(FixtureViewer, linear.id)
        val conversation = appLinkConversationKey(FixtureViewer, "channel-2")
        val state =
            SharedAppLinkPreviewState(
                entries =
                    mapOf(
                        appLinkReadScope(FixtureViewer, PendingMessage, pendingBody) to
                            AppLinkLoaded(results = mapOf(LinearIssue to AppLinkResult.Consent(LinearIssue, linear)), epochs = emptyMap()),
                    ),
                epoch = mapOf(linearKey to 1),
                claims = mapOf("$conversation:${linear.id}" to PendingMessage),
            )
        ThreadAppLinkPreviewHost(
            store = SharedAppLinkPreviewStore(HangingAppLinkPreviewApi, CoroutineScope(Dispatchers.Unconfined), initialState = state),
            viewerKey = FixtureViewer,
            serverId = "server-1",
            conversationId = "channel-2",
            toast = {},
        )
    }

private fun fixtureMessage(id: String, body: String, index: Int) =
    RaftThreadMessageItem(
        id = id,
        authorId = "user-niko",
        authorName = "Niko",
        authorKind = "human",
        avatarLabel = "N",
        timestampLabel = "2026-10-24T09:4$index:00Z",
        body = body,
    )

@Composable
private fun AppLinkFixtureCard(message: RaftThreadMessageItem, suppress: Boolean) {
    ThreadMessageCard(
        message = message,
        messageBodyTextStyle = raftMessageBodyTextStyle("md"),
        compactConsecutiveMessages = true,
        suppressRepeatedAuthor = suppress,
        timestampLabel = "9:40 AM",
        onShowActions = {},
        onOpenMember = {},
        onOpenThread = {},
        onOpenTask = {},
        onOpenTaskStatusMenu = {},
        onToggleReaction = {},
        onOpenAttachment = {},
        onOpenMarkdownTag = { _, _ -> },
        onOpenMarkdownUrl = {},
    )
}
