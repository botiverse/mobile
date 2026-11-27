package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.LocalRaftHost
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.members.MEMBER_DETAIL_PAGE
import build.raft.app.shell.RaftDetailConversation
import build.raft.app.shell.RaftWideSideColumns
import build.raft.app.shell.rememberRaftWideSideColumns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The side columns on a real composition ([rememberRaftWideSideColumns] as SettingsHomePage composes it, from the
 * detail picked): they are the conversation's, not the detail's. A message jumped to in it, a reply thread shown in
 * its place and Back to it keep the profile and the Artifact; another conversation closes them, for its beside
 * memory to open its own. See AGENTS.md "Compose UI tests".
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftWideSideColumnsConversationUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val channel = RaftAdaptiveDetailIdentity(serverId = "s1", threadId = "c1", surfaceKind = RaftThreadSurfaceKind.Channel)
    private val replyThread =
        RaftAdaptiveDetailIdentity(
            serverId = "s1",
            threadId = "stream-m9",
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            parentChannelId = "c1",
            parentMessageId = "m9",
        )

    @Test
    fun theColumnsStayWithTheirConversationAndCloseOnAnother() =
        runRobolectricComposeUiTest {
            var selection by mutableStateOf<RaftAdaptiveDetailIdentity?>(channel)
            var columns: RaftWideSideColumns? = null
            val transport = HostBridgeTransport { _, _, _ -> }
            val navigator =
                object : RaftCmpNavigator {
                    override fun openPage(pageName: String, pageData: JsonObject) = Unit

                    override fun closePage() = Unit
                }
            val host = RaftCmpHost(BridgeWireHostCapabilities(transport), navigator, bridgeTransport = transport)
            setContent {
                CompositionLocalProvider(LocalRaftHost provides host) {
                    columns = rememberRaftWideSideColumns(selection, navRailActive = true)
                }
            }
            val page = JsonObject(emptyMap())
            runOnIdle {
                val side = checkNotNull(columns)
                side.profile = side.profile.open(MEMBER_DETAIL_PAGE, page)
                side.artifact = side.artifact.open(RAFT_ARTIFACT_VIEWER_PAGE, page, side.profile)
            }
            for (sameConversation in listOf(channel.copy(focusedMessageId = "m5"), replyThread, channel)) {
                runOnIdle { selection = sameConversation }
                runOnIdle {
                    val side = checkNotNull(columns)
                    assertTrue(side.profile.isOpen && side.artifact.isOpen, "kept beside $sameConversation")
                    assertEquals(RaftDetailConversation("s1", "c1"), side.conversation)
                }
            }
            runOnIdle { selection = channel.copy(threadId = "c2") }
            runOnIdle {
                val side = checkNotNull(columns)
                assertFalse(side.profile.isOpen, "another conversation closes the profile")
                assertFalse(side.artifact.isOpen, "and the Artifact")
                assertEquals(RaftDetailConversation("s1", "c2"), side.conversation)
            }
        }
}
