package com.tencent.kuiklybase.markdown.compose.elements

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownTypography
import com.tencent.kuiklybase.markdown.compose.LocalSlockMarkdownAnnotationRevision
import com.tencent.kuiklybase.markdown.compose.LocalSlockMarkdownParagraphFillWidth
import com.tencent.kuiklybase.markdown.compose.slockMarkdownAnnotatedStringMemoKeys
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.tencent.kuiklybase.markdown.annotator.AnnotatorSettings
import com.tencent.kuiklybase.markdown.annotator.annotatorSettings
import com.tencent.kuiklybase.markdown.annotator.buildMarkdownAnnotatedString
import com.tencent.kuiklybase.markdown.compose.LocalSlockMarkdownRenderTiming
import com.tencent.kuiklybase.markdown.compose.slockMarkdownLogSubTrace

import org.intellij.markdown.ast.ASTNode

@Composable
fun MarkdownParagraph(
    content: String,
    node: ASTNode,
    // Slock (task #800): null resolves against LocalSlockMarkdownParagraphFillWidth
    // (default true = legacy fill). Right-aligned chat bubbles provide false so a
    // short paragraph wraps to its text and can hug the End edge; every other
    // caller keeps the legacy fill behavior untouched.
    modifier: Modifier? = null,
    style: TextStyle = LocalMarkdownTypography.current.paragraph,
    annotatorSettings: AnnotatorSettings = annotatorSettings(),
) {
    val paragraphFillWidth = LocalSlockMarkdownParagraphFillWidth.current
    val resolvedModifier = modifier ?: if (paragraphFillWidth) Modifier.fillMaxWidth() else Modifier
    // t6-a paragraph sub-phases: annotate = AnnotatedString build (plain
    // block, measured via the timing's clock), textCall = MarkdownText
    // composable call (sequential enter/exit). Null timing = untimed path.
    // Memoization is OPT-IN via the host annotation revision (see
    // LocalSlockMarkdownAnnotationRevision): without one, the legacy
    // rebuild-every-recomposition semantics are preserved because an
    // identity-stable annotator can still change its output. With one, the
    // build is remembered by its actual inputs + the revision, so unchanged
    // recompositions reuse the string and a revision change rebuilds exactly
    // once. Hits report annotate=0 (honest attribution: no work happened);
    // hit/rebuild counts are recorded ONLY on the memoized path, so a
    // legacy rebuild is never misreported as a memo rebuild.
    val nodeTiming = LocalSlockMarkdownRenderTiming.current
    val memoKeys = slockMarkdownAnnotatedStringMemoKeys(
        content, node, style, annotatorSettings, LocalSlockMarkdownAnnotationRevision.current
    )
    val buildStyledText = {
        if (nodeTiming != null) {
            nodeTiming.measureParagraphAnnotate {
                buildAnnotatedString {
                    pushStyle(style.toSpanStyle())
                    buildMarkdownAnnotatedString(content = content, node = node, annotatorSettings = annotatorSettings)
                    pop()
                }
            }
        } else {
            buildAnnotatedString {
                pushStyle(style.toSpanStyle())
                buildMarkdownAnnotatedString(content = content, node = node, annotatorSettings = annotatorSettings)
                pop()
            }
        }
    }
    val styledText =
        if (memoKeys == null) {
            buildStyledText()
        } else {
            var rebuilt = false
            val remembered = remember(*memoKeys) {
                rebuilt = true
                buildStyledText()
            }
            if (rebuilt) nodeTiming?.recordAnnotatedStringRebuild() else nodeTiming?.recordAnnotatedStringHit()
            remembered
        }
    slockMarkdownLogSubTrace {
        "phase=markdown.paragraph.annotated contentHash=${content.hashCode().toString(16)} " +
            "nodeStart=${node.startOffset} nodeEnd=${node.endOffset} textLength=${styledText.text.length} " +
            "spanStyles=${styledText.spanStyles.size} stringAnnotations=${styledText.getStringAnnotations(0, styledText.length).size} " +
            "linkAnnotations=${styledText.getLinkAnnotations(0, styledText.length).size}"
    }

    // 套个娃，解决placeholder span位置不对的问题
    Column(modifier = if (paragraphFillWidth) Modifier.fillMaxWidth() else Modifier) {
        nodeTiming?.enterParagraphText()
        MarkdownText(
            styledText,
            modifier = resolvedModifier,
            style = style,
        )
        nodeTiming?.exitParagraphText()
    }
}
