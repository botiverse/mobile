package build.raft.sprout.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

enum class ValidationProfile {
    /**
     * Cross-platform profile (`portable/1`):
     * - `c`: catalog components
     * - `p`: `PORTABLE_PRIMITIVES_V1` validated strictly by `portable-primitives/1`
     * - `h` / unknown HTML tags: refused as `UNSUPPORTED_CAPABILITY` (`$notice`)
     */
    PORTABLE_V1,

    /**
     * Web open profile (`web-open/1`):
     * - `c`: catalog components
     * - `h`: safe lower-case HTML/SVG elements validated by `Catalog.open` + explicit event allowlist + resource resolver
     */
    WEB_OPEN_V1,

    /**
     * Legacy Step 2 compatibility mode.
     */
    STEP2_VECTOR_COMPAT,
}

data class ValidationStats(
    val nodes: Int = 0,
    val items: Int = 0,
    val droppedProps: Int = 0,
    val droppedEvents: Int = 0,
    val unknownComponents: Int = 0,
    val diagnosticsOmitted: Int = 0,
    val guestDiagnostics: Int = 0,
    val hostDiagnostics: Int = 0,
)

enum class PortableSpecMode {
    SCHEMA_COMPAT,
    V6_STRICT,
}

data class HostEnvelopeOverride(
    val surfaceId: String,
    val surfaceGeneration: Long? = null,
    val sourceRevision: Long,
    val stateRevision: Long,
    val catalogId: String? = null,
    val catalogVersion: String? = null,
    val profile: String? = null,
)

const val DEFAULT_PORTABLE_PRIMITIVES_VERSION = "portable-primitives/1"
val SUPPORTED_KOTLIN_PRIMITIVES_VERSIONS: Set<String> = setOf(DEFAULT_PORTABLE_PRIMITIVES_VERSION)

data class ValidateFrameOptions(
    val profile: ValidationProfile = ValidationProfile.PORTABLE_V1,
    val portableSpecMode: PortableSpecMode = PortableSpecMode.V6_STRICT,
    val strictNullItems: Boolean = true,
    val idPrefix: String = "t-",
    val refs: TreeRefs? = null,
    val hostEnvelope: HostEnvelopeOverride? = null,
    val resolveResource: ResourceResolver? = null,
    val resourcePolicy: ResourcePolicy? = null,
    val publicClasses: Map<String, String>? = null,
    val scopeOpenClassNames: Boolean = true,
    val unresolvedGradientPaint: String? = "none",
    val maxFrameBytes: Int = ProtocolLimits.MAX_FRAME_BYTES,
    val maxNodes: Int = ProtocolLimits.MAX_NODES,
    val maxItems: Int = ProtocolLimits.MAX_ITEMS,
    val maxDepth: Int = ProtocolLimits.MAX_DEPTH,
    val primitivesVersion: String = DEFAULT_PORTABLE_PRIMITIVES_VERSION,
)

data class ValidatedPart(
    val nodes: List<TreeNode>,
    val rawId: String,
    val inSvg: Boolean,
    val depth: Int,
    val stats: ValidationStats,
    /** Cached expanded node IDs for O(k) whole-tree uniqueness checks without re-walking kept subtrees (v5 §14 Decision 4). */
    val nodeIds: Set<String>,
    val itemCount: Int,
)

/**
 * Atomic reference table for negotiated subtree reference mode (v5 §14 Decision 13):
 * - Scoped to `(surfaceId, runtimeEpoch, catalogId, catalogVersion, profile)`.
 * - Failed frames NEVER mutate `epoch`, `lastCommittedFrameRevision`, or `parts`.
 */
class TreeRefs(
    val surfaceScopeKey: String = "default",
) {
    var epoch: Long = -1L
        private set

    var lastCommittedFrameRevision: Long = 0L
        private set

    var unsafeCssAnimationSeen: Boolean = false
        internal set

    private val committedParts = LinkedHashMap<Long, ValidatedPart>()
    private val definedByPrimaryNode = HashMap<TreeNode, ValidatedPart>()

    fun getCommittedPart(partId: Long): ValidatedPart? = committedParts[partId]

    internal fun snapshotPartsForEpoch(targetEpoch: Long): LinkedHashMap<Long, ValidatedPart> =
        if (epoch == targetEpoch) LinkedHashMap(committedParts) else LinkedHashMap()

    internal fun commitTransaction(
        newEpoch: Long,
        frameRevision: Long,
        nextParts: Map<Long, ValidatedPart>,
        newlyDefined: List<ValidatedPart>,
    ) {
        epoch = newEpoch
        lastCommittedFrameRevision = frameRevision
        committedParts.clear()
        committedParts.putAll(nextParts)
        definedByPrimaryNode.clear()
        for (part in newlyDefined) {
            if (part.nodes.size == 1) {
                definedByPrimaryNode[part.nodes[0]] = part
            }
        }
    }

    fun reset() {
        epoch = -1L
        lastCommittedFrameRevision = 0L
        unsafeCssAnimationSeen = false
        committedParts.clear()
        definedByPrimaryNode.clear()
    }

    internal fun repoint(fresh: TreeNode, kept: TreeNode) {
        val part = definedByPrimaryNode[fresh] ?: return
        val updated = part.copy(nodes = listOf(kept))
        for ((k, v) in committedParts) {
            if (v === part) {
                committedParts[k] = updated
                break
            }
        }
    }
}

data class FrameValidationOutcome(
    val frame: SurfaceFrame?,
    val tree: List<TreeNode>,
    val diagnostics: List<ProtocolDiagnostic>,
    val stats: ValidationStats,
    /** Non-null when the frame as a whole was refused (`INVALID_FRAME`, `MISSING_REFERENCE`, `FRAME_BASE_MISMATCH`, etc.). */
    val errorCode: String? = null,
    /** True when the session should request a bounded 1-shot full snapshot from the guest (v5 §14 Decision 13). */
    val needsFullSnapshot: Boolean = false,
) {
    val isAccepted: Boolean get() = errorCode == null && frame != null
}

object SproutFrameValidator {
    private val STRICT_JSON = Json { ignoreUnknownKeys = false }

    /**
     * Parses raw UTF-8 JSON bytes/string with strict UTF-8 byte quota check before validation (v5 §14 Decision 15).
     */
    fun validateJsonString(
        rawJson: String,
        catalog: Catalog,
        options: ValidateFrameOptions = ValidateFrameOptions(),
    ): FrameValidationOutcome {
        val utf8Bytes = rawJson.encodeToByteArray().size
        if (utf8Bytes > options.maxFrameBytes) {
            val diag = ProtocolDiagnostic(
                code = "LIMIT_EXCEEDED",
                phase = "validate",
                message = "Frame UTF-8 payload ($utf8Bytes bytes) exceeds maxFrameBytes (${options.maxFrameBytes})",
                origin = DiagnosticOrigin.HOST,
            )
            return FrameValidationOutcome(
                frame = null,
                tree = emptyList(),
                diagnostics = listOf(diag),
                stats = ValidationStats(hostDiagnostics = 1),
                errorCode = "LIMIT_EXCEEDED",
            )
        }
        val parsed = try {
            STRICT_JSON.parseToJsonElement(rawJson)
        } catch (err: Exception) {
            val diag = ProtocolDiagnostic(
                code = "INVALID_FRAME",
                phase = "validate",
                message = "Malformed JSON frame: ${err.message?.takeUnicodeCodePoints(200) ?: "parse error"}",
                origin = DiagnosticOrigin.HOST,
            )
            return FrameValidationOutcome(
                frame = null,
                tree = emptyList(),
                diagnostics = listOf(diag),
                stats = ValidationStats(hostDiagnostics = 1),
                errorCode = "INVALID_FRAME",
            )
        }
        return validateFrame(parsed, catalog, options)
    }

    fun validateFrame(
        raw: JsonElement,
        catalog: Catalog,
        options: ValidateFrameOptions = ValidateFrameOptions(),
    ): FrameValidationOutcome {
        val isStep2Compat = options.profile == ValidationProfile.STEP2_VECTOR_COMPAT

        if (options.profile != ValidationProfile.WEB_OPEN_V1 && options.primitivesVersion !in SUPPORTED_KOTLIN_PRIMITIVES_VERSIONS) {
            return rejectEnvelope(
                "UNSUPPORTED_CAPABILITY",
                "Unsupported portable primitives version '${options.primitivesVersion.takeUnicodeCodePoints(ProtocolLimits.MAX_NAME_CHARS)}' (Kotlin supports portable-primitives/1)",
            )
        }

        // 1. Envelope validation
        val obj = raw as? JsonObject ?: return rejectEnvelope("INVALID_FRAME", "Frame must be a JSON object")
        if (obj.keys.any { it in RESERVED_PROTOTYPE_KEYS }) {
            return rejectEnvelope("INVALID_FRAME", "Frame contains reserved prototype key")
        }

        val protoEl = obj["protocol"] as? JsonPrimitive
        if (protoEl == null || !protoEl.isString) {
            return rejectEnvelope("INVALID_FRAME", "Missing string 'protocol'")
        }
        if (protoEl.content != GENUI_TREE_PROTOCOL_V1) {
            return rejectEnvelope("UNSUPPORTED_PROTOCOL", "Unsupported protocol '${protoEl.content}'")
        }

        val runtimeEpoch = exactSafeNonNegativeLongOrNull(obj["runtimeEpoch"])
            ?: return rejectEnvelope("INVALID_FRAME", "Missing or non-integer 'runtimeEpoch'")
        val frameRevision = exactSafeNonNegativeLongOrNull(obj["frameRevision"])
            ?.takeIf { it >= 1L }
            ?: return rejectEnvelope("INVALID_FRAME", "Missing or invalid 'frameRevision' (must be integer >= 1)")

        val rootArray = obj["root"] as? JsonArray
            ?: return rejectEnvelope("INVALID_FRAME", "Missing array 'root'")

        // Optional envelope fields on wire: validate types strictly, NEVER override host's `options.hostEnvelope`, `catalog`, or `options.profile`!
        val wireSurfaceId = if ("surfaceId" in obj) {
            val s = (obj["surfaceId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (s.isNullOrEmpty()) return rejectEnvelope("INVALID_FRAME", "Invalid 'surfaceId'")
            s
        } else {
            null
        }
        // `semantic.json` `envelopes` case 6: `surfaceGeneration` is an exact integer in `0..MAX_SAFE_INTEGER` (absent != 0)
        val wireSurfaceGeneration = if ("surfaceGeneration" in obj) {
            exactSafeNonNegativeLongOrNull(obj["surfaceGeneration"])
                ?: return rejectEnvelope("INVALID_FRAME", "Invalid 'surfaceGeneration'")
        } else {
            null
        }
        val wireSourceRevision = if ("sourceRevision" in obj) {
            exactSafeNonNegativeLongOrNull(obj["sourceRevision"])
                ?: return rejectEnvelope("INVALID_FRAME", "Invalid 'sourceRevision'")
        } else {
            null
        }
        val wireStateRevision = if ("stateRevision" in obj) {
            exactSafeNonNegativeLongOrNull(obj["stateRevision"])
                ?: return rejectEnvelope("INVALID_FRAME", "Invalid 'stateRevision'")
        } else {
            null
        }
        if ("catalogId" in obj) {
            (obj["catalogId"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }
                ?: return rejectEnvelope("INVALID_FRAME", "Invalid 'catalogId'")
        }
        if ("catalogVersion" in obj) {
            (obj["catalogVersion"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }
                ?: return rejectEnvelope("INVALID_FRAME", "Invalid 'catalogVersion'")
        }
        if ("profile" in obj) {
            (obj["profile"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }
                ?: return rejectEnvelope("INVALID_FRAME", "Invalid 'profile'")
        }

        // Validate host override identity & counter bounds BEFORE staging or committing `options.refs`!
        val hostEnv = options.hostEnvelope
        if (hostEnv != null) {
            if (hostEnv.surfaceId.isEmpty()) {
                return rejectEnvelope("INVALID_FRAME", "Invalid hostEnvelope 'surfaceId'")
            }
            if (hostEnv.surfaceGeneration != null && hostEnv.surfaceGeneration !in 0L..MAX_JS_SAFE_INTEGER) {
                return rejectEnvelope("INVALID_FRAME", "Invalid hostEnvelope 'surfaceGeneration'")
            }
            if (hostEnv.sourceRevision !in 0L..MAX_JS_SAFE_INTEGER) {
                return rejectEnvelope("INVALID_FRAME", "Invalid hostEnvelope 'sourceRevision'")
            }
            if (hostEnv.stateRevision !in 0L..MAX_JS_SAFE_INTEGER) {
                return rejectEnvelope("INVALID_FRAME", "Invalid hostEnvelope 'stateRevision'")
            }
            if (hostEnv.catalogId != null && hostEnv.catalogId.isEmpty()) {
                return rejectEnvelope("INVALID_FRAME", "Invalid hostEnvelope 'catalogId'")
            }
            if (hostEnv.catalogVersion != null && hostEnv.catalogVersion.isEmpty()) {
                return rejectEnvelope("INVALID_FRAME", "Invalid hostEnvelope 'catalogVersion'")
            }
            if (hostEnv.profile != null && hostEnv.profile.isEmpty()) {
                return rejectEnvelope("INVALID_FRAME", "Invalid hostEnvelope 'profile'")
            }
        }

        // Optional references envelope
        val referencesEnv: ReferencesEnvelope? = if ("references" in obj) {
            val refObj = obj["references"] as? JsonObject
                ?: return rejectEnvelope("INVALID_FRAME", "'references' must be an object")
            if (refObj.keys.any { it in RESERVED_PROTOTYPE_KEYS }) {
                return rejectEnvelope("INVALID_FRAME", "Reserved key in 'references'")
            }
            val refEpoch = exactSafeNonNegativeLongOrNull(refObj["epoch"])
                ?: return rejectEnvelope("INVALID_FRAME", "Invalid 'references.epoch'")
            val baseRev = if ("baseFrameRevision" in refObj) {
                val parsedBase = exactSafeNonNegativeLongOrNull(refObj["baseFrameRevision"])
                    ?: return rejectEnvelope("INVALID_FRAME", "Invalid 'references.baseFrameRevision'")
                if (!isStep2Compat && parsedBase >= frameRevision) {
                    return rejectEnvelope("INVALID_FRAME", "references.baseFrameRevision must be an earlier frame")
                }
                parsedBase
            } else {
                if (!isStep2Compat) return rejectEnvelope("INVALID_FRAME", "Missing 'references.baseFrameRevision'")
                null
            }
            ReferencesEnvelope(epoch = refEpoch, baseFrameRevision = baseRev)
        } else {
            null
        }

        // Verify reference mode preconditions atomically BEFORE mutating `options.refs`!
        val refs = options.refs
        val stagedParts: LinkedHashMap<Long, ValidatedPart>? = if (referencesEnv != null) {
            if (refs == null) {
                if (!isStep2Compat && (referencesEnv.baseFrameRevision ?: 0L) > 0L) {
                    return FrameValidationOutcome(
                        frame = null,
                        tree = emptyList(),
                        diagnostics = listOf(
                            ProtocolDiagnostic(
                                code = "FRAME_BASE_MISMATCH",
                                phase = "validate",
                                message = "Reference frame requires active TreeRefs table",
                                origin = DiagnosticOrigin.HOST,
                            ),
                        ),
                        stats = ValidationStats(hostDiagnostics = 1),
                        errorCode = "FRAME_BASE_MISMATCH",
                        needsFullSnapshot = true,
                    )
                }
                null
            } else {
                if (!isStep2Compat && referencesEnv.baseFrameRevision != null) {
                    val baseRev = referencesEnv.baseFrameRevision
                    if (baseRev > 0L && (refs.epoch != referencesEnv.epoch || refs.lastCommittedFrameRevision != baseRev)) {
                        return FrameValidationOutcome(
                            frame = null,
                            tree = emptyList(),
                            diagnostics = listOf(
                                ProtocolDiagnostic(
                                    code = "FRAME_BASE_MISMATCH",
                                    phase = "validate",
                                    message = "Expected baseFrameRevision ${refs.lastCommittedFrameRevision} (epoch ${refs.epoch}), got $baseRev (epoch ${referencesEnv.epoch})",
                                    origin = DiagnosticOrigin.HOST,
                                ),
                            ),
                            stats = ValidationStats(hostDiagnostics = 1),
                            errorCode = "FRAME_BASE_MISMATCH",
                            needsFullSnapshot = true,
                        )
                    }
                    if (baseRev == 0L) {
                        LinkedHashMap()
                    } else {
                        refs.snapshotPartsForEpoch(referencesEnv.epoch)
                    }
                } else {
                    refs.snapshotPartsForEpoch(referencesEnv.epoch)
                }
            }
        } else {
            null
        }
        val committedLookupParts: Map<Long, ValidatedPart>? = if (referencesEnv?.baseFrameRevision == 0L) {
            emptyMap()
        } else {
            stagedParts?.toMap()
        }
        val newlyDefinedParts = ArrayList<ValidatedPart>()

        // 2. Guest diagnostics (untrusted, max 8, origin = GUEST; v5 §14 Decision 10 & 14)
        val diagnostics = ArrayList<ProtocolDiagnostic>()
        var diagnosticsOmitted = 0
        var guestDiagCount = 0
        var hostDiagCount = 0

        if ("diagnostics" in obj) {
            val guestDiagArr = obj["diagnostics"] as? JsonArray
                ?: return rejectEnvelope("INVALID_FRAME", "'diagnostics' must be an array")
            var acceptedGuest = 0
            for (dEl in guestDiagArr) {
                val dObj = dEl as? JsonObject ?: continue
                if (dObj.keys.any { it in RESERVED_PROTOTYPE_KEYS }) continue
                val code = (dObj["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                val phase = (dObj["phase"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                val msg = (dObj["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                if (phase !in KNOWN_DIAGNOSTIC_PHASES) continue
                if (isStep2Compat && code !in KNOWN_DIAGNOSTIC_CODES) continue
                val line = if ("line" in dObj) exactSafeNonNegativeLongOrNull(dObj["line"])?.takeIf { it >= 1L } else null
                val nodeId = (dObj["nodeId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeUnicodeCodePoints(ProtocolLimits.MAX_NODE_ID_CHARS)
                if (acceptedGuest < ProtocolLimits.MAX_GUEST_DIAGNOSTICS && diagnostics.size < ProtocolLimits.MAX_DIAGNOSTICS) {
                    diagnostics.add(
                        ProtocolDiagnostic(
                            code = code.takeUnicodeCodePoints(ProtocolLimits.MAX_NAME_CHARS),
                            phase = phase,
                            message = msg.takeUnicodeCodePoints(ProtocolLimits.MAX_DIAGNOSTIC_MESSAGE_CHARS),
                            line = line,
                            nodeId = nodeId,
                            origin = DiagnosticOrigin.GUEST,
                        ),
                    )
                    acceptedGuest += 1
                    guestDiagCount += 1
                } else {
                    diagnosticsOmitted += 1
                }
            }
        }

        fun addHostDiagnostic(
            code: String,
            message: String,
            nodeId: String? = null,
            priority: Boolean = false,
            field: String? = null,
        ) {
            hostDiagCount += 1
            val diag = ProtocolDiagnostic(
                code = code,
                phase = "validate",
                message = message.takeUnicodeCodePoints(ProtocolLimits.MAX_DIAGNOSTIC_MESSAGE_CHARS),
                nodeId = nodeId,
                field = field,
                origin = DiagnosticOrigin.HOST,
            )
            if (diagnostics.size < ProtocolLimits.MAX_DIAGNOSTICS) {
                diagnostics.add(diag)
            } else if (priority && diagnostics.isNotEmpty()) {
                diagnostics[diagnostics.lastIndex] = diag
                diagnosticsOmitted += 1
            } else {
                diagnosticsOmitted += 1
            }
        }

        var nodeCount = 0
        var itemCount = 0
        var droppedPropsCount = 0
        var droppedEventsCount = 0
        var unknownComponentsCount = 0
        var overNotice: TreeNode.Element? = null
        val wholeTreeIds = HashSet<String>()

        val idPrefix = options.idPrefix
        val openCtx = PropAndCssValidator.OpenValidationContext(
            idPrefix = idPrefix,
            resolveResource = options.resolveResource,
            resourcePolicy = options.resourcePolicy,
            publicClasses = options.publicClasses,
            scopeClassNames = options.scopeOpenClassNames,
        )
        val openStyleHolders = ArrayList<MutableMap<String, JsonElement>>()
        val scopedKeyframes = LinkedHashSet<String>()

        class FrameAbortException(
            val code: String,
            override val message: String,
            val needsFullSnapshot: Boolean = false,
            val nodeId: String? = null,
            val field: String? = null,
        ) : RuntimeException(message)

        fun makeNotice(id: String, code: String, fallbackText: String, name: String? = null, limit: Int? = null): TreeNode.Element {
            val props = buildJsonObject {
                put("code", code)
                if (name != null) put("name", name)
                if (limit != null) put("limit", limit)
            }
            return TreeNode.Element(
                id = id,
                kind = NodeKind.COMPONENT,
                type = NOTICE_TYPE,
                props = props,
                events = emptyMap(),
                children = listOf(TreeNode.Text(fallbackText)),
            )
        }

        fun extractSvgTitle(children: List<TreeNode>): String? {
            val titleNode = children.firstOrNull { it is TreeNode.Element && it.type == "title" } as? TreeNode.Element
                ?: return null
            val text = titleNode.children.filterIsInstance<TreeNode.Text>().joinToString("") { it.text }.trim()
            return if (text.isNotEmpty()) text.takeUnicodeCodePoints(ProtocolLimits.MAX_TITLE_CHARS) else null
        }

        fun validateEvents(
            rawNode: JsonObject,
            nodeId: String,
            allowedSet: Set<String>?,
            openPolicy: OpenPolicy?,
        ): Map<String, EventBinding>? {
            if ("e" !in rawNode) return emptyMap()
            val rawEventsEl = rawNode["e"]
            if (rawEventsEl is JsonNull || rawEventsEl !is JsonObject) {
                if (!isStep2Compat) return null
                return emptyMap()
            }
            val clean = LinkedHashMap<String, EventBinding>()
            for ((eventName, bindingEl) in rawEventsEl) {
                val allowedName = when {
                    eventName in RESERVED_PROTOTYPE_KEYS -> false
                    openPolicy != null -> PropAndCssValidator.isValidOpenEventName(eventName, openPolicy, isStep2Compat)
                    allowedSet != null -> eventName in allowedSet
                    else -> false
                }
                val bObj = bindingEl as? JsonObject
                val h = if (bObj != null && bObj.keys.none { it in RESERVED_PROTOTYPE_KEYS }) exactSignedInt32OrNull(bObj["h"]) else null
                val e = if (bObj != null) exactSafeNonNegativeLongOrNull(bObj["e"]) else null
                if (!allowedName || h == null || e == null) {
                    droppedEventsCount += 1
                    addHostDiagnostic("EVENT_DROPPED", "Dropped event '$eventName'", nodeId = nodeId)
                } else {
                    clean[eventName] = EventBinding(handler = h, epoch = e)
                }
            }
            return clean
        }

        fun collectElementIds(nodes: List<TreeNode>, into: MutableSet<String>) {
            for (n in nodes) {
                if (n is TreeNode.Element) {
                    into.add(n.id)
                    collectElementIds(n.children, into)
                }
            }
        }

        fun countItemsRecursive(nodes: List<TreeNode>): Int {
            var total = 0
            for (n in nodes) {
                total += 1
                if (n is TreeNode.Element) total += countItemsRecursive(n.children)
            }
            return total
        }

        fun walkList(
            listEl: JsonElement?,
            depth: Int,
            inSvg: Boolean,
            parentPrimitiveTag: String? = null,
            svgRootCtx: PortableV6Spec.SvgRootContext? = null,
        ): List<TreeNode> {
            val out = ArrayList<TreeNode>()
            if (listEl == null || overNotice != null) return out
            val list = listEl as? JsonArray ?: return out

            if (depth > options.maxDepth) {
                overNotice = makeNotice(
                    id = "notice",
                    code = "TREE_TOO_DEEP",
                    fallbackText = "The UI is nested too deeply (the limit is ${options.maxDepth}).",
                    limit = options.maxDepth,
                )
                addHostDiagnostic(
                    code = "TREE_TOO_DEEP",
                    message = "Tree depth exceeds limit (${options.maxDepth})",
                    priority = true,
                )
                return out
            }

            val siblingIds = HashSet<String>()
            var lastStopOffset: Double? = null

            for (idx in list.indices) {
                if (overNotice != null) break
                val item = list[idx]

                if (item is JsonPrimitive && item.isString) {
                    itemCount += 1
                    if (!isStep2Compat && itemCount > options.maxItems) {
                        overNotice = makeNotice(
                            id = "notice",
                            code = "TREE_TOO_LARGE",
                            fallbackText = "The UI has too many elements (the limit is ${options.maxItems}).",
                            limit = options.maxItems,
                        )
                        addHostDiagnostic("TREE_TOO_LARGE", "Item count exceeds limit (${options.maxItems})", priority = true)
                        break
                    }
                    // In `portable/1`, bare strings inside `<svg>`, `<g>`, `<linearGradient>`, etc. are invalid (only `<text>` holds strings in SVG)
                    if (options.profile == ValidationProfile.PORTABLE_V1 && inSvg && parentPrimitiveTag != null && parentPrimitiveTag != "text") {
                        addHostDiagnostic("UNSUPPORTED_CAPABILITY", "Bare text is only allowed inside <text> within <svg>")
                        continue
                    }
                    out.add(TreeNode.Text(item.content))
                    continue
                }

                if (item is JsonNull) {
                    if (!isStep2Compat && options.strictNullItems) {
                        addHostDiagnostic("INVALID_NODE", "Explicit null item is not valid on wire")
                    }
                    continue
                }

                if (item !is JsonObject) {
                    addHostDiagnostic("INVALID_NODE", "Item at index $idx is neither string, node, nor reference")
                    continue
                }

                if (item.keys.any { it in RESERVED_PROTOTYPE_KEYS }) {
                    addHostDiagnostic("INVALID_NODE", "Node contains reserved prototype key")
                    continue
                }

                // Reference item `{ "r": partId }`
                if ("r" in item && "t" !in item && "i" !in item) {
                    val partId = exactSafeNonNegativeLongOrNull(item["r"])?.takeIf { it >= 1L }
                        ?: throw FrameAbortException("INVALID_FRAME", "Malformed reference item")
                    if (referencesEnv == null || committedLookupParts == null) {
                        throw FrameAbortException("MISSING_REFERENCE", "Reference $partId in frame without reference table", needsFullSnapshot = true)
                    }
                    val part = committedLookupParts[partId]
                        ?: throw FrameAbortException("MISSING_REFERENCE", "Missing referenced part $partId", needsFullSnapshot = true)
                    if (part.inSvg != inSvg || part.depth != depth) {
                        throw FrameAbortException("MISSING_REFERENCE", "Part $partId context mismatch", needsFullSnapshot = true)
                    }

                    nodeCount += part.stats.nodes
                    itemCount += part.itemCount
                    droppedPropsCount += part.stats.droppedProps
                    droppedEventsCount += part.stats.droppedEvents
                    unknownComponentsCount += part.stats.unknownComponents

                    if (nodeCount > options.maxNodes || (!isStep2Compat && itemCount > options.maxItems)) {
                        val hitLimit = if (!isStep2Compat && itemCount > options.maxItems) options.maxItems else options.maxNodes
                        overNotice = makeNotice(
                            id = "notice",
                            code = "TREE_TOO_LARGE",
                            fallbackText = "The UI has too many elements (the limit is $hitLimit).",
                            limit = hitLimit,
                        )
                        addHostDiagnostic("TREE_TOO_LARGE", "Element count exceeds limit ($hitLimit)", priority = true)
                        break
                    }

                    if (!isStep2Compat) {
                        for (pid in part.nodeIds) {
                            if (!wholeTreeIds.add(pid)) {
                                throw FrameAbortException("INVALID_FRAME", "Duplicate node id '$pid' across referenced part $partId")
                            }
                        }
                        siblingIds.add(part.rawId)
                        out.addAll(part.nodes)
                    } else {
                        var partKey = part.rawId
                        if (!siblingIds.add(partKey)) {
                            partKey = "$partKey#$idx"
                            siblingIds.add(partKey)
                            addHostDiagnostic("DUPLICATE_KEY", "Duplicate sibling id '${part.rawId}'", nodeId = partKey)
                        }
                        for (kept in part.nodes) {
                            if (kept is TreeNode.Element && kept.id != partKey) {
                                out.add(kept.copy(id = partKey))
                            } else {
                                out.add(kept)
                            }
                        }
                    }
                    continue
                }

                // Regular Element node
                val definesPartId: Long? = if ("d" in item) {
                    if (referencesEnv == null) {
                        if (!isStep2Compat) {
                            throw FrameAbortException("INVALID_FRAME", "Node carries 'd' without references envelope")
                        }
                        null
                    } else {
                        exactSafeNonNegativeLongOrNull(item["d"])?.takeIf { it >= 1L }
                            ?: throw FrameAbortException("INVALID_FRAME", "Invalid 'd' part id")
                    }
                } else {
                    null
                }

                val beforeSnapshot = if (definesPartId != null && stagedParts != null) {
                    intArrayOf(nodeCount, itemCount, droppedPropsCount, droppedEventsCount, unknownComponentsCount, out.size)
                } else {
                    null
                }

                nodeCount += 1
                itemCount += 1

                if (!isStep2Compat && definesPartId != null && stagedParts != null && definesPartId in stagedParts) {
                    throw FrameAbortException(
                        "INVALID_FRAME",
                        "Part id $definesPartId cannot be overwritten within epoch ${referencesEnv?.epoch}",
                    )
                }

                if (nodeCount > options.maxNodes || (!isStep2Compat && itemCount > options.maxItems)) {
                    val hitLimit = if (!isStep2Compat && itemCount > options.maxItems) options.maxItems else options.maxNodes
                    overNotice = makeNotice(
                        id = "notice",
                        code = "TREE_TOO_LARGE",
                        fallbackText = "The UI has too many elements (the limit is $hitLimit).",
                        limit = hitLimit,
                    )
                    addHostDiagnostic("TREE_TOO_LARGE", "Element count exceeds limit ($hitLimit)", priority = true)
                    break
                }

                val rawIdStr = (item["i"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val rawId: String = if (isStep2Compat) {
                    if (!rawIdStr.isNullOrEmpty()) rawIdStr.takeUnicodeCodePoints(ProtocolLimits.MAX_NODE_ID_CHARS) else idx.toString()
                } else {
                    if (rawIdStr.isNullOrEmpty()) {
                        throw FrameAbortException("INVALID_FRAME", "Node missing non-empty string 'i'")
                    }
                    if (rawIdStr.unicodeCodePointCount() > ProtocolLimits.MAX_NODE_ID_CHARS) {
                        throw FrameAbortException(
                            "LIMIT_EXCEEDED",
                            "Node id exceeds ${ProtocolLimits.MAX_NODE_ID_CHARS} Unicode code points",
                        )
                    }
                    rawIdStr
                }

                val nodeId: String = if (isStep2Compat) {
                    var candidate = rawId
                    if (!siblingIds.add(candidate)) {
                        candidate = "$candidate#$idx"
                        siblingIds.add(candidate)
                        addHostDiagnostic("DUPLICATE_KEY", "Duplicate sibling id '$rawId'", nodeId = candidate)
                    }
                    candidate
                } else {
                    if (!wholeTreeIds.add(rawId)) {
                        throw FrameAbortException("INVALID_FRAME", "Duplicate node id '$rawId' in expanded frame")
                    }
                    siblingIds.add(rawId)
                    rawId
                }

                if (!isStep2Compat) {
                    val pField = item["p"]
                    val cField = item["c"]
                    if (pField is JsonNull || (pField != null && pField !is JsonObject) ||
                        cField is JsonNull || (cField !is JsonArray && cField != null)
                    ) {
                        unknownComponentsCount += 1
                        addHostDiagnostic("INVALID_NODE", "Explicit null or wrong-type p/c field on node", nodeId = nodeId)
                        if (!inSvg) {
                            out.add(makeNotice(id = nodeId, code = "INVALID_NODE", fallbackText = "Invalid node"))
                        }
                        continue
                    }
                }

                val rawType = (item["t"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (rawType.isNullOrEmpty() || rawType.startsWith("$")) {
                    unknownComponentsCount += 1
                    addHostDiagnostic("INVALID_NODE", "Missing or reserved node type", nodeId = nodeId)
                    if (!inSvg) {
                        out.add(makeNotice(id = nodeId, code = "INVALID_NODE", fallbackText = "Invalid node"))
                    }
                } else {
                    lastStopOffset = placeElement(
                        item = item,
                        name = rawType,
                        nodeId = nodeId,
                        depth = depth,
                        inSvg = inSvg,
                        parentPrimitiveTag = parentPrimitiveTag,
                        svgRootCtx = svgRootCtx,
                        previousStopOffset = lastStopOffset,
                        out = out,
                        isStep2Compat = isStep2Compat,
                        options = options,
                        catalog = catalog,
                        idPrefix = idPrefix,
                        openCtx = openCtx,
                        openStyleHolders = openStyleHolders,
                        scopedKeyframes = scopedKeyframes,
                        validateEvents = ::validateEvents,
                        walkList = ::walkList,
                        extractSvgTitle = ::extractSvgTitle,
                        makeNotice = ::makeNotice,
                        addHostDiagnostic = { code, msg, nId, prio -> addHostDiagnostic(code, msg, nId, prio) },
                        onUnknownComponent = { unknownComponentsCount += 1 },
                        onDroppedProps = { delta -> droppedPropsCount += delta },
                        abortFrame = { code, msg, nId, fld -> throw FrameAbortException(code, msg, nodeId = nId, field = fld) },
                    )
                }

                if (definesPartId != null && beforeSnapshot != null && stagedParts != null && overNotice == null) {
                    val createdNodes = out.subList(beforeSnapshot[5], out.size).toList()
                    val partIds = LinkedHashSet<String>()
                    collectElementIds(createdNodes, partIds)
                    val part = ValidatedPart(
                        nodes = createdNodes,
                        rawId = rawId,
                        inSvg = inSvg,
                        depth = depth,
                        stats = ValidationStats(
                            nodes = nodeCount - beforeSnapshot[0],
                            items = itemCount - beforeSnapshot[1],
                            droppedProps = droppedPropsCount - beforeSnapshot[2],
                            droppedEvents = droppedEventsCount - beforeSnapshot[3],
                            unknownComponents = unknownComponentsCount - beforeSnapshot[4],
                        ),
                        nodeIds = partIds,
                        itemCount = countItemsRecursive(createdNodes),
                    )
                    stagedParts[definesPartId] = part
                    newlyDefinedParts.add(part)
                }
            }

            return out
        }

        val validatedRoot = try {
            walkList(rootArray, depth = 1, inSvg = false)
        } catch (abort: FrameAbortException) {
            addHostDiagnostic(abort.code, abort.message, nodeId = abort.nodeId, priority = true, field = abort.field)
            return FrameValidationOutcome(
                frame = null,
                tree = emptyList(),
                diagnostics = diagnostics,
                stats = ValidationStats(
                    nodes = nodeCount,
                    items = itemCount,
                    droppedProps = droppedPropsCount,
                    droppedEvents = droppedEventsCount,
                    unknownComponents = unknownComponentsCount,
                    diagnosticsOmitted = diagnosticsOmitted,
                    guestDiagnostics = guestDiagCount,
                    hostDiagnostics = hostDiagCount,
                ),
                errorCode = abort.code,
                needsFullSnapshot = abort.needsFullSnapshot,
            )
        }

        if (scopedKeyframes.isNotEmpty()) {
            PropAndCssValidator.renameInlineAnimations(
                animated = openCtx.animatedStyles,
                styleHolders = openStyleHolders,
                names = scopedKeyframes,
                prefix = idPrefix,
            )
        }

        val notice = overNotice
        val finalTree = if (notice != null) listOf(notice) else rebindMutatedStyleObjects(validatedRoot, openStyleHolders)

        if (referencesEnv != null && refs != null && stagedParts != null && overNotice == null) {
            if (scopedKeyframes.isNotEmpty() || openCtx.animatedStyles.isNotEmpty()) {
                refs.unsafeCssAnimationSeen = true
            }
            refs.commitTransaction(
                newEpoch = referencesEnv.epoch,
                frameRevision = frameRevision,
                nextParts = stagedParts,
                newlyDefined = newlyDefinedParts,
            )
        }

        val profileWireName = when (options.profile) {
            ValidationProfile.PORTABLE_V1 -> "portable/1"
            ValidationProfile.WEB_OPEN_V1 -> "web-open/1"
            ValidationProfile.STEP2_VECTOR_COMPAT -> null
        }

        val resolvedFrame = SurfaceFrame(
            protocol = GENUI_TREE_PROTOCOL_V1,
            surfaceId = options.hostEnvelope?.surfaceId ?: wireSurfaceId,
            surfaceGeneration = options.hostEnvelope?.surfaceGeneration ?: wireSurfaceGeneration,
            sourceRevision = options.hostEnvelope?.sourceRevision ?: wireSourceRevision,
            stateRevision = options.hostEnvelope?.stateRevision ?: wireStateRevision,
            // Host-controlled catalog & profile identity ONLY (`semantic.json` `envelopes` case 4)
            catalogId = options.hostEnvelope?.catalogId ?: catalog.declaredCatalogId,
            catalogVersion = options.hostEnvelope?.catalogVersion ?: catalog.declaredCatalogVersion,
            profile = options.hostEnvelope?.profile ?: profileWireName,
            runtimeEpoch = runtimeEpoch,
            frameRevision = frameRevision,
            references = referencesEnv,
            root = finalTree,
        )

        return FrameValidationOutcome(
            frame = resolvedFrame,
            tree = finalTree,
            diagnostics = diagnostics,
            stats = ValidationStats(
                nodes = nodeCount,
                items = itemCount,
                droppedProps = droppedPropsCount,
                droppedEvents = droppedEventsCount,
                unknownComponents = unknownComponentsCount,
                diagnosticsOmitted = diagnosticsOmitted,
                guestDiagnostics = guestDiagCount,
                hostDiagnostics = hostDiagCount,
            ),
        )
    }

    private fun placeElement(
        item: JsonObject,
        name: String,
        nodeId: String,
        depth: Int,
        inSvg: Boolean,
        parentPrimitiveTag: String?,
        svgRootCtx: PortableV6Spec.SvgRootContext?,
        previousStopOffset: Double?,
        out: MutableList<TreeNode>,
        isStep2Compat: Boolean,
        options: ValidateFrameOptions,
        catalog: Catalog,
        idPrefix: String,
        openCtx: PropAndCssValidator.OpenValidationContext,
        openStyleHolders: MutableList<MutableMap<String, JsonElement>>,
        scopedKeyframes: MutableSet<String>,
        validateEvents: (JsonObject, String, Set<String>?, OpenPolicy?) -> Map<String, EventBinding>?,
        walkList: (JsonElement?, Int, Boolean, String?, PortableV6Spec.SvgRootContext?) -> List<TreeNode>,
        extractSvgTitle: (List<TreeNode>) -> String?,
        makeNotice: (String, String, String, String?, Int?) -> TreeNode.Element,
        addHostDiagnostic: (String, String, String?, Boolean) -> Unit,
        onUnknownComponent: () -> Unit,
        onDroppedProps: (Int) -> Unit,
        abortFrame: (String, String, String?, String?) -> Nothing,
    ): Double? {
        val safeDisplayName = name.takeUnicodeCodePoints(ProtocolLimits.MAX_NAME_CHARS)
        val startsLower = name.first().isLowerCase()

        // Open-mode branch (`WEB_OPEN_V1` or `STEP2_VECTOR_COMPAT` with `catalog.open != null`)
        val openPolicy = when (options.profile) {
            ValidationProfile.PORTABLE_V1 -> null
            ValidationProfile.WEB_OPEN_V1, ValidationProfile.STEP2_VECTOR_COMPAT -> catalog.open
        }

        if (openPolicy != null && startsLower) {
            val rawOpenProps = (item["p"] as? JsonObject) ?: JsonObject(emptyMap())
            val allowed = if (PropAndCssValidator.isOpenElementAllowed(name, openPolicy)) {
                PropAndCssValidator.openProps(name, rawOpenProps, openPolicy, openCtx)
            } else {
                null
            }
            if (allowed == null) {
                onUnknownComponent()
                addHostDiagnostic("UNSUPPORTED_CAPABILITY", "<$safeDisplayName> is not supported here", nodeId, false)
                if (!inSvg) {
                    out.add(makeNotice(nodeId, "UNSUPPORTED_CAPABILITY", "<$safeDisplayName> is not supported here", safeDisplayName, null))
                }
                return previousStopOffset
            }
            if (allowed.issues.isNotEmpty()) {
                onDroppedProps(allowed.issues.size)
                for (issue in allowed.issues) {
                    addHostDiagnostic(issue.diagnosticCode, "Dropped '${issue.propName}' on <$safeDisplayName>", nodeId, false)
                }
            }
            if (allowed.disposition == PropAndCssValidator.OpenElementDisposition.BLOCKED_IMAGE_NOTICE) {
                if (!inSvg) {
                    out.add(makeNotice(nodeId, "RESOURCE_BLOCKED", allowed.fallbackAltText, safeDisplayName, null))
                }
                return previousStopOffset
            }
            if (allowed.disposition == PropAndCssValidator.OpenElementDisposition.BLOCKED_SVG_IMAGE_OMIT) {
                return previousStopOffset
            }

            if (name == "style") {
                val rawText = (item["c"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    ?.joinToString("")
                    ?: ""
                val classMapper: ((String) -> String)? = if (options.scopeOpenClassNames) {
                    { clsName -> PropAndCssValidator.scopedClass(clsName, idPrefix, options.publicClasses) }
                } else {
                    null
                }
                val scoped = PropAndCssValidator.scopeCss(rawText, "${idPrefix}scope", idPrefix, classMapper)
                if (scoped.blockedResource) {
                    addHostDiagnostic("RESOURCE_BLOCKED", "Blocked external CSS resource in <style>", nodeId, false)
                }
                scopedKeyframes.addAll(scoped.keyframes)
                out.add(
                    TreeNode.Element(
                        id = nodeId,
                        kind = NodeKind.HTML,
                        type = "style",
                        props = JsonObject(emptyMap()),
                        events = emptyMap(),
                        children = listOf(TreeNode.Text(scoped.css)),
                    ),
                )
                return previousStopOffset
            }

            val events = validateEvents(item, nodeId, null, openPolicy)
            if (events == null) {
                onUnknownComponent()
                addHostDiagnostic("INVALID_NODE", "Invalid events field on <$safeDisplayName>", nodeId, false)
                if (!inSvg) out.add(makeNotice(nodeId, "INVALID_NODE", "Invalid node", null, null))
                return previousStopOffset
            }

            val resolvedKind = if (options.profile == ValidationProfile.STEP2_VECTOR_COMPAT && name in PORTABLE_PRIMITIVES_V1) {
                NodeKind.PRIMITIVE
            } else {
                NodeKind.HTML
            }

            if (allowed.styleRef != null && ("animation" in allowed.styleRef || "animationName" in allowed.styleRef)) {
                val markerKey = "\u0000__anim_idx_${openStyleHolders.size}"
                allowed.props[markerKey] = JsonPrimitive(openStyleHolders.size)
                openStyleHolders.add(allowed.props)
            }

            val childInSvg = if (name == "foreignObject") false else (inSvg || name == "svg")
            val children = walkList(item["c"], depth + 1, childInSvg, null, null)
            out.add(
                TreeNode.Element(
                    id = nodeId,
                    kind = resolvedKind,
                    type = name,
                    props = JsonObject(allowed.props),
                    events = events,
                    children = children,
                ),
            )
            return previousStopOffset
        }

        // Strict / `PORTABLE_V1` catalog + portable-primitives branch
        val entryAndKind: Pair<ComponentSpec, NodeKind>? = when (options.profile) {
            ValidationProfile.PORTABLE_V1 -> {
                if (name in PORTABLE_PRIMITIVES_V1) {
                    catalog.portablePrimitives[name]?.let { it to NodeKind.PRIMITIVE }
                } else {
                    catalog.components[name]?.let { it to NodeKind.COMPONENT }
                }
            }

            ValidationProfile.WEB_OPEN_V1 -> {
                catalog.components[name]?.let { it to NodeKind.COMPONENT }
            }

            ValidationProfile.STEP2_VECTOR_COMPAT -> {
                catalog.components[name]?.let { spec ->
                    val k = if (name in PORTABLE_PRIMITIVES_V1) NodeKind.PRIMITIVE else NodeKind.COMPONENT
                    spec to k
                }
            }
        }

        val entry = entryAndKind?.first
        val isPortableProfile = options.profile == ValidationProfile.PORTABLE_V1

        // Content placement rules (`primitives.ts` & `catalog.ts`):
        // `<svg>`, `<g>`, and catalog SVG containers (`parentPrimitiveTag == null` when `inSvg`, e.g. `<defs>`, `<clipPath>`, `<mask>`, `<radialGradient>`)
        // allow `entry.svg == SvgPlacement.CHILD`.
        val allowedCatalogSvgChild = entryAndKind?.second == NodeKind.COMPONENT &&
            entry?.svg == SvgPlacement.CHILD &&
            (parentPrimitiveTag == null || parentPrimitiveTag == "svg" || parentPrimitiveTag == "g")

        val portablePlacementInvalid = isPortableProfile && (
            (parentPrimitiveTag == "span" && (entryAndKind?.second != NodeKind.PRIMITIVE || name != "span")) ||
                (inSvg && entryAndKind?.second != NodeKind.PRIMITIVE && !allowedCatalogSvgChild) ||
                (parentPrimitiveTag == "svg" && entryAndKind?.second == NodeKind.PRIMITIVE && name !in PortableV6Spec.SVG_ROOT_ALLOWED_CHILDREN) ||
                (parentPrimitiveTag == "g" && entryAndKind?.second == NodeKind.PRIMITIVE && name !in PortableV6Spec.SVG_G_ALLOWED_CHILDREN) ||
                (parentPrimitiveTag == "linearGradient" && (entryAndKind?.second != NodeKind.PRIMITIVE || name != "stop")) ||
                (parentPrimitiveTag == "text" && inSvg) ||
                (entryAndKind?.second == NodeKind.PRIMITIVE && name == "stop" && parentPrimitiveTag != null && parentPrimitiveTag != "linearGradient") ||
                (entryAndKind?.second == NodeKind.PRIMITIVE && name == "linearGradient" && parentPrimitiveTag != null && parentPrimitiveTag != "svg")
            )

        val placementInvalid = entry != null && (
            (entry.svg == SvgPlacement.CHILD && !inSvg) ||
                (openPolicy == null && inSvg && entry.svg == null) ||
                portablePlacementInvalid
            )

        if (entry == null || placementInvalid) {
            onUnknownComponent()
            val isUnknownCatalogComponent = entry == null && name.first().isUpperCase()
            val code = if (isUnknownCatalogComponent) "UNKNOWN_COMPONENT" else "UNSUPPORTED_CAPABILITY"
            val fallback = if (isUnknownCatalogComponent) {
                "Unknown component <$safeDisplayName>"
            } else {
                "<$safeDisplayName> is not supported here"
            }
            addHostDiagnostic(code, fallback, nodeId, false)
            if (!inSvg && parentPrimitiveTag != "span") {
                out.add(makeNotice(nodeId, code, fallback, safeDisplayName, null))
            }
            return previousStopOffset
        }

        val rawProps = (item["p"] as? JsonObject) ?: JsonObject(emptyMap())

        // In `PORTABLE_V1`, portable primitives (`g == "p"`) use `PortableV6Spec` exact semantic validation
        if (isPortableProfile && entryAndKind.second == NodeKind.PRIMITIVE) {
            val activeSvgCtx = if (name == "svg") {
                val rootId = (rawProps["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                PortableV6Spec.SvgRootContext(idPrefix).apply {
                    validGradientIds.addAll(PortableV6Spec.collectSvgRootGradients(item["c"] as? JsonArray, idPrefix, rootId))
                }
            } else {
                svgRootCtx ?: PortableV6Spec.SvgRootContext(idPrefix)
            }

            var nextStopOff = previousStopOffset
            val v6PropsResult = if (name == "div" || name == "span") {
                PortableV6Spec.validateBoxOrSpanPropsV6(name, rawProps, idPrefix)
            } else {
                val (res, resolvedOff) = PortableV6Spec.validateSvgNodePropsV6(
                    tag = name,
                    rawProps = rawProps,
                    idPrefix = idPrefix,
                    localGradientIds = activeSvgCtx.validGradientIds,
                    seenSvgIds = activeSvgCtx.seenIds,
                    previousStopOffset = previousStopOffset,
                    unresolvedGradientPaint = options.unresolvedGradientPaint,
                )
                nextStopOff = resolvedOff
                res
            }

            if (v6PropsResult.droppedCount > 0) {
                onDroppedProps(v6PropsResult.droppedCount)
                for (dm in v6PropsResult.droppedMessages) {
                    addHostDiagnostic("PROP_DROPPED", "Dropped '$dm' on <$safeDisplayName>", nodeId, false)
                }
            }

            if (v6PropsResult.rejectStopNode) {
                onUnknownComponent()
                addHostDiagnostic("INVALID_NODE", "Decreasing <stop> offset on <$safeDisplayName>", nodeId, false)
                return nextStopOff
            }

            val allowedPrimitiveEvents = if (name == "linearGradient" || name == "stop") emptySet() else setOf("click")
            val cleanEvents = validateEvents(item, nodeId, allowedPrimitiveEvents, null)
            if (cleanEvents == null) {
                onUnknownComponent()
                addHostDiagnostic("INVALID_NODE", "Invalid events field on <$safeDisplayName>", nodeId, false)
                if (!inSvg) out.add(makeNotice(nodeId, "INVALID_NODE", "Invalid node", null, null))
                return nextStopOff
            }

            val acceptsKidsV6 = name !in setOf("path", "circle", "rect", "stop")
            val children = if (!acceptsKidsV6) {
                if ((item["c"] as? JsonArray)?.isNotEmpty() == true) {
                    addHostDiagnostic("UNSUPPORTED_CAPABILITY", "<$safeDisplayName> does not accept children", nodeId, false)
                }
                emptyList()
            } else {
                walkList(
                    item["c"],
                    depth + 1,
                    inSvg || name == "svg",
                    if (name == "div") null else name,
                    if (inSvg || name == "svg") activeSvgCtx else null,
                )
            }

            val finalPrimitiveProps = if (name == "svg" && "ariaLabel" !in v6PropsResult.cleanProps) {
                val titleLabel = extractSvgTitle(children)
                if (titleLabel != null) {
                    JsonObject(LinkedHashMap(v6PropsResult.cleanProps).apply { put("ariaLabel", JsonPrimitive(titleLabel)) })
                } else {
                    v6PropsResult.cleanProps
                }
            } else {
                v6PropsResult.cleanProps
            }

            out.add(
                TreeNode.Element(
                    id = nodeId,
                    kind = NodeKind.PRIMITIVE,
                    type = name,
                    props = finalPrimitiveProps,
                    events = cleanEvents,
                    children = children,
                ),
            )
            return nextStopOff
        }

        val cleanProps = LinkedHashMap<String, JsonElement>()
        for ((propName, propVal) in rawProps) {
            val schema = if (propName in RESERVED_PROTOTYPE_KEYS) null else entry.props[propName]
            if (schema == null) {
                onDroppedProps(1)
                addHostDiagnostic("PROP_DROPPED", "Dropped prop '$propName' on <$safeDisplayName>", nodeId, false)
            } else {
                val checkedOutcome = PropAndCssValidator.checkPropEntry(
                    propName = propName,
                    schema = schema,
                    el = propVal,
                    idPrefix = idPrefix,
                    resolveResource = options.resolveResource,
                    resourcePolicy = options.resourcePolicy,
                )
                if (propName in entry.controlledProps && checkedOutcome.value == null) {
                    val misfitCode = when {
                        propVal is JsonNull -> "INVALID_FRAME"
                        schema is PropSchema.Str && propVal is JsonPrimitive &&
                            (propVal.isString || (propVal.booleanOrNull == null && propVal.doubleOrNull?.isFinite() == true)) ->
                            "LIMIT_EXCEEDED"
                        schema is PropSchema.Num && propVal is JsonPrimitive && !propVal.isString &&
                            propVal.booleanOrNull == null && propVal.doubleOrNull?.isFinite() == true ->
                            "LIMIT_EXCEEDED"
                        else -> "INVALID_FRAME"
                    }
                    val safeField = propName.takeUnicodeCodePoints(ProtocolLimits.MAX_NAME_CHARS)
                    abortFrame(
                        misfitCode,
                        "Controlled prop '$safeField' on <$safeDisplayName> failed validation",
                        nodeId,
                        propName,
                    )
                }
                if (checkedOutcome.issues.isNotEmpty()) {
                    onDroppedProps(checkedOutcome.issues.size)
                    for (issue in checkedOutcome.issues) {
                        addHostDiagnostic(issue.diagnosticCode, "Dropped prop '${issue.propName}' on <$safeDisplayName>", nodeId, false)
                    }
                }
                if (checkedOutcome.value != null) {
                    cleanProps[propName] = checkedOutcome.value
                }
            }
        }

        val cleanEvents = validateEvents(item, nodeId, entry.events, null)
        if (cleanEvents == null) {
            onUnknownComponent()
            addHostDiagnostic("INVALID_NODE", "Invalid events field on <$safeDisplayName>", nodeId, false)
            if (!inSvg) out.add(makeNotice(nodeId, "INVALID_NODE", "Invalid node", null, null))
            return previousStopOffset
        }

        val children = if (!entry.acceptsChildren) {
            emptyList()
        } else {
            walkList(item["c"], depth + 1, inSvg || entry.svg == SvgPlacement.ROOT, null, svgRootCtx)
        }

        if (entry.svg == SvgPlacement.ROOT && "ariaLabel" !in cleanProps) {
            val label = extractSvgTitle(children)
            if (label != null) {
                cleanProps["ariaLabel"] = JsonPrimitive(label)
            }
        }

        out.add(
            TreeNode.Element(
                id = nodeId,
                kind = entryAndKind.second,
                type = name,
                props = JsonObject(cleanProps),
                events = cleanEvents,
                children = children,
            ),
        )
        return previousStopOffset
    }

    private fun rebindMutatedStyleObjects(
        nodes: List<TreeNode>,
        holders: List<MutableMap<String, JsonElement>>,
    ): List<TreeNode> {
        if (holders.isEmpty()) return nodes
        return nodes.map { node ->
            when (node) {
                is TreeNode.Text -> node
                is TreeNode.Element -> {
                    val markerEntry = node.props.entries.firstOrNull { it.key.startsWith("\u0000__anim_idx_") }
                    val updatedProps = if (markerEntry != null) {
                        val idx = (markerEntry.value as JsonPrimitive).content.toInt()
                        val holder = holders[idx]
                        JsonObject(holder.filterKeys { !it.startsWith("\u0000__anim_idx_") })
                    } else {
                        node.props
                    }
                    val updatedChildren = rebindMutatedStyleObjects(node.children, holders)
                    if (updatedProps === node.props && updatedChildren === node.children) {
                        node
                    } else {
                        node.copy(props = updatedProps, children = updatedChildren)
                    }
                }
            }
        }
    }

    private fun rejectEnvelope(code: String, message: String): FrameValidationOutcome = FrameValidationOutcome(
        frame = null,
        tree = emptyList(),
        diagnostics = listOf(
            ProtocolDiagnostic(
                code = code,
                phase = "validate",
                message = message.takeUnicodeCodePoints(ProtocolLimits.MAX_DIAGNOSTIC_MESSAGE_CHARS),
                origin = DiagnosticOrigin.HOST,
            ),
        ),
        stats = ValidationStats(hostDiagnostics = 1),
        errorCode = code,
    )
}

/**
 * Structural subtree sharing (`shareTree`) across consecutive validated frames:
 * - Preserves exact Kotlin object identity (`===`) for unchanged subtrees (comparing `id`, `kind`, `type`, `props`, `events`, `children`)
 *   so Jetpack Compose skips recomposition of unchanged branches during streaming.
 */
object TreeSharer {
    data class ShareResult(
        val nodes: List<TreeNode>,
        val sharedNodes: Int,
    )

    fun shareTree(prev: List<TreeNode>?, next: List<TreeNode>, refs: TreeRefs? = null): ShareResult {
        if (prev == null) return ShareResult(next, 0)
        var shared = 0
        val out = ArrayList<TreeNode>(next.size)
        for (i in next.indices) {
            val prevItem = prev.getOrNull(i)
            val nextItem = next[i]
            val kept = shareNode(prevItem, nextItem, refs)
            if (kept === prevItem && kept is TreeNode.Element) {
                shared += countElementNodes(kept)
            } else if (kept is TreeNode.Element && prevItem is TreeNode.Element) {
                shared += countKeptUnder(prevItem, kept)
            }
            out.add(kept)
        }
        return ShareResult(out, shared)
    }

    private fun shareNode(prev: TreeNode?, next: TreeNode, refs: TreeRefs?): TreeNode {
        if (prev === next) return next
        if (prev !is TreeNode.Element || next !is TreeNode.Element) return next
        if (prev.id != next.id || prev.kind != next.kind || prev.type != next.type) return next

        var allChildrenSame = prev.children.size == next.children.size
        val nextChildren = ArrayList<TreeNode>(next.children.size)
        for (i in next.children.indices) {
            val prevChild = prev.children.getOrNull(i)
            val sharedChild = shareNode(prevChild, next.children[i], refs)
            if (sharedChild !== prevChild) allChildrenSame = false
            nextChildren.add(sharedChild)
        }
        if (!allChildrenSame || prev.props != next.props || prev.events != next.events) {
            return next.copy(children = nextChildren)
        }
        refs?.repoint(next, prev)
        return prev
    }

    private fun countElementNodes(node: TreeNode): Int = when (node) {
        is TreeNode.Text -> 0
        is TreeNode.Element -> 1 + node.children.sumOf { countElementNodes(it) }
    }

    private fun countKeptUnder(prev: TreeNode.Element, cur: TreeNode.Element): Int {
        var n = 0
        for (i in cur.children.indices) {
            val c = cur.children[i]
            val p = prev.children.getOrNull(i)
            if (c !is TreeNode.Element) continue
            n += if (c === p) countElementNodes(c) else if (p is TreeNode.Element) countKeptUnder(p, c) else 0
        }
        return n
    }
}
