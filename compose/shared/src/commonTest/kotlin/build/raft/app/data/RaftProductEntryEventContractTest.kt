package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive

/**
 * Every entry-level event the app can emit is in the server's registry, with only registered
 * properties and values. Vendored from botiverse/slock `packages/shared/src/productEvents.ts` at
 * [PinnedSlockSha] (PR #11064 head, which builds on #11045 = [PinnedBaseSlockSha] on staging).
 * The server rejects unknown names and values, so bump the SHAs and these lists together.
 */
class RaftProductEntryEventContractTest {
    private class Prop(val values: Set<String>?, val required: Boolean)

    private class EventSpec(val sources: Set<String>, val properties: Map<String, Prop>)

    private companion object {
        const val PinnedSlockSha = "36b076b0ca196bbeccd695c05d4df5a587e6602e"
        const val PinnedBaseSlockSha = "3e959613eeeaa80607e826792c1f72bd17459191"

        /** botiverse/slock #11467 head: `MESSAGE_ACTIONS` gained `select_text` after `copy_markdown`. */
        const val PinnedSelectTextSlockSha = "27f555f166a5f51e8758d8e10549751db15f4429"

        /** `APP_CLIENTS` = web + desktop + the native clients. */
        val AppClients = setOf("web", "desktop", "android", "ios", "harmonyos")

        val ChannelTabs = setOf("chat", "tasks", "files")
        val ChannelTabSurfaces = setOf("header_tabs", "header_swipe", "side_card_toggle", "side_column_tabs", "side_card_close")
        val MessageActions =
            setOf(
                "open_menu", "open_reaction_picker", "react_quick", "reply_in_thread", "follow_thread", "unfollow_thread",
                "task_done", "task_reopen", "create_task", "convert_to_task",
                "copy_link", "copy_markdown", "select_text", "forward", "select", "save", "unsave", "translate",
                "mention_sender", "copy_sender_name", "copy_sender_handle",
                "copy_text", "pin", "unpin", "mark_unread", "edit", "delete", "report",
            )
        val MessageActionSurfaces = setOf("hover_toolbar", "context_menu", "long_press", "toolbar_menu", "sender_name", "sender_menu")
        val AgentCreateEntries =
            setOf("sidebar", "sidebar_external", "channel_members", "computer_detail", "members_page", "composer_mention")

        /** `PRODUCT_EVENT_REGISTRY` entries for the events the app sends. */
        val Registry =
            mapOf(
                "channel_tab_selected" to
                    EventSpec(
                        AppClients,
                        mapOf(
                            "tab" to Prop(ChannelTabs, required = true),
                            "surface" to Prop(ChannelTabSurfaces, required = true),
                            "was_active" to Prop(null, required = true),
                        ),
                    ),
                "message_action_selected" to
                    EventSpec(
                        AppClients,
                        mapOf(
                            "action" to Prop(MessageActions, required = true),
                            "surface" to Prop(MessageActionSurfaces, required = true),
                            "in_thread" to Prop(null, required = false),
                        ),
                    ),
                "activity_item_open" to
                    EventSpec(
                        AppClients,
                        mapOf("item_kind" to Prop(setOf("channel", "dm", "thread", "mention_action"), required = false)),
                    ),
                "activity_mark" to
                    EventSpec(
                        AppClients,
                        mapOf("action" to Prop(setOf("read", "unread", "read_all", "done"), required = false)),
                    ),
                "agent_create_opened" to
                    EventSpec(AppClients, mapOf("entry" to Prop(AgentCreateEntries, required = false))),
            )

        /** Names the app once logged internally; never registered, so the server drops them. */
        val UnregisteredNames = listOf("row_opened", "task_created", "agent_created")

        /** Registry message actions the app's long-press menu has no item for. */
        val MessageActionsWithoutAppItem = setOf("copy_text", "pin", "unpin", "mark_unread", "edit", "delete", "report")
    }

    private fun allEvents(): List<RaftProductEvent<*>> =
        RaftChannelTab.entries.flatMap { tab ->
            RaftChannelTabSurface.entries.flatMap { surface ->
                listOf(true, false).map { RaftProductEvent.ChannelTabSelected(RaftChannelTabSelectedProperties(tab, surface, it)) }
            }
        } +
            RaftMessageAction.entries.flatMap { action ->
                listOf(true, false).map { RaftProductEvent.MessageActionSelected(RaftMessageActionSelectedProperties(action, RaftMessageActionSurface.LongPress, it)) }
            } +
            RaftActivityItemKind.entries.map { RaftProductEvent.ActivityItemOpen(RaftActivityItemOpenProperties(it)) } +
            RaftActivityMarkAction.entries.map { RaftProductEvent.ActivityMark(RaftActivityMarkProperties(it)) } +
            RaftAgentCreateEntry.entries.map { RaftProductEvent.AgentCreateOpened(RaftAgentCreateOpenedProperties(it)) }

    @Test
    fun pinnedShasMatchProductionConstants() {
        assertEquals(PinnedSlockSha, RAFT_PRODUCT_ENTRY_EVENTS_SLOCK_SHA)
        assertEquals(PinnedBaseSlockSha, RAFT_PRODUCT_ENTRY_EVENTS_BASE_SLOCK_SHA)
        assertEquals(PinnedSelectTextSlockSha, RAFT_PRODUCT_EVENTS_SELECT_TEXT_SLOCK_SHA)
    }

    @Test
    fun everyEmittableEventNamePropertyAndValueIsInTheRegistry() {
        val events = allEvents()
        assertEquals(Registry.keys, events.map { it.name }.toSet(), "each registered entry event has a typed emitter")
        events.forEach { event ->
            val spec = Registry[event.name] ?: error("${event.name} is not in the registry @ $PinnedSlockSha")
            val properties = event.encodeProperties()
            properties.forEach { (key, value) ->
                val prop = spec.properties[key] ?: error("${event.name}.$key is not a registered property")
                prop.values?.let { assertTrue(value.jsonPrimitive.content in it, "${event.name}.$key=${value.jsonPrimitive.content}") }
            }
            spec.properties.filterValues { it.required }.keys.forEach {
                assertTrue(it in properties, "${event.name} must carry required $it")
            }
        }
    }

    @Test
    fun appEnumsAreSubsetsOfTheRegistryAndNativeSourcesMayEmitThem() {
        val cases =
            listOf(
                Triple("CHANNEL_TABS", ChannelTabs, RaftChannelTab.entries.map { it.wire }),
                Triple("CHANNEL_TAB_SURFACES", ChannelTabSurfaces, RaftChannelTabSurface.entries.map { it.wire }),
                Triple("MESSAGE_ACTIONS", MessageActions, RaftMessageAction.entries.map { it.wire }),
                Triple("MESSAGE_ACTION_SURFACES", MessageActionSurfaces, RaftMessageActionSurface.entries.map { it.wire }),
                Triple("AGENT_CREATE_ENTRIES", AgentCreateEntries, RaftAgentCreateEntry.entries.map { it.wire }),
                Triple("activity_item_open.item_kind", Registry.getValue("activity_item_open").properties.getValue("item_kind").values!!, RaftActivityItemKind.entries.map { it.wire }),
                Triple("activity_mark.action", Registry.getValue("activity_mark").properties.getValue("action").values!!, RaftActivityMarkAction.entries.map { it.wire }),
            )
        cases.forEach { (name, registry, app) ->
            assertTrue(registry.containsAll(app), "$name: ${app.filterNot { it in registry }} not in slock@$PinnedSlockSha")
            assertEquals(app.size, app.toSet().size, "$name has duplicates")
        }
        RaftProductEventSource.entries.forEach { source ->
            Registry.forEach { (name, spec) -> assertTrue(source.wire in spec.sources, "$name does not accept ${source.wire}") }
        }
    }

    @Test
    fun registryActionsWithoutAnAppMenuItemAreNotSent() {
        val sent = RaftMessageAction.entries.map { it.wire }.toSet()
        assertEquals(emptySet(), sent intersect MessageActionsWithoutAppItem)
        assertTrue(MessageActionsWithoutAppItem.all { it in MessageActions })
    }

    @Test
    fun namesTheServerNeverAcceptedAreNotEmitted() {
        val emitted = allEvents().map { it.name }.toSet()
        UnregisteredNames.forEach { name ->
            assertFalse(name in Registry, name)
            assertFalse(name in emitted, name)
        }
    }
}
