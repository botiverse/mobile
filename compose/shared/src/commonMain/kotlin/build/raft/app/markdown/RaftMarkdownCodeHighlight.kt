package build.raft.app.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import build.raft.app.RaftAppThemeState
import build.raft.app.designtokens.RaftUiThemeColors
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.PhraseLocation
import dev.snipme.highlights.model.SyntaxLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Syntax colours for message code blocks (artin task #20, lane K).
 *
 * The web highlights with Shiki (`github-light` / `github-dark-high-contrast`); raft-ui has
 * no syntax tokens of its own. Mobile tokenizes with dev.snipme:highlights (Kotlin
 * Multiplatform, Apache-2.0), a lightweight keyword/string/comment/number scanner, and
 * paints each kind with a raft-ui semantic colour chosen to sit where the GitHub themes put
 * it, so light and dark both follow the design system:
 *
 * | kind       | GitHub theme (light / dark-hc) | raft-ui token              |
 * |------------|--------------------------------|----------------------------|
 * | keyword    | red     #D73A49 / #FF9492      | danger-strong              |
 * | string     | navy    #032F62 / #ADDCFF      | info-strong                |
 * | number     | blue    #005CC5 / #91CBFF      | foreground-link            |
 * | annotation | purple  #6F42C1 / #DBB7FF      | task-status-in-review-strong |
 * | comment    | grey    #6A737D / #BDC4CC      | foreground-hint            |
 *
 * Everything else keeps `code-foreground`. Exact parity with Shiki is not a goal.
 */

/** A highlighted token kind; later entries win where the scanner's ranges overlap. */
internal enum class RaftCodeTokenKind { NumberLiteral, Annotation, Keyword, StringLiteral, Comment }

/** [start] inclusive, [end] exclusive, in UTF-16 chars of the code. */
internal data class RaftCodeTokenRun(val kind: RaftCodeTokenKind, val start: Int, val end: Int)

/** Above either cap a block renders plain (the web's Shiki cap is 50 KB / 500 lines). */
internal const val RaftCodeHighlightMaxChars = 20_000
internal const val RaftCodeHighlightMaxLines = 500

/** Blocks up to this size are tokenized during composition; larger ones off the main thread. */
internal const val RaftCodeHighlightSyncMaxChars = 4_000

/**
 * The highlighter language for a fence's info string (` ```kotlin title="x" ` -> Kotlin),
 * or null to render plain: no info string, an unknown language, or one the scanner would
 * mis-colour (JSON, YAML, SQL… have no keyword table there).
 */
internal fun raftCodeSyntaxLanguage(info: String?): SyntaxLanguage? {
    val name =
        info
            ?.trim()
            ?.substringBefore(' ')
            ?.substringBefore('{')
            ?.lowercase()
            ?.removePrefix("language-")
            ?.takeIf { it.isNotEmpty() }
            ?: return null
    return when (name) {
        "kotlin", "kt", "kts" -> SyntaxLanguage.KOTLIN
        "java" -> SyntaxLanguage.JAVA
        "c", "h" -> SyntaxLanguage.C
        "cpp", "c++", "cc", "cxx", "hpp", "hh" -> SyntaxLanguage.CPP
        "cs", "csharp", "c#" -> SyntaxLanguage.CSHARP
        "dart" -> SyntaxLanguage.DART
        "rust", "rs" -> SyntaxLanguage.RUST
        "coffee", "coffeescript" -> SyntaxLanguage.COFFEESCRIPT
        "js", "javascript", "jsx", "mjs", "cjs" -> SyntaxLanguage.JAVASCRIPT
        "ts", "typescript", "tsx", "mts", "cts" -> SyntaxLanguage.TYPESCRIPT
        "py", "python", "python3" -> SyntaxLanguage.PYTHON
        "rb", "ruby" -> SyntaxLanguage.RUBY
        "sh", "bash", "shell", "shellscript", "zsh" -> SyntaxLanguage.SHELL
        "swift" -> SyntaxLanguage.SWIFT
        "go", "golang" -> SyntaxLanguage.GO
        "php" -> SyntaxLanguage.PHP
        "perl", "pl" -> SyntaxLanguage.PERL
        else -> null
    }
}

/** False for blocks past [RaftCodeHighlightMaxChars] / [RaftCodeHighlightMaxLines]. */
internal fun raftCodeHighlightEligible(code: String): Boolean {
    if (code.isEmpty() || code.length > RaftCodeHighlightMaxChars) return false
    var lines = 1
    for (char in code) {
        if (char == '\n' && ++lines > RaftCodeHighlightMaxLines) return false
    }
    return true
}

/** The raft-ui colour for [kind] in [colors] (light or dark). */
internal fun raftCodeTokenColor(kind: RaftCodeTokenKind, colors: RaftUiThemeColors): Color =
    when (kind) {
        RaftCodeTokenKind.Keyword -> colors.dangerStrong
        RaftCodeTokenKind.StringLiteral -> colors.infoStrong
        RaftCodeTokenKind.NumberLiteral -> colors.foregroundLink
        RaftCodeTokenKind.Annotation -> colors.taskStatusInReviewStrong
        RaftCodeTokenKind.Comment -> colors.foregroundHint
    }

private val RaftHashCommentLanguages =
    setOf(SyntaxLanguage.PYTHON, SyntaxLanguage.SHELL, SyntaxLanguage.RUBY, SyntaxLanguage.PERL, SyntaxLanguage.COFFEESCRIPT)

/**
 * Non-overlapping, ordered token runs for [code]; empty when nothing is highlighted or the
 * scanner fails. Pure, so it may run on any thread.
 *
 * The scanner treats both `//` and `#` as line comments in every language and does not see
 * that a comment marker sits inside a string; those comments are dropped here, so
 * `#include` or `"https://…"` stay plain instead of turning grey.
 */
internal fun raftCodeTokenRuns(code: String, language: SyntaxLanguage): List<RaftCodeTokenRun> {
    if (code.isEmpty()) return emptyList()
    val structure =
        runCatching { Highlights.Builder(code = code, language = language).build().getCodeStructure() }
            .getOrNull() ?: return emptyList()
    val hashComments = language in RaftHashCommentLanguages || language == SyntaxLanguage.PHP
    val slashComments = language !in RaftHashCommentLanguages
    fun keepComment(location: PhraseLocation): Boolean {
        val start = location.start
        val isHash = code.startsWith("#", start)
        val allowed = if (isHash) hashComments else slashComments
        return allowed && !code.isInsideStringOnLine(start)
    }
    val kinds = arrayOfNulls<RaftCodeTokenKind>(code.length)
    fun paint(locations: Collection<PhraseLocation>, kind: RaftCodeTokenKind) {
        for (location in locations) {
            val start = location.start.coerceIn(0, code.length)
            val end = location.end.coerceIn(start, code.length)
            for (index in start until end) kinds[index] = kind
        }
    }
    // Lowest precedence first: a number or keyword inside a string or comment takes its colour.
    paint(structure.literals, RaftCodeTokenKind.NumberLiteral)
    paint(structure.annotations, RaftCodeTokenKind.Annotation)
    paint(structure.keywords, RaftCodeTokenKind.Keyword)
    paint(structure.strings, RaftCodeTokenKind.StringLiteral)
    paint(structure.comments.filter(::keepComment), RaftCodeTokenKind.Comment)
    if (slashComments) paint(structure.multilineComments, RaftCodeTokenKind.Comment)

    val runs = ArrayList<RaftCodeTokenRun>()
    var index = 0
    while (index < kinds.size) {
        val kind = kinds[index]
        if (kind == null) {
            index++
            continue
        }
        var end = index + 1
        while (end < kinds.size && kinds[end] == kind) end++
        runs += RaftCodeTokenRun(kind, index, end)
        index = end
    }
    return runs
}

/** True when an unclosed `"`, `'` or `` ` `` precedes [index] on its line. */
private fun String.isInsideStringOnLine(index: Int): Boolean {
    var quote: Char? = null
    var i = lastIndexOf('\n', index - 1) + 1
    while (i < index) {
        val char = this[i]
        when {
            quote != null && char == '\\' -> i++
            quote != null && char == quote -> quote = null
            quote == null && (char == '"' || char == '\'' || char == '`') -> quote = char
        }
        i++
    }
    return quote != null
}

/** [code] with each run painted in its raft-ui colour; the rest inherits the text colour. */
internal fun raftHighlightedCode(
    code: String,
    runs: List<RaftCodeTokenRun>,
    colors: RaftUiThemeColors,
): AnnotatedString =
    buildAnnotatedString {
        append(code)
        for (run in runs) {
            if (run.start < 0 || run.end > code.length || run.start >= run.end) continue
            addStyle(SpanStyle(color = raftCodeTokenColor(run.kind, colors)), run.start, run.end)
        }
    }

/**
 * Recently tokenized blocks, keyed by language and code, so a message list scrolling back
 * over a block does not tokenize it again. Read and written on the main thread only.
 */
internal object RaftCodeTokenCache {
    private const val MaxEntries = 64
    private val entries = LinkedHashMap<Pair<SyntaxLanguage, String>, List<RaftCodeTokenRun>>()

    fun get(language: SyntaxLanguage, code: String): List<RaftCodeTokenRun>? {
        val key = language to code
        val runs = entries.remove(key) ?: return null
        entries[key] = runs
        return runs
    }

    fun put(language: SyntaxLanguage, code: String, runs: List<RaftCodeTokenRun>) {
        entries.remove(language to code)
        entries[language to code] = runs
        while (entries.size > MaxEntries) entries.remove(entries.keys.first())
    }

    fun clear() = entries.clear()
}

/**
 * [code] highlighted for the fence's [language] in the current theme, or null to render it
 * plain (unknown language, a block past the size caps, or — briefly — a large block still
 * being tokenized off the main thread).
 */
@Composable
internal fun rememberRaftHighlightedCode(code: String, language: String?): AnnotatedString? {
    val syntax = remember(language) { raftCodeSyntaxLanguage(language) } ?: return null
    if (!raftCodeHighlightEligible(code)) return null
    val runs =
        key(syntax, code) {
            val immediate =
                remember {
                    RaftCodeTokenCache.get(syntax, code)
                        ?: if (code.length <= RaftCodeHighlightSyncMaxChars) {
                            raftCodeTokenRuns(code, syntax).also { RaftCodeTokenCache.put(syntax, code, it) }
                        } else {
                            null
                        }
                }
            val produced by produceState(initialValue = immediate) {
                if (value == null) {
                    val computed = withContext(Dispatchers.Default) { raftCodeTokenRuns(code, syntax) }
                    RaftCodeTokenCache.put(syntax, code, computed)
                    value = computed
                }
            }
            produced
        } ?: return null
    if (runs.isEmpty()) return null
    val colors = RaftAppThemeState.colors
    return remember(code, runs, colors) { raftHighlightedCode(code, runs, colors) }
}
