package build.raft.webcontainer.jsb

import build.raft.webcontainer.extractUrlScheme
import build.raft.webcontainer.parseWebContainerOrigin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Standard v1 JSB capability identifiers shared across Android, iOS, and Desktop.
 *
 * Host applications explicitly opt in to the subset of capabilities appropriate for each surface
 * via [JsbPolicy.grantedCapabilities]; every standard method enforces its capability through
 * [JsbDispatcher] Gate 5 before handler execution.
 */
object JsbStandardCapabilities {
    const val APP_INFO: String = "app.info"
    const val UI_THEME: String = "ui.theme"
    const val UI_HAPTIC: String = "ui.haptic"
    const val UI_CLIPBOARD: String = "ui.clipboard"
    const val UI_TOAST: String = "ui.toast"
    const val NAV_OPEN_EXTERNAL: String = "nav.openExternal"
    const val NAV_CLOSE: String = "nav.close"
    const val AUTH_STATUS: String = "auth.status"

    val All: Set<String> =
        setOf(
            APP_INFO,
            UI_THEME,
            UI_HAPTIC,
            UI_CLIPBOARD,
            UI_TOAST,
            NAV_OPEN_EXTERNAL,
            NAV_CLOSE,
            AUTH_STATUS,
        )
}

/** Standard v1 JSB method names implemented once in `commonMain`. */
object JsbStandardMethods {
    const val APP_GET_INFO: String = "app.getInfo"
    const val UI_GET_THEME: String = "ui.getTheme"
    const val UI_HAPTIC: String = "ui.haptic"
    const val UI_COPY_TEXT: String = "ui.copyText"
    const val UI_SHOW_TOAST: String = "ui.showToast"
    const val NAV_OPEN_EXTERNAL: String = "nav.openExternal"
    const val NAV_CLOSE: String = "nav.close"
    const val AUTH_GET_STATUS: String = "auth.getStatus"
}

/** Standard v1 host->web JSB event names delivered via `window.raftBridge.on(name, cb)`. */
object JsbStandardEvents {
    const val THEME_CHANGED: String = "theme:changed"
}

/** Basic application runtime metadata exposed by `app.getInfo`. */
data class JsbAppInfo(
    val appVersion: String,
    val buildNumber: String,
    val platform: String,
    val osVersion: String = "",
    val locale: String = "",
)

/** Host theme snapshot exposed by `ui.getTheme` and `theme:changed` events. */
data class JsbThemeInfo(
    val isDark: Boolean,
    val mode: String = if (isDark) "dark" else "light",
)

/** Tactile feedback styles accepted by `ui.haptic`. */
enum class JsbHapticStyle(val wireName: String) {
    LIGHT("light"),
    MEDIUM("medium"),
    HEAVY("heavy"),
    SELECTION("selection"),
    SUCCESS("success"),
    WARNING("warning"),
    ERROR("error"),
    ;

    companion object {
        fun fromWire(raw: String?): JsbHapticStyle? {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            if (normalized.isEmpty()) return LIGHT
            return entries.firstOrNull { it.wireName == normalized }
        }
    }
}

/** Toast presentation duration accepted by `ui.showToast`. */
enum class JsbToastDuration(val wireName: String) {
    SHORT("short"),
    LONG("long"),
    ;

    companion object {
        fun fromWire(raw: String?): JsbToastDuration? {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            if (normalized.isEmpty()) return SHORT
            return entries.firstOrNull { it.wireName == normalized }
        }
    }
}

/**
 * Non-secret session presence snapshot returned by `auth.getStatus`.
 *
 * Intentionally excludes raw access tokens, cookies, or refresh secrets.
 */
data class JsbAuthStatus(
    val authenticated: Boolean,
    val accountId: String? = null,
    val serverId: String? = null,
)

/**
 * Host implementation for the standard JSB capability pack.
 *
 * Override only the capabilities supported by a host app or surface; unimplemented hooks cleanly
 * report [JsbErrorCode.METHOD_NOT_FOUND] after Gate 5 capability verification succeeds.
 */
interface JsbStandardHost {
    suspend fun getAppInfo(context: JsbCallContext): JsbAppInfo? = null

    suspend fun getThemeInfo(context: JsbCallContext): JsbThemeInfo? = null

    suspend fun triggerHaptic(
        context: JsbCallContext,
        style: JsbHapticStyle,
    ): Boolean? = null

    suspend fun copyText(
        context: JsbCallContext,
        text: String,
    ): Boolean? = null

    suspend fun showToast(
        context: JsbCallContext,
        message: String,
        duration: JsbToastDuration,
    ): Boolean? = null

    suspend fun openExternalUrl(
        context: JsbCallContext,
        url: String,
    ): Boolean? = null

    suspend fun closeContainer(
        context: JsbCallContext,
        reason: String?,
    ): Boolean? = null

    suspend fun getAuthStatus(context: JsbCallContext): JsbAuthStatus? = null
}

private const val MAX_CLIPBOARD_TEXT_CHARS: Int = 64 * 1024
private const val MAX_TOAST_MESSAGE_CHARS: Int = 500
private const val MAX_CLOSE_REASON_CHARS: Int = 128
private const val MAX_EXTERNAL_URL_CHARS: Int = 4 * 1024
private val DefaultExternalUrlSchemes: Set<String> = setOf("http", "https", "mailto", "tel")

/** Serializes [JsbThemeInfo] into the canonical payload for [JsbStandardEvents.THEME_CHANGED]. */
fun buildThemeChangedEventPayload(theme: JsbThemeInfo): JsonObject =
    buildJsonObject {
        put("isDark", theme.isDark)
        put("mode", theme.mode)
    }

/** Validates an external URL passed to `nav.openExternal` against [allowedSchemes]. */
fun isSafeJsbExternalUrl(
    rawUrl: String,
    allowedSchemes: Set<String> = DefaultExternalUrlSchemes,
): Boolean {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty() || trimmed.length > MAX_EXTERNAL_URL_CHARS) return false
    if (trimmed.any { it.code < 0x20 || it.code == 0x7F || it.isWhitespace() }) return false
    val normalizedAllowed = allowedSchemes.mapTo(LinkedHashSet()) { it.trim().lowercase() }
    val scheme = extractUrlScheme(trimmed) ?: return false
    if (scheme !in normalizedAllowed) return false
    if (scheme == "http" || scheme == "https") {
        val origin = parseWebContainerOrigin(trimmed) ?: return false
        if (origin.scheme != scheme) return false
    } else {
        if (trimmed.substringAfter(':').isBlank()) return false
    }
    return true
}

/**
 * Registers all 8 standard v1 JSB methods on this [JsbRegistry], delegating execution to [host]
 * with strict parameter validation and per-capability Gate 5 enforcement.
 */
fun JsbRegistry.registerStandardCapabilities(
    host: JsbStandardHost,
    allowedExternalSchemes: Set<String> = DefaultExternalUrlSchemes,
): JsbRegistry {
    val normalizedSchemes =
        allowedExternalSchemes
            .mapTo(LinkedHashSet()) { it.trim().lowercase() }
            .filterTo(LinkedHashSet()) { it.isNotEmpty() && it in DefaultExternalUrlSchemes }

    register(
        method = JsbStandardMethods.APP_GET_INFO,
        requiredCapabilities = setOf(JsbStandardCapabilities.APP_INFO),
    ) { ctx, _ ->
        val info = host.getAppInfo(ctx)
            ?: return@register JsbResult.Failure(JsbErrorCode.METHOD_NOT_FOUND, "Host does not provide app.getInfo")
        JsbResult.Success(
            buildJsonObject {
                put("appVersion", info.appVersion)
                put("buildNumber", info.buildNumber)
                put("platform", info.platform)
                if (info.osVersion.isNotEmpty()) put("osVersion", info.osVersion)
                if (info.locale.isNotEmpty()) put("locale", info.locale)
            },
        )
    }

    register(
        method = JsbStandardMethods.UI_GET_THEME,
        requiredCapabilities = setOf(JsbStandardCapabilities.UI_THEME),
    ) { ctx, _ ->
        val theme = host.getThemeInfo(ctx)
            ?: return@register JsbResult.Failure(JsbErrorCode.METHOD_NOT_FOUND, "Host does not provide ui.getTheme")
        JsbResult.Success(buildThemeChangedEventPayload(theme))
    }

    register(
        method = JsbStandardMethods.UI_HAPTIC,
        requiredCapabilities = setOf(JsbStandardCapabilities.UI_HAPTIC),
    ) { ctx, params ->
        val rawStyle = params.optionalString("style")
            ?: if ("style" in params) {
                return@register JsbResult.Failure(JsbErrorCode.INVALID_PARAMS, "'style' must be a string")
            } else {
                null
            }
        val style = JsbHapticStyle.fromWire(rawStyle)
            ?: return@register JsbResult.Failure(JsbErrorCode.INVALID_PARAMS, "Unsupported haptic style '$rawStyle'")
        val performed = host.triggerHaptic(ctx, style)
            ?: return@register JsbResult.Failure(JsbErrorCode.METHOD_NOT_FOUND, "Host does not provide ui.haptic")
        JsbResult.Success(buildJsonObject { put("performed", performed) })
    }

    register(
        method = JsbStandardMethods.UI_COPY_TEXT,
        requiredCapabilities = setOf(JsbStandardCapabilities.UI_CLIPBOARD),
    ) { ctx, params ->
        val text = params.optionalString("text")
            ?: return@register JsbResult.Failure(JsbErrorCode.INVALID_PARAMS, "Missing string parameter 'text'")
        if (text.isEmpty() || text.length > MAX_CLIPBOARD_TEXT_CHARS) {
            return@register JsbResult.Failure(
                JsbErrorCode.INVALID_PARAMS,
                "'text' must be 1..$MAX_CLIPBOARD_TEXT_CHARS characters",
            )
        }
        val copied = host.copyText(ctx, text)
            ?: return@register JsbResult.Failure(JsbErrorCode.METHOD_NOT_FOUND, "Host does not provide ui.copyText")
        JsbResult.Success(buildJsonObject { put("copied", copied) })
    }

    register(
        method = JsbStandardMethods.UI_SHOW_TOAST,
        requiredCapabilities = setOf(JsbStandardCapabilities.UI_TOAST),
    ) { ctx, params ->
        val message = params.optionalString("message")?.trim()
            ?: return@register JsbResult.Failure(JsbErrorCode.INVALID_PARAMS, "Missing string parameter 'message'")
        if (message.isEmpty() || message.length > MAX_TOAST_MESSAGE_CHARS) {
            return@register JsbResult.Failure(
                JsbErrorCode.INVALID_PARAMS,
                "'message' must be 1..$MAX_TOAST_MESSAGE_CHARS characters",
            )
        }
        val rawDuration = params.optionalString("duration")
            ?: if ("duration" in params) {
                return@register JsbResult.Failure(JsbErrorCode.INVALID_PARAMS, "'duration' must be a string")
            } else {
                null
            }
        val duration = JsbToastDuration.fromWire(rawDuration)
            ?: return@register JsbResult.Failure(JsbErrorCode.INVALID_PARAMS, "Unsupported toast duration '$rawDuration'")
        val shown = host.showToast(ctx, message, duration)
            ?: return@register JsbResult.Failure(JsbErrorCode.METHOD_NOT_FOUND, "Host does not provide ui.showToast")
        JsbResult.Success(buildJsonObject { put("shown", shown) })
    }

    register(
        method = JsbStandardMethods.NAV_OPEN_EXTERNAL,
        requiredCapabilities = setOf(JsbStandardCapabilities.NAV_OPEN_EXTERNAL),
    ) { ctx, params ->
        val rawUrl = params.optionalString("url")?.trim()
            ?: return@register JsbResult.Failure(JsbErrorCode.INVALID_PARAMS, "Missing string parameter 'url'")
        if (!isSafeJsbExternalUrl(rawUrl, normalizedSchemes)) {
            return@register JsbResult.Failure(JsbErrorCode.INVALID_PARAMS, "External URL is malformed or uses a disallowed scheme")
        }
        val opened = host.openExternalUrl(ctx, rawUrl)
            ?: return@register JsbResult.Failure(JsbErrorCode.METHOD_NOT_FOUND, "Host does not provide nav.openExternal")
        JsbResult.Success(buildJsonObject { put("opened", opened) })
    }

    register(
        method = JsbStandardMethods.NAV_CLOSE,
        requiredCapabilities = setOf(JsbStandardCapabilities.NAV_CLOSE),
    ) { ctx, params ->
        val reason = params.optionalString("reason")?.trim()?.takeIf { it.isNotEmpty() }
        if ("reason" in params && params.optionalString("reason") == null) {
            return@register JsbResult.Failure(JsbErrorCode.INVALID_PARAMS, "'reason' must be a string")
        }
        if (reason != null && reason.length > MAX_CLOSE_REASON_CHARS) {
            return@register JsbResult.Failure(
                JsbErrorCode.INVALID_PARAMS,
                "'reason' exceeds $MAX_CLOSE_REASON_CHARS characters",
            )
        }
        val closed = host.closeContainer(ctx, reason)
            ?: return@register JsbResult.Failure(JsbErrorCode.METHOD_NOT_FOUND, "Host does not provide nav.close")
        JsbResult.Success(buildJsonObject { put("closed", closed) })
    }

    register(
        method = JsbStandardMethods.AUTH_GET_STATUS,
        requiredCapabilities = setOf(JsbStandardCapabilities.AUTH_STATUS),
    ) { ctx, _ ->
        val status = host.getAuthStatus(ctx)
            ?: return@register JsbResult.Failure(JsbErrorCode.METHOD_NOT_FOUND, "Host does not provide auth.getStatus")
        JsbResult.Success(
            buildJsonObject {
                put("authenticated", status.authenticated)
                if (!status.accountId.isNullOrBlank()) put("accountId", status.accountId)
                if (!status.serverId.isNullOrBlank()) put("serverId", status.serverId)
            },
        )
    }

    return this
}

private fun JsonObject.optionalString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
