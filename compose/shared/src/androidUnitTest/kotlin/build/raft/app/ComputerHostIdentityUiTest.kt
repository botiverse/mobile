package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.data.AppLocale
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerDetailSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.sameMachinePeerNames
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.StringDesc
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI parity tests for `computer_host_identity_v0` (botiverse/slock #10082 / #10304, web
 * `MachineDetailPanel` `machine-detail-same-machine`): the Computer detail header names the other
 * Computers sharing `sameMachineGroup`, omits the line when alone or when the flag leaves the
 * field out, and never renders the raw opaque group label.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class ComputerHostIdentityUiTest {
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

    private val fixedTimeContext =
        RaftTimeContext(
            localOffsetMinutes = 0,
            todayEpochDay = raftEpochDayForDate(2026, 11, 24),
            nowEpochMillis = 1_795_521_600_000L,
            timeFormat = RaftTimeFormat24Hour,
        )

    private fun computer(
        id: String,
        name: String,
        sameMachineGroup: String? = null,
    ) = SettingsComputerSnapshot(
        id = id,
        name = name,
        daemonVersion = "",
        computerVersion = "1.0.60",
        isComputer = true,
        online = true,
        hostname = "shared-host",
        platform = "linux x64",
        sameMachineGroup = sameMachineGroup,
    )

    private fun ComposeUiTest.showDetail(
        target: SettingsComputerSnapshot,
        allComputers: List<SettingsComputerSnapshot> = listOf(target),
    ) {
        val snapshot =
            SettingsComputerDetailSnapshot(
                computer = target,
                workspaces = emptyList(),
                agents = emptyList(),
                sections = emptyList(),
                sameMachinePeerNames = sameMachinePeerNames(target, allComputers),
            )
        val model =
            raftComputerDetailModel(
                ScreenLoadState.Content(snapshot),
                machineId = target.id,
                targetServerId = null,
                currentServerId = "server-1",
            )
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(fixedTimeContext)) {
                    RaftComputersTheme {
                        RaftSettingsComputerDetailContent(
                            model = model,
                            actionState = null,
                            onRetry = {},
                            onIntent = {},
                            onOpenAgent = {},
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun detailHeaderNamesTwoPeerComputersInListOrderAndHidesRawGroupLabel() = runRobolectricComposeUiTest {
        val alpha = computer("a", "Alpha", sameMachineGroup = "machine-1")
        val beta = computer("b", "Beta", sameMachineGroup = "machine-1")
        val gamma = computer("c", "Gamma", sameMachineGroup = "machine-2")
        val delta = computer("d", "Delta", sameMachineGroup = "machine-1")

        showDetail(alpha, listOf(alpha, beta, gamma, delta))

        onNodeWithTag(RAFT_COMPUTER_DETAIL_SAME_MACHINE_TAG).assertIsDisplayed()
        onNodeWithText("Same machine as Beta and Delta").assertIsDisplayed()
        assertEquals(0, onAllNodes(hasText("machine-1", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun detailHeaderFormatsSinglePeerName() = runRobolectricComposeUiTest {
        val alpha = computer("a", "Alpha", sameMachineGroup = "machine-1")
        val beta = computer("b", "Beta", sameMachineGroup = "machine-1")

        showDetail(alpha, listOf(alpha, beta))

        onNodeWithTag(RAFT_COMPUTER_DETAIL_SAME_MACHINE_TAG).assertIsDisplayed()
        onNodeWithText("Same machine as Beta").assertIsDisplayed()
    }

    @Test
    fun detailHeaderFormatsThreePeerConjunctionList() = runRobolectricComposeUiTest {
        val alpha = computer("a", "Alpha", sameMachineGroup = "machine-1")
        val beta = computer("b", "Beta", sameMachineGroup = "machine-1")
        val gamma = computer("c", "Gamma", sameMachineGroup = "machine-1")
        val delta = computer("d", "Delta", sameMachineGroup = "machine-1")

        showDetail(alpha, listOf(alpha, beta, gamma, delta))

        onNodeWithTag(RAFT_COMPUTER_DETAIL_SAME_MACHINE_TAG).assertIsDisplayed()
        onNodeWithText("Same machine as Beta, Gamma, and Delta").assertIsDisplayed()
    }

    @Test
    fun detailHeaderHidesSameMachineNoteWhenFlagOffOrGroupNull() = runRobolectricComposeUiTest {
        val alone = computer("a", "Alpha", sameMachineGroup = null)

        showDetail(alone, listOf(alone, computer("b", "Beta", sameMachineGroup = null)))

        onNodeWithTag(RAFT_COMPUTER_DETAIL_SAME_MACHINE_TAG).assertDoesNotExist()
    }

    @Test
    fun detailHeaderHidesSameMachineNoteWhenNoOtherPeerSharesGroup() = runRobolectricComposeUiTest {
        val soloGroup = computer("a", "Alpha", sameMachineGroup = "machine-1")

        showDetail(soloGroup, listOf(soloGroup, computer("b", "Beta", sameMachineGroup = "machine-2")))

        onNodeWithTag(RAFT_COMPUTER_DETAIL_SAME_MACHINE_TAG).assertDoesNotExist()
        assertEquals(0, onAllNodes(hasText("machine-1", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun detailHeaderRendersLocalizedSameMachineCopyInSimplifiedChinese() = runRobolectricComposeUiTest {
        StringDesc.localeType = AppLocale.SimplifiedChinese.stringDescLocaleType
        val alpha = computer("a", "Alpha", sameMachineGroup = "machine-1")
        val beta = computer("b", "Beta", sameMachineGroup = "machine-1")
        val delta = computer("d", "Delta", sameMachineGroup = "machine-1")

        showDetail(alpha, listOf(alpha, beta, delta))

        onNodeWithTag(RAFT_COMPUTER_DETAIL_SAME_MACHINE_TAG).assertIsDisplayed()
        onNodeWithText("与 Beta和Delta 在同一台机器上").assertIsDisplayed()
    }
}
