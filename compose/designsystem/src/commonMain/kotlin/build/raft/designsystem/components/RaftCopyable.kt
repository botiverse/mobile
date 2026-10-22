package build.raft.designsystem.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.debugInspectorInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import build.raft.designsystem.theme.LocalRaftStrings

/**
 * What a long-press copy ([raftCopyable]) does with the text: put it on the clipboard and
 * tell the person it worked ("Copied"). The design system has no toast host, so an app
 * provides one round its screens through [LocalRaftCopyHandler] (Raft: the host pasteboard
 * plus the host toast). Without one the text goes to Compose's clipboard, unconfirmed.
 */
fun interface RaftCopyHandler {
    fun copy(text: String)
}

/** The enclosing [RaftCopyHandler]; null copies to the Compose clipboard with no confirmation. */
val LocalRaftCopyHandler = staticCompositionLocalOf<RaftCopyHandler?> { null }

/**
 * The enclosing copy action: [LocalRaftCopyHandler], else the Compose clipboard.
 * For a component's own Copy action (menu item, button) that should behave like [raftCopyable].
 */
@Composable
fun rememberRaftCopyAction(): (String) -> Unit {
    val handler = LocalRaftCopyHandler.current

    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    return remember(handler, clipboard) {
        { text -> handler?.copy(text) ?: clipboard.setText(AnnotatedString(text)) }
    }
}

/**
 * Long-press to copy an identifier value (a computer, agent or model name, a handle, an ID):
 * the long press gives the `LongPress` haptic and copies [text] (the raw value, not the label
 * or a status suffix) through [rememberRaftCopyAction]. Screen readers get a "Copy" custom
 * action ([build.raft.designsystem.theme.RaftStrings.copy]).
 *
 * - [onClick]: a value that is also a link (a computer name opening the computer) keeps its
 *   tap and its press ink (the ambient indication, as `clickable`); the long press copies.
 * - No [onClick]: the value stays plain text, with no press look and no click action, so a
 *   fact that can't be changed still doesn't look clickable.
 * - [enabled] false or empty [text]: no copy; [onClick] still taps.
 *
 * Not for message bodies (they have their own long-press menu), buttons or status words.
 */
fun Modifier.raftCopyable(
    text: String?,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onClick: (() -> Unit)? = null,
): Modifier {
    if (!enabled || text.isNullOrEmpty()) {
        return if (onClick != null) this.clickable(onClickLabel = onClickLabel, onClick = onClick) else this
    }
    return this.composed(
        inspectorInfo =
            debugInspectorInfo {
                name = "raftCopyable"
                properties["text"] = text
                properties["onClick"] = onClick
            },
    ) {
        val copy = rememberRaftCopyAction()
        val haptics = LocalHapticFeedback.current
        val copyLabel = LocalRaftStrings.current.copy
        val currentText by rememberUpdatedState(text)
        val longPressCopy: () -> Unit = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            copy(currentText)
        }
        val currentLongPress by rememberUpdatedState(longPressCopy)
        val gestures =
            if (onClick != null) {
                @OptIn(ExperimentalFoundationApi::class)
                Modifier.combinedClickable(
                    onClickLabel = onClickLabel,
                    onLongClickLabel = copyLabel,
                    // The haptic is ours (longPressCopy), so a copy buzzes once either way.
                    hapticFeedbackEnabled = false,
                    onLongClick = longPressCopy,
                    onClick = onClick,
                )
            } else {
                Modifier
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { currentLongPress() }) }
                    .semantics {
                        onLongClick(label = copyLabel) {
                            copy(currentText)
                            true
                        }
                    }
            }
        gestures.semantics {
            customActions = listOf(CustomAccessibilityAction(copyLabel) {
                copy(currentText)
                true
            })
        }
    }
}
