package com.tencent.kuiklybase.markdown.compose.elements

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
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
