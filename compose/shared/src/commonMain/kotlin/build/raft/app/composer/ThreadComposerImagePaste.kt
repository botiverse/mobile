package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.app.data.PlatformFile
import build.raft.app.base.BridgeModule
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

/** The clipboard the composer's editor gets: [ThreadComposerImagePasteClipboard] where [raftPlatformComposerUsesImagePasteClipboard]. */
@Composable
internal fun rememberThreadComposerClipboard(): Clipboard {
    val platform = LocalClipboard.current
    return remember(platform) {
        if (raftPlatformComposerUsesImagePasteClipboard()) ThreadComposerImagePasteClipboard(platform) else platform
    }
}

/** Reports the composer editor's focus for host image paste ([ThreadComposerImagePasteRequests.editorFocusChanged]). */
internal fun Modifier.threadComposerImagePasteFocus(owner: Any): Modifier =
    onFocusChanged { ThreadComposerImagePasteRequests.editorFocusChanged(owner, it.hasFocus) }

/**
 * Whether [changed] is [previous] with exactly the placeholder pasted over its selection (a paste
 * inserts at the selection and leaves the caret after it). Anything else, the user's own text
 * included, is an ordinary edit and is kept as typed.
 */
internal fun threadComposerIsImagePastePlaceholderEdit(
    previousText: CharSequence,
    previousSelection: TextRange,
    changedText: CharSequence,
    changedSelection: TextRange,
): Boolean {
    val start = previousSelection.min.coerceIn(0, previousText.length)
    val end = previousSelection.max.coerceIn(0, previousText.length)
    val expected = previousText.toString().replaceRange(start, end, ThreadComposerImagePastePlaceholder)
    return changedText.toString() == expected &&
        changedSelection == TextRange(start + ThreadComposerImagePastePlaceholder.length)
}

internal fun threadComposerIsImagePastePlaceholderEdit(previous: TextFieldValue, changed: TextFieldValue): Boolean =
    threadComposerIsImagePastePlaceholderEdit(
        previousText = previous.text,
        previousSelection = previous.selection,
        changedText = changed.text,
        changedSelection = changed.selection,
    )

/** Which path asked for a press's one image paste ([ThreadComposerImagePasteRequests.claim]). */
internal enum class ThreadComposerImagePasteRoute(val wire: String) {
    /** The host's hook on the editor's `paste:` (iOS: ⌘V, the edit menu's Paste). */
    SystemPaste("system_paste"),
    /** The composer's own paste-shortcut key handler (desktop). */
    KeyHandler("key_handler"),
    /** The composer clipboard's placeholder pasted by the text field. */
    Placeholder("placeholder"),
}

/**
 * Always-on diagnostics for image paste into the composer: which path ran, what the clipboard
 * holds by kind and count, and which path claimed or lost a press. Never clipboard content.
 * Goes to the same channel as `bridge.log` (iOS and Android diagnostics ring).
 */
internal object ThreadComposerImagePasteLog {
    var sink: (String) -> Unit = { raftLogInfo(BridgeModule.BRIDGE_LOG_TAG, it) }

    fun record(line: String) = sink(line)
}

/**
 * Image pastes into the composer from outside Compose (iOS: the editor's system edit menu and ⌘V,
 * RaftIosImagePaste.swift). The host reads the images inside the paste action (no "Allow Paste"
 * prompt) and hands them over here; the focused composer opens them in the image editor.
 */
internal object ThreadComposerImagePasteRequests {
    private val countFlow = MutableStateFlow(0)

    /** Bumped for each delivered paste; the focused composer reacts to it via [observeCount]. */
    val count: Int
        get() = countFlow.value

    @Composable
    fun observeCount(): Int = countFlow.collectAsState().value

    private var pending: List<PlatformFile>? = null
    private var lastClaim: TimeSource.Monotonic.ValueTimeMark? = null
    private var lastClaimRoute: ThreadComposerImagePasteRoute? = null

    /** The composer editor that has focus, and who to tell when that changes (iOS host). */
    var focusedOwner: Any? = null
        private set
    var focusListener: ((Boolean) -> Unit)? = null

    /**
     * One image paste per key press / menu tap: whichever path comes first claims it and a second
     * one within [ThreadComposerImagePasteDedupeMs] is dropped. On iOS the composer's key handler
     * leaves ⌘V to the system paste action ([raftPlatformComposerKeyHandlerPastesAttachments]), so
     * there the key handler never takes a press from it. Logs the route and the outcome.
     */
    fun claim(
        route: ThreadComposerImagePasteRoute,
        now: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow(),
    ): Boolean {
        val last = lastClaim
        val sinceLastMs = last?.let { (now - it).inWholeMilliseconds }
        val granted = sinceLastMs == null || sinceLastMs !in 0 until ThreadComposerImagePasteDedupeMs
        ThreadComposerImagePasteLog.record(
            "composer_image_paste_claim route=${route.wire} granted=$granted " +
                "previousRoute=${lastClaimRoute?.wire ?: "none"} sinceLastMs=${sinceLastMs ?: -1}"
        )
        if (!granted) return false
        lastClaim = now
        lastClaimRoute = route
        return true
    }

    /** Deletes a host paste's temporary files that no composer took (iOS host). */
    var discardFiles: ((List<PlatformFile>) -> Unit)? = null

    /**
     * A host paste for the focused composer. With no composer focused (the keyboard went away
     * while the images were being encoded) the images are dropped and their files deleted, so a
     * later paste never opens them instead of the clipboard's current images.
     */
    fun deliver(files: List<PlatformFile>) {
        if (focusedOwner == null) {
            discard(files)
            return
        }
        discardPending()
        pending = files
        countFlow.update { it + 1 }
    }

    private fun discardPending() {
        pending?.let(::discard)
        pending = null
    }

    private fun discard(files: List<PlatformFile>) {
        if (files.isNotEmpty()) discardFiles?.invoke(files)
    }

    /** The files a host paste delivered, once; null when the clipboard should be read instead. */
    fun takePending(): List<PlatformFile>? = pending.also { pending = null }

    fun editorFocusChanged(owner: Any, hasFocus: Boolean) {
        val before = focusedOwner
        when {
            hasFocus -> focusedOwner = owner
            before === owner -> {
                focusedOwner = null
                // Not taken before the composer lost focus: stale for the next paste.
                discardPending()
            }
            else -> return
        }
        if (before !== focusedOwner) focusListener?.invoke(focusedOwner != null)
    }
}

internal const val ThreadComposerImagePasteDedupeMs = 600L
