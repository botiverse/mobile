package build.raft.app

import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.components.rememberRaftDesignSystemEditor
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextFieldAction
import build.raft.designsystem.components.RaftTextFieldDefaults
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/** Web caps a section name at 80 characters. */
private const val RaftHomeSectionNameMaxLength = 80

/**
 * What the create/rename section dialog holds: the name being typed, the section's emoji and
 * whether the emoji picker is open. Picking sets the emoji and closes the picker; removing
 * clears it (web `SidebarSectionEmojiPicker`).
 */
@Stable
internal class RaftHomeSectionDialogState(initialName: String, initialEmoji: String) {
    var name by mutableStateOf(textFieldValueAtEnd(initialName))
    var emoji by mutableStateOf(initialEmoji.trim())
        private set
    var pickerOpen by mutableStateOf(false)

    val trimmedName: String get() = name.text.trim()

    fun updateName(value: TextFieldValue) {
        name = value.coerceTextLength(RaftHomeSectionNameMaxLength)
    }

    fun pickEmoji(value: String) {
        emoji = value.trim()
        pickerOpen = false
    }

    fun removeEmoji() {
        emoji = ""
        pickerOpen = false
    }

    /** The (name, emoji) to save, or null while the name is blank. A cleared emoji saves as null. */
    fun submission(): Pair<String, String?>? =
        trimmedName.takeIf { it.isNotEmpty() }?.let { it to emoji.takeIf(String::isNotEmpty) }
}

/**
 * task #512: create/rename dialog for a custom section, web `SidebarSectionDialog.tsx`: one
 * "Name" field whose start holds the emoji picker trigger (`SidebarSectionEmojiPicker` in an
 * InputGroup addon): the section's emoji, or the smile-plus icon when it has none. The trigger
 * opens [RaftEmojiPickerDialog]; with an emoji set, the picker also offers "Remove Emoji".
 */
@Composable
internal fun RaftHomeSectionDialog(
    title: String,
    submitLabel: String?,
    nameLabel: String,
    namePlaceholder: String,
    initialName: String,
    initialEmoji: String,
    onDismiss: () -> Unit,
    onSubmit: (name: String, emoji: String?) -> Unit,
    state: RaftHomeSectionDialogState = remember { RaftHomeSectionDialogState(initialName, initialEmoji) },
    autoFocus: Boolean = true,
) {
    val chooseLabel = stringResource(Res.string.home_section_choose_emoji)
    val triggerLabel =
        if (state.emoji.isEmpty()) chooseLabel else stringResource(Res.string.home_section_change_emoji, state.emoji)
    RaftUnifiedDialog(
        title = title,
        onDismiss = onDismiss,
        dismissOnOutsideTap = false,
        maxWidth = 384.dp
    ) {
        RaftField(label = nameLabel) {
            val editor =
                rememberRaftDesignSystemEditor(
                    keyboardOptions = RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions,
                    keyboardActions = KeyboardActions.Default,
                    keyboardHeightChange = {},
                    focusRequester = remember { FocusRequester() },
                    autoFocus = autoFocus,
                    onFocus = {},
                    onBlur = {},
                )
            RaftTextField(
                value = state.name,
                onValueChange = state::updateName,
                modifier = editor.modifier,
                placeholder = namePlaceholder,
                keyboardOptions = editor.keyboardOptions,
                leadingAction = {
                    RaftTextFieldAction(onClick = { state.pickerOpen = true }, label = triggerLabel) {
                        if (state.emoji.isEmpty()) {
                            RaftSlotIcon(RaftLucideIconName.SmilePlus)
                        } else {
                            // Web `text-xl`: the emoji is drawn larger than the 16dp icon box.
                            RaftText(
                                state.emoji,
                                Modifier.wrapContentSize(unbounded = true),
                                style = RaftTheme.typography.body.copy(fontSize = 20.sp, lineHeight = 28.sp),
                                maxLines = 1,
                            )
                        }
                    }
                },
            )
        }
        RaftDialogFooter {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg
            )
            RaftButton(
                text = submitLabel ?: stringResource(Res.string.common_save),
                onClick = { state.submission()?.let { (name, emoji) -> onSubmit(name, emoji) } },
                enabled = state.trimmedName.isNotEmpty(),
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg
            )
        }
    }
    if (state.pickerOpen) {
        RaftEmojiPickerDialog(
            title = chooseLabel,
            onDismiss = { state.pickerOpen = false },
            onSelect = state::pickEmoji,
            removeLabel = if (state.emoji.isNotEmpty()) stringResource(Res.string.home_section_remove_emoji) else null,
            onRemove = state::removeEmoji,
        )
    }
}
