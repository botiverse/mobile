package build.raft.sprout.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

const val GENUI_TREE_PROTOCOL_V1 = "genui.tree/1"
const val NOTICE_TYPE = "\$notice"
const val MAX_JS_SAFE_INTEGER = 9_007_199_254_740_991L

val RESERVED_PROTOTYPE_KEYS: Set<String> = setOf("__proto__", "constructor", "prototype")

/**
 * Minimal cross-platform `portable-primitives/1` tag set (v5 §14 Decision 1 & v6 §15 Item 9).
 */
val PORTABLE_PRIMITIVES_V1: Set<String> = setOf(
    "div", "span", "svg", "path", "circle", "rect", "g", "text", "linearGradient", "stop",
)

/**
 * Explicit Web open-mode event allowlist (`WEB_OPEN_EVENTS` in `primitives.ts`, v5 §14 Decision 8).
 */
val DEFAULT_WEB_OPEN_EVENTS: Set<String> = setOf(
    "click", "doubleClick", "contextMenu", "mouseDown", "mouseUp", "mouseEnter", "mouseLeave", "mouseMove", "mouseOver", "mouseOut",
    "pointerDown", "pointerUp", "pointerMove", "pointerEnter", "pointerLeave", "pointerOver", "pointerOut", "pointerCancel",
    "touchStart", "touchMove", "touchEnd", "touchCancel", "keyDown", "keyUp", "keyPress", "focus", "blur",
    "change", "input", "submit", "reset", "select", "toggle", "wheel", "scroll",
    "dragStart", "drag", "dragEnd", "dragEnter", "dragLeave", "dragOver", "drop",
    "animationStart", "animationEnd", "animationIteration", "transitionEnd",
    "load", "error", "play", "pause", "ended", "timeUpdate",
)

object ProtocolLimits {
    const val MAX_NODES = 5000
    const val MAX_ITEMS = 5000
    const val MAX_FRAME_BYTES = 1_048_576
    const val MAX_DEPTH = 40
    const val GUEST_MAX_NODES = 20000
    const val GUEST_MAX_DEPTH = 120
    const val MAX_RENDER_PASSES = 10

    const val MAX_NODE_ID_CHARS = 1000
    const val MAX_NAME_CHARS = 60
    const val MAX_DIAGNOSTIC_MESSAGE_CHARS = 300
    const val MAX_DIAGNOSTICS = 32
    const val MAX_GUEST_DIAGNOSTICS = 8

    const val DEFAULT_STRING_CHARS = 500
    const val MAX_URL_CHARS = 2000
    const val MAX_PAINT_CHARS = 80
    const val MAX_LENGTH_CHARS = 20
    const val MAX_COORDINATE = 100000.0
    const val MAX_BOX_LENGTH = 1_000_000
    const val MAX_ARIA_LABEL_CHARS = 200
    const val MAX_PATH_CHARS = 4000
    const val MAX_ID_CHARS = 41
    const val MAX_TRANSFORM_CHARS = 400
    const val MAX_TRANSFORM_FUNCTIONS = 8
    const val MAX_TITLE_CHARS = 200
    const val MAX_DATA_IMAGE_CHARS = 8000

    const val MAX_TAG_CHARS = 40
    const val MAX_ATTRIBUTE_TEXT = 8000
    const val MAX_STYLE_PROPERTIES = 200
    const val MAX_STYLE_VALUE_CHARS = 2000
    const val MAX_STYLE_TEXT = 20000

    const val MAX_EVENT_FIELDS = 24
    const val MAX_EVENT_STRING_CHARS = 4000
    const val MAX_EVENT_NAME_CHARS = 64

    const val MIN_TIMER_DELAY_MS = 4
    const val MAX_TIMERS = 64
    const val MAX_SHARED_STATE_CHARS = 65536
    const val MAX_LOG_CHARS = 2000
    const val MAX_HOST_ARGUMENT_CHARS = 4000

    fun asMap(): Map<String, Int> = mapOf(
        "MAX_NODES" to MAX_NODES,
        "MAX_ITEMS" to MAX_ITEMS,
        "MAX_FRAME_BYTES" to MAX_FRAME_BYTES,
        "MAX_DEPTH" to MAX_DEPTH,
        "GUEST_MAX_NODES" to GUEST_MAX_NODES,
        "GUEST_MAX_DEPTH" to GUEST_MAX_DEPTH,
        "MAX_RENDER_PASSES" to MAX_RENDER_PASSES,
        "MAX_NODE_ID_CHARS" to MAX_NODE_ID_CHARS,
        "MAX_NAME_CHARS" to MAX_NAME_CHARS,
        "MAX_DIAGNOSTIC_MESSAGE_CHARS" to MAX_DIAGNOSTIC_MESSAGE_CHARS,
        "MAX_DIAGNOSTICS" to MAX_DIAGNOSTICS,
        "MAX_GUEST_DIAGNOSTICS" to MAX_GUEST_DIAGNOSTICS,
        "DEFAULT_STRING_CHARS" to DEFAULT_STRING_CHARS,
        "MAX_URL_CHARS" to MAX_URL_CHARS,
        "MAX_PAINT_CHARS" to MAX_PAINT_CHARS,
        "MAX_LENGTH_CHARS" to MAX_LENGTH_CHARS,
        "MAX_COORDINATE" to MAX_COORDINATE.toInt(),
        "MAX_BOX_LENGTH" to MAX_BOX_LENGTH,
        "MAX_ARIA_LABEL_CHARS" to MAX_ARIA_LABEL_CHARS,
        "MAX_PATH_CHARS" to MAX_PATH_CHARS,
        "MAX_ID_CHARS" to MAX_ID_CHARS,
        "MAX_TRANSFORM_CHARS" to MAX_TRANSFORM_CHARS,
        "MAX_TRANSFORM_FUNCTIONS" to MAX_TRANSFORM_FUNCTIONS,
        "MAX_TITLE_CHARS" to MAX_TITLE_CHARS,
        "MAX_DATA_IMAGE_CHARS" to MAX_DATA_IMAGE_CHARS,
        "MAX_TAG_CHARS" to MAX_TAG_CHARS,
        "MAX_ATTRIBUTE_TEXT" to MAX_ATTRIBUTE_TEXT,
        "MAX_STYLE_PROPERTIES" to MAX_STYLE_PROPERTIES,
        "MAX_STYLE_VALUE_CHARS" to MAX_STYLE_VALUE_CHARS,
        "MAX_STYLE_TEXT" to MAX_STYLE_TEXT,
        "MAX_EVENT_FIELDS" to MAX_EVENT_FIELDS,
        "MAX_EVENT_STRING_CHARS" to MAX_EVENT_STRING_CHARS,
        "MAX_EVENT_NAME_CHARS" to MAX_EVENT_NAME_CHARS,
        "MIN_TIMER_DELAY_MS" to MIN_TIMER_DELAY_MS,
        "MAX_TIMERS" to MAX_TIMERS,
        "MAX_SHARED_STATE_CHARS" to MAX_SHARED_STATE_CHARS,
        "MAX_LOG_CHARS" to MAX_LOG_CHARS,
        "MAX_HOST_ARGUMENT_CHARS" to MAX_HOST_ARGUMENT_CHARS,
    )
}

@Serializable
enum class NodeKind(val wireCode: String) {
    /** Registered host catalog component (`"c"`). */
    @SerialName("c")
    COMPONENT("c"),

    /** Cross-platform primitive in `PORTABLE_PRIMITIVES_V1` (`"p"`). */
    @SerialName("p")
    PRIMITIVE("p"),

    /** Web open-mode HTML / CSS extension element (`"h"`). */
    @SerialName("h")
    HTML("h");

    companion object {
        fun fromWireOrNull(code: String): NodeKind? = when (code) {
            "c" -> COMPONENT
            "p" -> PRIMITIVE
            "h" -> HTML
            else -> null
        }
    }
}

/**
 * Event binding `{ "h": handlerId, "e": runtimeEpoch }` (v5 §14 Decision 5 & 6).
 */
@Serializable
data class EventBinding(
    @SerialName("h") val handler: Int,
    @SerialName("e") val epoch: Long,
) {
    constructor(handler: Int, epoch: Int) : this(handler, epoch.toLong())
}

@Serializable(with = TreeNodeSerializer::class)
sealed class TreeNode {
    data class Text(val text: String) : TreeNode()

    data class Element(
        val id: String,
        val kind: NodeKind,
        val type: String,
        val props: JsonObject = JsonObject(emptyMap()),
        val events: Map<String, EventBinding> = emptyMap(),
        val children: List<TreeNode> = emptyList(),
    ) : TreeNode()
}

object TreeNodeSerializer : KSerializer<TreeNode> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("build.raft.sprout.protocol.TreeNode")

    override fun deserialize(decoder: Decoder): TreeNode {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("TreeNode requires JSON decoding")
        return decodeNode(jsonDecoder.decodeJsonElement())
    }

    private fun decodeNode(el: JsonElement): TreeNode {
        if (el is JsonPrimitive) {
            if (!el.isString) throw SerializationException("Primitive TreeNode must be string")
            return TreeNode.Text(el.content)
        }
        val obj = el as? JsonObject ?: throw SerializationException("TreeNode must be string or object")
        val id = obj["i"]?.jsonPrimitive?.takeIf { it.isString }?.content
            ?: throw SerializationException("Missing string 'i'")
        val kindCode = obj["g"]?.jsonPrimitive?.takeIf { it.isString }?.content
            ?: throw SerializationException("Missing string 'g'")
        val kind = NodeKind.fromWireOrNull(kindCode)
            ?: throw SerializationException("Invalid kind '$kindCode'")
        val type = obj["t"]?.jsonPrimitive?.takeIf { it.isString }?.content
            ?: throw SerializationException("Missing string 't'")
        val props = obj["p"]?.jsonObject ?: JsonObject(emptyMap())
        val rawEvents = obj["e"]?.jsonObject ?: JsonObject(emptyMap())
        val events = LinkedHashMap<String, EventBinding>(rawEvents.size)
        for ((evName, bEl) in rawEvents) {
            val bObj = bEl as? JsonObject ?: throw SerializationException("Invalid event binding '$evName'")
            val h = exactSignedInt32OrNull(bObj["h"]) ?: throw SerializationException("Invalid handler int")
            val e = exactSafeNonNegativeLongOrNull(bObj["e"]) ?: throw SerializationException("Invalid epoch integer")
            events[evName] = EventBinding(h, e)
        }
        val children = (obj["c"]?.jsonArray ?: JsonArray(emptyList())).map { decodeNode(it) }
        return TreeNode.Element(id = id, kind = kind, type = type, props = props, events = events, children = children)
    }

    override fun serialize(encoder: Encoder, value: TreeNode) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("TreeNode requires JSON encoding")
        jsonEncoder.encodeJsonElement(encodeNode(value))
    }

    fun encodeNode(node: TreeNode): JsonElement = when (node) {
        is TreeNode.Text -> JsonPrimitive(node.text)
        is TreeNode.Element -> buildJsonObject {
            put("i", node.id)
            put("g", node.kind.wireCode)
            put("t", node.type)
            put("p", node.props)
            put(
                "e",
                buildJsonObject {
                    for ((k, v) in node.events) {
                        put(
                            k,
                            buildJsonObject {
                                put("h", v.handler)
                                put("e", v.epoch)
                            },
                        )
                    }
                },
            )
            put("c", JsonArray(node.children.map { encodeNode(it) }))
        }
    }
}

@Serializable
data class ReferencesEnvelope(
    val epoch: Long,
    /** v5 §14 Decision 13: previous committed frameRevision that built the reference table (0 = fresh full snapshot). */
    val baseFrameRevision: Long? = null,
)

@Serializable
enum class DiagnosticOrigin {
    @SerialName("guest")
    GUEST,

    @SerialName("host")
    HOST,
}

val KNOWN_DIAGNOSTIC_PHASES: Set<String> = setOf(
    "source", "compile", "load", "render", "validate", "event", "timer", "engine",
    "state", "action", "module",
)

val KNOWN_DIAGNOSTIC_CODES: Set<String> = setOf(
    // guest / runtime / compiler
    "COMPILE_ERROR", "FINAL_INVALID", "SOURCE_DIGEST_MISMATCH", "SOURCE_DIGEST_UNCHECKED",
    "SOURCE_GAP", "SOURCE_CONFLICT", "SOURCE_STALE", "SOURCE_ABANDONED",
    "NO_ROOT_COMPONENT", "RUNTIME_ERROR", "INTERRUPTED", "BUDGET_EXCEEDED",
    "EMPTY_TREE", "DUPLICATE_KEY", "TIMERS_DROPPED", "TIMERS_PAUSED", "ENGINE_FAILED",
    // protocol / validator / references
    "UNSUPPORTED_PROTOCOL", "INVALID_FRAME", "INVALID_NODE",
    "UNKNOWN_COMPONENT", "UNSUPPORTED_CAPABILITY",
    "PROP_DROPPED", "EVENT_DROPPED",
    "TREE_TOO_DEEP", "TREE_TOO_LARGE", "LIMIT_EXCEEDED",
    "MISSING_REFERENCE", "FRAME_BASE_MISMATCH",
    // session / state / action / event / resource / module (v5 §14, v6 §15, v8 §17, v34 §37)
    "STALE_EVENT", "INVALID_EVENT", "STALE_RECEIPT", "EFFECT_SUPPRESSED",
    "STATE_CONFLICT", "STATE_DENIED", "STATE_QUEUE_FULL", "STATE_UNCERTAIN",
    "RESULT_UNCONFIRMED", "SOURCE_UNBOUND",
    "REQUEST_ID_CONFLICT", "MISSING_ID_GENERATOR",
    "LINK_BLOCKED", "RESOURCE_BLOCKED", "NOT_VISIBLE", "SURFACE_DISPOSED",
    "MISSING_SURFACE_ID", "DUPLICATE_SURFACE_ID",
    "MODULE_ASYNC_INIT_UNSUPPORTED", "MODULE_NOT_READY", "MODULE_REQUIRES_DOM",
)

/**
 * Authoritative state write denial codes (`packages/state-action/src/types.ts` `STATE_DENIAL_CODES`).
 */
val STATE_DENIAL_CODES: Set<String> = linkedSetOf(
    "FORBIDDEN",
    "SURFACE_NOT_FOUND",
    "GENERATION_MISMATCH",
    "SCHEMA_MISMATCH",
    "MISSING_SURFACE_ID",
    "DUPLICATE_SURFACE_ID",
    "PREVIEW_NOT_FINISHED",
    "PAYLOAD_TOO_LARGE",
    "LIMIT_EXCEEDED",
    "PENDING_QUEUE_FULL",
    "INVALID_JSON_VALUE",
    "REQUEST_ID_CONFLICT",
    "MISSING_ID_GENERATOR",
    "STALE_EVENT",
    "UNSUPPORTED_CAPABILITY",
)

/**
 * Authoritative action emission denial codes (`packages/state-action/src/types.ts` `ACTION_DENIAL_CODES`).
 */
val ACTION_DENIAL_CODES: Set<String> = LinkedHashSet<String>(STATE_DENIAL_CODES.size + 5).apply {
    addAll(STATE_DENIAL_CODES)
    add("USER_ACTIVATION_REQUIRED")
    add("STALE_ACTIVATION_TOKEN")
    add("HOST_CONFIRMATION_DECLINED")
    add("IN_FLIGHT_WRITE_PENDING")
    add("UNKNOWN_ACTION")
}

/**
 * Structured reason why a local shared-state edit lost its overlay (`Spec v36 §37` / `DroppedOperation.reason`).
 * Consumers encountering an unknown or absent wire value MUST fall back neutrally via [describeForUser]
 * ("Your local change was withdrawn.") and never guess "replaced by a new version".
 */
const val UNSPECIFIED_DROP_REASON = "unspecified"

@Serializable
enum class DroppedOperationReason(val wireName: String) {
    @SerialName("source-switched")
    SOURCE_SWITCHED("source-switched"),

    @SerialName("source-unbound")
    SOURCE_UNBOUND("source-unbound"),

    @SerialName("concurrent-state")
    CONCURRENT_STATE("concurrent-state"),

    @SerialName("conflict")
    CONFLICT("conflict"),

    @SerialName("denied")
    DENIED("denied");

    companion object {
        const val NEUTRAL_WITHDRAWN_MESSAGE: String = "Your local change was withdrawn."

        val WIRE_NAMES: Set<String> = entries.map { it.wireName }.toSet()

        fun isDropReason(wire: String?): Boolean = fromWireOrNull(wire) != null

        fun fromWireOrNull(wire: String?): DroppedOperationReason? = when (wire) {
            "source-switched" -> SOURCE_SWITCHED
            "source-unbound" -> SOURCE_UNBOUND
            "concurrent-state" -> CONCURRENT_STATE
            "conflict" -> CONFLICT
            "denied" -> DENIED
            else -> null
        }

        /**
         * Preserves valid short host reason strings (`1..64` UTF-16 code units) verbatim without truncation
         * or coercion, mapping missing/empty/overlong values to [UNSPECIFIED_DROP_REASON] (`05d7fc7` / `f46dd63`).
         */
        fun normalizeDroppedReason(raw: String?): String =
            if (raw != null && raw.isNotEmpty() && raw.length <= 64) raw else UNSPECIFIED_DROP_REASON

        fun describeForUser(wire: String?): String = when (fromWireOrNull(wire)) {
            SOURCE_SWITCHED -> "Your local change was withdrawn because the interface updated to a new version."
            SOURCE_UNBOUND -> "This interface cannot save right now; your change was not sent."
            CONCURRENT_STATE -> "Your local change was withdrawn because the saved state updated first."
            CONFLICT -> "Your local change was withdrawn because the saved state updated first."
            DENIED -> "Your local change was withdrawn because an earlier change was refused."
            null -> NEUTRAL_WITHDRAWN_MESSAGE
        }
    }
}

@Serializable
enum class SuspensionCause(val wireName: String) {
    @SerialName("authentication")
    AUTHENTICATION("authentication"),

    @SerialName("authorization")
    AUTHORIZATION("authorization"),

    @SerialName("host-policy")
    HOST_POLICY("host-policy"),

    @SerialName("unknown")
    UNKNOWN("unknown");

    companion object {
        val WIRE_NAMES: Set<String> = entries.map { it.wireName }.toSet()

        fun fromWireOrNull(wire: String?): SuspensionCause? = when (wire) {
            "authentication" -> AUTHENTICATION
            "authorization" -> AUTHORIZATION
            "host-policy" -> HOST_POLICY
            "unknown" -> UNKNOWN
            else -> null
        }
    }
}

@Serializable
data class ProtocolDiagnostic(
    val code: String,
    val phase: String = "validate",
    val message: String,
    val line: Long? = null,
    val nodeId: String? = null,
    val field: String? = null,
    val incomplete: Boolean? = null,
    val origin: DiagnosticOrigin = DiagnosticOrigin.HOST,
)

@Serializable
data class SurfaceFrame(
    val protocol: String = GENUI_TREE_PROTOCOL_V1,
    val surfaceId: String? = null,
    val surfaceGeneration: Long? = null,
    val sourceRevision: Long? = null,
    val stateRevision: Long? = null,
    val catalogId: String? = null,
    val catalogVersion: String? = null,
    val profile: String? = null,
    val runtimeEpoch: Long,
    val frameRevision: Long,
    val references: ReferencesEnvelope? = null,
    val root: List<TreeNode> = emptyList(),
) {
    /** Encodes the complete host-stamped envelope fields matching `semantic.json` `envelopes`. */
    fun toEnvelopeJson(): JsonObject = buildJsonObject {
        put("protocol", protocol)
        if (surfaceId != null) put("surfaceId", surfaceId)
        if (surfaceGeneration != null) put("surfaceGeneration", surfaceGeneration)
        if (sourceRevision != null) put("sourceRevision", sourceRevision)
        if (stateRevision != null) put("stateRevision", stateRevision)
        if (catalogId != null) put("catalogId", catalogId)
        if (catalogVersion != null) put("catalogVersion", catalogVersion)
        if (profile != null) put("profile", profile)
        put("runtimeEpoch", runtimeEpoch)
        put("frameRevision", frameRevision)
    }
}

/**
 * Canonical node ID path helpers (v6 §15 Item 12):
 * - Positional child: `n:<index>`
 * - Keyed child: `k:<escapedKey>` where `~` -> `~0` and `/` -> `~1` (RFC 6901 JSON Pointer)
 * - Component return boundary: `r`
 * - Duplicate sibling React key fallback: positional `n:<index>` (plus guest `DUPLICATE_KEY` diagnostic)
 */
object CanonicalNodeId {
    fun escapeKey(rawKey: String): String = buildString(rawKey.length + 4) {
        for (ch in rawKey) {
            when (ch) {
                '~' -> append("~0")
                '/' -> append("~1")
                else -> append(ch)
            }
        }
    }

    fun unescapeKey(escaped: String): String = escaped
        .replace("~1", "/")
        .replace("~0", "~")

    fun positionalSegment(index: Int): String = "n:$index"

    fun keyedSegment(rawKey: String): String = "k:${escapeKey(rawKey)}"

    fun returnBoundarySegment(): String = "r"

    fun duplicateKeySegment(index: Int, @Suppress("UNUSED_PARAMETER") rawKey: String = ""): String = "n:$index"
}

// ---- Catalog & Schema Models (`schema/genui.catalog.v1.schema.json` + v6 §15 + v8 §17) ----

enum class ResourceKind(val wireName: String) {
    IMAGE("image"),
    MEDIA("media"),
    OTHER("other");

    companion object {
        fun fromWire(raw: String?): ResourceKind = when (raw) {
            "image" -> IMAGE
            "media" -> MEDIA
            else -> OTHER
        }
    }
}

sealed class PropSchema {
    data class EnumVal(val values: Set<String>) : PropSchema()
    data class Str(val max: Int = ProtocolLimits.DEFAULT_STRING_CHARS) : PropSchema()
    data class Num(val min: Double, val max: Double) : PropSchema()
    data object Bool : PropSchema()
    data object Url : PropSchema()
    data class Resource(val resource: ResourceKind) : PropSchema()
    data object Length : PropSchema()
    data object Paint : PropSchema()
    data class Pattern(val chars: String, val max: Int) : PropSchema()
    data class Transform(val target: TransformTarget) : PropSchema()
    data object Id : PropSchema()
    data object IdRef : PropSchema()
    // ---- portable-primitives/1 ----
    data object Color : PropSchema()
    data object SvgPaint : PropSchema()
    data object ViewBox : PropSchema()
    data class PathData(val max: Int = ProtocolLimits.MAX_PATH_CHARS) : PropSchema()
    data object FontWeight : PropSchema()
    data object Fraction : PropSchema()
    data object Coordinate : PropSchema()
    data object SvgTransform : PropSchema()
    data class Style(val properties: Map<String, PropSchema>) : PropSchema()
}

enum class TransformTarget { CSS, SVG }

enum class SvgPlacement { ROOT, CHILD }

data class ContentPolicy(
    val nodes: Set<String> = emptySet(),
    val text: Boolean = false,
    val catalog: String = "none", // "svg" or "none"
)

data class ComponentSpec(
    val props: Map<String, PropSchema> = emptyMap(),
    val events: Set<String> = emptySet(),
    val eventArgs: Map<String, String> = emptyMap(),
    val acceptsChildren: Boolean = true,
    val svg: SvgPlacement? = null,
    val content: ContentPolicy? = null,
    /** Spec v8 §17 Item 4: controlled props whose invalid/overlimit wire value rejects the frame instead of turning uncontrolled. */
    val controlledProps: Set<String> = emptySet(),
)

data class OpenPolicy(
    val neverElements: Set<String>,
    val neverAttributes: Set<String>,
    val urlAttributes: Set<String>,
    val fragmentRefElements: Set<String>,
    val smilElements: Set<String>,
    val idListAttributes: Set<String>,
    /** Explicit allowed event names in `WEB_OPEN_V1` (v5 §14 Decision 8). Null only in Step 2 regex compat mode. */
    val allowedEvents: Set<String>? = DEFAULT_WEB_OPEN_EVENTS,
)

data class Catalog(
    val catalogId: String = "default",
    val catalogVersion: String = "1",
    val primitiveVersion: String = "portable-primitives/1",
    val components: Map<String, ComponentSpec> = emptyMap(),
    val portablePrimitives: Map<String, ComponentSpec> = DEFAULT_PORTABLE_PRIMITIVES_SCHEMA_V1,
    val open: OpenPolicy? = null,
    val declaredCatalogId: String? = null,
    val declaredCatalogVersion: String? = null,
) {
    /**
     * Merges another catalog into this one, failing loudly on duplicate component names unless
     * explicitly aliased by the host (v5 §14 Decision 9).
     */
    fun mergeWith(other: Catalog, aliases: Map<String, String> = emptyMap()): Catalog {
        val merged = LinkedHashMap(components)
        for ((rawName, spec) in other.components) {
            val targetName = aliases[rawName] ?: rawName
            if (targetName in merged) {
                throw IllegalArgumentException(
                    "Duplicate catalog component '$targetName' when merging '${other.catalogId}' into '$catalogId'; provide explicit host alias",
                )
            }
            merged[targetName] = spec
        }
        return copy(components = merged)
    }

    companion object {
        fun fromJson(obj: JsonObject, catalogId: String = "default", catalogVersion: String = "1"): Catalog {
            val compsObj = obj["components"]?.jsonObject ?: JsonObject(emptyMap())
            val components = LinkedHashMap<String, ComponentSpec>(compsObj.size)
            for ((name, specEl) in compsObj) {
                components[name] = parseComponentSpec(specEl.jsonObject)
            }
            val openPolicy = obj["open"]?.jsonObject?.let { openObj ->
                OpenPolicy(
                    neverElements = openObj.stringSet("neverElements"),
                    neverAttributes = openObj.stringSet("neverAttributes"),
                    urlAttributes = openObj.stringSet("urlAttributes"),
                    fragmentRefElements = openObj.stringSet("fragmentRefElements"),
                    smilElements = openObj.stringSet("smilElements"),
                    idListAttributes = openObj.stringSet("idListAttributes"),
                    allowedEvents = openObj["events"]?.let { openObj.stringSet("events") }
                        ?: openObj["allowedEvents"]?.let { openObj.stringSet("allowedEvents") }
                        ?: DEFAULT_WEB_OPEN_EVENTS,
                )
            }
            val rawId = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
            val rawVer = (obj["version"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
            return Catalog(
                catalogId = rawId ?: catalogId,
                catalogVersion = rawVer ?: catalogVersion,
                components = components,
                open = openPolicy,
                declaredCatalogId = rawId,
                declaredCatalogVersion = rawVer,
            )
        }

        fun primitivesFromJson(primitivesRootObj: JsonObject): Map<String, ComponentSpec> {
            val primsObj = primitivesRootObj["primitives"]?.jsonObject ?: return emptyMap()
            val out = LinkedHashMap<String, ComponentSpec>(primsObj.size)
            for ((name, specEl) in primsObj) {
                out[name] = parseComponentSpec(specEl.jsonObject)
            }
            return out
        }

        private fun parseComponentSpec(specObj: JsonObject): ComponentSpec {
            val propsObj = specObj["props"]?.jsonObject ?: JsonObject(emptyMap())
            val props = LinkedHashMap<String, PropSchema>(propsObj.size)
            for ((propName, schemaEl) in propsObj) {
                props[propName] = parsePropSchema(schemaEl.jsonObject)
            }
            val events = specObj["events"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
            val eventArgs = specObj["eventArgs"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            val acceptsChildren = specObj["children"]?.jsonPrimitive?.booleanOrNull ?: true
            val svg = when (specObj["svg"]?.jsonPrimitive?.content) {
                "root" -> SvgPlacement.ROOT
                "child" -> SvgPlacement.CHILD
                else -> null
            }
            val content = specObj["content"]?.jsonObject?.let { cObj ->
                ContentPolicy(
                    nodes = cObj["nodes"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet(),
                    text = cObj["text"]?.jsonPrimitive?.booleanOrNull ?: false,
                    catalog = cObj["catalog"]?.jsonPrimitive?.content ?: "none",
                )
            }
            val controlledProps = specObj["controlledProps"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
            return ComponentSpec(
                props = props,
                events = events,
                eventArgs = eventArgs,
                acceptsChildren = acceptsChildren,
                svg = svg,
                content = content,
                controlledProps = controlledProps,
            )
        }

        private fun parsePropSchema(s: JsonObject): PropSchema = when (val kind = s["kind"]?.jsonPrimitive?.content) {
            "enum" -> PropSchema.EnumVal(s["values"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
            "string" -> PropSchema.Str(s["max"]?.jsonPrimitive?.intOrNull ?: ProtocolLimits.DEFAULT_STRING_CHARS)
            "number" -> PropSchema.Num(
                min = s["min"]!!.jsonPrimitive.doubleOrNull!!,
                max = s["max"]!!.jsonPrimitive.doubleOrNull!!,
            )
            "boolean" -> PropSchema.Bool
            "url" -> PropSchema.Url
            "resource" -> PropSchema.Resource(ResourceKind.fromWire(s["resource"]?.jsonPrimitive?.content))
            "length" -> PropSchema.Length
            "paint" -> PropSchema.Paint
            "pattern" -> PropSchema.Pattern(
                chars = s["chars"]!!.jsonPrimitive.content,
                max = s["max"]!!.jsonPrimitive.intOrNull!!,
            )
            "transform" -> PropSchema.Transform(
                target = if (s["target"]?.jsonPrimitive?.content == "svg") TransformTarget.SVG else TransformTarget.CSS,
            )
            "id" -> PropSchema.Id
            "idRef" -> PropSchema.IdRef
            "color" -> PropSchema.Color
            "svgPaint" -> PropSchema.SvgPaint
            "viewBox" -> PropSchema.ViewBox
            "pathData" -> PropSchema.PathData(s["max"]?.jsonPrimitive?.intOrNull ?: ProtocolLimits.MAX_PATH_CHARS)
            "fontWeight" -> PropSchema.FontWeight
            "fraction" -> PropSchema.Fraction
            "coordinate" -> PropSchema.Coordinate
            "svgTransform" -> PropSchema.SvgTransform
            "style" -> {
                val propsObj = s["properties"]!!.jsonObject
                val map = LinkedHashMap<String, PropSchema>(propsObj.size)
                for ((k, v) in propsObj) {
                    map[k] = parsePropSchema(v.jsonObject)
                }
                PropSchema.Style(map)
            }
            else -> throw IllegalArgumentException("Unknown PropSchema kind '$kind'")
        }

        private fun JsonObject.stringSet(key: String): Set<String> =
            this[key]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
    }
}

/**
 * Exact `portable-primitives/1` schema matching `packages/protocol/src/primitives.ts` & `schema/genui.primitives.v1.json`.
 */
val DEFAULT_PORTABLE_PRIMITIVES_SCHEMA_V1: Map<String, ComponentSpec> = run {
    val maxBox = ProtocolLimits.MAX_BOX_LENGTH.toDouble()
    val size = PropSchema.Num(0.0, maxBox)
    val coord = PropSchema.Num(-maxBox, maxBox)
    val unit = PropSchema.Num(0.0, 1.0)

    val textStyle = linkedMapOf(
        "color" to PropSchema.Color,
        "fontSize" to size,
        "fontWeight" to PropSchema.FontWeight,
        "fontStyle" to PropSchema.EnumVal(setOf("normal", "italic")),
        "textDecoration" to PropSchema.EnumVal(setOf("none", "underline", "line-through")),
        "lineHeight" to size,
        "textAlign" to PropSchema.EnumVal(setOf("start", "center", "end")),
    )
    val boxStyle = linkedMapOf<String, PropSchema>(
        "display" to PropSchema.EnumVal(setOf("flex", "none")),
        "flexDirection" to PropSchema.EnumVal(setOf("row", "column")),
        "alignItems" to PropSchema.EnumVal(setOf("start", "center", "end", "stretch")),
        "justifyContent" to PropSchema.EnumVal(setOf("start", "center", "end", "space-between")),
        "gap" to size,
        "width" to size,
        "height" to size,
        "minWidth" to size,
        "minHeight" to size,
        "maxWidth" to size,
        "maxHeight" to size,
        "padding" to size,
        "paddingTop" to size,
        "paddingRight" to size,
        "paddingBottom" to size,
        "paddingLeft" to size,
        "borderWidth" to size,
        "borderColor" to PropSchema.Color,
        "borderRadius" to size,
        "backgroundColor" to PropSchema.Color,
        "opacity" to unit,
    ).apply { putAll(textStyle) }

    val a11y = linkedMapOf(
        "id" to PropSchema.Id,
        "ariaLabel" to PropSchema.Str(ProtocolLimits.MAX_ARIA_LABEL_CHARS),
        "hidden" to PropSchema.Bool,
    )
    val presentation = linkedMapOf<String, PropSchema>(
        "fill" to PropSchema.SvgPaint,
        "stroke" to PropSchema.SvgPaint,
        "strokeWidth" to size,
        "strokeLinecap" to PropSchema.EnumVal(setOf("butt", "round", "square")),
        "strokeLinejoin" to PropSchema.EnumVal(setOf("miter", "round", "bevel")),
        "opacity" to unit,
        "fillOpacity" to unit,
        "strokeOpacity" to unit,
        "fillRule" to PropSchema.EnumVal(setOf("nonzero", "evenodd")),
        "transform" to PropSchema.SvgTransform,
        "id" to PropSchema.Id,
    )
    val nothingContent = ContentPolicy(nodes = emptySet(), text = false, catalog = "none")
    val shapes = listOf("path", "circle", "rect")
    fun shape(extra: Map<String, PropSchema>, content: ContentPolicy = nothingContent) = ComponentSpec(
        svg = SvgPlacement.CHILD,
        props = LinkedHashMap<String, PropSchema>().apply {
            putAll(extra)
            putAll(presentation)
        },
        events = setOf("click"),
        content = content,
    )

    linkedMapOf(
        "div" to ComponentSpec(
            props = LinkedHashMap<String, PropSchema>().apply {
                put("style", PropSchema.Style(boxStyle))
                putAll(a11y)
            },
            events = setOf("click"),
        ),
        "span" to ComponentSpec(
            props = LinkedHashMap<String, PropSchema>().apply {
                put("style", PropSchema.Style(textStyle))
                putAll(a11y)
            },
            events = setOf("click"),
            content = ContentPolicy(nodes = setOf("span"), text = true, catalog = "none"),
        ),
        "svg" to ComponentSpec(
            svg = SvgPlacement.ROOT,
            props = LinkedHashMap<String, PropSchema>().apply {
                put("width", size)
                put("height", size)
                put("viewBox", PropSchema.ViewBox)
                put("preserveAspectRatio", PropSchema.EnumVal(setOf("none", "xMidYMid meet")))
                put("fill", PropSchema.SvgPaint)
                put("stroke", PropSchema.SvgPaint)
                put("strokeWidth", size)
                put("opacity", unit)
                putAll(a11y)
            },
            events = setOf("click"),
            content = ContentPolicy(nodes = setOf("g") + shapes + setOf("text", "linearGradient"), text = false, catalog = "svg"),
        ),
        "path" to shape(mapOf("d" to PropSchema.PathData(ProtocolLimits.MAX_PATH_CHARS))),
        "circle" to shape(linkedMapOf("cx" to coord, "cy" to coord, "r" to size)),
        "rect" to shape(
            linkedMapOf(
                "x" to coord,
                "y" to coord,
                "width" to size,
                "height" to size,
                "rx" to size,
                "ry" to size,
            ),
        ),
        "g" to shape(
            emptyMap(),
            content = ContentPolicy(nodes = setOf("g") + shapes + setOf("text"), text = false, catalog = "svg"),
        ),
        "text" to shape(
            linkedMapOf(
                "x" to coord,
                "y" to coord,
                "fontSize" to size,
                "fontWeight" to PropSchema.FontWeight,
                "textAnchor" to PropSchema.EnumVal(setOf("start", "middle", "end")),
            ),
            content = ContentPolicy(nodes = emptySet(), text = true, catalog = "none"),
        ),
        "linearGradient" to ComponentSpec(
            svg = SvgPlacement.CHILD,
            props = linkedMapOf(
                "id" to PropSchema.Id,
                "x1" to PropSchema.Coordinate,
                "y1" to PropSchema.Coordinate,
                "x2" to PropSchema.Coordinate,
                "y2" to PropSchema.Coordinate,
                "gradientUnits" to PropSchema.EnumVal(setOf("objectBoundingBox", "userSpaceOnUse")),
                "gradientTransform" to PropSchema.SvgTransform,
            ),
            content = ContentPolicy(nodes = setOf("stop"), text = false, catalog = "none"),
        ),
        "stop" to ComponentSpec(
            svg = SvgPlacement.CHILD,
            props = linkedMapOf(
                "offset" to PropSchema.Fraction,
                "stopColor" to PropSchema.Color,
                "stopOpacity" to unit,
            ),
            content = nothingContent,
        ),
    )
}

// ---- Exact integer & Unicode code-point helpers ----

private val EXACT_INTEGER_REGEX = Regex("^-?\\d+$")

internal fun exactSignedInt32OrNull(el: JsonElement?): Int? {
    val prim = el as? JsonPrimitive ?: return null
    if (prim.isString || !EXACT_INTEGER_REGEX.matches(prim.content)) return null
    val value = prim.longOrNull ?: return null
    return if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else null
}

internal fun exactSafeNonNegativeLongOrNull(el: JsonElement?): Long? {
    val prim = el as? JsonPrimitive ?: return null
    if (prim.isString || !EXACT_INTEGER_REGEX.matches(prim.content)) return null
    val value = prim.longOrNull ?: return null
    return if (value in 0L..MAX_JS_SAFE_INTEGER) value else null
}

internal fun exactSafeNonNegativeIntOrNull(el: JsonElement?): Int? {
    val v = exactSafeNonNegativeLongOrNull(el) ?: return null
    return if (v <= Int.MAX_VALUE.toLong()) v.toInt() else null
}

fun String.unicodeCodePointCount(): Int {
    var count = 0
    var i = 0
    while (i < length) {
        if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            i += 2
        } else {
            i += 1
        }
        count += 1
    }
    return count
}

fun String.takeUnicodeCodePoints(maxCodePoints: Int): String {
    if (maxCodePoints <= 0) return ""
    var count = 0
    var i = 0
    while (i < length && count < maxCodePoints) {
        if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            i += 2
        } else {
            i += 1
        }
        count += 1
    }
    return if (i >= length) this else substring(0, i)
}
