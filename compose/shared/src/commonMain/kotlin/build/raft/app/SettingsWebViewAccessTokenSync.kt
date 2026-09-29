package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.data.AuthSessionEvent
import build.raft.app.data.SlockAuthSessionEventSource
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.runCatchingPreservingCancellation
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onSubscription

internal enum class SettingsWebViewSurface(val diagnosticsName: String) {
    Onboarding("onboarding"),
    Computers("computers"),
    Billing("billing"),
    Marketplace("marketplace"),
    Administration("administration"),
    ReleaseNotes("release-notes"),
}

internal data class SettingsWebViewAuthSnapshot(
    val session: StoredAuthSession? = null,
    val event: AuthSessionEvent? = null,
    val sessionGeneration: Long = 0L,
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
        ).also { reduced = it }
    }
}

@Composable
internal fun rememberSettingsWebViewAuthSnapshot(
    shellAuthStore: SlockAuthSessionStore,
    processAuthEvents: SlockAuthSessionEventSource,
): SettingsWebViewAuthSnapshot {
    var snapshot by remember(shellAuthStore, processAuthEvents) { mutableStateOf(SettingsWebViewAuthSnapshot()) }
    val reducer =
        remember(shellAuthStore, processAuthEvents) {
            SettingsWebViewAuthSnapshotReducer { nextSettingsWebViewSessionGeneration.incrementAndGet() }
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
    controller: SlockWebViewController,
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
    controller: SlockWebViewController,
    binding: SettingsWebViewHostAuthBinding?,
    surface: SettingsWebViewSurface,
    logger: (String) -> Unit = {},
) {
    val session = snapshot.session ?: return
    val activeBinding = binding ?: return
    val fence = activeBinding.fence

    DisposableEffect(fence) {
        onDispose { fence.detach() }
    }

    fun dispatch(update: SettingsWebViewAccessTokenUpdate) {
        val dispatched =
            controller.evaluateJavaScript(
                settingsWebViewRuntimeAccessTokenJavaScript(
                    accessToken = update.accessToken,
                    accountId = update.accountId,
                    serverId = update.serverId,
                    sessionGeneration = update.sessionGeneration,
                    webViewGeneration = update.webViewGeneration,
                    sequence = update.sequence,
                )
            )
        if (!dispatched) fence.onDispatchFailed(update)
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

private val nextSettingsWebViewSessionGeneration = atomic(0L)
private val nextSettingsWebViewGeneration = atomic(0L)
