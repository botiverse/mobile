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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftChoiceListItem
import build.raft.designsystem.components.RaftDialogSheet
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMultiChoiceList
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

internal const val JointMemberConnectMenuTag = "joint-member-connect-menu"
internal const val JointMemberConnectMenuItemTag = "joint-member-connect-menu-item"
internal const val JointMemberConnectSheetTag = "joint-member-connect-sheet"
internal const val JointMemberConnectErrorTag = "joint-member-connect-error"
internal const val JointMemberConnectConsequenceTag = "joint-member-connect-consequence"
internal const val JointMemberConnectGuestTag = "joint-member-connect-guest"

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
 * rest listed with why not. The line under the title says they connect now with no host
 * approval; under the list, what people in those servers see and, for a private channel, that it
 * becomes shared and the servers connected see its earlier messages (#13888). The commit is in
 * the sheet's head. A refusal stays in the sheet as a warning banner with the answer's trace ID.
 */
@Composable
internal fun JointMemberConnectSheet(
    state: JointChannelConnectionsState,
    onDismiss: () -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
    onSubmit: () -> Unit,
) {
    val sheet = state.memberConnectSheet ?: return
    val rows = state.memberConnectRows
    val channelName = state.connections?.channelName?.removePrefix("#").orEmpty()
    val channel = "#$channelName"
    val host = state.memberConnectHostName
    val nameOf = rows.associate { it.server.id to it.server.name.ifBlank { it.server.slug } }
    var query by remember { mutableStateOf("") }
    val selectedNames = sheet.selected.mapNotNull { nameOf[it] }
    val connectedNow = stringResource(Res.string.joint_member_connect_row_connected_now)
    val owner = stringResource(Res.string.joint_member_connect_row_owner)
    val admin = stringResource(Res.string.joint_member_connect_row_admin)
    val alreadyConnected = stringResource(Res.string.joint_member_connect_row_already_connected)
    val notAdmin = stringResource(Res.string.joint_member_connect_row_not_admin)
    val items =
        rows.map { row ->
            val server = row.server
            val reason =
                when {
                    server.id in sheet.done -> connectedNow
                    else ->
                        when (state.memberConnectRowState(row)) {
                            MemberConnectRowState.Eligible -> if (server.role == "owner") owner else admin
                            MemberConnectRowState.Connected -> alreadyConnected
                            MemberConnectRowState.NotAdmin -> notAdmin
                        }
                }
            RaftChoiceListItem(
                value = server.id,
                label = server.name.ifBlank { server.slug },
                descriptions = listOf(AnnotatedString(listOf(server.slug, reason).filter(String::isNotBlank).joinToString(" · "))),
                enabled = state.memberConnectRowSelectable(row),
                searchText = "${server.name} ${server.slug}",
                media = { JointMemberConnectServerAvatar(server) },
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
                RaftText(
                    stringResource(Res.string.joint_member_connect_description, host),
                    style = RaftTheme.typography.callout,
                    color = RaftTheme.colors.foregroundMuted,
                )
                if (sheet.lines.isNotEmpty()) {
                    val text =
                        sheet.lines.map { line ->
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
                                    jointMemberConnectMessage(line.message, nameOf[line.serverId].orEmpty(), channel, host)
                                is MemberConnectLine.Request -> jointMemberConnectMessage(line.message, "", channel, host)
                            }
                        }
                    Column(Modifier.fillMaxWidth().testTag(JointMemberConnectErrorTag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        RaftBanner(
                            description = text.joinToString("\n"),
                            status = RaftBannerStatus.Warning,
                            size = RaftBannerSize.Sm,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        RaftTraceIdNote(sheet.traceId)
                    }
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
            }
        }
    }
}

/** Web `AvatarSlot context="list-row" type="server"`: the server's image, else its initial. */
@Composable
private fun JointMemberConnectServerAvatar(server: MemberConnectServer) {
    val initial = server.name.trim().take(1).uppercase().ifBlank { "S" }
    val source = server.avatarUrl?.takeIf { it.isNotBlank() }
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
                        cacheSubject = RaftAvatarCacheSubject.Server(server.id),
                        cacheServerId = server.id,
                        serverDiagnosticsId = server.id,
                        fallback = { RaftAvatar(type = RaftAvatarType.Server, size = RaftAvatarSize.Md, initials = initial) },
                        placeholder = { RaftAvatar(type = RaftAvatarType.Server, size = RaftAvatarSize.Md, initials = initial) },
                    )
                }
            },
    )
}
