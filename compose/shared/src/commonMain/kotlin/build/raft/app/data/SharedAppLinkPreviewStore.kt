package build.raft.app.data

import build.raft.app.links.raftAppLinkReadUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
    /**
     * Batched read: the links answered `rate_limited` and not read since (the item `url` -> its
     * `retry_after` seconds). While any is left the answer is not reused by another message view:
     * mounted again, the row reads the whole message (web: such an answer is not cached).
     */
    val limited: Map<String, Int> = emptyMap(),
    /** The message view ([AppLinkCardView.id]) whose read this is; null for the GET. */
    val viewId: String? = null,
)

/** The read mode of the GET: an answer read with it is keyed as before. */
const val APP_LINK_READ_MODE_V1 = "v1"

/**
 * One mounted message view of the batched read (protocol v3.2.3 §7.1; web `LinkCardView`): the
 * `view_instance_id` Raft binds its tickets to, made once when the message is shown; the count of
 * reads it has sent (only the latest one's answer is taken); the ticket Raft gave it per link (the
 * item `url`), sent back on its next read; and its rate-limited re-reads so far. Touched only on
 * the store's own (serial) dispatcher.
 */
class AppLinkCardView internal constructor(val id: String) {
    internal var seq: Int = 0
    internal val tickets: MutableMap<String, String> = LinkedHashMap()
    internal var rereads: Int = 0

    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun create(): AppLinkCardView = AppLinkCardView(Uuid.random().toString())
    }
}

/** How a message is read with the Server's `interactive_link_cards` flag on: its [view] and the UI language. */
class AppLinkBatchRead(val view: AppLinkCardView, val locale: String) {
    /** The cache key part: what was declared and in which language (a POST is not HTTP-cacheable, so the store is the cache). */
    val mode: String get() = appLinkBatchReadMode(locale)
}

internal fun appLinkBatchReadMode(locale: String): String = "v2:${APP_LINK_CARD_READ_CAPABILITIES.joinToString(",")}:$locale"

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
    /** Moves on [SharedAppLinkPreviewStore.clearAll]: a read asked for before it is not kept. */
    val generation: Int = 0,
)

/** What a message row shows, and whether it should read (again). */
data class AppLinkMessageView(
    val results: Map<String, AppLinkResult>,
    val wantsRead: Boolean,
    /** Changes when the row should read again (another Allow while a read is on its way). */
    val readSignature: String,
    /** This message view's answer has rate_limited links to read again while the row is on screen. */
    val readsLimited: Boolean = false,
)

/**
 * The message's links that came back with a card body ([AppLinkResult.Card.card]), in link order:
 * (or [AppLinkResult.Card.cardV2]): what a row shows as App link cards. The chip of each is still in [AppLinkMessageView.results].
 */
val AppLinkMessageView.cards: List<AppLinkResult.Card>
    get() = results.values.filterIsInstance<AppLinkResult.Card>().filter { it.hasCardBody }

/** `${viewer}:${server}`: whose answers on which Server. */
fun appLinkViewerKey(viewerId: String, serverId: String): String = "$viewerId:$serverId"

internal fun appLinkAppKey(viewerKey: String, appId: String): String = "$viewerKey:$appId"

internal fun appLinkConversationKey(viewerKey: String, conversationId: String): String = "$viewerKey:$conversationId"

fun appLinkReadScope(viewerKey: String, messageId: String, content: String, mode: String = APP_LINK_READ_MODE_V1): String =
    "$viewerKey:$messageId:${content.length}:${content.hashCode()}" + if (mode == APP_LINK_READ_MODE_V1) "" else ":$mode"

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
    /** Raft's own app hosts: Artifact links on them never count as App links ([parseArtifactLinkTarget]). */
    artifactHosts: Collection<String> = emptyList(),
    /** [APP_LINK_READ_MODE_V1], or [AppLinkBatchRead.mode]: answers are kept per mode. */
    mode: String = APP_LINK_READ_MODE_V1,
    /** The batched read's mounted view ([AppLinkCardView.id]); null for the GET. */
    viewId: String? = null,
): AppLinkMessageView {
    val loaded = entries[appLinkReadScope(viewerKey, messageId, content, mode)]
        ?: return AppLinkMessageView(results = emptyMap(), wantsRead = true, readSignature = "")
    // Rate-limited links left: this view reads them again; another view reads the whole message.
    val ownsLimited = loaded.limited.isNotEmpty() && loaded.viewId == viewId
    val readWhole = loaded.limited.isNotEmpty() && !ownsLimited
    val prompts = loaded.results.values.filterIsInstance<AppLinkResult.Consent>()
    if (prompts.isEmpty()) return AppLinkMessageView(loaded.results, wantsRead = readWhole, readSignature = "", readsLimited = ownsLimited)
    val conversationKey = appLinkConversationKey(viewerKey, conversationId)
    val results = LinkedHashMap(loaded.results)
    var stale = false
    val linkLimit = if (mode == APP_LINK_READ_MODE_V1) APP_LINK_PREVIEW_V1_LINK_LIMIT else APP_LINK_CARD_READ_MAX_URLS
    val links by lazy { raftAppLinkReadUrls(content, artifactHosts, linkLimit) }
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
    return AppLinkMessageView(results = results, wantsRead = stale || readWhole, readSignature = signature, readsLimited = ownsLimited)
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
    /** Raft's own app hosts (client config): Artifact links on them are left out as the server does. */
    internal val artifactHosts: () -> Collection<String> = { emptyList() },
    /** Up to a second added at random to each rate-limited re-read's wait (tests: 0). */
    private val retryJitterMs: () -> Long = { Random.nextLong(APP_LINK_CARD_RETRY_JITTER_MS) },
) {
    private val _state = raftStoreState(initialState, "SharedAppLinkPreviewStore")
    val state: StateFlow<SharedAppLinkPreviewState> = _state.asStateFlow()

    private val inFlight = mutableSetOf<String>()

    /** A server answered the batched read 404 and then the GET: this session reads with the GET (web `batchedReadMissing`). */
    private var batchedReadMissing = false

    /**
     * One message's [AppLinkMessageView] (its chips, cards and whether it should read), emitted
     * only when that message's view changes: the selector a row collects instead of [state].
     */
    fun messageViewFlow(viewerKey: String, messageId: String, content: String, conversationId: String): Flow<AppLinkMessageView> =
        state.map { it.messageView(viewerKey, messageId, content, conversationId, artifactHosts()) }.distinctUntilChanged()

    /**
     * Drops every answer and every consent status (sign-out, account or environment switch).
     * Entries are keyed by viewer and Server, so a Server switch needs no clear. A read on its
     * way when this runs is not kept.
     */
    fun clearAll() {
        _state.update { SharedAppLinkPreviewState(generation = it.generation + 1) }
    }
    private var epochSeq = initialState.epoch.values.maxOrNull() ?: 0

    /**
     * Reads one message's App links unless its answer is current (or a read is on its way). A
     * failed read is not kept, so the row tries again when it is composed again; a read again
     * after Allow that fails drops that App's pending chips, not the links. With [batch] (the
     * Server's `interactive_link_cards` flag on) it is the batched link-card read of that message
     * view, else the GET.
     */
    fun read(viewerKey: String, serverId: String, messageId: String, content: String, conversationId: String, batch: AppLinkBatchRead? = null) {
        if (!appLinkPreviewsApply(content)) return
        scope.launch { readOnStore(viewerKey, serverId, messageId, content, conversationId, batch) }
    }

    private suspend fun readOnStore(
        viewerKey: String,
        serverId: String,
        messageId: String,
        content: String,
        conversationId: String,
        batch: AppLinkBatchRead?,
    ) {
        val mode = batch?.mode ?: APP_LINK_READ_MODE_V1
        val readScope = appLinkReadScope(viewerKey, messageId, content, mode)
        val before = _state.value
        if (!before.messageView(viewerKey, messageId, content, conversationId, artifactHosts(), mode, batch?.view?.id).wantsRead) return
        if (!inFlight.add(readScope)) return
        val askedAt = before.epoch
        val askedGeneration = before.generation
        val fetched =
            try {
                fetch(messageId, serverId, batch, urls = null)
            } finally {
                inFlight.remove(readScope)
            }
        val answer =
            when (fetched) {
                AppLinkFetch.Stale -> return
                AppLinkFetch.Failed -> null
                is AppLinkFetch.Answer -> fetched
            }
        if (answer == null) {
            _state.update { state ->
                if (state.generation != askedGeneration) return@update state
                val previous = state.entries[readScope] ?: return@update state
                val kept =
                    previous.results.filterValues { result ->
                        result !is AppLinkResult.Consent ||
                            state.status[appLinkAppKey(viewerKey, result.app.id)] != AppLinkConsentStatus.Allowed
                    }
                state.copy(entries = state.entries + (readScope to previous.copy(results = kept, epochs = askedAt)))
            }
            return
        }
        // Ask only on a link that can become the chip: a bare link of this content.
        val hosts = artifactHosts()
        val bare = raftAppLinkReadUrls(content, hosts, linkLimit(batch)).toSet()
        val results = answer.results.filterValues { it !is AppLinkResult.Consent || it.url in bare }
        val conversationKey = appLinkConversationKey(viewerKey, conversationId)
        _state.update { state ->
            if (state.generation != askedGeneration) return@update state
            val claims = state.claims.toMutableMap()
            results.values.filterIsInstance<AppLinkResult.Consent>().forEach { prompt ->
                val key = "$conversationKey:${prompt.app.id}"
                if (key !in claims) claims[key] = messageId
            }
            val entries = LinkedHashMap(state.entries)
            entries.remove(readScope)
            entries[readScope] = AppLinkLoaded(results, askedAt, limited = answer.limited, viewId = batch?.view?.id)
            while (entries.size > APP_LINK_RESULT_CACHE_MAX) entries.remove(entries.keys.first())
            state.copy(entries = entries, claims = claims)
        }
    }

    /** The links a read covers: ten for the batched read, three for the GET. */
    private fun linkLimit(batch: AppLinkBatchRead?): Int =
        if (batch != null && !batchedReadMissing) APP_LINK_CARD_READ_MAX_URLS else APP_LINK_PREVIEW_V1_LINK_LIMIT

    /**
     * While this message view is on screen ([batch]'s answer has rate_limited links), reads those
     * links again, only those (web `useAppLinkPreviews` re-reads): never sooner than Raft's
     * `retry_after`, each wait double the last, 1-60 s ([appLinkRereadBackoffMs]) plus up to a
     * second of jitter, at most [APP_LINK_CARD_READ_RETRIES] per view, never while a read of the
     * whole message is on its way. The caller cancels the job when the row leaves the screen.
     */
    fun keepReadingLimited(
        viewerKey: String,
        serverId: String,
        messageId: String,
        content: String,
        batch: AppLinkBatchRead,
    ): Job =
        scope.launch {
            val view = batch.view
            val readScope = appLinkReadScope(viewerKey, messageId, content, batch.mode)
            val askedGeneration = _state.value.generation
            fun limitedOfView(): Map<String, Int>? =
                _state.value.entries[readScope]?.takeIf { it.viewId == view.id && _state.value.generation == askedGeneration }
                    ?.limited?.takeIf { it.isNotEmpty() }
            var asked: Int? = limitedOfView()?.values?.maxOrNull() ?: return@launch
            var lastBackoffMs = 0L
            while (view.rereads < APP_LINK_CARD_READ_RETRIES) {
                limitedOfView() ?: return@launch
                val backoffMs = appLinkRereadBackoffMs(lastBackoffMs, asked)
                delay(minOf(60_000L, backoffMs + retryJitterMs()))
                lastBackoffMs = backoffMs
                // The whole read on its way covers these links (and a re-read sent now would be the view's latest).
                if (readScope in inFlight) {
                    asked = null
                    continue
                }
                val urls = limitedOfView()?.keys?.toList() ?: return@launch
                view.rereads += 1
                val fetched = fetch(messageId, serverId, batch, urls)
                if (fetched !is AppLinkFetch.Answer) {
                    asked = null
                    continue
                }
                val readKeys = urls.mapNotNull(::appLinkPreviewHref).toSet()
                _state.update { state ->
                    if (state.generation != askedGeneration) return@update state
                    val entry = state.entries[readScope]?.takeIf { it.viewId == view.id } ?: return@update state
                    val read = fetched.results.filter { (key, result) -> key in readKeys && key !in entry.results && result !is AppLinkResult.Consent }
                    val limited = entry.limited.filterKeys { it !in urls } + fetched.limited
                    state.copy(entries = state.entries + (readScope to entry.copy(results = entry.results + read, limited = limited)))
                }
                asked = fetched.limited.values.maxOrNull()
            }
        }

    /**
     * One read of a message's App links: the batched read of [batch]'s view (with its
     * capabilities, language and tickets, and [urls] to read only those again) or, without one or
     * once this session found no batched read, the GET (a re-read of some links has no GET form).
     * An answer to a request that is no longer the view's latest is [AppLinkFetch.Stale].
     */
    private suspend fun fetch(messageId: String, serverId: String, batch: AppLinkBatchRead?, urls: List<String>?): AppLinkFetch {
        if (batch == null || batchedReadMissing) return if (urls != null) AppLinkFetch.Failed else fetchWithGet(messageId, serverId)
        val view = batch.view
        view.seq += 1
        val seq = view.seq
        val tickets = view.tickets.filterKeys { urls == null || it in urls }
        val body =
            AppLinkCardReadRequestBody(
                viewInstanceId = view.id,
                capabilities = APP_LINK_CARD_READ_CAPABILITIES,
                locale = batch.locale,
                urls = urls,
                tickets = tickets.takeIf { it.isNotEmpty() },
            )
        val response = callOrNull("app_link_cards_read_error") { api.readLinkCards(messageId, serverId, body) }
        if (response?.code == 404 && urls == null) {
            val read = fetchWithGet(messageId, serverId)
            if (read is AppLinkFetch.Answer) batchedReadMissing = true
            return if (seq == view.seq) read else AppLinkFetch.Stale
        }
        if (seq != view.seq) return AppLinkFetch.Stale
        val parsed = response?.takeIf { it.isSuccessful }?.bodyText?.let { parseAppLinkCardReadAnswer(it, resolveIcon) }
        if (parsed == null) {
            diagnosticsLogger("app_link_cards_read_failed code=${response?.code ?: -1}")
            return AppLinkFetch.Failed
        }
        parsed.tickets.forEach { (url, ticket) -> if (ticket != null) view.tickets[url] = ticket else view.tickets.remove(url) }
        return AppLinkFetch.Answer(parsed.results, parsed.limited)
    }

    private suspend fun fetchWithGet(messageId: String, serverId: String): AppLinkFetch {
        val response = callOrNull("app_link_previews_read_error") { api.readPreviews(messageId, serverId) }
        val parsed = response?.takeIf { it.isSuccessful }?.bodyText?.let { parseAppLinkPreviewAnswer(it, resolveIcon) }
        if (parsed == null) {
            diagnosticsLogger("app_link_previews_read_failed code=${response?.code ?: -1}")
            return AppLinkFetch.Failed
        }
        return AppLinkFetch.Answer(parsed, emptyMap())
    }

    /** Prepares a `confirm: "prepare"` button: the description Raft's confirmation shows. */
    suspend fun prepareAction(serverId: String, handle: String, businessRevision: String): AppLinkActionResult<AppLinkPrepared> {
        val response = callOrNull("app_link_card_prepare_error") { api.prepareLinkCardAction(serverId, AppLinkPrepareRequestBody(handle, AppLinkPrepareClaim(businessRevision))) }
            ?: return AppLinkActionResult.Failed(null, null, outOfDate = false)
        return parseAppLinkPrepareAnswer(response.code, response.bodyText ?: response.errorBodyText)
    }

    /** Runs a button's action (after its confirmation, when it has one). The same [clientRequestId] repeats the same intent. */
    suspend fun executeAction(
        serverId: String,
        handle: String,
        clientRequestId: String,
        prepared: AppLinkPrepared?,
    ): AppLinkActionResult<AppLinkExecuted> {
        val body = AppLinkExecuteRequestBody(clientRequestId, handle, prepared?.preparedActionId, prepared?.preparedDigest)
        val response = callOrNull("app_link_card_execute_error") { api.executeLinkCardAction(serverId, body) }
            ?: return AppLinkActionResult.Failed(null, null, outOfDate = false)
        return parseAppLinkExecuteAnswer(response.code, response.bodyText ?: response.errorBodyText)
    }

    /**
     * Reads one link of a message again (after a button was pressed, or its card went stale): the
     * batched read of [batch]'s view for [url] alone, which sends back the view's ticket. Its answer
     * replaces that link's result; a link that is no longer previewed keeps what is shown.
     *
     * False when the read failed or Raft answered the link `rate_limited` (the card stays as it
     * was): a Refresh says so (web `LinkCardReread` `failed`). A read superseded by a later one
     * of the view is not a failure.
     */
    suspend fun rereadLink(viewerKey: String, serverId: String, messageId: String, content: String, batch: AppLinkBatchRead, url: String): Boolean {
        val readScope = appLinkReadScope(viewerKey, messageId, content, batch.mode)
        val askedGeneration = _state.value.generation
        val fetched =
            when (val read = fetch(messageId, serverId, batch, listOf(url))) {
                is AppLinkFetch.Answer -> read
                AppLinkFetch.Failed -> return false
                AppLinkFetch.Stale -> return true
            }
        if (url in fetched.limited) return false
        val fresh = fetched.results[url]?.takeIf { it is AppLinkResult.Card } ?: return true
        _state.update { state ->
            if (state.generation != askedGeneration) return@update state
            val entry = state.entries[readScope]?.takeIf { it.viewId == batch.view.id } ?: return@update state
            state.copy(entries = state.entries + (readScope to entry.copy(results = entry.results + (url to fresh))))
        }
        return true
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

    private suspend fun callOrNull(
        event: String = "app_link_previews_answer_error",
        block: suspend () -> HomeNetworkResponse,
    ): HomeNetworkResponse? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            diagnosticsLogger("$event error=${error::class.simpleName}")
            null
        }
}

/** What one read came to ([SharedAppLinkPreviewStore] `fetch`). */
private sealed interface AppLinkFetch {
    data class Answer(val results: Map<String, AppLinkResult>, val limited: Map<String, Int>) : AppLinkFetch

    data object Failed : AppLinkFetch

    /** The view sent a later request meanwhile: this answer is dropped. */
    data object Stale : AppLinkFetch
}

private const val APP_LINK_RESULT_CACHE_MAX = 300
