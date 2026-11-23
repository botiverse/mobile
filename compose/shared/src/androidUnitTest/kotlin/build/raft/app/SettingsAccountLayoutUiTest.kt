package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.DataResult
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Account web parity (#wg-mobile-sync:#252):
 * - Change password (or Set a password) sits directly below the profile card and above Connected
 *   accounts, matching `AccountSection.tsx`.
 * - Expanding Change password shows the device-login notice banner.
 * - Product analytics (`Share usage data`) renders with its section header above Session (`Sign out`).
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1400dp")
class SettingsAccountLayoutUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    private val account =
        RaftSettingsAccountPayload(
            instanceId = "settings-account-layout",
            profile =
                RaftSettingsAccountProfile(
                    displayName = "Artin",
                    handle = "artin",
                    email = "artin@raft.build",
                    avatarLabel = "AR",
                    verified = true,
                ),
            connectedAccounts =
                listOf(
                    RaftSettingsConnectedAccount("password", "Email password", "artin@raft.build", "connected", canConnect = false),
                    RaftSettingsConnectedAccount("github", "GitHub", "artin@github.example", "connected", canConnect = false),
                    RaftSettingsConnectedAccount("google", "Google", "Not connected", "notConnected", canConnect = true),
                ),
            usageDataVisible = true,
            usageDataSharing = true,
        )

    @BeforeTest
    fun setUp() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        build.raft.app.image.raftInstallImageLoader()
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    @Test
    fun changePasswordSitsAboveConnectedAccountsAndExpandsWithDeviceLoginNotice() =
        runRobolectricComposeUiTest {
            setContent {
                Host {
                    SettingsAccountContent(
                        payload = account,
                        applyTopSafeArea = false,
                        onEvent = { _, _ -> },
                    )
                }
            }
            waitForIdle()

            val passwordBounds = onNodeWithTag(SettingsAccountChangePasswordToggleTag).getUnclippedBoundsInRoot()
            val connectedBounds = onNodeWithTag(SettingsAccountConnectedAccountsSectionTag).getUnclippedBoundsInRoot()
            assertTrue(
                passwordBounds.bottom <= connectedBounds.top,
                "Change password must sit above Connected accounts: password=$passwordBounds connected=$connectedBounds",
            )

            onNodeWithTag(SettingsAccountChangePasswordToggleTag).performClick()
            waitForIdle()
            onNodeWithText(
                "Changing your password signs you out everywhere. Computers and agent credentials set up through a device login (Raft Computer, the raft CLI) stop working too, so you'll need to log in on them again."
            ).assertExists()
        }

    @Test
    fun setAPasswordSitsAboveConnectedAccountsWhenPasswordUnconfigured() =
        runRobolectricComposeUiTest {
            setContent {
                Host {
                    val scope = rememberCoroutineScope()
                    val store = remember { SettingsAccountPasswordSetupStore(sendEmail = { DataResult.Content(Unit) }, scope = scope) }
                    val state by store.state.collectAsState()
                    SettingsAccountContent(
                        payload = account,
                        applyTopSafeArea = false,
                        passwordSetup = SettingsAccountPasswordSetupUi(passwordConfigured = false, state = state, dispatch = store::dispatch),
                        onEvent = { _, _ -> },
                    )
                }
            }
            waitForIdle()

            val setPasswordBounds = onNodeWithTag(SettingsAccountSetPasswordTag).getUnclippedBoundsInRoot()
            val connectedBounds = onNodeWithTag(SettingsAccountConnectedAccountsSectionTag).getUnclippedBoundsInRoot()
            assertTrue(
                setPasswordBounds.bottom <= connectedBounds.top,
                "Set a password must sit above Connected accounts: setPassword=$setPasswordBounds connected=$connectedBounds",
            )
        }

    @Test
    fun productAnalyticsSitsAboveSessionWhenVisible() =
        runRobolectricComposeUiTest {
            setContent {
                Host {
                    SettingsAccountContent(
                        payload = account,
                        applyTopSafeArea = false,
                        onEvent = { _, _ -> },
                    )
                }
            }
            waitForIdle()

            onNodeWithText("Product analytics").assertExists()
            val analyticsBounds = onNodeWithTag(SettingsAccountUsageDataSectionTag).getUnclippedBoundsInRoot()
            val sessionBounds = onNodeWithTag(SettingsAccountSessionSectionTag).getUnclippedBoundsInRoot()
            assertTrue(
                analyticsBounds.bottom <= sessionBounds.top,
                "Product analytics must sit above Session: analytics=$analyticsBounds session=$sessionBounds",
            )
        }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftDesignSystemTheme { content() }
        }
    }
}
