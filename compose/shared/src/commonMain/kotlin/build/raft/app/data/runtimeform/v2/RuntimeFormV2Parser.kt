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

/** Field schema key (`provider.saved_connection`): a selected saved connection owns this field's value. */
internal const val RUNTIME_FORM_V2_CONNECTION_OWNED_KEY = "x-connectionOwned"

/**
 * Form capabilities this client implements. A form that lists anything else in
 * `requiredClientCapabilities` needs behaviour this client lacks (for example
 * typing a custom model), so the form is refused (`runtime_form_v2_contract_mismatch`) and
 * cannot be created or saved; there is no v1 form to use instead.
 */
internal val RUNTIME_FORM_V2_CLIENT_CAPABILITIES: Set<String> = setOf(
    // FieldCopy.choices: display label/description per option value.
    RUNTIME_FORM_V2_CAPABILITY_CHOICE_LABELS,
    // OptionSource.customValueAllowed: a select that also accepts a typed value.
    RUNTIME_FORM_V2_CAPABILITY_CUSTOM_VALUE,
    // OptionSource.status/reason/retryable: a source that is not live says why, with a retry.
    RUNTIME_FORM_V2_CAPABILITY_SOURCE_STATUS,
    // Option.connectionId/group/unavailable, x-connectionOwned fields and the formRevision echo.
    RUNTIME_FORM_V2_CAPABILITY_SAVED_CONNECTION,
)

internal const val RUNTIME_FORM_V2_CAPABILITY_CHOICE_LABELS: String = "choice.labels"
internal const val RUNTIME_FORM_V2_CAPABILITY_CUSTOM_VALUE: String = "select.custom_value"
internal const val RUNTIME_FORM_V2_CAPABILITY_SOURCE_STATUS: String = "option_source.status"
internal const val RUNTIME_FORM_V2_CAPABILITY_SAVED_CONNECTION: String = "provider.saved_connection"

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
    val visibleWhen: List<RuntimeFormV2VisibleWhen> = emptyList(),
    /** `choice.labels`: display copy for this field's choices, keyed by option value. */
    val choices: Map<String, RuntimeFormV2ChoiceCopy> = emptyMap(),
    /**
     * `x-connectionOwned` (`provider.saved_connection`): while a saved connection is selected this
     * field is disabled, not required and kept out of formValues; see [runtimeFormV2ConnectionActive].
     */
    val connectionOwned: Boolean = false
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
    val values: JsonObject? = null,
    /**
     * Definition.revision: opaque token for this exact form build, echoed on every option-source
     * request as `?formRevision=`; a 409 `form_revision_stale` means the whole form must be refetched.
     */
    val revision: String? = null,
    /**
     * Edit only (`writeOnlyKeepRequiresUnchanged`), by field key: a blank secret keeps the stored
     * value only while every listed (visible) field still equals its stored value. Malformed entries,
     * non-secret keys and bound keys that are not fields of this form are dropped (slock v2.ts
     * `keepRequiresUnchanged`). Empty when the form does not carry it.
     */
    val writeOnlyKeepRequiresUnchanged: Map<String, List<String>> = emptyMap()
)

private val runtimeFormV2Json = Json { ignoreUnknownKeys = true }

/** Parses a response body; null when it is not JSON or not a v2 form. */
internal fun parseRuntimeFormV2(body: String): ParsedRuntimeFormV2? =
    runCatching { runtimeFormV2Json.parseToJsonElement(body) }.getOrNull()?.let(::parseRuntimeFormV2)

/** Returns null only when the value is not a v2 form at all. */
internal fun parseRuntimeFormV2(value: JsonElement): ParsedRuntimeFormV2? {
    val root = value as? JsonObject ?: return null
    if (root["protocolVersion"].jsonNumberOrNull() != RUNTIME_FORM_V2_PROTOCOL_VERSION) return null
    if (!clientMeetsRequiredCapabilities(root["requiredClientCapabilities"])) return null
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
            visibleWhen = visibility[key].orEmpty(),
            choices = choiceCopy(copy["choices"]),
            connectionOwned = schema[RUNTIME_FORM_V2_CONNECTION_OWNED_KEY].booleanOrNullStrict() == true
        )
    }

    return ParsedRuntimeFormV2(
        runtimeId = root["runtimeId"].stringOrNull() ?: "",
        schemaVersion = root["schemaVersion"].stringOrNull() ?: "",
        fields = fields,
        optionSources = optionSources,
        blockingFieldKeys = fields.filter { it.kind == RuntimeFormV2FieldKind.Unsupported && it.required }.map { it.key },
        values = root["values"] as? JsonObject,
        revision = root["revision"].stringOrNull()?.takeIf { it.isNotEmpty() },
        writeOnlyKeepRequiresUnchanged = keepRequiresUnchanged(root["writeOnlyKeepRequiresUnchanged"], fields)
    )
}

/** `writeOnlyKeepRequiresUnchanged` by field key; anything malformed is ignored (tolerant, like slock v2.ts). */
private fun keepRequiresUnchanged(value: JsonElement?, fields: List<RuntimeFormV2Field>): Map<String, List<String>> {
    val entries = value as? JsonObject ?: return emptyMap()
    val kinds = fields.associate { it.key to it.kind }
    return entries.mapNotNull { (pointer, bound) ->
        val key = propertyKey(pointer) ?: return@mapNotNull null
        if (kinds[key] != RuntimeFormV2FieldKind.Secret || bound !is JsonArray) return@mapNotNull null
        val keys = bound.mapNotNull { propertyKey(it.stringOrNull()) }.filter { it in kinds && it != key }
        keys.takeIf { it.isNotEmpty() }?.let { key to it }
    }.toMap()
}

/**
 * A safety gate, so it fails closed: absent means nothing is required; present but
 * not an array of strings counts as an unknown requirement (the form cannot be used).
 */
private fun clientMeetsRequiredCapabilities(value: JsonElement?): Boolean {
    if (value == null || value is kotlinx.serialization.json.JsonNull) return true
    val required = value as? JsonArray ?: return false
    return required.all { item -> item.stringOrNull()?.let { it in RUNTIME_FORM_V2_CLIENT_CAPABILITIES } == true }
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

/** FieldCopy.choices, keeping only entries with a string label; unknown keys inside an entry are ignored. */
private fun choiceCopy(value: JsonElement?): Map<String, RuntimeFormV2ChoiceCopy> {
    val entries = value as? JsonObject ?: return emptyMap()
    return entries.mapNotNull { (optionValue, entry) ->
        val copy = entry as? JsonObject ?: return@mapNotNull null
        val label = (copy["label"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
        val description = (copy["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        optionValue to RuntimeFormV2ChoiceCopy(label, description)
    }.toMap()
}
