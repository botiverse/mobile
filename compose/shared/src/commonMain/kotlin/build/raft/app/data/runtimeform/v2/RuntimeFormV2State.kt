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
    val attributes: JsonObject = JsonObject(emptyMap()),
    /** `choice.labels`: only ever from FieldCopy.choices, never from the option itself. */
    val description: String? = null
) {
    /** `provider.saved_connection`: non-null marks a saved provider connection (value is `connection:<id>`). */
    val connectionId: String? get() = attributes["connectionId"].stringOrNull()?.takeIf { it.isNotEmpty() }

    /** `provider.saved_connection`: the connection's provider id; visibility rules see it too. */
    val underlyingProviderId: String? get() = attributes["underlyingProviderId"].stringOrNull()?.takeIf { it.isNotEmpty() }

    /** `provider.saved_connection`: `saved` or `direct`; null when the source does not group. */
    val group: String? get() = attributes["group"].stringOrNull()

    /** `provider.saved_connection` (edit): the bound connection cannot be selected right now; it blocks the save. */
    val unavailable: Boolean get() = attributes["unavailable"].booleanValue()
}

/**
 * Label priority (`choice.labels`): FieldCopy.choices[value].label, else the option's own label,
 * else the raw value. The description comes only from FieldCopy.choices.
 * Also applied to dependent_select on purpose: the frozen contract names select and derived_select,
 * but the web reference (slock v2State `labelled`) labels dependent_select options too, and a form
 * without choices for it renders exactly as before. Keep both clients aligned.
 */
private fun RuntimeFormV2Choice.labelledFor(field: RuntimeFormV2Field): RuntimeFormV2Choice {
    val text = field.choices[value]
    return copy(
        label = text?.label?.takeIf { it.isNotEmpty() } ?: label.takeIf { it.isNotEmpty() } ?: value,
        description = text?.description?.takeIf { it.isNotEmpty() }
    )
}

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
    val customValueAllowedByValue: Map<String, Boolean>? = null,
    /** `select.custom_value` (kind select only): a combobox; any typed value is valid besides the listed ones. */
    val customValueAllowed: Boolean? = null,
    /** `option_source.status`: live, fallback or unavailable, as sent (see [runtimeFormV2SourceStatus]). */
    val status: String? = null,
    /** Why the source is not live; may be a reason this client does not know. */
    val reason: String? = null,
    val retryable: Boolean? = null,
    /**
     * `OptionSource.unavailableOptions`: options that exist but cannot be picked now (models the
     * Computer's runtime listed as disabled; on v3 also saved connections that are not selectable).
     * Never in [options]; drawn disabled after the list. See [runtimeFormV2UnavailableSelection].
     */
    val unavailableOptions: List<RuntimeFormV2UnavailableChoice> = emptyList()
)

/**
 * One `OptionSource.unavailableOptions` entry. [reason] is plain text (runtime text for a model,
 * server copy in the request language for a saved connection); [connectionId] is set on a saved
 * provider connection (v3); [group] is `saved` / `direct` when the server groups it.
 */
internal data class RuntimeFormV2UnavailableChoice(
    val value: String,
    val label: String,
    val reason: String? = null,
    val connectionId: String? = null,
    val group: String? = null
)

/** A source that is not live (`option_source.status`). */
internal data class RuntimeFormV2SourceStatus(
    val unavailable: Boolean,
    val reason: String?,
    val retryable: Boolean
)

internal typealias RuntimeFormV2Sources = Map<String, RuntimeFormV2Source>

internal sealed interface RuntimeFormV2Choices {
    /** [allowCustom] (`select.custom_value`): pick a listed option or type any value; a stored unlisted value is shown as typed. */
    data class Select(
        val options: List<RuntimeFormV2Choice>,
        val allowEmpty: Boolean,
        val allowCustom: Boolean = false
    ) : RuntimeFormV2Choices
    data object FreeText : RuntimeFormV2Choices
}

internal enum class RuntimeFormV2ErrorCode {
    Required,
    InvalidUrl,
    NotAChoice,
    UnsupportedRequired,
    /** A required field whose option source is unavailable (`option_source.status`): retry, or wait. */
    SourceUnavailable,
    /**
     * The selected saved provider connection is marked `unavailable` (`provider.saved_connection`):
     * the stored binding stays visible but cannot be saved over; pick something else.
     */
    ConnectionUnavailable,
    /**
     * Edit: a blank secret that cannot keep the stored value because a field it is bound to changed
     * (`writeOnlyKeepRequiresUnchanged`); a new one is needed. Mirrors slock v2State `secret_reentry_required`.
     */
    SecretReentryRequired,
    /**
     * The current value is an `OptionSource.unavailableOptions` entry (v3: e.g. a bound saved
     * connection that is disabled) on a source that takes no typed value: shown
     * selected-and-unavailable; pick something else to save. Mirrors slock v2State `option_unavailable`.
     */
    OptionUnavailable
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
        }?.toMap(),
        customValueAllowed = (root["customValueAllowed"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull,
        status = root["status"].stringOrNull(),
        reason = root["reason"].stringOrNull()?.takeIf { it.isNotEmpty() },
        retryable = (root["retryable"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull,
        unavailableOptions = (root["unavailableOptions"] as? JsonArray)?.mapNotNull { option ->
            val obj = option as? JsonObject ?: return@mapNotNull null
            val optionValue = obj["value"].stringOrNull() ?: return@mapNotNull null
            RuntimeFormV2UnavailableChoice(
                value = optionValue,
                label = obj["label"].stringOrNull()?.takeIf { it.isNotEmpty() } ?: optionValue,
                reason = obj["reason"].stringOrNull()?.takeIf { it.isNotBlank() },
                connectionId = obj["connectionId"].stringOrNull()?.takeIf { it.isNotEmpty() },
                group = obj["group"].stringOrNull()
            )
        }.orEmpty()
    )
}

/**
 * The unavailable entries a field's list draws after its options (`OptionSource.unavailableOptions`);
 * a derived select follows its parent and draws none.
 */
internal fun runtimeFormV2UnavailableChoices(
    field: RuntimeFormV2Field,
    sources: RuntimeFormV2Sources
): List<RuntimeFormV2UnavailableChoice> =
    if (field.kind == RuntimeFormV2FieldKind.DerivedSelect) emptyList()
    else field.optionSourceId?.let { sources[it] }?.unavailableOptions.orEmpty()

/**
 * The option sources whose lists are keyed by [sourceId]'s field (a dependent_select's `dependsOn`), in
 * form order: re-requested once their parent answers a refresh, so a v3 reasoning effort follows the
 * re-probed model list. A source never follows itself.
 */
internal fun runtimeFormV2DependentSourceIds(form: ParsedRuntimeFormV2, sourceId: String): List<String> {
    val key = form.optionSources[sourceId]?.key ?: return emptyList()
    return form.optionSources.values.filter { it.dependsOn == key && it.sourceId != sourceId }.map { it.sourceId }
}

/** The `unavailableOptions` entry the field's current value names, if any (slock v2State). */
internal fun runtimeFormV2UnavailableSelection(
    field: RuntimeFormV2Field,
    sources: RuntimeFormV2Sources,
    values: RuntimeFormV2Values
): RuntimeFormV2UnavailableChoice? {
    val value = values.text(field.key).trim()
    if (value.isEmpty()) return null
    return field.optionSourceId?.let { sources[it] }?.unavailableOptions?.firstOrNull { it.value == value }
}

/**
 * Whether the field is required under the current values: the unconditional `required`, or (v3) a
 * `dataSchema.allOf` entry whose condition gives the branch that lists it. Mirrors slock v2State.
 */
internal fun isRuntimeFormV2FieldRequired(field: RuntimeFormV2Field, values: RuntimeFormV2Values): Boolean =
    field.required || field.requiredWhen.any { evaluateRuntimeFormV3Schema(it.condition, JsonObject(values)) == it.whenResult }

internal fun RuntimeFormV2Values.text(key: String?): String = this[key ?: ""].stringOrNull() ?: ""

/** Choices the field offers under the current values; null for fields without choices. */
internal fun runtimeFormV2Choices(
    field: RuntimeFormV2Field,
    sources: RuntimeFormV2Sources,
    values: RuntimeFormV2Values
): RuntimeFormV2Choices? {
    val source = field.optionSourceId?.let { sources[it] }
    return when (field.kind) {
        RuntimeFormV2FieldKind.Select ->
            RuntimeFormV2Choices.Select(
                source?.options.orEmpty().map { it.labelledFor(field) },
                allowEmpty = false,
                allowCustom = source?.customValueAllowed == true
            )
        RuntimeFormV2FieldKind.DependentSelect -> {
            val parent = values.text(field.dependsOn)
            val options = source?.optionsByValue?.get(parent).orEmpty().map { it.labelledFor(field) }
            val allowCustom = source?.customValueAllowedByValue?.get(parent) == true
            // A list that also accepts a typed value (e.g. Built-in Pi + OpenRouter, slock #13950) is a
            // searchable combobox; only a custom-only parent with no list is a plain text input.
            if (allowCustom && options.isEmpty()) {
                RuntimeFormV2Choices.FreeText
            } else {
                RuntimeFormV2Choices.Select(options, allowEmpty = false, allowCustom = allowCustom)
            }
        }
        RuntimeFormV2FieldKind.DerivedSelect -> {
            val from = field.derivedFrom ?: return null
            val selected = selectedOption(from.key, sources, values)
            val options = (selected?.attributes?.get(from.attribute) as? JsonArray)
                ?.mapNotNull { it.stringOrNull() }
                ?.map { RuntimeFormV2Choice(it, it).labelledFor(field) }
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

/**
 * The selected option of a select field when it is a saved provider connection
 * (`provider.saved_connection`: Option.connectionId non-empty); null for any other selection or field.
 */
internal fun runtimeFormV2SelectedConnection(
    field: RuntimeFormV2Field,
    sources: RuntimeFormV2Sources,
    values: RuntimeFormV2Values
): RuntimeFormV2Choice? =
    if (field.kind != RuntimeFormV2FieldKind.Select) null
    else selectedOption(field.key, sources, values)?.takeIf { it.connectionId != null }

/** True while any field holds a saved-connection selection: connectionOwned fields are then the connection's. */
internal fun runtimeFormV2ConnectionActive(
    form: ParsedRuntimeFormV2,
    sources: RuntimeFormV2Sources,
    values: RuntimeFormV2Values
): Boolean = form.fields.any { runtimeFormV2SelectedConnection(it, sources, values) != null }

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
            // A custom-allowed parent with a list (OpenRouter) keeps the server's default; a gateway
            // with no list has none and starts empty.
            val options = source?.optionsByValue?.get(parent).orEmpty()
            JsonPrimitive(
                if (source?.customValueAllowedByValue?.get(parent) == true && options.isEmpty()) ""
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
 *
 * Edit: an optional dependent_select or derived_select (a reasoning effort)
 * with no stored string (null or absent: the agent uses the runtime default)
 * starts at "" (Default), never at the parent's default
 * (`defaultValueByValue` / `defaultAttribute`): saving untouched must not turn
 * Default into the model's default effort. The parent's default applies on
 * create, and on edit only once the parent changes ([applyRuntimeFormV2Change]).
 * Only while the parent itself is stored: see [parentStored]. (slock #14569)
 */
internal fun initialRuntimeFormV2Values(form: ParsedRuntimeFormV2, sources: RuntimeFormV2Sources): RuntimeFormV2Values {
    val values = linkedMapOf<String, JsonElement>()
    val editing = form.values != null
    for (field in dependencyOrder(form.fields)) {
        val stored = form.values?.get(field.key)
        values[field.key] = when {
            storedFits(field, stored) -> stored!!
            editing && keepsRuntimeDefault(field) && parentStored(form, field) -> JsonPrimitive("")
            else -> defaultFor(field, sources, values)
        }
    }
    return values
}

/**
 * The field's parent has a stored value: the field is in effect for the agent
 * as saved. A parent with none (a branch the saved agent is not on, e.g. Pi's
 * "configured" model while it uses a provider) means the field does not apply,
 * so switching to that branch starts at the parent's default, as on create.
 */
private fun parentStored(form: ParsedRuntimeFormV2, field: RuntimeFormV2Field): Boolean {
    val parentKey = field.dependsOn ?: field.derivedFrom?.key ?: return false
    val parent = form.fields.firstOrNull { it.key == parentKey } ?: return false
    val stored = form.values?.get(parent.key)
    return storedFits(parent, stored) && stored.stringOrNull() != ""
}

/** An optional choice field whose empty value means "the runtime's default" and whose default follows a parent. */
private fun keepsRuntimeDefault(field: RuntimeFormV2Field): Boolean =
    (field.kind == RuntimeFormV2FieldKind.DependentSelect || field.kind == RuntimeFormV2FieldKind.DerivedSelect) &&
        !field.required && field.requiredWhen.isEmpty()

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
        // A typed value (`select.custom_value`) is the user's own; a re-probe never replaces it. Nor is a
        // value the source lists as unavailable (a bound saved connection): it is never silently rewritten.
        if (choices.allowCustom || choices.options.any { it.value == current }) continue
        if (runtimeFormV2UnavailableSelection(field, sources, next) != null) continue
        next = applyRuntimeFormV2Change(form, sources, next, field.key, defaultFor(field, sources, next))
    }
    return next
}

/**
 * Set one field and cascade: fields whose choices depend on it are reset to
 * their defaults, recursively, so a stale model can never survive a provider
 * change.
 */
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
    // Moving onto or off a saved-connection selection (`provider.saved_connection`) hands the
    // connectionOwned fields to the connection or back to the user: their old value must not survive.
    if (runtimeFormV2ConnectionActive(form, sources, next) != runtimeFormV2ConnectionActive(form, sources, values)) {
        for (field in form.fields) {
            if (field.connectionOwned) next[field.key] = defaultFor(field, sources, next)
        }
    }
    return next
}

/**
 * The field's option source status when it is not live (`option_source.status`); null when live, when
 * the source says nothing, or when the status is one this client does not know. Only fields that load
 * a source themselves report one; a derived field follows its parent. Mirrors slock v2State.
 */
internal fun runtimeFormV2SourceStatus(field: RuntimeFormV2Field, sources: RuntimeFormV2Sources): RuntimeFormV2SourceStatus? {
    val source = field.optionSourceId?.let { sources[it] } ?: return null
    val unavailable = when (source.status) {
        "unavailable" -> true
        "fallback" -> false
        else -> return null
    }
    return RuntimeFormV2SourceStatus(unavailable, source.reason, source.retryable == true)
}

/**
 * Hidden but kept: an optional field whose source is unavailable, or an optional dependent_select whose
 * parent value has no key in its loaded source (v3 reasoning effort for a typed or effort-less model).
 * Its value is kept: never reset, and on edit the stored one is submitted unchanged. A required one stays
 * to show why it blocks. Mirrors slock v2State `hiddenAsUnavailable`.
 */
private fun hiddenAsUnavailable(field: RuntimeFormV2Field, values: RuntimeFormV2Values, sources: RuntimeFormV2Sources?): Boolean {
    if (sources == null || isRuntimeFormV2FieldRequired(field, values)) return false
    if (runtimeFormV2SourceStatus(field, sources)?.unavailable == true) return true
    if (field.kind != RuntimeFormV2FieldKind.DependentSelect) return false
    val source = field.optionSourceId?.let { sources[it] } ?: return false
    val parent = values.text(field.dependsOn)
    return source.optionsByValue?.containsKey(parent) != true && source.customValueAllowedByValue?.containsKey(parent) != true
}

/**
 * The values a visibility rule sees for [key]: the raw one, plus, when it holds a saved-connection
 * selection (`provider.saved_connection`), the connection's underlying provider id. A gateway
 * connection keeps the gateway's endpoint fields visible (as disabled managed fields) instead of
 * hiding them behind the raw `connection:<id>` value.
 */
private fun visibilityValuesOf(key: String, values: RuntimeFormV2Values, sources: RuntimeFormV2Sources?): List<String> {
    val raw = values.text(key)
    val source = sources?.values?.firstOrNull { it.pointer == "/$key" && it.options != null } ?: return listOf(raw)
    val underlying = source.options?.firstOrNull { it.value == raw }?.underlyingProviderId
    return if (underlying != null) listOf(raw, underlying) else listOf(raw)
}

/**
 * Visibility rules (v2 `visibility`, v3 `rules`), plus (when [sources] is given) `option_source.status`:
 * an optional field whose source is unavailable is hidden, and so is an optional dependent_select whose
 * parent value has no key in its source. A field hidden by a v3 rule is not validated, not required and
 * not submitted.
 */
internal fun isRuntimeFormV2FieldVisible(
    field: RuntimeFormV2Field,
    values: RuntimeFormV2Values,
    sources: RuntimeFormV2Sources? = null
): Boolean =
    field.rule.let { rule -> rule == null || evaluateRuntimeFormV3Condition(rule.condition, values) == (rule.effect == RuntimeFormV3Effect.Show) } &&
        !hiddenAsUnavailable(field, values, sources) &&
        field.visibleWhen.all { rule -> visibilityValuesOf(rule.key, values, sources).any { it in rule.values } }

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
    val connectionActive = runtimeFormV2ConnectionActive(form, sources, values)
    for (field in form.fields) {
        if (!isRuntimeFormV2FieldVisible(field, values, sources)) continue
        val required = isRuntimeFormV2FieldRequired(field, values)
        if (field.kind == RuntimeFormV2FieldKind.Unsupported) {
            if (required) errors[field.key] = RuntimeFormV2ErrorCode.UnsupportedRequired
            continue
        }
        // A bound-but-unavailable connection stays visible and blocks the save: it is never silently rewritten.
        if (connectionActive && runtimeFormV2SelectedConnection(field, sources, values)?.unavailable == true) {
            errors[field.key] = RuntimeFormV2ErrorCode.ConnectionUnavailable
            continue
        }
        if (required && runtimeFormV2SourceStatus(field, sources)?.unavailable == true) {
            errors[field.key] = RuntimeFormV2ErrorCode.SourceUnavailable
            continue
        }
        val choices = runtimeFormV2Choices(field, sources, values)
        // A value that is an unavailable entry blocks, unless the source takes typed values: then it is
        // accepted as typed, as before.
        if (
            choices is RuntimeFormV2Choices.Select && !choices.allowCustom &&
            runtimeFormV2UnavailableSelection(field, sources, values) != null
        ) {
            errors[field.key] = RuntimeFormV2ErrorCode.OptionUnavailable
            continue
        }
        val text = values.text(field.key).trim()
        // While a saved connection is selected, the fields it owns are not the user's to fill, and a changed
        // provider binding never demands a re-entered secret for them (the connection owns the credential).
        val connectionOwnedNow = connectionActive && field.connectionOwned
        if (
            editing && field.kind == RuntimeFormV2FieldKind.Secret && text.isEmpty() && !connectionOwnedNow &&
            keepBindingChanged(form, sources, values, field.key)
        ) {
            errors[field.key] = RuntimeFormV2ErrorCode.SecretReentryRequired
            continue
        }
        // On edit a blank secret (writeOnly) field keeps the stored value. A field a selected saved
        // connection owns is not the user's to fill.
        val blankAllowed = (editing && field.kind == RuntimeFormV2FieldKind.Secret) || connectionOwnedNow
        if (required && !blankAllowed && field.kind in textLikeKinds && text.isEmpty()) {
            errors[field.key] = RuntimeFormV2ErrorCode.Required
            continue
        }
        if (field.kind == RuntimeFormV2FieldKind.Url && text.isNotEmpty() && !httpUrl.containsMatchIn(text)) {
            errors[field.key] = RuntimeFormV2ErrorCode.InvalidUrl
        }
        // A derived list with nothing to offer (a model without an effort menu, or a typed custom model no
        // option describes) is not shown, so it cannot be corrected here: its value goes to the server
        // unchanged, which validates it. Mirrors slock v2State.
        val unconstrained = field.kind == RuntimeFormV2FieldKind.DerivedSelect &&
            choices is RuntimeFormV2Choices.Select && choices.options.isEmpty()
        if (
            choices is RuntimeFormV2Choices.Select && !choices.allowCustom && !unconstrained &&
            text.isNotEmpty() && choices.options.none { it.value == text }
        ) {
            errors[field.key] = RuntimeFormV2ErrorCode.NotAChoice
        }
    }
    return errors
}

/**
 * `writeOnlyKeepRequiresUnchanged` comparison, as slock v2State `boundValue`: as strings (a non-string
 * value as its JSON text; absent or JSON null as ""), trimmed, trailing slashes removed.
 */
private fun boundValue(value: JsonElement?): String =
    when {
        value == null || value is JsonNull -> ""
        else -> value.stringOrNull() ?: value.toString()
    }.trim().trimEnd('/')

/** Whether a visible field the secret [key] is bound to differs from its stored value (form.values). */
private fun keepBindingChanged(
    form: ParsedRuntimeFormV2,
    sources: RuntimeFormV2Sources,
    values: RuntimeFormV2Values,
    key: String
): Boolean =
    form.writeOnlyKeepRequiresUnchanged[key].orEmpty().any { boundKey ->
        val boundField = form.fields.firstOrNull { it.key == boundKey }
        boundField != null && isRuntimeFormV2FieldVisible(boundField, values, sources) &&
            boundValue(values[boundKey]) != boundValue(form.values?.get(boundKey))
    }

/**
 * The `formValues` body: every visible field, plus the stored value of any
 * field this client could not render so it survives unchanged. An optional
 * field hidden because its source is unavailable keeps its stored value too
 * (edit); on create it has none and is omitted. Fields a selected saved
 * connection owns are left out entirely.
 */
internal fun runtimeFormV2Submission(
    form: ParsedRuntimeFormV2,
    values: RuntimeFormV2Values,
    sources: RuntimeFormV2Sources? = null
): JsonObject {
    val out = linkedMapOf<String, JsonElement>()
    val connectionActive = sources != null && runtimeFormV2ConnectionActive(form, sources, values)
    for (field in form.fields) {
        // Connection-owned fields never enter formValues while a saved connection is selected, not even
        // as empty strings: the server refuses any value for them on a connection submit.
        if (connectionActive && field.connectionOwned) continue
        if (hiddenAsUnavailable(field, values, sources)) {
            form.values?.get(field.key)?.let { out[field.key] = it }
            continue
        }
        if (field.kind != RuntimeFormV2FieldKind.Unsupported && !isRuntimeFormV2FieldVisible(field, values, sources)) continue
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
