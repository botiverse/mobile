package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.base.Utils
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.components.RaftIconSpinner
import build.raft.app.components.RaftStorePullToRefreshBox
import build.raft.app.data.FeedbackAttachment
import build.raft.app.data.FeedbackAuthorType
import build.raft.app.data.FeedbackClosureReason
import build.raft.app.data.FeedbackComment
import build.raft.app.data.FeedbackErrorKind
import build.raft.app.data.FeedbackOpenedAttachment
import build.raft.app.data.FeedbackReporterCloseReason
import build.raft.app.data.FeedbackTicket
import build.raft.app.data.FeedbackTicketDetailState
import build.raft.app.data.FeedbackTicketFilter
import build.raft.app.data.FeedbackTicketKind
import build.raft.app.data.FeedbackTicketStatus
import build.raft.app.data.FeedbackTicketsListPhase
import build.raft.app.data.FeedbackTicketsListState
import build.raft.app.data.NetworkSettingsFeedbackTicketsRepository
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SettingsFeedbackTicketsState
import build.raft.app.data.SettingsFeedbackTicketsStore
import build.raft.app.data.feedbackPreviewLine
import build.raft.app.markdown.RaftMarkdownBody
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.civilDateFromEpochDay
import build.raft.app.model.floorDiv
import build.raft.app.model.floorMod
import build.raft.app.model.formatRaftClock
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftChoiceList
import build.raft.designsystem.components.RaftChoiceListItem
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftEmptyState
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftPanelNotice
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftStatusSize
import build.raft.designsystem.components.RaftStatusVariant
import build.raft.designsystem.components.RaftTab
import build.raft.designsystem.components.RaftTabs
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.io.encoding.Base64
import org.jetbrains.compose.resources.StringResource
import kotlin.io.encoding.ExperimentalEncodingApi

/*
 * Settings › Feedback (Android and iOS since 1.23, task #90; Compose desktop since 1.24): the
 * person's own feedback tickets, natively, in place of the web page's Hands FeedbackWorkspace
 * (`<web>/feedback/`, no longer opened by the App). Same reads and rules
 * (SettingsFeedbackTicketsStore), the same words (the Hands SDK's en / zh-CN copy). New feedback
 * opens the native submit form (SettingsHandsFeedbackPage), which files through the Raft server and
 * comes back to the new ticket. A server without the ticket routes shows the list's error state
 * with Retry and the failed read's trace ID.
 */

const val SETTINGS_FEEDBACK_TICKETS_PAGE = "compose_settings_feedback_tickets"

/** pageData key: the ticket to open on (a done feedback card's "View in Settings"); identity only. */
internal const val SETTINGS_FEEDBACK_TICKETS_TICKET_ID = "settingsFeedbackTicketsTicketId"

@Page(SETTINGS_FEEDBACK_TICKETS_PAGE, supportInLocal = true)
internal class ComposeSettingsFeedbackTicketsPage :
    AuthenticatedProductPager(AuthenticatedProductRoute.SettingsFeedbackTickets) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { SettingsFeedbackTicketsRoute(raftPageVisibleResumeTick) }
    }
}

internal fun settingsFeedbackTicketsPageData(storeScope: RaftShellStoreScope, ticketId: String? = null): JSONObject =
    JSONObject().apply {
        put("pageName", SETTINGS_FEEDBACK_TICKETS_PAGE)
        put(RAFT_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
        storeScope.initialServerId?.let { put(RAFT_SHELL_SERVER_ID, it) }
        feedbackTicketIdOrNull(ticketId)?.let { put(SETTINGS_FEEDBACK_TICKETS_TICKET_ID, it) }
    }

internal fun Pager.openSettingsFeedbackTicketsPage(storeScope: RaftShellStoreScope, ticketId: String? = null) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
        SETTINGS_FEEDBACK_TICKETS_PAGE,
        settingsFeedbackTicketsPageData(storeScope, ticketId),
    )
}

/**
 * Settings › Feedback, from every entry (the Settings row, rail Help › Feedback, a done feedback
 * card's "View in Settings", an agent issue's "View Ticket"): the native ticket list, on [ticketId]
 * when one is named, on every platform.
 */
internal fun Pager.openSettingsFeedbackPage(storeScope: RaftShellStoreScope, ticketId: String? = null) {
    val (pageName, pageData) = settingsFeedbackPageRequest(storeScope, ticketId)
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(pageName, pageData)
}

/** The page [openSettingsFeedbackPage] opens: the native ticket list (on [ticketId] when it is one). */
internal fun settingsFeedbackPageRequest(storeScope: RaftShellStoreScope, ticketId: String? = null): Pair<String, JSONObject> =
    SETTINGS_FEEDBACK_TICKETS_PAGE to settingsFeedbackTicketsPageData(storeScope, ticketId)

@Composable
private fun SettingsFeedbackTicketsRoute(resumeTick: Int) {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val storeScope =
        remember(pager.pageData.params.toString(), pager.pagerId) {
            pager.pageData.params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
        }
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val networkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val scope = rememberCoroutineScope()
    val store =
        remember(networkClient, scope) {
            SettingsFeedbackTicketsStore(
                repository = NetworkSettingsFeedbackTicketsRepository(networkClient, raftFeedbackWebViewClientKind(), bridge::log),
                scope = scope,
                logger = bridge::log,
            )
        }
    val initialTicketId = remember(pager.pageData.params) { feedbackTicketIdOrNull(pager.pageData.params.optString(SETTINGS_FEEDBACK_TICKETS_TICKET_ID)) }
    LaunchedEffect(store) { store.start(initialTicketId) }
    // Back from the submit form (or the app) reads the list again, and opens a ticket just filed.
    LaunchedEffect(store, resumeTick) {
        if (resumeTick > 0) {
            store.refresh()
            SettingsFeedbackFiledTicketHandoff.take()?.let { ticketId ->
                bridge.log("settings_feedback_tickets_open_filed ticket=${ticketId.take(8)}")
                store.openTicket(ticketId)
            }
        }
    }
    val state by store.state.collectAsState()
    val router = remember(pager) { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME) }
    val timeContext = LocalRaftPageBaseTimeContextProvider.current?.current
    val openViewer by rememberUpdatedState<(FeedbackOpenedAttachment) -> Unit>({ opened ->
        val payload = opened.toFeedbackImagePreviewPayload()
        if (payload == null || !pager.openRaftFilePreviewPage(payload, sourceStoreScope = storeScope)) {
            bridge.logWarn("settings_feedback_tickets_attachment_viewer_rejected")
        }
    })
    SettingsFeedbackTicketsScreen(
        state = state,
        timeContext = timeContext,
        actions =
            SettingsFeedbackTicketsActions(
                onBack = { if (store.state.value.detail != null) store.closeTicketView() else router.closePage() },
                onNewFeedback = {
                    bridge.log("settings_feedback_tickets_new_feedback")
                    pager.openSettingsHandsFeedbackPage(storeScope, returnToTickets = true)
                },
                onRefresh = store::refresh,
                onRetryList = store::retryList,
                onLoadMore = store::loadMore,
                onFilter = store::setFilter,
                onOpenTicket = store::openTicket,
                onRetryTicket = store::retryTicket,
                onRefreshTicket = store::refreshTicket,
                onLoadMoreReplies = store::loadMoreReplies,
                onReplyChange = store::setReplyDraft,
                onSendReply = store::sendReply,
                onCloseTicket = store::closeTicket,
                onClearCloseError = store::clearCloseError,
                onOpenAttachment = { attachment -> store.openAttachment(attachment) { openViewer(it) } },
                onDismissAttachmentError = store::dismissAttachmentError,
            ),
        bottomContentPadding = raftBottomSafeAreaPadding(),
    )
}

/** What the page's controls do. */
internal class SettingsFeedbackTicketsActions(
    val onBack: () -> Unit = {},
    val onNewFeedback: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onRetryList: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onFilter: (FeedbackTicketFilter) -> Unit = {},
    val onOpenTicket: (String) -> Unit = {},
    val onRetryTicket: () -> Unit = {},
    val onRefreshTicket: () -> Unit = {},
    val onLoadMoreReplies: () -> Unit = {},
    val onReplyChange: (String) -> Unit = {},
    val onSendReply: () -> Unit = {},
    val onCloseTicket: (FeedbackReporterCloseReason) -> Unit = {},
    val onClearCloseError: () -> Unit = {},
    val onOpenAttachment: (FeedbackAttachment) -> Unit = {},
    val onDismissAttachmentError: () -> Unit = {},
)

/**
 * The reply field under a ticket. Production uses the conversation composer (its keyboard lift,
 * as the submit form does); tests may pass a plain field.
 */
internal typealias SettingsFeedbackReplyComposer =
    @Composable (draft: String, enabled: Boolean, sending: Boolean, error: String?, onChange: (String) -> Unit, onSend: () -> Unit) -> Unit

@Composable
internal fun SettingsFeedbackTicketsScreen(
    state: SettingsFeedbackTicketsState,
    timeContext: RaftTimeContext?,
    actions: SettingsFeedbackTicketsActions,
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
    replyComposer: SettingsFeedbackReplyComposer = { draft, enabled, sending, error, onChange, onSend ->
        SettingsFeedbackThreadReplyComposer(draft, enabled, sending, error, onChange, onSend, bottomContentPadding)
    },
) {
    val detail = state.detail
    var closeDialogOpen by remember(detail?.ticketId) { mutableStateOf(false) }
    val openCloseDialog = {
        actions.onClearCloseError()
        closeDialogOpen = true
    }
    RaftBackHandler(enabled = detail != null, label = "settings_feedback_ticket", blocksBackSwipe = false) { actions.onBack() }
    val ticket = detail?.ticket
    SettingsDetailShell(
        title = ticket?.title?.ifBlank { null } ?: stringResource(Res.string.settings_feedback_title),
        subtitle = "",
        iconName = if (detail == null) RaftLucideIconName.MessageSquarePlus else null,
        onBack = actions.onBack,
        titleContent = if (detail != null && ticket != null) {
            { FeedbackTicketHeadTitle(ticket) }
        } else {
            null
        },
        trailingContent =
            if (detail != null && detail.canClose && ticket?.status != FeedbackTicketStatus.Resolved) {
                {
                    RaftHeaderIconAction(
                        icon = RaftLucideIconName.CircleCheck,
                        contentDescription = stringResource(Res.string.settings_feedback_tickets_close_ticket),
                        onClick = openCloseDialog,
                    )
                }
            } else {
                null
            },
    ) {
        if (detail == null) {
            FeedbackTicketList(state.list, timeContext, actions, bottomContentPadding)
        } else {
            FeedbackTicketDetail(detail, timeContext, actions, replyComposer, bottomContentPadding, onOpenCloseDialog = openCloseDialog)
        }
    }
    if (detail != null && closeDialogOpen) {
        // A finished close shuts the dialog.
        LaunchedEffect(detail.ticket?.status) { if (detail.ticket?.status == FeedbackTicketStatus.Closed) closeDialogOpen = false }
        if (detail.canClose) {
            FeedbackCloseDialog(
                initialReason = if (ticket?.status == FeedbackTicketStatus.Resolved) FeedbackReporterCloseReason.Completed else FeedbackReporterCloseReason.NoLongerNeeded,
                closing = detail.closing,
                error = detail.closeError,
                onConfirm = actions.onCloseTicket,
                onDismiss = { if (!detail.closing) closeDialogOpen = false },
            )
        }
    }
}

@Composable
private fun RowScope.FeedbackTicketHeadTitle(ticket: FeedbackTicket) {
    Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RaftText(
            ticket.title,
            style = RaftTheme.typography.headline.copy(fontWeight = FontWeight.SemiBold),
            color = RaftTheme.colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        RaftText(
            feedbackTicketMetaLine(ticket),
            style = RaftTheme.typography.footnote,
            color = RaftTheme.colors.foregroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.width(8.dp))
    FeedbackStatusChip(ticket)
}

/** "Bug · Closed as Completed" (web PanelMeta). */
@Composable
private fun feedbackTicketMetaLine(ticket: FeedbackTicket): String {
    val kind = stringResource(if (ticket.kind == FeedbackTicketKind.Bug) Res.string.settings_feedback_tickets_kind_bug else Res.string.settings_feedback_tickets_kind_idea)
    val reason = ticket.closureReason?.takeIf { ticket.status == FeedbackTicketStatus.Closed } ?: return kind
    return "$kind · " + stringResource(Res.string.settings_feedback_tickets_closed_reason, stringResource(reason.labelResource()))
}

private fun FeedbackClosureReason.labelResource(): StringResource =
    when (this) {
        FeedbackClosureReason.Completed -> Res.string.settings_feedback_tickets_reason_completed
        FeedbackClosureReason.NoLongerNeeded -> Res.string.settings_feedback_tickets_reason_no_longer_needed
        FeedbackClosureReason.NotPlanned -> Res.string.settings_feedback_tickets_reason_not_planned
        FeedbackClosureReason.CannotReproduce -> Res.string.settings_feedback_tickets_reason_cannot_reproduce
        FeedbackClosureReason.Duplicate -> Res.string.settings_feedback_tickets_reason_duplicate
    }

/** Web FeedbackStatusChip: a soft badge with a status light; closed as completed reads Resolved. */
@Composable
internal fun FeedbackStatusChip(ticket: FeedbackTicket) {
    val status = ticket.displayStatus
    val (badge, light, label) =
        when (status) {
            FeedbackTicketStatus.Open -> Triple(RaftBadgeVariant.Warning, RaftStatusVariant.Warning, Res.string.settings_feedback_tickets_status_open)
            FeedbackTicketStatus.InProgress -> Triple(RaftBadgeVariant.Information, RaftStatusVariant.Information, Res.string.settings_feedback_tickets_status_in_progress)
            FeedbackTicketStatus.Resolved -> Triple(RaftBadgeVariant.Success, RaftStatusVariant.Success, Res.string.settings_feedback_tickets_status_resolved)
            FeedbackTicketStatus.Closed -> Triple(RaftBadgeVariant.Muted, RaftStatusVariant.Muted, Res.string.settings_feedback_tickets_status_closed)
        }
    RaftBadge(
        text = stringResource(label),
        appearance = RaftBadgeAppearance.Soft,
        variant = badge,
        icon = { RaftStatusDot(variant = light, size = RaftStatusSize.Sm) },
    )
}

@Composable
private fun FeedbackTicketList(
    list: FeedbackTicketsListState,
    timeContext: RaftTimeContext?,
    actions: SettingsFeedbackTicketsActions,
    bottomContentPadding: androidx.compose.ui.unit.Dp,
) {
    Column(Modifier.fillMaxSize()) {
        // One row: the status tabs once there is something to filter, and the one pink button (web).
        Row(
            Modifier.fillMaxWidth().background(RaftTheme.colors.layerCanvas).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (list.tickets.isNotEmpty()) {
                RaftTabs(Modifier.weight(1f)) {
                    FeedbackTicketFilter.entries.forEach { filter ->
                        RaftTab(
                            text = stringResource(filter.labelResource()),
                            selected = list.filter == filter,
                            onClick = { actions.onFilter(filter) },
                        )
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            RaftButton(
                text = stringResource(Res.string.settings_feedback_tickets_new_feedback),
                onClick = actions.onNewFeedback,
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Md,
                leadingIcon = { RaftLucideIcon(RaftLucideIconName.Plus, modifier = Modifier.size(16.dp)) },
            )
        }
        RaftStorePullToRefreshBox(
            refreshing = list.refreshing,
            onRefresh = actions.onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag(SettingsFeedbackTicketsListTag),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottomContentPadding + 16.dp),
            ) {
                // An error over rows already shown (a refresh, Load More) is a banner; the rows stay.
                if (list.phase == FeedbackTicketsListPhase.Ready && list.error != null) {
                    item(key = "error") {
                        FeedbackErrorBanner(list.error, list.retrying, actions.onRetryList, Modifier.padding(16.dp), traceId = list.errorTraceId)
                    }
                }
                when (list.phase) {
                    FeedbackTicketsListPhase.Loading ->
                        item(key = "loading") {
                            RaftCenteredLoading(
                                message = stringResource(Res.string.settings_feedback_tickets_loading),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            )
                        }
                    FeedbackTicketsListPhase.Failed ->
                        item(key = "failed") {
                            RaftEmptyState(
                                title = stringResource(list.error.messageResource()),
                                actions = {
                                    FeedbackRetryWithTraceId(list.retrying, actions.onRetryList, list.errorTraceId)
                                },
                            )
                        }
                    FeedbackTicketsListPhase.Ready -> {
                        val visible = list.visibleTickets
                        if (visible.isEmpty()) {
                            item(key = "empty") {
                                val (title, body) = list.emptyKind.emptyResources()
                                RaftEmptyState(title = stringResource(title), description = stringResource(body))
                            }
                        }
                        items(visible, key = { it.id }) { ticket ->
                            FeedbackTicketRow(ticket, timeContext, onClick = { actions.onOpenTicket(ticket.id) })
                        }
                        if (list.nextCursor != null) {
                            item(key = "more") {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    RaftButton(
                                        text = stringResource(if (list.loadingMore) Res.string.settings_feedback_tickets_loading_more else Res.string.settings_feedback_tickets_load_more),
                                        onClick = actions.onLoadMore,
                                        enabled = !list.loadingMore,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal const val SettingsFeedbackTicketsListTag = "settings_feedback_tickets_list"
internal const val SettingsFeedbackTicketThreadTag = "settings_feedback_ticket_thread"

private fun FeedbackTicketFilter.labelResource(): StringResource =
    when (this) {
        FeedbackTicketFilter.All -> Res.string.settings_feedback_tickets_filter_all
        FeedbackTicketFilter.Active -> Res.string.settings_feedback_tickets_filter_active
        FeedbackTicketFilter.Ended -> Res.string.settings_feedback_tickets_filter_ended
    }

private fun FeedbackTicketFilter.emptyResources(): Pair<StringResource, StringResource> =
    when (this) {
        FeedbackTicketFilter.All -> Res.string.settings_feedback_tickets_empty_all_title to Res.string.settings_feedback_tickets_empty_all_body
        FeedbackTicketFilter.Active -> Res.string.settings_feedback_tickets_empty_active_title to Res.string.settings_feedback_tickets_empty_active_body
        FeedbackTicketFilter.Ended -> Res.string.settings_feedback_tickets_empty_ended_title to Res.string.settings_feedback_tickets_empty_ended_body
    }

/** The web SDK's error copy for each transport error. */
internal fun FeedbackErrorKind?.messageResource(): StringResource =
    when (this) {
        FeedbackErrorKind.Conflict -> Res.string.settings_feedback_tickets_error_conflict
        FeedbackErrorKind.Invalid -> Res.string.settings_feedback_tickets_error_invalid
        FeedbackErrorKind.NotFound -> Res.string.settings_feedback_tickets_error_not_found
        FeedbackErrorKind.RateLimited -> Res.string.settings_feedback_tickets_error_rate_limited
        FeedbackErrorKind.Unauthorized -> Res.string.settings_feedback_tickets_error_unauthorized
        FeedbackErrorKind.Unavailable, FeedbackErrorKind.NotSupported, null -> Res.string.settings_feedback_tickets_error_unavailable
    }

/** An error state's Retry, with the failed read's trace ID under it (AGENTS.md "Errors carry the trace ID"). */
@Composable
private fun FeedbackRetryWithTraceId(retrying: Boolean, onRetry: () -> Unit, traceId: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RaftButton(
            text = stringResource(Res.string.settings_feedback_tickets_retry),
            onClick = onRetry,
            loading = retrying,
        )
        RaftTraceIdNote(traceId)
    }
}

@Composable
private fun FeedbackErrorBanner(
    kind: FeedbackErrorKind?,
    retrying: Boolean,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    traceId: String? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FeedbackErrorBannerBody(kind, retrying, onRetry)
        RaftTraceIdNote(traceId)
    }
}

@Composable
private fun FeedbackErrorBannerBody(kind: FeedbackErrorKind?, retrying: Boolean, onRetry: (() -> Unit)?) {
    RaftBanner(
        description = stringResource(kind.messageResource()),
        status = RaftBannerStatus.Warning,
        size = RaftBannerSize.Sm,
        action =
            onRetry?.let { retry ->
                {
                    RaftButton(
                        text = stringResource(Res.string.settings_feedback_tickets_retry),
                        onClick = retry,
                        loading = retrying,
                        enabled = !retrying,
                    )
                }
            },
    )
}

/** Web FeedbackTicketRow: kind and first line, the latest comment, status / replies / new, the time. */
@Composable
private fun FeedbackTicketRow(ticket: FeedbackTicket, timeContext: RaftTimeContext?, onClick: () -> Unit) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val newCount = ticket.newCount
    val title = ticket.title
    val label =
        if (newCount > 0) stringResource(Res.string.settings_feedback_tickets_unread_label, title, newCount) else title
    Row(
        Modifier
            .fillMaxWidth()
            .raftClickable(onClick = onClick, onClickLabel = title)
            .semantics(mergeDescendants = false) { contentDescription = label }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            RaftLucideIcon(
                if (ticket.kind == FeedbackTicketKind.Bug) RaftLucideIconName.Bug else RaftLucideIconName.Sparkles,
                modifier = Modifier.size(18.dp),
                color = raftArgbHex(colors.foregroundMuted),
            )
            if (ticket.unread) {
                RaftStatusDot(
                    modifier = Modifier.align(Alignment.TopEnd),
                    variant = RaftStatusVariant.Accent,
                    size = RaftStatusSize.Xs,
                    contentDescription = null,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RaftText(
                    title,
                    modifier = Modifier.weight(1f),
                    style = typography.subhead.copy(fontWeight = if (ticket.unread) FontWeight.Bold else FontWeight.SemiBold),
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                feedbackListTimeLabel(ticket.activityAt, timeContext)?.let { time ->
                    Spacer(Modifier.width(8.dp))
                    RaftText(time, style = typography.footnote, color = colors.foregroundMuted, maxLines = 1)
                }
            }
            val latest = ticket.latestComment
            val preview = latest?.let { feedbackPreviewLine(it.bodyPreview) }.orEmpty()
            if (latest != null && preview.isNotEmpty()) {
                RaftText(
                    "${stringResource(latest.authorType.senderResource())}: $preview",
                    style = typography.callout,
                    color = colors.foregroundMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FeedbackStatusChip(ticket)
                RaftBadge(
                    text = feedbackReplyCountLabel(ticket.commentCount),
                    appearance = RaftBadgeAppearance.Outline,
                    variant = RaftBadgeVariant.Muted,
                )
                if (newCount > 0) {
                    RaftBadge(
                        text = stringResource(Res.string.settings_feedback_tickets_new_count, if (newCount > 99) "99+" else newCount.toString()),
                        appearance = RaftBadgeAppearance.Solid,
                        variant = RaftBadgeVariant.Accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun feedbackReplyCountLabel(count: Int): String =
    when (count) {
        0 -> stringResource(Res.string.settings_feedback_tickets_replies_none)
        1 -> stringResource(Res.string.settings_feedback_tickets_replies_one)
        else -> stringResource(Res.string.settings_feedback_tickets_replies_other, count)
    }

private fun FeedbackAuthorType.senderResource(): StringResource =
    when (this) {
        FeedbackAuthorType.Reporter -> Res.string.settings_feedback_tickets_sender_you
        FeedbackAuthorType.Staff -> Res.string.settings_feedback_tickets_sender_team
        FeedbackAuthorType.System -> Res.string.settings_feedback_tickets_sender_update
    }

/** The list's time (web formatListTime): the clock today, "Yesterday", else the date. */
internal sealed interface FeedbackListTime {
    data class Clock(val text: String) : FeedbackListTime

    data object Yesterday : FeedbackListTime

    data class Date(val month: Int, val day: Int, val year: Int?) : FeedbackListTime
}

internal fun feedbackListTime(epochMillis: Long, timeContext: RaftTimeContext?): FeedbackListTime? {
    val context = timeContext ?: return null
    val offset = context.localOffsetMinutes
    val today = context.todayEpochDay ?: context.nowEpochMillis?.let { floorDiv(it + offset * 60_000L, 86_400_000L) } ?: return null
    val local = epochMillis + offset * 60_000L
    val day = floorDiv(local, 86_400_000L)
    return when (day) {
        today -> {
            val ofDay = floorMod(local, 86_400_000L)
            FeedbackListTime.Clock(formatRaftClock((ofDay / 3_600_000L).toInt(), ((ofDay % 3_600_000L) / 60_000L).toInt(), null, context.timeFormat.ifBlank { RaftTimeFormat24Hour }))
        }
        today - 1 -> FeedbackListTime.Yesterday
        else -> {
            val date = civilDateFromEpochDay(day)
            val now = civilDateFromEpochDay(today)
            FeedbackListTime.Date(date.month, date.day, date.year.takeIf { it != now.year })
        }
    }
}

@Composable
private fun feedbackListTimeLabel(epochMillis: Long, timeContext: RaftTimeContext?): String? =
    when (val time = feedbackListTime(epochMillis, timeContext)) {
        null -> null
        is FeedbackListTime.Clock -> time.text
        FeedbackListTime.Yesterday -> stringResource(Res.string.activity_list_day_yesterday)
        is FeedbackListTime.Date -> {
            val month = stringResource(FeedbackMonthResources[time.month - 1])
            if (time.year != null) {
                stringResource(Res.string.settings_feedback_tickets_list_date_year, month, time.day, time.year)
            } else {
                stringResource(Res.string.settings_feedback_tickets_list_date, month, time.day)
            }
        }
    }

private val FeedbackMonthResources =
    listOf(
        Res.string.activity_list_day_month_january,
        Res.string.activity_list_day_month_february,
        Res.string.activity_list_day_month_march,
        Res.string.activity_list_day_month_april,
        Res.string.activity_list_day_month_may,
        Res.string.activity_list_day_month_june,
        Res.string.activity_list_day_month_july,
        Res.string.activity_list_day_month_august,
        Res.string.activity_list_day_month_september,
        Res.string.activity_list_day_month_october,
        Res.string.activity_list_day_month_november,
        Res.string.activity_list_day_month_december,
    )

@Composable
private fun FeedbackTicketDetail(
    detail: FeedbackTicketDetailState,
    timeContext: RaftTimeContext?,
    actions: SettingsFeedbackTicketsActions,
    replyComposer: SettingsFeedbackReplyComposer,
    bottomContentPadding: androidx.compose.ui.unit.Dp,
    onOpenCloseDialog: () -> Unit,
) {
    val ticket = detail.ticket
    Column(Modifier.fillMaxSize()) {
        // Resolved by the team: the one question left (web PanelNotice).
        if (detail.canClose && ticket?.status == FeedbackTicketStatus.Resolved) {
            RaftPanelNotice(
                message = stringResource(Res.string.settings_feedback_tickets_resolved_notice),
                status = RaftBannerStatus.Success,
                actions = {
                    RaftButton(
                        text = stringResource(Res.string.settings_feedback_tickets_close_ticket),
                        onClick = onOpenCloseDialog,
                        enabled = !detail.closing,
                    )
                },
            )
        }
        if (detail.attachmentError) {
            RaftBanner(
                description = stringResource(Res.string.settings_feedback_tickets_attachment_open_error),
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                onDismiss = actions.onDismissAttachmentError,
            )
        }
        RaftStorePullToRefreshBox(
            refreshing = detail.refreshing,
            onRefresh = actions.onRefreshTicket,
            enabled = detail.detail != null,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            FeedbackTicketThread(detail, timeContext, actions)
        }
        val read = detail.detail
        if (read != null && read.ticket.status == FeedbackTicketStatus.Closed) {
            RaftText(
                stringResource(Res.string.settings_feedback_tickets_closed_description),
                modifier = Modifier.fillMaxWidth().background(RaftTheme.colors.layerCanvas).padding(horizontal = 16.dp, vertical = 12.dp).padding(bottom = bottomContentPadding),
                style = RaftTheme.typography.callout,
                color = RaftTheme.colors.foregroundMuted,
            )
        } else if (read != null) {
            replyComposer(
                detail.replyDraft,
                !detail.closing,
                detail.sending,
                detail.replyError?.let { stringResource(it.messageResource()) },
                actions.onReplyChange,
                actions.onSendReply,
            )
        }
    }
}

@Composable
private fun FeedbackTicketThread(detail: FeedbackTicketDetailState, timeContext: RaftTimeContext?, actions: SettingsFeedbackTicketsActions) {
    val read = detail.detail
    val listState = rememberLazyListState()
    // Opened at the latest message, as the web's conversation does.
    LaunchedEffect(detail.ticketId, read?.comments?.size) {
        if (read != null) listState.scrollToItem(maxOf(0, listState.layoutInfo.totalItemsCount - 1))
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag(SettingsFeedbackTicketThreadTag),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (read == null) {
            if (detail.loadError != null) {
                item(key = "failed") {
                    RaftEmptyState(
                        title = stringResource(detail.loadError.messageResource()),
                        actions = {
                            FeedbackRetryWithTraceId(detail.retrying, actions.onRetryTicket, detail.loadErrorTraceId)
                        },
                    )
                }
            } else {
                item(key = "loading") {
                    RaftCenteredLoading(
                        message = stringResource(Res.string.settings_feedback_tickets_loading),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    )
                }
            }
            return@LazyColumn
        }
        if (detail.loadError != null) {
            item(key = "error") {
                FeedbackErrorBanner(detail.loadError, detail.retrying, actions.onRetryTicket, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), traceId = detail.loadErrorTraceId)
            }
        }
        val ticket = read.ticket
        item(key = "day-${ticket.id}") { FeedbackDateDivider(ticket.createdAt, timeContext) }
        item(key = "ticket-${ticket.id}") {
            FeedbackConversationMessage(
                authorType = FeedbackAuthorType.Reporter,
                body = ticket.message,
                createdAt = ticket.createdAt,
                timeContext = timeContext,
                attachments = read.attachments,
                openingAttachmentId = detail.openingAttachmentId,
                onOpenAttachment = actions.onOpenAttachment,
                continuation = false,
            )
        }
        var previousAuthor = FeedbackAuthorType.Reporter
        var previousAt = ticket.createdAt
        read.comments.forEach { comment ->
            val newDay = feedbackLocalDay(previousAt, timeContext) != feedbackLocalDay(comment.createdAt, timeContext)
            if (newDay) item(key = "day-${comment.id}") { FeedbackDateDivider(comment.createdAt, timeContext) }
            val continuation = !newDay && previousAuthor == comment.authorType
            item(key = comment.id) {
                if (comment.authorType == FeedbackAuthorType.System) {
                    FeedbackSystemMessage(comment, timeContext)
                } else {
                    FeedbackConversationMessage(
                        authorType = comment.authorType,
                        body = comment.body,
                        createdAt = comment.createdAt,
                        timeContext = timeContext,
                        continuation = continuation,
                    )
                }
            }
            previousAuthor = comment.authorType
            previousAt = comment.createdAt
        }
        if (read.nextCommentCursor != null) {
            item(key = "more-replies") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    RaftButton(
                        text = stringResource(if (detail.loadingMoreReplies) Res.string.settings_feedback_tickets_loading_more else Res.string.settings_feedback_tickets_load_more_replies),
                        onClick = actions.onLoadMoreReplies,
                        enabled = !detail.loadingMoreReplies,
                    )
                }
            }
        }
    }
}

private fun feedbackLocalDay(epochMillis: Long, timeContext: RaftTimeContext?): Long =
    floorDiv(epochMillis + (timeContext?.localOffsetMinutes ?: 0) * 60_000L, 86_400_000L)

@Composable
private fun FeedbackDateDivider(epochMillis: Long, timeContext: RaftTimeContext?) {
    val today = timeContext?.todayEpochDay ?: timeContext?.nowEpochMillis?.let { feedbackLocalDay(it, timeContext) } ?: return
    val label = activityDayLabel(feedbackLocalDay(epochMillis, timeContext), today).localized()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).size(height = 1.dp, width = 0.dp).background(RaftTheme.colors.lineSeam))
        RaftText(label, modifier = Modifier.padding(horizontal = 8.dp), style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundMuted)
        Box(Modifier.weight(1f).size(height = 1.dp, width = 0.dp).background(RaftTheme.colors.lineSeam))
    }
}

@Composable
private fun feedbackClock(epochMillis: Long, timeContext: RaftTimeContext?): String {
    val local = epochMillis + (timeContext?.localOffsetMinutes ?: 0) * 60_000L
    val ofDay = floorMod(local, 86_400_000L)
    return formatRaftClock((ofDay / 3_600_000L).toInt(), ((ofDay % 3_600_000L) / 60_000L).toInt(), null, timeContext?.timeFormat ?: RaftTimeFormat24Hour)
}

/** Web FeedbackConversationMessage: sender and time, the text, the ticket's attachments. */
@Composable
private fun FeedbackConversationMessage(
    authorType: FeedbackAuthorType,
    body: String,
    createdAt: Long,
    timeContext: RaftTimeContext?,
    continuation: Boolean,
    attachments: List<FeedbackAttachment> = emptyList(),
    openingAttachmentId: String? = null,
    onOpenAttachment: (FeedbackAttachment) -> Unit = {},
) {
    val colors = RaftTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (continuation) 2.dp else 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(32.dp)) {
            if (!continuation) {
                RaftIconTile {
                    RaftLucideIcon(
                        if (authorType == FeedbackAuthorType.Reporter) RaftLucideIconName.User else RaftLucideIconName.MessageSquare,
                        modifier = Modifier.size(16.dp),
                        color = raftArgbHex(colors.foregroundMuted),
                    )
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!continuation) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaftText(stringResource(authorType.senderResource()), style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.SemiBold), color = colors.foreground)
                    RaftText(feedbackClock(createdAt, timeContext), style = RaftTheme.typography.footnote, color = colors.foregroundMuted)
                }
            }
            RaftMarkdownBody(body = body, modifier = Modifier.fillMaxWidth())
            if (attachments.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    attachments.forEach { attachment ->
                        FeedbackAttachmentRow(attachment, opening = openingAttachmentId == attachment.id, onOpen = { onOpenAttachment(attachment) })
                    }
                }
            }
        }
    }
}

/**
 * One of the ticket's files: an image opens in the image viewer (it is read through the server
 * first, authenticated); another file is listed by name and size only, as the App cannot open it.
 */
@Composable
private fun FeedbackAttachmentRow(attachment: FeedbackAttachment, opening: Boolean, onOpen: () -> Unit) {
    val colors = RaftTheme.colors
    val openLabel = stringResource(Res.string.settings_feedback_tickets_open_attachment, attachment.filename)
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.layerCard, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .then(if (attachment.isImage) Modifier.raftClickable(onClick = onOpen, enabled = !opening, onClickLabel = openLabel) else Modifier)
            .semantics { contentDescription = openLabel }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (opening) {
            RaftIconSpinner(size = 16.dp, color = colors.foregroundMuted)
        } else {
            RaftLucideIcon(if (attachment.isImage) RaftLucideIconName.Image else RaftLucideIconName.File, modifier = Modifier.size(16.dp), color = raftArgbHex(colors.foregroundMuted))
        }
        RaftText(attachment.filename, modifier = Modifier.weight(1f), style = RaftTheme.typography.callout, color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (attachment.sizeBytes > 0) {
            RaftText(build.raft.app.format.RaftFormat.bytes(attachment.sizeBytes), style = RaftTheme.typography.footnote, color = colors.foregroundMuted, maxLines = 1)
        }
    }
}

@Composable
private fun FeedbackSystemMessage(comment: FeedbackComment, timeContext: RaftTimeContext?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftText(
            feedbackPreviewLine(comment.body) + " · " + feedbackClock(comment.createdAt, timeContext),
            style = RaftTheme.typography.footnote,
            color = RaftTheme.colors.foregroundMuted,
        )
    }
}

/** Web FeedbackCloseDialog: why it is closed (it works now / no longer needed), then Close Ticket. */
@Composable
private fun FeedbackCloseDialog(
    initialReason: FeedbackReporterCloseReason,
    closing: Boolean,
    error: FeedbackErrorKind?,
    onConfirm: (FeedbackReporterCloseReason) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by remember(initialReason) { mutableStateOf(initialReason) }
    val items =
        listOf(
            RaftChoiceListItem(
                value = FeedbackReporterCloseReason.Completed,
                label = stringResource(Res.string.settings_feedback_tickets_close_choice_completed),
                descriptions = listOf(AnnotatedString(stringResource(Res.string.settings_feedback_tickets_close_choice_completed_hint))),
            ),
            RaftChoiceListItem(
                value = FeedbackReporterCloseReason.NoLongerNeeded,
                label = stringResource(Res.string.settings_feedback_tickets_close_choice_no_longer_needed),
                descriptions = listOf(AnnotatedString(stringResource(Res.string.settings_feedback_tickets_close_choice_no_longer_needed_hint))),
            ),
        )
    RaftDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_feedback_tickets_close_ticket),
        description = stringResource(Res.string.settings_feedback_tickets_close_description),
        dismissOnClickOutside = !closing,
        showClose = false,
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !closing,
            )
            RaftButton(
                text = stringResource(Res.string.settings_feedback_tickets_close_ticket),
                onClick = { onConfirm(reason) },
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
                loading = closing,
                loadingLabel = stringResource(Res.string.settings_feedback_tickets_closing),
                enabled = !closing,
            )
        },
    ) {
        RaftChoiceList(
            items = items,
            value = reason,
            onValueChange = { reason = it },
            searchPlaceholder = "",
            clearSearchLabel = "",
            noMatchLabel = "",
            emptyLabel = "",
            enabled = !closing,
        )
        if (error != null) {
            FeedbackErrorBanner(error, retrying = false, onRetry = null, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

/**
 * The reply field: the conversation composer the submit form uses (its keyboard lift and send
 * button), text only. Sending clears it; a failed send puts the text back and says why.
 */
@Composable
private fun SettingsFeedbackThreadReplyComposer(
    draft: String,
    enabled: Boolean,
    sending: Boolean,
    error: String?,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    bottomContentPadding: androidx.compose.ui.unit.Dp,
) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(draft, selection = TextRange(draft.length))) }
    // A failed send puts its text back into an empty field.
    LaunchedEffect(error) {
        if (error != null && fieldValue.text.isBlank() && draft.isNotBlank()) {
            fieldValue = TextFieldValue(draft, selection = TextRange(draft.length))
        }
    }
    Column(Modifier.fillMaxWidth().background(RaftColor.SurfaceWhite)) {
        ThreadComposerContentLeaf(
            attachmentPreviewEnabled = false,
            surface = ThreadComposerSurface(kind = RaftThreadSurfaceKind.Channel, channelId = "settings-feedback-reply", showTaskButton = false),
            state =
                ThreadComposerUiState(
                    instanceId = "settings-feedback-reply",
                    seedText = draft,
                    placeholder = stringResource(Res.string.settings_feedback_tickets_reply_placeholder),
                    enabled = enabled && !sending,
                    attachmentActionsEnabled = false,
                    originalImageOptionEnabled = false,
                    composerMode = if (sending) "sending" else if (error != null) "failed" else "idle",
                    statusLabel = error,
                    suggestionsEnabled = false,
                ),
            editorSessionId = 1L,
            editorValue = fieldValue,
            onEditorValueChange = { _, next ->
                fieldValue = next
                onChange(next.text)
                true
            },
            onNativeEditorValueChange = { _, incoming ->
                repairThreadComposerLegacyZeroSelection(previous = fieldValue, incoming = incoming).also { accepted ->
                    fieldValue = accepted
                    onChange(accepted.text)
                }
            },
            isEditorSessionActive = { true },
            editorAsTask = false,
            onEditorAsTaskChange = { _, _ -> true },
            dismissKeyboardOnBlur = true,
            onEvent = { action, value, _, _ ->
                when (action) {
                    "change" -> onChange(value.orEmpty())
                    "send" -> {
                        if (fieldValue.text.isNotBlank() && !sending) {
                            onChange(fieldValue.text)
                            onSend()
                            fieldValue = TextFieldValue("")
                        }
                    }
                }
            },
            contentPaddingTop = 10.dp,
            contentPaddingBottom = 8.dp,
            hostPaddingHorizontal = RaftAppSpacing.Sm,
            hostPaddingTop = 12.dp,
            keyboardLiftBottomContentPadding = bottomContentPadding + RaftAppSpacing.Lg,
        )
    }
}

/**
 * The image viewer's payload for an attachment read into memory: a `data:` URI the image loader
 * decodes itself (Coil's base64 data fetcher), so nothing is written to disk. Null for a file
 * that is not an image.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun FeedbackOpenedAttachment.toFeedbackImagePreviewPayload(): RaftFilePreviewPayload? {
    val mime = (bytes.contentType ?: attachment.contentType).lowercase().takeIf { it.startsWith("image/") } ?: return null
    val name = attachment.filename
    return RaftFilePreviewPayload(
        kind = "image",
        requestedKind = "image",
        titleDesc = Res.string.file_preview_image.desc(),
        subtitle = name,
        name = name,
        path = name,
        meta = attachment.sizeBytes.takeIf { it > 0L }?.let { build.raft.app.format.RaftFormat.bytes(it) }.orEmpty(),
        imageUrl = "data:$mime;base64," + Base64.encode(bytes.bytes),
        externalFileName = name,
        externalMimeType = mime,
        attachmentId = attachment.id,
    )
}
