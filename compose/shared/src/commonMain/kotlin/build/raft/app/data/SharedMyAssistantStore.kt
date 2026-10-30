package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * How long to wait before each refetch while the assistant exists but its DM does not yet (web
 * `assistantClient.ts` `DM_SETUP_RETRY_DELAYS_MS`).
 */
val MyAssistantDmSetupRetryDelaysMillis: List<Long> = listOf(2_000L, 5_000L, 10_000L)

/** One person's assistant in one server (web caches by `${serverId}:${userId}`). */
data class MyAssistantKey(val serverId: String, val userId: String)

/** Web `MyAssistantState`. */
sealed interface MyAssistantState {
    data object Loading : MyAssistantState

    /** Not available here: the flag is off, the API says not enabled, or a guest. */
    data object Off : MyAssistantState

    /** Available; [assistant] is null while the person has none. */
    data class Ready(val assistant: MyAssistantRecord?) : MyAssistantState
}

/**
 * Web `useMyAssistant`: guests never; the `personal_assistant` flag must be on (loading while its
 * first evaluation is pending); then the API's `enabled` decides.
 */
fun myAssistantState(
    viewerIsGuest: Boolean,
    flagDecisionPending: Boolean,
    flagEnabled: Boolean,
    answer: MyAssistantAnswer?,
): MyAssistantState =
    when {
        viewerIsGuest -> MyAssistantState.Off
        flagEnabled -> answerState(answer)
        flagDecisionPending -> MyAssistantState.Loading
        else -> MyAssistantState.Off
    }

private fun answerState(answer: MyAssistantAnswer?): MyAssistantState =
    when {
        answer == null -> MyAssistantState.Loading
        !answer.enabled -> MyAssistantState.Off
        else -> MyAssistantState.Ready(answer.assistant)
    }

/**
 * "My assistant" per server for one shell (web `assistantClient.ts` cache): one read per
 * person and server, shared by every page that asks; a refetch with backoff while the
 * assistant's DM is being set up. Also holds which composer the otter lies on ([otterHosts]).
 */
class SharedMyAssistantStore(
    private val api: RaftMyAssistantApi,
    private val scope: CoroutineScope,
    private val dmSetupRetryDelaysMillis: List<Long> = MyAssistantDmSetupRetryDelaysMillis,
    private val logger: (String) -> Unit = {},
) {
    private val answersState = MutableStateFlow<Map<MyAssistantKey, MyAssistantAnswer>>(emptyMap())

    /** The answers read so far, by person and server. */
    val answers: StateFlow<Map<MyAssistantKey, MyAssistantAnswer>> = answersState.asStateFlow()

    /** The composers that may carry the otter, and which one it lies on. */
    val otterHosts: RaftAssistantOtterHosts = RaftAssistantOtterHosts()

    private val lock = SynchronizedObject()
    private val inFlight = mutableMapOf<MyAssistantKey, Job>()

    fun answerFor(key: MyAssistantKey): MyAssistantAnswer? = answersState.value[key]

    /**
     * Reads [key] unless it is known or being read (web `useMyAssistant`'s effect: ask while
     * nothing is cached). Callers ask only once the flag is on and the viewer is not a guest.
     */
    fun ensureLoaded(key: MyAssistantKey) {
        if (key.serverId.isBlank() || key.userId.isBlank()) return
        val job =
            synchronized(lock) {
                if (answersState.value.containsKey(key) || inFlight.containsKey(key)) return
                scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        load(key, keepOnError = false)
                        awaitDmSetup(key)
                    } finally {
                        synchronized(lock) { inFlight.remove(key) }
                    }
                }.also { inFlight[key] = it }
            }
        job.start()
    }

    /**
     * While the assistant exists without its DM (web "Setting up your chat…"), asks again after
     * each delay, keeping the cached answer on a transient failure, until the DM appears or the
     * delays run out. The app has no Assistant page to own this, so the store does it once.
     */
    private suspend fun awaitDmSetup(key: MyAssistantKey) {
        for (wait in dmSetupRetryDelaysMillis) {
            if (!answerFor(key).awaitsDm()) return
            delay(wait)
            load(key, keepOnError = true)
        }
    }

    private suspend fun load(key: MyAssistantKey, keepOnError: Boolean) {
        when (val result = api.fetch(ServerId(key.serverId))) {
            is MyAssistantFetchResult.Answer -> store(key, result.answer)
            MyAssistantFetchResult.NotAvailable -> store(key, MyAssistantAnswer.NotEnabled)
            is MyAssistantFetchResult.Failed -> {
                // A refetch keeps what it had on a transient failure; a first read fails closed.
                if (keepOnError && answerFor(key) != null) return
                logger("my_assistant load_failed server=${key.serverId} code=${result.code ?: "transport"}")
                store(key, MyAssistantAnswer.NotEnabled)
            }
        }
    }

    private fun store(key: MyAssistantKey, answer: MyAssistantAnswer) {
        answersState.update { current -> if (current[key] == answer) current else current + (key to answer) }
    }
}

private fun MyAssistantAnswer?.awaitsDm(): Boolean =
    this != null && enabled && assistant != null && assistant.dmChannelId == null

/**
 * Which composer the otter lies on (web `assistantOtterState.ts` `useOtterHosts`): one otter per
 * shell. Every composer that may carry it registers while it is shown; the otter lies on the one
 * used last: the one focused last, else the one that appeared last (a thread opened over the
 * channel), else the one before it. [order] is that recency, newest last.
 */
class RaftAssistantOtterHosts {
    private val orderState = MutableStateFlow<List<String>>(emptyList())

    val order: StateFlow<List<String>> = orderState.asStateFlow()

    /** A composer that may carry the otter appeared: it takes the otter. */
    fun register(hostId: String) {
        orderState.update { order -> order.filterNot { it == hostId } + hostId }
    }

    fun unregister(hostId: String) {
        orderState.update { order -> if (hostId in order) order.filterNot { it == hostId } else order }
    }

    /** The person used this composer: the otter moves to it (web `activateOtterHost`). */
    fun activate(hostId: String) {
        orderState.update { order ->
            if (hostId !in order || order.lastOrNull() == hostId) order else order.filterNot { it == hostId } + hostId
        }
    }
}

/** The host the otter lies on in [order]. */
fun List<String>.activeAssistantOtterHost(): String? = lastOrNull()
