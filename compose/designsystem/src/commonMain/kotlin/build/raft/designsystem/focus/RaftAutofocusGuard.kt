package build.raft.designsystem.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What an automatic focus request (the composer taking focus when a conversation opens) must not
 * take focus from: an open dialog, menu or popover, or another text field (search, a rename
 * dialog's field) that holds focus. A user's own tap or shortcut never consults this.
 *
 * Dialogs, dropdown menus and dismissable floating overlays hold [HoldOpenSurface] while shown;
 * every app editor reports its focus through [setFieldFocused] unless it is the field the
 * automatic request is for (a conversation composer), which reports nothing. Main thread only.
 */
object RaftAutofocusGuard {
    private var openSurfaces by mutableStateOf(0)
    private var focusedFields by mutableStateOf(emptySet<Any>())

    /** True while an automatic focus request would take focus from something the user is in. */
    val blocksAutofocus: Boolean
        get() = openSurfaces > 0 || focusedFields.isNotEmpty()

    /** A dialog, menu or popover is shown. */
    val surfaceOpen: Boolean
        get() = openSurfaces > 0

    /** A text field other than a conversation composer holds focus. */
    val otherFieldFocused: Boolean
        get() = focusedFields.isNotEmpty()

    fun setFieldFocused(owner: Any, focused: Boolean) {
        val next = if (focused) focusedFields + owner else focusedFields - owner
        if (next != focusedFields) focusedFields = next
    }

    /** Marks a dialog, menu or popover as shown while this is in the composition. */
    @Composable
    fun HoldOpenSurface(enabled: Boolean = true) {
        DisposableEffect(enabled) {
            if (enabled) openSurfaces += 1
            onDispose { if (enabled) openSurfaces -= 1 }
        }
    }
}
