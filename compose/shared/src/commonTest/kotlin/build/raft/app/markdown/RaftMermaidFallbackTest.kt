package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #2539: the fence language is trimmed, and every mermaid fallback names its reason. */
class RaftMermaidFallbackTest {
    @Test
    fun fenceLanguageIsTrimmedSoWhitespaceAndCrlfStillSelectMermaid() {
        val cases =
            mapOf(
                "mermaid" to "mermaid",
                "mermaid " to "mermaid",
                " mermaid" to "mermaid",
                "mermaid\r" to "mermaid",
                "Mermaid\t\r" to "Mermaid",
                "kotlin \r" to "kotlin",
            )
        for ((info, expected) in cases) {
            assertEquals(expected, raftMarkdownFenceLanguage(info), "info=${info.encodeForMessage()}")
        }
        for (info in listOf("mermaid", "mermaid ", " mermaid", "mermaid\r", "MERMAID\r\n")) {
            assertTrue(raftMarkdownIsMermaidFence(info), "info=${info.encodeForMessage()}")
        }
    }

    @Test
    fun blankOrOtherFenceLanguageIsNotMermaid() {
        for (info in listOf(null, "", "  ", "\r")) {
            assertNull(raftMarkdownFenceLanguage(info), "info=${info?.encodeForMessage()}")
            assertFalse(raftMarkdownIsMermaidFence(info))
        }
        for (info in listOf("kotlin", "mermaidx", "mer maid")) {
            assertFalse(raftMarkdownIsMermaidFence(info), "info=$info")
        }
    }

    @Test
    fun throwReasonSeparatesClassInitFailureFromAPerInputThrow() {
        val cases =
            mapOf(
                "ExceptionInInitializerError" to RaftMermaidFallbackReason.ClassInitFailed,
                "NoClassDefFoundError" to RaftMermaidFallbackReason.ClassInitFailed,
                "IllegalStateException" to RaftMermaidFallbackReason.LayoutThrew,
                "PatternSyntaxException" to RaftMermaidFallbackReason.LayoutThrew,
                null to RaftMermaidFallbackReason.LayoutThrew,
            )
        for ((errorType, expected) in cases) {
            assertEquals(expected, raftMermaidThrowReason(errorType), "errorType=$errorType")
        }
    }

    @Test
    fun unsupportedSyntaxFallsBackAsParseFailure() {
        val code = "graph INVALID_SYNTAX\n    ??? --- !!!"
        val result = assertIs<RaftMermaidLayoutResult.Fallback>(raftMermaidLayout(code))
        assertEquals(RaftMermaidFallbackReason.ParseFailure, result.reason)
        assertEquals(code.length, result.codeLength)
        assertNull(result.errorType)
        assertTrue((result.diagnosticCount ?: 0) > 0, "diagnosticCount=${result.diagnosticCount}")
    }

    @Test
    fun supportedDiagramRenders() {
        assertIs<RaftMermaidLayoutResult.Rendered>(raftMermaidLayout("graph TD\n    A[Start] --> B[End]"))
    }

    @Test
    fun logLineCarriesReasonAndErrorTypeButNeverTheSource() {
        val threw =
            RaftMermaidLayoutResult.Fallback(
                reason = RaftMermaidFallbackReason.ClassInitFailed,
                codeLength = 32,
                errorType = "ExceptionInInitializerError",
            )
        val line = raftMermaidFallbackLogLine(threw)
        assertTrue(line.startsWith("mermaid_fallback "), line)
        assertEquals(
            mapOf(
                "reason" to "class_init_failed",
                "language" to "mermaid",
                "codeLen" to "32",
                "error" to "ExceptionInInitializerError",
            ),
            line.logFields(),
        )

        val zero =
            RaftMermaidLayoutResult.Fallback(
                reason = RaftMermaidFallbackReason.ZeroDimensions,
                codeLength = 10,
                width = "0.0",
                height = "12.0",
            )
        val zeroFields = raftMermaidFallbackLogLine(zero).logFields()
        assertEquals("zero_dimensions", zeroFields["reason"])
        assertEquals("0.0", zeroFields["width"])
        assertEquals("12.0", zeroFields["height"])

        val source = "graph INVALID_SYNTAX\n    Payroll ??? --- !!!"
        val parsed = assertIs<RaftMermaidLayoutResult.Fallback>(raftMermaidLayout(source))
        assertEquals(
            setOf("reason", "language", "codeLen", "diagnostics"),
            raftMermaidFallbackLogLine(parsed).logFields().keys,
        )
        assertFalse(raftMermaidFallbackLogLine(parsed).contains("Payroll"))
    }

    private fun String.logFields(): Map<String, String> =
        split(' ').drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun String.encodeForMessage(): String = replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")
}
