package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.data.AppLocale
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerDetailSnapshot
import build.raft.app.data.SettingsComputerSizeStatusSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.StringDesc
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Computer detail page Usage section (Disk & Memory only) and Low Disk warning banner parity.
 * Runtime account usage stays in the Info section's Detected runtimes chips below.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class ComputerDetailUsageUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = previousLocale
    }

    private val gb = 1_000_000_000.0
    private val gib = 1024.0 * 1024.0 * 1024.0

    private val fixedTimeContext =
        RaftTimeContext(
            localOffsetMinutes = 0,
            todayEpochDay = raftEpochDayForDate(2026, 11, 28),
            nowEpochMillis = 1_795_843_200_000L,
        )

    private fun computer(
        online: Boolean = true,
        runtimes: List<String> = listOf("claude", "codex"),
        diskStatus: SettingsComputerSizeStatusSnapshot? = SettingsComputerSizeStatusSnapshot(availableBytes = 200 * gb, totalBytes = 500 * gb),
        memoryStatus: SettingsComputerSizeStatusSnapshot? = SettingsComputerSizeStatusSnapshot(availableBytes = 4 * gib, totalBytes = 16 * gib),
    ) = SettingsComputerSnapshot(
        id = "m-1",
        name = "Build Box",
        daemonVersion = "",
        computerVersion = "1.0.57",
        isComputer = true,
        online = online,
        hostname = "build-box",
        platform = "linux x64",
        runtimes = runtimes,
        diskStatus = diskStatus,
        memoryStatus = memoryStatus,
    )

    private fun ComposeUiTest.showDetail(target: SettingsComputerSnapshot) {
        val snapshot =
            SettingsComputerDetailSnapshot(
                computer = target,
                workspaces = emptyList(),
                agents = emptyList(),
                sections = emptyList(),
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
    fun onlineComputerShowsDiskAndMemoryWithInlineDetailsAndNoRuntimeUsageRowsInUsageSection() = runRobolectricComposeUiTest {
        showDetail(computer(runtimes = listOf("claude", "codex", "antigravity")))

        onNodeWithTag(RAFT_COMPUTER_DETAIL_DISK_LOW_BANNER_TAG).assertDoesNotExist()
        onNodeWithTag(RAFT_COMPUTER_DETAIL_USAGE_TAG).assertIsDisplayed()
        onNodeWithText("Usage").assertIsDisplayed()

        onNodeWithTag(RAFT_COMPUTER_DETAIL_DISK_TAG).assertIsDisplayed()
        onNodeWithText("Disk").assertIsDisplayed()
        onNodeWithText("60% used").assertIsDisplayed()
        onNodeWithTag(RAFT_COMPUTER_DETAIL_DISK_DETAIL_TAG)
            .assertIsDisplayed()
            .assertTextEquals("200.0 GB free of 500.0 GB")

        onNodeWithTag(RAFT_COMPUTER_DETAIL_MEMORY_TAG).assertIsDisplayed()
        onNodeWithText("Memory").assertIsDisplayed()
        onNodeWithText("75% used").assertIsDisplayed()
        onNodeWithTag(RAFT_COMPUTER_DETAIL_MEMORY_DETAIL_TAG)
            .assertIsDisplayed()
            .assertTextEquals("4.0 GB free of 16.0 GB")

        val diskBounds = onNodeWithTag(RAFT_COMPUTER_DETAIL_DISK_TAG).fetchSemanticsNode().boundsInRoot
        val detailBounds = onNodeWithTag(RAFT_COMPUTER_DETAIL_DISK_DETAIL_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(detailBounds.left > diskBounds.left, "inline detail starts under the bar column, right of the label")

        // Runtime usage rows are NOT duplicated inside the Usage section (Detected runtimes live in Info below).
        assertEquals(
            0,
            onAllNodes(
                hasAnyAncestor(hasTestTag(RAFT_COMPUTER_DETAIL_USAGE_TAG)) and hasText("Claude", substring = true),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size,
        )
        assertEquals(
            0,
            onAllNodes(
                hasAnyAncestor(hasTestTag(RAFT_COMPUTER_DETAIL_USAGE_TAG)) and hasText("Codex", substring = true),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun lowDiskOnlineComputerShowsWarningBannerAndWarningDiskBar() = runRobolectricComposeUiTest {
        showDetail(
            computer(
                diskStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 10 * gb, totalBytes = 500 * gb),
            ),
        )

        onNodeWithTag(RAFT_COMPUTER_DETAIL_DISK_LOW_BANNER_TAG).assertIsDisplayed()
        onNodeWithText("Low disk space: 10.0 GB free of 500.0 GB").assertIsDisplayed()
        onNodeWithText(
            "Agents on this computer may fail to save work or start. Raft removes its own old migration data automatically; if space stays low, free up space on this computer.",
        ).assertIsDisplayed()

        onNodeWithTag(RAFT_COMPUTER_DETAIL_USAGE_TAG).assertIsDisplayed()
        onNodeWithTag(RAFT_COMPUTER_DETAIL_DISK_TAG).assertIsDisplayed()
        onNodeWithText("98% used").assertIsDisplayed()
        onNodeWithTag("$RAFT_COMPUTER_DETAIL_DISK_TAG-bar-warning").assertExists()
        onNodeWithTag(RAFT_COMPUTER_DETAIL_DISK_DETAIL_TAG).assertTextEquals("10.0 GB free of 500.0 GB")
    }

    @Test
    fun offlineComputerHidesBothUsageSectionAndLowDiskBanner() = runRobolectricComposeUiTest {
        showDetail(
            computer(
                online = false,
                diskStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 10 * gb, totalBytes = 500 * gb),
                memoryStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 4 * gib, totalBytes = 16 * gib),
                runtimes = listOf("claude"),
            ),
        )

        onNodeWithTag(RAFT_COMPUTER_DETAIL_DISK_LOW_BANNER_TAG).assertDoesNotExist()
        onNodeWithTag(RAFT_COMPUTER_DETAIL_USAGE_TAG).assertDoesNotExist()
    }

    @Test
    fun onlineComputerWithoutDiskOrMemoryHidesUsageSectionEvenWhenUsageRuntimesInstalled() = runRobolectricComposeUiTest {
        showDetail(
            computer(
                online = true,
                runtimes = listOf("claude", "codex", "antigravity"),
                diskStatus = null,
                memoryStatus = null,
            ),
        )

        onNodeWithTag(RAFT_COMPUTER_DETAIL_DISK_LOW_BANNER_TAG).assertDoesNotExist()
        onNodeWithTag(RAFT_COMPUTER_DETAIL_USAGE_TAG).assertDoesNotExist()
    }

    @Test
    fun diskOnlyComputerShowsInlineDiskDetailAndDashForMissingMemory() = runRobolectricComposeUiTest {
        showDetail(
            computer(
                online = true,
                diskStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 200 * gb, totalBytes = 500 * gb),
                memoryStatus = null,
            ),
        )

        onNodeWithTag(RAFT_COMPUTER_DETAIL_USAGE_TAG).assertIsDisplayed()
        onNodeWithTag(RAFT_COMPUTER_DETAIL_DISK_DETAIL_TAG).assertTextEquals("200.0 GB free of 500.0 GB")
        onNodeWithTag(RAFT_COMPUTER_DETAIL_MEMORY_TAG).assertIsDisplayed()
        onNodeWithTag(RAFT_COMPUTER_DETAIL_MEMORY_DETAIL_TAG).assertDoesNotExist()
        onNodeWithText("—").assertIsDisplayed()
    }

    @Test
    fun localizedLowDiskBannerAndUsageSectionRenderInKorean() = runRobolectricComposeUiTest {
        StringDesc.localeType = AppLocale.Korean.stringDescLocaleType
        showDetail(
            computer(
                diskStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 10 * gb, totalBytes = 500 * gb),
                memoryStatus = null,
                runtimes = emptyList(),
            ),
        )
        onNodeWithText("디스크 공간 부족: 500.0 GB 중 10.0 GB 남음").assertIsDisplayed()
        onNodeWithText("사용량").assertIsDisplayed()
        onNodeWithTag(RAFT_COMPUTER_DETAIL_DISK_DETAIL_TAG).assertTextEquals("500.0 GB 중 10.0 GB 남음")
    }

    @Test
    fun localizedLowDiskBannerAndUsageSectionRenderInRussian() = runRobolectricComposeUiTest {
        StringDesc.localeType = AppLocale.Russian.stringDescLocaleType
        showDetail(
            computer(
                diskStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 10 * gb, totalBytes = 500 * gb),
                memoryStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 4 * gib, totalBytes = 16 * gib),
                runtimes = emptyList(),
            ),
        )
        onNodeWithText("Мало места на диске: свободно 10.0 GB из 500.0 GB").assertIsDisplayed()
        onNodeWithText("Использование").assertIsDisplayed()
        onNodeWithTag(RAFT_COMPUTER_DETAIL_DISK_DETAIL_TAG).assertTextEquals("Свободно 10.0 GB из 500.0 GB")
    }
}
