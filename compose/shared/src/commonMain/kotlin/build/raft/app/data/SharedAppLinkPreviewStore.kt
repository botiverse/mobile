package build.raft.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The answer to the first-view prompt for one App, on one Server, for one viewer (web
 * `linkPreviewConsentStore` `LinkPreviewAnswerStatus`).
 *
 * - [Allowing] / [Declining]: on its way. [Failed]: allowing failed; the prompt offers Retry.
 * - [Allowed]: consent recorded; the App's epoch moved, so the messages that asked read again.
 * - [Declined] / [Unavailable]: no prompt anywhere; the links stay plain.
 */
enum class AppLinkConsentStatus { Allowing, Declining, Failed, Allowed, Declined, Unavailable }

/** One message's answer, as read (web `Loaded`). */
data class AppLinkLoaded(
    val results: Map<String, AppLinkResult>,
    /** The consent epochs when it was asked for: a later Allow makes it stale. */
    val epochs: Map<String, Int>,
)

data class SharedAppLinkPreviewState(
    /** By [appLinkReadScope]: viewer, Server, message and content. Bounded, most recently read last. */
    val entries: Map<String, AppLinkLoaded> = emptyMap(),
    /** By [appLinkAppKey]. */
    val status: Map<String, AppLinkConsentStatus> = emptyMap(),
    /** By [appLinkAppKey]: moves each time an App is allowed. */
    val epoch: Map<String, Int> = emptyMap(),
    /**
     * `${conversationKey}:${appId}` -> the message that asks. An App is asked once per
     * conversation, on the first message whose answer comes back with its prompt; the others
     * keep the plain link for the session.
     */
    val claims: Map<String, String> = emptyMap(),
)

/** What a message row shows, and whether it should read (again). */
data class AppLinkMessageView(
    val results: Map<String, AppLinkResult>,
    val wantsRead: Boolean,
    /** Changes when the row should read again (another Allow while a read is on its way). */
    val readSignature: String,
)

/** `${viewer}:${server}`: whose answers on which Server. */
fun appLinkViewerKey(viewerId: String, serverId: String): String = "$viewerId:$serverId"

internal fun appLinkAppKey(viewerKey: String, appId: String): String = "$viewerKey:$appId"

internal fun appLinkConversationKey(viewerKey: String, conversationId: String): String = "$viewerKey:$conversationId"

internal fun appLinkReadScope(viewerKey: String, messageId: String, content: String): String =
    "$viewerKey:$messageId:${content.length}:${content.hashCode()}"

/** Whether a message is read at all: ordinary text with an https link (web `active`). */
fun appLinkPreviewsApply(content: String): Boolean = content.contains("https://")

/**
 * What one message shows (the end of web `useAppLinkPreviews`): its answer, with each prompt
 * shown only on the message that claimed it in this conversation, hidden once its App is
 * declined or can't be answered, and, once the App is allowed since the answer was read, the
 * App's bare links waiting as pending chips while they are read again.
 */
fun SharedAppLinkPreviewState.messageView(
    viewerKey: String,
    messageId: String,
    content: String,
    conversationId: String,
): AppLinkMessageView {
    val loaded = entries[appLinkReadScope(viewerKey, messageId, content)]
        ?: return AppLinkMessageView(results = emptyMap(), wantsRead = true, readSignature = "")
    val prompts = loaded.results.values.filterIsInstance<AppLinkResult.Consent>()
    if (prompts.isEmpty()) return AppLinkMessageView(loaded.results, wantsRead = false, readSignature = "")
    val conversationKey = appLinkConversationKey(viewerKey, conversationId)
    val results = LinkedHashMap(loaded.results)
    var stale = false
    val links by lazy { appLinkMessagePreviewUrls(content) }
    for (prompt in prompts) {
        val appKey = appLinkAppKey(viewerKey, prompt.app.id)
        val allowedSince = (epoch[appKey] ?: 0) > (loaded.epochs[appKey] ?: 0)
        stale = stale || allowedSince
        results.remove(prompt.url)
        if (claims["$conversationKey:${prompt.app.id}"] != messageId) continue
        if (allowedSince) {
            val origin = prompt.url.appLinkOrigin()
            for (url in listOf(prompt.url) + links) {
                if (url.appLinkOrigin() == origin && url !in results) results[url] = AppLinkResult.Pending(url)
            }
            continue
        }
        val status = status[appKey]
        if (status != AppLinkConsentStatus.Declined && status != AppLinkConsentStatus.Unavailable) results[prompt.url] = prompt
    }
    val signature = prompts.joinToString(",") { (epoch[appLinkAppKey(viewerKey, it.app.id)] ?: 0).toString() }
    return AppLinkMessageView(results = results, wantsRead = stale, readSignature = signature)
}

private fun String.appLinkOrigin(): String = "https://" + substringAfter("://").substringBefore('/').substringBefore('?')

/**
 * App link previews for every message on screen (web `useAppLinkPreviews` +
 * `linkPreviewConsentStore`): one read per message once its row is composed, kept in memory
 * for the session per viewer, Server, message and content; one answer to an App's prompt
 * updates every message. Nothing is persisted; the server is the record.
 */
class SharedAppLinkPreviewStore internal constructor(
    private val api: RaftAppLinkPreviewApi,
    private val scope: CoroutineScope,
    private val resolveIcon: (String) -> String? = { it },
    private val diagnosticsLogger: (String) -> Unit = {},
    initialState: SharedAppLinkPreviewState = SharedAppLinkPreviewState(),
) {
    private val _state = raftStoreState(initialState, "SharedAppLinkPreviewStore")
    val state: StateFlow<SharedAppLinkPreviewState> = _state.asStateFlow()

    private val inFlight = mutableSetOf<String>()
    private var epochSeq = initialState.epoch.values.maxOrNull() ?: 0

    /**
     * Reads one message's App links unless its answer is current (or a read is on its way). A
     * failed read is not kept, so the row tries again when it is composed again; a read again
     * after Allow that fails drops that App's pending chips, not the links.
     */
    fun read(viewerKey: String, serverId: String, messageId: String, content: String, conversationId: String) {
        if (!appLinkPreviewsApply(content)) return
        scope.launch { readOnStore(viewerKey, serverId, messageId, content, conversationId) }
    }

    private suspend fun readOnStore(viewerKey: String, serverId: String, messageId: String, content: String, conversationId: String) {
        val readScope = appLinkReadScope(viewerKey, messageId, content)
        val before = _state.value
        if (!before.messageView(viewerKey, messageId, content, conversationId).wantsRead) return
        if (!inFlight.add(readScope)) return
        val askedAt = before.epoch
        val response =
            try {
                api.readPreviews(messageId, serverId)
            } catch (cancellation: CancellationException) {
                inFlight.remove(readScope)
                throw cancellation
            } catch (error: Throwable) {
                diagnosticsLogger("app_link_previews_read_error error=${error::class.simpleName}")
                null
            }
        inFlight.remove(readScope)
        val parsed = response?.takeIf { it.isSuccessful }?.bodyText?.let { parseAppLinkPreviewAnswer(it, resolveIcon) }
        if (parsed == null) {
            diagnosticsLogger("app_link_previews_read_failed code=${response?.code ?: -1}")
            _state.update { state ->
                val previous = state.entries[readScope] ?: return@update state
                val kept =
                    previous.results.filterValues { result ->
                        result !is AppLinkResult.Consent ||
                            state.status[appLinkAppKey(viewerKey, result.app.id)] != AppLinkConsentStatus.Allowed
                    }
                state.copy(entries = state.entries + (readScope to AppLinkLoaded(kept, askedAt)))
            }
            return
        }
        // Ask only on a link that can become the chip: a bare link of this content.
        val bare = appLinkMessagePreviewUrls(content).toSet()
        val results = parsed.filterValues { it !is AppLinkResult.Consent || it.url in bare }
        val conversationKey = appLinkConversationKey(viewerKey, conversationId)
        _state.update { state ->
            val claims = state.claims.toMutableMap()
            results.values.filterIsInstance<AppLinkResult.Consent>().forEach { prompt ->
                val key = "$conversationKey:${prompt.app.id}"
                if (key !in claims) claims[key] = messageId
            }
            val entries = LinkedHashMap(state.entries)
            entries.remove(readScope)
            entries[readScope] = AppLinkLoaded(results, askedAt)
            while (entries.size > APP_LINK_RESULT_CACHE_MAX) entries.remove(entries.keys.first())
            state.copy(entries = entries, claims = claims)
        }
    }

    /** Allows the App's previews here; the messages that asked read again. */
    fun allow(viewerKey: String, serverId: String, app: AppLinkPromptApp): Deferred<AppLinkConsentAnswer> =
        scope.async {
            val key = appLinkAppKey(viewerKey, app.id)
            setStatus(key, AppLinkConsentStatus.Allowing)
            val answer = appLinkConsentAnswerFor(callOrNull { api.allow(app.id, serverId) }, AppLinkConsentAnswer.Allowed)
            when (answer) {
                AppLinkConsentAnswer.Allowed -> {
                    epochSeq += 1
                    val epoch = epochSeq
                    _state.update { it.copy(status = it.status + (key to AppLinkConsentStatus.Allowed), epoch = it.epoch + (key to epoch)) }
                }
                AppLinkConsentAnswer.Unavailable -> setStatus(key, AppLinkConsentStatus.Unavailable)
                else -> setStatus(key, AppLinkConsentStatus.Failed)
            }
            answer
        }

    /** Declines the App's previews here: no prompt anywhere, the links stay plain. */
    fun decline(viewerKey: String, serverId: String, app: AppLinkPromptApp): Deferred<AppLinkConsentAnswer> =
        scope.async {
            val key = appLinkAppKey(viewerKey, app.id)
            val previous = _state.value.status[key]
            setStatus(key, AppLinkConsentStatus.Declining)
            val answer = appLinkConsentAnswerFor(callOrNull { api.decline(app.id, serverId) }, AppLinkConsentAnswer.Declined)
            when (answer) {
                AppLinkConsentAnswer.Declined -> setStatus(key, AppLinkConsentStatus.Declined)
                AppLinkConsentAnswer.Unavailable -> setStatus(key, AppLinkConsentStatus.Unavailable)
                else ->
                    // Nothing changed on the server: put the prompt back as it was.
                    _state.update { state ->
                        val status =
                            if (previous != null && previous != AppLinkConsentStatus.Declining) {
                                state.status + (key to previous)
                            } else {
                                state.status - key
                            }
                        state.copy(status = status)
                    }
            }
            answer
        }

    private fun setStatus(key: String, status: AppLinkConsentStatus) {
        _state.update { it.copy(status = it.status + (key to status)) }
    }

    private suspend fun callOrNull(block: suspend () -> HomeNetworkResponse): HomeNetworkResponse? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            diagnosticsLogger("app_link_previews_answer_error error=${error::class.simpleName}")
            null
        }
}

private const val APP_LINK_RESULT_CACHE_MAX = 300
