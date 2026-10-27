package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftSheet
import build.raft.designsystem.components.RaftSheetSurface
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/*
 * "Select Text" from the long-press menu (web: botiverse/slock #11467, MessageSelectTextDrawer):
 * a bottom sheet holding just the message's Markdown source, nothing selected, with the system's
 * long-press selection and handles, so part of it can be copied or quoted. Copy and Quote take
 * the selection made in the sheet, or the whole message when nothing is selected.
 */

/** The text Copy copies and Quote quotes: the selection in the sheet, else the whole Markdown. */
internal fun threadSelectTextSheetSource(selectedText: String, markdown: String): String =
    selectedText.takeIf { it.isNotBlank() } ?: markdown

/** Quote's Markdown blockquote (`> ` on every line), the same formatter the other quotes use. */
internal fun threadSelectTextSheetQuote(selectedText: String, markdown: String): String =
    formatThreadMessageQuote(threadSelectTextSheetSource(selectedText, markdown))

/**
 * The composer a quote lands in. On mobile each page has one composer and every message in its
 * list belongs to that page's conversation: a channel (or DM) message quotes into the channel
 * composer, a message in a reply thread (its parent included) into that thread's composer.
 * [None]: the page shows no composer (not joined, read-only, a locked joint channel), so the
 * sheet offers Copy only.
 */
internal enum class ThreadSelectTextQuoteTarget {
    ChannelComposer,
    ThreadComposer,
    None
}

internal fun threadSelectTextQuoteTarget(
    surfaceKind: String,
    composerAvailable: Boolean,
): ThreadSelectTextQuoteTarget =
    when {
        !composerAvailable -> ThreadSelectTextQuoteTarget.None
        surfaceKind == RaftThreadSurfaceKind.ReplyThread -> ThreadSelectTextQuoteTarget.ThreadComposer
        else -> ThreadSelectTextQuoteTarget.ChannelComposer
    }

/**
 * The selection a footer button acts on. It is read when the finger goes down on the footer
 * (a tap elsewhere may collapse the selection before the click lands) and dropped when that
 * gesture ends, so a press that slid off a button and never clicked cannot leave a stale
 * selection for a later click (Kirby's web fix). A click with no press in flight (a screen
 * reader's) reads the live selection.
 */
internal class ThreadSelectTextPressSelection {
    private var atPress: String? = null

    fun onPress(selectedText: String) {
        atPress = selectedText
    }

    fun onGestureEnd() {
        atPress = null
    }

    fun take(live: () -> String): String {
        val selected = atPress ?: live()
        atPress = null
        return selected
    }
}

/** Snapshots [selection] for [press] before the buttons see the down; clears it after they saw the up. */
private fun Modifier.threadSelectTextPressSnapshot(
    press: ThreadSelectTextPressSelection,
    selection: RaftNativeTextSelection,
): Modifier =
    pointerInput(press, selection) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            press.onPress(selection.selectedText())
            do {
                // Final pass: the button's click (Main pass, on the up) has already run.
                val event = awaitPointerEvent(PointerEventPass.Final)
            } while (event.changes.any { it.pressed })
            press.onGestureEnd()
        }
    }

/**
 * The sheet in its window. [onCopy] gets the text to copy (the sheet stays open); [onQuote] gets
 * the Markdown quote, and the caller closes the sheet before inserting it, so the composer can
 * take focus. A null [onQuote] (no composer on this page) leaves Copy alone.
 */
@Composable
internal fun ThreadMessageSelectTextSheet(
    markdown: String,
    senderName: String,
    timeLabel: String,
    textStyle: TextStyle,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onQuote: ((String) -> Unit)?,
) {
    RaftDesignSystemTheme {
        ThreadMessageSelectTextSheetContent(
            markdown = markdown,
            senderName = senderName,
            timeLabel = timeLabel,
            textStyle = textStyle,
            onDismiss = onDismiss,
            onCopy = onCopy,
            onQuote = onQuote,
            inWindow = true,
        )
    }
}

/** The sheet; [inWindow] false draws the surface alone (fixtures), bounded by [modifier]. */
@Composable
internal fun ThreadMessageSelectTextSheetContent(
    markdown: String,
    senderName: String,
    timeLabel: String,
    textStyle: TextStyle,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onQuote: ((String) -> Unit)?,
    inWindow: Boolean,
    modifier: Modifier = Modifier,
) {
    val selection = remember { RaftNativeTextSelection() }
    val press = remember { ThreadSelectTextPressSelection() }
    val title = stringResource(Res.string.thread_action_select_text)
    val hint = stringResource(Res.string.thread_select_text_hint)
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val body: @Composable ColumnScope.() -> Unit = {
        val byline = listOf(senderName, timeLabel).filter { it.isNotBlank() }.joinToString(" · ")
        if (byline.isNotEmpty()) {
            RaftText(byline, style = typography.footnote, color = colors.foregroundMuted)
        }
        RaftNativeSelectableText(
            text = markdown,
            // The message body's size and face (the reader's text-size setting), in the theme's ink.
            style = textStyle.copy(color = colors.foreground),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("thread_message_selection_text"),
            selection = selection,
        )
    }
    val actions: @Composable RowScope.() -> Unit = {
        RaftButton(
            text = stringResource(Res.string.common_copy),
            onClick = { onCopy(threadSelectTextSheetSource(press.take(selection::selectedText), markdown)) },
            modifier = Modifier.weight(1f).threadSelectTextPressSnapshot(press, selection),
            size = RaftButtonSize.Lg,
            leadingIcon = { RaftSlotIcon(RaftLucideIconName.Copy) },
        )
        if (onQuote != null) {
            RaftButton(
                text = stringResource(Res.string.thread_action_quote),
                onClick = { onQuote(threadSelectTextSheetQuote(press.take(selection::selectedText), markdown)) },
                modifier = Modifier.weight(1f).threadSelectTextPressSnapshot(press, selection),
                size = RaftButtonSize.Lg,
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.TextQuote) },
            )
        }
    }
    if (inWindow) {
        RaftSheet(
            onDismissRequest = onDismiss,
            title = title,
            modifier = modifier,
            description = hint,
            showClose = true,
            closeLabel = stringResource(Res.string.common_close),
            rowActions = actions,
            content = body,
        )
    } else {
        RaftSheetSurface(
            title = title,
            modifier = modifier,
            description = hint,
            onClose = onDismiss,
            closeLabel = stringResource(Res.string.common_close),
            rowActions = actions,
            content = body,
        )
    }
}
