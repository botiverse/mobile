package build.raft.app.data.runtimeform.v2

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Runtime form v2 client state, a port of slock `packages/runtime-form/src/v2State.ts`:
 * initial values, linkage when a field changes, visibility, choices, validation
 * and the submit body. UI-independent; the Compose renderer sits on top.
 *
 * Everything here is driven by the parsed form (field kinds, option sources,
 * visibility, derived choices). Nothing refers to a field or runtime by name.
 *
 * Values are JSON: a string, a boolean, an object of strings, JSON null, or, for
 * an unsupported field, whatever the server stored (carried back unchanged).
 */
internal typealias RuntimeFormV2Values = Map<String, JsonElement>

/** One choice of an option source; [attributes] is the whole wire object, read by derived selects. */
internal data class RuntimeFormV2Choice(
    val value: String,
    val label: String,
    val attributes: JsonObject = JsonObject(emptyMap())
)

/** Response of the option-sources endpoint, kept tolerant like the form itself. */
internal data class RuntimeFormV2Source(
    val sourceId: String,
    val kind: RuntimeFormV2SourceKind,
    val pointer: String,
    val dependsOn: String? = null,
    val options: List<RuntimeFormV2Choice>? = null,
    val defaultValue: String? = null,
    val optionsByValue: Map<String, List<RuntimeFormV2Choice>>? = null,
    val defaultValueByValue: Map<String, String>? = null,
    val customValueAllowedByValue: Map<String, Boolean>? = null
)

internal typealias RuntimeFormV2Sources = Map<String, RuntimeFormV2Source>

internal sealed interface RuntimeFormV2Choices {
    data class Select(val options: List<RuntimeFormV2Choice>, val allowEmpty: Boolean) : RuntimeFormV2Choices
    data object FreeText : RuntimeFormV2Choices
}

internal enum class RuntimeFormV2ErrorCode {
    Required,
    InvalidUrl,
    NotAChoice,
    UnsupportedRequired
}

/** Parses an option-sources response; null when it is not a usable source. */
internal fun parseRuntimeFormV2Source(value: JsonElement): RuntimeFormV2Source? {
    val root = value as? JsonObject ?: return null
    val kind = RuntimeFormV2SourceKind.fromWire(root["kind"].stringOrNull()) ?: return null
    val choices: (JsonElement?) -> List<RuntimeFormV2Choice>? = { list ->
        (list as? JsonArray)?.mapNotNull { option ->
            val obj = option as? JsonObject ?: return@mapNotNull null
            val optionValue = obj["value"].stringOrNull() ?: return@mapNotNull null
            RuntimeFormV2Choice(optionValue, obj["label"].stringOrNull() ?: optionValue, obj)
        }
    }
    return RuntimeFormV2Source(
        sourceId = root["sourceId"].stringOrNull() ?: return null,
        kind = kind,
        pointer = root["pointer"].stringOrNull() ?: return null,
        dependsOn = root["dependsOn"].stringOrNull(),
        options = choices(root["options"]),
        defaultValue = root["defaultValue"].stringOrNull(),
        optionsByValue = (root["optionsByValue"] as? JsonObject)?.mapNotNull { (parent, list) ->
            choices(list)?.let { parent to it }
        }?.toMap(),
        defaultValueByValue = (root["defaultValueByValue"] as? JsonObject)?.mapNotNull { (parent, default) ->
            default.stringOrNull()?.let { parent to it }
        }?.toMap(),
        customValueAllowedByValue = (root["customValueAllowedByValue"] as? JsonObject)?.mapNotNull { (parent, allowed) ->
            (allowed as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull?.let { parent to it }
        }?.toMap()
    )
}

internal fun RuntimeFormV2Values.text(key: String?): String = this[key ?: ""].stringOrNull() ?: ""

/** Choices the field offers under the current values; null for fields without choices. */
internal fun runtimeFormV2Choices(
    field: RuntimeFormV2Field,
    sources: RuntimeFormV2Sources,
    values: RuntimeFormV2Values
): RuntimeFormV2Choices? {
    val source = field.optionSourceId?.let { sources[it] }
    return when (field.kind) {
        RuntimeFormV2FieldKind.Select -> RuntimeFormV2Choices.Select(source?.options.orEmpty(), allowEmpty = false)
        RuntimeFormV2FieldKind.DependentSelect -> {
            val parent = values.text(field.dependsOn)
            if (source?.customValueAllowedByValue?.get(parent) == true) {
                RuntimeFormV2Choices.FreeText
            } else {
                RuntimeFormV2Choices.Select(source?.optionsByValue?.get(parent).orEmpty(), allowEmpty = false)
            }
        }
        RuntimeFormV2FieldKind.DerivedSelect -> {
            val from = field.derivedFrom ?: return null
            val selected = selectedOption(from.key, sources, values)
            val options = (selected?.attributes?.get(from.attribute) as? JsonArray)
                ?.mapNotNull { it.stringOrNull() }
                ?.map { RuntimeFormV2Choice(it, it) }
                .orEmpty()
            // An empty value means "the runtime's default"; it is always a valid choice.
            RuntimeFormV2Choices.Select(options, allowEmpty = true)
        }
        else -> null
    }
}

private fun selectedOption(
    key: String,
    sources: RuntimeFormV2Sources,
    values: RuntimeFormV2Values
): RuntimeFormV2Choice? {
    val value = values.text(key)
    for (source in sources.values) {
        if (source.pointer != "/$key") continue
        source.options?.let { options -> return options.firstOrNull { it.value == value } }
        val parent = source.dependsOn?.removePrefix("/") ?: ""
        return source.optionsByValue?.get(values.text(parent))?.firstOrNull { it.value == value }
    }
    return null
}

private fun defaultFor(
    field: RuntimeFormV2Field,
    sources: RuntimeFormV2Sources,
    values: RuntimeFormV2Values
): JsonElement {
    val source = field.optionSourceId?.let { sources[it] }
    return when (field.kind) {
        RuntimeFormV2FieldKind.Boolean -> JsonPrimitive(false)
        RuntimeFormV2FieldKind.StringMap -> JsonObject(emptyMap())
        RuntimeFormV2FieldKind.Select ->
            JsonPrimitive(source?.defaultValue ?: source?.options?.firstOrNull()?.value ?: "")
        RuntimeFormV2FieldKind.DependentSelect -> {
            val parent = values.text(field.dependsOn)
            JsonPrimitive(
                if (source?.customValueAllowedByValue?.get(parent) == true) ""
                else source?.defaultValueByValue?.get(parent) ?: ""
            )
        }
        RuntimeFormV2FieldKind.DerivedSelect -> {
            val from = field.derivedFrom
            val selected = from?.let { selectedOption(it.key, sources, values) }
            val fallback = from?.defaultAttribute?.let { selected?.attributes?.get(it) }.stringOrNull()
            JsonPrimitive(fallback ?: "")
        }
        RuntimeFormV2FieldKind.Unsupported -> JsonNull
        else -> JsonPrimitive("")
    }
}

/**
 * Starting values: for edit, the agent's stored values (form.values) where they
 * fit the field's kind; otherwise each field's default. Parents come before
 * their dependents so defaults follow the stored parent. writeOnly fields are
 * never stored in form.values, so they start blank, which on edit means "keep".
 */
internal fun initialRuntimeFormV2Values(form: ParsedRuntimeFormV2, sources: RuntimeFormV2Sources): RuntimeFormV2Values {
    val values = linkedMapOf<String, JsonElement>()
    for (field in dependencyOrder(form.fields)) {
        val stored = form.values?.get(field.key)
        values[field.key] = if (storedFits(field, stored)) stored!! else defaultFor(field, sources, values)
    }
    return values
}

private fun storedFits(field: RuntimeFormV2Field, stored: JsonElement?): Boolean {
    if (stored == null) return false
    return when (field.kind) {
        RuntimeFormV2FieldKind.Boolean -> stored is JsonPrimitive && !stored.isString && stored.booleanOrNull != null
        RuntimeFormV2FieldKind.StringMap -> stored is JsonObject && stored.values.all { it.stringOrNull() != null }
        RuntimeFormV2FieldKind.Unsupported -> true
        else -> stored.stringOrNull() != null
    }
}

/**
 * Set one field and cascade: fields whose choices depend on it are reset to
 * their defaults, recursively, so a stale model can never survive a provider
 * change.
 */
/**
 * After [sourceId] was re-probed: a select that now holds a value the source no longer offers is
 * reset to its default, and fields depending on it follow — as v1 does after re-probing models.
 * Free-text (custom-value) choices and empty values are left alone.
 */
internal fun runtimeFormV2ValuesAfterSourceReload(
    form: ParsedRuntimeFormV2,
    sources: RuntimeFormV2Sources,
    values: RuntimeFormV2Values,
    sourceId: String
): RuntimeFormV2Values {
    var next = values
    for (field in dependencyOrder(form.fields)) {
        if (field.optionSourceId != sourceId) continue
        val current = next.text(field.key)
        if (current.isEmpty()) continue
        val choices = runtimeFormV2Choices(field, sources, next) as? RuntimeFormV2Choices.Select ?: continue
        if (choices.options.any { it.value == current }) continue
        next = applyRuntimeFormV2Change(form, sources, next, field.key, defaultFor(field, sources, next))
    }
    return next
}

internal fun applyRuntimeFormV2Change(
    form: ParsedRuntimeFormV2,
    sources: RuntimeFormV2Sources,
    values: RuntimeFormV2Values,
    key: String,
    value: JsonElement
): RuntimeFormV2Values {
    val next = LinkedHashMap(values).apply { put(key, value) }
    val changed = mutableSetOf(key)
    for (field in dependencyOrder(form.fields)) {
        val parent = field.dependsOn ?: field.derivedFrom?.key
        if (parent != null && parent in changed) {
            next[field.key] = defaultFor(field, sources, next)
            changed += field.key
        }
    }
    return next
}

internal fun isRuntimeFormV2FieldVisible(field: RuntimeFormV2Field, values: RuntimeFormV2Values): Boolean =
    field.visibleWhen.all { rule -> values.text(rule.key) in rule.values }

private val textLikeKinds = setOf(
    RuntimeFormV2FieldKind.Text,
    RuntimeFormV2FieldKind.Secret,
    RuntimeFormV2FieldKind.Url,
    RuntimeFormV2FieldKind.Select,
    RuntimeFormV2FieldKind.DependentSelect
)
private val httpUrl = Regex("^https?://", RegexOption.IGNORE_CASE)

/** Errors by field key for visible fields; an empty map means the form can be submitted. */
internal fun validateRuntimeFormV2(
    form: ParsedRuntimeFormV2,
    sources: RuntimeFormV2Sources,
    values: RuntimeFormV2Values,
    editing: Boolean = false
): Map<String, RuntimeFormV2ErrorCode> {
    val errors = linkedMapOf<String, RuntimeFormV2ErrorCode>()
    for (field in form.fields) {
        if (!isRuntimeFormV2FieldVisible(field, values)) continue
        if (field.kind == RuntimeFormV2FieldKind.Unsupported) {
            if (field.required) errors[field.key] = RuntimeFormV2ErrorCode.UnsupportedRequired
            continue
        }
        val text = values.text(field.key).trim()
        // On edit a blank secret (writeOnly) field keeps the stored value.
        val blankAllowed = editing && field.kind == RuntimeFormV2FieldKind.Secret
        if (field.required && !blankAllowed && field.kind in textLikeKinds && text.isEmpty()) {
            errors[field.key] = RuntimeFormV2ErrorCode.Required
            continue
        }
        if (field.kind == RuntimeFormV2FieldKind.Url && text.isNotEmpty() && !httpUrl.containsMatchIn(text)) {
            errors[field.key] = RuntimeFormV2ErrorCode.InvalidUrl
        }
        val choices = runtimeFormV2Choices(field, sources, values)
        if (choices is RuntimeFormV2Choices.Select && text.isNotEmpty() && choices.options.none { it.value == text }) {
            errors[field.key] = RuntimeFormV2ErrorCode.NotAChoice
        }
    }
    return errors
}

/**
 * The `formValues` body: every visible field, plus the stored value of any
 * field this client could not render so it survives unchanged.
 */
internal fun runtimeFormV2Submission(form: ParsedRuntimeFormV2, values: RuntimeFormV2Values): JsonObject {
    val out = linkedMapOf<String, JsonElement>()
    for (field in form.fields) {
        if (field.kind != RuntimeFormV2FieldKind.Unsupported && !isRuntimeFormV2FieldVisible(field, values)) continue
        val value = values[field.key] ?: continue
        val text = value.stringOrNull()
        out[field.key] =
            if (text == null) {
                value
            } else {
                text.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive)
                    ?: if (field.kind == RuntimeFormV2FieldKind.DerivedSelect) JsonNull else JsonPrimitive("")
            }
    }
    return JsonObject(out)
}

/** A server error pointer `/formValues/<field>` → the field key, else null. */
internal fun runtimeFormV2FieldForPointer(pointer: String?): String? {
    val prefix = "/formValues/"
    if (pointer == null || !pointer.startsWith(prefix)) return null
    return pointer.removePrefix(prefix).substringBefore('/').takeIf { it.isNotEmpty() }
}

internal fun JsonElement?.booleanValue(): Boolean =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: false

internal fun JsonElement?.stringMapValue(): Map<String, String> =
    (this as? JsonObject)?.mapNotNull { (key, value) -> value.stringOrNull()?.let { key to it } }?.toMap().orEmpty()

private fun dependencyOrder(fields: List<RuntimeFormV2Field>): List<RuntimeFormV2Field> {
    val byKey = fields.associateBy { it.key }
    val ordered = mutableListOf<RuntimeFormV2Field>()
    val seen = mutableSetOf<String>()
    fun visit(field: RuntimeFormV2Field, depth: Int) {
        if (field.key in seen || depth > fields.size) return
        val parent = field.dependsOn ?: field.derivedFrom?.key
        parent?.let(byKey::get)?.let { visit(it, depth + 1) }
        if (seen.add(field.key)) ordered += field
    }
    fields.forEach { visit(it, 0) }
    return ordered
}
