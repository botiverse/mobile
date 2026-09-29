package com.tencent.kuiklybase.markdown.compose.elements

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.BorderStroke
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.RowScope
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.shape.CircleShape
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.clip
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.isSpecified
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownComponents
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownPadding
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownTypography
import com.tencent.kuiklybase.markdown.compose.LocalOrderedListHandler
import com.tencent.kuiklybase.markdown.compose.handleElement
import com.tencent.kuiklybase.markdown.utils.getUnescapedTextInNode
import com.tencent.kuiklybase.markdown.compose.components.MarkdownComponentModel
import com.tencent.kuiklybase.markdown.compose.elements.material.MarkdownBasicText
import com.tencent.kuiklybase.markdown.shaded.immutable.persistentMapOf
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownElementTypes.ORDERED_LIST
import org.intellij.markdown.MarkdownElementTypes.PARAGRAPH
import org.intellij.markdown.MarkdownElementTypes.UNORDERED_LIST
import org.intellij.markdown.MarkdownTokenTypes.Companion.LIST_BULLET
import org.intellij.markdown.MarkdownTokenTypes.Companion.LIST_NUMBER
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CHECK_BOX

/** key used to store the current depth in the [MarkdownComponentModel.extra] */
private const val MARKDOWN_LIST_DEPTH_KEY = "markdown_list_depth"

@Composable
fun MarkdownListItems(
    content: String,
    node: ASTNode,
    depth: Int = 0,
    markerModifier: RowScope.() -> Modifier = { Modifier },
    listModifier: RowScope.() -> Modifier = { Modifier.weight(1f) },
    bullet: @Composable (index: Int, child: ASTNode?) -> Unit,
) {
    val listDp = LocalMarkdownPadding.current.list
    val indentListDp = LocalMarkdownPadding.current.listIndent
    val listItemPaddingDp = LocalMarkdownPadding.current.listItemTop
    val listItemBottom = LocalMarkdownPadding.current.listItemBottom
    val markdownComponents = LocalMarkdownComponents.current
    val markdownTypography = LocalMarkdownTypography.current
    // Nested lists live inside the parent item's weighted column. Applying
    // fillMaxWidth at every depth creates an unconstrained placement cycle in
    // Kuikly's wrapped+nested layout path (StackOverflowError during place).
    // Keep the root list full-width, but let nested lists resolve naturally in
    // the already-constrained parent column.
    Column(
        modifier = Modifier
            .then(if (depth == 0) Modifier.fillMaxWidth() else Modifier)
            .padding(
                start = (indentListDp) * (depth + 1),
                top = listDp,
                bottom = listDp
            )
    ) {
        var index = 0
        node.children.forEach { child ->
            when (child.type) {
                MarkdownElementTypes.LIST_ITEM -> {
                    // LIST_NUMBER/LIST_BULLET, CHECK_BOX, PARAGRAPH
                    val checkboxNode = child.children.getOrNull(1)?.takeIf { it.type == CHECK_BOX }
                    val listIndicator = when (node.type) {
                        ORDERED_LIST -> child.findChildOfType(LIST_NUMBER)
                        UNORDERED_LIST -> child.findChildOfType(LIST_BULLET)
                        else -> null
                    }

                    Row(
                        modifier =
                            Modifier
                                .then(if (depth == 0) Modifier.fillMaxWidth() else Modifier)
                                .padding(bottom = listItemBottom)
                    ) {
                        Box(modifier = markerModifier()) {
                            if (checkboxNode != null) {
                                val model = MarkdownComponentModel(
                                    content = content,
                                    node = checkboxNode,
                                    typography = markdownTypography,
                                    extra = persistentMapOf(MARKDOWN_LIST_DEPTH_KEY to depth + 1)
                                )
                                markdownComponents.checkbox.invoke(model)
                            } else {
                                bullet(index, listIndicator)
                            }
                        }
                        Column(modifier = listModifier()) {
                            child.children.onEach { nestedChild ->
                                when (nestedChild.type) {
                                    ORDERED_LIST -> {
                                        val model = MarkdownComponentModel(
                                            content = content,
                                            node = nestedChild,
                                            typography = markdownTypography,
                                            extra = persistentMapOf(MARKDOWN_LIST_DEPTH_KEY to depth + 1)
                                        )
                                        markdownComponents.orderedList.invoke(model)
                                    }

                                    UNORDERED_LIST -> {
                                        val model = MarkdownComponentModel(
                                            content = content,
                                            node = nestedChild,
                                            typography = markdownTypography,
                                            extra = persistentMapOf(MARKDOWN_LIST_DEPTH_KEY to depth + 1)
                                        )
                                        markdownComponents.unorderedList.invoke(model)
                                    }

                                    PARAGRAPH -> {
                                        // Slock (task #800): list rows keep the legacy
                                        // fill regardless of the paragraph fill local.
                                        MarkdownParagraph(
                                            content = content,
                                            node = nestedChild,
                                            modifier = if (depth == 0) Modifier.fillMaxWidth() else Modifier,
                                        )
                                    }

                                    else -> {
                                        handleElement(
                                            node = nestedChild,
                                            components = markdownComponents,
                                            content = content,
                                            includeSpacer = false
                                        )
                                    }
                                }
                            }
                        }
                    }

                    index++
                }
            }
        }
    }
}

@Composable
fun MarkdownOrderedList(
    content: String,
    node: ASTNode,
    style: TextStyle = LocalMarkdownTypography.current.ordered,
    depth: Int = 0,
    markerModifier: RowScope.() -> Modifier = { Modifier },
    listModifier: RowScope.() -> Modifier = { Modifier.weight(1f) },
) {
    val orderedListHandler = LocalOrderedListHandler.current
    MarkdownListItems(content, node, depth, markerModifier, listModifier) { index, child ->
        MarkdownBasicText(
            text = orderedListHandler.transform(
                type = LIST_NUMBER,
                bullet = child?.getUnescapedTextInNode(content),
                index = index,
                depth = depth
            ),
            style = style,
            modifier = Modifier.padding(end = 4.dp)
        )
    }
}

@Composable
fun MarkdownBulletList(
    content: String,
    node: ASTNode,
    style: TextStyle = LocalMarkdownTypography.current.bullet,
    depth: Int = 0,
    markerModifier: RowScope.() -> Modifier = { Modifier },
    listModifier: RowScope.() -> Modifier = { Modifier.weight(1f) },
) {
    MarkdownListItems(content, node, depth, markerModifier, listModifier) { index, child ->
        MarkdownBullet(depth, style)
    }
}

@Composable
private fun MarkdownBullet(depth: Int, style: TextStyle) {
    val lineHeight = if (style.lineHeight.isSpecified) style.lineHeight.value.dp else 28.dp
    Box(
        modifier = Modifier
            .height(lineHeight)
            .padding(end = 4.dp)
            .width(9.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (depth <= 0) {
            Spacer(
                modifier = Modifier
                    .background(style.color)
                    .clip(CircleShape)
                    .size(5.dp)
            )
        } else {
            Spacer(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .border(BorderStroke(width = 0.5.dp, color = style.color))
            )
        }
    }
}

/**
 * Retrieve the current list depth from the [MarkdownComponentModel]
 */
val MarkdownComponentModel.listDepth: Int
    get() = (extra[MARKDOWN_LIST_DEPTH_KEY] as? Int) ?: 0
