package build.raft.webcontainer.jsb

import build.raft.webcontainer.WebContainerOrigin
import build.raft.webcontainer.WebIsolationScope
import build.raft.webcontainer.WebOfflineBundleDescriptor
import build.raft.webcontainer.createWebContainerLock
import build.raft.webcontainer.pool.WebContainerTelemetryEvent
import build.raft.webcontainer.pool.WebContainerTelemetrySink
import build.raft.webcontainer.webContainerMonotonicMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

const val JSB_WIRE_VERSION: Int = 1
const val DEFAULT_JSB_PORT_NAME: String = "raftBridgePort"

/**
 * Security and execution policy for the Kotlin JSB runtime.
 *
 * Disabled by default ([Disabled]); enabling requires explicit [allowedOrigins].
 */
data class JsbPolicy(
    val enabled: Boolean = false,
    val allowedOrigins: Set<WebContainerOrigin> = emptySet(),
    val grantedCapabilities: Set<String> = emptySet(),
    val defaultTimeoutMs: Long = 15_000L,
    val maxPayloadBytes: Int = 256 * 1024,
    val requireMainFrame: Boolean = true,
    val portName: String = DEFAULT_JSB_PORT_NAME,
) {
    init {
        require(defaultTimeoutMs > 0L) { "defaultTimeoutMs must be > 0." }
        require(maxPayloadBytes > 0) { "maxPayloadBytes must be > 0." }
        require(PortNamePattern.matches(portName)) { "Invalid JSB portName '$portName'." }
    }

    val isEffectivelyEnabled: Boolean
        get() = enabled && allowedOrigins.isNotEmpty()

    companion object {
        private val PortNamePattern = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
        val Disabled: JsbPolicy = JsbPolicy(enabled = false)
    }
}

/** Stable wire error codes returned to web callers when a JSB call fails. */
enum class JsbErrorCode(val wireCode: String) {
    BRIDGE_DISABLED("BRIDGE_DISABLED"),
    INVALID_MESSAGE("INVALID_MESSAGE"),
    UNSUPPORTED_VERSION("UNSUPPORTED_VERSION"),
    PAYLOAD_TOO_LARGE("PAYLOAD_TOO_LARGE"),
    ORIGIN_DENIED("ORIGIN_DENIED"),
    SUBFRAME_DENIED("SUBFRAME_DENIED"),
    CAPABILITY_DENIED("CAPABILITY_DENIED"),
    METHOD_NOT_FOUND("METHOD_NOT_FOUND"),
    INVALID_PARAMS("INVALID_PARAMS"),
    TIMEOUT("TIMEOUT"),
    CANCELLED("CANCELLED"),
    INTERNAL_ERROR("INTERNAL_ERROR"),
}

/** Result returned by a Kotlin JSB handler. */
sealed interface JsbResult {
    data class Success(val data: JsonElement = JsonObject(emptyMap())) : JsbResult

    data class Failure(
        val code: JsbErrorCode,
        val message: String,
        val details: JsonElement? = null,
    ) : JsbResult
}

/** Context passed to each registered Kotlin JSB method handler. */
data class JsbCallContext(
    val callId: String,
    val method: String,
    val origin: WebContainerOrigin,
    val documentUrl: String,
    val surfaceId: String,
    val isolationScope: WebIsolationScope,
    val isMainFrame: Boolean,
)

/** Specification of a single JSB method implemented in Kotlin. */
data class JsbMethodSpec(
    val method: String,
    val requiredCapabilities: Set<String> = emptySet(),
    val timeoutMs: Long? = null,
    val handler: suspend (JsbCallContext, JsonObject) -> JsbResult,
) {
    init {
        require(method.isNotBlank()) { "JSB method name must not be blank." }
        require(timeoutMs == null || timeoutMs > 0L) { "timeoutMs must be > 0 when specified." }
    }
}

/**
 * Pure Kotlin registry for JSB methods shared across Android, iOS, and Desktop.
 *
 * Host code registers handlers once in `commonMain`; platform hosts dispatch incoming web messages
 * through [JsbDispatcher].
 */
class JsbRegistry {
    private val lock = createWebContainerLock()
    private val methods = LinkedHashMap<String, JsbMethodSpec>()

    fun register(spec: JsbMethodSpec): JsbRegistry {
        lock.withLock { methods[spec.method] = spec }
        return this
    }

    fun register(
        method: String,
        requiredCapabilities: Set<String> = emptySet(),
        timeoutMs: Long? = null,
        handler: suspend (JsbCallContext, JsonObject) -> JsbResult,
    ): JsbRegistry = register(JsbMethodSpec(method, requiredCapabilities, timeoutMs, handler))

    fun unregister(method: String): Boolean = lock.withLock { methods.remove(method) != null }

    fun resolve(method: String): JsbMethodSpec? = lock.withLock { methods[method] }

    fun registeredMethods(): Set<String> = lock.withLock { methods.keys.toSet() }

    fun isEmpty(): Boolean = lock.withLock { methods.isEmpty() }

    companion object {
        val Empty: JsbRegistry = JsbRegistry()
    }
}

/** Decoded inbound JSB RPC request envelope (`v=1`). */
data class JsbInboundRequest(
    val version: Int,
    val id: String,
    val method: String,
    val params: JsonObject,
)

/** Strict JSON wire codec for JSB v1 requests, responses, and host->web events. */
object JsbWireCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val BestEffortIdRegex = Regex(""""id"\s*:\s*"([^"\\]{1,128})"""")

    sealed interface DecodeOutcome {
        data class Valid(val request: JsbInboundRequest) : DecodeOutcome

        data class Invalid(
            val callId: String?,
            val code: JsbErrorCode,
            val message: String,
        ) : DecodeOutcome
    }

    /**
     * Extracts `"id"` from a bounded prefix (first 512 chars) without parsing the full payload, so
     * early security gates (disabled / oversize / subframe / origin denied) reject without JSON
     * parsing overhead while still returning the request `id` so JS Promises settle cleanly.
     */
    fun extractBestEffortCallId(raw: String): String? {
        if (raw.isEmpty()) return null
        val prefix = if (raw.length > 512) raw.substring(0, 512) else raw
        return BestEffortIdRegex.find(prefix)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun decodeRequest(raw: String): DecodeOutcome {
        val root =
            try {
                json.parseToJsonElement(raw).jsonObject
            } catch (_: Throwable) {
                return DecodeOutcome.Invalid(null, JsbErrorCode.INVALID_MESSAGE, "Malformed JSON envelope")
            }

        val id = (root["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim().orEmpty()
        val version = (root["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (version == null) {
            return DecodeOutcome.Invalid(
                id.ifEmpty { null },
                JsbErrorCode.INVALID_MESSAGE,
                "Missing integer protocol version 'v'",
            )
        }
        if (version != JSB_WIRE_VERSION) {
            return DecodeOutcome.Invalid(
                id.ifEmpty { null },
                JsbErrorCode.UNSUPPORTED_VERSION,
                "Unsupported JSB protocol version $version (expected $JSB_WIRE_VERSION)",
            )
        }
        if (id.isEmpty()) {
            return DecodeOutcome.Invalid(null, JsbErrorCode.INVALID_MESSAGE, "Missing non-empty request 'id'")
        }
        val method = (root["method"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim().orEmpty()
        if (method.isEmpty()) {
            return DecodeOutcome.Invalid(id, JsbErrorCode.INVALID_MESSAGE, "Missing non-empty 'method'")
        }
        val paramsElement = root["params"]
        val params =
            when (paramsElement) {
                null -> JsonObject(emptyMap())
                is JsonObject -> paramsElement
                else -> return DecodeOutcome.Invalid(id, JsbErrorCode.INVALID_PARAMS, "'params' must be a JSON object")
            }
        return DecodeOutcome.Valid(JsbInboundRequest(version = version, id = id, method = method, params = params))
    }

    fun encodeResponse(
        callId: String?,
        result: JsbResult,
    ): String {
        val obj =
            buildJsonObject {
                put("v", JSB_WIRE_VERSION)
                if (callId != null) put("id", callId)
                when (result) {
                    is JsbResult.Success -> {
                        put("ok", true)
                        put("data", result.data)
                    }
                    is JsbResult.Failure -> {
                        put("ok", false)
                        put(
                            "error",
                            buildJsonObject {
                                put("code", result.code.wireCode)
                                put("message", result.message)
                                if (result.details != null) put("details", result.details)
                            },
                        )
                    }
                }
            }
        return obj.toString()
    }

    fun encodeEvent(
        name: String,
        payload: JsonElement = JsonObject(emptyMap()),
    ): String =
        buildJsonObject {
            put("v", JSB_WIRE_VERSION)
            put("type", "event")
            put("name", name)
            put("payload", payload)
        }.toString()
}

/**
 * Cross-platform JSB runtime dispatcher enforcing the 5-layer security gate, per-call coroutine
 * timeout, host-driven cancellation on navigation/crash/dispose, thread-safe tracking, and
 * guaranteed single-shot reply + telemetry delivery.
 */
class JsbDispatcher(
    private val policy: JsbPolicy,
    private val registry: JsbRegistry,
    private val scope: CoroutineScope,
    private val surfaceId: String = "default",
    private val isolationScope: WebIsolationScope = WebIsolationScope.Ephemeral,
    private val telemetrySinkProvider: () -> WebContainerTelemetrySink? = { null },
    private val nowMs: () -> Long = ::webContainerMonotonicMillis,
) {
    constructor(
        policy: JsbPolicy,
        registry: JsbRegistry,
        scope: CoroutineScope,
        surfaceId: String = "default",
        isolationScope: WebIsolationScope = WebIsolationScope.Ephemeral,
        telemetrySink: WebContainerTelemetrySink?,
        nowMs: () -> Long = ::webContainerMonotonicMillis,
    ) : this(
        policy = policy,
        registry = registry,
        scope = scope,
        surfaceId = surfaceId,
        isolationScope = isolationScope,
        telemetrySinkProvider = { telemetrySink },
        nowMs = nowMs,
    )

    private class ActiveCall(
        val callId: String,
        val method: String,
        val startedAtMs: Long,
        val replyOnce: (String) -> Unit,
        var job: Job? = null,
        var cancelledByHostReason: String? = null,
    )

    private val stateLock = createWebContainerLock()
    private val activeCalls = LinkedHashMap<String, ActiveCall>()
    private var nextInternalKey = 0L

    val activeCallCount: Int
        get() = stateLock.withLock { activeCalls.size }

    /**
     * Dispatches an incoming raw message string from a platform bridge and invokes [onReply]
     * strictly at most once with the serialized JSON response.
     */
    fun dispatch(
        rawPayload: String,
        origin: WebContainerOrigin?,
        documentUrl: String,
        isMainFrame: Boolean,
        onReply: (replyJson: String) -> Unit,
    ) {
        val startedAt = nowMs()
        val replyLock = createWebContainerLock()
        var replied = false
        val replyOnce: (String) -> Unit = { payload ->
            val shouldSend =
                replyLock.withLock {
                    if (replied) {
                        false
                    } else {
                        replied = true
                        true
                    }
                }
            if (shouldSend) {
                onReply(payload)
            }
        }

        val quickCallId = JsbWireCodec.extractBestEffortCallId(rawPayload)

        // Gate 1: Bridge enabled + payload byte size cap (before any full JSON parsing).
        if (!policy.isEffectivelyEnabled) {
            emitFailure(quickCallId, "", JsbErrorCode.BRIDGE_DISABLED, "JSB bridge is disabled", startedAt, replyOnce)
            return
        }
        val payloadBytes = rawPayload.encodeToByteArray().size
        if (payloadBytes > policy.maxPayloadBytes) {
            emitFailure(
                quickCallId,
                "",
                JsbErrorCode.PAYLOAD_TOO_LARGE,
                "Payload exceeds maxPayloadBytes (${policy.maxPayloadBytes})",
                startedAt,
                replyOnce,
            )
            return
        }

        // Gate 2: Main-frame enforcement (before full JSON parsing).
        if (policy.requireMainFrame && !isMainFrame) {
            emitFailure(
                quickCallId,
                "",
                JsbErrorCode.SUBFRAME_DENIED,
                "Subframe JSB calls are denied",
                startedAt,
                replyOnce,
            )
            return
        }

        // Gate 3: Exact origin allowlist (before full JSON parsing).
        if (origin == null || origin !in policy.allowedOrigins) {
            emitFailure(
                quickCallId,
                "",
                JsbErrorCode.ORIGIN_DENIED,
                "Origin is not permitted for JSB",
                startedAt,
                replyOnce,
            )
            return
        }

        // Gate 4: Strict protocol v1 schema + method lookup.
        val request =
            when (val decoded = JsbWireCodec.decodeRequest(rawPayload)) {
                is JsbWireCodec.DecodeOutcome.Valid -> decoded.request
                is JsbWireCodec.DecodeOutcome.Invalid -> {
                    emitFailure(decoded.callId ?: quickCallId, "", decoded.code, decoded.message, startedAt, replyOnce)
                    return
                }
            }
        val spec = registry.resolve(request.method)
        if (spec == null) {
            emitFailure(
                request.id,
                request.method,
                JsbErrorCode.METHOD_NOT_FOUND,
                "Unknown JSB method '${request.method}'",
                startedAt,
                replyOnce,
            )
            return
        }

        // Gate 5: Per-method capability allowlist.
        val missingCapability = spec.requiredCapabilities.firstOrNull { it !in policy.grantedCapabilities }
        if (missingCapability != null) {
            emitFailure(
                request.id,
                request.method,
                JsbErrorCode.CAPABILITY_DENIED,
                "Capability '$missingCapability' is not granted",
                startedAt,
                replyOnce,
            )
            return
        }

        val activeCall = ActiveCall(request.id, request.method, startedAt, replyOnce)
        val callKey =
            stateLock.withLock {
                val key = "${++nextInternalKey}:${request.id}"
                activeCalls[key] = activeCall
                key
            }
        val callContext =
            JsbCallContext(
                callId = request.id,
                method = request.method,
                origin = origin,
                documentUrl = documentUrl,
                surfaceId = surfaceId,
                isolationScope = isolationScope,
                isMainFrame = isMainFrame,
            )
        val timeoutMs = spec.timeoutMs ?: policy.defaultTimeoutMs
        val job =
            scope.launch {
                val result: JsbResult =
                    try {
                        withTimeout(timeoutMs) {
                            spec.handler(callContext, request.params)
                        }
                    } catch (_: TimeoutCancellationException) {
                        JsbResult.Failure(JsbErrorCode.TIMEOUT, "Method '${request.method}' timed out after ${timeoutMs}ms")
                    } catch (cancelled: CancellationException) {
                        val hostReason = stateLock.withLock { activeCall.cancelledByHostReason }
                        if (hostReason != null) {
                            // cancelInFlight already recorded telemetry and replied once.
                            return@launch
                        }
                        stateLock.withLock { activeCalls.remove(callKey) }
                        val cancelRes = JsbResult.Failure(JsbErrorCode.CANCELLED, "Call scope cancelled")
                        recordTelemetry(request.method, cancelRes, startedAt)
                        replyOnce(JsbWireCodec.encodeResponse(request.id, cancelRes))
                        throw cancelled
                    } catch (error: Throwable) {
                        JsbResult.Failure(
                            JsbErrorCode.INTERNAL_ERROR,
                            error.message?.take(200) ?: (error::class.simpleName ?: "Handler error"),
                        )
                    } finally {
                        stateLock.withLock { activeCalls.remove(callKey) }
                    }
                val alreadyCancelledByHost = stateLock.withLock { activeCall.cancelledByHostReason != null }
                if (!alreadyCancelledByHost) {
                    recordTelemetry(request.method, result, startedAt)
                    replyOnce(JsbWireCodec.encodeResponse(request.id, result))
                }
            }
        val shouldCancelImmediately =
            stateLock.withLock {
                activeCall.job = job
                activeCall.cancelledByHostReason != null
            }
        if (shouldCancelImmediately) {
            job.cancel(CancellationException("JSB cancelled before launch bind"))
        }
    }

    /**
     * Cancels all in-flight JSB calls (e.g., on main-frame navigation, renderer crash, or dispose),
     * settling each caller once with [JsbErrorCode.CANCELLED] and emitting telemetry once.
     */
    fun cancelInFlight(reason: String) {
        val snapshot =
            stateLock.withLock {
                if (activeCalls.isEmpty()) return
                val copy = activeCalls.values.toList()
                activeCalls.clear()
                for (call in copy) {
                    call.cancelledByHostReason = reason
                }
                copy
            }
        for (call in snapshot) {
            val failure = JsbResult.Failure(JsbErrorCode.CANCELLED, "Call cancelled: $reason")
            recordTelemetry(call.method, failure, call.startedAtMs)
            call.replyOnce(JsbWireCodec.encodeResponse(call.callId, failure))
            call.job?.cancel(CancellationException("JSB cancelled: $reason"))
        }
    }

    /** Builds a safe JS snippet delivering a host event to `window.raftBridge` on allowed origins. */
    fun buildEventDispatchScript(
        eventName: String,
        payload: JsonElement = JsonObject(emptyMap()),
    ): String? {
        if (!policy.isEffectivelyEnabled || eventName.isBlank()) return null
        val encoded = JsbWireCodec.encodeEvent(eventName, payload)
        val escapedJsonLiteral = JsonPrimitive(encoded).toString()
        val originsJson = policy.allowedOrigins.joinToString(",") { JsonPrimitive(it.asOriginString()).toString() }
        return "(function(){try{var a=[$originsJson];if(a.indexOf(window.location.origin)===-1)return;" +
            "var b=window.raftBridge;if(b&&typeof b.__dispatchEvent==='function'){b.__dispatchEvent($escapedJsonLiteral);}}catch(_){}})();"
    }

    private fun emitFailure(
        callId: String?,
        method: String,
        code: JsbErrorCode,
        message: String,
        startedAtMs: Long,
        replyOnce: (String) -> Unit,
    ) {
        val failure = JsbResult.Failure(code, message)
        recordTelemetry(method, failure, startedAtMs)
        replyOnce(JsbWireCodec.encodeResponse(callId, failure))
    }

    private fun recordTelemetry(
        method: String,
        result: JsbResult,
        startedAtMs: Long,
    ) {
        val elapsed = (nowMs() - startedAtMs).coerceAtLeast(0L)
        telemetrySinkProvider()?.onEvent(
            WebContainerTelemetryEvent.JsbCallCompleted(
                surfaceId = surfaceId,
                method = method,
                success = result is JsbResult.Success,
                errorCode = (result as? JsbResult.Failure)?.code?.wireCode,
                durationMs = elapsed,
            ),
        )
    }
}

/**
 * Container-owned JavaScript bootstrap and internal probe scripts.
 *
 * On iOS, [buildPageBridgeScript], [buildOfflineBundleScript], and [wrapDocumentStartScript] run
 * inside `WKContentWorld.pageWorld` (web-accessible, strictly origin-fenced), whereas
 * [ContentVisibilityProbeScript] executes inside `WKContentWorld.defaultClientWorld` (isolated
 * from webpage scripts).
 */
object JsbBootstrapScripts {
    /**
     * Wraps a document-start script in an IIFE enforcing [mainFrameOnly] (`window.top === window.self`)
     * and [allowedOrigins] (`window.location.origin` allowlist).
     *
     * Required on iOS (`WKUserScript` has no native origin filter) and on Android for `mainFrameOnly`
     * (`WebViewCompat.addDocumentStartJavaScript` injects into same-origin iframes too).
     */
    fun wrapDocumentStartScript(
        source: String,
        allowedOrigins: Set<WebContainerOrigin>,
        mainFrameOnly: Boolean = true,
    ): String {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return ""
        return buildString {
            append("(function(){")
            if (mainFrameOnly) {
                append("if(window.top!==window.self)return;")
            }
            if (allowedOrigins.isNotEmpty()) {
                val originsLiteral = allowedOrigins.joinToString(",") { JsonPrimitive(it.asOriginString()).toString() }
                append("var __allowed=[$originsLiteral];if(__allowed.indexOf(window.location.origin)===-1)return;")
            }
            append(trimmed)
            append("\n})();")
        }
    }

    /**
     * Frozen `window.raftBridge` page-world shim (`v=1`).
     *
     * Guards `window.top === window.self` and `window.location.origin` against [allowedOrigins],
     * adapting cleanly across:
     * - iOS `WKScriptMessageHandlerWithReply` (`window.webkit.messageHandlers[portName].postMessage`,
     *   settling directly without leaking `pending[id]` even if native omits `id`)
     * - Android `WebMessageListener` (`window[portName].postMessage` + `onmessage`)
     * - Desktop IPC (`window.ipc.postMessage` + `__dispatchResponse` / `__dispatchEvent`)
     */
    fun buildPageBridgeScript(
        portName: String = DEFAULT_JSB_PORT_NAME,
        allowedOrigins: Set<WebContainerOrigin>,
    ): String {
        val originsLiteral = allowedOrigins.joinToString(",") { JsonPrimitive(it.asOriginString()).toString() }
        val portLiteral = JsonPrimitive(portName).toString()
        return buildString {
            append("(function(){")
            append("if(window.top!==window.self)return;")
            append("var allowed=[$originsLiteral];")
            append("if(allowed.indexOf(window.location.origin)===-1)return;")
            append("if(window.raftBridge)return;")
            append("var portName=$portLiteral;")
            append("var seq=0;var pending={};var listeners={};")
            append("function buildError(err){var e=new Error((err&&err.message)||'JSB error');e.code=(err&&err.code)||'INTERNAL_ERROR';e.details=err&&err.details;return e;}")
            append("function settleDirect(raw,resolve,reject){")
            append("var msg;try{msg=typeof raw==='string'?JSON.parse(raw):raw;}catch(_){reject(buildError({code:'INTERNAL_ERROR',message:'Malformed native JSB response'}));return;}")
            append("if(!msg||msg.v!==1){reject(buildError({code:'UNSUPPORTED_VERSION',message:'Invalid JSB response envelope'}));return;}")
            append("if(msg.ok){resolve(msg.data);}else{reject(buildError(msg.error));}")
            append("}")
            append("function settle(raw){")
            append("var msg;try{msg=typeof raw==='string'?JSON.parse(raw):raw;}catch(_){return;}")
            append("if(!msg||msg.v!==1)return;")
            append("if(msg.type==='event'){dispatchEvent(msg);return;}")
            append("var entry=msg.id&&pending[msg.id];if(!entry)return;delete pending[msg.id];")
            append("if(msg.ok){entry.resolve(msg.data);}else{entry.reject(buildError(msg.error));}")
            append("}")
            append("function dispatchEvent(msg){")
            append("var list=listeners[msg.name];if(!list)return;")
            append("for(var i=0;i<list.length;i++){try{list[i](msg.payload);}catch(_){}}")
            append("}")
            append("var androidPort=window[portName];")
            append("if(androidPort&&typeof androidPort.postMessage==='function'){androidPort.onmessage=function(ev){settle(ev&&ev.data);};}")
            append("var bridge=Object.freeze({")
            append("version:1,")
            append("invoke:function(method,params){")
            append("return new Promise(function(resolve,reject){")
            append("if(typeof method!=='string'||!method){reject(buildError({code:'INVALID_MESSAGE',message:'Invalid method'}));return;}")
            append("var id='jsb_'+(++seq)+'_'+Date.now();")
            append("var wire=JSON.stringify({v:1,id:id,method:method,params:(params&&typeof params==='object'&&!Array.isArray(params))?params:{}});")
            append("var wk=window.webkit&&window.webkit.messageHandlers&&window.webkit.messageHandlers[portName];")
            append("if(wk&&typeof wk.postMessage==='function'){")
            append("wk.postMessage(wire).then(function(resp){settleDirect(resp,resolve,reject);},function(err){reject(buildError({code:'INTERNAL_ERROR',message:(err&&err.message)||'Native bridge error'}));});")
            append("return;")
            append("}")
            append("var ap=window[portName];")
            append("if(ap&&typeof ap.postMessage==='function'){pending[id]={resolve:resolve,reject:reject};ap.postMessage(wire);return;}")
            append("var dipc=window.ipc;")
            append("if(dipc&&typeof dipc.postMessage==='function'){pending[id]={resolve:resolve,reject:reject};dipc.postMessage(wire);return;}")
            append("reject(buildError({code:'BRIDGE_DISABLED',message:'Native JSB transport unavailable'}));")
            append("});},")
            append("on:function(name,cb){")
            append("if(typeof name!=='string'||typeof cb!=='function')return function(){};")
            append("if(!listeners[name])listeners[name]=[];")
            append("listeners[name].push(cb);")
            append("return function(){var arr=listeners[name];if(!arr)return;var idx=arr.indexOf(cb);if(idx>=0)arr.splice(idx,1);};")
            append("},")
            append("__dispatchEvent:function(raw){settle(raw);},")
            append("__dispatchResponse:function(raw){settle(raw);}")
            append("});")
            append("Object.defineProperty(window,'raftBridge',{value:bridge,writable:false,configurable:false,enumerable:true});")
            append("})();")
        }
    }

    /**
     * Injects frozen `window.__RAFT_OFFLINE_BUNDLE__` and fires `raft.offlineBundle.ready`,
     * guarded to the main frame and [allowedOrigins] (when non-empty).
     */
    fun buildOfflineBundleScript(
        descriptor: WebOfflineBundleDescriptor,
        allowedOrigins: Set<WebContainerOrigin> = emptySet(),
    ): String {
        val payload =
            buildJsonObject {
                put("protocol", descriptor.protocolVersion)
                put("bundleId", descriptor.bundleId)
                put("pageSessionId", descriptor.pageSessionId)
                if (descriptor.iosCustomScheme != null) put("iosCustomScheme", descriptor.iosCustomScheme)
                if (descriptor.metadata.isNotEmpty()) {
                    put(
                        "metadata",
                        buildJsonObject {
                            descriptor.metadata.forEach { (k, v) -> put(k, v) }
                        },
                    )
                }
            }.toString()
        val body =
            "try{var d=Object.freeze($payload);" +
                "Object.defineProperty(window,'__RAFT_OFFLINE_BUNDLE__',{value:d,writable:false,configurable:false,enumerable:true});" +
                "window.dispatchEvent(new CustomEvent('raft.offlineBundle.ready',{detail:d}));}catch(_){}"
        return wrapDocumentStartScript(source = body, allowedOrigins = allowedOrigins, mainFrameOnly = true)
    }

    /**
     * Read-only internal DOM visibility probe returning `"1"` if the main document has visible
     * content (text, media, canvas, SVG, or non-empty child geometry) or `"0"` if blank.
     * On iOS this MUST run in `WKContentWorld.defaultClientWorld`.
     */
    const val ContentVisibilityProbeScript: String =
        "(function(){try{var b=document.body;if(!b)return '0';" +
            "if((b.innerText||'').trim().length>0)return '1';" +
            "if(b.querySelector('img,svg,canvas,video,iframe,input,textarea,button'))return '1';" +
            "var r=b.getBoundingClientRect();return (r&&r.height>8&&b.children&&b.children.length>0)?'1':'0';" +
            "}catch(_){return '0';}})();"

    /** First-paint canvas background + `color-scheme` shield against dark-mode white flashes. */
    fun buildFirstPaintThemeScript(
        backgroundArgb: Int,
        isDark: Boolean,
    ): String {
        val hex = formatArgbAsCssRgb(backgroundArgb)
        val scheme = if (isDark) "dark" else "light"
        return "(function(){if(window.top!==window.self)return;try{var s=document.createElement('style');s.id='__raft_wc_fp';" +
            "s.textContent=':root{color-scheme:$scheme;background-color:$hex;}html,body{background-color:$hex;}';" +
            "(document.head||document.documentElement).appendChild(s);" +
            "window.addEventListener('DOMContentLoaded',function(){var el=document.getElementById('__raft_wc_fp');if(el&&el.parentNode)el.parentNode.removeChild(el);},{once:true});" +
            "}catch(_){}})();"
    }

    /** Desktop-width 1280px viewport meta helper (honoured natively by WKWebView & Android wide viewport). */
    const val DesktopViewportMetaScript: String =
        "(function(){if(window.top!==window.self)return;try{var m=document.querySelector('meta[name=\"viewport\"]');" +
            "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}" +
            "m.setAttribute('content','width=1280,initial-scale=0.35,minimum-scale=0.2,maximum-scale=5.0,user-scalable=yes');" +
            "}catch(_){}})();"

    /** Decodes [ContentVisibilityProbeScript] result strings (`"1"` -> true, `"0"` -> false, else null). */
    fun decodeContentProbeResult(raw: String?): Boolean? {
        val cleaned = raw?.trim()?.trim('"') ?: return null
        return when (cleaned) {
            "1", "true" -> true
            "0", "false" -> false
            else -> null
        }
    }

    private fun formatArgbAsCssRgb(argb: Int): String {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return "rgb($r,$g,$b)"
    }
}
