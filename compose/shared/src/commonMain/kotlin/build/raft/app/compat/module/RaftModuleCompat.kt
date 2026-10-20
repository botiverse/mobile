package build.raft.app.compat.module

import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.raftLogWarn
import build.raft.app.raftSha256Hex
import build.raft.app.toJsonObject
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/*
 * TODO(cmp-switch): Kuikly `Module` on top of the CMP host's bridge transport.
 *
 * `BridgeModule` (HRBridgeModule) and Kuikly's built-in Router/Codec/Performance modules keep
 * their API; native calls go over `RaftCmpHost.bridgeTransport` (the same HRBridgeModule wire the
 * Kuikly renderer used) and callbacks are delivered on the main thread, where CMP composes.
 * Synchronous native calls (`syncCall = true`) go through `HostBridgeTransport.callSync`; a host
 * without a synchronous path for the method gets the call sent asynchronously and the caller an
 * empty value (as before).
 */

typealias CallbackFn = (data: JSONObject?) -> Unit
typealias AnyCallbackFn = (data: Any?) -> Unit
typealias CallbackRef = String

fun Boolean.toInt(): Int = if (this) 1 else 0

private val moduleCallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
private val callbackSequence = atomic(0L)

abstract class Module {
    var pagerId: String = ""
        private set
    var pageData: PageData? = null
        private set
    protected var pager: Pager? = null
        private set

    private val liveCallbacks = HashSet<CallbackRef>()

    /** Set once the owning page is destroyed: native results arriving later are dropped. */
    private var detached = false

    abstract fun moduleName(): String

    internal fun attach(pager: Pager) {
        this.pager = pager
        this.pagerId = pager.pagerId
        this.pageData = pager.pageData
    }

    /**
     * The owning page was destroyed ([build.raft.app.compat.pager.PagerManager.unregister]): drop
     * every pending callback so a late native result never runs a destroyed page's callback
     * (which could e.g. openPage / closePage on a finished Activity). Calls made afterwards are
     * still sent (fire-and-forget cleanup), but their callbacks never run.
     */
    internal fun detach() {
        detached = true
        liveCallbacks.clear()
    }

    class ReturnValue(val callbackRef: CallbackRef?, val returnValue: Any? = null) {
        override fun toString(): String = returnValue?.toString() ?: ""
    }

    fun toNative(
        keepCallbackAlive: Boolean = false,
        methodName: String,
        param: Any?,
        callback: CallbackFn? = null,
        syncCall: Boolean = false,
    ): ReturnValue {
        val transport = pager?.host?.bridgeTransport
        if (transport == null) {
            raftLogWarn("RaftModuleCompat", "no_bridge_transport module=${moduleName()} method=$methodName")
            return ReturnValue(null)
        }
        if (syncCall) {
            val syncResult = runCatching { transport.callSync(methodName, param?.toString().orEmpty()) }.getOrNull()
            if (syncResult != null) return ReturnValue(null, syncResult)
            raftLogWarn("RaftModuleCompat", "sync_call_async module=${moduleName()} method=$methodName")
        }
        val ref: CallbackRef? = callback?.let { "cb-${callbackSequence.incrementAndGet()}" }
        if (ref != null && !detached) liveCallbacks.add(ref)
        transport.call(methodName, param?.toString().orEmpty(), if (callback == null) null else { result ->
            moduleCallbackScope.launch {
                if (ref == null || detached || ref !in liveCallbacks) return@launch
                if (!keepCallbackAlive) liveCallbacks.remove(ref)
                callback(result?.let { text -> runCatching { JSONObject(text) }.getOrNull() })
            }
        })
        return ReturnValue(ref)
    }

    fun asyncToNativeMethod(methodName: String, data: JSONObject?, callbackFn: CallbackFn?) {
        toNative(false, methodName, data?.toString(), callbackFn, false)
    }

    fun syncToNativeMethod(methodName: String, data: JSONObject?, callbackFn: CallbackFn?): String =
        toNative(false, methodName, data?.toString(), callbackFn, true).toString()

    fun removeCallback(callbackRef: CallbackRef) {
        liveCallbacks.remove(callbackRef)
    }
}

/** Kuikly `RouterModule`: page navigation through the CMP host navigator. */
class RouterModule : Module() {
    override fun moduleName(): String = MODULE_NAME

    fun openPage(pageName: String, pageData: JSONObject? = null) {
        val navigator = pager?.host?.navigator
        if (navigator == null) {
            raftLogWarn("RaftModuleCompat", "no_navigator openPage=$pageName")
            return
        }
        navigator.openPage(pageName, (pageData ?: JSONObject()).toJsonObject())
    }

    fun closePage() {
        pager?.host?.navigator?.closePage()
    }

    companion object {
        const val MODULE_NAME = "KRRouterModule"
    }
}

/** Kuikly `CodecModule`, implemented in Kotlin. */
class CodecModule : Module() {
    override fun moduleName(): String = MODULE_NAME

    fun sha256(string: String): String = raftSha256Hex(string.encodeToByteArray())

    companion object {
        const val MODULE_NAME = "KRCodecModule"
    }
}

/** Kuikly `PerformanceModule`: the Kuikly render pipeline metrics no longer exist on CMP. */
class PerformanceModule : Module() {
    override fun moduleName(): String = MODULE_NAME

    /** TODO(cmp-switch): always answers null (no Kuikly launch metrics on CMP). */
    fun getPerformanceData(callback: (PerformanceData?) -> Unit) {
        callback(null)
    }

    companion object {
        const val MODULE_NAME = "KRPerformanceModule"
    }
}

enum class ExecuteMode(val value: Int) { JVM(0), FRAMEWORK(1), JS(2), OHOS_NATIVE(4) }

data class PerformanceData(
    val pageExistTime: Int,
    val mode: ExecuteMode,
    val isFirstLaunchOfProcess: Boolean,
    val isFirstLaunchOfPage: Boolean,
    val pageLoadTime: LaunchData?,
    val mainFPS: Int?,
    val kotlinFPS: Int?,
    val memory: MemoryData?,
)

data class LaunchData(
    val initViewCost: Int,
    val preloadClassCost: Int,
    val fetchContextCodeCost: Int,
    val initRenderContextCost: Int,
    val initRenderCoreCost: Int,
    val initRenderLayerReadCacheCost: Int,
    val initRenderLayerRenderCacheCost: Int,
    val newPageCost: Int,
    val pageBuildCost: Int,
    val pageLayoutCost: Int,
    val createPageCost: Int,
    val createInstanceCost: Int,
    val renderCost: Int,
    val firstPaintCost: Int,
)

data class MemoryData(val avgIncrement: Int, val peakIncrement: Int, val appPeak: Int, val appAvg: Int)
