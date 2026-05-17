package ai.slock.compose

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.TextLinkStyles
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import ai.slock.compose.markdown.ThreadMarkdownTagKind
import ai.slock.compose.markdown.slockMarkdownAnnotator
import ai.slock.compose.markdown.threadMarkdownTagDisplayLabel
import com.tencent.kuiklybase.markdown.compose.Markdown
import com.tencent.kuiklybase.markdown.model.DefaultMarkdownColors
import com.tencent.kuiklybase.markdown.model.DefaultMarkdownTypography
import com.tencent.kuiklybase.markdown.model.markdownPadding

const val SLOCK_MARKDOWN_SMOKE_PAGE = "compose_markdown_smoke"

@Page(SLOCK_MARKDOWN_SMOKE_PAGE, supportInLocal = true)
internal class ComposeSlockMarkdownSmokePage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockMarkdownSmokeContent() }
    }
}

@Composable
private fun SlockMarkdownSmokeContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlockColor.AppBgLightBlue)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlockColor.BrandYellow)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(SlockSpacing.Md)
        ) {
            Text("Markdown Smoke", style = SlockType.PageTitle, color = SlockColor.TextBlack)
            Text("Forked renderer dependency smoke", style = SlockType.Button, color = SlockColor.TextMutedGray)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Text("Slock inline samples", style = SlockType.Button, color = SlockColor.TextBlack)
            Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
                SlockSmokeInlineChip(
                    threadMarkdownTagDisplayLabel("#markdown专修"),
                    smokeTagColor(ThreadMarkdownTagKind.Channel)
                )
                SlockSmokeInlineChip(
                    threadMarkdownTagDisplayLabel("@artin"),
                    smokeTagColor(ThreadMarkdownTagKind.UserMention)
                )
                SlockSmokeInlineChip(
                    threadMarkdownTagDisplayLabel("#14"),
                    smokeTagColor(ThreadMarkdownTagKind.Task)
                )
            }
            SlockSmokeInlineCodeChip("inline code")
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(SlockSpacing.Md)
        ) {
            Markdown(
                content = markdownSmokeBody,
                colors = slockMarkdownSmokeColors(),
                typography = slockMarkdownSmokeTypography(),
                annotator = slockMarkdownAnnotator(markdownSmokeBody),
                padding = markdownPadding(
                    block = SlockSpacing.Xs,
                    paragraphVerPadding = com.tencent.kuikly.compose.foundation.layout.PaddingValues(vertical = SlockSpacing.Xs),
                    codeBlock = com.tencent.kuikly.compose.foundation.layout.PaddingValues(SlockSpacing.Sm)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(SlockSpacing.Lg))
    }
}

private fun smokeTagColor(kind: ThreadMarkdownTagKind): Color =
    when (kind) {
        ThreadMarkdownTagKind.Channel -> SlockColor.AccentCyan
        ThreadMarkdownTagKind.Task -> SlockColor.AccentOrange
        ThreadMarkdownTagKind.UserMention -> SlockColor.AccentLavender
    }

@Composable
private fun SlockSmokeInlineChip(label: String, color: Color) {
    Row(
        modifier = Modifier
            .background(color)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .padding(start = 8.dp, end = 8.dp, top = 1.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = slockSmokeInlineChipTextStyle(), color = SlockColor.TextBlack)
    }
}

@Composable
private fun SlockSmokeInlineCodeChip(code: String) {
    Row(
        modifier = Modifier
            .background(SlockColor.EmptyStateGray)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .padding(start = 8.dp, end = 8.dp, top = 1.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(code, style = slockSmokeInlineCodeStyle(), color = SlockColor.TextBlack)
    }
}

private fun slockSmokeInlineChipTextStyle() =
    SlockType.Caption.copy(fontSize = 14.sp, lineHeight = 16.sp)

private fun slockSmokeInlineCodeStyle() =
    SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 16.sp)

@Composable
private fun slockMarkdownSmokeColors() =
    DefaultMarkdownColors(
        text = SlockColor.TextBlack,
        codeText = SlockColor.TextBlack,
        inlineCodeText = SlockColor.TextBlack,
        linkText = SlockColor.AccentCyan,
        codeBackground = SlockColor.EmptyStateGray,
        inlineCodeBackground = SlockColor.EmptyStateGray,
        dividerColor = SlockColor.BorderBlack,
        tableText = SlockColor.TextBlack,
        tableBackground = SlockColor.SurfaceWhite,
        tableHeaderBackground = SlockColor.BrandYellow,
        tableStroke = SlockColor.BorderBlack
    )

@Composable
private fun slockMarkdownSmokeTypography(): DefaultMarkdownTypography {
    val base = SlockType.Body
    val code = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    )
    return DefaultMarkdownTypography(
        h1 = SlockType.PageTitle.copy(fontSize = 22.sp, lineHeight = 28.sp),
        h2 = SlockType.PageTitle.copy(fontSize = 18.sp, lineHeight = 24.sp),
        h3 = SlockType.Button.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
        h4 = SlockType.Button,
        h5 = SlockType.Button,
        h6 = SlockType.Button,
        text = base,
        code = code,
        inlineCode = code,
        quote = SlockType.Body.copy(color = SlockColor.TextMutedGray),
        paragraph = base,
        ordered = base,
        bullet = base,
        list = base,
        link = SlockType.Body.copy(color = SlockColor.AccentCyan),
        textLink = TextLinkStyles(style = SpanStyle(color = SlockColor.AccentCyan)),
        table = base
    )
}

private val markdownSmokeBody =
    """
    # Slock Markdown

    这是一段 **Markdown** 文本，包含 `inline code`、链接和列表。

    - channel: #markdown专修
    - user: @artin
    - task: #14

    > blockquote should stay visible on Harmony.

    ```kotlin
    val renderer = "kuikly-compose-markdown"
    println(renderer)
    ```
    """.trimIndent()
