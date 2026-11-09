package build.raft.app

/**
 * What the iOS unhandled-exception hook records (`kuikly_unhandled_exception reason=… stack=…`).
 *
 * The record is one diagnostics line of at most 2048 characters. A Kotlin/Native
 * `stackTraceToString()` repeats the exception message as its first line and spells every frame
 * with the full on-device bundle path and column padding, so a long message (Compose's infinite
 * constraints error is ~900 characters, recorded twice) left only three frames (Hands f36226a4)
 * and a short one about ten (cf6307dd). The reason is clipped, the stack keeps only frames and
 * `Caused by` lines, and each frame is compacted, so the record holds enough frames to
 * symbolicate.
 */
internal class RaftUnhandledExceptionRecord(val reason: String, val stack: String)

internal const val RaftUnhandledExceptionReasonMaxChars = 300
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
        .joinToString("\n")
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
