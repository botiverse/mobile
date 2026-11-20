package build.raft.app

import build.raft.designsystem.theme.RaftControlMetrics
import androidx.compose.runtime.ReadOnlyComposable
import build.raft.app.resources.*
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.components.raftCopyable
import build.raft.designsystem.components.RaftIconToggleButton
import build.raft.designsystem.components.RaftBackButton
import build.raft.designsystem.components.RaftSideAction
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTooltip
import build.raft.designsystem.components.RaftBackButtonDefaults
import build.raft.app.RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY
import build.raft.app.data.RAFT_SHELL_OVERLAY_REQUEST_EVENT
import build.raft.app.data.RAFT_SHELL_OVERLAY_RESULT_EVENT
import build.raft.app.data.SHELL_OVERLAY_SELECTED_ID
import build.raft.app.data.raftShouldApplyShellOverlayResult
import build.raft.app.data.RaftShellOverlayRequest
import build.raft.app.data.RaftShellOverlayItem
import build.raft.app.data.cancelRaftShellOverlayJson
import build.raft.app.components.RaftExternalBridgeBadge
import build.raft.app.components.RaftUiControlledMenu
import build.raft.app.components.RaftUiMenuItem
import build.raft.app.components.RaftMetrics
import build.raft.app.base.Utils
import build.raft.app.data.RaftEventBus
import build.raft.app.data.map
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.model.MemberKind
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.layout
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.compat.pager.Pager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.Channel

internal const val ThreadReplyPageTitle = "Thread"
internal const val ThreadReplyEmptyTitle = "No replies yet"
internal const val ThreadReplyEmptyDetail = ""
private val ThreadComposerBottomBasePadding = 12.dp
private val ThreadComposerBottomSafeAreaLift = 2.dp
// raft-ui `PanelAction` on a phone (`h-11 min-w-11`, `[&>svg]:size-[22px]`, text-foreground):
// a 44dp frameless button carrying a 22dp glyph, so the hit area is the 44dp touch target.
// Under a mouse or trackpad the head's bar button: 28dp with a 16dp glyph (sizing v12, raft-ui
// 0.65 desktop density, RaftControlMetrics.barButtonSize).
private val ThreadHeaderActionSize: Dp
    @Composable @ReadOnlyComposable
    get() = RaftTheme.controls.barButtonSize
// raft-ui `panelHeadActions` on a phone: `gap-0.5`. The 44dp buttons carry 11dp of empty
// inset on each side, so neighbouring glyphs still sit 24dp apart.
private val ThreadHeaderActionGroupSpacing = 2.dp
private val ThreadHeaderActionIconSize: Dp
    @Composable @ReadOnlyComposable
    get() = RaftTheme.controls.barIconSize
private val ThreadHeaderActionLabelMinWidth = 44.dp
// raft `panelHeadEdges` on a phone + `panelHeadActions mr-[-3px]` / Back `-ml-[13px]`: the
// Back button and the last trailing action both sit 3dp from the screen edge, which puts each
// 22dp icon box 14dp in and makes the two ends mirror (foundations/alignment "Top bar icon buttons").
// raft-ui 0.14: Back and the last trailing action keep their 22dp glyph boxes on the 16dp line
// (16dp glyphs in 28dp bar buttons, 10dp from the edge, under a mouse or trackpad: sizing v12).
private val ThreadHeaderEdgeInset: Dp
    @Composable @ReadOnlyComposable
    get() = RaftBackButtonDefaults.EdgeInset

/**
 * The side edge of a conversation shown as the detail pane of a split: web `COMPOSER_EDGE_CLASS`
 * (`px-5`, composerHost.ts) for the composer frame, and the header title's start when Back is gone.
 */
internal val ThreadSplitDetailEdgeInset = 20.dp
private val ThreadHeaderDmAgentActivityMaxWidth = 150.dp
private val ThreadHeaderDmAgentActivityLeadingGap = RaftAppSpacing.Sm
// React AgentDMStatus uses the canonical StatusDot default (`md`, 10px).
// Keep the mobile header on the same canonical inline token instead of a
// smaller one-off dot.
private val ThreadHeaderDmAgentActivityDotSize = RaftStatusIndicatorTokens.InlineSize
// raft-ui `PanelHeaderIcon`: the 27dp location tile (`size-[27px]`) with a 14dp glyph
// (`[&_svg]:size-3.5 stroke-2`); the DM avatar slot keeps the tile's former 34dp.
private val ThreadHeaderChannelTileSize = 27.dp
private val ThreadHeaderChannelIconSize = 14.dp
private val ThreadHeaderDmAvatarSize = 34.dp
// raft-ui `panelHeader` on a phone: `max-md:gap-2` between Back, the tile, the title and actions.
internal val ThreadHeaderContentSpacing = 8.dp
private val ThreadHeaderChannelTitleGapExtra = 0.dp

internal fun threadComposerBottomPadding(
    keyboardInsetDp: Float,
    bottomSafeArea: Dp,
    keyboardInsetExcludesBottomSafeArea: Boolean = false,
    // Desktop (no safe area, no keyboard): the web composer's `safe-bottom` 1rem under the card.
    minimumBottomPadding: Dp = raftPlatformComposerMinimumBottomPaddingDp().dp
): Dp {
    val keyboardInset = keyboardInsetDp.coerceAtLeast(0f).dp
    // When the keyboard height already contains the bottom safe area (iOS keyboard frame), the
    // keyboard and the safe area overlap: the composer sits on whichever is taller. Taking the max
    // (instead of switching from safe area to keyboard as soon as the keyboard is > 0) keeps the
    // padding from dipping below its resting value during the first frames of the keyboard
    // animation, while the keyboard is still shorter than the safe area.
    val bottomInset =
        if (keyboardInsetExcludesBottomSafeArea) {
            keyboardInset + bottomSafeArea
        } else {
            maxOf(keyboardInset, bottomSafeArea)
        }
    return maxOf(ThreadComposerBottomBasePadding + ThreadComposerBottomSafeAreaLift + bottomInset, minimumBottomPadding)
}

internal fun threadChannelHeaderSubtitle(channelName: String?): String? {
    val raw = channelName?.trim().orEmpty()
    if (raw.isBlank() || raw.startsWith("Origin:", ignoreCase = true)) return null
    val name = raw.removePrefix("#").substringBefore(":").trim()
    if (name.isBlank()) return null
    if (name.equals(ThreadReplyPageTitle, ignoreCase = true)) return null
    if (name.equals("Threads", ignoreCase = true)) return null
    if (name.equals("Thread", ignoreCase = true) || name.equals("Channel", ignoreCase = true)) return null
    if (threadHeaderIdentityLikeTitle(name)) return null
    return "#$name"
}

internal fun existingThreadChannelHeaderSubtitle(subtitle: String?): String? =
    subtitle?.trim()?.takeIf { it.startsWith("#") }?.let(::threadChannelHeaderSubtitle)

internal fun threadHeaderDisplayTitle(
    title: String?,
    surfaceKind: String = RaftThreadSurfaceKind.Channel
): String =
    threadHeaderSafeTitleOrNull(title) ?: threadHeaderFallbackTitle(surfaceKind)

@Composable
internal fun ThreadPageHeader(
    agentFollowersHeader: @Composable () -> Unit = {},
    payload: RaftThreadScreenPayload,
    headerDisplay: ThreadHeaderDisplayModel = payload.threadHeaderFallbackDisplayModel(),
    originMessage: RaftThreadMessageItem? = payload.originMessage,
    /** The reply count the header shows ([threadHeaderReplyCount]); null while it is unknown. */
    originReplyCount: Int? = null,
    activityMutedIndicatorVisible: Boolean = payload.threadActivityMutedIndicatorVisible(null),
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    applyTopSafeArea: Boolean = true,
    showActions: Boolean = true,
    /** False for a channel / DM shown as the detail pane of a split (list on its left), as on web desktop. */
    showBack: Boolean = true,
    onBack: () -> Unit,
    onOpenInChannel: () -> Unit,
    onOpenMemberProfile: (MemberDetailIdentity) -> Unit,
    onOpenSearch: () -> Unit,
    onShowMembers: () -> Unit,
    onShowSettings: () -> Unit,
    /** The channel details are open beside the conversation: Settings is a pressed toggle (raft-design channel.md). */
    settingsOpen: Boolean = false,
    onTitleClick: (() -> Unit)? = null,
    /**
     * task #180: web parity (#9172/#9335). Null when this host cannot host a side column, which is
     * also when the menu item is hidden -- Focus mode relaxes layout, it never creates a column.
     */
    onToggleFocusMode: (() -> Unit)? = null,
    focusModeActive: Boolean = false,
    /** task #180: null when this thread has no message column to follow. */
    threadFollowed: Boolean? = null,
    onToggleThreadFollow: (() -> Unit)? = null,
    /** botiverse/slock #11687: the More menu's Mark as Unread (null: not a followed reply thread). */
    onMarkThreadUnread: (() -> Unit)? = null,
    /** Task v2: the task's title block and More menu rows (null: not a task, or not known yet). */
    taskConversationChrome: TaskConversationHeaderChrome? = null,
    /**
     * The thread's (or task conversation's) notification level row in the More menu (slock
     * docs/contracts/notifications.md §1.1); null leaves it out.
     */
    notificationLevelTarget: RaftNotificationLevelTarget? = null,
    /** Task v2: the Tasks / Files card toggles between Search and Settings (null: none). */
    sideCardToggles: ThreadHeaderSideCardToggles? = null,
    /** The thread timeline's sheet (web ThreadOverflowMenu Timeline); null where the ⋯ doesn't offer it. */
    onOpenTimeline: (() -> Unit)? = null,
    /** Agent Lounge v0: the Lounge this page is (or is in), and an agent DM's Lounge toggle. */
    lounge: ThreadPageLounge? = null,
    /** Share loop: the ⋯ menu's Share row and Copy link (ThreadShareOut.kt); null: none (the loop off, a DM). */
    shareEntry: ThreadHeaderShareEntry? = null,
) {
    // Agent Lounge v0 (web ChatPanel `lounge-header`): a Lounge is headed "{Name}'s Lounge" with its
    // agent's face and the Lounge mark; no # tile, no description. A thread in it names the Lounge.
    val headerLounge = lounge?.takeIf { it.lounge != null }
    val loungeHeader = headerLounge?.takeIf { headerDisplay.surfaceKind == RaftThreadSurfaceKind.Channel }
    val loungeTitle = headerLounge?.let { agentLoungeTitle(it.agentName) }
    val dmLoungeToggle = lounge?.onOpenDmAgentLounge?.takeIf { headerDisplay.surfaceKind == RaftThreadSurfaceKind.DirectMessage }
    val loungeLabel = stringResource(Res.string.agent_lounge_label)
    // Task v2 (web TaskConversationBar): a task conversation's search is the More menu's Search in Task.
    val taskConversation = payload.isTaskConversationSurface()
    val topSafeArea = if (applyTopSafeArea) raftTopSafeAreaPadding() else 0.dp
    // raft-design conversation.md "Threads" (web #15285): the title names the original message once
    // it scrolls out of view; a parent the person can't open offers no way in (ThreadHeaderOriginalTitle.kt).
    val headerOriginal =
        LocalThreadHeaderOriginalState.current?.takeIf {
            headerDisplay.surfaceKind == RaftThreadSurfaceKind.ReplyThread && headerDisplay.title == ThreadReplyPageTitle
        }
    val parentReachable = headerOriginal?.parentReachable != false
    val replyThreadReplyCountLabel =
        when {
            headerDisplay.surfaceKind != RaftThreadSurfaceKind.ReplyThread || originReplyCount == null -> null
            originReplyCount == 1 -> stringResource(Res.string.thread_inline_reply_count_one)
            else -> stringResource(Res.string.thread_inline_reply_count_many, originReplyCount)
        }
    val headerSubtitle =
        when {
            loungeHeader != null -> null
            headerLounge != null && headerDisplay.surfaceKind == RaftThreadSurfaceKind.ReplyThread ->
                replyThreadHeaderSubtitle(loungeTitle, replyThreadReplyCountLabel)
            else -> replyThreadHeaderSubtitle(headerDisplay.subtitle, replyThreadReplyCountLabel)
        }
    // Web ThreadPanelHeader (#10285): a reply thread's conversation name does what View in Channel
    // does (the parent message in its channel or DM), on the Compose head and on the native bar.
    val channelLinkLabel =
        (loungeTitle ?: headerDisplay.subtitle)?.takeIf {
            it.isNotBlank() &&
                headerDisplay.surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
                parentReachable &&
                payload.threadHeaderOpenInParentActionVisible()
        }
    val localizedHeaderTitle =
        loungeTitle?.takeIf { loungeHeader != null } ?: localizedThreadHeaderTitle(
            title = headerDisplay.title,
            surfaceKind = headerDisplay.surfaceKind,
            localizedReplyThread = stringResource(Res.string.thread_reply_title),
            localizedChannel = stringResource(Res.string.thread_channel_title),
            localizedDirectMessage = stringResource(Res.string.thread_direct_message_title)
        )
    val resolvedDmPeerAvatarWireValue =
        rememberRaftMemberAvatarWireValue(
            memberAvatarResolver,
            headerDisplay.dmPeerIdentity?.memberId?.value
        )
    // botiverse/slock #11273: an App Bot has no presence (it would read "Offline" forever); its
    // DM header carries the Bot badge instead.
    val dmAppBot = rememberRaftMemberAppBot(headerDisplay.dmAgentActivityMemberId())
    val dmAgentActivity =
        rememberRaftAgentStatusIndicatorState(
            agentStatusResolver = agentStatusResolver,
            memberId = headerDisplay.dmAgentActivityMemberId().takeIf { dmAppBot == null }
        )
    val showHeaderActions =
        showActions &&
            (
                headerDisplay.surfaceKind == RaftThreadSurfaceKind.ReplyThread ||
                    taskConversation ||
                    payload.threadHeaderSearchAction() != null ||
                    threadHeaderSettingsActionVisible(payload.showSettingsAction, headerDisplay.surfaceKind) ||
                    payload.threadHeaderOpenInParentActionVisible()
            )
    // The Tasks page's task column (RaftTasksTaskColumn.kt; web TaskConversationBar beside the
    // list): its Expand and Close end the head, also while the page is still loading.
    val taskColumnHead = LocalRaftTaskColumnHead.current
    // iPhone Duo (raft-design #859): beside the system's side column, Back and the page's actions
    // are the column's (Back first, two actions, the rest in its ⋯); the header keeps the title
    // and the subtitle, from the 16dp margin, without Back and without icon buttons.
    val showsBack = threadHeaderShowsBack(showBack, headerDisplay.surfaceKind)
    val controlsInColumn =
        raftSideColumnControls(
            back =
                if (showsBack) {
                    raftSideColumnBackAction(onBack)
                } else {
                    null
                },
            actions =
                if (showActions) {
                    threadHeaderSideColumnActions(
                        payload = payload,
                        surfaceKind = headerDisplay.surfaceKind,
                        taskConversation = taskConversation,
                        onOpenSearch = onOpenSearch,
                        onShowSettings = onShowSettings,
                        onOpenInChannel = onOpenInChannel,
                        parentReachable = parentReachable,
                        onToggleFocusMode = onToggleFocusMode,
                        focusModeActive = focusModeActive,
                        threadFollowed = threadFollowed,
                        onToggleThreadFollow = onToggleThreadFollow,
                        onMarkThreadUnread = onMarkThreadUnread,
                        onViewTaskInChannel = taskConversationChrome?.onViewInChannel,
                        sideCardToggles = sideCardToggles,
                        onOpenTimeline = onOpenTimeline,
                        onDeleteTask = taskConversationChrome?.onDeleteTask,
                        shareEntry = shareEntry,
                    )
                } else {
                    emptyList()
                },
        )
    // PROTOTYPE (native nav bar): the conversation's buttons on the system bar: Members (with its
    // member count, plain) first, then the Duo column's actions (the header's Search / Settings / Open in
    // Channel and its ⋯ rows: Focus mode, Search in Task, View in Channel, Mark as Unread,
    // Follow), then the ⋯'s notification level row. The bar keeps two buttons, the rest in ⋯.
    val nativeSideActions =
        if (showActions && LocalRaftNativeNavBarScript.current != null) {
            threadHeaderSideColumnActions(
                payload = payload,
                surfaceKind = headerDisplay.surfaceKind,
                taskConversation = taskConversation,
                onOpenSearch = onOpenSearch,
                onShowSettings = onShowSettings,
                onOpenInChannel = onOpenInChannel,
                parentReachable = parentReachable,
                onToggleFocusMode = onToggleFocusMode,
                focusModeActive = focusModeActive,
                threadFollowed = threadFollowed,
                onToggleThreadFollow = onToggleThreadFollow,
                onMarkThreadUnread = onMarkThreadUnread,
                onViewTaskInChannel = taskConversationChrome?.onViewInChannel,
                onOpenTimeline = onOpenTimeline,
                onDeleteTask = taskConversationChrome?.onDeleteTask,
                shareEntry = shareEntry,
            )
        } else {
            emptyList()
        }
    val nativeLevelTarget = notificationLevelTarget.whenLevelsSupported()
    val nativeLevelLabel = rememberRaftNotificationLevelMenuLabel(nativeLevelTarget)
    var nativeLevelDialogOpen by remember { mutableStateOf(false) }
    if (nativeLevelDialogOpen && nativeLevelTarget != null) {
        RaftNotificationLevelDialog(target = nativeLevelTarget, onDismiss = { nativeLevelDialogOpen = false })
    }
    val membersLabel = stringResource(Res.string.settings_members_title)
    val nativeNavBarActions =
        if (!showActions) {
            emptyList()
        } else {
            buildList {
                if (payload.showMembersAction && headerDisplay.surfaceKind != RaftThreadSurfaceKind.DirectMessage && loungeHeader == null) {
                    add(
                        RaftNativeNavBarAction(
                            "members",
                            membersLabel,
                            "person.2",
                            onShowMembers,
                            // The web header's member count: a quiet number beside the glyph,
                            // not the red unread badge (28 members read as 28 unread; #proj-mobile:#162).
                            count = raftNativeNavBarCountText(payload.membersCount),
                            priority = Int.MAX_VALUE,
                            icon = RaftLucideIconName.Users,
                        )
                    )
                }
                if (dmLoungeToggle != null) {
                    add(RaftNativeNavBarAction("lounge", loungeLabel, "person.2", dmLoungeToggle, icon = RaftLucideIconName.Users))
                }
                addAll(nativeSideActions.toRaftNativeNavBarActions())
                if (nativeLevelTarget != null) {
                    add(
                        RaftNativeNavBarAction(
                            NOTIFICATION_LEVEL_MENU_ITEM_ID,
                            nativeLevelLabel,
                            "bell",
                            { nativeLevelDialogOpen = true },
                            placement = RaftNativeNavBarPlacement.Overflow,
                            icon = RaftLucideIconName.BellRing,
                        )
                    )
                }
            }
        }
    RaftTopPageHeader(
        title = localizedHeaderTitle,
        nativeSubtitle = headerSubtitle,
        nativeOnSubtitleClick = onOpenInChannel.takeIf { channelLinkLabel != null },
        nativeActions = nativeNavBarActions,
        // PROTOTYPE: the channel glyph / DM avatar and the refresh spinner in the host's title view.
        nativeTitleExtras =
            RaftNativeNavBarTitleExtras(
                leading =
                    when {
                        loungeHeader != null ->
                            RaftNativeNavBarTitleLeading.Avatar(
                                label = loungeHeader.agentName,
                                avatarWireValue = loungeHeader.agentAvatarWireValue,
                                kind = RaftAvatarKind.Agent,
                            )
                        headerDisplay.surfaceKind == RaftThreadSurfaceKind.DirectMessage ->
                            RaftNativeNavBarTitleLeading.Avatar(
                                label = headerDisplay.title.ifBlank { headerDisplay.dmPeerAvatarLabel },
                                avatarWireValue = resolvedDmPeerAvatarWireValue ?: headerDisplay.dmPeerAvatarWireValue,
                                kind = raftAvatarKind(headerDisplay.dmPeerIdentity?.kind),
                            )
                        payload.showModeTabs ->
                            RaftNativeNavBarTitleLeading.Channel(headerDisplay.channelKindGlyph)
                        else -> null
                    },
                refreshing = payload.backgroundRefreshing,
            ),
        backgroundColor = RaftColor.Canvas,
        topSafeAreaOverride = topSafeArea,
        contentStartPadding =
            when {
                controlsInColumn -> RaftTopPageHeaderContentEndPadding
                showBack -> ThreadHeaderEdgeInset
                else -> ThreadSplitDetailEdgeInset
            },
        // The 3dp edge inset only mirrors Back when a trailing PanelAction sits at the end;
        // without one the title keeps the default end padding instead of running to the edge.
        contentEndPadding =
            if ((showHeaderActions && !controlsInColumn) || taskColumnHead != null) ThreadHeaderEdgeInset else RaftTopPageHeaderContentEndPadding,
        contentSpacing = ThreadHeaderContentSpacing,
        titleLeadingContent = {
            // Back is the same 44dp frameless PanelAction as the trailing actions (not the shared
            // 36dp leading slot), so its arrow mirrors the last trailing glyph 14dp from the edge.
            if (showsBack && !controlsInColumn) {
                RaftBackButton(onClick = onBack, label = stringResource(Res.string.common_back))
            }
            if (loungeHeader != null) {
                // The face is decorative for screen readers; its tap opens the agent's profile.
                // Raw clickable on purpose, as on the DM peer's face below: the app's press wash
                // draws over the face, where raftClickable's would sit behind the opaque avatar.
                Box(
                    modifier =
                        Modifier
                            .padding(end = ThreadHeaderChannelTitleGapExtra)
                            .then(
                                loungeHeader.onOpenAgentProfile?.let { open -> Modifier.clickable(onClick = open) } ?: Modifier
                            )
                ) {
                    RaftLoungeAvatar(
                        agentId = loungeHeader.lounge?.agentId,
                        avatarWireValue = loungeHeader.agentAvatarWireValue,
                        label = loungeHeader.agentName,
                        cacheServerId = loungeHeader.cacheServerId,
                        avatarSize = 35.dp,
                    )
                }
            } else if (headerDisplay.surfaceKind == RaftThreadSurfaceKind.DirectMessage) {
                ThreadDmPeerAvatarTile(
                    avatarWireValue = resolvedDmPeerAvatarWireValue ?: headerDisplay.dmPeerAvatarWireValue,
                    // The peer's name (the header title): initials come from its words.
                    avatarLabel = headerDisplay.title.ifBlank { headerDisplay.dmPeerAvatarLabel },
                    kind = raftAvatarKind(headerDisplay.dmPeerIdentity?.kind),
                    cacheSubject =
                        headerDisplay.dmPeerIdentity?.let { identity ->
                            raftMemberAvatarCacheSubject(identity.kind, identity.memberId.value)
                        },
                    cacheServerId = headerDisplay.dmPeerIdentity?.sourceServerId?.value,
                    onClick =
                        headerDisplay.dmPeerIdentity?.let { identity ->
                            { onOpenMemberProfile(identity) }
                        }
                )
            } else if (payload.showModeTabs) {
                Box(modifier = Modifier.padding(end = ThreadHeaderChannelTitleGapExtra)) {
                    RaftChannelIdentityTile(glyph = headerDisplay.channelKindGlyph)
                }
            }
        },
        titleContent = {
            if (taskConversationChrome != null) {
                // Task v2 (web TaskConversationBar): the ref over "Task #7", and on a phone the task's
                // state with the chevron that pulls the head down.
                TaskConversationBarTitle(taskConversationChrome, Modifier.weight(1f))
            } else
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .then(if (loungeHeader != null) Modifier.testTag(AgentLoungeTestTags.Header) else Modifier)
                        // A long press copies the channel / DM name; the tap (scroll to top) stays.
                        .raftCopyable(
                            loungeTitle?.takeIf { loungeHeader != null }
                                ?: threadHeaderCopyText(headerDisplay.title, headerDisplay.surfaceKind),
                            onClick = onTitleClick,
                        ),
                // raft-ui `PanelHeaderContent`: the 22dp title line sits straight on the 18dp meta line.
                verticalArrangement = Arrangement.Top
            ) {
                ThreadHeaderNameFirstRow(modifier = Modifier.fillMaxWidth()) {
                    ThreadHeaderTitleText(
                        normalTitle = localizedHeaderTitle,
                        state = headerOriginal,
                        // raft-ui `PanelTitle`: text-headline (17/22 bold; 16/24 in the wide type).
                        style = RaftType.Headline.copy(color = RaftColor.TextBlack),
                        color = RaftColor.TextBlack,
                    )
                    if (
                        loungeHeader == null &&
                            threadHeaderShowsArchivedBadge(payload, directoryArchived = headerDisplay.channelArchived)
                    ) {
                        ThreadArchivedChannelBadge()
                    }
                    if (headerDisplay.surfaceKind == RaftThreadSurfaceKind.Channel && loungeHeader == null) {
                        RaftExternalBridgeBadge(headerDisplay.bridgeProvider)
                    }
                    if (activityMutedIndicatorVisible) {
                        ThreadActivityMutedBadge()
                    }
                    if (dmAppBot != null) {
                        RaftAppBotBadge(identity = dmAppBot, botName = localizedHeaderTitle)
                    } else {
                        dmAgentActivity?.let {
                            ThreadDmAgentActivity(state = it, modifier = Modifier.layoutId(ThreadHeaderYieldingSlot))
                        }
                    }
                }
                // Web #10285: under a View in Channel tooltip.
                if (channelLinkLabel != null) {
                    ThreadHeaderChannelLinkSubtitle(
                        channelLabel = channelLinkLabel,
                        replyCountLabel = replyThreadReplyCountLabel,
                        onOpenInChannel = onOpenInChannel
                    )
                } else {
                    headerSubtitle?.let {
                        Text(
                            text = it,
                            // raft-ui `PanelMeta` (md): sans text-footnote on foreground-hint.
                            style = ThreadHeaderMetaStyle,
                            color = RaftColor.ForegroundHint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        trailingContent =
            if (showHeaderActions && controlsInColumn && taskColumnHead == null) {
                // The Agent followers are the task's facts, not a button: they stay in the header.
                { agentFollowersHeader() }
            } else if (showHeaderActions || taskColumnHead != null) {
                {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ThreadHeaderActionGroupSpacing)
                ) {
                    if (showHeaderActions && controlsInColumn) {
                        agentFollowersHeader()
                    } else if (showHeaderActions) {
                    agentFollowersHeader()
                    // Web ThreadPanel: a reply thread's Search in Thread and View in Channel live in
                    // its ⋯ menu ("thread commands live in the menu"), not as header buttons.
                    val replyThreadMenu = headerDisplay.surfaceKind == RaftThreadSurfaceKind.ReplyThread
                    if (!taskConversation && !replyThreadMenu && payload.threadHeaderSearchAction() != null) {
                        ThreadHeaderIconButton(icon = RaftLucideIconName.Search, onClick = onOpenSearch)
                    }
                    // Agent Lounge v0 (web useChannelHeadToggles): an agent DM's Lounge, between Search and Settings.
                    if (dmLoungeToggle != null) {
                        Box(Modifier.testTag(AgentLoungeTestTags.DmHeaderToggle)) {
                            ConversationHeaderToggle(
                                icon = RaftLucideIconName.Users,
                                selected = false,
                                label = loungeLabel,
                                onClick = dmLoungeToggle
                            )
                        }
                    }
                    sideCardToggles?.let { toggles ->
                        // web ChannelTasksToggle / ChannelFilesToggle: no count on either.
                        toggles.cardsInHeader().forEach { card ->
                            ConversationHeaderToggle(
                                icon = card.icon,
                                selected = toggles.cards.isOpen(card),
                                label = conversationSideCardToggleLabel(card, toggles.cards.isOpen(card)),
                                onClick = { toggles.onToggle(card) }
                            )
                        }
                    }
                    if (threadHeaderSettingsActionVisible(payload.showSettingsAction, headerDisplay.surfaceKind)) {
                        // web PanelToggleAction: pressed while the details are open, named Close then.
                        ConversationHeaderToggle(
                            icon = RaftLucideIconName.Settings,
                            selected = settingsOpen,
                            label =
                                stringResource(
                                    if (settingsOpen) Res.string.channel_details_close else Res.string.channel_details_open
                                ),
                            onClick = onShowSettings,
                        )
                    }
                    if (!replyThreadMenu && payload.threadHeaderOpenInParentActionVisible()) {
                        ThreadHeaderIconButton(icon = RaftLucideIconName.MapPin, onClick = onOpenInChannel)
                    }
                    val replyThreadSearch = onOpenSearch.takeIf { replyThreadMenu && payload.threadHeaderSearchAction() != null }
                    val replyThreadViewInChannel =
                        onOpenInChannel.takeIf { replyThreadMenu && parentReachable && payload.threadHeaderOpenInParentActionVisible() }
                    val toggleFocus = onToggleFocusMode
                    val hasPanelMenu =
                        onToggleThreadFollow != null || onMarkThreadUnread != null || taskConversation ||
                            replyThreadSearch != null || replyThreadViewInChannel != null || onOpenTimeline != null ||
                            shareEntry != null
                    if (hasPanelMenu) {
                        ThreadHeaderFocusModeMenu(
                            followed = threadFollowed,
                            onToggleFollow = onToggleThreadFollow,
                            onMarkUnread = onMarkThreadUnread,
                            onSearchInTask = onOpenSearch.takeIf { taskConversation } ?: replyThreadSearch,
                            onTimeline = onOpenTimeline,
                            onViewInChannel = taskConversationChrome?.onViewInChannel.takeIf { taskConversation } ?: replyThreadViewInChannel,
                            taskFollowLabels = taskConversation,
                            onDeleteTask = taskConversationChrome?.onDeleteTask.takeIf { taskConversation },
                            drawLocally = toggleFocus == null,
                            notificationLevelTargetInput = notificationLevelTarget,
                            shareEntry = shareEntry,
                        )
                    }
                    // web ThreadPanelHeader (slock #12211): Focus mode is the head's Expand, a window
                    // control after ⋯ and never in it, so a wide layout always shows it.
                    toggleFocus?.let { ThreadHeaderFocusModeToggle(focused = focusModeActive, onToggle = it) }
                    }
                    taskColumnHead?.let { RaftTaskColumnHeadActions(it) }
                }
                }
            } else {
                null
            },
        showBodyTopBanner = false,
    )
}

/** raft-ui `PanelMeta` (default size sm, as the web PanelHeader passes no size): sans text-caption 12/16 500. */
private val ThreadHeaderMetaStyle get() = RaftType.Caption

/**
 * A reply thread's header meta line with its channel name as a link (web ThreadPanel #10285): the
 * name opens the parent message in its channel, the reply count after it stays plain text.
 */
@Composable
internal fun ThreadHeaderChannelLinkSubtitle(
    channelLabel: String,
    replyCountLabel: String?,
    onOpenInChannel: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RaftTooltip(
            text = stringResource(Res.string.task_conversation_view_in_channel),
            modifier = Modifier.weight(1f, fill = false)
        ) {
            RaftTextLink(
                text = channelLabel,
                onClick = onOpenInChannel,
                style = ThreadHeaderMetaStyle,
                maxLines = 1
            )
        }
        replyCountLabel?.takeIf { it.isNotBlank() }?.let { count ->
            Text(
                text = " · $count",
                style = ThreadHeaderMetaStyle,
                color = RaftColor.ForegroundHint,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * The conversation header's actions for the Duo side column, in the header's order: Search and
 * Settings (or Open in Channel) first, so they are the two the column shows; the More menu's rows
 * (Focus mode, Search in Task, View in Channel, Follow) after them, in its ⋯.
 */
@Composable
private fun threadHeaderSideColumnActions(
    payload: RaftThreadScreenPayload,
    surfaceKind: String,
    taskConversation: Boolean,
    onOpenSearch: () -> Unit,
    onShowSettings: () -> Unit,
    onOpenInChannel: () -> Unit,
    /** False where the person can't open the parent conversation: no View in Channel (ThreadHeaderOriginal.Unavailable). */
    parentReachable: Boolean = true,
    onToggleFocusMode: (() -> Unit)?,
    focusModeActive: Boolean,
    threadFollowed: Boolean?,
    onToggleThreadFollow: (() -> Unit)?,
    onMarkThreadUnread: (() -> Unit)?,
    onViewTaskInChannel: (() -> Unit)?,
    sideCardToggles: ThreadHeaderSideCardToggles? = null,
    onOpenTimeline: (() -> Unit)? = null,
    /** Task v2: Delete task, the ⋯ menu's last row (web #14115); null for a reader who may not. */
    onDeleteTask: (() -> Unit)? = null,
    /** Share loop: Share and Copy link, in the ⋯; null: none. */
    shareEntry: ThreadHeaderShareEntry? = null,
): List<RaftSideAction> {
    val shareLabel = shareEntry?.let { stringResource(it.shareLabel) }
    val shareCopyLinkLabel = stringResource(Res.string.thread_link_menu_copy_link)
    val timelineLabel = stringResource(Res.string.thread_outline_list_title)
    val markUnreadLabel = stringResource(Res.string.home_action_mark_as_unread)
    val searchLabel = stringResource(Res.string.thread_action_search)
    val settingsLabel = stringResource(Res.string.settings_title)
    val viewInChannelLabel = stringResource(Res.string.task_conversation_view_in_channel)
    val deleteTaskLabel = stringResource(Res.string.task_delete_action)
    val focusLabel =
        stringResource(if (focusModeActive) Res.string.tasks_task_column_restore else Res.string.tasks_task_column_expand)
    val searchInTaskLabel = stringResource(Res.string.task_conversation_search)
    val followLabel =
        threadFollowed?.let { followed ->
            stringResource(
                when {
                    taskConversation && followed -> Res.string.task_conversation_unfollow
                    taskConversation -> Res.string.task_conversation_follow
                    followed -> Res.string.thread_action_unfollow_thread
                    else -> Res.string.thread_action_follow_thread
                }
            )
        }
    val sideCardLabels =
        ConversationSideCard.entries.associateWith { card ->
            conversationSideCardToggleLabel(card, sideCardToggles?.cards?.isOpen(card) == true)
        }
    return buildList {
        if (!taskConversation && payload.threadHeaderSearchAction() != null) {
            add(
                raftSideColumnLucideAction(
                    "thread-search",
                    searchLabel,
                    RaftLucideIconName.Search,
                    onOpenSearch,
                )
            )
        }
        sideCardToggles?.let { toggles ->
            toggles.cardsInHeader().forEach { card ->
                add(
                    raftSideColumnLucideAction(
                        "thread-side-card-${card.name.lowercase()}",
                        sideCardLabels.getValue(card),
                        card.icon,
                        { toggles.onToggle(card) },
                    )
                )
            }
        }
        if (threadHeaderSettingsActionVisible(payload.showSettingsAction, surfaceKind)) {
            add(
                raftSideColumnLucideAction(
                    "thread-settings",
                    settingsLabel,
                    RaftLucideIconName.Settings,
                    onShowSettings,
                )
            )
        }
        // Web ThreadOverflowMenu: Timeline after Search, before View in Channel; in the ⋯.
        if (onOpenTimeline != null) {
            add(raftSideColumnLucideAction(THREAD_TIMELINE_MENU_ITEM_ID, timelineLabel, RaftLucideIconName.List, onOpenTimeline, priority = -1))
        }
        if (parentReachable && payload.threadHeaderOpenInParentActionVisible()) {
            add(raftSideColumnLucideAction("thread-open-in-channel", viewInChannelLabel, RaftLucideIconName.MapPin, onOpenInChannel))
        }
        // web ThreadPanelHeader (slock #12211): Focus mode (the head's Expand) never goes into the ⋯.
        if (onToggleFocusMode != null) {
            val icon = if (focusModeActive) RaftLucideIconName.Minimize2 else RaftLucideIconName.Maximize2
            add(raftSideColumnLucideAction(FOCUS_MODE_MENU_ITEM_ID, focusLabel, icon, onToggleFocusMode).copy(keepVisible = true))
        }
        if (taskConversation) {
            add(raftSideColumnLucideAction(SEARCH_IN_TASK_MENU_ITEM_ID, searchInTaskLabel, RaftLucideIconName.Search, onOpenSearch))
            if (onViewTaskInChannel != null) {
                add(raftSideColumnLucideAction(VIEW_TASK_IN_CHANNEL_MENU_ITEM_ID, viewInChannelLabel, RaftLucideIconName.MapPin, onViewTaskInChannel))
            }
        }
        if (onMarkThreadUnread != null) {
            add(
                raftSideColumnLucideAction(
                    MARK_THREAD_UNREAD_MENU_ITEM_ID,
                    markUnreadLabel,
                    RaftLucideIconName.MessageSquareDot,
                    onMarkThreadUnread,
                    priority = -1,
                )
            )
        }
        if (followLabel != null && threadFollowed != null && onToggleThreadFollow != null) {
            add(
                raftSideColumnLucideAction(
                    FOLLOW_THREAD_MENU_ITEM_ID,
                    followLabel,
                    threadFollowMenuIcon(threadFollowed),
                    onToggleThreadFollow,
                    priority = -1,
                )
            )
        }
        if (shareEntry != null && shareLabel != null) {
            add(raftSideColumnLucideAction(SHARE_OUT_MENU_ITEM_ID, shareLabel, RaftLucideIconName.Share, shareEntry.onShare, priority = -1))
            add(
                raftSideColumnLucideAction(
                    SHARE_OUT_COPY_LINK_MENU_ITEM_ID,
                    shareCopyLinkLabel,
                    RaftLucideIconName.Link,
                    shareEntry.onCopyLink,
                    priority = -1,
                )
            )
        }
        if (taskConversation && onDeleteTask != null) {
            add(raftSideColumnLucideAction(DELETE_TASK_MENU_ITEM_ID, deleteTaskLabel, RaftLucideIconName.Trash2, onDeleteTask, priority = -1))
        }
    }
}

@Composable
private fun ThreadActivityMutedBadge() {
    // Header space is tight (name, status, actions), so muted shows as the bell-off icon alone
    // (artin, 11-08); screen readers still hear "Muted".
    val mutedLabel = stringResource(Res.string.member_label_muted)
    Box(
        modifier =
            Modifier
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .clearAndSetSemantics { contentDescription = mutedLabel },
        contentAlignment = Alignment.Center
    ) {
        RaftLucideIcon(
            RaftLucideIconName.BellOff,
            Modifier.size(14.dp),
            color = raftArgbHex(RaftColor.ForegroundHint)
        )
    }
}

/**
 * What a long press on the conversation header copies: the channel or DM peer name as shown.
 * Null for a reply thread (its title previews the parent message, a message body) and for the
 * generic stand-ins ("Channel", "Direct Message") shown before the name loads.
 */
internal fun threadHeaderCopyText(title: String, surfaceKind: String): String? =
    title.trim().takeIf {
        it.isNotEmpty() &&
            surfaceKind != RaftThreadSurfaceKind.ReplyThread &&
            !it.isGenericThreadHeaderFallbackLabel()
    }

internal fun localizedThreadHeaderTitle(
    title: String,
    surfaceKind: String,
    localizedReplyThread: String,
    localizedChannel: String,
    localizedDirectMessage: String
): String =
    when {
        surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
            title == ThreadReplyPageTitle -> localizedReplyThread
        surfaceKind == RaftThreadSurfaceKind.Channel && title == "Channel" -> localizedChannel
        surfaceKind == RaftThreadSurfaceKind.DirectMessage &&
            title == "Direct Message" -> localizedDirectMessage
        else -> title
    }

internal fun ThreadHeaderDisplayModel.dmAgentActivityMemberId(): String? =
    dmPeerIdentity
        ?.takeIf {
            surfaceKind == RaftThreadSurfaceKind.DirectMessage &&
                it.kind == MemberKind.Agent
        }
        ?.memberId
        ?.value
        ?.trim()
        ?.takeIf(String::isNotBlank)

private const val ThreadHeaderYieldingSlot = "yielding"

/**
 * The header's title line: the name, its badges, then the agent's activity, 8dp apart. The name is
 * measured first (web ChatPanel: `PanelTitle` keeps its width, AgentDMStatus is `flex-1 min-w-0`),
 * so on a crowded phone head the activity words give way down to the dot and the name stays
 * readable (artin 2026-11-06: with task v2's three buttons the name had shrunk to "."). The badges
 * keep their width; the [ThreadHeaderYieldingSlot] child gets what is left after them and the name,
 * but never less than its dot.
 */
@Composable
private fun ThreadHeaderNameFirstRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = ThreadHeaderDmAgentActivityLeadingGap.roundToPx()
        val loose = constraints.copy(minWidth = 0)
        val name = measurables.first()
        val yielding = measurables.drop(1).firstOrNull { it.layoutId == ThreadHeaderYieldingSlot }
        val fixed = measurables.drop(1).filter { it !== yielding }.map { it.measure(loose) }
        val gaps = gap * (measurables.size - 1)
        val yieldingMin = if (yielding == null) 0 else ThreadHeaderDmAgentActivityDotSize.roundToPx()
        val namePlaceable =
            if (constraints.hasBoundedWidth) {
                val room = constraints.maxWidth - fixed.sumOf { it.width } - gaps - yieldingMin
                name.measure(loose.copy(maxWidth = room.coerceAtLeast(0)))
            } else {
                name.measure(loose)
            }
        val yieldingPlaceable =
            yielding?.let {
                if (constraints.hasBoundedWidth) {
                    val rest = constraints.maxWidth - namePlaceable.width - fixed.sumOf { p -> p.width } - gaps
                    it.measure(loose.copy(maxWidth = rest.coerceAtLeast(0)))
                } else {
                    it.measure(loose)
                }
            }
        val placeables = listOf(namePlaceable) + fixed + listOfNotNull(yieldingPlaceable)
        val width = constraints.constrainWidth(placeables.sumOf { it.width } + gap * (placeables.size - 1))
        val height = constraints.constrainHeight(placeables.maxOf { it.height })
        layout(width, height) {
            var x = 0
            placeables.forEach {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width + gap
            }
        }
    }
}

@Composable
private fun ThreadDmAgentActivity(state: RaftAgentStatusIndicatorState, modifier: Modifier = Modifier) {
    // Web ChatPanel AgentDMStatus (#10446): stuck on one tool call reads as that, in the
    // warning colour, not as a plain "busy". Otherwise the same localized caption as every
    // other live-status surface (SSOT audit).
    val toolStuck = state.visibleToolStuck()
    val label = if (toolStuck != null) localizedAgentToolStuckText(toolStuck) else state.localizedMemberStatusLabel()
    val labelColor = if (toolStuck != null) RaftColor.WarningStrong else RaftColor.ForegroundHint
    Row(
        modifier =
            modifier
                .widthIn(max = ThreadHeaderDmAgentActivityMaxWidth)
                .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
    ) {
        RaftStatusIndicator(
            color = state.color,
            size = ThreadHeaderDmAgentActivityDotSize,
        )
        Text(
            text = label,
            style =
                RaftType.Caption.copy(
                    color = labelColor,
                    fontFamily = RaftMonoFamily
                ),
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // The dot stays; the words give way (web AgentDMStatus `truncate`).
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

@Composable
internal fun RaftChannelIdentityTile(
    glyph: RaftChannelKindGlyph,
    tileSize: Dp = ThreadHeaderChannelTileSize,
    iconSize: Dp = ThreadHeaderChannelIconSize,
) {
    // raft-ui `PanelHeaderIcon`: Source Yellow sticker, sticker radius, 1.5dp sticker-ink edge.
    val shape = RoundedCornerShape(RaftAppRadius.Sticker)
    Box(
        modifier = Modifier.size(tileSize)
            .background(RaftColor.Brand, shape)
            .border(RaftAppStroke.Control, RaftColor.StickerInk, shape),
        contentAlignment = Alignment.Center
    ) {
        RaftChannelKindIcon(glyph, size = iconSize, color = raftArgbHex(RaftColor.StickerInk))
    }
}

/** A channel's glyph from its type alone ([raftChannelKindGlyph]) where nothing else is known. */
internal fun String?.threadChannelHeaderIcon(): RaftLucideIconName = raftChannelKindGlyph(this).iconName

@Composable
private fun ThreadDmPeerAvatarTile(
    avatarWireValue: String?,
    avatarLabel: String,
    kind: RaftAvatarKind,
    cacheSubject: build.raft.app.data.RaftAvatarCacheSubject? = null,
    cacheServerId: String? = null,
    onClick: (() -> Unit)? = null
) {
    // Raw clickable on purpose, as on the lounge agent's face above: the app's press wash draws
    // over the face, where raftClickable's would sit behind the opaque avatar.
    Box(
        modifier =
            Modifier
                .size(ThreadHeaderDmAvatarSize)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        RaftAppAvatar(
            kind = kind,
            avatarWireValue = avatarWireValue,
            label = avatarLabel,
            avatarSize = ThreadHeaderDmAvatarSize,
            cacheSubject = cacheSubject,
            cacheServerId = cacheServerId,
        )
    }
}

@Composable
private fun ThreadHeaderIconButton(icon: RaftLucideIconName, onClick: () -> Unit) {
    ThreadHeaderCompactAction(icon = icon, onClick = onClick)
}

/** Task v2's header toggles ([ThreadPageHeader]); [cards] are the page's open cards. */
internal class ThreadHeaderSideCardToggles(
    val tasksAvailable: Boolean,
    val cards: ConversationSideCardsState,
    val onToggle: (ConversationSideCard) -> Unit,
) {
    /** Tasks before Files (raft-design channel.md); Tasks only where the conversation has tasks. */
    fun cardsInHeader(): List<ConversationSideCard> =
        ConversationSideCard.entries.filter { it != ConversationSideCard.Tasks || tasksAvailable }
}

/** web `task.sidePanel.open/close`, `message.chatPanel.filesCard.open/close`. */
@Composable
private fun conversationSideCardToggleLabel(card: ConversationSideCard, open: Boolean): String =
    stringResource(
        when (card) {
            ConversationSideCard.Tasks ->
                if (open) Res.string.thread_side_card_tasks_close else Res.string.thread_side_card_tasks_open
            ConversationSideCard.Files ->
                if (open) Res.string.thread_side_card_files_close else Res.string.thread_side_card_files_open
        }
    )

/**
 * A header toggle (raft-ui `PanelToggleAction`): the yellow sticker while its card is open, named
 * "Close Tasks" then; the toggle state is announced by [RaftIconToggleButton].
 */
/**
 * Focus mode as the head shows it on a wide layout (web ThreadPanelHeader `RowExpandAction`,
 * slock #12211): Expand, pressed Restore, a window control after ⋯ and never in it.
 */
@Composable
private fun ThreadHeaderFocusModeToggle(focused: Boolean, onToggle: () -> Unit) {
    val label = stringResource(if (focused) Res.string.tasks_task_column_restore else Res.string.tasks_task_column_expand)
    Box(Modifier.testTag(FOCUS_MODE_MENU_ITEM_ID)) {
        ConversationHeaderToggle(
            icon = if (focused) RaftLucideIconName.Minimize2 else RaftLucideIconName.Maximize2,
            selected = focused,
            label = label,
            onClick = onToggle,
        )
    }
}

@Composable
private fun ConversationHeaderToggle(
    icon: RaftLucideIconName,
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    RaftIconToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        tooltip = label,
    ) {
        RaftContentLucideIcon(icon, size = ThreadHeaderActionIconSize)
    }
}

/**
 * This page's composer focus ([ComposerFocusOnOpen]): the platform policy
 * ([rememberComposerAutofocusOnOpen]), the page shown, and, in the wide detail, no reply thread
 * beside it ([composerAutofocusYieldsToSideThread]: the thread panel gets the focus, and gives it
 * back when it closes only if the focus was lost), and on a phone or tablet no focused message
 * ([opensOnMessage]: the open lands on one message, which the soft keyboard would push off the
 * screen; [composerAutofocusSkipsMessageOpen]). The composer reads it when its request runs,
 * so a later change here (a keyboard reconnected, the page shown again) never re-runs the open.
 */
@Composable
internal fun rememberThreadComposerAutofocusOnOpen(
    routeActive: Boolean,
    adaptiveCloseRequest: RaftAdaptiveDetailCloseRequest?,
    adaptiveStoreScope: RaftShellStoreScope,
    opensOnMessage: Boolean,
): ComposerFocusOnOpen {
    val policy = rememberComposerAutofocusOnOpen()
    val yieldsToSideThread =
        if (adaptiveCloseRequest == null) false else rememberThreadComposerYieldsToSideThread(adaptiveCloseRequest, adaptiveStoreScope)
    return rememberComposerFocusOnOpen(
        policy = policy,
        shown = routeActive,
        yieldsToSideThread = yieldsToSideThread,
        opensOnMessage = composerAutofocusSkipsMessageOpen(desktopHost = raftPlatformHasFinePointer(), opensOnMessage = opensOnMessage),
    )
}

/** [composerAutofocusYieldsToSideThread] for the wide detail's page [adaptiveCloseRequest], live. */
@Composable
private fun rememberThreadComposerYieldsToSideThread(
    adaptiveCloseRequest: RaftAdaptiveDetailCloseRequest,
    adaptiveStoreScope: RaftShellStoreScope,
): Boolean {
    val pager = LocalActivity.current.getPager() as Pager
    val workspaceStore =
        remember(pager, adaptiveStoreScope) {
            RaftSharedStoreRegistry.get(Utils.bridgeModule(pager.pagerId)).adaptiveWorkspaceStore(adaptiveStoreScope)
        }
    val yieldsToSideThread by
        remember(workspaceStore, adaptiveCloseRequest) {
            workspaceStore.state
                .map { it.composerAutofocusYieldsToSideThread(adaptiveCloseRequest) }
                .distinctUntilChanged()
        }.collectAsState(
            initial = workspaceStore.snapshot().composerAutofocusYieldsToSideThread(adaptiveCloseRequest)
        )
    return yieldsToSideThread
}

/**
 * task #180 (web parity #9172/#9335): the thread panel's overflow menu, anchored in the header.
 *
 * Web puts Focus mode as the first item of this menu; mobile had no thread-panel overflow at all,
 * so this is that menu with its first (and for now only) entry. Kept generic so later parity items
 * can join it instead of adding more permanent header icons.
 */
@Composable
private fun ThreadHeaderFocusModeMenu(
    followed: Boolean? = null,
    onToggleFollow: (() -> Unit)? = null,
    /**
     * botiverse/slock #11687 (web ThreadOverflowMenu): Mark as Unread, before Follow; null for a
     * thread the person doesn't follow, as its Activity row has none.
     */
    onMarkUnread: (() -> Unit)? = null,
    /** Web ThreadOverflowMenu: Search in Task (a task) or Search in Thread (a reply thread), after Focus mode. */
    onSearchInTask: (() -> Unit)? = null,
    /** Web ThreadOverflowMenu Timeline (botiverse/slock #13534): the thread timeline's sheet, after Search. */
    onTimeline: (() -> Unit)? = null,
    /** View in Channel: a task's message in its channel, or a reply thread's parent message. */
    onViewInChannel: (() -> Unit)? = null,
    /** Task v2: the follow row reads Follow / Unfollow Task. */
    taskFollowLabels: Boolean = false,
    /** Task v2: Delete task after a separator, the menu's last row (web #14115); null: not offered. */
    onDeleteTask: (() -> Unit)? = null,
    /**
     * Draw the menu here, as a dropdown under the button, instead of asking the shell. Only a host
     * with a side column has the shell overlay; on a phone the page is the whole window, so a
     * request to the shell drew nothing (1.17.0 build 3: the task page's More did nothing).
     */
    drawLocally: Boolean = false,
    /** The thread's notification level row ("Notifications: <level>"); null leaves it out. */
    notificationLevelTargetInput: RaftNotificationLevelTarget? = null,
    /** Share loop: Share and Copy link lead the menu (design v10); null: none. */
    shareEntry: ThreadHeaderShareEntry? = null,
) {
    // An older server without the unified API shows no level row.
    val notificationLevelTarget = notificationLevelTargetInput.whenLevelsSupported()
    val anchorRef = remember { RaftLiveRootAnchorRef(identity = "thread_header_focus_mode") }
    var localMenuAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var notificationLevelDialogOpen by remember { mutableStateOf(false) }
    val notificationLevelLabel = rememberRaftNotificationLevelMenuLabel(notificationLevelTarget)
    val shareItems = threadHeaderShareMenuItems(shareEntry)
    val currentShareEntry by androidx.compose.runtime.rememberUpdatedState(shareEntry)
    val items =
        buildList {
            addAll(shareItems)
            // Task v2: the web menu's next rows for a task, in its order (ThreadOverflowMenu.tsx).
            if (onSearchInTask != null) {
                add(
                    RaftUiMenuItem(
                        id = SEARCH_IN_TASK_MENU_ITEM_ID,
                        label = stringResource(if (taskFollowLabels) Res.string.task_conversation_search else Res.string.thread_action_search_in_thread),
                        icon = RaftLucideIconName.Search,
                        enabled = true
                    )
                )
            }
            if (onTimeline != null) {
                add(
                    RaftUiMenuItem(
                        id = THREAD_TIMELINE_MENU_ITEM_ID,
                        label = stringResource(Res.string.thread_outline_list_title),
                        icon = RaftLucideIconName.List,
                        enabled = true
                    )
                )
            }
            if (onViewInChannel != null) {
                add(
                    RaftUiMenuItem(
                        id = VIEW_TASK_IN_CHANNEL_MENU_ITEM_ID,
                        label = stringResource(Res.string.task_conversation_view_in_channel),
                        icon = RaftLucideIconName.MapPin,
                        enabled = true
                    )
                )
            }
            if (onMarkUnread != null) {
                add(
                    RaftUiMenuItem(
                        id = MARK_THREAD_UNREAD_MENU_ITEM_ID,
                        label = stringResource(Res.string.home_action_mark_as_unread),
                        icon = RaftLucideIconName.MessageSquareDot,
                        enabled = true
                    )
                )
            }
            // task #180 (artin): follow/unfollow of this thread's message column sits in the same
            // panel menu. `followed` is null when the thread has no message to follow, in which case
            // the row is not offered at all rather than shown disabled.
            if (followed != null && onToggleFollow != null) {
                add(
                    RaftUiMenuItem(
                        id = FOLLOW_THREAD_MENU_ITEM_ID,
                        label =
                            stringResource(
                                when {
                                    taskFollowLabels && followed -> Res.string.task_conversation_unfollow
                                    taskFollowLabels -> Res.string.task_conversation_follow
                                    followed -> Res.string.thread_action_unfollow_thread
                                    else -> Res.string.thread_action_follow_thread
                                }
                            ),
                        icon = threadFollowMenuIcon(followed),
                        enabled = true
                    )
                )
            }
            // Design §4.3: the thread's level (All replies = follow / Mentions / Nothing / Default).
            if (notificationLevelTarget != null) {
                add(
                    RaftUiMenuItem(
                        id = NOTIFICATION_LEVEL_MENU_ITEM_ID,
                        label = notificationLevelLabel,
                        icon = RaftLucideIconName.BellRing,
                        enabled = true
                    )
                )
            }
            // Web: a separator, then Delete task (trash icon, no red in the menu).
            if (onDeleteTask != null) {
                add(
                    RaftUiMenuItem(
                        id = DELETE_TASK_MENU_ITEM_ID,
                        label = stringResource(Res.string.task_delete_action),
                        icon = RaftLucideIconName.Trash2,
                        enabled = true,
                        dividerBefore = true,
                    )
                )
            }
        }.let { rows -> threadHeaderMenuItemsAfterShare(rows, shareItems.size) }
    // task #180: the menu is drawn by the SHELL, not here. This page is a Kuikly @Page whose visible
    // surface is only its own pane, so a Modal opened inside it is clipped to that pane (device
    // evidence: nothing outside the window's left 400px strip could ever appear). We resolve the
    // anchor here -- in window space -- and ask the shell to draw it.
    // task #180: the EventBus must come from THIS page's own store registry, not from the host.
    // The thread column is a separate Kuikly @Page, so a host-supplied instance does not cross the
    // boundary -- the device trace showed `click_abort reason=eventBus_null` for exactly that reason.
    // RaftSharedStoreRegistry is keyed by pager bridge id, so each page gets the live bus it can
    // actually post on (the same path the rest of ThreadPage already uses).
    val localPager = LocalActivity.current.getPager() as Pager
    val eventBusOverride = LocalThreadHeaderMenuEventBus.current
    val pageEventBus: build.raft.app.data.RaftEventBus =
        remember(localPager.pagerId, eventBusOverride) {
            eventBusOverride ?: RaftSharedStoreRegistry.get(Utils.bridgeModule(localPager.pagerId)).eventBus
        }
    // This page's registry key, mirroring what RaftChildPage publishes for it.
    // This page's registry key, mirroring what RaftChildPage publishes for it. Verified by
    // reading PagerData: the host's pageData becomes `rawPageData["param"]` and `pageData.params`
    // reads that back, so `instanceId` is present on both sides. The trace logs both keys so any
    // future divergence is a line comparison rather than a silent zero origin.
    val pageOriginKey = raftChildPageOriginKey(THREAD_PAGE, LocalActivity.current.pageData.params)
    // Identifies this page to the shell so a menu cannot be attributed to a different thread column.
    val pageKey =
        LocalActivity.current.pageData.params
            .optString(RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY)
            .trim()
            .ifEmpty { "thread-panel" }
    var openRequestId by remember { mutableStateOf<String?>(null) }
    DisposableEffect(pageEventBus, pageKey) {
        val bus = pageEventBus
        val subscription =
            bus.observe(RAFT_SHELL_OVERLAY_RESULT_EVENT) { eventData ->
                if (!raftShouldApplyShellOverlayResult(openRequestId, eventData)) return@observe
                openRequestId = null
                val selectedId = eventData?.optString(SHELL_OVERLAY_SELECTED_ID).orEmpty()
                when (selectedId) {
                    FOLLOW_THREAD_MENU_ITEM_ID -> onToggleFollow?.invoke()
                    MARK_THREAD_UNREAD_MENU_ITEM_ID -> onMarkUnread?.invoke()
                    SEARCH_IN_TASK_MENU_ITEM_ID -> onSearchInTask?.invoke()
                    THREAD_TIMELINE_MENU_ITEM_ID -> onTimeline?.invoke()
                    VIEW_TASK_IN_CHANNEL_MENU_ITEM_ID -> onViewInChannel?.invoke()
                    DELETE_TASK_MENU_ITEM_ID -> onDeleteTask?.invoke()
                    NOTIFICATION_LEVEL_MENU_ITEM_ID -> notificationLevelDialogOpen = true
                    SHARE_OUT_MENU_ITEM_ID -> currentShareEntry?.onShare?.invoke()
                    SHARE_OUT_COPY_LINK_MENU_ITEM_ID -> currentShareEntry?.onCopyLink?.invoke()
                    else -> Unit // dismissed
                }
            }
        onDispose {
            // Leaving the page retires the menu; the shell drops a cancel for anything it is not
            // currently showing, so this cannot close another page's menu.
            openRequestId?.let { requestId ->
                bus.post(RAFT_SHELL_OVERLAY_REQUEST_EVENT, cancelRaftShellOverlayJson(requestId))
            }
            subscription.close()
        }
    }
    ThreadHeaderCompactAction(
        icon = RaftLucideIconName.Ellipsis,
        onClick = onClick@{
            // task #180 diagnostics (Argus): unconditional entry log. The previous device run
            // produced ZERO trace lines, which could not distinguish "the click never reached this
            // handler" from "the lines went somewhere else". This line fires before any guard.
            raftTraceToPlatformLog("SlockShellOverlay", "click_enter")
            if (drawLocally) {
                anchorRef.resolve(RaftPopoverLocalPoint.BottomEnd)?.let { localMenuAnchor = it }
                return@onClick
            }
            val bus = pageEventBus
            val anchor = anchorRef.resolve(RaftPopoverLocalPoint.BottomEnd)
            if (anchor == null) {
                raftTraceToPlatformLog("SlockShellOverlay", "click_abort reason=anchor_resolve_null")
                return@onClick
            }
            // Already window space: the shell must not convert this again. On CMP the child page
            // is composed inline, so its root offset includes the pane's position; adding the
            // measured page origin (the Kuikly-era step) counted it twice.
            val windowAnchor = raftChildPageWindowAnchor(anchor)
            if (windowAnchor == null) {
                raftTraceToPlatformLog("SlockShellOverlay", "click_abort reason=root_offset_null")
                return@onClick
            }
            val pageOrigin = RaftChildPageOriginRegistry.stateFor(pageOriginKey).windowOrigin
            val requestId = "thread-panel-${anchor.hashCode()}-${items.size}"
            openRequestId = requestId
            // task #180 diagnostics (Argus): point 1 of 3 -- the page posted a request.
            raftTraceToPlatformLog(
                "SlockShellOverlay",
                "page_post requestId=$requestId pageKey=$pageKey originKey=$pageOriginKey " +
                    "pageOrigin=$pageOrigin windowAnchor=$windowAnchor items=${items.size}"
            )
            bus.post(
                RAFT_SHELL_OVERLAY_REQUEST_EVENT,
                RaftShellOverlayRequest(
                    requestId = requestId,
                    pageKey = pageKey,
                    anchor = windowAnchor,
                    items =
                        items.map {
                            RaftShellOverlayItem(
                                id = it.id,
                                label = it.label,
                                enabled = it.enabled
                            )
                        }
                ).toJson()
            )
        },
        modifier = Modifier.raftCaptureLiveRootAnchor(anchorRef),
        contentDescription = stringResource(Res.string.thread_focus_mode_menu_more)
    )
    localMenuAnchor?.let { anchor ->
        RaftUiControlledMenu(
            expanded = true,
            anchor = anchor,
            items = items,
            onSelect = { id ->
                localMenuAnchor = null
                when (id) {
                    FOLLOW_THREAD_MENU_ITEM_ID -> onToggleFollow?.invoke()
                    MARK_THREAD_UNREAD_MENU_ITEM_ID -> onMarkUnread?.invoke()
                    SEARCH_IN_TASK_MENU_ITEM_ID -> onSearchInTask?.invoke()
                    THREAD_TIMELINE_MENU_ITEM_ID -> onTimeline?.invoke()
                    VIEW_TASK_IN_CHANNEL_MENU_ITEM_ID -> onViewInChannel?.invoke()
                    DELETE_TASK_MENU_ITEM_ID -> onDeleteTask?.invoke()
                    NOTIFICATION_LEVEL_MENU_ITEM_ID -> notificationLevelDialogOpen = true
                    SHARE_OUT_MENU_ITEM_ID -> currentShareEntry?.onShare?.invoke()
                    SHARE_OUT_COPY_LINK_MENU_ITEM_ID -> currentShareEntry?.onCopyLink?.invoke()
                }
            },
            onDismiss = { localMenuAnchor = null },
            // raft-ui DropdownMenuContent's `sideOffset` 6.
            verticalOffset = RaftMetrics.MenuSideOffset
        )
    }
    if (notificationLevelDialogOpen && notificationLevelTarget != null) {
        RaftNotificationLevelDialog(target = notificationLevelTarget, onDismiss = { notificationLevelDialogOpen = false })
    }
}

/** Share loop: the ⋯ menu's first rows, Share and Copy link; none without [entry]. */
@Composable
private fun threadHeaderShareMenuItems(entry: ThreadHeaderShareEntry?): List<RaftUiMenuItem> {
    entry ?: return emptyList()
    return listOf(
        RaftUiMenuItem(id = SHARE_OUT_MENU_ITEM_ID, label = stringResource(entry.shareLabel), icon = RaftLucideIconName.Share),
        RaftUiMenuItem(
            id = SHARE_OUT_COPY_LINK_MENU_ITEM_ID,
            label = stringResource(Res.string.thread_link_menu_copy_link),
            icon = RaftLucideIconName.Link,
        ),
    )
}

/** A separator between the [shareCount] share rows and the menu's own rows after them. */
internal fun threadHeaderMenuItemsAfterShare(rows: List<RaftUiMenuItem>, shareCount: Int): List<RaftUiMenuItem> =
    if (shareCount <= 0 || rows.size <= shareCount) {
        rows
    } else {
        rows.mapIndexed { index, row -> if (index == shareCount) row.copy(dividerBefore = true) else row }
    }

/**
 * Screenshot fixtures only: the bus the header's More menu posts on, instead of the live page's
 * shared store registry (null in the app).
 */
internal val LocalThreadHeaderMenuEventBus =
    androidx.compose.runtime.staticCompositionLocalOf<build.raft.app.data.RaftEventBus?> { null }

internal const val FOCUS_MODE_MENU_ITEM_ID = "thread-focus-mode"
internal const val MARK_THREAD_UNREAD_MENU_ITEM_ID = "thread-mark-unread"
internal const val FOLLOW_THREAD_MENU_ITEM_ID = "thread-toggle-follow"
internal const val SEARCH_IN_TASK_MENU_ITEM_ID = "task-search"
internal const val THREAD_TIMELINE_MENU_ITEM_ID = "thread-timeline"
internal const val VIEW_TASK_IN_CHANNEL_MENU_ITEM_ID = "task-view-in-channel"
internal const val DELETE_TASK_MENU_ITEM_ID = "task-delete"
internal const val NOTIFICATION_LEVEL_MENU_ITEM_ID = "thread-notification-level"

@Composable
internal fun ThreadHeaderActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp = ThreadHeaderActionSize,
    minHeight: Dp = ThreadHeaderActionSize,
    horizontalPadding: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    // raft-ui `PanelAction`: ghost head action, no edge and no offset shadow.
    RaftPanelAction(
        onClick = onClick,
        modifier = modifier.heightIn(min = minHeight).widthIn(min = minWidth),
    ) {
        Box(modifier = Modifier.padding(horizontal = horizontalPadding), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
private fun ThreadHeaderCompactAction(
    icon: RaftLucideIconName,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    contentDescription: String? = null,
    actionSize: Dp = ThreadHeaderActionSize,
    iconSize: Dp = ThreadHeaderActionIconSize
) {
    ThreadHeaderActionButton(
        onClick = onClick,
        modifier =
            if (contentDescription.isNullOrBlank()) {
                modifier
            } else {
                modifier.semantics { this.contentDescription = contentDescription }
            },
        minWidth = if (label == null) actionSize else ThreadHeaderActionLabelMinWidth,
        minHeight = actionSize,
        // raft-ui `PanelAction` `px-2` once it carries a label next to its glyph.
        horizontalPadding = if (label == null) 0.dp else RaftAppSpacing.Sm,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs, Alignment.CenterHorizontally)
        ) {
            // raft-ui `PanelAction` is `text-foreground`; the muted ink made the head read as disabled.
            RaftLucideIcon(icon, Modifier.size(iconSize), color = raftArgbHex(RaftColor.TextBlack))
            label?.takeIf { it.isNotBlank() }?.let {
                // raft-ui `PanelAction`: `text-footnote font-bold` (13/18).
                Text(it, style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold).copy(fontSize = 13.sp, lineHeight = 18.sp),
                    color = RaftColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

internal fun threadHeaderVisibleActionGapDp(): Float =
    ThreadHeaderActionGroupSpacing.value

internal fun threadHeaderContentSpacingDp(): Float = ThreadHeaderContentSpacing.value

// The touch head's geometry (a finger's 44dp actions), which the spacing tests pin.
internal fun threadHeaderActionSizeDp(): Float = RaftControlMetrics.Mobile.barButtonSize.value

internal fun threadHeaderActionIconSizeDp(): Float = RaftControlMetrics.Mobile.barIconSize.value

internal fun threadHeaderEdgeInsetDp(): Float = RaftControlMetrics.Mobile.barEdgeInset.value

internal fun threadHeaderChannelTitleGapDp(): Float =
    (ThreadHeaderContentSpacing + ThreadHeaderChannelTitleGapExtra).value

internal fun threadHeaderScrollToTopEnabled(surfaceKind: String?, selectedTab: String?): Boolean =
    surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
        selectedTab?.trim()?.lowercase() == "chat"

internal fun threadHeaderScrollToTopTargetIndex(lazyItemCount: Int): Int? =
    if (lazyItemCount > 0) 0 else null

internal fun consumeThreadHeaderScrollToTopRequest(
    request: Int,
    lazyItemCount: Int,
    fence: AppShellScrollToTopRequestFence,
): Boolean =
    request > 0 &&
        threadHeaderScrollToTopTargetIndex(lazyItemCount) != null &&
        fence.consume(request)
