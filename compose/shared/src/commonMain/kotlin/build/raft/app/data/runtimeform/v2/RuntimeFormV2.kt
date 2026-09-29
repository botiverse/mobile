// Generated from packages/runtime-form/contract/runtime-form-v2.contract.json (sha256 1556344f2749e4c0). Do not edit.
// Decode with Json { ignoreUnknownKeys = true }: every type is open.
// Enumerated strings stay String so an unknown value never fails decoding.
package build.raft.app.data.runtimeform.v2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Response of GET /api/servers/:id/machines/:machineId/runtime-forms/v2/:runtimeId (create), or of GET /api/agents/:id/runtime-form (edit, with `values`: the agent's current field values; writeOnly fields are never included). */
@Serializable
data class RuntimeFormV2Definition(
    val protocolVersion: Int, // always 2
    val runtimeId: String,
    val schemaVersion: String,
    val dataSchema: RuntimeFormV2DataSchema,
    val uiSchema: RuntimeFormV2UiSchema? = null,
    val optionSources: Map<String, RuntimeFormV2OptionSourceRef>? = null,
    /** Client capabilities this form needs beyond the base v2 renderer. Absent or null means []. A client that sees an entry it does not implement, a value that is not an array, or a non-string entry must treat the whole v2 form as unavailable and fall back to its v1/legacy form; it must never render a partial v2 form. Names (frozen): `select.custom_value` (OptionSource.customValueAllowed), `choice.labels` (FieldCopy.choices), `option_source.status` (OptionSource.status/reason/retryable). A form lists a name only when it uses that feature; the server never sends those fields to a form that does not list the name. */
    val requiredClientCapabilities: List<String>? = null,
    val values: Map<String, JsonElement>? = null,
)

@Serializable
data class RuntimeFormV2DataSchema(
    val type: String, // always "object"
    val required: List<String>? = null,
    val properties: Map<String, RuntimeFormV2FieldSchema>,
)

/** Render by `type` (with x-optionSource or x-optionsFrom), never by field name. An unknown `type` is unsupported: skip it if optional, block saving if required. */
@Serializable
data class RuntimeFormV2FieldSchema(
    val type: String,
    val title: String? = null,
    val minLength: Int? = null,
    val format: String? = null,
    val writeOnly: Boolean? = null,
    @SerialName("x-optionSource") val optionSource: String? = null,
    @SerialName("x-optionsFrom") val optionsFrom: RuntimeFormV2OptionsFrom? = null,
    /** JSON Schema allows a boolean (`false`) or a value schema (`{"type": "string"}` for a key-value map); clients must accept both. */
    val additionalProperties: JsonElement? = null,
)

/** Choices derived from the option currently selected in another field: that option's `attribute` (a string list) are the choices, and its `defaultAttribute` (if any) is the default. Example: Kimi reasoning effort follows the selected model. */
@Serializable
data class RuntimeFormV2OptionsFrom(
    val field: String,
    val attribute: String,
    val defaultAttribute: String? = null,
)

@Serializable
data class RuntimeFormV2UiSchema(
    val order: List<String>? = null,
    val layout: RuntimeFormV2Layout? = null,
    val visibility: List<RuntimeFormV2VisibilityRule>? = null,
    val localization: Map<String, RuntimeFormV2FieldCopy>? = null,
)

@Serializable
data class RuntimeFormV2Layout(
    val advanced: List<String>? = null,
)

/** Show the field at `pointer` only when the field at when.pointer has one of when.in. */
@Serializable
data class RuntimeFormV2VisibilityRule(
    val pointer: String,
    @SerialName("when") val condition: RuntimeFormV2VisibilityCondition,
)

@Serializable
data class RuntimeFormV2VisibilityCondition(
    val pointer: String,
    @SerialName("in") val values: List<String>,
)

@Serializable
data class RuntimeFormV2FieldCopy(
    val label: String? = null,
    val hint: String? = null,
    val placeholder: String? = null,
    /** capability `choice.labels`. Display copy for the choices of a `select` or `derived_select` field, keyed by option value. Label priority: choices[value].label, else the option's own `label`, else the raw value; `description` comes only from here. Keys that match no option are ignored; an option without a key falls back per that priority. */
    val choices: Map<String, RuntimeFormV2ChoiceCopy>? = null,
)

/** Copy for one choice (FieldCopy.choices, capability `choice.labels`). */
@Serializable
data class RuntimeFormV2ChoiceCopy(
    val label: String,
    val description: String? = null,
)

/** Choices for the field at `pointer`, loaded from .../runtime-forms/v2/:runtimeId/option-sources/:sourceId. */
@Serializable
data class RuntimeFormV2OptionSourceRef(
    val sourceId: String,
    val kind: String, // one of select, dependent_select
    val pointer: String,
    val dependsOn: String? = null,
)

/** Response of the option-sources endpoint. select: options + defaultValue. dependent_select: per-value lists keyed by the value of dependsOn. `customValueAllowed`, `status`, `reason` and `retryable` are sent only on the v2 endpoint and only to forms that list the matching capability (`select.custom_value`, `option_source.status`); a form without the capability gets the source exactly as before. */
@Serializable
data class RuntimeFormV2OptionSource(
    val sourceId: String,
    val kind: String, // one of select, dependent_select
    val pointer: String,
    val dependsOn: String? = null,
    val options: List<RuntimeFormV2Option>? = null,
    val defaultValue: String? = null,
    val optionsByValue: Map<String, List<RuntimeFormV2Option>>? = null,
    val defaultValueByValue: Map<String, String>? = null,
    val customValueAllowedByValue: Map<String, Boolean>? = null,
    /** capability `select.custom_value`; meaningful only for kind `select` (dependent_select keeps customValueAllowedByValue). true: render a combobox (pick a listed option or type a value). The server stores a listed value as a preset and an unlisted typed value as a custom value; a required field still needs a non-empty value. On edit, a stored value that is not in the list is shown and submitted as a typed value, never reported as not-a-choice. */
    val customValueAllowed: Boolean? = null,
    /** capability `option_source.status`. live: the list came from the Computer's probe. fallback: the probe was not live and the list is the bundled one (usable; show `reason`). unavailable: no list at all; `options` is []. One status per source, worst wins (unavailable > fallback > live), dependent_select included (there is no per-value status). A source-level failure is reported here, never as a form-level error, and never makes a client fall back to v1 (v1 fallback is only for a form-level 404 or protocol error). Client obligations: unavailable + required field: show the status and (when retryable) a retry, and block submit; unavailable + optional field: hide the field. Retry = request the same source again with `?refresh=1`; the server then bypasses any cache (the Computer's probe gate already single-flights concurrent probes). */
    val status: String? = null, // one of live, fallback, unavailable
    /** capability `option_source.status`. Why the status is not live; absent when status is live. probe_timeout and probe_failed stay distinct. Server mapping from the Computer's probe outcome: missing_config -> missing_config; no_models (or a live list with no models) -> no_models; unsupported -> unsupported; probe error with code detect_timeout, or the server's own wait timing out -> probe_timeout; probe error with code computer_offline, or the Computer offline/unrouted -> machine_offline; any other probe error or failure -> probe_failed. Clients must accept an unknown reason (show a generic message). */
    val reason: String? = null, // one of probe_timeout, probe_failed, missing_config, no_models, unsupported, machine_offline
    /** capability `option_source.status`. Whether retrying may change the answer: true for probe_timeout, probe_failed, machine_offline and no_models (the user may add models); false for missing_config and unsupported. Absent or false when status is live. */
    val retryable: Boolean? = null,
)

@Serializable
data class RuntimeFormV2Option(
    val value: String,
    val label: String,
    val providerKind: String? = null,
    val supportedReasoningEfforts: List<String>? = null,
    val defaultReasoningEffort: String? = null,
)

@Serializable
data class RuntimeFormV2SubmitRef(
    val protocolVersion: Int, // always 2
    val runtimeId: String,
)

/** The runtime-form part of POST /api/agents: field values keyed by dataSchema property name. The server assembles the runtimeConfig. */
@Serializable
data class RuntimeFormV2Submission(
    val formDefinitionRef: RuntimeFormV2SubmitRef,
    val formValues: Map<String, JsonElement>,
)
