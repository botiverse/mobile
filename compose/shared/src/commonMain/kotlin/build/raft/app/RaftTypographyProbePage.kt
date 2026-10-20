package build.raft.app

import build.raft.app.base.BasePager
import build.raft.app.markdown.RaftMarkdownBody
import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.markdown.ThreadMarkdownTagKind
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.compat.pager.Page

const val RAFT_TYPOGRAPHY_PROBE_PAGE = "compose_typography_probe"

@Page(RAFT_TYPOGRAPHY_PROBE_PAGE, supportInLocal = true)
internal class RaftTypographyProbePage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftTypographyProbeScreen() }
    }
}

@Composable
private fun RaftTypographyProbeScreen() {
    val statusBarHeight = LocalActivity.current.pageData.statusBarHeight
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .background(RaftColor.SurfaceCream)
                .padding(start = 16.dp, top = (statusBarHeight + 16).dp, end = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Typography Probe",
                    style = RaftType.PageTitle.copy(fontSize = 22.sp, lineHeight = 28.sp, color = RaftColor.TextBlack),
                    color = RaftColor.TextBlack
                )
                Text(
                    text = "Space Grotesk weight and CJK fallback parity checks",
                    style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray
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
                .background(RaftColor.SurfaceWhite)
                .border(RaftAppStroke.Default, RaftColor.BorderBlack)
                .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Tight Fallback Lines", style = RaftType.ButtonBold.copy(color = RaftColor.TextBlack), color = RaftColor.TextBlack)
        Text(
            text = "20sp/22sp · combining mark + emoji + CJK",
            style = RaftType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp, color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray
        )
        Text(
            text = "A\u0301g 😀 中文 fallback\nA\u0301g 😀 中文 fallback",
            style = RaftType.Body.copy(fontSize = 20.sp, lineHeight = 22.sp, color = RaftColor.TextBlack),
            color = RaftColor.TextBlack
        )
    }
}

@Composable
private fun TypographyProbeSection(title: String, rows: List<TypographyProbeRow>) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RaftColor.SurfaceWhite)
                .border(RaftAppStroke.Default, RaftColor.BorderBlack)
                .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = RaftType.ButtonBold.copy(color = RaftColor.TextBlack), color = RaftColor.TextBlack)
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
            style = RaftType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp, color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray,
            modifier = Modifier.weight(0.34f)
        )
        Text(
            text = row.sample,
            style = row.style,
            color = RaftColor.TextBlack,
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
                .background(RaftColor.SurfaceWhite)
                .border(RaftAppStroke.Default, RaftColor.BorderBlack)
                .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Message Body Path", style = RaftType.ButtonBold.copy(color = RaftColor.TextBlack), color = RaftColor.TextBlack)
        Text(
            text = "RaftMarkdownBody · 14sp/24sp · regular",
            style = RaftType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp, color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray
        )
        RaftMarkdownBody(
            body = "Rich fixture for #design: @artin please compare the Android capture against task #222.\n\n中文正文：输入 at 之后选择成员，光标应该回到最后。",
            textStyle = MessageBodyProbeStyle,
            highlightedMentions = TypographyProbeHighlightedMentions,
            isTagValid = ::isTypographyProbeMarkdownTagValid
        )
        Text(
            text = "RaftMarkdownBody · 14sp/24sp · medium probe",
            style = RaftType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp, color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray
        )
        RaftMarkdownBody(
            body = "Rich fixture for #design: @artin please compare the Android capture against task #222.\n\n中文正文：输入 at 之后选择成员，光标应该回到最后。",
            textStyle = MessageBodyProbeStyle.withRaftFontWeight(FontWeight.Medium),
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
    RaftType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = RaftColor.TextBlack)

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
        TypographyProbeRow("400", "Slock message body glyphs 012345", MessageBodyProbeStyle.withRaftFontWeight(FontWeight.Normal)),
        TypographyProbeRow("500", "Slock message body glyphs 012345", MessageBodyProbeStyle.withRaftFontWeight(FontWeight.Medium)),
        TypographyProbeRow("600", "Slock message body glyphs 012345", MessageBodyProbeStyle.withRaftFontWeight(FontWeight.SemiBold)),
        TypographyProbeRow("700", "Slock message body glyphs 012345", MessageBodyProbeStyle.withRaftFontWeight(FontWeight.Bold))
    )

private val CjkWeightRows =
    listOf(
        TypographyProbeRow("400", "中文 ABC 123", MessageBodyProbeStyle.withRaftFontWeight(FontWeight.Normal)),
        TypographyProbeRow("500", "中文 ABC 123", MessageBodyProbeStyle.withRaftFontWeight(FontWeight.Medium)),
        TypographyProbeRow("600", "中文 ABC 123", MessageBodyProbeStyle.withRaftFontWeight(FontWeight.SemiBold)),
        TypographyProbeRow("700", "中文 ABC 123", MessageBodyProbeStyle.withRaftFontWeight(FontWeight.Bold))
    )
