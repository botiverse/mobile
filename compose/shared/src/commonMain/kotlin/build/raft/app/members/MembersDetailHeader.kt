package build.raft.app

import build.raft.designsystem.components.LocalRaftPaneGridHeadTabs
import build.raft.designsystem.components.RaftBackButtonDefaults
import build.raft.designsystem.components.LocalRaftBarButtons
import build.raft.designsystem.components.RaftPaneGridHeadRowTabs
import build.raft.designsystem.components.raftPaneGridFitHead
import build.raft.designsystem.components.raftPaneGridHeadShownActions
import build.raft.app.resources.*
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.setValue
import build.raft.app.components.raftSoftWash
import build.raft.app.components.RaftSlotIcon
import build.raft.app.components.raftCardSurface
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftRadioGroup
import build.raft.designsystem.components.RaftRadioRow
import build.raft.designsystem.components.RaftTab
import build.raft.designsystem.components.RaftTabStrip
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.app.components.RaftCheckMarker
import build.raft.app.components.RaftCheckMarkerShape
import build.raft.app.components.RaftCheckMarkerTone
import build.raft.app.data.AgentArchiveWorkspaceChoice
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.avatarWireValueForAvatarSlot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftPanelNotice
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftPresence
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import build.raft.designsystem.components.raftDetectDragGesturesAfterHold
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.designsystem.components.raftCopyable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.data.map
import build.raft.app.model.isExternalAgentRuntime
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.components.RaftHoverTier
import androidx.compose.ui.semantics.selected

// raft-ui PanelHeader on a phone: Back and the trailing action are 44dp ghost icon buttons
// sitting 3dp from the screen edges, so their 22dp glyphs mirror each other.
private val MembersHumanHeaderEdgeInset = 3.dp

private val MembersHumanHeaderContentSpacing = 6.dp

// A profile head that starts without Back (the first page of the wide layout's profile column):
// the avatar on the 16dp content margin (raft-ui head edges), as the channel details' title under it.
private val MembersHeaderContentMargin = 16.dp

/**
 * The human profile head (web HumanDetailPanel `PanelHeader`): Back, the `md` avatar sticker,
 * the name (PanelTitle) over the `@handle` (Space Mono 12/16 hint), and the Message action
 * as a ghost PanelAction. In the wide layout's profile column (a side pane, [LocalRaftSidePanePage])
 * the head ends in the pane's Close and has Back only over a page of the cell's own stack.
 */
@Composable
internal fun MembersHumanDetailHeader(
    member: MemberProfile,
    currentUserId: String?,
    currentServerId: String?,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    viewerRole: String? = null,
    onBack: () -> Unit,
    onOpenDirectMessage: () -> Unit,
    directMessageOpening: Boolean = false
) {
    val canMessage =
        membersDirectMessageEligibility(
            member = member,
            currentUserId = currentUserId,
            currentServerId = currentServerId,
            viewerRole = viewerRole
        ) != MembersDirectMessageEligibility.Unavailable
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val messageLabel = stringResource(Res.string.member_label_message)
    val showsBack = raftSidePaneShowsBack()
    val paneCloses = LocalRaftSidePanePage.current != null
    val headRowTabs = LocalRaftPaneGridHeadTabs.current
    val messageButton: @Composable () -> Unit = {
        RaftIconButton(
            onClick = onOpenDirectMessage,
            tooltip = messageLabel,
            variant = RaftButtonVariant.Ghost,
            size = RaftIconButtonSize.Lg,
            loading = directMessageOpening
        ) {
            // raft-ui `DirectMessageIcon`, 22px (web HumanDetailPanel).
            MembersHumanGlyph(RaftLucideIconName.DirectMessage, 22.dp)
        }
    }
    RaftTopPageHeader(
        title = member.displayName,
        // PROTOTYPE (native nav bar): the @handle under the name, Message as the bar button.
        nativeSubtitle = member.detailHandle,
        nativeActions =
            if (canMessage) {
                listOf(RaftNativeNavBarAction("member-message", messageLabel, "message", onOpenDirectMessage, icon = RaftLucideIconName.DirectMessage))
            } else {
                emptyList()
            },
        nativeOnBack = onBack,
        backgroundColor = RaftColor.Canvas,
        contentStartPadding = if (showsBack) MembersHumanHeaderEdgeInset else MembersHeaderContentMargin,
        contentEndPadding = if (canMessage || paneCloses) MembersHumanHeaderEdgeInset else RaftTopPageHeaderContentEndPadding,
        contentSpacing = MembersHumanHeaderContentSpacing,
        showBodyTopBanner = false,
        titleLeadingContent = {
            RaftPaneAwareBackButton(onClick = onBack)
            if (headRowTabs == null) {
                MembersStickerAvatar(member, RaftAvatarSize.Md, memberAvatarResolver)
            }
        },
        titleContent = {
            Column(modifier = Modifier.weight(1f)) {
                RaftText(
                    text = member.displayName,
                    style = typography.headline,
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.raftCopyable(member.displayName)
                )
                RaftText(
                    text = member.detailHandle,
                    style = typography.monoMeta,
                    color = colors.foregroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.raftCopyable(member.detailHandle)
                )
            }
        },
        trailingContent =
            when {
                paneCloses -> {
                    {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (canMessage) messageButton()
                            RaftSidePaneCloseButton()
                        }
                    }
                }
                canMessage -> {
                    { messageButton() }
                }
                else -> null
            }
    )
}

@Composable
internal fun MembersDetailHeader(
    member: MemberProfile,
    currentUserId: String?,
    currentServerId: String?,
    detail: AgentDetailSnapshot?,
    deleted: Boolean,
    canManage: Boolean,
    /** Web `canMessageAgent`: false for an agent of another server of a joint channel. */
    canMessage: Boolean = true,
    agentStatusIndicatorState: RaftAgentStatusIndicatorState?,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    onBack: () -> Unit,
    onOpenDirectMessage: () -> Unit,
    directMessageOpening: Boolean = false,
    /** Agent Lounge v0: opens the agent's Lounge; null hides the action (flag off, Guest, or no Lounge for it). */
    onOpenLounge: (() -> Unit)? = null,
    loungeOpening: Boolean = false,
    onCopyMemberId: () -> Unit,
    onManagementMenuAnchorResolverReady: (RaftPopoverAnchorResolver) -> Unit,
    onRequestManagementMenu: (RaftPopoverAnchor?) -> Unit
) {
    val summary = detail?.summary
    val directMessageEligibility =
        if (!canMessage) {
            MembersDirectMessageEligibility.Unavailable
        } else {
            membersDirectMessageEligibility(
                member = member,
                currentUserId = currentUserId,
                currentServerId = currentServerId,
                deleted = deleted
            )
        }
    val threeDotButtonAnchor =
        remember(member.id.value) { RaftLiveRootAnchorRef(identity = "member:${member.id.value}:management") }
    val threeDotButtonAnchorResolver =
        remember(threeDotButtonAnchor) { threeDotButtonAnchor.currentCaptureResolver(RaftPopoverLocalPoint.BottomEnd) }
    val currentOnManagementMenuAnchorResolverReady by
        rememberUpdatedState(onManagementMenuAnchorResolverReady)
    val headerMetaLabel =
        when {
            deleted -> stringResource(Res.string.member_status_deleted)
            // Web AgentDetailHeader: the weaker @handle under the name (cindyz #737), in mono.
            member.kind == MemberKind.Agent -> member.detailHandle
            else -> null
        }
    val topSafeArea = raftTopSafeAreaPadding()
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    // PROTOTYPE (native nav bar): name and meta on the host bar, Message and the management ⋯ as
    // its buttons. The ⋯ opens the same management menu, anchored under the bar's trailing edge.
    val nativeMessageLabel = stringResource(Res.string.member_label_message)
    val nativeLoungeLabel = stringResource(Res.string.agent_lounge_label)
    val nativeMoreLabel = stringResource(Res.string.tab_more)
    val nativeBar = LocalRaftNativeNavBar.current
    val headRowTabs = LocalRaftPaneGridHeadTabs.current
    if (
        headRowTabs == null &&
        nativeBar != null &&
        raftNativeNavBarPublish(
            title = member.displayName,
            subtitle = headerMetaLabel,
            actions =
                buildList {
                    if (onOpenLounge != null) {
                        add(RaftNativeNavBarAction("member-lounge", nativeLoungeLabel, "person.2", onOpenLounge, icon = RaftLucideIconName.Users))
                    }
                    if (directMessageEligibility != MembersDirectMessageEligibility.Unavailable) {
                        add(RaftNativeNavBarAction("member-message", nativeMessageLabel, "message", onOpenDirectMessage, icon = RaftLucideIconName.DirectMessage))
                    }
                    if (member.kind == MemberKind.Agent) {
                        add(
                            RaftNativeNavBarAction(
                                "member-manage",
                                nativeMoreLabel,
                                "ellipsis",
                                { onRequestManagementMenu(threeDotButtonAnchorResolver.resolve()) },
                                // Web AgentProfileOverflowMenu: EllipsisVertical, as the Compose head.
                                icon = RaftLucideIconName.EllipsisVertical,
                            )
                        )
                    }
                },
            onBack = onBack,
        )
    ) {
        Box(Modifier.fillMaxWidth().height(topSafeArea)) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp)
                    .size(1.dp)
                    .raftCaptureLiveRootAnchor(threeDotButtonAnchor) {
                        currentOnManagementMenuAnchorResolverReady(threeDotButtonAnchorResolver)
                    }
            )
        }
        return
    }
    // raft-ui ProfilePanelHeader on a phone: a 56dp head on `layer-canvas` with a `line-seam`
    // under it; Back, the 35dp avatar, the title (`text-headline` bold) over one line of
    // meta (`text-caption`, `foreground-hint`), then 44dp ghost PanelActions 2dp apart.
    // The web head's Start / Stop and Restart / Reset sit in the More menu at phone width.
    // In the wide layout's profile column (a side pane) the actions end in the pane's Close, and
    // Back shows only over a page of the cell's own stack (raftSidePaneHeadButtons).
    val showsBack = raftSidePaneShowsBack()
    val paneCloses = LocalRaftSidePanePage.current != null
    Column(
        modifier = Modifier
            .raftPaneGridFitHead()
            .fillMaxWidth()
            .background(colors.layerCanvas)
            .padding(top = topSafeArea)
    ) {
        // The head's icon-lg buttons are bar buttons: 28dp under a mouse or trackpad (sizing v12).
        CompositionLocalProvider(LocalRaftBarButtons provides true) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val startInset = if (showsBack) RaftBackButtonDefaults.EdgeInset - 2.dp else MembersHeaderContentMargin
                val endInset = RaftBackButtonDefaults.EdgeInset - 2.dp
                val hasLounge = onOpenLounge != null
                val hasMessage = directMessageEligibility != MembersDirectMessageEligibility.Unavailable
                val hasMore = member.kind == MemberKind.Agent && canManage && !deleted
                val ownCount = (if (hasLounge) 1 else 0) + (if (hasMessage) 1 else 0)
                val shownOwn =
                    if (headRowTabs != null) {
                        raftPaneGridHeadShownActions(
                            widthDp = maxWidth.value,
                            leadButtons = if (showsBack) 1 else 0,
                            own = ownCount,
                            controls = (if (hasMore) 1 else 0) + (if (paneCloses) 1 else 0),
                            menuItems = 0,
                            tabs = true,
                            buttonDp = RaftTheme.controls.barButtonSize.value,
                            startDp = startInset.value,
                            endDp = endInset.value,
                            gapDp = 2f,
                            leadDp = RaftTheme.controls.barButtonSize.value + RaftAppSpacing.Sm.value,
                            titleGapDp = RaftAppSpacing.Sm.value,
                        )
                    } else {
                        ownCount
                    }
                val showLounge = hasLounge && shownOwn >= 1
                val showMessage = hasMessage && shownOwn >= (if (hasLounge) 2 else 1)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 56 with a finger, 44 under a mouse or trackpad (sizing v12, raft-ui 0.65 desktop density).
                        .height(RaftTheme.controls.headHeight - RaftStroke.Hairline)
                        // 3dp for a finger's 44dp buttons; 8dp for the 28dp bar buttons (sizing v12). Without
                        // Back the avatar starts on the 16dp content margin.
                        .padding(start = startInset, end = endInset),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
                ) {
                    RaftPaneAwareBackButton(onClick = onBack)
                    if (headRowTabs != null) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            RaftPaneGridHeadRowTabs(
                                tabs = headRowTabs,
                                contentDescription = stringResource(Res.string.pane_grid_row_tabs),
                            )
                        }
                    } else {
                        // The web's panel head shows the avatar only; it is changed from the profile hero.
                        MembersRaftAvatar(
                            member = member,
                            size = RaftAvatarSize.Md,
                            memberAvatarResolver = memberAvatarResolver
                        )
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            RaftText(
                                text = member.displayName,
                                style = typography.headline,
                                color = colors.foreground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!headerMetaLabel.isNullOrBlank()) {
                                RaftText(
                                    text = headerMetaLabel,
                                    style = if (deleted) typography.caption else typography.monoMeta.copy(lineHeight = typography.caption.lineHeight),
                                    color = if (deleted) colors.dangerStrong else colors.foregroundHint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Agent Lounge v0 (web AgentProfileOverflowMenu `agent-profile-inline-lounge`): the
                        // Lounge button inline in the head, before Message.
                        if (showLounge && onOpenLounge != null) {
                            RaftIconButton(
                                onClick = onOpenLounge,
                                tooltip = nativeLoungeLabel,
                                variant = RaftButtonVariant.Ghost,
                                size = RaftIconButtonSize.Lg,
                                loading = loungeOpening,
                                modifier = Modifier.testTag(AgentLoungeTestTags.ProfileAction)
                            ) {
                                RaftContentLucideIcon(RaftLucideIconName.Users, size = 22.dp)
                            }
                        }
                        if (showMessage && membersHeaderMessageUsesIconOnly(member.kind)) {
                            RaftIconButton(
                                onClick = onOpenDirectMessage,
                                tooltip = stringResource(Res.string.member_label_message),
                                variant = RaftButtonVariant.Ghost,
                                size = RaftIconButtonSize.Lg,
                                loading = directMessageOpening
                            ) {
                                // raft-ui `DirectMessageIcon`, as the web head draws it.
                                RaftContentLucideIcon(RaftLucideIconName.DirectMessage, size = 22.dp)
                            }
                        } else if (showMessage) {
                            // A filled Button straight in the head ends on the content edge (raft-ui panelHeadEdges).
                            RaftButton(
                                text = stringResource(Res.string.member_label_message),
                                onClick = onOpenDirectMessage,
                                modifier = Modifier.padding(end = 7.dp),
                                loading = directMessageOpening,
                                leadingIcon = { RaftContentLucideIcon(RaftLucideIconName.DirectMessage) }
                            )
                        }
                        // The More menu holds only Start/Stop and Restart / Reset: nothing to show without
                        // manage rights or once the agent is archived.
                        if (hasMore) {
                            RaftIconButton(
                                onClick = {
                                    onRequestManagementMenu(
                                        threeDotButtonAnchorResolver.resolve()
                                    )
                                },
                                tooltip = stringResource(Res.string.tab_more),
                                variant = RaftButtonVariant.Ghost,
                                size = RaftIconButtonSize.Lg,
                                modifier =
                                    Modifier.raftCaptureLiveRootAnchor(threeDotButtonAnchor) {
                                        currentOnManagementMenuAnchorResolverReady(threeDotButtonAnchorResolver)
                                    }
                            ) {
                                // Web AgentProfileOverflowMenu: EllipsisVertical.
                                RaftContentLucideIcon(RaftLucideIconName.EllipsisVertical, size = 22.dp)
                            }
                        }
                        // web AgentDetailPanel: the side pane's Close ends the head (nothing outside one).
                        RaftSidePaneCloseButton()
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam))
    }
}

/**
 * raft-ui Avatar (the square sticker, [RaftAvatarSize.Xl] with the hard sticker shadow)
 * around the member's resolved avatar (pixel avatar, photo or the app's fallback face).
 */
@Composable
internal fun MembersRaftAvatar(
    member: MemberProfile,
    size: RaftAvatarSize,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    presence: RaftPresence? = null,
    modifier: Modifier = Modifier
) {
    val resolvedAvatarWireValue = rememberRaftMemberAvatarWireValue(memberAvatarResolver, member.id.value)
    val fallbackAvatarWireValue = remember(member.avatar, member.gravatarHash, member.kind, member.id) {
        member.avatarWireValueForAvatarSlot()
    }
    RaftAppAvatarSticker(
        kind = raftAvatarKind(member.kind),
        avatarWireValue = resolvedAvatarWireValue ?: fallbackAvatarWireValue,
        label = member.raftAvatarName(),
        size = size,
        modifier = modifier,
        presence = presence,
        cacheSubject = member.raftAvatarCacheSubject(),
        cacheServerId = member.serverId?.value,
    )
}

/**
 * task #515 — web `AgentArchiveDialog` gate `hasComputerWorkspace`
 * (`Boolean(agent.machineId) && agent.external !== true && !isExternalAgentRuntime(runtime)`):
 * the workspace keep/delete choice is offered only when a managed agent really
 * has a workspace on a Computer. External runtimes never do (server would 409
 * `archive_workspace_unavailable`), and a missing machineId hides the radio
 * entirely — the request then archives with the workspace kept.
 */
internal fun membersAgentArchiveHasComputerWorkspace(
    machineId: String?,
    isExternalAgent: Boolean
): Boolean = !isExternalAgent && machineId?.isNotBlank() == true

/**
 * Wire body for `DELETE /agents/:id` — web always sends `{"workspace":...}`.
 */
internal fun membersAgentArchiveRequestBody(choice: AgentArchiveWorkspaceChoice): String =
    choice.toRequestBody()

// Mirrors React ConfirmDialog usage in AgentDetailPanel: message is user-facing
// copy, warning banner shows the message only (never repeats the title), and
// the confirm button carries the action verb (Stop / Archive, #148) with
// warning/destructive color.
@Composable
internal fun membersLifecycleConfirmTitle(action: MembersAgentLifecycleAction, agentName: String): String =
    when (action) {
        MembersAgentLifecycleAction.Stop -> stringResource(Res.string.member_action_stop_agent)
        // task #515: the archive dialog's title carries the agent name, matching
        // web `agent.archive.title` ("Archive {name}?"); other actions keep the
        // action-label title.
        MembersAgentLifecycleAction.Delete ->
            stringResource(Res.string.member_archive_title).replace("%1\$s", agentName)
        else -> action.localizedLabel()
    }

@Composable
private fun membersLifecycleConfirmMessage(action: MembersAgentLifecycleAction, agentName: String): String =
    when (action) {
        MembersAgentLifecycleAction.Stop ->
            stringResource(Res.string.member_dialog_confirm_stop).replace("%1\$s", agentName)
        MembersAgentLifecycleAction.Delete ->
            stringResource(Res.string.member_archive_description)
        MembersAgentLifecycleAction.UnassignMachine ->
            stringResource(Res.string.member_dialog_confirm_unassign).replace("%1\$s", agentName)
        else -> "Are you sure you want to ${action.localizedLabel().lowercase()} \"$agentName\"?"
    }

@Composable
internal fun MembersLifecycleConfirmation(
    action: MembersAgentLifecycleAction,
    agentName: String,
    busy: Boolean,
    archiveHasComputerWorkspace: Boolean = false,
    archiveComputerName: String = "",
    archiveWorkspaceChoice: AgentArchiveWorkspaceChoice = AgentArchiveWorkspaceChoice.Keep,
    onArchiveWorkspaceChoiceChange: (AgentArchiveWorkspaceChoice) -> Unit = {},
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    // Web `AgentArchiveDialog`: confirm button is warning when the workspace is
    // kept, danger once the user picks "delete the workspace". For non-archive
    // actions the classic destructive flag rules unchanged.
    val isArchive = action == MembersAgentLifecycleAction.Delete
    // Archive overrides the enum's blanket `destructive=true`: web keeps the
    // confirm button at warning until the user picks "delete the workspace".
    val destructive =
        if (isArchive) archiveWorkspaceChoice == AgentArchiveWorkspaceChoice.Delete else action.destructive
    // Hosted inside RaftUnifiedDialog, which owns the card padding.
    // Banner metrics mirror React Banner density=md: p-3 (12), gap-3 (12),
    // text-sm (14/20), border-2, and ConfirmDialog's mb-5 (20) to the buttons.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        if (isArchive) {
            // Web order: description → workspace question + radio rows → the
            // destructive banner ONLY when "delete" is selected (the keep path
            // carries no warning at all).
            Text(
                text = stringResource(Res.string.member_archive_description),
                // Web ConfirmDialog DialogBody: text-subhead (15/20 400).
                style = RaftType.Subhead,
                color = RaftColor.TextBlack
            )
            if (archiveHasComputerWorkspace) {
                Text(
                    text = stringResource(Res.string.member_archive_workspace_question),
                    style = RaftType.Caption.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    color = RaftColor.TextMutedGray
                )
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    MembersArchiveWorkspaceOption(
                        label = stringResource(Res.string.member_archive_option_keep),
                        description = stringResource(Res.string.member_archive_option_keep_desc)
                            .replace("%1\$s", archiveComputerName),
                        selected = archiveWorkspaceChoice == AgentArchiveWorkspaceChoice.Keep,
                        enabled = !busy,
                        onSelect = { onArchiveWorkspaceChoiceChange(AgentArchiveWorkspaceChoice.Keep) }
                    )
                    MembersArchiveWorkspaceOption(
                        label = stringResource(Res.string.member_archive_option_delete),
                        description = stringResource(Res.string.member_archive_option_delete_desc)
                            .replace("%1\$s", archiveComputerName),
                        selected = archiveWorkspaceChoice == AgentArchiveWorkspaceChoice.Delete,
                        enabled = !busy,
                        onSelect = { onArchiveWorkspaceChoiceChange(AgentArchiveWorkspaceChoice.Delete) }
                    )
                }
            }
            if (archiveWorkspaceChoice == AgentArchiveWorkspaceChoice.Delete && archiveHasComputerWorkspace) {
                MembersLifecycleWarningBanner(
                    destructive = true,
                    text = stringResource(Res.string.member_archive_delete_warning)
                )
            }
        } else {
            MembersLifecycleWarningBanner(
                destructive = destructive,
                text = membersLifecycleConfirmMessage(action, agentName)
            )
        }
        RaftDialogFooter {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                enabled = !busy,
                onClick = onCancel,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg
            )
            RaftButton(
                // #148: the confirm button says the verb only; the title names the agent.
                text = when {
                    isArchive -> stringResource(Res.string.member_archive_confirm)
                    action == MembersAgentLifecycleAction.Stop -> stringResource(Res.string.member_action_stop_confirm)
                    else -> membersLifecycleConfirmTitle(action, agentName)
                },
                loading = busy,
                // Web ConfirmDialog: confirmVariant danger for what cannot be undone, warning otherwise.
                variant = if (destructive) RaftButtonVariant.Danger else RaftButtonVariant.Warning,
                size = RaftButtonSize.Lg,
                // Web ConfirmDialog carries confirmIcon=<Archive> on the archive
                // confirm; other actions stay icon-less.
                leadingIcon = if (isArchive) {
                    { RaftSlotIcon(RaftLucideIconName.Archive) }
                } else {
                    null
                },
                onClick = onConfirm
            )
        }
    }
}

/** Banner row extracted so the archive flow can show it conditionally (web renders it only for the delete choice). */
@Composable
private fun MembersLifecycleWarningBanner(destructive: Boolean, text: String) {
    val accent = if (destructive) RaftColor.AccentRed else RaftColor.AccentOrange
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .raftCardSurface(fill = raftSoftWash(accent))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        // React Banner icons: destructive → AlertTriangle, warning →
        // CircleAlert (closest available catalog glyph is Info).
        RaftLucideIcon(
            if (destructive) RaftLucideIconName.TriangleAlert else RaftLucideIconName.Info,
            modifier = Modifier.size(18.dp),
            color = RaftColor.TextBlackArgb
        )
        Text(
            text = text,
            // Web Banner: text-callout (14/20 400).
            style = RaftType.Callout,
            color = RaftColor.TextBlack
        )
    }
}

// ── task #517: post-archive workspace section (web AgentArchiveSection parity) ──

/** Server-bound callbacks for the archive section so page wiring stays flat. */
internal class MembersAgentArchiveInputs(
    /** `GET /agents/:id/archive` — `Content(null)` means no archive record. */
    val loadArchive: suspend (MemberId) -> DataResult<build.raft.app.data.RaftAgentArchive?> = {
        DataResult.Error(ScreenError(code = "archive_unavailable", retryable = false))
    },
    /** `POST /agents/:id/archive/workspace {"action":"delete"}` — returns the updated view. */
    val deleteWorkspace: suspend (MemberId) -> DataResult<build.raft.app.data.RaftAgentArchive> = {
        DataResult.Error(ScreenError(code = "archive_unavailable", retryable = false))
    },
    /** Resolves `archivedByUserId` to a display name; null = render the date alone. */
    val memberNameByUserId: (String) -> String? = { null }
)

internal sealed interface MembersAgentArchiveUiState {
    data object Loading : MembersAgentArchiveUiState
    data class Loaded(val archive: build.raft.app.data.RaftAgentArchive?) : MembersAgentArchiveUiState
    data object Failed : MembersAgentArchiveUiState
}

/** Maps the archive view's `lastErrorCode` to a user-readable line
 *  (web `archiveErrorMessageId` + pending-op overlays). */
@Composable
private fun membersArchiveLastErrorText(
    archive: build.raft.app.data.RaftAgentArchive,
    computer: String
): String? {
    val code = archive.lastErrorCode ?: return null
    return if (archive.pendingOp == null) {
        when (code) {
            "ARCHIVE_WORKSPACE_MISSING" ->
                stringResource(Res.string.member_archive_error_workspace_missing)
            "ARCHIVE_DELETE_UNCONFIRMED" ->
                stringResource(Res.string.member_archive_error_delete_unconfirmed)
            else -> stringResource(Res.string.member_archive_error_generic)
        }
    } else if (code == "ARCHIVE_DELETE_TIMED_OUT") {
        stringResource(Res.string.member_archive_still_deleting, computer)
    } else {
        stringResource(Res.string.member_archive_retrying)
    }
}

/** Maps a refused workspace-delete `ScreenError` to text: known machine codes
 *  get localized copy, everything else shows the raw server message or the
 *  generic failure line (web `archiveRequestErrorMessage`). */
@Composable
private fun membersArchiveDeleteErrorText(error: ScreenError): String =
    when (error.code) {
        "archive_migration_active" ->
            stringResource(Res.string.member_archive_error_migration_active)
        else -> error.serverMessage?.takeIf { it.isNotBlank() }
            ?: stringResource(Res.string.member_archive_error_generic)
    }

@Composable
internal fun MembersAgentArchiveSection(
    agentId: MemberId,
    canManage: Boolean,
    fallbackArchivedAt: String?,
    timeContext: RaftTimeContext,
    inputs: MembersAgentArchiveInputs
) {
    var archiveState by remember(agentId) {
        mutableStateOf<MembersAgentArchiveUiState>(MembersAgentArchiveUiState.Loading)
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<ScreenError?>(null) }

    LaunchedEffect(agentId) {
        archiveState = when (val result = inputs.loadArchive(agentId)) {
            is DataResult.Content -> MembersAgentArchiveUiState.Loaded(result.data)
            else -> MembersAgentArchiveUiState.Failed
        }
    }

    val archive = (archiveState as? MembersAgentArchiveUiState.Loaded)?.archive
    val computer = archive?.machineName?.takeIf { it.isNotBlank() }
        ?: stringResource(Res.string.member_archive_unknown_computer)
    val workspaceText = when {
        archive == null -> stringResource(Res.string.member_archive_workspace_unknown)
        archive.pendingOp != null ->
            stringResource(Res.string.member_archive_workspace_deleting, computer)
        archive.workspaceState == build.raft.app.data.AgentArchiveWorkspaceState.OnComputer ->
            stringResource(Res.string.member_archive_workspace_on_computer, computer)
        archive.workspaceState == build.raft.app.data.AgentArchiveWorkspaceState.Deleted ->
            stringResource(Res.string.member_archive_workspace_deleted)
        else -> stringResource(Res.string.member_archive_workspace_unknown)
    }
    val archivedAtIso = archive?.archivedAt ?: fallbackArchivedAt
    val archivedAtText = archivedAtIso?.let {
        timeContext.formatTimestampLabel(it).ifBlank { it }
    }
    val archivedByName = archive?.archivedByUserId?.let(inputs.memberNameByUserId)
    val canDeleteWorkspace = canManage && archive != null && archive.pendingOp == null &&
        archive.workspaceState == build.raft.app.data.AgentArchiveWorkspaceState.OnComputer && !deleting

    MembersDetailSection(
        title = stringResource(Res.string.member_archive_section_title)
    ) {
        if (archiveState == MembersAgentArchiveUiState.Failed) {
            MembersLifecycleWarningBanner(
                destructive = false,
                text = stringResource(Res.string.member_archive_load_failed)
            )
        }
        if (archiveState == MembersAgentArchiveUiState.Failed || archive == null) {
            // Fallback: the same block the pre-archive detail rendered.
            MembersDetailField(
                stringResource(Res.string.member_label_status),
                stringResource(Res.string.member_status_deleted)
            )
            MembersDetailField(
                stringResource(Res.string.member_label_deleted_at),
                archivedAtIso.orDash()
            )
        } else {
            val byName = archivedByName
            MembersDetailField(
                stringResource(Res.string.member_archive_archived_at),
                if (byName != null && archivedAtText != null) {
                    stringResource(Res.string.member_archive_archived_at_by, archivedAtText, byName)
                } else {
                    archivedAtText.orDash()
                }
            )
            MembersDetailField(stringResource(Res.string.member_archive_workspace_label), workspaceText)
            if (archive.waitingForComputer) {
                Text(
                    text = stringResource(Res.string.member_archive_waiting_for_computer, computer),
                    style = RaftType.Caption,
                    color = RaftColor.TextMutedGray
                )
            }
        }
        val lastErrorText = archive?.let { membersArchiveLastErrorText(it, computer) }
        if (lastErrorText != null) {
            MembersLifecycleWarningBanner(destructive = false, text = lastErrorText)
        }
        val requestError = deleteError
        if (requestError != null) {
            MembersLifecycleWarningBanner(
                destructive = false,
                text = membersArchiveDeleteErrorText(requestError)
            )
        }
        if (canDeleteWorkspace) {
            RaftButton(
                text = stringResource(Res.string.member_archive_action_delete_workspace),
                onClick = { confirmDelete = true },
                enabled = !deleting,
                modifier = Modifier.fillMaxWidth(),
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Md
            )
        }
    }

    // Drive the suspend delete off the dialog's sync confirm click: the
    // dialog only owns the click, the section owns the request.
    LaunchedEffect(deleting) {
        if (!deleting) return@LaunchedEffect
        when (val result = inputs.deleteWorkspace(agentId)) {
            is DataResult.Content -> {
                archiveState = MembersAgentArchiveUiState.Loaded(result.data)
                confirmDelete = false
            }
            is DataResult.Error -> {
                deleteError = result.error
                confirmDelete = false
            }
            else -> {
                deleteError = ScreenError(code = "agent_archive_workspace_delete_contract_mismatch", retryable = true)
                confirmDelete = false
            }
        }
        deleting = false
    }

    if (confirmDelete) {
        // Web `ConfirmDialog`: action-label title, one message line, cancel +
        // danger confirm (the irreversible red lives here, not on the trigger).
        RaftUnifiedDialog(
            title = stringResource(Res.string.member_archive_action_delete_workspace),
            dismissEnabled = !deleting,
            dismissOnOutsideTap = false,
            contentPadding = 24.dp,
            onDismiss = { confirmDelete = false }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(
                    text = stringResource(Res.string.member_archive_confirm_delete_workspace, computer),
                    // Web ConfirmDialog danger: the message sits in a Banner, text-callout (14/20 400).
                    style = RaftType.Callout,
                    color = RaftColor.TextBlack
                )
                RaftDialogFooter {
                    RaftButton(
                        text = stringResource(Res.string.common_cancel),
                        enabled = !deleting,
                        onClick = { confirmDelete = false },
                        variant = RaftButtonVariant.Ghost,
                        size = RaftButtonSize.Lg
                    )
                    RaftButton(
                        text = stringResource(Res.string.member_archive_delete_workspace_confirm),
                        loading = deleting,
                        variant = RaftButtonVariant.Danger,
                        size = RaftButtonSize.Lg,
                        leadingIcon = { RaftSlotIcon(RaftLucideIconName.Trash2) },
                        onClick = {
                            deleting = true
                            deleteError = null
                        }
                    )
                }
            }
        }
    }
}

/**
 * One workspace-disposition row of the archive dialog — web renders a
 * `RadioGroupItem` + 15px/600 label + 13px muted description; the closest
 * shared primitive is the circle `RaftCheckMarker` used by
 * `RaftUiRadioRow`, with the whole row tappable.
 */
@Composable
private fun MembersArchiveWorkspaceOption(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.raftClickable(onClick = onSelect, role = Role.RadioButton, tier = RaftHoverTier.Subtle) else Modifier)
            .semantics { this.selected = selected }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        RaftCheckMarker(
            checked = selected,
            shape = RaftCheckMarkerShape.Circle,
            tone = RaftCheckMarkerTone.YellowFill,
            disabled = !enabled,
            size = 16.dp,
            modifier = Modifier.padding(top = 2.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = RaftType.Subhead.withRaftFontWeight(FontWeight.SemiBold),
                color = if (enabled) RaftColor.TextBlack else RaftColor.TextBlack.copy(alpha = 0.45f)
            )
            Text(
                text = description,
                // Web AgentArchiveDialog: text-footnote font-normal (13/18 400).
                style = RaftType.Footnote,
                color = RaftColor.TextMutedGray
            )
        }
    }
}

@Composable
internal fun MembersRestartResetDialog(
    agentName: String,
    selectedAction: MembersAgentLifecycleAction,
    busy: Boolean,
    errorMessage: String?,
    onSelectedActionChange: (MembersAgentLifecycleAction) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    RaftUnifiedDialog(
        title = stringResource(Res.string.member_dialog_restart_title).replace("%1\$s", agentName),
        onDismiss = onDismiss,
        dismissEnabled = !busy,
        dismissOnOutsideTap = false,
        maxWidth = 520.dp,
        // Web ResetAgentDialog is a ConfirmDialog: 18px title, text-sm option
        // labels, text-xs descriptions. Match the sibling lifecycle confirm dialog.
        titleMaxLines = 2,
        contentSpacing = RaftAppSpacing.Md
    ) {
        // Web ResetAgentDialog: a raft-ui RadioGroup of plain rows (no option cards, no
        // tint or offset shadow on the pick), a destructive Banner under the full reset,
        // and the confirm colour stating the consequence (warning restarts, danger wipes).
        val options =
            listOf(
                MembersAgentLifecycleAction.Restart,
                MembersAgentLifecycleAction.ResetSession,
                MembersAgentLifecycleAction.ResetFull
            )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
        ) {
            errorMessage?.takeIf { it.isNotBlank() }?.let {
                item {
                    RaftBanner(
                        description = stringResource(Res.string.member_error_reset_agent, it),
                        status = RaftBannerStatus.Destructive,
                        size = RaftBannerSize.Sm
                    )
                }
            }
            item {
                RaftRadioGroup {
                    options.forEach { action ->
                        RaftRadioRow(
                            label = action.restartResetTitle(),
                            description = action.restartResetDescription(),
                            selected = selectedAction == action,
                            enabled = !busy,
                            onClick = { onSelectedActionChange(action) }
                        )
                    }
                }
            }
            if (selectedAction == MembersAgentLifecycleAction.ResetFull) {
                item {
                    RaftBanner(
                        description = stringResource(Res.string.member_dialog_full_reset_warning),
                        status = RaftBannerStatus.Destructive,
                        size = RaftBannerSize.Sm
                    )
                }
            }
        }
        // Same footer as the app's other confirm dialogs (and web ConfirmDialog):
        // right-aligned buttons at their natural width.
        RaftDialogFooter {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !busy
            )
            RaftButton(
                text = selectedAction.restartResetButtonLabel(),
                onClick = onConfirm,
                variant = selectedAction.restartResetConfirmVariant(),
                size = RaftButtonSize.Lg,
                loading = busy,
                loadingLabel = stringResource(Res.string.member_action_restarting),
                leadingIcon = {
                    RaftLucideIcon(
                        RaftLucideIconName.RotateCcw,
                        modifier = Modifier.size(16.dp),
                        color = raftArgbHex(LocalRaftContentColor.current)
                    )
                }
            )
        }
    }
}

internal fun MembersAgentLifecycleAction.isRestartResetDialogAction(): Boolean =
    this == MembersAgentLifecycleAction.Restart ||
        this == MembersAgentLifecycleAction.ResetSession ||
        this == MembersAgentLifecycleAction.ResetFull

@Composable
private fun MembersAgentLifecycleAction.restartResetTitle(): String =
    when (this) {
        MembersAgentLifecycleAction.Restart -> stringResource(Res.string.member_action_restart_agent)
        MembersAgentLifecycleAction.ResetSession -> stringResource(Res.string.member_action_restart_session)
        MembersAgentLifecycleAction.ResetFull -> stringResource(Res.string.member_action_full_reset_restart)
        else -> localizedLabel()
    }

@Composable
private fun MembersAgentLifecycleAction.restartResetButtonLabel(): String =
    when (this) {
        MembersAgentLifecycleAction.Restart -> stringResource(Res.string.member_action_restart_confirm)
        MembersAgentLifecycleAction.ResetSession -> stringResource(Res.string.member_action_restart_session)
        MembersAgentLifecycleAction.ResetFull -> stringResource(Res.string.member_action_full_reset_restart)
        else -> localizedLabel()
    }

@Composable
private fun MembersAgentLifecycleAction.restartResetDescription(): String =
    when (this) {
        MembersAgentLifecycleAction.Restart ->
            stringResource(Res.string.member_dialog_restart_description)
        MembersAgentLifecycleAction.ResetSession ->
            stringResource(Res.string.member_dialog_reset_session_description)
        MembersAgentLifecycleAction.ResetFull ->
            stringResource(Res.string.member_dialog_full_reset_description)
        else -> ""
    }

private fun MembersAgentLifecycleAction.restartResetConfirmVariant(): RaftButtonVariant =
    if (this == MembersAgentLifecycleAction.ResetFull) RaftButtonVariant.Danger else RaftButtonVariant.Warning

/**
 * raft-ui ConversationPanelTabs + TabsList (default variant), as the web agent page: sticker
 * tabs (16dp icon, callout label) 2dp apart on `layer-canvas`, 16 / 8dp padding and a
 * `line-seam` under them ([RaftTabStrip]); the selected tab scrolls itself into view.
 * Long-press drag reorders (raft-ui SortableTabsList): the dragged tab follows the finger,
 * lifted (translucent, `info-soft` fill, drawn above its neighbours) so the drag is visible even
 * on the selected tab, and drops past every neighbour whose midpoint it crosses
 * ([membersAgentTabDropIndex]).
 */
@Composable
internal fun MembersAgentTabBar(
    tabs: List<MembersAgentDetailTab>,
    selectedTab: MembersAgentDetailTab,
    onSelectedTabChange: (MembersAgentDetailTab) -> Unit,
    onReorderTabs: ((List<MembersAgentDetailTab>) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var draggedTab by remember { mutableStateOf<MembersAgentDetailTab?>(null) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    // Keyed by the stable enum, not by `tabs`: a reorder must not drop measured widths, since
    // onSizeChanged does not refire for tabs whose size did not change.
    val tabWidthsPx = remember { mutableStateMapOf<MembersAgentDetailTab, Int>() }
    val currentTabs by rememberUpdatedState(tabs)
    val tabGapPx = with(LocalDensity.current) { 2.dp.toPx() }
    val dragFill = RaftTheme.colors.infoSoft
    RaftTabStrip(modifier = modifier) {
        tabs.forEach { tab ->
            key(tab) {
                val dragging = tab == draggedTab
                RaftTab(
                    text = tab.localizedLabel(),
                    selected = tab == selectedTab,
                    onClick = { onSelectedTabChange(tab) },
                    icon = { RaftContentLucideIcon(tab.iconName) },
                    modifier = Modifier
                        .onSizeChanged { tabWidthsPx[tab] = it.width }
                        .then(
                            if (dragging) {
                                Modifier
                                    .zIndex(1f)
                                    .graphicsLayer {
                                        translationX = dragOffsetX
                                        alpha = 0.85f
                                    }
                                    .background(dragFill, RoundedCornerShape(RaftRadius.Control))
                            } else {
                                Modifier
                            }
                        )
                        .pointerInput(tab, onReorderTabs) {
                            if (onReorderTabs == null) return@pointerInput
                            // raft-ui 0.32.2 (#787): the drag starts after the 500ms touch hold.
                            raftDetectDragGesturesAfterHold(
                                onDragStart = {
                                    draggedTab = tab
                                    dragOffsetX = 0f
                                },
                                onDragEnd = {
                                    val order = currentTabs
                                    val fromIndex = order.indexOf(tab)
                                    val toIndex = membersAgentTabDropIndex(
                                        widthsPx = order.map { tabWidthsPx[it] ?: 0 },
                                        gapPx = tabGapPx,
                                        fromIndex = fromIndex,
                                        dragOffsetPx = dragOffsetX
                                    )
                                    if (fromIndex in order.indices && toIndex != fromIndex) {
                                        onReorderTabs(order.membersMoveTab(fromIndex, toIndex))
                                    }
                                    draggedTab = null
                                    dragOffsetX = 0f
                                },
                                onDragCancel = {
                                    draggedTab = null
                                    dragOffsetX = 0f
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                dragOffsetX += dragAmount.x
                            }
                        }
                )
            }
        }
    }
}

internal data class MembersAgentRuntimeErrorBannerState(
    val errorText: String,
    val showViewLogs: Boolean,
    /** A typed BillingError (botiverse/slock #13435): the billing banner, whose one action is model settings. */
    val billingExhausted: Boolean = false
)

internal fun membersAgentRuntimeErrorBannerState(
    memberKind: MemberKind,
    selectedTab: MembersAgentDetailTab,
    runtimeErrorText: String?,
    billingExhausted: Boolean = false
): MembersAgentRuntimeErrorBannerState? {
    if (memberKind != MemberKind.Agent) return null
    val errorText = runtimeErrorText?.takeIf { it.isNotBlank() } ?: return null
    return MembersAgentRuntimeErrorBannerState(
        errorText = errorText,
        // The billing banner keeps one action (its copy rule); its log is still on the Activity tab.
        showViewLogs = selectedTab != MembersAgentDetailTab.Activity && !billingExhausted,
        billingExhausted = billingExhausted
    )
}

/**
 * A kept BillingError is on screen and the agent is running again (botiverse/slock #13435): the
 * error may have been cleared without this page hearing `agent:updated`, so re-read once.
 */
internal fun membersBillingErrorRereadDue(billingErrorShown: Boolean, liveStatus: AgentStatus?): Boolean =
    billingErrorShown &&
        when (liveStatus) {
            AgentStatus.Online, AgentStatus.Working, AgentStatus.Thinking, AgentStatus.Idle -> true
            AgentStatus.Offline, AgentStatus.Disconnected, AgentStatus.Error, null -> false
        }

/** The runtime editor can open now: the pencil's conditions (a known status, a readable config, the v2 form). */
internal fun membersRuntimeEditorCanOpen(
    canEdit: Boolean,
    hasAuthority: Boolean,
    hasConfig: Boolean,
    runtimeEditorReady: Boolean,
    hasFormV2Access: Boolean
): Boolean = canEdit && hasAuthority && hasConfig && runtimeEditorReady && hasFormV2Access

/** An open-editor request acts only once the editor can open; until then it stays pending. */
internal fun membersRuntimeEditorOpenRequestActs(requested: Boolean, canOpen: Boolean): Boolean = requested && canOpen

/**
 * Web AgentDetailPanel `canOpenModelSettings` (`canManageAgent && !agent.deletedAt &&
 * !isExternalAgent && !activityOnly`; the app has no activity-only page, and [canManage] is
 * already false for an archived agent).
 */
internal fun membersAgentCanOpenModelSettings(canManage: Boolean, isExternalAgent: Boolean): Boolean =
    canManage && !isExternalAgent

@Composable
internal fun MembersAgentDetailStateNotice(
    state: MembersAgentDetailLoadState,
    modifier: Modifier = Modifier
) {
    val errorStrings = localizedErrorPresentationStrings()
    val message =
        when (state) {
            MembersAgentDetailLoadState.Loading -> stringResource(Res.string.member_state_loading_agent_profile)
            MembersAgentDetailLoadState.Empty -> stringResource(Res.string.member_state_agent_profile_unavailable)
            is MembersAgentDetailLoadState.Error ->
                "${stringResource(Res.string.member_error_load_agent_profile)}: ${state.error.userFacingMessage(errorStrings)}"
            is MembersAgentDetailLoadState.NoPermission ->
                "${stringResource(Res.string.member_error_private_surfaces_hidden)}: ${state.blocker.userFacingMessage(errorStrings)}"
            MembersAgentDetailLoadState.Idle -> null
            is MembersAgentDetailLoadState.Content ->
                if (state.refreshing) stringResource(Res.string.member_state_refreshing_agent_profile) else null
        }
    val resolved = message ?: return
    // raft-ui Banner (sm): info while loading, warning when the profile can't be shown.
    val status =
        when (state) {
            MembersAgentDetailLoadState.Loading,
            is MembersAgentDetailLoadState.Content -> RaftBannerStatus.Info
            else -> RaftBannerStatus.Warning
        }
    RaftBanner(
        description = resolved,
        status = status,
        size = RaftBannerSize.Sm,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
internal fun MembersAgentRuntimeErrorBanner(
    errorText: String,
    canViewPrivate: Boolean,
    showViewLogs: Boolean,
    onViewLogs: () -> Unit,
    onCopyInfo: () -> Unit,
    modifier: Modifier = Modifier,
    /** The agent's name when the error is a typed BillingError: the billing banner (web AgentRuntimeErrorNotice). */
    billingAgentName: String? = null,
    /** Present when the viewer may open the agent's model settings. */
    onOpenModelSettings: (() -> Unit)? = null,
    /**
     * The error has a code (web #13985): [errorText] is the code's catalog sentence, which every
     * viewer reads, and a manager reads the error's detail under it.
     */
    coded: AgentRuntimeErrorDisplay.Coded? = null
) {
    if (billingAgentName != null) {
        MembersAgentBillingBanner(
            title = errorText,
            agentName = billingAgentName,
            canViewPrivate = canViewPrivate,
            onOpenModelSettings = onOpenModelSettings,
            onCopyInfo = onCopyInfo,
            modifier = modifier
        )
        return
    }
    // raft-ui PanelNotice under the header: the runtime error, then Copy Info and View Logs.
    RaftPanelNotice(
        message = if (canViewPrivate || coded != null) errorText else stringResource(Res.string.member_state_agent_error),
        modifier = modifier,
        // Web: a manager's detail goes under the code's headline (muted).
        details = listOfNotNull(coded?.detail?.trim()?.takeIf { canViewPrivate && it.isNotEmpty() }),
        status = RaftBannerStatus.Warning,
        // The error text gets the full width; the actions go underneath. Web `line-clamp-2`, with
        // the whole text in a tooltip on the notice (a long press here).
        maxLines = 2,
        messageTooltip = true,
        stackActions = true,
        actions = if (canViewPrivate) {
            {
                RaftButton(
                    text = stringResource(Res.string.member_action_copy_info),
                    onClick = onCopyInfo,
                    variant = RaftButtonVariant.Ghost
                )
                if (showViewLogs) {
                    RaftButton(
                        text = stringResource(Res.string.member_action_view_logs),
                        onClick = onViewLogs,
                        variant = RaftButtonVariant.Ghost
                    )
                }
            }
        } else {
            null
        }
    )
}

internal object MembersAgentBillingBannerTestTags {
    const val Banner = "agent-billing-banner"
    const val ModelSettings = "agent-billing-banner-model-settings"
}

/**
 * Web AgentRuntimeErrorNotice's billing banner (botiverse/slock #13435, task #1351): the title, a
 * body naming the agent, and one action — View model settings for whoever may open them, otherwise
 * who can fix it. Catalog copy, so every viewer gets it (never the provider's 402 text, which stays
 * in Copy Info for whoever may see details). No dismissal of its own: it goes when the error does.
 */
@Composable
private fun MembersAgentBillingBanner(
    title: String,
    agentName: String,
    canViewPrivate: Boolean,
    onOpenModelSettings: (() -> Unit)?,
    onCopyInfo: () -> Unit,
    modifier: Modifier = Modifier
) {
    RaftPanelNotice(
        message = title,
        modifier = modifier.testTag(MembersAgentBillingBannerTestTags.Banner),
        status = RaftBannerStatus.Warning,
        maxLines = Int.MAX_VALUE,
        stackActions = true,
        details =
            listOfNotNull(
                stringResource(Res.string.agent_runtime_error_billing_exhausted_body, agentName),
                if (onOpenModelSettings == null) stringResource(Res.string.agent_runtime_error_billing_exhausted_ask_admin) else null
            ),
        actions =
            if (onOpenModelSettings != null || canViewPrivate) {
                {
                    if (onOpenModelSettings != null) {
                        RaftButton(
                            text = stringResource(Res.string.agent_runtime_error_billing_exhausted_view_model_settings),
                            onClick = onOpenModelSettings,
                            variant = RaftButtonVariant.Ghost,
                            modifier = Modifier.testTag(MembersAgentBillingBannerTestTags.ModelSettings)
                        )
                    }
                    if (canViewPrivate) {
                        RaftButton(
                            text = stringResource(Res.string.member_action_copy_info),
                            onClick = onCopyInfo,
                            variant = RaftButtonVariant.Ghost
                        )
                    }
                }
            } else {
                null
            }
    )
}
