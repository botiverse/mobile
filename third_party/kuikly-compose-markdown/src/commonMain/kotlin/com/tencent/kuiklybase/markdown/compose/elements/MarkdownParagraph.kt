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

import org.intellij.markdown.ast.ASTNode

@Composable
fun MarkdownParagraph(
    content: String,
    node: ASTNode,
    modifier: Modifier = Modifier.fillMaxWidth(),
    style: TextStyle = LocalMarkdownTypography.current.paragraph,
    annotatorSettings: AnnotatorSettings = annotatorSettings(),
) {
    val styledText = buildAnnotatedString {
        pushStyle(style.toSpanStyle())
        buildMarkdownAnnotatedString(content = content, node = node, annotatorSettings = annotatorSettings)
        pop()
    }

    // 套个娃，解决placeholder span位置不对的问题
    Column(modifier = Modifier.fillMaxWidth()) {
        MarkdownText(
            styledText,
            modifier = modifier,
            style = style,
        )
    }
}
