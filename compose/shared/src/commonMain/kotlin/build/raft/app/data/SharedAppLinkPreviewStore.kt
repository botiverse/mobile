package build.raft.app.data

import build.raft.app.links.raftAppLinkReadUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Clock
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
    /** The Server it was read on: a `link_card.changed` nudge and a socket's return are that Server's. */
    val serverId: String? = null,
    /**
     * Not 0: one of its cards may be out of date (web `Loaded.changed`), because a `link_card.changed`
     * nudge named its object while no row showed it, or the app came back since it was read. The
     * answer stays as it is; a row shown with it reads those cards again once it is on screen
     * ([SharedAppLinkPreviewStore.keepFresh]). The number tells one mark from the next.
     */
    val changed: Int = 0,
) {
    /** The links whose card carries [objectKey] ([APP_LINK_ALL_OBJECTS]: any digest), in link order. */
    internal fun keyedUrls(objectKey: String): List<String> =
        results.values.mapNotNull { result ->
            val key = (result as? AppLinkResult.Card)?.objectKey ?: return@mapNotNull null
            result.url.takeIf { objectKey == APP_LINK_ALL_OBJECTS || key == objectKey }
        }
}

/** Every card whose App named its object, whichever it is (the catch-up after the app came back). */
internal const val APP_LINK_ALL_OBJECTS = "*"

/** Web `LINK_CARD_CHANGED_JITTER_MS`: a nudged card is read again after a random 1 ms to this, so its readers don't all ask the App at once. */
internal const val APP_LINK_CARD_CHANGED_JITTER_MS = 2_000L

/** Web `LINK_CARD_CHANGED_MIN_INTERVAL_MS`: an object is read again at most once per this per message view. */
internal const val APP_LINK_CARD_CHANGED_MIN_INTERVAL_MS = 10_000L

/** Web `LINK_CARD_REENTRY_COOLDOWN_MS`: a message whose read failed is read again no sooner than this after that read went out. */
internal const val APP_LINK_CARD_REENTRY_COOLDOWN_MS = 30_000L

/**
 * One message row on a page (web: a mounted message view and its `target` element): what the store
 * needs to read its cards again at the right moment. The row says when it is on screen, when the
 * person is on it and which cards are busy; [SharedAppLinkPreviewStore.keepFresh] does the rest.
 * The setters may be called from the UI thread; everything else is the store's.
 */
class AppLinkRowPresence internal constructor(
    internal val viewerKey: String,
    internal val serverId: String,
    internal val messageId: String,
    internal val content: String,
    internal val batch: AppLinkBatchRead?,
) {
    internal val readScope: String = appLinkReadScope(viewerKey, messageId, content, batch?.mode ?: APP_LINK_READ_MODE_V1)
    private val onScreenState = MutableStateFlow(false)
    private val engagedState = MutableStateFlow(false)
    private val busyState = MutableStateFlow<Set<String>>(emptySet())
    private val shownState = MutableStateFlow(true)

    internal val isOnScreen: StateFlow<Boolean> get() = onScreenState
    internal val isEngaged: StateFlow<Boolean> get() = engagedState
    internal val busyUrls: StateFlow<Set<String>> get() = busyState
    internal val isShown: StateFlow<Boolean> get() = shownState

    /** The row is drawn inside the window (not merely composed ahead of the scroll). */
    fun onScreen(value: Boolean) {
        onScreenState.value = value
    }

    /** The person is on this message (a card of it open, Raft's question or confirmation open on it): no card changes under them. */
    fun engaged(value: Boolean) {
        engagedState.value = value
    }

    /** The links whose card's buttons are at work: that work reads the card again when it ends. */
    fun busy(urls: Set<String>) {
        busyState.value = urls
    }

    /** The row left the page: nothing waits for it any more. */
    fun left() {
        shownState.value = false
        onScreenState.value = false
    }

    // The store's own, touched only on its dispatcher.
    internal val refreshes = HashMap<String, AppLinkCardRefresh>()
    /**
     * By object key: when this row last sent a re-read for it ([APP_LINK_CARD_CHANGED_MIN_INTERVAL_MS]
     * counts from then). The catch-up after the app came back is kept under [APP_LINK_ALL_OBJECTS],
     * a key of its own, so a nudge's read and a came-back read of the same card on screen can land
     * within ten seconds of each other. Harmless: it is bounded to the cards on screen, one extra
     * read per return.
     */
    internal val lastRefreshAt = HashMap<String, Long>()
    internal var quietMark = 0
    internal val reading = Mutex()
}

/** One re-read of a row's cards of one object on its way (web `linkCardChangedRereads.ts` `Job`). */
internal class AppLinkCardRefresh {
    lateinit var job: Job
    var reading = false
    var again = false
}

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

    /** How the view's latest request ended, once it has: what a read it superseded waits for ([SharedAppLinkPreviewStore.rereadLink]). */
    internal val settled = MutableStateFlow<AppLinkViewRead?>(null)

    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun create(): AppLinkCardView = AppLinkCardView(Uuid.random().toString())
    }
}

/**
 * One finished request of a view: its place in the view's count ([seq]), whether Raft [answered], the
 * links it read ([urls]; null: the whole message) and those it answered `rate_limited` ([limited]).
 */
internal class AppLinkViewRead(val seq: Int, val answered: Boolean, val urls: List<String>?, val limited: Set<String>) {
    /** Whether this read brought what is shown for [url]. */
    fun shows(url: String): Boolean = answered && url !in limited && (urls == null || url in urls)
}

/** Web `LINK_CARD_READ_TIMEOUT_MS`: a read still unanswered after this is given up by what waits for it. */
internal const val APP_LINK_CARD_READ_TIMEOUT_MS = 20_000L

/** Web `PARTIAL_READ_TRIES`: a read of some links is sent at most this many times when a later request of the view drops its answer. */
internal const val APP_LINK_PARTIAL_READ_TRIES = 3

/** How a message is read with the batched link-card read: its [view] and the UI language. */
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
    /**
     * RFC 086: a card message's acting buttons as last read for this viewer, by
     * [appLinkMessageCardKey]. Bounded, most recently read last.
     */
    val messageCards: Map<String, RaftMessageCardLive> = emptyMap(),
    /** By [appLinkMessageCardKey]: the viewer's choice for the card's App when it was read ([messageCardSignature]). */
    val messageCardSignatures: Map<String, String> = emptyMap(),
) {
    /** The viewer's choice for [live]'s App as it stands (its epoch and status): an answer read under another is stale. */
    internal fun messageCardSignature(viewerKey: String, live: RaftMessageCardLive): String {
        val appKey = live.app?.id?.let { appLinkAppKey(viewerKey, it) } ?: return ""
        return "${epoch[appKey] ?: 0}:${status[appKey]?.name.orEmpty()}"
    }
}

/** `${viewerKey}:${message}` ([appLinkViewerKey]): whose read of which card message ([SharedAppLinkPreviewState.messageCards]). */
fun appLinkMessageCardKey(viewerKey: String, messageId: String): String = "$viewerKey:$messageId"

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
    /** Stamps each read's answer ([AppLinkResult.Card.readAtMillis], [RaftMessageCardLive.readAtMillis]). */
    internal val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Monotonic: the cooldown and the interval between re-reads must not follow wall-clock changes. */
    private val monotonicMillis: () -> Long = ::raftForegroundGateNowMillis,
    /** The wait before a nudged card is read again: 1 ms to [APP_LINK_CARD_CHANGED_JITTER_MS] at random. */
    private val changedJitterMs: () -> Long = { 1L + Random.nextLong(APP_LINK_CARD_CHANGED_JITTER_MS) },
    /** `link_card.changed` nudges, each with the Server it came on. */
    changedHints: Flow<RaftLinkCardChangedHint> = RaftLinkCardChangedHints.hints,
    /** The process's one "came back" signal (#proj-mobile:#198). */
    freshness: Flow<RaftFreshnessEvent> = RaftFreshnessSignal.events,
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
     * failed read is not kept, so the row tries again when it is shown again (with [presence], no
     * sooner than the re-entry cooldown); a read again after Allow that fails drops that App's
     * pending chips, not the links. With [batch] it is the batched link-card read of that message
     * view, else the GET.
     */
    fun read(
        viewerKey: String,
        serverId: String,
        messageId: String,
        content: String,
        conversationId: String,
        batch: AppLinkBatchRead? = null,
        /**
         * The row that asks. With it, a message whose last read failed is read again no sooner than
         * [APP_LINK_CARD_REENTRY_COOLDOWN_MS] after that read went out: a row back sooner waits for
         * the rest, and leaving again ends the wait (web `useAppLinkPreviews` re-entry).
         */
        presence: AppLinkRowPresence? = null,
    ) {
        if (!appLinkPreviewsApply(content)) return
        scope.launch { readOnStore(viewerKey, serverId, messageId, content, conversationId, batch, presence) }
    }

    private suspend fun readOnStore(
        viewerKey: String,
        serverId: String,
        messageId: String,
        content: String,
        conversationId: String,
        batch: AppLinkBatchRead?,
        presence: AppLinkRowPresence?,
    ) {
        val mode = batch?.mode ?: APP_LINK_READ_MODE_V1
        val readScope = appLinkReadScope(viewerKey, messageId, content, mode)
        fun wanted(state: SharedAppLinkPreviewState): Boolean =
            state.messageView(viewerKey, messageId, content, conversationId, artifactHosts(), mode, batch?.view?.id).wantsRead
        if (!wanted(_state.value)) return
        // Only a message with nothing to show yet waits: a read again after Allow is the person's own and goes at once.
        if (presence != null && _state.value.entries[readScope] == null && !reentryCooldownPassed(readScope, presence)) return
        val before = _state.value
        if (!wanted(before)) return
        if (!inFlight.add(readScope)) return
        val askedAt = before.epoch
        val askedGeneration = before.generation
        val sentAt = monotonicMillis()
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
        if (answer == null) noteReadFailed(readScope, sentAt) else readFailedAt.remove(readScope)
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
            entries[readScope] = AppLinkLoaded(results, askedAt, limited = answer.limited, viewId = batch?.view?.id, serverId = serverId)
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
            if (seq == view.seq) view.settled.value = AppLinkViewRead(seq, read is AppLinkFetch.Answer, null, emptySet())
            return if (seq == view.seq) read else AppLinkFetch.Stale
        }
        // One line per batched read, so a card that does not change can be read from a log: which
        // message (its id's first 8 characters, never a link), the whole message or some links, and
        // what came back. `items` above `cards + limited` means this client refused an item.
        val asked = "message=${messageId.take(8)} links=${urls?.size ?: "all"}"
        if (seq != view.seq) {
            diagnosticsLogger("app_link_cards_read $asked result=superseded code=${response?.code ?: -1}")
            return AppLinkFetch.Stale
        }
        val parsed = response?.takeIf { it.isSuccessful }?.bodyText?.let { parseAppLinkCardReadAnswer(it, resolveIcon) }
        if (parsed == null) {
            diagnosticsLogger("app_link_cards_read_failed code=${response?.code ?: -1}")
            diagnosticsLogger("app_link_cards_read $asked result=failed code=${response?.code ?: -1}")
            view.settled.value = AppLinkViewRead(seq, false, urls, emptySet())
            return AppLinkFetch.Failed
        }
        parsed.tickets.forEach { (url, ticket) -> if (ticket != null) view.tickets[url] = ticket else view.tickets.remove(url) }
        val cards = parsed.results.values.count { it is AppLinkResult.Card }
        diagnosticsLogger("app_link_cards_read $asked result=answered code=${response?.code ?: -1} items=${parsed.itemCount} cards=$cards limited=${parsed.limited.size}")
        view.settled.value = AppLinkViewRead(seq, true, urls, parsed.limited.keys.toSet())
        return AppLinkFetch.Answer(parsed.results.stampedReadAt(nowMillis()), parsed.limited)
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

    /** Reads an operation's receipt (a `link_card.operation_changed` hint named it). */
    suspend fun operationStatus(serverId: String, operationId: String): AppLinkOperationStatus {
        val response = callOrNull("app_link_card_status_error") { api.readLinkCardStatus(serverId, AppLinkStatusRequestBody(operationId)) }
            ?: return AppLinkOperationStatus.Open
        return parseAppLinkStatusAnswer(response.code, response.bodyText ?: response.errorBodyText)
    }

    /**
     * Reads one link of a message again (after a button was pressed, or its card went stale): the
     * batched read of [batch]'s view for [url] alone, which sends back the view's ticket. Its answer
     * replaces that link's result whatever its revisions say and whichever row read the message
     * first; a link Raft answers with no card is the author's link again (web `reread`: "No result:
     * the link is the author's link now").
     *
     * False whenever the card shown is not what a read answered: the read failed, Raft answered the
     * link `rate_limited`, or there was no answer of this message to put it in. A Refresh says so in
     * a toast (web `LinkCardReread` `failed`), so an old card is never kept in silence. A read whose
     * place a later request of the view took reports that request's outcome instead of its own.
     */
    suspend fun rereadLink(viewerKey: String, serverId: String, messageId: String, content: String, batch: AppLinkBatchRead, url: String): Boolean {
        val readScope = appLinkReadScope(viewerKey, messageId, content, batch.mode)
        repeat(APP_LINK_PARTIAL_READ_TRIES) {
            when (val read = rereadLinks(readScope, serverId, messageId, batch, listOf(url), keepWithoutCard = false)) {
                AppLinkReread.Failed -> return false
                is AppLinkReread.Read -> return read.applied && url !in read.limited
                AppLinkReread.Superseded -> {
                    // A later request of the view took this one's place (two quick presses, a read of the whole
                    // message): that read decides what shows, so its outcome is this press's. Only when it did
                    // not bring this link (it failed, or read other links) is the read sent again (web `readSome`).
                    val view = batch.view
                    val newer = withTimeoutOrNull(APP_LINK_CARD_READ_TIMEOUT_MS) { view.settled.first { it != null && it.seq >= view.seq } }
                    if (newer?.shows(url) == true) return true
                }
            }
        }
        return false
    }

    /**
     * Reads [urls] of a message again, alone, in one request of [batch]'s view (web `readSome`). A
     * link answered with a card replaces the card shown, whichever view read the message first (a
     * row shown again reuses the answer of the row before it, as the web's cache does); a link
     * answered `rate_limited` keeps what is shown. A link answered with no card keeps its card when
     * [keepWithoutCard] (a nudge's re-read: only the person's own Refresh shows it gone), else it is
     * the author's link again.
     */
    private suspend fun rereadLinks(
        readScope: String,
        serverId: String,
        messageId: String,
        batch: AppLinkBatchRead,
        urls: List<String>,
        keepWithoutCard: Boolean,
    ): AppLinkReread {
        val askedGeneration = _state.value.generation
        // Why it is read again: the person's own press (Refresh, or a button's re-read), or a card that changed.
        val line = "app_link_card_reread message=${messageId.take(8)} why=${if (keepWithoutCard) "changed" else "press"} links=${urls.size}"
        val fetched =
            when (val read = fetch(messageId, serverId, batch, urls)) {
                is AppLinkFetch.Answer -> read
                AppLinkFetch.Failed -> {
                    diagnosticsLogger("$line result=failed")
                    return AppLinkReread.Failed
                }
                AppLinkFetch.Stale -> {
                    diagnosticsLogger("$line result=dropped reason=superseded")
                    return AppLinkReread.Superseded
                }
            }
        val limited = urls.mapNotNull { url -> fetched.limited[url]?.let { url to it } }.toMap()
        val answered = urls.filter { it !in limited }
        // The person's own press takes whatever Raft answered for the link (web `reread`); a nudge's only a card.
        val fresh = answered.mapNotNull { url -> fetched.results[url]?.takeIf { !keepWithoutCard || it is AppLinkResult.Card }?.let { url to it } }
        // Answered with nothing at all (no card, no prompt): no longer previewed.
        val gone = if (keepWithoutCard) emptyList() else answered.filter { it !in fetched.results }
        // What became of the answer: applied, or dropped and why (never in silence).
        var outcome = if (limited.size == urls.size) "kept reason=rate_limited" else "kept reason=no_card"
        if (fresh.isNotEmpty() || gone.isNotEmpty()) {
            _state.update { state ->
                if (state.generation != askedGeneration) {
                    outcome = "dropped reason=signed_out"
                    return@update state
                }
                val entry = state.entries[readScope]
                if (entry == null) {
                    outcome = "dropped reason=no_answer_held"
                    return@update state
                }
                outcome = "applied"
                state.copy(entries = state.entries + (readScope to entry.copy(results = entry.results - gone.toSet() + fresh)))
            }
        }
        diagnosticsLogger("$line result=$outcome cards=${fresh.size} gone=${gone.size} limited=${limited.size}")
        val applied = !outcome.startsWith("dropped")
        // Answered but not on screen: what waits for this read must not take it for shown.
        if (!applied) batch.view.settled.value?.let { last -> batch.view.settled.value = AppLinkViewRead(last.seq, false, last.urls, last.limited) }
        return AppLinkReread.Read(limited, applied = applied)
    }

    /**
     * The GET form of [rereadLinks] for a nudge or the catch-up (a session that found no batched read,
     * or a row without one): the message is read with the GET, whose `objectKeys` gave its cards their
     * digests, and the cards it answers for [urls] replace those shown. Anything else stays as it is.
     */
    private suspend fun rereadWithGet(readScope: String, serverId: String, messageId: String, urls: List<String>): AppLinkReread {
        val askedGeneration = _state.value.generation
        val line = "app_link_card_reread message=${messageId.take(8)} why=changed get=true links=${urls.size}"
        if (!inFlight.add(readScope)) return AppLinkReread.Superseded
        val fetched =
            try {
                fetchWithGet(messageId, serverId)
            } finally {
                inFlight.remove(readScope)
            }
        if (fetched !is AppLinkFetch.Answer) {
            diagnosticsLogger("$line result=failed")
            return AppLinkReread.Failed
        }
        val fresh = urls.mapNotNull { url -> (fetched.results[url] as? AppLinkResult.Card)?.let { url to it } }
        var applied = fresh.isEmpty()
        if (fresh.isNotEmpty()) {
            _state.update { state ->
                val entry = state.entries[readScope]?.takeIf { state.generation == askedGeneration } ?: return@update state
                applied = true
                state.copy(entries = state.entries + (readScope to entry.copy(results = entry.results + fresh)))
            }
        }
        diagnosticsLogger("$line result=${if (applied) "applied" else "dropped"} cards=${fresh.size}")
        return AppLinkReread.Read(emptyMap(), applied = applied)
    }

    /** The rows on a page now ([keepFresh]). Store dispatcher only, like everything below. */
    private val presences = LinkedHashSet<AppLinkRowPresence>()

    /** By read scope: when the last read of a message that failed went out ([monotonicMillis]). Bounded. */
    private val readFailedAt = LinkedHashMap<String, Long>()
    private var changedMarks = 0
    private val freshnessGate = RaftFreshnessGate(nowMillis = monotonicMillis)

    private fun noteReadFailed(readScope: String, sentAt: Long) {
        readFailedAt.remove(readScope)
        readFailedAt[readScope] = sentAt
        while (readFailedAt.size > APP_LINK_RESULT_CACHE_MAX) readFailedAt.remove(readFailedAt.keys.first())
    }

    /**
     * Whether a read of [readScope] may go now. A message whose last read failed is read again no
     * sooner than [APP_LINK_CARD_REENTRY_COOLDOWN_MS] after that read went out (web
     * `LINK_CARD_REENTRY_COOLDOWN_MS`): the row waits for the rest while it stays, and is not read
     * when it leaves meanwhile.
     */
    private suspend fun reentryCooldownPassed(readScope: String, presence: AppLinkRowPresence): Boolean {
        val failedAt = readFailedAt[readScope] ?: return true
        val rest = failedAt + APP_LINK_CARD_REENTRY_COOLDOWN_MS - monotonicMillis()
        if (rest <= 0L) return true
        return withTimeoutOrNull(rest) { presence.isShown.first { !it } } == null
    }

    /**
     * Keeps a row's cards current while it is on its page; the caller cancels the job (after
     * [AppLinkRowPresence.left]) when the row leaves. Nothing is read here for a card whose App named
     * no object ([AppLinkResult.Card.objectKey]), and nothing by the clock:
     *  - a `link_card.changed` nudge for an object one of its cards shows reads those cards again, as
     *    the web does ([refreshCards]);
     *  - an answer marked [AppLinkLoaded.changed] (a nudge that came while no row showed it, or the
     *    app came back since it was read) has its keyed cards read again once the row is on screen.
     */
    fun keepFresh(presence: AppLinkRowPresence): Job =
        scope.launch {
            presences += presence
            try {
                var first = true
                state.map { it.entries[presence.readScope]?.changed ?: 0 }.distinctUntilChanged().collect { mark ->
                    // Marked before the row was shown: read at once when it is on screen. Marked under it: after the jitter.
                    if (mark != 0 && mark != presence.quietMark) startRefresh(presence, APP_LINK_ALL_OBJECTS, jitter = !first)
                    first = false
                }
            } finally {
                presences -= presence
                val pending = presence.refreshes.values.toList()
                presence.refreshes.clear()
                pending.forEach { it.job.cancel() }
            }
        }

    /**
     * A `link_card.changed` nudge on [serverId] (web `onLinkCardChanged`): each row whose answer shows
     * a card of [objectKey] reads it again; an answer no row shows is marked, so the row shown with it
     * later reads it then (web: the cached answer's `changed`).
     */
    private fun onLinkCardChanged(serverId: String, objectKey: String) {
        val shown = HashSet<String>()
        for (presence in presences.toList()) {
            if (presence.serverId != serverId) continue
            if (_state.value.entries[presence.readScope]?.keyedUrls(objectKey).isNullOrEmpty()) continue
            shown += presence.readScope
            startRefresh(presence, objectKey, jitter = true)
        }
        markChanged { readScope, entry -> readScope !in shown && entry.serverId == serverId && entry.keyedUrls(objectKey).isNotEmpty() }
        diagnosticsLogger("app_link_card_changed rows=${shown.size}")
    }

    /**
     * The app came back (the foreground, or [serverId]'s socket): nudges sent meanwhile were missed, so
     * every answer with a card whose App named its object is marked (all Servers' for the foreground,
     * [serverId]'s for its socket). Rows on screen read those cards once; the others when they are next
     * on screen. The web has no such rule: a tab stays connected, a phone app does not.
     */
    private fun onCameBack(serverId: String?) {
        markChanged { _, entry -> (serverId == null || entry.serverId == serverId) && entry.keyedUrls(APP_LINK_ALL_OBJECTS).isNotEmpty() }
        diagnosticsLogger("app_link_card_came_back server=${serverId != null}")
    }

    /** Marks the answers [match] picks ([AppLinkLoaded.changed]) and returns the mark. */
    private fun markChanged(match: (String, AppLinkLoaded) -> Boolean): Int {
        changedMarks += 1
        val mark = changedMarks
        _state.update { state ->
            if (state.entries.none { (readScope, entry) -> match(readScope, entry) }) return@update state
            state.copy(entries = state.entries.mapValues { (readScope, entry) -> if (match(readScope, entry)) entry.copy(changed = mark) else entry })
        }
        return mark
    }

    /** The answer's cards were read again since [mark]: it is current, unless it was marked again meanwhile. */
    private fun clearChanged(readScope: String, mark: Int) {
        if (mark == 0) return
        _state.update { state ->
            val entry = state.entries[readScope]?.takeIf { it.changed == mark } ?: return@update state
            state.copy(entries = state.entries + (readScope to entry.copy(changed = 0)))
        }
    }

    /** One re-read per row and object at a time: a nudge while it waits is that read, one while it reads starts another after it. */
    private fun startRefresh(presence: AppLinkRowPresence, objectKey: String, jitter: Boolean) {
        val running = presence.refreshes[objectKey]
        if (running != null) {
            if (running.reading) running.again = true
            return
        }
        if (presence !in presences) return
        val refresh = AppLinkCardRefresh()
        presence.refreshes[objectKey] = refresh
        refresh.job =
            scope.launch(start = CoroutineStart.LAZY) {
                var done = false
                try {
                    done = refreshCards(presence, objectKey, jitter, refresh)
                } finally {
                    if (presence.refreshes[objectKey] === refresh) presence.refreshes.remove(objectKey)
                    // Not read (the row left first, or the read failed): the answer is marked, so the next row
                    // shown with it reads. This row does not start over for its own mark.
                    if (!done && objectKey != APP_LINK_ALL_OBJECTS) {
                        presence.quietMark = markChanged { readScope, _ -> readScope == presence.readScope }
                    }
                }
                if (refresh.again && presence in presences) startRefresh(presence, objectKey, jitter = true)
            }
        refresh.job.start()
    }

    /**
     * Reads a row's cards of [objectKey] again (web `useLinkCardChangedRereads` `start`), in order:
     *  1. waits until the row is on screen;
     *  2. waits the jitter (when [jitter]), at least [APP_LINK_CARD_CHANGED_MIN_INTERVAL_MS] after this
     *     row's last re-read of that object, and the rest of the cooldown of a read that failed;
     *  3. waits while the person is on the message, then for the screen again;
     *  4. reads those links in one request, as Refresh reads one. A card whose buttons are at work is
     *     left to that work. A link answered `rate_limited` goes again after Raft's `retry_after`,
     *     doubling, as one of the view's rate-limited re-reads ([APP_LINK_CARD_READ_RETRIES]).
     * Silent: the card shown stays until the new one comes, and stays when none does. True when
     * nothing is left to read.
     */
    private suspend fun refreshCards(presence: AppLinkRowPresence, objectKey: String, jitter: Boolean, refresh: AppLinkCardRefresh): Boolean {
        val batch = presence.batch
        val readScope = presence.readScope
        presence.isOnScreen.first { it }
        val startedAt = monotonicMillis()
        val sinceLast = presence.lastRefreshAt[objectKey]?.let { startedAt - it }
        val cooldown = readFailedAt[readScope]?.let { it + APP_LINK_CARD_REENTRY_COOLDOWN_MS - startedAt } ?: 0L
        var delayMs = maxOf(if (jitter) changedJitterMs() else 0L, sinceLast?.let { APP_LINK_CARD_CHANGED_MIN_INTERVAL_MS - it } ?: 0L, cooldown)
        var urls = _state.value.entries[readScope]?.keyedUrls(objectKey).orEmpty()
        var lastBackoffMs = 0L
        while (urls.isNotEmpty()) {
            if (delayMs > 0L) delay(delayMs)
            presence.isEngaged.first { !it }
            presence.isOnScreen.first { it }
            // A read of the whole message on its way brings these cards.
            if (readScope in inFlight) return true
            refresh.reading = true
            val sentAt = monotonicMillis()
            presence.lastRefreshAt[objectKey] = sentAt
            val mark = _state.value.entries[readScope]?.changed ?: 0
            val busy = presence.busyUrls.value
            val reading = _state.value.entries[readScope]?.keyedUrls(objectKey).orEmpty().filter { it in urls && it !in busy }
            if (reading.isEmpty()) break
            val read =
                presence.reading.withLock {
                    // A server without the batched read has no read of one link: the GET reads the message, and only these cards are taken from it.
                    if (batch == null || batchedReadMissing) rereadWithGet(readScope, presence.serverId, presence.messageId, reading)
                    else rereadLinks(readScope, presence.serverId, presence.messageId, batch, reading, keepWithoutCard = true)
                }
            when (read) {
                AppLinkReread.Failed -> {
                    noteReadFailed(readScope, sentAt)
                    return false
                }
                AppLinkReread.Superseded -> return false
                is AppLinkReread.Read -> {
                    if (!read.applied) return false
                    urls = reading.filter { it in read.limited }
                    if (urls.isEmpty()) {
                        readFailedAt.remove(readScope)
                        if (objectKey == APP_LINK_ALL_OBJECTS) clearChanged(readScope, mark)
                        return true
                    }
                    val view = batch?.view ?: return false
                    if (view.rereads >= APP_LINK_CARD_READ_RETRIES) return false
                    view.rereads += 1
                    val backoffMs = appLinkRereadBackoffMs(lastBackoffMs, urls.maxOf { read.limited.getValue(it) })
                    lastBackoffMs = backoffMs
                    delayMs = minOf(60_000L, backoffMs + retryJitterMs())
                }
            }
        }
        if (objectKey == APP_LINK_ALL_OBJECTS) clearChanged(readScope, _state.value.entries[readScope]?.changed ?: 0)
        return true
    }

    /** The card [url] of this message's read in [batch]'s mode as held now (after [rereadLink]), or null. */
    fun heldLinkCard(viewerKey: String, messageId: String, content: String, batch: AppLinkBatchRead, url: String): AppLinkResult.Card? =
        _state.value.entries[appLinkReadScope(viewerKey, messageId, content, batch.mode)]?.results?.get(url) as? AppLinkResult.Card

    /** The card message [messageId]'s acting read for this viewer as held now (after [readMessageCard]), or null. */
    fun heldMessageCard(viewerKey: String, messageId: String): RaftMessageCardLive? =
        _state.value.messageCards[appLinkMessageCardKey(viewerKey, messageId)]

    /**
     * Reads a card message's acting buttons for this viewer (RFC 086 §4.2): the batched read of
     * [view], declaring `card.execute.v1` with the link card capabilities, presenting the view's
     * tickets (one per fallback page). Its answer replaces what [appLinkMessageCardKey] held, and
     * the tickets it gives are the view's for its next read. False when the read failed (what is
     * shown stays); a read superseded by a later one of the view is not a failure.
     */
    suspend fun readMessageCard(
        viewerKey: String,
        serverId: String,
        messageId: String,
        view: AppLinkCardView,
        locale: String,
        /** The card's revision the row draws: an answer for it, read since the App's choice last moved, is current. */
        cardRevision: String? = null,
        /** Read even when the answer is current (after a press, which changes what the App answers). */
        force: Boolean = false,
    ): Boolean {
        val key = appLinkMessageCardKey(viewerKey, messageId)
        if (!force) {
            val state = _state.value
            val held = state.messageCards[key]
            if (held != null && held.cardRevision == cardRevision && state.messageCardSignatures[key] == state.messageCardSignature(viewerKey, held)) return true
            // A row composed again while its read is on its way waits for that read (Raft's read budget is per person).
            if (key in messageCardInFlight) return true
        }
        messageCardInFlight += key
        try {
            return readMessageCardNow(viewerKey, serverId, messageId, view, locale, key)
        } finally {
            messageCardInFlight -= key
        }
    }

    private val messageCardInFlight = mutableSetOf<String>()

    private suspend fun readMessageCardNow(viewerKey: String, serverId: String, messageId: String, view: AppLinkCardView, locale: String, key: String): Boolean {
        val askedGeneration = _state.value.generation
        view.seq += 1
        val seq = view.seq
        val response = callOrNull("message_card_read_error") { api.readLinkCards(messageId, serverId, messageCardReadRequestBody(view, locale)) }
        if (seq != view.seq) return true
        val live = response?.takeIf { it.isSuccessful }?.bodyText?.let { parseMessageCardReadAnswer(it, resolveIcon) }
        if (live == null) {
            diagnosticsLogger("message_card_read_failed code=${response?.code ?: -1}")
            return false
        }
        live.handles.forEach { view.tickets[it.url] = it.ticket }
        val stamped = live.copy(readAtMillis = nowMillis())
        _state.update { state ->
            if (state.generation != askedGeneration) return@update state
            val cards = LinkedHashMap(state.messageCards)
            cards.remove(key)
            cards[key] = stamped
            while (cards.size > APP_LINK_RESULT_CACHE_MAX) cards.remove(cards.keys.first())
            val signatures = state.messageCardSignatures.filterKeys { it in cards } + (key to state.messageCardSignature(viewerKey, stamped))
            state.copy(messageCards = cards, messageCardSignatures = signatures)
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

    // Last, so everything above is set before a collector can run.
    init {
        scope.launch { changedHints.collect { hint -> onLinkCardChanged(hint.serverId, hint.objectKey) } }
        scope.launch {
            freshness.collect { event ->
                // The network alone brings no nudge back: the socket's own return follows it.
                if (event.trigger == RaftFreshnessTrigger.NetworkRestored) return@collect
                // One catch-up per return, and one for every new socket connection of a Server ([RaftFreshnessGate]).
                if (freshnessGate.onEvent(event, event.serverId, visible = true) == null) return@collect
                onCameBack(event.serverId.takeIf { event.trigger == RaftFreshnessTrigger.Reconnect })
            }
        }
    }
}

/** How a read of some links of a message ended ([SharedAppLinkPreviewStore] `rereadLinks`). */
private sealed interface AppLinkReread {
    /** Read: [limited] are the links Raft answered `rate_limited` (their `retry_after` seconds), whose cards stay as they were. */
    data class Read(val limited: Map<String, Int>, val applied: Boolean = true) : AppLinkReread

    data object Failed : AppLinkReread

    /** The view sent a later request meanwhile: this answer was dropped. Not a failure. */
    data object Superseded : AppLinkReread
}

/** What one read came to ([SharedAppLinkPreviewStore] `fetch`). */
/** Each card answer of one read carries when it was read (its handles' age). */
private fun Map<String, AppLinkResult>.stampedReadAt(atMillis: Long): Map<String, AppLinkResult> =
    mapValues { (_, result) -> if (result is AppLinkResult.Card) result.copy(readAtMillis = atMillis) else result }

private sealed interface AppLinkFetch {
    data class Answer(val results: Map<String, AppLinkResult>, val limited: Map<String, Int>) : AppLinkFetch

    data object Failed : AppLinkFetch

    /** The view sent a later request meanwhile: this answer is dropped. */
    data object Stale : AppLinkFetch
}

private const val APP_LINK_RESULT_CACHE_MAX = 300
