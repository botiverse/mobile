package build.raft.app

import build.raft.app.data.RaftNotificationLevelSetting
import build.raft.app.data.RaftSidebarAction
import build.raft.app.data.RaftSidebarRowKind
import build.raft.app.model.MemberKind

/**
 * Pure event-mapping helpers for `sidebar_action_selected`
 * (`botiverse/slock` #16056 + #16067, `#proj-mobile:7e5fdb23`).
 */
internal fun RaftHomeConversationItem.sidebarRowKind(): RaftSidebarRowKind =
    when {
        agentLounge != null -> RaftSidebarRowKind.Lounge
        isDirectMessageConversation() ->
            if (peerType.equals("agent", ignoreCase = true)) RaftSidebarRowKind.AgentDm
            else RaftSidebarRowKind.Dm
        else -> RaftSidebarRowKind.Channel
    }

/** Pinned member row without a materialized DM yet (`dm` for humans, `agent_dm` for agents). */
internal fun RaftHomePinnedMemberItem.sidebarRowKind(): RaftSidebarRowKind =
    if (identity.kind == MemberKind.Agent) RaftSidebarRowKind.AgentDm else RaftSidebarRowKind.Dm

/** Maps a Home row long-press menu item id; submenu/dialog openers return null. */
internal fun homeConversationMenuSidebarAction(
    actionId: String,
    pinTargetIsPinned: Boolean,
): RaftSidebarAction? =
    when (actionId) {
        "mark-read", "mark-all-read" -> RaftSidebarAction.MarkRead
        "mark-unread" -> RaftSidebarAction.MarkUnread
        "mute-channel" -> RaftSidebarAction.Mute
        "unmute-channel" -> RaftSidebarAction.Unmute
        "toggle-pin" -> if (pinTargetIsPinned) RaftSidebarAction.Unpin else RaftSidebarAction.Pin
        "close-dm" -> RaftSidebarAction.Close
        "archive-channel" -> RaftSidebarAction.Archive
        "leave-channel" -> RaftSidebarAction.Leave
        "sort-channels" -> RaftSidebarAction.OpenReorder
        else -> null
    }

/** Maps a choice applied inside the Home row's Notifications dialog (`row_menu`). */
internal fun homeNotificationChoiceSidebarAction(choice: RaftNotificationChoice): RaftSidebarAction? =
    when (choice) {
        RaftNotificationChoice.Mute -> RaftSidebarAction.Mute
        is RaftNotificationChoice.Level ->
            when (choice.setting) {
                RaftNotificationLevelSetting.All -> RaftSidebarAction.NotifyAll
                RaftNotificationLevelSetting.Mentions -> RaftSidebarAction.NotifyMentions
                RaftNotificationLevelSetting.None -> RaftSidebarAction.NotifyNone
                RaftNotificationLevelSetting.Default -> null
            }
    }

/**
 * Maps a choice in the Home row's "Move to section" submenu (`row_menu`).
 * Mirrors web `SidebarRowContextMenu.tsx` (Nova #proj-mobile:7e5fdb23 rule 11):
 * Home always records `move_to_home`; picking the already-current Pinned or custom section
 * records nothing.
 */
internal fun homeMoveToSectionSidebarAction(
    actionId: String,
    placement: ChannelSectionPlacement,
): RaftSidebarAction? {
    if (actionId == "create") return RaftSidebarAction.NewSection
    return when (val target = raftHomeMoveToSectionValue(actionId)) {
        ChannelSectionValue.Home -> RaftSidebarAction.MoveToHome
        ChannelSectionValue.Pinned ->
            if (placement.current == ChannelSectionValue.Pinned || !placement.canPin) null
            else RaftSidebarAction.MoveToPinned
        is ChannelSectionValue.Custom ->
            if (placement.current == target || placement.customSections.none { it.id == target.sectionId }) null
            else RaftSidebarAction.MoveToSection
        null -> null
    }
}

/** Maps a sidebar section sort mode (`az` -> `sort_alpha`). */
internal fun homeSortModeSidebarAction(mode: RaftSidebarSortMode): RaftSidebarAction =
    when (mode) {
        RaftSidebarSortMode.Manual -> RaftSidebarAction.SortManual
        RaftSidebarSortMode.Recent -> RaftSidebarAction.SortRecent
        RaftSidebarSortMode.Az -> RaftSidebarAction.SortAlpha
    }

/** Maps a Home section long-press menu item id (`section_menu`). */
internal fun homeSectionMenuSidebarAction(
    actionId: String,
    hideEmptySections: Boolean,
): RaftSidebarAction? =
    when {
        actionId.startsWith("sort:") ->
            homeSortModeSidebarAction(RaftSidebarSortMode.fromWireValue(actionId.removePrefix("sort:")))
        actionId == "toggle-hide-empty" ->
            if (hideEmptySections) RaftSidebarAction.HideEmptyOff else RaftSidebarAction.HideEmptyOn
        actionId == "create" -> RaftSidebarAction.NewSection
        actionId == "create-channel" -> RaftSidebarAction.CreateChannel
        actionId == "create-external-channel" -> RaftSidebarAction.CreateExternalChannel
        actionId == "rename" -> RaftSidebarAction.RenameSection
        actionId == "delete" -> RaftSidebarAction.DeleteSection
        else -> null
    }
