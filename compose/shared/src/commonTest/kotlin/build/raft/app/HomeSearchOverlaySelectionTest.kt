package build.raft.app

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The wide search panel's keyboard cursor (web `useSearchKeyboardSelection`, overlay mode). */
class HomeSearchOverlaySelectionTest {
    private val typedRows =
        homeSearchOverlayRows(
            exactKey = "conversation:design",
            searchFor = true,
            entityKeys = listOf("conversation:android", "member:dev4"),
            messageKeys = listOf("msg-1", "thread-2"),
        )

    private fun entity(key: String, title: String, threadId: String?, type: SearchEntityType = SearchEntityType.Channel, archived: Boolean = false) =
        SearchEntityResult(
            key = key,
            type = type,
            title = title,
            subtitle = "",
            threadId = threadId,
            memberIdentity = null,
            conversationIdentity = null,
            archived = archived,
        )

    @Test fun `rows run in drawing order with their sections`() {
        assertEquals(
            listOf(
                HomeSearchOverlaySection.Exact,
                HomeSearchOverlaySection.SearchFor,
                HomeSearchOverlaySection.Entities,
                HomeSearchOverlaySection.Entities,
                HomeSearchOverlaySection.Messages,
                HomeSearchOverlaySection.Messages,
            ),
            typedRows.map { it.section },
        )
        assertEquals(HomeSearchOverlaySearchForKey, typedRows[1].key)
        val home = homeSearchOverlayRows(recentKeys = listOf("recent:a", "recent:b"))
        assertEquals(listOf(HomeSearchOverlaySection.Recent, HomeSearchOverlaySection.Recent), home.map { it.section })
    }

    @Test fun `the cursor starts on the first row and Return opens it`() {
        val cursor = HomeSearchOverlayCursor()
        assertEquals(0, cursor.index(typedRows))
        assertEquals("conversation:design", cursor.selected(typedRows)?.key)
        // Without an exact destination, Return on the first row is "Search for".
        val rows = homeSearchOverlayRows(searchFor = true, entityKeys = listOf("e1"))
        assertEquals(HomeSearchOverlaySection.SearchFor, HomeSearchOverlayCursor().selected(rows)?.section)
        assertNull(HomeSearchOverlayCursor().selected(emptyList()))
        assertEquals(-1, HomeSearchOverlayCursor().index(emptyList()))
    }

    @Test fun `arrows move across sections and stop at the ends without wrapping`() {
        var cursor = HomeSearchOverlayCursor()
        cursor = cursor.move(typedRows, 1).move(typedRows, 1)
        assertEquals(HomeSearchOverlaySection.Entities, cursor.selected(typedRows)?.section)
        cursor = cursor.move(typedRows, 1).move(typedRows, 1)
        assertEquals(HomeSearchOverlaySection.Messages, cursor.selected(typedRows)?.section)
        cursor = cursor.move(typedRows, 1).move(typedRows, 1).move(typedRows, 1)
        assertEquals("thread-2", cursor.selected(typedRows)?.key, "↓ on the last row stays there (web clamps)")
        cursor = cursor.move(typedRows, -10)
        assertEquals(0, cursor.index(typedRows), "↑ on the first row stays there")
        assertEquals(HomeSearchOverlayCursor(), HomeSearchOverlayCursor().move(emptyList(), 1))
    }

    @Test fun `the cursor follows its row across result waves and resets with the query`() {
        val cursor = HomeSearchOverlayCursor("member:dev4")
        val reranked = homeSearchOverlayRows(searchFor = true, entityKeys = listOf("member:dev4", "conversation:android"))
        assertEquals("member:dev4", cursor.selected(reranked)?.key)
        val gone = homeSearchOverlayRows(searchFor = true, entityKeys = listOf("conversation:android"))
        assertEquals(0, cursor.index(gone), "a row that left puts the cursor back on top")
        assertNull(cursor.reset().selectedKey)
    }

    @Test fun `arrows move and Return opens while other keys stay with the field`() {
        assertEquals(HomeSearchOverlayKeyAction.Move(1), homeSearchOverlayKeyAction(Key.DirectionDown, keyDown = true, shift = false))
        assertEquals(HomeSearchOverlayKeyAction.Move(-1), homeSearchOverlayKeyAction(Key.DirectionUp, keyDown = true, shift = false))
        assertEquals(HomeSearchOverlayKeyAction.Open, homeSearchOverlayKeyAction(Key.Enter, keyDown = true, shift = false))
        assertEquals(HomeSearchOverlayKeyAction.Open, homeSearchOverlayKeyAction(Key.NumPadEnter, keyDown = true, shift = false))
        assertNull(homeSearchOverlayKeyAction(Key.Enter, keyDown = true, shift = true))
        assertNull(homeSearchOverlayKeyAction(Key.Enter, keyDown = false, shift = false))
        assertNull(homeSearchOverlayKeyAction(Key.DirectionDown, keyDown = true, shift = false, composing = true))
        assertNull(homeSearchOverlayKeyAction(Key.A, keyDown = true, shift = false))
    }

    @Test fun `an exact destination is the result the query names`() {
        val results = listOf(entity("c:android-artifacts", "android-artifacts", "t1"), entity("c:android", "android", "t2"))
        assertEquals("c:android", homeSearchOverlayExactKey("#Android", results))
        assertNull(homeSearchOverlayExactKey("andr", results))
        assertNull(homeSearchOverlayExactKey("  ", results))
    }

    @Test fun `recent conversations are the visits then the frequent ones without the open one`() {
        val design = entity("c:design", "design", "t-design")
        val android = entity("c:android", "android", "t-android")
        val old = entity("c:old", "old", "t-old", archived = true)
        val dm = entity("dm:artin", "artin", "t-artin", type = SearchEntityType.DirectMessage)
        val agent = entity("m:dev4", "Dev 4", null, type = SearchEntityType.Agent)
        val recent =
            homeSearchOverlayRecentConversations(
                visitedThreadIds = listOf("t-design", "t-old", "t-android", "t-design", "t-missing"),
                candidates = listOf(design, android, old, dm, agent),
                fill = listOf(android, dm),
                excludeThreadId = "t-android",
            )
        assertEquals(listOf("c:design", "dm:artin"), recent.map { it.key })
        val capped =
            homeSearchOverlayRecentConversations(
                visitedThreadIds = listOf("t-design", "t-artin"),
                candidates = listOf(design, dm),
                fill = listOf(android),
                excludeThreadId = null,
                limit = 2,
            )
        assertEquals(listOf("c:design", "dm:artin"), capped.map { it.key })
    }

    @Test fun `Cmd K on Apple platforms and Ctrl K elsewhere open search`() {
        assertTrue(raftSearchShortcut(keyDown = true, key = Key.K, meta = true, ctrl = false, alt = false, shift = false, apple = true))
        assertTrue(raftSearchShortcut(keyDown = true, key = Key.K, meta = false, ctrl = true, alt = false, shift = false, apple = false))
        assertFalse(raftSearchShortcut(keyDown = true, key = Key.K, meta = false, ctrl = true, alt = false, shift = false, apple = true))
        assertFalse(raftSearchShortcut(keyDown = true, key = Key.K, meta = true, ctrl = false, alt = false, shift = true, apple = true))
        assertFalse(raftSearchShortcut(keyDown = false, key = Key.K, meta = true, ctrl = false, alt = false, shift = false, apple = true))
        assertEquals("slockSearchShortcut", RAFT_SEARCH_SHORTCUT_EVENT)
    }
}
