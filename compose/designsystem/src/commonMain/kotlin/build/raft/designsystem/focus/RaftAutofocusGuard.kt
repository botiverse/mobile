package build.raft.designsystem.focus

import androidx.compose.foundation.focusGroup
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
 * [LocalRaftAutofocusTargetField]), which reports nothing. Page roots report whether anything in
 * them holds focus ([raftReportsFocusPresence]), so a restore runs only when focus is lost
 * (raft-design "Moving focus" rule 4). Main thread only.
 */
object RaftAutofocusGuard {
    private var openSurfaces by mutableStateOf(0)
    private var focusedFields by mutableStateOf(emptySet<Any>())
    private var focusedRoots by mutableStateOf(emptySet<Any>())

    /** True while an automatic focus request would take focus from something the user is in. */
    val blocksAutofocus: Boolean
        get() = openSurfaces > 0 || focusedFields.isNotEmpty()

    /** A dialog, menu or popover is shown. */
    val surfaceOpen: Boolean
        get() = openSurfaces > 0

    /** A text field other than a conversation composer holds focus. */
    val otherFieldFocused: Boolean
        get() = focusedFields.isNotEmpty()

    /**
     * Something holds focus: a reported field, or anything inside a page root that reports its
     * focus ([raftReportsFocusPresence]). False once the focused element left the composition
     * (its focus is cleared) or nothing was focused: the focus is lost.
     */
    val focusHeld: Boolean
        get() = focusedRoots.isNotEmpty() || focusedFields.isNotEmpty()

    fun setFieldFocused(owner: Any, focused: Boolean) {
        val next = if (focused) focusedFields + owner else focusedFields - owner
        if (next != focusedFields) focusedFields = next
    }

    fun setRootHasFocus(owner: Any, hasFocus: Boolean) {
        val next = if (hasFocus) focusedRoots + owner else focusedRoots - owner
        if (next != focusedRoots) focusedRoots = next
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

/**
 * Reports whether anything inside this page root holds focus ([RaftAutofocusGuard.focusHeld]).
 *
 * The root is a [focusGroup] (a focus target that never takes focus itself), and the report reads
 * that target's own state. Without one, the report was derived from the descendants and stayed
 * "focused" after the focused one left the composition (a closed side thread's composer; seen in
 * ComposerAutofocusOnOpenUiTest): Compose clears the focus through the targets on the active path,
 * and a root with none of its own was apparently never told. Layout is untouched; the group only
 * keeps keyboard traversal inside the root before leaving it.
 */
@Composable
fun Modifier.raftReportsFocusPresence(): Modifier {
    val owner = remember { Any() }
    DisposableEffect(owner) {
        onDispose { RaftAutofocusGuard.setRootHasFocus(owner, false) }
    }
    return onFocusChanged { RaftAutofocusGuard.setRootHasFocus(owner, it.hasFocus) }.focusGroup()
}
