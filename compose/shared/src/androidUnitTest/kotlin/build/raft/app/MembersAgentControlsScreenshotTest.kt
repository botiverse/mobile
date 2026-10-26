package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import build.raft.app.components.RaftUiSelectionOption
import build.raft.app.data.AgentArchiveWorkspaceChoice
import build.raft.app.data.AgentScopeSet
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * JVM screenshots of the agent profile controls that moved off RaftBrutal* (migration batch 3,
 * docs/raftbrutal-migration.md), light and dark:
 * - `members.agent-controls`: the archive confirmation (warning commit with the Archive icon),
 *   the agent role as plain text, the inline role editor (default soft badge, md Save / Cancel),
 *   the avatar picker (selected sticker cell) and the permissions load-error banner with Retry.
 * - `members.agent-permissions`: a customised permission set (Use default, Save permissions,
 *   Discard).
 * - `members.report-issue`: the report-issue dialog (Field + Textarea, primary lg submit).
 * Verify / record as [RaftRobolectricScreenshot]; reports land in `build/reports/home-screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1100dp-xxhdpi")
class MembersAgentControlsScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun controlsLight() = capture("members.agent-controls", RaftAppAppearance.Light) { AgentControlsContent() }

    @Test
    fun controlsDark() = capture("members.agent-controls.dark", RaftAppAppearance.Dark) { AgentControlsContent() }

    @Test
    fun permissionsLight() = capture("members.agent-permissions", RaftAppAppearance.Light) { AgentPermissionsContent() }

    @Test
    fun permissionsDark() = capture("members.agent-permissions.dark", RaftAppAppearance.Dark) { AgentPermissionsContent() }

    @Test
    fun reportIssueLight() = capture("members.report-issue", RaftAppAppearance.Light, dialog = true) { ReportIssueContent() }

    @Test
    fun reportIssueDark() = capture("members.report-issue.dark", RaftAppAppearance.Dark, dialog = true) { ReportIssueContent() }

    private fun capture(name: String, appearance: RaftAppAppearance, dialog: Boolean = false, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
            root = { activity -> if (dialog) ShadowDialog.getLatestDialog()?.window?.decorView else activity.window.decorView },
        ) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { content() }
            }
        }
}

private val controlsAgent =
    MemberProfile(
        id = MemberId("agent-cindy"),
        displayName = "Cindy",
        handle = "cindy",
        kind = MemberKind.Agent,
        description = "Ships the mobile app.",
    )

@Composable
private fun AgentControlsContent() {
    Column(
        modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        MembersLifecycleConfirmation(
            action = MembersAgentLifecycleAction.Delete,
            agentName = "Cindy",
            busy = false,
            archiveHasComputerWorkspace = true,
            archiveComputerName = "daybed-earth",
            archiveWorkspaceChoice = AgentArchiveWorkspaceChoice.Keep,
            onConfirm = {},
            onCancel = {},
        )
        MembersAgentServerRoleChip(MembersAgentServerRoleDisplay.Known(MembersAgentServerRole.Admin))
        MembersServerRoleEditor(
            displayValue = "Admin",
            expanded = false,
            enabled = true,
            saving = false,
            saveEnabled = true,
            options =
                listOf(
                    RaftUiSelectionOption(id = "admin", label = "Admin", selected = true),
                    RaftUiSelectionOption(id = "member", label = "Member"),
                ),
            selectedId = "admin",
            error = null,
            onToggle = {},
            onSelect = {},
            onSave = {},
            onCancel = {},
        )
        MembersAgentAvatarChoiceGrid(
            member = controlsAgent,
            uploading = false,
            onUploadClick = {},
            onClear = {},
            onSelect = {},
        )
        // No agents API: the tab's first load fails into the error banner with its Retry.
        MembersAgentPermissionsTab(
            member = controlsAgent,
            detail = null,
            canManage = true,
            agentsApiClient = null,
            onUnavailableAction = {},
        )
    }
}

@Composable
private fun AgentPermissionsContent() {
    Column(modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(16.dp)) {
        MembersAgentPermissionsTab(
            member = controlsAgent,
            detail = null,
            canManage = true,
            agentsApiClient = null,
            initialScopeSet =
                remember {
                    AgentScopeSet(
                        agentId = controlsAgent.id,
                        granted = listOf("inbox:receive", "server:read"),
                        mode = "custom",
                        revision = 3L,
                    )
                },
            onUnavailableAction = {},
        )
    }
}

@Composable
private fun ReportIssueContent() {
    MembersAgentIssueReportDialog(
        agentName = "Cindy",
        runtimeTranscriptAvailable = true,
        busy = false,
        errorMessage = null,
        onSubmit = { _, _, _, _, _ -> },
        onDismiss = {},
    )
}
