package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * task #512: pins the semantics ported from web
 * `packages/web/src/store/sidebarSections.ts`.
 */
class SidebarSectionsTest {
    private fun section(id: String, sortMode: String = "manual") =
        SettingsServerSidebarCustomSection(id = id, name = "Section $id", sortMode = sortMode)

    private fun placement(kind: String, id: String, sectionId: String, position: Int) =
        SettingsServerSidebarSectionPlacement(kind = kind, id = id, sectionId = sectionId, position = position)

    @Test
    fun normalizeCustomSectionsDropsMalformedEntries() {
        val normalized =
            normalizeSidebarCustomSections(
                listOf(
                    section("sec-1"),
                    section("sec-1").copy(id = ""),
                    section("sec-1").copy(name = ""),
                    section("sec-2", sortMode = "bogus"),
                    section("sec-3", sortMode = "recent"),
                )
            )
        assertEquals(listOf("sec-1", "sec-3"), normalized.map { it.id })
    }

    @Test
    fun normalizeSectionPlacementsDropsMalformedEntries() {
        val normalized =
            normalizeSidebarSectionPlacements(
                listOf(
                    placement("channel", "channel-1", "sec-1", 0),
                    placement("bogus", "channel-2", "sec-1", 1),
                    placement("channel", "", "sec-1", 2),
                    placement("agent", "agent-1", "", 0),
                    placement("agent", "agent-1", "sec-1", -1),
                    placement("agent", "agent-2", "sec-1", 3),
                )
            )
        assertEquals(
            listOf(
                placement("channel", "channel-1", "sec-1", 0),
                placement("agent", "agent-2", "sec-1", 3),
            ),
            normalized
        )
    }

    @Test
    fun normalizeSectionOrderDedupesUnknownsAndAppendsMissing() {
        val sections = listOf(section("sec-1"), section("sec-2"))
        assertEquals(
            listOf("system:dms", "sec-2", "system:pinned", "system:joint", "system:channels", "sec-1"),
            normalizeSidebarSectionOrder(
                listOf("system:dms", "sec-2", "unknown", "system:dms"),
                sections
            )
        )
    }

    @Test
    fun normalizeSectionOrderDefaultsToSystemThenCustom() {
        val sections = listOf(section("sec-1"))
        assertEquals(
            listOf("system:pinned", "system:joint", "system:channels", "system:dms", "sec-1"),
            normalizeSidebarSectionOrder(emptyList(), sections)
        )
    }

    @Test
    fun reorderSectionOrderMovesActiveToOverIndex() {
        val order = listOf("a", "b", "c", "d")
        assertEquals(listOf("a", "d", "b", "c"), reorderSidebarSectionOrder(order, "d", "b"))
        assertEquals(listOf("b", "c", "a", "d"), reorderSidebarSectionOrder(order, "a", "c"))
        assertEquals(order, reorderSidebarSectionOrder(order, "a", "a"))
        assertEquals(order, reorderSidebarSectionOrder(order, "missing", "b"))
    }

    @Test
    fun moveToCustomSectionAppendsAndReindexes() {
        val placements =
            listOf(
                placement("channel", "channel-1", "sec-1", 0),
                placement("channel", "channel-2", "sec-1", 1),
                placement("channel", "dm-1", "sec-2", 0),
            )
        val moved = moveSidebarItemToCustomSection(placements, "channel", "dm-1", "sec-1")
        assertEquals(
            listOf(
                placement("channel", "channel-1", "sec-1", 0),
                placement("channel", "channel-2", "sec-1", 1),
                placement("channel", "dm-1", "sec-1", 2),
            ),
            moved
        )
    }

    @Test
    fun moveToCustomSectionAtPositionClampsAndReindexes() {
        val placements =
            listOf(
                placement("channel", "channel-1", "sec-1", 0),
                placement("channel", "channel-2", "sec-1", 1),
                placement("channel", "dm-1", "sec-2", 0),
            )
        val moved = moveSidebarItemToCustomSectionAtPosition(placements, "channel", "dm-1", "sec-1", 0)
        assertEquals(
            listOf(
                placement("channel", "dm-1", "sec-1", 0),
                placement("channel", "channel-1", "sec-1", 1),
                placement("channel", "channel-2", "sec-1", 2),
            ),
            moved
        )
    }

    @Test
    fun moveWithinSameSectionReorders() {
        val placements =
            listOf(
                placement("channel", "channel-1", "sec-1", 0),
                placement("channel", "channel-2", "sec-1", 1),
                placement("channel", "channel-3", "sec-1", 2),
            )
        val moved = moveSidebarItemToCustomSectionAtPosition(placements, "channel", "channel-3", "sec-1", 0)
        assertEquals(
            listOf(
                placement("channel", "channel-3", "sec-1", 0),
                placement("channel", "channel-1", "sec-1", 1),
                placement("channel", "channel-2", "sec-1", 2),
            ),
            moved
        )
    }

    @Test
    fun removePlacementReturnsSameListWhenAbsent() {
        val placements = listOf(placement("channel", "channel-1", "sec-1", 0))
        assertEquals(placements, removeSidebarItemPlacement(placements, "channel", "missing"))
        assertEquals(emptyList(), removeSidebarItemPlacement(placements, "channel", "channel-1"))
    }

    @Test
    fun placementKindIdKeysAgentDmsByMemberId() {
        // LiBai review: agent-peer DMs place as agent:<memberId> (web agent
        // items); channels and human DMs place as channel:<conversationId>.
        assertEquals(
            "agent" to "member-1",
            sidebarSectionPlacementKindId(peerType = "agent", presenceMemberId = "member-1", conversationId = "dm-1")
        )
        assertEquals(
            "channel" to "dm-2",
            sidebarSectionPlacementKindId(peerType = "user", presenceMemberId = "user-1", conversationId = "dm-2")
        )
        assertEquals(
            "channel" to "channel-1",
            sidebarSectionPlacementKindId(peerType = null, presenceMemberId = null, conversationId = "channel-1")
        )
        // An agent DM whose member id never resolved still falls back to the channel key.
        assertEquals(
            "channel" to "dm-3",
            sidebarSectionPlacementKindId(peerType = "agent", presenceMemberId = null, conversationId = "dm-3")
        )
    }
}
