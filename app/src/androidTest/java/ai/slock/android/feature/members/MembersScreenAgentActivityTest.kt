package ai.slock.android.feature.members

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import ai.slock.android.core.data.repository.AgentActivityEntry
import ai.slock.android.core.data.repository.AgentActivitySnapshot
import ai.slock.android.core.data.repository.AgentDmSnapshot
import ai.slock.android.core.data.repository.AgentReminderSnapshot
import ai.slock.android.core.data.repository.AgentWorkspaceSnapshot
import ai.slock.android.core.data.repository.SettingsComputerWorkspaceSnapshot
import ai.slock.android.core.model.AgentStatus
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.android.ui.components.SlockAdaptiveAppLayoutInfo
import ai.slock.android.ui.components.SlockAdaptiveNavigationPlacement
import ai.slock.android.ui.components.SlockWindowLayoutMode
import ai.slock.android.ui.theme.SlockTheme
import org.junit.Rule
import org.junit.Test

class MembersScreenAgentActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun wideMembersOpensDetailPaneBesideListAndClosesIt() {
        val agent = MemberProfile(
            id = MemberId("agent-wide"),
            displayName = "Wide Agent",
            handle = "@wide-agent",
            kind = MemberKind.Agent,
            description = "Checks wide layout",
            roleDescription = "agent",
            permissionBadges = listOf("computer:Jiachengs-MacBook-Pro.local"),
            presence = Presence.Active,
            agentStatus = AgentStatus.Online
        )
        val human = MemberProfile(
            id = MemberId("human-wide"),
            displayName = "Wide Human",
            handle = "@wide-human",
            kind = MemberKind.Human,
            description = "Designer",
            presence = Presence.Away
        )
        val openedMembers = mutableListOf<String>()

        composeRule.setContent {
            SlockTheme {
                MembersRouteScreen(
                    state = MembersUiState(
                        loadState = ScreenLoadState.Content(
                            MembersContent(
                                server = ServerSummary(ServerId("server-1"), "Slock"),
                                members = listOf(agent, human)
                            )
                        )
                    ),
                    adaptiveLayoutInfo = SlockAdaptiveAppLayoutInfo(
                        mode = SlockWindowLayoutMode.MediumListDetail,
                        navigationPlacement = SlockAdaptiveNavigationPlacement.NavigationRail,
                        width = 900.dp,
                        height = 700.dp
                    ),
                    onOpenMemberProfile = { openedMembers += it.id.value }
                )
            }
        }

        composeRule.onNodeWithText("Members").assertIsDisplayed()
        composeRule.onNodeWithText("Wide Agent").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Close member detail").assertCountEquals(0)
        composeRule.onAllNodesWithTag(MEMBERS_WIDE_DETAIL_PANE_TAG).assertCountEquals(0)
        composeRule.onAllNodesWithTag(MEMBERS_WIDE_DIVIDER_TAG).assertCountEquals(0)

        composeRule.onNodeWithText("Wide Agent").performClick()

        composeRule.onNodeWithText("Members").assertIsDisplayed()
        composeRule.onNodeWithTag(MEMBERS_WIDE_DETAIL_PANE_TAG).assertIsDisplayed()
        composeRule.onAllNodesWithTag(MEMBERS_WIDE_DIVIDER_TAG).assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Close member detail").assertIsDisplayed()
        composeRule.onNode(hasText("PROFILE") and hasClickAction()).assertIsDisplayed()
        assert(openedMembers == listOf("agent-wide")) {
            "Expected wide pane to request the clicked member profile once, got $openedMembers"
        }

        composeRule.onNodeWithContentDescription("Close member detail").performClick()

        composeRule.onNodeWithText("Members").assertIsDisplayed()
        composeRule.onNodeWithText("Wide Agent").assertIsDisplayed()
        composeRule.onAllNodesWithTag(MEMBERS_WIDE_DETAIL_PANE_TAG).assertCountEquals(0)
        composeRule.onAllNodesWithTag(MEMBERS_WIDE_DIVIDER_TAG).assertCountEquals(0)
    }

    @Test
    fun membersPageGroupsAgentsByComputerName() {
        val macAgent = MemberProfile(
            id = MemberId("agent-mac"),
            displayName = "Mac Agent",
            handle = "@mac-agent",
            kind = MemberKind.Agent,
            roleDescription = "agent",
            permissionBadges = listOf("computer:Jiachengs-MacBook-Pro.local"),
            presence = Presence.Active,
            agentStatus = AgentStatus.Online
        )
        val ciAgent = MemberProfile(
            id = MemberId("agent-ci"),
            displayName = "CI Agent",
            handle = "@ci-agent",
            kind = MemberKind.Agent,
            roleDescription = "agent",
            permissionBadges = listOf("computer:CI runner"),
            presence = Presence.Away,
            agentStatus = AgentStatus.Idle
        )
        val human = MemberProfile(
            id = MemberId("human-grouping"),
            displayName = "Human Member",
            handle = "@human-member",
            kind = MemberKind.Human,
            presence = Presence.Active
        )

        composeRule.setContent {
            SlockTheme {
                MembersPageShell(
                    state = MembersUiState(
                        loadState = ScreenLoadState.Content(
                            MembersContent(
                                server = ServerSummary(ServerId("server-grouping"), "Slock"),
                                members = listOf(macAgent, ciAgent, human)
                            )
                        )
                    )
                )
            }
        }

        composeRule.onNodeWithText("AGENTS").assertIsDisplayed()
        composeRule.onAllNodesWithText("Jiachengs-MacBook-Pro.local").assertCountEquals(1)
        composeRule.onAllNodesWithText("CI runner").assertCountEquals(1)
        composeRule.onNodeWithText("Mac Agent").assertIsDisplayed()
        composeRule.onNodeWithText("CI Agent").assertIsDisplayed()
        composeRule.onNodeWithText("HUMANS").assertIsDisplayed()
        composeRule.onNodeWithText("Human Member").assertIsDisplayed()
    }

    @Test
    fun agentActivityDetailShowsFormattedScrollableNewestFirstTimeline() {
        val agent = MemberProfile(
            id = MemberId("agent-activity"),
            displayName = "Activity Agent",
            handle = "@activity-agent",
            kind = MemberKind.Agent,
            description = "Runs activity tests",
            roleDescription = "agent",
            presence = Presence.Active,
            agentStatus = AgentStatus.Thinking
        )
        val olderEntries = (1..18).map { index ->
            AgentActivityEntry(
                id = "old-$index",
                type = "text",
                title = "Older event $index",
                body = "Older body $index",
                timestamp = "2026-05-09T01:${index.toString().padStart(2, '0')}:00Z"
            )
        }
        val newest = AgentActivityEntry(
            id = "newest",
            type = "tool_start",
            title = "Tool started",
            body = "Running connected verification",
            timestamp = "2026-05-10T02:20:00Z",
            details = listOf(
                "Tool" to "Gradle connected test",
                "Command" to ":app:connectedConnectedTestAndroidTest"
            )
        )
        val activityEntries = mutableStateOf(olderEntries + newest)

        composeRule.setContent {
            SlockTheme {
                MembersRouteScreen(
                    state = MembersUiState(
                        loadState = ScreenLoadState.Content(
                            MembersContent(
                                server = ServerSummary(ServerId("server-1"), "Slock"),
                                members = listOf(agent)
                            )
                        ),
                        selectedAgentActivityState = ScreenLoadState.Content(
                            AgentActivitySnapshot(
                                agentId = agent.id,
                                activity = activityEntries.value
                            )
                        )
                    ),
                    initialSelectedMemberId = agent.id.value,
                    initialSelectedDetailTab = "Activity"
                )
            }
        }

        composeRule.onNodeWithText("Tool started").assertIsDisplayed()
        composeRule.onNodeWithText("Thinking", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Agent status: Thinking").assertIsDisplayed()
        composeRule.onNodeWithText("Tool start").assertIsDisplayed()
        composeRule.onNodeWithText("10:20").assertIsDisplayed()
        composeRule.onNodeWithText("Running connected verification").assertIsDisplayed()
        composeRule.onNodeWithText("Tool: Gradle connected test").assertIsDisplayed()
        composeRule.onNodeWithText("Command: :app:connectedConnectedTestAndroidTest").assertIsDisplayed()

        composeRule.onNodeWithTag("agent_activity_timeline").performScrollToIndex(0)
        composeRule.onNodeWithText("Older event 1").assertIsDisplayed()

        composeRule.runOnIdle {
            activityEntries.value = activityEntries.value + AgentActivityEntry(
                id = "follow-bottom",
                type = "text",
                title = "Newest appended event",
                body = "Follow bottom after refresh",
                timestamp = "2026-05-10T02:21:00Z"
            )
        }

        composeRule.onNodeWithText("Newest appended event").assertIsDisplayed()
        composeRule.onNodeWithText("Follow bottom after refresh").assertIsDisplayed()
    }

    @Test
    fun agentDetailContentSwipesBetweenTabs() {
        val agent = MemberProfile(
            id = MemberId("agent-tabs"),
            displayName = "Tab Agent",
            handle = "@tab-agent",
            kind = MemberKind.Agent,
            roleDescription = "Runs tab tests",
            presence = Presence.Active,
            agentStatus = AgentStatus.Online
        )

        composeRule.setContent {
            SlockTheme {
                MembersRouteScreen(
                    state = MembersUiState(
                        loadState = ScreenLoadState.Content(
                            MembersContent(
                                server = ServerSummary(ServerId("server-1"), "Slock"),
                                members = listOf(agent)
                            )
                        ),
                        selectedAgentDmsState = ScreenLoadState.Content(
                            AgentDmSnapshot(agentId = agent.id, directMessages = emptyList())
                        ),
                        selectedAgentRemindersState = ScreenLoadState.Content(
                            AgentReminderSnapshot(agentId = agent.id, reminders = emptyList())
                        )
                    ),
                    initialSelectedMemberId = agent.id.value,
                    initialSelectedDetailTab = "Profile"
                )
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("DISPLAY NAME").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("DISPLAY NAME").assertIsDisplayed()
        composeRule.onNode(hasText("PROFILE") and hasClickAction()).assertIsSelected()

        composeRule.onNodeWithText("DISPLAY NAME").performTouchInput { swipeLeft() }
        composeRule.onNode(hasText("AGENT DMS") and hasClickAction()).assertIsSelected()

        composeRule.onNodeWithText("No agent DMs").performTouchInput { swipeLeft() }
        composeRule.onNode(hasText("REMINDERS") and hasClickAction()).assertIsSelected()

        composeRule.onNodeWithText("No scheduled reminders").performTouchInput { swipeRight() }
        composeRule.onNode(hasText("AGENT DMS") and hasClickAction()).assertIsSelected()
    }

    @Test
    fun workspaceTabShowsRealWorkspaceRows() {
        val agent = MemberProfile(
            id = MemberId("agent-workspace"),
            displayName = "Workspace Agent",
            handle = "@workspace-agent",
            kind = MemberKind.Agent,
            description = "Uses a real workspace snapshot",
            roleDescription = "agent",
            presence = Presence.Active,
            agentStatus = AgentStatus.Working
        )

        composeRule.setContent {
            SlockTheme {
                MembersRouteScreen(
                    state = MembersUiState(
                        loadState = ScreenLoadState.Content(
                            MembersContent(
                                server = ServerSummary(ServerId("server-1"), "Slock"),
                                members = listOf(agent)
                            )
                        ),
                        selectedAgentWorkspaceState = ScreenLoadState.Content(
                            AgentWorkspaceSnapshot(
                                agentId = agent.id,
                                computerName = "Mac Studio",
                                workspaces = listOf(
                                    SettingsComputerWorkspaceSnapshot(
                                        id = "workspace-1",
                                        name = "Slock",
                                        path = "/Users/artin/AndroidStudioProjects/Slock",
                                        statusLabel = "active",
                                        lastActiveLabel = "2026-05-13T19:30:00Z"
                                    )
                                )
                            )
                        )
                    ),
                    initialSelectedMemberId = agent.id.value,
                    initialSelectedDetailTab = "Workspace"
                )
            }
        }

        composeRule.onNode(hasText("WORKSPACE") and hasClickAction()).assertIsSelected()
        composeRule.onNodeWithText("Computer").assertIsDisplayed()
        composeRule.onNodeWithText("Mac Studio").assertIsDisplayed()
        composeRule.onNodeWithText("Slock").assertIsDisplayed()
        composeRule.onNodeWithText("/Users/artin/AndroidStudioProjects/Slock").assertIsDisplayed()
        composeRule.onNodeWithText("Status").assertIsDisplayed()
        composeRule.onNodeWithText("Active").assertIsDisplayed()
    }

    @Test
    fun agentProfileDetailDoesNotDuplicateHeaderIdentityOrStatus() {
        val agent = MemberProfile(
            id = MemberId("agent-single-identity"),
            displayName = "Single Identity Agent",
            handle = "@single-identity",
            kind = MemberKind.Agent,
            description = "Keeps one header identity",
            roleDescription = "agent",
            presence = Presence.Active,
            agentStatus = AgentStatus.Online
        )

        composeRule.setContent {
            SlockTheme {
                MembersRouteScreen(
                    state = MembersUiState(
                        loadState = ScreenLoadState.Content(
                            MembersContent(
                                server = ServerSummary(ServerId("server-1"), "Slock"),
                                members = listOf(agent)
                            )
                        )
                    ),
                    initialSelectedMemberId = agent.id.value,
                    initialSelectedDetailTab = "Profile"
                )
            }
        }

        composeRule.onAllNodesWithText("Single Identity Agent").assertCountEquals(1)
        composeRule.onAllNodesWithText("Online", substring = true).assertCountEquals(1)
        composeRule.onNode(hasText("PROFILE") and hasClickAction()).assertIsDisplayed()
        composeRule.onNode(
            hasText("@single-identity") and hasAnyAncestor(hasScrollAction())
        ).assertExists()
    }

    @Test
    fun agentProfileDetailEditsDisplayNameAndDescription() {
        val agentState = mutableStateOf(
            MemberProfile(
                id = MemberId("agent-edit-profile"),
                displayName = "Editable Agent",
                handle = "@editable-agent",
                kind = MemberKind.Agent,
                description = "Old description",
                roleDescription = "agent",
                presence = Presence.Active,
                agentStatus = AgentStatus.Online
            )
        )
        val saveCalls = mutableListOf<Pair<String, String>>()
        val saveState = mutableStateOf<AgentProfileSaveState>(AgentProfileSaveState.Idle)

        composeRule.setContent {
            SlockTheme {
                MembersRouteScreen(
                    state = MembersUiState(
                        loadState = ScreenLoadState.Content(
                            MembersContent(
                                server = ServerSummary(ServerId("server-1"), "Slock"),
                                members = listOf(agentState.value)
                            )
                        )
                    ).copy(agentProfileSaveState = saveState.value),
                    initialSelectedMemberId = agentState.value.id.value,
                    initialSelectedDetailTab = "Profile",
                    onSaveAgentProfile = { _, displayName, description ->
                        saveCalls += displayName to description
                        agentState.value = agentState.value.copy(
                            displayName = displayName,
                            description = description
                        )
                        saveState.value = AgentProfileSaveState.Saved
                    }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Edit agent profile").assertIsDisplayed()
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Edit agent profile").performClick()

        composeRule.onAllNodes(hasSetTextAction())[0].performTextReplacement("Updated Agent")
        composeRule.onAllNodes(hasSetTextAction())[1].performTextReplacement("Updated description")
        composeRule.onNodeWithText("Save").performClick()

        assert(saveCalls.single() == ("Updated Agent" to "Updated description")) {
            "Expected edited profile save payload, got $saveCalls"
        }
        composeRule.onAllNodesWithText("Updated Agent").assertCountEquals(2)
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }
}
