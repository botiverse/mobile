package build.raft.app.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard

/**
 * Rendered message text carries characters that exist only for layout: inline code's wrap marks
 * (a zero-width space where the web breaks, a word joiner where it does not,
 * `withRaftInlineCodeWebBreaks`), the word joiners that glue a chip to its padding, and the object
 * replacement character standing for each reserved padding gap or inline placeholder. None of them
 * is in the source, so none may leave the app: every copy of rendered text goes through this.
 *
 * Copies of the source (Copy Markdown, a code block's copy button, share / forward) never hold
 * rendered text and need no cleaning.
 */
internal fun String.raftCopyText(): String =
    if (none(::isRaftLayoutOnlyChar)) this else filterNot(::isRaftLayoutOnlyChar)

private fun isRaftLayoutOnlyChar(char: Char): Boolean =
    char == '\u200B' || char == '\u2060' || char == '\uFFFC'

/** [entry] with its plain text passed through [raftCopyText]; the same entry when nothing changes. */
internal expect fun raftCleanCopyClipEntry(entry: ClipEntry): ClipEntry

/**
 * The platform clipboard with every write cleaned ([raftCopyText]). Compose's own selection copy
 * (the system text menu, Ctrl/Cmd+C) writes through [LocalClipboard], so a selectable surface
 * showing rendered Markdown provides this ([RaftCleanCopyClipboardScope]).
 */
internal class RaftCleanCopyClipboard(private val delegate: Clipboard) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = delegate.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        delegate.setClipEntry(clipEntry?.let(::raftCleanCopyClipEntry))
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override val nativeClipboard: NativeClipboard
        get() = delegate.nativeClipboard
}

/** Provides [RaftCleanCopyClipboard] to [content] (a SelectionContainer over rendered Markdown). */
@Composable
internal fun RaftCleanCopyClipboardScope(content: @Composable () -> Unit) {
    val platform = LocalClipboard.current
    val clean = remember(platform) { platform as? RaftCleanCopyClipboard ?: RaftCleanCopyClipboard(platform) }
    CompositionLocalProvider(LocalClipboard provides clean, content = content)
}
