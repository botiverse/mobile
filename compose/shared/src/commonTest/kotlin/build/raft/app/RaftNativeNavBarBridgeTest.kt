package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** iOS native navigation: the bar's head stack and the trailing-button split. */
class RaftNativeNavBarBridgeTest {
    private class Recorder : RaftNativeNavBarListener {
        val titles = mutableListOf<String>()
        val nested = mutableListOf<Boolean>()
        val actions = mutableListOf<String>()
        val extras = mutableListOf<String>()
        val images = mutableListOf<RaftNativeTabBarAvatar?>()
        var cleared = 0

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
            titles += title
            this.nested += nested
            actions += actionsJson
            extras += extrasJson
            images += titleImage
        }

        override fun onNativeNavBarCleared() {
            cleared += 1
        }

        override fun onNativeNavBarScroll(offset: Double, contentHeight: Double, dragging: Boolean) = Unit
    }

    private fun subtitleTap(extrasJson: String) = Json.parseToJsonElement(extrasJson).jsonObject["subtitleTap"]

    private fun head(title: String, onBack: (() -> Unit)? = null) =
        RaftNativeNavBarHead(title, null, emptyList(), RaftNativeNavBarTitleExtras(), onBack = onBack)

    @Test
    fun theHeadActivatedLastIsShownAndGivesTheBarBackWhenItLeaves() {
        val bridge = RaftNativeNavBarBridge()
        val recorder = Recorder()
        bridge.attachListener(recorder)
        val page = RaftNativeNavBarBridge.Entry()
        val subView = RaftNativeNavBarBridge.Entry()
        bridge.activate(page)
        bridge.publish(page, head("Settings"))
        bridge.activate(subView)
        var backs = 0
        bridge.publish(subView, head("Account", onBack = { backs += 1 }))
        assertEquals(listOf("Settings", "Account"), recorder.titles)
        assertEquals(listOf(false, true), recorder.nested)
        assertTrue(bridge.actionTapped(RaftNativeNavBarBackId))
        assertEquals(1, backs)
        bridge.deactivate(subView)
        assertEquals("Settings", recorder.titles.last())
        bridge.deactivate(page)
        assertEquals(1, recorder.cleared)
    }

    @Test
    fun aTappableSubtitleIsFlaggedToTheHostAndRunsItsHandler() {
        val bridge = RaftNativeNavBarBridge()
        val recorder = Recorder()
        bridge.attachListener(recorder)
        val page = RaftNativeNavBarBridge.Entry()
        bridge.activate(page)
        var opened = 0
        val extras = RaftNativeNavBarTitleExtras()
        bridge.publish(page, RaftNativeNavBarHead("@artin: hi", "@Lagoon · 3 replies", emptyList(), extras, onSubtitleClick = { opened += 1 }))
        assertEquals(JsonPrimitive(true), subtitleTap(recorder.extras.last()))
        assertTrue(bridge.actionTapped(RaftNativeNavBarSubtitleId))
        assertEquals(1, opened)
        // Without a subtitle line there is nothing to tap.
        bridge.publish(page, RaftNativeNavBarHead("@artin: hi", null, emptyList(), extras, onSubtitleClick = { opened += 1 }))
        assertEquals(null, subtitleTap(recorder.extras.last()))
        bridge.publish(page, RaftNativeNavBarHead("@artin: hi", "@Lagoon", emptyList(), extras))
        assertEquals(null, subtitleTap(recorder.extras.last()))
        assertEquals(false, bridge.actionTapped(RaftNativeNavBarSubtitleId))
    }

    @Test
    fun aKeptAliveTabPublishesNothingUntilActivated() {
        val bridge = RaftNativeNavBarBridge()
        val recorder = Recorder()
        bridge.attachListener(recorder)
        val tasks = RaftNativeNavBarBridge.Entry()
        bridge.publish(tasks, head("Tasks"))
        assertEquals(emptyList(), recorder.titles)
    }

    @Test
    fun trailingButtonsKeepTwoByPriorityAndTheRestGoToTheOverflow() {
        val actions =
            listOf("search", "settings", "focus", "members").map { id ->
                RaftNativeNavBarAction(id, id, null, {}, priority = if (id == "members") Int.MAX_VALUE else if (id == "focus") -1 else 0)
            }
        val split = raftNativeNavBarSplit(actions).associate { it.id to it.placement }
        assertEquals(RaftNativeNavBarPlacement.Trailing, split["members"])
        assertEquals(RaftNativeNavBarPlacement.Trailing, split["search"])
        assertEquals(RaftNativeNavBarPlacement.Overflow, split["settings"])
        assertEquals(RaftNativeNavBarPlacement.Overflow, split["focus"])
    }

    @Test
    fun aDeclinedHeadOnTopHidesTheBarAndGivesItBackWhenItLeaves() {
        val bridge = RaftNativeNavBarBridge()
        val recorder = Recorder()
        bridge.attachListener(recorder)
        val page = RaftNativeNavBarBridge.Entry()
        bridge.activate(page)
        bridge.publish(page, head("Settings"))
        val unmapped = RaftNativeNavBarBridge.Entry().also { it.declined = true }
        bridge.activate(unmapped)
        assertEquals(1, recorder.cleared)
        bridge.deactivate(unmapped)
        assertEquals(listOf("Settings", "Settings"), recorder.titles)
    }

    @Test
    fun barButtonsCarryTheirDesignSystemGlyphAsAnAssetAndTheSymbolAsFallback() {
        val bridge = RaftNativeNavBarBridge()
        val recorder = Recorder()
        bridge.attachListener(recorder)
        val page = RaftNativeNavBarBridge.Entry()
        bridge.activate(page)
        val members = RaftNativeNavBarAction("members", "Members", "person.2", {}, icon = RaftLucideIconName.Users)
        val text = RaftNativeNavBarAction("text", "Text", null, {})
        bridge.publish(page, RaftNativeNavBarHead("General", null, listOf(members, text), RaftNativeNavBarTitleExtras()))
        val json = recorder.actions.last()
        assertTrue(""""imageAsset":"lucide-users"""" in json, json)
        assertTrue(""""systemSymbol":"person.2"""" in json, json)
        assertEquals(1, Regex("imageAsset").findAll(json).count(), "a button without a glyph sends none")
    }

    @Test
    fun aPlainCountTravelsApartFromTheUnreadBadgeAndIsCappedLikeTheWeb() {
        val bridge = RaftNativeNavBarBridge()
        val recorder = Recorder()
        bridge.attachListener(recorder)
        val page = RaftNativeNavBarBridge.Entry()
        bridge.activate(page)
        val members = RaftNativeNavBarAction("members", "Members", "person.2", {}, count = raftNativeNavBarCountText(28))
        val bell = RaftNativeNavBarAction("bell", "Notifications", "bell", {}, badge = 3)
        bridge.publish(page, RaftNativeNavBarHead("General", null, listOf(members, bell), RaftNativeNavBarTitleExtras()))
        val rows = Json.parseToJsonElement(recorder.actions.last()).jsonArray.map { it.jsonObject }
        assertEquals(JsonPrimitive("28"), rows[0]["count"])
        assertEquals(null, rows[0]["badge"])
        assertEquals(JsonPrimitive(3), rows[1]["badge"])
        assertEquals(null, rows[1]["count"])
        assertEquals(listOf(null, "1", "99", "99+"), listOf(0, 1, 99, 100).map(::raftNativeNavBarCountText))
    }

    @Test
    fun everyLucideWireNameMapsBack() {
        RaftLucideIconName.entries.forEach { assertEquals(it, raftLucideIconNameForWire(it.wireName)) }
        assertEquals(null, raftLucideIconNameForWire("not-a-glyph"))
    }

    @Test
    fun prefillShowsBeforeFirstComposeHandsOffLiveHandlersAndNeverResurrectsAfterDeactivate() {
        val bridge = RaftNativeNavBarBridge()
        val recorder = Recorder()
        bridge.attachListener(recorder)

        val prefillAction = RaftNativeNavBarAction("thread-search", "Search", "magnifyingglass", {}, icon = RaftLucideIconName.Search)
        val prefillExtras = RaftNativeNavBarTitleExtras(leading = RaftNativeNavBarTitleLeading.Channel(RaftChannelKindGlyph.Public))
        bridge.prefill(RaftNativeNavBarHead("proj-mobile", null, listOf(prefillAction), prefillExtras))
        assertEquals(listOf("proj-mobile"), recorder.titles)
        assertEquals(1, recorder.actions.size)

        // First Compose frame publishes the exact same head with a live handler: no duplicate UIKit
        // wire update, and taps now reach the live handler.
        var searches = 0
        val liveEntry = RaftNativeNavBarBridge.Entry()
        bridge.activate(liveEntry)
        bridge.publish(
            liveEntry,
            RaftNativeNavBarHead(
                "proj-mobile",
                null,
                listOf(prefillAction.copy(onClick = { searches += 1 })),
                prefillExtras,
            ),
        )
        assertEquals(listOf("proj-mobile"), recorder.titles)
        assertTrue(bridge.actionTapped("thread-search"))
        assertEquals(1, searches)

        // Once the live head leaves, prefill stays retired and does not reappear.
        bridge.deactivate(liveEntry)
        assertEquals(1, recorder.cleared)
        assertEquals(listOf("proj-mobile"), recorder.titles)

        // Late prefill after a live head was already seen is ignored.
        bridge.prefill(head("stale"))
        assertEquals(listOf("proj-mobile"), recorder.titles)
    }

    @Test
    fun conversationPrefillResolvesChannelGlyphDmAvatarAndReplySubtitleBeforePush() {
        val strings =
            RaftNativeNavBarPrefillStrings(
                replyThreadTitle = "Thread",
                channelTitle = "Channel",
                directMessageTitle = "Direct Message",
                searchLabel = "Search",
                searchInThreadLabel = "Search in thread",
                searchInTaskLabel = "Search in task",
                viewInChannelLabel = "View in Channel",
                settingsLabel = "Settings",
                openTasksLabel = "Open Tasks",
                openFilesLabel = "Open Files",
                loungeLabel = "Lounge",
                loungeTitleForAgent = { "$it's Lounge" },
                channelDetailsTitle = "Details",
                membersTitle = "Members",
            )
        val channelPayload =
            RaftThreadScreenPayload(
                instanceId = "channel-1",
                title = "proj-mobile",
                status = "loading",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "private",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                showModeTabs = true,
                showSettingsAction = true,
                showTaskOpenActions = true,
                showTaskStatusActions = true,
            )
        val channelHead = buildThreadNativeNavBarPrefillHead(payload = channelPayload, strings = strings)
        assertEquals("proj-mobile", channelHead.title)
        assertEquals(RaftNativeNavBarTitleLeading.Channel(RaftChannelKindGlyph.Private), channelHead.extras.leading)
        assertEquals(
            listOf("thread-search", "thread-side-card-tasks", "thread-side-card-files", "thread-settings"),
            channelHead.actions.map { it.id },
        )

        val dmPayload =
            RaftThreadScreenPayload(
                instanceId = "dm-1",
                title = "Lagoon",
                status = "loading",
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                sourceThreadId = "dm-1",
                streamChannelId = "dm-1",
                dmPeerIdentity =
                    MemberDetailIdentity(
                        memberId = build.raft.app.model.MemberId("agent-1"),
                        kind = build.raft.app.model.MemberKind.Agent,
                        displayName = "Lagoon",
                    ),
                showModeTabs = true,
                showSettingsAction = false,
                showTaskOpenActions = true,
                showTaskStatusActions = true,
            )
        val dmHead =
            buildThreadNativeNavBarPrefillHead(
                payload = dmPayload,
                dmAgentHasKnownLounge = true,
                resolvedDmPeerAvatarWireValue = "https://cdn.example/lagoon.png",
                strings = strings,
            )
        assertEquals("Lagoon", dmHead.title)
        assertEquals(
            RaftNativeNavBarTitleLeading.Avatar("Lagoon", "https://cdn.example/lagoon.png", RaftAvatarKind.Agent),
            dmHead.extras.leading,
        )
        assertEquals(
            listOf("thread-search", "lounge", "thread-side-card-tasks", "thread-side-card-files"),
            dmHead.actions.map { it.id },
        )

        val replyPayload =
            RaftThreadScreenPayload(
                instanceId = "reply-1",
                title = "",
                status = "loading",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "reply-1",
                parentChannelId = "channel-1",
                parentMessageId = "msg-1",
                parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                showModeTabs = false,
                showOpenInChannelAction = true,
            )
        val parentProjection =
            build.raft.app.data.SharedConversationDirectoryProjection(
                id = "channel-1",
                title = "proj-mobile",
                conversationKind = build.raft.app.model.ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "channel",
                serverId = "server-1",
                joined = true,
                archived = false,
                unreadCount = 0,
            )
        val replyHead =
            buildThreadNativeNavBarPrefillHead(
                payload = replyPayload,
                directoryProjection = parentProjection,
                strings = strings,
            )
        assertEquals("Thread", replyHead.title)
        assertEquals("#proj-mobile", replyHead.subtitle)
        assertTrue(replyHead.onSubtitleClick != null)
        assertEquals(listOf(SEARCH_IN_TASK_MENU_ITEM_ID, VIEW_TASK_IN_CHANNEL_MENU_ITEM_ID), replyHead.actions.map { it.id })
    }
}
