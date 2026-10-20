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
}
