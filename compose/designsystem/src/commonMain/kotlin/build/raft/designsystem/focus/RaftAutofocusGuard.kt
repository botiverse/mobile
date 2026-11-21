package build.raft.designsystem.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
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
 * Thin wrapper over the window's [RaftFocusCoordinator] (audit step 4 / #triage:#465). Dialogs hold
 * a modal token on [RaftFocusCoordinator.HoldModal]; dropdown menus and dismissable floating
 * overlays hold [HoldOpenSurface] while shown; every non-composer text field reports its focus
 * through [setFieldFocused] (the app's editors, and the design-system `RaftTextField` /
 * `RaftTextarea` themselves through [raftReportsFocusToAutofocusGuard]). A conversation composer
 * (inside [LocalRaftAutofocusTargetField]) opts out of `otherFieldFocused` and reports separately
 * as a composer to the window's coordinator. Main thread only.
 *
 * One per window ([LocalRaftAutofocusGuard], provided at each window's root): a dialog open or a
 * search field focused in one window never keeps the composer of another window (Android's app
 * windows, one per server) from taking the focus. A composition with no window of its own uses
 * [Default].
 */
@Stable
class RaftAutofocusGuard(
    val coordinator: RaftFocusCoordinator = RaftFocusCoordinator(),
) {
    /** True while an automatic focus request would take focus from something the user is in. */
    val blocksAutofocus: Boolean
        get() = coordinator.surfaceOpen || coordinator.otherTextFieldFocused

    /** A dialog, menu or popover is shown. */
    val surfaceOpen: Boolean
        get() = coordinator.surfaceOpen

    /** A text field other than a conversation composer holds focus. */
    val otherFieldFocused: Boolean
        get() = coordinator.otherTextFieldFocused

    fun setFieldFocused(owner: Any, focused: Boolean, region: RaftFocusRegion? = null) {
        coordinator.setTextFieldFocused(owner, focused, region)
    }

    /** Marks a dialog, menu or popover as shown while this is in the composition. */
    @Composable
    fun HoldOpenSurface(enabled: Boolean = true) {
        DisposableEffect(enabled, coordinator) {
            if (enabled) coordinator.acquireOpenSurface()
            onDispose { if (enabled) coordinator.releaseOpenSurface() }
        }
    }

    companion object {
        /** The guard of a composition no window provides one for (tests, a single-window host). */
        val Default: RaftAutofocusGuard = RaftAutofocusGuard()
    }
}

/** This window's [RaftAutofocusGuard]; [RaftAutofocusGuard.Default] where no window provides one. */
val LocalRaftAutofocusGuard = staticCompositionLocalOf { RaftAutofocusGuard.Default }

/**
 * True around the field an automatic focus request is for (a conversation composer's editor): its
 * own focus never counts as another field's, so it never blocks itself. Every other field reports.
 */
val LocalRaftAutofocusTargetField = staticCompositionLocalOf { false }

/**
 * Reports this text field's focus to its window's [RaftAutofocusGuard] (skipped inside
 * [LocalRaftAutofocusTargetField]). Put it on the editor's modifier, before the editor's own focus
 * target, as `RaftTextField` / `RaftTextarea` do.
 */
@Composable
fun Modifier.raftReportsFocusToAutofocusGuard(): Modifier {
    val reports = !LocalRaftAutofocusTargetField.current
    val guard = LocalRaftAutofocusGuard.current
    val region = LocalRaftFocusRegion.current
    val owner = remember { Any() }
    var currentlyFocused by remember { mutableStateOf(false) }
    SideEffect {
        if (!reports) {
            if (currentlyFocused) {
                currentlyFocused = false
                guard.setFieldFocused(owner, false, region)
            }
        } else if (currentlyFocused) {
            guard.setFieldFocused(owner, true, region)
        }
    }
    DisposableEffect(owner, guard) {
        onDispose { guard.setFieldFocused(owner, false, region) }
    }
    return if (reports) {
        onFocusChanged {
            currentlyFocused = it.isFocused
            guard.setFieldFocused(owner, it.isFocused, region)
        }
    } else {
        this
    }
}
