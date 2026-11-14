package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.data.ClaudeFiveHourLimit
import build.raft.app.data.ClaudeLimitResetsAvailability
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAuthRuntime
import build.raft.app.data.RuntimeAuthStatus
import build.raft.app.data.RuntimeAuthView
import build.raft.app.data.RuntimeLimitResetIdempotencyKey
import build.raft.app.data.RuntimeLimitResetKind
import build.raft.app.data.RuntimeLimitResetKindStatus
import build.raft.app.data.RuntimeLimitResetRefusal
import build.raft.app.data.RuntimeLimitResetResult
import build.raft.app.data.RuntimeLimitResetsReadResult
import build.raft.app.data.claudeFiveHourLimitOf
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftChoiceList
import build.raft.designsystem.components.RaftChoiceListItem
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/*
 * Claude limit resets on the usage card (slock #14336, #14375, #14538, #14605, #14757): web
 * `claudeLimitResetOffer.ts`, `useClaudeLimitResets.tsx`, `ClaudeLimitResetsDialog.tsx`,
 * `claudeLimitResetCopy.ts`. The card offers "View available resets" and, at the 5-hour limit,
 * "Use 5-hour reset"; the list reads the account's resets in Claude Code on the Computer only
 * when asked, and the confirm uses one kind. Codex's "Use a reset" is ComputerRuntimeLimitReset.kt.
 */

/** Web `ClaudeLimitResetOffer`: use (the person who connected the Computer), or the list only. */
internal enum class ComputerRuntimeClaudeLimitResetAccess { Use, ViewOnly }

/**
 * Web `claudeLimitResetOffer`: only for Claude, a person who may act on the Computer's runtimes
 * while it can be reached, Claude Code not signed out, the server saying this Computer runs them
 * (`claudeLimitResets` ok: the flag is on) and the Computer not having answered that it cannot.
 * `canUse` alone decides: someone else may only view the list, and only below the 5-hour limit,
 * where the read needs no consent. An older server without the field: offered as usable.
 */
internal fun computerRuntimeClaudeLimitResetAccess(
    provider: RuntimeAccountUsageProvider,
    authView: RuntimeAuthView?,
    atFiveHourLimit: Boolean,
    notSupportedHere: Boolean,
): ComputerRuntimeClaudeLimitResetAccess? {
    if (provider != RuntimeAccountUsageProvider.Claude || authView == null || !authView.canActNow) return null
    if (authView.statusOf(RuntimeAuthRuntime.Claude) == RuntimeAuthStatus.LoggedOut) return null
    if (authView.claudeLimitResets != ClaudeLimitResetsAvailability.Ok || notSupportedHere) return null
    if (authView.claudeLimitResetAccess?.canUse != false) return ComputerRuntimeClaudeLimitResetAccess.Use
    return if (atFiveHourLimit) null else ComputerRuntimeClaudeLimitResetAccess.ViewOnly
}

/**
 * What the card draws (web `SurfaceClaudeLimitReset`): [view] "View available resets" (not for an
 * account known to be on Claude's older program at its 5-hour limit, where opening the list would
 * use it), [useFiveHour] "Use 5-hour reset" as the card's main action at the 5-hour limit.
 */
internal data class ComputerRuntimeClaudeLimitResetOffer(val view: Boolean, val useFiveHour: Boolean)

/** The card's two Claude actions. */
internal enum class ComputerRuntimeClaudeLimitResetEntry { View, UseFiveHour }

/** Whether the card's usage shows the Claude account at its 5-hour limit (slock `claudeFiveHourLimitOf`). */
internal fun RuntimeAccountUsageLoad?.claudeAtFiveHourLimit(): Boolean {
    val read = (this as? RuntimeAccountUsageLoad.Loaded)?.read as? RuntimeAccountUsageRead.Available ?: return false
    return claudeFiveHourLimitOf(read.snapshot) == ClaudeFiveHourLimit.AtLimit
}

/** What the card or a dialog says about a Claude limit reset (web `claudeLimitResetCopy.ts`, `limitResetOutcome.ts`). */
internal sealed interface ComputerRuntimeClaudeLimitResetText {
    data class Done(val kind: RuntimeLimitResetKind) : ComputerRuntimeClaudeLimitResetText
    data object NothingToReset : ComputerRuntimeClaudeLimitResetText
    data class NoReset(val kind: RuntimeLimitResetKind) : ComputerRuntimeClaudeLimitResetText
    data object AlreadyRedeemed : ComputerRuntimeClaudeLimitResetText
    data class FiveHourSpent(val kind: RuntimeLimitResetKind) : ComputerRuntimeClaudeLimitResetText
    data object Unknown : ComputerRuntimeClaudeLimitResetText
    data object Timeout : ComputerRuntimeClaudeLimitResetText
    data object NotAttempted : ComputerRuntimeClaudeLimitResetText
    data class NotAttemptedAtLimit(val kind: RuntimeLimitResetKind) : ComputerRuntimeClaudeLimitResetText
    data object NotSupportedHere : ComputerRuntimeClaudeLimitResetText
    data object NotEnabledForAccount : ComputerRuntimeClaudeLimitResetText
    data class NotAvailableYet(val kind: RuntimeLimitResetKind) : ComputerRuntimeClaudeLimitResetText
    data object NotInstalled : ComputerRuntimeClaudeLimitResetText
    data object Unsupported : ComputerRuntimeClaudeLimitResetText
    data object Reauth : ComputerRuntimeClaudeLimitResetText
    data class ReadFailed(val viewOnly: Boolean) : ComputerRuntimeClaudeLimitResetText
    data class ReadTimeout(val viewOnly: Boolean) : ComputerRuntimeClaudeLimitResetText
    data object OwnerOnly : ComputerRuntimeClaudeLimitResetText
    data object Forbidden : ComputerRuntimeClaudeLimitResetText
    data object ComputerOffline : ComputerRuntimeClaudeLimitResetText
    data object UpgradeRequired : ComputerRuntimeClaudeLimitResetText
    data object NotAttached : ComputerRuntimeClaudeLimitResetText
    data object RateLimited : ComputerRuntimeClaudeLimitResetText
    data object Disabled : ComputerRuntimeClaudeLimitResetText
    data object Refused : ComputerRuntimeClaudeLimitResetText

    /** A request whose outcome is not known (Codex's sentence: it will not use a second one). */
    data object RequestUnknown : ComputerRuntimeClaudeLimitResetText
}

/** Web `claudeLimitResetNote`: what the card says once a redeem has an answer the dialog closes on; null keeps it open. */
internal fun claudeLimitResetNote(kind: RuntimeLimitResetKind, result: RuntimeLimitResetResult): ComputerRuntimeClaudeLimitResetText? =
    when (result) {
        RuntimeLimitResetResult.Reset -> ComputerRuntimeClaudeLimitResetText.Done(kind)
        RuntimeLimitResetResult.NothingToReset -> ComputerRuntimeClaudeLimitResetText.NothingToReset
        RuntimeLimitResetResult.NoReset -> ComputerRuntimeClaudeLimitResetText.NoReset(kind)
        RuntimeLimitResetResult.AlreadyRedeemed -> ComputerRuntimeClaudeLimitResetText.AlreadyRedeemed
        RuntimeLimitResetResult.UnknownFiveHourSpent -> ComputerRuntimeClaudeLimitResetText.FiveHourSpent(kind)
        RuntimeLimitResetResult.Unknown -> ComputerRuntimeClaudeLimitResetText.Unknown
        else -> null
    }

/** Web `claudeLimitResetsNotSupportedHere`: the Computer cannot run Claude limit resets at all. */
internal fun claudeLimitResetsNotSupportedHere(reason: String?): Boolean =
    reason != null && reason !in setOf("not_attempted", "not_installed", "consent_required", "capability_unavailable")

private fun claudeNotSupportedText(reason: String): ComputerRuntimeClaudeLimitResetText =
    if (reason == "endpoint_unavailable") {
        ComputerRuntimeClaudeLimitResetText.NotEnabledForAccount
    } else {
        ComputerRuntimeClaudeLimitResetText.NotSupportedHere
    }

/** Web `claudeLimitResetRefusal`: why nothing was used, for an answer the confirm stays open on. */
internal fun claudeLimitResetRefusal(
    kind: RuntimeLimitResetKind,
    result: RuntimeLimitResetResult,
    atFiveHourLimit: Boolean,
): ComputerRuntimeClaudeLimitResetText =
    when (result) {
        is RuntimeLimitResetResult.ReauthRequired -> ComputerRuntimeClaudeLimitResetText.Reauth
        is RuntimeLimitResetResult.Unsupported ->
            when (result.reason) {
                "not_attempted" ->
                    if (atFiveHourLimit && kind != RuntimeLimitResetKind.FiveHour) {
                        ComputerRuntimeClaudeLimitResetText.NotAttemptedAtLimit(kind)
                    } else {
                        ComputerRuntimeClaudeLimitResetText.NotAttempted
                    }
                "not_installed" -> ComputerRuntimeClaudeLimitResetText.NotInstalled
                "capability_unavailable" -> ComputerRuntimeClaudeLimitResetText.NotAvailableYet(kind)
                else -> claudeNotSupportedText(result.reason)
            }
        else -> ComputerRuntimeClaudeLimitResetText.Unknown
    }

/** Web `limitResetRequestOutcomeUnknown`: a timeout, a server error, no answer (or one that could not be read). */
internal val RuntimeLimitResetRefusal.outcomeUnknown: Boolean
    get() = status == null || status == 408 || status >= 500 || code == "malformed_response"

/** Web `limitResetRequestErrorMessageId`: why a limit-reset request itself failed. */
internal fun RuntimeLimitResetRefusal.claudeRequestError(): ComputerRuntimeClaudeLimitResetText {
    val status = status
    return when {
        code == "claude_limit_reset_owner_only" -> ComputerRuntimeClaudeLimitResetText.OwnerOnly
        status == 403 -> ComputerRuntimeClaudeLimitResetText.Forbidden
        code == "computer_offline" -> ComputerRuntimeClaudeLimitResetText.ComputerOffline
        code == "computer_upgrade_required" -> ComputerRuntimeClaudeLimitResetText.UpgradeRequired
        code == "computer_not_attached" -> ComputerRuntimeClaudeLimitResetText.NotAttached
        status == 429 -> ComputerRuntimeClaudeLimitResetText.RateLimited
        code == "claude_limit_reset_disabled" -> ComputerRuntimeClaudeLimitResetText.Disabled
        status != null && status in 400..499 && status != 408 -> ComputerRuntimeClaudeLimitResetText.Refused
        else -> ComputerRuntimeClaudeLimitResetText.RequestUnknown
    }
}

/**
 * What a read's answer leaves the list showing (web `ClaudeLimitResetsDialog.read`). Null for
 * `consent_required` without consent: the caller asks for it (or says who can).
 */
internal fun RuntimeLimitResetsReadResult.Unsupported.claudeReadFailure(canUse: Boolean): ComputerRuntimeClaudeLimitResetText =
    when {
        claudeLimitResetsNotSupportedHere(reason) -> claudeNotSupportedText(reason)
        reason == "not_attempted" ->
            if (canUse) ComputerRuntimeClaudeLimitResetText.NotAttempted else ComputerRuntimeClaudeLimitResetText.ReadFailed(viewOnly = true)
        reason == "not_installed" -> ComputerRuntimeClaudeLimitResetText.NotInstalled
        else -> ComputerRuntimeClaudeLimitResetText.Unsupported
    }

/** The list dialog's phase (web `ReadState`). */
internal sealed interface ComputerRuntimeClaudeLimitResetsPhase {
    data object Consent : ComputerRuntimeClaudeLimitResetsPhase
    data class Reading(val consent: Boolean) : ComputerRuntimeClaudeLimitResetsPhase
    data class Read(val result: RuntimeLimitResetsReadResult.Read) : ComputerRuntimeClaudeLimitResetsPhase
    data class Failed(val message: ComputerRuntimeClaudeLimitResetText) : ComputerRuntimeClaudeLimitResetsPhase
}

/** The open "Claude limit resets" list. */
internal data class ComputerRuntimeClaudeLimitResetsListState(
    val phase: ComputerRuntimeClaudeLimitResetsPhase,
    /** The viewer connected this Computer: they can use a reset and consent; false: a read-only list. */
    val canUse: Boolean,
    val atFiveHourLimit: Boolean,
    val selected: RuntimeLimitResetKind? = null,
    /** One read with consent per dialog, however often Continue is pressed. */
    val consented: Boolean = false,
) {
    /** Web `items[].disabled`: only an available kind, and at the 5-hour limit only the 5-hour one. */
    fun choosable(status: RuntimeLimitResetKindStatus): Boolean =
        status is RuntimeLimitResetKindStatus.Available && (!atFiveHourLimit || status.kind == RuntimeLimitResetKind.FiveHour)

    val usable: Boolean
        get() {
            val read = (phase as? ComputerRuntimeClaudeLimitResetsPhase.Read)?.result ?: return false
            if (!canUse || read.legacy) return false
            val chosen = selected ?: return false
            return read.resets.any { it.kind == chosen && choosable(it) }
        }
}

/** The open "Use your … reset?" confirm; its key comes from the store, one per attempt on that kind. */
internal data class ComputerRuntimeClaudeLimitResetConfirmState(
    val kind: RuntimeLimitResetKind,
    val idempotencyKey: RuntimeLimitResetIdempotencyKey,
    val account: String?,
    /** Opened from the list: closing it goes back there. */
    val fromList: Boolean,
    val atFiveHourLimit: Boolean,
    val submitting: Boolean = false,
    val error: ComputerRuntimeClaudeLimitResetText? = null,
)

private const val CLAUDE_CODE_NAME = "Claude Code"

@Composable
internal fun RuntimeLimitResetKind.claudeName(): String =
    stringResource(
        when (this) {
            RuntimeLimitResetKind.FiveHour -> Res.string.settings_computers_runtime_usage_claude_reset_kind_five_hour
            RuntimeLimitResetKind.Weekly -> Res.string.settings_computers_runtime_usage_claude_reset_kind_weekly
            RuntimeLimitResetKind.Fable -> Res.string.settings_computers_runtime_usage_claude_reset_kind_fable
            RuntimeLimitResetKind.Full -> Res.string.settings_computers_runtime_usage_claude_reset_kind_full
        }
    )

@Composable
internal fun ComputerRuntimeClaudeLimitResetText.text(): String =
    when (this) {
        is ComputerRuntimeClaudeLimitResetText.Done -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_done, kind.claudeName())
        ComputerRuntimeClaudeLimitResetText.NothingToReset -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_nothing_to_reset)
        is ComputerRuntimeClaudeLimitResetText.NoReset -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_no_reset, kind.claudeName())
        ComputerRuntimeClaudeLimitResetText.AlreadyRedeemed -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_already_redeemed)
        is ComputerRuntimeClaudeLimitResetText.FiveHourSpent ->
            stringResource(Res.string.settings_computers_runtime_usage_claude_reset_five_hour_spent, kind.claudeName())
        ComputerRuntimeClaudeLimitResetText.Unknown -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_unknown)
        ComputerRuntimeClaudeLimitResetText.Timeout -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_timeout)
        ComputerRuntimeClaudeLimitResetText.NotAttempted -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_not_attempted)
        is ComputerRuntimeClaudeLimitResetText.NotAttemptedAtLimit ->
            stringResource(Res.string.settings_computers_runtime_usage_claude_reset_not_attempted_at_limit, kind.claudeName())
        ComputerRuntimeClaudeLimitResetText.NotSupportedHere -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_not_supported_here)
        ComputerRuntimeClaudeLimitResetText.NotEnabledForAccount ->
            stringResource(Res.string.settings_computers_runtime_usage_claude_reset_not_enabled_for_account)
        is ComputerRuntimeClaudeLimitResetText.NotAvailableYet ->
            stringResource(Res.string.settings_computers_runtime_usage_claude_reset_not_available_yet, kind.claudeName())
        ComputerRuntimeClaudeLimitResetText.NotInstalled -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_not_installed, CLAUDE_CODE_NAME)
        ComputerRuntimeClaudeLimitResetText.Unsupported -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_unsupported, CLAUDE_CODE_NAME)
        ComputerRuntimeClaudeLimitResetText.Reauth -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_reauth, CLAUDE_CODE_NAME)
        is ComputerRuntimeClaudeLimitResetText.ReadFailed ->
            stringResource(
                if (viewOnly) {
                    Res.string.settings_computers_runtime_usage_claude_reset_read_failed_view_only
                } else {
                    Res.string.settings_computers_runtime_usage_claude_reset_read_failed
                }
            )
        is ComputerRuntimeClaudeLimitResetText.ReadTimeout ->
            stringResource(
                if (viewOnly) {
                    Res.string.settings_computers_runtime_usage_claude_reset_read_timeout_view_only
                } else {
                    Res.string.settings_computers_runtime_usage_claude_reset_read_timeout
                }
            )
        ComputerRuntimeClaudeLimitResetText.OwnerOnly -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_owner_only)
        ComputerRuntimeClaudeLimitResetText.Forbidden -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_forbidden)
        ComputerRuntimeClaudeLimitResetText.ComputerOffline -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_computer_offline)
        ComputerRuntimeClaudeLimitResetText.UpgradeRequired -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_upgrade_required)
        ComputerRuntimeClaudeLimitResetText.NotAttached -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_not_attached)
        ComputerRuntimeClaudeLimitResetText.RateLimited -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_rate_limited)
        ComputerRuntimeClaudeLimitResetText.Disabled -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_disabled)
        ComputerRuntimeClaudeLimitResetText.Refused -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_refused)
        ComputerRuntimeClaudeLimitResetText.RequestUnknown -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_unknown)
    }

/** Web `claudeKindMeta`: what Claude Code showed for a kind. */
@Composable
private fun RuntimeLimitResetKindStatus.meta(): String =
    when (this) {
        is RuntimeLimitResetKindStatus.Available -> {
            val left = resetsLeft
            val count =
                when {
                    left == null -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_state_available)
                    left == 1 -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_state_left_one, left)
                    else -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_state_left_other, left)
                }
            val useBy = expiresText?.let { stringResource(Res.string.settings_computers_runtime_usage_claude_reset_state_use_by, it) }
            listOfNotNull(count, useBy).joinToString(" · ")
        }
        is RuntimeLimitResetKindStatus.None -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_state_none)
        is RuntimeLimitResetKindStatus.NotChecked -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_state_not_checked)
        is RuntimeLimitResetKindStatus.NotOffered -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_state_not_offered)
        is RuntimeLimitResetKindStatus.Unknown -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_state_unknown)
    }

/** The card's Claude actions (web `claude-limit-resets-view-action`, `claude-limit-reset-five-hour-action`). */
@Composable
internal fun ComputerRuntimeClaudeLimitResetButtons(
    offer: ComputerRuntimeClaudeLimitResetOffer,
    onEntry: (ComputerRuntimeClaudeLimitResetEntry) -> Unit,
    modifier: Modifier = Modifier,
    size: RaftButtonSize = RaftButtonSize.Md,
) {
    if (offer.view) {
        RaftButton(
            text = stringResource(Res.string.settings_computers_runtime_usage_claude_reset_view_action),
            onClick = { onEntry(ComputerRuntimeClaudeLimitResetEntry.View) },
            modifier = modifier.testTag(COMPUTER_RUNTIME_CLAUDE_LIMIT_RESETS_VIEW_TAG),
            variant = RaftButtonVariant.Default,
            size = size,
            leadingIcon = { RaftComputersIcon(RaftLucideIconName.ListChecks) },
        )
    }
    if (offer.useFiveHour) {
        RaftButton(
            text = stringResource(Res.string.settings_computers_runtime_usage_claude_reset_use_five_hour_action),
            onClick = { onEntry(ComputerRuntimeClaudeLimitResetEntry.UseFiveHour) },
            modifier = modifier.testTag(COMPUTER_RUNTIME_CLAUDE_LIMIT_RESET_FIVE_HOUR_TAG),
            variant = RaftButtonVariant.Primary,
            size = size,
            leadingIcon = { RaftComputersIcon(RaftLucideIconName.TimerReset) },
        )
    }
}

/**
 * Web `ClaudeLimitResetsDialog` at the 5-hour limit: asks the person to accept that, on Claude's
 * older program, opening the list there uses the 5-hour reset.
 */
@Composable
private fun ComputerRuntimeClaudeLimitResetsConsentDialog(onContinue: () -> Unit, onDismiss: () -> Unit) {
    RaftDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_computers_runtime_usage_claude_reset_consent_title),
        size = RaftDialogSize.Sm,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
            )
            // Disruptive but chosen with eyes open: orange, not the red of a sure loss.
            RaftButton(
                text = stringResource(Res.string.settings_computers_runtime_usage_claude_reset_consent_confirm),
                onClick = onContinue,
                modifier = Modifier.testTag(COMPUTER_RUNTIME_CLAUDE_LIMIT_RESETS_CONSENT_TAG),
                variant = RaftButtonVariant.Warning,
                size = RaftButtonSize.Lg,
            )
        },
    ) {
        RaftText(
            stringResource(Res.string.settings_computers_runtime_usage_claude_reset_consent_body),
            style = RaftTheme.typography.subhead,
            color = RaftTheme.colors.foregroundMuted,
        )
    }
}

/**
 * Web `ClaudeLimitResetsDialog`: the consent step at the 5-hour limit, then the read, each kind with
 * what Claude Code showed, and "Use this reset" for the chosen one. A viewer who did not connect
 * the Computer gets display-only rows and Close.
 */
@Composable
internal fun ComputerRuntimeClaudeLimitResetsDialog(
    state: ComputerRuntimeClaudeLimitResetsListState,
    onContinue: () -> Unit,
    onSelect: (RuntimeLimitResetKind) -> Unit,
    onUse: () -> Unit,
    onDismiss: () -> Unit,
) {
    val phase = state.phase
    if (phase == ComputerRuntimeClaudeLimitResetsPhase.Consent) {
        ComputerRuntimeClaudeLimitResetsConsentDialog(onContinue, onDismiss)
        return
    }
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val listed = (phase as? ComputerRuntimeClaudeLimitResetsPhase.Read)?.result
    val rows = if (listed != null && !listed.legacy) listed.resets else emptyList()
    val listLabel = stringResource(Res.string.settings_computers_runtime_usage_claude_reset_list_label)
    RaftDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_computers_runtime_usage_claude_reset_dialog_title),
        size = RaftDialogSize.Md,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            if (state.canUse) {
                RaftButton(
                    text = stringResource(Res.string.common_cancel),
                    onClick = onDismiss,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                )
                RaftButton(
                    text = stringResource(Res.string.settings_computers_runtime_usage_claude_reset_use_selected_action),
                    onClick = onUse,
                    modifier = Modifier.testTag(COMPUTER_RUNTIME_CLAUDE_LIMIT_RESETS_USE_TAG),
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                    enabled = state.usable,
                )
            } else {
                // Read-only: nothing to use, so Close is the one action.
                RaftButton(
                    text = stringResource(Res.string.common_close),
                    onClick = onDismiss,
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Lg,
                )
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().testTag(COMPUTER_RUNTIME_CLAUDE_LIMIT_RESETS_DIALOG_TAG), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (phase is ComputerRuntimeClaudeLimitResetsPhase.Reading) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftSpinner(size = RaftSpinnerSize.Xs)
                    RaftText(
                        stringResource(
                            if (state.canUse) {
                                Res.string.settings_computers_runtime_usage_claude_reset_reading
                            } else {
                                Res.string.settings_computers_runtime_usage_claude_reset_reading_view_only
                            }
                        ),
                        style = typography.subhead,
                        color = colors.foregroundMuted,
                    )
                }
            }
            if (phase is ComputerRuntimeClaudeLimitResetsPhase.Failed) {
                RaftBanner(description = phase.message.text(), status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
            }
            if (listed?.fiveHourSpent == true) {
                RaftBanner(
                    description = stringResource(Res.string.settings_computers_runtime_usage_claude_reset_read_five_hour_spent),
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                )
            }
            if (listed?.legacy == true) {
                RaftText(
                    stringResource(
                        if (state.canUse) {
                            Res.string.settings_computers_runtime_usage_claude_reset_legacy
                        } else {
                            Res.string.settings_computers_runtime_usage_claude_reset_legacy_view_only
                        }
                    ),
                    style = typography.subhead,
                    color = colors.foregroundMuted,
                )
            }
            if (rows.isNotEmpty() && !state.canUse) {
                // Display-only rows: a radio list with nothing to do with the choice would be a dead end.
                Column(Modifier.fillMaxWidth()) {
                    rows.forEach { status ->
                        RaftListRow(
                            title = status.kind.claudeName(),
                            variant = RaftListRowVariant.Plain,
                            description = status.meta(),
                        )
                    }
                }
            }
            if (rows.isNotEmpty() && state.canUse) {
                val items =
                    rows.map { status ->
                        RaftChoiceListItem(
                            value = status.kind,
                            label = status.kind.claudeName(),
                            descriptions = listOf(AnnotatedString(status.meta())),
                            enabled = state.choosable(status),
                        )
                    }
                RaftChoiceList(
                    items = items,
                    value = state.selected,
                    onValueChange = onSelect,
                    searchPlaceholder = listLabel,
                    clearSearchLabel = stringResource(Res.string.common_clear),
                    noMatchLabel = listLabel,
                    emptyLabel = listLabel,
                )
                if (state.atFiveHourLimit) {
                    RaftText(
                        stringResource(Res.string.settings_computers_runtime_usage_claude_reset_at_limit_hint),
                        style = typography.footnote,
                        color = colors.foregroundMuted,
                    )
                }
            }
        }
    }
}

/** Web `ClaudeLimitResetConfirmDialog`: names the kind and the account, and that it cannot be undone. */
@Composable
internal fun ComputerRuntimeClaudeLimitResetConfirmDialog(
    state: ComputerRuntimeClaudeLimitResetConfirmState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val name = state.kind.claudeName()
    val account = state.account ?: stringResource(Res.string.settings_computers_runtime_usage_limit_reset_account_fallback)
    val body = stringResource(Res.string.settings_computers_runtime_usage_claude_reset_confirm_body, account, name)
    val dismiss = { if (!state.submitting) onDismiss() }
    RaftDialog(
        onDismissRequest = dismiss,
        title = stringResource(Res.string.settings_computers_runtime_usage_claude_reset_confirm_title, name),
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
                text = stringResource(Res.string.settings_computers_runtime_usage_claude_reset_confirm, name),
                onClick = onConfirm,
                modifier = Modifier.testTag(COMPUTER_RUNTIME_CLAUDE_LIMIT_RESET_CONFIRM_TAG),
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
                loading = state.submitting,
            )
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftText(
                claudeLimitResetConfirmBody(body, account),
                style = RaftTheme.typography.subhead,
                color = RaftTheme.colors.foregroundMuted,
            )
            state.error?.let { error ->
                RaftBanner(description = error.text(), status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
            }
        }
    }
}

/** The body with the account's name in bold ink, as the web's `font-semibold text-foreground` span. */
@Composable
private fun claudeLimitResetConfirmBody(body: String, account: String): AnnotatedString {
    val ink = RaftTheme.colors.foreground
    return buildAnnotatedString {
        append(body)
        val start = body.indexOf(account)
        if (start >= 0) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = ink), start, start + account.length)
    }
}

internal const val COMPUTER_RUNTIME_CLAUDE_LIMIT_RESETS_VIEW_TAG = "claude-limit-resets-view-action"
internal const val COMPUTER_RUNTIME_CLAUDE_LIMIT_RESET_FIVE_HOUR_TAG = "claude-limit-reset-five-hour-action"
internal const val COMPUTER_RUNTIME_CLAUDE_LIMIT_RESETS_DIALOG_TAG = "claude-limit-resets-dialog"
internal const val COMPUTER_RUNTIME_CLAUDE_LIMIT_RESETS_CONSENT_TAG = "claude-limit-resets-consent"
internal const val COMPUTER_RUNTIME_CLAUDE_LIMIT_RESETS_USE_TAG = "claude-limit-resets-use"
internal const val COMPUTER_RUNTIME_CLAUDE_LIMIT_RESET_CONFIRM_TAG = "claude-limit-reset-confirm"
