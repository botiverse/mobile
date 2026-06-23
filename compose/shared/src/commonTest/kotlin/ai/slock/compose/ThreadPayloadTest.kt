package ai.slock.compose

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.AvatarReference
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationPermissions
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.InboxItemKind
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.RealtimeRefreshRequired
import ai.slock.compose.model.RealtimeRefreshScope
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import ai.slock.compose.model.ThreadConversation
import ai.slock.compose.model.ThreadId
import ai.slock.compose.model.slockEpochDayForDate
import ai.slock.compose.model.threadIdentity
import ai.slock.compose.markdown.ThreadMarkdownTag
import ai.slock.compose.markdown.ThreadMarkdownTagKind
import ai.slock.compose.data.InboxItem
import ai.slock.compose.data.StoredAuthServer
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.StoredAuthUser
import ai.slock.compose.data.TasksUiState
import ai.slock.compose.data.ThreadMessagesPage
import ai.slock.compose.data.WireActionCardAction
import ai.slock.compose.data.WireActionCardMetadata
import ai.slock.compose.data.WireAttachment
import ai.slock.compose.data.WireMessage
import ai.slock.compose.data.WireReaction
import ai.slock.compose.data.WireThreadSummary
import ai.slock.compose.data.actionCardFromMessageMetadata
import ai.slock.compose.data.appendSharedThreadSentMessageFromPage
import ai.slock.compose.data.buildSharedThreadPayloadFromMessagesPage
import ai.slock.compose.data.parseSharedThreadPickedAttachmentFiles
import ai.slock.compose.data.parseSharedThreadUploadedAttachments
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ThreadPayloadTest {
    private val launchJson = Json { ignoreUnknownKeys = true }

    @Test
    fun threadComposerOnlyShowsOnChatTab() {
        assertTrue(shouldShowThreadComposerForTab(joined = true, showComposer = true, selectedTab = "chat"))
        assertFalse(shouldShowThreadComposerForTab(joined = true, showComposer = true, selectedTab = "tasks"))
        assertFalse(shouldShowThreadComposerForTab(joined = true, showComposer = true, selectedTab = "files"))
        assertFalse(shouldShowThreadComposerForTab(joined = false, showComposer = true, selectedTab = "chat"))
        assertFalse(shouldShowThreadComposerForTab(joined = true, showComposer = false, selectedTab = "chat"))
    }

    @Test
    fun threadQuickReactionsIncludeServerCheckMarkEmoji() {
        assertTrue("✅" in ThreadQuickReactionEmojis)
    }

    @Test
    fun genericAttachmentNoPreviewMetaLineKeepsSizeAndMimeType() {
        val attachment =
            KuiklyThreadAttachmentItem(
                id = "att-1",
                name = "archive.zip",
                sizeLabel = "42 KB",
                mimeType = "application/zip"
            )

        assertEquals("42 KB · application/zip", attachment.genericAttachmentMetaLine())
    }

    @Test
    fun threadMessagesLazyItemCountTracksScrollableRows() {
        assertEquals(
            4,
            threadMessagesLazyItemCount(
                originStatus = "none",
                showHistoryBoundary = true,
                messagesCount = 3,
                canLoadMoreAfter = false
            )
        )
        assertEquals(
            6,
            threadMessagesLazyItemCount(
                originStatus = "content",
                showHistoryBoundary = true,
                messagesCount = 3,
                canLoadMoreAfter = true
            )
        )
        assertEquals(
            3,
            sharedListBeyondBoundsItemCount(
                threadMessagesLazyItemCount(
                    originStatus = "none",
                    showHistoryBoundary = true,
                    messagesCount = 1,
                    canLoadMoreAfter = false
                )
            )
        )
    }

    @Test
    fun threadBottomAnchorOffsetBottomAlignsShortLatestItemInLongLists() {
        assertEquals(
            0,
            threadLatestItemBottomAnchorScrollOffset(
                itemSize = 120,
                viewportHeight = 640,
                visibleItemCount = 3,
                totalItemCount = 3
            )
        )
        assertEquals(
            -520,
            threadLatestItemBottomAnchorScrollOffset(
                itemSize = 120,
                viewportHeight = 640,
                visibleItemCount = 3,
                totalItemCount = 12
            )
        )
        assertEquals(
            160,
            threadLatestItemBottomAnchorScrollOffset(
                itemSize = 800,
                viewportHeight = 640,
                visibleItemCount = 1,
                totalItemCount = 3
            )
        )
    }

    @Test
    fun floatingOverlayPositionHandlesCrampedAndBottomAnchors() {
        val cramped = SlockFloatingOverlayLayout(size = Size(width = 200f, height = 100f))
        assertEquals(
            Offset(8f, 8f),
            cramped.positionContent(
                anchor = Offset(120f, 120f),
                contentSize = Size(width = 320f, height = 240f),
                viewportGap = 8f
            )
        )

        val roomy = SlockFloatingOverlayLayout(size = Size(width = 420f, height = 560f))
        assertEquals(
            Offset(200f, 338f),
            roomy.positionContent(
                anchor = Offset(360f, 1260f),
                contentSize = Size(width = 212f, height = 214f),
                viewportGap = 8f,
                horizontalOffset = -16f,
                verticalOffset = 8f,
                verticalPlacement = SlockFloatingVerticalPlacement.Auto
            )
        )
    }

    @Test
    fun typedPayloadKeepsMembersAndSettingsActions() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-1",
                title = "对话流专修",
                membersCount = 2,
                canAddMembers = false,
                addMemberStatus = "failed",
                addMemberErrorCode = "member_exists",
                removeMemberStatus = "failed",
                removingMemberId = "a1",
                removeMemberErrorCode = "member_remove_failed",
                members =
                    listOf(
                        KuiklyThreadMemberItem(id = "u1", displayName = "Artin", handle = "artin", roleLabel = "Owner"),
                        KuiklyThreadMemberItem(id = "a1", displayName = "Android Bot", kind = "agent", avatarLabel = "AB")
                    ),
                memberCandidates =
                    listOf(
                        KuiklyThreadMemberItem(id = "u2", displayName = "Cindy", handle = "cindy"),
                        KuiklyThreadMemberItem(id = "a2", displayName = "QA Bot", kind = "agent")
                    ),
                settingsActions =
                    listOf(
                        KuiklyThreadSettingsAction(id = "visibility"),
                        KuiklyThreadSettingsAction(id = "archive"),
                        KuiklyThreadSettingsAction(id = "delete")
                    )
            )

        assertEquals("thread-1", payload.instanceId)
        assertEquals("对话流专修", payload.title)
        assertEquals(2, payload.membersCount)
        assertEquals(false, payload.canAddMembers)
        assertEquals("failed", payload.addMemberStatus)
        assertEquals("member_exists", payload.addMemberErrorCode)
        assertEquals("failed", payload.removeMemberStatus)
        assertEquals("a1", payload.removingMemberId)
        assertEquals("member_remove_failed", payload.removeMemberErrorCode)
        assertEquals("Artin", payload.members.first().displayName)
        assertEquals("agent", payload.members[1].kind)
        assertEquals("Cindy", payload.memberCandidates.first().displayName)
        assertEquals("agent", payload.memberCandidates[1].kind)
        assertEquals("visibility", payload.settingsActions.first().id)
        assertEquals("archive", payload.settingsActions[1].id)
        assertEquals("delete", payload.settingsActions[2].id)
        assertEquals(true, payload.settingsActions.normalizedThreadSettingsChannelActions().all { it.destructive })
    }

    @Test
    fun invalidPayloadReturnsErrorState() {
        val payload = parseThreadScreenPayload("{not-json", "bad-1")

        assertEquals("bad-1", payload.instanceId)
        assertEquals("error", payload.status)
        assertEquals("payload_parse_failed", payload.errorCode)
    }

    @Test
    fun fullPayloadWithoutLaunchIdentityReturnsErrorState() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-legacy",
                      "title": "Legacy full payload",
                      "messages": [{"id": "m1", "body": "must not load"}],
                      "members": [{"id": "u1", "displayName": "Artin"}],
                      "filesPayload": "{\"status\":\"content\"}",
                      "composer": {"seedText": "must not load"}
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("fallback", payload.instanceId)
        assertEquals("error", payload.status)
        assertEquals("payload_parse_failed", payload.errorCode)
        assertEquals(emptyList(), payload.messages)
        assertEquals(emptyList(), payload.members)
        assertEquals("{}", payload.filesPayload)
        assertEquals("", payload.resolveThreadComposer().seedText)
    }

    @Test
    fun sourceThreadLaunchPayloadStartsStoreBackedLoadingState() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-launch-1",
                      "title": "general",
                      "status": "content",
                      "sourceThreadId": "channel-1",
                      "streamChannelId": "channel-1",
                      "membersCount": 2,
                      "joined": false,
                      "canSend": false,
                      "settingsActions": [
                        {"id": "archive", "enabled": true}
                      ]
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("thread-launch-1", payload.instanceId)
        assertEquals("Channel", payload.title)
        assertEquals("loading", payload.status)
        assertEquals("channel-1", payload.sourceThreadId)
        assertEquals("channel-1", payload.streamChannelId)
        assertEquals(0, payload.membersCount)
        assertEquals(true, payload.joined)
        assertEquals(true, payload.canSend)
        assertEquals(emptyList<KuiklyThreadMessageItem>(), payload.messages)
        assertEquals(emptyList<KuiklyThreadTaskItem>(), payload.tasks)
        assertEquals(emptyList<KuiklyThreadMemberItem>(), payload.members)
        assertEquals(emptyList<KuiklyThreadMemberItem>(), payload.memberCandidates)
        assertEquals("{}", payload.filesPayload)
        assertEquals("", payload.resolveThreadComposer().seedText)
        assertEquals(false, payload.resolveThreadComposer().seedAsTask)
        assertEquals(emptyList(), payload.settingsActions)
    }

    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun launchWireSchemaExcludesBigThreadStateFields() {
        val descriptor = WireThreadLaunchPayload.serializer().descriptor
        val names = (0 until descriptor.elementsCount)
            .map { index -> descriptor.getElementName(index) }
            .toSet()

        listOf(
            "messages",
            "tasks",
            "members",
            "memberCandidates",
            "filesPayload",
            "composer",
            "title",
            "subtitle",
            "channelKind",
            "memberScopedMentions",
            "showModeTabs",
            "showReplyActions",
            "showComposer",
            "joined",
            "canSend",
            "membersCount",
            "canAddMembers",
            "settingsActions",
            "showMembersAction",
            "showSettingsAction",
            "showOpenInChannelAction",
            "showMessageSaveAction",
            "showTaskOpenActions",
            "showTaskStatusActions",
            "emptyTitle",
            "emptyDetail",
            "joinLabel",
            "dmPeerName",
            "dmPeerAvatarUrl",
            "dmPeerAvatarLabel",
            "dmPeerGravatarUrl"
        ).forEach { forbidden ->
            assertFalse(forbidden in names, "$forbidden must stay outside compose_thread launch pageData")
        }
    }

    @Test
    fun launchPayloadCanUseThreadIdAliasFromLaunchSource() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "page-1",
                      "threadId": "dm-1",
                      "title": "@cindy",
                      "surfaceKind": "dm",
                      "selectedTab": "files",
                      "canSend": false
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("page-1", payload.instanceId)
        assertEquals("Direct Message", payload.title)
        assertEquals("dm-1", payload.sourceThreadId)
        assertEquals("dm-1", payload.streamChannelId)
        assertEquals("files", payload.selectedTab)
        assertEquals(true, payload.canSend)
        assertEquals(true, payload.memberScopedMentions)
        assertEquals(true, payload.resolveThreadComposer().enabled)
    }

    @Test
    fun launchPayloadDmPeerHintsAreIgnoredUntilStoreProjectionArrives() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "page-dm",
                      "threadId": "dm-artin",
                      "surfaceKind": "dm",
                      "dmPeerName": "artin",
                      "dmPeerAvatarUrl": "https://cdn.slock.ai/avatars/artin.webp",
                      "dmPeerAvatarLabel": "AR",
                      "dmPeerGravatarUrl": "https://www.gravatar.com/avatar/artin"
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("page-dm", payload.instanceId)
        assertEquals("Direct Message", payload.title)
        assertEquals("dm-artin", payload.sourceThreadId)
        assertEquals("dm-artin", payload.streamChannelId)
        assertEquals(null, payload.dmPeerName)
        assertEquals(null, payload.dmPeerAvatarUrl)
        assertEquals(null, payload.dmPeerAvatarLabel)
        assertEquals(null, payload.dmPeerGravatarUrl)
    }

    @Test
    fun launchPayloadDmWithoutPeerNameFallsBackToDirectMessage() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "page-dm",
                      "threadId": "dm-1",
                      "surfaceKind": "dm"
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("Direct Message", payload.title)
        assertEquals(null, payload.dmPeerName)
        assertEquals(null, payload.dmPeerAvatarUrl)
    }

    @Test
    fun launchPayloadDmEmptyPeerNameFallsBackToDirectMessage() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "page-dm",
                      "threadId": "dm-1",
                      "surfaceKind": "dm",
                      "dmPeerName": "   ",
                      "dmPeerAvatarUrl": ""
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("Direct Message", payload.title)
        assertEquals(null, payload.dmPeerName)
        assertEquals(null, payload.dmPeerAvatarUrl)
    }

    @Test
    fun appShellDmPayloadUsesConversationHeaderProjectionForPeerHeaderFields() {
        val conversation =
            ConversationSummary(
                threadId = ThreadId("dm-artin"),
                channelId = ChannelId("dm-artin"),
                kind = ConversationKind.DirectMessage,
                name = "artin",
                avatar = AvatarReference.Pixel("crown"),
                avatarUrl = "https://cdn.slock.ai/avatars/artin.webp",
                gravatarUrl = "https://www.gravatar.com/avatar/artin",
                permissions = ConversationPermissions(canSend = true)
            )
        val payload =
            buildAppShellThreadPayload(conversation)
        val headerState = sharedConversationHeaderState(listOf(conversation))
        val renderedPayload =
            payload.withConversationHeader(headerState.entryFor(payload))

        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, payload.surfaceKind)
        assertEquals("Direct Message", payload.title)
        assertEquals(null, payload.dmPeerName)
        assertEquals("artin", renderedPayload.title)
        assertEquals("artin", renderedPayload.dmPeerName)
        assertEquals("pixel:crown", renderedPayload.dmPeerAvatarUrl)
        assertEquals("AR", renderedPayload.dmPeerAvatarLabel)
        assertEquals("https://www.gravatar.com/avatar/artin", renderedPayload.dmPeerGravatarUrl)
        assertEquals(false, payload.showMembersAction)
        assertEquals(false, payload.showSettingsAction)
    }

    @Test
    fun appShellChannelPayloadUsesConversationHeaderProjectionForTitleOnly() {
        val conversation =
            ConversationSummary(
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                kind = ConversationKind.Channel,
                name = "首页专修",
                subtitle = "Home repair",
                avatar = AvatarReference.Pixel("ignored"),
                permissions = ConversationPermissions(canSend = true)
            )
        val payload = buildAppShellThreadPayload(conversation)
        val headerState = sharedConversationHeaderState(listOf(conversation))
        val renderedPayload = payload.withConversationHeader(headerState.entryFor(payload))

        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("Channel", payload.title)
        assertEquals("首页专修", renderedPayload.title)
        assertEquals("Home repair", renderedPayload.subtitle)
        assertEquals(null, renderedPayload.dmPeerName)
        assertEquals(null, renderedPayload.dmPeerAvatarUrl)
        assertEquals("Message #首页专修", renderedPayload.resolveThreadComposer().placeholder)
    }

    @Test
    fun appShellPrivateChannelPayloadUsesLockHeaderKind() {
        val conversation =
            ConversationSummary(
                threadId = ThreadId("private-1"),
                channelId = ChannelId("private-1"),
                channelType = "private",
                kind = ConversationKind.Channel,
                name = "onboarding-slocktest",
                permissions = ConversationPermissions(canSend = true)
            )
        val payload = buildAppShellThreadPayload(conversation)
        val headerState = sharedConversationHeaderState(listOf(conversation))
        val renderedPayload = payload.withConversationHeader(headerState.entryFor(payload))

        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("private", payload.channelKind)
        assertEquals("private", renderedPayload.channelKind)
        assertEquals(SlockLucideIconName.Lock, renderedPayload.channelKind.threadChannelHeaderIcon())
    }

    @Test
    fun appShellPayloadMarksJointChannelsAsMemberScopedMentions() {
        val payload =
            buildAppShellThreadPayload(
                ConversationSummary(
                    threadId = ThreadId("joint-1"),
                    channelId = ChannelId("joint-1"),
                    channelType = "joint",
                    kind = ConversationKind.Joint,
                    name = "partner",
                    permissions = ConversationPermissions(canSend = true)
                )
            )

        assertEquals(true, payload.memberScopedMentions)
        assertEquals("joint-1", payload.resolveThreadComposer().streamChannelId)
    }

    @Test
    fun memberSuggestionRealtimeRefreshOnlyTracksRelevantMemberScopes() {
        assertTrue(
            RealtimeRefreshRequired(
                scope = RealtimeRefreshScope.ChannelMembers,
                reason = "channel:members-updated",
                channelId = ChannelId("thread-1")
            ).refreshesThreadMemberSuggestions("thread-1")
        )
        assertFalse(
            RealtimeRefreshRequired(
                scope = RealtimeRefreshScope.ChannelMembers,
                reason = "channel:members-updated",
                channelId = ChannelId("other")
            ).refreshesThreadMemberSuggestions("thread-1")
        )
        assertFalse(
            RealtimeRefreshRequired(
                scope = RealtimeRefreshScope.ChannelMembers,
                reason = "channel:members-updated",
                channelId = ChannelId("thread-1")
            ).refreshesThreadMemberSuggestions(null)
        )
        assertTrue(
            RealtimeRefreshRequired(
                scope = RealtimeRefreshScope.ServerMembers,
                reason = "server:member-updated"
            ).refreshesThreadMemberSuggestions("thread-1")
        )
        assertFalse(
            RealtimeRefreshRequired(
                scope = RealtimeRefreshScope.Home,
                reason = "channel:updated",
                channelId = ChannelId("thread-1")
            ).refreshesThreadMemberSuggestions("thread-1")
        )
    }

    @Test
    fun threadSettingsActionsDropLegacyLeaveFallback() {
        val actions =
            listOf(
                KuiklyThreadSettingsAction(
                    id = "leave"
                )
            ).normalizedThreadSettingsChannelActions()

        assertEquals(
            listOf("visibility", "archive", "delete"),
            actions.map { it.id }
        )
        assertEquals(
            listOf("Make Private", "Archive Channel", "Delete Channel"),
            actions.map { it.title }
        )
    }

    @Test
    fun threadSettingsDisabledRecognizedActionsDoNotFallbackToDefaults() {
        val actions =
            listOf(
                KuiklyThreadSettingsAction(id = "visibility", enabled = false),
                KuiklyThreadSettingsAction(id = "archive", enabled = true),
                KuiklyThreadSettingsAction(id = "delete", enabled = false)
            ).normalizedThreadSettingsChannelActions()

        assertEquals(listOf("archive"), actions.map { it.id })
    }

    @Test
    fun threadSettingsKnownActionsUseCanonicalUiModel() {
        val actions =
            listOf(
                KuiklyThreadSettingsAction(id = "visibility"),
                KuiklyThreadSettingsAction(id = "archive"),
                KuiklyThreadSettingsAction(id = "delete")
            ).normalizedThreadSettingsChannelActions()

        assertEquals(
            listOf("visibility", "archive", "delete"),
            actions.map { it.id }
        )
        assertEquals(
            listOf("Make Private", "Archive Channel", "Delete Channel"),
            actions.map { it.title }
        )
        assertEquals(
            listOf(
                "Restrict this channel to selected members",
                "Freeze writes and hide from sidebars",
                "Permanently remove this channel"
            ),
            actions.map { it.subtitle }
        )
        assertEquals(
            listOf(SlockLucideIconName.Lock, SlockLucideIconName.Archive, SlockLucideIconName.Trash),
            actions.map { it.icon }
        )
        assertEquals(true, actions.all { it.destructive })
    }

    @Test
    fun launchSettingsActionsAreIgnoredAtBoundary() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-settings-boundary",
                      "sourceThreadId": "channel-1",
                      "settingsActions": [
                        {
                          "id": "visibility",
                          "enabled": false,
                          "title": "Wrong label",
                          "subtitle": "Wrong subtitle",
                          "icon": "trash",
                          "destructive": false
                        }
                      ]
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals(emptyList(), payload.settingsActions)
        assertEquals(
            emptyList(),
            payload.settingsActions.normalizedThreadSettingsChannelActions(useDefaultWhenEmpty = false)
        )
    }

    @Test
    fun buildsSharedShellThreadRoutePayloadFromConversation() {
        val payload =
            buildAppShellThreadPayload(
                ConversationSummary(
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    kind = ConversationKind.Channel,
                    name = "general",
                    subtitle = "Team channel",
                    joined = true,
                    memberCount = 7,
                    permissions = ConversationPermissions(canSend = true)
                ),
                AppShellThreadTimeContext(
                    localOffsetMinutes = 480,
                    todayEpochDay = 20593
                ),
                alignOwnMessagesRight = true
            )

        assertEquals("shared-shell-thread-channel-1", payload.instanceId)
        assertEquals("Channel", payload.title)
        assertEquals(null, payload.subtitle)
        assertEquals("loading", payload.status)
        assertEquals("channel-1", payload.sourceThreadId)
        assertEquals(true, payload.canSend)
        assertEquals(7, payload.membersCount)
        assertEquals(480, payload.localOffsetMinutes)
        assertEquals(20593, payload.todayEpochDay)
        assertEquals(true, payload.showReplyActions)
        assertEquals(true, payload.alignOwnMessagesRight)
        assertEquals(true, payload.showSettingsAction)
        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("channel-1", payload.streamChannelId)
        assertEquals(null, payload.parentChannelId)
        assertEquals(null, payload.parentMessageId)
        assertEquals(
            listOf("visibility", "archive", "delete"),
            payload.settingsActions.map { it.id }
        )
        val composer = payload.resolveThreadComposer()
        assertEquals("Message #Channel", composer.placeholder)
        assertEquals(KuiklyThreadSurfaceKind.Channel, composer.surfaceKind)
        assertEquals("channel-1", composer.streamChannelId)
        assertEquals("idle", composer.composerMode)
    }

    @Test
    fun sharedShellJointChannelRouteDisablesTaskSurfaces() {
        val payload =
            buildAppShellThreadPayload(
                ConversationSummary(
                    threadId = ThreadId("joint-1"),
                    channelId = ChannelId("joint-1"),
                    channelType = "joint",
                    kind = ConversationKind.Channel,
                    name = "partner",
                    subtitle = "Joint channel",
                    joined = true,
                    memberCount = 3,
                    permissions = ConversationPermissions(canSend = true, canCreateTask = true)
                )
            )

        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals(false, payload.showTaskOpenActions)
        assertEquals(false, payload.showTaskStatusActions)
        assertEquals(false, payload.composer.showAsTaskToggle)
        assertEquals(false, payload.resolveThreadComposer().showAsTaskToggle)
        assertEquals(false, payload.supportsChannelTasks())
        assertEquals(false, shouldStartThreadTasksJoin(payload.copy(status = "content"), null, ChannelId("joint-1")))
    }

    @Test
    fun sharedShellDirectMessageRouteKeepsDmSurfaceForComposerProps() {
        val payload =
            buildAppShellThreadPayload(
                ConversationSummary(
                    threadId = ThreadId("dm-agent-1"),
                    channelId = ChannelId("dm-agent-1"),
                    kind = ConversationKind.DirectMessage,
                    name = "Product-UX-Designer",
                    subtitle = "online",
                    joined = true,
                    permissions = ConversationPermissions(canSend = true)
                )
        )

        val headerState =
            sharedConversationHeaderState(
                listOf(
                    ConversationSummary(
                        threadId = ThreadId("dm-agent-1"),
                        channelId = ChannelId("dm-agent-1"),
                        kind = ConversationKind.DirectMessage,
                        name = "Product-UX-Designer",
                        subtitle = "online",
                        joined = true,
                        permissions = ConversationPermissions(canSend = true)
                    )
                )
            )
        val renderedPayload = payload.withConversationHeader(headerState.entryFor(payload))

        assertEquals("Direct Message", payload.title)
        assertEquals(null, payload.subtitle)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, payload.surfaceKind)
        assertEquals("dm-agent-1", payload.streamChannelId)
        assertEquals(null, payload.parentChannelId)
        assertEquals(null, payload.parentMessageId)
        assertEquals(null, payload.dmPeerName)
        assertEquals("Product-UX-Designer", renderedPayload.title)
        assertEquals("Product-UX-Designer", renderedPayload.dmPeerName)
        val composer = renderedPayload.resolveThreadComposer()
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, composer.surfaceKind)
        assertEquals("dm-agent-1", composer.streamChannelId)
        assertEquals("Message @Product-UX-Designer", composer.placeholder)
        assertEquals("idle", composer.composerMode)
    }

    @Test
    fun sharedShellReplyThreadRouteUsesLaunchOnlyThreadHeader() {
        val payload =
            buildAppShellThreadPayload(
                ConversationSummary(
                    threadId = threadIdentity(ChannelId("channel-1"), MessageId("message-1")),
                    channelId = ChannelId("channel-1"),
                    thread =
                        ThreadConversation(
                            parentChannelId = ChannelId("channel-1"),
                            parentMessageId = MessageId("message-1"),
                            streamChannelId = ChannelId("thread-1")
                        ),
                    kind = ConversationKind.Thread,
                    name = "general",
                    subtitle = "Parent message preview",
                    joined = true,
                    memberCount = 7,
                    permissions = ConversationPermissions(canSend = true)
                )
            )

        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals(null, payload.subtitle)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals(null, payload.originMessage)
        assertEquals(null, payload.originPreview)
        assertEquals("message-1", payload.originParentMessageId)
        assertEquals("loading", payload.originStatus)
        assertEquals(false, payload.showModeTabs)
        assertEquals(false, payload.showMembersAction)
        assertEquals(false, payload.showSettingsAction)
        assertEquals(true, payload.showOpenInChannelAction)
    }

    @Test
    fun threadHeaderDisplayTitleDropsLeadingChannelHash() {
        assertEquals("首页专修", threadHeaderDisplayTitle("#首页专修"))
        assertEquals("design", threadHeaderDisplayTitle("design"))
        assertEquals("#", threadHeaderDisplayTitle("#"))
    }

    @Test
    fun appShellActivityThreadItemOpensReplyThreadChrome() {
        val request =
            InboxItem(
                key = "thread:message-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("channel-1"),
                channelName = "thread-stream-name",
                threadChannelId = ChannelId("thread-channel-1"),
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "Parent message preview",
                parentMessageSenderId = "user-1",
                parentMessageSenderKind = MemberKind.Human,
                latestActivityMessageId = MessageId("reply-message-1"),
                latestActivityPreview = "Latest reply"
            ).toHomeMessageOpenRequest()
        val conversation = request.conversation

        val payload = buildAppShellThreadPayload(conversation, anchorMessageId = request.anchorMessageId)

        assertEquals("reply-message-1", request.anchorMessageId)
        assertEquals(threadIdentity(ChannelId("channel-1"), MessageId("message-1")), conversation.threadId)
        assertEquals(ConversationKind.Thread, conversation.kind)
        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals(null, payload.subtitle)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("reply-message-1", payload.focusedMessageId)
        assertEquals("thread-channel-1", payload.sourceThreadId)
        assertEquals("thread-channel-1", payload.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("message-1", payload.parentMessageId)
        assertEquals("message-1", payload.originParentMessageId)
        assertEquals("loading", payload.originStatus)
        assertEquals(null, payload.originPreview)
        assertEquals(null, payload.originMessage)
        assertEquals(false, payload.showModeTabs)
        assertEquals(false, payload.showMembersAction)
        assertEquals(false, payload.showSettingsAction)
        assertEquals(true, payload.showOpenInChannelAction)
    }

    @Test
    fun contentPatchUsesPatchHeaderContextInsteadOfSeededReplyOrigin() {
        val origin =
            KuiklyThreadMessageItem(
                id = "message-1",
                authorId = "user-1",
                authorName = "Artin",
                body = "Parent message preview"
            )
        val reply =
            KuiklyThreadMessageItem(
                id = "reply-message-1",
                authorId = "agent-1",
                authorName = "Android-Developer-4",
                body = "Latest reply"
            )
        val current =
            KuiklyThreadScreenPayload(
                instanceId = "shared-shell-thread-channel-1-message-1",
                title = ThreadReplyPageTitle,
                subtitle = "#seeded",
                status = "loading",
                sourceThreadId = "thread-channel-1",
                streamChannelId = "thread-channel-1",
                parentChannelId = "channel-1",
                parentMessageId = "message-1",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                originParentMessageId = "message-1",
                originMessage = origin,
                originPreview = "Parent message preview",
                originStatus = "loaded"
            )
        val patch =
            current.copy(
                title = ThreadReplyPageTitle,
                subtitle = "#general",
                status = "content",
                messages = listOf(reply),
                originMessage = null,
                originPreview = null,
                originParentMessageId = null,
                originStatus = "none"
            )

        val payload = current.withContentPatchFrom(patch)

        assertEquals("content", payload.status)
        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals("#general", payload.subtitle)
        assertEquals(listOf("reply-message-1"), payload.messages.map { it.id })
        assertEquals(null, payload.originParentMessageId)
        assertEquals("none", payload.originStatus)
        assertEquals(null, payload.originMessage)
        assertEquals(null, payload.originPreview)
    }

    @Test
    fun contentPatchReplacesThreadChromeWhenRouteChangesToChannel() {
        val current =
            KuiklyThreadScreenPayload(
                instanceId = "shared-shell-thread-reply-stream-1",
                title = ThreadReplyPageTitle,
                subtitle = "#general",
                status = "content",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = "reply-stream-1",
                streamChannelId = "reply-stream-1",
                parentChannelId = "channel-1",
                parentMessageId = "parent-message-1",
                originParentMessageId = "parent-message-1",
                showModeTabs = false,
                showReplyActions = false,
                showMembersAction = false,
                showSettingsAction = false,
                showOpenInChannelAction = true,
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "reply-1",
                            authorId = "agent-1",
                            authorName = "Agent",
                            body = "reply"
                        )
                    ),
                originStatus = "loaded",
                originMessage =
                    KuiklyThreadMessageItem(
                        id = "parent-message-1",
                        authorId = "user-1",
                        authorName = "Artin",
                        body = "parent"
                    )
            )
        val patch =
            KuiklyThreadScreenPayload(
                instanceId = "shared-shell-thread-channel-1",
                title = "general",
                subtitle = null,
                status = "content",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = "public",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                parentChannelId = null,
                parentMessageId = null,
                originParentMessageId = null,
                showModeTabs = true,
                showReplyActions = true,
                showMembersAction = true,
                showSettingsAction = true,
                showOpenInChannelAction = false,
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "channel-message-1",
                            authorId = "user-1",
                            authorName = "Artin",
                            body = "channel message"
                        )
                    ),
                originStatus = "none",
                originMessage = null
            )

        val payload = current.withContentPatchFrom(patch)

        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("public", payload.channelKind)
        assertEquals("channel-1", payload.sourceThreadId)
        assertEquals("channel-1", payload.streamChannelId)
        assertEquals(null, payload.parentChannelId)
        assertEquals(null, payload.parentMessageId)
        assertEquals(null, payload.originParentMessageId)
        assertEquals(true, payload.showModeTabs)
        assertEquals(true, payload.showReplyActions)
        assertEquals(true, payload.showMembersAction)
        assertEquals(true, payload.showSettingsAction)
        assertEquals(false, payload.showOpenInChannelAction)
        assertEquals("none", payload.originStatus)
        assertEquals(null, payload.originMessage)
        assertEquals(listOf("channel-message-1"), payload.messages.map { it.id })
    }

    @Test
    fun refreshPatchPreservesLoadedOriginWhenNetworkSnapshotOnlyProvesSameParent() {
        val origin =
            KuiklyThreadMessageItem(
                id = "message-1",
                authorId = "user-1",
                authorName = "Artin",
                body = "Original task body"
            )
        val previous =
            KuiklyThreadScreenPayload(
                instanceId = "shared-shell-thread-thread-channel-1",
                title = ThreadReplyPageTitle,
                subtitle = "#general",
                status = "content",
                sourceThreadId = "thread-channel-1",
                streamChannelId = "thread-channel-1",
                parentChannelId = "channel-1",
                parentMessageId = "message-1",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                originParentMessageId = "message-1",
                originMessage = origin,
                originStatus = "loaded",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "reply-message-1",
                            authorId = "agent-1",
                            authorName = "Android-Developer-4",
                            body = "Reply body"
                        )
                    )
            )
        val refreshPatch =
            previous.copy(
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "reply-message-1",
                            authorId = "agent-1",
                            authorName = "Android-Developer-4",
                            body = "Reply body"
                        ),
                        KuiklyThreadMessageItem(
                            id = "reply-message-2",
                            authorId = "agent-2",
                            authorName = "KMP-专家",
                            body = "Fresh reply"
                        )
                    ),
                originMessage = null,
                originPreview = null,
                originParentMessageId = "message-1",
                originStatus = "missing"
            )

        val payload = refreshPatch.preserveStableTimelineFrom(previous)

        assertEquals("loaded", payload.originStatus)
        assertEquals("message-1", payload.originParentMessageId)
        assertEquals("Original task body", payload.originMessage?.body)
        assertEquals(listOf("reply-message-1", "reply-message-2"), payload.messages.map { it.id })
    }

    @Test
    fun appShellActivityThreadItemShowsMissingOriginFallbackWhenPreviewIsUnavailable() {
        val request =
            InboxItem(
                key = "thread:message-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("channel-1"),
                channelName = "thread-stream-name",
                threadChannelId = ChannelId("thread-channel-1"),
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentMessageId = MessageId("message-1"),
                latestActivityPreview = "Latest reply"
            ).toHomeMessageOpenRequest()

        val payload = buildAppShellThreadPayload(request.conversation, anchorMessageId = request.anchorMessageId)

        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("message-1", payload.originParentMessageId)
        assertEquals("loading", payload.originStatus)
        assertEquals(null, payload.originMessage)
        assertEquals(null, payload.originPreview)
    }

    @Test
    fun appShellActivityThreadItemKeepsStreamUnresolvedWhenThreadStreamIsMissing() {
        val request =
            InboxItem(
                key = "thread:message-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-channel-compat"),
                channelName = "general",
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "Parent message preview",
                latestActivityPreview = "Latest reply"
            ).toHomeMessageOpenRequest()
        val conversation = request.conversation

        val payload = buildAppShellThreadPayload(conversation, anchorMessageId = request.anchorMessageId)

        assertEquals(threadIdentity(ChannelId("channel-1"), MessageId("message-1")), conversation.threadId)
        assertEquals(ConversationKind.Thread, conversation.kind)
        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals(null, payload.subtitle)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals(null, payload.sourceThreadId)
        assertEquals(null, payload.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("message-1", payload.parentMessageId)
        assertEquals("message-1", payload.originParentMessageId)
        assertEquals(null, conversation.thread?.streamChannelId)
        assertEquals(false, payload.showModeTabs)
        assertEquals(false, payload.showMembersAction)
        assertEquals(false, payload.showSettingsAction)
    }

    @Test
    fun appShellActivityChannelItemKeepsChannelChrome() {
        val request =
            InboxItem(
                key = "channel:general",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("channel-1"),
                channelName = "general",
                lastMessagePreview = "Latest channel message",
                lastMessageId = MessageId("latest-channel-message"),
                unreadCount = 2
            ).toHomeMessageOpenRequest()
        val conversation = request.conversation

        val payload = buildAppShellThreadPayload(conversation, anchorMessageId = request.anchorMessageId)

        assertEquals(ThreadId("channel-1"), conversation.threadId)
        assertEquals(ConversationKind.Channel, conversation.kind)
        assertEquals("Channel", payload.title)
        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals(null, payload.originParentMessageId)
        assertEquals("latest-channel-message", payload.focusedMessageId)
        assertEquals(true, payload.showModeTabs)
        assertEquals(true, payload.showMembersAction)
        assertEquals(true, payload.showSettingsAction)
    }

    @Test
    fun sharedShellThreadRouteOmitsSubtitleWhenChannelDescriptionIsMissing() {
        val payload =
            buildAppShellThreadPayload(
                ConversationSummary(
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    kind = ConversationKind.Channel,
                    name = "general",
                    subtitle = null,
                    joined = true,
                    memberCount = 7,
                    permissions = ConversationPermissions(canSend = true)
                )
            )

        assertEquals("Channel", payload.title)
        assertEquals(null, payload.subtitle)
    }

    @Test
    fun standaloneConversationThreadLaunchCarriesOnlyIdentityAndIntent() {
        val launch =
            buildStandaloneConversationThreadLaunchPayload(
                ConversationSummary(
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    channelType = "private",
                    kind = ConversationKind.Channel,
                    name = "release-room",
                    subtitle = "Product state must not be in PagerData",
                    joined = false,
                    memberCount = 12,
                    avatar = AvatarReference.Pixel("display-seed"),
                    permissions =
                        ConversationPermissions(
                            canRead = true,
                            canSend = false,
                            canCreateTask = false
                        )
                ),
                AppShellThreadTimeContext(localOffsetMinutes = 480, todayEpochDay = 20593),
                alignOwnMessagesRight = true,
                anchorMessageId = "message-focus"
            )

        assertEquals("shared-shell-thread-channel-1", launch.instanceId)
        assertEquals(KuiklyThreadSurfaceKind.Channel, launch.surfaceKind)
        assertEquals(null, launch.threadId)
        assertEquals("channel-1", launch.sourceThreadId)
        assertEquals("channel-1", launch.streamChannelId)
        assertEquals(null, launch.parentChannelId)
        assertEquals(null, launch.parentMessageId)
        assertEquals("message-focus", launch.focusedMessageId)
        assertEquals(480, launch.localOffsetMinutes)
        assertEquals(20593, launch.todayEpochDay)
        assertEquals(true, launch.alignOwnMessagesRight)

        val initialPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(launch),
                instanceId = "fallback"
            )

        assertEquals("shared-shell-thread-channel-1", initialPayload.instanceId)
        assertEquals("loading", initialPayload.status)
        assertEquals("Channel", initialPayload.title)
        assertEquals(KuiklyThreadSurfaceKind.Channel, initialPayload.surfaceKind)
        assertEquals("channel-1", initialPayload.sourceThreadId)
        assertEquals("message-focus", initialPayload.focusedMessageId)
        assertEquals(emptyList<KuiklyThreadMessageItem>(), initialPayload.messages)
        assertEquals(emptyList<KuiklyThreadTaskItem>(), initialPayload.tasks)
        assertEquals(emptyList(), initialPayload.settingsActions)
        assertEquals(0, initialPayload.membersCount)
        assertEquals(true, initialPayload.joined)
        assertEquals(true, initialPayload.canSend)
        assertEquals(null, initialPayload.dmPeerName)
        assertEquals(null, initialPayload.dmPeerAvatarUrl)

        val composer = initialPayload.resolveThreadComposer()
        assertEquals(KuiklyThreadSurfaceKind.Channel, composer.surfaceKind)
        assertEquals("channel-1", composer.streamChannelId)
        assertEquals(null, composer.mentionChannelId)
        assertEquals(null, composer.parentMessageId)
        assertEquals(true, composer.showAsTaskToggle)
    }

    @Test
    fun screenPayloadWireLaunchDropsDisplayAndProductState() {
        val launch =
            KuiklyThreadScreenPayload(
                instanceId = "display-route",
                title = "Display title",
                subtitle = "Display subtitle",
                status = "content",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-channel-1",
                streamChannelId = "thread-channel-1",
                parentChannelId = "parent-channel-1",
                parentMessageId = "parent-message-1",
                originParentMessageId = "parent-message-1",
                focusedMessageId = "reply-2",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "reply-1",
                            authorId = "agent-1",
                            authorName = "Android Dev",
                            timestampLabel = "now",
                            body = "This must not enter route payload"
                        )
                    ),
                joined = false,
                canSend = false,
                membersCount = 7,
                dmPeerName = "Display peer",
                dmPeerAvatarUrl = "https://example.com/avatar.png"
            ).toWireThreadLaunchPayload()

        assertEquals("display-route", launch.instanceId)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, launch.surfaceKind)
        assertEquals("thread-channel-1", launch.threadId)
        assertEquals("thread-channel-1", launch.sourceThreadId)
        assertEquals("thread-channel-1", launch.streamChannelId)
        assertEquals("parent-channel-1", launch.parentChannelId)
        assertEquals("parent-message-1", launch.parentMessageId)
        assertEquals("reply-2", launch.focusedMessageId)

        val initialPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(launch),
                instanceId = "fallback"
            )

        assertEquals("loading", initialPayload.status)
        assertEquals(ThreadReplyPageTitle, initialPayload.title)
        assertEquals(emptyList<KuiklyThreadMessageItem>(), initialPayload.messages)
        assertEquals(emptyList<KuiklyThreadTaskItem>(), initialPayload.tasks)
        assertEquals(emptyList(), initialPayload.settingsActions)
        assertEquals(0, initialPayload.membersCount)
        assertEquals(true, initialPayload.joined)
        assertEquals(true, initialPayload.canSend)
        assertEquals(null, initialPayload.dmPeerName)
        assertEquals(null, initialPayload.dmPeerAvatarUrl)
    }

    @Test
    fun standaloneReplyThreadLaunchPreservesComposerRoutingIntent() {
        val launch =
            WireThreadLaunchPayload(
                instanceId = "thread-route",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                threadId = "thread-channel-1",
                sourceThreadId = "thread-channel-1",
                streamChannelId = "thread-channel-1",
                parentChannelId = "parent-channel-1",
                parentMessageId = "parent-message-1",
                originParentMessageId = "parent-message-1",
                focusedMessageId = "reply-2",
                alignOwnMessagesRight = true,
                localOffsetMinutes = 480,
                todayEpochDay = 20593
            )

        val initialPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(launch),
                instanceId = "fallback"
            )

        assertEquals("thread-route", initialPayload.instanceId)
        assertEquals("loading", initialPayload.status)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, initialPayload.surfaceKind)
        assertEquals("thread-channel-1", initialPayload.sourceThreadId)
        assertEquals("thread-channel-1", initialPayload.streamChannelId)
        assertEquals("parent-channel-1", initialPayload.parentChannelId)
        assertEquals("parent-message-1", initialPayload.parentMessageId)
        assertEquals("parent-message-1", initialPayload.originParentMessageId)
        assertEquals("reply-2", initialPayload.focusedMessageId)
        assertEquals(false, initialPayload.showModeTabs)
        assertEquals(true, initialPayload.showOpenInChannelAction)

        val composer = initialPayload.resolveThreadComposer()
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, composer.surfaceKind)
        assertEquals("thread-channel-1", composer.streamChannelId)
        assertEquals("parent-channel-1", composer.mentionChannelId)
        assertEquals("parent-message-1", composer.parentMessageId)
        assertEquals("Reply in thread", composer.placeholder)
        assertEquals(false, composer.showAsTaskToggle)
    }

    @Test
    fun standaloneTaskThreadLaunchCarriesOnlyIdentityAndIntent() {
        val launch =
            buildStandaloneTaskThreadLaunchPayload(
                TaskSummary(
                    id = TaskId("task-1"),
                    title = "Fix slow route",
                    status = TaskStatus.InProgress,
                    taskNumber = 42,
                    messageId = MessageId("message-1"),
                    parentMessageId = MessageId("parent-message-1"),
                    channelId = ChannelId("parent-channel-1"),
                    threadId = ThreadId("thread-channel-1"),
                    assignee =
                        MemberProfile(
                            id = MemberId("agent-1"),
                            displayName = "Android Dev",
                            handle = "Android-Dev",
                            kind = MemberKind.Agent
                        ),
                    creator =
                        MemberProfile(
                            id = MemberId("user-1"),
                            displayName = "Artin",
                            handle = "artin",
                            kind = MemberKind.Human
                        ),
                    channelName = "bug triage",
                    updatedAtLabel = "now"
                ),
                AppShellThreadTimeContext(localOffsetMinutes = 480, todayEpochDay = 20593),
                alignOwnMessagesRight = true
            )

        assertEquals("task-thread-task-1", launch.instanceId)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, launch.surfaceKind)
        assertEquals("thread-channel-1", launch.threadId)
        assertEquals("thread-channel-1", launch.sourceThreadId)
        assertEquals("thread-channel-1", launch.streamChannelId)
        assertEquals("parent-channel-1", launch.parentChannelId)
        assertEquals("parent-message-1", launch.parentMessageId)
        assertEquals("parent-message-1", launch.originParentMessageId)
        assertEquals("parent-message-1", launch.focusedMessageId)
        assertEquals(480, launch.localOffsetMinutes)
        assertEquals(20593, launch.todayEpochDay)
        assertEquals(true, launch.alignOwnMessagesRight)

        val initialPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(launch),
                instanceId = "fallback"
            )

        assertEquals("task-thread-task-1", initialPayload.instanceId)
        assertEquals("loading", initialPayload.status)
        assertEquals(ThreadReplyPageTitle, initialPayload.title)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, initialPayload.surfaceKind)
        assertEquals("thread-channel-1", initialPayload.sourceThreadId)
        assertEquals("parent-channel-1", initialPayload.parentChannelId)
        assertEquals("parent-message-1", initialPayload.parentMessageId)
        assertEquals("parent-message-1", initialPayload.focusedMessageId)
        assertEquals(emptyList<KuiklyThreadMessageItem>(), initialPayload.messages)
        assertEquals(emptyList<KuiklyThreadTaskItem>(), initialPayload.tasks)
        assertEquals(emptyList(), initialPayload.settingsActions)
        assertEquals(0, initialPayload.membersCount)
        assertEquals(null, initialPayload.dmPeerName)
        assertEquals(null, initialPayload.dmPeerAvatarUrl)
    }

    @Test
    fun buildsReplyThreadLoadingPayloadWithoutFetchingParentChannel() {
        val parent =
            KuiklyThreadMessageItem(
                id = "message-1",
                authorId = "u1",
                authorName = "Artin",
                body = "Parent message"
            )
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "content",
                messages = listOf(parent)
            ).replyThreadLoadingPayload(parent)

        assertEquals("shared-shell-thread-pending-message-1", payload.instanceId)
        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals("#general", payload.subtitle)
        assertEquals("loading", payload.status)
        assertEquals(null, payload.sourceThreadId)
        assertEquals("message-1", payload.originParentMessageId)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("message-1", payload.parentMessageId)
        val composer = payload.resolveThreadComposer()
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, composer.surfaceKind)
        assertEquals("channel-1", composer.mentionChannelId)
        assertEquals("message-1", composer.parentMessageId)
        assertEquals("Reply in thread", composer.placeholder)
        assertEquals("loaded", payload.originStatus)
        assertEquals(false, payload.showModeTabs)
        assertEquals(true, payload.showOpenInChannelAction)
        assertEquals("Opening replies", payload.emptyTitle)
    }

    @Test
    fun replyThreadLoadingPayloadOmitsUnreadableChannelSubtitle() {
        val parent =
            KuiklyThreadMessageItem(
                id = "message-1",
                authorId = "u1",
                authorName = "Artin",
                body = "Parent message"
            )
        val payload =
            KuiklyThreadScreenPayload(
                title = "76c3c868",
                sourceThreadId = "channel-1",
                status = "content",
                messages = listOf(parent)
            ).replyThreadLoadingPayload(parent)

        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals(null, payload.subtitle)
    }

    @Test
    fun parentMessageContextLoadingPayloadTargetsParentConversation() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-replies-1",
                title = ThreadReplyPageTitle,
                status = "content",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = "reply-stream-1",
                streamChannelId = "reply-stream-1",
                parentChannelId = "channel-1",
                parentMessageId = "parent-message-1",
                originParentMessageId = "parent-message-1",
                showOpenInChannelAction = true
            ).parentMessageContextLoadingPayload("parent-message-1")

        assertEquals("shared-shell-thread-channel-1", payload.instanceId)
        assertEquals("loading", payload.status)
        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("channel-1", payload.sourceThreadId)
        assertEquals("channel-1", payload.streamChannelId)
        assertEquals(null, payload.parentChannelId)
        assertEquals(null, payload.parentMessageId)
        assertEquals(null, payload.originParentMessageId)
        assertEquals("parent-message-1", payload.focusedMessageId)
        assertEquals(false, payload.showOpenInChannelAction)
        assertEquals(true, payload.showModeTabs)
        assertEquals("Opening conversation", payload.emptyTitle)
        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.resolveThreadComposer().surfaceKind)
    }

    @Test
    fun buildsSharedThreadPayloadFromBridgeMessagesResponse() {
        val base =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "loading"
            )
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "u1", email = "artin@slock.ai"),
                accessToken = "access",
                refreshToken = "refresh"
            )

        val payload =
            buildSharedThreadPayloadFromMessagesPage(
                basePayload = base,
                session = session,
                page =
                    ThreadMessagesPage(
                        messages =
                            listOf(
                                WireMessage(
                                    id = "m1",
                                    channelId = "channel-1",
                                    senderType = "user",
                                    senderId = "u1",
                                    senderName = "Artin",
                                    messageType = "chat",
                                    content = "hello from shared route",
                                    createdAt = "2026-05-19T16:00:00Z",
                                    threadSummary =
                                        WireThreadSummary(
                                            threadChannelId = "thread-m1",
                                            replyCount = 3,
                                            unreadCount = 2,
                                            firstUnreadMessageId = "reply-2"
                                        ),
                                    taskStatus = "in_review",
                                    taskNumber = 42,
                                    claimedByName = "Android-Developer-2",
                                    attachments =
                                        listOf(
                                            WireAttachment(id = "a1", filename = "log.txt", mimeType = "text/plain", sizeBytes = 12),
                                            WireAttachment(
                                                id = "a2",
                                                filename = "screen.png",
                                                mimeType = "image/png",
                                                sizeBytes = 2048,
                                                thumbnailUrl = "https://cdn.example/screen.png"
                                            )
                                        ),
                                    reactions = listOf(WireReaction(emoji = "👍", count = 2, reactorIds = listOf("u1"), reactorNames = listOf("Artin")))
                                )
                            ),
                        canLoadMoreBefore = false,
                        canLoadMoreAfter = null
                    )
            )

        assertEquals("content", payload.status)
        assertEquals("m1", payload.messages.first().id)
        assertEquals("hello from shared route", payload.messages.first().body)
        assertEquals("Artin", payload.messages.first().authorName)
        assertEquals(true, payload.messages.first().isOwn)
        assertEquals("thread-m1", payload.messages.first().threadChannelId)
        assertEquals(3, payload.messages.first().replyCount)
        assertEquals(2, payload.messages.first().unreadReplyCount)
        assertEquals("reply-2", payload.messages.first().firstUnreadReplyMessageId)
        assertEquals("m1", payload.messages.first().taskId)
        assertEquals(42, payload.messages.first().taskNumber)
        assertEquals("in_review", payload.messages.first().taskStatus)
        assertEquals("Android-Developer-2", payload.messages.first().taskAssigneeName)
        assertEquals("#42", threadMessageTaskMetaLabel(payload.messages.first()))
        assertEquals("3 replies · 2 new", threadMessageRepliesMetaLabel(payload.messages.first()))
        assertEquals("👍", payload.messages.first().reactions.first().emoji)
        assertEquals(2, payload.messages.first().reactions.first().count)
        assertEquals(true, payload.messages.first().reactions.first().selected)
        assertEquals("log.txt", payload.messages.first().attachments.first().name)
        assertEquals("https://cdn.example/screen.png", payload.messages.first().attachments[1].thumbnailUrl)
    }

    @Test
    fun buildsSharedThreadPayloadPreservingActionCardMetadata() {
        val base =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "loading"
            )
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "u1", email = "artin@slock.ai"),
                accessToken = "access",
                refreshToken = "refresh"
            )

        val payload =
            buildSharedThreadPayloadFromMessagesPage(
                basePayload = base,
                session = session,
                page =
                    ThreadMessagesPage(
                        messages =
                            listOf(
                                WireMessage(
                                    id = "m-action",
                                    channelId = "channel-1",
                                    senderType = "agent",
                                    senderId = "agent-cindy",
                                    senderName = "Cindy",
                                    messageType = "chat",
                                    content = "Create agent action",
                                    createdAt = "2026-06-09T03:00:00Z",
                                    actionMetadata =
                                        WireActionCardMetadata(
                                            kind = "action-card",
                                            state = "prepared",
                                            action =
                                                WireActionCardAction(
                                                    type = "agent:create",
                                                    name = "Pi-Frontend-Chrome插件专家",
                                                    description = "负责 Chrome 插件前端工程与视觉对齐。",
                                                    suggestedComputer = "machine-2",
                                                    draftHint = "请创建这个专家 agent"
                                                )
                                        )
                                )
                            ),
                        canLoadMoreBefore = false,
                        canLoadMoreAfter = null
                    )
            )

        val card = actionCardFromMessageMetadata(payload.messages.single().metadata)
        assertEquals("agent:create", card?.type)
        assertEquals("prepared", card?.state)
        assertEquals("Pi-Frontend-Chrome插件专家", card?.name)
        assertEquals("负责 Chrome 插件前端工程与视觉对齐。", card?.description)
        assertEquals("machine-2", card?.suggestedComputer)
        assertEquals("请创建这个专家 agent", card?.draftHint)
    }

    @Test
    fun buildsSharedThreadPayloadPreservingChannelActionCardMetadata() {
        val base =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "loading"
            )
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "u1", email = "artin@slock.ai"),
                accessToken = "access",
                refreshToken = "refresh"
            )
        val payload =
            buildSharedThreadPayloadFromMessagesPage(
                basePayload = base,
                session = session,
                page =
                    ThreadMessagesPage(
                        messages =
                            listOf(
                                WireMessage(
                                    id = "m-channel-create",
                                    channelId = "channel-1",
                                    senderType = "agent",
                                    senderId = "agent-cindy",
                                    senderName = "Cindy",
                                    messageType = "chat",
                                    content = "Create channel action",
                                    createdAt = "2026-06-09T03:00:00Z",
                                    actionMetadata =
                                        WireActionCardMetadata(
                                            kind = "action-card",
                                            state = "prepared",
                                            action =
                                                WireActionCardAction(
                                                    type = "channel:create",
                                                    name = "design-review",
                                                    description = "Coordinate visual review.",
                                                    visibility = "private",
                                                    initialHumans = listOf("u2"),
                                                    initialAgents = listOf("agent-2")
                                                )
                                        )
                                ),
                                WireMessage(
                                    id = "m-channel-members",
                                    channelId = "channel-1",
                                    senderType = "agent",
                                    senderId = "agent-cindy",
                                    senderName = "Cindy",
                                    messageType = "chat",
                                    content = "Add members action",
                                    createdAt = "2026-06-09T03:01:00Z",
                                    actionMetadata =
                                        WireActionCardMetadata(
                                            kind = "action-card",
                                            state = "prepared",
                                            action =
                                                WireActionCardAction(
                                                    type = "channel:add_member",
                                                    channel = "channel-2",
                                                    humans = listOf("u3"),
                                                    agents = listOf("agent-3"),
                                                    draftHint = "Bring the review owners in."
                                                )
                                        )
                                )
                            ),
                        canLoadMoreBefore = false,
                        canLoadMoreAfter = null
                    )
            )

        val createCard = actionCardFromMessageMetadata(payload.messages.first().metadata)
        val addMembersCard = actionCardFromMessageMetadata(payload.messages.last().metadata)

        assertEquals("channel:create", createCard?.type)
        assertEquals("private", createCard?.visibility)
        assertEquals(listOf("u2"), createCard?.initialHumans)
        assertEquals(listOf("agent-2"), createCard?.initialAgents)
        assertEquals("channel:add_member", addMembersCard?.type)
        assertEquals("channel-2", addMembersCard?.channel)
        assertEquals(listOf("u3"), addMembersCard?.humans)
        assertEquals(listOf("agent-3"), addMembersCard?.agents)
        assertEquals("Bring the review owners in.", addMembersCard?.draftHint)
    }

    @Test
    fun parsesCanonicalAuthorAndTimestampForOwnUserRow() {
        val base =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "loading",
                localOffsetMinutes = 480,
                todayEpochDay = 20593
            )
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "u1", email = "artin@slock.ai"),
                accessToken = "access",
                refreshToken = "refresh"
            )

        val payload =
            buildSharedThreadPayloadFromMessagesPage(
                basePayload = base,
                session = session,
                page =
                    ThreadMessagesPage(
                        messages =
                            listOf(
                                WireMessage(
                                    id = "m1",
                                    channelId = "channel-1",
                                    senderType = "user",
                                    senderId = "u1",
                                    senderName = "Artin",
                                    content = "directory author",
                                    createdAt = "2026-05-19T16:00:00Z"
                                )
                            ),
                        canLoadMoreBefore = false,
                        canLoadMoreAfter = null
                    )
            )

        val message = payload.messages.single()
        assertEquals("Artin", message.authorName)
        assertEquals("human", message.authorKind)
        assertEquals(true, message.isOwn)
        assertEquals("2026-05-19T16:00:00Z", message.timestampLabel)
        assertEquals("00:00", payload.formatTimestampLabel(message.timestampLabel))
    }

    @Test
    fun formatsMessageTimestampWithPayloadTimeFormatPreference() {
        val payload =
            KuiklyThreadScreenPayload(
                localOffsetMinutes = 480,
                todayEpochDay = slockEpochDayForDate(2026, 5, 20),
                timeFormat = "12h"
            )

        assertEquals("12:00 AM", payload.formatTimestampLabel("2026-05-19T16:00:00Z"))
    }

    @Test
    fun formatsMessageTimestampWhenHostOmitsTodayEpochDay() {
        val payload =
            KuiklyThreadScreenPayload(
                localOffsetMinutes = 480,
                nowEpochMillis = 1779206400000L
            )

        assertEquals("00:00", payload.formatTimestampLabel("2026-05-19T16:00:00Z"))
    }

    @Test
    fun parsesCanonicalAgentSenderRow() {
        val base =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "design",
                sourceThreadId = "channel-1",
                status = "loading"
            )
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "u-artin", email = "artin@slock.ai"),
                accessToken = "access",
                refreshToken = "refresh"
            )

        val payload =
            buildSharedThreadPayloadFromMessagesPage(
                basePayload = base,
                session = session,
                page =
                    ThreadMessagesPage(
                        messages =
                            listOf(
                                WireMessage(
                                    id = "m1",
                                    channelId = "channel-1",
                                    senderType = "agent",
                                    senderId = "agent-android",
                                    senderName = "Android Developer",
                                    content = "agent visual row",
                                    createdAt = "2026-05-19T16:00:00Z"
                                )
                            ),
                        canLoadMoreBefore = false,
                        canLoadMoreAfter = null
                    )
            )

        val message = payload.messages.single()
        assertEquals("agent", message.authorKind)
        assertEquals("Android Developer", message.authorName)
        assertEquals("agent-android", message.authorId)
        assertEquals(false, message.isOwn)
    }

    @Test
    fun appendsSentMessageFromSendResponseWhenRefreshIsUnavailable() {
        val base =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "content",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "u2",
                            authorName = "Cindy",
                            body = "existing"
                        )
                    )
            )
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "u1", email = "artin@slock.ai"),
                accessToken = "access",
                refreshToken = "refresh"
            )

        val payload =
            appendSharedThreadSentMessageFromPage(
                basePayload = base,
                session = session,
                page =
                    ThreadMessagesPage(
                        messages =
                            listOf(
                                WireMessage(
                                    id = "m2",
                                    channelId = "channel-1",
                                    senderType = "user",
                                    senderId = "u1",
                                    senderName = "Artin",
                                    content = "sent from shared composer",
                                    createdAt = "2026-05-19T16:00:00Z"
                                )
                            ),
                        canLoadMoreBefore = null,
                        canLoadMoreAfter = null
                    )
            )

        assertEquals("content", payload.status)
        assertEquals(listOf("m1", "m2"), payload.messages.map { it.id })
        assertEquals(true, payload.messages.last().isOwn)
        assertEquals("sent from shared composer", payload.messages.last().body)
    }

    @Test
    fun picksCurrentServerSummaryForSharedHomeShellLoadingState() {
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "u1", email = "artin@slock.ai"),
                accessToken = "access",
                refreshToken = "refresh",
                selectedServerId = "server-2",
                servers =
                    listOf(
                        StoredAuthServer(id = "server-1", name = "First", slug = "first"),
                        StoredAuthServer(id = "server-2", name = "Second", slug = "second")
                    )
            )

        val current = session.currentServerSummary()

        assertEquals("server-2", current?.id?.value)
        assertEquals("Second", current?.name)
    }

    @Test
    fun parsesMessageFooterTaskMetadata() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-1",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "u1",
                            authorName = "Artin",
                            isOwn = true,
                            body = "please ship #163",
                            replyCount = 2,
                            taskId = "task-1",
                            taskNumber = 163,
                            taskStatus = "InReview",
                            taskAssigneeName = "Android-Developer-2"
                        )
                    )
            )

        val message = payload.messages.first()
        assertEquals(true, message.isOwn)
        assertEquals("task-1", message.taskId)
        assertEquals(163, message.taskNumber)
        assertEquals("InReview", message.taskStatus)
        assertEquals("Android-Developer-2", message.taskAssigneeName)
        assertEquals(2, message.replyCount)
        assertEquals("#163", threadMessageTaskMetaLabel(message))
        assertEquals("2 replies", threadMessageRepliesMetaLabel(message))
    }

    @Test
    fun resolvesThreadMentionsFromMembersButTaskFooterUsesLinkedTaskAssignee() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-1",
                members =
                    listOf(
                        KuiklyThreadMemberItem(
                            id = "agent_android",
                            displayName = "Android Developer",
                            handle = "Android-Developer",
                            kind = "agent"
                        ),
                        KuiklyThreadMemberItem(
                            id = "user_artin",
                            displayName = "Artin",
                            handle = "artin",
                            kind = "human"
                        )
                    ),
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "agent_android",
                            authorName = "Android Developer",
                            body = "Ping @agent_android and @user_artin",
                            replyCount = 5,
                            taskId = "task-1",
                            taskNumber = 10,
                            taskStatus = "InReview",
                            taskAssigneeName = "agent_android"
                        )
                    )
            )

        val message = payload.messages.first()
        assertEquals(
            "@Android Developer",
            payload.resolveMarkdownTagDisplayLabel(
                ThreadMarkdownTag("@agent_android", ThreadMarkdownTagKind.UserMention)
            )
        )
        assertEquals("Android Developer", payload.resolveTaskAssigneeDisplayName(message.taskAssigneeName))
        assertEquals(
            "#10",
            threadMessageTaskMetaLabel(
                message,
                resolvedTaskAssigneeName = null
            )
        )
        assertEquals(
            "#10 @Tasks Claimed Name",
            threadMessageTaskMetaLabel(
                message.withLinkedTask(
                    TaskSummary(
                        id = TaskId("task-1"),
                        title = "ship task",
                        status = TaskStatus.InReview,
                        taskNumber = 10,
                        messageId = MessageId("m1"),
                        assignee =
                            MemberProfile(
                                id = MemberId("agent_android"),
                                displayName = "Tasks Claimed Name",
                                handle = "Tasks-Claimed-Name",
                                kind = MemberKind.Agent
                            ),
                        updatedAtLabel = ""
                    )
                ),
                "Tasks Claimed Name"
            )
        )
    }

    @Test
    fun linkedTasksAreResolvedByMessageIdAndHideRawIds() {
        val assigneeId = "9d2a923b-7d46-43c3-bf5f-d12a6df8ef12"
        val message =
            KuiklyThreadMessageItem(
                id = "message-240",
                authorId = "user_artin",
                authorName = "Artin",
                body = "ship task",
                taskId = "task-240",
                taskNumber = 240,
                taskStatus = "InReview",
                taskAssigneeId = assigneeId,
                taskAssigneeName = assigneeId
            )
        val linkedTasks =
            TasksUiState(
                loadState =
                    ai.slock.compose.data.ScreenLoadState.Content(
                        ai.slock.compose.data.TasksContent(
                            summaryCounts = emptyMap(),
                            tasks =
                                listOf(
                                    TaskSummary(
                                        id = TaskId("task-240"),
                                        title = "ship task",
                                        status = TaskStatus.InReview,
                                        taskNumber = 240,
                                        messageId = MessageId("message-240"),
                                        assignee =
                                            MemberProfile(
                                                id = MemberId(assigneeId),
                                                displayName = "Android Developer",
                                                handle = "Android-Developer",
                                                kind = MemberKind.Agent
                                            ),
                                        updatedAtLabel = ""
                                    ),
                                    TaskSummary(
                                        id = TaskId("task-uuid-only"),
                                        title = "uuid only",
                                        status = TaskStatus.Todo,
                                        taskNumber = 241,
                                        messageId = MessageId("message-241"),
                                        assignee =
                                            MemberProfile(
                                                id = MemberId(assigneeId),
                                                displayName = assigneeId,
                                                handle = assigneeId,
                                                kind = MemberKind.Human
                                            ),
                                        updatedAtLabel = ""
                                    )
                                )
                        )
                    )
            ).threadLinkedTasksByMessageId()

        val linkedMessage = message.withLinkedTask(linkedTasks[message.id])
        assertEquals("Android Developer", linkedMessage.taskAssigneeName)
        assertEquals("#240 @Android Developer", threadMessageTaskMetaLabel(linkedMessage, linkedMessage.taskAssigneeName))

        val uuidOnlyMessage = message.copy(id = "message-241", taskNumber = 241).withLinkedTask(linkedTasks["message-241"])
        assertEquals(null, uuidOnlyMessage.taskAssigneeName)
        assertEquals("#241", threadMessageTaskMetaLabel(uuidOnlyMessage, uuidOnlyMessage.taskAssigneeName))
    }

    @Test
    fun threadTasksJoinStartsOnlyAfterContentGate() {
        val taskChannelId = ChannelId("channel-1")
        val loadingPayload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "loading"
            )
        val contentPayload = loadingPayload.copy(status = "content")

        assertFalse(shouldStartThreadTasksJoin(loadingPayload, startedThreadId = null, taskChannelId = taskChannelId))
        assertTrue(shouldStartThreadTasksJoin(contentPayload, startedThreadId = null, taskChannelId = taskChannelId))
        assertFalse(shouldStartThreadTasksJoin(contentPayload, startedThreadId = "channel-1", taskChannelId = taskChannelId))
        assertFalse(
            shouldStartThreadTasksJoin(
                contentPayload.copy(sourceThreadId = null),
                startedThreadId = null,
                taskChannelId = taskChannelId
            )
        )
    }

    @Test
    fun resolvedAuxiliaryThreadIdRejectsUnprovenReplyThreadHintUntilContent() {
        val launchPayload =
            KuiklyThreadScreenPayload(
                instanceId = "activity-thread",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = "wrong-thread",
                streamChannelId = "wrong-thread",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                status = "loading"
            )

        assertEquals(null, launchPayload.resolvedAuxiliaryThreadId(launchPayload))
        assertEquals(
            "thread-m1",
            launchPayload
                .copy(
                    sourceThreadId = "thread-m1",
                    streamChannelId = "thread-m1",
                    status = "content"
                )
                .resolvedAuxiliaryThreadId(launchPayload)
        )
        assertEquals(
            "wrong-thread",
            launchPayload
                .copy(status = "content")
                .resolvedAuxiliaryThreadId(launchPayload)
        )
    }

    @Test
    fun threadTasksJoinUsesResolvedAuxiliaryThreadId() {
        val launchPayload =
            KuiklyThreadScreenPayload(
                instanceId = "activity-thread",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = "wrong-thread",
                streamChannelId = "wrong-thread",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                status = "loading"
            )
        val resolvedPayload =
            launchPayload.copy(
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                status = "content"
            )
        val resolvedTaskChannelId = resolvedPayload.resolvedAuxiliaryThreadId(launchPayload)?.let(::ChannelId)

        assertFalse(shouldStartThreadTasksJoin(launchPayload, startedThreadId = null, taskChannelId = ChannelId("wrong-thread")))
        assertTrue(shouldStartThreadTasksJoin(resolvedPayload, startedThreadId = null, taskChannelId = resolvedTaskChannelId))
        assertFalse(shouldStartThreadTasksJoin(resolvedPayload, startedThreadId = null, taskChannelId = ChannelId("wrong-thread")))
    }

    @Test
    fun hidesRawUuidTaskAssigneeUntilMemberNameCanBeResolved() {
        val assigneeId = "9d2a923b-7d46-43c3-bf5f-d12a6df8ef12"
        val base =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "loading"
            )
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "u1", email = "artin@slock.ai"),
                accessToken = "access",
                refreshToken = "refresh"
            )

        val payload =
            buildSharedThreadPayloadFromMessagesPage(
                basePayload = base,
                session = session,
                page =
                    ThreadMessagesPage(
                        messages =
                            listOf(
                                WireMessage(
                                    id = "m1",
                                    channelId = "channel-1",
                                    senderType = "user",
                                    senderId = "u1",
                                    senderName = "Artin",
                                    content = "please ship #236",
                                    taskNumber = 236,
                                    taskStatus = "InReview",
                                    taskAssigneeId = assigneeId
                                )
                            ),
                        canLoadMoreBefore = false,
                        canLoadMoreAfter = false
                    )
            )

        val message = payload.messages.first()
        assertEquals(assigneeId, message.taskAssigneeId)
        assertEquals(null, message.taskAssigneeName)
        assertEquals("#236", threadMessageTaskMetaLabel(message))
    }

    @Test
    fun resolvesUuidTaskAssigneeIdFromThreadMembers() {
        val assigneeId = "9d2a923b-7d46-43c3-bf5f-d12a6df8ef12"
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-1",
                members =
                    listOf(
                        KuiklyThreadMemberItem(
                            id = assigneeId,
                            displayName = "Android Developer",
                            handle = "Android-Developer",
                            kind = "agent"
                        )
                    ),
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "user_artin",
                            authorName = "Artin",
                            body = "ship task",
                            taskId = "task-236",
                            taskNumber = 236,
                            taskStatus = "InReview",
                            taskAssigneeId = assigneeId,
                            taskAssigneeName = assigneeId
                        )
                    )
            )

        val message = payload.messages.first()
        assertEquals(assigneeId, message.taskAssigneeId)
        assertEquals("Android Developer", payload.resolveTaskAssigneeDisplayName(message.taskAssigneeName, message.taskAssigneeId))
        assertEquals(
            "#236",
            threadMessageTaskMetaLabel(
                message,
                resolvedTaskAssigneeName = null
            )
        )
    }

    @Test
    fun threadListBoundsUseMeasuredItemsForBottomOffset() {
        assertEquals(0, threadMessagesBeyondBoundsItemCount(-1))
        assertEquals(1, threadMessagesBeyondBoundsItemCount(1))
        assertEquals(2, threadMessagesBeyondBoundsItemCount(80))
        assertEquals(
            -568,
            threadLatestItemBottomAnchorScrollOffset(
                itemSize = 72,
                viewportHeight = 640,
                visibleItemCount = 2,
                totalItemCount = 8
            )
        )
        assertEquals(
            160,
            threadLatestItemBottomAnchorScrollOffset(
                itemSize = 800,
                viewportHeight = 640,
                visibleItemCount = 2,
                totalItemCount = 8
            )
        )
    }

    @Test
    fun parsesSystemMessageMetadataAndBuildsCollapsedSummary() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-1",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m-system",
                            authorId = "system",
                            authorName = "System",
                            messageType = "System",
                            systemEventType = "member_joined",
                            timestampLabel = "2026-05-09 21:53:33.024426+00",
                            body = "<p>Cindy joined<br>the channel</p>",
                            metadata =
                                mapOf(
                                    "actor" to "Cindy",
                                    "source" to "<b>invite</b>"
                                )
                        )
                    )
            )

        val message = payload.messages.first()
        assertEquals(true, message.isSystemMessage())
        assertEquals("member_joined", message.systemEventType)
        assertEquals("Cindy", message.metadata["actor"])
        assertEquals("invite", message.metadata["source"]?.sanitizeThreadSystemText())
        assertEquals("10:00 Cindy joined\nthe channel", message.collapsedSystemSummary("10:00"))
        assertEquals("Type: member_joined\nactor: Cindy\nsource: invite", message.expandedSystemDetails())
    }

    @Test
    fun sanitizesSystemMessageInlineWhitespaceWithoutNativeRegexEscapes() {
        assertEquals(
            "one two three four five",
            "one\t two\u000Bthree\u000Cfour\rfive".sanitizeThreadSystemText()
        )
    }

    @Test
    fun formatsRawTimestampInSharedThreadPayload() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-1",
                localOffsetMinutes = 480,
                todayEpochDay = 20583,
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "u1",
                            authorName = "Artin",
                            timestampLabel = "2026-05-09 21:53:33.024426+00",
                            body = "raw utc timestamp"
                        )
                    )
            )

        assertEquals("2026-05-09 21:53:33.024426+00", payload.messages.first().timestampLabel)
        assertEquals("05:53", payload.formatTimestampLabel(payload.messages.first().timestampLabel))
    }

    @Test
    fun parsesReplyContextFields() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-2",
                title = "Threads",
                subtitle = "Origin: #general · hello",
                navigationLabel = "Close",
                showModeTabs = false,
                showReplyActions = false,
                showOpenInChannelAction = true,
                originStatus = "message",
                originParentMessageId = "m-parent",
                originMessage =
                    KuiklyThreadMessageItem(
                        id = "m-parent",
                        authorId = "u1",
                        authorName = "Artin",
                        body = "hello from parent"
                    )
            )

        assertEquals("thread-2", payload.instanceId)
        assertEquals("Close", payload.navigationLabel)
        assertEquals(false, payload.showModeTabs)
        assertEquals(false, payload.showReplyActions)
        assertEquals(true, payload.showSettingsAction)
        assertEquals(true, payload.showOpenInChannelAction)
        assertEquals("message", payload.originStatus)
        assertEquals("m-parent", payload.originParentMessageId)
        assertEquals("hello from parent", payload.originMessage?.body)
    }

    @Test
    fun sharedShellKeepsSupportedThreadEventAffordances() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                showReplyActions = true,
                showOpenInChannelAction = true,
                showMessageSaveAction = true,
                showTaskOpenActions = true,
                showTaskStatusActions = true,
                canLoadMoreBefore = true,
                canLoadMoreAfter = true,
                canAddMembers = true,
                showSettingsAction = true
            ).withSharedShellEventCapabilities()

        assertEquals(true, payload.showReplyActions)
        assertEquals(true, payload.showOpenInChannelAction)
        assertEquals(true, payload.showMessageSaveAction)
        assertEquals(true, payload.showTaskOpenActions)
        assertEquals(true, payload.showTaskStatusActions)
        assertEquals(true, payload.canLoadMoreBefore)
        assertEquals(true, payload.canLoadMoreAfter)
        assertEquals(true, payload.canAddMembers)
        assertEquals(true, payload.showSettingsAction)
        assertEquals(true, payload.showMembersAction)
    }

    @Test
    fun sharedShellHidesChannelChromeForReplyThread() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-reply-1",
                title = "Threads",
                subtitle = "Origin: #general · hello",
                showModeTabs = false,
                showMembersAction = true,
                showSettingsAction = true,
                showOpenInChannelAction = true,
                originParentMessageId = "message-1"
            ).withSharedShellEventCapabilities()

        assertEquals(false, payload.showSettingsAction)
        assertEquals(false, payload.showMembersAction)
        assertEquals(true, payload.showOpenInChannelAction)
        assertEquals(null, payload.subtitle)
    }

    @Test
    fun parsesUploadedAttachmentsForSharedComposer() {
        val attachments =
            parseSharedThreadUploadedAttachments(
                JSONObject().apply {
                    put(
                        "bodyText",
                        """
                        {
                          "attachments": [
                            {
                              "id": "att-1",
                              "filename": "report.pdf",
                              "mimeType": "application/pdf",
                              "sizeBytes": 12288
                            }
                          ]
                        }
                        """.trimIndent()
                    )
                }
            )

        assertEquals(1, attachments.size)
        assertEquals("att-1", attachments.first().id)
        assertEquals("report.pdf", attachments.first().fileName)
        assertEquals("application/pdf", attachments.first().mimeType)
    }

    @Test
    fun parsesPickedAttachmentFileRefsForSharedUpload() {
        val files =
            parseSharedThreadPickedAttachmentFiles(
                JSONObject().apply {
                    put(
                        "files",
                        JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("id", "picked-1")
                                    put("fileName", "report.txt")
                                    put("mimeType", "text/plain")
                                    put("sizeBytes", 5L)
                                    put("previewUri", "content://picked/report.txt")
                                    put("fileRef", "content://picked/report.txt")
                                }
                            )
                        }
                    )
                }
            )

        assertEquals(1, files.size)
        assertEquals("picked-1", files.first().id)
        assertEquals("report.txt", files.first().fileName)
        assertEquals("text/plain", files.first().mimeType)
        assertEquals(5L, files.first().sizeBytes)
        assertEquals("content://picked/report.txt", files.first().previewUri)
        assertEquals("content://picked/report.txt", files.first().fileRef)
        assertEquals(0, files.first().bytes.size)
    }

    @Test
    fun parsesPickedAttachmentFileRefsFromBodyTextForSharedUpload() {
        val files =
            parseSharedThreadPickedAttachmentFiles(
                JSONObject().apply {
                    put(
                        "bodyText",
                        JSONObject()
                            .put(
                                "files",
                                JSONArray().apply {
                                    put(
                                        JSONObject().apply {
                                            put("id", "picked-1")
                                            put("fileName", "report.txt")
                                            put("mimeType", "text/plain")
                                            put("sizeBytes", 5L)
                                            put("previewUri", "content://picked/report.txt")
                                            put("fileRef", "content://picked/report.txt")
                                        }
                                    )
                                }
                            )
                            .toString()
                    )
                }
            )

        assertEquals(1, files.size)
        assertEquals("picked-1", files.first().id)
        assertEquals("report.txt", files.first().fileName)
        assertEquals("text/plain", files.first().mimeType)
        assertEquals(5L, files.first().sizeBytes)
        assertEquals("content://picked/report.txt", files.first().previewUri)
        assertEquals("content://picked/report.txt", files.first().fileRef)
        assertEquals(0, files.first().bytes.size)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun parsesPickedAttachmentBase64FallbackForSharedUpload() {
        val files =
            parseSharedThreadPickedAttachmentFiles(
                JSONObject().apply {
                    put(
                        "files",
                        JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("id", "picked-1")
                                    put("fileName", "report.txt")
                                    put("bodyBase64", Base64.encode("hello".encodeToByteArray()))
                                }
                            )
                        }
                    )
                }
            )

        assertEquals(1, files.size)
        assertEquals("hello", files.first().bytes.decodeToString())
        assertEquals(null, files.first().fileRef)
    }
}
