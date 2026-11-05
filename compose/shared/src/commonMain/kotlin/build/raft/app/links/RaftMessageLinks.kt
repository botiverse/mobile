package build.raft.app.links

import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.RaftClientConfigAppHosts
import build.raft.app.data.appLinkPreviewHref
import build.raft.app.data.parseArtifactLinkTarget
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.raftSchemeLinkToWebLink
import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/*
 * The one place the app recognises the links of a message, a port of the web's pipeline:
 *  - slock packages/shared/src/appLinkPreview.ts `messageBareLinks` / `visibleBareLinks`;
 *  - packages/shared/src/raftPermalinks.ts `parseRaftPermalink`;
 *  - packages/web/src/components/message/quotedMessagePermalink.ts `extractFirstQuotedMessagePermalink`;
 *  - packages/web/src/components/message/useMessageLinkCards.ts (the split into objects and Apps,
 *    MESSAGE_OBJECT_LINK_LIMIT).
 * Code is decided by the markdown parser, as on the web (`markdownRanges.ts`), never by regexes.
 *
 * One known difference: the web's parser has indented code switched off (an indented line in chat is
 * prose); the app's markdown parser has no such switch, so a link in a 4-space-indented line is not
 * found as a bare link here (the quoted-permalink scan treats such a line as prose, as the web does).
 */

/** web `visibleBareLinks`: only the first 32,000 characters of a message are read. */
internal const val MESSAGE_LINK_SCAN_LIMIT: Int = 32_000

/** web `MESSAGE_OBJECT_LINK_LIMIT`: first-party object references one message reads. */
internal const val MESSAGE_OBJECT_LINK_LIMIT: Int = 10

private val markdownParser = MarkdownParser(RaftGFMFlavourDescriptor())

// --- URLs -----------------------------------------------------------------------------------------

/** An http(s) URL as the WHATWG parser reads it, for the parts these rules look at. */
internal data class RaftHttpUrl(
    val scheme: String,
    val userInfo: String?,
    val host: String,
    val port: Int?,
    val path: String,
    val query: String?,
    val fragment: String?,
) {
    /** `URL.href`: scheme and host lower-cased, the scheme's default port dropped, an empty path `/`. */
    val href: String
        get() =
            buildString {
                append(scheme).append("://")
                if (userInfo != null) append(userInfo).append('@')
                append(host)
                if (port != null) append(':').append(port)
                append(path)
                if (query != null) append('?').append(query)
                if (fragment != null) append('#').append(fragment)
            }
}

internal fun parseRaftHttpUrl(value: String): RaftHttpUrl? {
    val raw = value.trim()
    val schemeEnd = raw.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = raw.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return null
    if (raw.any { it.isWhitespace() || it.code < 0x20 || it == '\\' }) return null
    var rest = raw.substring(schemeEnd + 3)
    val fragment = if ('#' in rest) rest.substringAfter('#').also { rest = rest.substringBefore('#') } else null
    val query = if ('?' in rest) rest.substringAfter('?').also { rest = rest.substringBefore('?') } else null
    val pathStart = rest.indexOf('/')
    val authority = if (pathStart < 0) rest else rest.substring(0, pathStart)
    val path = if (pathStart < 0) "/" else rest.substring(pathStart)
    if (authority.isEmpty()) return null
    val userInfo = if ('@' in authority) authority.substringBeforeLast('@') else null
    val hostAndPort = authority.substringAfterLast('@')
    val host = hostAndPort.substringBefore(':').lowercase()
    if (host.isEmpty()) return null
    val portText = if (':' in hostAndPort) hostAndPort.substringAfter(':') else null
    val port =
        if (portText == null || portText.isEmpty()) {
            null
        } else {
            portText.toIntOrNull()?.takeIf { it in 0..65535 } ?: return null
        }
    val defaultPort = if (scheme == "https") 443 else 80
    return RaftHttpUrl(scheme, userInfo, host, port?.takeUnless { it == defaultPort }, path, query, fragment)
}

/** web `messageLinkKey`: the key a link's card is looked up by, its WHATWG href. Null when it isn't an http(s) URL. */
internal fun raftMessageLinkKey(href: String): String? = parseRaftHttpUrl(href)?.href

// --- bare links (web messageBareLinks) ----------------------------------------------------------

/**
 * Every visible, bare http(s) link of [content], once each, in message order, as WHATWG hrefs. Bare:
 * its text is its own address (an autolink, `<url>`, `[url](url)`, or a reference link whose text is
 * the address). A link the author gave words to, links in code or math, in images and unused
 * definitions do not count.
 */
internal fun raftMessageBareLinks(content: String): List<String> {
    if ("://" !in content) return emptyList()
    val text = content.take(MESSAGE_LINK_SCAN_LIMIT)
    val tree = markdownParser.buildMarkdownTreeFromString(text)
    val definitions = HashMap<String, String>()
    fun collect(node: ASTNode) {
        if (node.type == MarkdownElementTypes.LINK_DEFINITION) {
            val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
            val destination = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
            if (label != null && destination != null) {
                definitions.getOrPut(label.getTextInNode(text).toString().referenceLabelKey()) {
                    destination.getTextInNode(text).toString().unwrapDestination()
                }
            }
            return
        }
        node.children.forEach(::collect)
    }
    collect(tree)

    val urls = LinkedHashSet<String>()
    fun accept(raw: String, linkText: String) {
        val target = parseRaftHttpUrl(raw) ?: return
        val shown = parseRaftHttpUrl(linkText.trim()) ?: return
        if (shown.href == target.href) urls += target.href
    }
    fun visit(node: ASTNode) {
        when (node.type) {
            MarkdownElementTypes.CODE_SPAN,
            MarkdownElementTypes.CODE_FENCE,
            MarkdownElementTypes.CODE_BLOCK,
            GFMElementTypes.INLINE_MATH,
            GFMElementTypes.BLOCK_MATH,
            MarkdownElementTypes.IMAGE,
            MarkdownElementTypes.LINK_DEFINITION,
            -> return
            MarkdownElementTypes.INLINE_LINK -> {
                val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                val destination = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                if (label != null && destination != null) {
                    accept(destination.getTextInNode(text).toString().unwrapDestination(), label.bracketedText(text))
                }
                return
            }
            MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
                val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                val reference =
                    node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
                        ?.getTextInNode(text)?.toString()
                        ?: label?.bracketedText(text)
                val destination = reference?.referenceLabelKey()?.let(definitions::get)
                val shown = label?.bracketedText(text) ?: reference
                if (destination != null && shown != null) accept(destination, shown)
                return
            }
            MarkdownElementTypes.AUTOLINK -> {
                val url = node.getTextInNode(text).toString().unwrapDestination()
                accept(url, url)
                return
            }
            GFMTokenTypes.GFM_AUTOLINK -> {
                val url = node.getTextInNode(text).toString()
                accept(url, url)
                return
            }
        }
        node.children.forEach(::visit)
    }
    visit(tree)
    return urls.toList()
}

private fun ASTNode.bracketedText(text: CharSequence): String =
    getTextInNode(text).toString().removePrefix("[").removeSuffix("]")

private fun String.unwrapDestination(): String = trim().removePrefix("<").removeSuffix(">")

/** CommonMark label matching: case-folded, inner whitespace collapsed, brackets removed. */
private fun String.referenceLabelKey(): String =
    trim().removePrefix("[").removeSuffix("]").trim().lowercase().replace(Regex("\\s+"), " ")

// --- classification (web useMessageLinkCards) ---------------------------------------------------

/**
 * What a message's bare links become (web `useMessageLinkCards`): first-party [objects] (Artifacts,
 * [MESSAGE_OBJECT_LINK_LIMIT] distinct references at most, the first link of each reference in
 * [objectCards]) and the third-party [appLinks] the App link read covers. A first-party link is never an
 * App's, and a first-party link past the limit stays the author's link (no chip, no card, no App).
 */
internal data class RaftMessageLinkPlan(
    /** Every bare link, in message order. */
    val bare: List<String>,
    /** href to Artifact, for each first-party link within the limit (all links of an accepted reference). */
    val objects: Map<String, ArtifactLinkTarget>,
    /** The first link of each accepted reference, in message order: the cards under the message. */
    val objectCards: List<String>,
    /** Public https links that are not first-party, in message order. */
    val appLinks: List<String>,
)

internal fun planRaftMessageLinks(
    content: String,
    appHosts: Collection<String>,
    artifactsEnabled: Boolean = true,
): RaftMessageLinkPlan {
    val bare = raftMessageBareLinks(content)
    if (bare.isEmpty()) return RaftMessageLinkPlan(emptyList(), emptyMap(), emptyList(), emptyList())
    val firstParty = LinkedHashMap<String, ArtifactLinkTarget>()
    if (artifactsEnabled) {
        for (url in bare) parseArtifactLinkTarget(url, appHosts)?.let { firstParty[url] = it }
    }
    val accepted = LinkedHashMap<String, ArtifactLinkTarget>()
    for (target in firstParty.values) {
        if (accepted.size >= MESSAGE_OBJECT_LINK_LIMIT) break
        if (target.key !in accepted) accepted[target.key] = target
    }
    val objects = LinkedHashMap<String, ArtifactLinkTarget>()
    val objectCards = ArrayList<String>()
    val listed = HashSet<String>()
    for ((url, target) in firstParty) {
        if (target.key !in accepted) continue
        objects[url] = target
        if (listed.add(target.key)) objectCards += url
    }
    val appLinks = bare.filter { it !in firstParty && appLinkPreviewHref(it) != null }
    return RaftMessageLinkPlan(bare, objects, objectCards, appLinks)
}

// --- Raft message permalinks (web parseRaftPermalink) -------------------------------------------

internal data class ParsedRaftPermalink(
    /** The web URL as written (trimmed). */
    val rawUrl: String,
    val serverSlug: String,
    val routeKind: ConversationKind,
    val channelId: ChannelId,
    val messageId: MessageId,
    val threadParentMessageId: MessageId?,
)

/** web DEFAULT_RAFT_PERMALINK_HOSTNAMES. */
private val DefaultPermalinkHostnames = setOf("app.slock.ai", "staging.slock.ai", "app.raft.build")

private val PermalinkPath = Regex("^/s/([^/]+)/(channel|dm)/([0-9a-f-]+)$", RegexOption.IGNORE_CASE)
private val ThreadParam = Regex("^([0-9a-f-]+):([\\s\\S]+)$", RegexOption.IGNORE_CASE)
private val LeadingMessageId =
    Regex(
        "^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{8})(?![0-9a-f-])",
        RegexOption.IGNORE_CASE,
    )

/**
 * The hostnames a permalink is recognised on: the web's fixed defaults, the server's `appHosts`
 * (client config) and the host of the configured web URL (the web's "page host").
 */
internal fun raftPermalinkHostnames(appHosts: Collection<String>, configuredWebUrl: String? = null): Set<String> =
    buildSet {
        addAll(DefaultPermalinkHostnames)
        appHosts.forEach { host -> host.trim().lowercase().removeSuffix(".").takeIf(String::isNotEmpty)?.let(::add) }
        configuredWebUrl?.let(::parseRaftHttpUrl)?.host?.let(::add)
    }

/** web `parseRaftPermalink`, with the exact-hostname allow-list passed in as [hostnames]. */
internal fun parseRaftPermalink(href: String, hostnames: Set<String>): ParsedRaftPermalink? {
    val url = parseRaftHttpUrl(href) ?: return null
    if (url.host !in hostnames) return null
    val match = PermalinkPath.matchEntire(url.path) ?: return null
    val params = queryParams(url.query)

    var threadParentMessageId: String? = null
    val threadParam = params["thread"]
    if (!threadParam.isNullOrEmpty()) {
        val thread = ThreadParam.matchEntire(threadParam) ?: return null
        if (thread.groupValues[1] != match.groupValues[3]) return null
        threadParentMessageId = leadingMessageId(thread.groupValues[2]) ?: return null
    }
    // `msg` pinpoints a message; a thread-only URL targets the thread parent.
    val explicitParam = params["msg"]
    val explicitId = explicitParam?.let { leadingMessageId(it) ?: return null }
    val messageId = explicitId ?: threadParentMessageId ?: return null

    return ParsedRaftPermalink(
        rawUrl = href.trim(),
        serverSlug = match.groupValues[1].percentDecoded() ?: return null,
        routeKind = if (match.groupValues[2].lowercase() == "dm") ConversationKind.DirectMessage else ConversationKind.Channel,
        channelId = ChannelId(match.groupValues[3]),
        messageId = MessageId(messageId),
        threadParentMessageId = threadParentMessageId?.let(::MessageId),
    )
}

/**
 * [parseRaftPermalink] on the hosts this installation knows ([raftPermalinkHostnames]: the defaults,
 * the client config's `appHosts`, and [configuredWebUrl]'s host).
 */
internal fun String.toParsedRaftPermalink(
    configuredWebUrl: String? = null,
    appHosts: Collection<String> = RaftClientConfigAppHosts.current,
): ParsedRaftPermalink? = parseRaftPermalink(this, raftPermalinkHostnames(appHosts, configuredWebUrl))

private fun leadingMessageId(value: String): String? = LeadingMessageId.find(value)?.groupValues?.get(1)

/** `URLSearchParams.get`: the first value of a key, `+` a space, `%XX` UTF-8 decoded (malformed escapes stay as written). */
private fun queryParams(query: String?): Map<String, String> {
    if (query.isNullOrEmpty()) return emptyMap()
    val result = LinkedHashMap<String, String>()
    for (part in query.split('&')) {
        if (part.isEmpty()) continue
        val key = part.substringBefore('=').replace('+', ' ').percentDecodedLenient()
        val value = part.substringAfter('=', "").replace('+', ' ').percentDecodedLenient()
        if (key !in result) result[key] = value
    }
    return result
}

private fun String.percentDecodedLenient(): String = percentDecoded() ?: this

/** `decodeURIComponent`: null on a malformed escape. */
private fun String.percentDecoded(): String? {
    if ('%' !in this) return this
    val bytes = ArrayList<Byte>(length)
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char == '%') {
            if (index + 2 >= length) return null
            val value = substring(index + 1, index + 3).toIntOrNull(16) ?: return null
            bytes += value.toByte()
            index += 3
        } else {
            char.toString().encodeToByteArray().forEach { bytes += it }
            index++
        }
    }
    return runCatching { bytes.toByteArray().decodeToString(throwOnInvalidSequence = true) }.getOrNull()
}

// --- the quoted message's permalink (web extractFirstQuotedMessagePermalink) ----------------------

internal data class ExtractedQuotedPermalink(
    /** The web URL as written (a `raft://` link already mapped to it). */
    val rawUrl: String,
    val parsed: ParsedRaftPermalink,
)

private const val CjkUrlBoundaryPunctuation = "，。、；：！？）》」』】》〉”’（《「『【〈“‘"
private val QuotedUrlPattern = Regex("(?:https?|raft)://[^\\s<>\"'$CjkUrlBoundaryPunctuation]+")
private val TrailingPunctuation = Regex("[),.!?;:，。！？；：]+$")

/**
 * The first Raft permalink to a message of [currentServerSlug] in [content] (the one the quoted
 * message card shows). The text outside markdown code and math is scanned for `http(s)://` and
 * `raft://` URLs; one right after `(` (a markdown link target) is skipped; trailing punctuation is
 * dropped; only the current server's links count. Later permalinks get no card.
 */
internal fun extractFirstQuotedRaftPermalink(
    content: String,
    hostnames: Set<String>,
    currentServerSlug: String?,
    /** The web origin a `raft://` link is mapped onto ([raftSchemeLinkToWebLink]); null: production. */
    webBaseUrl: String? = null,
): ExtractedQuotedPermalink? {
    if (currentServerSlug == null || !("http" in content || "raft://" in content)) return null
    val tree = markdownParser.buildMarkdownTreeFromString(content)
    val code = ArrayList<IntRange>()
    fun collect(node: ASTNode) {
        when (node.type) {
            MarkdownElementTypes.CODE_SPAN,
            MarkdownElementTypes.CODE_FENCE,
            GFMElementTypes.INLINE_MATH,
            GFMElementTypes.BLOCK_MATH,
            -> {
                code += node.startOffset until node.endOffset
                return
            }
        }
        node.children.forEach(::collect)
    }
    collect(tree)
    code.sortBy { it.first }

    var cursor = 0
    val chunks = ArrayList<String>()
    for (range in code) {
        if (range.first > cursor) chunks += content.substring(cursor, range.first)
        cursor = maxOf(cursor, range.last + 1)
    }
    if (cursor < content.length) chunks += content.substring(cursor)

    for (chunk in chunks) {
        for (match in QuotedUrlPattern.findAll(chunk)) {
            val start = match.range.first
            val raw = match.value
            // `raft` must not be the tail of a word (web: `(?<![\p{L}\p{N}_])raft`).
            if (raw.startsWith("raft") && start > 0 && chunk[start - 1].let { it.isLetterOrDigit() || it == '_' }) continue
            if (start > 0 && chunk[start - 1] == '(') continue
            val trailing = TrailingPunctuation.find(raw)?.value.orEmpty()
            val written = if (trailing.isEmpty()) raw else raw.dropLast(trailing.length)
            val candidate = if (written.startsWith("raft:")) raftSchemeLinkToWebLink(written, webBaseUrl) else written
            if (candidate == null) continue
            val parsed = parseRaftPermalink(candidate, hostnames) ?: continue
            if (parsed.serverSlug != currentServerSlug) continue
            return ExtractedQuotedPermalink(candidate, parsed)
        }
    }
    return null
}
