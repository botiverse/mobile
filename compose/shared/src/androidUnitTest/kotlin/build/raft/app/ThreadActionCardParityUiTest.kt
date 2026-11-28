package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.InstallMarketplaceAppActionCardType
import build.raft.app.data.PlanUpgradeActionCardType
import build.raft.app.data.SeatsAddActionCardType
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadActionCardSeatOutcome
import build.raft.app.resources.StringDesc
import build.raft.app.thread.LocalThreadActionCardNames
import build.raft.app.thread.LocalThreadActionCardSpecHost
import build.raft.app.thread.LocalThreadViewerServerId
import build.raft.app.thread.ThreadActionCardEntityKind
import build.raft.app.thread.ThreadActionCardNameLookup
import build.raft.app.thread.ThreadActionCardSpecHost
import build.raft.app.thread.ThreadActionCardSpecView
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for Parity #15 action card behavior:
 * - 15b: `seats:add` `seatOutcome` renders under the header for viewer==requester ("You can ...")
 *   and for other viewers ("Mei Lin can ..."), including when `executedByUserName` is absent.
 * - 15c: `plan:upgrade` flips to Done + folded + check line when `currentServerPlan` leaves Free
 *   and reverts when back on Free, suppressing changing buttons and `card.result`.
 * - 15d: done `install_marketplace_app` card with safe `setupUrl` stays unfolded with
 *   `action-card-continue-setup` invoking `openExternalUrl`, while one without `setupUrl` folds.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadActionCardParityUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val originalLocaleType = StringDesc.localeType

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = originalLocaleType
    }

    private val names =
        ThreadActionCardNameLookup { kind, id ->
            if (kind == ThreadActionCardEntityKind.Human && id == "u-mei") "Mei Lin" else null
        }

    private fun simpleSpec(
        buttonLabel: String = "Upgrade to Pro",
        withResultText: String? = null,
    ): String {
        val resultBlock =
            if (withResultText != null) {
                ""","result":{"root":"res","elements":{"res":{"type":"Text","props":{"text":"$withResultText"}}}}"""
            } else {
                ""
            }
        return """
            {"catalog":"action-card.v1","icon":"credit_card",
             "title":{"key":"test.title","text":{"en":"Test action card"}},
             "spec":{"root":"root","elements":{
               "root":{"type":"Stack","props":{"direction":"column"},"children":["actions"]},
               "actions":{"type":"Stack","props":{"direction":"row","role":"actions"},"children":["go"]},
               "go":{"type":"Button","props":{"label":{"key":"test.go","text":{"en":"$buttonLabel"}},"emphasis":"primary","action":{"kind":"execute"}}}
             }}$resultBlock}
        """.trimIndent()
    }

    @Test
    fun seatOutcomeRendersForRequesterAndOtherAndWithoutCommittedBy() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        var viewerId by mutableStateOf("u-mei")
        var executedBy by mutableStateOf<String?>("Ada")
        val spec = ActionCardSpecFixtures.cardFromJson(simpleSpec(buttonLabel = "Add seats"))

        setContent {
            val card =
                ThreadActionCard(
                    type = SeatsAddActionCardType,
                    state = "executed",
                    sourceServerId = "s-1",
                    requesterUserId = "u-mei",
                    executedByUserName = executedBy,
                    seatOutcome = ThreadActionCardSeatOutcome.ReadyAgentCreate("Scout"),
                )
            RaftTheme(appearance = RaftAppearance.Light) {
                CompositionLocalProvider(
                    LocalThreadViewerServerId provides "s-1",
                    LocalThreadActionCardNames provides names,
                    LocalThreadActionCardSpecHost provides ThreadActionCardSpecHost(viewerUserId = viewerId),
                ) {
                    ThreadActionCardSpecView(
                        messageId = "m-seat",
                        card = card,
                        spec = spec,
                        commitEnabled = true,
                        typedPending = false,
                        executedTimeLabel = null,
                        onAction = {},
                    )
                }
            }
        }

        onNodeWithTag("action-card-outcome").assertExists()
        onNodeWithText("You can create Scout now.", substring = true).assertExists()

        viewerId = "u-owner"
        waitForIdle()
        onNodeWithText("Mei Lin can create Scout now.", substring = true).assertExists()

        executedBy = null
        waitForIdle()
        onNodeWithTag("action-card-outcome").assertExists()
        onNodeWithText("Mei Lin can create Scout now.", substring = true).assertExists()
    }

    @Test
    fun planUpgradeCardAutoMarksDoneWhenServerLeavesFreeAndRevertsOnFree() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        var serverPlan by mutableStateOf<String?>("free")
        val spec = ActionCardSpecFixtures.cardFromJson(simpleSpec(buttonLabel = "Upgrade to Pro", withResultText = "Legacy result"))
        val card =
            ThreadActionCard(
                type = PlanUpgradeActionCardType,
                state = "prepared",
                sourceServerId = "s-1",
            )

        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                CompositionLocalProvider(
                    LocalThreadViewerServerId provides "s-1",
                    LocalThreadActionCardNames provides names,
                    LocalThreadActionCardSpecHost provides ThreadActionCardSpecHost(currentServerPlan = serverPlan),
                ) {
                    ThreadActionCardSpecView(
                        messageId = "m-upgrade",
                        card = card,
                        spec = spec,
                        commitEnabled = true,
                        typedPending = false,
                        executedTimeLabel = null,
                        onAction = {},
                    )
                }
            }
        }

        onNodeWithText("Upgrade to Pro").assertExists()

        serverPlan = "pro"
        waitForIdle()
        onNodeWithText("Upgraded to Pro").assertExists()
        onNodeWithText("Upgrade to Pro").assertDoesNotExist()
        onNodeWithText("Legacy result", substring = true).assertDoesNotExist()

        serverPlan = "founder"
        waitForIdle()
        onNodeWithText("No longer limited by the Free plan").assertExists()

        serverPlan = "free"
        waitForIdle()
        onNodeWithText("Upgrade to Pro").assertExists()
    }

    @Test
    fun installMarketplaceAppWithSafeSetupUrlStaysUnfoldedAndOpensExternalUrl() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        val openedUrls = mutableListOf<String>()
        val spec = ActionCardSpecFixtures.cardFromJson(simpleSpec(buttonLabel = "Add Linear", withResultText = "Linear installed"))
        var setupUrl by mutableStateOf<String?>("https://linear.app/raft/setup")

        setContent {
            val card =
                ThreadActionCard(
                    type = InstallMarketplaceAppActionCardType,
                    state = "executed",
                    sourceServerId = "s-1",
                    executedByUserName = "Ada",
                    resultKind = "marketplace-app-installation",
                    resultSetupUrl = setupUrl,
                )
            RaftTheme(appearance = RaftAppearance.Light) {
                CompositionLocalProvider(
                    LocalThreadViewerServerId provides "s-1",
                    LocalThreadActionCardNames provides names,
                    LocalThreadActionCardSpecHost provides
                        ThreadActionCardSpecHost(openExternalUrl = { openedUrls += it }),
                ) {
                    ThreadActionCardSpecView(
                        messageId = "m-install",
                        card = card,
                        spec = spec,
                        commitEnabled = true,
                        typedPending = false,
                        executedTimeLabel = null,
                        onAction = {},
                    )
                }
            }
        }

        onNodeWithTag("action-card-continue-setup").assertExists().performClick()
        assertEquals(listOf("https://linear.app/raft/setup"), openedUrls)

        setupUrl = null
        waitForIdle()
        onNodeWithTag("action-card-continue-setup").assertDoesNotExist()
    }
}
