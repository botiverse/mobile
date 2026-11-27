package build.raft.app.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import build.raft.app.RaftWebViewController
import build.raft.app.data.AuthSessionEvent
import build.raft.app.data.RaftAuthSessionEventSource
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.reduce
import build.raft.app.thread.dispatch
import build.raft.app.thread.reduce
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onSubscription

internal enum class SettingsWebViewSurface(val diagnosticsName: String) {
    Onboarding("onboarding"),
    Computers("computers"),
    Billing("billing"),
    Marketplace("marketplace"),
    Administration("administration"),
    ImBridges("im-bridges"),
    AppAuthorizations("app-authorizations"),
    ReleaseNotes("release-notes"),
    /** Settings > Account > Delete account: the web deletion page (also its pending state). */
    AccountDeletion("account-deletion"),
    /** Settings > Account > Data & privacy: the web data export surface (and emailed `/?data_export=` links). */
    DataPrivacy("data-privacy"),
    /** The in-app Artifact viewer's web page (not a Settings route; shares the token sync). */
    ArtifactViewer("artifact-viewer"),
    /** Authenticated in-app WebView for Raft's own web pages without a native screen (#proj-mobile:#204). */
    WebPage("web-page"),
}

internal data class SettingsWebViewAuthSnapshot(
    val session: StoredAuthSession? = null,
    val event: AuthSessionEvent? = null,
    val sessionGeneration: Long = 0L,
    val hydrated: Boolean = false,
)

internal class SettingsWebViewAuthSnapshotReducer(
    private val nextSessionGeneration: () -> Long,
) {
    private var reduced = SettingsWebViewAuthSnapshot()
    private var lastObservedEventGeneration = Long.MIN_VALUE

    fun reconcileSubscription(latestSession: StoredAuthSession?): SettingsWebViewAuthSnapshot =
        reduce(latestSession = latestSession, event = null)

    fun admit(event: AuthSessionEvent): Boolean {
        if (event.generation <= lastObservedEventGeneration) return false
        lastObservedEventGeneration = event.generation
        return true
    }

    fun reduceAccepted(
        latestSession: StoredAuthSession?,
        event: AuthSessionEvent,
    ): SettingsWebViewAuthSnapshot =
        reduce(latestSession = latestSession, event = event)

    private fun reduce(
        latestSession: StoredAuthSession?,
        event: AuthSessionEvent?,
    ): SettingsWebViewAuthSnapshot {
        val previousSession = reduced.session
        val sessionGeneration =
            when {
                latestSession == null -> 0L
                reduced.sessionGeneration <= 0L -> nextSessionGeneration()
                event is AuthSessionEvent.Authenticated -> nextSessionGeneration()
                previousSession?.user?.id != latestSession.user.id -> nextSessionGeneration()
                else -> reduced.sessionGeneration
            }
        return SettingsWebViewAuthSnapshot(
            session = latestSession,
            event = event,
            sessionGeneration = sessionGeneration,
            hydrated = true,
        ).also { reduced = it }
    }
}

@Composable
internal fun rememberSettingsWebViewAuthSnapshot(
    shellAuthStore: RaftAuthSessionStore,
    processAuthEvents: RaftAuthSessionEventSource,
    initialSession: StoredAuthSession? = null,
): SettingsWebViewAuthSnapshot {
    val reducer =
        remember(shellAuthStore, processAuthEvents) {
            SettingsWebViewAuthSnapshotReducer { nextSettingsWebViewSessionGeneration.incrementAndGet() }
        }
    var snapshot by remember(shellAuthStore, processAuthEvents) {
        mutableStateOf(
            if (initialSession != null) {
                reducer.reconcileSubscription(initialSession)
            } else {
                SettingsWebViewAuthSnapshot()
            },
        )
    }
    LaunchedEffect(shellAuthStore, processAuthEvents) {
        processAuthEvents.authSessionEvents
            .onSubscription {
                val latestSession = runCatchingPreservingCancellation { shellAuthStore.readSession() }.getOrNull()
                snapshot = reducer.reconcileSubscription(latestSession)
            }
            .collect { event ->
                if (!reducer.admit(event)) return@collect
                val latestSession = runCatchingPreservingCancellation { shellAuthStore.readSession() }.getOrNull()
                snapshot = reducer.reduceAccepted(latestSession, event)
            }
    }
    return snapshot
}

internal data class SettingsWebViewAccessTokenUpdate(
    val authMutationGeneration: Long,
    val accountId: String,
    val serverId: String,
    val sessionGeneration: Long,
    val webViewGeneration: Long,
    val sequence: Long,
    val accessToken: String,
)

internal class SettingsWebViewAccessTokenFence(
    initialSession: StoredAuthSession,
    private val sessionGeneration: Long,
    private val webViewGeneration: Long,
) {
    val accountId = initialSession.user.id.trim()
    val serverId = initialSession.currentServerId?.trim().orEmpty()
    private var lastGeneration = Long.MIN_VALUE
    private var nextSequence = 0L
    private var documentReady = false
    private var pendingUpdate: SettingsWebViewAccessTokenUpdate? = null
    private var active =
        accountId.isNotBlank() &&
            serverId.isNotBlank() &&
            sessionGeneration > 0L &&
            webViewGeneration > 0L

    fun onDocumentReady(session: StoredAuthSession?): SettingsWebViewAccessTokenUpdate? {
        val activeSession = session ?: return null
        if (!active || !activeSession.matchesBinding()) return null
        documentReady = true
        return takePendingUpdate()
    }

    fun onDocumentLoading() {
        documentReady = false
    }

    fun onAuthEvent(
        event: AuthSessionEvent?,
        latestSession: StoredAuthSession?,
    ): SettingsWebViewAccessTokenUpdate? {
        event ?: return null
        if (!active || event.generation <= lastGeneration) return null
        lastGeneration = event.generation

        val activeSession = latestSession
        if (event is AuthSessionEvent.Cleared || activeSession == null || !activeSession.matchesBinding()) {
            active = false
            documentReady = false
            pendingUpdate = null
            return null
        }
        if (event !is AuthSessionEvent.TokensRotated) return null

        val nextAccessToken = activeSession.accessToken.takeIf { it.isNotBlank() } ?: return null
        pendingUpdate = SettingsWebViewAccessTokenUpdate(
            authMutationGeneration = event.generation,
            accountId = accountId,
            serverId = serverId,
            sessionGeneration = sessionGeneration,
            webViewGeneration = webViewGeneration,
            sequence = ++nextSequence,
            accessToken = nextAccessToken,
        )
        return if (documentReady) takePendingUpdate() else null
    }

    fun onDispatchFailed(update: SettingsWebViewAccessTokenUpdate) {
        if (!active) return
        val pending = pendingUpdate
        if (pending == null || pending.sequence < update.sequence) {
            pendingUpdate = update
        }
    }

    fun detach() {
        active = false
        documentReady = false
        pendingUpdate = null
    }

    private fun takePendingUpdate(): SettingsWebViewAccessTokenUpdate? =
        pendingUpdate.also { pendingUpdate = null }

    private fun StoredAuthSession.matchesBinding(): Boolean =
        user.id.trim() == accountId &&
            currentServerId?.trim().orEmpty() == serverId
}

internal data class SettingsWebViewHostAuthBinding(
    val accountId: String,
    val serverId: String,
    val sessionGeneration: Long,
    val webViewGeneration: Long,
    internal val fence: SettingsWebViewAccessTokenFence,
)

@Composable
internal fun rememberSettingsWebViewHostAuthBinding(
    snapshot: SettingsWebViewAuthSnapshot,
    controller: RaftWebViewController,
): SettingsWebViewHostAuthBinding? {
    val session = snapshot.session ?: return null
    val accountId = session.user.id.trim()
    val serverId = session.currentServerId?.trim().orEmpty()
    if (accountId.isBlank() || serverId.isBlank() || snapshot.sessionGeneration <= 0L) return null
    val webViewGeneration =
        remember(controller, accountId, serverId, snapshot.sessionGeneration) {
            nextSettingsWebViewGeneration.incrementAndGet()
        }
    val fence =
        remember(controller, accountId, serverId, snapshot.sessionGeneration, webViewGeneration) {
            SettingsWebViewAccessTokenFence(
                initialSession = session,
                sessionGeneration = snapshot.sessionGeneration,
                webViewGeneration = webViewGeneration,
            )
        }
    return SettingsWebViewHostAuthBinding(
        accountId = accountId,
        serverId = serverId,
        sessionGeneration = snapshot.sessionGeneration,
        webViewGeneration = webViewGeneration,
        fence = fence,
    )
}

@Composable
internal fun SettingsWebViewAccessTokenSyncEffect(
    snapshot: SettingsWebViewAuthSnapshot,
    documentReady: Boolean,
    controller: RaftWebViewController,
    binding: SettingsWebViewHostAuthBinding?,
    surface: SettingsWebViewSurface,
    logger: (String) -> Unit = {},
    /** Refreshes the token ahead of its expiry while this surface is on screen (#300); null: none. */
    refresher: SettingsWebViewAccessTokenRefresher? = null,
    /** Whether the surface's page is on screen ([rememberRaftPagerAppeared]): not covered by another page. */
    visible: Boolean = true,
    /** A token reached the shown document (pushed into it). */
    onAccessTokenDispatched: (String) -> Unit = {},
    /** Origin the WebView is trusted to write `slock_access_token` into (null keeps legacy unguarded JS for callers already locked at the host). */
    webOrigin: String? = null,
) {
    val session = snapshot.session ?: return
    val activeBinding = binding ?: return
    val fence = activeBinding.fence

    if (refresher != null) {
        val currentLogger by rememberUpdatedState(logger)
        SettingsWebViewWhileShown(visible, refresher, fence) {
            runSettingsWebViewAccessTokenRefreshAhead(
                surface = surface.diagnosticsName,
                readAccessToken = refresher::currentAccessToken,
                nowMillis = { build.raft.app.data.defaultNowMillis() },
                refresh = { reason, expiring -> refresher.refresh(reason, expiring) },
                logger = { currentLogger(it) },
            )
        }
    }

    DisposableEffect(fence) {
        onDispose { fence.detach() }
    }

    val currentWebOrigin by rememberUpdatedState(webOrigin)

    fun dispatch(update: SettingsWebViewAccessTokenUpdate) {
        val script =
            settingsWebViewRuntimeAccessTokenJavaScript(
                accessToken = update.accessToken,
                accountId = update.accountId,
                serverId = update.serverId,
                sessionGeneration = update.sessionGeneration,
                webViewGeneration = update.webViewGeneration,
                sequence = update.sequence,
                webOrigin = currentWebOrigin,
            )
        if (script.isEmpty()) {
            fence.onDispatchFailed(update)
            logger(
                "settings_webview_access_token_sync surface=${surface.diagnosticsName} " +
                    "generation=${update.authMutationGeneration} result=blocked_origin"
            )
            return
        }
        val dispatched = controller.evaluateJavaScript(script)
        if (!dispatched) fence.onDispatchFailed(update) else onAccessTokenDispatched(update.accessToken)
        logger(
            "settings_webview_access_token_sync surface=${surface.diagnosticsName} " +
                "generation=${update.authMutationGeneration} result=${if (dispatched) "dispatched" else "detached"}"
        )
    }

    LaunchedEffect(fence, documentReady, session.accessToken) {
        if (!documentReady) {
            fence.onDocumentLoading()
            return@LaunchedEffect
        }
        fence.onDocumentReady(session)?.let(::dispatch)
    }
    LaunchedEffect(fence, snapshot.event, session.accessToken) {
        val update = fence.onAuthEvent(snapshot.event, session) ?: return@LaunchedEffect
        dispatch(update)
    }
}

/**
 * Runs [block] only while the surface is shown: [visible] (its page not covered by another,
 * [rememberRaftPagerAppeared]) and the App's lifecycle at least STARTED (in the foreground). It is
 * cancelled when either stops and started again when both hold, and when one of [keys] changes.
 */
@Composable
internal fun SettingsWebViewWhileShown(visible: Boolean, vararg keys: Any?, block: suspend () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentBlock by rememberUpdatedState(block)
    LaunchedEffect(lifecycleOwner, visible, *keys) {
        if (!visible) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { currentBlock() }
    }
}

/**
 * Whether [pager] is on screen (its host's appear / disappear events: the App in the foreground and
 * the page not covered by another one). True without a page (fixtures).
 */
@Composable
internal fun rememberRaftPagerAppeared(pager: build.raft.app.compat.pager.Pager?): Boolean {
    if (pager == null) return true
    var appeared by remember(pager) { mutableStateOf(pager.isAppeared) }
    DisposableEffect(pager) {
        appeared = pager.isAppeared
        val observer =
            object : build.raft.app.compat.pager.IPagerEventObserver {
                override fun onPagerEvent(pagerEvent: String, eventData: build.raft.app.compat.json.JSONObject) {
                    when (pagerEvent) {
                        build.raft.app.compat.pager.Pager.PAGER_EVENT_DID_APPEAR -> appeared = true
                        build.raft.app.compat.pager.Pager.PAGER_EVENT_DID_DISAPPEAR -> appeared = false
                    }
                }
            }
        pager.addPagerEventObserver(observer)
        onDispose { pager.removePagerEventObserver(observer) }
    }
    return appeared
}

private val nextSettingsWebViewSessionGeneration = atomic(0L)
private val nextSettingsWebViewGeneration = atomic(0L)
