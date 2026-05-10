package ai.slock.android.feature.members

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
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
import ai.slock.android.core.model.AgentStatus
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.android.ui.theme.SlockTheme
import org.junit.Rule
import org.junit.Test

class MembersScreenAgentActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun agentActivityDetailShowsFormattedScrollableNewestFirstTimeline() {
        val agent = MemberProfile(
            id = MemberId("agent-activity"),
            displayName = "Activity Agent",
            handle = "@activity-agent",
            kind = MemberKind.Agent,
            roleDescription = "Runs activity tests",
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
    fun agentProfileDetailDoesNotDuplicateHeaderIdentityOrStatus() {
        val agent = MemberProfile(
            id = MemberId("agent-single-identity"),
            displayName = "Single Identity Agent",
            handle = "@single-identity",
            kind = MemberKind.Agent,
            roleDescription = "Keeps one header identity",
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
                roleDescription = "Old description",
                presence = Presence.Active,
                agentStatus = AgentStatus.Online
            )
        )
        val saveCalls = mutableListOf<Pair<String, String>>()

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
                    ),
                    initialSelectedMemberId = agentState.value.id.value,
                    initialSelectedDetailTab = "Profile",
                    onSaveAgentProfile = { _, displayName, description ->
                        saveCalls += displayName to description
                        agentState.value = agentState.value.copy(
                            displayName = displayName,
                            roleDescription = description
                        )
                    }
                )
            }
        }

        composeRule.onAllNodes(hasSetTextAction())[0].performTextReplacement("Updated Agent")
        composeRule.onAllNodes(hasSetTextAction())[1].performTextReplacement("Updated description")
        composeRule.onNodeWithText("Save").performClick()

        assert(saveCalls.single() == ("Updated Agent" to "Updated description")) {
            "Expected edited profile save payload, got $saveCalls"
        }
        composeRule.onAllNodesWithText("Updated Agent").assertCountEquals(2)
    }
}
