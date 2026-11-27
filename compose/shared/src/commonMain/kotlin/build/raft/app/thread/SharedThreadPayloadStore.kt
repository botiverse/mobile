package build.raft.app.thread

import build.raft.app.SharedThreadFreshnessGeneration
import build.raft.app.SharedThreadFreshnessLedger
import build.raft.app.SharedThreadOwnedPayloadCacheKey
import build.raft.app.SharedThreadPayloadCacheKey
import build.raft.app.SharedThreadReplyCountFact
import build.raft.app.SharedThreadTimelineCacheMaxConversations
import build.raft.app.SharedThreadTimelineCacheMaxConversationsPerServer
import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedForegroundLoadGate
import build.raft.app.data.SharedReferenceResolver
import build.raft.app.data.ThreadSnapshot
import build.raft.app.data.map
import build.raft.app.get
import build.raft.app.messages
import build.raft.app.present
import build.raft.app.requireSharedThreadOwnerScopeId
import build.raft.app.sharedThreadOwnedPayloadCacheKey
import build.raft.app.sharedThreadTimelineAccountScopePrefix
import build.raft.app.data.TaskConversationChannelRef
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val SharedThreadPrewarmMaxConcurrency = 2

private const val SharedThreadPrewarmStartIntervalMillis = 100L
private const val SharedThreadPrewarmGenerationSupersededCode = "thread_prewarm_generation_superseded"
private const val SharedThreadStaleConversationLimit = 256
private const val SharedThreadPrewarmJoinTimeoutMillis = 5_000L

/**
 * artin #111 (prewarm age limit): an open takes a ready prewarm as its final answer only while it is
 * younger than this. An older one still paints the first frame from the cache, but the open fetches
 * its own answer to reconcile, because the freshness generation alone cannot prove a quiet socket.
 */
private const val SharedThreadPrewarmReadyMaxAgeMillis = 120_000L
private data class SharedThreadPayloadEntry(
    val payload: RaftThreadScreenPayload,
    val generation: SharedThreadFreshnessGeneration
)

/**
 * App-scope first-frame cache shared by Activity prewarm and visible Thread stores.
 *
 * Route payloads remain identity/intent-only. Background prewarm writes the same
 * canonical payload candidate that [SharedThreadStore] reads on start, so opening
 * a warmed reply thread does not need a second route-owned content model.
 */
internal class SharedThreadPayloadStore(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    maxConcurrentPrewarms: Int = SharedThreadPrewarmMaxConcurrency,
    private val prewarmStartIntervalMillis: Long = SharedThreadPrewarmStartIntervalMillis,
    internal val freshnessLedger: SharedThreadFreshnessLedger = SharedThreadFreshnessLedger(),
    private val replyThreadAccessFlights: SharedReplyThreadAccessFlightStore<ThreadSnapshot> =
        SharedReplyThreadAccessFlightStore(scope),
    /** Tenny rule 3: per-server LRU cap (one server = one env + account + server owner). */
    private val maxConversationsPerServer: Int = SharedThreadTimelineCacheMaxConversationsPerServer,
    /** Tenny rule 3: process-wide LRU cap across every server and account. */
    private val maxConversations: Int = SharedThreadTimelineCacheMaxConversations,
    /**
     * Process-wide "a visible thread page is loading its first content" gate. Owned here because
     * this store is the one object every visible thread page and the Activity reply prewarm share.
     */
    internal val foregroundLoadGate: SharedForegroundLoadGate = SharedForegroundLoadGate(),
    /** A visible open never waits on an in-flight prewarm longer than this before fetching itself. */
    private val prewarmJoinTimeoutMillis: Long = SharedThreadPrewarmJoinTimeoutMillis,
    // task #41: the clock of the prewarm start rate limit. Its wait is a `delay`, so a virtual-time
    // test hands in the scheduler's time source to keep the elapsed check and the wait on one clock;
    // production keeps the monotonic wall clock.
    private val prewarmStartTimeSource: TimeSource = TimeSource.Monotonic,
    /** artin #111: the age limit of a ready prewarm, see [SharedThreadPrewarmReadyMaxAgeMillis]. */
    private val prewarmReadyMaxAgeMillis: Long = SharedThreadPrewarmReadyMaxAgeMillis,
    private val prewarmReadyTimeSource: TimeSource = TimeSource.Monotonic,
) {
    private val payloads = MutableStateFlow<Map<SharedThreadOwnedPayloadCacheKey, SharedThreadPayloadEntry>>(emptyMap())
    // Least recently used first. Guarded by [recencyLock]; lock order is ledger -> recency, never
    // the reverse, because commits run inside `freshnessLedger.runIfCurrent`.
    private val recencyLock = SynchronizedObject()
    private val recency = LinkedHashSet<SharedThreadOwnedPayloadCacheKey>()
    private val prewarmMutex = Mutex()
    private val prewarmPool = Semaphore(maxConcurrentPrewarms.coerceAtLeast(1))
    private val prewarmStartMutex = Mutex()
    private val prewarmInFlight = mutableMapOf<SharedThreadOwnedPayloadCacheKey, Deferred<DataResult<RaftThreadScreenPayload>>>()
    private val prewarmInFlightGenerations =
        mutableMapOf<SharedThreadOwnedPayloadCacheKey, SharedThreadFreshnessGeneration>()
    private val prewarmFetchStartedGenerations =
        mutableMapOf<SharedThreadOwnedPayloadCacheKey, SharedThreadFreshnessGeneration>()
    private val prewarmReadyGenerations =
        mutableMapOf<SharedThreadOwnedPayloadCacheKey, SharedThreadFreshnessGeneration>()
    /** When each ready prewarm landed (guarded by [prewarmMutex]); read only by the age limit. */
    private val prewarmReadyAt = mutableMapOf<SharedThreadOwnedPayloadCacheKey, TimeMark>()
    private val prewarmFailureGenerations =
        mutableMapOf<SharedThreadOwnedPayloadCacheKey, SharedThreadFreshnessGeneration>()
    private val prewarmFailures = mutableMapOf<SharedThreadOwnedPayloadCacheKey, DataResult<RaftThreadScreenPayload>>()
    private val failedPrewarmTakeovers =
        mutableMapOf<SharedThreadOwnedPayloadCacheKey, Deferred<DataResult<RaftThreadScreenPayload>>>()
    private val failedPrewarmTakeoverGenerations =
        mutableMapOf<SharedThreadOwnedPayloadCacheKey, SharedThreadFreshnessGeneration>()
    private val failedPrewarmTakeoverResults =
        mutableMapOf<SharedThreadOwnedPayloadCacheKey, DataResult<RaftThreadScreenPayload>>()
    private val failedPrewarmTakeoverResultGenerations =
        mutableMapOf<SharedThreadOwnedPayloadCacheKey, SharedThreadFreshnessGeneration>()
    private var lastPrewarmStartedAt: TimeMark? = null
    /** Reference opens (thread, message, task refs): local first, one shared fetch phase, resolutions kept. */
    internal val referenceResolver: SharedReferenceResolver = SharedReferenceResolver()
    // A task conversation's task / parent channel never change: keep every ref this process read
    // (bounded, least recently used first) so a reopen resolves composer access without a request.
    private val taskConversationRefLock = SynchronizedObject()
    private val taskConversationRefs = LinkedHashMap<SharedTaskConversationRefKey, TaskConversationChannelRef>()
    private val taskConversationRefFlights =
        mutableMapOf<SharedTaskConversationRefKey, Deferred<DataResult<TaskConversationChannelRef>>>()

    fun cachedTaskConversationRef(key: SharedTaskConversationRefKey): TaskConversationChannelRef? =
        synchronized(taskConversationRefLock) {
            taskConversationRefs.remove(key)?.also { taskConversationRefs[key] = it }
        }

    /**
     * The ref of a task conversation: the cached one, else one shared fetch. The fetch runs on this
     * process-wide [scope], so a caller that stops waiting (the thread page's 1 s initial-access
     * deadline) does not cancel it: the ref still lands in the cache and a caller that keeps waiting
     * still gets it.
     */
    suspend fun taskConversationRef(
        key: SharedTaskConversationRefKey,
        load: suspend () -> DataResult<TaskConversationChannelRef>,
    ): DataResult<TaskConversationChannelRef> {
        cachedTaskConversationRef(key)?.let { return DataResult.Content(it) }
        var started = false
        val flight =
            synchronized(taskConversationRefLock) {
                taskConversationRefFlights[key]?.takeUnless { it.isCompleted }
                    ?: scope
                        .async(start = CoroutineStart.LAZY) {
                            val result =
                                try {
                                    load()
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (_: Throwable) {
                                    DataResult.Error(ScreenError(code = "task_conversation_ref_network", retryable = true))
                                }
                            if (result is DataResult.Content) {
                                synchronized(taskConversationRefLock) {
                                    taskConversationRefs.remove(key)
                                    taskConversationRefs[key] = result.data
                                    while (taskConversationRefs.size > SharedThreadTaskConversationRefCacheLimit) {
                                        taskConversationRefs.remove(taskConversationRefs.keys.first())
                                    }
                                }
                            }
                            result
                        }
                        .also { created ->
                            taskConversationRefFlights[key] = created
                            started = true
                        }
            }
        if (started) {
            // Completed, failed or cancelled: the flight leaves the map either way, so the map holds
            // only fetches still running.
            flight.invokeOnCompletion {
                synchronized(taskConversationRefLock) {
                    if (taskConversationRefFlights[key] === flight) taskConversationRefFlights.remove(key)
                }
            }
            flight.start()
        }
        return flight.await()
    }

    /** Session / account boundary: refs and running ref fetches leave with the account. */
    private fun clearTaskConversationRefs(matching: (SharedTaskConversationRefKey) -> Boolean) {
        val cancelled =
            synchronized(taskConversationRefLock) {
                taskConversationRefs.keys.removeAll(matching)
                val flights = taskConversationRefFlights.filterKeys(matching)
                flights.keys.forEach(taskConversationRefFlights::remove)
                flights.values
            }
        cancelled.forEach { it.cancel() }
    }

    internal fun taskConversationRefFlightCountForTest(): Int =
        synchronized(taskConversationRefLock) { taskConversationRefFlights.size }

    suspend fun joinOrStartReplyThreadAccessFlight(
        key: SharedReplyThreadAccessFlightKey,
        replaceFailure: Boolean = false,
        fetch: suspend () -> DataResult<ThreadSnapshot>
    ): DataResult<ThreadSnapshot> =
        replyThreadAccessFlights.joinOrStart(key, replaceFailure, fetch)

    suspend fun replyThreadAccessFailure(key: SharedReplyThreadAccessFlightKey): ScreenError? =
        replyThreadAccessFlights.failure(key)

    suspend fun clearReplyThreadAccessFailure(key: SharedReplyThreadAccessFlightKey) {
        replyThreadAccessFlights.clearFailure(key)
    }

    /**
     * Hands 7e198cab: conversations whose realtime durable write was refused (`poisoned`), by cause.
     * Later messages may be missing from every local copy (memory frame, durable window), so the
     * next open shows none of them as complete and waits for the network; a canonical refresh that
     * lands clears the mark. Bounded (oldest dropped); leaves with the session.
     */
    private val staleConversationCauses = LinkedHashMap<SharedThreadOwnedPayloadCacheKey, String>()

    fun markConversationStale(serverId: String, threadId: String, ownerScopeId: String, cause: String) {
        if (serverId.isBlank() || threadId.isBlank() || ownerScopeId.isBlank()) return
        val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
        synchronized(recencyLock) {
            staleConversationCauses.remove(key)
            staleConversationCauses[key] = cause
            while (staleConversationCauses.size > SharedThreadStaleConversationLimit) {
                staleConversationCauses.remove(staleConversationCauses.keys.first())
            }
        }
    }

    fun staleConversationCause(serverId: String, threadId: String, ownerScopeId: String): String? {
        if (serverId.isBlank() || threadId.isBlank() || ownerScopeId.isBlank()) return null
        val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
        return synchronized(recencyLock) { staleConversationCauses[key] }
    }

    fun clearConversationStale(serverId: String, threadId: String, ownerScopeId: String) {
        if (serverId.isBlank() || threadId.isBlank() || ownerScopeId.isBlank()) return
        val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
        synchronized(recencyLock) { staleConversationCauses.remove(key) }
    }

    /**
     * Hands 4cf4c781: conversations whose canonical access this process resolved (owner + server +
     * thread). A reopen showing the memory frame keeps that resolution instead of hiding the
     * composer until the refresh lands; the refresh still applies its access result. Dropped with
     * the conversation, the server, or the session, and on a terminal access result.
     */
    private val resolvedAccessKeys = mutableSetOf<SharedThreadOwnedPayloadCacheKey>()

    fun setResolvedAccess(serverId: String, threadId: String, ownerScopeId: String, resolved: Boolean) {
        if (serverId.isBlank() || threadId.isBlank() || ownerScopeId.isBlank()) return
        val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
        synchronized(recencyLock) {
            if (resolved) resolvedAccessKeys += key else resolvedAccessKeys -= key
        }
    }

    fun hasResolvedAccess(serverId: String, threadId: String, ownerScopeId: String): Boolean {
        if (serverId.isBlank() || threadId.isBlank() || ownerScopeId.isBlank()) return false
        val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
        return synchronized(recencyLock) { key in resolvedAccessKeys }
    }

    fun retireOwner(ownerScopeId: String) {
        val retired = freshnessLedger.retireOwner(ownerScopeId)
        if (retired.isEmpty()) return
        // Preserve already-committed local-first candidates while changing their generation token;
        // producers holding the retired token can no longer commit after an owner transition.
        payloads.update { current ->
            current.mapValues { (key, entry) ->
                val transition = retired[key]
                if (transition?.first === entry.generation) {
                    SharedThreadPayloadEntry(entry.payload, transition.second)
                } else {
                    entry
                }
            }
        }
    }

    /**
     * Account / environment boundary (Tenny rule 1): fence every in-flight producer of every server
     * owned by this environment + account. Committed entries are re-tokened, not dropped; they
     * stay unreachable for another account because the account is part of the key.
     */
    fun retireAccount(environmentId: String, principalId: String) {
        if (environmentId.isBlank() || principalId.isBlank()) return
        clearTaskConversationRefs { key -> key.environmentId == environmentId && key.principalId == principalId }
        val prefix = sharedThreadTimelineAccountScopePrefix(environmentId, principalId)
        val retired = freshnessLedger.retireOwnersMatching { owner -> owner.startsWith(prefix) }
        if (retired.isEmpty()) return
        payloads.update { current ->
            current.mapValues { (key, entry) ->
                val transition = retired[key]
                if (transition?.first === entry.generation) {
                    SharedThreadPayloadEntry(entry.payload, transition.second)
                } else {
                    entry
                }
            }
        }
    }

    /**
     * Tenny rule 2, server scope: the account was removed from [serverId] (or the server was
     * deleted). Drop that server's whole timeline partition for every owner and fence late
     * producers, so neither a cached first frame nor a late response can bring it back.
     */
    fun dropServer(serverId: String) {
        if (serverId.isBlank()) return
        freshnessLedger.retireOwnersMatching(serverId = serverId) { true }
        synchronized(recencyLock) {
            payloads.update { current -> current.filterKeys { key -> key.payloadKey.serverId != serverId } }
            recency.removeAll { key -> key.payloadKey.serverId == serverId }
            resolvedAccessKeys.removeAll { key -> key.payloadKey.serverId == serverId }
        }
    }

    /**
     * Tenny rule 2, conversation scope: a background validation proved the account lost access to
     * this conversation (403/404). Remove its cached timeline and fence in-flight producers.
     */
    fun evictConversation(serverId: String, threadId: String, ownerScopeId: String) {
        if (serverId.isBlank() || threadId.isBlank() || ownerScopeId.isBlank()) return
        val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
        freshnessLedger.advanceAndRun(serverId, threadId, ownerScopeId, cause = "access_lost") { _, _ ->
            synchronized(recencyLock) {
                payloads.update { current -> current - key }
                recency.remove(key)
                resolvedAccessKeys.remove(key)
            }
        }
    }

    /**
     * Session end (sign-out): cached timelines are message content and leave with the session.
     * Every owner generation is retired so a late producer cannot re-commit afterwards.
     */
    fun clearAll() {
        clearTaskConversationRefs { true }
        freshnessLedger.retireOwnersMatching { true }
        synchronized(recencyLock) {
            payloads.value = emptyMap()
            recency.clear()
            resolvedAccessKeys.clear()
            staleConversationCauses.clear()
        }
        referenceResolver.clear()
    }

    /** Test seam: owner keys currently cached, least recently used first. */
    internal fun cachedKeysForTest(): List<SharedThreadOwnedPayloadCacheKey> =
        synchronized(recencyLock) { recency.filter { key -> key in payloads.value }.toList() }

    /**
     * Read-only lookup for Activity's unlisted-thread handler (#proj-mobile:#163): returns the
     * cached parent message's author/type and whether the parent payload already marked the thread
     * followed. Durable offline stand-ins are skipped so stale cache never suppresses a re-read.
     */
    internal fun cachedParentMessageForActivity(
        serverId: String,
        parentChannelId: String,
        parentMessageId: String,
    ): Pair<build.raft.app.data.ActivityKnownParentMessage, Boolean>? {
        val entry =
            payloads.value.entries.firstOrNull { (key, value) ->
                key.payloadKey.serverId == serverId &&
                    key.payloadKey.threadId == parentChannelId &&
                    !value.payload.durableLastKnown
            }?.value ?: return null
        val msg = entry.payload.messages.firstOrNull { it.id == parentMessageId } ?: return null
        val senderKind =
            when (msg.authorKind.trim().lowercase()) {
                "agent" -> build.raft.app.model.MemberKind.Agent
                "system" -> build.raft.app.model.MemberKind.System
                "external", "external_projection" -> build.raft.app.model.MemberKind.External
                else -> build.raft.app.model.MemberKind.Human
            }
        val messageType =
            if (msg.messageType.equals("system", ignoreCase = true)) {
                build.raft.app.model.MessageType.System
            } else {
                build.raft.app.model.MessageType.Chat
            }
        return build.raft.app.data.ActivityKnownParentMessage(
            senderId = msg.authorId,
            senderKind = senderKind,
            messageType = messageType,
        ) to msg.isThreadFollowed
    }

    private fun commitEntry(key: SharedThreadOwnedPayloadCacheKey, entry: SharedThreadPayloadEntry) {
        synchronized(recencyLock) {
            payloads.update { current -> current + (key to entry) }
            recency.remove(key)
            recency.add(key)
            trimToCapsLocked()
        }
    }

    private fun touch(key: SharedThreadOwnedPayloadCacheKey) {
        synchronized(recencyLock) {
            if (recency.remove(key)) recency.add(key)
        }
    }

    /**
     * Evicts least recently used conversations first: within a server beyond
     * [maxConversationsPerServer], then across all servers beyond [maxConversations]. The entry
     * just written is the most recent one and is never the victim.
     */
    private fun trimToCapsLocked() {
        val present = payloads.value.keys
        recency.retainAll(present)
        present.filterNot(recency::contains).forEach(recency::add)
        val victims = mutableSetOf<SharedThreadOwnedPayloadCacheKey>()
        val perServerCap = maxConversationsPerServer.coerceAtLeast(1)
        recency.groupBy { key -> key.ownerScopeId }.values.forEach { ownerKeys ->
            if (ownerKeys.size > perServerCap) victims += ownerKeys.take(ownerKeys.size - perServerCap)
        }
        val survivors = recency.filterNot(victims::contains)
        val totalCap = maxConversations.coerceAtLeast(1)
        if (survivors.size > totalCap) victims += survivors.take(survivors.size - totalCap)
        if (victims.isEmpty()) return
        recency.removeAll(victims)
        payloads.update { current -> current - victims }
    }

    fun get(
        serverId: String,
        threadId: String,
        environmentId: String,
        principalId: String,
        ownerScopeId: String
    ): RaftThreadScreenPayload? {
        val generation = freshnessLedger.capture(serverId, threadId, ownerScopeId)
        val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
        return payloads.value[key]
            ?.takeIf { entry -> entry.generation === generation }
            ?.also { touch(key) }
            ?.payload
            ?.let { payload ->
                freshnessLedger.applyReplyCountFacts(serverId, threadId, environmentId, principalId, payload)
            }
    }

    /**
     * Hands 4b6ccb0d: the committed entry whose generation a later content fact (an Activity summary,
     * a message mutation, a resume) has advanced, i.e. exactly what [get] refuses. Owner, account and
     * server boundaries never leave such an entry behind ([retireOwner] / [retireAccount] re-token it,
     * [dropServer] / [evictConversation] / [remove] drop it), so this is still this owner's content,
     * only no longer known to be the newest. It is a first-frame stand-in candidate and nothing else:
     * the caller must compare it with the durable frame and always refresh afterwards. `null` when the
     * entry is missing or current (then [get] answers).
     */
    fun getSuperseded(
        serverId: String,
        threadId: String,
        environmentId: String,
        principalId: String,
        ownerScopeId: String
    ): RaftThreadScreenPayload? {
        val generation = freshnessLedger.capture(serverId, threadId, ownerScopeId)
        val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
        return payloads.value[key]
            ?.takeIf { entry -> entry.generation !== generation }
            ?.payload
            ?.let { payload ->
                freshnessLedger.applyReplyCountFacts(serverId, threadId, environmentId, principalId, payload)
            }
    }

    fun put(
        serverId: String,
        threadId: String,
        payload: RaftThreadScreenPayload,
        ownerScopeId: String
    ) {
        val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
        val generation = freshnessLedger.capture(serverId, threadId, ownerScopeId)
        commitEntry(key, SharedThreadPayloadEntry(payload, generation))
    }

    fun putIfCurrent(
        generation: SharedThreadFreshnessGeneration,
        payload: RaftThreadScreenPayload,
        ownerScopeId: String
    ): Boolean {
        if (generation.ownerScopeId != ownerScopeId) return false
        return freshnessLedger.runIfCurrent(generation) {
            val key =
                sharedThreadOwnedPayloadCacheKey(
                    ownerScopeId,
                    generation.key.serverId,
                    generation.key.threadId
                )
            commitEntry(key, SharedThreadPayloadEntry(payload, generation))
        }
    }

    fun recordReplyCountFacts(
        serverId: String,
        threadId: String,
        environmentId: String,
        principalId: String,
        facts: List<Pair<String, SharedThreadReplyCountFact>>
    ) {
        freshnessLedger.recordReplyCountFacts(serverId, threadId, environmentId, principalId, facts)
    }

    fun applyReplyCountFacts(
        serverId: String,
        threadId: String,
        environmentId: String,
        principalId: String,
        payload: RaftThreadScreenPayload
    ): RaftThreadScreenPayload =
        freshnessLedger.applyReplyCountFacts(serverId, threadId, environmentId, principalId, payload)

    fun captureGeneration(
        serverId: String,
        threadId: String,
        ownerScopeId: String
    ): SharedThreadFreshnessGeneration =
        freshnessLedger.capture(serverId, threadId, ownerScopeId)

    fun remove(
        serverId: String,
        threadId: String,
        ownerScopeId: String
    ) {
        val ownedKey = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
        val ownedLegacyKey = sharedThreadOwnedPayloadCacheKey(ownerScopeId, "", threadId)
        freshnessLedger.advanceAndRun(serverId, threadId, ownerScopeId) { _, _ ->
            payloads.update { current ->
                current.filterKeys { candidate ->
                    candidate != ownedLegacyKey && candidate != ownedKey
                }
            }
        }
    }

    fun removeIfCurrent(generation: SharedThreadFreshnessGeneration): Boolean =
        freshnessLedger.advanceIfCurrent(generation) {
            payloads.update { current ->
                current.filterKeys { candidate ->
                    if (
                        candidate.ownerScopeId == generation.ownerScopeId &&
                            candidate.payloadKey == SharedThreadPayloadCacheKey("", generation.key.threadId)
                    ) {
                        return@filterKeys false
                    }
                    candidate.ownerScopeId != generation.ownerScopeId ||
                        candidate.payloadKey != generation.key ||
                        current[candidate]?.generation !== generation
                }
            }
        }

    fun values(
        serverId: String,
        ownerScopeId: String
    ): Collection<RaftThreadScreenPayload> {
        requireSharedThreadOwnerScopeId(ownerScopeId)
        return payloads.value
            .filter { (key, entry) ->
                key.ownerScopeId == ownerScopeId &&
                    key.payloadKey.serverId == serverId &&
                    freshnessLedger.isCurrent(entry.generation)
            }
            .values
            .map { entry -> entry.payload }
    }

    suspend fun prewarm(
        serverId: String,
        threadId: String,
        ownerScopeId: String,
        fetch: suspend () -> DataResult<RaftThreadScreenPayload>
    ): DataResult<RaftThreadScreenPayload> = coroutineScope {
        val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
        val generation = freshnessLedger.capture(serverId, threadId, ownerScopeId)

        var cached: RaftThreadScreenPayload? = null
        var created: Deferred<DataResult<RaftThreadScreenPayload>>? = null
        val deferred =
            prewarmMutex.withLock {
                if (prewarmReadyGenerations[key] !== generation) {
                    prewarmReadyGenerations.remove(key)
                }
                if (prewarmFailureGenerations[key] !== generation) {
                    prewarmFailures.remove(key)
                    prewarmFailureGenerations.remove(key)
                }
                if (prewarmInFlightGenerations[key] !== generation) {
                    prewarmInFlight.remove(key)
                    prewarmInFlightGenerations.remove(key)
                    prewarmFetchStartedGenerations.remove(key)
                    failedPrewarmTakeovers.remove(key)
                    failedPrewarmTakeoverGenerations.remove(key)
                    failedPrewarmTakeoverResults.remove(key)
                    failedPrewarmTakeoverResultGenerations.remove(key)
                }
                if (prewarmReadyGenerations[key] === generation) {
                    cached = payloads.value[key]
                        ?.takeIf { entry -> entry.generation === generation }
                        ?.payload
                    if (cached != null) return@withLock null
                    prewarmReadyGenerations.remove(key)
                }
                prewarmInFlight[key]
                    ?.takeIf { prewarmInFlightGenerations[key] === generation }
                    ?: failedPrewarmTakeovers[key]
                        ?.takeIf { failedPrewarmTakeoverGenerations[key] === generation }
                    ?: scope.async(start = CoroutineStart.LAZY) {
                        try {
                            val result =
                                prewarmPool.withPermit {
                                    awaitPrewarmStartRateLimit()
                                    val currentJob = currentCoroutineContext()[Job]
                                    val stillOwned =
                                        prewarmMutex.withLock {
                                            if (
                                                prewarmInFlightGenerations[key] === generation &&
                                                prewarmInFlight[key] === currentJob
                                            ) {
                                                prewarmFetchStartedGenerations[key] = generation
                                                true
                                            } else {
                                                false
                                            }
                                        }
                                    if (!stillOwned) {
                                        throw CancellationException("queued thread prewarm was promoted")
                                    }
                                    fetch()
                                }
                            prewarmMutex.withLock {
                                val currentJob = currentCoroutineContext()[Job]
                                if (
                                    prewarmInFlightGenerations[key] === generation &&
                                    prewarmInFlight[key] === currentJob
                                ) {
                                    freshnessLedger.runIfCurrent(generation) {
                                        if (result is DataResult.Content) {
                                            commitEntry(key, SharedThreadPayloadEntry(result.data, generation))
                                            prewarmReadyGenerations[key] = generation
                                            prewarmReadyAt[key] = prewarmReadyTimeSource.markNow()
                                            prewarmFailures.remove(key)
                                            prewarmFailureGenerations.remove(key)
                                            failedPrewarmTakeoverResults.remove(key)
                                            failedPrewarmTakeoverResultGenerations.remove(key)
                                        } else {
                                            prewarmFailures[key] = result
                                            prewarmFailureGenerations[key] = generation
                                        }
                                    }
                                }
                            }
                            result
                        } finally {
                            val currentJob = currentCoroutineContext()[Job]
                            prewarmMutex.withLock {
                                if (prewarmInFlight[key] === currentJob) {
                                    prewarmInFlight.remove(key)
                                    prewarmInFlightGenerations.remove(key)
                                    prewarmFetchStartedGenerations.remove(key)
                                }
                            }
                        }
                    }.also { candidate ->
                        prewarmFailures.remove(key)
                        failedPrewarmTakeoverResults.remove(key)
                        failedPrewarmTakeoverResultGenerations.remove(key)
                        prewarmInFlight[key] = candidate
                        prewarmInFlightGenerations[key] = generation
                        created = candidate
                    }
            }
        cached?.let { return@coroutineScope DataResult.Content(it) }
        val shared = deferred ?: error("prewarm generation lookup missing")
        created?.start()
        shared.await()
    }

    /**
     * A visible Thread must not inherit the queue delay of a background prewarm that has not
     * reached its network fetch yet. Detach and cancel only that queued generation; an already
     * started request remains single-flight and is joined by [joinOrConsumePrewarm].
     */
    suspend fun promoteQueuedPrewarm(
        serverId: String,
        threadId: String,
        ownerScopeId: String
    ): Boolean {
        val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
        var queued: Deferred<DataResult<RaftThreadScreenPayload>>? = null
        prewarmMutex.withLock {
            val generation = freshnessLedger.capture(serverId, threadId, ownerScopeId)
            val candidate =
                prewarmInFlight[key]
                    ?.takeIf { prewarmInFlightGenerations[key] === generation }
                    ?: return@withLock
            if (prewarmFetchStartedGenerations[key] === generation) return@withLock
            prewarmInFlight.remove(key)
            prewarmInFlightGenerations.remove(key)
            prewarmFetchStartedGenerations.remove(key)
            queued = candidate
        }
        queued?.cancel()
        return queued != null
    }

    suspend fun joinOrConsumePrewarm(
        serverId: String,
        threadId: String,
        ownerScopeId: String
    ): DataResult<RaftThreadScreenPayload>? =
        joinOrConsumePrewarmWithOutcome(serverId, threadId, ownerScopeId).result

    /**
     * Like [joinOrConsumePrewarm] but says which prewarm state answered, and bounds the join of an
     * in-flight prewarm by [prewarmJoinTimeoutMillis]: a stuck background request must never hold
     * a visible open. On [SharedThreadPrewarmJoinOutcome.JoinTimedOut] the in-flight prewarm is
     * detached (not cancelled: callers already awaiting it still get its result, but it can no longer
     * commit into the cache or mark itself ready) and the caller issues its own fetch.
     */
    suspend fun joinOrConsumePrewarmWithOutcome(
        serverId: String,
        threadId: String,
        ownerScopeId: String
    ): SharedThreadPrewarmJoin {
        val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
        val lookup =
            prewarmMutex.withLock {
                val generation = freshnessLedger.capture(serverId, threadId, ownerScopeId)
                val readyAt = prewarmReadyAt.remove(key)
                val cached =
                    if (prewarmReadyGenerations[key] === generation) {
                        prewarmReadyGenerations.remove(key)
                        payloads.value[key]
                            ?.takeIf { entry -> entry.generation === generation }
                            ?.payload
                            // Too old to be the final answer: the open fetches its own (the cache entry stays).
                            ?.takeUnless {
                                readyAt != null && readyAt.elapsedNow().inWholeMilliseconds > prewarmReadyMaxAgeMillis
                            }
                    } else {
                        null
                    }
                SharedThreadPrewarmLookup(
                    generation = generation,
                    cached = cached,
                    inFlight = prewarmInFlight[key]
                        ?.takeIf { prewarmInFlightGenerations[key] === generation },
                    failure = prewarmFailures[key]
                        ?.takeIf { prewarmFailureGenerations[key] === generation }
                )
            }
        lookup.cached?.let {
            return SharedThreadPrewarmJoin(DataResult.Content(it), SharedThreadPrewarmJoinOutcome.ConsumedReady)
        }
        val inFlight = lookup.inFlight
        val outcome: SharedThreadPrewarmJoinOutcome
        val result =
            if (inFlight != null) {
                val joined =
                    withTimeoutOrNull(prewarmJoinTimeoutMillis.coerceAtLeast(1L)) { inFlight.await() }
                if (joined == null) {
                    // The caller now fetches for itself under this same generation. Detach (do not
                    // cancel) the slow prewarm, exactly like [promoteQueuedPrewarm] does, so its late
                    // completion fails the `prewarmInFlight[key] === currentJob` commit guard instead
                    // of overwriting the newer own-fetch entry and marking itself ready.
                    prewarmMutex.withLock {
                        if (prewarmInFlight[key] === inFlight) {
                            prewarmInFlight.remove(key)
                            prewarmInFlightGenerations.remove(key)
                            prewarmFetchStartedGenerations.remove(key)
                        }
                    }
                    return SharedThreadPrewarmJoin(null, SharedThreadPrewarmJoinOutcome.JoinTimedOut)
                }
                outcome = SharedThreadPrewarmJoinOutcome.JoinedInFlight
                joined
            } else if (lookup.failure != null) {
                outcome = SharedThreadPrewarmJoinOutcome.StoredFailure
                lookup.failure
            } else {
                return SharedThreadPrewarmJoin(null, SharedThreadPrewarmJoinOutcome.None)
            }
        if (freshnessLedger.isCurrent(lookup.generation)) return SharedThreadPrewarmJoin(result, outcome)
        return SharedThreadPrewarmJoin(
            DataResult.Error(
                ScreenError(code = SharedThreadPrewarmGenerationSupersededCode, retryable = true)
            ),
            outcome
        )
    }

    suspend fun takeOverFailedPrewarm(
        serverId: String,
        threadId: String,
        ownerScopeId: String,
        fetch: suspend () -> DataResult<RaftThreadScreenPayload>
    ): DataResult<RaftThreadScreenPayload> = coroutineScope {
        val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
        while (true) {
            var immediate: DataResult<RaftThreadScreenPayload>? = null
            var created: Deferred<DataResult<RaftThreadScreenPayload>>? = null
            lateinit var generation: SharedThreadFreshnessGeneration
            val deferred =
                prewarmMutex.withLock {
                    generation = freshnessLedger.capture(serverId, threadId, ownerScopeId)
                    if (prewarmReadyGenerations[key] === generation) {
                        val readyPayload = payloads.value[key]
                            ?.takeIf { entry -> entry.generation === generation }
                            ?.payload
                        if (readyPayload != null) {
                            readyPayload.let { payload ->
                                immediate = DataResult.Content(payload)
                                return@withLock null
                            }
                        } else {
                            prewarmReadyGenerations.remove(key)
                        }
                    }
                    if (failedPrewarmTakeoverResultGenerations[key] !== generation) {
                        failedPrewarmTakeoverResults.remove(key)
                        failedPrewarmTakeoverResultGenerations.remove(key)
                    }
                    if (failedPrewarmTakeoverGenerations[key] !== generation) {
                        failedPrewarmTakeovers.remove(key)
                        failedPrewarmTakeoverGenerations.remove(key)
                    }
                    failedPrewarmTakeoverResults[key]?.let { result ->
                        immediate = result
                        return@withLock null
                    }
                    failedPrewarmTakeovers[key]
                        ?: prewarmInFlight[key]?.takeIf { prewarmInFlightGenerations[key] === generation }
                        ?: prewarmMutexTakeover(key, generation, fetch).also { candidate ->
                            failedPrewarmTakeovers[key] = candidate
                            failedPrewarmTakeoverGenerations[key] = generation
                            prewarmReadyGenerations.remove(key)
                            prewarmInFlight.remove(key)
                            prewarmInFlightGenerations.remove(key)
                            created = candidate
                        }
                }
            val result =
                immediate
                    ?: run {
                        val shared = deferred ?: error("failed prewarm takeover lookup missing")
                        created?.start()
                        shared.await()
                    }
            if (freshnessLedger.isCurrent(generation)) return@coroutineScope result
        }
        error("failed prewarm takeover loop exited unexpectedly")
    }

    suspend fun recordCanonicalSuccess(
        generation: SharedThreadFreshnessGeneration,
        ownerScopeId: String
    ) {
        if (generation.ownerScopeId != ownerScopeId) return
        val key =
            sharedThreadOwnedPayloadCacheKey(
                ownerScopeId,
                generation.key.serverId,
                generation.key.threadId
            )
        prewarmMutex.withLock {
            freshnessLedger.runIfCurrent(generation) {
                prewarmFailures.remove(key)
                prewarmFailureGenerations.remove(key)
                failedPrewarmTakeoverResults.remove(key)
                failedPrewarmTakeoverResultGenerations.remove(key)
                // An explicit canonical retry is the manual escape from a failed
                // prewarm generation. Detach any older app-scope takeover so its
                // later completion cannot make that terminal failure sticky again.
                failedPrewarmTakeovers.remove(key)
                failedPrewarmTakeoverGenerations.remove(key)
            }
        }
    }

    private fun prewarmMutexTakeover(
        key: SharedThreadOwnedPayloadCacheKey,
        generation: SharedThreadFreshnessGeneration,
        fetch: suspend () -> DataResult<RaftThreadScreenPayload>
    ): Deferred<DataResult<RaftThreadScreenPayload>> =
        failedPrewarmTakeovers[key]
            ?.takeIf { failedPrewarmTakeoverGenerations[key] === generation }
            ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    val result = fetch()
                    prewarmMutex.withLock {
                        val currentJob = currentCoroutineContext()[Job]
                        if (
                            failedPrewarmTakeovers[key] === currentJob
                        ) {
                            freshnessLedger.runIfCurrent(generation) {
                                if (result is DataResult.Content) {
                                    commitEntry(key, SharedThreadPayloadEntry(result.data, generation))
                                    prewarmReadyGenerations[key] = generation
                                    prewarmFailures.remove(key)
                                    prewarmFailureGenerations.remove(key)
                                    failedPrewarmTakeoverResults.remove(key)
                                    failedPrewarmTakeoverResultGenerations.remove(key)
                                } else {
                                    failedPrewarmTakeoverResults[key] = result
                                    failedPrewarmTakeoverResultGenerations[key] = generation
                                }
                            }
                        }
                    }
                    result
                } finally {
                    val currentJob = currentCoroutineContext()[Job]
                    prewarmMutex.withLock {
                        if (failedPrewarmTakeovers[key] === currentJob) {
                            failedPrewarmTakeovers.remove(key)
                            failedPrewarmTakeoverGenerations.remove(key)
                        }
                    }
                }
            }

    private suspend fun awaitPrewarmStartRateLimit() {
        if (prewarmStartIntervalMillis <= 0L) return
        prewarmStartMutex.withLock {
            val elapsedMillis = lastPrewarmStartedAt?.elapsedNow()?.inWholeMilliseconds
            val remainingMillis =
                elapsedMillis
                    ?.let { elapsed -> (prewarmStartIntervalMillis - elapsed).coerceAtLeast(0L) }
                    ?: 0L
            if (remainingMillis > 0L) {
                delay(remainingMillis)
            }
            lastPrewarmStartedAt = prewarmStartTimeSource.markNow()
        }
    }

    internal fun clearForTest() {
        prewarmInFlight.values.forEach { it.cancel() }
        failedPrewarmTakeovers.values.forEach { it.cancel() }
        prewarmInFlight.clear()
        prewarmInFlightGenerations.clear()
        prewarmFetchStartedGenerations.clear()
        prewarmReadyGenerations.clear()
        prewarmFailures.clear()
        prewarmFailureGenerations.clear()
        failedPrewarmTakeovers.clear()
        failedPrewarmTakeoverGenerations.clear()
        failedPrewarmTakeoverResults.clear()
        failedPrewarmTakeoverResultGenerations.clear()
        lastPrewarmStartedAt = null
        synchronized(recencyLock) {
            payloads.value = emptyMap()
            recency.clear()
            resolvedAccessKeys.clear()
        }
        freshnessLedger.clearForTest()
    }
}

internal enum class SharedThreadPrewarmJoinOutcome(val diagnosticsName: String) {
    ConsumedReady("consumed_ready"),
    JoinedInFlight("joined_in_flight"),
    JoinTimedOut("join_timeout"),
    StoredFailure("stored_failure"),
    None("no_prewarm"),
}

internal data class SharedThreadPrewarmJoin(
    val result: DataResult<RaftThreadScreenPayload>?,
    val outcome: SharedThreadPrewarmJoinOutcome,
)

private data class SharedThreadPrewarmLookup(
    val generation: SharedThreadFreshnessGeneration,
    val cached: RaftThreadScreenPayload?,
    val inFlight: Deferred<DataResult<RaftThreadScreenPayload>>?,
    val failure: DataResult<RaftThreadScreenPayload>?
)

private const val SharedThreadTaskConversationRefCacheLimit = 256

/** A task conversation ref, per environment, account, server and conversation. */
internal data class SharedTaskConversationRefKey(
    val environmentId: String,
    val principalId: String,
    val serverId: String,
    val conversationChannelId: String,
)
