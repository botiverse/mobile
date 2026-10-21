package build.raft.app.base

import build.raft.app.AttachmentOwnerLease
import build.raft.app.ThreadComposerTemporaryFileRetirement
import build.raft.app.data.RAFT_SOCKET_BASE_URL
import build.raft.app.host.HostCapabilities
import build.raft.app.network.AttachmentDownloadCoordinator
import build.raft.app.raftLogDebug
import build.raft.app.raftLogError
import build.raft.app.raftLogInfo
import build.raft.app.raftLogWarn
import build.raft.app.compat.module.toInt
import build.raft.app.compat.module.CallbackRef
import build.raft.app.compat.module.CallbackFn
import build.raft.app.compat.module.Module
import build.raft.app.compat.module.PerformanceData
import build.raft.app.compat.json.JSONArray
import build.raft.app.compat.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.atomicfu.atomic

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

private val bridgeAttachmentExportOperationSequence = atomic(0L)

private val bridgeThreadImageCompressionOperationSequence = atomic(0L)

private fun nextBridgeThreadImageCompressionRequestId(): String =
    "thread-image-compress-${bridgeThreadImageCompressionOperationSequence.incrementAndGet()}"

internal fun nextBridgeAttachmentExportOperationId(owner: AttachmentOwnerLease): String =
    "${owner.principalKey.value}:${owner.scopeId}:${owner.generation}:" +
        bridgeAttachmentExportOperationSequence.incrementAndGet()

internal class BridgeModule : Module() {
    private val nextAttachmentDownloadId = atomic(0L)

    /** This bridge as the pager-independent [HostCapabilities] the data layer consumes. */
    val hostCapabilities: HostCapabilities by lazy { RaftHostCapabilities(this) }

    override fun moduleName(): String {
        return MODULE_NAME
    }

    fun closePage() {
        callNativeMethod(CLOSE_PAGE, null, null)
    }

    /** iOS host admission only; conversation identity/history remains in the shared store. */
    fun setConversationForwardGestureEnabled(enabled: Boolean) {
        callNativeMethod(
            CONVERSATION_FORWARD_GESTURE,
            JSONObject().apply { put("enabled", enabled) },
            null
        )
    }

    fun log(content: String) {
        log(BridgeLogLevel.Info, content)
    }

    fun log(level: BridgeLogLevel, content: String) {
        // task #25 root fix: logging must NEVER cross the Kuikly bridge.
        // Diagnostics lambdas legitimately fire from worker coroutines, but any
        // bridge call off the context thread trips the fork's release-live
        // KR_ASSERT_CONTEXT_HTREAD and aborts the app (the TestFlight
        // SIGABRTs). RaftPlatformLog is the bridge-free, any-thread-safe
        // channel built for exactly this; hosts route it into their own
        // diagnostics pipelines (Android: RaftAndroidLocalDiagnostics sink,
        // iOS: slockSharedLog notification + console).
        when (level) {
            BridgeLogLevel.Debug -> raftLogDebug(BRIDGE_LOG_TAG, content)
            BridgeLogLevel.Info -> raftLogInfo(BRIDGE_LOG_TAG, content)
            BridgeLogLevel.Warn -> raftLogWarn(BRIDGE_LOG_TAG, content)
            BridgeLogLevel.Error -> raftLogError(BRIDGE_LOG_TAG, content)
        }
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

    suspend fun exportThreadShareImage(
        x: Float?,
        y: Float?,
        width: Float?,
        height: Float?,
        fragments: String? = null,
        frame: Boolean = true
    ): JSONObject? =
        suspendCoroutine { continuation ->
            val methodArgs = JSONObject()
            methodArgs.put("x", x ?: 0f)
            methodArgs.put("y", y ?: 0f)
            methodArgs.put("width", width ?: 0f)
            methodArgs.put("height", height ?: 0f)
            fragments?.takeIf { it.isNotBlank() }?.let { methodArgs.put("fragments", it) }
            methodArgs.put("frame", frame)
            callNativeMethod("exportThreadShareImage", methodArgs) {
                continuation.resume(it)
            }
        }

    suspend fun probeThreadViewportPixels(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        generation: Long
    ): JSONObject? =
        suspendCancellableCoroutine { continuation ->
            val methodArgs = JSONObject()
            methodArgs.put("x", x)
            methodArgs.put("y", y)
            methodArgs.put("width", width)
            methodArgs.put("height", height)
            methodArgs.put("generation", generation)
            var callbackRef: CallbackRef? = null
            var completed = false
            val result =
                toNative(
                    keepCallbackAlive = false,
                    methodName = "probeThreadViewportPixels",
                    param = methodArgs.toString(),
                    callback = { payload ->
                        if (!continuation.isActive || completed) {
                            return@toNative
                        }
                        completed = true
                        callbackRef?.let { removeCallback(it) }
                        continuation.resume(payload)
                    },
                    syncCall = false
                )
            callbackRef = result.callbackRef
            continuation.invokeOnCancellation {
                callbackRef?.let { removeCallback(it) }
            }
        }

    suspend fun saveThreadShareImage(path: String): JSONObject? =
        suspendCoroutine { continuation ->
            val methodArgs = JSONObject()
            methodArgs.put("path", path)
            callNativeMethod("saveThreadShareImage", methodArgs) {
                continuation.resume(it)
            }
        }

    /**
     * Adaptive detail migration (task #18): invokes the native `adaptiveNavigation` transaction
     * API — `prepare` returns a one-shot token + controller identities for a later commit,
     * `commit` performs the guarded top-pop (result arrives as the slockAdaptiveNativeCommitResult
     * event on the target shell, not here), `cancel` releases a prepared transaction. Returns the
     * native reply payload, or null when the bridge method is absent on this platform.
     */
    suspend fun adaptiveNavigation(
        action: String,
        requestId: String,
        token: String? = null,
        /**
         * Reply sink used when the CALLER is cancelled while a native reply is in flight.
         * Dropping the callback would orphan a nonce the native side already accepted —
         * iOS tracks it as request_in_progress until a matching cancel/commit retires it,
         * so a late reply must still reach an owner that can clean it up.
         */
        onLateReply: ((JSONObject) -> Unit)? = null,
        /**
         * Commit-time editor fence inputs (task #27 contract): the Kuikly nativeRef of the
         * resolved AutoHeightTextAreaView and the text confirmed persisted at capture time.
         * Native prepare binds them to the nonce and re-verifies at commit; prepare calls
         * without a valid pair are rejected natively.
         */
        expectedEditorRefTag: Int? = null,
        expectedEditorText: String? = null,
    ): JSONObject? =
        suspendCancellableCoroutine { continuation ->
            val methodArgs =
                buildRaftAdaptiveNavigationMethodArgs(
                    action = action,
                    requestId = requestId,
                    token = token,
                    expectedEditorRefTag = expectedEditorRefTag,
                    expectedEditorText = expectedEditorText,
                )
            var callbackRef: CallbackRef? = null
            var completed = false
            val result =
                toNative(
                    keepCallbackAlive = false,
                    methodName = "adaptiveNavigation",
                    param = methodArgs.toString(),
                    callback = { payload ->
                        if (completed) {
                            return@toNative
                        }
                        completed = true
                        callbackRef?.let { removeCallback(it) }
                        if (continuation.isActive) {
                            continuation.resume(payload)
                        } else {
                            // Caller was cancelled mid-flight: hand the reply to the
                            // registered owner so an accepted nonce is retired (cancel),
                            // never silently lost.
                            payload?.let { onLateReply?.invoke(it) }
                        }
                    },
                    syncCall = false,
                )
            callbackRef = result.callbackRef
            continuation.invokeOnCancellation {
                // Keep the callback registered: a late reply must reach onLateReply so the
                // accepted transaction can be cancelled instead of leaking its nonce.
            }
        }

    /**
     * Fire-and-forget adaptive navigation dispatch (cancel). Shares the no-callback contract
     * with [dispatchAdaptiveCommit]: safe to invoke under NonCancellable during cleanup.
     */
    fun dispatchAdaptiveCancel(requestId: String, token: String?) {
        val methodArgs =
            JSONObject()
                .put("action", "cancel")
                .put("requestId", requestId)
        if (token != null) methodArgs.put("token", token)
        callNativeMethod("adaptiveNavigation", methodArgs, null)
    }

    /**
     * Fire-and-forget commit dispatch for the adaptive detail migration (task #18). iOS
     * deliberately never invokes a source callback for `commit` — the renderer may be torn
     * down mid-pop — so this must NOT suspend on `adaptiveNavigation`. The authoritative
     * result is the `slockAdaptiveNativeCommitResult` event on the target shell.
     */
    fun dispatchAdaptiveCommit(requestId: String, token: String?) {
        val methodArgs =
            JSONObject()
                .put("action", "commit")
                .put("requestId", requestId)
        if (token != null) methodArgs.put("token", token)
        callNativeMethod("adaptiveNavigation", methodArgs, null)
    }

    suspend fun openAttachment(
        url: String,
        attachmentId: String,
        fileName: String,
        owner: AttachmentOwnerLease,
        mimeType: String? = null,
        downloadId: String? = null,
        requirePublicDownload: Boolean = false,
        onProgress: ((BridgeAttachmentDownloadProgress) -> Unit)? = null
    ): JSONObject? =
        suspendCancellableCoroutine { continuation ->
            val resolvedDownloadId = attachmentDownloadId(owner, downloadId, attachmentId, fileName)
            var activeAttemptToken: String? = null
            val methodArgs = JSONObject()
            methodArgs.put("url", url)
            methodArgs.put("attachmentId", attachmentId)
            methodArgs.put("fileName", fileName)
            methodArgs.put("trackProgress", onProgress != null)
            methodArgs.put("downloadId", resolvedDownloadId)
            methodArgs.put("requirePublicDownload", requirePublicDownload)
            methodArgs.putAttachmentOwner(owner)
            mimeType?.takeIf { it.isNotBlank() }?.let { methodArgs.put("mimeType", it) }
            var callbackRef: CallbackRef? = null
            var completed = false
            val result =
                toNative(
                    keepCallbackAlive = onProgress != null,
                    methodName = OPEN_ATTACHMENT,
                    param = methodArgs.toString(),
                    callback = { payload ->
                        if (!continuation.isActive || completed) {
                            return@toNative
                        }
                        if (payload?.optString("status") == "progress") {
                            val progress = payload.toBridgeAttachmentDownloadProgress()
                            activeAttemptToken = progress.attemptToken.takeIf { it.isNotBlank() }
                            onProgress?.invoke(progress)
                            return@toNative
                        }
                        completed = true
                        callbackRef?.let { removeCallback(it) }
                        continuation.resume(payload)
                    },
                    syncCall = false
                )
            callbackRef = result.callbackRef
            continuation.invokeOnCancellation {
                AttachmentDownloadCoordinator.control(
                    downloadId = resolvedDownloadId,
                    expectedAttemptToken = activeAttemptToken,
                    action = "cancel"
                )
                callbackRef?.let { removeCallback(it) }
            }
        }

    suspend fun saveAttachment(
        url: String,
        attachmentId: String,
        fileName: String,
        owner: AttachmentOwnerLease,
        mimeType: String? = null,
        downloadId: String? = null,
        retainAfterPageDestroyed: Boolean = false,
        onProgress: ((BridgeAttachmentDownloadProgress) -> Unit)? = null
    ): JSONObject? = saveAttachmentWithNativeMethod(
        methodName = SAVE_ATTACHMENT,
        url = url,
        attachmentId = attachmentId,
        fileName = fileName,
        owner = owner,
        mimeType = mimeType,
        downloadId = downloadId,
        retainAfterPageDestroyed = retainAfterPageDestroyed,
        onProgress = onProgress
    )

    suspend fun saveImageToPhotos(
        url: String,
        attachmentId: String,
        fileName: String,
        owner: AttachmentOwnerLease,
        mimeType: String? = null,
        downloadId: String? = null,
        onProgress: ((BridgeAttachmentDownloadProgress) -> Unit)? = null
    ): JSONObject? = saveAttachmentWithNativeMethod(
        methodName = SAVE_IMAGE_TO_PHOTOS,
        url = url,
        attachmentId = attachmentId,
        fileName = fileName,
        owner = owner,
        mimeType = mimeType,
        downloadId = downloadId,
        retainAfterPageDestroyed = false,
        onProgress = onProgress
    )

    private suspend fun saveAttachmentWithNativeMethod(
        methodName: String,
        url: String,
        attachmentId: String,
        fileName: String,
        owner: AttachmentOwnerLease,
        mimeType: String?,
        downloadId: String?,
        retainAfterPageDestroyed: Boolean,
        onProgress: ((BridgeAttachmentDownloadProgress) -> Unit)?
    ): JSONObject? =
        suspendCancellableCoroutine { continuation ->
            val resolvedDownloadId = attachmentDownloadId(owner, downloadId, attachmentId, fileName)
            val exportOperationId = nextBridgeAttachmentExportOperationId(owner)
            var activeAttemptToken: String? = null
            val methodArgs = JSONObject()
            methodArgs.put("url", url)
            methodArgs.put("attachmentId", attachmentId)
            methodArgs.put("fileName", fileName)
            methodArgs.put("trackProgress", onProgress != null)
            methodArgs.put("downloadId", resolvedDownloadId)
            methodArgs.put("operationId", exportOperationId)
            methodArgs.putAttachmentOwner(owner)
            mimeType?.takeIf { it.isNotBlank() }?.let { methodArgs.put("mimeType", it) }
            var callbackRef: CallbackRef? = null
            var completed = false
            val result =
                toNative(
                    keepCallbackAlive = onProgress != null,
                    methodName = methodName,
                    param = methodArgs.toString(),
                    callback = { payload ->
                        if (!continuation.isActive || completed) {
                            return@toNative
                        }
                        if (payload?.optString("status") == "progress") {
                            val progress = payload.toBridgeAttachmentDownloadProgress()
                            activeAttemptToken = progress.attemptToken.takeIf { it.isNotBlank() }
                            onProgress?.invoke(progress)
                            return@toNative
                        }
                        completed = true
                        callbackRef?.let { removeCallback(it) }
                        continuation.resume(payload)
                    },
                    syncCall = false,
                    retainAfterDetach = retainAfterPageDestroyed
                )
            callbackRef = result.callbackRef
            continuation.invokeOnCancellation {
                activeAttemptToken?.takeIf(String::isNotBlank)?.let { attemptToken ->
                    AttachmentDownloadCoordinator.controlExisting(
                        downloadId = resolvedDownloadId,
                        expectedAttemptToken = attemptToken,
                        action = "cancel"
                    )
                }
                callNativeMethod(
                    CANCEL_ATTACHMENT_EXPORT,
                    JSONObject().apply {
                        put("downloadId", resolvedDownloadId)
                        put("operationId", exportOperationId)
                        putAttachmentOwner(owner)
                        activeAttemptToken?.takeIf(String::isNotBlank)?.let { put("attemptToken", it) }
                    },
                    null
                )
                callbackRef?.let { removeCallback(it) }
            }
        }

    suspend fun attachmentDownloadControl(
        downloadId: String,
        owner: AttachmentOwnerLease,
        expectedAttemptToken: String? = null,
        expectedRecordVersion: Long? = null,
        action: String
    ): JSONObject {
        val commonStatus =
            AttachmentDownloadCoordinator.control(
                downloadId = downloadId,
                expectedAttemptToken = expectedAttemptToken,
                action = action
            )
        val isStop = action == "cancel" || action == "stop"
        if (commonStatus != "missing" && !isStop) {
            return JSONObject().apply { put("status", commonStatus) }
        }
        return suspendCoroutine { continuation ->
            requestAttachmentDownloadControl(downloadId, owner, expectedAttemptToken, expectedRecordVersion, action) { payload ->
                val nativeStatus = payload?.optString("status").orEmpty()
                continuation.resume(
                    when {
                        nativeStatus.isNotBlank() && nativeStatus != "missing" -> payload!!
                        commonStatus != "missing" -> JSONObject().apply { put("status", commonStatus) }
                        else -> payload ?: JSONObject().apply {
                            put("status", "failed")
                            put("message", "Download control did not return a result")
                        }
                    }
                )
            }
        }
    }

    /**
     * Non-suspending control for app-scoped downloads (the download centre), safe after the page
     * that started the download is destroyed: the common coordinator is controlled directly and
     * the native host is told fire-and-forget.
     */
    fun attachmentDownloadControlDetached(
        downloadId: String,
        owner: AttachmentOwnerLease,
        expectedAttemptToken: String?,
        expectedRecordVersion: Long?,
        action: String
    ) {
        AttachmentDownloadCoordinator.control(
            downloadId = downloadId,
            expectedAttemptToken = expectedAttemptToken,
            action = action
        )
        requestAttachmentDownloadControl(downloadId, owner, expectedAttemptToken, expectedRecordVersion, action, callback = null)
    }

    private fun requestAttachmentDownloadControl(
        downloadId: String,
        owner: AttachmentOwnerLease,
        expectedAttemptToken: String?,
        expectedRecordVersion: Long?,
        action: String,
        callback: ((JSONObject?) -> Unit)? = null
    ) {
        val methodArgs = JSONObject()
        methodArgs.put("downloadId", downloadId)
        methodArgs.putAttachmentOwner(owner)
        methodArgs.put("commonHandled", true)
        expectedAttemptToken?.takeIf { it.isNotBlank() }?.let { methodArgs.put("attemptToken", it) }
        expectedRecordVersion?.let { methodArgs.put("recordVersion", it) }
        methodArgs.put("action", action)
        callNativeMethod(ATTACHMENT_DOWNLOAD_CONTROL, methodArgs, callback)
    }

    private fun attachmentDownloadId(
        owner: AttachmentOwnerLease,
        explicit: String?,
        attachmentId: String,
        fileName: String
    ): String {
        val base = explicit?.trim()?.takeIf { it.isNotBlank() }
            ?: "bridge:${attachmentId.encodeToByteArray().toHexToken()}:" +
                "${fileName.encodeToByteArray().toHexToken()}:${nextAttachmentDownloadId.incrementAndGet()}"
        return "${owner.principalKey.value}:$base"
    }

    suspend fun attachmentCacheStatus(
        attachmentId: String,
        fileName: String,
        owner: AttachmentOwnerLease,
        mimeType: String? = null
    ): JSONObject? =
        attachmentCacheCall(ATTACHMENT_CACHE_STATUS, attachmentId, fileName, owner, mimeType)

    suspend fun deleteAttachmentCache(
        attachmentId: String,
        fileName: String,
        owner: AttachmentOwnerLease,
        expectedAttemptToken: String? = null,
        expectedRecordVersion: Long? = null,
        mimeType: String? = null
    ): JSONObject? =
        attachmentCacheCall(
            DELETE_ATTACHMENT_CACHE,
            attachmentId,
            fileName,
            owner,
            mimeType,
            expectedAttemptToken,
            expectedRecordVersion
        )

    /**
     * Linearizes retirement of cache files owned by an ephemeral File Preview session.
     *
     * The native side must synchronously quarantine each admitted ref (an atomic same-directory
     * rename) before this call returns.  Only the tombstone deletion may be asynchronous.  Keeping
     * the call synchronous is important because shared draft persistence invokes it while holding
     * the process-wide ownership lock; an asynchronous dispatch would re-open a post-dispatch ABA
     * window in which a new draft can adopt the old pathname.
     */
    fun retireFilePreviewTemporaryFiles(
        sessionId: String,
        fileRefs: List<String>
    ): String {
        val cleanSessionId = sessionId.trim()
        val cleanRefs = fileRefs.map(String::trim).filter(String::isNotBlank).distinct()
        if (cleanRefs.isEmpty()) return ""
        // Retirement is a shared ownership transition, not a renderer operation.  Calling the
        // Kuikly sync bridge here is unsafe: persistence can invoke this method from its delayed
        // coroutine worker, while the renderer only accepts synchronous calls on its context
        // thread.  The app-scope registry provides a bridge-free, synchronous quarantine seam;
        // platform handlers atomically rename each admitted file to a sibling tombstone before
        // returning and may delete only those tombstones asynchronously.
        val result = ThreadComposerTemporaryFileRetirement.retire(cleanSessionId, cleanRefs.toSet())
        if (!result.succeeded) {
            raftLogWarn(
                BRIDGE_LOG_TAG,
                "thread_composer_temp_retire_fail sessionId=$cleanSessionId " +
                    "requested=${result.requestedCount} quarantined=${result.quarantinedCount} " +
                    "failed=${result.failedCount} rejected=${result.rejectedCount} " +
                    "errorCode=${result.errorCode.orEmpty()}"
            )
        }
        return JSONObject().apply {
            put("status", if (result.succeeded) "quarantined" else "failed")
            put("requested", result.requestedCount)
            put("admitted", result.admittedCount)
            put("quarantined", result.quarantinedCount)
            put("rejected", result.rejectedCount)
            put("failed", result.failedCount)
            result.errorCode?.let { put("errorCode", it) }
        }.toString()
    }

    suspend fun exportDownloadedAttachment(
        attachmentId: String,
        fileName: String,
        owner: AttachmentOwnerLease,
        mimeType: String? = null
    ): JSONObject? =
        attachmentCacheCall(
            EXPORT_DOWNLOADED_ATTACHMENT,
            attachmentId,
            fileName,
            owner,
            mimeType
        )

    private suspend fun attachmentCacheCall(
        methodName: String,
        attachmentId: String,
        fileName: String,
        owner: AttachmentOwnerLease,
        mimeType: String?,
        expectedAttemptToken: String? = null,
        expectedRecordVersion: Long? = null
    ): JSONObject? =
        suspendCancellableCoroutine { continuation ->
            val methodArgs = JSONObject()
            methodArgs.put("attachmentId", attachmentId)
            methodArgs.put("fileName", fileName)
            methodArgs.putAttachmentOwner(owner)
            expectedAttemptToken?.takeIf(String::isNotBlank)?.let { methodArgs.put("attemptToken", it) }
            expectedRecordVersion?.let { methodArgs.put("recordVersion", it) }
            mimeType?.takeIf { it.isNotBlank() }?.let { methodArgs.put("mimeType", it) }
            val result =
                toNative(
                    methodName = methodName,
                    param = methodArgs.toString(),
                    callback = { payload ->
                        if (continuation.isActive) {
                            continuation.resume(payload)
                        }
                    },
                    syncCall = false
                )
            continuation.invokeOnCancellation {
                result.callbackRef?.let { removeCallback(it) }
            }
        }

    fun attachmentDownloadOwnerChanged(owner: AttachmentOwnerLease?) {
        val methodArgs = JSONObject()
        if (owner == null) {
            methodArgs.put("action", "retire_account")
        } else {
            methodArgs.put("action", "bind")
            methodArgs.putAttachmentOwner(owner)
        }
        callNativeMethod(ATTACHMENT_DOWNLOAD_OWNER_CHANGED, methodArgs, null)
    }

    fun attachmentDownloadScopeRetired(shellInstanceId: String) {
        callNativeMethod(
            ATTACHMENT_DOWNLOAD_OWNER_CHANGED,
            JSONObject().apply {
                put("action", "retire_scope")
                put("shellInstanceId", shellInstanceId)
            },
            null
        )
    }

    private fun JSONObject.putAttachmentOwner(owner: AttachmentOwnerLease): JSONObject =
        put("shellInstanceId", owner.shellInstanceId)
            .put("ownerScopeId", owner.scopeId)
            .put("ownerAuthEpoch", owner.authEpoch)
            .put("ownerKey", owner.principalKey.value)
            .put("ownerGeneration", owner.generation)

    private fun ByteArray.toHexToken(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    /**
     * #81: host bridge for the selected-server app badge. Called from
     * [build.raft.app.data.SharedHomeStore] whenever
     * [build.raft.app.data.AppBadgeCount] changes. The host platform
     * (Android / iOS / desktop) decides how to render the badge
     * (launcher icon, app icon, tray, etc.) from `count`.
     *
     * [source] is passed for diagnostics / logging only. Per the
     * boundary rule from Cindy (2026-06-15 12:59), the host platform
     * MUST NOT branch on [source] (`SERVER_UNREAD_SUMMARY` /
     * `CHANNEL_DM_FALLBACK` / `DISABLED` / `UNKNOWN`) to decide which UI
     * to show, including `ACTIVITY_UNREAD_SUMMARY`. The platform only displays [count].
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

    fun syncDesktopShortcuts(shortcuts: List<build.raft.app.data.RaftDesktopShortcutItem>) {
        syncDesktopShortcuts(build.raft.app.data.RaftDesktopShortcutContract.serializeList(shortcuts))
    }

    fun syncDesktopShortcuts(shortcutsJson: String) {
        val methodArgs = JSONObject()
        methodArgs.put("shortcuts", shortcutsJson)
        callNativeMethod(SYNC_DESKTOP_SHORTCUTS, methodArgs, null)
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

    /**
     * Keeps a native settings capability callback alive until the host marks the response
     * terminal. Used only for fenced, progress-bearing operations such as feedback diagnostics.
     */
    internal fun settingsCapabilityStream(
        command: JSONObject,
        onEvent: (JSONObject?) -> Unit,
    ): () -> Unit {
        var callbackRef: CallbackRef? = null
        var terminalBeforeRegistration = false
        val result =
            toNative(
                keepCallbackAlive = true,
                methodName = "settingsCapability",
                param = command.toString(),
                callback = { payload ->
                    onEvent(payload)
                    if (payload?.optBoolean("terminal", false) == true) {
                        callbackRef?.let { removeCallback(it) }
                            ?: run { terminalBeforeRegistration = true }
                    }
                },
                syncCall = false,
            )
        callbackRef = result.callbackRef
        if (terminalBeforeRegistration) callbackRef?.let { removeCallback(it) }
        return { callbackRef?.let { removeCallback(it) } }
    }

    /**
     * One versioned avatar-cache capability shared by every host. Commands contain only the
     * remote transport URL plus opaque cache identities/policy state; native paths and bytes never
     * return through this bridge.
     */
    suspend fun avatarCacheCapability(command: JSONObject): JSONObject? =
        suspendCancellableCoroutine { continuation ->
            callNativeMethod("avatarCacheCapability", command) {
                if (continuation.isActive) continuation.resume(it)
            }
        }

    suspend fun threadAttachmentUpload(
        channelId: String,
        imageOnly: Boolean,
        capture: Boolean = false,
        compressImages: Boolean = false,
    ): JSONObject? =
        suspendCoroutine { continuation ->
            val methodArgs = JSONObject()
            methodArgs.put("channelId", channelId)
            methodArgs.put("imageOnly", imageOnly)
            methodArgs.put("capture", capture)
            methodArgs.put("compressImages", compressImages)
            callNativeMethod("threadAttachmentUpload", methodArgs) {
                continuation.resume(it)
            }
        }

    /**
     * Compresses one app-owned editor output on the host and returns the new temporary file
     * metadata.  The request id is part of the native cancellation/late-callback fence; it is
     * intentionally generated in shared code rather than inferred from a file path.
     */
    suspend fun compressThreadImage(
        fileRef: String,
        fileName: String,
        mimeType: String,
        requestId: String = nextBridgeThreadImageCompressionRequestId()
    ): JSONObject? =
        suspendCancellableCoroutine { continuation ->
            val cleanRequestId = requestId.trim().ifBlank { nextBridgeThreadImageCompressionRequestId() }
            val methodArgs = JSONObject().apply {
                put("requestId", cleanRequestId)
                put("fileRef", fileRef)
                put("fileName", fileName)
                put("mimeType", mimeType)
            }
            var callbackRef: CallbackRef? = null
            val terminal = atomic(false)
            fun finish(payload: JSONObject?) {
                if (!terminal.compareAndSet(false, true)) return
                callbackRef?.let { removeCallback(it) }
                if (continuation.isActive) continuation.resume(payload)
            }
            val result =
                toNative(
                    keepCallbackAlive = false,
                    methodName = COMPRESS_THREAD_IMAGE,
                    param = methodArgs.toString(),
                    callback = { payload -> finish(payload) },
                    syncCall = false
                )
            callbackRef = result.callbackRef
            // A synchronous native rejection can complete before toNative returns.  Do not leave
            // that callback registered in the renderer in that race.
            if (terminal.value) callbackRef?.let { removeCallback(it) }
            continuation.invokeOnCancellation {
                if (terminal.compareAndSet(false, true)) {
                    callNativeMethod(
                        CANCEL_COMPRESS_THREAD_IMAGE,
                        JSONObject().apply { put("requestId", cleanRequestId) },
                        null
                    )
                }
                callbackRef?.let { removeCallback(it) }
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
        shellInstanceId: String = "",
        socketUrl: String = RAFT_SOCKET_BASE_URL,
        lastSeq: Long = 0L,
        forceReconnect: Boolean = false,
        authorityScopeLeaseId: String,
        authorityPrincipalId: String,
        authorityAuthGeneration: Long,
        authorityConnectionFamilyId: String,
    ) {
        val methodArgs = JSONObject()
        methodArgs.put("action", "connect")
        methodArgs.put("serverId", serverId)
        methodArgs.put("accessToken", accessToken)
        methodArgs.put("shellInstanceId", shellInstanceId)
        methodArgs.put("socketUrl", socketUrl)
        methodArgs.put("lastSeq", lastSeq)
        methodArgs.put("forceReconnect", forceReconnect)
        methodArgs.put("authorityScopeLeaseId", authorityScopeLeaseId)
        methodArgs.put("authorityPrincipalId", authorityPrincipalId)
        methodArgs.put("authorityAuthGeneration", authorityAuthGeneration)
        methodArgs.put("authorityConnectionFamilyId", authorityConnectionFamilyId)
        callNativeMethod(REPORT_REALTIME, methodArgs, null)
    }

    fun realtimeReconcile(
        serverIds: Set<String>,
        accessToken: String,
        environmentId: String,
        socketUrl: String = RAFT_SOCKET_BASE_URL,
        forceReconnect: Boolean = false,
        disconnectReason: String = "auth_invalid",
        authorityScopeLeaseId: String = "",
        authorityPrincipalId: String = "",
        authorityAuthGeneration: Long = 0L,
        authorityConnectionFamilyId: String = "",
    ) {
        val methodArgs = JSONObject()
        val serverIdArray = JSONArray()
        serverIds.map(String::trim).filter(String::isNotEmpty).distinct().sorted().forEach(serverIdArray::put)
        methodArgs.put("action", "reconcile")
        methodArgs.put("serverIds", serverIdArray)
        methodArgs.put("accessToken", accessToken)
        methodArgs.put("environmentId", environmentId)
        methodArgs.put("socketUrl", socketUrl)
        methodArgs.put("forceReconnect", forceReconnect)
        methodArgs.put("disconnectReason", disconnectReason)
        methodArgs.put("authorityScopeLeaseId", authorityScopeLeaseId)
        methodArgs.put("authorityPrincipalId", authorityPrincipalId)
        methodArgs.put("authorityAuthGeneration", authorityAuthGeneration)
        methodArgs.put("authorityConnectionFamilyId", authorityConnectionFamilyId)
        callNativeMethod(REPORT_REALTIME, methodArgs, null)
    }

    fun realtimeDisconnect(
        reason: String = "shared_disconnect",
        serverId: String = "",
        shellInstanceId: String = "",
        explicitClose: Boolean = false
    ) {
        val methodArgs = JSONObject()
        methodArgs.put("action", "disconnect")
        methodArgs.put("reason", reason)
        methodArgs.put("serverId", serverId)
        methodArgs.put("shellInstanceId", shellInstanceId)
        methodArgs.put("explicitClose", explicitClose)
        callNativeMethod(REPORT_REALTIME, methodArgs, null)
    }

    fun realtimeAuthRefreshFinished(reason: String, success: Boolean) {
        val methodArgs = JSONObject()
        methodArgs.put("action", "authRefreshFinished")
        methodArgs.put("reason", reason)
        methodArgs.put("success", success)
        callNativeMethod(REPORT_REALTIME, methodArgs, null)
    }

    fun realtimeCursorAdvance(
        serverId: String,
        appliedSeq: Long,
        authorityScopeLeaseId: String,
        authorityPrincipalId: String,
        authorityAuthGeneration: Long,
        authorityConnectionFamilyId: String,
    ) {
        if (serverId.isBlank() || appliedSeq <= 0L) return
        val methodArgs = JSONObject()
        methodArgs.put("action", "cursorAdvance")
        methodArgs.put("serverId", serverId)
        methodArgs.put("appliedSeq", appliedSeq)
        methodArgs.put("authorityScopeLeaseId", authorityScopeLeaseId)
        methodArgs.put("authorityPrincipalId", authorityPrincipalId)
        methodArgs.put("authorityAuthGeneration", authorityAuthGeneration)
        methodArgs.put("authorityConnectionFamilyId", authorityConnectionFamilyId)
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
        // Shared logs stay under the tag the host diagnostics rings always used
        // for bridge logs, so logcat greps / ring policies keep matching.
        const val BRIDGE_LOG_TAG = "SlockKuikly"
        const val OPEN_PAGE = "openPage"
        const val CLOSE_PAGE = "closePage"
        const val CONVERSATION_FORWARD_GESTURE = "conversationForwardGesture"
        const val PERFORMANCE_MARKER = "performanceMarker"
        const val OPEN_ATTACHMENT = "openAttachment"
        const val SAVE_ATTACHMENT = "saveAttachment"
        const val SAVE_IMAGE_TO_PHOTOS = "saveImageToPhotos"
        const val CANCEL_ATTACHMENT_EXPORT = "cancelAttachmentExport"
        const val ATTACHMENT_CACHE_STATUS = "attachmentCacheStatus"
        const val DELETE_ATTACHMENT_CACHE = "deleteAttachmentCache"
        const val RETIRE_FILE_PREVIEW_TEMPORARY_FILES = "retireFilePreviewTemporaryFiles"
        const val EXPORT_DOWNLOADED_ATTACHMENT = "exportDownloadedAttachment"
        const val ATTACHMENT_DOWNLOAD_CONTROL = "attachmentDownloadControl"
        const val ATTACHMENT_DOWNLOAD_OWNER_CHANGED = "attachmentDownloadOwnerChanged"
        const val SSO_REQUEST = "ssoRequest"
        const val QQ_LIVE_SSO_REQUEST = "qqLiveSSORequest"
        const val REPORT_DT = "reportDT"
        const val LOCAL_SERVE_TIME = "localServeTime"
        const val CURRENT_TIMESTAMP = "currentTimestamp"
        const val DATE_FORMATTER = "dateFormatter"
        const val REPORT_REALTIME = "reportRealTime"
        const val MOTION_CAPABILITY = "motionCapability"
        const val MOTION_EVENT = "slockMotionEvent"
        const val REPORT_PAGE_COST_TIME_FOR_CACHE = "reportPageCostTimeForCache"
        const val REPORT_PAGE_COST_TIME_FOR_SUCCESS = "reportPageCostTimeForSuccess"
        const val REPORT_PAGE_COST_TIME_FOR_ERROR = "reportPageCostTimeForError"
        const val REMOTE_CONFIG = "loadRemoteConfig"
        const val SIGN_ALERT = "signAlert"
        const val CLOSE_KEYBOARD = "closeKeyboard"
        const val COMPRESS_THREAD_IMAGE = "compressThreadImage"
        const val CANCEL_COMPRESS_THREAD_IMAGE = "cancelCompressThreadImage"
        const val URL_ENCODE = "urlEncode"
        const val URL_DECODE = "urlDecode"
        const val SHOW_PHOTO_BROWSER = "showPhotoBrowser"
        const val HUMAN_VERIFICATION = "humanVerification"
        const val SYNC_DESKTOP_SHORTCUTS = "syncDesktopShortcuts"
    }

}

internal data class BridgeAttachmentDownloadProgress(
    val downloadId: String = "",
    val attemptToken: String = "",
    val sequence: Long = 0L,
    val recordVersion: Long? = null,
    val phase: String = "receiving",
    val status: String = "progress",
    val percent: Int?,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val bytesPerSecond: Long? = null
)

internal fun JSONObject.toBridgeAttachmentDownloadProgress(): BridgeAttachmentDownloadProgress {
    val totalBytes = optString("totalBytes").toLongOrNull()?.takeIf { it > 0L }
    val downloadedBytes = (optString("downloadedBytes").toLongOrNull() ?: 0L).coerceAtLeast(0L)
    val bytesPerSecond = optString("bytesPerSecond").toLongOrNull()?.takeIf { it > 0L }
    val explicitPercent = optString("percent").toIntOrNull()?.coerceIn(0, 100)
    val percent =
        when {
            explicitPercent == 100 -> 100
            downloadedBytes <= 0L -> null
            explicitPercent != null -> explicitPercent.coerceIn(1, 99)
            totalBytes != null ->
                ((downloadedBytes.toDouble() * 100.0) / totalBytes.toDouble())
                    .toInt()
                    .coerceIn(1, 99)
            else -> null
        }
    return BridgeAttachmentDownloadProgress(
        downloadId = optString("downloadId"),
        attemptToken = optString("attemptToken"),
        sequence = optString("sequence").toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
        recordVersion = optString("recordVersion").toLongOrNull(),
        phase = optString("phase").ifBlank { if (downloadedBytes > 0L) "receiving" else "response_wait" },
        status = optString("status").ifBlank { "progress" },
        percent = percent,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        bytesPerSecond = bytesPerSecond
    )
}

/**
 * Request-scoped monotonic reducer for attachment transfer samples.
 *
 * Native callbacks and render scheduling are asynchronous. A late sample must never rewind the
 * visible byte count, percentage, or sequence for the active download attempt.
 */
internal fun reduceAttachmentDownloadProgress(
    current: BridgeAttachmentDownloadProgress?,
    incoming: BridgeAttachmentDownloadProgress,
    expectedDownloadId: String
): BridgeAttachmentDownloadProgress? {
    if (incoming.downloadId.isBlank() || incoming.downloadId != expectedDownloadId) return current
    if (current == null || current.downloadId != incoming.downloadId) return incoming
    if (current.attemptToken.isNotBlank() && incoming.attemptToken.isNotBlank() &&
        current.attemptToken != incoming.attemptToken
    ) {
        return if (
            incoming.sequence == 1L &&
                (incoming.phase == "response_wait" || incoming.phase == "export_insert")
        ) {
            incoming
        } else {
            current
        }
    }
    if (incoming.sequence > 0L && current.sequence > 0L && incoming.sequence <= current.sequence) return current
    if (incoming.downloadedBytes < current.downloadedBytes) return current
    val currentPercent = current.percent
    val incomingPercent = incoming.percent
    if (currentPercent != null && incomingPercent != null && incomingPercent < currentPercent) return current
    return incoming.copy(
        totalBytes = incoming.totalBytes ?: current.totalBytes,
        percent = incomingPercent ?: currentPercent?.takeIf { incoming.downloadedBytes > 0L }
    )
}

/**
 * Serialized args for the `adaptiveNavigation` bridge call. Kept as a pure function so the
 * JSON wire shape is unit-testable without a native runtime — in particular `refTag` must
 * stay a JSON number: iOS `slockAdaptiveEditorWithRefTag:` binds only a finite non-negative
 * NSNumber, so a stringly ref can never resolve an editor instance (task #18).
 */
internal fun buildRaftAdaptiveNavigationMethodArgs(
    action: String,
    requestId: String,
    token: String? = null,
    expectedEditorRefTag: Int? = null,
    expectedEditorText: String? = null,
): JSONObject {
    val methodArgs =
        JSONObject()
            .put("action", action)
            .put("requestId", requestId)
    if (token != null) methodArgs.put("token", token)
    if (expectedEditorRefTag != null || expectedEditorText != null) {
        methodArgs.put(
            "expectedEditor",
            JSONObject().apply {
                // Explicit branch: omit refTag entirely when absent — JSONObject.NULL is an
                // org.json-ism not portable across the fork's JSON impls, and a null literal
                // would break iOS's NSNumber check the same way a string did.
                if (expectedEditorRefTag != null) put("refTag", expectedEditorRefTag)
                put("text", expectedEditorText ?: "")
            },
        )
    }
    return methodArgs
}

