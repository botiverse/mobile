package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun String.encodeForMessage(): String = replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")
}
