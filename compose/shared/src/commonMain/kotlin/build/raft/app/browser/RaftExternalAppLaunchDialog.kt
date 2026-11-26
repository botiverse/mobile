package build.raft.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

internal const val RAFT_EXTERNAL_APP_DIALOG_TAG = "raft_external_app_dialog"
internal const val RAFT_EXTERNAL_APP_DIALOG_MESSAGE_TAG = "raft_external_app_dialog_message"
internal const val RAFT_EXTERNAL_APP_DIALOG_CONFIRM_TAG = "raft_external_app_dialog_confirm"
internal const val RAFT_EXTERNAL_APP_DIALOG_CANCEL_TAG = "raft_external_app_dialog_cancel"

private const val MAX_EXTERNAL_APP_DISPLAY_LENGTH = 80

/**
 * Schemes that MUST NEVER leave the embedded WebView to launch an external app or OS handler,
 * even when accompanied by a user gesture (#proj-mobile:#396).
 */
private val HARD_DENIED_EXTERNAL_APP_SCHEMES: Set<String> =
    setOf(
        "javascript",
        "vbscript",
        "data",
        "blob",
        "file",
        "about",
        "content",
        "intent",
        "android-app",
        "chrome",
        "chrome-extension",
        "devtools",
        "view-source",
        "filesystem",
        "ftp",
        "ftps",
        "jar",
        "ws",
        "wss",
        "resource",
    )

/** Outcome of evaluating a blocked navigation inside the in-app browser (#proj-mobile:#396). */
internal sealed interface RaftExternalAppLaunchDecision {
    /** Standard `http`/`https` link handed off to the external browser. */
    data class OpenExternalWeb(val url: String) : RaftExternalAppLaunchDecision

    /** Non-`http/https` external app scheme requiring explicit user confirmation before launch. */
    data class ConfirmExternalApp(
        val url: String,
        val scheme: String,
        val displayTarget: String,
    ) : RaftExternalAppLaunchDecision

    /** Silently dropped (hidden panel, missing user gesture, malformed URL, or hard-denied scheme). */
    data class Drop(
        val reason: String,
        val scheme: String? = null,
    ) : RaftExternalAppLaunchDecision
}

/**
 * Evaluates a blocked WebView navigation URL from the in-app browser:
 * - While [panelVisible] is false (collapsed / hidden), drops all launches so background pages
 *   cannot hijack focus or queue surprise dialogs.
 * - `http` / `https` URLs continue through external browser handoff ([RaftExternalAppLaunchDecision.OpenExternalWeb]).
 * - Dangerous/local/script/intent schemes are hard-denied ([RaftExternalAppLaunchDecision.Drop]).
 * - Other valid RFC 3986 external app schemes (`mailto:`, `tel:`, `weixin://`, `alipays://`, etc.)
 *   require [isUserGesture] == true (blocking drive-by JS/redirect app launches) and then require
 *   an explicit user confirmation dialog ([RaftExternalAppLaunchDecision.ConfirmExternalApp]).
 */
internal fun classifyRaftExternalAppLaunch(
    rawUrl: String,
    isUserGesture: Boolean,
    panelVisible: Boolean,
): RaftExternalAppLaunchDecision {
    val url = rawUrl.trim()
    if (url.isEmpty()) return RaftExternalAppLaunchDecision.Drop("blank_url")
    if (url.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) {
        return RaftExternalAppLaunchDecision.Drop("invalid_url")
    }
    val colonIndex = url.indexOf(':')
    if (colonIndex <= 0) return RaftExternalAppLaunchDecision.Drop("missing_scheme")
    val scheme = url.substring(0, colonIndex).lowercase()
    if (
        scheme.first() !in 'a'..'z' ||
        !scheme.drop(1).all { it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '-' || it == '.' }
    ) {
        return RaftExternalAppLaunchDecision.Drop("invalid_scheme")
    }
    val remainder = url.substring(colonIndex + 1)
    if (remainder.isBlank()) return RaftExternalAppLaunchDecision.Drop("empty_target", scheme)

    if (!panelVisible) {
        return RaftExternalAppLaunchDecision.Drop("panel_not_visible", scheme)
    }
    if (scheme == "http" || scheme == "https") {
        return RaftExternalAppLaunchDecision.OpenExternalWeb(url)
    }
    if (scheme in HARD_DENIED_EXTERNAL_APP_SCHEMES) {
        return RaftExternalAppLaunchDecision.Drop("denied_scheme", scheme)
    }
    if (!isUserGesture) {
        return RaftExternalAppLaunchDecision.Drop("no_user_gesture", scheme)
    }
    return RaftExternalAppLaunchDecision.ConfirmExternalApp(
        url = url,
        scheme = scheme,
        displayTarget = formatRaftExternalAppDisplayTarget(url, scheme),
    )
}

/**
 * Formats a concise, spoof-resistant display string for the external-app confirmation dialog:
 * strips query strings, fragments, control/BiDi override characters, and caps length so malicious
 * pages cannot hide the scheme or spoof dialog copy.
 */
internal fun formatRaftExternalAppDisplayTarget(url: String, scheme: String): String {
    val stripped = url.substringBefore('?').substringBefore('#')
    val compact =
        if (stripped.startsWith("$scheme://", ignoreCase = true)) {
            val afterAuthorityPrefix = stripped.substring(scheme.length + 3)
            val authority = afterAuthorityPrefix.substringBefore('/').substringBefore('\\').substringAfterLast('@')
            if (authority.isNotBlank()) "$scheme://$authority" else "$scheme://"
        } else {
            stripped
        }
    val sanitized =
        buildString(compact.length) {
            for (ch in compact) {
                if (ch.code < 0x20 || ch.code == 0x7f || isBidiControlChar(ch)) continue
                append(ch)
            }
        }.ifBlank { "$scheme:" }
    return if (sanitized.length > MAX_EXTERNAL_APP_DISPLAY_LENGTH) {
        sanitized.take(MAX_EXTERNAL_APP_DISPLAY_LENGTH - 1) + "…"
    } else {
        sanitized
    }
}

private fun isBidiControlChar(ch: Char): Boolean =
    ch in '\u202A'..'\u202E' ||
        ch in '\u2066'..'\u2069' ||
        ch == '\u200E' ||
        ch == '\u200F' ||
        ch == '\u061C'

/**
 * Confirmation modal shown before handing a user-initiated non-`http/https` link from the in-app
 * browser to an external app (#proj-mobile:#396).
 */
@Composable
internal fun RaftExternalAppLaunchDialogHost(
    request: RaftExternalAppLaunchDecision.ConfirmExternalApp?,
    onConfirm: (RaftExternalAppLaunchDecision.ConfirmExternalApp) -> Unit,
    onDismiss: () -> Unit,
) {
    if (request == null) return
    val colors = RaftTheme.colors
    RaftDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.in_app_browser_external_app_title),
        modifier = Modifier.testTag(RAFT_EXTERNAL_APP_DIALOG_TAG),
        size = RaftDialogSize.Sm,
        dismissOnClickOutside = true,
        showClose = false,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                modifier = Modifier.testTag(RAFT_EXTERNAL_APP_DIALOG_CANCEL_TAG),
            )
            RaftButton(
                text = stringResource(Res.string.in_app_browser_external_app_open),
                onClick = { onConfirm(request) },
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
                modifier = Modifier.testTag(RAFT_EXTERNAL_APP_DIALOG_CONFIRM_TAG),
            )
        },
    ) {
        RaftText(
            text = stringResource(Res.string.in_app_browser_external_app_message, request.displayTarget),
            style = RaftTheme.typography.body,
            color = colors.foreground,
            modifier = Modifier.fillMaxWidth().testTag(RAFT_EXTERNAL_APP_DIALOG_MESSAGE_TAG),
        )
    }
}
