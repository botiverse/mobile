package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Settings › Account (web SettingsPanel account section), light and dark: the profile form with
 * the verified email, and Connected accounts with one provider linked (GitHub, "Connected as …",
 * Disconnect) and one not (Google, "Not connected", Connect), then Sign out and Delete account.
 * `settings.account.delete-dialog{,.failed}`: Delete account's confirm dialog, and the same after
 * a failed request (the warning under the consequences; Request deletion retries). Fixed fixture data. Reports land in
 * `build/reports/home-screenshots/` (uploaded by CI as `shared-home-screenshots`); verify / record
 * as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1100dp-xxhdpi")
class SettingsAccountScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun accountLight() = capture("settings.account", RaftAppAppearance.Light)

    @Test
    fun accountDark() = capture("settings.account.dark", RaftAppAppearance.Dark)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun deleteDialogLight() = deleteDialog("settings.account.delete-dialog", RaftAppAppearance.Light, failed = false)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun deleteDialogDark() = deleteDialog("settings.account.delete-dialog.dark", RaftAppAppearance.Dark, failed = false)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun deleteDialogFailedLight() = deleteDialog("settings.account.delete-dialog.failed", RaftAppAppearance.Light, failed = true)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            Page {
                SettingsAccountContent(
                    payload = accountFixture,
                    applyTopSafeArea = false,
                    accountDeletion = SettingsAccountDeletionUi(RaftAccountDeletionRequestState.Idle, {}, {}, {}),
                    onEvent = { _, _ -> },
                )
            }
        }

    private fun deleteDialog(name: String, appearance: RaftAppAppearance, failed: Boolean) =
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
            root = { ShadowDialog.getLatestDialog()?.window?.decorView },
        ) {
            Page {
                RaftDesignSystemTheme {
                    SettingsDeleteAccountDialog(busy = false, failed = failed, onConfirm = {}, onDismiss = {})
                }
            }
        }

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { content() }
    }
}

private val accountFixture =
    RaftSettingsAccountPayload(
        instanceId = "settings-account-screenshot",
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
                RaftSettingsConnectedAccount("github", "GitHub", "artin@raft.build", "connected", canConnect = false),
                RaftSettingsConnectedAccount("google", "Google", "Not connected", "notConnected", canConnect = true),
            ),
    )
