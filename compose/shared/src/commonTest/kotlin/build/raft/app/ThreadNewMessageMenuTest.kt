package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.putBoolean
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadNewMessageMenuTest {
    @Test
    fun compactToolRowKeepsSixActionsVisibleAndOverflowOutOfThePrimaryActions() {
        assertEquals(
            listOf(
                ThreadNewMessageMenuTool.Copy,
                ThreadNewMessageMenuTool.Reply,
                ThreadNewMessageMenuTool.Forward,
                ThreadNewMessageMenuTool.Save,
                ThreadNewMessageMenuTool.Translate,
                ThreadNewMessageMenuTool.Select
            ),
            ThreadNewMessageMenuToolOrder
        )
    }

    @Test
    fun compactMenuIsOptInAndLocalSendStatesKeepTheClassicActions() {
        assertEquals(
            ThreadMessageMenuVariant.Classic,
            threadMessageMenuVariant(
                newMessageMenuEnabled = false,
                hasLocalSendState = false
            )
        )
        assertEquals(
            ThreadMessageMenuVariant.Compact,
            threadMessageMenuVariant(
                newMessageMenuEnabled = true,
                hasLocalSendState = false
            )
        )
        assertEquals(
            ThreadMessageMenuVariant.Classic,
            threadMessageMenuVariant(
                newMessageMenuEnabled = true,
                hasLocalSendState = true
            )
        )
    }

    @Test
    fun preferenceDefaultsOffMigratesLegacyOptInAndWritesOnlyTheNewKey() = runBlocking {
        val backingStore = InMemoryRaftKeyValueStore()
        val preferenceStore = KeyValueChatLayoutPreferenceStore(backingStore)

        assertFalse(preferenceStore.readNewMessageMenuEnabledSync())

        backingStore.putBoolean("mobile:inline_selection_toolbar_enabled", true)
        assertTrue(preferenceStore.readNewMessageMenuEnabledSync())

        preferenceStore.saveNewMessageMenuEnabled(false)
        assertFalse(preferenceStore.readNewMessageMenuEnabledSync())
        assertEquals("false", backingStore.getString("mobile:new_message_menu_enabled"))
        assertEquals("true", backingStore.getString("mobile:inline_selection_toolbar_enabled"))
    }

    @Test
    fun actionAvailabilityDisablesOnlyUnavailableProductionActions() {
        val availability =
            ThreadNewMessageMenuActionAvailability(
                reactions = false,
                reply = false,
                save = false,
                translate = false
            )

        assertFalse(availability.reactions)
        assertFalse(availability.enabled(ThreadNewMessageMenuTool.Reply))
        assertFalse(availability.enabled(ThreadNewMessageMenuTool.Save))
        assertFalse(availability.enabled(ThreadNewMessageMenuTool.Translate))
        assertTrue(availability.enabled(ThreadNewMessageMenuTool.Copy))
        assertTrue(availability.enabled(ThreadNewMessageMenuTool.Forward))
        assertTrue(availability.enabled(ThreadNewMessageMenuTool.Select))
    }

    @Test
    fun toolDispatchRoutesEveryVisibleActionToItsMatchingProductionCallback() {
        val routed = mutableListOf<ThreadNewMessageMenuTool>()
        val callbacks =
            ThreadNewMessageMenuToolCallbacks(
                onCopy = { routed += ThreadNewMessageMenuTool.Copy },
                onReply = { routed += ThreadNewMessageMenuTool.Reply },
                onForward = { routed += ThreadNewMessageMenuTool.Forward },
                onSave = { routed += ThreadNewMessageMenuTool.Save },
                onTranslate = { routed += ThreadNewMessageMenuTool.Translate },
                onSelect = { routed += ThreadNewMessageMenuTool.Select }
            )

        ThreadNewMessageMenuToolOrder.forEach { tool -> tool.dispatch(callbacks) }

        assertEquals(ThreadNewMessageMenuToolOrder, routed)
    }
}
