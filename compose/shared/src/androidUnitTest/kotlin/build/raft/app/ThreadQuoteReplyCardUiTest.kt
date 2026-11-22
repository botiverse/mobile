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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Quote replies in a message row (web `quote_reply_v0`): a quote the server verified is a card naming
 * the quoted message's author over its excerpt, and a tap on it jumps to that message; a quote it
 * did not verify keeps its excerpt as a plain quote. Neither shows the `> msg=<id>` line. Drives the
 * production [ThreadMessageCard].
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

    private fun ComposeUiTest.showRow(message: RaftThreadMessageItem, onJump: (String) -> Unit = {}) {
        // Compose string resources read the app context through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
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

    private fun ComposeUiTest.cardShown(): Boolean =
        onAllNodes(hasTestTag(threadQuoteReplyCardTestTag(0)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aVerifiedQuoteIsACardOfItsAuthorAndExcerptThatJumpsToTheQuotedMessage() =
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
            showRow(reply(mapOf("0" to quote)), onJump = { jumps += it })

            val texts = shownTexts()
            assertTrue(cardShown(), "a quote card")
            assertTrue(texts.any { "Alice" in it }, "the card names the quoted message's author: $texts")
            assertTrue(texts.any { Excerpt in it }, "the card shows the excerpt: $texts")
            assertTrue(texts.any { "Agreed on that one." in it }, "the reply follows the card: $texts")
            assertTrue(texts.none { "msg=" in it }, "no msg= line: $texts")

            onNodeWithTag(threadQuoteReplyCardTestTag(0), useUnmergedTree = true).performClick()
            waitForIdle()
            assertEquals(listOf(QuotedId), jumps)
        }

    @Test
    fun anUnverifiedQuoteIsAPlainQuoteOfItsExcerptWithoutTheRef() =
        runRobolectricComposeUiTest {
            // The read attached no resolved quote (the quoted message was deleted, or isn't visible).
            showRow(reply(resolvedQuotes = emptyMap()))

            val texts = shownTexts()
            assertTrue(!cardShown(), "no card")
            assertTrue(texts.any { Excerpt in it }, "the excerpt shows as a quote: $texts")
            assertTrue(texts.any { "Agreed on that one." in it }, "the reply shows: $texts")
            assertTrue(texts.none { "msg=" in it }, "no msg= line: $texts")
        }

    private companion object {
        const val QuotedId = "54a733de-0001-4000-8000-000000000001"
        const val Excerpt = "keep quote replies in the same conversation"
    }
}
