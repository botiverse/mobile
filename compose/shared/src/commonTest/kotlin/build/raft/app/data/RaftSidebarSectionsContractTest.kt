package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Web sidebar-section parity: the GET/PATCH `/servers/:id/sidebar-order`
 * wire mapping for the joint-channel sort mode, the new-section order
 * insertion, and the device-local "Hide Empty Sections" preference.
 */
class RaftSidebarSectionsContractTest {

    @Test
    fun sidebarOrderParsesTheSeparateJointChannelSortMode() {
        val order =
            parseSharedSettingsSidebarOrder(
                """{"channelSortMode":"recent","jointChannelSortMode":"az","customSections":[],"sectionOrder":[],"sectionPlacements":[],"sectionsVersion":2}"""
            )
        assertEquals("recent", order?.channelSortMode)
        assertEquals("az", order?.jointChannelSortMode)
        // Older servers without the field fall back to manual, like web.
        assertEquals("manual", parseSharedSettingsSidebarOrder("""{"channelSortMode":"az"}""")?.jointChannelSortMode)
    }

    @Test
    fun sidebarOrderPatchBodyCarriesJointChannelSortModeOnlyWhenSet() {
        assertEquals(
            """{"jointChannelSortMode":"recent"}""",
            buildSharedSettingsServerSidebarOrderUpdateBody(
                SettingsServerSidebarOrderUpdateRequest(jointChannelSortMode = "recent")
            )
        )
        assertFalse(
            buildSharedSettingsServerSidebarOrderUpdateBody(
                SettingsServerSidebarOrderUpdateRequest(channelSortMode = "az")
            ).contains("jointChannelSortMode")
        )
    }

    @Test
    fun sectionMoveAndCreatePatchBodyUsesWebFieldNames() {
        val body =
            buildSharedSettingsServerSidebarOrderUpdateBody(
                SettingsServerSidebarOrderUpdateRequest(
                    pinned = emptyList(),
                    customSections = listOf(SettingsServerSidebarCustomSection(id = "sec-1", name = "Mobile", emoji = null, sortMode = "manual")),
                    sectionOrder = listOf("system:pinned", "sec-1", "system:joint", "system:channels", "system:dms"),
                    sectionPlacements = listOf(SettingsServerSidebarSectionPlacement(kind = "channel", id = "c-1", sectionId = "sec-1", position = 0)),
                    sectionsVersion = 4
                )
            )
        assertTrue(body.contains(""""customSections":[{"id":"sec-1","name":"Mobile","emoji":null,"sortMode":"manual"}]""") ||
            body.contains(""""customSections":[{"id":"sec-1","name":"Mobile","sortMode":"manual"}]"""), body)
        assertTrue(body.contains(""""sectionPlacements":[{"kind":"channel","id":"c-1","sectionId":"sec-1","position":0}]"""), body)
        assertTrue(body.contains(""""sectionsVersion":4"""), body)
        assertTrue(body.contains(""""pinned":[]"""), body)
    }

    @Test
    fun newSectionGoesBeforeTheMenuSectionOrAtTheEnd() {
        val existing = listOf(SettingsServerSidebarCustomSection(id = "sec-a", name = "A"))
        val order = listOf("system:pinned", "sec-a", "system:joint", "system:channels", "system:dms")
        assertEquals(
            listOf("system:pinned", "sec-a", "system:joint", "new", "system:channels", "system:dms"),
            insertSidebarSectionIntoOrder(order, existing, "new", insertBeforeSectionId = "system:channels")
        )
        assertEquals(
            listOf("system:pinned", "new", "sec-a", "system:joint", "system:channels", "system:dms"),
            insertSidebarSectionIntoOrder(order, existing, "new", insertBeforeSectionId = "sec-a")
        )
        assertEquals(order + "new", insertSidebarSectionIntoOrder(order, existing, "new", insertBeforeSectionId = null))
        // An unknown anchor appends; a partial stored order is normalized first.
        assertEquals(
            listOf("system:dms", "system:pinned", "system:joint", "system:channels", "sec-a", "new"),
            insertSidebarSectionIntoOrder(listOf("system:dms"), existing, "new", insertBeforeSectionId = "gone")
        )
    }

    @Test
    fun hideEmptySectionsPreferenceUsesWebStorageKeyAndMobileDefault() {
        val backing = mutableMapOf<String, String?>()
        val prefs =
            object : SharedPreferenceStore {
                override fun getString(key: String): String? = backing[key]
                override fun putString(key: String, value: String?) {
                    backing[key] = value
                }
            }
        val store = KeyValueRaftSidebarHideEmptySectionsStore(prefs)
        assertEquals(RaftSidebarHideEmptySectionsDefault, store.read())
        store.write(false)
        assertEquals("false", backing["slock:sidebarHideEmptySections"])
        assertFalse(store.read())
        store.write(true)
        assertTrue(store.read())
    }

    @Test
    fun normalizeCustomSectionsAndPlacementsDeduplicatesByIdentity() {
        val sections =
            normalizeSidebarCustomSections(
                listOf(
                    SettingsServerSidebarCustomSection(id = "sec-1", name = "First", sortMode = "manual"),
                    SettingsServerSidebarCustomSection(id = "sec-1", name = "Duplicate", sortMode = "az"),
                    SettingsServerSidebarCustomSection(id = "sec-2", name = "Second", sortMode = "recent")
                )
            )
        assertEquals(listOf("First", "Second"), sections.map { it.name })

        val placements =
            normalizeSidebarSectionPlacements(
                listOf(
                    SettingsServerSidebarSectionPlacement(kind = "channel", id = "c-1", sectionId = "sec-1", position = 0),
                    SettingsServerSidebarSectionPlacement(kind = "channel", id = "c-1", sectionId = "sec-2", position = 1),
                    SettingsServerSidebarSectionPlacement(kind = "agent", id = "a-1", sectionId = "sec-1", position = 2)
                )
            )
        assertEquals(
            listOf(
                SettingsServerSidebarSectionPlacement(kind = "channel", id = "c-1", sectionId = "sec-1", position = 0),
                SettingsServerSidebarSectionPlacement(kind = "agent", id = "a-1", sectionId = "sec-1", position = 2)
            ),
            placements
        )
    }

    @Test
    fun resolveSidebarPlacementSectionIdPrefersLiveCanonicalAgentKeyOverLegacyChannelKey() {
        val validSections = setOf("sec-old", "sec-new")
        val dualPlacements =
            listOf(
                SettingsServerSidebarSectionPlacement(kind = "channel", id = "dm-agent-1", sectionId = "sec-old", position = 0),
                SettingsServerSidebarSectionPlacement(kind = "agent", id = "agent-1", sectionId = "sec-new", position = 1)
            )
        assertEquals(
            "sec-new",
            resolveSidebarPlacementSectionId(
                placements = dualPlacements,
                peerType = "agent",
                presenceMemberId = "agent-1",
                conversationId = "dm-agent-1",
                validSectionIds = validSections
            )
        )
        // Legacy-only channel placement still resolves for an agent DM.
        assertEquals(
            "sec-old",
            resolveSidebarPlacementSectionId(
                placements = listOf(dualPlacements.first()),
                peerType = "agent",
                presenceMemberId = "agent-1",
                conversationId = "dm-agent-1",
                validSectionIds = validSections
            )
        )
        // Orphaned canonical placement falls back to live legacy placement, and orphan-only is still returned so Home can clear it.
        val orphanCanonical =
            listOf(
                SettingsServerSidebarSectionPlacement(kind = "agent", id = "agent-1", sectionId = "deleted", position = 0),
                SettingsServerSidebarSectionPlacement(kind = "channel", id = "dm-agent-1", sectionId = "sec-old", position = 1)
            )
        assertEquals(
            "sec-old",
            resolveSidebarPlacementSectionId(
                placements = orphanCanonical,
                peerType = "agent",
                presenceMemberId = "agent-1",
                conversationId = "dm-agent-1",
                validSectionIds = validSections
            )
        )
        assertEquals(
            "deleted",
            resolveSidebarPlacementSectionId(
                placements = listOf(orphanCanonical.first()),
                peerType = "agent",
                presenceMemberId = "agent-1",
                conversationId = "dm-agent-1",
                validSectionIds = validSections
            )
        )
    }
}
