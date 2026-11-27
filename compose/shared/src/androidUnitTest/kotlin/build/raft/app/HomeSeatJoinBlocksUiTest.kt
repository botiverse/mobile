package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SeatJoinBlock
import build.raft.app.data.SettingsBillingCurrentPlanSnapshot
import build.raft.app.data.SettingsBillingPrepaidSnapshot
import build.raft.app.data.SettingsBillingSeatUsageSnapshot
import build.raft.app.data.SettingsBillingSnapshot
import build.raft.app.data.SettingsBillingSubscriptionSnapshot
import build.raft.app.resources.StringDesc
import build.raft.app.settings.SettingsBillingViewOnlyContent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI tests for Prepaid Pro iOS view-only billing (Apple 3.1.1 compliant) and Notification Center
 * `seat-join-blocks` cards across owner, non-owner, and iOS view-only modes.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h780dp-xxhdpi")
class HomeSeatJoinBlocksUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun englishLocale() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
    }

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = previousLocale
    }

    private fun sampleBlock() =
        SeatJoinBlock(
            id = "blk-grace",
            userId = "user-grace",
            name = "Grace Hopper",
            inviterUserId = "user-admin",
            inviterName = "Ada",
            createdAt = "2026-11-10T10:00:00.000Z",
        )

    @Test
    fun ownerAndNonOwnerSeatJoinBlockCardsShowExpectedActionsAndDismiss() =
        runRobolectricComposeUiTest {
            var isOwner by mutableStateOf(true)
            var blocks by mutableStateOf(listOf(sampleBlock()))
            val clicked = mutableListOf<String>()
            val dismissed = mutableListOf<String>()

            setContent {
                RaftDesignSystemTheme {
                    val items =
                        blocks.map { block ->
                            seatJoinBlockNotificationItem(
                                block = block,
                                serverName = "Acme",
                                isOwner = isOwner,
                                offersUpgrade = true,
                            )
                        }
                    Column {
                        HomeNotificationBellButton(notifications = items, open = true, onToggle = {}, size = 44.dp)
                        HomeNotificationPopover(
                            notifications = items,
                            onView = { clicked += "${it.id}:${it.actionLabel}" },
                            onDismiss = { item ->
                                dismissed += item.id
                                blocks = blocks.filterNot { "seat-join-blocked:${it.id}" == item.id }
                            },
                        )
                    }
                }
            }

            onNode(hasTestTag("home_notification_row_seat-join-blocked:blk-grace")).assertExists()
            onNode(hasText("Acme · Grace Hopper couldn't join")).assertExists()
            onNode(hasText("Upgrade needed for Grace Hopper to join")).assertExists()
            onNode(hasText("Add seats")).performClick()
            waitForIdle()
            assertEquals(listOf("seat-join-blocked:blk-grace:Add seats"), clicked)

            // Non-owner sees "Ask an owner" instead of "Add seats".
            runOnIdle { isOwner = false }
            waitForIdle()
            onNode(hasText("Add seats")).assertDoesNotExist()
            onNode(hasText("Ask an owner")).performClick()
            waitForIdle()
            assertEquals(
                listOf("seat-join-blocked:blk-grace:Add seats", "seat-join-blocked:blk-grace:Ask an owner"),
                clicked,
            )

            // Dismiss removes the card and shows empty state.
            onNode(hasText("Dismiss")).performClick()
            waitForIdle()
            assertEquals(listOf("seat-join-blocked:blk-grace"), dismissed)
            onNode(hasTestTag("home_notification_row_seat-join-blocked:blk-grace")).assertDoesNotExist()
            onNode(hasText("No notifications right now")).assertExists()
        }

    @Test
    fun iosViewOnlySeatJoinBlockOmitsUpgradeCopyAndActionButtonWhileKeepingDismiss() =
        runRobolectricComposeUiTest {
            var blocks by mutableStateOf(listOf(sampleBlock()))
            val dismissed = mutableListOf<String>()

            setContent {
                RaftDesignSystemTheme {
                    val items =
                        blocks.map { block ->
                            seatJoinBlockNotificationItem(
                                block = block,
                                serverName = "Acme",
                                isOwner = false,
                                offersUpgrade = false,
                            )
                        }
                    HomeNotificationPopover(
                        notifications = items,
                        onView = {},
                        onDismiss = { item ->
                            dismissed += item.id
                            blocks = blocks.filterNot { "seat-join-blocked:${it.id}" == item.id }
                        },
                    )
                }
            }

            onNode(hasText("Acme · Grace Hopper couldn't join")).assertExists()
            onNode(hasText("Grace Hopper couldn't join because this server is out of seats.")).assertExists()
            // Apple App Store Guideline 3.1.1: zero upgrade, add-seats, or ask-an-owner controls/copy.
            onNode(hasText("Upgrade", substring = true, ignoreCase = true)).assertDoesNotExist()
            onNode(hasText("Add seats", substring = true, ignoreCase = true)).assertDoesNotExist()
            onNode(hasText("Ask an owner", substring = true, ignoreCase = true)).assertDoesNotExist()
            onNode(hasText("Alipay", substring = true, ignoreCase = true)).assertDoesNotExist()

            onNode(hasText("Dismiss")).performClick()
            waitForIdle()
            assertEquals(listOf("seat-join-blocked:blk-grace"), dismissed)
            onNode(hasTestTag("home_notification_row_seat-join-blocked:blk-grace")).assertDoesNotExist()
        }

    @Test
    fun prepaidProViewOnlyBillingRendersGraceAndEndingSoonWithoutAlipayOrRenewCopy() =
        runRobolectricComposeUiTest {
            var inGrace by mutableStateOf(true)
            val now = SettingsBillingViewOnlyFixtureNow // 2026-11-02T00:00:00Z

            setContent {
                RaftDesignSystemTheme {
                    val prepaid =
                        if (inGrace) {
                            SettingsBillingPrepaidSnapshot(
                                endsAt = "2026-11-01T00:00:00.000Z",
                                graceEndsAt = "2026-11-04T00:00:00.000Z",
                                inGrace = true,
                                seatQuantity = 5,
                            )
                        } else {
                            SettingsBillingPrepaidSnapshot(
                                endsAt = "2026-11-07T00:00:00.000Z",
                                graceEndsAt = "2026-11-10T00:00:00.000Z",
                                inGrace = false,
                                seatQuantity = 5,
                            )
                        }
                    val snapshot =
                        SettingsBillingSnapshot(
                            currentPlan = SettingsBillingCurrentPlanSnapshot(planName = "", usageRows = emptyList()),
                            plans = emptyList(),
                            serverPlan = "pro",
                            summary =
                                SettingsBillingSubscriptionSnapshot(
                                    effectivePlan = "pro",
                                    source = "prepaid",
                                    stripeConfigured = true,
                                    canManageBilling = true,
                                    prepaid = prepaid,
                                    maxUniversalSeats = 5.0,
                                    usage = SettingsBillingSeatUsageSnapshot(humans = 3, agents = 10, universalSeats = 4.0),
                                ),
                        )
                    SettingsBillingViewOnlyContent(
                        billing = ScreenLoadState.Content(snapshot),
                        sessionPlan = "pro",
                        onRetry = {},
                        nowEpochMillis = now,
                    )
                }
            }

            // In grace: warning banner, Ended badge, seat meter, NO Alipay / Renew / Stripe Billing card.
            onNode(hasText("Prepaid Pro ended on", substring = true)).assertExists()
            onNode(hasText("after that this server moves to Free.", substring = true)).assertExists()
            onNode(hasText("Ended ", substring = true)).assertExists()
            onNode(hasText("5 seats · Until", substring = true)).assertExists()
            onNode(hasText("4 of 5 used")).assertExists()
            onNode(hasText("Alipay", substring = true, ignoreCase = true)).assertDoesNotExist()
            onNode(hasText("Renew", substring = true, ignoreCase = true)).assertDoesNotExist()
            onNode(hasText("Billing")).assertDoesNotExist()

            // Ending soon (<= 7d): info banner, Ends badge, zero Alipay / Renew action.
            runOnIdle { inGrace = false }
            waitForIdle()
            onNode(hasText("Pro ends on", substring = true)).assertExists()
            onNode(hasText("Prepaid Pro doesn't renew on its own.")).assertExists()
            onNode(hasText("Ends ", substring = true)).assertExists()
            onNode(hasText("Alipay", substring = true, ignoreCase = true)).assertDoesNotExist()
            onNode(hasText("Renew", substring = true)).assertDoesNotExist()
        }
}
