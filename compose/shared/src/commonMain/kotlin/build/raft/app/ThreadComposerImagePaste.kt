package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Paste on a phone with only an image on the clipboard (a screenshot's "Copy and Delete"). The text
 * field offers Paste only for text, so while the clipboard holds images and no text the composer's
 * clipboard reports this placeholder ([raftPlatformImagePasteClipEntry]). A paste of it attaches the
 * images ([threadComposerTakeImagePaste]); the placeholder never stays in the message.
 */
internal const val ThreadComposerImagePastePlaceholder = "raft-paste-image"

/** The composer's clipboard: [platform]'s, with the image placeholder while it holds only images. */
internal class ThreadComposerImagePasteClipboard(private val platform: Clipboard) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = raftPlatformImagePasteClipEntry() ?: platform.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) = platform.setClipEntry(clipEntry)

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override val nativeClipboard: NativeClipboard
        get() = platform.nativeClipboard
}

/**
 * [value] without the image placeholder, the caret where it was pasted; null when [value] has none.
 * The composer then attaches the clipboard's images (ThreadComposerPasteAttachmentsAction) instead.
 */
internal fun threadComposerTakeImagePaste(value: TextFieldValue): TextFieldValue? {
    val at = value.text.indexOf(ThreadComposerImagePastePlaceholder)
    if (at < 0) return null
    return TextFieldValue(value.text.replace(ThreadComposerImagePastePlaceholder, ""), TextRange(at))
}

/**
 * System pastes into the editor that found only images (iOS: the editor's long-press edit menu,
 * which does not go through the composer's clipboard); the focused composer attaches them.
 */
internal object ThreadComposerImagePasteRequests {
    var count by mutableIntStateOf(0)
}
