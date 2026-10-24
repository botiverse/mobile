package com.tencent.kuiklybase.markdown.compose.elements

import androidx.compose.runtime.Composable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownColors
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
                // Nested lists already sit inside the parent item's text column, so
                // the indent is per level, not multiplied by depth (web: each `ul`
                // adds the same padding-left).
                start = indentListDp,
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
    val density = LocalDensity.current
    val listIndent = LocalMarkdownPadding.current.listIndent
    val fontSize = with(density) {
        (if (style.fontSize.isSpecified) style.fontSize else MarkdownBulletFallbackFontSize).toDp()
    }
    val lineHeight = with(density) {
        when {
            style.lineHeight.isSp -> style.lineHeight.toDp()
            style.lineHeight.isEm -> fontSize * style.lineHeight.value
            else -> fontSize * MarkdownBulletFallbackLineHeightEm
        }
    }
    val metrics = markdownBulletMetrics(fontSize = fontSize, lineHeight = lineHeight, listIndent = listIndent)
    val color = markdownBulletColor(style.color, LocalMarkdownColors.current.text)
    // The slot is one first-line box tall, so the marker is centered on the first line
    // whatever the font scale; the marker hangs at the slot's end, `gap` before the text.
    Box(
        modifier = Modifier
            .width(metrics.slotWidth)
            .height(metrics.lineHeight)
            .padding(end = metrics.gap),
        contentAlignment = Alignment.CenterEnd
    ) {
        val marker = Modifier.size(metrics.markerSize)
        when (markdownBulletShape(depth)) {
            MarkdownBulletShape.Disc -> Spacer(marker.clip(CircleShape).background(color))
            MarkdownBulletShape.Circle -> Spacer(
                marker.border(BorderStroke(width = metrics.strokeWidth, color = color), CircleShape)
            )
            MarkdownBulletShape.Square -> Spacer(marker.background(color))
        }
    }
}

/**
 * The marker is painted, not typeset, so it must resolve its colour the way list text does
 * ([MarkdownBasicText]: style colour, else [LocalMarkdownColors] text). Painting with an
 * unspecified style colour draws nothing: callers whose base style carries no colour (the file
 * preview, the default `RaftType.Body`) lost every bullet while their text still rendered.
 */
fun markdownBulletColor(styleColor: Color, markdownTextColor: Color): Color =
    styleColor.takeOrElse { markdownTextColor }.takeOrElse { Color.Black }

/** Browser `list-style-type` per nesting level: `disc` → `circle` → `square` (and square below). */
enum class MarkdownBulletShape { Disc, Circle, Square }

fun markdownBulletShape(depth: Int): MarkdownBulletShape = when {
    depth <= 0 -> MarkdownBulletShape.Disc
    depth == 1 -> MarkdownBulletShape.Circle
    else -> MarkdownBulletShape.Square
}

data class MarkdownBulletMetrics(
    val markerSize: Dp,
    val strokeWidth: Dp,
    val gap: Dp,
    val slotWidth: Dp,
    val lineHeight: Dp,
)

/**
 * Web parity for unordered list markers, all in em of the list text so they follow the
 * message font size:
 * - marker box: Chromium paints disc/circle/square at `ascent / 3` (≈ 1/3 em) for every level;
 * - circle stroke: 1px (kept ≥ 1dp, grows with large fonts);
 * - gap: the outside marker sits ~0.5em before the text (Chromium's 7px at 16px);
 * - indent: each list level moves the text 1.25em right (Slock web `pl-5`, 20px at the 16px
 *   phone body); the list's own [listIndent] is part of that, the marker slot fills the rest.
 */
fun markdownBulletMetrics(fontSize: Dp, lineHeight: Dp, listIndent: Dp): MarkdownBulletMetrics {
    val markerSize = fontSize * MarkdownBulletSizeEm
    val strokeWidth = maxOf(1.dp, markerSize * MarkdownBulletCircleStrokeRatio)
    val gap = fontSize * MarkdownBulletGapEm
    val slotWidth = maxOf(fontSize * MarkdownListIndentEm - listIndent, markerSize + gap)
    return MarkdownBulletMetrics(
        markerSize = markerSize,
        strokeWidth = strokeWidth,
        gap = gap,
        slotWidth = slotWidth,
        lineHeight = maxOf(lineHeight, markerSize),
    )
}

private const val MarkdownBulletSizeEm = 1f / 3f
private const val MarkdownBulletCircleStrokeRatio = 0.2f
private const val MarkdownBulletGapEm = 0.5f
/** Text indent of one list level, in em of the list text (shared by bullet and number markers). */
const val MarkdownListIndentEm = 1.25f
private const val MarkdownBulletFallbackLineHeightEm = 1.5f
private val MarkdownBulletFallbackFontSize = 14.sp

/**
 * Retrieve the current list depth from the [MarkdownComponentModel]
 */
val MarkdownComponentModel.listDepth: Int
    get() = (extra[MARKDOWN_LIST_DEPTH_KEY] as? Int) ?: 0
