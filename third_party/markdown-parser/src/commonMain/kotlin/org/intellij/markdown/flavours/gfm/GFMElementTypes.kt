package org.intellij.markdown.flavours.gfm

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementType
import kotlin.jvm.JvmField

object GFMTokenTypes {
    @JvmField
    val TILDE: IElementType = MarkdownElementType("~", true)

    @JvmField
    val TABLE_SEPARATOR: IElementType = MarkdownElementType("TABLE_SEPARATOR", true)

    @JvmField
    val GFM_AUTOLINK: IElementType = MarkdownElementType("GFM_AUTOLINK", true)

    @JvmField
    val CHECK_BOX: IElementType = MarkdownElementType("CHECK_BOX", true)

    @JvmField
    val CELL: IElementType = MarkdownElementType("CELL", true)

    @JvmField
    val DOLLAR: IElementType = MarkdownElementType("DOLLAR", true)

    /** The opening `$$` line of a [GFMElementTypes.BLOCK_MATH] (Raft). */
    @JvmField
    val MATH_FENCE_START: IElementType = MarkdownElementType("MATH_FENCE_START", true)

    /** One formula line of a [GFMElementTypes.BLOCK_MATH], fence indent removed (Raft). */
    @JvmField
    val MATH_FENCE_CONTENT: IElementType = MarkdownElementType("MATH_FENCE_CONTENT", true)

    /** The closing `$$` line of a [GFMElementTypes.BLOCK_MATH] (Raft). */
    @JvmField
    val MATH_FENCE_END: IElementType = MarkdownElementType("MATH_FENCE_END", true)

    @JvmField
    val ALERT_TITLE: IElementType = MarkdownElementType("ALERT_TITLE", true)
}

object  GFMElementTypes {
    @JvmField
    val STRIKETHROUGH: IElementType = MarkdownElementType("STRIKETHROUGH")

    @JvmField
    val TABLE: IElementType = MarkdownElementType("TABLE")

    @JvmField
    val HEADER: IElementType = MarkdownElementType("HEADER")

    @JvmField
    val ROW: IElementType = MarkdownElementType("ROW")

    @JvmField
    val INLINE_MATH: IElementType = MarkdownElementType("INLINE_MATH")

    @JvmField
    val BLOCK_MATH: IElementType = MarkdownElementType("BLOCK_MATH")

    @JvmField
    val ALERT: IElementType = MarkdownElementType("ALERT")
}
