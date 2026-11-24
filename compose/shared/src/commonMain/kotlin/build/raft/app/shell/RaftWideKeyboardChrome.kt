package build.raft.app

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.compat.ui.LocalRaftHostImeInsets
import build.raft.app.compat.ui.raftImeBottomPx
import build.raft.designsystem.theme.RaftTheme

/*
 * #338 (Kirby 11-24, raft-design responsive.md): a wide window keeps the wide shell however short it is (a phone
 * held sideways, 914 x 411). With the keyboard up the room above it goes to typing:
 *  1. the shell's top bar steps aside, as the phone's bottom tab bar does ([RaftAppShellSurface]);
 *  2. if the conversation's head, the composer and a few rows of messages still don't fit, the head steps aside too
 *     ([raftWideKeyboardHidesHead]);
 *  3. if even the composer and a few rows don't fit, the composer keeps its height on the keyboard over what is
 *     above it ([raftThreadComposerKeptPlacement], #5630).
 * The rail neither scrolls nor moves: the keyboard covers it. With the keyboard down everything is back at once, no
 * animation.
 */

/**
 * The room above the keyboard (window less its top safe area and the keyboard) while the wide shell's top bar has
 * stepped aside for the keyboard; null with the keyboard down, or outside the wide shell (a phone, a fixture). A
 * [State] so a page reads it in a derived state and recomposes only when its own decision flips, not every keyboard
 * frame.
 */
internal val LocalRaftWideKeyboardRoom = compositionLocalOf<State<Dp?>?> { null }

/**
 * How much of the messages stays above the composer before the head steps aside (#338, "a few rows"): about three
 * lines of messages.
 */
internal val RAFT_WIDE_KEYBOARD_MESSAGE_ROOM = 96.dp

/**
 * Whether the conversation's head steps aside on the wide shell with the keyboard up: when [roomAboveKeyboard] can't
 * hold the [headHeight], the composer's kept height ([RAFT_THREAD_COMPOSER_KEPT_HEIGHT]) and a few rows of messages
 * ([RAFT_WIDE_KEYBOARD_MESSAGE_ROOM]). Null room (keyboard down, not the wide shell): it stays.
 */
internal fun raftWideKeyboardHidesHead(roomAboveKeyboard: Dp?, headHeight: Dp): Boolean =
    roomAboveKeyboard != null &&
        roomAboveKeyboard < headHeight + RAFT_THREAD_COMPOSER_KEPT_HEIGHT + RAFT_WIDE_KEYBOARD_MESSAGE_ROOM

/**
 * The shortest keyboard the wide shell steps aside for. An iPad with a hardware keyboard still reports the short
 * shortcut bar (about 55pt) as a keyboard; that leaves the page all the room it needs, so the top bar stays.
 */
internal val RAFT_WIDE_KEYBOARD_MIN_HEIGHT = 100.dp

/**
 * The room above the keyboard in a window [windowHeight] tall under [topSafeArea], [keyboard] tall; null without a
 * keyboard (or one shorter than [RAFT_WIDE_KEYBOARD_MIN_HEIGHT]).
 */
internal fun raftWideKeyboardRoom(windowHeight: Dp, topSafeArea: Dp, keyboard: Dp): Dp? =
    if (keyboard < RAFT_WIDE_KEYBOARD_MIN_HEIGHT || windowHeight <= 0.dp) null
    else (windowHeight - topSafeArea - keyboard).coerceAtLeast(0.dp)

/**
 * The shell's live [LocalRaftWideKeyboardRoom]: read per keyboard frame inside a derived state, so only its readers
 * update.
 */
@Composable
internal fun rememberRaftWideKeyboardRoom(topSafeArea: Dp): State<Dp?> {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val host = LocalRaftHostImeInsets.current
    val windowInfo = LocalWindowInfo.current
    val topSafe by rememberUpdatedState(topSafeArea)
    return remember(density, ime, host, windowInfo) {
        derivedStateOf {
            with(density) {
                raftWideKeyboardRoom(
                    windowHeight = windowInfo.containerSize.height.toDp(),
                    topSafeArea = topSafe,
                    keyboard = raftImeBottomPx(ime, host, density).toDp(),
                )
            }
        }
    }
}

/** Whether this page's head steps aside for the keyboard on the wide shell ([raftWideKeyboardHidesHead]). */
@Composable
internal fun raftWideKeyboardHeadHidden(): Boolean {
    val room = LocalRaftWideKeyboardRoom.current ?: return false
    val headHeight by rememberUpdatedState(RaftTheme.controls.headHeight)
    val hidden by remember(room) { derivedStateOf { raftWideKeyboardHidesHead(room.value, headHeight) } }
    return hidden
}
