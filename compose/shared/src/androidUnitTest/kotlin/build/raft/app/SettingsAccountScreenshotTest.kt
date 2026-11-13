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
import build.raft.app.data.AccountLoginSecurity
import build.raft.app.data.ScreenError
import build.raft.app.data.SecondFactorStepUpBanner
import build.raft.app.data.SecondFactorStepUpDialogState
import build.raft.app.data.SecondFactorStepUpFieldError
import build.raft.app.data.SecondFactorStepUpMethod
import build.raft.app.data.SharedAccountLoginSecurityState
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
 * a failed request (the warning under the consequences; Request deletion retries).
 * `settings.account.login-security{,.dark,.failed}`: the page with Login & security after the
 * profile (2 passkeys, two-step verification On; a failed first read with its trace ID).
 * `settings.account.step-up{,.dark,.backup-used,.locked,.passkey-only}`: "Verify it's you" for a
 * refused password or sign-in method change (code field with the backup-code link; a used backup
 * code; the lock banner; only a passkey listed). Fixed fixture data. Reports land in
 * `build/reports/home-screenshots/` (uploaded by CI as `shared-home-screenshots`); verify / record
 * as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1100dp-xxhdpi")
class SettingsAccountScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    // The provider icons (`assets://` SVGs) and the avatar's placeholder need Raft's image loader.
    // Without it they drew blank or not depending on whether an earlier test in the same JVM
    // fork had created a page host (which installs it), so the goldens followed fork layout.
    @org.junit.Before
    fun installImageLoader() {
        runCatching { build.raft.app.image.raftInstallImageLoader() }
    }

    @Test
    fun accountLight() = capture("settings.account", RaftAppAppearance.Light)

    @Test
    fun accountDark() = capture("settings.account.dark", RaftAppAppearance.Dark)

    @Test
    fun accountLoginSecurityLight() = capture("settings.account.login-security", RaftAppAppearance.Light, loginSecurityOn)

    @Test
    fun accountLoginSecurityDark() = capture("settings.account.login-security.dark", RaftAppAppearance.Dark, loginSecurityOn)

    @Test
    fun accountLoginSecurityFailedLight() =
        capture(
            "settings.account.login-security.failed",
            RaftAppAppearance.Light,
            SharedAccountLoginSecurityState(
                error = ScreenError(code = "second_factor_status_http_500", traceId = "0af7651916cd43dd8448eb211c80319c")
            ),
        )

    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun stepUpDialogLight() = stepUpDialog("settings.account.step-up", RaftAppAppearance.Light, stepUpCode)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun stepUpDialogDark() = stepUpDialog("settings.account.step-up.dark", RaftAppAppearance.Dark, stepUpCode)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun stepUpDialogBackupCodeUsedLight() =
        stepUpDialog(
            "settings.account.step-up.backup-used",
            RaftAppAppearance.Light,
            stepUpCode.copy(
                method = SecondFactorStepUpMethod.BackupCode,
                input = "ABCD-EFGH",
                fieldError = SecondFactorStepUpFieldError.BackupCodeUsed,
            ),
        )

    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun stepUpDialogLockedLight() =
        stepUpDialog(
            "settings.account.step-up.locked",
            RaftAppAppearance.Light,
            stepUpCode.copy(input = "123456", banner = SecondFactorStepUpBanner.Locked(lockedUntilEpochMillis = 0L)),
        )

    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun stepUpDialogPasskeyOnlyLight() =
        stepUpDialog(
            "settings.account.step-up.passkey-only",
            RaftAppAppearance.Light,
            SecondFactorStepUpDialogState(methods = emptyList(), method = null),
        )

    private fun stepUpDialog(name: String, appearance: RaftAppAppearance, state: SecondFactorStepUpDialogState) =
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
            root = { ShadowDialog.getLatestDialog()?.window?.decorView },
        ) {
            Page {
                RaftDesignSystemTheme {
                    // A fixed clock: goldens must not follow the machine's time zone.
                    SecondFactorStepUpDialog(state = state, onIntent = {}, formatClock = { "23:40" })
                }
            }
        }

    private fun capture(name: String, appearance: RaftAppAppearance, loginSecurity: SharedAccountLoginSecurityState? = null) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            Page {
                SettingsAccountContent(
                    payload = accountFixture,
                    applyTopSafeArea = false,
                    accountDeletion = SettingsAccountDeletionUi {},
                    loginSecurity = loginSecurity?.let { SettingsAccountLoginSecurityUi(it, onOpen = {}, onRetry = {}) },
                    onEvent = { _, _ -> },
                )
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

private val loginSecurityOn =
    SharedAccountLoginSecurityState(security = AccountLoginSecurity(twoStepEnabled = true, passkeyCount = 2))

private val stepUpCode =
    SecondFactorStepUpDialogState(
        methods = listOf(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode),
        method = SecondFactorStepUpMethod.Totp,
    )

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
