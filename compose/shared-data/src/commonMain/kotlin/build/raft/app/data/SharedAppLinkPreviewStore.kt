package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.links.raftAppLinkReadUrls
import build.raft.app.reportProjectionRowDropped
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
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

/** Disk-cached sanitized link-card response for stale-while-revalidate (#proj-mobile:#294). */
@Serializable
@RaftCoreInternalApi data class PersistedAppLinkPreviewRecord(
    val kind: String,
    val rawBodyJson: String,
)

@RaftCoreInternalApi const val PERSISTED_APP_LINK_KIND_BATCH = "v2"
@RaftCoreInternalApi const val PERSISTED_APP_LINK_KIND_GET = "v1"

@RaftCoreInternalApi val AppLinkPreviewResourceSpec: RaftOfflineResourceSpec<String, PersistedAppLinkPreviewRecord> =
    RaftOfflineResourceSpec(
        name = "app_link_preview",
        version = 1,
        serializer = PersistedAppLinkPreviewRecord.serializer(),
        paramsId = { scopeKey -> scopeKey },
        maxEntriesPerAccount = 300,
        maxBytesPerEntry = 128 * 1024,
        maxAgeMillis = 7L * 24 * 60 * 60 * 1000L,
    )

/**
 * Strips prompts, signed action handles, view tickets, and `rate_limited` entries before writing a
 * link-card response to disk so only display-only cards are cached (#proj-mobile:#294).
 */
@RaftCoreInternalApi fun sanitizeAppLinkPreviewBodyForDisk(kind: String, bodyText: String): String? {
    val root = runCatching { Json.parseToJsonElement(bodyText) }.getOrNull() as? JsonObject ?: return null
    return when (kind) {
        PERSISTED_APP_LINK_KIND_BATCH -> {
            val items = root["items"] as? JsonArray ?: return null
            val sanitized =
                items.mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val itemKind = (obj["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                    when (itemKind) {
                        "v1" -> obj
                        "v2" ->
                            JsonObject(
                                obj.toMutableMap().apply {
                                    this["ticket"] = JsonNull
                                    this["actions"] = JsonArray(emptyList())
                                }
                            )
                        "v3" -> {
                            val previewItems = (obj["preview"] as? JsonObject)?.get("items") as? JsonArray
                            val isConnectAccount =
                                previewItems?.any { entry ->
                                    ((entry as? JsonObject)?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content == "connect_account"
                                } == true
                            if (isConnectAccount) {
                                null
                            } else {
                                JsonObject(
                                    obj.toMutableMap().apply {
                                        this["ticket"] = JsonNull
                                        this["actions"] = JsonArray(emptyList())
                                    }
                                )
                            }
                        }
                        else -> null
                    }
                }
            if (sanitized.isEmpty()) return null
            Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("items" to JsonArray(sanitized))))
        }
        PERSISTED_APP_LINK_KIND_GET -> {
            val previews = root["previews"] as? JsonArray ?: return null
            if (previews.isEmpty()) return null
            val filtered = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
            filtered["previews"] = previews
            root["appIcons"]?.let { filtered["appIcons"] = it }
            root["objectKeys"]?.let { filtered["objectKeys"] = it }
            Json.encodeToString(JsonObject.serializer(), JsonObject(filtered))
        }
        else -> null
    }
}

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
    /**
     * True when hydrated from the offline disk cache before the live network read lands: cards
     * render immediately (read-only, no handles/tickets/prompts) while `messageView` still requests
     * a network revalidation (#proj-mobile:#294).
     */
    val staleFromDisk: Boolean = false,
) {
    /** The links whose card carries [objectKey] ([APP_LINK_ALL_OBJECTS]: any digest), in link order. */
    @RaftCoreInternalApi fun keyedUrls(objectKey: String): List<String> =
        results.values.mapNotNull { result ->
            val key = (result as? AppLinkResult.Card)?.objectKey ?: return@mapNotNull null
            result.url.takeIf { objectKey == APP_LINK_ALL_OBJECTS || key == objectKey }
        }
}

/** Every card whose App named its object, whichever it is (the catch-up after the app came back). */
@RaftCoreInternalApi const val APP_LINK_ALL_OBJECTS = "*"

/** Web `LINK_CARD_CHANGED_JITTER_MS`: a nudged card is read again after a random 1 ms to this, so its readers don't all ask the App at once. */
@RaftCoreInternalApi const val APP_LINK_CARD_CHANGED_JITTER_MS = 2_000L

/** Web `LINK_CARD_CHANGED_MIN_INTERVAL_MS`: an object is read again at most once per this per message view. */
@RaftCoreInternalApi const val APP_LINK_CARD_CHANGED_MIN_INTERVAL_MS = 10_000L

/**
 * A Server's socket that comes back within this of the app's last coming back is the same return
 * (the foreground, then its socket): the cards already read for it are not read again. The app's own
 * number; the web has no catch-up.
 */
@RaftCoreInternalApi const val APP_LINK_CARD_RETURN_WINDOW_MS = 30_000L

/** Web `LINK_CARD_REENTRY_COOLDOWN_MS`: a message whose read failed is read again no sooner than this after that read went out. */
@RaftCoreInternalApi const val APP_LINK_CARD_REENTRY_COOLDOWN_MS = 30_000L

/**
 * Maximum micro-batch window for committing concurrent initial link-card reads into [SharedAppLinkPreviewStore.state]
 * (Hands `5abe9394`): when opening a conversation fires multiple `link-cards/read` calls together,
 * completed answers stage briefly while sibling reads remain in flight and flush together once all
 * concurrent initial reads finish or this cap elapses. Single-message reads flush immediately (0 ms).
 */
@RaftCoreInternalApi const val APP_LINK_READ_COMMIT_BATCH_WINDOW_MS = 120L

/**
 * One message row on a page (web: a mounted message view and its `target` element): what the store
 * needs to read its cards again at the right moment. The row says when it is on screen, when the
 * person is on it and which cards are busy; [SharedAppLinkPreviewStore.keepFresh] does the rest.
 * The setters may be called from the UI thread; everything else is the store's.
 */
class AppLinkRowPresence @RaftCoreInternalApi constructor(
    @RaftCoreInternalApi val viewerKey: String,
    @RaftCoreInternalApi val serverId: String,
    @RaftCoreInternalApi val messageId: String,
    @RaftCoreInternalApi val content: String,
    @RaftCoreInternalApi val batch: AppLinkBatchRead?,
) {
    @RaftCoreInternalApi val readScope: String = appLinkReadScope(viewerKey, messageId, content, batch?.mode ?: APP_LINK_READ_MODE_V1)
    private val onScreenState = MutableStateFlow(false)
    private val engagedState = MutableStateFlow(false)
    private val busyState = MutableStateFlow<Set<String>>(emptySet())
    private val shownState = MutableStateFlow(true)

    @RaftCoreInternalApi val isOnScreen: StateFlow<Boolean> get() = onScreenState
    @RaftCoreInternalApi val isEngaged: StateFlow<Boolean> get() = engagedState
    @RaftCoreInternalApi val busyUrls: StateFlow<Set<String>> get() = busyState
    @RaftCoreInternalApi val isShown: StateFlow<Boolean> get() = shownState

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
    @RaftCoreInternalApi val refreshes = HashMap<String, AppLinkCardRefresh>()
    @RaftCoreInternalApi var quietMark = 0
    @RaftCoreInternalApi val reading = Mutex()
}

/** One re-read of a row's cards of one object on its way (web `linkCardChangedRereads.ts` `Job`). */
@RaftCoreInternalApi class AppLinkCardRefresh {
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
class AppLinkCardView @RaftCoreInternalApi constructor(val id: String) {
    @RaftCoreInternalApi var seq: Int = 0
    @RaftCoreInternalApi val tickets: MutableMap<String, String> = LinkedHashMap()
    @RaftCoreInternalApi var rereads: Int = 0

    /** How the view's latest request ended, once it has: what a read it superseded waits for ([SharedAppLinkPreviewStore.rereadLink]). */
    @RaftCoreInternalApi val settled = MutableStateFlow<AppLinkViewRead?>(null)

    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun create(): AppLinkCardView = AppLinkCardView(Uuid.random().toString())
    }
}

/**
 * One finished request of a view: its place in the view's count ([seq]), whether Raft [answered], the
 * links it read ([urls]; null: the whole message) and those it answered `rate_limited` ([limited]).
 */
@RaftCoreInternalApi class AppLinkViewRead(val seq: Int, val answered: Boolean, val urls: List<String>?, val limited: Set<String>) {
    /** Whether this read brought what is shown for [url]. */
    fun shows(url: String): Boolean = answered && url !in limited && (urls == null || url in urls)
}

/** Web `LINK_CARD_READ_TIMEOUT_MS`: a read still unanswered after this is given up by what waits for it. */
@RaftCoreInternalApi const val APP_LINK_CARD_READ_TIMEOUT_MS = 20_000L

/** Web `PARTIAL_READ_TRIES`: a read of some links is sent at most this many times when a later request of the view drops its answer. */
@RaftCoreInternalApi const val APP_LINK_PARTIAL_READ_TRIES = 3

/** How a message is read with the batched link-card read: its [view] and the UI language. */
class AppLinkBatchRead(val view: AppLinkCardView, val locale: String) {
    /** The cache key part: what was declared and in which language (a POST is not HTTP-cacheable, so the store is the cache). */
    val mode: String get() = appLinkBatchReadMode(locale)
}

@RaftCoreInternalApi fun appLinkBatchReadMode(locale: String): String = "v2:${APP_LINK_CARD_READ_CAPABILITIES.joinToString(",")}:$locale"

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
    @RaftCoreInternalApi fun messageCardSignature(viewerKey: String, live: RaftMessageCardLive): String {
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

@RaftCoreInternalApi fun appLinkAppKey(viewerKey: String, appId: String): String = "$viewerKey:$appId"

@RaftCoreInternalApi fun appLinkConversationKey(viewerKey: String, conversationId: String): String = "$viewerKey:$conversationId"

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
    if (prompts.isEmpty()) return AppLinkMessageView(loaded.results, wantsRead = readWhole || loaded.staleFromDisk, readSignature = "", readsLimited = ownsLimited)
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
    return AppLinkMessageView(results = results, wantsRead = stale || readWhole || loaded.staleFromDisk, readSignature = signature, readsLimited = ownsLimited)
}

private fun String.appLinkOrigin(): String = "https://" + substringAfter("://").substringBefore('/').substringBefore('?')

/**
 * App link previews for every message on screen (web `useAppLinkPreviews` +
 * `linkPreviewConsentStore`): one read per message once its row is composed, kept in memory
 * for the session per viewer, Server, message and content; one answer to an App's prompt
 * updates every message. Sanitized display-only cards (without prompts, `connect_account`,
 * view tickets, signed action handles, or `rate_limited` items) are persisted per
 * (environment, account, Server) via [RaftOfflineResource] for cold-start stale-while-revalidate
 * (#proj-mobile:#294).
 */
class SharedAppLinkPreviewStore @RaftCoreInternalApi constructor(
    private val api: RaftAppLinkPreviewApi,
    private val scope: CoroutineScope,
    private val resolveIcon: (String) -> String? = { it },
    private val diagnosticsLogger: (String) -> Unit = {},
    initialState: SharedAppLinkPreviewState = SharedAppLinkPreviewState(),
    /** Raft's own app hosts (client config): Artifact links on them are left out as the server does. */
    @RaftCoreInternalApi val artifactHosts: () -> Collection<String> = { emptyList() },
    /** Up to a second added at random to each rate-limited re-read's wait (tests: 0). */
    private val retryJitterMs: () -> Long = { Random.nextLong(APP_LINK_CARD_RETRY_JITTER_MS) },
    /** Stamps each read's answer ([AppLinkResult.Card.readAtMillis], [RaftMessageCardLive.readAtMillis]). */
    @RaftCoreInternalApi val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Monotonic: the cooldown and the interval between re-reads must not follow wall-clock changes. */
    private val monotonicMillis: () -> Long = ::raftForegroundGateNowMillis,
    /** The wait before a nudged card is read again: 1 ms to [APP_LINK_CARD_CHANGED_JITTER_MS] at random. */
    private val changedJitterMs: () -> Long = { 1L + Random.nextLong(APP_LINK_CARD_CHANGED_JITTER_MS) },
    /** `link_card.changed` nudges, each with the Server it came on. */
    changedHints: Flow<RaftLinkCardChangedHint> = RaftLinkCardChangedHints.hints,
    /** The process's one "came back" signal (#proj-mobile:#198). */
    freshness: Flow<RaftFreshnessEvent> = RaftFreshnessSignal.events,
    /** Disk cache for stale-while-revalidate link cards (#proj-mobile:#294). */
    private val offlineResource: RaftOfflineResource<String, PersistedAppLinkPreviewRecord>? = null,
    private val currentAccountId: () -> String? = { null },
    /** Micro-batch cap for concurrent initial `read` state commits (0 disables batching). */
    private val readCommitBatchWindowMs: Long = APP_LINK_READ_COMMIT_BATCH_WINDOW_MS,
) {
    private val _state = raftStoreState(initialState, "SharedAppLinkPreviewStore")
    val state: StateFlow<SharedAppLinkPreviewState> = _state.asStateFlow()

    private val inFlight = mutableSetOf<String>()
    private val stateGuardLock = SynchronizedObject()
    private val diskHydrationAttempted = mutableSetOf<String>()
    private val serverGenerations = HashMap<String, Long>()
    private var activeInitialReads = 0
    private val pendingReadCommits = LinkedHashMap<String, PendingReadCommit>()
    private var pendingReadCommitFlushJob: Job? = null

    /** A server answered the batched read 404 and then the GET: this session reads with the GET (web `batchedReadMissing`). */
    private var batchedReadMissing = false

    /**
     * One message's [AppLinkMessageView] (its chips, cards and whether it should read), emitted
     * only when that message's view changes: the selector a row collects instead of [state].
     */
    fun messageViewFlow(
        viewerKey: String,
        messageId: String,
        content: String,
        conversationId: String,
        mode: String = APP_LINK_READ_MODE_V1,
        viewId: String? = null,
    ): Flow<AppLinkMessageView> =
        state
            .map { it.messageView(viewerKey, messageId, content, conversationId, artifactHosts(), mode = mode, viewId = viewId) }
            .distinctUntilChanged()

    /**
     * Drops every answer and every consent status (sign-out, account or environment switch).
     * Entries are keyed by viewer and Server, so a Server switch needs no clear. A read on its
     * way when this runs is not kept.
     */
    fun clearAll() {
        val jobToCancel =
            synchronized(stateGuardLock) {
                diskHydrationAttempted.clear()
                pendingReadCommits.clear()
                val existing = pendingReadCommitFlushJob
                pendingReadCommitFlushJob = null
                _state.update { SharedAppLinkPreviewState(generation = it.generation + 1) }
                existing
            }
        jobToCancel?.cancel()
    }

    /** Drops one server's cached answers and fences any in-flight reads for it (server access lost). */
    fun dropServer(serverId: String) {
        val cleanServer = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        val jobToCancel =
            synchronized(stateGuardLock) {
                serverGenerations[cleanServer] = (serverGenerations[cleanServer] ?: 0L) + 1L
                diskHydrationAttempted.removeAll { keyBelongsToServer(it, cleanServer) }
                pendingReadCommits.entries.removeAll { it.value.serverId == cleanServer }
                val cancelJob =
                    if (pendingReadCommits.isEmpty()) {
                        pendingReadCommitFlushJob.also { pendingReadCommitFlushJob = null }
                    } else {
                        null
                    }
                _state.update { state ->
                    val nextEntries = state.entries.filterNot { (key, loaded) ->
                        loaded.serverId == cleanServer || keyBelongsToServer(key, cleanServer)
                    }
                    val nextStatus = state.status.filterKeys { !keyBelongsToServer(it, cleanServer) }
                    val nextEpoch = state.epoch.filterKeys { !keyBelongsToServer(it, cleanServer) }
                    val nextClaims = state.claims.filterKeys { !keyBelongsToServer(it, cleanServer) }
                    val nextMessageCards = state.messageCards.filterKeys { !keyBelongsToServer(it, cleanServer) }
                    val nextSignatures = state.messageCardSignatures.filterKeys { !keyBelongsToServer(it, cleanServer) }
                    if (
                        nextEntries.size == state.entries.size &&
                        nextStatus.size == state.status.size &&
                        nextEpoch.size == state.epoch.size &&
                        nextClaims.size == state.claims.size &&
                        nextMessageCards.size == state.messageCards.size &&
                        nextSignatures.size == state.messageCardSignatures.size
                    ) {
                        state
                    } else {
                        state.copy(
                            entries = nextEntries,
                            status = nextStatus,
                            epoch = nextEpoch,
                            claims = nextClaims,
                            messageCards = nextMessageCards,
                            messageCardSignatures = nextSignatures,
                        )
                    }
                }
                cancelJob
            }
        jobToCancel?.cancel()
    }

    private fun keyBelongsToServer(key: String, serverId: String): Boolean {
        val firstColon = key.indexOf(':')
        if (firstColon < 0) return false
        val rest = key.substring(firstColon + 1)
        return rest == serverId || rest.startsWith("$serverId:")
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

    private fun resolveDiskAccountId(viewerKey: String): String? =
        currentAccountId()?.trim()?.takeIf { it.isNotEmpty() }
            ?: viewerKey.substringBefore(':').trim().takeIf { ':' in viewerKey && it.isNotEmpty() }

    private fun diskScopeParam(messageId: String, content: String, mode: String): String =
        "$messageId:${content.length}:${content.hashCode()}:$mode"

    private suspend fun hydrateFromDisk(
        accountId: String,
        serverId: String,
        messageId: String,
        content: String,
        mode: String,
        readScope: String,
        generation: Int,
        serverGeneration: Long,
    ) {
        val resource = offlineResource ?: return
        val diskKey = resource.key(accountId, serverId, diskScopeParam(messageId, content, mode)) ?: return
        val record = runCatchingPreservingCancellation { resource.read(diskKey) }.getOrNull() ?: return
        val parsed =
            when (record.kind) {
                PERSISTED_APP_LINK_KIND_BATCH -> parseAppLinkCardReadAnswer(record.rawBodyJson, resolveIcon)?.results
                PERSISTED_APP_LINK_KIND_GET -> parseAppLinkPreviewAnswer(record.rawBodyJson, resolveIcon)
                else -> null
            } ?: return
        // Disk cache holds display-only cards: never restore prompts, connect_account, pending chips, tickets, or signed handles.
        val cardsOnly = LinkedHashMap<String, AppLinkResult>()
        parsed.forEach { (url, result) ->
            if (result is AppLinkResult.Card && result.connectUrl == null) {
                cardsOnly[url] = result.copy(handles = emptyMap(), readAtMillis = null)
            }
        }
        if (cardsOnly.isEmpty()) return
        synchronized(stateGuardLock) {
            if ((serverGenerations[serverId] ?: 0L) != serverGeneration) return
            _state.update { state ->
                if (state.generation != generation || readScope in state.entries) return@update state
                val entries = LinkedHashMap(state.entries)
                entries[readScope] =
                    AppLinkLoaded(
                        results = cardsOnly,
                        epochs = state.epoch,
                        serverId = serverId,
                        staleFromDisk = true,
                    )
                while (entries.size > APP_LINK_RESULT_CACHE_MAX) entries.remove(entries.keys.first())
                state.copy(entries = entries)
            }
        }
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
        val hydrateAccountId = if (offlineResource != null) resolveDiskAccountId(viewerKey) else null
        val hydrateTokens =
            if (offlineResource != null && hydrateAccountId != null) {
                synchronized(stateGuardLock) {
                    val snapshot = _state.value
                    if (snapshot.entries[readScope] == null && diskHydrationAttempted.add(readScope)) {
                        snapshot.generation to (serverGenerations[serverId] ?: 0L)
                    } else {
                        null
                    }
                }
            } else {
                null
            }
        if (hydrateTokens != null && hydrateAccountId != null) {
            hydrateFromDisk(
                accountId = hydrateAccountId,
                serverId = serverId,
                messageId = messageId,
                content = content,
                mode = mode,
                readScope = readScope,
                generation = hydrateTokens.first,
                serverGeneration = hydrateTokens.second,
            )
        }
        // Only a message with nothing live to show yet waits: a read again after Allow is the person's own and goes at once.
        val currentEntry = _state.value.entries[readScope]
        if (presence != null && (currentEntry == null || currentEntry.staleFromDisk) && !reentryCooldownPassed(readScope, presence)) return
        val (before, askedServerGeneration) =
            synchronized(stateGuardLock) {
                val snapshot = _state.value
                if (!wanted(snapshot)) return
                if (!inFlight.add(readScope)) return
                activeInitialReads += 1
                snapshot to (serverGenerations[serverId] ?: 0L)
            }
        val askedAt = before.epoch
        val askedGeneration = before.generation
        val resource = offlineResource
        val fetchStartedAt = resource?.fetchStarted()
        val accountIdAtStart = resolveDiskAccountId(viewerKey)
        val sentAt = monotonicMillis()
        var completedNormally = false
        var remainingInitialReads = 0
        val fetched =
            try {
                fetch(messageId, serverId, batch, urls = null).also { completedNormally = true }
            } finally {
                remainingInitialReads =
                    synchronized(stateGuardLock) {
                        inFlight.remove(readScope)
                        activeInitialReads = (activeInitialReads - 1).coerceAtLeast(0)
                        activeInitialReads
                    }
                if (!completedNormally && remainingInitialReads == 0) {
                    flushPendingReadCommitsSync()
                }
            }
        val answer =
            when (fetched) {
                AppLinkFetch.Stale -> {
                    if (remainingInitialReads == 0) flushPendingReadCommits()
                    return
                }
                AppLinkFetch.Failed -> null
                is AppLinkFetch.Answer -> fetched
            }
        if (answer == null) {
            noteReadFailed(readScope, sentAt)
        } else {
            readFailedAt.remove(readScope)
            caughtUpAt.remember(readScope, sentAt)
        }
        if (answer == null) {
            synchronized(stateGuardLock) {
                if ((serverGenerations[serverId] ?: 0L) == askedServerGeneration) {
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
                }
            }
            if (remainingInitialReads == 0) flushPendingReadCommits()
            return
        }
        // Ask only on a link that can become the chip: a bare link of this content.
        val hosts = artifactHosts()
        val bare = raftAppLinkReadUrls(content, hosts, linkLimit(batch)).toSet()
        val results = answer.results.filterValues { it !is AppLinkResult.Consent || it.url in bare }
        val conversationKey = appLinkConversationKey(viewerKey, conversationId)
        commitOrStageRead(
            PendingReadCommit(
                readScope = readScope,
                serverId = serverId,
                messageId = messageId,
                content = content,
                mode = mode,
                conversationKey = conversationKey,
                askedAt = askedAt,
                askedGeneration = askedGeneration,
                askedServerGeneration = askedServerGeneration,
                results = results,
                limited = answer.limited,
                viewId = batch?.view?.id,
                rawKind = answer.rawKind,
                rawBodyJson = answer.rawBodyJson,
                accountIdAtStart = accountIdAtStart,
                fetchStartedAt = fetchStartedAt,
            )
        )
    }

    private suspend fun commitOrStageRead(commit: PendingReadCommit) {
        var timerToStart: Job? = null
        var timerToCancel: Job? = null
        var acceptedForDisk: List<PendingReadCommit> = emptyList()
        synchronized(stateGuardLock) {
            if ((serverGenerations[commit.serverId] ?: 0L) != commit.askedServerGeneration) {
                if (activeInitialReads == 0 && pendingReadCommits.isNotEmpty()) {
                    timerToCancel = pendingReadCommitFlushJob
                    pendingReadCommitFlushJob = null
                    acceptedForDisk = flushPendingReadCommitsLocked()
                }
                return@synchronized
            }
            pendingReadCommits[commit.readScope] = commit
            val shouldDefer = readCommitBatchWindowMs > 0L && activeInitialReads > 0
            if (!shouldDefer) {
                timerToCancel = pendingReadCommitFlushJob
                pendingReadCommitFlushJob = null
                acceptedForDisk = flushPendingReadCommitsLocked()
            } else if (pendingReadCommitFlushJob?.isActive != true) {
                lateinit var scheduled: Job
                scheduled =
                    scope.launch(start = CoroutineStart.LAZY) {
                        delay(readCommitBatchWindowMs)
                        flushPendingReadCommits(expectedTimerJob = scheduled)
                    }
                pendingReadCommitFlushJob = scheduled
                timerToStart = scheduled
            }
        }
        timerToCancel?.cancel()
        timerToStart?.start()
        persistAcceptedCommits(acceptedForDisk)
    }

    private suspend fun flushPendingReadCommits(expectedTimerJob: Job? = null) {
        var timerToCancel: Job? = null
        val acceptedForDisk =
            synchronized(stateGuardLock) {
                if (expectedTimerJob != null) {
                    if (pendingReadCommitFlushJob === expectedTimerJob) {
                        pendingReadCommitFlushJob = null
                    }
                } else {
                    timerToCancel = pendingReadCommitFlushJob
                    pendingReadCommitFlushJob = null
                }
                flushPendingReadCommitsLocked()
            }
        timerToCancel?.cancel()
        persistAcceptedCommits(acceptedForDisk)
    }

    private fun flushPendingReadCommitsSync() {
        var timerToCancel: Job? = null
        val acceptedForDisk =
            synchronized(stateGuardLock) {
                timerToCancel = pendingReadCommitFlushJob
                pendingReadCommitFlushJob = null
                flushPendingReadCommitsLocked()
            }
        timerToCancel?.cancel()
        if (acceptedForDisk.isNotEmpty() && offlineResource != null) {
            scope.launch { persistAcceptedCommits(acceptedForDisk) }
        }
    }

    private fun flushPendingReadCommitsLocked(): List<PendingReadCommit> {
        if (pendingReadCommits.isEmpty()) return emptyList()
        val batch = pendingReadCommits.values.toList()
        pendingReadCommits.clear()
        val accepted = mutableListOf<PendingReadCommit>()
        _state.update { state ->
            accepted.clear()
            var claims: MutableMap<String, String>? = null
            var entries: LinkedHashMap<String, AppLinkLoaded>? = null
            for (commit in batch) {
                if (state.generation != commit.askedGeneration) continue
                if ((serverGenerations[commit.serverId] ?: 0L) != commit.askedServerGeneration) continue
                accepted += commit
                val nextEntries = entries ?: LinkedHashMap(state.entries).also { entries = it }
                val prompts = commit.results.values.filterIsInstance<AppLinkResult.Consent>()
                if (prompts.isNotEmpty()) {
                    val nextClaims = claims ?: state.claims.toMutableMap().also { claims = it }
                    prompts.forEach { prompt ->
                        val key = "${commit.conversationKey}:${prompt.app.id}"
                        if (key !in nextClaims) nextClaims[key] = commit.messageId
                    }
                }
                nextEntries.remove(commit.readScope)
                nextEntries[commit.readScope] =
                    AppLinkLoaded(
                        results = commit.results,
                        epochs = commit.askedAt,
                        limited = commit.limited,
                        viewId = commit.viewId,
                        serverId = commit.serverId,
                    )
                while (nextEntries.size > APP_LINK_RESULT_CACHE_MAX) nextEntries.remove(nextEntries.keys.first())
            }
            val updatedEntries = entries ?: return@update state
            state.copy(entries = updatedEntries, claims = claims ?: state.claims)
        }
        return accepted
    }

    private suspend fun persistAcceptedCommits(commits: List<PendingReadCommit>) {
        val resource = offlineResource ?: return
        for (commit in commits) {
            val accountIdAtStart = commit.accountIdAtStart ?: continue
            val fetchStartedAt = commit.fetchStartedAt ?: continue
            if (commit.limited.isNotEmpty()) continue
            val diskKey =
                resource.key(
                    accountIdAtStart,
                    commit.serverId,
                    diskScopeParam(commit.messageId, commit.content, commit.mode),
                    mintedAt = fetchStartedAt,
                ) ?: continue
            val hasCacheableCards = commit.results.values.any { it is AppLinkResult.Card && it.connectUrl == null }
            val rawKind = commit.rawKind
            val rawBody = commit.rawBodyJson
            val sanitized =
                if (hasCacheableCards && rawKind != null && rawBody != null) {
                    sanitizeAppLinkPreviewBodyForDisk(rawKind, rawBody)
                } else {
                    null
                }
            if (sanitized != null && rawKind != null) {
                runCatchingPreservingCancellation {
                    resource.write(diskKey, PersistedAppLinkPreviewRecord(rawKind, sanitized), startedAt = fetchStartedAt)
                }
            } else if (!hasCacheableCards) {
                runCatchingPreservingCancellation {
                    resource.removeIfNotSuperseded(diskKey, startedAt = fetchStartedAt)
                }
            }
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
                if (synchronized(stateGuardLock) { readScope in inFlight }) {
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
        val rawBodyText = response?.takeIf { it.isSuccessful }?.bodyText
        val parsed = rawBodyText?.let { parseAppLinkCardReadAnswer(it, resolveIcon) }
        if (parsed == null) {
            diagnosticsLogger("app_link_cards_read_failed code=${response?.code ?: -1}")
            diagnosticsLogger("app_link_cards_read $asked result=failed code=${response?.code ?: -1}")
            view.settled.value = AppLinkViewRead(seq, false, urls, emptySet())
            return AppLinkFetch.Failed
        }
        parsed.tickets.forEach { (url, ticket) -> if (ticket != null) view.tickets[url] = ticket else view.tickets.remove(url) }
        val cards = parsed.results.values.count { it is AppLinkResult.Card }
        diagnosticsLogger("app_link_cards_read $asked result=answered code=${response?.code ?: -1} items=${parsed.itemCount} cards=$cards limited=${parsed.limited.size}")
        // An item this client refused is not dropped without a trace (the card shown, or the plain link, is the fallback).
        val refused = parsed.itemCount - cards - parsed.limited.size
        if (refused > 0) reportProjectionRowDropped("app_link_cards_read", "item_refused", diagnostics = diagnosticsLogger)
        view.settled.value = AppLinkViewRead(seq, true, urls, parsed.limited.keys.mapTo(HashSet()) { appLinkPreviewHref(it) ?: it })
        return AppLinkFetch.Answer(
            results = parsed.results.stampedReadAt(nowMillis()),
            limited = parsed.limited,
            rawKind = PERSISTED_APP_LINK_KIND_BATCH.takeIf { urls == null },
            rawBodyJson = rawBodyText.takeIf { urls == null },
        )
    }

    private suspend fun fetchWithGet(messageId: String, serverId: String): AppLinkFetch {
        val response = callOrNull("app_link_previews_read_error") { api.readPreviews(messageId, serverId) }
        val rawBodyText = response?.takeIf { it.isSuccessful }?.bodyText
        val parsed = rawBodyText?.let { parseAppLinkPreviewAnswer(it, resolveIcon) }
        if (parsed == null) {
            diagnosticsLogger("app_link_previews_read_failed code=${response?.code ?: -1}")
            return AppLinkFetch.Failed
        }
        return AppLinkFetch.Answer(
            results = parsed,
            limited = emptyMap(),
            rawKind = PERSISTED_APP_LINK_KIND_GET,
            rawBodyJson = rawBodyText,
        )
    }

    /** Prepares a `confirm: "prepare"` button: the description Raft's confirmation shows. */
    suspend fun prepareAction(serverId: String, handle: String, businessRevision: String): AppLinkActionResult<AppLinkPrepared> {
        val response = callOrNull("app_link_card_prepare_error") { api.prepareLinkCardAction(serverId, AppLinkPrepareRequestBody(handle, AppLinkPrepareClaim(businessRevision))) }
            ?: return AppLinkActionResult.Failed(null, null, outOfDate = false, traceId = null)
        return parseAppLinkPrepareAnswer(response.code, response.bodyText ?: response.errorBodyText, response.traceId)
    }

    /**
     * Runs a button's action (after its confirmation, when it has one). The same [clientRequestId]
     * repeats the same intent. [inputs] and [sealedInputs] (RFC 086 §5.4) ride only in this execute
     * request and are never logged, cached, or persisted.
     */
    suspend fun executeAction(
        serverId: String,
        handle: String,
        clientRequestId: String,
        prepared: AppLinkPrepared?,
        inputs: Map<String, String>? = null,
        sealedInputs: String? = null,
    ): AppLinkActionResult<AppLinkExecuted> {
        val body =
            AppLinkExecuteRequestBody(
                clientRequestId = clientRequestId,
                handle = handle,
                preparedActionId = prepared?.preparedActionId,
                preparedDigest = prepared?.preparedDigest,
                inputs = inputs,
                sealedInputs = sealedInputs,
            )
        val response = callOrNull("app_link_card_execute_error") { api.executeLinkCardAction(serverId, body) }
            ?: return AppLinkActionResult.Failed(null, null, outOfDate = false, traceId = null)
        return parseAppLinkExecuteAnswer(response.code, response.bodyText ?: response.errorBodyText, response.traceId)
    }

    /** Reads an operation's receipt (a `link_card.operation_changed` hint named it). */
    suspend fun operationStatus(serverId: String, operationId: String): AppLinkOperationStatus {
        val response = callOrNull("app_link_card_status_error") { api.readLinkCardStatus(serverId, AppLinkStatusRequestBody(operationId)) }
            ?: return AppLinkOperationStatus.Open
        return parseAppLinkStatusAnswer(response.code, response.bodyText ?: response.errorBodyText)
    }

    /**
     * Reads one link of a message again (after a button was pressed, or its card went stale): the
     * batched read of [batch]'s view for [url] alone, which sends back the view's ticket. A card in
     * the answer replaces the card shown, whatever its revisions say and whichever row read the
     * message first.
     *
     * True only when the card shown is the one a read just answered. False when the read failed,
     * Raft answered the link `rate_limited`, the answer had no card for the link, or there was no
     * answer of this message to put it in: the card stays as it was and a Refresh says so in a toast
     * (web `LinkCardReread` `failed`), so an old card is never kept in silence. A read whose place a
     * later request of the view took reports that request's outcome instead of its own.
     *
     * No card for the link keeps the card (the web makes it the author's link). Raft answers no item
     * alike when the App says the link is plain, when the App's call failed, and when its answer was
     * invalid (slock `linkCardReadService.ts` `omit`), and an item this client refuses reads the same
     * here, so there is nothing to tell "gone" from "not now" by.
     *
     * Runs on the store's dispatcher whoever calls it: the press calls from the UI thread, and the
     * view's request count and tickets are the store's.
     */
    suspend fun rereadLink(viewerKey: String, serverId: String, messageId: String, content: String, batch: AppLinkBatchRead, url: String): Boolean =
        withContext(storeContext) { rereadLinkOnStore(appLinkReadScope(viewerKey, messageId, content, batch.mode), serverId, messageId, batch, url) }

    /** The dispatcher of [scope]: what a caller on another thread hops to before it touches a view. */
    private val storeContext: CoroutineContext = scope.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext

    private suspend fun rereadLinkOnStore(readScope: String, serverId: String, messageId: String, batch: AppLinkBatchRead, url: String): Boolean {
        var sends = 0
        while (sends < APP_LINK_PARTIAL_READ_TRIES) {
            sends += 1
            when (val read = rereadLinks(readScope, serverId, messageId, batch, listOf(url), why = "press")) {
                AppLinkReread.Failed -> return false
                is AppLinkReread.Read -> return url in read.cards
                // A later request of the view took this one's place (two quick presses, a read of the whole
                // message): that read decides what shows, so its outcome is this press's. Only when it did
                // not bring this link (it failed, or read other links) is the read sent again (web `readSome`).
                AppLinkReread.Superseded -> {
                    if (supersedingReadShows(batch.view, listOf(url))) return true
                }
            }
        }
        return false
    }

    /** Waits for the view's latest request ([AppLinkCardView.settled]) and says whether it brought every one of [urls]. */
    private suspend fun supersedingReadShows(view: AppLinkCardView, urls: List<String>): Boolean {
        val newer = withTimeoutOrNull(APP_LINK_CARD_READ_TIMEOUT_MS) { view.settled.first { it != null && it.seq >= view.seq } }
        return newer != null && urls.all(newer::shows)
    }

    /**
     * Reads [urls] of a message again, alone, in one request of [batch]'s view (web `readSome`). A
     * link answered with a card replaces the card shown, whichever view read the message first (a
     * row shown again reuses the answer of the row before it, as the web's cache does), in its place
     * among the message's links. Anything else keeps what is shown: `rate_limited`, a prompt, no item.
     */
    private suspend fun rereadLinks(
        readScope: String,
        serverId: String,
        messageId: String,
        batch: AppLinkBatchRead,
        urls: List<String>,
        /** For the diagnostics: `press` (the person's own Refresh, or a button's re-read) or `changed`. */
        why: String,
    ): AppLinkReread {
        val askedGeneration = _state.value.generation
        val line = "app_link_card_reread message=${messageId.take(8)} why=$why links=${urls.size}"
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
        // Raft names a rate_limited link as the message wrote it; the results and [urls] are by its normalized href.
        val limitedByKey = fetched.limited.mapKeys { (url, _) -> appLinkPreviewHref(url) ?: url }
        val limited = urls.mapNotNull { url -> limitedByKey[url]?.let { url to it } }.toMap()
        val fresh = urls.mapNotNull { url -> (fetched.results[url] as? AppLinkResult.Card)?.takeIf { url !in limited }?.let { url to it } }
        // What became of the answer: applied, kept or dropped and why (never in silence).
        var outcome = if (limited.isNotEmpty()) "kept reason=rate_limited" else "kept reason=no_card"
        if (fresh.isNotEmpty()) {
            flushPendingReadCommitsSync()
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
                state.copy(entries = state.entries + (readScope to entry.copy(results = entry.results + fresh)))
            }
        }
        diagnosticsLogger("$line result=$outcome cards=${fresh.size} limited=${limited.size}")
        val applied = outcome == "applied"
        // Answered but not on screen: what waits for this read must not take it for shown.
        if (fresh.isNotEmpty() && !applied) batch.view.settled.value?.let { last -> batch.view.settled.value = AppLinkViewRead(last.seq, false, last.urls, last.limited) }
        return AppLinkReread.Read(limited, cards = if (applied) fresh.map { it.first }.toSet() else emptySet(), dropped = fresh.isNotEmpty() && !applied)
    }

    /**
     * The GET form of [rereadLinks] for a nudge or the catch-up (a session that found no batched read,
     * or a row without one): the message is read with the GET, whose `objectKeys` gave its cards their
     * digests, and the cards it answers for [urls] replace those shown. Anything else stays as it is.
     */
    private suspend fun rereadWithGet(readScope: String, serverId: String, messageId: String, urls: List<String>): AppLinkReread {
        val askedGeneration = _state.value.generation
        val line = "app_link_card_reread message=${messageId.take(8)} why=changed get=true links=${urls.size}"
        if (!synchronized(stateGuardLock) { inFlight.add(readScope) }) return AppLinkReread.Superseded
        val fetched =
            try {
                fetchWithGet(messageId, serverId)
            } finally {
                synchronized(stateGuardLock) { inFlight.remove(readScope) }
            }
        if (fetched !is AppLinkFetch.Answer) {
            diagnosticsLogger("$line result=failed")
            return AppLinkReread.Failed
        }
        val fresh = urls.mapNotNull { url -> (fetched.results[url] as? AppLinkResult.Card)?.let { url to it } }
        var applied = fresh.isEmpty()
        if (fresh.isNotEmpty()) {
            flushPendingReadCommitsSync()
            _state.update { state ->
                val entry = state.entries[readScope]?.takeIf { state.generation == askedGeneration } ?: return@update state
                applied = true
                state.copy(entries = state.entries + (readScope to entry.copy(results = entry.results + fresh)))
            }
        }
        diagnosticsLogger("$line result=${if (applied) "applied" else "dropped"} cards=${fresh.size}")
        return AppLinkReread.Read(emptyMap(), cards = if (applied) fresh.map { it.first }.toSet() else emptySet(), dropped = !applied)
    }

    /** The rows on a page now ([keepFresh]). Store dispatcher only, like everything below. */
    private val presences = LinkedHashSet<AppLinkRowPresence>()

    /** By read scope: when the last read of a message that failed went out ([monotonicMillis]). Bounded. */
    private val readFailedAt = LinkedHashMap<String, Long>()
    private var changedMarks = 0
    private val freshnessGate = RaftFreshnessGate(nowMillis = monotonicMillis)

    /**
     * By read scope and object key ([refreshKey]): when a re-read for it last went out
     * ([APP_LINK_CARD_CHANGED_MIN_INTERVAL_MS] counts from then). Kept here, not on the row: a list
     * makes a new row each time a message scrolls in, and the interval must outlive it. The catch-up
     * after the app came back is kept under [APP_LINK_ALL_OBJECTS], a key of its own, so a nudge's read
     * and a came-back read of the same card on screen can land within ten seconds of each other.
     * Harmless: it is bounded to the cards on screen, one extra read per return. Bounded.
     */
    private val lastRefreshAt = LinkedHashMap<String, Long>()

    /**
     * By read scope: Raft answered a re-read of this message `rate_limited`, and no re-read of it goes
     * before [AppLinkReadHold.notBefore], whichever row asks (the row that got the answer may be gone
     * and back). Each wait is Raft's `retry_after` at least and double the last, 60 s at most
     * ([appLinkRereadBackoffMs]); an answer with no rate_limited link ends it. Bounded.
     */
    private val readHolds = LinkedHashMap<String, AppLinkReadHold>()

    /** By read scope: when the read that last brought its keyed cards went out (a whole read, or the catch-up's). Bounded. */
    private val caughtUpAt = LinkedHashMap<String, Long>()

    /** When the app last came back ([onCameBack]). */
    private var lastCameBackAt: Long? = null

    private class AppLinkReadHold(val notBefore: Long, val backoffMs: Long)

    private fun refreshKey(readScope: String, objectKey: String): String = "$readScope\n$objectKey"

    private fun <V> LinkedHashMap<String, V>.remember(key: String, value: V) {
        remove(key)
        put(key, value)
        while (size > APP_LINK_RESULT_CACHE_MAX) remove(keys.first())
    }

    private fun noteReadFailed(readScope: String, sentAt: Long) {
        readFailedAt.remember(readScope, sentAt)
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
        flushPendingReadCommitsSync()
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
     *
     * One return is one read. The socket usually comes back a moment after the foreground: within
     * [APP_LINK_CARD_RETURN_WINDOW_MS] of the last coming back it marks only the answers not read
     * since then, so a card read for the foreground is not read again for its socket. A socket that
     * comes back later than that is a return of its own (nudges were missed while it was down).
     */
    private fun onCameBack(serverId: String?) {
        flushPendingReadCommitsSync()
        val now = monotonicMillis()
        val previous = lastCameBackAt
        lastCameBackAt = now
        val sameReturnSince = previous?.takeIf { serverId != null && now - it < APP_LINK_CARD_RETURN_WINDOW_MS }
        markChanged { readScope, entry ->
            (serverId == null || entry.serverId == serverId) &&
                entry.keyedUrls(APP_LINK_ALL_OBJECTS).isNotEmpty() &&
                !(sameReturnSince != null && (caughtUpAt[readScope] ?: Long.MIN_VALUE) >= sameReturnSince)
        }
        diagnosticsLogger("app_link_card_came_back server=${serverId != null} same_return=${sameReturnSince != null}")
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
     *     doubling, 60 s at most; that wait is the message's ([readHolds]), so a row that scrolls away
     *     and back does not read sooner.
     * Silent: the card shown stays until the new one comes, and stays when none does. True when
     * nothing is left to read.
     */
    private suspend fun refreshCards(presence: AppLinkRowPresence, objectKey: String, jitter: Boolean, refresh: AppLinkCardRefresh): Boolean {
        val batch = presence.batch
        val readScope = presence.readScope
        val intervalKey = refreshKey(readScope, objectKey)
        presence.isOnScreen.first { it }
        val startedAt = monotonicMillis()
        val sinceLast = lastRefreshAt[intervalKey]?.let { startedAt - it }
        val cooldown = readFailedAt[readScope]?.let { it + APP_LINK_CARD_REENTRY_COOLDOWN_MS - startedAt } ?: 0L
        val held = readHolds[readScope]?.let { it.notBefore - startedAt } ?: 0L
        var delayMs = maxOf(maxOf(if (jitter) changedJitterMs() else 0L, sinceLast?.let { APP_LINK_CARD_CHANGED_MIN_INTERVAL_MS - it } ?: 0L), maxOf(cooldown, held))
        var urls = _state.value.entries[readScope]?.keyedUrls(objectKey).orEmpty()
        var superseded = 0
        while (urls.isNotEmpty()) {
            if (delayMs > 0L) delay(delayMs)
            presence.isEngaged.first { !it }
            presence.isOnScreen.first { it }
            // A read of the whole message on its way brings these cards.
            if (synchronized(stateGuardLock) { readScope in inFlight }) return true
            refresh.reading = true
            val sentAt = monotonicMillis()
            lastRefreshAt.remember(intervalKey, sentAt)
            val mark = _state.value.entries[readScope]?.changed ?: 0
            val busy = presence.busyUrls.value
            val reading = _state.value.entries[readScope]?.keyedUrls(objectKey).orEmpty().filter { it in urls && it !in busy }
            if (reading.isEmpty()) break
            val read =
                presence.reading.withLock {
                    // A server without the batched read has no read of one link: the GET reads the message, and only these cards are taken from it.
                    if (batch == null || batchedReadMissing) rereadWithGet(readScope, presence.serverId, presence.messageId, reading)
                    else rereadLinks(readScope, presence.serverId, presence.messageId, batch, reading, why = "changed")
                }
            when (read) {
                AppLinkReread.Failed -> {
                    noteReadFailed(readScope, sentAt)
                    return false
                }
                AppLinkReread.Superseded -> {
                    // A later request of the view (the person's press) took its place: when that one brought
                    // these cards there is nothing left; else this goes again, [APP_LINK_PARTIAL_READ_TRIES] sends at most.
                    superseded += 1
                    if (batch != null && supersedingReadShows(batch.view, reading)) urls = emptyList()
                    else if (superseded >= APP_LINK_PARTIAL_READ_TRIES) return false
                    delayMs = 0L
                }
                is AppLinkReread.Read -> {
                    if (read.dropped) return false
                    urls = reading.filter { it in read.limited }
                    if (urls.isEmpty()) {
                        readFailedAt.remove(readScope)
                        readHolds.remove(readScope)
                        if (objectKey == APP_LINK_ALL_OBJECTS) {
                            clearChanged(readScope, mark)
                            caughtUpAt.remember(readScope, sentAt)
                        }
                        return true
                    }
                    // rate_limited: no re-read of this message before Raft's retry_after, doubling, whichever row asks.
                    val backoffMs = appLinkRereadBackoffMs(readHolds[readScope]?.backoffMs ?: 0L, urls.maxOf { read.limited.getValue(it) })
                    delayMs = minOf(60_000L, backoffMs + retryJitterMs())
                    readHolds.remember(readScope, AppLinkReadHold(monotonicMillis() + delayMs, backoffMs))
                    // While it waits, a nudge is this read again, not another after it.
                    refresh.reading = false
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
        val (askedGeneration, askedServerGeneration) =
            synchronized(stateGuardLock) {
                _state.value.generation to (serverGenerations[serverId] ?: 0L)
            }
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
        synchronized(stateGuardLock) {
            if ((serverGenerations[serverId] ?: 0L) != askedServerGeneration) return true
            _state.update { state ->
                if (state.generation != askedGeneration) return@update state
                val cards = LinkedHashMap(state.messageCards)
                cards.remove(key)
                cards[key] = stamped
                while (cards.size > APP_LINK_RESULT_CACHE_MAX) cards.remove(cards.keys.first())
                val signatures = state.messageCardSignatures.filterKeys { it in cards } + (key to state.messageCardSignature(viewerKey, stamped))
                state.copy(messageCards = cards, messageCardSignatures = signatures)
            }
        }
        return true
    }

    /** Allows the App's previews here; the messages that asked read again. */
    fun allow(viewerKey: String, serverId: String, app: AppLinkPromptApp): Deferred<AppLinkConsentAnswer> =
        scope.async {
            flushPendingReadCommitsSync()
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
            flushPendingReadCommitsSync()
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
    /**
     * Answered: [cards] are the links whose card on screen is now the one just read; [limited] those Raft
     * answered `rate_limited` (their `retry_after` seconds), whose cards stay as they were. [dropped]: it
     * brought cards and they could not be put on screen (signed out, or no answer of the message held).
     */
    data class Read(val limited: Map<String, Int>, val cards: Set<String>, val dropped: Boolean = false) : AppLinkReread

    data object Failed : AppLinkReread

    /** The view sent a later request meanwhile: this answer was dropped. Not a failure. */
    data object Superseded : AppLinkReread
}

/** What one read came to ([SharedAppLinkPreviewStore] `fetch`). */
/** Each card answer of one read carries when it was read (its handles' age). */
private fun Map<String, AppLinkResult>.stampedReadAt(atMillis: Long): Map<String, AppLinkResult> =
    mapValues { (_, result) -> if (result is AppLinkResult.Card) result.copy(readAtMillis = atMillis) else result }

private sealed interface AppLinkFetch {
    data class Answer(
        val results: Map<String, AppLinkResult>,
        val limited: Map<String, Int>,
        val rawKind: String? = null,
        val rawBodyJson: String? = null,
    ) : AppLinkFetch

    data object Failed : AppLinkFetch

    /** The view sent a later request meanwhile: this answer is dropped. */
    data object Stale : AppLinkFetch
}

private data class PendingReadCommit(
    val readScope: String,
    val serverId: String,
    val messageId: String,
    val content: String,
    val mode: String,
    val conversationKey: String,
    val askedAt: Map<String, Int>,
    val askedGeneration: Int,
    val askedServerGeneration: Long,
    val results: Map<String, AppLinkResult>,
    val limited: Map<String, Int>,
    val viewId: String?,
    val rawKind: String?,
    val rawBodyJson: String?,
    val accountIdAtStart: String?,
    val fetchStartedAt: Long?,
)

private const val APP_LINK_RESULT_CACHE_MAX = 300
