package build.raft.app.data

import build.raft.app.model.MemberKind
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.network.RaftFailedRequestTraceIds
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/*
 * In-app store rating prompt (release 1.25, spec "Raft 应用内评分 Spec" v8 by Ginny, approved by
 * huxijin): after a moment that went well, the app may call the platform's own rating prompt
 * (iOS SKStoreReviewController / AppStore.requestReview, Android Play In-App Review), and only:
 *  - at most once per device, ever: once the app has called the system API it never calls it again,
 *    across versions, whether or not the system showed anything;
 *  - never for an internal account ([RaftInternalServers]) nor on a non-production environment;
 *  - only when this device first opened the app at least 3 days ago, the app was active on at
 *    least 5 distinct local days, and the current server has at least one Computer;
 *  - only after one of two triggers ([RaftAppRatingPromptTrigger]), never right after a failed
 *    server request, while typing, or at launch.
 * No custom dialog, no "rate us" link, no Settings entry.
 */

/** The platform's native store rating prompt (Android Play In-App Review, iOS App Store review). */
fun interface RaftStoreReviewRequester {
    /**
     * Asks the platform to show its rating prompt; main thread. True when the system API was called
     * (the platform decides whether anything shows), false when it could not be called (no
     * foreground activity / window scene): then nothing is recorded and a later trigger may try.
     */
    fun requestReview(): Boolean
}

/** Where a host installs its [RaftStoreReviewRequester]. None installed (desktop) means never ask. */
object RaftStoreReviewHost {
    @Volatile
    var requester: RaftStoreReviewRequester? = null
}

/** What this device remembers for the rating prompt. */
data class RaftRatingPromptRecord(
    /** The app has called the system rating API on this device. Never reset. */
    val requested: Boolean = false,
    /** The local day (epoch days) this device first opened the app with rating support. */
    val firstSeenEpochDay: Long? = null,
    /** How many distinct local days the app was opened or brought to the foreground. */
    val activeDayCount: Int = 0,
    /** The last day counted in [activeDayCount]. */
    val lastActiveEpochDay: Long? = null,
)

/**
 * The rating prompt's device record over the shared preference store (AGENTS.md "Local
 * Preferences"). Keeps only the flag, the first-seen day and the active-day count, never the days.
 */
class RaftRatingPromptRepository(private val store: SharedPreferenceStore) {
    fun read(): RaftRatingPromptRecord =
        RaftRatingPromptRecord(
            requested = store.getBoolean(KeyRequested),
            firstSeenEpochDay = store.getString(KeyFirstSeenDay)?.toLongOrNull(),
            activeDayCount = store.getString(KeyActiveDayCount)?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            lastActiveEpochDay = store.getString(KeyLastActiveDay)?.toLongOrNull(),
        )

    /**
     * The app was opened or foregrounded on local day [epochDay]. A day counts once; a day not
     * after the last counted one (the same day, or a clock / time zone moved back) does not count.
     */
    fun recordActiveDay(epochDay: Long) {
        val record = read()
        if (record.firstSeenEpochDay == null) store.putString(KeyFirstSeenDay, epochDay.toString())
        val last = record.lastActiveEpochDay
        if (last != null && epochDay <= last) return
        store.putString(KeyActiveDayCount, (record.activeDayCount + 1).toString())
        store.putString(KeyLastActiveDay, epochDay.toString())
    }

    fun markRequested() {
        store.putBoolean(KeyRequested, true)
    }

    companion object {
        const val KeyRequested = "ratingPrompt:requested"
        const val KeyFirstSeenDay = "ratingPrompt:firstSeenDay"
        const val KeyActiveDayCount = "ratingPrompt:activeDayCount"
        const val KeyLastActiveDay = "ratingPrompt:lastActiveDay"
    }
}

/** Why the prompt was not requested. */
enum class RaftRatingPromptBlock {
    AlreadyRequested,
    InternalAccount,
    RecentError,
    TooSoonSinceFirstOpen,
    TooFewActiveDays,
    NoComputer,
}

/** Everything [RaftRatingPromptPolicy] decides on. */
data class RaftRatingPromptInput(
    val record: RaftRatingPromptRecord,
    val todayEpochDay: Long,
    /** [SharedSettingsApiEnvironmentOption.key] of the active API environment. */
    val environmentKey: String,
    /** Every server the signed-in account has joined. */
    val joinedServerIds: Collection<String>,
    /** The current server's machine list has at least one Computer. */
    val currentServerHasComputer: Boolean,
    /** A server request failed moments ago (the error the person may be looking at). */
    val recentServerError: Boolean,
)

sealed interface RaftRatingPromptDecision {
    data object Request : RaftRatingPromptDecision

    data class Skip(val reason: RaftRatingPromptBlock) : RaftRatingPromptDecision
}

/** The rating prompt rules, pure. */
object RaftRatingPromptPolicy {
    const val MinDaysSinceFirstOpen: Long = 3
    const val MinActiveDays: Int = 5

    fun decide(input: RaftRatingPromptInput): RaftRatingPromptDecision {
        val record = input.record
        val block =
            when {
                record.requested -> RaftRatingPromptBlock.AlreadyRequested
                RaftInternalServers.isInternalAccount(input.environmentKey, input.joinedServerIds) ->
                    RaftRatingPromptBlock.InternalAccount
                input.recentServerError -> RaftRatingPromptBlock.RecentError
                record.firstSeenEpochDay == null || input.todayEpochDay - record.firstSeenEpochDay < MinDaysSinceFirstOpen ->
                    RaftRatingPromptBlock.TooSoonSinceFirstOpen
                record.activeDayCount < MinActiveDays -> RaftRatingPromptBlock.TooFewActiveDays
                !input.currentServerHasComputer -> RaftRatingPromptBlock.NoComputer
                else -> null
            }
        return block?.let { RaftRatingPromptDecision.Skip(it) } ?: RaftRatingPromptDecision.Request
    }

    /**
     * `task_result_viewed`: a finished ([TaskStatus.Done]) task the viewer created and gave to an
     * agent. [creatorId] / [assigneeKind] come from the task as it was opened.
     */
    fun isOwnFinishedAgentTask(
        status: TaskStatus,
        creatorId: String?,
        assigneeKind: MemberKind?,
        viewerUserId: String?,
    ): Boolean =
        status == TaskStatus.Done &&
            assigneeKind == MemberKind.Agent &&
            !viewerUserId.isNullOrBlank() &&
            creatorId?.trim() == viewerUserId.trim()
}

/** The signed-in account as the rating prompt needs it. */
data class RaftRatingPromptAccount(
    val userId: String,
    val currentServerId: String?,
    val joinedServerIds: List<String>,
)

/** The signed-in shell's view of the account and its servers, installed while a shell is shown. */
interface RaftRatingPromptShellContext {
    suspend fun account(): RaftRatingPromptAccount?

    /** Whether [serverId]'s machine list has at least one Computer; null when it can't be known. */
    suspend fun serverHasComputer(serverId: String): Boolean?
}

/** What one trigger came to (tests and the log). */
sealed interface RaftRatingPromptOutcome {
    data object Requested : RaftRatingPromptOutcome

    data class Skipped(val reason: String) : RaftRatingPromptOutcome
}

/**
 * Turns the two triggers into at most one platform rating request per device. Hosts report the
 * app's foreground/background; the shell installs [RaftRatingPromptShellContext]; the trigger
 * points report their success. All evaluation runs off the main thread on [scope], one at a time;
 * only [RaftStoreReviewRequester.requestReview] runs on [requestContext] (main).
 */
class RaftRatingPromptCoordinator(
    private val repository: () -> RaftRatingPromptRepository?,
    private val todayEpochDay: () -> Long,
    private val environmentKey: () -> String,
    private val recentServerError: () -> Boolean,
    private val requester: () -> RaftStoreReviewRequester?,
    private val track: (RaftAppRatingPromptTrigger) -> Unit,
    private val scope: CoroutineScope,
    private val requestContext: CoroutineContext,
    private val log: (String) -> Unit = {},
) {
    private class OpenedTask(val status: TaskStatus, val creatorId: String?, val assigneeKind: MemberKind?)

    private val lock = SynchronizedObject()
    private val evaluation = Mutex()
    private var shellContext: RaftRatingPromptShellContext? = null
    private var openedTask: OpenedTask? = null

    /** The signed-in shell is shown with [context]; returns the handle to pass to [unbindShell]. */
    fun bindShell(context: RaftRatingPromptShellContext): RaftRatingPromptShellContext {
        synchronized(lock) { shellContext = context }
        return context
    }

    /** [context]'s shell went away; a newer shell's binding stays. */
    fun unbindShell(context: RaftRatingPromptShellContext) {
        synchronized(lock) { if (shellContext === context) shellContext = null }
    }

    /** The app came to the foreground: counts today as an active day. */
    fun onAppForeground() {
        val records = repository() ?: return
        runCatching { records.recordActiveDay(todayEpochDay()) }
            .onFailure { log("rating_prompt_active_day_failed error=${it::class.simpleName}") }
    }

    /** The app went to the background: an opened task no longer leads back to the list. */
    fun onAppBackground() {
        synchronized(lock) { openedTask = null }
    }

    /** A task opened from the tasks list; it counts when the person comes back to the list. */
    fun onTaskOpened(task: TaskSummary) {
        if (!canEverAsk()) return
        synchronized(lock) {
            openedTask = OpenedTask(task.status, task.creator?.id?.value, task.assignee?.kind)
        }
    }

    /** The tasks list shows again after a task was open (Back on phones, the overlay closed on wide). */
    fun onTaskListShown() {
        val opened = synchronized(lock) { openedTask.also { openedTask = null } } ?: return
        if (opened.status != TaskStatus.Done || opened.assigneeKind != MemberKind.Agent) return
        launchEvaluation(RaftAppRatingPromptTrigger.TaskResultViewed) { account ->
            RaftRatingPromptPolicy.isOwnFinishedAgentTask(opened.status, opened.creatorId, opened.assigneeKind, account.userId)
        }
    }

    /** An agent's action card ran after the person approved / confirmed it. */
    fun onActionCardApproved() {
        launchEvaluation(RaftAppRatingPromptTrigger.ActionCardApproved) { true }
    }

    private fun canEverAsk(): Boolean =
        requester() != null && repository()?.let { runCatching { it.read().requested }.getOrDefault(true) } == false

    private fun launchEvaluation(trigger: RaftAppRatingPromptTrigger, qualifies: (RaftRatingPromptAccount) -> Boolean) {
        // Cheap exits first: a host that can't ask (desktop) or a device that already asked.
        if (!canEverAsk()) return
        scope.launch { evaluate(trigger, qualifies) }
    }

    internal suspend fun evaluate(
        trigger: RaftAppRatingPromptTrigger,
        qualifies: (RaftRatingPromptAccount) -> Boolean = { true },
    ): RaftRatingPromptOutcome {
        // One at a time, and a trigger arriving during an evaluation is dropped: two quick
        // successes must never become two requests.
        if (!evaluation.tryLock()) return skipped(trigger, "busy")
        try {
            val records = repository() ?: return skipped(trigger, "no_store")
            val host = requester() ?: return skipped(trigger, "no_host")
            val context = synchronized(lock) { shellContext } ?: return skipped(trigger, "no_shell")
            val account = context.account() ?: return skipped(trigger, "signed_out")
            if (!qualifies(account)) return skipped(trigger, "not_own_agent_task")
            val record = records.read()
            val base =
                RaftRatingPromptInput(
                    record = record,
                    todayEpochDay = todayEpochDay(),
                    environmentKey = environmentKey(),
                    joinedServerIds = account.joinedServerIds,
                    // Checked last, and only when everything else allows it: it may load the list.
                    currentServerHasComputer = true,
                    recentServerError = recentServerError(),
                )
            val early = RaftRatingPromptPolicy.decide(base)
            if (early is RaftRatingPromptDecision.Skip) return skipped(trigger, early.reason.name)
            val hasComputer = account.currentServerId?.let { context.serverHasComputer(it) } == true
            val decision = RaftRatingPromptPolicy.decide(base.copy(currentServerHasComputer = hasComputer))
            if (decision is RaftRatingPromptDecision.Skip) return skipped(trigger, decision.reason.name)
            val called = withContext(requestContext) { host.requestReview() }
            if (!called) return skipped(trigger, "host_unavailable")
            // The system API was called: never again on this device, whatever it showed.
            records.markRequested()
            track(trigger)
            log("rating_prompt_requested trigger=${trigger.wire}")
            return RaftRatingPromptOutcome.Requested
        } finally {
            evaluation.unlock()
        }
    }

    private fun skipped(trigger: RaftAppRatingPromptTrigger, reason: String): RaftRatingPromptOutcome {
        log("rating_prompt_skipped trigger=${trigger.wire} reason=$reason")
        return RaftRatingPromptOutcome.Skipped(reason)
    }
}

/** The process-wide rating prompt: production wiring of [RaftRatingPromptCoordinator]. */
object RaftRatingPrompt {
    val coordinator: RaftRatingPromptCoordinator by lazy {
        RaftRatingPromptCoordinator(
            repository = { runCatching { RaftRatingPromptRepository(raftSharedPreferenceStore()) }.getOrNull() },
            todayEpochDay = ::raftLocalEpochDayNow,
            // Unreadable reads as not production, which never asks.
            environmentKey = { runCatching { RaftAppScopeApiEnvironment.store().current().key }.getOrDefault("") },
            recentServerError = { RaftFailedRequestTraceIds.recent() != null },
            requester = { RaftStoreReviewHost.requester },
            track = RaftProductEvents::appRatingPromptRequested,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            requestContext = Dispatchers.Main,
            log = { message -> build.raft.app.raftLogInfo("RaftRatingPrompt", message) },
        )
    }

    fun onAppForeground() {
        coordinator.onAppForeground()
    }

    fun onAppBackground() {
        coordinator.onAppBackground()
    }

    fun onTaskOpened(task: TaskSummary) {
        coordinator.onTaskOpened(task)
    }

    fun onTaskListShown() {
        coordinator.onTaskListShown()
    }

    fun onActionCardApproved() {
        coordinator.onActionCardApproved()
    }
}

/** Today in this device's time zone, as epoch days. */
internal fun raftLocalEpochDayNow(): Long {
    val now = ProcessRaftDatetimeFoundationOwner.nowEpochMillis()
    val offsetMinutes = ProcessRaftDatetimeFoundationOwner.localOffsetMinutesAt(now, legacyOffsetMinutes = 0)
    return raftLocalEpochDay(now, offsetMinutes)
}

/** The local day (epoch days) of [epochMillis] at a UTC offset of [offsetMinutes]. */
internal fun raftLocalEpochDay(epochMillis: Long, offsetMinutes: Int): Long =
    (epochMillis + offsetMinutes * 60_000L).floorDiv(86_400_000L)
