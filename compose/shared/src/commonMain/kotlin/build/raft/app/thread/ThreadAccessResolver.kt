package build.raft.app.thread

import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.ReplyCacheProvenance
import build.raft.app.complete
import build.raft.app.data.DataResult
import build.raft.app.data.MviStaleToken
import build.raft.app.get
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.present
import build.raft.app.resolveThreadRouteIdentity
import build.raft.app.thread.resolveThreadRouteIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Upper bound of a task conversation's late initial-access lookup (after the 1 s first pass). */
private const val ThreadInitialAccessLateLookupTimeoutMillis = 15_000L

/**
 * The open conversation's access for [SharedThreadStore]: the initial access lookup, the canonical access
 * of a superseded refresh, the reply-thread parent authority (directory hints and the bounded
 * fallback), the access retry and a local join / leave.
 *
 * It owns [initialAccessStateMutex]. Start, refresh and the superseded content passes in the store lock
 * that same Mutex instance through an accessor, at the same points as before.
 *
 * Its jobs launch on the store's scope, as before, and the store keeps their fields (start cancels them);
 * this class reads and writes them through accessors. Store state is read only through [storeState] (the
 * store's own flow, read-only here) and the getters, at each use; writes go through the store's `reduceState`.
 */
internal class ThreadAccessResolver(
    private val dataSource: SharedThreadDataSource,
    private val scope: CoroutineScope,
    private val diagnosticsLogger: ((String) -> Unit)?,
    private val initialAccessLookupDeadline: suspend () -> Unit,
    private val replyParentAuthorityFallbackDelay: suspend () -> Unit,
    private val storeState: StateFlow<SharedThreadState>,
    private val currentRouteStaleToken: () -> MviStaleToken,
    private val currentLastRoutePayload: () -> RaftThreadScreenPayload?,
    private val currentVisiblePayloadOwnerScopeId: () -> String?,
    private val getInitialAccessResolutionJob: () -> Job?,
    private val setInitialAccessResolutionJob: (Job?) -> Unit,
    private val getReplyParentAuthorityFallbackJob: () -> Job?,
    private val setReplyParentAuthorityFallbackJob: (Job?) -> Unit,
    private val getDeferredParentDirectoryHintJob: () -> Job?,
    private val setDeferredParentDirectoryHintJob: (Job?) -> Unit,
    private val reduceState: (transform: (SharedThreadState) -> SharedThreadState) -> Boolean,
    staleTokenStillCurrent: (
        staleToken: MviStaleToken,
        source: String,
        stage: String,
        routePayload: RaftThreadScreenPayload,
        currentPayload: RaftThreadScreenPayload
    ) -> Boolean,
    routeStillCurrent: (
        routePayload: RaftThreadScreenPayload,
        source: String,
        stage: String,
        currentPayload: RaftThreadScreenPayload
    ) -> Boolean,
    private val launchBackgroundHomeContextHydration: (routePayload: RaftThreadScreenPayload) -> Unit,
) {
    private val storeStaleTokenStillCurrent = staleTokenStillCurrent
    private val storeRouteStillCurrent = routeStillCurrent

    val initialAccessStateMutex = Mutex()

    /** The store's route stale token, read at each use. */
    private val routeStaleToken: MviStaleToken
        get() = currentRouteStaleToken()

    /** The store's last route payload, read at each use. */
    private val lastRoutePayload: RaftThreadScreenPayload?
        get() = currentLastRoutePayload()

    /** The store's visible payload owner scope, read at each use. */
    private val visiblePayloadOwnerScopeId: String?
        get() = currentVisiblePayloadOwnerScopeId()

    /** The store's field, read and written at each use. */
    private var initialAccessResolutionJob: Job?
        get() = getInitialAccessResolutionJob()
        set(value) {
            setInitialAccessResolutionJob(value)
        }

    /** The store's field, read and written at each use. */
    private var replyParentAuthorityFallbackJob: Job?
        get() = getReplyParentAuthorityFallbackJob()
        set(value) {
            setReplyParentAuthorityFallbackJob(value)
        }

    /** The store's field, read and written at each use. */
    private var deferredParentDirectoryHintJob: Job?
        get() = getDeferredParentDirectoryHintJob()
        set(value) {
            setDeferredParentDirectoryHintJob(value)
        }

    private fun staleTokenStillCurrent(
        staleToken: MviStaleToken,
        source: String,
        stage: String,
        routePayload: RaftThreadScreenPayload,
        currentPayload: RaftThreadScreenPayload = storeState.value.payload
    ): Boolean = storeStaleTokenStillCurrent(staleToken, source, stage, routePayload, currentPayload)

    private fun routeStillCurrent(
        routePayload: RaftThreadScreenPayload,
        source: String,
        stage: String,
        currentPayload: RaftThreadScreenPayload = storeState.value.payload
    ): Boolean = storeRouteStillCurrent(routePayload, source, stage, currentPayload)

    /**
     * task #243: race the lookup against an injectable deadline SIGNAL.
     *
     * `withTimeoutOrNull(1_000L)` tied the timeout branch to a real second of wall clock, which is
     * unreachable deterministically from a test - `commonTest` avoids
     * `kotlinx-coroutines-test`, so virtual time is not available here. Handing the
     * deadline in as a suspend function lets a test complete it explicitly (the same explicit-barrier
     * shape already used in SharedReplyThreadAccessIntegrationTest) while production keeps `delay`.
     *
     * The block still runs, so callers that count downstream requests keep observing them.
     */
    private suspend fun <T> raceInitialAccessLookupDeadline(block: suspend () -> T?): T? =
        coroutineScope {
            val outcome = CompletableDeferred<T?>()
            val work = launch {
                try {
                    outcome.complete(block())
                } catch (cancellation: CancellationException) {
                    // The body rethrows CancellationException on purpose (see the call site and
                    // initialConversationAccessResolution). A cancelled child does NOT fail its
                    // parent, so without this the exception would be swallowed, nothing would
                    // complete `outcome`, and the resolution would sit here until the deadline
                    // fired - turning "abandon the lookup" into "wait, then take the timeout
                    // branch" and running the whole projection with a null candidate. Hand it to
                    // the awaiter so it keeps escaping exactly as it did under withTimeoutOrNull.
                    outcome.completeExceptionally(cancellation)
                    throw cancellation
                }
            }
            val deadline = launch {
                initialAccessLookupDeadline()
                outcome.complete(null)
            }
            try {
                outcome.await()
            } finally {
                work.cancel()
                deadline.cancel()
            }
        }

    fun launchInitialAccessResolution(
        routePayload: RaftThreadScreenPayload,
        staleToken: MviStaleToken
    ) {
        initialAccessResolutionJob?.cancel()
        replyParentAuthorityFallbackJob?.cancel()
        replyParentAuthorityFallbackJob = null
        initialAccessResolutionJob =
            scope.launch {
                suspend fun applyResolution(
                    homeResolution: ThreadHomeContextResolution?,
                    late: Boolean,
                    lateProvenance: ReplyCacheProvenance? = null,
                ) {
                    if (homeResolution?.ownerCurrent == false) {
                        diagnosticsLogger?.invoke(
                            "thread_access_resolution_stale_drop " +
                                "threadId=${routePayload.sourceThreadId.orEmpty()} reason=owner_changed"
                        )
                        return
                    }
                    val candidate = homeResolution?.accessCandidate
                    // A late lookup only adds a positive candidate the deadline cut off; it never
                    // re-runs the terminal (no candidate) projection the timed-out pass already applied.
                    if (late && (candidate == null || !candidate.joined || !candidate.permissions.canSend)) return
                    val launchBackgroundHomeContext =
                        homeResolution?.requiresBackgroundHomeContextHydration
                            ?: (routePayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread)
                    initialAccessStateMutex.withLock {
                        val initialPayload = storeState.value.payload
                        if (
                            !staleTokenStillCurrent(
                                staleToken,
                                "thread_access_resolution",
                                "apply",
                                routePayload,
                                initialPayload
                            ) ||
                            !routeStillCurrent(routePayload, "thread_access_resolution", "apply", initialPayload)
                        ) {
                            return@withLock
                        }
                        fun commitResolution() {
                            var staleDropKind: String? = null
                            var staleDropPayload: RaftThreadScreenPayload? = null
                            reduceState { currentState ->
                                staleDropKind = null
                                staleDropPayload = null
                                val currentPayload = currentState.payload
                                if (!staleToken.isCurrent(routeStaleToken)) {
                                    staleDropKind = "stale_token"
                                    staleDropPayload = currentPayload
                                    return@reduceState currentState
                                }
                                if (!currentPayload.isUsableCachedPayloadForRoute(routePayload)) {
                                    staleDropKind = "route_changed"
                                    staleDropPayload = currentPayload
                                    return@reduceState currentState
                                }
                                // The late pass re-checks everything that may have changed while the ref was
                                // in flight: the session owner (fenced around commitResolution), the route (above),
                                // and any terminal answer.
                                if (
                                    late &&
                                    (
                                        !(
                                            currentState.accessResolution == ThreadAccessResolution.Loading ||
                                                (
                                                    currentState.accessResolution == ThreadAccessResolution.Resolved &&
                                                        !currentState.canonicalAccessResolved &&
                                                        !currentState.writeActionsEnabled
                                                )
                                        ) ||
                                            currentState.conversationAccessLost ||
                                            currentPayload.status == "error"
                                    )
                                ) {
                                    return@reduceState currentState
                                }
                                // If an early positive parent directory hint already unlocked this reply
                                // thread while the initial lookup timed out / returned null, keep that
                                // positive candidate instead of clobbering Writable back to Unknown.
                                // A non-null candidate from this lookup (positive OR authoritative
                                // negative) always takes precedence.
                                val effectiveCandidate =
                                    candidate
                                        ?: currentState.initialHomeAccessCandidate?.takeIf { existing ->
                                            currentPayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
                                                currentState.replyParentAuthority == ReplyParentAuthorityStatus.Writable &&
                                                existing.joined &&
                                                existing.permissions.canSend &&
                                                currentPayload.acceptsInitialConversationAccessCandidate(existing)
                                        }
                                val projectedPayload =
                                    currentPayload
                                        .let { payload ->
                                            homeResolution?.payload?.let { homePayload ->
                                                payload.withHomeContextProjectionFrom(homePayload)
                                            } ?: payload
                                        }
                                        .withInitialHomeConversationAccessFallbackUnlessCanonical(
                                            canonicalAccessResolved =
                                                currentState.canonicalAccessResolved || currentState.provisionalCanonicalAccess,
                                            candidate = effectiveCandidate,
                                        )
                                        .withReadOnlyReplyAccessWhenHomeCandidateUnavailable(
                                            homeLookupComplete = true,
                                            homeAccessCandidate = effectiveCandidate
                                        )
                                val nextResolution =
                                    when {
                                        currentState.accessResolution == ThreadAccessResolution.NoPermission ->
                                            ThreadAccessResolution.NoPermission
                                        currentState.accessResolution == ThreadAccessResolution.Error ->
                                            ThreadAccessResolution.Error
                                        currentState.accessResolution == ThreadAccessResolution.Resolved &&
                                            currentState.canonicalAccessResolved ->
                                            ThreadAccessResolution.Resolved
                                        projectedPayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
                                            currentState.canonicalAccessResolved &&
                                            (effectiveCandidate != null || projectedPayload.hasReadableCanonicalReplyContent()) ->
                                            ThreadAccessResolution.Resolved
                                        projectedPayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
                                            currentState.canonicalAccessResolved ->
                                            ThreadAccessResolution.Error
                                        currentState.canonicalAccessResolved ->
                                            ThreadAccessResolution.Resolved
                                        // A superseded canonical result already attested write authority.
                                        currentState.provisionalCanonicalAccess &&
                                            (currentPayload.status == "content" || currentPayload.status == "empty") ->
                                            ThreadAccessResolution.Resolved
                                        // Task #1141 + reply-thread parity: a POSITIVE access candidate
                                        // (joined + canSend) is the same write authority the canonical fetch
                                        // would confirm — for Channel/DM it is the conversation's own row, and
                                        // for a ReplyThread it is the exact parent row, which is the only write
                                        // authority the reply surface ever consults
                                        // (hasWriteAuthorityForAccess + withInitialHomeConversationAccessFallback).
                                        // Accept it for renderable cached content just as it is accepted for
                                        // "empty", so the composer mounts when the directory answers instead of
                                        // after the first network refresh. For reply threads the canonical reply
                                        // stream never attests parent write authority anyway, so waiting for it
                                        // added latency without adding information. A negative candidate (stale
                                        // unjoined row) keeps waiting for the canonical fetch, which must still
                                        // win; a later canonical NoPermission still closes access.
                                        effectiveCandidate != null &&
                                            effectiveCandidate.joined &&
                                            effectiveCandidate.permissions.canSend &&
                                            currentPayload.status == "content" ->
                                            ThreadAccessResolution.Resolved
                                        effectiveCandidate != null && currentPayload.status == "empty" ->
                                            ThreadAccessResolution.Resolved
                                        // A non-reply page whose content is already renderable (kept access or
                                        // unknown task parent detail) must not regress to Loading when the Home
                                        // lookup finds no row. A negative candidate still waits for canonical.
                                        effectiveCandidate == null &&
                                            currentState.accessResolution == ThreadAccessResolution.Resolved &&
                                            !currentState.canonicalAccessResolved &&
                                            projectedPayload.surfaceKind != RaftThreadSurfaceKind.ReplyThread &&
                                            currentPayload.status in ThreadResolvedContentStatuses ->
                                            ThreadAccessResolution.Resolved
                                        currentPayload.status == "error" ->
                                            ThreadAccessResolution.Error
                                        currentPayload.status == "empty" ->
                                            ThreadAccessResolution.Error
                                        else -> ThreadAccessResolution.Loading
                                    }
                                val authorityProjectedPayload =
                                    projectedPayload.withTerminalAccessWriteAuthorityClosed(nextResolution)
                                val nextWriteActions =
                                    if (
                                        !currentState.canonicalAccessResolved &&
                                        !currentState.provisionalCanonicalAccess &&
                                        authorityProjectedPayload.surfaceKind != RaftThreadSurfaceKind.ReplyThread &&
                                        effectiveCandidate == null
                                    ) {
                                        currentState.writeActionsEnabled
                                    } else {
                                        authorityProjectedPayload.hasWriteAuthorityForAccess(
                                            accessResolution = nextResolution,
                                            homeLookupComplete = true,
                                            homeAccessCandidate = effectiveCandidate
                                        )
                                    }
                                currentState.copy(
                                    payload = authorityProjectedPayload,
                                    accessResolution = nextResolution,
                                    initialHomeAccessLookupComplete = true,
                                    initialHomeAccessCandidate = effectiveCandidate,
                                    replyParentAuthority =
                                        resolveReplyParentAuthorityStatus(
                                            accessResolution = nextResolution,
                                            payload = authorityProjectedPayload,
                                            candidate = effectiveCandidate,
                                        ),
                                    writeActionsEnabled = nextWriteActions
                                )
                            }
                            when (staleDropKind) {
                                "stale_token" ->
                                    staleTokenStillCurrent(
                                        staleToken,
                                        "thread_access_resolution",
                                        "apply",
                                        routePayload,
                                        staleDropPayload ?: storeState.value.payload
                                    )
                                "route_changed" ->
                                    routeStillCurrent(
                                        routePayload,
                                        "thread_access_resolution",
                                        "apply",
                                        staleDropPayload ?: storeState.value.payload
                                    )
                            }
                        }
                        if (late) {
                            val provenance = lateProvenance ?: return@withLock
                            dataSource.withCurrentReplyCacheProvenanceForLocalSend(provenance) {
                                commitResolution()
                            }
                        } else {
                            commitResolution()
                        }
                    }
                    if (
                        !late &&
                        launchBackgroundHomeContext &&
                        staleTokenStillCurrent(
                            staleToken,
                            "thread_access_home_context",
                            "launch",
                            routePayload,
                        ) &&
                        routeStillCurrent(
                            routePayload,
                            "thread_access_home_context",
                            "launch",
                        )
                    ) {
                        launchBackgroundHomeContextHydration(routePayload)
                    }
                }
                suspend fun lookup(): ThreadHomeContextResolution? =
                    try {
                        dataSource.initialConversationAccessResolution(routePayload)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        null
                    }
                if (!routePayload.isTaskConversationSurface()) {
                    applyResolution(raceInitialAccessLookupDeadline { lookup() }, late = false)
                    if (routePayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
                        launchReplyParentAuthorityFallback(routePayload, staleToken)
                    }
                    return@launch
                }
                // A task conversation's lookup waits on a `GET /channels/:id` ref fetch. The deadline
                // only bounds how long the first pass waits; it must not cancel the lookup (that
                // left access Loading although the ref landed ~1 s later, and the composer waited
                // for the whole canonical load). The lookup is a child of this job, so a newer
                // resolution or a route change still cancels it.
                val lookupProvenance = dataSource.captureReplyCacheProducerContext()?.provenance
                val pendingLookup = async { lookup() }
                val homeResolution = raceInitialAccessLookupDeadline { pendingLookup.await() }
                applyResolution(homeResolution, late = false)
                if (homeResolution == null) {
                    // Bounded: a ref that has not answered by then is left to the canonical load.
                    val lateResolution =
                        withTimeoutOrNull(ThreadInitialAccessLateLookupTimeoutMillis) { pendingLookup.await() }
                    pendingLookup.cancel()
                    diagnosticsLogger?.invoke(
                        "thread_initial_task_access_late threadId=${routePayload.sourceThreadId.orEmpty()} " +
                            "candidate=${lateResolution?.accessCandidate != null}"
                    )
                    applyResolution(lateResolution, late = true, lateProvenance = lookupProvenance)
                }
            }
    }

    /**
     * The first canonical result of a refresh whose freshness generation moved while it was
     * fetched: the data source refetches it, so its content is not final and is NOT shown. Its
     * conversation access is server truth all the same (realtime message traffic moves the
     * generation, not the conversation's write authority), so a positive answer records canonical
     * access now instead of after the refetch rounds settle. A negative answer waits for the final
     * result, which still records itself; a later NoPermission still closes access.
     */
    suspend fun applySupersededCanonicalAccess(
        routePayload: RaftThreadScreenPayload,
        staleToken: MviStaleToken,
        refreshProvenance: ReplyCacheProvenance,
        result: DataResult<RaftThreadScreenPayload>,
    ) {
        val canonical = (result as? DataResult.Content)?.data ?: return
        // A reply thread's canonical stream never attests its parent's write authority.
        if (routePayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread) return
        if (canonical.surfaceKind == RaftThreadSurfaceKind.ReplyThread) return
        if (!canonical.canonicalAccessKnown || !canonical.joined || !canonical.canSend) return
        initialAccessStateMutex.withLock {
            dataSource.withCurrentReplyCacheProvenanceForLocalSend(refreshProvenance) {
                var staleDropKind: String? = null
                var staleDropPayload: RaftThreadScreenPayload? = null
                var appliedResolution: ThreadAccessResolution? = null
                reduceState { currentState ->
                    staleDropKind = null
                    staleDropPayload = null
                    appliedResolution = null
                    val currentPayload = currentState.payload
                    if (!staleToken.isCurrent(routeStaleToken)) {
                        staleDropKind = "stale_token"
                        staleDropPayload = currentPayload
                        return@reduceState currentState
                    }
                    if (!currentPayload.isUsableCachedPayloadForRoute(routePayload)) {
                        staleDropKind = "route_changed"
                        staleDropPayload = currentPayload
                        return@reduceState currentState
                    }
                    if (
                        currentState.canonicalAccessResolved ||
                        currentState.provisionalCanonicalAccess ||
                        currentState.conversationAccessLost ||
                        currentState.accessResolution == ThreadAccessResolution.NoPermission ||
                        currentState.accessResolution == ThreadAccessResolution.Error
                    ) {
                        return@reduceState currentState
                    }
                    val projectedPayload = currentPayload.withCanonicalConversationAccessFrom(canonical)
                    // Like a positive Home candidate: it resolves the footer over renderable content; the
                    // final result still records itself (canonicalAccessResolved stays false).
                    val nextResolution =
                        if (currentPayload.status == "content" || currentPayload.status == "empty") {
                            ThreadAccessResolution.Resolved
                        } else {
                            currentState.accessResolution
                        }
                    appliedResolution = nextResolution
                    currentState.copy(
                        payload = projectedPayload,
                        accessResolution = nextResolution,
                        provisionalCanonicalAccess = true,
                        writeActionsEnabled =
                            projectedPayload.hasWriteAuthorityForAccess(
                                accessResolution = nextResolution,
                                homeLookupComplete = currentState.initialHomeAccessLookupComplete,
                                homeAccessCandidate = currentState.initialHomeAccessCandidate,
                            ),
                    )
                }
                when (staleDropKind) {
                    "stale_token" ->
                        staleTokenStillCurrent(
                            staleToken,
                            "thread_superseded_access",
                            "apply",
                            routePayload,
                            staleDropPayload ?: storeState.value.payload
                        )
                    "route_changed" ->
                        routeStillCurrent(
                            routePayload,
                            "thread_superseded_access",
                            "apply",
                            staleDropPayload ?: storeState.value.payload
                        )
                }
                appliedResolution?.let { nextResolution ->
                    diagnosticsLogger?.invoke(
                        "thread_superseded_canonical_access threadId=${routePayload.sourceThreadId.orEmpty()} " +
                            "accessResolution=$nextResolution"
                    )
                }
            }
        }
    }

    /**
     * Event-driven re-evaluation of reply-thread parent authority (task #228
     * successor direction A). The Home directory is the same authority source
     * the initial lookup consults; when its row for the exact parent appears
     * or changes while the thread is open, this applies it in place - no
     * retry loop, no network. Only upgrades [ReplyParentAuthorityStatus.Unknown]
     * or recomputes a candidate-derived [ReplyParentAuthorityStatus.Denied];
     * terminal NoPermission/Error and non-reply surfaces are untouched.
     * A network-authoritative hint with `present = false` attests the parent
     * channel was deleted and lands in [ReplyParentAuthorityStatus.ParentDeleted];
     * a later `present = true` hint (recreated/restored row) recomputes from there.
     */
    suspend fun applyParentDirectoryHint(hint: SharedThreadIntent.ApplyParentDirectoryHint) {
        val state = storeState.value
        val payload = state.payload
        if (payload.surfaceKind != RaftThreadSurfaceKind.ReplyThread) return
        if (state.accessResolution == ThreadAccessResolution.NoPermission ||
            state.accessResolution == ThreadAccessResolution.Error
        ) {
            return
        }
        val earlyPositiveHint =
            hint.present &&
                hint.joined &&
                hint.canSend &&
                (!state.canonicalAccessResolved || !state.initialHomeAccessLookupComplete)
        if ((!state.canonicalAccessResolved || !state.initialHomeAccessLookupComplete) && !earlyPositiveHint) {
            deferParentDirectoryHint(hint)
            return
        }
        val parentChannelId = payload.parentChannelId?.takeIf { it.isNotBlank() } ?: return
        if (parentChannelId != hint.parentChannelId) return
        val parentMessageId =
            payload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: payload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: return
        if (parentMessageId != hint.parentMessageId) return
        val routeIdentity = payload.resolveThreadRouteIdentity()
        if (routeIdentity.parentKey != "$parentChannelId:$parentMessageId") return
        if (!hint.present) {
            if (!hint.authoritative) {
                // Row absent in a non-authoritative snapshot: nothing new to
                // learn - the state stays Unknown/Resolving.
                return
            }
            initialAccessStateMutex.withLock {
                var appliedThreadId: String? = null
                reduceState { current ->
                    appliedThreadId = null
                    // Re-check under the lock and atomic CAS: a route/account switch may have
                    // landed while the hint was queued on the dispatcher.
                    if (current.payload.parentChannelId != parentChannelId ||
                        current.payload.sourceThreadId != payload.sourceThreadId ||
                        current.payload.channelArchived ||
                        current.accessResolution == ThreadAccessResolution.NoPermission ||
                        current.accessResolution == ThreadAccessResolution.Error
                    ) {
                        return@reduceState current
                    }
                    appliedThreadId = current.payload.sourceThreadId.orEmpty()
                    // A network-authoritative directory read that no longer lists
                    // the parent means the channel was deleted.
                    current.copy(
                        replyParentAuthority = ReplyParentAuthorityStatus.ParentDeleted,
                        writeActionsEnabled = false,
                    )
                }
                appliedThreadId?.let { threadId ->
                    diagnosticsLogger?.invoke(
                        "thread_parent_authority_hint_applied threadId=$threadId " +
                            "authority=${ReplyParentAuthorityStatus.ParentDeleted} present=false authoritative=true"
                    )
                }
            }
            return
        }
        // A non-authoritative snapshot (durable identity cache or refresh-pending) must never
        // downgrade a writable reply thread or flash a Join CTA before a live network directory
        // confirms membership (mirrors launchInitialAccessResolution's negative-candidate rule).
        if (!hint.authoritative && (!hint.joined || !hint.canSend)) {
            return
        }
        if (state.replyParentAuthority == ReplyParentAuthorityStatus.Writable &&
            payload.joined == hint.joined && payload.canSend == hint.canSend &&
            state.accessResolution == ThreadAccessResolution.Resolved &&
            state.writeActionsEnabled
        ) {
            return
        }
        val conversationKind =
            routeIdentity.parentConversationKind ?: ConversationKind.Channel
        val candidate =
            ConversationAccessCandidate(
                ids = setOf(parentChannelId),
                conversationKind = conversationKind,
                surfaceKind = "channel",
                joined = hint.joined,
                archived = false,
                permissions =
                    ConversationPermissions(
                        canRead = true,
                        canSend = hint.canSend,
                        canAttach = hint.canSend,
                        canCreateTask = hint.canSend,
                    ),
            )
        if (!payload.acceptsInitialConversationAccessCandidate(candidate)) return
        initialAccessStateMutex.withLock {
            var appliedThreadId: String? = null
            var appliedAuthority: ReplyParentAuthorityStatus? = null
            reduceState { current ->
                appliedThreadId = null
                appliedAuthority = null
                // Re-check under the lock and atomic CAS: a route/account switch may have
                // landed while the hint was queued on the dispatcher.
                if (current.payload.parentChannelId != parentChannelId ||
                    current.payload.sourceThreadId != payload.sourceThreadId ||
                    current.accessResolution == ThreadAccessResolution.NoPermission ||
                    current.accessResolution == ThreadAccessResolution.Error ||
                    (earlyPositiveHint && current.payload.channelArchived)
                ) {
                    return@reduceState current
                }
                val nextResolution =
                    if (
                        hint.joined &&
                        hint.canSend &&
                        current.accessResolution == ThreadAccessResolution.Loading &&
                        current.payload.status in ThreadResolvedContentStatuses
                    ) {
                        ThreadAccessResolution.Resolved
                    } else {
                        current.accessResolution
                    }
                val nextLookupComplete =
                    current.initialHomeAccessLookupComplete || (hint.joined && hint.canSend)
                val projected =
                    current.payload
                        .withInitialHomeConversationAccessFallbackUnlessCanonical(
                            canonicalAccessResolved = current.canonicalAccessResolved,
                            candidate = candidate,
                        )
                        .withTerminalAccessWriteAuthorityClosed(nextResolution)
                val nextAuthority =
                    if (hint.joined && hint.canSend) {
                        ReplyParentAuthorityStatus.Writable
                    } else {
                        ReplyParentAuthorityStatus.Denied
                    }
                val nextWriteActions =
                    projected.hasWriteAuthorityForAccess(
                        accessResolution = nextResolution,
                        homeLookupComplete = nextLookupComplete,
                        homeAccessCandidate = candidate
                    )
                appliedThreadId = current.payload.sourceThreadId.orEmpty()
                appliedAuthority = nextAuthority
                current.copy(
                    payload = projected,
                    accessResolution = nextResolution,
                    initialHomeAccessLookupComplete = nextLookupComplete,
                    initialHomeAccessCandidate = candidate,
                    replyParentAuthority = nextAuthority,
                    writeActionsEnabled = nextWriteActions,
                )
            }
            val authority = appliedAuthority
            if (authority != null) {
                diagnosticsLogger?.invoke(
                    "thread_parent_authority_hint_applied threadId=${appliedThreadId.orEmpty()} " +
                        "authority=$authority joined=${hint.joined} canSend=${hint.canSend}"
                )
            }
        }
    }

    /**
     * A positive parent row that arrived before the canonical reply load and the initial access
     * pass had both run used to be dropped, and the page only sends it again when the directory or
     * the known Lounges change - which for a Lounge (in no list) may be never, leaving the thread
     * on "checking permissions" for good. Keep the latest such hint and apply it once both passes
     * ran, only while they left the parent authority undetermined (a decided authority stays
     * theirs). An absent-row hint is not kept: a parent deletion is only read from a hint that
     * arrives after the passes, as before.
     */
    private fun deferParentDirectoryHint(hint: SharedThreadIntent.ApplyParentDirectoryHint) {
        if (!hint.present) return
        if (!hint.authoritative && (!hint.joined || !hint.canSend)) return
        val payload = storeState.value.payload
        if (payload.parentChannelId != hint.parentChannelId) return
        val parentMessageId =
            payload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: payload.originParentMessageId?.takeIf { it.isNotBlank() }
        if (parentMessageId != hint.parentMessageId) return
        val sourceThreadId = payload.sourceThreadId
        diagnosticsLogger?.invoke(
            "thread_parent_authority_hint_deferred threadId=${sourceThreadId.orEmpty()} " +
                "joined=${hint.joined} canSend=${hint.canSend}"
        )
        deferredParentDirectoryHintJob?.cancel()
        deferredParentDirectoryHintJob =
            scope.launch {
                val ready =
                    storeState.first { state ->
                        state.payload.sourceThreadId != sourceThreadId ||
                            state.payload.parentChannelId != hint.parentChannelId ||
                            state.accessResolution == ThreadAccessResolution.NoPermission ||
                            state.accessResolution == ThreadAccessResolution.Error ||
                            (state.canonicalAccessResolved && state.initialHomeAccessLookupComplete)
                    }
                if (ready.payload.sourceThreadId != sourceThreadId) return@launch
                if (!ready.replyParentAuthority.isUndeterminedReplyParentAuthority()) return@launch
                diagnosticsLogger?.invoke(
                    "thread_parent_authority_hint_replayed threadId=${sourceThreadId.orEmpty()} " +
                        "authority=${ready.replyParentAuthority}"
                )
                applyParentDirectoryHint(hint)
            }
    }

    /**
     * Bounded fallback (fb artin / tygg, 1.23): a reply thread whose parent write authority is
     * still undetermined [replyParentAuthorityFallbackDelay] after the initial access pass reads
     * the parent itself instead of waiting forever for a directory row. Runs once per access
     * pass; a decided authority, a terminal access answer or a route change ends it. The answer
     * goes through [applyParentDirectoryHint], so it obeys the same route and terminal fences
     * (and waits for the canonical reply load if that has not landed yet).
     */
    private fun launchReplyParentAuthorityFallback(
        routePayload: RaftThreadScreenPayload,
        staleToken: MviStaleToken,
    ) {
        replyParentAuthorityFallbackJob?.cancel()
        replyParentAuthorityFallbackJob =
            scope.launch {
                replyParentAuthorityFallbackDelay()
                val threadId = routePayload.sourceThreadId.orEmpty()
                val waited = storeState.value
                if (
                    !staleTokenStillCurrent(staleToken, "thread_parent_authority_fallback", "start", routePayload) ||
                    !routeStillCurrent(routePayload, "thread_parent_authority_fallback", "start", waited.payload)
                ) {
                    return@launch
                }
                // ParentDeleted too: a Lounge this client did not know yet is absent from every
                // directory snapshot, which reads as "deleted"; only a Lounge answer may lift it.
                val waitedParentDeleted = waited.replyParentAuthority == ReplyParentAuthorityStatus.ParentDeleted
                if (!waited.replyParentAuthority.isUndeterminedReplyParentAuthority() && !waitedParentDeleted) {
                    return@launch
                }
                if (
                    waited.accessResolution == ThreadAccessResolution.NoPermission ||
                    waited.accessResolution == ThreadAccessResolution.Error ||
                    waited.conversationAccessLost
                ) {
                    return@launch
                }
                val reason =
                    when (waited.replyParentAuthority) {
                        ReplyParentAuthorityStatus.Unknown -> "authority_unknown_timeout"
                        ReplyParentAuthorityStatus.ParentDeleted -> "authority_parent_deleted_timeout"
                        else -> "authority_resolving_timeout"
                    }
                diagnosticsLogger?.invoke(
                    "thread_parent_authority_fallback_start threadId=$threadId reason=$reason " +
                        "canonical=${waited.canonicalAccessResolved} lookupComplete=${waited.initialHomeAccessLookupComplete}"
                )
                val result =
                    try {
                        dataSource.replyParentAccessFallback(waited.payload)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (throwable: Throwable) {
                        ReplyParentAccessFallbackResult.Failed(
                            "exception_${throwable::class.simpleName.orEmpty()}"
                        )
                    }
                when (result) {
                    is ReplyParentAccessFallbackResult.Resolved -> {
                        val candidate = result.candidate
                        if (waitedParentDeleted && !result.lounge) {
                            // An ordinary parent keeps what its directory said.
                            diagnosticsLogger?.invoke(
                                "thread_parent_authority_fallback_result threadId=$threadId reason=$reason " +
                                    "result=ignored_not_lounge"
                            )
                            return@launch
                        }
                        diagnosticsLogger?.invoke(
                            "thread_parent_authority_fallback_result threadId=$threadId reason=$reason " +
                                "result=resolved lounge=${result.lounge} joined=${candidate.joined} " +
                                "canSend=${candidate.permissions.canSend}"
                        )
                        val parentChannelId = waited.payload.parentChannelId?.takeIf { it.isNotBlank() } ?: return@launch
                        val parentMessageId =
                            waited.payload.parentMessageId?.takeIf { it.isNotBlank() }
                                ?: waited.payload.originParentMessageId?.takeIf { it.isNotBlank() }
                                ?: return@launch
                        applyParentDirectoryHint(
                            SharedThreadIntent.ApplyParentDirectoryHint(
                                parentChannelId = parentChannelId,
                                parentMessageId = parentMessageId,
                                present = true,
                                joined = candidate.joined,
                                canSend = candidate.permissions.canSend && !candidate.archived,
                                authoritative = true,
                            )
                        )
                    }
                    is ReplyParentAccessFallbackResult.Failed ->
                        diagnosticsLogger?.invoke(
                            "thread_parent_authority_fallback_result threadId=$threadId reason=$reason " +
                                "result=failed code=${result.reason}"
                        )
                    is ReplyParentAccessFallbackResult.Unavailable ->
                        diagnosticsLogger?.invoke(
                            "thread_parent_authority_fallback_result threadId=$threadId reason=$reason " +
                                "result=unavailable code=${result.reason}"
                        )
                }
            }
    }

    suspend fun retryAccessResolution() {
        val routePayload = lastRoutePayload ?: storeState.value.payload
        initialAccessStateMutex.withLock {
            var restartedCanonical: Boolean? = null
            reduceState { currentState ->
                restartedCanonical = null
                if (
                    currentState.accessResolution != ThreadAccessResolution.Error ||
                    !currentState.canonicalAccessResolved
                ) {
                    return@reduceState currentState
                }
                restartedCanonical = currentState.canonicalAccessResolved
                currentState.copy(
                    accessResolution = ThreadAccessResolution.Loading,
                    initialHomeAccessLookupComplete = false,
                    initialHomeAccessCandidate = null
                )
            }
            val canonical = restartedCanonical ?: return
            diagnosticsLogger?.invoke(
                "thread_access_retry_start threadId=${routePayload.sourceThreadId.orEmpty()} " +
                    "canonicalAccessResolved=$canonical"
            )
        }
        launchInitialAccessResolution(routePayload, routeStaleToken)
    }

    suspend fun applyLocalChannelMembershipChange(channelId: String, joined: Boolean) {
        var payloadToCache: RaftThreadScreenPayload? = null
        initialAccessStateMutex.withLock {
            var candidatePayload: RaftThreadScreenPayload? = null
            val emitted =
                reduceState { current ->
                    candidatePayload = null
                    if (current.payload.joinChannelTargetId()?.value != channelId) return@reduceState current
                    if (current.accessResolution == ThreadAccessResolution.NoPermission ||
                        current.accessResolution == ThreadAccessResolution.Error
                    ) {
                        return@reduceState current
                    }
                    val nextPayload =
                        if (joined) current.payload.withChannelJoined(channelId) else current.payload.withChannelLeft(channelId)
                    val nextCandidate =
                        updatedMembershipAccessCandidate(
                            payload = nextPayload,
                            existingCandidate = current.initialHomeAccessCandidate,
                            channelId = channelId,
                            joined = joined,
                        )
                    val nextAuthority =
                        if (nextPayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
                            if (joined) ReplyParentAuthorityStatus.Writable else ReplyParentAuthorityStatus.Denied
                        } else {
                            current.replyParentAuthority
                        }
                    val nextAccessResolution =
                        if (joined &&
                            current.accessResolution == ThreadAccessResolution.Loading &&
                            nextPayload.status in ThreadResolvedContentStatuses
                        ) {
                            ThreadAccessResolution.Resolved
                        } else {
                            current.accessResolution
                        }
                    val nextCanonicalAccessResolved =
                        current.canonicalAccessResolved ||
                            (joined && nextPayload.surfaceKind != RaftThreadSurfaceKind.ReplyThread)
                    val nextLookupComplete = current.initialHomeAccessLookupComplete || nextCandidate != null
                    val nextWriteActions =
                        nextPayload.hasWriteAuthorityForAccess(
                            accessResolution = nextAccessResolution,
                            homeLookupComplete = nextLookupComplete,
                            homeAccessCandidate = nextCandidate,
                        )
                    candidatePayload = nextPayload
                    current.copy(
                        payload = nextPayload,
                        accessResolution = nextAccessResolution,
                        canonicalAccessResolved = nextCanonicalAccessResolved,
                        initialHomeAccessLookupComplete = nextLookupComplete,
                        initialHomeAccessCandidate = nextCandidate,
                        replyParentAuthority = nextAuthority,
                        writeActionsEnabled = nextWriteActions,
                    )
                }
            val nextPayload = candidatePayload
            if (emitted && nextPayload != null && !nextPayload.durableLastKnown) {
                payloadToCache = nextPayload
            }
        }
        payloadToCache?.let { cached ->
            dataSource.cachePayload(
                cached,
                ownerScopeId = visiblePayloadOwnerScopeId.orEmpty(),
            )
        }
    }
}
