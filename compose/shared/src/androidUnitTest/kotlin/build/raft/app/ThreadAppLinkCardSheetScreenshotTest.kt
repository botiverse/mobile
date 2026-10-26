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
import build.raft.app.data.AppLinkResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftAppLinkCard
import build.raft.app.data.RaftAppLinkCardElement.Badge
import build.raft.app.data.RaftAppLinkCardElement.Stack
import build.raft.app.data.RaftAppLinkCardElement.Text
import build.raft.app.data.RaftAppLinkPreviewApi
import build.raft.app.data.RaftAppLinkTextTone
import build.raft.app.data.SharedAppLinkPreviewState
import build.raft.app.data.SharedAppLinkPreviewStore
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
 * #proj-mobile: App link item cards in the thread, light and dark. Row one: a message whose
 * link's App sent a card body; its cyan chip opens the card. Row two: a preview with only its
 * title, the chip alone (a tap opens the link). Under them the sheet row one's chip opens (its
 * surface, without the window): the App's name, [RaftAppLinkCardView], Open Link. Reports land in
 * `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class ThreadAppLinkCardSheetScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun appLinkCardLight() = capture("thread.app-link-card-sheet", RaftAppAppearance.Light)

    @Test
    fun appLinkCardDark() = capture("thread.app-link-card-sheet.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            val host = remember { cardFixtureHost() }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalThreadAppLinkPreviewHost provides host,
            ) {
                RaftDesignSystemTheme { CardRows() }
            }
        }
    }
}

private const val CardViewer = "user-niko:server-1"
private const val CardConversation = "channel-1"
private const val WithCardMessage = "0b5a1f8e-6d0c-4c3e-9a51-0e1b2c3d4e11"
private const val ChipOnlyMessage = "0b5a1f8e-6d0c-4c3e-9a51-0e1b2c3d4e12"

private const val LinearIssue = "https://linear.app/raft/issue/RAFT-812"
private const val StampPr = "https://stamp.build/botiverse/mobile/pull/3654"

private val withCardBody = "Blocked on $LinearIssue until the tray lands"
private val chipOnlyBody = "Merged: $StampPr"

private val linearCard =
    AppLinkResult.Card(
        url = LinearIssue,
        app = AppLinkApp("Linear", null),
        title = "Composer tray loses the draft on rotate",
        card =
            RaftAppLinkCard(
                root = "root",
                elements =
                    mapOf(
                        "root" to Stack(listOf("state", "who", "id")),
                        "state" to Badge("In Progress"),
                        "who" to Text("Assigned to Niko · due Friday"),
                        "id" to Text("RAFT-812", RaftAppLinkTextTone.Muted),
                    ),
            ),
    )
private val stampTitleOnly = AppLinkResult.Card(StampPr, AppLinkApp("Stamp", null), "Fix the composer tray (#3654)")

private fun cardFixtureHost(): ThreadAppLinkPreviewHost {
    val state =
        SharedAppLinkPreviewState(
            entries =
                mapOf(
                    appLinkReadScope(CardViewer, WithCardMessage, withCardBody) to
                        AppLinkLoaded(results = mapOf(LinearIssue to linearCard), epochs = emptyMap()),
                    appLinkReadScope(CardViewer, ChipOnlyMessage, chipOnlyBody) to
                        AppLinkLoaded(results = mapOf(StampPr to stampTitleOnly), epochs = emptyMap()),
                ),
        )
    return ThreadAppLinkPreviewHost(
        store = SharedAppLinkPreviewStore(IdleAppLinkPreviewApi, CoroutineScope(Dispatchers.Unconfined), initialState = state),
        viewerKey = CardViewer,
        serverId = "server-1",
        conversationId = CardConversation,
        toast = {},
    )
}

private object IdleAppLinkPreviewApi : RaftAppLinkPreviewApi {
    override suspend fun readPreviews(messageId: String, serverId: String): HomeNetworkResponse = awaitCancellation()

    override suspend fun allow(appId: String, serverId: String): HomeNetworkResponse = awaitCancellation()

    override suspend fun decline(appId: String, serverId: String): HomeNetworkResponse = awaitCancellation()
}

@Composable
private fun CardRows() {
    val rows =
        listOf(WithCardMessage to withCardBody, ChipOnlyMessage to chipOnlyBody).mapIndexed { index, (id, body) ->
            RaftThreadMessageItem(
                id = id,
                authorId = "user-niko",
                authorName = "Niko",
                authorKind = "human",
                avatarLabel = "N",
                timestampLabel = "2026-10-26T09:4$index:00Z",
                body = body,
            )
        }
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        rows.forEachIndexed { index, message ->
            ThreadMessageCard(
                message = message,
                messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                compactConsecutiveMessages = true,
                suppressRepeatedAuthor = shouldSuppressCompactThreadAuthor(rows.getOrNull(index - 1), message, localOffsetMinutes = 0),
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
        ThreadAppLinkCardSheet(
            card = linearCard,
            onDismiss = {},
            onOpenLink = {},
            inWindow = false,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}
