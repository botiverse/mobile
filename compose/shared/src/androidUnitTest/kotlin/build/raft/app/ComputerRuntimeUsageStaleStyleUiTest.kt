package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import build.raft.app.data.AppLocale
import build.raft.app.data.RuntimeAccountUsageAccount
import build.raft.app.data.RuntimeAccountUsageHealth
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAccountUsageSnapshot
import build.raft.app.data.RuntimeAccountUsageWindow
import build.raft.app.data.RuntimeAccountUsageWindowStatus
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.StringDesc
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Stale runtime usage sheet/card styling (#proj-mobile:#433):
 * - Stale snapshots must not paint `fillMuted` behind `RaftMeter` (whose track would blend into it);
 *   instead the surface stays clean and the stale account snapshot dims to 0.6 opacity while keeping
 *   the "Out of date" warning strip and footer legible.
 * - Inside sheets/dialogs (`inset = 0.dp`), stale content is framed in a rounded hairline container
 *   and applies a consistent 12dp horizontal inset across the warning strip, accounts, meters, and footer.
 * - Fresh snapshots (`inset = 0.dp`) and desktop cards (`inset = 16.dp`) keep their own insets.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xhdpi")
class ComputerRuntimeUsageStaleStyleUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    private val fixedTimeContext =
        RaftTimeContext(
            localOffsetMinutes = 0,
            todayEpochDay = raftEpochDayForDate(2026, 11, 28),
            // 2026-11-28T09:00:00Z
            nowEpochMillis = 1_795_856_400_000L,
        )

    @BeforeTest
    fun setUp() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
    }

    @AfterTest
    fun tearDown() {
        StringDesc.localeType = previousLocale
    }

    @Test
    fun staleUsageInSheetUsesConsistentInsetCleanSurfaceAndVisibleZeroPercentMeterTrack() = runRobolectricComposeUiTest {
        var surfaceColor = Color.Unspecified
        var fillMutedColor = Color.Unspecified
        var warningSoftColor = Color.Unspecified
        setContent {
            CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(fixedTimeContext)) {
                RaftTheme(appearance = RaftAppearance.Light) {
                    surfaceColor = RaftTheme.colors.layerPopover
                    fillMutedColor = RaftTheme.colors.fillMuted
                    warningSoftColor = RaftTheme.colors.warningSoft
                    RaftComputersTheme {
                        Box(Modifier.fillMaxWidth().background(surfaceColor).padding(16.dp)) {
                            ComputerRuntimeUsageContents(
                                state = usageState(stale = true),
                                timeContext = fixedTimeContext,
                                inset = 0.dp,
                                footerAction = {
                                    RaftButton(
                                        text = "Check again",
                                        onClick = {},
                                        variant = RaftButtonVariant.Ghost,
                                        size = RaftButtonSize.Md,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()

        onNodeWithTag(COMPUTER_RUNTIME_USAGE_STALE_BANNER_TAG, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Out of date", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(COMPUTER_RUNTIME_ACCOUNT_PLAN_TAG, useUnmergedTree = true).assertIsDisplayed()
        onNode(hasText("0% used", substring = true), useUnmergedTree = true).assertIsDisplayed()
        onNode(hasText("24% used", substring = true), useUnmergedTree = true).assertIsDisplayed()
        onNode(hasText("61% used", substring = true), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(COMPUTER_RUNTIME_USAGE_UPDATED_TAG, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Check again", useUnmergedTree = true).assertIsDisplayed()

        val expectedInsetPx = with(density) { 12.dp.toPx() }
        val containerBounds = onNodeWithTag(COMPUTER_RUNTIME_USAGE_CONTENTS_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val bannerBoxBounds = onNodeWithTag(COMPUTER_RUNTIME_USAGE_STALE_BANNER_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val bannerTextBounds = onNodeWithText("Out of date", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val accountsBounds = onNodeWithTag(COMPUTER_RUNTIME_USAGE_ACCOUNTS_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val planBounds = onNodeWithTag(COMPUTER_RUNTIME_ACCOUNT_PLAN_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val meter0Bounds = onNode(hasContentDescription("Quota zero"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val meter24Bounds = onNode(hasContentDescription("Quota low"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val meter61Bounds = onNode(hasContentDescription("Quota mid"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val updatedBounds = onNodeWithTag(COMPUTER_RUNTIME_USAGE_UPDATED_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        assertClose(expectedInsetPx, bannerTextBounds.left - containerBounds.left, "stale banner text left inset")
        assertClose(expectedInsetPx, planBounds.left - containerBounds.left, "account plan left inset")
        assertClose(expectedInsetPx, meter0Bounds.left - containerBounds.left, "0% meter left inset")
        assertClose(expectedInsetPx, meter24Bounds.left - containerBounds.left, "24% meter left inset")
        assertClose(expectedInsetPx, meter61Bounds.left - containerBounds.left, "61% meter left inset")
        assertClose(expectedInsetPx, containerBounds.right - meter0Bounds.right, "0% meter right inset")
        assertClose(expectedInsetPx, containerBounds.right - meter24Bounds.right, "24% meter right inset")
        assertClose(expectedInsetPx, containerBounds.right - meter61Bounds.right, "61% meter right inset")
        assertClose(expectedInsetPx, updatedBounds.left - containerBounds.left, "footer updated text left inset")

        val pixels = onRoot().captureToImage().toPixelMap()

        // The horizontal gutter inside the stale accounts block must keep the clean sheet surface,
        // never painting fillMuted behind RaftMeter.
        val gutterX = (accountsBounds.left + with(density) { 6.dp.toPx() }).roundToInt()
        val gutterY = accountsBounds.center.y.roundToInt()
        val gutterPixel = pixels[gutterX, gutterY]
        assertColorClose(surfaceColor, gutterPixel, "stale accounts backdrop should match clean surface")
        assertNotEquals(fillMutedColor, gutterPixel, "stale accounts backdrop must not paint fillMuted")

        // The "Out of date" strip stays crisp at full warningSoft opacity (sampled away from text and rounded corners).
        val bannerSampleX = (bannerBoxBounds.right - with(density) { 16.dp.toPx() }).roundToInt()
        val bannerSampleY = bannerBoxBounds.center.y.roundToInt()
        assertColorClose(warningSoftColor, pixels[bannerSampleX, bannerSampleY], "stale warning strip stays full warningSoft")

        // The framed hairline border is rendered along the stale container edge.
        val borderPixel = pixels[containerBounds.left.roundToInt(), gutterY]
        assertNotEquals(surfaceColor, borderPixel, "framed stale sheet container should draw its hairline border")

        // The 0% meter track (bottom 6dp Canvas of RaftMeter) must remain visibly distinct from the surface.
        val trackX = meter0Bounds.center.x.roundToInt()
        val trackY = (meter0Bounds.bottom - with(density) { 3.dp.toPx() }).roundToInt()
        val trackPixel = pixels[trackX, trackY]
        assertNotEquals(surfaceColor, trackPixel, "0% meter track must be visible against surface")
        assertTrue(trackPixel.red < 0.99f, "expected 0% meter track darker than white surface, got $trackPixel")
    }

    @Test
    fun staleUsageInSheetSurfaceFramesWithTwelveDpInsetAndKeepsCheckAgainVisible() = runRobolectricComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(fixedTimeContext)) {
                RaftTheme(appearance = RaftAppearance.Light) {
                    RaftComputersTheme {
                        ComputerRuntimeUsageSheetSurface(
                            runtimeLabel = "Claude Code",
                            state = usageState(stale = true),
                            timeContext = fixedTimeContext,
                        )
                    }
                }
            }
        }
        waitForIdle()
        val sheetContainer = onNodeWithTag(COMPUTER_RUNTIME_USAGE_CONTENTS_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val sheetPlan = onNodeWithTag(COMPUTER_RUNTIME_ACCOUNT_PLAN_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val sheetUpdated = onNodeWithTag(COMPUTER_RUNTIME_USAGE_UPDATED_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val twelveDpPx = with(density) { 12.dp.toPx() }
        assertClose(twelveDpPx, sheetPlan.left - sheetContainer.left, "sheet surface stale plan inset")
        assertClose(twelveDpPx, sheetUpdated.left - sheetContainer.left, "sheet surface stale footer inset")
        onNodeWithTag(COMPUTER_RUNTIME_USAGE_REFRESH_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun freshSheetKeepsZeroInsetAndStaleCardKeepsSixteenDpInset() = runRobolectricComposeUiTest {
        var stale by mutableStateOf(false)
        var inset by mutableStateOf(0.dp)
        setContent {
            CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(fixedTimeContext)) {
                RaftTheme(appearance = RaftAppearance.Light) {
                    RaftComputersTheme {
                        ComputerRuntimeUsageContents(
                            state = usageState(stale = stale),
                            timeContext = fixedTimeContext,
                            inset = inset,
                        )
                    }
                }
            }
        }
        waitForIdle()
        val freshContainer = onNodeWithTag(COMPUTER_RUNTIME_USAGE_CONTENTS_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val freshPlan = onNodeWithTag(COMPUTER_RUNTIME_ACCOUNT_PLAN_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val freshUpdated = onNodeWithTag(COMPUTER_RUNTIME_USAGE_UPDATED_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertClose(0f, freshPlan.left - freshContainer.left, "fresh sheet plan inset stays 0dp")
        assertClose(0f, freshUpdated.left - freshContainer.left, "fresh sheet footer inset stays 0dp")

        stale = true
        inset = 16.dp
        waitForIdle()
        val cardContainer = onNodeWithTag(COMPUTER_RUNTIME_USAGE_CONTENTS_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val cardBannerText = onNodeWithText("Out of date", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val cardPlan = onNodeWithTag(COMPUTER_RUNTIME_ACCOUNT_PLAN_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val cardUpdated = onNodeWithTag(COMPUTER_RUNTIME_USAGE_UPDATED_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val sixteenDpPx = with(density) { 16.dp.toPx() }
        assertClose(sixteenDpPx, cardBannerText.left - cardContainer.left, "stale card banner inset")
        assertClose(sixteenDpPx, cardPlan.left - cardContainer.left, "stale card plan inset")
        assertClose(sixteenDpPx, cardUpdated.left - cardContainer.left, "stale card footer inset")
    }

    private fun usageState(stale: Boolean): ComputerRuntimeUsageSheetState {
        val account =
            RuntimeAccountUsageAccount(
                accountKey = "acct-1",
                planLabel = "Max",
                identityLabel = "ar***@example.com",
                health = RuntimeAccountUsageHealth.Ok,
                windows =
                    listOf(
                        RuntimeAccountUsageWindow("quota_zero", "Quota zero", RuntimeAccountUsageWindowStatus.Ok, 0.0, null),
                        RuntimeAccountUsageWindow("quota_low", "Quota low", RuntimeAccountUsageWindowStatus.Ok, 0.24, null),
                        RuntimeAccountUsageWindow("quota_mid", "Quota mid", RuntimeAccountUsageWindowStatus.Ok, 0.61, null),
                    ),
            )
        val snapshot =
            RuntimeAccountUsageSnapshot(
                provider = "claude",
                collectedAt = "2026-11-28T08:30:00.000Z",
                accounts = listOf(account),
            )
        return ComputerRuntimeUsageSheetState(
            load = RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Available(stale = stale, snapshot = snapshot)),
            loading = false,
            checkDue = true,
        )
    }

    private fun assertClose(expected: Float, actual: Float, label: String, tolerancePx: Float = 1.5f) {
        assertTrue(
            abs(expected - actual) <= tolerancePx,
            "$label: expected ${expected}px (±${tolerancePx}px), got ${actual}px",
        )
    }

    private fun assertColorClose(expected: Color, actual: Color, label: String, tolerance: Float = 0.02f) {
        assertTrue(
            abs(expected.red - actual.red) <= tolerance &&
                abs(expected.green - actual.green) <= tolerance &&
                abs(expected.blue - actual.blue) <= tolerance,
            "$label: expected $expected, got $actual",
        )
    }
}
