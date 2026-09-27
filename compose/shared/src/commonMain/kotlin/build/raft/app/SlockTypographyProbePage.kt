package build.raft.app

import build.raft.app.base.BasePager
import build.raft.app.markdown.SlockMarkdownBody
import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.markdown.ThreadMarkdownTagKind
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page

const val SLOCK_TYPOGRAPHY_PROBE_PAGE = "compose_typography_probe"

@Page(SLOCK_TYPOGRAPHY_PROBE_PAGE, supportInLocal = true)
internal class SlockTypographyProbePage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockTypographyProbeScreen() }
    }
}

@Composable
private fun SlockTypographyProbeScreen() {
    val statusBarHeight = LocalActivity.current.pageData.statusBarHeight
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceCream)
                .padding(start = 16.dp, top = (statusBarHeight + 16).dp, end = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Typography Probe",
                    style = SlockType.PageTitle.copy(fontSize = 22.sp, lineHeight = 28.sp, color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack
                )
                Text(
                    text = "Space Grotesk weight and CJK fallback parity checks",
                    style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray
                )
            }
        }
        item { TypographyProbeSection("Latin Text", LatinWeightRows) }
        item { TypographyProbeSection("Mixed CJK Fallback", CjkWeightRows) }
        item { TypographyTightLineHeightProbeSection() }
        item { TypographyMarkdownProbeSection() }
    }
}

@Composable
private fun TypographyTightLineHeightProbeSection() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Tight Fallback Lines", style = SlockType.ButtonBold.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
        Text(
            text = "20sp/22sp · combining mark + emoji + CJK",
            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        Text(
            text = "A\u0301g 😀 中文 fallback\nA\u0301g 😀 中文 fallback",
            style = SlockType.Body.copy(fontSize = 20.sp, lineHeight = 22.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
    }
}

@Composable
private fun TypographyProbeSection(title: String, rows: List<TypographyProbeRow>) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = SlockType.ButtonBold.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
        rows.forEach { row ->
            TypographyProbeTextRow(row)
        }
    }
}

@Composable
private fun TypographyProbeTextRow(row: TypographyProbeRow) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = row.label,
            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            modifier = Modifier.weight(0.34f)
        )
        Text(
            text = row.sample,
            style = row.style,
            color = SlockColor.TextBlack,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TypographyMarkdownProbeSection() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Message Body Path", style = SlockType.ButtonBold.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
        Text(
            text = "SlockMarkdownBody · 14sp/24sp · regular",
            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        SlockMarkdownBody(
            body = "Rich fixture for #design: @artin please compare the Android capture against task #222.\n\n中文正文：输入 at 之后选择成员，光标应该回到最后。",
            textStyle = MessageBodyProbeStyle,
            highlightedMentions = TypographyProbeHighlightedMentions,
            isTagValid = ::isTypographyProbeMarkdownTagValid
        )
        Text(
            text = "SlockMarkdownBody · 14sp/24sp · medium probe",
            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        SlockMarkdownBody(
            body = "Rich fixture for #design: @artin please compare the Android capture against task #222.\n\n中文正文：输入 at 之后选择成员，光标应该回到最后。",
            textStyle = MessageBodyProbeStyle.withSlockFontWeight(FontWeight.Medium),
            highlightedMentions = TypographyProbeHighlightedMentions,
            isTagValid = ::isTypographyProbeMarkdownTagValid
        )
    }
}

private data class TypographyProbeRow(
    val label: String,
    val sample: String,
    val style: TextStyle
)

private val MessageBodyProbeStyle =
    SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack)

private val TypographyProbeHighlightedMentions = setOf("artin")

private val TypographyProbeKnownChannelNames = setOf("design")

private fun isTypographyProbeMarkdownTagValid(tag: ThreadMarkdownTag): Boolean =
    when (tag.kind) {
        ThreadMarkdownTagKind.Task -> true
        ThreadMarkdownTagKind.UserMention -> tag.label.removePrefix("@").equals("artin", ignoreCase = true)
        ThreadMarkdownTagKind.DirectMessageThread,
        ThreadMarkdownTagKind.DirectMessage ->
            tag.directMessagePeerName.equals("artin", ignoreCase = true)
        ThreadMarkdownTagKind.Channel,
        ThreadMarkdownTagKind.ChannelThread -> {
            val channelKey =
                (tag.channelName ?: tag.label.removePrefix("#").substringBefore(":"))
                    .trim()
                    .lowercase()
            channelKey in TypographyProbeKnownChannelNames
        }
    }

private val LatinWeightRows =
    listOf(
        TypographyProbeRow("400", "Slock message body glyphs 012345", MessageBodyProbeStyle.withSlockFontWeight(FontWeight.Normal)),
        TypographyProbeRow("500", "Slock message body glyphs 012345", MessageBodyProbeStyle.withSlockFontWeight(FontWeight.Medium)),
        TypographyProbeRow("600", "Slock message body glyphs 012345", MessageBodyProbeStyle.withSlockFontWeight(FontWeight.SemiBold)),
        TypographyProbeRow("700", "Slock message body glyphs 012345", MessageBodyProbeStyle.withSlockFontWeight(FontWeight.Bold))
    )

private val CjkWeightRows =
    listOf(
        TypographyProbeRow("400", "中文 ABC 123", MessageBodyProbeStyle.withSlockFontWeight(FontWeight.Normal)),
        TypographyProbeRow("500", "中文 ABC 123", MessageBodyProbeStyle.withSlockFontWeight(FontWeight.Medium)),
        TypographyProbeRow("600", "中文 ABC 123", MessageBodyProbeStyle.withSlockFontWeight(FontWeight.SemiBold)),
        TypographyProbeRow("700", "中文 ABC 123", MessageBodyProbeStyle.withSlockFontWeight(FontWeight.Bold))
    )
