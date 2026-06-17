package ai.slock.compose.base

import ai.slock.compose.data.SLOCK_SOCKET_BASE_URL
import com.tencent.kuikly.core.base.toInt
import com.tencent.kuikly.core.module.CallbackFn
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.module.PerformanceData
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal enum class BridgeLogLevel(
    val wireValue: String
) {
    Debug("debug"),
    Info("info"),
    Warn("warn"),
    Error("error");

    companion object {
        fun fromWire(value: String?): BridgeLogLevel =
            when (value?.trim()?.lowercase()) {
                Debug.wireValue -> Debug
                Warn.wireValue -> Warn
                Error.wireValue,
                "fatal" -> Error
                else -> Info
            }
    }
}

internal fun BridgeLogLevel.mirrorsToConsole(): Boolean = this != BridgeLogLevel.Debug

internal class BridgeModule : Module() {

    override fun moduleName(): String {
        return MODULE_NAME
    }

    fun closePage() {
        callNativeMethod(CLOSE_PAGE, null, null)
    }

    fun log(content: String) {
        log(BridgeLogLevel.Info, content)
    }

    fun log(level: BridgeLogLevel, content: String) {
        val methodArgs = JSONObject()
        methodArgs.put("content", content)
        methodArgs.put("level", level.wireValue)
        callNativeMethod(LOG, methodArgs, null)
    }

    fun logDebug(content: String) {
        log(BridgeLogLevel.Debug, content)
    }

    fun logWarn(content: String) {
        log(BridgeLogLevel.Warn, content)
    }

    fun logError(content: String) {
        log(BridgeLogLevel.Error, content)
    }

    fun enterPerformanceScene(sceneName: String) {
        performanceScene("enter", sceneName)
    }

    fun exitPerformanceScene(sceneName: String) {
        performanceScene("exit", sceneName)
    }

    fun performanceMarker(
        phase: String,
        routeName: String,
        event: String = "instant",
        sceneId: String? = null,
        threadId: String? = null,
        durationMs: Long? = null,
        metadata: Map<String, String> = emptyMap()
    ) {
        val cleanPhase = phase.trim().takeIf { it.isNotBlank() } ?: return
        val cleanRouteName = routeName.trim().takeIf { it.isNotBlank() } ?: "unknown"
        val cleanEvent = event.trim().takeIf { it.isNotBlank() } ?: "instant"
        val threadHash = threadId.stableShortHash()
        val cleanSceneId =
            sceneId?.trim()?.takeIf { it.isNotBlank() }
                ?: "$cleanRouteName:$threadHash"
        val methodArgs =
            JSONObject().apply {
                put("sceneId", cleanSceneId)
                put("routeName", cleanRouteName)
                put("phase", cleanPhase)
                put("event", cleanEvent)
                put("threadHash", threadHash)
                durationMs?.takeIf { it >= 0L }?.let { put("durationMs", it) }
                if (metadata.isNotEmpty()) {
                    put(
                        "metadata",
                        JSONObject().apply {
                            metadata
                                .filterKeys { key -> key.isNotBlank() }
                                .forEach { (key, value) ->
                                    put(key.take(40), value.take(120))
                                }
                        }
                    )
                }
            }
        callNativeMethod(PERFORMANCE_MARKER, methodArgs, null)
    }

    private fun performanceScene(action: String, sceneName: String) {
        val cleanScene = sceneName.trim().takeIf { it.isNotBlank() } ?: return
        val methodArgs = JSONObject()
        methodArgs.put("action", action)
        methodArgs.put("sceneName", cleanScene)
        callNativeMethod("performanceScene", methodArgs, null)
    }

    fun reportKuiklyPerformanceSnapshot(
        routeName: String,
        reason: String,
        threadId: String?,
        data: PerformanceData?,
        statusOverride: String? = null
    ): String {
        val cleanRouteName = routeName.trim().ifBlank { "unknown" }
        val cleanReason = reason.trim().ifBlank { "unknown" }
        val status = statusOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: if (data == null) "missing" else "content"
        val threadHash = threadId.stableShortHash()
        val metrics = JSONObject()
        val dimensions = JSONObject()
        val methodArgs = JSONObject()

        dimensions.put("route", cleanRouteName)
        dimensions.put("reason", cleanReason)
        dimensions.put("status", status)
        dimensions.put("threadHash", threadHash)

        if (data != null) {
            val mode = data.mode.name
            dimensions.put("mode", mode)
            dimensions.put("firstProcessLaunch", data.isFirstLaunchOfProcess.toString())
            dimensions.put("firstPageLaunch", data.isFirstLaunchOfPage.toString())
            metrics.put("pageExistTime", data.pageExistTime)
            data.mainFPS?.let { metrics.put("mainFPS", it) }
            data.kotlinFPS?.let { metrics.put("kotlinFPS", it) }
            data.pageLoadTime?.let { launch ->
                metrics.put("launchFirstPaintCost", launch.firstPaintCost)
                metrics.put("launchInitViewCost", launch.initViewCost)
                metrics.put("launchPreloadClassCost", launch.preloadClassCost)
                metrics.put("launchFetchContextCodeCost", launch.fetchContextCodeCost)
                metrics.put("launchInitRenderContextCost", launch.initRenderContextCost)
                metrics.put("launchInitRenderCoreCost", launch.initRenderCoreCost)
                metrics.put("launchNewPageCost", launch.newPageCost)
                metrics.put("launchPageBuildCost", launch.pageBuildCost)
                metrics.put("launchPageLayoutCost", launch.pageLayoutCost)
                metrics.put("launchCreatePageCost", launch.createPageCost)
                metrics.put("launchCreateInstanceCost", launch.createInstanceCost)
                metrics.put("launchRenderCost", launch.renderCost)
            }
            data.memory?.let { memory ->
                metrics.put("memoryAvgIncrement", memory.avgIncrement)
                metrics.put("memoryPeakIncrement", memory.peakIncrement)
                metrics.put("memoryAppPeak", memory.appPeak)
                metrics.put("memoryAppAvg", memory.appAvg)
            }
        }
        val summary = buildKuiklyPerformanceSnapshotSummary(
            routeName = cleanRouteName,
            reason = cleanReason,
            status = status,
            threadHash = threadHash,
            data = data
        )

        methodArgs.put("routeName", cleanRouteName)
        methodArgs.put("reason", cleanReason)
        methodArgs.put("status", status)
        methodArgs.put("threadHash", threadHash)
        methodArgs.put("summary", summary)
        methodArgs.put("metrics", metrics)
        methodArgs.put("dimensions", dimensions)
        callNativeMethod("kuiklyPerformanceSnapshot", methodArgs, null)
        return summary
    }

    private fun buildKuiklyPerformanceSnapshotSummary(
        routeName: String,
        reason: String,
        status: String,
        threadHash: String,
        data: PerformanceData?
    ): String {
        val launch = data?.pageLoadTime
        return buildString {
            append("route=$routeName reason=$reason status=$status threadHash=$threadHash")
            append(" firstPaintMs=${launch?.firstPaintCost ?: "na"}")
            append(" mainFPS=${data?.mainFPS ?: "na"}")
            append(" kotlinFPS=${data?.kotlinFPS ?: "na"}")
            append(" pageExistMs=${data?.pageExistTime ?: "na"}")
            data?.mode?.name?.let { mode -> append(" mode=$mode") }
            data?.let {
                append(" firstProcess=${it.isFirstLaunchOfProcess} firstPage=${it.isFirstLaunchOfPage}")
            }
            launch?.let {
                append(" buildMs=${it.pageBuildCost}")
                append(" layoutMs=${it.pageLayoutCost}")
                append(" renderMs=${it.renderCost}")
            }
            data?.memory?.let { memory ->
                append(" memoryAvg=${memory.appAvg}")
                append(" memoryPeak=${memory.appPeak}")
                append(" memoryAvgDelta=${memory.avgIncrement}")
                append(" memoryPeakDelta=${memory.peakIncrement}")
            }
        }
    }

    private fun String?.stableShortHash(): String {
        val value = this?.takeIf { it.isNotBlank() } ?: return "unknown"
        var hash = 2166136261L
        value.forEach { char ->
            hash = hash xor char.code.toLong()
            hash = (hash * 16777619L) and 0xffffffffL
        }
        return hash.toString(16)
    }

    fun copyToPasteboard(content: String) {
        val methodArgs = JSONObject()
        methodArgs.put("content", content)
        callNativeMethod("copyToPasteboard", methodArgs, null)
    }

    fun showAlert(
        title: String?,
        message: String?,
        leftBtnTitle: String?,
        rightBtnTitle: String?,
        responseCallbackFn: CallbackFn
    ) {
        val methodArgs = JSONObject()
        val buttonArray = JSONArray()
        leftBtnTitle?.also {
            buttonArray.put(it)
        }
        rightBtnTitle?.also {
            buttonArray.put(it)
        }

        methodArgs.put("buttons", buttonArray)
        title?.also {
            methodArgs.put("title", it)
        }
        message?.also {
            methodArgs.put("message", it)
        }
        callNativeMethod("showAlert", methodArgs) {
            responseCallbackFn(it)
        }
    }

    // 拨打电话
    fun callPhone(phoneNumber: String) {
        val methodArgs = JSONObject()
        methodArgs.put("phoneNumber", phoneNumber)
        callNativeMethod("callPhone", methodArgs, null)
    }

    fun toast(content: String) {
        val methodArgs = JSONObject()
        methodArgs.put("content", content)
        callNativeMethod("toast", methodArgs, null)
    }

    fun openUrl(url: String, mimeType: String? = null) {
        val methodArgs = JSONObject()
        methodArgs.put("url", url)
        mimeType?.takeIf { it.isNotBlank() }?.let { methodArgs.put("mimeType", it) }
        callNativeMethod("openUrl", methodArgs, null)
    }

    suspend fun openAttachment(
        url: String,
        attachmentId: String,
        fileName: String,
        mimeType: String? = null
    ): JSONObject? =
        suspendCoroutine { continuation ->
            val methodArgs = JSONObject()
            methodArgs.put("url", url)
            methodArgs.put("attachmentId", attachmentId)
            methodArgs.put("fileName", fileName)
            mimeType?.takeIf { it.isNotBlank() }?.let { methodArgs.put("mimeType", it) }
            callNativeMethod(OPEN_ATTACHMENT, methodArgs) {
                continuation.resume(it)
            }
        }

    fun settingsEvent(instanceId: String, action: String, itemId: String? = null) {
        val methodArgs = JSONObject()
        methodArgs.put("instanceId", instanceId)
        methodArgs.put("action", action)
        itemId?.let { methodArgs.put("itemId", it) }
        callNativeMethod("settingsEvent", methodArgs, null)
    }

    /**
     * #81: host bridge for the selected-server app badge. Called from
     * [ai.slock.compose.data.SharedHomeStore] whenever
     * [ai.slock.compose.data.AppBadgeCount] changes. The host platform
     * (Android / OHOS / iOS / desktop) decides how to render the badge
     * (launcher icon, app icon, tray, etc.) from `count`.
     *
     * [source] is passed for diagnostics / logging only. Per the
     * boundary rule from Cindy (2026-06-15 12:59), the host platform
     * MUST NOT branch on [source] (`SERVER_UNREAD_SUMMARY` /
     * `CHANNEL_DM_FALLBACK` / `DISABLED` / `UNKNOWN`) to decide which UI
     * to show. The platform only displays [count].
     *
     * For [count] = 0 the host MAY keep the previous badge visible
     * (Android / iOS launcher icon convention) or clear it. The choice
     * is platform-specific.
     */
    fun appBadge(count: Int, source: String, isStale: Boolean) {
        val methodArgs = JSONObject()
        methodArgs.put("count", count)
        methodArgs.put("source", source)
        methodArgs.put("isStale", isStale)
        callNativeMethod("appBadge", methodArgs, null)
    }

    suspend fun authRequest(command: JSONObject): JSONObject? =
        suspendCoroutine { continuation ->
            callNativeMethod(AUTH_REQUEST, command) {
                continuation.resume(it)
            }
        }

    suspend fun socialAuth(provider: String): JSONObject? =
        suspendCoroutine { continuation ->
            val methodArgs = JSONObject()
            methodArgs.put("provider", provider)
            callNativeMethod(SOCIAL_AUTH, methodArgs) {
                continuation.resume(it)
            }
        }

    suspend fun motionCapability(command: JSONObject): JSONObject? =
        suspendCoroutine { continuation ->
            callNativeMethod(MOTION_CAPABILITY, command) {
                continuation.resume(it)
            }
        }

    fun motionStartOperatingHand() {
        motionCommand("startOperatingHand")
    }

    fun motionStopOperatingHand() {
        motionCommand("stopOperatingHand")
    }

    suspend fun motionGetRecentOperatingHand(): JSONObject? =
        motionCapability(JSONObject().apply { put("action", "getRecentOperatingHand") })

    fun motionStartHoldingHand() {
        motionCommand("startHoldingHand")
    }

    fun motionStopHoldingHand() {
        motionCommand("stopHoldingHand")
    }

    fun motionStopAll() {
        motionCommand("stopAll")
    }

    suspend fun settingsCapability(command: JSONObject): JSONObject? =
        suspendCoroutine { continuation ->
            callNativeMethod("settingsCapability", command) {
                continuation.resume(it)
            }
        }

    suspend fun threadAttachmentUpload(channelId: String, imageOnly: Boolean): JSONObject? =
        suspendCoroutine { continuation ->
            val methodArgs = JSONObject()
            methodArgs.put("channelId", channelId)
            methodArgs.put("imageOnly", imageOnly)
            callNativeMethod("threadAttachmentUpload", methodArgs) {
                continuation.resume(it)
            }
        }

    fun openPage(
        url: String,
        closeCurPage: Boolean = false,
        closeSamePage: Boolean = false,
        userData: JSONObject? = null,
        callbackFn: CallbackFn? = null
    ) {
        val methodArgs = JSONObject()
        methodArgs.put("url", url)
        methodArgs.put("closeCurPage", closeCurPage.toInt())
        methodArgs.put("closeSamePage", closeSamePage.toInt())
        userData?.also {
            methodArgs.put("userData", it)
        }
        callNativeMethod(OPEN_PAGE, methodArgs, callbackFn)
    }

    suspend fun ssoRequest(cmd: String, reqParams: JSONObject): JSONObject? {
        return suspendCoroutine<JSONObject?> { continuation ->
            ssoRequest(cmd, reqParams) {
                continuation.resume(it)
            }
        }
    }

    fun ssoRequest(cmd: String, reqParams: JSONObject, responseCallbackFn: CallbackFn) {
        val methodArgs = JSONObject()
        methodArgs.put("cmd", cmd)
        methodArgs.put("reqParam", reqParams)
        callNativeMethod(SSO_REQUEST, methodArgs, responseCallbackFn)
    }

    fun qqLiveSSORequest(
        service: String,
        method: String,
        reqParams: JSONObject,
        responseCallbackFn: CallbackFn
    ) {
        val methodArgs = JSONObject()
        methodArgs.put("service", service)
        methodArgs.put("method", method)
        methodArgs.put("reqParams", reqParams)
        callNativeMethod(QQ_LIVE_SSO_REQUEST, methodArgs, responseCallbackFn)
    }

    // 灯塔上报
    fun reportDT(eventCode: String, data: JSONObject) {
        val methodArgs = JSONObject()
        methodArgs.put("eventCode", eventCode)
        methodArgs.put("data", data)
        // methodArgs.put("realtime", 1)
        callNativeMethod(REPORT_DT, methodArgs, null)
    }

    // 实时上报
    fun reportRealTime(eventCode: String, data: JSONObject) {
        val methodArgs = JSONObject()
        methodArgs.put("eventCode", eventCode)
        methodArgs.put("data", data)
        callNativeMethod(REPORT_REALTIME, methodArgs, null)
    }

    fun realtimeConnect(
        serverId: String,
        accessToken: String,
        socketUrl: String = SLOCK_SOCKET_BASE_URL,
        lastSeq: Long = 0L,
        forceReconnect: Boolean = false
    ) {
        val methodArgs = JSONObject()
        methodArgs.put("action", "connect")
        methodArgs.put("serverId", serverId)
        methodArgs.put("accessToken", accessToken)
        methodArgs.put("socketUrl", socketUrl)
        methodArgs.put("lastSeq", lastSeq)
        methodArgs.put("forceReconnect", forceReconnect)
        callNativeMethod(REPORT_REALTIME, methodArgs, null)
    }

    fun realtimeDisconnect(reason: String = "shared_disconnect") {
        val methodArgs = JSONObject()
        methodArgs.put("action", "disconnect")
        methodArgs.put("reason", reason)
        callNativeMethod(REPORT_REALTIME, methodArgs, null)
    }

    fun realtimeJoinChannel(channelId: String) {
        val methodArgs = JSONObject()
        methodArgs.put("action", "joinChannel")
        methodArgs.put("channelId", channelId)
        callNativeMethod(REPORT_REALTIME, methodArgs, null)
    }

    fun realtimeAuthRefreshFinished(reason: String, success: Boolean) {
        val methodArgs = JSONObject()
        methodArgs.put("action", "authRefreshFinished")
        methodArgs.put("reason", reason)
        methodArgs.put("success", success)
        callNativeMethod(REPORT_REALTIME, methodArgs, null)
    }

    // 页面首屏（有内容，来自缓存）耗时上报
    fun reportPageCostTimeForCache() {
        callNativeMethod(REPORT_PAGE_COST_TIME_FOR_CACHE, null, null)
    }

    // 页面首屏（有内容，来自后台）耗时上报
    fun reportPageCostTimeForSuccess() {
        callNativeMethod(REPORT_PAGE_COST_TIME_FOR_SUCCESS, null, null)
    }

    // 页面首屏耗时上报 - 加载失败
    fun reportPageCostTimeForError() {
        callNativeMethod(REPORT_PAGE_COST_TIME_FOR_ERROR, null, null)
    }

    fun openSelectAddressView(addressData: JSONObject?, callbackFn: CallbackFn) {
        val methodArgs = JSONObject()
        addressData?.apply {
            methodArgs.put("addressData", addressData)
            methodArgs.put("from", 5)
        }

        callNativeMethod("openSelectAddressView", methodArgs) {
            callbackFn(it)
        }
    }

    fun openApplySampleSuccessPage(
        orderId: String,
        shopId: String,
        spuId: String,
        skuId: String,
        priSortId: String
    ) {
        val methodArgs = JSONObject()
        methodArgs.put("orderId", orderId)
        methodArgs.put("shopId", shopId)
        methodArgs.put("spuId", spuId)
        methodArgs.put("skuId", skuId)
        methodArgs.put("priSortId", priSortId)

        callNativeMethod("openApplySampleSuccessPage", methodArgs, null)
    }

    // 异步获取本地服务器时间戳
    fun localServeTime(cb: CallbackFn) {
        callNativeMethod(LOCAL_SERVE_TIME, null, cb)
    }

    //同步获取本地服务器时间戳
    suspend fun localServeTime(): JSONObject? {
        return suspendCoroutine<JSONObject?> { continuation ->
            localServeTime() {
                continuation.resume(it)
            }
        }
    }

    // 同步获取时间戳（毫秒）
    // 注：一般不用于业务，仅为本地性能耗时测试
    fun currentTimeStamp(): Long {
        val timestamp = syncCallNativeMethod(CURRENT_TIMESTAMP, null, null)
        if (timestamp.isNotEmpty()) {
            return timestamp.toLong()
        } else {
            return 0
        }
    }

    // 同步获取日期格式化
    fun dateFormatter(timeStamp: Long, format: String): String {
        val params = JSONObject()
        params.put("timeStamp", timeStamp)
        params.put("format", format)
        return syncCallNativeMethod(DATE_FORMATTER, params, null)
    }

    /**
     * 根据 [key] 获取本地缓存的数据, 异步返回
     */
    fun fetchCachedFromNative(key: String, callbackFn: CallbackFn) {
        val param = JSONObject().apply {
            put("key", key)
        }
        callNativeMethod("fetchCachedFromNative", param) {
            callbackFn(it)
        }
    }

    /**
     * 根据 [key] 获取本地缓存的数据, 同步返回
     */
    fun getCachedFromNative(key: String): String {
        val param = JSONObject().apply {
            put("key", key)
        }
        return syncCallNativeMethod("getCachedFromNative", param, null)
    }

    /**
     * 向 native 写入 [key] 对应的缓存
     */
    fun setCachedToNative(key: String, value: String, callbackFn: CallbackFn? = null) {
        val param = JSONObject().apply {
            put("key", key)
            put("value", value)
        }
        callNativeMethod("setCachedToNative", param) {
            callbackFn?.invoke(it)
        }
    }

    /**
     * 预下载图片、PAG、APNG资源
     * */
    fun preDownloadImage(url: String, callbackFn: CallbackFn? = null) {
        val params = JSONObject().apply {
            put("url", url)
        }
        callNativeMethod("preDownloadImage", params) {
            if (callbackFn != null) {
                callbackFn(it)
            }
        }
    }

    fun preDownloadPAGResource(url: String) {
        val params = JSONObject().apply {
            put("url", url)
        }
        callNativeMethod("preDownloadPAGResource", params, null)
    }

    fun preDownloadAPNGResource(url: String) {
        val params = JSONObject().apply {
            put("url", url)
        }
        callNativeMethod("preDownloadAPNGResource", params, null)
    }

    // 更新离线包
    fun updateOfflineIfNeed(bid: String) {
        val params = JSONObject().apply {
            put("bid", bid)
        }
        callNativeMethod("updateOfflineIfNeed", params, null)
    }

    fun showSignJumpAlert(params: JSONObject): String {
        return syncCallNativeMethod(SIGN_ALERT, params, null)
    }

    fun closeKeyboard(data: JSONObject? = null, callbackFn: CallbackFn? = null): String {
        return syncCallNativeMethod(CLOSE_KEYBOARD, data, callbackFn)
    }

    fun humanVerification(params: JSONObject, callbackFn: CallbackFn? = null): String {
        return syncCallNativeMethod(HUMAN_VERIFICATION, params, callbackFn)
    }

    fun urlEncode(string: String): String {
        val params = JSONObject()
        params.put("string", string)
        return syncCallNativeMethod(URL_ENCODE, params, null)
    }

    fun urlDecode(string: String): String {
        val params = JSONObject()
        params.put("string", string)
        return syncCallNativeMethod(URL_DECODE, params, null)
    }

    private fun callNativeMethod(methodName: String, data: JSONObject?, callbackFn: CallbackFn?) {
        toNative(
            false,
            methodName,
            data?.toString(),
            callbackFn,
            false
        )
    }

    // --------- 同步调用Native方法 -------
    private fun syncCallNativeMethod(
        methodName: String,
        data: JSONObject?,
        callbackFn: CallbackFn?
    ): String {
        return toNative(
            false,
            methodName,
            data?.toString(),
            callbackFn,
            true
        ).toString()
    }

    private fun motionCommand(action: String) {
        callNativeMethod(
            MOTION_CAPABILITY,
            JSONObject().apply { put("action", action) },
            null
        )
    }

    companion object {
        const val MODULE_NAME = "HRBridgeModule"
        const val OPEN_PAGE = "openPage"
        const val CLOSE_PAGE = "closePage"
        const val LOG = "log"
        const val PERFORMANCE_MARKER = "performanceMarker"
        const val OPEN_ATTACHMENT = "openAttachment"
        const val SSO_REQUEST = "ssoRequest"
        const val QQ_LIVE_SSO_REQUEST = "qqLiveSSORequest"
        const val REPORT_DT = "reportDT"
        const val LOCAL_SERVE_TIME = "localServeTime"
        const val CURRENT_TIMESTAMP = "currentTimestamp"
        const val DATE_FORMATTER = "dateFormatter"
        const val REPORT_REALTIME = "reportRealTime"
        const val AUTH_REQUEST = "authRequest"
        const val SOCIAL_AUTH = "socialAuth"
        const val MOTION_CAPABILITY = "motionCapability"
        const val MOTION_EVENT = "slockMotionEvent"
        const val REPORT_PAGE_COST_TIME_FOR_CACHE = "reportPageCostTimeForCache"
        const val REPORT_PAGE_COST_TIME_FOR_SUCCESS = "reportPageCostTimeForSuccess"
        const val REPORT_PAGE_COST_TIME_FOR_ERROR = "reportPageCostTimeForError"
        const val REMOTE_CONFIG = "loadRemoteConfig"
        const val SIGN_ALERT = "signAlert"
        const val CLOSE_KEYBOARD = "closeKeyboard"
        const val URL_ENCODE = "urlEncode"
        const val URL_DECODE = "urlDecode"
        const val SHOW_PHOTO_BROWSER = "showPhotoBrowser"
        const val HUMAN_VERIFICATION = "humanVerification"
    }

}
