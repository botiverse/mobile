package build.raft.app.data.runtimeform.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Runtime form protocol v2 client parser, a port of slock
 * `packages/runtime-form/src/v2.ts` (`parseRuntimeFormV2`).
 *
 * v1 pinned every field, title and order per schemaVersion, so any server change
 * broke installed clients. v2 is rendered by field kind, never by field name, and
 * is tolerant by contract: unknown keys anywhere are ignored and an unknown field
 * kind becomes [RuntimeFormV2FieldKind.Unsupported] instead of rejecting the form.
 *
 * The body is walked as raw JSON rather than decoded through the generated
 * [RuntimeFormV2Definition]: a generated class fails on a value shape it does not
 * declare (for example `additionalProperties: false`), which would drop the form.
 */
const val RUNTIME_FORM_V2_PROTOCOL_VERSION: Int = 2

/** Field schema key naming the optionSources entry that supplies its choices. */
internal const val RUNTIME_FORM_V2_OPTION_SOURCE_KEY = "x-optionSource"

/** Field schema key: choices come from the option selected in another field. */
internal const val RUNTIME_FORM_V2_OPTIONS_FROM_KEY = "x-optionsFrom"

/**
 * Form capabilities this client implements. A form that lists anything else in
 * `requiredClientCapabilities` needs behaviour this client lacks (for example
 * typing a custom model), so it is treated as having no v2 form and v1 is used.
 */
internal val RUNTIME_FORM_V2_CLIENT_CAPABILITIES: Set<String> = emptySet()

internal enum class RuntimeFormV2FieldKind {
    Text,
    Secret,
    Url,
    Boolean,
    StringMap,
    Select,
    DependentSelect,
    DerivedSelect,
    Unsupported
}

internal enum class RuntimeFormV2SourceKind(val wire: String) {
    Select("select"),
    DependentSelect("dependent_select");

    companion object {
        fun fromWire(value: String?): RuntimeFormV2SourceKind? = entries.firstOrNull { it.wire == value }
    }
}

/** For a derived select: choices are `attribute` of the option selected in [key]. */
internal data class RuntimeFormV2DerivedFrom(
    val key: String,
    val attribute: String,
    val defaultAttribute: String? = null
)

/** Visible only while the field [key] holds one of [values]. */
internal data class RuntimeFormV2VisibleWhen(
    val key: String,
    val values: List<String>
)

internal data class RuntimeFormV2Field(
    val key: String,
    val kind: RuntimeFormV2FieldKind,
    val required: Boolean,
    val advanced: Boolean,
    val label: String,
    val hint: String? = null,
    val placeholder: String? = null,
    /** For select/dependent_select: the optionSources id to load choices from. */
    val optionSourceId: String? = null,
    /** For dependent_select: the field key whose value selects the option list. */
    val dependsOn: String? = null,
    val derivedFrom: RuntimeFormV2DerivedFrom? = null,
    /** Shown only when every rule matches; empty means always shown. */
    val visibleWhen: List<RuntimeFormV2VisibleWhen> = emptyList()
)

internal data class RuntimeFormV2OptionSourceKey(
    val sourceId: String,
    val kind: RuntimeFormV2SourceKind,
    val key: String,
    val dependsOn: String? = null
)

internal data class ParsedRuntimeFormV2(
    val runtimeId: String,
    val schemaVersion: String,
    val fields: List<RuntimeFormV2Field>,
    val optionSources: Map<String, RuntimeFormV2OptionSourceKey>,
    /**
     * Required fields this client cannot render. The form still shows, but the
     * client must not submit it and should ask the user to update.
     */
    val blockingFieldKeys: List<String>,
    /** Edit only: the agent's current values by field key (writeOnly fields absent). */
    val values: JsonObject? = null
)

private val runtimeFormV2Json = Json { ignoreUnknownKeys = true }

/** Parses a response body; null when it is not JSON or not a v2 form. */
internal fun parseRuntimeFormV2(body: String): ParsedRuntimeFormV2? =
    runCatching { runtimeFormV2Json.parseToJsonElement(body) }.getOrNull()?.let(::parseRuntimeFormV2)

/** Returns null only when the value is not a v2 form at all. */
internal fun parseRuntimeFormV2(value: JsonElement): ParsedRuntimeFormV2? {
    val root = value as? JsonObject ?: return null
    if (root["protocolVersion"].jsonNumberOrNull() != RUNTIME_FORM_V2_PROTOCOL_VERSION) return null
    if (!RUNTIME_FORM_V2_CLIENT_CAPABILITIES.containsAll(root["requiredClientCapabilities"].stringList())) return null
    val dataSchema = root["dataSchema"] as? JsonObject ?: return null
    val properties = dataSchema["properties"] as? JsonObject ?: return null

    val uiSchema = root["uiSchema"] as? JsonObject ?: JsonObject(emptyMap())
    val layout = uiSchema["layout"] as? JsonObject ?: JsonObject(emptyMap())
    val localization = uiSchema["localization"] as? JsonObject ?: JsonObject(emptyMap())
    val required = dataSchema["required"].stringList().toSet()
    val advanced = layout["advanced"].stringList().mapNotNull(::propertyKey).toSet()

    val optionSources = linkedMapOf<String, RuntimeFormV2OptionSourceKey>()
    (root["optionSources"] as? JsonObject)?.forEach { (sourceId, source) ->
        if (source !is JsonObject) return@forEach
        val key = propertyKey(source["pointer"].stringOrNull()) ?: return@forEach
        val kind = RuntimeFormV2SourceKind.fromWire(source["kind"].stringOrNull()) ?: return@forEach
        val dependsOn =
            if (kind == RuntimeFormV2SourceKind.DependentSelect) {
                propertyKey(source["dependsOn"].stringOrNull()) ?: return@forEach
            } else {
                null
            }
        optionSources[sourceId] = RuntimeFormV2OptionSourceKey(sourceId, kind, key, dependsOn)
    }

    val visibility = linkedMapOf<String, MutableList<RuntimeFormV2VisibleWhen>>()
    (uiSchema["visibility"] as? JsonArray)?.forEach { rule ->
        if (rule !is JsonObject) return@forEach
        val condition = rule["when"] as? JsonObject ?: return@forEach
        val key = propertyKey(rule["pointer"].stringOrNull()) ?: return@forEach
        val on = propertyKey(condition["pointer"].stringOrNull()) ?: return@forEach
        visibility.getOrPut(key) { mutableListOf() } += RuntimeFormV2VisibleWhen(on, condition["in"].stringList())
    }

    val declaredOrder = uiSchema["order"].stringList().filter { it in properties }
    val order = LinkedHashSet(declaredOrder + properties.keys)

    val fields = order.mapNotNull { key ->
        val schema = properties[key] as? JsonObject ?: return@mapNotNull null
        val copy = localization[key] as? JsonObject ?: JsonObject(emptyMap())
        val source = schema[RUNTIME_FORM_V2_OPTION_SOURCE_KEY].stringOrNull()?.let { optionSources[it] }
        val derived = derivedFrom(schema[RUNTIME_FORM_V2_OPTIONS_FROM_KEY], properties)
        RuntimeFormV2Field(
            key = key,
            kind = fieldKind(schema, source, derived),
            required = key in required,
            advanced = key in advanced,
            label = copy["label"].stringOrNull() ?: schema["title"].stringOrNull() ?: key,
            hint = copy["hint"].stringOrNull()?.takeIf { it.isNotEmpty() },
            placeholder = copy["placeholder"].stringOrNull()?.takeIf { it.isNotEmpty() },
            optionSourceId = source?.sourceId,
            dependsOn = source?.dependsOn,
            derivedFrom = derived,
            visibleWhen = visibility[key].orEmpty()
        )
    }

    return ParsedRuntimeFormV2(
        runtimeId = root["runtimeId"].stringOrNull() ?: "",
        schemaVersion = root["schemaVersion"].stringOrNull() ?: "",
        fields = fields,
        optionSources = optionSources,
        blockingFieldKeys = fields.filter { it.kind == RuntimeFormV2FieldKind.Unsupported && it.required }.map { it.key },
        values = root["values"] as? JsonObject
    )
}

private fun derivedFrom(value: JsonElement?, properties: JsonObject): RuntimeFormV2DerivedFrom? {
    val from = value as? JsonObject ?: return null
    val field = from["field"].stringOrNull() ?: return null
    val attribute = from["attribute"].stringOrNull() ?: return null
    if (field !in properties) return null
    return RuntimeFormV2DerivedFrom(field, attribute, from["defaultAttribute"].stringOrNull())
}

private fun fieldKind(
    schema: JsonObject,
    source: RuntimeFormV2OptionSourceKey?,
    derived: RuntimeFormV2DerivedFrom?
): RuntimeFormV2FieldKind =
    when (schema["type"].stringOrNull()) {
        "string" -> when {
            source?.kind == RuntimeFormV2SourceKind.Select -> RuntimeFormV2FieldKind.Select
            source?.kind == RuntimeFormV2SourceKind.DependentSelect -> RuntimeFormV2FieldKind.DependentSelect
            derived != null -> RuntimeFormV2FieldKind.DerivedSelect
            schema["writeOnly"].booleanOrNullStrict() == true -> RuntimeFormV2FieldKind.Secret
            schema["format"].stringOrNull() == "uri" -> RuntimeFormV2FieldKind.Url
            else -> RuntimeFormV2FieldKind.Text
        }
        "boolean" -> RuntimeFormV2FieldKind.Boolean
        "object" ->
            if ((schema["additionalProperties"] as? JsonObject)?.get("type").stringOrNull() == "string") {
                RuntimeFormV2FieldKind.StringMap
            } else {
                RuntimeFormV2FieldKind.Unsupported
            }
        else -> RuntimeFormV2FieldKind.Unsupported
    }

internal fun propertyKey(pointer: String?): String? {
    if (pointer == null || !pointer.startsWith("/")) return null
    val key = pointer.substring(1)
    return key.takeIf { it.isNotEmpty() && '/' !in it }
}

internal fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement?.stringList(): List<String> =
    (this as? JsonArray)?.mapNotNull { it.stringOrNull() }.orEmpty()

private fun JsonElement?.jsonNumberOrNull(): Int? =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

private fun JsonElement?.booleanOrNullStrict(): Boolean? =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
