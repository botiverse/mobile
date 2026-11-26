package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The Home server switcher's rows (web ServerRowUnread) and the iOS native Home title that opens it. */
class HomeServerSwitcherRowTest {
    private val other = RaftServerSwitcherItem(id = "s2", name = "Acme Labs", slug = "acme", activityUnreadCount = 3L)

    @Test
    fun theCurrentServerShowsNoCountOfItsOwn() {
        assertNull(homeServerSwitcherRowUnread(other, current = true))
        assertEquals(HomeServerSwitcherRowUnread(3, muted = false), homeServerSwitcherRowUnread(other, current = false))
    }

    @Test
    fun aMutedServerKeepsItsCountMarkedMuted() {
        assertEquals(
            HomeServerSwitcherRowUnread(16, muted = true),
            homeServerSwitcherRowUnread(other.copy(activityUnreadCount = 16L, serverPushMuted = true), current = false)
        )
    }

    @Test
    fun theNativeTitleTapOpensThePageMenuWithAChevronInsteadOfASystemMenu() {
        val bridge = RaftNativeNavBarBridge()
        var extras: JsonObject? = null
        var titleMenu: String? = null
        bridge.attachListener(
            object : RaftNativeNavBarListener {
                override fun onNativeNavBarChanged(
                    title: String,
                    subtitle: String?,
                    actionsJson: String,
                    extrasJson: String,
                    titleMenuJson: String,
                    hasBack: Boolean,
                    nested: Boolean,
                    titleImage: RaftNativeTabBarAvatar?,
                ) {
                    extras = Json.parseToJsonElement(extrasJson).jsonObject
                    titleMenu = titleMenuJson
                }

                override fun onNativeNavBarCleared() = Unit

                override fun onNativeNavBarScroll(offset: Double, contentHeight: Double, dragging: Boolean) = Unit
            }
        )
        var toggles = 0
        val entry = RaftNativeNavBarBridge.Entry()
        bridge.activate(entry)
        bridge.publish(
            entry,
            RaftNativeNavBarHead(
                title = "Acme Labs",
                subtitle = null,
                actions = emptyList(),
                extras =
                    RaftNativeNavBarTitleExtras(
                        leading = homeNativeNavBarServerLeading(other),
                        chevron = RaftNativeNavBarTitleChevron.Down,
                    ),
                onTitleClick = { toggles += 1 },
            )
        )
        val published = extras!!
        assertEquals("[]", titleMenu)
        assertEquals("down", published["chevron"]?.jsonPrimitive?.content)
        assertEquals("true", published["titleTap"]?.jsonPrimitive?.content)
        val leading = published["leading"]!!.jsonObject
        assertEquals("server", leading["kind"]?.jsonPrimitive?.content)
        assertEquals("A", leading["label"]?.jsonPrimitive?.content)
        assertTrue(bridge.actionTapped(RaftNativeNavBarTitleId))
        assertEquals(1, toggles)
    }

    @Test
    fun initialScrollKeepsRowsInsideInitialViewportAtTop() {
        assertEquals(
            HomeServerSwitcherInitialScroll(firstVisibleItemIndex = 0, firstVisibleItemScrollOffsetPx = 0),
            homeServerSwitcherInitialScroll(selectedIndex = 0, serverCount = 12, rowHeightPx = 96),
        )
        assertEquals(
            HomeServerSwitcherInitialScroll(firstVisibleItemIndex = 0, firstVisibleItemScrollOffsetPx = 0),
            homeServerSwitcherInitialScroll(selectedIndex = 5, serverCount = 12, rowHeightPx = 96),
        )
        assertEquals(
            HomeServerSwitcherInitialScroll(firstVisibleItemIndex = 0, firstVisibleItemScrollOffsetPx = 0),
            homeServerSwitcherInitialScroll(selectedIndex = 4, serverCount = 6, rowHeightPx = 96),
        )
        assertEquals(
            HomeServerSwitcherInitialScroll(firstVisibleItemIndex = 0, firstVisibleItemScrollOffsetPx = 0),
            homeServerSwitcherInitialScroll(selectedIndex = -1, serverCount = 12, rowHeightPx = 96),
        )
    }

    @Test
    fun initialScrollCentersBelowFoldServerAndClampsLastServerInView() {
        // 15 servers, 6 visible rows, 100px per row (viewport = 600px, maxOffset = 900px).
        // Row 9 sits at y=900..1000 -> centered offset = 900 - 250 = 650 -> item 6, offset 50px.
        assertEquals(
            HomeServerSwitcherInitialScroll(firstVisibleItemIndex = 6, firstVisibleItemScrollOffsetPx = 50),
            homeServerSwitcherInitialScroll(selectedIndex = 9, serverCount = 15, rowHeightPx = 100),
        )
        // Last server (row 14) clamps to maxOffset = 900px -> item 9, offset 0px (rows 9..14 visible).
        assertEquals(
            HomeServerSwitcherInitialScroll(firstVisibleItemIndex = 9, firstVisibleItemScrollOffsetPx = 0),
            homeServerSwitcherInitialScroll(selectedIndex = 14, serverCount = 15, rowHeightPx = 100),
        )
        // Row 6 of 8 (maxOffset = 200px) clamps cleanly to item 2, offset 0px (rows 2..7 visible).
        assertEquals(
            HomeServerSwitcherInitialScroll(firstVisibleItemIndex = 2, firstVisibleItemScrollOffsetPx = 0),
            homeServerSwitcherInitialScroll(selectedIndex = 6, serverCount = 8, rowHeightPx = 100),
        )
    }
}
