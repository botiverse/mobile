package build.raft.app

/**
 * What the iOS unhandled-exception hook records (`kuikly_unhandled_exception reason=… stack=…`).
 *
 * The record is one diagnostics line. A Kotlin/Native `stackTraceToString()` repeats the exception
 * message as its first line and spells every frame with the full on-device bundle path and column
 * padding, so a long message (Compose's infinite constraints error is ~900 characters, recorded
 * twice) left only three frames (Hands f36226a4) and a short one about ten (cf6307dd). The reason
 * is clipped, the stack keeps only frames and `Caused by` lines, and each frame is compacted.
 *
 * The line used to be clipped at 2048 characters like every other diagnostics line, which still
 * cut a Compose layout crash (b692fce7, 50046806: ~40 of its frames, all Compose internals) before
 * the frames that say which layout threw. The native recorder now gives this one line
 * `RaftDiagnosticsExceptionMessageMaxChars` (16384, `HRBridgeModule.m`): it is written to the
 * local diagnostics log that a Hands crash report attaches as a zip file, not sent as a field. A
 * stack longer than [RaftUnhandledExceptionStackMaxChars] keeps its head (where it threw) and its
 * tail (what called in), with the number of lines left out between them.
 */
internal class RaftUnhandledExceptionRecord(val reason: String, val stack: String)

internal const val RaftUnhandledExceptionReasonMaxChars = 300

/**
 * The compacted stack's budget: under the native recorder's `RaftDiagnosticsExceptionStackMaxChars`
 * (14336) and, with the reason and the line's prefix, its 16384-character line. About 280 frames.
 */
internal const val RaftUnhandledExceptionStackMaxChars = 12000

/** Of [RaftUnhandledExceptionStackMaxChars], what the head keeps; the tail gets the rest. */
private const val RaftUnhandledExceptionStackHeadShare = 0.7
private const val RaftUnhandledExceptionCauseMaxChars = 200
private const val RaftUnhandledExceptionSymbolMaxChars = 64

private val RaftWhitespaceRun = Regex("\\s+")
// `/private/var/containers/Bundle/Application/<UUID>/iosApp.app/iosApp` -> `iosApp`.
private val RaftIosBundlePath = Regex("/private/var/containers/Bundle/Application/[0-9A-Fa-f-]+/[^/ ]+\\.app/")
// `at 0 iosApp 0x10a03f9db <symbol> + 6337751`
private val RaftNativeFrame = Regex("^at (\\d+) (\\S+) (0x[0-9A-Fa-f]+) (.+) \\+ (\\d+)$")

/** [type]: the exception's qualified class name (the caller's platform knows it). */
internal fun raftUnhandledExceptionRecord(throwable: Throwable, type: String): RaftUnhandledExceptionRecord {
    val name = type.ifEmpty { "Throwable" }
    val reason = clip(name + (throwable.message?.let { ": $it" } ?: ""), RaftUnhandledExceptionReasonMaxChars)
    return RaftUnhandledExceptionRecord(
        reason = reason,
        stack = raftCompactUnhandledExceptionStack(throwable.stackTraceToString(), throwable.toString()),
    )
}

/**
 * [stack] without its leading [header] (the exception's `toString()`, already in the reason), one
 * compacted frame per line; `Caused by` lines are kept, clipped.
 */
internal fun raftCompactUnhandledExceptionStack(stack: String, header: String): String {
    val body = if (header.isNotEmpty() && stack.startsWith(header)) stack.substring(header.length) else stack
    return body
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            if (line.startsWith("at ")) {
                compactFrame(line)
            } else {
                // A `Caused by: …` header, or a message line that did not match the header.
                clip(line.replace(RaftWhitespaceRun, " "), RaftUnhandledExceptionCauseMaxChars)
            }
        }
        .toList()
        .let(::keepHeadAndTail)
        .joinToString("\n")
}

/**
 * [lines] within [RaftUnhandledExceptionStackMaxChars] (with their newlines): all of them when they
 * fit, else the first lines up to the head's share, a `… N lines elided …` line and the last lines
 * that fit after it.
 */
private fun keepHeadAndTail(lines: List<String>): List<String> {
    val total = lines.sumOf { it.length + 1 } - 1
    if (total <= RaftUnhandledExceptionStackMaxChars) return lines
    // The marker's own room, with digits to spare for the count.
    val budget = RaftUnhandledExceptionStackMaxChars - "… 000000 lines elided …".length - 1
    val headBudget = (budget * RaftUnhandledExceptionStackHeadShare).toInt()
    var headEnd = 0
    var used = 0
    while (headEnd < lines.size && used + lines[headEnd].length + 1 <= headBudget) {
        used += lines[headEnd].length + 1
        headEnd++
    }
    var tailStart = lines.size
    while (tailStart > headEnd && used + lines[tailStart - 1].length + 1 <= budget) {
        used += lines[tailStart - 1].length + 1
        tailStart--
    }
    val elided = tailStart - headEnd
    return lines.subList(0, headEnd) + "… $elided lines elided …" + lines.subList(tailStart, lines.size)
}

private fun compactFrame(line: String): String {
    val compact = line.replace(RaftWhitespaceRun, " ").replace(RaftIosBundlePath, "")
    val native = RaftNativeFrame.matchEntire(compact) ?: return compact
    val (index, image, address, symbol, offset) = native.destructured
    // A release build's symbol is often the nearest exported one (a long mangled name that says
    // nothing); the address alone symbolicates with the dSYM.
    return if (symbol.length > RaftUnhandledExceptionSymbolMaxChars) {
        "at $index $image $address"
    } else {
        "at $index $image $address $symbol + $offset"
    }
}

private fun clip(text: String, maxChars: Int): String =
    if (text.length <= maxChars) text else text.take(maxChars - 1) + "…"
