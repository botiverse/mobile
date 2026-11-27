package build.raft.app

import build.raft.app.resources.*
import build.raft.app.settings.observe
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import build.raft.app.data.AddComputerCodeError
import build.raft.app.data.AddComputerCodeOutcome
import build.raft.app.data.AddComputerCodeProblem
import build.raft.app.data.AddComputerCodeState
import build.raft.app.data.AddComputerCodeStep
import build.raft.app.data.AddComputerCodeSubmitting
import build.raft.app.data.DeviceLoginGrantKind
import build.raft.app.data.DeviceLoginRequest
import build.raft.app.data.DeviceLoginServer
import build.raft.app.data.RaftTimeFormatRuntime
import build.raft.app.data.SharedSettingsIntent
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.appendRaftTextLink
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/*
 * Add Computer's "The terminal shows a code? Enter it here." (web botiverse/slock 329e1700f7,
 * #15612: features/computer/AddComputerCodeEntry.tsx, AddComputerCodeReview.tsx and
 * components/auth/DeviceLoginRequestDetails.tsx). State and calls: data/AddComputerCode.kt.
 *
 * Where the phone differs: the web's step-up is drawn in the window (StepUpBeforeApproval); here it
 * is the shared "Verify it's you" dialog over the sheet, as for registering the computer (#4962).
 * The web hides its footer in a phone sheet, so Deny and Enter a different code would not show
 * there; here they sit under the facts, and Add to server is the sheet's commit in its head.
 */

internal const val AddComputerCodeToggleTag = "add-machine-code-toggle"
internal const val AddComputerCodeEntryTag = "add-machine-code-entry"
internal const val AddComputerCodeApprovedTag = "add-machine-code-approved"
internal const val AddComputerCodeDeniedTag = "add-machine-code-denied"
internal const val AddComputerCodeConfirmTag = "add-machine-code-confirm"
internal const val AddComputerCodeElsewhereTag = "add-machine-code-elsewhere"

/**
 * Under the command: the field, closed until asked for (most people never see a code), then the
 * approved or denied line while the sheet keeps waiting for the computer (web AddComputerCodeEntry).
 */
@Composable
internal fun AddComputerCodeEntry(code: AddComputerCodeState, onIntent: (SharedSettingsIntent) -> Unit) {
    when (val step = code.step) {
        is AddComputerCodeStep.Approved -> AddComputerCodeApproved(step.outcome)
        is AddComputerCodeStep.Denied ->
            Column(Modifier.fillMaxWidth().testTag(AddComputerCodeDeniedTag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RaftBanner(
                    description = stringResource(Res.string.settings_computers_code_denied, step.serverName),
                    status = RaftBannerStatus.Info,
                    showIcon = false,
                )
                RaftTextLink(
                    text = stringResource(Res.string.settings_computers_code_different_code),
                    onClick = { onIntent(SharedSettingsIntent.ResetAddComputerCode) },
                    style = RaftTheme.typography.footnote,
                )
            }
        else -> AddComputerCodeField(code, onIntent)
    }
}

@Composable
private fun AddComputerCodeField(code: AddComputerCodeState, onIntent: (SharedSettingsIntent) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RaftTextLink(
            text = stringResource(Res.string.settings_computers_code_toggle),
            onClick = { onIntent(SharedSettingsIntent.ToggleAddComputerCode) },
            modifier = Modifier.testTag(AddComputerCodeToggleTag),
            style = RaftTheme.typography.footnote,
        )
        if (!code.open) return@Column
        // A section that submits on its own: its button under its field, at the start (DS › Forms).
        Column(Modifier.fillMaxWidth().testTag(AddComputerCodeEntryTag), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Caller-local editor, seeded once per mount (the field mounts empty after Enter a
            // different code); the store keeps the String it checks.
            var value by remember { mutableStateOf(TextFieldValue(code.code, selection = TextRange(code.code.length))) }
            val submit = { if (code.canSubmit) onIntent(SharedSettingsIntent.SubmitAddComputerCode) }
            RaftField(
                label = stringResource(Res.string.settings_computers_code_label),
                description = stringResource(Res.string.settings_computers_code_hint),
            ) {
                RaftTextField(
                    value = value,
                    onValueChange = {
                        val typed = it.text != value.text
                        value = it
                        if (typed) onIntent(SharedSettingsIntent.SetAddComputerCode(it.text))
                    },
                    placeholder = stringResource(Res.string.settings_computers_code_placeholder),
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Go,
                        ),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                )
            }
            code.error?.let { AddComputerCodeErrorBanner(it, onIntent) }
            RaftButton(
                text =
                    stringResource(
                        if (code.submitting == AddComputerCodeSubmitting.Describe) Res.string.settings_computers_code_checking else Res.string.common_continue
                    ),
                onClick = submit,
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Lg,
                enabled = code.canSubmit,
            )
        }
    }
}

@Composable
private fun AddComputerCodeApproved(outcome: AddComputerCodeOutcome) {
    val machine = outcome.machineName
    val server = outcome.serverName
    RaftBanner(
        description =
            when {
                outcome.migrate && machine != null -> stringResource(Res.string.settings_computers_code_migrate_approved, machine, server)
                outcome.migrate -> stringResource(Res.string.settings_computers_code_migrate_approved_unnamed, server)
                machine != null -> stringResource(Res.string.settings_computers_code_attach_approved, machine, server)
                else -> stringResource(Res.string.settings_computers_code_attach_approved_unnamed, server)
            },
        status = RaftBannerStatus.Success,
        title =
            stringResource(
                if (outcome.migrate) Res.string.settings_computers_code_migrate_approved_title else Res.string.settings_computers_code_attach_approved_title
            ),
        modifier = Modifier.testTag(AddComputerCodeApprovedTag),
    )
}

/** The sheet's head while the confirm screen takes it (web `codeReviewTitle`, `DialogCommit`). */
internal class AddComputerCodeReviewHead(val title: String, val commitLabel: String?)

/**
 * The confirm title says what the code asks for, by the name it gave (web `deviceLoginConfirmTitle`);
 * the commit is Add to server (Move it over), or none for a code the sheet doesn't approve.
 */
@Composable
internal fun addComputerCodeReviewHead(code: AddComputerCodeState, review: AddComputerCodeStep.Confirm): AddComputerCodeReviewHead {
    if (review.target == null) return AddComputerCodeReviewHead(stringResource(Res.string.settings_computers_code_approve_title), commitLabel = null)
    val grant = review.request.grant
    val migrate = grant?.kind == DeviceLoginGrantKind.ComputerMigrate
    val name = grant?.machineName
    val approving = code.submitting == AddComputerCodeSubmitting.Approve
    return AddComputerCodeReviewHead(
        title =
            when {
                migrate && name != null -> stringResource(Res.string.settings_computers_code_migrate_title, name)
                migrate -> stringResource(Res.string.settings_computers_code_migrate_title_unnamed)
                name != null -> stringResource(Res.string.settings_computers_code_attach_title, name)
                else -> stringResource(Res.string.settings_computers_code_attach_title_unnamed)
            },
        commitLabel =
            stringResource(
                when {
                    migrate && approving -> Res.string.settings_computers_code_moving_to_server
                    migrate -> Res.string.settings_computers_code_move_to_server
                    approving -> Res.string.settings_computers_code_adding_to_server
                    else -> Res.string.settings_computers_code_add_to_server
                }
            ),
    )
}

/**
 * A typed code's confirm screen in the command's place (web AddComputerCodeReview): the device
 * login page's facts, for the sheet's server and no other, so there is nothing to pick: the
 * Server fact says which, and Add to server is the consent. A code for anything else (a sign-in,
 * an agent key, a server the person can't add computers to) is not approved here: it says so and
 * offers the device login page, where the code is typed again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddComputerCodeReview(code: AddComputerCodeState, review: AddComputerCodeStep.Confirm, onIntent: (SharedSettingsIntent) -> Unit) {
    val busy = code.submitting != null
    val target = review.target
    if (target == null) {
        val kind = review.request.grant?.kind
        val forComputer = kind == DeviceLoginGrantKind.ComputerAttach || kind == DeviceLoginGrantKind.ComputerMigrate
        val notice = stringResource(if (forComputer) Res.string.settings_computers_code_other_server else Res.string.settings_computers_code_not_for_computer)
        val link = stringResource(Res.string.settings_computers_code_open_device_page)
        RaftBanner(
            description =
                buildAnnotatedString {
                    append(notice)
                    append(" ")
                    appendRaftTextLink(link, onClick = { onIntent(SharedSettingsIntent.OpenAddComputerDeviceLoginPage) })
                },
            status = RaftBannerStatus.Info,
            modifier = Modifier.testTag(AddComputerCodeElsewhereTag),
        )
    } else {
        AddComputerCodeRequestDetails(review.request, target)
        code.error?.let { AddComputerCodeErrorBanner(it, onIntent) }
    }
    // The web's footer, in its order: Enter a different code, then Deny (only for the sheet's server).
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RaftButton(
            text = stringResource(Res.string.settings_computers_code_different_code),
            onClick = { onIntent(SharedSettingsIntent.ResetAddComputerCode) },
            variant = RaftButtonVariant.Ghost,
            size = RaftButtonSize.Lg,
            enabled = !busy,
        )
        if (target != null) {
            RaftButton(
                text =
                    stringResource(
                        if (code.submitting == AddComputerCodeSubmitting.Deny) Res.string.settings_computers_code_denying else Res.string.settings_computers_code_deny
                    ),
                onClick = { onIntent(SharedSettingsIntent.DenyAddComputerCode) },
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Lg,
                enabled = !busy,
            )
        }
    }
}

/**
 * The confirm screen's body (web DeviceLoginRequestDetails for a computer): the warning against
 * approving someone else's login, then the code, the computer by the name it gave (quoted, plain
 * text: whoever started the login chose it) with its hostname and OS, when, from where, and the
 * server it is for.
 */
@Composable
private fun AddComputerCodeRequestDetails(request: DeviceLoginRequest, target: DeviceLoginServer) {
    val grant = request.grant
    RaftBanner(
        description =
            stringResource(
                if (grant?.kind == DeviceLoginGrantKind.ComputerMigrate) Res.string.settings_computers_code_migrate_warning else Res.string.settings_computers_code_attach_warning
            ),
        status = RaftBannerStatus.Warning,
        modifier = Modifier.testTag(AddComputerCodeConfirmTag),
    )
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val machine = grant?.machineName
    val machineDetail = listOfNotNull(grant?.hostname, grant?.os).joinToString(" · ")
    val fromUnknown = stringResource(Res.string.settings_computers_code_from_unknown)
    val from = listOfNotNull(request.requesterIp, request.requesterLocation).joinToString(" · ").ifEmpty { fromUnknown }
    RaftDescriptionList(Modifier.fillMaxWidth(), direction = RaftDescriptionListDirection.Vertical) {
        Item(term = stringResource(Res.string.settings_computers_code_fact_code), value = request.userCode, mono = true)
        Item(term = stringResource(Res.string.settings_computers_code_fact_computer)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RaftText(
                    if (machine != null) stringResource(Res.string.settings_computers_code_named, machine) else stringResource(Res.string.settings_computers_code_not_given),
                    style = typography.callout,
                    color = colors.foreground,
                )
                if (machineDetail.isNotEmpty()) RaftText(machineDetail, style = typography.caption, color = colors.foreground)
                RaftText(stringResource(Res.string.settings_computers_code_self_reported), style = typography.caption, color = colors.foregroundHint)
            }
        }
        Item(term = stringResource(Res.string.settings_computers_code_fact_started), value = addComputerCodeStartedLabel(request.requestedAt))
        Item(term = stringResource(Res.string.settings_computers_code_fact_from), value = from)
        Item(term = stringResource(Res.string.settings_computers_code_fact_server)) {
            RaftText(target.name, style = typography.callout, color = colors.foreground)
            RaftText("/${target.slug}", style = typography.monoMeta, color = colors.foregroundMuted)
        }
    }
}

/**
 * "14:05 (2 minutes ago)" on the page's live clock, which moves while the screen waits (web
 * `startedValue`); the time alone without one; null when the time can't be read.
 */
@Composable
private fun addComputerCodeStartedLabel(requestedAt: String): String? {
    val context = currentRaftTimeContext(null)
    val time =
        if (context != null) {
            raftAbsoluteTimestampLabel(requestedAt, context)
                ?: raftDeviceZoneAbsoluteLabel(requestedAt, timeFormat = context.timeFormat)
        } else {
            val fallbackTimeFormat = RaftTimeFormatRuntime.observe()
            raftDeviceZoneAbsoluteLabel(requestedAt, timeFormat = fallbackTimeFormat)
        } ?: return null
    if (context == null) return time
    val ago = raftRelativeTimestampLabel(requestedAt, context, clampFuture = true)
    return stringResource(Res.string.settings_computers_code_started_value, time, ago)
}

/** Why the code can't go on, with the device login page where it can still help, and the trace id. */
@Composable
private fun AddComputerCodeErrorBanner(error: AddComputerCodeError, onIntent: (SharedSettingsIntent) -> Unit) {
    val message = addComputerCodeErrorText(error)
    val link = stringResource(Res.string.settings_computers_code_open_device_page)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftBanner(
            description =
                buildAnnotatedString {
                    append(message)
                    if (error.offersDevicePage) {
                        append(" ")
                        appendRaftTextLink(link, onClick = { onIntent(SharedSettingsIntent.OpenAddComputerDeviceLoginPage) })
                    }
                },
            status = RaftBannerStatus.Warning,
            showIcon = false,
        )
        RaftTraceIdNote(error.traceId)
    }
}

/** The device login page's words for [error] (web `showFailure`, `resolve`); the server's own for anything else. */
@Composable
private fun addComputerCodeErrorText(error: AddComputerCodeError): String =
    when (error.problem) {
        AddComputerCodeProblem.CodeInvalid -> stringResource(Res.string.settings_computers_code_invalid)
        AddComputerCodeProblem.CodeExpired -> stringResource(Res.string.settings_computers_code_expired)
        AddComputerCodeProblem.CodeAlreadyUsed -> stringResource(Res.string.settings_computers_code_already_used)
        AddComputerCodeProblem.KindUnsupported -> stringResource(Res.string.settings_computers_code_kind_unsupported)
        AddComputerCodeProblem.ServerSelectionRequired -> stringResource(Res.string.settings_computers_code_server_selection_required)
        AddComputerCodeProblem.ServerSelectionNotAllowed -> stringResource(Res.string.settings_computers_code_server_selection_not_allowed)
        AddComputerCodeProblem.StepUpRequired -> stringResource(Res.string.settings_computers_code_step_up_notice)
        AddComputerCodeProblem.DescribeFailed -> error.serverMessage ?: stringResource(Res.string.settings_computers_code_describe_failed)
        AddComputerCodeProblem.ApproveFailed -> error.serverMessage ?: stringResource(Res.string.settings_computers_code_approve_failed)
        AddComputerCodeProblem.DenyFailed -> error.serverMessage ?: stringResource(Res.string.settings_computers_code_deny_failed)
    }
