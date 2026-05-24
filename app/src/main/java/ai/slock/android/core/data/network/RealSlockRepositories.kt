package ai.slock.android.core.data.network

import ai.slock.android.core.data.auth.AuthSession
import ai.slock.android.core.data.auth.AuthSessionStore
import ai.slock.android.core.data.auth.AuthTokenRefresher
import ai.slock.android.core.data.auth.KtorAuthRepository
import ai.slock.android.core.data.repository.AgentActivityEntry
import ai.slock.android.core.data.repository.AgentActivitySnapshot
import ai.slock.android.core.data.repository.AgentDetailSnapshot
import ai.slock.android.core.data.repository.AgentDmSnapshot
import ai.slock.android.core.data.repository.AgentDmSummary
import ai.slock.android.core.data.repository.AgentReminderSnapshot
import ai.slock.android.core.data.repository.AgentReminderSummary
import ai.slock.android.core.data.repository.AgentSkillGroup
import ai.slock.android.core.data.repository.AgentSkillPathGroup
import ai.slock.android.core.data.repository.AgentSkillSummary
import ai.slock.android.core.data.repository.AgentSkillsSnapshot
import ai.slock.android.core.data.repository.AgentWorkspaceChildrenSnapshot
import ai.slock.android.core.data.repository.AgentWorkspaceFilePreviewSnapshot
import ai.slock.android.core.data.repository.AgentWorkspaceSnapshot
import ai.slock.android.core.data.repository.AgentWorkspaceTreeSnapshot
import ai.slock.android.core.data.repository.AttachmentOpenTarget
import ai.slock.android.core.data.repository.AttachmentUploadFile
import ai.slock.android.core.data.repository.AttachmentUploadRequest
import ai.slock.android.core.data.repository.AttachmentUploadResult
import ai.slock.android.core.data.repository.ChannelFileItem
import ai.slock.android.core.data.repository.ChannelFilesSnapshot
import ai.slock.android.core.data.repository.ChannelMemberSnapshot
import ai.slock.android.core.data.repository.ChannelSnapshot
import ai.slock.android.core.data.repository.ChannelUnreadSnapshot
import ai.slock.android.core.data.repository.ConversationRepository
import ai.slock.compose.data.DataResult
import ai.slock.android.core.data.repository.HomeSnapshot
import ai.slock.android.core.data.repository.MemberSnapshot
import ai.slock.android.core.data.repository.SendMessageResult
import ai.slock.android.core.data.repository.SettingsComputerWorkspaceSnapshot
import ai.slock.android.core.data.repository.ThreadActionResult
import ai.slock.android.core.data.repository.ThreadListSnapshot
import ai.slock.android.core.data.repository.ThreadSnapshot
import ai.slock.android.core.model.AgentStatus
import ai.slock.android.core.model.Attachment
import ai.slock.android.core.model.AttachmentId
import ai.slock.android.core.model.AvatarReference
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationPermissions
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.InboxItem
import ai.slock.android.core.model.InboxItemKind
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageDeliveryState
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.MessageType
import ai.slock.compose.model.sortedByMessageTime
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.model.ThreadConversation
import ai.slock.android.core.model.withUnreadReplyCount
import ai.slock.compose.model.SlockWorkspaceFileKind
import ai.slock.compose.model.SlockWorkspaceFilePreview
import ai.slock.compose.model.SlockWorkspaceNode
import ai.slock.compose.model.parentChannelIdOrNull
import ai.slock.compose.model.parentMessageIdOrNull
import ai.slock.compose.model.threadIdentity
import ai.slock.compose.model.toGravatarAvatarReference
import ai.slock.compose.network.SlockRequestPriority
import ai.slock.android.core.state.EmptyReason
import com.google.gson.Gson
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import ai.slock.compose.model.topLevelThreadId

class RealConversationRepository(
        private val store: AuthSessionStore,
        private val api: SlockApi,
        private val warningLogger: (String) -> Unit = { message ->
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                    "SlockConversation",
                    message
            )
        }
) : ConversationRepository {
    override suspend fun getThread(
            threadId: ThreadId,
            includeFollowedSummaries: Boolean
    ): DataResult<ThreadSnapshot> {
        val context = currentContext() ?: return authRequired()
        return loadChannelMessages(
                context = context,
                threadId = threadId,
                block = { api.channelMessages(currentSlockApiPriority(), threadId.value, MESSAGE_PAGE_LIMIT) },
                source = "messages_channel",
                includeFollowedSummaries = includeFollowedSummaries
        )
    }

    override suspend fun getMessageContext(messageId: MessageId): DataResult<ThreadSnapshot> {
        val context = currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoMessages,
                block = { api.messageContext(currentSlockApiPriority(), messageId.value) },
                emptyWhen = { it.messages.isEmpty() && it.parentMessage == null },
                source = "messages_context",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    val fallbackThreadId = ThreadId(messageId.value)
                    val payload =
                            ai.slock.compose.data.parseSharedMessageContext(
                                    bodyText,
                                    fallbackThreadId
                            )
                    val resolvedThreadId = payload.channelId?.let { ThreadId(it.value) }
                            ?: fallbackThreadId
                    buildSharedThreadSnapshot(
                            payload = payload,
                            threadId = resolvedThreadId,
                            canLoadMoreBefore = payload.hasOlder,
                            canLoadMoreAfter = payload.hasNewer,
                            highlightedMessageId = payload.targetMessageId ?: messageId
                    )
                }
        )
    }

    override suspend fun loadOlderMessages(
            threadId: ThreadId,
            beforeSeq: Long
    ): DataResult<ThreadSnapshot> {
        val context = currentContext() ?: return authRequired()
        return loadChannelMessages(
                context = context,
                threadId = threadId,
                block = {
                    api.channelMessages(currentSlockApiPriority(), threadId.value, MESSAGE_PAGE_LIMIT, beforeSeq = beforeSeq)
                },
                source = "messages_channel_older"
        )
    }

    override suspend fun loadNewerMessages(
            threadId: ThreadId,
            afterSeq: Long
    ): DataResult<ThreadSnapshot> {
        val context = currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoMessages,
                block = {
                    api.messageSync(currentSlockApiPriority(),
                            sinceSeq = afterSeq,
                            limit = SYNC_PAGE_LIMIT,
                            channelId = threadId.value
                    )
                },
                emptyWhen = { it.messages.isEmpty() },
                source = "messages_sync",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    val payload =
                            ai.slock.compose.data.parseSharedSyncMessages(
                                    bodyText,
                                    threadId
                            )
                    buildSharedThreadSnapshot(
                            payload = payload,
                            threadId = threadId
                    )
                }
        )
    }

    override suspend fun getChannelThreads(
            channelId: ChannelId,
            limit: Int
    ): DataResult<ThreadListSnapshot> {
        val context = currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoInboxItems,
                block = { api.channelThreads(currentSlockApiPriority(), channelId.value, limit) },
                emptyWhen = { it.threads.isEmpty() },
                source = "channel_threads",
                warningLogger = warningLogger,
                mapper = { bodyText -> parseThreadListSnapshotFromBody(bodyText, context) }
        )
    }

    override suspend fun getFollowedThreads(
            serverId: ServerId,
            limit: Int
    ): DataResult<ThreadListSnapshot> {
        val context = store.readSession()?.toNetworkServerContext(serverId) ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoInboxItems,
                block = { api.followedThreads(currentSlockApiPriority(), limit) },
                emptyWhen = { it.threads.isEmpty() },
                source = "followed_threads",
                warningLogger = warningLogger,
                mapper = { bodyText -> parseThreadListSnapshotFromBody(bodyText, context) }
        )
    }

    override suspend fun createOrOpenThread(
            channelId: ChannelId,
            parentMessageId: MessageId
    ): DataResult<ThreadActionResult> =
            threadAction(
                    fallbackThreadChannelId = null,
                    fallbackParentMessageId = parentMessageId,
                    source = "thread_create_or_open"
            ) {
                api.createOrOpenThread(currentSlockApiPriority(), channelId.value, parentMessageId.toParentMessageThreadBody())
            }

    override suspend fun followThread(parentMessageId: MessageId): DataResult<ThreadActionResult> =
            threadAction(
                    fallbackThreadChannelId = null,
                    fallbackParentMessageId = parentMessageId,
                    source = "thread_follow_parent"
            ) { api.followThread(currentSlockApiPriority(), parentMessageId.toParentMessageThreadBody()) }

    override suspend fun followThread(threadChannelId: ChannelId): DataResult<ThreadActionResult> =
            threadAction(threadChannelId, source = "thread_follow") {
                api.followThread(currentSlockApiPriority(), threadChannelId.toThreadActionBody())
            }

    override suspend fun unfollowThread(
            threadChannelId: ChannelId
    ): DataResult<ThreadActionResult> =
            threadAction(threadChannelId, source = "thread_unfollow") {
                api.unfollowThread(currentSlockApiPriority(), threadChannelId.toThreadActionBody())
            }

    override suspend fun markThreadDone(
            threadChannelId: ChannelId
    ): DataResult<ThreadActionResult> =
            threadAction(threadChannelId, source = "thread_done") {
                api.markThreadDone(currentSlockApiPriority(), threadChannelId.toThreadActionBody())
            }

    override suspend fun markThreadUndone(
            threadChannelId: ChannelId
    ): DataResult<ThreadActionResult> =
            threadAction(threadChannelId, source = "thread_undone") {
                api.markThreadUndone(currentSlockApiPriority(), threadChannelId.toThreadActionBody())
            }

    override suspend fun uploadAttachments(
            request: AttachmentUploadRequest
    ): DataResult<AttachmentUploadResult> {
        currentContext() ?: return authRequired()
        if (request.files.isEmpty()) return DataResult.Empty(EmptyReason.NoMessages)
        return execute(
                emptyReason = EmptyReason.NoMessages,
                block = {
                    api.uploadAttachments(currentSlockApiPriority(),
                            channelId = request.channelId.value.toRequestBody(textMediaType),
                            files = request.files.map { it.toMultipartPart() }
                    )
                },
                source = "attachments_upload",
                warningLogger = warningLogger,
                mapper = { bodyText -> ai.slock.compose.data.parseSharedAttachmentUploadResult(bodyText) },
                emptyWhen = { it.attachments.isEmpty() }
        )
    }

    override suspend fun getAttachmentOpenTarget(
            attachmentId: AttachmentId
    ): DataResult<AttachmentOpenTarget> {
        currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoMessages,
                block = { api.attachmentUrl(currentSlockApiPriority(), attachmentId.value) },
                source = "attachment_open_url",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    ai.slock.compose.data.parseSharedAttachmentOpenTarget(
                            bodyText,
                            attachmentId
                    ) ?: throw IllegalStateException("Attachment URL response missing target")
                },
                emptyWhen = { it.url.isBlank() }
        )
    }

    override suspend fun getChannelFiles(
            channelId: ChannelId,
            limit: Int,
            cursor: String?
    ): DataResult<ChannelFilesSnapshot> {
        currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoMessages,
                block = { api.channelFiles(currentSlockApiPriority(), channelId.value, limit, cursor) },
                source = "channel_files",
                warningLogger = warningLogger,
                mapper = { bodyText -> ai.slock.compose.data.parseSharedChannelFilesSnapshot(bodyText, channelId) },
                emptyWhen = { it.items.isEmpty() }
        )
    }

    override suspend fun sendMessage(
            channelId: ChannelId,
            body: String,
            attachmentIds: List<AttachmentId>,
            asTask: Boolean
    ): DataResult<SendMessageResult> {
        val context = currentContext() ?: return authRequired()
        if (body.isBlank() && attachmentIds.isEmpty())
                return DataResult.Empty(EmptyReason.NoMessages)
        return execute(
                emptyReason = EmptyReason.NoMessages,
                block = { api.sendMessage(currentSlockApiPriority(), sendMessageBody(channelId, body, attachmentIds, asTask)) },
                source = "messages_send",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    val fallbackThreadId = topLevelThreadId(channelId)
                    val message =
                            ai.slock.compose.data.parseSharedSentMessage(
                                    bodyText,
                                    fallbackThreadId
                            )
                                    ?: Message(
                                            id = MessageId("message-sent"),
                                            threadId = fallbackThreadId,
                                            channelId = channelId,
                                            author =
                                                    context.session.user.toMemberProfile(),
                                            timestampLabel = "",
                                            body = body,
                                            deliveryState = MessageDeliveryState.Sent
                                    )
                    SendMessageResult(message)
                }
        )
    }

    private suspend fun loadChannelMessages(
            context: NetworkServerContext,
            threadId: ThreadId,
            block: suspend () -> ApiResponse,
            source: String,
            includeFollowedSummaries: Boolean = true
    ): DataResult<ThreadSnapshot> = coroutineScope {
        val messagesDeferred =
                async {
                    execute(
                            emptyReason = EmptyReason.NoMessages,
                            block = block,
                            emptyWhen = { it.messages.isEmpty() },
                            source = source,
                            warningLogger = warningLogger,
                            mapper = { bodyText ->
                                val payload =
                                        ai.slock.compose.data.parseSharedChannelMessages(
                                                bodyText,
                                                threadId
                                        )
                                buildSharedThreadSnapshot(
                                        payload = payload,
                                        threadId = threadId,
                                        canLoadMoreBefore =
                                                payload.messages.size >= MESSAGE_PAGE_LIMIT
                                )
                            }
                    )
                }
        val threadSummariesDeferred =
                async {
                    execute(
                            emptyReason = EmptyReason.NoInboxItems,
                            block = { api.channelThreads(currentSlockApiPriority(), threadId.value, MESSAGE_PAGE_LIMIT) },
                            emptyWhen = { false },
                            source = "${source}_thread_summaries",
                            warningLogger = warningLogger,
                            mapper = { bodyText -> parseThreadListSnapshotFromBody(bodyText, context) }
                    )
                }
        val followedSummariesDeferred =
                if (includeFollowedSummaries) {
                    async {
                        execute(
                                emptyReason = EmptyReason.NoInboxItems,
                                block = { api.followedThreads(currentSlockApiPriority(), MESSAGE_PAGE_LIMIT) },
                                emptyWhen = { false },
                                source = "${source}_followed_summaries",
                                warningLogger = warningLogger,
                                mapper = { bodyText -> parseThreadListSnapshotFromBody(bodyText, context) }
                        )
                    }
                } else {
                    null
                }
        val messagesResult = messagesDeferred.await()
        val threadSummaries = threadSummariesDeferred.await()
        val followedSummaries = followedSummariesDeferred?.await()
        if (messagesResult !is DataResult.Content) {
            messagesResult
        } else {
            val channelThreads =
                    if (threadSummaries is DataResult.Content) threadSummaries.data.threads
                    else emptyList()
            val followedForChannel =
                    if (followedSummaries is DataResult.Content)
                            followedSummaries.data.threads.filter {
                                it.parentChannelId?.value == threadId.value
                            }
                    else emptyList()
            val mergedThreads =
                    (channelThreads + followedForChannel).distinctBy { it.key }
            if (threadSummaries is DataResult.Error && followedSummaries is DataResult.Error) {
                warningLogger(
                        "thread_summaries_error source=$source " +
                                "channel_code=${threadSummaries.error.code} " +
                                "followed_code=${followedSummaries.error.code}"
                )
                messagesResult
            } else {
                warningLogger(
                        "thread_summaries_loaded source=$source " +
                                "channel=${channelThreads.size} followed_filtered=${followedForChannel.size} " +
                                "merged=${mergedThreads.size} " +
                                "candidateIds=${mergedThreads.take(3).joinToString { t ->
                                    "${t.channelId.value.take(8)}/" +
                                            "${t.threadChannelId?.value?.take(8) ?: "null"}/" +
                                            "${t.parentMessageId?.value?.take(8) ?: "null"}"
                                }}"
                )
                DataResult.Content(
                        messagesResult.data.withReplySummaries(mergedThreads, warningLogger)
                )
            }
        }
    }

    private suspend fun threadAction(
            threadChannelId: ChannelId,
            source: String,
            block: suspend () -> ApiResponse
    ): DataResult<ThreadActionResult> =
            threadAction(
                    fallbackThreadChannelId = threadChannelId,
                    fallbackParentMessageId = null,
                    source = source,
                    block = block
            )

    private suspend fun threadAction(
            fallbackThreadChannelId: ChannelId?,
            fallbackParentMessageId: MessageId?,
            source: String,
            block: suspend () -> ApiResponse
    ): DataResult<ThreadActionResult> {
        currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoInboxItems,
                block = block,
                source = source,
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    val result =
                            ai.slock.compose.data.parseSharedThreadActionResult(
                                    bodyText = bodyText,
                                    fallbackThreadChannelId = fallbackThreadChannelId,
                                    fallbackParentMessageId = fallbackParentMessageId
                            ) ?: error("thread action response missing thread channel id")
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockThreadReplies",
                            "thread_action_result source=$source " +
                                    "threadChannelId=${result.threadChannelId.value} " +
                                    "fallback=${fallbackThreadChannelId?.value.orEmpty()} " +
                                    "parentMessageId=${fallbackParentMessageId?.value.orEmpty()} " +
                                    "replyCount=${result.replyCount}"
                    )
                    result
                }
        )
    }

    private suspend fun currentContext(): NetworkServerContext? {
        val session = store.readSession() ?: return null
        val selectedId = session.currentServerId ?: return null
        return session.toNetworkServerContext(ServerId(selectedId))
    }

    private companion object {
        const val MESSAGE_PAGE_LIMIT = 50
        const val SYNC_PAGE_LIMIT = 200
    }
}

object RealSlockApiFactory {
    fun create(
            store: AuthSessionStore,
            baseUrl: String = KtorAuthRepository.BASE_URL,
            tokenRefresher: AuthTokenRefresher =
                    AuthTokenRefresher(
                            store = store,
                            api = KtorAuthRepository.createAuthApi(baseUrl)
                    )
    ): SlockApi =
        ComposeSlockApiAdapter(
                store = store,
                baseUrl = baseUrl,
                tokenRefresher = tokenRefresher
        )
}

private data class NetworkServerContext(val session: AuthSession, val server: ServerSummary)

private suspend fun AuthSessionStore.toNetworkServerContext(
        serverId: ServerId
): NetworkServerContext? = readSession()?.toNetworkServerContext(serverId)

private fun AuthSession.toNetworkServerContext(serverId: ServerId): NetworkServerContext? {
    val selectedId = currentServerId ?: return null
    if (selectedId != serverId.value) return null
    val server = servers.firstOrNull { it.id == selectedId }?.toServerSummary() ?: return null
    return NetworkServerContext(session = this, server = server)
}

private fun <T> DataResult<*>.mapError(): DataResult<T> =
        when (this) {
            is DataResult.Empty -> this
            is DataResult.Error -> this
            is DataResult.NoPermission -> this
            is DataResult.Content -> error("Cannot map content as error.")
        }

private fun mergeDirectMessages(
        existing: List<ConversationSummary>,
        incoming: List<ConversationSummary>
): List<ConversationSummary> {
    val merged = LinkedHashMap<String, ConversationSummary>()
    for (conversation in existing) {
        merged[conversation.conversationCacheKey] = conversation
    }
    for (conversation in incoming) {
        val key = conversation.conversationCacheKey
        val previous = merged[key]
        merged.remove(key)
        merged[key] =
                if (previous == null) {
                    conversation
                } else {
                    conversation.withDirectMessageFallback(previous)
                }
    }
    return merged.values.toList()
}

private fun List<ConversationSummary>.dedupeHomeConversations(): List<ConversationSummary> {
    val deduped = LinkedHashMap<String, ConversationSummary>()
    for (conversation in this) {
        val key = conversation.conversationCacheKey
        deduped.remove(key)
        deduped[key] = conversation
    }
    return deduped.values.toList()
}

private val ConversationSummary.conversationCacheKey: String
        get() =
                if (kind == ConversationKind.Thread) threadId.value
                else channelId?.value ?: threadId.value

private fun ConversationSummary.cacheKey(): String? =
        if (kind == ConversationKind.Thread) threadId.value else channelId?.value ?: threadId.value

private fun ConversationSummary.mergeConversationSummary(
        incoming: ConversationSummary
): ConversationSummary =
        incoming.copy(
                name = incoming.name.takeUnless { it.isGenericConversationName(incoming) } ?: name,
                subtitle = incoming.subtitle ?: subtitle,
                avatarUrl = incoming.avatarUrl ?: avatarUrl,
                avatar = incoming.avatar ?: avatar,
                unreadCount =
                        when {
                            incoming.unreadCountKnown -> incoming.unreadCount
                            unreadCountKnown -> unreadCount
                            else -> incoming.unreadCount
                        },
                unreadCountKnown = incoming.unreadCountKnown || unreadCountKnown,
                mentionCount =
                        when {
                            incoming.mentionCountKnown -> incoming.mentionCount
                            mentionCountKnown -> mentionCount
                            else -> incoming.mentionCount
                        },
                mentionCountKnown = incoming.mentionCountKnown || mentionCountKnown,
                lastMessageId = incoming.lastMessageId ?: lastMessageId,
                firstUnreadMessageId = incoming.firstUnreadMessageId ?: firstUnreadMessageId,
                lastMessagePreview = incoming.lastMessagePreview ?: lastMessagePreview,
                lastMessageAt = incoming.lastMessageAt ?: lastMessageAt,
                lastMessageSenderName = incoming.lastMessageSenderName ?: lastMessageSenderName,
                presence = incoming.presence ?: presence,
                agentStatus = incoming.agentStatus ?: agentStatus,
                presenceMemberId = incoming.presenceMemberId ?: presenceMemberId,
                hasDraft = if (incoming.hasDraftKnown) incoming.hasDraft else hasDraft,
                hasDraftKnown = incoming.hasDraftKnown || hasDraftKnown,
                joined = incoming.joined || joined,
                archived = incoming.archived || archived,
                memberCount = incoming.memberCount ?: memberCount
        )

private fun String.isGenericConversationName(conversation: ConversationSummary): Boolean =
        isBlank() || this == conversation.threadId.value ||
                this == conversation.channelId?.value

private fun ConversationSummary.withMergedMemberCount(incoming: Int?): ConversationSummary =
        copy(memberCount = incoming ?: memberCount)

private fun ConversationSummary.withDirectMessageFallback(
        previous: ConversationSummary
): ConversationSummary =
        copy(
                name = name.takeUnless { it.isGenericConversationName(this) } ?: previous.name,
                subtitle = subtitle ?: previous.subtitle,
                avatarUrl = avatarUrl ?: previous.avatarUrl,
                avatar = avatar ?: previous.avatar,
                unreadCount =
                        when {
                            unreadCountKnown -> unreadCount
                            previous.unreadCountKnown -> previous.unreadCount
                            else -> unreadCount
                        },
                unreadCountKnown = unreadCountKnown || previous.unreadCountKnown,
                mentionCount =
                        when {
                            mentionCountKnown -> mentionCount
                            previous.mentionCountKnown -> previous.mentionCount
                            else -> mentionCount
                        },
                mentionCountKnown = mentionCountKnown || previous.mentionCountKnown,
                lastMessageId = lastMessageId ?: previous.lastMessageId,
                firstUnreadMessageId = firstUnreadMessageId ?: previous.firstUnreadMessageId,
                lastMessagePreview = lastMessagePreview ?: previous.lastMessagePreview,
                lastMessageAt = lastMessageAt ?: previous.lastMessageAt,
                lastMessageSenderName = lastMessageSenderName ?: previous.lastMessageSenderName,
                presence = presence ?: previous.presence,
                agentStatus = agentStatus ?: previous.agentStatus,
                presenceMemberId = presenceMemberId ?: previous.presenceMemberId,
                hasDraft = if (hasDraftKnown) hasDraft else previous.hasDraft,
                hasDraftKnown = hasDraftKnown || previous.hasDraftKnown,
                joined = joined || previous.joined,
                archived = archived || previous.archived,
                memberCount = memberCount ?: previous.memberCount
        )

private fun parseThreadListSnapshotFromBody(
        bodyText: String,
        context: NetworkServerContext
): ThreadListSnapshot {
    val parsed =
            ai.slock.compose.data.parseSharedThreadListItems(bodyText)
                    ?: throw IllegalStateException("Thread list response missing threads")
    return ThreadListSnapshot(
            server = context.server,
            threads = parsed.items,
            totalCount = parsed.totalCount,
            hasMore = parsed.hasMore,
            nextOffset = parsed.nextOffset
    )
}

private fun buildSharedThreadSnapshot(
        payload: ai.slock.compose.data.SharedMessagesPayload,
        threadId: ThreadId,
        canLoadMoreBefore: Boolean = false,
        canLoadMoreAfter: Boolean = false,
        highlightedMessageId: MessageId? = null
): ThreadSnapshot {
    val highlighted =
            highlightedMessageId?.let { id ->
                payload.messages.firstOrNull { it.id == id }
            }
    val baseConversation = buildDefaultConversation(threadId, threadId.value)
    val conversation =
            if (highlighted != null) {
                baseConversation.withThreadAnchor(
                        parentMessage = highlighted,
                        streamChannelId = ChannelId(threadId.value)
                )
            } else {
                baseConversation
            }
    return ThreadSnapshot(
            conversation = conversation,
            messages = payload.messages,
            parentMessage = highlighted,
            canLoadMoreBefore = canLoadMoreBefore,
            canLoadMoreAfter = canLoadMoreAfter,
            historyLimited = payload.historyLimited,
            highlightedMessageId = highlightedMessageId
    )
}

private fun buildDefaultConversation(threadId: ThreadId, name: String): ConversationSummary {
    val parentChannelId = threadId.parentChannelIdOrNull()
    val parentMessageId = threadId.parentMessageIdOrNull()
    return ConversationSummary(
            threadId = threadId,
            channelId = parentChannelId ?: ChannelId(threadId.value),
            thread =
                    if (parentChannelId != null && parentMessageId != null) {
                        ThreadConversation(
                                parentChannelId = parentChannelId,
                                parentMessageId = parentMessageId,
                                streamChannelId = null
                        )
                    } else null,
            kind =
                    if (parentChannelId != null && parentMessageId != null) {
                        ConversationKind.Thread
                    } else {
                        ConversationKind.Channel
                    },
            name = name
    )
}

private fun ThreadSnapshot.withReplySummaries(
        threads: List<InboxItem>,
        logger: (String) -> Unit
): ThreadSnapshot {
    if (threads.isEmpty()) return this
    val summariesByParentMessageId =
            threads
                    .asSequence()
                    .mapNotNull { thread -> thread.parentMessageId?.let { it.value to thread } }
                    .toMap()
    val summariesByThreadChannelId =
            threads
                    .asSequence()
                    .flatMap { thread ->
                        sequenceOf(
                                thread.threadChannelId?.value?.let { it to thread },
                                thread.channelId.value to thread
                        ).filterNotNull()
                    }
                    .toMap()
    var joinMissCount = 0
    var firstJoinMissMessageId: MessageId? = null
    var firstJoinMissThreadChannelId: ChannelId? = null
    val updatedMessages =
            messages.map { message ->
                val summary =
                        summariesByParentMessageId[message.id.value]
                                ?: message.threadChannelId?.value?.let(summariesByThreadChannelId::get)
                                ?: threads.firstOrNull { thread ->
                                    thread.matchesParentMessage(message.id)
                                }
                if (summary == null) {
                    if (message.replyCount == 0) {
                        joinMissCount += 1
                        if (firstJoinMissMessageId == null) {
                            firstJoinMissMessageId = message.id
                            firstJoinMissThreadChannelId = message.threadChannelId
                        }
                    }
                    return@map message
                }
                val summaryThreadChannelId = summary.threadChannelId ?: summary.channelId
                logger(
                        "summary_join parentMessageId=${message.id.value} " +
                                "channelId=${message.channelId?.value.orEmpty()} " +
                                "threadChannelId=${summaryThreadChannelId.value} " +
                                "replyCount=${maxOf(message.replyCount, summary.replyCount)} " +
                                "unreadCount=${summary.unreadCount} " +
                                "source=channel_threads"
                )
                message.copy(
                        threadChannelId = message.threadChannelId ?: summaryThreadChannelId,
                        replyCount = maxOf(message.replyCount, summary.replyCount)
                ).withUnreadReplyCount(summary.unreadCount)
            }
    if (joinMissCount > 0) {
        val candidates =
                threads.take(2).joinToString { thread ->
                    "ch=${thread.channelId.value.take(8)} " +
                            "pMsgId=${thread.parentMessageId?.value?.take(8) ?: "null"}"
                }
        logger(
                "summary_join_miss_count count=$joinMissCount " +
                        "firstMsgId=${firstJoinMissMessageId?.value?.take(8).orEmpty()} " +
                        "firstThreadCh=${firstJoinMissThreadChannelId?.value?.take(8) ?: "null"} " +
                        "threadCandidates=[$candidates] source=channel_threads"
        )
    }
    return copy(messages = updatedMessages)
}

private fun InboxItem.matchesParentMessage(messageId: MessageId): Boolean {
    val candidateIds =
            listOfNotNull(
                    parentMessageId?.value,
                    lastMessageId?.value,
                    latestActivityMessageId?.value,
                    parentMessagePreview?.extractSlockMessageReferenceId(),
                    latestActivityPreview?.extractSlockMessageReferenceId(),
                    lastMessagePreview?.extractSlockMessageReferenceId(),
                    threadChannelId?.value,
                    channelId.value,
                    key.removePrefix("thread:").takeIf { it != key }
            )
    return candidateIds.any { candidate -> messageId.value.matchesThreadSummaryId(candidate) }
}

private fun String.extractSlockMessageReferenceId(): String? =
        Regex("#[A-Za-z0-9_-]+:([A-Fa-f0-9]{8})(?:[A-Fa-f0-9-]{0,28})?")
                .find(this)
                ?.groupValues
                ?.getOrNull(1)

private fun String.matchesThreadSummaryId(candidate: String): Boolean {
    if (equals(candidate, ignoreCase = true)) return true
    val parentShortId = take(8)
    val candidateShortId = candidate.take(8)
    return parentShortId.length == 8 &&
            candidateShortId.length == 8 &&
            parentShortId.equals(candidateShortId, ignoreCase = true)
}



private fun String?.toPresence(fallback: Presence = Presence.Offline): Presence =
        toPresenceOrNull() ?: fallback

private fun String?.toPresenceOrNull(): Presence? =
        toAgentStatusOrNull()?.presence
                ?: when (this?.lowercase()?.replace('-', '_')) {
            "active", "online", "working", "available", "connected" -> Presence.Active
            "away", "idle", "busy" -> Presence.Away
            "offline", "inactive", "disconnected", "not_connected", "notconnected" ->
                    Presence.Offline
            else -> null
        }

private fun String?.toAgentStatusOrNull(): AgentStatus? = AgentStatus.fromWireValue(this)




private fun ConversationSummary.withThreadAnchor(
        parentMessage: Message?,
        streamChannelId: ChannelId,
        parentChannelName: String? = null
): ConversationSummary {
    fun ConversationSummary.withParentNameFallback(): ConversationSummary {
        val fallbackName =
                parentChannelName?.takeIf { name.isBlank() || name == threadId.value || name == channelId?.value }
                        ?: return this
        return copy(name = fallbackName)
    }
    if (kind == ConversationKind.Thread && thread?.streamChannelId == null) {
        return copy(
                thread = thread?.copy(streamChannelId = streamChannelId)
        ).withParentNameFallback()
    }
    val parentChannelId = parentMessage?.channelId ?: return withParentNameFallback()
    return copy(
            threadId = threadIdentity(parentChannelId, parentMessage.id),
            channelId = parentChannelId,
            thread =
                    ThreadConversation(
                            parentChannelId = parentChannelId,
                            parentMessageId = parentMessage.id,
                            streamChannelId = parentMessage.threadChannelId ?: streamChannelId
                    ),
            kind = ConversationKind.Thread
    ).withParentNameFallback()
}

private fun ChannelId.toThreadActionBody(): RequestBody =
        mapOf("threadChannelId" to value, "channelId" to value).toJsonBody()

private fun MessageId.toParentMessageThreadBody(): RequestBody =
        mapOf("parentMessageId" to value).toJsonBody()

private fun sendMessageBody(
        channelId: ChannelId,
        body: String,
        attachmentIds: List<AttachmentId>,
        asTask: Boolean
): RequestBody =
        mapOf(
                        "channelId" to channelId.value,
                        "content" to body,
                        "attachmentIds" to attachmentIds.map { it.value },
                        "asTask" to asTask
                )
                .toJsonBody()

private fun AttachmentUploadFile.toMultipartPart(): MultipartBody.Part {
    val body = bytes.toRequestBody((mimeType ?: "application/octet-stream").toMediaTypeOrNull())
    return MultipartBody.Part.createFormData("files", fileName, body)
}

private fun Map<String, Any?>.toJsonBody(): RequestBody =
        Gson().toJson(this).toRequestBody(jsonMediaType)

private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
private val textMediaType = "text/plain; charset=utf-8".toMediaType()
