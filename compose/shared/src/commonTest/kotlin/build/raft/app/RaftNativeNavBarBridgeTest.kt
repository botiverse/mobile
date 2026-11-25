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
        val prefillExtras = RaftNativeNavBarTitleExtras(leading = RaftNativeNavBarTitleLeading.Channel(RaftChannelKindGlyph.Hash))
        bridge.prefill(RaftNativeNavBarHead("proj-mobile", null, listOf(prefillAction), prefillExtras))
        assertEquals(listOf("proj-mobile"), recorder.titles)
        assertEquals(1, recorder.actions.size)

        // Temporary LoadingShell (empty actions, preservePrefilledActions = true) keeps prefilled
        // buttons on the bar instead of flashing them away before FeatureSurfaces mounts.
        val liveEntry = RaftNativeNavBarBridge.Entry()
        bridge.activate(liveEntry)
        bridge.publish(
            liveEntry,
            RaftNativeNavBarHead(
                "proj-mobile",
                null,
                emptyList(),
                prefillExtras,
                preservePrefilledActions = true,
            ),
        )
        assertEquals(listOf("proj-mobile"), recorder.titles)
        assertEquals(1, recorder.actions.size)

        // FeatureSurfaces publishes the exact same head with a live handler: no duplicate UIKit
        // wire update, and taps now reach the live handler.
        var searches = 0
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
    fun stalledLoadingShellDropsPreservedPrefilledButtonsWhenItGivesUpAndCannotResurrectThem() {
        val bridge = RaftNativeNavBarBridge()
        val recorder = Recorder()
        bridge.attachListener(recorder)

        val prefillAction = RaftNativeNavBarAction("thread-search", "Search", "magnifyingglass", {}, icon = RaftLucideIconName.Search)
        val extras = RaftNativeNavBarTitleExtras(leading = RaftNativeNavBarTitleLeading.Channel(RaftChannelKindGlyph.Hash))
        bridge.prefill(RaftNativeNavBarHead("proj-mobile", null, listOf(prefillAction), extras))
        assertEquals(1, Json.parseToJsonElement(recorder.actions.last()).jsonArray.size)

        val liveEntry = RaftNativeNavBarBridge.Entry()
        bridge.activate(liveEntry)
        bridge.publish(
            liveEntry,
            RaftNativeNavBarHead("proj-mobile", null, emptyList(), extras, preservePrefilledActions = true),
        )
        assertEquals(1, Json.parseToJsonElement(recorder.actions.last()).jsonArray.size)

        // Loading stalls or transitions to error/retry: LoadingShell drops preservePrefilledActions,
        // clearing the non-interactive prefilled buttons from the bar.
        bridge.publish(
            liveEntry,
            RaftNativeNavBarHead("proj-mobile", null, emptyList(), extras, preservePrefilledActions = false),
        )
        assertEquals(0, Json.parseToJsonElement(recorder.actions.last()).jsonArray.size)
        assertEquals(false, bridge.actionTapped("thread-search"))

        // Even if preservePrefilledActions flips back true later, dropped prefill buttons stay gone.
        bridge.publish(
            liveEntry,
            RaftNativeNavBarHead("proj-mobile", null, emptyList(), extras, preservePrefilledActions = true),
        )
        assertEquals(0, Json.parseToJsonElement(recorder.actions.last()).jsonArray.size)
        assertEquals(false, bridge.actionTapped("thread-search"))
    }

    @Test
    fun conversationPrefillMatchesLiveHeadActionsAndCoversMembersAndDetails() {
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
                loungeAudienceSubtitle = "Everyone in this server can see this. Only this agent replies.",
                privateAudienceSubtitleForAgent = { "Only you and $it can see this." },
                channelDetailsTitle = "Details",
                membersTitle = "Members",
            )
        val liveLabels =
            ThreadHeaderSideColumnLabels(
                searchLabel = strings.searchLabel,
                searchInThreadLabel = strings.searchInThreadLabel,
                searchInTaskLabel = strings.searchInTaskLabel,
                settingsLabel = strings.settingsLabel,
                viewInChannelLabel = strings.viewInChannelLabel,
            )

        fun liveIdsFor(
            payload: RaftThreadScreenPayload,
            surfaceKind: String,
            taskHasViewInChannel: Boolean = false,
        ): List<String> {
            val taskConversation = payload.isTaskConversationSurface()
            val sideCardActions =
                if (conversationSideCardsEnabled(payload.showModeTabs, assistantPanelEmbed = false)) {
                    threadHeaderSideCardOwnActions(
                        tasksAvailable = payload.supportsChannelTasks(),
                        labelForCard = { card, _ ->
                            if (card == ConversationSideCard.Tasks) strings.openTasksLabel else strings.openFilesLabel
                        },
                    )
                } else {
                    emptyList()
                }
            return threadHeaderSideColumnActionsForPayload(
                payload = payload,
                surfaceKind = surfaceKind,
                taskConversation = taskConversation,
                labels = liveLabels,
                onViewTaskInChannel = ({}).takeIf { taskConversation && taskHasViewInChannel },
                sideCardActions = sideCardActions,
            ).toRaftNativeNavBarActions().map { it.id }
        }

        // 1. Private channel
        val channelPayload =
            RaftThreadScreenPayload(
                instanceId = "channel-1",
                title = "",
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
        val channelProjection =
            build.raft.app.data.SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1"),
                conversationKind = build.raft.app.model.ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "private",
                title = "proj-mobile",
            )
        val channelHead =
            buildThreadNativeNavBarPrefillHead(
                payload = channelPayload,
                directoryProjection = channelProjection,
                strings = strings,
            )
        assertEquals("proj-mobile", channelHead.title)
        assertEquals(RaftNativeNavBarTitleLeading.Channel(RaftChannelKindGlyph.Lock), channelHead.extras.leading)
        assertEquals(liveIdsFor(channelPayload, RaftThreadSurfaceKind.Channel), channelHead.actions.map { it.id })

        // 2. Agent DM with Lounge ON and OFF
        val dmPeer =
            MemberDetailIdentity(
                memberId = build.raft.app.model.MemberId("agent-1"),
                kind = build.raft.app.model.MemberKind.Agent,
            )
        val dmPayload =
            RaftThreadScreenPayload(
                instanceId = "dm-1",
                title = "",
                status = "loading",
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                sourceThreadId = "dm-1",
                streamChannelId = "dm-1",
                dmPeerIdentity = dmPeer,
                showModeTabs = true,
                showSettingsAction = false,
                showTaskOpenActions = true,
                showTaskStatusActions = true,
            )
        val dmProjection =
            build.raft.app.data.SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "dm-1",
                aliases = setOf("dm-1"),
                conversationKind = build.raft.app.model.ConversationKind.DirectMessage,
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                title = "Lagoon",
                peerIdentity = dmPeer,
            )
        val dmLoungeOnHead =
            buildThreadNativeNavBarPrefillHead(
                payload = dmPayload,
                directoryProjection = dmProjection,
                loungeSwitchVisible = true,
                resolvedDmPeerAvatarWireValue = "https://cdn.example/lagoon.png",
                strings = strings,
            )
        assertEquals("Lagoon", dmLoungeOnHead.title)
        assertEquals("Only you and Lagoon can see this.", dmLoungeOnHead.subtitle)
        assertEquals(
            RaftNativeNavBarTitleLeading.AgentConversation(
                label = "Lagoon",
                avatarWireValue = "https://cdn.example/lagoon.png",
                isLounge = false,
                agentId = "agent-1",
            ),
            dmLoungeOnHead.extras.leading,
        )
        assertEquals(
            listOf("thread-search"),
            dmLoungeOnHead.actions.filter { it.placement == RaftNativeNavBarPlacement.Trailing }.map { it.id },
        )
        assertEquals(
            listOf("thread-side-card-tasks", "thread-side-card-files", "thread-settings"),
            dmLoungeOnHead.actions.filter { it.placement == RaftNativeNavBarPlacement.Overflow }.map { it.id },
        )
        assertEquals(
            liveIdsFor(dmPayload, RaftThreadSurfaceKind.DirectMessage),
            dmLoungeOnHead.actions.map { it.id },
        )
        assertTrue("lounge" !in dmLoungeOnHead.actions.map { it.id })

        val dmLoungeOffHead =
            buildThreadNativeNavBarPrefillHead(
                payload = dmPayload,
                directoryProjection = dmProjection,
                loungeSwitchVisible = false,
                resolvedDmPeerAvatarWireValue = "https://cdn.example/lagoon.png",
                strings = strings,
            )
        assertEquals(null, dmLoungeOffHead.subtitle)
        assertEquals(
            RaftNativeNavBarTitleLeading.Avatar("Lagoon", "https://cdn.example/lagoon.png", RaftAvatarKind.Agent),
            dmLoungeOffHead.extras.leading,
        )
        assertEquals(
            liveIdsFor(dmPayload, RaftThreadSurfaceKind.DirectMessage),
            dmLoungeOffHead.actions.map { it.id },
        )
        assertTrue("lounge" !in dmLoungeOffHead.actions.map { it.id })

        val loungeHead =
            buildThreadNativeNavBarPrefillHead(
                payload = channelPayload.copy(sourceThreadId = "lounge-1", streamChannelId = "lounge-1"),
                pageLounge =
                    build.raft.app.data.RaftAgentLounge(
                        channelId = build.raft.app.model.ChannelId("lounge-1"),
                        agentId = "agent-1",
                        agentName = "Lagoon",
                    ),
                loungeSwitchVisible = true,
                strings = strings,
            )
        assertEquals("Lagoon's Lounge", loungeHead.title)
        assertEquals("Everyone in this server can see this. Only this agent replies.", loungeHead.subtitle)
        assertEquals(
            RaftNativeNavBarTitleLeading.AgentConversation(
                label = "Lagoon",
                avatarWireValue = null,
                isLounge = true,
                agentId = "agent-1",
            ),
            loungeHead.extras.leading,
        )
        assertEquals(
            listOf("thread-search"),
            loungeHead.actions.filter { it.placement == RaftNativeNavBarPlacement.Trailing }.map { it.id },
        )
        assertEquals(
            listOf("thread-side-card-tasks", "thread-side-card-files", "thread-settings"),
            loungeHead.actions.filter { it.placement == RaftNativeNavBarPlacement.Overflow }.map { it.id },
        )

        // 3. Reply thread
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
                serverId = "server-1",
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1"),
                conversationKind = build.raft.app.model.ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "channel",
                title = "proj-mobile",
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
        assertEquals(liveIdsFor(replyPayload, RaftThreadSurfaceKind.ReplyThread), replyHead.actions.map { it.id })

        // 4. Task conversation
        val taskPayload =
            RaftThreadScreenPayload(
                instanceId = "task-1",
                title = "",
                status = "loading",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = RaftTaskConversationChannelKind,
                sourceThreadId = "task-1",
                streamChannelId = "task-1",
                parentChannelId = "channel-1",
                showModeTabs = false,
                showSettingsAction = false,
            )
        val taskProjection =
            build.raft.app.data.SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "task-1",
                aliases = setOf("task-1"),
                conversationKind = build.raft.app.model.ConversationKind.Task,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = RaftTaskConversationChannelKind,
                title = "Task #282",
            )
        val taskHead =
            buildThreadNativeNavBarPrefillHead(
                payload = taskPayload,
                directoryProjection = taskProjection,
                strings = strings,
            )
        assertEquals("Task #282", taskHead.title)
        assertEquals(
            liveIdsFor(taskPayload, RaftThreadSurfaceKind.Channel, taskHasViewInChannel = true),
            taskHead.actions.map { it.id },
        )
        // Without a directory projection while still loading, prefill matches the live header's generic fallback.
        val taskLoadingFallbackHead =
            buildThreadNativeNavBarPrefillHead(
                payload = taskPayload,
                strings = strings,
            )
        assertEquals("Channel", taskLoadingFallbackHead.title)
        assertEquals(
            liveIdsFor(taskPayload, RaftThreadSurfaceKind.Channel, taskHasViewInChannel = true),
            taskLoadingFallbackHead.actions.map { it.id },
        )

        // 5. Pushed Members, Channel Details, and App Shell Server Stack (Settings / Members) pages
        val membersHead = buildNativeNavBarPrefillHead(THREAD_MEMBERS_PAGE, "{}", strings = strings)
        assertEquals("Members", membersHead?.title)
        assertEquals(emptyList(), membersHead?.actions)
        val detailsHead = buildNativeNavBarPrefillHead(CHANNEL_SETTINGS_PAGE, "{}", strings = strings)
        assertEquals("Details", detailsHead?.title)
        assertEquals(emptyList(), detailsHead?.actions)
        val serverSettingsHead =
            buildNativeNavBarPrefillHead(
                RAFT_APP_SHELL_SERVER_PAGE,
                appShellServerStackPageData(AppShellServerTabKey, shellId = "shell-1").toString(),
                strings = strings,
            )
        assertEquals("Settings", serverSettingsHead?.title)
        assertEquals(emptyList(), serverSettingsHead?.actions)
        assertTrue(serverSettingsHead?.onBack != null)
        assertTrue(serverSettingsHead?.onTitleClick != null)
        val serverMembersHead =
            buildNativeNavBarPrefillHead(
                RAFT_APP_SHELL_SERVER_PAGE,
                appShellServerStackPageData("members", shellId = "shell-1").toString(),
                strings = strings,
            )
        assertEquals("Members", serverMembersHead?.title)
        assertEquals(emptyList(), serverMembersHead?.actions)
        assertTrue(serverMembersHead?.onBack != null)
        assertEquals(null, serverMembersHead?.onTitleClick)
        assertEquals(
            null,
            buildNativeNavBarPrefillHead(RAFT_APP_SHELL_SERVER_PAGE, """{"tab":"unknown"}""", strings = strings),
        )
    }

    @Test
    fun agentConversationNativeTitleMenuSerializesAudienceSubtitlesUnreadAndSeparator() {
        val bridge = RaftNativeNavBarBridge()
        var lastTitleMenuJson = ""
        var lastExtrasJson = ""
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
                    lastExtrasJson = extrasJson
                    lastTitleMenuJson = titleMenuJson
                }

                override fun onNativeNavBarCleared() = Unit

                override fun onNativeNavBarScroll(offset: Double, contentHeight: Double, dragging: Boolean) = Unit
            }
        )
        var selectedLounge = 0
        var selectedPrivate = 0
        var viewedProfile = 0
        val switch =
            AgentLoungeConversationSwitch(
                activeSide = AgentLoungeConversationSide.Lounge,
                privateUnread = AgentLoungeSwitchUnread(count = 4),
                onSelectLounge = { selectedLounge++ },
                onSelectPrivate = { selectedPrivate++ },
            )
        val menu =
            agentConversationNativeTitleMenu(
                switch = switch,
                loungeLabel = "Lounge",
                loungeAudience = "Members and agents of this server can take part.",
                privateChatLabel = "Private chat",
                privateAudience = "Only you and Tenny can see this.",
                viewProfileLabel = "View profile",
                onViewProfile = { viewedProfile++ },
            )
        val entry = RaftNativeNavBarBridge.Entry()
        bridge.activate(entry)
        bridge.publish(
            entry,
            RaftNativeNavBarHead(
                title = "Tenny's Lounge",
                subtitle = "Members and agents of this server can take part.",
                actions = emptyList(),
                extras =
                    RaftNativeNavBarTitleExtras(
                        leading =
                            RaftNativeNavBarTitleLeading.AgentConversation(
                                label = "Tenny",
                                avatarWireValue = null,
                                isLounge = true,
                                attention = true,
                                agentId = "agent-tenny",
                                accessibilityLabel = "Tenny's Lounge. Switch between Lounge and private chat",
                            ),
                    ),
                titleMenu = menu,
            ),
        )
        val extrasObj = Json.parseToJsonElement(lastExtrasJson).jsonObject["leading"]!!.jsonObject
        assertEquals(JsonPrimitive("agent_conversation"), extrasObj["kind"])
        assertEquals(JsonPrimitive("lounge"), extrasObj["side"])
        assertEquals(JsonPrimitive(true), extrasObj["attention"])

        val rows = Json.parseToJsonElement(lastTitleMenuJson).jsonArray.map { it.jsonObject }
        assertEquals(3, rows.size)
        assertEquals(JsonPrimitive("agent-conversation-lounge"), rows[0]["id"])
        assertEquals(JsonPrimitive(true), rows[0]["checked"])
        assertEquals(JsonPrimitive("lucide-sofa"), rows[0]["imageAsset"])
        assertEquals(JsonPrimitive("Members and agents of this server can take part."), rows[0]["subtitle"])
        assertEquals(null, rows[0]["badge"])

        assertEquals(JsonPrimitive("agent-conversation-private"), rows[1]["id"])
        assertEquals(null, rows[1]["checked"])
        assertEquals(JsonPrimitive("lucide-lock"), rows[1]["imageAsset"])
        assertEquals(JsonPrimitive("Only you and Tenny can see this."), rows[1]["subtitle"])
        assertEquals(JsonPrimitive(4), rows[1]["badge"])

        assertEquals(JsonPrimitive("agent-conversation-profile"), rows[2]["id"])
        assertEquals(JsonPrimitive(true), rows[2]["dividerBefore"])
        assertEquals(JsonPrimitive("lucide-user-round"), rows[2]["imageAsset"])

        assertTrue(bridge.actionTapped("agent-conversation-lounge"))
        assertEquals(0, selectedLounge, "already on Lounge")
        assertTrue(bridge.actionTapped("agent-conversation-private"))
        assertEquals(1, selectedPrivate)
        assertTrue(bridge.actionTapped("agent-conversation-profile"))
        assertEquals(1, viewedProfile)
    }
}
