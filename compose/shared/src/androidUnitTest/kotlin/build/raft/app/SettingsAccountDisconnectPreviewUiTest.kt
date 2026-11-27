package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.SecondFactorStepUpDialogState
import build.raft.app.data.SecondFactorStepUpLostDevice
import build.raft.app.data.SecondFactorStepUpMethod
import build.raft.app.data.SecondFactorStepUpRecovery
import build.raft.app.data.SecondFactorStepUpSignOutScope
import build.raft.app.data.SecondFactorStepUpView
import build.raft.app.data.SecurityRevokePreview
import build.raft.app.data.SecurityRevokePreviewComputer
import build.raft.app.data.SharedPasswordDisconnectPreviewStore
import build.raft.app.data.SharedSignedInDevicesState
import build.raft.app.data.SignedInDevice
import build.raft.app.data.SignedInDeviceLostReach
import build.raft.app.data.SignedInDeviceRevokeReason
import build.raft.app.data.SignedInDevicesSignOutDialog
import build.raft.app.data.SignedInDevicesSignOutTarget
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.app.settings.RaftSettingsAccountPayload
import build.raft.app.settings.RaftSettingsAccountProfile
import build.raft.app.settings.SettingsAccountChangePasswordToggleTag
import build.raft.app.settings.SettingsAccountContent
import build.raft.app.settings.SettingsDisconnectPreviewTag
import build.raft.app.settings.SettingsPasswordDisconnectGeneralTag
import build.raft.app.settings.SettingsPasswordDisconnectNamedTag
import build.raft.app.settings.SettingsPasswordDisconnectNoneTag
import build.raft.app.settings.SettingsPasswordDisconnectNotice
import build.raft.app.settings.SettingsPasswordDisconnectPreviewUi
import build.raft.app.settings.SettingsSignedInDevicesOverlays
import build.raft.app.settings.SettingsSignedInDevicesUi
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
 * Robolectric Compose UI tests for `#wg-mobile-sync:#340` (`botiverse/slock` #17061 / #17069 /
 * #17081 / #17083):
 * - Sign-out choice and leftovers dialogs never mention "agent keys": Computers only when counted,
 *   neutral approved-items line otherwise.
 * - Named disconnect preview renders under `LostDevice` and `Leftovers` only when Computers are
 *   present; hidden under `SignOut` and for keys-only previews.
 * - Change-password disconnect notice renders general info (null), signs-out-everywhere info
 *   (empty / keys-only), or warning banner with named Computers + reconnect instructions.
 * - Opening/closing Change password on [SettingsAccountContent] with a live
 *   [SharedPasswordDisconnectPreviewStore] fetches once (no recomposition reset loop), stays
 *   visible, and clears on close.
 * - Step-up Can't verify? sign-out-with-password copy drops "agent keys".
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1200dp")
class SettingsAccountDisconnectPreviewUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

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

    private val previewWithComputers =
        SecurityRevokePreview(
            computers =
                listOf(
                    SecurityRevokePreviewComputer(
                        id = "comp-1",
                        name = "Studio Mac",
                        serverId = "srv-1",
                        serverName = "Botiverse",
                        agentCount = 2,
                    ),
                    SecurityRevokePreviewComputer(
                        id = "comp-2",
                        name = "Quiet Box",
                        serverId = "srv-2",
                        serverName = "Lab",
                        agentCount = 0,
                    ),
                ),
            agentKeys = 3,
        )

    @Test
    fun signOutChoiceShowsComputersWithoutAgentKeysAndPreviewOnlyUnderLostDevice() =
        runRobolectricComposeUiTest {
            setContent {
                RaftDesignSystemTheme {
                    SettingsSignedInDevicesOverlays(
                        SettingsSignedInDevicesUi(
                            state =
                                SharedSignedInDevicesState(
                                    signOut =
                                        SignedInDevicesSignOutDialog(
                                            target = SignedInDevicesSignOutTarget.One(webDevice),
                                            reach = webDevice.lostDeviceReach,
                                            reason = SignedInDeviceRevokeReason.LostDevice,
                                            disconnectPreview = previewWithComputers,
                                        )
                                ),
                            dispatch = {},
                            onOpenComputers = {},
                        )
                    )
                }
            }

            onNodeWithText("What was approved from it keeps working.", substring = true).assertExists()
            onNodeWithText("Also disconnects 1 computer approved from this device.").assertExists()
            onNodeWithText("agent key", substring = true, ignoreCase = true).assertDoesNotExist()
            onNodeWithTag(SettingsDisconnectPreviewTag).assertExists()
            onNodeWithText("This will disconnect:").assertExists()
            onNodeWithText("Studio Mac").assertExists()
            onNodeWithText("Botiverse · 2 agents stop").assertExists()
            onNodeWithText("Quiet Box").assertExists()
            onNodeWithText("Lab · No agents on it").assertExists()
            onNodeWithText("raft-computer setup --machine", substring = true).assertExists()
        }

    @Test
    fun signOutReasonHidesPreviewEvenWhenLoadedAndLeftoversShowsPreview() =
        runRobolectricComposeUiTest {
            var reason by mutableStateOf(SignedInDeviceRevokeReason.SignOut)
            var leftovers by mutableStateOf(false)
            setContent {
                RaftDesignSystemTheme {
                    val dialog =
                        if (leftovers) {
                            SignedInDevicesSignOutDialog(
                                target = SignedInDevicesSignOutTarget.Leftovers,
                                reach = SignedInDeviceLostReach(computers = 2, agentKeys = 1),
                                disconnectPreview = previewWithComputers,
                            )
                        } else {
                            SignedInDevicesSignOutDialog(
                                target = SignedInDevicesSignOutTarget.One(webDevice),
                                reach = webDevice.lostDeviceReach,
                                reason = reason,
                                disconnectPreview = previewWithComputers,
                            )
                        }
                    SettingsSignedInDevicesOverlays(
                        SettingsSignedInDevicesUi(
                            state = SharedSignedInDevicesState(signOut = dialog),
                            dispatch = {},
                            onOpenComputers = {},
                        )
                    )
                }
            }

            // Under SignOut reason, named disconnect preview must stay hidden.
            onNodeWithTag(SettingsDisconnectPreviewTag).assertDoesNotExist()

            // Leftovers always shows the named disconnect preview.
            runOnIdle { leftovers = true }
            waitForIdle()
            onNodeWithTag(SettingsDisconnectPreviewTag).assertExists()
            onNodeWithText("Studio Mac").assertExists()
        }

    @Test
    fun keysOnlySignOutUsesNeutralApprovedLineAndHidesDisconnectPreview() =
        runRobolectricComposeUiTest {
            val keysOnlyDevice = webDevice.copy(lostDeviceReach = SignedInDeviceLostReach(computers = 0, agentKeys = 2))
            setContent {
                RaftDesignSystemTheme {
                    SettingsSignedInDevicesOverlays(
                        SettingsSignedInDevicesUi(
                            state =
                                SharedSignedInDevicesState(
                                    signOut =
                                        SignedInDevicesSignOutDialog(
                                            target = SignedInDevicesSignOutTarget.One(keysOnlyDevice),
                                            reach = keysOnlyDevice.lostDeviceReach,
                                            reason = SignedInDeviceRevokeReason.LostDevice,
                                            disconnectPreview = SecurityRevokePreview(computers = emptyList(), agentKeys = 2),
                                        )
                                ),
                            dispatch = {},
                            onOpenComputers = {},
                        )
                    )
                }
            }

            onNodeWithText("Also disconnects what was approved from this device.").assertExists()
            onNodeWithTag(SettingsDisconnectPreviewTag).assertDoesNotExist()
            onNodeWithText("agent key", substring = true, ignoreCase = true).assertDoesNotExist()
        }

    @Test
    fun passwordDisconnectNoticeHandlesNullEmptyKeysOnlyAndNamedComputers() =
        runRobolectricComposeUiTest {
            setContent {
                RaftDesignSystemTheme {
                    Column {
                        SettingsPasswordDisconnectNotice(preview = null)
                        SettingsPasswordDisconnectNotice(preview = SecurityRevokePreview(computers = emptyList(), agentKeys = 4))
                        SettingsPasswordDisconnectNotice(preview = previewWithComputers)
                    }
                }
            }

            onNodeWithTag(SettingsPasswordDisconnectGeneralTag).assertExists()
            onNodeWithText("Computers and agent credentials set up through a device login", substring = true).assertExists()

            onNodeWithTag(SettingsPasswordDisconnectNoneTag).assertExists()
            onNodeWithTag(SettingsPasswordDisconnectNamedTag).assertExists()
            onNodeWithText("Studio Mac").assertExists()
            onNodeWithText("Botiverse · 2 agents stop").assertExists()
            onNodeWithText("raft-computer setup --machine", substring = true).assertExists()
        }

    @Test
    fun settingsAccountPasswordFormFetchesPreviewOnceStaysStableAndClearsOnClose() =
        runRobolectricComposeUiTest {
            var fetchCount = 0
            setContent {
                val scope = rememberCoroutineScope()
                val store =
                    remember {
                        SharedPasswordDisconnectPreviewStore(
                            fetchPreview = {
                                fetchCount += 1
                                previewWithComputers
                            },
                            scope = scope,
                        )
                    }
                val previewState by store.state.collectAsState()
                var passwordFormOpen by remember { mutableStateOf(false) }
                val key = if (passwordFormOpen) "password_change:0" else null
                LaunchedEffect(store, key) { store.sync(key) }
                SettingsAccountContent(
                    payload =
                        RaftSettingsAccountPayload(
                            instanceId = "disconnect-preview-test",
                            profile =
                                RaftSettingsAccountProfile(
                                    displayName = "Artin",
                                    handle = "artin",
                                    email = "artin@raft.build",
                                    avatarLabel = "AR",
                                    verified = true,
                                ),
                        ),
                    applyTopSafeArea = false,
                    // Intentionally allocate a new UI holder on every recomposition (mirroring SettingsAccountScreen).
                    passwordDisconnectPreview =
                        SettingsPasswordDisconnectPreviewUi(
                            preview = previewState.preview,
                            onFormOpenChanged = { passwordFormOpen = it },
                        ),
                    onEvent = { _, _ -> },
                )
            }

            waitForIdle()
            assertEquals(0, fetchCount, "closed password form must not fetch")

            // Open Change password: fetches once and keeps named preview rendered across recompositions.
            onNodeWithTag(SettingsAccountChangePasswordToggleTag).performClick()
            waitForIdle()
            assertEquals(1, fetchCount, "opening password form must fetch exactly once")
            onNodeWithTag(SettingsPasswordDisconnectNamedTag).assertExists()
            onNodeWithText("Studio Mac").assertExists()

            // Close Change password: preview is cleared and reopening fetches fresh.
            onNodeWithTag(SettingsAccountChangePasswordToggleTag).performClick()
            waitForIdle()
            onNodeWithTag(SettingsPasswordDisconnectNamedTag).assertDoesNotExist()

            onNodeWithTag(SettingsAccountChangePasswordToggleTag).performClick()
            waitForIdle()
            assertEquals(2, fetchCount, "reopening password form must fetch fresh")
            onNodeWithTag(SettingsPasswordDisconnectNamedTag).assertExists()
        }

    @Test
    fun stepUpCantVerifySignOutCopyOmitsAgentKeys() =
        runRobolectricComposeUiTest {
            setContent {
                RaftDesignSystemTheme {
                    SecondFactorStepUpDialog(
                        state =
                            SecondFactorStepUpDialogState(
                                methods = listOf(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode),
                                method = SecondFactorStepUpMethod.Totp,
                                recovery =
                                    SecondFactorStepUpRecovery(
                                        email = "artin@raft.build",
                                        lostDevice = SecondFactorStepUpLostDevice(SecondFactorStepUpSignOutScope.One, password = true),
                                    ),
                                view = SecondFactorStepUpView.Lost,
                            ),
                        onIntent = {},
                    )
                }
            }

            onNodeWithText("What it approved keeps working until you disconnect it with your second step.", substring = true).assertExists()
            onNodeWithText("agent key", substring = true, ignoreCase = true).assertDoesNotExist()
        }
}
