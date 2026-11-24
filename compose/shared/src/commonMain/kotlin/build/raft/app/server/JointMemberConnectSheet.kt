package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.JOINT_CHANNEL_MAX_SERVERS
import build.raft.app.data.RaftAvatarCacheSubject
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftChoiceListItem
import build.raft.designsystem.components.RaftDialogSheet
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMultiChoiceList
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextFieldDefaults
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

internal const val JointMemberConnectMenuTag = "joint-member-connect-menu"
internal const val JointMemberConnectMenuItemTag = "joint-member-connect-menu-item"
internal const val JointMemberConnectSheetTag = "joint-member-connect-sheet"
internal const val JointMemberConnectErrorTag = "joint-member-connect-error"
internal const val JointMemberConnectConsequenceTag = "joint-member-connect-consequence"
internal const val JointMemberConnectGuestTag = "joint-member-connect-guest"
internal const val JointMemberConnectGuestEntryTag = "member-connect-guest-entry"
internal const val JointMemberConnectCreateServerTag = "member-connect-create-server"
internal const val JointMemberConnectNewServerFormTag = "member-connect-new-server"
internal const val JointMemberConnectNewServerErrorTag = "member-connect-new-server-error"
internal const val JointMemberConnectNewServerNameTag = "member-connect-new-server-name"
internal const val JointMemberConnectNewServerSlugTag = "member-connect-new-server-slug"
internal const val JointMemberConnectUpgradeTag = "member-connect-upgrade"
internal const val JointMemberConnectAskOwnerTag = "member-connect-ask-owner"

/**
 * Web ConnectedServersMenu: the ⋯ at the end of Connected servers' heading with one item,
 * Connect your servers. The caller draws it only where the viewer has a server to connect.
 */
@Composable
internal fun JointMemberConnectMenu(enabled: Boolean, onConnectYourServers: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.joint_member_connect_menu_label)
    Box {
        RaftIconButton(
            onClick = { expanded = true },
            tooltip = label,
            variant = RaftButtonVariant.Ghost,
            size = RaftIconButtonSize.Md,
            enabled = enabled,
            modifier = Modifier.testTag(JointMemberConnectMenuTag),
        ) {
            RaftComputersIcon(RaftLucideIconName.EllipsisVertical, size = 18.dp)
        }
        RaftDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, alignEnd = true) {
            RaftMenuItem(
                text = stringResource(Res.string.joint_member_connect_menu_item),
                onClick = {
                    expanded = false
                    onConnectYourServers()
                },
                leadingIcon = { RaftComputersIcon(RaftLucideIconName.Plug) },
                modifier = Modifier.testTag(JointMemberConnectMenuItemTag),
            )
        }
    }
}

/** `Intl.ListFormat` conjunction of [names] (the web's `formatList(..., { type: "conjunction" })`). */
@Composable
internal fun jointMemberConnectNames(names: List<String>): String {
    val two = stringResource(Res.string.member_removal_impact_list_two, "\u0001", "\u0002")
    val more = stringResource(Res.string.member_removal_impact_list_more, "\u0001", "\u0002")
    val end = stringResource(Res.string.member_removal_impact_list_end, "\u0001", "\u0002")
    fun fill(template: String, a: String, b: String) = template.replace("\u0001", a).replace("\u0002", b)
    return memberRemovalImpactJoin(names, { a, b -> fill(two, a, b) }, { a, b -> fill(more, a, b) }, { a, b -> fill(end, a, b) })
}

/** Disjunction join of [names] (e.g. "Tom or Lena") for `askServers` approver hints (slock #16361). */
@Composable
internal fun jointMemberConnectOrNames(names: List<String>): String {
    val two = stringResource(Res.string.joint_member_connect_list_or_two, "\u0001", "\u0002")
    val more = stringResource(Res.string.member_removal_impact_list_more, "\u0001", "\u0002")
    val end = stringResource(Res.string.joint_member_connect_list_or_end, "\u0001", "\u0002")
    fun fill(template: String, a: String, b: String) = template.replace("\u0001", a).replace("\u0002", b)
    return memberRemovalImpactJoin(names, { a, b -> fill(two, a, b) }, { a, b -> fill(more, a, b) }, { a, b -> fill(end, a, b) })
}

/** A refusal as the sheet says it: values {target} (server name), {channel} (with #), {host}, {max}. */
@Composable
internal fun jointMemberConnectMessage(message: MemberConnectMessage, target: String, channel: String, host: String): String =
    when (message) {
        MemberConnectMessage.NotAdmin -> stringResource(Res.string.joint_member_connect_error_not_admin, target)
        MemberConnectMessage.AlreadyConnected -> stringResource(Res.string.joint_member_connect_error_already_connected, target)
        MemberConnectMessage.MembershipChanged -> stringResource(Res.string.joint_member_connect_error_membership_changed, channel)
        MemberConnectMessage.NotAllowed -> stringResource(Res.string.joint_member_connect_error_not_allowed, channel)
        MemberConnectMessage.GuestHasComputer -> stringResource(Res.string.joint_member_connect_error_guest_has_computer, host)
        MemberConnectMessage.ServerMembershipRequired ->
            stringResource(Res.string.joint_member_connect_error_server_membership_required, target)
        MemberConnectMessage.InvalidServer -> stringResource(Res.string.joint_member_connect_error_invalid_server, target, channel)
        MemberConnectMessage.Archived -> stringResource(Res.string.joint_member_connect_error_archived, channel)
        MemberConnectMessage.ChannelNameTaken -> stringResource(Res.string.joint_member_connect_error_channel_name_taken, target, channel)
        MemberConnectMessage.TooManyAttempts -> stringResource(Res.string.joint_member_connect_error_too_many_attempts)
        MemberConnectMessage.ChannelFull -> stringResource(Res.string.joint_member_connect_error_channel_full, channel)
        MemberConnectMessage.TargetFreeLimit -> stringResource(Res.string.joint_member_connect_error_target_free_limit, target)
        MemberConnectMessage.HostLimit -> stringResource(Res.string.joint_member_connect_error_host_limit, host)
        MemberConnectMessage.JointFreeServerLimit ->
            stringResource(Res.string.joint_member_connect_error_joint_free_server_limit, channel, target)
        MemberConnectMessage.GuestsPresent -> stringResource(Res.string.joint_member_connect_error_guests_present, channel)
        MemberConnectMessage.JointPartyLimit ->
            stringResource(Res.string.joint_member_connect_error_joint_party_limit, channel, JOINT_CHANNEL_MAX_SERVERS, host)
        MemberConnectMessage.ConnectInProgress -> stringResource(Res.string.joint_member_connect_error_connect_in_progress, channel)
        MemberConnectMessage.Busy -> stringResource(Res.string.joint_member_connect_error_busy, channel)
        MemberConnectMessage.Generic -> stringResource(Res.string.joint_member_connect_error_generic)
    }

/**
 * Web ConnectYourServersDialog (`narrow="sheet"`, a phone dialog sheet): the viewer's servers,
 * the ones they own or administer and that aren't in the channel chosen with a checkbox, the
 * rest listed with why not (including `askServers` approver names, slock #16361). From a Guest's
 * Connect your servers entry (`sheet.guestEntry`, slock #16375), the Guest helper line leads and
 * "Create a server and connect" opens the new-server form in the same sheet.
 */
@Composable
internal fun JointMemberConnectSheet(
    state: JointChannelConnectionsState,
    onDismiss: () -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
    onSubmit: () -> Unit,
    onUpgradeServer: (serverId: String) -> Unit = {},
    offersUpgrade: Boolean = settingsBillingOffersUpgrade(),
    onOpenCreateServer: () -> Unit = {},
    onCloseCreateServer: () -> Unit = {},
    onNewServerNameChange: (String) -> Unit = {},
    onNewServerSlugChange: (String) -> Unit = {},
    onSubmitNewServer: () -> Unit = {},
) {
    val sheet = state.memberConnectSheet ?: return
    val channelName = state.connections?.channelName?.removePrefix("#").orEmpty()
    val channel = "#$channelName"
    val host = state.memberConnectHostName
    val newServer = sheet.newServer
    if (newServer != null) {
        JointMemberConnectNewServerSheet(
            form = newServer,
            channel = channel,
            host = host,
            onDismiss = onDismiss,
            onBack = onCloseCreateServer,
            onNameChange = onNewServerNameChange,
            onSlugChange = onNewServerSlugChange,
            onSubmit = onSubmitNewServer,
        )
        return
    }
    JointMemberConnectListSheet(
        state = state,
        sheet = sheet,
        channel = channel,
        host = host,
        onDismiss = onDismiss,
        onSelectionChange = onSelectionChange,
        onSubmit = onSubmit,
        onUpgradeServer = onUpgradeServer,
        offersUpgrade = offersUpgrade,
        onOpenCreateServer = onOpenCreateServer,
    )
}

@Composable
private fun JointMemberConnectListSheet(
    state: JointChannelConnectionsState,
    sheet: MemberConnectSheet,
    channel: String,
    host: String,
    onDismiss: () -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
    onSubmit: () -> Unit,
    onUpgradeServer: (serverId: String) -> Unit,
    offersUpgrade: Boolean,
    onOpenCreateServer: () -> Unit,
) {
    val rows = state.memberConnectDisplayRows
    val nameOf = rows.associate { it.server.id to it.server.name.ifBlank { it.server.slug } }
    val askByServerId = sheet.askServers.associateBy { it.id }
    var query by remember { mutableStateOf("") }
    val selectedNames = sheet.selected.mapNotNull { nameOf[it] }
    val connectedNow = stringResource(Res.string.joint_member_connect_row_connected_now)
    val atLimitLabel = stringResource(Res.string.joint_member_connect_row_at_limit)
    val owner = stringResource(Res.string.joint_member_connect_row_owner)
    val admin = stringResource(Res.string.joint_member_connect_row_admin)
    val alreadyConnected = stringResource(Res.string.joint_member_connect_row_already_connected)
    val notAdmin = stringResource(Res.string.joint_member_connect_row_not_admin)
    val upgradeLabel = stringResource(Res.string.channel_external_invite_upgrade)
    val askOwnerLabel = stringResource(Res.string.joint_member_connect_row_ask_owner)
    val mono = RaftTheme.typography.monoMeta.toSpanStyle()
    val items =
        rows.map { row ->
            val server = row.server
            val serverDisplayName = server.name.ifBlank { server.slug }
            val limit = state.memberConnectLimit(row)
            val atLimit = limit?.atLimit == true
            val approverNames =
                askByServerId[server.id]
                    ?.approvers
                    ?.map { it.name.trim() }
                    ?.filter(String::isNotBlank)
                    .orEmpty()
            val reason =
                when {
                    server.id in sheet.done -> connectedNow
                    atLimit -> atLimitLabel
                    else ->
                        when (state.memberConnectRowState(row)) {
                            MemberConnectRowState.Eligible -> if (server.role == "owner") owner else admin
                            MemberConnectRowState.Connected -> alreadyConnected
                            MemberConnectRowState.NotAdmin ->
                                if (approverNames.isNotEmpty()) {
                                    stringResource(
                                        Res.string.joint_member_connect_row_ask_approvers,
                                        jointMemberConnectOrNames(approverNames),
                                    )
                                } else {
                                    notAdmin
                                }
                        }
                }
            val description =
                buildAnnotatedString {
                    if (server.slug.isNotBlank()) withStyle(mono) { append(server.slug) }
                    if (reason.isNotBlank()) {
                        if (length > 0) append(" · ")
                        append(reason)
                    }
                }
            val limitAction: (@Composable () -> Unit)? =
                if (atLimit && offersUpgrade) {
                    val canUpgrade = limit.canUpgrade
                    val a11yLabel =
                        if (canUpgrade) {
                            stringResource(Res.string.external_invite_connect_upgrade_server, serverDisplayName)
                        } else {
                            stringResource(Res.string.joint_member_connect_row_ask_owner_server, serverDisplayName)
                        }
                    {
                        RaftButton(
                            text = if (canUpgrade) upgradeLabel else askOwnerLabel,
                            onClick = { onUpgradeServer(server.id) },
                            variant = RaftButtonVariant.Default,
                            size = RaftButtonSize.Md,
                            enabled = !sheet.busy,
                            modifier =
                                Modifier
                                    .testTag(if (canUpgrade) JointMemberConnectUpgradeTag else JointMemberConnectAskOwnerTag)
                                    .semantics { contentDescription = a11yLabel },
                        )
                    }
                } else {
                    null
                }
            RaftChoiceListItem(
                value = server.id,
                label = serverDisplayName,
                descriptions = listOf(description),
                enabled = state.memberConnectRowSelectable(row),
                searchText = "${server.name} ${server.slug}",
                media = { JointMemberConnectServerAvatar(server) },
                trailingAction = limitAction,
            )
        }
    val commitLabel =
        if (sheet.selected.size <= 1) {
            stringResource(Res.string.joint_member_connect_commit_one)
        } else {
            stringResource(Res.string.joint_member_connect_commit_many, sheet.selected.size)
        }
    RaftDesignSystemTheme {
        RaftDialogSheet(
            onDismissRequest = { if (!sheet.busy) onDismiss() },
            title = stringResource(Res.string.joint_member_connect_title),
            commitLabel = if (sheet.busy) stringResource(Res.string.joint_member_connect_connecting) else commitLabel,
            onCommit = onSubmit,
            commitEnabled = sheet.selected.isNotEmpty() && !sheet.closed && !sheet.busy,
            commitLoading = sheet.busy,
            closeLabel = stringResource(Res.string.common_close),
            cancelLabel = stringResource(if (sheet.done.isNotEmpty()) Res.string.common_close else Res.string.common_cancel),
            closeEnabled = !sheet.busy,
            modifier = Modifier.testTag(JointMemberConnectSheetTag),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (sheet.guestEntry) {
                    RaftText(
                        stringResource(Res.string.joint_member_connect_guest_entry_why),
                        modifier = Modifier.testTag(JointMemberConnectGuestEntryTag),
                        style = RaftTheme.typography.callout,
                        color = RaftTheme.colors.foregroundMuted,
                    )
                }
                RaftText(
                    stringResource(Res.string.joint_member_connect_description, host),
                    style = RaftTheme.typography.callout,
                    color = RaftTheme.colors.foregroundMuted,
                )
                if (sheet.lines.isNotEmpty()) {
                    JointMemberConnectLinesBanner(
                        lines = sheet.lines,
                        traceId = sheet.traceId,
                        nameOf = nameOf,
                        channel = channel,
                        host = host,
                        tag = JointMemberConnectErrorTag,
                    )
                }
                RaftMultiChoiceList(
                    items = items,
                    values = sheet.selected,
                    onValuesChange = onSelectionChange,
                    query = query,
                    onQueryChange = { query = it },
                    searchPlaceholder = stringResource(Res.string.joint_member_connect_search),
                    clearSearchLabel = stringResource(Res.string.joint_member_connect_clear_search),
                    noMatchLabel = stringResource(Res.string.common_no_matches),
                    emptyLabel = stringResource(Res.string.common_no_matches),
                    enabled = !sheet.busy,
                )
                if (sheet.selected.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().testTag(JointMemberConnectConsequenceTag),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RaftText(
                            stringResource(Res.string.joint_member_connect_visibility, jointMemberConnectNames(selectedNames)),
                            style = RaftTheme.typography.callout,
                            color = RaftTheme.colors.foregroundMuted,
                        )
                        if (state.memberConnectToPrivate) {
                            RaftText(
                                stringResource(Res.string.joint_member_connect_becomes_shared, channel),
                                style = RaftTheme.typography.callout,
                                color = RaftTheme.colors.foregroundMuted,
                            )
                        }
                        if (state.memberConnectGuest) {
                            RaftText(
                                stringResource(Res.string.joint_member_connect_guest, channel, selectedNames.firstOrNull().orEmpty()),
                                modifier = Modifier.testTag(JointMemberConnectGuestTag),
                                style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
                                color = RaftTheme.colors.foreground,
                            )
                        }
                    }
                }
                if (sheet.guestEntry) {
                    RaftButton(
                        text = stringResource(Res.string.joint_member_connect_guest_create_server),
                        onClick = onOpenCreateServer,
                        variant = RaftButtonVariant.Ghost,
                        size = RaftButtonSize.Md,
                        enabled = !sheet.busy && !sheet.closed,
                        modifier = Modifier.testTag(JointMemberConnectCreateServerTag),
                    )
                }
            }
        }
    }
}

/**
 * Web `ConnectNewServerForm` (slock #16353/#16375): name & slug fields inside the same sheet,
 * submitting `POST channels/:id/connect-new-server`.
 */
@Composable
private fun JointMemberConnectNewServerSheet(
    form: MemberConnectNewServerFormState,
    channel: String,
    host: String,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onSlugChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val editable = !form.busy && !form.kept
    RaftDesignSystemTheme {
        RaftDialogSheet(
            onDismissRequest = { if (!form.busy) onDismiss() },
            title = stringResource(Res.string.external_invite_new_server_title),
            commitLabel =
                if (form.busy) {
                    stringResource(Res.string.external_invite_new_server_creating)
                } else {
                    stringResource(Res.string.external_invite_new_server_commit)
                },
            onCommit = onSubmit,
            commitEnabled = editable,
            commitLoading = form.busy,
            closeLabel = stringResource(Res.string.common_close),
            cancelLabel = stringResource(Res.string.external_invite_back),
            closeEnabled = !form.busy,
            onBack = onBack,
            backLabel = stringResource(Res.string.external_invite_back),
            modifier = Modifier.testTag(JointMemberConnectNewServerFormTag),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                RaftText(
                    stringResource(Res.string.external_invite_new_server_description, channel, host),
                    style = RaftTheme.typography.callout,
                    color = RaftTheme.colors.foregroundMuted,
                )
                if (form.lines.isNotEmpty()) {
                    JointMemberConnectLinesBanner(
                        lines = form.lines,
                        traceId = form.traceId,
                        nameOf = emptyMap(),
                        channel = channel,
                        host = host,
                        tag = JointMemberConnectNewServerErrorTag,
                        defaultTarget = form.name.trim(),
                    )
                }
                RaftField(
                    label = stringResource(Res.string.external_invite_new_server_name_label),
                    error = form.fieldErrors.name?.let { newServerFieldErrorText(it) },
                ) {
                    RaftTextField(
                        value = form.name,
                        onValueChange = onNameChange,
                        modifier = Modifier.fillMaxWidth().testTag(JointMemberConnectNewServerNameTag),
                        placeholder = stringResource(Res.string.external_invite_new_server_name_placeholder),
                        keyboardOptions = RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions,
                        enabled = editable,
                        invalid = form.fieldErrors.name != null,
                    )
                }
                RaftField(
                    label = stringResource(Res.string.external_invite_new_server_slug_label),
                    description = stringResource(Res.string.external_invite_new_server_slug_help),
                    error = form.fieldErrors.slug?.let { newServerFieldErrorText(it) },
                ) {
                    RaftTextField(
                        value = form.slug,
                        onValueChange = onSlugChange,
                        modifier = Modifier.fillMaxWidth().testTag(JointMemberConnectNewServerSlugTag),
                        keyboardOptions = RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions,
                        enabled = editable,
                        invalid = form.fieldErrors.slug != null,
                    )
                }
            }
        }
    }
}

@Composable
private fun JointMemberConnectLinesBanner(
    lines: List<MemberConnectLine>,
    traceId: String?,
    nameOf: Map<String, String>,
    channel: String,
    host: String,
    tag: String,
    defaultTarget: String = "",
) {
    val text =
        lines.map { line ->
            when (line) {
                is MemberConnectLine.Connected -> {
                    val names = jointMemberConnectNames(line.serverIds.map { nameOf[it].orEmpty() })
                    if (line.serverIds.size == 1) {
                        stringResource(Res.string.joint_member_connect_partial_one, names)
                    } else {
                        stringResource(Res.string.joint_member_connect_partial_many, names)
                    }
                }
                is MemberConnectLine.Refused ->
                    line.serverWords ?: jointMemberConnectMessage(line.message, nameOf[line.serverId].orEmpty(), channel, host)
                is MemberConnectLine.Request ->
                    line.serverWords ?: jointMemberConnectMessage(line.message, defaultTarget, channel, host)
                is MemberConnectLine.NewServerKept ->
                    stringResource(Res.string.external_invite_new_server_kept, line.serverName)
            }
        }
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftBanner(
            description = text.joinToString("\n"),
            status = RaftBannerStatus.Warning,
            size = RaftBannerSize.Sm,
            modifier = Modifier.fillMaxWidth(),
        )
        RaftTraceIdNote(traceId)
    }
}

@Composable
private fun JointMemberConnectServerAvatar(server: MemberConnectServer) = RaftServerListRowAvatar(server.id, server.name, server.avatarUrl)

/** Web `AvatarSlot context="list-row" type="server"`: the server's image, else its initial. */
@Composable
internal fun RaftServerListRowAvatar(serverId: String, name: String, avatarUrl: String?) {
    val initial = name.trim().take(1).uppercase().ifBlank { "S" }
    val source = avatarUrl?.takeIf { it.isNotBlank() }
    RaftAvatar(
        type = RaftAvatarType.Server,
        size = RaftAvatarSize.Md,
        initials = initial,
        image =
            source?.let { url ->
                @Composable {
                    RaftAvatarHttpImage(
                        source = url,
                        modifier = Modifier.fillMaxSize(),
                        cacheSubject = RaftAvatarCacheSubject.Server(serverId),
                        cacheServerId = serverId,
                        serverDiagnosticsId = serverId,
                        fallback = { RaftAvatar(type = RaftAvatarType.Server, size = RaftAvatarSize.Md, initials = initial) },
                        placeholder = {},
                    )
                }
            },
    )
}
