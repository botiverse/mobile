package build.raft.app

import build.raft.app.model.AgentStatus
import build.raft.app.model.ChannelId
import build.raft.app.model.AvatarReference
import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ConversationSummary
import build.raft.app.model.ForwardMessageItem
import build.raft.app.model.InboxItemKind
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Presence
import build.raft.app.model.MemberProfile
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.RaftRelativeTimeLabel
import build.raft.app.model.RaftTimeFormat12Hour
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.resolveRaftRelativeTimeLabel
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.model.threadIdentity
import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.markdown.ThreadMarkdownTagKind
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.AuthPendingOpenRequest
import build.raft.app.data.InboxItem
import build.raft.app.data.PlatformFile
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.SharedTasksDataStore
import build.raft.app.data.SharedTasksDataStoreStatus
import build.raft.app.data.SharedTasksProjectionSlice
import build.raft.app.data.SharedTasksScope
import build.raft.app.data.SharedTaskReferenceResolutionSource
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.SharedConversationIdentityProvider
import build.raft.app.data.SharedMembersMemberSuggestion
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersState
import build.raft.app.data.RaftTasksApi
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.TasksUiState
import build.raft.app.data.TaskSnapshot
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.WireActionCardAction
import build.raft.app.data.WireActionCardMetadata
import build.raft.app.data.toWireJsonObject
import build.raft.app.data.WireAttachment
import build.raft.app.data.WireMessage
import build.raft.app.data.WireReaction
import build.raft.app.data.WireThreadSummary
import build.raft.app.data.actionCardFromMessageMetadata
import build.raft.app.data.appendSharedThreadSentMessageFromPage
import build.raft.app.data.buildSharedThreadPayloadFromMessagesPage
import build.raft.app.data.convertMessageToCanonicalTask
import build.raft.app.data.parseSharedThreadPickedPlatformFiles
import build.raft.app.data.parseSharedThreadUploadedAttachments
import build.raft.app.data.parseSharedMessageContext
import build.raft.app.data.toHomeChannelRow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import build.raft.app.raftMonoFamilyForWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import build.raft.app.compat.json.JSONArray
import build.raft.app.compat.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ThreadPayloadTest {
    @Test
    fun firstContentPerformanceBudgetLocksPayloadLayoutAndDrawThresholds() {
        assertFalse(evaluateThreadFirstContentPerformanceBudget().passed)
        assertTrue(
            evaluateThreadFirstContentPerformanceBudget(
                payloadReadyMs = ThreadPayloadReadyBudgetMs,
                layoutMs = ThreadFirstLayoutBudgetMs,
                drawMs = ThreadFirstDrawBudgetMs
            ).passed
        )

        val overBudget =
            evaluateThreadFirstContentPerformanceBudget(
                payloadReadyMs = ThreadPayloadReadyBudgetMs + 1,
                layoutMs = ThreadFirstLayoutBudgetMs + 1,
                drawMs = ThreadFirstDrawBudgetMs + 1
            )
        assertFalse(overBudget.passed)
        assertFalse(overBudget.payloadReadyWithinBudget ?: true)
        assertFalse(overBudget.layoutWithinBudget ?: true)
        assertFalse(overBudget.drawWithinBudget ?: true)
    }

    private val launchJson = Json { ignoreUnknownKeys = true }

    @Test
    fun threadLaunchPayloadOpensReplyThreadWithMissingParentKind() {
        // task #304/#307 (cae1eb2e4): the parent channel+message tuple alone is
        // enough to open a reply thread; an unresolved parent kind stays null on
        // the payload instead of rejecting the launch.
        val warnings = mutableListOf<String>()
        val identity =
            ThreadLaunchConversationIdentity(
                threadId = ThreadId("thread-1"),
                channelId = ChannelId("parent-1"),
                thread =
                    ThreadConversation(
                        parentChannelId = ChannelId("parent-1"),
                        parentMessageId = MessageId("msg-1"),
                        streamChannelId = ChannelId("thread-1"),
                        parentSurfaceKind = null,
                        parentChannelKind = null
                    ),
                kind = ConversationKind.Thread
            )

        val standalone =
            buildStandaloneConversationThreadLaunchPayload(
                conversation = identity,
                timeContext = null,
                onWarn = { warnings.add(it) }
            )
        val appShell =
            buildAppShellThreadPayload(
                conversation = identity,
                onWarn = { warnings.add(it) }
            )

        assertNotNull(standalone)
        assertNotNull(appShell)
        assertTrue(warnings.none { it.contains("reply_identity_incomplete") }, warnings.toString())
    }

    @Test
    fun standaloneReplyThreadChannelKindDoesNotChangeResolvedRouteIdentity() {
        val identity =
            buildThreadLaunchConversationIdentity(
                kind = ConversationKind.Thread,
                channelId = ChannelId("reply-thread-1"),
                threadChannelId = ChannelId("reply-thread-1"),
                parentChannelId = ChannelId("channel-1"),
                parentMessageId = MessageId("message-1"),
                parentSurfaceKind = ConversationKind.Channel.threadParentSurfaceKindOrNull(),
                parentChannelKind = ConversationKind.Channel.threadChannelKindWireValue()
            )
        val launch =
            requireNotNull(buildStandaloneConversationThreadLaunchPayload(
                conversation = identity,
                timeContext = null,
                anchorMessageId = "reply-message-1"
            ))

        val previousShape = launch.copy(channelKind = null)
        val newRouteIdentity =
            checkNotNull(launch.toInitialPayloadOrNull("fallback")).resolveThreadRouteIdentity()
        val previousRouteIdentity =
            checkNotNull(previousShape.toInitialPayloadOrNull("fallback")).resolveThreadRouteIdentity()

        assertEquals(RaftThreadSurfaceKind.Channel, launch.channelKind)
        assertEquals(null, previousShape.channelKind)
        assertEquals(previousRouteIdentity, newRouteIdentity)
    }

    @Test
    fun threadLaunchIdentityBuilderParsesDirectParentSurfaceKindThroughContract() {
        // t6/#420 construction-side: the final legacy parent-surface table did not accept
        // "direct" and could drop a DM parent shape before the payload builders saw it.
        // Parent-surface strings now go through the #417 contract, so "direct" qualifies as
        // the dm parent shape consistently with "dm"/"direct_message".
        val identity =
            buildThreadLaunchConversationIdentity(
                kind = ConversationKind.Thread,
                channelId = ChannelId("reply-stream-1"),
                threadChannelId = ChannelId("reply-stream-1"),
                parentChannelId = ChannelId("dm-parent-1"),
                parentMessageId = MessageId("parent-message-1"),
                parentSurfaceKind = "direct"
            )

        assertEquals(RaftThreadSurfaceKind.DirectMessage, identity.thread?.parentSurfaceKind)

        val payload = requireNotNull(buildStandaloneConversationThreadLaunchPayload(identity, timeContext = null))
        assertEquals(RaftThreadSurfaceKind.DirectMessage, payload.parentSurfaceKind)
    }

    @Test
    fun screenPayloadWireLaunchPreservesDirectParentSurfaceKind() {
        val launch =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "dm-parent-1",
                parentMessageId = "parent-message-1",
                parentSurfaceKind = "direct",
                sourceThreadId = "reply-stream-1",
                streamChannelId = "reply-stream-1"
            ).toWireThreadLaunchPayload()

        assertEquals(RaftThreadSurfaceKind.DirectMessage, launch.parentSurfaceKind)
    }

    @Test
    fun composerSendBodyFallsBackToAttachmentLabelWhenDraftIsBlank() {
        assertEquals("[1 attachment]", threadComposerMessageBodyForSend("   ", attachmentCount = 1))
        assertEquals("[2 attachments]", threadComposerMessageBodyForSend("", attachmentCount = 2))
    }

    @Test
    fun composerSendBodyKeepsTypedDraftWhenPresent() {
        assertEquals("hello", threadComposerMessageBodyForSend("  hello  ", attachmentCount = 1))
        assertEquals("", threadComposerMessageBodyForSend("   ", attachmentCount = 0))
    }

    @Test
    fun composerPendingImageAttachmentGroupIncludesReadyImagesForGallery() {
        val attachments =
            listOf(
                RaftPendingAttachmentItem(
                    id = "image-1",
                    fileName = "first.png",
                    mimeType = "image/png",
                    previewUri = "file://first"
                ),
                RaftPendingAttachmentItem(
                    id = "file-1",
                    fileName = "notes.txt",
                    mimeType = "text/plain",
                    previewUri = "file://notes"
                ),
                RaftPendingAttachmentItem(
                    id = "image-2",
                    fileName = "second.jpg",
                    mimeType = "image/jpeg",
                    previewUri = "file://second"
                )
            )

        val group = attachments.composerPendingImageAttachmentGroup("image-2")

        assertEquals(1, group?.currentIndex)
        assertEquals(listOf("image-1", "image-2"), group?.images?.map { it.id })
        assertEquals(listOf("file://first", "file://second"), group?.images?.map { it.thumbnailUrl })
    }

    @Test
    fun composerPendingImageAttachmentGroupIgnoresUploadingFailedAndMissingPreviewImages() {
        val attachments =
            listOf(
                RaftPendingAttachmentItem(
                    id = "image-uploading",
                    fileName = "uploading.png",
                    mimeType = "image/png",
                    previewUri = "file://uploading",
                    status = "uploading"
                ),
                RaftPendingAttachmentItem(
                    id = "image-failed",
                    fileName = "failed.png",
                    mimeType = "image/png",
                    previewUri = "file://failed",
                    status = "failed"
                ),
                RaftPendingAttachmentItem(
                    id = "image-no-preview",
                    fileName = "no-preview.png",
                    mimeType = "image/png"
                )
            )

        assertEquals(null, attachments.composerPendingImageAttachmentGroup("image-uploading"))
        assertEquals(null, attachments.composerPendingImageAttachmentGroup("image-failed"))
        assertEquals(null, attachments.composerPendingImageAttachmentGroup("image-no-preview"))
    }

    @Test
    fun pickedSingleImageOpensAnnotation() {
        assertEquals(
            ThreadPickedAttachmentComposerAction.OpenImageAnnotation,
            resolveThreadPickedAttachmentComposerAction(
                action = "attachImage",
                fileCount = 1,
            )
        )
    }

    @Test
    fun composerImageButtonMapsTapToLibraryAndLongPressToCamera() {
        assertEquals(ThreadComposerAttachImageAction, threadComposerImageAction(longPress = false))
        assertEquals(ThreadComposerCaptureImageAction, threadComposerImageAction(longPress = true))
    }

    @Test
    fun capturedImageUsesTheSameAnnotationPipelineAsPickedImage() {
        assertEquals(
            ThreadPickedAttachmentComposerAction.OpenImageAnnotation,
            resolveThreadPickedAttachmentComposerAction(
                action = ThreadComposerCaptureImageAction,
                fileCount = 1,
                allFilesAreImages = true,
            )
        )
    }

    @Test
    fun pickedMultipleImagesOpenAnnotationGallery() {
        assertEquals(
            ThreadPickedAttachmentComposerAction.OpenImageAnnotation,
            resolveThreadPickedAttachmentComposerAction(
                action = "attachImage",
                fileCount = 2,
            )
        )
    }

    @Test
    fun pickedVideoNeverOpensImageAnnotationEvenFromImagePickerAction() {
        assertEquals(
            ThreadPickedAttachmentComposerAction.UploadToComposer,
            resolveThreadPickedAttachmentComposerAction(
                action = "attachImage",
                fileCount = 1,
                allFilesAreImages = false,
            )
        )
    }

    @Test
    fun composerImagePickerRequiresMatchingImageMimeAndExtension() {
        assertTrue(raftComposerImagePickerIsStrictImage(fileName = "photo.jpg", mimeType = "image/jpeg"))
        assertFalse(raftComposerImagePickerIsStrictImage(fileName = "clip.jpg", mimeType = "video/mp4"))
        assertFalse(raftComposerImagePickerIsStrictImage(fileName = "clip.mp4", mimeType = "image/jpeg"))
        assertFalse(raftComposerImagePickerIsStrictImage(fileName = "photo.jpg", mimeType = ""))
        assertFalse(raftComposerImagePickerIsStrictImage(fileName = "photo", mimeType = "image/jpeg"))
    }

    @Test
    fun composerImagePickerRejectsMixedSelectionThroughTheProductionClassifier() {
        val files =
            listOf(
                PlatformFile(id = "image", fileName = "photo.png", mimeType = "image/png"),
                PlatformFile(id = "video", fileName = "clip.mp4", mimeType = "video/mp4"),
            )
        val allFilesAreImages = raftComposerImagePickerFilesAreStrictImages(files)

        assertFalse(allFilesAreImages)
        assertEquals(
            ThreadPickedAttachmentComposerAction.UploadToComposer,
            resolveThreadPickedAttachmentComposerAction(
                action = "attachImage",
                fileCount = files.size,
                allFilesAreImages = allFilesAreImages,
            )
        )
    }

    @Test
    fun pickedFileDoesNotOpenImageAnnotation() {
        assertEquals(
            ThreadPickedAttachmentComposerAction.UploadToComposer,
            resolveThreadPickedAttachmentComposerAction(
                action = "attachFile",
                fileCount = 1,
            )
        )
    }

    @Test
    fun pickedAttachmentComposerActionIgnoresEmptyPickerResult() {
        assertEquals(
            ThreadPickedAttachmentComposerAction.None,
            resolveThreadPickedAttachmentComposerAction(
                action = "attachImage",
                fileCount = 0,
            )
        )
    }

    @Test
    fun threadMentionSuggestionsRankThreadParticipantsBeforeChannelGroups() {
        val suggestions =
            listOf(
                mentionSuggestion("Parent Only", "parent-only", "u-parent-only", inMentionChannel = true),
                mentionSuggestion("Outside Only", "outside-only", "u-outside-only", inMentionChannel = false),
                mentionSuggestion("Thread Parent", "thread-parent", "u-thread-parent", inMentionChannel = true),
                mentionSuggestion("Thread Outside", "thread-outside", "u-thread-outside", inMentionChannel = false),
                mentionSuggestion("Build Agent", "build-agent", "agent-build", inMentionChannel = true, kind = MemberKind.Agent)
            )
        val messages =
            listOf(
                threadMessage("m1", "u-thread-parent", "thread-parent"),
                threadMessage("m2", "u-thread-outside", "thread-outside"),
                threadMessage("m3", "agent-build", "build-agent"),
            )

        val sorted =
            suggestions.sortForThreadMentionParticipants(
                threadMessages = messages,
                prioritizeThreadParticipants = true
            )

        assertEquals(
            listOf("build-agent", "thread-outside", "thread-parent", "parent-only", "outside-only"),
            sorted.map { it.handle }
        )
    }

    @Test
    fun threadMentionSuggestionsIncludeOriginAuthorInParticipantRanks() {
        val suggestions =
            listOf(
                mentionSuggestion("Cindy", "cindy", "u-cindy", inMentionChannel = true),
                mentionSuggestion("Origin", "origin", "u-origin", inMentionChannel = true),
                mentionSuggestion("Artin", "artin", "u-artin", inMentionChannel = true),
                mentionSuggestion("Guest", "guest", "u-guest", inMentionChannel = true)
            )
        val originMessage = threadMessage("origin-message", "u-origin", "origin")
        val messages =
            listOf(
                threadMessage("m1", "u-cindy", "cindy"),
                threadMessage("m2", "u-artin", "artin")
            )

        val sorted =
            suggestions.sortForThreadMentionParticipants(
                threadMessages = messages,
                originMessage = originMessage,
                prioritizeThreadParticipants = true
            )

        assertEquals(
            listOf("artin", "cindy", "origin", "guest"),
            sorted.map { it.handle }
        )
    }

    @Test
    fun threadMentionSuggestionsKeepOriginalOrderWhenNotThreadRanked() {
        val suggestions =
            listOf(
                mentionSuggestion("Cindy", "cindy", "u-cindy"),
                mentionSuggestion("Artin", "artin", "u-artin")
            )
        val messages = listOf(threadMessage("m1", "u-artin", "artin"))

        val sorted =
            suggestions.sortForThreadMentionParticipants(
                threadMessages = messages,
                prioritizeThreadParticipants = false
            )

        assertEquals(listOf("cindy", "artin"), sorted.map { it.handle })
    }

    @Test
    fun threadMessagePermalinkMatchesReactChannelRoute() {
        val payload =
            RaftThreadScreenPayload(
                sourceThreadId = "channel-1",
                channelKind = "channel"
            )

        val permalink =
            payload.threadMessagePermalink(
                message = threadMessage("message-1", "u-artin", "artin"),
                serverSlug = "workspace"
            )

        assertEquals(
            "https://app.raft.build/s/workspace/channel/channel-1?msg=message-1",
            permalink
        )
    }

    @Test
    fun threadMessagePermalinkUsesParentThreadParamForReplyRows() {
        val payload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "channel-1",
                parentMessageId = "parent-1",
                sourceThreadId = "thread-1",
                streamChannelId = "thread-1",
                parentChannelKind = "channel"
            )

        val permalink =
            payload.threadMessagePermalink(
                message = threadMessage("reply-1", "u-artin", "artin"),
                serverSlug = "workspace"
            )

        assertEquals(
            "https://app.raft.build/s/workspace/channel/channel-1?msg=reply-1&thread=channel-1%3Aparent-1",
            permalink
        )
    }

    @Test
    fun threadMessagePermalinkParsesDirectParentKindAsDm() {
        // t432 PR-A: the permalink DM check parses through the #417 contract — a
        // parent channel kind of "direct" routes as dm (the old private vocab table
        // rejected it and fell to the channel route).
        val payload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "dm-parent",
                parentMessageId = "parent-1",
                sourceThreadId = "thread-1",
                streamChannelId = "thread-1",
                parentChannelKind = "direct"
            )

        val permalink =
            payload.threadMessagePermalink(
                message = threadMessage("reply-1", "u-artin", "artin"),
                serverSlug = "workspace"
            )

        assertEquals(
            "https://app.raft.build/s/workspace/dm/dm-parent?msg=reply-1&thread=dm-parent%3Aparent-1",
            permalink
        )
    }

    @Test
    fun threadMessageRowPreservesFollowedThreadState() {
        val row =
            Message(
                id = MessageId("parent-1"),
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                threadChannelId = ChannelId("thread-1"),
                author = MemberProfile(
                    id = MemberId("u-artin"),
                    displayName = "Artin",
                    handle = "artin",
                    kind = MemberKind.Human
                ),
                timestampLabel = "Now",
                body = "Parent",
                replyCount = 4,
                isThreadFollowed = true
            ).toRaftThreadMessageItem(currentUserId = null)

        assertEquals("thread-1", row.threadChannelId)
        assertEquals(4, row.replyCount)
        assertTrue(row.isThreadFollowed)
    }

    @Test
    fun compactThreadAuthorSuppressionResetsAfterWindowDayOrAuthorChanges() {
        val base =
            RaftThreadMessageItem(
                id = "m1",
                authorId = "u-artin",
                authorName = "Artin",
                timestampLabel = "2026-06-27T01:00:00Z"
            )

        assertTrue(
            shouldSuppressCompactThreadAuthor(
                previous = base,
                next = base.copy(id = "m2", timestampLabel = "2026-06-27T01:30:00Z"),
                localOffsetMinutes = 0
            )
        )
        assertFalse(
            shouldSuppressCompactThreadAuthor(
                previous = base,
                next = base.copy(id = "m3", timestampLabel = "2026-06-27T01:30:01Z"),
                localOffsetMinutes = 0
            )
        )
        assertFalse(
            shouldSuppressCompactThreadAuthor(
                previous = base.copy(timestampLabel = "2026-06-27T23:50:00Z"),
                next = base.copy(id = "m4", timestampLabel = "2026-06-28T00:05:00Z"),
                localOffsetMinutes = 0
            )
        )
        assertFalse(
            shouldSuppressCompactThreadAuthor(
                previous = base.copy(timestampLabel = "2026-06-27T23:50:00Z"),
                next =
                    base.copy(
                        id = "local-send-1",
                        timestampLabel = "Now",
                        deliveryState = ThreadMessageDeliverySending,
                        pendingSendContext =
                            RaftThreadPendingSendContext(
                                channelId = "channel-1",
                                body = "optimistic"
                            )
                    ),
                localOffsetMinutes = 0
            )
        )
        assertFalse(
            shouldSuppressCompactThreadAuthor(
                previous = base,
                next = base.copy(id = "m5", authorId = "u-cindy", authorName = "Cindy", timestampLabel = "2026-06-27T01:05:00Z"),
                localOffsetMinutes = 0
            )
        )
        val twelveHourRenderConfig =
            ThreadTimelineRenderConfig(
                backgroundRefreshing = false,
                errorCode = null,
                originPreview = null,
                originErrorCode = null,
                timeContext =
                    RaftTimeContext(
                        localOffsetMinutes = 480,
                        todayEpochDay = raftEpochDayForDate(2026, 6, 28),
                        timeFormat = RaftTimeFormat12Hour
                    ),
                members = emptyList()
            )
        val compactTimestamp =
            twelveHourRenderConfig.formatCompactTimestampLabel(
                RaftThreadMessageItem(
                    id = "message-24h",
                    authorId = "user-1",
                    authorName = "User",
                    timestampLabel = "2026-06-27T12:34:00Z"
                )
            )
        val forwardedTimestamp =
            ForwardMessageItem(
                id = "forwarded-message-24h",
                authorId = "user-1",
                authorName = "User",
                timestampLabel = "2026-06-27T12:34:00Z"
            ).formatThreadForwardedTimestampLabel(twelveHourRenderConfig.timeContext)

        assertEquals("20:34", compactTimestamp)
        assertEquals(compactTimestamp, forwardedTimestamp)
        assertFalse(compactTimestamp.contains("Z"))
        assertFalse(compactTimestamp.contains("AM"))
        assertFalse(compactTimestamp.contains("PM"))
        assertEquals(
            "",
            ForwardMessageItem(
                id = "forwarded-message-missing-context",
                authorId = "user-1",
                authorName = "User",
                timestampLabel = "2026-06-27T12:34:00Z"
            ).formatThreadForwardedTimestampLabel(timeContext = null)
        )
    }

    @Test
    fun threadDateDividerLabelAppearsOnlyWhenLocalDayChanges() {
        val timeContext =
            RaftTimeContext(
                localOffsetMinutes = 0,
                todayEpochDay = raftEpochDayForDate(2026, 7, 4)
            )
        val julyOne =
            RaftThreadMessageItem(
                id = "m1",
                authorId = "u-artin",
                authorName = "Artin",
                timestampLabel = "2026-07-01T09:00:00Z"
            )
        val julyOneLater = julyOne.copy(id = "m2", timestampLabel = "2026-07-01T10:00:00Z")
        val julyThree = julyOne.copy(id = "m3", timestampLabel = "2026-07-03T10:00:00Z")
        val julyFour = julyOne.copy(id = "m4", timestampLabel = "2026-07-04T10:00:00Z")

        assertEquals("WEDNESDAY, JULY 1", threadDateDividerLabel(null, julyOne, timeContext))
        assertEquals(null, threadDateDividerLabel(julyOne, julyOneLater, timeContext))
        assertEquals("YESTERDAY", threadDateDividerLabel(julyOneLater, julyThree, timeContext))
        assertEquals("TODAY", threadDateDividerLabel(julyThree, julyFour, timeContext))
        assertEquals(null, threadDateDividerLabel(julyFour, julyFour.copy(id = "local", timestampLabel = "Now"), timeContext))
    }

    @Test
    fun threadDateDividerLabelsRefreshAcrossLocalMidnightWithoutReorderingMessages() {
        val previousDayMessage =
            RaftThreadMessageItem(
                id = "m1",
                authorId = "u-artin",
                authorName = "Artin",
                timestampLabel = "2026-07-22T20:21:00+08:00"
            )
        val currentDayMessage =
            previousDayMessage.copy(
                id = "m2",
                timestampLabel = "2026-07-23T00:01:00+08:00"
            )
        val provider =
            RaftTimeContextProvider(
                RaftTimeCapabilities(localOffsetMinutes = 480, timeFormat = "24h").contextAt(
                    parseRaftTimestampEpochMillisOrNull("2026-07-22T23:59:30+08:00")!!,
                    RaftLegacyTestDatetimeFoundationOwner,
                )
            )
        val renderConfig =
            RaftThreadScreenPayload(localOffsetMinutes = 480).toThreadTimelineRenderConfig()
        val launchDividerContext = renderConfig.dateDividerTimeContext(provider.todayEpochDay)

        assertEquals("TODAY", threadDateDividerLabel(null, previousDayMessage, launchDividerContext))

        provider.update(
            RaftTimeCapabilities(localOffsetMinutes = 480, timeFormat = "24h").contextAt(
                parseRaftTimestampEpochMillisOrNull("2026-07-23T00:01:05+08:00")!!,
                RaftLegacyTestDatetimeFoundationOwner,
            )
        )
        val refreshedContext = renderConfig.dateDividerTimeContext(provider.todayEpochDay)

        assertEquals("YESTERDAY", threadDateDividerLabel(null, previousDayMessage, refreshedContext))
        assertEquals("TODAY", threadDateDividerLabel(previousDayMessage, currentDayMessage, refreshedContext))
        assertEquals(
            listOf("m1", "m2"),
            listOf(previousDayMessage, currentDayMessage)
                .sortedBy { parseRaftTimestampEpochMillisOrNull(it.timestampLabel, refreshedContext.localOffsetMinutes) }
                .map(RaftThreadMessageItem::id)
        )
    }

    @Test
    fun threadDateDividerTextStyleUsesCompactMetaScale() {
        val style = threadDateDividerTextStyle()

        assertEquals(11.sp, style.fontSize)
        assertEquals(14.sp, style.lineHeight)
        assertEquals(0.sp, style.letterSpacing)
        // Space Mono ships weight in the family (Kuikly drops serialized weight),
        // so the bold divider uses the mono Bold family with Normal weight (#375).
        assertEquals(FontWeight.Normal, style.fontWeight)
        assertEquals(raftMonoFamilyForWeight(FontWeight.Bold), style.fontFamily)
    }

    @Test
    fun markdownChannelLaunchPayloadResolvesChannelNameToIdentityOnlyRoute() {
        val payload =
            buildMarkdownChannelLaunchPayload(
                currentPayload =
                    RaftThreadScreenPayload(
                        instanceId = "current-thread",
                        alignOwnMessagesRight = true,
                        localOffsetMinutes = 480,
                        timeFormat = RaftTimeFormat12Hour
                    ),
                home =
                    markdownChannelHomeSnapshot(
                        ConversationSummary(
                            threadId = ThreadId("channel-general-id"),
                            channelId = ChannelId("channel-general-id"),
                            channelType = "channel",
                            kind = ConversationKind.Channel,
                            name = "general"
                        )
                    ),
                channelLabel = "#General"
            )

        checkNotNull(payload)
        assertEquals(RaftThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals(RaftThreadSurfaceKind.Channel, payload.channelKind)
        assertEquals("channel-general-id", payload.sourceThreadId)
        assertEquals("channel-general-id", payload.streamChannelId)
        assertEquals("chat", payload.selectedTab)
        assertEquals("loading", payload.status)
        assertEquals(emptyList(), payload.messages)
        assertEquals(true, payload.alignOwnMessagesRight)
        assertEquals(480, payload.localOffsetMinutes)
        assertEquals(RaftTimeFormat12Hour, payload.timeFormat)
    }

    @Test
    fun markdownChannelLaunchPayloadPreservesJointChannelKind() {
        val payload =
            buildMarkdownChannelLaunchPayload(
                currentPayload = RaftThreadScreenPayload(),
                home =
                    markdownChannelHomeSnapshot(
                        ConversationSummary(
                            threadId = ThreadId("joint-local-id"),
                            channelId = ChannelId("joint-local-id"),
                            channelType = "joint",
                            kind = ConversationKind.Joint,
                            name = "members专修"
                        )
                    ),
                channelLabel = "members专修"
            )

        checkNotNull(payload)
        assertEquals(RaftThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("joint", payload.channelKind)
        assertEquals("joint-local-id", payload.streamChannelId)
    }

    @Test
    fun markdownChannelLaunchPayloadRejectsUnknownOrAmbiguousChannelNames() {
        val home =
            markdownChannelHomeSnapshot(
                ConversationSummary(
                    threadId = ThreadId("channel-a"),
                    channelId = ChannelId("channel-a"),
                    kind = ConversationKind.Channel,
                    name = "design"
                ),
                ConversationSummary(
                    threadId = ThreadId("channel-b"),
                    channelId = ChannelId("channel-b"),
                    kind = ConversationKind.Channel,
                    name = "Design"
                )
            )

        assertEquals(null, buildMarkdownChannelLaunchPayload(RaftThreadScreenPayload(), home, "#missing"))
        assertEquals(null, buildMarkdownChannelLaunchPayload(RaftThreadScreenPayload(), home, "#design"))
    }

    @Test
    fun markdownThreadRefLaunchPayloadResolvesChannelThreadToIdentityOnlyRoute() {
        val payload =
            buildMarkdownThreadRefLaunchPayload(
                currentPayload =
                    RaftThreadScreenPayload(
                        instanceId = "current-thread",
                        alignOwnMessagesRight = true,
                        compactConsecutiveMessages = true,
                        localOffsetMinutes = 480,
                        timeFormat = RaftTimeFormat12Hour
                    ),
                home =
                    markdownChannelHomeSnapshot(
                        ConversationSummary(
                            threadId = ThreadId("home-channel-id"),
                            channelId = ChannelId("home-channel-id"),
                            channelType = "joint",
                            kind = ConversationKind.Joint,
                            name = "首页专修"
                        )
                    ),
                channelLabel = "#首页专修:5fbf9608",
                threadShortId = "5fbf9608",
                parentMessageId = "5fbf9608-1111-2222-3333-444444444444",
                focusedMessageId = "reply-1",
                threadChannelId = "thread-channel-id"
            )

        checkNotNull(payload)
        assertEquals(RaftThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("joint", payload.parentChannelKind)
        assertEquals("home-channel-id", payload.parentChannelId)
        assertEquals("5fbf9608-1111-2222-3333-444444444444", payload.parentMessageId)
        assertEquals("thread-channel-id", payload.sourceThreadId)
        assertEquals("thread-channel-id", payload.streamChannelId)
        assertEquals("reply-1", payload.focusedMessageId)
        assertEquals("chat", payload.selectedTab)
        assertEquals("loading", payload.status)
        assertEquals(emptyList(), payload.messages)
        assertEquals(true, payload.alignOwnMessagesRight)
        assertEquals(true, payload.compactConsecutiveMessages)
        assertEquals(480, payload.localOffsetMinutes)
        assertEquals(RaftTimeFormat12Hour, payload.timeFormat)
    }

    @Test
    fun messageContextParserKeepsCanonicalThreadTargetForShortRefs() {
        val payload =
            parseSharedMessageContext(
                """
                {
                  "channelId": "thread-channel-id",
                  "targetMessageId": "reply-1",
                  "canonicalTarget": {
                    "kind": "thread",
                    "channelId": "home-channel-id",
                    "messageId": "reply-1",
                    "threadParentMessageId": "5fbf9608-1111-2222-3333-444444444444",
                    "threadChannelId": "thread-channel-id"
                  },
                  "messages": []
                }
                """.trimIndent(),
                ThreadId("fallback-thread")
            )

        assertEquals("thread", payload.canonicalTarget?.kind)
        assertEquals(ChannelId("home-channel-id"), payload.canonicalTarget?.channelId)
        assertEquals(MessageId("reply-1"), payload.canonicalTarget?.messageId)
        assertEquals(
            MessageId("5fbf9608-1111-2222-3333-444444444444"),
            payload.canonicalTarget?.threadParentMessageId
        )
        assertEquals(ChannelId("thread-channel-id"), payload.canonicalTarget?.threadChannelId)
    }

    @Test
    fun threadComposerOnlyShowsOnChatTab() {
        assertTrue(shouldShowThreadComposerForTab(joined = true, showComposer = true, selectedTab = "chat"))
        assertFalse(shouldShowThreadComposerForTab(joined = true, showComposer = true, selectedTab = "tasks"))
        assertFalse(shouldShowThreadComposerForTab(joined = true, showComposer = true, selectedTab = "files"))
        assertFalse(shouldShowThreadComposerForTab(joined = false, showComposer = true, selectedTab = "chat"))
        assertFalse(shouldShowThreadComposerForTab(joined = true, showComposer = false, selectedTab = "chat"))
    }

    @Test
    fun threadComposerDoesNotWaitForContentExtrasOrBottomAnchorReadiness() {
        assertTrue(
            shouldShowThreadComposerForReadyContent(
                joined = true,
                showComposer = true,
                selectedTab = "chat"
            )
        )
    }

    @Test
    fun cachedTimelineRendersWhileComposerWaitsForResolvedAccessInBothConversationSurfaces() {
        val uncachedLoadingPayload = RaftThreadScreenPayload(status = "loading")
        assertFalse(uncachedLoadingPayload.hasRenderableContentForComposer())
        assertTrue(
            uncachedLoadingPayload.shouldRenderThreadLoadingShell(
                timelineSlice = uncachedLoadingPayload.toThreadTimelineSlice(),
                accessResolution = ThreadAccessResolution.Loading
            )
        )
        listOf(RaftThreadSurfaceKind.Channel, RaftThreadSurfaceKind.ReplyThread).forEach { surfaceKind ->
            val cachedLoadingPayload =
                RaftThreadScreenPayload(
                    status = "loading",
                    surfaceKind = surfaceKind,
                    messages =
                        listOf(
                            RaftThreadMessageItem(
                                id = "m1",
                                authorId = "u1",
                                authorName = "Artin",
                                body = "cached"
                            )
                        )
                )
            assertFalse(cachedLoadingPayload.hasRenderableContentForComposer())
            assertFalse(
                cachedLoadingPayload
                    .copy(status = "content")
                    .hasRenderableContentForComposer(cachedLoadingPayload.toThreadTimelineSlice())
            )
            val cachedContentPayload = cachedLoadingPayload.copy(status = "content")
            assertFalse(
                cachedContentPayload.hasRenderableContentForComposer(
                    timelineSlice = cachedContentPayload.toThreadTimelineSlice(),
                    accessResolution = ThreadAccessResolution.Loading
                )
            )
            assertFalse(
                cachedContentPayload.shouldRenderThreadLoadingShell(
                    timelineSlice = cachedContentPayload.toThreadTimelineSlice(),
                    accessResolution = ThreadAccessResolution.Loading
                )
            )
            assertEquals(
                ThreadRouteMountPlan.FeatureSurfaces,
                cachedContentPayload.threadRouteMountPlan(
                    timelineSlice = cachedContentPayload.toThreadTimelineSlice(),
                    accessResolution = ThreadAccessResolution.Loading
                )
            )
            assertEquals(
                ThreadRouteMountPlan.LoadingShell,
                cachedContentPayload
                    .copy(selectedTab = "tasks")
                    .threadRouteMountPlan(
                        timelineSlice = cachedContentPayload.toThreadTimelineSlice(),
                        accessResolution = ThreadAccessResolution.Loading
                    )
            )
            assertFalse(
                cachedContentPayload.shouldRenderThreadLoadingShell(
                    timelineSlice = cachedContentPayload.toThreadTimelineSlice(),
                    accessResolution = ThreadAccessResolution.Resolved
                )
            )
            assertEquals(
                ThreadRouteMountPlan.FeatureSurfaces,
                cachedContentPayload.threadRouteMountPlan(
                    timelineSlice = cachedContentPayload.toThreadTimelineSlice(),
                    accessResolution = ThreadAccessResolution.Resolved
                )
            )
            assertTrue(cachedContentPayload.hasRenderableContentForComposer())
        }
        val unjoinedLoading = RaftThreadScreenPayload(status = "loading", joined = false)
        val unjoinedContent = RaftThreadScreenPayload(status = "content", joined = false)
        assertFalse(
            unjoinedLoading.isThreadPrimaryFooterReady(
                timelineSlice = unjoinedLoading.toThreadTimelineSlice(),
                accessResolution = ThreadAccessResolution.Loading
            )
        )
        assertTrue(
            unjoinedContent.isThreadPrimaryFooterReady(
                timelineSlice = unjoinedContent.toThreadTimelineSlice(),
                accessResolution = ThreadAccessResolution.Resolved
            )
        )
        assertTrue(RaftThreadScreenPayload(status = "content").hasRenderableContentForComposer())
        assertTrue(RaftThreadScreenPayload(status = "empty").hasRenderableContentForComposer())
        assertFalse(RaftThreadScreenPayload(status = "error").hasRenderableContentForComposer())
        assertFalse(RaftThreadScreenPayload(status = "no_permission").hasRenderableContentForComposer())
    }

    @Test
    fun consumeComposerSeedForSendRemovesOnlySentAttachments() {
        val payload =
            RaftThreadScreenPayload(
                composer =
                    RaftThreadComposerPayload(
                        seedText = "Diagnostics attached",
                        seedAsTask = true,
                        syncNonce = 7,
                        pendingAttachments =
                            listOf(
                                RaftPendingAttachmentItem(id = "diagnostics-att-1", fileName = "slock-feedback.txt"),
                                RaftPendingAttachmentItem(id = "manual-att-2", fileName = "manual.txt")
                            )
                    )
            )

        val consumed =
            payload.consumeComposerSeedForSend(
                sentBody = "  Diagnostics attached  ",
                sentAttachmentIds = setOf("diagnostics-att-1")
            )

        assertEquals("", consumed.composer.seedText)
        assertEquals(false, consumed.composer.seedAsTask)
        assertEquals(8, consumed.composer.syncNonce)
        assertEquals(listOf("manual-att-2"), consumed.composer.pendingAttachments.map { it.id })
    }

    @Test
    fun consumeComposerSeedForSendPreservesUnmatchedSeedText() {
        val payload =
            RaftThreadScreenPayload(
                composer =
                    RaftThreadComposerPayload(
                        seedText = "Keep this draft",
                        syncNonce = 2,
                        pendingAttachments =
                            listOf(
                                RaftPendingAttachmentItem(id = "diagnostics-att-1", fileName = "slock-feedback.txt")
                            )
                    )
            )

        val consumed =
            payload.consumeComposerSeedForSend(
                sentBody = "Different body",
                sentAttachmentIds = setOf("diagnostics-att-1")
            )

        assertEquals("Keep this draft", consumed.composer.seedText)
        assertEquals(3, consumed.composer.syncNonce)
        assertEquals(emptyList(), consumed.composer.pendingAttachments)
    }

    @Test
    fun threadRowDoesNotUseMessageStatusWhenCanonicalStatusIsUnavailable() {
        val message =
            RaftThreadMessageItem(
                id = "m1",
                authorId = "agent-1",
                authorName = "Agent",
                authorKind = "agent",
                body = "hello"
            )

        assertNull(resolveThreadAgentStatusIndicatorState(message, canonicalState = null))
        assertNull(
            message.toThreadAuthorProfileCandidate(ServerId("server-1"))?.agentStatus,
            "message author snapshots may carry static profile candidates, never runtime status"
        )
        assertEquals(
            AgentStatus.Working,
            resolveThreadAgentStatusIndicatorState(
                message,
                canonicalState = raftAgentStatusIndicatorState(
                    status = AgentStatus.Working,
                    presence = Presence.Active,
                    external = false
                )
            )?.status
        )
    }

    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun threadMemberSnapshotSchemaCarriesNoRuntimeStatus() {
        val descriptor = RaftThreadMemberItem.serializer().descriptor
        val names =
            (0 until descriptor.elementsCount)
                .map(descriptor::getElementName)
                .toSet()

        assertFalse("presence" in names)
        assertFalse("agentStatus" in names)
    }

    @Test
    fun threadQuickReactionsIncludeServerCheckMarkEmoji() {
        assertTrue("✅" in ThreadQuickReactionEmojis)
    }

    @Test
    fun defaultThreadMessageVisualFixtureMatchesReactProviderSeed() {
        val payload = threadVisualFixture(THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT, "visual-thread-message-row")
        val message = payload.messages.single()

        assertEquals("visual-thread-message-row", payload.instanceId)
        assertEquals("channel-design", payload.streamChannelId)
        // 5041e36f aligned the default fixture with the React provider seed's
        // rich row metadata: reply actions on, 5 replies (2 unread), 1 reaction.
        assertTrue(payload.showReplyActions)
        assertFalse(payload.showTaskOpenActions)
        assertFalse(message.isOwn)
        assertEquals("agent-cindy", message.authorId)
        assertEquals("Cindy", message.authorName)
        assertEquals("agent", message.authorKind)
        assertEquals("Captured the Android visual artifact for #product:f8e569cb and queued React parity.", message.body)
        assertEquals("thread-msg-agent-reply", message.threadChannelId)
        assertEquals(null, message.taskNumber)
        assertEquals(5, message.replyCount)
        assertEquals(2, message.unreadReplyCount)
        assertEquals("reply-agent-visual-2", message.firstUnreadReplyMessageId)
        assertEquals(1, message.reactions.size)
        assertEquals("👍", message.reactions.single().emoji)
        assertEquals(1, message.reactions.single().count)
        assertFalse(message.reactions.single().selected)
    }

    @Test
    fun inlineRepliesVisualFixturePinsItsOwnClockSoReplyTimestampsRenderRealDigits() {
        // The capture case components.thread.message-row.inline-replies exists to guard the inline
        // reply timestamp row, but every reply rendered the literal "unknown time": the fixture host
        // installed no time context, so resolveRaftRelativeTimeLabel() answered Unknown for a
        // perfectly parseable createdAt. A row with no digits cannot guard digit rendering, which is
        // how the non-monospace timestamp fixed in #2074 got past it. The pinned clock below keeps
        // the capture deterministic while making the row show real, comparable digits.
        val payload =
            threadVisualFixture(THREAD_MESSAGE_VISUAL_FIXTURE_INLINE_REPLIES, "visual-thread-inline-replies")
        val timeContext = payload.toRaftTimeContext()

        assertEquals(1_784_888_400_000L, timeContext.nowEpochMillis)
        assertEquals(480, timeContext.localOffsetMinutes)

        // The header runs through the same shared time context (production ThreadMessageCard), so a
        // pinned clock also demands a production-shaped label: MessageParser sets
        // timestampLabel = createdAt, a raw ISO instant. A pre-formatted "07/24 18:20" does not
        // parse and would resolve to Unknown — the capture caught exactly that.
        val message = payload.messages.single()
        assertEquals("2026-07-24T10:20:00Z", message.timestampLabel)
        assertNotEquals(
            RaftRelativeTimeLabel.Unknown,
            resolveRaftRelativeTimeLabel(message.timestampLabel, timeContext),
            "the header timestamp must resolve to a real label, not the unknown-time fallback"
        )
        assertEquals("18:20", raftAbsoluteTimestampLabel(message.timestampLabel, timeContext))

        val replies = message.inlineReplies?.replies.orEmpty()
        assertEquals(3, replies.size)
        replies.forEach { reply ->
            assertNotEquals(
                RaftRelativeTimeLabel.Unknown,
                resolveRaftRelativeTimeLabel(reply.createdAt, timeContext),
                "reply ${reply.messageId} must resolve to a real label, not the unknown-time fallback"
            )
        }
        // Conversation-group timestamps default to compact local absolute time, and every reply is
        // on the pinned clock's local day, so the row renders bare digits at +08:00.
        assertEquals(
            listOf("18:16", "18:18", "18:20"),
            replies.map { raftAbsoluteTimestampLabel(it.createdAt, timeContext) }
        )
    }

    @Test
    fun longInlineCodeVisualFixtureContainsSingleCodeSpan() {
        val payload = threadVisualFixture(THREAD_MESSAGE_VISUAL_FIXTURE_LONG_INLINE_CODE, "visual-thread-message-row-long-inline-code")
        val message = payload.messages.single()

        assertEquals("visual-thread-message-row-long-inline-code", payload.instanceId)
        assertEquals("channel-markdown", payload.streamChannelId)
        assertTrue(payload.showReplyActions)
        assertTrue(payload.showTaskOpenActions)
        assertEquals("Cindy", message.authorName)
        assertEquals(listOf("markdown专修"), payload.knownMarkdownChannelNames)
        assertEquals(listOf("artin"), payload.knownMarkdownMentionNames)
        assertEquals(listOf(42, 43), payload.knownMarkdownTaskNumbers)
        assertFalse(message.body.contains("```"))
        assertEquals(16, message.body.count { it == '`' })
        assertTrue(message.body.contains("`go test ./...`"))
        assertTrue(message.body.contains("Ordinary mention regression: @KMP-专家 #520 Phase 5"))
        assertTrue(payload.members.any { it.displayName == "KMP-专家" || it.handle == "KMP-专家" })
        assertTrue(message.body.contains("reply/channel-markdown/msg-inline-visual"))
        assertTrue(message.body.contains("owner-key-consumes-thread-route-identity-only"))
        assertTrue(message.body.contains("./gradlew :shared:testDebugUnitTest --tests build.raft.app.markdown.SlockMarkdownTest"))
        assertTrue(message.body.contains("compose/shared/src/commonMain/kotlin/build/raft/app/markdown/SlockMarkdown.kt"))
    }

    @Test
    fun task524MarkdownVisibleFixtureKeepsParagraphAndListText() {
        val payload = threadVisualFixture(THREAD_MESSAGE_VISUAL_FIXTURE_TASK524_MARKDOWN_VISIBLE, "visual-thread-task524-markdown-visible")
        val message = payload.messages.single()

        assertEquals("visual-thread-task524-markdown-visible", payload.instanceId)
        assertEquals(RaftThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("channel-home", payload.parentChannelId)
        assertEquals("parent-task524-visible", payload.parentMessageId)
        assertFalse(payload.showReplyActions)
        assertFalse(payload.showTaskOpenActions)
        assertEquals("CC-Wow", message.authorName)
        assertTrue(message.body.contains("Review summary before the list must remain visible."))
        assertTrue(message.body.contains("- Bullet item text must render after the marker."))
        assertTrue(message.body.contains("1. First numbered item text must not disappear."))
        assertTrue(message.body.contains("2. Second numbered item with `inline code` must still render."))
        assertTrue(message.body.contains("这里的正文不能只剩编号"))
        assertTrue(message.body.contains("task #524"))
    }

    @Test
    fun genericAttachmentNoPreviewMetaLineHidesMimeType() {
        val attachment =
            RaftThreadAttachmentItem(
                id = "att-1",
                name = "archive.zip",
                sizeLabel = "42 KB",
                mimeType = "application/zip"
            )

        assertEquals("42 KB", attachment.genericAttachmentMetaLine())
    }

    @Test
    fun attachmentCardMetaLabelIsTheKindLikeTheWeb() {
        val pdf =
            RaftThreadAttachmentItem(
                id = "att-pdf",
                name = "visual-parity-notes.pdf",
                sizeLabel = "184 KB",
                mimeType = "application/pdf"
            )
        val html =
            RaftThreadAttachmentItem(
                id = "att-html",
                name = "thread-screen-preview.html",
                sizeLabel = "32 KB",
                mimeType = "text/html"
            )

        assertEquals("application/pdf", pdf.threadAttachmentCardMetaLabel(htmlPreviewLabel = "HTML preview", fileLabel = "file"))
        assertEquals("HTML preview", html.threadAttachmentCardMetaLabel(htmlPreviewLabel = "HTML preview", fileLabel = "file"))
        assertEquals("file", pdf.copy(mimeType = "").threadAttachmentCardMetaLabel(htmlPreviewLabel = "HTML preview", fileLabel = "file"))
        assertEquals("184 KB", pdf.displaySizeLabel())
    }

    @Test
    fun attachmentGroupsFollowTheWebOrderVideosAudioThenOneCardGroup() {
        val doc = RaftThreadAttachmentItem(id = "doc", name = "notes.pdf", mimeType = "application/pdf")
        val video = RaftThreadAttachmentItem(id = "video", name = "clip.mp4", mimeType = "video/mp4")
        val zip = RaftThreadAttachmentItem(id = "zip", name = "bundle.zip", mimeType = "application/zip")
        val audio = RaftThreadAttachmentItem(id = "audio", name = "memo.mp3", mimeType = "audio/mpeg")
        val runs = listOf(doc, audio, video, zip).threadAttachmentCardRuns()
        assertEquals(listOf(listOf("video"), listOf("audio"), listOf("doc", "zip")), runs.map { run -> run.map { it.id } })
    }

    @Test
    fun attachmentCardCommentCountSkipsPdf() {
        val doc = RaftThreadAttachmentItem(id = "doc", name = "notes.md", mimeType = "text/markdown", commentCount = 3)
        assertEquals(3, doc.threadAttachmentCardCommentCount())
        assertEquals(0, doc.copy(name = "notes.pdf", mimeType = "application/pdf").threadAttachmentCardCommentCount())
    }

    @Test
    fun inlineImageShowsFailureOnlyOnceEverySourceFailed() {
        assertEquals(false, threadInlineImageShowsFailure(sourceCount = 2, failedSources = 1, awaitingRemoteFallback = false))
        assertEquals(true, threadInlineImageShowsFailure(sourceCount = 2, failedSources = 2, awaitingRemoteFallback = false))
        assertEquals(true, threadInlineImageShowsFailure(sourceCount = 0, failedSources = 0, awaitingRemoteFallback = false))
        assertEquals(false, threadInlineImageShowsFailure(sourceCount = 0, failedSources = 0, awaitingRemoteFallback = true))
    }

    @Test
    fun universalMp4UsesInlineVideoPreviewWithoutWideningUnknownFiles() {
        val mp4ByMime =
            RaftThreadAttachmentItem(
                id = "att-video-mime",
                name = "clip.bin",
                mimeType = "video/mp4; codecs=avc1"
            )
        val mp4ByName =
            RaftThreadAttachmentItem(
                id = "att-video-name",
                name = "demo.MP4?download=1",
                mimeType = "application/octet-stream"
            )
        val unknownVideo =
            RaftThreadAttachmentItem(
                id = "att-video-unknown",
                name = "demo.futurevideo",
                mimeType = "video/vnd.future-container"
            )
        val archive =
            RaftThreadAttachmentItem(
                id = "att-archive",
                name = "demo.zip",
                mimeType = "application/zip"
            )

        assertTrue(mp4ByMime.isPreviewableVideoAttachment())
        assertTrue(mp4ByName.isPreviewableVideoAttachment())
        assertFalse(unknownVideo.isPreviewableVideoAttachment())
        assertFalse(archive.isPreviewableVideoAttachment())
    }

    @Test
    fun threadAttachmentItemMatchesAudioAttachmentsByMimeOrExtension() {
        val mp3ByMime =
            RaftThreadAttachmentItem(
                id = "att-mp3-mime",
                name = "recording.bin",
                mimeType = "audio/mpeg"
            )
        val wavByName =
            RaftThreadAttachmentItem(
                id = "att-wav-name",
                name = "recording.wav",
                mimeType = "application/octet-stream"
            )
        val m4aByMime =
            RaftThreadAttachmentItem(
                id = "att-m4a-mime",
                name = "voice.bin",
                mimeType = "audio/x-m4a"
            )
        val oggByName =
            RaftThreadAttachmentItem(
                id = "att-ogg-name",
                name = "music.ogg",
                mimeType = ""
            )
        val flacByName =
            RaftThreadAttachmentItem(
                id = "att-flac-name",
                name = "lossless.flac",
                mimeType = ""
            )
        val nonAudio =
            RaftThreadAttachmentItem(
                id = "att-archive",
                name = "demo.zip",
                mimeType = "application/zip"
            )

        assertTrue(mp3ByMime.isPreviewableAudioAttachment())
        assertTrue(wavByName.isPreviewableAudioAttachment())
        assertTrue(m4aByMime.isPreviewableAudioAttachment())
        assertTrue(oggByName.isPreviewableAudioAttachment())
        assertTrue(flacByName.isPreviewableAudioAttachment())
        assertFalse(nonAudio.isPreviewableAudioAttachment())
    }

    @Test
    fun inlineAudioPreviewRequestsAreDeduplicatedUntilFailure() {
        val attachmentId = "att-audio"

        assertTrue(emptyMap<String, ThreadInlineAudioPreviewUiState>().canRequestInlineAudioPreview(attachmentId))
        assertFalse(
            mapOf(
                attachmentId to
                    ThreadInlineAudioPreviewUiState(status = ThreadInlineAudioPreviewStatus.Loading)
            ).canRequestInlineAudioPreview(attachmentId)
        )
        assertFalse(
            mapOf(
                attachmentId to
                    ThreadInlineAudioPreviewUiState(
                        status = ThreadInlineAudioPreviewStatus.Ready,
                        url = "https://example.test/audio.mp3"
                    )
            ).canRequestInlineAudioPreview(attachmentId)
        )
        assertTrue(
            mapOf(
                attachmentId to
                    ThreadInlineAudioPreviewUiState(status = ThreadInlineAudioPreviewStatus.Error)
            ).canRequestInlineAudioPreview(attachmentId)
        )
        assertFalse(emptyMap<String, ThreadInlineAudioPreviewUiState>().canRequestInlineAudioPreview(""))
    }

    @Test
    fun inlineAudioPreviewTreatsMissingStateAsLoadingUntilTheAutomaticSourceArrives() {
        assertEquals(ThreadInlineAudioRenderMode.Loading, (null as ThreadInlineAudioPreviewUiState?).audioRenderMode())
        assertEquals(
            ThreadInlineAudioRenderMode.Loading,
            ThreadInlineAudioPreviewUiState(status = ThreadInlineAudioPreviewStatus.Loading).audioRenderMode()
        )
        assertEquals(
            ThreadInlineAudioRenderMode.Ready,
            ThreadInlineAudioPreviewUiState(
                status = ThreadInlineAudioPreviewStatus.Ready,
                url = "https://example.test/audio.mp3"
            ).audioRenderMode()
        )
        assertEquals(
            ThreadInlineAudioRenderMode.Error,
            ThreadInlineAudioPreviewUiState(status = ThreadInlineAudioPreviewStatus.Ready).audioRenderMode()
        )
    }

    @Test
    fun inlineVideoPreviewRequestsAreDeduplicatedUntilFailure() {
        val attachmentId = "att-video"

        assertTrue(emptyMap<String, ThreadInlineVideoPreviewUiState>().canRequestInlineVideoPreview(attachmentId))
        assertFalse(
            mapOf(
                attachmentId to
                    ThreadInlineVideoPreviewUiState(status = ThreadInlineVideoPreviewStatus.Loading)
            ).canRequestInlineVideoPreview(attachmentId)
        )
        assertFalse(
            mapOf(
                attachmentId to
                    ThreadInlineVideoPreviewUiState(
                        status = ThreadInlineVideoPreviewStatus.Ready,
                        url = "https://example.test/video.mp4"
                    )
            ).canRequestInlineVideoPreview(attachmentId)
        )
        assertTrue(
            mapOf(
                attachmentId to
                    ThreadInlineVideoPreviewUiState(status = ThreadInlineVideoPreviewStatus.Error)
            ).canRequestInlineVideoPreview(attachmentId)
        )
        assertFalse(emptyMap<String, ThreadInlineVideoPreviewUiState>().canRequestInlineVideoPreview(""))
    }

    @Test
    fun inlineVideoPreviewTreatsMissingStateAsLoadingUntilTheAutomaticSourceArrives() {
        assertEquals(ThreadInlineVideoRenderMode.Loading, null.renderMode())
        assertEquals(
            ThreadInlineVideoRenderMode.Loading,
            ThreadInlineVideoPreviewUiState(status = ThreadInlineVideoPreviewStatus.Loading).renderMode()
        )
        assertEquals(
            ThreadInlineVideoRenderMode.Ready,
            ThreadInlineVideoPreviewUiState(
                status = ThreadInlineVideoPreviewStatus.Ready,
                url = "https://example.test/video.mp4"
            ).renderMode()
        )
        assertEquals(
            ThreadInlineVideoRenderMode.Error,
            ThreadInlineVideoPreviewUiState(status = ThreadInlineVideoPreviewStatus.Ready).renderMode()
        )
    }

    @Test
    fun authSessionSelfMentionNamesIncludeBareHandle() {
        val session =
            StoredAuthSession(
                user =
                    StoredAuthUser(
                        id = "user-artin",
                        email = "artin@example.com",
                        name = "@artin",
                        displayName = "Artin"
                    ),
                accessToken = "token",
                refreshToken = "refresh"
            )

        assertTrue("artin" in session.threadSelfMentionNames())
    }

    @Test
    fun visualRichTextFixturesUseDeterministicViewerForSelfMentionContext() {
        assertEquals(
            setOf("artin"),
            threadVisualFixtureHighlightedMentions(THREAD_MESSAGE_VISUAL_FIXTURE_LONG_INLINE_CODE)
        )
        assertEquals(
            setOf("artin"),
            threadVisualFixtureHighlightedMentions(THREAD_MESSAGE_VISUAL_FIXTURE_MD_LINK_REF)
        )
        assertEquals(
            emptySet(),
            threadVisualFixtureHighlightedMentions(THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT)
        )
    }

    @Test
    fun authPendingThreadRouteKeepsIdentityAndDropsDisplayFallback() {
        val pending =
            WireThreadLaunchPayload(
                instanceId = "thread-original",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                threadId = "reply-stream",
                sourceThreadId = "reply-stream",
                streamChannelId = "reply-stream",
                parentChannelId = "channel-1",
                parentMessageId = "message-parent",
                originParentMessageId = "message-parent",
                focusedMessageId = "message-reply",
                selectedTab = "chat",
                originFallbackPreview = "display content must not survive",
                originFallbackSenderId = "member-1",
                originFallbackSenderKind = MemberKind.Human
            ).toAuthPendingOpenRequest(
                serverId = "server-1",
                accountPrincipalId = "user-1",
            )

        val threadPending = assertIs<AuthPendingOpenRequest.Thread>(pending)
        assertEquals("server-1", threadPending.serverId)
        assertEquals("user-1", threadPending.accountPrincipalId)
        assertEquals("reply-stream", threadPending.sourceThreadId)
        assertEquals("message-parent", threadPending.parentMessageId)
        assertEquals("message-reply", threadPending.focusedMessageId)

        val resumed = threadPending.toWireThreadLaunchPayload()
        assertEquals("reply-stream", resumed.sourceThreadId)
        assertEquals("message-parent", resumed.parentMessageId)
        assertEquals("message-reply", resumed.focusedMessageId)
        assertEquals(null, resumed.originFallbackPreview)
        assertEquals(null, resumed.originFallbackSenderId)
        assertEquals(null, resumed.originFallbackSenderKind)
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
            7,
            threadMessagesLazyItemCount(
                originStatus = "content",
                showHistoryBoundary = true,
                messagesCount = 3,
                canLoadMoreAfter = true,
                showBottomScrollPadding = true
            )
        )
        assertEquals(
            3,
            threadMessagesLazyItemCount(
                originStatus = "content",
                showHistoryBoundary = false,
                messagesCount = 0,
                canLoadMoreAfter = false,
                showReplyEmptyState = true,
                showBottomScrollPadding = true
            )
        )
        assertEquals(
            2,
            sharedListBeyondBoundsItemCount(
                threadMessagesLazyItemCount(
                    originStatus = "none",
                    showHistoryBoundary = true,
                    messagesCount = 1,
                    canLoadMoreAfter = false
                )
            )
        )
        val loadingCount =
            threadMessagesLazyItemCount(
                originStatus = "none",
                showHistoryBoundary = false,
                messagesCount = 0,
                canLoadMoreAfter = false,
                showReplyEmptyState = true,
                showBottomScrollPadding = true
            )
        val loadedCount =
            threadMessagesLazyItemCount(
                originStatus = "none",
                showHistoryBoundary = false,
                messagesCount = 0,
                canLoadMoreAfter = false,
                showReplyEmptyState = false,
                showBottomScrollPadding = true
            )
        assertEquals(2, loadingCount)
        assertEquals(1, loadedCount)
    }

    @Test
    fun threadOriginCountsAsScrollableListRowAgain() {
        assertEquals(
            51,
            threadMessagesLazyItemCount(
                originStatus = "content",
                showHistoryBoundary = true,
                messagesCount = 49,
                canLoadMoreAfter = false
            )
        )
        assertEquals(
            4,
            RaftThreadScreenPayload(
                originStatus = "content",
                messages = (0 until 3).map { index -> testThreadMessage("reply-$index") }
            ).latestMessageListItemIndex()
        )
    }

    @Test
    fun threadBottomScrollPaddingAnchorsToLastLazyRow() {
        val payload =
            RaftThreadScreenPayload(
                originStatus = "content",
                messages = (0 until 3).map { index -> testThreadMessage("reply-$index") },
                canLoadMoreAfter = true
            )
        val lazyItemCount =
            threadMessagesLazyItemCount(
                originStatus = payload.originStatus,
                showHistoryBoundary = payload.messages.isNotEmpty(),
                messagesCount = payload.messages.size,
                canLoadMoreAfter = payload.canLoadMoreAfter,
                showBottomScrollPadding = true
            )

        assertEquals(4, payload.latestMessageListItemIndex())
        assertEquals(6, payload.toThreadTimelineSlice().bottomScrollPaddingListItemIndex(lazyItemCount))
    }

    @Test
    fun threadInlineSearchMatchesOriginAndRepliesInListOrder() {
        val payload =
            RaftThreadScreenPayload(
                originStatus = "content",
                originMessage = testThreadMessage("origin").copy(body = "The launch decision"),
                messages =
                    listOf(
                        testThreadMessage("reply-0").copy(body = "unrelated"),
                        testThreadMessage("reply-1").copy(body = "Launch follow-up"),
                        testThreadMessage("reply-2").copy(body = "other")
                    )
            )
        val matches = payload.toThreadTimelineSlice().threadInlineSearchMatches("launch")

        assertEquals(
            listOf(
                ThreadInlineSearchMatch(messageId = "origin", listItemIndex = 0),
                ThreadInlineSearchMatch(messageId = "reply-1", listItemIndex = 3)
            ),
            matches
        )
    }

    @Test
    fun threadInlineSearchMatchesAuthorFieldsAndIgnoresBlankQuery() {
        val payload =
            RaftThreadScreenPayload(
                messages =
                    listOf(
                        testThreadMessage("reply-0").copy(authorName = "Artin", body = "hello"),
                        testThreadMessage("reply-1").copy(authorHandle = "Android-Developer-2", body = "ship it")
                    )
            )
        val timeline = payload.toThreadTimelineSlice()

        assertEquals(emptyList<ThreadInlineSearchMatch>(), timeline.threadInlineSearchMatches("   "))
        assertEquals(
            listOf(ThreadInlineSearchMatch(messageId = "reply-1", listItemIndex = 2)),
            timeline.threadInlineSearchMatches("developer-2")
        )
    }

    @Test
    fun threadRealOlderBoundaryRendersWithFullMessageList() {
        val fullPayload =
            RaftThreadScreenPayload(
                messages = listOf(testThreadMessage("reply-0"), testThreadMessage("reply-1")),
                canLoadMoreBefore = false,
                olderMessagesStatus = "idle"
            )

        assertEquals(2, fullPayload.latestMessageListItemIndex())
        assertEquals(
            3,
            threadMessagesLazyItemCount(
                originStatus = fullPayload.originStatus,
                showHistoryBoundary = true,
                messagesCount = fullPayload.messages.size,
                canLoadMoreAfter = fullPayload.canLoadMoreAfter
            )
        )
    }

    @Test
    fun threadOlderAutoLoadStartsBeforeBoundaryIsVisible() {
        assertFalse(
            shouldThreadAutoLoadOlderMessages(
                boundaryIndex = 0,
                firstVisibleIndex = 9,
                userScrollGeneration = 1
            )
        )
        assertTrue(
            shouldThreadAutoLoadOlderMessages(
                boundaryIndex = 0,
                firstVisibleIndex = 8,
                userScrollGeneration = 1
            )
        )
        assertTrue(
            shouldThreadAutoLoadOlderMessages(
                boundaryIndex = 0,
                firstVisibleIndex = 0,
                userScrollGeneration = 1
            )
        )
    }

    @Test
    fun threadOlderAutoLoadIgnoresProgrammaticEntryScroll() {
        assertFalse(
            shouldThreadAutoLoadOlderMessages(
                boundaryIndex = 0,
                firstVisibleIndex = 0,
                userScrollGeneration = 0
            )
        )
    }

    @Test
    fun threadOlderAutoLoadRequiresFreshDragAfterEachSettledPrepend() {
        assertFalse(
            shouldThreadAutoLoadOlderMessages(
                boundaryIndex = 0,
                firstVisibleIndex = 0,
                userScrollGeneration = 7,
                lastSettledUserScrollGeneration = 7
            )
        )
        assertTrue(
            shouldThreadAutoLoadOlderMessages(
                boundaryIndex = 0,
                firstVisibleIndex = 0,
                userScrollGeneration = 8,
                lastSettledUserScrollGeneration = 7
            )
        )
    }

    @Test
    fun threadBottomAnchorOffsetBottomAlignsShortLatestItemInLongLists() {
        assertEquals(
            -520,
            threadLatestItemBottomAnchorScrollOffset(
                itemSize = 120,
                viewportHeight = 640,
                visibleItemCount = 3,
                totalItemCount = 3
            )
        )
        assertEquals(
            0,
            threadInitialBottomAnchorScrollDelta(
                itemOffset = 520,
                itemSize = 120,
                trailingPaddingSize = 0,
                viewportEndOffset = 640
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
    fun threadBottomAnchorOffsetKeepsShortReplyThreadPaddingAtViewportBottom() {
        assertEquals(
            -528,
            threadLatestItemBottomAnchorScrollOffset(
                itemSize = 96,
                viewportHeight = 640,
                visibleItemCount = 5,
                totalItemCount = 6,
                trailingPaddingSize = 16
            )
        )
    }

    @Test
    fun threadOriginContentInitializesAtLatestNativeViewportItem() {
        assertEquals(8, threadInitialFirstVisibleItemIndex(latestItemIndex = 8))
    }

    @Test
    fun focusedMessageScrollOffsetTopAlignsFocusedItems() {
        assertEquals(
            0,
            threadFocusedItemScrollOffset(
                itemSize = 120,
                viewportHeight = 640
            )
        )
        assertEquals(
            0,
            threadFocusedItemScrollOffset(
                itemSize = 800,
                viewportHeight = 640
            )
        )
        assertEquals(
            0,
            threadFocusedItemScrollOffset(
                itemSize = 640,
                viewportHeight = 640
            )
        )
        assertEquals(
            0,
            threadFocusedItemScrollOffset(
                itemSize = 0,
                viewportHeight = 640
            )
        )
    }

    @Test
    fun floatingOverlayPositionHandlesCrampedAndBottomAnchors() {
        val cramped = RaftFloatingOverlayLayout(size = Size(width = 200f, height = 100f))
        assertEquals(
            Offset(8f, 8f),
            cramped.positionContent(
                anchor = Offset(120f, 120f),
                contentSize = Size(width = 320f, height = 240f),
                viewportGap = 8f
            )
        )

        val roomy = RaftFloatingOverlayLayout(size = Size(width = 420f, height = 560f))
        assertEquals(
            Offset(200f, 338f),
            roomy.positionContent(
                anchor = Offset(360f, 1260f),
                contentSize = Size(width = 212f, height = 214f),
                viewportGap = 8f,
                horizontalOffset = -16f,
                verticalOffset = 8f,
                verticalPlacement = RaftFloatingVerticalPlacement.Auto
            )
        )

        val rightEdgeFilter = RaftFloatingOverlayLayout(size = Size(width = 390f, height = 844f))
        assertEquals(
            Offset(162f, 108f),
            rightEdgeFilter.positionContent(
                anchor = Offset(318f, 100f),
                contentSize = Size(width = 220f, height = 360f),
                viewportGap = 8f,
                verticalOffset = 8f,
                verticalPlacement = RaftFloatingVerticalPlacement.Auto,
                horizontalAutoFlip = false
            )
        )

        assertEquals(
            Offset(24f, 252f),
            roomy.positionContent(
                anchor = Offset(24f, 500f),
                contentSize = Size(width = 320f, height = 240f),
                viewportGap = 8f,
                verticalOffsetAbove = 8f,
                verticalPlacement = RaftFloatingVerticalPlacement.Above
            )
        )
    }

    @Test
    fun floatingOverlayPositionSupportsHorizontalCenterPlacement() {
        val viewport = RaftFloatingOverlayLayout(size = Size(width = 400f, height = 800f))
        val anchor = Offset(x = 350f, y = 100f) // trigger is on the far right

        // Centered horizontally: (400 - 280) / 2 = 60f
        // Vertical below anchor: 100 + 6 = 106f
        val centered = viewport.positionContent(
            anchor = anchor,
            contentSize = Size(width = 280f, height = 200f),
            viewportGap = 8f,
            verticalOffset = 6f,
            horizontalPlacement = RaftFloatingHorizontalPlacement.Center
        )
        assertEquals(Offset(60f, 106f), centered)

        // With horizontal offset
        val withOffset = viewport.positionContent(
            anchor = anchor,
            contentSize = Size(width = 280f, height = 200f),
            viewportGap = 8f,
            horizontalOffset = 10f,
            verticalOffset = 6f,
            horizontalPlacement = RaftFloatingHorizontalPlacement.Center
        )
        assertEquals(Offset(70f, 106f), withOffset)

        // Clamped when wider than viewport minus gap
        val wideContent = viewport.positionContent(
            anchor = anchor,
            contentSize = Size(width = 420f, height = 200f),
            viewportGap = 8f,
            verticalOffset = 6f,
            horizontalPlacement = RaftFloatingHorizontalPlacement.Center
        )
        assertEquals(Offset(8f, 106f), wideContent)
    }

    @Test
    fun typedPayloadKeepsMembersAndSettingsActions() {
        val payload =
            RaftThreadScreenPayload(
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
                        RaftThreadMemberItem(id = "u1", displayName = "Artin", handle = "artin", roleLabel = "Owner"),
                        RaftThreadMemberItem(id = "a1", displayName = "Android Bot", kind = "agent", avatarLabel = "AB")
                    ),
                memberCandidates =
                    listOf(
                        RaftThreadMemberItem(id = "u2", displayName = "Cindy", handle = "cindy"),
                        RaftThreadMemberItem(id = "a2", displayName = "QA Bot", kind = "agent")
                    ),
                settingsActions =
                    listOf(
                        RaftThreadSettingsAction(id = "visibility"),
                        RaftThreadSettingsAction(id = "archive"),
                        RaftThreadSettingsAction(id = "delete")
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
    fun threadMemberProjectionNormalizesDescriptionForAddMembers() {
        assertEquals(
            "Coordinates release validation.",
            normalizedThreadMemberDescription("  Coordinates release validation.  ")
        )
        assertEquals("", normalizedThreadMemberDescription(null))
        assertEquals("", normalizedThreadMemberDescription("   "))
    }

    @Test
    fun threadMembersRosterSubtitleUsesLiveActivityOnlyForAgents() {
        assertEquals(
            "Compacting context",
            threadMembersRosterSubtitle(
                memberKind = "agent",
                liveActivityLabel = "  Compacting context  ",
                humanRoleLabel = "Agent"
            )
        )
        assertEquals(
            "Owner",
            threadMembersRosterSubtitle(
                memberKind = "human",
                liveActivityLabel = "Online",
                humanRoleLabel = "Owner"
            )
        )
        assertEquals(
            "",
            threadMembersRosterSubtitle(
                memberKind = "agent",
                liveActivityLabel = null,
                humanRoleLabel = "Agent"
            )
        )
    }

    @Test
    fun threadAddMemberCandidateSubtitleUsesDescriptionWithoutRoleFallback() {
        val agent =
            RaftThreadMemberItem(
                id = "agent-1",
                displayName = "Release Steward",
                kind = "agent",
                roleLabel = "Agent",
                description = "  Coordinates release validation.  "
            )
        val human =
            RaftThreadMemberItem(
                id = "human-1",
                displayName = "Cindy",
                kind = "human",
                roleLabel = "Member",
                description = "Reviews mobile release evidence."
            )
        val blank =
            RaftThreadMemberItem(
                id = "agent-2",
                displayName = "No Description",
                kind = "agent",
                roleLabel = "Agent"
            )

        assertEquals("Coordinates release validation.", threadAddMemberCandidateSubtitle(agent.description))
        assertEquals("Reviews mobile release evidence.", threadAddMemberCandidateSubtitle(human.description))
        assertEquals("", threadAddMemberCandidateSubtitle(blank.description))
    }

    @Test
    fun threadAddMemberSearchUsesDescriptionWithoutRoleFallback() {
        val candidates =
            listOf(
                RaftThreadMemberItem(
                    id = "agent-1",
                    displayName = "Release Steward",
                    handle = "release-steward",
                    kind = "agent",
                    roleLabel = "Runtime Operator",
                    description = "Coordinates package validation."
                ),
                RaftThreadMemberItem(
                    id = "human-1",
                    displayName = "Cindy",
                    handle = "cindy",
                    kind = "human",
                    roleLabel = "Workspace Admin",
                    description = "Reviews mobile evidence."
                )
            )

        assertEquals(
            listOf("agent-1"),
            filterThreadAddMemberCandidates(query = "package", candidates = candidates).map { it.id }
        )
        assertEquals(
            emptyList(),
            filterThreadAddMemberCandidates(query = "Operator", candidates = candidates).map { it.id }
        )
        assertEquals(
            emptyList(),
            filterThreadAddMemberCandidates(query = "Admin", candidates = candidates).map { it.id }
        )
    }

    @Test
    fun addMemberSubmissionHighlightsOnlySelectedRowWhileLockingAllClicks() {
        assertEquals(
            ThreadAddMemberRowInteraction(selected = true, enabled = true, clickEnabled = false),
            threadAddMemberRowInteraction(
                memberKey = "human:user-1",
                selectedKey = "human:user-1",
                blocked = false,
                adding = true,
                alreadyJoined = false
            )
        )
        assertEquals(
            ThreadAddMemberRowInteraction(selected = false, enabled = true, clickEnabled = false),
            threadAddMemberRowInteraction(
                memberKey = "human:user-2",
                selectedKey = "human:user-1",
                blocked = false,
                adding = true,
                alreadyJoined = false
            )
        )
    }

    @Test
    fun threadMemberRowsUseCanonicalStatusOnly() {
        val canonical =
            raftAgentStatusIndicatorState(
                status = AgentStatus.Working,
                presence = Presence.Active,
                external = false
            )

        assertNull(
            resolveThreadMemberAgentStatusIndicatorState(
                memberKind = "agent",
                canonicalState = null
            ),
            "member payload status must not become a fallback state"
        )
        assertEquals(
            AgentStatus.Working,
            resolveThreadMemberAgentStatusIndicatorState(
                memberKind = "agent",
                canonicalState = canonical
            )?.status
        )
        assertNull(
            resolveThreadMemberAgentStatusIndicatorState(
                memberKind = "human",
                canonicalState = canonical
            )
        )
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
        assertEquals("", payload.resolveThreadComposer().seedText)
    }

    @Test
    fun launchPayloadWithUnknownSurfaceKindReturnsErrorState() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-bad-surface",
                      "surfaceKind": "spaceship",
                      "sourceThreadId": "channel-1",
                      "streamChannelId": "channel-1"
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("fallback", payload.instanceId)
        assertEquals("error", payload.status)
        assertEquals("payload_parse_failed", payload.errorCode)
    }

    @Test
    fun launchPayloadWithUnknownChannelKindReturnsErrorState() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-bad-kind",
                      "surfaceKind": "channel",
                      "channelKind": "spaceship",
                      "sourceThreadId": "channel-1",
                      "streamChannelId": "channel-1"
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("fallback", payload.instanceId)
        assertEquals("error", payload.status)
        assertEquals("payload_parse_failed", payload.errorCode)
    }

    @Test
    fun launchPayloadWithUnknownParentKindReturnsErrorState() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-bad-parent-kind",
                      "surfaceKind": "thread",
                      "sourceThreadId": "reply-stream-1",
                      "streamChannelId": "reply-stream-1",
                      "parentChannelId": "channel-1",
                      "parentMessageId": "message-1",
                      "parentSurfaceKind": "spaceship"
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("fallback", payload.instanceId)
        assertEquals("error", payload.status)
        assertEquals("payload_parse_failed", payload.errorCode)
    }

    @Test
    fun launchPayloadParsesPrivateChannelKindThroughContract() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-private",
                      "surfaceKind": "channel",
                      "channelKind": "private",
                      "sourceThreadId": "private-1",
                      "streamChannelId": "private-1"
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("thread-private", payload.instanceId)
        assertEquals("loading", payload.status)
        assertEquals(RaftThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("private", payload.channelKind)
    }

    @Test
    fun launchPayloadParsesDirectChannelKindAsDirectMessage() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-direct",
                      "surfaceKind": "channel",
                      "channelKind": "direct",
                      "sourceThreadId": "dm-1",
                      "streamChannelId": "dm-1"
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("thread-direct", payload.instanceId)
        assertEquals("loading", payload.status)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, payload.surfaceKind)
        assertEquals(RaftThreadSurfaceKind.Channel, payload.channelKind)
        assertEquals(true, payload.memberScopedMentions)
        assertEquals(false, payload.showMembersAction)
        assertEquals(false, payload.showSettingsAction)
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
        assertEquals("", payload.title)
        assertEquals("loading", payload.status)
        assertEquals("channel-1", payload.sourceThreadId)
        assertEquals("channel-1", payload.streamChannelId)
        assertEquals(0, payload.membersCount)
        assertEquals(true, payload.joined)
        assertEquals(true, payload.canSend)
        assertEquals(emptyList<RaftThreadMessageItem>(), payload.messages)
        assertEquals(emptyList<RaftThreadTaskItem>(), payload.tasks)
        assertEquals(emptyList<RaftThreadMemberItem>(), payload.members)
        assertEquals(emptyList<RaftThreadMemberItem>(), payload.memberCandidates)
        assertEquals("", payload.resolveThreadComposer().seedText)
        assertEquals(false, payload.resolveThreadComposer().seedAsTask)
        assertEquals(emptyList(), payload.settingsActions)
    }

    @Test
    fun threadFilesRouteStartPayloadIgnoresLegacyFilesPayloadSeed() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-launch-1",
                      "sourceThreadId": "channel-1",
                      "streamChannelId": "channel-1",
                      "filesPayload": "{\"instanceId\":\"seed-instance\",\"status\":\"content\",\"items\":[{\"attachmentId\":\"seed-file\",\"name\":\"must-not-seed.pdf\"}]}"
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        val filesPayload = payload.initialThreadFilesRoutePayload()

        assertEquals("thread-launch-1", filesPayload.instanceId)
        assertEquals("loading", filesPayload.status)
        assertEquals(emptyList(), filesPayload.items)
        assertEquals(false, filesPayload.hasMore)
        assertEquals(null, filesPayload.nextCursor)
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
            "composerPendingAttachments",
            "pendingAttachments",
            "name",
            "title",
            "subtitle",
            "permissions",
            "memberScopedMentions",
            "showModeTabs",
            "showReplyActions",
            "showComposer",
            "joined",
            "archived",
            "canSend",
            "membersCount",
            "canAddMembers",
            "jointChannelId",
            "jointRole",
            "settingsActions",
            "showMembersAction",
            "showSettingsAction",
            "showOpenInChannelAction",
            "showMessageSaveAction",
            "showTaskOpenActions",
            "showTaskStatusActions",
            "emptyTitle",
            "emptyDetail",
            "originMessage",
            "originPreview",
            "originStatus",
            "lastMessagePreview",
            "lastMessageAt",
            "lastMessageSenderName",
            "unreadCount",
            "mentionCount",
            "hasDraft",
            "avatar",
            "presence",
            "agentStatus",
            "joinLabel",
            "dmPeerName",
            "dmPeerAvatarUrl",
            "dmPeerAvatarLabel",
        ).forEach { forbidden ->
            assertFalse(forbidden in names, "$forbidden must stay outside compose_thread launch pageData")
        }
    }

    @Test
    fun directMessageLaunchWireCarriesPeerIdentityWithoutPresentationState() {
        val identity =
            MemberDetailIdentity(
                memberId = MemberId("agent-1"),
                kind = MemberKind.Agent,
                sourceServerId = ServerId("server-1")
            )
        val launch =
            WireThreadLaunchPayload(
                instanceId = "dm-1",
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                threadId = "dm-1",
                sourceThreadId = "dm-1",
                streamChannelId = "dm-1",
                dmPeerIdentity = identity
            )

        val initial = launch.toInitialPayload("fallback")

        assertEquals(identity, initial.dmPeerIdentity)
        assertEquals(null, initial.dmPeerName)
        assertEquals(null, initial.dmPeerAvatarUrl)
        assertEquals(null, initial.dmPeerAvatarLabel)
    }

    @Test
    fun launchPayloadIgnoresLegacyPendingAttachmentRouteState() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-channel-1",
                      "surfaceKind": "channel",
                      "sourceThreadId": "channel-1",
                      "streamChannelId": "channel-1",
                      "composerSeedText": "draft from route",
                      "composerPendingAttachments": [
                        {
                          "id": "att-route",
                          "fileName": "route.txt",
                          "status": "ready"
                        }
                      ]
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("draft from route", payload.composer.seedText)
        assertEquals(emptyList(), payload.composer.pendingAttachments)
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
        assertEquals("", payload.title)
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
        assertEquals("", payload.title)
        assertEquals("dm-artin", payload.sourceThreadId)
        assertEquals("dm-artin", payload.streamChannelId)
        assertEquals(null, payload.dmPeerName)
        assertEquals(null, payload.dmPeerAvatarUrl)
        assertEquals(null, payload.dmPeerAvatarLabel)
    }

    @Test
    fun launchPayloadDmWithoutPeerNameKeepsTitleIdentityOnly() {
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

        assertEquals("", payload.title)
        assertEquals(null, payload.dmPeerName)
        assertEquals(null, payload.dmPeerAvatarUrl)
    }

    @Test
    fun launchPayloadDmEmptyPeerNameKeepsTitleIdentityOnly() {
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

        assertEquals("", payload.title)
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
                permissions = ConversationPermissions(canSend = true)
            )
        val payload =
            buildAppShellThreadPayloadFromSummary(conversation)
        val headerDisplay =
            payload.threadHeaderDisplayModelFromDirectory(conversation.toConversationHeaderProjection())

        assertEquals(RaftThreadSurfaceKind.DirectMessage, payload.surfaceKind)
        assertEquals("", payload.title)
        assertEquals(null, payload.dmPeerName)
        assertEquals("artin", headerDisplay.title)
        assertEquals(null, headerDisplay.dmPeerAvatarWireValue)
        assertEquals("AR", headerDisplay.dmPeerAvatarLabel)
        assertEquals(false, payload.showMembersAction)
        assertEquals(false, payload.showSettingsAction)
        assertEquals(true, payload.memberScopedMentions)
        assertEquals(false, payload.threadComposerMemberScopedMentions())
        assertEquals(false, payload.threadComposerMemberSuggestionHeadersVisible())
        assertEquals(true, payload.shouldPrioritizeThreadMentionParticipants())
    }

    @Test
    fun appShellAgentDmHeaderProjectionRejectsGravatarAvatar() {
        val conversation =
            ConversationSummary(
                threadId = ThreadId("dm-agent-1"),
                channelId = ChannelId("dm-agent-1"),
                kind = ConversationKind.DirectMessage,
                name = "Product-UX-Designer",
                directMessagePeerType = "agent",
                permissions = ConversationPermissions(canSend = true)
            )
        val payload = buildAppShellThreadPayloadFromSummary(conversation)
        val headerDisplay = payload.threadHeaderDisplayModelFromDirectory(conversation.toConversationHeaderProjection())

        assertEquals("Product-UX-Designer", headerDisplay.title)
        assertEquals(null, headerDisplay.dmPeerAvatarWireValue)
        assertEquals("PR", headerDisplay.dmPeerAvatarLabel)
        assertEquals(null, payload.dmPeerAvatarUrl)
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
                permissions = ConversationPermissions(canSend = true)
            )
        val payload = buildAppShellThreadPayloadFromSummary(conversation)
        val headerDisplay = payload.threadHeaderDisplayModelFromDirectory(conversation.toConversationHeaderProjection())

        assertEquals(RaftThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("", payload.title)
        assertEquals("首页专修", headerDisplay.title)
        assertEquals("Home repair", headerDisplay.subtitle)
        assertEquals(null, payload.dmPeerName)
        assertEquals(null, payload.dmPeerAvatarUrl)
        assertEquals("Message #首页专修", payload.resolveThreadComposer(displayName = headerDisplay.title).placeholder)
    }

    // task #396: a Slack-bridged channel's header carries the bridge provider for the `[Slack]` tag;
    // an unbridged channel and a DM never do.
    @Test
    fun appShellChannelHeaderProjectionCarriesSlackBridgeProvider() {
        val bridged =
            ConversationSummary(
                threadId = ThreadId("channel-bridged"),
                channelId = ChannelId("channel-bridged"),
                kind = ConversationKind.Channel,
                name = "slack-sync",
                bridgeProvider = "slack",
                permissions = ConversationPermissions(canSend = true)
            )
        val plain = bridged.copy(threadId = ThreadId("channel-plain"), channelId = ChannelId("channel-plain"), bridgeProvider = null)

        val bridgedHeader =
            buildAppShellThreadPayloadFromSummary(bridged)
                .threadHeaderDisplayModelFromDirectory(bridged.toConversationHeaderProjection())
        val plainHeader =
            buildAppShellThreadPayloadFromSummary(plain)
                .threadHeaderDisplayModelFromDirectory(plain.toConversationHeaderProjection())

        assertEquals(RaftThreadSurfaceKind.Channel, bridgedHeader.surfaceKind)
        assertEquals("slack", bridgedHeader.bridgeProvider)
        assertEquals(null, plainHeader.bridgeProvider)
    }

    @Test
    fun directoryProjectionUpdatesThreadHeaderWithoutChangingRouteIdentity() {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "Channel",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                surfaceKind = RaftThreadSurfaceKind.Channel
            )
        val projection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1", "stream-channel-1"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "private",
                title = "ops-room",
                subtitle = "Operations"
            )

        val headerDisplay = payload.threadHeaderDisplayModelFromDirectory(projection)

        assertEquals("ops-room", headerDisplay.title)
        assertEquals("Operations", headerDisplay.subtitle)
        assertEquals("private", headerDisplay.channelKind)
        assertEquals("Channel", payload.title)
        assertEquals(null, payload.subtitle)
        assertEquals("channel", payload.channelKind)
        assertEquals("channel-1", payload.sourceThreadId)
        assertEquals("channel-1", payload.streamChannelId)
        assertEquals(null, payload.parentChannelId)
        assertEquals(null, payload.parentMessageId)
    }

    @Test
    fun channelHeaderIgnoresPayloadTitleWithoutDirectoryProjection() {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "wrong-network-title",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "joint"
            )

        val headerDisplay = payload.threadHeaderDisplayModelFromDirectory(null)

        assertEquals("Channel", headerDisplay.title)
        assertEquals("joint", headerDisplay.channelKind)
    }

    @Test
    fun notificationLaunchHeaderUsesLoadedThreadTitleWithoutRouteDisplayPayload() {
        val routePayload =
            WireThreadLaunchPayload(
                instanceId = "notification-thread-channel-1-msg-42",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                focusedMessageId = "msg-42"
            ).toInitialPayload("notification-thread-channel-1-msg-42")
        val input = routePayload.threadHeaderProjectionInput()
        val loadedPayload =
            routePayload.copy(
                title = "对话流专修",
                subtitle = "Thread fixes",
                status = "content",
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg-42",
                            authorId = "user-1",
                            authorName = "Artin",
                            body = "body"
                        )
                    )
            )

        val initialHeader = input.displayModel(projection = null, contentPayload = routePayload)
        val loadedHeader = input.displayModel(projection = null, contentPayload = loadedPayload)

        assertEquals("Channel", initialHeader.title)
        assertEquals("对话流专修", loadedHeader.title)
        assertEquals("Thread fixes", loadedHeader.subtitle)
        assertEquals("", routePayload.title)
    }

    @Test
    fun threadHeaderProjectionRoutePayloadUsesResolvedIdentityButNoDisplayContent() {
        val loadedPayload =
            RaftThreadScreenPayload(
                instanceId = "thread-launch",
                title = "wrong-network-title",
                subtitle = "wrong-network-subtitle",
                status = "content",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "joint",
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "msg-42",
                            authorId = "user-1",
                            authorName = "Artin",
                            body = "body"
                        )
                    )
            )
        val projection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1"),
                conversationKind = ConversationKind.Joint,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "joint",
                title = "对话流专修",
                subtitle = "Thread fixes"
            )

        val routePayload = loadedPayload.threadHeaderProjectionRoutePayload()
        val input = routePayload.threadHeaderProjectionInput()
        val displayWithProjection = input.displayModel(projection, routePayload)
        val displayWithoutProjection = input.displayModel(null, routePayload)

        assertEquals(listOf("channel-1", "channel-1"), input.lookupIds)
        assertEquals("", routePayload.title)
        assertEquals(null, routePayload.subtitle)
        assertEquals("loading", routePayload.status)
        assertEquals(emptyList(), routePayload.messages)
        assertEquals("对话流专修", displayWithProjection.title)
        assertEquals("Thread fixes", displayWithProjection.subtitle)
        assertEquals("joint", displayWithProjection.channelKind)
        assertEquals("Channel", displayWithoutProjection.title)
        assertEquals(null, displayWithoutProjection.subtitle)
    }

    @Test
    fun contentSliceRejectsIdentityLikeTitleAndUsesSafeSameRouteHeaderMetadata() {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "Channel",
                subtitle = null,
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "joint"
            )
        val networkPatch =
            payload.copy(
                title = "1fe17699-4cc4-4331-bb98-f7a327b947ac",
                subtitle = "network subtitle",
                status = "content",
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "m1",
                            authorId = "user-1",
                            authorName = "Artin",
                            body = "body"
                        )
                    )
            )

        val merged = payload.withContentSliceFrom(networkPatch)

        assertEquals("Channel", merged.title)
        assertEquals("network subtitle", merged.subtitle)
        assertEquals("content", merged.status)
        assertEquals(listOf("m1"), merged.messages.map { it.id })
    }

    @Test
    fun replyThreadParentHeaderProjectionParsesDirectAndRejectsUnresolved() {
        // t432 PR-A: the header's private parent-surface table (with its live
        // `else -> Channel` fallback — the last one in the codebase) is gone. "direct"
        // parses to dm via the contract; an unresolvable parent shape rejects enrichment
        // instead of guessing Channel (display degradation over displayed misidentity).
        val dmProjection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "dm-parent",
                aliases = setOf("dm-parent"),
                conversationKind = ConversationKind.DirectMessage,
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                title = "Cindy"
            )
        val channelProjection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "dm-parent",
                aliases = setOf("dm-parent"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "channel",
                title = "general"
            )
        val payload =
            RaftThreadScreenPayload(
                instanceId = "thread-1",
                title = "",
                sourceThreadId = "reply-stream",
                streamChannelId = "reply-stream",
                parentChannelId = "dm-parent",
                parentMessageId = "parent-message",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentSurfaceKind = "direct"
            )

        assertTrue(payload.acceptsThreadHeaderDirectoryProjection(dmProjection))
        assertFalse(payload.acceptsThreadHeaderDirectoryProjection(channelProjection))

        val unresolved = payload.copy(parentSurfaceKind = null)
        assertFalse(unresolved.acceptsThreadHeaderDirectoryProjection(dmProjection))
        assertFalse(unresolved.acceptsThreadHeaderDirectoryProjection(channelProjection))
    }

    @Test
    fun threadHeaderProjectionInputKeepsLastAcceptedProjectionWhenCurrentIsEmpty() {
        val routePayload =
            RaftThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "joint"
            )
        val input = routePayload.threadHeaderProjectionInput()
        val acceptedProjection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1"),
                conversationKind = ConversationKind.Joint,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "joint",
                title = "raft-mobile",
                subtitle = "Mobile work"
            )

        val stableProjection = input.stableProjection(current = null, previous = acceptedProjection)
        val headerDisplay = input.displayModel(stableProjection, routePayload)

        assertEquals(acceptedProjection, stableProjection)
        assertEquals("raft-mobile", headerDisplay.title)
        assertEquals("joint", headerDisplay.channelKind)
    }

    @Test
    fun dmHeaderProjectionInputKeepsLastAcceptedAvatarWhenCurrentIsEmpty() {
        val routePayload =
            RaftThreadScreenPayload(
                instanceId = "thread-dm-1",
                title = "",
                sourceThreadId = "dm-1",
                streamChannelId = "dm-1",
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = "dm"
            )
        val input = routePayload.threadHeaderProjectionInput()
        val acceptedProjection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "dm-1",
                aliases = setOf("dm-1"),
                conversationKind = ConversationKind.DirectMessage,
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                title = "artin",
                avatarWireValue = "pixel:crown",
                avatarLabel = "AR",
                peerIdentity =
                    MemberDetailIdentity(
                        memberId = MemberId("member-artin"),
                        kind = MemberKind.Human,
                        sourceServerId = ServerId("server-1")
                    )
            )

        val stableProjection = input.stableProjection(current = null, previous = acceptedProjection)
        val headerDisplay = input.displayModel(stableProjection, routePayload)

        assertEquals(acceptedProjection, stableProjection)
        assertEquals("artin", headerDisplay.title)
        assertEquals("pixel:crown", headerDisplay.dmPeerAvatarWireValue)
        assertEquals("AR", headerDisplay.dmPeerAvatarLabel)
        assertEquals("member-artin", headerDisplay.dmPeerIdentity?.memberId?.value)
        assertEquals(MemberKind.Human, headerDisplay.dmPeerIdentity?.kind)
    }

    @Test
    fun jointPayloadIgnoresGenericChannelDirectoryProjection() {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "thread-joint-1",
                title = "partner",
                sourceThreadId = "joint-1",
                streamChannelId = "joint-1",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "joint"
            )
        val projection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "joint-1",
                aliases = setOf("joint-1"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "channel",
                title = "partner",
                subtitle = "Generic projection"
            )

        val headerDisplay = payload.threadHeaderDisplayModelFromDirectory(projection)

        assertFalse(payload.acceptsConversationHeaderProjection(projection))
        assertEquals("joint", headerDisplay.channelKind)
        assertEquals(RaftLucideIconName.GitBranch, headerDisplay.channelKind.threadChannelHeaderIcon())
    }

    @Test
    fun genericChannelPayloadAcceptsSpecificDirectoryChannelKind() {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "thread-private-1",
                title = "ops",
                sourceThreadId = "private-1",
                streamChannelId = "private-1",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "channel"
            )
        val projection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "private-1",
                aliases = setOf("private-1"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "private",
                title = "ops"
            )

        val headerDisplay = payload.threadHeaderDisplayModelFromDirectory(projection)

        assertTrue(payload.acceptsConversationHeaderProjection(projection))
        assertEquals("private", headerDisplay.channelKind)
        assertEquals(RaftLucideIconName.Lock, headerDisplay.channelKind.threadChannelHeaderIcon())
    }

    @Test
    fun directoryProjectionDrivesDmHeaderAvatarWithoutChangingRouteIdentity() {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "dm-1",
                title = "Direct Message",
                sourceThreadId = "dm-1",
                streamChannelId = "dm-1",
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                dmPeerIdentity =
                    MemberDetailIdentity(
                        memberId = MemberId("agent-1"),
                        kind = MemberKind.Agent
                    ),
                dmPeerName = null,
                dmPeerAvatarUrl = null,
            )
        val projection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "dm-1",
                aliases = setOf("dm-1", "agent-1"),
                conversationKind = ConversationKind.DirectMessage,
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                title = "Product-UX-Designer",
                avatarWireValue = "pixel:robot",
                avatarLabel = "PR",
                peerIdentity =
                    MemberDetailIdentity(
                        memberId = MemberId("agent-1"),
                        kind = MemberKind.Agent,
                        sourceServerId = ServerId("server-1")
                    )
            )

        val headerDisplay = payload.threadHeaderDisplayModelFromDirectory(projection)

        assertEquals("Product-UX-Designer", headerDisplay.title)
        assertEquals("pixel:robot", headerDisplay.dmPeerAvatarWireValue)
        assertEquals("PR", headerDisplay.dmPeerAvatarLabel)
        assertEquals("agent-1", headerDisplay.dmPeerIdentity?.memberId?.value)
        assertEquals(MemberKind.Agent, headerDisplay.dmPeerIdentity?.kind)
        assertEquals("Direct Message", payload.title)
        assertEquals(null, payload.dmPeerName)
        assertEquals(null, payload.dmPeerAvatarUrl)
        assertEquals("dm-1", payload.sourceThreadId)
        assertEquals("dm-1", payload.streamChannelId)
    }

    @Test
    fun directoryProjectionDoesNotChangeReplyThreadRequestIdentity() {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "reply-message-1",
                title = ThreadReplyPageTitle,
                sourceThreadId = "reply-thread-1",
                streamChannelId = "reply-thread-1",
                parentChannelId = "channel-1",
                parentMessageId = "message-1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentSurfaceKind = RaftThreadSurfaceKind.Channel
            )
        val projection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "channel",
                title = "general"
            )
        val headerDisplay = payload.threadHeaderDisplayModelFromDirectory(projection)

        assertEquals(ThreadReplyPageTitle, headerDisplay.title)
        assertEquals("#general", headerDisplay.subtitle)
        assertEquals(null, payload.subtitle)
        assertEquals("reply-thread-1", payload.sourceThreadId)
        assertEquals("reply-thread-1", payload.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("message-1", payload.parentMessageId)
    }

    @Test
    fun activityReplyThreadKeepsParentChromeAfterLoadedPayloadTransition() {
        val request =
            InboxItem(
                key = "thread:message-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("reply-thread-1"),
                channelName = "reply-thread",
                threadChannelId = ChannelId("reply-thread-1"),
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("message-1"),
                latestActivityMessageId = MessageId("reply-message-1")
            ).toHomeMessageOpenRequestForTest()
        val launch =
            requireNotNull(buildStandaloneConversationThreadLaunchPayload(
                conversation = request.conversationIdentity,
                timeContext = null,
                anchorMessageId = request.anchorMessageId
            ))
        val initial =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(launch),
                instanceId = "activity-reply-thread"
            )
        val loaded =
            buildSharedThreadPayloadFromMessagesPage(
                basePayload = initial.copy(showOpenInChannelAction = false),
                page =
                    ThreadMessagesPage(
                        messages = emptyList(),
                        canLoadMoreBefore = false,
                        canLoadMoreAfter = false
                    ),
                session = null
            )
        val parentProjection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "channel",
                title = "general"
            )
        val headerDisplay =
            loaded
                .threadHeaderProjectionInput()
                .displayModel(parentProjection, loaded)

        assertEquals("#general", headerDisplay.subtitle)
        assertTrue(loaded.threadHeaderOpenInParentActionVisible())
        assertEquals("channel-1", loaded.parentChannelId)
        assertEquals("message-1", loaded.parentMessageId)
    }

    @Test
    fun threadHeaderProjectionUsesScopeServerBeforeAsyncSessionReadCompletes() {
        assertEquals(
            "server-from-scope",
            threadHeaderProjectionServerId(
                sessionServerId = null,
                scopeInitialServerId = "server-from-scope"
            )
        )
        assertEquals(
            "server-from-session",
            threadHeaderProjectionServerId(
                sessionServerId = "server-from-session",
                scopeInitialServerId = "server-from-scope"
            )
        )
    }

    @Test
    fun threadHeaderInitialProjectionReadsCachedParentBeforeFlowCollection() {
        val payload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "reply-thread-1",
                streamChannelId = "reply-thread-1",
                parentChannelId = "channel-1",
                parentMessageId = "message-1",
                parentSurfaceKind = RaftThreadSurfaceKind.Channel
            )
        val parentProjection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "channel",
                title = "general"
            )
        val provider =
            object : SharedConversationIdentityProvider {
                override fun observeServer(serverId: ServerId): Flow<SharedConversationDirectoryState> =
                    flowOf(SharedConversationDirectoryState())

                override fun projectionFlow(
                    serverId: ServerId,
                    ids: Collection<String>
                ): Flow<Map<String, SharedConversationDirectoryProjection>> = flowOf(emptyMap())

                override fun resolveProjection(
                    serverId: ServerId,
                    id: String
                ): SharedConversationDirectoryProjection? =
                    parentProjection.takeIf { serverId.value == "server-1" && id == "channel-1" }
            }
        val input = payload.threadHeaderProjectionInput()
        val initialMap =
            threadHeaderInitialProjectionMap(
                provider = provider,
                serverId = ServerId("server-1"),
                input = input
            )
        val owner = ThreadHeaderProjectionStateOwner(input, initialMap)
        val initialDisplay = input.displayModel(owner.selectedProjection(), payload)

        owner.accept(emptyMap())
        val incompleteParentProjection =
            parentProjection.copy(
                title = "",
                subtitle = null
            )
        val incompleteOutcome = owner.accept(mapOf("channel-1" to incompleteParentProjection))
        val secondaryProjection =
            parentProjection.copy(
                canonicalChannelId = "reply-thread-1",
                aliases = setOf("reply-thread-1"),
                title = "secondary"
            )
        val secondaryOutcome = owner.accept(mapOf("reply-thread-1" to secondaryProjection))
        val loadedPayload = payload.copy(status = "content", showOpenInChannelAction = false)
        val loadedDisplay = input.displayModel(owner.selectedProjection(), loadedPayload)

        assertEquals("#general", initialDisplay.subtitle)
        assertEquals("#general", initialDisplay.threadComposerMentionScopeChannelLabel())
        assertEquals("channel-1", payload.composer.mentionChannelId ?: payload.parentChannelId)
        assertEquals("#general", loadedDisplay.subtitle)
        assertEquals("#general", loadedDisplay.threadComposerMentionScopeChannelLabel())
        assertTrue(loadedPayload.threadHeaderOpenInParentActionVisible())
        assertEquals(setOf("channel-1"), owner.state.value.keys)
        assertEquals(parentProjection, owner.selectedProjection())
        assertEquals(
            ThreadHeaderProjectionAcceptance.PreservedNoDisplayValidProjection,
            incompleteOutcome
        )
        assertEquals(
            ThreadHeaderProjectionAcceptance.PreservedNoDisplayValidProjection,
            secondaryOutcome
        )
    }

    @Test
    fun replyThreadShowsParentMutedStatusWithoutExposingItsOwnMuteAction() {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "reply-message-1",
                title = ThreadReplyPageTitle,
                sourceThreadId = "reply-thread-1",
                streamChannelId = "reply-thread-1",
                parentChannelId = "channel-1",
                parentMessageId = "message-1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                activityMuteSupported = true
            )
        val parentProjection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1", "reply-thread-1"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "channel",
                title = "general",
                joined = true,
                activityNotificationPreference =
                    ChannelActivityNotificationPreference(
                        activityMuted = true,
                        activityMuteSupported = true
                    )
            )

        assertEquals(null, payload.threadActivityMuteState(parentProjection))
        assertTrue(payload.threadActivityMutedIndicatorVisible(parentProjection))
    }

    @Test
    fun channelPayloadKeepsActivityMuteStateWhenProjectionClaimsReplyThread() {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                joined = true,
                activityMuteSupported = true
            )
        val wrongProjection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1"),
                conversationKind = ConversationKind.Thread,
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                channelKind = "channel",
                title = "general",
                joined = true,
                activityNotificationPreference =
                    ChannelActivityNotificationPreference(
                        activityMuted = true,
                        activityMuteSupported = true
                    )
            )

        val muteState = payload.threadActivityMuteState(wrongProjection)

        assertEquals("channel-1", muteState?.channelId)
        assertEquals(true, muteState?.muted)
        assertEquals(true, muteState?.supported)
        assertTrue(payload.threadActivityMutedIndicatorVisible(wrongProjection))
    }

    @Test
    fun mutedIndicatorFollowsCanonicalMuteStateAcrossChannelAndDirectMessageSurfaces() {
        val unmutedChannel =
            RaftThreadScreenPayload(
                instanceId = "channel-1",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                activityMuted = false,
                activityMuteSupported = true
            )
        val mutedDirectMessage =
            RaftThreadScreenPayload(
                instanceId = "dm-1",
                sourceThreadId = "dm-1",
                streamChannelId = "dm-1",
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                activityMuted = true,
                activityMuteSupported = true
            )

        assertFalse(unmutedChannel.threadActivityMutedIndicatorVisible(null))
        assertTrue(mutedDirectMessage.threadActivityMutedIndicatorVisible(null))
    }

    @Test
    fun conversationHeaderProjectionDoesNotRenderIdentityAsTitle() {
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        val conversation =
            ConversationSummary(
                threadId = ThreadId(uuid),
                channelId = ChannelId(uuid),
                kind = ConversationKind.Channel,
                name = uuid,
                permissions = ConversationPermissions(canSend = true)
            )
        val payload =
            RaftThreadScreenPayload(
                instanceId = "notification-thread",
                title = "Channel",
                sourceThreadId = uuid,
                streamChannelId = uuid,
                surfaceKind = RaftThreadSurfaceKind.Channel
            )
        val headerDisplay = payload.threadHeaderDisplayModelFromDirectory(conversation.toConversationHeaderProjection())

        assertEquals("Channel", headerDisplay.title)
    }

    @Test
    fun replyThreadHeaderUsesMatchedParentDirectoryProjectionForSubtitle() {
        val payload =
            RaftThreadScreenPayload(
                title = ThreadReplyPageTitle,
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelId = "channel-1",
                parentMessageId = "message-1",
                originParentMessageId = "message-1",
                subtitle = null
            )
        val projection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "channel",
                title = "general",
                subtitle = "General updates"
            )

        val headerDisplay = payload.threadHeaderDisplayModelFromDirectory(projection)

        assertEquals(ThreadReplyPageTitle, headerDisplay.title)
        assertEquals("#general", headerDisplay.subtitle)
        assertEquals(RaftThreadSurfaceKind.ReplyThread, headerDisplay.surfaceKind)
        assertEquals(null, payload.subtitle)
    }

    @Test
    fun replyThreadHeaderHidesUnverifiedChannelSubtitleWithoutDirectoryProjection() {
        val payload =
            RaftThreadScreenPayload(
                title = ThreadReplyPageTitle,
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "channel-1",
                parentMessageId = "message-1",
                subtitle = "#product"
            )

        val headerDisplay = payload.threadHeaderDisplayModelFromDirectory(null)

        assertNull(headerDisplay.subtitle)
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
        val payload = buildAppShellThreadPayloadFromSummary(conversation)
        val headerDisplay = payload.threadHeaderDisplayModelFromDirectory(conversation.toConversationHeaderProjection())

        assertEquals(RaftThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("private", payload.channelKind)
        assertEquals("private", headerDisplay.channelKind)
        assertEquals(RaftLucideIconName.Lock, headerDisplay.channelKind.threadChannelHeaderIcon())
    }

    @Test
    fun appShellJointChannelPayloadUsesBranchHeaderKindWhenRawTypeIsGenericChannel() {
        val conversation =
            ConversationSummary(
                threadId = ThreadId("joint-1"),
                channelId = ChannelId("joint-1"),
                channelType = "channel",
                kind = ConversationKind.Joint,
                name = "partner",
                permissions = ConversationPermissions(canSend = true)
            )
        val payload = buildAppShellThreadPayloadFromSummary(conversation)
        val headerDisplay = payload.threadHeaderDisplayModelFromDirectory(conversation.toConversationHeaderProjection())

        assertEquals(RaftThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("joint", payload.channelKind)
        assertEquals("joint", headerDisplay.channelKind)
        assertEquals(RaftLucideIconName.GitBranch, headerDisplay.channelKind.threadChannelHeaderIcon())
    }

    @Test
    fun appShellPayloadMarksJointChannelsAsMemberScopedMentions() {
        val payload =
            buildAppShellThreadPayloadFromSummary(
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
        assertEquals(true, payload.threadComposerMemberScopedMentions())
        assertEquals(true, payload.threadComposerMemberSuggestionHeadersVisible())
        assertEquals("joint-1", payload.resolveThreadComposer().streamChannelId)
    }

    @Test
    fun replyThreadComposerUsesServerWideMemberSuggestionsWithMembershipHeaders() {
        val payload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelKind = "channel",
                memberScopedMentions = true
            )

        assertEquals(false, payload.threadComposerMemberScopedMentions())
        assertEquals(true, payload.threadComposerMemberSuggestionHeadersVisible())
    }

    @Test
    fun jointReplyThreadComposerUsesParentChannelMemberScope() {
        val payload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "joint-channel-1",
                parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelKind = "joint",
                memberScopedMentions = true,
                composer =
                    buildThreadSurfaceComposerPayload(
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        displayName = ThreadReplyPageTitle,
                        fallbackId = "joint-parent-1",
                        enabled = true,
                        mentionChannelId = "joint-channel-1",
                        parentMessageId = "joint-parent-1",
                        streamChannelId = "joint-thread-1"
                    )
            )

        assertEquals("joint-channel-1", payload.resolveThreadComposer().mentionChannelId)
        assertEquals(true, payload.threadComposerMemberScopedMentions())
        assertEquals(true, payload.threadComposerMemberSuggestionHeadersVisible())
    }

    @Test
    fun directMessageReplyThreadComposerUsesServerWideMemberSuggestionsWithoutMembershipHeaders() {
        val payload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentSurfaceKind = RaftThreadSurfaceKind.DirectMessage,
                memberScopedMentions = true
            )

        assertEquals(false, payload.threadComposerMemberScopedMentions())
        assertEquals(false, payload.threadComposerMemberSuggestionHeadersVisible())
    }

    @Test
    fun wireThreadLaunchPayloadCarriesChannelKindIntoInitialPayload() {
        val payload =
            WireThreadLaunchPayload(
                instanceId = "joint-thread",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "joint",
                sourceThreadId = "joint-1",
                streamChannelId = "joint-1"
            ).toInitialPayload("fallback")

        assertEquals(RaftThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("joint", payload.channelKind)
        assertEquals(RaftLucideIconName.GitBranch, payload.channelKind.threadChannelHeaderIcon())
    }

    @Test
    fun wireThreadLaunchPayloadCarriesRowActionAffordancesIntoInitialPayload() {
        val payload =
            WireThreadLaunchPayload(
                instanceId = "row-actions-thread",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                showReactionActions = false
            ).toInitialPayload("fallback")

        assertFalse(payload.showReactionActions)
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
                RaftThreadSettingsAction(
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
                RaftThreadSettingsAction(id = "visibility", enabled = false),
                RaftThreadSettingsAction(id = "archive", enabled = true),
                RaftThreadSettingsAction(id = "delete", enabled = false)
            ).normalizedThreadSettingsChannelActions()

        assertEquals(listOf("archive"), actions.map { it.id })
    }

    @Test
    fun threadSettingsKnownActionsUseCanonicalUiModel() {
        val actions =
            listOf(
                RaftThreadSettingsAction(id = "visibility"),
                RaftThreadSettingsAction(id = "archive"),
                RaftThreadSettingsAction(id = "delete")
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
            listOf(RaftLucideIconName.Lock, RaftLucideIconName.Archive, RaftLucideIconName.Trash),
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
    fun conversationAccessFallbackMustMatchRouteIdentityBeforeDisablingComposer() {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "thread-channel-1",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                joined = true,
                canSend = true,
                composer =
                    RaftThreadComposerPayload(
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        enabled = true,
                        streamChannelId = "channel-1"
                    )
            )
        val unrelatedAccessFallback =
            ConversationSummary(
                threadId = ThreadId("channel-2"),
                channelId = ChannelId("channel-2"),
                kind = ConversationKind.Channel,
                name = "unrelated",
                joined = false,
                permissions = ConversationPermissions(canSend = false)
            )

        val result = payload.withConversationAccessFallback(unrelatedAccessFallback.toConversationAccessCandidate())

        assertEquals(true, result.joined)
        assertEquals(true, result.canSend)
        assertEquals(true, result.composer.enabled)
    }

    @Test
    fun buildsSharedShellThreadRoutePayloadFromConversation() {
        val payload =
            buildAppShellThreadPayloadFromSummary(
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
        assertEquals("", payload.title)
        assertEquals(null, payload.subtitle)
        assertEquals("loading", payload.status)
        assertEquals("channel-1", payload.sourceThreadId)
        assertEquals(true, payload.canSend)
        assertEquals(0, payload.membersCount)
        assertEquals(480, payload.localOffsetMinutes)
        assertEquals(20593, payload.todayEpochDay)
        assertEquals(true, payload.showReplyActions)
        assertEquals(true, payload.alignOwnMessagesRight)
        assertEquals(true, payload.showSettingsAction)
        assertEquals(RaftThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("channel-1", payload.streamChannelId)
        assertEquals(null, payload.parentChannelId)
        assertEquals(null, payload.parentMessageId)
        assertEquals(
            listOf("visibility", "archive", "delete"),
            payload.settingsActions.map { it.id }
        )
        val composer = payload.resolveThreadComposer()
        assertEquals("Message #channel-1", composer.placeholder)
        assertEquals(RaftThreadSurfaceKind.Channel, composer.surfaceKind)
        assertEquals("channel-1", composer.streamChannelId)
        assertEquals("idle", composer.composerMode)
    }

    @Test
    fun sharedShellJointChannelRouteHasRegularChannelTaskSurfaces() {
        fun payloadFor(channelType: String) =
            buildAppShellThreadPayloadFromSummary(
                ConversationSummary(
                    threadId = ThreadId("joint-1"),
                    channelId = ChannelId("joint-1"),
                    channelType = channelType,
                    kind = ConversationKind.Channel,
                    name = "partner",
                    subtitle = "Joint channel",
                    joined = true,
                    memberCount = 3,
                    permissions = ConversationPermissions(canSend = true, canCreateTask = true)
                )
            )
        val payload = payloadFor("joint")
        val regular = payloadFor("channel")

        // Joint task surfaces exist server-side; the client no longer hides them.
        assertEquals(RaftThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals(true, payload.supportsChannelTasks())
        assertEquals(regular.showTaskOpenActions, payload.showTaskOpenActions)
        assertEquals(regular.showTaskStatusActions, payload.showTaskStatusActions)
        assertEquals(regular.composer.showAsTaskToggle, payload.composer.showAsTaskToggle)
        assertEquals(regular.resolveThreadComposer().showAsTaskToggle, payload.resolveThreadComposer().showAsTaskToggle)
        assertEquals(
            shouldStartThreadTasksJoin(regular.copy(status = "content"), null, ChannelId("joint-1")),
            shouldStartThreadTasksJoin(payload.copy(status = "content"), null, ChannelId("joint-1"))
        )
    }

    @Test
    fun sharedShellReplyThreadWithJointParentHasRegularTaskSurfaces() {
        fun payloadFor(parentKind: String) =
            buildAppShellThreadPayloadFromSummary(
                ConversationSummary(
                    threadId = threadIdentity(ChannelId("joint-1"), MessageId("message-1")),
                    channelId = ChannelId("joint-1"),
                    kind = ConversationKind.Thread,
                    name = "Reply thread",
                    joined = true,
                    permissions = ConversationPermissions(canSend = true, canCreateTask = true),
                    thread =
                        ThreadConversation(
                            parentChannelId = ChannelId("joint-1"),
                            parentMessageId = MessageId("message-1"),
                            streamChannelId = ChannelId("thread-message-1"),
                            parentChannelKind = parentKind
                        )
                )
            )
        val payload = payloadFor("joint")
        val regular = payloadFor("channel")

        assertEquals(RaftThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("joint", payload.parentChannelKind)
        assertEquals(true, payload.supportsChannelTasks())
        assertEquals(regular.showTaskOpenActions, payload.showTaskOpenActions)
        assertEquals(regular.showTaskStatusActions, payload.showTaskStatusActions)
        assertEquals(regular.composer.showAsTaskToggle, payload.composer.showAsTaskToggle)
        assertEquals(regular.resolveThreadComposer().showAsTaskToggle, payload.resolveThreadComposer().showAsTaskToggle)
    }

    @Test
    fun replyThreadRealtimeRoomsIncludeParentChannelAndThreadRooms() {
        val payload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "dm-parent-channel",
                sourceThreadId = "thread-channel",
                streamChannelId = "thread-channel"
            )

        assertEquals(
            listOf("dm-parent-channel", "thread-channel"),
            payload.activeRealtimeRoomIds()
        )
    }

    @Test
    fun onlyAReplyThreadsOwnRoomIsKeptAfterThePageCloses() {
        // Task #14c: the kept rooms are the thread's own channel, never its parent channel.
        val reply =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "parent-channel",
                sourceThreadId = "thread-channel",
                streamChannelId = "thread-channel"
            )
        assertEquals(setOf("thread-channel"), reply.replyThreadRealtimeRoomIds())
        // Twin: the same ids on a channel page keep nothing (a channel keeps today's leave rule).
        assertEquals(emptySet(), reply.copy(surfaceKind = RaftThreadSurfaceKind.Channel).replyThreadRealtimeRoomIds())
        assertEquals(emptySet(), reply.copy(sourceThreadId = "parent-channel", streamChannelId = " ").replyThreadRealtimeRoomIds())
    }

    @Test
    fun replyThreadRealtimeRoomsWaitForConcreteParentChannel() {
        val payload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = " ",
                sourceThreadId = "thread-channel",
                streamChannelId = "thread-channel"
            )

        assertEquals(
            listOf("thread-channel"),
            payload.activeRealtimeRoomIds()
        )
    }

    @Test
    fun topLevelThreadMessagesCanConvertToTasksWhenTaskActionsAreEnabled() {
        val payload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                showTaskStatusActions = true
            )

        assertTrue(payload.canConvertMessagesToTasks())
        assertTrue(
            RaftThreadMessageItem(
                id = "msg-1",
                authorId = "u1",
                authorName = "Artin",
                body = "ship it"
            ).canConvertToTask()
        )
    }

    @Test
    fun replyThreadAndIneligibleMessagesCannotConvertToTasks() {
        val replyPayload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentMessageId = "parent-1",
                originParentMessageId = "parent-1",
                showTaskStatusActions = true
            )
        val disabledPayload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                showTaskStatusActions = false
            )

        assertFalse(replyPayload.canConvertMessagesToTasks())
        assertFalse(disabledPayload.canConvertMessagesToTasks())
        assertFalse(
            RaftThreadMessageItem(
                id = "msg-task-id",
                authorId = "u1",
                authorName = "Artin",
                taskId = "task-1"
            ).canConvertToTask()
        )
        assertFalse(
            RaftThreadMessageItem(
                id = "msg-task-number",
                authorId = "u1",
                authorName = "Artin",
                taskNumber = 12
            ).canConvertToTask()
        )
        assertFalse(
            RaftThreadMessageItem(
                id = "msg-system",
                authorId = "system",
                authorName = "System",
                messageType = "System"
            ).canConvertToTask()
        )
        assertFalse(
            RaftThreadMessageItem(
                id = "msg-local",
                authorId = "u1",
                authorName = "Artin",
                deliveryState = ThreadMessageDeliverySending,
                pendingSendContext =
                    RaftThreadPendingSendContext(
                        channelId = "channel-1",
                        body = "pending"
                    )
            ).canConvertToTask()
        )
    }

    @Test
    fun sharedShellDirectMessageRouteKeepsDmSurfaceForComposerProps() {
        val payload =
            buildAppShellThreadPayloadFromSummary(
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

        val headerDisplay =
            payload.threadHeaderDisplayModelFromDirectory(
                ConversationSummary(
                    threadId = ThreadId("dm-agent-1"),
                    channelId = ChannelId("dm-agent-1"),
                    kind = ConversationKind.DirectMessage,
                    name = "Product-UX-Designer",
                    subtitle = "online",
                    joined = true,
                    permissions = ConversationPermissions(canSend = true)
                ).toConversationHeaderProjection()
            )

        assertEquals("", payload.title)
        assertEquals(null, payload.subtitle)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, payload.surfaceKind)
        assertEquals("dm-agent-1", payload.streamChannelId)
        assertEquals(null, payload.parentChannelId)
        assertEquals(null, payload.parentMessageId)
        assertEquals(null, payload.dmPeerName)
        assertEquals("Product-UX-Designer", headerDisplay.title)
        val composer = payload.resolveThreadComposer(displayName = headerDisplay.title)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, composer.surfaceKind)
        assertEquals("dm-agent-1", composer.streamChannelId)
        assertEquals("Message @Product-UX-Designer", composer.placeholder)
        assertEquals("idle", composer.composerMode)
    }

    @Test
    fun directMessageReplyThreadLaunchKeepsParentSurfaceKind() {
        val dmPayload =
            buildAppShellThreadPayloadFromSummary(
                ConversationSummary(
                    threadId = ThreadId("dm-agent-1"),
                    channelId = ChannelId("dm-agent-1"),
                    kind = ConversationKind.DirectMessage,
                    name = "Product-UX-Designer",
                    permissions = ConversationPermissions(canSend = true)
                )
            )
        val parentMessage =
            RaftThreadMessageItem(
                id = "dm-parent-1",
                authorId = "agent-1",
                authorName = "Product-UX-Designer",
                body = "Can you check this?",
                threadChannelId = "thread-dm-parent-1"
            )

        val replyPayload =
            dmPayload.replyThreadLoadingPayload(
                parentMessageId = parentMessage.id,
                parentChannelId = dmPayload.sourceThreadId,
                threadChannelId = parentMessage.threadChannelId
            )
        val launchPayload = replyPayload.toWireThreadLaunchPayload()
        val parsedPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(WireThreadLaunchPayload.serializer(), launchPayload),
                instanceId = "fallback"
            )
        val parentPayload = parsedPayload.parentMessageContextLoadingPayload("dm-parent-1")

        assertEquals(RaftThreadSurfaceKind.ReplyThread, parsedPayload.surfaceKind)
        assertEquals("previous", replyPayload.parentNavigationHint)
        assertEquals("previous", launchPayload.parentNavigationHint)
        assertEquals("previous", parsedPayload.parentNavigationHint)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, launchPayload.parentSurfaceKind)
        assertEquals("dm-agent-1", parsedPayload.parentChannelId)
        assertEquals("dm-parent-1", parsedPayload.parentMessageId)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, parsedPayload.parentSurfaceKind)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, parentPayload.surfaceKind)
        assertEquals("dm-agent-1", parentPayload.sourceThreadId)
        assertEquals("dm-agent-1", parentPayload.streamChannelId)
        assertEquals("", parentPayload.title)
    }

    @Test
    fun activityReplyThreadOpenActionTargetsParentChannelSurface() {
        val replyPayload =
            RaftThreadScreenPayload(
                instanceId = "shared-shell-thread-reply-stream",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                channelKind = "channel",
                sourceThreadId = "reply-stream",
                streamChannelId = "reply-stream",
                parentChannelId = "parent-channel",
                parentMessageId = "parent-message",
                parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelKind = "channel",
                showOpenInChannelAction = true
            )

        val parentRequest =
            assertNotNull(
                replyPayload.resolveReplyParentConversationOpenRequest(
                    serverId = "server-1",
                    conversationIdentityProvider = null
                )
            )
        val parentIdentity = parentRequest.conversationIdentity
        val parentPayload = requireNotNull(buildAppShellThreadPayload(parentIdentity))

        assertEquals(ConversationKind.Channel, parentIdentity.kind)
        assertEquals("parent-channel", parentIdentity.channelId?.value)
        assertEquals(RaftThreadSurfaceKind.Channel, parentPayload.surfaceKind)
        assertEquals("parent-channel", parentPayload.sourceThreadId)
        assertEquals(null, parentPayload.parentMessageId)
    }

    @Test
    fun replyThreadOpenParentRequiresCurrentSessionServer() {
        val replyPayload =
            RaftThreadScreenPayload(
                instanceId = "shared-shell-thread-reply-stream",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                channelKind = "channel",
                sourceThreadId = "reply-stream",
                streamChannelId = "reply-stream",
                parentChannelId = "parent-channel",
                parentMessageId = "parent-message",
                parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelKind = "channel",
                showOpenInChannelAction = true
            )

        assertNull(replyPayload.resolveReplyParentConversationOpenRequest("", null))
        assertNull(replyPayload.resolveReplyParentConversationOpenRequest("   ", null))
        assertNull(threadParentNavigationServerId(null))
        assertNull(threadParentNavigationServerId("   "))
        assertEquals("server-source", threadParentNavigationServerId(" server-source "))
    }

    @Test
    fun jointReplyThreadOpenParentUsesCurrentSessionProjectionWhenAvailable() {
        val replyPayload =
            RaftThreadScreenPayload(
                instanceId = "shared-shell-thread-joint-reply-stream",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                channelKind = "channel",
                sourceThreadId = "reply-stream",
                streamChannelId = "reply-stream",
                parentChannelId = "joint-parent",
                parentMessageId = "parent-message",
                parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelKind = "joint",
                showOpenInChannelAction = true
            )
        val parentProjection =
            SharedConversationDirectoryProjection(
                serverId = "server-source",
                canonicalChannelId = "joint-parent",
                aliases = setOf("joint-parent"),
                conversationKind = ConversationKind.Joint,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "joint",
                title = "partner"
            )
        val resolvedServers = mutableListOf<String>()
        val provider =
            object : SharedConversationIdentityProvider {
                override fun observeServer(serverId: ServerId): Flow<SharedConversationDirectoryState> =
                    flowOf(SharedConversationDirectoryState())

                override fun projectionFlow(
                    serverId: ServerId,
                    ids: Collection<String>
                ): Flow<Map<String, SharedConversationDirectoryProjection>> = flowOf(emptyMap())

                override fun resolveProjection(
                    serverId: ServerId,
                    id: String
                ): SharedConversationDirectoryProjection? {
                    resolvedServers += serverId.value
                    return parentProjection.takeIf {
                        serverId.value == "server-source" && id == "joint-parent"
                    }
                }
            }

        val parentRequest =
            assertNotNull(
                replyPayload.resolveReplyParentConversationOpenRequest(
                    serverId = " server-source ",
                    conversationIdentityProvider = provider
                )
            )

        assertEquals(listOf("server-source"), resolvedServers)
        assertEquals(ConversationKind.Joint, parentRequest.conversationIdentity.kind)
        assertEquals("joint-parent", parentRequest.conversationIdentity.channelId?.value)
    }

    @Test
    fun jointReplyThreadLaunchKeepsParentChannelKind() {
        val parentMessage =
            RaftThreadMessageItem(
                id = "joint-parent-1",
                authorId = "u1",
                authorName = "Artin",
                body = "Can you check this?",
                threadChannelId = "thread-joint-parent-1"
            )
        val jointPayload =
            RaftThreadScreenPayload(
                instanceId = "thread-joint-1",
                title = "partner",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "joint",
                sourceThreadId = "joint-channel-1",
                streamChannelId = "joint-channel-1",
                status = "content",
                messages = listOf(parentMessage)
            )

        val replyPayload =
            jointPayload.replyThreadLoadingPayload(
                parentMessageId = parentMessage.id,
                parentChannelId = jointPayload.sourceThreadId,
                threadChannelId = parentMessage.threadChannelId
            )
        val launchPayload = replyPayload.toWireThreadLaunchPayload()
        val parsedPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(WireThreadLaunchPayload.serializer(), launchPayload),
                instanceId = "fallback"
            )
        val parentPayload = parsedPayload.parentMessageContextLoadingPayload("joint-parent-1")

        assertEquals(RaftThreadSurfaceKind.ReplyThread, parsedPayload.surfaceKind)
        assertEquals(RaftThreadSurfaceKind.Channel, launchPayload.parentSurfaceKind)
        assertEquals("joint", replyPayload.parentChannelKind)
        assertEquals("joint", launchPayload.parentChannelKind)
        assertEquals("joint", parsedPayload.parentChannelKind)
        assertEquals(true, parsedPayload.threadComposerMemberScopedMentions())
        assertEquals(RaftThreadSurfaceKind.Channel, parentPayload.surfaceKind)
        assertEquals("joint-channel-1", parentPayload.sourceThreadId)
        assertEquals("joint", parentPayload.channelKind)
        assertEquals(RaftLucideIconName.GitBranch, parentPayload.channelKind.threadChannelHeaderIcon())
    }

    @Test
    fun jointReplyThreadLaunchHasRegularInitialTaskSurfaces() {
        fun launchFor(parentKind: String) =
            WireThreadLaunchPayload(
                instanceId = "thread-route",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-channel-1",
                streamChannelId = "thread-channel-1",
                parentChannelId = "joint-1",
                parentMessageId = "parent-message-1",
                parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelKind = parentKind,
                originParentMessageId = "parent-message-1"
            ).toInitialPayload("fallback")
        val initialPayload = launchFor("joint")
        val regular = launchFor("channel")

        assertEquals(RaftThreadSurfaceKind.ReplyThread, initialPayload.surfaceKind)
        assertEquals("joint", initialPayload.parentChannelKind)
        assertEquals(regular.showTaskOpenActions, initialPayload.showTaskOpenActions)
        assertEquals(regular.showTaskStatusActions, initialPayload.showTaskStatusActions)
        assertEquals(regular.composer.showAsTaskToggle, initialPayload.composer.showAsTaskToggle)
        assertEquals(regular.resolveThreadComposer().showAsTaskToggle, initialPayload.resolveThreadComposer().showAsTaskToggle)
        assertEquals(regular.supportsChannelTasks(), initialPayload.supportsChannelTasks())
    }

    @Test
    fun sharedShellReplyThreadRouteUsesConversationChannelSubtitle() {
        val payload =
            buildAppShellThreadPayloadFromSummary(
                ConversationSummary(
                    threadId = threadIdentity(ChannelId("channel-1"), MessageId("message-1")),
                    channelId = ChannelId("channel-1"),
                    thread =
                        ThreadConversation(
                            parentChannelId = ChannelId("channel-1"),
                            parentMessageId = MessageId("message-1"),
                            streamChannelId = ChannelId("thread-1"),
                            parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                            parentChannelKind = "channel"
                        ),
                    kind = ConversationKind.Thread,
                    name = "general",
                    subtitle = "Parent message preview",
                    joined = true,
                    memberCount = 7,
                    permissions = ConversationPermissions(canSend = true)
                )
            )

        assertEquals("", payload.title)
        assertEquals(null, payload.subtitle)
        assertEquals(RaftThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals(null, payload.originMessage)
        assertEquals(null, payload.originPreview)
        assertEquals("message-1", payload.originParentMessageId)
        assertEquals(RaftThreadSurfaceKind.Channel, payload.parentSurfaceKind)
        assertEquals(null, payload.parentNavigationHint)
        assertEquals(null, payload.toWireThreadLaunchPayload().parentNavigationHint)
        assertEquals("missing", payload.originStatus)
        assertEquals(false, payload.showModeTabs)
        assertEquals(false, payload.showMembersAction)
        assertEquals(false, payload.showSettingsAction)
        assertEquals(true, payload.showOpenInChannelAction)
    }

    @Test
    fun threadHeaderDisplayTitleDropsLeadingChannelHash() {
        assertEquals("首页专修", threadHeaderDisplayTitle("#首页专修"))
        assertEquals("design", threadHeaderDisplayTitle("design"))
        assertEquals("Channel", threadHeaderDisplayTitle("#"))
    }

    @Test
    fun threadHeaderDisplayTitleDoesNotRenderIdentityFallbacks() {
        assertEquals("Channel", threadHeaderDisplayTitle("123e4567-e89b-12d3-a456-426614174000"))
        assertEquals("Channel", threadHeaderDisplayTitle("deadbeef"))
        assertEquals("Direct Message", threadHeaderDisplayTitle("123e4567-e89b-12d3-a456-426614174000", RaftThreadSurfaceKind.DirectMessage))
    }

    @Test
    fun canonicalConversationContextDoesNotRenderIdentityAsTitle() {
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        val context =
            SharedThreadHeaderContextStore.conversationContext(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                conversation =
                    ConversationSummary(
                        threadId = ThreadId(uuid),
                        channelId = ChannelId(uuid),
                        kind = ConversationKind.Channel,
                        name = uuid
                    ),
                originMessage = null,
                parentMessageId = null
            )

        assertEquals("", context.title)
        assertEquals("Channel", threadHeaderDisplayTitle(context.title, RaftThreadSurfaceKind.Channel))
    }

    @Test
    fun canonicalConversationContextUsesNormalizedChannelTitle() {
        val context =
            SharedThreadHeaderContextStore.conversationContext(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                conversation =
                    ConversationSummary(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        kind = ConversationKind.Channel,
                        name = "#对话流专修"
                    ),
                originMessage = null,
                parentMessageId = null
            )

        assertEquals("对话流专修", context.title)
    }

    @Test
    fun replyConversationContextKeepsOriginLoadingWhileHydrationIsPending() {
        val context =
            SharedThreadHeaderContextStore.conversationContext(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                conversation =
                    ConversationSummary(
                        threadId = ThreadId("thread-1"),
                        channelId = ChannelId("parent-channel-1"),
                        kind = ConversationKind.Thread,
                        name = "Reply thread",
                        thread =
                            ThreadConversation(
                                parentChannelId = ChannelId("parent-channel-1"),
                                parentMessageId = MessageId("message-1"),
                                streamChannelId = ChannelId("thread-1")
                            )
                    ),
                originMessage = null,
                parentMessageId = "message-1",
                originPending = true
            )

        assertEquals("message-1", context.originParentMessageId)
        assertEquals("loading", context.originStatus)
    }

    @Test
    fun replyConversationContextRendersDmParentPeerAsAtLabel() {
        // task #399: a reply thread hosted in a DM subtitles the header with
        // the peer's @-name — never a # channel tag.
        val context =
            SharedThreadHeaderContextStore.conversationContext(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                conversation =
                    ConversationSummary(
                        threadId = ThreadId("dm-thread-1"),
                        channelId = ChannelId("dm-1"),
                        kind = ConversationKind.Thread,
                        name = "Jiacheng",
                        thread =
                            ThreadConversation(
                                parentChannelId = ChannelId("dm-1"),
                                parentMessageId = MessageId("message-1"),
                                streamChannelId = ChannelId("dm-thread-1"),
                                parentSurfaceKind = RaftThreadSurfaceKind.DirectMessage,
                                parentChannelKind = "dm"
                            )
                    ),
                originMessage = null,
                parentMessageId = "message-1"
            )

        assertEquals("@Jiacheng", context.subtitle)
    }

    @Test
    fun replyConversationContextStillMarksOriginMissingWhenNoHydrationIsPending() {
        val context =
            SharedThreadHeaderContextStore.conversationContext(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                conversation =
                    ConversationSummary(
                        threadId = ThreadId("thread-1"),
                        channelId = ChannelId("parent-channel-1"),
                        kind = ConversationKind.Thread,
                        name = "Reply thread",
                        thread =
                            ThreadConversation(
                                parentChannelId = ChannelId("parent-channel-1"),
                                parentMessageId = MessageId("message-1"),
                                streamChannelId = ChannelId("thread-1")
                            )
                    ),
                originMessage = null,
                parentMessageId = "message-1"
            )

        assertEquals("message-1", context.originParentMessageId)
        assertEquals("missing", context.originStatus)
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
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "Parent message preview",
                parentMessageSenderId = "user-1",
                parentMessageSenderKind = MemberKind.Human,
                latestActivityMessageId = MessageId("reply-message-1"),
                latestActivityPreview = "Latest reply"
            ).toHomeMessageOpenRequestForTest()
        val conversation = request.conversationIdentity

        val payload =
            requireNotNull(buildAppShellThreadPayload(
                conversation = conversation,
                anchorMessageId = request.anchorMessageId,
                originFallback = request.originFallback
            ))

        assertEquals("reply-message-1", request.anchorMessageId)
        assertEquals(threadIdentity(ChannelId("channel-1"), MessageId("message-1")), conversation.threadId)
        assertEquals(ConversationKind.Thread, conversation.kind)
        assertEquals("", payload.title)
        assertEquals(null, payload.subtitle)
        assertEquals(RaftThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("reply-message-1", payload.focusedMessageId)
        assertEquals("thread-channel-1", payload.sourceThreadId)
        assertEquals("thread-channel-1", payload.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("message-1", payload.parentMessageId)
        assertEquals("message-1", payload.originParentMessageId)
        assertEquals("missing", payload.originStatus)
        assertEquals("Parent message preview", payload.originPreview)
        assertEquals(null, payload.originMessage)
        assertEquals(false, payload.showModeTabs)
        assertEquals(false, payload.showMembersAction)
        assertEquals(false, payload.showSettingsAction)
        assertEquals(true, payload.showOpenInChannelAction)
    }

    @Test
    fun appShellThreadOriginFallbackIsCarriedOutsideConversationSummary() {
        val request =
            InboxItem(
                key = "thread:message-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("channel-1"),
                channelName = "thread-stream-name",
                threadChannelId = ChannelId("thread-channel-1"),
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "Parent message preview",
                parentMessageSenderId = "user-1",
                parentMessageSenderKind = MemberKind.Human,
                latestActivityMessageId = MessageId("reply-message-1")
            ).toHomeMessageOpenRequestForTest()

        val payload =
            requireNotNull(buildAppShellThreadPayload(
                conversation = request.conversationIdentity,
                anchorMessageId = request.anchorMessageId,
                originFallback = request.originFallback
            ))

        assertEquals("Parent message preview", request.originFallback?.preview)
        assertEquals("user-1", request.originFallback?.senderId)
        assertEquals(MemberKind.Human, request.originFallback?.senderKind)
        assertEquals("channel-1:message-1", request.conversationIdentity.resolveThreadRouteIdentity().parentKey)
        assertEquals("message-1", payload.originParentMessageId)
        assertEquals("missing", payload.originStatus)
        assertEquals("Parent message preview", payload.originPreview)
    }

    @Test
    fun appShellThreadOriginFallbackMustMatchParentIdentity() {
        val request =
            InboxItem(
                key = "thread:message-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("channel-1"),
                channelName = "thread-stream-name",
                threadChannelId = ChannelId("thread-channel-1"),
                parentChannelId = ChannelId("channel-1"),
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "Parent message preview",
                latestActivityMessageId = MessageId("reply-message-1")
            ).toHomeMessageOpenRequestForTest()
        val wrongFallback =
            ThreadLaunchOriginFallback(
                parentChannelId = "channel-2",
                parentMessageId = "message-1",
                preview = "Wrong parent preview",
                senderId = null,
                senderKind = null
            )

        val payload =
            requireNotNull(buildAppShellThreadPayload(
                conversation = request.conversationIdentity,
                anchorMessageId = request.anchorMessageId,
                originFallback = wrongFallback
            ))

        assertEquals("message-1", payload.originParentMessageId)
        assertEquals("missing", payload.originStatus)
        assertEquals(null, payload.originPreview)
    }

    @Test
    fun contentSliceUsesPatchHeaderContextInsteadOfSeededReplyOrigin() {
        val origin =
            RaftThreadMessageItem(
                id = "message-1",
                authorId = "user-1",
                authorName = "Artin",
                body = "Parent message preview"
            )
        val reply =
            RaftThreadMessageItem(
                id = "reply-message-1",
                authorId = "agent-1",
                authorName = "Android-Developer-4",
                body = "Latest reply"
            )
        val current =
            RaftThreadScreenPayload(
                instanceId = "shared-shell-thread-channel-1-message-1",
                title = ThreadReplyPageTitle,
                subtitle = "#seeded",
                status = "loading",
                sourceThreadId = "thread-channel-1",
                streamChannelId = "thread-channel-1",
                parentChannelId = "channel-1",
                parentMessageId = "message-1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
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

        val payload = current.withContentSliceFrom(patch)

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
    fun contentSliceDoesNotReplaceThreadChromeWhenPatchChangesRoute() {
        val current =
            RaftThreadScreenPayload(
                instanceId = "shared-shell-thread-reply-stream-1",
                title = ThreadReplyPageTitle,
                subtitle = "#general",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
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
                dmPeerName = "Current peer",
                dmPeerAvatarUrl = "pixel:current",
                dmPeerAvatarLabel = "CP",
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "reply-1",
                            authorId = "agent-1",
                            authorName = "Agent",
                            body = "reply"
                        )
                    ),
                originStatus = "loaded",
                originMessage =
                    RaftThreadMessageItem(
                        id = "parent-message-1",
                        authorId = "user-1",
                        authorName = "Artin",
                        body = "parent"
                    )
            )
        val patch =
            RaftThreadScreenPayload(
                instanceId = "shared-shell-thread-channel-1",
                title = "general",
                subtitle = null,
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
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
                dmPeerName = "Wrong peer",
                dmPeerAvatarUrl = "pixel:wrong",
                dmPeerAvatarLabel = "WP",
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "channel-message-1",
                            authorId = "user-1",
                            authorName = "Artin",
                            body = "channel message"
                        )
                    ),
                originStatus = "none",
                originMessage = null
            )

        val payload = current.withContentSliceFrom(patch)

        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals("#general", payload.subtitle)
        assertEquals(RaftThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("channel", payload.channelKind)
        assertEquals("reply-stream-1", payload.sourceThreadId)
        assertEquals("reply-stream-1", payload.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("parent-message-1", payload.parentMessageId)
        assertEquals(null, payload.originParentMessageId)
        assertEquals(false, payload.showModeTabs)
        assertEquals(false, payload.showReplyActions)
        assertEquals(false, payload.showMembersAction)
        assertEquals(false, payload.showSettingsAction)
        assertEquals(true, payload.showOpenInChannelAction)
        assertEquals("Current peer", payload.dmPeerName)
        assertEquals("pixel:current", payload.dmPeerAvatarUrl)
        assertEquals("CP", payload.dmPeerAvatarLabel)
        assertEquals("none", payload.originStatus)
        assertEquals(null, payload.originMessage)
        assertEquals(listOf("channel-message-1"), payload.messages.map { it.id })
    }

    @Test
    fun refreshPatchPreservesLoadedOriginWhenNetworkSnapshotOnlyProvesSameParent() {
        val origin =
            RaftThreadMessageItem(
                id = "message-1",
                authorId = "user-1",
                authorName = "Artin",
                body = "Original task body"
            )
        val previous =
            RaftThreadScreenPayload(
                instanceId = "shared-shell-thread-thread-channel-1",
                title = ThreadReplyPageTitle,
                subtitle = "#general",
                status = "content",
                sourceThreadId = "thread-channel-1",
                streamChannelId = "thread-channel-1",
                parentChannelId = "channel-1",
                parentMessageId = "message-1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                originParentMessageId = "message-1",
                originMessage = origin,
                originStatus = "loaded",
                messages =
                    listOf(
                        RaftThreadMessageItem(
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
                        RaftThreadMessageItem(
                            id = "reply-message-1",
                            authorId = "agent-1",
                            authorName = "Android-Developer-4",
                            body = "Reply body"
                        ),
                        RaftThreadMessageItem(
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
    fun refreshPatchPreservesResolvedLinkedTaskForVisibleTaskMessage() {
        val previous =
            RaftThreadScreenPayload(
                instanceId = "thread-channel-1",
                status = "content",
                sourceThreadId = "thread-channel-1",
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "message-240",
                            authorId = "user-artin",
                            authorName = "Artin",
                            body = "ship task",
                            taskId = "task-240",
                            taskNumber = 240,
                            taskStatus = "InReview",
                            taskAssigneeId = "agent-android",
                            taskAssigneeName = "Android Developer"
                        )
                    ),
                tasks =
                    listOf(
                        RaftThreadTaskItem(
                            id = "task-240",
                            title = "ship task",
                            status = "InReview",
                            taskNumber = 240,
                            messageId = "message-240",
                            assigneeId = "agent-android",
                            assigneeName = "Android Developer"
                        )
                    )
            )
        val refreshPatch =
            previous.copy(
                messages =
                    listOf(
                        previous.messages.first().copy(
                            taskStatus = null,
                            taskAssigneeName = "agent-android"
                        )
                    ),
                tasks = emptyList()
            )

        val payload = refreshPatch.preserveStableTimelineFrom(previous)

        assertEquals(listOf("task-240"), payload.tasks.map { it.id })
        val retainedTask = payload.tasks.single()
        val linkedTasks =
            listOf(
                TaskSummary(
                    id = TaskId(retainedTask.id),
                    title = retainedTask.title,
                    status = TaskStatus.InReview,
                    taskNumber = retainedTask.taskNumber,
                    messageId = MessageId(retainedTask.messageId ?: ""),
                    assignee =
                        MemberProfile(
                            id = MemberId(retainedTask.assigneeId ?: ""),
                            displayName = retainedTask.assigneeName ?: "",
                            handle = retainedTask.assigneeName ?: "",
                            kind = MemberKind.Agent
                        ),
                    updatedAtLabel = retainedTask.updatedAtLabel
                )
            ).threadLinkedTasksByMessageId()
        val linkedMessage = payload.messages.first().withLinkedTask(linkedTasks["message-240"])
        assertEquals("in_review", linkedMessage.taskStatus)
        assertEquals("Android Developer", linkedMessage.taskAssigneeName)
        assertEquals("#240 @Android Developer", threadMessageTaskMetaLabel(linkedMessage, linkedMessage.taskAssigneeName))
    }

    @Test
    fun primaryRefreshRetainsAcceptedSavedAndTaskFactsUntilAuxiliaryAuthoritiesCommit() {
        val acceptedMessage =
            RaftThreadMessageItem(
                id = "message-240",
                authorId = "user-artin",
                authorName = "Artin",
                body = "ship task",
                isSaved = true,
                taskId = "task-240",
                taskNumber = 240,
                taskStatus = "InReview",
                taskAssigneeKind = "agent",
                taskAssigneeId = "agent-android",
                taskAssigneeName = "Android Developer"
            )
        val previous =
            RaftThreadScreenPayload(
                instanceId = "thread-channel-1",
                status = "content",
                sourceThreadId = "thread-channel-1",
                messages = listOf(acceptedMessage)
            )
        val primaryRefresh =
            previous.copy(
                messages =
                    listOf(
                        acceptedMessage.copy(
                            isSaved = false,
                            taskId = null,
                            taskNumber = null,
                            taskStatus = null,
                            taskAssigneeKind = null,
                            taskAssigneeId = null,
                            taskAssigneeName = null
                        )
                    )
            )

        val retained =
            primaryRefresh.preserveStableTimelineFrom(
                previous = previous,
                preserveAuxiliaryProjectionFromPrevious = true
            )

        assertTrue(retained.messages.single().isSaved)
        assertEquals("task-240", retained.messages.single().taskId)
        assertEquals("InReview", retained.messages.single().taskStatus)
        assertEquals("Android Developer", retained.messages.single().taskAssigneeName)

        val savedAuthorityCommitted = retained.withSavedMessageIds(emptySet())
        assertFalse(savedAuthorityCommitted.messages.single().isSaved)

        val authoritativeTask =
            TaskSummary(
                id = TaskId("task-240"),
                title = "ship task",
                status = TaskStatus.Done,
                taskNumber = 240,
                messageId = MessageId("message-240"),
                updatedAtLabel = "now"
            )
        val taskAuthorityCommitted =
            savedAuthorityCommitted
                .toThreadTimelineSlice()
                .withThreadLinkedTasksProjection(
                    ThreadLinkedTasksProjection(
                        authoritative = true,
                        lookup = listOf(authoritativeTask).threadLinkedTasksByMessageLookup()
                    )
                )

        assertEquals("done", taskAuthorityCommitted.messages.single().taskStatus)
    }

    @Test
    fun refreshPatchDropsResolvedLinkedTaskWhenVisibleMessagesNoLongerReferenceIt() {
        val previous =
            RaftThreadScreenPayload(
                instanceId = "thread-channel-1",
                status = "content",
                sourceThreadId = "thread-channel-1",
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "message-240",
                            authorId = "user-artin",
                            authorName = "Artin",
                            body = "ship task",
                            taskId = "task-240",
                            taskNumber = 240
                        )
                    ),
                tasks =
                    listOf(
                        RaftThreadTaskItem(
                            id = "task-240",
                            title = "ship task",
                            status = "InReview",
                            taskNumber = 240,
                            messageId = "message-240",
                            assigneeId = "agent-android",
                            assigneeName = "Android Developer"
                        )
                    )
            )
        val refreshPatch =
            previous.copy(
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "message-241",
                            authorId = "user-artin",
                            authorName = "Artin",
                            body = "plain message"
                        )
                    ),
                tasks = emptyList()
            )

        val payload = refreshPatch.preserveStableTimelineFrom(previous)

        assertEquals(emptyList(), payload.tasks)
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
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("message-1"),
                latestActivityPreview = "Latest reply"
            ).toHomeMessageOpenRequestForTest()

        val payload = requireNotNull(
            buildAppShellThreadPayload(request.conversationIdentity, anchorMessageId = request.anchorMessageId)
        )

        assertEquals(RaftThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("message-1", payload.originParentMessageId)
        assertEquals("missing", payload.originStatus)
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
            ).toHomeMessageOpenRequestForTest()
        val conversation = request.conversationIdentity

        val payload = requireNotNull(buildAppShellThreadPayload(conversation, anchorMessageId = request.anchorMessageId))

        assertEquals(threadIdentity(ChannelId("channel-1"), MessageId("message-1")), conversation.threadId)
        assertEquals(ConversationKind.Thread, conversation.kind)
        assertEquals("", payload.title)
        assertEquals(null, payload.subtitle)
        assertEquals(RaftThreadSurfaceKind.ReplyThread, payload.surfaceKind)
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
            ).toHomeMessageOpenRequestForTest()
        val conversation = request.conversationIdentity

        val payload = requireNotNull(buildAppShellThreadPayload(conversation, anchorMessageId = request.anchorMessageId))

        assertEquals(ThreadId("channel-1"), conversation.threadId)
        assertEquals(ConversationKind.Channel, conversation.kind)
        assertEquals("", payload.title)
        assertEquals("Channel", threadHeaderDisplayTitle(payload.title, payload.surfaceKind))
        assertEquals(RaftThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals(null, payload.originParentMessageId)
        assertEquals("latest-channel-message", payload.focusedMessageId)
        assertEquals(true, payload.showModeTabs)
        assertEquals(true, payload.showMembersAction)
        assertEquals(true, payload.showSettingsAction)
    }

    @Test
    fun sharedShellThreadRouteOmitsSubtitleWhenChannelDescriptionIsMissing() {
        val payload =
            buildAppShellThreadPayloadFromSummary(
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

        assertEquals("", payload.title)
        assertEquals("Channel", threadHeaderDisplayTitle(payload.title, payload.surfaceKind))
        assertEquals(null, payload.subtitle)
    }

    @Test
    fun standaloneConversationThreadLaunchCarriesOnlyIdentityAndIntent() {
        val launch =
            buildStandaloneConversationThreadLaunchPayloadFromSummary(
                ConversationSummary(
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    channelType = "private",
                    kind = ConversationKind.Channel,
                    name = "release-room",
                    subtitle = "Product state must not be in PagerData",
                    joined = false,
                    memberCount = 12,
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
        assertEquals(RaftThreadSurfaceKind.Channel, launch.surfaceKind)
        assertEquals(null, launch.threadId)
        assertEquals("channel-1", launch.sourceThreadId)
        assertEquals("channel-1", launch.streamChannelId)
        assertEquals(null, launch.parentChannelId)
        assertEquals(null, launch.parentMessageId)
        assertEquals("message-focus", launch.focusedMessageId)
        assertTrue(launch.focusedMessageRequestNonce > 0)
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
        assertEquals("", initialPayload.title)
        assertEquals(RaftThreadSurfaceKind.Channel, initialPayload.surfaceKind)
        assertEquals("channel-1", initialPayload.sourceThreadId)
        assertEquals("message-focus", initialPayload.focusedMessageId)
        assertEquals(launch.focusedMessageRequestNonce, initialPayload.focusedMessageRequestNonce)
        assertEquals(emptyList<RaftThreadMessageItem>(), initialPayload.messages)
        assertEquals(emptyList<RaftThreadTaskItem>(), initialPayload.tasks)
        assertEquals(emptyList(), initialPayload.settingsActions)
        assertEquals(0, initialPayload.membersCount)
        assertEquals(true, initialPayload.joined)
        assertEquals(true, initialPayload.canSend)
        assertEquals(null, initialPayload.dmPeerName)
        assertEquals(null, initialPayload.dmPeerAvatarUrl)

        val composer = initialPayload.resolveThreadComposer()
        assertEquals(RaftThreadSurfaceKind.Channel, composer.surfaceKind)
        assertEquals("channel-1", composer.streamChannelId)
        assertEquals(null, composer.mentionChannelId)
        assertEquals(null, composer.parentMessageId)
        assertEquals(true, composer.showAsTaskToggle)
    }

    @Test
    fun standaloneFocusedChannelLaunchesUseFreshFocusIntentNonce() {
        val conversation =
            ConversationSummary(
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                kind = ConversationKind.Channel,
                name = "visual-testing"
            )

        val first =
            buildStandaloneConversationThreadLaunchPayloadFromSummary(
                conversation,
                timeContext = null,
                anchorMessageId = "message-focus"
            )
        val second =
            buildStandaloneConversationThreadLaunchPayloadFromSummary(
                conversation,
                timeContext = null,
                anchorMessageId = "message-focus"
            )
        val plain =
            buildStandaloneConversationThreadLaunchPayloadFromSummary(
                conversation,
                timeContext = null
            )

        assertEquals("message-focus", first.focusedMessageId)
        assertEquals("message-focus", second.focusedMessageId)
        assertTrue(first.focusedMessageRequestNonce > 0)
        assertTrue(second.focusedMessageRequestNonce > first.focusedMessageRequestNonce)
        assertEquals(0, plain.focusedMessageRequestNonce)
    }

    @Test
    fun standaloneChannelLaunchIgnoresReplyThreadMetadataOnSummary() {
        val conversation =
            ConversationSummary(
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                channelType = "channel",
                kind = ConversationKind.Channel,
                name = "general",
                thread =
                    ThreadConversation(
                        parentChannelId = ChannelId("channel-1"),
                        parentMessageId = MessageId("parent-1"),
                        streamChannelId = ChannelId("reply-thread-1")
                    )
            )
        val launch =
            buildStandaloneConversationThreadLaunchPayloadFromSummary(
                conversation,
                timeContext = null
            )
        val routeIdentity = conversation.resolveThreadRouteIdentity()

        assertEquals(RaftThreadSurfaceKind.Channel, routeIdentity.surfaceKind)
        assertEquals("channel-1", routeIdentity.timelineChannelId)
        assertEquals("channel:channel-1", routeIdentity.instanceKey)
        assertEquals(RaftThreadSurfaceKind.Channel, launch.surfaceKind)
        assertEquals(null, launch.threadId)
        assertEquals("channel-1", launch.sourceThreadId)
        assertEquals("channel-1", launch.streamChannelId)
        assertEquals(null, launch.parentChannelId)
        assertEquals(null, launch.parentMessageId)
        assertEquals(null, launch.originParentMessageId)

        val initialPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(launch),
                instanceId = "fallback"
            )

        assertEquals(RaftThreadSurfaceKind.Channel, initialPayload.surfaceKind)
        assertEquals("channel-1", initialPayload.sourceThreadId)
        assertEquals("channel-1", initialPayload.streamChannelId)
        assertEquals(null, initialPayload.parentChannelId)
        assertEquals(null, initialPayload.parentMessageId)
        assertEquals(null, initialPayload.originParentMessageId)

        val appShellPayload = buildAppShellThreadPayloadFromSummary(conversation)
        val appShellComposer = appShellPayload.resolveThreadComposer()
        assertEquals(RaftThreadSurfaceKind.Channel, appShellPayload.surfaceKind)
        assertEquals("channel-1", appShellPayload.sourceThreadId)
        assertEquals("channel-1", appShellPayload.streamChannelId)
        assertEquals(null, appShellPayload.parentChannelId)
        assertEquals(null, appShellPayload.parentMessageId)
        assertEquals(null, appShellPayload.originParentMessageId)
        assertEquals(RaftThreadSurfaceKind.Channel, appShellComposer.surfaceKind)
        assertEquals("channel-1", appShellComposer.streamChannelId)
        assertEquals(null, appShellComposer.mentionChannelId)
        assertEquals(null, appShellComposer.parentMessageId)
    }

    @Test
    fun standaloneUnresolvedReplyLaunchCarriesOnlyParentPairUntilResolved() {
        val launch =
            buildStandaloneConversationThreadLaunchPayloadFromSummary(
                ConversationSummary(
                    threadId = ThreadId("thread-summary-id"),
                    channelId = null,
                    channelType = "channel",
                    kind = ConversationKind.Thread,
                    name = "parent reply",
                    thread =
                        ThreadConversation(
                            parentChannelId = ChannelId("channel-1"),
                            parentMessageId = MessageId("m1"),
                            streamChannelId = null
                        )
                ),
                timeContext = null
            )

        assertEquals("shared-shell-thread-thread-summary-id", launch.instanceId)
        assertEquals(RaftThreadSurfaceKind.ReplyThread, launch.surfaceKind)
        assertEquals(null, launch.threadId)
        assertEquals(null, launch.sourceThreadId)
        assertEquals(null, launch.streamChannelId)
        assertEquals("channel-1", launch.parentChannelId)
        assertEquals("m1", launch.parentMessageId)
        assertEquals("m1", launch.originParentMessageId)

        val initialPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(launch),
                instanceId = "fallback"
            )

        assertEquals(RaftThreadSurfaceKind.ReplyThread, initialPayload.surfaceKind)
        assertEquals(null, initialPayload.sourceThreadId)
        assertEquals(null, initialPayload.streamChannelId)
        assertEquals("channel-1", initialPayload.parentChannelId)
        assertEquals("m1", initialPayload.parentMessageId)
        assertEquals(null, initialPayload.resolveThreadRouteIdentity().timelineKey)
    }

    @Test
    fun standaloneThreadRoutePageDataCarriesHostPageName() {
        val launch =
            WireThreadLaunchPayload(
                instanceId = "shared-shell-thread-channel-1",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                selectedTab = "chat",
            )

        val pageData = threadLaunchPageData(launch)
        val parsed =
            parseThreadScreenPayload(
                payload = pageData.optString("payload"),
                instanceId = pageData.optString("instanceId")
            )

        assertEquals(THREAD_PAGE, pageData.optString("pageName"))
        assertEquals("shared-shell-thread-channel-1", pageData.optString("instanceId"))
        assertEquals("shared-shell-thread-channel-1", parsed.instanceId)
        assertEquals("channel-1", parsed.sourceThreadId)
        assertEquals("channel-1", parsed.streamChannelId)
    }

    @Test
    fun standaloneThreadRoutePageDataPayloadIsIdentityOnly() {
        val launch =
            WireThreadLaunchPayload(
                instanceId = "shared-shell-thread-channel-1",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                selectedTab = "chat"
            )

        val encodedPayload = threadLaunchPageData(launch).optString("payload")

        listOf(
            "\"messages\"",
            "\"tasks\"",
            "\"members\"",
            "\"memberCandidates\"",
            "\"filesPayload\"",
            "\"settingsActions\""
        ).forEach { forbidden ->
            assertFalse(encodedPayload.contains(forbidden), "$forbidden must stay outside compose_thread pageData")
        }
    }

    @Test
    fun standaloneActivityThreadLaunchShowsOpenInConversationAction() {
        val request =
            InboxItem(
                key = "thread:message-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-channel-1"),
                channelName = "thread-stream-name",
                threadChannelId = ChannelId("thread-channel-1"),
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "Parent message preview",
                latestActivityMessageId = MessageId("reply-message-1"),
                latestActivityPreview = "Latest reply"
            ).toHomeMessageOpenRequestForTest()

        val launch =
            requireNotNull(buildStandaloneConversationThreadLaunchPayload(
                conversation = request.conversationIdentity,
                timeContext = AppShellThreadTimeContext(localOffsetMinutes = 480),
                anchorMessageId = request.anchorMessageId,
                originFallback = request.originFallback
            ))
        val initialPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(launch),
                instanceId = "fallback"
            )

        assertEquals(RaftThreadSurfaceKind.ReplyThread, launch.surfaceKind)
        assertEquals("thread-channel-1", launch.threadId)
        assertEquals("thread-channel-1", launch.streamChannelId)
        assertEquals("channel-1", launch.parentChannelId)
        assertEquals("message-1", launch.parentMessageId)
        assertEquals(RaftThreadSurfaceKind.Channel, launch.parentSurfaceKind)
        assertEquals("message-1", launch.originParentMessageId)
        assertEquals("message-1", launch.originFallbackParentMessageId)
        assertEquals("Parent message preview", launch.originFallbackPreview)
        assertEquals("reply-message-1", launch.focusedMessageId)
        assertEquals(RaftThreadSurfaceKind.ReplyThread, initialPayload.surfaceKind)
        assertEquals(RaftThreadSurfaceKind.Channel, initialPayload.parentSurfaceKind)
        assertEquals("message-1", initialPayload.originParentMessageId)
        assertEquals("missing", initialPayload.originStatus)
        assertEquals("Parent message preview", initialPayload.originPreview)
        assertEquals("reply-message-1", initialPayload.focusedMessageId)
        assertEquals(false, initialPayload.showModeTabs)
        assertEquals(false, initialPayload.showMembersAction)
        assertEquals(false, initialPayload.showSettingsAction)
        assertEquals(true, initialPayload.showOpenInChannelAction)
    }

    @Test
    fun standaloneSavedDmThreadLaunchKeepsDirectMessageParentSurface() {
        val request =
            InboxItem(
                key = "saved:dm-reply-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-dm-parent-1"),
                channelName = "thread-stream-name",
                threadChannelId = ChannelId("thread-dm-parent-1"),
                parentChannelId = ChannelId("dm-agent-1"),
                parentChannelName = "Product-UX-Designer",
                parentChannelKind = ConversationKind.DirectMessage,
                parentMessageId = MessageId("dm-parent-1"),
                parentMessagePreview = "Can you check this?",
                latestActivityMessageId = MessageId("dm-reply-1"),
                latestActivityPreview = "Sure"
            ).toHomeMessageOpenRequestForTest()

        val launch =
            requireNotNull(buildStandaloneConversationThreadLaunchPayload(
                conversation = request.conversationIdentity,
                timeContext = AppShellThreadTimeContext(localOffsetMinutes = 480),
                anchorMessageId = request.anchorMessageId
            ))
        val initialPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(launch),
                instanceId = "fallback"
            )
        val parentPayload = initialPayload.parentMessageContextLoadingPayload("dm-parent-1")

        assertEquals(RaftThreadSurfaceKind.ReplyThread, launch.surfaceKind)
        assertEquals("thread-dm-parent-1", launch.threadId)
        assertEquals("dm-agent-1", launch.parentChannelId)
        assertEquals("dm-parent-1", launch.parentMessageId)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, request.conversationIdentity.thread?.parentSurfaceKind)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, launch.parentSurfaceKind)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, initialPayload.parentSurfaceKind)
        assertEquals(true, initialPayload.showOpenInChannelAction)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, parentPayload.surfaceKind)
        assertEquals("dm-agent-1", parentPayload.sourceThreadId)
        assertEquals("dm-agent-1", parentPayload.streamChannelId)
    }

    @Test
    fun screenPayloadWireLaunchDropsDisplayAndProductState() {
        val launch =
            RaftThreadScreenPayload(
                instanceId = "display-route",
                title = "Display title",
                subtitle = "Display subtitle",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-channel-1",
                streamChannelId = "thread-channel-1",
                parentChannelId = "parent-channel-1",
                parentMessageId = "parent-message-1",
                parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelKind = "channel",
                originParentMessageId = "parent-message-1",
                focusedMessageId = "reply-2",
                messages =
                    listOf(
                        RaftThreadMessageItem(
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
        assertEquals(RaftThreadSurfaceKind.ReplyThread, launch.surfaceKind)
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
        assertEquals("", initialPayload.title)
        assertEquals(emptyList<RaftThreadMessageItem>(), initialPayload.messages)
        assertEquals(emptyList<RaftThreadTaskItem>(), initialPayload.tasks)
        assertEquals(emptyList(), initialPayload.settingsActions)
        assertEquals(0, initialPayload.membersCount)
        assertEquals(true, initialPayload.joined)
        assertEquals(true, initialPayload.canSend)
        assertEquals(null, initialPayload.dmPeerName)
        assertEquals(null, initialPayload.dmPeerAvatarUrl)
    }

    @Test
    fun screenPayloadWireLaunchOrNullRejectsInvalidRouteKinds() {
        assertEquals(
            null,
            RaftThreadScreenPayload(
                instanceId = "invalid-route",
                surfaceKind = "mystery",
                sourceThreadId = "thread-channel-1",
                streamChannelId = "thread-channel-1"
            ).toWireThreadLaunchPayloadOrNull()
        )
    }

    @Test
    fun standaloneReplyThreadLaunchPreservesComposerRoutingIntent() {
        val launch =
            WireThreadLaunchPayload(
                instanceId = "thread-route",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                threadId = "thread-channel-1",
                sourceThreadId = "thread-channel-1",
                streamChannelId = "thread-channel-1",
                parentChannelId = "parent-channel-1",
                parentMessageId = "parent-message-1",
                parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelKind = "channel",
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
        assertEquals(RaftThreadSurfaceKind.ReplyThread, initialPayload.surfaceKind)
        assertEquals("thread-channel-1", initialPayload.sourceThreadId)
        assertEquals("thread-channel-1", initialPayload.streamChannelId)
        assertEquals("parent-channel-1", initialPayload.parentChannelId)
        assertEquals("parent-message-1", initialPayload.parentMessageId)
        assertEquals("parent-message-1", initialPayload.originParentMessageId)
        assertEquals("reply-2", initialPayload.focusedMessageId)
        assertEquals(false, initialPayload.showModeTabs)
        assertEquals(true, initialPayload.showOpenInChannelAction)

        val composer = initialPayload.resolveThreadComposer()
        assertEquals(RaftThreadSurfaceKind.ReplyThread, composer.surfaceKind)
        assertEquals("thread-channel-1", composer.streamChannelId)
        assertEquals("parent-channel-1", composer.mentionChannelId)
        assertEquals("parent-message-1", composer.parentMessageId)
        assertEquals("Reply in thread", composer.placeholder)
        assertEquals(false, composer.showAsTaskToggle)
        assertEquals(false, initialPayload.threadComposerMemberScopedMentions())
        assertEquals(true, initialPayload.threadComposerMemberSuggestionHeadersVisible())
    }

    @Test
    fun standaloneTaskThreadLaunchCarriesOnlyIdentityAndIntent() {
        val launch =
            requireNotNull(
                buildStandaloneTaskThreadLaunchPayload(
                    task = TaskSummary(
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
                    serverId = "server-1",
                    conversationDirectory =
                        listOf(
                            SharedConversationDirectoryProjection(
                                serverId = "server-1",
                                canonicalChannelId = "parent-channel-1",
                                aliases = setOf("parent-channel-1"),
                                conversationKind = ConversationKind.Channel,
                                surfaceKind = RaftThreadSurfaceKind.Channel,
                                channelKind = RaftThreadSurfaceKind.Channel,
                                title = "bug triage"
                            )
                        ).toSharedConversationDirectoryState(),
                    timeContext = AppShellThreadTimeContext(localOffsetMinutes = 480, todayEpochDay = 20593),
                    alignOwnMessagesRight = true
                )
            )

        assertEquals("shared-shell-thread-parent-channel-1:parent-message-1", launch.instanceId)
        assertEquals(RaftThreadSurfaceKind.ReplyThread, launch.surfaceKind)
        assertEquals("thread-channel-1", launch.threadId)
        assertEquals("thread-channel-1", launch.sourceThreadId)
        assertEquals("thread-channel-1", launch.streamChannelId)
        assertEquals("parent-channel-1", launch.parentChannelId)
        assertEquals("parent-message-1", launch.parentMessageId)
        assertEquals("parent-message-1", launch.originParentMessageId)
        assertEquals("message-1", launch.focusedMessageId)
        assertEquals(480, launch.localOffsetMinutes)
        assertEquals(20593, launch.todayEpochDay)
        assertEquals(true, launch.alignOwnMessagesRight)

        val initialPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(launch),
                instanceId = "fallback"
            )

        assertEquals("shared-shell-thread-parent-channel-1:parent-message-1", initialPayload.instanceId)
        assertEquals("loading", initialPayload.status)
        assertEquals("", initialPayload.title)
        assertEquals(RaftThreadSurfaceKind.ReplyThread, initialPayload.surfaceKind)
        assertEquals("thread-channel-1", initialPayload.sourceThreadId)
        assertEquals("parent-channel-1", initialPayload.parentChannelId)
        assertEquals("parent-message-1", initialPayload.parentMessageId)
        assertEquals("message-1", initialPayload.focusedMessageId)
        assertEquals(emptyList<RaftThreadMessageItem>(), initialPayload.messages)
        assertEquals(emptyList<RaftThreadTaskItem>(), initialPayload.tasks)
        assertEquals(emptyList(), initialPayload.settingsActions)
        assertEquals(0, initialPayload.membersCount)
        assertEquals(null, initialPayload.dmPeerName)
        assertEquals(null, initialPayload.dmPeerAvatarUrl)
    }

    @Test
    fun buildsReplyThreadLoadingPayloadWithoutFetchingParentChannel() {
        val parent =
            RaftThreadMessageItem(
                id = "message-1",
                authorId = "u1",
                authorName = "Artin",
                body = "Parent message"
            )
        val payload =
            RaftThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "content",
                messages = listOf(parent)
            ).replyThreadLoadingPayload(
                parentMessageId = parent.id,
                parentChannelId = "channel-1",
                threadChannelId = parent.threadChannelId
            )

        assertEquals("shared-shell-thread-pending-message-1", payload.instanceId)
        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals("#general", payload.subtitle)
        assertEquals("loading", payload.status)
        assertEquals(null, payload.sourceThreadId)
        assertEquals("message-1", payload.originParentMessageId)
        assertEquals(RaftThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("message-1", payload.parentMessageId)
        val composer = payload.resolveThreadComposer()
        assertEquals(RaftThreadSurfaceKind.ReplyThread, composer.surfaceKind)
        assertEquals("channel-1", composer.mentionChannelId)
        assertEquals("message-1", composer.parentMessageId)
        assertEquals("Reply in thread", composer.placeholder)
        assertEquals("none", payload.originStatus)
        assertEquals(false, payload.showModeTabs)
        assertEquals(true, payload.showOpenInChannelAction)
        assertEquals("Loading...", payload.emptyTitle)
    }

    @Test
    fun replyThreadTerminalEmptyProjectionCannotRegressToLoadingPresentation() {
        val terminalEmpty =
            RaftThreadScreenPayload(
                status = "empty",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                emptyTitle = "Loading...",
                emptyDetail = "Stale loading detail"
            ).toThreadTimelineSlice()
        val loading =
            RaftThreadScreenPayload(
                status = "loading",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                emptyTitle = "Loading..."
            ).toThreadTimelineSlice()
        val error =
            RaftThreadScreenPayload(
                status = "error",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                emptyTitle = "Could not load replies",
                emptyDetail = "Try again"
            ).toThreadTimelineSlice()

        assertEquals(ThreadReplyEmptyTitle, terminalEmpty.emptyTitle)
        assertEquals(ThreadReplyEmptyDetail, terminalEmpty.emptyDetail)
        assertEquals("Loading...", loading.emptyTitle)
        assertEquals("Could not load replies", error.emptyTitle)
        assertEquals("Try again", error.emptyDetail)
    }

    @Test
    fun replyThreadLoadingPayloadUsesKnownThreadChannelAsRouteIdentityOnly() {
        val parent =
            RaftThreadMessageItem(
                id = "message-1",
                authorId = "u1",
                authorName = "Artin",
                body = "Parent message",
                threadChannelId = "thread-message-1"
            )
        val payload =
            RaftThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "content",
                messages = listOf(parent)
            ).replyThreadLoadingPayload(
                parentMessageId = parent.id,
                parentChannelId = "channel-1",
                threadChannelId = parent.threadChannelId
            )

        assertEquals("shared-shell-thread-thread-message-1", payload.instanceId)
        assertEquals("thread-message-1", payload.sourceThreadId)
        assertEquals("thread-message-1", payload.streamChannelId)
        assertEquals("thread-message-1", payload.resolveThreadComposer().streamChannelId)
        assertEquals(null, payload.originMessage)

        val launch = payload.toWireThreadLaunchPayload()
        assertEquals("thread-message-1", launch.threadId)
        assertEquals("thread-message-1", launch.sourceThreadId)
        assertEquals("thread-message-1", launch.streamChannelId)
        assertEquals("channel-1", launch.parentChannelId)
        assertEquals("message-1", launch.parentMessageId)
        assertEquals("message-1", launch.originParentMessageId)
    }

    @Test
    fun nestedReplyOpenFromReplyThreadUsesExplicitParentChannelInsteadOfReplyStream() {
        val parent =
            RaftThreadMessageItem(
                id = "task-parent-541",
                authorId = "user_artin",
                authorName = "Artin",
                body = "Task parent",
                threadChannelId = "nested-reply-stream-541",
                taskId = "task-541",
                taskNumber = 541
            )
        val payload =
            RaftThreadScreenPayload(
                instanceId = "shared-shell-thread-reply-stream-541",
                title = ThreadReplyPageTitle,
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "reply-stream-541",
                streamChannelId = "reply-stream-541",
                parentChannelId = "parent-channel",
                parentMessageId = "root-parent-message",
                messages = listOf(parent)
            )
        val timeline = payload.toThreadTimelineSlice()
        val taskParent = timeline.findTaskParentMessage("task-541")
        val loadingPayload =
            payload.replyThreadLoadingPayload(
                parentMessageId = taskParent!!.id,
                parentChannelId = payload.replyParentChannelIdForNestedOpen(),
                threadChannelId = parent.threadChannelId
            )

        assertEquals("parent-channel", payload.replyParentChannelIdForNestedOpen())
        assertEquals("parent-channel", loadingPayload.parentChannelId)
        assertEquals("task-parent-541", loadingPayload.parentMessageId)
        assertEquals("nested-reply-stream-541", loadingPayload.sourceThreadId)
        assertEquals("nested-reply-stream-541", loadingPayload.streamChannelId)
    }

    @Test
    fun replyThreadLoadingPayloadOmitsUnreadableChannelSubtitle() {
        val parent =
            RaftThreadMessageItem(
                id = "message-1",
                authorId = "u1",
                authorName = "Artin",
                body = "Parent message"
            )
        val payload =
            RaftThreadScreenPayload(
                title = "76c3c868",
                sourceThreadId = "channel-1",
                status = "content",
                messages = listOf(parent)
            ).replyThreadLoadingPayload(
                parentMessageId = parent.id,
                parentChannelId = "channel-1",
                threadChannelId = parent.threadChannelId
            )

        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals(null, payload.subtitle)
    }

    @Test
    fun parentMessageContextLoadingPayloadTargetsParentConversation() {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "thread-replies-1",
                title = ThreadReplyPageTitle,
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "reply-stream-1",
                streamChannelId = "reply-stream-1",
                parentChannelId = "channel-1",
                parentMessageId = "parent-message-1",
                originParentMessageId = "parent-message-1",
                showOpenInChannelAction = true
            ).parentMessageContextLoadingPayload("parent-message-1")

        assertEquals("shared-shell-thread-channel-1", payload.instanceId)
        assertEquals("loading", payload.status)
        assertEquals(RaftThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("channel-1", payload.sourceThreadId)
        assertEquals("channel-1", payload.streamChannelId)
        assertEquals(null, payload.parentChannelId)
        assertEquals(null, payload.parentMessageId)
        assertEquals(null, payload.originParentMessageId)
        assertEquals("parent-message-1", payload.focusedMessageId)
        assertEquals(false, payload.showOpenInChannelAction)
        assertEquals(true, payload.showModeTabs)
        assertEquals("Loading...", payload.emptyTitle)
        assertEquals(RaftThreadSurfaceKind.Channel, payload.resolveThreadComposer().surfaceKind)
    }

    @Test
    fun standaloneInboxJointReplyThreadCarriesParentChannelKind() {
        val request =
            InboxItem(
                key = "thread:thread-joint-parent-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-joint-parent-1"),
                channelName = "thread-stream-name",
                threadChannelId = ChannelId("thread-joint-parent-1"),
                parentChannelId = ChannelId("joint-channel-1"),
                parentChannelName = "Partner",
                parentChannelKind = ConversationKind.Joint,
                parentMessageId = MessageId("joint-parent-1"),
                parentMessagePreview = "Can you check this?",
                latestActivityMessageId = MessageId("joint-reply-1"),
                latestActivityPreview = "Sure"
            ).toHomeMessageOpenRequestForTest()

        val launch =
            requireNotNull(buildStandaloneConversationThreadLaunchPayload(
                conversation = request.conversationIdentity,
                timeContext = AppShellThreadTimeContext(localOffsetMinutes = 480),
                anchorMessageId = request.anchorMessageId
            ))
        val initialPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(launch),
                instanceId = "fallback"
            )
        val parentPayload = initialPayload.parentMessageContextLoadingPayload("joint-parent-1")

        assertEquals(RaftThreadSurfaceKind.ReplyThread, launch.surfaceKind)
        assertEquals("joint", request.conversationIdentity.thread?.parentChannelKind)
        assertEquals(RaftThreadSurfaceKind.Channel, launch.parentSurfaceKind)
        assertEquals("joint", launch.parentChannelKind)
        assertEquals("joint", initialPayload.parentChannelKind)
        assertEquals(RaftThreadSurfaceKind.Channel, parentPayload.surfaceKind)
        assertEquals("joint", parentPayload.channelKind)
        assertEquals(RaftLucideIconName.GitBranch, parentPayload.channelKind.threadChannelHeaderIcon())
    }

    @Test
    fun buildsSharedThreadPayloadFromBridgeMessagesResponse() {
        val base =
            RaftThreadScreenPayload(
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
                                    taskAssigneeType = "agent",
                                    taskAssigneeId = "agent-ad2",
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
        assertEquals("👍", payload.messages.first().reactions.first().emoji)
        assertEquals(2, payload.messages.first().reactions.first().count)
        assertEquals(true, payload.messages.first().reactions.first().selected)
        assertEquals("log.txt", payload.messages.first().attachments.first().name)
        assertEquals("https://cdn.example/screen.png", payload.messages.first().attachments[1].thumbnailUrl)
    }

    @Test
    fun threadWireProjectionRejectsUnknownMessageIdentityAndDropsOnlyInvalidTaskSubprojections() {
        val logs = mutableListOf<String>()
        val payload =
            buildSharedThreadPayloadFromMessagesPage(
                basePayload =
                    RaftThreadScreenPayload(
                        sourceThreadId = "channel-1",
                        status = "loading",
                    ),
                session =
                    StoredAuthSession(
                        user = StoredAuthUser(id = "u1", email = "artin@slock.ai"),
                        accessToken = "access",
                        refreshToken = "refresh",
                    ),
                page =
                    ThreadMessagesPage(
                        messages =
                            listOf(
                                WireMessage(
                                    id = "secret-invalid-sender-id",
                                    channelId = "channel-1",
                                    senderType = "future-member",
                                    senderId = "secret-sender-id",
                                    senderName = "Secret Sender",
                                    messageType = "chat",
                                    content = "private sender body",
                                ),
                                WireMessage(
                                    id = "invalid-system-sender",
                                    channelId = "channel-1",
                                    senderType = "system",
                                    senderId = "system",
                                    senderName = "System",
                                    messageType = "chat",
                                    content = "invalid frontend sender",
                                ),
                                WireMessage(
                                    id = "secret-invalid-type-id",
                                    channelId = "channel-1",
                                    senderType = "user",
                                    senderId = "u1",
                                    senderName = "Artin",
                                    messageType = "future-message",
                                    content = "private type body",
                                ),
                                WireMessage(
                                    id = "task-invalid-status",
                                    channelId = "channel-1",
                                    senderType = "user",
                                    senderId = "u1",
                                    senderName = "Artin",
                                    messageType = "chat",
                                    content = "keep message without invalid task",
                                    taskNumber = 1,
                                    taskStatus = "future-status",
                                ),
                                WireMessage(
                                    id = "task-invalid-assignee",
                                    channelId = "channel-1",
                                    senderType = "user",
                                    senderId = "u1",
                                    senderName = "Artin",
                                    messageType = "chat",
                                    content = "keep task without invalid assignee",
                                    taskNumber = 2,
                                    taskStatus = "todo",
                                    taskAssigneeType = "future-member",
                                    taskAssigneeId = "secret-assignee-id",
                                    taskAssigneeName = "Secret Assignee",
                                ),
                                WireMessage(
                                    id = "valid-message",
                                    channelId = "channel-1",
                                    senderType = "agent",
                                    senderId = "agent-1",
                                    senderName = "Agent One",
                                    messageType = "chat",
                                    content = "keep valid message",
                                ),
                            ),
                        canLoadMoreBefore = false,
                        canLoadMoreAfter = false,
                    ),
                diagnostics = logs::add,
            )

        assertEquals(
            listOf("task-invalid-status", "task-invalid-assignee", "valid-message"),
            payload.messages.map { it.id },
        )
        val invalidStatus = payload.messages[0]
        assertNull(invalidStatus.taskId)
        assertNull(invalidStatus.taskStatus)
        val invalidAssignee = payload.messages[1]
        assertEquals("task-invalid-assignee", invalidAssignee.taskId)
        assertEquals("todo", invalidAssignee.taskStatus)
        assertNull(invalidAssignee.taskAssigneeId)
        assertNull(invalidAssignee.taskAssigneeKind)
        assertTrue(logs.any { it == "projection_value_rejected source=thread_wire_sender field=member_kind value=future-member" })
        assertTrue(logs.any { it == "projection_value_rejected source=thread_wire_sender field=member_kind value=system" })
        assertTrue(logs.any { it == "projection_value_rejected source=thread_wire_message field=message_type value=future-message" })
        assertTrue(logs.any { it == "projection_value_rejected source=thread_wire_task field=task_status value=future-status" })
        assertTrue(logs.any { it == "projection_value_rejected source=thread_wire_task_assignee field=member_kind value=future-member" })
        assertTrue(
            logs.none {
                "secret-invalid" in it || "secret-sender-id" in it || "secret-assignee-id" in it ||
                    "private sender body" in it || "Secret Assignee" in it
            },
        )
    }

    @Test
    fun threadItemReconstructionRejectsUnknownAuthorKindWithBoundedDiagnostic() {
        val logs = mutableListOf<String>()

        val message =
            RaftThreadMessageItem(
                id = "secret-message-id",
                authorId = "secret-author-id",
                authorName = "Secret Author",
                authorKind = "future-member",
                messageType = "Chat",
                body = "private body",
            ).toMessage(
                threadId = ThreadId("thread-1"),
                channelId = ChannelId("channel-1"),
                diagnostics = logs::add,
            )

        assertNull(message)
        assertEquals(
            listOf("projection_value_rejected source=thread_item_author field=member_kind value=future-member"),
            logs,
        )
        assertTrue(logs.none { "secret-message-id" in it || "secret-author-id" in it || "private body" in it })
    }

    @Test
    fun taskStatusRoundTripsThroughMessageAndLinkedTaskProjectionForEveryWireValue() {
        val author =
            MemberProfile(
                id = MemberId("user-artin"),
                displayName = "Artin",
                handle = "artin",
                kind = MemberKind.Human,
            )

        TaskStatus.entries.forEachIndexed { index, status ->
            val task =
                TaskSummary(
                    id = TaskId("task-$index"),
                    title = "Task $index",
                    status = status,
                    taskNumber = index + 1,
                    messageId = MessageId("message-$index"),
                    updatedAtLabel = "now",
                )
            val message =
                Message(
                    id = MessageId("message-$index"),
                    threadId = ThreadId("thread-1"),
                    channelId = ChannelId("channel-1"),
                    author = author,
                    timestampLabel = "now",
                    body = "Task $index",
                    task = task,
                )

            val messageItem = message.toRaftThreadMessageItem(currentUserId = null)
            assertEquals(status.toWireValue(), messageItem.taskStatus, status.name)
            assertEquals(
                status,
                messageItem.toMessage(ThreadId("thread-1"), ChannelId("channel-1"))?.task?.status,
                status.name,
            )

            val linkedItem =
                messageItem
                    .copy(taskId = null, taskNumber = null, taskStatus = null)
                    .withLinkedTask(task)
            assertEquals(status.toWireValue(), linkedItem.taskStatus, status.name)
            assertEquals(
                status,
                linkedItem.toMessage(ThreadId("thread-1"), ChannelId("channel-1"))?.task?.status,
                status.name,
            )
        }
    }

    @Test
    fun replyThreadMessagesPageRequiresResolvedThreadMembership() {
        val base =
            RaftThreadScreenPayload(
                instanceId = "reply-thread-m1",
                title = ThreadReplyPageTitle,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
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
                                    id = "parent",
                                    channelId = "channel-1",
                                    senderType = "user",
                                    senderId = "u1",
                                    senderName = "Artin",
                                    messageType = "chat",
                                    content = "parent row",
                                    threadSummary = WireThreadSummary(threadChannelId = "thread-m1")
                                ),
                                WireMessage(
                                    id = "legacy-unscoped",
                                    senderType = "user",
                                    senderId = "u1",
                                    senderName = "Artin",
                                    messageType = "chat",
                                    content = "missing row identity"
                                ),
                                WireMessage(
                                    id = "reply-1",
                                    channelId = "thread-m1",
                                    senderType = "user",
                                    senderId = "u1",
                                    senderName = "Artin",
                                    messageType = "chat",
                                    content = "reply row"
                                )
                            ),
                        canLoadMoreBefore = false,
                        canLoadMoreAfter = false
                    )
            )

        assertEquals("content", payload.status)
        assertEquals(listOf("reply row"), payload.messages.map { it.body })
    }

    @Test
    fun buildsSharedThreadPayloadPreservingActionCardMetadata() {
        val base =
            RaftThreadScreenPayload(
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
                                                ).toWireJsonObject()
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
            RaftThreadScreenPayload(
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
                                                ).toWireJsonObject()
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
                                                ).toWireJsonObject()
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
        assertEquals("Bring the review owners in.", addMembersCard?.actionCardDraftHint())
    }

    @Test
    fun buildsSharedThreadPayloadPreservingIntegrationApproveLoginActionCardMetadata() {
        val base =
            RaftThreadScreenPayload(
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
                                    id = "m-login-card",
                                    channelId = "channel-1",
                                    senderType = "agent",
                                    senderId = "agent-cindy",
                                    senderName = "Cindy",
                                    messageType = "chat",
                                    content = "Approve Hands for @KMP-专家",
                                    createdAt = "2026-07-07T03:00:00Z",
                                    actionMetadata =
                                        WireActionCardMetadata(
                                            kind = "action-card",
                                            state = "prepared",
                                            action =
                                                WireActionCardAction(
                                                    type = "integration:approve_agent_login",
                                                    requestId = "request-1",
                                                    agentId = "agent-1",
                                                    agentName = "KMP-专家",
                                                    clientId = "client-1",
                                                    clientKey = "hands",
                                                    clientName = "Hands",
                                                    scopes = listOf("identity", "profile"),
                                                    draftHint = "KMP-专家 is requesting human approval."
                                                ).toWireJsonObject()
                                        )
                                )
                            ),
                        canLoadMoreBefore = false,
                        canLoadMoreAfter = null
                    )
            )

        val card = actionCardFromMessageMetadata(payload.messages.single().metadata)

        assertEquals("integration:approve_agent_login", card?.type)
        assertEquals("request-1", card?.requestId)
        assertEquals("agent-1", card?.agentId)
        assertEquals("KMP-专家", card?.agentName)
        assertEquals("client-1", card?.clientId)
        assertEquals("hands", card?.clientKey)
        assertEquals("Hands", card?.clientName)
        assertEquals(listOf("identity", "profile"), card?.scopes)
        assertEquals("KMP-专家 is requesting human approval.", card?.draftHint)
        assertEquals(null, card?.actionCardDraftHint())
    }

    @Test
    fun parsesCanonicalAuthorAndTimestampForOwnUserRow() {
        val base =
            RaftThreadScreenPayload(
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
                                    messageType = "chat",
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
            RaftThreadScreenPayload(
                localOffsetMinutes = 480,
                todayEpochDay = raftEpochDayForDate(2026, 5, 20),
                timeFormat = "12h"
            )

        assertEquals("12:00 AM", payload.formatTimestampLabel("2026-05-19T16:00:00Z"))
    }

    @Test
    fun formatsMessageTimestampWhenHostOmitsTodayEpochDay() {
        val payload =
            RaftThreadScreenPayload(
                localOffsetMinutes = 480,
                nowEpochMillis = 1779206400000L
            )

        assertEquals("00:00", payload.formatTimestampLabel("2026-05-19T16:00:00Z"))
    }

    @Test
    fun parsesCanonicalAgentSenderRow() {
        val base =
            RaftThreadScreenPayload(
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
                                    messageType = "chat",
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
            RaftThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "content",
                messages =
                    listOf(
                        RaftThreadMessageItem(
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
                                    messageType = "chat",
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
            RaftThreadScreenPayload(
                instanceId = "thread-1",
                messages =
                    listOf(
                        RaftThreadMessageItem(
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
    }

    @Test
    fun resolvesThreadMentionsFromMembersButTaskFooterUsesLinkedTaskAssignee() {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "thread-1",
                members =
                    listOf(
                        RaftThreadMemberItem(
                            id = "agent_android",
                            displayName = "Android Developer",
                            handle = "Android-Developer",
                            kind = "agent"
                        ),
                        RaftThreadMemberItem(
                            id = "user_artin",
                            displayName = "Artin",
                            handle = "artin",
                            kind = "human"
                        )
                    ),
                messages =
                    listOf(
                        RaftThreadMessageItem(
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
            resolveThreadMarkdownTagDisplayLabel(
                ThreadMarkdownTag("@agent_android", ThreadMarkdownTagKind.UserMention),
                threadMarkdownMentionDisplayLabels(payload.members, emptyList())
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
            RaftThreadMessageItem(
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
                    build.raft.app.data.ScreenLoadState.Content(
                        build.raft.app.data.TasksContent(
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
    fun linkedTasksFallbackToTaskIdAndTaskNumberWhenMessageIdIsMissing() {
        val taskWithoutMessageId =
            TaskSummary(
                id = TaskId("task-240"),
                title = "ship task",
                status = TaskStatus.InReview,
                taskNumber = 240,
                messageId = null,
                assignee =
                    MemberProfile(
                        id = MemberId("agent-android"),
                        displayName = "Android Developer",
                        handle = "Android-Developer",
                        kind = MemberKind.Agent
                    ),
                updatedAtLabel = ""
            )
        val linkedTasks = listOf(taskWithoutMessageId).threadLinkedTasksByMessageLookup()
        val taskIdMessage =
            RaftThreadMessageItem(
                id = "message-240",
                authorId = "user_artin",
                authorName = "Artin",
                body = "ship task",
                taskId = "task-240",
                taskNumber = 240,
                taskStatus = "Todo"
            )
        val taskNumberMessage = taskIdMessage.copy(id = "message-241", taskId = null)

        val byTaskId = taskIdMessage.withLinkedTask(linkedTasks.threadLinkedTaskForMessage(taskIdMessage))
        val byTaskNumber = taskNumberMessage.withLinkedTask(linkedTasks.threadLinkedTaskForMessage(taskNumberMessage))

        assertEquals("in_review", byTaskId.taskStatus)
        assertEquals("Android Developer", byTaskId.taskAssigneeName)
        assertEquals("in_review", byTaskNumber.taskStatus)
        assertEquals("Android Developer", byTaskNumber.taskAssigneeName)
    }

    @Test
    fun threadActionMessageUsesLinkedTaskProjectionForHistoricalTasks() {
        val message =
            RaftThreadMessageItem(
                id = "message-240",
                authorId = "user_artin",
                authorName = "Artin",
                body = "historical task"
            )
        val task =
            TaskSummary(
                id = TaskId("message-240"),
                title = "historical task",
                status = TaskStatus.InReview,
                taskNumber = 240,
                messageId = null,
                assignee = null,
                updatedAtLabel = ""
            )
        val payload = RaftThreadScreenPayload(messages = listOf(message))
        val linkedTasks = listOf(task).threadLinkedTasksByMessageLookup(payload.threadLinkedTaskReferences())

        val actionMessage =
            payload.threadActionMessage(
                "message-240",
                ThreadLinkedTasksProjection(authoritative = true, lookup = linkedTasks)
            )

        assertEquals("message-240", actionMessage?.taskId)
        assertEquals("in_review", actionMessage?.taskStatus)
        assertEquals(240, actionMessage?.taskNumber)
    }

    @Test
    fun threadActionMessageClearsStaleTaskFactsWhenProjectionIsAuthoritativeEmpty() {
        val staleMessage =
            RaftThreadMessageItem(
                id = "message-240",
                authorId = "user_artin",
                authorName = "Artin",
                body = "historical task",
                taskId = "task-240",
                taskNumber = 240,
                taskStatus = "Todo",
                taskAssigneeId = "agent-android",
                taskAssigneeName = "Android Developer"
            )
        val payload = RaftThreadScreenPayload(messages = listOf(staleMessage))

        val actionMessage =
            payload.threadActionMessage(
                "message-240",
                ThreadLinkedTasksProjection(authoritative = true, lookup = emptyMap())
            )

        assertEquals(null, actionMessage?.taskId)
        assertEquals(null, actionMessage?.taskNumber)
        assertEquals(null, actionMessage?.taskStatus)
        assertEquals(null, actionMessage?.taskAssigneeId)
        assertEquals(null, actionMessage?.taskAssigneeName)
        assertEquals("task-240", staleMessage.taskId)
    }

    @Test
    fun findTaskParentMessageUsesLinkedTaskProjectionWithoutProjectedTimeline() {
        val message =
            RaftThreadMessageItem(
                id = "message-240",
                authorId = "user_artin",
                authorName = "Artin",
                body = "historical task"
            )
        val task =
            TaskSummary(
                id = TaskId("task-240"),
                title = "historical task",
                status = TaskStatus.Done,
                taskNumber = 240,
                messageId = MessageId("message-240"),
                assignee = null,
                updatedAtLabel = ""
            )
        val payload = RaftThreadScreenPayload(messages = listOf(message))
        val timeline = payload.toThreadTimelineSlice()
        val projection =
            ThreadLinkedTasksProjection(
                authoritative = true,
                lookup = listOf(task).threadLinkedTasksByMessageLookup(payload.threadLinkedTaskReferences())
            )

        val parent = timeline.findTaskParentMessage("task-240", projection)

        assertEquals("message-240", parent?.id)
        assertEquals(null, timeline.messages.single().taskId)
    }

    @Test
    fun threadTaskStatusChipClickIdentityRebindsByMessageTaskAndStatus() {
        val todoMessage =
            RaftThreadMessageItem(
                id = "message-1",
                authorId = "agent-1",
                authorName = "Agent",
                body = "task one",
                taskId = "task-1",
                taskStatus = "todo"
            )
        val doneMessage = todoMessage.copy(taskStatus = "done")
        val otherMessage = todoMessage.copy(id = "message-2", taskId = "task-2")

        val todoIdentity =
            threadTaskStatusChipClickIdentity(
                message = todoMessage,
                selectedStatus = todoMessage.taskStatus.threadTaskStatusOption(),
                taskStatusActionsEnabled = true,
                taskOpenEnabled = true
            )

        assertNotEquals(
            todoIdentity,
            threadTaskStatusChipClickIdentity(
                message = doneMessage,
                selectedStatus = doneMessage.taskStatus.threadTaskStatusOption(),
                taskStatusActionsEnabled = true,
                taskOpenEnabled = true
            )
        )
        assertNotEquals(
            todoIdentity,
            threadTaskStatusChipClickIdentity(
                message = otherMessage,
                selectedStatus = otherMessage.taskStatus.threadTaskStatusOption(),
                taskStatusActionsEnabled = true,
                taskOpenEnabled = true
            )
        )
        assertNotEquals(
            todoIdentity,
            threadTaskStatusChipClickIdentity(
                message = todoMessage,
                selectedStatus = todoMessage.taskStatus.threadTaskStatusOption(),
                taskStatusActionsEnabled = false,
                taskOpenEnabled = true
            )
        )
    }

    @Test
    fun strippedTimelineActionMessageCanRecoverReplyThreadRouteMetadata() {
        val message =
            RaftThreadMessageItem(
                id = "message-1",
                authorId = "user_artin",
                authorName = "Artin",
                body = "parent",
                threadChannelId = "reply-stream-1",
                replyCount = 3,
                unreadReplyCount = 2,
                firstUnreadReplyMessageId = "reply-2",
                isThreadFollowed = true
            )
        val payload =
            RaftThreadScreenPayload(
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                messages = listOf(message)
            )
        val timeline = payload.toThreadTimelineSlice()
        val strippedActionMessage = timeline.threadActionMessage("message-1")

        val replyMetadata = payload.toThreadReplyMetadataMap().threadReplyMetadataFor(strippedActionMessage!!)
        val loadingPayload =
            payload.replyThreadLoadingPayload(
                parentMessageId = strippedActionMessage.id,
                parentChannelId = payload.sourceThreadId,
                threadChannelId = replyMetadata.threadChannelId,
                focusedMessageId = replyMetadata.firstUnreadReplyMessageId
            )

        assertEquals(null, strippedActionMessage.threadChannelId)
        assertEquals(0, strippedActionMessage.replyCount)
        assertEquals("reply-stream:reply-stream-1", loadingPayload.resolveThreadRouteIdentity().instanceKey)
        assertEquals("reply-stream-1", loadingPayload.streamChannelId)
        assertEquals("reply-2", loadingPayload.focusedMessageId)
    }

    @Test
    fun findMarkdownTaskIdUsesLinkedTaskProjectionWithoutProjectedTimeline() {
        val message =
            RaftThreadMessageItem(
                id = "message-240",
                authorId = "user_artin",
                authorName = "Artin",
                body = "historical task"
            )
        val task =
            TaskSummary(
                id = TaskId("task-240"),
                title = "historical task",
                status = TaskStatus.InProgress,
                taskNumber = 240,
                messageId = MessageId("message-240"),
                assignee = null,
                updatedAtLabel = ""
            )
        val payload = RaftThreadScreenPayload(messages = listOf(message))
        val timeline = payload.toThreadTimelineSlice()
        val projection =
            ThreadLinkedTasksProjection(
                authoritative = true,
                lookup = listOf(task).threadLinkedTasksByMessageLookup(payload.threadLinkedTaskReferences())
            )

        val taskId =
            timeline.findMarkdownTaskId(
                ThreadMarkdownTag("#240", ThreadMarkdownTagKind.Task),
                projection
            )

        assertEquals("task-240", taskId)
        assertEquals(null, timeline.messages.single().taskId)
    }

    @Test
    fun threadTaskStatusUpdateUsesLinkedTaskThroughGlobalStoreIntent() {
        val task =
            TaskSummary(
                id = TaskId("task-240"),
                title = "historical task",
                status = TaskStatus.InProgress,
                taskNumber = 240,
                messageId = MessageId("message-240"),
                assignee = null,
                updatedAtLabel = ""
            )
        val projection =
            ThreadLinkedTasksProjection(
                authoritative = true,
                lookup = listOf(task).threadLinkedTasksByMessageLookup()
            )

        val intent = requireNotNull(projection.statusUpdateIntent("task-240", TaskStatus.Closed))

        assertEquals(task, intent.task)
        assertEquals(TaskStatus.Closed, intent.status)
    }

    @Test
    fun threadTaskIdentityLookupDoesNotConfuseAnotherTasksMessageId() {
        val target =
            TaskSummary(
                id = TaskId("task-target"),
                title = "target",
                status = TaskStatus.InProgress,
                messageId = MessageId("message-target"),
                updatedAtLabel = ""
            )
        val collision =
            TaskSummary(
                id = TaskId("task-other"),
                title = "other",
                status = TaskStatus.Todo,
                messageId = MessageId("task-target"),
                updatedAtLabel = ""
            )
        val projection =
            ThreadLinkedTasksProjection(
                authoritative = true,
                lookup = listOf(target, collision).threadLinkedTasksByMessageLookup()
            )

        assertEquals(target, projection.taskById("task-target"))
    }

    @Test
    fun threadTaskOpenArbitrationOpensCanonicalOnceWhenParentIsOutsideLoadedTimeline() {
        val task =
            TaskSummary(
                id = TaskId("task-outside-window"),
                title = "outside window",
                status = TaskStatus.InProgress,
                updatedAtLabel = ""
            )
        var loadedParentOpens = 0
        var canonicalOpens = 0
        var failClosed = 0

        arbitrateThreadTaskOpen(
            loadedParentAvailable = false,
            loadedParentProvenanceAccepted = false,
            canonicalTask = task
        ).dispatch(
            onUseLoadedParent = { loadedParentOpens += 1 },
            onOpenCanonical = { openedTask, reason ->
                assertEquals(task, openedTask)
                assertEquals(ThreadTaskOpenRecoveryReason.ParentOutsideLoadedWindow, reason)
                canonicalOpens += 1
            },
            onFailClosed = { failClosed += 1 }
        )

        assertEquals(0, loadedParentOpens)
        assertEquals(1, canonicalOpens)
        assertEquals(0, failClosed)
    }

    @Test
    fun threadTaskOpenArbitrationOpensCanonicalOnceWhenLoadedParentProvenanceIsStale() {
        val task =
            TaskSummary(
                id = TaskId("task-stale-parent"),
                title = "stale parent",
                status = TaskStatus.Todo,
                updatedAtLabel = ""
            )
        var loadedParentOpens = 0
        var canonicalOpens = 0
        var failClosed = 0

        arbitrateThreadTaskOpen(
            loadedParentAvailable = true,
            loadedParentProvenanceAccepted = false,
            canonicalTask = task
        ).dispatch(
            onUseLoadedParent = { loadedParentOpens += 1 },
            onOpenCanonical = { openedTask, reason ->
                assertEquals(task, openedTask)
                assertEquals(ThreadTaskOpenRecoveryReason.StaleProvenance, reason)
                canonicalOpens += 1
            },
            onFailClosed = { failClosed += 1 }
        )

        assertEquals(0, loadedParentOpens)
        assertEquals(1, canonicalOpens)
        assertEquals(0, failClosed)
    }

    @Test
    fun threadTaskOpenArbitrationFailsClosedWithoutCanonicalTaskIdentity() {
        var loadedParentOpens = 0
        var canonicalOpens = 0
        var failClosed = 0
        var failReason: ThreadTaskOpenRecoveryReason? = null

        arbitrateThreadTaskOpen(
            loadedParentAvailable = false,
            loadedParentProvenanceAccepted = false,
            canonicalTask = null
        ).dispatch(
            onUseLoadedParent = { loadedParentOpens += 1 },
            onOpenCanonical = { _, _ -> canonicalOpens += 1 },
            onFailClosed = { reason ->
                failClosed += 1
                failReason = reason
            }
        )

        assertEquals(0, loadedParentOpens)
        assertEquals(0, canonicalOpens)
        assertEquals(1, failClosed)
        assertEquals(ThreadTaskOpenRecoveryReason.TaskIdentityUnavailable, failReason)
    }

    @Test
    fun threadTaskStatusUpdateRequiresLinkedTaskProjection() {
        val intent =
            emptyThreadLinkedTasksProjection()
                .statusUpdateIntent("task-missing", TaskStatus.Closed)

        assertEquals(null, intent)
    }

    @Test
    fun taskThreadLaunchPayloadUsesTaskIdentityOutsideLoadedTimeline() {
        val payload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = RaftThreadSurfaceKind.Channel,
                sourceThreadId = "channel-dialog",
                streamChannelId = "channel-dialog",
                alignOwnMessagesRight = true,
                compactConsecutiveMessages = true,
                localOffsetMinutes = 480,
                todayEpochDay = 20260628,
                nowEpochMillis = 1_782_690_000_000,
                timeFormat = RaftTimeFormat12Hour
            )
        val task =
            TaskSummary(
                id = TaskId("task-482"),
                title = "markdown clicks",
                status = TaskStatus.InProgress,
                taskNumber = 482,
                messageId = MessageId("message-482"),
                parentMessageId = MessageId("message-482"),
                channelId = ChannelId("channel-dialog"),
                threadId = ThreadId("thread-482"),
                updatedAtLabel = "now"
            )

        val launchPayload = payload.taskThreadLaunchPayload(task, ServerId("server-1"))

        assertEquals(RaftThreadSurfaceKind.ReplyThread, launchPayload?.surfaceKind)
        assertEquals("channel-dialog", launchPayload?.parentChannelId)
        assertEquals("message-482", launchPayload?.parentMessageId)
        assertEquals("thread-482", launchPayload?.streamChannelId)
        assertEquals("thread-482", launchPayload?.sourceThreadId)
        assertEquals(null, launchPayload?.focusedMessageId)
        assertEquals(true, launchPayload?.alignOwnMessagesRight)
        assertEquals(true, launchPayload?.compactConsecutiveMessages)
        assertEquals(RaftTimeFormat12Hour, launchPayload?.timeFormat)
        assertEquals(emptyList<RaftThreadMessageItem>(), launchPayload?.messages)
        assertEquals(emptyList<RaftThreadMemberItem>(), launchPayload?.members)
        assertEquals("loading", launchPayload?.status)
    }

    @Test
    fun taskThreadLaunchPayloadFocusesTaskReplyWhenDistinctFromOrigin() {
        val payload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = RaftThreadSurfaceKind.Channel,
                sourceThreadId = "channel-dialog",
                streamChannelId = "channel-dialog"
            )
        val task =
            TaskSummary(
                id = TaskId("task-482"),
                title = "markdown clicks",
                status = TaskStatus.InProgress,
                taskNumber = 482,
                messageId = MessageId("reply-message-482"),
                parentMessageId = MessageId("parent-message-482"),
                channelId = ChannelId("channel-dialog"),
                threadId = ThreadId("thread-482"),
                updatedAtLabel = "now"
            )

        val launchPayload = payload.taskThreadLaunchPayload(task, ServerId("server-1"))

        assertEquals("parent-message-482", launchPayload?.parentMessageId)
        assertEquals("parent-message-482", launchPayload?.originParentMessageId)
        assertEquals("reply-message-482", launchPayload?.focusedMessageId)
    }

    @Test
    fun taskThreadLaunchPayloadFailsClosedWhenTypedParentIdentityIsMissing() {
        val payload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = RaftThreadSurfaceKind.Channel,
                sourceThreadId = "channel-dialog",
                streamChannelId = "channel-dialog"
            )
        val task =
            TaskSummary(
                id = TaskId("task-missing-parent"),
                title = "missing parent",
                status = TaskStatus.Todo,
                updatedAtLabel = "now"
            )

        assertEquals(null, payload.taskThreadLaunchPayload(task, ServerId("server-1")))
    }

    @Test
    fun markdownTaskNumberOpenUsesCurrentChannelNumberEndpointBeforeServerFallback() = runBlocking {
        val api =
            FakeMarkdownTaskNumberApi(
                channelTaskBody =
                    """{"task":{"id":"task-541","channelId":"parent-channel","threadId":"reply-stream","messageId":"parent-message","taskNumber":541,"title":"Correct channel task","status":"in_progress"}}""",
                serverTasksBody =
                    """{"tasks":[{"id":"wrong-task-541","channelId":"reply-stream","threadId":"reply-stream","messageId":"parent-message","taskNumber":541,"title":"Wrong stream task","status":"in_progress"}]}"""
            )
        val result =
            openMarkdownTaskNumberPayload(
                serverId = ServerId("server-1"),
                taskNumber = 541,
                currentPayload =
                    RaftThreadScreenPayload(
                        status = "content",
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "channel",
                        sourceThreadId = "parent-channel",
                        streamChannelId = "parent-channel"
                    ),
                currentTaskChannelId = ChannelId("parent-channel"),
                tasksDataSource = SharedTasksDataSource(api),
                tasksDataStore = null
            )

        val found = assertIs<MarkdownTaskNumberOpenResult.Found>(result)
        assertEquals("task-541", found.task.id.value)
        assertEquals("parent-channel", found.payload.parentChannelId)
        assertEquals("parent-message", found.payload.parentMessageId)
        assertEquals("reply-stream", found.payload.streamChannelId)
        assertEquals(listOf("taskByNumber:server-1:parent-channel:541"), api.calls)
    }

    @Test
    fun markdownTaskNumberOpenUsesLoadedServerStoreWithoutNetwork() = runBlocking {
        val store = SharedTasksDataStore()
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot =
                TaskSnapshot(
                    tasks =
                        listOf(
                            TaskSummary(
                                id = TaskId("task-340"),
                                title = "Loaded task",
                                status = TaskStatus.InProgress,
                                taskNumber = 340,
                                channelId = ChannelId("target-channel"),
                                threadId = ThreadId("target-thread"),
                                messageId = MessageId("target-message"),
                                updatedAtLabel = ""
                            )
                        ),
                    summaryCounts = emptyMap()
                )
        )
        val api =
            FakeMarkdownTaskNumberApi(
                channelTaskBody = """{"task":{"id":"network-task"}}""",
                serverTasksBody = """{"tasks":[]}"""
            )
        val targetProjection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "target-channel",
                aliases = setOf("target-channel"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "channel",
                title = "target"
            )
        val conversationIdentityProvider =
            object : SharedConversationIdentityProvider {
                override fun observeServer(serverId: ServerId): Flow<SharedConversationDirectoryState> =
                    flowOf(SharedConversationDirectoryState())

                override fun projectionFlow(
                    serverId: ServerId,
                    ids: Collection<String>
                ): Flow<Map<String, SharedConversationDirectoryProjection>> = flowOf(emptyMap())

                override fun resolveProjection(
                    serverId: ServerId,
                    id: String
                ): SharedConversationDirectoryProjection? =
                    targetProjection.takeIf { serverId.value == "server-1" && id == "target-channel" }
            }

        val result =
            openMarkdownTaskNumberPayload(
                serverId = ServerId("server-1"),
                taskNumber = 340,
                currentPayload =
                    RaftThreadScreenPayload(
                        status = "content",
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "channel",
                        sourceThreadId = "current-channel",
                        streamChannelId = "current-channel"
                    ),
                currentTaskChannelId = ChannelId("current-channel"),
                tasksDataSource = SharedTasksDataSource(api),
                tasksDataStore = store,
                conversationIdentityProvider = conversationIdentityProvider
            )

        val found = assertIs<MarkdownTaskNumberOpenResult.Found>(result)
        assertEquals("task-340", found.task.id.value)
        assertEquals(SharedTaskReferenceResolutionSource.LoadedServer, found.source)
        assertEquals("target-channel", found.payload.parentChannelId)
        assertEquals("target-message", found.payload.parentMessageId)
        assertEquals(emptyList(), api.calls)
    }

    @Test
    fun markdownTaskNumberOpenUsesLoadedExactChannelStoreWithoutNetwork() = runBlocking {
        val store = SharedTasksDataStore()
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("parent-channel")),
            snapshot =
                TaskSnapshot(
                    tasks =
                        listOf(
                            TaskSummary(
                                id = TaskId("task-541"),
                                title = "Loaded channel task",
                                status = TaskStatus.InProgress,
                                taskNumber = 541,
                                channelId = ChannelId("parent-channel"),
                                messageId = MessageId("parent-message"),
                                updatedAtLabel = ""
                            )
                        ),
                    summaryCounts = emptyMap()
                )
        )
        val api =
            FakeMarkdownTaskNumberApi(
                channelTaskBody = """{"task":{"id":"network-task"}}""",
                serverTasksBody = """{"tasks":[]}"""
            )

        val result =
            openMarkdownTaskNumberPayload(
                serverId = ServerId("server-1"),
                taskNumber = 541,
                currentPayload =
                    RaftThreadScreenPayload(
                        status = "content",
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "channel",
                        sourceThreadId = "parent-channel",
                        streamChannelId = "parent-channel"
                    ),
                currentTaskChannelId = ChannelId("parent-channel"),
                tasksDataSource = SharedTasksDataSource(api),
                tasksDataStore = store
            )

        val found = assertIs<MarkdownTaskNumberOpenResult.Found>(result)
        assertEquals("task-541", found.task.id.value)
        assertEquals(SharedTaskReferenceResolutionSource.LoadedChannel, found.source)
        assertEquals(emptyList(), api.calls)
    }

    @Test
    fun markdownTaskNumberOpenShowsLoadedAmbiguousStoreWithoutNetwork() = runBlocking {
        val store = SharedTasksDataStore()
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot =
                TaskSnapshot(
                    tasks =
                        listOf(
                            TaskSummary(
                                id = TaskId("task-a-343"),
                                title = "A",
                                status = TaskStatus.Todo,
                                taskNumber = 343,
                                channelId = ChannelId("channel-a"),
                                messageId = MessageId("message-a"),
                                updatedAtLabel = ""
                            ),
                            TaskSummary(
                                id = TaskId("task-b-343"),
                                title = "B",
                                status = TaskStatus.Done,
                                taskNumber = 343,
                                channelId = ChannelId("channel-b"),
                                messageId = MessageId("message-b"),
                                updatedAtLabel = ""
                            )
                        ),
                    summaryCounts = emptyMap()
                )
        )
        val api =
            FakeMarkdownTaskNumberApi(
                channelTaskBody = """{"task":{"id":"network-task"}}""",
                serverTasksBody = """{"tasks":[]}"""
            )

        val result =
            openMarkdownTaskNumberPayload(
                serverId = ServerId("server-1"),
                taskNumber = 343,
                currentPayload =
                    RaftThreadScreenPayload(
                        status = "content",
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "channel",
                        sourceThreadId = "parent-channel",
                        streamChannelId = "parent-channel"
                    ),
                currentTaskChannelId = ChannelId("current-channel"),
                tasksDataSource = SharedTasksDataSource(api),
                tasksDataStore = store
            )

        val ambiguous = assertIs<MarkdownTaskNumberOpenResult.Ambiguous>(result)
        assertEquals(listOf("task-a-343", "task-b-343"), ambiguous.candidates.map { it.id.value })
        assertEquals(SharedTaskReferenceResolutionSource.LoadedServer, ambiguous.source)
        assertEquals(emptyList(), api.calls)
    }

    @Test
    fun markdownTaskNumberLoadedMissStillAllowsCurrentChannelFallback() = runBlocking {
        val store = SharedTasksDataStore()
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot = TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap())
        )
        val api =
            FakeMarkdownTaskNumberApi(
                channelTaskBody =
                    """{"task":{"id":"task-541","channelId":"parent-channel","threadId":"reply-stream","messageId":"parent-message","taskNumber":541,"title":"Fallback task","status":"in_progress"}}""",
                serverTasksBody = """{"tasks":[]}"""
            )

        val result =
            openMarkdownTaskNumberPayload(
                serverId = ServerId("server-1"),
                taskNumber = 541,
                currentPayload =
                    RaftThreadScreenPayload(
                        status = "content",
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "channel",
                        sourceThreadId = "parent-channel",
                        streamChannelId = "parent-channel"
                    ),
                currentTaskChannelId = ChannelId("parent-channel"),
                tasksDataSource = SharedTasksDataSource(api),
                tasksDataStore = store
            )

        val found = assertIs<MarkdownTaskNumberOpenResult.Found>(result)
        assertEquals(SharedTaskReferenceResolutionSource.NetworkChannel, found.source)
        assertEquals(listOf("taskByNumber:server-1:parent-channel:541"), api.calls)
    }

    @Test
    fun markdownTaskNumberOpenRequestGateRejectsOlderCompletion() {
        val gate = MarkdownTaskNumberOpenRequestGate()
        val older = gate.nextRequest()
        val newer = gate.nextRequest()

        assertNull(gate.accept(older, "old-result"))
        assertEquals("new-result", gate.accept(newer, "new-result"))
    }

    @Test
    fun linkedTaskProjectionIsOnlyAuthoritativeAfterTasksLoadSettles() {
        val references =
            RaftThreadScreenPayload(
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "message-240",
                            authorId = "user_artin",
                            authorName = "Artin",
                            body = "ship task",
                            taskId = "task-240",
                            taskNumber = 240
                        )
                    )
            ).threadLinkedTaskReferences()
        val loadingProjection =
            TasksUiState(
                loadState = build.raft.app.data.ScreenLoadState.Loading
            ).threadLinkedTasksProjection(references)
        val emptyProjection =
            TasksUiState(
                loadState = build.raft.app.data.ScreenLoadState.Empty(build.raft.app.data.EmptyReason.NoTasks)
            ).threadLinkedTasksProjection(references)

        assertEquals(false, loadingProjection.authoritative)
        assertEquals(emptyMap(), loadingProjection.lookup)
        assertEquals(true, emptyProjection.authoritative)
        assertEquals(emptyMap(), emptyProjection.lookup)
    }

    @Test
    fun partialSharedTaskProjectionEnrichesKnownRowsWithoutClearingHistoricalTaskFacts() {
        val recentTask =
            TaskSummary(
                id = TaskId("task-recent"),
                title = "recent task",
                status = TaskStatus.InProgress,
                taskNumber = 626,
                messageId = MessageId("message-recent"),
                updatedAtLabel = ""
            )
        val historicalMessage =
            RaftThreadMessageItem(
                id = "message-history",
                authorId = "user-artin",
                authorName = "Artin",
                body = "historical task",
                taskId = "task-history",
                taskNumber = 625,
                taskStatus = "todo"
            )
        val recentMessage =
            RaftThreadMessageItem(
                id = "message-recent",
                authorId = "user-artin",
                authorName = "Artin",
                body = "recent task"
            )
        val payload = RaftThreadScreenPayload(messages = listOf(historicalMessage, recentMessage))
        val projection =
            SharedTasksProjectionSlice(
                status = SharedTasksDataStoreStatus.Partial,
                tasks = listOf(recentTask)
            ).threadLinkedTasksProjection(payload.threadLinkedTaskReferences())

        val projected = payload.toThreadTimelineSlice().withThreadLinkedTasksProjection(projection)

        assertFalse(projection.authoritative)
        assertEquals("task-history", projected.messages[0].taskId)
        assertEquals(625, projected.messages[0].taskNumber)
        assertEquals("task-recent", projected.messages[1].taskId)
        assertEquals(626, projected.messages[1].taskNumber)
        assertEquals("in_progress", projected.messages[1].taskStatus)
    }

    @Test
    fun linkedTaskProjectionMatchesMessagesByTaskIdentity() {
        val task =
            TaskSummary(
                id = TaskId("task-240"),
                title = "ship task",
                status = TaskStatus.Done,
                taskNumber = 240,
                messageId = null,
                assignee =
                    MemberProfile(
                        id = MemberId("agent-android"),
                        displayName = "Android Developer",
                        handle = "Android-Developer",
                        kind = MemberKind.Agent
                    ),
                updatedAtLabel = ""
            )
        val unrelatedSameNumberTask =
            task.copy(
                id = TaskId("other-task"),
                status = TaskStatus.Todo,
                taskNumber = 241,
                assignee = null
            )
        val message =
            RaftThreadMessageItem(
                id = "message-240",
                authorId = "user_artin",
                authorName = "Artin",
                body = "ship task",
                taskId = "task-240",
                taskNumber = 240,
                taskStatus = "Todo"
            )
        val references =
            RaftThreadScreenPayload(messages = listOf(message)).threadLinkedTaskReferences()
        val projection =
            TasksUiState(
                loadState =
                    build.raft.app.data.ScreenLoadState.Content(
                        build.raft.app.data.TasksContent(
                            summaryCounts = emptyMap(),
                            tasks = listOf(task, unrelatedSameNumberTask)
                        )
                    )
            ).threadLinkedTasksProjection(references)
        val linkedMessage = message.withLinkedTask(projection.lookup.threadLinkedTaskForMessage(message))

        assertEquals(true, projection.authoritative)
        assertEquals(null, projection.lookup.threadLinkedTaskForMessage(message.copy(taskId = null, taskNumber = 241)))
        assertEquals("done", linkedMessage.taskStatus)
        assertEquals("Android Developer", linkedMessage.taskAssigneeName)
    }

    @Test
    fun linkedTaskLookupKeepsWarmSharedProjectionWhenScopedStateIsEmpty() {
        val task =
            TaskSummary(
                id = TaskId("task-240"),
                title = "ship task",
                status = TaskStatus.InReview,
                taskNumber = 240,
                assignee =
                    MemberProfile(
                        id = MemberId("agent-android"),
                        displayName = "Android Developer",
                        handle = "Android-Developer",
                        kind = MemberKind.Agent
                    ),
                updatedAtLabel = ""
            )
        val warmSharedLookup = listOf(task).threadLinkedTasksByMessageLookup()
        val fallbackTask = task.copy(status = TaskStatus.Todo, assignee = null)
        val selectedLookup =
            threadLinkedTasksLookup(
                scopedProjection = ThreadLinkedTasksProjection(authoritative = true, lookup = emptyMap()),
                sharedStoreProjection = ThreadLinkedTasksProjection(authoritative = true, lookup = warmSharedLookup)
            )
        val fallbackLookup = listOf(fallbackTask).threadLinkedTasksByMessageLookup()
        val message =
            RaftThreadMessageItem(
                id = "message-240",
                authorId = "user_artin",
                authorName = "Artin",
                body = "ship task",
                taskId = "task-240",
                taskNumber = 240,
                taskStatus = "Todo"
            )
        val linkedMessage = message.withLinkedTask(selectedLookup.lookup.threadLinkedTaskForMessage(message))

        assertEquals(warmSharedLookup, selectedLookup.lookup)
        assertNotEquals(fallbackLookup, selectedLookup.lookup)
        assertEquals("in_review", linkedMessage.taskStatus)
        assertEquals("Android Developer", linkedMessage.taskAssigneeName)
    }

    @Test
    fun threadPayloadProjectsMessageTaskStatusFromLinkedTasksStoreWithoutRewritingPayloadTasks() {
        val message =
            RaftThreadMessageItem(
                id = "message-240",
                authorId = "user_artin",
                authorName = "Artin",
                body = "ship task",
                taskId = "task-240",
                taskNumber = 240,
                taskStatus = "Todo"
            )
        val staleTask =
            RaftThreadTaskItem(
                id = "task-240",
                title = "ship task",
                status = "Todo",
                taskNumber = 240,
                messageId = "message-240"
            )
        val task =
            TaskSummary(
                id = TaskId("task-240"),
                title = "ship task",
                status = TaskStatus.Done,
                taskNumber = 240,
                messageId = MessageId("message-240"),
                updatedAtLabel = "now"
            )
        val payload =
            RaftThreadScreenPayload(
                messages = listOf(message),
                tasks = listOf(staleTask)
            )

        val projected =
            payload.toThreadTimelineSlice().withThreadLinkedTasksProjection(
                ThreadLinkedTasksProjection(
                    authoritative = true,
                    lookup = listOf(task).threadLinkedTasksByMessageLookup()
                )
            )

        assertEquals("done", projected.messages.single().taskStatus)
        assertEquals("Todo", payload.tasks.single().status)
    }

    @Test
    fun newerServerTaskProjectionEnrichesMessageBehindOlderLoadedChannelSnapshot() = runBlocking {
        val store = SharedTasksDataStore()
        val message =
            RaftThreadMessageItem(
                id = "message-new",
                authorId = "user_artin",
                authorName = "Artin",
                body = "new task"
            )
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("channel-1")),
            snapshot = TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap())
        )
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot =
                TaskSnapshot(
                    tasks =
                        listOf(
                            TaskSummary(
                                id = TaskId("task-new"),
                                title = "new task",
                                status = TaskStatus.InReview,
                                taskNumber = 288,
                                channelId = ChannelId("channel-1"),
                                messageId = MessageId("message-new"),
                                updatedAtLabel = ""
                            )
                        ),
                    summaryCounts = emptyMap()
                )
        )
        val payload = RaftThreadScreenPayload(messages = listOf(message))
        val slice = store.projection("server-1", SharedTasksScope.Channel(ChannelId("channel-1")))

        val projected =
            payload.toThreadTimelineSlice().withThreadLinkedTasksProjection(
                slice.threadLinkedTasksProjection(payload.threadLinkedTaskReferences())
            )

        assertEquals(false, slice.isExactScopeLoaded)
        assertEquals("task-new", projected.messages.single().taskId)
        assertEquals(288, projected.messages.single().taskNumber)
        assertEquals("in_review", projected.messages.single().taskStatus)
    }

    @Test
    fun convertMessageWithoutTaskBodyRefreshesCanonicalServerProjectionOnce() = runBlocking {
        val convertedTask =
            TaskSummary(
                id = TaskId("message-convert"),
                title = "convert me",
                status = TaskStatus.Todo,
                taskNumber = 291,
                channelId = ChannelId("channel-1"),
                messageId = MessageId("message-convert"),
                updatedAtLabel = ""
            )
        var convertCalls = 0
        var serverLoadCalls = 0
        val dataSource =
            SharedTasksDataSource(
                api =
                    object : RaftTasksApi {
                        override suspend fun serverTasks(
                            serverId: ServerId,
                            priority: DataRequestPriority
                        ): HomeNetworkResponse {
                            serverLoadCalls += 1
                            return HomeNetworkResponse(
                                code = 200,
                                bodyText =
                                    """{"tasks":[{"id":"message-convert","channelId":"channel-1","taskNumber":291,"title":"convert me","status":"todo","createdByType":"user","createdById":"user-1"}]}"""
                            )
                        }

                        override suspend fun convertMessage(
                            serverId: ServerId,
                            messageId: MessageId
                        ): HomeNetworkResponse {
                            assertEquals(ServerId("server-1"), serverId)
                            convertCalls += 1
                            return HomeNetworkResponse(code = 204, bodyText = null)
                        }

                        override suspend fun updateTaskStatus(
                            taskId: TaskId,
                            status: TaskStatus
                        ): HomeNetworkResponse = HomeNetworkResponse(code = 501, bodyText = null)
                    }
            )
        val store = SharedTasksDataStore()

        val result =
            convertMessageToCanonicalTask(
                serverId = ServerId("server-1"),
                messageId = MessageId("message-convert"),
                tasksDataSource = dataSource,
                tasksDataStore = store
            )

        assertEquals(convertedTask.id, assertIs<DataResult.Content<TaskSummary>>(result).data.id)
        assertEquals(1, convertCalls)
        assertEquals(1, serverLoadCalls)
        assertEquals(
            listOf("message-convert"),
            store.projection("server-1", SharedTasksScope.Server).tasks.map { task -> task.id.value }
        )
    }

    @Test
    fun convertMessageWithTaskBodyUpsertsCanonicalProjectionWithoutRefresh() = runBlocking {
        var serverLoadCalls = 0
        val dataSource =
            SharedTasksDataSource(
                api =
                    object : RaftTasksApi {
                        override suspend fun serverTasks(
                            serverId: ServerId,
                            priority: DataRequestPriority
                        ): HomeNetworkResponse {
                            serverLoadCalls += 1
                            return HomeNetworkResponse(code = 200, bodyText = """{"tasks":[]}""")
                        }

                        override suspend fun convertMessage(
                            serverId: ServerId,
                            messageId: MessageId
                        ): HomeNetworkResponse =
                            HomeNetworkResponse(
                                code = 200,
                                bodyText =
                                    """{"task":{"id":"message-convert","channelId":"channel-1","taskNumber":291,"title":"convert me","status":"todo","createdByType":"user","createdById":"user-1"}}"""
                            ).also { assertEquals(ServerId("server-1"), serverId) }

                        override suspend fun updateTaskStatus(
                            taskId: TaskId,
                            status: TaskStatus
                        ): HomeNetworkResponse = HomeNetworkResponse(code = 501, bodyText = null)
                    }
            )
        val store = SharedTasksDataStore()

        val result =
            convertMessageToCanonicalTask(
                serverId = ServerId("server-1"),
                messageId = MessageId("message-convert"),
                tasksDataSource = dataSource,
                tasksDataStore = store
            )

        assertEquals("message-convert", assertIs<DataResult.Content<TaskSummary>>(result).data.id.value)
        assertEquals(0, serverLoadCalls)
        assertEquals(
            listOf("message-convert"),
            store.projection("server-1", SharedTasksScope.Server).tasks.map { task -> task.id.value }
        )
    }

    @Test
    fun convertMessageWithoutTaskBodyFailsWhenCanonicalProjectionOmitsMessage() = runBlocking {
        var serverLoadCalls = 0
        val dataSource =
            SharedTasksDataSource(
                api =
                    object : RaftTasksApi {
                        override suspend fun serverTasks(
                            serverId: ServerId,
                            priority: DataRequestPriority
                        ): HomeNetworkResponse {
                            serverLoadCalls += 1
                            return HomeNetworkResponse(code = 200, bodyText = """{"tasks":[]}""")
                        }

                        override suspend fun convertMessage(
                            serverId: ServerId,
                            messageId: MessageId
                        ): HomeNetworkResponse =
                            HomeNetworkResponse(code = 204, bodyText = null)
                                .also { assertEquals(ServerId("server-1"), serverId) }

                        override suspend fun updateTaskStatus(
                            taskId: TaskId,
                            status: TaskStatus
                        ): HomeNetworkResponse = HomeNetworkResponse(code = 501, bodyText = null)
                    }
            )

        val result =
            convertMessageToCanonicalTask(
                serverId = ServerId("server-1"),
                messageId = MessageId("message-convert"),
                tasksDataSource = dataSource,
                tasksDataStore = SharedTasksDataStore()
            )

        assertEquals(
            "tasks_convert_message_projection_missing",
            assertIs<DataResult.Error>(result).error.code
        )
        assertEquals(1, serverLoadCalls)
    }

    @Test
    fun convertMessageFailureDoesNotRefreshCanonicalProjection() = runBlocking {
        var serverLoadCalls = 0
        val dataSource =
            SharedTasksDataSource(
                api =
                    object : RaftTasksApi {
                        override suspend fun serverTasks(
                            serverId: ServerId,
                            priority: DataRequestPriority
                        ): HomeNetworkResponse {
                            serverLoadCalls += 1
                            return HomeNetworkResponse(code = 200, bodyText = """{"tasks":[]}""")
                        }

                        override suspend fun convertMessage(
                            serverId: ServerId,
                            messageId: MessageId
                        ): HomeNetworkResponse =
                            HomeNetworkResponse(code = 500, bodyText = null)
                                .also { assertEquals(ServerId("server-1"), serverId) }

                        override suspend fun updateTaskStatus(
                            taskId: TaskId,
                            status: TaskStatus
                        ): HomeNetworkResponse = HomeNetworkResponse(code = 501, bodyText = null)
                    }
            )

        val result =
            convertMessageToCanonicalTask(
                serverId = ServerId("server-1"),
                messageId = MessageId("message-convert"),
                tasksDataSource = dataSource,
                tasksDataStore = SharedTasksDataStore()
            )

        assertEquals("tasks_500", assertIs<DataResult.Error>(result).error.code)
        assertEquals(0, serverLoadCalls)
    }

    @Test
    fun realtimeTaskUpsertEnrichesMessageInLoadedExactChannelProjection() = runBlocking {
        val store = SharedTasksDataStore()
        val message =
            RaftThreadMessageItem(
                id = "message-realtime",
                authorId = "user_artin",
                authorName = "Artin",
                body = "realtime task"
            )
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("channel-1")),
            snapshot = TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap())
        )
        store.upsertTask(
            serverId = "server-1",
            task =
                TaskSummary(
                    id = TaskId("task-realtime"),
                    title = "realtime task",
                    status = TaskStatus.InProgress,
                    taskNumber = 289,
                    channelId = ChannelId("channel-1"),
                    messageId = MessageId("message-realtime"),
                    updatedAtLabel = ""
                )
        )
        val payload = RaftThreadScreenPayload(messages = listOf(message))
        val slice = store.projection("server-1", SharedTasksScope.Channel(ChannelId("channel-1")))

        val projected =
            payload.toThreadTimelineSlice().withThreadLinkedTasksProjection(
                slice.threadLinkedTasksProjection(payload.threadLinkedTaskReferences())
            )

        assertEquals(true, slice.isExactScopeLoaded)
        assertEquals("task-realtime", projected.messages.single().taskId)
        assertEquals(289, projected.messages.single().taskNumber)
        assertEquals("in_progress", projected.messages.single().taskStatus)
    }

    @Test
    fun unscopedServerTaskEnrichesVisibleMessageOnlyWhileChannelProjectionIsStale() = runBlocking {
        val store = SharedTasksDataStore()
        val message =
            RaftThreadMessageItem(
                id = "message-unscoped",
                authorId = "user_artin",
                authorName = "Artin",
                body = "unscoped realtime task"
            )
        val payload = RaftThreadScreenPayload(messages = listOf(message))
        val unscopedTask =
            TaskSummary(
                id = TaskId("task-unscoped"),
                title = "unscoped realtime task",
                status = TaskStatus.InProgress,
                taskNumber = 290,
                channelId = null,
                channelName = null,
                messageId = MessageId("message-unscoped"),
                updatedAtLabel = ""
            )
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("channel-1")),
            snapshot = TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap())
        )
        store.upsertTask("server-1", unscopedTask)
        val staleChannelSlice =
            store.channelProjectionFlow("server-1", "channel-1", "general").first()
        val serverSlice = store.projection("server-1", SharedTasksScope.Server)

        val staleProjected =
            payload.toThreadTimelineSlice().withThreadLinkedTasksProjection(
                threadLinkedTasksProjection(
                    channelProjectionSlice = staleChannelSlice,
                    serverProjectionSlice = serverSlice,
                    references = payload.threadLinkedTaskReferences()
                )
            )

        assertEquals(false, staleChannelSlice.isExactScopeLoaded)
        assertEquals("task-unscoped", staleProjected.messages.single().taskId)
        assertEquals(290, staleProjected.messages.single().taskNumber)

        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("channel-1")),
            snapshot = TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap())
        )
        val freshChannelSlice = store.projection("server-1", SharedTasksScope.Channel(ChannelId("channel-1")))
        val freshProjected =
            payload.toThreadTimelineSlice().withThreadLinkedTasksProjection(
                threadLinkedTasksProjection(
                    channelProjectionSlice = freshChannelSlice,
                    serverProjectionSlice = serverSlice,
                    references = payload.threadLinkedTaskReferences()
                )
            )

        assertEquals(true, freshChannelSlice.isExactScopeLoaded)
        assertEquals(null, freshProjected.messages.single().taskId)
    }

    @Test
    fun authoritativeEmptyLinkedTaskProjectionClearsStaleMessageTaskFacts() {
        val payload =
            RaftThreadScreenPayload(
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "message-240",
                            authorId = "user_artin",
                            authorName = "Artin",
                            body = "ship task",
                            taskId = "task-240",
                            taskNumber = 240,
                            taskStatus = "Todo",
                            taskAssigneeId = "agent-android",
                            taskAssigneeName = "Android Developer"
                        )
                    )
            )

        val projected =
            payload.toThreadTimelineSlice().withThreadLinkedTasksProjection(
                ThreadLinkedTasksProjection(authoritative = true, lookup = emptyMap())
            )

        val message = projected.messages.single()
        assertEquals(null, message.taskId)
        assertEquals(null, message.taskNumber)
        assertEquals(null, message.taskStatus)
        assertEquals(null, message.taskAssigneeId)
        assertEquals(null, message.taskAssigneeName)
    }

    @Test
    fun threadTasksJoinStartsOnlyAfterContentGate() {
        val taskChannelId = ChannelId("channel-1")
        val loadingPayload =
            RaftThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "loading",
                showTaskStatusActions = true
            )
        val contentPayload = loadingPayload.copy(status = "content", selectedTab = "chat")

        assertFalse(shouldStartThreadTasksJoin(loadingPayload, startedThreadId = null, taskChannelId = taskChannelId))
        assertFalse(shouldStartThreadTasksLoad(loadingPayload, taskChannelId = taskChannelId))
        assertFalse(shouldStartThreadTasksJoin(contentPayload, startedThreadId = null, taskChannelId = taskChannelId))
        assertTrue(shouldStartThreadTasksLoad(contentPayload, taskChannelId = taskChannelId))
        assertTrue(
            shouldRefreshThreadTasksProjection(
                payload = contentPayload,
                taskChannelId = taskChannelId,
                refreshStarted = false
            )
        )
        assertFalse(
            shouldRefreshThreadTasksProjection(
                payload = contentPayload,
                taskChannelId = taskChannelId,
                refreshStarted = true
            )
        )
        assertFalse(shouldStartThreadTasksJoin(contentPayload, startedThreadId = "channel-1", taskChannelId = taskChannelId))
        assertTrue(shouldStartThreadTasksLoad(contentPayload, taskChannelId = taskChannelId))
        assertTrue(
            shouldStartThreadTasksJoin(
                contentPayload.copy(selectedTab = "tasks"),
                startedThreadId = null,
                taskChannelId = taskChannelId
            )
        )
        assertFalse(
            shouldStartThreadTasksJoin(
                contentPayload.copy(sourceThreadId = null),
                startedThreadId = null,
                taskChannelId = taskChannelId
            )
        )
        assertFalse(
            shouldRefreshThreadTasksProjection(
                payload = contentPayload.copy(selectedTab = "tasks"),
                taskChannelId = taskChannelId,
                refreshStarted = false
            )
        )
    }

    @Test
    fun resolvedAuxiliaryThreadIdUsesCurrentPayloadIdentityAfterLoading() {
        val launchPayload =
            RaftThreadScreenPayload(
                instanceId = "activity-thread",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "wrong-thread",
                streamChannelId = "wrong-thread",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                status = "loading"
            )

        assertEquals(null, launchPayload.resolvedAuxiliaryThreadId())
        assertEquals(
            "thread-m1",
            launchPayload
                .copy(
                    sourceThreadId = "thread-m1",
                    streamChannelId = "thread-m1",
                    status = "content"
                )
                .resolvedAuxiliaryThreadId()
        )
        assertEquals(
            "wrong-thread",
            launchPayload
                .copy(status = "content")
                .resolvedAuxiliaryThreadId()
        )
    }

    @Test
    fun resolvedAuxiliaryThreadIdAllowsEmptyReplyThreadAfterLoad() {
        val launchPayload =
            RaftThreadScreenPayload(
                instanceId = "task-thread-task-1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                status = "loading"
            )

        val loadedEmptyPayload =
            launchPayload.copy(
                status = "empty",
                messages = emptyList()
            )
        val resolvedThreadId = loadedEmptyPayload.resolvedAuxiliaryThreadId()
        val composer =
            loadedEmptyPayload
                .resolveThreadComposer()
                .copy(streamChannelId = resolvedThreadId, enabled = resolvedThreadId != null)
        val surface =
            composer.toThreadComposerSurface(
                channelId = resolvedThreadId.orEmpty(),
                parentChannelId = loadedEmptyPayload.parentChannelId
            )

        assertEquals(null, launchPayload.resolvedAuxiliaryThreadId())
        assertEquals("thread-m1", resolvedThreadId)
        assertEquals("thread-m1", surface.sendChannelId)
    }

    @Test
    fun threadTasksJoinUsesParentChannelForReplyThreadTaskStatusHydration() {
        val launchPayload =
            RaftThreadScreenPayload(
                instanceId = "activity-thread",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "wrong-thread",
                streamChannelId = "wrong-thread",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                status = "loading",
                showTaskStatusActions = true
            )
        val resolvedPayload =
            launchPayload.copy(
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                status = "content",
                selectedTab = "chat"
            )
        val resolvedTaskChannelId = resolvedPayload.resolvedTaskLookupChannelId()?.let(::ChannelId)

        assertEquals("thread-m1", resolvedPayload.resolvedAuxiliaryThreadId())
        assertEquals("channel-1", resolvedPayload.resolvedTaskLookupChannelId())
        assertFalse(shouldStartThreadTasksJoin(launchPayload, startedThreadId = null, taskChannelId = ChannelId("channel-1")))
        assertFalse(shouldStartThreadTasksJoin(resolvedPayload, startedThreadId = null, taskChannelId = resolvedTaskChannelId))
        assertTrue(
            shouldStartThreadTasksJoin(
                resolvedPayload.copy(selectedTab = "tasks"),
                startedThreadId = null,
                taskChannelId = resolvedTaskChannelId
            )
        )
        assertFalse(shouldStartThreadTasksJoin(resolvedPayload, startedThreadId = null, taskChannelId = ChannelId("thread-m1")))
    }

    @Test
    fun hidesRawUuidTaskAssigneeUntilMemberNameCanBeResolved() {
        val assigneeId = "9d2a923b-7d46-43c3-bf5f-d12a6df8ef12"
        val base =
            RaftThreadScreenPayload(
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
                                    content = "please ship #236",
                                    taskNumber = 236,
                                    taskStatus = "in_review",
                                    taskAssigneeType = "agent",
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
            RaftThreadScreenPayload(
                instanceId = "thread-1",
                members =
                    listOf(
                        RaftThreadMemberItem(
                            id = assigneeId,
                            displayName = "Android Developer",
                            handle = "Android-Developer",
                            kind = "agent"
                        )
                    ),
                messages =
                    listOf(
                        RaftThreadMessageItem(
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
    fun threadMemberDetailPageDataCarriesMemberIdentityAndExpectedKindOnly() {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "thread-1",
                sourceThreadId = "thread-source-1",
                localOffsetMinutes = 480,
                todayEpochDay = 20623,
                nowEpochMillis = 1782200000000,
                timeFormat = RaftTimeFormat12Hour
            )

        val pageData =
            payload.toThreadMemberDetailPageData(
                MemberDetailIdentity(MemberId("human-artin"), MemberKind.Human)
            )!!

        assertEquals(MEMBER_DETAIL_PAGE, pageData.optString("pageName"))
        assertEquals("human-artin", pageData.optString("memberId"))
        assertEquals("human", pageData.optString("memberKind"))
        assertEquals("", pageData.optString("sourceServerId"))
        assertEquals("thread-member-detail-thread-1-human-artin", pageData.optString("instanceId"))
        assertEquals("", pageData.optString(RAFT_SHELL_INSTANCE_ID))
        assertEquals("", pageData.optString(RAFT_SHELL_SERVER_ID))
        assertEquals("", pageData.optString("members"))
        assertEquals("", pageData.optString("profile"))
        assertEquals("480", pageData.optString("localOffsetMinutes"))
        assertEquals("20623", pageData.optString("todayEpochDay"))
        assertEquals("1782200000000", pageData.optString("nowEpochMillis"))
        assertEquals(RaftTimeFormat12Hour, pageData.optString("timeFormat"))
    }

    @Test
    fun threadAuthorGestureIdentityChangesWhenVirtualizedRowAuthorChanges() {
        val authorA =
            RaftThreadMessageItem(
                id = "message-1",
                authorId = "agent-a",
                authorName = "Agent A",
                authorKind = "agent"
            )
        val authorB = authorA.copy(authorId = "agent-b", authorName = "Agent B")
        val nextMessageFromA = authorA.copy(id = "message-2")

        assertEquals("message-1|agent-a|agent", authorA.threadAuthorGestureIdentity())
        assertNotEquals(authorA.threadAuthorGestureIdentity(), authorB.threadAuthorGestureIdentity())
        assertNotEquals(authorA.threadAuthorGestureIdentity(), nextMessageFromA.threadAuthorGestureIdentity())
    }

    @Test
    fun threadMembersPagerRouteCarriesOnlyScopeAndDisplayContext() {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "thread-1",
                sourceThreadId = "channel-1",
                localOffsetMinutes = 480,
                todayEpochDay = 20623,
                nowEpochMillis = 1782200000000,
                timeFormat = RaftTimeFormat12Hour
            )

        val pageData =
            payload.toThreadMembersPageData(
                serverId = ServerId("server-1"),
                channelId = ChannelId("channel-1")
            )

        assertEquals(THREAD_MEMBERS_PAGE, pageData.optString("pageName"))
        assertEquals("thread-members-thread-1", pageData.optString("instanceId"))
        assertEquals("server-1", pageData.optString("serverId"))
        assertEquals("channel-1", pageData.optString("channelId"))
        assertEquals("", pageData.optString("members"))
        assertEquals("", pageData.optString("memberCandidates"))
        assertEquals("", pageData.optString("title"))
        assertEquals("", pageData.optString(RAFT_SHELL_INSTANCE_ID))
        assertEquals("", pageData.optString(RAFT_SHELL_SERVER_ID))
        assertEquals(
            ThreadMembersPageRoute(
                instanceId = "thread-members-thread-1",
                serverId = ServerId("server-1"),
                channelId = ChannelId("channel-1"),
                localOffsetMinutes = 480,
                todayEpochDay = 20623,
                nowEpochMillis = 1782200000000,
                timeFormat = RaftTimeFormat12Hour
            ),
            pageData.toThreadMembersPageRouteOrNull()
        )
    }

    @Test
    fun threadMembersTargetServerSurvivesDifferentInheritedShellOwnerServer() {
        val pageData =
            RaftThreadScreenPayload(instanceId = "thread-1", sourceThreadId = "channel-1")
                .toThreadMembersPageData(
                    serverId = ServerId("server-target"),
                    channelId = ChannelId("channel-1")
                )
                .withRaftShellStoreScope(
                    RaftShellStoreScope.create("shell-a", "server-owner")
                )

        assertEquals("server-target", pageData.optString("serverId"))
        assertEquals("server-owner", pageData.optString(RAFT_SHELL_SERVER_ID))
        assertEquals(ServerId("server-target"), pageData.toThreadMembersPageRouteOrNull()?.serverId)
        assertEquals("server-owner", pageData.raftShellStoreScopeOrNull()?.initialServerId)
    }

    @Test
    fun threadMemberDetailPageDataCarriesSourceServerIdentityOnly() {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "thread-1",
                sourceThreadId = "thread-source-1"
            )

        val pageData =
            payload.toThreadMemberDetailPageData(
                MemberDetailIdentity(
                    memberId = MemberId("remote-human"),
                    kind = MemberKind.Human,
                    sourceServerId = ServerId("server-remote")
                )
            )!!

        assertEquals("remote-human", pageData.optString("memberId"))
        assertEquals("human", pageData.optString("memberKind"))
        assertEquals("server-remote", pageData.optString("sourceServerId"))
        assertEquals("", pageData.optString("members"))
        assertEquals("", pageData.optString("profile"))
        assertEquals("", pageData.optString("displayName"))
    }

    @Test
    fun threadChannelSearchPageDataCarriesPreselectedChannelIdentityOnly() {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "thread-1",
                sourceThreadId = "channel-1",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                localOffsetMinutes = 480,
                todayEpochDay = 20623,
                nowEpochMillis = 1782200000000,
                timeFormat = RaftTimeFormat12Hour
            )

        val pageData = payload.toThreadChannelSearchPageData()!!

        assertEquals(HOME_SEARCH_PAGE, pageData.optString("pageName"))
        assertEquals("search", pageData.optString("destination"))
        assertEquals("channel-1", pageData.optString(HOME_SEARCH_SELECTED_CHANNEL_ID))
        assertEquals("", pageData.optString(HOME_SEARCH_INITIAL_OPEN_FILTER_ID))
        assertEquals(true, pageData.optBoolean(HOME_SEARCH_FOCUS_INPUT, false))
        assertEquals("", pageData.optString("title"))
        assertEquals("", pageData.optString("messages"))
        assertEquals("480", pageData.optString("localOffsetMinutes"))
        assertEquals("20623", pageData.optString("todayEpochDay"))
        assertEquals("1782200000000", pageData.optString("nowEpochMillis"))
        assertEquals(RaftTimeFormat12Hour, pageData.optString("timeFormat"))
    }

    @Test
    fun threadChannelSearchPageDataRejectsNonChannelSurfaces() {
        assertEquals(
            null,
            RaftThreadScreenPayload(
                sourceThreadId = "dm-1",
                surfaceKind = RaftThreadSurfaceKind.DirectMessage
            ).toThreadChannelSearchPageData()
        )
        assertEquals(
            null,
            RaftThreadScreenPayload(
                sourceThreadId = "thread-1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread
            ).toThreadChannelSearchPageData()
        )
        assertEquals(
            null,
            RaftThreadScreenPayload(
                sourceThreadId = "   ",
                surfaceKind = RaftThreadSurfaceKind.Channel
            ).toThreadChannelSearchPageData()
        )
    }

    @Test
    fun threadHeaderSearchKeepsChannelSearchPageAndScopesInlineSearchToThreads() {
        assertEquals(
            ThreadHeaderSearchAction.ChannelSearch,
            RaftThreadScreenPayload(
                sourceThreadId = "channel-1",
                surfaceKind = RaftThreadSurfaceKind.Channel
            ).threadHeaderSearchAction()
        )
        assertEquals(
            ThreadHeaderSearchAction.InlineThreadSearch,
            RaftThreadScreenPayload(
                sourceThreadId = "thread-1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread
            ).threadHeaderSearchAction()
        )
        assertEquals(
            null,
            RaftThreadScreenPayload(
                sourceThreadId = "dm-1",
                surfaceKind = RaftThreadSurfaceKind.DirectMessage
            ).threadHeaderSearchAction()
        )
    }

    @Test
    fun threadListBoundsUseMeasuredItemsForBottomOffset() {
        assertEquals(0, threadMessagesBeyondBoundsItemCount(-1))
        assertEquals(1, threadMessagesBeyondBoundsItemCount(1))
        assertEquals(3, threadMessagesBeyondBoundsItemCount(3))
        assertEquals(4, threadMessagesBeyondBoundsItemCount(80))
        assertEquals(8, threadMessagesBeyondBoundsItemCount(80, viewportHeightDp = 720f))
        assertEquals(8, threadMessagesBeyondBoundsItemCount(80, viewportHeightDp = 10_000f))
        assertEquals(
            ThreadLazyWindowConsumerCounts(
                listBeyondBoundsItems = 8,
                markdownPreparseOverscanItems = 8,
                diagnosticsBeyondBoundsItems = 8
            ),
            threadLazyWindowConsumerCounts(
                itemCount = 80,
                viewportHeightDp = 720f
            )
        )
        assertFalse(threadViewportMeasurementReady(0f))
        assertFalse(threadViewportMeasurementReady(Float.NaN))
        assertFalse(threadViewportMeasurementReady(Float.POSITIVE_INFINITY))
        assertTrue(threadViewportMeasurementReady(720f))
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
            RaftThreadScreenPayload(
                instanceId = "thread-1",
                messages =
                    listOf(
                        RaftThreadMessageItem(
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
        assertEquals("Cindy joined\nthe channel", message.systemContentSummary())
        assertEquals("Cindy joined\nthe channel\nType: member_joined\nactor: Cindy\nsource: invite", message.expandedSystemContent())
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
            RaftThreadScreenPayload(
                instanceId = "thread-1",
                localOffsetMinutes = 480,
                todayEpochDay = 20583,
                messages =
                    listOf(
                        RaftThreadMessageItem(
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
            RaftThreadScreenPayload(
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
                    RaftThreadMessageItem(
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
            RaftThreadScreenPayload(
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
    fun threadStoreRetryEventsDispatchRealStoreIntentsInsteadOfFallingThroughAsUnsupported() {
        mapOf(
            "retryThreadLoad" to SharedThreadIntent.Refresh::class,
            "retryThreadAccess" to SharedThreadIntent.RetryAccessResolution::class,
            "retryLoadOlder" to SharedThreadIntent.LoadOlder::class,
            "retryLoadNewer" to SharedThreadIntent.LoadNewer::class,
        ).forEach { (action, expectedIntentClass) ->
            val dispatched = mutableListOf<SharedThreadIntent>()

            assertEquals(true, handleThreadStoreRetryEvent(action, dispatched::add), action)
            assertEquals(1, dispatched.size, action)
            assertEquals(expectedIntentClass, dispatched.single()::class, action)
        }

        val dispatched = mutableListOf<SharedThreadIntent>()
        assertEquals(false, handleThreadStoreRetryEvent("openTask", dispatched::add))
        assertEquals(emptyList(), dispatched)
    }

    @Test
    fun threadRetrySeparatesCanonicalLoadFailureFromParentAccessProofFailure() {
        assertEquals("retryThreadLoad", threadRetryAction(canonicalAccessResolved = false))
        assertEquals("retryThreadAccess", threadRetryAction(canonicalAccessResolved = true))
    }

    @Test
    fun sharedShellHidesChannelChromeForReplyThread() {
        val payload =
            RaftThreadScreenPayload(
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
                }.toJsonObject()
            )

        assertEquals(1, attachments.size)
        assertEquals("att-1", attachments.first().id)
        assertEquals("report.pdf", attachments.first().fileName)
        assertEquals("application/pdf", attachments.first().mimeType)
        assertEquals(12288L, attachments.first().sizeBytes)
        assertEquals("", attachments.first().sizeLabel)
        assertEquals("12.0 KB", attachments.first().displaySizeLabel())
    }

    @Test
    fun parsesPickedAttachmentFileRefsForSharedUpload() {
        val files =
            parseSharedThreadPickedPlatformFiles(
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
            parseSharedThreadPickedPlatformFiles(
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
            parseSharedThreadPickedPlatformFiles(
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

    @Test
    fun threadMessageSavedBadgeTracksSavedStateIndependentlyOfRowActionPreferences() {
        assertTrue(
            shouldShowThreadMessageSavedBadge(
                showSaveAction = true,
                richContentEnabled = true,
                hasLocalSendState = false,
                isSaved = true
            )
        )
        assertFalse(
            shouldShowThreadMessageSavedBadge(
                showSaveAction = true,
                richContentEnabled = true,
                hasLocalSendState = false,
                isSaved = false
            )
        )
        assertTrue(
            shouldShowThreadMessageSavedBadge(
                showSaveAction = true,
                richContentEnabled = true,
                hasLocalSendState = false,
                isSaved = true
            )
        )
        assertFalse(
            shouldShowThreadMessageSavedBadge(
                showSaveAction = true,
                richContentEnabled = true,
                hasLocalSendState = true,
                isSaved = true
            )
        )
    }

    @Test
    fun threadFollowActionRequiresPersistedMessage() {
        val replyMetadata =
            ThreadReplyMetadata(
                parentMessageId = "message-1",
                threadChannelId = "thread-1",
                replyCount = 2,
                unreadReplyCount = 0,
                firstUnreadReplyMessageId = null,
                isThreadFollowed = false
            )
        val pendingContext =
            RaftThreadPendingSendContext(
                channelId = "channel-1",
                body = "Pending message"
            )

        assertTrue(
            shouldShowThreadFollowAction(
                message = RaftThreadMessageItem(id = "message-1", authorId = "author-1", authorName = "Author"),
                replyMetadata = replyMetadata
            )
        )
        assertFalse(
            shouldShowThreadFollowAction(
                message =
                    RaftThreadMessageItem(
                        id = "local-sending",
                        authorId = "author-1",
                        authorName = "Author",
                        deliveryState = ThreadMessageDeliverySending,
                        pendingSendContext = pendingContext
                    ),
                replyMetadata = replyMetadata.copy(parentMessageId = "local-sending")
            )
        )
        assertFalse(
            shouldShowThreadFollowAction(
                message =
                    RaftThreadMessageItem(
                        id = "local-failed",
                        authorId = "author-1",
                        authorName = "Author",
                        deliveryState = ThreadMessageDeliveryFailed,
                        pendingSendContext = pendingContext
                    ),
                replyMetadata = replyMetadata.copy(parentMessageId = "local-failed")
            )
        )
    }

    @Test
    fun savedBadgeQualifiesAsThreadFooterMeta() {
        val message =
            RaftThreadMessageItem(
                id = "saved-only",
                authorId = "author-1",
                authorName = "Author",
                isSaved = true
            )

        assertFalse(message.hasThreadFooterMeta())
        assertTrue(message.hasThreadFooterMeta(showSavedBadge = true))
    }

    private fun markdownChannelHomeSnapshot(vararg channels: ConversationSummary): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Server"),
            currentMember =
                MemberProfile(
                    id = MemberId("member-1"),
                    displayName = "Member",
                    handle = "member",
                    kind = MemberKind.Human
                ),
            channels = channels.map { it.toHomeChannelRow() },
            directMessages = emptyList(),
            unreadTotal = 0
        )

    private fun mentionSuggestion(
        displayName: String,
        handle: String,
        memberId: String,
        inMentionChannel: Boolean = true,
        kind: MemberKind = MemberKind.Human
    ): SharedMembersMemberSuggestion =
        SharedMembersMemberSuggestion(
            displayName = displayName,
            handle = handle,
            memberId = memberId,
            inMentionChannel = inMentionChannel,
            kind = kind
        )

    private fun threadMessage(
        id: String,
        authorId: String,
        authorHandle: String
    ): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = id,
            authorId = authorId,
            authorName = authorHandle,
            authorHandle = authorHandle
        )

    private fun testThreadMessage(
        id: String,
        messageType: String = "chat"
    ): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = id,
            authorId = "u-$id",
            authorName = "User $id",
            messageType = messageType,
            body = "body $id"
        )

    private fun buildAppShellThreadPayloadFromSummary(
        conversation: ConversationSummary,
        timeContext: AppShellThreadTimeContext? = null,
        alignOwnMessagesRight: Boolean = false,
        compactConsecutiveMessages: Boolean = false,
        anchorMessageId: String? = null,
        originFallback: ThreadLaunchOriginFallback? = null
    ): RaftThreadScreenPayload =
        requireNotNull(buildAppShellThreadPayload(
            conversation = conversation.toThreadLaunchConversationIdentity(),
            timeContext = timeContext,
            alignOwnMessagesRight = alignOwnMessagesRight,
            compactConsecutiveMessages = compactConsecutiveMessages,
            anchorMessageId = anchorMessageId,
            originFallback = originFallback,
            accessState =
                ThreadLaunchAccessState(
                    joined = conversation.joined,
                    permissions = conversation.permissions
                )
        ))

    private fun buildStandaloneConversationThreadLaunchPayloadFromSummary(
        conversation: ConversationSummary,
        timeContext: AppShellThreadTimeContext?,
        alignOwnMessagesRight: Boolean = false,
        compactConsecutiveMessages: Boolean = false,
        anchorMessageId: String? = null
    ): WireThreadLaunchPayload =
        requireNotNull(buildStandaloneConversationThreadLaunchPayload(
            conversation = conversation.toThreadLaunchConversationIdentity(),
            timeContext = timeContext,
            alignOwnMessagesRight = alignOwnMessagesRight,
            compactConsecutiveMessages = compactConsecutiveMessages,
            anchorMessageId = anchorMessageId
        ))

    private class FakeMarkdownTaskNumberApi(
        private val channelTaskBody: String,
        private val serverTasksBody: String
    ) : RaftTasksApi {
        val calls = mutableListOf<String>()

        override suspend fun serverTasks(
            serverId: ServerId,
            priority: DataRequestPriority
        ): HomeNetworkResponse {
            calls += "serverTasks:${serverId.value}"
            return HomeNetworkResponse(code = 200, bodyText = serverTasksBody)
        }

        override suspend fun taskByNumber(
            serverId: ServerId,
            channelId: ChannelId,
            taskNumber: Int,
            priority: DataRequestPriority
        ): HomeNetworkResponse {
            calls += "taskByNumber:${serverId.value}:${channelId.value}:$taskNumber"
            return HomeNetworkResponse(code = 200, bodyText = channelTaskBody)
        }

        override suspend fun updateTaskStatus(
            taskId: TaskId,
            status: TaskStatus
        ): HomeNetworkResponse =
            HomeNetworkResponse(code = 501, bodyText = null)
    }
}
