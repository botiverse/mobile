package build.raft.app

import build.raft.app.base.Utils
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalInputField
import build.raft.app.components.SlockBrutalPanel
import build.raft.app.components.SlockExternalBridgeBadge
import build.raft.app.components.externalBridgeBadgeVisible
import build.raft.app.components.SlockHashGlobeIcon
import build.raft.app.components.SlockPublicPageGlobe
import build.raft.app.components.channelOnPublicPage
import build.raft.app.components.SlockSkeleton
import build.raft.app.components.SlockSkeletonVariant
import build.raft.app.data.ChannelRepository
import build.raft.app.data.DataResult
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.EmptyReason
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeServerDrawerConversationSection
import build.raft.app.data.HomeServerDrawerLoadStatus
import build.raft.app.data.HomeServerDrawerNormalizedContent
import build.raft.app.data.HomeServerDrawerNormalizedRow
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.NetworkSlockAnnouncementsRemindersApi
import build.raft.app.data.NetworkSlockHomeApi
import build.raft.app.data.OpenDirectMessageResult
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.SettingsServerSidebarCustomSection
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.SettingsServerSidebarSectionPlacement
import build.raft.app.data.SIDEBAR_SECTION_ITEM_KIND_AGENT
import build.raft.app.data.SIDEBAR_SECTION_ITEM_KIND_CHANNEL
import build.raft.app.data.normalizeSidebarSectionOrder
import build.raft.app.data.ScreenError
import build.raft.app.data.APP_UPDATE_HOME_NOTIFICATION_ID
import build.raft.app.data.SharedHomeDataSource
import build.raft.app.data.KeyValueNotificationPreferenceStore
import build.raft.app.data.PersistentNotificationPreferenceRepository
import build.raft.app.data.slockSharedPreferenceStore
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SlockComputersNetworkApi
import build.raft.app.data.SlockAvatarCacheSubject
import build.raft.app.data.SlockAvatarCommitIdentity
import build.raft.app.data.SlockAvatarMountedBinding
import build.raft.app.data.SlockAvatarMountedHandle
import build.raft.app.data.SlockSharedStoreRegistry
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.parseSlockAvatarMountedRenderModel
import build.raft.app.data.parseSlockHome
import build.raft.app.model.AvatarReference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.model.appAvatarApiFallbackUrl
import build.raft.app.model.appAvatarWireValueOrNull
import build.raft.app.model.gravatarAvatarHttpUrlOrNull
import build.raft.app.model.parseSlockTimestampEpochMillisOrNull
import build.raft.app.resources.MR
import com.tencent.kuikly.core.manager.BridgeManager
import com.tencent.kuikly.core.module.RouterModule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.coil3.AsyncImagePainter
import com.tencent.kuikly.compose.coil3.rememberAsyncImagePainter
import com.tencent.kuikly.compose.extension.scrollToTop
import com.tencent.kuikly.compose.foundation.Canvas
import com.tencent.kuikly.compose.ui.draw.alpha
import com.tencent.kuikly.compose.ui.draw.clipToBounds
import com.tencent.kuikly.compose.ui.draw.drawBehind
import com.tencent.kuikly.compose.foundation.Image
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.gestures.detectDragGestures
import com.tencent.kuikly.compose.foundation.gestures.detectDragGesturesAfterLongPress
import com.tencent.kuikly.compose.foundation.gestures.detectHorizontalDragGestures
import com.tencent.kuikly.compose.foundation.gestures.detectTapGestures
import com.tencent.kuikly.compose.foundation.hoverable
import com.tencent.kuikly.compose.foundation.interaction.MutableInteractionSource
import com.tencent.kuikly.compose.foundation.interaction.collectIsHoveredAsState
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.input.pointer.positionChanged
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.BoxScope
import com.tencent.kuikly.compose.foundation.layout.BoxWithConstraints
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.LazyListState
import com.tencent.kuikly.compose.foundation.lazy.rememberLazyListState
import com.tencent.kuikly.compose.material3.rememberPullToRefreshState
// #192 trace scope extension: reuse the markdown trace helper for avatar phase
// attribution (process-wide `expect/actual` infrastructure; one `traceMarkdownSection`
// overload set covers both `markdown.*` and `avatar.*` / future `composer.*` /
// `inbox.*` / `tabbar.*` phase names).
import build.raft.app.markdown.traceMarkdownSection
import build.raft.app.markdown.traceMarkdownSectionNonComposable
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.rotate
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.Path
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.semantics.isTraversalGroup
import com.tencent.kuikly.compose.ui.semantics.semantics
import com.tencent.kuikly.compose.ui.semantics.stateDescription
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import kotlinx.coroutines.launch
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.tmm.kmmresource.compose.localized
import com.tencent.tmm.kmmresource.resource.format
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.time.TimeSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val SLOCK_HOME_PAGE = "compose_home"
private const val HOME_PAGE_DATA_EVENT = "slockPageData"

private val SidebarActionIconSize = 14.dp
private val SidebarActionIconBoxSize = 14.dp
private val SidebarActionRowHeight = 32.dp
private val HomeSmallShadowOffset = 2.dp
private val SidebarRowHorizontalPadding = 8.dp
private val SidebarActionLabelGap = 6.dp
private val SidebarActionTrailingSlotWidth = 36.dp
private val ChannelSymbolWidth = 18.dp
private val ChannelSymbolIconSize = 14.dp
private val ChannelAvatarSize = 20.dp
private val ChannelAvatarFontSize = 11.sp
private val ChannelRowHeight = 32.dp
private const val EdgeAutoScrollStepPx = 18f
private val ChannelRowHorizontalPadding = 8.dp
private val ChannelRowShadowOffset = 2.dp
private val ChannelRowMutedIconSize = 12.dp
private val ChannelRowMutedIconGap = 4.dp
private val DirectMessageStatusDotSize = SlockStatusIndicatorTokens.InlineSize
private val CountPillMinWidth = 18.dp
private val CountPillHeight = 16.dp
private val HomeUnreadBadgeRadius = 4.dp
private val HomeUnreadBadgeTextOffsetY = 0.dp
private val HomeListHorizontalPadding = SlockSpacing.Md
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
internal val HomeServerSwitcherDragHandleIconName = SlockLucideIconName.GripVertical
private const val HomeServerSwitcherMaxVisibleRows = 6
private const val HomeServerBadgeTiltDegrees = -2f
private val HomeServerBadgeHorizontalInset = 14.dp
private val HomeServerBadgeVerticalInset = 6.dp
private val HomeServerBadgeContentGap = 6.dp
private val HomeServerBadgeChevronSize = 16.dp
internal val HomeServerIdentityNameChevronGap = 4.dp
internal val HomeServerPublicPageGlobeGap = 4.dp
internal val HomeServerPublicPageGlobeSize = 13.dp
internal val HomeServerIdentityChevronSize = 18.dp
private val SlockSortEdgeScrollZone = 56.dp
private val SlockSortEdgeScrollStep = 10.dp
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
    val hasAttention: Boolean = false
)

internal data class HomeConversationBadgeInput(
    val unreadCount: Int,
    val joined: Boolean = true,
    val muted: Boolean = false,
    val mentionPierced: Boolean = false,
    val followed: Boolean = false
)

internal fun homeConversationTitleColor(
    muted: Boolean,
    joined: Boolean = true
): Color =
    when {
        muted -> SlockColor.TextMutedGray
        joined -> SlockColor.TextBlack
        else -> SlockColor.TextBlack.copy(alpha = 0.46f)
    }

internal fun homeChannelTitleTextStyle(
    selected: Boolean,
    hasUnread: Boolean,
    badgeTone: HomeSidebarBadgeTone,
): TextStyle =
    // Channel names and DM / agent names in the sidebar read the same ListItem token so the rows
    // stay the same size (artin 2026-10-18: pinned DMs rendered larger than channels).
    if (selected || (hasUnread && badgeTone == HomeSidebarBadgeTone.Loud)) {
        SlockType.ListItemBold
    } else {
        SlockType.ListItem
    }

internal fun homeConversationMutedIndicatorIcon(muted: Boolean): SlockLucideIconName? =
    if (muted) SlockLucideIconName.BellOff else null

internal data class HomeActivitySidebarProjection(
    val unreadLabel: String?,
    val totalLabel: String?
)

@Page(SLOCK_HOME_PAGE, supportInLocal = true)
internal class ComposeSlockHomePage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockHomePageContent(slockPageVisibleResumeTick) }
    }
}

@Serializable
data class SlockServerSwitcherItem(
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
    server: SlockServerSwitcherItem
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
data class SlockHomeConversationItem(
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
    val guestVisible: Boolean = false
)

data class SlockHomeServerDrawerSection(
    val server: SlockServerSwitcherItem,
    val expanded: Boolean = false,
    val status: HomeServerDrawerLoadStatus = HomeServerDrawerLoadStatus.Unloaded,
    val requestGeneration: Long = 0L,
    val errorCode: String? = null,
    val channels: List<SlockHomeConversationItem> = emptyList(),
    val directMessages: List<SlockHomeConversationItem> = emptyList(),
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

data class SlockServerDrawerUserHeader(
    val displayName: String,
    val memberId: String? = null,
    val serverId: String? = null,
    val avatarWireValue: String? = null,
    val avatarLabel: String = displayName.take(2).uppercase()
)

data class SlockHomeServerDrawerHostedSection(
    val header: SlockHomeServerDrawerSection,
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

internal sealed interface SlockHomeServerDrawerRow {
    val key: String

    data class ServerHeader(
        val section: SlockHomeServerDrawerSection
    ) : SlockHomeServerDrawerRow {
        override val key: String = "server:${section.server.id}"
    }

    data class SectionHeader(
        val serverId: String,
        val section: SlockHomeConversationSection,
        val count: Int
    ) : SlockHomeServerDrawerRow {
        override val key: String = "section:$serverId:${section.name.lowercase()}"
    }

    data class Conversation(
        val serverId: String,
        val section: SlockHomeConversationSection,
        val item: SlockHomeConversationItem
    ) : SlockHomeServerDrawerRow {
        override val key: String = "conversation:$serverId:${section.name.lowercase()}:${item.id}"
    }

    data class Skeleton(
        val serverId: String,
        val index: Int
    ) : SlockHomeServerDrawerRow {
        override val key: String = "skeleton:$serverId:$index"
    }

    data class Error(
        val serverId: String,
        val code: String,
        val hasCache: Boolean
    ) : SlockHomeServerDrawerRow {
        override val key: String = "error:$serverId"
    }
}

@Serializable
data class SlockHomeNotificationItem(
    val id: String = "",
    val title: String = "",
    val body: String = "",
    val actionLabel: String = "",
    val primaryActionActive: Boolean = false,
    val targetId: String? = null,
    val kind: SlockHomeNotificationKind = SlockHomeNotificationKind.Warning,
    val icon: SlockHomeNotificationIcon = SlockHomeNotificationIcon.Default
)

@Serializable
enum class SlockHomeNotificationKind { Warning, Info, Error }

@Serializable
enum class SlockHomeNotificationIcon { Default, WifiOff, CircleArrowUp, Info }

data class SlockHomeInlineError(
    val title: String,
    val detail: String,
    val code: String,
    val actionLabel: String = "",
)

internal data class SlockHomeInlineErrorVisibleCopy(
    val title: String,
    val detail: String,
    val actionLabel: String
)

internal fun SlockHomeInlineError.toVisibleCopy(): SlockHomeInlineErrorVisibleCopy =
    SlockHomeInlineErrorVisibleCopy(
        title = title,
        detail = detail,
        actionLabel = actionLabel
    )

internal fun SlockHomeServerDrawerRow.Error.visibleRetryLabel(localizedRetryLabel: String): String =
    localizedRetryLabel

@Serializable
enum class SlockPresence { Online, Offline, Unknown }

enum class SlockHomeConversationSection {
    Channel,
    JointChannel,
    DirectMessage,
    Pinned
}

enum class SlockHomeSidebarTopSelection {
    Search,
    Activity,
    Saved
}

@Serializable
enum class SlockSidebarSortMode(val wireValue: String) {
    Manual("manual"),
    Recent("recent"),
    Az("az");

    companion object {
        fun fromWireValue(value: String?): SlockSidebarSortMode =
            entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() } ?: Manual
    }
}

@Serializable
data class SlockHomeShellLabels(
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
    val mute: String = "Mute",
    val unmute: String = "Unmute",
    val closeChat: String = "Close Chat",
    val archive: String = "Archive",
    val sortChannels: String = "Sort",
    val saveSort: String = "Save sort",
    val sortManual: String = "Manual",
    val sortRecent: String = "Recent",
    val sortAz: String = "A-Z",
    // task #512: custom sidebar sections (web channel-sections port).
    val moveToSection: String = "Move to section",
    val removeFromSection: String = "Remove from section",
    val newSectionMenu: String = "New section…",
    val renameSection: String = "Rename / change icon…",
    val deleteSection: String = "Delete section",
    val newSectionTitle: String = "New section",
    val editSectionTitle: String = "Edit section",
    val createSection: String = "Create section",
    val sectionNameLabel: String = "Name",
    val sectionNamePlaceholder: String = "My Project",
    val sectionEmojiLabel: String = "Emoji",
    val addChannel: String = "Add channel",
    val noServersAvailable: String = "No servers available",
    val joinChineseCommunityServer: String = "Join Chinese Community",
    val joinCommunityServer: String = "Join community server",
    val switchOrCreateServer: String = "Switch or create server"
) {
    companion object {
        @Composable
        fun localized(): SlockHomeShellLabels = SlockHomeShellLabels(
            search = stringResource(MR.strings.home_nav_search),
            inbox = stringResource(MR.strings.home_nav_activity),
            followedThreads = stringResource(MR.strings.home_nav_followed_threads),
            saved = stringResource(MR.strings.home_nav_saved),
            channels = stringResource(MR.strings.home_section_channels),
            jointChannels = stringResource(MR.strings.home_section_joint_channels),
            directMessages = stringResource(MR.strings.home_section_direct_messages),
            pinned = stringResource(MR.strings.home_section_pinned),
            pin = stringResource(MR.strings.home_action_pin),
            unpin = stringResource(MR.strings.home_action_unpin),
            markAsRead = stringResource(MR.strings.home_action_mark_as_read),
            markAsUnread = stringResource(MR.strings.home_action_mark_as_unread),
            mute = stringResource(MR.strings.home_action_mute),
            unmute = stringResource(MR.strings.home_action_unmute),
            closeChat = stringResource(MR.strings.home_action_close_chat),
            archive = stringResource(MR.strings.home_action_archive),
            sortChannels = stringResource(MR.strings.home_action_sort),
            saveSort = stringResource(MR.strings.home_action_save_sort),
            sortManual = stringResource(MR.strings.home_sort_manual),
            sortRecent = stringResource(MR.strings.home_sort_recent),
            sortAz = stringResource(MR.strings.home_sort_az),
            moveToSection = stringResource(MR.strings.home_section_move_to),
            removeFromSection = stringResource(MR.strings.home_section_remove_from),
            newSectionMenu = stringResource(MR.strings.home_section_new_menu),
            renameSection = stringResource(MR.strings.home_section_rename),
            deleteSection = stringResource(MR.strings.home_section_delete),
            newSectionTitle = stringResource(MR.strings.home_section_new_title),
            editSectionTitle = stringResource(MR.strings.home_section_edit_title),
            createSection = stringResource(MR.strings.home_section_create),
            sectionNameLabel = stringResource(MR.strings.home_section_name_label),
            sectionNamePlaceholder = stringResource(MR.strings.home_section_name_placeholder),
            sectionEmojiLabel = stringResource(MR.strings.home_section_emoji_label),
            addChannel = stringResource(MR.strings.home_action_add_channel),
            noServersAvailable = stringResource(MR.strings.home_state_no_servers),
            joinChineseCommunityServer = stringResource(MR.strings.home_server_switcher_join_chinese_community),
            joinCommunityServer = stringResource(MR.strings.home_server_switcher_join_community),
            switchOrCreateServer = stringResource(MR.strings.home_server_switcher_switch_or_create)
        )
    }
}

@Serializable
internal data class SlockHomePayload(
    val instanceId: String = "",
    val currentServerName: String = "Raft Android",
    val currentServerId: String = "",
    val servers: List<SlockServerSwitcherItem> = emptyList(),
    val channels: List<SlockHomeConversationItem> = emptyList(),
    val directMessages: List<SlockHomeConversationItem> = emptyList(),
    val selectedChannelId: String? = null,
    val inboxUnreadCount: Int = 0,
    val savedCount: Int = 0,
    val notifications: List<SlockHomeNotificationItem> = emptyList(),
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
    val labels: SlockHomeShellLabels = SlockHomeShellLabels()
)

private enum class ChannelRowInteractionState { Default, Selected, Hovered, Focused, Pressed }

private data class ChannelRowContent(
    val name: String,
    val positionPrefix: String? = null,
    val symbol: String = "#",
    val avatarLabel: String? = null,
    val avatarUrl: String? = null,
    val avatarWireValue: String? = avatarUrl,
    val presence: SlockPresence? = null,
    val showPresence: Boolean = true,
    val badge: String? = null,
    val badgeTone: HomeSidebarBadgeTone = HomeSidebarBadgeTone.Loud,
    val hasDraft: Boolean = false,
    val muted: Boolean = false,
    val joined: Boolean = true,
    val bridgeProvider: String? = null
) {
    fun defaultInteractionIdentity(): String = "channel:$symbol:$name"
}

private data class ChannelRowInteraction(
    val identity: String,
    val state: ChannelRowInteractionState = ChannelRowInteractionState.Default,
    val captureLongPressAnchor: Boolean = true,
    val onClick: (() -> Unit)? = null,
    val onLongPress: ((SlockPopoverAnchor) -> Unit)? = null,
    val onPress: (() -> Unit)? = null,
    val onPressEnd: (() -> Unit)? = null
)

private data class ChannelRowDrag(
    val enabled: Boolean = false,
    val offsetY: Float = 0f,
    val onStart: (() -> Unit)? = null,
    val onMove: ((Float) -> Unit)? = null,
    val onEnd: (() -> Unit)? = null,
    val onCancel: (() -> Unit)? = null,
    val rowEnabled: Boolean = false
)

private val homeConversationOpenBarrier = HomeConversationOpenBarrier()

private const val HomePullToRefreshEnabled = true

internal fun homePullToRefreshEnabled(
    contentRefreshEnabled: Boolean,
    platformSupportsPullToRefresh: Boolean = slockPlatformSupportsKuiklyPullToRefreshItem()
): Boolean =
    contentRefreshEnabled && HomePullToRefreshEnabled && platformSupportsPullToRefresh

internal fun homeHeaderRefreshIndicatorVisible(contentRefreshing: Boolean, pullRefreshing: Boolean): Boolean =
    contentRefreshing || pullRefreshing

@Composable
private fun SlockHomePageContent(routeVisibleTick: Int = 0) {
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
    val sharedStores = remember { SlockSharedStoreRegistry.get(bridge) }
    val notificationPreferenceRepository =
        remember {
            PersistentNotificationPreferenceRepository(
                KeyValueNotificationPreferenceStore(slockSharedPreferenceStore())
            )
        }
    val standaloneHomeStore =
        remember(sharedStores, notificationPreferenceRepository) {
            val homeDataSource =
                SharedHomeDataSource(
                    sessionStore = sharedStores.authStore,
                    api = NetworkSlockHomeApi(sharedStores.networkClient),
                    announcementsApi = NetworkSlockAnnouncementsRemindersApi(sharedStores.networkClient),
                    computersApi = SlockComputersNetworkApi(sharedStores.networkClient),
                    notificationPreferenceRepository = notificationPreferenceRepository,
                    serverMembersStore = sharedStores.serverMembersStore,
                    parseHome = { networkPayload, session ->
                        parseSlockHome(networkPayload, session) { message -> bridge.log(message) }
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
            slockScopedAgentStatusResolver(sharedStores.serverMembersStore, payload.currentServerId)
        }
    val memberAvatarResolver =
        remember(sharedStores.serverMembersStore, payload.currentServerId) {
            if (payload.currentServerId.trim().isBlank()) {
                EmptySlockMemberAvatarResolver
            } else {
                slockMemberAvatarResolver(sharedStores.serverMembersStore, payload.currentServerId)
            }
        }
    val serverMembersState by sharedStores.serverMembersStore.state.collectAsState()
    val pinnedMemberProfileResolver: (String) -> SlockHomePinnedMemberItem? =
        remember(serverMembersState, payload.currentServerId) {
            { memberId ->
                serverMembersState
                    .memberProfile(ServerId(payload.currentServerId), memberId)
                    ?.toSlockHomePinnedMemberItem()
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
    val unableToOpenDirectMessage = stringResource(MR.strings.home_error_open_direct_message)
    val failedToOpenDirectMessage = stringResource(MR.strings.home_error_open_direct_message_failed)
    val noDirectMessagePermission = stringResource(MR.strings.home_error_open_direct_message_permission)
    val errorStrings = localizedErrorPresentationStrings()
    LaunchedEffect(sharedStores.serverMembersStore, payload.currentServerId) {
        val serverId = payload.currentServerId.trim().takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        sharedStores.serverMembersStore.refreshMembers(ServerId(serverId), DataRequestPriority.Background)
    }
    fun unsupportedStandaloneHomeAction(action: String, itemId: String? = null) {
        bridge.log("standalone_home_unsupported_action action=$action itemId=${itemId.orEmpty()}")
    }
    SlockStructuralHomeShell(
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
        labels = SlockHomeShellLabels.localized(),
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
        agentStatusResolver = agentStatusResolver,
        memberAvatarResolver = memberAvatarResolver,
        pinnedMemberProfileResolver = pinnedMemberProfileResolver,
        diagnosticsLogger = bridge::log
    )
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
    payload: SlockHomePayload,
    conversationId: String
) {
    val launchPayload =
        buildHomeConversationThreadLaunchPayload(payload, conversationId)
            ?: return
    if (!homeConversationOpenBarrier.tryAcquire(launchPayload.instanceId)) return
    pager.openSlockPage(THREAD_PAGE, threadLaunchPageData(launchPayload))
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
    openSlockPage(THREAD_PAGE, threadLaunchPageData(launchPayload))
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
    payload: SlockHomePayload,
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

private fun SlockHomeConversationItem.toThreadLaunchConversationIdentity(): ThreadLaunchConversationIdentity? {
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
        SlockServerSwitcherItem(
            id = "srv_mobile",
            name = "Raft Mobile",
            slug = "raft-mobile",
            avatarUrl = "pixel:robot"
        )
    val designServer =
        SlockServerSwitcherItem(
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
                .background(SlockColor.SurfaceWhite)
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
            modifier = Modifier.padding(horizontal = SlockSpacing.Md),
            minWidth = 276.dp,
            maxWidth = 328.dp
        ) {
            ServerSwitcherRow(
                server = designServer,
                selected = true,
                pending = false,
                onClick = {}
            )
            ServerSwitcherRow(
                server =
                    SlockServerSwitcherItem(
                        id = "srv_fallback",
                        name = "Raft Community",
                        slug = "raft-community"
                    ),
                selected = false,
                pending = false,
                onClick = {}
            )
        }
    }
}

@Composable
internal fun HomeTitlebarPreferenceVisualScreen(enabled: Boolean) {
    val server =
        SlockServerSwitcherItem(
            id = "srv_persisted",
            name = "Raft Product Design Workspace",
            slug = "raft-design",
            avatarUrl = "pixel:paint"
        )
    SlockStructuralHomeShell(
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
                SlockServerSwitcherItem(
                    id = "server-a",
                    name = "Workspace A",
                    slug = "workspace-a",
                    avatarUrl = "pixel:paint"
                ),
                SlockServerSwitcherItem(
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
    SlockStructuralHomeShell(
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
        SlockServerSwitcherItem(
            id = "server-loading",
            name = "Loading workspace",
            slug = "loading-workspace"
        )
    SlockStructuralHomeShell(
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

@Composable
internal fun HomeTitlebarErrorVisualScreen() {
    val server =
        SlockServerSwitcherItem(
            id = "server-error",
            name = "Workspace with removed avatar",
            slug = "workspace-error"
        )
    SlockStructuralHomeShell(
        currentServerName = server.name,
        currentServerId = server.id,
        servers = listOf(server),
        homeServerAvatarEnabled = true,
        inlineError =
            SlockHomeInlineError(
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
        style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp),
        color = SlockColor.TextMutedGray,
        modifier = Modifier.padding(horizontal = SlockSpacing.Md)
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
    Box(modifier = Modifier.width(342.dp).height(110.dp).background(SlockColor.SurfaceWhite).padding(top = 48.dp)) {
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
    Box(modifier = Modifier.width(390.dp).height(844.dp).background(SlockColor.SurfaceWhite).padding(top = 48.dp).padding(16.dp)) {
        HomeNotificationPopover(
            notifications = visualHomeNotifications(),
            onView = {},
            onDismiss = {},
            modifier = Modifier.align(Alignment.TopStart)
        )
    }
}

@Composable
internal fun HomeAppUpdateBannerVisualScreen() {
    Column(modifier = Modifier.width(390.dp).height(844.dp).background(SlockColor.SurfaceWhite).padding(top = 48.dp)) {
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
                SlockHomeNotificationItem(
                    id = APP_UPDATE_HOME_NOTIFICATION_ID,
                    title = "Raft 1.6.0 is available",
                    body = "See what's new, then download and install the update.",
                    actionLabel = "View",
                    kind = SlockHomeNotificationKind.Info,
                    icon = SlockHomeNotificationIcon.CircleArrowUp
                ),
            onClick = {}
        )
        Box(modifier = Modifier.fillMaxSize().background(SlockColor.AppBgLightBlue).padding(16.dp)) {
            Text(
                text = "HOME",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
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
        SlockServerSwitcherItem(
            id = "srv_design",
            name = "Raft Design",
            slug = "raft-design",
            avatarUrl = "pixel:paint"
        )
    Column(
        modifier =
            Modifier
                .width(342.dp)
                .background(SlockColor.SurfaceWhite)
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
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = SlockSpacing.Md)) {
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
            modifier = Modifier.padding(bottom = SlockSpacing.Md)
        )
    }
}

private fun visualHomeNotifications(): List<SlockHomeNotificationItem> =
    listOf(
        SlockHomeNotificationItem(
            id = "visual-agent-offline",
            title = "Android-Developer lost connection",
            body = "Last heartbeat was 4 minutes ago.",
            actionLabel = "View",
            kind = SlockHomeNotificationKind.Warning
        ),
        SlockHomeNotificationItem(
            id = "visual-release-blocker",
            title = "Release gate needs review",
            body = "Two visual cases changed in the latest run.",
            actionLabel = "Open",
            kind = SlockHomeNotificationKind.Info
        )
    )

@Composable
private fun rememberHomePayloadTrace(payload: String, instanceId: String): HomePayloadParseTrace =
    remember(payload, instanceId) {
        val startedAt = TimeSource.Monotonic.markNow()
        val parsed = runCatching {
            Json { ignoreUnknownKeys = true }
                .decodeFromString(SlockHomePayload.serializer(), payload)
                .let { if (it.instanceId.isBlank()) it.copy(instanceId = instanceId) else it }
        }
        HomePayloadParseTrace(
            payload = parsed.getOrElse { SlockHomePayload(instanceId = instanceId) },
            durationMs = startedAt.elapsedNow().inWholeMilliseconds,
            responseBytes = payload.encodeToByteArray().size,
            result = if (parsed.isSuccess) "success" else "failure"
        )
    }

private data class HomePayloadParseTrace(
    val payload: SlockHomePayload,
    val durationMs: Long,
    val responseBytes: Int,
    val result: String
)

/**
 * task #336: state payload of [SlockStructuralHomeShell]. Grouping the shell's data parameters
 * keeps the composable's register frame small so Android 16's dex verifier accepts the
 * un-minified debug build (the 79-parameter flat signature was rejected with a register-type
 * conflict: "register v327 has type Reference ... but expected Boolean").
 */
internal data class SlockStructuralHomeShellData(
    val currentServerName: String = "Raft Android",
    val currentServerId: String = "slock-android",
    val servers: List<SlockServerSwitcherItem> = emptyList(),
    val channels: List<SlockHomeConversationItem> = emptyList(),
    val directMessages: List<SlockHomeConversationItem> = emptyList(),
    val selectedChannelId: String? = null,
    val selectedConversationSection: SlockHomeConversationSection? = null,
    val inboxUnreadCount: Int = 0,
    val inboxTotalCount: Int = 0,
    val savedCount: Int = 0,
    val notifications: List<SlockHomeNotificationItem> = emptyList(),
    val inlineError: SlockHomeInlineError? = null,
    val contentRefreshing: Boolean = false,
    val contentRefreshEnabled: Boolean = true,
    val serverSwitcherExpanded: Boolean = false,
    val serverDrawerEnabled: Boolean = false,
    val homeServerAvatarEnabled: Boolean = false,
    val serverDrawerSections: List<SlockHomeServerDrawerSection> = emptyList(),
    val pendingServerId: String? = null,
    val prefersChineseCommunity: Boolean = false,
    val applyTopSafeArea: Boolean = true,
    val topSafeAreaOverride: Dp? = null,
    val activitySelected: Boolean = false,
    val searchSelected: Boolean = false,
    val savedSelected: Boolean = false,
    val channelOrder: List<String> = emptyList(),
    val channelSortMode: String = "manual",
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
    val canArchiveChannels: Boolean = false,
    val showLoadingSkeletons: Boolean = false,
    val selectedTabKey: String = "home",
    val showBottomBar: Boolean = true,
    val externalFocusLossDismissEpoch: Int = 0,
    val outerPadding: Dp = SlockSpacing.Md,
    val labels: SlockHomeShellLabels = SlockHomeShellLabels(),
    val scrollToTopRequest: Int = 0,
    val initialListPosition: AppShellLazyListPosition = AppShellLazyListPosition(),
)

/**
 * task #336: callback payload of [SlockStructuralHomeShell]. See [SlockStructuralHomeShellData]
 * for why the flat parameter list had to be grouped.
 */
internal class SlockStructuralHomeShellCallbacks(
    val onServerBadgeClick: () -> Unit = {},
    val onServerSwitcherDismiss: () -> Unit = {},
    val onServerDrawerSectionToggle: (String) -> Unit = {},
    val onServerDrawerRetry: (String) -> Unit = {},
    val onServerSelected: (String) -> Unit = {},
    val onServerOrderChange: (List<String>) -> Unit = {},
    val onCreateServerClick: () -> Unit = {},
    val onCreateChannelClick: () -> Unit = {},
    val onTabSelected: (String) -> Unit = {},
    val onInboxClick: () -> Unit = {},
    val onFollowedThreadsClick: () -> Unit = {},
    val onSavedClick: () -> Unit = {},
    val onSearchClick: () -> Unit = {},
    val onNotificationView: (SlockHomeNotificationItem) -> Unit = {},
    val onNotificationDismiss: (SlockHomeNotificationItem) -> Unit = {},
    val onInlineErrorAction: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onChannelSortModeChange: (SlockSidebarSortMode) -> Unit = {},
    val onSidebarOrderChange: (target: SlockSidebarSortTarget, order: List<String>) -> Unit = { _, _ -> },
    val onDmSortModeChange: (SlockSidebarSortMode) -> Unit = {},
    val onPinnedSortModeChange: (SlockSidebarSortMode) -> Unit = {},
    // task #512: custom sidebar sections (web channel-sections port).
    val onSidebarSectionSortModeChange: (sectionId: String, SlockSidebarSortMode) -> Unit = { _, _ -> },
    val onSidebarSectionCreate: (name: String, emoji: String?) -> Unit = { _, _ -> },
    val onSidebarSectionRename: (sectionId: String, name: String, emoji: String?) -> Unit = { _, _, _ -> },
    val onSidebarSectionDelete: (sectionId: String) -> Unit = {},
    /** [sectionId] null returns the conversation to its default section. */
    val onMoveConversationToSection: (conversationId: String, sectionId: String?) -> Unit = { _, _ -> },
    val onChannelClick: (String) -> Unit = {},
    val onPinnedMemberClick: (MemberDetailIdentity) -> Unit = {},
    val onChannelLongPress: (String, SlockPopoverAnchor) -> Unit = { _, _ -> },
    val onPinToggle: (String) -> Unit = {},
    val onReadToggle: (conversationId: String, markUnread: Boolean) -> Unit = { _, _ -> },
    val onChannelActivityMuteToggle: (conversationId: String, muted: Boolean) -> Unit = { _, _ -> },
    val onCloseDirectMessage: (String) -> Unit = {},
    val onArchiveChannel: (String) -> Unit = {},
    val agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    val memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    val pinnedMemberProfileResolver: (String) -> SlockHomePinnedMemberItem? = { null },
    val diagnosticsLogger: (String) -> Unit = {},
    val onPinMenuDismiss: () -> Unit = {},
    val onListPositionSnapshot: (String?, AppShellLazyListPosition) -> Unit = { _, _ -> },
)

@Composable
internal fun SlockStructuralHomeShell(
    modifier: Modifier = Modifier,
    currentServerName: String = "Raft Android",
    currentServerId: String = "slock-android",
    servers: List<SlockServerSwitcherItem> = emptyList(),
    channels: List<SlockHomeConversationItem> = emptyList(),
    directMessages: List<SlockHomeConversationItem> = emptyList(),
    selectedChannelId: String? = null,
    selectedConversationSection: SlockHomeConversationSection? = null,
    inboxUnreadCount: Int = 0,
    inboxTotalCount: Int = 0,
    savedCount: Int = 0,
    notifications: List<SlockHomeNotificationItem> = emptyList(),
    inlineError: SlockHomeInlineError? = null,
    contentRefreshing: Boolean = false,
    contentRefreshEnabled: Boolean = true,
    serverSwitcherExpanded: Boolean = false,
    serverDrawerEnabled: Boolean = false,
    homeServerAvatarEnabled: Boolean = false,
    serverDrawerSections: List<SlockHomeServerDrawerSection> = emptyList(),
    pendingServerId: String? = null,
    prefersChineseCommunity: Boolean = false,
    applyTopSafeArea: Boolean = true,
    topSafeAreaOverride: Dp? = null,
    activitySelected: Boolean = false,
    searchSelected: Boolean = false,
    savedSelected: Boolean = false,
    channelOrder: List<String> = emptyList(),
    channelSortMode: String = "manual",
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
    canArchiveChannels: Boolean = false,
    // #372 React parity: while home content is loading (session known,
    // channels / directMessages still empty) render static skeleton rows
    // under the CHANNELS and DIRECT MESSAGES headers instead of a bare shell.
    showLoadingSkeletons: Boolean = false,
    selectedTabKey: String = "home",
    showBottomBar: Boolean = true,
    externalFocusLossDismissEpoch: Int = 0,
    bottomContentPadding: Dp = if (showBottomBar) SlockSize.BottomBarHeight + SlockSpacing.Md else SlockSpacing.Lg,
    outerPadding: Dp = SlockSpacing.Md,
    labels: SlockHomeShellLabels = SlockHomeShellLabels(),
    onServerBadgeClick: () -> Unit = {},
    onServerSwitcherDismiss: () -> Unit = {},
    onServerDrawerSectionToggle: (String) -> Unit = {},
    onServerDrawerRetry: (String) -> Unit = {},
    onServerSelected: (String) -> Unit = {},
    onServerOrderChange: (List<String>) -> Unit = {},
    onCreateServerClick: () -> Unit = {},
    onCreateChannelClick: () -> Unit = {},
    onTabSelected: (String) -> Unit = {},
    onInboxClick: () -> Unit = {},
    onFollowedThreadsClick: () -> Unit = {},
    onSavedClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onNotificationView: (SlockHomeNotificationItem) -> Unit = {},
    onNotificationDismiss: (SlockHomeNotificationItem) -> Unit = {},
    onInlineErrorAction: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onChannelSortModeChange: (SlockSidebarSortMode) -> Unit = {},
    onSidebarOrderChange: (target: SlockSidebarSortTarget, order: List<String>) -> Unit = { _, _ -> },
    onDmSortModeChange: (SlockSidebarSortMode) -> Unit = {},
    onPinnedSortModeChange: (SlockSidebarSortMode) -> Unit = {},
    // task #512: custom sidebar sections (web channel-sections port).
    onSidebarSectionSortModeChange: (sectionId: String, SlockSidebarSortMode) -> Unit = { _, _ -> },
    onSidebarSectionCreate: (name: String, emoji: String?) -> Unit = { _, _ -> },
    onSidebarSectionRename: (sectionId: String, name: String, emoji: String?) -> Unit = { _, _, _ -> },
    onSidebarSectionDelete: (sectionId: String) -> Unit = {},
    onMoveConversationToSection: (conversationId: String, sectionId: String?) -> Unit = { _, _ -> },
    onChannelClick: (String) -> Unit = {},
    onPinnedMemberClick: (MemberDetailIdentity) -> Unit = {},
    onChannelLongPress: (String, SlockPopoverAnchor) -> Unit = { _, _ -> },
    onPinToggle: (String) -> Unit = {},
    onReadToggle: (conversationId: String, markUnread: Boolean) -> Unit = { _, _ -> },
    onChannelActivityMuteToggle: (conversationId: String, muted: Boolean) -> Unit = { _, _ -> },
    onCloseDirectMessage: (String) -> Unit = {},
    onArchiveChannel: (String) -> Unit = {},
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    pinnedMemberProfileResolver: (String) -> SlockHomePinnedMemberItem? = { null },
    diagnosticsLogger: (String) -> Unit = {},
    onPinMenuDismiss: () -> Unit = {},
    scrollToTopRequest: Int = 0,
    listPositionServerId: String = currentServerId,
    initialListPosition: AppShellLazyListPosition = AppShellLazyListPosition(),
    onListPositionSnapshot: (String?, AppShellLazyListPosition) -> Unit = { _, _ -> }
) {
    // task #336: thin delegation to the payload-based implementation keeps every call site
    // (named arguments) unchanged while the verifier-relevant frame lives on the small
    // SlockStructuralHomeShellContent signature below.
    SlockStructuralHomeShellContent(
        modifier = modifier,
        data =
            SlockStructuralHomeShellData(
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
                contentRefreshing = contentRefreshing,
                contentRefreshEnabled = contentRefreshEnabled,
                serverSwitcherExpanded = serverSwitcherExpanded,
                serverDrawerEnabled = serverDrawerEnabled,
                homeServerAvatarEnabled = homeServerAvatarEnabled,
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
                canArchiveChannels = canArchiveChannels,
                showLoadingSkeletons = showLoadingSkeletons,
                selectedTabKey = selectedTabKey,
                showBottomBar = showBottomBar,
                externalFocusLossDismissEpoch = externalFocusLossDismissEpoch,
                outerPadding = outerPadding,
                labels = labels,
                scrollToTopRequest = scrollToTopRequest,
                initialListPosition = initialListPosition,
            ),
        callbacks =
            SlockStructuralHomeShellCallbacks(
                onServerBadgeClick = onServerBadgeClick,
                onServerSwitcherDismiss = onServerSwitcherDismiss,
                onServerDrawerSectionToggle = onServerDrawerSectionToggle,
                onServerDrawerRetry = onServerDrawerRetry,
                onServerSelected = onServerSelected,
                onServerOrderChange = onServerOrderChange,
                onCreateServerClick = onCreateServerClick,
                onCreateChannelClick = onCreateChannelClick,
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
                onChannelActivityMuteToggle = onChannelActivityMuteToggle,
                onCloseDirectMessage = onCloseDirectMessage,
                onArchiveChannel = onArchiveChannel,
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

@Composable
private fun SlockStructuralHomeShellContent(
    modifier: Modifier,
    data: SlockStructuralHomeShellData,
    callbacks: SlockStructuralHomeShellCallbacks,
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
    val contentRefreshing = data.contentRefreshing
    val contentRefreshEnabled = data.contentRefreshEnabled
    val serverSwitcherExpanded = data.serverSwitcherExpanded
    val serverDrawerEnabled = data.serverDrawerEnabled
    val homeServerAvatarEnabled = data.homeServerAvatarEnabled
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
    val canArchiveChannels = data.canArchiveChannels
    val showLoadingSkeletons = data.showLoadingSkeletons
    val selectedTabKey = data.selectedTabKey
    val showBottomBar = data.showBottomBar
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
    val onCreateChannelClick = callbacks.onCreateChannelClick
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
    val onChannelActivityMuteToggle = callbacks.onChannelActivityMuteToggle
    val onCloseDirectMessage = callbacks.onCloseDirectMessage
    val onArchiveChannel = callbacks.onArchiveChannel
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
    // #211 review (Cindy): single source of truth for which row is
    // currently being pressed. Set by the row's onPress callback, cleared
    // by onPressEnd (after tryAwaitRelease returns) so the visual only
    // spans the actual pointer-down window. Never overrides a real
    // Selected highlight — the row picks Pressed only when not Selected.
    // #211 React parity (revised): each rendered copy of a pinned
    // conversation (pinned + original section) needs its own press
    // key, otherwise pressing the pinned copy would also press the
    // original copy. Row key format is `<section>:<conversationId>`.
    var pressedRowKey by remember(currentServerId) { mutableStateOf<String?>(null) }
    var channelSortModeState by remember(currentServerId) { mutableStateOf(SlockSidebarSortMode.fromWireValue(channelSortMode)) }
    var directMessageSortMode by remember(currentServerId) { mutableStateOf(SlockSidebarSortMode.fromWireValue(dmSortMode)) }
    var pinnedSortModeState by remember(currentServerId) { mutableStateOf(SlockSidebarSortMode.fromWireValue(pinnedSortMode)) }
    var activeSortMenu by remember(currentServerId) { mutableStateOf<SlockHomeSortSection?>(null) }
    var sortMenuAnchor by remember(currentServerId) { mutableStateOf<SlockPopoverAnchor?>(null) }
    var activePinMenuConversationId by remember(currentServerId) { mutableStateOf<String?>(null) }
    var activePinnedMemberMenuKey by remember(currentServerId) { mutableStateOf<String?>(null) }
    var pinMenuAnchor by remember(currentServerId) { mutableStateOf<SlockPopoverAnchor?>(null) }
    LaunchedEffect(currentServerId, channelSortMode, dmSortMode, pinnedSortMode) {
        channelSortModeState = SlockSidebarSortMode.fromWireValue(channelSortMode)
        directMessageSortMode = SlockSidebarSortMode.fromWireValue(dmSortMode)
        pinnedSortModeState = SlockSidebarSortMode.fromWireValue(pinnedSortMode)
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
    // #211 React parity (revised by @artin 23:38): pinned rows are
    // shown alongside the original section copy, not deduped. The user
    // sees both, can click either, and either copy is independently
    // pressed / selected. Dedup still applies *inside* the pinned
    // section itself (handled by `pinnedItemsForSidebar`).
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
    var sortOverlaySection by remember(currentServerId) { mutableStateOf<SlockHomeSortSection?>(null) }
    var activeMenuSection by remember(currentServerId) { mutableStateOf<SlockHomeSortSection?>(null) }
    // task #512: custom-section UI state — collapsed ids, the header context
    // menu, the create/rename dialog, the delete confirmation, and the
    // move-to-section submenu target.
    var collapsedCustomSectionIds by remember(currentServerId) { mutableStateOf(setOf<String>()) }
    var activeSectionMenuId by remember(currentServerId) { mutableStateOf<String?>(null) }
    var sectionMenuAnchor by remember(currentServerId) { mutableStateOf<SlockPopoverAnchor?>(null) }
    var sectionDialogTarget by remember(currentServerId) { mutableStateOf<SlockHomeSectionDialogTarget?>(null) }
    var sectionDeleteConfirmId by remember(currentServerId) { mutableStateOf<String?>(null) }
    var moveToSectionConversationId by remember(currentServerId) { mutableStateOf<String?>(null) }
    val allChannelId = remember(channels) { channels.firstOrNull { it.name == "all" }?.id }
    // task #512: conversations claimed by a custom section leave their default
    // system section (web customPlacedChannelIds / customPlacedAgentIds).
    val customPlacedConversationIds = remember(sectionPlacements, directMessages) {
        sidebarCustomPlacedConversationIds(sectionPlacements, directMessages)
    }
    val baseDisplayedChannels = remember(channels, channelOrder, channelSortModeState, customPlacedConversationIds) {
        val regularChannels = channels.filter { it.kind != JointChannelKindWireValue }
        regularChannels
            .filter { it.id !in customPlacedConversationIds }
            .orderedBySidebarIds(channelOrder, pinnedFirstId = allChannelId)
            .sortedForSidebar(channelSortModeState, pinnedFirstId = allChannelId) { it.name }
    }
    val displayedChannels = baseDisplayedChannels
    val sortableDisplayedChannels = remember(baseDisplayedChannels, allChannelId) {
        baseDisplayedChannels.withoutSidebarPinnedItem(allChannelId)
    }
    val displayedJointChannels = remember(channels, channelOrder, channelSortModeState, customPlacedConversationIds) {
        channels
            .filter { it.kind == JointChannelKindWireValue }
            .filter { it.id !in customPlacedConversationIds }
            .orderedBySidebarIds(channelOrder)
            .sortedForSidebar(channelSortModeState) { it.name }
    }
    val hiddenDmIdSet = remember(hiddenDmIds) { hiddenDmIds.toSet() }
    val displayedDirectMessages = remember(directMessages, dmOrder, directMessageSortMode, hiddenDmIdSet, customPlacedConversationIds) {
        directMessages
            .filter { it.id !in customPlacedConversationIds }
            .orderedBySidebarIds(dmOrder)
            .filter { dm -> dm.id !in hiddenDmIdSet || dm.homeConversationBadgeProjection().hasUnread }
            .sortedForSidebar(directMessageSortMode) { it.name }
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
                        is SlockHomePinnedClickTarget.Conversation -> "${row.key}->conversation:${target.conversationId}"
                        is SlockHomePinnedClickTarget.Member -> "${row.key}->member:${target.identity.memberId.value}"
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
    // sectionOrder order; empty ones stay hidden (mobile always hides empties).
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
    val displayedSidebarSections =
        remember(sectionOrder, customSections, displayedCustomSections, hasDisplayedPinned, hasDisplayedJointChannels) {
            homeSidebarSectionOrder(
                sectionOrder = sectionOrder,
                customSections = customSections,
                customSectionRows = displayedCustomSections,
                hasPinned = hasDisplayedPinned,
                hasJointChannels = hasDisplayedJointChannels
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
    // copy. Pressed state still uses the per-row `pressedRowKey`
    // so the two copies don't press together.
    val channelSectionSelection = sidebarSelection.sectionSelection(SlockHomeConversationSection.Channel)
    val jointChannelSectionSelection = sidebarSelection.sectionSelection(SlockHomeConversationSection.JointChannel)
    val directMessageSectionSelection = sidebarSelection.sectionSelection(SlockHomeConversationSection.DirectMessage)
    val pinnedSectionSelection = sidebarSelection.sectionSelection(SlockHomeConversationSection.Pinned)
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
            hasPullToRefreshItem = pullToRefreshEnabled,
            hasInlineError = inlineError != null,
            channelSkeletonCount =
                if (channelsExpanded && showLoadingSkeletons) HomeLoadingChannelSkeletonRows else 0,
            directMessageSkeletonCount =
                if (directMessagesExpanded && showLoadingSkeletons) HomeLoadingDirectMessageSkeletonRows else 0
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
    val captureRowAnchors = !listState.isScrollInProgress
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
    var notificationCenterExpanded by remember(currentServerId) { mutableStateOf(false) }
    LaunchedEffect(externalFocusLossDismissEpoch) {
        if (externalFocusLossDismissEpoch > 0) {
            notificationCenterExpanded = false
        }
    }
    val pullToRefreshState = rememberPullToRefreshState(pullRefreshing)
    val sidebarInteractionsEnabled = true
    val disabledListAction: () -> Unit = {}
    val resolvedTopSafeArea = slockTopSafeAreaPadding()
    val topSafeArea =
        homeShellTopSafeArea(
            applyTopSafeArea = applyTopSafeArea,
            resolvedTopSafeArea = resolvedTopSafeArea,
            topSafeAreaOverride = topSafeAreaOverride,
        )
    val bottomSafeArea = if (showBottomBar) slockBottomSafeAreaPadding() else 0.dp
    val effectiveBottomContentPadding =
        if (showBottomBar) {
            bottomContentPadding.max(slockAppBottomTabBarContentInset(bottomSafeArea))
        } else {
            bottomContentPadding
        }
    val bottomBarOverlaysContent = showBottomBar && slockAppBottomTabBarOverlaysContent()
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
    val onChannelLongPressWithDismiss: (String, SlockPopoverAnchor) -> Unit = { conversationId, anchor ->
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
        modifier = modifier.fillMaxSize().background(SlockColor.AppBgLightBlue).padding(outerPadding)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(SlockColor.SurfaceWhite)) {
            Column(modifier = Modifier.fillMaxSize()) {
                traceMarkdownSection(SlockHomeTraceNames.TITLEBAR_RENDER, notifications.size) {
                    HomeTitlebar(
                        serverName = currentServerName,
                        serverIdentity = currentHomeHeaderServer.takeIf { homeServerAvatarEnabled },
                        serverPublicPageLive = currentHomeHeaderServer.publicPageLive,
                        hasOtherServerUnread = serverSwitcherHasOtherUnread,
                        notifications = visibleNotifications,
                        notificationCenterExpanded = notificationCenterExpanded,
                        refreshing = headerRefreshing,
                        topSafeArea = topSafeArea,
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
                SlockAppShellBodyTopBanner()
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
                    traceMarkdownSection(SlockHomeTraceNames.LIST_RENDER, lazyItemCount) {
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
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            beyondBoundsItemCount = homeBeyondBoundsItemCount
                        ) {
                            if (pullToRefreshEnabled) {
                                slockRaftPullToRefreshItem(
                                    state = pullToRefreshState,
                                    scrollState = listState,
                                    onRefresh = {
                                        pullRefreshActive = true
                                        onRefresh()
                                    }
                                )
                            }
                            item { Spacer(Modifier.height(SlockSpacing.Md)) }
                            item { SidebarActionRow(SlockLucideIconName.Search, labels.search, selected = sidebarSelection.topAction == SlockHomeSidebarTopSelection.Search, modifier = Modifier.padding(horizontal = HomeListHorizontalPadding), onClick = if (sidebarInteractionsEnabled) onSearchClickWithDismiss else null) }
                            item {
                                val activityProjection =
                                    homeActivitySidebarProjection(
                                        activityUnreadCount = inboxUnreadCount,
                                        activityTotalCount = inboxTotalCount
                                    )
                                SidebarActivityRow(
                                    labels.inbox,
                                    unreadCount = activityProjection.unreadLabel,
                                    totalCount = activityProjection.totalLabel,
                                    selected = sidebarSelection.topAction == SlockHomeSidebarTopSelection.Activity,
                                    modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                    onClick = if (sidebarInteractionsEnabled) onInboxClickWithDismiss else null
                                )
                            }
                            item { SidebarActionRow(SlockLucideIconName.Bookmark, labels.saved, selected = sidebarSelection.topAction == SlockHomeSidebarTopSelection.Saved, modifier = Modifier.padding(horizontal = HomeListHorizontalPadding), trailingText = formatHomeSavedCount(savedCount), onClick = if (sidebarInteractionsEnabled) onSavedClickWithDismiss else null) }
                            inlineError?.let { error ->
                                item {
                                    HomeInlineErrorCard(
                                        error = error,
                                        modifier =
                                            Modifier
                                                .padding(horizontal = HomeListHorizontalPadding, vertical = SlockSpacing.Sm),
                                        onAction = onInlineErrorAction
                                    )
                                }
                            }
                        // Sections render in web's normalized sectionOrder: system and
                        // custom sections interleave (web Sidebar.tsx renders every block
                        // with CSS `order = sectionOrder.indexOf(id)`); empty sections hide.
                        displayedSidebarSections.forEach { sidebarSection ->
                            when (sidebarSection) {
                                SlockHomeSidebarSection.Pinned -> {
                                    item { Spacer(Modifier.height(SlockSpacing.Xs)) }
                                    item {
                                        SlockCollapsibleSectionHeader(
                                            label = labels.pinned,
                                            count = displayedPinned.size,
                                            expanded = pinnedExpanded,
                                            onExpandedChange = { if (sidebarInteractionsEnabled) pinnedExpanded = it },
                                            showCollapsedUnreadDot = pinnedSectionHasUnread,
                                            sortContentDescription = "${labels.sortChannels} ${labels.pinned}",
                                            onSortClick = { anchor ->
                                                if (sidebarInteractionsEnabled) {
                                                    activeSortMenu = if (activeSortMenu == SlockHomeSortSection.Pinned) null else SlockHomeSortSection.Pinned
                                                    sortMenuAnchor = anchor
                                                }
                                            },
                                            modifier = Modifier.padding(horizontal = HomeListHorizontalPadding)
                                        )
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
                                            // Selected wins over pressed so the user never confuses
                                            // "I'm pressing" with "this is the current conversation".
                                            val rowInteractionState = when {
                                                pinned != null && sidebarSelection.conversationId == pinned.id -> ChannelRowInteractionState.Selected
                                                pressedRowKey == "pinned:${pinnedRow.key}" -> ChannelRowInteractionState.Pressed
                                                else -> ChannelRowInteractionState.Default
                                            }
                                            val handleLongPress: (SlockPopoverAnchor) -> Unit = { anchor ->
                                                when {
                                                    pinned != null -> {
                                                        onChannelLongPressWithDismiss(pinned.id, anchor)
                                                        activePinnedMemberMenuKey = null
                                                        activePinMenuConversationId = pinned.id
                                                        activeMenuSection = SlockHomeSortSection.Pinned
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
                                                        activeMenuSection = SlockHomeSortSection.Pinned
                                                        pinMenuAnchor = anchor
                                                    }
                                                }
                                                // Menu opens: clear press so the visual doesn't stick
                                                // while the user reads it.
                                                pressedRowKey = null
                                            }
                                            if (isDirectMessage) {
                                                val badgeProjection = pinned?.homeConversationBadgeProjection()
                                                DirectMessageRow(
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
                                                    modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                                    hasDraft = pinned?.hasDraft == true,
                                                    muted = pinned?.activityMuted == true,
                                                    badge = badgeProjection?.label,
                                                    badgeTone = badgeProjection?.tone ?: HomeSidebarBadgeTone.Loud,
                                                    interactionState = rowInteractionState,
                                                    captureLongPressAnchor = captureRowAnchors,
                                                    onClick =
                                                        if (sidebarInteractionsEnabled) {
                                                            {
                                                                when (val target = pinnedRow.clickTarget()) {
                                                                    is SlockHomePinnedClickTarget.Conversation -> {
                                                                        diagnosticsLogger(
                                                                            "shared_home_pinned_click serverId=$currentServerId " +
                                                                                "key=${pinnedRow.key} target=conversation " +
                                                                                "conversationId=${target.conversationId}"
                                                                        )
                                                                        onChannelClickWithDismiss(target.conversationId)
                                                                    }
                                                                    is SlockHomePinnedClickTarget.Member -> {
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
                                                    onLongPress = if (sidebarInteractionsEnabled && pinnedRow.supportsLongPress()) handleLongPress else null,
                                                    onPress = if (sidebarInteractionsEnabled) ({ pressedRowKey = "pinned:${pinnedRow.key}" }) else null,
                                                    onPressEnd = { if (pressedRowKey == "pinned:${pinnedRow.key}") pressedRowKey = null }
                                                )
                                            } else if (pinned != null) {
                                                val badgeProjection = pinned.homeConversationBadgeProjection()
                                                ChannelRow(
                                                    content =
                                                        ChannelRowContent(
                                                            name = pinned.name,
                                                            symbol = pinned.homeChannelSymbol(currentHomeHeaderServer.publicPageLive),
                                                            joined = pinned.joined,
                                                            hasDraft = pinned.hasDraft,
                                                            muted = pinned.activityMuted,
                                                            bridgeProvider = pinned.bridgeProvider,
                                                            badge = badgeProjection.label,
                                                            badgeTone = badgeProjection.tone
                                                        ),
                                                    modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                                    interaction =
                                                        ChannelRowInteraction(
                                                            identity = homeSidebarInteractionIdentity("pinned", pinnedRow.key),
                                                            state = rowInteractionState,
                                                            captureLongPressAnchor = captureRowAnchors,
                                                            onClick = if (sidebarInteractionsEnabled) ({ onChannelClickWithDismiss(pinned.id) }) else null,
                                                            onLongPress = if (sidebarInteractionsEnabled) handleLongPress else null,
                                                            onPress = if (sidebarInteractionsEnabled) ({ pressedRowKey = "pinned:${pinnedRow.key}" }) else null,
                                                            onPressEnd = { if (pressedRowKey == "pinned:${pinnedRow.key}") pressedRowKey = null }
                                                        )
                                                )
                                            }
                                        }
                                    }
                                }

                                SlockHomeSidebarSection.JointChannels -> {
                                    item { Spacer(Modifier.height(SlockSpacing.Xs)) }
                                    item {
                                        SlockCollapsibleSectionHeader(
                                            label = labels.jointChannels,
                                            count = displayedJointChannels.size,
                                            expanded = jointChannelsExpanded,
                                            onExpandedChange = { if (sidebarInteractionsEnabled) jointChannelsExpanded = it },
                                            showCollapsedUnreadDot = jointChannelSectionHasUnread,
                                            modifier = Modifier.padding(horizontal = HomeListHorizontalPadding)
                                        )
                                    }
                                    if (jointChannelsExpanded) {
                                        items(displayedJointChannels.size, key = { index -> jointChannelLazyKeys[index] }) { index ->
                                            val channel = displayedJointChannels[index]
                                            val badgeProjection = channel.homeConversationBadgeProjection()
                                            val jointInteractionState = when {
                                                sidebarSelection.conversationId == channel.id -> ChannelRowInteractionState.Selected
                                                pressedRowKey == "channels:${channel.id}" -> ChannelRowInteractionState.Pressed
                                                else -> ChannelRowInteractionState.Default
                                            }
                                            ChannelRow(
                                                content =
                                                    ChannelRowContent(
                                                        name = channel.name,
                                                        symbol = channel.homeChannelSymbol(currentHomeHeaderServer.publicPageLive),
                                                        joined = channel.joined,
                                                        hasDraft = channel.hasDraft,
                                                        muted = channel.activityMuted,
                                                        bridgeProvider = channel.bridgeProvider,
                                                        badge = badgeProjection.label,
                                                        badgeTone = badgeProjection.tone
                                                    ),
                                                modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                                interaction =
                                                    ChannelRowInteraction(
                                                        identity = homeSidebarInteractionIdentity("joint", channel.id),
                                                        state = jointInteractionState,
                                                        captureLongPressAnchor = captureRowAnchors,
                                                        onClick = if (sidebarInteractionsEnabled) ({ onChannelClickWithDismiss(channel.id) }) else null,
                                                        onLongPress = if (sidebarInteractionsEnabled) ({ anchor ->
                                                            onChannelLongPressWithDismiss(channel.id, anchor)
                                                            activePinMenuConversationId = channel.id
                                                            activeMenuSection = SlockHomeSortSection.JointChannels
                                                            pinMenuAnchor = anchor
                                                            pressedRowKey = null
                                                        }) else null,
                                                        onPress = if (sidebarInteractionsEnabled) ({ pressedRowKey = "channels:${channel.id}" }) else null,
                                                        onPressEnd = { if (pressedRowKey == "channels:${channel.id}") pressedRowKey = null }
                                                    )
                                            )
                                        }
                                    }
                                }

                                SlockHomeSidebarSection.Channels -> {
                                    item { Spacer(Modifier.height(SlockSpacing.Xs)) }
                                    item {
                                        SlockCollapsibleSectionHeader(
                                            label = labels.channels,
                                            count = displayedChannels.size,
                                            expanded = channelsExpanded,
                                            onExpandedChange = { if (sidebarInteractionsEnabled) channelsExpanded = it },
                                            showCollapsedUnreadDot = channelSectionHasUnread,
                                            sortContentDescription = "${labels.sortChannels} ${labels.channels}",
                                            onSortClick = { anchor ->
                                                if (sidebarInteractionsEnabled) {
                                                    activeSortMenu = if (activeSortMenu == SlockHomeSortSection.Channels) null else SlockHomeSortSection.Channels
                                                    sortMenuAnchor = anchor
                                                }
                                            },
                                            modifier = Modifier.padding(horizontal = HomeListHorizontalPadding)
                                        ) {
                                            SlockSectionHeaderIconAction(SlockLucideIconName.Plus, labels.addChannel, if (sidebarInteractionsEnabled) onCreateChannelClick else disabledListAction)
                                        }
                                    }
                                    if (channelsExpanded) {
                                        items(displayedChannels.size, key = { index -> channelLazyKeys[index] }) { index ->
                                            val channel = displayedChannels[index]
                                            val badgeProjection = channel.homeConversationBadgeProjection()
                                            // #211 review (Cindy): selected wins over pressed.
                                            val channelInteractionState = when {
                                                sidebarSelection.conversationId == channel.id -> ChannelRowInteractionState.Selected
                                                pressedRowKey == "channels:${channel.id}" -> ChannelRowInteractionState.Pressed
                                                else -> ChannelRowInteractionState.Default
                                            }
                                            ChannelRow(
                                                content =
                                                    ChannelRowContent(
                                                        name = channel.name,
                                                        symbol = channel.homeChannelSymbol(currentHomeHeaderServer.publicPageLive),
                                                        joined = channel.joined,
                                                        hasDraft = channel.hasDraft,
                                                        muted = channel.activityMuted,
                                                        bridgeProvider = channel.bridgeProvider,
                                                        badge = badgeProjection.label,
                                                        badgeTone = badgeProjection.tone
                                                    ),
                                                modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                                interaction =
                                                    ChannelRowInteraction(
                                                        identity = homeSidebarInteractionIdentity("channel", channel.id),
                                                        state = channelInteractionState,
                                                        captureLongPressAnchor = captureRowAnchors,
                                                        onClick = if (sidebarInteractionsEnabled) ({ onChannelClickWithDismiss(channel.id) }) else null,
                                                        onLongPress = if (sidebarInteractionsEnabled) ({ anchor ->
                                                            onChannelLongPressWithDismiss(channel.id, anchor)
                                                            activePinMenuConversationId = channel.id
                                                            activeMenuSection = SlockHomeSortSection.Channels
                                                            pinMenuAnchor = anchor
                                                            pressedRowKey = null
                                                        }) else null,
                                                        onPress = if (sidebarInteractionsEnabled) ({ pressedRowKey = "channels:${channel.id}" }) else null,
                                                        onPressEnd = { if (pressedRowKey == "channels:${channel.id}") pressedRowKey = null }
                                                    )
                                            )
                                        }
                                    }
                                    if (channelsExpanded && showLoadingSkeletons) {
                                        items(HomeLoadingChannelSkeletonRows, key = { index -> "channel-skeleton-$index" }) {
                                            HomeSidebarSkeletonRow(modifier = Modifier.padding(horizontal = HomeListHorizontalPadding))
                                        }
                                    }
                                }

                                SlockHomeSidebarSection.DirectMessages -> {
                                    item { Spacer(Modifier.height(SlockSpacing.Xs)) }
                                    item {
                                        SlockCollapsibleSectionHeader(
                                            label = labels.directMessages,
                                            count = displayedDirectMessages.size,
                                            expanded = directMessagesExpanded,
                                            onExpandedChange = { if (sidebarInteractionsEnabled) directMessagesExpanded = it },
                                            showCollapsedUnreadDot = directMessageSectionHasUnread,
                                            sortContentDescription = "${labels.sortChannels} ${labels.directMessages}",
                                            onSortClick = { anchor ->
                                                if (sidebarInteractionsEnabled) {
                                                    activeSortMenu = if (activeSortMenu == SlockHomeSortSection.DirectMessages) null else SlockHomeSortSection.DirectMessages
                                                    sortMenuAnchor = anchor
                                                }
                                            },
                                            modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                            trailingContent = null
                                        )
                                    }
                                    if (directMessagesExpanded) {
                                        items(displayedDirectMessages.size, key = { index -> directMessageLazyKeys[index] }) { index ->
                                            val dm = displayedDirectMessages[index]
                                            val badgeProjection = dm.homeConversationBadgeProjection()
                                            val dmInteractionState = when {
                                                sidebarSelection.conversationId == dm.id -> ChannelRowInteractionState.Selected
                                                pressedRowKey == "dms:${dm.id}" -> ChannelRowInteractionState.Pressed
                                                else -> ChannelRowInteractionState.Default
                                            }
                                            DirectMessageRow(
                                                interactionIdentity = homeSidebarInteractionIdentity("dm", dm.id),
                                                name = dm.name,
                                                description = dm.description,
                                                avatarLabel = dm.avatarLabel,
                                                peerType = dm.peerType,
                                                memberId = dm.presenceMemberId,
                                                cacheServerId = currentServerId,
                                                agentStatusResolver = agentStatusResolver,
                                                memberAvatarResolver = memberAvatarResolver,
                                                modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                                hasDraft = dm.hasDraft,
                                                muted = dm.activityMuted,
                                                badge = badgeProjection.label,
                                                badgeTone = badgeProjection.tone,
                                                interactionState = dmInteractionState,
                                                captureLongPressAnchor = captureRowAnchors,
                                                onClick = if (sidebarInteractionsEnabled) ({ onChannelClickWithDismiss(dm.id) }) else null,
                                                onLongPress = if (sidebarInteractionsEnabled) ({ anchor ->
                                                    onChannelLongPressWithDismiss(dm.id, anchor)
                                                    activePinMenuConversationId = dm.id
                                                    activeMenuSection = SlockHomeSortSection.DirectMessages
                                                    pinMenuAnchor = anchor
                                                    pressedRowKey = null
                                                }) else null,
                                                onPress = if (sidebarInteractionsEnabled) ({ pressedRowKey = "dms:${dm.id}" }) else null,
                                                onPressEnd = { if (pressedRowKey == "dms:${dm.id}") pressedRowKey = null }
                                            )
                                        }
                                    }
                                    if (directMessagesExpanded && showLoadingSkeletons) {
                                        items(HomeLoadingDirectMessageSkeletonRows, key = { index -> "dm-skeleton-$index" }) {
                                            HomeSidebarSkeletonRow(modifier = Modifier.padding(horizontal = HomeListHorizontalPadding))
                                        }
                                    }
                                }

                                // task #512: custom sections (web channel-sections port), each
                                // with an emoji + name header; long-press (or the trailing
                                // ellipsis) opens the section menu.
                                is SlockHomeSidebarSection.Custom -> {
                                    val sectionRows = sidebarSection.rows
                                    val customSection = sectionRows.section
                                    val customSectionExpanded = customSection.id !in collapsedCustomSectionIds
                                    item(key = "custom-section-spacer:$currentServerId:${customSection.id}") {
                                        Spacer(Modifier.height(SlockSpacing.Xs))
                                    }
                                    item(key = "custom-section-header:$currentServerId:${customSection.id}") {
                                        SlockCollapsibleSectionHeader(
                                            label = customSection.sidebarSectionHeaderLabel(),
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
                                            showCollapsedUnreadDot =
                                                sectionRows.items.any { it.homeConversationBadgeProjection().hasUnread },
                                            uppercaseLabel = false,
                                            onLongPress =
                                                if (sidebarInteractionsEnabled) {
                                                    { anchor ->
                                                        activeSectionMenuId = customSection.id
                                                        sectionMenuAnchor = anchor
                                                        pressedRowKey = null
                                                    }
                                                } else {
                                                    null
                                                },
                                            longPressAnchorIdentity = "$currentServerId:${customSection.id}",
                                            modifier = Modifier.padding(horizontal = HomeListHorizontalPadding)
                                        ) {
                                            SlockSectionHeaderIconAction(
                                                SlockLucideIconName.Ellipsis,
                                                customSection.name,
                                                onClick2 = { anchor ->
                                                    if (sidebarInteractionsEnabled) {
                                                        activeSectionMenuId = customSection.id
                                                        sectionMenuAnchor = anchor
                                                    }
                                                }
                                            )
                                        }
                                    }
                                    if (customSectionExpanded) {
                                        items(
                                            sectionRows.items.size,
                                            key = { index -> "custom:$currentServerId:${customSection.id}:${sectionRows.items[index].id}" }
                                        ) { index ->
                                            val item = sectionRows.items[index]
                                            val badgeProjection = item.homeConversationBadgeProjection()
                                            val customRowKey = "custom:${customSection.id}:${item.id}"
                                            val customInteractionState = when {
                                                sidebarSelection.conversationId == item.id -> ChannelRowInteractionState.Selected
                                                pressedRowKey == customRowKey -> ChannelRowInteractionState.Pressed
                                                else -> ChannelRowInteractionState.Default
                                            }
                                            val customLongPress: (SlockPopoverAnchor) -> Unit = { anchor ->
                                                onChannelLongPressWithDismiss(item.id, anchor)
                                                activePinMenuConversationId = item.id
                                                activeMenuSection = null
                                                pinMenuAnchor = anchor
                                                pressedRowKey = null
                                            }
                                            if (item.isDirectMessageConversation()) {
                                                DirectMessageRow(
                                                    interactionIdentity = homeSidebarInteractionIdentity("custom", customRowKey),
                                                    name = item.name,
                                                    description = item.description,
                                                    avatarLabel = item.avatarLabel,
                                                    peerType = item.peerType,
                                                    memberId = item.presenceMemberId,
                                                    cacheServerId = currentServerId,
                                                    agentStatusResolver = agentStatusResolver,
                                                    memberAvatarResolver = memberAvatarResolver,
                                                    modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                                    hasDraft = item.hasDraft,
                                                    muted = item.activityMuted,
                                                    badge = badgeProjection.label,
                                                    badgeTone = badgeProjection.tone,
                                                    interactionState = customInteractionState,
                                                    captureLongPressAnchor = captureRowAnchors,
                                                    onClick = if (sidebarInteractionsEnabled) ({ onChannelClickWithDismiss(item.id) }) else null,
                                                    onLongPress = if (sidebarInteractionsEnabled) customLongPress else null,
                                                    onPress = if (sidebarInteractionsEnabled) ({ pressedRowKey = customRowKey }) else null,
                                                    onPressEnd = { if (pressedRowKey == customRowKey) pressedRowKey = null }
                                                )
                                            } else {
                                                ChannelRow(
                                                    content =
                                                        ChannelRowContent(
                                                            name = item.name,
                                                            symbol = item.homeChannelSymbol(currentHomeHeaderServer.publicPageLive),
                                                            joined = item.joined,
                                                            hasDraft = item.hasDraft,
                                                            muted = item.activityMuted,
                                                            bridgeProvider = item.bridgeProvider,
                                                            badge = badgeProjection.label,
                                                            badgeTone = badgeProjection.tone
                                                        ),
                                                    modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                                    interaction =
                                                        ChannelRowInteraction(
                                                            identity = homeSidebarInteractionIdentity("custom", customRowKey),
                                                            state = customInteractionState,
                                                            captureLongPressAnchor = captureRowAnchors,
                                                            onClick = if (sidebarInteractionsEnabled) ({ onChannelClickWithDismiss(item.id) }) else null,
                                                            onLongPress = if (sidebarInteractionsEnabled) customLongPress else null,
                                                            onPress = if (sidebarInteractionsEnabled) ({ pressedRowKey = customRowKey }) else null,
                                                            onPressEnd = { if (pressedRowKey == customRowKey) pressedRowKey = null }
                                                        )
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
            ServerSwitcherAvatarPrewarmSurface(servers)
            if (serverSwitcherExpanded) {
                if (serverDrawerEnabled) {
                    // The opt-in drawer owns the full screen so its scrim and native
                    // dispatch barrier also cover the shell bottom surface.
                    SlockFloatingOverlayHost(
                        onDismiss = onServerSwitcherDismiss,
                        dismissActivationDelayMillis = SlockFloatingOverlayClickTriggeredDismissDelayMillis
                    ) { _ ->
                        HomeServerDrawerPanel(
                            sections =
                                serverDrawerSections.ifEmpty {
                                    servers.map { server ->
                                        SlockHomeServerDrawerSection(
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
                            onCreateServerClick = onCreateServerClick
                        )
                    }
                } else {
                    // Preserve the legacy popover boundary when the opt-in is disabled:
                    // the shell bottom inset/tab surface remains outside the dismiss host.
                    SlockFloatingOverlayHost(
                        onDismiss = onServerSwitcherDismiss,
                        modifier = Modifier.padding(bottom = bottomContentPadding),
                        dismissActivationDelayMillis = SlockFloatingOverlayClickTriggeredDismissDelayMillis
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
                            modifier =
                                Modifier
                                    .padding(
                                        top = homeServerSwitcherPanelTopOffset(topSafeArea),
                                        start = SlockSpacing.Lg
                                    )
                                    .clickable(onClick = {})
                        )
                    }
                }
            }
            if (notificationCenterExpanded) {
                SlockFloatingOverlayHost(
                    onDismiss = { notificationCenterExpanded = false },
                    modifier = Modifier.padding(bottom = bottomContentPadding),
                    dismissActivationDelayMillis = SlockFloatingOverlayClickTriggeredDismissDelayMillis
                ) { _ ->
                    Box(Modifier.fillMaxSize()) {
                        HomeNotificationPopover(
                            visibleNotifications,
                            onNotificationView,
                            onNotificationDismiss,
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(
                                    top = homeNotificationPopoverTopOffset(topSafeArea),
                                    end = SlockSpacing.Md
                                )
                                .slockFloatingContentClickTarget()
                        )
                    }
                }
            }
            activeSortMenu?.let { section ->
                val resolvedSortAnchor = sortMenuAnchor ?: return@let
                SlockBaseMenuOverlay(
                    anchor = resolvedSortAnchor,
                    items = slockHomeSortMenuItems(labels),
                    selectedId = when (section) {
                        SlockHomeSortSection.Channels, SlockHomeSortSection.JointChannels -> channelSortModeState.wireValue
                        SlockHomeSortSection.DirectMessages -> directMessageSortMode.wireValue
                        SlockHomeSortSection.Pinned -> pinnedSortModeState.wireValue
                    },
                    onDismiss = { activeSortMenu = null },
                    onSelect = { selected ->
                        val nextMode = SlockSidebarSortMode.fromWireValue(selected)
                        when (section) {
                            SlockHomeSortSection.Channels, SlockHomeSortSection.JointChannels -> {
                                channelSortModeState = nextMode
                                onChannelSortModeChange(nextMode)
                            }
                            SlockHomeSortSection.DirectMessages -> {
                                directMessageSortMode = nextMode
                                onDmSortModeChange(nextMode)
                            }
                            SlockHomeSortSection.Pinned -> {
                                pinnedSortModeState = nextMode
                                onPinnedSortModeChange(nextMode)
                            }
                        }
                        activeSortMenu = null
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
            val pinTargetIsPinned = activeConversationId != null && activeConversationId in pinnedIdSet
            val sortableFromMenu =
                when (activeMenuSection) {
                    SlockHomeSortSection.Pinned -> pinnedItemsForSort.size > 1
                    SlockHomeSortSection.Channels -> sortableDisplayedChannels.size > 1
                    SlockHomeSortSection.JointChannels -> displayedJointChannels.size > 1
                    SlockHomeSortSection.DirectMessages -> displayedDirectMessages.size > 1
                    null -> false
                }
            val pinMenuItems = activeConversation?.let { conversation ->
                slockHomeConversationMenuItems(
                    conversation = conversation,
                    labels = labels,
                    pinTargetIsPinned = pinTargetIsPinned,
                    sortableFromMenu = sortableFromMenu && sortOverlaySection == null,
                    activeIsDirectMessage = activeIsDirectMessage,
                    activeCanArchive = activeCanArchive,
                    hasCustomSections = customSections.isNotEmpty()
                )
            }
            if (activeConversationId != null && pinMenuItems != null) {
                val resolvedPinAnchor = pinMenuAnchor
                if (resolvedPinAnchor != null) {
                SlockBaseMenuOverlay(
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
                            "mute-channel" -> onChannelActivityMuteToggle(activeConversationId, true)
                            "unmute-channel" -> onChannelActivityMuteToggle(activeConversationId, false)
                            "toggle-pin" -> onPinToggle(activeConversationId)
                            "close-dm" -> onCloseDirectMessage(activeConversationId)
                            "archive-channel" -> onArchiveChannel(activeConversationId)
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
                SlockBaseMenuOverlay(
                    anchor = resolvedPinAnchor,
                    items = slockHomePinnedMemberMenuItems(labels),
                    selectedId = "",
                    onDismiss = {
                        activePinnedMemberMenuKey = null
                        onPinMenuDismiss()
                    },
                    onSelect = { actionId ->
                        if (actionId == "sort-channels") {
                            sortOverlaySection = SlockHomeSortSection.Pinned
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
                    SlockBaseMenuOverlay(
                        anchor = resolvedSectionAnchor,
                        items = slockHomeSectionMenuItems(labels),
                        selectedId = "sort:${activeSectionMenuSection.sortMode}",
                        onDismiss = { activeSectionMenuId = null },
                        onSelect = { actionId ->
                            val sectionId = activeSectionMenuSection.id
                            when {
                                actionId.startsWith("sort:") ->
                                    onSidebarSectionSortModeChange(
                                        sectionId,
                                        SlockSidebarSortMode.fromWireValue(actionId.removePrefix("sort:"))
                                    )
                                actionId == "rename" ->
                                    sectionDialogTarget = SlockHomeSectionDialogTarget.Edit(sectionId)
                                actionId == "create" ->
                                    sectionDialogTarget = SlockHomeSectionDialogTarget.Create
                                actionId == "delete" -> sectionDeleteConfirmId = sectionId
                            }
                            activeSectionMenuId = null
                        }
                    )
                }
            }
            val moveToSectionTarget = moveToSectionConversationId
            if (moveToSectionTarget != null && customSections.isNotEmpty()) {
                val resolvedMoveAnchor = pinMenuAnchor
                if (resolvedMoveAnchor != null) {
                    val moveTargetDm = directMessages.firstOrNull { it.id == moveToSectionTarget }
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
                    SlockBaseMenuOverlay(
                        anchor = resolvedMoveAnchor,
                        items = slockHomeMoveToSectionMenuItems(customSections, moveTargetCurrentSectionId, labels),
                        selectedId = "section:${moveTargetCurrentSectionId.orEmpty()}",
                        onDismiss = { moveToSectionConversationId = null },
                        onSelect = { actionId ->
                            if (actionId.startsWith("section:")) {
                                val targetSectionId = actionId.removePrefix("section:").takeIf { it.isNotBlank() }
                                onMoveConversationToSection(moveToSectionTarget, targetSectionId)
                            }
                            moveToSectionConversationId = null
                        }
                    )
                }
            }
            sectionDialogTarget?.let { target ->
                val editingSection =
                    (target as? SlockHomeSectionDialogTarget.Edit)
                        ?.let { edit -> customSections.firstOrNull { it.id == edit.sectionId } }
                if (target is SlockHomeSectionDialogTarget.Create || editingSection != null) {
                    SlockHomeSectionDialog(
                        title = if (editingSection != null) labels.editSectionTitle else labels.newSectionTitle,
                        submitLabel = if (editingSection != null) null else labels.createSection,
                        nameLabel = labels.sectionNameLabel,
                        namePlaceholder = labels.sectionNamePlaceholder,
                        emojiLabel = labels.sectionEmojiLabel,
                        initialName = editingSection?.name.orEmpty(),
                        initialEmoji = editingSection?.emoji.orEmpty(),
                        onDismiss = { sectionDialogTarget = null },
                        onSubmit = { name, emoji ->
                            if (editingSection != null) {
                                onSidebarSectionRename(editingSection.id, name, emoji)
                            } else {
                                onSidebarSectionCreate(name, emoji)
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
                SlockUnifiedDialog(
                    title = labels.deleteSection,
                    onDismiss = { sectionDeleteConfirmId = null },
                    maxWidth = 384.dp
                ) {
                    Text(
                        text =
                            MR.strings.home_section_delete_message
                                .format(listOf(sectionDeleteSection.name))
                                .localized(),
                        style = SlockType.Caption,
                        color = SlockColor.TextMutedGray
                    )
                    SlockDialogFooter {
                        SlockBrutalActionButton(
                            label = stringResource(MR.strings.common_cancel),
                            onClick = { sectionDeleteConfirmId = null },
                            backgroundColor = SlockColor.SurfaceWhite
                        )
                        SlockBrutalActionButton(
                            label = stringResource(MR.strings.common_delete),
                            onClick = {
                                onSidebarSectionDelete(sectionDeleteSection.id)
                                sectionDeleteConfirmId = null
                            },
                            leadingIcon = SlockLucideIconName.Trash2,
                            backgroundColor = SlockColor.AccentRed
                        )
                    }
                }
            }
            sortOverlaySection?.let { section ->
                val sortItems =
                    when (section) {
                        SlockHomeSortSection.Pinned -> pinnedItemsForSort
                        SlockHomeSortSection.JointChannels -> displayedJointChannels
                        SlockHomeSortSection.DirectMessages -> displayedDirectMessages
                        SlockHomeSortSection.Channels -> sortableDisplayedChannels
                    }
                SlockSidebarSortOverlay(
                    title = when (section) {
                        SlockHomeSortSection.Pinned -> labels.pinned
                        SlockHomeSortSection.JointChannels -> labels.jointChannels
                        SlockHomeSortSection.DirectMessages -> labels.directMessages
                        SlockHomeSortSection.Channels -> labels.channels
                    },
                    items = sortItems,
                    saveLabel = labels.saveSort,
                    onDismiss = { sortOverlaySection = null },
                    onSave = { orderedIds ->
                        sortOverlaySection = null
                        val initialIds = sortItems.map { it.id }
                        if (orderedIds != initialIds) {
                            when (section) {
                                SlockHomeSortSection.Pinned -> onSidebarOrderChange(SlockSidebarSortTarget.Pinned, orderedIds)
                                SlockHomeSortSection.JointChannels -> onSidebarOrderChange(SlockSidebarSortTarget.JointChannels, orderedIds)
                                SlockHomeSortSection.DirectMessages -> onSidebarOrderChange(SlockSidebarSortTarget.DirectMessages, orderedIds)
                                SlockHomeSortSection.Channels -> onSidebarOrderChange(SlockSidebarSortTarget.Channels, orderedIds)
                            }
                        }
                    }
                )
            }
        }
        if (showBottomBar && !bottomBarOverlaysContent) {
            val homeTabLabel = stringResource(MR.strings.tab_home)
            val tasksTabLabel = stringResource(MR.strings.tab_tasks)
            val membersTabLabel = stringResource(MR.strings.tab_members)
            val settingsTabLabel = stringResource(MR.strings.tab_settings)
            val bottomTabItems = remember(
                selectedTabKey,
                homeTabLabel,
                tasksTabLabel,
                membersTabLabel,
                settingsTabLabel
            ) {
                listOf(
                    SlockBottomTabItemPayload("home", homeTabLabel, SlockLucideIconName.House, selected = selectedTabKey == "home"),
                    SlockBottomTabItemPayload("tasks", tasksTabLabel, SlockLucideIconName.SquareCheckBig, selected = selectedTabKey == "tasks"),
                    SlockBottomTabItemPayload("members", membersTabLabel, SlockLucideIconName.Users, selected = selectedTabKey == "members"),
                    SlockBottomTabItemPayload("settings", settingsTabLabel, SlockLucideIconName.Settings, selected = selectedTabKey == "settings")
                )
            }
            SlockAppBottomTabBarHost(
                items = bottomTabItems,
                onSelect = onBottomTabSelected,
                modifier = Modifier.fillMaxWidth(),
                bottomSafeArea = bottomSafeArea
            )
        }
        }
        if (bottomBarOverlaysContent) {
            val homeTabLabel = stringResource(MR.strings.tab_home)
            val tasksTabLabel = stringResource(MR.strings.tab_tasks)
            val membersTabLabel = stringResource(MR.strings.tab_members)
            val settingsTabLabel = stringResource(MR.strings.tab_settings)
            val bottomTabItems = remember(
                selectedTabKey,
                homeTabLabel,
                tasksTabLabel,
                membersTabLabel,
                settingsTabLabel
            ) {
                listOf(
                    SlockBottomTabItemPayload("home", homeTabLabel, SlockLucideIconName.House, selected = selectedTabKey == "home"),
                    SlockBottomTabItemPayload("tasks", tasksTabLabel, SlockLucideIconName.SquareCheckBig, selected = selectedTabKey == "tasks"),
                    SlockBottomTabItemPayload("members", membersTabLabel, SlockLucideIconName.Users, selected = selectedTabKey == "members"),
                    SlockBottomTabItemPayload("settings", settingsTabLabel, SlockLucideIconName.Settings, selected = selectedTabKey == "settings")
                )
            }
            SlockAppBottomTabBarHost(
                items = bottomTabItems,
                onSelect = onBottomTabSelected,
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                bottomSafeArea = bottomSafeArea
            )
        }
    }
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
    slockViewportBeyondBoundsItemCount(
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
    hasPullToRefreshItem: Boolean = true,
    hasInlineError: Boolean = false,
    channelSkeletonCount: Int = 0,
    directMessageSkeletonCount: Int = 0
): Int {
    val fixedItems =
        (if (hasPullToRefreshItem) 1 else 0) +
            1 + // top spacer
            1 + // search
            1 + // activity
            1 + // saved
            1 + // section spacer
            1 + // channels header
            1 + // section spacer
            1 + // direct messages header
            1 // bottom spacer
    val jointSectionItems = if (jointCount > 0) 2 else 0 // spacer + header
    val pinnedSectionItems = if (pinnedCount > 0) 2 else 0 // header + (rows below); rows counted conditionally
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
internal sealed interface SlockHomeSidebarSection {
    object Pinned : SlockHomeSidebarSection
    object JointChannels : SlockHomeSidebarSection
    object Channels : SlockHomeSidebarSection
    object DirectMessages : SlockHomeSidebarSection
    data class Custom(val rows: SlockHomeCustomSectionRows) : SlockHomeSidebarSection
}

/**
 * The home sidebar's top-level blocks in render order. Mirrors web Sidebar.tsx,
 * which renders every system block (`system:pinned` / `system:joint` /
 * `system:channels` / `system:dms`) and every custom section with CSS
 * `order = sectionOrder.indexOf(id)`, where sectionOrder is web
 * `normalizeSidebarSectionOrder` output: so system and custom sections
 * interleave in the user's order, and ids missing from it fall back to system
 * sections in canonical order, then custom sections in declaration order.
 * Visibility keeps mobile's rules: pinned and joint hide when empty, custom
 * sections appear only if [customSectionRows] has them (non-empty), DMs always show.
 */
internal fun homeSidebarSectionOrder(
    sectionOrder: List<String>,
    customSections: List<SettingsServerSidebarCustomSection>,
    customSectionRows: List<SlockHomeCustomSectionRows>,
    hasPinned: Boolean,
    hasJointChannels: Boolean
): List<SlockHomeSidebarSection> {
    val rowsBySectionId = customSectionRows.associateBy { it.section.id }
    return normalizeSidebarSectionOrder(sectionOrder, customSections).mapNotNull { id ->
        when (id) {
            "system:pinned" -> SlockHomeSidebarSection.Pinned.takeIf { hasPinned }
            "system:joint" -> SlockHomeSidebarSection.JointChannels.takeIf { hasJointChannels }
            "system:channels" -> SlockHomeSidebarSection.Channels
            "system:dms" -> SlockHomeSidebarSection.DirectMessages
            else -> rowsBySectionId[id]?.let { SlockHomeSidebarSection.Custom(it) }
        }
    }
}

private enum class SlockHomeSortSection { Channels, JointChannels, DirectMessages, Pinned }

internal fun slockHomeConversationMenuItems(
    conversation: SlockHomeConversationItem,
    labels: SlockHomeShellLabels = SlockHomeShellLabels(),
    pinTargetIsPinned: Boolean,
    sortableFromMenu: Boolean,
    activeIsDirectMessage: Boolean,
    activeCanArchive: Boolean,
    hasCustomSections: Boolean = false
): List<SlockBaseMenuItem> =
    buildList {
        val hasUnread = conversation.homeConversationBadgeProjection().hasUnread
        add(
            SlockBaseMenuItem(
                id = if (hasUnread) "mark-read" else "mark-unread",
                label = if (hasUnread) labels.markAsRead else labels.markAsUnread,
                icon = if (hasUnread) SlockLucideIconName.MailOpen else SlockLucideIconName.Mail
            )
        )
        if (conversation.activityMuteSupported) {
            add(
                SlockBaseMenuItem(
                    id = if (conversation.activityMuted) "unmute-channel" else "mute-channel",
                    label = if (conversation.activityMuted) labels.unmute else labels.mute,
                    icon = if (conversation.activityMuted) SlockLucideIconName.Bell else SlockLucideIconName.BellOff
                )
            )
        }
        add(
            SlockBaseMenuItem(
                id = "toggle-pin",
                label = if (pinTargetIsPinned) labels.unpin else labels.pin,
                icon = if (pinTargetIsPinned) SlockLucideIconName.PinOff else SlockLucideIconName.Pin
            )
        )
        // task #512: web parity (MoveToSectionMenuItem) — only when at least one
        // custom section exists to move into.
        if (hasCustomSections) {
            add(
                SlockBaseMenuItem(
                    id = "move-to-section",
                    label = labels.moveToSection,
                    icon = SlockLucideIconName.Folder
                )
            )
        }
        if (sortableFromMenu) {
            add(
                SlockBaseMenuItem(
                    id = "sort-channels",
                    label = labels.sortChannels,
                    icon = SlockLucideIconName.ArrowUpDown
                )
            )
        }
        if (activeIsDirectMessage) {
            add(SlockBaseMenuItem(id = "close-dm", label = labels.closeChat, icon = SlockLucideIconName.X))
        } else if (activeCanArchive) {
            add(
                SlockBaseMenuItem(
                    id = "archive-channel",
                    label = labels.archive,
                    icon = SlockLucideIconName.Archive,
                    dividerBefore = true
                )
            )
        }
    }

internal fun slockHomePinnedMemberMenuItems(
    labels: SlockHomeShellLabels = SlockHomeShellLabels()
): List<SlockBaseMenuItem> =
    listOf(
        SlockBaseMenuItem(
            id = "sort-channels",
            label = labels.sortChannels,
            icon = SlockLucideIconName.ArrowUpDown
        )
    )

/**
 * task #512: custom-section header menu — sort radios first (the overlay's
 * selectedId marks the current mode), then rename / new / delete. Mirrors web's
 * section context menu (Sidebar.tsx) minus the desktop-only display toggles.
 */
internal fun slockHomeSectionMenuItems(labels: SlockHomeShellLabels = SlockHomeShellLabels()): List<SlockBaseMenuItem> =
    SlockSidebarSortMode.entries.map { mode ->
        SlockBaseMenuItem(
            id = "sort:${mode.wireValue}",
            label =
                when (mode) {
                    SlockSidebarSortMode.Manual -> labels.sortManual
                    SlockSidebarSortMode.Recent -> labels.sortRecent
                    SlockSidebarSortMode.Az -> labels.sortAz
                }
        )
    } +
        listOf(
            SlockBaseMenuItem(id = "rename", label = labels.renameSection, icon = SlockLucideIconName.Pencil, dividerBefore = true),
            SlockBaseMenuItem(id = "create", label = labels.newSectionMenu, icon = SlockLucideIconName.Plus),
            SlockBaseMenuItem(id = "delete", label = labels.deleteSection, icon = SlockLucideIconName.Trash2, dividerBefore = true)
        )

/**
 * task #512: "move to section" submenu rows — one per custom section plus a
 * remove entry (only when the conversation is currently placed). Item ids carry
 * the target: `section:<id>`; the bare `section:` id means the default section.
 */
internal fun slockHomeMoveToSectionMenuItems(
    customSections: List<SettingsServerSidebarCustomSection>,
    currentSectionId: String?,
    labels: SlockHomeShellLabels = SlockHomeShellLabels()
): List<SlockBaseMenuItem> =
    buildList {
        if (currentSectionId != null) {
            add(SlockBaseMenuItem(id = "section:", label = labels.removeFromSection, icon = SlockLucideIconName.X))
        }
        customSections.forEach { section ->
            add(
                SlockBaseMenuItem(
                    id = "section:${section.id}",
                    label = section.sidebarSectionHeaderLabel(),
                    icon = SlockLucideIconName.Folder
                )
            )
        }
    }

/** task #512: create/rename target for the custom-section dialog. */
private sealed interface SlockHomeSectionDialogTarget {
    data object Create : SlockHomeSectionDialogTarget

    data class Edit(val sectionId: String) : SlockHomeSectionDialogTarget
}

/**
 * task #512: create/rename dialog for a custom section — web
 * SidebarSectionDialog.tsx reduced to a name field plus a plain emoji text
 * field (no emoji picker on mobile). Web caps the name at 80 chars.
 */
@Composable
private fun SlockHomeSectionDialog(
    title: String,
    submitLabel: String?,
    nameLabel: String,
    namePlaceholder: String,
    emojiLabel: String,
    initialName: String,
    initialEmoji: String,
    onDismiss: () -> Unit,
    onSubmit: (name: String, emoji: String?) -> Unit
) {
    var name by remember { mutableStateOf(textFieldValueAtEnd(initialName)) }
    var emoji by remember { mutableStateOf(textFieldValueAtEnd(initialEmoji)) }
    val trimmedName = name.text.trim()
    SlockUnifiedDialog(
        title = title,
        onDismiss = onDismiss,
        maxWidth = 384.dp
    ) {
        Text(text = nameLabel, style = SlockType.Caption, color = SlockColor.TextMutedGray)
        SlockBrutalInputField(
            value = name,
            onValueChange = { name = it.coerceTextLength(80) },
            placeholder = namePlaceholder,
            autoFocus = true
        )
        Text(text = emojiLabel, style = SlockType.Caption, color = SlockColor.TextMutedGray)
        SlockBrutalInputField(
            value = emoji,
            onValueChange = { emoji = it.coerceTextLength(8) },
            placeholder = "🙂"
        )
        SlockDialogFooter {
            SlockBrutalActionButton(
                label = stringResource(MR.strings.common_cancel),
                onClick = onDismiss,
                backgroundColor = SlockColor.SurfaceWhite
            )
            SlockBrutalActionButton(
                label = submitLabel ?: stringResource(MR.strings.common_save),
                onClick = { onSubmit(trimmedName, emoji.text.trim().takeIf { it.isNotEmpty() }) },
                enabled = trimmedName.isNotEmpty(),
                backgroundColor = SlockColor.BrandYellow
            )
        }
    }
}

enum class SlockSidebarSortTarget { Channels, JointChannels, DirectMessages, Pinned }

internal fun SlockSidebarSortTarget.toReorderSidebarIntent(order: List<String>): SharedHomeIntent.ReorderSidebar =
    when (this) {
        SlockSidebarSortTarget.Channels -> SharedHomeIntent.ReorderSidebar(channelOrder = order)
        SlockSidebarSortTarget.JointChannels -> SharedHomeIntent.ReorderSidebar(jointChannelOrder = order)
        SlockSidebarSortTarget.DirectMessages -> SharedHomeIntent.ReorderSidebar(dmOrder = order)
        SlockSidebarSortTarget.Pinned -> SharedHomeIntent.ReorderSidebar(pinnedOrder = order)
    }

internal fun slockHomeSortMenuItems(labels: SlockHomeShellLabels = SlockHomeShellLabels()): List<SlockBaseMenuItem> =
    SlockSidebarSortMode.entries.map { mode ->
        SlockBaseMenuItem(
            id = mode.wireValue,
            label =
                when (mode) {
                    SlockSidebarSortMode.Manual -> labels.sortManual
                    SlockSidebarSortMode.Recent -> labels.sortRecent
                    SlockSidebarSortMode.Az -> labels.sortAz
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
    if (label == null) return HomeSidebarBadgeProjection(label = null)
    val loud =
        input.joined &&
            (!input.muted || input.mentionPierced || input.followed)
    return HomeSidebarBadgeProjection(
        label = label,
        tone = if (loud) HomeSidebarBadgeTone.Loud else HomeSidebarBadgeTone.Quiet,
        hasUnread = true,
        hasAttention = loud
    )
}

internal fun SlockHomeConversationItem.homeConversationBadgeProjection(
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
            followed = followed
        )
    )

internal fun homeActivitySidebarProjection(
    activityUnreadCount: Int,
    activityTotalCount: Int
): HomeActivitySidebarProjection =
    HomeActivitySidebarProjection(
        unreadLabel = formatHomeSidebarBadgeCount(homeActivitySidebarUnreadCount(activityUnreadCount)),
        totalLabel = formatHomeSidebarBadgeCount(homeActivitySidebarTotalCount(activityUnreadCount, activityTotalCount))
    )

internal fun homeServerSwitcherHasOtherUnread(
    currentServerId: String,
    servers: List<SlockServerSwitcherItem>
): Boolean =
    servers.any { server ->
        server.id != currentServerId &&
            homeServerSwitcherBadgePresentation(server).kind != HomeServerSwitcherBadgeKind.None
    }

private const val DefaultCommunityServerSlug = "community"
private const val ChineseCommunityServerSlug = "community-cn"

internal data class SlockServerSwitcherCommunityAction(
    val slug: String,
    val label: String
)

internal fun homeServerSwitcherHasJoinedCommunity(
    servers: List<SlockServerSwitcherItem>,
    slug: String
): Boolean =
    servers.any { it.slug == slug }

internal fun homeServerSwitcherJoinCommunityActions(
    servers: List<SlockServerSwitcherItem>,
    labels: SlockHomeShellLabels,
    includeChineseCommunity: Boolean
): List<SlockServerSwitcherCommunityAction> =
    buildList {
        if (
            includeChineseCommunity &&
            !homeServerSwitcherHasJoinedCommunity(servers, ChineseCommunityServerSlug)
        ) {
            add(SlockServerSwitcherCommunityAction(ChineseCommunityServerSlug, labels.joinChineseCommunityServer))
        }
        if (!homeServerSwitcherHasJoinedCommunity(servers, DefaultCommunityServerSlug)) {
            add(SlockServerSwitcherCommunityAction(DefaultCommunityServerSlug, labels.joinCommunityServer))
        }
    }

internal fun homeServerSwitcherPanelTopOffset(topSafeArea: Dp): Dp =
    topSafeArea + SlockSize.PageHeaderHeight + HomeServerSwitcherTopGap

internal fun homeShellHeaderHeight(topSafeArea: Dp): Dp =
    topSafeArea + SlockSize.PageHeaderHeight

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

internal data class SlockHomeSidebarSelection(
    val topAction: SlockHomeSidebarTopSelection? = null,
    val conversationId: String? = null,
    val conversationSection: SlockHomeConversationSection? = null
)

internal data class SlockHomeSectionSelection(
    val section: SlockHomeConversationSection,
    val selectedId: String?
)

internal fun homeSidebarSelection(
    selectedChannelId: String?,
    selectedConversationSection: SlockHomeConversationSection?,
    channels: List<SlockHomeConversationItem>,
    directMessages: List<SlockHomeConversationItem>,
    searchSelected: Boolean,
    activitySelected: Boolean,
    savedSelected: Boolean,
    pinnedChannelIds: Set<String> = emptySet()
): SlockHomeSidebarSelection =
    when {
        searchSelected -> SlockHomeSidebarSelection(topAction = SlockHomeSidebarTopSelection.Search)
        activitySelected -> SlockHomeSidebarSelection(topAction = SlockHomeSidebarTopSelection.Activity)
        savedSelected -> SlockHomeSidebarSelection(topAction = SlockHomeSidebarTopSelection.Saved)
        selectedChannelId != null -> {
            val section =
                selectedConversationSection
                    ?: homeConversationSectionForSelection(
                        selectedChannelId = selectedChannelId,
                        channels = channels,
                        directMessages = directMessages,
                        pinnedChannelIds = pinnedChannelIds
                    )
            SlockHomeSidebarSelection(
                conversationId = selectedChannelId,
                conversationSection = section
            )
        }
        else -> SlockHomeSidebarSelection()
    }

internal fun homeConversationSectionForSelection(
    selectedChannelId: String,
    channels: List<SlockHomeConversationItem>,
    directMessages: List<SlockHomeConversationItem>,
    pinnedChannelIds: Set<String> = emptySet()
): SlockHomeConversationSection? {
    // #211: a pinned id should always resolve to the Pinned section, even
    // if it is still present in the raw `channels` / `directMessages` lists
    // (server returns the full set, the visual layer hides the original
    // copies). Otherwise the section-aware selection would point at the
    // Channel / JointChannel / DirectMessage row and the Pinned row would
    // not be highlighted, leaving the user with two un-highlighted sections
    // and one phantom highlight in the original section.
    if (selectedChannelId in pinnedChannelIds) {
        return SlockHomeConversationSection.Pinned
    }
    val channel = channels.firstOrNull { it.id == selectedChannelId }
    if (channel != null) {
        return if (channel.homeChannelKind() == JointChannelKindWireValue) {
            SlockHomeConversationSection.JointChannel
        } else {
            SlockHomeConversationSection.Channel
        }
    }
    return if (directMessages.any { it.id == selectedChannelId }) {
        SlockHomeConversationSection.DirectMessage
    } else {
        null
    }
}

internal fun SlockHomeConversationItem.homeChannelKind(): String =
    when (parseConversationKindOrNull(kind)) {
        ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
        ConversationKind.Joint -> JointChannelKindWireValue
        else -> {
            channelType?.takeIf { it.isNotBlank() }?.lowercase()
                ?: kind.lowercase()
        }
    }

internal fun SlockHomeConversationItem.isDirectMessageConversation(): Boolean =
    parseConversationKindOrNull(kind) == ConversationKind.DirectMessage

internal fun SlockHomeConversationItem.homeChannelSymbol(): String =
    homeChannelKind().toSlockChannelSymbol()

/**
 * Row glyph symbol including the public-page mark: a regular channel readable on the server's
 * live public page draws the hash-globe glyph in place of `#` (web channelKindIcon.tsx:22).
 * Interaction identities keep using the plain [homeChannelSymbol] so they never change with it.
 */
internal fun SlockHomeConversationItem.homeChannelSymbol(serverPublicPageLive: Boolean): String =
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

internal const val HomeChannelSymbolHashGlobe = "hash-globe"

internal fun String?.toSlockChannelSymbol(): String =
    when (this?.lowercase()) {
        "private" -> "lock"
        JointChannelKindWireValue -> "git-branch"
        else -> "#"
    }

internal fun SlockHomeSidebarSelection.sectionSelection(
    section: SlockHomeConversationSection
): SlockHomeSectionSelection =
    SlockHomeSectionSelection(
        section = section,
        selectedId = conversationId.takeIf { conversationSection == section }
    )

internal fun SlockHomeSectionSelection.selects(id: String): Boolean =
    selectedId == id

internal fun List<SlockHomeConversationItem>.sortedForSidebar(
    mode: SlockSidebarSortMode,
    pinnedFirstId: String? = null,
    label: (SlockHomeConversationItem) -> String
): List<SlockHomeConversationItem> {
    if (mode == SlockSidebarSortMode.Manual) return withPinnedFirst(pinnedFirstId)
    val pinned = pinnedFirstId?.let { id -> firstOrNull { it.id == id } }
    val sortable = pinned?.let { item -> filterNot { it.id == item.id } } ?: this
    val sorted =
        when (mode) {
            SlockSidebarSortMode.Manual -> sortable
            SlockSidebarSortMode.Recent ->
                sortable.sortedWith(
                    compareByDescending<SlockHomeConversationItem> {
                        parseSlockTimestampEpochMillisOrNull(it.lastMessageAt ?: it.createdAt) ?: Long.MIN_VALUE
                    }.thenBy { label(it).trim().lowercase() }.thenBy { it.id }
                )
            SlockSidebarSortMode.Az ->
                sortable.sortedWith(compareBy<SlockHomeConversationItem> { label(it).trim().lowercase() }.thenBy { it.id })
        }
    return if (pinned != null) listOf(pinned) + sorted else sorted
}

internal fun List<SlockHomeConversationItem>.orderedBySidebarIds(
    order: List<String>,
    pinnedFirstId: String? = null
): List<SlockHomeConversationItem> {
    if (isEmpty()) return this
    if (order.isEmpty()) return withPinnedFirst(pinnedFirstId)
    val byId = associateBy { it.id }
    val ordered = LinkedHashMap<String, SlockHomeConversationItem>()
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

private fun List<SlockHomeConversationItem>.withPinnedFirst(pinnedFirstId: String?): List<SlockHomeConversationItem> {
    if (pinnedFirstId.isNullOrBlank() || size < 2 || firstOrNull()?.id == pinnedFirstId) return this
    val pinned = firstOrNull { it.id == pinnedFirstId } ?: return this
    return listOf(pinned) + filterNot { it.id == pinnedFirstId }
}

internal fun List<SlockHomeConversationItem>.withoutSidebarPinnedItem(
    pinnedFirstId: String?
): List<SlockHomeConversationItem> {
    if (pinnedFirstId.isNullOrBlank()) return this
    return filterNot { it.id == pinnedFirstId }
}

internal fun homeServerDrawerRows(
    sections: List<SlockHomeServerDrawerSection>
): List<SlockHomeServerDrawerRow> =
    buildList {
        sections.forEach { drawerSection ->
            val serverId = drawerSection.server.id
            add(SlockHomeServerDrawerRow.ServerHeader(drawerSection))
            if (!drawerSection.expanded) return@forEach

            val hasCache = drawerSection.channels.isNotEmpty() || drawerSection.directMessages.isNotEmpty()
            if (!hasCache && drawerSection.status in setOf(HomeServerDrawerLoadStatus.Unloaded, HomeServerDrawerLoadStatus.Loading)) {
                repeat(3) { index -> add(SlockHomeServerDrawerRow.Skeleton(serverId, index)) }
                return@forEach
            }

            val hiddenDmIds = drawerSection.hiddenDmIds.toSet()
            val visibleDms =
                drawerSection.directMessages
                    .orderedBySidebarIds(drawerSection.dmOrder)
                    .filter { dm -> dm.id !in hiddenDmIds || dm.homeConversationBadgeProjection().hasUnread }
                    .sortedForSidebar(SlockSidebarSortMode.fromWireValue(drawerSection.dmSortMode)) { it.name }
            val regularChannels =
                drawerSection.channels
                    .filter { it.kind != JointChannelKindWireValue }
                    .orderedBySidebarIds(drawerSection.channelOrder)
                    .sortedForSidebar(SlockSidebarSortMode.fromWireValue(drawerSection.channelSortMode)) { it.name }
            val jointChannels =
                drawerSection.channels
                    .filter { it.kind == JointChannelKindWireValue }
                    .orderedBySidebarIds(drawerSection.channelOrder)
                    .sortedForSidebar(SlockSidebarSortMode.fromWireValue(drawerSection.channelSortMode)) { it.name }
            val pinned =
                pinnedRowsForSidebar(
                    channels = drawerSection.channels,
                    directMessages = drawerSection.directMessages,
                    pinnedIds = drawerSection.pinnedChannelIds,
                    pinnedAgentIds = drawerSection.pinnedAgentIds,
                    pinnedOrder = drawerSection.pinnedOrder,
                    typedPinnedItems = drawerSection.pinnedItems,
                    pinnedMemberProfileResolver = { null },
                    mode = SlockSidebarSortMode.fromWireValue(drawerSection.pinnedSortMode)
                ).mapNotNull { it.conversation }

            fun appendConversationSection(
                section: SlockHomeConversationSection,
                items: List<SlockHomeConversationItem>
            ) {
                if (items.isEmpty()) return
                add(SlockHomeServerDrawerRow.SectionHeader(serverId, section, items.size))
                items.forEach { item -> add(SlockHomeServerDrawerRow.Conversation(serverId, section, item)) }
            }

            appendConversationSection(SlockHomeConversationSection.Pinned, pinned)
            appendConversationSection(SlockHomeConversationSection.Channel, regularChannels)
            appendConversationSection(SlockHomeConversationSection.JointChannel, jointChannels)
            appendConversationSection(SlockHomeConversationSection.DirectMessage, visibleDms)

            if (drawerSection.status in setOf(HomeServerDrawerLoadStatus.ErrorEmpty, HomeServerDrawerLoadStatus.ErrorWithCache)) {
                add(
                    SlockHomeServerDrawerRow.Error(
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

internal fun List<String>.moveItem(id: String, toIndex: Int): List<String> {
    val fromIndex = indexOf(id)
    if (fromIndex < 0 || toIndex !in indices || fromIndex == toIndex) return this
    val mutable = toMutableList()
    val item = mutable.removeAt(fromIndex)
    mutable.add(toIndex.coerceIn(0, mutable.size), item)
    return mutable
}

/** A visible sort-overlay row's laid-out vertical bounds, in viewport space. */
internal data class SlockSortRowBounds(val index: Int, val top: Float, val size: Float)

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
    rows: List<SlockSortRowBounds>,
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

private suspend fun runSlockSortAutoScrollLoop(
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
internal data class SlockHomePinnedMemberItem(
    val identity: MemberDetailIdentity,
    val name: String,
    val description: String? = null,
    val avatarLabel: String
)

internal fun MemberProfile.toSlockHomePinnedMemberItem(): SlockHomePinnedMemberItem? {
    if (kind == MemberKind.System) return null
    return SlockHomePinnedMemberItem(
        identity = MemberDetailIdentity(id, kind, serverId),
        name = displayName.ifBlank { handle.ifBlank { id.value } },
        description = handle.takeIf { it.isNotBlank() }?.let { "@$it" },
        avatarLabel = avatarLabel.ifBlank { displayName.take(1).uppercase().ifBlank { "?" } }
    )
}

internal data class SlockHomePinnedRow(
    val key: String,
    val conversation: SlockHomeConversationItem? = null,
    val member: SlockHomePinnedMemberItem? = null
) {
    val sortLabel: String = conversation?.name ?: member?.name.orEmpty()
    val sortTimestamp: String? = conversation?.lastMessageAt ?: conversation?.createdAt
}

internal sealed interface SlockHomePinnedClickTarget {
    data class Conversation(val conversationId: String) : SlockHomePinnedClickTarget

    data class Member(val identity: MemberDetailIdentity) : SlockHomePinnedClickTarget
}

internal fun SlockHomePinnedRow.clickTarget(): SlockHomePinnedClickTarget? =
    conversation?.let { SlockHomePinnedClickTarget.Conversation(it.id) }
        ?: member?.let { SlockHomePinnedClickTarget.Member(it.identity) }

internal fun SlockHomePinnedRow.supportsLongPress(): Boolean = conversation != null || member != null

internal fun SlockHomePinnedRow.toSidebarSortItem(): SlockHomeConversationItem? =
    conversation ?: member?.let { pinnedMember ->
        SlockHomeConversationItem(
            id = pinnedMember.identity.memberId.value,
            name = pinnedMember.name,
            kind = KuiklyThreadSurfaceKind.DirectMessage,
            channelType = "dm",
            description = pinnedMember.description,
            avatarLabel = pinnedMember.avatarLabel,
            peerType = pinnedMember.identity.kind.memberKindWireValue(),
            presenceMemberId = pinnedMember.identity.memberId.value
        )
    }

internal fun pinnedItemsForSidebar(
    channels: List<SlockHomeConversationItem>,
    directMessages: List<SlockHomeConversationItem>,
    pinnedIds: List<String>,
    pinnedAgentIds: List<String> = emptyList(),
    pinnedOrder: List<String>,
    mode: SlockSidebarSortMode
): List<SlockHomeConversationItem> =
    pinnedRowsForSidebar(
        channels = channels,
        directMessages = directMessages,
        pinnedIds = pinnedIds,
        pinnedAgentIds = pinnedAgentIds,
        pinnedOrder = pinnedOrder,
        mode = mode
    ).mapNotNull { it.conversation }

internal fun pinnedRowsForSidebar(
    channels: List<SlockHomeConversationItem>,
    directMessages: List<SlockHomeConversationItem>,
    pinnedIds: List<String>,
    pinnedAgentIds: List<String> = emptyList(),
    pinnedOrder: List<String>,
    typedPinnedItems: List<SettingsServerSidebarPinnedItem> = emptyList(),
    pinnedMemberProfileResolver: (String) -> SlockHomePinnedMemberItem? = { null },
    mode: SlockSidebarSortMode
): List<SlockHomePinnedRow> {
    val byId: Map<String, SlockHomeConversationItem> = buildMap {
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
                    byId[id]?.let { SlockHomePinnedRow(key = "channel:$id", conversation = it) }
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
                        SlockHomePinnedRow(key = "$kind:$id", conversation = conversation)
                    } else {
                        pinnedMemberProfileResolver(id)
                            ?.takeIf { it.identity.kind == pinnedMemberKind }
                            ?.let { member -> SlockHomePinnedRow(key = "$kind:$id", member = member) }
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
        SlockSidebarSortMode.Manual -> {
            val ordered = LinkedHashMap<String, SlockHomePinnedRow>()
            pinnedOrder.forEach { id ->
                byId[id]?.takeIf { id in pinnedSet }?.let { ordered.put(it.id, SlockHomePinnedRow(key = id, conversation = it)) }
            }
            existingPinned.forEach { item ->
                if (!ordered.containsKey(item.id)) {
                    ordered.put(item.id, SlockHomePinnedRow(key = item.id, conversation = item))
                }
            }
            ordered.values.toList()
        }
        SlockSidebarSortMode.Recent ->
            existingPinned.sortedWith(
                compareByDescending<SlockHomeConversationItem> {
                    parseSlockTimestampEpochMillisOrNull(it.lastMessageAt ?: it.createdAt) ?: Long.MIN_VALUE
                }.thenBy { it.name.trim().lowercase() }.thenBy { it.id }
            ).map { SlockHomePinnedRow(key = it.id, conversation = it) }
        SlockSidebarSortMode.Az ->
            existingPinned.sortedWith(
                compareBy<SlockHomeConversationItem> { it.name.trim().lowercase() }.thenBy { it.id }
            ).map { SlockHomePinnedRow(key = it.id, conversation = it) }
    }
}

/** task #512: custom section header label — emoji prefix when one is set. */
internal fun SettingsServerSidebarCustomSection.sidebarSectionHeaderLabel(): String =
    emoji?.takeIf { it.isNotBlank() }?.let { "$it $name" } ?: name

internal data class SlockHomeCustomSectionRows(
    val section: SettingsServerSidebarCustomSection,
    val items: List<SlockHomeConversationItem>
)

/**
 * task #512: resolve custom-section placements to conversation rows, in
 * sectionOrder order. Mirrors web Sidebar.tsx `customSectionItems`: placements
 * resolve channels and DM channels by id (kind "channel") or agent-peer DMs by
 * member id (kind "agent"); pinned conversations stay in the pinned section
 * (the two stay mutually exclusive); empty sections keep mobile's always-hidden
 * behavior; each section's items follow its own sort mode.
 */
internal fun sidebarCustomSectionRows(
    channels: List<SlockHomeConversationItem>,
    directMessages: List<SlockHomeConversationItem>,
    customSections: List<SettingsServerSidebarCustomSection>,
    sectionOrder: List<String>,
    sectionPlacements: List<SettingsServerSidebarSectionPlacement>,
    pinnedConversationIds: Set<String>
): List<SlockHomeCustomSectionRows> {
    if (customSections.isEmpty() || sectionPlacements.isEmpty()) return emptyList()
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
    val itemsBySection = LinkedHashMap<String, MutableList<SlockHomeConversationItem>>()
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
            if (items.isEmpty()) return@mapNotNull null
            SlockHomeCustomSectionRows(
                section = section,
                items = items.sortedForSidebar(SlockSidebarSortMode.fromWireValue(section.sortMode)) { it.name }
            )
        }
}

/**
 * task #512: conversation ids hidden from their default system sections because
 * a custom section claims them (web customPlacedChannelIds / customPlacedAgentIds).
 */
internal fun sidebarCustomPlacedConversationIds(
    sectionPlacements: List<SettingsServerSidebarSectionPlacement>,
    directMessages: List<SlockHomeConversationItem>
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

private fun List<SlockHomePinnedRow>.sortedPinnedRows(mode: SlockSidebarSortMode): List<SlockHomePinnedRow> =
    when (mode) {
        SlockSidebarSortMode.Manual -> this
        SlockSidebarSortMode.Recent ->
            sortedWith(
                compareByDescending<SlockHomePinnedRow> {
                    parseSlockTimestampEpochMillisOrNull(it.sortTimestamp) ?: Long.MIN_VALUE
                }.thenBy { it.sortLabel.trim().lowercase() }.thenBy { it.key }
            )
        SlockSidebarSortMode.Az ->
            sortedWith(compareBy<SlockHomePinnedRow> { it.sortLabel.trim().lowercase() }.thenBy { it.key })
    }
@Composable
private fun HomeTitlebar(
    serverName: String,
    serverIdentity: SlockServerSwitcherItem? = null,
    serverPublicPageLive: Boolean = false,
    hasOtherServerUnread: Boolean,
    notifications: List<SlockHomeNotificationItem>,
    notificationCenterExpanded: Boolean = false,
    refreshing: Boolean,
    topSafeArea: Dp,
    onServerBadgeClick: () -> Unit,
    onBlankClick: (() -> Unit)? = null,
    onNotificationToggle: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth().height(homeShellHeaderHeight(topSafeArea)).background(SlockColor.BrandYellow)) {
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack).align(Alignment.BottomCenter))
        com.tencent.kuikly.compose.foundation.layout.Row(
            modifier =
                Modifier
                    .padding(top = topSafeArea)
                    .height(SlockSize.PageHeaderContentHeight)
                    .padding(horizontal = SlockSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (serverIdentity == null) {
                Box(modifier = Modifier.clickable(onClick = onServerBadgeClick)) {
                    SlockLogoBadge(
                        text = serverName,
                        showDropdown = true,
                        tilted = true,
                        unread = hasOtherServerUnread,
                        publicPageLive = serverPublicPageLive,
                        textStyle = SlockType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 24.sp)
                    )
                }
                val blankModifier =
                    if (onBlankClick != null) {
                        Modifier.weight(1f).height(SlockSize.PageHeaderContentHeight).clickable(onClick = onBlankClick)
                    } else {
                        Modifier.weight(1f)
                    }
                Spacer(blankModifier)
            } else {
                HomeServerIdentityCluster(
                    server = serverIdentity,
                    onClick = onServerBadgeClick,
                    modifier = Modifier.weight(1f)
                )
            }
            if (refreshing) {
                SlockHeaderRefreshIndicator()
            }
            HomeNotificationCenter(
                notifications = notifications,
                open = notificationCenterExpanded,
                onToggle = onNotificationToggle
            )
        }
    }
}

@Composable
private fun HomeServerIdentityCluster(
    server: SlockServerSwitcherItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.height(SlockSize.PageHeaderContentHeight).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ServerSwitcherAvatarTile(server)
        Spacer(Modifier.width(8.dp))
        Text(
            text = server.name,
            color = SlockColor.TextBlack,
            style = SlockType.ButtonBold.copy(fontSize = 15.sp, lineHeight = 20.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (server.publicPageLive) {
            Spacer(Modifier.width(HomeServerPublicPageGlobeGap))
            SlockPublicPageGlobe(live = true, size = HomeServerPublicPageGlobeSize, color = SlockColor.TextBlackArgb)
        }
        Spacer(Modifier.width(HomeServerIdentityNameChevronGap))
        SlockLucideIcon(
            SlockLucideIconName.ChevronDown,
            Modifier.size(HomeServerIdentityChevronSize),
            color = SlockColor.TextBlackArgb
        )
    }
}

@Composable
private fun SlockLogoBadge(
    text: String,
    modifier: Modifier = Modifier,
    showDropdown: Boolean = false,
    tilted: Boolean = true,
    unread: Boolean = false,
    publicPageLive: Boolean = false,
    textStyle: TextStyle = SlockType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 24.sp)
) {
    if (tilted) {
        SlockTiltedLogoBadge(
            text = text,
            modifier = modifier,
            showDropdown = showDropdown,
            unread = unread,
            publicPageLive = publicPageLive,
            textStyle = textStyle
        )
        return
    }

    Box(modifier = modifier.padding(end = 2.dp, bottom = 2.dp)) {
        Box(Modifier.matchParentSize().offset(x = 2.dp, y = 2.dp).background(SlockColor.ShadowBlack))
        com.tencent.kuikly.compose.foundation.layout.Row(
            modifier = Modifier.background(SlockColor.TextBlack).border(SlockStroke.Default, SlockColor.BorderBlack).padding(horizontal = 16.dp, vertical = SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text, color = SlockColor.BrandYellow, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
            if (showDropdown) {
                Spacer(Modifier.width(SlockSpacing.Xs))
                SlockLucideIcon(SlockLucideIconName.ChevronDown, Modifier.size(18.dp), color = SlockColor.BrandYellowArgb)
            }
        }
    }
}

@Composable
private fun SlockTiltedLogoBadge(
    text: String,
    modifier: Modifier = Modifier,
    showDropdown: Boolean = false,
    unread: Boolean = false,
    publicPageLive: Boolean = false,
    textStyle: TextStyle = SlockType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 24.sp)
) {
    key(text, showDropdown, publicPageLive) {
        SlockTiltedBadgeSurface(modifier = modifier) {
            com.tencent.kuikly.compose.foundation.layout.Row(
                modifier = Modifier.padding(
                    horizontal = HomeServerBadgeHorizontalInset,
                    vertical = HomeServerBadgeVerticalInset
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text, color = SlockColor.BrandYellow, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                if (publicPageLive) {
                    Spacer(Modifier.width(HomeServerBadgeContentGap))
                    SlockPublicPageGlobe(live = true, size = HomeServerPublicPageGlobeSize, color = SlockColor.BrandYellowArgb)
                }
                if (showDropdown) {
                    Spacer(Modifier.width(HomeServerBadgeContentGap))
                    SlockLucideIcon(
                        SlockLucideIconName.ChevronDown,
                        Modifier.size(HomeServerBadgeChevronSize),
                        color = SlockColor.BrandYellowArgb
                    )
                }
            }
            if (unread) ServerUnreadMarker(modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
internal fun SlockTiltedBadgeSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    // Draw the chrome as final vector geometry. Only the foreground is
    // transformed, so chrome and text are never resampled as one bitmap.
    Box(modifier = modifier) {
        TiltedBadgeChrome(modifier = Modifier.matchParentSize())
        Box(modifier = Modifier.padding(end = HomeSmallShadowOffset, bottom = HomeSmallShadowOffset)) {
            Box(modifier = Modifier.rotate(HomeServerBadgeTiltDegrees)) {
                content()
            }
        }
    }
}

@Composable
private fun TiltedBadgeChrome(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val layers = homeServerBadgeChromeLayers(
            widthPx = size.width,
            heightPx = size.height,
            shadowOffsetPx = HomeSmallShadowOffset.toPx()
        )
        drawPath(path = homeServerBadgePath(layers.shadowCorners), color = SlockColor.ShadowBlack)
        drawPath(path = homeServerBadgePath(layers.mainCorners), color = SlockColor.TextBlack)
    }
}

private fun homeServerBadgePath(corners: List<Offset>): Path =
    Path().apply {
        moveTo(corners[0].x, corners[0].y)
        lineTo(corners[1].x, corners[1].y)
        lineTo(corners[2].x, corners[2].y)
        lineTo(corners[3].x, corners[3].y)
        close()
    }

internal data class HomeServerBadgeChromeLayers(
    val mainCorners: List<Offset>,
    val shadowCorners: List<Offset>
)

internal fun homeServerBadgeChromeLayers(
    widthPx: Float,
    heightPx: Float,
    shadowOffsetPx: Float
): HomeServerBadgeChromeLayers {
    val safeOffset = shadowOffsetPx.coerceAtLeast(0f)
    val chromeWidth = (widthPx - safeOffset).coerceAtLeast(1f)
    val chromeHeight = (heightPx - safeOffset).coerceAtLeast(1f)
    val mainCorners = homeServerBadgeTiltCorners(chromeWidth, chromeHeight)
    return HomeServerBadgeChromeLayers(
        mainCorners = mainCorners,
        shadowCorners = mainCorners.map { corner ->
            Offset(x = corner.x + safeOffset, y = corner.y + safeOffset)
        }
    )
}

internal fun homeServerBadgeTiltCorners(
    widthPx: Float,
    heightPx: Float,
    tiltDegrees: Float = HomeServerBadgeTiltDegrees
): List<Offset> {
    val safeWidth = widthPx.coerceAtLeast(1f)
    val safeHeight = heightPx.coerceAtLeast(1f)
    val angle = (tiltDegrees * PI / 180.0).toFloat()
    val cosAngle = cos(angle)
    val sinAngle = sin(angle)
    val sinMagnitude = abs(sinAngle)
    val denominator = (cosAngle * cosAngle - sinMagnitude * sinMagnitude).coerceAtLeast(0.01f)
    val rectWidth = ((safeWidth * cosAngle - safeHeight * sinMagnitude) / denominator).coerceAtLeast(1f)
    val rectHeight = ((safeHeight * cosAngle - safeWidth * sinMagnitude) / denominator).coerceAtLeast(1f)
    val centerX = safeWidth / 2f
    val centerY = safeHeight / 2f
    val halfWidth = rectWidth / 2f
    val halfHeight = rectHeight / 2f
    return listOf(
        rotateHomeBadgePoint(-halfWidth, -halfHeight, cosAngle, sinAngle, centerX, centerY),
        rotateHomeBadgePoint(halfWidth, -halfHeight, cosAngle, sinAngle, centerX, centerY),
        rotateHomeBadgePoint(halfWidth, halfHeight, cosAngle, sinAngle, centerX, centerY),
        rotateHomeBadgePoint(-halfWidth, halfHeight, cosAngle, sinAngle, centerX, centerY)
    )
}

private fun rotateHomeBadgePoint(
    x: Float,
    y: Float,
    cosAngle: Float,
    sinAngle: Float,
    centerX: Float,
    centerY: Float
): Offset =
    Offset(
        x = centerX + x * cosAngle - y * sinAngle,
        y = centerY + x * sinAngle + y * cosAngle
    )

@Composable
private fun ServerUnreadMarker(modifier: Modifier = Modifier) {
    SlockStatusIndicator(
        color = SlockColor.AccentPink,
        size = SlockStatusIndicatorTokens.ServerUnreadSize,
        modifier = modifier.offset(x = 4.dp, y = (-4).dp)
    )
}

@Composable
private fun HomeNotificationCenter(
    notifications: List<SlockHomeNotificationItem>,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Web parity (task #692 / PR #5605): the Bell trigger is permanent and
    // neutral. Availability (trigger exists), disclosure (popup open) and
    // attention (unread notifications) are independent states: only attention
    // gets the pink dot; only disclosure gets the selected button treatment.
    // The trigger never carries warning/error severity colors.
    val visibleNotifications = remember(notifications) { notifications.visibleHomeNotifications() }
    val hasUnread = visibleNotifications.isNotEmpty()
    Box(modifier = modifier.size(HomeNotificationTriggerSlotSize)) {
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(end = HomeSmallShadowOffset, bottom = HomeSmallShadowOffset)) {
            if (open) {
                Box(
                    modifier = Modifier
                        .size(HomeNotificationTriggerSize)
                        .offset(x = HomeSmallShadowOffset, y = HomeSmallShadowOffset)
                        .background(SlockColor.ShadowBlack)
                )
            }
            Button(
                onClick = onToggle,
                modifier =
                    Modifier
                        .size(HomeNotificationTriggerSize)
                        .background(if (open) SlockColor.SurfaceWhite else Color.Transparent)
                        .border(SlockStroke.Default, if (open) SlockColor.BorderBlack else Color.Transparent)
            ) {
                // Web parity (task #113 / artin 2026-08-06): the pink dot sits
                // directly at the Bell icon's top-right corner (web: dot on the
                // icon wrapper span, `-top-1 -end-1`), not at the button slot
                // corner. Icon-sized wrapper keeps the same geometry.
                Box(modifier = Modifier.size(16.dp)) {
                    SlockLucideIcon(SlockLucideIconName.Bell, Modifier.size(16.dp))
                    if (hasUnread) {
                        ServerUnreadMarker(Modifier.align(Alignment.TopEnd))
                    }
                }
            }
        }
    }
}

private val HomeNotificationTriggerSize = 32.dp
private val HomeNotificationTriggerSlotSize = HomeNotificationTriggerSize + HomeSmallShadowOffset
private val HomeNotificationPopoverTopGap = 4.dp
internal fun homeNotificationPopoverTopOffset(topSafeArea: Dp): Dp =
    topSafeArea + SlockSize.PageHeaderHeight + HomeNotificationPopoverTopGap

internal fun shouldDismissHomeNotificationCenterForTabSelection(
    expanded: Boolean,
    selectedTabKey: String,
    targetTabKey: String
): Boolean =
    expanded && selectedTabKey != targetTabKey

internal fun List<SlockHomeNotificationItem>.visibleHomeNotifications(): List<SlockHomeNotificationItem> =
    filter { notification ->
        notification.title.isNotBlank() || notification.body.isNotBlank()
    }

internal fun List<SlockHomeNotificationItem>.homeAppUpdateBannerNotification(): SlockHomeNotificationItem? =
    visibleHomeNotifications().firstOrNull { it.id == APP_UPDATE_HOME_NOTIFICATION_ID }

internal fun List<SlockHomeNotificationItem>.nonAppUpdateHomeNotifications(): List<SlockHomeNotificationItem> =
    visibleHomeNotifications().filterNot { it.id == APP_UPDATE_HOME_NOTIFICATION_ID }

@Composable
private fun HomeAppUpdateBanner(
    notification: SlockHomeNotificationItem,
    onClick: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceCream)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SlockLucideIcon(
            SlockLucideIconName.CircleArrowUp,
            modifier = Modifier.size(16.dp),
            color = "#FF000000"
        )
        Text(
            text = notification.title.ifBlank { notification.body },
            modifier = Modifier.weight(1f),
            style = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 15.sp),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(MR.strings.app_update_view),
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 14.sp),
            color = SlockColor.TextBlack,
            maxLines = 1
        )
        SlockLucideIcon(SlockLucideIconName.ChevronRight, modifier = Modifier.size(16.dp), color = "#FF000000")
    }
    Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
}
@Composable
private fun HomeNotificationPopover(notifications: List<SlockHomeNotificationItem>, onView: (SlockHomeNotificationItem) -> Unit, onDismiss: (SlockHomeNotificationItem) -> Unit, modifier: Modifier = Modifier) {
    HardShadowPanel(modifier = modifier, minWidth = 320.dp, maxWidth = 320.dp) {
        com.tencent.kuikly.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceCream).padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(MR.strings.home_notification_center_title), style = SlockType.SectionLabel, color = SlockColor.TextMutedGray, maxLines = 1)
            Text(
                if (notifications.isEmpty()) {
                    stringResource(MR.strings.home_notification_center_item_count_all_clear)
                } else if (notifications.size == 1) {
                    MR.strings.home_notification_center_item_count_one.format(listOf(notifications.size)).localized()
                } else {
                    MR.strings.home_notification_center_item_count_many.format(listOf(notifications.size)).localized()
                },
                style = SlockType.Button,
                color = SlockColor.TextMutedGray,
                maxLines = 1
            )
        }
        Box(Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
        if (notifications.isEmpty()) {
            HomeNotificationCenterEmptyState()
        } else {
            notifications.forEachIndexed { index, notification ->
                if (index > 0) {
                    Box(Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
                }
                HomeNotificationRow(notification, { onView(notification) }, { onDismiss(notification) })
            }
        }
    }
}

@Composable
private fun HomeNotificationCenterEmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        SlockLucideIcon(
            SlockLucideIconName.CircleCheck,
            modifier = Modifier.size(36.dp),
            color = SlockColor.TextMutedGrayArgb
        )
        Text(
            text = stringResource(MR.strings.home_notification_center_empty_title),
            style = SlockType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp),
            color = SlockColor.TextMutedGray,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(MR.strings.home_notification_center_empty_body),
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HomeNotificationRow(notification: SlockHomeNotificationItem, onView: () -> Unit, onDismiss: () -> Unit) {
    val title = notification.title.ifBlank { notification.body }
    val body = if (notification.title.isBlank()) "" else notification.body
    com.tencent.kuikly.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth().padding(SlockSpacing.Md), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
        Box(modifier = Modifier.size(24.dp).background(notification.kind.notificationColor()).border(SlockStroke.Default, SlockColor.BorderBlack), contentAlignment = Alignment.Center) {
            SlockLucideIcon(notification.icon.notificationIconName(notification.kind), Modifier.size(16.dp))
        }
        Column(modifier = Modifier.width(HomeNotificationTextWidth), verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
            Text(title, style = SlockType.Button, color = SlockColor.TextBlack, overflow = TextOverflow.Ellipsis, maxLines = 3)
            if (body.isNotBlank()) {
                Text(body, style = SlockType.Button, color = SlockColor.TextBlack, overflow = TextOverflow.Ellipsis, maxLines = 3)
            }
            com.tencent.kuikly.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
                if (notification.actionLabel.isNotBlank()) {
                    HomeNotificationActionButton(
                        notification.actionLabel,
                        SlockColor.AccentPink,
                        onView,
                        shadowOffset = notification.primaryActionShadowOffset()
                    )
                }
                HomeNotificationActionButton(stringResource(MR.strings.common_dismiss), SlockColor.SurfaceWhite, onDismiss)
            }
        }
    }
}

private val HomeNotificationTextWidth = 260.dp
private val HomeNotificationActionShadowOffset = 2.dp
private val HomeNotificationActionActiveShadowOffset = HomeNotificationActionShadowOffset * 2

internal fun SlockHomeNotificationIcon.notificationIconName(kind: SlockHomeNotificationKind): SlockLucideIconName =
    when (this) {
        SlockHomeNotificationIcon.WifiOff -> SlockLucideIconName.TriangleAlert
        SlockHomeNotificationIcon.CircleArrowUp -> SlockLucideIconName.CircleArrowUp
        SlockHomeNotificationIcon.Info -> SlockLucideIconName.Info
        SlockHomeNotificationIcon.Default ->
            when (kind) {
                SlockHomeNotificationKind.Warning -> SlockLucideIconName.TriangleAlert
                SlockHomeNotificationKind.Info -> SlockLucideIconName.Info
                SlockHomeNotificationKind.Error -> SlockLucideIconName.TriangleAlert
            }
    }

internal fun SlockHomeNotificationItem.primaryActionShadowOffset(): Dp =
    if (isAppUpdateActionActive()) {
        HomeNotificationActionActiveShadowOffset
    } else {
        HomeNotificationActionShadowOffset
    }

private fun SlockHomeNotificationItem.isAppUpdateActionActive(): Boolean =
    id == APP_UPDATE_HOME_NOTIFICATION_ID &&
        (primaryActionActive || actionLabel.trim().lowercase() in setOf("downloading", "install"))

@Composable
private fun HomeNotificationActionButton(text: String, color: Color, onClick: () -> Unit, shadowOffset: Dp = HomeNotificationActionShadowOffset) {
    SlockBrutalActionButton(
        label = text,
        onClick = onClick,
        backgroundColor = color,
        minWidth = 72.dp,
        minHeight = 32.dp,
        shadowOffset = shadowOffset,
        textStyle = HomeNotificationActionButtonTextStyle
    )
}

internal val HomeNotificationActionButtonTextStyle =
    SlockType.Caption.withSlockFontWeight(FontWeight.Bold)

private fun SlockHomeNotificationKind.notificationColor(): Color = when (this) {
    SlockHomeNotificationKind.Warning -> SlockColor.AccentOrange
    SlockHomeNotificationKind.Info -> SlockColor.BrandYellow
    SlockHomeNotificationKind.Error -> SlockColor.AccentRed
}

@Composable
internal fun HomeInlineErrorCard(
    error: SlockHomeInlineError,
    modifier: Modifier = Modifier,
    showAction: Boolean = true,
    onAction: () -> Unit
) {
    val visibleCopy = error.toVisibleCopy()
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(SlockSpacing.Md)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                verticalAlignment = Alignment.Top
            ) {
                SlockLucideIcon(
                    SlockLucideIconName.TriangleAlert,
                    modifier = Modifier.size(18.dp),
                    color = SlockColor.TextBlackArgb
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = visibleCopy.title,
                        style = SlockType.ButtonBold,
                        color = SlockColor.TextBlack,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = visibleCopy.detail,
                        style = SlockType.Caption,
                        color = SlockColor.TextMutedGray,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (showAction) {
                SlockBrutalActionButton(
                    label = visibleCopy.actionLabel,
                    onClick = onAction,
                    leadingIcon = SlockLucideIconName.RefreshCw,
                    backgroundColor = SlockColor.SurfaceWhite
                )
            }
        }
    }
}

@Composable
private fun HomeServerDrawerPanel(
    sections: List<SlockHomeServerDrawerSection>,
    labels: SlockHomeShellLabels,
    onDismiss: () -> Unit,
    onServerClick: (String) -> Unit,
    onSectionToggle: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCreateServerClick: () -> Unit
) {
    val rows = remember(sections) { homeServerDrawerRows(sections) }
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { HomeServerDrawerSwipeDismissThreshold.toPx() }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(SlockColor.TextBlack.copy(alpha = 0.28f)))
        val drawerWidth = homeServerDrawerWidth(maxWidth = maxWidth, maxHeight = maxHeight)
        var horizontalDragPx by remember { mutableStateOf(0f) }
        Column(
            modifier =
                Modifier
                    .width(drawerWidth)
                    .height(maxHeight)
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .clickable(onClick = {})
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
                        .background(SlockColor.BrandYellow)
                        .border(SlockStroke.Default, SlockColor.BorderBlack)
                        .padding(horizontal = SlockSpacing.Md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                Text(
                    text = stringResource(MR.strings.home_section_servers),
                    style = SlockType.ButtonBold,
                    color = SlockColor.TextBlack,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                Box(
                    modifier = Modifier.size(36.dp).clickable(onClick = onCreateServerClick),
                    contentAlignment = Alignment.Center
                ) {
                    SlockLucideIcon(SlockLucideIconName.Plus, Modifier.size(18.dp))
                }
            }
            if (rows.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(labels.noServersAvailable, style = SlockType.Caption, color = SlockColor.TextMutedGray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    beyondBoundsItemCount = sharedListBeyondBoundsItemCount(rows.size)
                ) {
                    items(rows.size, key = { index -> rows[index].key }) { index ->
                        when (val row = rows[index]) {
                            is SlockHomeServerDrawerRow.ServerHeader ->
                                HomeServerDrawerServerHeader(
                                    section = row.section,
                                    hasCachedConversations =
                                        row.section.channels.isNotEmpty() || row.section.directMessages.isNotEmpty(),
                                    onServerClick = onServerClick,
                                    onSectionToggle = onSectionToggle,
                                )
                            is SlockHomeServerDrawerRow.SectionHeader ->
                                HomeServerDrawerSectionHeader(row.section, row.count, labels)
                            is SlockHomeServerDrawerRow.Conversation ->
                                HomeServerDrawerConversationRow(row)
                            is SlockHomeServerDrawerRow.Skeleton ->
                                HomeSidebarSkeletonRow(
                                    modifier =
                                        Modifier.padding(
                                            horizontal = HomeServerDrawerConversationHorizontalPadding,
                                            vertical = SlockSpacing.Xs
                                        )
                                )
                            is SlockHomeServerDrawerRow.Error ->
                                HomeServerDrawerErrorRow(
                                    row = row,
                                    onRetry = { onRetry(row.serverId) }
                                )
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
            ServerSwitcherActionRow(labels.switchOrCreateServer, onCreateServerClick)
        }
    }
}

@Composable
internal fun HomeServerDrawerHostedPanel(
    sections: List<SlockHomeServerDrawerHostedSection>,
    labels: SlockHomeShellLabels,
    userHeader: SlockServerDrawerUserHeader,
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

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceWhite)
                .semantics { isTraversalGroup = true }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(homeShellHeaderHeight(topSafeArea))
                        .background(SlockColor.BrandYellow)
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(top = topSafeArea)
                            .padding(horizontal = SlockSpacing.Md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
                ) {
                    SlockAvatar(
                        avatarWireValue = userHeader.avatarWireValue,
                        label = userHeader.avatarLabel,
                        avatarSize = 32.dp,
                        cacheSubject = slockMemberAvatarCacheSubject(MemberKind.Human, userHeader.memberId),
                        cacheServerId = userHeader.serverId
                    )
                    Text(
                        text = userHeader.displayName,
                        style = SlockType.ButtonBold,
                        color = SlockColor.TextBlack,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(SlockStroke.Default)
                        .background(SlockColor.BorderBlack)
                )
            }
            if (sections.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(labels.noServersAvailable, style = SlockType.Caption, color = SlockColor.TextMutedGray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    beyondBoundsItemCount = 2
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
                        if (
                            !hasCache &&
                            section.status in
                                setOf(
                                    HomeServerDrawerLoadStatus.Unloaded,
                                    HomeServerDrawerLoadStatus.Loading
                                )
                        ) {
                            items(3, key = { index -> "skeleton:$serverId:$index" }) {
                                HomeSidebarSkeletonRow(
                                    modifier =
                                        Modifier.padding(
                                            horizontal = HomeServerDrawerConversationHorizontalPadding,
                                            vertical = SlockSpacing.Xs
                                        )
                                )
                            }
                        } else {
                            items(
                                normalized.rows.size,
                                key = { index -> "$serverId:${normalized.rows[index].key}" }
                            ) { index ->
                                when (val row = normalized.rows[index]) {
                                    is HomeServerDrawerNormalizedRow.SectionHeader ->
                                        HomeServerDrawerSectionHeader(
                                            section = row.section.toSlockHomeConversationSection(),
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
                                                slockMemberAvatarResolver(serverMembersStore, serverId)
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
                                                    SlockHomeServerDrawerRow.Conversation(
                                                        serverId = serverId,
                                                        section =
                                                            row.section
                                                                .toSlockHomeConversationSection(),
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
                                        SlockHomeServerDrawerRow.Error(
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
                    .width(SlockStroke.Default)
                    .background(SlockColor.BorderBlack)
        )
    }
}

private fun HomeServerDrawerConversationSection.toSlockHomeConversationSection(): SlockHomeConversationSection =
    when (this) {
        HomeServerDrawerConversationSection.Pinned -> SlockHomeConversationSection.Pinned
        HomeServerDrawerConversationSection.Channel -> SlockHomeConversationSection.Channel
        HomeServerDrawerConversationSection.JointChannel -> SlockHomeConversationSection.JointChannel
        HomeServerDrawerConversationSection.DirectMessage -> SlockHomeConversationSection.DirectMessage
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
    section: SlockHomeServerDrawerSection,
    hasCachedConversations: Boolean,
    onServerClick: (String) -> Unit,
    onSectionToggle: (String) -> Unit,
) {
    val busy = homeServerDrawerHeaderSpinning(section.status)
    val failed = section.status == HomeServerDrawerLoadStatus.ErrorEmpty || section.status == HomeServerDrawerLoadStatus.ErrorWithCache
    val unavailable = section.status == HomeServerDrawerLoadStatus.Unavailable
    val interactive = !unavailable || hasCachedConversations
    val unavailableDescription = stringResource(MR.strings.settings_state_unavailable)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (section.expanded) SlockColor.SurfaceCream else SlockColor.SurfaceWhite)
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
                Text(section.server.name, style = SlockType.ButtonBold, color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "/${section.server.slug}",
                    style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!unavailable) ServerSwitcherUnreadIndicator(section.server)
            if (busy) {
                SlockLucideIcon(
                    SlockLucideIconName.LoaderCircle,
                    Modifier.size(14.dp),
                    color = SlockColor.TextMutedGrayArgb,
                    spinning = busy,
                )
            } else if (failed) {
                SlockLucideIcon(SlockLucideIconName.BadgeInfo, Modifier.size(14.dp), color = SlockColor.AccentRedArgb)
            } else if (unavailable) {
                SlockLucideIcon(SlockLucideIconName.BadgeInfo, Modifier.size(14.dp), color = SlockColor.TextMutedGrayArgb)
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
                SlockLucideIcon(
                    if (section.expanded) SlockLucideIconName.ChevronUp else SlockLucideIconName.ChevronDown,
                    Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun HomeServerDrawerSectionHeader(
    section: SlockHomeConversationSection,
    count: Int,
    labels: SlockHomeShellLabels
) {
    val label =
        when (section) {
            SlockHomeConversationSection.Pinned -> labels.pinned
            SlockHomeConversationSection.Channel -> labels.channels
            SlockHomeConversationSection.JointChannel -> labels.jointChannels
            SlockHomeConversationSection.DirectMessage -> labels.directMessages
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(HomeServerDrawerSectionHeaderHeight)
                .background(SlockColor.SurfaceCream)
                .padding(horizontal = HomeServerDrawerConversationHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label.slockDisplayUppercase(), style = SlockType.Caption, color = SlockColor.TextMutedGray, modifier = Modifier.weight(1f), maxLines = 1)
        Text(count.toString(), style = SlockType.Caption, color = SlockColor.TextMutedGray, maxLines = 1)
    }
}

@Composable
private fun HomeServerDrawerConversationRow(
    row: SlockHomeServerDrawerRow.Conversation,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    enabled: Boolean = false,
    onClick: () -> Unit = {}
) {
    val item = row.item
    val badgeProjection = item.homeConversationBadgeProjection()
    if (item.isDirectMessageConversation()) {
        DirectMessageRow(
            interactionIdentity = "drawer:${row.serverId}:${row.section.name}:${item.id}",
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
            badge = badgeProjection.label,
            badgeTone = badgeProjection.tone,
            interactionState = ChannelRowInteractionState.Default,
            onClick = onClick.takeIf { enabled },
            onLongPress = null,
            onPress = null,
            onPressEnd = {}
        )
    } else {
        ChannelRow(
            content =
                ChannelRowContent(
                    name = item.name,
                    symbol = item.homeChannelSymbol(),
                    joined = item.joined,
                    hasDraft = item.hasDraft,
                    muted = item.activityMuted,
                    bridgeProvider = item.bridgeProvider,
                    badge = badgeProjection.label,
                    badgeTone = badgeProjection.tone
                ),
            modifier = Modifier.padding(horizontal = HomeServerDrawerConversationHorizontalPadding),
            interaction =
                ChannelRowInteraction(
                    identity = "drawer:${row.serverId}:${row.section.name}:${item.id}",
                    state = ChannelRowInteractionState.Default,
                    onClick = onClick.takeIf { enabled },
                    onLongPress = null,
                    onPress = null,
                    onPressEnd = {}
                )
        )
    }
}

@Composable
private fun HomeServerDrawerErrorRow(
    row: SlockHomeServerDrawerRow.Error,
    onRetry: () -> Unit
) {
    val retryLabel = row.visibleRetryLabel(stringResource(MR.strings.home_action_retry))
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (row.hasCache) SlockColor.SurfaceCream else SlockColor.SurfaceWhite)
                .clickable(onClick = onRetry)
                .padding(horizontal = HomeServerDrawerConversationHorizontalPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        SlockLucideIcon(SlockLucideIconName.RefreshCw, Modifier.size(14.dp), color = SlockColor.AccentRedArgb)
        Text(
            text = retryLabel,
            style = SlockType.Caption,
            color = SlockColor.AccentRed,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun ServerSwitcherPanel(
    servers: List<SlockServerSwitcherItem>,
    currentServerId: String,
    pendingServerId: String?,
    prefersChineseCommunity: Boolean,
    labels: SlockHomeShellLabels,
    onServerSelected: (String) -> Unit,
    onServerOrderChange: (List<String>) -> Unit,
    onCreateServerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val joinCommunityActions =
        homeServerSwitcherJoinCommunityActions(
            servers = servers,
            labels = labels,
            includeChineseCommunity = prefersChineseCommunity
        )
    val incomingOrder = servers.map { it.id }
    var order by remember { mutableStateOf(incomingOrder) }
    var pendingIncomingOrder by remember { mutableStateOf<List<String>?>(null) }
    val serversById = remember(servers) { servers.associateBy { it.id } }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val rowStepPx = with(density) { HomeServerSwitcherRowMinHeight.toPx().coerceAtLeast(1f) }
    val edgeScrollZonePx = with(density) { SlockSortEdgeScrollZone.toPx() }
    val edgeScrollStepPx = with(density) { SlockSortEdgeScrollStep.toPx() }
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

    fun visibleServerRowBounds(): List<SlockSortRowBounds> =
        listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            val key = info.key as? String ?: return@mapNotNull null
            val id = key.removePrefix("server-").takeIf { key.startsWith("server-") } ?: return@mapNotNull null
            val index = order.indexOf(id).takeIf { it >= 0 } ?: return@mapNotNull null
            SlockSortRowBounds(index = index, top = info.offset.toFloat(), size = info.size.toFloat())
        }

    fun recomputeDrag() {
        if (draggedId == null) return
        targetIndex = resolveSortTargetIndex(pointerViewportY, visibleServerRowBounds(), order.lastIndex)
    }

    LaunchedEffect(draggedId) {
        if (draggedId == null) return@LaunchedEffect
        runSlockSortAutoScrollLoop(
            listState = listState,
            isDragging = { draggedId != null },
            pointerViewportY = { pointerViewportY },
            edgeScrollZonePx = edgeScrollZonePx,
            edgeScrollStepPx = edgeScrollStepPx
        ) { delta, consumed ->
            recomputeDrag()
            slockLogInfo(
                "SlockServerSwitcher",
                "autoscroll delta=$delta consumed=$consumed pointerY=$pointerViewportY " +
                    "target=$targetIndex firstVisible=${listState.firstVisibleItemIndex}"
            )
        }
    }

    HardShadowPanel(modifier = modifier, minWidth = 276.dp, maxWidth = 328.dp) {
        if (servers.isEmpty()) {
            Text(labels.noServersAvailable, style = SlockType.Caption, color = SlockColor.TextMutedGray, modifier = Modifier.padding(SlockSpacing.Md))
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
            Box(modifier = serverListModifier.clipToBounds()) {
                LazyColumn(
                    state = listState,
                    modifier = serverLazyListModifier,
                    beyondBoundsItemCount = homeServerSwitcherBeyondBoundsItemCount(order.size),
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
                                server.id == pendingServerId,
                                dragHandleVisible = order.size > 1,
                                onClick = if (draggedId == null) ({ onServerSelected(server.id) }) else null
                            )
                        }
                    }
                }
                if (order.size > 1) {
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
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { pos ->
                                                val target = resolveSortTargetIndex(pos.y, visibleServerRowBounds(), order.lastIndex)
                                                val id = order.getOrNull(target)
                                                if (id != null) {
                                                    draggedId = id
                                                    draggedStartIndex = target
                                                    pointerViewportY = pos.y
                                                    targetIndex = target
                                                    slockLogInfo("SlockServerSwitcher", "dragStart id=$id index=$target size=${order.size}")
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
                                                slockLogInfo("SlockServerSwitcher", "dragEnd changed=$changed from=$draggedStartIndex to=$targetIndex")
                                                draggedId = null
                                                targetIndex = -1
                                            },
                                            onDragCancel = {
                                                slockLogInfo("SlockServerSwitcher", "dragCancel id=$draggedId")
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
                                    .background(SlockColor.AccentPink)
                        )
                    }
                    serversById[active]?.let { server ->
                        ServerSwitcherRow(
                            server,
                            server.id == currentServerId,
                            server.id == pendingServerId,
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
        Box(Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
        joinCommunityActions.forEach { action ->
            ServerSwitcherActionRow(action.label, onCreateServerClick)
        }
        ServerSwitcherActionRow(labels.switchOrCreateServer, onCreateServerClick)
    }
}

@Composable
private fun HardShadowPanel(
    modifier: Modifier = Modifier,
    minWidth: Dp,
    maxWidth: Dp,
    content: @Composable com.tencent.kuikly.compose.foundation.layout.ColumnScope.() -> Unit
) {
    SlockBrutalPanel(
        modifier = modifier,
        minWidth = minWidth,
        maxWidth = maxWidth,
        shadowOffset = HomePanelShadowOffset,
        content = content
    )
}

@Composable
private fun ServerSwitcherRow(
    server: SlockServerSwitcherItem,
    selected: Boolean,
    pending: Boolean,
    dragHandleVisible: Boolean = false,
    pressed: Boolean = false,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val clickModifier = onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier
    com.tencent.kuikly.compose.foundation.layout.Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = HomeServerSwitcherRowMinHeight)
                .background(
                    when {
                        selected || pressed -> SlockColor.BrandYellow
                        else -> SlockColor.SurfaceWhite
                    }
                )
                .then(clickModifier)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(modifier = Modifier.width(14.dp), contentAlignment = Alignment.Center) {
            when {
                pending -> Text("...", style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(lineHeight = 14.sp), color = SlockColor.TextBlack, maxLines = 1, textAlign = TextAlign.Center)
                selected -> SlockLucideIcon(SlockLucideIconName.Check, Modifier.size(14.dp))
                else -> Spacer(Modifier.size(14.dp))
            }
        }
        ServerSwitcherAvatarTile(server)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    server.name,
                    style = SlockType.Button,
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (server.publicPageLive) {
                    Spacer(Modifier.width(HomeServerPublicPageGlobeGap))
                    SlockPublicPageGlobe(live = true, size = HomeServerPublicPageGlobeSize)
                }
            }
            Text(
                "/${server.slug}",
                style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
                color = SlockColor.TextBlack.copy(alpha = 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        ServerSwitcherUnreadIndicator(server)
        if (dragHandleVisible) {
            Box(modifier = Modifier.width(HomeServerSwitcherDragHandleWidth), contentAlignment = Alignment.Center) {
                SlockLucideIcon(
                    HomeServerSwitcherDragHandleIconName,
                    Modifier.size(14.dp),
                    color = SlockColor.TextMutedGrayArgb
                )
            }
        }
    }
}

@Composable
private fun ServerSwitcherUnreadIndicator(server: SlockServerSwitcherItem) {
    val presentation = homeServerSwitcherBadgePresentation(server)
    when (presentation.kind) {
        HomeServerSwitcherBadgeKind.ActivityNumber,
        HomeServerSwitcherBadgeKind.BroaderUnreadNumber ->
            presentation.label?.let { HomeUnreadBadge(it) }
        HomeServerSwitcherBadgeKind.None -> Unit
    }
}

@Composable
internal fun ServerSwitcherAvatarTile(server: SlockServerSwitcherItem) {
    val presentation = server.toServerSwitcherAvatarPresentation()
    Box(
        modifier =
            Modifier
                .size(32.dp)
                .background(SlockColor.TextBlack)
                .border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        if (presentation.avatarWireValue == null) {
            ServerAvatarFallbackLabel(presentation.fallbackLabel)
        }
        presentation.avatarWireValue?.let { source ->
            if (source.toPixelAvatarSpec() != null) {
                // Visual-fixture pixel avatars use the same renderer as the compact header so
                // the connected comparison can prove one identity projection at both levels.
                SlockAvatar(
                    avatarWireValue = source,
                    label = presentation.fallbackLabel,
                    modifier = Modifier.fillMaxSize(),
                    avatarSize = 32.dp,
                    borderWidth = 0.dp,
                    cacheSubject = SlockAvatarCacheSubject.Server(server.id),
                    cacheServerId = server.id
                )
            } else {
                // The invisible prewarm surface and visible tile share the typed mounted cache;
                // the mounted session coalesces an in-flight fetch for this server identity.
                SlockAvatarHttpImage(
                    source = source,
                    modifier = Modifier.fillMaxSize(),
                    cacheSubject = SlockAvatarCacheSubject.Server(server.id),
                    cacheServerId = server.id,
                    serverDiagnosticsId = server.id,
                    fallback = { ServerAvatarFallbackLabel(presentation.fallbackLabel) },
                    placeholder = { ServerAvatarFallbackLabel(presentation.fallbackLabel) }
                )
            }
        }
    }
}

@Composable
private fun ServerAvatarFallbackLabel(label: String) {
    Text(
        label,
        style =
            SlockType.Button.copy(
                fontFamily = slockMonoFamilyForWeight(FontWeight.Bold),
                fontSize = 17.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Normal
            ),
        color = SlockColor.BrandYellow,
        maxLines = 1,
        textAlign = TextAlign.Center
    )
}

internal data class ServerSwitcherAvatarPresentation(
    val avatarWireValue: String?,
    val fallbackLabel: String
)

internal fun SlockServerSwitcherItem.toServerSwitcherAvatarPresentation(): ServerSwitcherAvatarPresentation =
    ServerSwitcherAvatarPresentation(
        avatarWireValue = avatarUrl?.takeIf { it.isNotBlank() },
        fallbackLabel = name.take(1).uppercase()
    )

internal fun homeHeaderServerIdentity(
    currentServerId: String,
    currentServerName: String,
    servers: List<SlockServerSwitcherItem>
): SlockServerSwitcherItem {
    val matched = servers.firstOrNull { server -> server.id == currentServerId }
    return matched?.copy(name = matched.name.ifBlank { currentServerName })
        ?: SlockServerSwitcherItem(
            id = currentServerId,
            name = currentServerName,
            slug = currentServerId
        )
}

@Composable
private fun ServerSwitcherAvatarPrewarmSurface(
    servers: List<SlockServerSwitcherItem>
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
                SlockAvatarHttpImage(
                    source = source,
                    modifier = Modifier.fillMaxSize(),
                    cacheSubject = SlockAvatarCacheSubject.Server(server.id),
                    cacheServerId = server.id,
                    serverDiagnosticsId = server.id,
                    diagnosticsRole = "prewarm"
                )
            }
        }
    }
}

@Composable
private fun ServerSwitcherActionRow(label: String, onClick: () -> Unit) {
    com.tencent.kuikly.compose.foundation.layout.Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 42.dp)
                .background(SlockColor.SurfaceWhite)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        SlockLucideIcon(SlockLucideIconName.Plus, Modifier.size(14.dp))
        Text(label, style = SlockType.ButtonBold, color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SidebarActivityRow(
    label: String,
    unreadCount: String?,
    totalCount: String?,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    SidebarActionRow(
        icon = SlockLucideIconName.Activity,
        label = label,
        selected = selected,
        modifier = modifier,
        badge = unreadCount,
        trailingText = totalCount,
        onClick = onClick
    )
}

/**
 * Visual fixture: every sidebar row type, selected and default, in the sidebar's
 * 2dp-gap list. Rows never reserve space for the selection shadow (it draws into
 * the gap, like web box-shadow), so selecting a row cannot change its size.
 */
@Composable
internal fun HomeSidebarRowStatesVisualContent() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceCream)
                .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val rowModifier = Modifier.padding(horizontal = HomeListHorizontalPadding)
        SidebarActionRow(SlockLucideIconName.Search, "Search", modifier = rowModifier, selected = true)
        SidebarActionRow(SlockLucideIconName.Bookmark, "Saved", modifier = rowModifier)
        ChannelRow(
            content = ChannelRowContent(name = "general"),
            modifier = rowModifier,
            interaction = ChannelRowInteraction(identity = "visual-general", state = ChannelRowInteractionState.Selected)
        )
        ChannelRow(content = ChannelRowContent(name = "design"), modifier = rowModifier)
        DirectMessageRow(
            name = "artin",
            avatarLabel = "AR",
            modifier = rowModifier,
            interactionState = ChannelRowInteractionState.Selected
        )
        DirectMessageRow(name = "Cindy", avatarLabel = "CI", modifier = rowModifier)
    }
}

@Composable
private fun SidebarActionRow(icon: SlockLucideIconName, label: String, modifier: Modifier = Modifier, selected: Boolean = false, badge: String? = null, trailingText: String? = null, onClick: (() -> Unit)? = null) {
    val gestureIdentity = "$icon:$label"
    val hoverInteractionSource = remember(gestureIdentity) { MutableInteractionSource() }
    val isHovered by hoverInteractionSource.collectIsHoveredAsState()
    var pressed by remember(gestureIdentity) { mutableStateOf(false) }
    val effectiveInteractionState = when {
        selected -> ChannelRowInteractionState.Selected
        pressed -> ChannelRowInteractionState.Pressed
        isHovered -> ChannelRowInteractionState.Hovered
        else -> ChannelRowInteractionState.Default
    }
    val rowBackground = when (effectiveInteractionState) {
        ChannelRowInteractionState.Default -> Color.Transparent
        ChannelRowInteractionState.Selected -> SlockColor.AccentPink
        ChannelRowInteractionState.Hovered -> SlockColor.SurfaceWhite
        ChannelRowInteractionState.Focused -> SlockColor.SurfaceWhite
        ChannelRowInteractionState.Pressed -> SlockColor.SurfaceWhite
    }
    val borderColor = when (effectiveInteractionState) {
        ChannelRowInteractionState.Default -> Color.Transparent
        ChannelRowInteractionState.Hovered, ChannelRowInteractionState.Pressed, ChannelRowInteractionState.Selected, ChannelRowInteractionState.Focused -> SlockColor.BorderBlack
    }
    val gestureOwner =
        rememberSlockGestureOwner(
            surface = "home-sidebar-action",
            semanticIdentity = gestureIdentity,
            callbacks =
                HomeSidebarActionGestureCallbacks(
                    onClick = onClick,
                    onPressedChange = { pressed = it }
                )
        )
    val clickModifier =
        if (onClick != null) {
            Modifier.slockGesturePointerInput(gestureOwner) { owner ->
                var gesture: SlockGestureSession<HomeSidebarActionGestureCallbacks>? = null
                detectTapGestures(
                    onPress = {
                        val session = owner.beginGesture().also { gesture = it }
                        session.currentCallbacksOrNull()?.let { callbacks ->
                            callbacks.onPressedChange(true)
                            try {
                                tryAwaitRelease()
                            } finally {
                                session.currentCallbacksOrNull()?.onPressedChange(false)
                            }
                        }
                    },
                    onTap = {
                        (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.onClick?.invoke()
                    }
                )
            }
        } else {
            Modifier
        }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(clickModifier)
            .hoverable(hoverInteractionSource)
    ) {
        if (selected || pressed) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SidebarActionRowHeight)
                    .offset(x = ChannelRowShadowOffset, y = ChannelRowShadowOffset)
                    .background(SlockColor.ShadowBlack)
            )
        }
        com.tencent.kuikly.compose.foundation.layout.Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(SidebarActionRowHeight)
                    .background(rowBackground)
                    .border(SlockStroke.Default, borderColor)
                    .padding(horizontal = SidebarRowHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(SidebarActionIconBoxSize), contentAlignment = Alignment.Center) { SlockLucideIcon(icon, Modifier.size(SidebarActionIconSize)) }
            Text(label, style = SlockType.ListItem, color = SlockColor.TextBlack, modifier = Modifier.padding(start = SidebarActionLabelGap).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (badge != null || trailingText != null) {
                Box(
                    modifier = Modifier.width(SidebarActionTrailingSlotWidth),
                    contentAlignment = Alignment.Center
                ) {
                    if (badge != null) {
                        CountPill(badge, SlockColor.AccentPink)
                    } else if (trailingText != null) {
                        SidebarTrailingNumberText(trailingText, color = SlockColor.TextBlack)
                    }
                }
            }
        }
    }
}

private data class HomeSidebarActionGestureCallbacks(
    val onClick: (() -> Unit)?,
    val onPressedChange: (Boolean) -> Unit
)

internal fun formatHomeSavedCount(savedCount: Int): String? =
    formatHomeSidebarBadgeCount(savedCount)

@Composable
private fun SidebarTrailingNumberText(text: String, color: Color) {
    Box(modifier = Modifier.height(SidebarActionIconBoxSize), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 10.sp, lineHeight = 10.sp),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun Modifier.homeRowAnchorCapture(
    enabled: Boolean,
    anchorRef: SlockLiveRootAnchorRef
): Modifier = this.slockCaptureLiveRootAnchor(anchorRef = anchorRef, enabled = enabled)

private fun homeRowLongPressAnchor(
    rowAnchor: SlockLiveRootAnchorRef,
    pressOffset: Offset
): SlockPopoverAnchor? =
    rowAnchor.resolve(SlockPopoverLocalPoint.BottomAtX(pressOffset.x))

private data class HomeConversationRowGestureCallbacks(
    val onClick: (() -> Unit)?,
    val onLongPress: ((SlockPopoverAnchor) -> Unit)?,
    val onPress: (() -> Unit)?,
    val onPressEnd: (() -> Unit)?,
    val onDragStart: (() -> Unit)? = null,
    val onDragMove: ((Float) -> Unit)? = null,
    val onDragEnd: (() -> Unit)? = null,
    val onDragCancel: (() -> Unit)? = null,
    val onCaptureAnchorChange: (Boolean) -> Unit,
    val rowAnchor: SlockLiveRootAnchorRef
)

@Composable
private fun ChannelRow(
    content: ChannelRowContent,
    modifier: Modifier = Modifier,
    interaction: ChannelRowInteraction =
        ChannelRowInteraction(identity = content.defaultInteractionIdentity()),
    drag: ChannelRowDrag = ChannelRowDrag()
) {
    val name = content.name
    val interactionIdentity = interaction.identity
    val interactionState = interaction.state
    // #211 review (Cindy / @Android-Developer-2 01:27): hover is
    // detected locally so the visual layer can react without the shell
    // having to track per-row hover. Selected still wins: a currently
    // selected row keeps its white brutal-shadow treatment on hover.
    val hoverInteractionSource = remember(interactionIdentity) { MutableInteractionSource() }
    val isHovered by hoverInteractionSource.collectIsHoveredAsState()
    val selected = interactionState == ChannelRowInteractionState.Selected
    val pressed = interactionState == ChannelRowInteractionState.Pressed
    // #211 review (Cindy): when the row is being pressed but not yet
    // selected, show a light overlay so the user sees the gesture was
    // accepted. Pressed wins over the default / hovered / focused
    // background but never overwrites a real Selected highlight.
    val effectiveInteractionState = when {
        selected -> ChannelRowInteractionState.Selected
        pressed -> ChannelRowInteractionState.Pressed
        isHovered -> ChannelRowInteractionState.Hovered
        else -> interactionState
    }
    val rowBackground = when (effectiveInteractionState) {
        ChannelRowInteractionState.Default -> Color.Transparent
        ChannelRowInteractionState.Selected -> SlockColor.AccentPink
        ChannelRowInteractionState.Hovered -> SlockColor.SurfaceWhite
        ChannelRowInteractionState.Focused -> SlockColor.SurfaceWhite
        ChannelRowInteractionState.Pressed -> SlockColor.SurfaceWhite
    }
    val borderColor = when (effectiveInteractionState) {
        ChannelRowInteractionState.Default -> Color.Transparent
        ChannelRowInteractionState.Hovered, ChannelRowInteractionState.Pressed, ChannelRowInteractionState.Selected, ChannelRowInteractionState.Focused -> SlockColor.BorderBlack
    }
    val titleColor = homeConversationTitleColor(muted = content.muted, joined = content.joined)
    val mutedIndicatorIcon = homeConversationMutedIndicatorIcon(content.muted)
    val rowAnchor = remember(interactionIdentity) { SlockLiveRootAnchorRef(identity = interactionIdentity) }
    var captureAnchorForPress by remember(interactionIdentity) { mutableStateOf(false) }
    val hoverableModifier = Modifier.hoverable(hoverInteractionSource)
    val gestureCallbacks =
        HomeConversationRowGestureCallbacks(
            onClick = interaction.onClick,
            onLongPress = interaction.onLongPress,
            onPress = interaction.onPress,
            onPressEnd = interaction.onPressEnd,
            onDragStart = drag.onStart,
            onDragMove = drag.onMove,
            onDragEnd = drag.onEnd,
            onDragCancel = drag.onCancel,
            onCaptureAnchorChange = { captureAnchorForPress = it },
            rowAnchor = rowAnchor
        )
    val rowDragGestureOwner =
        rememberSlockGestureOwner(
            surface = "home-channel-row-drag",
            semanticIdentity = interactionIdentity,
            callbacks = gestureCallbacks
        )
    val rowDragModifier =
        if (drag.rowEnabled && drag.enabled && drag.onStart != null && drag.onMove != null && drag.onEnd != null) {
            Modifier.slockGesturePointerInput(rowDragGestureOwner, drag.rowEnabled, drag.enabled) { owner ->
                var gesture: SlockGestureSession<HomeConversationRowGestureCallbacks>? = null
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        owner.beginGesture().also { gesture = it }.currentCallbacksOrNull()?.onDragStart?.invoke()
                    },
                    onDragEnd = {
                        (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.onDragEnd?.invoke()
                    },
                    onDragCancel = {
                        (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.let { callbacks ->
                            (callbacks.onDragCancel ?: callbacks.onDragEnd)?.invoke()
                        }
                    }
                ) { change, dragAmount ->
                    change.consume()
                    (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.onDragMove?.invoke(dragAmount.y)
                }
            }
        } else {
            Modifier
        }
    val dragHandleGestureOwner =
        rememberSlockGestureOwner(
            surface = "home-channel-row-drag-handle",
            semanticIdentity = interactionIdentity,
            callbacks = gestureCallbacks
        )
    val dragHandleModifier =
        if (drag.enabled && drag.onStart != null && drag.onMove != null && drag.onEnd != null) {
            Modifier.slockGesturePointerInput(dragHandleGestureOwner, drag.enabled) { owner ->
                var gesture: SlockGestureSession<HomeConversationRowGestureCallbacks>? = null
                // Sort mode is explicit, so the handle drags immediately —
                // no long-press gate (task #356: the v2 long-press-drag felt stuck).
                detectDragGestures(
                    onDragStart = {
                        owner.beginGesture().also { gesture = it }.currentCallbacksOrNull()?.onDragStart?.invoke()
                    },
                    onDragEnd = {
                        (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.onDragEnd?.invoke()
                    },
                    onDragCancel = {
                        (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.let { callbacks ->
                            (callbacks.onDragCancel ?: callbacks.onDragEnd)?.invoke()
                        }
                    }
                ) { change, dragAmount ->
                    change.consume()
                    (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.onDragMove?.invoke(dragAmount.y)
                }
            }
        } else {
            Modifier
        }
    val clickGestureOwner =
        rememberSlockGestureOwner(
            surface = "home-channel-row-click",
            semanticIdentity = interactionIdentity,
            callbacks = gestureCallbacks
        )
    val clickModifier =
        if (interaction.onLongPress != null) {
            Modifier.slockGesturePointerInput(clickGestureOwner, true) { owner ->
                var gesture: SlockGestureSession<HomeConversationRowGestureCallbacks>? = null
                detectTapGestures(
                    onPress = {
                        val callbacks =
                            owner.beginGesture().also { gesture = it }.currentCallbacksOrNull()
                        callbacks?.let {
                            callbacks.onCaptureAnchorChange(true)
                            callbacks.onPress?.invoke()
                        }
                        try {
                            tryAwaitRelease()
                        } finally {
                            gesture?.currentCallbacksOrNull()?.let { callbacks ->
                                callbacks.onCaptureAnchorChange(false)
                                callbacks.onPressEnd?.invoke()
                            }
                        }
                    },
                    onTap = {
                        (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.onClick?.invoke()
                    },
                    onLongPress = { pressOffset ->
                        (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.let { callbacks ->
                            homeRowLongPressAnchor(callbacks.rowAnchor, pressOffset)?.let { anchor ->
                                callbacks.onLongPress?.invoke(anchor)
                            }
                        }
                    }
                )
            }
        } else if (interaction.onClick != null) {
            Modifier.slockGesturePointerInput(clickGestureOwner, false) { owner ->
                var gesture: SlockGestureSession<HomeConversationRowGestureCallbacks>? = null
                detectTapGestures(
                    onPress = {
                        val callbacks =
                            owner.beginGesture().also { gesture = it }.currentCallbacksOrNull()
                        callbacks?.let {
                            callbacks.onPress?.invoke()
                            try {
                                tryAwaitRelease()
                            } finally {
                                gesture?.currentCallbacksOrNull()?.onPressEnd?.invoke()
                            }
                        }
                    },
                    onTap = {
                        (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.onClick?.invoke()
                    }
                )
            }
        } else {
            Modifier
        }
    traceMarkdownSection(SlockHomeTraceNames.CHANNEL_ROW_RENDER, name.length) {
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, drag.offsetY.roundToInt()) }
                    .homeRowAnchorCapture(interaction.captureLongPressAnchor || captureAnchorForPress, rowAnchor)
                    .then(clickModifier)
                    .then(rowDragModifier)
                    .then(hoverableModifier)
        ) {
        if (selected || pressed) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(ChannelRowHeight)
                        .offset(x = ChannelRowShadowOffset, y = ChannelRowShadowOffset)
                        .background(SlockColor.ShadowBlack)
            )
        }
        com.tencent.kuikly.compose.foundation.layout.Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(ChannelRowHeight)
                    .background(rowBackground)
                    .border(SlockStroke.Default, borderColor)
                    .padding(start = ChannelRowHorizontalPadding, end = SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (content.positionPrefix != null) {
                Text(
                    content.positionPrefix,
                    style = SlockType.Button,
                    color = SlockColor.TextMutedGray,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 22.dp).padding(end = SlockSpacing.Xs)
                )
            }
            Box(modifier = Modifier.size(width = ChannelAvatarSize, height = ChannelAvatarSize), contentAlignment = Alignment.Center) {
                if (content.avatarLabel != null) {
                    ChannelAvatar(content.avatarWireValue, content.avatarLabel, content.presence, content.showPresence)
                } else if (content.symbol == "#") {
                    SlockLucideIcon(
                        "hash",
                        Modifier.size(ChannelSymbolIconSize),
                        color = if (content.joined) SlockColor.TextBlackArgb else "#75000000"
                    )
                } else if (content.symbol == HomeChannelSymbolHashGlobe) {
                    SlockHashGlobeIcon(
                        size = ChannelSymbolIconSize,
                        color = if (content.joined) SlockColor.TextBlackArgb else "#75000000"
                    )
                } else if (content.symbol == "git-branch") {
                    SlockLucideIcon(
                        "git-branch",
                        Modifier.size(ChannelSymbolIconSize),
                        color = if (content.joined) SlockColor.TextBlackArgb else "#75000000"
                    )
                } else if (content.symbol == "lock") {
                    SlockLucideIcon(
                        "lock",
                        Modifier.size(ChannelSymbolIconSize),
                        color = if (content.joined) SlockColor.TextBlackArgb else "#75000000"
                    )
                } else {
                    Text(content.symbol, style = SlockType.Button, color = SlockColor.TextBlack.copy(alpha = if (content.joined) 1f else 0.46f), textAlign = TextAlign.Center)
                }
            }
            Row(
                modifier =
                    Modifier
                        .padding(start = SidebarActionLabelGap)
                        .weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    name,
                    style =
                        homeChannelTitleTextStyle(
                            selected = selected,
                            hasUnread = content.badge != null,
                            badgeTone = content.badgeTone,
                        ),
                    color = titleColor,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (externalBridgeBadgeVisible(content.bridgeProvider)) {
                    Spacer(Modifier.width(ChannelRowMutedIconGap))
                    SlockExternalBridgeBadge(content.bridgeProvider)
                }
                if (mutedIndicatorIcon != null) {
                    Spacer(Modifier.width(ChannelRowMutedIconGap))
                    SlockLucideIcon(
                        mutedIndicatorIcon,
                        Modifier.size(ChannelRowMutedIconSize),
                        color = SlockColor.TextMutedGrayArgb
                    )
                }
            }
            if (content.badge != null) Spacer(Modifier.width(SlockSpacing.Sm)) else if (content.hasDraft) Spacer(Modifier.width(SlockSpacing.Xs))
            if (content.hasDraft) SlockLucideIcon(SlockLucideIconName.Pencil, Modifier.size(14.dp), color = SlockColor.TextMutedGrayArgb)
            if (content.badge != null) {
                if (content.hasDraft) Spacer(Modifier.width(SlockSpacing.Xs))
                when (content.badgeTone) {
                    HomeSidebarBadgeTone.Loud -> CountPill(content.badge, SlockColor.AccentPink)
                    HomeSidebarBadgeTone.Quiet -> DimmedUnreadCount(content.badge)
                }
            }
            if (drag.enabled) {
                Spacer(Modifier.width(SlockSpacing.Xs))
                Box(
                    modifier =
                        Modifier
                            .size(width = 18.dp, height = ChannelAvatarSize)
                            .then(dragHandleModifier),
                    contentAlignment = Alignment.Center
                ) {
                    SlockLucideIcon(
                        "grip-vertical",
                        Modifier.size(14.dp),
                        color = SlockColor.TextMutedGrayArgb
                    )
                }
            }
        }
        }
    }
}

// #372 React parity: outlined-circle + gray-bar skeleton row sized like
// ChannelRow (same height / paddings) so the loaded conversation list
// replaces the skeletons in place. SlockSkeleton is fully static (no
// shimmer), which keeps visual captures deterministic.
@Composable
private fun HomeSidebarSkeletonRow(modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(ChannelRowHeight)
                .padding(start = ChannelRowHorizontalPadding, end = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockSkeleton(modifier = Modifier.size(ChannelAvatarSize), variant = SlockSkeletonVariant.Circle)
        SlockSkeleton(
            modifier =
                Modifier
                    .padding(start = SidebarActionLabelGap)
                    .weight(HomeLoadingSkeletonLineWeight)
                    .height(HomeLoadingSkeletonLineHeight),
            variant = SlockSkeletonVariant.Line
        )
        Spacer(Modifier.weight(1f - HomeLoadingSkeletonLineWeight))
    }
}

@Composable
private fun SlockSidebarSortOverlay(
    title: String,
    items: List<SlockHomeConversationItem>,
    saveLabel: String,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var order by remember(items) { mutableStateOf(items.map { it.id }) }
    val itemsById = remember(items) { items.associateBy { it.id } }
    val density = LocalDensity.current
    val rowStepPx = with(density) { (ChannelRowHeight + 2.dp).toPx().coerceAtLeast(1f) }
    val edgeScrollZonePx = with(density) { SlockSortEdgeScrollZone.toPx() }
    val edgeScrollStepPx = with(density) { SlockSortEdgeScrollStep.toPx() }
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
    var draggedId by remember(items) { mutableStateOf<String?>(null) }
    var draggedStartIndex by remember(items) { mutableStateOf(0) }
    var pointerViewportY by remember(items) { mutableStateOf(0f) }
    var targetIndex by remember(items) { mutableStateOf(-1) }
    // Debounced copy of targetIndex that drives the live position numbers. The
    // raw target changes every frame under auto-scroll, which made the numbers
    // flicker wildly (device feedback: "dragging 6 kept becoming 13"). Numbers
    // only renumber after the target has been stable for a beat; the insertion
    // line and the actual drop keep using the raw, immediate target.
    var settledTargetIndex by remember(items) { mutableStateOf(-1) }
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

    fun visibleSortRowBounds(): List<SlockSortRowBounds> =
        listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            val key = info.key as? String ?: return@mapNotNull null
            val id = key.removePrefix("sort-").takeIf { key.startsWith("sort-") } ?: return@mapNotNull null
            val index = order.indexOf(id).takeIf { it >= 0 } ?: return@mapNotNull null
            SlockSortRowBounds(index = index, top = info.offset.toFloat(), size = info.size.toFloat())
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
        runSlockSortAutoScrollLoop(
            listState = listState,
            isDragging = { draggedId != null },
            pointerViewportY = { pointerViewportY },
            edgeScrollZonePx = edgeScrollZonePx,
            edgeScrollStepPx = edgeScrollStepPx
        ) { delta, consumed ->
            recomputeDrag()
            slockLogInfo(
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

    SlockUnifiedDialog(
        title = title,
        onDismiss = onDismiss,
        dismissEnabled = draggedId == null,
        contentSpacing = 12.dp
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 420.dp).clipToBounds()) {
            // Container-level long-press drag: identify the row under the finger
            // at long-press, then track the finger. Because the gesture is on
            // the container it survives list scrolling and preview reordering.
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(order) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { pos ->
                                    val rows = visibleSortRowBounds()
                                    val target = resolveSortTargetIndex(pos.y, rows, order.lastIndex)
                                    val id = order.getOrNull(target)
                                    if (id != null) {
                                        draggedId = id
                                        draggedStartIndex = target
                                        pointerViewportY = pos.y
                                        targetIndex = target
                                        slockLogInfo(
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
                                        order = order.moveItem(active, targetIndex)
                                    }
                                    slockLogInfo(
                                        "SlockHomeSort",
                                        "dragEnd changed=$changed from=$draggedStartIndex to=$targetIndex"
                                    )
                                    draggedId = null
                                    targetIndex = -1
                                },
                                onDragCancel = {
                                    slockLogInfo("SlockHomeSort", "dragCancel id=$draggedId")
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
                verticalArrangement = Arrangement.spacedBy(2.dp),
                userScrollEnabled = draggedId == null
            ) {
                items(order.size, key = { index -> "sort-${order[index]}" }) { index ->
                    val itemId = order[index]
                    val item = itemsById[itemId] ?: return@items
                    val isDirectMessage = item.isDirectMessageConversation()
                    // The list never reflows during a drag. The dragged row keeps
                    // its slot as an invisible placeholder (the visible copy is the
                    // floating overlay); every other row stays exactly in place, so
                    // there is no per-frame reflow to jitter under auto-scroll.
                    if (draggedId == itemId) {
                        Spacer(Modifier.fillMaxWidth().height(ChannelRowHeight))
                    } else {
                        // 1-based position before the row icon: live from the settled
                        // preview during a drag (debounced, so no frantic jumping),
                        // fixed slot index otherwise.
                        val position = previewPositionById[itemId] ?: (index + 1)
                        ChannelRow(
                            content =
                                ChannelRowContent(
                                    name = item.name,
                                    positionPrefix = "$position",
                                    symbol = if (isDirectMessage) "#" else item.homeChannelSymbol(),
                                    avatarLabel = if (isDirectMessage) item.avatarLabel else null,
                                    joined = item.joined || isDirectMessage,
                                    showPresence = false
                                ),
                            interaction =
                                ChannelRowInteraction(
                                    identity = "channel:${if (isDirectMessage) "#" else item.homeChannelSymbol()}:${item.name}",
                                    state = ChannelRowInteractionState.Default,
                                    captureLongPressAnchor = false
                                ),
                            modifier = Modifier.padding(horizontal = HomeListHorizontalPadding)
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
                                .background(SlockColor.AccentPink)
                    )
                }
            }

            // Floating copy of the dragged row, pinned under the finger. Never
            // part of the LazyColumn, so it is never disposed mid-drag.
            if (active != null) {
                val item = itemsById[active]
                if (item != null) {
                    val isDirectMessage = item.isDirectMessageConversation()
                    // Held row shows its settled landing position (debounced target).
                    val position = previewPositionById[active] ?: (draggedStartIndex + 1)
                    ChannelRow(
                        content =
                            ChannelRowContent(
                                name = item.name,
                                positionPrefix = "$position",
                                symbol = if (isDirectMessage) "#" else item.homeChannelSymbol(),
                                avatarLabel = if (isDirectMessage) item.avatarLabel else null,
                                joined = item.joined || isDirectMessage,
                                showPresence = false
                            ),
                        interaction =
                            ChannelRowInteraction(
                                identity = "channel:${if (isDirectMessage) "#" else item.homeChannelSymbol()}:${item.name}",
                                state = ChannelRowInteractionState.Pressed,
                                captureLongPressAnchor = false
                            ),
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
                                            rowSize = rowStepPx
                                        ).roundToInt()
                                    )
                                }
                    )
                }
            }
        }
        SlockModalFullWidthActionButton(
            label = saveLabel,
            icon = SlockLucideIconName.Check,
            color = SlockColor.AccentPink,
            enabled = draggedId == null,
            onClick = { onSave(order) }
        )
    }
}

@Composable
private fun DimmedUnreadCount(text: String) {
    Box(modifier = Modifier.height(CountPillHeight).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = SlockType.Caption.copy(fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 10.sp, fontFamily = SlockMonoFamily),
            color = SlockColor.TextBlack.copy(alpha = 0.5f),
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DirectMessageRow(name: String, description: String? = null, modifier: Modifier = Modifier, avatarLabel: String, peerType: String? = null, memberId: String? = null, cacheServerId: String? = null, agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver, memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver, badge: String? = null, badgeTone: HomeSidebarBadgeTone = HomeSidebarBadgeTone.Loud, hasDraft: Boolean = false, muted: Boolean = false, interactionIdentity: String = "dm:${memberId.orEmpty()}:$name", interactionState: ChannelRowInteractionState = ChannelRowInteractionState.Default, captureLongPressAnchor: Boolean = true, onClick: (() -> Unit)? = null, onLongPress: ((SlockPopoverAnchor) -> Unit)? = null, onPress: (() -> Unit)? = null, onPressEnd: (() -> Unit)? = null) {
    val hoverInteractionSource = remember(interactionIdentity) { MutableInteractionSource() }
    val isHovered by hoverInteractionSource.collectIsHoveredAsState()
    val selected = interactionState == ChannelRowInteractionState.Selected
    val pressed = interactionState == ChannelRowInteractionState.Pressed
    val unread = badge != null
    val isAgent = peerType?.equals("agent", ignoreCase = true) == true
    val descriptionText = description?.takeIf { it.isNotBlank() }
    val avatarWireValue = rememberSlockMemberAvatarWireValue(memberAvatarResolver, memberId)
    val titleColor = homeConversationTitleColor(muted = muted)
    val mutedIndicatorIcon = homeConversationMutedIndicatorIcon(muted)
    val effectiveInteractionState = when {
        selected -> ChannelRowInteractionState.Selected
        pressed -> ChannelRowInteractionState.Pressed
        isHovered -> ChannelRowInteractionState.Hovered
        else -> interactionState
    }
    val rowBackground = when (effectiveInteractionState) {
        ChannelRowInteractionState.Default -> Color.Transparent
        ChannelRowInteractionState.Selected -> SlockColor.AccentPink
        ChannelRowInteractionState.Hovered -> SlockColor.SurfaceWhite
        ChannelRowInteractionState.Focused -> SlockColor.SurfaceWhite
        ChannelRowInteractionState.Pressed -> SlockColor.SurfaceWhite
    }
    val borderColor = when (interactionState) {
        ChannelRowInteractionState.Default -> Color.Transparent
        ChannelRowInteractionState.Hovered, ChannelRowInteractionState.Pressed, ChannelRowInteractionState.Selected, ChannelRowInteractionState.Focused -> SlockColor.BorderBlack
    }
    val rowAnchor = remember(interactionIdentity) { SlockLiveRootAnchorRef(identity = interactionIdentity) }
    var captureAnchorForPress by remember(interactionIdentity) { mutableStateOf(false) }
    val hoverableModifier = Modifier.hoverable(hoverInteractionSource)
    val gestureOwner =
        rememberSlockGestureOwner(
            surface = "home-direct-message-row",
            semanticIdentity = interactionIdentity,
            callbacks =
                HomeConversationRowGestureCallbacks(
                    onClick = onClick,
                    onLongPress = onLongPress,
                    onPress = onPress,
                    onPressEnd = onPressEnd,
                    onCaptureAnchorChange = { captureAnchorForPress = it },
                    rowAnchor = rowAnchor
                )
        )
    val clickModifier =
        if (onLongPress != null) {
            Modifier.slockGesturePointerInput(gestureOwner, "click", true) { owner ->
                var gesture: SlockGestureSession<HomeConversationRowGestureCallbacks>? = null
                detectTapGestures(
                    onPress = {
                        val callbacks =
                            owner.beginGesture().also { gesture = it }.currentCallbacksOrNull()
                        callbacks?.let {
                            callbacks.onCaptureAnchorChange(true)
                            callbacks.onPress?.invoke()
                        }
                        try {
                            tryAwaitRelease()
                        } finally {
                            gesture?.currentCallbacksOrNull()?.let { callbacks ->
                                callbacks.onCaptureAnchorChange(false)
                                callbacks.onPressEnd?.invoke()
                            }
                        }
                    },
                    onTap = {
                        (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.onClick?.invoke()
                    },
                    onLongPress = { pressOffset ->
                        (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.let { callbacks ->
                            homeRowLongPressAnchor(callbacks.rowAnchor, pressOffset)?.let { anchor ->
                                callbacks.onLongPress?.invoke(anchor)
                            }
                        }
                    }
                )
            }
        } else if (onClick != null) {
            Modifier.slockGesturePointerInput(gestureOwner, "click", false) { owner ->
                var gesture: SlockGestureSession<HomeConversationRowGestureCallbacks>? = null
                detectTapGestures(
                    onPress = {
                        val callbacks =
                            owner.beginGesture().also { gesture = it }.currentCallbacksOrNull()
                        callbacks?.let {
                            callbacks.onPress?.invoke()
                            try {
                                tryAwaitRelease()
                            } finally {
                                gesture?.currentCallbacksOrNull()?.onPressEnd?.invoke()
                            }
                        }
                    },
                    onTap = {
                        (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.onClick?.invoke()
                    }
                )
            }
        } else {
            Modifier
        }
    traceMarkdownSection(SlockHomeTraceNames.DIRECT_MESSAGE_ROW_RENDER, name.length) {
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .homeRowAnchorCapture(captureLongPressAnchor || captureAnchorForPress, rowAnchor)
                    .then(clickModifier)
                    .then(hoverableModifier)
        ) {
        if (selected || pressed) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(ChannelRowHeight)
                        .offset(x = ChannelRowShadowOffset, y = ChannelRowShadowOffset)
                        .background(SlockColor.ShadowBlack)
            )
        }
        com.tencent.kuikly.compose.foundation.layout.Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(ChannelRowHeight)
                    .background(rowBackground)
                    .border(SlockStroke.Default, borderColor)
                    .padding(start = ChannelRowHorizontalPadding, end = SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(width = ChannelAvatarSize, height = ChannelAvatarSize), contentAlignment = Alignment.Center) {
                SlockAvatar(
                    avatarWireValue = avatarWireValue,
                    label = avatarLabel,
                    cacheSubject = slockMemberAvatarCacheSubject(peerType.homeDmPeerMemberKindOrNull(), memberId),
                    cacheServerId = cacheServerId
                )
            }
            com.tencent.kuikly.compose.foundation.layout.Row(
                modifier =
                    Modifier
                        .padding(start = SidebarActionLabelGap)
                        .weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    name,
                    style = if (unread) SlockType.ListItemBold else SlockType.ListItem,
                    color = titleColor,
                    modifier =
                        if (descriptionText != null) {
                            Modifier.widthIn(max = 260.dp)
                        } else {
                            Modifier.weight(1f, fill = false)
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (mutedIndicatorIcon != null) {
                    Spacer(Modifier.width(ChannelRowMutedIconGap))
                    SlockLucideIcon(
                        mutedIndicatorIcon,
                        Modifier.size(ChannelRowMutedIconSize),
                        color = SlockColor.TextMutedGrayArgb
                    )
                }
                if (descriptionText != null) {
                    Spacer(Modifier.width(SlockSpacing.Xs))
                    Text(
                        descriptionText,
                        style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
                        color = SlockColor.TextMutedGray,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (isAgent || hasDraft || badge != null) {
                Spacer(Modifier.width(SlockSpacing.Sm))
            }
            if (isAgent) {
                memberId?.takeIf { it.isNotBlank() }?.let {
                    // A missing resolver value means presence is still Unknown/not ready.
                    // Do not manufacture Offline: that falsely renders a gray dot while the
                    // authoritative member/presence projection is loading or recovering.
                    projectHomeAgentStatusIndicatorState(
                        showPresence = true,
                        memberId = it,
                        canonicalState = rememberSlockAgentStatusIndicatorState(agentStatusResolver, it)
                    )?.let { state -> DirectMessageStatusDot(state) }
                }
            }
            if (hasDraft) {
                if (isAgent) Spacer(Modifier.width(SlockSpacing.Xs))
                SlockLucideIcon(SlockLucideIconName.Pencil, Modifier.size(14.dp), color = SlockColor.TextMutedGrayArgb)
            }
            if (badge != null) {
                if (isAgent || hasDraft) Spacer(Modifier.width(SlockSpacing.Xs))
                when (badgeTone) {
                    HomeSidebarBadgeTone.Loud -> CountPill(badge, SlockColor.AccentPink)
                    HomeSidebarBadgeTone.Quiet -> DimmedUnreadCount(badge)
                }
            }
        }
        }
    }
}

@Composable
private fun DirectMessageStatusDot(state: SlockAgentStatusIndicatorState) {
    SlockStatusIndicator(
        color = state.color,
        size = DirectMessageStatusDotSize,
        pulsing = state.pulsing
    )
}

@Composable
private fun ChannelAvatar(avatarWireValue: String?, label: String, presence: SlockPresence? = null, showPresence: Boolean = true) {
    SlockAvatarWithStatus(
        statusColor = if (showPresence) presence?.homeRowColor else null,
        avatarSize = ChannelAvatarSize,
        statusSize = 8.dp
    ) {
        SlockAvatar(avatarWireValue, label)
    }
}

internal fun slockMemberAvatarCacheSubject(
    kind: MemberKind?,
    memberId: String?
): SlockAvatarCacheSubject.Member? {
    val cleanMemberId = memberId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val cacheableKind = kind?.takeIf { it == MemberKind.Human || it == MemberKind.Agent } ?: return null
    return SlockAvatarCacheSubject.Member(cacheableKind, cleanMemberId)
}

internal data class SlockMemberAvatarCacheIdentity(
    val subject: SlockAvatarCacheSubject.Member,
    val serverId: String
)

internal fun slockMemberAvatarCacheIdentity(
    kind: MemberKind?,
    memberId: String?,
    serverId: String?
): SlockMemberAvatarCacheIdentity? {
    val cleanServerId = serverId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val subject = slockMemberAvatarCacheSubject(kind, memberId) ?: return null
    return SlockMemberAvatarCacheIdentity(subject = subject, serverId = cleanServerId)
}

internal fun MemberProfile.slockAvatarCacheSubject(): SlockAvatarCacheSubject.Member? =
    slockMemberAvatarCacheSubject(kind, id.value)

internal fun MemberProfile.slockAvatarCacheIdentity(
    cacheServerId: String? = serverId?.value
): SlockMemberAvatarCacheIdentity? {
    val cleanCacheServerId = cacheServerId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val profileServerId = serverId?.value?.trim()?.takeIf(String::isNotEmpty)
    if (profileServerId != null && profileServerId != cleanCacheServerId) return null
    return slockMemberAvatarCacheIdentity(kind, id.value, cleanCacheServerId)
}

@Composable
internal fun SlockAvatar(
    avatarWireValue: String?,
    label: String,
    modifier: Modifier = Modifier,
    avatarSize: Dp = ChannelAvatarSize,
    borderWidth: Dp = SlockStroke.Thin,
    diagnostics: SlockAvatarRowDiagnostics? = null,
    cacheSubject: SlockAvatarCacheSubject? = null,
    cacheServerId: String? = null
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
        traceMarkdownSectionNonComposable(SlockAvatarTraceNames.IDENTIFY) {
            displayAvatarWireValue?.toPixelAvatarSpec()
        }
    }
    when {
        pixelSpec != null -> traceMarkdownSection(SlockAvatarTraceNames.RENDER, displayAvatarWireValue?.length ?: 0) {
            PixelAvatar(pixelSpec, modifier, avatarSize, borderWidth)
        }
        httpAvatarSource != null -> Box(
            modifier = modifier
                .size(width = avatarSize, height = avatarSize)
                .background(SlockColor.AccentLavender)
                .border(borderWidth, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            // task #343: an uploaded avatar must not flash the generic icon while its cached
            // bytes are still being resolved (cold start reads them from SQLite + native). Keep
            // the neutral background until the load settles; draw the icon only on a definitive
            // failure. Gravatar keeps the immediate icon: it is often unreachable (CN) and the
            // icon is its expected fallback.
            if (gravatarHttpSource != null) DefaultUserAvatarIcon(avatarSize)
            SlockAvatarHttpImage(
                source = httpAvatarSource,
                modifier = Modifier.fillMaxSize(),
                diagnostics = diagnostics,
                cacheSubject = cacheSubject,
                cacheServerId = cacheServerId,
                fallback = { if (gravatarHttpSource == null) DefaultUserAvatarIcon(avatarSize) }
            )
        }
        else -> traceMarkdownSection(SlockAvatarTraceNames.FALLBACK_DEFAULT, displayAvatarWireValue?.length ?: 0) {
            DefaultUserAvatar(modifier, avatarSize, borderWidth)
        }
    }
}

@Composable
internal fun SlockAvatarHttpImage(
    source: String,
    modifier: Modifier = Modifier,
    diagnostics: SlockAvatarRowDiagnostics? = null,
    cacheSubject: SlockAvatarCacheSubject? = null,
    cacheServerId: String? = null,
    fallback: @Composable () -> Unit = {},
    serverDiagnosticsId: String? = null,
    // "visible" or "prewarm" in server avatar logs, so a stuck flight can be traced to its owner.
    diagnosticsRole: String = "visible",
    // Shown until the image has actually loaded, so a slow or withheld load shows a placeholder
    // (the server tile passes its initial) instead of the bare tile background.
    placeholder: @Composable () -> Unit = {}
) {
    val apiBaseUrl =
        SlockSharedStoreRegistry.currentOrNull()
            ?.apiEnvironmentStore
            ?.current()
            ?.apiBaseUrl
            ?.trimEnd('/')
    val fallbackSource =
        remember(source, apiBaseUrl) {
            source.appAvatarApiFallbackUrl(apiBaseUrl)?.takeUnless { it == source }
        }
    val loadKey =
        remember(source, fallbackSource, apiBaseUrl) {
            SlockAvatarHttpLoadKey(
                primarySource = source,
                primaryEndpoint =
                    if (apiBaseUrl != null && source.startsWith("$apiBaseUrl/")) {
                        SlockAvatarHttpEndpoint.Api
                    } else {
                        SlockAvatarHttpEndpoint.Cdn
                    },
                apiFallbackSource = fallbackSource
            )
        }
    val loadStateFlow = remember(loadKey) { slockAvatarHttpLoadCoordinator.stateFlow(loadKey) }
    val loadState by loadStateFlow.collectAsState()
    if (slockAvatarHttpShowsFallback(loadState)) fallback()
    var lease by remember(loadKey) { mutableStateOf<SlockAvatarHttpLoadLease?>(null) }
    val activeSource = loadKey.sourceFor(loadState.endpoint) ?: return
    val avatarMountedBinding = SlockSharedStoreRegistry.currentOrNull()?.avatarMountedBinding
    val initialManagedModel =
        remember(activeSource, cacheSubject, cacheServerId, avatarMountedBinding) {
            if (avatarMountedBinding == null || cacheSubject == null) {
                null
            } else {
                avatarMountedBinding.peekInitialManagedModel(
                    subject = cacheSubject,
                    remoteUrl = activeSource,
                    serverId = cacheServerId
                )
            }
        }
    var mountedHandle by
        remember(activeSource, cacheSubject, cacheServerId, avatarMountedBinding) {
            mutableStateOf<SlockAvatarMountedHandle?>(null)
        }

    // Durable bytes are an owner-scoped render source, not a process-session HTTP result. Inspect
    // them before the HTTP painter gate so a restarted process can render an already committed
    // avatar without first exposing the default icon. Cache misses and unsupported providers still
    // return no managed model here and remain subject to the coordinator below.
    LaunchedEffect(activeSource, cacheSubject, cacheServerId, avatarMountedBinding) {
        mountedHandle =
            if (avatarMountedBinding == null || cacheSubject == null) {
                null
            } else {
                avatarMountedBinding.mount(
                    subject = cacheSubject,
                    remoteUrl = activeSource,
                    serverId = cacheServerId
                )
            }
    }
    DisposableEffect(avatarMountedBinding, mountedHandle) {
        val ownedHandle = mountedHandle
        onDispose {
            if (ownedHandle != null) avatarMountedBinding?.dispose(ownedHandle)
        }
    }
    val ownsCurrentRequest =
        loadState.cache == SlockAvatarHttpCacheState.InFlight &&
            lease?.generation == loadState.generation &&
            lease?.ownerToken == loadState.ownerToken
    val refreshEffectKey =
        slockAvatarHttpRefreshEffectKey(
            activeSource = activeSource,
            loadState = loadState,
            ownsCurrentRequest = ownsCurrentRequest
        )
    LaunchedEffect(
        avatarMountedBinding,
        mountedHandle,
        refreshEffectKey
    ) {
        val binding = avatarMountedBinding ?: return@LaunchedEffect
        val handle = mountedHandle ?: return@LaunchedEffect
        if (!refreshEffectKey.refreshAdmitted) return@LaunchedEffect
        binding.refreshWhenHttpLoadAdmitted(
            handle = handle,
            loadState = loadState,
            ownsCurrentRequest = ownsCurrentRequest
        )
    }

    DisposableEffect(loadKey, lease?.ownerToken) {
        val ownedLease = lease
        slockAvatarHttpLoadCoordinator.retain(loadKey)
        onDispose {
            ownedLease?.let { slockAvatarHttpLoadCoordinator.releaseOwner(loadKey, it) }
            slockAvatarHttpLoadCoordinator.release(loadKey)
        }
    }

    // ownerToken is part of the key so that an owner departing (ownerToken -> null while the
    // flight stays InFlight) re-fires this effect and lets a surviving/new row adopt the orphan.
    LaunchedEffect(loadKey, loadState.cache, loadState.ownerToken, loadState.retryAtMillis, loadState.generation) {
        val retryDelayMillis = slockAvatarHttpLoadCoordinator.retryDelayMillis(loadState)
        if (retryDelayMillis > 0L) delay(retryDelayMillis)
        when (slockAvatarHttpAcquireAttempt(loadState, ownsCurrentRequest)) {
            SlockAvatarHttpAcquireAttempt.Now ->
                lease = slockAvatarHttpLoadCoordinator.tryAcquire(loadKey)
            SlockAvatarHttpAcquireAttempt.AfterStuckOwnerTimeout -> {
                // Another row owns the flight. If it goes silent, tryAcquire adopts it after the
                // stuck-owner timeout; keep checking until then. Any progress that changes the
                // keys (ready, failure, new owner) cancels this loop.
                while (true) {
                    delay(slockAvatarHttpLoadCoordinator.stuckOwnerRecheckDelayMillis)
                    val adopted = slockAvatarHttpLoadCoordinator.tryAcquire(loadKey) ?: continue
                    if (serverDiagnosticsId != null) {
                        slockLogInfo(
                            "SlockServerAvatar",
                            "server_avatar_stuck_owner_adopted serverHash=${serverDiagnosticsId.slockAvatarDiagnosticsHash()} " +
                                "role=${diagnosticsRole} generation=${adopted.generation}"
                        )
                    }
                    lease = adopted
                    break
                }
            }
            SlockAvatarHttpAcquireAttempt.None -> Unit
        }
    }

    val candidateImageModel =
        if (avatarMountedBinding == null || cacheSubject == null) {
            activeSource
        } else {
            // Cold cache: the durable bytes haven't landed yet, so imageModel is still null. Fall
            // back to an already-committed managed model from this session, or the ordinary HTTP source
            // so the painter isn't starved while the mounted download is still in flight; once bytes
            // commit, imageModel takes over rendering.
            val handle = mountedHandle
            handle?.imageModel?.collectAsState()?.value
                ?: initialManagedModel
                ?: activeSource
        }
    LaunchedEffect(diagnostics, loadKey, loadState, lease?.ownerToken, candidateImageModel) {
        val row = diagnostics ?: return@LaunchedEffect
        row.logger(
            "thread_avatar_painter messageHash=${row.messageHash} authorHash=${row.authorHash} " +
                "source=${loadKey.diagnosticsSource} ${activeSource.slockAvatarDiagnosticsUrlShape(apiBaseUrl)} " +
                "activeEndpoint=${loadState.endpoint.diagnosticsName()} " +
                "model=${if (parseSlockAvatarMountedRenderModel(candidateImageModel) != null) "managed" else "url"} " +
                "painter=${loadState.painter.diagnosticsName()} " +
                "cache=${loadState.diagnosticsCacheState(lease?.ownerToken)}"
        )
    }
    LaunchedEffect(serverDiagnosticsId, loadKey, loadState, lease?.ownerToken, candidateImageModel) {
        val serverId = serverDiagnosticsId ?: return@LaunchedEffect
        slockLogInfo(
            "SlockServerAvatar",
            "server_avatar_painter serverHash=${serverId.slockAvatarDiagnosticsHash()} " +
                "role=$diagnosticsRole " +
                "source=${loadKey.diagnosticsSource} ${activeSource.slockAvatarDiagnosticsUrlShape(apiBaseUrl)} " +
                "activeEndpoint=${loadState.endpoint.diagnosticsName()} " +
                "model=${if (parseSlockAvatarMountedRenderModel(candidateImageModel) != null) "managed" else "url"} " +
                "painter=${loadState.painter.diagnosticsName()} " +
                "cache=${loadState.diagnosticsCacheState(lease?.ownerToken)}"
        )
    }
    val admittedImageModel =
        slockAvatarHttpAdmittedImageModel(
            candidateImageModel = candidateImageModel,
            loadState = loadState,
            ownsCurrentRequest = ownsCurrentRequest
        )
    // Sticky render source: once a model has reached painter Success, keep it while the pending
    // candidate is only a different encoding of the same bytes (URL -> managed at the pinned
    // commit, or managed at the same commit). Switching the painter model mid-display rebuilds the
    // painter and drops the frame back to the placeholder for a frame or two — the visible
    // "flash" of task #402. The managed first-frame value (cold start renders durable bytes
    // without a fetch) is unaffected: stickiness only arms after a Success is already on screen.
    var lastRenderedFrame by
        remember(activeSource, cacheSubject, cacheServerId, avatarMountedBinding) {
            mutableStateOf<SlockAvatarRenderedFrame?>(null)
        }
    val stickyImageModel =
        slockAvatarStickyImageModel(
            candidateImageModel = admittedImageModel,
            lastRenderedFrame = lastRenderedFrame
        )
    if (stickyImageModel != admittedImageModel) {
        LaunchedEffect(stickyImageModel, admittedImageModel) {
            slockLogDebug(
                "SlockAvatar",
                "avatar_model_sticky source=${activeSource.slockAvatarDiagnosticsHash()} " +
                    "rendered=${if (parseSlockAvatarMountedRenderModel(stickyImageModel.orEmpty()) != null) "managed" else "url"} " +
                    "candidate=${if (parseSlockAvatarMountedRenderModel(admittedImageModel.orEmpty()) != null) "managed" else "url"}"
            )
        }
    }
    if (stickyImageModel == null) {
        if (!slockAvatarHttpShowsFallback(loadState)) placeholder()
        return
    }
    // The endpoint whose URL this painter renders; every report carries it so a late result for a
    // URL the flight has moved off is ignored.
    val renderedEndpoint = loadState.endpoint
    val painter = rememberAsyncImagePainter(model = stickyImageModel)
    val imageState by painter.state.collectAsState()
    // Record the rendered frame inside an effect — writing state mid-composition can skip
    // dependents and re-runs anyway. A fresh Success becomes the new baseline and clears the
    // URL pin; for a URL frame the pin is the managed commit admitted at this instant (usually
    // none yet — the first later managed candidate arms it below).
    LaunchedEffect(imageState, stickyImageModel, admittedImageModel) {
        if (imageState is AsyncImagePainter.State.Success) {
            lastRenderedFrame =
                SlockAvatarRenderedFrame(
                    model = stickyImageModel,
                    urlBaselineCommit =
                        parseSlockAvatarMountedRenderModel(admittedImageModel.orEmpty())
                            ?.commitIdentity
                )
        }
    }
    // Arm the URL pin lazily: the mount's imageModel can emit after the URL frame is already on
    // screen, in which case the first managed candidate seen belongs to this frame and becomes the
    // bound. Any commit that supersedes it ends the stickiness.
    LaunchedEffect(admittedImageModel, lastRenderedFrame) {
        val frame = lastRenderedFrame ?: return@LaunchedEffect
        if (parseSlockAvatarMountedRenderModel(frame.model) != null) return@LaunchedEffect
        if (frame.urlBaselineCommit != null) return@LaunchedEffect
        val candidateCommit =
            parseSlockAvatarMountedRenderModel(admittedImageModel.orEmpty())?.commitIdentity
                ?: return@LaunchedEffect
        lastRenderedFrame = frame.copy(urlBaselineCommit = candidateCommit)
    }
    if (imageState !is AsyncImagePainter.State.Success && !slockAvatarHttpShowsFallback(loadState)) {
        placeholder()
    }
    LaunchedEffect(imageState, stickyImageModel, loadState.generation, lease?.ownerToken) {
        val phase = imageState.toSlockAvatarHttpPainterPhase()
        if (ownsCurrentRequest) {
            lease?.let { currentLease ->
                slockAvatarHttpLoadCoordinator.reportPainterPhase(loadKey, currentLease, phase, renderedEndpoint)
            }
        } else if (loadState.cache == SlockAvatarHttpCacheState.InFlight) {
            slockAvatarHttpLoadCoordinator.reportObserverPhase(
                loadKey,
                loadState.generation,
                renderedEndpoint,
                phase
            )
        } else if (phase == SlockAvatarHttpPainterPhase.Error) {
            slockAvatarHttpLoadCoordinator.reportReadyConsumerError(loadKey, loadState.generation, renderedEndpoint)
        }
    }
    Image(
        painter = painter,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.FillBounds
    )
}

/**
 * task #343: the generic avatar icon is a failure affordance, not a loading placeholder. It is
 * shown only once the HTTP load has settled on failure (cooldown), or a ready generation failed to
 * render. Idle / in-flight loads keep the neutral background so a cold start goes straight from
 * blank to the real avatar instead of flashing the default icon first.
 */
internal fun slockAvatarHttpShowsFallback(loadState: SlockAvatarHttpLoadSnapshot): Boolean =
    loadState.cache == SlockAvatarHttpCacheState.Cooldown ||
        (loadState.cache == SlockAvatarHttpCacheState.Ready &&
            loadState.painter == SlockAvatarHttpPainterPhase.Error)

/**
 * The model the avatar painter last reached Success with, plus the bound for URL-frame stickiness.
 *
 * `urlBaselineCommit` is meaningful only while [model] is a plain URL: it pins the URL -> managed
 * stickiness to a single managed commit. It is recorded when the URL frame succeeds (the admitted
 * candidate at that instant) or, if no managed model had been emitted yet, armed by the first
 * managed candidate observed afterwards. A later managed model carrying a different commitIdentity
 * is a real avatar change and is admitted, so a stable (non content-addressed) URL cannot pin the
 * old frame forever. Own-CDN avatar URLs are content-addressed (`/avatars/<ns>/<hash>.webp`, see
 * AppAvatarCdnPath), so the common change path already resets this frame via `activeSource`.
 */
internal data class SlockAvatarRenderedFrame(
    val model: String,
    val urlBaselineCommit: SlockAvatarCommitIdentity? = null
)

/**
 * task #402: pick the render model once a frame has succeeded. `candidateImageModel` is the
 * currently admitted model; `lastRenderedFrame` is the model the painter last reached Success
 * with. Returns the model to feed `rememberAsyncImagePainter`.
 *
 * Rules:
 * - No candidate or no rendered success yet → the candidate decides (cold start, first frame).
 * - Candidate is not a managed descriptor (a URL, including a reverted/cleared mount) → the
 *   candidate decides; stickiness only suppresses managed-model handoffs, never a real source change.
 * - Rendered model is a URL and candidate is managed → keep the URL while the candidate is the
 *   commit this URL frame belongs to (baseline unset = the first managed candidate seen, which is
 *   then armed as the baseline). The managed bytes for that commit are fetched from the same
 *   activeSource, so re-rendering would re-decode the identical image through a placeholder gap.
 *   A superseding commitIdentity re-renders — the stickiness window is bounded to one commit.
 * - Both managed → keep the rendered model only while slot and commitIdentity match (same bytes,
 *   newer modelRevision). A different commitIdentity is a real avatar change and must re-render.
 */
internal fun slockAvatarStickyImageModel(
    candidateImageModel: String?,
    lastRenderedFrame: SlockAvatarRenderedFrame?
): String? {
    val candidate = candidateImageModel ?: return null
    val frame = lastRenderedFrame ?: return candidate
    val rendered = frame.model
    if (rendered == candidate) return rendered
    val candidateManaged = parseSlockAvatarMountedRenderModel(candidate) ?: return candidate
    val renderedManaged = parseSlockAvatarMountedRenderModel(rendered)
    if (renderedManaged == null) {
        val baseline = frame.urlBaselineCommit
        return if (baseline == null || candidateManaged.commitIdentity == baseline) {
            rendered
        } else {
            candidate
        }
    }
    return if (
        renderedManaged.slot == candidateManaged.slot &&
        renderedManaged.commitIdentity == candidateManaged.commitIdentity
    ) {
        rendered
    } else {
        candidate
    }
}

/**
 * A committed mounted descriptor is already fenced by environment/account/server/subject/content
 * identity, so it may render before the process-local HTTP painter coordinator is warm. Ordinary
 * URLs (including the unsupported-provider fallback) keep the existing single-flight gate.
 */
internal fun slockAvatarHttpAdmittedImageModel(
    candidateImageModel: String,
    loadState: SlockAvatarHttpLoadSnapshot,
    ownsCurrentRequest: Boolean
): String? {
    if (parseSlockAvatarMountedRenderModel(candidateImageModel) != null) return candidateImageModel
    return candidateImageModel.takeIf { slockAvatarHttpRenderIsAdmitted(loadState, ownsCurrentRequest) }
}

/**
 * Which rows may render the shared URL. Every row renders an in-flight or ready generation and
 * reports its own result (see SlockAvatarHttpLoadCoordinator.reportObserverPhase); only Idle and
 * Cooldown withhold it. Rendering used to be owner-only, which tied every row to the owner being
 * drawn: a composed-but-undrawn owner left all other rows blank (HarmonyOS 1.13.0 black server
 * avatars). The durable refresh below keeps the owner-only gate so it still downloads once.
 */
internal fun slockAvatarHttpRenderIsAdmitted(
    loadState: SlockAvatarHttpLoadSnapshot,
    ownsCurrentRequest: Boolean
): Boolean =
    ownsCurrentRequest ||
        loadState.cache == SlockAvatarHttpCacheState.Ready ||
        loadState.cache == SlockAvatarHttpCacheState.InFlight

/**
 * Durable mounted bytes may bypass this gate for rendering only. Any refresh or ordinary URL load
 * still requires the current coordinator lease, or reuse of an already-ready HTTP generation.
 */
internal fun slockAvatarHttpLoadIsAdmitted(
    loadState: SlockAvatarHttpLoadSnapshot,
    ownsCurrentRequest: Boolean
): Boolean = ownsCurrentRequest || loadState.cache == SlockAvatarHttpCacheState.Ready

/**
 * Stable render-axis identity for the mounted refresh effect. A successful managed first frame
 * changes cache/ownership, but not the admitted generation; keeping those transient fields out of
 * the key lets the original refresh finish instead of cancelling and restarting its transport.
 */
internal data class SlockAvatarHttpRefreshEffectKey(
    val activeSource: String,
    val generation: Long,
    val refreshAdmitted: Boolean
)

internal fun slockAvatarHttpRefreshEffectKey(
    activeSource: String,
    loadState: SlockAvatarHttpLoadSnapshot,
    ownsCurrentRequest: Boolean
): SlockAvatarHttpRefreshEffectKey =
    SlockAvatarHttpRefreshEffectKey(
        activeSource = activeSource,
        generation = loadState.generation,
        refreshAdmitted = slockAvatarHttpLoadIsAdmitted(loadState, ownsCurrentRequest)
    )

internal suspend fun SlockAvatarMountedBinding.refreshWhenHttpLoadAdmitted(
    handle: SlockAvatarMountedHandle,
    loadState: SlockAvatarHttpLoadSnapshot,
    ownsCurrentRequest: Boolean
): Boolean {
    if (!slockAvatarHttpLoadIsAdmitted(loadState, ownsCurrentRequest)) return false
    refresh(handle)
    return true
}

private fun AsyncImagePainter.State.toSlockAvatarHttpPainterPhase(): SlockAvatarHttpPainterPhase =
    when (this) {
        is AsyncImagePainter.State.Empty -> SlockAvatarHttpPainterPhase.Empty
        is AsyncImagePainter.State.Loading -> SlockAvatarHttpPainterPhase.Loading
        is AsyncImagePainter.State.Success -> SlockAvatarHttpPainterPhase.Success
        is AsyncImagePainter.State.Error -> SlockAvatarHttpPainterPhase.Error
    }

@Composable
private fun DefaultUserAvatar(
    modifier: Modifier = Modifier,
    avatarSize: Dp = ChannelAvatarSize,
    borderWidth: Dp = SlockStroke.Thin
) {
    Box(modifier = modifier.size(avatarSize).background(SlockColor.AccentLavender).border(borderWidth, SlockColor.BorderBlack), contentAlignment = Alignment.Center) {
        DefaultUserAvatarIcon(avatarSize)
    }
}

@Composable
private fun DefaultUserAvatarIcon(avatarSize: Dp) {
    SlockLucideIcon(SlockLucideIconName.User, modifier = Modifier.size((avatarSize.value * 0.56f).dp), color = SlockColor.TextBlackArgb)
}

private val SlockPresence.homeRowColor: Color
    get() = when (this) { SlockPresence.Online -> SlockColor.AccentLime; SlockPresence.Offline -> SlockColor.StatusNeutralGray; SlockPresence.Unknown -> SlockColor.BrandYellow }

@Composable
private fun PixelAvatar(
    spec: PixelAvatarSpec,
    modifier: Modifier = Modifier,
    avatarSize: Dp = ChannelAvatarSize,
    borderWidth: Dp = SlockStroke.Thin
) {
    SlockPixelAvatarDrawCache.onCompose()
    if (isSlockNativePixelAvatarViewSupported()) {
        SlockNativePixelAvatar(
            spec = spec,
            modifier = modifier,
            avatarSize = avatarSize,
            borderWidth = borderWidth
        )
        return
    }
    // Non-Android hosts keep the shared Canvas fallback. The Android host registers a native
    // render view that consumes the same precomputed spans and caches the bitmap by spec/size.
    Canvas(modifier = modifier.size(width = avatarSize, height = avatarSize).border(borderWidth, SlockColor.BorderBlack)) {
        drawRect(spec.background)
        spec.spans.forEach { span ->
            val left = pixelAvatarGridEdge(span.startColumnIndex, size.width)
            val right = pixelAvatarGridEdge(span.startColumnIndex + span.columnCount, size.width)
            val top = pixelAvatarGridEdge(span.rowIndex, size.height)
            val bottom = pixelAvatarGridEdge(span.rowIndex + 1, size.height)
            drawRect(
                span.color,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top)
            )
        }
    }
}

private fun pixelAvatarGridEdge(index: Int, extent: Float): Float =
    when {
        index <= 0 -> 0f
        index >= PixelAvatarGridCellCount -> extent
        else -> (extent * index / PixelAvatarGridCellCount).roundToInt().toFloat()
    }

internal data class PixelAvatarSpec(
    val background: Color,
    val grid: List<String>,
    // Palette lookups resolved at construction. Defaults are the generated bundled table
    // (safe during SlockPixelAvatarDataKt class init: they are that file's own fields);
    // SlockPixelAvatarRuntime passes the downloaded document's own palette instead.
    val palette: Map<Char, Color> = PixelAvatarPalette,
    val paletteArgb: Map<Char, String> = PixelAvatarPaletteArgb,
    // Null means "use the bundled PixelAvatarKnownBackgroundArgb" — resolved lazily below,
    // NOT as a default argument, which would re-enter SlockHomeComponentsKt mid-init.
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
                            colorArgb = spanColorArgb ?: SlockColor.TextBlackArgb
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
    // class init, which SlockHomeComponentsKt's own init triggers — an eager
    // read here hits PixelAvatarKnownBackgroundArgb before it is assigned
    // (init cycle -> NPE in every unit test touching this file).
    val backgroundArgb: String by lazy(LazyThreadSafetyMode.NONE) {
        (backgroundArgbLookup ?: PixelAvatarKnownBackgroundArgb)[background] ?: SlockColor.BrandYellowArgb
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
// see SlockPixelAvatarData.kt (scripts/generate_pixel_avatars.mjs, task #352).

/**
 * #195/#199 diagnostic counter retained for avatar render churn. Android now routes pixel
 * avatars through a host bitmap cache; non-Android hosts keep the shared Canvas fallback.
 * Logging remains aggregate-only and does not include avatar content.
 */
private object SlockPixelAvatarDrawCache {
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
        // OHOS / JS see the side effect of counters updating but no log line is emitted
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
internal expect fun isSlockNativePixelAvatarViewSupported(): Boolean
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
    Color(0xFF1E1E1C) to 'C',
    Color(0xFF1E1E1C) to 'G',
    Color(0xFF1E1E1C) to 'P',
    Color(0xFF1E1E1C) to 'Y',
    Color(0xFF1E1E1C) to 'V',
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
        return SlockPixelAvatarRuntime.current.let { data ->
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
    get() = SlockPixelAvatarRuntime.current.let { data -> data.specs.keys.filterNot { it in data.reservedKeys } }
private fun String.removePrefixOrNull(prefix: String): String? = takeIf { it.startsWith(prefix) }?.substring(prefix.length)
private fun randomPixelAvatar(seed: String): PixelAvatarSpec {
    val random = PixelAvatarRandom(pixelHash(seed))
    val (background, fill) = PixelAvatarRandomOptions[(random.next() * PixelAvatarRandomOptions.size).toInt()]
    val grid = List(8) { val left = List(4) { if (random.next() < 0.45) fill else '_' }; (left + left.asReversed()).joinToString("") }
    return PixelAvatarSpec(background, grid)
}
private fun pixelHash(value: String): Int { var hash = 0; value.forEach { hash = (hash shl 5) - hash + it.code }; return hash }
private class PixelAvatarRandom(seed: Int) { private var value = seed; fun next(): Double { value += 1831565813; var mixed = (value xor (value ushr 15)) * (1 or value); mixed = (mixed + ((mixed xor (mixed ushr 7)) * (61 or mixed))) xor mixed; val unsigned = (mixed xor (mixed ushr 14)).toLong() and 0xffffffffL; return unsigned.toDouble() / 4294967296.0 } }

@Composable
private fun CountPill(text: String, color: Color) {
    HomeUnreadBadge(text = text, color = color)
}

@Composable
private fun HomeUnreadBadge(text: String, color: Color = SlockColor.AccentPink) {
    Box(
        modifier =
            Modifier
                .height(CountPillHeight)
                .widthIn(min = CountPillMinWidth)
                .borderRadius(HomeUnreadBadgeRadius)
                .background(color)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 10.sp, lineHeight = 10.sp),
            color = SlockColor.SurfaceWhite,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.offset(y = HomeUnreadBadgeTextOffsetY)
        )
    }
}
