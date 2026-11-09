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
    fun nothingNewShowsNothing() {
        assertNull(homeServerSwitcherRowUnread(other.copy(activityUnreadCount = 0L, broaderUnreadCount = 5), current = false))
    }

    @Test
    fun theNativeTitleLeadsWithTheServerTileAndOnlyAnHttpImage() {
        assertEquals(
            RaftNativeNavBarTitleLeading.Server(label = "A", imageUrl = null),
            homeNativeNavBarServerLeading(other)
        )
        assertEquals(
            RaftNativeNavBarTitleLeading.Server(label = "A", imageUrl = "https://cdn.example/a.png"),
            homeNativeNavBarServerLeading(other.copy(avatarUrl = "https://cdn.example/a.png"))
        )
        assertNull(homeNativeNavBarServerLeading(other.copy(avatarUrl = "pixel:abc")).imageUrl)
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
}
