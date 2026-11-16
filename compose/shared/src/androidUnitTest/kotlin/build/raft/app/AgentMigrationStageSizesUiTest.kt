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
 * The status line's sizes on a real composition, per stage, in the web's words
 * (botiverse/slock staging 6935917a0f `agent.detail.migrationPackingProgress` /
 * `agent.detail.migrationDownloading`): packing shows the files and size packed so far, the
 * download shows the compressed bundle's share; other stages keep their plain line.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1500dp")
class AgentMigrationStageSizesUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun remember() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    private fun ComposeUiTest.section(notice: AgentMigrationNotice) {
        setContent {
            MigrationFixtureHost {
                Column {
                    AgentMigrationSection(
                        AgentMigrationState(agentId = MemberId(AgentMigrationDrainRetryFixtures.AGENT), loaded = true, notice = notice),
                        AgentMigrationDrainRetryFixtures.inputs(),
                    )
                }
            }
        }
    }

    @Test
    fun packingShowsTheFilesAndSizePackedSoFar() =
        runRobolectricComposeUiTest {
            section(AgentMigrationDrainRetryFixtures.packing)
            onNodeWithText("Packing the workspace on Studio Mac: 48,210 files, 2.5 GiB so far.").assertExists()
        }

    @Test
    fun packingWithNothingReportedKeepsThePlainLine() =
        runRobolectricComposeUiTest {
            section(AgentMigrationDrainRetryFixtures.packing.copy(sourceBuildProgress = null))
            onNodeWithText("Packing the workspace on Studio Mac. Large workspaces can take up to 30 minutes.").assertExists()
        }

    @Test
    fun aRetryPackingShowsItsProgressOnTheStatusLine() =
        runRobolectricComposeUiTest {
            section(AgentMigrationDrainRetryFixtures.retryPacking)
            onNodeWithText("Packing the workspace on Studio Mac: 1,203 files, 812 MiB so far.").assertExists()
        }

    @Test
    fun downloadingShowsTheCompressedShare() =
        runRobolectricComposeUiTest {
            section(AgentMigrationDrainRetryFixtures.downloading)
            onNodeWithText("Build box is downloading the workspace: 37% (120 MiB of 320 MiB, compressed).").assertExists()
        }

    @Test
    fun downloadingInSimplifiedChinese() =
        runRobolectricComposeUiTest {
            StringDesc.localeType = AppLocale.SimplifiedChinese.stringDescLocaleType
            section(AgentMigrationDrainRetryFixtures.downloading)
            onNodeWithText("Build box 正在下载工作区：37%（120 MiB / 320 MiB，压缩后）。").assertExists()
        }

    @Test
    fun aDownloadWithAllChunksInSaysUnpacking() =
        runRobolectricComposeUiTest {
            val downloading = AgentMigrationDrainRetryFixtures.downloading
            section(downloading.copy(transferProgress = downloading.transferProgress!!.copy(receivedBytes = 320L * 1024 * 1024, receivedChunks = 40)))
            onNodeWithText("Download finished. Unpacking the workspace on Build box.").assertExists()
            onNodeWithText("compressed", substring = true).assertDoesNotExist()
        }

    // The times (botiverse/slock staging 7a631c0ffe `agent.detail.migrationWaitingForAgent` /
    // `agent.detail.migrationPackingElapsed`), against the fixtures' clock (08:00).

    @Test
    fun aWaitingSourceSaysAtMostHowManyMoreMinutes() =
        runRobolectricComposeUiTest {
            section(AgentMigrationDrainRetryFixtures.waiting)
            onNodeWithText(
                "Waiting for the agent to finish its current work on Studio Mac (at most 6 more minutes). It is then stopped and its workspace packed. Stop the agent to continue now.",
            ).assertExists()
        }

    @Test
    fun theLastMinuteIsSingular() =
        runRobolectricComposeUiTest {
            section(AgentMigrationDrainRetryFixtures.waiting.copy(sourceDrainUntil = "2026-11-11T08:00:20.000Z"))
            onNodeWithText("(at most 1 more minute)", substring = true).assertExists()
        }

    @Test
    fun aPassedWaitSaysTheSourceIsStoppingTheAgent() =
        runRobolectricComposeUiTest {
            section(AgentMigrationDrainRetryFixtures.waiting.copy(sourceDrainUntil = "2026-11-11T07:59:00.000Z"))
            onNodeWithText("Stopping the agent on Studio Mac so its workspace can be packed.").assertExists()
        }

    @Test
    fun packingWithNoSizesYetSaysHowLongItHasRun() =
        runRobolectricComposeUiTest {
            section(AgentMigrationDrainRetryFixtures.packingFor)
            onNodeWithText("Packing the workspace on Studio Mac (4 minutes so far). Large workspaces can take up to 30 minutes.").assertExists()
        }

    @Test
    fun packingMinutesInRussianTakeTheirPluralForm() =
        runRobolectricComposeUiTest {
            StringDesc.localeType = AppLocale.Russian.stringDescLocaleType
            section(AgentMigrationDrainRetryFixtures.packingFor)
            onNodeWithText("(уже 4 минуты)", substring = true).assertExists()
        }

    @Test
    fun waitingMinutesInRussianTakeTheirPluralForm() =
        runRobolectricComposeUiTest {
            StringDesc.localeType = AppLocale.Russian.stringDescLocaleType
            section(AgentMigrationDrainRetryFixtures.waiting)
            onNodeWithText("(не более 6 минут)", substring = true).assertExists()
        }
}
