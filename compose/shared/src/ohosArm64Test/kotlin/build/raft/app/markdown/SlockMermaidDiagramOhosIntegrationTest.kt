package build.raft.app.markdown

import build.raft.mermaid.core.MermaidParseResult
import build.raft.mermaid.core.MermaidParser
import build.raft.mermaid.layout.LayoutConfig
import build.raft.mermaid.layout.simple.FixedWidthTextMeasurer
import build.raft.mermaid.layout.simple.SimpleMermaidLayout
import kotlin.test.Test
import kotlin.test.assertTrue

class SlockMermaidDiagramOhosIntegrationTest {

    @Test
    fun testValidFlowchartParsesAndLayoutsForMarkdown() {
        val mermaidSource = """
            graph TD
                A[Start] --> B[Process]
                B --> C[End]
        """.trimIndent()

        val parseResult = MermaidParser.parse(mermaidSource)
        assertTrue(parseResult is MermaidParseResult.Success)

        val layout = SimpleMermaidLayout.layout(
            diagram = parseResult.diagram,
            textMeasurer = FixedWidthTextMeasurer,
            config = LayoutConfig()
        )

        assertTrue(layout.width > 0.0)
        assertTrue(layout.height > 0.0)
        assertTrue(layout.commands.isNotEmpty())
    }

    @Test
    fun testInvalidMermaidFailsClosedGracefully() {
        val invalidMermaid = """
            graph INVALID_SYNTAX
                ??? --- !!!
        """.trimIndent()

        val parseResult = MermaidParser.parse(invalidMermaid)
        assertTrue(parseResult is MermaidParseResult.Failure)
        assertTrue(parseResult.diagnostics.isNotEmpty())
    }

    @Test
    fun testSequenceDiagramParsesAndLayoutsForMarkdown() {
        val sequenceSource = """
            sequenceDiagram
                Alice->>Bob: Hello
                Bob-->>Alice: Hi
        """.trimIndent()

        val parseResult = MermaidParser.parse(sequenceSource)
        assertTrue(parseResult is MermaidParseResult.Success)

        val layout = SimpleMermaidLayout.layout(
            diagram = parseResult.diagram,
            textMeasurer = FixedWidthTextMeasurer,
            config = LayoutConfig()
        )

        assertTrue(layout.width > 0.0)
        assertTrue(layout.height > 0.0)
        assertTrue(layout.commands.size >= 2)
    }
}
