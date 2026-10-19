package build.raft.app.data

import build.raft.app.optBoolean
import build.raft.app.optDouble
import build.raft.app.optJsonArray
import build.raft.app.optJsonObject
import build.raft.app.optString
import com.tencent.kuikly.compose.ui.geometry.Offset
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * task #180: cross-page contract for shell-rendered overlays.
 *
 * A Kuikly `@Page` child (the thread column, the detail pane) is its own composition whose visible
 * surface is only its own pane. A `Modal` opened inside it is therefore clipped to that pane -- the
 * device evidence was unambiguous across four rounds: nothing drawn outside the window's left 400px
 * strip could ever appear, whatever coordinates were passed.
 *
 * So the child asks the SHELL to draw. The shell's composition spans the window, and it already
 * hosts window-level overlays (`SlockFloatingOverlayHost`, e.g. the rail server switcher).
 *
 * Lifecycle:
 *   page  --Request--> shell      (open, with a window-space anchor)
 *   shell --Result---> page       (selected item id, or dismissed)
 *   page  --Cancel---> shell      (page going away / navigating)
 *   shell --Dismissed-> page      (shell consumed Back while open)
 *
 * Staleness: every message carries the same [requestId]. The shell ignores a stale id, and a page
 * ignores a result whose id it did not issue, so a late delivery can never mutate the wrong page.
 */
internal const val SLOCK_SHELL_OVERLAY_REQUEST_EVENT = "slockShellOverlayRequest"
internal const val SLOCK_SHELL_OVERLAY_RESULT_EVENT = "slockShellOverlayResult"

internal const val SHELL_OVERLAY_MESSAGE_TYPE = "type"
internal const val SHELL_OVERLAY_REQUEST_ID = "requestId"
internal const val SHELL_OVERLAY_PAGE_KEY = "pageKey"
internal const val SHELL_OVERLAY_ANCHOR_X = "anchorX"
internal const val SHELL_OVERLAY_ANCHOR_Y = "anchorY"
internal const val SHELL_OVERLAY_ITEMS = "items"
internal const val SHELL_OVERLAY_SELECTED_ID = "selectedId"
internal const val SHELL_OVERLAY_DISMISSED = "dismissed"

internal const val SHELL_OVERLAY_TYPE_REQUEST = "request"
internal const val SHELL_OVERLAY_TYPE_CANCEL = "cancel"

/** One row of the menu the shell draws. Mirrors the fields the shell's dropdown needs. */
internal data class SlockShellOverlayItem(
    val id: String,
    val label: String,
    val iconName: String? = null,
    val enabled: Boolean = true,
)

/**
 * A page's request for the shell to draw a menu. [anchor] is in WINDOW space -- the page has already
 * resolved the popover anchor, so the shell must not convert it again.
 */
internal data class SlockShellOverlayRequest(
    val requestId: String,
    val pageKey: String,
    val anchor: Offset,
    val items: List<SlockShellOverlayItem>,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put(SHELL_OVERLAY_MESSAGE_TYPE, SHELL_OVERLAY_TYPE_REQUEST)
            put(SHELL_OVERLAY_REQUEST_ID, requestId)
            put(SHELL_OVERLAY_PAGE_KEY, pageKey)
            put(SHELL_OVERLAY_ANCHOR_X, anchor.x.toDouble())
            put(SHELL_OVERLAY_ANCHOR_Y, anchor.y.toDouble())
            put(
                SHELL_OVERLAY_ITEMS,
                buildJsonArray {
                    items.forEach { item ->
                        add(
                            buildJsonObject {
                                put("id", item.id)
                                put("label", item.label)
                                put("enabled", item.enabled)
                                item.iconName?.let { put("icon", it) }
                            }
                        )
                    }
                }
            )
        }
}

/** The shell's answer to a request: the chosen id, or a dismissal. */
internal data class SlockShellOverlayResult(
    val requestId: String,
    val selectedId: String? = null,
    val dismissed: Boolean = false,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put(SHELL_OVERLAY_MESSAGE_TYPE, SHELL_OVERLAY_TYPE_CANCEL.takeIf { dismissed } ?: "result")
            put(SHELL_OVERLAY_REQUEST_ID, requestId)
            selectedId?.let { put(SHELL_OVERLAY_SELECTED_ID, it) }
            put(SHELL_OVERLAY_DISMISSED, dismissed)
        }
}

internal fun cancelSlockShellOverlayJson(requestId: String): JsonObject =
    buildJsonObject {
        put(SHELL_OVERLAY_MESSAGE_TYPE, SHELL_OVERLAY_TYPE_CANCEL)
        put(SHELL_OVERLAY_REQUEST_ID, requestId)
    }

internal fun JsonObject.shellOverlayRequestIdOrNull(): String? =
    optString(SHELL_OVERLAY_REQUEST_ID).trim().takeIf { it.isNotEmpty() }

/**
 * Whether a page should act on [incoming]: only when it issued [currentRequestId].
 *
 * A null [currentRequestId] means "no menu open", so any result is stale. This is what stops a
 * result arriving after the page moved on from mutating the wrong surface.
 */
internal fun slockShouldApplyShellOverlayResult(
    currentRequestId: String?,
    incoming: JsonObject?,
): Boolean {
    val incomingId = incoming?.shellOverlayRequestIdOrNull() ?: return false
    return currentRequestId != null && currentRequestId == incomingId
}

/**
 * Whether the shell should drop an incoming id: it must match the request the shell is showing.
 * The shell ignores anything else, so a cancel from a closed page cannot close a newer menu.
 */
internal fun slockShellShouldDropOverlayRequest(
    activeRequestId: String?,
    incomingRequestId: String?,
): Boolean = activeRequestId != null && activeRequestId != incomingRequestId

/** What the shell keeps while it is showing a page-requested menu. */
internal data class SlockShellOpenOverlay(
    val request: SlockShellOverlayRequest,
)

/**
 * Parses a page's request off the event bus. Returns null for anything malformed -- a menu the shell
 * cannot fully understand must not be drawn, because a half-parsed row could dispatch the wrong id.
 */
internal fun JsonObject?.toSlockShellOverlayRequestOrNull(): SlockShellOverlayRequest? {
    val json = this ?: return null
    val requestId = json.shellOverlayRequestIdOrNull() ?: return null
    val pageKey = json.optString(SHELL_OVERLAY_PAGE_KEY).trim()
    if (pageKey.isEmpty()) return null
    val rows = json.optJsonArray(SHELL_OVERLAY_ITEMS) ?: return null
    val items =
        (0 until rows.size).mapNotNull { index ->
            val row = rows.optJsonObject(index) ?: return@mapNotNull null
            val id = row.optString("id").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val label = row.optString("label")
            SlockShellOverlayItem(
                id = id,
                label = label,
                iconName = row.optString("icon").takeIf { it.isNotEmpty() },
                enabled = row.optBoolean("enabled", true),
            )
        }
    if (items.isEmpty()) return null
    return SlockShellOverlayRequest(
        requestId = requestId,
        pageKey = pageKey,
        // Window space, verbatim. The page resolved this; the shell must not re-derive it.
        anchor =
            Offset(
                json.optDouble(SHELL_OVERLAY_ANCHOR_X, 0.0).toFloat(),
                json.optDouble(SHELL_OVERLAY_ANCHOR_Y, 0.0).toFloat(),
            ),
        items = items,
    )
}
