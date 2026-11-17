package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
    /** Whether the otter shows over every page (the account's setting); null: always shown (tests). */
    val otterPrefs: SharedAssistantOtterPrefsStore? = null,
) {
    private val answersState = MutableStateFlow<Map<MyAssistantKey, MyAssistantAnswer>>(emptyMap())

    /** The answers read so far, by person and server. */
    val answers: StateFlow<Map<MyAssistantKey, MyAssistantAnswer>> = answersState.asStateFlow()

    /** The composers that may carry the otter, and which one it lies on. */
    val otterHosts: RaftAssistantOtterHosts = RaftAssistantOtterHosts()

    private val lock = SynchronizedObject()
    private val inFlight = mutableMapOf<MyAssistantKey, Job>()
    private val createsInFlight = mutableMapOf<MyAssistantKey, Deferred<MyAssistantCreateOutcome>>()
    private val deletesInFlight = mutableMapOf<MyAssistantKey, Deferred<MyAssistantDeleteOutcome>>()

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

    /**
     * Creates the person's assistant (web `createAssistant`), single flight per person and server:
     * a second press while one is on its way waits for the same answer instead of asking again.
     * Runs in the store's scope, so leaving the page does not cancel a create the server may
     * already have done. On a create, or a 409 `assistant_exists` (adopted), the cached answer
     * becomes the server's view and the page turns into the assistant's DM; while its DM is still
     * being set up the store asks again with backoff. A 403 closes it (not available), a 404 or
     * another 409 reads the assistant again.
     */
    suspend fun create(key: MyAssistantKey): MyAssistantCreateOutcome {
        if (key.serverId.isBlank() || key.userId.isBlank()) {
            return MyAssistantCreateOutcome.Failed(ScreenError("missing_identity", retryable = false))
        }
        val request =
            synchronized(lock) {
                createsInFlight[key] ?: scope.async(start = CoroutineStart.LAZY) {
                    try {
                        createOnce(key)
                    } finally {
                        synchronized(lock) { createsInFlight.remove(key) }
                    }
                }.also { createsInFlight[key] = it }
            }
        request.start()
        return request.await()
    }

    /** Whether a create for [key] is on its way. */
    fun creating(key: MyAssistantKey): Boolean = synchronized(lock) { createsInFlight.containsKey(key) }

    private suspend fun createOnce(key: MyAssistantKey): MyAssistantCreateOutcome =
        when (val result = api.create(ServerId(key.serverId))) {
            is MyAssistantCreateResult.Created -> {
                val answer = result.answer
                if (answer != null) store(key, answer) else load(key, keepOnError = true)
                scope.launch { awaitDmSetup(key) }
                MyAssistantCreateOutcome.Done
            }
            is MyAssistantCreateResult.Exists -> {
                logger("my_assistant create_adopted_existing server=${key.serverId}")
                store(key, result.answer)
                scope.launch { awaitDmSetup(key) }
                MyAssistantCreateOutcome.Done
            }
            is MyAssistantCreateResult.CapacityLimit -> {
                logger("my_assistant create_capacity_limit server=${key.serverId} traceId=${result.traceId ?: "none"}")
                MyAssistantCreateOutcome.CapacityLimit(
                    ScreenError("assistant_capacity_limit", retryable = false, serverMessage = result.message, traceId = result.traceId)
                )
            }
            is MyAssistantCreateResult.NotAvailable -> {
                logger("my_assistant create_not_available server=${key.serverId} code=${result.code} traceId=${result.traceId ?: "none"}")
                store(key, MyAssistantAnswer.NotEnabled)
                MyAssistantCreateOutcome.Done
            }
            is MyAssistantCreateResult.Stale -> {
                logger("my_assistant create_stale server=${key.serverId} code=${result.code} traceId=${result.traceId ?: "none"}")
                load(key, keepOnError = true)
                MyAssistantCreateOutcome.Done
            }
            is MyAssistantCreateResult.Failed -> {
                logger(
                    "my_assistant create_failed server=${key.serverId} code=${result.code ?: "transport"}" +
                        " traceId=${result.traceId ?: "none"}"
                )
                MyAssistantCreateOutcome.Failed(
                    ScreenError(result.code?.let { "http_$it" } ?: "network_error", traceId = result.traceId)
                )
            }
        }

    /**
     * Deletes the person's assistant (web `deleteAssistant`), single flight per person and server:
     * a second confirm while one is on its way waits for the same answer. Runs in the store's
     * scope, so closing the profile does not cancel a delete the server may already have done. On
     * a delete the cached answer becomes the server's view (no assistant: the page offers Create
     * again); a 403 closes it (not available), a 404 or 409 reads the assistant again. Only another
     * failure is the caller's to show.
     */
    suspend fun delete(key: MyAssistantKey): MyAssistantDeleteOutcome {
        if (key.serverId.isBlank() || key.userId.isBlank()) {
            return MyAssistantDeleteOutcome.Failed(ScreenError("missing_identity", retryable = false))
        }
        val request =
            synchronized(lock) {
                deletesInFlight[key] ?: scope.async(start = CoroutineStart.LAZY) {
                    try {
                        deleteOnce(key)
                    } finally {
                        synchronized(lock) { deletesInFlight.remove(key) }
                    }
                }.also { deletesInFlight[key] = it }
            }
        request.start()
        return request.await()
    }

    /** Whether a delete for [key] is on its way. */
    fun deleting(key: MyAssistantKey): Boolean = synchronized(lock) { deletesInFlight.containsKey(key) }

    private suspend fun deleteOnce(key: MyAssistantKey): MyAssistantDeleteOutcome =
        when (val result = api.delete(ServerId(key.serverId))) {
            is MyAssistantDeleteResult.Deleted -> {
                // Web stores the reply's view; one that can't be read is still "no assistant here".
                val answer = result.answer?.takeIf { it.assistant == null }
                if (answer != null) {
                    store(key, answer)
                } else {
                    store(key, MyAssistantAnswer(enabled = answerFor(key)?.enabled ?: true, assistant = null))
                    scope.launch { load(key, keepOnError = true) }
                }
                MyAssistantDeleteOutcome.Deleted
            }
            is MyAssistantDeleteResult.NotAvailable -> {
                logger("my_assistant delete_not_available server=${key.serverId} code=${result.code} traceId=${result.traceId ?: "none"}")
                store(key, MyAssistantAnswer.NotEnabled)
                MyAssistantDeleteOutcome.Deleted
            }
            is MyAssistantDeleteResult.Stale -> {
                logger("my_assistant delete_stale server=${key.serverId} code=${result.code} traceId=${result.traceId ?: "none"}")
                load(key, keepOnError = true)
                MyAssistantDeleteOutcome.Deleted
            }
            is MyAssistantDeleteResult.Failed -> {
                logger(
                    "my_assistant delete_failed server=${key.serverId} code=${result.code ?: "transport"}" +
                        " traceId=${result.traceId ?: "none"}"
                )
                MyAssistantDeleteOutcome.Failed(
                    ScreenError(result.code?.let { "http_$it" } ?: "network_error", traceId = result.traceId)
                )
            }
        }

    /**
     * Saves the owner's extra instructions (web `saveAssistantOwnerNotes`): [notes] trimmed, empty
     * clears them. Not optimistic: the cached answer takes the server's view once it says yes, its
     * saved notes or else the trimmed text. A 403 / 404 reads the assistant again, as web refetches.
     */
    suspend fun saveOwnerNotes(key: MyAssistantKey, notes: String): MyAssistantOwnerNotesSaveOutcome {
        val trimmed = notes.trim()
        return when (val result = api.updateOwnerNotes(ServerId(key.serverId), trimmed)) {
            is MyAssistantOwnerNotesSaveResult.Saved -> {
                applySavedOwnerNotes(key, result.answer, trimmed)
                MyAssistantOwnerNotesSaveOutcome.Saved
            }
            is MyAssistantOwnerNotesSaveResult.TooLong ->
                MyAssistantOwnerNotesSaveOutcome.TooLong(
                    ScreenError("assistant_owner_notes_too_long", retryable = false, traceId = result.traceId)
                )
            is MyAssistantOwnerNotesSaveResult.NotAvailable -> {
                scope.launch { load(key, keepOnError = true) }
                MyAssistantOwnerNotesSaveOutcome.Failed(
                    ScreenError("http_${result.code}", retryable = false, traceId = result.traceId)
                )
            }
            is MyAssistantOwnerNotesSaveResult.Failed -> {
                logger(
                    "my_assistant owner_notes_save_failed server=${key.serverId} code=${result.code ?: "transport"}" +
                        " traceId=${result.traceId ?: "none"}"
                )
                MyAssistantOwnerNotesSaveOutcome.Failed(
                    ScreenError(result.code?.let { "http_$it" } ?: "network_error", traceId = result.traceId)
                )
            }
        }
    }

    /**
     * Turns one of the owner's switches on or off (web `updateAssistantSetting`), optimistically:
     * the cached answer takes [value] at once and goes back to what it was when the save fails. On
     * success it takes only this switch from the reply (the other may have a save of its own in
     * flight). A 403 / 404 reads the assistant again, which hides the switch as the server now says.
     * The save runs in the store's scope, so it finishes (and reverts) even if the profile closes.
     */
    suspend fun saveSetting(key: MyAssistantKey, setting: AssistantOwnerSetting, value: Boolean): MyAssistantSettingSaveOutcome {
        val previous = answerFor(key)?.assistant?.ownerSetting(setting)
        patchSetting(key, setting, value)
        return scope.async {
            when (val result = api.updateSetting(ServerId(key.serverId), setting, value)) {
                is MyAssistantSettingSaveResult.Saved -> {
                    val mine = answerFor(key)?.assistant
                    val replied =
                        result.answer
                            ?.takeIf { it.enabled }
                            ?.assistant
                            ?.takeIf { mine == null || it.agentId == mine.agentId }
                            ?.ownerSetting(setting)
                    patchSetting(key, setting, replied ?: value)
                    MyAssistantSettingSaveOutcome.Saved
                }
                is MyAssistantSettingSaveResult.NotAvailable -> {
                    patchSetting(key, setting, previous)
                    scope.launch { load(key, keepOnError = true) }
                    MyAssistantSettingSaveOutcome.Failed(
                        ScreenError("http_${result.code}", retryable = false, traceId = result.traceId)
                    )
                }
                is MyAssistantSettingSaveResult.Failed -> {
                    patchSetting(key, setting, previous)
                    logger(
                        "my_assistant setting_save_failed server=${key.serverId} setting=${setting.wireName}" +
                            " code=${result.code ?: "transport"} traceId=${result.traceId ?: "none"}"
                    )
                    MyAssistantSettingSaveOutcome.Failed(
                        ScreenError(result.code?.let { "http_$it" } ?: "network_error", traceId = result.traceId)
                    )
                }
            }
        }.await()
    }

    /** Web `patchSetting`: the cached assistant with [setting] at [value]; nothing while none is cached. */
    private fun patchSetting(key: MyAssistantKey, setting: AssistantOwnerSetting, value: Boolean?) {
        answersState.update { current ->
            val cached = current[key] ?: return@update current
            val mine = cached.assistant ?: return@update current
            val next = cached.copy(assistant = mine.withOwnerSetting(setting, value))
            if (next == cached) current else current + (key to next)
        }
    }

    private fun applySavedOwnerNotes(key: MyAssistantKey, reply: MyAssistantAnswer?, trimmed: String) {
        answersState.update { current ->
            val cached = current[key]?.takeIf { it.enabled } ?: return@update current
            val mine = cached.assistant ?: return@update current
            val replied = reply?.assistant?.takeIf { reply.enabled && it.agentId == mine.agentId }
            val next =
                if (replied != null) {
                    // The reply is the whole view: take it, keeping the notes just saved if it left them out.
                    cached.copy(assistant = replied.copy(ownerNotes = replied.ownerNotes ?: trimmed))
                } else {
                    cached.copy(assistant = mine.copy(ownerNotes = trimmed))
                }
            if (next == cached) current else current + (key to next)
        }
    }

    private fun store(key: MyAssistantKey, answer: MyAssistantAnswer) {
        answersState.update { current -> if (current[key] == answer) current else current + (key to answer) }
    }
}

/** What a create means for the Create view (web CreateAssistantView `CreateFailure`). */
sealed interface MyAssistantCreateOutcome {
    /**
     * The cached answer now says what is so (created, adopted, closed or read again): the page
     * follows it. Nothing for the view to show.
     */
    data object Done : MyAssistantCreateOutcome

    /** At the agent or seat limit: [error] carries the server's message and the trace ID. */
    data class CapacityLimit(val error: ScreenError) : MyAssistantCreateOutcome

    /** Not created for any other reason: "Couldn't create your assistant", with the trace ID. */
    data class Failed(val error: ScreenError) : MyAssistantCreateOutcome
}

/** What a delete means for the owner's section (web AssistantOwnerSection `handleDelete`). */
sealed interface MyAssistantDeleteOutcome {
    /**
     * The cached answer now says what is so (deleted, closed, or read again): the section goes
     * with the assistant and the page offers Create. Nothing to show.
     */
    data object Deleted : MyAssistantDeleteOutcome

    /** Not deleted (5xx, offline): said with the trace ID; the assistant stays. */
    data class Failed(val error: ScreenError) : MyAssistantDeleteOutcome
}

/**
 * What saving the owner's notes means for the editor (web AssistantInstructionsDialog `SaveError`).
 * A refusal keeps the [ScreenError], so the editor shows its trace ID (AGENTS.md "Errors carry the
 * trace ID").
 */
sealed interface MyAssistantOwnerNotesSaveOutcome {
    /** Saved: the editor closes. */
    data object Saved : MyAssistantOwnerNotesSaveOutcome

    /** Over the limit: the editor says so. */
    data class TooLong(val error: ScreenError) : MyAssistantOwnerNotesSaveOutcome

    /** Not saved for any other reason; the draft stays. */
    data class Failed(val error: ScreenError) : MyAssistantOwnerNotesSaveOutcome
}

/**
 * What saving one of the owner's switches means for its row (web `AssistantSettingSaveError`). A
 * failure keeps the [ScreenError], so the row shows its trace ID.
 */
sealed interface MyAssistantSettingSaveOutcome {
    data object Saved : MyAssistantSettingSaveOutcome

    /** Not saved; the switch is back where it was. */
    data class Failed(val error: ScreenError) : MyAssistantSettingSaveOutcome
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
