package build.raft.app.markdown

/**
 * raft-ui 0.25 (raft-design #755): an app link shows its host in ASCII, so a look-alike
 * internationalised name can't pass for another. Every label with a character outside ASCII is
 * written in its `xn--` punycode form (RFC 3492), as the web's WHATWG URL parser does:
 * `bücher.example` → `xn--bcher-kva.example`. [address] is a host, or a host followed by a path
 * (`appLinkShortAddress`); only the part before the first `/` changes. Lower-cases the host and
 * reads the ideographic full stops (`。`, `．`, `｡`) as dots. Pure: called where the host is shown.
 */
internal fun raftAppLinkAsciiHost(address: String): String {
    val hostEnd = address.indexOf('/').let { if (it < 0) address.length else it }
    val host = address.substring(0, hostEnd)
    if (host.all { it.code < 0x80 }) return address
    val ascii =
        host
            .map { if (it == '。' || it == '．' || it == '｡') '.' else it }
            .joinToString("")
            .lowercase()
            .split('.')
            .joinToString(".") { label -> if (label.all { it.code < 0x80 }) label else "xn--" + punycodeEncode(label) }
    return ascii + address.substring(hostEnd)
}

private const val PunycodeBase = 36
private const val PunycodeTMin = 1
private const val PunycodeTMax = 26
private const val PunycodeSkew = 38
private const val PunycodeDamp = 700
private const val PunycodeInitialBias = 72
private const val PunycodeInitialN = 0x80

/** RFC 3492 section 6.3: [label]'s code points as basic code points, without the `xn--` prefix. */
internal fun punycodeEncode(label: String): String {
    val input = label.unicodeScalars()
    val output = StringBuilder()
    input.filter { it < 0x80 }.forEach { output.append(Char(it)) }
    val basicCount = output.length
    var handled = basicCount
    if (basicCount > 0) output.append('-')
    var n = PunycodeInitialN
    var delta = 0L
    var bias = PunycodeInitialBias
    while (handled < input.size) {
        val m = input.filter { it >= n }.min()
        delta += (m - n).toLong() * (handled + 1)
        n = m
        for (c in input) {
            if (c < n) delta++
            if (c == n) {
                var q = delta
                var k = PunycodeBase
                while (true) {
                    val t =
                        when {
                            k <= bias -> PunycodeTMin
                            k >= bias + PunycodeTMax -> PunycodeTMax
                            else -> k - bias
                        }
                    if (q < t) break
                    output.append(punycodeDigit(t + ((q - t) % (PunycodeBase - t)).toInt()))
                    q = (q - t) / (PunycodeBase - t)
                    k += PunycodeBase
                }
                output.append(punycodeDigit(q.toInt()))
                bias = punycodeAdapt(delta, handled + 1, handled == basicCount)
                delta = 0
                handled++
            }
        }
        delta++
        n++
    }
    return output.toString()
}

private fun punycodeAdapt(delta: Long, points: Int, first: Boolean): Int {
    var d = if (first) delta / PunycodeDamp else delta / 2
    d += d / points
    var k = 0
    while (d > ((PunycodeBase - PunycodeTMin) * PunycodeTMax) / 2) {
        d /= PunycodeBase - PunycodeTMin
        k += PunycodeBase
    }
    return (k + (PunycodeBase - PunycodeTMin + 1) * d / (d + PunycodeSkew)).toInt()
}

private fun punycodeDigit(d: Int): Char = if (d < 26) 'a' + d else '0' + (d - 26)

private fun String.unicodeScalars(): List<Int> {
    val points = ArrayList<Int>(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            points += 0x10000 + ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00)
            i += 2
        } else {
            points += c.code
            i++
        }
    }
    return points
}
