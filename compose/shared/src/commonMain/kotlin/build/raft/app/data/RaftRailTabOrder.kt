package build.raft.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// slock staging aae2b7a89 `railTabOrder.ts` adds `artifacts` after `tasks` (RAIL_TAB_IDS and
// INSERTED_AFTER): the app keeps it in the order whether or not its server draws the tab.
//
// The wide rail's tabs in the person's own order, saved on their account: one order for every
// server and device (botiverse/slock #10279, 1cfdc489e1; contract read at staging 4ca2f2e6cb):
//
//   packages/shared/src/railTabOrder.ts   RAIL_TAB_IDS, normalizeRailTabOrder
//   GET   /api/auth/me   -> the user, with `railTabOrder: string[] | null` (null = the default
//                           order; a stored order comes back complete, userService.ts toPublicUser)
//   PATCH /api/auth/me   { railTabOrder: string[] | null } -> the user (auth.ts parseRailTabOrder:
//                           stored complete, null resets; only the fields sent are written)

/**
 * The web's rail tab ids in their default order (slock `RAIL_TAB_IDS`). `assistant` (the personal
 * assistant) has no rail entry in the app: it keeps its place in the order without being drawn.
 */
internal val RaftRailTabIds: List<String> =
    listOf("search", "chat", "activity", "tasks", "artifacts", "members", "computers", "apps", "assistant")

/**
 * Tabs added after people had saved orders, and the tab they follow by default (slock
 * `INSERTED_AFTER`): a saved order without one gets it right after that tab instead of at the end.
 */
private val RaftRailTabInsertedAfter: Map<String, String> = mapOf("apps" to "computers", "artifacts" to "tasks")

/**
 * slock `normalizeRailTabOrder`: [order] as a full order of every tab. Known ids in the given
 * order (first occurrence wins), then the tabs it leaves out in their default order; a left-out
 * tab in [RaftRailTabInsertedAfter] goes right after its tab instead. Unknown ids are dropped;
 * null is the default order.
 */
internal fun normalizeRaftRailTabOrder(order: List<String>?): List<String> {
    val result = mutableListOf<String>()
    order.orEmpty().forEach { id -> if (id in RaftRailTabIds && id !in result) result += id }
    RaftRailTabIds.forEach { id ->
        if (id in result) return@forEach
        val at = RaftRailTabInsertedAfter[id]?.let(result::indexOf) ?: -1
        if (at >= 0) result.add(at + 1, id) else result += id
    }
    return result
}

/**
 * The full order after the rail showed [visibleOrder] (a reorder of the ids it draws): the drawn
 * ids take the drawn ids' slots in [fullOrder] in their new order, so the tabs the app doesn't
 * draw (the assistant; Search while the top bar holds it) keep their place.
 */
internal fun raftRailTabOrderWithVisibleOrder(fullOrder: List<String>, visibleOrder: List<String>): List<String> {
    val full = normalizeRaftRailTabOrder(fullOrder)
    val shown = visibleOrder.filter { it in full }.distinct()
    if (shown.size != full.count { it in shown }) return full
    val moved = shown.iterator()
    return full.map { id -> if (id in shown) moved.next() else id }
}

private val railTabOrderJson = Json { ignoreUnknownKeys = true }

/**
 * The part of `GET/PATCH /api/auth/me`'s user the rail reads. `id` is required so a body that is
 * not the user is a mismatch; `railTabOrder` is null when nothing is saved (and on a server from
 * before slock #10279, which doesn't send it).
 */
@Serializable
internal data class RailTabOrderAccountDto(
    val id: String,
    val railTabOrder: List<String>? = null,
)

/** `PATCH /api/auth/me`'s body: the complete order, and nothing else. */
@Serializable
internal data class RailTabOrderUpdateBody(val railTabOrder: List<String>)

/** `GET/PATCH /api/auth/me`'s answer: the saved order as sent (null: the default), else an error. */
internal fun parseSharedRailTabOrderAccount(statusCode: Int, bodyText: String?): DataResult<List<String>?> {
    if (statusCode !in 200..299) {
        return DataResult.Error(ScreenError(code = "rail_tab_order_http_$statusCode", retryable = statusCode >= 500))
    }
    val dto =
        bodyText?.takeIf { it.isNotBlank() }?.let { body ->
            runCatching { railTabOrderJson.decodeFromString(RailTabOrderAccountDto.serializer(), body) }.getOrNull()
        } ?: return DataResult.Error(ScreenError(code = "rail_tab_order_contract_mismatch", retryable = false))
    return DataResult.Content(dto.railTabOrder)
}

internal fun buildSharedRailTabOrderUpdateBody(order: List<String>): String =
    railTabOrderJson.encodeToString(RailTabOrderUpdateBody.serializer(), RailTabOrderUpdateBody(order))

internal interface RailTabOrderDataSource {
    /** The account's saved order (`GET auth/me`). */
    suspend fun readOrder(): DataResult<List<String>?>

    /** Saves [order] (`PATCH auth/me`); the server's stored order back. */
    suspend fun saveOrder(order: List<String>): DataResult<List<String>?>
}

internal class NetworkRailTabOrderDataSource(private val networkClient: RaftNetworkClient) : RailTabOrderDataSource {
    // A return to the foreground: nothing waits on it.
    override suspend fun readOrder(): DataResult<List<String>?> =
        networkCall { networkClient.settingsAccount(DataRequestPriority.Background) }

    // The drop the person just made.
    override suspend fun saveOrder(order: List<String>): DataResult<List<String>?> =
        networkCall { networkClient.updateSettingsAccount(DataRequestPriority.Foreground, buildSharedRailTabOrderUpdateBody(order)) }

    private suspend fun networkCall(call: suspend () -> HomeNetworkResponse): DataResult<List<String>?> =
        try {
            val response = call()
            parseSharedRailTabOrderAccount(response.code, response.bodyText?.takeIf { it.isNotBlank() } ?: response.errorBodyText)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
        }
}

internal sealed interface SharedRailTabOrderEffect {
    /** The save failed and the previous order is back. */
    data object SaveFailed : SharedRailTabOrderEffect
}

internal data class SharedRailTabOrderState(
    /** The signed-in account the order belongs to (null before a session). */
    val accountId: String? = null,
    /** Every tab id ([RaftRailTabIds]) in the order the rail draws them. */
    val order: List<String> = RaftRailTabIds,
    val effect: SharedRailTabOrderEffect? = null,
)

internal sealed interface SharedRailTabOrderIntent {
    /** The session's user (the app's launch `GET auth/me`): its account and saved order. */
    data class SessionLoaded(val accountId: String, val savedOrder: List<String>?) : SharedRailTabOrderIntent

    /** Whether the rail shows (the wide layout): only then does a return to the foreground read. */
    data class RailShown(val shown: Boolean) : SharedRailTabOrderIntent

    /** Read the saved order again (the app came back to the foreground). */
    data object Refresh : SharedRailTabOrderIntent

    /** The person dropped a tab: [order] is the new full order. */
    data class Reorder(val order: List<String>) : SharedRailTabOrderIntent

    data object ConsumeEffect : SharedRailTabOrderIntent
}

/**
 * The rail's tab order (web `authStore.updateRailTabOrder`). The session's user seeds it; a return
 * to the foreground while the rail shows reads `auth/me` again (another device may have changed
 * it). A drop shows at once and sends one `PATCH auth/me` with the complete order; a failed save
 * puts the previous order back (unless a later drop replaced it) and says so once. The last save
 * wins, across devices too. Reads are single-flight, and a read that started before a drop never
 * overrides it. Main-thread confined: [scope] runs on the main dispatcher.
 */
internal class SharedRailTabOrderStore(
    private val dataSource: RailTabOrderDataSource,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(SharedRailTabOrderState())
    val state: StateFlow<SharedRailTabOrderState> = mutableState.asStateFlow()

    /** Bumped by every drop and account change: a read or save from before it doesn't apply. */
    private var generation = 0L
    /** Bumped by an account change: a save of the last account no longer counts as in flight. */
    private var accountEpoch = 0L
    private var savesInFlight = 0
    private var refreshJob: Job? = null
    private var railShown = false

    fun dispatch(intent: SharedRailTabOrderIntent) {
        when (intent) {
            is SharedRailTabOrderIntent.SessionLoaded -> sessionLoaded(intent.accountId, intent.savedOrder)
            is SharedRailTabOrderIntent.RailShown -> railShown = intent.shown
            SharedRailTabOrderIntent.Refresh -> refresh()
            is SharedRailTabOrderIntent.Reorder -> reorder(intent.order)
            SharedRailTabOrderIntent.ConsumeEffect ->
                if (mutableState.value.effect != null) mutableState.value = mutableState.value.copy(effect = null)
        }
    }

    /** Reads again on every background -> foreground return of [appForeground] while the rail shows. */
    fun followAppLifecycle(appForeground: Flow<Boolean>): Job =
        scope.launch {
            var previous: Boolean? = null
            appForeground.collect { foreground ->
                val returned = foreground && previous == false
                previous = foreground
                if (returned && railShown) refresh()
            }
        }

    private fun sessionLoaded(accountId: String, savedOrder: List<String>?) {
        val current = mutableState.value
        if (current.accountId != accountId) {
            // Another account: nothing of the last one's applies.
            generation += 1
            accountEpoch += 1
            refreshJob?.cancel()
            refreshJob = null
            savesInFlight = 0
            mutableState.value = SharedRailTabOrderState(accountId = accountId, order = normalizeRaftRailTabOrder(savedOrder))
            return
        }
        // A save on its way answers with the newer order.
        if (savesInFlight > 0) return
        apply(normalizeRaftRailTabOrder(savedOrder))
    }

    private fun refresh() {
        if (mutableState.value.accountId == null || refreshJob?.isActive == true || savesInFlight > 0) return
        val mine = generation
        refreshJob =
            scope.launch {
                val result = dataSource.readOrder()
                if (mine != generation || savesInFlight > 0 || result !is DataResult.Content) return@launch
                apply(normalizeRaftRailTabOrder(result.data))
            }
    }

    private fun reorder(requested: List<String>) {
        val current = mutableState.value
        if (current.accountId == null) return
        val order = normalizeRaftRailTabOrder(requested)
        if (order == current.order) return
        val previous = current.order
        val mine = ++generation
        val epoch = accountEpoch
        refreshJob?.cancel()
        refreshJob = null
        savesInFlight += 1
        mutableState.value = current.copy(order = order, effect = null)
        logger("rail_tab_order_save_start order=${order.joinToString(",")}")
        scope.launch {
            val result =
                try {
                    dataSource.saveOrder(order)
                } finally {
                    if (epoch == accountEpoch) savesInFlight -= 1
                }
            // A later drop (or another account) replaced this one: its own answer decides.
            if (mine != generation) return@launch
            when (result) {
                is DataResult.Content -> {
                    logger("rail_tab_order_save_result result=content")
                    apply(normalizeRaftRailTabOrder(result.data ?: order))
                }
                else -> {
                    logger("rail_tab_order_save_result result=error")
                    mutableState.value = mutableState.value.copy(order = previous, effect = SharedRailTabOrderEffect.SaveFailed)
                }
            }
        }
    }

    private fun apply(order: List<String>) {
        if (mutableState.value.order != order) mutableState.value = mutableState.value.copy(order = order)
    }
}
