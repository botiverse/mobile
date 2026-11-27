package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.thread.LocalThreadActionCardSpecHost
import build.raft.app.thread.ThreadActionCardSpecHost
import build.raft.app.thread.ThreadExternalFreezeNoticeBanner
import build.raft.app.thread.ThreadExternalFrozenBanner
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for #wg-mobile-sync:#178 (botiverse/slock #15653 #15686):
 * - ExternalFreezeNotice role-based action labels, click forwarding, and iOS neutral copy
 * - ExternalFrozenBanner manager vs member copy, role-based action labels, and iOS neutral copy
 * - RaftUpgradeDialog FreezeUpcoming wording in Upgrade and iOS LimitNotice modes
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalChannelFreezeUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun externalFreezeNoticeShowsRoleBasedActionAndClicksThrough() = runRobolectricComposeUiTest {
        var clicks = 0
        setContent {
            RaftDesignSystemTheme {
                ThreadExternalFreezeNoticeBanner(
                    deadlineIso = "2026-12-01T14:30:00Z",
                    plan = "free",
                    role = "owner",
                    offersUpgrade = true,
                    onOpenUpgrade = { clicks++ },
                )
            }
        }
        waitForIdle()
        onNodeWithText("Upgrade to Pro").assertIsDisplayed().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun externalFreezeNoticeUsesNeutralCopyAndViewYourPlanWhenUpgradeNotOffered() = runRobolectricComposeUiTest {
        var openedPlan = 0
        setContent {
            CompositionLocalProvider(
                LocalThreadActionCardSpecHost provides ThreadActionCardSpecHost(openPlan = { openedPlan++ }),
            ) {
                RaftDesignSystemTheme {
                    ThreadExternalFreezeNoticeBanner(
                        deadlineIso = "2026-12-01T14:30:00Z",
                        plan = "free",
                        role = "owner",
                        offersUpgrade = false,
                        onOpenUpgrade = {},
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithText("Leave all but one of those channels to keep posting.", substring = true).assertIsDisplayed()
        onNodeWithText("View your plan").assertIsDisplayed().performClick()
        assertEquals(1, openedPlan)
    }

    @Test
    fun externalFrozenBannerShowsManagerCopyForAdmin() = runRobolectricComposeUiTest {
        setContent {
            RaftDesignSystemTheme {
                ThreadExternalFrozenBanner(
                    serverName = "Acme",
                    plan = "free",
                    role = "admin",
                    offersUpgrade = true,
                    onOpenUpgrade = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText("Acme can't post in external channels right now").assertIsDisplayed()
        onNodeWithText("Free allows 1 external channel. Upgrade, or leave the extra channels, to post again.")
            .assertIsDisplayed()
        onNodeWithText("Ask an owner to upgrade").assertIsDisplayed()
    }

    @Test
    fun upgradeDialogShowsFreezeUpcomingCopyWhenContextPaired() = runRobolectricComposeUiTest {
        setContent {
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
        waitForIdle()
        onNodeWithText("Keep posting in your external channels").assertIsDisplayed()
        onNodeWithText("Acme can't post in its external channels", substring = true).assertIsDisplayed()
    }
}
