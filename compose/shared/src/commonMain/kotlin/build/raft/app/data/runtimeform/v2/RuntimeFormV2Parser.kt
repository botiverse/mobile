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
 * Protocol v3 (slock README "Protocol v3", requested with `runtime_form.v3`) is read by the same parser:
 * `uiSchema.rules` and `dataSchema.allOf` (see RuntimeFormV3Rules.kt) instead of `visibility` and
 * `x-connectionOwned`, a reasoning effort as an `x-optionSource` dependent_select instead of
 * `x-optionsFrom`, and unavailable choices only in `OptionSource.unavailableOptions`.
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
    val connectionOwned: Boolean = false,
    /** v3 `uiSchema.rules` entry for this field (SHOW/HIDE on a condition); null on v2. */
    val rule: RuntimeFormV3Rule? = null,
    /** v3 `dataSchema.allOf`: required while a condition holds (or fails), besides [required]. */
    val requiredWhen: List<RuntimeFormV3RequiredWhen> = emptyList()
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
    val writeOnlyKeepRequiresUnchanged: Map<String, List<String>> = emptyMap(),
    /**
     * The wire protocolVersion: 2, or 3 when the request advertised `runtime_form.v3` (README "Protocol
     * v3"). A submit echoes it in `formDefinitionRef`; formValues mean the same on both.
     */
    val protocolVersion: Int = RUNTIME_FORM_V2_PROTOCOL_VERSION
)

private val runtimeFormV2Json = Json { ignoreUnknownKeys = true }

/** Parses a response body; null when it is not JSON or not a v2 form. */
internal fun parseRuntimeFormV2(body: String): ParsedRuntimeFormV2? =
    runCatching { runtimeFormV2Json.parseToJsonElement(body) }.getOrNull()?.let(::parseRuntimeFormV2)

/**
 * Returns null when the value is not a v2/v3 form at all, or one this client must not render: a required
 * client capability it lacks, or (v3) a rule or conditional required it cannot evaluate (an unknown
 * effect, scope or condition keyword, or a second rule for one field). A null form is
 * `runtime_form_v2_contract_mismatch`; it is never rendered with a rule silently ignored.
 */
internal fun parseRuntimeFormV2(value: JsonElement): ParsedRuntimeFormV2? {
    val root = value as? JsonObject ?: return null
    val protocolVersion = root["protocolVersion"].jsonNumberOrNull()
    if (protocolVersion != RUNTIME_FORM_V2_PROTOCOL_VERSION && protocolVersion != RUNTIME_FORM_V3_PROTOCOL_VERSION) return null
    val v3 = protocolVersion == RUNTIME_FORM_V3_PROTOCOL_VERSION
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

    // v3 rules and conditional required; anything this client cannot evaluate refuses the form.
    val rules = linkedMapOf<String, RuntimeFormV3Rule>()
    val requiredWhen = linkedMapOf<String, MutableList<RuntimeFormV3RequiredWhen>>()
    if (v3) {
        (uiSchema["rules"] as? JsonArray)?.forEach { rule ->
            val parsed = runtimeFormV3Rule(rule) ?: return null
            if (rules.put(parsed.first, parsed.second) != null) return null
        }
        (dataSchema["allOf"] as? JsonArray)?.forEach { entry ->
            val clauses = entry as? JsonObject ?: return null
            val condition = clauses["if"] as? JsonObject
            if (!isSupportedRuntimeFormV3Schema(condition)) return null
            for ((branch, whenResult) in listOf("then" to true, "else" to false)) {
                val clause = clauses[branch] ?: continue
                val keys = ((clause as? JsonObject)?.get("required") as? JsonArray) ?: return null
                keys.stringList().forEach { key ->
                    requiredWhen.getOrPut(key) { mutableListOf() } += RuntimeFormV3RequiredWhen(condition!!, whenResult)
                }
            }
        }
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
            connectionOwned = schema[RUNTIME_FORM_V2_CONNECTION_OWNED_KEY].booleanOrNullStrict() == true,
            rule = rules[key],
            requiredWhen = requiredWhen[key].orEmpty()
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
        writeOnlyKeepRequiresUnchanged = keepRequiresUnchanged(root["writeOnlyKeepRequiresUnchanged"], fields),
        protocolVersion = if (v3) RUNTIME_FORM_V3_PROTOCOL_VERSION else RUNTIME_FORM_V2_PROTOCOL_VERSION
    )
}

/** One v3 `uiSchema.rules` entry as (field key, rule); null when any part of it is unsupported. */
private fun runtimeFormV3Rule(value: JsonElement): Pair<String, RuntimeFormV3Rule>? {
    val rule = value as? JsonObject ?: return null
    val key = runtimeFormV3Scope(rule["scope"].stringOrNull())?.key ?: return null
    val effect = when (rule["effect"].stringOrNull()) {
        "SHOW" -> RuntimeFormV3Effect.Show
        "HIDE" -> RuntimeFormV3Effect.Hide
        else -> return null
    }
    val condition = rule["condition"] as? JsonObject ?: return null
    val scope = runtimeFormV3Scope(condition["scope"].stringOrNull()) ?: return null
    val schema = condition["schema"] as? JsonObject
    if (!isSupportedRuntimeFormV3Schema(schema)) return null
    return key to RuntimeFormV3Rule(effect, RuntimeFormV3Condition(scope, schema!!))
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
