package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import build.raft.app.base.BridgeModule
import build.raft.app.data.DataResult
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.ForwardMessageTargetDirectoryCursor
import build.raft.app.data.ForwardTargetSearchUpdate
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.RealConversationRepository
import build.raft.app.data.RealForwardMessageTargetRepository
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.StoredAuthSession
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftWindowDialog
import build.raft.designsystem.components.RaftWindowDialogProperties
import build.raft.designsystem.components.rememberRaftStableWindowInsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val WEB_PAGE_FORWARD_SEARCH_DEBOUNCE_MS = 250L
private const val WEB_PAGE_FORWARD_MAX_TITLE_CHARS = 160

/** Whether [rawUrl] is a valid `http://` or `https://` web page URL eligible to forward to Raft. */
internal fun canForwardWebPageUrl(rawUrl: String): Boolean {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return false
    val lower = trimmed.lowercase()
    return (lower.startsWith("https://") || lower.startsWith("http://")) &&
        raftMobileWebPanelDisplayHost(trimmed).isNotEmpty()
}

/**
 * Formats the chat message body when forwarding a web page from the in-app browser to a Raft
 * channel or DM. Includes a concise single-line title above the URL when the title adds real
 * context (not blank, not equal to the URL or bare host), keeping the raw URL on its own line so
 * Raft renders link cards and unfurls reliably.
 */
internal fun formatWebPageForwardMessageBody(
    url: String,
    pageTitle: String? = null,
): String {
    val cleanUrl = url.trim()
    if (cleanUrl.isEmpty()) return ""
    val cleanTitle =
        pageTitle
            ?.replace('\r', ' ')
            ?.replace('\n', ' ')
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(WEB_PAGE_FORWARD_MAX_TITLE_CHARS)
            .orEmpty()
    val host = raftMobileWebPanelDisplayHost(cleanUrl)
    val titleAddsContext =
        cleanTitle.isNotEmpty() &&
            !cleanTitle.equals(cleanUrl, ignoreCase = true) &&
            !cleanTitle.equals(host, ignoreCase = true) &&
            !cleanTitle.startsWith("http://", ignoreCase = true) &&
            !cleanTitle.startsWith("https://", ignoreCase = true)
    return if (titleAddsContext) "$cleanTitle\n$cleanUrl" else cleanUrl
}

/**
 * Full-screen destination picker rendered in a [RaftWindowDialog] above the in-app browser WebView
 * so tapping a single Raft channel, DM, thread, or task immediately forwards the active page URL.
 */
@Composable
internal fun RaftWebPageForwardHost(
    url: String,
    pageTitle: String?,
    session: StoredAuthSession,
    authStore: RaftAuthSessionStore,
    networkClient: RaftNetworkClient,
    bridge: BridgeModule,
    onDismiss: () -> Unit,
    onForwarded: (targetTitle: String) -> Unit,
    onForwardFailed: () -> Unit,
) {
    val servers = session.servers
    val initialServerId =
        remember(session.currentServerId, servers) {
            session.currentServerId
                ?.trim()
                ?.takeIf { id -> id.isNotEmpty() && servers.any { it.id == id } }
                ?: servers.firstOrNull()?.id.orEmpty()
        }
    if (initialServerId.isBlank() || !canForwardWebPageUrl(url)) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    var selectedServerId by remember(url) { mutableStateOf(initialServerId) }
    var query by remember(url, selectedServerId) { mutableStateOf("") }
    var activeQuery by remember(url, selectedServerId) { mutableStateOf("") }
    var targets by remember(url, selectedServerId) { mutableStateOf<List<ForwardMessageTarget>>(emptyList()) }
    var conversationTargets by remember(url, selectedServerId) { mutableStateOf<List<ForwardMessageTarget>>(emptyList()) }
    var nextCursor by remember(url, selectedServerId) { mutableStateOf<ForwardMessageTargetDirectoryCursor?>(null) }
    var loadState by remember(url, selectedServerId) { mutableStateOf(RaftForwardPickerLoadState.Loading) }
    var errorCode by remember(url, selectedServerId) { mutableStateOf<String?>(null) }
    var appending by remember(url, selectedServerId) { mutableStateOf(false) }
    var sending by remember(url) { mutableStateOf(false) }
    var reloadNonce by remember(url, selectedServerId) { mutableIntStateOf(0) }
    var requestGeneration by remember(url, selectedServerId) { mutableIntStateOf(1) }

    val coroutineScope = rememberCoroutineScope()
    val directorySessionId = remember(url, selectedServerId) { "web-panel-forward:$selectedServerId:${url.hashCode()}" }
    val memberRepository =
        remember(authStore, networkClient) {
            RealMemberRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { bridge.log("web_panel_forward_members $it") },
            )
        }
    val channelRepository =
        remember(authStore, networkClient) {
            RealChannelRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                warningLogger = { bridge.log("web_panel_forward_channels $it") },
            )
        }
    val targetRepository =
        remember(authStore, networkClient, channelRepository, memberRepository) {
            RealForwardMessageTargetRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                channelRepository = channelRepository,
                memberRepository = memberRepository,
                warningLogger = { bridge.log("web_panel_forward_targets $it") },
            )
        }

    DisposableEffect(targetRepository, directorySessionId) {
        onDispose { targetRepository.releaseDirectorySnapshot(directorySessionId) }
    }

    LaunchedEffect(query, selectedServerId) {
        if (query != activeQuery) {
            delay(WEB_PAGE_FORWARD_SEARCH_DEBOUNCE_MS)
            activeQuery = query
        }
    }

    LaunchedEffect(selectedServerId, activeQuery, reloadNonce) {
        val sid = ServerId(selectedServerId)
        val gen = requestGeneration + 1
        requestGeneration = gen
        loadState = RaftForwardPickerLoadState.Loading
        errorCode = null
        appending = false
        nextCursor = null
        val trimmedQuery = activeQuery.trim()
        if (trimmedQuery.isEmpty()) {
            val dirResult =
                targetRepository.directoryTargets(
                    serverId = sid,
                    sessionId = directorySessionId,
                    cursor = ForwardMessageTargetDirectoryCursor(sessionId = directorySessionId),
                    pageSize = 30,
                )
            conversationTargets = targetRepository.conversationTargets(serverId = sid, currentChannelId = null)
            when (dirResult) {
                is DataResult.Content -> {
                    targets = dirResult.data.items
                    nextCursor = dirResult.data.nextCursor
                    loadState = RaftForwardPickerLoadState.Loaded
                }
                is DataResult.Empty -> {
                    targets = emptyList()
                    nextCursor = null
                    loadState = RaftForwardPickerLoadState.Loaded
                }
                is DataResult.Error -> {
                    targets = emptyList()
                    errorCode = dirResult.error.code
                    loadState = RaftForwardPickerLoadState.Error
                }
            }
        } else {
            conversationTargets = emptyList()
            targetRepository.searchTargetsProgressively(sid, trimmedQuery, 40).collect { update ->
                when (update) {
                    is ForwardTargetSearchUpdate.Interim -> {
                        targets = update.targets
                    }
                    is ForwardTargetSearchUpdate.Final -> {
                        when (val res = update.result) {
                            is DataResult.Content -> {
                                targets = res.data
                                loadState = RaftForwardPickerLoadState.Loaded
                            }
                            is DataResult.Empty -> {
                                targets = emptyList()
                                loadState = RaftForwardPickerLoadState.Loaded
                            }
                            is DataResult.Error -> {
                                errorCode = res.error.code
                                loadState = RaftForwardPickerLoadState.Error
                            }
                        }
                    }
                }
            }
        }
    }

    fun loadMoreDirectory() {
        val cursor = nextCursor ?: return
        if (appending || activeQuery.isNotBlank() || loadState != RaftForwardPickerLoadState.Loaded) return
        val expectedGeneration = requestGeneration
        val expectedServerId = selectedServerId
        val expectedSessionId = directorySessionId
        appending = true
        val sid = ServerId(expectedServerId)
        coroutineScope.launch {
            try {
                val pageResult =
                    targetRepository.directoryTargets(
                        serverId = sid,
                        sessionId = expectedSessionId,
                        cursor = cursor,
                        pageSize = 30,
                    )
                val stillCurrent =
                    requestGeneration == expectedGeneration &&
                        selectedServerId == expectedServerId &&
                        directorySessionId == expectedSessionId &&
                        activeQuery.isBlank()
                if (!stillCurrent) return@launch
                when (pageResult) {
                    is DataResult.Content -> {
                        val existingKeys = targets.mapTo(HashSet()) { it.threadForwardSelectionKey() }
                        val fresh = pageResult.data.items.filter { it.threadForwardSelectionKey() !in existingKeys }
                        targets = targets + fresh
                        nextCursor = pageResult.data.nextCursor
                    }
                    is DataResult.Empty -> nextCursor = null
                    is DataResult.Error -> nextCursor = null
                }
            } finally {
                if (requestGeneration == expectedGeneration && selectedServerId == expectedServerId) {
                    appending = false
                }
            }
        }
    }

    fun sendToTarget(target: ForwardMessageTarget) {
        if (sending) return
        val body = formatWebPageForwardMessageBody(url = url, pageTitle = pageTitle)
        if (body.isBlank()) {
            onForwardFailed()
            return
        }
        sending = true
        val sid = ServerId(selectedServerId)
        coroutineScope.launch {
            try {
                val resolved =
                    targetRepository.resolveTargetChannel(
                        sourceServerId = sid,
                        target = target,
                        allowJoin = true,
                    )
                val destination = (resolved as? DataResult.Content)?.data
                if (destination == null) {
                    bridge.logWarn("web_panel_forward_resolve_failed code=${(resolved as? DataResult.Error)?.error?.code ?: "empty"}")
                    onForwardFailed()
                    return@launch
                }
                val conversationRepository =
                    RealConversationRepository(
                        sessionStore = authStore,
                        networkClient = networkClient,
                        routeServerIdProvider = { destination.serverId.value },
                        warningLogger = { bridge.log("web_panel_forward_send $it") },
                    )
                when (val sent = conversationRepository.sendMessage(channelId = destination.channelId, body = body)) {
                    is DataResult.Content -> onForwarded(target.title)
                    is DataResult.Empty,
                    is DataResult.Error -> {
                        bridge.logWarn("web_panel_forward_send_failed code=${(sent as? DataResult.Error)?.error?.code ?: "empty"}")
                        onForwardFailed()
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                bridge.logWarn("web_panel_forward_exception ${t.message.orEmpty()}")
                onForwardFailed()
            } finally {
                sending = false
            }
        }
    }

    val pickerItems = remember(targets) { targets.map { it.toRaftForwardTargetPickerItem() } }
    val pickerConversationItems = remember(conversationTargets) { conversationTargets.map { it.toRaftForwardTargetPickerItem() } }
    val serverSwitcherItems =
        remember(servers) {
            servers.map { srv ->
                RaftServerSwitcherItem(
                    id = srv.id,
                    name = srv.name,
                    slug = srv.slug.orEmpty(),
                    avatarUrl = srv.avatarUrl,
                    publicPageLive = srv.publicPageLive,
                )
            }
        }
    val displaySubtitle =
        remember(url) {
            raftMobileWebPanelDisplayHost(url).ifBlank { url }
        }
    val bottomSafeArea = raftBottomSafeAreaPadding()
    val queryPending = query != activeQuery

    RaftWindowDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        properties =
            RaftWindowDialogProperties(
                dismissOnBackPress = !sending,
                drawScrim = false,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(rememberRaftStableWindowInsets(WindowInsets.safeDrawing)),
        ) {
            RaftForwardTargetPicker(
                messageCount = 1,
                skippedCount = 0,
                nestedForwardCount = 0,
                subtitleOverride = displaySubtitle,
                singleTapContinues = true,
                allowMultiSelect = false,
                searchInputInteractive = !sending,
                bottomSafeArea = bottomSafeArea,
                topContent =
                    if (serverSwitcherItems.size > 1) {
                        {
                            RaftServerSwitcherControl(
                                servers = serverSwitcherItems,
                                selectedServerId = selectedServerId,
                                accessibilityLabel = stringResource(Res.string.settings_server_title),
                                enabled = !sending && loadState != RaftForwardPickerLoadState.Loading,
                                onSelect = { nextId ->
                                    if (nextId != selectedServerId) {
                                        targetRepository.releaseDirectorySnapshot(directorySessionId)
                                        selectedServerId = nextId
                                    }
                                },
                            )
                        }
                    } else {
                        null
                    },
                state =
                    RaftForwardTargetPickerUiState(
                        sessionKey =
                            RaftForwardPickerSessionKey(
                                sourceContext = "web-panel-forward:$url",
                                serverId = selectedServerId,
                                openGeneration = 1,
                            ),
                        query = query,
                        items = pickerItems,
                        selectedKey = null,
                        selectedItems = emptyList(),
                        loadState = if (queryPending) RaftForwardPickerLoadState.Loading else loadState,
                        errorCode = if (queryPending) null else errorCode,
                        canLoadMore = nextCursor != null && activeQuery.isBlank(),
                        appending = appending,
                        interactive = !queryPending && !sending && loadState == RaftForwardPickerLoadState.Loaded,
                        dismissEnabled = !sending,
                        requestGeneration = requestGeneration,
                        conversationItems = pickerConversationItems,
                    ),
                onIntent = { intent ->
                    when (intent) {
                        is RaftForwardTargetPickerIntent.QueryChanged -> query = intent.query
                        RaftForwardTargetPickerIntent.Retry -> reloadNonce += 1
                        RaftForwardTargetPickerIntent.LoadMore -> loadMoreDirectory()
                        RaftForwardTargetPickerIntent.Dismiss -> if (!sending) onDismiss()
                        is RaftForwardTargetPickerIntent.TargetSelected -> {
                            if (intent.renderGeneration == requestGeneration && !sending) {
                                sendToTarget(intent.item.payload)
                            }
                        }
                        is RaftForwardTargetPickerIntent.RemoveSelection,
                        RaftForwardTargetPickerIntent.Continue -> Unit
                    }
                },
            )
        }
    }
}
