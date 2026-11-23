package build.raft.app.data

import androidx.compose.runtime.Composable
import build.raft.app.raftLogInfo
import build.raft.app.raftSha256Hex
import kotlin.io.encoding.Base64
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The app side of the web step-up (AppWebStepUpApi.kt): the request this app is waiting for, kept
 * on disk like the browser login's ([AppLoginPending]) in the same `slockAppLogin` store under its
 * own key, so a callback for it is never taken for a browser login and a late or repeated one is
 * recognised as stale. [startedAt]: when its page opened. [trace]: its attempt's span, for the next
 * launch to report if the app is killed with the page up (AppWebStepUpTrace.kt; absent on a request
 * saved before the span existed).
 */
@Serializable
internal data class AppWebStepUpPending(
    val generation: String,
    val requestId: String,
    val verifier: String,
    val returnUri: String,
    val startedAt: Long,
    val trace: AppWebStepUpPendingTrace? = null,
)

/** Where [AppWebStepUpPending] lives: the browser login's store, its own key. */
internal const val APP_WEB_STEP_UP_PENDING_KEY = "step_up_pending"

/** As the browser login: older than this is stale (the server's request lives 5 min). */
private const val APP_WEB_STEP_UP_FRESH_MS = 600_000L

internal class AppWebStepUpPendingStore(
    private val store: () -> RaftKeyValueStore?,
    val now: () -> Long = ::defaultNowMillis,
) {
    private val lock = SynchronizedObject()

    /** The request this store last saved: one store per launch ([RaftAppWebStepUp.pending]), so this launch's own. */
    private var savedHere: String? = null

    fun read(): AppWebStepUpPending? =
        runCatching {
            Json.decodeFromString(AppWebStepUpPending.serializer(), store()?.getString(APP_WEB_STEP_UP_PENDING_KEY).orEmpty())
        }.getOrNull()

    fun save(pending: AppWebStepUpPending) {
        synchronized(lock) { savedHere = pending.generation }
        store()?.putString(APP_WEB_STEP_UP_PENDING_KEY, Json.encodeToString(AppWebStepUpPending.serializer(), pending))
    }

    /**
     * The request on file when an earlier launch saved it: that launch was killed with the page up,
     * and no dialog of this one waits for it ([RaftAppWebStepUp.offerCallback] drops its callback), so
     * it can never finish. Null when there is none, or it is this launch's own.
     */
    fun leftover(): AppWebStepUpPending? {
        val onFile = read() ?: return null
        val own = synchronized(lock) { savedHere }
        return onFile.takeIf { it.generation != own }
    }

    fun clear() {
        store()?.putString(APP_WEB_STEP_UP_PENDING_KEY, null)
    }

    fun isFresh(pending: AppWebStepUpPending): Boolean = (now() - pending.startedAt) in -60_000L until APP_WEB_STEP_UP_FRESH_MS

    /** Whether [requestId] is the step-up on file, not stale. */
    fun isWaitingFor(requestId: String): Boolean {
        val pending = read() ?: return false
        return requestId.isNotBlank() && pending.requestId == requestId && isFresh(pending)
    }
}

/** RFC 7636 S256 PKCE pair. */
internal data class AppWebStepUpPkce(val verifier: String, val challenge: String)

private val pkceBase64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

/** `BASE64URL(SHA256(ASCII(verifier)))`. */
internal fun appWebStepUpChallenge(verifier: String): String {
    val hex = raftSha256Hex(verifier.encodeToByteArray())
    val digest = ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    return pkceBase64.encode(digest)
}

/** A fresh verifier from 48 bytes of [Uuid.random] (a CSPRNG on every platform): 64 base64url characters. */
@OptIn(ExperimentalUuidApi::class)
internal fun newAppWebStepUpPkce(): AppWebStepUpPkce {
    val random = Uuid.random().toByteArray() + Uuid.random().toByteArray() + Uuid.random().toByteArray()
    val verifier = pkceBase64.encode(random)
    return AppWebStepUpPkce(verifier, appWebStepUpChallenge(verifier))
}

@OptIn(ExperimentalUuidApi::class)
internal fun newAppWebStepUpGeneration(): String = "app-step-up-${Uuid.random()}"

/**
 * What [SecondFactorStepUpCoordinator] needs for Continue in browser: the two authenticated calls
 * (this sign-in's bearer), the callback [returnUri] this build registers, the pending entry, PKCE,
 * and where each attempt's `mobile.auth.web_step_up` span goes ([tracer], AppWebStepUpTrace.kt).
 * Only built where the platform runs the web session ([secondFactorWebStepUp]).
 */
class SecondFactorWebStepUp internal constructor(
    internal val start: suspend (AppWebStepUpStartBody) -> AppWebStepUpStartResult,
    internal val complete: suspend (AppWebStepUpCompleteBody) -> AppWebStepUpCompleteResult,
    internal val returnUri: String,
    internal val pending: AppWebStepUpPendingStore = RaftAppWebStepUp.pending,
    internal val pkce: () -> AppWebStepUpPkce = ::newAppWebStepUpPkce,
    internal val generation: () -> String = ::newAppWebStepUpGeneration,
    internal val log: (String) -> Unit = RaftAppWebStepUp::log,
    internal val tracer: AppWebStepUpTracer = AppWebStepUpTracer(),
)

/**
 * Continue in browser for this platform, or null where the step-up has none: only iOS, whose
 * native passkeys can't use the legacy RP ID (Android asserts both natively; the desktop app keeps
 * Open on the web). [returnUri]: the platform's browser-login callback.
 */
internal fun secondFactorWebStepUp(
    start: suspend (AppWebStepUpStartBody) -> AppWebStepUpStartResult,
    complete: suspend (AppWebStepUpCompleteBody) -> AppWebStepUpCompleteResult,
    returnUri: String? = raftAppWebStepUpReturnUri(),
): SecondFactorWebStepUp? {
    val uri = returnUri?.takeIf { it in APP_LOGIN_RETURN_URIS } ?: return null
    return SecondFactorWebStepUp(start, complete, uri)
}

/**
 * The process-wide side of the web step-up: the pending entry on disk, and the callbacks the app
 * is handed from outside the web session (an `openURL` of `<scheme>://login/callback` naming the
 * step-up's request, [RaftAppLoginBridge.completeCallbackUri]); the open dialog forwards them to
 * its coordinator.
 */
internal object RaftAppWebStepUp {
    private val store: RaftKeyValueStore? by lazy {
        runCatching { RaftSharedKeyValueStoreProvider.create(RAFT_APP_LOGIN_MMKV_NAMESPACE, "App web step-up").store }
            .onFailure { log("app_web_step_up_store_unavailable type=${it::class.simpleName}") }
            .getOrNull()
    }

    val pending = AppWebStepUpPendingStore({ store })

    private val mutableCallbacks = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val callbacks: SharedFlow<String> = mutableCallbacks.asSharedFlow()

    fun log(message: String) = raftLogInfo("SlockAppWebStepUp", message)

    /**
     * Takes [callback] ([uri]) when it names the step-up this app is waiting for; false leaves it to
     * the browser login. With no dialog open to take it (the app was restarted) the entry is dropped.
     */
    fun offerCallback(callback: AppLoginCallback, uri: String): Boolean {
        if (raftAppWebStepUpReturnUri() == null) return false
        if (!pending.isWaitingFor(callback.requestId)) return false
        if (mutableCallbacks.subscriptionCount.value == 0) {
            pending.clear()
            log("app_web_step_up_callback result=dropped reason=no_dialog")
            return true
        }
        val emitted = mutableCallbacks.tryEmit(uri)
        log("app_web_step_up_callback source=open_url forwarded=$emitted")
        return true
    }

    /**
     * Signed in ([RaftProductEvents.setSignedIn]): a step-up an earlier launch was killed in, its page
     * up, is reported as `abandoned` now that the span can be sent ([reportAppWebStepUpLeftover]).
     */
    fun onSignedIn() {
        if (raftAppWebStepUpReturnUri() == null) return
        try {
            reportAppWebStepUpLeftover(pending, AppWebStepUpTracer()) { message -> log(message) }
        } catch (_: Throwable) {
            // Telemetry never affects signing in.
        }
    }
}

/**
 * The browser-login callback URI of this build where the platform runs the web step-up in a
 * system web session (iOS: `<SlockOAuthCallbackScheme>://login/callback`); null elsewhere.
 */
internal expect fun raftAppWebStepUpReturnUri(): String?

/** The platform's web session for the step-up page. */
fun interface RaftAppWebStepUpBrowser {
    /** Opens [url]; returns once the page called back to [callbackScheme] or the session ended. */
    suspend fun open(url: String, callbackScheme: String): RaftAppWebStepUpBrowserResult
}

sealed interface RaftAppWebStepUpBrowserResult {
    /** The page handed back to the app: `<scheme>://login/callback?requestId=…&code=…|error=…`. */
    data class Callback(val uri: String) : RaftAppWebStepUpBrowserResult
    /** The person closed the sheet. */
    data object Closed : RaftAppWebStepUpBrowserResult
    /** It could not be shown; [reason] is a log-safe code. */
    data class Failed(val reason: String) : RaftAppWebStepUpBrowserResult
}

/** iOS: `ASWebAuthenticationSession` from this screen's window; elsewhere always [RaftAppWebStepUpBrowserResult.Failed]. */
@Composable
internal expect fun rememberRaftAppWebStepUpBrowser(): RaftAppWebStepUpBrowser
