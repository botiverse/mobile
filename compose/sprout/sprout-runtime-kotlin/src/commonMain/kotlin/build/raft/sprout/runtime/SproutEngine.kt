package build.raft.sprout.runtime

import build.raft.sprout.protocol.Catalog
import build.raft.sprout.protocol.DEFAULT_PORTABLE_PRIMITIVES_VERSION
import build.raft.sprout.protocol.DiagnosticOrigin
import build.raft.sprout.protocol.DroppedOperationReason
import build.raft.sprout.protocol.EventBinding
import build.raft.sprout.protocol.FrameValidationOutcome
import build.raft.sprout.protocol.GENUI_TREE_PROTOCOL_V1
import build.raft.sprout.protocol.HostEnvelopeOverride
import build.raft.sprout.protocol.NodeKind
import build.raft.sprout.protocol.PortableSpecMode
import build.raft.sprout.protocol.ProtocolDiagnostic
import build.raft.sprout.protocol.ProtocolLimits
import build.raft.sprout.protocol.SUPPORTED_KOTLIN_PRIMITIVES_VERSIONS
import build.raft.sprout.protocol.SproutEventValidator
import build.raft.sprout.protocol.SproutFrameValidator
import build.raft.sprout.protocol.SurfaceFrame
import build.raft.sprout.protocol.isSafeHttpActionUrl
import build.raft.sprout.protocol.TreeNode
import build.raft.sprout.protocol.TreeRefs
import build.raft.sprout.protocol.TreeSharer
import build.raft.sprout.protocol.ValidateFrameOptions
import build.raft.sprout.protocol.ValidationProfile
import build.raft.sprout.protocol.ValidationStats
import build.raft.sprout.protocol.takeUnicodeCodePoints
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.atomicfu.atomic
import kotlin.random.Random
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

const val SUPPORTED_GUEST_ABI_VERSION: Int = 2

class SproutRuntimeException(
    val code: String,
    val phase: String,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

data class RuntimeLimits(
    val memoryLimitBytes: Long = 16L * 1024L * 1024L,
    val maxStackSizeBytes: Long = 256L * 1024L,
    val sourceUpdateBudgetMs: Long = 50L,
    val dispatchBudgetMs: Long = 25L,
    val tickBudgetMs: Long = 16L,
    val compilerMemoryLimitBytes: Long = 32L * 1024L * 1024L,
    val compilerBudgetMs: Long = 100L,
    val maxPendingStateOperations: Int = 32,
    val maxPendingRequests: Int = 32,
) {
    fun validateOrThrow() {
        require(memoryLimitBytes > 0L) { "memoryLimitBytes must be > 0 (got $memoryLimitBytes)" }
        require(maxStackSizeBytes > 0L) { "maxStackSizeBytes must be > 0 (got $maxStackSizeBytes)" }
        require(sourceUpdateBudgetMs > 0L) { "sourceUpdateBudgetMs must be > 0 (got $sourceUpdateBudgetMs)" }
        require(dispatchBudgetMs > 0L) { "dispatchBudgetMs must be > 0 (got $dispatchBudgetMs)" }
        require(tickBudgetMs > 0L) { "tickBudgetMs must be > 0 (got $tickBudgetMs)" }
        require(compilerMemoryLimitBytes > 0L) { "compilerMemoryLimitBytes must be > 0 (got $compilerMemoryLimitBytes)" }
        require(compilerBudgetMs > 0L) { "compilerBudgetMs must be > 0 (got $compilerBudgetMs)" }
        require(maxPendingStateOperations in 1..256) {
            "maxPendingStateOperations must be in 1..256 (got $maxPendingStateOperations)"
        }
        require(maxPendingRequests in 1..256) {
            "maxPendingRequests must be in 1..256 (got $maxPendingRequests)"
        }
    }
}

data class SurfaceAdapters(
    val state: Boolean = true,
    val action: Boolean = true,
    val navigation: Boolean = true,
)

data class RepeatedDiagnosticEntry(
    val code: String,
    val phase: String = "timer",
    val message: String,
    val count: Int,
)

data class RepeatedDiagnostics(
    val available: Boolean = false,
    val entries: List<RepeatedDiagnosticEntry> = emptyList(),
    val suppressed: Int = 0,
)

@Serializable
data class GuestEntryPointSpec(
    val name: String,
    val params: List<String> = emptyList(),
    val required: Boolean = true,
    val extension: Boolean = false,
)

@Serializable
data class GuestAbiDetails(
    val abiVersion: Int,
    val entryPoints: List<GuestEntryPointSpec> = emptyList(),
    val hostBindings: List<String> = listOf("__denied", "__enter", "__log", "__now", "__sharedWrite", "raft"),
    val requiredBindings: List<String> = listOf("__enter", "__log", "__now"),
    val hooks: List<String> = emptyList(),
    val reactNamespace: List<String> = emptyList(),
    val programGlobals: List<String> = emptyList(),
    val compilerGlobals: List<String> = emptyList(),
    val capabilities: Map<String, String> = emptyMap(),
)

data class GuestRuntimeCapabilities(
    val hooks: List<String> = emptyList(),
    val raftActions: List<String> = emptyList(),
    val useAction: Boolean = false,
    val esmImports: Boolean = false,
    val context: Boolean = false,
    val useReducedMotion: Boolean = false,
)

@Serializable
data class GuestRuntimeAbiManifest(
    val guestRuntimeVersion: String,
    val sha256: String,
    val bytes: Int,
    val file: String,
    val abi: GuestAbiDetails,
) {
    val abiVersion: Int get() = abi.abiVersion
    val requiredHostBindings: List<String> get() = abi.requiredBindings
    val consumedUnderscoreBindings: List<String> get() = abi.hostBindings.filter { it.startsWith("__") }
    val requiredEntries: List<String> get() = abi.entryPoints.filter { it.required && !it.extension }.map { it.name }
    val allEntries: Set<String> get() = abi.entryPoints.map { it.name }.toSet()
    val capabilities: GuestRuntimeCapabilities
        get() = GuestRuntimeCapabilities(
            hooks = abi.hooks,
            raftActions = if ("raft" in abi.hostBindings) listOf("reply", "openLink") else emptyList(),
            useAction = abi.capabilities["useAction"] == "implemented",
            esmImports = false,
            context = abi.capabilities["useContext"] == "implemented" && abi.capabilities["createContext"] == "implemented",
            useReducedMotion = abi.capabilities["useReducedMotion"] == "implemented",
        )
}

data class LockedEsmModule(
    val canonicalId: String,
    val source: String,
)

data class ModuleManifest(
    val entryMap: Map<String, String> = emptyMap(),
    val modules: List<LockedEsmModule> = emptyList(),
)

sealed class SharedStateOp {
    data class Set(val key: String, val value: JsonElement) : SharedStateOp()
    data class Delete(val key: String) : SharedStateOp()
}

data class PendingStateOperation(
    val operationId: String,
    val operations: List<SharedStateOp>,
    val expectedOverlayAfter: JsonObject,
    val sourceRevision: Long = -1L,
    val baseRevision: Long = 0L,
    val sent: Boolean = false,
    val reason: String? = null,
    val draftOperations: List<SharedStateOp>? = null,
)

data class UncertainStateOperation(
    val operationId: String,
    val baseRevision: Long,
    val operations: List<SharedStateOp>,
    val attempts: Int,
    val withdrawn: Boolean,
)

sealed class StateReceipt {
    abstract val surfaceId: String?
    abstract val surfaceGeneration: Long?

    data class Accepted(
        val operationId: String,
        val revision: Long,
        val state: JsonObject,
        override val surfaceId: String? = null,
        override val surfaceGeneration: Long? = null,
    ) : StateReceipt()

    data class Conflict(
        val operationId: String,
        val revision: Long,
        val state: JsonObject,
        override val surfaceId: String? = null,
        override val surfaceGeneration: Long? = null,
    ) : StateReceipt()

    data class Denied(
        val operationId: String,
        val code: String,
        override val surfaceId: String? = null,
        override val surfaceGeneration: Long? = null,
        val local: Boolean? = null,
    ) : StateReceipt()

    data class Uncertain(
        val operationId: String,
        val reason: String? = null,
        override val surfaceId: String? = null,
        override val surfaceGeneration: Long? = null,
    ) : StateReceipt()

    data class Withdrawn(
        val operationId: String,
        val reason: String? = null,
        val draftOperations: List<SharedStateOp>? = null,
        val revision: Long,
        val state: JsonObject,
        override val surfaceId: String? = null,
        override val surfaceGeneration: Long? = null,
    ) : StateReceipt()

    data class Snapshot(
        val revision: Long,
        val state: JsonObject,
        val operationId: String? = null,
        override val surfaceId: String? = null,
        override val surfaceGeneration: Long? = null,
    ) : StateReceipt()
}

data class ActionResultReceipt(
    val requestId: String,
    val status: String,
    val committedStateRevision: Long? = null,
    val state: JsonObject? = null,
    val surfaceId: String? = null,
    val surfaceGeneration: Long? = null,
)

sealed class OutboundEffect {
    data class InitState(
        val operationId: String,
        val surfaceId: String,
        val surfaceGeneration: Long? = null,
        val sourceRevision: Long = 0L,
        val expectedRevision: Long = 0L,
        val defaults: JsonObject,
        val schemaVersion: Int = 1,
    ) : OutboundEffect()

    data class ProposeState(
        val operationId: String,
        val surfaceId: String,
        val surfaceGeneration: Long? = null,
        val sourceRevision: Long = 0L,
        val baseRevision: Long,
        val operations: List<SharedStateOp>,
    ) : OutboundEffect()

    data class SharedStateSet(
        val key: String,
        val value: JsonElement,
    ) : OutboundEffect()

    data class ActionRequest(
        val action: String,
        val payload: JsonElement,
        val requestId: String = "",
        val surfaceId: String = "",
        val surfaceGeneration: Long? = null,
        val sourceRevision: Long = 0L,
        val expectedStateRevision: Long = 0L,
    ) : OutboundEffect()

    data class Navigate(
        val surfaceId: String,
        val url: String,
    ) : OutboundEffect()
}

enum class SurfaceStatus(val wireName: String) {
    PREVIEW("preview"),
    READY("ready"),
    FINAL_INVALID("final-invalid"),
    CANCELLED("cancelled"),
    ERROR("error"),
}

enum class InertReason(val wireName: String?) {
    NONE(null),
    SOURCE_REPLACED("source-replaced"),
    FRAME_REJECTED("frame-rejected"),
    STALE_PREVIEW("stale-preview"),
    SOURCE_CANCELLED("source-cancelled"),
    SOURCE_ERROR("source-error"),
    FINAL_INVALID("final-invalid"),
}

data class FrameCommit(
    val frame: SurfaceFrame,
    val diagnostics: List<ProtocolDiagnostic>,
    val stats: ValidationStats,
    val sharedSubtreeNodes: Int,
    val effects: List<OutboundEffect>,
    val suggestedInitialState: JsonObject,
    val nextTimerDueMs: Long?,
    val hasAnimationFrame: Boolean = false,
    /** False when the last render/finish was rejected or source was replaced, leaving the previous frame read-only (v6 §15 Item 1, 2, 13). */
    val interactionEnabled: Boolean = true,
    val inertReason: InertReason = if (interactionEnabled) InertReason.NONE else InertReason.FRAME_REJECTED,
    val status: SurfaceStatus = SurfaceStatus.PREVIEW,
    val stalePreview: Boolean = false,
    val uncertain: UncertainStateOperation? = null,
    val changed: Boolean = false,
    val pendingStateOperations: Int = 0,
    val droppedOperations: List<PendingStateOperation> = emptyList(),
    val guestRuntimeVersion: String = "",
    val guestAbiVersion: Int = SUPPORTED_GUEST_ABI_VERSION,
    val compileMs: Double,
    val executeMs: Double,
    val validateMs: Double,
) {
    val sharedStateSets: List<SharedStateOp.Set>
        get() = effects.flatMap { eff ->
            when (eff) {
                is OutboundEffect.ProposeState -> eff.operations.filterIsInstance<SharedStateOp.Set>()
                is OutboundEffect.SharedStateSet -> listOf(SharedStateOp.Set(eff.key, eff.value))
                else -> emptyList()
            }
        }
}

/**
 * Drives `QuickJs` (`Dispatchers.Unconfined`) strictly synchronously on the caller's thread (v5 §3 & §8):
 * - Zero thread hop and zero `runBlocking` deadlock on UI threads.
 * - Any module or guest code attempting async Promise suspension fails immediately with `MODULE_ASYNC_INIT_UNSUPPORTED`.
 */
internal object SyncQuickJs {
    fun <T> evalSync(
        vm: QuickJs,
        budgetMs: Long,
        phase: String,
        code: String,
        filename: String = "<eval>",
        asModule: Boolean = false,
    ): T {
        vm.evaluationTimeoutMillis = budgetMs
        val evalJob = kotlinx.coroutines.Job()
        var outcome: Result<Any?>? = null
        val block: suspend () -> Any? = {
            vm.evaluate<Any?>(code = code, filename = filename, asModule = asModule)
        }
        try {
            block.startCoroutine(
                object : Continuation<Any?> {
                    override val context: CoroutineContext = Dispatchers.Unconfined + evalJob
                    override fun resumeWith(result: Result<Any?>) {
                        outcome = result
                    }
                },
            )
            val completed = outcome ?: throw SproutRuntimeException(
                code = "MODULE_ASYNC_INIT_UNSUPPORTED",
                phase = "module",
                message = "Asynchronous module initialization or Promise suspension is forbidden ($filename)",
            )
            val value = completed.getOrElse { err ->
                when (err) {
                    is QuickJsInterruptedException -> throw SproutRuntimeException(
                        code = "INTERRUPTED",
                        phase = phase,
                        message = "QuickJS execution exceeded ${budgetMs}ms budget in $filename",
                        cause = err,
                    )
                    is QuickJsException -> {
                        val msg = err.message ?: "QuickJS error"
                        val (mappedCode, mappedPhase) = mapGuestException(msg, phase)
                        throw SproutRuntimeException(mappedCode, mappedPhase, msg, err)
                    }
                    else -> throw SproutRuntimeException("RUNTIME_ERROR", phase, err.message ?: err.toString(), err)
                }
            }
            @Suppress("UNCHECKED_CAST")
            return value as T
        } finally {
            evalJob.cancel()
        }
    }

    private fun mapGuestException(msg: String, defaultPhase: String): Pair<String, String> = when {
        "UI tree is too large" in msg || "TREE_TOO_LARGE" in msg -> "TREE_TOO_LARGE" to "render"
        "UI tree is too deep" in msg || "TREE_TOO_DEEP" in msg -> "TREE_TOO_DEEP" to "render"
        "Waiting for the default export" in msg || "No root component" in msg -> "NO_ROOT_COMPONENT" to "render"
        "UNSUPPORTED_CAPABILITY" in msg -> "UNSUPPORTED_CAPABILITY" to defaultPhase
        "import is not supported in generated UI code" in msg -> "UNSUPPORTED_CAPABILITY" to "module"
        "shared state would be" in msg && "the limit is" in msg -> "LIMIT_EXCEEDED" to "state"
        "out of memory" in msg.lowercase() -> "LIMIT_EXCEEDED" to defaultPhase
        else -> "RUNTIME_ERROR" to defaultPhase
    }
}

internal sealed class PreviewCompileOutput {
    data class Whole(val code: String) : PreviewCompileOutput()
    data class Parts(
        val code: String,
        val reset: Boolean,
        val appendedJson: String,
        val tailJson: String,
        val staleCells: Boolean,
        val cellsJson: String,
    ) : PreviewCompileOutput()
}

/**
 * Isolated QuickJS runtime dedicated solely to running the shared JS fault-tolerant JSX compiler bundle (`compiler.iife.js`).
 */
class QuickJsCompiler internal constructor(
    compilerBundleSource: String,
    private val limits: RuntimeLimits,
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val vm: QuickJs = QuickJs.create(Dispatchers.Unconfined).apply {
        memoryLimit = limits.compilerMemoryLimitBytes
        maxStackSize = 512L * 1024L
    }

    init {
        SyncQuickJs.evalSync<Any?>(
            vm = vm,
            budgetMs = 2_000L,
            phase = "compile",
            code = """
                $compilerBundleSource
                globalThis.__sessions = new Map();
                globalThis.__sessionsInline = new Map();
                globalThis.__compilePreview = function(sessionKey, src, useCells, allowParts) {
                  if (allowParts) {
                    var sp = globalThis.__sessions.get(sessionKey);
                    if (!sp) {
                      sp = new GenUICompiler.StreamCompiler(true, useCells ? {} : null);
                      globalThis.__sessions.set(sessionKey, sp);
                    }
                    var r = sp.update(src);
                    var allDecl = true;
                    var i;
                    for (i = 0; i < r.appended.length; i++) {
                      if (r.appended[i].declaration === false) { allDecl = false; break; }
                    }
                    if (allDecl) {
                      for (i = 0; i < r.tail.length; i++) {
                        if (r.tail[i].declaration === false) { allDecl = false; break; }
                      }
                    }
                    if (allDecl) {
                      var app = [], tl = [], cls = [];
                      for (i = 0; i < r.appended.length; i++) app.push({ c: r.appended[i].code, n: r.appended[i].names || [] });
                      for (i = 0; i < r.tail.length; i++) tl.push({ c: r.tail[i].code, n: r.tail[i].names || [] });
                      var rawCells = r.cells || [];
                      for (i = 0; i < rawCells.length; i++) cls.push({ i: rawCells[i].id, p: rawCells[i].params || [], c: rawCells[i].code });
                      return JSON.stringify({
                        mode: "parts",
                        code: r.code,
                        reset: !!r.reset,
                        staleCells: !!r.staleCells,
                        appendedJson: JSON.stringify(app),
                        tailJson: JSON.stringify(tl),
                        cellsJson: JSON.stringify(cls)
                      });
                    }
                  }
                  var si = globalThis.__sessionsInline.get(sessionKey);
                  if (!si) {
                    si = new GenUICompiler.StreamCompiler(true, useCells ? { hoist: false } : null);
                    globalThis.__sessionsInline.set(sessionKey, si);
                  }
                  return JSON.stringify({ mode: "whole", code: si.update(src).code });
                };
                globalThis.__resetSession = function(sessionKey) {
                  globalThis.__sessions.delete(sessionKey);
                  globalThis.__sessionsInline.delete(sessionKey);
                };
                globalThis.__compileStrict = function(src) {
                  return GenUICompiler.compileStrict(src);
                };
                globalThis.__compileTolerantPlain = function(src) {
                  return GenUICompiler.compileTolerant(src);
                };
            """.trimIndent(),
            filename = "compiler.iife.js",
        )
    }

    internal fun compilePreview(
        sessionKey: String,
        source: String,
        useCells: Boolean = true,
        allowParts: Boolean = true,
    ): PreviewCompileOutput {
        val escapedKey = Json.encodeToString(kotlinx.serialization.serializer<String>(), sessionKey)
        val escapedSrc = Json.encodeToString(kotlinx.serialization.serializer<String>(), source)
        val raw: String = try {
            SyncQuickJs.evalSync(
                vm = vm,
                budgetMs = limits.compilerBudgetMs,
                phase = "compile",
                code = "globalThis.__compilePreview($escapedKey, $escapedSrc, $useCells, $allowParts)",
                filename = "compilePreview",
            )
        } catch (err: SproutRuntimeException) {
            if (err.code == "RUNTIME_ERROR") {
                throw SproutRuntimeException("COMPILE_ERROR", "compile", err.message, err)
            }
            throw err
        }
        val obj = json.parseToJsonElement(raw).jsonObject
        return if (obj["mode"]?.jsonPrimitive?.content == "parts") {
            PreviewCompileOutput.Parts(
                code = obj["code"]!!.jsonPrimitive.content,
                reset = obj["reset"]?.jsonPrimitive?.booleanOrNull == true,
                appendedJson = obj["appendedJson"]!!.jsonPrimitive.content,
                tailJson = obj["tailJson"]!!.jsonPrimitive.content,
                staleCells = obj["staleCells"]?.jsonPrimitive?.booleanOrNull == true,
                cellsJson = obj["cellsJson"]!!.jsonPrimitive.content,
            )
        } else {
            PreviewCompileOutput.Whole(code = obj["code"]!!.jsonPrimitive.content)
        }
    }

    fun compileStreaming(sessionKey: String, source: String, useCells: Boolean = true): String {
        return when (val out = compilePreview(sessionKey, source, useCells = useCells, allowParts = false)) {
            is PreviewCompileOutput.Whole -> out.code
            is PreviewCompileOutput.Parts -> out.code
        }
    }

    fun compileStrict(source: String): String {
        val escapedSrc = Json.encodeToString(kotlinx.serialization.serializer<String>(), source)
        return try {
            SyncQuickJs.evalSync(
                vm = vm,
                budgetMs = limits.compilerBudgetMs,
                phase = "compile",
                code = "globalThis.__compileStrict($escapedSrc)",
                filename = "compileStrict",
            )
        } catch (err: SproutRuntimeException) {
            if (err.code == "RUNTIME_ERROR") {
                throw SproutRuntimeException("COMPILE_ERROR", "compile", err.message, err)
            }
            throw err
        }
    }

    fun compileTolerantPlain(source: String): String {
        val escapedSrc = Json.encodeToString(kotlinx.serialization.serializer<String>(), source)
        return SyncQuickJs.evalSync(
            vm = vm,
            budgetMs = limits.compilerBudgetMs,
            phase = "compile",
            code = "globalThis.__compileTolerantPlain($escapedSrc)",
            filename = "compileTolerantPlain",
        )
    }

    fun dropSession(sessionKey: String) {
        if (vm.isClosed) return
        val escapedKey = Json.encodeToString(kotlinx.serialization.serializer<String>(), sessionKey)
        runCatching {
            SyncQuickJs.evalSync<Any?>(vm, 50L, "compile", "globalThis.__resetSession($escapedKey)", "dropSession")
        }
    }

    override fun close() {
        if (!vm.isClosed) vm.close()
    }
}

class SproutEngine private constructor(
    val compiler: QuickJsCompiler,
    val guestRuntimeSource: String,
    val guestManifest: GuestRuntimeAbiManifest,
    val sessionInstanceId: String,
    private val defaultModuleManifest: ModuleManifest,
    private val defaultLimits: RuntimeLimits,
    val coldPrepareMs: Double,
) : AutoCloseable {
    private val idSequence = atomic(0L)

    internal fun nextUnrepeatableId(): String = "$sessionInstanceId.${idSequence.incrementAndGet()}"

    val guestRuntimeVersion: String get() = guestManifest.guestRuntimeVersion
    val guestAbiVersion: Int get() = guestManifest.abiVersion
    val capabilities: GuestRuntimeCapabilities get() = guestManifest.capabilities

    companion object {
        private val manifestJson = Json { ignoreUnknownKeys = true }

        fun prepare(
            compilerBundle: String = loadBundledCompilerSource(),
            guestRuntimeBundle: String = loadBundledGuestRuntimeSource(),
            guestManifest: GuestRuntimeAbiManifest = loadBundledGuestManifest(),
            moduleManifest: ModuleManifest = ModuleManifest(),
            limits: RuntimeLimits = RuntimeLimits(),
            sessionInstanceId: String = generateSessionInstanceId(),
        ): SproutEngine {
            limits.validateOrThrow()
            require(sessionInstanceId.isNotBlank()) { "sessionInstanceId must be non-empty" }
            verifyGuestRuntimeBundleAndAbi(guestRuntimeBundle, guestManifest)

            val mark = TimeSource.Monotonic.markNow()
            val compiler = QuickJsCompiler(compilerBundle, limits)
            val elapsedMs = mark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
            return SproutEngine(
                compiler = compiler,
                guestRuntimeSource = guestRuntimeBundle,
                guestManifest = guestManifest,
                sessionInstanceId = sessionInstanceId,
                defaultModuleManifest = moduleManifest,
                defaultLimits = limits,
                coldPrepareMs = elapsedMs,
            )
        }

        fun verifyGuestRuntimeBundleAndAbi(
            guestRuntimeBundle: String,
            manifest: GuestRuntimeAbiManifest,
        ) {
            if (manifest.abiVersion != SUPPORTED_GUEST_ABI_VERSION) {
                throw SproutRuntimeException(
                    code = "HOST_ABI_MISMATCH",
                    phase = "engine",
                    message = "Unsupported guest-runtime abiVersion=${manifest.abiVersion} (expected $SUPPORTED_GUEST_ABI_VERSION)",
                )
            }
            val utf8Bytes = guestRuntimeBundle.encodeToByteArray()
            val actualHex = SproutSha256.sha256Hex(utf8Bytes)
            if (!actualHex.equals(manifest.sha256, ignoreCase = true) || utf8Bytes.size != manifest.bytes) {
                throw SproutRuntimeException(
                    code = "GUEST_MANIFEST_INVALID",
                    phase = "engine",
                    message = "Canonical guest-runtime SHA-256/byte-size mismatch (expected ${manifest.sha256}/${manifest.bytes}, got $actualHex/${utf8Bytes.size})",
                )
            }
            val mandatoryHostBindings = setOf("__log", "__now", "__enter")
            if (!manifest.requiredHostBindings.containsAll(mandatoryHostBindings)) {
                throw SproutRuntimeException(
                    code = "HOST_ABI_MISMATCH",
                    phase = "engine",
                    message = "Guest manifest missing mandatory host bindings: ${mandatoryHostBindings - manifest.requiredHostBindings.toSet()}",
                )
            }
        }

        fun loadBundledCompilerSource(): String = SproutEmbeddedBundles.compilerSource()

        fun loadBundledGuestRuntimeSource(): String = SproutEmbeddedBundles.guestRuntimeSource()

        fun loadBundledGuestManifest(): GuestRuntimeAbiManifest =
            manifestJson.decodeFromString(GuestRuntimeAbiManifest.serializer(), SproutEmbeddedBundles.guestManifestJson())

        private fun generateSessionInstanceId(): String {
            val bytes = ByteArray(16)
            Random.Default.nextBytes(bytes)
            return SproutSha256.bytesToHex(bytes)
        }
    }

    fun createSurface(
        surfaceId: String,
        catalog: Catalog,
        profile: ValidationProfile = ValidationProfile.PORTABLE_V1,
        portableSpecMode: PortableSpecMode = PortableSpecMode.V6_STRICT,
        surfaceGeneration: Long? = 1L,
        initialState: JsonObject = JsonObject(emptyMap()),
        initialStateRevision: Long = 0L,
        moduleManifest: ModuleManifest = defaultModuleManifest,
        limits: RuntimeLimits = defaultLimits,
        maxFrameBytes: Int = ProtocolLimits.MAX_FRAME_BYTES,
        initialRuntimeEpoch: Long = 1L,
        useCells: Boolean = false,
        incrementalPreview: Boolean = true,
        maxCatchUpMs: Long = 0L,
        reducedMotion: Boolean = false,
        revisionFence: Boolean = false,
        primitivesVersion: String = DEFAULT_PORTABLE_PRIMITIVES_VERSION,
        adapters: SurfaceAdapters = SurfaceAdapters(),
    ): SproutSurface {
        limits.validateOrThrow()
        if (primitivesVersion !in SUPPORTED_KOTLIN_PRIMITIVES_VERSIONS) {
            throw SproutRuntimeException(
                code = "UNSUPPORTED_CAPABILITY",
                phase = "engine",
                message = "primitivesVersion \"${primitivesVersion.take(60)}\" is not known to this session (known: ${SUPPORTED_KOTLIN_PRIMITIVES_VERSIONS.joinToString(", ")})",
            )
        }
        if (maxCatchUpMs !in 0L..1_000L) {
            throw IllegalArgumentException("Invalid maxCatchUpMs=$maxCatchUpMs: must be finite and within 0..1000")
        }
        if (initialStateRevision !in 0L..build.raft.sprout.protocol.MAX_JS_SAFE_INTEGER) {
            throw IllegalArgumentException("Invalid initialStateRevision=$initialStateRevision: must be within 0..MAX_SAFE_INTEGER")
        }
        if (surfaceGeneration != null && surfaceGeneration !in 0L..build.raft.sprout.protocol.MAX_JS_SAFE_INTEGER) {
            throw IllegalArgumentException("Invalid surfaceGeneration=$surfaceGeneration: must be within 0..MAX_SAFE_INTEGER when specified")
        }
        if (initialRuntimeEpoch !in 0L..build.raft.sprout.protocol.MAX_JS_SAFE_INTEGER) {
            throw IllegalArgumentException("Invalid initialRuntimeEpoch=$initialRuntimeEpoch: must be within 0..MAX_SAFE_INTEGER")
        }
        if (moduleManifest.modules.isNotEmpty()) {
            for (mod in moduleManifest.modules) {
                if (containsTopLevelAwait(mod.source)) {
                    throw SproutRuntimeException(
                        code = "MODULE_ASYNC_INIT_UNSUPPORTED",
                        phase = "module",
                        message = "Top-level await is forbidden in ESM module '${mod.canonicalId}'",
                    )
                }
            }
            if (!guestManifest.capabilities.esmImports) {
                throw SproutRuntimeException(
                    code = "UNSUPPORTED_CAPABILITY",
                    phase = "module",
                    message = "ESM module manifest cannot be loaded: canonical guest-runtime ${guestManifest.guestRuntimeVersion} reports esmImports=false",
                )
            }
        }
        if (maxFrameBytes <= 0) {
            throw IllegalArgumentException("Invalid maxFrameBytes=$maxFrameBytes: must be > 0")
        }
        return SproutSurface(
            engine = this,
            surfaceId = surfaceId,
            surfaceGeneration = surfaceGeneration,
            catalog = catalog,
            profile = profile,
            portableSpecMode = portableSpecMode,
            initialState = initialState,
            initialStateRevision = initialStateRevision,
            limits = limits,
            maxFrameBytes = maxFrameBytes,
            initialRuntimeEpoch = initialRuntimeEpoch,
            useCells = useCells,
            incrementalPreview = incrementalPreview,
            maxCatchUpMs = maxCatchUpMs,
            requestedReducedMotion = reducedMotion,
            revisionFence = revisionFence,
            primitivesVersion = primitivesVersion,
            adapters = adapters,
        )
    }

    override fun close() {
        compiler.close()
    }
}

/**
 * Encapsulates a single canonical `guest-runtime.js` QuickJS VM instance and its trusted Section H guards:
 * 1. Injects native host bindings (`__log`, `__now`, `__enter`, `__denied`, `__sharedWrite`) and `raft.{reply, openLink}`
 *    and verifies EVERY `requiredHostBindings` entry is a function BEFORE evaluating `guest-runtime.js` (`HOST_ABI_MISMATCH` if missing).
 * 2. Seals `Function.prototype.apply`, `Function.prototype.call`, `Function.prototype.bind`, `Function.prototype`, and `Reflect.apply`
 *    immediately after evaluating `guest-runtime.js` (before any guest code runs) so `fn.apply(undefined, arguments)` inside `entry(name, fn)`
 *    cannot expose internal unguarded entry functions through prototype tampering.
 * 3. Stores the one-shot `enterPermit` (`1 -> 0`) and `activeHostEntry` in Kotlin JVM memory, and independently verifies
 *    `activeHostEntry == "__dispatch"` inside `__sharedWrite` and `__hostAction` as a second-layer host call-cause guard.
 */
internal fun guestLogDiagnosticCode(level: String, message: String): String? = when {
    level == "error" && message.startsWith("timer callback:") -> "RUNTIME_ERROR"
    level == "warn" && message.startsWith("more than ") -> "TIMERS_DROPPED"
    else -> null
}

internal class GuestVmHandle(
    val vm: QuickJs,
    val epoch: Long,
    val guestRuntimeVersion: String,
    val guestAbiVersion: Int,
    private val limits: RuntimeLimits,
    private val isSourceFinished: () -> Boolean,
    private val onClosedOnce: () -> Unit = {},
) : AutoCloseable {
    private var closedOnce = false
    var activeLogicalNowMs: Long = 0L
        private set
    var activeHostEntry: String = "none"
        private set
    private var enterPermit: Int = 0

    val pendingDiagnostics = ArrayList<ProtocolDiagnostic>()
    val pendingActions = ArrayList<Pair<String, String>>()
    var latestSharedWriteJson: String? = null
    var needsSharedRollback: Boolean = false

    fun clearCallScratch() {
        pendingDiagnostics.clear()
        pendingActions.clear()
        latestSharedWriteJson = null
        needsSharedRollback = false
    }

    private fun hostOriginLabel(): String = when (activeHostEntry) {
        "__tick" -> "timer"
        "__load", "__loadParts" -> "module"
        "__render", "__sharedLoad" -> "render"
        else -> "none"
    }

    fun installBindings(omitRequiredBindingsForTest: Set<String> = emptySet()) {
        if ("__log" !in omitRequiredBindingsForTest) {
            vm.function("__log") { args ->
                val level = args.getOrNull(0)?.toString() ?: "log"
                val message = args.getOrNull(1)?.toString() ?: ""
                val diagCode = guestLogDiagnosticCode(level, message)
                if (diagCode != null) {
                    pendingDiagnostics.add(
                        ProtocolDiagnostic(
                            code = diagCode,
                            phase = "timer",
                            message = message.takeUnicodeCodePoints(300),
                            origin = DiagnosticOrigin.HOST,
                        ),
                    )
                }
                Unit
            }
        }
        if ("__now" !in omitRequiredBindingsForTest) {
            vm.function("__now") {
                activeLogicalNowMs.toDouble()
            }
        }
        if ("__enter" !in omitRequiredBindingsForTest) {
            vm.function("__enter") { args ->
                val requestedName = args.getOrNull(0)?.toString()
                if (enterPermit == 1 && requestedName != null && requestedName == activeHostEntry) {
                    enterPermit = 0
                    1
                } else {
                    pendingDiagnostics.add(
                        ProtocolDiagnostic(
                            code = "EFFECT_SUPPRESSED",
                            phase = "engine",
                            message = "called the host's entry point ${requestedName ?: "unknown"} itself [origin: ${hostOriginLabel()}]",
                            origin = DiagnosticOrigin.HOST,
                        ),
                    )
                    0
                }
            }
        }
        if ("__denied" !in omitRequiredBindingsForTest) {
            vm.function("__denied") { args ->
                val what = args.getOrNull(0)?.toString() ?: "effect"
                val origin = args.getOrNull(1)?.toString() ?: "none"
                val detail = args.getOrNull(2)?.toString() ?: ""
                val phase = when (what) {
                    "entry" -> "engine"
                    "state" -> "state"
                    else -> "action"
                }
                val msg = when (what) {
                    "entry" -> "the program called the host's entry point $detail itself [origin: $origin]: refused; only the host dispatches events, loads, ticks and renders"
                    "state" -> "shared state write ($detail) was dropped [origin: $origin]: only an event handler may write shared state"
                    else -> "raft.$what was dropped [origin: $origin]: only an event handler may ask for an action"
                }
                pendingDiagnostics.add(
                    ProtocolDiagnostic(
                        code = "EFFECT_SUPPRESSED",
                        phase = phase,
                        message = msg.takeUnicodeCodePoints(300),
                        origin = DiagnosticOrigin.HOST,
                    ),
                )
                Unit
            }
        }
        if ("__sharedWrite" !in omitRequiredBindingsForTest) {
            vm.function("__sharedWrite") { args ->
                val wholeJson = args.getOrNull(0)?.toString() ?: "{}"
                if (activeHostEntry != "__dispatch") {
                    needsSharedRollback = true
                    pendingDiagnostics.add(
                        ProtocolDiagnostic(
                            code = "EFFECT_SUPPRESSED",
                            phase = "state",
                            message = "Host rejected shared state write outside dispatch [origin: ${hostOriginLabel()}]",
                            origin = DiagnosticOrigin.HOST,
                        ),
                    )
                } else if (!isSourceFinished()) {
                    needsSharedRollback = true
                    pendingDiagnostics.add(
                        ProtocolDiagnostic(
                            code = "EFFECT_SUPPRESSED",
                            phase = "state",
                            message = "Suppressed shared state write during streaming preview [origin: preview]",
                            origin = DiagnosticOrigin.HOST,
                        ),
                    )
                } else {
                    latestSharedWriteJson = wholeJson
                }
                Unit
            }
        }
        vm.function("__hostAction") { args ->
            val actionName = args.getOrNull(0)?.toString() ?: "action"
            val payloadJson = args.getOrNull(1)?.toString() ?: "null"
            if (activeHostEntry != "__dispatch") {
                pendingDiagnostics.add(
                    ProtocolDiagnostic(
                        code = "EFFECT_SUPPRESSED",
                        phase = "action",
                        message = "Host rejected '$actionName' outside dispatch [origin: ${hostOriginLabel()}]",
                        origin = DiagnosticOrigin.HOST,
                    ),
                )
            } else if (!isSourceFinished()) {
                pendingDiagnostics.add(
                    ProtocolDiagnostic(
                        code = "EFFECT_SUPPRESSED",
                        phase = "action",
                        message = "Suppressed '$actionName' during streaming preview [origin: preview]",
                        origin = DiagnosticOrigin.HOST,
                    ),
                )
            } else {
                pendingActions.add(actionName to payloadJson)
            }
            Unit
        }

        // Pre-bootstrap `G.raft` using captured `JSON.stringify` and delete `__hostAction` from global scope
        SyncQuickJs.evalSync<Any?>(
            vm = vm,
            budgetMs = 200L,
            phase = "engine",
            code = """
                (function(G) {
                  "use strict";
                  var rawAction = G.__hostAction;
                  delete G.__hostAction;
                  var stringify = G.JSON.stringify;
                  G.raft = {
                    reply: function(v) {
                      var s;
                      try { s = stringify(v === undefined ? null : v); } catch (e) { s = "null"; }
                      rawAction("reply", typeof s === "string" ? s : "null");
                    },
                    openLink: function(v) {
                      var s;
                      try { s = stringify(v === undefined ? null : v); } catch (e) { s = "null"; }
                      rawAction("openLink", typeof s === "string" ? s : "null");
                    }
                  };
                })(globalThis);
            """.trimIndent(),
            filename = "host-pre-bootstrap",
        )
    }

    fun evaluateAndSealGuestRuntime(
        guestRuntimeSource: String,
        manifest: GuestRuntimeAbiManifest,
    ) {
        // 1. Fail closed if ANY required host binding (`__enter`, `__log`, `__now`, `__denied`, `__sharedWrite`) is missing!
        val requiredJson = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), manifest.requiredHostBindings)
        val missingPre = SyncQuickJs.evalSync<String>(
            vm = vm,
            budgetMs = 200L,
            phase = "engine",
            code = """
                (function(G, req) {
                  var missing = [];
                  for (var i = 0; i < req.length; i++) {
                    if (typeof G[req[i]] !== "function") missing.push(req[i]);
                  }
                  return missing.join(",");
                })(globalThis, $requiredJson)
            """.trimIndent(),
            filename = "verify-required-bindings",
        )
        if (missingPre.isNotEmpty()) {
            throw SproutRuntimeException(
                code = "HOST_ABI_MISMATCH",
                phase = "engine",
                message = "Cannot initialize canonical guest-runtime: missing required host binding(s): $missingPre",
            )
        }

        // 2. Evaluate unmodified canonical `guest-runtime.js` (which itself also throws without __log/__now/__enter in abiVersion 2)
        try {
            SyncQuickJs.evalSync<Any?>(
                vm = vm,
                budgetMs = 2_000L,
                phase = "engine",
                code = guestRuntimeSource,
                filename = manifest.file,
            )
        } catch (err: SproutRuntimeException) {
            if ("host bindings" in err.message) {
                throw SproutRuntimeException("HOST_ABI_MISMATCH", "engine", err.message, err)
            }
            throw err
        }

        // 3. Verify globals were consumed, all required entries exist, unarmed entry throws, AND seal `Function.prototype.{apply,call,bind}` + `Reflect.apply`!
        val consumedJson = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), manifest.consumedUnderscoreBindings)
        val requiredEntriesJson = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), manifest.requiredEntries)
        val abiPostError = SyncQuickJs.evalSync<String>(
            vm = vm,
            budgetMs = 200L,
            phase = "engine",
            code = """
                (function(G, consumed, reqEntries) {
                  "use strict";
                  var i;
                  for (i = 0; i < consumed.length; i++) {
                    if (typeof G[consumed[i]] !== "undefined") return "unconsumed_binding:" + consumed[i];
                  }
                  for (i = 0; i < reqEntries.length; i++) {
                    if (typeof G[reqEntries[i]] !== "function") return "missing_entry:" + reqEntries[i];
                  }
                  var unarmedThrew = false;
                  try {
                    G.__schedule();
                  } catch (e) {
                    unarmedThrew = String(e && e.message || e).indexOf("is the host's to call") >= 0;
                  }
                  if (!unarmedThrew) return "unarmed_entry_did_not_throw";
                  var fp = G.Function.prototype;
                  G.Object.defineProperty(fp, "apply", { value: fp.apply, writable: false, configurable: false });
                  G.Object.defineProperty(fp, "call", { value: fp.call, writable: false, configurable: false });
                  G.Object.defineProperty(fp, "bind", { value: fp.bind, writable: false, configurable: false });
                  G.Object.freeze(fp);
                  if (G.Reflect && typeof G.Reflect.apply === "function") {
                    G.Object.defineProperty(G.Reflect, "apply", { value: G.Reflect.apply, writable: false, configurable: false });
                  }
                  return "";
                })(globalThis, $consumedJson, $requiredEntriesJson)
            """.trimIndent(),
            filename = "verify-and-seal-abi",
        )
        if (abiPostError.isNotEmpty()) {
            throw SproutRuntimeException(
                code = "HOST_ABI_MISMATCH",
                phase = "engine",
                message = "Canonical guest-runtime ABI check failed after evaluation: $abiPostError",
            )
        }
    }

    fun <T> invokeEntry(
        entryName: String,
        budgetMs: Long,
        phase: String,
        jsCallExpr: String,
        logicalNowMs: Long = activeLogicalNowMs,
    ): T {
        activeLogicalNowMs = logicalNowMs
        activeHostEntry = entryName
        enterPermit = 1
        try {
            return SyncQuickJs.evalSync(
                vm = vm,
                budgetMs = budgetMs,
                phase = phase,
                code = jsCallExpr,
                filename = entryName,
            )
        } finally {
            enterPermit = 0
            activeHostEntry = "none"
        }
    }

    override fun close() {
        enterPermit = 0
        activeHostEntry = "none"
        if (!vm.isClosed) {
            runCatching { vm.close() }
        }
        if (!closedOnce) {
            closedOnce = true
            onClosedOnce()
        }
    }
}

/**
 * Isolated per-surface session + QuickJS runtime implementing Spec v6 §15, Spec v8 §17, Spec v9 §18 (`B5`), and Spec v10 §19 (`H`):
 * - Runs the shared canonical `guest-runtime.js` (`0.0.0+2fff75eaa746`) verified by SHA-256 and `GuestRuntimeAbiManifest`.
 * - Enforces one-shot `__enter` permit, frozen `Function.prototype.{apply,call,bind}`, and independent host callback origin checks.
 * - `finishSource` validates strict output in a brand-new QuickJS VM (`runtimeEpoch + 1`) whenever incremental/tolerant loads or preview interactions/ticks ran, and is idempotent on duplicate calls.
 * - Implements single-in-flight pending state overlay (`initState`, `proposeState`, `applyStateReceipt`) with unique `<sessionInstanceId>.<seq>` IDs.
 */
class SproutSurface internal constructor(
    private val engine: SproutEngine,
    val surfaceId: String,
    val surfaceGeneration: Long?,
    val catalog: Catalog,
    val profile: ValidationProfile,
    val portableSpecMode: PortableSpecMode,
    initialState: JsonObject,
    initialStateRevision: Long,
    private val limits: RuntimeLimits,
    private val maxFrameBytes: Int,
    initialRuntimeEpoch: Long,
    private val useCells: Boolean,
    private val incrementalPreview: Boolean,
    val maxCatchUpMs: Long = 0L,
    private val requestedReducedMotion: Boolean = false,
    val revisionFence: Boolean = false,
    val primitivesVersion: String = DEFAULT_PORTABLE_PRIMITIVES_VERSION,
    val adapters: SurfaceAdapters = SurfaceAdapters(),
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private var guest: GuestVmHandle

    private var disposed = false
    private var visible = true
    private var foreground = true
    private var logicalClockOffsetMs: Long = 0L
    private var pausedAtWallMs: Long? = null

    private var activeSourceRevision: Long? = null
    private val sourceRevision: Long get() = activeSourceRevision ?: 0L
    var runtimeEpoch: Long = initialRuntimeEpoch
        private set

    private fun nextRuntimeEpochOrNull(): Long? =
        if (runtimeEpoch >= build.raft.sprout.protocol.MAX_JS_SAFE_INTEGER) null else runtimeEpoch + 1L
    private var confirmedStateRevision: Long = initialStateRevision
    private var confirmedSharedState: JsonObject = canonicalizeJsonObject(initialState)
    private var sourceFinished = false
    private var abandonedState: String? = null
    private var previewStale = false
    private var reducedMotion: Boolean = requestedReducedMotion
    var status: SurfaceStatus = SurfaceStatus.PREVIEW
        private set
    var inertReason: InertReason = InertReason.NONE
        private set

    val interactionEnabled: Boolean
        get() = inertReason == InertReason.NONE

    private val sourceBuffer = StringBuilder()
    private var loadCountInCurrentVm = 0
    private var ranTolerantOrIncrementalLoad = false
    private var touchedInCurrentVm = false
    private var loadedStrictJs: String? = null
    private var initStateProposedForRevision: Long = -1L

    private class ActiveOutRequest(
        val operationId: String,
        val surfaceId: String,
        val surfaceGeneration: Long?,
        val sourceRevision: Long,
        val baseRevision: Long,
        val operations: List<SharedStateOp>,
        var withdrawn: Boolean = false,
        var uncertain: Boolean = false,
        var attempts: Int = 1,
    ) {
        fun toProposeEffect(): OutboundEffect.ProposeState = OutboundEffect.ProposeState(
            operationId = operationId,
            surfaceId = surfaceId,
            surfaceGeneration = surfaceGeneration,
            sourceRevision = sourceRevision,
            baseRevision = baseRevision,
            operations = operations,
        )

        fun toUncertainSnapshot(): UncertainStateOperation? = if (uncertain) {
            UncertainStateOperation(
                operationId = operationId,
                baseRevision = baseRevision,
                operations = operations,
                attempts = attempts,
                withdrawn = withdrawn,
            )
        } else {
            null
        }
    }

    private class SuppressedCallEffectLedger(
        val sharedViewBeforeCall: JsonObject? = null,
        val enqueuedStateOperationId: String? = null,
        val promotedStateOperationId: String? = null,
        val createdActionRequestIds: List<String> = emptyList(),
    ) {
        companion object {
            val NONE = SuppressedCallEffectLedger()
        }
    }

    private val pendingBatches = ArrayDeque<PendingStateOperation>()
    private var activeOut: ActiveOutRequest? = null
    private val fencedOps = LinkedHashMap<String, Long>()
    private var pendingInitOpId: String? = null
    private val pendingRequestIds = LinkedHashSet<String>()

    fun uncertainOperation(): UncertainStateOperation? = activeOut?.toUncertainSnapshot()

    fun pendingOperations(): List<PendingStateOperation> = pendingBatches.toList()

    fun repeatedDiagnostics(): RepeatedDiagnostics {
        if (disposed || guest.vm.isClosed || "__reports" !in engine.guestManifest.allEntries) {
            return RepeatedDiagnostics(available = false)
        }
        val rawText = runCatching {
            guest.invokeEntry<String?>(
                entryName = "__reports",
                budgetMs = 50L,
                phase = "engine",
                jsCallExpr = "typeof globalThis.__reports === 'function' ? globalThis.__reports() : null",
            )
        }.getOrNull() ?: return RepeatedDiagnostics(available = false)
        val parsed = runCatching { json.parseToJsonElement(rawText).jsonObject }.getOrNull()
            ?: return RepeatedDiagnostics(available = false)
        val rawEntries = (parsed["e"] as? kotlinx.serialization.json.JsonArray)
            ?: return RepeatedDiagnostics(available = false)
        val entries = ArrayList<RepeatedDiagnosticEntry>()
        for (el in rawEntries) {
            val obj = el as? JsonObject ?: continue
            val l = (obj["l"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val m = (obj["m"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val n = obj["n"]?.jsonPrimitive?.intOrNull ?: continue
            val code = guestLogDiagnosticCode(l, m) ?: continue
            entries.add(
                RepeatedDiagnosticEntry(
                    code = code,
                    phase = "timer",
                    message = m.takeUnicodeCodePoints(300),
                    count = n,
                ),
            )
        }
        val suppressed = parsed["s"]?.jsonPrimitive?.intOrNull ?: 0
        return RepeatedDiagnostics(available = true, entries = entries, suppressed = suppressed)
    }

    private var refs = TreeRefs("$surfaceId:${catalog.catalogId}:${catalog.catalogVersion}:$profile:${engine.guestRuntimeVersion}")
    private var wholeTreesOnly = false
    internal var liveGuestVmCountForTest: Int = 0
        private set
    private val elementBindingsIndex = HashMap<String, Map<String, EventBinding>>()
    private var lastValidFrame: SurfaceFrame
    private var lastSuggestedInitialState: JsonObject = JsonObject(emptyMap())
    private var nextTimerDueMs: Long? = null
    private var hasAnimationFrame: Boolean = false
    private val startupCapabilityDiagnostics = ArrayList<ProtocolDiagnostic>()

    private fun compilerSessionKey(): String = "$surfaceId#${surfaceGeneration ?: "local"}#$runtimeEpoch#$sourceRevision"

    val currentCommittedFrame: SurfaceFrame get() = lastValidFrame

    val hasCommittedFrame: Boolean get() = lastValidFrame.frameRevision > 0

    fun currentOrNull(): SurfaceFrame? = if (hasCommittedFrame) lastValidFrame else null

    fun capabilities(): GuestRuntimeCapabilities = engine.capabilities

    private fun profileWireString(): String = when (profile) {
        ValidationProfile.PORTABLE_V1 -> "portable/1"
        ValidationProfile.WEB_OPEN_V1 -> "web-open/1"
        ValidationProfile.STEP2_VECTOR_COMPAT -> "portable/1"
    }

    init {
        if (requestedReducedMotion && !engine.capabilities.useReducedMotion) {
            startupCapabilityDiagnostics.add(
                ProtocolDiagnostic(
                    code = "UNSUPPORTED_CAPABILITY",
                    phase = "engine",
                    message = "reducedMotion requested, but guest-runtime ${engine.guestRuntimeVersion} reports useReducedMotion=false",
                    origin = DiagnosticOrigin.HOST,
                ),
            )
        }
        lastValidFrame = SurfaceFrame(
            protocol = GENUI_TREE_PROTOCOL_V1,
            surfaceId = surfaceId,
            surfaceGeneration = surfaceGeneration,
            sourceRevision = 0L,
            stateRevision = confirmedStateRevision,
            catalogId = catalog.catalogId,
            catalogVersion = catalog.catalogVersion,
            profile = profileWireString(),
            runtimeEpoch = runtimeEpoch,
            frameRevision = 0L,
            root = emptyList(),
        )
        guest = createInitializedGuestVm(runtimeEpoch, currentSharedView())
    }

    internal fun createInitializedGuestVm(
        epoch: Long,
        stateSnapshot: JsonObject,
        omitRequiredBindingsForTest: Set<String> = emptySet(),
    ): GuestVmHandle {
        if (epoch !in 0L..build.raft.sprout.protocol.MAX_JS_SAFE_INTEGER) {
            throw SproutRuntimeException(
                code = "LIMIT_EXCEEDED",
                phase = "engine",
                message = "runtimeEpoch $epoch exceeds 0..MAX_SAFE_INTEGER (${build.raft.sprout.protocol.MAX_JS_SAFE_INTEGER})",
            )
        }
        val rawVm = QuickJs.create(Dispatchers.Unconfined).apply {
            memoryLimit = limits.memoryLimitBytes
            maxStackSize = limits.maxStackSizeBytes
        }
        liveGuestVmCountForTest += 1
        val handle = GuestVmHandle(
            vm = rawVm,
            epoch = epoch,
            guestRuntimeVersion = engine.guestRuntimeVersion,
            guestAbiVersion = engine.guestAbiVersion,
            limits = limits,
            isSourceFinished = { sourceFinished },
            onClosedOnce = { liveGuestVmCountForTest = (liveGuestVmCountForTest - 1).coerceAtLeast(0) },
        )
        try {
            handle.installBindings(omitRequiredBindingsForTest = omitRequiredBindingsForTest)
            handle.evaluateAndSealGuestRuntime(engine.guestRuntimeSource, engine.guestManifest)

            // Follow GUEST-ABI.md §3 order: `__defineHosts(json)` -> `__epoch(n)` -> `__delta(on)` -> `__sharedLoad(json)`
            val catalogJson = Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
                catalog.components.keys.toList(),
            )
            val escapedCatalog = Json.encodeToString(kotlinx.serialization.serializer<String>(), catalogJson)
            handle.invokeEntry<Any?>(
                entryName = "__defineHosts",
                budgetMs = 100L,
                phase = "engine",
                jsCallExpr = "globalThis.__defineHosts($escapedCatalog)",
            )
            handle.invokeEntry<Any?>(
                entryName = "__epoch",
                budgetMs = 100L,
                phase = "engine",
                jsCallExpr = "globalThis.__epoch($epoch)",
            )
            val deltaFlag = if (useCells && !wholeTreesOnly) 1 else 0
            handle.invokeEntry<Any?>(
                entryName = "__delta",
                budgetMs = 100L,
                phase = "engine",
                jsCallExpr = "globalThis.__delta($deltaFlag)",
            )
            if (reducedMotion && "__motion" in engine.guestManifest.allEntries) {
                handle.invokeEntry<Any?>(
                    entryName = "__motion",
                    budgetMs = 50L,
                    phase = "engine",
                    jsCallExpr = "globalThis.__motion(1)",
                )
            }
            if (stateSnapshot.isNotEmpty()) {
                val escapedState = Json.encodeToString(
                    kotlinx.serialization.serializer<String>(),
                    canonicalizeJsonObject(stateSnapshot).toString(),
                )
                handle.invokeEntry<String>(
                    entryName = "__sharedLoad",
                    budgetMs = 100L,
                    phase = "state",
                    jsCallExpr = "globalThis.__sharedLoad($escapedState)",
                )
            }
            handle.clearCallScratch()
            return handle
        } catch (t: Throwable) {
            handle.close()
            throw t
        }
    }

    private fun currentSharedView(): JsonObject {
        if (pendingBatches.isEmpty()) return confirmedSharedState
        return pendingBatches.last().expectedOverlayAfter
    }

    private fun applyOpsToState(base: JsonObject, ops: List<SharedStateOp>): JsonObject {
        val mutable = LinkedHashMap<String, JsonElement>(base)
        for (op in ops) {
            when (op) {
                is SharedStateOp.Set -> mutable[op.key] = canonicalizeJsonElement(op.value)
                is SharedStateOp.Delete -> mutable.remove(op.key)
            }
        }
        return canonicalizeJsonObject(JsonObject(mutable))
    }

    private fun drainStartupDiagnostics(): List<ProtocolDiagnostic> {
        if (startupCapabilityDiagnostics.isEmpty()) return emptyList()
        val copy = startupCapabilityDiagnostics.toList()
        startupCapabilityDiagnostics.clear()
        return copy
    }

    private fun resolveLogicalNowMs(wallNowMs: Long): Long = (wallNowMs - logicalClockOffsetMs).coerceAtLeast(0L)

    private fun replaceGuestVmForSourceSwitch(newSourceRevision: Long): List<PendingStateOperation> {
        require(newSourceRevision in 0L..build.raft.sprout.protocol.MAX_JS_SAFE_INTEGER) {
            "Invalid sourceRevision $newSourceRevision: must be within 0..MAX_SAFE_INTEGER"
        }
        val droppedOnSwitch = ArrayList<PendingStateOperation>()
        val currentRev = activeSourceRevision
        if (currentRev != null && newSourceRevision != currentRev) {
            val nextEpoch = nextRuntimeEpochOrNull() ?: throw SproutRuntimeException(
                code = "LIMIT_EXCEEDED",
                phase = "engine",
                message = "Cannot switch to sourceRevision $newSourceRevision: runtimeEpoch $runtimeEpoch reached MAX_SAFE_INTEGER",
            )
            // Build candidate VM with confirmed state ONLY before mutating queues or closing active VM
            val freshGuest = createInitializedGuestVm(nextEpoch, confirmedSharedState)

            compiler.dropSession(compilerSessionKey())
            if (pendingBatches.isNotEmpty() || (activeOut != null && !activeOut!!.withdrawn)) {
                val outId = if (activeOut != null && !activeOut!!.withdrawn) activeOut!!.operationId else null
                activeOut?.withdrawn = true
                if (pendingBatches.isNotEmpty()) {
                    for (p in pendingBatches) {
                        droppedOnSwitch.add(p.copy(reason = DroppedOperationReason.SOURCE_SWITCHED.wireName))
                    }
                    pendingBatches.clear()
                    startupCapabilityDiagnostics.add(
                        ProtocolDiagnostic(
                            code = "STATE_CONFLICT",
                            phase = "state",
                            message = "the source changed from revision $currentRev to $newSourceRevision: changes of the previous program that are not confirmed are not carried into the new one${if (outId != null) " ($outId was already sent and is still waited for; only its answer is reconciled)" else ""}",
                            origin = DiagnosticOrigin.HOST,
                        ),
                    )
                }
            }
            pendingInitOpId = null

            // Old tree stays on screen until the new VM renders, but its handlers are immediately stale (`source-replaced`, v6 §15 Item 2)
            inertReason = InertReason.SOURCE_REPLACED
            elementBindingsIndex.clear()
            nextTimerDueMs = null
            hasAnimationFrame = false

            val oldGuest = guest
            guest = freshGuest
            oldGuest.close()

            runtimeEpoch = nextEpoch
            refs = TreeRefs("$surfaceId:${catalog.catalogId}:${catalog.catalogVersion}:$profile:${engine.guestRuntimeVersion}")
            loadCountInCurrentVm = 0
            ranTolerantOrIncrementalLoad = false
            touchedInCurrentVm = false
            loadedStrictJs = null
        }
        activeSourceRevision = newSourceRevision
        sourceFinished = false
        abandonedState = null
        previewStale = false
        status = SurfaceStatus.PREVIEW
        return droppedOnSwitch
    }

    private val compiler: QuickJsCompiler get() = engine.compiler

    /**
     * Synchronous source load (`setSource`, Spec v6 §3 & §15 Item 1):
     * - When `finished == true`, compiles strictly, loads whole, and finalizes in one pass (`status = READY`, `epoch` unchanged).
     * - When `finished == false` (preview), uses `compilePreview` (statement-by-statement `__loadParts` when `incrementalPreview` and all statements are declarations, or whole `__load` otherwise).
     */
    fun setSource(
        revision: Int,
        source: String,
        nowMs: Long = 0L,
        precompiledJs: String? = null,
        finished: Boolean = false,
    ): FrameCommit = setSource(revision.toLong(), source, nowMs, precompiledJs, finished)

    fun setSource(
        revision: Long,
        source: String,
        nowMs: Long = 0L,
        precompiledJs: String? = null,
        finished: Boolean = false,
    ): FrameCommit {
        checkNotDisposed()
        if (revision !in 0L..build.raft.sprout.protocol.MAX_JS_SAFE_INTEGER) {
            return diagnosticOnLastValid(
                ProtocolDiagnostic(
                    code = "SOURCE_CONFLICT",
                    phase = "source",
                    message = "setSource needs an integer revision in 0..MAX_SAFE_INTEGER (got $revision)",
                    origin = DiagnosticOrigin.HOST,
                ),
            )
        }
        val currentRev = activeSourceRevision
        if (currentRev != null && revision < currentRev) {
            return diagnosticOnLastValid(
                ProtocolDiagnostic("SOURCE_STALE", "source", "Ignored stale sourceRevision $revision < $currentRev", origin = DiagnosticOrigin.HOST),
            )
        }
        if (abandonedState != null && revision == currentRev) {
            return diagnosticOnLastValid(
                ProtocolDiagnostic("SOURCE_ABANDONED", "source", "Source revision $revision was already $abandonedState", origin = DiagnosticOrigin.HOST),
            )
        }
        if (currentRev != null && revision == currentRev && sourceFinished) {
            if (source == sourceBuffer.toString()) {
                return unchangedCommit()
            }
            return diagnosticOnLastValid(
                ProtocolDiagnostic(
                    code = "SOURCE_CONFLICT",
                    phase = "source",
                    message = "Source revision $revision is already finished with different text",
                    origin = DiagnosticOrigin.HOST,
                ),
            )
        }
        if (currentRev != null && revision != currentRev && nextRuntimeEpochOrNull() == null) {
            return diagnosticOnLastValid(
                ProtocolDiagnostic(
                    code = "LIMIT_EXCEEDED",
                    phase = "engine",
                    message = "Cannot switch to sourceRevision $revision: runtimeEpoch $runtimeEpoch reached MAX_SAFE_INTEGER",
                    origin = DiagnosticOrigin.HOST,
                ),
            )
        }
        val dropped = try {
            replaceGuestVmForSourceSwitch(revision)
        } catch (err: SproutRuntimeException) {
            return diagnosticOnLastValid(
                ProtocolDiagnostic(code = err.code, phase = err.phase, message = err.message, origin = DiagnosticOrigin.HOST),
            )
        }
        sourceBuffer.setLength(0)
        sourceBuffer.append(source)
        val logicalNow = resolveLogicalNowMs(nowMs)

        if (finished) {
            val compileMark = TimeSource.Monotonic.markNow()
            val strictJs = try {
                precompiledJs ?: compiler.compileStrict(source)
            } catch (err: SproutRuntimeException) {
                status = SurfaceStatus.FINAL_INVALID
                inertReason = InertReason.FINAL_INVALID
                elementBindingsIndex.clear()
                nextTimerDueMs = null
                hasAnimationFrame = false
                return diagnosticOnLastValid(
                    ProtocolDiagnostic(code = err.code, phase = err.phase, message = err.message, origin = DiagnosticOrigin.HOST),
                    dropped = dropped,
                )
            }
            val compileMs = compileMark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)

            // If current VM already executed anything, replace with a fresh VM before strict finished load
            if (loadCountInCurrentVm > 0 || touchedInCurrentVm) {
                val nextEpoch = nextRuntimeEpochOrNull()
                if (nextEpoch == null) {
                    markFinalInvalidReadOnly()
                    return diagnosticOnLastValid(
                        ProtocolDiagnostic(
                            code = "LIMIT_EXCEEDED",
                            phase = "engine",
                            message = "Cannot finalize sourceRevision $revision in fresh VM: runtimeEpoch $runtimeEpoch reached MAX_SAFE_INTEGER",
                            origin = DiagnosticOrigin.HOST,
                        ),
                        dropped = dropped,
                    )
                }
                val freshGuest = try {
                    createInitializedGuestVm(nextEpoch, currentSharedView())
                } catch (err: SproutRuntimeException) {
                    markFinalInvalidReadOnly()
                    return diagnosticOnLastValid(
                        ProtocolDiagnostic(code = err.code, phase = err.phase, message = err.message, origin = DiagnosticOrigin.HOST),
                        dropped = dropped,
                    )
                }
                val oldGuest = guest
                guest = freshGuest
                oldGuest.close()
                runtimeEpoch = nextEpoch
                refs = TreeRefs("$surfaceId:${catalog.catalogId}:${catalog.catalogVersion}:$profile:${engine.guestRuntimeVersion}")
                loadCountInCurrentVm = 0
                ranTolerantOrIncrementalLoad = false
                touchedInCurrentVm = false
            }

            sourceFinished = true
            status = SurfaceStatus.READY
            loadedStrictJs = strictJs
            ranTolerantOrIncrementalLoad = false

            val commit = executeLoadInGuest(
                targetGuest = guest,
                loadOutput = PreviewCompileOutput.Whole(strictJs),
                compileMs = compileMs,
                logicalNowMs = logicalNow,
                isStreamingAppend = false,
                isStrictFinish = true,
                dropped = dropped,
            )
            return maybeAttachInitStateEffect(commit)
        }

        val compileMark = TimeSource.Monotonic.markNow()
        var compiledStrictlyWhole = false
        val previewOut: PreviewCompileOutput = try {
            if (precompiledJs != null) {
                PreviewCompileOutput.Whole(precompiledJs)
            } else {
                val preview = compiler.compilePreview(
                    sessionKey = compilerSessionKey(),
                    source = source,
                    useCells = useCells,
                    allowParts = incrementalPreview,
                )
                if (preview is PreviewCompileOutput.Whole) {
                    val strictCandidate = runCatching { compiler.compileStrict(source) }.getOrNull()
                    if (strictCandidate != null && strictCandidate == preview.code) {
                        compiledStrictlyWhole = true
                    }
                }
                preview
            }
        } catch (err: SproutRuntimeException) {
            return markPreviewCommitIncomplete(
                diagnosticOnLastValid(
                    ProtocolDiagnostic(code = err.code, phase = err.phase, message = err.message, origin = DiagnosticOrigin.HOST),
                    dropped = dropped,
                ),
            )
        }
        val compileMs = compileMark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)

        if (!compiledStrictlyWhole || loadCountInCurrentVm > 0 || previewOut is PreviewCompileOutput.Parts) {
            ranTolerantOrIncrementalLoad = true
            loadedStrictJs = null
        } else if (previewOut is PreviewCompileOutput.Whole) {
            loadedStrictJs = previewOut.code
        }

        return markPreviewCommitIncomplete(
            executeLoadInGuest(
                targetGuest = guest,
                loadOutput = previewOut,
                compileMs = compileMs,
                logicalNowMs = logicalNow,
                isStreamingAppend = false,
                isStrictFinish = false,
                dropped = dropped,
            ),
        )
    }

    /**
     * Synchronous streaming append (`appendSource`, Spec v6 §3, §4, §15):
     * - Keeps the SAME `runtimeEpoch` across all streaming chunks of `revision` so kept-subtree handlers stay live during preview;
     * - Marks `ranTolerantOrIncrementalLoad = true` so `finishSource` always rebuilds in a fresh guest VM (`runtimeEpoch + 1`).
     */
    fun appendSource(revision: Int, offset: Int, text: String, nowMs: Long = 0L): FrameCommit =
        appendSource(revision.toLong(), offset, text, nowMs)

    fun appendSource(revision: Long, offset: Int, text: String, nowMs: Long = 0L): FrameCommit {
        checkNotDisposed()
        if (revision !in 0L..build.raft.sprout.protocol.MAX_JS_SAFE_INTEGER || offset < 0) {
            return diagnosticOnLastValid(
                ProtocolDiagnostic(
                    code = "SOURCE_CONFLICT",
                    phase = "source",
                    message = "appendSource needs an integer revision in 0..MAX_SAFE_INTEGER and an offset >= 0 (got revision=$revision, offset=$offset)",
                    origin = DiagnosticOrigin.HOST,
                ),
            )
        }
        val currentRev = activeSourceRevision
        if (currentRev != null && revision < currentRev) {
            return diagnosticOnLastValid(
                ProtocolDiagnostic("SOURCE_STALE", "source", "Ignored stale sourceRevision $revision < $currentRev", origin = DiagnosticOrigin.HOST),
            )
        }
        var dropped = emptyList<PendingStateOperation>()
        if (currentRev == null || revision != currentRev) {
            if (offset != 0) {
                return diagnosticOnLastValid(
                    ProtocolDiagnostic("SOURCE_GAP", "source", "New sourceRevision $revision must start at offset 0 (got $offset)", origin = DiagnosticOrigin.HOST),
                )
            }
            if (currentRev != null && nextRuntimeEpochOrNull() == null) {
                return diagnosticOnLastValid(
                    ProtocolDiagnostic(
                        code = "LIMIT_EXCEEDED",
                        phase = "engine",
                        message = "Cannot switch to sourceRevision $revision: runtimeEpoch $runtimeEpoch reached MAX_SAFE_INTEGER",
                        origin = DiagnosticOrigin.HOST,
                    ),
                )
            }
            dropped = try {
                replaceGuestVmForSourceSwitch(revision)
            } catch (err: SproutRuntimeException) {
                return diagnosticOnLastValid(
                    ProtocolDiagnostic(code = err.code, phase = err.phase, message = err.message, origin = DiagnosticOrigin.HOST),
                )
            }
            sourceBuffer.setLength(0)
        } else if (abandonedState != null) {
            return diagnosticOnLastValid(
                ProtocolDiagnostic("SOURCE_ABANDONED", "source", "Source revision $revision was already $abandonedState", origin = DiagnosticOrigin.HOST),
            )
        } else if (sourceFinished) {
            return diagnosticOnLastValid(
                ProtocolDiagnostic("SOURCE_CONFLICT", "source", "Cannot appendSource to already finished revision $revision", origin = DiagnosticOrigin.HOST),
            )
        }
        val currentLen = sourceBuffer.length
        if (offset > currentLen) {
            throw SproutRuntimeException("SOURCE_GAP", "source", "Stream gap at offset $offset (current buffer length $currentLen)")
        }
        if (offset + text.length <= currentLen) {
            val overlap = sourceBuffer.substring(offset, offset + text.length)
            if (overlap != text) {
                throw SproutRuntimeException("SOURCE_CONFLICT", "source", "Conflicting duplicate chunk at offset $offset")
            }
            return unchangedCommit(dropped = dropped)
        }
        val expectedPrefixLen = currentLen - offset
        if (expectedPrefixLen > 0 && text.substring(0, expectedPrefixLen) != sourceBuffer.substring(offset, currentLen)) {
            throw SproutRuntimeException("SOURCE_CONFLICT", "source", "Conflicting overlapping prefix at offset $offset")
        }
        val newSuffix = text.substring(expectedPrefixLen)
        sourceBuffer.append(newSuffix)
        ranTolerantOrIncrementalLoad = true
        loadedStrictJs = null

        val compileMark = TimeSource.Monotonic.markNow()
        val previewOut = try {
            compiler.compilePreview(
                sessionKey = compilerSessionKey(),
                source = sourceBuffer.toString(),
                useCells = useCells,
                allowParts = incrementalPreview,
            )
        } catch (err: SproutRuntimeException) {
            return markPreviewCommitIncomplete(
                diagnosticOnLastValid(
                    ProtocolDiagnostic(code = err.code, phase = err.phase, message = err.message, origin = DiagnosticOrigin.HOST),
                    dropped = dropped,
                ),
            )
        }
        val compileMs = compileMark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
        return markPreviewCommitIncomplete(
            executeLoadInGuest(
                targetGuest = guest,
                loadOutput = previewOut,
                compileMs = compileMs,
                logicalNowMs = resolveLogicalNowMs(nowMs),
                isStreamingAppend = true,
                isStrictFinish = false,
                dropped = dropped,
            ),
        )
    }

    /**
     * Synchronous transactional finalization (`finishSource`, Spec v6 §15 Item 1 & Spec v8 §17 Item 6):
     * - Idempotent when `revision == sourceRevision` is already finished (`changed = false, effects = [], diagnostics = []`).
     * - Reuses the active VM ONLY if it ran a single clean whole-program strict load and was never touched by events or ticks.
     * - Otherwise builds and validates the strict program in a brand-new QuickJS VM (`runtimeEpoch + 1`) before swapping!
     */
    fun abandonSource(revision: Int, state: String, message: String? = null): FrameCommit =
        abandonSource(revision.toLong(), state, message)

    fun abandonSource(revision: Long, state: String, message: String? = null): FrameCommit {
        checkNotDisposed()
        if (state != "cancelled" && state != "error") {
            return diagnosticOnLastValid(
                ProtocolDiagnostic("SOURCE_CONFLICT", "source", "abandonSource state must be 'cancelled' or 'error'", origin = DiagnosticOrigin.HOST),
            )
        }
        val currentRev = activeSourceRevision
        if (currentRev == null || revision != currentRev || sourceFinished) {
            return diagnosticOnLastValid(
                ProtocolDiagnostic("SOURCE_STALE", "source", "Cannot abandon revision $revision (active=${currentRev ?: "none"}, finished=$sourceFinished)", origin = DiagnosticOrigin.HOST),
            )
        }
        if (abandonedState == null) {
            abandonedState = state
            status = if (state == "cancelled") SurfaceStatus.CANCELLED else SurfaceStatus.ERROR
            inertReason = if (state == "cancelled") InertReason.SOURCE_CANCELLED else InertReason.SOURCE_ERROR
            previewStale = false
            elementBindingsIndex.clear()
            nextTimerDueMs = null
            hasAnimationFrame = false
        }
        val suffix = if (!message.isNullOrBlank()) ": $message" else ""
        return diagnosticOnLastValid(
            ProtocolDiagnostic(
                code = "SOURCE_ABANDONED",
                phase = "source",
                message = "Source revision $revision ended as $state$suffix",
                origin = DiagnosticOrigin.HOST,
            ),
        )
    }

    fun finishSource(revision: Int, expectedSha256Hex: String? = null, nowMs: Long = 0L): FrameCommit =
        finishSource(revision.toLong(), expectedSha256Hex, nowMs)

    fun finishSource(revision: Long, expectedSha256Hex: String? = null, nowMs: Long = 0L): FrameCommit {
        checkNotDisposed()
        val currentRev = activeSourceRevision
        if (currentRev == null || revision != currentRev) {
            return diagnosticOnLastValid(
                ProtocolDiagnostic("SOURCE_STALE", "source", "finishSource revision $revision != active ${currentRev ?: "none"}", origin = DiagnosticOrigin.HOST),
            )
        }
        if (abandonedState != null) {
            return diagnosticOnLastValid(
                ProtocolDiagnostic("SOURCE_ABANDONED", "source", "Source revision $revision was already $abandonedState", origin = DiagnosticOrigin.HOST),
            )
        }
        previewStale = false
        val fullSource = sourceBuffer.toString()
        if (expectedSha256Hex != null) {
            val actualHex = sha256Hex(fullSource)
            if (!actualHex.equals(expectedSha256Hex, ignoreCase = true)) {
                if (sourceFinished) {
                    throw SproutRuntimeException("SOURCE_CONFLICT", "source", "finishSource digest conflicts with already finished revision $revision")
                }
                markFinalInvalidReadOnly()
                return diagnosticOnLastValid(
                    ProtocolDiagnostic(
                        code = "SOURCE_DIGEST_MISMATCH",
                        phase = "source",
                        message = "Source SHA-256 mismatch on finishSource",
                        origin = DiagnosticOrigin.HOST,
                    ),
                )
            }
        }
        if (sourceFinished) {
            return unchangedCommit()
        }

        val compileMark = TimeSource.Monotonic.markNow()
        val strictJs = try {
            compiler.compileStrict(fullSource)
        } catch (err: SproutRuntimeException) {
            markFinalInvalidReadOnly()
            return diagnosticOnLastValid(
                ProtocolDiagnostic(
                    code = "COMPILE_ERROR",
                    phase = "compile",
                    message = "Finished source failed strict syntax validation: ${err.message.takeUnicodeCodePoints(240)}",
                    origin = DiagnosticOrigin.HOST,
                ),
            )
        }
        val compileMs = compileMark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)

        val canReuseCleanWholeVm =
            loadCountInCurrentVm == 1 &&
                !ranTolerantOrIncrementalLoad &&
                !touchedInCurrentVm &&
                loadedStrictJs == strictJs &&
                interactionEnabled

        if (canReuseCleanWholeVm) {
            sourceFinished = true
            status = SurfaceStatus.READY
            val reused = FrameCommit(
                frame = lastValidFrame,
                diagnostics = drainStartupDiagnostics(),
                stats = ValidationStats(),
                sharedSubtreeNodes = 0,
                effects = emptyList(),
                suggestedInitialState = lastSuggestedInitialState,
                nextTimerDueMs = nextTimerDueMs,
                hasAnimationFrame = hasAnimationFrame,
                interactionEnabled = true,
                inertReason = InertReason.NONE,
                status = SurfaceStatus.READY,
                changed = false,
                pendingStateOperations = pendingBatches.size,
                guestRuntimeVersion = engine.guestRuntimeVersion,
                guestAbiVersion = engine.guestAbiVersion,
                compileMs = compileMs,
                executeMs = 0.0,
                validateMs = 0.0,
            )
            return maybeAttachInitStateEffect(reused)
        }

        val candidateEpoch = nextRuntimeEpochOrNull()
        if (candidateEpoch == null) {
            markFinalInvalidReadOnly()
            return diagnosticOnLastValid(
                ProtocolDiagnostic(
                    code = "LIMIT_EXCEEDED",
                    phase = "engine",
                    message = "Cannot finalize sourceRevision $revision in fresh VM: runtimeEpoch $runtimeEpoch reached MAX_SAFE_INTEGER",
                    origin = DiagnosticOrigin.HOST,
                ),
            )
        }
        val candidateRefs = TreeRefs("$surfaceId:${catalog.catalogId}:${catalog.catalogVersion}:$profile:${engine.guestRuntimeVersion}")
        val candidateGuest = try {
            createInitializedGuestVm(candidateEpoch, currentSharedView())
        } catch (err: SproutRuntimeException) {
            markFinalInvalidReadOnly()
            return diagnosticOnLastValid(
                ProtocolDiagnostic(code = err.code, phase = err.phase, message = err.message, origin = DiagnosticOrigin.HOST),
            )
        }

        val logicalNow = resolveLogicalNowMs(nowMs)
        val execMark = TimeSource.Monotonic.markNow()
        val escapedCode = Json.encodeToString(kotlinx.serialization.serializer<String>(), strictJs)
        val renderedFrameJson: String
        var scheduleJson: String
        var defaultsJson: String
        try {
            candidateGuest.clearCallScratch()
            candidateGuest.invokeEntry<Any?>(
                entryName = "__load",
                budgetMs = limits.sourceUpdateBudgetMs,
                phase = "module",
                jsCallExpr = "globalThis.__load($escapedCode, 0)",
                logicalNowMs = logicalNow,
            )
            renderedFrameJson = candidateGuest.invokeEntry(
                entryName = "__render",
                budgetMs = limits.sourceUpdateBudgetMs,
                phase = "render",
                jsCallExpr = "globalThis.__render(1)",
                logicalNowMs = logicalNow,
            )
            scheduleJson = candidateGuest.invokeEntry(
                entryName = "__schedule",
                budgetMs = 50L,
                phase = "engine",
                jsCallExpr = "globalThis.__schedule()",
                logicalNowMs = logicalNow,
            )
            defaultsJson = candidateGuest.invokeEntry(
                entryName = "__sharedDefaults",
                budgetMs = 50L,
                phase = "state",
                jsCallExpr = "globalThis.__sharedDefaults()",
                logicalNowMs = logicalNow,
            )
        } catch (err: SproutRuntimeException) {
            val scratchDiags = candidateGuest.pendingDiagnostics.toList()
            candidateGuest.close()
            markFinalInvalidReadOnly()
            val errDiag = ProtocolDiagnostic(
                code = err.code,
                phase = err.phase,
                message = err.message.takeUnicodeCodePoints(240),
                origin = DiagnosticOrigin.HOST,
            )
            return FrameCommit(
                frame = lastValidFrame,
                diagnostics = scratchDiags + errDiag,
                stats = ValidationStats(hostDiagnostics = scratchDiags.size + 1),
                sharedSubtreeNodes = 0,
                effects = emptyList(),
                suggestedInitialState = lastSuggestedInitialState,
                nextTimerDueMs = null,
                hasAnimationFrame = false,
                interactionEnabled = false,
                inertReason = InertReason.FINAL_INVALID,
                status = SurfaceStatus.FINAL_INVALID,
                changed = false,
                pendingStateOperations = pendingBatches.size,
                guestRuntimeVersion = engine.guestRuntimeVersion,
                guestAbiVersion = engine.guestAbiVersion,
                compileMs = compileMs,
                executeMs = execMark.elapsedNow().toDouble(DurationUnit.MILLISECONDS),
                validateMs = 0.0,
            )
        }
        val execMs = execMark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
        val scratchDiags = drainStartupDiagnostics() + candidateGuest.pendingDiagnostics.toList()
        candidateGuest.clearCallScratch()

        val valMark = TimeSource.Monotonic.markNow()
        val activeCandidateRefs = if (wholeTreesOnly) null else candidateRefs
        var outcome = SproutFrameValidator.validateJsonString(
            rawJson = renderedFrameJson,
            catalog = catalog,
            options = ValidateFrameOptions(
                profile = profile,
                primitivesVersion = primitivesVersion,
                portableSpecMode = portableSpecMode,
                maxFrameBytes = maxFrameBytes,
                idPrefix = "s-$surfaceId-",
                refs = activeCandidateRefs,
                hostEnvelope = buildHostEnvelope(),
                unresolvedGradientPaint = "none",
            ),
        )
        var extraCandidateDiags: List<ProtocolDiagnostic> = emptyList()
        if (outcome.isAccepted && activeCandidateRefs?.unsafeCssAnimationSeen == true) {
            wholeTreesOnly = true
            refs.reset()
            val fullRaw: String
            try {
                candidateGuest.invokeEntry<Any?>(
                    entryName = "__delta",
                    budgetMs = 50L,
                    phase = "engine",
                    jsCallExpr = "globalThis.__delta(0)",
                    logicalNowMs = logicalNow,
                )
                fullRaw = candidateGuest.invokeEntry(
                    entryName = "__render",
                    budgetMs = limits.sourceUpdateBudgetMs,
                    phase = "render",
                    jsCallExpr = "globalThis.__render(0)",
                    logicalNowMs = logicalNow,
                )
                scheduleJson = candidateGuest.invokeEntry(
                    entryName = "__schedule",
                    budgetMs = 50L,
                    phase = "engine",
                    jsCallExpr = "globalThis.__schedule()",
                    logicalNowMs = logicalNow,
                )
                defaultsJson = candidateGuest.invokeEntry(
                    entryName = "__sharedDefaults",
                    budgetMs = 50L,
                    phase = "state",
                    jsCallExpr = "globalThis.__sharedDefaults()",
                    logicalNowMs = logicalNow,
                )
            } catch (err: SproutRuntimeException) {
                val fallbackDiags = candidateGuest.pendingDiagnostics.toList()
                candidateGuest.close()
                markFinalInvalidReadOnly()
                val errDiag = ProtocolDiagnostic(
                    code = err.code,
                    phase = err.phase,
                    message = err.message.takeUnicodeCodePoints(240),
                    origin = DiagnosticOrigin.HOST,
                )
                val allDiags = scratchDiags + fallbackDiags + errDiag
                return FrameCommit(
                    frame = lastValidFrame,
                    diagnostics = allDiags,
                    stats = ValidationStats(hostDiagnostics = allDiags.size),
                    sharedSubtreeNodes = 0,
                    effects = emptyList(),
                    suggestedInitialState = lastSuggestedInitialState,
                    nextTimerDueMs = null,
                    hasAnimationFrame = false,
                    interactionEnabled = false,
                    inertReason = InertReason.FINAL_INVALID,
                    status = SurfaceStatus.FINAL_INVALID,
                    changed = false,
                    pendingStateOperations = pendingBatches.size,
                    guestRuntimeVersion = engine.guestRuntimeVersion,
                    guestAbiVersion = engine.guestAbiVersion,
                    compileMs = compileMs,
                    executeMs = execMark.elapsedNow().toDouble(DurationUnit.MILLISECONDS),
                    validateMs = valMark.elapsedNow().toDouble(DurationUnit.MILLISECONDS),
                )
            }
            extraCandidateDiags = candidateGuest.pendingDiagnostics.toList()
            candidateGuest.clearCallScratch()
            outcome = SproutFrameValidator.validateJsonString(
                rawJson = fullRaw,
                catalog = catalog,
                options = ValidateFrameOptions(
                    profile = profile,
                    primitivesVersion = primitivesVersion,
                    portableSpecMode = portableSpecMode,
                    maxFrameBytes = maxFrameBytes,
                    idPrefix = "s-$surfaceId-",
                    refs = null,
                    hostEnvelope = buildHostEnvelope(),
                    unresolvedGradientPaint = "none",
                ),
            )
        }
        val valMs = valMark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)

        val validatedFrame = outcome.frame
        if (!outcome.isAccepted || validatedFrame == null) {
            candidateGuest.close()
            markFinalInvalidReadOnly()
            return FrameCommit(
                frame = lastValidFrame,
                diagnostics = scratchDiags + extraCandidateDiags + outcome.diagnostics,
                stats = outcome.stats,
                sharedSubtreeNodes = 0,
                effects = emptyList(),
                suggestedInitialState = lastSuggestedInitialState,
                nextTimerDueMs = null,
                hasAnimationFrame = false,
                interactionEnabled = false,
                inertReason = InertReason.FINAL_INVALID,
                status = SurfaceStatus.FINAL_INVALID,
                changed = false,
                pendingStateOperations = pendingBatches.size,
                guestRuntimeVersion = engine.guestRuntimeVersion,
                guestAbiVersion = engine.guestAbiVersion,
                compileMs = compileMs,
                executeMs = execMs,
                validateMs = valMs,
            )
        }

        // Candidate VM + frame validated cleanly -> atomically swap guest VM, advance runtimeEpoch, keep surfaceGeneration unchanged!
        compiler.dropSession(compilerSessionKey())
        val oldGuest = guest
        guest = candidateGuest
        oldGuest.close()

        runtimeEpoch = candidateEpoch
        refs = candidateRefs
        if (wholeTreesOnly) refs.reset()
        loadCountInCurrentVm = 1
        ranTolerantOrIncrementalLoad = false
        touchedInCurrentVm = false
        loadedStrictJs = strictJs
        sourceFinished = true
        status = SurfaceStatus.READY
        inertReason = InertReason.NONE

        applyScheduleJson(scheduleJson)
        lastSuggestedInitialState = canonicalizeJsonObject(json.parseToJsonElement(defaultsJson).jsonObject)

        val shared = TreeSharer.shareTree(
            prev = if (lastValidFrame.frameRevision > 0) lastValidFrame.root else null,
            next = validatedFrame.root,
            refs = if (wholeTreesOnly) null else refs,
        )
        val committedFrame = validatedFrame.copy(root = shared.nodes)
        lastValidFrame = committedFrame
        rebuildBindingsIndex(committedFrame.root)

        val commit = FrameCommit(
            frame = committedFrame,
            diagnostics = scratchDiags + outcome.diagnostics,
            stats = outcome.stats,
            sharedSubtreeNodes = shared.sharedNodes,
            effects = emptyList(),
            suggestedInitialState = lastSuggestedInitialState,
            nextTimerDueMs = nextTimerDueMs,
            hasAnimationFrame = hasAnimationFrame,
            interactionEnabled = true,
            inertReason = InertReason.NONE,
            status = SurfaceStatus.READY,
            changed = true,
            pendingStateOperations = pendingBatches.size,
            guestRuntimeVersion = engine.guestRuntimeVersion,
            guestAbiVersion = engine.guestAbiVersion,
            compileMs = compileMs,
            executeMs = execMs,
            validateMs = valMs,
        )
        return maybeAttachInitStateEffect(commit)
    }

    /**
     * Verified synchronous event dispatch (`dispatch`, Spec v6 §15 Item 3, 4, 6, 10, 13):
     * - Checks `SproutEventValidator.staleEventReason` (surfaceId, surfaceGeneration, interactionEnabled, epoch, frameRevision, nodeId, eventName, handler).
     * - Atomically validates `arguments` via `SproutEventValidator.validateEventArgs` (`INVALID_EVENT`, zero silent truncation).
     * - Immediately suppresses and rolls back shared-state writes in streaming preview (`!sourceFinished`), keeping local `useState` live.
     */
    fun dispatch(
        nodeId: String,
        eventName: String,
        targetFrameRevision: Long,
        targetRuntimeEpoch: Long = runtimeEpoch,
        arguments: JsonArray = JsonArray(emptyList()),
        nowMs: Long = 0L,
        targetSurfaceId: String = surfaceId,
        targetSurfaceGeneration: Long? = surfaceGeneration,
        targetHandler: Int? = null,
    ): FrameCommit {
        checkNotDisposed()
        val staleReason = SproutEventValidator.staleEventReason(
            committedSurfaceId = surfaceId,
            committedSurfaceGeneration = surfaceGeneration,
            committedRuntimeEpoch = runtimeEpoch,
            tableIsCurrent = interactionEnabled,
            committedFrameRuntimeEpoch = lastValidFrame.runtimeEpoch,
            committedFrameRevision = lastValidFrame.frameRevision,
            committedTree = lastValidFrame.root,
            eventSurfaceId = targetSurfaceId,
            eventSurfaceGeneration = targetSurfaceGeneration,
            eventEpoch = targetRuntimeEpoch,
            eventFrameRevision = targetFrameRevision,
            nodeId = nodeId,
            eventName = eventName,
            handler = targetHandler,
        )
        if (staleReason != null) {
            return diagnosticOnLastValid(
                ProtocolDiagnostic(
                    code = "STALE_EVENT",
                    phase = "event",
                    message = "Rejected stale event '$eventName' on '$nodeId': $staleReason",
                    nodeId = nodeId,
                    origin = DiagnosticOrigin.HOST,
                ),
            )
        }

        val binding = elementBindingsIndex[nodeId]?.get(eventName)
            ?: return diagnosticOnLastValid(
                ProtocolDiagnostic(
                    code = "STALE_EVENT",
                    phase = "event",
                    message = "No active binding for '$eventName' on '$nodeId'",
                    nodeId = nodeId,
                    origin = DiagnosticOrigin.HOST,
                ),
            )

        val targetElement = findElementById(lastValidFrame.root, nodeId)
        val maxTextLen = if (targetElement != null) {
            SproutEventValidator.eventTextLimit(catalog, targetElement.kind, targetElement.type)
        } else {
            ProtocolLimits.MAX_EVENT_STRING_CHARS
        }
        val compEventArgs = if (targetElement?.kind == NodeKind.COMPONENT) {
            catalog.components[targetElement.type]?.eventArgs ?: emptyMap()
        } else {
            emptyMap()
        }
        val argError = SproutEventValidator.validateEventArgs(
            eventName = eventName,
            args = arguments,
            maxStringCodePoints = maxTextLen,
            componentEventArgs = compEventArgs,
        )
        if (argError != null) {
            return diagnosticOnLastValid(
                ProtocolDiagnostic(
                    code = "INVALID_EVENT",
                    phase = "event",
                    message = argError,
                    nodeId = nodeId,
                    origin = DiagnosticOrigin.HOST,
                ),
            )
        }

        val logicalNow = resolveLogicalNowMs(nowMs)
        val viewBefore = currentSharedView()
        touchedInCurrentVm = true
        guest.clearCallScratch()

        val execMark = TimeSource.Monotonic.markNow()
        val escapedArgs = Json.encodeToString(kotlinx.serialization.serializer<String>(), arguments.toString())
        var frameJsonStr: String = try {
            guest.invokeEntry(
                entryName = "__dispatch",
                budgetMs = limits.dispatchBudgetMs,
                phase = "event",
                jsCallExpr = "globalThis.__dispatch(${binding.handler}, $escapedArgs)",
                logicalNowMs = logicalNow,
            )
        } catch (err: SproutRuntimeException) {
            val scratchDiags = guest.pendingDiagnostics.toList()
            if (guest.latestSharedWriteJson != null || guest.needsSharedRollback) {
                rollbackGuestSharedQuietly(viewBefore, logicalNow)
            }
            guest.clearCallScratch()
            return diagnosticOnLastValid(
                ProtocolDiagnostic(
                    code = err.code,
                    phase = err.phase,
                    message = err.message.takeUnicodeCodePoints(240),
                    nodeId = nodeId,
                    origin = DiagnosticOrigin.HOST,
                ),
                extraDiags = scratchDiags,
            )
        }

        val outboundEffects = ArrayList<OutboundEffect>()
        val extraDiags = ArrayList<ProtocolDiagnostic>(drainStartupDiagnostics())
        extraDiags.addAll(guest.pendingDiagnostics)
        var enqueuedStateOpId: String? = null
        var promotedStateOpId: String? = null
        val createdActionRequestIds = ArrayList<String>()

        fun applyRollbackFrameOrMarkInert() {
            try {
                val rollbackFrameJson = rollbackGuestShared(viewBefore, logicalNow)
                if (rollbackFrameJson.isNotEmpty()) {
                    frameJsonStr = rollbackFrameJson
                }
            } catch (err: SproutRuntimeException) {
                extraDiags.addAll(guest.pendingDiagnostics)
                guest.clearCallScratch()
                extraDiags.add(
                    ProtocolDiagnostic(
                        code = err.code,
                        phase = "state",
                        message = err.message.takeUnicodeCodePoints(240),
                        nodeId = nodeId,
                        origin = DiagnosticOrigin.HOST,
                    ),
                )
                frameJsonStr = ""
                if (lastValidFrame.frameRevision > 0L) {
                    if (inertReason == InertReason.NONE) {
                        inertReason = InertReason.FRAME_REJECTED
                    }
                    elementBindingsIndex.clear()
                }
            }
        }

        // Process shared-state writes or roll back suppressed preview writes in the SAME call (Ruling 3 & 6)
        if (guest.needsSharedRollback) {
            applyRollbackFrameOrMarkInert()
        } else if (sourceFinished && guest.latestSharedWriteJson != null) {
            val nextWhole = canonicalizeJsonObject(json.parseToJsonElement(guest.latestSharedWriteJson!!).jsonObject)
            val ops = diffSharedStateOps(viewBefore, nextWhole)
            if (ops.isNotEmpty()) {
                if (!adapters.state) {
                    val keys = ops.joinToString(", ") { op ->
                        when (op) {
                            is SharedStateOp.Set -> op.key
                            is SharedStateOp.Delete -> op.key
                        }
                    }.takeUnicodeCodePoints(120)
                    extraDiags.add(
                        ProtocolDiagnostic(
                            code = "UNSUPPORTED_CAPABILITY",
                            phase = "state",
                            message = "shared state write ($keys) was not saved: this surface has no host adapter for shared state (local state still works; nothing was saved or shared)",
                            nodeId = nodeId,
                            origin = DiagnosticOrigin.HOST,
                        ),
                    )
                    applyRollbackFrameOrMarkInert()
                } else if (pendingBatches.size >= limits.maxPendingStateOperations) {
                    extraDiags.add(
                        ProtocolDiagnostic(
                            code = "STATE_QUEUE_FULL",
                            phase = "state",
                            message = "Pending shared state queue full (${limits.maxPendingStateOperations})",
                            nodeId = nodeId,
                            origin = DiagnosticOrigin.HOST,
                        ),
                    )
                    applyRollbackFrameOrMarkInert()
                } else {
                    val opId = engine.nextUnrepeatableId()
                    val overlayAfter = applyOpsToState(viewBefore, ops)
                    val batch = PendingStateOperation(
                        operationId = opId,
                        operations = ops,
                        expectedOverlayAfter = overlayAfter,
                        sourceRevision = sourceRevision,
                        baseRevision = confirmedStateRevision,
                    )
                    pendingBatches.addLast(batch)
                    enqueuedStateOpId = opId
                    promotedStateOpId = promoteNextPendingIfIdle(outboundEffects)
                }
            }
        }

        if (sourceFinished) {
            for ((actionName, payloadJson) in guest.pendingActions) {
                val payloadEl = runCatching { json.parseToJsonElement(payloadJson) }.getOrDefault(JsonNull)
                if (actionName == "openLink") {
                    val urlStr = (payloadEl as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
                    if (!isSafeHttpActionUrl(urlStr)) {
                        extraDiags.add(
                            ProtocolDiagnostic(
                                code = "LINK_BLOCKED",
                                phase = "action",
                                message = "raft.openLink: not an http(s) URL: ${urlStr.takeUnicodeCodePoints(120)}",
                                nodeId = nodeId,
                                origin = DiagnosticOrigin.HOST,
                            ),
                        )
                    } else if (!adapters.navigation) {
                        extraDiags.add(
                            ProtocolDiagnostic(
                                code = "UNSUPPORTED_CAPABILITY",
                                phase = "action",
                                message = "raft.openLink was not sent: this surface has no host adapter for navigation (nothing was opened)",
                                nodeId = nodeId,
                                origin = DiagnosticOrigin.HOST,
                            ),
                        )
                    } else {
                        outboundEffects.add(
                            OutboundEffect.Navigate(
                                surfaceId = surfaceId,
                                url = urlStr.trim(),
                            ),
                        )
                    }
                } else {
                    if (!adapters.action) {
                        extraDiags.add(
                            ProtocolDiagnostic(
                                code = "UNSUPPORTED_CAPABILITY",
                                phase = "action",
                                message = "raft.$actionName was not sent: this surface has no host adapter for actions (nothing was delivered to anyone)",
                                nodeId = nodeId,
                                origin = DiagnosticOrigin.HOST,
                            ),
                        )
                    } else if (pendingRequestIds.size >= limits.maxPendingRequests) {
                        extraDiags.add(
                            ProtocolDiagnostic(
                                code = "EFFECT_SUPPRESSED",
                                phase = "action",
                                message = "raft.$actionName was not sent: ${pendingRequestIds.size} requests wait for a result",
                                nodeId = nodeId,
                                origin = DiagnosticOrigin.HOST,
                            ),
                        )
                    } else {
                        val reqId = engine.nextUnrepeatableId()
                        pendingRequestIds.add(reqId)
                        createdActionRequestIds.add(reqId)
                        outboundEffects.add(
                            OutboundEffect.ActionRequest(
                                action = actionName,
                                payload = payloadEl,
                                requestId = reqId,
                                surfaceId = surfaceId,
                                surfaceGeneration = surfaceGeneration,
                                sourceRevision = sourceRevision,
                                expectedStateRevision = confirmedStateRevision,
                            ),
                        )
                    }
                }
            }
        }
        guest.clearCallScratch()

        val callLedger = SuppressedCallEffectLedger(
            sharedViewBeforeCall = if (enqueuedStateOpId != null) viewBefore else null,
            enqueuedStateOperationId = enqueuedStateOpId,
            promotedStateOperationId = promotedStateOpId,
            createdActionRequestIds = createdActionRequestIds,
        )

        val scheduleJson = try {
            guest.invokeEntry<String>(
                entryName = "__schedule",
                budgetMs = 50L,
                phase = "engine",
                jsCallExpr = "globalThis.__schedule()",
                logicalNowMs = logicalNow,
            )
        } catch (err: SproutRuntimeException) {
            val scratchDiags = guest.pendingDiagnostics.toList()
            guest.clearCallScratch()
            rollbackSuppressedCallEffects(callLedger, logicalNow)
            if (lastValidFrame.frameRevision > 0L) {
                if (inertReason == InertReason.NONE) {
                    inertReason = InertReason.FRAME_REJECTED
                }
                elementBindingsIndex.clear()
            }
            return diagnosticOnLastValid(
                ProtocolDiagnostic(
                    code = err.code,
                    phase = err.phase,
                    message = err.message.takeUnicodeCodePoints(240),
                    nodeId = nodeId,
                    origin = DiagnosticOrigin.HOST,
                ),
                extraDiags = extraDiags + scratchDiags,
            )
        }
        applyScheduleJson(scheduleJson)
        val execMs = execMark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)

        if (frameJsonStr.isEmpty()) {
            return FrameCommit(
                frame = lastValidFrame,
                diagnostics = extraDiags,
                stats = ValidationStats(hostDiagnostics = extraDiags.size),
                sharedSubtreeNodes = 0,
                effects = outboundEffects,
                suggestedInitialState = lastSuggestedInitialState,
                nextTimerDueMs = nextTimerDueMs,
                hasAnimationFrame = hasAnimationFrame,
                interactionEnabled = interactionEnabled,
                inertReason = inertReason,
                status = status,
                changed = false,
                pendingStateOperations = pendingBatches.size,
                guestRuntimeVersion = engine.guestRuntimeVersion,
                guestAbiVersion = engine.guestAbiVersion,
                compileMs = 0.0,
                executeMs = execMs,
                validateMs = 0.0,
            )
        }

        return validateGuestFrameAndCommit(
            guestFrameRawJson = frameJsonStr,
            compileMs = 0.0,
            execMs = execMs,
            effects = outboundEffects,
            extraHostDiagnostics = extraDiags,
            isStreamingAppend = false,
            logicalNowMs = logicalNow,
            callLedger = callLedger,
        )
    }

    /**
     * Synchronous local timer tick (`tick`, paused when hidden/backgrounded, local state ONLY, Spec v6 §15 Item 4, 7, 13):
     * - Returns `null` when hidden/backgrounded, when `inertReason` is `FINAL_INVALID`/`SOURCE_REPLACED`, or when no frame or diagnostic was produced.
     * - Note: When `inertReason == FRAME_REJECTED`, timers still advance so a subsequent valid frame can restore interactivity (Ruling 13)!
     */
    fun tick(nowMs: Long): FrameCommit? {
        val res = tickWithDiagnostics(nowMs)
        if (!visible || !foreground) return null
        if (!res.changed && res.diagnostics.isEmpty()) return null
        return res
    }

    /**
     * Full diagnostic-bearing `tick` (`NOT_VISIBLE` when hidden/backgrounded, Spec v6 §15 Item 7 & `session-semantic.json`).
     */
    fun tickWithDiagnostics(nowMs: Long): FrameCommit {
        checkNotDisposed()
        if (!visible || !foreground) {
            return diagnosticOnLastValid(
                ProtocolDiagnostic(
                    code = "NOT_VISIBLE",
                    phase = "timer",
                    message = "Timer tick ignored while surface is hidden or backgrounded",
                    origin = DiagnosticOrigin.HOST,
                ),
            )
        }
        if (inertReason == InertReason.FINAL_INVALID || inertReason == InertReason.SOURCE_REPLACED) {
            return unchangedCommit()
        }

        val logicalNow = resolveLogicalNowMs(nowMs)
        val viewBefore = currentSharedView()
        touchedInCurrentVm = true
        guest.clearCallScratch()

        val execMark = TimeSource.Monotonic.markNow()
        val rawJson = try {
            guest.invokeEntry<String>(
                entryName = "__tick",
                budgetMs = limits.tickBudgetMs,
                phase = "timer",
                jsCallExpr = "globalThis.__tick($logicalNow)",
                logicalNowMs = logicalNow,
            )
        } catch (err: SproutRuntimeException) {
            val scratchDiags = guest.pendingDiagnostics.toList()
            if (guest.needsSharedRollback) rollbackGuestSharedQuietly(viewBefore, logicalNow)
            guest.clearCallScratch()
            return diagnosticOnLastValid(
                ProtocolDiagnostic(
                    code = err.code,
                    phase = err.phase,
                    message = err.message.takeUnicodeCodePoints(240),
                    origin = DiagnosticOrigin.HOST,
                ),
                extraDiags = scratchDiags,
            )
        }
        if (guest.needsSharedRollback) {
            rollbackGuestSharedQuietly(viewBefore, logicalNow)
        }
        val extraDiags = drainStartupDiagnostics() + guest.pendingDiagnostics.toList()
        guest.clearCallScratch()

        val execMs = execMark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
        val parsed = json.parseToJsonElement(rawJson).jsonObject
        nextTimerDueMs = parsed["n"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull
        hasAnimationFrame = parsed["r"]?.jsonPrimitive?.booleanOrNull == true
        val frameEl = parsed["t"]?.takeIf { it !is JsonNull }

        if (frameEl == null) {
            return FrameCommit(
                frame = lastValidFrame,
                diagnostics = extraDiags,
                stats = ValidationStats(hostDiagnostics = extraDiags.size),
                sharedSubtreeNodes = 0,
                effects = emptyList(),
                suggestedInitialState = lastSuggestedInitialState,
                nextTimerDueMs = nextTimerDueMs,
                hasAnimationFrame = hasAnimationFrame,
                interactionEnabled = interactionEnabled,
                inertReason = inertReason,
                status = status,
                changed = false,
                pendingStateOperations = pendingBatches.size,
                guestRuntimeVersion = engine.guestRuntimeVersion,
                guestAbiVersion = engine.guestAbiVersion,
                compileMs = 0.0,
                executeMs = execMs,
                validateMs = 0.0,
            )
        }

        return validateGuestFrameAndCommit(
            guestFrameRawJson = frameEl.toString(),
            compileMs = 0.0,
            execMs = execMs,
            effects = emptyList(),
            extraHostDiagnostics = extraDiags,
            isStreamingAppend = false,
            logicalNowMs = logicalNow,
        )
    }

    /** Synchronously applies a confirmed snapshot (`applyState`, shorthand for `StateReceipt.Snapshot`). */
    fun applyState(confirmedRevision: Long, confirmedState: JsonObject): FrameCommit =
        applyStateReceipt(StateReceipt.Snapshot(revision = confirmedRevision, state = confirmedState))

    private fun applyConcurrentAuthoritativeState(
        revision: Long,
        rawState: JsonObject,
        diagnostics: MutableList<ProtocolDiagnostic>,
        droppedOut: MutableList<PendingStateOperation>,
    ): Boolean {
        if (revision <= confirmedStateRevision) return false
        pendingInitOpId = null
        confirmedStateRevision = revision
        confirmedSharedState = canonicalizeJsonObject(rawState)
        activeOut?.withdrawn = true
        if (pendingBatches.isNotEmpty()) {
            val withdrawnList = pendingBatches.map { it.copy(reason = DroppedOperationReason.CONCURRENT_STATE.wireName) }
            pendingBatches.clear()
            droppedOut.addAll(withdrawnList)
            diagnostics.add(
                ProtocolDiagnostic(
                    code = "STATE_CONFLICT",
                    phase = "state",
                    message = "the state changed to revision $revision from elsewhere: operations computed on older data are not re-applied",
                    origin = DiagnosticOrigin.HOST,
                ),
            )
        }
        if (revisionFence) {
            val out = activeOut
            if (out != null && revision > out.baseRevision) {
                activeOut = null
                fencedOps[out.operationId] = out.baseRevision
                while (fencedOps.size > 32) {
                    fencedOps.remove(fencedOps.keys.first())
                }
            }
        }
        return true
    }

    fun retryState(): FrameCommit {
        checkNotDisposed()
        val outboundEffects = ArrayList<OutboundEffect>()
        val out = activeOut
        if (out != null && out.uncertain) {
            out.uncertain = false
            out.attempts += 1
            outboundEffects.add(out.toProposeEffect())
        }
        val diags = drainStartupDiagnostics()
        return FrameCommit(
            frame = lastValidFrame,
            diagnostics = diags,
            stats = ValidationStats(hostDiagnostics = diags.size),
            sharedSubtreeNodes = 0,
            effects = outboundEffects,
            suggestedInitialState = lastSuggestedInitialState,
            nextTimerDueMs = nextTimerDueMs,
            hasAnimationFrame = hasAnimationFrame,
            interactionEnabled = interactionEnabled,
            inertReason = inertReason,
            status = status,
            stalePreview = previewStale && status == SurfaceStatus.PREVIEW,
            uncertain = uncertainOperation(),
            changed = false,
            pendingStateOperations = pendingBatches.size,
            guestRuntimeVersion = engine.guestRuntimeVersion,
            guestAbiVersion = engine.guestAbiVersion,
            compileMs = 0.0,
            executeMs = 0.0,
            validateMs = 0.0,
        )
    }

    /**
     * Synchronously applies an authoritative `StateReceipt` (`Accepted`, `Conflict`, `Denied`, `Uncertain`, `Snapshot`),
     * enforcing single-in-flight pending overlay convergence, revision fence, and frozen retry envelopes.
     */
    fun applyStateReceipt(receipt: StateReceipt, nowMs: Long = 0L): FrameCommit {
        checkNotDisposed()
        if (receipt.surfaceId != null && receipt.surfaceId != surfaceId) {
            return diagnosticOnLastValid(
                ProtocolDiagnostic(
                    code = "STALE_RECEIPT",
                    phase = "state",
                    message = "Receipt surfaceId '${receipt.surfaceId}' != '$surfaceId'",
                    origin = DiagnosticOrigin.HOST,
                ),
            )
        }
        if (receipt.surfaceGeneration != null && receipt.surfaceGeneration != surfaceGeneration) {
            return diagnosticOnLastValid(
                ProtocolDiagnostic(
                    code = "STALE_RECEIPT",
                    phase = "state",
                    message = "Receipt surfaceGeneration ${receipt.surfaceGeneration} != $surfaceGeneration",
                    origin = DiagnosticOrigin.HOST,
                ),
            )
        }
        val receiptRevision = when (receipt) {
            is StateReceipt.Accepted -> receipt.revision
            is StateReceipt.Conflict -> receipt.revision
            is StateReceipt.Snapshot -> receipt.revision
            is StateReceipt.Withdrawn -> receipt.revision
            is StateReceipt.Denied, is StateReceipt.Uncertain -> null
        }
        if (receiptRevision != null && receiptRevision !in 0L..build.raft.sprout.protocol.MAX_JS_SAFE_INTEGER) {
            return diagnosticOnLastValid(
                ProtocolDiagnostic(
                    code = "STALE_RECEIPT",
                    phase = "state",
                    message = "Receipt revision $receiptRevision outside 0..MAX_SAFE_INTEGER",
                    origin = DiagnosticOrigin.HOST,
                ),
            )
        }

        val logicalNow = resolveLogicalNowMs(nowMs)
        val outboundEffects = ArrayList<OutboundEffect>()
        val diagnostics = ArrayList<ProtocolDiagnostic>()
        val dropped = ArrayList<PendingStateOperation>()
        var promotedStateOpId: String? = null

        when (receipt) {
            is StateReceipt.Uncertain -> {
                val waiting = activeOut
                if (waiting == null || waiting.operationId != receipt.operationId) {
                    if (receipt.operationId !in fencedOps) {
                        return diagnosticOnLastValid(
                            ProtocolDiagnostic(
                                code = "STALE_RECEIPT",
                                phase = "state",
                                message = "operation ${receipt.operationId} is not waiting",
                                origin = DiagnosticOrigin.HOST,
                            ),
                        )
                    }
                    return unchangedCommit()
                }
                waiting.uncertain = true
                val behind = pendingBatches.size - (if (waiting.withdrawn) 0 else 1)
                val why = if (!receipt.reason.isNullOrEmpty()) " (${receipt.reason.takeUnicodeCodePoints(80)})" else ""
                return diagnosticOnLastValid(
                    ProtocolDiagnostic(
                        code = "STATE_UNCERTAIN",
                        phase = "state",
                        message = "no answer for ${waiting.operationId}$why, attempt ${waiting.attempts}: it is not known whether it was saved${if (behind > 0) " ($behind later change(s) wait)" else ""}; retry sends the same request again",
                        origin = DiagnosticOrigin.HOST,
                    ),
                )
            }

            is StateReceipt.Snapshot -> {
                if (receipt.revision < confirmedStateRevision) {
                    return diagnosticOnLastValid(
                        ProtocolDiagnostic(
                            code = "STALE_RECEIPT",
                            phase = "state",
                            message = "Snapshot revision ${receipt.revision} < confirmed $confirmedStateRevision",
                            origin = DiagnosticOrigin.HOST,
                        ),
                    )
                }
                if (receipt.revision == confirmedStateRevision) {
                    if (receipt.operationId != null && receipt.operationId == pendingInitOpId) {
                        pendingInitOpId = null
                    }
                    return unchangedCommit()
                }
                applyConcurrentAuthoritativeState(receipt.revision, receipt.state, diagnostics, dropped)
            }

            is StateReceipt.Accepted -> {
                val out = activeOut
                if (out == null || out.operationId != receipt.operationId) {
                    if (fencedOps.remove(receipt.operationId) != null) {
                        val advanced = applyConcurrentAuthoritativeState(receipt.revision, receipt.state, diagnostics, dropped)
                        if (!advanced && diagnostics.isEmpty()) return unchangedCommit()
                    } else {
                        return diagnosticOnLastValid(
                            ProtocolDiagnostic(
                                code = "STALE_RECEIPT",
                                phase = "state",
                                message = "Unknown or stale accepted operationId '${receipt.operationId}'",
                                origin = DiagnosticOrigin.HOST,
                            ),
                        )
                    }
                } else {
                    activeOut = null
                    val canonState = canonicalizeJsonObject(receipt.state)
                    if (out.withdrawn) {
                        applyConcurrentAuthoritativeState(receipt.revision, receipt.state, diagnostics, dropped)
                        promotedStateOpId = promoteNextPendingIfIdle(outboundEffects)
                    } else {
                        val acceptedBatch = pendingBatches.removeFirst()
                        val proven = receipt.revision > confirmedStateRevision &&
                            canonState == applyOpsToState(confirmedSharedState, acceptedBatch.operations)
                        if (receipt.revision > confirmedStateRevision) {
                            confirmedStateRevision = receipt.revision
                            confirmedSharedState = canonState
                        }
                        if (!proven && pendingBatches.isNotEmpty()) {
                            for (p in pendingBatches) {
                                dropped.add(p.copy(reason = DroppedOperationReason.CONCURRENT_STATE.wireName))
                            }
                            pendingBatches.clear()
                            diagnostics.add(
                                ProtocolDiagnostic(
                                    code = "STATE_CONFLICT",
                                    phase = "state",
                                    message = "Accepted receipt state diverged from predecessor overlay; dropped ${dropped.size} dependent operation(s)",
                                    origin = DiagnosticOrigin.HOST,
                                ),
                            )
                        }
                        promotedStateOpId = promoteNextPendingIfIdle(outboundEffects)
                    }
                }
            }

            is StateReceipt.Conflict -> {
                val out = activeOut
                if (out == null || out.operationId != receipt.operationId) {
                    if (fencedOps.remove(receipt.operationId) != null) {
                        val advanced = applyConcurrentAuthoritativeState(receipt.revision, receipt.state, diagnostics, dropped)
                        if (!advanced && diagnostics.isEmpty()) return unchangedCommit()
                    } else {
                        return diagnosticOnLastValid(
                            ProtocolDiagnostic(
                                code = "STALE_RECEIPT",
                                phase = "state",
                                message = "Unknown conflict operationId '${receipt.operationId}'",
                                origin = DiagnosticOrigin.HOST,
                            ),
                        )
                    }
                } else {
                    activeOut = null
                    if (out.withdrawn) {
                        applyConcurrentAuthoritativeState(receipt.revision, receipt.state, diagnostics, dropped)
                    } else {
                        val head = pendingBatches.removeFirst()
                        if (receipt.revision > confirmedStateRevision) {
                            confirmedStateRevision = receipt.revision
                            confirmedSharedState = canonicalizeJsonObject(receipt.state)
                        }
                        dropped.add(head.copy(reason = DroppedOperationReason.CONFLICT.wireName))
                        for (p in pendingBatches) {
                            dropped.add(p.copy(reason = DroppedOperationReason.CONFLICT.wireName))
                        }
                        pendingBatches.clear()
                        diagnostics.add(
                            ProtocolDiagnostic(
                                code = "STATE_CONFLICT",
                                phase = "state",
                                message = "State write conflicted at revision ${receipt.revision}",
                                origin = DiagnosticOrigin.HOST,
                            ),
                        )
                    }
                    promotedStateOpId = promoteNextPendingIfIdle(outboundEffects)
                }
            }

            is StateReceipt.Denied -> {
                val out = activeOut
                if (out == null || out.operationId != receipt.operationId) {
                    if (fencedOps.remove(receipt.operationId) != null) {
                        return unchangedCommit()
                    }
                    return diagnosticOnLastValid(
                        ProtocolDiagnostic(
                            code = "STALE_RECEIPT",
                            phase = "state",
                            message = "Unknown denied operationId '${receipt.operationId}'",
                            origin = DiagnosticOrigin.HOST,
                        ),
                    )
                }
                activeOut = null
                if (!out.withdrawn) {
                    val head = pendingBatches.removeFirst()
                    dropped.add(head.copy(reason = DroppedOperationReason.DENIED.wireName))
                    for (p in pendingBatches) {
                        dropped.add(p.copy(reason = DroppedOperationReason.DENIED.wireName))
                    }
                    pendingBatches.clear()
                    diagnostics.add(
                        ProtocolDiagnostic(
                            code = "STATE_DENIED",
                            phase = "state",
                            message = "State write denied (${receipt.code})",
                            origin = DiagnosticOrigin.HOST,
                        ),
                    )
                }
                promotedStateOpId = promoteNextPendingIfIdle(outboundEffects)
            }

            is StateReceipt.Withdrawn -> {
                val at = pendingBatches.indexOfFirst { it.operationId == receipt.operationId }
                val out = activeOut
                if (at < 0) {
                    if (out != null && out.operationId == receipt.operationId && out.withdrawn) {
                        activeOut = null
                    } else if (fencedOps.remove(receipt.operationId) == null) {
                        return diagnosticOnLastValid(
                            ProtocolDiagnostic(
                                code = "STALE_RECEIPT",
                                phase = "state",
                                message = "operation ${receipt.operationId.take(80)} is not waiting",
                                origin = DiagnosticOrigin.HOST,
                            ),
                        )
                    }
                } else {
                    if (out != null && out.operationId == receipt.operationId) {
                        activeOut = null
                    }
                    val normReason = DroppedOperationReason.normalizeDroppedReason(receipt.reason)
                    val kept = ArrayList<PendingStateOperation>(at)
                    val gone = ArrayList<PendingStateOperation>(pendingBatches.size - at)
                    for ((idx, item) in pendingBatches.withIndex()) {
                        if (idx < at) kept.add(item) else gone.add(item)
                    }
                    pendingBatches.clear()
                    pendingBatches.addAll(kept)
                    val cleanDraft = receipt.draftOperations?.takeIf { it.isNotEmpty() }
                    for (item in gone) {
                        dropped.add(
                            item.copy(
                                reason = normReason,
                                draftOperations = if (item.operationId == receipt.operationId) cleanDraft else null,
                            ),
                        )
                    }
                    val laterSuffix = if (gone.size > 1) {
                        " and the ${gone.size - 1} later change${if (gone.size == 2) "" else "s"} made on it"
                    } else {
                        ""
                    }
                    val idsList = gone.joinToString(", ") { it.operationId }
                    diagnostics.add(
                        ProtocolDiagnostic(
                            code = "STATE_CONFLICT",
                            phase = "state",
                            message = "the host withdrew ${receipt.operationId.take(80)} before it was sent ($normReason)$laterSuffix; taken back, not sent again: $idsList",
                            origin = DiagnosticOrigin.HOST,
                        ),
                    )
                }
                if (receipt.revision > confirmedStateRevision) {
                    applyConcurrentAuthoritativeState(receipt.revision, receipt.state, diagnostics, dropped)
                }
                promotedStateOpId = promoteNextPendingIfIdle(outboundEffects)
            }
        }

        return rerenderFromConfirmedState(
            logicalNow = logicalNow,
            outboundEffects = outboundEffects,
            diagnostics = drainStartupDiagnostics() + diagnostics,
            dropped = dropped,
            callLedger = SuppressedCallEffectLedger(promotedStateOperationId = promotedStateOpId),
        )
    }

    private fun rerenderFromConfirmedState(
        logicalNow: Long,
        outboundEffects: List<OutboundEffect>,
        diagnostics: List<ProtocolDiagnostic>,
        dropped: List<PendingStateOperation>,
        callLedger: SuppressedCallEffectLedger = SuppressedCallEffectLedger.NONE,
    ): FrameCommit {
        if (inertReason == InertReason.FINAL_INVALID || abandonedState != null) {
            lastValidFrame = lastValidFrame.copy(stateRevision = confirmedStateRevision)
            return FrameCommit(
                frame = lastValidFrame,
                diagnostics = diagnostics,
                stats = ValidationStats(hostDiagnostics = diagnostics.size),
                sharedSubtreeNodes = 0,
                effects = outboundEffects,
                suggestedInitialState = lastSuggestedInitialState,
                nextTimerDueMs = null,
                hasAnimationFrame = false,
                interactionEnabled = false,
                inertReason = inertReason,
                status = status,
                uncertain = uncertainOperation(),
                changed = false,
                pendingStateOperations = pendingBatches.size,
                droppedOperations = dropped,
                guestRuntimeVersion = engine.guestRuntimeVersion,
                guestAbiVersion = engine.guestAbiVersion,
                compileMs = 0.0,
                executeMs = 0.0,
                validateMs = 0.0,
            )
        }

        val targetView = currentSharedView()
        val execMark = TimeSource.Monotonic.markNow()
        guest.clearCallScratch()
        val escapedView = Json.encodeToString(kotlinx.serialization.serializer<String>(), targetView.toString())
        val rawFrameJson = try {
            guest.invokeEntry<String>(
                entryName = "__sharedLoad",
                budgetMs = limits.dispatchBudgetMs,
                phase = "state",
                jsCallExpr = "globalThis.__sharedLoad($escapedView)",
                logicalNowMs = logicalNow,
            )
        } catch (err: SproutRuntimeException) {
            val scratchDiags = guest.pendingDiagnostics.toList()
            guest.clearCallScratch()
            rollbackSuppressedCallEffects(callLedger, logicalNow)
            if (lastValidFrame.frameRevision > 0L) {
                if (inertReason == InertReason.NONE) {
                    inertReason = InertReason.FRAME_REJECTED
                }
                elementBindingsIndex.clear()
            }
            lastValidFrame = lastValidFrame.copy(stateRevision = confirmedStateRevision)
            val errDiag = ProtocolDiagnostic(
                code = err.code,
                phase = "state",
                message = err.message.takeUnicodeCodePoints(240),
                origin = DiagnosticOrigin.HOST,
            )
            val failDiags = diagnostics + scratchDiags + errDiag
            return FrameCommit(
                frame = lastValidFrame,
                diagnostics = failDiags,
                stats = ValidationStats(hostDiagnostics = failDiags.size),
                sharedSubtreeNodes = 0,
                effects = emptyList(),
                suggestedInitialState = lastSuggestedInitialState,
                nextTimerDueMs = nextTimerDueMs,
                hasAnimationFrame = hasAnimationFrame,
                interactionEnabled = interactionEnabled,
                inertReason = inertReason,
                status = status,
                uncertain = uncertainOperation(),
                changed = false,
                pendingStateOperations = pendingBatches.size,
                droppedOperations = dropped,
                guestRuntimeVersion = engine.guestRuntimeVersion,
                guestAbiVersion = engine.guestAbiVersion,
                compileMs = 0.0,
                executeMs = execMark.elapsedNow().toDouble(DurationUnit.MILLISECONDS),
                validateMs = 0.0,
            )
        }
        val scratchDiags = guest.pendingDiagnostics.toList()
        guest.clearCallScratch()
        val allDiags = diagnostics + scratchDiags
        val execMs = execMark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)

        if (rawFrameJson.isEmpty()) {
            lastValidFrame = lastValidFrame.copy(stateRevision = confirmedStateRevision)
            return FrameCommit(
                frame = lastValidFrame,
                diagnostics = allDiags,
                stats = ValidationStats(hostDiagnostics = allDiags.size),
                sharedSubtreeNodes = 0,
                effects = outboundEffects,
                suggestedInitialState = lastSuggestedInitialState,
                nextTimerDueMs = nextTimerDueMs,
                hasAnimationFrame = hasAnimationFrame,
                interactionEnabled = interactionEnabled,
                inertReason = inertReason,
                status = status,
                uncertain = uncertainOperation(),
                changed = false,
                pendingStateOperations = pendingBatches.size,
                droppedOperations = dropped,
                guestRuntimeVersion = engine.guestRuntimeVersion,
                guestAbiVersion = engine.guestAbiVersion,
                compileMs = 0.0,
                executeMs = execMs,
                validateMs = 0.0,
            )
        }

        return validateGuestFrameAndCommit(
            guestFrameRawJson = rawFrameJson,
            compileMs = 0.0,
            execMs = execMs,
            effects = outboundEffects,
            extraHostDiagnostics = allDiags,
            isStreamingAppend = false,
            logicalNowMs = logicalNow,
            droppedOperations = dropped,
            callLedger = callLedger,
        )
    }

    /**
     * Updates visibility/foreground/reducedMotion state with monotonic logical clock pause (Spec v6 §15 Item 7 & reducedMotion):
     * - While hidden or backgrounded, the logical clock stands still.
     * - On resume, advances by at most `maxCatchUpMs` (default `0L`, zero jump!).
     */
    fun setVisibility(
        isVisible: Boolean = visible,
        isForeground: Boolean = foreground,
        reducedMotion: Boolean = this.reducedMotion,
        nowMs: Long = 0L,
    ): FrameCommit {
        checkNotDisposed()
        val wasActive = visible && foreground
        val nowActive = isVisible && isForeground
        if (wasActive && !nowActive) {
            pausedAtWallMs = nowMs
        } else if (!wasActive && nowActive) {
            val pausedStart = pausedAtWallMs
            if (pausedStart != null && nowMs > pausedStart) {
                val wallElapsed = nowMs - pausedStart
                val catchUp = wallElapsed.coerceAtMost(maxCatchUpMs)
                logicalClockOffsetMs += (wallElapsed - catchUp)
            }
            pausedAtWallMs = null
        }
        visible = isVisible
        foreground = isForeground
        if (reducedMotion != this.reducedMotion) {
            return setReducedMotion(reducedMotion, nowMs)
        }
        return unchangedCommit()
    }

    fun setReducedMotion(reduce: Boolean, nowMs: Long = 0L): FrameCommit {
        checkNotDisposed()
        val prev = reducedMotion
        reducedMotion = reduce
        if (prev == reduce || inertReason == InertReason.FINAL_INVALID || abandonedState != null) {
            return unchangedCommit()
        }
        if ("__motion" in engine.guestManifest.allEntries) {
            val logicalNow = resolveLogicalNowMs(nowMs)
            val execMark = TimeSource.Monotonic.markNow()
            guest.clearCallScratch()
            val flag = if (reduce) 1 else 0
            val rawFrameJson = guest.invokeEntry<String>(
                entryName = "__motion",
                budgetMs = limits.dispatchBudgetMs,
                phase = "render",
                jsCallExpr = "globalThis.__motion($flag)",
                logicalNowMs = logicalNow,
            )
            if (guest.needsSharedRollback) {
                rollbackGuestSharedQuietly(currentSharedView(), logicalNow)
            }
            val extraDiags = drainStartupDiagnostics() + guest.pendingDiagnostics.toList()
            guest.clearCallScratch()
            val sched = guest.invokeEntry<String>(
                entryName = "__schedule",
                budgetMs = 50L,
                phase = "engine",
                jsCallExpr = "globalThis.__schedule()",
                logicalNowMs = logicalNow,
            )
            applyScheduleJson(sched)
            val execMs = execMark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
            if (rawFrameJson.isNotEmpty()) {
                return validateGuestFrameAndCommit(
                    guestFrameRawJson = rawFrameJson,
                    compileMs = 0.0,
                    execMs = execMs,
                    effects = emptyList(),
                    extraHostDiagnostics = extraDiags,
                    isStreamingAppend = status == SurfaceStatus.PREVIEW,
                    logicalNowMs = logicalNow,
                )
            }
        }
        return unchangedCommit()
    }

    fun applyActionResult(receipt: ActionResultReceipt, nowMs: Long = 0L): FrameCommit {
        checkNotDisposed()
        if (receipt.surfaceId != null && receipt.surfaceId != surfaceId) {
            return diagnosticOnLastValid(
                ProtocolDiagnostic(
                    code = "STALE_RECEIPT",
                    phase = "action",
                    message = "Receipt surfaceId '${receipt.surfaceId}' != '$surfaceId'",
                    origin = DiagnosticOrigin.HOST,
                ),
            )
        }
        if (receipt.surfaceGeneration != null && receipt.surfaceGeneration != surfaceGeneration) {
            return diagnosticOnLastValid(
                ProtocolDiagnostic(
                    code = "STALE_RECEIPT",
                    phase = "action",
                    message = "Receipt surfaceGeneration ${receipt.surfaceGeneration} != $surfaceGeneration",
                    origin = DiagnosticOrigin.HOST,
                ),
            )
        }
        if (!pendingRequestIds.remove(receipt.requestId)) {
            return diagnosticOnLastValid(
                ProtocolDiagnostic(
                    code = "STALE_RECEIPT",
                    phase = "action",
                    message = "request ${receipt.requestId.take(80)} is not waiting",
                    origin = DiagnosticOrigin.HOST,
                ),
            )
        }
        val logicalNow = resolveLogicalNowMs(nowMs)
        val diagnostics = ArrayList<ProtocolDiagnostic>()
        val dropped = ArrayList<PendingStateOperation>()
        val advanced = if (receipt.state != null &&
            receipt.committedStateRevision != null &&
            receipt.committedStateRevision in 0L..build.raft.sprout.protocol.MAX_JS_SAFE_INTEGER
        ) {
            applyConcurrentAuthoritativeState(receipt.committedStateRevision, receipt.state, diagnostics, dropped)
        } else {
            false
        }
        if (advanced) {
            return rerenderFromConfirmedState(
                logicalNow = logicalNow,
                outboundEffects = emptyList(),
                diagnostics = drainStartupDiagnostics() + diagnostics,
                dropped = dropped,
            )
        }
        val allDiags = drainStartupDiagnostics() + diagnostics
        lastValidFrame = lastValidFrame.copy(stateRevision = confirmedStateRevision)
        return FrameCommit(
            frame = lastValidFrame,
            diagnostics = allDiags,
            stats = ValidationStats(hostDiagnostics = allDiags.size),
            sharedSubtreeNodes = 0,
            effects = emptyList(),
            suggestedInitialState = lastSuggestedInitialState,
            nextTimerDueMs = nextTimerDueMs,
            hasAnimationFrame = hasAnimationFrame,
            interactionEnabled = interactionEnabled,
            inertReason = inertReason,
            status = status,
            stalePreview = previewStale && status == SurfaceStatus.PREVIEW,
            uncertain = uncertainOperation(),
            changed = false,
            pendingStateOperations = pendingBatches.size,
            droppedOperations = dropped,
            guestRuntimeVersion = engine.guestRuntimeVersion,
            guestAbiVersion = engine.guestAbiVersion,
            compileMs = 0.0,
            executeMs = 0.0,
            validateMs = 0.0,
        )
    }

    fun memoryUsageBytes(): Long = if (guest.vm.isClosed) 0L else guest.vm.memoryUsage.memoryUsedSize

    override fun close() {
        if (disposed) return
        disposed = true
        compiler.dropSession(compilerSessionKey())
        refs.reset()
        elementBindingsIndex.clear()
        pendingBatches.clear()
        activeOut = null
        fencedOps.clear()
        pendingRequestIds.clear()
        guest.close()
    }

    private fun promoteNextPendingIfIdle(outboundEffects: MutableList<OutboundEffect>): String? {
        if (activeOut == null && pendingBatches.isNotEmpty()) {
            val nextHead = pendingBatches.removeFirst().copy(baseRevision = confirmedStateRevision, sent = true)
            pendingBatches.addFirst(nextHead)
            val frozenSourceRev = if (nextHead.sourceRevision >= 0L) nextHead.sourceRevision else sourceRevision
            val out = ActiveOutRequest(
                operationId = nextHead.operationId,
                surfaceId = surfaceId,
                surfaceGeneration = surfaceGeneration,
                sourceRevision = frozenSourceRev,
                baseRevision = confirmedStateRevision,
                operations = nextHead.operations,
            )
            activeOut = out
            outboundEffects.add(out.toProposeEffect())
            return out.operationId
        }
        return null
    }

    private fun rollbackSuppressedCallEffects(
        callLedger: SuppressedCallEffectLedger,
        logicalNowMs: Long,
    ) {
        for (reqId in callLedger.createdActionRequestIds) {
            pendingRequestIds.remove(reqId)
        }
        val promotedId = callLedger.promotedStateOperationId
        if (promotedId != null) {
            if (activeOut?.operationId == promotedId) {
                activeOut = null
            }
            if (promotedId != callLedger.enqueuedStateOperationId) {
                val head = pendingBatches.firstOrNull()
                if (head != null && head.operationId == promotedId && head.sent) {
                    pendingBatches.removeFirst()
                    pendingBatches.addFirst(head.copy(sent = false))
                }
            }
        }
        val enqueuedId = callLedger.enqueuedStateOperationId
        if (enqueuedId != null) {
            pendingBatches.removeAll { it.operationId == enqueuedId }
        }
        val restoreView = callLedger.sharedViewBeforeCall
        if (restoreView != null) {
            rollbackGuestSharedQuietly(restoreView, logicalNowMs)
            guest.clearCallScratch()
        }
    }

    private fun maybeAttachInitStateEffect(commit: FrameCommit): FrameCommit {
        if (!sourceFinished ||
            status != SurfaceStatus.READY ||
            confirmedStateRevision != 0L ||
            pendingBatches.isNotEmpty() ||
            initStateProposedForRevision == sourceRevision ||
            lastSuggestedInitialState.isEmpty()
        ) {
            return commit
        }
        initStateProposedForRevision = sourceRevision
        if (!adapters.state) {
            val keys = lastSuggestedInitialState.keys.joinToString(", ").takeUnicodeCodePoints(120)
            val diag = ProtocolDiagnostic(
                code = "UNSUPPORTED_CAPABILITY",
                phase = "state",
                message = "useSharedState ($keys) is not saved or shared: this surface has no host adapter for shared state",
                origin = DiagnosticOrigin.HOST,
            )
            return commit.copy(diagnostics = commit.diagnostics + diag)
        }
        val opId = engine.nextUnrepeatableId()
        pendingInitOpId = opId
        val initEffect = OutboundEffect.InitState(
            operationId = opId,
            surfaceId = surfaceId,
            surfaceGeneration = surfaceGeneration,
            sourceRevision = sourceRevision,
            expectedRevision = 0L,
            defaults = lastSuggestedInitialState,
            schemaVersion = 1,
        )
        return commit.copy(effects = commit.effects + initEffect)
    }

    private fun isCurrentSourceCompleteStrict(): Boolean =
        runCatching { compiler.compileStrict(sourceBuffer.toString()) }.isSuccess

    private fun markPreviewCommitIncomplete(commit: FrameCommit): FrameCommit {
        if (sourceFinished || commit.diagnostics.isEmpty()) return commit
        var isComplete: Boolean? = null
        var changedAny = false
        val updated = commit.diagnostics.map { d ->
            val eligiblePhase = d.phase == "compile" || d.phase == "load" || d.phase == "module" || d.phase == "render"
            if (!eligiblePhase) {
                d
            } else {
                val shouldMark = when (d.code) {
                    "COMPILE_ERROR", "NO_ROOT_COMPONENT", "EMPTY_TREE" -> true
                    "RUNTIME_ERROR" -> {
                        val comp = isComplete ?: isCurrentSourceCompleteStrict().also { isComplete = it }
                        !comp
                    }
                    else -> false
                }
                if (shouldMark && d.incomplete != true) {
                    changedAny = true
                    d.copy(incomplete = true)
                } else {
                    d
                }
            }
        }
        return if (changedAny) commit.copy(diagnostics = updated) else commit
    }

    private fun markFinalInvalidReadOnly() {
        status = SurfaceStatus.FINAL_INVALID
        inertReason = InertReason.FINAL_INVALID
        elementBindingsIndex.clear()
        nextTimerDueMs = null
        hasAnimationFrame = false
    }

    private fun rollbackGuestShared(targetView: JsonObject, logicalNowMs: Long): String {
        val escaped = Json.encodeToString(kotlinx.serialization.serializer<String>(), targetView.toString())
        return guest.invokeEntry(
            entryName = "__sharedLoad",
            budgetMs = limits.dispatchBudgetMs,
            phase = "state",
            jsCallExpr = "globalThis.__sharedLoad($escaped)",
            logicalNowMs = logicalNowMs,
        )
    }

    private fun rollbackGuestSharedQuietly(targetView: JsonObject, logicalNowMs: Long) {
        runCatching { rollbackGuestShared(targetView, logicalNowMs) }
    }

    private fun applyScheduleJson(scheduleJson: String) {
        val obj = json.parseToJsonElement(scheduleJson).jsonObject
        nextTimerDueMs = obj["n"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull
        hasAnimationFrame = obj["r"]?.jsonPrimitive?.booleanOrNull == true
    }

    private fun buildHostEnvelope(): HostEnvelopeOverride = HostEnvelopeOverride(
        surfaceId = surfaceId,
        surfaceGeneration = surfaceGeneration,
        sourceRevision = sourceRevision,
        stateRevision = confirmedStateRevision,
        catalogId = catalog.catalogId,
        catalogVersion = catalog.catalogVersion,
        profile = profileWireString(),
    )

    private fun executeLoadInGuest(
        targetGuest: GuestVmHandle,
        loadOutput: PreviewCompileOutput,
        compileMs: Double,
        logicalNowMs: Long,
        isStreamingAppend: Boolean,
        isStrictFinish: Boolean,
        dropped: List<PendingStateOperation> = emptyList(),
    ): FrameCommit {
        val execMark = TimeSource.Monotonic.markNow()
        targetGuest.clearCallScratch()
        val renderedFrameJson: String
        val scheduleJson: String
        val defaultsJson: String
        try {
            when (loadOutput) {
                is PreviewCompileOutput.Parts -> {
                    val resetFlag = if (loadOutput.reset || loadCountInCurrentVm == 0) 1 else 0
                    val staleFlag = if (loadOutput.staleCells) 1 else 0
                    val escApp = Json.encodeToString(kotlinx.serialization.serializer<String>(), loadOutput.appendedJson)
                    val escTail = Json.encodeToString(kotlinx.serialization.serializer<String>(), loadOutput.tailJson)
                    val escCells = Json.encodeToString(kotlinx.serialization.serializer<String>(), loadOutput.cellsJson)
                    try {
                        targetGuest.invokeEntry<Any?>(
                            entryName = "__loadParts",
                            budgetMs = limits.sourceUpdateBudgetMs,
                            phase = "module",
                            jsCallExpr = "globalThis.__loadParts($resetFlag, $escApp, $escTail, 0, $staleFlag, $escCells)",
                            logicalNowMs = logicalNowMs,
                        )
                    } catch (err: SproutRuntimeException) {
                        if ("finished statement:" in err.message) {
                            val wholeFallback = compiler.compileStreaming(compilerSessionKey(), sourceBuffer.toString(), useCells = useCells)
                            val escWhole = Json.encodeToString(kotlinx.serialization.serializer<String>(), wholeFallback)
                            targetGuest.invokeEntry<Any?>(
                                entryName = "__load",
                                budgetMs = limits.sourceUpdateBudgetMs,
                                phase = "module",
                                jsCallExpr = "globalThis.__load($escWhole, 0)",
                                logicalNowMs = logicalNowMs,
                            )
                        } else {
                            throw err
                        }
                    }
                }
                is PreviewCompileOutput.Whole -> {
                    val escCode = Json.encodeToString(kotlinx.serialization.serializer<String>(), loadOutput.code)
                    targetGuest.invokeEntry<Any?>(
                        entryName = "__load",
                        budgetMs = limits.sourceUpdateBudgetMs,
                        phase = "module",
                        jsCallExpr = "globalThis.__load($escCode, 0)",
                        logicalNowMs = logicalNowMs,
                    )
                }
            }
            loadCountInCurrentVm += 1
            renderedFrameJson = targetGuest.invokeEntry(
                entryName = "__render",
                budgetMs = limits.sourceUpdateBudgetMs,
                phase = "render",
                jsCallExpr = "globalThis.__render(0)",
                logicalNowMs = logicalNowMs,
            )
            scheduleJson = targetGuest.invokeEntry(
                entryName = "__schedule",
                budgetMs = 50L,
                phase = "engine",
                jsCallExpr = "globalThis.__schedule()",
                logicalNowMs = logicalNowMs,
            )
            defaultsJson = targetGuest.invokeEntry(
                entryName = "__sharedDefaults",
                budgetMs = 50L,
                phase = "state",
                jsCallExpr = "globalThis.__sharedDefaults()",
                logicalNowMs = logicalNowMs,
            )
        } catch (err: SproutRuntimeException) {
            val scratchDiags = drainStartupDiagnostics() + targetGuest.pendingDiagnostics.toList()
            targetGuest.clearCallScratch()
            if (isStrictFinish) {
                markFinalInvalidReadOnly()
            }
            val errDiag = ProtocolDiagnostic(
                code = err.code,
                phase = err.phase,
                message = err.message.takeUnicodeCodePoints(240),
                origin = DiagnosticOrigin.HOST,
            )
            val allDiags = scratchDiags + errDiag
            return FrameCommit(
                frame = lastValidFrame.copy(sourceRevision = sourceRevision, stateRevision = confirmedStateRevision),
                diagnostics = allDiags,
                stats = ValidationStats(hostDiagnostics = allDiags.size),
                sharedSubtreeNodes = 0,
                effects = emptyList(),
                suggestedInitialState = lastSuggestedInitialState,
                nextTimerDueMs = nextTimerDueMs,
                hasAnimationFrame = hasAnimationFrame,
                interactionEnabled = interactionEnabled,
                inertReason = inertReason,
                status = status,
                changed = false,
                pendingStateOperations = pendingBatches.size,
                droppedOperations = dropped,
                guestRuntimeVersion = engine.guestRuntimeVersion,
                guestAbiVersion = engine.guestAbiVersion,
                compileMs = compileMs,
                executeMs = execMark.elapsedNow().toDouble(DurationUnit.MILLISECONDS),
                validateMs = 0.0,
            )
        }

        val execMs = execMark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
        val extraDiags = drainStartupDiagnostics() + targetGuest.pendingDiagnostics.toList()
        targetGuest.clearCallScratch()

        applyScheduleJson(scheduleJson)
        lastSuggestedInitialState = canonicalizeJsonObject(json.parseToJsonElement(defaultsJson).jsonObject)

        return validateGuestFrameAndCommit(
            guestFrameRawJson = renderedFrameJson,
            compileMs = compileMs,
            execMs = execMs,
            effects = emptyList(),
            extraHostDiagnostics = extraDiags,
            isStreamingAppend = isStreamingAppend,
            logicalNowMs = logicalNowMs,
            isStrictFinish = isStrictFinish,
            droppedOperations = dropped,
        )
    }

    private fun validateGuestFrameAndCommit(
        guestFrameRawJson: String,
        compileMs: Double,
        execMs: Double,
        effects: List<OutboundEffect>,
        extraHostDiagnostics: List<ProtocolDiagnostic> = emptyList(),
        isStreamingAppend: Boolean = false,
        logicalNowMs: Long = 0L,
        allowSnapshotRetry: Boolean = true,
        isStrictFinish: Boolean = false,
        droppedOperations: List<PendingStateOperation> = emptyList(),
        callLedger: SuppressedCallEffectLedger = SuppressedCallEffectLedger.NONE,
    ): FrameCommit {
        val valMark = TimeSource.Monotonic.markNow()
        val activeRefs = if (wholeTreesOnly) null else refs
        val outcome: FrameValidationOutcome = SproutFrameValidator.validateJsonString(
            rawJson = guestFrameRawJson,
            catalog = catalog,
            options = ValidateFrameOptions(
                profile = profile,
                primitivesVersion = primitivesVersion,
                portableSpecMode = portableSpecMode,
                maxFrameBytes = maxFrameBytes,
                idPrefix = "s-$surfaceId-",
                refs = activeRefs,
                hostEnvelope = buildHostEnvelope(),
                unresolvedGradientPaint = "none",
            ),
        )
        val valMs = valMark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)

        fun handleFallbackGuestError(err: SproutRuntimeException): FrameCommit {
            val fallbackDiags = guest.pendingDiagnostics.toList()
            guest.clearCallScratch()
            rollbackSuppressedCallEffects(callLedger, logicalNowMs)
            if (isStrictFinish) {
                markFinalInvalidReadOnly()
            } else {
                if (inertReason == InertReason.NONE) {
                    inertReason = InertReason.FRAME_REJECTED
                }
                elementBindingsIndex.clear()
            }
            val errDiag = ProtocolDiagnostic(
                code = err.code,
                phase = err.phase,
                message = err.message.takeUnicodeCodePoints(240),
                origin = DiagnosticOrigin.HOST,
            )
            val allDiags = extraHostDiagnostics + fallbackDiags + errDiag
            return FrameCommit(
                frame = lastValidFrame,
                diagnostics = allDiags,
                stats = ValidationStats(hostDiagnostics = allDiags.size),
                sharedSubtreeNodes = 0,
                effects = emptyList(),
                suggestedInitialState = lastSuggestedInitialState,
                nextTimerDueMs = nextTimerDueMs,
                hasAnimationFrame = hasAnimationFrame,
                interactionEnabled = false,
                inertReason = inertReason,
                status = status,
                changed = false,
                pendingStateOperations = pendingBatches.size,
                droppedOperations = droppedOperations,
                guestRuntimeVersion = engine.guestRuntimeVersion,
                guestAbiVersion = engine.guestAbiVersion,
                compileMs = compileMs,
                executeMs = execMs,
                validateMs = valMs,
            )
        }

        // Bounded 1-shot recovery on MISSING_REFERENCE / FRAME_BASE_MISMATCH (v5 §14 Decision 13 & v6 §15 Item 13)
        if (!outcome.isAccepted && outcome.needsFullSnapshot && allowSnapshotRetry && activeRefs != null) {
            activeRefs.reset()
            val retryRaw: String = try {
                val r = guest.invokeEntry<String>(
                    entryName = "__render",
                    budgetMs = limits.sourceUpdateBudgetMs,
                    phase = "render",
                    jsCallExpr = "globalThis.__render(1)",
                    logicalNowMs = logicalNowMs,
                )
                val sched = guest.invokeEntry<String>(
                    entryName = "__schedule",
                    budgetMs = 50L,
                    phase = "engine",
                    jsCallExpr = "globalThis.__schedule()",
                    logicalNowMs = logicalNowMs,
                )
                applyScheduleJson(sched)
                r
            } catch (err: SproutRuntimeException) {
                return handleFallbackGuestError(err)
            }
            val retryDiags = guest.pendingDiagnostics.toList()
            guest.clearCallScratch()
            return validateGuestFrameAndCommit(
                guestFrameRawJson = retryRaw,
                compileMs = compileMs,
                execMs = execMs,
                effects = effects,
                extraHostDiagnostics = extraHostDiagnostics + retryDiags,
                isStreamingAppend = isStreamingAppend,
                logicalNowMs = logicalNowMs,
                allowSnapshotRetry = false,
                isStrictFinish = isStrictFinish,
                droppedOperations = droppedOperations,
                callLedger = callLedger,
            )
        }

        if (outcome.isAccepted && activeRefs?.unsafeCssAnimationSeen == true) {
            wholeTreesOnly = true
            activeRefs.reset()
            val fullRaw: String = try {
                guest.invokeEntry<Any?>(
                    entryName = "__delta",
                    budgetMs = 50L,
                    phase = "engine",
                    jsCallExpr = "globalThis.__delta(0)",
                    logicalNowMs = logicalNowMs,
                )
                val r = guest.invokeEntry<String>(
                    entryName = "__render",
                    budgetMs = limits.sourceUpdateBudgetMs,
                    phase = "render",
                    jsCallExpr = "globalThis.__render(0)",
                    logicalNowMs = logicalNowMs,
                )
                val sched = guest.invokeEntry<String>(
                    entryName = "__schedule",
                    budgetMs = 50L,
                    phase = "engine",
                    jsCallExpr = "globalThis.__schedule()",
                    logicalNowMs = logicalNowMs,
                )
                applyScheduleJson(sched)
                r
            } catch (err: SproutRuntimeException) {
                return handleFallbackGuestError(err)
            }
            val fullDiags = guest.pendingDiagnostics.toList()
            guest.clearCallScratch()
            return validateGuestFrameAndCommit(
                guestFrameRawJson = fullRaw,
                compileMs = compileMs,
                execMs = execMs,
                effects = effects,
                extraHostDiagnostics = extraHostDiagnostics + fullDiags,
                isStreamingAppend = isStreamingAppend,
                logicalNowMs = logicalNowMs,
                allowSnapshotRetry = false,
                isStrictFinish = isStrictFinish,
                droppedOperations = droppedOperations,
                callLedger = callLedger,
            )
        }

        val validatedFrame = outcome.frame
        if (!outcome.isAccepted || validatedFrame == null) {
            rollbackSuppressedCallEffects(callLedger, logicalNowMs)
            // Spec v6 §15 Item 13: rejected guest render already overwrote guest handler table -> keep visual tree read-only!
            if (isStrictFinish) {
                markFinalInvalidReadOnly()
            } else {
                inertReason = InertReason.FRAME_REJECTED
                elementBindingsIndex.clear()
            }
            return FrameCommit(
                frame = lastValidFrame,
                diagnostics = extraHostDiagnostics + outcome.diagnostics,
                stats = outcome.stats,
                sharedSubtreeNodes = 0,
                effects = emptyList(),
                suggestedInitialState = lastSuggestedInitialState,
                nextTimerDueMs = nextTimerDueMs,
                hasAnimationFrame = hasAnimationFrame,
                interactionEnabled = false,
                inertReason = inertReason,
                status = status,
                changed = false,
                pendingStateOperations = pendingBatches.size,
                droppedOperations = droppedOperations,
                guestRuntimeVersion = engine.guestRuntimeVersion,
                guestAbiVersion = engine.guestAbiVersion,
                compileMs = compileMs,
                executeMs = execMs,
                validateMs = valMs,
            )
        }

        // Enforce monotonic frameRevision within current runtimeEpoch (v5 §14 Decision 7)
        if (validatedFrame.runtimeEpoch != runtimeEpoch ||
            (lastValidFrame.runtimeEpoch == runtimeEpoch && validatedFrame.frameRevision <= lastValidFrame.frameRevision)
        ) {
            rollbackSuppressedCallEffects(callLedger, logicalNowMs)
            inertReason = if (isStrictFinish) InertReason.FINAL_INVALID else InertReason.FRAME_REJECTED
            if (isStrictFinish) status = SurfaceStatus.FINAL_INVALID
            elementBindingsIndex.clear()
            return diagnosticOnLastValid(
                ProtocolDiagnostic(
                    code = "INVALID_FRAME",
                    phase = "validate",
                    message = "Non-monotonic frameRevision ${validatedFrame.frameRevision} under runtimeEpoch $runtimeEpoch",
                    origin = DiagnosticOrigin.HOST,
                ),
                extraDiags = extraHostDiagnostics,
                dropped = droppedOperations,
            )
        }

        // Spec v8 §17 Item 7: Only an INCOMPLETE (non-strict) streaming prefix producing `[]` keeps the previous non-empty preview frame (`EMPTY_TREE`);
        // any complete strict program returning `null` / `[]` commits `root = []` immediately, even during preview!
        if (!sourceFinished && validatedFrame.root.isEmpty() && lastValidFrame.root.isNotEmpty()) {
            val isCompleteStrictProgram = runCatching { compiler.compileStrict(sourceBuffer.toString()) }.isSuccess
            if (!isCompleteStrictProgram) {
                val emptyDiag = ProtocolDiagnostic(
                    code = "EMPTY_TREE",
                    phase = "render",
                    message = "Incomplete streaming prefix rendered empty tree; keeping previous preview frame read-only",
                    origin = DiagnosticOrigin.HOST,
                )
                previewStale = true
                if (hasAnyEventBindings(lastValidFrame.root)) {
                    if (inertReason == InertReason.NONE) {
                        inertReason = InertReason.STALE_PREVIEW
                    }
                    elementBindingsIndex.clear()
                }
                return FrameCommit(
                    frame = lastValidFrame,
                    diagnostics = extraHostDiagnostics + outcome.diagnostics + emptyDiag,
                    stats = outcome.stats,
                    sharedSubtreeNodes = 0,
                    effects = emptyList(),
                    suggestedInitialState = lastSuggestedInitialState,
                    nextTimerDueMs = nextTimerDueMs,
                    hasAnimationFrame = hasAnimationFrame,
                    interactionEnabled = interactionEnabled,
                    inertReason = inertReason,
                    status = status,
                    stalePreview = status == SurfaceStatus.PREVIEW,
                    changed = false,
                    pendingStateOperations = pendingBatches.size,
                    droppedOperations = droppedOperations,
                    guestRuntimeVersion = engine.guestRuntimeVersion,
                    guestAbiVersion = engine.guestAbiVersion,
                    compileMs = compileMs,
                    executeMs = execMs,
                    validateMs = valMs,
                )
            }
        }

        val shared = TreeSharer.shareTree(
            prev = if (lastValidFrame.frameRevision > 0) lastValidFrame.root else null,
            next = validatedFrame.root,
            refs = if (wholeTreesOnly) null else refs,
        )
        val committedFrame = validatedFrame.copy(root = shared.nodes)
        lastValidFrame = committedFrame
        previewStale = false
        inertReason = InertReason.NONE
        rebuildBindingsIndex(committedFrame.root)

        return FrameCommit(
            frame = committedFrame,
            diagnostics = extraHostDiagnostics + outcome.diagnostics,
            stats = outcome.stats,
            sharedSubtreeNodes = shared.sharedNodes,
            effects = effects,
            suggestedInitialState = lastSuggestedInitialState,
            nextTimerDueMs = nextTimerDueMs,
            hasAnimationFrame = hasAnimationFrame,
            interactionEnabled = true,
            inertReason = InertReason.NONE,
            status = status,
            stalePreview = false,
            uncertain = uncertainOperation(),
            changed = true,
            pendingStateOperations = pendingBatches.size,
            droppedOperations = droppedOperations,
            guestRuntimeVersion = engine.guestRuntimeVersion,
            guestAbiVersion = engine.guestAbiVersion,
            compileMs = compileMs,
            executeMs = execMs,
            validateMs = valMs,
        )
    }

    private fun diffSharedStateOps(before: JsonObject, after: JsonObject): List<SharedStateOp> {
        val ops = ArrayList<SharedStateOp>()
        for ((k, v) in after) {
            if (k !in before || before[k] != v) {
                ops.add(SharedStateOp.Set(k, v))
            }
        }
        for (k in before.keys) {
            if (k !in after) {
                ops.add(SharedStateOp.Delete(k))
            }
        }
        return ops
    }

    private fun findElementById(nodes: List<TreeNode>, id: String): TreeNode.Element? {
        for (n in nodes) {
            if (n is TreeNode.Element) {
                if (n.id == id) return n
                findElementById(n.children, id)?.let { return it }
            }
        }
        return null
    }

    private fun hasAnyEventBindings(nodes: List<TreeNode>): Boolean {
        for (n in nodes) {
            if (n is TreeNode.Element) {
                if (n.events.isNotEmpty() || hasAnyEventBindings(n.children)) return true
            }
        }
        return false
    }

    private fun rebuildBindingsIndex(nodes: List<TreeNode>) {
        elementBindingsIndex.clear()
        fun walk(list: List<TreeNode>) {
            for (n in list) {
                if (n is TreeNode.Element) {
                    if (n.events.isNotEmpty()) {
                        elementBindingsIndex[n.id] = n.events
                    }
                    walk(n.children)
                }
            }
        }
        walk(nodes)
    }

    private fun unchangedCommit(dropped: List<PendingStateOperation> = emptyList()): FrameCommit {
        val diags = drainStartupDiagnostics()
        return FrameCommit(
            frame = lastValidFrame,
            diagnostics = diags,
            stats = ValidationStats(hostDiagnostics = diags.size),
            sharedSubtreeNodes = 0,
            effects = emptyList(),
            suggestedInitialState = lastSuggestedInitialState,
            nextTimerDueMs = nextTimerDueMs,
            hasAnimationFrame = hasAnimationFrame,
            interactionEnabled = interactionEnabled,
            inertReason = inertReason,
            status = status,
            stalePreview = previewStale && status == SurfaceStatus.PREVIEW,
            uncertain = uncertainOperation(),
            changed = false,
            pendingStateOperations = pendingBatches.size,
            droppedOperations = dropped,
            guestRuntimeVersion = engine.guestRuntimeVersion,
            guestAbiVersion = engine.guestAbiVersion,
            compileMs = 0.0,
            executeMs = 0.0,
            validateMs = 0.0,
        )
    }

    private fun diagnosticOnLastValid(
        diag: ProtocolDiagnostic,
        extraDiags: List<ProtocolDiagnostic> = emptyList(),
        dropped: List<PendingStateOperation> = emptyList(),
    ): FrameCommit {
        val all = drainStartupDiagnostics() + extraDiags + diag
        return FrameCommit(
            frame = lastValidFrame,
            diagnostics = all,
            stats = ValidationStats(hostDiagnostics = all.size),
            sharedSubtreeNodes = 0,
            effects = emptyList(),
            suggestedInitialState = lastSuggestedInitialState,
            nextTimerDueMs = nextTimerDueMs,
            hasAnimationFrame = hasAnimationFrame,
            interactionEnabled = interactionEnabled,
            inertReason = inertReason,
            status = status,
            stalePreview = previewStale && status == SurfaceStatus.PREVIEW,
            uncertain = uncertainOperation(),
            changed = false,
            pendingStateOperations = pendingBatches.size,
            droppedOperations = dropped,
            guestRuntimeVersion = engine.guestRuntimeVersion,
            guestAbiVersion = engine.guestAbiVersion,
            compileMs = 0.0,
            executeMs = 0.0,
            validateMs = 0.0,
        )
    }

    private fun sha256Hex(text: String): String {
        return SproutSha256.sha256Hex(text.encodeToByteArray())
    }

    private fun checkNotDisposed() {
        if (disposed) throw SproutRuntimeException("ENGINE_FAILED", "engine", "Surface '$surfaceId' has been disposed")
    }
}

private fun canonicalizeJsonElement(el: JsonElement): JsonElement = when (el) {
    is JsonObject -> canonicalizeJsonObject(el)
    is JsonArray -> JsonArray(el.map { canonicalizeJsonElement(it) })
    else -> el
}

private fun canonicalizeJsonObject(obj: JsonObject): JsonObject {
    if (obj.isEmpty()) return obj
    val sorted = LinkedHashMap<String, JsonElement>(obj.size)
    for (k in obj.keys.sorted()) {
        sorted[k] = canonicalizeJsonElement(obj.getValue(k))
    }
    return JsonObject(sorted)
}

internal fun containsTopLevelAwait(src: String): Boolean {
    var i = 0
    val n = src.length
    var fnBraceDepth = 0
    var pendingFunctionBrace = false
    var prevWord = ""
    while (i < n) {
        val ch = src[i]
        if (ch == '/' && i + 1 < n && src[i + 1] == '/') {
            i += 2
            while (i < n && src[i] != '\n') i++
            continue
        }
        if (ch == '/' && i + 1 < n && src[i + 1] == '*') {
            i += 2
            while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) i++
            i += 2
            continue
        }
        if (ch == '\'' || ch == '"' || ch == '`') {
            val q = ch
            i++
            while (i < n) {
                if (src[i] == '\\') {
                    i += 2
                    continue
                }
                if (src[i] == q) {
                    i++
                    break
                }
                i++
            }
            continue
        }
        if (ch == '=' && i + 1 < n && src[i + 1] == '>') {
            pendingFunctionBrace = true
            i += 2
            continue
        }
        if (ch == '{') {
            if (pendingFunctionBrace || prevWord == ")" || prevWord == "function") {
                fnBraceDepth++
                pendingFunctionBrace = false
            } else if (fnBraceDepth > 0) {
                fnBraceDepth++
            }
            i++
            continue
        }
        if (ch == '}') {
            if (fnBraceDepth > 0) fnBraceDepth--
            i++
            continue
        }
        if (ch == ')') {
            prevWord = ")"
            i++
            continue
        }
        if (ch.isLetter() || ch == '_' || ch == '$') {
            val start = i
            while (i < n && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '$')) i++
            val word = src.substring(start, i)
            if (word == "function") pendingFunctionBrace = true
            if (word == "await" && fnBraceDepth == 0 && prevWord != ".") return true
            prevWord = word
            continue
        }
        if (!ch.isWhitespace()) {
            if (ch == ';') pendingFunctionBrace = false
            prevWord = ch.toString()
        }
        i++
    }
    return false
}
