package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.base.Utils
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.RaftPublicMessage
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.parseRaftPublicMessages
import build.raft.app.data.parseRaftPublicServerCard
import build.raft.app.model.ConversationKind
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A message link into a public server the account has not joined (slock #11126 review rule 2):
 * the conversation is read through the anonymous public routes and shown read-only when a
 * stranger may read it; the one "Message not found" state only when the public route says 404
 * (or 400/403). A transient failure (5xx, timeout, offline) keeps the page with a Retry, never the
 * not-found state. The app has no join-public-server path, so there is no "Join to view".
 */
internal sealed interface RaftPublicPreviewState {
    data object Loading : RaftPublicPreviewState

    data class Content(
        val channelName: String?,
        val serverName: String?,
        val messages: List<RaftPublicMessage>,
        val highlightMessageId: String?,
        val thread: Boolean,
    ) : RaftPublicPreviewState

    data object NotFound : RaftPublicPreviewState

    data object Unavailable : RaftPublicPreviewState
}

internal class RaftPublicConversationPreviewStore(
    private val networkClient: RaftNetworkClient,
    private val link: RaftMessageLink,
    private val scope: CoroutineScope,
    private val diagnosticsLogger: (String) -> Unit = {},
) {
    private val mutableState = MutableStateFlow<RaftPublicPreviewState>(RaftPublicPreviewState.Loading)
    val state: StateFlow<RaftPublicPreviewState> = mutableState.asStateFlow()
    private var job: Job? = null

    /** Single-flight: a load already running is not restarted. */
    fun load() {
        if (job?.isActive == true) return
        mutableState.value = RaftPublicPreviewState.Loading
        job = scope.launch { mutableState.value = loadOnce() }
    }

    internal suspend fun loadOnce(): RaftPublicPreviewState = coroutineScope {
        val base = "public/servers/${link.serverSlug}"
        val cardRequest = async { request(base) }
        val pageRequest = async { request("$base/channels/${link.channelId}/messages", listOf("limit" to PageSize.toString())) }
        val card = cardRequest.await()?.takeIf { it.code in 200..299 }?.let { parseRaftPublicServerCard(it.bodyText) }
        val page = pageRequest.await()
        when (page.linkOutcome()) {
            RaftLinkProbeOutcome.NotFound -> return@coroutineScope notFound("public_channel_${page?.code}")
            RaftLinkProbeOutcome.Unavailable -> return@coroutineScope unavailable("public_channel_${page?.code ?: "transport"}")
            RaftLinkProbeOutcome.Found -> Unit
        }
        var messages = parseRaftPublicMessages(page?.bodyText) ?: return@coroutineScope unavailable("public_channel_unreadable")
        // The target may sit above the newest page: look a little further back for it.
        val target = link.threadParentMessageId ?: link.messageId
        var olderPages = 0
        while (target != null && messages.none { it.id == target } && messages.size >= PageSize && olderPages < MaxOlderPages) {
            val older =
                request(
                    "$base/channels/${link.channelId}/messages",
                    listOf("limit" to PageSize.toString(), "beforeMessageId" to messages.first().id),
                )?.takeIf { it.code in 200..299 }?.let { parseRaftPublicMessages(it.bodyText) } ?: break
            if (older.isEmpty()) break
            messages = older + messages
            olderPages++
        }
        val channelName = card?.channelNames?.get(link.channelId)
        val parent = link.threadParentMessageId?.let { id -> messages.firstOrNull { it.id == id } }
        val threadId = parent?.threadId
        if (parent != null && threadId != null) {
            val replies =
                request("$base/threads/$threadId/messages", listOf("limit" to PageSize.toString()))
                    ?.takeIf { it.code in 200..299 }
                    ?.let { parseRaftPublicMessages(it.bodyText) }
            if (replies != null) {
                diagnosticsLogger("message_link_public_preview result=thread replies=${replies.size}")
                return@coroutineScope RaftPublicPreviewState.Content(
                    channelName = channelName,
                    serverName = card?.serverName,
                    messages = listOf(parent) + replies,
                    highlightMessageId = (link.messageId ?: parent.id).takeIf { id -> id == parent.id || replies.any { it.id == id } },
                    thread = true,
                )
            }
        }
        diagnosticsLogger("message_link_public_preview result=channel messages=${messages.size}")
        RaftPublicPreviewState.Content(
            channelName = channelName,
            serverName = card?.serverName,
            messages = messages,
            highlightMessageId = target?.takeIf { id -> messages.any { it.id == id } },
            thread = false,
        )
    }

    private suspend fun request(path: String, query: List<Pair<String, String>> = emptyList()): HomeNetworkResponse? =
        try {
            networkClient.request(priority = DataRequestPriority.Foreground, path = path, query = query)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            diagnosticsLogger("message_link_public_probe_failed error=${error::class.simpleName.orEmpty()}")
            null
        }

    private fun notFound(reason: String): RaftPublicPreviewState {
        diagnosticsLogger("message_link_public_preview result=not_found reason=$reason")
        return RaftPublicPreviewState.NotFound
    }

    private fun unavailable(reason: String): RaftPublicPreviewState {
        diagnosticsLogger("message_link_public_preview result=unavailable reason=$reason")
        return RaftPublicPreviewState.Unavailable
    }

    private companion object {
        const val PageSize = 50
        const val MaxOlderPages = 2
    }
}

internal const val RAFT_MESSAGE_LINK_PUBLIC_PREVIEW_PAGE = "compose_message_link_public_preview"
private const val PublicPreviewSlugKey = "serverSlug"
private const val PublicPreviewRouteKindKey = "routeKind"
private const val PublicPreviewChannelIdKey = "channelId"
private const val PublicPreviewMessageIdKey = "messageId"
private const val PublicPreviewThreadParentKey = "threadParentMessageId"

/** Identity-only pageData (AGENTS.md route/pageData rule): the page loads its own content. */
internal fun RaftMessageLink.toPublicPreviewPageData(): JSONObject =
    JSONObject()
        .put("pageName", RAFT_MESSAGE_LINK_PUBLIC_PREVIEW_PAGE)
        .put(PublicPreviewSlugKey, serverSlug)
        .put(PublicPreviewRouteKindKey, if (routeKind == ConversationKind.DirectMessage) "dm" else "channel")
        .put(PublicPreviewChannelIdKey, channelId)
        .apply {
            messageId?.let { put(PublicPreviewMessageIdKey, it) }
            threadParentMessageId?.let { put(PublicPreviewThreadParentKey, it) }
        }

/** Re-validated through the same parser rules as the URL; anything else is no link. */
internal fun JSONObject.publicPreviewMessageLinkOrNull(): RaftMessageLink? {
    fun value(key: String) = optString(key).trim().takeIf(String::isNotEmpty)
    val slug = value(PublicPreviewSlugKey) ?: return null
    val kind = if (value(PublicPreviewRouteKindKey) == "dm") "dm" else "channel"
    val channelId = value(PublicPreviewChannelIdKey) ?: return null
    val query =
        listOfNotNull(
            value(PublicPreviewMessageIdKey)?.let { "msg=$it" },
            value(PublicPreviewThreadParentKey)?.let { "thread=$channelId:$it" },
        ).joinToString("&")
    return parseRaftMessageLink("https://app.raft.build/s/$slug/$kind/$channelId" + if (query.isEmpty()) "" else "?$query")
}

internal fun Pager.openRaftMessageLinkPublicPreviewPage(link: RaftMessageLink, storeScope: RaftShellStoreScope): Boolean =
    openRaftPage(
        pageName = RAFT_MESSAGE_LINK_PUBLIC_PREVIEW_PAGE,
        pageData = link.toPublicPreviewPageData(),
        storeScopeOverride = storeScope,
    )

@Page(RAFT_MESSAGE_LINK_PUBLIC_PREVIEW_PAGE, supportInLocal = true)
internal class ComposeRaftMessageLinkPublicPreviewPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftMessageLinkPublicPreviewPageContent() }
    }
}

@Composable
private fun RaftMessageLinkPublicPreviewPageContent() {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val pager = LocalActivity.current.getPager()
    val close = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() }
    val link = remember(pager.pageData.params.toString()) { pager.pageData.params.publicPreviewMessageLinkOrNull() }
    if (link == null) {
        RaftMessageLinkNotFoundScreen(onBack = close, topSafeArea = raftTopSafeAreaPadding())
        return
    }
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val sharedStores = remember { RaftSharedStoreRegistry.get(bridge) }
    val storeScope = remember(pager.pageData.params.toString()) {
        pager.pageData.params.raftShellStoreScope(build.raft.app.data.RaftShellStoreScope.Legacy.shellInstanceId)
    }
    val networkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val scope = rememberCoroutineScope()
    val store = remember(link, networkClient) { RaftPublicConversationPreviewStore(networkClient, link, scope, bridge::log) }
    LaunchedEffect(store) { store.load() }
    val state by store.state.collectAsState()
    RaftMessageLinkPublicPreviewScreen(
        state = state,
        onBack = close,
        onRetry = store::load,
        topSafeArea = raftTopSafeAreaPadding(),
    )
}

@Composable
internal fun RaftMessageLinkPublicPreviewScreen(
    state: RaftPublicPreviewState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    topSafeArea: Dp = 0.dp,
) {
    if (state is RaftPublicPreviewState.NotFound) {
        RaftMessageLinkNotFoundScreen(onBack = onBack, modifier = modifier, topSafeArea = topSafeArea)
        return
    }
    RaftBackHandler(enabled = true, label = "message_link_public_preview", blocksBackSwipe = false) { onBack() }
    RaftDesignSystemTheme {
        val colors = RaftTheme.colors
        Column(modifier.fillMaxSize().background(colors.layerCanvas)) {
            val content = state as? RaftPublicPreviewState.Content
            RaftPanelBackHeader(
                title = content?.channelName?.let { "#$it" } ?: stringResource(Res.string.message_link_public_preview_title),
                subtitle = content?.serverName,
                onBack = onBack,
                topSafeArea = topSafeArea,
            )
            Box(Modifier.fillMaxWidth().background(colors.fillMuted).padding(horizontal = 16.dp, vertical = 10.dp)) {
                RaftText(
                    text = stringResource(Res.string.message_link_public_preview_notice),
                    style = RaftTheme.typography.footnote,
                    color = colors.foregroundMuted,
                )
            }
            when (state) {
                RaftPublicPreviewState.Loading ->
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { RaftSpinner() }
                RaftPublicPreviewState.Unavailable ->
                    Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(Modifier.widthIn(max = 320.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            RaftText(
                                text = stringResource(Res.string.error_server_unreachable),
                                style = RaftTheme.typography.callout,
                                color = colors.foregroundMuted,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(16.dp))
                            RaftButton(text = stringResource(Res.string.common_retry), onClick = onRetry, size = RaftButtonSize.Lg)
                        }
                    }
                is RaftPublicPreviewState.Content -> RaftPublicPreviewMessageList(state, Modifier.fillMaxWidth().weight(1f))
                RaftPublicPreviewState.NotFound -> Unit
            }
        }
    }
}

@Composable
private fun RaftPublicPreviewMessageList(state: RaftPublicPreviewState.Content, modifier: Modifier) {
    val colors = RaftTheme.colors
    val listState = rememberLazyListState()
    val highlightIndex = state.messages.indexOfFirst { it.id == state.highlightMessageId }
    LaunchedEffect(state.highlightMessageId, state.messages.size) {
        // One programmatic scroll when the content lands: the link target (or the newest message).
        val index = if (highlightIndex >= 0) highlightIndex else state.messages.lastIndex
        if (index >= 0) listState.scrollToItem(index)
    }
    LazyColumn(modifier, state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(state.messages, key = { it.id }) { message ->
            val highlighted = message.id == state.highlightMessageId
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(if (highlighted) colors.infoSoft else colors.layerCanvas)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                RaftText(
                    text = message.senderName,
                    style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.Bold),
                    color = colors.foreground,
                    maxLines = 1,
                )
                if (message.content.isNotBlank()) {
                    RaftText(text = message.content, style = RaftTheme.typography.body, color = colors.foreground)
                }
            }
        }
    }
}
