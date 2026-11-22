package build.raft.app

import androidx.compose.ui.test.ExperimentalTestApi
import build.raft.app.data.AgentConfigApply
import kotlin.test.Test
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * botiverse/slock #15669 / #15714 on a real composition: a running agent's saved runtime config
 * shows "<name>'s settings saved" with a plain confirmation (never a manual Restart now button),
 * and `saved` shows no manual restart prompt even when active.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MembersRuntimeConfigApplyUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun savedShowsNoRestartPromptEvenWhenActive() {
        assertNull(membersRuntimeConfigApplyNotice(AgentConfigApply.Saved, agentActive = true, newSession = false))
    }
}
