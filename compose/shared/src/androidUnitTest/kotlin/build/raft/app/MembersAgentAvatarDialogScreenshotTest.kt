package build.raft.app

import build.raft.app.resources.*
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import build.raft.app.data.DataResult
import build.raft.app.data.PlatformFile
import build.raft.app.data.ScreenError
import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.designsystem.components.RaftDialogSheetSurface
import build.raft.designsystem.theme.RaftTheme
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * The agent's change-avatar dialog (web AgentProfileEditDialog, field "avatar", the phone sheet),
 * light and dark, in a window tall enough to show every series:
 * - `members.agent-avatar.dialog`: opened on an agent whose avatar is the cat: the preview and
 *   the dashed upload tile, then the nine series in rows of four, the cat chosen (yellow), Save
 *   disabled;
 * - `members.agent-avatar.dialog.saving`: the fox staged and Save running (its spinner);
 * - `members.agent-avatar.dialog.error`: an upload that failed, the warning banner under the grid.
 * Then the dialog's behaviour through its semantics: a tile only stages, Save sends the staged
 * choice and closes; × with a staged choice asks to discard it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1500dp-xxhdpi")
class MembersAgentAvatarDialogScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val agent =
        MemberProfile(
            id = MemberId("agent-ada"),
            displayName = "Ada",
            handle = "ada",
            kind = MemberKind.Agent,
            avatarUrl = "pixel:cat",
            avatar = AvatarReference.Pixel("cat")
        )

    @Test
    fun dialogLight() = dialog("members.agent-avatar.dialog", RaftAppAppearance.Light)

    @Test
    fun dialogDark() = dialog("members.agent-avatar.dialog.dark", RaftAppAppearance.Dark)

    @Test
    fun savingLight() = sheet("members.agent-avatar.dialog.saving", RaftAppAppearance.Light, MembersAgentAvatarDraft.Preset("fox"), saving = true)

    @Test
    fun savingDark() = sheet("members.agent-avatar.dialog.saving.dark", RaftAppAppearance.Dark, MembersAgentAvatarDraft.Preset("fox"), saving = true)

    @Test
    fun errorLight() =
        sheet(
            "members.agent-avatar.dialog.error",
            RaftAppAppearance.Light,
            MembersAgentAvatarDraft.Unchanged,
            error = MembersAgentAvatarError.Upload(ScreenError("network", serverMessage = "The connection was lost."))
        )

    @Test
    fun errorDark() =
        sheet(
            "members.agent-avatar.dialog.error.dark",
            RaftAppAppearance.Dark,
            MembersAgentAvatarDraft.Unchanged,
            error = MembersAgentAvatarError.Upload(ScreenError("network", serverMessage = "The connection was lost."))
        )

    private fun dialog(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
            root = { ShadowDialog.getLatestDialog()?.window?.decorView },
        ) {
            MigrationFixtureHost {
                MembersAgentAvatarDialog(
                    member = agent,
                    currentAvatarWireValue = "pixel:cat",
                    onPickFile = { DataResult.Error(ScreenError("unused")) },
                    onSave = { DataResult.Content(agent) },
                    onDismiss = {}
                )
            }
        }

    private fun sheet(
        name: String,
        appearance: RaftAppAppearance,
        draft: MembersAgentAvatarDraft,
        saving: Boolean = false,
        error: MembersAgentAvatarError? = null
    ) = RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
        MigrationFixtureHost {
            Column(Modifier.fillMaxSize().background(RaftTheme.colors.foreground.copy(alpha = 0.6f)).padding(top = 76.dp)) {
                RaftDialogSheetSurface(
                    title = stringResource(Res.string.member_action_choose_avatar),
                    commitLabel = stringResource(Res.string.common_save),
                    onCommit = {},
                    onClose = {},
                    modifier = Modifier.fillMaxSize(),
                    commitEnabled = draft != MembersAgentAvatarDraft.Unchanged,
                    commitLoading = saving,
                    closeEnabled = !saving
                ) {
                    MembersAgentAvatarDialogBody(
                        member = agent,
                        currentAvatarWireValue = "pixel:cat",
                        draft = draft,
                        picking = false,
                        enabled = !saving,
                        error = error,
                        onUpload = {},
                        onSelectPreset = {}
                    )
                }
            }
        }
    }

    @Test
    fun aTileOnlyStagesAndSaveSendsTheStagedChoiceThenCloses() {
        val saved = mutableListOf<MembersAgentAvatarDraft>()
        var dismissed = 0
        open(onSave = { saved += it; DataResult.Content(agent) }, onDismiss = { dismissed++ })

        assertTrue(save().isDisabled(), "Save is disabled until something is staged")
        node("fox").click()
        settle()
        assertTrue(saved.isEmpty(), "a tile only stages; nothing is sent before Save")
        assertTrue(!save().isDisabled(), "Save is enabled once a choice is staged")
        save().click()
        settle()
        assertEquals(listOf<MembersAgentAvatarDraft>(MembersAgentAvatarDraft.Preset("fox")), saved)
        assertEquals(1, dismissed)
    }

    @Test
    fun closingWithAStagedChoiceAsksToDiscardIt() {
        var dismissed = 0
        open(onSave = { DataResult.Content(agent) }, onDismiss = { dismissed++ })
        node("robot").click()
        settle()
        node("Close").click()
        settle()
        assertEquals(0, dismissed, "× with a staged change does not close at once")
        val discard = nodes().firstOrNull { it.texts().contains("Discard Changes") }
        assertNotNull(discard, "the discard confirmation opened")
        discard.click()
        settle()
        assertEquals(1, dismissed)
    }

    @Test
    fun aPickedImageIsStagedNotUploaded() {
        val saved = mutableListOf<MembersAgentAvatarDraft>()
        val image = PlatformFile(id = "file-1", fileName = "ada.png", mimeType = "image/png", sizeBytes = 1_000L)
        open(onPickFile = { DataResult.Content(image) }, onSave = { saved += it; DataResult.Content(agent) }, onDismiss = {})
        node("Upload image").click()
        settle()
        assertTrue(saved.isEmpty(), "picking an image does not upload it")
        save().click()
        settle()
        assertEquals(listOf<MembersAgentAvatarDraft>(MembersAgentAvatarDraft.Upload(image)), saved)
    }

    private fun open(
        onPickFile: suspend () -> DataResult<PlatformFile> = { DataResult.Error(ScreenError("unused")) },
        onSave: suspend (MembersAgentAvatarDraft) -> DataResult<*>,
        onDismiss: () -> Unit
    ) {
        RaftAppThemeState.apply(RaftAppAppearance.Light)
        val activity = activities.launch()
        // Moko string resources read the app context through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        activity.setContent {
            Host {
                MembersAgentAvatarDialog(
                    member = agent,
                    currentAvatarWireValue = "pixel:cat",
                    onPickFile = onPickFile,
                    onSave = onSave,
                    onDismiss = onDismiss
                )
            }
        }
        settle()
    }

    @Composable
    private fun Host(content: @Composable () -> Unit) = MigrationFixtureHost(content)

    private fun nodes(): List<SemanticsNode> {
        val dialog = assertNotNull(ShadowDialog.getLatestDialog(), "no dialog window")
        val root = assertNotNull(findComposeRoot(dialog.window!!.decorView), "no compose root in the dialog window")
        val out = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            out += node
            node.children.forEach(::walk)
        }
        walk((root as ViewRootForTest).semanticsOwner.rootSemanticsNode)
        return out
    }

    private fun SemanticsNode.texts(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun node(label: String): SemanticsNode =
        assertNotNull(
            nodes().firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null && it.texts().contains(label) },
            "no clickable \"$label\""
        )

    private fun save(): SemanticsNode = node("Save")

    private fun SemanticsNode.isDisabled(): Boolean = config.getOrNull(SemanticsProperties.Disabled) != null

    private fun SemanticsNode.click() {
        config[SemanticsActions.OnClick].action!!.invoke()
    }

    private fun settle() {
        repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200)) }
    }

    private fun findComposeRoot(view: View): View? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findComposeRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
