package build.raft.app

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
