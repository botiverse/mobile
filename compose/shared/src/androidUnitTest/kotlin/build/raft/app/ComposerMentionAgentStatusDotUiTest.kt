package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.NeutralAgentStatusLabel
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberKind
import build.raft.app.model.Presence
import build.raft.designsystem.components.RaftSlotKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Web `mentionAgentPresence.behavior.test.tsx` parity (slock #17017, #wg-mobile-sync:#349):
 * unknown-presence agents in the `@` mention picker omit both the status dot and the `avatar-badge`
 * slot instead of painting a gray offline dot, while known statuses keep their dot.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class ComposerMentionAgentStatusDotUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val avatarBadgeMatcher = SemanticsMatcher.expectValue(RaftSlotKey, "avatar-badge")

    private fun agentItem(id: String, name: String = id) =
        RaftComposerMemberItem(
            displayName = name,
            handle = id,
            kind = MemberKind.Agent,
            memberId = id,
        )

    @Test
    fun omitsDotForNullOrNeutralUnknownPresenceAndShowsDotWhenPresenceBecomesKnown() =
        runRobolectricComposeUiTest {
            val neutral =
                SharedServerMemberStatus(
                    status = null,
                    presence = Presence.Offline,
                    statusLabel = NeutralAgentStatusLabel,
                    external = false,
                ).toRaftAgentStatusIndicatorState()
            val statusFlow = MutableStateFlow<RaftAgentStatusIndicatorState?>(null)
            val resolver: RaftAgentStatusResolver = { statusFlow }

            setContent {
                RaftDesignSystemTheme {
                    Column {
                        ComposerMemberSuggestionRow(
                            member = agentItem("agent-unknown", "Unknown Agent"),
                            inChannel = true,
                            selected = false,
                            agentStatusResolver = resolver,
                            onClick = {},
                        )
                    }
                }
            }
            waitForIdle()
            assertEquals(0, onAllNodes(avatarBadgeMatcher, useUnmergedTree = true).fetchSemanticsNodes().size)

            runOnUiThread { statusFlow.value = neutral }
            waitForIdle()
            assertEquals(
                0,
                onAllNodes(avatarBadgeMatcher, useUnmergedTree = true).fetchSemanticsNodes().size,
                "neutral unknown sentinel must not paint a gray offline dot or badge shell",
            )

            runOnUiThread {
                statusFlow.value = raftAgentStatusIndicatorState(AgentStatus.Offline, Presence.Offline, external = false)
            }
            waitForIdle()
            assertEquals(
                1,
                onAllNodes(avatarBadgeMatcher, useUnmergedTree = true).fetchSemanticsNodes().size,
                "known offline agent renders its status dot",
            )

            runOnUiThread {
                statusFlow.value = raftAgentStatusIndicatorState(AgentStatus.Online, Presence.Active, external = false)
            }
            waitForIdle()
            assertEquals(1, onAllNodes(avatarBadgeMatcher, useUnmergedTree = true).fetchSemanticsNodes().size)
        }

    @Test
    fun omitsDotForMutedCandidateEvenWhenAgentPresenceIsKnown() = runRobolectricComposeUiTest {
        val online = raftAgentStatusIndicatorState(AgentStatus.Online, Presence.Active, external = false)
        val resolver: RaftAgentStatusResolver = { MutableStateFlow(online) }

        setContent {
            RaftDesignSystemTheme {
                Column {
                    ComposerMemberSuggestionRow(
                        member = agentItem("agent-muted", "Muted Agent"),
                        inChannel = false,
                        selected = false,
                        agentStatusResolver = resolver,
                        onClick = {},
                    )
                }
            }
        }
        waitForIdle()
        assertEquals(0, onAllNodes(avatarBadgeMatcher, useUnmergedTree = true).fetchSemanticsNodes().size)
    }
}
