package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.data.ComputerHandoverRepository
import build.raft.app.data.ComputerHandoverState
import build.raft.app.data.DataResult
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftRadioGroup
import build.raft.designsystem.components.RaftRadioRow
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftToastIntent
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/*
 * Hand over a Computer (botiverse/slock #16435 / #16445 / #16452, web ComputerHandoverButton,
 * ComputerHandoverNotice, HandOverComputerDialog, useComputerHandover):
 * - On a managed Computer's Info section, beside who it is attached by ("Attached by" / 授权人),
 *   offers "Hand over" when `state.canDesignate && !state.upgradeRequired && attacher != null`.
 * - The dialog offers only owners and admins (the server's fresh-attach `registerMachines` rule)
 *   other than the current attacher; an admin may designate themselves.
 * - Under the Info details, shows either a pending handover banner (with Cancel handover, or
 *   Decline for the designee, when `canCancel`) or an upgrade-first banner naming
 *   `minimumComputerVersion` when `canDesignate && upgradeRequired`.
 */

internal const val RAFT_COMPUTER_HANDOVER_BUTTON_TAG = "computer-hand-over"
internal const val RAFT_COMPUTER_HANDOVER_PENDING_TAG = "computer-handover-pending"
internal const val RAFT_COMPUTER_HANDOVER_CANCEL_TAG = "computer-handover-cancel"
internal const val RAFT_COMPUTER_HANDOVER_UPGRADE_FIRST_TAG = "computer-handover-upgrade-first"
internal const val RAFT_HANDOVER_DIALOG_TAG = "hand-over-computer-dialog"
internal const val RAFT_HANDOVER_COMPUTER_NAME_TAG = "hand-over-computer-name"
internal const val RAFT_HANDOVER_SEARCH_TAG = "hand-over-person"
internal const val RAFT_HANDOVER_HOW_TAG = "hand-over-how"
internal const val RAFT_HANDOVER_REGISTRANT_TAG = "hand-over-registrant"
internal const val RAFT_HANDOVER_SIGN_INS_TAG = "hand-over-sign-ins"
internal const val RAFT_HANDOVER_FAILED_TAG = "hand-over-failed"
internal const val RAFT_HANDOVER_CONFIRM_TAG = "hand-over-confirm"

internal fun raftHandOverPersonTag(userId: String): String = "hand-over-person-$userId"

/**
 * Web `HandOverComputerDialog` `people`: active human owners and admins of the server (the
 * fresh-attach `registerMachines` rule; never Guests or plain members) other than [attacherUserId].
 * An admin may pick themselves (marked [RaftComputerOwnerCandidate.isYou]).
 */
internal fun raftComputerHandoverCandidates(
    members: List<MemberProfile>,
    attacherUserId: String?,
    currentUserId: String?,
): List<RaftComputerOwnerCandidate> {
    val attacher = attacherUserId?.trim()?.takeIf { it.isNotBlank() }
    val me = currentUserId?.trim()?.takeIf { it.isNotBlank() }
    val seen = HashSet<String>()
    return members.mapNotNull { member ->
        val id = member.id.value.trim()
        if (member.kind != MemberKind.Human || id.isBlank() || id == attacher) return@mapNotNull null
        if (member.membershipStatus != null && member.membershipStatus != "active") return@mapNotNull null
        val role = member.serverRole?.trim()?.lowercase()
        if (role != "owner" && role != "admin") return@mapNotNull null
        if (!seen.add(id)) return@mapNotNull null
        RaftComputerOwnerCandidate(
            id = id,
            name = member.displayName.trim().ifBlank { member.handle },
            isYou = id == me,
        )
    }
}

/**
 * Web `useComputerHandover`: reads `GET …/machines/:machineId/handover` for managed Computers,
 * publishes [state] (null until read, or when the read fails / the machine is not a live Computer),
 * and exposes [designate] and [cancel] (re-reading state when either is refused by the server).
 */
@Stable
internal class RaftComputerHandoverController(
    private val repository: ComputerHandoverRepository,
) {
    var state by mutableStateOf<ComputerHandoverState?>(null)
        private set

    private var readSeq: Long = 0L

    suspend fun refresh(serverId: ServerId?, machineId: String, enabled: Boolean) {
        val seq = ++readSeq
        if (!enabled || serverId == null || machineId.isBlank()) {
            state = null
            return
        }
        val result =
            try {
                repository.getHandover(serverId, machineId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }
        if (seq != readSeq) return
        state = (result as? DataResult.Content)?.data
    }

    suspend fun designate(
        serverId: ServerId?,
        machineId: String,
        toUserId: String,
    ): DataResult<ComputerHandoverState> {
        if (serverId == null || machineId.isBlank()) {
            return DataResult.Error(build.raft.app.data.ScreenError("computer_handover_unavailable", retryable = false))
        }
        val result =
            try {
                repository.designateHandover(serverId, machineId, toUserId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                DataResult.Error(build.raft.app.data.ScreenError("computer_handover_network", retryable = true))
            }
        if (result is DataResult.Content) {
            ++readSeq
            state = result.data
        } else {
            refresh(serverId, machineId, enabled = true)
        }
        return result
    }

    suspend fun cancel(
        serverId: ServerId?,
        machineId: String,
    ): DataResult<ComputerHandoverState> {
        if (serverId == null || machineId.isBlank()) {
            return DataResult.Error(build.raft.app.data.ScreenError("computer_handover_unavailable", retryable = false))
        }
        val result =
            try {
                repository.cancelHandover(serverId, machineId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                DataResult.Error(build.raft.app.data.ScreenError("computer_handover_network", retryable = true))
            }
        if (result is DataResult.Content) {
            ++readSeq
            state = result.data
        } else {
            refresh(serverId, machineId, enabled = true)
        }
        return result
    }
}

@Composable
internal fun rememberComputerHandoverEffect(
    controller: RaftComputerHandoverController,
    serverId: ServerId?,
    machineId: String,
    enabled: Boolean,
    refreshTick: Int,
) {
    LaunchedEffect(controller, serverId, machineId, enabled, refreshTick) {
        controller.refresh(serverId, machineId, enabled)
    }
}

/**
 * Formats a pending handover expiry timestamp in the viewer's localized date and clock
 * (web `intl.formatDate(new Date(pending.expiresAt), { dateStyle: "medium", timeStyle: "short" })`).
 */
@Composable
internal fun raftComputerHandoverExpiresLabel(expiresAt: String): String {
    val trimmed = expiresAt.trim()
    if (trimmed.isEmpty()) return ""
    val timeContext = currentRaftEffectiveDateTimeContext()
    val epochMillis = parseRaftTimestampEpochMillisOrNull(trimmed, timeContext.localOffsetMinutes) ?: return trimmed
    val date = trimmed.membersJoinedDateOrNull(timeContext)?.let { membersLocalizedDate(it) } ?: return trimmed
    val clock = timeContext.formatClock(epochMillis)
    return stringResource(Res.string.settings_computers_handover_expires_at, date, clock)
}

/**
 * Web `ComputerHandoverButton`: the "Hand over" button beside who the Computer is attached by,
 * opening [RaftHandOverComputerDialog]. Hidden when the viewer cannot designate, when the Computer
 * needs an upgrade first (the notice below takes its place), or when there is no attacher.
 */
@Composable
internal fun RaftComputerHandoverButton(
    computerName: String,
    serverSlug: String,
    attacher: RaftComputerCreatorModel?,
    registrantMoves: Boolean,
    currentUserId: String?,
    members: List<MemberProfile>,
    handoverState: ComputerHandoverState?,
    onDesignate: (suspend (String) -> DataResult<ComputerHandoverState>)?,
) {
    if (handoverState?.canDesignate != true || handoverState.upgradeRequired || attacher == null || onDesignate == null) {
        return
    }
    var open by remember(computerName, attacher.id) { mutableStateOf(false) }
    var saving by remember(computerName, attacher.id) { mutableStateOf(false) }
    var failed by remember(computerName, attacher.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    RaftButton(
        text = stringResource(Res.string.settings_computers_handover_button),
        onClick = {
            failed = false
            open = true
        },
        modifier = Modifier.testTag(RAFT_COMPUTER_HANDOVER_BUTTON_TAG),
    )

    if (open) {
        val candidates =
            remember(members, attacher.id, currentUserId) {
                raftComputerHandoverCandidates(members, attacherUserId = attacher.id, currentUserId = currentUserId)
            }
        RaftHandOverComputerDialog(
            computerName = computerName,
            serverSlug = serverSlug,
            attacherUserId = attacher.id,
            attacherName = attacher.displayName,
            registrantMoves = registrantMoves,
            currentUserId = currentUserId,
            candidates = candidates,
            saving = saving,
            failed = failed,
            onHandOver = { toUserId ->
                if (!saving) {
                    saving = true
                    failed = false
                    scope.launch {
                        val result = onDesignate(toUserId)
                        saving = false
                        if (result is DataResult.Content) {
                            open = false
                        } else {
                            failed = true
                        }
                    }
                }
            },
            onDismiss = {
                if (!saving) {
                    open = false
                    failed = false
                }
            },
        )
    }
}

/**
 * Web `HandOverComputerDialog`: "Hand over this Computer", offering owners and admins other than
 * the current attacher, explaining how the designee completes it (`raft-computer handover accept`),
 * noting when machine registration moves too, and warning that the current attacher's Claude and
 * Codex sign-ins on that machine stay in use until signed out there.
 */
@Composable
internal fun RaftHandOverComputerDialog(
    computerName: String,
    serverSlug: String,
    attacherUserId: String,
    attacherName: String,
    registrantMoves: Boolean,
    currentUserId: String?,
    candidates: List<RaftComputerOwnerCandidate>,
    saving: Boolean,
    failed: Boolean,
    onHandOver: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var personId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val matching = remember(candidates, query) { raftComputerOwnerCandidatesMatching(candidates, query) }
    val signInsText =
        if (currentUserId == attacherUserId) {
            stringResource(Res.string.settings_computers_handover_dialog_sign_ins_yours, computerName)
        } else {
            stringResource(Res.string.settings_computers_handover_dialog_sign_ins_theirs, attacherName, computerName)
        }
    RaftDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = stringResource(Res.string.settings_computers_handover_dialog_title),
        modifier = Modifier.testTag(RAFT_HANDOVER_DIALOG_TAG),
        dismissOnClickOutside = !saving,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !saving,
            )
            RaftButton(
                text = stringResource(Res.string.settings_computers_handover_button),
                onClick = {
                    val chosen = personId
                    if (chosen != null && !saving) onHandOver(chosen)
                },
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
                enabled = personId != null,
                loading = saving,
                loadingLabel = stringResource(Res.string.settings_computers_handover_dialog_saving),
                modifier = Modifier.testTag(RAFT_HANDOVER_CONFIRM_TAG),
            )
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftText(
                computerName,
                style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
                color = RaftTheme.colors.foreground,
                modifier = Modifier.testTag(RAFT_HANDOVER_COMPUTER_NAME_TAG),
            )
            RaftText(
                stringResource(Res.string.settings_computers_handover_dialog_person_label),
                style = RaftTheme.typography.subhead,
                color = RaftTheme.colors.foreground,
            )
            RaftTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().testTag(RAFT_HANDOVER_SEARCH_TAG),
                placeholder = stringResource(Res.string.settings_computers_handover_dialog_person_placeholder),
                enabled = !saving,
            )
            if (matching.isEmpty()) {
                RaftText(
                    stringResource(Res.string.settings_computers_handover_dialog_no_match),
                    style = RaftTheme.typography.callout,
                    color = RaftTheme.colors.foregroundHint,
                )
            } else {
                RaftRadioGroup(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                    matching.forEach { candidate ->
                        RaftRadioRow(
                            label =
                                if (candidate.isYou) {
                                    stringResource(Res.string.settings_computers_owner_dialog_you, candidate.name)
                                } else {
                                    candidate.name
                                },
                            selected = personId == candidate.id,
                            onClick = { personId = candidate.id },
                            enabled = !saving,
                            modifier = Modifier.testTag(raftHandOverPersonTag(candidate.id)),
                        )
                    }
                }
            }
            RaftBanner(
                description = AnnotatedString(stringResource(Res.string.settings_computers_handover_dialog_how, serverSlug)),
                status = RaftBannerStatus.Info,
                size = RaftBannerSize.Sm,
                live = false,
                modifier = Modifier.testTag(RAFT_HANDOVER_HOW_TAG),
            )
            if (registrantMoves) {
                RaftBanner(
                    description = AnnotatedString(stringResource(Res.string.settings_computers_handover_dialog_registrant)),
                    status = RaftBannerStatus.Info,
                    size = RaftBannerSize.Sm,
                    live = false,
                    modifier = Modifier.testTag(RAFT_HANDOVER_REGISTRANT_TAG),
                )
            }
            RaftBanner(
                description = AnnotatedString(signInsText),
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm,
                live = false,
                modifier = Modifier.testTag(RAFT_HANDOVER_SIGN_INS_TAG),
            )
            if (failed) {
                RaftBanner(
                    description = AnnotatedString(stringResource(Res.string.settings_computers_handover_dialog_failed)),
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                    showIcon = false,
                    modifier = Modifier.testTag(RAFT_HANDOVER_FAILED_TAG),
                )
            }
        }
    }
}

/**
 * Web `ComputerHandoverNotice`: under the Info details of a managed Computer, shows either the
 * pending handover (with Cancel handover, or Decline for its designee, when `canCancel`), or the
 * upgrade-first info banner naming `minimumComputerVersion` when `canDesignate && upgradeRequired`.
 */
@Composable
internal fun RaftComputerHandoverNotice(
    handoverState: ComputerHandoverState?,
    currentUserId: String?,
    serverSlug: String,
    onCancel: (suspend () -> DataResult<ComputerHandoverState>)?,
    modifier: Modifier = Modifier,
) {
    val state = handoverState ?: return
    val pending = state.handover
    if (pending != null) {
        val isDesignee = currentUserId != null && pending.to.id == currentUserId
        val name = pending.to.label
        val expires = raftComputerHandoverExpiresLabel(pending.expiresAt)
        var canceling by remember(pending.id) { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val showErrorToast = rememberRaftErrorToast()
        val cancelFailedText = stringResource(Res.string.settings_computers_handover_cancel_failed)
        val title =
            if (isDesignee) {
                stringResource(Res.string.settings_computers_handover_pending_title_you)
            } else {
                stringResource(Res.string.settings_computers_handover_pending_title, name)
            }
        val body =
            if (isDesignee) {
                stringResource(Res.string.settings_computers_handover_pending_body_you, expires, serverSlug)
            } else {
                stringResource(Res.string.settings_computers_handover_pending_body, name, expires)
            }
        RaftBanner(
            description = AnnotatedString(body),
            status = RaftBannerStatus.Info,
            title = title,
            live = false,
            modifier = modifier.testTag(RAFT_COMPUTER_HANDOVER_PENDING_TAG),
            action =
                if (state.canCancel && onCancel != null) {
                    {
                        RaftButton(
                            text =
                                stringResource(
                                    if (isDesignee) {
                                        Res.string.settings_computers_handover_decline
                                    } else {
                                        Res.string.settings_computers_handover_cancel
                                    }
                                ),
                            loading = canceling,
                            onClick = {
                                if (!canceling) {
                                    canceling = true
                                    scope.launch {
                                        val result = onCancel()
                                        canceling = false
                                        if (result !is DataResult.Content) {
                                            val traceId =
                                                (result as? DataResult.Error)?.error?.traceId
                                                    ?: (result as? DataResult.NoPermission)?.blocker?.traceId
                                            showErrorToast(cancelFailedText, traceId, RaftToastIntent.Warning)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.testTag(RAFT_COMPUTER_HANDOVER_CANCEL_TAG),
                        )
                    }
                } else {
                    null
                },
        )
        return
    }
    if (state.canDesignate && state.upgradeRequired) {
        RaftBanner(
            description =
                AnnotatedString(
                    stringResource(Res.string.settings_computers_handover_upgrade_first, state.minimumComputerVersion)
                ),
            status = RaftBannerStatus.Info,
            live = false,
            modifier = modifier.testTag(RAFT_COMPUTER_HANDOVER_UPGRADE_FIRST_TAG),
        )
    }
}
