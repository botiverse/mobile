package build.raft.app

import androidx.compose.ui.unit.dp
import build.raft.app.data.ArtifactExcerpt
import build.raft.app.data.ArtifactExcerptBlock
import build.raft.app.data.ArtifactExcerptSpan
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactSummary
import build.raft.app.thread.artifactCardScreenshotMaxHeight
import build.raft.app.thread.artifactCardVersionBehind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The compact Artifact message card's rules (#proj-mobile:#439). */
class ArtifactMessageCardCompactTest {
    private fun words(text: String) = listOf(ArtifactExcerptSpan(text))

    @Test
    fun excerptDropsTheDocumentsOpeningH1() {
        val body = ArtifactExcerptBlock.Paragraph(words("正文第一段"))
        val excerpt = ArtifactExcerpt("方案\n\n正文第一段", listOf(ArtifactExcerptBlock.Heading(1, words("方案")), body))
        assertEquals(listOf<ArtifactExcerptBlock>(body), excerpt.withoutLeadingTitle("方案")?.blocks)
    }

    @Test
    fun excerptKeepsOtherOpenings() {
        val h2 = ArtifactExcerpt("Scope\n\nBody", listOf(ArtifactExcerptBlock.Heading(2, words("Scope")), ArtifactExcerptBlock.Paragraph(words("Body"))))
        assertSame(h2, h2.withoutLeadingTitle("Plan"))
        val paragraph = ArtifactExcerpt("Body", listOf(ArtifactExcerptBlock.Paragraph(words("Body"))))
        assertSame(paragraph, paragraph.withoutLeadingTitle("Plan"))
        val text = ArtifactExcerpt("Body first")
        assertSame(text, text.withoutLeadingTitle("Plan"))
    }

    @Test
    fun anExcerptThatIsOnlyItsHeadingShowsNothing() {
        assertNull(ArtifactExcerpt("Plan", listOf(ArtifactExcerptBlock.Heading(1, words("Plan")))).withoutLeadingTitle("Plan"))
        assertNull(ArtifactExcerpt("Plan").withoutLeadingTitle("Plan"))
    }

    @Test
    fun plainTextDropsAFirstLineThatRepeatsTheTitle() {
        assertEquals("Body", ArtifactExcerpt("Plan\nBody").withoutLeadingTitle("Plan")?.text)
        assertEquals("Body", ArtifactExcerpt("  launch   PLAN \nBody").withoutLeadingTitle("Launch plan")?.text)
        val other = ArtifactExcerpt("Intro\nBody")
        assertSame(other, other.withoutLeadingTitle("Plan"))
    }

    @Test
    fun anH1GoesWhateverItSays() {
        val body = ArtifactExcerptBlock.Paragraph(words("Body"))
        val excerpt = ArtifactExcerpt("Other words\nBody", listOf(ArtifactExcerptBlock.Heading(1, words("Other words")), body))
        val shown = excerpt.withoutLeadingTitle("Plan")
        assertEquals(listOf<ArtifactExcerptBlock>(body), shown?.blocks)
        assertEquals("Body", shown?.text)
    }

    @Test
    fun versionOnlyWhenBehind() {
        val latest =
            ArtifactSummary(
                id = "pt_a",
                title = "Plan",
                description = null,
                kind = ArtifactKind.Markdown,
                version = 4,
                pinned = false,
                latestVersion = 4,
                home = ArtifactHome.Server,
                authorName = "Niko",
                updatedAt = null,
            )
        assertNull(artifactCardVersionBehind(latest))
        assertNull(artifactCardVersionBehind(latest.copy(pinned = true)))
        assertNull(artifactCardVersionBehind(latest.copy(version = 2)), "not pinned: the latest is shown")
        assertEquals(2 to 4, artifactCardVersionBehind(latest.copy(version = 2, pinned = true)))
    }

    @Test
    fun pictureIs120OnAPhone() {
        assertEquals(120.dp, artifactCardScreenshotMaxHeight(compact = true))
        assertEquals(192.dp, artifactCardScreenshotMaxHeight(compact = false))
    }

    @Test
    fun phoneExcerptLinesDefaultTo4WithinTwoToEight() {
        assertEquals(72.dp, artifactCardExcerptCompactMaxHeight(ArtifactCardExcerptDefaultLines))
        assertEquals(36.dp, artifactCardExcerptCompactMaxHeight(1))
        assertEquals(144.dp, artifactCardExcerptCompactMaxHeight(20))
    }
}
