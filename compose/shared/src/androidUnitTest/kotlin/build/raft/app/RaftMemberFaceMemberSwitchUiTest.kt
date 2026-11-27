package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import build.raft.app.members.RaftMemberAvatarResolver
import build.raft.app.members.rememberRaftMemberAvatarWireValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An Activity row keeps its slot while its sender changes (a new message moves it to the top with
 * another sender). The face, the joint-channel actor and the status dot are read through
 * `collectAsState`, whose state outlives a new flow; unless the read is keyed by the member, the
 * first frame after the switch still painted the previous member (2026-10-28 recording: Echo's row
 * showed M's face for a frame). Every committed frame must pair a member with that member's value.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftMemberFaceMemberSwitchUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    /** Composes [read] for [M], switches the same slot to [ECHO], and returns every committed (member, value). */
    private fun <T> ComposeUiTest.committedAcrossSwitch(read: @Composable (String) -> T): List<Pair<String, T>> {
        var memberId by mutableStateOf(M)
        val committed = mutableListOf<Pair<String, T>>()
        setContent {
            val id = memberId
            val value = read(id)
            SideEffect { committed += id to value }
        }
        runOnIdle { memberId = ECHO }
        waitForIdle()
        return committed
    }

    private fun <T> assertEveryFrameShowsItsOwnMember(committed: List<Pair<String, T>>, expected: (String) -> T) {
        assertEquals(setOf(M, ECHO), committed.map { it.first }.toSet(), "both members composed: $committed")
        committed.forEach { (id, value) ->
            assertEquals(expected(id), value, "frame for $id painted another member's value: $committed")
        }
    }

    @Test
    fun faceNeverPaintsThePreviousSender() =
        runRobolectricComposeUiTest {
            val avatars = mapOf(M to "https://example.test/m.png", ECHO to "https://example.test/echo.png")
            val resolver = RaftMemberAvatarResolver(currentAvatar = { avatars[it] }, avatarFlow = { flowOf(avatars[it]) })
            val committed = committedAcrossSwitch { id -> rememberRaftMemberAvatarWireValue(resolver, id) }
            assertEveryFrameShowsItsOwnMember(committed) { avatars[it] }
        }

    private companion object {
        const val M = "member-m"
        const val ECHO = "agent-echo"
    }
}
