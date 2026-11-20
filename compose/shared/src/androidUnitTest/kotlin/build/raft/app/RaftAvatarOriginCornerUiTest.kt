package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import build.raft.app.model.MemberServerRef
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The origin corner on a small face (#proj-raft-ui:#276, artin #proj-mobile:#205): the @ list's
 * candidate from another server shows that server's logo, round and at least 10dp, when the viewer
 * has it; otherwise the plain dot. A large face keeps the square server tile.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftAvatarOriginCornerUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val raft = MemberServerRef("server-raft", "Raft", "raft")

    private fun androidx.compose.ui.test.ComposeUiTest.corner(edge: Int, small: Boolean, logos: Map<String, String>) =
        setContent {
            RaftTheme {
                CompositionLocalProvider(LocalRaftViewerServerLogos provides logos) {
                    RaftAvatarWithOriginCorner(servers = listOf(raft), avatarEdge = edge.dp, small = small) {
                        Box(Modifier.size(edge.dp))
                    }
                }
            }
        }

    @Test
    fun aSmallFaceShowsTheServersLogoRoundAndAtLeastTenDp() = runRobolectricComposeUiTest {
        corner(edge = 19, small = true, logos = mapOf("server-raft" to "https://cdn.example/raft.png"))
        onNodeWithTag(RaftAvatarOriginCornerLogoTestTag, useUnmergedTree = true).assertExists()
        onNodeWithTag(RaftAvatarOriginCornerDotTestTag, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(RaftAvatarOriginCornerTestTag, useUnmergedTree = true)
            .assertWidthIsEqualTo(10.dp)
            .assertHeightIsEqualTo(10.dp)
    }

    @Test
    fun aSmallFaceWithoutTheLogoKeepsThePlainDot() = runRobolectricComposeUiTest {
        corner(edge = 19, small = true, logos = emptyMap())
        onNodeWithTag(RaftAvatarOriginCornerDotTestTag, useUnmergedTree = true).assertExists()
        onNodeWithTag(RaftAvatarOriginCornerLogoTestTag, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aLargeFaceKeepsTheSquareTile() = runRobolectricComposeUiTest {
        corner(edge = 35, small = false, logos = mapOf("server-raft" to "https://cdn.example/raft.png"))
        onNodeWithTag(RaftAvatarOriginCornerTileTestTag, useUnmergedTree = true).assertExists()
        onNodeWithTag(RaftAvatarOriginCornerLogoTestTag, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(RaftAvatarOriginCornerDotTestTag, useUnmergedTree = true).assertDoesNotExist()
    }
}
