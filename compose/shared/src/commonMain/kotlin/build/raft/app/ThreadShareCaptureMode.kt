package build.raft.app

import androidx.compose.runtime.compositionLocalOf

/**
 * True while "Generate image" is rasterizing the selected rows (ThreadPage `shareCaptureMode`).
 *
 * A share image is public, so it keeps only what the author wrote; nothing resolved for the
 * viewer goes in (AppFather / Remy / Tenny, 2026-10-26; web `selectScreenshot.ts`
 * `sanitizeClonedMessageForScreenshot` + `STRIP_SELECTORS`). While this is true a message row:
 *  - drops the quote preview of a Raft message link ([ThreadQuotedPermalinkCard]) together
 *    with its top margin; the link itself stays in the body;
 *  - reads no App link previews ([ThreadAppLinkPreviewScope]): its chips go back to the link
 *    the author wrote, and the item card popover, the consent prompt and the pending
 *    placeholder are not composed.
 * The App renders no Artifact cards or "Saved as Artifact" lines yet; once it does, they go
 * behind this flag too.
 */
internal val LocalThreadShareCaptureActive = compositionLocalOf { false }

/** Whether a message row composes its viewer-only quote preview. */
internal fun threadQuotedPermalinkPreviewVisible(
    state: ThreadQuotedPermalinkUiState?,
    shareCaptureActive: Boolean,
): Boolean = state != null && !shareCaptureActive

/** Whether a message row reads (and shows) its App link previews. */
internal fun threadAppLinkPreviewsEnabled(sentMessage: Boolean, shareCaptureActive: Boolean): Boolean =
    sentMessage && !shareCaptureActive
