package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import build.raft.app.assistant.AssistantChatSettingUp
import build.raft.app.assistant.AssistantCreateFailure
import build.raft.app.assistant.AssistantCreateForm
import build.raft.app.assistant.AssistantPageActions
import build.raft.app.assistant.AssistantPageScreen
import build.raft.app.assistant.AssistantPageView
import build.raft.app.data.MyAssistantRecord
import build.raft.app.data.ScreenError
import build.raft.designsystem.components.LocalRaftSpinnerStillStep
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone Home's Assistant row and the Assistant page (web botiverse/slock
 * e136d7c9543fb84bf91330370d89e17c8d8fa044: phone Home's Assistant entry, AssistantRoute,
 * CreateAssistantView, AssistantConversation's head), on fixture data:
 * - `assistant.home-row`: Saved, Drafts and Assistant with Ottie's 24dp face, light and dark;
 * - `assistant.page.loading`: the head with Back over the spinner;
 * - `assistant.page.create`: Create your assistant, light and dark; `.capacity`: the limit's
 *   banner with View plans and the trace ID; `.failed`: the generic warning;
 * - `assistant.page.conversation.paused`: the head (face, name, Paused, what it reads, the gear)
 *   over "Setting up your chat…".
 * Goldens are CI's render (`test.yml` with `record_screenshots=true`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class AssistantPageScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val actions = AssistantPageActions(onBack = {})
    private val mine = MyAssistantRecord(id = "a1", name = "Ottie", agentId = "a1", dmChannelId = null, readsDms = true)

    @Test
    fun homeRowLight() = capture("assistant.home-row", RaftAppAppearance.Light) { HomeAssistantRowVisualContent() }

    @Test
    fun loading() = capture("assistant.page.loading", RaftAppAppearance.Light) { AssistantPageScreen(AssistantPageView.Loading, actions) }

    @Test
    fun createLight() = capture("assistant.page.create", RaftAppAppearance.Light) { Create(AssistantCreateForm()) }

    @Test
    fun createDark() = capture("assistant.page.create.dark", RaftAppAppearance.Dark) { Create(AssistantCreateForm()) }

    @Test
    fun createCapacity() =
        capture("assistant.page.create.capacity", RaftAppAppearance.Light) {
            Create(
                AssistantCreateForm(
                    failure =
                        AssistantCreateFailure.Capacity(
                            ScreenError("assistant_capacity_limit", serverMessage = "Seat limit reached (1/1 on Pro plan).", traceId = TRACE_ID)
                        )
                )
            )
        }

    @Test
    fun createFailed() =
        capture("assistant.page.create.failed", RaftAppAppearance.Light) {
            Create(AssistantCreateForm(failure = AssistantCreateFailure.Generic(ScreenError("http_500", traceId = TRACE_ID))))
        }

    @Test
    fun conversationPaused() =
        capture("assistant.page.conversation.paused", RaftAppAppearance.Light) {
            AssistantPageScreen(AssistantPageView.Conversation(mine), actions, paused = true) { AssistantChatSettingUp() }
        }

    @Composable
    private fun Create(form: AssistantCreateForm) = AssistantPageScreen(AssistantPageView.Create(form), actions)

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            // The spinners hold still (the first pixel lit), as reduced motion draws them.
            CompositionLocalProvider(LocalRaftSpinnerStillStep provides 0) { MigrationFixtureHost(content) }
        }
}

private const val TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
