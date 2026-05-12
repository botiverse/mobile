package ai.slock.kmp

import ai.slock.kmp.base.BasePager
import ai.slock.shared.markdown.SlockMarkdownPreviewSample
import ai.slock.shared.markdown.SlockMarkdownPreviewSamples
import ai.slock.shared.markdown.ThreadMarkdownTagKind
import ai.slock.shared.markdown.normalizeThreadMarkdownBody
import ai.slock.shared.markdown.threadInlineCodeMatches
import ai.slock.shared.markdown.threadInlineTagMatches
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.layout.Row

private const val MARKDOWN_PREVIEW_PAGE_NAME = "markdown_preview"

@Page(MARKDOWN_PREVIEW_PAGE_NAME, supportInLocal = true)
internal class MarkdownPreviewPage : BasePager() {
    override fun body(): ViewBuilder {
        return {
            attr { backgroundColor(Color.WHITE) }
            RouterNavBar {
                attr { title = "Markdown Preview" }
            }
            Scroller {
                attr {
                    flex(1f)
                    padding(12f)
                    backgroundColor(Color(0xFFF8F8F8))
                }
                SlockMarkdownPreviewSamples.all.forEach { sample ->
                    MarkdownPreviewSampleCard(sample)
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.MarkdownPreviewSampleCard(sample: SlockMarkdownPreviewSample) {
    View {
        attr {
            marginBottom(14f)
            padding(12f)
            borderRadius(8f)
            backgroundColor(Color.WHITE)
        }
        Text {
            attr {
                text(sample.title)
                fontSize(18f)
                fontWeightBold()
                color(Color(0xFF111111))
            }
        }
        Text {
            attr {
                text(sample.description)
                fontSize(12f)
                color(Color(0xFF666666))
                marginTop(4f)
            }
        }
        Text {
            attr {
                text(sample.body.normalizeThreadMarkdownBody())
                fontSize(14f)
                color(Color(0xFF222222))
                marginTop(10f)
            }
        }
        val tags = sample.body.normalizeThreadMarkdownBody().threadInlineTagMatches()
        if (tags.isNotEmpty()) {
            Text {
                attr {
                    text("Inline tags")
                    fontSize(12f)
                    color(Color(0xFF666666))
                    marginTop(10f)
                }
            }
            Row {
                attr { marginTop(6f) }
                tags.forEach { match ->
                    MarkdownTokenChip(
                        text = match.tag.label,
                        color =
                            when (match.tag.kind) {
                                ThreadMarkdownTagKind.Channel -> Color(0xFF9BE7FF)
                                ThreadMarkdownTagKind.Task -> Color(0xFFFFC46B)
                                ThreadMarkdownTagKind.UserMention -> Color(0xFFD8C2FF)
                            }
                    )
                }
            }
        }
        val codeMatches = sample.body.normalizeThreadMarkdownBody().threadInlineCodeMatches()
        if (codeMatches.isNotEmpty()) {
            Text {
                attr {
                    text("Inline code: " + codeMatches.joinToString { it.code })
                    fontSize(12f)
                    color(Color(0xFF6F5300))
                    marginTop(8f)
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.MarkdownTokenChip(text: String, color: Color) {
    View {
        attr {
            backgroundColor(color)
            borderRadius(4f)
            padding(6f)
            marginRight(6f)
        }
        Text {
            attr {
                this.text(text)
                fontSize(12f)
                color(Color(0xFF111111))
            }
        }
    }
}
