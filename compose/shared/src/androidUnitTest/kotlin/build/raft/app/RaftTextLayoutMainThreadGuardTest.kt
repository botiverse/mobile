package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Text layout stays on the main thread. Compose Multiplatform's `ui-text` keeps process-wide
 * caches in plain HashMaps (JetBrains CMP-10613), so measuring, drawing or resolving fonts for
 * text on a background thread while the UI lays out its own text can corrupt them and crash
 * (seen on iOS). Heavy pixel work may still go off the main thread; only the text step may not.
 *
 * Structure check: inside every block that runs on a background context (`withContext`,
 * `launch` or `async` given `Dispatchers.Default` / `Dispatchers.IO` / a `rasterContext`), no
 * call to a Compose text API, and no call to a function of ours that takes a text measurer or
 * font resolver as a parameter (those functions are found from their declarations).
 */
class RaftTextLayoutMainThreadGuardTest {
    @Test
    fun noTextLayoutInBackgroundBlocks() {
        val files =
            SourceRoots.map(::repositoryFile)
                .filter { it.isDirectory }
                .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue(files.size > 100, "sources not found under $SourceRoots")
        val sources = files.associateWith { stripComments(it.readText()) }
        val textHelpers = textHelperNames(sources.values)
        // The export's text step must stay a helper of its own (taking the measurer), so the
        // check below can see where it is called.
        assertTrue("drawRaftComposerImageExportText" in textHelpers, "text helpers found: $textHelpers")
        assertTrue("renderRaftComposerImageExportBase" !in textHelpers, "the export's off-main part takes no text")

        val offenders =
            sources.flatMap { (file, source) ->
                backgroundTextCalls(source, textHelpers).map { (line, call) -> "${file.path.substringAfter("compose/")}:$line calls $call" }
            }
        if (offenders.isNotEmpty()) {
            fail(
                "Text is laid out on a background thread (CMP-10613). Keep the heavy work there and move only " +
                    "the text measure/draw onto the main thread:\n" + offenders.joinToString("\n")
            )
        }
    }

    @Test
    fun guardCatchesTextInABackgroundBlock() {
        val source =
            stripComments(
                """
                internal fun renderWithText(source: ImageBitmap, text: RaftComposerImageExportText?): ImageBitmap? = null
                suspend fun save() {
                    val base = withContext(Dispatchers.Default) { renderBase(source) } // renderWithText( in a comment
                    withContext(Dispatchers.Main) { renderWithText(source, text) }
                    withContext(Dispatchers.Default) {
                        if (ready) { renderWithText(source, text) }
                    }
                    scope.launch(rasterContext) { textMeasurer.measure("x") }
                }
                """.trimIndent()
            )
        val helpers = textHelperNames(listOf(source))
        assertEquals(setOf("renderWithText"), helpers)
        assertEquals(
            listOf(6 to "renderWithText", 8 to "measure"),
            backgroundTextCalls(source, helpers),
        )
    }

    private fun repositoryFile(relativePath: String): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        return generateSequence(start) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.exists() }
            ?: File(start, relativePath)
    }

    private companion object {
        val SourceRoots =
            listOf(
                "compose/shared/src/commonMain/kotlin",
                "compose/shared/src/iosMain/kotlin",
                "compose/shared/src/nativeMain/kotlin",
                "compose/shared/src/jvmCommonMain/kotlin",
                "compose/shared/src/desktopMain/kotlin",
                "compose/shared/src/androidMain/kotlin",
                "compose/designsystem/src/commonMain/kotlin",
            )

        /** Compose text entry points that lay out, draw or resolve fonts. */
        val ComposeTextApis =
            setOf(
                "TextMeasurer",
                "rememberTextMeasurer",
                "drawText",
                "Paragraph",
                "MultiParagraph",
                "ParagraphIntrinsics",
                "createFontFamilyResolver",
                "resolveAsTypeface",
            )

        /** Parameter types that mean "this function lays out text". */
        val TextParameterTypes = Regex("""\b(TextMeasurer|RaftComposerImageExportText|FontFamily\.Resolver)\b""")

        val BackgroundContext = Regex("""Dispatchers\.(Default|IO)\b|\b\w*[rR]asterContext\b""")

        val BlockStart = Regex("""\b(?:withContext|launch|async)\s*\(([^(){}]*)\)\s*\{""")

        val FunctionDeclaration = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?(\w+)\s*\(""")

        val Call = Regex("""(?<![\w.])(\w+)\s*\(""")

        /** `measurer.measure(` / `textMeasurer.measure(`. */
        val MeasurerMeasure = Regex("""\b\w*[mM]easurer\s*\.\s*(measure)\s*\(""")

        /**
         * [source] with comments and string / char literal contents blanked (newlines kept, so
         * offsets and line numbers stay), leaving only code for the brace and call matching.
         */
        fun stripComments(source: String): String {
            val out = StringBuilder(source.length)
            var index = 0
            fun blank(until: Int) {
                while (index < until && index < source.length) {
                    out.append(if (source[index] == '\n') '\n' else ' ')
                    index++
                }
            }
            while (index < source.length) {
                when {
                    source.startsWith("//", index) -> blank(source.indexOf('\n', index).let { if (it < 0) source.length else it })
                    source.startsWith("/*", index) -> {
                        // Kotlin block comments nest.
                        var depth = 0
                        var end = index
                        while (end < source.length) {
                            if (source.startsWith("/*", end)) {
                                depth++
                                end += 2
                            } else if (source.startsWith("*/", end)) {
                                end += 2
                                if (--depth == 0) break
                            } else {
                                end++
                            }
                        }
                        blank(end)
                    }
                    source.startsWith("\"\"\"", index) -> {
                        val end = source.indexOf("\"\"\"", index + 3).let { if (it < 0) source.length else it + 3 }
                        out.append("\"\"\"")
                        index += 3
                        blank(end - 3)
                        out.append("\"\"\"")
                        index = end
                    }
                    source[index] == '"' || source[index] == '\'' -> {
                        val quote = source[index]
                        var end = index + 1
                        while (end < source.length && source[end] != quote && source[end] != '\n') {
                            end += if (source[end] == '\\') 2 else 1
                        }
                        out.append(quote)
                        index++
                        blank(end.coerceAtMost(source.length))
                        if (index < source.length && source[index] == quote) {
                            out.append(quote)
                            index++
                        }
                    }
                    else -> {
                        out.append(source[index])
                        index++
                    }
                }
            }
            return out.toString()
        }

        /** Names of the functions whose parameter list takes a text measurer or font resolver. */
        fun textHelperNames(sources: Collection<String>): Set<String> =
            sources.flatMapTo(sortedSetOf()) { source ->
                FunctionDeclaration.findAll(source).mapNotNull { match ->
                    val open = match.range.last
                    val close = matching(source, open, '(', ')') ?: return@mapNotNull null
                    match.groupValues[1].takeIf { TextParameterTypes.containsMatchIn(source.substring(open, close)) }
                }.toList()
            }

        /** (line, call) of every text call inside a block that runs on a background context. */
        fun backgroundTextCalls(source: String, textHelpers: Set<String>): List<Pair<Int, String>> =
            BlockStart.findAll(source)
                .filter { BackgroundContext.containsMatchIn(it.groupValues[1]) }
                .flatMap { start ->
                    val open = start.range.last
                    val close = matching(source, open, '{', '}') ?: source.length
                    val body = source.substring(open, close)
                    val calls =
                        Call.findAll(body)
                            .filter { it.groupValues[1] in ComposeTextApis || it.groupValues[1] in textHelpers }
                            .map { it.range.first to it.groupValues[1] } +
                            MeasurerMeasure.findAll(body).map { it.groups[1]!!.range.first to "measure" }
                    calls.map { (offset, name) -> lineOf(source, open + offset) to name }
                }
                .distinct()
                .sortedBy { it.first }
                .toList()

        fun matching(source: String, open: Int, opening: Char, closing: Char): Int? {
            var depth = 0
            for (index in open until source.length) {
                when (source[index]) {
                    opening -> depth++
                    closing -> if (--depth == 0) return index
                }
            }
            return null
        }

        fun lineOf(source: String, offset: Int): Int = source.substring(0, offset).count { it == '\n' } + 1
    }
}
