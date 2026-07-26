package build.raft.app.markdown

/**
 * task #441 — SLOCK_RICHTEXT_CAPS emission (the "emit" half of the caps loop;
 * the collect half is scripts/collect_richtext_caps.py, merged via PR #433).
 *
 * Shared code computes the FACTUAL per-platform renderer capabilities and
 * builds the single-line marker; the render host prints it (see the
 * LaunchedEffect in [SlockMarkdownBody]). Contract (locked v0.2, mirrored by
 * the collector):
 *   - one line: `SLOCK_RICHTEXT_CAPS {json}` (note the single trailing space
 *     after the prefix, before the JSON object);
 *   - capability fields are factual booleans — a missing marker means the
 *     caps are UNKNOWN, never a default, so platforms must not emit values
 *     they cannot back with a verified renderer behavior;
 *   - `declaredDivergences` is an array documenting intentional/known gaps
 *     for review and visual reports; it does not suppress visual diffs;
 *   - if multiple lines are printed (re-render), the LAST one wins.
 */
internal data class SlockRichTextPlatformCaps(
    /** Native renderer draws inline-code chrome from the wrapping span style. */
    val inlineCodeChrome: Boolean,
    /** Native renderer draws slock-ref chips (channel/thread/task/self-mention). */
    val slockRefChrome: Boolean,
    /** Reaction sprite atlas decodes into real sprites (not the emoji fallback). */
    val reactionAtlas: Boolean,
    val declaredDivergences: List<SlockRichTextDeclaredDivergence> = emptyList()
)

/**
 * Factual capabilities of THIS platform's renderer. Each actual documents the
 * evidence for its values; update them when the underlying renderer changes
 * (that flip is what turns the visual-testing caps badge without any manual
 * step — the whole point of #441).
 */
internal expect fun slockRichTextPlatformCaps(): SlockRichTextPlatformCaps

/** The complete marker line, ready to print to the platform console. */
internal fun slockRichTextCapsLine(): String =
    slockRichTextCapsLine(slockRichTextPlatformCaps())

internal fun slockRichTextCapsLine(caps: SlockRichTextPlatformCaps): String =
    buildString {
        append(SLOCK_RICHTEXT_CAPS)
        append(" {")
        append("\"inlineCodeChrome\":")
        append(caps.inlineCodeChrome)
        append(",\"slockRefChrome\":")
        append(caps.slockRefChrome)
        append(",\"reactionAtlas\":")
        append(caps.reactionAtlas)
        append(",\"declaredDivergences\":[")
        caps.declaredDivergences.forEachIndexed { index, divergence ->
            if (index > 0) append(",")
            append("{\"id\":")
            appendCapsJsonString(divergence.id)
            append(",\"description\":")
            appendCapsJsonString(divergence.description)
            append("}")
        }
        append("]}")
    }

private fun StringBuilder.appendCapsJsonString(value: String) {
    append("\"")
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
    append("\"")
}
