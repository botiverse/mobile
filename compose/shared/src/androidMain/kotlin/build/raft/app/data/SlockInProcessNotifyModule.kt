package build.raft.app.data

import android.os.Handler
import android.os.Looper
import com.tencent.kuikly.core.render.android.css.ktx.isMainThread
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONObject

/**
 * task #354: drop-in replacement for Kuikly's built-in `KRNotifyModule`,
 * registered under the same module name via the host `moduleExport` hook
 * (external registrations run after built-ins and last-wins, so no fork
 * change is needed).
 *
 * Same addNotify/removeNotify/postNotify JSON contract, but events travel
 * through [SlockKuiklyEventHub] instead of a system broadcast: the app is
 * single-process, and the broadcast's Binder parcel crashed on a
 * 1,628,916-byte realtime event (Hands 143077f5) while buying nothing.
 * Callbacks receive the payload on the main thread exactly as the replaced
 * broadcast receiver delivered it.
 */
class SlockInProcessNotifyModule : KuiklyRenderBaseModule() {

    private val callbacksByEvent =
        mutableMapOf<String, MutableList<Pair<String, KuiklyRenderCallback>>>()
    private val hubListener =
        SlockKuiklyEventHub.Listener { eventName, payloadJson ->
            // Snapshot: a callback may add/remove listeners re-entrantly.
            callbacksByEvent[eventName]?.toList()?.forEach { (_, callback) ->
                callback.invoke(payloadJson)
            }
        }
    private var registered = false

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? =
        when (method) {
            METHOD_ADD_NOTIFY -> {
                addNotify(params, callback)
                null
            }
            METHOD_REMOVE_NOTIFY -> {
                removeNotify(params)
                null
            }
            METHOD_POST_NOTIFY -> {
                postNotify(params)
                null
            }
            else -> super.call(method, params, callback)
        }

    override fun onDestroy() {
        super.onDestroy()
        runOnMainThread {
            if (registered) {
                SlockKuiklyEventHub.unregister(hubListener)
                registered = false
            }
            callbacksByEvent.clear()
        }
    }

    private fun addNotify(params: String?, callback: KuiklyRenderCallback?) {
        val cb = callback ?: return
        val jsonObject = JSONObject(params ?: return)
        val eventName = jsonObject.optString(KEY_EVENT_NAME).takeIf { it.isNotEmpty() } ?: return
        val id = jsonObject.optString(KEY_ID).takeIf { it.isNotEmpty() } ?: return
        runOnMainThread {
            callbacksByEvent.getOrPut(eventName) { mutableListOf() }.add(id to cb)
            if (!registered) {
                SlockKuiklyEventHub.register(hubListener)
                registered = true
            }
        }
    }

    private fun removeNotify(params: String?) {
        val jsonObject = JSONObject(params ?: return)
        val eventName = jsonObject.optString(KEY_EVENT_NAME).takeIf { it.isNotEmpty() } ?: return
        val id = jsonObject.optString(KEY_ID).takeIf { it.isNotEmpty() } ?: return
        runOnMainThread {
            val list = callbacksByEvent[eventName] ?: return@runOnMainThread
            val index = list.indexOfFirst { it.first == id }
            if (index >= 0) list.removeAt(index)
            if (list.isEmpty()) callbacksByEvent.remove(eventName)
        }
    }

    private fun postNotify(params: String?) {
        val jsonObject = JSONObject(params ?: return)
        val eventName = jsonObject.optString(KEY_EVENT_NAME).takeIf { it.isNotEmpty() } ?: return
        val data = jsonObject.optJSONObject(KEY_DATA) ?: JSONObject()
        val payload = data.toString()
        runOnMainThread { SlockKuiklyEventHub.publish(eventName, payload) }
    }

    private fun runOnMainThread(block: () -> Unit) {
        if (isMainThread()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post(block)
        }
    }

    companion object {
        /** Registered under the built-in name so shared code needs no change. */
        const val MODULE_NAME = "KRNotifyModule"
        private const val METHOD_ADD_NOTIFY = "addNotify"
        private const val METHOD_REMOVE_NOTIFY = "removeNotify"
        private const val METHOD_POST_NOTIFY = "postNotify"
        private const val KEY_EVENT_NAME = "eventName"
        private const val KEY_DATA = "data"
        private const val KEY_ID = "id"
    }
}
