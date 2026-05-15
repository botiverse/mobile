package ai.slock.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.TextLinkStyles
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuiklybase.markdown.compose.Markdown
import com.tencent.kuiklybase.markdown.model.DefaultMarkdownColors
import com.tencent.kuiklybase.markdown.model.DefaultMarkdownTypography
import com.tencent.kuiklybase.markdown.model.rememberMarkdownState

const val SLOCK_MARKDOWN_SMOKE_PAGE = "compose_markdown_smoke"

@Page(SLOCK_MARKDOWN_SMOKE_PAGE, supportInLocal = true)
internal class ComposeMarkdownSmokePage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockMarkdownSmokeContent() }
    }
}

@Composable
internal fun SlockMarkdownSmokeContent() {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Markdown Smoke",
                    style = SlockType.PageTitle.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Kuikly shared renderer: markdown basics, code, links, and HTML tags as text",
                    style = SlockType.Input.copy(fontSize = 13.sp, lineHeight = 18.sp, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        markdownSmokeSamples.forEach { sample ->
            item(sample.title) {
                SlockMarkdownSmokeCard(sample)
            }
        }
    }
}

@Composable
private fun SlockMarkdownSmokeCard(sample: MarkdownSmokeSample) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            sample.title.uppercase(),
            style = SlockType.SectionLabel.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        SlockMarkdownText(sample.body)
    }
}

@Composable
internal fun SlockMarkdownText(
    content: String,
    modifier: Modifier = Modifier
) {
    val markdownState = rememberMarkdownState()
    LaunchedEffect(content) {
        markdownState.parse(content.escapeHtmlTagsAsText(), false)
    }
    Markdown(
        state = markdownState,
        colors = SlockMarkdownColors,
        typography = SlockMarkdownTypography,
        modifier = modifier.fillMaxWidth()
    )
}

private val SlockMarkdownColors = DefaultMarkdownColors(
    text = SlockColor.TextBlack,
    codeText = SlockColor.TextBlack,
    inlineCodeText = SlockColor.TextBlack,
    linkText = Color(0xFF005FCC),
    codeBackground = SlockColor.BrandYellow,
    inlineCodeBackground = SlockColor.BrandYellow,
    dividerColor = SlockColor.BorderBlack,
    tableText = SlockColor.TextBlack,
    tableBackground = SlockColor.SurfaceWhite,
    tableHeaderBackground = SlockColor.SurfaceCream,
    tableStroke = SlockColor.BorderBlack
)

private val SlockMarkdownTypography = DefaultMarkdownTypography(
    h1 = SlockType.PageTitle,
    h2 = SlockType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp),
    h3 = SlockType.Button.copy(fontSize = 16.sp, lineHeight = 20.sp),
    h4 = SlockType.Button,
    h5 = SlockType.Body,
    h6 = SlockType.Body,
    text = SlockType.Body,
    code = SlockType.Caption,
    inlineCode = SlockType.Caption,
    quote = SlockType.Body,
    paragraph = SlockType.Body,
    ordered = SlockType.Body,
    bullet = SlockType.Body,
    list = SlockType.Body,
    link = SlockType.Body,
    textLink = TextLinkStyles(),
    table = SlockType.Caption
)

private data class MarkdownSmokeSample(
    val title: String,
    val body: String
)

private val markdownSmokeSamples = listOf(
    MarkdownSmokeSample(
        title = "Basics",
        body = """
            # Slock markdown
            Plain text with **bold**, _emphasis_, and `inline code`.

            - channel rows should stay readable
            - emoji must remain visible: ✅ 🚀 📌
        """.trimIndent()
    ),
    MarkdownSmokeSample(
        title = "Code and link",
        body = """
            Open [Slock](https://app.slock.ai) and keep code blocks legible:

            ```kotlin
            val target = "#markdown专修"
            println(target)
            ```
        """.trimIndent()
    ),
    MarkdownSmokeSample(
        title = "HTML tags as text",
        body = """
            <div class="message">
              <button>View</button>
            </div>

            The brackets above should show as normal text, not render as HTML.
        """.trimIndent()
    )
)

private fun String.escapeHtmlTagsAsText(): String =
    buildString(length) {
        var fenced = false
        lineSequence().forEachIndexed { index, line ->
            if (index > 0) append('\n')
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                fenced = !fenced
                append(line)
            } else if (fenced) {
                append(line)
            } else {
                line.forEach { char ->
                    when (char) {
                        '<' -> append("\\<")
                        '>' -> append("\\>")
                        else -> append(char)
                    }
                }
            }
        }
    }
