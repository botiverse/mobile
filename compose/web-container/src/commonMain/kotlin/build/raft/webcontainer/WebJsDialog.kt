package build.raft.webcontainer

/** Kind of JavaScript modal requested by a web page (`window.alert` / `confirm` / `prompt`). */
enum class WebJsDialogKind {
    Alert,
    Confirm,
    Prompt,
}

/** Terminal outcome delivered back to the WebView engine for a [WebJsDialogRequest]. */
sealed interface WebJsDialogResult {
    data object AlertDismissed : WebJsDialogResult

    data class Confirm(val confirmed: Boolean) : WebJsDialogResult

    data class Prompt(val value: String?) : WebJsDialogResult
}

internal const val WEB_JS_DIALOG_MAX_MESSAGE_CHARS: Int = 2_000
internal const val WEB_JS_DIALOG_MAX_PROMPT_CHARS: Int = 2_000

private val JsDialogIdLock = createWebContainerLock()
private var nextJsDialogId: Long = 0L

private fun nextWebJsDialogRequestId(): Long =
    JsDialogIdLock.withLock {
        nextJsDialogId += 1L
        nextJsDialogId
    }

internal fun sanitizeWebJsDialogText(
    raw: String?,
    maxChars: Int,
): String {
    if (raw.isNullOrEmpty()) return ""
    val cleaned = raw.replace("\u0000", "")
    return if (cleaned.length <= maxChars) cleaned else cleaned.take(maxChars)
}

/**
 * Pure visibility & host-capability gate shared by Android and iOS WebView hosts: a JavaScript
 * dialog is allowed to present through the custom host handler only when the host opted in
 * ([allowJsDialogs]), the WebView is currently visible and active in the foreground
 * ([isVisibleAndActive]), and a dialog presenter callback is attached ([hasHandler]). Otherwise
 * opt-in hosts (and iOS `WKUIDelegate` unconditionally) immediately cancel/dismiss the JS request
 * without blocking the WebProcess; non-opt-in visible Android surfaces may fall through to
 * WebView's default behavior for backwards compatibility.
 */
fun isWebJsDialogPermitted(
    allowJsDialogs: Boolean,
    isVisibleAndActive: Boolean,
    hasHandler: Boolean,
): Boolean = allowJsDialogs && isVisibleAndActive && hasHandler

/**
 * Single-shot JavaScript dialog request (`alert`, `confirm`, or `prompt`).
 *
 * Guarantees that the underlying platform callback (`JsResult` / `JsPromptResult` on Android,
 * `WKUIDelegate` `completionHandler` on iOS) is invoked **strictly once** across every exit path:
 * user confirm, user cancel, outside dismiss, panel collapse/hide, navigation, or WebView disposal.
 */
class WebJsDialogRequest(
    val kind: WebJsDialogKind,
    val originUrl: String,
    message: String?,
    defaultPromptText: String? = null,
    val id: Long = nextWebJsDialogRequestId(),
    private val onSettled: () -> Unit = {},
    private val onResult: (WebJsDialogResult) -> Unit,
) {
    val message: String = sanitizeWebJsDialogText(message, WEB_JS_DIALOG_MAX_MESSAGE_CHARS)
    val defaultPromptText: String = sanitizeWebJsDialogText(defaultPromptText, WEB_JS_DIALOG_MAX_PROMPT_CHARS)

    private val lock = createWebContainerLock()
    private var settled: Boolean = false
    private val settledListeners = mutableListOf<() -> Unit>()

    val isSettled: Boolean
        get() = lock.withLock { settled }

    /**
     * Registers a one-shot observer invoked once this request settles (from user action or from
     * external platform cancellation such as navigation, backgrounding, or teardown). If already
     * settled, [listener] is invoked immediately outside the lock.
     */
    fun addOnSettledListener(listener: () -> Unit) {
        val alreadySettled =
            lock.withLock {
                if (settled) {
                    true
                } else {
                    settledListeners += listener
                    false
                }
            }
        if (alreadySettled) {
            runCatching { listener() }
        }
    }

    /** Confirms the dialog (`OK`); for [WebJsDialogKind.Prompt], delivers [promptValue]. */
    fun confirm(promptValue: String? = null): Boolean {
        val outcome =
            when (kind) {
                WebJsDialogKind.Alert -> WebJsDialogResult.AlertDismissed
                WebJsDialogKind.Confirm -> WebJsDialogResult.Confirm(confirmed = true)
                WebJsDialogKind.Prompt ->
                    WebJsDialogResult.Prompt(
                        value = sanitizeWebJsDialogText(promptValue ?: "", WEB_JS_DIALOG_MAX_PROMPT_CHARS),
                    )
            }
        return settleOnce(outcome)
    }

    /** Cancels or dismisses the dialog (`Cancel`, backdrop tap, hidden page, or teardown). */
    fun cancel(): Boolean {
        val outcome =
            when (kind) {
                WebJsDialogKind.Alert -> WebJsDialogResult.AlertDismissed
                WebJsDialogKind.Confirm -> WebJsDialogResult.Confirm(confirmed = false)
                WebJsDialogKind.Prompt -> WebJsDialogResult.Prompt(value = null)
            }
        return settleOnce(outcome)
    }

    private fun settleOnce(result: WebJsDialogResult): Boolean {
        val listenersToNotify: List<() -> Unit>? =
            lock.withLock {
                if (settled) {
                    null
                } else {
                    settled = true
                    val snapshot = settledListeners.toList()
                    settledListeners.clear()
                    snapshot
                }
            }
        if (listenersToNotify == null) return false
        runCatching { onSettled() }
        for (listener in listenersToNotify) {
            runCatching { listener() }
        }
        onResult(result)
        return true
    }
}

/**
 * Tracks the currently pending [WebJsDialogRequest] owned by a platform WebView host so that any
 * replacement, hide, navigation, renderer death, or teardown cancels it immediately and safely.
 */
class WebJsDialogTracker {
    private val lock = createWebContainerLock()
    private var active: WebJsDialogRequest? = null

    val activeRequest: WebJsDialogRequest?
        get() = lock.withLock { active }

    fun begin(next: WebJsDialogRequest) {
        val previous =
            lock.withLock {
                val old = active
                active = next
                old
            }
        if (previous !== next) {
            previous?.cancel()
        }
    }

    fun clearIfSame(request: WebJsDialogRequest) {
        lock.withLock {
            if (active === request) {
                active = null
            }
        }
    }

    fun cancelActive() {
        val current =
            lock.withLock {
                val old = active
                active = null
                old
            }
        current?.cancel()
    }
}
