package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.data.AgentMigrationNotice
import build.raft.app.data.AgentMigrationState
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.MemberId
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The migration section's newer lines on a real composition (botiverse/slock staging 0e8b4fab):
 * what a stopping source waits for and by when (#14230), and the agent's own retry with the sizes
 * it compares (#14275), with the web behaviour tests' expectations.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1500dp")
class AgentMigrationDrainRetryUiTest {
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

    private fun ComposeUiTest.section(notice: AgentMigrationNotice, inputs: AgentMigrationSectionInputs = AgentMigrationDrainRetryFixtures.inputs()) {
        setContent {
            MigrationFixtureHost {
                Column { AgentMigrationSection(AgentMigrationState(agentId = MemberId(AgentMigrationDrainRetryFixtures.AGENT), loaded = true, notice = notice), inputs) }
            }
        }
    }

    @Test
    fun aDrainPastItsTimeOrWaitingForSomethingUnknownSaysItStopsAnyMomentAsOther() =
        runRobolectricComposeUiTest {
            section(AgentMigrationDrainRetryFixtures.draining(waitingFor = "something_new", until = "2026-11-11T07:59:00.000Z"))
            onNodeWithText("Migration Agent is still busy. It stops any moment now, then its workspace is packed.").assertExists()
        }

    @Test
    fun aRetryThatFailedAgainSaysTheNextMoveIsAPersonsAndWhatItHadPacked() =
        runRobolectricComposeUiTest {
            section(AgentMigrationDrainRetryFixtures.retryFailed)
            onNodeWithText("This was Migration Agent's one retry; a person needs to start the next migration.", substring = true).assertExists()
            onNodeWithText("48,210 files").assertExists()
            onNodeWithText("31,002 files, 3 GiB when it stopped").assertExists()
        }
}
