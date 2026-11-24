package build.raft.app

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.SharedMembersLoadStatus
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Task #69 + `#proj-mobile:#349` / `#346`: the composer owns a single-source native
 * `TextFieldState` with zero controlled `TextFieldValue` round-trip bridge. Any IME composition
 * (at end of text or mid-sentence) edits `TextFieldState` directly while draft persistence and
 * autocomplete observe it strictly one-way, so draft sync and recompositions can never reset the
 * candidate bar or bounce a mid-sentence cursor back to the end.
 *
 * Drives the real composer leaf ([ThreadComposerContentLeaf]) with the same [ThreadComposerEditorOwner]
 * [ThreadComposerContent] uses, through the Android InputConnection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
class ThreadComposerImeCompositionTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class Harness(
        val activity: ComponentActivity,
        val owner: ThreadComposerEditorOwner,
        val composed: MutableList<TextFieldValue>,
        val changes: MutableList<String>,
        val events: MutableList<String>,
        val inputConnection: InputConnection,
        val editorCommand: MutableState<ThreadComposerEditorCommand?>,
        val insertMentionRequest: MutableState<ThreadComposerInsertMentionRequest?>,
        val suggestionPortal: ThreadComposerSuggestionPortalState,
        val quoteHost: ThreadQuoteReplyHost,
    )

    private val editLog = mutableListOf<String>()
    private val defaultEditLogSink = ThreadComposerProgrammaticEditLog.sink

    @BeforeTest
    fun captureProgrammaticEditLog() {
        ThreadComposerProgrammaticEditLog.sink = { editLog += it }
    }

    @AfterTest
    fun restoreProgrammaticEditLog() {
        ThreadComposerProgrammaticEditLog.sink = defaultEditLogSink
    }

    private fun mountComposer(
        initialSeedText: String = "",
        memberSuggestions: List<RaftComposerMemberItem> = emptyList(),
        memberSuggestionsStatus: SharedMembersLoadStatus = SharedMembersLoadStatus.Idle,
        quoteReplyEnabled: Boolean = false,
    ): Harness {
        val activity = activities.launch()
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        var uiState by mutableStateOf(
            ThreadComposerUiState(
                instanceId = "ime-composition",
                seedText = initialSeedText,
                memberSuggestions = memberSuggestions,
                memberSuggestionsStatus = memberSuggestionsStatus,
            )
        )
        val owner = ThreadComposerEditorOwner(seedText = initialSeedText, seedAsTask = false)
        val composed = mutableListOf<TextFieldValue>()
        val changes = mutableListOf<String>()
        val events = mutableListOf<String>()
        val editorCommand = mutableStateOf<ThreadComposerEditorCommand?>(null)
        val insertMentionRequest = mutableStateOf<ThreadComposerInsertMentionRequest?>(null)
        val suggestionPortal = ThreadComposerSuggestionPortalState()
        val quoteHost = ThreadQuoteReplyHost(enabled = quoteReplyEnabled)
        activity.setContent {
            val value = owner.value
            composed += value
            val onEvent: (String, String?, Boolean?, String?) -> Unit = { action, text, _, _ ->
                events += action
                if (action == "change") {
                    changes += text.orEmpty()
                    // Mirror the thread page: the draft store echoes the text into the host
                    // state, which recomposes the composer while the composition is open.
                    uiState = uiState.copy(seedText = text.orEmpty())
                }
            }
            // The store's editor commands reach the owner as in ThreadComposerContent.
            ThreadComposerEditorCommandEffects(
                instanceId = uiState.instanceId,
                command = editorCommand.value,
                owner = owner,
                onEvent = onEvent,
            )
            RaftDesignSystemTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    CompositionLocalProvider(
                        LocalThreadComposerSuggestionPortal provides suggestionPortal,
                        LocalThreadQuoteReplyHost provides quoteHost,
                    ) {
                        ThreadComposerContentLeaf(
                            surface = ThreadComposerSurface(channelId = "c1"),
                            state = uiState,
                            editorOwner = owner,
                            onEvent = onEvent,
                            attachmentPreviewEnabled = true,
                            composerSendKeyEnabled = true,
                            insertMentionRequest = insertMentionRequest.value,
                        )
                    }
                }
            }
        }
        settle(activity)
        // The state-based text field starts its input session only in a focused window
        // (TextFieldDecoratorModifier); Robolectric's activity window never gains focus on its own.
        activity.window.decorView.dispatchWindowFocusChanged(true)
        uiState = uiState.copy(focusRequestNonce = 1)
        settle(activity)
        val root = findComposeRoot(activity.window.decorView) as View
        val inputConnection = assertNotNull(root.onCreateInputConnection(EditorInfo()), "composer editor focused")
        editLog.clear()
        return Harness(
            activity,
            owner,
            composed,
            changes,
            events,
            inputConnection,
            editorCommand,
            insertMentionRequest,
            suggestionPortal,
            quoteHost,
        )
    }

    private fun Harness.composeMarked(committed: String, marked: String) {
        if (committed.isNotEmpty()) inputConnection.commitText(committed, 1)
        inputConnection.setComposingText(marked, 1)
        settle(activity)
        val end = committed.length + marked.length
        assertEquals(
            TextFieldValue(committed + marked, selection = TextRange(end), composition = TextRange(committed.length, end)),
            owner.value,
            "marked text open",
        )
        editLog.clear()
    }

    private fun Harness.send(command: ThreadComposerEditorCommand) {
        editorCommand.value = command
        settle(activity)
    }

    private fun editLogLine(kind: String, composing: Boolean, deferred: Boolean) =
        "composer_programmatic_edit kind=$kind composing=$composing deferred=$deferred"

    @Test
    fun draftReplaceDuringCompositionIsDeferredAndKeepsWhatTheUserTyped() {
        val harness = mountComposer()
        harness.composeMarked(committed = "", marked = "ni")
        harness.send(
            ThreadComposerEditorCommand(
                id = 1,
                kind = ThreadComposerEditorCommandKind.Replace,
                text = "restored draft",
                expectedText = "ni",
                expectedAsTask = false,
            )
        )
        assertEquals("ni", harness.inputConnection.extractedText(), "draft replace held while marked")
        assertEquals(listOf(editLogLine("replace", composing = true, deferred = true)), editLog)

        harness.inputConnection.commitText("你", 1)
        settle(harness.activity)
        assertEquals(TextFieldValue("你", selection = TextRange(1)), harness.owner.value, "typed text kept")
        assertEquals("staleEditorCommandIgnored", harness.events.last())
    }

    @Test
    fun clearOnSendIsImmediateDuringComposition() {
        val harness = mountComposer()
        harness.composeMarked(committed = "hi ", marked = "ni")
        val sessionBefore = harness.owner.editorSessionId
        harness.send(
            ThreadComposerEditorCommand(
                id = 1,
                kind = ThreadComposerEditorCommandKind.Replace,
                text = "hi ni @alice ",
                expectedText = "hi ni",
                expectedAsTask = false,
                edit = ThreadComposerDraftEdit.InsertMention("alice"),
            )
        )
        harness.send(
            ThreadComposerEditorCommand(
                id = 2,
                kind = ThreadComposerEditorCommandKind.Clear,
                expectedText = "hi ni",
                expectedAsTask = false,
            )
        )
        assertEquals("", harness.owner.text, "send clears at once")
        assertEquals(sessionBefore + 1, harness.owner.editorSessionId)
        assertEquals("", harness.inputConnection.extractedText())
        assertEquals(
            listOf(
                editLogLine("mention", composing = true, deferred = true),
                editLogLine("clear", composing = true, deferred = false),
            ),
            editLog,
        )
        assertEquals(false, harness.owner.hasDeferredCommandsReady, "the sent draft's held mention is dropped")
    }

    @Test
    fun pinyinMarkedTextRoundTripsUnchangedUntilCommitted() {
        val harness = mountComposer()
        val marks = listOf("k", "k r", "k r y", "k r yi")
        for (mark in marks) {
            harness.composed.clear()
            harness.inputConnection.setComposingText(mark, 1)
            settle(harness.activity)
            val expected =
                TextFieldValue(mark, selection = TextRange(mark.length), composition = TextRange(0, mark.length))
            assertEquals(expected, harness.owner.value, "owner keeps the composition for \"$mark\"")
            assertEquals(
                listOf(expected),
                harness.composed.distinct(),
                "composer only composes the IME's own value while \"$mark\" is marked",
            )
            assertEquals(mark, harness.inputConnection.extractedText())
        }

        harness.composed.clear()
        harness.inputConnection.commitText("可以", 1)
        settle(harness.activity)
        val committed = TextFieldValue("可以", selection = TextRange(2))
        assertEquals(committed, harness.owner.value, "candidate commit replaces the marked text")
        assertEquals(listOf(committed), harness.composed.distinct())
        assertEquals(marks + "可以", harness.changes)
    }

    @Test
    fun latinComposingRegionAfterExistingTextIsPreserved() {
        // Gboard / Sogou keep the current Latin word in a composing region after committed text.
        val harness = mountComposer()
        harness.inputConnection.commitText("hi ", 1)
        settle(harness.activity)
        harness.inputConnection.setComposingText("wor", 1)
        settle(harness.activity)
        assertEquals(
            TextFieldValue("hi wor", selection = TextRange(6), composition = TextRange(3, 6)),
            harness.owner.value,
        )
        harness.inputConnection.setComposingText("world", 1)
        harness.inputConnection.finishComposingText()
        settle(harness.activity)
        assertEquals(TextFieldValue("hi world", selection = TextRange(8)), harness.owner.value)
        assertEquals("hi world", harness.inputConnection.extractedText())
    }

    @Test
    fun midSentenceImeComposingAndCommitKeepsCursorMidSentenceAcrossDraftSync() {
        // Regression test for #proj-mobile:#346 & #349: moving the caret into the middle of an
        // existing sentence and composing + committing via IME while draft persistence echoes into
        // host state must never bounce the cursor back to the end of the text.
        val harness = mountComposer(initialSeedText = "今天天气真不错")
        assertEquals(TextFieldValue("今天天气真不错", selection = TextRange(7)), harness.owner.value)

        // Move caret to index 2 ("今天|天气真不错").
        harness.inputConnection.setSelection(2, 2)
        settle(harness.activity)
        assertEquals(TextRange(2), harness.owner.selection, "caret moved mid-sentence")
        assertEquals(emptyList(), harness.changes, "selection-only move emits no draft change")

        // Compose Pinyin mid-sentence across multiple frames while draft sync echoes seedText.
        for (mark in listOf("n", "ni")) {
            harness.inputConnection.setComposingText(mark, 1)
            settle(harness.activity)
            val expectedText = "今天${mark}天气真不错"
            val expectedCursor = 2 + mark.length
            assertEquals(
                TextFieldValue(
                    text = expectedText,
                    selection = TextRange(expectedCursor),
                    composition = TextRange(2, expectedCursor),
                ),
                harness.owner.value,
                "mid-sentence composing \"$mark\" keeps caret and marked span in place",
            )
            assertEquals(expectedText, harness.inputConnection.extractedText())
        }

        // Commit candidate mid-sentence: cursor MUST stay right after "你" (index 3), NEVER end (8).
        harness.inputConnection.commitText("你", 1)
        settle(harness.activity)
        assertEquals(
            TextFieldValue("今天你天气真不错", selection = TextRange(3)),
            harness.owner.value,
            "mid-sentence commit must keep caret at index 3, not end",
        )
        assertNull(harness.owner.composition)

        // Continue composing a second word immediately at index 3 to confirm caret stays anchored.
        harness.inputConnection.setComposingText("hao", 1)
        settle(harness.activity)
        assertEquals(
            TextFieldValue("今天你hao天气真不错", selection = TextRange(6), composition = TextRange(3, 6)),
            harness.owner.value,
        )
        harness.inputConnection.commitText("好", 1)
        settle(harness.activity)
        assertEquals(
            TextFieldValue("今天你好天气真不错", selection = TextRange(4)),
            harness.owner.value,
            "second consecutive mid-sentence commit keeps caret at index 4",
        )
        assertEquals(
            listOf("今天n天气真不错", "今天ni天气真不错", "今天你天气真不错", "今天你hao天气真不错", "今天你好天气真不错"),
            harness.changes,
        )
    }

    @Test
    fun midSentenceLatinComposingAndCommitPreservesCursorAcrossDraftSync() {
        val harness = mountComposer(initialSeedText = "hello world")
        harness.inputConnection.setSelection(5, 5)
        settle(harness.activity)

        harness.inputConnection.setComposingText(" bea", 1)
        settle(harness.activity)
        assertEquals(
            TextFieldValue("hello bea world", selection = TextRange(9), composition = TextRange(5, 9)),
            harness.owner.value,
        )
        harness.inputConnection.setComposingText(" beautiful", 1)
        settle(harness.activity)
        harness.inputConnection.finishComposingText()
        settle(harness.activity)
        assertEquals(
            TextFieldValue("hello beautiful world", selection = TextRange(15)),
            harness.owner.value,
            "mid-sentence Latin finishComposingText must keep cursor at 15, not 21",
        )
        assertEquals("hello beautiful world", harness.inputConnection.extractedText())
    }

    @Test
    fun restorePrefillSetsSelectionAtEndOnceAndDoesNotFightLaterMidSentenceImeEdits() {
        val harness = mountComposer()
        // Failure restore / prefill sets text and places selection at end ONCE.
        harness.send(
            ThreadComposerEditorCommand(
                id = 1,
                kind = ThreadComposerEditorCommandKind.Replace,
                text = "今天天气",
                expectedText = "",
                expectedAsTask = false,
            )
        )
        assertEquals(TextFieldValue("今天天气", selection = TextRange(4)), harness.owner.value)

        // Subsequent mid-sentence edit must never be pulled back to index 4 by the earlier Replace.
        harness.inputConnection.setSelection(2, 2)
        settle(harness.activity)
        harness.inputConnection.setComposingText("hao", 1)
        settle(harness.activity)
        harness.inputConnection.commitText("好", 1)
        settle(harness.activity)
        assertEquals(TextFieldValue("今天好天气", selection = TextRange(3)), harness.owner.value)
    }

    @Test
    fun staleDelayedHydrateDuringMidSentenceComposingKeepsCompositionAndMidSentenceCursor() {
        val harness = mountComposer(initialSeedText = "今天天气")
        harness.inputConnection.setSelection(2, 2)
        settle(harness.activity)
        harness.inputConnection.setComposingText("ni", 1)
        settle(harness.activity)

        harness.send(
            ThreadComposerEditorCommand(
                id = 1,
                kind = ThreadComposerEditorCommandKind.Replace,
                text = "stale draft",
                expectedText = "",
                expectedAsTask = false,
            )
        )
        assertEquals(
            TextFieldValue("今天ni天气", selection = TextRange(4), composition = TextRange(2, 4)),
            harness.owner.value,
            "stale hydrate must not interrupt mid-sentence composition or move caret",
        )
        assertEquals("staleEditorCommandIgnored", harness.events.last())
    }

    @Test
    fun mentionSuggestionsMatchDuringIosSyllableSeparatedComposingAndReplaceWholeSpan() {
        val e40 =
            RaftComposerMemberItem(
                displayName = "E40",
                handle = "E40",
                description = "frontend infra",
                memberId = "agent-e40",
                inMentionChannel = true,
            )
        val other =
            RaftComposerMemberItem(
                displayName = "Lagoon",
                handle = "Lagoon",
                description = "Mobile DRI",
                memberId = "agent-lagoon",
                inMentionChannel = true,
            )
        val harness =
            mountComposer(
                memberSuggestions = listOf(other, e40),
                memberSuggestionsStatus = SharedMembersLoadStatus.Loaded,
            )
        // Mirrors iOS Shuangpin / Pinyin typing `@` followed by composing `f r` (U+2006 separator).
        harness.composeMarked(committed = "@", marked = "f\u2006r")
        var matchedPresentation: ThreadComposerSuggestionPresentation? = null
        for (attempt in 0 until 40) {
            val current = harness.suggestionPortal.presentation
            if (current != null &&
                current.panelState == ThreadComposerSuggestionPanelState.MemberItems &&
                current.selectionContext.query == "fr" &&
                current.memberSuggestions.map { it.handle } == listOf("E40")
            ) {
                matchedPresentation = current
                break
            }
            Thread.sleep(25L)
            settle(harness.activity)
        }
        val presentation =
            assertNotNull(
                matchedPresentation,
                "expected member suggestions for composing @f\\u2006r, got=${harness.suggestionPortal.presentation?.selectionContext} items=${harness.suggestionPortal.presentation?.memberSuggestions?.map { it.handle }}",
            )
        assertEquals(
            TextFieldValue("@f\u2006r", selection = TextRange(4), composition = TextRange(1, 4)),
            harness.owner.value,
            "matching suggestions must not disturb active IME composition",
        )

        presentation.pickSuggestionRow(0)
        settle(harness.activity)

        assertEquals(TextFieldValue("@E40 ", selection = TextRange(5)), harness.owner.value)
        assertNull(harness.owner.composition)
        assertEquals("@E40 ", harness.inputConnection.extractedText())
    }

    @Test
    fun midSentenceMentionSuggestionDuringComposingReplacesSpanAndKeepsCursorBeforeSuffix() {
        val e40 =
            RaftComposerMemberItem(
                displayName = "E40",
                handle = "E40",
                description = "frontend infra",
                memberId = "agent-e40",
                inMentionChannel = true,
            )
        val harness =
            mountComposer(
                initialSeedText = "请帮我看一下",
                memberSuggestions = listOf(e40),
                memberSuggestionsStatus = SharedMembersLoadStatus.Loaded,
            )
        harness.inputConnection.setSelection(2, 2)
        settle(harness.activity)
        harness.inputConnection.commitText("@", 1)
        harness.inputConnection.setComposingText("f\u2006r", 1)
        settle(harness.activity)

        var matchedPresentation: ThreadComposerSuggestionPresentation? = null
        for (attempt in 0 until 40) {
            val current = harness.suggestionPortal.presentation
            if (current != null &&
                current.panelState == ThreadComposerSuggestionPanelState.MemberItems &&
                current.selectionContext.query == "fr" &&
                current.memberSuggestions.map { it.handle } == listOf("E40")
            ) {
                matchedPresentation = current
                break
            }
            Thread.sleep(25L)
            settle(harness.activity)
        }
        val presentation = assertNotNull(matchedPresentation, "mid-sentence composing @f\\u2006r should open suggestions")
        presentation.pickSuggestionRow(0)
        settle(harness.activity)

        assertEquals(
            TextFieldValue("请帮@E40 我看一下", selection = TextRange(7)),
            harness.owner.value,
            "mid-sentence mention pick must keep suffix intact and place caret right after @E40 ",
        )
        assertNull(harness.owner.composition)
        assertTrue(harness.changes.last() == "请帮@E40 我看一下")
    }

    @Test
    fun midSentenceImeComposingInsideSegmentedQuoteBlockPreservesCursor() {
        ThreadComposerQuoteMetaCache.clear()
        try {
            val quote =
                PendingComposerQuote(
                    messageId = "11111111-1111-4111-8111-111111111111",
                    authorName = "Alice",
                    authorType = "user",
                    excerpt = "first question",
                    isPartial = true,
                    createdAt = "2026-11-22T08:00:00.000Z",
                )
            ThreadComposerQuoteMetaCache.remember(quote)
            val harness = mountComposer(initialSeedText = "", quoteReplyEnabled = true)
            // Insert quote chip to enter segmented mode and hand focus to the trailing TextFieldState block.
            harness.quoteHost.requestInsertQuote(
                ThreadComposerQuoteInsert(quote = quote, fallbackMarkdown = "> ${quote.excerpt}")
            )
            settle(harness.activity)
            val root = findComposeRoot(harness.activity.window.decorView) as View
            val ic = assertNotNull(root.onCreateInputConnection(EditorInfo()), "segmented quote editor focused")
            ic.commitText("今天天气", 1)
            settle(harness.activity)
            ic.setSelection(2, 2)
            settle(harness.activity)

            ic.setComposingText("hao", 1)
            settle(harness.activity)
            assertEquals("今天hao天气", ic.extractedText())

            ic.commitText("好", 1)
            settle(harness.activity)
            assertEquals("今天好天气", ic.extractedText())
            val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
            assertNotNull(extracted)
            assertEquals(3, extracted.selectionStart, "segmented quote mid-sentence caret must stay at 3")
            assertEquals(3, extracted.selectionEnd, "segmented quote mid-sentence caret must stay at 3")
            assertTrue(harness.owner.text.endsWith("\n\n今天好天气"))
        } finally {
            ThreadComposerQuoteMetaCache.clear()
        }
    }

    private fun InputConnection.extractedText(): String? =
        getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()

    // Robolectric does not draw; flush the frames, then the pending measure/layout explicitly.
    private fun settle(activity: ComponentActivity) {
        Snapshot.sendApplyNotifications()
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        (findComposeRoot(activity.window.decorView) ?: error("no compose root")).measureAndLayoutForTest()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun findComposeRoot(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findComposeRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
