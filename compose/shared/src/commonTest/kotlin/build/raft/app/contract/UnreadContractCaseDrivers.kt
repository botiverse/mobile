package build.raft.app.contract

import build.raft.app.HomeConversationBadgeInput
import build.raft.app.HomeSidebarBadgeTone
import build.raft.app.SharedThreadIntent
import build.raft.app.SharedThreadStore
import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayloadForRoute
import build.raft.app.TestThreadEnvironmentId
import build.raft.app.data.ChannelUnreadSnapshot
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.EMPTY_CHANNEL_UNREAD_SUMMARY_BODY
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeConversationRow
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.MarkUnreadOutcome
import build.raft.app.data.RaftHomeApi
import build.raft.app.data.RaftReadToggleNotice
import build.raft.app.data.ReadReceiptCoordinator
import build.raft.app.data.ReadReceiptOwner
import build.raft.app.data.ReadReceiptRepository
import build.raft.app.data.ReadReceiptResult
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeDataSource
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.ScreenError
import build.raft.app.data.parseMarkConversationUnreadResult
import build.raft.app.data.SharedHomeStore
import build.raft.app.data.SharedActivityIntent
import build.raft.app.data.SharedActivityStore
import build.raft.app.data.FakeInboxRepository
import build.raft.app.data.activityStore
import build.raft.app.data.threadItem
import build.raft.app.data.snapshot as activitySnapshot
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.data.markUnreadOutcome
import build.raft.app.data.sampleSession
import build.raft.app.homeChannelRowMarks
import build.raft.app.homeConversationBadgeProjection
import build.raft.app.homeConversationRowTone
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import build.raft.designsystem.components.RaftRowTone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession as threadSampleSession

/**
 * App-side policy for the vendored unread contract cases, beside the case's own `knownGaps`.
 *
 * An agreed case the app currently disagrees with, where slock lists no `app` gap: skipped with the
 * reason (product code is not changed by the loader PR).
 */
internal val AppUnreadKnownGaps: Map<String, String> =
    mapOf(
        "R2-optimistic-badge" to
            "the optimistic 1 stays after a counted answer (SharedHomeStore.applyMarkUnreadOutcome keeps the row " +
            "on Counted); the answer's count arrives only with the next unread summary",
        "R3-hold-until-leave" to
            "the thread-menu answer alone leaves the thread read in the app's unread state (Activity/Home learn " +
            "the mark only from the read_state:updated that follows, which this case does not script)",
    )

/** Agreed cases no driver below can run yet, and why. A listed case that becomes drivable fails. */
internal val AppUnreadCannotDriveYet: Map<String, String> =
    mapOf(
        "R7-jump-lands-on-paywall" to
            "no driver for jumpToUnreadThread/jumpLands yet (the jump decision lives in the channel page's " +
            "unread-thread jump flow, not in a store this harness drives)",
    )

/** One way the app can run a case: [decline] says why it cannot (null = it can). */
internal class UnreadCaseDriver(
    val name: String,
    val decline: (UnreadContractCase) -> String?,
    val run: suspend TestScope.(UnreadContractCase) -> List<String>,
)

internal sealed interface UnreadCasePlan {
    data class Run(val driver: UnreadCaseDriver) : UnreadCasePlan

    /** [driver] is set when the app could run it anyway, so the report can tell whether the gap still holds. */
    data class Skip(val reason: String, val driver: UnreadCaseDriver? = null) : UnreadCasePlan

    /** The app-side lists are wrong for this case. */
    data class Misconfigured(val reason: String) : UnreadCasePlan
}

internal val UnreadCaseDrivers: List<UnreadCaseDriver> =
    listOf(markUnreadAnswerDriver(), channelRowDriver(), dmRowDriver(), homeRowMarkUnreadDriver(), threadMenuDriver())

internal fun planUnreadCase(case: UnreadContractCase): UnreadCasePlan {
    if (case.status == "pending-product-decision") {
        return UnreadCasePlan.Skip("pending product decision: ${case.decision?.question} (owner ${case.decision?.owner})")
    }
    if (case.status != "agreed") return UnreadCasePlan.Skip("status '${case.status}' is not one this loader knows")
    val misses = mutableListOf<String>()
    val driver =
        UnreadCaseDrivers.firstOrNull { candidate ->
            val reason = candidate.decline(case)
            if (reason != null) misses += "${candidate.name}: $reason"
            reason == null
        }
    case.knownGaps.firstOrNull { it.client == "app" }?.let { gap ->
        return UnreadCasePlan.Skip("app gap: ${gap.reason}", driver)
    }
    AppUnreadKnownGaps[case.id]?.let { reason -> return UnreadCasePlan.Skip("app gap: $reason", driver) }
    val cannotDrive = AppUnreadCannotDriveYet[case.id]
    return when {
        driver != null && cannotDrive != null ->
            UnreadCasePlan.Misconfigured("${case.id} is drivable by ${driver.name} now: remove it from AppUnreadCannotDriveYet")
        driver != null -> UnreadCasePlan.Run(driver)
        cannotDrive != null -> UnreadCasePlan.Skip("app cannot drive it yet: $cannotDrive")
        else ->
            UnreadCasePlan.Misconfigured(
                "${case.id}: no app driver (${misses.joinToString("; ")}); drive it or list it in AppUnreadCannotDriveYet"
            )
    }
}

// ---- R1: what a Mark as Unread answer means -------------------------------------------------------

/**
 * `markUnread` then a 200 answer, asserting only `outcome`: the answer is read the way the Activity
 * row, Home and the thread page read it ([parseMarkConversationUnreadResult] + [markUnreadOutcome]).
 */
private fun markUnreadAnswerDriver() =
    UnreadCaseDriver(
        name = "answer",
        decline = { case ->
            val answer = case.steps.getOrNull(1) as? UnreadCaseStep.ServerResponds
            when {
                case.steps.size != 2 || case.steps[0] !is UnreadCaseStep.MarkUnread || answer == null ->
                    "not markUnread → answer"
                answer.status != 200 -> "a ${answer.status} answer needs a store"
                case.checkpoints.isNotEmpty() || case.assertsRequests || case.assertedStateKeys != setOf("outcome") ->
                    "asserts more than the outcome"
                else -> null
            }
        },
        run = { case ->
            val answer = case.steps[1] as UnreadCaseStep.ServerResponds
            val result =
                parseMarkConversationUnreadResult(
                    answer.body?.toString().orEmpty(),
                    ChannelId(case.given.conversation.id),
                )
            val actual = result?.let { outcomeElement(DataResult.Content(it)) } ?: JsonPrimitive("unparsed")
            listOfNotNull(unreadCaseStateMismatch("outcome", case.expect!!.state!!.getValue("outcome"), actual))
        },
    )

// ---- R9–R11, S1: how a Home row draws unread ------------------------------------------------------

private fun UnreadContractCase.rowDisplayDecline(kind: UnreadCaseConversationKind): String? =
    when {
        given.row == null || steps.isNotEmpty() -> "not a row display case"
        given.conversation.kind != kind -> "not a ${kind.name.lowercase()} row"
        assertsRequests || checkpoints.isNotEmpty() || assertedStateKeys != setOf("rowDisplay") -> "asserts more than rowDisplay"
        else -> null
    }

/** A channel row: [homeChannelRowMarks] and [homeConversationRowTone], as HomeSidebarChannelRow draws them. */
private fun channelRowDriver() =
    UnreadCaseDriver(
        name = "channelRow",
        decline = { case ->
            case.rowDisplayDecline(UnreadCaseConversationKind.Channel)
                ?: "hasNew has no input in homeChannelRowMarks".takeIf { case.given.row?.hasNew == true }
        },
        run = { case ->
            val row = case.given.row!!
            val marks =
                homeChannelRowMarks(
                    unreadCount = row.unread,
                    followedThreadUnreadCount = row.threads,
                    mentioned = row.mention == true,
                    joined = row.joined,
                    muted = row.muted,
                    hasDraft = false,
                    markedUnread = row.markedUnread,
                )
            val quietNumber = if (row.unread > 0) "unread" to row.unread else "threads" to row.threads
            val display =
                rowDisplay(
                    kind =
                        when {
                            marks.loud -> "loud"
                            marks.quietLabel != null -> "quiet"
                            marks.markedUnreadDot -> "markedDot"
                            else -> "none"
                        },
                    // The pink badge shows both numbers; the grey count shows one (the unread, else the threads).
                    segments =
                        when {
                            marks.loud -> listOf("unread" to marks.loudCount, "threads" to marks.loudThreadCount)
                            marks.quietLabel != null -> listOf(quietNumber)
                            else -> emptyList()
                        },
                    unreadTone =
                        homeConversationRowTone(row.muted, row.joined, selected = false, loudUnread = marks.unreadTone) ==
                            RaftRowTone.Unread,
                )
            listOfNotNull(unreadCaseStateMismatch("rowDisplay", case.expect!!.state!!.getValue("rowDisplay"), display))
        },
    )

/**
 * A DM row: [homeConversationBadgeProjection] with exactly the inputs HomeSidebarDirectMessageRow
 * passes (unread, muted, marked; no mention or follow), and its row tone.
 */
private fun dmRowDriver() =
    UnreadCaseDriver(
        name = "dmRow",
        decline = { case ->
            case.rowDisplayDecline(UnreadCaseConversationKind.Dm)
                ?: "threads/hasNew on a DM row".takeIf { case.given.row?.let { it.threads > 0 || it.hasNew != null } == true }
        },
        run = { case ->
            val row = case.given.row!!
            val badge =
                homeConversationBadgeProjection(
                    HomeConversationBadgeInput(unreadCount = row.unread, muted = row.muted, markedUnread = row.markedUnread)
                )
            val display =
                rowDisplay(
                    kind =
                        when {
                            badge.label != null && badge.tone == HomeSidebarBadgeTone.Loud -> "loud"
                            badge.label != null -> "quiet"
                            badge.markedUnreadDot -> "markedDot"
                            else -> "none"
                        },
                    segments = if (badge.label != null) listOf("unread" to row.unread) else emptyList(),
                    unreadTone =
                        homeConversationRowTone(row.muted, joined = true, selected = false, loudUnread = badge.hasUnread) ==
                            RaftRowTone.Unread,
                )
            listOfNotNull(unreadCaseStateMismatch("rowDisplay", case.expect!!.state!!.getValue("rowDisplay"), display))
        },
    )

private fun rowDisplay(kind: String, segments: List<Pair<String, Int>>, unreadTone: Boolean): JsonObject =
    buildJsonObject {
        put("kind", kind)
        put("segments", buildJsonObject { segments.filter { it.second > 0 }.forEach { (key, value) -> put(key, value) } })
        put("unreadTone", unreadTone)
    }

// ---- Store drivers: requests through a scripted repository ----------------------------------------

/**
 * The read-receipt repository under the stores, scripted by the case. It records every write as
 * the `{method, /api/path}` RealReadReceiptRepository sends for it (those paths are pinned by
 * ReadReceiptRepositoryDataSourceTest); `markChannelUnread` waits for the case's `serverResponds`
 * and reads the answer with the production [parseMarkConversationUnreadResult]; reads are
 * acknowledged at once (no case scripts an answer to a read). Not the real repository: it parses
 * on RaftJsonParseDispatcher (a real thread), which would race virtual time.
 */
private class ScriptedUnreadRepository : ReadReceiptRepository {
    val requests = mutableListOf<UnreadCaseRequest>()
    var lastUnreadResult: DataResult<ReadReceiptResult>? = null
    private val waiting = ArrayDeque<CompletableDeferred<UnreadCaseStep.ServerResponds>>()

    override suspend fun getUnreadCounts(serverId: ServerId): DataResult<ChannelUnreadSnapshot> =
        DataResult.Error(ScreenError(code = "not_scripted", retryable = false))

    override suspend fun markChannelReadToSeq(channelId: ChannelId, seq: Long): DataResult<ReadReceiptResult> {
        requests += UnreadCaseRequest("POST", "/api/channels/${channelId.value}/read")
        return DataResult.Content(ReadReceiptResult(channelId = channelId, seq = seq))
    }

    override suspend fun markChannelReadAll(channelId: ChannelId): DataResult<ReadReceiptResult> {
        requests += UnreadCaseRequest("POST", "/api/channels/${channelId.value}/read-all")
        return DataResult.Content(ReadReceiptResult(channelId = channelId))
    }

    override suspend fun markChannelUnread(channelId: ChannelId): DataResult<ReadReceiptResult> {
        requests += UnreadCaseRequest("POST", "/api/channels/${channelId.value}/unread")
        val answer = CompletableDeferred<UnreadCaseStep.ServerResponds>()
        waiting.addLast(answer)
        val step = answer.await()
        val result =
            if (step.status in 200..299) {
                parseMarkConversationUnreadResult(step.body?.toString().orEmpty(), channelId)
                    ?.let { DataResult.Content(it) }
                    ?: DataResult.Error(ScreenError(code = "network_parse_failed", retryable = true))
            } else {
                DataResult.Error(ScreenError(code = "http_${step.status}", retryable = step.status >= 500))
            }
        lastUnreadResult = result
        return result
    }

    override suspend fun markInboxReadAll(serverId: ServerId): DataResult<ReadReceiptResult> {
        requests += UnreadCaseRequest("POST", "/api/channels/inbox/read-all")
        return DataResult.Content(ReadReceiptResult())
    }

    /** Answers the earliest unanswered request; false when none is waiting. */
    fun respond(step: UnreadCaseStep.ServerResponds): Boolean {
        val answer = waiting.removeFirstOrNull() ?: return false
        answer.complete(step)
        return true
    }
}

private fun outcomeElement(result: DataResult<ReadReceiptResult>?): JsonElement =
    when (result) {
        null -> JsonNull
        is DataResult.Content ->
            JsonPrimitive(
                when (result.data.markUnreadOutcome()) {
                    MarkUnreadOutcome.Counted -> "counted"
                    MarkUnreadOutcome.Marked -> "marked"
                    MarkUnreadOutcome.NothingToMark -> "nothing"
                    MarkUnreadOutcome.Pending -> "pending"
                }
            )
        else -> JsonPrimitive("failed:${result::class.simpleName}")
    }

private fun toastElement(notices: List<RaftReadToggleNotice>): JsonElement =
    when (notices.lastOrNull()) {
        null -> JsonNull
        RaftReadToggleNotice.MarkUnreadNothing -> JsonPrimitive("markUnreadNothing")
    }

/** One store-driven run: apply the steps, check checkpoints and `expect`. */
private abstract class StoreCaseSession(val case: UnreadContractCase) {
    val repository = ScriptedUnreadRepository()
    val notices = mutableListOf<RaftReadToggleNotice>()
    private var noticesBeforeLastStep = 0

    /** Applies one act; a message when this session cannot. */
    abstract suspend fun TestScope.apply(step: UnreadCaseStep): String?

    /** Lets the store finish what the act started (virtual time only). */
    open fun TestScope.settle() = runCurrent()

    /** The observed value of a state key, or null when this session cannot observe it. */
    abstract suspend fun TestScope.observe(key: String): JsonElement?

    fun lastActToast(): JsonElement = toastElement(notices.drop(noticesBeforeLastStep))

    suspend fun TestScope.runCase(): List<String> {
        val failures = mutableListOf<String>()
        case.checkpoints.filter { it.afterStep !in 1..case.steps.size }.forEach {
            failures += "checkpoint afterStep ${it.afterStep} is outside the ${case.steps.size} steps"
        }
        case.steps.forEachIndexed { index, step ->
            noticesBeforeLastStep = notices.size
            apply(step)?.let { return failures + "step ${index + 1} (${step::class.simpleName}): $it" }
            settle()
            case.checkpoints.filter { it.afterStep == index + 1 }.forEach { checkpoint ->
                failures += check("after step ${index + 1}", checkpoint.requests, checkpoint.state)
            }
        }
        case.expect?.let { failures += check("at the end", it.requests, it.state) }
        return failures
    }

    private suspend fun TestScope.check(
        where: String,
        requests: List<UnreadCaseRequest>?,
        state: JsonObject?,
    ): List<String> {
        val failures = mutableListOf<String>()
        if (requests != null && requests != repository.requests) {
            failures += "$where: requests expected $requests, got ${repository.requests.toList()}"
        }
        state?.forEach { (key, expected) ->
            unreadCaseStateMismatch(key, expected, observe(key))?.let { failures += "$where: $it" }
        }
        return failures
    }
}

/** The state keys a store driver can observe; anything else declines the case. */
private fun UnreadContractCase.declineUnobservable(observable: Set<String>): String? =
    (assertedStateKeys - observable).takeIf { it.isNotEmpty() }?.let { "cannot observe ${it.sorted()}" }

// ---- R2: Mark as Unread from a Home row (SharedHomeStore) -----------------------------------------

private fun homeRowMarkUnreadDriver() =
    UnreadCaseDriver(
        name = "homeRow",
        decline = { case ->
            val row = case.given.row
            when {
                row == null || case.given.conversation.kind == UnreadCaseConversationKind.Thread ->
                    "not a channel/DM row"
                case.steps.firstOrNull() != UnreadCaseStep.MarkUnread(UnreadCaseSurface.ConversationRow) ->
                    "does not start with markUnread on a conversation row"
                case.steps.any { it !is UnreadCaseStep.MarkUnread && it !is UnreadCaseStep.ServerResponds } ->
                    "acts other than markUnread/serverResponds"
                row.unread > 0 || row.markedUnread || row.threads > 0 ->
                    "the harness seeds an empty unread summary (no starting count or mark)"
                else -> case.declineUnobservable(setOf("unreadState", "toast", "outcome"))
            }
        },
        run = { case -> with(HomeRowSession(case)) { start(); runCase() } },
    )

private class HomeRowSession(case: UnreadContractCase) : StoreCaseSession(case) {
    private val conversationId = case.given.conversation.id
    private lateinit var store: SharedHomeStore

    suspend fun TestScope.start() {
        val session = sampleSession()
        val row = case.given.row!!
        val snapshot =
            HomeSnapshot(
                server = ServerSummary(ServerId("server-1"), "Slock"),
                currentMember =
                    MemberProfile(
                        id = MemberId("user-1"),
                        displayName = "User One",
                        handle = "user-1",
                        kind = MemberKind.Human,
                        presence = Presence.Active,
                    ),
                channels =
                    listOfNotNull(
                        HomeChannelRow(
                            threadId = ThreadId(conversationId),
                            channelId = ChannelId(conversationId),
                            name = "general",
                            unreadCountKnown = true,
                            joined = row.joined,
                        ).takeIf { case.given.conversation.kind == UnreadCaseConversationKind.Channel }
                    ),
                directMessages =
                    listOfNotNull(
                        HomeDirectMessageRow(
                            threadId = ThreadId(conversationId),
                            channelId = ChannelId(conversationId),
                            name = "Cindy",
                            unreadCountKnown = true,
                        ).takeIf { case.given.conversation.kind == UnreadCaseConversationKind.Dm }
                    ),
                unreadTotal = 0,
            )
        store =
            SharedHomeStore(
                authStore = InMemoryRaftAuthSessionStore(session),
                dataSource =
                    SharedHomeDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(session),
                        api = EmptySummaryHomeApi,
                        parseHome = { payload, _ -> DataResult.Content(snapshot.copy(markedUnreadIds = payload.markedUnreadIds)) },
                        parseDispatcher = Dispatchers.Unconfined,
                    ),
                scope = backgroundScope,
                markChannelReadAll = { channelId -> repository.markChannelReadAll(channelId) },
                markChannelUnread = { channelId -> repository.markChannelUnread(channelId) },
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.readToggleNotices.collect { notices += it } }
        store.accept(SharedHomeIntent.Start)
        runCurrent()
    }

    override suspend fun TestScope.apply(step: UnreadCaseStep): String? =
        when (step) {
            is UnreadCaseStep.MarkUnread -> {
                // accept() suspends until the write is answered; the answer is a later step.
                backgroundScope.launch { store.accept(SharedHomeIntent.MarkUnread(conversationId)) }
                null
            }
            is UnreadCaseStep.ServerResponds -> if (repository.respond(step)) null else "no request is waiting for an answer"
            else -> "not driven by the Home row session"
        }

    override suspend fun TestScope.observe(key: String): JsonElement? =
        when (key) {
            "unreadState" -> {
                val snapshot = (store.state.value.content as? SharedHomeContentState.Content)?.snapshot
                val row: HomeConversationRow? =
                    snapshot?.channels?.firstOrNull { it.channelId.value == conversationId }
                        ?: snapshot?.directMessages?.firstOrNull { it.channelId.value == conversationId }
                when {
                    row == null -> JsonPrimitive("row missing")
                    row.unreadCount > 0 -> buildJsonObject { put("kind", "count"); put("count", row.unreadCount) }
                    conversationId in snapshot!!.markedUnreadIds -> buildJsonObject { put("kind", "dot") }
                    else -> buildJsonObject { put("kind", "none") }
                }
            }
            "toast" -> lastActToast()
            "outcome" -> outcomeElement(repository.lastUnreadResult)
            else -> null
        }
}

private object EmptySummaryHomeApi : RaftHomeApi {
    override suspend fun channels(priority: DataRequestPriority) = HomeNetworkResponse(code = 200, bodyText = "[]")

    override suspend fun directMessages(priority: DataRequestPriority) = HomeNetworkResponse(code = 200, bodyText = "[]")

    override suspend fun channelUnreadCounts(priority: DataRequestPriority) =
        HomeNetworkResponse(code = 200, bodyText = EMPTY_CHANNEL_UNREAD_SUMMARY_BODY)

    override suspend fun serverUnreadSummary(priority: DataRequestPriority) = HomeNetworkResponse(code = 200, bodyText = "[]")
}

// ---- R1/R3: Mark as Unread from an open thread (SharedThreadStore) --------------------------------

private fun threadMenuDriver() =
    UnreadCaseDriver(
        name = "threadMenu",
        decline = { case ->
            val conversation = case.given.conversation
            when {
                conversation.kind != UnreadCaseConversationKind.Thread || case.given.openThread != conversation.id ->
                    "not a case in the open thread"
                case.steps.any {
                    it !is UnreadCaseStep.ServerResponds && it !is UnreadCaseStep.ReplyArrives &&
                        it != UnreadCaseStep.LeaveThread && it != UnreadCaseStep.MarkUnread(UnreadCaseSurface.ThreadMenu) &&
                        !(it is UnreadCaseStep.RealtimeEvent && it.type == RealtimeSocketEvents.ReadStateUpdated)
                } -> "acts other than threadMenu markUnread/serverResponds/replyArrives/leaveThread/read_state:updated"
                else -> case.declineUnobservable(setOf("outcome", "toast", "holdActive", "unreadState"))
            }
        },
        run = { case -> with(ThreadMenuSession(case)) { start(); runCase().also { close() } } },
    )

/**
 * The open thread page: [SharedThreadStore] over the real read-receipt coordinator and repository.
 * `replyArrives(visible)` is the page's visible-read dispatch; `leaveThread` disposes the store (the
 * page is gone). `holdActive` is probed: a visible read dispatched now, held iff no read goes out
 * (the probe's own requests are not counted as the case's); after leaving, the probe reopens the
 * thread in a fresh store. A `read_state:updated` is mapped by the production RealtimeEventMapper
 * and goes to the open page (it resolves a 202) and to the Activity store, whose row for the
 * thread is the `unreadState` the case observes (the thread page keeps none).
 */
private class ThreadMenuSession(case: UnreadContractCase) : StoreCaseSession(case) {
    private val threadId = case.given.conversation.id
    private val base = basePayload().copy(instanceId = "shared-shell-thread-$threadId", sourceThreadId = threadId)
    private lateinit var coordinatorScope: CoroutineScope
    private lateinit var dataSource: build.raft.app.SharedThreadDataSource
    private var store: SharedThreadStore? = null
    private var storeScope: CoroutineScope? = null
    private lateinit var activity: SharedActivityStore

    suspend fun TestScope.start() {
        coordinatorScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        dataSource =
            build.raft.app.testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true).also { it.failReadAll = false },
                sessionStore = InMemoryRaftAuthSessionStore(threadSampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult = DataResult.Content(threadSnapshot(threadId = threadId, body = "cached body", seq = 0))
                    ),
                readReceiptCoordinator = ReadReceiptCoordinator(scope = coordinatorScope, repository = repository),
                readReceiptOwnerFactory = { serverId, accountId -> ReadReceiptOwner("production", accountId, serverId) },
            )
        dataSource.cachePayloadForTest(contentPayloadForRoute(base, "cached body"))
        activity =
            activityStore(
                backgroundScope,
                FakeInboxRepository(initialSnapshots = listOf(activitySnapshot(listOf(threadItem(threadId))))),
            )
        activity.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        openStore()
    }

    private suspend fun TestScope.openStore(): SharedThreadStore {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val opened = SharedThreadStore(dataSource = dataSource, scope = scope, readCoalesceTimeSource = testScheduler.timeSource)
        scope.launch { opened.readToggleNotices.collect { notices += it } }
        opened.accept(SharedThreadIntent.Start(base))
        // Let the page resolve its access before the case acts. Only here: once a write is waiting
        // for its scripted answer, advancing virtual time would fire request timeouts instead.
        testScheduler.advanceUntilIdle()
        store = opened
        storeScope = scope
        return opened
    }

    private fun leave() {
        storeScope?.cancel()
        store = null
        storeScope = null
    }

    fun close() {
        leave()
        coordinatorScope.cancel()
    }

    override suspend fun TestScope.apply(step: UnreadCaseStep): String? {
        val open = store
        return when (step) {
            is UnreadCaseStep.MarkUnread ->
                open?.dispatch(SharedThreadIntent.MarkThreadUnread(threadId)).let { if (open == null) "the thread is not open" else null }
            is UnreadCaseStep.ServerResponds -> if (repository.respond(step)) null else "no request is waiting for an answer"
            is UnreadCaseStep.ReplyArrives -> {
                if (step.visible) open?.dispatch(SharedThreadIntent.MarkVisibleRead())
                if (open == null) "the thread is not open" else null
            }
            UnreadCaseStep.LeaveThread -> {
                leave()
                null
            }
            is UnreadCaseStep.RealtimeEvent -> {
                val event = RealtimeEventMapper(diagnosticsLogger = {}).map(step.type, step.payload)
                if (event == null) {
                    "the app's RealtimeEventMapper drops this ${step.type} payload"
                } else {
                    open?.dispatch(SharedThreadIntent.ApplyRealtimeEvent(event))
                    activity.dispatch(SharedActivityIntent.ApplyRealtimeEvent(event))
                    null
                }
            }
            else -> "not driven by the thread session"
        }
    }

    override suspend fun TestScope.observe(key: String): JsonElement? =
        when (key) {
            "outcome" -> outcomeElement(repository.lastUnreadResult)
            "toast" -> lastActToast()
            "holdActive" -> JsonPrimitive(probeHold())
            "unreadState" -> {
                val row = activity.state.value.items.firstOrNull { it.channelId.value == threadId }
                when {
                    row == null -> JsonPrimitive("activity row missing")
                    row.unreadCount > 0 -> buildJsonObject { put("kind", "count"); put("count", row.unreadCount) }
                    row.markedUnread -> buildJsonObject { put("kind", "dot") }
                    else -> buildJsonObject { put("kind", "none") }
                }
            }
            else -> null
        }

    private suspend fun TestScope.probeHold(): Boolean {
        val reopened = store == null
        val probed = store ?: openStore()
        val before = repository.requests.size
        probed.dispatch(SharedThreadIntent.MarkVisibleRead())
        settle()
        val sent = repository.requests.drop(before)
        while (repository.requests.size > before) repository.requests.removeAt(repository.requests.lastIndex)
        if (reopened) leave()
        return sent.none { it.path == "/api/channels/$threadId/read" || it.path == "/api/channels/$threadId/read-all" }
    }
}
