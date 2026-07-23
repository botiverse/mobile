package com.tencent.kuiklybase.markdown.compose.elements

import androidx.compose.runtime.Composable
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownTypography
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.buildAnnotatedString
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
    modifier: Modifier = Modifier.fillMaxWidth(),
    style: TextStyle = LocalMarkdownTypography.current.paragraph,
    annotatorSettings: AnnotatorSettings = annotatorSettings(),
) {
    // t6-a paragraph sub-phases: annotate = AnnotatedString build (plain
    // block, measured via the timing's clock), textCall = MarkdownText
    // composable call (sequential enter/exit). Null timing = untimed path.
    val nodeTiming = LocalSlockMarkdownRenderTiming.current
    val styledText =
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
    slockMarkdownLogSubTrace(
        "phase=markdown.paragraph.annotated contentHash=${content.hashCode().toString(16)} " +
            "nodeStart=${node.startOffset} nodeEnd=${node.endOffset} textLength=${styledText.text.length} " +
            "spanStyles=${styledText.spanStyles.size} stringAnnotations=${styledText.getStringAnnotations(0, styledText.length).size} " +
            "linkAnnotations=${styledText.getLinkAnnotations(0, styledText.length).size}"
    )

    // 套个娃，解决placeholder span位置不对的问题
    Column(modifier = Modifier.fillMaxWidth()) {
        nodeTiming?.enterParagraphText()
        MarkdownText(
            styledText,
            modifier = modifier,
            style = style,
        )
        nodeTiming?.exitParagraphText()
    }
}
