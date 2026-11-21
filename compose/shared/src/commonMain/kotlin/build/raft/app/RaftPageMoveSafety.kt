package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import build.raft.app.compat.ui.LocalRaftPageActive

/*
 * What a page may change only once Navigation 3 has finished moving it.
 *
 * Navigation 3 renders every page as movable content and moves it into the scene of each push and
 * pop, a frame or more after the stack changes (navigation3 `SceneSetupNavEntryDecorator`; the page
 * is never active then: RaftMobilePageStackDisplay). A node that lives in a subcomposition of the
 * page (a lazy list's row, a SubcomposeLayout) and changes in the frame of the move gets its new
 * modifier while it is detached; Compose applies it on re-attach before the node counts as attached
 * (`LayoutNode.attach`: `applyModifier` before `markAsAttached`, Compose UI 1.12.1, unchanged in
 * 1.14.0-alpha01), so a modifier node that reads a CompositionLocal in its update throws
 * "Cannot read CompositionLocal because the Modifier node is not currently attached":
 * - SelectableTextAnnotatedStringNode.update (Hands 11b1a052): [rememberThreadInPlaceBodySelectable].
 * - TextFieldCoreModifierNode.updateNode, the first time its caret shows (`startCursorJob` reads
 *   LocalCursorBlinkEnabled; foundation 1.12.1, same code in 1.13.0 and 1.14.0-alpha01): a field
 *   focused as its page moved. Main f467aed8 on a cloud phone, which reports a hardware keyboard,
 *   so opening a conversation focuses its composer (ComposerAutofocusPolicy.kt), task
 *   #wg-mobile-sync:#197. [raftPageGatedCursorBrush].
 */

/**
 * Whether [pageActive] has held for [frames] frames, or already held when this was first composed:
 * a page Navigation 3 is done moving. False at once, in the composition the page stops being
 * active, since its move comes only a frame or more after that.
 */
@Composable
internal fun rememberRaftPageActiveForFrames(pageActive: Boolean, frames: Int): Boolean {
    var activeSettled by remember { mutableStateOf(pageActive) }
    LaunchedEffect(pageActive) {
        if (!pageActive) {
            activeSettled = false
            return@LaunchedEffect
        }
        if (activeSettled) return@LaunchedEffect
        repeat(frames) { withFrameNanos {} }
        activeSettled = true
    }
    return pageActive && activeSettled
}

/** Frames a page stays active before its text fields show their caret ([raftPageGatedCursorBrush]). */
internal const val RaftTextFieldCaretActivationFrames = 2

/** A cursor brush that shows no caret: a text field draws and animates none for an unspecified one. */
private val RaftHiddenCaretBrush: Brush = SolidColor(Color.Unspecified)

/**
 * [cursorBrush] on a page that has been active for [RaftTextFieldCaretActivationFrames] frames,
 * else no caret, so a field's caret never shows for the first time in the frame its page moves
 * (see the top of this file). The caret is what starts the cursor job (focused, writeable and a
 * specified brush); focus, the keyboard and typing are untouched. A field on a page sliding in
 * shows its caret once the slide has settled; one first composed on an active page shows it at once.
 */
@Composable
internal fun raftPageGatedCursorBrush(cursorBrush: Brush): Brush =
    if (rememberRaftPageActiveForFrames(LocalRaftPageActive.current, RaftTextFieldCaretActivationFrames)) {
        cursorBrush
    } else {
        RaftHiddenCaretBrush
    }
