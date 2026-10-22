package com.tencent.kuiklybase.markdown.model

import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * The TeX of an INLINE_MATH node, as mdast-util-math gives the web's
 * `inlineMath` value (botiverse/slock #10813): the text between the dollar
 * runs, block-quote markers of continuation lines dropped, and one space (or
 * line ending) of padding removed from each side when both sides have it and
 * the formula is not only padding.
 */
fun raftInlineMathSource(node: ASTNode, content: CharSequence): String {
    val children = node.children
    if (children.size < 2) return ""
    val source = StringBuilder()
    var skipWhitespace = false
    for (index in 1 until children.size - 1) {
        val child = children[index]
        when {
            child.type == MarkdownTokenTypes.BLOCK_QUOTE -> skipWhitespace = true
            skipWhitespace && child.type == MarkdownTokenTypes.WHITE_SPACE -> skipWhitespace = false
            else -> {
                skipWhitespace = false
                source.append(content, child.startOffset, child.endOffset)
            }
        }
    }
    if (source.length >= 2 &&
        source.first().isMathPadding() &&
        source.last().isMathPadding() &&
        !source.all { it.isMathPadding() }
    ) {
        return source.substring(1, source.length - 1)
    }
    return source.toString()
}

/**
 * The TeX of a BLOCK_MATH node (`$$` fences on their own lines), as
 * mdast-util-math gives the web's `math` value: the formula lines, fence
 * indent removed, joined with line feeds.
 */
fun raftBlockMathSource(node: ASTNode, content: CharSequence): String {
    val source = StringBuilder()
    var started = false
    for (child in node.children) {
        when (child.type) {
            GFMTokenTypes.MATH_FENCE_START -> Unit
            GFMTokenTypes.MATH_FENCE_END -> break
            MarkdownTokenTypes.EOL ->
                if (started) source.append('\n') else started = true
            GFMTokenTypes.MATH_FENCE_CONTENT -> {
                started = true
                source.append(content, child.startOffset, child.endOffset)
            }
        }
    }
    // The line ending before the closing fence is not part of the formula.
    if (source.endsWith("\n")) source.setLength(source.length - 1)
    return source.toString().trimEnd('\r')
}

private fun Char.isMathPadding(): Boolean = this == ' ' || this == '\n' || this == '\r'
