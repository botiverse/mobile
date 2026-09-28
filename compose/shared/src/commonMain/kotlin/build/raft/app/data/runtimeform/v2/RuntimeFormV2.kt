// Generated from packages/runtime-form/contract/runtime-form-v2.contract.json (sha256 de3eccf071095ad5). Do not edit.
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
)

/** Choices for the field at `pointer`, loaded from .../runtime-forms/v2/:runtimeId/option-sources/:sourceId. */
@Serializable
data class RuntimeFormV2OptionSourceRef(
    val sourceId: String,
    val kind: String, // one of select, dependent_select
    val pointer: String,
    val dependsOn: String? = null,
)

/** Response of the option-sources endpoint. select: options + defaultValue. dependent_select: per-value lists keyed by the value of dependsOn. */
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
