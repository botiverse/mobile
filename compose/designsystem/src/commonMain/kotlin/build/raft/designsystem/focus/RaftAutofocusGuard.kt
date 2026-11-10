package build.raft.designsystem.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/**
 * What an automatic focus request (the composer taking focus when a conversation opens) must not
 * take focus from: an open dialog, menu or popover, or another text field (search, a rename
 * dialog's field) that holds focus. A user's own tap or shortcut never consults this.
 *
 * Dialogs, dropdown menus and dismissable floating overlays hold [HoldOpenSurface] while shown;
 * every text field reports its focus through [setFieldFocused] (the app's editors, and the
 * design-system `RaftTextField` / `RaftTextarea` themselves through [raftReportsFocusToAutofocusGuard])
 * unless it is the field the automatic request is for (a conversation composer, inside
 * [LocalRaftAutofocusTargetField]), which reports nothing. Main thread only.
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

/**
 * True around the field an automatic focus request is for (a conversation composer's editor): its
 * own focus never counts as another field's, so it never blocks itself. Every other field reports.
 */
val LocalRaftAutofocusTargetField = staticCompositionLocalOf { false }

/**
 * Reports this text field's focus to [RaftAutofocusGuard] (skipped inside
 * [LocalRaftAutofocusTargetField]). Put it on the editor's modifier, before the editor's own focus
 * target, as `RaftTextField` / `RaftTextarea` do.
 */
@Composable
fun Modifier.raftReportsFocusToAutofocusGuard(): Modifier {
    val reports = !LocalRaftAutofocusTargetField.current
    val owner = remember { Any() }
    DisposableEffect(owner, reports) {
        onDispose { RaftAutofocusGuard.setFieldFocused(owner, false) }
    }
    return if (reports) onFocusChanged { RaftAutofocusGuard.setFieldFocused(owner, it.isFocused) } else this
}
