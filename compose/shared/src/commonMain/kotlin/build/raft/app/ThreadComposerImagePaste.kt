package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.app.data.PlatformFile
import kotlin.time.TimeSource

/**
 * Paste on a phone with only an image on the clipboard (a screenshot's "Copy and Delete"). The text
 * field offers Paste only for text, so while the clipboard holds images and no text the composer's
 * clipboard reports this placeholder ([raftPlatformImagePasteClipEntry]). A paste of exactly it
 * opens the images in the editor ([threadComposerIsImagePastePlaceholderEdit]); the placeholder
 * never goes into the message.
 */
internal const val ThreadComposerImagePastePlaceholder = "raft-paste-image"

/** Most images one paste takes (the rest of the clipboard is left out). */
internal const val ThreadComposerImagePasteMaxImages = 9

/** The composer's clipboard: [platform]'s, with the image placeholder while it holds only images. */
internal class ThreadComposerImagePasteClipboard(private val platform: Clipboard) : Clipboard {
    /** The placeholder was handed out and not yet pasted (only then is it an image paste). */
    private var placeholderServed = false

    override suspend fun getClipEntry(): ClipEntry? {
        val placeholder = raftPlatformImagePasteClipEntry()
        placeholderServed = placeholder != null
        return placeholder ?: platform.getClipEntry()
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) = platform.setClipEntry(clipEntry)

    /** True once for the paste of the placeholder this clipboard handed out. */
    fun consumePlaceholderPaste(): Boolean = placeholderServed.also { placeholderServed = false }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override val nativeClipboard: NativeClipboard
        get() = platform.nativeClipboard
}

/**
 * Whether [changed] is [previous] with exactly the placeholder pasted over its selection (a paste
 * inserts at the selection and leaves the caret after it). Anything else, the user's own text
 * included, is an ordinary edit and is kept as typed.
 */
internal fun threadComposerIsImagePastePlaceholderEdit(previous: TextFieldValue, changed: TextFieldValue): Boolean {
    val start = previous.selection.min.coerceIn(0, previous.text.length)
    val end = previous.selection.max.coerceIn(0, previous.text.length)
    val expected = previous.text.replaceRange(start, end, ThreadComposerImagePastePlaceholder)
    return changed.text == expected &&
        changed.selection == TextRange(start + ThreadComposerImagePastePlaceholder.length)
}

/**
 * Image pastes into the composer from outside Compose (iOS: the editor's system edit menu and ⌘V,
 * RaftIosImagePaste.swift). The host reads the images inside the paste action (no "Allow Paste"
 * prompt) and hands them over here; the focused composer opens them in the image editor.
 */
internal object ThreadComposerImagePasteRequests {
    /** Bumped for each delivered paste; the focused composer reacts to it. */
    var count by mutableIntStateOf(0)
        private set

    private var pending: List<PlatformFile>? = null
    private var lastClaim: TimeSource.Monotonic.ValueTimeMark? = null

    /** The composer editor that has focus, and who to tell when that changes (iOS host). */
    var focusedOwner: Any? = null
        private set
    var focusListener: ((Boolean) -> Unit)? = null

    /**
     * One image paste per key press / menu tap: ⌘V can reach both the system paste action and the
     * composer's key handler, so whichever comes first claims it and the other is dropped.
     */
    fun claim(now: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()): Boolean {
        val last = lastClaim
        if (last != null && (now - last).inWholeMilliseconds in 0 until ThreadComposerImagePasteDedupeMs) return false
        lastClaim = now
        return true
    }

    fun deliver(files: List<PlatformFile>) {
        pending = files
        count++
    }

    /** The files a host paste delivered, once; null when the clipboard should be read instead. */
    fun takePending(): List<PlatformFile>? = pending.also { pending = null }

    fun editorFocusChanged(owner: Any, hasFocus: Boolean) {
        val before = focusedOwner
        when {
            hasFocus -> focusedOwner = owner
            before === owner -> focusedOwner = null
            else -> return
        }
        if (before !== focusedOwner) focusListener?.invoke(focusedOwner != null)
    }
}

internal const val ThreadComposerImagePasteDedupeMs = 600L
