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
import build.raft.app.data.SharedMembersLoadStatus
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Task #69 (iOS Pinyin "k r yi" committed as letters): the composer must hand every IME value
 * back to the field unchanged while a composition is open. Any rewrite of text, selection or
 * composition during composing (mention/markdown transforms, draft echo, selection repair) ends
 * the platform IME session: UIKit resets the candidate bar, Android restarts input.
 *
 * Drives the real composer leaf with the same controlled owner [ThreadComposerContent] uses,
 * through the Android InputConnection, and records every value the composer accepts and every
 * value it composes back into the field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
class ThreadComposerImeCompositionTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class Harness(
        val activity: ComponentActivity,
        val owner: ThreadComposerControlledEditorOwner,
        val composed: MutableList<TextFieldValue>,
        val accepted: MutableList<TextFieldValue>,
        val changes: MutableList<String>,
        val events: MutableList<String>,
        val inputConnection: InputConnection,
        val editorCommand: MutableState<ThreadComposerEditorCommand?>,
        val insertMentionRequest: MutableState<ThreadComposerInsertMentionRequest?>,
        val suggestionPortal: ThreadComposerSuggestionPortalState,
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
        memberSuggestions: List<RaftComposerMemberItem> = emptyList(),
        memberSuggestionsStatus: SharedMembersLoadStatus = SharedMembersLoadStatus.Idle,
    ): Harness {
        val activity = activities.launch()
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        var uiState by mutableStateOf(
            ThreadComposerUiState(
                instanceId = "ime-composition",
                memberSuggestions = memberSuggestions,
                memberSuggestionsStatus = memberSuggestionsStatus,
            )
        )
        val owner = ThreadComposerControlledEditorOwner(seedText = "", seedAsTask = false)
        val composed = mutableListOf<TextFieldValue>()
        val accepted = mutableListOf<TextFieldValue>()
        val changes = mutableListOf<String>()
        val events = mutableListOf<String>()
        val editorCommand = mutableStateOf<ThreadComposerEditorCommand?>(null)
        val insertMentionRequest = mutableStateOf<ThreadComposerInsertMentionRequest?>(null)
        val suggestionPortal = ThreadComposerSuggestionPortalState()
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
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                CompositionLocalProvider(LocalThreadComposerSuggestionPortal provides suggestionPortal) {
                    ThreadComposerContentLeaf(
                        surface = ThreadComposerSurface(channelId = "c1"),
                        state = uiState,
                        editorSessionId = owner.editorSessionId,
                        editorValue = value,
                        onEditorValueChange = { id, next -> owner.acceptEditorValue(id, next) },
                        onNativeEditorValueChange = { id, next ->
                            owner.acceptNativeEditorValue(id, next)?.also { accepted += it }
                        },
                        isEditorSessionActive = owner::isActiveSession,
                        editorAsTask = owner.asTask,
                        onEditorAsTaskChange = owner::acceptAsTask,
                        onEvent = onEvent,
                        attachmentPreviewEnabled = true,
                        composerSendKeyEnabled = true,
                        insertMentionRequest = insertMentionRequest.value,
                    )
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
            accepted,
            changes,
            events,
            inputConnection,
            editorCommand,
            insertMentionRequest,
            suggestionPortal,
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
        assertEquals("", harness.owner.value.text, "send clears at once")
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
            assertEquals(expected, harness.accepted.last(), "IME value accepted as-is for \"$mark\"")
            assertEquals(expected, harness.owner.value, "owner keeps the composition for \"$mark\"")
            assertEquals(
                listOf(expected),
                harness.composed.distinct(),
                "composer only composes the IME's own value back while \"$mark\" is marked",
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
        assertNull(harness.owner.value.composition)
        assertEquals("@E40 ", harness.inputConnection.extractedText())
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
