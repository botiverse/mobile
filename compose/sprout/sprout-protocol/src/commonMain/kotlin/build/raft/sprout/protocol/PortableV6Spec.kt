package build.raft.sprout.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Spec v6 §15 Item 9, Item 10 & Spec v8 §17 strict cross-platform contracts:
 * - Narrowed `portable-primitives/1` box/text layout, units (finite numbers 0..1,000,000, no %, em, px, auto, calc, or digit strings),
 *   cross-property style rules (`lineHeight >= fontSize`, `min <= max`, `width/height` within `[min, max]`, `border-box` `padding + 2*borderWidth <= width/maxWidth`,
 *   dropping the whole `style` prop with 1 `PROP_DROPPED` on combination contradiction, never clamping),
 *   strict `#rrggbbaa` lowercase / `currentColor` / `none` / local-gradient paint normalization,
 *   true SVG path command/arc-flag parser (`""` valid, compact arcs valid, leading/trailing/double commas rejected),
 *   column-vector 2D affine matrix composition, and sRGB premultiplied-alpha gradient interpolation.
 * - Deterministic event liveness (`staleEventReason`) and atomic per-event argument validation (`EVENT_ARGUMENTS`, `INVALID_EVENT`).
 */
object PortableV6Spec {
    const val MAX_BOX_LENGTH = 1_000_000.0
    const val MAX_SVG_COORD = 1_000_000.0
    const val MAX_ARIA_LABEL_CHARS = ProtocolLimits.MAX_ARIA_LABEL_CHARS
    const val MAX_PATH_D_CHARS = ProtocolLimits.MAX_PATH_CHARS

    private val STRICT_ID_REGEX = Regex("^[A-Za-z][\\w-]{0,40}$")
    private val HEX_3_REGEX = Regex("^#([0-9a-fA-F]{3})$")
    private val HEX_4_REGEX = Regex("^#([0-9a-fA-F]{4})$")
    private val HEX_6_REGEX = Regex("^#([0-9a-fA-F]{6})$")
    private val HEX_8_REGEX = Regex("^#([0-9a-fA-F]{8})$")
    private val LOCAL_GRADIENT_URL_REGEX = Regex("^url\\(\\s*#([A-Za-z][\\w-]{0,40})\\s*\\)$")
    private val TRANSFORM_CALL_REGEX = Regex("([A-Za-z]+)\\s*\\(([^()]*)\\)")

    val DIV_BOX_ONLY_STYLES: Set<String> = setOf(
        "display", "flexDirection", "alignItems", "justifyContent", "gap",
        "width", "height", "minWidth", "minHeight", "maxWidth", "maxHeight",
        "padding", "paddingTop", "paddingRight", "paddingBottom", "paddingLeft",
        "borderWidth", "borderColor", "borderRadius", "backgroundColor", "opacity",
    )

    val TEXT_STYLES: Set<String> = setOf(
        "color", "fontSize", "fontWeight", "fontStyle", "textDecoration", "lineHeight", "textAlign",
    )

    val DIV_STYLES: Set<String> = DIV_BOX_ONLY_STYLES + TEXT_STYLES

    val SVG_SHAPES: Set<String> = setOf("path", "circle", "rect")
    val SVG_G_ALLOWED_CHILDREN: Set<String> = setOf("g", "path", "circle", "rect", "text")
    val SVG_ROOT_ALLOWED_CHILDREN: Set<String> = SVG_G_ALLOWED_CHILDREN + "linearGradient"

    private val VALID_FONT_WEIGHT_INTS: Set<Int> = setOf(100, 200, 300, 400, 500, 600, 700, 800, 900)

    /**
     * 2D Affine matrix in SVG column-vector convention:
     * Composing `M1 * M2` means `M2` is applied to the point first, then `M1`:
     * e.g. `translate(10, 0) scale(2)` maps `(1, 0)` -> `(2, 0)` -> `(12, 0)`.
     */
    data class Affine2D(
        val a: Double = 1.0,
        val b: Double = 0.0,
        val c: Double = 0.0,
        val d: Double = 1.0,
        val e: Double = 0.0,
        val f: Double = 0.0,
    ) {
        fun times(rhs: Affine2D): Affine2D = Affine2D(
            a = a * rhs.a + c * rhs.b,
            b = b * rhs.a + d * rhs.b,
            c = a * rhs.c + c * rhs.d,
            d = b * rhs.c + d * rhs.d,
            e = a * rhs.e + c * rhs.f + e,
            f = b * rhs.e + d * rhs.f + f,
        )

        fun mapPoint(x: Double, y: Double): Pair<Double, Double> =
            (a * x + c * y + e) to (b * x + d * y + f)

        companion object {
            val IDENTITY = Affine2D()

            fun translate(tx: Double, ty: Double = 0.0): Affine2D =
                Affine2D(e = tx, f = ty)

            fun scale(sx: Double, sy: Double = sx): Affine2D =
                Affine2D(a = sx, d = sy)

            fun rotate(deg: Double, cx: Double = 0.0, cy: Double = 0.0): Affine2D {
                val rad = deg * PI / 180.0
                val cosA = cos(rad)
                val sinA = sin(rad)
                val rot = Affine2D(a = cosA, b = sinA, c = -sinA, d = cosA)
                return if (cx == 0.0 && cy == 0.0) {
                    rot
                } else {
                    translate(cx, cy).times(rot).times(translate(-cx, -cy))
                }
            }
        }
    }

    data class ParsedSvgTransform(
        val canonical: String,
        val matrix: Affine2D,
    )

    /**
     * Parses SVG transform list (`translate(tx[,ty])`, `scale(sx[,sy])`, `rotate(deg[,cx,cy])` with plain finite numbers ONLY).
     */
    fun parseSvgTransform(el: JsonElement?): ParsedSvgTransform? {
        val prim = el as? JsonPrimitive ?: return null
        if (!prim.isString) return null
        val raw = prim.content
        if (raw.unicodeCodePointCount() > ProtocolLimits.MAX_TRANSFORM_CHARS) return null
        val text = raw.trim()
        if (text.isEmpty()) return null
        val leftover = text.replace(TRANSFORM_CALL_REGEX, "").replace(Regex("[\\s,]"), "")
        if (leftover.isNotEmpty()) return null

        val parts = ArrayList<String>()
        var matrix = Affine2D.IDENTITY

        for (m in TRANSFORM_CALL_REGEX.findAll(text)) {
            if (parts.size >= ProtocolLimits.MAX_TRANSFORM_FUNCTIONS) return null
            val fn = m.groupValues[1]
            val argBody = m.groupValues[2].trim()
            if (argBody.isEmpty() || argBody.startsWith(",") || argBody.endsWith(",") || argBody.contains(Regex(",\\s*,"))) {
                return null
            }
            val argTokens = argBody.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            if (argTokens.isEmpty()) return null
            val nums = ArrayList<Double>(argTokens.size)
            for (tok in argTokens) {
                if (tok.any { it.isLetter() && it != 'e' && it != 'E' }) return null
                val d = tok.toDoubleOrNull() ?: return null
                if (!d.isFinite() || abs(d) > MAX_SVG_COORD) return null
                nums.add(d)
            }
            when (fn) {
                "translate" -> {
                    if (nums.size !in 1..2) return null
                    val tx = nums[0]
                    val ty = nums.getOrElse(1) { 0.0 }
                    matrix = matrix.times(Affine2D.translate(tx, ty))
                    parts.add("translate(${fmtNum(tx)} ${fmtNum(ty)})")
                }
                "scale" -> {
                    if (nums.size !in 1..2) return null
                    val sx = nums[0]
                    val sy = nums.getOrElse(1) { sx }
                    matrix = matrix.times(Affine2D.scale(sx, sy))
                    parts.add("scale(${fmtNum(sx)} ${fmtNum(sy)})")
                }
                "rotate" -> {
                    if (nums.size != 1 && nums.size != 3) return null
                    val deg = nums[0]
                    if (nums.size == 1) {
                        matrix = matrix.times(Affine2D.rotate(deg))
                        parts.add("rotate(${fmtNum(deg)})")
                    } else {
                        val cx = nums[1]
                        val cy = nums[2]
                        matrix = matrix.times(Affine2D.rotate(deg, cx, cy))
                        parts.add("rotate(${fmtNum(deg)} ${fmtNum(cx)} ${fmtNum(cy)})")
                    }
                }
                else -> return null
            }
        }
        if (parts.isEmpty()) return null
        return ParsedSvgTransform(canonical = parts.joinToString(" "), matrix = matrix)
    }

    /**
     * Normalizes `portable/1` colors to exact `#rrggbbaa` lowercase or `"currentColor"`
     * (and `"none"` / `url(#id)` when `allowSvgNoneAndGradient == true`).
     * Exact case/whitespace per `portable-semantic.json` & Spec v8 §17 Item 8:
     * - `#RGB`, `#RGBA`, `#RRGGBB`, `#RRGGBBAA` (any hex case, no leading/trailing spaces) -> `#rrggbbaa`
     * - `"transparent"` -> `"#00000000"`, `"black"` -> `"#000000ff"`, `"white"` -> `"#ffffffff"`
     * - `"currentColor"` -> `"currentColor"`
     */
    fun normalizeColor(
        el: JsonElement?,
        idPrefix: String = "",
        allowSvgNoneAndGradient: Boolean = false,
        allowedLocalGradientIds: Set<String>? = null,
    ): String? {
        val prim = el as? JsonPrimitive ?: return null
        if (!prim.isString) return null
        val raw = prim.content
        if (raw.unicodeCodePointCount() > ProtocolLimits.MAX_PAINT_CHARS) return null

        when (raw) {
            "currentColor" -> return "currentColor"
            "black" -> return "#000000ff"
            "white" -> return "#ffffffff"
            "transparent" -> return "#00000000"
            "none" -> return if (allowSvgNoneAndGradient) "none" else null
        }

        val lower = raw.lowercase()
        HEX_3_REGEX.matchEntire(lower)?.let { m ->
            val h = m.groupValues[1]
            return "#${h[0]}${h[0]}${h[1]}${h[1]}${h[2]}${h[2]}ff"
        }
        HEX_4_REGEX.matchEntire(lower)?.let { m ->
            val h = m.groupValues[1]
            return "#${h[0]}${h[0]}${h[1]}${h[1]}${h[2]}${h[2]}${h[3]}${h[3]}"
        }
        HEX_6_REGEX.matchEntire(lower)?.let { m ->
            return "#${m.groupValues[1]}ff"
        }
        HEX_8_REGEX.matchEntire(lower)?.let { m ->
            return "#${m.groupValues[1]}"
        }

        if (allowSvgNoneAndGradient) {
            val urlMatch = LOCAL_GRADIENT_URL_REGEX.matchEntire(raw) ?: return null
            val rawGradId = urlMatch.groupValues[1]
            val prefixed = idPrefix + rawGradId
            if (allowedLocalGradientIds != null && prefixed !in allowedLocalGradientIds) {
                return null
            }
            return "url(#$prefixed)"
        }
        return null
    }

    fun isLocalGradientUrlSyntax(el: JsonElement?): Boolean {
        val prim = el as? JsonPrimitive ?: return false
        return prim.isString && LOCAL_GRADIENT_URL_REGEX.matches(prim.content)
    }

    fun parseViewBox(el: JsonElement?): JsonPrimitive? {
        val prim = el as? JsonPrimitive ?: return null
        if (!prim.isString) return null
        val raw = prim.content
        if (raw.unicodeCodePointCount() > 100) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(",") || trimmed.endsWith(",") || trimmed.contains(Regex(",\\s*,"))) {
            return null
        }
        val tokens = trimmed.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (tokens.size != 4) return null
        val nums = tokens.map { tok ->
            if (tok.any { it.isLetter() && it != 'e' && it != 'E' }) return null
            val d = tok.toDoubleOrNull() ?: return null
            if (!d.isFinite() || abs(d) > MAX_SVG_COORD) return null
            d
        }
        if (nums[2] <= 0.0 || nums[3] <= 0.0) return null
        return JsonPrimitive("${fmtNum(nums[0])} ${fmtNum(nums[1])} ${fmtNum(nums[2])} ${fmtNum(nums[3])}")
    }

    fun parseFontWeight(el: JsonElement?): JsonPrimitive? {
        val prim = el as? JsonPrimitive ?: return null
        if (prim.booleanOrNull != null) return null
        if (prim.isString) {
            return when (prim.content) {
                "normal" -> JsonPrimitive(400)
                "bold" -> JsonPrimitive(700)
                else -> null
            }
        }
        val iv = exactSafeNonNegativeIntOrNull(prim) ?: return null
        return if (iv in VALID_FONT_WEIGHT_INTS) JsonPrimitive(iv) else null
    }

    fun parseFraction(el: JsonElement?): Double? {
        val prim = el as? JsonPrimitive ?: return null
        if (prim.booleanOrNull != null) return null
        if (prim.isString) {
            val t = prim.content
            if (!t.endsWith("%")) return null
            val numStr = t.dropLast(1)
            if (numStr.isEmpty() || numStr.any { it.isWhitespace() }) return null
            val pct = numStr.toDoubleOrNull() ?: return null
            if (!pct.isFinite() || pct < 0.0 || pct > 100.0) return null
            return pct / 100.0
        }
        val d = prim.doubleOrNull ?: return null
        if (!d.isFinite() || d < 0.0 || d > 1.0) return null
        return d
    }

    fun parseCoordinate(el: JsonElement?, gradientUnits: String = "objectBoundingBox"): JsonPrimitive? {
        val prim = el as? JsonPrimitive ?: return null
        if (prim.booleanOrNull != null) return null
        if (prim.isString) {
            val t = prim.content
            if (!t.endsWith("%")) return null
            val numStr = t.dropLast(1)
            if (numStr.isEmpty() || numStr.any { it.isWhitespace() }) return null
            val pct = numStr.toDoubleOrNull() ?: return null
            if (!pct.isFinite() || abs(pct) > MAX_SVG_COORD) return null
            return JsonPrimitive("${fmtNum(pct)}%")
        }
        val d = prim.doubleOrNull ?: return null
        if (!d.isFinite() || abs(d) > MAX_SVG_COORD) return null
        if (gradientUnits == "objectBoundingBox" && (d < 0.0 || d > 1.0)) return null
        return PropAndCssValidator.jsonNumberPrimitive(d)
    }

    /**
     * Strict SVG path `d` grammar parser (`portable-semantic.json` `paths` + Spec v6 §15 Item 9b):
     * - Rejects > 4000 Unicode code points without truncating.
     * - Empty/whitespace `d` (`""`) is valid (`portable-semantic.json` line 253); non-empty must start with `M` or `m`.
     * - Parses every command (`M/m`, `L/l`, `H/h`, `V/v`, `C/c`, `S/s`, `Q/q`, `T/t`, `A/a`, `Z/z`),
     *   exact parameter tuple arity, finite numbers, non-negative radii on `A/a`, exact `0 | 1` arc flags,
     *   and rejects leading/trailing/double commas!
     */
    fun isValidSvgPathData(d: String, maxChars: Int = MAX_PATH_D_CHARS): Boolean {
        if (d.unicodeCodePointCount() > maxChars) return false
        val s = d.trim()
        if (s.isEmpty()) return true
        var i = 0
        val n = s.length

        fun skipWsOnly() {
            while (i < n && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun skipSep(allowComma: Boolean): Boolean {
            var sawComma = false
            while (i < n) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++
                } else if (c == ',') {
                    if (!allowComma || sawComma) return false
                    sawComma = true
                    i++
                } else {
                    break
                }
            }
            return true
        }

        fun readNumber(allowLeadingComma: Boolean): Double? {
            if (!skipSep(allowLeadingComma)) return null
            if (i >= n) return null
            val start = i
            if (s[i] == '+' || s[i] == '-') i++
            var digitsBefore = 0
            while (i < n && s[i] in '0'..'9') {
                i++
                digitsBefore++
            }
            var digitsAfter = 0
            if (i < n && s[i] == '.') {
                i++
                while (i < n && s[i] in '0'..'9') {
                    i++
                    digitsAfter++
                }
            }
            if (digitsBefore == 0 && digitsAfter == 0) {
                i = start
                return null
            }
            if (i < n && (s[i] == 'e' || s[i] == 'E')) {
                val expPos = i
                i++
                if (i < n && (s[i] == '+' || s[i] == '-')) i++
                var expDigits = 0
                while (i < n && s[i] in '0'..'9') {
                    i++
                    expDigits++
                }
                if (expDigits == 0) {
                    i = expPos
                    return null
                }
            }
            val v = s.substring(start, i).toDoubleOrNull() ?: return null
            return if (v.isFinite() && abs(v) <= MAX_SVG_COORD) v else null
        }

        fun readArcFlag(allowLeadingComma: Boolean): Int? {
            if (!skipSep(allowLeadingComma)) return null
            if (i >= n) return null
            return when (s[i]) {
                '0' -> {
                    i++
                    0
                }
                '1' -> {
                    i++
                    1
                }
                else -> null
            }
        }

        fun isCommandChar(c: Char): Boolean = c in "MmLlHhVvCcSsQqTtAaZz"

        skipWsOnly()
        if (i >= n || (s[i] != 'M' && s[i] != 'm')) return false

        while (i < n) {
            skipWsOnly()
            if (i >= n) break
            val cmd = s[i]
            if (!isCommandChar(cmd)) return false
            i++
            if (cmd == 'Z' || cmd == 'z') {
                skipWsOnly()
                if (i < n && !isCommandChar(s[i])) return false
                continue
            }

            var tupleCount = 0
            while (true) {
                val savedPos = i
                skipWsOnly()
                if (i >= n || isCommandChar(s[i])) break
                i = savedPos

                val firstAllowsComma = tupleCount > 0
                when (cmd) {
                    'M', 'm', 'L', 'l', 'T', 't' -> {
                        readNumber(firstAllowsComma) ?: return false
                        readNumber(true) ?: return false
                    }
                    'H', 'h', 'V', 'v' -> {
                        readNumber(firstAllowsComma) ?: return false
                    }
                    'S', 's', 'Q', 'q' -> {
                        readNumber(firstAllowsComma) ?: return false
                        repeat(3) { readNumber(true) ?: return false }
                    }
                    'C', 'c' -> {
                        readNumber(firstAllowsComma) ?: return false
                        repeat(5) { readNumber(true) ?: return false }
                    }
                    'A', 'a' -> {
                        val rx = readNumber(firstAllowsComma) ?: return false
                        val ry = readNumber(true) ?: return false
                        if (rx < 0.0 || ry < 0.0) return false
                        readNumber(true) ?: return false // x-axis-rotation
                        readArcFlag(true) ?: return false // large-arc-flag (0 or 1 ONLY)
                        readArcFlag(true) ?: return false // sweep-flag (0 or 1 ONLY)
                        readNumber(true) ?: return false // x
                        readNumber(true) ?: return false // y
                    }
                    else -> return false
                }
                tupleCount++
            }
            if (tupleCount == 0) return false
        }
        return true
    }

    // ---- sRGB Premultiplied-Alpha Piecewise Linear Gradient Interpolation (Spec v6 §15 Item 9b) ----

    data class SrgbRgba(val r: Int, val g: Int, val b: Int, val a: Double)

    data class ResolvedGradientStop(
        val offset: Double,
        val color: SrgbRgba,
    )

    fun parseNormalizedHexRgba(hex: String, extraOpacity: Double = 1.0): SrgbRgba? {
        val h = hex.removePrefix("#")
        return when (h.length) {
            6 -> {
                val r = h.substring(0, 2).toIntOrNull(16) ?: return null
                val g = h.substring(2, 4).toIntOrNull(16) ?: return null
                val b = h.substring(4, 6).toIntOrNull(16) ?: return null
                SrgbRgba(r, g, b, extraOpacity.coerceIn(0.0, 1.0))
            }
            8 -> {
                val r = h.substring(0, 2).toIntOrNull(16) ?: return null
                val g = h.substring(2, 4).toIntOrNull(16) ?: return null
                val b = h.substring(4, 6).toIntOrNull(16) ?: return null
                val a = (h.substring(6, 8).toIntOrNull(16) ?: return null) / 255.0
                SrgbRgba(r, g, b, (a * extraOpacity).coerceIn(0.0, 1.0))
            }
            else -> null
        }
    }

    fun evalGradientSrgbPremul(stops: List<ResolvedGradientStop>, t: Double): SrgbRgba? {
        if (stops.isEmpty()) return null
        if (stops.size == 1) return stops[0].color
        if (t < stops.first().offset) return stops.first().color
        if (t >= stops.last().offset) return stops.last().color

        var leftIdx = 0
        for (idx in stops.indices) {
            if (stops[idx].offset <= t) leftIdx = idx else break
        }
        if (leftIdx >= stops.lastIndex) return stops.last().color
        val left = stops[leftIdx]
        val right = stops[leftIdx + 1]
        val span = right.offset - left.offset
        if (span <= 1e-9) return right.color

        val u = ((t - left.offset) / span).coerceIn(0.0, 1.0)
        val a0 = left.color.a
        val a1 = right.color.a
        val a = a0 + (a1 - a0) * u
        if (a <= 1e-9) return SrgbRgba(0, 0, 0, 0.0)

        val pr = (left.color.r * a0) + ((right.color.r * a1) - (left.color.r * a0)) * u
        val pg = (left.color.g * a0) + ((right.color.g * a1) - (left.color.g * a0)) * u
        val pb = (left.color.b * a0) + ((right.color.b * a1) - (left.color.b * a0)) * u

        return SrgbRgba(
            r = (pr / a).roundToInt().coerceIn(0, 255),
            g = (pg / a).roundToInt().coerceIn(0, 255),
            b = (pb / a).roundToInt().coerceIn(0, 255),
            a = a.coerceIn(0.0, 1.0),
        )
    }

    // ---- Strict Portable Primitive Node / Style / SVG Validation ----

    data class V6PropsCheckResult(
        val cleanProps: JsonObject,
        val droppedCount: Int,
        val droppedMessages: List<String>,
        /** True when a `<stop>` violates non-decreasing offset order and must be left out with `INVALID_NODE`. */
        val rejectStopNode: Boolean = false,
    )

    private fun checkNonNegativeBoxNumber(el: JsonElement?, max: Double = MAX_BOX_LENGTH): JsonPrimitive? {
        val prim = el as? JsonPrimitive ?: return null
        if (prim.isString || prim.booleanOrNull != null) return null
        val d = prim.doubleOrNull ?: return null
        if (!d.isFinite() || d < 0.0 || d > max) return null
        return PropAndCssValidator.jsonNumberPrimitive(d)
    }

    private fun checkSignedSvgCoord(el: JsonElement?): JsonPrimitive? {
        val prim = el as? JsonPrimitive ?: return null
        if (prim.isString || prim.booleanOrNull != null) return null
        val d = prim.doubleOrNull ?: return null
        if (!d.isFinite() || abs(d) > MAX_SVG_COORD) return null
        return PropAndCssValidator.jsonNumberPrimitive(d)
    }

    private fun checkUnitRatioStrict(el: JsonElement?): JsonPrimitive? {
        val prim = el as? JsonPrimitive ?: return null
        if (prim.isString || prim.booleanOrNull != null) return null
        val d = prim.doubleOrNull ?: return null
        if (!d.isFinite() || d < 0.0 || d > 1.0) return null
        return PropAndCssValidator.jsonNumberPrimitive(d)
    }

    private fun checkBoundedStringRejectOverlimit(el: JsonElement?, maxCodePoints: Int): JsonPrimitive? {
        val prim = el as? JsonPrimitive ?: return null
        if (!prim.isString) return null
        if (prim.content.unicodeCodePointCount() > maxCodePoints) return null
        return prim
    }

    private fun checkStrictId(el: JsonElement?, idPrefix: String): JsonPrimitive? {
        val prim = el as? JsonPrimitive ?: return null
        if (!prim.isString || !STRICT_ID_REGEX.matches(prim.content)) return null
        return JsonPrimitive(idPrefix + prim.content)
    }

    private fun contradictsPortableStyleRules(keptStyle: Map<String, JsonElement>): Boolean {
        fun num(key: String): Double? = (keptStyle[key] as? JsonPrimitive)?.doubleOrNull

        val fs = num("fontSize")
        val lh = num("lineHeight")
        if (fs != null && lh != null && lh < fs) return true

        val w = num("width")
        val minW = num("minWidth")
        val maxW = num("maxWidth")
        if (minW != null && maxW != null && minW > maxW) return true
        if (w != null && minW != null && w < minW) return true
        if (w != null && maxW != null && w > maxW) return true

        val h = num("height")
        val minH = num("minHeight")
        val maxH = num("maxHeight")
        if (minH != null && maxH != null && minH > maxH) return true
        if (h != null && minH != null && h < minH) return true
        if (h != null && maxH != null && h > maxH) return true

        // Spec v8 §17 Item 2 & PORTABLE_STYLE_RULES: border-box padding + 2*borderWidth must fit explicit width/maxWidth and height/maxHeight
        val bw = num("borderWidth") ?: 0.0
        val padAll = num("padding") ?: 0.0
        val padL = num("paddingLeft") ?: padAll
        val padR = num("paddingRight") ?: padAll
        val padT = num("paddingTop") ?: padAll
        val padB = num("paddingBottom") ?: padAll

        if (w != null && padL + padR + 2.0 * bw > w) return true
        if (maxW != null && padL + padR + 2.0 * bw > maxW) return true
        if (h != null && padT + padB + 2.0 * bw > h) return true
        if (maxH != null && padT + padB + 2.0 * bw > maxH) return true

        return false
    }

    fun validateBoxOrSpanPropsV6(
        tag: String, // "div" or "span"
        rawProps: JsonObject,
        idPrefix: String,
    ): V6PropsCheckResult {
        val clean = LinkedHashMap<String, JsonElement>()
        val dropped = ArrayList<String>()

        for ((propName, propVal) in rawProps) {
            if (propName in RESERVED_PROTOTYPE_KEYS) {
                dropped.add(propName)
                continue
            }
            when (propName) {
                "id" -> {
                    val v = checkStrictId(propVal, idPrefix)
                    if (v == null) dropped.add(propName) else clean[propName] = v
                }
                "ariaLabel" -> {
                    val v = checkBoundedStringRejectOverlimit(propVal, MAX_ARIA_LABEL_CHARS)
                    if (v == null) dropped.add(propName) else clean[propName] = v
                }
                "hidden" -> {
                    val b = (propVal as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                    if (b == null) dropped.add(propName) else clean[propName] = JsonPrimitive(b)
                }
                "style" -> {
                    val styleObj = propVal as? JsonObject
                    if (styleObj == null) {
                        dropped.add("style")
                    } else {
                        val allowedKeys = if (tag == "div") DIV_STYLES else TEXT_STYLES
                        val keptStyle = LinkedHashMap<String, JsonElement>()
                        for ((sk, sv) in styleObj) {
                            if (sk in RESERVED_PROTOTYPE_KEYS || sk !in allowedKeys) {
                                dropped.add("style.$sk")
                                continue
                            }
                            val checkedVal: JsonElement? = when (sk) {
                                "display" -> checkEnum(sv, setOf("flex", "none"))
                                "flexDirection" -> checkEnum(sv, setOf("row", "column"))
                                "alignItems" -> checkEnum(sv, setOf("start", "center", "end", "stretch"))
                                "justifyContent" -> checkEnum(sv, setOf("start", "center", "end", "space-between"))
                                "fontStyle" -> checkEnum(sv, setOf("normal", "italic"))
                                "textDecoration" -> checkEnum(sv, setOf("none", "underline", "line-through"))
                                "textAlign" -> checkEnum(sv, setOf("start", "center", "end"))
                                "fontWeight" -> parseFontWeight(sv)
                                "opacity" -> checkUnitRatioStrict(sv)
                                "color", "backgroundColor", "borderColor" ->
                                    normalizeColor(sv, idPrefix, allowSvgNoneAndGradient = false)?.let { JsonPrimitive(it) }
                                else -> checkNonNegativeBoxNumber(sv)
                            }
                            if (checkedVal == null) {
                                dropped.add("style.$sk")
                            } else {
                                keptStyle[sk] = checkedVal
                            }
                        }

                        // PORTABLE_STYLE_RULES & Spec v8 §17 Item 2:
                        // Contradictory combinations reject the whole `style` prop with 1 PROP_DROPPED;
                        // otherwise `style` (even `{}`) stays on `p`.
                        if (tag == "div" && contradictsPortableStyleRules(keptStyle)) {
                            dropped.add("style(contradictoryCombination)")
                        } else if (tag == "span" && contradictsPortableStyleRules(keptStyle)) {
                            dropped.add("style(contradictoryCombination)")
                        } else {
                            clean["style"] = JsonObject(keptStyle)
                        }
                    }
                }
                else -> dropped.add(propName)
            }
        }
        return V6PropsCheckResult(JsonObject(clean), dropped.size, dropped)
    }

    /**
     * Collects unique `<linearGradient id="...">` definitions in direct children of `<svg>`
     * AND tracks all seen IDs across the `<svg>` root (a second occurrence of the same `id` in one `<svg>` root is dropped!).
     */
    class SvgRootContext(val idPrefix: String) {
        val seenIds = HashSet<String>()
        val validGradientIds = LinkedHashSet<String>()
    }

    fun collectSvgRootGradients(svgRawChildren: JsonArray?, idPrefix: String, svgRootId: String? = null): Set<String> {
        if (svgRawChildren == null) return emptySet()
        val seenInRoot = HashSet<String>()
        if (svgRootId != null && STRICT_ID_REGEX.matches(svgRootId)) {
            seenInRoot.add(idPrefix + svgRootId)
        }
        val gradients = LinkedHashSet<String>()

        fun scanIdsPreOrder(items: JsonArray?, directUnderSvg: Boolean) {
            if (items == null) return
            for (el in items) {
                val obj = el as? JsonObject ?: continue
                val t = (obj["t"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                val rawId = (obj["p"] as? JsonObject)?.get("id")?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
                if (rawId != null && STRICT_ID_REGEX.matches(rawId)) {
                    val prefixed = idPrefix + rawId
                    val isFirst = seenInRoot.add(prefixed)
                    if (isFirst && directUnderSvg && t == "linearGradient") {
                        gradients.add(prefixed)
                    } else if (!isFirst) {
                        gradients.remove(prefixed)
                    }
                }
                if (t == "g" || t == "linearGradient") {
                    scanIdsPreOrder(obj["c"] as? JsonArray, directUnderSvg = false)
                }
            }
        }

        scanIdsPreOrder(svgRawChildren, directUnderSvg = true)
        return gradients
    }

    fun validateSvgNodePropsV6(
        tag: String,
        rawProps: JsonObject,
        idPrefix: String,
        localGradientIds: Set<String>,
        seenSvgIds: MutableSet<String>,
        previousStopOffset: Double? = null,
        /** Spec v8 §17 Item 9: when `"none"`, an unresolved `url(#id)` emits `PROP_DROPPED` AND sets paint to `"none"` so it never falls back to `currentColor`. */
        unresolvedGradientPaint: String? = null,
    ): Pair<V6PropsCheckResult, Double?> {
        val clean = LinkedHashMap<String, JsonElement>()
        val dropped = ArrayList<String>()
        var explicitStopOffset: Double? = null

        val gradientUnits = if (tag == "linearGradient") {
            val gu = (rawProps["gradientUnits"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (gu == "userSpaceOnUse") "userSpaceOnUse" else "objectBoundingBox"
        } else {
            "objectBoundingBox"
        }

        fun checkUniqueSvgId(propVal: JsonElement): JsonPrimitive? {
            val checked = checkStrictId(propVal, idPrefix) ?: return null
            return if (seenSvgIds.add(checked.content)) checked else null
        }

        fun checkSvgPaintProp(propName: String, propVal: JsonElement): JsonPrimitive? {
            val normalized = normalizeColor(propVal, idPrefix, allowSvgNoneAndGradient = true, allowedLocalGradientIds = localGradientIds)
            if (normalized != null) return JsonPrimitive(normalized)
            if (unresolvedGradientPaint != null && isLocalGradientUrlSyntax(propVal)) {
                dropped.add(propName)
                return JsonPrimitive(unresolvedGradientPaint)
            }
            return null
        }

        for ((propName, propVal) in rawProps) {
            if (propName in RESERVED_PROTOTYPE_KEYS) {
                dropped.add(propName)
                continue
            }
            var handledFallbackPaint = false
            val checked: JsonElement? = when (tag) {
                "svg" -> when (propName) {
                    "width", "height", "strokeWidth" -> checkNonNegativeBoxNumber(propVal)
                    "opacity" -> checkUnitRatioStrict(propVal)
                    "viewBox" -> parseViewBox(propVal)
                    "preserveAspectRatio" -> checkEnum(propVal, setOf("none", "xMidYMid meet"))
                    "fill", "stroke" -> {
                        val before = dropped.size
                        val p = checkSvgPaintProp(propName, propVal)
                        if (dropped.size > before) handledFallbackPaint = true
                        p
                    }
                    "id" -> checkUniqueSvgId(propVal)
                    "ariaLabel" -> checkBoundedStringRejectOverlimit(propVal, MAX_ARIA_LABEL_CHARS)
                    "hidden" -> (propVal as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull?.let { JsonPrimitive(it) }
                    else -> null
                }

                "path", "circle", "rect", "g", "text" -> when (propName) {
                    "fill", "stroke" -> {
                        val before = dropped.size
                        val p = checkSvgPaintProp(propName, propVal)
                        if (dropped.size > before) handledFallbackPaint = true
                        p
                    }
                    "strokeWidth" -> checkNonNegativeBoxNumber(propVal)
                    "strokeLinecap" -> checkEnum(propVal, setOf("butt", "round", "square"))
                    "strokeLinejoin" -> checkEnum(propVal, setOf("miter", "round", "bevel"))
                    "opacity", "fillOpacity", "strokeOpacity" -> checkUnitRatioStrict(propVal)
                    "fillRule" -> checkEnum(propVal, setOf("nonzero", "evenodd"))
                    "transform" -> parseSvgTransform(propVal)?.let { JsonPrimitive(it.canonical) }
                    "id" -> checkUniqueSvgId(propVal)
                    "d" -> if (tag == "path") {
                        val s = (propVal as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (s != null && isValidSvgPathData(s)) JsonPrimitive(s) else null
                    } else null
                    "cx", "cy" -> if (tag == "circle") checkSignedSvgCoord(propVal) else null
                    "r" -> if (tag == "circle") checkNonNegativeBoxNumber(propVal) else null
                    "x", "y" -> if (tag == "rect" || tag == "text") checkSignedSvgCoord(propVal) else null
                    "width", "height", "rx", "ry" -> if (tag == "rect") checkNonNegativeBoxNumber(propVal) else null
                    "fontSize" -> if (tag == "text") checkNonNegativeBoxNumber(propVal) else null
                    "fontWeight" -> if (tag == "text") parseFontWeight(propVal) else null
                    "textAnchor" -> if (tag == "text") checkEnum(propVal, setOf("start", "middle", "end")) else null
                    else -> null
                }

                "linearGradient" -> when (propName) {
                    "id" -> checkUniqueSvgId(propVal)
                    "gradientUnits" -> checkEnum(propVal, setOf("objectBoundingBox", "userSpaceOnUse"))
                    "gradientTransform" -> parseSvgTransform(propVal)?.let { JsonPrimitive(it.canonical) }
                    "x1", "y1", "x2", "y2" -> parseCoordinate(propVal, gradientUnits)
                    else -> null
                }

                "stop" -> when (propName) {
                    "offset" -> {
                        val frac = parseFraction(propVal)
                        if (frac == null) {
                            null
                        } else {
                            explicitStopOffset = frac
                            PropAndCssValidator.jsonNumberPrimitive(frac)
                        }
                    }
                    "stopColor" -> normalizeColor(propVal, idPrefix, allowSvgNoneAndGradient = false)?.let { JsonPrimitive(it) }
                    "stopOpacity" -> checkUnitRatioStrict(propVal)
                    else -> null
                }

                else -> null
            }

            if (checked == null) {
                if (!handledFallbackPaint) dropped.add(propName)
            } else {
                clean[propName] = checked
            }
        }

        if (tag == "stop") {
            val effectiveOffset = explicitStopOffset ?: 0.0
            if (previousStopOffset != null && effectiveOffset < previousStopOffset - 1e-9) {
                // PORTABLE_SVG_RULES ("stop-order"): decreasing stop is left out with INVALID_NODE!
                return V6PropsCheckResult(
                    cleanProps = JsonObject(clean),
                    droppedCount = dropped.size,
                    droppedMessages = dropped,
                    rejectStopNode = true,
                ) to previousStopOffset
            }
            return V6PropsCheckResult(JsonObject(clean), dropped.size, dropped) to effectiveOffset
        }

        return V6PropsCheckResult(JsonObject(clean), dropped.size, dropped) to previousStopOffset
    }

    private fun checkEnum(el: JsonElement?, allowed: Set<String>): JsonPrimitive? {
        val prim = el as? JsonPrimitive ?: return null
        return if (prim.isString && prim.content in allowed) prim else null
    }

    private fun fmtNum(v: Double): String {
        val normalized = if (v == 0.0) 0.0 else v
        val asLong = normalized.toLong()
        return if (normalized == asLong.toDouble() && abs(normalized) <= MAX_JS_SAFE_INTEGER.toDouble()) {
            asLong.toString()
        } else {
            val s = normalized.toString()
            if (s.endsWith(".0")) s.dropLast(2) else s
        }
    }
}

/**
 * Deterministic event liveness & argument schema validator (`packages/protocol/src/events.ts` & `session-semantic.json`).
 */
object SproutEventValidator {
    private val MODIFIER_FIELDS: Set<String> = setOf("shiftKey", "ctrlKey", "altKey", "metaKey")
    private val POINTER_FIELDS: Set<String> = setOf("type", "clientX", "clientY", "offsetX", "offsetY", "button") + MODIFIER_FIELDS
    private val WHEEL_FIELDS: Set<String> = POINTER_FIELDS + setOf("deltaX", "deltaY")
    private val KEY_FIELDS: Set<String> = setOf("type", "key", "code") + MODIFIER_FIELDS
    private val INPUT_FIELDS: Set<String> = setOf("type", "value", "checked")
    private val GENERIC_FIELDS: Set<String> = setOf("type")

    val EVENT_ARGUMENTS_BY_NAME: Map<String, String> = buildMap {
        put("checkedChange", "boolean")
        put("valueChange", "string")
        for (n in listOf("change", "input")) put(n, "input")
        for (n in listOf(
            "click", "doubleClick", "contextMenu", "mouseDown", "mouseUp", "mouseEnter", "mouseLeave",
            "mouseMove", "mouseOver", "mouseOut", "pointerDown", "pointerUp", "pointerMove", "pointerEnter",
            "pointerLeave", "pointerOver", "pointerOut", "pointerCancel", "dragStart", "drag", "dragEnd",
            "dragEnter", "dragLeave", "dragOver", "drop",
        )) {
            put(n, "pointer")
        }
        put("wheel", "wheel")
        for (n in listOf("keyDown", "keyUp", "keyPress")) put(n, "key")
    }

    fun eventArgumentKindFor(eventName: String, componentEventArgs: Map<String, String> = emptyMap()): String =
        componentEventArgs[eventName] ?: EVENT_ARGUMENTS_BY_NAME[eventName] ?: "generic"

    /**
     * Maximum Unicode code points allowed for string/input event arguments on `(kind, type)` (`packages/validator/src/eventArgs.ts`):
     * smallest `max` across `spec.controlledProps` with `PropSchema.Str`, else `ProtocolLimits.MAX_EVENT_STRING_CHARS`.
     */
    fun eventTextLimit(catalog: Catalog, kind: NodeKind, type: String): Int {
        if (kind != NodeKind.COMPONENT) return ProtocolLimits.MAX_EVENT_STRING_CHARS
        val spec = catalog.components[type] ?: return ProtocolLimits.MAX_EVENT_STRING_CHARS
        var minLimit: Int? = null
        for (prop in spec.controlledProps) {
            val schema = spec.props[prop]
            if (schema is PropSchema.Str) {
                if (minLimit == null || schema.max < minLimit) {
                    minLimit = schema.max
                }
            }
        }
        return minLimit ?: ProtocolLimits.MAX_EVENT_STRING_CHARS
    }

    fun staleEventReason(
        committedSurfaceId: String?,
        committedSurfaceGeneration: Long? = null,
        committedRuntimeEpoch: Long,
        tableIsCurrent: Boolean,
        committedFrameRuntimeEpoch: Long,
        committedFrameRevision: Long,
        committedTree: List<TreeNode>,
        eventSurfaceId: String?,
        eventSurfaceGeneration: Long? = null,
        eventEpoch: Long,
        eventFrameRevision: Long,
        nodeId: String,
        eventName: String,
        handler: Int? = null,
    ): String? {
        if (committedSurfaceId != null && eventSurfaceId != null && committedSurfaceId != eventSurfaceId) {
            return "surfaceId mismatch ($eventSurfaceId != $committedSurfaceId)"
        }
        if (committedSurfaceGeneration != null && eventSurfaceGeneration != null && committedSurfaceGeneration != eventSurfaceGeneration) {
            return "surfaceGeneration mismatch ($eventSurfaceGeneration != $committedSurfaceGeneration)"
        }
        if (!tableIsCurrent) {
            return "guest handler table is not current"
        }
        if (eventEpoch != committedRuntimeEpoch || committedFrameRuntimeEpoch != committedRuntimeEpoch) {
            return "runtimeEpoch mismatch (event=$eventEpoch, guest=$committedRuntimeEpoch, frame=$committedFrameRuntimeEpoch)"
        }
        if (eventFrameRevision != committedFrameRevision) {
            return "frameRevision mismatch ($eventFrameRevision != $committedFrameRevision)"
        }
        val element = findElementById(committedTree, nodeId) ?: return "node '$nodeId' not in committed tree"
        val binding = element.events[eventName] ?: return "event '$eventName' not bound on '$nodeId'"
        if (binding.epoch != eventEpoch) {
            return "binding epoch mismatch (${binding.epoch} != $eventEpoch)"
        }
        if (handler != null && binding.handler != handler) {
            return "handler id mismatch ($handler != ${binding.handler})"
        }
        return null
    }

    private fun findElementById(nodes: List<TreeNode>, targetId: String): TreeNode.Element? {
        for (n in nodes) {
            if (n is TreeNode.Element) {
                if (n.id == targetId) return n
                findElementById(n.children, targetId)?.let { return it }
            }
        }
        return null
    }

    fun validateEventArgsByKind(
        kind: String,
        args: JsonArray,
        maxStringCodePoints: Int = ProtocolLimits.MAX_EVENT_STRING_CHARS,
        maxNameCodePoints: Int = ProtocolLimits.MAX_EVENT_NAME_CHARS,
    ): String? {
        if (args.any { it is JsonObject && it.keys.any { k -> k in RESERVED_PROTOTYPE_KEYS } }) {
            return "Event arguments contain reserved prototype key"
        }
        return when (kind) {
            "boolean" -> {
                if (args.size != 1) return "Boolean event requires exactly 1 boolean argument"
                val b = (args[0] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                if (b == null) "Boolean event argument must be boolean" else null
            }

            "string" -> {
                if (args.size != 1) return "String event requires exactly 1 string argument"
                val s = (args[0] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return "String event argument must be string"
                if (s.unicodeCodePointCount() > maxStringCodePoints) {
                    "String event argument exceeds $maxStringCodePoints Unicode code points"
                } else {
                    null
                }
            }

            "input" -> validateOptionalDollarEvent(args, INPUT_FIELDS, maxStringCodePoints, maxNameCodePoints)
            "pointer" -> validateOptionalDollarEvent(args, POINTER_FIELDS, maxStringCodePoints, maxNameCodePoints)
            "wheel" -> validateOptionalDollarEvent(args, WHEEL_FIELDS, maxStringCodePoints, maxNameCodePoints)
            "key" -> validateOptionalDollarEvent(args, KEY_FIELDS, maxStringCodePoints, maxNameCodePoints)
            "generic" -> validateOptionalDollarEvent(args, GENERIC_FIELDS, maxStringCodePoints, maxNameCodePoints)
            else -> "Unknown event argument kind '$kind'"
        }
    }

    /**
     * Validates `args` atomically against per-event schema (`events.ts` & Spec v6 §15 Item 10):
     * - Returns `null` when valid, or a non-null diagnostic message for `INVALID_EVENT`.
     */
    fun validateEventArgs(
        eventName: String,
        args: JsonArray,
        maxStringCodePoints: Int = ProtocolLimits.MAX_EVENT_STRING_CHARS,
        componentEventArgs: Map<String, String> = emptyMap(),
    ): String? {
        val kind = eventArgumentKindFor(eventName, componentEventArgs)
        return validateEventArgsByKind(kind, args, maxStringCodePoints)
    }

    private fun validateOptionalDollarEvent(
        args: JsonArray,
        allowedFields: Set<String>,
        maxStringCodePoints: Int,
        maxNameCodePoints: Int,
    ): String? {
        if (args.isEmpty()) return null
        if (args.size != 1) return "Event accepts at most 1 \$event argument (got ${args.size})"
        val outer = args[0] as? JsonObject ?: return "Structured event argument must be a {\$event: ...} object"
        if (outer.size != 1 || "\$event" !in outer) {
            return "Arbitrary flat object arguments are forbidden; wrap structured event fields in {\$event: {...}}"
        }
        val evEl = outer["\$event"]
        if (evEl is JsonNull || evEl !is JsonObject) return "\$event must be a JSON object"
        if (evEl.size > ProtocolLimits.MAX_EVENT_FIELDS) return "\$event exceeds MAX_EVENT_FIELDS"

        for ((k, v) in evEl) {
            if (k in RESERVED_PROTOTYPE_KEYS || k !in allowedFields) {
                return "Undeclared or disallowed \$event field '$k'"
            }
            val prim = v as? JsonPrimitive ?: return "\$event.$k must be a primitive"
            if (v is JsonNull) return "\$event.$k must not be null"
            when (k) {
                "value" -> {
                    if (!prim.isString) return "\$event.value must be a string"
                    if (prim.content.unicodeCodePointCount() > maxStringCodePoints) {
                        return "\$event.value exceeds $maxStringCodePoints Unicode code points"
                    }
                }
                "type", "key", "code" -> {
                    if (!prim.isString) return "\$event.$k must be a string"
                    if (prim.content.unicodeCodePointCount() > maxNameCodePoints) {
                        return "\$event.$k exceeds $maxNameCodePoints Unicode code points"
                    }
                }
                "checked", "shiftKey", "ctrlKey", "altKey", "metaKey" -> {
                    if (prim.isString || prim.booleanOrNull == null) return "\$event.$k must be a boolean"
                }
                "clientX", "clientY", "offsetX", "offsetY", "deltaX", "deltaY", "button" -> {
                    if (prim.isString || prim.booleanOrNull != null) return "\$event.$k must be a finite number"
                    val d = prim.doubleOrNull
                    if (d == null || !d.isFinite()) return "\$event.$k must be a finite number"
                }
                else -> return "Unhandled \$event field '$k'"
            }
        }
        return null
    }
}
