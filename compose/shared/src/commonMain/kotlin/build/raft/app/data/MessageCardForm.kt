package build.raft.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Forms on an App-sent card's acting buttons (RFC 086 §5.1, §5.4).
 *
 * Contract: botiverse/slock `packages/shared/src/messageCardActions.ts` and
 * `packages/web/src/components/message/linkCardForm.ts`.
 *
 * Values live only in the confirmation dialog's local inputs and in the single execute request built
 * from them (a SecretInput's values are sealed first to the App's public key, [SealedInputs.kt]):
 * never in a store, disk cache, draft, or diagnostic log.
 */

/** Bounds of a form on an acting button (`MESSAGE_CARD_FORM_LIMITS`). */
internal const val MESSAGE_CARD_FORM_MAX_FIELDS = 4
internal const val MESSAGE_CARD_FORM_LABEL_MAX_CODE_POINTS = 40
internal const val MESSAGE_CARD_FORM_PLACEHOLDER_MAX_CODE_POINTS = 120
internal const val MESSAGE_CARD_FORM_TEXT_INPUT_MAX_LENGTH = 500
internal const val MESSAGE_CARD_FORM_SECRET_INPUT_MAX_BYTES = 4096

/** Maximum size of `sealed_inputs` compact JWE string in bytes/ASCII chars (`MESSAGE_CARD_SEALED_INPUTS_MAX_BYTES`). */
internal const val MESSAGE_CARD_SEALED_INPUTS_MAX_BYTES = 8 * 1024

/** Compact JWE shape: five base64url segments, the second (encrypted key) empty under ECDH-ES. */
internal val MESSAGE_CARD_SEALED_INPUTS_PATTERN =
    Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")

/** Field id pattern (`MESSAGE_CARD_FORM_FIELD_ID`, same as `LINK_CARD_ACTION_ID`). */
internal val MESSAGE_CARD_FORM_FIELD_ID = Regex("^[a-z][a-z0-9_]{0,31}$")

/** Line breaks forbidden in form labels, placeholders, defaults, and TextInput values (`CARD_LINE_BREAK`). */
private val CARD_LINE_BREAK = Regex("\\r\\n|[\\n\\r\\u000B\\u000C\\u001C\\u001D\\u001E\\u0085\\u2028\\u2029]")

/** A form on an acting button: 1 to 4 [TextInput] or [SecretInput] fields, each id unique. */
data class RaftMessageCardForm(
    val fields: List<RaftMessageCardFormField>,
) {
    /** Whether any field is a [RaftMessageCardFormField.SecretInput]. */
    val hasSecretInput: Boolean
        get() = fields.any { it is RaftMessageCardFormField.SecretInput }
}

/** One field of a [RaftMessageCardForm] (`MessageCardFormField`). */
sealed interface RaftMessageCardFormField {
    val id: String
    val label: String
    val placeholder: String?
    val required: Boolean

    /** One-line text field; [defaultValue] (the App's proposal) is one line within [maxLength] code points. */
    data class TextInput(
        override val id: String,
        override val label: String,
        override val placeholder: String? = null,
        override val required: Boolean = false,
        val maxLength: Int? = null,
        val defaultValue: String? = null,
    ) : RaftMessageCardFormField

    /** One-line secret sealed to the App on the viewer's device; never prefilled, never echoed. */
    data class SecretInput(
        override val id: String,
        override val label: String,
        override val placeholder: String? = null,
        override val required: Boolean = false,
    ) : RaftMessageCardFormField
}

/** What is wrong with a single form field value (`LinkCardFormProblem`). */
enum class RaftMessageCardFormProblem {
    Required,
    TooLong,
    OneLine,
}

/** Whether an execute action's form contains a [RaftMessageCardFormField.SecretInput]. */
internal val RaftLinkCardButtonAction.Execute.hasSecretInput: Boolean
    get() = form?.hasSecretInput == true

private val TEXT_INPUT_KEYS = setOf("type", "id", "label", "placeholder", "required", "maxLength", "default")
private val SECRET_INPUT_KEYS = setOf("type", "id", "label", "placeholder", "required")

/**
 * Strictly parses an acting button's `form` (`messageCardFormSchema` / `messageCardTextFormSchema`).
 * When [allowSecret] is false (undo-window buttons), any `SecretInput` field causes refusal.
 */
internal fun readMessageCardForm(raw: JsonElement?, allowSecret: Boolean): RaftMessageCardForm? {
    val obj = raw as? JsonObject ?: return null
    if (obj.keys != setOf("fields")) return null
    val rawFields = obj["fields"] as? JsonArray ?: return null
    if (rawFields.size !in 1..MESSAGE_CARD_FORM_MAX_FIELDS) return null
    val seenIds = HashSet<String>(rawFields.size)
    val fields = ArrayList<RaftMessageCardFormField>(rawFields.size)
    for (entry in rawFields) {
        val fieldObj = entry as? JsonObject ?: return null
        val type = fieldObj["type"].cardString() ?: return null
        val id = fieldObj["id"].cardString()?.takeIf { MESSAGE_CARD_FORM_FIELD_ID.matches(it) && it !in CARD_RESERVED_IDS } ?: return null
        if (!seenIds.add(id)) return null
        val label = fieldObj["label"].cardString()?.takeIf { isOneLineCardText(it, MESSAGE_CARD_FORM_LABEL_MAX_CODE_POINTS) } ?: return null
        val placeholder =
            if ("placeholder" in fieldObj) {
                fieldObj["placeholder"].cardString()?.takeIf { isOneLineCardText(it, MESSAGE_CARD_FORM_PLACEHOLDER_MAX_CODE_POINTS) } ?: return null
            } else {
                null
            }
        val required =
            if ("required" in fieldObj) {
                fieldObj["required"].cardBoolean() ?: return null
            } else {
                false
            }
        val parsed: RaftMessageCardFormField =
            when (type) {
                "TextInput" -> {
                    if (!fieldObj.hasOnlyKeys(TEXT_INPUT_KEYS)) return null
                    val maxLength =
                        if ("maxLength" in fieldObj) {
                            fieldObj["maxLength"].cardSafeInteger()?.takeIf { it in 1L..MESSAGE_CARD_FORM_TEXT_INPUT_MAX_LENGTH.toLong() }?.toInt()
                                ?: return null
                        } else {
                            null
                        }
                    val defaultValue =
                        if ("default" in fieldObj) {
                            val candidate = fieldObj["default"].cardString() ?: return null
                            val limit = maxLength ?: MESSAGE_CARD_FORM_TEXT_INPUT_MAX_LENGTH
                            if (cardCodePoints(candidate) > limit || CARD_LINE_BREAK.containsMatchIn(candidate)) return null
                            candidate
                        } else {
                            null
                        }
                    RaftMessageCardFormField.TextInput(
                        id = id,
                        label = label,
                        placeholder = placeholder,
                        required = required,
                        maxLength = maxLength,
                        defaultValue = defaultValue,
                    )
                }
                "SecretInput" -> {
                    if (!allowSecret) return null
                    if (!fieldObj.hasOnlyKeys(SECRET_INPUT_KEYS)) return null
                    RaftMessageCardFormField.SecretInput(
                        id = id,
                        label = label,
                        placeholder = placeholder,
                        required = required,
                    )
                }
                else -> return null
            }
        fields += parsed
    }
    return RaftMessageCardForm(fields)
}

private fun isOneLineCardText(value: String, maxCodePoints: Int): Boolean =
    isCardText(value, 1, maxCodePoints) && !CARD_LINE_BREAK.containsMatchIn(value)

/** UTF-8 byte length of [value]. */
internal fun messageCardUtf8ByteLength(value: String): Int = value.encodeToByteArray().size

/** The maximum length of [field] in its unit: code points for [TextInput], UTF-8 bytes for [SecretInput]. */
internal fun messageCardFormFieldMax(field: RaftMessageCardFormField): Int =
    when (field) {
        is RaftMessageCardFormField.SecretInput -> MESSAGE_CARD_FORM_SECRET_INPUT_MAX_BYTES
        is RaftMessageCardFormField.TextInput -> field.maxLength ?: MESSAGE_CARD_FORM_TEXT_INPUT_MAX_LENGTH
    }

/**
 * Validates one [value] against [field] (`linkCardFormProblem`):
 * - empty is valid unless `field.required`;
 * - `SecretInput` checks UTF-8 byte length <= 4096;
 * - `TextInput` checks code points <= `maxLength` (default 500) and single-line (`CARD_LINE_BREAK`).
 */
internal fun messageCardFormProblem(field: RaftMessageCardFormField, value: String): RaftMessageCardFormProblem? {
    if (value.isEmpty()) return if (field.required) RaftMessageCardFormProblem.Required else null
    return when (field) {
        is RaftMessageCardFormField.SecretInput ->
            if (messageCardUtf8ByteLength(value) > MESSAGE_CARD_FORM_SECRET_INPUT_MAX_BYTES) {
                RaftMessageCardFormProblem.TooLong
            } else {
                null
            }
        is RaftMessageCardFormField.TextInput -> {
            val limit = field.maxLength ?: MESSAGE_CARD_FORM_TEXT_INPUT_MAX_LENGTH
            when {
                cardCodePoints(value) > limit -> RaftMessageCardFormProblem.TooLong
                CARD_LINE_BREAK.containsMatchIn(value) -> RaftMessageCardFormProblem.OneLine
                else -> null
            }
        }
    }
}

/** Validation problems keyed by field id; empty when the form is valid to submit. */
internal fun messageCardFormProblems(
    form: RaftMessageCardForm,
    values: Map<String, String>,
): Map<String, RaftMessageCardFormProblem> {
    val problems = LinkedHashMap<String, RaftMessageCardFormProblem>()
    for (field in form.fields) {
        val problem = messageCardFormProblem(field, values[field.id].orEmpty())
        if (problem != null) problems[field.id] = problem
    }
    return problems
}

/**
 * Execute's plain `inputs` (`linkCardFormInputs`): non-empty [TextInput] values by field id;
 * empty optional fields are omitted, and [SecretInput] values are NEVER included. Null if empty.
 */
internal fun messageCardFormInputs(
    form: RaftMessageCardForm,
    values: Map<String, String>,
): Map<String, String>? {
    val inputs = LinkedHashMap<String, String>()
    for (field in form.fields) {
        if (field !is RaftMessageCardFormField.TextInput) continue
        val value = values[field.id].orEmpty()
        if (value.isNotEmpty()) inputs[field.id] = value
    }
    return inputs.takeIf { it.isNotEmpty() }
}

/**
 * Secret values to seal (`linkCardFormSecrets`): returns null when [form] has no [SecretInput].
 * When [form] has any [SecretInput], ALWAYS returns a mutable map (empty `{}` if all optional
 * secret inputs were left empty) so the caller seals it and clears the map in `finally`.
 */
internal fun messageCardFormSecrets(
    form: RaftMessageCardForm,
    values: Map<String, String>,
): MutableMap<String, String>? {
    if (!form.hasSecretInput) return null
    val secrets = LinkedHashMap<String, String>()
    for (field in form.fields) {
        if (field !is RaftMessageCardFormField.SecretInput) continue
        val value = values[field.id].orEmpty()
        if (value.isNotEmpty()) secrets[field.id] = value
    }
    return secrets
}

/** Whether [candidateValue] fits a SecretInput's 4096 UTF-8 byte cap without truncation. */
internal fun messageCardSecretValueFits(candidateValue: String): Boolean =
    messageCardUtf8ByteLength(candidateValue) <= MESSAGE_CARD_FORM_SECRET_INPUT_MAX_BYTES

/**
 * Whether inserting/pasting [data] over `[start, end)` keeps a SecretInput within 4096 UTF-8 bytes
 * (`linkCardSecretInsertFits`). Oversize edits must be refused whole, never silently truncated.
 */
internal fun messageCardSecretInsertFits(
    value: String,
    start: Int? = null,
    end: Int? = null,
    data: String,
): Boolean {
    val from = (start ?: value.length).coerceIn(0, value.length)
    val to = (end ?: value.length).coerceIn(from, value.length)
    val next = value.substring(0, from) + data + value.substring(to)
    return messageCardSecretValueFits(next)
}

/** Whether a submission's `inputs` and `sealedInputs` fit [form] (`messageCardFormSubmissionFits`). */
internal fun messageCardFormSubmissionFits(
    form: RaftMessageCardForm?,
    inputs: Map<String, String>?,
    sealedInputs: String?,
): Boolean {
    if (form == null) return sealedInputs == null && inputs == null
    if ((sealedInputs != null) != form.hasSecretInput) return false
    if (sealedInputs != null && (sealedInputs.length > MESSAGE_CARD_SEALED_INPUTS_MAX_BYTES || !MESSAGE_CARD_SEALED_INPUTS_PATTERN.matches(sealedInputs))) {
        return false
    }
    val submitted = inputs.orEmpty()
    val textFields = form.fields.filterIsInstance<RaftMessageCardFormField.TextInput>().associateBy { it.id }
    for ((id, value) in submitted) {
        val field = textFields[id] ?: return false
        val limit = field.maxLength ?: MESSAGE_CARD_FORM_TEXT_INPUT_MAX_LENGTH
        if (cardCodePoints(value) > limit || CARD_LINE_BREAK.containsMatchIn(value)) return false
    }
    return textFields.values.all { field -> !field.required || !submitted[field.id].isNullOrEmpty() }
}
