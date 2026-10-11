package build.raft.app.debug

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.buildAnnotatedString
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.withStyle
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page

/**
 * Verification-only carrier for upstream PR Tencent-TDS/KuiklyUI#1774 (per-span
 * background colour). Same six cases as `RichTextSpanBackgroundDemo` in that PR
 * and in the KuiklyUI fork demo (patch
 * feat_demo_add_rich_text_span_background_demos). The renderer fix itself is
 * already in Kuikly 2.28.0-raft.11 as patch
 * feat_richtext_render_spanstyle_background_colors_on_all_platforms. It lives on
 * the non-production branch only because the fork demo HAP cannot be signed with
 * the Raft profile. Open it by page name.
 */
const val RICH_TEXT_SPAN_BACKGROUND_DEMO_PAGE = "RichTextSpanBackgroundDemo"

@Page(RICH_TEXT_SPAN_BACKGROUND_DEMO_PAGE, supportInLocal = true)
internal class RichTextSpanBackgroundDemoPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SpanBackgroundScreen() }
    }
}

private val Highlight = Color(0xFFFFE08A)
private val Mint = Color(0xFFB9F6CA)
private val Sky = Color(0xFFBBDEFB)
private val PanelBackground = Color(0xFFE0E0E0)

@Composable
private fun SpanBackgroundScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color.White),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("RichText span background (Kuikly raft.11, upstream #1774)", fontSize = 16.sp, fontWeight = FontWeight.Bold)

                SpanCase("1. Single span. Expected: only the middle word carries a yellow background.") {
                    Text(
                        buildAnnotatedString {
                            append("plain ")
                            withStyle(SpanStyle(background = Highlight)) { append("highlighted") }
                            append(" plain")
                        },
                        fontSize = 18.sp,
                    )
                }

                SpanCase(
                    "2. Long span wrapping over several lines. Expected: the yellow background follows " +
                        "the span's glyphs on every line it occupies and stops at the span end; the " +
                        "trailing plain text has no background.",
                ) {
                    Text(
                        buildAnnotatedString {
                            append("Before the span. ")
                            withStyle(SpanStyle(background = Highlight)) {
                                append(
                                    "This span is long enough to wrap onto at least two or three lines " +
                                        "at 18sp in a 260dp wide column, so the background must break " +
                                        "and continue on the next line exactly where the glyphs are.",
                                )
                            }
                            append(" After the span, no background.")
                        },
                        fontSize = 18.sp,
                        modifier = Modifier.width(260.dp),
                    )
                }

                SpanCase(
                    "3. Text background plus span background. Expected: the whole text box is grey; " +
                        "the two inner spans paint green and blue on top of the grey, only behind " +
                        "their own glyphs; grey stays visible around them and in the line gaps.",
                ) {
                    Text(
                        buildAnnotatedString {
                            append("grey box, ")
                            withStyle(SpanStyle(background = Mint)) { append("green span") }
                            append(", then ")
                            withStyle(SpanStyle(background = Sky, fontWeight = FontWeight.Bold)) {
                                append("bold blue span that also wraps to the next line")
                            }
                            append(", grey again.")
                        },
                        fontSize = 18.sp,
                        modifier = Modifier.width(260.dp).background(PanelBackground).padding(8.dp),
                    )
                }

                SpanCase(
                    "4. Adjacent spans with different backgrounds and sizes. Expected: yellow, green " +
                        "and blue sit edge to edge with no gap and no overlap; the larger span's " +
                        "background is taller.",
                ) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(background = Highlight)) { append("yellow") }
                            withStyle(SpanStyle(background = Mint, fontSize = 26.sp)) { append("GREEN") }
                            withStyle(SpanStyle(background = Sky)) { append("blue") }
                        },
                        fontSize = 18.sp,
                    )
                }

                SpanCase(
                    "5. Span background together with a span colour. Expected: white text on a blue " +
                        "background for the span; the surrounding text stays black on white.",
                ) {
                    Text(
                        buildAnnotatedString {
                            append("before ")
                            withStyle(SpanStyle(background = Color(0xFF1E88E5), color = Color.White)) {
                                append(" inverted ")
                            }
                            append(" after")
                        },
                        fontSize = 18.sp,
                    )
                }

                SpanCase("6. Control: view-level background only, no span background. Expected: unchanged, the whole box is yellow.") {
                    Text(
                        "plain text with a view background",
                        fontSize = 18.sp,
                        modifier = Modifier.background(Highlight),
                    )
                }
            }
        }
    }
}

@Composable
private fun SpanCase(expected: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(expected, fontSize = 12.sp, color = Color(0xFF616161))
        content()
    }
}
