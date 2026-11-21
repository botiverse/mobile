package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.MemberServerRef
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The members page of a joint channel on a real composition (web jointMemberListGroups behavior
 * test, botiverse/slock #12853): "Members of Bravo" then "Other servers" in place of Humans and
 * Agents, people before agents in each, and a person's "Also in …" spoken as "{name} is also a
 * member of {servers}"; an agent's stays the short text. Any other channel keeps Humans and Agents.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class ChannelMembersJointGroupsUiTest {
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

    private val alpha = MemberServerRef("server-alpha", "Alpha", "alpha")

    // Seen from server Bravo, in roster order (people and agents interleaved).
    private val roster =
        listOf(
            RaftThreadMemberItem("human-ebby", "Ebby", handle = "ebby", inViewerServer = true, alsoInServerLabels = listOf("Alpha", "Charlie")),
            RaftThreadMemberItem("agent-alpha", "AlphaBot", handle = "alphabot", kind = "agent", inViewerServer = false, originServers = listOf(alpha)),
            RaftThreadMemberItem("agent-tenny", "Tenny", handle = "tenny", kind = "agent", inViewerServer = true, alsoInServerLabels = listOf("Delta")),
            RaftThreadMemberItem("human-dana", "Dana", handle = "dana", inViewerServer = false, originServers = listOf(alpha)),
        )

    private fun ComposeUiTest.showRoster(channelKind: String?) {
        setContent {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                ThreadMembersPageContent(
                    members = roster,
                    filteredMembers = roster,
                    filteredExternal = emptyList(),
                    externalMemberCount = 0,
                    candidates = emptyList(),
                    rosterQuery = TextFieldValue(""),
                    onQueryChange = {},
                    agentsExpanded = true,
                    humansExpanded = true,
                    externalExpanded = true,
                    onAgentsExpandedChange = {},
                    onHumansExpandedChange = {},
                    onExternalExpandedChange = {},
                    addDialogVisible = false,
                    onAddVisibleChange = {},
                    addMemberStatus = "idle",
                    addMemberErrorCode = null,
                    removalGate = ThreadMemberRemovalGateState(),
                    membersFailed = false,
                    membersLoading = false,
                    refreshing = false,
                    canAddMembers = false,
                    agentStatusResolver = { flowOf(null) },
                    memberAvatarResolver = EmptyRaftMemberAvatarResolver,
                    onBack = {},
                    onRefresh = {},
                    onOpenMember = {},
                    onRemoveMember = {},
                    onSubmit = {},
                    channelKind = channelKind,
                    viewerServerName = "Bravo",
                )
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.top(text: String): Float = onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    private fun ComposeUiTest.alsoIn(text: String) =
        onNode(hasTestTag(ChannelMemberAlsoInTestTag) and hasText(text), useUnmergedTree = true)

    @Test
    fun aJointChannelListsTheViewedServersMembersThenOtherServers() =
        runRobolectricComposeUiTest {
            showRoster(JointChannelKindWireValue)
            onAllNodesWithText("Humans").assertCountEquals(0)
            onAllNodesWithText("Agents").assertCountEquals(0)
            val order = listOf("Members of Bravo", "Ebby", "Tenny", "Other servers", "Dana", "AlphaBot")
            assertEquals(order, order.sortedBy { top(it) })

            // A person's servers are spoken as the sentence (web `sourceOf`), the list joined as a list.
            alsoIn("Also in Alpha, Charlie").assertContentDescriptionEquals("Ebby is also a member of Alpha and Charlie")
            // An agent's keep the short text.
            alsoIn("Also in Delta").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        }

    @Test
    fun anyOtherChannelKeepsHumansAndAgents() =
        runRobolectricComposeUiTest {
            showRoster("channel")
            onAllNodesWithText("Members of Bravo").assertCountEquals(0)
            onAllNodesWithText("Other servers").assertCountEquals(0)
            val order = listOf("Humans", "Ebby", "Dana", "Agents", "AlphaBot", "Tenny")
            assertEquals(order, order.sortedBy { top(it) })
            alsoIn("Also in Alpha, Charlie").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        }
}
