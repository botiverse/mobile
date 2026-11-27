package build.raft.app

import androidx.compose.ui.test.ExperimentalTestApi
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentConfigApply
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.members.MembersRuntimeConfigApplyCopy
import build.raft.app.members.membersRuntimeConfigApplyCopy
import build.raft.app.members.membersRuntimeConfigApplyNotice
import build.raft.app.members.showMembersRuntimeConfigApplyNotice
import build.raft.app.resources.StringDesc
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.components.RaftToastIntent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * botiverse/slock #15669 / #15714 / #16556 (`#proj-runtime:#101`) on a real composition:
 * - active `applied` / `restarted`: single-line "<name>'s settings applied" with no description or action
 * - `saved` / inactive `applied` / `restarted`: single-line "<name>'s settings saved" with no description or action
 * - `when_idle`: "<name>'s settings saved" with current-message wording and Raft Computer update hint
 * - `null`: no toast
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MembersRuntimeConfigApplyUiTest {
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

    @Test
    fun toastTiersMatchWebCopyWithoutTurnWording() =
        runRobolectricComposeUiTest {
            lateinit var copy: MembersRuntimeConfigApplyCopy
            setContent {
                RaftDesignSystemTheme {
                    copy = membersRuntimeConfigApplyCopy()
                }
            }
            waitForIdle()

            // Tier 1: applied / restarted while active -> single-line applied toast
            for (apply in listOf(AgentConfigApply.Applied, AgentConfigApply.Restarted)) {
                val host = RaftToastHostState()
                val notice = requireNotNull(membersRuntimeConfigApplyNotice(apply, agentActive = true, newSession = false))
                showMembersRuntimeConfigApplyNotice(host, notice, "Otter", copy)
                val toast = host.visible.single()
                assertEquals("Otter's settings applied", toast.title)
                assertNull(toast.description)
                assertNull(toast.actionLabel)
                assertEquals(RaftToastIntent.Success, toast.intent)
            }

            // Tier 2: saved (active or inactive) or inactive applied/restarted -> single-line saved toast
            for ((apply, active) in listOf(
                AgentConfigApply.Saved to true,
                AgentConfigApply.Saved to false,
                AgentConfigApply.Applied to false,
                AgentConfigApply.Restarted to false,
            )) {
                val host = RaftToastHostState()
                val notice = requireNotNull(membersRuntimeConfigApplyNotice(apply, agentActive = active, newSession = false))
                showMembersRuntimeConfigApplyNotice(host, notice, "Otter", copy)
                val toast = host.visible.single()
                assertEquals("Otter's settings saved", toast.title)
                assertNull(toast.description)
                assertNull(toast.actionLabel)
                assertEquals(RaftToastIntent.Success, toast.intent)
            }

            // Tier 3: when_idle on older Computer -> saved title + current-message & update hint
            run {
                val host = RaftToastHostState()
                val notice = requireNotNull(membersRuntimeConfigApplyNotice(AgentConfigApply.WhenIdle, agentActive = true, newSession = false))
                showMembersRuntimeConfigApplyNotice(host, notice, "Otter", copy)
                val toast = host.visible.single()
                assertEquals("Otter's settings saved", toast.title)
                assertEquals("Applies after the current message. Update Raft Computer to switch right away.", toast.description)
                assertNull(toast.actionLabel)
            }
            run {
                val host = RaftToastHostState()
                val notice = requireNotNull(membersRuntimeConfigApplyNotice(AgentConfigApply.WhenIdle, agentActive = true, newSession = true))
                showMembersRuntimeConfigApplyNotice(host, notice, "Otter", copy)
                val toast = host.visible.single()
                assertEquals("Otter's settings saved", toast.title)
                assertEquals("Starts a new session after the current message. Update Raft Computer to switch right away.", toast.description)
                assertNull(toast.actionLabel)
            }

            // Undefined configApply -> no toast
            assertNull(membersRuntimeConfigApplyNotice(null, agentActive = true, newSession = false))
        }
}
