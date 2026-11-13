package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val appLinkPreviewsJson = Json {
    ignoreUnknownKeys = true
}

/**
 * Typed boundary of App link previews (artin #20 lane L), as the web reads them.
 *
 * Server contract (botiverse/slock staging 2d4f961df; the card catalog re-checked at
 * 3652d3703232dd2e61b8f07b0217befe94337118):
 *  - `GET messages/:messageId/app-link-previews` (`packages/server/src/routes/messages.ts`,
 *    `services/appLinkPreviewService.ts`) answers
 *    `{ previews: [{ version: 1, url, appName, title, card? }], prompts: [{ url, app: { id, name, icon } }],
 *    appIcons: { [previewUrl]: string | null } }` with `Cache-Control: private, no-store`; 404 for
 *    a message the viewer can't see, a 429 (60 reads a minute per person) answers
 *    `{ previews: [], prompts: [] }`. The
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
 * read drops only its own list. Each preview is strict (`.strict()`): an unknown key, or a `card`
 * outside the read-only catalog ([RaftAppLinkCard]), makes it unreadable, like the web. One
 * result per link, one prompt per App. A conversation's messages are read with the batched
 * link-card read instead ([AppLinkCardV2.kt]), declaring
 * [APP_LINK_CARD_READ_CAPABILITIES] (`card.v2`, `inline.v1`, `inline.tone.v2`, `button.v1`, `confirm.v1`, `undo.v1`, `block.v1`, `preview.v3`, `unreviewed_app.v1`).
 *
 * Field names are exact camelCase. No snake_case fallbacks.
 */
internal const val APP_LINK_PREVIEW_URL_MAX_LENGTH = 2000

/** The links a v1 read covers (`messagePreviewUrls(content, 3)` on the server). */
internal const val APP_LINK_PREVIEW_V1_LINK_LIMIT = 3

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

/**
 * The App as Raft stores it: its name and its uploaded icon (never an image from the preview).
 * [reviewed] is the batched read item's `app.reviewed` (RFC 076 section 17, botiverse/slock #13945
 * `LinkCardReadApp`): `false` for an App acting under its own Server's confirmation, which Raft has
 * not reviewed (only sent to a client that declares `unreviewed_app.v1`); absent (null) otherwise,
 * official Apps included. The read item's field present with any other value also reads `false`
 * (fail-safe: confirm first). Every `execute` button of an App with `reviewed == false` goes through
 * Raft's confirmation first ([build.raft.app.ThreadAppLinkCardPress]).
 */
data class AppLinkApp(val name: String, val icon: String?, val reviewed: Boolean? = null) {
    /** `app.reviewed: false`: the App's acting buttons confirm first, undo-window ones too. */
    val unreviewed: Boolean get() = reviewed == false
}

/** An App the viewer hasn't answered yet on this Server. */
data class AppLinkPromptApp(val id: String, val name: String, val icon: String?)

/**
 * How one link of a message is shown (protocol v3.2.3 addendum 5 §2; web `AppLinkResult`). A
 * link without a result stays the author's link.
 */
sealed interface AppLinkResult {
    /** The link's normalized href, the key it is looked up by. */
    val url: String

    /**
     * A cyan chip with the App's icon and its title; [card], when the App sent one, is the
     * read-only body the chip's card shows (json-render catalog v1, already validated).
     */
    data class Card(
        override val url: String,
        val app: AppLinkApp,
        val title: String,
        val card: RaftAppLinkCard? = null,
        /** A v2 answer's card (the batched read), in place of [card]. */
        val cardV2: RaftAppLinkCardV2? = null,
        /** A v2 answer's inline label: its title is the chip's words ([chipTitle]). */
        val inline: AppLinkInline? = null,
        /** A version 3 `card` item with `placement: "block"`: drawn after the link's paragraph, never in a chip's card. */
        val block: AppLinkBlockCard? = null,
        /**
         * Whether the link is drawn as the chip. Always, except a version 3 answer that lists a block
         * card and no `inline`: the link stays the author's plain link and only the card is drawn.
         */
        val chip: Boolean = true,
        /** A version 3 `connect_account` item: the chip's card asks the reader to connect, with one button to this URL. */
        val connectUrl: String? = null,
        /** The signed handle per `execute` action id Raft gave this card (version 3; see [AppLinkBlockCard]): what a pressed button sends. */
        val handles: Map<String, String> = emptyMap(),
    ) : AppLinkResult {
        /** The words on the chip (web `appLinkChipTitle`): the inline title, else [title]. The card always shows [title]. */
        val chipTitle: String get() = inline?.title ?: title

        /** Whether the App sent a card body to open (v1 [card] or v2 [cardV2]). */
        val hasCardBody: Boolean get() = card != null || cardV2 != null || connectUrl != null
    }

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
    return appLinkResultsWithPrompts(results, list.prompts.orEmpty(), resolveIcon)
}

/** Adds an answer's prompts (the GET's and the batched read's are alike: web `withPrompts`), one per App, on links without a result. */
internal fun appLinkResultsWithPrompts(
    results: Map<String, AppLinkResult>,
    prompts: List<JsonElement>,
    resolveIcon: (String) -> String?,
): Map<String, AppLinkResult> {
    val all = LinkedHashMap(results)
    val asked = mutableSetOf<String>()
    readAppLinkPrompts(prompts)?.forEach { prompt ->
        if (prompt.url in all || !asked.add(prompt.app.id)) return@forEach
        all[prompt.url] = prompt.copy(app = prompt.app.copy(icon = prompt.app.icon?.let(resolveIcon)))
    }
    return all
}

/** The previews as one list, or null when any of them (or `appIcons`) can't be read. */
private fun readAppLinkPreviews(list: WireAppLinkPreviewList): List<AppLinkResult.Card>? {
    val previews = list.previews.orEmpty()
    if (previews.size > 10) return null
    val icons = list.appIcons.orEmpty()
    if (icons.values.any { (it?.length ?: 0) > APP_LINK_PREVIEW_URL_MAX_LENGTH }) return null
    return previews.map { element -> readAppLinkPreview(element, icons) ?: return null }
}

/** The keys a v1 list preview may carry (`appLinkPreviewResponseSchema.extend({ url, appName })`, strict). */
private val APP_LINK_PREVIEW_KEYS = setOf("version", "title", "card", "url", "appName")

/** The keys of a v1 preview itself (`appLinkPreviewResponseSchema`, strict): a batched read's `v1` item. */
internal val APP_LINK_PREVIEW_CONTENT_KEYS = setOf("version", "title", "card")

/** A v1 preview's title and card (`appLinkPreviewResponseSchema`) when [obj] has only [keys], or null. */
internal fun readAppLinkPreviewContent(obj: JsonObject, keys: Set<String>): Pair<String, RaftAppLinkCard?>? {
    if (!keys.containsAll(obj.keys)) return null
    if (!obj["version"].isJsonNumber(1.0)) return null
    val title = obj["title"].jsonStringOrNull()?.takeIf { it.length in 1..160 } ?: return null
    val card = if ("card" in obj) parseRaftAppLinkCard(obj.getValue("card")) ?: return null else null
    return title to card
}

/** One preview of the list, or null when the web's schema would refuse it. */
private fun readAppLinkPreview(element: JsonElement, icons: Map<String, String?>): AppLinkResult.Card? {
    val obj = element as? JsonObject ?: return null
    val (title, card) = readAppLinkPreviewContent(obj, APP_LINK_PREVIEW_KEYS) ?: return null
    val rawUrl = obj["url"].jsonStringOrNull() ?: return null
    val url = appLinkPreviewHref(rawUrl) ?: return null
    val appName = obj["appName"].jsonStringOrNull()?.takeIf { it.length <= 300 } ?: return null
    return AppLinkResult.Card(url = url, app = AppLinkApp(name = appName, icon = icons[rawUrl]), title = title, card = card)
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

    /**
     * `POST messages/:messageId/link-cards/read` (the batched link-card read): 404 where the server
     * has no batched read.
     */
    suspend fun readLinkCards(messageId: String, serverId: String, body: AppLinkCardReadRequestBody): HomeNetworkResponse

    /** `POST link-cards/prepare` (RFC 076 section 6.2): the description a `confirm: "prepare"` button shows. */
    suspend fun prepareLinkCardAction(serverId: String, body: AppLinkPrepareRequestBody): HomeNetworkResponse =
        throw UnsupportedOperationException("prepareLinkCardAction")

    /** `POST link-cards/execute` (RFC 076 section 6.3). */
    suspend fun executeLinkCardAction(serverId: String, body: AppLinkExecuteRequestBody): HomeNetworkResponse =
        throw UnsupportedOperationException("executeLinkCardAction")

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

    override suspend fun readLinkCards(messageId: String, serverId: String, body: AppLinkCardReadRequestBody): HomeNetworkResponse =
        networkClient.request(
            // The same on-screen decoration as the GET it replaces.
            priority = DataRequestPriority.Visible,
            path = "messages/${messageId.appLinkPathSegment()}/link-cards/read",
            method = "POST",
            serverIdOverride = ServerId(serverId),
            bodyText = body.toJson(),
        )

    override suspend fun prepareLinkCardAction(serverId: String, body: AppLinkPrepareRequestBody): HomeNetworkResponse =
        networkClient.request(
            // A person pressed a button.
            priority = DataRequestPriority.Foreground,
            path = "link-cards/prepare",
            method = "POST",
            serverIdOverride = ServerId(serverId),
            bodyText = body.toJson(),
        )

    override suspend fun executeLinkCardAction(serverId: String, body: AppLinkExecuteRequestBody): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "link-cards/execute",
            method = "POST",
            serverIdOverride = ServerId(serverId),
            bodyText = body.toJson(),
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

/**
 * An App's read-only link card (botiverse/slock 3652d3703 `packages/shared/src/appLinkPreview.ts`
 * `appPreviewCardSchema`, json-render catalog version 1): one bounded tree of [elements] from
 * [root]. Read only through [parseRaftAppLinkCard], so a value always holds the schema's limits:
 * at most [RAFT_APP_LINK_CARD_MAX_ELEMENTS] elements, every element reached exactly once from
 * [root], at most [RAFT_APP_LINK_CARD_MAX_DEPTH] levels below it, ids matching
 * `^[a-zA-Z][a-zA-Z0-9_-]{0,63}$`. No expressions, actions or remote content.
 *
 * [version] is the catalog the card was read with; version 2 (RFC 076: Stack direction/wrap,
 * Text warning/mono, RelativeTime, Duration, Button) is not read yet and adds its own
 * [RaftAppLinkCardElement] subtypes when it is.
 */
data class RaftAppLinkCard(
    val root: String,
    /** By element id, in the App's order. */
    val elements: Map<String, RaftAppLinkCardElement>,
    val version: Int = 1,
) {
    /** The element [id] names, or null (a child id always names one in a parsed card). */
    fun element(id: String): RaftAppLinkCardElement? = elements[id]
}

/**
 * One element of a [RaftAppLinkCard] (the catalog's discriminated `type`). A [CardElement], so the
 * bounded-tree check is the one action cards use ([checkCardBoundedTree], `cardElements.ts`).
 * Catalog version 2 takes its Stack, RelativeTime and Duration from the shared [CardStack],
 * [CardRelativeTime] and [CardDuration], as `action-card.v1` does.
 */
sealed interface RaftAppLinkCardElement : CardElement {
    /** `Stack`: its [children] (element ids, at most 12) top to bottom; no props in v1. */
    data class Stack(val children: List<String>) : RaftAppLinkCardElement {
        override val childIds: List<String> get() = children
    }

    /** `Text`: 1-500 characters of plain text. */
    data class Text(val text: String, val tone: RaftAppLinkTextTone = RaftAppLinkTextTone.Default) : RaftAppLinkCardElement

    /** `Badge`: the App's state in its own words, 1-60 characters (a neutral soft badge on the web). */
    data class Badge(val text: String) : RaftAppLinkCardElement
}

/** `Text.props.tone` (default `default`). */
enum class RaftAppLinkTextTone { Default, Muted }

const val RAFT_APP_LINK_CARD_MAX_ELEMENTS = 16
const val RAFT_APP_LINK_CARD_MAX_DEPTH = 4

/**
 * Reads a v1 `card` exactly as `appPreviewCardSchema` does, or null when it would refuse it:
 * strict objects throughout (no unknown keys, no `state`, `on`, `visible` or expressions), a
 * known element `type`, the props' types and lengths, at most 16 elements, no reserved ids, and
 * one tree from `root` that reaches every element once, at most 4 levels deep.
 */
fun parseRaftAppLinkCard(element: JsonElement): RaftAppLinkCard? {
    val obj = element as? JsonObject ?: return null
    if (!setOf("root", "elements").containsAll(obj.keys)) return null
    val root = obj["root"].jsonStringOrNull()?.takeIf(CARD_ELEMENT_ID::matches) ?: return null
    val rawElements = obj["elements"] as? JsonObject ?: return null
    val elements = LinkedHashMap<String, RaftAppLinkCardElement>()
    for ((id, raw) in rawElements) {
        if (!CARD_ELEMENT_ID.matches(id)) return null
        elements[id] = readRaftAppLinkCardElement(raw) ?: return null
    }
    if (checkCardBoundedTree(root, elements, RAFT_APP_LINK_CARD_MAX_ELEMENTS, RAFT_APP_LINK_CARD_MAX_DEPTH) != null) return null
    return RaftAppLinkCard(root = root, elements = elements)
}

private fun readRaftAppLinkCardElement(raw: JsonElement): RaftAppLinkCardElement? {
    val obj = raw as? JsonObject ?: return null
    val props = obj["props"] as? JsonObject ?: return null
    return when (obj["type"].jsonStringOrNull()) {
        "Stack" -> {
            if (!setOf("type", "props", "children").containsAll(obj.keys) || props.isNotEmpty()) return null
            val children =
                if ("children" in obj) {
                    val list = obj["children"] as? JsonArray ?: return null
                    if (list.size > CARD_STACK_MAX_CHILDREN) return null
                    list.map { child -> child.jsonStringOrNull()?.takeIf(CARD_ELEMENT_ID::matches) ?: return null }
                } else {
                    emptyList()
                }
            RaftAppLinkCardElement.Stack(children)
        }
        "Text" -> {
            if (!setOf("type", "props").containsAll(obj.keys) || !setOf("text", "tone").containsAll(props.keys)) return null
            val text = props["text"].jsonStringOrNull()?.takeIf { it.length in 1..500 } ?: return null
            val tone =
                if ("tone" in props) {
                    when (props["tone"].jsonStringOrNull()) {
                        "default" -> RaftAppLinkTextTone.Default
                        "muted" -> RaftAppLinkTextTone.Muted
                        else -> return null
                    }
                } else {
                    RaftAppLinkTextTone.Default
                }
            RaftAppLinkCardElement.Text(text, tone)
        }
        "Badge" -> {
            if (!setOf("type", "props").containsAll(obj.keys) || !setOf("text").containsAll(props.keys)) return null
            RaftAppLinkCardElement.Badge(props["text"].jsonStringOrNull()?.takeIf { it.length in 1..60 } ?: return null)
        }
        else -> null
    }
}

/** A JSON string's value; null for anything else (a number or `null` is not a string to zod). */
private fun JsonElement?.jsonStringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A JSON number equal to [value] (zod `z.literal(1)` takes `1` and `1.0`, never `"1"`). */
private fun JsonElement?.isJsonNumber(value: Double): Boolean =
    (this as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content?.toDoubleOrNull() == value
