package build.raft.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import build.raft.app.RAFT_LINK_SLOT
import build.raft.app.RaftColor
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.SecondFactorStepUpDialog
import build.raft.app.currentRaftEffectiveDateTimeContext
import build.raft.app.data.NetworkSignedInDevicesDataSource
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.SharedSignedInDevicesIntent
import build.raft.app.data.SharedSignedInDevicesState
import build.raft.app.data.SharedSignedInDevicesStore
import build.raft.app.data.SecondFactorStepUpWebTarget
import build.raft.app.data.SignedInDevice
import build.raft.app.data.SignedInDeviceLostReach
import build.raft.app.data.SignedInDevicePlatform
import build.raft.app.data.SignedInDeviceRevokeReason
import build.raft.app.data.SignedInDevicesNotice
import build.raft.app.data.SignedInDevicesReauthDialog
import build.raft.app.data.SignedInDevicesSignOutDialog
import build.raft.app.data.SignedInDevicesSignOutTarget
import build.raft.app.iconName
import build.raft.app.members.iconName
import build.raft.app.settings.icon
import build.raft.app.settings.iconName
import build.raft.app.model.RaftTimeContext
import build.raft.app.raftArgbHex
import build.raft.app.raftRelativeTimestampLabel
import build.raft.app.raftSentenceWithLink
import build.raft.app.resources.*
import build.raft.app.thread.dispatch
import build.raft.app.thread.icon
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftRadioGroup
import build.raft.designsystem.components.RaftRadioRow
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftToastIntent
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/*
 * Settings › Account › Signed-in devices (web `features/settings/account/SignedInDevicesSection.tsx`,
 * `SignedInDeviceRow.tsx`, `SignOutDeviceDialog.tsx`): the person's own sign-ins on the web, the
 * desktop app and the phone app, This device first as the server lists it, Sign out for each other
 * one or for all of them. Computers and the raft CLI sign in as devices and are the Computers page's
 * (the note under the list says so). The proof the server asks for (the password, "Verify it's you")
 * is SharedSignedInDevicesStore's.
 */

internal const val SettingsSignedInDevicesSectionTag = "signed-in-devices-section"
internal const val SettingsSignedInDevicesSignOutOthersTag = "signed-in-devices-sign-out-others"
internal const val SettingsSignOutDeviceDialogTag = "sign-out-device-dialog"
internal const val SettingsSignOutDeviceConfirmTag = "sign-out-device-confirm"
internal const val SettingsSignedInDevicesReauthDialogTag = "signed-in-devices-reauth-dialog"

internal fun settingsSignedInDeviceRowTag(id: String) = "signed-in-device-$id"

internal fun settingsSignedInDeviceSignOutTag(id: String) = "signed-in-device-sign-out-$id"

/**
 * The section's state, where its intents go, and the Computers page the note links to.
 * [timeContext]: the clock its dates read against (null: the app's live one; screenshots fix it).
 */
internal class SettingsSignedInDevicesUi(
    val state: SharedSignedInDevicesState,
    val dispatch: (SharedSignedInDevicesIntent) -> Unit,
    val onOpenComputers: () -> Unit,
    val timeContext: RaftTimeContext? = null,
)

/** The page's store: read when the page shows and again on every return to the foreground. */
@Composable
internal fun rememberSettingsSignedInDevicesStore(
    networkClient: RaftNetworkClient,
    /** "Verify it's you"'s Open on the web ([build.raft.app.data.secondFactorStepUpWebUrlResolver]). */
    stepUpWebUrl: (suspend (SecondFactorStepUpWebTarget) -> String?)? = null,
): SharedSignedInDevicesStore {
    val scope = rememberCoroutineScope()
    val store =
        remember(networkClient, scope) {
            SharedSignedInDevicesStore(NetworkSignedInDevicesDataSource(networkClient), scope, stepUpWebUrl)
        }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, store) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) store.dispatch(SharedSignedInDevicesIntent.Refresh)
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return store
}

/** Web `signedInDeviceLabel`: "Web", "Desktop", "iOS", ...; "Unknown device" for a sign-in from before platforms were recorded. */
@Composable
internal fun settingsSignedInDeviceLabel(platform: SignedInDevicePlatform): String =
    stringResource(
        when (platform) {
            SignedInDevicePlatform.Web -> Res.string.settings_signed_in_devices_platform_web
            SignedInDevicePlatform.Desktop -> Res.string.settings_signed_in_devices_platform_desktop
            SignedInDevicePlatform.Ios -> Res.string.settings_signed_in_devices_platform_ios
            SignedInDevicePlatform.Android -> Res.string.settings_signed_in_devices_platform_android
            SignedInDevicePlatform.HarmonyOs -> Res.string.settings_signed_in_devices_platform_harmonyos
            SignedInDevicePlatform.Unknown -> Res.string.settings_signed_in_devices_platform_unknown
        }
    )

private fun SignedInDevicePlatform.icon(): RaftLucideIconName =
    when (this) {
        SignedInDevicePlatform.Web -> RaftLucideIconName.Globe
        SignedInDevicePlatform.Desktop -> RaftLucideIconName.Monitor
        SignedInDevicePlatform.Ios,
        SignedInDevicePlatform.Android,
        SignedInDevicePlatform.HarmonyOs -> RaftLucideIconName.Smartphone
        SignedInDevicePlatform.Unknown -> RaftLucideIconName.CircleQuestionMark
    }

/** The list (only while [SharedSignedInDevicesState.visible]); its dialogs are [SettingsSignedInDevicesOverlays]. */
@Composable
internal fun SettingsSignedInDevicesSection(ui: SettingsSignedInDevicesUi) {
    val state = ui.state
    if (!state.visible) return
    val list = state.list
    SettingsFormSection(iconName = RaftLucideIconName.MonitorSmartphone, title = stringResource(Res.string.settings_signed_in_devices_title)) {
        Column(
            Modifier.fillMaxWidth().testTag(SettingsSignedInDevicesSectionTag).settingsSearchAnchor("signed-in-devices"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (list == null) {
                RaftBanner(
                    description = stringResource(Res.string.settings_signed_in_devices_load_failed),
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                    action = {
                        RaftButton(
                            text = stringResource(Res.string.common_retry),
                            onClick = { ui.dispatch(SharedSignedInDevicesIntent.Refresh) },
                            variant = RaftButtonVariant.Default,
                            size = RaftButtonSize.Md,
                            loading = state.loading
                        )
                    }
                )
            } else {
                RaftListRowGroup(Modifier.fillMaxWidth()) {
                    list.sessions.forEachIndexed { index, device ->
                        SettingsSignedInDeviceRow(device, divider = index > 0, timeContext = ui.timeContext) {
                            ui.dispatch(SharedSignedInDevicesIntent.OpenSignOut(device))
                        }
                    }
                }
                val linkText = stringResource(Res.string.settings_signed_in_devices_computers_note_link)
                val sentence = stringResource(Res.string.settings_signed_in_devices_computers_note, RAFT_LINK_SLOT)
                RaftText(
                    raftSentenceWithLink(sentence, linkText, onClick = ui.onOpenComputers),
                    style = RaftTheme.typography.footnote,
                    color = RaftTheme.colors.foregroundMuted
                )
                if (list.canSignOutOthers) {
                    RaftButton(
                        text =
                            stringResource(
                                if (list.othersListed) {
                                    Res.string.settings_signed_in_devices_sign_out_all
                                } else {
                                    Res.string.settings_signed_in_devices_leftovers_button
                                }
                            ),
                        onClick = { ui.dispatch(SharedSignedInDevicesIntent.OpenSignOutOthers) },
                        modifier = Modifier.testTag(SettingsSignedInDevicesSignOutOthersTag),
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Md
                    )
                }
            }
        }
    }
}

/** The section's dialogs (the question, then the password or "Verify it's you" over it) and its toast. */
@Composable
internal fun SettingsSignedInDevicesOverlays(ui: SettingsSignedInDevicesUi) {
    val state = ui.state
    SettingsSignedInDevicesNoticeEffect(state.notice) { ui.dispatch(SharedSignedInDevicesIntent.NoticeShown) }
    state.signOut?.let { dialog -> SettingsSignOutDeviceDialog(dialog, ui.timeContext, ui.dispatch) }
    // Over the question that asked for it.
    state.reauth?.let { dialog -> SettingsSignedInDevicesReauthDialog(dialog, ui.dispatch) }
    state.stepUp?.let { dialog ->
        SecondFactorStepUpDialog(
            state = dialog,
            onIntent = { intent -> ui.dispatch(SharedSignedInDevicesIntent.StepUp(intent)) },
            description = stringResource(Res.string.settings_step_up_lost_device_description),
        )
    }
}

/**
 * One sign-in: what it is (the client's own claim), its version, when it signed in and was last
 * active, and Sign out. The sign-in the app is using says This device and has no action (Session's
 * Sign out is its way out).
 */
@Composable
private fun SettingsSignedInDeviceRow(
    device: SignedInDevice,
    divider: Boolean,
    timeContext: RaftTimeContext?,
    onSignOut: () -> Unit,
) {
    val label = settingsSignedInDeviceLabel(device.platform)
    val facts =
        listOfNotNull(
            device.version?.let { stringResource(Res.string.settings_signed_in_devices_version, it) },
            stringResource(Res.string.settings_signed_in_devices_signed_in, settingsCompactDateLabel(device.createdAt, timeContext)),
            if (device.current) {
                stringResource(Res.string.settings_signed_in_devices_active_now)
            } else {
                stringResource(
                    Res.string.settings_signed_in_devices_last_active,
                    raftRelativeTimestampLabel(device.lastActiveAt, timeContext, clampFuture = true)
                )
            },
        )
    RaftListRow(
        title = label,
        modifier = Modifier.testTag(settingsSignedInDeviceRowTag(device.id)),
        variant = RaftListRowVariant.Plain,
        divider = divider,
        subtitle = facts.joinToString(" · "),
        leading = {
            RaftIconTile(size = RaftIconTileSize.Md) {
                RaftLucideIcon(
                    device.platform.icon(),
                    modifier = Modifier.size(RaftIconTileSize.Md.icon),
                    color = raftArgbHex(RaftColor.ForegroundMuted)
                )
            }
        },
        titleTrailing =
            if (device.current) {
                {
                    RaftBadge(
                        text = stringResource(Res.string.settings_signed_in_devices_this_device),
                        appearance = RaftBadgeAppearance.Soft,
                        variant = RaftBadgeVariant.Default,
                    )
                }
            } else {
                null
            },
        trailing =
            if (device.current) {
                null
            } else {
                {
                    RaftButton(
                        text = stringResource(Res.string.settings_signed_in_devices_sign_out),
                        onClick = onSignOut,
                        modifier = Modifier.testTag(settingsSignedInDeviceSignOutTag(device.id)),
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Md
                    )
                }
            },
    )
}

/** "3 computers", "1 agent key". */
@Composable
private fun computersCount(count: Int): String =
    stringResource(
        if (count == 1) Res.string.settings_signed_in_devices_computers_count_one else Res.string.settings_signed_in_devices_computers_count_other,
        count
    )

@Composable
private fun agentKeysCount(count: Int): String =
    stringResource(
        if (count == 1) Res.string.settings_signed_in_devices_agent_keys_count_one else Res.string.settings_signed_in_devices_agent_keys_count_other,
        count
    )

/** Which of a sentence's three forms (web `lostBodyId`): both, Computers only, agent keys only. */
internal enum class SettingsLostReachForm { Both, Computers, Keys }

internal fun settingsLostReachForm(reach: SignedInDeviceLostReach): SettingsLostReachForm =
    when {
        reach.computers > 0 && reach.agentKeys > 0 -> SettingsLostReachForm.Both
        reach.computers > 0 -> SettingsLostReachForm.Computers
        else -> SettingsLostReachForm.Keys
    }

/** What `lost_device` also disconnects, for one device, the others, or the leftovers. */
@Composable
private fun settingsLostReachText(target: SignedInDevicesSignOutTarget, reach: SignedInDeviceLostReach): String {
    val form = settingsLostReachForm(reach)
    if (form == SettingsLostReachForm.Both) {
        val both =
            when (target) {
                is SignedInDevicesSignOutTarget.One -> Res.string.settings_signed_in_devices_choice_lost_body
                SignedInDevicesSignOutTarget.Others -> Res.string.settings_signed_in_devices_choice_lost_all_body
                SignedInDevicesSignOutTarget.Leftovers -> Res.string.settings_signed_in_devices_leftovers_body
            }
        return stringResource(both, computersCount(reach.computers), agentKeysCount(reach.agentKeys))
    }
    val count = if (form == SettingsLostReachForm.Computers) reach.computers else reach.agentKeys
    val one = count == 1
    val resource =
        when (target) {
            is SignedInDevicesSignOutTarget.One ->
                if (form == SettingsLostReachForm.Computers) {
                    if (one) Res.string.settings_signed_in_devices_choice_lost_body_computers_one else Res.string.settings_signed_in_devices_choice_lost_body_computers_other
                } else {
                    if (one) Res.string.settings_signed_in_devices_choice_lost_body_keys_one else Res.string.settings_signed_in_devices_choice_lost_body_keys_other
                }
            SignedInDevicesSignOutTarget.Others ->
                if (form == SettingsLostReachForm.Computers) {
                    if (one) Res.string.settings_signed_in_devices_choice_lost_all_body_computers_one else Res.string.settings_signed_in_devices_choice_lost_all_body_computers_other
                } else {
                    if (one) Res.string.settings_signed_in_devices_choice_lost_all_body_keys_one else Res.string.settings_signed_in_devices_choice_lost_all_body_keys_other
                }
            SignedInDevicesSignOutTarget.Leftovers ->
                if (form == SettingsLostReachForm.Computers) {
                    if (one) Res.string.settings_signed_in_devices_leftovers_body_computers_one else Res.string.settings_signed_in_devices_leftovers_body_computers_other
                } else {
                    if (one) Res.string.settings_signed_in_devices_leftovers_body_keys_one else Res.string.settings_signed_in_devices_leftovers_body_keys_other
                }
        }
    return stringResource(resource, count)
}

/**
 * Web SignOutDeviceDialog: with something `lost_device` would also disconnect, two answers (Sign out,
 * the default and the least it changes; or I no longer have it, which says how many Computers and
 * agent keys go too); without, one question, Sign out. The leftovers only disconnect, in red.
 */
@Composable
private fun SettingsSignOutDeviceDialog(
    dialog: SignedInDevicesSignOutDialog,
    timeContext: RaftTimeContext?,
    dispatch: (SharedSignedInDevicesIntent) -> Unit,
) {
    val target = dialog.target
    val one = target as? SignedInDevicesSignOutTarget.One
    val title =
        when {
            one != null ->
                stringResource(
                    if (dialog.reach.any) Res.string.settings_signed_in_devices_sign_out_title else Res.string.settings_signed_in_devices_sign_out_title_question,
                    settingsSignedInDeviceLabel(one.device.platform)
                )
            dialog.leftovers -> stringResource(Res.string.settings_signed_in_devices_leftovers_title)
            dialog.reach.any -> stringResource(Res.string.settings_signed_in_devices_sign_out_all_title)
            else -> stringResource(Res.string.settings_signed_in_devices_sign_out_all_title_question)
        }
    val summary =
        one?.let {
            stringResource(
                Res.string.settings_signed_in_devices_summary,
                settingsCompactDateLabel(it.device.createdAt, timeContext),
                raftRelativeTimestampLabel(it.device.lastActiveAt, timeContext, clampFuture = true)
            )
        }
    val dismiss = { if (!dialog.submitting) dispatch(SharedSignedInDevicesIntent.CloseSignOut) }
    RaftDialog(
        onDismissRequest = dismiss,
        title = title,
        modifier = Modifier.testTag(SettingsSignOutDeviceDialogTag),
        size = RaftDialogSize.Md,
        dismissOnClickOutside = false,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = dismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !dialog.submitting
            )
            RaftButton(
                text =
                    stringResource(
                        when {
                            dialog.leftovers -> Res.string.settings_signed_in_devices_confirm_disconnect
                            dialog.disconnects -> Res.string.settings_signed_in_devices_confirm_lost
                            else -> Res.string.settings_signed_in_devices_confirm_sign_out
                        }
                    ),
                onClick = { dispatch(SharedSignedInDevicesIntent.Confirm) },
                modifier = Modifier.testTag(SettingsSignOutDeviceConfirmTag),
                variant = if (dialog.disconnects) RaftButtonVariant.Danger else RaftButtonVariant.Warning,
                size = RaftButtonSize.Lg,
                loading = dialog.submitting
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val muted = RaftTheme.colors.foregroundMuted
            when {
                dialog.leftovers ->
                    RaftText(settingsLostReachText(target, dialog.reach), style = RaftTheme.typography.subhead, color = muted)
                dialog.hasChoice -> {
                    summary?.let { RaftText(it, style = RaftTheme.typography.subhead, color = muted) }
                    val others = one == null
                    RaftRadioGroup(Modifier.fillMaxWidth()) {
                        RaftRadioRow(
                            label = stringResource(Res.string.settings_signed_in_devices_choice_sign_out),
                            selected = dialog.reason == SignedInDeviceRevokeReason.SignOut,
                            onClick = { dispatch(SharedSignedInDevicesIntent.ChooseReason(SignedInDeviceRevokeReason.SignOut)) },
                            description =
                                stringResource(
                                    if (others) Res.string.settings_signed_in_devices_choice_sign_out_all_body else Res.string.settings_signed_in_devices_choice_sign_out_body
                                ) + "\n" +
                                    stringResource(
                                        if (others) Res.string.settings_signed_in_devices_choice_sign_out_all_hint else Res.string.settings_signed_in_devices_choice_sign_out_hint
                                    ),
                            enabled = !dialog.submitting
                        )
                        RaftRadioRow(
                            label =
                                stringResource(
                                    if (others) Res.string.settings_signed_in_devices_choice_lost_all else Res.string.settings_signed_in_devices_choice_lost
                                ),
                            selected = dialog.reason == SignedInDeviceRevokeReason.LostDevice,
                            onClick = { dispatch(SharedSignedInDevicesIntent.ChooseReason(SignedInDeviceRevokeReason.LostDevice)) },
                            description = settingsLostReachText(target, dialog.reach),
                            enabled = !dialog.submitting
                        )
                    }
                }
                else -> {
                    summary?.let { RaftText(it, style = RaftTheme.typography.subhead, color = muted) }
                    RaftText(
                        stringResource(
                            if (one != null) Res.string.settings_signed_in_devices_single_body else Res.string.settings_signed_in_devices_single_all_body
                        ),
                        style = RaftTheme.typography.subhead,
                        color = muted
                    )
                }
            }
            if (dialog.failed) {
                RaftBanner(
                    description = stringResource(Res.string.settings_signed_in_devices_failed),
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm
                )
            }
        }
    }
}

/**
 * Web ReauthDialog / NoPasswordDialog for a `reauth_required`: the password (a wrong one under the
 * field, a lock or anything else in a banner), or, for an account without a password, only how to
 * prove it (sign in again) and Done.
 */
@Composable
private fun SettingsSignedInDevicesReauthDialog(
    dialog: SignedInDevicesReauthDialog,
    dispatch: (SharedSignedInDevicesIntent) -> Unit,
) {
    val close = { if (!dialog.submitting) dispatch(SharedSignedInDevicesIntent.ReauthClose) }
    if (!dialog.password) {
        RaftDialog(
            onDismissRequest = close,
            title = stringResource(Res.string.settings_step_up_title),
            modifier = Modifier.testTag(SettingsSignedInDevicesReauthDialogTag),
            size = RaftDialogSize.Md,
            description = stringResource(Res.string.settings_signed_in_devices_reauth_no_password),
            dismissOnClickOutside = false,
            closeLabel = stringResource(Res.string.common_close),
            footer = {
                RaftButton(
                    text = stringResource(Res.string.common_done),
                    onClick = close,
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Lg
                )
            }
        )
        return
    }
    RaftDialog(
        onDismissRequest = close,
        title = stringResource(Res.string.settings_step_up_title),
        modifier = Modifier.testTag(SettingsSignedInDevicesReauthDialogTag),
        size = RaftDialogSize.Md,
        description = stringResource(Res.string.settings_signed_in_devices_reauth_description),
        dismissOnClickOutside = false,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = close,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !dialog.submitting
            )
            RaftButton(
                text = stringResource(Res.string.settings_step_up_verify),
                onClick = { dispatch(SharedSignedInDevicesIntent.ReauthSubmit) },
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
                enabled = dialog.canSubmit || dialog.submitting,
                loading = dialog.submitting,
                loadingLabel = stringResource(Res.string.settings_step_up_verifying)
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftField(
                label = stringResource(Res.string.settings_reauth_password_label),
                modifier = Modifier.fillMaxWidth(),
                error = if (dialog.passwordIncorrect) stringResource(Res.string.settings_reauth_password_incorrect) else null,
                enabled = !dialog.submitting
            ) {
                RaftTextField(
                    value = dialog.input,
                    onValueChange = { dispatch(SharedSignedInDevicesIntent.ReauthInput(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !dialog.submitting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { dispatch(SharedSignedInDevicesIntent.ReauthSubmit) }),
                    visualTransformation = PasswordVisualTransformation()
                )
            }
            val dateContext = currentRaftEffectiveDateTimeContext()
            when {
                dialog.locked ->
                    RaftBanner(
                        description =
                            dialog.lockedUntilEpochMillis?.let {
                                stringResource(Res.string.settings_reauth_locked_until, dateContext.formatClock(it))
                            }
                                ?: stringResource(Res.string.settings_step_up_locked),
                        status = RaftBannerStatus.Warning,
                        size = RaftBannerSize.Sm
                    )
                dialog.failed ->
                    RaftBanner(
                        description = stringResource(Res.string.settings_reauth_failed),
                        status = RaftBannerStatus.Warning,
                        size = RaftBannerSize.Sm
                    )
            }
        }
    }
}

/** Web `announce`: a success toast, or an info toast that adds "Disconnecting the rest shortly." */
@Composable
private fun SettingsSignedInDevicesNoticeEffect(notice: SignedInDevicesNotice?, onShown: () -> Unit) {
    val toastHost = LocalRaftToastHostState.current
    val title =
        when (notice) {
            null -> null
            is SignedInDevicesNotice.SignedOut ->
                stringResource(
                    if (notice.lost) Res.string.settings_signed_in_devices_signed_out_lost else Res.string.settings_signed_in_devices_signed_out,
                    settingsSignedInDeviceLabel(notice.platform)
                )
            is SignedInDevicesNotice.SignedOutAll ->
                when (notice.count) {
                    0 -> stringResource(Res.string.settings_signed_in_devices_signed_out_all_none)
                    1 -> stringResource(Res.string.settings_signed_in_devices_signed_out_all_one, notice.count)
                    else -> stringResource(Res.string.settings_signed_in_devices_signed_out_all_other, notice.count)
                }
            is SignedInDevicesNotice.SignedOutAllLost -> stringResource(Res.string.settings_signed_in_devices_signed_out_all_lost)
            is SignedInDevicesNotice.LeftoversDone -> stringResource(Res.string.settings_signed_in_devices_leftovers_done)
        }
    val pending = stringResource(Res.string.settings_signed_in_devices_cascade_pending)
    LaunchedEffect(notice) {
        if (notice == null || title == null) return@LaunchedEffect
        if (notice.cascadePending) {
            toastHost?.show(title, description = pending, intent = RaftToastIntent.Info)
        } else {
            toastHost?.show(title, intent = RaftToastIntent.Success)
        }
        onShown()
    }
}
