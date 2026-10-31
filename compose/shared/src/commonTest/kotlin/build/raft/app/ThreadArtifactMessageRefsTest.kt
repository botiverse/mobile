package build.raft.app

import build.raft.app.data.ArtifactDeletedShown
import build.raft.app.data.ArtifactFacts
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.ArtifactSummaryAnswer
import build.raft.app.data.artifactMessageLinkTargets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The cards under a message (web `ArtifactMessageRefs.tsx` `artifactLinkOf` + the default export,
 * `artifactFacts.ts` `formatArtifactFacts` / `formatArtifactCardFacts`; botiverse/slock staging
 * 84e41d9be).
 */
class ThreadArtifactMessageRefsTest {
    private val hosts = listOf("app.raft.build")
    private val a = "pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11"
    private val b = "pt_7f3c2a10-5b9e-4d61-8c2f-1a2b3c4d5e6f"
    private val c = "pt_11111111-2222-4333-8444-555555555555"

    private fun summary(
        id: String = a,
        kind: ArtifactKind = ArtifactKind.Html,
        version: Int = 4,
        pinned: Boolean = false,
        latestVersion: Int = 4,
        facts: ArtifactFacts = ArtifactFacts(sizeBytes = 2048),
    ) = ArtifactSummary(
        id = id,
        title = "Q3 report",
        description = null,
        kind = kind,
        version = version,
        pinned = pinned,
        latestVersion = latestVersion,
        home = ArtifactHome.Conversation("proj-workspace", dm = false),
        facts = facts,
    )

    private val deleted = ArtifactDeletedShown("Old plan", "Martin", "2026-10-30T10:00:00Z")

    @Test
    fun noReferencesDrawNothing() {
        assertEquals(ThreadArtifactMessageRefsView.None, threadArtifactMessageRefsView(emptyList(), emptyMap()))
    }

    @Test
    fun anyReferenceWithoutAnAnswerIsOneSkeleton() {
        val targets = listOf(ArtifactLinkTarget(a), ArtifactLinkTarget(b))
        val view = threadArtifactMessageRefsView(targets, mapOf(a to ArtifactSummaryAnswer.Ok(summary())))
        assertEquals(ThreadArtifactMessageRefsView.Loading, view)
    }

    @Test
    fun okAndDeletedAreDrawnInMessageOrderUnavailableAndFailedAddNothing() {
        val content =
            "First https://app.raft.build/artifacts/$b then https://app.raft.build/artifacts/$a/v/2, " +
                "hidden https://app.raft.build/artifacts/$c and https://app.raft.build/artifacts/pt_failed " +
                "and https://app.raft.build/artifacts/$b again"
        val targets = artifactMessageLinkTargets(content, hosts)
        assertEquals(listOf(b, "$a;version=2", c, "pt_failed"), targets.map { it.key }, "each reference once, in order")
        val pinned = summary(version = 2, pinned = true)
        val view =
            threadArtifactMessageRefsView(
                targets,
                mapOf(
                    b to ArtifactSummaryAnswer.Deleted(deleted),
                    "$a;version=2" to ArtifactSummaryAnswer.Ok(pinned),
                    c to ArtifactSummaryAnswer.Unavailable,
                    "pt_failed" to ArtifactSummaryAnswer.Failed,
                ),
            )
        val refs = assertIs<ThreadArtifactMessageRefsView.Cards>(view).refs
        assertEquals(2, refs.size)
        assertEquals(deleted, assertIs<ThreadArtifactMessageRef.Deleted>(refs[0]).deleted)
        val card = assertIs<ThreadArtifactMessageRef.Card>(refs[1])
        assertEquals(pinned, card.summary)
        assertEquals("$a@2#1", card.key)
        assertEquals(ArtifactLinkTarget(a, 2), card.summary.openTarget(), "a pinned card opens its version")
    }

    @Test
    fun onlyUnavailableOrFailedDrawsNothing() {
        val targets = listOf(ArtifactLinkTarget(a), ArtifactLinkTarget(b))
        val view = threadArtifactMessageRefsView(targets, mapOf(a to ArtifactSummaryAnswer.Unavailable, b to ArtifactSummaryAnswer.Failed))
        assertEquals(ThreadArtifactMessageRefsView.None, view)
    }

    @Test
    fun anUnpinnedCardOpensTheLatest() {
        assertEquals(ArtifactLinkTarget(a, null), summary(version = 4).openTarget())
    }

    @Test
    fun factLinePerKind() {
        assertEquals(emptyList(), artifactCardFactParts(summary(kind = ArtifactKind.Html)), "HTML: its description is the line")
        assertEquals(emptyList(), artifactCardFactParts(summary(kind = ArtifactKind.Markdown)))
        assertEquals(
            listOf(ArtifactFactPart.Pages(6)),
            artifactCardFactParts(summary(kind = ArtifactKind.Pdf, facts = ArtifactFacts(sizeBytes = 9, pages = 6))),
        )
        assertEquals(
            listOf(ArtifactFactPart.RowsColumns(5, 4), ArtifactFactPart.ColumnNames(listOf("Channel", "September"))),
            artifactCardFactParts(
                summary(kind = ArtifactKind.Csv, facts = ArtifactFacts(rows = 5, columns = 4, columnNames = listOf("Channel", "September"))),
            ),
        )
        assertEquals(listOf(ArtifactFactPart.Rows(5)), artifactCardFactParts(summary(kind = ArtifactKind.Csv, facts = ArtifactFacts(rows = 5))))
        assertEquals(
            listOf(ArtifactFactPart.Pixels(1600, 1000)),
            artifactCardFactParts(summary(kind = ArtifactKind.Image, facts = ArtifactFacts(width = 1600, height = 1000))),
        )
        assertEquals(listOf(ArtifactFactPart.Lines(214)), artifactCardFactParts(summary(kind = ArtifactKind.Text, facts = ArtifactFacts(lines = 214))))
        // Nothing better known: the size.
        assertEquals(
            listOf(ArtifactFactPart.Size(3_000)),
            artifactCardFactParts(summary(kind = ArtifactKind.Image, facts = ArtifactFacts(sizeBytes = 3_000, width = 0, height = 10))),
        )
        assertEquals(listOf(ArtifactFactPart.Size(10)), artifactCardFactParts(summary(kind = ArtifactKind.Video, facts = ArtifactFacts(sizeBytes = 10))))
    }

    @Test
    fun aPinnedCardBehindTheLatestSaysSoFirst() {
        assertEquals(
            listOf(ArtifactFactPart.LatestIs(5), ArtifactFactPart.Pages(2)),
            artifactCardFactParts(summary(kind = ArtifactKind.Pdf, version = 3, pinned = true, latestVersion = 5, facts = ArtifactFacts(pages = 2))),
        )
        assertEquals(listOf(ArtifactFactPart.LatestIs(5)), artifactCardFactParts(summary(version = 3, pinned = true, latestVersion = 5)))
        assertEquals(emptyList(), artifactCardFactParts(summary(version = 3, pinned = false, latestVersion = 5)), "unpinned shows the latest")
    }

    @Test
    fun sizesAsTheWebFormatsThem() {
        assertEquals("512" to 0, artifactSizeValue(512))
        assertEquals("2" to 1, artifactSizeValue(2048))
        assertEquals("1000" to 1, artifactSizeValue(1_023_999))
        assertEquals("2.5" to 2, artifactSizeValue(2_621_440))
        assertEquals("1.0" to 2, artifactSizeValue(1024 * 1024))
    }
}
