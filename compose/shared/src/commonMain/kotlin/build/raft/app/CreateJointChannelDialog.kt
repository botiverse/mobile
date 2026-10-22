package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.JOINT_CHANNEL_MAX_SERVERS
import build.raft.app.data.JointChannelAccepted
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.errorPresentationOrNull
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftCardVariant
import build.raft.designsystem.components.RaftCheckboxRow
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextarea
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource

/**
 * Home sidebar "Joint Channels" + → web `CreateJointChannelDialog.tsx` (slock 7d349b509d65):
 * `POST channels` with `visibility: "joint"`, one `jointInvites` entry per server, optional
 * initial members of this server. Free servers see the web's allowance banner; plan-limit
 * failures add its "View Billing" link.
 */
@Composable
internal fun CreateJointChannelDialogHost(
    networkClient: RaftNetworkClient,
    freePlan: Boolean,
    currentServerSlug: String?,
    currentMemberId: String?,
    members: List<MemberProfile>,
    membersLoading: Boolean,
    logger: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenChannel: (JointChannelAccepted) -> Unit,
    onOpenBilling: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val store =
        remember(networkClient, scope) {
            CreateJointChannelStore(NetworkCreateJointChannelDataSource(networkClient, logger), scope)
        }
    DisposableEffect(store) { onDispose { store.clear() } }
    val state by store.state.collectAsState()
    val latestOpen by rememberUpdatedState(onOpenChannel)
    LaunchedEffect(state.effect) {
        when (val effect = state.effect) {
            is CreateJointChannelEffect.Open -> latestOpen(effect.channel)
            null -> Unit
        }
        if (state.effect != null) store.dispatch(CreateJointChannelIntent.ConsumeEffect)
    }
    CreateJointChannelDialog(
        state = state,
        freePlan = freePlan,
        currentServerSlug = currentServerSlug,
        selectableMembers = members.filter { it.id.value != currentMemberId && (it.kind == MemberKind.Agent || it.kind == MemberKind.Human) },
        membersLoading = membersLoading,
        onIntent = store::dispatch,
        onDismiss = { if (!state.submitting) onDismiss() },
        onOpenBilling = onOpenBilling,
    )
}

@Composable
private fun CreateJointChannelDialog(
    state: CreateJointChannelState,
    freePlan: Boolean,
    currentServerSlug: String?,
    selectableMembers: List<MemberProfile>,
    membersLoading: Boolean,
    onIntent: (CreateJointChannelIntent) -> Unit,
    onDismiss: () -> Unit,
    onOpenBilling: () -> Unit,
) {
    val title = stringResource(MR.strings.joint_create_title)
    val errorText = state.error?.let { createJointChannelErrorMessage(it, currentServerSlug) }
    RaftDesignSystemTheme {
        RaftDialog(
            onDismissRequest = onDismiss,
            title = title,
            size = RaftDialogSize.Lg,
            dismissOnClickOutside = false,
            closeLabel = stringResource(MR.strings.common_close),
            footer = {
                RaftButton(
                    text = stringResource(MR.strings.common_cancel),
                    onClick = onDismiss,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                    enabled = !state.submitting,
                )
                RaftButton(
                    text = stringResource(MR.strings.joint_create_submit),
                    onClick = { onIntent(CreateJointChannelIntent.Submit) },
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                    loading = state.submitting,
                    loadingLabel = stringResource(MR.strings.joint_create_creating),
                )
            },
        ) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (freePlan) {
                    RaftBanner(
                        description = stringResource(MR.strings.joint_create_free_allowance),
                        status = RaftBannerStatus.Info,
                        size = RaftBannerSize.Sm,
                    )
                }
                errorText?.let { message ->
                    RaftBanner(
                        description = message,
                        status = RaftBannerStatus.Warning,
                        size = RaftBannerSize.Sm,
                        action =
                            if (state.showsBillingLink) {
                                {
                                    RaftButton(
                                        text = stringResource(MR.strings.joint_create_view_billing),
                                        onClick = onOpenBilling,
                                        variant = RaftButtonVariant.Default,
                                    )
                                }
                            } else {
                                null
                            },
                    )
                }
                state.collision?.let { collision ->
                    RaftBanner(
                        description = stringResource(MR.strings.joint_create_archived_name_held, collision.archivedChannelName),
                        status = RaftBannerStatus.Warning,
                        size = RaftBannerSize.Sm,
                        action = {
                            if (collision.canUnarchive) {
                                RaftButton(
                                    text = stringResource(MR.strings.joint_create_unarchive),
                                    onClick = { onIntent(CreateJointChannelIntent.UnarchiveCollision) },
                                    enabled = !state.submitting,
                                )
                            }
                            RaftButton(
                                text = stringResource(MR.strings.joint_create_change_name),
                                onClick = { onIntent(CreateJointChannelIntent.ChangeCollidingName) },
                                variant = RaftButtonVariant.Ghost,
                            )
                        },
                    )
                }
                RaftField(label = stringResource(MR.strings.joint_create_name_label), required = true, enabled = !state.submitting) {
                    RaftTextField(
                        value = state.name,
                        onValueChange = { onIntent(CreateJointChannelIntent.SetName(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = stringResource(MR.strings.joint_create_name_placeholder),
                    )
                }
                RaftField(label = stringResource(MR.strings.joint_create_description_label), optional = true, enabled = !state.submitting) {
                    RaftTextarea(
                        value = state.description,
                        onValueChange = { onIntent(CreateJointChannelIntent.SetDescription(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = stringResource(MR.strings.joint_create_description_placeholder),
                    )
                }
                CreateJointInviteDrafts(state, onIntent)
                CreateJointMembers(state, selectableMembers, membersLoading, onIntent)
            }
        }
    }
}

@Composable
private fun CreateJointInviteDrafts(state: CreateJointChannelState, onIntent: (CreateJointChannelIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RaftSectionEyebrow(stringResource(MR.strings.joint_create_invite_servers))
            Spacer(Modifier.weight(1f))
            RaftButton(
                text = stringResource(MR.strings.joint_create_add_server),
                onClick = { onIntent(CreateJointChannelIntent.AddDraft) },
                enabled = !state.submitting && state.canAddDraft,
                leadingIcon = { RaftComputersIcon(RaftLucideIconName.Plus) },
            )
        }
        if (!state.canAddDraft) {
            RaftText(
                stringResource(MR.strings.joint_create_max_servers, JOINT_CHANNEL_MAX_SERVERS),
                style = RaftTheme.typography.caption,
                color = RaftTheme.colors.foregroundHint,
            )
        }
        state.drafts.forEachIndexed { index, draft ->
            RaftCard(
                variant = RaftCardVariant.Default,
                title = stringResource(MR.strings.joint_create_server_invite_index, index + 1),
                trailing =
                    if (state.drafts.size > 1) {
                        {
                            RaftButton(
                                text = stringResource(MR.strings.joint_create_remove),
                                onClick = { onIntent(CreateJointChannelIntent.RemoveDraft(draft.id)) },
                                variant = RaftButtonVariant.Ghost,
                                enabled = !state.submitting,
                            )
                        }
                    } else {
                        null
                    },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RaftField(label = stringResource(MR.strings.joint_connections_slug_label), required = true, enabled = !state.submitting) {
                        RaftTextField(
                            value = draft.targetServerSlug,
                            onValueChange = { onIntent(CreateJointChannelIntent.UpdateDraft(draft.id, targetServerSlug = it)) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = stringResource(MR.strings.joint_connections_slug_placeholder),
                        )
                    }
                    RaftField(
                        label = stringResource(MR.strings.joint_connections_people_label),
                        required = true,
                        description = stringResource(MR.strings.joint_connections_people_hint),
                        enabled = !state.submitting,
                    ) {
                        RaftTextarea(
                            value = draft.invitedPeopleText,
                            onValueChange = { onIntent(CreateJointChannelIntent.UpdateDraft(draft.id, invitedPeopleText = it)) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = stringResource(MR.strings.joint_connections_people_placeholder),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateJointMembers(
    state: CreateJointChannelState,
    members: List<MemberProfile>,
    membersLoading: Boolean,
    onIntent: (CreateJointChannelIntent) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    RaftField(label = stringResource(MR.strings.joint_create_current_members), optional = true, enabled = !state.submitting) {
        when {
            membersLoading && members.isEmpty() ->
                RaftText(stringResource(MR.strings.common_loading), style = RaftTheme.typography.callout, color = RaftTheme.colors.foregroundHint)
            members.isEmpty() ->
                RaftText(stringResource(MR.strings.joint_create_no_members), style = RaftTheme.typography.callout, color = RaftTheme.colors.foregroundHint)
            else -> {
                val filtered = createJointMemberMatches(members, query)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaftTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = stringResource(MR.strings.joint_create_members_search),
                    )
                    listOf(
                        MemberKind.Agent to stringResource(MR.strings.joint_create_agents),
                        MemberKind.Human to stringResource(MR.strings.joint_create_humans),
                    ).forEach { (kind, label) ->
                        val group = filtered.filter { it.kind == kind }
                        if (group.isNotEmpty()) {
                            RaftSectionEyebrow(label, Modifier.padding(top = 4.dp))
                            group.forEach { member ->
                                val selected =
                                    if (kind == MemberKind.Agent) member.id.value in state.selectedAgentIds else member.id.value in state.selectedUserIds
                                RaftCheckboxRow(
                                    label = member.displayName.ifBlank { member.handle },
                                    checked = selected,
                                    onCheckedChange = {
                                        onIntent(
                                            if (kind == MemberKind.Agent) {
                                                CreateJointChannelIntent.ToggleAgent(member.id.value)
                                            } else {
                                                CreateJointChannelIntent.ToggleUser(member.id.value)
                                            }
                                        )
                                    },
                                )
                            }
                        }
                    }
                    if (filtered.isEmpty()) {
                        RaftText(
                            stringResource(MR.strings.joint_create_no_matches, query.trim()),
                            style = RaftTheme.typography.callout,
                            color = RaftTheme.colors.foregroundHint,
                        )
                    }
                }
            }
        }
    }
}

/** Name/handle substring match, agents before humans as the web picker groups them. */
internal fun createJointMemberMatches(members: List<MemberProfile>, query: String): List<MemberProfile> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return members
    return members.filter { member ->
        member.displayName.lowercase().contains(needle) || member.handle.lowercase().contains(needle)
    }
}

@Composable
private fun createJointChannelErrorMessage(error: CreateJointChannelError, currentServerSlug: String?): String {
    val strings = localizedErrorPresentationStrings()
    // The server's structured fields fill the localized sentences (web describeJointInviteError).
    val details = (error as? CreateJointChannelError.Server)?.details
    val invitee = details?.invitee.orEmpty()
    val server = details?.targetServerSlug.orEmpty()
    val blocking = details?.blockingChannelName.orEmpty()
    val freeHost = stringResource(MR.strings.joint_create_free_host_limit, blocking)
    val notFound = stringResource(MR.strings.joint_invite_error_invitee_not_found, invitee, server)
    val notAdmin = stringResource(MR.strings.joint_invite_error_invitee_not_admin, invitee, server)
    val serverNotFound = stringResource(MR.strings.joint_invite_error_server_not_found, server)
    val copy =
        CreateJointErrorCopy(
            nameInvalid = stringResource(MR.strings.channel_create_error_name_format),
            slugRequired = stringResource(MR.strings.joint_connections_slug_required),
            peopleRequired = stringResource(MR.strings.joint_connections_people_required),
            freeServerLimit = stringResource(MR.strings.joint_limit_free_servers),
            serverLimit = stringResource(MR.strings.joint_limit_servers, JOINT_CHANNEL_MAX_SERVERS),
            freeHostLimit = { freeHost },
            freeHostLimitUnnamed = stringResource(MR.strings.joint_create_free_host_limit_unnamed),
            inviteeNotFound = { _, _ -> notFound },
            inviteeNotAdmin = { _, _ -> notAdmin },
            serverNotFound = { serverNotFound },
            cannotInviteSelf = stringResource(MR.strings.joint_invite_error_cannot_invite_self),
            failedCreate = stringResource(MR.strings.joint_create_failed),
        )
    return createJointChannelErrorText(error, copy, currentServerSlug) { result ->
        result.errorPresentationOrNull()?.resolve(strings)
    }
}
