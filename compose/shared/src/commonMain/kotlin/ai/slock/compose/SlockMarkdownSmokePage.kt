package ai.slock.compose

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page

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
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        content.toSmokeMarkdownBlocks().forEach { block ->
            SmokeMarkdownBlock(block)
        }
    }
}

@Composable
private fun SmokeMarkdownBlock(block: SmokeMarkdownBlock) {
    when (block) {
        is SmokeMarkdownBlock.Code ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SlockColor.BrandYellow)
                    .border(1.dp, SlockColor.BorderBlack)
                    .padding(8.dp)
            ) {
                Text(
                    block.text,
                    style = SlockType.Caption.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack
                )
            }
        is SmokeMarkdownBlock.Heading ->
            Text(
                block.text,
                style = SlockType.PageTitle.copy(
                    fontSize = when (block.level) {
                        1 -> 20.sp
                        2 -> 18.sp
                        else -> 16.sp
                    },
                    lineHeight = when (block.level) {
                        1 -> 24.sp
                        2 -> 22.sp
                        else -> 20.sp
                    },
                    color = SlockColor.TextBlack
                ),
                color = SlockColor.TextBlack
            )
        is SmokeMarkdownBlock.ListItem ->
            Text(
                "• ${block.text.toSmokeMarkdownPlainText()}",
                style = SlockType.Body.copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack
            )
        is SmokeMarkdownBlock.Quote ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SlockColor.SurfaceCream)
                    .border(1.dp, SlockColor.BorderBlack)
                    .padding(8.dp)
            ) {
                Text(
                    block.text.toSmokeMarkdownPlainText(),
                    style = SlockType.Body.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack
                )
            }
        is SmokeMarkdownBlock.Text ->
            Text(
                block.text.toSmokeMarkdownPlainText(),
                style = SlockType.Body.copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack
            )
        SmokeMarkdownBlock.Space ->
            Box(modifier = Modifier.fillMaxWidth().height(4.dp), contentAlignment = Alignment.Center) {}
    }
}

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

private sealed interface SmokeMarkdownBlock {
    data class Heading(val level: Int, val text: String) : SmokeMarkdownBlock
    data class Text(val text: String) : SmokeMarkdownBlock
    data class ListItem(val text: String) : SmokeMarkdownBlock
    data class Quote(val text: String) : SmokeMarkdownBlock
    data class Code(val text: String) : SmokeMarkdownBlock
    data object Space : SmokeMarkdownBlock
}

private fun String.toSmokeMarkdownBlocks(): List<SmokeMarkdownBlock> {
    val blocks = mutableListOf<SmokeMarkdownBlock>()
    var fenced = false
    val codeLines = mutableListOf<String>()
    lineSequence().forEach { rawLine ->
        val line = rawLine.trimEnd()
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            if (fenced) {
                blocks += SmokeMarkdownBlock.Code(codeLines.joinToString("\n"))
                codeLines.clear()
                fenced = false
            } else {
                fenced = true
            }
        } else if (fenced) {
            codeLines += line
        } else if (trimmed.isBlank()) {
            blocks += SmokeMarkdownBlock.Space
        } else if (trimmed.startsWith("#")) {
            val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 6)
            blocks += SmokeMarkdownBlock.Heading(level, trimmed.drop(level).trim().toSmokeMarkdownPlainText())
        } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            blocks += SmokeMarkdownBlock.ListItem(trimmed.drop(2).trim())
        } else if (trimmed.startsWith(">")) {
            blocks += SmokeMarkdownBlock.Quote(trimmed.drop(1).trim())
        } else {
            blocks += SmokeMarkdownBlock.Text(line.trim())
        }
    }
    if (fenced && codeLines.isNotEmpty()) {
        blocks += SmokeMarkdownBlock.Code(codeLines.joinToString("\n"))
    }
    return blocks
}

private fun String.toSmokeMarkdownPlainText(): String =
    replace(Regex("""!\[([^]]*)]\(([^)]*)\)"""), "$1 ($2)")
        .replace(Regex("""\[([^]]+)]\(([^)]*)\)"""), "$1 ($2)")
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .replace(Regex("""(^|[^*])\*([^*]+)\*"""), "$1$2")
        .replace(Regex("""(^|[^_])_([^_]+)_"""), "$1$2")
