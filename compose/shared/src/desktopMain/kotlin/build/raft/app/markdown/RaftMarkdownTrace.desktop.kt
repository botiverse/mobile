package build.raft.app.markdown

internal actual fun traceBegin(name: String) = Unit

internal actual fun traceEnd() = Unit

internal actual fun raftTraceDiagnosticsEnabled(): Boolean = false

internal actual fun raftNanoTimeNanos(): Long = System.nanoTime()

internal actual fun logMarkdownSpike(
    phase: String,
    durationUs: Long,
    bodyLength: Int,
    extra: () -> String,
) = Unit

internal actual fun raftRichTextPlatformCaps(): RaftRichTextPlatformCaps =
    RaftRichTextPlatformCaps(
        inlineCodeChrome = true,
        raftRefChrome = true,
        reactionAtlas = true,
    )

/** Skia: breaks around placeholders, letter-spaces a lone word joiner. */
internal actual fun raftPlatformInlineSpacerIsPlaceholder(): Boolean = false
