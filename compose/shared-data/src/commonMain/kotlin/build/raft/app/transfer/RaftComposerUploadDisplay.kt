package build.raft.app.transfer

import build.raft.app.resources.*


/** What a composer attachment chip says about its upload (the web's chip states). */
enum class RaftComposerUploadPhase { Checking, Uploading, Finishing, Failed }

/**
 * The upload state the composer attachment tray renders, derived from the upload meter only
 * ([raftComposerUploadDisplay]); the tray does no math of its own.
 *
 * - [percent]: the bar value. 0 before the first byte, 1..99 while uploading, 99 while
 *   finishing, never 100 (a server confirmed upload is a ready attachment with no overlay). Null
 *   means no bar: checking, failed, or an unknown total.
 * - [label]: "Checking file…", "Uploading" (0% or unknown), "42%", "Finishing upload…" (finishing
 *   or 99%), "Upload failed". Render with `label.localized()`.
 */
data class RaftComposerUploadDisplay(
    val phase: RaftComposerUploadPhase,
    val percent: Int?,
    val label: StringDesc
)

/**
 * The chip state of one composer attachment: [status] is the attachment's status word
 * ("queued", "uploading", "failed", "ready"), [transfer] its upload meter state, null until the
 * upload has started sending (the file is still being checked: size, limits, upload session).
 * Null for a ready attachment and for one queued behind others (no overlay, like the web).
 *
 * Mirrors the web composer (MessageInput.tsx): `Math.max(1, Math.min(99, …))` while sending,
 * "Finishing upload…" at 99 and above until the server confirms, "Uploading" before the first
 * percent.
 */
fun raftComposerUploadDisplay(status: String, transfer: RaftTransferProgress?): RaftComposerUploadDisplay? =
    when (status) {
        "failed" ->
            RaftComposerUploadDisplay(RaftComposerUploadPhase.Failed, null, Res.string.thread_composer_attachment_upload_failed.desc())
        "uploading" -> {
            val percent = transfer?.percent
            when {
                transfer == null ->
                    RaftComposerUploadDisplay(RaftComposerUploadPhase.Checking, null, Res.string.thread_composer_attachment_checking.desc())
                transfer?.phase == RaftTransferPhase.Finishing || (percent != null && percent >= 99) ->
                    RaftComposerUploadDisplay(RaftComposerUploadPhase.Finishing, 99, Res.string.thread_composer_attachment_finishing.desc())
                percent == null || percent <= 0 ->
                    // The web draws the empty bar while nothing is sent yet; no bar at all when the
                    // total is unknown.
                    RaftComposerUploadDisplay(
                        RaftComposerUploadPhase.Uploading,
                        if (transfer.bytesTotal != null) 0 else null,
                        Res.string.thread_composer_attachment_uploading.desc()
                    )
                else -> RaftComposerUploadDisplay(RaftComposerUploadPhase.Uploading, percent, StringDesc.Raw("$percent%"))
            }
        }
        else -> null
    }
