package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeLimitResetIdempotencyKey
import build.raft.app.data.RuntimeLimitResetKind
import build.raft.app.data.RuntimeLimitResetKindStatus
import build.raft.app.data.toRuntimeAccountUsageRead
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.components.RaftPreviewCardDefaults
import build.raft.designsystem.components.RaftPreviewCardSurface
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * "Use a limit reset" on the Claude usage card (botiverse/slock #15574, web fixture screenshots
 * `visual-testing/claude-limit-reset/`): the card's one entry (the main action at the 5-hour
 * limit), "This takes a moment" while it reads, "Which reset do you want to use?", the confirm in
 * Claude Code's words, and the card's line after a read on Claude's older program and after a read
 * with nothing to use.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w520dp-h640dp-xxhdpi")
class ComputerRuntimeClaudeLimitResetScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun entryAtTheLimit() =
        capture("settings.computer.runtime_usage.claude_limit_reset.entry") {
            UsageCard(atLimit = true, offer = ComputerRuntimeClaudeLimitResetOffer(canUse = true, primary = true))
        }

    @Test
    @Config(qualifiers = "w390dp-h640dp-xxhdpi")
    fun choose() =
        dialog("settings.computer.runtime_usage.claude_limit_reset.choose") {
            ComputerRuntimeClaudeLimitResetsDialog(
                state =
                    ComputerRuntimeClaudeLimitResetsListState(
                        phase = ComputerRuntimeClaudeLimitResetsPhase.Choose(listOf(weekly, fable)),
                        canUse = true,
                        atFiveHourLimit = false,
                        selected = RuntimeLimitResetKind.Weekly,
                    ),
                onSelect = {},
                onUse = {},
                onDismiss = {},
            )
        }

    @Test
    @Config(qualifiers = "w390dp-h640dp-xxhdpi")
    fun confirm() =
        dialog("settings.computer.runtime_usage.claude_limit_reset.confirm") {
            ComputerRuntimeClaudeLimitResetConfirmDialog(
                state =
                    ComputerRuntimeClaudeLimitResetConfirmState(
                        kind = RuntimeLimitResetKind.Weekly,
                        idempotencyKey = RuntimeLimitResetIdempotencyKey.of("0f8a2b6e-3c1d-4e5f-9a7b-1c2d3e4f5a6b")!!,
                        account = "x*****n@botiverse.dev",
                        atFiveHourLimit = false,
                        status = weekly,
                    ),
                onConfirm = {},
                onDismiss = {},
            )
        }

    private fun capture(name: String, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
            Page(content)
        }

    private fun dialog(name: String, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(
            name,
            RaftAppAppearance.Light,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
            root = { ShadowDialog.getLatestDialog()?.window?.decorView },
        ) {
            Page(content)
        }

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftTheme(appearance = RaftAppearance.Light) {
                CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(claudeResetTimeContext)) {
                    RaftComputersTheme { content() }
                }
            }
        }
    }
}

private val weekly = RuntimeLimitResetKindStatus.Available(RuntimeLimitResetKind.Weekly, resetsLeft = 1, expiresText = "Nov 26, 4:59pm")
private val fable = RuntimeLimitResetKindStatus.Available(RuntimeLimitResetKind.Fable, resetsLeft = 3)

private val claudeResetTimeContext =
    RaftTimeContext(
        localOffsetMinutes = 8 * 60,
        todayEpochDay = raftEpochDayForDate(2026, 11, 19),
        // 2026-11-19T03:10:00Z
        nowEpochMillis = 1_795_057_800_000L,
    )

// slock web tests `claudeSnapshot(atLimit)`: one Claude account and its "Current session" window.
private fun claudeUsage(atLimit: Boolean): String =
    """{"state":"fresh","snapshot":{"protocolVersion":2,"provider":"claude","collectedAt":"2026-11-19T03:00:00.000Z","accounts":[{"accountKey":"acct-1","maskedLabel":"x*****n@botiverse.dev","planLabel":"Max","health":"${if (atLimit) "rate_limited" else "ok"}","windows":[{"id":"session","label":"Current session","status":"${if (atLimit) "limit_reached" else "ok"}","usedRatio":${if (atLimit) "1" else "0.2"},"resetsAt":"2026-11-19T05:00:00.000Z"},{"id":"seven_day","label":"Weekly limit","status":"ok","usedRatio":0.64,"resetsAt":"2026-11-24T09:00:00.000Z"}]}]}}"""

/** The usage card as a pinned preview card shows it, with the Claude entry and the line under the usage. */
@Composable
private fun UsageCard(atLimit: Boolean, offer: ComputerRuntimeClaudeLimitResetOffer, note: ComputerRuntimeClaudeLimitResetText? = null) {
    val read = remember(atLimit) { Json.parseToJsonElement(claudeUsage(atLimit)).toRuntimeAccountUsageRead()!! }
    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(24.dp)) {
        RaftPreviewCardSurface(width = RaftPreviewCardDefaults.WideWidth) {
            ComputerRuntimeUsageCard(
                runtimeLabel = "Claude Code",
                state =
                    ComputerRuntimeUsageSheetState(
                        load = RuntimeAccountUsageLoad.Loaded(read),
                        loading = false,
                        runtimeVersion = "2.1.322 (Claude Code)",
                        claudeLimitReset = offer,
                        claudeLimitResetNote = note,
                    ),
                timeContext = claudeResetTimeContext,
                onRefresh = {},
                onClose = {},
            )
        }
    }
}
