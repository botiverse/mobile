package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for #wg-mobile-sync:#178 (botiverse/slock #15653 #15686):
 * - ExternalFreezeNotice role-based action labels and click forwarding
 * - ExternalFrozenBanner manager vs member copy and role-based action labels
 * - RaftUpgradeDialog FreezeUpcoming wording in Upgrade and iOS LimitNotice modes
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalChannelFreezeUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun externalFreezeNoticeShowsRoleBasedActionAndClicksThrough() {
        var clicks = 0
        rule.setContent {
            RaftDesignSystemTheme {
                ThreadExternalFreezeNoticeBanner(
                    deadlineIso = "2026-12-01T14:30:00Z",
                    plan = "free",
                    role = "owner",
                    onOpenUpgrade = { clicks++ },
                )
            }
        }
        rule.onNodeWithText("Upgrade to Pro").assertIsDisplayed().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun externalFreezeNoticeShowsAskOwnerForMemberAndViewBillingForPaidPlan() {
        rule.setContent {
            RaftDesignSystemTheme {
                ThreadExternalFreezeNoticeBanner(
                    deadlineIso = "2026-12-01T14:30:00Z",
                    plan = "free",
                    role = "member",
                    onOpenUpgrade = {},
                )
            }
        }
        rule.onNodeWithText("Ask an owner to upgrade").assertIsDisplayed()
    }

    @Test
    fun externalFrozenBannerShowsManagerCopyForAdminAndMemberCopyForMember() {
        rule.setContent {
            RaftDesignSystemTheme {
                ThreadExternalFrozenBanner(
                    serverName = "Acme",
                    plan = "free",
                    role = "admin",
                    onOpenUpgrade = {},
                )
            }
        }
        rule.onNodeWithText("Acme can't post in external channels right now").assertIsDisplayed()
        rule.onNodeWithText("Free allows 1 external channel. Upgrade, or leave the extra channels, to post again.")
            .assertIsDisplayed()
        rule.onNodeWithText("Ask an owner to upgrade").assertIsDisplayed()
    }

    @Test
    fun externalFrozenBannerShowsViewBillingOnPaidPlanAndMemberBodyForMember() {
        rule.setContent {
            RaftDesignSystemTheme {
                ThreadExternalFrozenBanner(
                    serverName = "Acme",
                    plan = "pro",
                    role = "member",
                    onOpenUpgrade = {},
                )
            }
        }
        rule.onNodeWithText("Free allows 1 external channel. Ask an owner to upgrade.").assertIsDisplayed()
        rule.onNodeWithText("View billing").assertIsDisplayed()
    }

    @Test
    fun upgradeDialogShowsFreezeUpcomingCopyWhenContextPaired() {
        rule.setContent {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    RaftUpgradeDialog(
                        request =
                            RaftUpgradeRequest(
                                gate = RaftUpgradeGate.ExternalChannelFrozen,
                                mode = RaftUpgradeMode.Upgrade,
                                pagerId = "fixture",
                                serverId = "server-1",
                                serverName = "Acme",
                                owner = true,
                                webUrl = "https://raft.build/s/acme/settings/billing?upgrade=external_channel_frozen&upgradeCanceled=1&upgradeFreezeAt=2026-12-01T14%3A30%3A00Z&lang=en",
                                externalContext = RaftUpgradeExternalContext.FreezeUpcoming("2026-12-01T14:30:00Z"),
                            ),
                        onOpenWeb = {},
                        onDismiss = {},
                    )
                }
            }
        }
        rule.onNodeWithText("Keep posting in your external channels").assertIsDisplayed()
        rule.onNodeWithText("Acme can't post in its external channels", substring = true).assertIsDisplayed()
    }
}
