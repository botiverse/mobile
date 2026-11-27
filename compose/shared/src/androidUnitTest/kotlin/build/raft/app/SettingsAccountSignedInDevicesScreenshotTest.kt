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
import build.raft.app.data.AppLogin
import build.raft.app.data.SecondFactorStepUpDialogState
import build.raft.app.data.SecondFactorStepUpLostDevice
import build.raft.app.data.SecondFactorStepUpMethod
import build.raft.app.data.SecondFactorStepUpRecovery
import build.raft.app.data.SecondFactorStepUpSignOutScope
import build.raft.app.data.SecondFactorStepUpView
import build.raft.app.data.SharedAppLoginsState
import build.raft.app.data.SharedSignedInDevicesState
import build.raft.app.data.SignedInDevice
import build.raft.app.data.SignedInDeviceList
import build.raft.app.data.SignedInDeviceLostReach
import build.raft.app.data.SignedInDeviceRevokeReason
import build.raft.app.data.SignedInDevicesSignOutDialog
import build.raft.app.data.SignedInDevicesSignOutTarget
import build.raft.app.model.RaftTimeContext
import build.raft.app.settings.RaftSettingsAccountPayload
import build.raft.app.settings.RaftSettingsAccountProfile
import build.raft.app.settings.RaftSettingsConnectedAccount
import build.raft.app.settings.SettingsAccountContent
import build.raft.app.settings.SettingsAppLoginsUi
import build.raft.app.settings.SettingsSignedInDevicesOverlays
import build.raft.app.settings.SettingsSignedInDevicesUi
import kotlin.test.Test
import kotlin.time.Instant
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Settings › Account's Signed-in devices and Apps you signed in to with Raft (web
 * SignedInDevicesSection / AppLoginsSection), light and dark, against a fixed clock:
 *  - `settings.account.signed-in-devices{,.dark}`: This device, a web sign-in and an unknown one,
 *    the Computers note, Sign out of all other devices, then two Apps with Revoke;
 *  - `settings.account.signed-in-devices.failed`: the first read failed (Try again);
 *  - `settings.account.sign-out-device.choice{,.dark}`: the question with Sign out / I no longer
 *    have it (1 computer and 2 agent keys), the second chosen;
 *  - `settings.account.sign-out-device.single`: nothing to disconnect, one question;
 *  - `settings.account.sign-out-device.leftovers`: only what signed-out devices approved;
 *  - `settings.account.sign-out-device.reauth`: the password, a wrong one under the field;
 *  - `settings.account.step-up.lost{,.dark}`: "Verify it's you"'s Can't verify? page for a lost
 *    device (the backup code first, the password email, Sign it out now with your password, the
 *    passkey warning and the two-step reset link);
 *  - `settings.account.app-login-revoke`: Revoke's confirm.
 * Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1400dp-xxhdpi")
class SettingsAccountSignedInDevicesScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @org.junit.Before
    fun installImageLoader() {
        build.raft.app.image.raftInstallImageLoader()
    }

    @Test
    fun signedInDevicesLight() = page("settings.account.signed-in-devices", RaftAppAppearance.Light, devicesState)

    @Test
    fun signedInDevicesFailedLight() =
        page("settings.account.signed-in-devices.failed", RaftAppAppearance.Light, SharedSignedInDevicesState(loadFailed = true), SharedAppLoginsState())

    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun signOutChoiceLight() = dialog("settings.account.sign-out-device.choice", RaftAppAppearance.Light, choiceState)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun stepUpLostLight() = dialog("settings.account.step-up.lost", RaftAppAppearance.Light, choiceState.copy(stepUp = lostStepUp))

    private fun page(
        name: String,
        appearance: RaftAppAppearance,
        devices: SharedSignedInDevicesState,
        logins: SharedAppLoginsState = appLoginsState,
    ) = RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
        Page {
            SettingsAccountContent(
                payload = signedInDevicesAccountFixture,
                applyTopSafeArea = false,
                signedInDevices = SettingsSignedInDevicesUi(devices, dispatch = {}, onOpenComputers = {}, timeContext = fixedClock),
                appLogins = SettingsAppLoginsUi(logins, dispatch = {}, timeContext = fixedClock),
                onEvent = { _, _ -> },
            )
        }
    }

    private fun dialog(name: String, appearance: RaftAppAppearance, state: SharedSignedInDevicesState) =
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
            root = { ShadowDialog.getLatestDialog()?.window?.decorView },
        ) {
            Page {
                RaftDesignSystemTheme {
                    SettingsSignedInDevicesOverlays(SettingsSignedInDevicesUi(state, dispatch = {}, onOpenComputers = {}, timeContext = fixedClock))
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

/** 2026-11-18 10:00 UTC, read in UTC: goldens must not follow the machine's clock or zone. */
private val fixedClock =
    RaftTimeContext(localOffsetMinutes = 0, nowEpochMillis = Instant.parse("2026-11-18T10:00:00Z").toEpochMilliseconds())

private val thisDevice =
    SignedInDevice(
        id = "fam-current",
        platformClaim = "android",
        version = "1.27.0",
        createdAt = "2026-11-01T08:00:00.000Z",
        lastActiveAt = "2026-11-18T09:59:00.000Z",
        current = true,
        lostDeviceReach = SignedInDeviceLostReach(),
    )

private val webDevice =
    SignedInDevice(
        id = "fam-web",
        platformClaim = "browser",
        version = null,
        createdAt = "2026-10-02T08:00:00.000Z",
        lastActiveAt = "2026-11-17T09:00:00.000Z",
        current = false,
        lostDeviceReach = SignedInDeviceLostReach(computers = 1, agentKeys = 2),
    )

private val unknownDevice =
    SignedInDevice(
        id = "fam-old",
        platformClaim = null,
        version = null,
        createdAt = "2025-01-02T08:00:00.000Z",
        lastActiveAt = "2025-02-17T09:00:00.000Z",
        current = false,
        lostDeviceReach = SignedInDeviceLostReach(),
    )

private val devicesState =
    SharedSignedInDevicesState(
        list =
            SignedInDeviceList(
                sessions = listOf(thisDevice, webDevice, unknownDevice),
                othersLostDeviceReach = SignedInDeviceLostReach(computers = 1, agentKeys = 2),
            )
    )

private val choiceState =
    devicesState.copy(
        signOut =
            SignedInDevicesSignOutDialog(
                target = SignedInDevicesSignOutTarget.One(webDevice),
                reach = webDevice.lostDeviceReach,
                reason = SignedInDeviceRevokeReason.LostDevice,
            )
    )

private val lostStepUp =
    SecondFactorStepUpDialogState(
        methods = listOf(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode),
        method = SecondFactorStepUpMethod.Totp,
        recovery = SecondFactorStepUpRecovery("artin@raft.build", SecondFactorStepUpLostDevice(SecondFactorStepUpSignOutScope.One, password = true)),
        passkeyListed = true,
        view = SecondFactorStepUpView.Lost,
    )

private val appLogins =
    listOf(
        AppLogin(id = "consent-2", clientId = "rft_app_notes", name = "Notes", logoUrl = null, grantedAt = "2026-11-10T08:00:00.000Z"),
        AppLogin(id = "consent-1", clientId = "rft_app_calendar", name = "Calendar", logoUrl = null, grantedAt = "2025-09-01T08:00:00.000Z"),
    )

private val appLoginsState = SharedAppLoginsState(logins = appLogins)

private val signedInDevicesAccountFixture =
    RaftSettingsAccountPayload(
        instanceId = "settings-account-signed-in-devices",
        profile =
            RaftSettingsAccountProfile(
                displayName = "Artin",
                handle = "artin",
                email = "artin@raft.build",
                avatarLabel = "AR",
                verified = true,
            ),
        connectedAccounts =
            listOf(RaftSettingsConnectedAccount("password", "Email password", "artin@raft.build", "connected", canConnect = false)),
    )
