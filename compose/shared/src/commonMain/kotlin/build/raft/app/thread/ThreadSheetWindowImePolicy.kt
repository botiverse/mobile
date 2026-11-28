package build.raft.app.thread

import androidx.compose.runtime.Composable

/**
 * Keeps the dialog window's IME flag in sync as [ThreadMessageActionSheet] morphs between
 * [ThreadMessageSheetMode.Actions] (`keepsHostKeyboard = true`: composer keyboard stays open)
 * and [ThreadMessageSheetMode.EmojiPicker] / [ThreadMessageSheetMode.SelectText]
 * (`keepsHostKeyboard = false`: clears `FLAG_ALT_FOCUSABLE_IM` on Android so emoji search input
 * can open the software keyboard).
 */
@Composable
internal expect fun ThreadSheetWindowImePolicy(keepsHostKeyboard: Boolean)
