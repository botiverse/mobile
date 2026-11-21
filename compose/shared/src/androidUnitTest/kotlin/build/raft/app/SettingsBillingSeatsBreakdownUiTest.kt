package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsBillingSnapshot
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * iOS Plan & billing's seat meter says what the seats in use are made of, as the web's does
 * (botiverse/slock 16f5f8bc40, PlanSectionContent's SeatsBreakdown): under "n of m used", a label
 * then its seats, one row per kind, and no "People 4 · 4 seats" line.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp")
class SettingsBillingSeatsBreakdownUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val originalLocaleType = StringDesc.localeType

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = originalLocaleType
    }

    // A fact is one node of the merged tree holding its label and its seats (RaftDescriptionList
    // `Horizontal`); the unmerged tree also holds the copies of the labels it measures the column with.

    @Test
    fun theSeatMeterBreaksDownItsSeats() =
        runRobolectricComposeUiTest {
            show(settingsBillingViewOnlyProFixtureUsing(humans = 3, agents = 15))
            onNodeWithText("4.5 of 5 used", useUnmergedTree = true).assertExists()
            onNodeWithText("3 people").assert(hasText("3 seats"))
            onNodeWithText("15 agents").assert(hasText("1.5 seats (1 per 10 agents)"))
            // The older inline facts ("People 3 · 3 seats", "Agents 15 · 1.5 seats") are gone.
            listOf("People", "Agents", "3 · 3 seats", "15 · 1.5 seats").forEach {
                onAllNodesWithText(it, useUnmergedTree = true).assertCountEquals(0)
            }
        }

    @Test
    fun noAgentsRowWithoutAgents() =
        runRobolectricComposeUiTest {
            show(settingsBillingViewOnlyProFixtureUsing(humans = 4, agents = 0))
            onNodeWithText("4 people").assert(hasText("4 seats"))
            onAllNodes(hasText("agent", substring = true, ignoreCase = true), useUnmergedTree = true).assertCountEquals(0)
        }

    private fun ComposeUiTest.show(billing: ScreenLoadState<SettingsBillingSnapshot>) {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    SettingsBillingViewOnlyContent(
                        billing = billing,
                        sessionPlan = null,
                        onRetry = {},
                        nowEpochMillis = SettingsBillingViewOnlyFixtureNow,
                    )
                }
            }
        }
        waitForIdle()
    }
}
