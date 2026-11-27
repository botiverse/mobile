package build.raft.app

import androidx.compose.ui.text.LinkAnnotation
import build.raft.app.data.ArtifactDeletedShown
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.ArtifactSummaryAnswer
import build.raft.app.data.profileDescriptionPieces
import build.raft.app.data.resolveProfileDescriptionSegments
import build.raft.app.markdown.raftInlineBoxRanges
import build.raft.app.markdown.raftTextWithoutInlineGaps
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-Kotlin tests for [buildRaftProfileDescriptionAnnotatedContent] (botiverse/slock #14969 and
 * #15230): chip fill colors, title capping, unreadable/loading fallback clickable chips, deleted
 * non-clickable chips, and non-chip body click routing.
 */
class RaftProfileDescriptionChipsTest {
    private val hosts = listOf("app.raft.build")
    private val id = "TUCt9hmScMEdBpHaTXatxr"
    private val url = "https://app.raft.build/artifacts/$id"
    private val target = ArtifactLinkTarget(id)

    private val summary =
        ArtifactSummary(
            id = target.canonicalId,
            title = "Stamp 工作清单",
            description = null,
            kind = ArtifactKind.Markdown,
            version = 1,
            pinned = false,
            latestVersion = 1,
            home = ArtifactHome.Server,
        )

    private fun buildContent(
        text: String,
        answers: Map<String, ArtifactSummaryAnswer>,
        opened: MutableList<Pair<ArtifactLinkTarget, ArtifactSummary?>> = mutableListOf(),
        onTextClick: (() -> Unit)? = null,
        interactive: Boolean = true,
    ): RaftProfileDescriptionAnnotatedContent {
        val segments =
            resolveProfileDescriptionSegments(
                pieces = profileDescriptionPieces(text),
                appHosts = hosts,
                answers = answers,
                enabled = true,
            )
        return buildRaftProfileDescriptionAnnotatedContent(
            segments = segments,
            placeholder = "Artifact",
            deletedLabel = "deleted",
            compact = false,
            interactive = interactive,
            onOpenArtifact = { t, s -> opened += t to s },
            onTextClick = onTextClick,
        )
    }

    private fun RaftProfileDescriptionAnnotatedContent.readable(): String =
        text.raftTextWithoutInlineGaps().replace('\u00A0', ' ')

    @Test
    fun availableArtifactRendersCyanChipAndRoutesChipVsSurroundingTextClicks() {
        val opened = mutableListOf<Pair<ArtifactLinkTarget, ArtifactSummary?>>()
        var textClicks = 0
        val content =
            buildContent(
                text = "工作清单：$url，代码：https://github.com/botiverse/slock",
                answers = mapOf(target.key to ArtifactSummaryAnswer.Ok(summary)),
                opened = opened,
                onTextClick = { textClicks += 1 },
            )

        assertEquals("工作清单：Stamp 工作清单，代码：https://github.com/botiverse/slock", content.readable())
        assertFalse(content.readable().contains(url))
        assertEquals(RaftColor.InfoSoft, content.text.raftInlineBoxRanges().single().item.backgroundColor)
        assertEquals(1, content.interactiveChipRanges.size)
        assertTrue(content.unavailableSpans.isEmpty())

        val links = content.text.getLinkAnnotations(0, content.text.length)
        val chipLink = links.first { (it.item as? LinkAnnotation.Clickable)?.tag == RaftProfileDescriptionArtifactTag }.item as LinkAnnotation.Clickable
        chipLink.linkInteractionListener!!.onClick(chipLink)
        assertEquals(listOf<Pair<ArtifactLinkTarget, ArtifactSummary?>>(target to summary), opened.toList())
        assertEquals(0, textClicks)

        val bodyLink = links.first { (it.item as? LinkAnnotation.Clickable)?.tag != RaftProfileDescriptionArtifactTag }.item as LinkAnnotation.Clickable
        bodyLink.linkInteractionListener!!.onClick(bodyLink)
        assertEquals(1, textClicks)
        assertEquals(1, opened.size)
    }

    @Test
    fun unavailableAndLoadingArtifactsRenderPlaceholderChipWithoutUrlAndStillOpenOnTap() {
        val openedUnavailable = mutableListOf<Pair<ArtifactLinkTarget, ArtifactSummary?>>()
        val unavailable =
            buildContent(
                text = "清单：$url",
                answers = mapOf(target.key to ArtifactSummaryAnswer.Unavailable),
                opened = openedUnavailable,
            )
        assertEquals("清单：Artifact", unavailable.readable())
        assertFalse(unavailable.readable().contains(url))
        assertEquals(RaftColor.InactiveSoft, unavailable.text.raftInlineBoxRanges().single().item.backgroundColor)
        assertEquals(1, unavailable.unavailableSpans.size)
        val unavailableLink =
            unavailable.text
                .getLinkAnnotations(0, unavailable.text.length)
                .first { (it.item as? LinkAnnotation.Clickable)?.tag == RaftProfileDescriptionArtifactUnavailableTag }
                .item as LinkAnnotation.Clickable
        unavailableLink.linkInteractionListener!!.onClick(unavailableLink)
        assertEquals(listOf<Pair<ArtifactLinkTarget, ArtifactSummary?>>(target to null), openedUnavailable.toList())

        val openedPending = mutableListOf<Pair<ArtifactLinkTarget, ArtifactSummary?>>()
        val pending =
            buildContent(
                text = "清单：$url",
                answers = emptyMap(),
                opened = openedPending,
            )
        assertEquals("清单：Artifact", pending.readable())
        assertFalse(pending.readable().contains(url))
        assertEquals(RaftColor.InactiveSoft, pending.text.raftInlineBoxRanges().single().item.backgroundColor)
        assertTrue(pending.unavailableSpans.isEmpty(), "loading chip has no unavailable hover card")
        val pendingLink =
            pending.text
                .getLinkAnnotations(0, pending.text.length)
                .first { (it.item as? LinkAnnotation.Clickable)?.tag == RaftProfileDescriptionArtifactPendingTag }
                .item as LinkAnnotation.Clickable
        pendingLink.linkInteractionListener!!.onClick(pendingLink)
        assertEquals(listOf<Pair<ArtifactLinkTarget, ArtifactSummary?>>(target to null), openedPending.toList())
    }

    @Test
    fun deletedArtifactShowsTitleAndDeletedSuffixAndIsNotAnInteractiveArtifactChip() {
        val opened = mutableListOf<Pair<ArtifactLinkTarget, ArtifactSummary?>>()
        var textClicks = 0
        val deleted = ArtifactDeletedShown(title = "Old list", deletedBy = null, deletedAt = null)
        val content =
            buildContent(
                text = "清单：$url",
                answers = mapOf(target.key to ArtifactSummaryAnswer.Deleted(deleted)),
                opened = opened,
                onTextClick = { textClicks += 1 },
            )
        assertEquals("清单：Old list deleted", content.readable())
        assertTrue(content.interactiveChipRanges.isEmpty())
        assertTrue(content.unavailableSpans.isEmpty())
        val artifactLinks =
            content.text.getLinkAnnotations(0, content.text.length).filter {
                val tag = (it.item as? LinkAnnotation.Clickable)?.tag
                tag == RaftProfileDescriptionArtifactTag ||
                    tag == RaftProfileDescriptionArtifactPendingTag ||
                    tag == RaftProfileDescriptionArtifactUnavailableTag
            }
        assertTrue(artifactLinks.isEmpty())
    }

    @Test
    fun nonInteractiveModeProducesNoLinksForShareSelection() {
        val content =
            buildContent(
                text = "清单：$url",
                answers = mapOf(target.key to ArtifactSummaryAnswer.Ok(summary)),
                onTextClick = {},
                interactive = false,
            )
        assertEquals("清单：Stamp 工作清单", content.readable())
        assertTrue(content.text.getLinkAnnotations(0, content.text.length).isEmpty())
    }
}
