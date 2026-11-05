package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLinkApp
import build.raft.app.data.AppLinkBlockCard
import build.raft.app.data.AppLinkInline
import build.raft.app.data.AppLinkLoaded
import build.raft.app.data.AppLinkResult
import build.raft.app.data.CardBadgeTone
import build.raft.app.data.CardStack
import build.raft.app.data.CardStackDirection
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftAppLinkCardV2
import build.raft.app.data.RaftAppLinkPreviewApi
import build.raft.app.data.RaftLinkCardButtonAction
import build.raft.app.data.RaftLinkCardButtonVariant
import build.raft.app.data.RaftLinkCardTextTone
import build.raft.app.data.RaftLinkCardV2Element
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
 * App link block cards (RFC 076 section 14; raft-design #1010), light and dark, in the message
 * they are drawn in:
 *  - `thread.app-link-block-card`: a link that sits in a sentence and a bare link on its own line,
 *    each with only a block card in its answer, so each stays the author's plain link and the card
 *    follows its paragraph: Raft's header (the App's icon, name and the host), the title that opens
 *    the item, the card's states and details and the More menu (⋯). On a phone the card takes the
 *    message's width; `.wide` is a window of 900dp, where it is capped at 400dp as on the web.
 *  - `thread.app-link-inline-block`: an answer that lists `inline` and a block card: the chip in the
 *    sentence (no hover card) and the card after the paragraph.
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1000dp-xxhdpi")
class ThreadAppLinkBlockCardScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun blockCardLight() = capture("thread.app-link-block-card", RaftAppAppearance.Light, BlockOnlyMessages)

    @Test
    fun blockCardDark() = capture("thread.app-link-block-card.dark", RaftAppAppearance.Dark, BlockOnlyMessages)

    @Test
    @Config(sdk = [34], qualifiers = "w900dp-h1000dp-xxhdpi")
    fun blockCardWideLight() = capture("thread.app-link-block-card.wide", RaftAppAppearance.Light, BlockOnlyMessages)

    @Test
    @Config(sdk = [34], qualifiers = "w900dp-h1000dp-xxhdpi")
    fun blockCardWideDark() = capture("thread.app-link-block-card.wide.dark", RaftAppAppearance.Dark, BlockOnlyMessages)

    @Test
    fun inlineAndBlockLight() = capture("thread.app-link-inline-block", RaftAppAppearance.Light, InlineAndBlockMessages)

    @Test
    fun inlineAndBlockDark() = capture("thread.app-link-inline-block.dark", RaftAppAppearance.Dark, InlineAndBlockMessages)

    private fun capture(name: String, appearance: RaftAppAppearance, messages: List<BlockFixtureMessage>) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            val host = remember(messages) { blockFixtureHost(messages) }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalThreadAppLinkPreviewHost provides host,
            ) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
                        messages.forEachIndexed { index, fixture ->
                            BlockFixtureRow(fixtureBlockMessage(fixture, index), suppress = index > 0)
                        }
                    }
                }
            }
        }
    }
}

private const val BlockViewer = "user-niko:server-1"
private const val BlockConversation = "channel-1"

private class BlockFixtureMessage(val id: String, val body: String, val results: Map<String, AppLinkResult>)

private const val InSentenceUrl = "https://stamp.build/botiverse/slock/pull/12361"
private const val AloneUrl = "https://stamp.build/botiverse/slock/pull/12400"

/** The web's `prCard` shape, without the clock-driven RelativeTime: states, details, a branch, a warning and an Open in Stamp link. */
private fun blockTree(url: String) =
    RaftAppLinkCardV2(
        root = "root",
        elements =
            mapOf(
                "root" to CardStack(children = listOf("states", "people", "branch", "checks", "open")),
                "states" to CardStack(CardStackDirection.Row, wrap = true, children = listOf("review", "ci")),
                "review" to RaftLinkCardV2Element.Badge("In review", CardBadgeTone.Review),
                "ci" to RaftLinkCardV2Element.Badge("CI running", CardBadgeTone.Progress),
                "people" to CardStack(CardStackDirection.Row, children = listOf("author", "lines")),
                "author" to RaftLinkCardV2Element.Text("AppFather", RaftLinkCardTextTone.Muted),
                "lines" to RaftLinkCardV2Element.Text("+322 -94", RaftLinkCardTextTone.Muted),
                "branch" to RaftLinkCardV2Element.Text("agent/link-cards-pr-d-read", mono = true),
                "checks" to RaftLinkCardV2Element.Text("2 checks failing", RaftLinkCardTextTone.Warning),
                "open" to
                    RaftLinkCardV2Element.Button(
                        "Open in Stamp",
                        RaftLinkCardButtonVariant.Default,
                        RaftLinkCardButtonAction.Navigate(url),
                    ),
            ),
    )

private fun blockResult(url: String, title: String, inline: AppLinkInline?) =
    AppLinkResult.Card(
        url = url,
        app = AppLinkApp("Stamp", null),
        title = title,
        inline = inline,
        block = AppLinkBlockCard(title, blockTree(url)),
        chip = inline != null,
    )

private val BlockOnlyMessages =
    listOf(
        BlockFixtureMessage(
            id = "0b5a1f8e-6d0c-4c3e-9a51-0e1b2c3d4f01",
            body = "Review needed on $InSentenceUrl before we cut the build.",
            results = mapOf(InSentenceUrl to blockResult(InSentenceUrl, "#12361 feat(web): an App link card's buttons in one row, the main action at its end", null)),
        ),
        BlockFixtureMessage(
            id = "0b5a1f8e-6d0c-4c3e-9a51-0e1b2c3d4f02",
            body = AloneUrl,
            results = mapOf(AloneUrl to blockResult(AloneUrl, "#12400 fix(server): announcement reach counts Russian users", null)),
        ),
    )

private val InlineAndBlockMessages =
    listOf(
        BlockFixtureMessage(
            id = "0b5a1f8e-6d0c-4c3e-9a51-0e1b2c3d4f03",
            body = "Review needed on $InSentenceUrl before we cut the build.",
            results =
                mapOf(
                    InSentenceUrl to
                        blockResult(InSentenceUrl, "#12361 feat(web): an App link card's buttons in one row, the main action at its end", AppLinkInline("botiverse/slock#12361")),
                ),
        ),
    )

private fun blockFixtureHost(messages: List<BlockFixtureMessage>): ThreadAppLinkPreviewHost {
    val state =
        SharedAppLinkPreviewState(
            entries = messages.associate { appLinkReadScope(BlockViewer, it.id, it.body) to AppLinkLoaded(results = it.results, epochs = emptyMap()) },
        )
    return ThreadAppLinkPreviewHost(
        store = SharedAppLinkPreviewStore(api = HangingBlockApi, scope = CoroutineScope(Dispatchers.Unconfined), initialState = state),
        viewerKey = BlockViewer,
        serverId = "server-1",
        conversationId = BlockConversation,
        toast = {},
    )
}

private object HangingBlockApi : RaftAppLinkPreviewApi {
    override suspend fun readPreviews(messageId: String, serverId: String): HomeNetworkResponse = awaitCancellation()

    override suspend fun readLinkCards(messageId: String, serverId: String, body: build.raft.app.data.AppLinkCardReadRequestBody): HomeNetworkResponse = awaitCancellation()

    override suspend fun allow(appId: String, serverId: String): HomeNetworkResponse = awaitCancellation()

    override suspend fun decline(appId: String, serverId: String): HomeNetworkResponse = awaitCancellation()
}

private fun fixtureBlockMessage(fixture: BlockFixtureMessage, index: Int) =
    RaftThreadMessageItem(
        id = fixture.id,
        authorId = "user-niko",
        authorName = "Niko",
        authorKind = "human",
        avatarLabel = "N",
        timestampLabel = "2026-10-24T09:4$index:00Z",
        body = fixture.body,
    )

@Composable
private fun BlockFixtureRow(message: RaftThreadMessageItem, suppress: Boolean) {
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
