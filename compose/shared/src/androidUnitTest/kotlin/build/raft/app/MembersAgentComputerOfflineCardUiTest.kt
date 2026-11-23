package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AppLocale
import build.raft.app.data.ComputerStatus
import build.raft.app.data.ComputerSummary
import build.raft.app.data.SettingsComputerCommandContext
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersState
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.ServerId
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class MembersAgentComputerOfflineCardUiTest {
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
            localOffsetMinutes = 8 * 60,
            todayEpochDay = raftEpochDayForDate(2026, 6, 19),
            nowEpochMillis = 1_781_872_200_000L,
            timeFormat = RaftTimeFormat24Hour,
        )

    private fun sampleOfflineMachine(
        id: String = "m-1",
        name: String = "martin-mac",
        status: ComputerStatus = ComputerStatus.Offline,
        lastHeartbeat: String? = "2026-06-19T04:15:00Z",
        creatorUserId: String? = "u-martin",
    ): ComputerSummary =
        ComputerSummary(
            id = id,
            name = name,
            status = status,
            statusVersion = 1L,
            apiKeyPrefix = null,
            runtimes = emptyList(),
            daemonVersion = "0.65.0",
            computerVersion = "0.0.50",
            isComputer = true,
            hostname = null,
            os = "darwin",
            description = null,
            lastHeartbeat = lastHeartbeat,
            createdAt = null,
            creatorUserId = creatorUserId,
        )

    @Composable
    private fun ProfileWithComputer(
        machine: ComputerSummary,
        deletedAt: String? = null,
        isExternal: Boolean = false,
        accessMode: MemberDetailAccessMode = MemberDetailAccessMode.LocalFull,
    ) {
        val pager =
            remember {
                object : Pager() {}.apply {
                    pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                }
            }
        val agent =
            MemberProfile(
                id = MemberId("agent-1"),
                displayName = "Ruby",
                handle = "@ruby",
                kind = MemberKind.Agent,
                isExternal = isExternal,
                agentDeletedAt = deletedAt,
            )
        val detail =
            AgentDetailSnapshot(
                agentId = agent.id,
                summary =
                    AgentDetailSummary(
                        id = agent.id.value,
                        name = "ruby",
                        displayName = "Ruby",
                        machineId = machine.id,
                        machineName = machine.name,
                        status = "stopped",
                        isExternal = isExternal,
                        deletedAt = deletedAt,
                    ),
                directMessages = emptyList(),
                reminders = emptyList(),
                activity = emptyList(),
            )
        val serverMembers =
            SharedServerMembersState(
                entriesByServerId =
                    mapOf(
                        "server-home" to
                            SharedServerMembersEntry(
                                serverId = "server-home",
                                members =
                                    listOf(
                                        MemberProfile(
                                            id = MemberId("u-martin"),
                                            displayName = "Martin",
                                            handle = "@martin",
                                            kind = MemberKind.Human,
                                        ),
                                    ),
                            ),
                    ),
            )
        val commandContext =
            SettingsComputerCommandContext(
                serverSlug = "acme",
                deploymentEnv = "production",
                serverUrl = RAFT_COMPUTER_DEFAULT_SERVER_URL,
            )
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftDesignSystemTheme {
                MembersDetailContent(
                    member = agent,
                    currentUserId = "u-martin",
                    currentServerId = "server-home",
                    serverMembersState = serverMembers,
                    agentDetailState = MembersAgentDetailLoadState.Content(detail),
                    canViewPrivateAgentSurfaces = { _, _ -> false },
                    canManageAgent = { _, _ -> false },
                    machineStatusRow = {
                        MembersMachineStatusRow.Machine(
                            online = machine.status == ComputerStatus.Online,
                            runLabel = MembersMachineRunLabel.ComputerOffline,
                        )
                    },
                    offlineComputerInputs =
                        MembersAgentOfflineComputerInputs(
                            machines = listOf(machine),
                            commandContext = commandContext,
                        ),
                    actions =
                        MembersDetailActions(
                            onBack = {},
                            onOpenDirectMessage = {},
                            onOpenComputer = {},
                            onCopyMemberId = {},
                            onOpenUrl = {},
                            onUnavailableAction = {},
                        ),
                    agentCallbacks =
                        MembersAgentDetailCallbacks(
                            onUpdateAgentProfile = null,
                            onAgentLifecycleAction = null,
                            agentWorkspaceTreeLoader = null,
                            agentWorkspaceChildrenLoader = null,
                            onCopyAgentDiagnostics = {},
                            onReportAgentIssue = null,
                            onUploadAgentAvatar = null,
                        ),
                    timeContext = fixedTimeContext,
                    presentation = MembersAgentDetailPresentation(stableVisualCapture = false),
                    accessMode = accessMode,
                )
            }
        }
    }

    @Test
    fun rendersOfflineCardWithTimestampOwnerLinkAndCopyAction() = runRobolectricComposeUiTest {
        var openedComputer = 0
        val copiedCommands = mutableListOf<String>()
        val state =
            MembersAgentComputerOfflineCardState(
                machineId = "m-1",
                computerName = "martin-mac",
                ownerName = "Martin",
                sinceEpochMillis = null,
                sinceIso = "2026-06-19T04:15:00Z",
                restartCommand = "raft-computer restart /acme",
            )

        setContent {
            RaftDesignSystemTheme {
                MembersAgentComputerOfflineCard(
                    state = state,
                    timeContext = fixedTimeContext,
                    onOpenComputer = { openedComputer += 1 },
                    onCopyCommand = { copiedCommands += it },
                )
            }
        }
        waitForIdle()

        onNodeWithTag(MembersAgentComputerOfflineTestTags.Card).assertIsDisplayed()
        onNodeWithText("martin-mac has been offline since 12:15").assertIsDisplayed()
        onNodeWithText("Turn on Martin's computer martin-mac and keep it from sleeping").assertIsDisplayed()
        onNodeWithText("On that computer, open Raft Desktop or run:").assertIsDisplayed()
        onNodeWithText("raft-computer restart /acme").assertIsDisplayed()

        // Tapping the computer name link opens the Computer page.
        onNodeWithText("martin-mac").performClick()
        assertEquals(1, openedComputer)

        // Copy button starts as "Copy command", invokes copy callback, and flips to "Copied".
        onNodeWithTag(MembersAgentComputerOfflineTestTags.CopyCommand).assertIsDisplayed()
        onNodeWithContentDescription("Copy command")
            .assertIsDisplayed()
            .performClick()
        waitForIdle()

        assertEquals(listOf("raft-computer restart /acme"), copiedCommands)
        onNodeWithContentDescription("Copied").assertIsDisplayed()
    }

    @Test
    fun rendersOfflineCardWithoutTimestampOrOwner() = runRobolectricComposeUiTest {
        val state =
            MembersAgentComputerOfflineCardState(
                machineId = "computer-studio",
                computerName = "Studio Test Rig",
                ownerName = null,
                sinceEpochMillis = null,
                sinceIso = null,
                restartCommand = "raft-computer restart /visual",
            )

        setContent {
            RaftDesignSystemTheme {
                MembersAgentComputerOfflineCard(
                    state = state,
                    timeContext = fixedTimeContext,
                    onOpenComputer = null,
                    onCopyCommand = {},
                )
            }
        }
        waitForIdle()

        onNodeWithTag(MembersAgentComputerOfflineTestTags.Title).assertTextEquals("Studio Test Rig is offline")
        onNodeWithText("Turn on the computer Studio Test Rig and keep it from sleeping").assertIsDisplayed()
        onNodeWithText("On that computer, open Raft Desktop or run:").assertIsDisplayed()
        onNodeWithText("raft-computer restart /visual").assertIsDisplayed()
    }

    @Test
    fun rendersLocalizedSimplifiedChineseCopy() = runRobolectricComposeUiTest {
        StringDesc.localeType = AppLocale.SimplifiedChinese.stringDescLocaleType
        val state =
            MembersAgentComputerOfflineCardState(
                machineId = "m-1",
                computerName = "martin-mac",
                ownerName = "Martin",
                sinceEpochMillis = null,
                sinceIso = "2026-06-19T04:15:00Z",
                restartCommand = "raft-computer restart /acme",
            )

        setContent {
            RaftDesignSystemTheme {
                MembersAgentComputerOfflineCard(
                    state = state,
                    timeContext = fixedTimeContext,
                    onOpenComputer = {},
                    onCopyCommand = {},
                )
            }
        }
        waitForIdle()

        onNodeWithText("martin-mac 自 12:15 起离线").assertIsDisplayed()
        onNodeWithText("把 Martin 的电脑「martin-mac」打开，别让它睡眠").assertIsDisplayed()
        onNodeWithText("在那台电脑上打开 Raft Desktop，或运行：").assertIsDisplayed()
    }

    @Test
    fun membersDetailContentShowsOfflineCardForManagedAgentOnOfflineComputer() = runRobolectricComposeUiTest {
        setContent {
            ProfileWithComputer(machine = sampleOfflineMachine(status = ComputerStatus.Offline))
        }
        waitForIdle()

        onNodeWithTag(MembersAgentComputerOfflineTestTags.Card).assertIsDisplayed()
        onNodeWithText("martin-mac has been offline since 12:15").assertIsDisplayed()
        onNodeWithText("Turn on Martin's computer martin-mac and keep it from sleeping").assertIsDisplayed()
        onNodeWithText("raft-computer restart /acme").assertIsDisplayed()
    }

    @Test
    fun membersDetailContentHidesOfflineCardWhenComputerIsOnline() = runRobolectricComposeUiTest {
        setContent {
            ProfileWithComputer(machine = sampleOfflineMachine(status = ComputerStatus.Online))
        }
        waitForIdle()

        onNodeWithTag(MembersAgentComputerOfflineTestTags.Card).assertDoesNotExist()
    }

    @Test
    fun membersDetailContentHidesOfflineCardForArchivedExternalAndRemoteAgents() = runRobolectricComposeUiTest {
        setContent {
            ProfileWithComputer(
                machine = sampleOfflineMachine(status = ComputerStatus.Offline),
                deletedAt = "2026-06-18T10:00:00Z",
            )
        }
        waitForIdle()
        onNodeWithTag(MembersAgentComputerOfflineTestTags.Card).assertDoesNotExist()
    }

    @Test
    fun membersDetailContentHidesOfflineCardForExternalAgent() = runRobolectricComposeUiTest {
        setContent {
            ProfileWithComputer(
                machine = sampleOfflineMachine(status = ComputerStatus.Offline),
                isExternal = true,
            )
        }
        waitForIdle()
        onNodeWithTag(MembersAgentComputerOfflineTestTags.Card).assertDoesNotExist()
    }

    @Test
    fun membersDetailContentHidesOfflineCardForRemoteProfile() = runRobolectricComposeUiTest {
        setContent {
            ProfileWithComputer(
                machine = sampleOfflineMachine(status = ComputerStatus.Offline),
                accessMode = MemberDetailAccessMode.RemoteProfileOnly,
            )
        }
        waitForIdle()
        onNodeWithTag(MembersAgentComputerOfflineTestTags.Card).assertDoesNotExist()
    }
}
