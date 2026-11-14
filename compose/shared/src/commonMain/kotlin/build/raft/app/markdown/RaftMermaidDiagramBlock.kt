package build.raft.app.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import build.raft.app.raftLogWarn
import build.raft.mermaid.core.MermaidParseResult
import build.raft.mermaid.core.MermaidParser
import build.raft.mermaid.layout.LayoutConfig
import build.raft.mermaid.layout.LayoutScene
import build.raft.mermaid.layout.simple.FixedWidthTextMeasurer
import build.raft.mermaid.layout.simple.SimpleMermaidLayout

/**
 * Renders a Mermaid diagram block.
 *
 * Parse → layout runs on the multiplatform `build.raft.mermaid` artifacts; the resulting
 * [LayoutScene] is drawn by the `mermaid-compose` library ([RaftMermaidViewport]) inside
 * [RaftMermaidCard], with its text in [diagramTextStyle] (the message body font).
 *
 * Contract: if Mermaid source parses and lays out, render the diagram with interactive zoom;
 * otherwise fall back to [fallback] and log one `mermaid_fallback` line with the reason (#2539).
 */
@Composable
internal fun RaftMermaidDiagramBlock(
    code: String,
    diagramTextStyle: TextStyle,
    fallback: @Composable () -> Unit
) {
    val result: RaftMermaidLayoutResult =
        remember(code) { raftMermaidLayout(code).also { if (it is RaftMermaidLayoutResult.Fallback) raftLogMermaidFallback(it) } }

    if (result is RaftMermaidLayoutResult.Rendered) {
        val layoutScene = result.scene
        RaftMermaidCard(
            code = code,
            diagramWidth = layoutScene.width.toFloat(),
            diagramHeight = layoutScene.height.toFloat(),
            codeContent = fallback,
            diagramContent = { fitScale, state, panAlways, modifier ->
                RaftMermaidViewport(
                    scene = layoutScene,
                    textStyle = diagramTextStyle,
                    fitScale = fitScale,
                    state = state,
                    panAlways = panAlways,
                    modifier = modifier
                )
            }
        )
    } else {
        fallback()
    }
}

/**
 * Why a mermaid fence fell back to a plain code block (#2539). [wireValue] is the `reason=` of
 * the `mermaid_fallback` diagnostics line.
 */
internal enum class RaftMermaidFallbackReason(val wireValue: String) {
    /** The parser rejected the source: normally unsupported syntax in the user's diagram. */
    ParseFailure("parse_failure"),

    /** Parse or layout threw: a library defect for this input. */
    LayoutThrew("layout_threw"),

    /**
     * The library's class failed to initialise (`ExceptionInInitializerError`, then
     * `NoClassDefFoundError` on every later touch): every diagram falls back for the rest of the
     * process, whatever its content.
     */
    ClassInitFailed("class_init_failed"),

    /** Layout succeeded but produced a scene with no area. */
    ZeroDimensions("zero_dimensions"),
}

internal sealed interface RaftMermaidLayoutResult {
    class Rendered(val scene: LayoutScene) : RaftMermaidLayoutResult

    /**
     * [errorType]: the throwable's class name for a throw, [width] x [height] for
     * [RaftMermaidFallbackReason.ZeroDimensions], [diagnosticCount] for a parse failure.
     * Never the diagram source: it is user content.
     */
    data class Fallback(
        val reason: RaftMermaidFallbackReason,
        val codeLength: Int,
        val errorType: String? = null,
        val diagnosticCount: Int? = null,
        val width: String? = null,
        val height: String? = null,
    ) : RaftMermaidLayoutResult
}

private val RaftMermaidClassInitErrorTypes = setOf("ExceptionInInitializerError", "NoClassDefFoundError")

/** The fallback reason for a throwable of class [errorType] (its simple name) out of parse/layout. */
internal fun raftMermaidThrowReason(errorType: String?): RaftMermaidFallbackReason =
    if (errorType in RaftMermaidClassInitErrorTypes) {
        RaftMermaidFallbackReason.ClassInitFailed
    } else {
        RaftMermaidFallbackReason.LayoutThrew
    }

internal fun raftMermaidLayout(code: String): RaftMermaidLayoutResult =
    try {
        val parseResult = MermaidParser.parse(code.trim())
        if (parseResult is MermaidParseResult.Success) {
            val scene =
                SimpleMermaidLayout.layout(
                    diagram = parseResult.diagram,
                    textMeasurer = FixedWidthTextMeasurer,
                    config = LayoutConfig()
                )
            if (scene.width > 0 && scene.height > 0) {
                RaftMermaidLayoutResult.Rendered(scene)
            } else {
                RaftMermaidLayoutResult.Fallback(
                    reason = RaftMermaidFallbackReason.ZeroDimensions,
                    codeLength = code.length,
                    width = scene.width.toString(),
                    height = scene.height.toString(),
                )
            }
        } else {
            RaftMermaidLayoutResult.Fallback(
                reason = RaftMermaidFallbackReason.ParseFailure,
                codeLength = code.length,
                diagnosticCount = (parseResult as? MermaidParseResult.Failure)?.diagnostics?.size,
            )
        }
    } catch (error: Throwable) {
        val errorType = error::class.simpleName
        RaftMermaidLayoutResult.Fallback(
            reason = raftMermaidThrowReason(errorType),
            codeLength = code.length,
            errorType = errorType ?: "unknown",
        )
    }

/** The diagnostics line for [fallback]: reason and error type only, never the diagram source. */
internal fun raftMermaidFallbackLogLine(fallback: RaftMermaidLayoutResult.Fallback): String =
    buildString {
        append("mermaid_fallback reason=").append(fallback.reason.wireValue)
        append(" language=mermaid codeLen=").append(fallback.codeLength)
        fallback.errorType?.let { append(" error=").append(it) }
        fallback.diagnosticCount?.let { append(" diagnostics=").append(it) }
        if (fallback.width != null || fallback.height != null) {
            append(" width=").append(fallback.width).append(" height=").append(fallback.height)
        }
    }

private fun raftLogMermaidFallback(fallback: RaftMermaidLayoutResult.Fallback) {
    // Warn: a product defect (throw, class init, zero size) must reach the feedback diagnostics
    // log; a parse failure says which of the four causes it was not.
    raftLogWarn("RaftMermaid", raftMermaidFallbackLogLine(fallback))
}
