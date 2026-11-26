package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import build.raft.app.data.AgentContextViewGrant
import build.raft.app.data.AgentContextViewGrantRevokeOutcome
import build.raft.app.data.AgentContextViewGrantUnusableReason
import build.raft.app.data.AgentContextViewGrantsRepository
import build.raft.app.data.DataResult
import build.raft.app.data.MobileUiErrorCode
import build.raft.app.data.MobileUiErrorSurface
import build.raft.app.data.isEligibleContextViewGrantCandidate
import build.raft.app.model.MemberKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.serverRoleCanViewAdministrationSettings
import build.raft.app.resources.*
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
import build.raft.designsystem.components.RaftDialogSurface
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowText
import build.raft.designsystem.components.RaftSelect
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.launch

internal const val SETTINGS_CONTEXT_GRANTS_SECTION_TAG = "settings-context-grants-section"
internal const val SETTINGS_CONTEXT_GRANTS_OPEN_GRANT_TAG = "settings-context-grants-open-grant"
internal const val SETTINGS_CONTEXT_GRANTS_RETRY_TAG = "settings-context-grants-retry"
internal const val SETTINGS_CONTEXT_GRANTS_DISCLOSURE_TAG = "settings-context-grants-disclosure"
internal const val SETTINGS_CONTEXT_GRANTS_CONFIRM_GRANT_TAG = "settings-context-grants-confirm-grant"
internal const val SETTINGS_CONTEXT_GRANTS_CONFIRM_REVOKE_TAG = "settings-context-grants-confirm-revoke"

internal data class AgentContextViewGrantCandidate(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val avatarWireValue: String? = null,
    val isAgent: Boolean = true,
    val isDeleted: Boolean = false,
    val isPersonalAssistant: Boolean = false,
    val managedByAppInstallId: String? = null,
) {
    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: name
}

internal fun filterEligibleContextViewGrantCandidates(
    candidates: List<AgentContextViewGrantCandidate>,
    grantedAgentIds: Set<String>,
): List<AgentContextViewGrantCandidate> =
    candidates
        .filter { candidate ->
            isEligibleContextViewGrantCandidate(
                agentId = candidate.id,
                isAgent = candidate.isAgent,
                isDeleted = candidate.isDeleted,
                isPersonalAssistant = candidate.isPersonalAssistant,
                managedByAppInstallId = candidate.managedByAppInstallId,
                grantedAgentIds = grantedAgentIds,
            )
        }
        .sortedBy { it.label.lowercase() }

internal sealed interface AgentContextViewGrantsUiState {
    data object Loading : AgentContextViewGrantsUiState
    data class Ready(val grants: List<AgentContextViewGrant>) : AgentContextViewGrantsUiState
    data class LoadFailed(val traceId: String? = null) : AgentContextViewGrantsUiState
}

/**
 * Native Settings › Administration › "Context access for agents" section (`botiverse/slock`
 * #13548 & #13550). Note: the mobile Settings › Administration route itself currently delegates to
 * the web Administration page in a standalone Settings WebView (`SettingsAdministrationPage.kt`);
 * this section provides the complete native owner/admin grants management UI and repository wiring.
 */
@Composable
internal fun AgentContextViewGrantsSection(
    serverId: ServerId?,
    serverRole: String?,
    featureFlagEnabled: Boolean,
    repository: AgentContextViewGrantsRepository,
    candidates: List<AgentContextViewGrantCandidate>,
    modifier: Modifier = Modifier,
    timeContext: RaftTimeContext? = null,
) {
    if (!featureFlagEnabled || !serverRoleCanViewAdministrationSettings(serverRole) || serverId == null) {
        return
    }
    val scope = rememberCoroutineScope()
    var reloadNonce by remember(serverId) { mutableIntStateOf(0) }
    var uiState by remember(serverId) { mutableStateOf<AgentContextViewGrantsUiState>(AgentContextViewGrantsUiState.Loading) }
    var infoNote by remember(serverId) { mutableStateOf<String?>(null) }
    var grantDialogOpen by remember(serverId) { mutableStateOf(false) }
    var granting by remember(serverId) { mutableStateOf(false) }
    var grantError by remember(serverId) { mutableStateOf<Pair<String, String?>?>(null) }
    var revokeTarget by remember(serverId) { mutableStateOf<AgentContextViewGrant?>(null) }
    var revoking by remember(serverId) { mutableStateOf(false) }
    var revokeError by remember(serverId) { mutableStateOf<Pair<String, String?>?>(null) }

    val loadFailedLabel = stringResource(Res.string.settings_context_view_grants_load_failed)
    val alreadyRevokedTemplate = stringResource(Res.string.settings_context_view_grants_already_revoked, "%1\$s")

    LaunchedEffect(serverId, reloadNonce) {
        uiState = AgentContextViewGrantsUiState.Loading
        uiState =
            when (val result = repository.listGrants(serverId)) {
                is DataResult.Content -> AgentContextViewGrantsUiState.Ready(result.data)
                is DataResult.Error -> AgentContextViewGrantsUiState.LoadFailed(traceId = result.error.traceId)
                is DataResult.Empty,
                is DataResult.NoPermission -> AgentContextViewGrantsUiState.LoadFailed()
            }
    }

    val grants = (uiState as? AgentContextViewGrantsUiState.Ready)?.grants.orEmpty()
    val eligibleCandidates =
        remember(candidates, grants) {
            filterEligibleContextViewGrantCandidates(
                candidates = candidates,
                grantedAgentIds = grants.mapTo(hashSetOf()) { it.agent.id },
            )
        }

    AgentContextViewGrantsSectionContent(
        serverId = serverId.value,
        state = uiState,
        infoNote = infoNote,
        onDismissInfoNote = { infoNote = null },
        onRetryLoad = { reloadNonce += 1 },
        onOpenGrantDialog = {
            grantError = null
            grantDialogOpen = true
        },
        onRequestRevoke = { grant ->
            revokeError = null
            revokeTarget = grant
        },
        timeContext = timeContext,
        modifier = modifier,
    )

    if (grantDialogOpen) {
        GrantContextViewDialog(
            eligibleCandidates = eligibleCandidates,
            granting = granting,
            errorMessage = grantError?.first,
            errorTraceId = grantError?.second,
            onDismiss = {
                if (!granting) {
                    grantDialogOpen = false
                    grantError = null
                }
            },
            onConfirm = { candidate ->
                if (granting) return@GrantContextViewDialog
                granting = true
                grantError = null
                scope.launch {
                    when (val result = repository.grantAccess(serverId, candidate.id)) {
                        is DataResult.Content -> {
                            granting = false
                            grantDialogOpen = false
                            infoNote = null
                            reloadNonce += 1
                        }
                        is DataResult.Error -> {
                            granting = false
                            grantError =
                                (result.error.serverMessage?.takeIf { it.isNotBlank() } ?: loadFailedLabel) to
                                    result.error.traceId
                        }
                        is DataResult.Empty,
                        is DataResult.NoPermission -> {
                            granting = false
                            grantError = loadFailedLabel to null
                        }
                    }
                }
            },
        )
    }

    revokeTarget?.let { target ->
        RevokeContextViewDialog(
            grant = target,
            revoking = revoking,
            errorMessage = revokeError?.first,
            errorTraceId = revokeError?.second,
            onDismiss = {
                if (!revoking) {
                    revokeTarget = null
                    revokeError = null
                }
            },
            onConfirm = {
                if (revoking) return@RevokeContextViewDialog
                revoking = true
                revokeError = null
                scope.launch {
                    when (val result = repository.revokeGrant(serverId, target.id)) {
                        is DataResult.Content -> {
                            revoking = false
                            revokeTarget = null
                            if (result.data == AgentContextViewGrantRevokeOutcome.Gone) {
                                infoNote = alreadyRevokedTemplate.replace("%1\$s", target.agent.name)
                            } else {
                                infoNote = null
                            }
                            reloadNonce += 1
                        }
                        is DataResult.Error -> {
                            revoking = false
                            revokeError =
                                (result.error.serverMessage?.takeIf { it.isNotBlank() } ?: loadFailedLabel) to
                                    result.error.traceId
                        }
                        is DataResult.Empty,
                        is DataResult.NoPermission -> {
                            revoking = false
                            revokeError = loadFailedLabel to null
                        }
                    }
                }
            },
        )
    }
}

@Composable
internal fun agentContextViewGrantUnusableLabel(reason: AgentContextViewGrantUnusableReason?): String =
    when (reason) {
        AgentContextViewGrantUnusableReason.GranterGone ->
            stringResource(Res.string.settings_context_view_grants_unusable_granter_gone)
        AgentContextViewGrantUnusableReason.GranterNotAdmin ->
            stringResource(Res.string.settings_context_view_grants_unusable_granter_not_admin)
        AgentContextViewGrantUnusableReason.AgentIneligible ->
            stringResource(Res.string.settings_context_view_grants_unusable_agent_ineligible)
        null -> stringResource(Res.string.settings_context_view_grants_inactive)
    }

/**
 * Pure presentation body for "Context access for agents" (testable directly without network).
 */
@Composable
internal fun AgentContextViewGrantsSectionContent(
    serverId: String?,
    state: AgentContextViewGrantsUiState,
    infoNote: String?,
    onDismissInfoNote: () -> Unit,
    onRetryLoad: () -> Unit,
    onOpenGrantDialog: () -> Unit,
    onRequestRevoke: (AgentContextViewGrant) -> Unit,
    modifier: Modifier = Modifier,
    timeContext: RaftTimeContext? = null,
) {
    SettingsFormSection(
        iconName = RaftLucideIconName.FileSearch,
        title = stringResource(Res.string.settings_context_view_grants_section_label),
        loading = state is AgentContextViewGrantsUiState.Loading,
    ) {
        Column(
            modifier = modifier.fillMaxWidth().testTag(SETTINGS_CONTEXT_GRANTS_SECTION_TAG),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftText(
                    text = stringResource(Res.string.settings_context_view_grants_description),
                    style = RaftTheme.typography.callout,
                    color = RaftTheme.colors.foregroundMuted,
                    modifier = Modifier.weight(1f),
                )
                RaftButton(
                    text = stringResource(Res.string.settings_context_view_grants_grant),
                    onClick = onOpenGrantDialog,
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Md,
                    enabled = state is AgentContextViewGrantsUiState.Ready,
                    modifier = Modifier.testTag(SETTINGS_CONTEXT_GRANTS_OPEN_GRANT_TAG),
                )
            }

            if (!infoNote.isNullOrBlank()) {
                RaftBanner(
                    description = infoNote,
                    status = RaftBannerStatus.Info,
                    size = RaftBannerSize.Sm,
                    onDismiss = onDismissInfoNote,
                )
            }

            when (state) {
                AgentContextViewGrantsUiState.Loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        RaftSpinner()
                    }
                }
                is AgentContextViewGrantsUiState.LoadFailed -> {
                    RaftBanner(
                        description = AnnotatedString(stringResource(Res.string.settings_context_view_grants_load_failed)),
                        status = RaftBannerStatus.Warning,
                        size = RaftBannerSize.Md,
                        action = {
                            RaftButton(
                                text = stringResource(Res.string.settings_context_view_grants_retry),
                                onClick = onRetryLoad,
                                variant = RaftButtonVariant.Default,
                                size = RaftButtonSize.Md,
                                modifier = Modifier.testTag(SETTINGS_CONTEXT_GRANTS_RETRY_TAG),
                            )
                        },
                        content =
                            if (!state.traceId.isNullOrBlank()) {
                                {
                                    RaftTraceIdNote(
                                        traceId = state.traceId,
                                        uiErrorSurface = MobileUiErrorSurface.ScreenError,
                                        uiErrorRoute = "settings_context_view_grants",
                                        uiErrorCode = MobileUiErrorCode.Unknown,
                                    )
                                }
                            } else {
                                null
                            },
                    )
                }
                is AgentContextViewGrantsUiState.Ready -> {
                    if (state.grants.isEmpty()) {
                        RaftText(
                            text = stringResource(Res.string.settings_context_view_grants_empty),
                            style = RaftTheme.typography.callout,
                            color = RaftTheme.colors.foregroundHint,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    } else {
                        RaftListRowGroup {
                            state.grants.forEachIndexed { index, grant ->
                                val whenLabel = raftLocalizedShortDateTime(grant.grantedAt, timeContext)
                                val subtitle =
                                    if (grant.granter != null && grant.granter.name.isNotBlank()) {
                                        stringResource(
                                            Res.string.settings_context_view_grants_granted_by,
                                            grant.granter.name,
                                            whenLabel,
                                        )
                                    } else {
                                        stringResource(
                                            Res.string.settings_context_view_grants_granted_by_former,
                                            whenLabel,
                                        )
                                    }
                                RaftListRow(
                                    title = grant.agent.name,
                                    subtitle = subtitle,
                                    text = RaftListRowText.Member,
                                    divider = index > 0,
                                    leading = {
                                        RaftMemberAvatar(
                                            identity =
                                                RaftMemberAvatarIdentity(
                                                    kind = MemberKind.Agent,
                                                    memberId = grant.agent.id,
                                                    serverId = serverId,
                                                ),
                                            label = grant.agent.name,
                                            avatarSize = 35.dp,
                                        )
                                    },
                                    titleTrailing =
                                        if (!grant.usable) {
                                            {
                                                RaftBadge(
                                                    text = agentContextViewGrantUnusableLabel(grant.unusableReason),
                                                    appearance = RaftBadgeAppearance.Soft,
                                                    variant = RaftBadgeVariant.Warning,
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                    trailing = {
                                        RaftButton(
                                            text = stringResource(Res.string.settings_context_view_grants_revoke),
                                            onClick = { onRequestRevoke(grant) },
                                            variant = RaftButtonVariant.Default,
                                            size = RaftButtonSize.Md,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun GrantContextViewDialog(
    eligibleCandidates: List<AgentContextViewGrantCandidate>,
    granting: Boolean,
    errorMessage: String?,
    errorTraceId: String?,
    onDismiss: () -> Unit,
    onConfirm: (AgentContextViewGrantCandidate) -> Unit,
) {
    var selectedCandidate by remember(eligibleCandidates) { mutableStateOf<AgentContextViewGrantCandidate?>(null) }

    RaftDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_context_view_grants_grant_title),
        size = RaftDialogSize.Md,
        dismissOnClickOutside = !granting,
        footer = {
            GrantContextViewDialogFooter(
                selectedCandidate = selectedCandidate,
                granting = granting,
                onDismiss = onDismiss,
                onConfirm = onConfirm,
            )
        },
    ) {
        GrantContextViewDialogBody(
            eligibleCandidates = eligibleCandidates,
            selectedCandidate = selectedCandidate,
            onSelectCandidate = { selectedCandidate = it },
            granting = granting,
            errorMessage = errorMessage,
            errorTraceId = errorTraceId,
        )
    }
}

/**
 * In-tree dialog surface variant of [GrantContextViewDialog] for fixtures and Robolectric UI tests.
 */
@Composable
internal fun GrantContextViewDialogSurface(
    eligibleCandidates: List<AgentContextViewGrantCandidate>,
    granting: Boolean,
    errorMessage: String?,
    errorTraceId: String?,
    onDismiss: () -> Unit,
    onConfirm: (AgentContextViewGrantCandidate) -> Unit,
) {
    var selectedCandidate by remember(eligibleCandidates) { mutableStateOf<AgentContextViewGrantCandidate?>(null) }
    RaftDialogSurface(
        title = stringResource(Res.string.settings_context_view_grants_grant_title),
        size = RaftDialogSize.Md,
        onClose = onDismiss,
        footer = {
            GrantContextViewDialogFooter(
                selectedCandidate = selectedCandidate,
                granting = granting,
                onDismiss = onDismiss,
                onConfirm = onConfirm,
            )
        },
    ) {
        GrantContextViewDialogBody(
            eligibleCandidates = eligibleCandidates,
            selectedCandidate = selectedCandidate,
            onSelectCandidate = { selectedCandidate = it },
            granting = granting,
            errorMessage = errorMessage,
            errorTraceId = errorTraceId,
        )
    }
}

@Composable
private fun GrantContextViewDialogFooter(
    selectedCandidate: AgentContextViewGrantCandidate?,
    granting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (AgentContextViewGrantCandidate) -> Unit,
) {
    RaftButton(
        text = stringResource(Res.string.common_cancel),
        onClick = onDismiss,
        variant = RaftButtonVariant.Ghost,
        size = RaftButtonSize.Lg,
        enabled = !granting,
    )
    RaftButton(
        text =
            stringResource(
                if (granting) {
                    Res.string.settings_context_view_grants_granting
                } else {
                    Res.string.settings_context_view_grants_grant_confirm
                }
            ),
        onClick = { selectedCandidate?.let(onConfirm) },
        variant = RaftButtonVariant.Primary,
        size = RaftButtonSize.Lg,
        enabled = selectedCandidate != null && !granting,
        loading = granting,
        modifier = Modifier.testTag(SETTINGS_CONTEXT_GRANTS_CONFIRM_GRANT_TAG),
    )
}

@Composable
private fun GrantContextViewDialogBody(
    eligibleCandidates: List<AgentContextViewGrantCandidate>,
    selectedCandidate: AgentContextViewGrantCandidate?,
    onSelectCandidate: (AgentContextViewGrantCandidate) -> Unit,
    granting: Boolean,
    errorMessage: String?,
    errorTraceId: String?,
) {
    val disclosureSubject =
        selectedCandidate?.label ?: stringResource(Res.string.settings_context_view_grants_this_agent)
    val disclosureText = stringResource(Res.string.settings_context_view_grants_disclosure, disclosureSubject)
    val placeholder =
        if (eligibleCandidates.isEmpty()) {
            stringResource(Res.string.settings_context_view_grants_no_candidates)
        } else {
            stringResource(Res.string.settings_context_view_grants_agent_placeholder)
        }
    RaftField(
        label = stringResource(Res.string.settings_context_view_grants_agent_label),
        enabled = eligibleCandidates.isNotEmpty() && !granting,
    ) {
        RaftSelect(
            value = selectedCandidate,
            options = eligibleCandidates,
            onValueChange = onSelectCandidate,
            optionLabel = { it.label },
            placeholder = placeholder,
            enabled = eligibleCandidates.isNotEmpty() && !granting,
        )
    }
    // Pre-pick warning shown before picking so the admin sees the server-wide scope up front (#13548).
    RaftBanner(
        description = AnnotatedString(disclosureText),
        status = RaftBannerStatus.Warning,
        size = RaftBannerSize.Sm,
        live = false,
        modifier = Modifier.testTag(SETTINGS_CONTEXT_GRANTS_DISCLOSURE_TAG),
    )
    if (!errorMessage.isNullOrBlank()) {
        RaftBanner(
            description = AnnotatedString(errorMessage),
            status = RaftBannerStatus.Warning,
            size = RaftBannerSize.Sm,
            content =
                if (!errorTraceId.isNullOrBlank()) {
                    {
                        RaftTraceIdNote(
                            traceId = errorTraceId,
                            uiErrorSurface = MobileUiErrorSurface.ScreenError,
                            uiErrorRoute = "settings_context_view_grants",
                            uiErrorCode = MobileUiErrorCode.Unknown,
                        )
                    }
                } else {
                    null
                },
        )
    }
}

@Composable
internal fun RevokeContextViewDialog(
    grant: AgentContextViewGrant,
    revoking: Boolean,
    errorMessage: String?,
    errorTraceId: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    RaftDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_context_view_grants_revoke_title, grant.agent.name),
        description = stringResource(Res.string.settings_context_view_grants_revoke_message, grant.agent.name),
        size = RaftDialogSize.Sm,
        dismissOnClickOutside = !revoking,
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !revoking,
            )
            RaftButton(
                text =
                    stringResource(
                        if (revoking) {
                            Res.string.settings_context_view_grants_revoking
                        } else {
                            Res.string.settings_context_view_grants_revoke
                        }
                    ),
                onClick = onConfirm,
                variant = RaftButtonVariant.Warning,
                size = RaftButtonSize.Lg,
                enabled = !revoking,
                loading = revoking,
                modifier = Modifier.testTag(SETTINGS_CONTEXT_GRANTS_CONFIRM_REVOKE_TAG),
            )
        },
    ) {
        if (!errorMessage.isNullOrBlank()) {
            RaftBanner(
                description = AnnotatedString(errorMessage),
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm,
                content =
                    if (!errorTraceId.isNullOrBlank()) {
                        {
                            RaftTraceIdNote(
                                traceId = errorTraceId,
                                uiErrorSurface = MobileUiErrorSurface.ScreenError,
                                uiErrorRoute = "settings_context_view_grants",
                                uiErrorCode = MobileUiErrorCode.Unknown,
                            )
                        }
                    } else {
                        null
                    },
            )
        }
    }
}
