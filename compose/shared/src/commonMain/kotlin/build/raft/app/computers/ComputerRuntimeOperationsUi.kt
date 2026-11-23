package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import build.raft.app.data.RuntimeAuthRuntime
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCodeBlock
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftPreviewCard
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.components.rememberRaftPreviewCardState
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/*
 * Upgrade on a runtime's card and Install on a not-installed runtime chip (web RuntimeUpgradeButton,
 * RuntimeUpgradeOutcome, MachineRuntimeChips' InstallCard, RuntimeInstallDialog, RuntimeInstallOutcome
 * at botiverse/slock #15117 / #15214), and the card's update-check line (#15429 RuntimeUsageSurface).
 * State and copy: ComputerRuntimeOperations.kt.
 */

/**
 * [copy] with each version in mono. Each version stands in as its own private-use character while the
 * sentence is formatted, so a translation may place them in any order.
 */
@Composable
internal fun computerRuntimeUpdateCheckText(copy: ComputerRuntimeUpdateCheckCopy): AnnotatedString {
    val slots = copy.args.indices.map { (ComputerRuntimeVersionSlot + it).toString() }
    val sentence = stringResource(copy.message, *slots.toTypedArray())
    val mono = RaftTheme.typography.monoMeta.toSpanStyle()
    return remember(sentence, copy.args, mono) {
        buildAnnotatedString {
            sentence.forEach { char ->
                val index = char - ComputerRuntimeVersionSlot
                if (index in copy.args.indices) withStyle(mono) { append(copy.args[index]) } else append(char)
            }
        }
    }
}

/** U+E000, the first private-use character: the first version's stand-in while the sentence is formatted. */
private const val ComputerRuntimeVersionSlot = '\uE000'

/**
 * What the last Upgrade or Install learned, one plain sentence (web `RuntimeUpgradeOutcome` /
 * `RuntimeInstallOutcome`); nothing when there is no [copy]. Ink once it helped, orange-strong when
 * the person has something to do; a failed request's trace id under it.
 */
@Composable
internal fun ComputerRuntimeOperationOutcome(copy: ComputerRuntimeOperationCopy?, runtimeName: String, modifier: Modifier = Modifier) {
    if (copy == null) return
    Column(modifier.fillMaxWidth().testTag(COMPUTER_RUNTIME_OPERATION_OUTCOME_TAG), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RaftText(
            copy.serverMessage ?: stringResource(copy.message, runtimeName, *copy.args.toTypedArray()),
            style = RaftTheme.typography.caption,
            color = if (copy.success) RaftTheme.colors.foreground else RaftTheme.colors.warningStrong,
        )
        if (copy.traceId != null) RaftTraceIdNote(copy.traceId)
    }
}

/**
 * Install on one Computer for the Computer page's runtime chips (web MachineRuntimeChips): reads the
 * Computer's runtime-auth view only when [readNow] (the not-installed chips are shown, to a person
 * who may see its runtimes). Null when the page has no store, owner or server.
 */
@Composable
internal fun rememberComputerRuntimeInstallController(
    access: MembersRuntimeAccountUsageAccess?,
    machineId: String,
    readNow: Boolean,
): ComputerRuntimeInstallController? {
    val owner = access?.owner
    val serverId = access?.serverId?.trim()?.takeIf { it.isNotEmpty() }
    // Only where the page acts on the Computer's runtimes (its sign-in host): the Computer page.
    val host = access?.auth
    val scope = rememberCoroutineScope()
    val controller =
        remember(owner, serverId, machineId, access?.store, host) {
            if (owner == null || serverId == null || host == null || machineId.isBlank()) {
                null
            } else {
                ComputerRuntimeInstallController(
                    ops = StoreComputerRuntimeInstallOps(access.store, owner, serverId, machineId),
                    scope = scope,
                    log = access.log,
                    onIdentityChanged = access.onIdentityChanged,
                )
            }
        }
    LaunchedEffect(controller, readNow) { if (readNow) controller?.read() }
    return controller
}

/**
 * A not-installed runtime the Computer can install from here (web `InstallCard` in a muted chip's
 * popover): the chip is a button (web `PopoverTrigger openOnHover`) that opens a small card that
 * says so and offers Install, which first shows what would run. A mouse resting on it opens the card
 * too; its click, or a finger's tap, pins it. No long-press copy: the chip's one gesture is to open
 * the card (web: the chip copies nothing). The action is an ink-border button, busy while the plan
 * is read or the installer runs; another runtime's chip is disabled meanwhile.
 */
@Composable
internal fun ComputerRuntimeInstallChip(
    label: String,
    raftRuntimeId: String,
    runtimeName: String,
    installAs: RuntimeAuthRuntime,
    controller: ComputerRuntimeInstallController,
) {
    val state = controller.state
    val mine = state is ComputerRuntimeInstallState.ForRuntime && state.runtime == installAs
    val busy = controller.busy
    val running = mine && state is ComputerRuntimeInstallState.Running
    val buttonLabel = stringResource(if (running) Res.string.settings_computers_runtime_install_running else Res.string.settings_computers_runtime_install_action)
    val cardState = rememberRaftPreviewCardState()
    RaftPreviewCard(
        card = {
            Column(Modifier.padding(16.dp).width(268.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RaftText(
                    stringResource(Res.string.settings_computers_runtime_install_not_installed, runtimeName),
                    style = RaftTheme.typography.callout,
                    color = RaftTheme.colors.foregroundMuted,
                )
                RaftButton(
                    text = buttonLabel,
                    onClick = {
                        cardState.dismiss()
                        controller.start(installAs, raftRuntimeId, runtimeName)
                    },
                    modifier = Modifier.testTag("$COMPUTER_RUNTIME_INSTALL_TAG-$raftRuntimeId"),
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Md,
                    enabled = !busy || mine,
                    loading = busy && mine,
                    loadingLabel = buttonLabel,
                )
            }
        },
        state = cardState,
        pinOnClick = true,
        trigger = {
            RaftBadge(
                text = label,
                modifier = Modifier.testTag("$COMPUTER_RUNTIME_INSTALL_CHIP_TAG-$raftRuntimeId"),
                appearance = RaftBadgeAppearance.Soft,
                variant = RaftBadgeVariant.Muted,
                onClick = cardState::pin,
            )
        },
    )
}

/**
 * Under the runtime chips: "Installing … This can take a few minutes." while the installer runs, then
 * the outcome (web `RuntimeInstallOutcome`); and the confirm or manual steps while one is open.
 */
@Composable
internal fun ComputerRuntimeInstallStatus(controller: ComputerRuntimeInstallController, computerName: String?, openUrl: (String) -> Unit) {
    when (val state = controller.state) {
        is ComputerRuntimeInstallState.Running ->
            RaftText(
                stringResource(Res.string.settings_computers_runtime_install_running_status, state.runtimeName),
                modifier = Modifier.fillMaxWidth().testTag(COMPUTER_RUNTIME_OPERATION_OUTCOME_TAG),
                style = RaftTheme.typography.caption,
                color = RaftTheme.colors.foregroundMuted,
            )
        is ComputerRuntimeInstallState.Confirm, is ComputerRuntimeInstallState.Manual ->
            ComputerRuntimeInstallDialog(
                state = state as ComputerRuntimeInstallState.ForRuntime,
                computerName = computerName ?: stringResource(Res.string.settings_computers_runtime_auth_this_computer),
                onConfirm = controller::confirm,
                onClose = controller::cancel,
                openUrl = openUrl,
            )
        is ComputerRuntimeInstallState.ForRuntime ->
            ComputerRuntimeOperationOutcome(computerRuntimeInstallCopy(state), runtimeName = state.runtimeName)
        ComputerRuntimeInstallState.Idle -> Unit
    }
}

/**
 * Web `RuntimeInstallDialog`: before anything runs, the person reads the exact commands the Computer
 * would run (OAR's plan, as the vendor documents them) and the vendor's page, then presses Install
 * (pink: it does a thing) or Cancel. Where OAR will not run them here, the same steps are shown for
 * the person to run on the Computer themselves, with Close only.
 */
@Composable
internal fun ComputerRuntimeInstallDialog(
    state: ComputerRuntimeInstallState.ForRuntime,
    computerName: String,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
    /** Opens the vendor's page in the browser. */
    openUrl: (String) -> Unit,
) {
    val manual = state as? ComputerRuntimeInstallState.Manual
    val plan = (state as? ComputerRuntimeInstallState.Confirm)?.plan
    val steps = manual?.plan?.steps ?: plan?.steps ?: return
    val source = manual?.plan?.source ?: plan?.source
    val runtime = state.runtimeName
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    RaftComputersTheme {
        RaftDialog(
            onDismissRequest = onClose,
            title =
                stringResource(
                    if (manual != null) Res.string.settings_computers_runtime_install_manual_title else Res.string.settings_computers_runtime_install_confirm_title,
                    runtime,
                ),
            modifier = Modifier.testTag(COMPUTER_RUNTIME_INSTALL_DIALOG_TAG),
            size = RaftDialogSize.Sm,
            closeLabel = stringResource(Res.string.common_close),
            footer = {
                if (manual != null) {
                    RaftButton(
                        stringResource(Res.string.settings_computers_runtime_install_manual_close),
                        onClick = onClose,
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Lg,
                    )
                } else {
                    RaftButton(stringResource(Res.string.common_cancel), onClick = onClose, variant = RaftButtonVariant.Ghost, size = RaftButtonSize.Lg)
                    RaftButton(
                        stringResource(Res.string.settings_computers_runtime_install_action),
                        onClick = onConfirm,
                        modifier = Modifier.testTag(COMPUTER_RUNTIME_INSTALL_CONFIRM_TAG),
                        variant = RaftButtonVariant.Primary,
                        size = RaftButtonSize.Lg,
                    )
                }
            },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RaftText(
                    if (manual != null) {
                        stringResource(computerRuntimeInstallUnsupportedCopy(manual.plan.reason), runtime)
                    } else {
                        stringResource(Res.string.settings_computers_runtime_install_confirm_description, runtime, computerName)
                    },
                    style = typography.subhead,
                    color = colors.foregroundMuted,
                )
                manual?.plan?.detail?.let { RaftText(it, style = typography.subhead, color = colors.foregroundMuted) }
                if (manual != null) {
                    RaftText(
                        stringResource(Res.string.settings_computers_runtime_install_manual_steps, computerName),
                        style = typography.subhead,
                        color = colors.foregroundMuted,
                    )
                }
                RaftCodeBlock(text = steps.joinToString("\n"), modifier = Modifier.testTag(COMPUTER_RUNTIME_INSTALL_STEPS_TAG))
                if (source != null) {
                    RaftTextLink(
                        text = stringResource(Res.string.settings_computers_runtime_install_source),
                        onClick = { openUrl(source) },
                        variant = RaftTextLinkVariant.Strong,
                        style = typography.footnote,
                        leavesRaft = true,
                    )
                }
            }
        }
    }
}

internal const val COMPUTER_RUNTIME_UPGRADE_TAG = "runtime-upgrade-action"
internal const val COMPUTER_RUNTIME_UPDATE_CHECK_TAG = "runtime-update-check"
internal const val COMPUTER_RUNTIME_OPERATION_OUTCOME_TAG = "runtime-operation-outcome"
internal const val COMPUTER_RUNTIME_INSTALL_TAG = "machine-runtime-install"
internal const val COMPUTER_RUNTIME_INSTALL_CHIP_TAG = "machine-runtime-install-chip"
internal const val COMPUTER_RUNTIME_INSTALL_DIALOG_TAG = "runtime-install-dialog"
internal const val COMPUTER_RUNTIME_INSTALL_CONFIRM_TAG = "runtime-install-confirm"
internal const val COMPUTER_RUNTIME_INSTALL_STEPS_TAG = "runtime-install-steps"
