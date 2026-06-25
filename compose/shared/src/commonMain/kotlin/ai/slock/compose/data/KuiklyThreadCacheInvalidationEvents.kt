package ai.slock.compose.data

import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

private const val THREAD_CACHE_INVALIDATION_EVENT = "slock.threadCacheInvalidated"
private const val KEY_SEQUENCE = "sequence"
private const val KEY_SERVER_ID = "serverId"
private const val KEY_THREAD_IDS = "threadIds"
private const val KEY_BROADCAST = "broadcast"

internal class KuiklyThreadCacheInvalidationEvents(
    private val pager: Pager
) {
    private val module: NotifyModule
        get() = pager.acquireModule(NotifyModule.MODULE_NAME)

    fun addListener(onInvalidated: (ThreadRealtimeCacheInvalidationSignal) -> Unit): CallbackRef =
        module.addNotify(THREAD_CACHE_INVALIDATION_EVENT) { data ->
            data?.toThreadRealtimeCacheInvalidationSignal()?.let(onInvalidated)
        }

    fun removeListener(callbackRef: CallbackRef) {
        module.removeNotify(THREAD_CACHE_INVALIDATION_EVENT, callbackRef)
    }

    fun postInvalidation(signal: ThreadRealtimeCacheInvalidationSignal) {
        module.postNotify(THREAD_CACHE_INVALIDATION_EVENT, signal.toJson())
    }
}

private fun ThreadRealtimeCacheInvalidationSignal.toJson(): JSONObject =
    JSONObject()
        .put(KEY_SEQUENCE, sequence.toString())
        .put(KEY_SERVER_ID, serverId)
        .put(
            KEY_THREAD_IDS,
            JSONArray().apply {
                threadIds.forEach(::put)
            }
        )
        .put(KEY_BROADCAST, broadcast)

private fun JSONObject.toThreadRealtimeCacheInvalidationSignal(): ThreadRealtimeCacheInvalidationSignal? {
    val serverId = optString(KEY_SERVER_ID).takeIf { it.isNotBlank() } ?: return null
    val sequence = optString(KEY_SEQUENCE).toLongOrNull() ?: return null
    val threadIds =
        buildSet {
            val array = optJSONArray(KEY_THREAD_IDS) ?: return@buildSet
            for (index in 0 until array.length()) {
                array.optString(index)?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    return ThreadRealtimeCacheInvalidationSignal(
        sequence = sequence,
        serverId = serverId,
        threadIds = threadIds,
        broadcast = optBoolean(KEY_BROADCAST)
    )
}
