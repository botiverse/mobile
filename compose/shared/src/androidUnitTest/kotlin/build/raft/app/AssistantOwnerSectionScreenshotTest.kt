package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.assistant.AssistantInstructionsEditorBody
import build.raft.app.assistant.AssistantInstructionsSaveError
import build.raft.app.assistant.AssistantOwnerSectionContent
import build.raft.app.assistant.AssistantPausedBanner
import build.raft.app.data.ASSISTANT_OWNER_NOTES_MAX_LENGTH
import build.raft.app.data.assistantOwnerNotesCanSave
import build.raft.designsystem.components.RaftDialogSheetSurface
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The owner's side of their personal assistant's profile (web AssistantOwnerSection,
 * AssistantInstructionsDialog, AssistantPaused `PausedNotice`), on fixed fixture data:
 * - `assistant.owner-section`: the 「Assistant」 eyebrow over the Extra instructions row, light and dark;
 * - `assistant.instructions.empty`: the editor (the phone sheet) on no saved notes, Save disabled;
 * - `assistant.instructions.near-limit`: a draft at 90% of the limit, the counter shown, Save enabled;
 * - `assistant.instructions.too-long`: a draft over the limit, the error and the red counter, Save
 *   disabled, and the banner of a save the server refused as too long with its trace ID;
 * - `assistant.paused-notice`: the owner's paused notice with Start, then Starting…, then a failed
 *   start with its trace ID.
 * Goldens are CI's render (`test.yml` with `record_screenshots=true`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class AssistantOwnerSectionScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun sectionLight() = capture("assistant.owner-section", RaftAppAppearance.Light) { SectionFixture() }

    @Test
    fun sectionDark() = capture("assistant.owner-section.dark", RaftAppAppearance.Dark) { SectionFixture() }

    @Test
    fun editorEmptyLight() = capture("assistant.instructions.empty", RaftAppAppearance.Light) { EditorFixture(draft = "") }

    @Test
    fun editorEmptyDark() = capture("assistant.instructions.empty.dark", RaftAppAppearance.Dark) { EditorFixture(draft = "") }

    @Test
    fun editorNearLimit() =
        capture("assistant.instructions.near-limit", RaftAppAppearance.Light) {
            EditorFixture(draft = longDraft(ASSISTANT_OWNER_NOTES_MAX_LENGTH * 9 / 10 + 12))
        }

    @Test
    fun editorTooLong() =
        capture("assistant.instructions.too-long", RaftAppAppearance.Light) {
            EditorFixture(
                draft = longDraft(ASSISTANT_OWNER_NOTES_MAX_LENGTH + 37),
                saveError = AssistantInstructionsSaveError.TooLong(traceId = FIXTURE_TRACE_ID),
            )
        }

    @Test
    fun pausedNoticeLight() = capture("assistant.paused-notice", RaftAppAppearance.Light) { PausedFixture() }

    @Test
    fun pausedNoticeDark() = capture("assistant.paused-notice.dark", RaftAppAppearance.Dark) { PausedFixture() }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            MigrationFixtureHost(content)
        }
}

private const val SAVED_NOTES = ""

private const val FIXTURE_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"

/** Words, not one repeated letter, so the field wraps as real notes do. */
private fun longDraft(size: Int): String {
    val sentence = "Reply to me in English and keep it short. Skip CI notifications unless a deploy fails. "
    return buildString { while (length < size) append(sentence) }.take(size).trimEnd().padEnd(size, '.')
}

@Composable
private fun SectionFixture() {
    Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(vertical = 16.dp, horizontal = 20.dp)) {
        AssistantOwnerSectionContent(name = "Ada", onOpenInstructions = {})
    }
}

@Composable
private fun EditorFixture(draft: String, saveError: AssistantInstructionsSaveError? = null) {
    Column(Modifier.fillMaxSize().background(RaftTheme.colors.foreground.copy(alpha = 0.6f)).padding(top = 76.dp)) {
        RaftDialogSheetSurface(
            title = stringResource(Res.string.assistant_instructions_title),
            commitLabel = stringResource(Res.string.assistant_instructions_save),
            onCommit = {},
            onClose = {},
            modifier = Modifier.fillMaxSize(),
            commitEnabled = assistantOwnerNotesCanSave(draft, SAVED_NOTES),
        ) {
            AssistantInstructionsEditorBody(
                name = "Ada",
                draft = draft,
                onDraftChange = {},
                saving = false,
                saveError = saveError,
            )
        }
    }
}

@Composable
private fun PausedFixture() {
    Column(
        Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(vertical = 16.dp, horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AssistantPausedBanner(line = "Paused by Grace · 2h ago", starting = false, failed = false, onStart = {})
        AssistantPausedBanner(line = "Paused · 2h ago", starting = true, failed = false, onStart = {}, modifier = Modifier.fillMaxWidth())
        AssistantPausedBanner(
            line = "Paused by Grace · 2h ago",
            starting = false,
            failed = true,
            onStart = {},
            failureTraceId = FIXTURE_TRACE_ID,
        )
    }
}
