package build.raft.app.cmp.host

import build.raft.app.host.HostCapabilities
import build.raft.app.parseSlockJsonObjectOrNull
import build.raft.app.toSlockJsonString
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.coroutines.resume

/**
 * One native bridge method call, in the `HRBridgeModule` wire format both native hosts already
 * implement for Kuikly pages: a method name, its params as JSON text, and a callback that receives
 * the result payload as JSON text (null when the host returns none).
 *
 * Hosts may invoke [callback] on any thread and, for streaming methods, more than once; dropping
 * late or extra results is the caller's job (see [BridgeWireHostCapabilities]).
 */
fun interface HostBridgeTransport {
    fun call(method: String, paramsJson: String, callback: ((String?) -> Unit)?)
}

/**
 * [HostCapabilities] for CMP pages that reuses the native implementations behind the Kuikly bridge.
 *
 * Every capability is encoded exactly as the Kuikly `BridgeModule` encodes it (method names, param
 * keys and order, Kuikly-compatible JSON text via [toSlockJsonString]), so the native side cannot
 * tell a CMP page from a Kuikly page. Keep the two in step until `BridgeModule` is deleted.
 */
class BridgeWireHostCapabilities(
    private val transport: HostBridgeTransport,
) : HostCapabilities {
    private val compressionRequestSequence = atomic(0L)

    override fun toast(content: String) = send("toast", params("content" to content))

    override fun copyToPasteboard(content: String) = send("copyToPasteboard", params("content" to content))

    override fun openUrl(url: String, mimeType: String?) =
        send(
            "openUrl",
            params("url" to url, "mimeType" to mimeType?.takeIf { it.isNotBlank() }),
        )

    override suspend fun settingsCapability(command: JsonObject): JsonObject? = request("settingsCapability", command)

    override fun settingsCapabilityStream(
        command: JsonObject,
        onEvent: (JsonObject?) -> Unit,
    ): () -> Unit {
        val active = atomic(true)
        transport.call("settingsCapability", command.toSlockJsonString()) { result ->
            if (!active.value) return@call
            val payload = parseResult(result)
            if (payload?.get("terminal")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true) {
                if (!active.compareAndSet(true, false)) return@call
            }
            onEvent(payload)
        }
        return { active.value = false }
    }

    override suspend fun socialAuth(provider: String): JsonObject? =
        request("socialAuth", params("provider" to provider))

    override suspend fun threadAttachmentUpload(
        channelId: String,
        imageOnly: Boolean,
        capture: Boolean,
        compressImages: Boolean,
    ): JsonObject? =
        request(
            "threadAttachmentUpload",
            params(
                "channelId" to channelId,
                "imageOnly" to imageOnly,
                "capture" to capture,
                "compressImages" to compressImages,
            ),
        )

    override suspend fun compressThreadImage(fileRef: String, fileName: String, mimeType: String): JsonObject? {
        val requestId = "cmp-thread-image-compress-${compressionRequestSequence.incrementAndGet()}"
        return suspendCancellableCoroutine { continuation ->
            val terminal = atomic(false)
            continuation.invokeOnCancellation {
                if (terminal.compareAndSet(false, true)) {
                    send("cancelCompressThreadImage", params("requestId" to requestId))
                }
            }
            transport.call(
                "compressThreadImage",
                params(
                    "requestId" to requestId,
                    "fileRef" to fileRef,
                    "fileName" to fileName,
                    "mimeType" to mimeType,
                ).toSlockJsonString(),
            ) { result ->
                if (terminal.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(parseResult(result))
                }
            }
        }
    }

    override suspend fun avatarCacheCapability(command: JsonObject): JsonObject? =
        request("avatarCacheCapability", command)

    override fun realtimeConnect(
        serverId: String,
        accessToken: String,
        shellInstanceId: String,
        socketUrl: String,
        lastSeq: Long,
        forceReconnect: Boolean,
        authorityScopeLeaseId: String,
        authorityPrincipalId: String,
        authorityAuthGeneration: Long,
        authorityConnectionFamilyId: String,
    ) = send(
        REPORT_REALTIME,
        params(
            "action" to "connect",
            "serverId" to serverId,
            "accessToken" to accessToken,
            "shellInstanceId" to shellInstanceId,
            "socketUrl" to socketUrl,
            "lastSeq" to lastSeq,
            "forceReconnect" to forceReconnect,
            "authorityScopeLeaseId" to authorityScopeLeaseId,
            "authorityPrincipalId" to authorityPrincipalId,
            "authorityAuthGeneration" to authorityAuthGeneration,
            "authorityConnectionFamilyId" to authorityConnectionFamilyId,
        ),
    )

    override fun realtimeReconcile(
        serverIds: Set<String>,
        accessToken: String,
        environmentId: String,
        socketUrl: String,
        forceReconnect: Boolean,
        disconnectReason: String,
        authorityScopeLeaseId: String,
        authorityPrincipalId: String,
        authorityAuthGeneration: Long,
        authorityConnectionFamilyId: String,
    ) = send(
        REPORT_REALTIME,
        params(
            "action" to "reconcile",
            "serverIds" to
                JsonArray(
                    serverIds.map(String::trim).filter(String::isNotEmpty).distinct().sorted().map(::JsonPrimitive),
                ),
            "accessToken" to accessToken,
            "environmentId" to environmentId,
            "socketUrl" to socketUrl,
            "forceReconnect" to forceReconnect,
            "disconnectReason" to disconnectReason,
            "authorityScopeLeaseId" to authorityScopeLeaseId,
            "authorityPrincipalId" to authorityPrincipalId,
            "authorityAuthGeneration" to authorityAuthGeneration,
            "authorityConnectionFamilyId" to authorityConnectionFamilyId,
        ),
    )

    override fun realtimeDisconnect(
        reason: String,
        serverId: String,
        shellInstanceId: String,
        explicitClose: Boolean,
    ) = send(
        REPORT_REALTIME,
        params(
            "action" to "disconnect",
            "reason" to reason,
            "serverId" to serverId,
            "shellInstanceId" to shellInstanceId,
            "explicitClose" to explicitClose,
        ),
    )

    override fun realtimeAuthRefreshFinished(reason: String, success: Boolean) =
        send(
            REPORT_REALTIME,
            params("action" to "authRefreshFinished", "reason" to reason, "success" to success),
        )

    override fun realtimeCursorAdvance(
        serverId: String,
        appliedSeq: Long,
        authorityScopeLeaseId: String,
        authorityPrincipalId: String,
        authorityAuthGeneration: Long,
        authorityConnectionFamilyId: String,
    ) {
        if (serverId.isBlank() || appliedSeq <= 0L) return
        send(
            REPORT_REALTIME,
            params(
                "action" to "cursorAdvance",
                "serverId" to serverId,
                "appliedSeq" to appliedSeq,
                "authorityScopeLeaseId" to authorityScopeLeaseId,
                "authorityPrincipalId" to authorityPrincipalId,
                "authorityAuthGeneration" to authorityAuthGeneration,
                "authorityConnectionFamilyId" to authorityConnectionFamilyId,
            ),
        )
    }

    override fun appBadge(count: Int, source: String, isStale: Boolean) =
        send("appBadge", params("count" to count, "source" to source, "isStale" to isStale))

    override fun syncDesktopShortcuts(shortcutsJson: String) =
        send("syncDesktopShortcuts", params("shortcuts" to shortcutsJson))

    private fun send(method: String, params: JsonObject) {
        transport.call(method, params.toSlockJsonString(), null)
    }

    private suspend fun request(method: String, params: JsonObject): JsonObject? =
        suspendCancellableCoroutine { continuation ->
            val delivered = atomic(false)
            transport.call(method, params.toSlockJsonString()) { result ->
                if (delivered.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(parseResult(result))
                }
            }
        }

    private fun parseResult(result: String?): JsonObject? = parseSlockJsonObjectOrNull(result)

    private companion object {
        const val REPORT_REALTIME = "reportRealTime"

        /** Builds params in argument order; null values are omitted, like the bridge's `?.let { put }`. */
        fun params(vararg entries: Pair<String, Any?>): JsonObject {
            val map = LinkedHashMap<String, JsonElement>()
            for ((key, value) in entries) {
                map[key] =
                    when (value) {
                        null -> continue
                        is JsonElement -> value
                        is String -> JsonPrimitive(value)
                        is Boolean -> JsonPrimitive(value)
                        is Number -> JsonPrimitive(value)
                        else -> error("Unsupported bridge param type for $key")
                    }
            }
            return JsonObject(map)
        }
    }
}
