package build.raft.app

import build.raft.designsystem.components.RaftSideAction
import build.raft.designsystem.components.RaftSideActionColumnDefaults
import build.raft.designsystem.components.RaftStatusBarBackdropClaim
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.app.resources.*
import build.raft.app.components.RaftIconSpinner
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftTopBarDefaults
import build.raft.designsystem.components.raftCopyable
import build.raft.designsystem.components.rememberRaftSkeletonDelayPassed
import build.raft.designsystem.components.LocalRaftListSkeletonDelayMillis
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftDialogSheet
import build.raft.app.components.RaftSlotIcon
import build.raft.designsystem.gesture.raftKeepsBackSwipe
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftAvatarAttentionLight
import build.raft.designsystem.components.RaftAvatarBadgeBox
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftMentionMarker
import build.raft.designsystem.components.RaftRowTone
import build.raft.designsystem.components.RaftSidebarChannelItem
import build.raft.designsystem.components.RaftWorkspaceButton
import build.raft.designsystem.components.RaftWorkspaceButtonDefaults
import build.raft.designsystem.components.RaftSidebarItem
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftSidebarItemVariant
import build.raft.designsystem.components.raftSidebarItemIconSize
import build.raft.designsystem.components.RaftSidebarItemMarks
import build.raft.designsystem.components.RaftSidebarItemMeta
import build.raft.designsystem.components.RaftQuietUnreadDot
import build.raft.designsystem.components.RaftSidebarSectionAction
import build.raft.designsystem.components.RaftSidebarSectionHeader
import build.raft.designsystem.components.RaftUnreadCount
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.app.components.RaftDesignSystemTheme
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.RowScope
import build.raft.app.base.Utils
import build.raft.app.components.RaftBrutalActionButton
import build.raft.app.components.RaftExternalBridgeBadge
import build.raft.app.components.externalBridgeBadgeVisible
import build.raft.app.components.RaftHashGlobeIcon
import build.raft.app.components.RaftPublicPageGlobe
import build.raft.app.components.channelOnPublicPage
import build.raft.app.components.RaftAppSkeleton
import build.raft.app.components.RaftAppSkeletonVariant
import build.raft.app.components.raftCardSurface
import build.raft.app.data.ChannelRepository
import build.raft.app.data.DataResult
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.EmptyReason
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeServerDrawerConversationSection
import build.raft.app.data.HomeServerDrawerLoadStatus
import build.raft.app.data.HomeServerDrawerNormalizedContent
import build.raft.app.data.HomeServerDrawerNormalizedRow
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.NetworkRaftAnnouncementsRemindersApi
import build.raft.app.data.NetworkRaftHomeApi
import build.raft.app.data.OpenDirectMessageResult
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.SettingsServerSidebarCustomSection
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.SettingsServerSidebarSectionPlacement
import build.raft.app.data.SIDEBAR_SECTION_ITEM_KIND_AGENT
import build.raft.app.data.SIDEBAR_SECTION_ITEM_KIND_CHANNEL
import build.raft.app.data.normalizeSidebarSectionOrder
import build.raft.app.data.RaftSidebarHideEmptySectionsDefault
import build.raft.app.data.ScreenError
import build.raft.app.data.APP_UPDATE_HOME_NOTIFICATION_ID
import build.raft.app.data.SharedHomeDataSource
import build.raft.app.data.KeyValueNotificationPreferenceStore
import build.raft.app.data.PersistentNotificationPreferenceRepository
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.RaftComputersNetworkApi
import build.raft.app.data.RaftAvatarCacheSubject
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.parseRaftHome
import build.raft.app.model.AvatarReference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.model.appAvatarWireValueOrNull
import build.raft.app.model.gravatarAvatarHttpUrlOrNull
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.compat.pager.BridgeManager
import build.raft.app.compat.module.RouterModule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import build.raft.app.data.messageBodyFontSizeOwnerKey
import build.raft.app.data.runCatchingPreservingCancellation
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import build.raft.app.compat.ui.scrollToTop
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import build.raft.designsystem.components.raftDetectDragGesturesAfterHold
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import build.raft.app.components.RaftPullToRefreshBox
import build.raft.app.components.raftHeaderRefreshIndicatorVisible
// #192 trace scope extension: reuse the markdown trace helper for avatar phase
// attribution (process-wide `expect/actual` infrastructure; one `traceMarkdownSection`
// overload set covers both `markdown.*` and `avatar.*` / future `composer.*` /
// `inbox.*` / `tabbar.*` phase names).
import build.raft.app.markdown.traceMarkdownSection
import build.raft.app.markdown.traceMarkdownSectionNonComposable
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import build.raft.app.compat.pager.Page
import kotlinx.coroutines.launch
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IPagerEventObserver
import build.raft.app.compat.pager.Pager
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val RAFT_HOME_PAGE = "compose_home"
private const val HOME_PAGE_DATA_EVENT = "slockPageData"

private val HomeSmallShadowOffset = 2.dp
/** raft-ui `SidebarItem` `gap-2` between the icon box and the name. */
private val SidebarActionLabelGap = 8.dp
private val ChannelSymbolWidth = 18.dp
/** Web sidebar avatar `size-[19px]`: fills the raft-ui SidebarItem leading slot. */
private val ChannelAvatarSize = 19.dp
private val ChannelAvatarFontSize = 11.sp
/** raft-ui phone `SidebarItem`: `max-md:min-h-11` (44px), what a loading skeleton row stands in for. */
private val HomeSidebarSkeletonRowHeight = 44.dp
/** raft-ui `SidebarItem` `pl-2` inside its 1.5dp edge. */
private val HomeSidebarSkeletonRowStartPadding = 9.5.dp
/** `SidebarItemMetaIcon`: 14dp after the name and in the aside. */
private val HomeSidebarMetaIconSize = 14.dp
private const val EdgeAutoScrollStepPx = 18f
private val ChannelRowShadowOffset = 2.dp
/** Web `SIDEBAR_ROW_TRAILING_CLASS` `max-w-[58%]`. */
private const val DirectMessageTrailingMaxWidthFraction = 0.58f
private val HomeListHorizontalPadding = RaftAppSpacing.Md
// Web phone sidebar: the list is `px-2` and section headers add their own `px-2`, while rows
// add only `pl-2` inside their highlight. Rows therefore sit 8dp from the edge so their icon
// lines up under the section chevron (16dp), not 8dp past it.
private val HomeListRowHorizontalPadding = RaftAppSpacing.Sm
// Web phone Sidebar.tsx scroller `pt-1` (the Home head stays on screen, so not `pt-2`).
private val HomeListTopPadding = RaftAppSpacing.Xs
private val HomeListTopPaddingUnderTopBar = RaftAppSpacing.Sm
// Web `SidebarSectionDescription` `py-1.5`.
private val HomeSidebarSectionEmptyHintPaddingVertical = 6.dp
// #372 React parity: the post-sign-in loading state shows skeleton rows
// under the CHANNELS / DIRECT MESSAGES section headers (4 + 3 rows in the
// React app) instead of a bare shell.
private const val HomeLoadingChannelSkeletonRows = 4
private const val HomeLoadingDirectMessageSkeletonRows = 3
private const val HomeLoadingSkeletonLineWeight = 0.6f
private val HomeLoadingSkeletonLineHeight = 12.dp
private val HomePanelShadowOffset = 4.dp
private val HomeServerSwitcherTopGap = 4.dp
private val HomeServerSwitcherRowMinHeight = 52.dp
private val HomeServerSwitcherDragHandleWidth = 32.dp
internal val HomeServerSwitcherDragHandleIconName = RaftLucideIconName.GripVertical
private const val HomeServerSwitcherMaxVisibleRows = 6
internal val HomeServerPublicPageGlobeGap = 4.dp
internal val HomeServerPublicPageGlobeSize = 13.dp
private val RaftSortEdgeScrollZone = 56.dp
private val RaftSortEdgeScrollStep = 10.dp
private const val SharedListMaxBeyondBoundsItemCount = 96
private const val HomeListMinBeyondBoundsItemCount = 6
private const val HomeListMaxBeyondBoundsItemCount = 24
private const val HomeListOverscanViewportFraction = 1f
private const val HomeListRepresentativeRowStepDp = 34f
private const val HomeListServerSwitchRampStep = 3
private const val HomeServerSwitcherMaxBeyondBoundsItemCount = 6
private const val HomeServerDrawerWidthFraction = 0.84f
private const val HomeServerDrawerLandscapeWidthFraction = 0.50f
private val HomeServerDrawerMaxWidth = 360.dp
private val HomeServerDrawerSwipeDismissThreshold = 72.dp
private val HomeServerDrawerHeaderHeight = 56.dp
private val HomeServerDrawerSectionHeaderHeight = 28.dp
private val HomeServerDrawerConversationHorizontalPadding = 16.dp

internal enum class HomeSidebarBadgeTone {
    Loud,
    Quiet
}

internal data class HomeSidebarBadgeProjection(
    val label: String?,
    val tone: HomeSidebarBadgeTone = HomeSidebarBadgeTone.Loud,
    val hasUnread: Boolean = false,
    val hasAttention: Boolean = false,
    /** Unread with no number (marked unread, botiverse/slock #11795): the quiet dot instead of [label]. */
    val markedUnreadDot: Boolean = false
)

internal data class HomeConversationBadgeInput(
    val unreadCount: Int,
    val joined: Boolean = true,
    val muted: Boolean = false,
    val mentionPierced: Boolean = false,
    val followed: Boolean = false,
    /** Marked unread with nothing countable: unread with the quiet dot when there is no count. */
    val markedUnread: Boolean = false
)

internal fun homeConversationMutedIndicatorIcon(muted: Boolean): RaftLucideIconName? =
    if (muted) RaftLucideIconName.BellOff else null

/**
 * The Home list's Search and Activity entry rows show only where the shell's own navigation (the
 * phone bottom bar or the wide rail, [appShellNavCarriesSearchAndActivity]) does not carry them.
 */
internal fun homeSidebarShowsSearchAndActivityRows(searchAndActivityOnShellNav: Boolean): Boolean =
    !searchAndActivityOnShellNav

/** Activity row aside: the pink unread count, or the quiet total when nothing is unread. */
internal data class HomeActivitySidebarProjection(
    val unreadCount: Int,
    val totalLabel: String?
)

@Page(RAFT_HOME_PAGE, supportInLocal = true)
internal class ComposeRaftHomePage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftHomePageContent(raftPageVisibleResumeTick) }
    }
}

@Serializable
data class RaftServerSwitcherItem(
    val id: String = "",
    val name: String = "",
    val slug: String = "",
    val avatarUrl: String? = null,
    /** Legacy broader unread is presentation-only fallback when Activity is unknown. */
    val broaderUnreadCount: Int = 0,
    /** Present means known, including an authoritative zero. */
    val activityUnreadCount: Long? = null,
    val serverPushMuted: Boolean = false,
    /** `GET /api/servers` `publicPageLive`; drives the Globe mark next to the server name. */
    val publicPageLive: Boolean = false,
)

internal data class HomeServerSwitcherBadgePresentation(
    val kind: HomeServerSwitcherBadgeKind,
    val label: String? = null,
    val muted: Boolean = false,
)

internal enum class HomeServerSwitcherBadgeKind {
    None,
    ActivityNumber,
    BroaderUnreadNumber
}

internal fun homeServerSwitcherBadgePresentation(
    server: RaftServerSwitcherItem
): HomeServerSwitcherBadgePresentation {
    val exactLabel = server.activityUnreadCount?.let { count -> formatHomeSidebarBadgeCount(count) }
    val broaderLabel = formatHomeSidebarBadgeCount(server.broaderUnreadCount)
    return when {
        exactLabel != null ->
            HomeServerSwitcherBadgePresentation(
                kind = HomeServerSwitcherBadgeKind.ActivityNumber,
                label = exactLabel,
                muted = server.serverPushMuted
            )
        server.activityUnreadCount == 0L ->
            HomeServerSwitcherBadgePresentation(
                kind = HomeServerSwitcherBadgeKind.None,
                muted = server.serverPushMuted
            )
        broaderLabel != null ->
            HomeServerSwitcherBadgePresentation(
                kind = HomeServerSwitcherBadgeKind.BroaderUnreadNumber,
                label = broaderLabel,
                muted = server.serverPushMuted
            )
        else -> HomeServerSwitcherBadgePresentation(kind = HomeServerSwitcherBadgeKind.None)
    }
}

@Serializable
data class RaftHomeConversationItem(
    val id: String = "",
    val name: String = "",
    val kind: String = "",
    val channelType: String? = null,
    val unreadCount: Int = 0,
    val lastMessageAt: String? = null,
    val lastMessagePreview: String? = null,
    val lastMessageSenderName: String? = null,
    val createdAt: String? = null,
    val description: String? = null,
    val avatarLabel: String = name.take(2).uppercase(),
    val avatarWireValue: String? = null,
    val peerType: String? = null,
    val presenceMemberId: String? = null,
    val hasDraft: Boolean = false,
    val joined: Boolean = true,
    val archived: Boolean = false,
    val activityMuted: Boolean = false,
    val activityMuteSupported: Boolean = false,
    // task #396: external bridge provider ("slack") from `channel.bridge.provider`.
    val bridgeProvider: String? = null,
    /** Channel `guestVisible`; combine with the server's publicPageLive via [channelOnPublicPage]. */
    val guestVisible: Boolean = false,
    /**
     * An unread message here mentions the user (web unread summary `hasMention`, or a followed
     * thread's): the row's yellow @ sticker. Projected from the Home store's row `mentionCount`
     * and [build.raft.app.data.HomeSnapshot.followedThreadUnread].
     */
    val mentioned: Boolean = false,
    /**
     * Followed threads under this channel with new replies (web `threadUnread[channelId].count`):
     * the row's `[thread] N` segment. Projected from the Home store's followed-thread unread when
     * the server enables `thread_unread_badge_v0`; 0 otherwise.
     */
    val followedThreadUnreadCount: Int = 0,
    /**
     * Marked unread with nothing countable (botiverse/slock #11795, [build.raft.app.data.HomeSnapshot.markedUnreadIds]):
     * with no count the row shows the quiet unread dot (web `QuietUnreadDot`); a count wins over it.
     */
    val markedUnread: Boolean = false
)

data class RaftHomeServerDrawerSection(
    val server: RaftServerSwitcherItem,
    val expanded: Boolean = false,
    val status: HomeServerDrawerLoadStatus = HomeServerDrawerLoadStatus.Unloaded,
    val requestGeneration: Long = 0L,
    val errorCode: String? = null,
    val channels: List<RaftHomeConversationItem> = emptyList(),
    val directMessages: List<RaftHomeConversationItem> = emptyList(),
    val channelOrder: List<String> = emptyList(),
    val channelSortMode: String = "manual",
    val dmOrder: List<String> = emptyList(),
    val dmSortMode: String = "manual",
    val pinnedChannelIds: List<String> = emptyList(),
    val pinnedAgentIds: List<String> = emptyList(),
    val pinnedOrder: List<String> = emptyList(),
    val pinnedItems: List<SettingsServerSidebarPinnedItem> = emptyList(),
    val pinnedSortMode: String = "manual",
    val hiddenDmIds: List<String> = emptyList()
)

data class RaftServerDrawerUserHeader(
    val displayName: String,
    val memberId: String? = null,
    val serverId: String? = null,
    val avatarWireValue: String? = null,
    val avatarLabel: String = displayName.take(2).uppercase()
)

data class RaftHomeServerDrawerHostedSection(
    val header: RaftHomeServerDrawerSection,
    val normalizedContent: HomeServerDrawerNormalizedContent,
    val hasCachedConversations: Boolean
)

internal class HomeServerDrawerInteractiveMarkerGate {
    private var claimedGeneration: Long? = null

    fun claim(generation: Long): Boolean {
        if (claimedGeneration == generation) return false
        claimedGeneration = generation
        return true
    }
}

internal sealed interface RaftHomeServerDrawerRow {
    val key: String

    data class ServerHeader(
        val section: RaftHomeServerDrawerSection
    ) : RaftHomeServerDrawerRow {
        override val key: String = "server:${section.server.id}"
    }

    data class SectionHeader(
        val serverId: String,
        val section: RaftHomeConversationSection,
        val count: Int
    ) : RaftHomeServerDrawerRow {
        override val key: String = "section:$serverId:${section.name.lowercase()}"
    }

    data class Conversation(
        val serverId: String,
        val section: RaftHomeConversationSection,
        val item: RaftHomeConversationItem
    ) : RaftHomeServerDrawerRow {
        override val key: String = "conversation:$serverId:${section.name.lowercase()}:${item.id}"
    }

    data class Skeleton(
        val serverId: String,
        val index: Int
    ) : RaftHomeServerDrawerRow {
        override val key: String = "skeleton:$serverId:$index"
    }

    data class Error(
        val serverId: String,
        val code: String,
        val hasCache: Boolean
    ) : RaftHomeServerDrawerRow {
        override val key: String = "error:$serverId"
    }
}

@Serializable
data class RaftHomeNotificationItem(
    val id: String = "",
    val title: String = "",
    val body: String = "",
    val actionLabel: String = "",
    val primaryActionActive: Boolean = false,
    val targetId: String? = null,
    val kind: RaftHomeNotificationKind = RaftHomeNotificationKind.Warning,
    val icon: RaftHomeNotificationIcon = RaftHomeNotificationIcon.Default
)

@Serializable
enum class RaftHomeNotificationKind { Warning, Info, Error }

@Serializable
enum class RaftHomeNotificationIcon { Default, WifiOff, CircleArrowUp, Info }

data class RaftHomeInlineError(
    val title: String,
    val detail: String,
    val code: String,
    val actionLabel: String = "",
)

internal data class RaftHomeInlineErrorVisibleCopy(
    val title: String,
    val detail: String,
    val actionLabel: String
)

internal fun RaftHomeInlineError.toVisibleCopy(): RaftHomeInlineErrorVisibleCopy =
    RaftHomeInlineErrorVisibleCopy(
        title = title,
        detail = detail,
        actionLabel = actionLabel
    )

internal fun RaftHomeServerDrawerRow.Error.visibleRetryLabel(localizedRetryLabel: String): String =
    localizedRetryLabel

@Serializable
enum class RaftAppPresence { Online, Offline, Unknown }

enum class RaftHomeConversationSection {
    Channel,
    JointChannel,
    DirectMessage,
    Pinned
}

enum class RaftHomeSidebarTopSelection {
    Search,
    Activity,
    Saved
}

@Serializable
enum class RaftSidebarSortMode(val wireValue: String) {
    Manual("manual"),
    Recent("recent"),
    Az("az");

    companion object {
        fun fromWireValue(value: String?): RaftSidebarSortMode =
            entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() } ?: Manual
    }
}

@Serializable
data class RaftHomeShellLabels(
    val search: String = "Search",
    val inbox: String = "Activity",
    val followedThreads: String = "Followed threads",
    val saved: String = "Saved",
    val channels: String = "Channels",
    val jointChannels: String = "Joint Channels",
    val directMessages: String = "Direct Messages",
    val pinned: String = "Pinned",
    val pin: String = "Pin",
    val unpin: String = "Unpin",
    val markAsRead: String = "Mark as Read",
    val markAsUnread: String = "Mark as Unread",
    val markAllAsRead: String = "Mark All as Read",
    val mute: String = "Mute",
    val unmute: String = "Unmute",
    val closeChat: String = "Close Chat",
    val archive: String = "Archive",
    val leaveChannel: String = "Leave Channel",
    val sortChannels: String = "Sort",
    val saveSort: String = "Save",
    val sortManual: String = "Manual",
    val sortRecent: String = "Recent",
    val sortAz: String = "A-Z",
    // task #512: custom sidebar sections (web channel-sections port).
    val moveToSection: String = "Move to section",
    /** Move-to-section target for the pinned section (web `layout.sidebar.pinned`). */
    val pinnedTarget: String = "Pinned",
    val sortMenuTitle: String = "Sort",
    val displaySection: String = "Display",
    val hideEmptySections: String = "Hide empty sections",
    val createChannel: String = "Create channel",
    val sectionEmpty: String = "No conversations",
    val pinnedSectionEmpty: String = "Long-press a channel or DM to pin it",
    val jointChannelsEmpty: String = "No joint channels yet",
    val removeFromSection: String = "Remove from section",
    val newSectionMenu: String = "New section…",
    val renameSection: String = "Rename / change icon…",
    val deleteSection: String = "Delete section",
    val newSectionTitle: String = "New section",
    val editSectionTitle: String = "Edit section",
    val createSection: String = "Create section",
    val sectionNameLabel: String = "Name",
    val sectionNamePlaceholder: String = "My Project",
    val addChannel: String = "Add channel",
    val createJointChannel: String = "Create Joint Channel",
    val noServersAvailable: String = "No servers available",
    val joinChineseCommunityServer: String = "Join Chinese Community",
    val joinCommunityServer: String = "Join community server",
    val switchOrCreateServer: String = "Switch or create server",
    val inviteHuman: String = "Invite Human"
) {
    companion object {
        @Composable
        fun localized(): RaftHomeShellLabels = RaftHomeShellLabels(
            search = stringResource(Res.string.home_nav_search),
            inbox = stringResource(Res.string.home_nav_activity),
            followedThreads = stringResource(Res.string.home_nav_followed_threads),
            saved = stringResource(Res.string.home_nav_saved),
            channels = stringResource(Res.string.home_section_channels),
            jointChannels = stringResource(Res.string.home_section_joint_channels),
            directMessages = stringResource(Res.string.home_section_direct_messages),
            pinned = stringResource(Res.string.home_section_pinned),
            pin = stringResource(Res.string.home_action_pin),
            unpin = stringResource(Res.string.home_action_unpin),
            markAsRead = stringResource(Res.string.home_action_mark_as_read),
            markAsUnread = stringResource(Res.string.home_action_mark_as_unread),
            markAllAsRead = stringResource(Res.string.home_action_mark_all_as_read),
            mute = stringResource(Res.string.home_action_mute),
            unmute = stringResource(Res.string.home_action_unmute),
            closeChat = stringResource(Res.string.home_action_close_chat),
            archive = stringResource(Res.string.home_action_archive),
            leaveChannel = stringResource(Res.string.channel_settings_leave),
            sortChannels = stringResource(Res.string.home_action_sort),
            saveSort = stringResource(Res.string.common_save),
            sortManual = stringResource(Res.string.home_sort_manual),
            sortRecent = stringResource(Res.string.home_sort_recent),
            sortAz = stringResource(Res.string.home_sort_az),
            moveToSection = stringResource(Res.string.home_section_move_to),
            pinnedTarget = stringResource(Res.string.home_section_move_to_pinned),
            sortMenuTitle = stringResource(Res.string.home_section_menu_sort_title),
            displaySection = stringResource(Res.string.home_section_menu_display_title),
            hideEmptySections = stringResource(Res.string.home_section_hide_empty),
            createChannel = stringResource(Res.string.home_section_create_channel),
            sectionEmpty = stringResource(Res.string.home_section_empty),
            pinnedSectionEmpty = stringResource(Res.string.home_section_pinned_empty),
            jointChannelsEmpty = stringResource(Res.string.home_section_joint_empty),
            removeFromSection = stringResource(Res.string.home_section_remove_from),
            newSectionMenu = stringResource(Res.string.home_section_new_menu),
            renameSection = stringResource(Res.string.home_section_rename),
            deleteSection = stringResource(Res.string.home_section_delete),
            newSectionTitle = stringResource(Res.string.home_section_new_title),
            editSectionTitle = stringResource(Res.string.home_section_edit_title),
            createSection = stringResource(Res.string.home_section_create),
            sectionNameLabel = stringResource(Res.string.home_section_name_label),
            sectionNamePlaceholder = stringResource(Res.string.home_section_name_placeholder),
            addChannel = stringResource(Res.string.home_action_add_channel),
            createJointChannel = stringResource(Res.string.home_section_create_joint_channel),
            noServersAvailable = stringResource(Res.string.home_state_no_servers),
            joinChineseCommunityServer = stringResource(Res.string.home_server_switcher_join_chinese_community),
            joinCommunityServer = stringResource(Res.string.home_server_switcher_join_community),
            switchOrCreateServer = stringResource(Res.string.home_server_switcher_switch_or_create),
            inviteHuman = stringResource(Res.string.home_server_switcher_invite_human)
        )
    }
}

@Serializable
internal data class RaftHomePayload(
    val instanceId: String = "",
    val currentServerName: String = "Raft Android",
    val currentServerId: String = "",
    val servers: List<RaftServerSwitcherItem> = emptyList(),
    val channels: List<RaftHomeConversationItem> = emptyList(),
    val directMessages: List<RaftHomeConversationItem> = emptyList(),
    val selectedChannelId: String? = null,
    val inboxUnreadCount: Int = 0,
    val savedCount: Int = 0,
    val notifications: List<RaftHomeNotificationItem> = emptyList(),
    val serverSwitcherExpanded: Boolean = false,
    val contentRefreshing: Boolean = false,
    val contentRefreshEnabled: Boolean = true,
    val applyTopSafeArea: Boolean = true,
    val activitySelected: Boolean = false,
    val channelOrder: List<String> = emptyList(),
    val channelSortMode: String = "manual",
    val dmOrder: List<String> = emptyList(),
    val dmSortMode: String = "manual",
    val pinnedChannelIds: List<String> = emptyList(),
    val pinnedAgentIds: List<String> = emptyList(),
    val pinnedOrder: List<String> = emptyList(),
    val pinned: List<SettingsServerSidebarPinnedItem> = emptyList(),
    val pinnedSortMode: String = "manual",
    val hiddenDmIds: List<String> = emptyList(),
    val canArchiveChannels: Boolean = false,
    val bottomContentPaddingDp: Float = 24f,
    val labels: RaftHomeShellLabels = RaftHomeShellLabels()
)

private val homeConversationOpenBarrier = HomeConversationOpenBarrier()

private const val HomePullToRefreshEnabled = true

internal fun homePullToRefreshEnabled(contentRefreshEnabled: Boolean): Boolean =
    contentRefreshEnabled && HomePullToRefreshEnabled

/** artin task #22: during a user pull the pull indicator is the only refresh chrome. */
internal fun homeHeaderRefreshIndicatorVisible(contentRefreshing: Boolean, pullRefreshing: Boolean): Boolean =
    raftHeaderRefreshIndicatorVisible(refreshing = contentRefreshing, pullRefreshing = pullRefreshing)

@Composable
private fun RaftHomePageContent(routeVisibleTick: Int = 0) {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    var payloadText by remember { mutableStateOf(pager.pageData.params.optString("payload")) }
    var instanceId by remember { mutableStateOf(pager.pageData.params.optString("instanceId")) }
    DisposableEffect(pager) {
        val observer =
            object : IPagerEventObserver {
                override fun onPagerEvent(pagerEvent: String, eventData: JSONObject) {
                    if (pagerEvent != HOME_PAGE_DATA_EVENT) return
                    eventData.optString("payload").takeIf { it.isNotBlank() }?.let {
                        payloadText = it
                    }
                    eventData.optString("instanceId").takeIf { it.isNotBlank() }?.let {
                        instanceId = it
                    }
                }
            }
        pager.addPagerEventObserver(observer)
        onDispose { pager.removePagerEventObserver(observer) }
    }
    val payloadTrace = rememberHomePayloadTrace(
        payloadText,
        instanceId
    )
    val payload = payloadTrace.payload
    LaunchedEffect(payloadText, instanceId) {
        bridge.log(
            "home_launch_payload_parse source=pageData result=${payloadTrace.result} " +
                "responseBytes=${payloadTrace.responseBytes} durationMs=${payloadTrace.durationMs} dispatcher=caller"
        )
    }
    LaunchedEffect(payload.instanceId, payload.channels.size, payload.directMessages.size) {
        bridge.log("home_render instanceId=${payload.instanceId} channels=${payload.channels.size} dms=${payload.directMessages.size}")
    }
    val sharedStores = remember { RaftSharedStoreRegistry.get(bridge) }
    val notificationPreferenceRepository =
        remember {
            PersistentNotificationPreferenceRepository(
                KeyValueNotificationPreferenceStore(raftSharedPreferenceStore())
            )
        }
    val standaloneHomeStore =
        remember(sharedStores, notificationPreferenceRepository) {
            val homeDataSource =
                SharedHomeDataSource(
                    sessionStore = sharedStores.authStore,
                    api = NetworkRaftHomeApi(sharedStores.networkClient),
                    announcementsApi = NetworkRaftAnnouncementsRemindersApi(sharedStores.networkClient),
                    computersApi = RaftComputersNetworkApi(sharedStores.networkClient),
                    notificationPreferenceRepository = notificationPreferenceRepository,
                    serverMembersStore = sharedStores.serverMembersStore,
                    parseHome = { networkPayload, session ->
                        parseRaftHome(networkPayload, session) { message -> bridge.log(message) }
                    },
                    diagnosticsLogger = { message -> bridge.log("standalone_home $message") }
                )
            sharedStores.getOrCreateHomeStore(
                dataSource = homeDataSource,
                diagnosticsLogger = { message -> bridge.log("standalone_home $message") }
            )
        }
    LaunchedEffect(routeVisibleTick, payload.instanceId) {
        if (
            activityPageDidAppearShouldRefresh(routeVisibleTick) &&
            payload.instanceId.isNotBlank()
        ) {
            standaloneHomeStore.dispatch(SharedHomeIntent.ForegroundRefresh)
        }
    }
    val agentStatusResolver =
        remember(sharedStores.serverMembersStore, payload.currentServerId) {
            raftScopedAgentStatusResolver(sharedStores.serverMembersStore, payload.currentServerId)
        }
    val memberAvatarResolver =
        remember(sharedStores.serverMembersStore, payload.currentServerId) {
            if (payload.currentServerId.trim().isBlank()) {
                EmptyRaftMemberAvatarResolver
            } else {
                raftMemberAvatarResolver(sharedStores.serverMembersStore, payload.currentServerId)
            }
        }
    val memberAppBotResolver =
        remember(sharedStores.serverMembersStore, payload.currentServerId) {
            raftMemberAppBotResolver(sharedStores.serverMembersStore, payload.currentServerId)
        }
    val serverMembersState by sharedStores.serverMembersStore.state.collectAsState()
    val pinnedMemberProfileResolver: (String) -> RaftHomePinnedMemberItem? =
        remember(serverMembersState, payload.currentServerId) {
            { memberId ->
                serverMembersState
                    .memberProfile(ServerId(payload.currentServerId), memberId)
                    ?.toRaftHomePinnedMemberItem()
            }
        }
    val standaloneChannelRepository =
        remember(sharedStores) {
            RealChannelRepository(
                sessionStore = sharedStores.authStore,
                networkClient = sharedStores.networkClient,
                warningLogger = { message -> bridge.log("standalone_home_channel_repository $message") }
            )
        }
    val standaloneScope = rememberCoroutineScope()
    val unableToOpenDirectMessage = stringResource(Res.string.home_error_open_direct_message)
    val failedToOpenDirectMessage = stringResource(Res.string.home_error_open_direct_message_failed)
    val noDirectMessagePermission = stringResource(Res.string.home_error_open_direct_message_permission)
    val errorStrings = localizedErrorPresentationStrings()
    LaunchedEffect(sharedStores.serverMembersStore, payload.currentServerId) {
        val serverId = payload.currentServerId.trim().takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        sharedStores.serverMembersStore.refreshMembers(ServerId(serverId), DataRequestPriority.Background)
    }
    fun unsupportedStandaloneHomeAction(action: String, itemId: String? = null) {
        bridge.log("standalone_home_unsupported_action action=$action itemId=${itemId.orEmpty()}")
    }
    // The emoji picker (section icons) keeps skin tone and frequently used emoji per account,
    // keyed like the thread's Add Reaction picker (sindresorhus, #3963).
    val emojiPickerOwnerKey by produceState<String?>(null, sharedStores) {
        value =
            runCatchingPreservingCancellation { sharedStores.authStore.readSession() }.getOrNull()?.user?.id?.let { accountId ->
                messageBodyFontSizeOwnerKey(sharedStores.apiEnvironmentStore.current().apiBaseUrl, accountId)
            }
    }
    CompositionLocalProvider(LocalRaftEmojiPickerOwnerKey provides emojiPickerOwnerKey) {
    RaftStructuralHomeShell(
        currentServerName = payload.currentServerName,
        currentServerId = payload.currentServerId,
        servers = payload.servers,
        channels = payload.channels,
        directMessages = payload.directMessages,
        selectedChannelId = payload.selectedChannelId,
        inboxUnreadCount = payload.inboxUnreadCount,
        savedCount = payload.savedCount,
        notifications = payload.notifications,
        contentRefreshing = payload.contentRefreshing,
        contentRefreshEnabled = payload.contentRefreshEnabled,
        serverSwitcherExpanded = payload.serverSwitcherExpanded,
        applyTopSafeArea = payload.applyTopSafeArea,
        activitySelected = payload.activitySelected,
        channelOrder = payload.channelOrder,
        channelSortMode = payload.channelSortMode,
        dmOrder = payload.dmOrder,
        dmSortMode = payload.dmSortMode,
        pinnedChannelIds = payload.pinnedChannelIds,
        pinnedAgentIds = payload.pinnedAgentIds,
        pinnedOrder = payload.pinnedOrder,
        pinnedItems = payload.pinned,
        pinnedSortMode = payload.pinnedSortMode,
        hiddenDmIds = payload.hiddenDmIds,
        canArchiveChannels = payload.canArchiveChannels,
        showBottomBar = false,
        bottomContentPadding = payload.bottomContentPaddingDp.dp,
        outerPadding = 0.dp,
        labels = RaftHomeShellLabels.localized(),
        onServerBadgeClick = { unsupportedStandaloneHomeAction("toggleServerSwitcher") },
        onServerSwitcherDismiss = { unsupportedStandaloneHomeAction("dismissServerSwitcher") },
        onServerSelected = { unsupportedStandaloneHomeAction("selectServer", it) },
        onServerOrderChange = { order -> standaloneHomeStore.dispatch(SharedHomeIntent.ReorderServers(order)) },
        onCreateServerClick = { unsupportedStandaloneHomeAction("createServer") },
        onCreateChannelClick = { unsupportedStandaloneHomeAction("createChannel") },
        onInboxClick = { unsupportedStandaloneHomeAction("openInbox") },
        onFollowedThreadsClick = { unsupportedStandaloneHomeAction("openFollowedThreads") },
        onSavedClick = { unsupportedStandaloneHomeAction("openSaved") },
        onSearchClick = { unsupportedStandaloneHomeAction("openSearch") },
        onNotificationView = { unsupportedStandaloneHomeAction("viewNotification", it.id) },
        onNotificationDismiss = { unsupportedStandaloneHomeAction("dismissNotification", it.id) },
        onRefresh = { standaloneHomeStore.dispatch(SharedHomeIntent.Refresh) },
        onChannelSortModeChange = { unsupportedStandaloneHomeAction("setChannelSortMode", it.wireValue) },
        onSidebarOrderChange = { target, order -> standaloneHomeStore.dispatch(target.toReorderSidebarIntent(order)) },
        onDmSortModeChange = { unsupportedStandaloneHomeAction("setDmSortMode", it.wireValue) },
        onPinnedSortModeChange = { unsupportedStandaloneHomeAction("setPinnedSortMode", it.wireValue) },
        onChannelClick = { conversationId -> openThreadPageFromConversationId(pager, payload, conversationId) },
        onPinnedMemberClick = { identity ->
            standaloneScope.launch {
                when (val result = standaloneChannelRepository.openPinnedMemberDirectMessage(identity)) {
                    is DataResult.Content -> {
                        standaloneHomeStore.dispatch(SharedHomeIntent.Refresh)
                        pager.openThreadPageFromConversation(
                            result.data.conversation,
                            "${payload.instanceId}:${identity.kind.memberKindWireValue()}:${identity.memberId.value}"
                        )
                    }
                    is DataResult.Empty -> bridge.toast(unableToOpenDirectMessage)
                    is DataResult.Error ->
                        bridge.toast(
                            homeDirectMessageOpenFailureMessage(
                                failedToOpenDirectMessage,
                                result.error,
                                errorStrings
                            )
                        )
                    is DataResult.NoPermission -> bridge.toast(noDirectMessagePermission)
                }
            }
        },
        onChannelLongPress = { _, _ -> },
        onPinToggle = { conversationId ->
            unsupportedStandaloneHomeAction("togglePinned", conversationId)
        },
        onReadToggle = { conversationId, markUnread ->
            unsupportedStandaloneHomeAction(if (markUnread) "markUnread" else "markRead", conversationId)
        },
        onChannelActivityMuteToggle = { conversationId, muted ->
            standaloneHomeStore.dispatch(SharedHomeIntent.SetChannelActivityMuted(conversationId, muted))
        },
        onCloseDirectMessage = { conversationId ->
            unsupportedStandaloneHomeAction("closeDm", conversationId)
        },
        onArchiveChannel = { conversationId ->
            unsupportedStandaloneHomeAction("archiveChannel", conversationId)
        },
        onLeaveChannel = { conversationId ->
            unsupportedStandaloneHomeAction("leaveChannel", conversationId)
        },
        agentStatusResolver = agentStatusResolver,
        memberAvatarResolver = memberAvatarResolver,
        memberAppBotResolver = memberAppBotResolver,
        pinnedMemberProfileResolver = pinnedMemberProfileResolver,
        diagnosticsLogger = bridge::log
    )
    }
}

internal fun homeDirectMessageOpenFailureMessage(
    prefix: String,
    error: ScreenError,
    errorStrings: ErrorPresentationStrings
): String =
    "$prefix: ${error.userFacingMessage(errorStrings)}"

/**
 * #228: open the thread page for a conversation that the sidebar / pinned list
 * references by id. Replaces the prior host-bridge action path
 * which went through the host bridge and silently failed to preserve surfaceKind.
 * Direct scoped navigation to `compose_thread` keeps surfaceKind intact
 * so the thread page's `#226` showMembers/showSettings guards and `#227` header label
 * both see the correct kind (Channel / DirectMessage / ReplyThread).
 */
private fun openThreadPageFromConversationId(
    pager: Pager,
    payload: RaftHomePayload,
    conversationId: String
) {
    val launchPayload =
        buildHomeConversationThreadLaunchPayload(payload, conversationId)
            ?: return
    if (!homeConversationOpenBarrier.tryAcquire(launchPayload.instanceId)) return
    pager.openRaftPage(THREAD_PAGE, threadLaunchPageData(launchPayload))
}

private fun Pager.openThreadPageFromConversation(
    conversation: HomeDirectMessageRow,
    instanceIdOverride: String
) {
    val launchPayload =
        buildStandaloneConversationThreadLaunchPayload(
            conversation = conversation.toThreadLaunchConversationIdentity(),
            timeContext = null,
            instanceIdOverride = instanceIdOverride
        ) ?: return
    openRaftPage(THREAD_PAGE, threadLaunchPageData(launchPayload))
}

internal suspend fun ChannelRepository.openPinnedMemberDirectMessage(
    identity: MemberDetailIdentity
): DataResult<OpenDirectMessageResult> =
    when (identity.kind) {
        MemberKind.Agent -> openAgentDirectMessage(identity.memberId)
        MemberKind.Human -> openUserDirectMessage(identity.memberId)
        MemberKind.System -> DataResult.Empty(EmptyReason.NoChannels)
        MemberKind.External -> DataResult.Empty(EmptyReason.NoChannels)
    }

internal class HomeConversationOpenBarrier(
    private val windowMillis: Long = 800L,
    private val nowMillis: () -> Long = { defaultNowMillis() }
) {
    private val lastOpenedAtByKey = mutableMapOf<String, Long>()

    fun tryAcquire(key: String): Boolean {
        val cleanKey = key.trim()
        if (cleanKey.isBlank()) return false
        val now = nowMillis()
        val lastOpenedAt = lastOpenedAtByKey[cleanKey]
        if (lastOpenedAt != null && now >= lastOpenedAt && now - lastOpenedAt < windowMillis) {
            return false
        }
        pruneExpired(now)
        lastOpenedAtByKey[cleanKey] = now
        return true
    }

    private fun pruneExpired(now: Long) {
        val iterator = lastOpenedAtByKey.iterator()
        while (iterator.hasNext()) {
            val openedAt = iterator.next().value
            if (now < openedAt || now - openedAt >= windowMillis) {
                iterator.remove()
            }
        }
    }
}

internal fun buildHomeConversationThreadLaunchPayload(
    payload: RaftHomePayload,
    conversationId: String
): WireThreadLaunchPayload? {
    val conversation =
        payload.channels.firstOrNull { it.id == conversationId }
            ?: payload.directMessages.firstOrNull { it.id == conversationId }
            ?: return null
    return buildStandaloneConversationThreadLaunchPayload(
        conversation = conversation.toThreadLaunchConversationIdentity() ?: return null,
        timeContext = null,
        instanceIdOverride = "${payload.instanceId}:$conversationId"
    )
}

private fun RaftHomeConversationItem.toThreadLaunchConversationIdentity(): ThreadLaunchConversationIdentity? {
    val cleanId = id.takeIf { it.isNotBlank() } ?: return null
    val conversationKind = parseConversationKindOrNull(kind) ?: return null
    val threadId = ThreadId(cleanId)
    val channelId = ChannelId(cleanId)
    return ThreadLaunchConversationIdentity(
        threadId = threadId,
        channelId = channelId,
        channelType =
            when (conversationKind) {
                ConversationKind.Channel -> channelType?.takeIf { it.isNotBlank() } ?: kind
                ConversationKind.Joint -> conversationKind.toWireChannelKindOrNull()
                else -> null
            },
        threadStreamChannelIdHint =
            if (conversationKind == ConversationKind.Thread) {
                channelId
            } else {
                null
            },
        kind = conversationKind,
        dmPeerIdentity =
            if (conversationKind == ConversationKind.DirectMessage) {
                memberDetailIdentityFromWire(
                    memberId = presenceMemberId,
                    kindWireValue = peerType,
                    source = MemberDetailIdentitySource.DirectoryDmPeer
                )
            } else {
                null
            }
    )
}

@Composable
internal fun HomeTitlebarVisualScreen() {
    val compactServer =
        RaftServerSwitcherItem(
            id = "srv_mobile",
            name = "Raft Mobile",
            slug = "raft-mobile",
            avatarUrl = "pixel:robot"
        )
    val designServer =
        RaftServerSwitcherItem(
            id = "srv_design",
            name = "Raft Product Design Workspace",
            slug = "raft-design",
            avatarUrl = "pixel:paint"
        )
    val fallbackServer = designServer.copy(avatarUrl = null)
    Column(
        modifier =
            Modifier
                .width(342.dp)
                .height(560.dp)
                .background(RaftColor.SurfaceWhite)
                .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        HomeTitlebarDesignLabel("SETTING OFF · EXISTING COMPACT BADGE")
        HomeTitlebar(
            serverName = designServer.name,
            hasOtherServerUnread = true,
            notifications = visualHomeNotifications(),
            refreshing = false,
            topSafeArea = 0.dp,
            onServerBadgeClick = {},
            onNotificationToggle = {}
        )
        HomeTitlebarDesignLabel("SETTING ON · SHORT NAME · 4DP CHEVRON GAP")
        HomeTitlebar(
            serverName = compactServer.name,
            serverIdentity = compactServer,
            hasOtherServerUnread = false,
            notifications = visualHomeNotifications(),
            refreshing = false,
            topSafeArea = 0.dp,
            onServerBadgeClick = {},
            onNotificationToggle = {}
        )
        HomeTitlebarDesignLabel("SETTING ON · LONG NAME ELLIPSIS · CHEVRON VISIBLE")
        HomeTitlebar(
            serverName = fallbackServer.name,
            serverIdentity = fallbackServer,
            hasOtherServerUnread = false,
            notifications = visualHomeNotifications(),
            refreshing = false,
            topSafeArea = 0.dp,
            onServerBadgeClick = {},
            onNotificationToggle = {}
        )
        HomeTitlebarDesignLabel("RIGHT PINK DOT = NOTIFICATION UNREAD")
        HomeTitlebarDesignLabel("EXPANDED SWITCHER · SAME IDENTITY TOKEN")
        HardShadowPanel(
            modifier = Modifier.padding(horizontal = RaftAppSpacing.Md),
            minWidth = 276.dp,
            maxWidth = 328.dp
        ) {
            ServerSwitcherRow(
                server = designServer,
                selected = true,
                onClick = {}
            )
            ServerSwitcherRow(
                server =
                    RaftServerSwitcherItem(
                        id = "srv_fallback",
                        name = "Raft Community",
                        slug = "raft-community"
                    ),
                selected = false,
                onClick = {}
            )
        }
    }
}

@Composable
internal fun HomeTitlebarPreferenceVisualScreen(enabled: Boolean) {
    val server =
        RaftServerSwitcherItem(
            id = "srv_persisted",
            name = "Raft Product Design Workspace",
            slug = "raft-design",
            avatarUrl = "pixel:paint"
        )
    RaftStructuralHomeShell(
        currentServerName = server.name,
        currentServerId = server.id,
        servers = listOf(server),
        homeServerAvatarEnabled = enabled,
        applyTopSafeArea = false,
        showBottomBar = false,
        outerPadding = 0.dp
    )
}

@Composable
internal fun HomeTitlebarServerSwitchVisualScreen() {
    val servers =
        remember {
            listOf(
                RaftServerSwitcherItem(
                    id = "server-a",
                    name = "Workspace A",
                    slug = "workspace-a",
                    avatarUrl = "pixel:paint"
                ),
                RaftServerSwitcherItem(
                    id = "server-b",
                    name = "Workspace B after switch",
                    slug = "workspace-b"
                )
            )
        }
    var currentServerId by remember { mutableStateOf(servers.first().id) }
    LaunchedEffect(Unit) {
        delay(700)
        currentServerId = servers.last().id
    }
    val currentServer = servers.first { server -> server.id == currentServerId }
    RaftStructuralHomeShell(
        currentServerName = currentServer.name,
        currentServerId = currentServer.id,
        servers = servers,
        homeServerAvatarEnabled = true,
        serverSwitcherExpanded = true,
        applyTopSafeArea = false,
        showBottomBar = false,
        outerPadding = 0.dp
    )
}

@Composable
internal fun HomeTitlebarLoadingVisualScreen() {
    val server =
        RaftServerSwitcherItem(
            id = "server-loading",
            name = "Loading workspace",
            slug = "loading-workspace"
        )
    // A capture: the skeleton rows show in the first frame, not after the 1s list delay.
    CompositionLocalProvider(LocalRaftListSkeletonDelayMillis provides 0L) {
        RaftStructuralHomeShell(
            currentServerName = server.name,
            currentServerId = server.id,
            servers = listOf(server),
            homeServerAvatarEnabled = true,
            showLoadingSkeletons = true,
            applyTopSafeArea = false,
            showBottomBar = false,
            outerPadding = 0.dp
        )
    }
}

@Composable
internal fun HomeTitlebarErrorVisualScreen() {
    val server =
        RaftServerSwitcherItem(
            id = "server-error",
            name = "Workspace with removed avatar",
            slug = "workspace-error"
        )
    RaftStructuralHomeShell(
        currentServerName = server.name,
        currentServerId = server.id,
        servers = listOf(server),
        homeServerAvatarEnabled = true,
        inlineError =
            RaftHomeInlineError(
                title = "Could not refresh Home",
                detail = "The last workspace identity remains available while content retries.",
                code = "home_network",
                actionLabel = "Retry"
            ),
        applyTopSafeArea = false,
        showBottomBar = false,
        outerPadding = 0.dp
    )
}

@Composable
private fun HomeTitlebarDesignLabel(text: String) {
    Text(
        text = text,
        style = RaftType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp),
        color = RaftColor.TextMutedGray,
        modifier = Modifier.padding(horizontal = RaftAppSpacing.Md)
    )
}

@Composable
internal fun HomeTitlebarResizeVisualScreen() {
    var useLongServerName by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(300)
        useLongServerName = true
        delay(300)
        useLongServerName = false
        delay(300)
        useLongServerName = true
    }
    Box(modifier = Modifier.width(342.dp).height(110.dp).background(RaftColor.SurfaceWhite).padding(top = 48.dp)) {
        HomeTitlebar(
            serverName = if (useLongServerName) "Raft Android Workspace" else "Raft",
            hasOtherServerUnread = true,
            notifications = emptyList(),
            refreshing = false,
            topSafeArea = 0.dp,
            onServerBadgeClick = { useLongServerName = !useLongServerName },
            onNotificationToggle = {}
        )
    }
}

@Composable
internal fun HomeNotificationCenterVisualScreen() {
    // Fill the full 390x844 viewport crop with SurfaceWhite so the pager's default
    // surface does not bleed a cream band below/right of the popover. Fixes
    // visual-drift #767.
    // The phone popover: 8dp from both screen edges (raft-ui #737).
    Box(modifier = Modifier.width(390.dp).height(844.dp).background(RaftColor.SurfaceWhite).padding(top = 48.dp)) {
        HomeNotificationPopover(
            notifications = visualHomeNotifications(),
            onView = {},
            onDismiss = {},
            modifier = Modifier.align(Alignment.TopEnd).homeNotificationPopoverPhoneWidth(),
            minWidth = 0.dp,
            maxWidth = HomeNotificationPopoverPhoneMaxWidth
        )
    }
}

@Composable
internal fun HomeAppUpdateBannerVisualScreen() {
    Column(modifier = Modifier.width(390.dp).height(844.dp).background(RaftColor.SurfaceWhite).padding(top = 48.dp)) {
        HomeTitlebar(
            serverName = "Raft Design",
            hasOtherServerUnread = false,
            notifications = emptyList(),
            refreshing = false,
            topSafeArea = 0.dp,
            onServerBadgeClick = {},
            onNotificationToggle = {}
        )
        HomeAppUpdateBanner(
            notification =
                RaftHomeNotificationItem(
                    id = APP_UPDATE_HOME_NOTIFICATION_ID,
                    title = "Raft 1.6.0 is available",
                    body = "See what's new, then download and install the update.",
                    actionLabel = "View",
                    kind = RaftHomeNotificationKind.Info,
                    icon = RaftHomeNotificationIcon.CircleArrowUp
                ),
            onClick = {}
        )
        Box(modifier = Modifier.fillMaxSize().background(RaftColor.AppBgLightBlue).padding(16.dp)) {
            Text(
                text = "HOME",
                style = RaftType.Caption,
                color = RaftColor.TextMutedGray
            )
        }
    }
}

@Composable
internal fun HomeNotificationTriggerStatesVisualScreen() {
    // Native-only evidence capture (no React baseline): renders the Bell
    // trigger in its independent states — empty-closed, unread-closed,
    // unread-open, a narrow-screen variant, and the empty drawer — so pixel
    // review can verify the web-mobile parity contract (task #692 / PR #5605)
    // without relying on server-driven live notification state.
    val designServer =
        RaftServerSwitcherItem(
            id = "srv_design",
            name = "Raft Design",
            slug = "raft-design",
            avatarUrl = "pixel:paint"
        )
    Column(
        modifier =
            Modifier
                .width(342.dp)
                .background(RaftColor.SurfaceWhite)
                .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        HomeTitlebarDesignLabel("EMPTY · CLOSED")
        HomeTitlebar(
            serverName = designServer.name,
            hasOtherServerUnread = false,
            notifications = emptyList(),
            notificationCenterExpanded = false,
            refreshing = false,
            topSafeArea = 0.dp,
            onServerBadgeClick = {},
            onNotificationToggle = {}
        )
        HomeTitlebarDesignLabel("UNREAD · CLOSED")
        HomeTitlebar(
            serverName = designServer.name,
            hasOtherServerUnread = false,
            notifications = visualHomeNotifications(),
            notificationCenterExpanded = false,
            refreshing = false,
            topSafeArea = 0.dp,
            onServerBadgeClick = {},
            onNotificationToggle = {}
        )
        HomeTitlebarDesignLabel("UNREAD · OPEN")
        HomeTitlebar(
            serverName = designServer.name,
            hasOtherServerUnread = false,
            notifications = visualHomeNotifications(),
            notificationCenterExpanded = true,
            refreshing = false,
            topSafeArea = 0.dp,
            onServerBadgeClick = {},
            onNotificationToggle = {}
        )
        HomeTitlebarDesignLabel("NARROW · UNREAD · CLOSED")
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = RaftAppSpacing.Md)) {
            HomeTitlebar(
                serverName = designServer.name,
                hasOtherServerUnread = false,
                notifications = visualHomeNotifications(),
                notificationCenterExpanded = false,
                refreshing = false,
                topSafeArea = 0.dp,
                onServerBadgeClick = {},
                onNotificationToggle = {}
            )
        }
        HomeTitlebarDesignLabel("EMPTY DRAWER")
        HomeNotificationPopover(
            notifications = emptyList(),
            onView = {},
            onDismiss = {},
            modifier = Modifier.padding(bottom = RaftAppSpacing.Md)
        )
    }
}

private fun visualHomeNotifications(): List<RaftHomeNotificationItem> =
    listOf(
        RaftHomeNotificationItem(
            id = "visual-agent-offline",
            title = "Android-Developer lost connection",
            body = "Last heartbeat was 4 minutes ago.",
            actionLabel = "View",
            kind = RaftHomeNotificationKind.Warning
        ),
        RaftHomeNotificationItem(
            id = "visual-release-blocker",
            title = "Release gate needs review",
            body = "Two visual cases changed in the latest run.",
            actionLabel = "Open",
            kind = RaftHomeNotificationKind.Info
        )
    )

@Composable
private fun rememberHomePayloadTrace(payload: String, instanceId: String): HomePayloadParseTrace =
    remember(payload, instanceId) {
        val startedAt = TimeSource.Monotonic.markNow()
        val parsed = runCatching {
            Json { ignoreUnknownKeys = true }
                .decodeFromString(RaftHomePayload.serializer(), payload)
                .let { if (it.instanceId.isBlank()) it.copy(instanceId = instanceId) else it }
        }
        HomePayloadParseTrace(
            payload = parsed.getOrElse { RaftHomePayload(instanceId = instanceId) },
            durationMs = startedAt.elapsedNow().inWholeMilliseconds,
            responseBytes = payload.encodeToByteArray().size,
            result = if (parsed.isSuccess) "success" else "failure"
        )
    }

private data class HomePayloadParseTrace(
    val payload: RaftHomePayload,
    val durationMs: Long,
    val responseBytes: Int,
    val result: String
)

/**
 * task #336: state payload of [RaftStructuralHomeShell]. Grouping the shell's data parameters
 * keeps the composable's register frame small so Android 16's dex verifier accepts the
 * un-minified debug build (the 79-parameter flat signature was rejected with a register-type
 * conflict: "register v327 has type Reference ... but expected Boolean").
 */
internal data class RaftStructuralHomeShellData(
    val currentServerName: String = "Raft Android",
    val currentServerId: String = "slock-android",
    val servers: List<RaftServerSwitcherItem> = emptyList(),
    val channels: List<RaftHomeConversationItem> = emptyList(),
    val directMessages: List<RaftHomeConversationItem> = emptyList(),
    val selectedChannelId: String? = null,
    val selectedConversationSection: RaftHomeConversationSection? = null,
    val inboxUnreadCount: Int = 0,
    val inboxTotalCount: Int = 0,
    val savedCount: Int = 0,
    val notifications: List<RaftHomeNotificationItem> = emptyList(),
    val inlineError: RaftHomeInlineError? = null,
    /** Task #164: the unread counts may be old (read warehouse behind); shows the stale notice. */
    val unreadStale: Boolean = false,
    val contentRefreshing: Boolean = false,
    val contentRefreshEnabled: Boolean = true,
    val serverSwitcherExpanded: Boolean = false,
    val serverDrawerEnabled: Boolean = false,
    val homeServerAvatarEnabled: Boolean = false,
    /** Wide layout: the top bar is above the list, so the list has no head (web `hideTitle`). */
    val serverSwitcherInTopBar: Boolean = false,
    val serverDrawerSections: List<RaftHomeServerDrawerSection> = emptyList(),
    val pendingServerId: String? = null,
    val prefersChineseCommunity: Boolean = false,
    val applyTopSafeArea: Boolean = true,
    val topSafeAreaOverride: Dp? = null,
    val activitySelected: Boolean = false,
    val searchSelected: Boolean = false,
    val savedSelected: Boolean = false,
    val channelOrder: List<String> = emptyList(),
    val channelSortMode: String = "manual",
    val jointChannelSortMode: String = "manual",
    val dmOrder: List<String> = emptyList(),
    val dmSortMode: String = "manual",
    val pinnedChannelIds: List<String> = emptyList(),
    val pinnedAgentIds: List<String> = emptyList(),
    val pinnedOrder: List<String> = emptyList(),
    val pinnedItems: List<SettingsServerSidebarPinnedItem> = emptyList(),
    val pinnedSortMode: String = "manual",
    val hiddenDmIds: List<String> = emptyList(),
    // task #512: custom sidebar sections (web channel-sections port).
    val customSections: List<SettingsServerSidebarCustomSection> = emptyList(),
    val sectionOrder: List<String> = emptyList(),
    val sectionPlacements: List<SettingsServerSidebarSectionPlacement> = emptyList(),
    /** Web "Display > Hide Empty Sections" (a local, not server-synced, preference). */
    val hideEmptySections: Boolean = RaftSidebarHideEmptySectionsDefault,
    val canArchiveChannels: Boolean = false,
    val showLoadingSkeletons: Boolean = false,
    val selectedTabKey: String = "home",
    val showBottomBar: Boolean = true,
    /**
     * The shell's navigation carries Search and Activity (the phone bar's tabs, Jasper
     * 2026-10-20; the wide rail's entries, nav spec §5), so the list drops its Search and
     * Activity entry rows and keeps only Saved. False where neither is on screen.
     */
    val searchAndActivityOnShellNav: Boolean = true,
    val externalFocusLossDismissEpoch: Int = 0,
    val outerPadding: Dp = RaftAppSpacing.Md,
    val labels: RaftHomeShellLabels = RaftHomeShellLabels(),
    val scrollToTopRequest: Int = 0,
    val initialListPosition: AppShellLazyListPosition = AppShellLazyListPosition(),
)

/**
 * task #336: callback payload of [RaftStructuralHomeShell]. See [RaftStructuralHomeShellData]
 * for why the flat parameter list had to be grouped.
 */
internal class RaftStructuralHomeShellCallbacks(
    val onServerBadgeClick: () -> Unit = {},
    val onServerSwitcherDismiss: () -> Unit = {},
    val onServerDrawerSectionToggle: (String) -> Unit = {},
    val onServerDrawerRetry: (String) -> Unit = {},
    val onServerSelected: (String) -> Unit = {},
    val onServerOrderChange: (List<String>) -> Unit = {},
    val onCreateServerClick: () -> Unit = {},
    /** Server switcher "Invite Human"; null hides it (web gates on `inviteMembers`). */
    val onInviteHumanClick: (() -> Unit)? = null,
    val onCreateChannelClick: () -> Unit = {},
    /** Joint Channels section "+" (web Sidebar `canCreateJointChannel`); null hides it. */
    val onCreateJointChannelClick: (() -> Unit)? = null,
    val onTabSelected: (String) -> Unit = {},
    val onInboxClick: () -> Unit = {},
    val onFollowedThreadsClick: () -> Unit = {},
    val onSavedClick: () -> Unit = {},
    val onSearchClick: () -> Unit = {},
    val onNotificationView: (RaftHomeNotificationItem) -> Unit = {},
    val onNotificationDismiss: (RaftHomeNotificationItem) -> Unit = {},
    val onInlineErrorAction: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onChannelSortModeChange: (RaftSidebarSortMode) -> Unit = {},
    val onJointChannelSortModeChange: (RaftSidebarSortMode) -> Unit = {},
    val onHideEmptySectionsChange: (Boolean) -> Unit = {},
    val onSidebarOrderChange: (target: RaftSidebarSortTarget, order: List<String>) -> Unit = { _, _ -> },
    val onDmSortModeChange: (RaftSidebarSortMode) -> Unit = {},
    val onPinnedSortModeChange: (RaftSidebarSortMode) -> Unit = {},
    // task #512: custom sidebar sections (web channel-sections port).
    val onSidebarSectionSortModeChange: (sectionId: String, RaftSidebarSortMode) -> Unit = { _, _ -> },
    val onSidebarSectionCreate: (RaftHomeSectionCreateRequest) -> Unit = {},
    val onSidebarSectionRename: (sectionId: String, name: String, emoji: String?) -> Unit = { _, _, _ -> },
    val onSidebarSectionDelete: (sectionId: String) -> Unit = {},
    /** [sectionId] null returns the conversation to its default section. */
    val onMoveConversationToSection: (conversationId: String, sectionId: String?) -> Unit = { _, _ -> },
    val onChannelClick: (String) -> Unit = {},
    val onPinnedMemberClick: (MemberDetailIdentity) -> Unit = {},
    val onChannelLongPress: (String, RaftPopoverAnchor) -> Unit = { _, _ -> },
    val onPinToggle: (String) -> Unit = {},
    val onReadToggle: (conversationId: String, markUnread: Boolean) -> Unit = { _, _ -> },
    /**
     * Sidebar "Mark All as Read" for a joined channel and its followed threads (web
     * `SidebarReadToggleMenuItem` with `includeThreads`). Null while `thread_unread_badge_v0` or
     * `sidebar_mark_all_read_v0` is off: the row keeps the single-scope read toggle.
     */
    val onMarkConversationReadAll: ((conversationId: String) -> Unit)? = null,
    val onChannelActivityMuteToggle: (conversationId: String, muted: Boolean) -> Unit = { _, _ -> },
    val onCloseDirectMessage: (String) -> Unit = {},
    val onArchiveChannel: (String) -> Unit = {},
    /** Row menu "Leave Channel": the host confirms, then leaves (botiverse/slock #11289). */
    val onLeaveChannel: (String) -> Unit = {},
    val agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    val memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    val pinnedMemberProfileResolver: (String) -> RaftHomePinnedMemberItem? = { null },
    val diagnosticsLogger: (String) -> Unit = {},
    val onPinMenuDismiss: () -> Unit = {},
    val onListPositionSnapshot: (String?, AppShellLazyListPosition) -> Unit = { _, _ -> },
)

@Composable
internal fun RaftStructuralHomeShell(
    modifier: Modifier = Modifier,
    currentServerName: String = "Raft Android",
    currentServerId: String = "slock-android",
    servers: List<RaftServerSwitcherItem> = emptyList(),
    channels: List<RaftHomeConversationItem> = emptyList(),
    directMessages: List<RaftHomeConversationItem> = emptyList(),
    selectedChannelId: String? = null,
    selectedConversationSection: RaftHomeConversationSection? = null,
    inboxUnreadCount: Int = 0,
    inboxTotalCount: Int = 0,
    savedCount: Int = 0,
    notifications: List<RaftHomeNotificationItem> = emptyList(),
    inlineError: RaftHomeInlineError? = null,
    unreadStale: Boolean = false,
    contentRefreshing: Boolean = false,
    contentRefreshEnabled: Boolean = true,
    serverSwitcherExpanded: Boolean = false,
    serverDrawerEnabled: Boolean = false,
    homeServerAvatarEnabled: Boolean = false,
    /**
     * Wide layout (artin task #8): the top bar holds the server switcher and the bell, so the list
     * has no head of its own (web MainLayout `hideTitle={showTopBar}`); the bell's state goes to
     * [LocalRaftAppShellTopBar].
     */
    serverSwitcherInTopBar: Boolean = false,
    serverDrawerSections: List<RaftHomeServerDrawerSection> = emptyList(),
    pendingServerId: String? = null,
    prefersChineseCommunity: Boolean = false,
    applyTopSafeArea: Boolean = true,
    topSafeAreaOverride: Dp? = null,
    activitySelected: Boolean = false,
    searchSelected: Boolean = false,
    savedSelected: Boolean = false,
    channelOrder: List<String> = emptyList(),
    channelSortMode: String = "manual",
    jointChannelSortMode: String = "manual",
    dmOrder: List<String> = emptyList(),
    dmSortMode: String = "manual",
    pinnedChannelIds: List<String> = emptyList(),
    pinnedAgentIds: List<String> = emptyList(),
    pinnedOrder: List<String> = emptyList(),
    pinnedItems: List<SettingsServerSidebarPinnedItem> = emptyList(),
    pinnedSortMode: String = "manual",
    hiddenDmIds: List<String> = emptyList(),
    // task #512: custom sidebar sections (web channel-sections port).
    customSections: List<SettingsServerSidebarCustomSection> = emptyList(),
    sectionOrder: List<String> = emptyList(),
    sectionPlacements: List<SettingsServerSidebarSectionPlacement> = emptyList(),
    hideEmptySections: Boolean = RaftSidebarHideEmptySectionsDefault,
    canArchiveChannels: Boolean = false,
    // #372 React parity: while home content is loading (session known,
    // channels / directMessages still empty) render static skeleton rows
    // under the CHANNELS and DIRECT MESSAGES headers instead of a bare shell.
    showLoadingSkeletons: Boolean = false,
    selectedTabKey: String = "home",
    showBottomBar: Boolean = true,
    searchAndActivityOnShellNav: Boolean = showBottomBar,
    externalFocusLossDismissEpoch: Int = 0,
    bottomContentPadding: Dp = if (showBottomBar) RaftAppSize.BottomBarHeight + RaftAppSpacing.Md else RaftAppSpacing.Lg,
    outerPadding: Dp = RaftAppSpacing.Md,
    labels: RaftHomeShellLabels = RaftHomeShellLabels(),
    onServerBadgeClick: () -> Unit = {},
    onServerSwitcherDismiss: () -> Unit = {},
    onServerDrawerSectionToggle: (String) -> Unit = {},
    onServerDrawerRetry: (String) -> Unit = {},
    onServerSelected: (String) -> Unit = {},
    onServerOrderChange: (List<String>) -> Unit = {},
    onCreateServerClick: () -> Unit = {},
    onInviteHumanClick: (() -> Unit)? = null,
    onCreateChannelClick: () -> Unit = {},
    onCreateJointChannelClick: (() -> Unit)? = null,
    onTabSelected: (String) -> Unit = {},
    onInboxClick: () -> Unit = {},
    onFollowedThreadsClick: () -> Unit = {},
    onSavedClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onNotificationView: (RaftHomeNotificationItem) -> Unit = {},
    onNotificationDismiss: (RaftHomeNotificationItem) -> Unit = {},
    onInlineErrorAction: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onChannelSortModeChange: (RaftSidebarSortMode) -> Unit = {},
    onJointChannelSortModeChange: (RaftSidebarSortMode) -> Unit = {},
    onHideEmptySectionsChange: (Boolean) -> Unit = {},
    onSidebarOrderChange: (target: RaftSidebarSortTarget, order: List<String>) -> Unit = { _, _ -> },
    onDmSortModeChange: (RaftSidebarSortMode) -> Unit = {},
    onPinnedSortModeChange: (RaftSidebarSortMode) -> Unit = {},
    // task #512: custom sidebar sections (web channel-sections port).
    onSidebarSectionSortModeChange: (sectionId: String, RaftSidebarSortMode) -> Unit = { _, _ -> },
    onSidebarSectionCreate: (RaftHomeSectionCreateRequest) -> Unit = {},
    onSidebarSectionRename: (sectionId: String, name: String, emoji: String?) -> Unit = { _, _, _ -> },
    onSidebarSectionDelete: (sectionId: String) -> Unit = {},
    onMoveConversationToSection: (conversationId: String, sectionId: String?) -> Unit = { _, _ -> },
    onChannelClick: (String) -> Unit = {},
    onPinnedMemberClick: (MemberDetailIdentity) -> Unit = {},
    onChannelLongPress: (String, RaftPopoverAnchor) -> Unit = { _, _ -> },
    onPinToggle: (String) -> Unit = {},
    onReadToggle: (conversationId: String, markUnread: Boolean) -> Unit = { _, _ -> },
    onMarkConversationReadAll: ((conversationId: String) -> Unit)? = null,
    onChannelActivityMuteToggle: (conversationId: String, muted: Boolean) -> Unit = { _, _ -> },
    onCloseDirectMessage: (String) -> Unit = {},
    onArchiveChannel: (String) -> Unit = {},
    onLeaveChannel: (String) -> Unit = {},
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    // botiverse/slock #11273: which DM peers are App Bots (the Bot badge, no presence or runtime).
    memberAppBotResolver: RaftMemberAppBotResolver = EmptyRaftMemberAppBotResolver,
    pinnedMemberProfileResolver: (String) -> RaftHomePinnedMemberItem? = { null },
    diagnosticsLogger: (String) -> Unit = {},
    onPinMenuDismiss: () -> Unit = {},
    scrollToTopRequest: Int = 0,
    listPositionServerId: String = currentServerId,
    initialListPosition: AppShellLazyListPosition = AppShellLazyListPosition(),
    onListPositionSnapshot: (String?, AppShellLazyListPosition) -> Unit = { _, _ -> }
) {
    // task #336: thin delegation to the payload-based implementation keeps every call site
    // (named arguments) unchanged while the verifier-relevant frame lives on the small
    // RaftStructuralHomeShellContent signature below. The Home list rows and section headers
    // are designsystem sidebar parts, so the shell sits in the designsystem theme (left
    // unindented to keep this delegation's diff small).
    RaftDesignSystemTheme {
    CompositionLocalProvider(LocalRaftMemberAppBotResolver provides memberAppBotResolver) {
    RaftStructuralHomeShellContent(
        modifier = modifier,
        data =
            RaftStructuralHomeShellData(
                currentServerName = currentServerName,
                currentServerId = currentServerId,
                servers = servers,
                channels = channels,
                directMessages = directMessages,
                selectedChannelId = selectedChannelId,
                selectedConversationSection = selectedConversationSection,
                inboxUnreadCount = inboxUnreadCount,
                inboxTotalCount = inboxTotalCount,
                savedCount = savedCount,
                notifications = notifications,
                inlineError = inlineError,
                unreadStale = unreadStale,
                contentRefreshing = contentRefreshing,
                contentRefreshEnabled = contentRefreshEnabled,
                serverSwitcherExpanded = serverSwitcherExpanded,
                serverDrawerEnabled = serverDrawerEnabled,
                homeServerAvatarEnabled = homeServerAvatarEnabled,
                serverSwitcherInTopBar = serverSwitcherInTopBar,
                serverDrawerSections = serverDrawerSections,
                pendingServerId = pendingServerId,
                prefersChineseCommunity = prefersChineseCommunity,
                applyTopSafeArea = applyTopSafeArea,
                topSafeAreaOverride = topSafeAreaOverride,
                activitySelected = activitySelected,
                searchSelected = searchSelected,
                savedSelected = savedSelected,
                channelOrder = channelOrder,
                channelSortMode = channelSortMode,
                jointChannelSortMode = jointChannelSortMode,
                dmOrder = dmOrder,
                dmSortMode = dmSortMode,
                pinnedChannelIds = pinnedChannelIds,
                pinnedAgentIds = pinnedAgentIds,
                pinnedOrder = pinnedOrder,
                pinnedItems = pinnedItems,
                pinnedSortMode = pinnedSortMode,
                hiddenDmIds = hiddenDmIds,
                customSections = customSections,
                sectionOrder = sectionOrder,
                sectionPlacements = sectionPlacements,
                hideEmptySections = hideEmptySections,
                canArchiveChannels = canArchiveChannels,
                showLoadingSkeletons = showLoadingSkeletons,
                selectedTabKey = selectedTabKey,
                showBottomBar = showBottomBar,
                searchAndActivityOnShellNav = searchAndActivityOnShellNav,
                externalFocusLossDismissEpoch = externalFocusLossDismissEpoch,
                outerPadding = outerPadding,
                labels = labels,
                scrollToTopRequest = scrollToTopRequest,
                initialListPosition = initialListPosition,
            ),
        callbacks =
            RaftStructuralHomeShellCallbacks(
                onServerBadgeClick = onServerBadgeClick,
                onServerSwitcherDismiss = onServerSwitcherDismiss,
                onServerDrawerSectionToggle = onServerDrawerSectionToggle,
                onServerDrawerRetry = onServerDrawerRetry,
                onServerSelected = onServerSelected,
                onServerOrderChange = onServerOrderChange,
                onCreateServerClick = onCreateServerClick,
                onInviteHumanClick = onInviteHumanClick,
                onCreateChannelClick = onCreateChannelClick,
                onCreateJointChannelClick = onCreateJointChannelClick,
                onTabSelected = onTabSelected,
                onInboxClick = onInboxClick,
                onFollowedThreadsClick = onFollowedThreadsClick,
                onSavedClick = onSavedClick,
                onSearchClick = onSearchClick,
                onNotificationView = onNotificationView,
                onNotificationDismiss = onNotificationDismiss,
                onInlineErrorAction = onInlineErrorAction,
                onRefresh = onRefresh,
                onChannelSortModeChange = onChannelSortModeChange,
                onJointChannelSortModeChange = onJointChannelSortModeChange,
                onHideEmptySectionsChange = onHideEmptySectionsChange,
                onSidebarOrderChange = onSidebarOrderChange,
                onDmSortModeChange = onDmSortModeChange,
                onPinnedSortModeChange = onPinnedSortModeChange,
                onSidebarSectionSortModeChange = onSidebarSectionSortModeChange,
                onSidebarSectionCreate = onSidebarSectionCreate,
                onSidebarSectionRename = onSidebarSectionRename,
                onSidebarSectionDelete = onSidebarSectionDelete,
                onMoveConversationToSection = onMoveConversationToSection,
                onChannelClick = onChannelClick,
                onPinnedMemberClick = onPinnedMemberClick,
                onChannelLongPress = onChannelLongPress,
                onPinToggle = onPinToggle,
                onReadToggle = onReadToggle,
                onMarkConversationReadAll = onMarkConversationReadAll,
                onChannelActivityMuteToggle = onChannelActivityMuteToggle,
                onCloseDirectMessage = onCloseDirectMessage,
                onArchiveChannel = onArchiveChannel,
                onLeaveChannel = onLeaveChannel,
                agentStatusResolver = agentStatusResolver,
                memberAvatarResolver = memberAvatarResolver,
                pinnedMemberProfileResolver = pinnedMemberProfileResolver,
                diagnosticsLogger = diagnosticsLogger,
                onPinMenuDismiss = onPinMenuDismiss,
                onListPositionSnapshot = onListPositionSnapshot,
            ),
        bottomContentPadding = bottomContentPadding,
        listPositionServerId = listPositionServerId,
    )
    }
    }
}

@Composable
private fun RaftStructuralHomeShellContent(
    modifier: Modifier,
    data: RaftStructuralHomeShellData,
    callbacks: RaftStructuralHomeShellCallbacks,
    bottomContentPadding: Dp,
    listPositionServerId: String,
) {
    val currentServerName = data.currentServerName
    val currentServerId = data.currentServerId
    val servers = data.servers
    val channels = data.channels
    val directMessages = data.directMessages
    val selectedChannelId = data.selectedChannelId
    val selectedConversationSection = data.selectedConversationSection
    val inboxUnreadCount = data.inboxUnreadCount
    val inboxTotalCount = data.inboxTotalCount
    val savedCount = data.savedCount
    val notifications = data.notifications
    val inlineError = data.inlineError
    val unreadStale = data.unreadStale
    val contentRefreshing = data.contentRefreshing
    val contentRefreshEnabled = data.contentRefreshEnabled
    val serverSwitcherExpanded = data.serverSwitcherExpanded
    val serverDrawerEnabled = data.serverDrawerEnabled
    val homeServerAvatarEnabled = data.homeServerAvatarEnabled
    val serverSwitcherInTopBar = data.serverSwitcherInTopBar
    val serverDrawerSections = data.serverDrawerSections
    val pendingServerId = data.pendingServerId
    val prefersChineseCommunity = data.prefersChineseCommunity
    val applyTopSafeArea = data.applyTopSafeArea
    val topSafeAreaOverride = data.topSafeAreaOverride
    val activitySelected = data.activitySelected
    val searchSelected = data.searchSelected
    val savedSelected = data.savedSelected
    val channelOrder = data.channelOrder
    val channelSortMode = data.channelSortMode
    val jointChannelSortMode = data.jointChannelSortMode
    val dmOrder = data.dmOrder
    val dmSortMode = data.dmSortMode
    val pinnedChannelIds = data.pinnedChannelIds
    val pinnedAgentIds = data.pinnedAgentIds
    val pinnedOrder = data.pinnedOrder
    val pinnedItems = data.pinnedItems
    val pinnedSortMode = data.pinnedSortMode
    val hiddenDmIds = data.hiddenDmIds
    val customSections = data.customSections
    val sectionOrder = data.sectionOrder
    val sectionPlacements = data.sectionPlacements
    val hideEmptySections = data.hideEmptySections
    val canArchiveChannels = data.canArchiveChannels
    val showLoadingSkeletons = data.showLoadingSkeletons
    // raft-ui 0.22.1 (#720): list skeleton rows show only after 1s of loading, so a fast load
    // doesn't flash them. The empty hints still wait on [showLoadingSkeletons].
    val loadingSkeletonRowsShown = rememberRaftSkeletonDelayPassed(loading = showLoadingSkeletons)
    val selectedTabKey = data.selectedTabKey
    val showBottomBar = data.showBottomBar
    val searchAndActivityOnShellNav = data.searchAndActivityOnShellNav
    val externalFocusLossDismissEpoch = data.externalFocusLossDismissEpoch
    val outerPadding = data.outerPadding
    val labels = data.labels
    val scrollToTopRequest = data.scrollToTopRequest
    val initialListPosition = data.initialListPosition
    val onServerBadgeClick = callbacks.onServerBadgeClick
    val onServerSwitcherDismiss = callbacks.onServerSwitcherDismiss
    val onServerDrawerSectionToggle = callbacks.onServerDrawerSectionToggle
    val onServerDrawerRetry = callbacks.onServerDrawerRetry
    val onServerSelected = callbacks.onServerSelected
    val onServerOrderChange = callbacks.onServerOrderChange
    val onCreateServerClick = callbacks.onCreateServerClick
    val onInviteHumanClick = callbacks.onInviteHumanClick
    val onCreateChannelClick = callbacks.onCreateChannelClick
    val onCreateJointChannelClick = callbacks.onCreateJointChannelClick
    val onTabSelected = callbacks.onTabSelected
    val onInboxClick = callbacks.onInboxClick
    val onFollowedThreadsClick = callbacks.onFollowedThreadsClick
    val onSavedClick = callbacks.onSavedClick
    val onSearchClick = callbacks.onSearchClick
    val onNotificationView = callbacks.onNotificationView
    val onNotificationDismiss = callbacks.onNotificationDismiss
    val onInlineErrorAction = callbacks.onInlineErrorAction
    val onRefresh = callbacks.onRefresh
    val onChannelSortModeChange = callbacks.onChannelSortModeChange
    val onJointChannelSortModeChange = callbacks.onJointChannelSortModeChange
    val onHideEmptySectionsChange = callbacks.onHideEmptySectionsChange
    val onSidebarOrderChange = callbacks.onSidebarOrderChange
    val onDmSortModeChange = callbacks.onDmSortModeChange
    val onPinnedSortModeChange = callbacks.onPinnedSortModeChange
    val onSidebarSectionSortModeChange = callbacks.onSidebarSectionSortModeChange
    val onSidebarSectionCreate = callbacks.onSidebarSectionCreate
    val onSidebarSectionRename = callbacks.onSidebarSectionRename
    val onSidebarSectionDelete = callbacks.onSidebarSectionDelete
    val onMoveConversationToSection = callbacks.onMoveConversationToSection
    val onChannelClick = callbacks.onChannelClick
    val onPinnedMemberClick = callbacks.onPinnedMemberClick
    val onChannelLongPress = callbacks.onChannelLongPress
    val onPinToggle = callbacks.onPinToggle
    val onReadToggle = callbacks.onReadToggle
    val onMarkConversationReadAll = callbacks.onMarkConversationReadAll
    val onChannelActivityMuteToggle = callbacks.onChannelActivityMuteToggle
    val onCloseDirectMessage = callbacks.onCloseDirectMessage
    val onArchiveChannel = callbacks.onArchiveChannel
    val onLeaveChannel = callbacks.onLeaveChannel
    val agentStatusResolver = callbacks.agentStatusResolver
    val memberAvatarResolver = callbacks.memberAvatarResolver
    val pinnedMemberProfileResolver = callbacks.pinnedMemberProfileResolver
    val diagnosticsLogger = callbacks.diagnosticsLogger
    val onPinMenuDismiss = callbacks.onPinMenuDismiss
    val onListPositionSnapshot = callbacks.onListPositionSnapshot
    var channelsExpanded by remember { mutableStateOf(true) }
    var jointChannelsExpanded by remember { mutableStateOf(true) }
    var directMessagesExpanded by remember { mutableStateOf(true) }
    var pinnedExpanded by remember(currentServerId) { mutableStateOf(true) }
    var channelSortModeState by remember(currentServerId) { mutableStateOf(RaftSidebarSortMode.fromWireValue(channelSortMode)) }
    var jointChannelSortModeState by remember(currentServerId) { mutableStateOf(RaftSidebarSortMode.fromWireValue(jointChannelSortMode)) }
    var directMessageSortMode by remember(currentServerId) { mutableStateOf(RaftSidebarSortMode.fromWireValue(dmSortMode)) }
    var pinnedSortModeState by remember(currentServerId) { mutableStateOf(RaftSidebarSortMode.fromWireValue(pinnedSortMode)) }
    var activeSortMenu by remember(currentServerId) { mutableStateOf<RaftHomeSortSection?>(null) }
    var sortMenuAnchor by remember(currentServerId) { mutableStateOf<RaftPopoverAnchor?>(null) }
    var activePinMenuConversationId by remember(currentServerId) { mutableStateOf<String?>(null) }
    var activePinnedMemberMenuKey by remember(currentServerId) { mutableStateOf<String?>(null) }
    var pinMenuAnchor by remember(currentServerId) { mutableStateOf<RaftPopoverAnchor?>(null) }
    LaunchedEffect(currentServerId, channelSortMode, jointChannelSortMode, dmSortMode, pinnedSortMode) {
        channelSortModeState = RaftSidebarSortMode.fromWireValue(channelSortMode)
        jointChannelSortModeState = RaftSidebarSortMode.fromWireValue(jointChannelSortMode)
        directMessageSortMode = RaftSidebarSortMode.fromWireValue(dmSortMode)
        pinnedSortModeState = RaftSidebarSortMode.fromWireValue(pinnedSortMode)
        activeSortMenu = null
        activePinnedMemberMenuKey = null
    }
    LaunchedEffect(currentServerId, channels, directMessages) {
        // If the active row menu target was removed, close the menu.
        val activeId = activePinMenuConversationId
        if (activeId != null && channels.none { it.id == activeId } && directMessages.none { it.id == activeId }) {
            activePinMenuConversationId = null
        }
    }
    LaunchedEffect(currentServerId, directMessages) {
        val sample =
            directMessages
                .take(6)
                .joinToString(separator = ";") { dm ->
                    "id=${dm.id.take(36)}:" +
                        "peer=${dm.peerType.orEmpty()}:" +
                        "member=${dm.presenceMemberId.orEmpty()}"
                }
        diagnosticsLogger(
            "shared_home_dm_status_rows serverId=$currentServerId " +
                "count=${directMessages.size} agentRows=${directMessages.count { it.peerType?.equals("agent", ignoreCase = true) == true }} " +
                "withMemberId=${directMessages.count { !it.presenceMemberId.isNullOrBlank() }} sample=$sample"
        )
    }
    // Pinned conversations leave their system and custom sections (web typed pinned section);
    // dedup inside the pinned section itself is handled by `pinnedRowsForSidebar`.
    @Suppress("UNUSED_VARIABLE")
    val pinnedIdSet =
        remember(directMessages, pinnedChannelIds, pinnedAgentIds, pinnedItems) {
            buildSet {
                addAll(pinnedChannelIds)
                addAll(pinnedAgentIds)
                pinnedItems.forEach { item ->
                    when (item.kind.trim().lowercase()) {
                        "channel" -> add(item.id)
                        "agent", "human" -> {
                            add(item.id)
                            directMessages
                                .firstOrNull { dm -> dm.presenceMemberId == item.id }
                                ?.let { add(it.id) }
                        }
                    }
                }
                val pinnedAgentIdSet = pinnedAgentIds.toSet()
                directMessages.forEach { dm ->
                    if (dm.peerType.equals("agent", ignoreCase = true) && dm.presenceMemberId in pinnedAgentIdSet) {
                        add(dm.id)
                    }
                }
            }
        }
    var sortOverlaySection by remember(currentServerId) { mutableStateOf<RaftHomeSortSection?>(null) }
    var activeMenuSection by remember(currentServerId) { mutableStateOf<RaftHomeSortSection?>(null) }
    // task #512: custom-section UI state — collapsed ids, the header context
    // menu, the create/rename dialog, the delete confirmation, and the
    // move-to-section submenu target.
    var collapsedCustomSectionIds by remember(currentServerId) { mutableStateOf(setOf<String>()) }
    var activeSectionMenuId by remember(currentServerId) { mutableStateOf<String?>(null) }
    var sectionMenuAnchor by remember(currentServerId) { mutableStateOf<RaftPopoverAnchor?>(null) }
    var sectionDialogTarget by remember(currentServerId) { mutableStateOf<RaftHomeSectionDialogTarget?>(null) }
    var sectionDeleteConfirmId by remember(currentServerId) { mutableStateOf<String?>(null) }
    var moveToSectionConversationId by remember(currentServerId) { mutableStateOf<String?>(null) }
    // Web parity: long-pressing a system section header (web right-click)
    // opens that section's menu — sort, display, new section.
    var activeSystemSectionMenu by remember(currentServerId) { mutableStateOf<RaftHomeSortSection?>(null) }
    var systemSectionMenuAnchor by remember(currentServerId) { mutableStateOf<RaftPopoverAnchor?>(null) }
    val systemSectionSortMode: (RaftHomeSortSection) -> RaftSidebarSortMode = { section ->
        when (section) {
            RaftHomeSortSection.Channels -> channelSortModeState
            RaftHomeSortSection.JointChannels -> jointChannelSortModeState
            RaftHomeSortSection.DirectMessages -> directMessageSortMode
            RaftHomeSortSection.Pinned -> pinnedSortModeState
        }
    }
    val selectSystemSectionSortMode: (RaftHomeSortSection, RaftSidebarSortMode) -> Unit = { section, nextMode ->
        when (section) {
            RaftHomeSortSection.Channels -> {
                channelSortModeState = nextMode
                onChannelSortModeChange(nextMode)
            }
            RaftHomeSortSection.JointChannels -> {
                jointChannelSortModeState = nextMode
                onJointChannelSortModeChange(nextMode)
            }
            RaftHomeSortSection.DirectMessages -> {
                directMessageSortMode = nextMode
                onDmSortModeChange(nextMode)
            }
            RaftHomeSortSection.Pinned -> {
                pinnedSortModeState = nextMode
                onPinnedSortModeChange(nextMode)
            }
        }
    }
    val allChannelId = remember(channels) { channels.firstOrNull { it.name == "all" }?.id }
    // task #512: conversations claimed by a custom section leave their default
    // system section (web customPlacedChannelIds / customPlacedAgentIds).
    val customPlacedConversationIds = remember(sectionPlacements, directMessages) {
        sidebarCustomPlacedConversationIds(sectionPlacements, directMessages)
    }
    val displayedPinned = remember(channels, directMessages, pinnedChannelIds, pinnedAgentIds, pinnedOrder, pinnedItems, pinnedSortModeState, pinnedMemberProfileResolver) {
        pinnedRowsForSidebar(
            channels = channels,
            directMessages = directMessages,
            pinnedIds = pinnedChannelIds,
            pinnedAgentIds = pinnedAgentIds,
            pinnedOrder = pinnedOrder,
            typedPinnedItems = pinnedItems,
            pinnedMemberProfileResolver = pinnedMemberProfileResolver,
            mode = pinnedSortModeState
        )
    }
    // Web Sidebar.tsx (typed pinned section, #4569): a pinned channel or DM leaves its system
    // section — Channels / Joint Channels / Direct Messages list and count only what is not
    // already shown under Pinned. Only rows the Pinned section actually renders are removed, so
    // a stale pin can never hide a conversation from every section.
    val pinnedShownConversationIds = remember(displayedPinned) { displayedPinned.sidebarPinnedConversationIds() }
    val baseDisplayedChannels = remember(channels, channelOrder, channelSortModeState, customPlacedConversationIds, pinnedShownConversationIds) {
        val regularChannels = channels.filter { it.kind != JointChannelKindWireValue }
        regularChannels
            .filter { it.id !in customPlacedConversationIds && it.id !in pinnedShownConversationIds }
            .orderedBySidebarIds(channelOrder, pinnedFirstId = allChannelId)
            .sortedForSidebar(channelSortModeState, pinnedFirstId = allChannelId) { it.name }
    }
    val displayedChannels = baseDisplayedChannels
    val sortableDisplayedChannels = remember(baseDisplayedChannels, allChannelId) {
        baseDisplayedChannels.withoutSidebarPinnedItem(allChannelId)
    }
    val displayedJointChannels = remember(channels, channelOrder, jointChannelSortModeState, customPlacedConversationIds, pinnedShownConversationIds) {
        channels
            .filter { it.kind == JointChannelKindWireValue }
            .filter { it.id !in customPlacedConversationIds && it.id !in pinnedShownConversationIds }
            .orderedBySidebarIds(channelOrder)
            .sortedForSidebar(jointChannelSortModeState) { it.name }
    }
    val hiddenDmIdSet = remember(hiddenDmIds) { hiddenDmIds.toSet() }
    val displayedDirectMessages = remember(directMessages, dmOrder, directMessageSortMode, hiddenDmIdSet, customPlacedConversationIds, pinnedShownConversationIds) {
        directMessages
            .filter { it.id !in customPlacedConversationIds && it.id !in pinnedShownConversationIds }
            .orderedBySidebarIds(dmOrder)
            .filter { dm -> dm.id !in hiddenDmIdSet || dm.homeConversationBadgeProjection().hasUnread }
            .sortedForSidebar(directMessageSortMode) { it.name }
    }
    LaunchedEffect(currentServerId, pinnedItems, displayedPinned) {
        val resolvedKeys = displayedPinned.mapTo(mutableSetOf()) { it.key }
        val unresolvedTypedCount =
            pinnedItems.count { item ->
                "${item.kind.trim().lowercase()}:${item.id.trim()}" !in resolvedKeys
            }
        val sample =
            displayedPinned
                .take(6)
                .joinToString(separator = ";") { row ->
                    when (val target = row.clickTarget()) {
                        is RaftHomePinnedClickTarget.Conversation -> "${row.key}->conversation:${target.conversationId}"
                        is RaftHomePinnedClickTarget.Member -> "${row.key}->member:${target.identity.memberId.value}"
                        null -> "${row.key}->none"
                    }
                }
        diagnosticsLogger(
            "shared_home_pinned_resolution serverId=$currentServerId " +
                "typed=${pinnedItems.size} rows=${displayedPinned.size} " +
                "conversation=${displayedPinned.count { it.conversation != null }} " +
                "memberFallback=${displayedPinned.count { it.member != null }} " +
                "unresolved=$unresolvedTypedCount sample=$sample"
        )
    }
    val pinnedItemsForSort = remember(displayedPinned) { displayedPinned.mapNotNull { it.toSidebarSortItem() } }
    // task #512: custom sections interleave with the system sections in web's
    // sectionOrder order; like web, custom sections show even when empty.
    val displayedCustomSections =
        remember(channels, directMessages, customSections, sectionOrder, sectionPlacements, pinnedIdSet) {
            sidebarCustomSectionRows(
                channels = channels,
                directMessages = directMessages,
                customSections = customSections,
                sectionOrder = sectionOrder,
                sectionPlacements = sectionPlacements,
                pinnedConversationIds = pinnedIdSet
            )
        }
    val hasDisplayedPinned = displayedPinned.isNotEmpty()
    val hasDisplayedJointChannels = displayedJointChannels.isNotEmpty()
    // Keep the DMs section while its rows are still loading (skeletons).
    val hasDisplayedDirectMessages = displayedDirectMessages.isNotEmpty() || showLoadingSkeletons
    val displayedSidebarSections =
        remember(
            sectionOrder,
            customSections,
            displayedCustomSections,
            hasDisplayedPinned,
            hasDisplayedJointChannels,
            hasDisplayedDirectMessages,
            hideEmptySections
        ) {
            homeSidebarSectionOrder(
                sectionOrder = sectionOrder,
                customSections = customSections,
                customSectionRows = displayedCustomSections,
                hasPinned = hasDisplayedPinned,
                hasJointChannels = hasDisplayedJointChannels,
                hasDirectMessages = hasDisplayedDirectMessages,
                hideEmptySections = hideEmptySections
            )
        }
    val pinnedSectionHasUnread = remember(displayedPinned) { displayedPinned.any { it.conversation?.homeConversationBadgeProjection()?.hasUnread == true } }
    val channelSectionHasUnread = remember(displayedChannels) { displayedChannels.any { it.homeConversationBadgeProjection().hasUnread } }
    val jointChannelSectionHasUnread = remember(displayedJointChannels) { displayedJointChannels.any { it.homeConversationBadgeProjection().hasUnread } }
    val directMessageSectionHasUnread = remember(displayedDirectMessages) { displayedDirectMessages.any { it.homeConversationBadgeProjection().hasUnread } }
    val serverSwitcherHasOtherUnread =
        remember(currentServerId, servers) {
            homeServerSwitcherHasOtherUnread(currentServerId, servers)
        }
    val currentHomeHeaderServer =
        remember(currentServerId, currentServerName, servers) {
            homeHeaderServerIdentity(
                currentServerId = currentServerId,
                currentServerName = currentServerName,
                servers = servers
            )
        }
    val sidebarSelection =
        homeSidebarSelection(
            selectedChannelId = selectedChannelId,
            selectedConversationSection = selectedConversationSection,
            channels = channels,
            directMessages = directMessages,
            searchSelected = searchSelected,
            activitySelected = activitySelected,
            savedSelected = savedSelected,
            pinnedChannelIds = pinnedIdSet
        )
    // #211 React parity (revised by @artin 23:38): pinned row and
    // its original-section copy both render the same conversationId,
    // and both must show the Selected highlight at the same time.
    // Use the conversationId match directly so the per-section
    // gating in `sectionSelection()` doesn't filter out the second
    // copy. Each row owns its press state (the designsystem row's own
    // interaction source), so the two copies don't press together.
    val channelSectionSelection = sidebarSelection.sectionSelection(RaftHomeConversationSection.Channel)
    val jointChannelSectionSelection = sidebarSelection.sectionSelection(RaftHomeConversationSection.JointChannel)
    val directMessageSectionSelection = sidebarSelection.sectionSelection(RaftHomeConversationSection.DirectMessage)
    val pinnedSectionSelection = sidebarSelection.sectionSelection(RaftHomeConversationSection.Pinned)
    val pullToRefreshEnabled = homePullToRefreshEnabled(contentRefreshEnabled)
    val lazyItemCount =
        homeLazyItemCount(
            channelsCount = displayedChannels.size,
            jointCount = displayedJointChannels.size,
            directMessagesCount = displayedDirectMessages.size,
            pinnedCount = displayedPinned.size,
            channelsExpanded = channelsExpanded,
            jointChannelsExpanded = jointChannelsExpanded,
            directMessagesExpanded = directMessagesExpanded,
            pinnedExpanded = pinnedExpanded,
            hasInlineError = inlineError != null,
            channelSkeletonCount =
                if (channelsExpanded && loadingSkeletonRowsShown) HomeLoadingChannelSkeletonRows else 0,
            directMessageSkeletonCount =
                if (directMessagesExpanded && loadingSkeletonRowsShown) HomeLoadingDirectMessageSkeletonRows else 0
        )
    val lazyKeyRegistry = remember { HomeSidebarLazyKeyRegistry() }
    val pinnedLazyKeys =
        lazyKeyRegistry.keysFor(
            serverId = currentServerId,
            section = "pinned",
            identities = displayedPinned.map { it.key }
        )
    val jointChannelLazyKeys =
        lazyKeyRegistry.keysFor(
            serverId = currentServerId,
            section = "joint",
            identities = displayedJointChannels.map { it.id }
        )
    val channelLazyKeys =
        lazyKeyRegistry.keysFor(
            serverId = currentServerId,
            section = "channel",
            identities = displayedChannels.map { it.id }
        )
    val directMessageLazyKeys =
        lazyKeyRegistry.keysFor(
            serverId = currentServerId,
            section = "dm",
            identities = displayedDirectMessages.map { it.id }
        )
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex = initialListPosition.firstVisibleItemIndex,
            initialFirstVisibleItemScrollOffset = initialListPosition.firstVisibleItemScrollOffset
        )
    var listStateServerId by remember { mutableStateOf(listPositionServerId) }
    var listPremeasureServerId by remember { mutableStateOf(currentServerId) }
    var listPremeasureLimit by remember { mutableStateOf(homeListInitialBeyondBoundsItemCount()) }
    val homeUserScrollGeneration = remember { HomeUserScrollGeneration() }
    val listPositionRestoreStartUserScrollGeneration =
        remember(
            listPositionServerId,
            initialListPosition.firstVisibleItemIndex,
            initialListPosition.firstVisibleItemScrollOffset
        ) {
            // Capture the restore intent at composition time. A pointer move
            // before the effect gets the viewport retires this intent.
            homeUserScrollGeneration.value
        }
    val latestOnListPositionSnapshot by rememberUpdatedState(onListPositionSnapshot)
    DisposableEffect(listState, currentServerId) {
        val snapshotServerId = currentServerId
        onDispose {
            if (homeListStateOwnerMatches(snapshotServerId, listStateServerId)) {
                latestOnListPositionSnapshot(
                    snapshotServerId,
                    listState.toAppShellLazyListPosition()
                )
            }
        }
    }
    val listScope = rememberCoroutineScope()
    val scrollToTopRequestFence = remember { AppShellScrollToTopRequestFence(scrollToTopRequest) }
    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequestFence.consume(scrollToTopRequest)) {
            listState.animateScrollToTopExpressive()
        }
    }
    var pullRefreshActive by remember(currentServerId) { mutableStateOf(false) }
    LaunchedEffect(contentRefreshing, pullToRefreshEnabled) {
        if (!contentRefreshing || !pullToRefreshEnabled) {
            pullRefreshActive = false
        }
    }
    val pullRefreshing = contentRefreshing && pullToRefreshEnabled && pullRefreshActive
    val headerRefreshing = homeHeaderRefreshIndicatorVisible(
        contentRefreshing = contentRefreshing,
        pullRefreshing = pullRefreshing
    )
    // artin 2026-08-06 ruling (task #113): app update does NOT go through the
    // notification center — it keeps its independent top banner. The Bell
    // trigger and center only see non-app-update notifications.
    val appUpdateBanner = remember(notifications) { notifications.homeAppUpdateBannerNotification() }
    val visibleNotifications = remember(notifications) { notifications.nonAppUpdateHomeNotifications() }
    // Wide layout: the bell is in the top bar, so its open state is the shell's; Home still
    // closes it on focus loss, a tab switch and a server switch.
    val topBarBell = LocalRaftAppShellTopBar.current?.takeIf { serverSwitcherInTopBar }
    val localNotificationCenterExpanded = remember(currentServerId) { mutableStateOf(false) }
    var notificationCenterExpanded by (topBarBell?.bellOpen ?: localNotificationCenterExpanded)
    if (topBarBell != null) {
        LaunchedEffect(topBarBell, currentServerId) { topBarBell.bellOpen.value = false }
        SideEffect {
            topBarBell.notifications = visibleNotifications
            topBarBell.onView = onNotificationView
            topBarBell.onDismiss = onNotificationDismiss
        }
        DisposableEffect(topBarBell) {
            onDispose {
                topBarBell.bellOpen.value = false
                topBarBell.notifications = emptyList()
                topBarBell.onView = {}
                topBarBell.onDismiss = {}
            }
        }
    }
    LaunchedEffect(externalFocusLossDismissEpoch) {
        if (externalFocusLossDismissEpoch > 0) {
            notificationCenterExpanded = false
        }
    }
    val sidebarInteractionsEnabled = true
    val resolvedTopSafeArea = raftTopSafeAreaPadding()
    val topSafeArea =
        homeShellTopSafeArea(
            applyTopSafeArea = applyTopSafeArea,
            resolvedTopSafeArea = resolvedTopSafeArea,
            topSafeAreaOverride = topSafeAreaOverride,
        )
    val bottomSafeArea = if (showBottomBar) raftBottomSafeAreaPadding() else 0.dp
    val effectiveBottomContentPadding =
        if (showBottomBar) {
            bottomContentPadding.max(raftAppBottomTabBarContentInset(bottomSafeArea))
        } else {
            bottomContentPadding
        }
    val bottomBarOverlaysContent = showBottomBar && raftAppBottomTabBarOverlaysContent()
    // iPhone Duo (raft-design #859): beside the system's side column the bell is Home's page
    // action in the column, and the head is the server switcher line on paper.
    val sideColumnPosture = LocalRaftSideColumnPosture.current
    val bellInColumn =
        !serverSwitcherInTopBar &&
            raftSideColumnControls(
                back = null,
                actions =
                    listOf(
                        homeSideColumnBellAction(visibleNotifications) {
                            notificationCenterExpanded = !notificationCenterExpanded
                        }
                    ),
            )
    val dismissNotificationCenterForFocusLoss = {
        if (notificationCenterExpanded) {
            notificationCenterExpanded = false
        }
    }
    val onServerBadgeClickWithDismiss: () -> Unit = {
        dismissNotificationCenterForFocusLoss()
        onServerBadgeClick()
    }
    val onSearchClickWithDismiss: () -> Unit = {
        dismissNotificationCenterForFocusLoss()
        onSearchClick()
    }
    val onInboxClickWithDismiss: () -> Unit = {
        dismissNotificationCenterForFocusLoss()
        onInboxClick()
    }
    val onSavedClickWithDismiss: () -> Unit = {
        dismissNotificationCenterForFocusLoss()
        onSavedClick()
    }
    val onChannelClickWithDismiss: (String) -> Unit = { conversationId ->
        dismissNotificationCenterForFocusLoss()
        onChannelClick(conversationId)
    }
    val onChannelLongPressWithDismiss: (String, RaftPopoverAnchor) -> Unit = { conversationId, anchor ->
        dismissNotificationCenterForFocusLoss()
        onChannelLongPress(conversationId, anchor)
    }
    val onBottomTabSelected: (String) -> Unit = { tabKey ->
        if (shouldDismissHomeNotificationCenterForTabSelection(
                expanded = notificationCenterExpanded,
                selectedTabKey = selectedTabKey,
                targetTabKey = tabKey
            )
        ) {
            notificationCenterExpanded = false
        }
        onTabSelected(tabKey)
    }
    Box(
        modifier = modifier.fillMaxSize().background(RaftColor.AppBgLightBlue).padding(outerPadding)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(RaftColor.SurfaceWhite)) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (!serverSwitcherInTopBar) traceMarkdownSection(RaftHomeTraceNames.TITLEBAR_RENDER, notifications.size) {
                    HomeTitlebar(
                        serverName = currentServerName,
                        serverIdentity = currentHomeHeaderServer.takeIf { homeServerAvatarEnabled },
                        serverPublicPageLive = currentHomeHeaderServer.publicPageLive,
                        hasOtherServerUnread = serverSwitcherHasOtherUnread,
                        serverSwitcherOpen = serverSwitcherExpanded,
                        notifications = visibleNotifications,
                        notificationCenterExpanded = notificationCenterExpanded,
                        refreshing = headerRefreshing,
                        topSafeArea = topSafeArea,
                        bellInColumn = bellInColumn,
                        onServerBadgeClick = onServerBadgeClickWithDismiss,
                        onBlankClick = {
                            dismissNotificationCenterForFocusLoss()
                            listScope.launch {
                                listState.animateScrollToTopExpressive()
                            }
                        },
                        onNotificationToggle = { notificationCenterExpanded = !notificationCenterExpanded }
                    )
                }
                if (serverSwitcherInTopBar && !raftUnderAppShellTopBar()) {
                    // iPhone Duo's inner screen (raft-design #859): no top bar, so the sidebar has
                    // its 56dp head with the destination's name, its seam on the rail header's.
                    // The server switcher is the rail's tile, not this head.
                    RaftTopPageHeader(
                        title = stringResource(Res.string.tab_home),
                        backgroundColor = RaftColor.Canvas,
                        topSafeAreaOverride = topSafeArea,
                        contentStartPadding = RaftMainTabHeaderStartPadding,
                        titleStyle = RaftMainTabHeaderTitleStyle,
                        onTitleClick = {
                            dismissNotificationCenterForFocusLoss()
                            listScope.launch { listState.animateScrollToTopExpressive() }
                        },
                        showBodyTopBanner = false,
                    )
                }
                RaftAppShellBodyTopBanner()
                // Task #164 (stdrc O2): under the head, above the list; the counts stay as they are.
                if (unreadStale) {
                    RaftReadWarehouseStaleNotice(stringResource(Res.string.home_unread_stale_notice))
                }
                appUpdateBanner?.let { update ->
                    HomeAppUpdateBanner(
                        notification = update,
                        onClick = { onNotificationView(update) }
                    )
                }
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val targetHomeBeyondBoundsItemCount =
                        homeListBeyondBoundsItemCount(
                            itemCount = lazyItemCount,
                            viewportHeightDp = maxHeight.value
                        )
                    val homeBeyondBoundsItemCount =
                        if (listPremeasureServerId == currentServerId) {
                            listPremeasureLimit.coerceAtMost(targetHomeBeyondBoundsItemCount)
                        } else {
                            HomeListMinBeyondBoundsItemCount.coerceAtMost(targetHomeBeyondBoundsItemCount)
                        }
                    LaunchedEffect(
                        currentServerId,
                        listPositionServerId,
                        initialListPosition.firstVisibleItemIndex,
                        initialListPosition.firstVisibleItemScrollOffset,
                        targetHomeBeyondBoundsItemCount,
                        contentRefreshing
                    ) {
                        if (listStateServerId != listPositionServerId) {
                            latestOnListPositionSnapshot(
                                listStateServerId,
                                listState.toAppShellLazyListPosition()
                            )
                            if (
                                shouldRestoreHomeListPosition(
                                    listStateServerId = listStateServerId,
                                    listPositionServerId = listPositionServerId,
                                    restoreStartUserScrollGeneration = listPositionRestoreStartUserScrollGeneration,
                                    currentUserScrollGeneration = homeUserScrollGeneration.value,
                                    isScrollInProgress = listState.isScrollInProgress
                                )
                            ) {
                                listState.scrollToItem(
                                    initialListPosition.firstVisibleItemIndex,
                                    initialListPosition.firstVisibleItemScrollOffset
                                )
                            }
                            // A skipped restore is consumed too: otherwise a later,
                            // unrelated effect restart could pull the user back.
                            listStateServerId = listPositionServerId
                        }
                        if (listPremeasureServerId != currentServerId) {
                            listPremeasureLimit = homeListInitialBeyondBoundsItemCount()
                            listPremeasureServerId = currentServerId
                        }
                        if (listPremeasureLimit > targetHomeBeyondBoundsItemCount) {
                            listPremeasureLimit = targetHomeBeyondBoundsItemCount
                        }
                        if (!homeListShouldRampBeyondBounds(contentRefreshing)) {
                            return@LaunchedEffect
                        }
                        while (listPremeasureLimit < targetHomeBeyondBoundsItemCount) {
                            withFrameNanos { }
                            listPremeasureLimit =
                                homeListNextServerSwitchBeyondBoundsItemCount(
                                    current = listPremeasureLimit,
                                    target = targetHomeBeyondBoundsItemCount
                                )
                        }
                    }
                    traceMarkdownSection(RaftHomeTraceNames.LIST_RENDER, lazyItemCount) {
                        RaftPullToRefreshBox(
                            isRefreshing = pullRefreshing,
                            onRefresh = {
                                pullRefreshActive = true
                                onRefresh()
                            },
                            modifier = Modifier.fillMaxSize(),
                            enabled = pullToRefreshEnabled,
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .touchListener { type, _ ->
                                            if (type == TouchType.Move) {
                                                homeUserScrollGeneration.value += 1
                                            }
                                        }
                                        .scrollToTop {
                                            listScope.launch {
                                                listState.animateScrollToTopExpressive()
                                            }
                                        },
                                // Rows keep their own 1dp margins (raft-ui SidebarItem `my-px`).
                            ) {
                                // Web phone Home (Sidebar.tsx `px-2 pt-1`): the list starts 4dp under the
                                // header; the first row's own 1dp margin puts its box 5dp down. Under the
                                // wide top bar, with no head, 8dp (Sidebar `hideTitle`: `pt-2`).
                                item { Spacer(Modifier.height(if (serverSwitcherInTopBar) HomeListTopPaddingUnderTopBar else HomeListTopPadding)) }
                                // Jasper 2026-10-20: with the phone bar on screen, Search and Activity
                                // live there (Search and Activity tabs); only Saved stays here. The wide
                                // rail carries both too (nav spec §5), so it drops them as well.
                                if (homeSidebarShowsSearchAndActivityRows(searchAndActivityOnShellNav)) {
                                    item { HomeSidebarEntryRow(RaftLucideIconName.Search, labels.search, selected = sidebarSelection.topAction == RaftHomeSidebarTopSelection.Search, modifier = Modifier.padding(horizontal = HomeListRowHorizontalPadding), onClick = if (sidebarInteractionsEnabled) onSearchClickWithDismiss else null) }
                                    // artin 2026-10-20: without the phone bar the Home 动态 row is the Activity entry (muscle
                                    // memory); it opens that same kept-alive tab via openHomeSecondaryDestination(Activity).
                                    item {
                                        val activityProjection =
                                            homeActivitySidebarProjection(
                                                activityUnreadCount = inboxUnreadCount,
                                                activityTotalCount = inboxTotalCount
                                            )
                                        HomeSidebarEntryRow(
                                            RaftLucideIconName.Activity,
                                            labels.inbox,
                                            selected = sidebarSelection.topAction == RaftHomeSidebarTopSelection.Activity,
                                            modifier = Modifier.padding(horizontal = HomeListRowHorizontalPadding),
                                            onClick = if (sidebarInteractionsEnabled) onInboxClickWithDismiss else null,
                                            trailing =
                                                when {
                                                    activityProjection.unreadCount > 0 -> {
                                                        { RaftUnreadCount(activityProjection.unreadCount, contentDescription = null) }
                                                    }
                                                    activityProjection.totalLabel != null -> {
                                                        { RaftSidebarItemMeta(activityProjection.totalLabel) }
                                                    }
                                                    else -> null
                                                }
                                        )
                                    }
                                }
                                item { HomeSidebarEntryRow(RaftLucideIconName.Bookmark, labels.saved, selected = sidebarSelection.topAction == RaftHomeSidebarTopSelection.Saved, modifier = Modifier.padding(horizontal = HomeListRowHorizontalPadding), trailing = formatHomeSavedCount(savedCount)?.let { count -> { RaftSidebarItemMeta(count) } }, onClick = if (sidebarInteractionsEnabled) onSavedClickWithDismiss else null) }
                                inlineError?.let { error ->
                                    item {
                                        HomeInlineErrorCard(
                                            error = error,
                                            modifier =
                                                Modifier
                                                    .padding(horizontal = HomeListHorizontalPadding, vertical = RaftAppSpacing.Sm),
                                            onAction = onInlineErrorAction
                                        )
                                    }
                                }
                            // Sections render in web's normalized sectionOrder: system and
                            // custom sections interleave (web Sidebar.tsx renders every block
                            // with CSS `order = sectionOrder.indexOf(id)`); empty sections hide.
                            // No spacer between blocks: as on the web, a section header's own
                            // `pt-4` and the row above's 1dp margin are the whole gap.
                            displayedSidebarSections.forEach { sidebarSection ->
                                when (sidebarSection) {
                                    RaftHomeSidebarSection.Pinned -> {
                                        item {
                                            HomeSidebarSectionHeader(
                                                title = labels.pinned,
                                                count = displayedPinned.size,
                                                expanded = pinnedExpanded,
                                                onExpandedChange = { if (sidebarInteractionsEnabled) pinnedExpanded = it },
                                                longPressIdentity = "$currentServerId:system:pinned",
                                                menuOpen = activeSystemSectionMenu == RaftHomeSortSection.Pinned,
                                                attention = pinnedSectionHasUnread,
                                                sortLabel = "${labels.sortChannels} ${labels.pinned}",
                                                onSortClick = { anchor ->
                                                    if (sidebarInteractionsEnabled) {
                                                        activeSortMenu = if (activeSortMenu == RaftHomeSortSection.Pinned) null else RaftHomeSortSection.Pinned
                                                        sortMenuAnchor = anchor
                                                    }
                                                },
                                                onLongPress =
                                                    if (sidebarInteractionsEnabled) {
                                                        { anchor ->
                                                            activeSystemSectionMenu = RaftHomeSortSection.Pinned
                                                            systemSectionMenuAnchor = anchor
                                                        }
                                                    } else {
                                                        null
                                                    },
                                                modifier = Modifier.padding(horizontal = HomeListRowHorizontalPadding)
                                            )
                                        }
                                        if (pinnedExpanded && displayedPinned.isEmpty()) {
                                            item(key = "pinned-empty:$currentServerId") {
                                                HomeSidebarSectionEmptyHint(labels.pinnedSectionEmpty, Modifier.padding(horizontal = HomeListRowHorizontalPadding))
                                            }
                                        }
                                        if (pinnedExpanded) {
                                            items(displayedPinned.size, key = { index -> pinnedLazyKeys[index] }) { index ->
                                                val pinnedRow = displayedPinned[index]
                                                val pinned = pinnedRow.conversation
                                                val pinnedMember = pinnedRow.member
                                                val isDirectMessage = pinned?.isDirectMessageConversation() == true || pinnedMember != null
                                                // #211 React parity (revised by @artin 23:38): pinned copy and
                                                // original-section copy of the same conversation both
                                                // render Selected when their conversationId matches.
                                                val pinnedSelected = pinned != null && sidebarSelection.conversationId == pinned.id
                                                val pinnedMenuOpen =
                                                    if (pinned != null) {
                                                        activePinMenuConversationId == pinned.id && activeMenuSection == RaftHomeSortSection.Pinned
                                                    } else {
                                                        activePinnedMemberMenuKey == pinnedRow.key
                                                    }
                                                val handleLongPress: (RaftPopoverAnchor) -> Unit = { anchor ->
                                                    when {
                                                        pinned != null -> {
                                                            onChannelLongPressWithDismiss(pinned.id, anchor)
                                                            activePinnedMemberMenuKey = null
                                                            activePinMenuConversationId = pinned.id
                                                            activeMenuSection = RaftHomeSortSection.Pinned
                                                            pinMenuAnchor = anchor
                                                        }
                                                        pinnedMember != null -> {
                                                            // Typed pinned members can exist before a DM
                                                            // conversation is materialized. They still own
                                                            // a stable member id in the per-user pinned
                                                            // order, so expose the section-level Sort action
                                                            // without inventing conversation operations.
                                                            dismissNotificationCenterForFocusLoss()
                                                            activePinMenuConversationId = null
                                                            activePinnedMemberMenuKey = pinnedRow.key
                                                            activeMenuSection = RaftHomeSortSection.Pinned
                                                            pinMenuAnchor = anchor
                                                        }
                                                    }
                                                }
                                                if (isDirectMessage) {
                                                    HomeSidebarDirectMessageRow(
                                                        interactionIdentity = homeSidebarInteractionIdentity("pinned", pinnedRow.key),
                                                        name = pinned?.name ?: pinnedMember?.name.orEmpty(),
                                                        description = pinned?.description ?: pinnedMember?.description,
                                                        avatarLabel = pinned?.avatarLabel ?: pinnedMember?.avatarLabel.orEmpty(),
                                                        peerType =
                                                            pinned?.peerType
                                                                ?: pinnedMember?.identity?.kind?.memberKindWireValue(),
                                                        memberId =
                                                            pinned?.presenceMemberId
                                                                ?: pinnedMember?.identity?.memberId?.value,
                                                        cacheServerId = currentServerId,
                                                        agentStatusResolver = agentStatusResolver,
                                                        memberAvatarResolver = memberAvatarResolver,
                                                        modifier = Modifier.padding(horizontal = HomeListRowHorizontalPadding),
                                                        hasDraft = pinned?.hasDraft == true,
                                                        muted = pinned?.activityMuted == true,
                                                        unreadCount = pinned?.unreadCount ?: 0,
                                                        markedUnread = pinned?.markedUnread == true,
                                                        selected = pinnedSelected,
                                                        menuOpen = pinnedMenuOpen,
                                                        onClick =
                                                            if (sidebarInteractionsEnabled) {
                                                                {
                                                                    when (val target = pinnedRow.clickTarget()) {
                                                                        is RaftHomePinnedClickTarget.Conversation -> {
                                                                            diagnosticsLogger(
                                                                                "shared_home_pinned_click serverId=$currentServerId " +
                                                                                    "key=${pinnedRow.key} target=conversation " +
                                                                                    "conversationId=${target.conversationId}"
                                                                            )
                                                                            onChannelClickWithDismiss(target.conversationId)
                                                                        }
                                                                        is RaftHomePinnedClickTarget.Member -> {
                                                                            diagnosticsLogger(
                                                                                "shared_home_pinned_click serverId=$currentServerId " +
                                                                                    "key=${pinnedRow.key} target=member " +
                                                                                    "memberId=${target.identity.memberId.value} " +
                                                                                    "memberKind=${target.identity.kind.memberKindWireValue()}"
                                                                            )
                                                                            dismissNotificationCenterForFocusLoss()
                                                                            onPinnedMemberClick(target.identity)
                                                                        }
                                                                        null -> Unit
                                                                    }
                                                                }
                                                            } else {
                                                                null
                                                            },
                                                        onLongPress = if (sidebarInteractionsEnabled && pinnedRow.supportsLongPress()) handleLongPress else null
                                                    )
                                                } else if (pinned != null) {
                                                    HomeSidebarChannelRow(
                                                        name = pinned.name,
                                                        symbol = pinned.homeChannelSymbol(currentHomeHeaderServer.publicPageLive),
                                                        joined = pinned.joined,
                                                        hasDraft = pinned.hasDraft,
                                                        muted = pinned.activityMuted,
                                                        bridgeProvider = pinned.bridgeProvider,
                                                        unreadCount = pinned.unreadCount,
                                                        markedUnread = pinned.markedUnread,
                                                        mentioned = pinned.mentioned,
                                                        followedThreadUnreadCount = pinned.followedThreadUnreadCount,
                                                        interactionIdentity = homeSidebarInteractionIdentity("pinned", pinnedRow.key),
                                                        selected = pinnedSelected,
                                                        menuOpen = pinnedMenuOpen,
                                                        onClick = if (sidebarInteractionsEnabled) ({ onChannelClickWithDismiss(pinned.id) }) else null,
                                                        onLongPress = if (sidebarInteractionsEnabled) handleLongPress else null,
                                                        modifier = Modifier.padding(horizontal = HomeListRowHorizontalPadding)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    RaftHomeSidebarSection.JointChannels -> {
                                        item {
                                            HomeSidebarSectionHeader(
                                                title = labels.jointChannels,
                                                count = displayedJointChannels.size,
                                                expanded = jointChannelsExpanded,
                                                onExpandedChange = { if (sidebarInteractionsEnabled) jointChannelsExpanded = it },
                                                longPressIdentity = "$currentServerId:system:joint",
                                                menuOpen = activeSystemSectionMenu == RaftHomeSortSection.JointChannels,
                                                attention = jointChannelSectionHasUnread,
                                                sortLabel = "${labels.sortChannels} ${labels.jointChannels}",
                                                onSortClick = { anchor ->
                                                    if (sidebarInteractionsEnabled) {
                                                        activeSortMenu = if (activeSortMenu == RaftHomeSortSection.JointChannels) null else RaftHomeSortSection.JointChannels
                                                        sortMenuAnchor = anchor
                                                    }
                                                },
                                                addLabel = labels.createJointChannel.takeIf { onCreateJointChannelClick != null },
                                                onAddClick = onCreateJointChannelClick,
                                                addEnabled = sidebarInteractionsEnabled,
                                                onLongPress =
                                                    if (sidebarInteractionsEnabled) {
                                                        { anchor ->
                                                            activeSystemSectionMenu = RaftHomeSortSection.JointChannels
                                                            systemSectionMenuAnchor = anchor
                                                        }
                                                    } else {
                                                        null
                                                    },
                                                modifier = Modifier.padding(horizontal = HomeListRowHorizontalPadding)
                                            )
                                        }
                                        if (jointChannelsExpanded && displayedJointChannels.isEmpty()) {
                                            item(key = "joint-empty:$currentServerId") {
                                                HomeSidebarSectionEmptyHint(labels.jointChannelsEmpty, Modifier.padding(horizontal = HomeListRowHorizontalPadding))
                                            }
                                        }
                                        if (jointChannelsExpanded) {
                                            items(displayedJointChannels.size, key = { index -> jointChannelLazyKeys[index] }) { index ->
                                                val channel = displayedJointChannels[index]
                                                val jointSelected = sidebarSelection.conversationId == channel.id
                                                HomeSidebarChannelRow(
                                                    name = channel.name,
                                                    symbol = channel.homeChannelSymbol(currentHomeHeaderServer.publicPageLive),
                                                    joined = channel.joined,
                                                    hasDraft = channel.hasDraft,
                                                    muted = channel.activityMuted,
                                                    bridgeProvider = channel.bridgeProvider,
                                                    unreadCount = channel.unreadCount,
                                                    markedUnread = channel.markedUnread,
                                                    mentioned = channel.mentioned,
                                                    followedThreadUnreadCount = channel.followedThreadUnreadCount,
                                                    interactionIdentity = homeSidebarInteractionIdentity("joint", channel.id),
                                                    selected = jointSelected,
                                                    menuOpen = activePinMenuConversationId == channel.id && activeMenuSection == RaftHomeSortSection.JointChannels,
                                                    onClick = if (sidebarInteractionsEnabled) ({ onChannelClickWithDismiss(channel.id) }) else null,
                                                    onLongPress = if (sidebarInteractionsEnabled) ({ anchor ->
                                                        onChannelLongPressWithDismiss(channel.id, anchor)
                                                        activePinMenuConversationId = channel.id
                                                        activeMenuSection = RaftHomeSortSection.JointChannels
                                                        pinMenuAnchor = anchor
                                                    }) else null,
                                                    modifier = Modifier.padding(horizontal = HomeListRowHorizontalPadding)
                                                )
                                            }
                                        }
                                    }

                                    RaftHomeSidebarSection.Channels -> {
                                        item {
                                            HomeSidebarSectionHeader(
                                                title = labels.channels,
                                                count = displayedChannels.size,
                                                expanded = channelsExpanded,
                                                onExpandedChange = { if (sidebarInteractionsEnabled) channelsExpanded = it },
                                                longPressIdentity = "$currentServerId:system:channels",
                                                menuOpen = activeSystemSectionMenu == RaftHomeSortSection.Channels,
                                                attention = channelSectionHasUnread,
                                                sortLabel = "${labels.sortChannels} ${labels.channels}",
                                                onSortClick = { anchor ->
                                                    if (sidebarInteractionsEnabled) {
                                                        activeSortMenu = if (activeSortMenu == RaftHomeSortSection.Channels) null else RaftHomeSortSection.Channels
                                                        sortMenuAnchor = anchor
                                                    }
                                                },
                                                addLabel = labels.addChannel,
                                                onAddClick = onCreateChannelClick,
                                                addEnabled = sidebarInteractionsEnabled,
                                                onLongPress =
                                                    if (sidebarInteractionsEnabled) {
                                                        { anchor ->
                                                            activeSystemSectionMenu = RaftHomeSortSection.Channels
                                                            systemSectionMenuAnchor = anchor
                                                        }
                                                    } else {
                                                        null
                                                    },
                                                modifier = Modifier.padding(horizontal = HomeListRowHorizontalPadding)
                                            )
                                        }
                                        if (channelsExpanded) {
                                            items(displayedChannels.size, key = { index -> channelLazyKeys[index] }) { index ->
                                                val channel = displayedChannels[index]
                                                val channelSelected = sidebarSelection.conversationId == channel.id
                                                HomeSidebarChannelRow(
                                                    name = channel.name,
                                                    symbol = channel.homeChannelSymbol(currentHomeHeaderServer.publicPageLive),
                                                    joined = channel.joined,
                                                    hasDraft = channel.hasDraft,
                                                    muted = channel.activityMuted,
                                                    bridgeProvider = channel.bridgeProvider,
                                                    unreadCount = channel.unreadCount,
                                                    markedUnread = channel.markedUnread,
                                                    mentioned = channel.mentioned,
                                                    followedThreadUnreadCount = channel.followedThreadUnreadCount,
                                                    interactionIdentity = homeSidebarInteractionIdentity("channel", channel.id),
                                                    selected = channelSelected,
                                                    menuOpen = activePinMenuConversationId == channel.id && activeMenuSection == RaftHomeSortSection.Channels,
                                                    onClick = if (sidebarInteractionsEnabled) ({ onChannelClickWithDismiss(channel.id) }) else null,
                                                    onLongPress = if (sidebarInteractionsEnabled) ({ anchor ->
                                                        onChannelLongPressWithDismiss(channel.id, anchor)
                                                        activePinMenuConversationId = channel.id
                                                        activeMenuSection = RaftHomeSortSection.Channels
                                                        pinMenuAnchor = anchor
                                                    }) else null,
                                                    modifier = Modifier.padding(horizontal = HomeListRowHorizontalPadding)
                                                )
                                            }
                                        }
                                        if (channelsExpanded && loadingSkeletonRowsShown) {
                                            items(HomeLoadingChannelSkeletonRows, key = { index -> "channel-skeleton-$index" }) {
                                                HomeSidebarSkeletonRow(modifier = Modifier.padding(horizontal = HomeListRowHorizontalPadding))
                                            }
                                        }
                                    }

                                    RaftHomeSidebarSection.DirectMessages -> {
                                        item {
                                            HomeSidebarSectionHeader(
                                                title = labels.directMessages,
                                                count = displayedDirectMessages.size,
                                                expanded = directMessagesExpanded,
                                                onExpandedChange = { if (sidebarInteractionsEnabled) directMessagesExpanded = it },
                                                longPressIdentity = "$currentServerId:system:dms",
                                                menuOpen = activeSystemSectionMenu == RaftHomeSortSection.DirectMessages,
                                                attention = directMessageSectionHasUnread,
                                                sortLabel = "${labels.sortChannels} ${labels.directMessages}",
                                                onSortClick = { anchor ->
                                                    if (sidebarInteractionsEnabled) {
                                                        activeSortMenu = if (activeSortMenu == RaftHomeSortSection.DirectMessages) null else RaftHomeSortSection.DirectMessages
                                                        sortMenuAnchor = anchor
                                                    }
                                                },
                                                onLongPress =
                                                    if (sidebarInteractionsEnabled) {
                                                        { anchor ->
                                                            activeSystemSectionMenu = RaftHomeSortSection.DirectMessages
                                                            systemSectionMenuAnchor = anchor
                                                        }
                                                    } else {
                                                        null
                                                    },
                                                modifier = Modifier.padding(horizontal = HomeListRowHorizontalPadding)
                                            )
                                        }
                                        if (directMessagesExpanded && displayedDirectMessages.isEmpty() && !showLoadingSkeletons) {
                                            item(key = "dms-empty:$currentServerId") {
                                                HomeSidebarSectionEmptyHint(labels.sectionEmpty, Modifier.padding(horizontal = HomeListRowHorizontalPadding))
                                            }
                                        }
                                        if (directMessagesExpanded) {
                                            items(displayedDirectMessages.size, key = { index -> directMessageLazyKeys[index] }) { index ->
                                                val dm = displayedDirectMessages[index]
                                                val dmSelected = sidebarSelection.conversationId == dm.id
                                                HomeSidebarDirectMessageRow(
                                                    interactionIdentity = homeSidebarInteractionIdentity("dm", dm.id),
                                                    name = dm.name,
                                                    description = dm.description,
                                                    avatarLabel = dm.avatarLabel,
                                                    peerType = dm.peerType,
                                                    memberId = dm.presenceMemberId,
                                                    cacheServerId = currentServerId,
                                                    agentStatusResolver = agentStatusResolver,
                                                    memberAvatarResolver = memberAvatarResolver,
                                                    modifier = Modifier.padding(horizontal = HomeListRowHorizontalPadding),
                                                    hasDraft = dm.hasDraft,
                                                    muted = dm.activityMuted,
                                                    unreadCount = dm.unreadCount,
                                                    markedUnread = dm.markedUnread,
                                                    selected = dmSelected,
                                                    menuOpen = activePinMenuConversationId == dm.id && activeMenuSection == RaftHomeSortSection.DirectMessages,
                                                    onClick = if (sidebarInteractionsEnabled) ({ onChannelClickWithDismiss(dm.id) }) else null,
                                                    onLongPress = if (sidebarInteractionsEnabled) ({ anchor ->
                                                        onChannelLongPressWithDismiss(dm.id, anchor)
                                                        activePinMenuConversationId = dm.id
                                                        activeMenuSection = RaftHomeSortSection.DirectMessages
                                                        pinMenuAnchor = anchor
                                                    }) else null
                                                )
                                            }
                                        }
                                        if (directMessagesExpanded && loadingSkeletonRowsShown) {
                                            items(HomeLoadingDirectMessageSkeletonRows, key = { index -> "dm-skeleton-$index" }) {
                                                HomeSidebarSkeletonRow(modifier = Modifier.padding(horizontal = HomeListRowHorizontalPadding))
                                            }
                                        }
                                    }

                                    // task #512: custom sections (web channel-sections port), each
                                    // with an emoji + name header; long-press opens the section menu
                                    // (web: right-click / long-press, no trailing control).
                                    is RaftHomeSidebarSection.Custom -> {
                                        val sectionRows = sidebarSection.rows
                                        val customSection = sectionRows.section
                                        val customSectionExpanded = customSection.id !in collapsedCustomSectionIds
                                        item(key = "custom-section-header:$currentServerId:${customSection.id}") {
                                            HomeSidebarSectionHeader(
                                                title = customSection.name,
                                                emoji = customSection.emoji?.takeIf { it.isNotBlank() },
                                                count = sectionRows.items.size,
                                                expanded = customSectionExpanded,
                                                onExpandedChange = { expanded ->
                                                    if (sidebarInteractionsEnabled) {
                                                        collapsedCustomSectionIds =
                                                            if (expanded) {
                                                                collapsedCustomSectionIds - customSection.id
                                                            } else {
                                                                collapsedCustomSectionIds + customSection.id
                                                            }
                                                    }
                                                },
                                                longPressIdentity = "$currentServerId:${customSection.id}",
                                                menuOpen = activeSectionMenuId == customSection.id,
                                                attention = sectionRows.items.any { it.homeConversationBadgeProjection().hasUnread },
                                                onLongPress =
                                                    if (sidebarInteractionsEnabled) {
                                                        { anchor ->
                                                            activeSectionMenuId = customSection.id
                                                            sectionMenuAnchor = anchor
                                                        }
                                                    } else {
                                                        null
                                                    },
                                                modifier = Modifier.padding(horizontal = HomeListRowHorizontalPadding)
                                            )
                                        }
                                        if (customSectionExpanded && sectionRows.items.isEmpty()) {
                                            item(key = "custom-section-empty:$currentServerId:${customSection.id}") {
                                                HomeSidebarSectionEmptyHint(labels.sectionEmpty, Modifier.padding(horizontal = HomeListRowHorizontalPadding))
                                            }
                                        }
                                        if (customSectionExpanded) {
                                            items(
                                                sectionRows.items.size,
                                                key = { index -> "custom:$currentServerId:${customSection.id}:${sectionRows.items[index].id}" }
                                            ) { index ->
                                                val item = sectionRows.items[index]
                                                val customRowKey = "custom:${customSection.id}:${item.id}"
                                                val customSelected = sidebarSelection.conversationId == item.id
                                                val customMenuOpen = activePinMenuConversationId == item.id && activeMenuSection == null
                                                val customLongPress: (RaftPopoverAnchor) -> Unit = { anchor ->
                                                    onChannelLongPressWithDismiss(item.id, anchor)
                                                    activePinMenuConversationId = item.id
                                                    activeMenuSection = null
                                                    pinMenuAnchor = anchor
                                                }
                                                if (item.isDirectMessageConversation()) {
                                                    HomeSidebarDirectMessageRow(
                                                        interactionIdentity = homeSidebarInteractionIdentity("custom", customRowKey),
                                                        name = item.name,
                                                        description = item.description,
                                                        avatarLabel = item.avatarLabel,
                                                        peerType = item.peerType,
                                                        memberId = item.presenceMemberId,
                                                        cacheServerId = currentServerId,
                                                        agentStatusResolver = agentStatusResolver,
                                                        memberAvatarResolver = memberAvatarResolver,
                                                        modifier = Modifier.padding(horizontal = HomeListRowHorizontalPadding),
                                                        hasDraft = item.hasDraft,
                                                        muted = item.activityMuted,
                                                        unreadCount = item.unreadCount,
                                                        markedUnread = item.markedUnread,
                                                        selected = customSelected,
                                                        menuOpen = customMenuOpen,
                                                        onClick = if (sidebarInteractionsEnabled) ({ onChannelClickWithDismiss(item.id) }) else null,
                                                        onLongPress = if (sidebarInteractionsEnabled) customLongPress else null
                                                    )
                                                } else {
                                                    HomeSidebarChannelRow(
                                                        name = item.name,
                                                        symbol = item.homeChannelSymbol(currentHomeHeaderServer.publicPageLive),
                                                        joined = item.joined,
                                                        hasDraft = item.hasDraft,
                                                        muted = item.activityMuted,
                                                        bridgeProvider = item.bridgeProvider,
                                                        unreadCount = item.unreadCount,
                                                        markedUnread = item.markedUnread,
                                                        mentioned = item.mentioned,
                                                        followedThreadUnreadCount = item.followedThreadUnreadCount,
                                                        interactionIdentity = homeSidebarInteractionIdentity("custom", customRowKey),
                                                        selected = customSelected,
                                                        menuOpen = customMenuOpen,
                                                        onClick = if (sidebarInteractionsEnabled) ({ onChannelClickWithDismiss(item.id) }) else null,
                                                        onLongPress = if (sidebarInteractionsEnabled) customLongPress else null,
                                                        modifier = Modifier.padding(horizontal = HomeListRowHorizontalPadding)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(effectiveBottomContentPadding)) }
                        }
                        }
                }
            }
            }
            ServerSwitcherAvatarPrewarmSurface(servers)
            if (serverSwitcherExpanded) {
                if (serverDrawerEnabled) {
                    // The opt-in drawer owns the full screen so its scrim and native
                    // dispatch barrier also cover the shell bottom surface.
                    // The iOS system tab bar is drawn above the page: hide it under the drawer.
                    RaftNativeTabBarCover()
                    RaftFloatingOverlayHost(
                        onDismiss = onServerSwitcherDismiss,
                        dismissActivationDelayMillis = RaftFloatingOverlayClickTriggeredDismissDelayMillis
                    ) { _ ->
                        HomeServerDrawerPanel(
                            sections =
                                serverDrawerSections.ifEmpty {
                                    servers.map { server ->
                                        RaftHomeServerDrawerSection(
                                            server = server,
                                            expanded = server.id == currentServerId,
                                            status =
                                                if (server.id == currentServerId) {
                                                    HomeServerDrawerLoadStatus.Loaded
                                                } else {
                                                    HomeServerDrawerLoadStatus.Unloaded
                                                },
                                            channels = if (server.id == currentServerId) channels else emptyList(),
                                            directMessages = if (server.id == currentServerId) directMessages else emptyList(),
                                            channelOrder = if (server.id == currentServerId) channelOrder else emptyList(),
                                            channelSortMode = if (server.id == currentServerId) channelSortMode else "manual",
                                            dmOrder = if (server.id == currentServerId) dmOrder else emptyList(),
                                            dmSortMode = if (server.id == currentServerId) dmSortMode else "manual",
                                            pinnedChannelIds = if (server.id == currentServerId) pinnedChannelIds else emptyList(),
                                            pinnedAgentIds = if (server.id == currentServerId) pinnedAgentIds else emptyList(),
                                            pinnedOrder = if (server.id == currentServerId) pinnedOrder else emptyList(),
                                            pinnedItems = if (server.id == currentServerId) pinnedItems else emptyList(),
                                            pinnedSortMode = if (server.id == currentServerId) pinnedSortMode else "manual",
                                            hiddenDmIds = if (server.id == currentServerId) hiddenDmIds else emptyList()
                                        )
                                    }
                                },
                            labels = labels,
                            onDismiss = onServerSwitcherDismiss,
                            onServerClick = onServerSelected,
                            onSectionToggle = onServerDrawerSectionToggle,
                            onRetry = onServerDrawerRetry,
                            onCreateServerClick = onCreateServerClick,
                            onInviteHumanClick = onInviteHumanClick
                        )
                    }
                } else {
                    // Preserve the legacy popover boundary when the opt-in is disabled:
                    // the shell bottom inset/tab surface remains outside the dismiss host.
                    RaftFloatingOverlayHost(
                        onDismiss = onServerSwitcherDismiss,
                        modifier = Modifier.padding(bottom = bottomContentPadding),
                        dismissActivationDelayMillis = RaftFloatingOverlayClickTriggeredDismissDelayMillis
                    ) { _ ->
                        ServerSwitcherPanel(
                            servers = servers,
                            currentServerId = currentServerId,
                            pendingServerId = pendingServerId,
                            prefersChineseCommunity = prefersChineseCommunity,
                            labels = labels,
                            onServerSelected = onServerSelected,
                            onServerOrderChange = onServerOrderChange,
                            onCreateServerClick = onCreateServerClick,
                            onInviteHumanClick = onInviteHumanClick,
                            modifier =
                                Modifier
                                    .padding(
                                        top = homeServerSwitcherPanelTopOffset(topSafeArea, bellInColumn),
                                        start = RaftAppSpacing.Lg
                                    )
                                    .clickable(onClick = {})
                        )
                    }
                }
            }
            // Wide layout: the shell draws the popover under the top bar's bell.
            if (notificationCenterExpanded && topBarBell == null) {
                RaftFloatingOverlayHost(
                    onDismiss = { notificationCenterExpanded = false },
                    modifier = Modifier.padding(bottom = bottomContentPadding),
                    dismissActivationDelayMillis = RaftFloatingOverlayClickTriggeredDismissDelayMillis
                ) { _ ->
                    Box(Modifier.fillMaxSize()) {
                        HomeNotificationPopover(
                            visibleNotifications,
                            onNotificationView,
                            onNotificationDismiss,
                            if (bellInColumn) {
                                // Beside the column's bell, toward the content: its bottom edge level
                                // with the bell's, 8dp from the column (MobileNav's vertical tab bar
                                // popover; page-structure "Frame insets").
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(bottom = homeSideColumnBellPopoverBottomOffset(sideColumnPosture))
                                    .homeNotificationPopoverPhoneWidth()
                                    .raftFloatingContentClickTarget()
                            } else {
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = homeNotificationPopoverTopOffset(topSafeArea))
                                    .homeNotificationPopoverPhoneWidth()
                                    .raftFloatingContentClickTarget()
                            },
                            minWidth = 0.dp,
                            maxWidth = HomeNotificationPopoverPhoneMaxWidth
                        )
                    }
                }
            }
            activeSortMenu?.let { section ->
                val resolvedSortAnchor = sortMenuAnchor ?: return@let
                RaftBaseMenuOverlay(
                    anchor = resolvedSortAnchor,
                    items = raftHomeSortMenuItems(labels),
                    selectedId = systemSectionSortMode(section).wireValue,
                    onDismiss = { activeSortMenu = null },
                    onSelect = { selected ->
                        selectSystemSectionSortMode(section, RaftSidebarSortMode.fromWireValue(selected))
                        activeSortMenu = null
                    }
                )
            }
            activeSystemSectionMenu?.let { section ->
                val resolvedAnchor = systemSectionMenuAnchor ?: return@let
                RaftBaseMenuOverlay(
                    anchor = resolvedAnchor,
                    items =
                        raftHomeSectionMenuItems(
                            labels = labels,
                            sortMode = systemSectionSortMode(section),
                            hideEmptySections = hideEmptySections,
                            custom = false,
                            canCreateChannel = section == RaftHomeSortSection.Channels
                        ),
                    selectedId = "",
                    onDismiss = { activeSystemSectionMenu = null },
                    onSelect = { actionId ->
                        when {
                            actionId.startsWith("sort:") ->
                                selectSystemSectionSortMode(section, RaftSidebarSortMode.fromWireValue(actionId.removePrefix("sort:")))
                            actionId == "toggle-hide-empty" -> onHideEmptySectionsChange(!hideEmptySections)
                            actionId == "create" ->
                                sectionDialogTarget =
                                    RaftHomeSectionDialogTarget.Create(insertBeforeSectionId = section.sidebarSectionOrderId())
                            actionId == "create-channel" -> onCreateChannelClick()
                        }
                        activeSystemSectionMenu = null
                    }
                )
            }
            val activeConversationId = activePinMenuConversationId
            val activeConversation =
                activeConversationId?.let { id ->
                    channels.firstOrNull { it.id == id } ?: directMessages.firstOrNull { it.id == id }
                }
            val activeIsDirectMessage = activeConversation?.isDirectMessageConversation() == true
            val activeIsJoint = activeConversation?.homeChannelKind() == JointChannelKindWireValue
            val activeCanArchive =
                activeConversation != null &&
                    !activeIsDirectMessage &&
                    !activeIsJoint &&
                    activeConversation.name != "all" &&
                    !activeConversation.archived &&
                    canArchiveChannels
            val activeCanLeave =
                activeConversation != null &&
                    homeConversationCanLeave(
                        joined = activeConversation.joined,
                        archived = activeConversation.archived,
                        isDirectMessage = activeIsDirectMessage,
                        name = activeConversation.name
                    )
            val pinTargetIsPinned = activeConversationId != null && activeConversationId in pinnedIdSet
            val sortableFromMenu =
                when (activeMenuSection) {
                    RaftHomeSortSection.Pinned -> pinnedItemsForSort.size > 1
                    RaftHomeSortSection.Channels -> sortableDisplayedChannels.size > 1
                    RaftHomeSortSection.JointChannels -> displayedJointChannels.size > 1
                    RaftHomeSortSection.DirectMessages -> displayedDirectMessages.size > 1
                    null -> false
                }
            val pinMenuItems = activeConversation?.let { conversation ->
                raftHomeConversationMenuItems(
                    conversation = conversation,
                    labels = labels,
                    pinTargetIsPinned = pinTargetIsPinned,
                    sortableFromMenu = sortableFromMenu && sortOverlaySection == null,
                    activeIsDirectMessage = activeIsDirectMessage,
                    activeCanArchive = activeCanArchive,
                    activeCanLeave = activeCanLeave,
                    markAllReadEnabled = onMarkConversationReadAll != null
                )
            }
            if (activeConversationId != null && pinMenuItems != null) {
                val resolvedPinAnchor = pinMenuAnchor
                if (resolvedPinAnchor != null) {
                RaftBaseMenuOverlay(
                    anchor = resolvedPinAnchor,
                    items = pinMenuItems,
                    selectedId = "",
                    onDismiss = {
                        activePinMenuConversationId = null
                        onPinMenuDismiss()
                    },
                    onSelect = { actionId ->
                        when (actionId) {
                            "mark-read" -> onReadToggle(activeConversationId, false)
                            "mark-unread" -> onReadToggle(activeConversationId, true)
                            "mark-all-read" -> onMarkConversationReadAll?.invoke(activeConversationId)
                            "mute-channel" -> onChannelActivityMuteToggle(activeConversationId, true)
                            "unmute-channel" -> onChannelActivityMuteToggle(activeConversationId, false)
                            "toggle-pin" -> onPinToggle(activeConversationId)
                            "close-dm" -> onCloseDirectMessage(activeConversationId)
                            "archive-channel" -> onArchiveChannel(activeConversationId)
                            "leave-channel" -> onLeaveChannel(activeConversationId)
                            // task #512: opens the move-to-section submenu on the
                            // same anchor (pinMenuAnchor survives menu close).
                            "move-to-section" -> moveToSectionConversationId = activeConversationId
                            "sort-channels" -> {
                                sortOverlaySection = activeMenuSection
                            }
                        }
                        activePinMenuConversationId = null
                    }
                )
                }
            }
            if (activePinnedMemberMenuKey != null) {
                val resolvedPinAnchor = pinMenuAnchor
                if (resolvedPinAnchor != null) {
                RaftBaseMenuOverlay(
                    anchor = resolvedPinAnchor,
                    items = raftHomePinnedMemberMenuItems(labels),
                    selectedId = "",
                    onDismiss = {
                        activePinnedMemberMenuKey = null
                        onPinMenuDismiss()
                    },
                    onSelect = { actionId ->
                        if (actionId == "sort-channels") {
                            sortOverlaySection = RaftHomeSortSection.Pinned
                        }
                        activePinnedMemberMenuKey = null
                    }
                )
                }
            }
            // task #512: custom-section overlays — the header context menu, the
            // move-to-section submenu, the create/rename dialog and the delete
            // confirmation.
            val activeSectionMenuSection =
                activeSectionMenuId?.let { id -> customSections.firstOrNull { it.id == id } }
            if (activeSectionMenuSection != null) {
                val resolvedSectionAnchor = sectionMenuAnchor
                if (resolvedSectionAnchor != null) {
                    RaftBaseMenuOverlay(
                        anchor = resolvedSectionAnchor,
                        items =
                            raftHomeSectionMenuItems(
                                labels = labels,
                                sortMode = RaftSidebarSortMode.fromWireValue(activeSectionMenuSection.sortMode),
                                hideEmptySections = hideEmptySections
                            ),
                        selectedId = "",
                        onDismiss = { activeSectionMenuId = null },
                        onSelect = { actionId ->
                            val sectionId = activeSectionMenuSection.id
                            when {
                                actionId.startsWith("sort:") ->
                                    onSidebarSectionSortModeChange(
                                        sectionId,
                                        RaftSidebarSortMode.fromWireValue(actionId.removePrefix("sort:"))
                                    )
                                actionId == "toggle-hide-empty" -> onHideEmptySectionsChange(!hideEmptySections)
                                actionId == "rename" ->
                                    sectionDialogTarget = RaftHomeSectionDialogTarget.Edit(sectionId)
                                actionId == "create" ->
                                    sectionDialogTarget = RaftHomeSectionDialogTarget.Create(insertBeforeSectionId = sectionId)
                                actionId == "delete" -> sectionDeleteConfirmId = sectionId
                            }
                            activeSectionMenuId = null
                        }
                    )
                }
            }
            val moveToSectionTarget = moveToSectionConversationId
            if (moveToSectionTarget != null) {
                val resolvedMoveAnchor = pinMenuAnchor
                if (resolvedMoveAnchor != null) {
                    val moveTargetDm = directMessages.firstOrNull { it.id == moveToSectionTarget }
                    val moveTargetIsPinned = moveToSectionTarget in pinnedIdSet
                    val moveTargetCurrentSectionId =
                        sectionPlacements
                            .firstOrNull { placement ->
                                when (placement.kind) {
                                    SIDEBAR_SECTION_ITEM_KIND_CHANNEL -> placement.id == moveToSectionTarget
                                    SIDEBAR_SECTION_ITEM_KIND_AGENT ->
                                        moveTargetDm?.presenceMemberId != null &&
                                            placement.id == moveTargetDm?.presenceMemberId
                                    else -> false
                                }
                            }
                            ?.sectionId
                    RaftBaseMenuOverlay(
                        anchor = resolvedMoveAnchor,
                        items =
                            raftHomeMoveToSectionMenuItems(
                                customSections = customSections,
                                currentSectionId = moveTargetCurrentSectionId,
                                labels = labels,
                                pinned = moveTargetIsPinned
                            ),
                        selectedId = "",
                        onDismiss = { moveToSectionConversationId = null },
                        onSelect = { actionId ->
                            when {
                                // Web moveItemToPinned: pin it (pinning also drops
                                // any custom-section placement in the store).
                                actionId == "pinned" -> if (!moveTargetIsPinned) onPinToggle(moveToSectionTarget)
                                actionId == "create" ->
                                    sectionDialogTarget =
                                        RaftHomeSectionDialogTarget.Create(moveConversationId = moveToSectionTarget)
                                actionId.startsWith("section:") -> {
                                    val targetSectionId = actionId.removePrefix("section:").takeIf { it.isNotBlank() }
                                    if (targetSectionId == null || targetSectionId != moveTargetCurrentSectionId) {
                                        onMoveConversationToSection(moveToSectionTarget, targetSectionId)
                                    }
                                }
                            }
                            moveToSectionConversationId = null
                        }
                    )
                }
            }
            sectionDialogTarget?.let { target ->
                val editingSection =
                    (target as? RaftHomeSectionDialogTarget.Edit)
                        ?.let { edit -> customSections.firstOrNull { it.id == edit.sectionId } }
                if (target is RaftHomeSectionDialogTarget.Create || editingSection != null) {
                    RaftHomeSectionDialog(
                        title = if (editingSection != null) labels.editSectionTitle else labels.newSectionTitle,
                        submitLabel = if (editingSection != null) null else labels.createSection,
                        nameLabel = labels.sectionNameLabel,
                        namePlaceholder = labels.sectionNamePlaceholder,
                        initialName = editingSection?.name.orEmpty(),
                        initialEmoji = editingSection?.emoji.orEmpty(),
                        onDismiss = { sectionDialogTarget = null },
                        onSubmit = { name, emoji ->
                            if (editingSection != null) {
                                onSidebarSectionRename(editingSection.id, name, emoji)
                            } else if (target is RaftHomeSectionDialogTarget.Create) {
                                onSidebarSectionCreate(
                                    RaftHomeSectionCreateRequest(
                                        name = name,
                                        emoji = emoji,
                                        moveConversationId = target.moveConversationId,
                                        insertBeforeSectionId = target.insertBeforeSectionId
                                    )
                                )
                            }
                            sectionDialogTarget = null
                        }
                    )
                } else {
                    // The edited section disappeared (deleted from another
                    // surface / a 409 reload) — close the stale dialog.
                    LaunchedEffect(target) { sectionDialogTarget = null }
                }
            }
            val sectionDeleteSection =
                sectionDeleteConfirmId?.let { id -> customSections.firstOrNull { it.id == id } }
            if (sectionDeleteSection != null) {
                RaftUnifiedDialog(
                    title = labels.deleteSection,
                    onDismiss = { sectionDeleteConfirmId = null },
                    maxWidth = 384.dp
                ) {
                    Text(
                        text =
                            Res.string.home_section_delete_message
                                .format(listOf(sectionDeleteSection.name))
                                .localized(),
                        style = RaftType.Caption,
                        color = RaftColor.TextMutedGray
                    )
                    RaftDialogFooter {
                        RaftButton(
                            text = stringResource(Res.string.common_cancel),
                            onClick = { sectionDeleteConfirmId = null },
                            variant = RaftButtonVariant.Ghost,
                            size = RaftButtonSize.Lg
                        )
                        RaftButton(
                            text = stringResource(Res.string.home_section_delete_confirm),
                            onClick = {
                                onSidebarSectionDelete(sectionDeleteSection.id)
                                sectionDeleteConfirmId = null
                            },
                            leadingIcon = { RaftSlotIcon(RaftLucideIconName.Trash2) },
                            variant = RaftButtonVariant.Danger,
                            size = RaftButtonSize.Lg
                        )
                    }
                }
            }
            sortOverlaySection?.let { section ->
                val sortItems =
                    when (section) {
                        RaftHomeSortSection.Pinned -> pinnedItemsForSort
                        RaftHomeSortSection.JointChannels -> displayedJointChannels
                        RaftHomeSortSection.DirectMessages -> displayedDirectMessages
                        RaftHomeSortSection.Channels -> sortableDisplayedChannels
                    }
                RaftSidebarSortOverlay(
                    title = when (section) {
                        RaftHomeSortSection.Pinned -> labels.pinned
                        RaftHomeSortSection.JointChannels -> labels.jointChannels
                        RaftHomeSortSection.DirectMessages -> labels.directMessages
                        RaftHomeSortSection.Channels -> labels.channels
                    },
                    items = sortItems,
                    saveLabel = labels.saveSort,
                    onDismiss = { sortOverlaySection = null },
                    cacheServerId = currentServerId,
                    agentStatusResolver = agentStatusResolver,
                    memberAvatarResolver = memberAvatarResolver,
                    onSave = { orderedIds ->
                        sortOverlaySection = null
                        val initialIds = sortItems.map { it.id }
                        if (orderedIds != initialIds) {
                            when (section) {
                                RaftHomeSortSection.Pinned -> onSidebarOrderChange(RaftSidebarSortTarget.Pinned, orderedIds)
                                RaftHomeSortSection.JointChannels -> onSidebarOrderChange(RaftSidebarSortTarget.JointChannels, orderedIds)
                                RaftHomeSortSection.DirectMessages -> onSidebarOrderChange(RaftSidebarSortTarget.DirectMessages, orderedIds)
                                RaftHomeSortSection.Channels -> onSidebarOrderChange(RaftSidebarSortTarget.Channels, orderedIds)
                            }
                        }
                    }
                )
            }
        }
        if (showBottomBar && !bottomBarOverlaysContent) {
            RaftHomePreviewBottomTabBar(
                selectedTabKey = selectedTabKey,
                onSelect = onBottomTabSelected,
                modifier = Modifier.fillMaxWidth(),
                bottomSafeArea = bottomSafeArea
            )
        }
        }
        if (bottomBarOverlaysContent) {
            RaftHomePreviewBottomTabBar(
                selectedTabKey = selectedTabKey,
                onSelect = onBottomTabSelected,
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                bottomSafeArea = bottomSafeArea
            )
        }
    }
}

/** Stand-in for the app-shell bottom bar (visual fixtures): the same phone tabs and Search action. */
@Composable
private fun RaftHomePreviewBottomTabBar(
    selectedTabKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier,
    bottomSafeArea: Dp,
) {
    val homeTabLabel = stringResource(Res.string.tab_home)
    val activityTabLabel = stringResource(Res.string.tab_activity)
    val tasksTabLabel = stringResource(Res.string.tab_tasks)
    val moreTabLabel = stringResource(Res.string.tab_more)
    val searchLabel = stringResource(Res.string.home_nav_search)
    val items = remember(selectedTabKey, homeTabLabel, activityTabLabel, searchLabel, tasksTabLabel, moreTabLabel) {
        appShellPhoneBottomTabs(
            selectedTab = selectedTabKey,
            homeLabel = homeTabLabel,
            activityLabel = activityTabLabel,
            searchLabel = searchLabel,
            tasksLabel = tasksTabLabel,
            moreLabel = moreTabLabel,
        )
    }
    RaftAppBottomTabBarHost(
        items = items,
        onSelect = onSelect,
        modifier = modifier,
        bottomSafeArea = bottomSafeArea
    )
}

private fun Dp.max(other: Dp): Dp =
    if (this >= other) this else other

internal fun sharedListBeyondBoundsItemCount(itemCount: Int): Int =
    itemCount.coerceAtLeast(0).coerceAtMost(SharedListMaxBeyondBoundsItemCount)

internal fun homeListBeyondBoundsItemCount(
    itemCount: Int,
    viewportHeightDp: Float,
    representativeRowStepDp: Float = HomeListRepresentativeRowStepDp
): Int =
    raftViewportBeyondBoundsItemCount(
        itemCount = itemCount,
        viewportHeightDp = viewportHeightDp,
        representativeRowStepDp = representativeRowStepDp,
        minimumItems = HomeListMinBeyondBoundsItemCount,
        maximumItems = HomeListMaxBeyondBoundsItemCount,
        viewportFraction = HomeListOverscanViewportFraction
    )

internal fun homeListInitialBeyondBoundsItemCount(): Int = 0

internal fun homeListShouldRampBeyondBounds(contentRefreshing: Boolean): Boolean = !contentRefreshing

internal fun homeListNextServerSwitchBeyondBoundsItemCount(current: Int, target: Int): Int =
    if (current <= 0) {
        HomeListMinBeyondBoundsItemCount.coerceAtMost(target.coerceAtLeast(0))
    } else {
        (current + HomeListServerSwitchRampStep).coerceAtMost(target.coerceAtLeast(0))
    }

internal fun homeSidebarSlotKey(section: String, index: Int): String =
    "home-$section-slot-$index"

internal fun homeSidebarInteractionIdentity(section: String, identity: String): String =
    "$section:$identity"

internal fun homeListStateOwnerMatches(snapshotServerId: String?, listStateServerId: String?): Boolean =
    snapshotServerId?.trim()?.takeIf { it.isNotEmpty() } ==
        listStateServerId?.trim()?.takeIf { it.isNotEmpty() }

internal fun shouldRestoreHomeListPosition(
    listStateServerId: String?,
    listPositionServerId: String?,
    restoreStartUserScrollGeneration: Int,
    currentUserScrollGeneration: Int,
    isScrollInProgress: Boolean
): Boolean =
    listStateServerId != listPositionServerId &&
        restoreStartUserScrollGeneration == currentUserScrollGeneration &&
        !isScrollInProgress

private class HomeUserScrollGeneration(
    var value: Int = 0
)

internal class HomeSidebarLazyKeyRegistry {
    private data class SectionKeys(
        val serverId: String,
        val keysByIdentity: Map<String, String>
    )

    private val sectionKeys = mutableMapOf<String, SectionKeys>()

    fun keysFor(serverId: String, section: String, identities: List<String>): List<String> {
        val normalizedServerId = serverId.trim()
        val previous = sectionKeys[section]
        val keys =
            if (previous == null || previous.serverId != normalizedServerId) {
                identities.mapIndexed { index, _ -> homeSidebarSlotKey(section, index) }
            } else {
                identities.map { identity ->
                    previous.keysByIdentity[identity]
                        ?: "home-$section-identity-$identity"
                }
            }
        sectionKeys[section] =
            SectionKeys(
                serverId = normalizedServerId,
                keysByIdentity = identities.zip(keys).toMap()
            )
        return keys
    }
}

internal fun homeServerSwitcherBeyondBoundsItemCount(itemCount: Int): Int =
    itemCount.coerceAtLeast(0).coerceAtMost(HomeServerSwitcherMaxBeyondBoundsItemCount)

internal fun homeLazyItemCount(
    channelsCount: Int,
    jointCount: Int,
    directMessagesCount: Int,
    pinnedCount: Int = 0,
    channelsExpanded: Boolean,
    jointChannelsExpanded: Boolean,
    directMessagesExpanded: Boolean,
    pinnedExpanded: Boolean = false,
    hasInlineError: Boolean = false,
    channelSkeletonCount: Int = 0,
    directMessageSkeletonCount: Int = 0
): Int {
    val fixedItems =
        1 + // top spacer
            1 + // search
            1 + // activity
            1 + // saved
            1 + // channels header
            1 + // direct messages header
            1 // bottom spacer
    val jointSectionItems = if (jointCount > 0) 1 else 0 // header
    val pinnedSectionItems = if (pinnedCount > 0) 1 else 0 // header; rows counted conditionally
    return fixedItems +
        jointSectionItems +
        pinnedSectionItems +
        (if (hasInlineError) 1 else 0) +
        channelSkeletonCount.coerceAtLeast(0) +
        directMessageSkeletonCount.coerceAtLeast(0) +
        (if (channelsExpanded) channelsCount else 0) +
        (if (jointChannelsExpanded) jointCount else 0) +
        (if (directMessagesExpanded) directMessagesCount else 0) +
        (if (pinnedExpanded) pinnedCount else 0)
}

/** One top-level block of the home sidebar list. */
internal sealed interface RaftHomeSidebarSection {
    object Pinned : RaftHomeSidebarSection
    object JointChannels : RaftHomeSidebarSection
    object Channels : RaftHomeSidebarSection
    object DirectMessages : RaftHomeSidebarSection
    data class Custom(val rows: RaftHomeCustomSectionRows) : RaftHomeSidebarSection
}

/**
 * The home sidebar's top-level blocks in render order. Mirrors web Sidebar.tsx,
 * which renders every system block (`system:pinned` / `system:joint` /
 * `system:channels` / `system:dms`) and every custom section with CSS
 * `order = sectionOrder.indexOf(id)`, where sectionOrder is web
 * `normalizeSidebarSectionOrder` output: so system and custom sections
 * interleave in the user's order, and ids missing from it fall back to system
 * sections in canonical order, then custom sections in declaration order.
 * Visibility follows web's "Hide Empty Sections" display preference
 * (sidebarEmptySectionVisibility.ts): when on, empty Pinned / Joint Channels /
 * DMs drop out; Channels always shows (it carries the empty-state hint) and so
 * does every custom section in [customSectionRows], empty or not.
 */
internal fun homeSidebarSectionOrder(
    sectionOrder: List<String>,
    customSections: List<SettingsServerSidebarCustomSection>,
    customSectionRows: List<RaftHomeCustomSectionRows>,
    hasPinned: Boolean,
    hasJointChannels: Boolean,
    hasDirectMessages: Boolean = true,
    hideEmptySections: Boolean = true
): List<RaftHomeSidebarSection> {
    val rowsBySectionId = customSectionRows.associateBy { it.section.id }
    return normalizeSidebarSectionOrder(sectionOrder, customSections).mapNotNull { id ->
        when (id) {
            "system:pinned" -> RaftHomeSidebarSection.Pinned.takeIf { hasPinned || !hideEmptySections }
            "system:joint" -> RaftHomeSidebarSection.JointChannels.takeIf { hasJointChannels || !hideEmptySections }
            "system:channels" -> RaftHomeSidebarSection.Channels
            "system:dms" -> RaftHomeSidebarSection.DirectMessages.takeIf { hasDirectMessages || !hideEmptySections }
            else -> rowsBySectionId[id]?.let { RaftHomeSidebarSection.Custom(it) }
        }
    }
}

private enum class RaftHomeSortSection { Channels, JointChannels, DirectMessages, Pinned }

/** The section's id in web's `sectionOrder` (`system:*`). */
private fun RaftHomeSortSection.sidebarSectionOrderId(): String =
    when (this) {
        RaftHomeSortSection.Pinned -> "system:pinned"
        RaftHomeSortSection.JointChannels -> "system:joint"
        RaftHomeSortSection.Channels -> "system:channels"
        RaftHomeSortSection.DirectMessages -> "system:dms"
    }

internal fun raftHomeConversationMenuItems(
    conversation: RaftHomeConversationItem,
    labels: RaftHomeShellLabels = RaftHomeShellLabels(),
    pinTargetIsPinned: Boolean,
    sortableFromMenu: Boolean,
    activeIsDirectMessage: Boolean,
    activeCanArchive: Boolean,
    /** Web Sidebar `canLeaveChannel`: see [homeConversationCanLeave]. */
    activeCanLeave: Boolean = false,
    canMoveToSection: Boolean = true,
    /** Both `thread_unread_badge_v0` and `sidebar_mark_all_read_v0` are on for this server. */
    markAllReadEnabled: Boolean = false
): List<RaftBaseMenuItem> =
    buildList {
        // Web parity (Sidebar.tsx row context menu): "Move to Section" leads,
        // then read / pin / mute, then close (DMs) or archive / leave (channels).
        if (canMoveToSection) {
            add(
                RaftBaseMenuItem(
                    id = "move-to-section",
                    label = labels.moveToSection,
                    icon = RaftLucideIconName.FolderInput
                )
            )
        }
        // Web SidebarReadToggleMenuItem: a joined channel reads with its followed threads ("Mark All
        // as Read") once both gates are on, and thread-only unread then also counts as unread.
        // DMs and unjoined channels keep the single-scope toggle.
        val readConversation = markAllReadEnabled && conversation.joined && !activeIsDirectMessage
        val hasUnread =
            conversation.homeConversationBadgeProjection().hasUnread ||
                (readConversation && conversation.followedThreadUnreadCount > 0)
        add(
            RaftBaseMenuItem(
                id =
                    when {
                        !hasUnread -> "mark-unread"
                        readConversation -> "mark-all-read"
                        else -> "mark-read"
                    },
                label =
                    when {
                        !hasUnread -> labels.markAsUnread
                        readConversation -> labels.markAllAsRead
                        else -> labels.markAsRead
                    },
                icon = if (hasUnread) RaftLucideIconName.MailOpen else RaftLucideIconName.Mail,
                dividerBefore = canMoveToSection
            )
        )
        add(
            RaftBaseMenuItem(
                id = "toggle-pin",
                label = if (pinTargetIsPinned) labels.unpin else labels.pin,
                icon = if (pinTargetIsPinned) RaftLucideIconName.PinOff else RaftLucideIconName.Pin
            )
        )
        // Unmute stays offered while muted even if mute is no longer supported (removed App bot DM).
        if (conversation.activityMuteSupported || conversation.activityMuted) {
            add(
                RaftBaseMenuItem(
                    id = if (conversation.activityMuted) "unmute-channel" else "mute-channel",
                    label = if (conversation.activityMuted) labels.unmute else labels.mute,
                    icon = if (conversation.activityMuted) RaftLucideIconName.Bell else RaftLucideIconName.BellOff
                )
            )
        }
        // Mobile-only: the manual reorder overlay stands in for web drag-and-drop.
        if (sortableFromMenu) {
            add(
                RaftBaseMenuItem(
                    id = "sort-channels",
                    label = labels.sortChannels,
                    icon = RaftLucideIconName.ArrowUpDown
                )
            )
        }
        if (activeIsDirectMessage) {
            add(RaftBaseMenuItem(id = "close-dm", label = labels.closeChat, icon = RaftLucideIconName.X, dividerBefore = true))
        } else if (activeCanArchive) {
            add(
                RaftBaseMenuItem(
                    id = "archive-channel",
                    label = labels.archive,
                    icon = RaftLucideIconName.Archive,
                    dividerBefore = true
                )
            )
        }
        // botiverse/slock #11289: Leave sits under Archive, sharing its divider.
        if (!activeIsDirectMessage && activeCanLeave) {
            add(
                RaftBaseMenuItem(
                    id = "leave-channel",
                    label = labels.leaveChannel,
                    icon = RaftLucideIconName.LogOut,
                    dividerBefore = !activeCanArchive
                )
            )
        }
    }

internal fun raftHomePinnedMemberMenuItems(
    labels: RaftHomeShellLabels = RaftHomeShellLabels()
): List<RaftBaseMenuItem> =
    listOf(
        RaftBaseMenuItem(
            id = "sort-channels",
            label = labels.sortChannels,
            icon = RaftLucideIconName.ArrowUpDown
        )
    )

/**
 * Section header menu (long-press on a section header, the mobile stand-in
 * for web's right-click). Mirrors web Sidebar.tsx's section context menu:
 * [create channel (Channels only)] / Sort: Manual, Recent, A-Z (current one
 * checked) / Display: Hide Empty Sections (checked when on) / [rename] /
 * New Section… / [delete]. Rename and delete only exist on custom sections.
 * Web also offers "Create Joint Channel" on the Joint Channels menu; mobile
 * has no create-joint-channel flow yet, so it is omitted.
 */
internal fun raftHomeSectionMenuItems(
    labels: RaftHomeShellLabels = RaftHomeShellLabels(),
    sortMode: RaftSidebarSortMode = RaftSidebarSortMode.Manual,
    hideEmptySections: Boolean = false,
    custom: Boolean = true,
    canCreateChannel: Boolean = false
): List<RaftBaseMenuItem> =
    buildList {
        if (canCreateChannel) {
            add(RaftBaseMenuItem(id = "create-channel", label = labels.createChannel, icon = RaftLucideIconName.Plus))
        }
        RaftSidebarSortMode.entries.forEachIndexed { index, mode ->
            add(
                RaftBaseMenuItem(
                    id = "sort:${mode.wireValue}",
                    label =
                        when (mode) {
                            RaftSidebarSortMode.Manual -> labels.sortManual
                            RaftSidebarSortMode.Recent -> labels.sortRecent
                            RaftSidebarSortMode.Az -> labels.sortAz
                        },
                    checked = mode == sortMode,
                    dividerBefore = index == 0 && canCreateChannel,
                    groupLabel = if (index == 0) labels.sortMenuTitle else null
                )
            )
        }
        add(
            RaftBaseMenuItem(
                id = "toggle-hide-empty",
                label = labels.hideEmptySections,
                checked = hideEmptySections,
                dividerBefore = true,
                groupLabel = labels.displaySection
            )
        )
        if (custom) {
            add(RaftBaseMenuItem(id = "rename", label = labels.renameSection, icon = RaftLucideIconName.Pencil, dividerBefore = true))
        }
        add(
            RaftBaseMenuItem(
                id = "create",
                label = labels.newSectionMenu,
                icon = RaftLucideIconName.FolderPlus,
                dividerBefore = !custom
            )
        )
        if (custom) {
            add(RaftBaseMenuItem(id = "delete", label = labels.deleteSection, icon = RaftLucideIconName.Trash2, dividerBefore = true))
        }
    }

/**
 * "Move to section" submenu. Mirrors web Sidebar.tsx: Pinned (checked and
 * disabled when the conversation is already pinned), one row per custom
 * section (the current one checked), then New Section… and — only when the
 * conversation sits in a custom section — Remove from Section. Item ids carry
 * the target: `pinned`, `section:<id>`, `create`, and the bare `section:` for
 * "back to the default section".
 */
internal fun raftHomeMoveToSectionMenuItems(
    customSections: List<SettingsServerSidebarCustomSection>,
    currentSectionId: String?,
    labels: RaftHomeShellLabels = RaftHomeShellLabels(),
    pinned: Boolean = false
): List<RaftBaseMenuItem> =
    buildList {
        add(
            RaftBaseMenuItem(
                id = "pinned",
                label = labels.pinnedTarget,
                icon = RaftLucideIconName.Pin,
                checked = pinned,
                enabled = !pinned
            )
        )
        customSections.forEach { section ->
            add(
                RaftBaseMenuItem(
                    id = "section:${section.id}",
                    label = section.sidebarSectionHeaderLabel(),
                    icon = RaftLucideIconName.FolderInput,
                    checked = section.id == currentSectionId
                )
            )
        }
        add(RaftBaseMenuItem(id = "create", label = labels.newSectionMenu, icon = RaftLucideIconName.FolderPlus, dividerBefore = true))
        if (currentSectionId != null) {
            add(RaftBaseMenuItem(id = "section:", label = labels.removeFromSection, icon = RaftLucideIconName.X))
        }
    }

/** A "New section…" submission: the section, plus where it came from. */
data class RaftHomeSectionCreateRequest(
    val name: String,
    val emoji: String?,
    /** The row whose Move to Section > New Section… opened the dialog; it moves into the new section. */
    val moveConversationId: String? = null,
    /** sectionOrder id of the section whose header menu opened the dialog; the new section goes before it. */
    val insertBeforeSectionId: String? = null
)

/**
 * Web `SidebarSectionDescription` hint under an empty, expanded section: `px-2 py-1.5` inside
 * the list's `px-2`, so its words start under the section chevron (16dp from the edge).
 */
@Composable
private fun HomeSidebarSectionEmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = RaftType.Caption,
        color = RaftColor.TextMutedGray,
        modifier = modifier.fillMaxWidth().padding(horizontal = RaftAppSpacing.Sm, vertical = HomeSidebarSectionEmptyHintPaddingVertical)
    )
}

/** task #512: create/rename target for the custom-section dialog. */
private sealed interface RaftHomeSectionDialogTarget {
    data class Create(
        val moveConversationId: String? = null,
        val insertBeforeSectionId: String? = null
    ) : RaftHomeSectionDialogTarget

    data class Edit(val sectionId: String) : RaftHomeSectionDialogTarget
}

enum class RaftSidebarSortTarget { Channels, JointChannels, DirectMessages, Pinned }

internal fun RaftSidebarSortTarget.toReorderSidebarIntent(order: List<String>): SharedHomeIntent.ReorderSidebar =
    when (this) {
        RaftSidebarSortTarget.Channels -> SharedHomeIntent.ReorderSidebar(channelOrder = order)
        RaftSidebarSortTarget.JointChannels -> SharedHomeIntent.ReorderSidebar(jointChannelOrder = order)
        RaftSidebarSortTarget.DirectMessages -> SharedHomeIntent.ReorderSidebar(dmOrder = order)
        RaftSidebarSortTarget.Pinned -> SharedHomeIntent.ReorderSidebar(pinnedOrder = order)
    }

internal fun raftHomeSortMenuItems(labels: RaftHomeShellLabels = RaftHomeShellLabels()): List<RaftBaseMenuItem> =
    RaftSidebarSortMode.entries.map { mode ->
        RaftBaseMenuItem(
            id = mode.wireValue,
            label =
                when (mode) {
                    RaftSidebarSortMode.Manual -> labels.sortManual
                    RaftSidebarSortMode.Recent -> labels.sortRecent
                    RaftSidebarSortMode.Az -> labels.sortAz
                }
        )
    }

internal fun homeActivitySidebarUnreadCount(
    activityUnreadCount: Int
): Int =
    activityUnreadCount.coerceAtLeast(0)

internal fun homeActivitySidebarTotalCount(
    activityUnreadCount: Int,
    activityTotalCount: Int
): Int =
    activityTotalCount.takeIf { activityUnreadCount <= 0 && it > 0 } ?: 0

internal fun formatHomeSidebarBadgeCount(count: Int): String? =
    formatHomeSidebarBadgeCount(count.toLong())

internal fun formatHomeSidebarBadgeCount(count: Long): String? =
    when {
        count <= 0 -> null
        count >= 100 -> "99+"
        else -> count.toString()
    }

internal fun homeConversationBadgeProjection(input: HomeConversationBadgeInput): HomeSidebarBadgeProjection {
    val label = formatHomeSidebarBadgeCount(input.unreadCount)
    val loud =
        input.joined &&
            (!input.muted || input.mentionPierced || input.followed)
    if (label == null) {
        // web SidebarPeerRowBody / getChannelUnreadIndicatorState: marked unread with no count is
        // unread (the row's menu offers Mark as Read) with the quiet dot; a count wins over it.
        if (!input.markedUnread) return HomeSidebarBadgeProjection(label = null)
        return HomeSidebarBadgeProjection(
            label = null,
            tone = if (loud) HomeSidebarBadgeTone.Loud else HomeSidebarBadgeTone.Quiet,
            hasUnread = true,
            hasAttention = loud,
            markedUnreadDot = true
        )
    }
    return HomeSidebarBadgeProjection(
        label = label,
        tone = if (loud) HomeSidebarBadgeTone.Loud else HomeSidebarBadgeTone.Quiet,
        hasUnread = true,
        hasAttention = loud
    )
}

internal fun RaftHomeConversationItem.homeConversationBadgeProjection(
    muted: Boolean = activityMuted,
    mentionPierced: Boolean = false,
    followed: Boolean = false
): HomeSidebarBadgeProjection =
    homeConversationBadgeProjection(
        HomeConversationBadgeInput(
            unreadCount = unreadCount,
            joined = joined,
            muted = muted,
            mentionPierced = mentionPierced,
            followed = followed,
            markedUnread = markedUnread
        )
    )

internal fun homeActivitySidebarProjection(
    activityUnreadCount: Int,
    activityTotalCount: Int
): HomeActivitySidebarProjection =
    HomeActivitySidebarProjection(
        unreadCount = homeActivitySidebarUnreadCount(activityUnreadCount),
        totalLabel = formatHomeSidebarBadgeCount(homeActivitySidebarTotalCount(activityUnreadCount, activityTotalCount))
    )

internal fun homeServerSwitcherHasOtherUnread(
    currentServerId: String,
    servers: List<RaftServerSwitcherItem>
): Boolean =
    servers.any { server ->
        server.id != currentServerId &&
            homeServerSwitcherBadgePresentation(server).kind != HomeServerSwitcherBadgeKind.None
    }

private const val DefaultCommunityServerSlug = "community"
private const val ChineseCommunityServerSlug = "community-cn"

internal data class RaftServerSwitcherCommunityAction(
    val slug: String,
    val label: String
)

internal fun homeServerSwitcherHasJoinedCommunity(
    servers: List<RaftServerSwitcherItem>,
    slug: String
): Boolean =
    servers.any { it.slug == slug }

internal fun homeServerSwitcherJoinCommunityActions(
    servers: List<RaftServerSwitcherItem>,
    labels: RaftHomeShellLabels,
    includeChineseCommunity: Boolean
): List<RaftServerSwitcherCommunityAction> =
    buildList {
        if (
            includeChineseCommunity &&
            !homeServerSwitcherHasJoinedCommunity(servers, ChineseCommunityServerSlug)
        ) {
            add(RaftServerSwitcherCommunityAction(ChineseCommunityServerSlug, labels.joinChineseCommunityServer))
        }
        if (!homeServerSwitcherHasJoinedCommunity(servers, DefaultCommunityServerSlug)) {
            add(RaftServerSwitcherCommunityAction(DefaultCommunityServerSlug, labels.joinCommunityServer))
        }
    }

internal fun homeServerSwitcherPanelTopOffset(topSafeArea: Dp, sideColumnTitleLine: Boolean = false): Dp =
    homeShellHeaderHeight(topSafeArea, sideColumnTitleLine) + HomeServerSwitcherTopGap

/** The Home head: the 56dp row, on the yellow frame, or beside the Duo side column on paper. */
internal fun homeShellHeaderHeight(topSafeArea: Dp, sideColumnTitleLine: Boolean = false): Dp =
    topSafeArea + RaftAppSize.PageHeaderHeight

/**
 * From the window's bottom to the bottom of the bell's popover beside the Duo side column: level
 * with the bell's bottom, which stands [RaftSideActionColumnDefaults.Inset] over the native tab bar.
 */
internal fun homeSideColumnBellPopoverBottomOffset(posture: RaftSideColumnPosture): Dp =
    posture.nativeTabBarHeight + RaftSideActionColumnDefaults.Inset

/** Home's one page action in the Duo side column: the bell, its pixel for unseen notifications. */
@Composable
private fun homeSideColumnBellAction(
    notifications: List<RaftHomeNotificationItem>,
    onToggle: () -> Unit,
): RaftSideAction {
    val visible = remember(notifications) { notifications.visibleHomeNotifications() }
    val attentionDescription =
        when (visible.size) {
            0 -> null
            1 -> Res.string.home_notification_center_item_count_one.format(listOf(1)).localized()
            else -> Res.string.home_notification_center_item_count_many.format(listOf(visible.size)).localized()
        }
    return raftSideColumnLucideAction(
        id = "home-bell",
        label = stringResource(Res.string.home_notification_center_title),
        icon = RaftLucideIconName.Bell,
        onClick = onToggle,
        attention = visible.isNotEmpty(),
        attentionDescription = attentionDescription,
        // The iOS host may draw Home's column (the bell alone) with a system bar button.
        systemSymbol = "bell",
    )
}

internal fun homeShellTopSafeArea(
    applyTopSafeArea: Boolean,
    resolvedTopSafeArea: Dp,
    topSafeAreaOverride: Dp?,
): Dp =
    topSafeAreaOverride ?: if (applyTopSafeArea) resolvedTopSafeArea else 0.dp

internal fun homeServerSwitcherServerListMaxHeight(serverCount: Int): Dp? {
    if (serverCount <= HomeServerSwitcherMaxVisibleRows) return null
    return (HomeServerSwitcherRowMinHeight.value * HomeServerSwitcherMaxVisibleRows).dp
}

internal data class RaftHomeSidebarSelection(
    val topAction: RaftHomeSidebarTopSelection? = null,
    val conversationId: String? = null,
    val conversationSection: RaftHomeConversationSection? = null
)

internal data class RaftHomeSectionSelection(
    val section: RaftHomeConversationSection,
    val selectedId: String?
)

internal fun homeSidebarSelection(
    selectedChannelId: String?,
    selectedConversationSection: RaftHomeConversationSection?,
    channels: List<RaftHomeConversationItem>,
    directMessages: List<RaftHomeConversationItem>,
    searchSelected: Boolean,
    activitySelected: Boolean,
    savedSelected: Boolean,
    pinnedChannelIds: Set<String> = emptySet()
): RaftHomeSidebarSelection =
    when {
        searchSelected -> RaftHomeSidebarSelection(topAction = RaftHomeSidebarTopSelection.Search)
        activitySelected -> RaftHomeSidebarSelection(topAction = RaftHomeSidebarTopSelection.Activity)
        savedSelected -> RaftHomeSidebarSelection(topAction = RaftHomeSidebarTopSelection.Saved)
        selectedChannelId != null -> {
            val section =
                selectedConversationSection
                    ?: homeConversationSectionForSelection(
                        selectedChannelId = selectedChannelId,
                        channels = channels,
                        directMessages = directMessages,
                        pinnedChannelIds = pinnedChannelIds
                    )
            RaftHomeSidebarSelection(
                conversationId = selectedChannelId,
                conversationSection = section
            )
        }
        else -> RaftHomeSidebarSelection()
    }

internal fun homeConversationSectionForSelection(
    selectedChannelId: String,
    channels: List<RaftHomeConversationItem>,
    directMessages: List<RaftHomeConversationItem>,
    pinnedChannelIds: Set<String> = emptySet()
): RaftHomeConversationSection? {
    // #211: a pinned id should always resolve to the Pinned section, even
    // if it is still present in the raw `channels` / `directMessages` lists
    // (server returns the full set, the visual layer hides the original
    // copies). Otherwise the section-aware selection would point at the
    // Channel / JointChannel / DirectMessage row and the Pinned row would
    // not be highlighted, leaving the user with two un-highlighted sections
    // and one phantom highlight in the original section.
    if (selectedChannelId in pinnedChannelIds) {
        return RaftHomeConversationSection.Pinned
    }
    val channel = channels.firstOrNull { it.id == selectedChannelId }
    if (channel != null) {
        return if (channel.homeChannelKind() == JointChannelKindWireValue) {
            RaftHomeConversationSection.JointChannel
        } else {
            RaftHomeConversationSection.Channel
        }
    }
    return if (directMessages.any { it.id == selectedChannelId }) {
        RaftHomeConversationSection.DirectMessage
    } else {
        null
    }
}

internal fun RaftHomeConversationItem.homeChannelKind(): String =
    when (parseConversationKindOrNull(kind)) {
        ConversationKind.DirectMessage -> RaftThreadSurfaceKind.DirectMessage
        ConversationKind.Joint -> JointChannelKindWireValue
        else -> {
            channelType?.takeIf { it.isNotBlank() }?.lowercase()
                ?: kind.lowercase()
        }
    }

internal fun RaftHomeConversationItem.isDirectMessageConversation(): Boolean =
    parseConversationKindOrNull(kind) == ConversationKind.DirectMessage

internal fun RaftHomeConversationItem.homeChannelSymbol(): String =
    homeChannelKind().toRaftChannelSymbol()

/**
 * Row glyph symbol including the public-page mark: a regular channel readable on the server's
 * live public page draws the hash-globe glyph in place of `#` (web channelKindIcon.tsx:22).
 * Interaction identities keep using the plain [homeChannelSymbol] so they never change with it.
 */
internal fun RaftHomeConversationItem.homeChannelSymbol(serverPublicPageLive: Boolean): String =
    if (
        channelOnPublicPage(
            serverPublicPageLive = serverPublicPageLive,
            channelKind = homeChannelKind(),
            guestVisible = guestVisible,
            archived = archived,
        )
    ) {
        HomeChannelSymbolHashGlobe
    } else {
        homeChannelSymbol()
    }

/**
 * The same row glyph symbol for a Home snapshot channel row, read through the sidebar item's
 * kind / channelType / guestVisible / archived (as `toSharedHomeConversationItem` maps them), so
 * other surfaces (Activity) pick exactly the icon the Home sidebar draws.
 */
internal fun HomeChannelRow.homeChannelSymbol(serverPublicPageLive: Boolean): String =
    RaftHomeConversationItem(
        id = channelId.value,
        kind = kind.toWireChannelKindOrNull() ?: kind.toRaftSurfaceKind(),
        channelType = channelType?.takeIf { it.isNotBlank() },
        guestVisible = guestVisible,
        archived = archived,
    ).homeChannelSymbol(serverPublicPageLive)

internal const val HomeChannelSymbolHashGlobe = "hash-globe"

internal fun String?.toRaftChannelSymbol(): String =
    when (this?.lowercase()) {
        "private" -> "lock"
        JointChannelKindWireValue -> "git-branch"
        else -> "#"
    }

internal fun RaftHomeSidebarSelection.sectionSelection(
    section: RaftHomeConversationSection
): RaftHomeSectionSelection =
    RaftHomeSectionSelection(
        section = section,
        selectedId = conversationId.takeIf { conversationSection == section }
    )

internal fun RaftHomeSectionSelection.selects(id: String): Boolean =
    selectedId == id

internal fun List<RaftHomeConversationItem>.sortedForSidebar(
    mode: RaftSidebarSortMode,
    pinnedFirstId: String? = null,
    label: (RaftHomeConversationItem) -> String
): List<RaftHomeConversationItem> {
    if (mode == RaftSidebarSortMode.Manual) return withPinnedFirst(pinnedFirstId)
    val pinned = pinnedFirstId?.let { id -> firstOrNull { it.id == id } }
    val sortable = pinned?.let { item -> filterNot { it.id == item.id } } ?: this
    val sorted =
        when (mode) {
            RaftSidebarSortMode.Manual -> sortable
            RaftSidebarSortMode.Recent ->
                sortable.sortedWith(
                    compareByDescending<RaftHomeConversationItem> {
                        parseRaftTimestampEpochMillisOrNull(it.lastMessageAt ?: it.createdAt) ?: Long.MIN_VALUE
                    }.thenBy { label(it).trim().lowercase() }.thenBy { it.id }
                )
            RaftSidebarSortMode.Az ->
                sortable.sortedWith(compareBy<RaftHomeConversationItem> { label(it).trim().lowercase() }.thenBy { it.id })
        }
    return if (pinned != null) listOf(pinned) + sorted else sorted
}

internal fun List<RaftHomeConversationItem>.orderedBySidebarIds(
    order: List<String>,
    pinnedFirstId: String? = null
): List<RaftHomeConversationItem> {
    if (isEmpty()) return this
    if (order.isEmpty()) return withPinnedFirst(pinnedFirstId)
    val byId = associateBy { it.id }
    val ordered = LinkedHashMap<String, RaftHomeConversationItem>()
    pinnedFirstId?.let { id ->
        byId[id]?.let { ordered[it.id] = it }
    }
    order.forEach { id ->
        if (id == pinnedFirstId) return@forEach
        byId[id]?.let { ordered[it.id] = it }
    }
    forEach { item ->
        if (!ordered.containsKey(item.id)) {
            ordered[item.id] = item
        }
    }
    return ordered.values.toList()
}

private fun List<RaftHomeConversationItem>.withPinnedFirst(pinnedFirstId: String?): List<RaftHomeConversationItem> {
    if (pinnedFirstId.isNullOrBlank() || size < 2 || firstOrNull()?.id == pinnedFirstId) return this
    val pinned = firstOrNull { it.id == pinnedFirstId } ?: return this
    return listOf(pinned) + filterNot { it.id == pinnedFirstId }
}

internal fun List<RaftHomeConversationItem>.withoutSidebarPinnedItem(
    pinnedFirstId: String?
): List<RaftHomeConversationItem> {
    if (pinnedFirstId.isNullOrBlank()) return this
    return filterNot { it.id == pinnedFirstId }
}

internal fun homeServerDrawerRows(
    sections: List<RaftHomeServerDrawerSection>
): List<RaftHomeServerDrawerRow> =
    buildList {
        sections.forEach { drawerSection ->
            val serverId = drawerSection.server.id
            add(RaftHomeServerDrawerRow.ServerHeader(drawerSection))
            if (!drawerSection.expanded) return@forEach

            val hasCache = drawerSection.channels.isNotEmpty() || drawerSection.directMessages.isNotEmpty()
            if (!hasCache && drawerSection.status in setOf(HomeServerDrawerLoadStatus.Unloaded, HomeServerDrawerLoadStatus.Loading)) {
                repeat(3) { index -> add(RaftHomeServerDrawerRow.Skeleton(serverId, index)) }
                return@forEach
            }

            val hiddenDmIds = drawerSection.hiddenDmIds.toSet()
            val visibleDms =
                drawerSection.directMessages
                    .orderedBySidebarIds(drawerSection.dmOrder)
                    .filter { dm -> dm.id !in hiddenDmIds || dm.homeConversationBadgeProjection().hasUnread }
                    .sortedForSidebar(RaftSidebarSortMode.fromWireValue(drawerSection.dmSortMode)) { it.name }
            val regularChannels =
                drawerSection.channels
                    .filter { it.kind != JointChannelKindWireValue }
                    .orderedBySidebarIds(drawerSection.channelOrder)
                    .sortedForSidebar(RaftSidebarSortMode.fromWireValue(drawerSection.channelSortMode)) { it.name }
            val jointChannels =
                drawerSection.channels
                    .filter { it.kind == JointChannelKindWireValue }
                    .orderedBySidebarIds(drawerSection.channelOrder)
                    .sortedForSidebar(RaftSidebarSortMode.fromWireValue(drawerSection.channelSortMode)) { it.name }
            val pinned =
                pinnedRowsForSidebar(
                    channels = drawerSection.channels,
                    directMessages = drawerSection.directMessages,
                    pinnedIds = drawerSection.pinnedChannelIds,
                    pinnedAgentIds = drawerSection.pinnedAgentIds,
                    pinnedOrder = drawerSection.pinnedOrder,
                    typedPinnedItems = drawerSection.pinnedItems,
                    pinnedMemberProfileResolver = { null },
                    mode = RaftSidebarSortMode.fromWireValue(drawerSection.pinnedSortMode)
                ).mapNotNull { it.conversation }

            fun appendConversationSection(
                section: RaftHomeConversationSection,
                items: List<RaftHomeConversationItem>
            ) {
                if (items.isEmpty()) return
                add(RaftHomeServerDrawerRow.SectionHeader(serverId, section, items.size))
                items.forEach { item -> add(RaftHomeServerDrawerRow.Conversation(serverId, section, item)) }
            }

            appendConversationSection(RaftHomeConversationSection.Pinned, pinned)
            appendConversationSection(RaftHomeConversationSection.Channel, regularChannels)
            appendConversationSection(RaftHomeConversationSection.JointChannel, jointChannels)
            appendConversationSection(RaftHomeConversationSection.DirectMessage, visibleDms)

            if (drawerSection.status in setOf(HomeServerDrawerLoadStatus.ErrorEmpty, HomeServerDrawerLoadStatus.ErrorWithCache)) {
                add(
                    RaftHomeServerDrawerRow.Error(
                        serverId = serverId,
                        code = drawerSection.errorCode ?: "home_network",
                        hasCache = hasCache
                    )
                )
            }
        }
    }

internal fun homeServerDrawerWidth(maxWidth: Dp, maxHeight: Dp): Dp =
    minOf(
        maxWidth *
            if (maxWidth > maxHeight) {
                HomeServerDrawerLandscapeWidthFraction
            } else {
                HomeServerDrawerWidthFraction
            },
        HomeServerDrawerMaxWidth
    )

internal fun shouldDismissHomeServerDrawerOnSwipe(
    horizontalDragDp: Float,
    thresholdDp: Float = HomeServerDrawerSwipeDismissThreshold.value
): Boolean = horizontalDragDp <= -thresholdDp

/** Web ServerSwitcherMenu: `opacity-60` on the server list while a switch is pending. */
internal const val HomeServerSwitchPendingAlpha = 0.6f

/** Whether the server lists (Home switcher, server panel) wait on a switch: dimmed, no taps or drags. */
internal fun homeServerSwitchListPending(pendingServerId: String?): Boolean = !pendingServerId.isNullOrBlank()

internal fun List<String>.moveItem(id: String, toIndex: Int): List<String> {
    val fromIndex = indexOf(id)
    if (fromIndex < 0 || toIndex !in indices || fromIndex == toIndex) return this
    val mutable = toMutableList()
    val item = mutable.removeAt(fromIndex)
    mutable.add(toIndex.coerceIn(0, mutable.size), item)
    return mutable
}

/** A visible sort-overlay row's laid-out vertical bounds, in viewport space. */
internal data class RaftSortRowBounds(val index: Int, val top: Float, val size: Float)

/**
 * Resolve which list index the finger is over, in CONTENT space. [pointerY] is
 * the finger's viewport Y; [rows] are the currently visible rows (their tops
 * move as the list scrolls, so the same finger Y maps to a new index after an
 * auto-scroll — this is what keeps drag + auto-scroll consistent). Above the
 * first visible row clamps to it; below the last clamps to it; result is
 * clamped to [0, lastIndex].
 */
internal fun resolveSortTargetIndex(
    pointerY: Float,
    rows: List<RaftSortRowBounds>,
    lastIndex: Int
): Int {
    if (rows.isEmpty() || lastIndex < 0) return 0
    val sorted = rows.sortedBy { it.index }
    val first = sorted.first()
    val last = sorted.last()
    val raw =
        when {
            pointerY <= first.top -> first.index
            pointerY >= last.top + last.size -> last.index
            // Rows are laid out with spacing between them: a pointer inside the
            // gap between two rows belongs to the next row down. "First row whose
            // bottom edge is below the pointer" covers both cases; the old
            // strict-containment check missed the gaps and fell back to the last
            // visible row, snapping the insertion indicator to the end whenever
            // the finger crossed a gap (task #356 device feedback).
            else -> sorted.first { pointerY < it.top + it.size }.index
        }
    return raw.coerceIn(0, lastIndex)
}

internal fun resolveSortAutoScrollDelta(
    pointerY: Float,
    viewportStart: Float,
    viewportEnd: Float,
    edgeScrollZone: Float,
    edgeScrollStep: Float,
    canScrollBackward: Boolean,
    canScrollForward: Boolean
): Float {
    if (viewportEnd <= viewportStart || edgeScrollZone <= 0f || edgeScrollStep <= 0f) return 0f
    return when {
        pointerY < viewportStart + edgeScrollZone && canScrollBackward -> -edgeScrollStep
        pointerY > viewportEnd - edgeScrollZone && canScrollForward -> edgeScrollStep
        else -> 0f
    }
}

internal fun resolveSortDraggedRowTop(
    pointerY: Float,
    viewportStart: Float,
    viewportEnd: Float,
    rowSize: Float
): Float {
    // The raw pointer remains unbounded for edge auto-scroll; only the floating
    // copy is clamped so it cannot stretch a Kuikly host past the list viewport.
    if (viewportEnd <= viewportStart || rowSize <= 0f) return viewportStart
    val maxTop = viewportEnd - rowSize
    if (maxTop <= viewportStart) return viewportStart
    return (pointerY - rowSize / 2f).coerceIn(viewportStart, maxTop)
}

private suspend fun runRaftSortAutoScrollLoop(
    listState: LazyListState,
    isDragging: () -> Boolean,
    pointerViewportY: () -> Float,
    edgeScrollZonePx: Float,
    edgeScrollStepPx: Float,
    onScrolled: (delta: Float, consumed: Float) -> Unit
) {
    while (isDragging()) {
        val info = listState.layoutInfo
        val delta =
            resolveSortAutoScrollDelta(
                pointerY = pointerViewportY(),
                viewportStart = info.viewportStartOffset.toFloat(),
                viewportEnd = info.viewportEndOffset.toFloat(),
                edgeScrollZone = edgeScrollZonePx,
                edgeScrollStep = edgeScrollStepPx,
                canScrollBackward = listState.canScrollBackward,
                canScrollForward = listState.canScrollForward
            )
        if (delta != 0f) {
            val consumed = listState.dispatchRawDelta(delta)
            onScrolled(delta, consumed)
        }
        withFrameNanos { }
    }
}

/**
 * Reconstruct the Pinned section from raw `pinnedChannelIds`, `pinnedAgentIds`,
 * and `pinnedOrder`
 * (server side `SettingsServerSidebarOrderSnapshot`).
 *
 * Manual mode: items in `pinnedOrder` (drop unknown ids), then any remaining
 * pinned ids not present in `pinnedOrder` appended in input order.
 * Recent mode: sorted by `lastMessageAt ?: createdAt` desc, then name.
 * A-Z mode: sorted by display name asc.
 *
 * Items must be present in either `channels` (regular or joint) or
 * `directMessages`; pinned ids that no longer exist are dropped. Agent ids
 * are resolved to their corresponding agent DM row through the row's
 * `presenceMemberId`, matching React's `pinnedAgentIds -> agentDmById` path.
 */
internal data class RaftHomePinnedMemberItem(
    val identity: MemberDetailIdentity,
    val name: String,
    val description: String? = null,
    val avatarLabel: String
)

internal fun MemberProfile.toRaftHomePinnedMemberItem(): RaftHomePinnedMemberItem? {
    if (kind == MemberKind.System) return null
    return RaftHomePinnedMemberItem(
        identity = MemberDetailIdentity(id, kind, serverId),
        name = displayName.ifBlank { handle.ifBlank { id.value } },
        description = handle.takeIf { it.isNotBlank() }?.let { "@$it" },
        avatarLabel = avatarLabel.ifBlank { displayName.ifBlank { handle }.toRaftAvatarLabel() }
    )
}

internal data class RaftHomePinnedRow(
    val key: String,
    val conversation: RaftHomeConversationItem? = null,
    val member: RaftHomePinnedMemberItem? = null
) {
    val sortLabel: String = conversation?.name ?: member?.name.orEmpty()
    val sortTimestamp: String? = conversation?.lastMessageAt ?: conversation?.createdAt
}

internal sealed interface RaftHomePinnedClickTarget {
    data class Conversation(val conversationId: String) : RaftHomePinnedClickTarget

    data class Member(val identity: MemberDetailIdentity) : RaftHomePinnedClickTarget
}

internal fun RaftHomePinnedRow.clickTarget(): RaftHomePinnedClickTarget? =
    conversation?.let { RaftHomePinnedClickTarget.Conversation(it.id) }
        ?: member?.let { RaftHomePinnedClickTarget.Member(it.identity) }

internal fun RaftHomePinnedRow.supportsLongPress(): Boolean = conversation != null || member != null

internal fun RaftHomePinnedRow.toSidebarSortItem(): RaftHomeConversationItem? =
    conversation ?: member?.let { pinnedMember ->
        RaftHomeConversationItem(
            id = pinnedMember.identity.memberId.value,
            name = pinnedMember.name,
            kind = RaftThreadSurfaceKind.DirectMessage,
            channelType = "dm",
            description = pinnedMember.description,
            avatarLabel = pinnedMember.avatarLabel,
            peerType = pinnedMember.identity.kind.memberKindWireValue(),
            presenceMemberId = pinnedMember.identity.memberId.value
        )
    }

internal fun pinnedItemsForSidebar(
    channels: List<RaftHomeConversationItem>,
    directMessages: List<RaftHomeConversationItem>,
    pinnedIds: List<String>,
    pinnedAgentIds: List<String> = emptyList(),
    pinnedOrder: List<String>,
    mode: RaftSidebarSortMode
): List<RaftHomeConversationItem> =
    pinnedRowsForSidebar(
        channels = channels,
        directMessages = directMessages,
        pinnedIds = pinnedIds,
        pinnedAgentIds = pinnedAgentIds,
        pinnedOrder = pinnedOrder,
        mode = mode
    ).mapNotNull { it.conversation }

/** Conversation ids the Pinned section renders; they leave their system section (web parity). */
internal fun List<RaftHomePinnedRow>.sidebarPinnedConversationIds(): Set<String> =
    mapNotNullTo(HashSet()) { row -> row.conversation?.id }

internal fun pinnedRowsForSidebar(
    channels: List<RaftHomeConversationItem>,
    directMessages: List<RaftHomeConversationItem>,
    pinnedIds: List<String>,
    pinnedAgentIds: List<String> = emptyList(),
    pinnedOrder: List<String>,
    typedPinnedItems: List<SettingsServerSidebarPinnedItem> = emptyList(),
    pinnedMemberProfileResolver: (String) -> RaftHomePinnedMemberItem? = { null },
    mode: RaftSidebarSortMode
): List<RaftHomePinnedRow> {
    val byId: Map<String, RaftHomeConversationItem> = buildMap {
        channels.forEach { put(it.id, it) }
        directMessages.forEach { put(it.id, it) }
        directMessages.forEach { dm ->
            if (dm.peerType.homeDmPeerMemberKindOrNull() != null) {
                dm.presenceMemberId?.takeIf { it.isNotBlank() }?.let { put(it, dm) }
            }
        }
    }
    val typedRows =
        typedPinnedItems.mapNotNull { item ->
            val kind = item.kind.trim().lowercase()
            val id = item.id.trim()
            when (kind) {
                "channel" ->
                    byId[id]?.let { RaftHomePinnedRow(key = "channel:$id", conversation = it) }
                "agent", "human" -> {
                    val pinnedMemberKind = kind.projectedMemberKindOrNull()
                    val conversation =
                        directMessages.firstOrNull { dm ->
                            dm.presenceMemberId == id &&
                                pinnedMemberKind != null &&
                                dm.peerType.homeDmPeerMemberKindOrNull() == pinnedMemberKind
                        } ?: byId[id]?.takeIf { dm ->
                            dm.isDirectMessageConversation() &&
                                pinnedMemberKind != null &&
                                dm.peerType.homeDmPeerMemberKindOrNull() == pinnedMemberKind
                        }
                    if (conversation != null) {
                        RaftHomePinnedRow(key = "$kind:$id", conversation = conversation)
                    } else {
                        pinnedMemberProfileResolver(id)
                            ?.takeIf { it.identity.kind == pinnedMemberKind }
                            ?.let { member -> RaftHomePinnedRow(key = "$kind:$id", member = member) }
                    }
                }
                else -> null
            }
        }.distinctBy { row -> row.conversation?.id ?: row.member?.identity?.memberId?.value ?: row.key }
    if (typedRows.isNotEmpty()) {
        return typedRows.sortedPinnedRows(mode)
    }
    if (pinnedIds.isEmpty() && pinnedAgentIds.isEmpty()) return emptyList()
    val allPinnedIds = (pinnedIds + pinnedAgentIds).distinct()
    val pinnedSet = allPinnedIds.toSet()
    val existingPinned = allPinnedIds.mapNotNull { byId[it] }.distinctBy { it.id }
    return when (mode) {
        RaftSidebarSortMode.Manual -> {
            val ordered = LinkedHashMap<String, RaftHomePinnedRow>()
            pinnedOrder.forEach { id ->
                byId[id]?.takeIf { id in pinnedSet }?.let { ordered.put(it.id, RaftHomePinnedRow(key = id, conversation = it)) }
            }
            existingPinned.forEach { item ->
                if (!ordered.containsKey(item.id)) {
                    ordered.put(item.id, RaftHomePinnedRow(key = item.id, conversation = item))
                }
            }
            ordered.values.toList()
        }
        RaftSidebarSortMode.Recent ->
            existingPinned.sortedWith(
                compareByDescending<RaftHomeConversationItem> {
                    parseRaftTimestampEpochMillisOrNull(it.lastMessageAt ?: it.createdAt) ?: Long.MIN_VALUE
                }.thenBy { it.name.trim().lowercase() }.thenBy { it.id }
            ).map { RaftHomePinnedRow(key = it.id, conversation = it) }
        RaftSidebarSortMode.Az ->
            existingPinned.sortedWith(
                compareBy<RaftHomeConversationItem> { it.name.trim().lowercase() }.thenBy { it.id }
            ).map { RaftHomePinnedRow(key = it.id, conversation = it) }
    }
}

/** task #512: custom section header label — emoji prefix when one is set. */
internal fun SettingsServerSidebarCustomSection.sidebarSectionHeaderLabel(): String =
    emoji?.takeIf { it.isNotBlank() }?.let { "$it $name" } ?: name

internal data class RaftHomeCustomSectionRows(
    val section: SettingsServerSidebarCustomSection,
    val items: List<RaftHomeConversationItem>
)

/**
 * task #512: resolve custom-section placements to conversation rows, in
 * sectionOrder order. Mirrors web Sidebar.tsx `customSectionItems`: placements
 * resolve channels and DM channels by id (kind "channel") or agent-peer DMs by
 * member id (kind "agent"); pinned conversations stay in the pinned section
 * (the two stay mutually exclusive); every custom section is listed, empty
 * ones too (web renders them with a "No conversations" hint); each section's
 * items follow its own sort mode.
 */
internal fun sidebarCustomSectionRows(
    channels: List<RaftHomeConversationItem>,
    directMessages: List<RaftHomeConversationItem>,
    customSections: List<SettingsServerSidebarCustomSection>,
    sectionOrder: List<String>,
    sectionPlacements: List<SettingsServerSidebarSectionPlacement>,
    pinnedConversationIds: Set<String>
): List<RaftHomeCustomSectionRows> {
    if (customSections.isEmpty()) return emptyList()
    val sectionById = customSections.associateBy { it.id }
    val conversationById = buildMap {
        channels.forEach { put(it.id, it) }
        directMessages.forEach { put(it.id, it) }
    }
    val agentDmByMemberId =
        directMessages
            .filter {
                it.peerType.equals(SIDEBAR_SECTION_ITEM_KIND_AGENT, ignoreCase = true) &&
                    !it.presenceMemberId.isNullOrBlank()
            }
            .associateBy { it.presenceMemberId.orEmpty() }
    val itemsBySection = LinkedHashMap<String, MutableList<RaftHomeConversationItem>>()
    sectionPlacements.sortedBy { it.position }.forEach { placement ->
        val section = sectionById[placement.sectionId] ?: return@forEach
        val conversation =
            when (placement.kind) {
                SIDEBAR_SECTION_ITEM_KIND_CHANNEL -> conversationById[placement.id]
                SIDEBAR_SECTION_ITEM_KIND_AGENT -> agentDmByMemberId[placement.id]
                else -> null
            } ?: return@forEach
        if (conversation.id in pinnedConversationIds || placement.id in pinnedConversationIds) return@forEach
        itemsBySection.getOrPut(section.id) { mutableListOf() } += conversation
    }
    return normalizeSidebarSectionOrder(sectionOrder, customSections)
        .mapNotNull { sectionId ->
            val section = sectionById[sectionId] ?: return@mapNotNull null
            val items = itemsBySection[sectionId].orEmpty()
            RaftHomeCustomSectionRows(
                section = section,
                items = items.sortedForSidebar(RaftSidebarSortMode.fromWireValue(section.sortMode)) { it.name }
            )
        }
}

/**
 * task #512: conversation ids hidden from their default system sections because
 * a custom section claims them (web customPlacedChannelIds / customPlacedAgentIds).
 */
internal fun sidebarCustomPlacedConversationIds(
    sectionPlacements: List<SettingsServerSidebarSectionPlacement>,
    directMessages: List<RaftHomeConversationItem>
): Set<String> =
    buildSet {
        sectionPlacements.forEach { placement ->
            when (placement.kind) {
                SIDEBAR_SECTION_ITEM_KIND_CHANNEL -> add(placement.id)
                SIDEBAR_SECTION_ITEM_KIND_AGENT ->
                    directMessages
                        .firstOrNull {
                            it.peerType.equals(SIDEBAR_SECTION_ITEM_KIND_AGENT, ignoreCase = true) &&
                                it.presenceMemberId == placement.id
                        }
                        ?.let { add(it.id) }
            }
        }
    }

private fun String?.homeDmPeerMemberKindOrNull(): MemberKind? =
    parsedMemberKindOrNull(MemberKindWireContract.InternalActor)
        ?: projectedMemberKindOrNull()?.takeIf { it != MemberKind.System }

private fun List<RaftHomePinnedRow>.sortedPinnedRows(mode: RaftSidebarSortMode): List<RaftHomePinnedRow> =
    when (mode) {
        RaftSidebarSortMode.Manual -> this
        RaftSidebarSortMode.Recent ->
            sortedWith(
                compareByDescending<RaftHomePinnedRow> {
                    parseRaftTimestampEpochMillisOrNull(it.sortTimestamp) ?: Long.MIN_VALUE
                }.thenBy { it.sortLabel.trim().lowercase() }.thenBy { it.key }
            )
        RaftSidebarSortMode.Az ->
            sortedWith(compareBy<RaftHomePinnedRow> { it.sortLabel.trim().lowercase() }.thenBy { it.key })
    }
@Composable
private fun HomeTitlebar(
    serverName: String,
    serverIdentity: RaftServerSwitcherItem? = null,
    serverPublicPageLive: Boolean = false,
    hasOtherServerUnread: Boolean,
    serverSwitcherOpen: Boolean = false,
    notifications: List<RaftHomeNotificationItem>,
    notificationCenterExpanded: Boolean = false,
    refreshing: Boolean,
    topSafeArea: Dp,
    /** iPhone Duo: the bell is in the side column; the head is the switcher line on paper. */
    bellInColumn: Boolean = false,
    onServerBadgeClick: () -> Unit,
    onBlankClick: (() -> Unit)? = null,
    onNotificationToggle: () -> Unit
) {
    val background = if (bellInColumn) RaftColor.Canvas else RaftColor.Frame
    // raft-ui phone head: the frame layer (Source Yellow in light) with a 1dp seam. The seam is
    // flattened onto the canvas like RaftTopPageHeader's, so it reads the same grey on every tab
    // (an alpha seam over the yellow frame came out yellow-brown).
    // The frame runs up under the status bar: the host's status bar icons follow it.
    RaftStatusBarBackdropClaim(background, enabled = topSafeArea > 0.dp)
    Box(modifier = Modifier.fillMaxWidth().height(homeShellHeaderHeight(topSafeArea, bellInColumn)).background(background)) {
        // Beside the Duo side column the switcher line is the page's first line, on paper, with the
        // same seam as every top-level head.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(RaftAppStroke.Thin)
                .background(RaftColor.BorderSeam.compositeOver(RaftColor.Canvas))
                .align(Alignment.BottomCenter)
        )
        androidx.compose.foundation.layout.Row(
            modifier =
                Modifier
                    .padding(top = topSafeArea)
                    .height(RaftAppSize.PageHeaderContentHeight)
                    // The frame runs edge to edge; the identity and bell stay in the safe area.
                    .raftSafeAreaHorizontalPadding()
                    .padding(horizontal = RaftAppSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // The header always shows the identity cluster (avatar tile, name, chevron). Before
            // the server identity loads (cold start, fixtures) it is built from the name alone.
            HomeServerIdentityCluster(
                server = homeTitlebarServerIdentity(serverIdentity, serverName, serverPublicPageLive),
                unread = hasOtherServerUnread,
                open = serverSwitcherOpen,
                onClick = onServerBadgeClick,
                onBlankClick = onBlankClick,
                modifier = Modifier.weight(1f)
            )
            if (refreshing) {
                RaftHeaderRefreshIndicator()
            }
            // The bell's icon box ends on the content line (16dp), mirroring the server avatar on
            // the left; its 44dp target reaches past it.
            if (!bellInColumn) HomeNotificationCenter(
                notifications = notifications,
                open = notificationCenterExpanded,
                onToggle = onNotificationToggle,
                modifier = Modifier.offset(x = HomeNotificationBellEdgeInset)
            )
        }
    }
}

/**
 * The server shown in the Home head: the loaded identity, or (before it loads) an item built from
 * [serverName] so the head keeps the same avatar tile + name + chevron instead of another badge.
 */
internal fun homeTitlebarServerIdentity(
    serverIdentity: RaftServerSwitcherItem?,
    serverName: String,
    serverPublicPageLive: Boolean
): RaftServerSwitcherItem =
    serverIdentity ?: RaftServerSwitcherItem(name = serverName, publicPageLive = serverPublicPageLive)

@Composable
private fun HomeServerIdentityCluster(
    server: RaftServerSwitcherItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    unread: Boolean = false,
    open: Boolean = false,
    onBlankClick: (() -> Unit)? = null
) {
    // The caller hands this cluster the header's remaining width (weight). Only the
    // avatar + name + chevron is the switcher: the free space to its right must not be a
    // touch target, so the clickable sits on a wrap-content row inside that width.
    Box(
        modifier =
            modifier
                .height(RaftAppSize.PageHeaderContentHeight)
                .then(
                    if (onBlankClick != null) {
                        // Blank header space: an affordance-less tap target, so no press wash.
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onBlankClick
                        )
                    } else {
                        Modifier
                    }
                ),
        contentAlignment = Alignment.CenterStart
    ) {
    // raft-ui WorkspaceButton: the avatar sits on the header's content line and the press
    // fill reaches past it by the button's inset.
    RaftWorkspaceButton(
        name = server.name,
        onClick = onClick,
        modifier = Modifier.offset(x = -RaftWorkspaceButtonDefaults.ContentInset),
        // Web: the button keeps its press fill while the server list is open.
        open = open,
        avatar = {
            // Web: another server's unread rides on the avatar's badge corner (AttentionDot).
            RaftAvatarBadgeBox(
                size = RaftAvatarSize.Sm,
                badge = {
                    if (unread) {
                        RaftAvatarAttentionLight(
                            RaftAvatarSize.Sm,
                            cutout = RaftTheme.colors.layerCanvas,
                            contentDescription = stringResource(Res.string.home_server_switcher_other_unread),
                        )
                    }
                },
            ) {
                ServerSwitcherAvatarTile(server, size = RaftWorkspaceButtonDefaults.AvatarSize)
            }
        },
        mark =
            if (server.publicPageLive) {
                { RaftPublicPageGlobe(live = true, size = HomeServerPublicPageGlobeSize, color = RaftColor.TextBlackArgb) }
            } else {
                null
            },
    )
    }
}

@Composable
private fun ServerUnreadMarker(modifier: Modifier = Modifier) {
    RaftStatusIndicator(
        color = RaftColor.AccentPink,
        size = RaftStatusIndicatorTokens.ServerUnreadSize,
        modifier = modifier.offset(x = 4.dp, y = (-4).dp)
    )
}

@Composable
private fun HomeNotificationCenter(
    notifications: List<RaftHomeNotificationItem>,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Web parity (task #692 / PR #5605): the Bell trigger is permanent and
    // neutral. Availability (trigger exists), disclosure (popup open) and
    // attention (unread notifications) are independent states: only attention
    // gets the pink dot; only disclosure gets the selected button treatment.
    // The trigger never carries warning/error severity colors.
    Box(modifier = modifier.size(HomeNotificationTriggerSlotSize)) {
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(end = HomeSmallShadowOffset, bottom = HomeSmallShadowOffset)) {
            HomeNotificationBellButton(notifications, open, onToggle, HomeNotificationTriggerSize)
        }
    }
}

/**
 * The wide top bar's bell (web TopBarGlobalActions, NotificationTrigger `top-bar`): the Home
 * head's bell as a 36dp frame item, so its 20dp icon ends 20dp from the window's edge.
 */
@Composable
internal fun RaftAppTopBarNotificationBell(
    notifications: List<RaftHomeNotificationItem>,
    open: Boolean,
    onToggle: () -> Unit,
) {
    HomeNotificationBellButton(notifications, open, onToggle, RaftAppTopBarNotificationTriggerSize)
}

/**
 * The bell's popover, shown while [state] is open: under the wide top bar, at its trailing end; or,
 * with [besideRail] (the bell in the rail's footer, iPhone Duo's inner screen), next to the rail,
 * 8dp past its seam and 8dp above [bottomInset], rising from the bell.
 */
@Composable
internal fun RaftAppTopBarNotificationOverlay(
    state: RaftAppShellTopBarState,
    topInset: Dp,
    besideRail: Boolean = false,
    bottomInset: Dp = 0.dp,
) {
    if (!state.bellOpen.value) return
    RaftFloatingOverlayHost(
        onDismiss = { state.bellOpen.value = false },
        dismissActivationDelayMillis = RaftFloatingOverlayClickTriggeredDismissDelayMillis
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            HomeNotificationPopover(
                state.notifications,
                state.onView,
                state.onDismiss,
                if (besideRail) {
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(
                            start = RaftAppRailOuterWidth + RaftAppRailPopoverGap,
                            bottom = bottomInset + RaftAppRailPopoverGap,
                        )
                } else {
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = topInset, end = RaftTopBarDefaults.TrailingEndPadding)
                }.raftFloatingContentClickTarget()
            )
        }
    }
}

@Composable
private fun HomeNotificationBellButton(
    notifications: List<RaftHomeNotificationItem>,
    open: Boolean,
    onToggle: () -> Unit,
    size: Dp,
) {
    val visibleNotifications = remember(notifications) { notifications.visibleHomeNotifications() }
    val hasUnread = visibleNotifications.isNotEmpty()
    // raft-ui `PanelAction`: ghost; disclosure shows the pressed wash, no shadow.
    Button(
        onClick = onToggle,
        pressOffset = 0.dp,
        modifier =
            Modifier
                .size(size)
                .background(if (open) RaftColor.FillPressed else Color.Transparent, RoundedCornerShape(RaftAppRadius.Control))
    ) {
        // Web parity (task #113 / artin 2026-08-06): the pink dot sits
        // directly at the Bell icon's top-right corner (web: dot on the
        // icon wrapper span, `-top-1 -end-1`), not at the button slot
        // corner. Icon-sized wrapper keeps the same geometry.
        Box(modifier = Modifier.size(HomeNotificationBellIconSize)) {
            RaftLucideIcon(RaftLucideIconName.Bell, Modifier.size(HomeNotificationBellIconSize))
            if (hasUnread) {
                ServerUnreadMarker(Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

// A 20dp bell in a 44dp button: raft-ui NotificationCenter's bell size (`size-5`). The web phone navbar
// uses 22px; artin found that too big in the app (2026-10-21).
private val HomeNotificationTriggerSize = 44.dp
private val RaftAppTopBarNotificationTriggerSize = 36.dp
private val HomeNotificationBellIconSize = 20.dp
private val HomeNotificationTriggerSlotSize = HomeNotificationTriggerSize + HomeSmallShadowOffset
// From the trigger slot's end to the bell icon's: the shadow offset, then the icon centred in 44dp.
internal val HomeNotificationBellEdgeInset =
    HomeSmallShadowOffset + (HomeNotificationTriggerSize - HomeNotificationBellIconSize) / 2
private val HomeNotificationPopoverTopGap = 4.dp

// raft-ui 0.24 (#737) NotificationCenterPopup `viewport="mobile"`: on a phone the popup is the
// screen's width less 8dp on each side, at most 480dp, 8dp from the edge it hangs against
// (measured from the screen's edge, not the safe area).
private val HomeNotificationPopoverPhoneEdgeInset = 8.dp
private val HomeNotificationPopoverPhoneMaxWidth = 480.dp

private fun Modifier.homeNotificationPopoverPhoneWidth(): Modifier =
    padding(horizontal = HomeNotificationPopoverPhoneEdgeInset).fillMaxWidth()
internal fun homeNotificationPopoverTopOffset(topSafeArea: Dp): Dp =
    topSafeArea + RaftAppSize.PageHeaderHeight + HomeNotificationPopoverTopGap

internal fun shouldDismissHomeNotificationCenterForTabSelection(
    expanded: Boolean,
    selectedTabKey: String,
    targetTabKey: String
): Boolean =
    expanded && selectedTabKey != targetTabKey

internal fun List<RaftHomeNotificationItem>.visibleHomeNotifications(): List<RaftHomeNotificationItem> =
    filter { notification ->
        notification.title.isNotBlank() || notification.body.isNotBlank()
    }

internal fun List<RaftHomeNotificationItem>.homeAppUpdateBannerNotification(): RaftHomeNotificationItem? =
    visibleHomeNotifications().firstOrNull { it.id == APP_UPDATE_HOME_NOTIFICATION_ID }

internal fun List<RaftHomeNotificationItem>.nonAppUpdateHomeNotifications(): List<RaftHomeNotificationItem> =
    visibleHomeNotifications().filterNot { it.id == APP_UPDATE_HOME_NOTIFICATION_ID }

@Composable
private fun HomeAppUpdateBanner(
    notification: RaftHomeNotificationItem,
    onClick: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RaftColor.FillMuted)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RaftLucideIcon(
            RaftLucideIconName.CircleArrowUp,
            modifier = Modifier.size(16.dp),
            color = RaftColor.TextBlackArgb
        )
        Text(
            text = notification.title.ifBlank { notification.body },
            modifier = Modifier.weight(1f),
            style = RaftType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 15.sp),
            color = RaftColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(Res.string.app_update_view),
            style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 14.sp),
            color = RaftColor.TextBlack,
            maxLines = 1
        )
        RaftLucideIcon(RaftLucideIconName.ChevronRight, modifier = Modifier.size(16.dp), color = RaftColor.TextBlackArgb)
    }
    Box(modifier = Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftColor.BorderSeam))
}
@Composable
private fun HomeNotificationPopover(
    notifications: List<RaftHomeNotificationItem>,
    onView: (RaftHomeNotificationItem) -> Unit,
    onDismiss: (RaftHomeNotificationItem) -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp = 320.dp,
    maxWidth: Dp = 320.dp,
) {
    HardShadowPanel(modifier = modifier, minWidth = minWidth, maxWidth = maxWidth) {
        androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth().background(RaftColor.FillMuted).padding(horizontal = RaftAppSpacing.Md, vertical = RaftAppSpacing.Sm), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.home_notification_center_title), style = RaftType.SectionLabel, color = RaftColor.TextMutedGray, maxLines = 1)
            Text(
                if (notifications.isEmpty()) {
                    stringResource(Res.string.home_notification_center_item_count_all_clear)
                } else if (notifications.size == 1) {
                    Res.string.home_notification_center_item_count_one.format(listOf(notifications.size)).localized()
                } else {
                    Res.string.home_notification_center_item_count_many.format(listOf(notifications.size)).localized()
                },
                style = RaftType.Button,
                color = RaftColor.TextMutedGray,
                maxLines = 1
            )
        }
        Box(Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftColor.BorderHairline))
        if (notifications.isEmpty()) {
            HomeNotificationCenterEmptyState()
        } else {
            notifications.forEachIndexed { index, notification ->
                if (index > 0) {
                    Box(Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftColor.BorderHairline))
                }
                HomeNotificationRow(notification, { onView(notification) }, { onDismiss(notification) })
            }
        }
    }
}

@Composable
private fun HomeNotificationCenterEmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = RaftAppSpacing.Md, vertical = RaftAppSpacing.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
    ) {
        RaftLucideIcon(
            RaftLucideIconName.CircleCheck,
            modifier = Modifier.size(36.dp),
            color = RaftColor.TextMutedGrayArgb
        )
        Text(
            text = stringResource(Res.string.home_notification_center_empty_title),
            style = RaftType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp),
            color = RaftColor.TextMutedGray,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(Res.string.home_notification_center_empty_body),
            style = RaftType.Caption,
            color = RaftColor.TextMutedGray,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HomeNotificationRow(notification: RaftHomeNotificationItem, onView: () -> Unit, onDismiss: () -> Unit) {
    val title = notification.title.ifBlank { notification.body }
    val body = if (notification.title.isBlank()) "" else notification.body
    androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth().padding(RaftAppSpacing.Md), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)) {
        Box(modifier = Modifier.size(24.dp).background(notification.kind.notificationColor(), RoundedCornerShape(RaftAppRadius.Sticker)).border(RaftAppStroke.Control, RaftColor.StickerInk, RoundedCornerShape(RaftAppRadius.Sticker)), contentAlignment = Alignment.Center) {
            RaftLucideIcon(notification.icon.notificationIconName(notification.kind), Modifier.size(16.dp), color = raftArgbHex(RaftColor.StickerInk))
        }
        Column(modifier = Modifier.width(HomeNotificationTextWidth), verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)) {
            Text(title, style = RaftType.Button, color = RaftColor.TextBlack, overflow = TextOverflow.Ellipsis, maxLines = 3)
            if (body.isNotBlank()) {
                Text(body, style = RaftType.Button, color = RaftColor.TextBlack, overflow = TextOverflow.Ellipsis, maxLines = 3)
            }
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)) {
                if (notification.actionLabel.isNotBlank()) {
                    HomeNotificationActionButton(
                        notification.actionLabel,
                        RaftColor.AccentPink,
                        onView,
                        shadowOffset = notification.primaryActionShadowOffset()
                    )
                }
                HomeNotificationActionButton(stringResource(Res.string.common_dismiss), RaftColor.SurfaceWhite, onDismiss)
            }
        }
    }
}

private val HomeNotificationTextWidth = 260.dp
private val HomeNotificationActionShadowOffset = 2.dp
private val HomeNotificationActionActiveShadowOffset = HomeNotificationActionShadowOffset * 2

internal fun RaftHomeNotificationIcon.notificationIconName(kind: RaftHomeNotificationKind): RaftLucideIconName =
    when (this) {
        RaftHomeNotificationIcon.WifiOff -> RaftLucideIconName.TriangleAlert
        RaftHomeNotificationIcon.CircleArrowUp -> RaftLucideIconName.CircleArrowUp
        RaftHomeNotificationIcon.Info -> RaftLucideIconName.Info
        RaftHomeNotificationIcon.Default ->
            when (kind) {
                RaftHomeNotificationKind.Warning -> RaftLucideIconName.TriangleAlert
                RaftHomeNotificationKind.Info -> RaftLucideIconName.Info
                RaftHomeNotificationKind.Error -> RaftLucideIconName.TriangleAlert
            }
    }

internal fun RaftHomeNotificationItem.primaryActionShadowOffset(): Dp =
    if (isAppUpdateActionActive()) {
        HomeNotificationActionActiveShadowOffset
    } else {
        HomeNotificationActionShadowOffset
    }

private fun RaftHomeNotificationItem.isAppUpdateActionActive(): Boolean =
    id == APP_UPDATE_HOME_NOTIFICATION_ID &&
        (primaryActionActive || actionLabel.trim().lowercase() in setOf("downloading", "install"))

@Composable
private fun HomeNotificationActionButton(text: String, color: Color, onClick: () -> Unit, shadowOffset: Dp = HomeNotificationActionShadowOffset) {
    RaftBrutalActionButton(
        label = text,
        onClick = onClick,
        backgroundColor = color,
        minWidth = 72.dp,
        minHeight = 32.dp,
        textStyle = HomeNotificationActionButtonTextStyle
    )
}

internal val HomeNotificationActionButtonTextStyle: TextStyle
    // Getter: the UI-text locale and rendering defaults can change at runtime.
    get() =
        RaftType.Caption.withRaftFontWeight(FontWeight.Bold)

private fun RaftHomeNotificationKind.notificationColor(): Color = when (this) {
    RaftHomeNotificationKind.Warning -> RaftColor.AccentOrange
    RaftHomeNotificationKind.Info -> RaftColor.BrandYellow
    RaftHomeNotificationKind.Error -> RaftColor.AccentRed
}

@Composable
internal fun HomeInlineErrorCard(
    error: RaftHomeInlineError,
    modifier: Modifier = Modifier,
    showAction: Boolean = true,
    onAction: () -> Unit
) {
    val visibleCopy = error.toVisibleCopy()
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .raftCardSurface()
                .padding(RaftAppSpacing.Md)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
                verticalAlignment = Alignment.Top
            ) {
                RaftLucideIcon(
                    RaftLucideIconName.TriangleAlert,
                    modifier = Modifier.size(18.dp),
                    color = RaftColor.TextBlackArgb
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = visibleCopy.title,
                        style = RaftType.ButtonBold,
                        color = RaftColor.TextBlack,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = visibleCopy.detail,
                        style = RaftType.Caption,
                        color = RaftColor.TextMutedGray,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (showAction) {
                RaftBrutalActionButton(
                    label = visibleCopy.actionLabel,
                    onClick = onAction,
                    leadingIcon = RaftLucideIconName.RefreshCw,
                    backgroundColor = RaftColor.SurfaceWhite
                )
            }
        }
    }
}

@Composable
private fun HomeServerDrawerPanel(
    sections: List<RaftHomeServerDrawerSection>,
    labels: RaftHomeShellLabels,
    onDismiss: () -> Unit,
    onServerClick: (String) -> Unit,
    onSectionToggle: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCreateServerClick: () -> Unit,
    onInviteHumanClick: (() -> Unit)? = null
) {
    val rows = remember(sections) { homeServerDrawerRows(sections) }
    // raft-ui 0.22.1 (#720): skeleton rows only after 1s of loading.
    val skeletonRowsShown =
        rememberRaftSkeletonDelayPassed(loading = rows.any { it is RaftHomeServerDrawerRow.Skeleton })
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { HomeServerDrawerSwipeDismissThreshold.toPx() }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // The one modal scrim (raft `layer-backdrop`), as behind every RaftWindowDialog.
        Box(Modifier.fillMaxSize().background(RaftTheme.scrim))
        val drawerWidth = homeServerDrawerWidth(maxWidth = maxWidth, maxHeight = maxHeight)
        var horizontalDragPx by remember { mutableStateOf(0f) }
        Column(
            modifier =
                Modifier
                    .width(drawerWidth)
                    .height(maxHeight)
                    .background(RaftColor.Canvas)
                    .border(RaftAppStroke.Thin, RaftColor.BorderSeam)
                    .clickable(onClick = {})
                    .raftKeepsBackSwipe()
                    .pointerInput(onDismiss) {
                        detectHorizontalDragGestures(
                            onDragStart = { horizontalDragPx = 0f },
                            onDragEnd = {
                                if (shouldDismissHomeServerDrawerOnSwipe(horizontalDragPx, swipeThresholdPx)) {
                                    onDismiss()
                                }
                                horizontalDragPx = 0f
                            },
                            onDragCancel = { horizontalDragPx = 0f }
                        ) { change, dragAmount ->
                            horizontalDragPx += dragAmount
                            if (dragAmount < 0f) change.consume()
                        }
                    }
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(HomeServerDrawerHeaderHeight)
                        .background(RaftColor.Frame)
                        .padding(horizontal = RaftAppSpacing.Md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
            ) {
                Text(
                    text = stringResource(Res.string.home_section_servers),
                    style = RaftType.ButtonBold,
                    color = RaftColor.TextBlack,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                Box(
                    modifier = Modifier.size(36.dp).clickable(onClick = onCreateServerClick),
                    contentAlignment = Alignment.Center
                ) {
                    RaftLucideIcon(RaftLucideIconName.Plus, Modifier.size(18.dp))
                }
            }
            if (rows.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(labels.noServersAvailable, style = RaftType.Caption, color = RaftColor.TextMutedGray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    items(rows.size, key = { index -> rows[index].key }) { index ->
                        when (val row = rows[index]) {
                            is RaftHomeServerDrawerRow.ServerHeader ->
                                HomeServerDrawerServerHeader(
                                    section = row.section,
                                    hasCachedConversations =
                                        row.section.channels.isNotEmpty() || row.section.directMessages.isNotEmpty(),
                                    onServerClick = onServerClick,
                                    onSectionToggle = onSectionToggle,
                                )
                            is RaftHomeServerDrawerRow.SectionHeader ->
                                HomeServerDrawerSectionHeader(row.section, row.count, labels)
                            is RaftHomeServerDrawerRow.Conversation ->
                                HomeServerDrawerConversationRow(row)
                            is RaftHomeServerDrawerRow.Skeleton ->
                                if (skeletonRowsShown) {
                                    HomeSidebarSkeletonRow(
                                        modifier =
                                            Modifier.padding(
                                                horizontal = HomeServerDrawerConversationHorizontalPadding,
                                                vertical = RaftAppSpacing.Xs
                                            )
                                    )
                                }
                            is RaftHomeServerDrawerRow.Error ->
                                HomeServerDrawerErrorRow(
                                    row = row,
                                    onRetry = { onRetry(row.serverId) }
                                )
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftColor.BorderHairline))
            ServerSwitcherActionRow(labels.switchOrCreateServer, onCreateServerClick)
            onInviteHumanClick?.let { ServerSwitcherActionRow(labels.inviteHuman, it, RaftLucideIconName.UserPlus) }
        }
    }
}

@Composable
internal fun HomeServerDrawerHostedPanel(
    sections: List<RaftHomeServerDrawerHostedSection>,
    labels: RaftHomeShellLabels,
    userHeader: RaftServerDrawerUserHeader,
    topSafeArea: Dp,
    bottomSafeArea: Dp,
    serverMembersStore: SharedServerMembersStore?,
    rowObservationGeneration: Long?,
    interactiveGeneration: Long?,
    authoritativeOpenGeneration: Long?,
    onServerClick: (String) -> Unit,
    onSectionToggle: (String) -> Unit,
    onRetry: (String) -> Unit,
    onConversationClick: (Long, String, String) -> Unit,
    onFirstInteractiveRow: (Long, String) -> Unit
) {
    val markerGate = remember { HomeServerDrawerInteractiveMarkerGate() }
    val projector = remember { SharedHomeConversationRowProjector() }
    val firstInteractiveServerId =
        remember(sections) {
            sections.firstOrNull {
                it.header.expanded && it.normalizedContent.firstConversationIndex >= 0
            }?.header?.server?.id
        }
    // raft-ui 0.22.1 (#720): skeleton rows only after 1s of loading.
    val skeletonRowsShown =
        rememberRaftSkeletonDelayPassed(loading = sections.any { it.header.expanded && it.showsHomeServerDrawerSkeleton() })

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(RaftColor.SurfaceWhite)
                .semantics { isTraversalGroup = true }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(homeShellHeaderHeight(topSafeArea))
                        .background(RaftColor.Frame)
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(top = topSafeArea)
                            .padding(horizontal = RaftAppSpacing.Md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
                ) {
                    RaftAppAvatar(
                        avatarWireValue = userHeader.avatarWireValue,
                        label = userHeader.displayName.ifBlank { userHeader.avatarLabel },
                        avatarSize = 32.dp,
                        cacheSubject = raftMemberAvatarCacheSubject(MemberKind.Human, userHeader.memberId),
                        cacheServerId = userHeader.serverId
                    )
                    Text(
                        text = userHeader.displayName,
                        style = RaftType.ButtonBold,
                        color = RaftColor.TextBlack,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(RaftAppStroke.Thin)
                        .background(RaftColor.BorderSeam)
                )
            }
            if (sections.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(labels.noServersAvailable, style = RaftType.Caption, color = RaftColor.TextMutedGray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    sections.forEach { hostedSection ->
                        val section = hostedSection.header
                        val serverId = section.server.id
                        items(1, key = { "server:$serverId" }) {
                            HomeServerDrawerServerHeader(
                                section = section,
                                hasCachedConversations = hostedSection.hasCachedConversations,
                                onServerClick = onServerClick,
                                onSectionToggle = onSectionToggle,
                            )
                        }
                        if (!section.expanded) return@forEach

                        val normalized = hostedSection.normalizedContent
                        val hasCache = hostedSection.hasCachedConversations
                        if (hostedSection.showsHomeServerDrawerSkeleton()) {
                            if (skeletonRowsShown) {
                                items(3, key = { index -> "skeleton:$serverId:$index" }) {
                                    HomeSidebarSkeletonRow(
                                        modifier =
                                            Modifier.padding(
                                                horizontal = HomeServerDrawerConversationHorizontalPadding,
                                                vertical = RaftAppSpacing.Xs
                                            )
                                    )
                                }
                            }
                        } else {
                            items(
                                normalized.rows.size,
                                key = { index -> "$serverId:${normalized.rows[index].key}" }
                            ) { index ->
                                when (val row = normalized.rows[index]) {
                                    is HomeServerDrawerNormalizedRow.SectionHeader ->
                                        HomeServerDrawerSectionHeader(
                                            section = row.section.toRaftHomeConversationSection(),
                                            count = row.count,
                                            labels = labels
                                        )
                                    is HomeServerDrawerNormalizedRow.Conversation -> {
                                        val item =
                                            remember(row.conversation) {
                                                projector.project(row.conversation)
                                            }
                                        val memberAvatarResolver =
                                            remember(serverMembersStore, serverId) {
                                                raftMemberAvatarResolver(serverMembersStore, serverId)
                                            }
                                        val markerModifier =
                                            if (
                                                serverId == firstInteractiveServerId &&
                                                index == normalized.firstConversationIndex &&
                                                rowObservationGeneration != null &&
                                                rowObservationGeneration == authoritativeOpenGeneration
                                            ) {
                                                Modifier.onGloballyPositioned {
                                                    val generation = rowObservationGeneration
                                                    if (markerGate.claim(generation)) {
                                                        onFirstInteractiveRow(
                                                            generation,
                                                            "$serverId:${row.key}"
                                                        )
                                                    }
                                                }
                                            } else {
                                                Modifier
                                            }
                                        Box(modifier = Modifier.fillMaxWidth().then(markerModifier)) {
                                            HomeServerDrawerConversationRow(
                                                row =
                                                    RaftHomeServerDrawerRow.Conversation(
                                                        serverId = serverId,
                                                        section =
                                                            row.section
                                                                .toRaftHomeConversationSection(),
                                                        item = item
                                                    ),
                                                memberAvatarResolver = memberAvatarResolver,
                                                enabled =
                                                    section.status !=
                                                        HomeServerDrawerLoadStatus.Unavailable &&
                                                        interactiveGeneration != null &&
                                                        interactiveGeneration == rowObservationGeneration &&
                                                        interactiveGeneration ==
                                                        authoritativeOpenGeneration,
                                                onClick = {
                                                    rowObservationGeneration?.let { generation ->
                                                        onConversationClick(
                                                            generation,
                                                            serverId,
                                                            item.id
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (
                            section.status in
                                setOf(
                                    HomeServerDrawerLoadStatus.ErrorEmpty,
                                    HomeServerDrawerLoadStatus.ErrorWithCache
                                )
                        ) {
                            items(1, key = { "error:$serverId" }) {
                                HomeServerDrawerErrorRow(
                                    row =
                                        RaftHomeServerDrawerRow.Error(
                                            serverId = serverId,
                                            code = section.errorCode ?: "home_network",
                                            hasCache = hasCache
                                        ),
                                    onRetry = { onRetry(serverId) }
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.fillMaxWidth().height(bottomSafeArea))
        }
        Box(
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(RaftAppStroke.Thin)
                    .background(RaftColor.BorderSeam)
        )
    }
}

private fun HomeServerDrawerConversationSection.toRaftHomeConversationSection(): RaftHomeConversationSection =
    when (this) {
        HomeServerDrawerConversationSection.Pinned -> RaftHomeConversationSection.Pinned
        HomeServerDrawerConversationSection.Channel -> RaftHomeConversationSection.Channel
        HomeServerDrawerConversationSection.JointChannel -> RaftHomeConversationSection.JointChannel
        HomeServerDrawerConversationSection.DirectMessage -> RaftHomeConversationSection.DirectMessage
    }

internal enum class HomeServerDrawerHeaderAction {
    OpenServerHome,
    ToggleSection,
}

internal fun homeServerDrawerHeaderSpinning(status: HomeServerDrawerLoadStatus): Boolean =
    status == HomeServerDrawerLoadStatus.Loading || status == HomeServerDrawerLoadStatus.Refreshing

internal fun dispatchHomeServerDrawerHeaderAction(
    action: HomeServerDrawerHeaderAction,
    serverId: String,
    onServerClick: (String) -> Unit,
    onSectionToggle: (String) -> Unit,
) {
    when (action) {
        HomeServerDrawerHeaderAction.OpenServerHome -> onServerClick(serverId)
        HomeServerDrawerHeaderAction.ToggleSection -> onSectionToggle(serverId)
    }
}

@Composable
private fun HomeServerDrawerServerHeader(
    section: RaftHomeServerDrawerSection,
    hasCachedConversations: Boolean,
    onServerClick: (String) -> Unit,
    onSectionToggle: (String) -> Unit,
) {
    val busy = homeServerDrawerHeaderSpinning(section.status)
    val failed = section.status == HomeServerDrawerLoadStatus.ErrorEmpty || section.status == HomeServerDrawerLoadStatus.ErrorWithCache
    val unavailable = section.status == HomeServerDrawerLoadStatus.Unavailable
    val interactive = !unavailable || hasCachedConversations
    val unavailableDescription = stringResource(Res.string.settings_state_unavailable)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (section.expanded) RaftColor.FillMuted else RaftColor.Canvas)
                .alpha(if (unavailable) 0.3f else 1f)
                .semantics {
                    if (unavailable) stateDescription = unavailableDescription
                }
                .heightIn(min = HomeServerSwitcherRowMinHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = HomeServerSwitcherRowMinHeight)
                    .clickable(enabled = interactive) {
                        dispatchHomeServerDrawerHeaderAction(
                            action = HomeServerDrawerHeaderAction.OpenServerHome,
                            serverId = section.server.id,
                            onServerClick = onServerClick,
                            onSectionToggle = onSectionToggle,
                        )
                    }
                    .padding(start = 12.dp, end = if (interactive) 0.dp else 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ServerSwitcherAvatarTile(section.server)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                // A long press copies the name / the slug as shown ("/slug"); a tap still opens.
                Text(section.server.name, modifier = Modifier.raftCopyable(section.server.name), style = RaftType.ButtonBold, color = RaftColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val shownSlug = "/${section.server.slug}"
                Text(
                    shownSlug,
                    modifier = Modifier.raftCopyable(shownSlug),
                    style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                    color = RaftColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!unavailable) ServerSwitcherUnreadIndicator(section.server)
            if (busy) {
                RaftIconSpinner(size = 14.dp, color = RaftColor.TextMutedGray)
            } else if (failed) {
                RaftLucideIcon(RaftLucideIconName.BadgeInfo, Modifier.size(14.dp), color = RaftColor.AccentRedArgb)
            } else if (unavailable) {
                RaftLucideIcon(RaftLucideIconName.BadgeInfo, Modifier.size(14.dp), color = RaftColor.TextMutedGrayArgb)
            }
        }
        if (interactive) {
            Box(
                modifier =
                    Modifier
                        .width(44.dp)
                        .heightIn(min = HomeServerSwitcherRowMinHeight)
                        .clickable {
                            dispatchHomeServerDrawerHeaderAction(
                                action = HomeServerDrawerHeaderAction.ToggleSection,
                                serverId = section.server.id,
                                onServerClick = onServerClick,
                                onSectionToggle = onSectionToggle,
                            )
                        },
                contentAlignment = Alignment.Center
            ) {
                RaftLucideIcon(
                    if (section.expanded) RaftLucideIconName.ChevronUp else RaftLucideIconName.ChevronDown,
                    Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun HomeServerDrawerSectionHeader(
    section: RaftHomeConversationSection,
    count: Int,
    labels: RaftHomeShellLabels
) {
    val label =
        when (section) {
            RaftHomeConversationSection.Pinned -> labels.pinned
            RaftHomeConversationSection.Channel -> labels.channels
            RaftHomeConversationSection.JointChannel -> labels.jointChannels
            RaftHomeConversationSection.DirectMessage -> labels.directMessages
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(HomeServerDrawerSectionHeaderHeight)
                .background(RaftColor.FillMuted)
                .padding(horizontal = HomeServerDrawerConversationHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // raft-ui section titles keep source case (`normal-case`).
        Text(label, style = RaftType.Caption, color = RaftColor.ForegroundHint, modifier = Modifier.weight(1f), maxLines = 1)
        Text(count.toString(), style = RaftType.Caption, color = RaftColor.TextMutedGray, maxLines = 1)
    }
}

@Composable
private fun HomeServerDrawerConversationRow(
    row: RaftHomeServerDrawerRow.Conversation,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    enabled: Boolean = false,
    onClick: () -> Unit = {}
) {
    val item = row.item
    val identity = "drawer:${row.serverId}:${row.section.name}:${item.id}"
    // The hosted drawer also renders outside the Home shell's designsystem theme.
    RaftDesignSystemTheme {
        if (item.isDirectMessageConversation()) {
            HomeSidebarDirectMessageRow(
                interactionIdentity = identity,
                name = item.name,
                description = item.description,
                avatarLabel = item.avatarLabel,
                peerType = item.peerType,
                memberId = item.presenceMemberId,
                cacheServerId = row.serverId,
                memberAvatarResolver = memberAvatarResolver,
                modifier = Modifier.padding(horizontal = HomeServerDrawerConversationHorizontalPadding),
                hasDraft = item.hasDraft,
                muted = item.activityMuted,
                unreadCount = item.unreadCount,
                markedUnread = item.markedUnread,
                onClick = onClick.takeIf { enabled }
            )
        } else {
            HomeSidebarChannelRow(
                name = item.name,
                symbol = item.homeChannelSymbol(),
                joined = item.joined,
                hasDraft = item.hasDraft,
                muted = item.activityMuted,
                bridgeProvider = item.bridgeProvider,
                unreadCount = item.unreadCount,
                markedUnread = item.markedUnread,
                mentioned = item.mentioned,
                followedThreadUnreadCount = item.followedThreadUnreadCount,
                interactionIdentity = identity,
                onClick = onClick.takeIf { enabled },
                modifier = Modifier.padding(horizontal = HomeServerDrawerConversationHorizontalPadding)
            )
        }
    }
}

@Composable
private fun HomeServerDrawerErrorRow(
    row: RaftHomeServerDrawerRow.Error,
    onRetry: () -> Unit
) {
    val retryLabel = row.visibleRetryLabel(stringResource(Res.string.home_action_retry))
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (row.hasCache) RaftColor.FillMuted else RaftColor.Canvas)
                .clickable(onClick = onRetry)
                .padding(horizontal = HomeServerDrawerConversationHorizontalPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
    ) {
        RaftLucideIcon(RaftLucideIconName.RefreshCw, Modifier.size(14.dp), color = RaftColor.AccentRedArgb)
        Text(
            text = retryLabel,
            style = RaftType.Caption,
            color = RaftColor.AccentRed,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun ServerSwitcherPanel(
    servers: List<RaftServerSwitcherItem>,
    currentServerId: String,
    pendingServerId: String?,
    prefersChineseCommunity: Boolean,
    labels: RaftHomeShellLabels,
    onServerSelected: (String) -> Unit,
    onServerOrderChange: (List<String>) -> Unit,
    onCreateServerClick: () -> Unit,
    onInviteHumanClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val joinCommunityActions =
        homeServerSwitcherJoinCommunityActions(
            servers = servers,
            labels = labels,
            includeChineseCommunity = prefersChineseCommunity
        )
    // Web ServerSwitcherMenu `hostedSwitchPending`: while a switch is pending the whole list sits
    // at 60% and takes no tap or drag; it comes back when the switch lands or fails.
    val switchPending = homeServerSwitchListPending(pendingServerId)
    val incomingOrder = servers.map { it.id }
    var order by remember { mutableStateOf(incomingOrder) }
    var pendingIncomingOrder by remember { mutableStateOf<List<String>?>(null) }
    val serversById = remember(servers) { servers.associateBy { it.id } }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val rowStepPx = with(density) { HomeServerSwitcherRowMinHeight.toPx().coerceAtLeast(1f) }
    val edgeScrollZonePx = with(density) { RaftSortEdgeScrollZone.toPx() }
    val edgeScrollStepPx = with(density) { RaftSortEdgeScrollStep.toPx() }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var draggedStartIndex by remember { mutableStateOf(0) }
    var pointerViewportY by remember { mutableStateOf(0f) }
    var targetIndex by remember { mutableStateOf(-1) }

    LaunchedEffect(incomingOrder) {
        if (draggedId == null) {
            order = incomingOrder
            pendingIncomingOrder = null
        } else {
            pendingIncomingOrder = incomingOrder
        }
    }
    LaunchedEffect(draggedId) {
        if (draggedId == null) {
            pendingIncomingOrder?.let { nextOrder ->
                order = nextOrder
                pendingIncomingOrder = null
            }
        }
    }

    fun visibleServerRowBounds(): List<RaftSortRowBounds> =
        listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            val key = info.key as? String ?: return@mapNotNull null
            val id = key.removePrefix("server-").takeIf { key.startsWith("server-") } ?: return@mapNotNull null
            val index = order.indexOf(id).takeIf { it >= 0 } ?: return@mapNotNull null
            RaftSortRowBounds(index = index, top = info.offset.toFloat(), size = info.size.toFloat())
        }

    fun recomputeDrag() {
        if (draggedId == null) return
        targetIndex = resolveSortTargetIndex(pointerViewportY, visibleServerRowBounds(), order.lastIndex)
    }

    LaunchedEffect(draggedId) {
        if (draggedId == null) return@LaunchedEffect
        runRaftSortAutoScrollLoop(
            listState = listState,
            isDragging = { draggedId != null },
            pointerViewportY = { pointerViewportY },
            edgeScrollZonePx = edgeScrollZonePx,
            edgeScrollStepPx = edgeScrollStepPx
        ) { delta, consumed ->
            recomputeDrag()
            raftLogInfo(
                "SlockServerSwitcher",
                "autoscroll delta=$delta consumed=$consumed pointerY=$pointerViewportY " +
                    "target=$targetIndex firstVisible=${listState.firstVisibleItemIndex}"
            )
        }
    }

    HardShadowPanel(modifier = modifier, minWidth = 276.dp, maxWidth = 328.dp) {
        if (servers.isEmpty()) {
            Text(labels.noServersAvailable, style = RaftType.Caption, color = RaftColor.TextMutedGray, modifier = Modifier.padding(RaftAppSpacing.Md))
        } else {
            val serverListMaxHeight = homeServerSwitcherServerListMaxHeight(servers.size)
            val serverListModifier =
                if (serverListMaxHeight == null) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.fillMaxWidth().height(serverListMaxHeight)
                }
            val serverLazyListModifier =
                if (serverListMaxHeight == null) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.fillMaxSize()
                }
            Box(
                modifier =
                    serverListModifier
                        .clipToBounds()
                        .alpha(if (switchPending) HomeServerSwitchPendingAlpha else 1f)
                        .semantics { if (switchPending) disabled() }
            ) {
                LazyColumn(
                    state = listState,
                    modifier = serverLazyListModifier,
                    userScrollEnabled = draggedId == null
                ) {
                    items(order.size, key = { index -> "server-${order[index]}" }) { index ->
                        val server = serversById[order[index]] ?: return@items
                        if (draggedId == server.id) {
                            Spacer(Modifier.fillMaxWidth().height(HomeServerSwitcherRowMinHeight))
                        } else {
                            ServerSwitcherRow(
                                server,
                                server.id == currentServerId,
                                dragHandleVisible = order.size > 1,
                                onClick = if (draggedId == null && !switchPending) ({ onServerSelected(server.id) }) else null
                            )
                        }
                    }
                }
                if (order.size > 1 && !switchPending) {
                    // Keep reorder ownership on the visible grip column. A long-press detector on
                    // the full LazyColumn competes with each row's clickable gesture on iOS, so a
                    // normal server tap can be swallowed before SelectServer is dispatched. The
                    // stable overlay still survives row replacement while dragging.
                    Box(
                        modifier =
                            Modifier
                                .matchParentSize(),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxHeight()
                                    .width(HomeServerSwitcherDragHandleWidth)
                                    .pointerInput(order) {
                                        // raft-ui 0.32.2 (#787): the drag starts after the 500ms touch hold.
                                        raftDetectDragGesturesAfterHold(
                                            onDragStart = { pos ->
                                                val target = resolveSortTargetIndex(pos.y, visibleServerRowBounds(), order.lastIndex)
                                                val id = order.getOrNull(target)
                                                if (id != null) {
                                                    draggedId = id
                                                    draggedStartIndex = target
                                                    pointerViewportY = pos.y
                                                    targetIndex = target
                                                    raftLogInfo("SlockServerSwitcher", "dragStart id=$id index=$target size=${order.size}")
                                                }
                                            },
                                            onDragEnd = {
                                                val active = draggedId
                                                var changed = false
                                                if (active != null && targetIndex >= 0 && targetIndex != draggedStartIndex) {
                                                    changed = true
                                                    val next = order.moveItem(active, targetIndex)
                                                    order = next
                                                    onServerOrderChange(next)
                                                }
                                                raftLogInfo("SlockServerSwitcher", "dragEnd changed=$changed from=$draggedStartIndex to=$targetIndex")
                                                draggedId = null
                                                targetIndex = -1
                                            },
                                            onDragCancel = {
                                                raftLogInfo("SlockServerSwitcher", "dragCancel id=$draggedId")
                                                draggedId = null
                                                targetIndex = -1
                                            }
                                        ) { change, dragAmount ->
                                            if (draggedId != null) {
                                                change.consume()
                                                pointerViewportY += dragAmount.y
                                                recomputeDrag()
                                            }
                                        }
                                    }
                        )
                    }
                }
                val active = draggedId
                if (active != null && targetIndex >= 0) {
                    val bounds = visibleServerRowBounds()
                    val targetRow = bounds.firstOrNull { it.index == targetIndex }
                    val lineY =
                        when {
                            targetRow != null && targetIndex >= draggedStartIndex -> targetRow.top + targetRow.size
                            targetRow != null -> targetRow.top
                            else -> bounds.maxByOrNull { it.index }?.let { it.top + it.size }
                        }
                    if (lineY != null) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .offset { IntOffset(0, lineY.roundToInt()) }
                                    .height(2.dp)
                                    .background(RaftColor.AccentPink)
                        )
                    }
                    serversById[active]?.let { server ->
                        ServerSwitcherRow(
                            server,
                            server.id == currentServerId,
                            dragHandleVisible = true,
                            pressed = true,
                            onClick = null,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .offset {
                                        val info = listState.layoutInfo
                                        IntOffset(
                                            0,
                                            resolveSortDraggedRowTop(
                                                pointerY = pointerViewportY,
                                                viewportStart = info.viewportStartOffset.toFloat(),
                                                viewportEnd = info.viewportEndOffset.toFloat(),
                                                rowSize = rowStepPx
                                            ).roundToInt()
                                        )
                                    }
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftColor.BorderHairline))
        joinCommunityActions.forEach { action ->
            ServerSwitcherActionRow(action.label, onCreateServerClick)
        }
        ServerSwitcherActionRow(labels.switchOrCreateServer, onCreateServerClick)
        // Web ServerSwitcherMenu: "Invite Human" sits last, under "Switch or create
        // server", only for viewers holding `inviteMembers` (owner/admin).
        onInviteHumanClick?.let { ServerSwitcherActionRow(labels.inviteHuman, it, RaftLucideIconName.UserPlus) }
    }
}

@Composable
private fun HardShadowPanel(
    modifier: Modifier = Modifier,
    minWidth: Dp,
    maxWidth: Dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    // raft-ui PopoverPopup without positioning; the rows bring their own padding.
    build.raft.designsystem.components.RaftPopoverSurface(
        modifier = modifier.widthIn(min = minWidth, max = maxWidth),
        width = null,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        content = content
    )
}

@Composable
private fun ServerSwitcherRow(
    server: RaftServerSwitcherItem,
    selected: Boolean,
    dragHandleVisible: Boolean = false,
    pressed: Boolean = false,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val clickModifier = onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier
    androidx.compose.foundation.layout.Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = HomeServerSwitcherRowMinHeight)
                .background(
                    when {
                        selected -> RaftColor.Selected
                        pressed -> RaftColor.FillPressed
                        else -> Color.Transparent
                    },
                    RoundedCornerShape(RaftAppRadius.Control)
                )
                .then(clickModifier)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(modifier = Modifier.width(14.dp), contentAlignment = Alignment.Center) {
            // No "..." while a switch is pending (web ServerSwitcherMenu): the whole list dims.
            if (selected) {
                RaftLucideIcon(RaftLucideIconName.Check, Modifier.size(14.dp), color = raftArgbHex(RaftColor.StickerInk))
            } else {
                Spacer(Modifier.size(14.dp))
            }
        }
        ServerSwitcherAvatarTile(server)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    server.name,
                    style = RaftType.Button,
                    color = if (selected) RaftColor.StickerInk else RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // A long press copies the name / the slug as shown ("/slug"); a tap still switches.
                    modifier = Modifier.weight(1f, fill = false).raftCopyable(server.name)
                )
                if (server.publicPageLive) {
                    Spacer(Modifier.width(HomeServerPublicPageGlobeGap))
                    RaftPublicPageGlobe(live = true, size = HomeServerPublicPageGlobeSize)
                }
            }
            val shownSlug = "/${server.slug}"
            Text(
                shownSlug,
                modifier = Modifier.raftCopyable(shownSlug),
                style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
                color = (if (selected) RaftColor.StickerInk else RaftColor.TextBlack).copy(alpha = 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        ServerSwitcherUnreadIndicator(server)
        if (dragHandleVisible) {
            Box(modifier = Modifier.width(HomeServerSwitcherDragHandleWidth), contentAlignment = Alignment.Center) {
                RaftLucideIcon(
                    HomeServerSwitcherDragHandleIconName,
                    Modifier.size(14.dp),
                    color = RaftColor.TextMutedGrayArgb
                )
            }
        }
    }
}

@Composable
private fun ServerSwitcherUnreadIndicator(server: RaftServerSwitcherItem) {
    val presentation = homeServerSwitcherBadgePresentation(server)
    when (presentation.kind) {
        HomeServerSwitcherBadgeKind.ActivityNumber,
        HomeServerSwitcherBadgeKind.BroaderUnreadNumber ->
            presentation.label?.let { label ->
                // The unread-count sticker (raft-ui `SidebarItemCount`) over the already capped label;
                // the switcher also renders outside the Home shell's designsystem theme.
                RaftDesignSystemTheme { RaftBadge(text = label, variant = RaftBadgeVariant.Accent) }
            }
        HomeServerSwitcherBadgeKind.None -> Unit
    }
}

@Composable
internal fun ServerSwitcherAvatarTile(server: RaftServerSwitcherItem, size: Dp = 32.dp) {
    val presentation = server.toServerSwitcherAvatarPresentation()
    Box(
        modifier =
            Modifier
                .size(size)
                // raft-ui square avatar: sticker radius (4dp), ink tile.
                .clip(RoundedCornerShape(RaftAppRadius.Sticker))
                // No ink fill behind an uploaded image: a transparent server avatar must show the
                // surface it sits on (the yellow frame), not a black square. The ink tile belongs to
                // the initial-letter fallback only (ServerAvatarFallbackLabel draws it).
                .border(RaftAppStroke.Control, RaftColor.StickerInk, RoundedCornerShape(RaftAppRadius.Sticker)),
        contentAlignment = Alignment.Center
    ) {
        if (presentation.avatarWireValue == null) {
            ServerAvatarFallbackLabel(presentation.fallbackLabel, size)
        }
        presentation.avatarWireValue?.let { source ->
            if (source.toPixelAvatarSpec() != null) {
                // Visual-fixture pixel avatars use the same renderer as the compact header so
                // the connected comparison can prove one identity projection at both levels.
                RaftAppAvatar(
                    avatarWireValue = source,
                    label = presentation.fallbackLabel,
                    modifier = Modifier.fillMaxSize(),
                    avatarSize = size,
                    borderWidth = 0.dp,
                    cacheSubject = RaftAvatarCacheSubject.Server(server.id),
                    cacheServerId = server.id
                )
            } else {
                // The invisible prewarm surface and visible tile share the typed mounted cache;
                // the mounted session coalesces an in-flight fetch for this server identity.
                RaftAvatarHttpImage(
                    source = source,
                    modifier = Modifier.fillMaxSize(),
                    cacheSubject = RaftAvatarCacheSubject.Server(server.id),
                    cacheServerId = server.id,
                    serverDiagnosticsId = server.id,
                    fallback = { ServerAvatarFallbackLabel(presentation.fallbackLabel, size) },
                    placeholder = { ServerAvatarFallbackLabel(presentation.fallbackLabel, size) }
                )
            }
        }
    }
}

@Composable
private fun ServerAvatarFallbackLabel(label: String, tileSize: Dp = 32.dp) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(RaftColor.StickerInk, RoundedCornerShape(RaftAppRadius.Sticker)),
        contentAlignment = Alignment.Center
    ) {
    Text(
        label,
        style =
            RaftType.Button.copy(
                fontFamily = raftMonoFamilyForWeight(FontWeight.Bold),
                // The 27dp header tile takes raft-ui's `sm` initial size.
                fontSize = if (tileSize < 32.dp) 13.sp else 17.sp,
                lineHeight = if (tileSize < 32.dp) 16.sp else 20.sp,
                fontWeight = FontWeight.Normal
            ),
        color = RaftColor.BrandYellow,
        maxLines = 1,
        textAlign = TextAlign.Center
    )
    }
}

internal data class ServerSwitcherAvatarPresentation(
    val avatarWireValue: String?,
    val fallbackLabel: String
)

internal fun RaftServerSwitcherItem.toServerSwitcherAvatarPresentation(): ServerSwitcherAvatarPresentation =
    ServerSwitcherAvatarPresentation(
        avatarWireValue = avatarUrl?.takeIf { it.isNotBlank() },
        fallbackLabel = name.take(1).uppercase()
    )

internal fun homeHeaderServerIdentity(
    currentServerId: String,
    currentServerName: String,
    servers: List<RaftServerSwitcherItem>
): RaftServerSwitcherItem {
    val matched = servers.firstOrNull { server -> server.id == currentServerId }
    return matched?.copy(name = matched.name.ifBlank { currentServerName })
        ?: RaftServerSwitcherItem(
            id = currentServerId,
            name = currentServerName,
            slug = currentServerId
        )
}

@Composable
private fun ServerSwitcherAvatarPrewarmSurface(
    servers: List<RaftServerSwitcherItem>
) {
    val remoteAvatars =
        servers.mapNotNull { server ->
            val source =
                server.toServerSwitcherAvatarPresentation().avatarWireValue
                    ?: return@mapNotNull null
            if (source.toPixelAvatarSpec() != null) null else server to source
        }
    if (remoteAvatars.isEmpty()) return
    Box(
        modifier =
            Modifier
                .size(1.dp)
                .alpha(0f)
                .clipToBounds()
    ) {
        remoteAvatars.forEach { (server, source) ->
            key(server.id, source) {
                RaftAvatarHttpImage(
                    source = source,
                    modifier = Modifier.fillMaxSize(),
                    cacheSubject = RaftAvatarCacheSubject.Server(server.id),
                    cacheServerId = server.id,
                    serverDiagnosticsId = server.id,
                    diagnosticsRole = "prewarm"
                )
            }
        }
    }
}

@Composable
private fun ServerSwitcherActionRow(
    label: String,
    onClick: () -> Unit,
    iconName: RaftLucideIconName = RaftLucideIconName.Plus
) {
    androidx.compose.foundation.layout.Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 42.dp)
                .background(RaftColor.SurfaceWhite)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
    ) {
        RaftLucideIcon(iconName, Modifier.size(14.dp))
        Text(label, style = RaftType.ButtonBold, color = RaftColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Visual fixture: every sidebar row type, selected and default, as the Home list draws them
 * (raft-ui phone SidebarItem: at least 44dp, 1dp margin above and below). Selecting a row
 * never changes its size.
 */
@Composable
internal fun HomeSidebarRowStatesVisualContent() {
    RaftDesignSystemTheme {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(RaftColor.SurfaceCream)
                    .padding(vertical = 16.dp)
        ) {
            val rowModifier = Modifier.padding(horizontal = HomeListRowHorizontalPadding)
            HomeSidebarEntryRow(RaftLucideIconName.Search, "Search", modifier = rowModifier, selected = true)
            HomeSidebarEntryRow(RaftLucideIconName.Bookmark, "Saved", modifier = rowModifier)
            HomeSidebarChannelRow(name = "general", symbol = "#", modifier = rowModifier, selected = true)
            HomeSidebarChannelRow(name = "design", symbol = "#", modifier = rowModifier)
            HomeSidebarDirectMessageRow(name = "artin", avatarLabel = "AR", modifier = rowModifier, selected = true)
            HomeSidebarDirectMessageRow(name = "Cindy", avatarLabel = "CI", modifier = rowModifier)
        }
    }
}

/**
 * Search / Activity / Saved at the top of the Home list: a raft-ui SidebarItem
 * ([RaftSidebarItem]) led by a lucide icon. [trailing] is the count (Activity's pink
 * unread count, Saved's quiet number).
 */
@Composable
private fun HomeSidebarEntryRow(
    icon: RaftLucideIconName,
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    RaftSidebarItem(
        title = label,
        modifier = modifier,
        onClick = onClick,
        selected = selected,
        leading = { HomeSidebarLucideIcon(icon) },
        trailing = trailing,
        variant = RaftSidebarItemVariant.Entry
    )
}

/**
 * A lucide glyph the designsystem does not draw, in the enclosing sidebar row's icon ink
 * (`foreground-hint`, `sticker-ink` on a selected row): in the leading slot at the row's
 * icon size ([raftSidebarItemIconSize]: 16dp, 20dp in an entry row), 14dp after the name
 * and in the aside (`SidebarItemMetaIcon`).
 */
@Composable
private fun HomeSidebarLucideIcon(icon: RaftLucideIconName, size: Dp = raftSidebarItemIconSize()) {
    RaftLucideIcon(icon, Modifier.size(size), color = raftArgbHex(LocalRaftContentColor.current))
}

internal fun formatHomeSavedCount(savedCount: Int): String? =
    formatHomeSidebarBadgeCount(savedCount)

/**
 * The long-press of a Home row or section header. The designsystem part takes it as its own
 * long click (`combinedClickable`: the press look, the long-click action and its label for
 * TalkBack / VoiceOver, and a long press that does not also click). A long click has no touch
 * point, so the menu opens under the part's bounds: [anchorModifier] keeps them live
 * (re-captured on every placement, so a scrolled or recycled row anchors where it is now).
 */
private class HomeSidebarLongClick(
    val anchorModifier: Modifier,
    val onLongClick: (() -> Unit)?
)

@Composable
private fun rememberHomeSidebarLongClick(
    identity: String,
    onLongPress: ((RaftPopoverAnchor) -> Unit)?
): HomeSidebarLongClick {
    val anchorRef = remember(identity) { RaftLiveRootAnchorRef(identity = identity) }
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val hasLongPress = onLongPress != null
    return remember(anchorRef, hasLongPress) {
        if (hasLongPress) {
            HomeSidebarLongClick(
                anchorModifier = Modifier.raftCaptureLiveRootAnchor(anchorRef),
                onLongClick = {
                    anchorRef.resolve(RaftPopoverLocalPoint.BottomStart)?.let { anchor -> currentOnLongPress?.invoke(anchor) }
                }
            )
        } else {
            HomeSidebarLongClick(anchorModifier = Modifier, onLongClick = null)
        }
    }
}

/** A conversation name: user content, so Han characters keep the zh-Hans forms in any UI language. */
private fun homeSidebarUserContentName(name: String): AnnotatedString =
    AnnotatedString(name, spanStyles = listOf(AnnotatedString.Range(SpanStyle(localeList = RaftUserContentLocaleList), 0, name.length)))

/**
 * raft-ui SidebarItem tone of a Home conversation row: not joined reads `away` (unless it is
 * the current place), muted `muted`, a loud unread `unread` (a 700 name).
 */
/**
 * What a Home channel row draws at its end (web Sidebar.tsx `ChannelRow` with
 * `getChannelUnreadIndicatorState`): the channel's unread and its followed threads with new
 * replies are one pink count (`N`, `N · [thread] M`, `[thread] M`) in a joined, unmuted channel,
 * and a quiet grey number in a muted or not-joined one; the @ sticker shows for a joined
 * channel with an unread mention; the draft pencil only when nothing is unread.
 */
internal data class HomeChannelRowMarks(
    val mention: Boolean,
    val loudCount: Int,
    val loudThreadCount: Int,
    val quietLabel: String?,
    val showDraft: Boolean,
    /**
     * Marked unread with nothing counted (botiverse/slock #11795, web `showMarkedUnreadDot`): the
     * quiet unread dot where a count would be, before the draft pencil.
     */
    val markedUnreadDot: Boolean = false,
    /** web `unreadTone`: the unread name of a loud count, or of a marked row in a joined, unmuted channel. */
    val unreadTone: Boolean = loudCount > 0 || loudThreadCount > 0,
) {
    val loud: Boolean
        get() = loudCount > 0 || loudThreadCount > 0
}

internal fun homeChannelRowMarks(
    unreadCount: Int,
    followedThreadUnreadCount: Int,
    mentioned: Boolean,
    joined: Boolean,
    muted: Boolean,
    hasDraft: Boolean,
    markedUnread: Boolean = false,
): HomeChannelRowMarks {
    val unread = unreadCount.coerceAtLeast(0)
    val threads = followedThreadUnreadCount.coerceAtLeast(0)
    val hasUnread = unread > 0 || threads > 0
    val loud = hasUnread && joined && !muted
    val quiet = hasUnread && !loud
    val markedUnreadDot = !hasUnread && markedUnread
    return HomeChannelRowMarks(
        mention = mentioned && joined,
        loudCount = if (loud) unread else 0,
        loudThreadCount = if (loud) threads else 0,
        quietLabel = if (quiet) formatHomeSidebarBadgeCount(if (unread > 0) unread else threads) else null,
        showDraft = hasDraft && !hasUnread && !markedUnreadDot,
        markedUnreadDot = markedUnreadDot,
        unreadTone = loud || (markedUnreadDot && joined && !muted),
    )
}

internal fun homeConversationRowTone(
    muted: Boolean,
    joined: Boolean,
    selected: Boolean,
    loudUnread: Boolean
): RaftRowTone =
    when {
        !joined && !selected -> RaftRowTone.Away
        muted -> RaftRowTone.Muted
        loudUnread -> RaftRowTone.Unread
        else -> RaftRowTone.Default
    }

/**
 * A channel in the Home list: raft-ui SidebarChannelItem ([RaftSidebarChannelItem]) for `#`
 * and private channels; a joint channel's `git-branch` and a public-page channel's
 * hash-globe go through the same [RaftSidebarItem] with their own glyph. Right after the
 * name (it truncates, they do not): the bridge badge and the muted bell. Web ChannelRow
 * aside: the pink unread count, else the grey number of a quiet (muted / not joined)
 * unread, else the draft pencil. [menuOpen]: this row's long-press menu is open.
 */
@Composable
private fun HomeSidebarChannelRow(
    name: String,
    symbol: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    joined: Boolean = true,
    muted: Boolean = false,
    unreadCount: Int = 0,
    markedUnread: Boolean = false,
    mentioned: Boolean = false,
    followedThreadUnreadCount: Int = 0,
    hasDraft: Boolean = false,
    bridgeProvider: String? = null,
    interactionIdentity: String = "channel:$symbol:$name",
    menuOpen: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: ((RaftPopoverAnchor) -> Unit)? = null
) {
    val marks =
        homeChannelRowMarks(
            unreadCount = unreadCount,
            followedThreadUnreadCount = followedThreadUnreadCount,
            mentioned = mentioned,
            joined = joined,
            muted = muted,
            hasDraft = hasDraft,
            markedUnread = markedUnread
        )
    val loudCount = marks.loudCount
    val loudThreadCount = marks.loudThreadCount
    val quietLabel = marks.quietLabel
    val showBridge = externalBridgeBadgeVisible(bridgeProvider)
    val showDraft = marks.showDraft
    val mutedIcon = homeConversationMutedIndicatorIcon(muted)
    val titleTrailing: (@Composable () -> Unit)? =
        if (showBridge || mutedIcon != null) {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showBridge) RaftExternalBridgeBadge(bridgeProvider)
                    mutedIcon?.let { HomeSidebarLucideIcon(it, HomeSidebarMetaIconSize) }
                }
            }
        } else {
            null
        }
    val markedUnreadDescription =
        if (marks.markedUnreadDot) stringResource(Res.string.home_sidebar_marked_unread) else null
    val aside: (@Composable RowScope.() -> Unit)? =
        when {
            quietLabel != null -> {
                { RaftSidebarItemMeta(quietLabel) }
            }
            // web ChannelRow `showMarkedUnreadDot`: unread with no count, before the draft pencil.
            marks.markedUnreadDot -> {
                { RaftQuietUnreadDot(contentDescription = markedUnreadDescription) }
            }
            showDraft -> {
                { HomeSidebarLucideIcon(RaftLucideIconName.Pencil, HomeSidebarMetaIconSize) }
            }
            else -> null
        }
    // Web ChannelRow: the yellow @ sticker sits before the count (`ml-1`), whatever the count's tone.
    val mentionDescription = if (marks.mention) stringResource(Res.string.activity_list_mentioned_you) else null
    val trailing: (@Composable RowScope.() -> Unit)? =
        if (marks.mention) {
            {
                RaftMentionMarker(contentDescription = mentionDescription)
                aside?.invoke(this)
            }
        } else {
            aside
        }
    val tone = homeConversationRowTone(muted = muted, joined = joined, selected = selected, loudUnread = marks.unreadTone)
    val longClick = rememberHomeSidebarLongClick(interactionIdentity, onLongPress)
    val rowModifier = modifier.then(longClick.anchorModifier)
    val title = remember(name) { homeSidebarUserContentName(name) }
    val longClickLabel = longClick.onLongClick?.let { stringResource(Res.string.home_sidebar_row_menu_action) }
    traceMarkdownSection(RaftHomeTraceNames.CHANNEL_ROW_RENDER, name.length) {
        if (symbol == "#" || symbol == "lock") {
            RaftSidebarChannelItem(
                name = title,
                modifier = rowModifier,
                onClick = onClick,
                selected = selected,
                tone = tone,
                isPrivate = symbol == "lock",
                unreadCount = loudCount,
                threadCount = loudThreadCount,
                trailing = trailing,
                onLongClick = longClick.onLongClick,
                onLongClickLabel = longClickLabel,
                menuOpen = menuOpen,
                titleTrailing = titleTrailing
            )
        } else {
            RaftSidebarItem(
                title = title,
                modifier = rowModifier,
                onClick = onClick,
                selected = selected,
                tone = tone,
                onLongClick = longClick.onLongClick,
                onLongClickLabel = longClickLabel,
                menuOpen = menuOpen,
                titleTrailing = titleTrailing,
                leading = { HomeSidebarChannelGlyph(symbol) },
                trailing =
                    if (trailing != null || marks.loud) {
                        {
                            trailing?.invoke(this)
                            RaftSidebarItemMarks(unreadCount = loudCount, threadCount = loudThreadCount, countDescription = null)
                        }
                    } else {
                        null
                    }
            )
        }
    }
}

/**
 * The glyph of a [homeChannelSymbol] (`SidebarItemChannelIcon`): `#`, lock, joint branch or the
 * public-page hash-globe, at [size] in the current ink ([LocalRaftContentColor]). Home sidebar rows
 * draw it at the row's icon size; Activity draws it in a list row's channel tile and as a card's
 * title icon.
 */
@Composable
internal fun HomeSidebarChannelGlyph(symbol: String, size: Dp = raftSidebarItemIconSize(), modifier: Modifier = Modifier) {
    when (symbol) {
        "#" -> RaftGlyphIcon(RaftGlyph.Hash, modifier = modifier, size = size)
        "lock" -> RaftGlyphIcon(RaftGlyph.Lock, modifier = modifier, size = size)
        HomeChannelSymbolHashGlobe -> RaftHashGlobeIcon(size = size, color = raftArgbHex(LocalRaftContentColor.current), modifier = modifier)
        else -> RaftLucideIcon(symbol, modifier.size(size), color = raftArgbHex(LocalRaftContentColor.current))
    }
}

/**
 * A Home list section header: raft-ui SidebarSectionHeader ([RaftSidebarSectionHeader]) with
 * the section's controls (sort, create), the pink attention pixel while a collapsed section
 * holds something unread, and a long-press that opens the section menu (web right-click /
 * long-press) under the header; [menuOpen] while that menu is open. [longPressIdentity] must
 * name the section (an id, not its label), so two same-named sections never share a popover
 * anchor.
 */
@Composable
private fun HomeSidebarSectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    longPressIdentity: String,
    modifier: Modifier = Modifier,
    attention: Boolean = false,
    emoji: String? = null,
    sortLabel: String? = null,
    onSortClick: ((RaftPopoverAnchor) -> Unit)? = null,
    addLabel: String? = null,
    onAddClick: (() -> Unit)? = null,
    addEnabled: Boolean = true,
    menuOpen: Boolean = false,
    onLongPress: ((RaftPopoverAnchor) -> Unit)? = null
) {
    val hasSort = sortLabel != null && onSortClick != null
    val hasAdd = addLabel != null && onAddClick != null
    val longClick = rememberHomeSidebarLongClick("section-header:$longPressIdentity", onLongPress)
    RaftSidebarSectionHeader(
        title = title,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier.then(longClick.anchorModifier),
        onLongClick = longClick.onLongClick,
        onLongClickLabel = longClick.onLongClick?.let { stringResource(Res.string.home_sidebar_section_menu_action, title) },
        menuOpen = menuOpen,
        count = count,
        attention = attention,
        icon = emoji?.let { glyph -> @Composable { RaftText(glyph, style = RaftTheme.typography.footnote, maxLines = 1) } },
        actions =
            if (hasSort || hasAdd) {
                {
                    if (sortLabel != null && onSortClick != null) HomeSidebarSortAction(sortLabel, onSortClick)
                    if (addLabel != null && onAddClick != null) {
                        RaftSidebarSectionAction(RaftGlyph.Plus, addLabel, onAddClick, enabled = addEnabled)
                    }
                }
            } else {
                null
            }
    )
}

/** The section's Sort control, `arrow-up-down`; the sort menu opens under it. */
@Composable
private fun HomeSidebarSortAction(label: String, onClick: (RaftPopoverAnchor) -> Unit) {
    val anchorRef = remember(label) { RaftLiveRootAnchorRef(identity = label) }
    RaftSidebarSectionAction(
        glyph = RaftGlyph.ArrowUpDown,
        tooltip = label,
        onClick = { anchorRef.resolve(RaftPopoverLocalPoint.BottomStart)?.let(onClick) },
        modifier = Modifier.raftCaptureLiveRootAnchor(anchorRef)
    )
}

// #372 React parity: outlined-circle + gray-bar skeleton row sized like a
// sidebar row (44dp, 1dp margins, 8dp start padding) so the loaded
// conversation list replaces the skeletons in place. RaftAppSkeleton is fully static (no
// shimmer), which keeps visual captures deterministic.
/** A server with nothing cached yet, still loading, shows skeleton rows in the drawer. */
private fun RaftHomeServerDrawerHostedSection.showsHomeServerDrawerSkeleton(): Boolean =
    !hasCachedConversations &&
        header.status in setOf(HomeServerDrawerLoadStatus.Unloaded, HomeServerDrawerLoadStatus.Loading)

@Composable
private fun HomeSidebarSkeletonRow(modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .padding(vertical = 1.dp)
                .fillMaxWidth()
                .height(HomeSidebarSkeletonRowHeight)
                .padding(start = HomeSidebarSkeletonRowStartPadding, end = RaftAppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RaftAppSkeleton(modifier = Modifier.size(ChannelAvatarSize), variant = RaftAppSkeletonVariant.Circle)
        RaftAppSkeleton(
            modifier =
                Modifier
                    .padding(start = SidebarActionLabelGap)
                    .weight(HomeLoadingSkeletonLineWeight)
                    .height(HomeLoadingSkeletonLineHeight),
            variant = RaftAppSkeletonVariant.Line
        )
        Spacer(Modifier.weight(1f - HomeLoadingSkeletonLineWeight))
    }
}

@Composable
private fun RaftSidebarSortOverlay(
    title: String,
    items: List<RaftHomeConversationItem>,
    saveLabel: String,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    cacheServerId: String? = null,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver
) {
    // The sheet owns the order the person is building. A realtime tick (a new message, a
    // presence change) hands in new item objects for the same rows: it must neither reset
    // that order nor cancel a drag, so state is keyed on nothing and only reconciled by id.
    val itemIds = remember(items) { items.map { it.id } }
    var userOrder by remember { mutableStateOf(itemIds) }
    val order = remember(userOrder, itemIds) { reconcileSidebarSortOrder(userOrder, itemIds) }
    val itemsById = remember(items) { items.associateBy { it.id } }
    val density = LocalDensity.current
    val edgeScrollZonePx = with(density) { RaftSortEdgeScrollZone.toPx() }
    val edgeScrollStepPx = with(density) { RaftSortEdgeScrollStep.toPx() }
    val listState = rememberLazyListState()

    // Content-space drag model. The LazyColumn item order and layout stay FIXED
    // while a row is dragged: the list does NOT reflow. Reflowing every row each
    // frame (the previous approach) was inherently jittery under auto-scroll —
    // the target changed every frame, and the dragged placeholder disposing and
    // recomposing as it scrolled off/back churned the reflow basis and shoved the
    // list (task #356 device feedback). Instead we track the finger's viewport Y,
    // resolve a single drop target against the current visible-row layout, draw a
    // floating copy under the finger + an insertion line at the target, and
    // commit the reorder once on drop.
    var draggedId by remember { mutableStateOf<String?>(null) }
    var draggedStartIndex by remember { mutableStateOf(0) }
    // Height of the held row as laid out (a sidebar row grows with the font scale): its
    // placeholder keeps that slot and the floating copy is centred on the finger with it.
    var draggedRowSizePx by remember { mutableStateOf(0f) }
    var pointerViewportY by remember { mutableStateOf(0f) }
    var targetIndex by remember { mutableStateOf(-1) }
    // Debounced copy of targetIndex that drives the live position numbers. The
    // raw target changes every frame under auto-scroll, which made the numbers
    // flicker wildly (device feedback: "dragging 6 kept becoming 13"). Numbers
    // only renumber after the target has been stable for a beat; the insertion
    // line and the actual drop keep using the raw, immediate target.
    var settledTargetIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(draggedId, targetIndex) {
        if (draggedId == null) {
            settledTargetIndex = -1
        } else if (settledTargetIndex < 0) {
            settledTargetIndex = targetIndex
        } else if (targetIndex != settledTargetIndex) {
            delay(200)
            settledTargetIndex = targetIndex
        }
    }

    fun visibleSortRowBounds(): List<RaftSortRowBounds> =
        listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            val key = info.key as? String ?: return@mapNotNull null
            val id = key.removePrefix("sort-").takeIf { key.startsWith("sort-") } ?: return@mapNotNull null
            val index = order.indexOf(id).takeIf { it >= 0 } ?: return@mapNotNull null
            RaftSortRowBounds(index = index, top = info.offset.toFloat(), size = info.size.toFloat())
        }

    fun recomputeDrag() {
        if (draggedId == null) return
        targetIndex = resolveSortTargetIndex(pointerViewportY, visibleSortRowBounds(), order.lastIndex)
    }

    // Continuous auto-scroll while the finger sits in an edge zone — required
    // for long sections (140+ channels): the user holds near the edge and the
    // list keeps scrolling even without further finger movement. The drag
    // gesture lives on the CONTAINER (below), not on a list row, so scrolling
    // the dragged row off-screen no longer disposes the row and cancels the
    // gesture (task #356 device feedback: auto-release + jitter).
    LaunchedEffect(draggedId) {
        if (draggedId == null) return@LaunchedEffect
        runRaftSortAutoScrollLoop(
            listState = listState,
            isDragging = { draggedId != null },
            pointerViewportY = { pointerViewportY },
            edgeScrollZonePx = edgeScrollZonePx,
            edgeScrollStepPx = edgeScrollStepPx
        ) { delta, consumed ->
            recomputeDrag()
            raftLogInfo(
                "SlockHomeSort",
                "autoscroll delta=$delta consumed=$consumed pointerY=$pointerViewportY " +
                    "target=$targetIndex firstVisible=${listState.firstVisibleItemIndex}"
            )
        }
    }

    // Live position numbers from the SETTLED preview: where each row would sit
    // if the drag dropped at the debounced target. O(n) rebuild only when the
    // settled target moves (not per frame), O(1) lookup per row.
    val previewPositionById: Map<String, Int> =
        draggedId?.takeIf { settledTargetIndex >= 0 }?.let { active ->
            order.moveItem(active, settledTargetIndex)
                .withIndex()
                .associate { (i, id) -> id to i + 1 }
        } ?: emptyMap()

    // raft-ui Dialog on a phone (2026-10-21): a long reorderable list is a 90% sheet with
    // × at the start and the one commit button at the end of its head, no footer.
    RaftDialogSheet(
        onDismissRequest = { if (draggedId == null) onDismiss() },
        title = title,
        commitLabel = saveLabel,
        commitEnabled = draggedId == null,
        onCommit = { onSave(order) },
        closeLabel = stringResource(Res.string.common_close)
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            // Container-level long-press drag: identify the row under the finger
            // at long-press, then track the finger. Because the gesture is on
            // the container it survives list scrolling and preview reordering.
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(order) {
                            // raft-ui 0.32.2 (#787): the drag starts after the 500ms touch hold.
                            raftDetectDragGesturesAfterHold(
                                onDragStart = { pos ->
                                    val rows = visibleSortRowBounds()
                                    val target = resolveSortTargetIndex(pos.y, rows, order.lastIndex)
                                    val id = order.getOrNull(target)
                                    if (id != null) {
                                        draggedId = id
                                        draggedStartIndex = target
                                        draggedRowSizePx = rows.firstOrNull { it.index == target }?.size ?: 0f
                                        pointerViewportY = pos.y
                                        targetIndex = target
                                        raftLogInfo(
                                            "SlockHomeSort",
                                            "dragStart id=$id index=$target posY=${pos.y} size=${order.size}"
                                        )
                                    }
                                },
                                onDragEnd = {
                                    val active = draggedId
                                    var changed = false
                                    if (active != null && targetIndex >= 0 && targetIndex != draggedStartIndex) {
                                        changed = true
                                        userOrder = order.moveItem(active, targetIndex)
                                    }
                                    raftLogInfo(
                                        "SlockHomeSort",
                                        "dragEnd changed=$changed from=$draggedStartIndex to=$targetIndex"
                                    )
                                    draggedId = null
                                    targetIndex = -1
                                },
                                onDragCancel = {
                                    raftLogInfo("SlockHomeSort", "dragCancel id=$draggedId")
                                    draggedId = null
                                    targetIndex = -1
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                if (draggedId != null) {
                                    pointerViewportY += dragAmount.y
                                    recomputeDrag()
                                }
                            }
                        },
                userScrollEnabled = draggedId == null
            ) {
                items(order.size, key = { index -> "sort-${order[index]}" }) { index ->
                    val itemId = order[index]
                    val item = itemsById[itemId] ?: return@items
                    // The list never reflows during a drag. The dragged row keeps
                    // its slot as an invisible placeholder (the visible copy is the
                    // floating overlay); every other row stays exactly in place, so
                    // there is no per-frame reflow to jitter under auto-scroll.
                    if (draggedId == itemId) {
                        Spacer(Modifier.fillMaxWidth().height(with(density) { draggedRowSizePx.toDp() }))
                    } else {
                        // 1-based position before the row icon: live from the settled
                        // preview during a drag (debounced, so no frantic jumping),
                        // fixed slot index otherwise.
                        val position = previewPositionById[itemId] ?: (index + 1)
                        HomeSidebarSortRow(
                            item = item,
                            position = position,
                            modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                            cacheServerId = cacheServerId,
                            agentStatusResolver = agentStatusResolver,
                            memberAvatarResolver = memberAvatarResolver
                        )
                    }
                }
            }

            // Insertion line: a thin bar at the drop target, so where the row will
            // land is explicit without reflowing the list. Drawn at the top of the
            // visible row currently at targetIndex (or the bottom of the last
            // visible row when the target is below the fold).
            val active = draggedId
            if (active != null && targetIndex >= 0) {
                val bounds = visibleSortRowBounds()
                // Direction-aware so the line matches where moveItem actually drops:
                // dragging DOWN lands after the target row (line at its bottom),
                // dragging UP lands before it (line at its top).
                val targetRow = bounds.firstOrNull { it.index == targetIndex }
                val lineY =
                    when {
                        targetRow != null && targetIndex >= draggedStartIndex -> targetRow.top + targetRow.size
                        targetRow != null -> targetRow.top
                        else -> bounds.maxByOrNull { it.index }?.let { it.top + it.size }
                    }
                if (lineY != null) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = HomeListHorizontalPadding)
                                .offset { IntOffset(0, lineY.roundToInt()) }
                                .height(2.dp)
                                .background(RaftColor.AccentPink)
                    )
                }
            }

            // Floating copy of the dragged row, pinned under the finger. Never
            // part of the LazyColumn, so it is never disposed mid-drag.
            if (active != null) {
                val item = itemsById[active]
                if (item != null) {
                    // Held row shows its settled landing position (debounced target).
                    val position = previewPositionById[active] ?: (draggedStartIndex + 1)
                    HomeSidebarSortRow(
                        item = item,
                        position = position,
                        held = true,
                        cacheServerId = cacheServerId,
                        agentStatusResolver = agentStatusResolver,
                        memberAvatarResolver = memberAvatarResolver,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = HomeListHorizontalPadding)
                                .offset {
                                    val info = listState.layoutInfo
                                    IntOffset(
                                        0,
                                        resolveSortDraggedRowTop(
                                            pointerY = pointerViewportY,
                                            viewportStart = info.viewportStartOffset.toFloat(),
                                            viewportEnd = info.viewportEndOffset.toFloat(),
                                            rowSize = draggedRowSizePx
                                        ).roundToInt()
                                    )
                                }
                    )
                }
            }
        }
    }
}

/**
 * The sheet's order against the rows currently in the section: keep the person's order for
 * rows still there, drop rows that left, append rows that arrived (in section order).
 */
internal fun reconcileSidebarSortOrder(userOrder: List<String>, itemIds: List<String>): List<String> {
    val present = itemIds.toSet()
    val kept = userOrder.filter { it in present }
    if (kept.size == itemIds.size) return kept
    val keptSet = kept.toSet()
    return kept + itemIds.filterNot { it in keptSet }
}

/**
 * A sort sheet row: the live 1-based [position], then the conversation as a display-only
 * [RaftSidebarItem]. [held] is the floating copy under the finger, drawn pressed.
 */
@Composable
private fun HomeSidebarSortRow(
    item: RaftHomeConversationItem,
    position: Int,
    modifier: Modifier = Modifier,
    held: Boolean = false,
    cacheServerId: String? = null,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver
) {
    val isDirectMessage = item.isDirectMessageConversation()
    val pressSource = remember { MutableInteractionSource() }
    if (held) LaunchedEffect(pressSource) { pressSource.emit(PressInteraction.Press(Offset.Zero)) }
    Row(
        modifier = modifier.then(if (held) Modifier.clearAndSetSemantics {} else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.widthIn(min = 22.dp).padding(end = RaftAppSpacing.Xs), contentAlignment = Alignment.CenterEnd) {
            Text("$position", style = RaftType.Button, color = RaftColor.TextMutedGray, textAlign = TextAlign.End)
        }
        RaftSidebarItem(
            title = remember(item.name) { homeSidebarUserContentName(item.name) },
            modifier = Modifier.weight(1f),
            onClick = if (held) ({}) else null,
            tone = if (item.joined || isDirectMessage) RaftRowTone.Default else RaftRowTone.Away,
            leading = {
                if (isDirectMessage) {
                    // The sidebar DM row's avatar (HomeSidebarDirectMessageRow): the member's
                    // real (pixel) avatar from the resolver/cache, the agent status pixel.
                    val memberId = item.presenceMemberId
                    val isAgent = item.peerType.equals("agent", ignoreCase = true)
                    val isAppBot = rememberRaftMemberAppBot(memberId?.takeIf { isAgent }) != null
                    DirectMessageAvatar(
                        agentMemberId = raftHomeDmPresenceAgentId(memberId, isAgent = isAgent, isAppBot = isAppBot),
                        agentStatusResolver = agentStatusResolver,
                        avatarWireValue = rememberRaftMemberAvatarWireValue(memberAvatarResolver, memberId),
                        avatarLabel = item.name.ifBlank { item.avatarLabel },
                        cacheSubject = raftMemberAvatarCacheSubject(item.peerType.homeDmPeerMemberKindOrNull(), memberId),
                        cacheServerId = cacheServerId,
                        fallbackFill = raftAvatarFallbackFill(if (isAgent) MemberKind.Agent else MemberKind.Human)
                    )
                } else {
                    HomeSidebarChannelGlyph(item.homeChannelSymbol())
                }
            },
            interactionSource = pressSource
        )
    }
}

/**
 * A person or agent conversation in the Home list (web `SidebarPeerRowBody`): a
 * [RaftSidebarItem] led by the 19dp avatar with the agent's status pixel on its corner, the
 * name with the muted bell right after it and the description as its subtitle, then the
 * aside: the unread count alone, otherwise the draft mark and the agent's meta (capped at 58%
 * of the row). [menuOpen]: this row's long-press menu is open.
 */
@Composable
internal fun HomeSidebarDirectMessageRow(
    name: String,
    avatarLabel: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    peerType: String? = null,
    memberId: String? = null,
    cacheServerId: String? = null,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    selected: Boolean = false,
    muted: Boolean = false,
    unreadCount: Int = 0,
    markedUnread: Boolean = false,
    hasDraft: Boolean = false,
    interactionIdentity: String = "dm:${memberId.orEmpty()}:$name",
    menuOpen: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: ((RaftPopoverAnchor) -> Unit)? = null
) {
    val isAgent = peerType?.equals("agent", ignoreCase = true) == true
    val badge =
        homeConversationBadgeProjection(
            HomeConversationBadgeInput(unreadCount = unreadCount, muted = muted, markedUnread = markedUnread)
        )
    val avatarWireValue = rememberRaftMemberAvatarWireValue(memberAvatarResolver, memberId)
    // botiverse/slock #11273 (web `DmRow` / `AgentDmRow`): an App Bot's name carries the Bot
    // badge; it shows no status pixel and no runtime or activity meta.
    val appBot = rememberRaftMemberAppBot(memberId?.takeIf { isAgent })
    // The agent's status is read only inside the avatar-dot and trailing-meta leaves below, so
    // an agent:activity push recomposes those two leaves, not the whole row.
    val agentMemberId = raftHomeDmPresenceAgentId(memberId, isAgent = isAgent, isAppBot = appBot != null)
    val trailingSlots =
        raftHomeDmTrailingSlots(
            badge = badge.label,
            hasDraft = hasDraft,
            isAgent = agentMemberId != null,
            isAppBot = appBot != null,
            markedUnread = badge.markedUnreadDot
        )
    val markedUnreadDescription =
        if (badge.markedUnreadDot) stringResource(Res.string.home_sidebar_marked_unread) else null
    val mutedIcon = homeConversationMutedIndicatorIcon(muted)
    val longClick = rememberHomeSidebarLongClick(interactionIdentity, onLongPress)
    val title = remember(name) { homeSidebarUserContentName(name) }
    traceMarkdownSection(RaftHomeTraceNames.DIRECT_MESSAGE_ROW_RENDER, name.length) {
        RaftSidebarItem(
            title = title,
            modifier = modifier.then(longClick.anchorModifier),
            onClick = onClick,
            selected = selected,
            tone = homeConversationRowTone(muted = muted, joined = true, selected = selected, loudUnread = badge.hasUnread),
            subtitle = description?.takeIf { it.isNotBlank() },
            onLongClick = longClick.onLongClick,
            onLongClickLabel = longClick.onLongClick?.let { stringResource(Res.string.home_sidebar_row_menu_action) },
            menuOpen = menuOpen,
            titleTrailing =
                if (appBot != null || mutedIcon != null) {
                    {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (appBot != null) RaftAppBotBadge(identity = appBot, botName = title.text)
                            mutedIcon?.let { icon -> HomeSidebarLucideIcon(icon, HomeSidebarMetaIconSize) }
                        }
                    }
                } else {
                    null
                },
            variant = RaftSidebarItemVariant.Peer,
            leading = {
                DirectMessageAvatar(
                    agentMemberId = agentMemberId,
                    agentStatusResolver = agentStatusResolver,
                    avatarWireValue = avatarWireValue,
                    avatarLabel = name.ifBlank { avatarLabel },
                    cacheSubject = raftMemberAvatarCacheSubject(peerType.homeDmPeerMemberKindOrNull(), memberId),
                    cacheServerId = cacheServerId,
                    fallbackFill = raftAvatarFallbackFill(if (isAgent) MemberKind.Agent else MemberKind.Human)
                )
            },
            trailing =
                if (trailingSlots.isNotEmpty()) {
                    {
                        trailingSlots.forEach { slot ->
                            when (slot) {
                                RaftHomeDmTrailingSlot.UnreadBadge ->
                                    when (badge.tone) {
                                        HomeSidebarBadgeTone.Loud -> RaftSidebarItemMarks(unreadCount = unreadCount, countDescription = null)
                                        HomeSidebarBadgeTone.Quiet -> RaftSidebarItemMeta(badge.label.orEmpty())
                                    }
                                RaftHomeDmTrailingSlot.MarkedUnreadDot ->
                                    RaftQuietUnreadDot(contentDescription = markedUnreadDescription)
                                RaftHomeDmTrailingSlot.Draft -> HomeSidebarLucideIcon(RaftLucideIconName.Pencil, HomeSidebarMetaIconSize)
                                RaftHomeDmTrailingSlot.AgentMeta ->
                                    DirectMessageAgentMeta(
                                        agentMemberId = agentMemberId,
                                        agentStatusResolver = agentStatusResolver,
                                        selected = selected,
                                        modifier = Modifier.weight(1f, fill = false).raftHomeDmTrailingColumnCap()
                                    )
                            }
                        }
                    }
                } else {
                    null
                }
        )
    }
}

/** Web `SIDEBAR_ROW_TRAILING_CLASS` `max-w-[58%]`, applied to the whole trailing column. */
private fun Modifier.raftHomeDmTrailingColumnCap(): Modifier =
    layout { measurable, constraints ->
        val cap =
            if (constraints.hasBoundedWidth) {
                (constraints.maxWidth * DirectMessageTrailingMaxWidthFraction).toInt()
            } else {
                constraints.maxWidth
            }
        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = cap))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

@Composable
private fun rememberDirectMessageAgentStatus(
    agentStatusResolver: RaftAgentStatusResolver,
    agentMemberId: String?
): RaftAgentStatusIndicatorState? =
    // A missing resolver value means presence is still Unknown/not ready. Do not manufacture
    // Offline: that falsely renders a gray dot while the authoritative member/presence
    // projection is loading or recovering.
    projectHomeAgentStatusIndicatorState(
        showPresence = agentMemberId != null,
        memberId = agentMemberId,
        canonicalState = rememberRaftAgentStatusIndicatorState(agentStatusResolver, agentMemberId)
    )

/**
 * Web `AvatarSlot badge={<SidebarAgentActivityBadge/>}`: the agent's status pixel (`sm`, 8px)
 * on the avatar's corner, so it stays visible beside an unread count. Static, never pulsing
 * (web `activityPresence`: "an ambient state never loops"). A leaf: it alone reads the status.
 */
@Composable
private fun DirectMessageAvatar(
    agentMemberId: String?,
    agentStatusResolver: RaftAgentStatusResolver,
    avatarWireValue: String?,
    avatarLabel: String,
    cacheSubject: RaftAvatarCacheSubject.Member?,
    cacheServerId: String?,
    fallbackFill: Color
) {
    val status = rememberDirectMessageAgentStatus(agentStatusResolver, agentMemberId)
    RaftAvatarWithStatus(
        statusColor = status?.color,
        avatarSize = ChannelAvatarSize,
        statusPulsing = false
    ) {
        RaftAppAvatar(
            avatarWireValue = avatarWireValue,
            label = avatarLabel,
            cacheSubject = cacheSubject,
            cacheServerId = cacheServerId,
            fallbackFill = fallbackFill
        )
    }
}

/**
 * Web `SidebarAgentMeta`: while the agent works, what it is doing (`rf-srow__meta--live`,
 * Grotesk 13/18 regular, foreground-muted); otherwise its runtime name (`SidebarItemMeta`,
 * Space Mono 12/16, foreground-hint). Sticker ink on the selected row. One line with an
 * ellipsis. Visual only (web `aria-hidden`): the row's accessible name stays the agent's name
 * and a sentence that changes every few seconds is not announced. A leaf: it alone reads the
 * status, so an agent:activity push recomposes only this text.
 */
@Composable
private fun DirectMessageAgentMeta(
    agentMemberId: String?,
    agentStatusResolver: RaftAgentStatusResolver,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val status = rememberDirectMessageAgentStatus(agentStatusResolver, agentMemberId)
    val meta = raftHomeDmAgentMeta(status) ?: return
    val (text, style, color) =
        when (meta) {
            is RaftHomeDmAgentMeta.Live ->
                Triple(
                    meta.activity.localizedText(),
                    RaftType.Caption.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    RaftColor.TextMuted
                )
            is RaftHomeDmAgentMeta.Runtime ->
                Triple(
                    meta.label,
                    RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, fontFamily = RaftMonoFamily),
                    RaftColor.ForegroundHint
                )
        }
    Text(
        text,
        style = style,
        color = if (selected) RaftColor.StickerInk else color,
        modifier = modifier.clearAndSetSemantics {},
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

internal fun raftMemberAvatarCacheSubject(
    kind: MemberKind?,
    memberId: String?
): RaftAvatarCacheSubject.Member? {
    val cleanMemberId = memberId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val cacheableKind = kind?.takeIf { it == MemberKind.Human || it == MemberKind.Agent } ?: return null
    return RaftAvatarCacheSubject.Member(cacheableKind, cleanMemberId)
}

internal data class RaftMemberAvatarCacheIdentity(
    val subject: RaftAvatarCacheSubject.Member,
    val serverId: String
)

internal fun raftMemberAvatarCacheIdentity(
    kind: MemberKind?,
    memberId: String?,
    serverId: String?
): RaftMemberAvatarCacheIdentity? {
    val cleanServerId = serverId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val subject = raftMemberAvatarCacheSubject(kind, memberId) ?: return null
    return RaftMemberAvatarCacheIdentity(subject = subject, serverId = cleanServerId)
}

internal fun MemberProfile.raftAvatarCacheSubject(): RaftAvatarCacheSubject.Member? =
    raftMemberAvatarCacheSubject(kind, id.value)

internal fun MemberProfile.raftAvatarCacheIdentity(
    cacheServerId: String? = serverId?.value
): RaftMemberAvatarCacheIdentity? {
    val cleanCacheServerId = cacheServerId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val profileServerId = serverId?.value?.trim()?.takeIf(String::isNotEmpty)
    if (profileServerId != null && profileServerId != cleanCacheServerId) return null
    return raftMemberAvatarCacheIdentity(kind, id.value, cleanCacheServerId)
}

@Composable
internal fun RaftAppAvatar(
    avatarWireValue: String?,
    label: String,
    modifier: Modifier = Modifier,
    avatarSize: Dp = ChannelAvatarSize,
    // Unspecified: raft-ui's `border-[1.5px] border-sticker-ink` edge at every size;
    // 0 when the caller draws its own frame.
    borderWidth: Dp = Dp.Unspecified,
    diagnostics: RaftAvatarRowDiagnostics? = null,
    cacheSubject: RaftAvatarCacheSubject? = null,
    cacheServerId: String? = null,
    // The sticker fill under a photo or initials: `palette-lavender` (people) by default;
    // raft-ui agents sit on `inactive` until their pixel avatar covers it
    // ([raftAvatarFallbackFill]).
    fallbackFill: Color = RaftColor.AccentLavender
) {
    // #192 trace scope extension: per-phase attribution for avatar rendering. The
    // avatar is rendered for every channel row in Home + every participant in
    // Thread — that's a lot of recompositions on scroll, so we want to know whether
    // it's the identify / http-fetch / render / fallback-default that's hot.
    val displayAvatarWireValue = avatarWireValue?.appAvatarWireValueOrNull()
    val gravatarHttpSource = remember(displayAvatarWireValue) {
        displayAvatarWireValue?.gravatarAvatarHttpUrlOrNull()
    }
    val httpAvatarSource = remember(displayAvatarWireValue, gravatarHttpSource) {
        gravatarHttpSource
            ?: displayAvatarWireValue?.takeIf {
                it.startsWith("http://") || it.startsWith("https://")
            }
    }
    val pixelSpec = remember(displayAvatarWireValue) {
        traceMarkdownSectionNonComposable(RaftAvatarTraceNames.IDENTIFY) {
            displayAvatarWireValue?.toPixelAvatarSpec()
        }
    }
    val step = RaftAvatarStep.nearest(avatarSize)
    when {
        pixelSpec != null -> traceMarkdownSection(RaftAvatarTraceNames.RENDER, displayAvatarWireValue?.length ?: 0) {
            RaftPixelAvatarDrawCache.onCompose()
            RaftAppAvatarFrame(modifier, avatarSize, borderWidth, pixelSpec.background) {
                RaftNativePixelAvatar(pixelSpec)
            }
        }
        httpAvatarSource != null -> RaftAppAvatarFrame(modifier, avatarSize, borderWidth, fallbackFill) {
            // task #343: an uploaded avatar must not flash the fallback while its cached
            // bytes are still being resolved (cold start reads them from SQLite + native). Keep
            // the neutral background until the load settles; draw the fallback only on a
            // definitive failure. Gravatar keeps the immediate fallback: it is often unreachable
            // (CN) and the fallback is its expected face.
            if (gravatarHttpSource != null) RaftAppAvatarFallback(label, step)
            RaftAvatarHttpImage(
                source = httpAvatarSource,
                modifier = Modifier.fillMaxSize(),
                diagnostics = diagnostics,
                cacheSubject = cacheSubject,
                cacheServerId = cacheServerId,
                fallback = { if (gravatarHttpSource == null) RaftAppAvatarFallback(label, step) }
            )
        }
        else -> traceMarkdownSection(RaftAvatarTraceNames.FALLBACK_DEFAULT, displayAvatarWireValue?.length ?: 0) {
            RaftAppAvatarFrame(modifier, avatarSize, borderWidth, fallbackFill) {
                RaftAppAvatarFallback(label, step)
            }
        }
    }
}

/**
 * raft-ui Avatar root + image slot: an [avatarSize] square sticker (`rounded-sticker`, 4dp)
 * on [fill], with a [borderWidth] `sticker-ink` edge and, outside it, the 1.5dp
 * `sticker-edge` ring (transparent in light, the die-cut edge in dark; it takes no layout
 * space). The [face] fills the inside of the edge with 2dp corners (`rounded-[2px]`), so a
 * photo or pixel face is never painted under the ink. An unspecified [borderWidth] takes the
 * raft-ui 1.5dp edge.
 */
@Composable
internal fun RaftAppAvatarFrame(
    modifier: Modifier,
    avatarSize: Dp,
    borderWidth: Dp,
    fill: Color,
    face: @Composable BoxScope.() -> Unit
) {
    val edge = if (borderWidth != Dp.Unspecified) borderWidth else RaftAppStroke.Control
    val ringColor = if (edge > 0.dp) RaftColor.StickerEdge else Color.Transparent
    Box(
        modifier =
            modifier
                .then(
                    if (ringColor.alpha > 0f) {
                        Modifier.drawBehind {
                            // `shadow-[0_0_0_1.5px_var(--sticker-edge)]`: a band outside the
                            // box whose corners follow the sticker radius.
                            val ring = RaftAppStroke.Control.toPx()
                            val outset = ring / 2f
                            drawRoundRect(
                                color = ringColor,
                                topLeft = Offset(-outset, -outset),
                                size = Size(size.width + outset * 2f, size.height + outset * 2f),
                                cornerRadius = CornerRadius(RaftAppRadius.Sticker.toPx() + outset),
                                style = Stroke(width = ring)
                            )
                        }
                    } else {
                        Modifier
                    }
                )
                .size(width = avatarSize, height = avatarSize)
                .clip(RaftAvatarStickerShape)
                .background(fill)
                .then(if (edge > 0.dp) Modifier.border(edge, RaftColor.StickerInk, RaftAvatarStickerShape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(edge).clip(RaftAvatarFaceShape),
            contentAlignment = Alignment.Center,
            content = face
        )
    }
}

// raft-ui Avatar root: `rounded-sticker` (4dp).
private val RaftAvatarStickerShape = RoundedCornerShape(RaftAppRadius.Sticker)

// raft-ui `image` / `fallback` slots: `rounded-[2px]` inside the 1.5dp ink edge.
private val RaftAvatarFaceShape = RoundedCornerShape(2.dp)

/**
 * raft-ui AvatarFallback: a person without a photo shows their initials (Space Grotesk
 * bold, `leading-none`, -0.01em, `sticker-ink`, sized per avatar step: 40 / 22 / 15 / 13 /
 * 11 / 8); with no name to take them from, the placeholder person icon at the step's size.
 * The initials are decoration: the row or header around the avatar names the person, so
 * they are hidden from accessibility services.
 */
@Composable
private fun RaftAppAvatarFallback(label: String, step: RaftAvatarStep) {
    val initials = remember(label, step) { raftAppAvatarInitials(label, step) }
    if (initials.isEmpty()) {
        RaftLucideIcon(
            RaftLucideIconName.User,
            modifier = Modifier.size(step.iconSize),
            color = raftArgbHex(RaftColor.StickerInk)
        )
        return
    }
    Text(
        text = initials,
        modifier = Modifier.clearAndSetSemantics {},
        style =
            RaftType.Body
                .withRaftFontWeight(FontWeight.Bold)
                .copy(
                    fontSize = step.initialsSize,
                    lineHeight = step.initialsSize,
                    letterSpacing = (-0.01).em,
                    textAlign = TextAlign.Center
                ),
        color = RaftColor.StickerInk,
        maxLines = 1,
        softWrap = false
    )
}

/**
 * The name a member's avatar takes its initials from: the display name, else the handle,
 * never the member id the profiles fall back to; then the stored [MemberProfile.avatarLabel].
 */
internal fun MemberProfile.raftAvatarName(): String =
    listOf(displayName, handle).map(String::trim).firstOrNull { it.isNotEmpty() && it != id.value }
        ?: avatarLabel

/** raft-ui Avatar `type`: an agent without a face sits on `inactive`, a person on `palette-lavender`. */
internal fun raftAvatarFallbackFill(kind: MemberKind?): Color =
    if (kind == MemberKind.Agent) RaftColor.Inactive else RaftColor.AccentLavender

private val RaftAppPresence.homeRowColor: Color
    get() = when (this) { RaftAppPresence.Online -> RaftColor.AccentLime; RaftAppPresence.Offline -> RaftColor.StatusNeutralGray; RaftAppPresence.Unknown -> RaftColor.BrandYellow }

internal fun pixelAvatarGridEdge(index: Int, extent: Float): Float =
    when {
        index <= 0 -> 0f
        index >= PixelAvatarGridCellCount -> extent
        else -> (extent * index / PixelAvatarGridCellCount).roundToInt().toFloat()
    }

internal data class PixelAvatarSpec(
    val background: Color,
    val grid: List<String>,
    // Palette lookups resolved at construction. Defaults are the generated bundled table
    // (safe during RaftPixelAvatarDataKt class init: they are that file's own fields);
    // RaftPixelAvatarRuntime passes the downloaded document's own palette instead.
    val palette: Map<Char, Color> = PixelAvatarPalette,
    val paletteArgb: Map<Char, String> = PixelAvatarPaletteArgb,
    // Null means "use the bundled PixelAvatarKnownBackgroundArgb" — resolved lazily below,
    // NOT as a default argument, which would re-enter RaftHomeComponentsKt mid-init.
    val backgroundArgbLookup: Map<Color, String>? = null
) {
    val spans: List<PixelAvatarSpan> =
        buildList {
            grid.forEachIndexed { rowIndex, row ->
                var spanColor: Color? = null
                var spanColorArgb: String? = null
                var spanStart = 0
                var spanLength = 0
                fun flushSpan() {
                    val color = spanColor ?: return
                    add(
                        PixelAvatarSpan(
                            rowIndex = rowIndex,
                            startColumnIndex = spanStart,
                            columnCount = spanLength,
                            color = color,
                            colorArgb = spanColorArgb ?: raftArgbHex(RaftColor.StickerInk)
                        )
                    )
                    spanColor = null
                    spanColorArgb = null
                    spanLength = 0
                }
                row.forEachIndexed { columnIndex, key ->
                    val color = palette[key] ?: Color.Transparent
                    val colorArgb = paletteArgb[key]
                    when {
                        color == Color.Transparent -> flushSpan()
                        color == spanColor -> spanLength += 1
                        else -> {
                            flushSpan()
                            spanColor = color
                            spanColorArgb = colorArgb
                            spanStart = columnIndex
                            spanLength = 1
                        }
                    }
                }
                flushSpan()
            }
        }

    // Lazy: PixelAvatarFixedSpecs (generated file) constructs specs during its
    // class init, which RaftHomeComponentsKt's own init triggers — an eager
    // read here hits PixelAvatarKnownBackgroundArgb before it is assigned
    // (init cycle -> NPE in every unit test touching this file).
    val backgroundArgb: String by lazy(LazyThreadSafetyMode.NONE) {
        (backgroundArgbLookup ?: PixelAvatarKnownBackgroundArgb)[background] ?: RaftColor.BrandYellowArgb
    }

    val nativeSpanPayload: String =
        spans.joinToString(separator = ";") { span ->
            "${span.rowIndex},${span.startColumnIndex},${span.columnCount},${span.colorArgb}"
        }

    // Lazy for the same reason: its template reads backgroundArgb, which would
    // force that lazy right back during the generated file's class init.
    val nativeCacheKey: String by lazy(LazyThreadSafetyMode.NONE) {
        "$backgroundArgb|${grid.joinToString(separator = "/")}"
    }
}

internal data class PixelAvatarSpan(
    val rowIndex: Int,
    val startColumnIndex: Int,
    val columnCount: Int,
    val color: Color,
    val colorArgb: String
)
private const val PixelAvatarGridCellCount = 8
private const val PixelAvatarProtocolPrefix = "pixel:"
private const val PixelAvatarRandomPrefix = "random:"
// PixelAvatarDefaultKey / PixelAvatarReservedKeys / PixelAvatarPalette(Argb) /
// PixelAvatarFixedSpecs are GENERATED from the canonical web pixelAvatars.json —
// see RaftPixelAvatarData.kt (scripts/generate_pixel_avatars.mjs, task #352).

/**
 * #195/#199 diagnostic counter retained for avatar render churn. Android now routes pixel
 * avatars through a host bitmap cache; non-Android hosts keep the shared Canvas fallback.
 * Logging remains aggregate-only and does not include avatar content.
 */
private object RaftPixelAvatarDrawCache {
    private var composeCount: Int = 0
    private var rebuildCount: Int = 0

    fun onCompose() {
        composeCount += 1
        maybeLog()
    }

    fun onRebuild() {
        rebuildCount += 1
        maybeLog()
    }

    private var lastLogAtCompose: Int = 0

    private fun maybeLog() {
        // Log every 64 composes (~one screen worth of avatar rows). Avoid spamming logcat.
        if (composeCount - lastLogAtCompose < 64) return
        lastLogAtCompose = composeCount
        val hitRate =
            if (composeCount == 0) 0.0
            else 1.0 - rebuildCount.toDouble() / composeCount.toDouble()
        // Debug-only log; intentionally keep at info level so it's easy to grep in logcat.
        // iOS / JS see the side effect of counters updating but no log line is emitted
        // (the android.util.Log call is platform-conditional below).
        // Note: avoid String.format for cross-platform compat (not in Kotlin/Native stdlib);
        // manual integer-truncation to two decimals is fine for diagnostic purposes.
        val hitRatePct = (hitRate * 100.0).toInt()
        logPixelAvatarDrawCache("composes=$composeCount rebuilds=$rebuildCount hitRatePct=$hitRatePct%")
    }

    fun snapshot(): Pair<Int, Int> = composeCount to rebuildCount

    fun resetForTest() {
        composeCount = 0
        rebuildCount = 0
        lastLogAtCompose = 0
    }
}

internal expect fun logPixelAvatarDrawCache(message: String)
private val PixelAvatarKnownBackgroundArgb: Map<Color, String> =
    pixelAvatarKnownBackgroundArgb(
        palette = PixelAvatarPalette,
        paletteArgb = PixelAvatarPaletteArgb,
        literalBackgroundArgb = PixelAvatarLiteralBackgroundArgb
    )
private val PixelAvatarRandomOptions = listOf(
    PixelAvatarPalette.getValue('C') to 'K',
    PixelAvatarPalette.getValue('Y') to 'K',
    PixelAvatarPalette.getValue('L') to 'K',
    PixelAvatarPalette.getValue('P') to 'W',
    PixelAvatarPalette.getValue('V') to 'K',
    Color(0xFF1E1E1C) to 'C', // raft-color: pixel-art avatar palette
    Color(0xFF1E1E1C) to 'G', // raft-color: pixel-art avatar palette
    Color(0xFF1E1E1C) to 'P', // raft-color: pixel-art avatar palette
    Color(0xFF1E1E1C) to 'Y', // raft-color: pixel-art avatar palette
    Color(0xFF1E1E1C) to 'V', // raft-color: pixel-art avatar palette
    PixelAvatarPalette.getValue('O') to 'K',
    PixelAvatarPalette.getValue('C') to 'W'
)
private fun String.toPixelAvatarSpec(): PixelAvatarSpec? =
    pixelAvatarSpecForWireValue(this)

internal fun pixelAvatarSpecForWireValue(value: String): PixelAvatarSpec? {
    val normalized = value.trim().takeIf { it.isNotBlank() } ?: return null
    normalized.removePrefixOrNull(PixelAvatarProtocolPrefix)?.let { key ->
        key.removePrefixOrNull(PixelAvatarRandomPrefix)?.let { seed ->
            return seed.takeIf { it.isNotBlank() }?.let(::randomPixelAvatar)
        }
        return RaftPixelAvatarRuntime.current.let { data ->
            data.specs[key] ?: data.specs.getValue(data.defaultKey)
        }
    }
    return null
}

/**
 * Returns the picker keys in display order, mirroring React
 * `AVATAR_KEYS = Object.keys(AVATARS).filter(k => !RESERVED_AVATAR_KEYS.includes(k))`.
 * Includes the default key (`robot`) — React renders the default as a separate
 * second button in the picker (`title="Default (robot)"`) so callers prepend
 * the Upload cell and use this list directly for the remaining keys.
 * Used by the avatar picker modal grid in `MembersAgentProfileTab`.
 */
internal val PixelAvatarPickerKeys: List<String>
    get() = RaftPixelAvatarRuntime.current.let { data -> data.specs.keys.filterNot { it in data.reservedKeys } }
private fun String.removePrefixOrNull(prefix: String): String? = takeIf { it.startsWith(prefix) }?.substring(prefix.length)
private fun randomPixelAvatar(seed: String): PixelAvatarSpec {
    val random = PixelAvatarRandom(pixelHash(seed))
    val (background, fill) = PixelAvatarRandomOptions[(random.next() * PixelAvatarRandomOptions.size).toInt()]
    val grid = List(8) { val left = List(4) { if (random.next() < 0.45) fill else '_' }; (left + left.asReversed()).joinToString("") }
    return PixelAvatarSpec(background, grid)
}
private fun pixelHash(value: String): Int { var hash = 0; value.forEach { hash = (hash shl 5) - hash + it.code }; return hash }
private class PixelAvatarRandom(seed: Int) { private var value = seed; fun next(): Double { value += 1831565813; var mixed = (value xor (value ushr 15)) * (1 or value); mixed = (mixed + ((mixed xor (mixed ushr 7)) * (61 or mixed))) xor mixed; val unsigned = (mixed xor (mixed ushr 14)).toLong() and 0xffffffffL; return unsigned.toDouble() / 4294967296.0 } }
