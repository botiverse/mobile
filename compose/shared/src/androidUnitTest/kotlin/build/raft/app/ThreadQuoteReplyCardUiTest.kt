package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.model.ResolvedInlineQuote
import build.raft.app.thread.LocalThreadQuoteReplyHost
import build.raft.app.thread.QuoteChipAuthorAvatar
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.ThreadMessageCard
import build.raft.app.thread.ThreadQuoteChipAvatarLookup
import build.raft.app.thread.ThreadQuoteReplyHost
import build.raft.app.thread.quoteChipAvatarTestTag
import build.raft.app.thread.raftMessageBodyTextStyle
import build.raft.app.thread.threadQuoteReplyCardTestTag
import build.raft.app.thread.threadQuoteReplyChipTestTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Quote replies in a message row (botiverse/slock #16210):
 * - Verified quotes render as one-line chips (avatar when unambiguous, author, single-line excerpt
 *   prefixed with `…` only for partial quotes) and tap jumps to the quoted message.
 * - Unverified quotes stay plain blockquotes without the `> msg=<id>` line.
 * - Plain messages (including ordinary blockquotes without a `> msg=<id>` ref) pass through as
 *   plain Markdown without rendering a quote chip.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadQuoteReplyCardUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun reply(resolvedQuotes: Map<String, ResolvedInlineQuote>?) =
        RaftThreadMessageItem(
            id = "91c2b8aa-2222-4222-8222-222222222222",
            authorId = "user-bob",
            authorName = "Bob",
            authorKind = "human",
            avatarLabel = "B",
            timestampLabel = "2026-11-22T08:01:00Z",
            body = "> $Excerpt\n> msg=54a733de\n\nAgreed on that one.",
            resolvedQuotes = resolvedQuotes,
        )

    private fun ComposeUiTest.showRow(
        message: RaftThreadMessageItem,
        avatarLookup: ThreadQuoteChipAvatarLookup = ThreadQuoteChipAvatarLookup.Empty,
        onJump: (String) -> Unit = {},
    ) {
        // Compose string resources read the app context through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            val host =
                remember(avatarLookup, onJump) {
                    ThreadQuoteReplyHost(
                        avatarLookup = avatarLookup,
                        onJumpToQuotedMessage = onJump,
                    )
                }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalThreadQuoteReplyHost provides host,
            ) {
                RaftDesignSystemTheme {
                    Column(Modifier.width(390.dp)) {
                        ThreadMessageCard(
                            message = message,
                            messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                            timestampLabel = "8:01 AM",
                            onShowActions = {},
                            onOpenMember = {},
                            onOpenThread = {},
                            onOpenTask = {},
                            onOpenTaskStatusMenu = {},
                            onToggleReaction = {},
                            onOpenAttachment = {},
                            onOpenMarkdownTag = { _, _ -> },
                            onOpenMarkdownUrl = {},
                            onJumpToQuotedMessage = onJump,
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    /** Every text the row shows, as screen readers and finders read it. */
    private fun ComposeUiTest.shownTexts(): List<String> =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun ComposeUiTest.chipShown(): Boolean =
        onAllNodes(hasTestTag(threadQuoteReplyCardTestTag(0)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.avatarShown(): Boolean =
        onAllNodes(hasTestTag(quoteChipAvatarTestTag()), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aVerifiedPartialQuoteRendersOneLineChipWithAvatarEllipsisAndJump() =
        runRobolectricComposeUiTest {
            val jumps = mutableListOf<String>()
            val quote =
                ResolvedInlineQuote(
                    messageId = QuotedId,
                    shortId = "54a733de",
                    authorName = "Alice",
                    authorType = "user",
                    createdAt = "2026-11-22T08:00:00.000Z",
                    isPartial = true,
                )
            val lookup =
                ThreadQuoteChipAvatarLookup { _, author, _ ->
                    QuoteChipAuthorAvatar(kind = RaftAvatarKind.Human, avatarWireValue = null, label = author)
                }
            showRow(reply(mapOf("0" to quote)), avatarLookup = lookup, onJump = { jumps += it })

            val texts = shownTexts()
            assertTrue(chipShown(), "a quote chip")
            assertTrue(avatarShown(), "known author draws the 14dp avatar")
            assertTrue(texts.any { it == "Alice" }, "the chip names the quoted message's author: $texts")
            assertTrue(texts.any { it == "…$Excerpt" }, "partial excerpt starts with ellipsis: $texts")
            assertTrue(texts.any { "Agreed on that one." in it }, "the reply follows the chip: $texts")
            assertTrue(texts.none { "msg=" in it }, "no msg= line: $texts")

            onNodeWithTag(threadQuoteReplyChipTestTag(0), useUnmergedTree = true).performClick()
            waitForIdle()
            assertEquals(listOf(QuotedId), jumps)
        }

    @Test
    fun wholeMessageQuoteOmitsEllipsisAndUnknownAuthorOmitsAvatar() =
        runRobolectricComposeUiTest {
            val quote =
                ResolvedInlineQuote(
                    messageId = QuotedId,
                    shortId = "54a733de",
                    authorName = "Alice",
                    authorType = "user",
                    createdAt = "2026-11-22T08:00:00.000Z",
                    isPartial = false,
                )
            showRow(reply(mapOf("0" to quote)), avatarLookup = ThreadQuoteChipAvatarLookup.Empty)

            val texts = shownTexts()
            assertTrue(chipShown(), "a quote chip")
            assertFalse(avatarShown(), "uncertain author omits avatar")
            assertTrue(texts.any { it == Excerpt }, "whole quote keeps excerpt without leading ellipsis: $texts")
        }

    @Test
    fun unverifiedQuoteStaysPlainBlockquoteWithoutRefLine() =
        runRobolectricComposeUiTest {
            showRow(reply(emptyMap()))
            val unverifiedTexts = shownTexts()
            assertFalse(chipShown(), "unverified quote must not render a chip")
            assertTrue(unverifiedTexts.any { Excerpt in it }, "keeps excerpt as plain quote: $unverifiedTexts")
            assertTrue(unverifiedTexts.none { "msg=" in it }, "unverified quote strips msg= line: $unverifiedTexts")
        }

    @Test
    fun plainMessagePassesThroughWithoutQuoteChip() =
        runRobolectricComposeUiTest {
            val plain =
                reply(emptyMap()).copy(
                    body = "> Plain blockquote without ref\n\nOrdinary follow-up text.",
                )
            showRow(plain)
            val texts = shownTexts()
            assertFalse(chipShown(), "plain message must not render a quote chip")
            assertTrue(texts.any { "Plain blockquote without ref" in it }, "keeps plain blockquote: $texts")
            assertTrue(texts.any { "Ordinary follow-up text." in it }, "keeps message text: $texts")
        }

    private companion object {
        const val QuotedId = "54a733de-0001-4000-8000-000000000001"
        const val Excerpt = "keep quote replies in the same conversation"
    }
}
