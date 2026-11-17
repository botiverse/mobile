package build.raft.app

import build.raft.app.data.isTaskConversation
import build.raft.app.data.formatTaskConversationRef
import build.raft.app.resources.*
import build.raft.app.markdown.markdownToPreviewText
import build.raft.designsystem.components.RaftConversationKindGlyph
import build.raft.designsystem.components.RaftConversationKindIcon
import build.raft.designsystem.components.RaftButton
import androidx.compose.ui.input.key.key
import build.raft.app.components.raftCardSurface
import build.raft.app.data.isRefreshing
import build.raft.app.data.SharedHomeSavedPanelState
import build.raft.app.data.SharedHomeSearchSavedIntent
import build.raft.app.data.InboxItem
import build.raft.app.data.savedMessageIdOrNull
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.avatarWireValueForAvatarSlot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import build.raft.app.components.RaftPullToRefreshBox
import build.raft.app.components.raftHeaderRefreshIndicatorVisible
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.map
import build.raft.app.data.map
import build.raft.designsystem.components.RaftEmptyState
import build.raft.designsystem.components.RaftEmptyStateDefaults

private val SavedUnsaveHitTargetSize = 44.dp

@Composable
internal fun SharedHomeSavedPanel(
    state: SharedHomeSavedPanelState,
    cacheServerId: String? = null,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    onIntent: (SharedHomeSearchSavedIntent) -> Unit,
    onClose: (() -> Unit)? = null,
    onOpenItem: (InboxItem) -> Unit,
    bottomContentPadding: Dp,
    includeTopSafeArea: Boolean = true,
    timeContext: AppShellThreadTimeContext? = null,
    shellScope: RaftAppShellScope = rememberRaftAppShellScope(),
    listState: LazyListState = rememberLazyListState(),
    onTitleClick: (() -> Unit)? = null,
    onCopyLink: (InboxItem) -> Unit = {},
    onCopyMarkdown: (InboxItem) -> Unit = {},
    // The rail's inline Saved (two-pane): the row whose conversation is open beside the list is
    // selected, and only while it stays open. Null (the pushed phone page) keeps the tapped row.
    inlineListSelection: RaftInlineListSelection? = null,
) {
    LaunchedEffect(Unit) {
        onIntent(SharedHomeSearchSavedIntent.OpenSaved)
    }
    var selectedItemKey by remember { mutableStateOf<String?>(null) }
    var activeMenu by remember { mutableStateOf<SharedHomeSavedContextMenuState?>(null) }
    LaunchedEffect(state.items) {
        val selected = selectedItemKey
        if (selected != null && state.items.none { it.key == selected }) {
            selectedItemKey = null
        }
        val menuItemKey = activeMenu?.item?.key
        if (menuItemKey != null && state.items.none { it.key == menuItemKey }) {
            activeMenu = null
        }
    }
    val projectedItems =
        remember(state.items, draftProjection) {
            state.items.map { item -> item.withComposerDraftProjection(draftProjection) }
        }
    val copyLinkLabel = stringResource(Res.string.saved_action_copy_link)
    val copyMarkdownLabel = stringResource(Res.string.saved_action_copy_markdown)
    val removeLabel = stringResource(Res.string.saved_action_remove)
    val contextMenuItems = remember(copyLinkLabel, copyMarkdownLabel, removeLabel) {
        listOf(
            RaftBaseMenuItem(SharedHomeSavedMenuActionCopyLink, copyLinkLabel, RaftLucideIconName.Link),
            RaftBaseMenuItem(SharedHomeSavedMenuActionCopyMarkdown, copyMarkdownLabel, RaftLucideIconName.Copy),
            RaftBaseMenuItem(
                SharedHomeSavedMenuActionRemove,
                removeLabel,
                // web SavedPanel: the menu's remove item draws BookmarkMinus, as the row's toggle does.
                RaftLucideIconName.BookmarkMinus,
                dividerBefore = true
            )
        )
    }
    if (state.loading && projectedItems.isEmpty()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(RaftColor.SurfaceWhite)
        ) {
            SharedHomeSavedHeader(
                count = state.totalCount.takeIf { it > 0 } ?: projectedItems.size,
                onClose = onClose,
                refreshing = state.refreshing || state.pullRefreshing,
                topSafeAreaPadding = shellScope.topInset(includeTopSafeArea),
                onTitleClick = onTitleClick
            )
            RaftAppShellBodyTopBanner()
            RaftCenteredLoading(modifier = Modifier.fillMaxWidth().weight(1f))
        }
        return
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(RaftColor.SurfaceWhite)
    ) {
        Column(Modifier.fillMaxSize()) {
            SharedHomeSavedHeader(
                count = state.totalCount.takeIf { it > 0 } ?: projectedItems.size,
                onClose = onClose,
                // artin task #22: the pull indicator below is the only chrome for a pulled refresh.
                refreshing = raftHeaderRefreshIndicatorVisible(state.refreshing, state.pullRefreshing),
                topSafeAreaPadding = shellScope.topInset(includeTopSafeArea),
                onTitleClick = onTitleClick
            )
            RaftAppShellBodyTopBanner()
            RaftPullToRefreshBox(
                isRefreshing = state.pullRefreshing,
                onRefresh = { onIntent(SharedHomeSearchSavedIntent.PullRefreshSaved) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(RaftColor.SurfaceWhite),
                    // web SavedPanel scroller `py-1`: plain rows stacked edge to edge.
                    contentPadding = PaddingValues(top = 4.dp)
                ) {
                    val hasSavedRows = projectedItems.isNotEmpty()
                    if (state.noPermissionCode != null || (state.errorCode != null && !hasSavedRows)) {
                        item {
                            SharedHomePanelStatus(
                                errorCode = state.errorCode,
                                noPermissionCode = state.noPermissionCode,
                                empty = false,
                                emptyTitle = "",
                                emptyDetail = "",
                                onRetry = { onIntent(SharedHomeSearchSavedIntent.RefreshSaved) }
                            )
                        }
                    } else if (state.empty || (state.loaded && projectedItems.isEmpty())) {
                        item {
                            SharedHomeSavedEmptyState()
                        }
                    }
                    items(projectedItems.size, key = { index -> "saved-${projectedItems[index].key}" }) { index ->
                        val item = projectedItems[index]
                        SharedHomeSavedResultCard(
                            item = item,
                            selected =
                                (inlineListSelection?.selectedRowKey ?: selectedItemKey) == item.key ||
                                    activeMenu?.item?.key == item.key,
                            cacheServerId = cacheServerId,
                            memberAvatarResolver = memberAvatarResolver,
                            onUnsave = { onIntent(SharedHomeSearchSavedIntent.Unsave(item)) },
                            onOpen = {
                                if (inlineListSelection == null) selectedItemKey = item.key
                                inlineListSelection.opening(item.key) { onOpenItem(item) }
                            },
                            onContextMenu = { anchor -> activeMenu = SharedHomeSavedContextMenuState(item, anchor) },
                            timeContext = timeContext
                        )
                    }
                    if (state.hasMore) {
                        item(key = "saved-load-more-sentinel") {
                            // web SavedPanel: an infinite-scroll sentinel (min 40dp, 12dp padding), not a
                            // button. Composing it means the list end is near, so the next page loads; a
                            // failed page keeps an explicit retry.
                            val latestOnIntent by rememberUpdatedState(onIntent)
                            val canRequest = !state.loadingMore && state.errorCode == null
                            LaunchedEffect(projectedItems.size, canRequest) {
                                if (canRequest) latestOnIntent(SharedHomeSearchSavedIntent.LoadMoreSaved)
                            }
                            if (state.errorCode != null && !state.loadingMore) {
                                SharedHomeLoadMoreRow(
                                    loading = false,
                                    label = stringResource(Res.string.saved_action_load_more),
                                    onClick = { onIntent(SharedHomeSearchSavedIntent.LoadMoreSaved) }
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (state.loadingMore) {
                                        Text(
                                            text = stringResource(Res.string.common_loading),
                                            style = RaftType.Caption,
                                            color = RaftColor.ForegroundHint
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(bottomContentPadding))
                    }
                }
            }
        }
        activeMenu?.let { menu ->
            RaftBaseMenuOverlay(
                anchor = menu.anchor,
                items = contextMenuItems,
                selectedId = "",
                onDismiss = { activeMenu = null },
                onSelect = { actionId ->
                    val item = menu.item
                    activeMenu = null
                    when (actionId) {
                        SharedHomeSavedMenuActionCopyLink -> onCopyLink(item)
                        SharedHomeSavedMenuActionCopyMarkdown -> onCopyMarkdown(item)
                        SharedHomeSavedMenuActionRemove -> onIntent(SharedHomeSearchSavedIntent.Unsave(item))
                    }
                }
            )
        }
    }
}

private const val SharedHomeSavedMenuActionCopyLink = "copy-link"
private const val SharedHomeSavedMenuActionCopyMarkdown = "copy-markdown"
private const val SharedHomeSavedMenuActionRemove = "remove"

internal enum class SharedHomeSavedGestureOwner {
    Open,
    Unsave
}

internal fun sharedHomeSavedGestureOwner(
    tapX: Float,
    rowWidthPx: Float,
    trailingActionWidthPx: Float
): SharedHomeSavedGestureOwner {
    if (rowWidthPx <= 0f || trailingActionWidthPx <= 0f) return SharedHomeSavedGestureOwner.Open
    val actionWidth = trailingActionWidthPx.coerceAtMost(rowWidthPx)
    return if (tapX >= rowWidthPx - actionWidth) {
        SharedHomeSavedGestureOwner.Unsave
    } else {
        SharedHomeSavedGestureOwner.Open
    }
}

private data class SharedHomeSavedContextMenuState(
    val item: InboxItem,
    val anchor: RaftPopoverAnchor
)

internal fun sharedHomeSavedPermalink(
    item: InboxItem,
    serverSlug: String,
    webBaseUrl: String? = null
): String? {
    val messageId = item.savedMessageIdOrNull()?.value?.takeIf { it.isNotBlank() } ?: return null
    val cleanServerSlug = serverSlug.trim().takeIf { it.isNotBlank() } ?: return null
    val cleanWebBaseUrl = sharedSettingsResolvedWebBaseUrl(webBaseUrl)
    // Task v2: the message lives in the task conversation itself (web SavedPanel `toMessage(channelId, …)`).
    if (item.isTaskConversation) return "$cleanWebBaseUrl/s/$cleanServerSlug/channel/${item.channelId.value}?msg=$messageId"
    val isThread = item.kind == InboxItemKind.Thread
    val channelForLink = if (isThread) item.parentChannelId ?: item.channelId else item.channelId
    val routeKind =
        if (if (isThread) item.parentChannelKind == ConversationKind.DirectMessage else item.kind == InboxItemKind.DirectMessage) {
            "dm"
        } else {
            "channel"
        }
    val params =
        if (isThread) {
            val parentMessageId = item.parentMessageId?.value?.takeIf { it.isNotBlank() }
            if (parentMessageId != null) {
                "msg=$messageId&thread=${channelForLink.value}%3A$parentMessageId"
            } else {
                "msg=$messageId"
            }
        } else {
            "msg=$messageId"
        }
    return "$cleanWebBaseUrl/s/$cleanServerSlug/$routeKind/${channelForLink.value}?$params"
}

/** Saved and search rows: the item's message Markdown read as one line; null when nothing is left. */
internal fun InboxItem.previewMarkdownText(): String? =
    (latestActivityPreview ?: lastMessagePreview ?: parentMessagePreview)
        ?.let(::markdownToPreviewText)
        ?.takeIf { it.isNotBlank() }

internal fun InboxItem.sharedHomeSavedMarkdownContent(): String =
    latestActivityPreview ?: lastMessagePreview ?: parentMessagePreview ?: ""

@Composable
private fun SharedHomeSavedEmptyState() {
    RaftEmptyState(
        title = stringResource(Res.string.saved_empty_title),
        description = stringResource(Res.string.saved_empty_detail),
        icon = { RaftContentLucideIcon(RaftLucideIconName.Bookmark, size = RaftEmptyStateDefaults.IconSize) },
        modifier =
            Modifier
                .fillMaxWidth()
                .height(300.dp)
    )
}

@Composable
private fun SharedHomeSavedHeader(
    count: Int,
    onClose: (() -> Unit)?,
    refreshing: Boolean,
    topSafeAreaPadding: Dp = 0.dp,
    onTitleClick: (() -> Unit)? = null
) {
    // web SavedPanel head: raft-ui PanelHeader with the phone Back, "N saved items".
    RaftPanelBackHeader(
        title = stringResource(Res.string.saved_title),
        subtitle = stringResource(if (count == 1) Res.string.saved_count_one else Res.string.saved_count, count),
        onBack = onClose,
        topSafeArea = topSafeAreaPadding,
        refreshing = refreshing,
        onTitleClick = onTitleClick,
    )
}

@Composable
private fun SharedHomePanelStatus(
    errorCode: String?,
    noPermissionCode: String?,
    empty: Boolean,
    emptyTitle: String,
    emptyDetail: String,
    onRetry: () -> Unit
) {
    val userFacingErrorLabels = threadUserFacingErrorLabels()
    when {
        noPermissionCode != null ->
            SharedHomePanelMessage(
                title = stringResource(Res.string.panel_error_no_permission),
                detail =
                    userFacingErrorLabels.forErrorCode(noPermissionCode)
                        ?: userFacingErrorLabels.generic(),
                tone = RaftColor.AccentRed,
                actionLabel = stringResource(Res.string.common_retry),
                onAction = onRetry
            )
        errorCode != null ->
            SharedHomePanelMessage(
                title = stringResource(Res.string.panel_error_load),
                detail =
                    userFacingErrorLabels.forErrorCode(errorCode)
                        ?: userFacingErrorLabels.generic(),
                tone = RaftColor.AccentRed,
                actionLabel = stringResource(Res.string.common_retry),
                onAction = onRetry
            )
        empty ->
            SharedHomePanelMessage(
                title = emptyTitle,
                detail = emptyDetail,
                tone = RaftColor.AppBgLightBlue
            )
    }
}

@Composable
private fun SharedHomePanelMessage(
    title: String,
    detail: String,
    tone: androidx.compose.ui.graphics.Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier =
            Modifier
                .padding(horizontal = SearchMessagesHorizontalPadding)
                .fillMaxWidth()
                .raftCardSurface(fill = tone.copy(alpha = 0.42f))
                .padding(RaftAppSpacing.Md),
        horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                style = RaftType.SectionLabel,
                color = RaftColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                detail,
                style = RaftType.Caption,
                color = RaftColor.TextMutedGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (actionLabel != null && onAction != null) {
            // raft-ui Button md: an action in a card.
            RaftButton(text = actionLabel, onClick = onAction)
        }
    }
}

@Composable
private fun SharedHomeSavedSelectedSurface(
    selected: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
    ) {
        // raft-ui ListRow `plain`: no frame; the open row keeps the fill-pressed wash.
        content(
            Modifier
                .fillMaxWidth()
                .background(if (selected) RaftColor.FillPressed else Color.Transparent)
        )
    }
}

@Composable
private fun SharedHomeSavedResultCard(
    item: InboxItem,
    selected: Boolean,
    cacheServerId: String? = null,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    onUnsave: () -> Unit,
    onOpen: () -> Unit,
    onContextMenu: (RaftPopoverAnchor) -> Unit,
    timeContext: AppShellThreadTimeContext? = null
) {
    val avatarCacheIdentity =
        remember(item, cacheServerId) { item.previewSenderAvatarCacheIdentity(cacheServerId) }
    val preview = item.previewMarkdownText() ?: stringResource(Res.string.saved_state_no_preview)
    val sender = item.latestActivitySenderName ?: item.lastMessageSenderName
    val time = item.latestActivityAt ?: item.lastMessageAt
    val openGestureKey = remember(item) { item.inboxItemOpenGestureKey() }
    val gestureGeneration = remember(item.key) { mutableStateOf(0L) }
    val gestureLogger = remember { raftLogger("SlockHomeSaved", "home_saved_gesture") }
    val savedMessageId = item.savedMessageIdOrNull()?.value.orEmpty()
    val source =
        when {
            item.kind == InboxItemKind.DirectMessage -> item.channelName.takeIf { it.isNotBlank() }?.let { "@$it" }
            // Task v2: a task conversation names its task by address (`#channel:#7`), as Activity does.
            item.isTaskConversation ->
                item.taskNumber?.let { formatTaskConversationRef(it, item.parentChannelName, item.parentChannelKind) }
                    ?: item.parentChannelName?.takeIf { it.isNotBlank() }?.let { "#$it" }
            item.kind == InboxItemKind.Thread ->
                agentLoungeLabelOrNull(item.parentChannelId?.value)
                    ?: item.parentChannelName?.takeIf { it.isNotBlank() }?.let { "#$it" }
            else -> agentLoungeLabelOrNull(item.channelId.value) ?: item.channelName.takeIf { it.isNotBlank() }?.let { "#$it" }
        }
    val contentAnchor = remember(openGestureKey) { RaftLiveRootAnchorRef(identity = openGestureKey) }
    fun dispatchSavedGesture(owner: SharedHomeSavedGestureOwner, source: String) {
        val generation = gestureGeneration.value + 1L
        gestureGeneration.value = generation
        gestureLogger(
            "owner=${owner.name.lowercase()} action=${owner.name.lowercase()} source=$source generation=$generation " +
                "itemKey=${item.key.take(80)} messageId=${savedMessageId.take(80)}"
        )
        when (owner) {
            SharedHomeSavedGestureOwner.Open -> onOpen()
            SharedHomeSavedGestureOwner.Unsave -> onUnsave()
        }
    }
    val gestureOwner =
        rememberRaftGestureOwner(
            surface = "home-saved-result",
            semanticIdentity = openGestureKey,
            callbacks =
                SharedHomeSavedResultGestureCallbacks(
                    onTap = { localOffset, rowWidthPx, trailingActionWidthPx ->
                        dispatchSavedGesture(
                            owner =
                                sharedHomeSavedGestureOwner(
                                    tapX = localOffset.x,
                                    rowWidthPx = rowWidthPx,
                                    trailingActionWidthPx = trailingActionWidthPx
                                ),
                            source = "tap"
                        )
                    },
                    onLongPress = { localOffset, rowWidthPx, trailingActionWidthPx ->
                        val owner =
                            sharedHomeSavedGestureOwner(
                                tapX = localOffset.x,
                                rowWidthPx = rowWidthPx,
                                trailingActionWidthPx = trailingActionWidthPx
                            )
                        val generation = gestureGeneration.value + 1L
                        gestureGeneration.value = generation
                        if (owner == SharedHomeSavedGestureOwner.Open) {
                            gestureLogger(
                                "owner=context_menu action=context_menu source=long_press generation=$generation " +
                                    "itemKey=${item.key.take(80)} messageId=${savedMessageId.take(80)}"
                            )
                            contentAnchor
                                .resolve(RaftPopoverLocalPoint.Exact(localOffset))
                                ?.let(onContextMenu)
                        } else {
                            gestureLogger(
                                "owner=ignored action=none source=long_press generation=$generation " +
                                    "reason=unsave_zone_long_press itemKey=${item.key.take(80)} " +
                                    "messageId=${savedMessageId.take(80)}"
                            )
                        }
                    }
                )
        )
    // web SavedPanel row: a plain raft-ui ListRow (16/12dp padding, no card frame); the
    // pressed / open row takes the fill-pressed wash.
    SharedHomeSavedSelectedSurface(
        selected = selected,
        modifier = Modifier.fillMaxWidth()
    ) { surfaceModifier ->
        Row(
            modifier =
                surfaceModifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .raftCaptureLiveRootAnchor(contentAnchor)
                    .raftGesturePointerInput(gestureOwner) { owner ->
                        val trailingActionWidthPx = SavedUnsaveHitTargetSize.toPx()
                        var gesture: RaftGestureSession<SharedHomeSavedResultGestureCallbacks>? = null
                        detectTapGestures(
                            onPress = {
                                gesture = owner.beginGesture()
                                tryAwaitRelease()
                            },
                            onTap = { localOffset ->
                                (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.onTap(
                                        localOffset,
                                        size.width.toFloat(),
                                        trailingActionWidthPx
                                    )
                            },
                            onLongPress = { localOffset ->
                                (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.onLongPress(
                                        localOffset,
                                        size.width.toFloat(),
                                        trailingActionWidthPx
                                    )
                            }
                        )
                    },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Meta line: `text-caption`, 8dp gaps: place, Thread, sender (14dp avatar), time.
                Row(
                    // One 16dp caption line (web `items-center text-caption`), 4dp above the preview.
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).height(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    source?.let {
                        Text(
                            text = it,
                            style = RaftType.Caption,
                            color = RaftColor.ForegroundHint,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    // A task conversation is not a reply thread (web SavedItem marks only `channelType: "thread"`).
                    if (item.kind == InboxItemKind.Thread && !item.isTaskConversation) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // web SavedPanel: raft-ui ThreadIcon (two bubbles) at 12px, as Activity marks a thread.
                            RaftConversationKindIcon(
                                RaftConversationKindGlyph.Thread,
                                size = 12.dp,
                                color = RaftColor.ForegroundHint
                            )
                            Text(
                                text = stringResource(Res.string.saved_meta_thread),
                                style = RaftType.Caption,
                                color = RaftColor.ForegroundHint,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                    if (item.hasDraft) {
                        SharedHomeDraftPencilIcon()
                    }
                    sender?.let {
                        SharedHomeSavedSenderMeta(
                            senderName = it,
                            senderKind = item.latestActivitySenderKind ?: item.lastMessageSenderKind,
                            avatarWireValue = item.resolvedSenderAvatarWireValueForPreview(memberAvatarResolver),
                            avatarCacheIdentity = avatarCacheIdentity,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    time?.let { timestamp ->
                        RaftRelativeTimestampText(
                            value = timestamp,
                            timeContext = timeContext,
                            style = RaftType.Caption.copy(fontFamily = RaftMonoFamily),
                            color = RaftColor.ForegroundHint,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                // `text-callout`, three lines.
                Text(
                    text = preview,
                    style = RaftType.Button.withRaftFontWeight(FontWeight.Normal),
                    color = RaftColor.TextBlack,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SharedHomeSavedUnsaveButton(
                onClick = { dispatchSavedGesture(SharedHomeSavedGestureOwner.Unsave, source = "accessibility") }
            )
        }
    }
}

private data class SharedHomeSavedResultGestureCallbacks(
    val onTap: (Offset, Float, Float) -> Unit,
    val onLongPress: (Offset, Float, Float) -> Unit
)

/**
 * web SavedPanel toggle: a pressed PanelToggleAction drawn quiet (no sticker fill) on a phone,
 * 44dp, with the outline 22dp BookmarkMinus in `foreground`. Every row is saved, so the toggle
 * is the remove action, drawn as the message menu draws it (icon standard v4: no filled icon
 * for a state), and named "Remove from Saved" (web `aria-label` `saved.remove`).
 */
@Composable
private fun SharedHomeSavedUnsaveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val removeLabel = stringResource(Res.string.saved_action_remove)
    Box(
        modifier =
            modifier
                .size(SavedUnsaveHitTargetSize)
                .zIndex(1f)
                .raftButtonAccessibility(enabled = true) { currentOnClick() }
                .semantics { contentDescription = removeLabel },
        contentAlignment = Alignment.Center
    ) {
        RaftLucideIcon(
            RaftLucideIconName.BookmarkMinus,
            color = RaftColor.TextBlackArgb,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * web SavedPanel sender: the 14dp `preview-mini` avatar, 4dp, the name in semibold ink. The
 * sender's kind decides the face without a photo ([RaftAvatarKind]): an agent's robot, a
 * person's placeholder, an external sender's initials.
 */
@Composable
private fun SharedHomeSavedSenderMeta(
    senderName: String,
    senderKind: MemberKind?,
    avatarWireValue: String?,
    avatarCacheIdentity: RaftMemberAvatarCacheIdentity? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RaftAppAvatar(
            kind = raftAvatarKind(senderKind),
            avatarWireValue = avatarWireValue,
            label = senderName,
            avatarSize = 14.dp,
            cacheSubject = avatarCacheIdentity?.subject,
            cacheServerId = avatarCacheIdentity?.serverId
        )
        Text(
            text = senderName,
            style = RaftType.Caption.withRaftFontWeight(FontWeight.SemiBold),
            color = RaftColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private data class InboxPreviewSenderIdentity(
    val kind: MemberKind,
    val memberId: String,
    val avatarWireValue: String?
)

private fun InboxItem.previewSenderIdentityOrNull(): InboxPreviewSenderIdentity? {
    val latestId = latestActivitySenderId?.trim()?.takeIf(String::isNotEmpty)
    if (latestActivitySenderKind != null && latestId != null) {
        return InboxPreviewSenderIdentity(
            kind = latestActivitySenderKind,
            memberId = latestId,
            avatarWireValue = latestActivitySenderAvatarWireValue
        )
    }
    val lastId = lastMessageSenderId?.trim()?.takeIf(String::isNotEmpty)
    if (lastMessageSenderKind != null && lastId != null) {
        return InboxPreviewSenderIdentity(
            kind = lastMessageSenderKind,
            memberId = lastId,
            avatarWireValue = lastMessageSenderAvatarWireValue
        )
    }
    return null
}

private fun InboxItem.senderAvatarWireValueForPreview(): String? {
    val identity = previewSenderIdentityOrNull()
    return if (identity != null) {
        avatarWireValueForAvatarSlot(
            kind = identity.kind,
            id = identity.memberId,
            avatarWireValue = identity.avatarWireValue
        )
    } else {
        latestActivitySenderAvatarWireValue ?: lastMessageSenderAvatarWireValue
    }
}

internal fun InboxItem.previewSenderAvatarCacheIdentity(
    cacheServerId: String?
): RaftMemberAvatarCacheIdentity? {
    val identity = previewSenderIdentityOrNull() ?: return null
    return raftMemberAvatarCacheIdentity(
        kind = identity.kind,
        memberId = identity.memberId,
        serverId = cacheServerId
    )
}

@Composable
internal fun InboxItem.resolvedSenderAvatarWireValueForPreview(
    memberAvatarResolver: RaftMemberAvatarResolver
): String? {
    val senderId = previewSenderMemberId()
    val memberAvatarWireValue = rememberRaftMemberAvatarWireValue(memberAvatarResolver, senderId)
    return memberAvatarWireValue ?: senderAvatarWireValueForPreview()
}

private fun InboxItem.previewSenderMemberId(): String? =
    previewSenderIdentityOrNull()?.memberId
