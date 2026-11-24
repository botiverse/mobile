package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import build.raft.app.data.ArtifactDeletedShown
import build.raft.app.data.ArtifactFacts
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.ArtifactSummaryAnswer
import build.raft.app.data.artifactWebUrl
import build.raft.app.links.planRaftMessageLinks
import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
    fun okAndDeletedAreDrawnInMessageOrderUnavailableAndFailedAddNothing() {
        val content =
            "First https://app.raft.build/artifacts/$b then https://app.raft.build/artifacts/$a/v/2, " +
                "hidden https://app.raft.build/artifacts/$c and https://app.raft.build/artifacts/pt_failed " +
                "and https://app.raft.build/artifacts/$b again"
        val targets = planRaftMessageLinks(content, hosts).objectTargets
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
    fun anUnpinnedCardOpensTheLatest() {
        assertEquals(ArtifactLinkTarget(a, null), summary(version = 4).openTarget())
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
    fun artifactCardMenuItemsMatchMobileOrderIconsAndSeparatorWithoutSettle() {
        val items = artifactCardMenuItems()
        assertEquals(
            listOf(
                ArtifactCardMenuItemSpec(
                    action = ArtifactCardMenuAction.Open,
                    label = Res.string.common_open,
                    icon = RaftLucideIconName.PanelRightOpen,
                    testTag = ArtifactCardMenuTags.Open,
                    dividerBefore = false,
                ),
                ArtifactCardMenuItemSpec(
                    action = ArtifactCardMenuAction.CopyLink,
                    label = Res.string.artifacts_menu_copy_link,
                    icon = RaftLucideIconName.Link,
                    testTag = ArtifactCardMenuTags.CopyLink,
                    dividerBefore = true,
                ),
            ),
            items,
        )

        val withUnfollow = artifactCardMenuItems(following = true, showFollow = true)
        assertEquals(
            listOf(ArtifactCardMenuAction.Open, ArtifactCardMenuAction.CopyLink, ArtifactCardMenuAction.Follow),
            withUnfollow.map { it.action },
        )
        assertEquals(Res.string.artifacts_menu_unfollow, withUnfollow[2].label)
        assertEquals(RaftLucideIconName.UnfollowArtifact, withUnfollow[2].icon)
        assertFalse(withUnfollow[2].dividerBefore, "Follow/Unfollow shares the Copy link group without a separator")
        assertTrue(withUnfollow[2].enabled)

        val withPendingFollow = artifactCardMenuItems(following = false, showFollow = true, followPending = true)
        assertEquals(Res.string.artifacts_menu_follow, withPendingFollow[2].label)
        assertEquals(RaftLucideIconName.FollowArtifact, withPendingFollow[2].icon)
        assertFalse(withPendingFollow[2].enabled)

        // Unknown following state or showFollow = false omits the Follow item.
        assertEquals(2, artifactCardMenuItems(following = null, showFollow = true).size)
        assertEquals(2, artifactCardMenuItems(following = true, showFollow = false).size)
    }

    @Test
    fun artifactCardContextMenuPointerAndKeyboardGatesFollowWebRules() {
        assertTrue(artifactCardOpensContextMenuForPointer(PointerType.Mouse, secondaryButtonPressed = true, pressStarted = true, menuEnabled = true))
        assertTrue(artifactCardOpensContextMenuForPointer(PointerType.Stylus, secondaryButtonPressed = true, pressStarted = true, menuEnabled = true))
        assertFalse(artifactCardOpensContextMenuForPointer(PointerType.Touch, secondaryButtonPressed = true, pressStarted = true, menuEnabled = true))
        assertFalse(artifactCardOpensContextMenuForPointer(PointerType.Mouse, secondaryButtonPressed = false, pressStarted = true, menuEnabled = true))
        assertFalse(artifactCardOpensContextMenuForPointer(PointerType.Mouse, secondaryButtonPressed = true, pressStarted = false, menuEnabled = true))
        assertFalse(artifactCardOpensContextMenuForPointer(PointerType.Mouse, secondaryButtonPressed = true, pressStarted = true, menuEnabled = false))
        assertFalse(
            artifactCardOpensContextMenuForPointer(
                PointerType.Mouse,
                secondaryButtonPressed = true,
                pressStarted = true,
                menuEnabled = true,
                hasTextSelectionInCard = true,
            ),
        )

        assertTrue(artifactCardOpensContextMenuForKey(KeyEventType.KeyDown, Key.Menu, shiftPressed = false, menuEnabled = true))
        assertTrue(artifactCardOpensContextMenuForKey(KeyEventType.KeyDown, Key.F10, shiftPressed = true, menuEnabled = true))
        assertFalse(artifactCardOpensContextMenuForKey(KeyEventType.KeyDown, Key.F10, shiftPressed = false, menuEnabled = true))
        assertFalse(artifactCardOpensContextMenuForKey(KeyEventType.KeyUp, Key.Menu, shiftPressed = false, menuEnabled = true))
        assertFalse(artifactCardOpensContextMenuForKey(KeyEventType.KeyDown, Key.Menu, shiftPressed = false, menuEnabled = false))
    }

    @Test
    fun artifactCardMenuAnchorsClampInsideCardAndCopyLinkFollowsPinnedTarget() {
        assertEquals(IntOffset(12, 34), artifactCardPointerMenuAnchorPx(Offset(12.2f, 33.8f), IntSize(200, 100)))
        assertEquals(IntOffset(199, 0), artifactCardPointerMenuAnchorPx(Offset(250f, -10f), IntSize(200, 100)))
        assertEquals(IntOffset(24, 24), artifactCardKeyboardMenuAnchorPx(IntSize(200, 100), insetPx = 24))
        assertEquals(IntOffset(10, 8), artifactCardKeyboardMenuAnchorPx(IntSize(20, 16), insetPx = 24))

        assertEquals(
            "https://staging.raft.build/artifacts/$a",
            artifactWebUrl("https://staging.raft.build", summary(version = 4, pinned = false).openTarget()),
        )
        assertEquals(
            "https://staging.raft.build/artifacts/$a/v/2",
            artifactWebUrl("https://staging.raft.build", summary(version = 2, pinned = true).openTarget()),
        )
    }
}
