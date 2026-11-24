package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.RaftMessageCardForm
import build.raft.app.data.RaftMessageCardFormField
import build.raft.app.data.RaftMessageCardFormProblem
import build.raft.app.data.messageCardFormFieldMax
import build.raft.app.data.messageCardFormInputs
import build.raft.app.data.messageCardFormProblems
import build.raft.app.data.messageCardFormSecrets
import build.raft.app.data.messageCardSecretValueFits
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftFieldDescription
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextFieldAction

/*
 * Form fields rendered inside Raft's confirmation dialog for App-sent card acting buttons
 * (RFC 086 §5.1; web `LinkCardFormFields.tsx`).
 *
 * Values live strictly in dialog-scoped state, never in stores, caches, drafts, or logs.
 * SecretInput fields are cleared on submit, cancel, dismiss, and dispose.
 */

internal const val AppLinkCardFormTestTag = "link-card-form"
internal const val AppLinkCardFormFieldTestTagPrefix = "link-card-form-field-"
internal const val AppLinkCardFormSecretToggleTestTagPrefix = "link-card-form-secret-toggle-"
internal const val AppLinkCardFormSecretNoticeTestTagPrefix = "link-card-form-secret-notice-"

/**
 * Dialog-scoped form state for [ThreadAppLinkCardPress.Confirmation].
 * Holds local input text, live validation problems, and per-secret reveal flags; clears all
 * SecretInput values immediately on [clearSecrets] (called on submit, cancel, and dispose).
 */
internal class ThreadAppLinkCardFormState(val form: RaftMessageCardForm) {
    val values = mutableStateMapOf<String, String>()
    val problems = mutableStateMapOf<String, RaftMessageCardFormProblem>()
    val secretRevealed = mutableStateMapOf<String, Boolean>()

    init {
        for (field in form.fields) {
            values[field.id] =
                when (field) {
                    is RaftMessageCardFormField.TextInput -> field.defaultValue.orEmpty()
                    is RaftMessageCardFormField.SecretInput -> ""
                }
        }
    }

    fun updateText(fieldId: String, next: String) {
        values[fieldId] = next
        problems.remove(fieldId)
    }

    /**
     * Handles an edit/paste on a SecretInput: refuses any candidate exceeding 4096 UTF-8 bytes
     * whole (never silently truncating) and surfaces the byte-limit error immediately.
     */
    fun updateSecret(fieldId: String, next: String) {
        if (!messageCardSecretValueFits(next)) {
            problems[fieldId] = RaftMessageCardFormProblem.TooLong
            return
        }
        values[fieldId] = next
        problems.remove(fieldId)
    }

    fun toggleSecretReveal(fieldId: String) {
        secretRevealed[fieldId] = !(secretRevealed[fieldId] ?: false)
    }

    /**
     * Validates all fields. When valid, builds `(inputs, secrets)`, clears SecretInput field state
     * immediately, and returns the pair; returns null if any field has a validation problem.
     */
    fun validateAndConsume(): Pair<Map<String, String>?, MutableMap<String, String>?>? {
        val snapshot = HashMap<String, String>(form.fields.size)
        for (field in form.fields) {
            snapshot[field.id] = values[field.id].orEmpty()
        }
        val found = messageCardFormProblems(form, snapshot)
        problems.clear()
        problems.putAll(found)
        if (found.isNotEmpty()) return null
        val inputs = messageCardFormInputs(form, snapshot)
        val secrets = messageCardFormSecrets(form, snapshot)
        clearSecrets()
        return inputs to secrets
    }

    /** Clears all SecretInput values and reveal states from dialog memory. */
    fun clearSecrets() {
        for (field in form.fields) {
            if (field is RaftMessageCardFormField.SecretInput) {
                values[field.id] = ""
                secretRevealed.remove(field.id)
            }
        }
    }
}

@Composable
internal fun rememberThreadAppLinkCardFormState(
    key: Any,
    form: RaftMessageCardForm,
): ThreadAppLinkCardFormState {
    val state = remember(key, form) { ThreadAppLinkCardFormState(form) }
    DisposableEffect(state) {
        onDispose { state.clearSecrets() }
    }
    return state
}

@Composable
internal fun ThreadAppLinkCardFormFields(
    state: ThreadAppLinkCardFormState,
    appName: String,
    disabled: Boolean,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val fields = state.form.fields
    Column(
        modifier = modifier.fillMaxWidth().testTag(AppLinkCardFormTestTag),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        fields.forEachIndexed { index, field ->
            val isLast = index == fields.lastIndex
            val value = state.values[field.id].orEmpty()
            val problem = state.problems[field.id]
            val secret = field is RaftMessageCardFormField.SecretInput
            val errorText =
                when (problem) {
                    RaftMessageCardFormProblem.Required -> stringResource(Res.string.thread_link_preview_action_form_required)
                    RaftMessageCardFormProblem.OneLine -> stringResource(Res.string.thread_link_preview_action_form_one_line)
                    RaftMessageCardFormProblem.TooLong ->
                        stringResource(
                            if (secret) {
                                Res.string.thread_link_preview_action_form_too_many_bytes
                            } else {
                                Res.string.thread_link_preview_action_form_too_long
                            },
                            messageCardFormFieldMax(field),
                        )
                    null -> null
                }
            val keyboardActions =
                KeyboardActions(
                    onDone = { onSubmit() },
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                )
            val imeAction = if (isLast) ImeAction.Done else ImeAction.Next

            RaftField(
                label = field.label,
                description = null,
                error = errorText,
                enabled = !disabled,
                optional = !field.required,
                required = field.required,
            ) {
                if (secret) {
                    val revealed = state.secretRevealed[field.id] == true
                    // Keep the audience notice always visible above any error (RFC 086 §5.1 / web LinkCardFormFields).
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        RaftTextField(
                            value = value,
                            onValueChange = { next -> state.updateSecret(field.id, next) },
                            modifier = Modifier.fillMaxWidth().testTag("$AppLinkCardFormFieldTestTagPrefix${field.id}"),
                            placeholder = field.placeholder,
                            enabled = !disabled,
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    autoCorrectEnabled = false,
                                    imeAction = imeAction,
                                ),
                            keyboardActions = keyboardActions,
                            visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingAction = {
                                RaftTextFieldAction(
                                    onClick = { state.toggleSecretReveal(field.id) },
                                    label =
                                        stringResource(
                                            if (revealed) Res.string.common_hide_password else Res.string.common_show_password,
                                        ),
                                    modifier = Modifier.testTag("$AppLinkCardFormSecretToggleTestTagPrefix${field.id}"),
                                    enabled = !disabled,
                                ) {
                                    RaftSlotIcon(if (revealed) RaftLucideIconName.EyeOff else RaftLucideIconName.Eye)
                                }
                            },
                        )
                        RaftFieldDescription(
                            text = stringResource(Res.string.thread_link_preview_action_form_secret_notice, appName),
                            modifier = Modifier.testTag("$AppLinkCardFormSecretNoticeTestTagPrefix${field.id}"),
                            enabled = !disabled,
                        )
                    }
                } else {
                    RaftTextField(
                        value = value,
                        onValueChange = { next -> state.updateText(field.id, next) },
                        modifier = Modifier.fillMaxWidth().testTag("$AppLinkCardFormFieldTestTagPrefix${field.id}"),
                        placeholder = field.placeholder,
                        enabled = !disabled,
                        keyboardOptions = KeyboardOptions(imeAction = imeAction),
                        keyboardActions = keyboardActions,
                    )
                }
            }
        }
    }
}
