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
 * Claude limit resets on the usage card, as Claude Code's own `/limit-reset` (slock #14336, #14757,
 * #15564, #15574; flag `claude_limit_reset_v2`): web `claudeLimitResetOffer.ts`,
 * `useClaudeLimitResets.tsx`, `ClaudeLimitResetsDialog.tsx`, `claudeLimitResetCopy.ts`. The card
 * has one entry, "Use a limit reset"; the press reads at once ("This takes a moment"), then
 * [claudeLimitResetReadStep] decides: one usable kind goes straight to its confirm, several to
 * "Which reset do you want to use?", anything else ends with a line on the card (on Claude's
 * older program the read itself may have used the 5-hour reset). Codex's "Use a reset" is
 * ComputerRuntimeLimitReset.kt.
 */

/** Web `ClaudeLimitResetOffer`: use (the person who connected the Computer, or an owner or admin), or the list only (an older server). */
internal enum class ComputerRuntimeClaudeLimitResetAccess { Use, ViewOnly }

/**
 * Web `claudeLimitResetOffer`: only for Claude where `claude_limit_reset_v2` is on ([flagOn]), a
 * person who may act on the Computer's runtimes while it can be reached, Claude Code not signed
 * out, the server saying this Computer runs them (`claudeLimitResets` ok: the server's copy of the
 * same flag is on) and the Computer not having answered that it cannot. `canUse` alone decides.
 * The current server sends it true to everyone it sends the resets to (the person who connected
 * the Computer, an owner or admin; slock #15124); an older server sent it false to an owner or
 * admin who did not connect it, who may then only view the list, and only below the 5-hour
 * limit, where the read needs no consent. A server without the field: offered as usable.
 */
internal fun computerRuntimeClaudeLimitResetAccess(
    provider: RuntimeAccountUsageProvider,
    flagOn: Boolean,
    authView: RuntimeAuthView?,
    atFiveHourLimit: Boolean,
    notSupportedHere: Boolean,
): ComputerRuntimeClaudeLimitResetAccess? {
    if (provider != RuntimeAccountUsageProvider.Claude || !flagOn || authView == null || !authView.canActNow) return null
    if (authView.statusOf(RuntimeAuthRuntime.Claude) is RuntimeAuthStatus.LoggedOut) return null
    if (authView.claudeLimitResets != ClaudeLimitResetsAvailability.Ok || notSupportedHere) return null
    if (authView.claudeLimitResetAccess?.canUse != false) return ComputerRuntimeClaudeLimitResetAccess.Use
    return if (atFiveHourLimit) null else ComputerRuntimeClaudeLimitResetAccess.ViewOnly
}

/**
 * The card's one Claude entry (web `SurfaceClaudeLimitReset`): "Use a limit reset" for a viewer
 * who may use them ([canUse]), the card's main action at the 5-hour limit ([primary]); "View
 * available resets" (a read-only list) for one who may not.
 */
internal data class ComputerRuntimeClaudeLimitResetOffer(val canUse: Boolean, val primary: Boolean)

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
    data object OnboardingRequired : ComputerRuntimeClaudeLimitResetText
    data object NotSupportedHere : ComputerRuntimeClaudeLimitResetText
    data object NotEnabledForAccount : ComputerRuntimeClaudeLimitResetText
    data class NotAvailableYet(val kind: RuntimeLimitResetKind) : ComputerRuntimeClaudeLimitResetText
    data object NotInstalled : ComputerRuntimeClaudeLimitResetText
    data object Unsupported : ComputerRuntimeClaudeLimitResetText
    data object Reauth : ComputerRuntimeClaudeLimitResetText
    data class ReadFailed(val viewOnly: Boolean) : ComputerRuntimeClaudeLimitResetText
    data class ReadTimeout(val viewOnly: Boolean) : ComputerRuntimeClaudeLimitResetText
    data object ReadNotAttempted : ComputerRuntimeClaudeLimitResetText
    data object Forbidden : ComputerRuntimeClaudeLimitResetText
    data object ComputerOffline : ComputerRuntimeClaudeLimitResetText
    data object UpgradeRequired : ComputerRuntimeClaudeLimitResetText
    data object NotAttached : ComputerRuntimeClaudeLimitResetText
    data object RateLimited : ComputerRuntimeClaudeLimitResetText
    data object Disabled : ComputerRuntimeClaudeLimitResetText
    data object Refused : ComputerRuntimeClaudeLimitResetText

    /** A request whose outcome is not known (Codex's sentence: it will not use a second one). */
    data object RequestUnknown : ComputerRuntimeClaudeLimitResetText

    /** The read's line on the card: on Claude's older program it used the 5-hour reset (web `legacyReset`). */
    data object LegacyReset : ComputerRuntimeClaudeLimitResetText

    /** Older program, this week's 5-hour reset already gone: nothing was used (web `legacyUsed`). */
    data object LegacyUsed : ComputerRuntimeClaudeLimitResetText

    /** Older program below the 5-hour limit: nothing was used (web `legacyKept`). */
    data object LegacyKept : ComputerRuntimeClaudeLimitResetText

    /** Newer program, no kind to use (web `noneToUse`). */
    data object NoneToUse : ComputerRuntimeClaudeLimitResetText

    /** At the 5-hour limit only other kinds are left: they are on claude.ai (web `noneAtLimit`). */
    data object NoneAtLimit : ComputerRuntimeClaudeLimitResetText

    /** "No, keep it" before anything was sent (web `claudeLimitResetKeptNote`); [expiresText]: what the read said. */
    data class Kept(val kind: RuntimeLimitResetKind, val expiresText: String?) : ComputerRuntimeClaudeLimitResetText
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

/**
 * Web `claudeLimitResetsNotSupportedHere`: the Computer cannot run Claude limit resets at all. Not
 * for nothing attempted, Claude Code's unfinished first-run setup, a missing Claude Code, consent,
 * or a kind this account cannot use here yet.
 */
internal fun claudeLimitResetsNotSupportedHere(reason: String?): Boolean =
    reason != null && reason !in setOf("not_attempted", "onboarding_required", "not_installed", "consent_required", "capability_unavailable")

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
                "onboarding_required" -> ComputerRuntimeClaudeLimitResetText.OnboardingRequired
                "capability_unavailable" -> ComputerRuntimeClaudeLimitResetText.NotAvailableYet(kind)
                else -> claudeNotSupportedText(result.reason)
            }
        else -> ComputerRuntimeClaudeLimitResetText.Unknown
    }

/** Web `limitResetRequestOutcomeUnknown`: a timeout, a server error, no answer (or one that could not be read). */
internal val RuntimeLimitResetRefusal.outcomeUnknown: Boolean
    get() {
        val codeStatus = status
        return codeStatus == null || codeStatus == 408 || codeStatus >= 500 || code == "malformed_response"
    }

/** Web `limitResetRequestErrorMessageId`: why a limit-reset request itself failed. */
internal fun RuntimeLimitResetRefusal.claudeRequestError(): ComputerRuntimeClaudeLimitResetText {
    val status = status
    return when {
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
 * What an unsupported read leaves the dialog saying (web `ClaudeLimitResetsDialog.read`). The
 * caller handles `consent_required` without consent first (read again with it, or say who can).
 */
internal fun RuntimeLimitResetsReadResult.Unsupported.claudeReadFailure(canUse: Boolean): ComputerRuntimeClaudeLimitResetText =
    when {
        claudeLimitResetsNotSupportedHere(reason) -> claudeNotSupportedText(reason)
        reason == "onboarding_required" -> ComputerRuntimeClaudeLimitResetText.OnboardingRequired
        reason == "not_attempted" ->
            if (canUse) ComputerRuntimeClaudeLimitResetText.ReadNotAttempted else ComputerRuntimeClaudeLimitResetText.ReadFailed(viewOnly = true)
        reason == "not_installed" -> ComputerRuntimeClaudeLimitResetText.NotInstalled
        else -> ComputerRuntimeClaudeLimitResetText.Unsupported
    }

/**
 * Web `ClaudeLimitResetReadStep`: what comes after a read, as Claude Code's bare `/limit-reset`
 * goes on.
 */
internal sealed interface ComputerRuntimeClaudeLimitResetReadStep {
    /** A line on the card; [usageChanged]: the read itself used the older program's 5-hour reset. */
    data class Note(val text: ComputerRuntimeClaudeLimitResetText, val usageChanged: Boolean) : ComputerRuntimeClaudeLimitResetReadStep

    /** The one usable kind: straight to its confirm, no list. */
    data class Confirm(val status: RuntimeLimitResetKindStatus.Available) : ComputerRuntimeClaudeLimitResetReadStep

    /** Several usable kinds: "Which reset do you want to use?". */
    data class Choose(val usable: List<RuntimeLimitResetKindStatus.Available>) : ComputerRuntimeClaudeLimitResetReadStep
}

/**
 * Web `claudeLimitResetReadStep`. On Claude's older program the read was the command: it ends with
 * a line (`fiveHourSpent`: it used the 5-hour reset, so the usage is read again; nothing is ever
 * redeemed after it). On the newer one a kind is usable when Claude Code showed it available and,
 * at the 5-hour limit, only the 5-hour one (the Computer types no other command there).
 */
internal fun claudeLimitResetReadStep(
    result: RuntimeLimitResetsReadResult.Read,
    atFiveHourLimit: Boolean,
): ComputerRuntimeClaudeLimitResetReadStep {
    if (result.fiveHourSpent) return ComputerRuntimeClaudeLimitResetReadStep.Note(ComputerRuntimeClaudeLimitResetText.LegacyReset, usageChanged = true)
    if (result.legacy) {
        val fiveHour = result.resets.firstOrNull { it.kind == RuntimeLimitResetKind.FiveHour }
        val text = if (fiveHour is RuntimeLimitResetKindStatus.None) ComputerRuntimeClaudeLimitResetText.LegacyUsed else ComputerRuntimeClaudeLimitResetText.LegacyKept
        return ComputerRuntimeClaudeLimitResetReadStep.Note(text, usageChanged = false)
    }
    val available = result.resets.filterIsInstance<RuntimeLimitResetKindStatus.Available>()
    val usable = available.filter { !atFiveHourLimit || it.kind == RuntimeLimitResetKind.FiveHour }
    return when {
        usable.size == 1 -> ComputerRuntimeClaudeLimitResetReadStep.Confirm(usable.single())
        usable.size > 1 -> ComputerRuntimeClaudeLimitResetReadStep.Choose(usable)
        available.isNotEmpty() -> ComputerRuntimeClaudeLimitResetReadStep.Note(ComputerRuntimeClaudeLimitResetText.NoneAtLimit, usageChanged = false)
        result.resets.any { it is RuntimeLimitResetKindStatus.Unknown } ->
            ComputerRuntimeClaudeLimitResetReadStep.Note(ComputerRuntimeClaudeLimitResetText.ReadFailed(viewOnly = false), usageChanged = false)
        else -> ComputerRuntimeClaudeLimitResetReadStep.Note(ComputerRuntimeClaudeLimitResetText.NoneToUse, usageChanged = false)
    }
}

/** The read dialog's phase (web `ReadState`). */
internal sealed interface ComputerRuntimeClaudeLimitResetsPhase {
    data class Reading(val consent: Boolean) : ComputerRuntimeClaudeLimitResetsPhase

    /** The read-only list of every kind, for a viewer who may not use them. */
    data class Read(val result: RuntimeLimitResetsReadResult.Read) : ComputerRuntimeClaudeLimitResetsPhase

    /** "Which reset do you want to use?": the usable kinds, nothing chosen for the person. */
    data class Choose(val usable: List<RuntimeLimitResetKindStatus.Available>) : ComputerRuntimeClaudeLimitResetsPhase

    /** [traceId]: the failed request's `X-Trace-Id`, when a server answer failed it. */
    data class Failed(
        val message: ComputerRuntimeClaudeLimitResetText,
        val traceId: String? = null,
        val serverMessage: String? = null,
    ) : ComputerRuntimeClaudeLimitResetsPhase
}

/** The open "Use a limit reset" read (and the choice, if any). */
internal data class ComputerRuntimeClaudeLimitResetsListState(
    val phase: ComputerRuntimeClaudeLimitResetsPhase,
    /** The viewer may use a reset and consent to the read; false: a read-only list. */
    val canUse: Boolean,
    val atFiveHourLimit: Boolean,
    val selected: RuntimeLimitResetKind? = null,
) {
    /** The chosen kind, once one of the usable kinds is chosen. */
    val chosen: RuntimeLimitResetKindStatus.Available?
        get() {
            val usable = (phase as? ComputerRuntimeClaudeLimitResetsPhase.Choose)?.usable ?: return null
            return usable.firstOrNull { it.kind == selected }
        }
}

/** The open "Use a {kind}?" confirm; its key comes from the store, one per attempt on that kind. */
internal data class ComputerRuntimeClaudeLimitResetConfirmState(
    val kind: RuntimeLimitResetKind,
    val idempotencyKey: RuntimeLimitResetIdempotencyKey,
    val account: String?,
    val atFiveHourLimit: Boolean,
    /** What the read said of this kind ("1 left · use by …"); null when it said nothing more. */
    val status: RuntimeLimitResetKindStatus.Available? = null,
    val submitting: Boolean = false,
    /** A request was sent: closing it now is no longer "No, keep it". */
    val sent: Boolean = false,
    val error: ComputerRuntimeClaudeLimitResetText? = null,
    /** The failed request's `X-Trace-Id`, shown under [error]. */
    val traceId: String? = null,
    /** Scrubbed Tier 3 server refusal sentence for definite 4xx refusals without a Tier 2 code. */
    val serverMessage: String? = null,
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
        ComputerRuntimeClaudeLimitResetText.OnboardingRequired -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_onboarding_required)
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
        ComputerRuntimeClaudeLimitResetText.ReadNotAttempted -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_read_not_attempted)
        ComputerRuntimeClaudeLimitResetText.Forbidden -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_forbidden)
        ComputerRuntimeClaudeLimitResetText.ComputerOffline -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_computer_offline)
        ComputerRuntimeClaudeLimitResetText.UpgradeRequired -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_upgrade_required)
        ComputerRuntimeClaudeLimitResetText.NotAttached -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_not_attached)
        ComputerRuntimeClaudeLimitResetText.RateLimited -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_rate_limited)
        ComputerRuntimeClaudeLimitResetText.Disabled -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_disabled)
        ComputerRuntimeClaudeLimitResetText.Refused -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_refused)
        ComputerRuntimeClaudeLimitResetText.RequestUnknown -> stringResource(Res.string.settings_computers_runtime_usage_limit_reset_unknown)
        ComputerRuntimeClaudeLimitResetText.LegacyReset -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_legacy_reset)
        ComputerRuntimeClaudeLimitResetText.LegacyUsed -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_legacy_used)
        ComputerRuntimeClaudeLimitResetText.LegacyKept -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_legacy_kept)
        ComputerRuntimeClaudeLimitResetText.NoneToUse -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_none_to_use)
        ComputerRuntimeClaudeLimitResetText.NoneAtLimit -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_none_at_limit)
        is ComputerRuntimeClaudeLimitResetText.Kept -> {
            val kept = stringResource(Res.string.settings_computers_runtime_usage_claude_reset_kept, kind.claudeName())
            val useBy = expiresText?.let { stringResource(Res.string.settings_computers_runtime_usage_claude_reset_state_use_by, it) }
            listOfNotNull(kept, useBy).joinToString(" · ")
        }
    }

/**
 * Web `claudeKindMeta`: what Claude Code showed for a kind, its own way ("2 left · use by …" under
 * a confirm, "2 left · until …" on a row of the choice). Only what the read said.
 */
@Composable
private fun RuntimeLimitResetKindStatus.meta(until: Boolean = false): String =
    when (this) {
        is RuntimeLimitResetKindStatus.Available -> {
            val left = resetsLeft
            val count =
                when {
                    left == null -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_state_available)
                    left == 1 -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_state_left_one, left)
                    else -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_state_left_other, left)
                }
            val deadline =
                expiresText?.let {
                    stringResource(
                        if (until) {
                            Res.string.settings_computers_runtime_usage_claude_reset_state_until
                        } else {
                            Res.string.settings_computers_runtime_usage_claude_reset_state_use_by
                        },
                        it,
                    )
                }
            listOfNotNull(count, deadline).joinToString(" · ")
        }
        is RuntimeLimitResetKindStatus.None -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_state_none)
        is RuntimeLimitResetKindStatus.NotChecked -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_state_not_checked)
        is RuntimeLimitResetKindStatus.NotOffered -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_state_not_offered)
        is RuntimeLimitResetKindStatus.Unknown -> stringResource(Res.string.settings_computers_runtime_usage_claude_reset_state_unknown)
    }

/**
 * The card's one Claude entry (web `claude-limit-reset-action` / `claude-limit-resets-view-action`):
 * "Use a limit reset", primary at the 5-hour limit, or "View available resets" for a viewer who
 * may not use them.
 */
@Composable
internal fun ComputerRuntimeClaudeLimitResetButton(
    offer: ComputerRuntimeClaudeLimitResetOffer,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    size: RaftButtonSize = RaftButtonSize.Md,
) {
    if (offer.canUse) {
        RaftButton(
            text = stringResource(Res.string.settings_computers_runtime_usage_claude_reset_use_action),
            onClick = onOpen,
            modifier = modifier.testTag(COMPUTER_RUNTIME_CLAUDE_LIMIT_RESET_ACTION_TAG),
            variant = if (offer.primary) RaftButtonVariant.Primary else RaftButtonVariant.Default,
            size = size,
            leadingIcon = { RaftComputersIcon(RaftLucideIconName.TimerReset) },
        )
    } else {
        RaftButton(
            text = stringResource(Res.string.settings_computers_runtime_usage_claude_reset_view_action),
            onClick = onOpen,
            modifier = modifier.testTag(COMPUTER_RUNTIME_CLAUDE_LIMIT_RESETS_VIEW_TAG),
            variant = RaftButtonVariant.Default,
            size = size,
            leadingIcon = { RaftComputersIcon(RaftLucideIconName.ListChecks) },
        )
    }
}

/**
 * Web `ClaudeLimitResetsDialog`: "This takes a moment" while it reads, then "Which reset do you
 * want to use?" when several kinds can be used ("Use a weekly reset", "1 left · until …"), or why
 * nothing could be read. A viewer who may not use resets gets display-only rows and Close.
 */
@Composable
internal fun ComputerRuntimeClaudeLimitResetsDialog(
    state: ComputerRuntimeClaudeLimitResetsListState,
    onSelect: (RuntimeLimitResetKind) -> Unit,
    onUse: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val phase = state.phase
    val listed = (phase as? ComputerRuntimeClaudeLimitResetsPhase.Read)?.result
    val rows = if (listed != null && !listed.legacy) listed.resets else emptyList()
    val usable = (phase as? ComputerRuntimeClaudeLimitResetsPhase.Choose)?.usable.orEmpty()
    val listLabel = stringResource(Res.string.settings_computers_runtime_usage_claude_reset_list_label)
    RaftDialog(
        onDismissRequest = onDismiss,
        title =
            stringResource(
                if (usable.isNotEmpty()) {
                    Res.string.settings_computers_runtime_usage_claude_reset_choose_title
                } else {
                    Res.string.settings_computers_runtime_usage_claude_reset_dialog_title
                }
            ),
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
                    enabled = state.chosen != null,
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
                Row(
                    Modifier.testTag(COMPUTER_RUNTIME_CLAUDE_LIMIT_RESETS_READING_TAG),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftSpinner(size = RaftSpinnerSize.Xs)
                    RaftText(
                        stringResource(Res.string.settings_computers_runtime_usage_claude_reset_reading),
                        style = typography.subhead,
                        color = colors.foreground,
                    )
                }
            }
            if (phase is ComputerRuntimeClaudeLimitResetsPhase.Failed) {
                RaftBanner(description = phase.serverMessage ?: phase.message.text(), status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
                RaftTraceIdNote(phase.traceId)
            }
            if (listed?.legacy == true) {
                RaftText(
                    stringResource(Res.string.settings_computers_runtime_usage_claude_reset_legacy_view_only),
                    style = typography.subhead,
                    color = colors.foreground,
                )
            }
            if (rows.isNotEmpty()) {
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
            if (usable.isNotEmpty()) {
                val items =
                    usable.map { status ->
                        RaftChoiceListItem(
                            value = status.kind,
                            label = stringResource(Res.string.settings_computers_runtime_usage_claude_reset_use_kind, status.kind.claudeName()),
                            descriptions = listOf(AnnotatedString(status.meta(until = true))),
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
            }
        }
    }
}

/**
 * Web `ClaudeLimitResetConfirmDialog`, in Claude Code's words: "Use a weekly reset?", the account
 * named, that it refills its limits now and cannot be undone, what the read said of the kind;
 * "Yes, use my weekly reset" / "No, keep it".
 */
@Composable
internal fun ComputerRuntimeClaudeLimitResetConfirmDialog(
    state: ComputerRuntimeClaudeLimitResetConfirmState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val name = state.kind.claudeName()
    val account = state.account ?: stringResource(Res.string.settings_computers_runtime_usage_limit_reset_account_fallback)
    val body = stringResource(Res.string.settings_computers_runtime_usage_claude_reset_confirm_body, account)
    val detail = state.status?.meta()
    val dismiss = { if (!state.submitting) onDismiss() }
    RaftDialog(
        onDismissRequest = dismiss,
        title = stringResource(Res.string.settings_computers_runtime_usage_claude_reset_confirm_title, name),
        size = RaftDialogSize.Sm,
        dismissOnClickOutside = !state.submitting,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = stringResource(Res.string.settings_computers_runtime_usage_claude_reset_keep),
                onClick = dismiss,
                modifier = Modifier.testTag(COMPUTER_RUNTIME_CLAUDE_LIMIT_RESET_KEEP_TAG),
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
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RaftText(
                claudeLimitResetConfirmBody(body, account),
                style = RaftTheme.typography.subhead,
                color = RaftTheme.colors.foregroundMuted,
            )
            if (detail != null) {
                RaftText(detail, style = RaftTheme.typography.footnote, color = RaftTheme.colors.foreground)
            }
            state.error?.let { error ->
                RaftBanner(description = state.serverMessage ?: error.text(), status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
                RaftTraceIdNote(state.traceId)
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

internal const val COMPUTER_RUNTIME_CLAUDE_LIMIT_RESET_ACTION_TAG = "claude-limit-reset-action"
internal const val COMPUTER_RUNTIME_CLAUDE_LIMIT_RESETS_VIEW_TAG = "claude-limit-resets-view-action"
internal const val COMPUTER_RUNTIME_CLAUDE_LIMIT_RESETS_DIALOG_TAG = "claude-limit-resets-dialog"
internal const val COMPUTER_RUNTIME_CLAUDE_LIMIT_RESETS_READING_TAG = "claude-limit-resets-reading"
internal const val COMPUTER_RUNTIME_CLAUDE_LIMIT_RESETS_USE_TAG = "claude-limit-resets-use"
internal const val COMPUTER_RUNTIME_CLAUDE_LIMIT_RESET_CONFIRM_TAG = "claude-limit-reset-confirm"
internal const val COMPUTER_RUNTIME_CLAUDE_LIMIT_RESET_KEEP_TAG = "claude-limit-reset-keep"
