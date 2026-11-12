@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app

import build.raft.app.data.PlatformFile
import build.raft.app.resources.Res
import build.raft.app.resources.desc
import build.raft.app.resources.resolve
import build.raft.app.resources.thread_composer_paste_image
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.UIKit.UIPasteboard

/*
 * The iOS host side of image paste into the composer (RaftIosImagePaste.swift). The host owns the
 * clipboard read (inside the system paste action, so iOS shows no "Allow Paste" prompt), the
 * downscale / orientation / encoding, and the hook on the composer's editor view; the composer
 * opens the images in the image editor (ThreadComposerImagePasteRequests).
 */

/**
 * Images and no text on the clipboard: the one rule for the edit menu's Paste, ⌘V and the
 * composer's clipboard. A clipboard with text as well pastes the text, as before. Asking does not
 * read the clipboard, so it shows no paste prompt.
 */
fun raftIosClipboardHoldsOnlyImages(): Boolean {
    val board = UIPasteboard.generalPasteboard
    return board.hasImages && !board.hasStrings
}

/** One pasted image the host wrote to its picked-attachments cache. */
class RaftIosPastedImage(
    val id: String,
    val path: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
)

internal fun RaftIosPastedImage.toPlatformFile(): PlatformFile =
    PlatformFile(
        id = id,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        fileRef = NSURL.fileURLWithPath(path).absoluteString,
        // App-owned temporary file: retired after send / removal, as a picked photo's copy is.
        source = ThreadComposerClipboardImageSource,
    )

/** Blocking read of the clipboard's images (at most [raftIosPasteMaxImages]). */
interface RaftIosPastedImageReader {
    fun read(): List<RaftIosPastedImage>
}

/** Told when the composer's editor gains or loses focus (the host then hooks that editor view). */
interface RaftIosComposerEditorFocusListener {
    fun composerEditorFocusChanged(focused: Boolean)
}

object RaftIosComposerImagePasteHost {
    init {
        ThreadComposerImagePasteRequests.discardFiles = { files ->
            files.forEach { file ->
                file.fileRef?.let { NSURL.URLWithString(it) }?.let { NSFileManager.defaultManager.removeItemAtURL(it, null) }
            }
        }
    }

    var reader: RaftIosPastedImageReader? = null

    fun setFocusListener(listener: RaftIosComposerEditorFocusListener?) {
        ThreadComposerImagePasteRequests.focusListener = listener?.let { l -> { focused: Boolean -> l.composerEditorFocusChanged(focused) } }
    }
}

/**
 * The composer's edit menu item for paste with only images on the clipboard (RaftIosImagePaste.swift
 * adds it where the menu has no Paste), in the app language.
 */
fun raftComposerPasteImageLabel(): String = Res.string.thread_composer_paste_image.desc().resolve()

/** Most images one paste takes. */
fun raftIosPasteMaxImages(): Int = ThreadComposerImagePasteMaxImages

/**
 * The host's paste action is about to attach images. False when this press was already taken
 * (⌘V reaches both the system paste action and the composer's key handler): then do nothing.
 */
fun raftComposerImagePasteClaim(): Boolean = ThreadComposerImagePasteRequests.claim()

/** Images the host read in its paste action; the focused composer opens them in the editor. */
fun raftComposerImagesPasted(images: List<RaftIosPastedImage>) {
    if (images.isEmpty()) return
    ThreadComposerImagePasteRequests.deliver(images.map { it.toPlatformFile() })
}
