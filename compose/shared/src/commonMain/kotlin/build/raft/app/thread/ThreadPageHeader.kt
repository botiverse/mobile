package build.raft.app.thread

import build.raft.app.assistant.LocalThreadAssistantPanelEmbed
import build.raft.app.available
import build.raft.designsystem.theme.RaftControlMetrics
import androidx.compose.runtime.ReadOnlyComposable
import build.raft.app.resources.*
import build.raft.app.thread.iconName
import build.raft.app.toJson
import build.raft.app.toRaftNativeNavBarActions
import build.raft.app.visibleToolStuck
import build.raft.app.whenLevelsSupported
import build.raft.app.withRaftFontWeight
import build.raft.app.withoutClearedLoginRequiredAttention
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.components.LocalRaftCopyHandler
import build.raft.designsystem.components.LocalRaftPaneGridHeadTabs
import build.raft.designsystem.components.RaftCopyHandler
import build.raft.designsystem.components.RaftPaneGridHeadFit
import build.raft.designsystem.components.raftCopyable
import build.raft.designsystem.components.rememberRaftCopyAction
import build.raft.designsystem.components.raftPaneGridHeadFit
import build.raft.designsystem.components.RaftIconToggleButton
import build.raft.designsystem.components.RaftBackButton
import build.raft.designsystem.components.RaftSideAction
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTooltip
import build.raft.designsystem.components.RaftBackButtonDefaults
import build.raft.app.RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY
import build.raft.app.RaftAdaptiveDetailCloseRequest
import build.raft.app.RaftAgentStatusIndicatorState
import build.raft.app.RaftAgentStatusResolver
import build.raft.app.RaftAppAvatar
import build.raft.app.RaftAppBotBadge
import build.raft.app.RaftAppRadius
import build.raft.app.RaftAppSpacing
import build.raft.app.RaftAppStroke
import build.raft.app.RaftAvatarKind
import build.raft.app.RaftChannelKindGlyph
import build.raft.app.RaftChannelKindIcon
import build.raft.app.RaftChildPageOriginRegistry
import build.raft.app.RaftColor
import build.raft.app.RaftContentLucideIcon
import build.raft.app.RaftLiveRootAnchorRef
import build.raft.app.RaftLoungeAvatar
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftMemberAvatarResolver
import build.raft.app.RaftMonoFamily
import build.raft.app.RaftNativeNavBarAction
import build.raft.app.RaftNativeNavBarPlacement
import build.raft.app.RaftNativeNavBarTitleExtras
import build.raft.app.RaftNativeNavBarTitleLeading
import build.raft.app.RaftNotificationLevelDialog
import build.raft.app.RaftNotificationLevelTarget
import build.raft.app.RaftPanelAction
import build.raft.app.RaftPanelHeadInlineMetaGap
import build.raft.app.RaftPopoverAnchor
import build.raft.app.RaftPopoverLocalPoint
import build.raft.app.RaftSidePaneCloseButton
import build.raft.app.RaftSlackSyncBadge
import build.raft.app.RaftStatusIndicator
import build.raft.app.RaftStatusIndicatorTokens
import build.raft.app.RaftTaskColumnHeadActions
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.RaftTopPageHeader
import build.raft.app.RaftTopPageHeaderContentEndPadding
import build.raft.app.RaftType
import build.raft.app.TaskConversationBarTitle
import build.raft.app.TaskConversationHeaderChrome
import build.raft.app.ThreadPageLounge
import build.raft.app.agentConversationNativeTitleMenu
import build.raft.app.agentLoungeTitle
import build.raft.app.data.RAFT_SHELL_OVERLAY_REQUEST_EVENT
import build.raft.app.data.RAFT_SHELL_OVERLAY_RESULT_EVENT
import build.raft.app.data.RaftConversationHeaderAction
import build.raft.app.data.RaftConversationHeaderSurface
import build.raft.app.data.RaftProductEvents
import build.raft.app.data.SHELL_OVERLAY_SELECTED_ID
import build.raft.app.data.raftShouldApplyShellOverlayResult
import build.raft.app.dotColor
import build.raft.app.fill
import build.raft.app.foldPhoneConversationNativeNavBarActions
import build.raft.app.get
import build.raft.app.iconName
import build.raft.app.localizedAgentToolStuckText
import build.raft.app.localizedMemberStatusLabel
import build.raft.app.data.RaftShellOverlayRequest
import build.raft.app.data.RaftShellOverlayItem
import build.raft.app.data.cancelRaftShellOverlayJson
import build.raft.app.components.RaftUiControlledMenu
import build.raft.app.components.RaftUiMenuItem
import build.raft.app.composerAutofocusSkipsMessageOpen
import build.raft.app.composerAutofocusYieldsToSideThread
import build.raft.app.components.RaftMetrics
import build.raft.app.base.Utils
import build.raft.app.data.RaftEventBus
import build.raft.app.data.map
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.model.MemberKind
import build.raft.app.observe
import build.raft.app.optString
import build.raft.app.post
import build.raft.app.raftArgbHex
import build.raft.app.raftAvatarKind
import build.raft.app.raftCaptureLiveRootAnchor
import build.raft.app.raftChannelKindGlyph
import build.raft.app.raftChildPageOriginKey
import build.raft.app.raftChildPageWindowAnchor
import build.raft.app.raftMemberAvatarCacheSubject
import build.raft.app.raftPlatformComposerMinimumBottomPaddingDp
import build.raft.app.raftPlatformHasFinePointer
import build.raft.app.raftSideColumnBackAction
import build.raft.app.raftSideColumnControls
import build.raft.app.raftSideColumnLucideAction
import build.raft.app.raftSidePaneShowsBack
import build.raft.app.raftTopSafeAreaPadding
import build.raft.app.raftTraceToPlatformLog
import build.raft.app.raftWindowWide
import build.raft.app.rememberAgentAttentionParts
import build.raft.app.rememberAgentDeliveryHoldView
import build.raft.app.rememberAgentReadinessDisplayCopy
import build.raft.app.rememberAgentRuntimeRetiredState
import build.raft.app.rememberAgentRuntimeSignInResolution
import build.raft.app.rememberComposerAutofocusOnOpen
import build.raft.app.rememberComposerFocusOnOpen
import build.raft.app.rememberRaftAgentStatusIndicatorState
import build.raft.app.rememberRaftMemberAppBot
import build.raft.app.rememberRaftMemberAvatarWireValue
import build.raft.app.rememberRaftNotificationLevelIcon
import build.raft.app.rememberRaftNotificationLevelMenuLabel
import build.raft.app.resolve
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.AgentAttentionTestTags
import build.raft.app.AgentConversationAvatarMenuButton
import build.raft.app.AgentConversationAvatarMenuExtraWidthDp
import build.raft.app.AgentDeliveryHoldTestTags
import build.raft.app.AgentLoungeConversationSide
import build.raft.app.AgentLoungeTestTags
import build.raft.app.AgentReadinessTestTags
import build.raft.app.AgentRuntimeRetiredTestTags
import build.raft.app.AgentRuntimeSignInAction
import build.raft.app.AgentRuntimeSignInResolution
import build.raft.app.AgentRuntimeSignInTestTags
import build.raft.app.AppShellScrollToTopRequestFence
import build.raft.app.ComposerFocusOnOpen
import build.raft.app.EmptyRaftAgentStatusResolver
import build.raft.app.EmptyRaftMemberAvatarResolver
import build.raft.app.LocalRaftNativeNavBar
import build.raft.app.LocalRaftNativeNavBarScript
import build.raft.app.LocalRaftSidePanePage
import build.raft.app.LocalRaftTaskColumnHead
import build.raft.app.LocalRaftWindowHorizontalSafeArea
import build.raft.app.MemberDetailIdentity
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
// (`[&_svg]:size-3.5 stroke-2`). The DM / lounge avatar is web AvatarSlot `panel-header`
// (RaftControlMetrics.headAvatar): 35 in the phone's 56 head, the tile's 27 in the 48 short
// head and under a fine pointer.
private val ThreadHeaderChannelTileSize = 27.dp
private val ThreadHeaderChannelIconSize = 14.dp
private val ThreadHeaderDmAvatarSize: Dp
    @Composable @ReadOnlyComposable
    get() = RaftTheme.controls.headAvatar.edge
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
    val restingBase = ThreadComposerBottomBasePadding + ThreadComposerBottomSafeAreaLift
    // On Android (keyboard excludes the bottom safe area), the safe area may be an opaque system
    // navigation bar, so the composer keeps the base clearance above both safe area and keyboard.
    // On iOS (keyboard frame includes the bottom safe area), the resting bottom safe area is the
    // transparent home-indicator band, which already clears the indicator; stacking another 14dp
    // on top left a 35-48dp empty band below the composer on iPhone Duo landscape (#proj-mobile:#330).
    // Taking maxOf(restingBase + keyboardInset, bottomSafeArea) keeps resting clearance tight to
    // the safe area while remaining monotonic across every IME frame and preserving full clearance
    // above the open keyboard.
    val padded =
        if (keyboardInsetExcludesBottomSafeArea) {
            restingBase + keyboardInset + bottomSafeArea
        } else {
            maxOf(restingBase + keyboardInset, bottomSafeArea)
        }
    return maxOf(padded, minimumBottomPadding)
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
    /** Voice call entry for an eligible agent DM (first item folded into `⋯` on phone). */
    onStartCall: (() -> Unit)? = null,
    /** Keep prefilled iOS native nav bar buttons while LoadingShell renders before FeatureSurfaces. */
    preserveNativePrefilledActions: Boolean = false,
    /** Server-driven primary action selection on phone headers (`mobile_phone_header_action_v0`). */
    phoneHeaderPrimaryConfig: PhoneConversationHeaderPrimaryConfig = LocalPhoneConversationHeaderPrimaryConfig.current,
) {
    // Agent Lounge v0 (web ChatPanel `lounge-header`): a Lounge is headed "{Name}'s Lounge" with its
    // agent's face and the Lounge mark; no # tile, no description. A thread in it names the Lounge.
    val headerLounge = lounge?.takeIf { it.lounge != null }
    val loungeHeader = headerLounge?.takeIf { headerDisplay.surfaceKind == RaftThreadSurfaceKind.Channel }
    val loungeTitle = headerLounge?.let { agentLoungeTitle(it.agentName) }
    val loungeAudienceSubtitle =
        when {
            lounge?.conversationSwitch == null -> null
            loungeHeader != null -> stringResource(Res.string.agent_lounge_audience_notice)
            headerDisplay.surfaceKind == RaftThreadSurfaceKind.DirectMessage -> {
                val peerName =
                    build.raft.app.data.loungeAgentDisplayName(
                        headerDisplay.title.trim().removePrefix("@").takeUnless { it.isGenericThreadHeaderFallbackLabel() }
                    ) ?: build.raft.app.data.loungeAgentDisplayName(lounge.agentName)
                peerName?.let { stringResource(Res.string.agent_lounge_private_audience_notice, it) }
            }
            else -> null
        }
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
            loungeAudienceSubtitle != null -> loungeAudienceSubtitle
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
                    onStartCall != null ||
                    sideCardToggles?.cardsInHeader()?.isNotEmpty() == true ||
                    threadHeaderSettingsActionVisible(payload.showSettingsAction, headerDisplay.surfaceKind) ||
                    payload.threadHeaderOpenInParentActionVisible()
            )
    val isTopLevelConversation = !taskConversation && headerDisplay.surfaceKind != RaftThreadSurfaceKind.ReplyThread
    val isNamedChannel = headerDisplay.surfaceKind == RaftThreadSurfaceKind.Channel && loungeHeader == null && !taskConversation
    val activeConversationSwitch =
        lounge?.conversationSwitch?.takeIf { LocalThreadAssistantPanelEmbed.current == null }
    val conversationMenuAgentId = loungeHeader?.lounge?.agentId ?: headerDisplay.dmPeerIdentity?.memberId?.value
    val conversationMenuAgentName =
        if (loungeHeader != null) {
            loungeHeader.agentName
        } else {
            build.raft.app.data.loungeAgentDisplayName(
                headerDisplay.title.trim().removePrefix("@").takeUnless { it.isGenericThreadHeaderFallbackLabel() }
            ) ?: lounge?.agentName.orEmpty()
        }
    val conversationMenuAvatarWireValue =
        loungeHeader?.agentAvatarWireValue
            ?: resolvedDmPeerAvatarWireValue
            ?: headerDisplay.dmPeerAvatarWireValue
            ?: lounge?.agentAvatarWireValue
    val conversationMenuCacheServerId =
        loungeHeader?.cacheServerId
            ?: headerDisplay.dmPeerIdentity?.sourceServerId?.value
            ?: lounge?.cacheServerId
    // The Tasks page's task column (RaftTasksTaskColumn.kt; web TaskConversationBar beside the
    // list): its Expand and Close end the head, also while the page is still loading.
    val taskColumnHead = LocalRaftTaskColumnHead.current
    // The reply thread's column on the wide layout (a side pane, LocalRaftSidePanePage; web ThreadPanelHeader
    // presentation "side"): Close ends the head and closes the column only, the channel staying; no Back, the
    // column holds no page under the thread (raftSidePaneHeadButtons).
    val paneCloses = LocalRaftSidePanePage.current != null
    // iPhone Duo (raft-design #859): beside the system's side column, Back and the page's actions
    // are the column's (Back first, two actions, the rest in its ⋯); the header keeps the title
    // and the subtitle, from the 16dp margin, without Back and without icon buttons.
    val showsBack = threadHeaderShowsBack(showBack, headerDisplay.surfaceKind) && raftSidePaneShowsBack()
    val wideWindow = raftWindowWide()
    val baseCompact = !wideWindow
    val headerConversation = threadHeaderConversationKind(headerDisplay.surfaceKind, taskConversation)
    fun trackHeader(
        action: RaftConversationHeaderAction,
        surface: RaftConversationHeaderSurface,
        compact: Boolean = baseCompact,
    ) {
        val kind = headerConversation ?: return
        RaftProductEvents.conversationHeaderActionSelected(action, surface, kind, compact)
    }
    val conversationMenuViewProfile: (() -> Unit)? =
        when {
            lounge?.onOpenAgentProfile != null -> {
                {
                    trackHeader(RaftConversationHeaderAction.OpenProfile, RaftConversationHeaderSurface.Title)
                    lounge.onOpenAgentProfile.invoke()
                }
            }
            headerDisplay.dmPeerIdentity != null -> {
                val identity = headerDisplay.dmPeerIdentity
                {
                    trackHeader(RaftConversationHeaderAction.OpenProfile, RaftConversationHeaderSurface.Title)
                    onOpenMemberProfile(identity)
                }
            }
            else -> null
        }
    val trackedBack: () -> Unit = {
        threadHeaderBackProductEvent(
            surfaceKind = headerDisplay.surfaceKind,
            taskConversation = taskConversation,
            wideLayout = wideWindow,
            inSidePane = paneCloses,
        )?.let { action ->
            trackHeader(action, RaftConversationHeaderSurface.Header, compact = false)
        }
        onBack()
    }
    val controlsInColumn =
        raftSideColumnControls(
            back =
                if (showsBack) {
                    raftSideColumnBackAction(trackedBack)
                } else {
                    null
                },
            actions =
                if (showActions) {
                    wrapThreadHeaderSideColumnActionsForAnalytics(
                        actions =
                            threadHeaderSideColumnActions(
                                payload = payload,
                                surfaceKind = headerDisplay.surfaceKind,
                                taskConversation = taskConversation,
                                isNamedChannel = isNamedChannel,
                                settingsOpen = settingsOpen,
                                onOpenSearch = onOpenSearch,
                                onStartCall = onStartCall,
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
                            ),
                        conversation = headerConversation,
                        compact = baseCompact,
                        settingsOpen = settingsOpen,
                        focusModeActive = focusModeActive,
                        threadFollowed = threadFollowed,
                        taskConversation = taskConversation,
                    )
                } else {
                    emptyList()
                },
        )
    // iOS native nav bar (web Task v2 + phone fold #16464): on phone every top-level conversation
    // shows Search + ⋯, folding Call (when present), Tasks, Files, and Details & settings into ⋯.
    val nativeSideActions =
        if (showActions && (LocalRaftNativeNavBar.current != null || LocalRaftNativeNavBarScript.current != null)) {
            threadHeaderSideColumnActions(
                payload = payload,
                surfaceKind = headerDisplay.surfaceKind,
                taskConversation = taskConversation,
                isNamedChannel = isNamedChannel,
                settingsOpen = settingsOpen,
                onOpenSearch = onOpenSearch,
                onStartCall = onStartCall,
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
            )
        } else {
            emptyList()
        }
    val nativeLevelTarget = notificationLevelTarget.whenLevelsSupported()
    val nativeLevelLabel = rememberRaftNotificationLevelMenuLabel(nativeLevelTarget)
    val nativeLevelIcon = rememberRaftNotificationLevelIcon(nativeLevelTarget)
    var nativeLevelDialogOpen by remember { mutableStateOf(false) }
    if (nativeLevelDialogOpen && nativeLevelTarget != null) {
        RaftNotificationLevelDialog(target = nativeLevelTarget, onDismiss = { nativeLevelDialogOpen = false })
    }
    val nativeNavBarActions =
        if (!showActions) {
            emptyList()
        } else {
            val baseActions =
                foldPhoneConversationNativeNavBarActions(
                    actions =
                        applyThreadHeaderNativeNavBarCheckedState(
                            actions = nativeSideActions.toRaftNativeNavBarActions(),
                            sideCardToggles = sideCardToggles,
                            settingsOpen = settingsOpen,
                        ),
                    foldForPhone = !wideWindow && isTopLevelConversation,
                    primaryConfig = phoneHeaderPrimaryConfig,
                )
            val withLevel =
                if (nativeLevelTarget == null) {
                    baseActions
                } else {
                    val levelAction =
                        RaftNativeNavBarAction(
                            NOTIFICATION_LEVEL_MENU_ITEM_ID,
                            nativeLevelLabel,
                            "bell",
                            { nativeLevelDialogOpen = true },
                            placement = RaftNativeNavBarPlacement.Overflow,
                            icon = nativeLevelIcon,
                        )
                    val deleteIndex = baseActions.indexOfFirst { it.id == DELETE_TASK_MENU_ITEM_ID }
                    if (deleteIndex >= 0) {
                        buildList {
                            addAll(baseActions.subList(0, deleteIndex))
                            add(levelAction)
                            addAll(baseActions.subList(deleteIndex, baseActions.size))
                        }
                    } else {
                        baseActions + levelAction
                    }
                }
            wrapThreadHeaderNativeNavBarActionsForAnalytics(
                actions = withLevel,
                conversation = headerConversation,
                compact = baseCompact || withLevel.any { it.placement == RaftNativeNavBarPlacement.Overflow },
                settingsOpen = settingsOpen,
                focusModeActive = focusModeActive,
                threadFollowed = threadFollowed,
                taskConversation = taskConversation,
            )
        }
    val nativeAgentConversationMenu =
        if (activeConversationSwitch != null) {
            agentConversationNativeTitleMenu(
                switch = activeConversationSwitch,
                loungeLabel = stringResource(Res.string.agent_lounge_label),
                loungeAudience = stringResource(Res.string.agent_lounge_audience_notice),
                privateChatLabel = stringResource(Res.string.agent_lounge_private_chat_label),
                privateAudience = stringResource(Res.string.agent_lounge_private_audience_notice, conversationMenuAgentName),
                viewProfileLabel = stringResource(Res.string.agent_lounge_view_profile),
                onViewProfile = conversationMenuViewProfile,
            )
        } else {
            emptyList()
        }
    val nativeAgentConversationTriggerLabel =
        activeConversationSwitch?.let { switch ->
            val base =
                if (switch.activeSide == AgentLoungeConversationSide.Lounge) {
                    stringResource(Res.string.agent_lounge_switch_trigger_lounge, conversationMenuAgentName)
                } else {
                    stringResource(Res.string.agent_lounge_switch_trigger_private, conversationMenuAgentName)
                }
            val unreadCount = switch.otherSideUnread.count
            if (unreadCount > 0) {
                "$base, ${stringResource(Res.string.agent_lounge_switch_trigger_other_unread, unreadCount)}"
            } else {
                base
            }
        }
    val headerDensity = LocalDensity.current
    var headerWidthDp by remember { mutableStateOf(0f) }
    val headRowTabs = LocalRaftPaneGridHeadTabs.current
    val sidePane = LocalRaftSidePanePage.current
    RaftTopPageHeader(
        title = localizedHeaderTitle,
        modifier =
            Modifier.onSizeChanged { size ->
                val measured = with(headerDensity) { size.width.toDp().value }
                if (measured != headerWidthDp) headerWidthDp = measured
            },
        nativeSubtitle = headerSubtitle,
        nativeOnSubtitleClick =
            channelLinkLabel?.let {
                {
                    trackHeader(RaftConversationHeaderAction.ViewInChannel, RaftConversationHeaderSurface.Title)
                    onOpenInChannel()
                }
            },
        nativePreservePrefilledActions = preserveNativePrefilledActions,
        nativeActions = nativeNavBarActions,
        nativeTitleMenu = nativeAgentConversationMenu,
        // PROTOTYPE: the channel glyph / DM avatar / B1 conversation switcher and the refresh spinner in the host's title view.
        nativeTitleExtras =
            RaftNativeNavBarTitleExtras(
                leading =
                    when {
                        activeConversationSwitch != null ->
                            RaftNativeNavBarTitleLeading.AgentConversation(
                                label = conversationMenuAgentName,
                                avatarWireValue = conversationMenuAvatarWireValue,
                                isLounge = activeConversationSwitch.activeSide == AgentLoungeConversationSide.Lounge,
                                attention = activeConversationSwitch.hasOtherSideAttention,
                                agentId = conversationMenuAgentId,
                                cacheServerId = conversationMenuCacheServerId,
                                accessibilityLabel = nativeAgentConversationTriggerLabel,
                            )
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
                showsBack -> ThreadHeaderEdgeInset
                else -> ThreadSplitDetailEdgeInset
            },
        // The 3dp edge inset only mirrors Back when a trailing PanelAction sits at the end;
        // without one the title keeps the default end padding instead of running to the edge.
        contentEndPadding =
            if ((showHeaderActions && !controlsInColumn) || taskColumnHead != null || paneCloses) ThreadHeaderEdgeInset else RaftTopPageHeaderContentEndPadding,
        contentSpacing = ThreadHeaderContentSpacing,
        titleLeadingContent = {
            // Back is the same 44dp frameless PanelAction as the trailing actions (not the shared
            // 36dp leading slot), so its arrow mirrors the last trailing glyph 14dp from the edge.
            if (showsBack && !controlsInColumn) {
                RaftBackButton(onClick = trackedBack, label = stringResource(Res.string.common_back))
            }
            // Rule 4 crowded split view: row tabs replace the identity avatar/tile + title (web PanelHeader).
            if (headRowTabs == null) {
                if (activeConversationSwitch != null) {
                    AgentConversationAvatarMenuButton(
                        agentId = conversationMenuAgentId,
                        agentName = conversationMenuAgentName,
                        avatarWireValue = conversationMenuAvatarWireValue,
                        cacheServerId = conversationMenuCacheServerId,
                        switch = activeConversationSwitch,
                        avatarSize = ThreadHeaderDmAvatarSize,
                        onViewProfile = conversationMenuViewProfile,
                    )
                } else if (loungeHeader != null) {
                    // The face is decorative for screen readers; its tap opens the agent's profile.
                    // Raw clickable on purpose, as on the DM peer's face below: the app's press wash
                    // draws over the face, where raftClickable's would sit behind the opaque avatar.
                    Box(
                        modifier =
                            Modifier
                                .padding(end = ThreadHeaderChannelTitleGapExtra)
                                .then(
                                    loungeHeader.onOpenAgentProfile?.let { open ->
                                        Modifier.clickable { // raw-click: an avatar face, see above
                                            trackHeader(RaftConversationHeaderAction.OpenProfile, RaftConversationHeaderSurface.Title)
                                            open()
                                        }
                                    } ?: Modifier
                                )
                    ) {
                        RaftLoungeAvatar(
                            agentId = loungeHeader.lounge?.agentId,
                            avatarWireValue = loungeHeader.agentAvatarWireValue,
                            label = loungeHeader.agentName,
                            cacheServerId = loungeHeader.cacheServerId,
                            avatarSize = ThreadHeaderDmAvatarSize,
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
                                {
                                    trackHeader(RaftConversationHeaderAction.OpenProfile, RaftConversationHeaderSurface.Title)
                                    onOpenMemberProfile(identity)
                                }
                            }
                    )
                } else if (payload.showModeTabs) {
                    Box(modifier = Modifier.padding(end = ThreadHeaderChannelTitleGapExtra)) {
                        RaftChannelIdentityTile(glyph = headerDisplay.channelKindGlyph)
                    }
                }
            }
        },
        titleContent = {
            if (taskConversationChrome != null) {
                // Task v2 (web TaskConversationBar): the ref over "Task #7", and on a phone the task's
                // state with the chevron that pulls the head down.
                TaskConversationBarTitle(taskConversationChrome, Modifier.weight(1f))
            } else {
                val copyText =
                    loungeTitle?.takeIf { loungeHeader != null }
                        ?: threadHeaderCopyText(headerDisplay.title, headerDisplay.surfaceKind)
                val trackedTitleClick =
                    onTitleClick?.let { click ->
                        {
                            threadHeaderTitleClickProductEvent(
                                surfaceKind = headerDisplay.surfaceKind,
                                taskConversation = taskConversation,
                                isLounge = loungeHeader != null,
                                settingsOpen = settingsOpen,
                            )?.let { action ->
                                trackHeader(action, RaftConversationHeaderSurface.Title)
                            }
                            click()
                        }
                    }
                val enclosingCopyAction = rememberRaftCopyAction()
                val copyTrackedHandler =
                    remember(enclosingCopyAction, headerConversation, baseCompact) {
                        RaftCopyHandler { text ->
                            headerConversation?.let { kind ->
                                RaftProductEvents.conversationHeaderActionSelected(
                                    action = RaftConversationHeaderAction.CopyName,
                                    surface = RaftConversationHeaderSurface.Title,
                                    conversation = kind,
                                    compact = baseCompact,
                                )
                            }
                            enclosingCopyAction(text)
                        }
                    }
                val headModifier =
                    Modifier
                        .weight(1f)
                        .then(if (loungeHeader != null) Modifier.testTag(AgentLoungeTestTags.Header) else Modifier)
                        // A long press copies the channel / DM name; the tap (scroll to top) stays.
                        .raftCopyable(
                            copyText,
                            onClick = trackedTitleClick,
                        )
                val titleRowContent: @Composable (Modifier) -> Unit = { rowModifier ->
                    ThreadHeaderNameFirstRow(modifier = rowModifier) {
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
                            // Web ChatPanel `ChannelSlackSyncBadge`: a Slack channel says so by its glyph;
                            // after its name only a stopped sync, the warning Badge.
                            RaftSlackSyncBadge(headerDisplay.slackSyncProblem)
                        }
                        if (activityMutedIndicatorVisible) {
                            ThreadActivityMutedBadge()
                        }
                        if (dmAppBot != null) {
                            RaftAppBotBadge(identity = dmAppBot, botName = localizedHeaderTitle)
                        } else {
                            dmAgentActivity?.let {
                                ThreadDmAgentActivity(
                                    state = it,
                                    agentName = localizedHeaderTitle,
                                    modifier = Modifier.layoutId(ThreadHeaderYieldingSlot)
                                )
                            }
                        }
                    }
                }
                val hasSubtitle = channelLinkLabel != null || !headerSubtitle.isNullOrBlank()
                val subtitleContent: @Composable (Modifier) -> Unit = { subModifier ->
                    // Web #10285: under a View in Channel tooltip.
                    if (channelLinkLabel != null) {
                        ThreadHeaderChannelLinkSubtitle(
                            channelLabel = channelLinkLabel,
                            replyCountLabel = replyThreadReplyCountLabel,
                            onOpenInChannel = {
                                trackHeader(RaftConversationHeaderAction.ViewInChannel, RaftConversationHeaderSurface.Title)
                                onOpenInChannel()
                            },
                            modifier = subModifier,
                        )
                    } else {
                        headerSubtitle?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                modifier = subModifier,
                                // raft-ui `PanelMeta` (md): sans text-footnote on foreground-hint.
                                style = ThreadHeaderMetaStyle,
                                color = RaftColor.ForegroundHint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                // raft-design desktop-density rule 2 & panel.md (web PanelHeaderContent / ThreadPanelHeader):
                // from 768px (RaftScale.Wide), every head is one line by touch or mouse alike; only a
                // compact phone stacks the subtitle under the title.
                CompositionLocalProvider(LocalRaftCopyHandler provides copyTrackedHandler) {
                    if (RaftTheme.scale == RaftScale.Wide && hasSubtitle) {
                        ThreadHeaderInlineTitleRow(
                            subtitlePriority = headerOriginal?.swapped == true,
                            modifier = headModifier,
                            title = { titleRowContent(Modifier.layoutId("title")) },
                            subtitle = { subtitleContent(Modifier.layoutId("subtitle")) },
                        )
                    } else {
                        Column(
                            modifier = headModifier,
                            // raft-ui `PanelHeaderContent`: the 22dp title line sits straight on the 18dp meta line.
                            verticalArrangement = Arrangement.Top
                        ) {
                            titleRowContent(Modifier.fillMaxWidth())
                            if (hasSubtitle) subtitleContent(Modifier)
                        }
                    }
                }
            }
        },
        trailingContent =
            if (showHeaderActions && controlsInColumn && taskColumnHead == null && !paneCloses) {
                // The Agent followers are the task's facts, not a button: they stay in the header.
                { agentFollowersHeader() }
            } else if (showHeaderActions || taskColumnHead != null || paneCloses) {
                {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ThreadHeaderActionGroupSpacing)
                ) {
                    var closeInMenu = false
                    var trailingCompact = baseCompact
                    if (showHeaderActions && controlsInColumn) {
                        if (headRowTabs == null) agentFollowersHeader()
                    } else if (showHeaderActions) {
                    if (headRowTabs == null) agentFollowersHeader()
                    // Web ThreadPanel: a reply thread's Search in Thread and View in Channel live in
                    // its ⋯ menu ("thread commands live in the menu"), not as header buttons.
                    val replyThreadMenu = headerDisplay.surfaceKind == RaftThreadSurfaceKind.ReplyThread
                    val searchLabel =
                        stringResource(
                            if (isNamedChannel) Res.string.thread_action_search_channel
                            else Res.string.thread_action_search_conversation
                        )
                    val callLabel = onStartCall?.let { stringResource(Res.string.thread_action_call) }
                    val settingsLabel =
                        stringResource(
                            when {
                                isNamedChannel && settingsOpen -> Res.string.channel_details_close
                                isNamedChannel -> Res.string.channel_details_open
                                settingsOpen -> Res.string.conversation_details_close
                                else -> Res.string.conversation_details_open
                            }
                        )
                    val openInChannelLabel = stringResource(Res.string.task_conversation_view_in_channel)
                    val sideCardOwnActions =
                        sideCardToggles?.let { toggles ->
                            val labels =
                                ConversationSideCard.entries.associateWith { card ->
                                    conversationSideCardToggleLabel(card, toggles.cards.isOpen(card))
                                }
                            threadHeaderSideCardOwnActions(
                                tasksAvailable = toggles.tasksAvailable,
                                isCardOpen = toggles.cards::isOpen,
                                labelForCard = { card, _ -> labels.getValue(card) },
                                onToggle = toggles.onToggle,
                            )
                        }.orEmpty()
                    val ownActions =
                        threadHeaderConversationOwnActions(
                            searchLabel = searchLabel.takeIf { !taskConversation && !replyThreadMenu && payload.threadHeaderSearchAction() != null },
                            onOpenSearch = onOpenSearch,
                            callLabel = callLabel.takeIf { !taskConversation && !replyThreadMenu },
                            onStartCall = onStartCall,
                            sideCardActions = sideCardOwnActions,
                            settingsLabel =
                                settingsLabel.takeIf {
                                    threadHeaderSettingsActionVisible(payload.showSettingsAction, headerDisplay.surfaceKind)
                                },
                            settingsOpen = settingsOpen,
                            onShowSettings = onShowSettings,
                            openInChannelLabel =
                                openInChannelLabel.takeIf {
                                    !replyThreadMenu && payload.threadHeaderOpenInParentActionVisible()
                                },
                            onOpenInChannel = onOpenInChannel,
                        )
                    val replyThreadSearch = onOpenSearch.takeIf { replyThreadMenu && payload.threadHeaderSearchAction() != null }
                    val replyThreadViewInChannel =
                        onOpenInChannel.takeIf { replyThreadMenu && parentReachable && payload.threadHeaderOpenInParentActionVisible() }
                    val toggleFocus = onToggleFocusMode
                    val hasPanelMenu =
                        onToggleThreadFollow != null || onMarkThreadUnread != null || taskConversation ||
                            replyThreadSearch != null || replyThreadViewInChannel != null || onOpenTimeline != null
                    val edgeInsetDp = ThreadHeaderEdgeInset.value
                    val actionSizeDp = ThreadHeaderActionSize.value
                    val horizontalSafeArea = LocalRaftWindowHorizontalSafeArea.current
                    val horizontalSafeAreaDp = horizontalSafeArea.left.value + horizontalSafeArea.right.value
                    val startInsetDp = if (showsBack && !controlsInColumn) edgeInsetDp else ThreadSplitDetailEdgeInset.value
                    val backWidthDp = if (showsBack && !controlsInColumn) actionSizeDp + ThreadHeaderContentSpacing.value else 0f
                    val leadingTileDp =
                        when {
                            headRowTabs != null -> 0f
                            activeConversationSwitch != null ->
                                ThreadHeaderDmAvatarSize.value + AgentConversationAvatarMenuExtraWidthDp + ThreadHeaderContentSpacing.value
                            loungeHeader != null -> ThreadHeaderDmAvatarSize.value + ThreadHeaderContentSpacing.value
                            headerDisplay.surfaceKind == RaftThreadSurfaceKind.DirectMessage ->
                                ThreadHeaderDmAvatarSize.value + ThreadHeaderContentSpacing.value
                            payload.showModeTabs -> ThreadHeaderChannelTileSize.value + ThreadHeaderContentSpacing.value
                            else -> 0f
                        }
                    val leadOverheadDp =
                        horizontalSafeAreaDp + startInsetDp + backWidthDp + leadingTileDp + ThreadHeaderContentSpacing.value + edgeInsetDp
                    // When row tabs sit inline in a narrow column (e.g. 160dp touch with 44dp bar buttons),
                    // apply raftPaneGridHeadFit so Expand (and then Close) fold into ⋯ instead of crushing tabs.
                    // On a phone (compact width, not wide/tablet/desktop), every top-level conversation
                    // header shows the resolved primary action (default Search) + ⋯ (`botiverse/slock#16464`,
                    // `#proj-chat:#45`, `#wg-mobile-sync:#13`).
                    val phoneHeadSplit =
                        if (headRowTabs == null && !wideWindow && isTopLevelConversation) {
                            splitPhoneConversationHeaderActions(
                                actions = ownActions,
                                idOf = ThreadHeaderOwnActionSpec::id,
                                existingMenuItems = hasPanelMenu,
                                config = phoneHeaderPrimaryConfig,
                            )
                        } else {
                            null
                        }
                    val fit =
                        if (headRowTabs != null) {
                            raftPaneGridHeadFit(
                                widthDp = headerWidthDp,
                                leadButtons = 0,
                                own = ownActions.size,
                                expand = toggleFocus != null,
                                close = paneCloses,
                                menuItems = if (hasPanelMenu) 1 else 0,
                                tabs = true,
                                buttonDp = actionSizeDp,
                                startDp = horizontalSafeAreaDp + startInsetDp + backWidthDp + leadingTileDp,
                                endDp = edgeInsetDp,
                                gapDp = ThreadHeaderActionGroupSpacing.value,
                                titleGapDp = ThreadHeaderContentSpacing.value,
                            )
                        } else if (phoneHeadSplit != null) {
                            RaftPaneGridHeadFit(shownOwn = phoneHeadSplit.visible.size)
                        } else {
                            RaftPaneGridHeadFit(
                                shownOwn =
                                    threadHeaderShownOwnActions(
                                        widthDp = headerWidthDp,
                                        leadOverheadDp = leadOverheadDp,
                                        ownCount = ownActions.size,
                                        actionSizeDp = actionSizeDp,
                                        // Expand and the side pane's Close never fold (web ThreadPanelHeader).
                                        fixedTrailingCount = (if (toggleFocus != null) 1 else 0) + (if (paneCloses) 1 else 0),
                                        existingMenuItems = hasPanelMenu,
                                    ),
                            )
                        }
                    closeInMenu = fit.closeInMenu
                    val shownOwnCount = fit.shownOwn
                    val visibleOwnActions = phoneHeadSplit?.visible ?: ownActions.take(shownOwnCount)
                    val foldedWindowActions =
                        buildList {
                            if (fit.expandInMenu && toggleFocus != null) {
                                add(
                                    ThreadHeaderOwnActionSpec(
                                        id = FOCUS_MODE_MENU_ITEM_ID,
                                        label = stringResource(if (focusModeActive) Res.string.tasks_task_column_restore else Res.string.tasks_task_column_expand),
                                        icon = if (focusModeActive) RaftLucideIconName.Minimize2 else RaftLucideIconName.Maximize2,
                                        onClick = toggleFocus,
                                    )
                                )
                            }
                            if (fit.closeInMenu && sidePane != null) {
                                add(
                                    ThreadHeaderOwnActionSpec(
                                        id = "thread-pane-close",
                                        label = stringResource(Res.string.common_close),
                                        icon = RaftLucideIconName.X,
                                        onClick = sidePane.close,
                                    )
                                )
                            }
                        }
                    val foldedOwnActions = (phoneHeadSplit?.folded ?: ownActions.drop(shownOwnCount)) + foldedWindowActions
                    val headerCompact = baseCompact || foldedOwnActions.isNotEmpty()
                    trailingCompact = headerCompact
                    val trackedFoldedOwnActions =
                        foldedOwnActions.map { action ->
                            val eventAction =
                                threadHeaderOwnActionProductEvent(
                                    actionId = action.id,
                                    settingsOpen = settingsOpen,
                                    focusModeActive = focusModeActive,
                                )
                            if (eventAction == null) {
                                action
                            } else {
                                val delegate = action.onClick
                                action.copy(
                                    onClick = {
                                        trackHeader(eventAction, RaftConversationHeaderSurface.MoreMenu, compact = headerCompact)
                                        delegate()
                                    }
                                )
                            }
                        }
                    visibleOwnActions.forEach { action ->
                        val trackedClick: () -> Unit = {
                            threadHeaderOwnActionProductEvent(
                                actionId = action.id,
                                settingsOpen = settingsOpen,
                                focusModeActive = focusModeActive,
                            )?.let { eventAction ->
                                trackHeader(eventAction, RaftConversationHeaderSurface.Header, compact = headerCompact)
                            }
                            action.onClick()
                        }
                        val renderButton: @Composable () -> Unit = {
                            if (action.toggle) {
                                ConversationHeaderToggle(
                                    icon = action.icon,
                                    selected = action.selected,
                                    label = action.label,
                                    onClick = trackedClick,
                                )
                            } else {
                                ThreadHeaderIconButton(icon = action.icon, contentDescription = action.label, onClick = trackedClick)
                            }
                        }
                        if (action.testTag != null) {
                            Box(Modifier.testTag(action.testTag)) { renderButton() }
                        } else {
                            renderButton()
                        }
                    }
                    if (hasPanelMenu || trackedFoldedOwnActions.isNotEmpty()) {
                        ThreadHeaderFocusModeMenu(
                            foldedActions = trackedFoldedOwnActions,
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
                            onMenuOpen = {
                                trackHeader(RaftConversationHeaderAction.OpenMenu, RaftConversationHeaderSurface.Header, compact = headerCompact)
                            },
                            onMenuItemSelected = { selectedId ->
                                threadHeaderOverflowMenuProductEvent(
                                    actionId = selectedId,
                                    followed = threadFollowed,
                                    taskConversation = taskConversation,
                                )?.let { eventAction ->
                                    trackHeader(eventAction, RaftConversationHeaderSurface.MoreMenu, compact = headerCompact)
                                }
                            },
                        )
                    }
                    // web ThreadPanelHeader (slock #12211): Focus mode is the head's Expand, a window
                    // control after ⋯ and never in it, so a wide layout always shows it.
                    if (!fit.expandInMenu) {
                        toggleFocus?.let { toggle ->
                            ThreadHeaderFocusModeToggle(
                                focused = focusModeActive,
                                onToggle = {
                                    trackHeader(
                                        if (focusModeActive) RaftConversationHeaderAction.Restore else RaftConversationHeaderAction.Expand,
                                        RaftConversationHeaderSurface.Header,
                                        compact = headerCompact,
                                    )
                                    toggle()
                                },
                            )
                        }
                    }
                    }
                    taskColumnHead?.let { RaftTaskColumnHeadActions(it) }
                    // web ThreadPanelHeader: Close after Expand, the head's last control, in the thread's column.
                    if (paneCloses && !closeInMenu) {
                        RaftSidePaneCloseButton(
                            onBeforeClose = {
                                trackHeader(RaftConversationHeaderAction.Close, RaftConversationHeaderSurface.Header, compact = trailingCompact)
                            }
                        )
                    }
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
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        ThreadHeaderViewInChannelLink(
            label = channelLabel,
            onViewInChannel = onOpenInChannel,
            style = ThreadHeaderMetaStyle,
            modifier = Modifier.weight(1f, fill = false)
        )
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
 * A conversation's name as a link that does what View in Channel does, under its tooltip: a reply
 * thread's channel or DM (web ThreadPanelHeader #10285) and the place in a task's ref (web
 * TaskConversationBar #16258). Screen readers hear where it goes, not just the name.
 */
@Composable
internal fun ThreadHeaderViewInChannelLink(
    label: String,
    onViewInChannel: () -> Unit,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val viewInChannel = stringResource(Res.string.task_conversation_view_in_channel)
    RaftTooltip(text = viewInChannel, modifier = modifier) {
        RaftTextLink(
            text = label,
            onClick = onViewInChannel,
            modifier = Modifier.semantics { onClick(label = viewInChannel) { onViewInChannel(); true } },
            style = style,
            maxLines = 1
        )
    }
}

internal const val ThreadHeaderMinTitleWidthDp = 56f

internal data class ThreadHeaderOwnActionSpec(
    val id: String,
    val label: String,
    val icon: RaftLucideIconName,
    val selected: Boolean = false,
    val toggle: Boolean = false,
    val testTag: String? = null,
    val onClick: () -> Unit,
)

/**
 * Web Task v2 conversation head button order (`useChannelHeadToggles.tsx`, `botiverse/slock#16464`):
 * Search, Call (agent DM voice call when offered), Tasks, Files, Settings, then Open in Channel where applicable.
 * No Members button (Members lives inside Settings / channel details, also reachable via title tap).
 */
internal fun threadHeaderConversationOwnActions(
    searchLabel: String?,
    onOpenSearch: () -> Unit,
    callLabel: String? = null,
    onStartCall: (() -> Unit)? = null,
    sideCardActions: List<ThreadHeaderOwnActionSpec> = emptyList(),
    settingsLabel: String?,
    settingsOpen: Boolean,
    onShowSettings: () -> Unit,
    openInChannelLabel: String?,
    onOpenInChannel: () -> Unit,
): List<ThreadHeaderOwnActionSpec> =
    buildList {
        if (!searchLabel.isNullOrBlank()) {
            add(
                ThreadHeaderOwnActionSpec(
                    id = "thread-search",
                    label = searchLabel,
                    icon = RaftLucideIconName.Search,
                    onClick = onOpenSearch,
                )
            )
        }
        if (!callLabel.isNullOrBlank() && onStartCall != null) {
            add(
                ThreadHeaderOwnActionSpec(
                    id = "thread-call",
                    label = callLabel,
                    icon = RaftLucideIconName.Phone,
                    testTag = "agent-dm-call",
                    onClick = onStartCall,
                )
            )
        }
        addAll(sideCardActions)
        if (!settingsLabel.isNullOrBlank()) {
            add(
                ThreadHeaderOwnActionSpec(
                    id = "thread-settings",
                    label = settingsLabel,
                    icon = RaftLucideIconName.Settings,
                    selected = settingsOpen,
                    toggle = true,
                    onClick = onShowSettings,
                )
            )
        }
        if (!openInChannelLabel.isNullOrBlank()) {
            add(
                ThreadHeaderOwnActionSpec(
                    id = "thread-open-in-channel",
                    label = openInChannelLabel,
                    icon = RaftLucideIconName.MapPin,
                    onClick = onOpenInChannel,
                )
            )
        }
    }

/** Maximum trailing icon buttons on a phone conversation header (`botiverse/slock#16464`). */
internal const val ThreadHeaderPhoneMaxTrailingButtons = 2

/**
 * Web `headFit.ts` `phoneHeadFold` (`botiverse/slock#16464`, `#proj-chat:#45`):
 * On a phone every top-level conversation header shows at most two buttons on the right —
 * Search and `⋯` — folding Call (when offered), Tasks, Files, and Details & settings into `⋯`
 * at every phone width. A head with at most two actions and no existing menu items keeps both
 * without `⋯`.
 */
internal fun threadHeaderPhoneShownOwnActions(
    ownCount: Int,
    existingMenuItems: Boolean = false,
): Int {
    if (ownCount <= 0) return 0
    if (!existingMenuItems && ownCount <= ThreadHeaderPhoneMaxTrailingButtons) return ownCount
    return (ThreadHeaderPhoneMaxTrailingButtons - 1).coerceAtMost(ownCount)
}

/**
 * Web `headFit.ts` (`shownOwnActions`, #189 rule 6): how many of the conversation head's own
 * buttons stay visible before folding from the last into `⋯` so the title keeps at least
 * [minTitleWidthDp]. When [widthDp] is not measured yet (`<= 0`), every action stays visible.
 */
internal fun threadHeaderShownOwnActions(
    widthDp: Float,
    leadOverheadDp: Float,
    ownCount: Int,
    actionSizeDp: Float,
    actionGapDp: Float = ThreadHeaderActionGroupSpacing.value,
    fixedTrailingCount: Int = 0,
    existingMenuItems: Boolean = false,
    minTitleWidthDp: Float = ThreadHeaderMinTitleWidthDp,
): Int {
    if (ownCount <= 0) return 0
    if (widthDp <= 0f) return ownCount
    val room = widthDp - leadOverheadDp - minTitleWidthDp
    for (shown in ownCount downTo 1) {
        val needsOverflowMenu = existingMenuItems || shown < ownCount
        val fixed = fixedTrailingCount + if (needsOverflowMenu) 1 else 0
        val totalSlots = shown + fixed
        val need = actionSizeDp * totalSlots + actionGapDp * (totalSlots - 1).coerceAtLeast(0)
        if (need <= room) return shown
    }
    return 0
}

/**
 * Single source for Task v2 header side-card actions (Tasks before Files; Tasks only when
 * [tasksAvailable]), shared by the Compose header, Duo side column, live iOS native nav bar, and
 * pre-push native nav bar prefill.
 */
internal fun threadHeaderSideCardOwnActions(
    tasksAvailable: Boolean,
    isCardOpen: (ConversationSideCard) -> Boolean = { false },
    labelForCard: (ConversationSideCard, Boolean) -> String,
    onToggle: (ConversationSideCard) -> Unit = {},
): List<ThreadHeaderOwnActionSpec> =
    ConversationSideCard.entries
        .filter { it != ConversationSideCard.Tasks || tasksAvailable }
        .map { card ->
            val open = isCardOpen(card)
            ThreadHeaderOwnActionSpec(
                id = "thread-side-card-${card.name.lowercase()}",
                label = labelForCard(card, open),
                icon = card.icon,
                selected = open,
                toggle = true,
                onClick = { onToggle(card) },
            )
        }

internal data class ThreadHeaderSideColumnLabels(
    val searchLabel: String,
    val searchInThreadLabel: String,
    val searchInTaskLabel: String,
    val settingsLabel: String,
    val viewInChannelLabel: String,
    val callLabel: String = "",
    val loungeLabel: String = "",
    val timelineLabel: String = "",
    val markUnreadLabel: String = "",
    val deleteTaskLabel: String = "",
    val focusLabel: String = "",
    val followLabel: String? = null,
)

/**
 * Pure single source of conversation header actions for the Duo side column, live iOS native nav
 * bar, and pre-push native nav bar prefill (#proj-mobile:#281 / #282). Own conversation buttons
 * always come from [threadHeaderConversationOwnActions], so live head and prefill cannot drift.
 */
internal fun threadHeaderSideColumnActionsForPayload(
    payload: RaftThreadScreenPayload,
    surfaceKind: String,
    taskConversation: Boolean,
    labels: ThreadHeaderSideColumnLabels,
    onOpenSearch: () -> Unit = {},
    onStartCall: (() -> Unit)? = null,
    onShowSettings: () -> Unit = {},
    onOpenInChannel: () -> Unit = {},
    parentReachable: Boolean = true,
    onToggleFocusMode: (() -> Unit)? = null,
    focusModeActive: Boolean = false,
    threadFollowed: Boolean? = null,
    onToggleThreadFollow: (() -> Unit)? = null,
    onMarkThreadUnread: (() -> Unit)? = null,
    onViewTaskInChannel: (() -> Unit)? = null,
    sideCardActions: List<ThreadHeaderOwnActionSpec> = emptyList(),
    onOpenTimeline: (() -> Unit)? = null,
    onDeleteTask: (() -> Unit)? = null,
): List<RaftSideAction> {
    val replyThreadMenu = surfaceKind == RaftThreadSurfaceKind.ReplyThread
    val ownActions =
        threadHeaderConversationOwnActions(
            searchLabel = labels.searchLabel.takeIf { !taskConversation && !replyThreadMenu && payload.threadHeaderSearchAction() != null },
            onOpenSearch = onOpenSearch,
            callLabel = labels.callLabel.takeIf { !taskConversation && !replyThreadMenu && onStartCall != null },
            onStartCall = onStartCall,
            sideCardActions = sideCardActions,
            settingsLabel = labels.settingsLabel.takeIf { threadHeaderSettingsActionVisible(payload.showSettingsAction, surfaceKind) },
            settingsOpen = false,
            onShowSettings = onShowSettings,
            openInChannelLabel =
                labels.viewInChannelLabel.takeIf {
                    !replyThreadMenu && parentReachable && payload.threadHeaderOpenInParentActionVisible()
                },
            onOpenInChannel = onOpenInChannel,
        )
    return buildList {
        ownActions.forEach { action ->
            add(raftSideColumnLucideAction(action.id, action.label, action.icon, action.onClick))
        }
        // web ThreadPanelHeader (slock #12211): Focus mode (the head's Expand) never goes into the ⋯.
        if (onToggleFocusMode != null) {
            val icon = if (focusModeActive) RaftLucideIconName.Minimize2 else RaftLucideIconName.Maximize2
            add(raftSideColumnLucideAction(FOCUS_MODE_MENU_ITEM_ID, labels.focusLabel, icon, onToggleFocusMode).copy(keepVisible = true))
        }
        // Menu order v4 (botiverse/slock #16225): Follow + Mark as unread lead the thread/task ⋯,
        // before Search, Timeline, View in channel, then Delete task.
        if (labels.followLabel != null && threadFollowed != null && onToggleThreadFollow != null) {
            add(
                raftSideColumnLucideAction(
                    FOLLOW_THREAD_MENU_ITEM_ID,
                    labels.followLabel,
                    threadFollowMenuIcon(threadFollowed),
                    onToggleThreadFollow,
                    priority = -1,
                )
            )
        }
        if (onMarkThreadUnread != null) {
            add(
                raftSideColumnLucideAction(
                    MARK_THREAD_UNREAD_MENU_ITEM_ID,
                    labels.markUnreadLabel,
                    RaftLucideIconName.MessageSquareDot,
                    onMarkThreadUnread,
                    priority = -1,
                )
            )
        }
        if (taskConversation || (replyThreadMenu && payload.threadHeaderSearchAction() != null)) {
            val label = if (taskConversation) labels.searchInTaskLabel else labels.searchInThreadLabel
            add(raftSideColumnLucideAction(SEARCH_IN_TASK_MENU_ITEM_ID, label, RaftLucideIconName.Search, onOpenSearch))
        }
        // Web ThreadOverflowMenu: Timeline after Search, before View in Channel; in the ⋯.
        if (onOpenTimeline != null) {
            add(raftSideColumnLucideAction(THREAD_TIMELINE_MENU_ITEM_ID, labels.timelineLabel, RaftLucideIconName.List, onOpenTimeline, priority = -1))
        }
        if (taskConversation && onViewTaskInChannel != null) {
            add(raftSideColumnLucideAction(VIEW_TASK_IN_CHANNEL_MENU_ITEM_ID, labels.viewInChannelLabel, RaftLucideIconName.MapPin, onViewTaskInChannel))
        } else if (replyThreadMenu && parentReachable && payload.threadHeaderOpenInParentActionVisible()) {
            add(raftSideColumnLucideAction(VIEW_TASK_IN_CHANNEL_MENU_ITEM_ID, labels.viewInChannelLabel, RaftLucideIconName.MapPin, onOpenInChannel))
        }
        if (taskConversation && onDeleteTask != null) {
            add(raftSideColumnLucideAction(DELETE_TASK_MENU_ITEM_ID, labels.deleteTaskLabel, RaftLucideIconName.Trash2, onDeleteTask, priority = -1))
        }
    }
}

/**
 * The conversation header's actions for the Duo side column and iOS native nav bar, in web Task v2
 * order: Search, Lounge (agent DM), Tasks, Files, Settings — folding from the last into ⋯; reply
 * thread and task commands prioritize Search and View in Channel while Timeline / Mark Unread /
 * Follow / Delete sit in ⋯, and Focus mode stays out of ⋯.
 */
@Composable
private fun threadHeaderSideColumnActions(
    payload: RaftThreadScreenPayload,
    surfaceKind: String,
    taskConversation: Boolean,
    isNamedChannel: Boolean,
    settingsOpen: Boolean,
    onOpenSearch: () -> Unit,
    onStartCall: (() -> Unit)? = null,
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
): List<RaftSideAction> {
    val sideCardActions =
        sideCardToggles?.let { toggles ->
            val labels =
                ConversationSideCard.entries.associateWith { card ->
                    conversationSideCardToggleLabel(card, toggles.cards.isOpen(card))
                }
            threadHeaderSideCardOwnActions(
                tasksAvailable = toggles.tasksAvailable,
                isCardOpen = toggles.cards::isOpen,
                labelForCard = { card, _ -> labels.getValue(card) },
                onToggle = toggles.onToggle,
            )
        }.orEmpty()
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
    val searchLabel =
        stringResource(
            if (isNamedChannel) Res.string.thread_action_search_channel else Res.string.thread_action_search_conversation
        )
    val settingsLabel =
        stringResource(
            when {
                isNamedChannel && settingsOpen -> Res.string.channel_details_close
                isNamedChannel -> Res.string.channel_details_open
                settingsOpen -> Res.string.conversation_details_close
                else -> Res.string.conversation_details_open
            }
        )
    val callLabel = if (onStartCall != null) stringResource(Res.string.thread_action_call) else ""
    return threadHeaderSideColumnActionsForPayload(
        payload = payload,
        surfaceKind = surfaceKind,
        taskConversation = taskConversation,
        labels =
            ThreadHeaderSideColumnLabels(
                searchLabel = searchLabel,
                searchInThreadLabel = stringResource(Res.string.thread_action_search_in_thread),
                searchInTaskLabel = stringResource(Res.string.task_conversation_search),
                settingsLabel = settingsLabel,
                viewInChannelLabel = stringResource(Res.string.task_conversation_view_in_channel),
                callLabel = callLabel,
                timelineLabel = stringResource(Res.string.thread_outline_list_title),
                markUnreadLabel = stringResource(Res.string.home_action_mark_as_unread),
                deleteTaskLabel = stringResource(Res.string.task_delete_action),
                focusLabel =
                    stringResource(if (focusModeActive) Res.string.tasks_task_column_restore else Res.string.tasks_task_column_expand),
                followLabel = followLabel,
            ),
        onOpenSearch = onOpenSearch,
        onStartCall = onStartCall,
        onShowSettings = onShowSettings,
        onOpenInChannel = onOpenInChannel,
        parentReachable = parentReachable,
        onToggleFocusMode = onToggleFocusMode,
        focusModeActive = focusModeActive,
        threadFollowed = threadFollowed,
        onToggleThreadFollow = onToggleThreadFollow,
        onMarkThreadUnread = onMarkThreadUnread,
        onViewTaskInChannel = onViewTaskInChannel,
        sideCardActions = sideCardActions,
        onOpenTimeline = onOpenTimeline,
        onDeleteTask = onDeleteTask,
    )
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
        val nameY = (height - namePlaceable.height) / 2
        val nameBaseline = namePlaceable[FirstBaseline]
        val alignmentLines: Map<AlignmentLine, Int> =
            if (nameBaseline != AlignmentLine.Unspecified) {
                mapOf(FirstBaseline to nameY + nameBaseline)
            } else {
                emptyMap()
            }
        layout(width, height, alignmentLines = alignmentLines) {
            var x = 0
            placeables.forEach {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width + gap
            }
        }
    }
}

/**
 * Wide-scale single-line conversation head (raft-design desktop-density rule 2, web
 * `PanelHeaderContent` / `ThreadPanelHeader`): title row and subtitle share one baseline separated
 * by [RaftPanelHeadInlineMetaGap]. Normally the title keeps its room and the subtitle yields first;
 * when a reply thread's title has swapped to the original message preview ([subtitlePriority]), the
 * place + reply count keeps up to half the width (matching web `ThreadPanelHeader` `max-w-[50%]`)
 * so a long message preview never erases the channel link and reply count.
 */
@Composable
private fun ThreadHeaderInlineTitleRow(
    subtitlePriority: Boolean,
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    subtitle: @Composable () -> Unit,
) {
    Layout(
        content = {
            title()
            subtitle()
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val gap = RaftPanelHeadInlineMetaGap.roundToPx()
        val loose = constraints.copy(minWidth = 0)
        val titleMeasurable = measurables.first { it.layoutId == "title" }
        val subtitleMeasurable = measurables.firstOrNull { it.layoutId == "subtitle" }
        val (titlePlaceable, subtitlePlaceable) =
            if (subtitleMeasurable == null) {
                titleMeasurable.measure(loose) to null
            } else if (!constraints.hasBoundedWidth) {
                titleMeasurable.measure(loose) to subtitleMeasurable.measure(loose)
            } else if (subtitlePriority) {
                val available = (constraints.maxWidth - gap).coerceAtLeast(0)
                val halfRoom = available / 2
                val titleIntrinsic = titleMeasurable.maxIntrinsicWidth(constraints.maxHeight)
                val subtitleCap =
                    if (titleIntrinsic < available - halfRoom) {
                        (available - titleIntrinsic).coerceAtLeast(halfRoom)
                    } else {
                        halfRoom
                    }
                val sub = subtitleMeasurable.measure(loose.copy(maxWidth = subtitleCap.coerceAtLeast(0)))
                val titleRoom =
                    if (sub.width > 0) (constraints.maxWidth - sub.width - gap).coerceAtLeast(0) else constraints.maxWidth
                val tit = titleMeasurable.measure(loose.copy(maxWidth = titleRoom))
                tit to sub
            } else {
                val tit = titleMeasurable.measure(loose)
                val subRoom =
                    if (tit.width > 0) (constraints.maxWidth - tit.width - gap).coerceAtLeast(0) else constraints.maxWidth
                val sub = subtitleMeasurable.measure(loose.copy(maxWidth = subRoom))
                tit to sub
            }
        val showGap = titlePlaceable.width > 0 && subtitlePlaceable != null && subtitlePlaceable.width > 0
        val contentWidth = titlePlaceable.width + (if (showGap) gap else 0) + (subtitlePlaceable?.width ?: 0)
        val width = constraints.constrainWidth(contentWidth)
        val titleBaseline = titlePlaceable[FirstBaseline]
        val subtitleBaseline = subtitlePlaceable?.get(FirstBaseline) ?: AlignmentLine.Unspecified
        val alignBaselines =
            subtitlePlaceable != null &&
                subtitlePlaceable.width > 0 &&
                titleBaseline != AlignmentLine.Unspecified &&
                subtitleBaseline != AlignmentLine.Unspecified
        val naturalHeight =
            if (alignBaselines) {
                val ascent = maxOf(titleBaseline, subtitleBaseline)
                val descent = maxOf(titlePlaceable.height - titleBaseline, subtitlePlaceable.height - subtitleBaseline)
                ascent + descent
            } else {
                maxOf(titlePlaceable.height, subtitlePlaceable?.height ?: 0)
            }
        val height = constraints.constrainHeight(naturalHeight)
        layout(width, height) {
            if (alignBaselines) {
                val ascent = maxOf(titleBaseline, subtitleBaseline)
                val topOffset = (height - naturalHeight) / 2
                titlePlaceable.placeRelative(0, topOffset + ascent - titleBaseline)
                subtitlePlaceable.placeRelative(
                    titlePlaceable.width + (if (showGap) gap else 0),
                    topOffset + ascent - subtitleBaseline,
                )
            } else {
                titlePlaceable.placeRelative(0, (height - titlePlaceable.height) / 2)
                if (subtitlePlaceable != null && subtitlePlaceable.width > 0) {
                    subtitlePlaceable.placeRelative(
                        titlePlaceable.width + (if (showGap) gap else 0),
                        (height - subtitlePlaceable.height) / 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadDmAgentActivity(
    state: RaftAgentStatusIndicatorState,
    agentName: String? = null,
    modifier: Modifier = Modifier
) {
    // Web ChatPanel AgentDMStatus (#10446): stuck on one tool call reads as that, in the
    // warning colour, not as a plain "busy". Otherwise, a failed start waiting for runtime sign-in
    // (slock #15282) reads "Sign-in needed for <runtime>" ("Sign-in needed" in a phone window: under
    // the app's wide breakpoint, [raftWindowWide], as the web's is under its `md`) on the
    // error light with Sign in beside it for viewers who can act on the Computer.
    val toolStuck = state.visibleToolStuck()
    val runtimeRetired = if (toolStuck == null) rememberAgentRuntimeRetiredState(state) else null
    if (runtimeRetired != null) {
        val wide = raftWindowWide()
        val retiredLabel = if (wide) runtimeRetired.fullText else runtimeRetired.shortText
        Row(
            modifier =
                modifier
                    .widthIn(max = ThreadHeaderDmAgentActivityMaxWidth)
                    .semantics { contentDescription = runtimeRetired.detail },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
        ) {
            RaftStatusIndicator(
                color = RaftColor.AccentOrange,
                size = ThreadHeaderDmAgentActivityDotSize,
            )
            Text(
                text = retiredLabel,
                style =
                    RaftType.Caption.copy(
                        color = RaftColor.ForegroundHint,
                        fontFamily = RaftMonoFamily
                    ),
                color = RaftColor.ForegroundHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .testTag(AgentRuntimeRetiredTestTags.DmHeaderStatus)
            )
        }
        return
    }
    val readinessCopy = if (toolStuck == null) rememberAgentReadinessDisplayCopy(state) else null
    if (readinessCopy != null) {
        Row(
            modifier =
                modifier
                    .widthIn(max = ThreadHeaderDmAgentActivityMaxWidth)
                    .semantics { contentDescription = readinessCopy.fullDetail },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
        ) {
            RaftStatusIndicator(
                color = readinessCopy.light.dotColor(),
                size = ThreadHeaderDmAgentActivityDotSize,
            )
            Text(
                text = readinessCopy.title,
                style =
                    RaftType.Caption.copy(
                        color = RaftColor.ForegroundHint,
                        fontFamily = RaftMonoFamily
                    ),
                color = RaftColor.ForegroundHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(AgentReadinessTestTags.DmHeaderStatus)
            )
        }
        return
    }
    val signInResolution =
        if (toolStuck == null) {
            rememberAgentRuntimeSignInResolution(state, fallbackName = agentName)
        } else {
            AgentRuntimeSignInResolution(state = null, clearedByLiveLogin = false)
        }
    val signIn = signInResolution.state
    if (signIn != null) {
        val wide = raftWindowWide()
        val signInLabel = if (wide) signIn.fullText else signIn.shortText
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
        ) {
            RaftStatusIndicator(
                color = RaftColor.AccentOrange,
                size = ThreadHeaderDmAgentActivityDotSize,
            )
            Text(
                text = signInLabel,
                style =
                    RaftType.Caption.copy(
                        color = RaftColor.ForegroundHint,
                        fontFamily = RaftMonoFamily
                    ),
                color = RaftColor.ForegroundHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .testTag(AgentRuntimeSignInTestTags.DmHeaderStatus)
                        .semantics { contentDescription = signIn.detail }
            )
            AgentRuntimeSignInAction(
                signIn = signIn,
                testTag = AgentRuntimeSignInTestTags.DmHeaderAction,
            )
        }
        return
    }
    val effectiveState = state.withoutClearedLoginRequiredAttention(signInResolution.clearedByLiveLogin)
    val attentionParts = if (toolStuck == null) rememberAgentAttentionParts(effectiveState) else null
    if (attentionParts != null) {
        Row(
            modifier =
                modifier
                    .widthIn(max = ThreadHeaderDmAgentActivityMaxWidth)
                    .semantics { contentDescription = attentionParts.fullText },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
        ) {
            RaftStatusIndicator(
                color = RaftColor.AccentOrange,
                size = ThreadHeaderDmAgentActivityDotSize,
            )
            Text(
                text = attentionParts.short,
                style =
                    RaftType.Caption.copy(
                        color = RaftColor.ForegroundHint,
                        fontFamily = RaftMonoFamily
                    ),
                color = RaftColor.ForegroundHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .testTag(AgentAttentionTestTags.DmHeaderStatus)
            )
        }
        return
    }
    val deliveryHold = if (toolStuck == null) rememberAgentDeliveryHoldView(effectiveState) else null
    if (deliveryHold != null) {
        val wide = raftWindowWide()
        val holdLabel = if (wide) deliveryHold.text else deliveryHold.shortText
        Row(
            modifier =
                modifier
                    .widthIn(max = ThreadHeaderDmAgentActivityMaxWidth)
                    .semantics { contentDescription = deliveryHold.text },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
        ) {
            RaftStatusIndicator(
                color = RaftColor.AccentOrange,
                size = ThreadHeaderDmAgentActivityDotSize,
            )
            Text(
                text = holdLabel,
                style =
                    RaftType.Caption.copy(
                        color = RaftColor.ForegroundHint,
                        fontFamily = RaftMonoFamily
                    ),
                color = RaftColor.ForegroundHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(AgentDeliveryHoldTestTags.DmHeaderStatus)
            )
        }
        return
    }
    val label = if (toolStuck != null) localizedAgentToolStuckText(toolStuck) else effectiveState.localizedMemberStatusLabel()
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
            color = effectiveState.color,
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
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier) // raw-click: an avatar face, see above
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
private fun ThreadHeaderIconButton(
    icon: RaftLucideIconName,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    ThreadHeaderCompactAction(icon = icon, contentDescription = contentDescription, onClick = onClick)
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
    val adaptiveFocusContext =
        if (adaptiveCloseRequest == null) {
            ThreadAdaptiveFocusContext(yieldsToSideThread = false, isDefaultLanding = false)
        } else {
            rememberThreadAdaptiveFocusContext(adaptiveCloseRequest, adaptiveStoreScope)
        }
    return rememberComposerFocusOnOpen(
        policy = policy,
        shown = routeActive,
        yieldsToSideThread = adaptiveFocusContext.yieldsToSideThread,
        opensOnMessage = composerAutofocusSkipsMessageOpen(desktopHost = raftPlatformHasFinePointer(), opensOnMessage = opensOnMessage),
        userInitiated = !adaptiveFocusContext.isDefaultLanding,
    )
}

private data class ThreadAdaptiveFocusContext(
    val yieldsToSideThread: Boolean,
    val isDefaultLanding: Boolean,
)

/** [composerAutofocusYieldsToSideThread] and default-landing status for the wide detail's [adaptiveCloseRequest], live. */
@Composable
private fun rememberThreadAdaptiveFocusContext(
    adaptiveCloseRequest: RaftAdaptiveDetailCloseRequest,
    adaptiveStoreScope: RaftShellStoreScope,
): ThreadAdaptiveFocusContext {
    val pager = LocalActivity.current.getPager() as Pager
    val workspaceStore =
        remember(pager, adaptiveStoreScope) {
            RaftSharedStoreRegistry.get(Utils.bridgeModule(pager.pagerId)).adaptiveWorkspaceStore(adaptiveStoreScope)
        }
    fun build.raft.app.data.SharedAdaptiveWorkspaceSession.toFocusContext(): ThreadAdaptiveFocusContext {
        val selection = workspace?.selection
        val isDefaultLanding =
            workspace?.selectionIsDefaultLanding == true &&
                selection != null &&
                selection.identity.key == adaptiveCloseRequest.identityKey &&
                selection.generation == adaptiveCloseRequest.generation
        return ThreadAdaptiveFocusContext(
            yieldsToSideThread = composerAutofocusYieldsToSideThread(adaptiveCloseRequest),
            isDefaultLanding = isDefaultLanding,
        )
    }
    val context by
        remember(workspaceStore, adaptiveCloseRequest) {
            workspaceStore.state
                .map { it.toFocusContext() }
                .distinctUntilChanged()
        }.collectAsState(
            initial = workspaceStore.snapshot().toFocusContext()
        )
    return context
}

internal data class ThreadHeaderOverflowMenuLabels(
    val followLabel: String? = null,
    val markUnreadLabel: String = "",
    val searchLabel: String = "",
    val timelineLabel: String = "",
    val viewInChannelLabel: String = "",
    val notificationLevelLabel: String? = null,
    val notificationLevelIcon: RaftLucideIconName = RaftLucideIconName.Bell,
    val deleteTaskLabel: String = "",
)

/**
 * Thread / task `⋯` header menu items (menu order v4, botiverse/slock #16225 / `563672da5`,
 * `ThreadOverflowMenu.tsx`):
 * 1. Follow / Unfollow + Mark as unread (for a reply thread; a task has no Mark as unread so its
 *    Follow shares the next group rather than standing alone above a divider).
 * 2. Search, Timeline, View in channel, then folded head actions.
 * 3. Notifications level (mobile-only, when supported).
 * 4. Delete task (when offered).
 * Dividers appear only between non-empty groups (never leading, trailing, or double).
 */
internal fun threadHeaderOverflowMenuItems(
    labels: ThreadHeaderOverflowMenuLabels,
    foldedActions: List<ThreadHeaderOwnActionSpec> = emptyList(),
    followed: Boolean? = null,
    hasToggleFollow: Boolean = false,
    hasMarkUnread: Boolean = false,
    hasSearch: Boolean = false,
    hasTimeline: Boolean = false,
    hasViewInChannel: Boolean = false,
    taskFollowLabels: Boolean = false,
    hasDeleteTask: Boolean = false,
): List<RaftUiMenuItem> =
    buildList {
        val showFollow = followed != null && hasToggleFollow && !labels.followLabel.isNullOrBlank()
        val showMarkUnread = hasMarkUnread && !taskFollowLabels
        val hasAttentionGroup = showFollow || showMarkUnread
        if (showFollow) {
            add(
                RaftUiMenuItem(
                    id = FOLLOW_THREAD_MENU_ITEM_ID,
                    label = labels.followLabel.orEmpty(),
                    icon = threadFollowMenuIcon(followed == true),
                    enabled = true,
                )
            )
        }
        if (showMarkUnread) {
            add(
                RaftUiMenuItem(
                    id = MARK_THREAD_UNREAD_MENU_ITEM_ID,
                    label = labels.markUnreadLabel,
                    icon = RaftLucideIconName.MessageSquareDot,
                    enabled = true,
                )
            )
        }
        // Web ThreadOverflowMenu.tsx:177: a task has no Mark as Unread, so its Follow shares the
        // conversation group rather than standing alone above a divider.
        var needConversationDivider = !taskFollowLabels && hasAttentionGroup
        fun consumeConversationDivider(): Boolean {
            val divider = needConversationDivider
            needConversationDivider = false
            return divider
        }
        if (hasSearch) {
            add(
                RaftUiMenuItem(
                    id = SEARCH_IN_TASK_MENU_ITEM_ID,
                    label = labels.searchLabel,
                    icon = RaftLucideIconName.Search,
                    enabled = true,
                    dividerBefore = consumeConversationDivider(),
                )
            )
        }
        if (hasTimeline) {
            add(
                RaftUiMenuItem(
                    id = THREAD_TIMELINE_MENU_ITEM_ID,
                    label = labels.timelineLabel,
                    icon = RaftLucideIconName.List,
                    enabled = true,
                    dividerBefore = consumeConversationDivider(),
                )
            )
        }
        if (hasViewInChannel) {
            add(
                RaftUiMenuItem(
                    id = VIEW_TASK_IN_CHANNEL_MENU_ITEM_ID,
                    label = labels.viewInChannelLabel,
                    icon = RaftLucideIconName.MapPin,
                    enabled = true,
                    dividerBefore = consumeConversationDivider(),
                )
            )
        }
        foldedActions.forEach { action ->
            add(
                RaftUiMenuItem(
                    id = action.id,
                    label = action.label,
                    icon = action.icon,
                    enabled = true,
                    dividerBefore = consumeConversationDivider(),
                )
            )
        }
        // Design §4.3: the thread's notification level (All replies = follow / Mentions / Nothing / Default).
        labels.notificationLevelLabel?.let { levelLabel ->
            add(
                RaftUiMenuItem(
                    id = NOTIFICATION_LEVEL_MENU_ITEM_ID,
                    label = levelLabel,
                    icon = labels.notificationLevelIcon,
                    enabled = true,
                    dividerBefore = isNotEmpty(),
                )
            )
        }
        // Web: a separator, then Delete task (trash icon, no red in the menu).
        if (hasDeleteTask) {
            add(
                RaftUiMenuItem(
                    id = DELETE_TASK_MENU_ITEM_ID,
                    label = labels.deleteTaskLabel,
                    icon = RaftLucideIconName.Trash2,
                    enabled = true,
                    dividerBefore = isNotEmpty(),
                )
            )
        }
    }

/**
 * task #180 (web parity #9172/#9335, menu order v4 #16225): the thread/task panel's `⋯` overflow
 * menu, anchored in the header.
 */
@Composable
private fun ThreadHeaderFocusModeMenu(
    foldedActions: List<ThreadHeaderOwnActionSpec> = emptyList(),
    followed: Boolean? = null,
    onToggleFollow: (() -> Unit)? = null,
    /**
     * botiverse/slock #11687 / #16225 (web ThreadOverflowMenu): Mark as unread, after Follow in
     * the thread's first group; null for a thread the person doesn't follow.
     */
    onMarkUnread: (() -> Unit)? = null,
    /** Web ThreadOverflowMenu: Search in Task (a task) or Search in Thread (a reply thread). */
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
    onMenuOpen: (() -> Unit)? = null,
    onMenuItemSelected: ((String) -> Unit)? = null,
) {
    // An older server without the unified API shows no level row.
    val notificationLevelTarget = notificationLevelTargetInput.whenLevelsSupported()
    val anchorRef = remember { RaftLiveRootAnchorRef(identity = "thread_header_focus_mode") }
    var localMenuAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var notificationLevelDialogOpen by remember { mutableStateOf(false) }
    val notificationLevelLabel = rememberRaftNotificationLevelMenuLabel(notificationLevelTarget)
    val notificationLevelIcon = rememberRaftNotificationLevelIcon(notificationLevelTarget)
    val latestFoldedActions by rememberUpdatedState(foldedActions)
    val latestOnMenuOpen by rememberUpdatedState(onMenuOpen)
    val latestOnMenuItemSelected by rememberUpdatedState(onMenuItemSelected)
    val latestOnToggleFollow by rememberUpdatedState(onToggleFollow)
    val latestOnMarkUnread by rememberUpdatedState(onMarkUnread)
    val latestOnSearchInTask by rememberUpdatedState(onSearchInTask)
    val latestOnTimeline by rememberUpdatedState(onTimeline)
    val latestOnViewInChannel by rememberUpdatedState(onViewInChannel)
    val latestOnDeleteTask by rememberUpdatedState(onDeleteTask)
    val followLabel =
        if (followed != null && onToggleFollow != null) {
            stringResource(
                when {
                    taskFollowLabels && followed -> Res.string.task_conversation_unfollow
                    taskFollowLabels -> Res.string.task_conversation_follow
                    followed -> Res.string.thread_action_unfollow_thread
                    else -> Res.string.thread_action_follow_thread
                }
            )
        } else {
            null
        }
    val items =
        threadHeaderOverflowMenuItems(
            labels =
                ThreadHeaderOverflowMenuLabels(
                    followLabel = followLabel,
                    markUnreadLabel = stringResource(Res.string.home_action_mark_as_unread),
                    searchLabel = stringResource(if (taskFollowLabels) Res.string.task_conversation_search else Res.string.thread_action_search_in_thread),
                    timelineLabel = stringResource(Res.string.thread_outline_list_title),
                    viewInChannelLabel = stringResource(Res.string.task_conversation_view_in_channel),
                    notificationLevelLabel = notificationLevelTarget?.let { notificationLevelLabel },
                    notificationLevelIcon = notificationLevelIcon,
                    deleteTaskLabel = stringResource(Res.string.task_delete_action),
                ),
            foldedActions = foldedActions,
            followed = followed,
            hasToggleFollow = onToggleFollow != null,
            hasMarkUnread = onMarkUnread != null,
            hasSearch = onSearchInTask != null,
            hasTimeline = onTimeline != null,
            hasViewInChannel = onViewInChannel != null,
            taskFollowLabels = taskFollowLabels,
            hasDeleteTask = onDeleteTask != null,
        )
    // task #180: the menu is drawn by the SHELL, not here. This page is a Kuikly @Page whose visible
    // surface is only its own pane, so a Modal opened inside it is clipped to that pane (device
    // evidence: nothing outside the window's left 400px strip could ever appear). We resolve the
    // anchor here -- in window space -- and ask the shell to draw it.
    // task #180: the EventBus must come from THIS page's own store registry, not from the host.
    // The thread column is a separate Kuikly @Page, so a host-supplied instance does not cross the
    // boundary -- the device trace showed `click_abort reason=eventBus_null` for exactly that reason.
    // RaftSharedStoreRegistry is keyed by pager bridge id, so each page gets the live bus it can
    // actually post on (the same path the rest of ThreadPage already uses).
    val pageEventBus: build.raft.app.data.RaftEventBus?
    val pageOriginKey: String
    val pageKey: String
    if (!drawLocally) {
        val localPager = LocalActivity.current.getPager() as Pager
        val eventBusOverride = LocalThreadHeaderMenuEventBus.current
        pageEventBus =
            remember(localPager.pagerId, eventBusOverride) {
                eventBusOverride ?: RaftSharedStoreRegistry.get(Utils.bridgeModule(localPager.pagerId)).eventBus
            }
        // This page's registry key, mirroring what RaftChildPage publishes for it. Verified by
        // reading PagerData: the host's pageData becomes `rawPageData["param"]` and `pageData.params`
        // reads that back, so `instanceId` is present on both sides. The trace logs both keys so any
        // future divergence is a line comparison rather than a silent zero origin.
        pageOriginKey = raftChildPageOriginKey(THREAD_PAGE, LocalActivity.current.pageData.params)
        // Identifies this page to the shell so a menu cannot be attributed to a different thread column.
        pageKey =
            LocalActivity.current.pageData.params
                .optString(RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY)
                .trim()
                .ifEmpty { "thread-panel" }
    } else {
        pageEventBus = null
        pageOriginKey = ""
        pageKey = "thread-panel"
    }
    var openRequestId by remember { mutableStateOf<String?>(null) }
    if (pageEventBus != null) {
        DisposableEffect(pageEventBus, pageKey) {
            val bus = pageEventBus
            val subscription =
                bus.observe(RAFT_SHELL_OVERLAY_RESULT_EVENT) { eventData ->
                    if (!raftShouldApplyShellOverlayResult(openRequestId, eventData)) return@observe
                    openRequestId = null
                    val selectedId = eventData?.optString(SHELL_OVERLAY_SELECTED_ID).orEmpty()
                    when (selectedId) {
                        FOLLOW_THREAD_MENU_ITEM_ID -> latestOnToggleFollow?.let { latestOnMenuItemSelected?.invoke(selectedId); it() }
                        MARK_THREAD_UNREAD_MENU_ITEM_ID -> latestOnMarkUnread?.let { latestOnMenuItemSelected?.invoke(selectedId); it() }
                        SEARCH_IN_TASK_MENU_ITEM_ID -> latestOnSearchInTask?.let { latestOnMenuItemSelected?.invoke(selectedId); it() }
                        THREAD_TIMELINE_MENU_ITEM_ID -> latestOnTimeline?.let { latestOnMenuItemSelected?.invoke(selectedId); it() }
                        VIEW_TASK_IN_CHANNEL_MENU_ITEM_ID -> latestOnViewInChannel?.let { latestOnMenuItemSelected?.invoke(selectedId); it() }
                        DELETE_TASK_MENU_ITEM_ID -> latestOnDeleteTask?.invoke()
                        NOTIFICATION_LEVEL_MENU_ITEM_ID -> notificationLevelDialogOpen = true
                        else -> latestFoldedActions.firstOrNull { it.id == selectedId }?.onClick?.invoke()
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
    }
    ThreadHeaderCompactAction(
        icon = RaftLucideIconName.Ellipsis,
        onClick = onClick@{
            // task #180 diagnostics (Argus): unconditional entry log. The previous device run
            // produced ZERO trace lines, which could not distinguish "the click never reached this
            // handler" from "the lines went somewhere else". This line fires before any guard.
            raftTraceToPlatformLog("SlockShellOverlay", "click_enter")
            if (drawLocally) {
                anchorRef.resolve(RaftPopoverLocalPoint.BottomEnd)?.let {
                    if (localMenuAnchor == null) latestOnMenuOpen?.invoke()
                    localMenuAnchor = it
                }
                return@onClick
            }
            val bus = pageEventBus ?: return@onClick
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
            latestOnMenuOpen?.invoke()
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
                                iconName = it.icon?.name,
                                enabled = it.enabled,
                                dividerBefore = it.dividerBefore,
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
                    FOLLOW_THREAD_MENU_ITEM_ID -> latestOnToggleFollow?.let { latestOnMenuItemSelected?.invoke(id); it() }
                    MARK_THREAD_UNREAD_MENU_ITEM_ID -> latestOnMarkUnread?.let { latestOnMenuItemSelected?.invoke(id); it() }
                    SEARCH_IN_TASK_MENU_ITEM_ID -> latestOnSearchInTask?.let { latestOnMenuItemSelected?.invoke(id); it() }
                    THREAD_TIMELINE_MENU_ITEM_ID -> latestOnTimeline?.let { latestOnMenuItemSelected?.invoke(id); it() }
                    VIEW_TASK_IN_CHANNEL_MENU_ITEM_ID -> latestOnViewInChannel?.let { latestOnMenuItemSelected?.invoke(id); it() }
                    DELETE_TASK_MENU_ITEM_ID -> latestOnDeleteTask?.invoke()
                    NOTIFICATION_LEVEL_MENU_ITEM_ID -> notificationLevelDialogOpen = true
                    else -> latestFoldedActions.firstOrNull { it.id == id }?.onClick?.invoke()
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
        minWidth = actionSize,
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
