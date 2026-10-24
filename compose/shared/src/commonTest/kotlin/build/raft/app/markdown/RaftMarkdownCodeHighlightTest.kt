package build.raft.app.markdown

import build.raft.app.designtokens.RaftUiThemeColors
import dev.snipme.highlights.model.SyntaxLanguage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftMarkdownCodeHighlightTest {
    @AfterTest
    fun clearCache() = RaftCodeTokenCache.clear()

    private fun kindAt(code: String, runs: List<RaftCodeTokenRun>, index: Int): RaftCodeTokenKind? =
        runs.firstOrNull { index >= it.start && index < it.end }?.kind

    private fun kindOf(code: String, runs: List<RaftCodeTokenRun>, phrase: String, occurrence: Int = 0): RaftCodeTokenKind? {
        var index = -1
        repeat(occurrence + 1) { index = code.indexOf(phrase, index + 1) }
        check(index >= 0) { "\"$phrase\" not in code" }
        return kindAt(code, runs, index)
    }

    // --- language from the fence's info string ---

    @Test
    fun infoStringMapsToLanguage() {
        assertEquals(SyntaxLanguage.KOTLIN, raftCodeSyntaxLanguage("kotlin"))
        assertEquals(SyntaxLanguage.KOTLIN, raftCodeSyntaxLanguage("KT"))
        assertEquals(SyntaxLanguage.PYTHON, raftCodeSyntaxLanguage("py title=\"x.py\""))
        assertEquals(SyntaxLanguage.TYPESCRIPT, raftCodeSyntaxLanguage("language-tsx"))
        assertEquals(SyntaxLanguage.JAVASCRIPT, raftCodeSyntaxLanguage(" js "))
        assertEquals(SyntaxLanguage.SHELL, raftCodeSyntaxLanguage("bash"))
        assertEquals(SyntaxLanguage.SHELL, raftCodeSyntaxLanguage("zsh"))
        assertEquals(SyntaxLanguage.CPP, raftCodeSyntaxLanguage("c++"))
        assertEquals(SyntaxLanguage.CSHARP, raftCodeSyntaxLanguage("c#"))
        assertEquals(SyntaxLanguage.GO, raftCodeSyntaxLanguage("golang"))
        assertEquals(SyntaxLanguage.RUST, raftCodeSyntaxLanguage("rs{.numberLines}"))
    }

    @Test
    fun missingOrUnknownLanguageIsPlain() {
        assertNull(raftCodeSyntaxLanguage(null))
        assertNull(raftCodeSyntaxLanguage(""))
        assertNull(raftCodeSyntaxLanguage("   "))
        assertNull(raftCodeSyntaxLanguage("text"))
        assertNull(raftCodeSyntaxLanguage("json"))
        assertNull(raftCodeSyntaxLanguage("yaml"))
        assertNull(raftCodeSyntaxLanguage("mermaid"))
        assertNull(raftCodeSyntaxLanguage("brainfuck"))
    }

    // --- size caps ---

    @Test
    fun largeBlocksFallBackToPlain() {
        assertTrue(raftCodeHighlightEligible("val x = 1"))
        assertFalse(raftCodeHighlightEligible(""))
        assertTrue(raftCodeHighlightEligible("a".repeat(RaftCodeHighlightMaxChars)))
        assertFalse(raftCodeHighlightEligible("a".repeat(RaftCodeHighlightMaxChars + 1)))
        assertTrue(raftCodeHighlightEligible("x\n".repeat(RaftCodeHighlightMaxLines - 1) + "x"))
        assertFalse(raftCodeHighlightEligible("x\n".repeat(RaftCodeHighlightMaxLines)))
    }

    // --- token -> colour ---

    @Test
    fun tokenKindsUseRaftUiColours() {
        for (colors in listOf(RaftUiThemeColors.RaftLight, RaftUiThemeColors.RaftDark)) {
            assertEquals(colors.dangerStrong, raftCodeTokenColor(RaftCodeTokenKind.Keyword, colors))
            assertEquals(colors.infoStrong, raftCodeTokenColor(RaftCodeTokenKind.StringLiteral, colors))
            assertEquals(colors.foregroundLink, raftCodeTokenColor(RaftCodeTokenKind.NumberLiteral, colors))
            assertEquals(colors.taskStatusInReviewStrong, raftCodeTokenColor(RaftCodeTokenKind.Annotation, colors))
            assertEquals(colors.foregroundHint, raftCodeTokenColor(RaftCodeTokenKind.Comment, colors))
        }
    }

    @Test
    fun tokenColoursAreDistinctFromEachOtherAndTheCodeText() {
        for (colors in listOf(RaftUiThemeColors.RaftLight, RaftUiThemeColors.RaftDark)) {
            val painted = RaftCodeTokenKind.entries.map { raftCodeTokenColor(it, colors) }
            assertEquals(painted.size, painted.toSet().size, "token colours collide: $painted")
            painted.forEach { assertNotEquals(colors.codeForeground, it) }
        }
        // Light and dark differ: every kind follows the theme.
        RaftCodeTokenKind.entries.forEach { kind ->
            assertNotEquals(
                raftCodeTokenColor(kind, RaftUiThemeColors.RaftLight),
                raftCodeTokenColor(kind, RaftUiThemeColors.RaftDark),
                "$kind",
            )
        }
    }

    // --- tokenizing ---

    @Test
    fun kotlinKeywordsStringsCommentsAndNumbers() {
        val code = "val greeting = \"hi\" // say hi\nfun answer() = 42"
        val runs = raftCodeTokenRuns(code, SyntaxLanguage.KOTLIN)
        assertEquals(RaftCodeTokenKind.Keyword, kindOf(code, runs, "val"))
        assertEquals(RaftCodeTokenKind.Keyword, kindOf(code, runs, "fun"))
        assertEquals(RaftCodeTokenKind.StringLiteral, kindOf(code, runs, "\"hi\""))
        assertEquals(RaftCodeTokenKind.Comment, kindOf(code, runs, "// say"))
        assertEquals(RaftCodeTokenKind.NumberLiteral, kindOf(code, runs, "42"))
        assertNull(kindOf(code, runs, "greeting"))
        assertNull(kindOf(code, runs, "answer"))
    }

    @Test
    fun runsAreOrderedAndDoNotOverlap() {
        val code = "@Suppress(\"x\")\nclass A { val n = 1 /* one */ }"
        val runs = raftCodeTokenRuns(code, SyntaxLanguage.KOTLIN)
        assertTrue(runs.isNotEmpty())
        runs.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start, "$a overlaps $b") }
        runs.forEach { assertTrue(it.start in 0 until it.end && it.end <= code.length, "$it") }
    }

    @Test
    fun aNumberInsideAStringTakesTheStringColour() {
        val code = "val s = \"v 42\""
        val runs = raftCodeTokenRuns(code, SyntaxLanguage.KOTLIN)
        assertEquals(RaftCodeTokenKind.StringLiteral, kindOf(code, runs, "42"))
    }

    @Test
    fun aCommentMarkerInsideAStringIsNotAComment() {
        val code = "val url = \"https://raft.build\""
        val runs = raftCodeTokenRuns(code, SyntaxLanguage.KOTLIN)
        assertEquals(RaftCodeTokenKind.StringLiteral, kindOf(code, runs, "//raft"))
        assertEquals(RaftCodeTokenKind.StringLiteral, kindOf(code, runs, "build"))
    }

    @Test
    fun aStringWithAMarkerDoesNotSwallowTheNextLine() {
        val code = "val color = \"#fff\"\nval glob = \"src/**/*.ts\"\nval name = \"a\" // real"
        val runs = raftCodeTokenRuns(code, SyntaxLanguage.KOTLIN)
        assertEquals(RaftCodeTokenKind.StringLiteral, kindOf(code, runs, "#fff"))
        assertEquals(RaftCodeTokenKind.StringLiteral, kindOf(code, runs, "/*.ts"))
        assertEquals(RaftCodeTokenKind.Keyword, kindOf(code, runs, "val", occurrence = 1))
        assertEquals(RaftCodeTokenKind.Keyword, kindOf(code, runs, "val", occurrence = 2))
        assertNull(kindOf(code, runs, "name"))
        assertEquals(RaftCodeTokenKind.Comment, kindOf(code, runs, "// real"))
    }

    @Test
    fun maskKeepsOffsetsAndBlanksOnlyMisreadMarkers() {
        val code = "a = \"http://x\" # note\nb = 7 // 2"
        val masked = raftMaskMisreadCommentMarkers(code, SyntaxLanguage.PYTHON)
        assertEquals(code.length, masked.length)
        assertEquals("a = \"http:  x\" # note\nb = 7    2", masked)
        val shell = "echo \${#arr} \$#  # n"
        assertEquals("echo \${ arr} \$   # n", raftMaskMisreadCommentMarkers(shell, SyntaxLanguage.SHELL))
    }

    @Test
    fun hashCommentsOnlyInHashLanguages() {
        val python = "x = 7 // 2  # floor\ny = 1"
        val pyRuns = raftCodeTokenRuns(python, SyntaxLanguage.PYTHON)
        assertNotEquals(RaftCodeTokenKind.Comment, kindOf(python, pyRuns, "// 2"))
        assertEquals(RaftCodeTokenKind.Comment, kindOf(python, pyRuns, "# floor"))

        val c = "#include <stdio.h>\nint main() { return 0; }"
        val cRuns = raftCodeTokenRuns(c, SyntaxLanguage.C)
        assertNotEquals(RaftCodeTokenKind.Comment, kindOf(c, cRuns, "#include"))
        assertEquals(RaftCodeTokenKind.Keyword, kindOf(c, cRuns, "return"))

        val floorDivision = "q = a // b if c else d"
        val floorRuns = raftCodeTokenRuns(floorDivision, SyntaxLanguage.PYTHON)
        assertEquals(RaftCodeTokenKind.Keyword, kindOf(floorDivision, floorRuns, "if"))
        assertEquals(RaftCodeTokenKind.Keyword, kindOf(floorDivision, floorRuns, "else"))

        val define = "#define DONE return 0"
        val defineRuns = raftCodeTokenRuns(define, SyntaxLanguage.C)
        assertEquals(RaftCodeTokenKind.Keyword, kindOf(define, defineRuns, "return"))
    }

    @Test
    fun emptyCodeHasNoRuns() {
        assertEquals(emptyList(), raftCodeTokenRuns("", SyntaxLanguage.KOTLIN))
    }

    // --- annotated string ---

    @Test
    fun highlightedCodeKeepsTheTextAndPaintsEachRun() {
        val code = "val x = 1"
        val runs =
            listOf(
                RaftCodeTokenRun(RaftCodeTokenKind.Keyword, 0, 3),
                RaftCodeTokenRun(RaftCodeTokenKind.NumberLiteral, 8, 9),
                // Out of range or empty: ignored rather than crashing.
                RaftCodeTokenRun(RaftCodeTokenKind.Comment, 8, 40),
                RaftCodeTokenRun(RaftCodeTokenKind.Comment, 5, 5),
            )
        val colors = RaftUiThemeColors.RaftDark
        val text = raftHighlightedCode(code, runs, colors)
        assertEquals(code, text.text)
        assertEquals(2, text.spanStyles.size)
        assertEquals(colors.dangerStrong, text.spanStyles[0].item.color)
        assertEquals(0, text.spanStyles[0].start)
        assertEquals(3, text.spanStyles[0].end)
        assertEquals(colors.foregroundLink, text.spanStyles[1].item.color)
    }

    // --- memo ---

    @Test
    fun cacheEvictsTheLeastRecentlyUsedBlock() {
        val runs = listOf(RaftCodeTokenRun(RaftCodeTokenKind.Keyword, 0, 3))
        RaftCodeTokenCache.put(SyntaxLanguage.KOTLIN, "first", runs)
        repeat(63) { RaftCodeTokenCache.put(SyntaxLanguage.KOTLIN, "block $it", runs) }
        // Touch "first" so "block 0" is now the oldest.
        assertEquals(runs, RaftCodeTokenCache.get(SyntaxLanguage.KOTLIN, "first"))
        RaftCodeTokenCache.put(SyntaxLanguage.KOTLIN, "one more", runs)
        assertEquals(runs, RaftCodeTokenCache.get(SyntaxLanguage.KOTLIN, "first"))
        assertNull(RaftCodeTokenCache.get(SyntaxLanguage.KOTLIN, "block 0"))
        // The language is part of the key.
        assertNull(RaftCodeTokenCache.get(SyntaxLanguage.PYTHON, "first"))
    }
}
