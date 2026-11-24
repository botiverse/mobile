package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.data.AgentBlockedState
import build.raft.app.data.AppLocale
import build.raft.app.data.englishStatusLabel
import build.raft.app.data.ComputerStatus
import build.raft.app.data.ComputerSummary
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerAgentSnapshot
import build.raft.app.data.SettingsComputerCommandContext
import build.raft.app.data.SettingsComputerDetailSnapshot
import build.raft.app.data.SettingsComputerRevocationSnapshot
import build.raft.app.data.SettingsComputerRevokedVia
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputerVersionGateSnapshot
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.StringDesc
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI parity tests for botiverse/slock #16468 (revoked Computer detail banner, recovery steps, and
 * agent revoked note) and #16514 (built-in 1.0.53 version floor offline reinstall notice with npm /
 * Homebrew note).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class ComputerRevocationAndVersionFloorUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    private val fixedTimeContext =
        RaftTimeContext(
            localOffsetMinutes = 0,
            todayEpochDay = raftEpochDayForDate(2026, 11, 24),
            nowEpochMillis = 1_795_521_600_000L,
            timeFormat = RaftTimeFormat24Hour,
        )

    private val commandContext =
        SettingsComputerCommandContext(
            serverSlug = "acme",
            deploymentEnv = "production",
            serverUrl = "https://api.raft.build",
        )

    private val leftRevocation =
        SettingsComputerRevocationSnapshot(
            at = "2026-11-20T12:00:00.000Z",
            via = SettingsComputerRevokedVia.MemberLeft,
            subjectUserName = "Jonathan",
            departureReason = "left",
        )

    private fun ComposeUiTest.showDetail(snapshot: SettingsComputerDetailSnapshot) {
        val model =
            raftComputerDetailModel(
                ScreenLoadState.Content(snapshot),
                machineId = snapshot.computer.id,
                targetServerId = null,
                currentServerId = "server-1",
            )
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(fixedTimeContext)) {
                    RaftComputersTheme {
                        RaftSettingsComputerDetailContent(
                            model = model,
                            actionState = null,
                            onRetry = {},
                            onIntent = {},
                            onOpenAgent = {},
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun revokedDetail(
        revocation: SettingsComputerRevocationSnapshot? = leftRevocation,
        isComputer: Boolean = false,
        agentCount: Int = 2,
        canManage: Boolean = true,
    ) = SettingsComputerDetailSnapshot(
        computer =
            SettingsComputerSnapshot(
                id = "m1",
                name = "Build Box",
                daemonVersion = "1.0.53",
                computerVersion = if (isComputer) "1.0.53" else null,
                isComputer = isComputer,
                online = false,
                platform = "linux x64",
                revocation = revocation,
                agentCount = agentCount,
            ),
        workspaces = emptyList(),
        agents = List(agentCount) { index -> SettingsComputerAgentSnapshot(id = "a$index", name = "Agent $index") },
        sections = emptyList(),
        canManageMachines = canManage,
        canEditMachine = canManage,
        commandContext = commandContext,
    )

    @Test
    fun revokedComputerDetailShowsKeyRevokedStatusBannerAndRecoveryCommands() = runRobolectricComposeUiTest {
        showDetail(revokedDetail(agentCount = 2))

        onNodeWithText("Key revoked").assertIsDisplayed()
        onNodeWithTag(RaftComputerRevocationTestTags.Root, useUnmergedTree = true).assertExists()
        onNodeWithTag(RaftComputerRevocationTestTags.Banner, useUnmergedTree = true).assertExists()
        onNodeWithText("Disconnected on Nov 20, 2026", substring = true).assertIsDisplayed()
        onNodeWithText(
            "Jonathan left the server, so this Computer's key was revoked. 2 agents on it are offline.",
            substring = true,
        ).assertIsDisplayed()
        onNodeWithText("Bring it back").assertIsDisplayed()
        onNodeWithText(
            "Reattach it: on that machine, run this command and approve the sign-in with an owner or admin account. It comes back as the same Computer with its agents, and the person who approves becomes its Attached by.",
            substring = true,
        ).assertIsDisplayed()
        onNodeWithText("raft-computer setup /acme --machine m1", substring = true).assertIsDisplayed()
        onNodeWithText("Or move its agents to another computer, from each agent's page.", substring = true).assertIsDisplayed()
        assertEquals(0, count("Migrate to computer", substring = true))
    }

    @Test
    fun revokedComputerDetailCoversAllCauseVariantsAndAgentCountSingularAndZero() = runRobolectricComposeUiTest {
        val cases =
            listOf(
                leftRevocation.copy(via = SettingsComputerRevokedVia.MemberLeft, subjectUserName = null) to
                    "The person who attached it left the server, so this Computer's key was revoked.",
                leftRevocation.copy(via = SettingsComputerRevokedVia.MemberRemoved, byUserName = "Alice", subjectUserName = "Bob") to
                    "Alice removed Bob from the server, so this Computer's key was revoked.",
                leftRevocation.copy(via = SettingsComputerRevokedVia.MemberRemoved, byUserName = null, subjectUserName = "Bob") to
                    "Bob was removed from the server, so this Computer's key was revoked.",
                leftRevocation.copy(via = SettingsComputerRevokedVia.MemberRemoved, byUserName = null, subjectUserName = null) to
                    "The person who attached it was removed from the server, so this Computer's key was revoked.",
                leftRevocation.copy(via = SettingsComputerRevokedVia.AccountDeleted, subjectUserName = "Bob") to
                    "Bob deleted their account, so this Computer's key was revoked.",
                leftRevocation.copy(via = SettingsComputerRevokedVia.AccountDeleted, subjectUserName = null) to
                    "An account linked to this Computer was deleted, so its key was revoked.",
                leftRevocation.copy(via = SettingsComputerRevokedVia.KeyRevokedManually, byUserName = "Alice") to
                    "Alice revoked this Computer's key.",
                leftRevocation.copy(via = SettingsComputerRevokedVia.KeyRevokedManually, byUserName = null) to
                    "Someone revoked this Computer's key.",
                leftRevocation.copy(via = SettingsComputerRevokedVia.RevokeLink) to
                    "This Computer's key was revoked with the link emailed when it was attached.",
                leftRevocation.copy(via = SettingsComputerRevokedVia.ComputerDetached) to
                    "This Computer was disconnected from the server on its own machine, which revoked its key.",
                leftRevocation.copy(via = SettingsComputerRevokedVia.SessionRevoked) to
                    "The sign-in that attached this Computer was signed out (for example, by a password change or its revoke link), which revoked its key.",
                leftRevocation.copy(via = SettingsComputerRevokedVia.ApproverSessionRevoked) to
                    "The sign-in that approved this Computer was signed out for security, which revoked its key.",
                leftRevocation.copy(via = SettingsComputerRevokedVia.SetupReset) to
                    "This Computer's key was revoked when the server's setup was reset.",
                leftRevocation.copy(via = null) to
                    "This Computer's key was revoked.",
            )
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(fixedTimeContext)) {
                    RaftComputersTheme {
                        androidx.compose.foundation.layout.Column {
                            cases.forEach { (revocation, _) ->
                                RaftComputerRevokedNotice(
                                    revocation = revocation,
                                    agentCount = if (revocation.via == SettingsComputerRevokedVia.SetupReset) 1 else 0,
                                    setupCommand = null,
                                    onCopy = {},
                                    timeContext = fixedTimeContext,
                                )
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        cases.forEach { (revocation, expected) ->
            val fullExpected =
                if (revocation.via == SettingsComputerRevokedVia.SetupReset) {
                    "$expected 1 agent on it is offline."
                } else {
                    expected
                }
            onNodeWithText(fullExpected, substring = true).assertExists()
        }
    }

    @Test
    fun liveComputerNeverShowsRevokedStateAndUnrevokedLegacyRowKeepsMigrateBlock() = runRobolectricComposeUiTest {
        showDetail(revokedDetail(revocation = leftRevocation, isComputer = true))
        assertEquals(0, onAllNodes(hasText("Key revoked")).fetchSemanticsNodes().size)
        onNodeWithTag(RaftComputerRevocationTestTags.Root, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun offlineComputerBelow1053ShowsReinstallNoticeWithPackageManagerFirstNote() = runRobolectricComposeUiTest {
        val offlineTooOld =
            SettingsComputerDetailSnapshot(
                computer =
                    SettingsComputerSnapshot(
                        id = "m-old",
                        name = "Old Box",
                        daemonVersion = "",
                        computerVersion = "1.0.52",
                        isComputer = true,
                        online = false,
                        platform = "darwin arm64",
                        versionGate =
                            SettingsComputerVersionGateSnapshot(
                                reason = "computer_version_unsupported",
                                minVersion = "1.0.53",
                                enforcedAt = null,
                                enforced = true,
                            ),
                    ),
                workspaces = emptyList(),
                agents = emptyList(),
                sections = emptyList(),
                canManageMachines = true,
                canEditMachine = true,
                commandContext = commandContext,
            )
        showDetail(offlineTooOld)

        onNodeWithTag("computer-version-notice", useUnmergedTree = true).assertExists()
        onNodeWithText("Agents on this computer aren't running: the version is too old", substring = true).assertIsDisplayed()
        onNodeWithText(
            "v1.0.52 is too old to connect to Raft or upgrade from here. Run the install command on that machine: it stays the same computer, with its agents.",
            substring = true,
        ).assertIsDisplayed()
        onNodeWithText(
            "If raft-computer was installed with npm or Homebrew, uninstall it that way first: the installer won't overwrite it.",
            substring = true,
        ).assertIsDisplayed()
        onNodeWithTag("computer-version-notice-reinstall", useUnmergedTree = true).assertExists()
    }

    @Test
    fun viewerWhoCannotManageOfflineOldComputerGetsAskAdminWithoutPackageManagerNoteOrCommands() = runRobolectricComposeUiTest {
        val offlineTooOld =
            SettingsComputerDetailSnapshot(
                computer =
                    SettingsComputerSnapshot(
                        id = "m-old",
                        name = "Old Box",
                        daemonVersion = "",
                        computerVersion = "1.0.40",
                        isComputer = true,
                        online = false,
                        platform = "linux x64",
                        versionGate =
                            SettingsComputerVersionGateSnapshot(
                                reason = "computer_version_unsupported",
                                minVersion = "1.0.53",
                                enforcedAt = null,
                                enforced = true,
                            ),
                    ),
                workspaces = emptyList(),
                agents = emptyList(),
                sections = emptyList(),
                canManageMachines = false,
                canEditMachine = false,
                commandContext = commandContext,
            )
        showDetail(offlineTooOld)

        onNodeWithText("Ask an admin to reinstall it.", substring = true).assertIsDisplayed()
        assertEquals(0, count("npm or Homebrew", substring = true))
        onNodeWithTag("computer-version-notice-reinstall", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun agentDetailStatusShowsRevokedCauseAndReattachCommandUnderRevokedNotice() = runRobolectricComposeUiTest {
        val revokedMachine =
            ComputerSummary(
                id = "m1",
                name = "Build Box",
                status = ComputerStatus.Offline,
                statusVersion = 1L,
                apiKeyPrefix = null,
                runtimes = emptyList(),
                daemonVersion = null,
                computerVersion = null,
                isComputer = false,
                hostname = null,
                os = "linux x64",
                description = null,
                lastHeartbeat = null,
                createdAt = null,
                revocation = leftRevocation,
            )
        val noteState =
            assertNotNull(
                resolveMembersAgentComputerRevokedNoteState(
                    machineId = "m1",
                    machines = listOf(revokedMachine),
                    commandContext = commandContext,
                )
            )
        assertEquals("raft-computer setup /acme --machine m1", noteState.setupCommand)

        // Live Computer, missing revocation, deleted, external, or bounded projection never resolves a note.
        assertNull(resolveMembersAgentComputerRevokedNoteState("m1", listOf(revokedMachine.copy(isComputer = true)), commandContext))
        assertNull(resolveMembersAgentComputerRevokedNoteState("m1", listOf(revokedMachine.copy(revocation = null)), commandContext))
        assertNull(resolveMembersAgentComputerRevokedNoteState("m1", listOf(revokedMachine), commandContext, deleted = true))
        assertNull(resolveMembersAgentComputerRevokedNoteState("m1", listOf(revokedMachine), commandContext, isExternalAgent = true))
        assertNull(resolveMembersAgentComputerRevokedNoteState("m1", listOf(revokedMachine), commandContext, isBoundedPublicProjection = true))

        val member =
            MemberProfile(
                id = MemberId("a1"),
                displayName = "Worker",
                handle = "worker",
                kind = MemberKind.Agent,
                agentBlockedReason = "computer_access_revoked",
            )
        val blockedIndicator =
            RaftAgentStatusIndicatorState(
                status = AgentStatus.Error,
                color = RaftColor.AccentOrange,
                label = AgentBlockedState.ComputerAccessRevoked.englishStatusLabel,
                external = false,
                blocked = AgentBlockedState.ComputerAccessRevoked,
            )

        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(fixedTimeContext)) {
                    MembersAgentProfileStatus(
                        member = member,
                        summary = null,
                        agentStatusObservedState = blockedIndicator,
                        agentStatusIndicatorState = blockedIndicator,
                        timeContext = fixedTimeContext,
                        computerRevokedNote = noteState,
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithText("Computer access revoked: reattach the computer or move the agent").assertIsDisplayed()
        onNodeWithTag(MembersAgentComputerRevokedTestTags.Note, useUnmergedTree = true).assertExists()
        onNodeWithText("Disconnected on Nov 20, 2026").assertIsDisplayed()
        onNodeWithText("Jonathan left the server, so this Computer's key was revoked.").assertIsDisplayed()
        onNodeWithText(
            "Reattach Build Box: on that machine, run raft-computer setup /acme --machine m1 and approve the sign-in with an owner or admin account. Whoever approves becomes its Attached by.",
        ).assertIsDisplayed()
        onNodeWithText("Or move this agent to another computer.").assertIsDisplayed()
    }

    private fun ComposeUiTest.count(text: String, substring: Boolean = false): Int =
        onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size
}
