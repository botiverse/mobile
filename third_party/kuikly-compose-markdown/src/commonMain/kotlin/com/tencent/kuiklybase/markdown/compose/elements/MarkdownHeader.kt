package com.tencent.kuiklybase.markdown.compose.elements

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import build.raft.app.markdown.RaftTextDirection
import build.raft.app.markdown.raftMarkdownBlockDirection
import build.raft.app.markdown.toComposeLayoutDirection
import build.raft.app.markdown.withRaftTextDirection
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

@Composable
fun MarkdownHeader(
    content: String,
    node: ASTNode,
    style: TextStyle,
    modifier: Modifier = Modifier,
    contentChildType: IElementType = MarkdownTokenTypes.ATX_CONTENT,
) {
    val blockDirection = remember(content, node) { raftMarkdownBlockDirection(content, node) }
    val resolvedLayoutDirection = blockDirection.toComposeLayoutDirection(LocalLayoutDirection.current)
    val resolvedModifier = if (blockDirection == RaftTextDirection.Rtl) modifier.fillMaxWidth() else modifier
    CompositionLocalProvider(LocalLayoutDirection provides resolvedLayoutDirection) {
        MarkdownText(
            modifier = resolvedModifier,
            content = content,
            node = node,
            style = style.withRaftTextDirection(blockDirection),
            contentChildType = contentChildType,
        )
    }
}
