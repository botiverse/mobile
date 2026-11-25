package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.MAX_INLINE_QUOTES_PER_MESSAGE
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.data.RaftComposerSendKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * End-to-end multi-quote composer UI tests driving production [ThreadComposerContent]
 * (botiverse/slock #16027 / #16059 / #16210, `quoteReply.behavior.test.tsx`).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi-keysexposed-qwerty")
class ThreadComposerQuoteChipsUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class Harness(
        val host: ThreadQuoteReplyHost,
        val feedbacks: MutableList<ThreadComposerQuoteFeedback>,
        val jumps: MutableList<String>,
        val sentRequests: MutableList<ThreadComposerSendRequest>,
        val events: MutableList<Pair<String, String?>>,
    )

    private fun ComposeUiTest.showComposer(
        initialDraft: String = "",
        avatarLookup: ThreadQuoteChipAvatarLookup = ThreadQuoteChipAvatarLookup.Empty,
    ): Harness {
        RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        val feedbacks = mutableListOf<ThreadComposerQuoteFeedback>()
        val jumps = mutableListOf<String>()
        val sentRequests = mutableListOf<ThreadComposerSendRequest>()
        val events = mutableListOf<Pair<String, String?>>()
        val host =
            ThreadQuoteReplyHost(
                avatarLookup = avatarLookup,
                onJumpToQuotedMessage = { jumps += it },
                onQuoteFeedback = { feedbacks += it },
            )
        setContent {
            val portal = remember { ThreadComposerSuggestionPortalState() }
            RaftDesignSystemTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    CompositionLocalProvider(
                        LocalThreadComposerSuggestionPortal provides portal,
                        LocalThreadQuoteReplyHost provides host,
                    ) {
                        ThreadComposerContent(
                            surface = ThreadComposerSurface(channelId = "c1"),
                            state =
                                ThreadComposerUiState(
                                    instanceId = "quote-chips-test",
                                    seedText = initialDraft,
                                ),
                            onEvent = { action, value, _, _ -> events += action to value },
                            onSendRequest = { req ->
                                sentRequests += req
                                ThreadComposerSendAdmission.Started
                            },
                            onKeyboardHeightChange = {},
                            contentPaddingTop = 0.dp,
                            contentPaddingBottom = 0.dp,
                            composerSendKeyEnabled = true,
                            composerSendKey = RaftComposerSendKey.Enter,
                            hardwareKeyboardConnected = true,
                            attachmentPreviewEnabled = true,
                        )
                    }
                }
                ThreadComposerSuggestionPortalHost(portal)
            }
        }
        waitForIdle()
        return Harness(host, feedbacks, jumps, sentRequests, events)
    }

    private fun ComposeUiTest.nodeExists(tag: String): Boolean =
        onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.shownTexts(): List<String> =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    @Test
    fun singleAndStackedQuotesRenderChipsReplyBelowAndTwoStepBackspace() =
        runRobolectricComposeUiTest {
            ThreadComposerQuoteMetaCache.clear()
            try {
                val harness =
                    showComposer(
                        avatarLookup =
                            ThreadQuoteChipAvatarLookup { _, author, _ ->
                                QuoteChipAuthorAvatar(RaftAvatarKind.Human, null, author)
                            }
                    )

                harness.host.requestInsertQuote(ThreadComposerQuoteInsert(quote = QuoteA, fallbackMarkdown = "> ${QuoteA.excerpt}"))
                waitForIdle()

                assertTrue(nodeExists(ComposerQuoteBlocksTestTag))
                assertTrue(nodeExists(composerQuoteRowTestTag(0, 1)))
                assertTrue(nodeExists(composerQuoteChipTestTag(0)))
                assertTrue(nodeExists(composerQuoteRemoveTestTag(0)))
                assertTrue(nodeExists(quoteChipAvatarTestTag()))
                // Only one quote -> no "Reply below" button.
                assertFalse(nodeExists(composerQuoteInsertBelowTestTag(0)))

                // Clicking the chip jumps to the quoted message.
                onNodeWithTag(composerQuoteChipTestTag(0), useUnmergedTree = true).performClick()
                waitForIdle()
                assertEquals(listOf(QuoteA.messageId), harness.jumps)

                // Add a second quote: stacked chips show Reply below on quote 0 only.
                harness.host.requestInsertQuote(ThreadComposerQuoteInsert(quote = QuoteB, fallbackMarkdown = "> ${QuoteB.excerpt}"))
                waitForIdle()
                assertTrue(nodeExists(composerQuoteRowTestTag(0, 2)))
                assertTrue(nodeExists(composerQuoteRowTestTag(1, 2)))
                assertTrue(nodeExists(composerQuoteInsertBelowTestTag(0)), "stacked quote 0 shows Reply below")
                assertFalse(nodeExists(composerQuoteInsertBelowTestTag(1)), "last quote hides Reply below")

                // First Backspace at caret 0 below quote 1 arms it and shows armed hint; quote remains.
                val primaryEditor = onNodeWithTag("composer_textarea", useUnmergedTree = true)
                primaryEditor.performClick()
                waitForIdle()
                primaryEditor.performKeyInput { pressKey(Key.Backspace) }
                waitForIdle()
                assertTrue(nodeExists(composerQuoteArmedHintTestTag(1)), "1st Backspace arms quote 1")
                assertTrue(nodeExists(composerQuoteChipTestTag(1)), "1st Backspace keeps quote 1")

                // Second Backspace removes armed quote 1, leaving quote 0 intact.
                primaryEditor.performKeyInput { pressKey(Key.Backspace) }
                waitForIdle()
                assertFalse(nodeExists(composerQuoteChipTestTag(1)), "2nd Backspace removes quote 1")
                assertTrue(nodeExists(composerQuoteChipTestTag(0)), "quote 0 stays intact")

                // Clicking × removes quote 0 and returns to plain composer.
                onNodeWithTag(composerQuoteRemoveTestTag(0), useUnmergedTree = true).performClick()
                waitForIdle()
                assertFalse(nodeExists(ComposerQuoteBlocksTestTag), "removing last quote exits segmented mode")
            } finally {
                ThreadComposerQuoteMetaCache.clear()
            }
        }

    @Test
    fun replyBelowInterleavesAndSendsCanonicalMultiQuoteWirePayloadWithOptimisticQuotes() =
        runRobolectricComposeUiTest {
            ThreadComposerQuoteMetaCache.clear()
            try {
                val harness = showComposer()
                harness.host.requestInsertQuote(ThreadComposerQuoteInsert(quote = QuoteA, fallbackMarkdown = "> ${QuoteA.excerpt}"))
                waitForIdle()
                harness.host.requestInsertQuote(ThreadComposerQuoteInsert(quote = QuoteB, fallbackMarkdown = "> ${QuoteB.excerpt}"))
                waitForIdle()

                // Open an interleaved reply block below quote 0.
                onNodeWithTag(composerQuoteInsertBelowTestTag(0), useUnmergedTree = true).performClick()
                waitForIdle()
                // Reply below button hides while a text block sits between quote 0 and quote 1.
                assertFalse(nodeExists(composerQuoteInsertBelowTestTag(0)))

                // Active block is now the interleaved block at index 1 ("composer_textarea").
                onNodeWithTag("composer_textarea", useUnmergedTree = true).performTextInput("Answer 1")
                waitForIdle()

                // Type into the trailing text block at index 3.
                onNodeWithTag(composerTextBlockTestTag(3, isPrimary = false), useUnmergedTree = true).performClick()
                waitForIdle()
                onNodeWithTag("composer_textarea", useUnmergedTree = true).performTextInput("Answer 2")
                waitForIdle()

                // Press Enter to send.
                onNodeWithTag("composer_textarea", useUnmergedTree = true).performKeyInput { pressKey(Key.Enter) }
                waitForIdle()

                assertEquals(1, harness.sentRequests.size, "expected 1 send request")
                val sent = harness.sentRequests.single()
                assertEquals(
                    "> ${QuoteA.excerpt}\n> msg=11111111\n\nAnswer 1\n\n> ${QuoteB.excerpt}\n> msg=22222222\n\nAnswer 2",
                    sent.value.text,
                )
                val optimistic = sent.optimisticResolvedQuotes
                assertNotNull(optimistic)
                assertEquals(setOf("0", "1"), optimistic.keys)
                assertEquals("Alice", optimistic["0"]?.authorName)
                assertEquals("Sage", optimistic["1"]?.authorName)
            } finally {
                ThreadComposerQuoteMetaCache.clear()
            }
        }

    @Test
    fun duplicateFeedbackQuietCountBadgeAndLimit() =
        runRobolectricComposeUiTest {
            ThreadComposerQuoteMetaCache.clear()
            try {
                val harness = showComposer()
                harness.host.requestInsertQuote(ThreadComposerQuoteInsert(quote = QuoteA, fallbackMarkdown = ""))
                waitForIdle()
                // Duplicate triggers feedback without adding a second chip.
                harness.host.requestInsertQuote(ThreadComposerQuoteInsert(quote = QuoteA, fallbackMarkdown = ""))
                waitForIdle()
                assertEquals(listOf<ThreadComposerQuoteFeedback>(ThreadComposerQuoteFeedback.Duplicate), harness.feedbacks)
                assertFalse(nodeExists(ComposerQuoteCountBadgeTestTag), "badge stays hidden under 7 quotes")

                // Fill up to 10 quotes: badge appears at 7+ and 11th triggers LimitReached.
                for (i in 1 until MAX_INLINE_QUOTES_PER_MESSAGE) {
                    val q =
                        QuoteA.copy(
                            messageId = "${i.toString().padStart(8, 'a')}-1111-4111-8111-111111111111",
                            excerpt = "point $i",
                        )
                    harness.host.requestInsertQuote(ThreadComposerQuoteInsert(quote = q, fallbackMarkdown = ""))
                    waitForIdle()
                }
                assertTrue(nodeExists(ComposerQuoteCountBadgeTestTag), "quiet badge appears at 7..10 quotes")
                assertTrue(shownTexts().any { it == "10/10" }, "badge shows 10/10: ${shownTexts()}")

                harness.host.requestInsertQuote(
                    ThreadComposerQuoteInsert(
                        quote = QuoteA.copy(messageId = "ffffffff-1111-4111-8111-111111111111", excerpt = "overflow"),
                        fallbackMarkdown = "",
                    )
                )
                waitForIdle()
                assertEquals(
                    ThreadComposerQuoteFeedback.LimitReached(MAX_INLINE_QUOTES_PER_MESSAGE),
                    harness.feedbacks.lastOrNull(),
                )
            } finally {
                ThreadComposerQuoteMetaCache.clear()
            }
        }

    @Test
    fun plainDraftStaysSingleTextarea() =
        runRobolectricComposeUiTest {
            ThreadComposerQuoteMetaCache.clear()
            try {
                showComposer(initialDraft = "> plain blockquote\n\nHi")
                assertFalse(nodeExists(ComposerQuoteBlocksTestTag), "plain blockquote stays single-textarea")
            } finally {
                ThreadComposerQuoteMetaCache.clear()
            }
        }

    @Test
    fun persistedQuoteDraftHydratesIntoQuoteChip() =
        runRobolectricComposeUiTest {
            ThreadComposerQuoteMetaCache.clear()
            try {
                ThreadComposerQuoteMetaCache.remember(QuoteA)
                showComposer(initialDraft = "> ${QuoteA.excerpt}\n> msg=11111111\n\nHi")
                assertTrue(nodeExists(ComposerQuoteBlocksTestTag), "persisted quote draft hydrates segmented composer")
                assertTrue(nodeExists(composerQuoteChipTestTag(0)), "persisted quote draft renders chip")
            } finally {
                ThreadComposerQuoteMetaCache.clear()
            }
        }

    private companion object {
        val QuoteA =
            PendingComposerQuote(
                messageId = "11111111-1111-4111-8111-111111111111",
                authorName = "Alice",
                authorType = "user",
                excerpt = "first question",
                isPartial = true,
                createdAt = "2026-11-22T08:00:00.000Z",
            )
        val QuoteB =
            PendingComposerQuote(
                messageId = "22222222-2222-4222-8222-222222222222",
                authorName = "Sage",
                authorType = "agent",
                excerpt = "second question",
                isPartial = false,
                createdAt = "2026-11-22T08:01:00.000Z",
            )
    }
}
