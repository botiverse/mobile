package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val appLinkPreviewsJson = Json {
    ignoreUnknownKeys = true
}

/**
 * Typed boundary of App link previews (artin #20 lane L), as the web reads them.
 *
 * Server contract (botiverse/slock staging 2d4f961df):
 *  - `GET messages/:messageId/app-link-previews` (`packages/server/src/routes/messages.ts`,
 *    `services/appLinkPreviewService.ts`) answers
 *    `{ previews: [{ version: 1, url, appName, title, card? }], prompts: [{ url, app: { id, name, icon } }],
 *    appIcons: { [previewUrl]: string | null } }`; 404 for a message the viewer can't see. The
 *    server only calls an App for a viewer who consented to it on this Server and hasn't
 *    declined it; a prompt never calls the App.
 *  - `POST integrations/clients/:clientId/link-preview-consent` allows,
 *    `POST integrations/clients/:clientId/link-preview-decline` declines
 *    (`packages/server/src/routes/integrations.ts`). Both 404 `LINK_PREVIEW_APP_NOT_FOUND`;
 *    allowing also 409 `LINK_PREVIEW_SCOPES_NOT_ALLOWED`.
 *
 * Read as the web reads it (`packages/shared/src/appLinkPreview.ts`
 * `appLinkPreviewListResponseSchema`, `packages/web/src/components/message/useAppLinkPreviews.ts`
 * `readAppLinkPreviewAnswer`): the top level is tolerant of new fields; the previews (with
 * `appIcons`) are read as one list and the prompts as another, so an entry this client can't
 * read drops only its own list. One result per link, one prompt per App. This client reads with
 * the GET only: it declares no `card.v2`, so the batched link-card read (and its
 * `connect_account` chip) is not used.
 *
 * Field names are exact camelCase. No snake_case fallbacks.
 */
internal const val APP_LINK_PREVIEW_URL_MAX_LENGTH = 2000

/** The links a v1 read covers (`messagePreviewUrls(content, 3)` on the server). */
internal const val APP_LINK_PREVIEW_V1_LINK_LIMIT = 3

@Serializable
private data class WireAppLinkPreview(
    val version: Int? = null,
    val url: String? = null,
    val appName: String? = null,
    val title: String? = null,
)

@Serializable
private data class WireAppLinkPromptApp(
    val id: String? = null,
    val name: String? = null,
    val icon: String? = null,
)

@Serializable
private data class WireAppLinkPrompt(
    val url: String? = null,
    val app: WireAppLinkPromptApp? = null,
)

@Serializable
private data class WireAppLinkPreviewList(
    val previews: List<JsonElement>? = null,
    val prompts: List<JsonElement>? = null,
    val appIcons: Map<String, String?>? = null,
)

/** The App as Raft stores it: its name and its uploaded icon (never an image from the preview). */
data class AppLinkApp(val name: String, val icon: String?)

/** An App the viewer hasn't answered yet on this Server. */
data class AppLinkPromptApp(val id: String, val name: String, val icon: String?)

/**
 * How one link of a message is shown (protocol v3.2.3 addendum 5 §2; web `AppLinkResult`). A
 * link without a result stays the author's link.
 */
sealed interface AppLinkResult {
    /** The link's normalized href, the key it is looked up by. */
    val url: String

    /** A cyan chip with the App's icon and its title. */
    data class Card(override val url: String, val app: AppLinkApp, val title: String) : AppLinkResult

    /** The viewer hasn't answered this App: a grey chip that asks first. */
    data class Consent(override val url: String, val app: AppLinkPromptApp) : AppLinkResult

    /** The viewer just allowed the App and its links are read again. */
    data class Pending(override val url: String) : AppLinkResult
}

/**
 * The link's href when it may be previewed (web `publicPreviewUrl`): https, no credentials,
 * fragment or port, a dotted host that is not an IP address, at most 2,000 characters. The
 * scheme and host are lower-cased and an empty path is `/`, as the WHATWG parser writes them. A
 * host with characters outside ASCII is refused: the web shows it in punycode, which this
 * client doesn't compute, so the link stays plain.
 */
internal fun appLinkPreviewHref(raw: String): String? {
    if (raw.length > APP_LINK_PREVIEW_URL_MAX_LENGTH) return null
    if (!raw.startsWith("https://", ignoreCase = true)) return null
    if (raw.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f || it == '\\' }) return null
    val rest = raw.substring("https://".length)
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
    val host = rest.substring(0, authorityEnd).lowercase()
    if (host.isEmpty() || '@' in host || ':' in host || '[' in host || ']' in host) return null
    if (!host.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' }) return null
    if ('.' !in host || host.startsWith('.') || ".." in host) return null
    if (host.split('.').let { parts -> parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all(Char::isDigit) } }) return null
    val tail = rest.substring(authorityEnd)
    if ('#' in tail) return null
    val queryStart = tail.indexOf('?').let { if (it < 0) tail.length else it }
    val path = tail.substring(0, queryStart).ifEmpty { "/" }
    return "https://$host$path${tail.substring(queryStart)}"
}

/** An http(s) link's host without a leading `www.` (web `appLinkDomain`), or null. */
internal fun appLinkDomain(url: String): String? {
    val rest =
        when {
            url.startsWith("https://", ignoreCase = true) -> url.substring(8)
            url.startsWith("http://", ignoreCase = true) -> url.substring(7)
            else -> return null
        }
    val authority = rest.substring(0, rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it })
    val host = authority.substringAfterLast('@').let { value ->
        if (value.startsWith("[")) value.substringBefore(']') + "]" else value.substringBefore(':')
    }.lowercase()
    if (host.isEmpty()) return null
    return host.removePrefix("www.")
}

/**
 * The address shortened to its domain and path (web `appLinkShortAddress`), for a chip without
 * a title: `stamp.build/botiverse/slock/pull/10449`. The query and fragment are left out.
 */
internal fun appLinkShortAddress(url: String): String? {
    val domain = appLinkDomain(url) ?: return null
    val rest = url.substringAfter("://")
    val pathStart = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    if (pathStart < 0 || rest[pathStart] != '/') return domain
    val path = rest.substring(pathStart).substringBefore('?').substringBefore('#')
    return if (path == "/") domain else "$domain$path"
}

/**
 * The bare links of a message, as the server picks the ones it previews (web
 * `messagePreviewUrls`): an autolink, `<url>` or `[url](url)` whose text is its own address, in
 * message order, deduplicated, at most [limit]. A link the author gave words to, links in code
 * and images are left out.
 */
internal fun appLinkMessagePreviewUrls(content: String, limit: Int = APP_LINK_PREVIEW_V1_LINK_LIMIT): List<String> {
    val text = content.take(32_000).withoutAppLinkCode()
    val urls = LinkedHashSet<String>()
    fun add(raw: String) {
        appLinkPreviewHref(raw)?.let(urls::add)
    }
    var index = 0
    while (index < text.length && urls.size < limit) {
        val char = text[index]
        when {
            char == '!' && text.startsWith("![", index) -> {
                index = text.markdownLinkEnd(index + 1)?.end ?: (index + 2)
            }
            char == '[' -> {
                val link = text.markdownLinkEnd(index)
                if (link == null) {
                    index++
                } else {
                    if (appLinkPreviewHref(link.label.trim()) != null && appLinkPreviewHref(link.label.trim()) == appLinkPreviewHref(link.destination)) {
                        add(link.destination)
                    }
                    index = link.end
                }
            }
            char == '<' && text.startsWith("https://", index + 1, ignoreCase = true) -> {
                val close = text.indexOf('>', index)
                val candidate = if (close < 0) null else text.substring(index + 1, close)
                if (candidate != null && candidate.none { it.isWhitespace() || it == '<' }) {
                    add(candidate)
                    index = close + 1
                } else {
                    index++
                }
            }
            (char == 'h' || char == 'H') && text.startsWith("https://", index, ignoreCase = true) &&
                (index == 0 || !text[index - 1].isLetterOrDigit()) -> {
                var end = index
                while (end < text.length && !text[end].isWhitespace() && text[end] != '<') end++
                add(text.substring(index, end).trimAutolinkTail())
                index = end
            }
            else -> index++
        }
    }
    return urls.toList().take(limit)
}

private data class MarkdownInlineLink(val label: String, val destination: String, val end: Int)

/** `[label](destination)` or `[label](<destination>)` starting at [open] (a `[`), or null. */
private fun String.markdownLinkEnd(open: Int): MarkdownInlineLink? {
    if (getOrNull(open) != '[') return null
    var depth = 0
    var close = -1
    for (i in open until length) {
        when (this[i]) {
            '[' -> depth++
            ']' -> {
                depth--
                if (depth == 0) {
                    close = i
                    break
                }
            }
            '\n' -> if (i + 1 < length && this[i + 1] == '\n') return null
        }
    }
    if (close < 0 || getOrNull(close + 1) != '(') return null
    val destinationStart = close + 2
    val end = indexOf(')', destinationStart).takeIf { it >= 0 } ?: return null
    val inside = substring(destinationStart, end).trim()
    val destination =
        if (inside.startsWith("<")) {
            inside.removePrefix("<").substringBefore('>')
        } else {
            inside.substringBefore(' ')
        }
    return MarkdownInlineLink(label = substring(open + 1, close), destination = destination, end = end + 1)
}

/** GFM autolink end: trailing punctuation and an unbalanced `)` are not part of the link. */
private fun String.trimAutolinkTail(): String {
    var value = this
    while (value.isNotEmpty()) {
        val last = value.last()
        value =
            when {
                last in "?!.,:*_~'\";" -> value.dropLast(1)
                last == ')' && value.count { it == ')' } > value.count { it == '(' } -> value.dropLast(1)
                else -> return value
            }
    }
    return value
}

/** The text with fenced code blocks and code spans blanked out (same offsets do not matter here). */
private fun String.withoutAppLinkCode(): String {
    val lines = lines()
    val kept = StringBuilder()
    var fence: String? = null
    for (line in lines) {
        val trimmed = line.trimStart()
        val marker = when {
            trimmed.startsWith("```") -> "```"
            trimmed.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (fence == null && marker != null) {
            fence = marker
            kept.append('\n')
            continue
        }
        if (fence != null) {
            if (marker == fence) fence = null
            kept.append('\n')
            continue
        }
        kept.append(line.withoutCodeSpans()).append('\n')
    }
    return kept.toString()
}

private fun String.withoutCodeSpans(): String {
    if ('`' !in this) return this
    val out = StringBuilder()
    var index = 0
    while (index < length) {
        if (this[index] != '`') {
            out.append(this[index])
            index++
            continue
        }
        var run = 0
        while (index + run < length && this[index + run] == '`') run++
        val ticks = "`".repeat(run)
        val close = indexOf(ticks, index + run)
        if (close < 0) {
            out.append(ticks)
            index += run
        } else {
            out.append(' ')
            index = close + run
        }
    }
    return out.toString()
}

/**
 * Reads a `GET messages/:id/app-link-previews` answer into one result per link (web
 * `readAppLinkPreviewAnswer`): previews first, then the prompts, one per App, on links that
 * have no preview. Null when the body is not JSON at all (a failed read). [resolveIcon] makes
 * an icon Raft serves from a path (`/api/integration-logos/…`) absolute.
 */
fun parseAppLinkPreviewAnswer(
    bodyText: String,
    resolveIcon: (String) -> String? = { it },
): Map<String, AppLinkResult>? {
    val list =
        runCatching { appLinkPreviewsJson.decodeFromString(WireAppLinkPreviewList.serializer(), bodyText) }
            .getOrNull() ?: return null
    val results = LinkedHashMap<String, AppLinkResult>()
    readAppLinkPreviews(list)?.forEach { card ->
        if (card.url !in results) results[card.url] = card.copy(app = card.app.copy(icon = card.app.icon?.let(resolveIcon)))
    }
    val asked = mutableSetOf<String>()
    readAppLinkPrompts(list.prompts.orEmpty())?.forEach { prompt ->
        if (prompt.url in results || !asked.add(prompt.app.id)) return@forEach
        results[prompt.url] = prompt.copy(app = prompt.app.copy(icon = prompt.app.icon?.let(resolveIcon)))
    }
    return results
}

/** The previews as one list, or null when any of them (or `appIcons`) can't be read. */
private fun readAppLinkPreviews(list: WireAppLinkPreviewList): List<AppLinkResult.Card>? {
    val previews = list.previews.orEmpty()
    if (previews.size > 10) return null
    val icons = list.appIcons.orEmpty()
    if (icons.values.any { (it?.length ?: 0) > APP_LINK_PREVIEW_URL_MAX_LENGTH }) return null
    return previews.map { element ->
        val wire =
            runCatching { appLinkPreviewsJson.decodeFromJsonElement(WireAppLinkPreview.serializer(), element) }
                .getOrNull() ?: return null
        val rawUrl = wire.url ?: return null
        val url = appLinkPreviewHref(rawUrl) ?: return null
        val title = wire.title?.takeIf { it.isNotEmpty() && it.length <= 160 } ?: return null
        val appName = wire.appName?.takeIf { it.length <= 300 } ?: return null
        if (wire.version != 1) return null
        AppLinkResult.Card(url = url, app = AppLinkApp(name = appName, icon = icons[rawUrl]), title = title)
    }
}

/** The prompts as one list (web `promptListSchema`), or null when any of them can't be read. */
private fun readAppLinkPrompts(prompts: List<JsonElement>): List<AppLinkResult.Consent>? {
    if (prompts.size > 10) return null
    return prompts.map { element ->
        val wire =
            runCatching { appLinkPreviewsJson.decodeFromJsonElement(WireAppLinkPrompt.serializer(), element) }
                .getOrNull() ?: return null
        val url = wire.url?.let(::appLinkPreviewHref) ?: return null
        val app = wire.app ?: return null
        val id = app.id?.takeIf { it.isNotEmpty() && it.length <= 200 } ?: return null
        val name = app.name?.takeIf { it.isNotEmpty() && it.length <= 300 } ?: return null
        if ((app.icon?.length ?: 0) > APP_LINK_PREVIEW_URL_MAX_LENGTH) return null
        AppLinkResult.Consent(url = url, app = AppLinkPromptApp(id = id, name = name, icon = app.icon))
    }
}

/**
 * An App icon as a loadable URL: https as given, a path Raft serves (`/api/…`) on the API's
 * origin, anything else null (the generic App glyph shows instead).
 */
internal fun resolveAppLinkIconUrl(icon: String, apiBaseUrl: String): String? {
    val trimmed = icon.trim()
    return when {
        trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        trimmed.startsWith("/") && !trimmed.startsWith("//") -> {
            val rest = apiBaseUrl.substringAfter("://", missingDelimiterValue = "")
            if (rest.isEmpty()) return null
            apiBaseUrl.substringBefore("://") + "://" + rest.substringBefore('/') + trimmed
        }
        else -> null
    }
}

/** What an answer to the first-view prompt came to (web `LinkPreviewAnswerResult`). */
enum class AppLinkConsentAnswer {
    Allowed,
    Declined,
    Failed,

    /** 404 / 409: the App can no longer be answered for here; asking again cannot help. */
    Unavailable,
}

internal fun appLinkConsentAnswerFor(response: HomeNetworkResponse?, success: AppLinkConsentAnswer): AppLinkConsentAnswer =
    when {
        response == null -> AppLinkConsentAnswer.Failed
        response.isSuccessful -> success
        response.code == 404 || response.code == 409 -> AppLinkConsentAnswer.Unavailable
        else -> AppLinkConsentAnswer.Failed
    }

internal interface RaftAppLinkPreviewApi {
    /** `GET messages/:messageId/app-link-previews`. */
    suspend fun readPreviews(messageId: String, serverId: String): HomeNetworkResponse

    /** `POST integrations/clients/:clientId/link-preview-consent`. */
    suspend fun allow(appId: String, serverId: String): HomeNetworkResponse

    /** `POST integrations/clients/:clientId/link-preview-decline`. */
    suspend fun decline(appId: String, serverId: String): HomeNetworkResponse
}

internal class NetworkRaftAppLinkPreviewApi(
    private val networkClient: RaftNetworkClient,
) : RaftAppLinkPreviewApi {
    override suspend fun readPreviews(messageId: String, serverId: String): HomeNetworkResponse =
        networkClient.request(
            // An on-screen message's own decoration: below what the person asked for.
            priority = DataRequestPriority.Visible,
            path = "messages/${messageId.appLinkPathSegment()}/app-link-previews",
            serverIdOverride = ServerId(serverId),
        )

    override suspend fun allow(appId: String, serverId: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "integrations/clients/${appId.appLinkPathSegment()}/link-preview-consent",
            method = "POST",
            serverIdOverride = ServerId(serverId),
            bodyText = "{}",
        )

    override suspend fun decline(appId: String, serverId: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "integrations/clients/${appId.appLinkPathSegment()}/link-preview-decline",
            method = "POST",
            serverIdOverride = ServerId(serverId),
            bodyText = "{}",
        )
}

/** `encodeURIComponent` for one path segment. */
internal fun String.appLinkPathSegment(): String = buildString {
    for (byte in this@appLinkPathSegment.encodeToByteArray()) {
        val char = (byte.toInt() and 0xff).toChar()
        if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-_.!~*'()") {
            append(char)
        } else {
            append('%')
            append("0123456789ABCDEF"[(byte.toInt() shr 4) and 0xf])
            append("0123456789ABCDEF"[byte.toInt() and 0xf])
        }
    }
}
