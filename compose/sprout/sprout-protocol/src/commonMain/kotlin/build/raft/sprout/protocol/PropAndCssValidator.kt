package build.raft.sprout.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.math.round

typealias ResourceResolver = (url: String, kind: ResourceKind) -> String?

data class ResourcePolicy(
    val origins: List<String>? = null,
    val httpOrigins: List<String>? = null,
    val resourceBase: String? = null,
    val ownsBlob: ((url: String) -> Boolean)? = null,
)

fun isSafeHttpActionUrl(url: String, maxChars: Int = ProtocolLimits.MAX_URL_CHARS): Boolean =
    PropAndCssValidator.isSafeHttpUrl(url, maxChars)

internal object PropAndCssValidator {
    const val MAX_INLINE_IMAGE_SIDE: Int = 2048
    const val MAX_INLINE_IMAGE_PIXELS: Int = 1_048_576

    private val LENGTH_REGEX = Regex("^[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:px|%|em)?$")
    private val STRICT_ID_REGEX = Regex("^[A-Za-z][\\w-]{0,40}$")
    private val ID_REF_REGEX = Regex("^url\\(\\s*#([A-Za-z][\\w-]{0,40})\\s*\\)$")
    private val HEX_COLOR_REGEX = Regex("^#(?:[0-9a-f]{3}|[0-9a-f]{4}|[0-9a-f]{6}|[0-9a-f]{8})$", RegexOption.IGNORE_CASE)
    private val FUNCTION_COLOR_REGEX = Regex(
        "^(?:rgba?|hsla?|oklch)\\(\\s*[-+]?[\\d.]+(?:%|deg)?(?:[\\s,/]+[-+]?[\\d.]+(?:%|deg)?){2,3}\\s*\\)$",
        RegexOption.IGNORE_CASE,
    )
    private val NAMED_COLORS: Set<String> = setOf(
        "black", "white", "gray", "grey", "silver", "red", "orange", "yellow", "gold", "green", "lime", "teal", "cyan",
        "blue", "navy", "purple", "magenta", "pink", "brown", "tan", "beige", "salmon", "coral", "crimson", "tomato",
        "skyblue", "steelblue", "royalblue", "indigo", "violet", "orchid", "olive", "khaki", "ivory", "lightgray",
        "lightgrey", "darkgray", "darkgrey", "dimgray", "slategray", "transparent",
    )

    private val INLINE_IMAGE_DATA_REGEX = Regex("^data:image/(png|jpeg|webp);base64,([A-Za-z0-9+/]+={0,2})$")
    private val ANY_SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val HTTP_SCHEME_PREFIX_REGEX = Regex("^https?://", RegexOption.IGNORE_CASE)
    private val CLASS_NAME_REGEX = Regex("^-?[A-Za-z_][\\w-]*$")
    private const val MAX_CLASSES = 32
    private val PROSE_ATTRIBUTES: Set<String> = setOf(
        "alt", "title", "placeholder", "label", "value", "defaultValue",
        "aria-label", "aria-description", "aria-valuetext", "aria-placeholder", "aria-roledescription",
        "summary", "abbr",
    )

    private val TRANSFORM_FUNC_REGEX = Regex("([A-Za-z]+)\\s*\\(([^()]*)\\)")
    private val TRANSFORM_ARG_REGEX = Regex("^[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:e[-+]?\\d+)?(px|deg)?$", RegexOption.IGNORE_CASE)

    private val OPEN_TAG_REGEX = Regex("^[a-z][A-Za-z0-9]*$")
    private val OPEN_ATTR_REGEX = Regex("^[A-Za-z][A-Za-z0-9:_-]{0,60}$")
    private val OPEN_ID_REGEX = Regex("^[A-Za-z_][\\w-]{0,80}$")
    private val STYLE_PROP_REGEX = Regex("^(?:--)?[A-Za-z][A-Za-z0-9-]{0,60}$")
    private val URL_CALL_COUNT_REGEX = Regex("url\\(", RegexOption.IGNORE_CASE)
    private val URL_CALL_REWRITE_REGEX = Regex("url\\(\\s*(['\"]?)([^)'\"]*)\\1\\s*\\)", RegexOption.IGNORE_CASE)
    private val LOCAL_URL_HASH_REGEX = Regex("url\\(#[^)]*\\)", RegexOption.IGNORE_CASE)
    private val SMIL_TIMING_REGEX = Regex("(^|[;\\s])([A-Za-z_][\\w-]*)\\.(begin|end|click|mouseover|mouseout|mouseenter|mouseleave|focus|blur|repeat)")
    private val STEP2_OPEN_EVENT_REGEX = Regex("^[a-z][A-Za-z]{1,40}$")

    private val NESTING_AT_RULES_REGEX = Regex("^@(media|supports|container|layer)\\b", RegexOption.IGNORE_CASE)
    private val AT_RULE_RESOURCE_REGEX = Regex("@(?:import|font-face|namespace)")
    private val KEYFRAMES_REGEX = Regex("^@(?:-webkit-)?keyframes\\s+([A-Za-z_][\\w-]*)\\s*$", RegexOption.IGNORE_CASE)
    private val KEYFRAME_STEP_REGEX = Regex("^[\\d.%\\s,a-z]+$", RegexOption.IGNORE_CASE)
    private val ROOT_SELECTOR_PREFIX_REGEX = Regex("^(?::root|html|body)\\b", RegexOption.IGNORE_CASE)
    private val ANIMATION_PROP_REGEX = Regex("^(?:-webkit-)?animation(?:-name)?$", RegexOption.IGNORE_CASE)
    private val IDENT_WORD_REGEX = Regex("[A-Za-z_][\\w-]*")
    private val CSS_URL_FUNCTION_REGEX = Regex("url\\s*\\(\\s*(['\"]?)\\s*([^)'\"]*)")
    private val CSS_EXPRESSION_REGEX = Regex("expression\\s*\\(")
    private val CSS_COMMENT_OR_UNCLOSED_REGEX = Regex("/\\*[\\s\\S]*?(?:\\*/|$)")
    private val STYLE_TAG_TEXT_REGEX = Regex("</?style", RegexOption.IGNORE_CASE)

    fun jsonNumberPrimitive(value: Double): JsonPrimitive {
        val normalized = if (value == 0.0) 0.0 else value
        val asLong = normalized.toLong()
        return if (normalized == asLong.toDouble() && abs(normalized) <= MAX_JS_SAFE_INTEGER.toDouble()) {
            JsonPrimitive(asLong)
        } else {
            JsonPrimitive(normalized)
        }
    }

    private fun formatNumberText(value: Double): String {
        val normalized = if (value == 0.0) 0.0 else value
        val asLong = normalized.toLong()
        if (normalized == asLong.toDouble() && abs(normalized) <= MAX_JS_SAFE_INTEGER.toDouble()) {
            return asLong.toString()
        }
        val raw = normalized.toString()
        return if (raw.endsWith(".0")) raw.dropLast(2) else raw
    }

    private fun round3(n: Double): Double = round(n * 1000.0) / 1000.0

    private data class ParsedHttpUrl(
        val scheme: String,
        val origin: String,
        val pathname: String,
        val queryAndHash: String,
        val hasCredentials: Boolean,
    )

    private fun hasControlChar(text: String): Boolean =
        text.any { it.category == CharCategory.CONTROL || it.category == CharCategory.FORMAT }

    private fun parseHttpUrl(raw: String): ParsedHttpUrl? {
        if (raw.isEmpty() || raw.any { it.code <= 0x20 || it.code == 0x7F } || '\\' in raw) return null
        val colonIdx = raw.indexOf(':')
        if (colonIdx <= 0) return null
        val scheme = raw.substring(0, colonIdx).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = raw.substring(colonIdx + 1)
        if (!rest.startsWith("//")) return null
        val afterSlashes = rest.substring(2)
        val authorityEnd = afterSlashes.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it < 0) afterSlashes.length else it }
        val authority = afterSlashes.substring(0, authorityEnd)
        if (authority.isEmpty()) return null
        val tail = afterSlashes.substring(authorityEnd)
        val qhIdx = tail.indexOfFirst { it == '?' || it == '#' }
        val rawPath = if (qhIdx < 0) tail else tail.substring(0, qhIdx)
        val queryAndHash = if (qhIdx < 0) "" else tail.substring(qhIdx)
        val normalizedPath = normalizeUrlPathname(if (rawPath.isEmpty()) "/" else rawPath)

        val atIdx = authority.lastIndexOf('@')
        val userInfo = if (atIdx >= 0) authority.substring(0, atIdx) else ""
        val hasCredentials = atIdx >= 0 && userInfo.isNotEmpty() && userInfo != ":"
        val hostPort = if (atIdx >= 0) authority.substring(atIdx + 1) else authority
        if (hostPort.isEmpty()) return null

        val hostLower: String
        val portNum: Int?
        if (hostPort.startsWith("[")) {
            val closeBracket = hostPort.indexOf(']')
            if (closeBracket <= 1) return null
            val portSuffix = hostPort.substring(closeBracket + 1)
            if (portSuffix.isNotEmpty() && !validPortSuffix(portSuffix)) return null
            hostLower = hostPort.substring(0, closeBracket + 1).lowercase()
            portNum = portSuffix.removePrefix(":").takeIf { it.isNotEmpty() }?.toIntOrNull()
        } else {
            val portColon = hostPort.indexOf(':')
            if (portColon >= 0) {
                val portSuffix = hostPort.substring(portColon)
                if (!validPortSuffix(portSuffix)) return null
                hostLower = hostPort.substring(0, portColon).lowercase()
                portNum = portSuffix.removePrefix(":").takeIf { it.isNotEmpty() }?.toIntOrNull()
            } else {
                hostLower = hostPort.lowercase()
                portNum = null
            }
        }
        if (hostLower.isEmpty()) return null
        val defaultPort = if (scheme == "https") 443 else 80
        val originPort = if (portNum != null && portNum != defaultPort) ":$portNum" else ""
        return ParsedHttpUrl(
            scheme = scheme,
            origin = "$scheme://$hostLower$originPort",
            pathname = normalizedPath,
            queryAndHash = queryAndHash,
            hasCredentials = hasCredentials,
        )
    }

    private fun normalizeUrlPathname(rawPath: String): String {
        val trailingSlash = rawPath.endsWith("/") || rawPath.endsWith("/.") || rawPath.endsWith("/..") || rawPath == "." || rawPath == ".."
        val out = ArrayList<String>()
        for (seg in rawPath.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                else -> out.add(seg)
            }
        }
        return if (out.isEmpty()) {
            "/"
        } else {
            "/" + out.joinToString("/") + (if (trailingSlash) "/" else "")
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun inlineImage(value: String): String? {
        if (value.length > ProtocolLimits.MAX_DATA_IMAGE_CHARS) return null
        val match = INLINE_IMAGE_DATA_REGEX.matchEntire(value) ?: return null
        val format = match.groupValues[1]
        val base64 = match.groupValues[2]
        if (base64.length % 4 != 0) return null
        val bytes = try {
            Base64.decode(base64)
        } catch (_: Exception) {
            return null
        }
        val size = readStaticImageDimensions(format, bytes) ?: return null
        val (w, h) = size
        if (w < 1 || h < 1 || w > MAX_INLINE_IMAGE_SIDE || h > MAX_INLINE_IMAGE_SIDE) return null
        if (w.toLong() * h.toLong() > MAX_INLINE_IMAGE_PIXELS.toLong()) return null
        return value
    }

    private fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xFF

    private fun be32(b: ByteArray, at: Int): Long =
        ((u8(b, at).toLong() shl 24) or (u8(b, at + 1).toLong() shl 16) or (u8(b, at + 2).toLong() shl 8) or u8(b, at + 3).toLong()) and 0xFFFFFFFFL

    private fun asciiAt(b: ByteArray, at: Int, text: String): Boolean {
        if (at < 0 || at + text.length > b.size) return false
        for (i in text.indices) {
            if (u8(b, at + i) != text[i].code) return false
        }
        return true
    }

    private fun findAscii(b: ByteArray, text: String, from: Int): Int {
        var i = from
        while (i + text.length <= b.size) {
            if (asciiAt(b, i, text)) return i
            i += 1
        }
        return -1
    }

    private fun readStaticImageDimensions(format: String, b: ByteArray): Pair<Int, Int>? {
        if (format == "png") {
            if (b.size < 33 || be32(b, 0) != 0x89504e47L || be32(b, 4) != 0x0d0a1a0aL || !asciiAt(b, 12, "IHDR")) return null
            if (findAscii(b, "acTL", 8) >= 0) return null
            val w = be32(b, 16)
            val h = be32(b, 20)
            if (w > Int.MAX_VALUE || h > Int.MAX_VALUE) return null
            return w.toInt() to h.toInt()
        }
        if (format == "jpeg") {
            if (b.size < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8 || u8(b, 2) != 0xFF) return null
            var at = 2
            while (at + 9 < b.size) {
                if (u8(b, at) != 0xFF) return null
                val marker = u8(b, at + 1)
                if (marker == 0xFF) {
                    at += 1
                    continue
                }
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    val h = (u8(b, at + 5) shl 8) or u8(b, at + 6)
                    val w = (u8(b, at + 7) shl 8) or u8(b, at + 8)
                    return w to h
                }
                if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) {
                    at += 2
                    continue
                }
                at += 2 + ((u8(b, at + 2) shl 8) or u8(b, at + 3))
            }
            return null
        }
        // WebP: RIFF <size> WEBP, then VP8X, "VP8 ", or VP8L
        if (b.size < 30 || !asciiAt(b, 0, "RIFF") || !asciiAt(b, 8, "WEBP")) return null
        if (findAscii(b, "ANIM", 12) >= 0 || findAscii(b, "ANMF", 12) >= 0) return null
        if (asciiAt(b, 12, "VP8X")) {
            if ((u8(b, 20) and 0x02) != 0) return null
            val w = 1 + (u8(b, 24) or (u8(b, 25) shl 8) or (u8(b, 26) shl 16))
            val h = 1 + (u8(b, 27) or (u8(b, 28) shl 8) or (u8(b, 29) shl 16))
            return w to h
        }
        if (asciiAt(b, 12, "VP8 ")) {
            if (u8(b, 23) != 0x9D || u8(b, 24) != 0x01 || u8(b, 25) != 0x2A) return null
            val w = (u8(b, 26) or (u8(b, 27) shl 8)) and 0x3FFF
            val h = (u8(b, 28) or (u8(b, 29) shl 8)) and 0x3FFF
            return w to h
        }
        if (asciiAt(b, 12, "VP8L")) {
            if (u8(b, 20) != 0x2F) return null
            val bits = (u8(b, 21).toLong() or (u8(b, 22).toLong() shl 8) or (u8(b, 23).toLong() shl 16) or (u8(b, 24).toLong() shl 24)) and 0xFFFFFFFFL
            val w = 1 + (bits and 0x3FFFL).toInt()
            val h = 1 + ((bits ushr 14) and 0x3FFFL).toInt()
            return w to h
        }
        return null
    }

    fun isSafeInlineDataImage(text: String): Boolean = inlineImage(text.trim()) != null

    fun isSafeHttpUrl(text: String, maxChars: Int = ProtocolLimits.MAX_URL_CHARS): Boolean {
        if (text.isEmpty() || text.unicodeCodePointCount() > maxChars) return false
        if (hasScriptUrl(text)) return false
        return parseHttpUrl(text.trim()) != null
    }

    private fun openLinkUrl(value: String): String? {
        val text = value.trim()
        if (text.length > ProtocolLimits.MAX_ATTRIBUTE_TEXT || hasScriptUrl(text)) return null
        val compact = flatControlAndSpace(text)
        if (compact.startsWith("data:image/")) return text
        if (!compact.startsWith("https://") && !compact.startsWith("http://")) return null
        return if (parseHttpUrl(text) != null) text else null
    }

    private fun allowedRemoteUrl(parsed: ParsedHttpUrl, policy: ResourcePolicy?, ignoreOriginsAllowlist: Boolean = false): Boolean {
        if (parsed.hasCredentials) return false
        if (parsed.scheme == "https") {
            return ignoreOriginsAllowlist || policy?.origins == null || parsed.origin in policy.origins
        }
        return parsed.scheme == "http" && policy?.httpOrigins?.contains(parsed.origin) == true
    }

    fun guestResourceUrl(text: String, policy: ResourcePolicy?): String? {
        if (hasControlChar(text) || '\\' in text) return null
        if (HTTP_SCHEME_PREFIX_REGEX.containsMatchIn(text)) {
            val parsed = parseHttpUrl(text) ?: return null
            return if (!parsed.hasCredentials) text else null
        }
        val baseText = policy?.resourceBase ?: return null
        if (ANY_SCHEME_REGEX.containsMatchIn(text) || text.startsWith("/") || text.any { it.isWhitespace() }) return null
        if (!HTTP_SCHEME_PREFIX_REGEX.containsMatchIn(baseText)) return null
        val base = parseHttpUrl(baseText) ?: return null
        if (!allowedRemoteUrl(base, policy, ignoreOriginsAllowlist = true)) return null
        val directory = base.pathname.substring(0, base.pathname.lastIndexOf('/') + 1)
        val qhIdx = text.indexOfFirst { it == '?' || it == '#' }
        val relPath = if (qhIdx < 0) text else text.substring(0, qhIdx)
        val relSuffix = if (qhIdx < 0) "" else text.substring(qhIdx)
        val resolvedPath = normalizeUrlPathname(directory + relPath)
        if (!resolvedPath.startsWith(directory)) return null
        return "${base.origin}$resolvedPath$relSuffix"
    }

    fun checkResolvedResource(answer: String?, kind: ResourceKind, policy: ResourcePolicy?, maxChars: Int = ProtocolLimits.MAX_ATTRIBUTE_TEXT): String? {
        if (answer.isNullOrEmpty() || answer.length > maxChars) return null
        if (answer.any { it.isWhitespace() } || hasControlChar(answer)) return null
        val lower = answer.lowercase()
        if (lower.startsWith("data:")) {
            return if (kind == ResourceKind.IMAGE) inlineImage(answer) else null
        }
        if (lower.startsWith("blob:")) {
            return try {
                if (policy?.ownsBlob?.invoke(answer) == true) answer else null
            } catch (_: Exception) {
                null
            }
        }
        if (!HTTP_SCHEME_PREFIX_REGEX.containsMatchIn(answer)) return null
        val parsed = parseHttpUrl(answer) ?: return null
        return if (allowedRemoteUrl(parsed, policy)) answer else null
    }

    private fun resolveExternalResourceUrl(
        rawUrl: String,
        kind: ResourceKind,
        resolver: ResourceResolver?,
        policy: ResourcePolicy? = null,
        maxChars: Int = ProtocolLimits.MAX_ATTRIBUTE_TEXT,
    ): String? {
        val text = rawUrl.trim()
        if (text.isEmpty() || text.length > maxChars || hasScriptUrl(text)) return null
        if (flatControlAndSpace(text).startsWith("data:")) {
            return if (kind == ResourceKind.IMAGE) inlineImage(text) else null
        }
        val asked = guestResourceUrl(text, policy) ?: return null
        if (resolver == null) return null
        val answer = try {
            resolver.invoke(asked, kind)
        } catch (_: Exception) {
            return null
        }
        return checkResolvedResource(answer, kind, policy, maxChars)
    }

    private fun validPortSuffix(portSuffix: String): Boolean {
        if (!portSuffix.startsWith(":")) return false
        val digits = portSuffix.substring(1)
        if (digits.isEmpty()) return true
        val port = digits.toIntOrNull() ?: return false
        return port in 0..65535
    }

    private fun flatControlAndSpace(text: String): String = buildString(text.length) {
        for (ch in text) {
            if (!ch.isWhitespace() && ch.category != CharCategory.CONTROL && ch.category != CharCategory.FORMAT) {
                append(ch.lowercaseChar())
            }
        }
    }

    fun hasScriptUrl(text: String): Boolean {
        val compact = flatControlAndSpace(text)
        return compact.contains("javascript:") || compact.contains("vbscript:") || compact.contains("data:text/html")
    }

    fun checkPaint(el: JsonElement, idPrefix: String): JsonPrimitive? {
        val prim = el as? JsonPrimitive ?: return null
        if (!prim.isString) return null
        val raw = prim.content
        if (raw.unicodeCodePointCount() > ProtocolLimits.MAX_PAINT_CHARS) return null
        val text = raw.trim()
        val lower = text.lowercase()
        if (lower == "currentcolor") return JsonPrimitive("currentColor")
        if (lower == "none") return JsonPrimitive("none")
        if (HEX_COLOR_REGEX.matches(text) || FUNCTION_COLOR_REGEX.matches(text)) return JsonPrimitive(text)
        if (lower in NAMED_COLORS) return JsonPrimitive(lower)
        val ref = ID_REF_REGEX.matchEntire(text) ?: return null
        return JsonPrimitive("url(#$idPrefix${ref.groupValues[1]})")
    }

    data class ParsedTransform(val css: String, val svg: String)

    fun parseTransform(el: JsonElement): ParsedTransform? {
        val prim = el as? JsonPrimitive ?: return null
        if (!prim.isString) return null
        val raw = prim.content
        if (raw.unicodeCodePointCount() > ProtocolLimits.MAX_TRANSFORM_CHARS) return null
        val text = raw.trim()
        if (text.isEmpty()) return null
        val leftover = text.replace(TRANSFORM_FUNC_REGEX, "").replace(Regex("[\\s,]"), "")
        if (leftover.isNotEmpty()) return null

        val css = ArrayList<String>()
        val svg = ArrayList<String>()

        fun parseArgs(body: String, allowedUnit: String): List<Double>? {
            val parts = body.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            val out = ArrayList<Double>(parts.size)
            for (part in parts) {
                val m = TRANSFORM_ARG_REGEX.matchEntire(part) ?: return null
                val suffix = m.groupValues[1].lowercase()
                if (suffix.isNotEmpty() && suffix != allowedUnit) return null
                val numPart = if (suffix.isNotEmpty()) part.dropLast(suffix.length) else part
                val v = numPart.toDoubleOrNull() ?: return null
                if (!v.isFinite() || abs(v) > ProtocolLimits.MAX_COORDINATE) return null
                out.add(round3(v))
            }
            return out
        }

        for (match in TRANSFORM_FUNC_REGEX.findAll(text)) {
            if (css.size >= ProtocolLimits.MAX_TRANSFORM_FUNCTIONS) return null
            val name = match.groupValues[1]
            val body = match.groupValues[2]
            when (name) {
                "translate", "translateX", "translateY" -> {
                    val a = parseArgs(body, "px") ?: return null
                    val maxArgs = if (name == "translate") 2 else 1
                    if (a.isEmpty() || a.size > maxArgs) return null
                    val x = if (name == "translateY") 0.0 else a[0]
                    val y = when (name) {
                        "translateY" -> a[0]
                        "translate" -> a.getOrElse(1) { 0.0 }
                        else -> 0.0
                    }
                    val xs = formatNumberText(x)
                    val ys = formatNumberText(y)
                    css.add("translate(${xs}px, ${ys}px)")
                    svg.add("translate($xs $ys)")
                }

                "rotate" -> {
                    val a = parseArgs(body, "deg") ?: return null
                    if (a.size != 1 && a.size != 3) return null
                    val deg = formatNumberText(a[0])
                    if (a.size == 1) {
                        css.add("rotate(${deg}deg)")
                        svg.add("rotate($deg)")
                    } else {
                        val cx = formatNumberText(a[1])
                        val cy = formatNumberText(a[2])
                        val ncx = formatNumberText(-a[1])
                        val ncy = formatNumberText(-a[2])
                        css.add("translate(${cx}px, ${cy}px) rotate(${deg}deg) translate(${ncx}px, ${ncy}px)")
                        svg.add("rotate($deg $cx $cy)")
                    }
                }

                "scale" -> {
                    val a = parseArgs(body, "") ?: return null
                    if (a.isEmpty() || a.size > 2) return null
                    val x = formatNumberText(a[0])
                    val y = formatNumberText(a.getOrElse(1) { a[0] })
                    css.add("scale($x, $y)")
                    svg.add("scale($x $y)")
                }

                else -> return null
            }
        }
        return if (css.isNotEmpty()) ParsedTransform(css.joinToString(" "), svg.joinToString(" ")) else null
    }

    fun checkProp(
        schema: PropSchema,
        el: JsonElement,
        idPrefix: String,
        resolveResource: ResourceResolver? = null,
        resourcePolicy: ResourcePolicy? = null,
    ): JsonPrimitive? {
        if (el is JsonNull) return null
        return when (schema) {
            is PropSchema.EnumVal -> {
                val prim = el as? JsonPrimitive ?: return null
                if (prim.isString && prim.content in schema.values) prim else null
            }

            is PropSchema.Str -> {
                val prim = el as? JsonPrimitive ?: return null
                val text = when {
                    prim.isString -> prim.content
                    prim.doubleOrNull != null && prim.booleanOrNull == null -> {
                        val d = prim.doubleOrNull!!
                        if (d.isFinite()) formatNumberText(d) else return null
                    }
                    else -> return null
                }
                // Step 5 `catalog.ts`: longer text is dropped whole, never cut!
                if (text.unicodeCodePointCount() <= schema.max) JsonPrimitive(text) else null
            }

            is PropSchema.Num -> {
                val prim = el as? JsonPrimitive ?: return null
                if (prim.isString || prim.booleanOrNull != null) return null
                val d = prim.doubleOrNull ?: return null
                // Step 5 `catalog.ts`: number outside [min, max] is dropped, never clamped!
                if (!d.isFinite() || d < schema.min || d > schema.max) return null
                jsonNumberPrimitive(d)
            }

            PropSchema.Bool -> {
                val prim = el as? JsonPrimitive ?: return null
                if (prim.isString) return null
                val b = prim.booleanOrNull ?: return null
                JsonPrimitive(b)
            }

            PropSchema.Url -> {
                val prim = el as? JsonPrimitive ?: return null
                if (prim.isString && isSafeHttpUrl(prim.content)) prim else null
            }

            is PropSchema.Resource -> {
                val prim = el as? JsonPrimitive ?: return null
                if (!prim.isString) return null
                val resolved = resolveExternalResourceUrl(
                    rawUrl = prim.content,
                    kind = schema.resource,
                    resolver = resolveResource,
                    policy = resourcePolicy,
                    maxChars = ProtocolLimits.MAX_ATTRIBUTE_TEXT,
                ) ?: return null
                JsonPrimitive(resolved)
            }

            PropSchema.Length -> {
                val prim = el as? JsonPrimitive ?: return null
                if (prim.isString) {
                    val raw = prim.content
                    val trimmed = raw.trim()
                    if (raw.unicodeCodePointCount() <= ProtocolLimits.MAX_LENGTH_CHARS && LENGTH_REGEX.matches(trimmed)) {
                        JsonPrimitive(trimmed)
                    } else {
                        null
                    }
                } else if (prim.booleanOrNull == null) {
                    val d = prim.doubleOrNull ?: return null
                    if (d.isFinite() && abs(d) <= ProtocolLimits.MAX_COORDINATE) jsonNumberPrimitive(d) else null
                } else {
                    null
                }
            }

            PropSchema.Paint -> checkPaint(el, idPrefix)

            is PropSchema.Pattern -> {
                val prim = el as? JsonPrimitive ?: return null
                val text = when {
                    prim.isString -> prim.content
                    prim.booleanOrNull == null && prim.doubleOrNull != null && prim.doubleOrNull!!.isFinite() ->
                        formatNumberText(prim.doubleOrNull!!)
                    else -> return null
                }
                if (text.unicodeCodePointCount() <= schema.max && Regex(schema.chars).containsMatchIn(text)) {
                    JsonPrimitive(text)
                } else {
                    null
                }
            }

            is PropSchema.Transform -> {
                val parsed = parseTransform(el) ?: return null
                JsonPrimitive(if (schema.target == TransformTarget.SVG) parsed.svg else parsed.css)
            }

            PropSchema.Id -> {
                val prim = el as? JsonPrimitive ?: return null
                if (prim.isString && STRICT_ID_REGEX.matches(prim.content)) {
                    JsonPrimitive(idPrefix + prim.content)
                } else {
                    null
                }
            }

            PropSchema.IdRef -> {
                val prim = el as? JsonPrimitive ?: return null
                if (!prim.isString) return null
                val m = ID_REF_REGEX.matchEntire(prim.content.trim()) ?: return null
                JsonPrimitive("url(#$idPrefix${m.groupValues[1]})")
            }

            PropSchema.Color -> PortableV6Spec.normalizeColor(el, idPrefix, allowSvgNoneAndGradient = false)?.let { JsonPrimitive(it) }
            PropSchema.SvgPaint -> PortableV6Spec.normalizeColor(el, idPrefix, allowSvgNoneAndGradient = true)?.let { JsonPrimitive(it) }
            PropSchema.ViewBox -> PortableV6Spec.parseViewBox(el)
            is PropSchema.PathData -> {
                val s = (el as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (s != null && PortableV6Spec.isValidSvgPathData(s, schema.max)) JsonPrimitive(s) else null
            }
            PropSchema.FontWeight -> PortableV6Spec.parseFontWeight(el)
            PropSchema.Fraction -> PortableV6Spec.parseFraction(el)?.let { jsonNumberPrimitive(it) }
            PropSchema.Coordinate -> PortableV6Spec.parseCoordinate(el, "userSpaceOnUse")
            PropSchema.SvgTransform -> PortableV6Spec.parseSvgTransform(el)?.let { JsonPrimitive(it.canonical) }
            is PropSchema.Style -> null // Handled by checkPropEntry below
        }
    }

    data class DroppedPropIssue(
        val propName: String,
        val diagnosticCode: String, // "PROP_DROPPED" or "RESOURCE_BLOCKED"
    )

    data class CheckedPropOutcome(
        val value: JsonElement?,
        val issues: List<DroppedPropIssue>,
    )

    fun checkPropEntry(
        propName: String,
        schema: PropSchema,
        el: JsonElement,
        idPrefix: String,
        resolveResource: ResourceResolver? = null,
        resourcePolicy: ResourcePolicy? = null,
    ): CheckedPropOutcome {
        if (schema is PropSchema.Style) {
            val styleObj = el as? JsonObject
                ?: return CheckedPropOutcome(null, listOf(DroppedPropIssue(propName, "PROP_DROPPED")))
            val kept = LinkedHashMap<String, JsonElement>()
            val issues = ArrayList<DroppedPropIssue>()
            for ((styleKey, styleVal) in styleObj) {
                val subSchema = if (styleKey in RESERVED_PROTOTYPE_KEYS) null else schema.properties[styleKey]
                val checkedSub = if (subSchema != null && subSchema !is PropSchema.Style) {
                    checkProp(subSchema, styleVal, idPrefix, resolveResource, resourcePolicy)
                } else {
                    null
                }
                if (checkedSub == null) {
                    val code = if (subSchema is PropSchema.Resource && (styleVal as? JsonPrimitive)?.isString == true) {
                        "RESOURCE_BLOCKED"
                    } else {
                        "PROP_DROPPED"
                    }
                    issues.add(DroppedPropIssue("$propName.$styleKey", code))
                } else {
                    kept[styleKey] = checkedSub
                }
            }
            return CheckedPropOutcome(
                value = JsonObject(kept),
                issues = issues,
            )
        }
        val checked = checkProp(schema, el, idPrefix, resolveResource, resourcePolicy)
        return if (checked == null) {
            val code = if (schema is PropSchema.Resource && (el as? JsonPrimitive)?.isString == true) {
                "RESOURCE_BLOCKED"
            } else {
                "PROP_DROPPED"
            }
            CheckedPropOutcome(null, listOf(DroppedPropIssue(propName, code)))
        } else {
            CheckedPropOutcome(checked, emptyList())
        }
    }

    // ---- Open-mode HTML / SVG / CSS Scoper (`web-open/1`) ----

    class OpenValidationContext(
        val idPrefix: String,
        val resolveResource: ResourceResolver? = null,
        val resourcePolicy: ResourcePolicy? = null,
        val publicClasses: Map<String, String>? = null,
        val scopeClassNames: Boolean = true,
    ) {
        val animatedStyles = ArrayList<MutableMap<String, JsonPrimitive>>()
    }

    fun scopedClass(name: String, idPrefix: String, publicClasses: Map<String, String>? = null): String {
        val published = publicClasses?.get(name)
        if (published != null) {
            val parts = published.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isNotEmpty() && parts.all { CLASS_NAME_REGEX.matches(it) }) {
                return published.trim()
            }
        }
        return "${idPrefix}c-$name"
    }

    private fun scopedClassList(value: String, ctx: OpenValidationContext): String? {
        val names = value.split(Regex("\\s+")).filter { CLASS_NAME_REGEX.matches(it) }.take(MAX_CLASSES)
        return if (names.isEmpty()) null else names.joinToString(" ") { scopedClass(it, ctx.idPrefix, ctx.publicClasses) }
    }

    fun isOpenElementAllowed(tag: String, policy: OpenPolicy): Boolean =
        OPEN_TAG_REGEX.matches(tag) &&
            tag.unicodeCodePointCount() <= ProtocolLimits.MAX_TAG_CHARS &&
            tag.lowercase() !in policy.neverElements

    fun isValidOpenEventName(name: String, policy: OpenPolicy, step2RegexCompat: Boolean): Boolean {
        if (name in RESERVED_PROTOTYPE_KEYS) return false
        return if (step2RegexCompat || policy.allowedEvents == null) {
            STEP2_OPEN_EVENT_REGEX.matches(name)
        } else {
            name in policy.allowedEvents
        }
    }

    private data class UrlRewriteOutcome(
        val rewritten: String?,
        val blockedResource: Boolean = false,
    )

    private fun rewriteUrlReferences(value: String, idPrefix: String): UrlRewriteOutcome {
        val expected = URL_CALL_COUNT_REGEX.findAll(value).count()
        if (expected == 0) return UrlRewriteOutcome(value)
        var rewritten = 0
        var blockedResource = false
        var malformed = false
        val out = URL_CALL_REWRITE_REGEX.replace(value) { match ->
            rewritten += 1
            val target = match.groupValues[2].trim()
            if (target.startsWith("#") && OPEN_ID_REGEX.matches(target.substring(1))) {
                "url(#$idPrefix${target.substring(1)})"
            } else if (isSafeInlineDataImage(target)) {
                "url(\"$target\")"
            } else {
                blockedResource = true
                "none"
            }
        }
        if (rewritten != expected) malformed = true
        return if (blockedResource || malformed) {
            UrlRewriteOutcome(rewritten = null, blockedResource = blockedResource)
        } else {
            UrlRewriteOutcome(rewritten = out, blockedResource = false)
        }
    }

    private fun smilTargetsFloor(rawProps: JsonObject): Boolean {
        val attrEl = rawProps["attributeName"] as? JsonPrimitive ?: return false
        if (!attrEl.isString) return false
        val name = flatControlAndSpace(attrEl.content)
        return name == "href" || name == "xlink:href" || name == "src" ||
            name == "action" || name == "formaction" || name == "srcdoc" ||
            name.startsWith("on") || name.startsWith("data-sprout-")
    }

    private fun openStyle(
        el: JsonElement,
        ctx: OpenValidationContext,
        issues: MutableList<DroppedPropIssue>,
    ): MutableMap<String, JsonPrimitive>? {
        val obj = el as? JsonObject ?: return null
        val out = LinkedHashMap<String, JsonPrimitive>()
        var count = 0
        for ((property, raw) in obj) {
            if (property in RESERVED_PROTOTYPE_KEYS) continue
            if (++count > ProtocolLimits.MAX_STYLE_PROPERTIES || !STYLE_PROP_REGEX.matches(property)) continue
            val prim = raw as? JsonPrimitive ?: continue
            if (!prim.isString && prim.booleanOrNull == null && prim.doubleOrNull != null) {
                val d = prim.doubleOrNull!!
                if (d.isFinite()) out[property] = jsonNumberPrimitive(d)
                continue
            }
            if (!prim.isString) continue
            val rawStr = prim.content
            if (rawStr.length > ProtocolLimits.MAX_STYLE_VALUE_CHARS || hasScriptUrl(rawStr)) continue
            val urlCheck = rewriteUrlReferences(rawStr, ctx.idPrefix)
            if (urlCheck.rewritten == null || hasUnsafeCssValue(urlCheck.rewritten.replace(LOCAL_URL_HASH_REGEX, ""))) {
                if (cssLoadsResources(rawStr)) {
                    issues.add(DroppedPropIssue("style.$property", "RESOURCE_BLOCKED"))
                }
                continue
            }
            out[property] = JsonPrimitive(urlCheck.rewritten)
        }
        if ("animation" in out || "animationName" in out) {
            ctx.animatedStyles.add(out)
        }
        return out
    }

    enum class OpenElementDisposition {
        KEEP,
        BLOCKED_IMAGE_NOTICE,
        BLOCKED_SVG_IMAGE_OMIT,
    }

    data class OpenPropsResult(
        val props: MutableMap<String, JsonElement>,
        val styleRef: MutableMap<String, JsonPrimitive>?,
        val issues: List<DroppedPropIssue>,
        val disposition: OpenElementDisposition = OpenElementDisposition.KEEP,
        val fallbackAltText: String = "Image not shown",
    )

    private fun resolveOpenSrcSet(
        rawSrcSet: String,
        kind: ResourceKind,
        resolver: ResourceResolver?,
        policy: ResourcePolicy?,
    ): String? {
        val candidates = rawSrcSet.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (candidates.isEmpty()) return null
        val rewrittenCandidates = ArrayList<String>(candidates.size)
        for (c in candidates) {
            val tokens = c.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            if (tokens[0].startsWith("data:", ignoreCase = true)) return null
            val resolvedUrl = resolveExternalResourceUrl(tokens[0], kind, resolver, policy) ?: return null
            if (resolvedUrl.any { it.isWhitespace() || it == ',' }) return null
            val suffix = if (tokens.size > 1) " " + tokens.drop(1).joinToString(" ") else ""
            rewrittenCandidates.add(resolvedUrl + suffix)
        }
        return rewrittenCandidates.joinToString(", ")
    }

    private fun resourceKindOfTag(tag: String): ResourceKind = when (tag) {
        "img", "image", "input", "feImage" -> ResourceKind.IMAGE
        "video", "audio", "source", "track" -> ResourceKind.MEDIA
        else -> ResourceKind.OTHER
    }

    private fun isNavigationLinkAttr(tag: String, attrName: String): Boolean =
        attrName == "cite" || ((attrName == "href" || attrName == "xlinkHref" || attrName == "xlink:href") && (tag == "a" || tag == "area"))

    fun openProps(
        tag: String,
        rawProps: JsonObject,
        policy: OpenPolicy,
        ctx: OpenValidationContext,
    ): OpenPropsResult? {
        if (tag in policy.smilElements && smilTargetsFloor(rawProps)) return null
        val props = LinkedHashMap<String, JsonElement>()
        var styleMap: MutableMap<String, JsonPrimitive>? = null
        val issues = ArrayList<DroppedPropIssue>()

        val rawAlt = (rawProps["alt"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        val fallbackAlt = if (rawAlt.isNotEmpty()) rawAlt.takeUnicodeCodePoints(ProtocolLimits.MAX_TITLE_CHARS) else "Image not shown"

        for ((name, value) in rawProps) {
            if (name in RESERVED_PROTOTYPE_KEYS ||
                !OPEN_ATTR_REGEX.matches(name) ||
                name in policy.neverAttributes ||
                name.startsWith("on", ignoreCase = true) ||
                name.startsWith("data-sprout-", ignoreCase = true)
            ) {
                issues.add(DroppedPropIssue(name, "PROP_DROPPED"))
                continue
            }
            if (name == "style") {
                val st = openStyle(value, ctx, issues)
                if (st == null) {
                    issues.add(DroppedPropIssue(name, "PROP_DROPPED"))
                } else {
                    styleMap = st
                    props["style"] = JsonObject(st)
                }
                continue
            }
            val prim = value as? JsonPrimitive
            if (prim == null || value is JsonNull) {
                issues.add(DroppedPropIssue(name, "PROP_DROPPED"))
                continue
            }
            if (!prim.isString) {
                val b = prim.booleanOrNull
                if (b != null) {
                    props[name] = JsonPrimitive(b)
                    continue
                }
                val d = prim.doubleOrNull
                if (d != null && d.isFinite()) {
                    props[name] = jsonNumberPrimitive(d)
                } else {
                    issues.add(DroppedPropIssue(name, "PROP_DROPPED"))
                }
                continue
            }
            val strVal = prim.content
            if (name in policy.urlAttributes) {
                val trimmed = strVal.trim()
                if (trimmed.startsWith("#")) {
                    val targetId = trimmed.substring(1)
                    if (tag in policy.fragmentRefElements && OPEN_ID_REGEX.matches(targetId)) {
                        props[name] = JsonPrimitive("#${ctx.idPrefix}$targetId")
                    } else {
                        issues.add(DroppedPropIssue(name, "PROP_DROPPED"))
                    }
                    continue
                }
                if (tag == "use") {
                    issues.add(DroppedPropIssue(name, "PROP_DROPPED"))
                    continue
                }
                if (isNavigationLinkAttr(tag, name)) {
                    val safeLink = openLinkUrl(trimmed)
                    if (safeLink != null) {
                        props[name] = JsonPrimitive(safeLink)
                    } else {
                        issues.add(DroppedPropIssue(name, "PROP_DROPPED"))
                    }
                    continue
                }
                if (trimmed.length > ProtocolLimits.MAX_ATTRIBUTE_TEXT) {
                    issues.add(DroppedPropIssue(name, "RESOURCE_BLOCKED"))
                    continue
                }
                val kind = resourceKindOfTag(tag)
                if (name == "srcSet" || name == "srcset") {
                    val resolvedSet = if (!hasScriptUrl(trimmed)) {
                        resolveOpenSrcSet(trimmed, kind, ctx.resolveResource, ctx.resourcePolicy)
                    } else {
                        null
                    }
                    if (resolvedSet != null) {
                        props[name] = JsonPrimitive(resolvedSet)
                    } else {
                        issues.add(DroppedPropIssue(name, "RESOURCE_BLOCKED"))
                    }
                    continue
                }
                val resolvedUrl = resolveExternalResourceUrl(trimmed, kind, ctx.resolveResource, ctx.resourcePolicy, ProtocolLimits.MAX_ATTRIBUTE_TEXT)
                if (resolvedUrl != null) {
                    props[name] = JsonPrimitive(resolvedUrl)
                } else {
                    issues.add(DroppedPropIssue(name, "RESOURCE_BLOCKED"))
                }
                continue
            }

            if (strVal.length > ProtocolLimits.MAX_ATTRIBUTE_TEXT || hasScriptUrl(strVal)) {
                issues.add(DroppedPropIssue(name, "PROP_DROPPED"))
                continue
            }

            val keptStr: String? = when {
                name == "id" -> if (OPEN_ID_REGEX.matches(strVal)) ctx.idPrefix + strVal else null
                name == "className" || name == "class" -> {
                    if (ctx.scopeClassNames) {
                        scopedClassList(strVal, ctx)
                    } else {
                        val tokens = strVal.split(Regex("\\s+")).filter { it.isNotEmpty() }
                        if (tokens.isEmpty()) "" else tokens.joinToString(" ") { ctx.idPrefix + it }
                    }
                }
                name in policy.idListAttributes -> {
                    val ids = strVal.split(Regex("\\s+")).filter { it.isNotEmpty() }
                    if (ids.isNotEmpty() && ids.all { OPEN_ID_REGEX.matches(it) }) {
                        ids.joinToString(" ") { ctx.idPrefix + it }
                    } else {
                        null
                    }
                }
                tag in policy.smilElements && (name == "begin" || name == "end") ->
                    SMIL_TIMING_REGEX.replace(strVal) { m ->
                        "${m.groupValues[1]}${ctx.idPrefix}${m.groupValues[2]}.${m.groupValues[3]}"
                    }
                else -> {
                    if (decodeCssEscapes(strVal) != strVal && cssLoadsResources(strVal)) {
                        issues.add(DroppedPropIssue(name, "RESOURCE_BLOCKED"))
                        continue
                    }
                    val rw = rewriteUrlReferences(strVal, ctx.idPrefix)
                    if (rw.rewritten == null) {
                        if (cssLoadsResources(strVal)) {
                            issues.add(DroppedPropIssue(name, "RESOURCE_BLOCKED"))
                            continue
                        }
                        null
                    } else if (name !in PROSE_ATTRIBUTES && cssLoadsResources(strVal)) {
                        issues.add(DroppedPropIssue(name, "RESOURCE_BLOCKED"))
                        continue
                    } else {
                        rw.rewritten
                    }
                }
            }
            if (keptStr == null) {
                issues.add(DroppedPropIssue(name, "PROP_DROPPED"))
            } else {
                props[name] = JsonPrimitive(keptStr)
            }
        }

        val hasBlockedNonStyleResource = issues.any { it.diagnosticCode == "RESOURCE_BLOCKED" && !it.propName.startsWith("style.") }
        if ((tag == "img" || tag == "image") && hasBlockedNonStyleResource) {
            val disposition = if (tag == "image") {
                OpenElementDisposition.BLOCKED_SVG_IMAGE_OMIT
            } else {
                OpenElementDisposition.BLOCKED_IMAGE_NOTICE
            }
            return OpenPropsResult(
                props = props,
                styleRef = null,
                issues = issues,
                disposition = disposition,
                fallbackAltText = fallbackAlt,
            )
        }

        if (tag == "a") {
            props["target"] = JsonPrimitive("_blank")
            props["rel"] = JsonPrimitive("noopener noreferrer")
        }
        return OpenPropsResult(props = props, styleRef = styleMap, issues = issues)
    }

    // ---- CSS Escape Decoding, Shape Analysis, and Scoping (`cssText.ts` & `scopeCss.ts`) ----

    data class ScopedCss(
        val css: String,
        val keyframes: List<String>,
        val blockedResource: Boolean,
    )

    data class CssShape(
        val escapeOutsideString: Boolean,
        val unterminated: Boolean,
        val unbalanced: Boolean,
    )

    data class CssResources(
        val urls: List<String>,
        val resourceFunction: Boolean,
        val atRule: Boolean,
        val script: Boolean,
    )

    private val CSS_RESOURCE_FUNC_REGEX = Regex("(?:image-set|image|cross-fade|element|paint|src|image-rect)\\s*\\(")

    private fun isCssSpace(ch: Char): Boolean = ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\u000C'
    private fun isCssNewline(ch: Char): Boolean = ch == '\n' || ch == '\r' || ch == '\u000C'
    private fun isAsciiHex(ch: Char): Boolean = ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

    fun decodeCssEscapes(text: String): String {
        if ('\\' !in text && '\u0000' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\u0000') {
                out.append('\uFFFD')
                i += 1
                continue
            }
            if (ch != '\\') {
                out.append(ch)
                i += 1
                continue
            }
            if (i + 1 >= text.length) {
                out.append('\uFFFD')
                break
            }
            val next = text[i + 1]
            if (isAsciiHex(next)) {
                var j = i + 1
                while (j < text.length && j - (i + 1) < 6 && isAsciiHex(text[j])) {
                    j += 1
                }
                val cp = text.substring(i + 1, j).toIntOrNull(16) ?: 0
                if (cp == 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) {
                    out.append('\uFFFD')
                } else if (cp <= 0xFFFF) {
                    out.append(cp.toChar())
                } else {
                    val u = cp - 0x10000
                    out.append((0xD800 + (u shr 10)).toChar())
                    out.append((0xDC00 + (u and 0x3FF)).toChar())
                }
                if (j < text.length) {
                    if (text[j] == '\r' && j + 1 < text.length && text[j + 1] == '\n') {
                        j += 2
                    } else if (isCssSpace(text[j])) {
                        j += 1
                    }
                }
                i = j
                continue
            }
            if (isCssNewline(next)) {
                i += if (next == '\r' && i + 2 < text.length && text[i + 2] == '\n') 3 else 2
                continue
            }
            if (next.isHighSurrogate() && i + 2 < text.length && text[i + 2].isLowSurrogate()) {
                out.append(next)
                out.append(text[i + 2])
                i += 3
            } else {
                out.append(if (next == '\u0000') '\uFFFD' else next)
                i += 2
            }
        }
        return out.toString()
    }

    fun cssShape(text: String): CssShape {
        var escapeOutsideString = false
        var unterminated = false
        var unbalanced = false
        val openStack = ArrayList<Char>()
        var quote = '\u0000'
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (quote != '\u0000') {
                if (ch == '\\') {
                    if (i + 2 < text.length && text[i + 1] == '\r' && text[i + 2] == '\n') i += 1
                    i += 2
                    continue
                }
                if (isCssNewline(ch)) {
                    unterminated = true
                    quote = '\u0000'
                } else if (ch == quote) {
                    quote = '\u0000'
                }
                i += 1
                continue
            }
            if (ch == '/' && i + 1 < text.length && text[i + 1] == '*') {
                val end = text.indexOf("*/", i + 2)
                if (end < 0) {
                    unterminated = true
                    break
                }
                i = end + 2
                continue
            }
            when (ch) {
                '"', '\'' -> quote = ch
                '\\' -> {
                    escapeOutsideString = true
                    i += 2
                    continue
                }
                '(' -> openStack.add(')')
                '[' -> openStack.add(']')
                '{' -> openStack.add('}')
                ')', ']', '}' -> {
                    if (openStack.isEmpty() || openStack.removeAt(openStack.lastIndex) != ch) {
                        unbalanced = true
                    }
                }
            }
            i += 1
        }
        if (quote != '\u0000') unterminated = true
        if (openStack.isNotEmpty()) unbalanced = true
        return CssShape(
            escapeOutsideString = escapeOutsideString,
            unterminated = unterminated,
            unbalanced = unbalanced,
        )
    }

    fun cssPlain(text: String): String =
        decodeCssEscapes(text).replace(CSS_COMMENT_OR_UNCLOSED_REGEX, "")

    fun cssResources(text: String): CssResources {
        val plain = cssPlain(text)
        val lower = plain.lowercase()
        val source = if (lower.length == plain.length) plain else lower
        val urls = ArrayList<String>()
        for (match in CSS_URL_FUNCTION_REGEX.findAll(lower)) {
            val targetGroup = match.groups[2]
            if (targetGroup != null) {
                val start = match.range.first + match.value.length - targetGroup.value.length
                urls.add(source.substring(start, start + targetGroup.value.length).trim())
            }
        }
        return CssResources(
            urls = urls,
            resourceFunction = CSS_RESOURCE_FUNC_REGEX.containsMatchIn(lower),
            atRule = AT_RULE_RESOURCE_REGEX.containsMatchIn(lower),
            script = CSS_EXPRESSION_REGEX.containsMatchIn(lower) || flatControlAndSpace(lower).contains("javascript:"),
        )
    }

    fun cssLoadsResources(text: String): Boolean {
        val found = cssResources(text)
        if (found.atRule || found.resourceFunction) return true
        return found.urls.any { target ->
            !target.startsWith("#") && !target.lowercase().startsWith("data:image/")
        }
    }

    fun hasUnsafeCssValue(value: String): Boolean {
        val shape = cssShape(value)
        if (shape.escapeOutsideString || shape.unterminated || shape.unbalanced) return true
        val found = cssResources(value)
        if (found.script || found.atRule || found.resourceFunction) return true
        return found.urls.any { target -> inlineImage(target) == null }
    }

    fun renameAnimations(value: String, names: Set<String>, prefix: String): String {
        if (names.isEmpty()) return value
        return IDENT_WORD_REGEX.replace(value) { m ->
            val word = m.value
            if (word in names) prefix + word else word
        }
    }

    fun renameInlineAnimations(
        animated: List<MutableMap<String, JsonPrimitive>>,
        styleHolders: List<MutableMap<String, JsonElement>>,
        names: Set<String>,
        prefix: String,
    ) {
        if (names.isEmpty()) return
        for (idx in animated.indices) {
            val styleMap = animated[idx]
            for (prop in listOf("animation", "animationName")) {
                val cur = styleMap[prop]
                if (cur != null && cur.isString) {
                    styleMap[prop] = JsonPrimitive(renameAnimations(cur.content, names, prefix))
                }
            }
            styleHolders.getOrNull(idx)?.put("style", JsonObject(styleMap))
        }
    }

    private data class CssRule(val prelude: String, val body: String)

    private fun topLevelRules(text: String): List<CssRule> {
        val rules = ArrayList<CssRule>()
        var i = 0
        while (i < text.length) {
            val open = text.indexOf('{', i)
            if (open < 0) break
            val semi = text.indexOf(';', i)
            if (semi in 0 until open) {
                i = semi + 1
                continue
            }
            var depth = 1
            var j = open + 1
            while (j < text.length && depth > 0) {
                when (text[j]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                j++
            }
            if (depth != 0) break
            rules.add(CssRule(text.substring(i, open).trim(), text.substring(open + 1, j - 1)))
            i = j
        }
        return rules
    }

    private fun splitOutside(text: String, separator: Char): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var quote = '\u0000'
        var start = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (quote != '\u0000') {
                if (ch == '\\') i++
                else if (ch == quote) quote = '\u0000'
            } else when {
                ch == '"' || ch == '\'' -> quote = ch
                ch == '(' || ch == '[' -> depth++
                ch == ')' || ch == ']' -> depth--
                ch == separator && depth == 0 -> {
                    out.add(text.substring(start, i))
                    start = i + 1
                }
            }
            i++
        }
        out.add(text.substring(start))
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun renameClassesInSelector(selector: String, classOf: (String) -> String): String {
        val out = StringBuilder(selector.length + 16)
        var quote = '\u0000'
        var brackets = 0
        var i = 0
        while (i < selector.length) {
            val ch = selector[i]
            if (quote != '\u0000') {
                if (ch == '\\') {
                    out.append(ch)
                    if (i + 1 < selector.length) out.append(selector[i + 1])
                    i += 2
                } else {
                    if (ch == quote) quote = '\u0000'
                    out.append(ch)
                    i += 1
                }
                continue
            }
            when {
                ch == '"' || ch == '\'' -> {
                    quote = ch
                    out.append(ch)
                    i += 1
                }
                ch == '[' -> {
                    brackets += 1
                    out.append(ch)
                    i += 1
                }
                ch == ']' -> {
                    brackets = maxOf(0, brackets - 1)
                    out.append(ch)
                    i += 1
                }
                ch == '.' && brackets == 0 -> {
                    val m = CLASS_NAME_REGEX.find(selector.substring(i + 1).let { rest ->
                        val end = rest.indexOfFirst { c -> !(c.isLetterOrDigit() || c == '_' || c == '-') }
                            .let { idx -> if (idx < 0) rest.length else idx }
                        rest.substring(0, end)
                    })
                    if (m != null && m.range.first == 0 && m.value.isNotEmpty()) {
                        val mapped = classOf(m.value).split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(".")
                        out.append('.').append(mapped)
                        i += 1 + m.value.length
                    } else {
                        out.append(ch)
                        i += 1
                    }
                }
                else -> {
                    out.append(ch)
                    i += 1
                }
            }
        }
        return out.toString()
    }

    private fun scopeSelector(selector: String, scope: String, classOf: ((String) -> String)?): String {
        val renamed = if (classOf != null) renameClassesInSelector(selector, classOf) else selector
        val trimmed = renamed.trim()
        val rooted = ROOT_SELECTOR_PREFIX_REGEX.replace(trimmed, "").trim()
        return if (rooted != trimmed) {
            if (rooted.isNotEmpty()) ".$scope $rooted" else ".$scope"
        } else {
            ".$scope $renamed"
        }
    }

    private fun selectorIsWhole(prelude: String): Boolean {
        val shape = cssShape(prelude)
        return !shape.unterminated && !shape.unbalanced
    }

    private fun scopeDeclarations(
        body: String,
        names: Set<String>,
        prefix: String,
    ): String =
        splitOutside(body, ';')
            .filter { decl ->
                ':' in decl && '{' !in decl && !hasUnsafeCssValue(decl)
            }
            .map { d ->
                val colon = d.indexOf(':')
                val property = d.substring(0, colon).trim()
                val value = d.substring(colon + 1).trim()
                if (ANIMATION_PROP_REGEX.matches(property)) {
                    "$property: ${renameAnimations(value, names, prefix)}"
                } else {
                    "$property: $value"
                }
            }
            .joinToString("; ")

    private fun collectKeyframes(rules: List<CssRule>, into: MutableList<String>) {
        for (rule in rules) {
            val kf = KEYFRAMES_REGEX.matchEntire(rule.prelude)
            if (kf != null) {
                into.add(kf.groupValues[1])
            } else if (NESTING_AT_RULES_REGEX.containsMatchIn(rule.prelude)) {
                collectKeyframes(topLevelRules(rule.body), into)
            }
        }
    }

    private fun scopeRules(
        rules: List<CssRule>,
        scope: String,
        names: Set<String>,
        prefix: String,
        classOf: ((String) -> String)?,
    ): String {
        val out = ArrayList<String>()
        for (rule in rules) {
            val kf = KEYFRAMES_REGEX.matchEntire(rule.prelude)
            when {
                kf != null -> {
                    val steps = topLevelRules(rule.body)
                        .filter { KEYFRAME_STEP_REGEX.matches(it.prelude) }
                        .map { "${it.prelude} { ${scopeDeclarations(it.body, names, prefix)} }" }
                    out.add("@keyframes $prefix${kf.groupValues[1]} { ${steps.joinToString(" ")} }")
                }

                rule.prelude.startsWith("@") -> {
                    if (NESTING_AT_RULES_REGEX.containsMatchIn(rule.prelude) && !hasUnsafeCssValue(rule.prelude)) {
                        out.add("${rule.prelude} { ${scopeRules(topLevelRules(rule.body), scope, names, prefix, classOf)} }")
                    }
                }

                rule.prelude.isNotEmpty() && rule.prelude.none { it == '{' || it == '}' || it == ';' || it == '@' } && selectorIsWhole(rule.prelude) -> {
                    val selectors = splitOutside(rule.prelude, ',').map { scopeSelector(it, scope, classOf) }
                    if (selectors.isNotEmpty()) {
                        out.add("${selectors.joinToString(", ")} { ${scopeDeclarations(rule.body, names, prefix)} }")
                    }
                }
            }
        }
        return out.joinToString("\n")
    }

    fun scopeCss(text: String, scope: String, prefix: String, classOf: ((String) -> String)? = null): ScopedCss {
        val blockedResource = cssLoadsResources(text)
        val source = text
            .take(ProtocolLimits.MAX_STYLE_TEXT)
            .replace(CSS_COMMENT_OR_UNCLOSED_REGEX, "")
            .replace(STYLE_TAG_TEXT_REGEX, "")
        val rules = topLevelRules(source)
        val keyframes = ArrayList<String>()
        collectKeyframes(rules, keyframes)
        val css = scopeRules(rules, scope, keyframes.toSet(), prefix, classOf)
        return ScopedCss(
            css = css,
            keyframes = keyframes,
            blockedResource = blockedResource,
        )
    }
}
