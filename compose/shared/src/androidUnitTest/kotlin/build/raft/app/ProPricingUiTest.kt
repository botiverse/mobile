package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.CreateAgentSeatState
import build.raft.app.data.ProPricing
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SeatsAddActionCardType
import build.raft.app.data.SeatsAddPurpose
import build.raft.app.data.SettingsBillingAppSumoSnapshot
import build.raft.app.data.SettingsBillingCurrentPlanSnapshot
import build.raft.app.data.SettingsBillingFileUploadQuotaSnapshot
import build.raft.app.data.SettingsBillingSeatUsageSnapshot
import build.raft.app.data.SettingsBillingSnapshot
import build.raft.app.data.SettingsBillingSubscriptionSnapshot
import build.raft.app.data.ThreadActionCard
import build.raft.app.resources.Res
import build.raft.app.resources.StringDesc
import build.raft.app.resources.action_card_seats_add_purpose_invite_one
import build.raft.app.resources.action_card_seats_add_purpose_invite_other
import build.raft.app.resources.action_card_seats_add_short_one
import build.raft.app.resources.action_card_seats_add_short_other
import build.raft.app.resources.action_card_seats_add_title_one
import build.raft.app.resources.action_card_seats_add_title_other
import build.raft.app.resources.format
import build.raft.app.resources.resolve
import build.raft.app.resources.upgrade_dialog_seat_note_seat
import build.raft.app.thread.ThreadActionCardSpecSeatRequestSheet
import build.raft.app.thread.ThreadActionCardSpecSeatRequestState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric Compose UI tests covering server-resolved per-person Pro pricing (`person_v2`,
 * botiverse/slock #16345, #16367, #16449, #16453) across Billing, Upgrade Dialog, Create Agent
 * Dialog, and Seat-Request Action Card sheet (plus graceful legacy fallback and Russian plurals).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProPricingUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val originalLocaleType = StringDesc.localeType

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = originalLocaleType
    }

    @Composable
    private fun PageHost(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply {
                    pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftDesignSystemTheme { content() }
        }
    }

    @Test
    fun billingViewOnlyHidesAgentsUnderPersonV2AndShowsThemOnLegacyOmittedPricing() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        val proBase = settingsBillingViewOnlyProFixture.data
        val v2Fixture =
            ScreenLoadState.Content(
                proBase.copy(
                    summary =
                        proBase.summary?.let { s ->
                            s.copy(
                                pricing = ProPricing.PersonV2,
                                usage = s.usage?.copy(humans = 3, agents = 12, universalSeats = 3.0),
                            )
                        }
                )
            )
        setContent {
            PageHost {
                SettingsBillingViewOnlyContent(
                    billing = v2Fixture,
                    sessionPlan = null,
                    onRetry = {},
                    nowEpochMillis = SettingsBillingViewOnlyFixtureNow,
                )
            }
        }
        onNodeWithText("3 people").assertExists()
        onNodeWithText("3 seats").assertExists()
        onNodeWithText("12 agents").assertDoesNotExist()
    }

    @Test
    fun billingViewOnlyFallbackShowsAgentsWhenServerOmitsPricing() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        setContent {
            PageHost {
                SettingsBillingViewOnlyContent(
                    billing = settingsBillingViewOnlyProFixture,
                    sessionPlan = null,
                    onRetry = {},
                    nowEpochMillis = SettingsBillingViewOnlyFixtureNow,
                )
            }
        }
        onNodeWithText("3 people").assertExists()
        onNodeWithText("12 agents").assertExists()
    }

    @Test
    fun billingViewOnlyAppSumoRendersUnlimitedAgentsWithoutNegativeLimitRatio() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        val appSumoFixture =
            ScreenLoadState.Content(
                SettingsBillingSnapshot(
                    currentPlan = SettingsBillingCurrentPlanSnapshot(planName = "", usageRows = emptyList()),
                    plans = emptyList(),
                    serverPlan = "pro",
                    summary =
                        SettingsBillingSubscriptionSnapshot(
                            effectivePlan = "pro",
                            source = "appsumo",
                            appsumo =
                                SettingsBillingAppSumoSnapshot(
                                    tier = 1,
                                    maxHumans = 1,
                                    maxAgents = -1,
                                    maxHostedJointChannels = 2,
                                    hostedJointChannels = 1,
                                ),
                            maxUniversalSeats = -1.0,
                            usage = SettingsBillingSeatUsageSnapshot(humans = 1, agents = 11, universalSeats = 2.1),
                            fileUploadQuota =
                                SettingsBillingFileUploadQuotaSnapshot(
                                    month = null,
                                    limited = true,
                                    limitBytes = 1024L * 1024L * 1024L,
                                    remainingBytes = 512L * 1024L * 1024L,
                                ),
                        ),
                )
            )
        setContent {
            PageHost {
                SettingsBillingViewOnlyContent(
                    billing = appSumoFixture,
                    sessionPlan = null,
                    onRetry = {},
                    nowEpochMillis = SettingsBillingViewOnlyFixtureNow,
                )
            }
        }
        onNodeWithText("AppSumo Lifetime Pro · Tier 1").assertExists()
        onNodeWithText("1 of 1 used").assertExists()
        onNodeWithText("11 used · Unlimited").assertExists()
        onNodeWithText("1 of 2 used").assertExists()
        onNodeWithText("-1", substring = true).assertDoesNotExist()
    }

    @Test
    fun upgradeDialogNeverGuessesPriceWhenUnresolved() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        val request =
            RaftUpgradeRequest(
                gate = RaftUpgradeGate.History30d,
                mode = RaftUpgradeMode.Upgrade,
                pagerId = "p",
                serverId = "s-1",
                serverName = "Acme",
                owner = true,
                webUrl = "https://raft.build/s/acme/settings/billing",
                pricing = null,
            )
        setContent {
            PageHost { RaftUpgradeDialog(request = request, onOpenWeb = {}, onDismiss = {}) }
        }
        onNodeWithText("$10", substring = true).assertDoesNotExist()
        onNodeWithText("$20", substring = true).assertDoesNotExist()
    }

    @Test
    fun upgradeDialogShowsSeatV1AndPersonV2PricesAndHidesPricesInLimitNotice() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        val base =
            RaftUpgradeRequest(
                gate = RaftUpgradeGate.History30d,
                mode = RaftUpgradeMode.Upgrade,
                pagerId = "p",
                serverId = "s-1",
                serverName = "Acme",
                owner = true,
                webUrl = "https://raft.build/s/acme/settings/billing",
                pricing = ProPricing.PersonV2,
            )
        setContent {
            PageHost { RaftUpgradeDialog(request = base, onOpenWeb = {}, onDismiss = {}) }
        }
        onNodeWithText("$20 per person a month, or $17.60 a month billed yearly.").assertExists()
        onNodeWithText("A seat covers 1 person. Agents don't take seats.").assertExists()
    }

    @Test
    fun upgradeDialogShowsSeatV1PricesAndAgentNote() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        val request =
            RaftUpgradeRequest(
                gate = RaftUpgradeGate.History30d,
                mode = RaftUpgradeMode.Upgrade,
                pagerId = "p",
                serverId = "s-1",
                serverName = "Acme",
                owner = true,
                webUrl = "https://raft.build/s/acme/settings/billing",
                pricing = ProPricing.SeatV1,
            )
        setContent {
            PageHost { RaftUpgradeDialog(request = request, onOpenWeb = {}, onDismiss = {}) }
        }
        onNodeWithText("$10 per seat a month, or $8.80 a month billed yearly.").assertExists()
        onNodeWithText("A seat covers 1 person or 10 agents.").assertExists()
    }

    @Test
    fun upgradeDialogAddSeatsUnderPersonV2UsesPerPersonHintAndLimitNoticeShowsNoPrice() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        val addSeats =
            RaftUpgradeRequest(
                gate = RaftUpgradeGate.SeatLimitInvite,
                mode = RaftUpgradeMode.AddSeats,
                pagerId = "p",
                serverId = "s-1",
                serverName = "Acme",
                owner = true,
                webUrl = "https://raft.build/s/acme/settings/billing",
                pricing = ProPricing.PersonV2,
            )
        setContent {
            PageHost { RaftUpgradeDialog(request = addSeats, onOpenWeb = {}, onDismiss = {}) }
        }
        onNodeWithText("All of Acme's seats are taken. Each person uses 1 seat; agents don't take seats.").assertExists()
        onNodeWithText("$20", substring = true).assertDoesNotExist()
    }

    @Test
    fun upgradeDialogLimitNoticeNeverShowsPriceEvenWithPersonV2() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        val limitNotice =
            RaftUpgradeRequest(
                gate = RaftUpgradeGate.History30d,
                mode = RaftUpgradeMode.LimitNotice,
                pagerId = "p",
                serverId = "s-1",
                serverName = "Acme",
                owner = true,
                webUrl = "https://raft.build/s/acme/settings/billing",
                pricing = ProPricing.PersonV2,
            )
        setContent {
            PageHost { RaftUpgradeDialog(request = limitNotice, onOpenWeb = {}, onDismiss = {}) }
        }
        onNodeWithText("$20", substring = true).assertDoesNotExist()
        onNodeWithText("$17.60", substring = true).assertDoesNotExist()
        onNodeWithText("OK").assertExists()
    }

    @Test
    fun createAgentDialogUnderPersonV2ShowsNoSeatLineAndAllowsSubmit() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        setContent {
            PageHost {
                SharedCreateAgentDialog(
                    submitting = false,
                    errorMessage = null,
                    initialName = "scout",
                    external = true,
                    currentAgentCount = 25,
                    maxAgents = -1,
                    limitPlanLabel = "Pro",
                    seatLimit = null,
                    onDismiss = {},
                    onSubmit = {},
                )
            }
        }
        onNodeWithText("each agent takes 0.1 seat", substring = true).assertDoesNotExist()
        onNodeWithText("Seat limit reached", substring = true).assertDoesNotExist()
        onNodeWithText("Create").assertExists().assertIsEnabled()
    }

    @Test
    fun createAgentDialogUnderSeatV1FullBlocksSubmitWithSeatBanner() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        setContent {
            PageHost {
                SharedCreateAgentDialog(
                    submitting = false,
                    errorMessage = null,
                    initialName = "scout",
                    external = true,
                    currentAgentCount = 10,
                    maxAgents = -1,
                    limitPlanLabel = "Pro",
                    seatLimit =
                        CreateAgentSeatState(
                            pricing = ProPricing.SeatV1,
                            seatCapacity = 3,
                            usedTenths = 30L,
                            seatsShort = 1,
                            reached = true,
                        ),
                    onDismiss = {},
                    onSubmit = {},
                )
            }
        }
        onNodeWithText("Seat limit reached (3/3 on Pro plan).", substring = true).assertExists()
        onNodeWithText("Create").assertExists().assertIsNotEnabled()
    }

    @Test
    fun createAgentDialogUnderSeatV1PartialShowsSeatUsageHelper() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        setContent {
            PageHost {
                SharedCreateAgentDialog(
                    submitting = false,
                    errorMessage = null,
                    initialName = "scout",
                    external = true,
                    currentAgentCount = 5,
                    maxAgents = -1,
                    limitPlanLabel = "Pro",
                    seatLimit =
                        CreateAgentSeatState(
                            pricing = ProPricing.SeatV1,
                            seatCapacity = 3,
                            usedTenths = 25L,
                            seatsShort = 0,
                            reached = false,
                        ),
                    onDismiss = {},
                    onSubmit = {},
                )
            }
        }
        onNodeWithText("2.5/3 seats used; each agent takes 0.1 seat.").assertExists()
        onNodeWithText("Create").assertExists().assertIsEnabled()
    }

    @Test
    fun seatRequestSheetRendersPersonV2InviteAndRoutesCommitToWeb() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        var openedWeb = false
        val state =
            ThreadActionCardSpecSeatRequestState(
                messageId = "m-1",
                elementId = "add",
                card =
                    ThreadActionCard(
                        type = SeatsAddActionCardType,
                        state = "prepared",
                        sourceServerId = "srv-v2",
                        seatsAddCount = 2,
                        seatsAddPurpose = SeatsAddPurpose.Invite(listOf("grace@lumen.test", "alan@lumen.test")),
                    ),
                seatsShort = 2,
                pricing = ProPricing.PersonV2,
                needTenths = 20L,
            )
        setContent {
            PageHost {
                ThreadActionCardSpecSeatRequestSheet(
                    state = state,
                    onOpenWeb = { openedWeb = true },
                    onDismiss = {},
                )
            }
        }
        onNodeWithText("Add 2 seats").assertExists()
        onNodeWithText("2 more seats are needed to cover this request.").assertExists()
        onNodeWithText("2 people × 1").assertExists()
        onNodeWithText("Each person takes 1 seat; agents and apps take none.").assertExists()
        onNodeWithText("Add seats on the web").assertExists().performClick()
        assertTrue(openedWeb)
    }

    @Test
    fun seatRequestSheetRendersSeatV1AgentSumRowAndRuleNote() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        val state =
            ThreadActionCardSpecSeatRequestState(
                messageId = "m-2",
                elementId = "add",
                card =
                    ThreadActionCard(
                        type = SeatsAddActionCardType,
                        state = "prepared",
                        sourceServerId = "srv-v1",
                        seatsAddCount = 1,
                        seatsAddPurpose = SeatsAddPurpose.AgentCreate("scout"),
                    ),
                seatsShort = 1,
                pricing = ProPricing.SeatV1,
                needTenths = 1L,
            )
        setContent {
            PageHost {
                ThreadActionCardSpecSeatRequestSheet(
                    state = state,
                    onOpenWeb = {},
                    onDismiss = {},
                )
            }
        }
        onNodeWithText("Add 1 seat").assertExists()
        onNodeWithText("1 more seat is needed to cover this request.").assertExists()
        onNodeWithText("scout × 0.1").assertExists()
        onNodeWithText("Each person takes 1 seat and each agent 0.1; apps take none.").assertExists()
    }

    @Test
    fun russianPluralsResolveOneFewManyForSeatRequestAndUpgradeNote() {
        StringDesc.localeType = StringDesc.LocaleType.Custom("ru")
        assertEquals("Добавить 1 место", Res.string.action_card_seats_add_title_one.format(1).resolve())
        assertEquals("Добавить 2 места", Res.string.action_card_seats_add_title_other.format(2).resolve())
        assertEquals("Добавить 5 мест", Res.string.action_card_seats_add_title_other.format(5).resolve())
        assertEquals("Для этого запроса нужно ещё 1 место.", Res.string.action_card_seats_add_short_one.format(1).resolve())
        assertEquals("Для этого запроса нужно ещё 3 места.", Res.string.action_card_seats_add_short_other.format(3).resolve())
        assertEquals("Для этого запроса нужно ещё 5 мест.", Res.string.action_card_seats_add_short_other.format(5).resolve())
        assertEquals("1 человек × 1", Res.string.action_card_seats_add_purpose_invite_one.format(1).resolve())
        assertEquals("2 человека × 1", Res.string.action_card_seats_add_purpose_invite_other.format(2).resolve())
        assertEquals("5 человек × 1", Res.string.action_card_seats_add_purpose_invite_other.format(5).resolve())
        assertEquals("Одно место — это 1 человек или 1 агент.", Res.string.upgrade_dialog_seat_note_seat.format(1).resolve())
        assertEquals("Одно место — это 1 человек или 3 агента.", Res.string.upgrade_dialog_seat_note_seat.format(3).resolve())
        assertEquals("Одно место — это 1 человек или 10 агентов.", Res.string.upgrade_dialog_seat_note_seat.format(10).resolve())
    }
}
