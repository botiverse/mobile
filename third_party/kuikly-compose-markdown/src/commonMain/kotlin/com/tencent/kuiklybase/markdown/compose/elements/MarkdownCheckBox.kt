package com.tencent.kuiklybase.markdown.compose.elements

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.wrapContentWidth
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuiklybase.markdown.compose.elements.material.MarkdownBasicText
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode

@Composable
fun MarkdownCheckBox(
    content: String,
    node: ASTNode,
    style: TextStyle,
    checkedIndicator: @Composable (Boolean, Modifier) -> Unit = { checked, modifier ->
        MarkdownBasicText(
            text = "[${if (checked) "x" else " "}] ",
            modifier = modifier,
            style = style
        )
    },
) {
    val checked = node.getTextInNode(content).contains("[x]")
    Row(modifier = Modifier.wrapContentWidth()) {
        checkedIndicator(checked, Modifier.wrapContentWidth().padding(end = 4.dp))
    }
}
