package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.data.SettingsServerSidebarCustomSection
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftSelect
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/**
 * Where a channel sits in MY sidebar, as web ChannelSectionSelect models it: its home section
 * (Channels, or Joint Channels), Pinned, or one of my custom sections.
 */
internal sealed interface ChannelSectionValue {
    data object Home : ChannelSectionValue

    data object Pinned : ChannelSectionValue

    data class Custom(val sectionId: String) : ChannelSectionValue
}

/**
 * The sidebar state "Move to section" reads (web `useConversationSidebarSection` + the
 * sidebar order): my custom sections, whether the channel is pinned, and its custom-section
 * placement. Per person and per server (`/servers/:id/sidebar-order`).
 */
internal data class ChannelSectionPlacement(
    val customSections: List<SettingsServerSidebarCustomSection>,
    val pinned: Boolean,
    val placementSectionId: String?,
    val canPin: Boolean = true,
    val joint: Boolean = false,
) {
    /** Pinned wins over a placement; a placement in a deleted section shows as home (web). */
    val current: ChannelSectionValue
        get() =
            when {
                pinned -> ChannelSectionValue.Pinned
                placementSectionId != null && customSections.any { it.id == placementSectionId } ->
                    ChannelSectionValue.Custom(placementSectionId)
                else -> ChannelSectionValue.Home
            }

    /** Web options: home first, Pinned (when it can be pinned), then my sections in order. */
    val options: List<ChannelSectionValue>
        get() =
            buildList {
                add(ChannelSectionValue.Home)
                if (canPin) add(ChannelSectionValue.Pinned)
                customSections.forEach { add(ChannelSectionValue.Custom(it.id)) }
            }

    /** The sectionOrder id of where it sits now: a section created from here goes before it. */
    val currentSectionKey: String
        get() =
            when (val value = current) {
                ChannelSectionValue.Pinned -> "system:pinned"
                ChannelSectionValue.Home -> if (joint) "system:joint" else "system:channels"
                is ChannelSectionValue.Custom -> value.sectionId
            }
}

/** One sidebar write a pick makes (each maps to a SharedHomeIntent). */
internal sealed interface ChannelSectionMove {
    data object Pin : ChannelSectionMove

    data object Unpin : ChannelSectionMove

    data object ClearPlacement : ChannelSectionMove

    data class MoveTo(val sectionId: String) : ChannelSectionMove
}

/**
 * What picking [next] writes (web ChannelSectionSelect `handleChange`): home unpins and drops
 * any placement, also an orphaned one; Pinned pins (which drops the placement); a custom
 * section moves the channel there (which unpins). Picking where it already is writes nothing.
 */
internal fun channelSectionMoves(state: ChannelSectionPlacement, next: ChannelSectionValue): List<ChannelSectionMove> =
    when (next) {
        ChannelSectionValue.Home ->
            buildList {
                if (state.pinned) add(ChannelSectionMove.Unpin)
                if (state.placementSectionId != null) add(ChannelSectionMove.ClearPlacement)
            }
        state.current -> emptyList()
        ChannelSectionValue.Pinned -> if (state.canPin) listOf(ChannelSectionMove.Pin) else emptyList()
        is ChannelSectionValue.Custom ->
            if (state.customSections.any { it.id == next.sectionId }) listOf(ChannelSectionMove.MoveTo(next.sectionId)) else emptyList()
    }

/**
 * Web ChannelPreferencesSection's first row: "Move to section", its line, and the select of
 * [ChannelSectionPlacement.options] with "Create section…" last, which opens the sidebar's New
 * section dialog and creates the section with this channel in it.
 */
@Composable
internal fun ChannelSectionSelectRow(
    state: ChannelSectionPlacement,
    enabled: Boolean,
    onMove: (List<ChannelSectionMove>) -> Unit,
    onCreate: (name: String, emoji: String?) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    val title = stringResource(Res.string.home_section_move_to)
    val homeLabel =
        if (state.joint) stringResource(Res.string.home_section_joint_channels) else stringResource(Res.string.home_section_channels)
    val pinnedLabel = stringResource(Res.string.home_section_pinned)
    val createLabel = stringResource(Res.string.channel_settings_section_create)
    // null is the "Create section…" action at the end of the list.
    val options: List<ChannelSectionValue?> = state.options + null
    val labelOf: (ChannelSectionValue?) -> String = { value ->
        when (value) {
            null -> createLabel
            ChannelSectionValue.Home -> homeLabel
            ChannelSectionValue.Pinned -> pinnedLabel
            is ChannelSectionValue.Custom ->
                state.customSections.firstOrNull { it.id == value.sectionId }?.sidebarSectionHeaderLabel().orEmpty()
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RaftText(
                title,
                style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.Medium),
                color = RaftTheme.colors.foreground
            )
            RaftText(
                stringResource(Res.string.channel_settings_section_detail),
                style = RaftTheme.typography.footnote,
                color = RaftTheme.colors.foregroundHint
            )
        }
        RaftSelect(
            value = state.current,
            options = options,
            onValueChange = { picked ->
                if (picked == null) {
                    creating = true
                } else {
                    channelSectionMoves(state, picked).takeIf { it.isNotEmpty() }?.let(onMove)
                }
            },
            optionLabel = labelOf,
            enabled = enabled,
            label = title,
        )
    }
    if (creating) {
        RaftHomeSectionDialog(
            title = stringResource(Res.string.home_section_new_title),
            submitLabel = stringResource(Res.string.home_section_create),
            nameLabel = stringResource(Res.string.home_section_name_label),
            namePlaceholder = stringResource(Res.string.home_section_name_placeholder),
            initialName = "",
            initialEmoji = "",
            onDismiss = { creating = false },
            onSubmit = { name, emoji ->
                creating = false
                if (name.isNotBlank()) onCreate(name.trim(), emoji?.trim()?.takeIf { it.isNotEmpty() })
            },
        )
    }
}

/**
 * What the channel settings drawer shows besides the name form and the actions (web
 * ChannelOverflowMenu members strip + ChannelPreferencesSection), with what it does. Null
 * parts are hidden.
 */
internal data class ThreadChannelSettingsPanel(
    /** The channel as Home knows it; with [permissions] it gates Info (web `showInfoSection`). */
    val channel: build.raft.app.data.HomeChannelRow? = null,
    val permissions: ChannelSettingsPermissions? = null,
    val members: build.raft.app.data.SharedServerChannelMembersSlice? = null,
    val canAddMembers: Boolean = false,
    val agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    val section: ChannelSectionPlacement? = null,
    /** Already filtered to servers with notification levels ([whenLevelsSupported]). */
    val notificationLevelTarget: RaftNotificationLevelTarget? = null,
    val refreshNotificationLevels: Boolean = true,
    val longMessageCollapse: RaftLongMessageCollapseConversationSetting? = null,
    val writeActionsEnabled: Boolean = true,
    val onOpenMembers: () -> Unit = {},
    val onAddMember: () -> Unit = {},
    val onMoveSection: (List<ChannelSectionMove>) -> Unit = {},
    val onCreateSection: (name: String, emoji: String?) -> Unit = { _, _ -> },
)

/** Web `showInfoSection`; without the channel's Home row, the form shows (the old drawer did). */
internal fun ThreadChannelSettingsPanel.showsInfoForm(): Boolean {
    val row = channel ?: return true
    val granted = permissions ?: return true
    return channelSettingsShowsInfo(row, granted)
}

/** Web: #all keeps its name; without metadata rights the name is read only. */
internal fun ThreadChannelSettingsPanel.nameEditable(): Boolean {
    val row = channel ?: return true
    if (channelSettingsIsAllChannel(row)) return false
    return permissions?.editMetadata ?: true
}

/**
 * The drawer's panel inputs from what the conversation already holds: the channel's Home row and
 * the server role (Info gating, web `effectiveCapabilities`), the members slice the header
 * shows, the sidebar order (Move to section), the level target (Notifications, only where the
 * server has levels) and the conversation's collapse setting. Sidebar writes go to Home's store
 * ([SharedHomeIntent.TogglePinned], [SharedHomeIntent.MoveConversationToSection],
 * [SharedHomeIntent.CreateSidebarSection]), which applies them at once and rolls back on failure.
 */
@Composable
internal fun rememberThreadChannelSettingsPanel(
    homeContent: build.raft.app.data.SharedHomeContentState,
    serverId: build.raft.app.model.ServerId?,
    channelId: build.raft.app.model.ChannelId?,
    memberSlice: build.raft.app.data.SharedServerChannelMembersSlice,
    pinState: ThreadConversationPinUiState?,
    activityMuteState: ThreadActivityMuteUiState?,
    longMessageCollapse: RaftLongMessageCollapseConversationSetting?,
    agentStatusResolver: RaftAgentStatusResolver,
    writeActionsEnabled: Boolean,
    onHomeIntent: (build.raft.app.data.SharedHomeIntent) -> Unit,
): ThreadChannelSettingsPanel? {
    if (channelId == null) return null
    val snapshot = (homeContent as? build.raft.app.data.SharedHomeContentState.Content)?.snapshot
    val channel = snapshot?.channels?.firstOrNull { it.channelId == channelId }
    val permissions = channel?.let { channelSettingsPermissions(it, snapshot?.server?.permissions?.canManageSettings == true) }
    val sidebarOrder = snapshot?.sidebarOrder
    val section =
        pinState?.let { pin ->
            ChannelSectionPlacement(
                customSections = sidebarOrder?.customSections.orEmpty(),
                pinned = pin.pinned,
                placementSectionId =
                    sidebarOrder?.sectionPlacements
                        ?.firstOrNull { it.kind == build.raft.app.data.SIDEBAR_SECTION_ITEM_KIND_CHANNEL && it.id == pin.conversationId }
                        ?.sectionId,
                canPin = pin.available,
                joint = channel?.let(::channelSettingsIsJoint) == true,
            )
        }
    val muted = activityMuteState?.muted == true
    val levelTarget =
        serverId?.let {
            RaftNotificationLevelTarget(
                serverId = it.value,
                scope = build.raft.app.data.RaftNotificationLevelScope.Channel,
                id = channelId.value,
                muted = muted,
                // A muted channel keeps its Unmute even where mute is no longer offered.
                muteSupported = activityMuteState?.supported == true || muted,
            )
        }.whenLevelsSupported()
    return ThreadChannelSettingsPanel(
        channel = channel,
        permissions = permissions,
        members = memberSlice,
        canAddMembers = channelSettingsCanAddMembers(memberSlice, channel),
        agentStatusResolver = agentStatusResolver,
        section = section,
        notificationLevelTarget = levelTarget,
        longMessageCollapse = longMessageCollapse,
        writeActionsEnabled = writeActionsEnabled,
        onMoveSection = { moves ->
            if (writeActionsEnabled && pinState != null) {
                channelSectionHomeIntents(pinState, moves).forEach(onHomeIntent)
            }
        },
        onCreateSection = { name, emoji ->
            if (writeActionsEnabled && pinState != null) {
                onHomeIntent(
                    build.raft.app.data.SharedHomeIntent.CreateSidebarSection(
                        name = name,
                        emoji = emoji,
                        moveConversationId = pinState.conversationId,
                        insertBeforeSectionId = section?.currentSectionKey,
                    )
                )
            }
        },
    )
}

/** Web `canAddMembers`: the server said so and the channel is not archived. */
internal fun channelSettingsCanAddMembers(
    slice: build.raft.app.data.SharedServerChannelMembersSlice,
    channel: build.raft.app.data.HomeChannelRow?,
): Boolean = slice.canAddMembersKnown && slice.canAddMembers && channel?.archived != true

/** The Home intents a Move to section pick dispatches, in order. */
internal fun channelSectionHomeIntents(
    pin: ThreadConversationPinUiState,
    moves: List<ChannelSectionMove>,
): List<build.raft.app.data.SharedHomeIntent> =
    moves.map { move ->
        when (move) {
            ChannelSectionMove.Pin,
            ChannelSectionMove.Unpin ->
                build.raft.app.data.SharedHomeIntent.TogglePinned(
                    conversationId = pin.conversationId,
                    pinnedKind = pin.pinnedKind,
                    pinnedId = pin.pinnedId,
                )
            ChannelSectionMove.ClearPlacement ->
                build.raft.app.data.SharedHomeIntent.MoveConversationToSection(pin.conversationId, null)
            is ChannelSectionMove.MoveTo ->
                build.raft.app.data.SharedHomeIntent.MoveConversationToSection(pin.conversationId, move.sectionId)
        }
    }

/**
 * Whether the drawer offers a channel action ("visibility", "archive", "delete"), with the web's
 * gates (EditChannelDialog `visibilityActionRow` / `archiveActionRow` / `deleteActionRow`) once
 * the channel's Home row and permissions are known; before that, what the conversation offers.
 */
internal fun ThreadChannelSettingsPanel.allowsChannelAction(actionId: String): Boolean {
    val row = channel ?: return true
    val granted = permissions ?: return true
    return when (actionId) {
        "visibility" -> channelSettingsShowsVisibilityAction(row, granted)
        // The drawer has Archive only, so it shows while the channel is live.
        "archive" -> channelSettingsShowsArchiveAction(row, granted) && !row.archived
        "delete" -> channelSettingsShowsDeleteAction(row, granted) && channelSettingsCanDelete(row)
        else -> false
    }
}
