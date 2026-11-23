package build.raft.app

import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Identity of a file just saved to the device (Android Downloads or iOS Files + local cache) that
 * the completion toast's "Open" button can hand to a system app via `openAttachment`.
 */
internal data class RaftSavedDownloadOpenTarget(
    val attachmentId: String,
    val fileName: String,
    val owner: AttachmentOwnerLease,
    val mimeType: String? = null,
)

/**
 * App-scoped coroutine scope for opening a saved file from a completion toast, so tapping "Open"
 * still works after the originating page closes and `RaftCmpToasts` hands the toast to the active
 * window.
 */
internal val raftSavedDownloadToastOpenScope: CoroutineScope by lazy {
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}

/**
 * Resolves the saved download's display name from the host's save payload when available (e.g., an
 * Artifact download named by `Content-Disposition`), falling back to [fallbackFileName].
 */
internal fun raftSavedDownloadTargetFromResult(
    attachmentId: String,
    fallbackFileName: String,
    owner: AttachmentOwnerLease,
    mimeType: String?,
    result: JSONObject?,
): RaftSavedDownloadOpenTarget {
    val savedFileName =
        result?.optString("fileName")?.trim()?.takeIf { it.isNotBlank() }
            ?: fallbackFileName.trim().ifBlank { "attachment" }
    return RaftSavedDownloadOpenTarget(
        attachmentId = attachmentId.trim(),
        fileName = savedFileName,
        owner = owner,
        mimeType = mimeType?.trim()?.takeIf { it.isNotBlank() },
    )
}

internal fun raftCanOfferSavedDownloadOpenAction(
    target: RaftSavedDownloadOpenTarget?,
    openLabel: String,
    hasDesktopDownloadBar: Boolean,
    ownerAllowed: Boolean,
): Boolean =
    !hasDesktopDownloadBar &&
        target != null &&
        target.attachmentId.isNotBlank() &&
        openLabel.isNotBlank() &&
        ownerAllowed

/**
 * Shows the post-download confirmation toast, attaching an "Open" button on mobile (Android/iOS)
 * so the user can immediately open the saved file in a system app that handles it.
 *
 * Opening uses `requirePublicDownload = true` (never re-downloading over the network) and
 * `retainAfterPageDestroyed = true` on [openScope], while re-checking [ProcessAttachmentAuthScopes]
 * at tap time so a sign-out or account switch fails closed.
 */
internal fun raftShowSavedDownloadToast(
    bridge: BridgeModule,
    message: String,
    openLabel: String,
    target: RaftSavedDownloadOpenTarget?,
    openResultTerms: RaftAttachmentOpenResultTerms,
    hasDesktopDownloadBar: Boolean = raftPlatformSavedFileHost() != null,
    openScope: CoroutineScope = raftSavedDownloadToastOpenScope,
) {
    val canOfferOpen =
        raftCanOfferSavedDownloadOpenAction(
            target = target,
            openLabel = openLabel,
            hasDesktopDownloadBar = hasDesktopDownloadBar,
            ownerAllowed = target != null && ProcessAttachmentAuthScopes.allows(target.owner),
        )
    if (!canOfferOpen || target == null) {
        bridge.toast(message)
        return
    }
    val opening = atomic(false)
    bridge.toastWithAction(
        content = message,
        actionLabel = openLabel,
        onAction = {
            if (!ProcessAttachmentAuthScopes.allows(target.owner)) return@toastWithAction
            if (!opening.compareAndSet(false, true)) return@toastWithAction
            openScope.launch {
                val result =
                    bridge.openAttachment(
                        url = "",
                        attachmentId = target.attachmentId,
                        fileName = target.fileName,
                        owner = target.owner,
                        mimeType = target.mimeType,
                        requirePublicDownload = true,
                        retainAfterPageDestroyed = true,
                    )
                val status = result?.optString("status").orEmpty()
                val failureMessage = raftAttachmentOpenResultMessage(status, openResultTerms)
                if (failureMessage != null) {
                    bridge.log(
                        "saved_download_toast_open_failed attachment=${target.attachmentId.take(8)} " +
                            "status=${raftAttachmentOpenDiagnosticStatus(status)} " +
                            "errorKind=${raftAttachmentOpenDiagnosticErrorKind(result?.optString("errorKind"))}"
                    )
                    bridge.toast(failureMessage)
                }
            }
        },
    )
}
