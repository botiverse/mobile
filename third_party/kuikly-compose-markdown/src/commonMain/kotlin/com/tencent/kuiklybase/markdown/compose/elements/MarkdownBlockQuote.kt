package com.tencent.kuiklybase.markdown.compose.elements

import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownComponents
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownDimens
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownPadding
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownTypography
import com.tencent.kuiklybase.markdown.compose.handleElement
import com.tencent.kuiklybase.markdown.utils.findChildOfTypeRecursive
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownElementTypes.PARAGRAPH
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes.TABLE

@Composable
fun MarkdownBlockQuote(
    content: String,
    node: ASTNode,
    style: TextStyle = LocalMarkdownTypography.current.quote,
    // Slock: the web draws the rail in `line-seam`, not the quote's text colour.
    barColor: Color = style.color,
) {
    val blockQuoteColor = barColor

    val blockQuoteThickness = LocalMarkdownDimens.current.blockQuoteThickness
    val blockQuote = LocalMarkdownPadding.current.blockQuote
    val blockQuoteText = LocalMarkdownPadding.current.blockQuoteText
    val blockQuoteBar = LocalMarkdownPadding.current.blockQuoteBar
    val markdownComponents = LocalMarkdownComponents.current

    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Spacer(
            modifier = Modifier
                .padding(blockQuoteBar)
                .width(blockQuoteThickness)
                .fillMaxHeight()
                .background(blockQuoteColor)
        )

        Column(
            modifier = Modifier
                .padding(blockQuote)
                .fillMaxWidth()
        ) {
            val nonBlockquotes = node.children.filter { it.type != MarkdownElementTypes.BLOCK_QUOTE }
            val nestedQuote = node.findChildOfType(MarkdownElementTypes.BLOCK_QUOTE)

            if (nonBlockquotes.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth().padding(blockQuoteText)) {
                    nonBlockquotes.onEach { quote ->
                        when {
                            requiresBlockQuotePlainTextFallback(quote) -> {
                                MarkdownText(
                                    content = blockQuoteTablePlainText(
                                        quote.getTextInNode(content).toString()
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                    style = style.merge(fontFamily = FontFamily.Monospace)
                                )
                            }

                            quote.type == PARAGRAPH -> {
                                // Slock (task #800): quoted paragraphs keep the
                                // legacy fill regardless of the paragraph fill local.
                                MarkdownParagraph(content, quote, modifier = Modifier.fillMaxWidth(), style = style)
                            }

                            else -> {
                                handleElement(
                                    node = quote,
                                    components = markdownComponents,
                                    content = content,
                                    includeSpacer = false
                                )
                            }
                        }
                    }
                }

                if (nestedQuote != null) Spacer(Modifier.height(8.dp))
            }

            if (nestedQuote != null) {
                MarkdownBlockQuote(
                    content = content,
                    node = nestedQuote,
                    style = style,
                    barColor = barColor
                )
            }
        }
    }
}

internal fun requiresBlockQuotePlainTextFallback(node: ASTNode): Boolean =
    node.type == TABLE || node.findChildOfTypeRecursive(TABLE) != null

internal fun blockQuoteTablePlainText(content: String): String =
    content.lineSequence().joinToString("\n") { line ->
        when {
            line.startsWith("> ") -> line.removePrefix("> ")
            line.startsWith(">") -> line.removePrefix(">")
            else -> line
        }
    }
