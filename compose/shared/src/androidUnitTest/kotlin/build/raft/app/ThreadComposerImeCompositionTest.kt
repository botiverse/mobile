package build.raft.app

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        val inputConnection: InputConnection,
    )

    private fun mountComposer(): Harness {
        val activity = activities.launch()
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        var uiState by mutableStateOf(ThreadComposerUiState(instanceId = "ime-composition"))
        val owner = ThreadComposerControlledEditorOwner(seedText = "", seedAsTask = false)
        val composed = mutableListOf<TextFieldValue>()
        val accepted = mutableListOf<TextFieldValue>()
        val changes = mutableListOf<String>()
        activity.setContent {
            val value = owner.value
            composed += value
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
                onEvent = { action, text, _, _ ->
                    if (action == "change") {
                        changes += text.orEmpty()
                        // Mirror the thread page: the draft store echoes the text into the host
                        // state, which recomposes the composer while the composition is open.
                        uiState = uiState.copy(seedText = text.orEmpty())
                    }
                },
                attachmentPreviewEnabled = true,
                composerSendKeyEnabled = true,
            )
        }
        settle(activity)
        // The state-based text field starts its input session only in a focused window
        // (TextFieldDecoratorModifier); Robolectric's activity window never gains focus on its own.
        activity.window.decorView.dispatchWindowFocusChanged(true)
        uiState = uiState.copy(focusRequestNonce = 1)
        settle(activity)
        val root = findComposeRoot(activity.window.decorView) as View
        val inputConnection = assertNotNull(root.onCreateInputConnection(EditorInfo()), "composer editor focused")
        return Harness(activity, owner, composed, accepted, changes, inputConnection)
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
