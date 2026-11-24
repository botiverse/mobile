package build.raft.app

import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.components.raftSearchRowHover
import build.raft.designsystem.components.raftScrollEdgeFade
import build.raft.designsystem.components.raftScrollIntoViewGroup
import build.raft.designsystem.components.raftScrollIntoViewWhen
import build.raft.designsystem.components.rememberRaftScrollIntoViewGroup
import build.raft.app.components.RaftSlotIcon
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import build.raft.app.components.RaftAnchoredPickerTrigger
import build.raft.app.data.SearchSort
import build.raft.app.data.SearchTimeRange
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.typedPinnedOrLegacy
import build.raft.app.data.SharedHomeSearchPanelState
import build.raft.app.data.SharedHomeSearchSavedIntent
import build.raft.app.data.InboxItem
import build.raft.app.data.SettingsServerSidebarOrderSnapshot
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.collectIsPressedAsState
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import build.raft.app.data.map

// raft-ui search-entity leading slot: channels and computers take an IconTile `sm` (27dp
// `fill-muted`, 14dp `foreground-muted` icon); people and agents an avatar `sm` (27dp) with
// the 9dp activity square at the corner.
@Composable
private fun SharedHomeSearchEntityLeadingAvatar(
    result: SearchEntityResult,
    agentStatusResolver: RaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver
) {
    when (result.type) {
        SearchEntityType.Channel ->
            RaftIconTile(size = RaftIconTileSize.Sm) {
                RaftChannelKindIcon(
                    // A row of core's search carries its status: people from outside (`external ||
                    // guest`) draw two people even for a channel the app hasn't loaded; the globe and
                    // the Slack mark come from the Home row (web `SearchChannelIcon`).
                    result.homeSearchChannelIconGlyph(currentRaftChannelKinds()),
                    size = RaftIconTileSize.Sm.icon,
                    color = raftArgbHex(RaftColor.TextMuted),
                )
            }
        SearchEntityType.Computer ->
            RaftIconTile(size = RaftIconTileSize.Sm) {
                RaftLucideIcon(
                    result.homeSearchEntityLeadingIconName() ?: RaftLucideIconName.Hash,
                    color = raftArgbHex(RaftColor.TextMuted),
                    modifier = Modifier.size(RaftIconTileSize.Sm.icon)
                )
            }

        else ->
            run {
                val memberAvatarWireValue =
                    result.memberId
                        ?.takeIf { it.isNotBlank() }
                        ?.let { rememberRaftMemberAvatarWireValue(memberAvatarResolver, it) }
                    // An App Bot has no presence (web: no `AgentActivityDot` when `appBot`).
                    val appBot = rememberRaftMemberAppBot(result.appBotLookupMemberId())
                    val status =
                        if (result.showPresence && appBot == null) {
                            result.memberId
                                ?.takeIf { it.isNotBlank() }
                                ?.let {
                                    projectHomeAgentStatusIndicatorState(
                                        showPresence = true,
                                        memberId = it,
                                        canonicalState = rememberRaftAgentStatusIndicatorState(agentStatusResolver, it)
                                    )
                                }
                        } else {
                            null
                        }

                RaftAvatarWithStatus(
                    statusColor = status?.color,
                    avatarSize = RaftSearchMetrics.EntityLeading,
                ) {
                    RaftAppAvatar(
                        // web EntityResultRow: an agent's face (else the robot), a person's stored
                        // avatar (else the placeholder person), never a made-up one.
                        kind =
                            when (result.type) {
                                SearchEntityType.Agent -> RaftAvatarKind.Agent
                                SearchEntityType.Human -> RaftAvatarKind.Human
                                else -> raftAvatarKind(result.memberIdentity?.kind)
                            },
                        avatarWireValue = memberAvatarWireValue ?: result.avatarWireValue,
                        label = result.title.removePrefix("@").removePrefix("#"),
                        // task #107: must match the RaftAvatarWithStatus wrapper's
                        // avatarSize — the default (ChannelAvatarSize = 20.dp) leaves the
                        // avatar centered in its slot while the status dot anchors to
                        // the slot corner, floating outside the visible avatar.
                        avatarSize = RaftSearchMetrics.EntityLeading,
                        cacheSubject =
                            result.memberIdentity?.let { identity ->
                                raftMemberAvatarCacheSubject(identity.kind, identity.memberId.value)
                            },
                        cacheServerId = result.memberIdentity?.sourceServerId?.value
                    )
                }
            }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SharedHomeSearchFilters(
    state: SharedHomeSearchPanelState,
    channelFilters: List<SearchChannelFilterItem>,
    scopeAnchorChanged: (RaftPopoverAnchorResolver) -> Unit,
    senderAnchorChanged: (RaftPopoverAnchorResolver) -> Unit,
    channelAnchorChanged: (RaftPopoverAnchorResolver) -> Unit,
    timeAnchorChanged: (RaftPopoverAnchorResolver) -> Unit,
    sortAnchorChanged: (RaftPopoverAnchorResolver) -> Unit,
    onOpenFilter: (String, RaftPopoverAnchor) -> Unit,
    onIntent: (SharedHomeSearchSavedIntent) -> Unit,
    showSenderFilter: Boolean = true,
    horizontalPadding: Dp = SearchControlsHorizontalPadding,
    // web SearchFilterBar `scopeConversation`: a conversation's own search names its scope
    // ("#general", a DM's name) in a soft information badge where the Channel chip sits. It is
    // the page's scope, not a filter: nothing here clears it ("Search everywhere" is the way out).
    scopeConversation: String? = null,
    // The popup a chip opens under itself (the sender and channel comboboxes), composed in the
    // chip's own box so it anchors to it; given the filter id.
    chipPopover: @Composable (String) -> Unit = {}
) {
    val hasActiveFilters =
        state.selectedScopes.isNotEmpty() ||
            state.myMessagesOnly ||
            state.selectedSenderId != null ||
            (state.selectedChannelId != null && scopeConversation == null) ||
            state.timeRange != SearchTimeRange.Any
    val selectedChannel = channelFilters.firstOrNull { it.id == state.selectedChannelId }
    val scopeActive = state.selectedScopes.isNotEmpty()
    val senderActive = state.selectedSenderId != null
    val channelActive = state.selectedChannelId != null
    val timeActive = state.timeRange != SearchTimeRange.Any
    val sortActive = state.sort != SearchSort.Relevance
    val scopeLabel = stringResource(Res.string.search_filter_scope)
    val channelLabel = stringResource(Res.string.search_filter_channel)
    // raft-ui PickerTriggerButton: an applied filter (web `appliedFilterProps`) is the
    // `info-soft` wash. Web SearchFilterBar (raft-design responsive.md › The toolbar over a
    // list) is ONE line, 8dp gaps, 16dp in from the edges: the filter strip (From, Scope,
    // Channel, the time range) fills it and scrolls sideways with its labels kept, Clear all
    // pinned at the strip's end, the sort pinned at the line's end. A chip naming a person or
    // a channel is capped (web `max-w-60`): a long name ends in an ellipsis. The strip fades
    // over 24dp at an edge with chips hidden past it (raft-design spacing.md › Edges fade). An
    // applied filter is kept in view, 24dp clear of that edge (web `data-active`, FitToolbar
    // `useScrollGroup`): when Search opens with filters applied, the first of them; then the
    // one just applied.
    SharedHomeSearchControlsBlock {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .raftSafeAreaHorizontalPadding()
                .padding(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val chipModifier = Modifier.widthIn(max = SearchFilterNameChipMaxWidth)
            val stripScroll = rememberScrollState()
            val stripReveal = rememberRaftScrollIntoViewGroup(stripScroll)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .testTag("search-filter-strip")
                    .raftScrollIntoViewGroup(stripReveal)
                    .raftScrollEdgeFade(stripScroll)
                    .horizontalScroll(stripScroll),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                homeSearchFilterOrder(showSenderFilter).forEach { filter ->
                    key("search-filter-${filter.id}") {
                        when (filter) {
                            HomeSearchFilter.Sender ->
                                Box(chipModifier.raftScrollIntoViewWhen(senderActive, stripReveal)) {
                                    RaftAnchoredPickerTrigger(
                                        label = state.localizedSenderFilterLabel(),
                                        anchorIdentity = "search_filter:sender",
                                        hasValue = senderActive,
                                        iconName = RaftLucideIconName.CircleUser,
                                        onAnchorChanged = senderAnchorChanged,
                                        onClick = { anchor ->
                                            onOpenFilter("sender", anchor)
                                        }
                                    )
                                    chipPopover("sender")
                                }
                            HomeSearchFilter.Scope ->
                                RaftAnchoredPickerTrigger(
                                    modifier = chipModifier.raftScrollIntoViewWhen(scopeActive, stripReveal),
                                    label = if (scopeActive) "$scopeLabel ${state.selectedScopes.size}" else scopeLabel,
                                    anchorIdentity = "search_filter:scope",
                                    hasValue = scopeActive,
                                    iconName = RaftLucideIconName.AtSign,
                                    onAnchorChanged = scopeAnchorChanged,
                                    onClick = { anchor ->
                                        onOpenFilter("scope", anchor)
                                    }
                                )
                            HomeSearchFilter.Channel ->
                                if (scopeConversation != null) {
                                    RaftBadge(
                                        text = Res.string.search_scope_in_channel.format(listOf(scopeConversation)).localized(),
                                        modifier = chipModifier.testTag("search-scope-chip"),
                                        appearance = RaftBadgeAppearance.Soft,
                                        variant = RaftBadgeVariant.Information,
                                    )
                                } else Box(chipModifier.raftScrollIntoViewWhen(channelActive, stripReveal)) {
                                    RaftAnchoredPickerTrigger(
                                        label = selectedChannel?.label ?: channelLabel,
                                        anchorIdentity = "search_filter:channel",
                                        hasValue = channelActive,
                                        iconName = selectedChannel?.iconName ?: RaftLucideIconName.Hash,
                                        onAnchorChanged = channelAnchorChanged,
                                        onClick = { anchor ->
                                            onOpenFilter("channel", anchor)
                                        }
                                    )
                                    chipPopover("channel")
                                }
                            HomeSearchFilter.Time ->
                                RaftAnchoredPickerTrigger(
                                    modifier = chipModifier.raftScrollIntoViewWhen(timeActive, stripReveal),
                                    label = state.timeRange.localizedSearchLabel(),
                                    anchorIdentity = "search_filter:time",
                                    hasValue = timeActive,
                                    iconName = RaftLucideIconName.Calendar,
                                    onAnchorChanged = timeAnchorChanged,
                                    onClick = { anchor ->
                                        onOpenFilter("time", anchor)
                                    }
                                )
                        }
                    }
                }
            }
            if (hasActiveFilters) {
                // web SearchFilterBar: Clear all is a `ghost` `md` Button, not a picker chip.
                RaftButton(
                    text = stringResource(Res.string.search_filter_clear_all),
                    onClick = {
                        val scopeChannelId = state.selectedChannelId.takeIf { scopeConversation != null }
                        onIntent(SharedHomeSearchSavedIntent.ClearSearchFilters)
                        // The scope is not a filter: Clear all keeps the conversation.
                        scopeChannelId?.let { onIntent(SharedHomeSearchSavedIntent.SetSearchChannel(it)) }
                    },
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Md
                )
            }
            RaftAnchoredPickerTrigger(
                label = state.sort.localizedSearchLabel(),
                anchorIdentity = "search_filter:sort",
                hasValue = sortActive,
                iconName = RaftLucideIconName.ArrowDownUp,
                onAnchorChanged = sortAnchorChanged,
                onClick = { anchor ->
                    onOpenFilter("sort", anchor)
                }
            )
        }
    }
}

/** web SearchFilterBar `CHIP_MAX_WIDTH_CLASS` (`max-w-60`): a phone's strip shows the chip whole. */
private val SearchFilterNameChipMaxWidth = 240.dp

internal enum class HomeSearchFilter(val id: String) {
    Sender("sender"),
    Scope("scope"),
    Channel("channel"),
    Time("time"),
}

internal fun homeSearchFilterOrder(showSenderFilter: Boolean): List<HomeSearchFilter> =
    if (showSenderFilter) {
        listOf(HomeSearchFilter.Sender, HomeSearchFilter.Scope, HomeSearchFilter.Channel, HomeSearchFilter.Time)
    } else {
        listOf(HomeSearchFilter.Scope, HomeSearchFilter.Channel, HomeSearchFilter.Time)
    }

@Composable
private fun SharedHomeSearchControlsBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RaftColor.SurfaceWhite)
                .padding(top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

/**
 * Visual-fixture-only typography stress for the personalized Search home.
 *
 * Kuikly currently serializes TextStyle SP values without consuming an inner
 * LocalDensity fontScale override, so that override produced a byte-identical
 * 1.0x/1.3x screenshot. Scaling the real component styles here keeps the
 * production default byte-for-byte unchanged while exercising the actual
 * shared History/Frequent layout with larger glyph metrics on every renderer.
 */
internal fun TextStyle.withHomeSearchVisualTextScale(scale: Float): TextStyle {
    if (scale == 1f) return this
    val resolved = scale.coerceIn(1f, 2f)
    return copy(
        fontSize = fontSize * resolved,
        lineHeight = lineHeight * resolved,
        letterSpacing = letterSpacing * resolved,
    )
}

@Composable
internal fun SharedHomeSearchSectionLabel(
    label: String,
    visualTextScale: Float = 1f,
    count: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    RaftSearchSectionHeading(
        label = label,
        count = count,
        trailing = trailing,
        countStyle = RaftTheme.typography.monoMeta.withHomeSearchVisualTextScale(visualTextScale),
        style =
            RaftTheme.typography.footnote
                .copy(fontWeight = FontWeight.Bold)
                .withHomeSearchVisualTextScale(visualTextScale)
    )
}

/**
 * search-message `messageHeader` / search-thread `threadMeta`: `font-mono text-mono-meta`
 * (12/16) in `foreground-hint` for the channel, thread and time meta, nested hits included.
 */
internal fun sharedHomeSearchTimestampTextStyle() =
    RaftType.Caption.copy(
        fontFamily = RaftMonoFamily,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    )

// raft-ui search-entity: a flat list-cell row, with a finger `pointer-coarse:py-2` (min 56dp, so
// a title over its subtitle is the web's 62dp), under a mouse `py-3` (min 64dp), its content 20dp
// in, `gap-3` between a 27dp leading avatar / IconTile and the text; `text-body` semibold title
// with at most one mark ([homeSearchEntityBadge]), `text-callout` `foreground-muted` description;
// 2dp between rows, no line; pressed fills the cell shape, and so does a mouse over it (not while
// [selected]: the keyboard cursor or the row open beside the list, drawn by the caller). One
// tappable row, no Join: a channel the reader hasn't joined says "Not joined" and opens on its
// preview. An archived channel mutes its title and description.
@Composable
internal fun SharedHomeSearchEntityRow(
    result: SearchEntityResult,
    query: String,
    agentStatusResolver: RaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    isPinned: Boolean = false,
    showSeparator: Boolean = false,
    selected: Boolean = false,
    visualTextScale: Float = 1f,
    onOpen: () -> Unit,
    onContextMenu: ((RaftPopoverAnchor) -> Unit)? = null
) {
    val gestureKey = remember(result.key, isPinned, onContextMenu != null) { "search-entity:${result.key}:$isPinned" }
    val contentAnchor = remember(gestureKey) { RaftLiveRootAnchorRef(identity = gestureKey) }
    val gestureOwner =
        rememberRaftGestureOwner(
            surface = "home-search-entity",
            semanticIdentity = gestureKey,
            callbacks = SharedHomeSearchEntityGestureCallbacks(onOpen, onContextMenu, contentAnchor)
        )
    // Same delayed press as the message rows' clickable: a touch that becomes a scroll within
    // the tap timeout never flashes the pressed fill.
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val mutedArchived = result.archivedNow && result.type == SearchEntityType.Channel
    val typography = RaftTheme.typography
    val pointer = RaftTheme.pointerInput
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = RaftSearchMetrics.entityMinHeight(pointer))
                .raftSearchRowGap(showSeparator)
                .raftSearchRowPressed(pressed, RaftColor.FillPressed)
                .raftSearchRowHover(interactionSource, selected = selected, pressed = pressed)
                .raftCaptureLiveRootAnchor(contentAnchor)
                .raftGesturePointerInput(gestureOwner, interactionSource) { owner ->
                    var gesture: RaftGestureSession<SharedHomeSearchEntityGestureCallbacks>? = null
                    coroutineScope {
                        val pressScope = this
                        // raw-click: the row draws its own hover and press (raftSearchRowHover / raftSearchRowPressed) and owns tap + long press
                        detectTapGestures(
                            onPress = { offset ->
                                gesture = owner.beginGesture()
                                raftDelayedRowPress(offset, interactionSource, pressScope) { tryAwaitRelease() }
                            },
                            onTap = {
                                (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.onOpen()
                            },
                            onLongPress = { localOffset ->
                                (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.let { callbacks ->
                                    callbacks.onContextMenu?.let { callback ->
                                        callbacks.contentAnchor
                                            .resolve(RaftPopoverLocalPoint.Exact(localOffset))
                                            ?.let(callback)
                                    }
                                }
                            }
                        )
                    }
                }
                .raftSafeAreaHorizontalPadding()
                .padding(horizontal = RaftSearchMetrics.Inset, vertical = RaftSearchMetrics.entityPaddingVertical(pointer)),
        horizontalArrangement = Arrangement.spacedBy(RaftSearchMetrics.EntityGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SharedHomeSearchEntityLeadingAvatar(result, agentStatusResolver, memberAvatarResolver)
        // An App Bot is a Bot, not an Agent: the Bot badge takes the kind tag's place and its
        // subtitle drops the "Agent" kind word (web `searchEntities`: an agent's subtitle is its
        // `@handle`).
        val appBot = rememberRaftMemberAppBot(result.appBotLookupMemberId())
        val agentKindLabel = stringResource(Res.string.search_entity_agent)
        val shown = remember(result, appBot != null, agentKindLabel) {
            if (appBot != null) result.withoutAgentKindLabel(agentKindLabel) else result
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftSearchHitText(
                    text = result.title,
                    query = query,
                    style =
                        typography.body
                            .copy(fontWeight = FontWeight.SemiBold)
                            .withHomeSearchVisualTextScale(visualTextScale),
                    color = if (mutedArchived) RaftColor.ForegroundHint else RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // One mark at most (web EntityResultRow): an App Bot's is its Bot badge in the
                // outline look (web `AppBotBadge appearance="outline"`).
                if (appBot != null) {
                    RaftAppBotBadge(identity = appBot, botName = result.title, appearance = RaftBadgeAppearance.Outline)
                } else {
                    result.homeSearchEntityBadge()?.let { SharedHomeSearchEntityBadge(it) }
                }
            }
            // A channel says "Channel" (web `searchEntities` subtitle `{ kind: "channel" }`); one
            // matched by its description shows that instead, cut like a message snippet (web
            // `searchEntitySubtitleForQuery`): only when the description holds a hit.
            val channelLabel = stringResource(Res.string.search_entity_channel)
            if (shown.homeSearchNotJoined) {
                // web EntityResultRow `notJoined`: "Not joined" in ink, then what the channel is
                // about (the query's snippet when it hit the description), never the "Channel" label.
                val notJoined = stringResource(Res.string.search_not_joined)
                val description =
                    shown.description?.let { if (homeSearchHighlightRanges(it, query).isEmpty()) it else channelDescriptionSnippet(it, query) }
                val line = if (description == null) notJoined else "$notJoined · $description"
                RaftSearchHitText(
                    text = line,
                    query = query,
                    highlightRange = if (description == null) IntRange.EMPTY else (line.length - description.length) until line.length,
                    prefixLength = notJoined.length,
                    prefixStyle = SpanStyle(color = RaftColor.TextBlack),
                    style = typography.callout.withHomeSearchVisualTextScale(visualTextScale),
                    color = RaftColor.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(HomeSearchEntityGroupTags.NotJoined),
                )
            } else {
            val channelDescriptionHit =
                shown.type == SearchEntityType.Channel &&
                    shown.subtitleDataRange == null &&
                    homeSearchHighlightRanges(shown.subtitle, query).isNotEmpty()
            val subtitle =
                when {
                    channelDescriptionHit -> channelDescriptionSnippet(shown.subtitle, query)
                    shown.type == SearchEntityType.Channel -> channelLabel
                    else -> shown.subtitle
                }
            if (subtitle.isNotBlank() || appBot == null) RaftSearchHitText(
                text = subtitle,
                query = query,
                // Only real data (a handle, a description) is marked, never the "Channel" label.
                highlightRange =
                    when {
                        channelDescriptionHit -> null
                        shown.type == SearchEntityType.Channel -> IntRange.EMPTY
                        else -> shown.subtitleDataRange
                    },
                style = typography.callout.withHomeSearchVisualTextScale(visualTextScale),
                color = if (mutedArchived) RaftColor.ForegroundHint else RaftColor.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            }
        }
    }
}

private data class SharedHomeSearchEntityGestureCallbacks(
    val onOpen: () -> Unit,
    val onContextMenu: ((RaftPopoverAnchor) -> Unit)?,
    val contentAnchor: RaftLiveRootAnchorRef
)

internal data class SearchEntityPinTarget(
    val conversationId: String,
    val kind: String? = null,
    val id: String? = null
)

internal fun searchEntityPinTarget(result: SearchEntityResult): SearchEntityPinTarget? {
    if (result.archivedNow) return null
    val threadId = result.threadId?.trim()?.takeIf { it.isNotBlank() }
    val memberId = result.memberId?.trim()?.takeIf { it.isNotBlank() }
    return when (result.type) {
        SearchEntityType.Channel ->
            threadId?.let { SearchEntityPinTarget(conversationId = it, kind = "channel", id = it) }
        SearchEntityType.DirectMessage ->
            threadId?.let { SearchEntityPinTarget(conversationId = it) }
        SearchEntityType.Human ->
            memberId?.let {
                SearchEntityPinTarget(
                    conversationId = threadId ?: it,
                    kind = "human",
                    id = it
                )
            }
        SearchEntityType.Agent ->
            memberId?.let {
                SearchEntityPinTarget(
                    conversationId = threadId ?: it,
                    kind = "agent",
                    id = it
                )
            }
        SearchEntityType.Computer -> null
    }
}

internal fun SearchEntityResult.isPinnedBy(items: List<SettingsServerSidebarPinnedItem>): Boolean {
    val target = searchEntityPinTarget(this) ?: return false
    return items.any { item ->
        when {
            target.kind != null && target.id != null ->
                item.kind.equals(target.kind, ignoreCase = true) && item.id == target.id ||
                    item.kind.equals("channel", ignoreCase = true) && item.id == target.conversationId
            else ->
                item.id == target.conversationId ||
                    memberId?.let { member ->
                        item.id == member &&
                            (item.kind.equals("agent", ignoreCase = true) ||
                                item.kind.equals("human", ignoreCase = true))
                    } == true
        }
    }
}

internal fun SettingsServerSidebarOrderSnapshot?.pinnedChannelConversationIds(): Set<String> =
    this
        ?.typedPinnedOrLegacy()
        .orEmpty()
        .asSequence()
        .filter { it.kind.trim().equals("channel", ignoreCase = true) }
        .map { it.id.trim() }
        .filter { it.isNotBlank() }
        .toSet()

// web EntityResultRow's one mark: Archived `soft` `muted`; External (with the external icon on
// the tile), Agent and Guest in the outline look. No kind tag: the group heading says it.
@Composable
private fun SharedHomeSearchEntityBadge(badge: HomeSearchEntityBadge) {
    RaftBadge(
        text =
            when (badge) {
                HomeSearchEntityBadge.Archived -> stringResource(Res.string.channel_settings_status_archived)
                HomeSearchEntityBadge.External -> stringResource(Res.string.search_badge_external)
                HomeSearchEntityBadge.Agent -> stringResource(Res.string.search_entity_agent)
                HomeSearchEntityBadge.Guest -> stringResource(Res.string.search_badge_guest)
            },
        modifier = Modifier.testTag("${HomeSearchEntityGroupTags.Badge}-${badge.name.lowercase()}"),
        appearance = if (badge == HomeSearchEntityBadge.Archived) RaftBadgeAppearance.Soft else RaftBadgeAppearance.Outline,
        variant = if (badge == HomeSearchEntityBadge.Archived) RaftBadgeVariant.Muted else RaftBadgeVariant.Default
    )
}

// raft-ui search-thread: a flat block (no line, card or wash). The `px-5 pt-3` header holds a
// mono-meta `foreground-hint` line (Thread Badge, #channel, hit count, time at the end) over the
// `text-callout` semibold title; the hits follow as search-message rows. As the web's
// SearchMessageResultsSection: the badge is an `outline` Badge, the count says "1 hit" / "2 hits",
// and the title is the parent message as text, cut as the web cuts it ([homeSearchThreadTitle]),
// with no hit mark and no line limit (`threadTitle`): only the hits below mark the query.
@Composable
internal fun SharedHomeThreadResultGroup(
    group: HomeSearchResultGroup.Thread,
    query: String,
    showSeparator: Boolean = false,
    // The group is the row open beside the list: its hits don't hover.
    selected: Boolean = false,
    cacheServerId: String? = null,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    timeContext: AppShellThreadTimeContext? = null,
    onOpenItem: (InboxItem) -> Unit
) {
    val firstItem = group.items.firstOrNull()
    val sourceLabel = searchResultSourceLabel(firstItem ?: return)
    val latestTime = firstItem.latestActivityAt ?: firstItem.lastMessageAt
    val metaStyle = sharedHomeSearchTimestampTextStyle()
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .raftSearchRowGap(showSeparator)
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // threadHeader `pt-3 pb-0`, then threadMessages' `py-2` before the first hit.
                    .raftSafeAreaHorizontalPadding().padding(start = RaftSearchMetrics.Inset, top = 12.dp, end = RaftSearchMetrics.Inset, bottom = RaftSearchMetrics.ListPaddingVertical),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // web `<Badge appearance="outline">`: a hairline `line-hairline` edge, mono
                    // meta type in `foreground-muted`, the ThreadIcon at the badge's icon size
                    // (`[&_svg]:size-3.5`) in the same ink.
                    RaftBadge(
                        text = stringResource(Res.string.search_meta_thread),
                        appearance = RaftBadgeAppearance.Outline,
                        icon = { RaftSlotIcon(RaftLucideIconName.Thread) }
                    )
                    Text(
                        text = sourceLabel,
                        style = metaStyle,
                        color = RaftColor.ForegroundHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = homeSearchThreadHitsCount(group.items.size).localized(),
                        style = metaStyle,
                        color = RaftColor.ForegroundHint,
                        maxLines = 1
                    )
                    if (firstItem.hasDraft) {
                        SharedHomeDraftPencilIcon(compact = true)
                    }
                }
                if (!latestTime.isNullOrBlank()) {
                    RaftRelativeTimestampText(
                        value = latestTime,
                        timeContext = timeContext,
                        style = metaStyle,
                        color = RaftColor.ForegroundHint,
                        maxLines = 1
                    )
                }
            }
            RaftText(
                text = group.title,
                style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
                color = RaftColor.TextBlack
            )
        }
        group.items.forEachIndexed { index, item ->
            SharedHomeSearchMessageResultRow(
                item = item,
                query = query,
                showSeparator = index > 0,
                selected = selected,
                cacheServerId = cacheServerId,
                memberAvatarResolver = memberAvatarResolver,
                timeContext = timeContext,
                onOpen = { onOpenItem(item) }
            )
        }
        RaftSearchListEnd()
    }
}

/**
 * A thread result's hit count (web `search.hitsCount`): "1 hit", "2 hits", in each language's
 * plural forms (Russian's from files/plurals/ru.json).
 */
internal fun homeSearchThreadHitsCount(count: Int): StringDesc =
    (if (count == 1) Res.string.search_hits_count_one else Res.string.search_hits_count_other).format(count)

// raft-ui search-message: a flat list-cell row, `py-3` with its content 20dp in (8dp cell inset +
// 12dp padding), 2dp from the row above and no line between hits; pressed fills the cell shape, and
// so does a mouse over it (not while [selected], the caller's selection).
// The header line is mono-meta `foreground-hint` with the sender first (14dp avatar,
// `text-callout` bold `foreground`), then #channel, thread, and the time at the end; the body is `text-callout` `foreground-muted`, two lines, 2dp below.
@Composable
internal fun SharedHomeSearchMessageResultRow(
    item: InboxItem,
    query: String,
    showSeparator: Boolean = false,
    selected: Boolean = false,
    cacheServerId: String? = null,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    timeContext: AppShellThreadTimeContext? = null,
    onOpen: () -> Unit
) {
    val avatarCacheIdentity =
        remember(item, cacheServerId) { item.previewSenderAvatarCacheIdentity(cacheServerId) }
    val preview = item.searchHitPreviewText() ?: stringResource(Res.string.search_state_no_preview)
    // RFC 077 (web MessageHitRow): "Alice (sent by Friday)", text only.
    val sender = (item.latestActivitySenderName ?: item.lastMessageSenderName)?.let { delegatedPreviewSender(it, item.latestDelegatedBy) }
    val time = item.latestActivityAt ?: item.lastMessageAt
    val metaStyle = sharedHomeSearchTimestampTextStyle()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // Raw clickable below on purpose: the row draws its own press, inset in the list cell like
    // the other search rows (raftSearchRowPressed), which raftClickable's full-width wash can't.
    // web MessageHitRow (DS `.rf-hit`): the sender's 27dp avatar in its own column, then who /
    // where / thread / when on one line and a two-line excerpt under it, 12dp after the avatar.
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .raftSearchRowGap(showSeparator)
                .raftSearchRowPressed(pressed, RaftColor.FillPressed)
                .raftSearchRowHover(interactionSource, selected = selected, pressed = pressed)
                // raw-click: the row draws its own hover and press (raftSearchRowHover / raftSearchRowPressed)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onOpen)
                .raftSafeAreaHorizontalPadding().padding(horizontal = RaftSearchMetrics.Inset, vertical = RaftSearchMetrics.RowPaddingVertical),
        horizontalArrangement = Arrangement.spacedBy(RaftSearchMetrics.EntityGap),
        verticalAlignment = Alignment.Top
    ) {
        val senderLabel = sender?.takeIf { it.isNotBlank() }
        // web MessageHitRow AvatarSlot: a sender without an avatar shows the kind's default, the
        // robot for an agent and the person glyph on lavender for anyone else.
        val senderIsAgent = (item.latestActivitySenderKind ?: item.lastMessageSenderKind) == MemberKind.Agent
        RaftAppAvatar(
            kind = if (senderIsAgent) RaftAvatarKind.Agent else RaftAvatarKind.Human,
            avatarWireValue = item.resolvedSenderAvatarWireValueForPreview(memberAvatarResolver),
            label = senderLabel.orEmpty(),
            avatarSize = RaftSearchMetrics.EntityLeading,
            cacheSubject = avatarCacheIdentity?.subject,
            cacheServerId = avatarCacheIdentity?.serverId
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (senderLabel != null) {
                        RaftText(
                            text = senderLabel,
                            style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.Bold),
                            color = RaftColor.TextBlack,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 180.dp)
                        )
                    }
                    // web MessageHitRow: a hit nested in its thread still names its channel and
                    // says "thread", like a top-level one.
                    Text(
                        text = searchResultSourceLabel(item),
                        style = metaStyle,
                        color = RaftColor.ForegroundHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (item.kind == InboxItemKind.Thread) {
                        SharedHomeSearchThreadMeta(metaStyle)
                    }
                    if (item.hasDraft) {
                        SharedHomeDraftPencilIcon(compact = true)
                    }
                }
                if (!time.isNullOrBlank()) {
                    RaftRelativeTimestampText(
                        value = time,
                        timeContext = timeContext,
                        style = metaStyle,
                        color = RaftColor.ForegroundHint,
                        maxLines = 1
                    )
                }
            }
            if (item.latestRetracted) {
                // RFC 077 (web MessageHitRow `search-hit-retracted`): a retracted message shows the
                // muted retracted line, never its text.
                RaftText(
                    text = stringResource(Res.string.message_delegated_retracted),
                    style = RaftTheme.typography.callout,
                    color = RaftColor.ForegroundHint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                RaftSearchHitText(
                    text = preview,
                    query = query,
                    style = RaftTheme.typography.callout,
                    color = RaftColor.TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// search-message `messageThreadMeta`: a 12dp raft-ui ThreadIcon and "thread" (web
// `search.threadInline`, lower case in English) in the meta type.
@Composable
private fun SharedHomeSearchThreadMeta(style: TextStyle) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RaftLucideIcon(
            RaftLucideIconName.Thread,
            color = raftArgbHex(RaftColor.ForegroundHint),
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = stringResource(Res.string.search_meta_thread_inline),
            style = style,
            color = RaftColor.ForegroundHint,
            maxLines = 1
        )
    }
}

@Composable
internal fun SharedHomeDraftPencilIcon(
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    RaftLucideIcon(
        RaftLucideIconName.Pencil,
        modifier = modifier.size(if (compact) 10.dp else 12.dp),
        color = RaftColor.TextMutedGrayArgb
    )
}

private fun searchResultSourceLabel(item: InboxItem): String {
    val name =
        if (item.kind == InboxItemKind.Thread) {
            item.parentChannelName ?: item.channelName
        } else {
            item.channelName
        }
    if (item.kind == InboxItemKind.DirectMessage) return "@$name"
    // Agent Lounge v0: a hit in a Lounge (or a thread in one) names the Lounge, never `#<agent>-lounge`.
    val placeId = if (item.kind == InboxItemKind.Thread) item.parentChannelId ?: item.channelId else item.channelId
    return agentLoungeLabelOrNull(placeId.value) ?: "#$name"
}

/** Agent Lounge v0: a search scoped to a Lounge (no channel list holds it) reads "{Name}'s Lounge". */
internal fun agentLoungeSearchScopeOrNull(channelId: String?): SearchChannelFilterItem? {
    val id = channelId?.takeIf { it.isNotBlank() } ?: return null
    val label = agentLoungeLabelOrNull(id) ?: return null
    return SearchChannelFilterItem(id = id, label = label, icon = RaftLucideIconName.Users)
}

/** The raft-ui search hit spans; [RaftSearchHitText] draws the sticker behind them. */
internal fun homeSearchHighlightedAnnotatedString(
    text: String,
    query: String
): AnnotatedString = raftSearchHitAnnotatedString(text, homeSearchHighlightRanges(text, query))

/**
 * The distinct terms of [query] (web `searchHighlightTokens`), in query order. A leading "@" / "#"
 * only says what kind of thing is searched: "@remy" matches "Remy" and "@Remy" alike (the prefix
 * is taken from the text when it is there).
 */
internal fun homeSearchHighlightTokens(query: String): List<String> =
    query
        .trim()
        .split(Regex("\\s+"))
        .map { it.trim().trimStart('@', '#') }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }

/**
 * The search hit spans of [query]'s whitespace-separated terms in [text], case-insensitive,
 * longest term first, never overlapping, in text order. Ranges are indices into [text]
 * itself: matching compares char by char (`regionMatches(ignoreCase = true)`) rather than
 * searching `text.lowercase()`, whose length can differ from [text] ("İ" lowercases to two
 * chars). Hits that touch ("foo" + "bar" in "foobar") merge into one span, so they draw as
 * one sticker.
 */
internal fun homeSearchHighlightRanges(
    text: String,
    query: String,
    within: IntRange? = null
): List<IntRange> {
    if (within != null) {
        val from = within.first.coerceIn(0, text.length)
        val to = (within.last + 1).coerceIn(from, text.length)
        return homeSearchHighlightRanges(text.substring(from, to), query).map { (it.first + from)..(it.last + from) }
    }
    if (text.isBlank()) return emptyList()
    val terms = homeSearchHighlightTokens(query).sortedByDescending { it.length }
    if (terms.isEmpty()) return emptyList()

    val occupied = BooleanArray(text.length)
    val ranges = mutableListOf<IntRange>()
    terms.forEach { term ->
        var start = 0
        while (start + term.length <= text.length) {
            if (!text.regionMatches(start, term, 0, term.length, ignoreCase = true)) {
                start++
                continue
            }
            val endExclusive = start + term.length
            // A hit on a handle or channel takes its "@" / "#" ("@Remy" is one unit, as when the
            // query has it).
            val hitStart = if (!isHandleStart(text, start - 1)) start else start - 1
            if ((hitStart until endExclusive).none { occupied[it] }) {
                ranges += hitStart until endExclusive
                for (index in hitStart until endExclusive) {
                    occupied[index] = true
                }
            }
            start = endExclusive
        }
    }
    return mergeAbuttingHighlightRanges(ranges.sortedBy { it.first })
}

private fun Char.isHitPrefix(): Boolean = this == '@' || this == '#'

/** True when [index] is an "@" / "#" that opens a handle or channel: not glued to a preceding word ("a@b.com"). */
private fun isHandleStart(text: String, index: Int): Boolean =
    index >= 0 && text[index].isHitPrefix() && (index == 0 || !text[index - 1].isLetterOrDigit())

/** Joins sorted, non-overlapping ranges that touch (`a.last + 1 == b.first`). */
private fun mergeAbuttingHighlightRanges(sorted: List<IntRange>): List<IntRange> {
    if (sorted.size < 2) return sorted
    val merged = mutableListOf(sorted.first())
    for (range in sorted.drop(1)) {
        val last = merged.last()
        if (range.first <= last.last + 1) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
        } else {
            merged += range
        }
    }
    return merged
}

@Composable
internal fun SharedHomeLoadMoreRow(
    loading: Boolean,
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier =
            Modifier
                .padding(horizontal = SearchMessagesHorizontalPadding)
                .padding(top = 4.dp, bottom = 8.dp)
                .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        // raft-ui Button `md` (32dp).
        RaftButton(
            text = if (loading) stringResource(Res.string.common_loading) else label,
            onClick = onClick,
            variant = RaftButtonVariant.Default,
            size = RaftButtonSize.Md,
            enabled = !loading
        )
    }
}
