package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.data.RuntimeAccountUsageAccount
import build.raft.app.data.RuntimeAccountUsageHealth
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAccountUsageWindowStatus
import build.raft.app.data.RuntimeAuthAvailability
import build.raft.app.data.RuntimeAuthView
import build.raft.app.data.RuntimeLimitResetIdempotencyKey
import build.raft.app.data.RuntimeLimitResetRefusal
import build.raft.app.data.RuntimeLimitResetResult
import build.raft.app.data.authRuntime
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/*
 * Use a Codex limit reset from the usage card (slock #13504: web `RuntimeUsageSurface`'s
 * "Use a reset", `RuntimeLimitResetDialog`, `limitResetOutcome.ts`).
 */

/** The card's limit-reset action (web `SurfaceLimitReset`). */
internal data class ComputerRuntimeLimitResetOffer(
    /** The account is rate-limited right now: the reset is the card's main action then. */
    val primary: Boolean,
)

/** The account whose reset the button spends (web `resetAccount`: the first holding one; Codex reports one account). */
internal fun RuntimeAccountUsageLoad?.limitResetAccount(): RuntimeAccountUsageAccount? {
    val read = (this as? RuntimeAccountUsageLoad.Loaded)?.read as? RuntimeAccountUsageRead.Available ?: return null
    return read.snapshot.accounts.firstOrNull { (it.limitResetsAvailable ?: 0) > 0 }
}

/**
 * Web `surfaceLimitReset`: offered only while the account holds a reset, to a person who may act
 * on the Computer's runtimes (`canAct`, the human who attached it or a server owner/admin) while
 * the Computer can be reached (`availability` ok), and not while the runtime is signed out. A
 * runtime with no sign-in from the web (Grok) has no runtime-auth view, so no action.
 */
internal fun computerRuntimeLimitResetOffer(
    load: RuntimeAccountUsageLoad?,
    authView: RuntimeAuthView?,
    provider: RuntimeAccountUsageProvider,
): ComputerRuntimeLimitResetOffer? {
    val account = load.limitResetAccount() ?: return null
    val authRuntime = provider.authRuntime ?: return null
    if (authView == null || !authView.canAct || authView.availability != RuntimeAuthAvailability.Ok) return null
    if (authView.statusKinds[authRuntime] == "logged_out") return null
    return ComputerRuntimeLimitResetOffer(
        primary =
            account.health == RuntimeAccountUsageHealth.RateLimited ||
                account.windows.any { it.status == RuntimeAccountUsageWindowStatus.LimitReached },
    )
}

/** What the last limit reset did, said once in the card's status line (web `limitResetNoteMessageId`). */
internal enum class ComputerRuntimeLimitResetNote { Done, NothingToReset, NoReset, AlreadyRedeemed }

/** Why the confirm dialog stays open (web `limitResetFailureMessageId` and `requestErrorMessageId`). */
internal enum class ComputerRuntimeLimitResetError {
    Reauth,
    NotInstalled,
    Unsupported,
    Unknown,
    Forbidden,
    ComputerOffline,
    UpgradeRequired,
    NotAttached,
}

internal fun RuntimeLimitResetResult.limitResetNote(): ComputerRuntimeLimitResetNote? =
    when (this) {
        RuntimeLimitResetResult.Reset -> ComputerRuntimeLimitResetNote.Done
        RuntimeLimitResetResult.NothingToReset -> ComputerRuntimeLimitResetNote.NothingToReset
        RuntimeLimitResetResult.NoReset -> ComputerRuntimeLimitResetNote.NoReset
        RuntimeLimitResetResult.AlreadyRedeemed -> ComputerRuntimeLimitResetNote.AlreadyRedeemed
        else -> null
    }

internal fun RuntimeLimitResetResult.limitResetError(): ComputerRuntimeLimitResetError? =
    when (this) {
        is RuntimeLimitResetResult.ReauthRequired -> ComputerRuntimeLimitResetError.Reauth
        is RuntimeLimitResetResult.Unsupported ->
            if (reason == "not_installed") ComputerRuntimeLimitResetError.NotInstalled else ComputerRuntimeLimitResetError.Unsupported
        RuntimeLimitResetResult.Unknown -> ComputerRuntimeLimitResetError.Unknown
        else -> null
    }

/** A request with no outcome: a timeout, a server error or a dropped connection may or may not have spent one. */
internal fun RuntimeLimitResetRefusal.limitResetError(): ComputerRuntimeLimitResetError =
    when {
        status == 403 -> ComputerRuntimeLimitResetError.Forbidden
        code == "computer_offline" -> ComputerRuntimeLimitResetError.ComputerOffline
        code == "computer_upgrade_required" -> ComputerRuntimeLimitResetError.UpgradeRequired
        code == "computer_not_attached" -> ComputerRuntimeLimitResetError.NotAttached
        else -> ComputerRuntimeLimitResetError.Unknown
    }

/** The open confirm dialog. Its account and count are fixed for its life: the read after an outcome may change them. */
internal data class ComputerRuntimeLimitResetDialogState(
    /** The account's masked email or name, as the card shows it; null when it has none. */
    val account: String?,
    val resetsAvailable: Int,
    val idempotencyKey: RuntimeLimitResetIdempotencyKey,
    val submitting: Boolean = false,
    val error: ComputerRuntimeLimitResetError? = null,
)

@Composable
internal fun ComputerRuntimeLimitResetNote.text(): String =
    stringResource(
        when (this) {
            ComputerRuntimeLimitResetNote.Done -> Res.string.settings_computers_runtime_usage_limit_reset_done
            ComputerRuntimeLimitResetNote.NothingToReset -> Res.string.settings_computers_runtime_usage_limit_reset_nothing_to_reset
            ComputerRuntimeLimitResetNote.NoReset -> Res.string.settings_computers_runtime_usage_limit_reset_no_reset
            ComputerRuntimeLimitResetNote.AlreadyRedeemed -> Res.string.settings_computers_runtime_usage_limit_reset_already_redeemed
        }
    )

@Composable
private fun ComputerRuntimeLimitResetError.text(runtimeName: String): String =
    when (this) {
        ComputerRuntimeLimitResetError.Reauth -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_reauth, runtimeName)
        ComputerRuntimeLimitResetError.NotInstalled ->
            stringResource(Res.string.settings_computers_runtime_usage_limit_reset_not_installed, runtimeName)
        ComputerRuntimeLimitResetError.Unsupported ->
            stringResource(Res.string.settings_computers_runtime_usage_limit_reset_unsupported, runtimeName)
        ComputerRuntimeLimitResetError.Unknown -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_unknown)
        ComputerRuntimeLimitResetError.Forbidden -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_forbidden)
        ComputerRuntimeLimitResetError.ComputerOffline -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_computer_offline)
        ComputerRuntimeLimitResetError.UpgradeRequired -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_upgrade_required)
        ComputerRuntimeLimitResetError.NotAttached -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_not_attached)
    }

/** "N resets left" / "No resets left" beside the account (web `runtime-account-limit-resets`). */
@Composable
internal fun computerRuntimeLimitResetsLeftText(resets: Int): String =
    when {
        resets <= 0 -> stringResource(Res.string.settings_computers_runtime_usage_limit_resets_none)
        resets == 1 -> stringResource(Res.string.settings_computers_runtime_usage_limit_resets_left_one, resets)
        else -> stringResource(Res.string.settings_computers_runtime_usage_limit_resets_left_other, resets)
    }

/** The card's "Use a reset" button; primary only while the account is rate-limited. */
@Composable
internal fun ComputerRuntimeLimitResetButton(
    offer: ComputerRuntimeLimitResetOffer,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: RaftButtonSize = RaftButtonSize.Md,
) {
    RaftButton(
        text = stringResource(Res.string.settings_computers_runtime_usage_limit_reset_action),
        onClick = onClick,
        modifier = modifier.testTag(COMPUTER_RUNTIME_LIMIT_RESET_ACTION_TAG),
        variant = if (offer.primary) RaftButtonVariant.Primary else RaftButtonVariant.Default,
        size = size,
        leadingIcon = { RaftComputersIcon(RaftLucideIconName.TimerReset) },
    )
}

/**
 * Web `RuntimeLimitResetDialog`: confirms spending one of the account's resets, naming the
 * account whose reset it is. An outcome the person has to act on keeps it open with what
 * happened; any other closes it and the card says what the reset did.
 */
@Composable
internal fun ComputerRuntimeLimitResetDialog(
    runtimeName: String,
    state: ComputerRuntimeLimitResetDialogState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val account = state.account ?: stringResource(Res.string.settings_computers_runtime_usage_limit_reset_account_fallback)
    val description =
        stringResource(
            if (state.resetsAvailable == 1) {
                Res.string.settings_computers_runtime_usage_limit_reset_description_one
            } else {
                Res.string.settings_computers_runtime_usage_limit_reset_description_other
            },
            account,
            runtimeName,
            state.resetsAvailable,
        )
    val dismiss = { if (!state.submitting) onDismiss() }
    RaftDialog(
        onDismissRequest = dismiss,
        title = stringResource(Res.string.settings_computers_runtime_usage_limit_reset_title),
        size = RaftDialogSize.Sm,
        dismissOnClickOutside = !state.submitting,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = dismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !state.submitting,
            )
            RaftButton(
                text = stringResource(Res.string.settings_computers_runtime_usage_limit_reset_confirm),
                onClick = onConfirm,
                modifier = Modifier.testTag(COMPUTER_RUNTIME_LIMIT_RESET_CONFIRM_TAG),
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
                loading = state.submitting,
            )
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftText(
                computerRuntimeLimitResetDescription(description, account),
                style = RaftTheme.typography.subhead,
                color = RaftTheme.colors.foregroundMuted,
            )
            state.error?.let { error ->
                RaftBanner(description = error.text(runtimeName), status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
            }
        }
    }
}

/** The description with the account's name in bold ink, as the web's `font-semibold text-foreground` span. */
@Composable
private fun computerRuntimeLimitResetDescription(description: String, account: String): AnnotatedString {
    val ink = RaftTheme.colors.foreground
    return buildAnnotatedString {
        append(description)
        val start = description.indexOf(account)
        if (start >= 0) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = ink), start, start + account.length)
    }
}

internal const val COMPUTER_RUNTIME_LIMIT_RESET_ACTION_TAG = "runtime-limit-reset-action"
internal const val COMPUTER_RUNTIME_LIMIT_RESET_CONFIRM_TAG = "runtime-limit-reset-confirm"
