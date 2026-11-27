package build.raft.app

import build.raft.app.thread.dispatch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The composer event a tray drag (or a screen reader's Move left / Move right) sends: its value
 * is the tray's attachment ids in their new order ([encodeThreadComposerAttachmentOrder]). The
 * page turns it into [SharedThreadComposerDraftIntent.ReorderAttachments].
 */
internal const val ThreadComposerReorderAttachmentsAction = "reorderAttachments"

private val attachmentOrderSerializer = ListSerializer(String.serializer())

internal fun encodeThreadComposerAttachmentOrder(ids: List<String>): String =
    Json.encodeToString(attachmentOrderSerializer, ids)

/** The order an event carried, or null when it carried none. */
internal fun decodeThreadComposerAttachmentOrder(value: String?): List<String>? =
    value
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Json.decodeFromString(attachmentOrderSerializer, it) }.getOrNull() }
        ?.filter { it.isNotBlank() }

/**
 * The tray's new order ([ThreadComposerReorderAttachmentsAction]'s value) into the draft store: it
 * becomes the send's `attachmentIds` order (web `arrayMove` of `pendingFiles`). Kept out of the
 * thread page's event handler, which is near the JVM method-size ceiling.
 */
internal fun SharedThreadComposerDraftViewModel.reorderThreadComposerAttachments(value: String?, log: (String) -> Unit) {
    val order = decodeThreadComposerAttachmentOrder(value)
    if (order == null) {
        log("thread_composer_attachment_reorder_rejected reason=missing_order")
        return
    }
    log("thread_composer_attachment_reorder count=${order.size}")
    dispatch(SharedThreadComposerDraftIntent.ReorderAttachments(order))
}
