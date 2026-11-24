package build.raft.app

import build.raft.app.data.RaftClientConfigAppHosts
import build.raft.app.links.toParsedRaftPermalink
import build.raft.app.assistant.LocalThreadAssistantPanelEmbed
import build.raft.app.resources.*
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.map
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.SharedComputersIntent
import build.raft.app.data.SharedComputersPickerSlice
import build.raft.app.data.SharedComputersStore
import build.raft.app.data.SharedCreateAgentFormStore
import build.raft.app.data.PendingMentionAction
import build.raft.app.data.PendingMentionActionExecuteResult
import build.raft.app.data.SharedMembersChannelSuggestionSlice
import build.raft.app.data.SharedMembersMemberSuggestionSlice
import build.raft.app.model.ConversationKind
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.TaskStatus
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import build.raft.designsystem.components.LocalRaftPaneGridHeadTabs
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.downloads.RaftBridgeSaveAttachmentTask
import build.raft.app.downloads.RaftDownloadCenter
import build.raft.app.downloads.RaftDownloadCenterProcess
import build.raft.app.downloads.RaftDownloadDescriptor
import build.raft.app.downloads.RaftDownloadOutcome
import build.raft.app.downloads.raftDownloadCenterEntryId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.Channel

internal data class ThreadAttachmentOpenResult(
    val status: String,
    /** The host's own words (platform English), for the log only: the UI maps [status]. */
    val message: String = "",
    /** The host's failure category (`http_status`, `storage_publish`...), for the log. */
    val errorKind: String = "",
    val traceId: String? = null,
    /** Host location of the saved file (e.g. desktop `file:` URI or Android `content://` URI), when known. */
    val localUri: String? = null,
    /** True when the transfer ran through [RaftDownloadCenter]. */
    val handledByDownloadCenter: Boolean = false,
)

@Composable
internal fun ThreadCreateAgentActionDialogHost(
    dialogState: ThreadCreateAgentActionDialogState,
    computersStore: SharedComputersStore?,
    createAgentFormStore: SharedCreateAgentFormStore?,
    computerServerId: String?,
    computerServerResolved: Boolean,
    onAuthRequired: (String?) -> Boolean,
    onAddComputer: (() -> Unit)?,
    onDismiss: () -> Unit,
    onSubmit: (SharedCreateAgentDialogSubmit) -> Unit
) {
    val emptyComputerPickerSliceFlow = remember { MutableStateFlow(SharedComputersPickerSlice()) }
    val computerPickerSlice by remember(computersStore, computerServerId) {
        computersStore?.computerPickerSliceFlow(computerServerId) ?: emptyComputerPickerSliceFlow
    }.collectAsState(SharedComputersPickerSlice())

    LaunchedEffect(
        computersStore,
        dialogState.messageId,
        computerServerId,
        computerServerResolved
    ) {
        if (!computerServerResolved) return@LaunchedEffect
        val serverId = computerServerId
        if (serverId.isNullOrBlank()) return@LaunchedEffect
        computersStore?.dispatch(
            SharedComputersIntent.EnsureComputerPicker(
                serverId = serverId,
                priority = DataRequestPriority.Visible
            )
        )
    }

    LaunchedEffect(dialogState.messageId, computerPickerSlice.errorCode) {
        val code = computerPickerSlice.errorCode ?: return@LaunchedEffect
        onAuthRequired(code)
    }

    val machinesErrorCode =
        when {
            computersStore != null &&
                computerServerResolved &&
                computerServerId.isNullOrBlank() -> "server_id_missing"
            computerPickerSlice.initialLoadError != null -> computerPickerSlice.errorCode
            else -> null
        }
    SharedCreateAgentDialog(
        submitting = dialogState.submitting,
        errorMessage =
            dialogState.errorCode?.let {
                threadCreateAgentDialogErrorCopy(it, dialogState.errorServerMessage)
            },
        initialName = dialogState.card.name.removePrefix("@"),
        initialDescription = dialogState.card.description.orEmpty(),
        draftHint = dialogState.card.draftHint,
        suggestedComputer = dialogState.card.suggestedComputer,
        requiredComputer = dialogState.card.requiredComputer,
        suggestedRuntime = dialogState.card.suggestedRuntime,
        machines = computerPickerSlice.machines,
        machinesLoading =
            computersStore != null &&
                (!computerServerResolved ||
                    computerPickerSlice.initialLoadInFlight),
        machinesErrorMessage = machinesErrorCode?.let { threadCreateAgentMachinesErrorCopy(it) },
        onMachinesRetry = {
            val serverId = computerServerId
            if (!serverId.isNullOrBlank()) {
                computersStore?.dispatch(
                    SharedComputersIntent.RefreshComputerPicker(
                        serverId = serverId,
                        priority = DataRequestPriority.Visible
                    )
                )
            }
        },
        createAgentFormStore = createAgentFormStore,
        createAgentServerId = computerServerId,
        onAddComputer = onAddComputer,
        onDismiss = onDismiss,
        onSubmit = onSubmit
    )
}

internal suspend fun openSharedThreadAttachment(
    bridge: build.raft.app.base.BridgeModule,
    dataSource: SharedThreadDataSource,
    attachment: RaftThreadAttachmentItem,
    owner: AttachmentOwnerLease,
    downloadId: String? = null,
    share: Boolean = false,
    onProgress: ((build.raft.app.base.BridgeAttachmentDownloadProgress) -> Unit)? = null
): ThreadAttachmentOpenResult {
    val attachmentId = attachment.id.takeIf { it.isNotBlank() }
        ?: return ThreadAttachmentOpenResult(status = "failed")
    return when (val response = dataSource.attachmentUrl(DataRequestPriority.Visible, attachmentId, disposition = "attachment")) {
        is ThreadApiResult.Success -> {
            val nativeResult =
                bridge.openAttachment(
                    url = response.data.url,
                    attachmentId = attachmentId,
                    fileName = attachment.name.ifBlank { "attachment-${attachmentId.take(8)}" },
                    owner = owner,
                    mimeType = attachment.mimeType,
                    downloadId = downloadId,
                    share = share,
                    onProgress = onProgress
                )
            val nativeErrorKind = nativeResult?.optString("errorKind").orEmpty()
            val status = raftThreadAttachmentOpenStatus(nativeResult?.optString("status").orEmpty())
            ThreadAttachmentOpenResult(
                status = status,
                errorKind = nativeErrorKind,
                traceId = null
            )
        }
        is ThreadApiResult.Failure ->
            ThreadAttachmentOpenResult(status = "failed", traceId = response.traceId)
    }
}

/**
 * The download-centre descriptor for a thread/Files/lightbox attachment, sharing the exact
 * [raftDownloadCenterEntryId] key as `FileDetailPage` (`RaftFilePreview`) so in-flight progress,
 * desktop download bar rows, and saved-file state stay unified across surfaces.
 */
internal fun RaftThreadAttachmentItem.threadAttachmentDownloadDescriptor(
    owner: AttachmentOwnerLease,
): RaftDownloadDescriptor? {
    val attachmentId = id.takeIf { it.isNotBlank() } ?: return null
    val fileName = name.ifBlank { "attachment-${attachmentId.take(8)}" }
    return RaftDownloadDescriptor(
        id = raftDownloadCenterEntryId(
            ownerKey = owner.principalKey.value,
            attachmentId = attachmentId,
            fileName = fileName,
        ),
        ownerKey = owner.principalKey.value,
        attachmentId = attachmentId,
        fileName = fileName,
        mimeType = mimeType.takeIf { it.isNotBlank() },
        totalBytes = sizeBytes.takeIf { it > 0L },
        accountKey = owner.accountKey,
    )
}

internal fun RaftDownloadOutcome.toThreadAttachmentSaveResult(): ThreadAttachmentOpenResult =
    when (this) {
        is RaftDownloadOutcome.Completed ->
            ThreadAttachmentOpenResult(
                status = "saved",
                localUri = localUri,
                handledByDownloadCenter = true,
            )
        is RaftDownloadOutcome.Cancelled ->
            ThreadAttachmentOpenResult(
                status = "cancelled",
                handledByDownloadCenter = true,
            )
        is RaftDownloadOutcome.Failed ->
            ThreadAttachmentOpenResult(
                status =
                    when (hostStatus) {
                        "storage_failed", "download_failed" -> hostStatus
                        else -> "failed"
                    },
                message = message.orEmpty(),
                errorKind = errorKind.orEmpty(),
                traceId = traceId,
                handledByDownloadCenter = true,
            )
    }

internal suspend fun saveSharedThreadAttachment(
    bridge: build.raft.app.base.BridgeModule,
    dataSource: SharedThreadDataSource,
    attachment: RaftThreadAttachmentItem,
    owner: AttachmentOwnerLease,
    downloadId: String? = null,
    onProgress: ((build.raft.app.base.BridgeAttachmentDownloadProgress) -> Unit)? = null,
    downloadCenter: RaftDownloadCenter = RaftDownloadCenterProcess.center,
): ThreadAttachmentOpenResult {
    val descriptor =
        attachment.threadAttachmentDownloadDescriptor(owner)
            ?: return ThreadAttachmentOpenResult(status = "failed", message = "attachment_id_missing")
    if (!ProcessAttachmentAuthScopes.allows(owner)) {
        return ThreadAttachmentOpenResult(status = "cancelled")
    }
    downloadCenter.activeResult(descriptor.id)?.let { active ->
        return active.await().toThreadAttachmentSaveResult()
    }
    val attachmentId = descriptor.attachmentId
    val fileName = descriptor.fileName
    val initialUrl =
        when (val response = dataSource.attachmentUrl(DataRequestPriority.Visible, attachmentId, disposition = "attachment")) {
            is ThreadApiResult.Success ->
                response.data.url.takeIf { it.isNotBlank() }
                    ?: return ThreadAttachmentOpenResult(status = "failed", message = "attachment_url_empty")
            is ThreadApiResult.Failure ->
                return ThreadAttachmentOpenResult(
                    status = "failed",
                    message = "attachment_url_failed code=${response.code}",
                    traceId = response.traceId,
                )
        }
    if (!ProcessAttachmentAuthScopes.allows(owner)) {
        return ThreadAttachmentOpenResult(status = "cancelled")
    }
    val baseDownloadId = downloadId?.takeIf { it.isNotBlank() } ?: "thread_attachment:$attachmentId:$fileName"
    // App-scoped download centre (matching RaftFilePreview): leaving the thread keeps the transfer
    // alive, drives the desktop download bar and Settings › Storage, and shares saved-file state
    // with FileDetailPage under the same descriptor id.
    var livePageProgress = onProgress
    val outcome =
        try {
            downloadCenter.start(
                descriptor = descriptor,
                task =
                    RaftBridgeSaveAttachmentTask(
                        bridge = bridge,
                        owner = owner,
                        attachmentId = attachmentId,
                        fileName = fileName,
                        mimeType = descriptor.mimeType,
                        initialUrl = initialUrl,
                        downloadIdForAttempt = { attempt ->
                            if (attempt <= 1) baseDownloadId else "$baseDownloadId:retry$attempt"
                        },
                        resolveUrl = resolveUrl@{
                            if (!ProcessAttachmentAuthScopes.allows(owner)) return@resolveUrl null
                            try {
                                when (
                                    val refreshed =
                                        dataSource.attachmentUrl(
                                            DataRequestPriority.Visible,
                                            attachmentId,
                                            disposition = "attachment",
                                        )
                                ) {
                                    is ThreadApiResult.Success -> refreshed.data.url.takeIf { it.isNotBlank() }
                                    is ThreadApiResult.Failure -> null
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                null
                            }
                        },
                        onPageProgress = { progress -> livePageProgress?.invoke(progress) },
                    ),
            ).await()
        } finally {
            livePageProgress = null
        }
    return outcome.toThreadAttachmentSaveResult()
}

/**
 * Saves a conversation image into the device's photo library (Android MediaStore Images, iOS
 * PhotoKit) as the phone viewer's Save to Photos does (RaftFilePreview): the image's own file,
 * `attachments/{id}/url` with disposition=attachment, handed to the host's `saveImageToPhotos`.
 * For the wide lightbox's Save to Photos by touch (#305). A failed address request is `failed`
 * with its trace ID; otherwise the host's status word (`saved`, `cancelled`, `permission_denied`,
 * `unsupported_image`, ...), which [raftAttachmentSaveFailure] reads for Photos.
 */
internal suspend fun saveSharedThreadImageToPhotos(
    bridge: build.raft.app.base.BridgeModule,
    dataSource: SharedThreadDataSource,
    attachment: RaftThreadAttachmentItem,
    owner: AttachmentOwnerLease,
    downloadId: String? = null,
): ThreadAttachmentOpenResult {
    val attachmentId = attachment.id.takeIf { it.isNotBlank() }
        ?: return ThreadAttachmentOpenResult(status = "failed", message = "attachment_id_missing")
    val url =
        when (val response = dataSource.attachmentUrl(DataRequestPriority.Visible, attachmentId, disposition = "attachment")) {
            is ThreadApiResult.Success ->
                response.data.url.takeIf { it.isNotBlank() }
                    ?: return ThreadAttachmentOpenResult(status = "failed", message = "attachment_url_empty")
            is ThreadApiResult.Failure ->
                return ThreadAttachmentOpenResult(
                    status = "failed",
                    message = "attachment_url_failed code=${response.code}",
                    traceId = response.traceId,
                )
        }
    val result =
        bridge.saveImageToPhotos(
            url = url,
            attachmentId = attachmentId,
            fileName = attachment.name.ifBlank { "attachment-${attachmentId.take(8)}" },
            owner = owner,
            mimeType = attachment.mimeType.takeIf { it.isNotBlank() },
            downloadId = downloadId,
        )
    return ThreadAttachmentOpenResult(
        status = result?.optString("status").orEmpty(),
        message = result?.optString("message").orEmpty(),
        errorKind = result?.optString("errorKind").orEmpty(),
    )
}

internal sealed interface ThreadAttachmentSaveToast {
    data class Info(val message: String) : ThreadAttachmentSaveToast

    data class Error(val message: String, val traceId: String?) : ThreadAttachmentSaveToast
}

/**
 * The toast shown after [saveSharedThreadAttachment] finishes, or null when no toast should fire.
 *
 * On desktop ([hasDesktopDownloadBar] true, i.e. `raftPlatformSavedFileHost() != null`),
 * `RaftDesktopDownloadBar` already shows `"Saved to <Folder>"` + Show in Finder (or `"Downloaded"`)
 * for centre-handled saves, so no second generic saved toast is fired — matching `RaftFilePreview`.
 * Mobile platforms keep the saved toast, and failures keep their error toasts with [ThreadAttachmentOpenResult.traceId].
 */
internal fun raftThreadAttachmentSaveToast(
    result: ThreadAttachmentOpenResult,
    hasDesktopDownloadBar: Boolean,
    savedMessage: String,
    fallbackSavedMessage: String,
    downloadFailedMessage: String,
    storageUnavailableMessage: String,
    unableToDownloadMessage: String,
): ThreadAttachmentSaveToast? =
    when (result.status) {
        "saved" ->
            if (hasDesktopDownloadBar && result.handledByDownloadCenter) {
                null
            } else {
                ThreadAttachmentSaveToast.Info(
                    raftThreadAttachmentSavedMessage(
                        nativeMessage = savedMessage,
                        fallbackMessage = fallbackSavedMessage,
                    )
                )
            }
        "cancelled" -> null
        else ->
            when (
                raftAttachmentSaveFailure(result.status, RaftAttachmentSaveDestination.Downloads)
                    ?: RaftAttachmentSaveFailure.UnableToDownload
            ) {
                RaftAttachmentSaveFailure.DownloadFailed ->
                    ThreadAttachmentSaveToast.Error(downloadFailedMessage, result.traceId)
                RaftAttachmentSaveFailure.StorageUnavailable ->
                    ThreadAttachmentSaveToast.Info(storageUnavailableMessage)
                else ->
                    ThreadAttachmentSaveToast.Error(unableToDownloadMessage, result.traceId)
            }
    }

internal fun raftThreadAttachmentSavedMessage(
    nativeMessage: String,
    fallbackMessage: String
): String = nativeMessage.trim().ifBlank { fallbackMessage }

/**
 * A tapped message-body link: the [url] to open and, when it is a message link, its in-app [target].
 * [conversationLink]: the conversation it names on one of Raft's origins ([RaftOwnLinkRoute.Conversation]:
 * with or without a message, a task's own conversation, another server's), which the app shell can
 * resolve when the conversation is not one this page already knows.
 */
internal data class RaftMarkdownUrlOpen(
    val url: String,
    val target: RaftThreadWebLinkTarget?,
    val conversationLink: RaftMessageLink? = null,
)

/**
 * How a tapped message-body link opens. A Raft Desktop link (`raft:///s/…`, `raft://s/…`) is first
 * turned into its web link on [webUrl], the Developer environment's web URL (AGENTS.md
 * "Environment Web Link URL Rule"), and then opens exactly as that web link does: a conversation
 * link in-app ([raftOwnLinkRoute]), any other Raft page as [RaftLinkNavigator] opens it. Every other
 * link is unchanged.
 */
internal fun raftMarkdownUrlOpen(
    rawUrl: String,
    webUrl: String?,
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
): RaftMarkdownUrlOpen {
    val webLink = raftSchemeLinkToWebLink(rawUrl, webUrl)
    val url = webLink ?: rawUrl
    val route = raftOwnLinkRoute(url, webUrl, appHosts)
    // The web's task panel (`task=`): its web page, never the bare channel under it.
    if (route == RaftOwnLinkRoute.WebPage(RaftOwnLinkWebPageReason.TaskPanel)) return RaftMarkdownUrlOpen(url, target = null)
    val target = url.toRaftThreadWebLinkTarget(configuredWebUrl = webUrl, appHosts = appHosts)
    return RaftMarkdownUrlOpen(url, target, (route as? RaftOwnLinkRoute.Conversation)?.link)
}

/**
 * Whether a tapped conversation link opens on this page's own path ([RaftThreadWebLinkTarget],
 * the conversation directory) rather than through the app shell: a legacy link with no server
 * ([RaftMarkdownUrlOpen.conversationLink] null), or one whose conversation this server's directory
 * already has ([knownHere]: a channel or DM of this server; a task's conversation, another server's
 * channel and one not loaded yet are not in it). The shell reads the conversation first, as the web's
 * channel route does, so a task's conversation opens as the task (slock #14960).
 */
internal fun RaftMarkdownUrlOpen.opensOnThisPage(knownHere: (channelId: String) -> Boolean): Boolean {
    val local = target ?: return false
    return conversationLink == null || knownHere(local.channelId)
}

internal fun String.toRaftThreadWebLinkTarget(
    configuredWebUrl: String? = null,
    appHosts: Collection<String> = RaftClientConfigAppHosts.current,
): RaftThreadWebLinkTarget? {
    toParsedRaftPermalink(configuredWebUrl = configuredWebUrl, appHosts = appHosts)?.let { target ->
        return RaftThreadWebLinkTarget(
            channelId = target.channelId.value,
            messageId = target.messageId.value,
            routeKind =
                if (target.routeKind == ConversationKind.DirectMessage) {
                    RaftThreadSurfaceKind.DirectMessage
                } else {
                    RaftThreadSurfaceKind.Channel
                },
            threadParentMessageId = target.threadParentMessageId?.value
        )
    }

    // Compatibility for pre-workspace and channel-only links. New message-chip
    // and preview callers use the canonical typed parser above.
    val trimmed = trim()
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = trimmed.substring(0, schemeEnd).lowercase()
    if (scheme != "https" && scheme != "http") return null
    val withoutScheme = trimmed.substring(schemeEnd + 3)
    val pathStart = withoutScheme.indexOf('/')
    val host = if (pathStart >= 0) withoutScheme.substring(0, pathStart) else withoutScheme
    if (host.substringBefore(':').lowercase().trimEnd('.') !in setOf("app.slock.ai", "staging.slock.ai", "app.raft.build")) return null
    val pathAndQuery = if (pathStart >= 0) withoutScheme.substring(pathStart) else ""
    val path = pathAndQuery.substringBefore('?')
    val query = pathAndQuery.substringAfter('?', missingDelimiterValue = "")
    val segments = path.split('/').filter { it.isNotBlank() }
    if (segments.firstOrNull() == "s") return null
    val routeIndex = segments.indexOfFirst {
        val segment = it.lowercase()
        segment == RaftThreadSurfaceKind.Channel || segment == RaftThreadSurfaceKind.DirectMessage
    }
    if (routeIndex < 0) return null
    val routeKind = segments.getOrNull(routeIndex)?.lowercase()
        ?.takeIf { it == RaftThreadSurfaceKind.Channel || it == RaftThreadSurfaceKind.DirectMessage }
        ?: return null
    val channelId = segments.getOrNull(routeIndex + 1)?.takeIf { it.isNotBlank() } ?: return null
    val threadParam = query.queryParamValue("thread")
    val threadParentMessageId =
        threadParam?.split(':', limit = 2)
            ?.takeIf { it.size == 2 && it[0] == channelId && it[1].isNotBlank() }
            ?.get(1)
            ?: if (threadParam == null) {
                null
            } else {
                return null
            }
    val messageId = query.queryParamValue("msg")?.takeIf { it.isNotBlank() } ?: threadParentMessageId
    return RaftThreadWebLinkTarget(
        channelId = channelId,
        messageId = messageId,
        routeKind = routeKind,
        threadParentMessageId = threadParentMessageId
    )
}

private fun String.queryParamValue(name: String): String? =
    split('&')
        .firstNotNullOfOrNull { part ->
            val pieces = part.split('=', limit = 2)
            pieces.getOrNull(1)?.takeIf { pieces.firstOrNull() == name && it.isNotBlank() }
        }
        ?.raftPercentDecode()

private fun String.raftPercentDecode(): String {
    if ('%' !in this && '+' !in this) return this
    val builder = StringBuilder(length)
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char == '%' && index + 2 < length) {
            val hex = substring(index + 1, index + 3)
            val decoded = hex.toIntOrNull(16)
            if (decoded != null) {
                builder.append(decoded.toChar())
                index += 3
                continue
            }
        }
        builder.append(if (char == '+') ' ' else char)
        index += 1
    }
    return builder.toString()
}

internal fun RaftThreadScreenPayload.commentHostLoadingPayload(
    ref: RaftThreadMessageCommentRef
): RaftThreadScreenPayload? {
    if (!ref.hasThreadCommentHostSource()) return null
    val source = ref.hostSource ?: return null
    val hostMessageId = ref.hostMessageId?.takeIf { it.isNotBlank() } ?: return null
    val rootThreadId = source.rootThreadChannelId?.takeIf { it.isNotBlank() }
    val threadStreamId = source.threadChannelId?.takeIf { it.isNotBlank() } ?: rootThreadId
    val opensReplyThread = rootThreadId != null || source.type == RaftThreadSurfaceKind.ReplyThread
    if (opensReplyThread) {
        val parentMessageId =
            source.parentMessageId?.takeIf { it.isNotBlank() }
                ?: rootThreadId?.let { hostMessageId }
                ?: return null
        val streamId = threadStreamId
        return copy(
            instanceId = streamId?.let { "shared-shell-thread-$it" } ?: "shared-shell-thread-pending-$parentMessageId",
            title = ThreadReplyPageTitle,
            subtitle = replyThreadHeaderSubtitle(),
            status = "loading",
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            sourceThreadId = streamId,
            streamChannelId = streamId,
            parentChannelId = source.channelId,
            parentMessageId = parentMessageId,
            parentSurfaceKind = RaftThreadSurfaceKind.Channel,
            navigationLabel = "Close",
            selectedTab = RaftThreadTab.Chat.wireValue,
            showModeTabs = false,
            showOpenInChannelAction = true,
            originMessage = null,
            originPreview = null,
            originParentMessageId = parentMessageId,
            focusedMessageId = hostMessageId,
            originStatus = "loading",
            originErrorCode = null,
            messages = emptyList(),
            canLoadMoreBefore = false,
            canLoadMoreAfter = false,
            errorCode = null,
            composer =
                buildThreadSurfaceComposerPayload(
                    surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                    displayName = ThreadReplyPageTitle,
                    fallbackId = parentMessageId,
                    enabled = canSend,
                    mentionChannelId = source.channelId,
                    parentMessageId = parentMessageId,
                    streamChannelId = streamId
                )
        )
    }
    val surfaceKind =
        if (source.routeKind == RaftThreadSurfaceKind.DirectMessage) {
            RaftThreadSurfaceKind.DirectMessage
        } else {
            RaftThreadSurfaceKind.Channel
        }
    return copy(
        instanceId = "shared-shell-thread-${source.channelId}",
        title = SharedThreadHeaderContextStore.launchContext(surfaceKind, null).title,
        subtitle = null,
        status = "loading",
        surfaceKind = surfaceKind,
        sourceThreadId = source.channelId,
        streamChannelId = source.channelId,
        parentChannelId = null,
        parentMessageId = null,
        selectedTab = RaftThreadTab.Chat.wireValue,
        messages = emptyList(),
        originMessage = null,
        originPreview = null,
        originParentMessageId = null,
        focusedMessageId = hostMessageId,
        originStatus = "none",
        originErrorCode = null,
        errorCode = null,
        composer =
            RaftThreadComposerPayload(
                surfaceKind = surfaceKind,
                enabled = canSend,
                streamChannelId = source.channelId
            )
    ).withLaunchHeaderContext()
}

private fun RaftThreadScreenPayload.findThreadImageAttachmentGroup(attachmentId: String): ThreadImageAttachmentGroup? {
    val messageCandidates = (listOfNotNull(originMessage) + messages).distinctBy { it.id }
    messageCandidates.forEach { message ->
        val images = message.attachments.filter { it.isImageAttachment() }
        val currentIndex = images.indexOfFirst { it.id == attachmentId }
        if (currentIndex >= 0) {
            return ThreadImageAttachmentGroup(images = images, currentIndex = currentIndex)
        }
    }
    return null
}

private fun RaftThreadScreenPayload.findThreadAttachment(attachmentId: String): RaftThreadAttachmentItem? =
    (listOfNotNull(originMessage) + messages)
        .asSequence()
        .flatMap { it.attachments.asSequence() }
        .firstOrNull { it.id == attachmentId }

internal fun List<RaftPendingAttachmentItem>.composerPendingImageAttachmentGroup(
    attachmentId: String
): ThreadImageAttachmentGroup? {
    val cleanAttachmentId = attachmentId.trim()
    if (cleanAttachmentId.isBlank()) return null
    val images =
        asSequence()
            .filter { attachment ->
                attachment.status == "ready" &&
                    attachment.isComposerImageAttachment() &&
                    attachment.previewUri?.isNotBlank() == true
            }
            .map { attachment ->
                RaftThreadAttachmentItem(
                    id = attachment.id,
                    name = attachment.fileName.ifBlank { "Attachment" },
                    mimeType = attachment.mimeType.orEmpty(),
                    sizeLabel = attachment.sizeLabel,
                    sizeBytes = attachment.sizeBytes,
                    thumbnailUrl = attachment.previewUri
                )
            }
            .toList()
    val currentIndex = images.indexOfFirst { it.id == cleanAttachmentId }
    return if (currentIndex >= 0) {
        ThreadImageAttachmentGroup(images = images, currentIndex = currentIndex)
    } else {
        null
    }
}


internal fun RaftThreadScreenPayload.replyThreadLoadingPayload(
    parentMessageId: String,
    parentChannelId: String?,
    threadChannelId: String?,
    focusedMessageId: String? = null
): RaftThreadScreenPayload {
    val knownParentMessageId = parentMessageId.takeIf { it.isNotBlank() }
    val knownThreadChannelId = threadChannelId?.takeIf { it.isNotBlank() }
    val resolvedParentSurfaceKind =
        when {
            surfaceKind == RaftThreadSurfaceKind.DirectMessage -> RaftThreadSurfaceKind.DirectMessage
            channelKind.equals("dm", ignoreCase = true) -> RaftThreadSurfaceKind.DirectMessage
            memberScopedMentions && parentChannelId.isNullOrBlank() -> RaftThreadSurfaceKind.DirectMessage
            else -> RaftThreadSurfaceKind.Channel
        }
    val resolvedParentChannelKind =
        if (resolvedParentSurfaceKind == RaftThreadSurfaceKind.Channel) {
            channelKind.normalizedThreadChannelKind()
        } else {
            null
        }
    val composer =
        buildThreadSurfaceComposerPayload(
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            displayName = ThreadReplyPageTitle,
            fallbackId = knownParentMessageId.orEmpty(),
            enabled = canSend,
            mentionChannelId = parentChannelId,
            parentMessageId = knownParentMessageId,
            streamChannelId = knownThreadChannelId
        )
    return copy(
        instanceId = knownThreadChannelId?.let { "shared-shell-thread-$it" } ?: "shared-shell-thread-pending-$parentMessageId",
        title = ThreadReplyPageTitle,
        subtitle = replyThreadHeaderSubtitle(),
        status = "loading",
        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
        sourceThreadId = knownThreadChannelId,
        streamChannelId = knownThreadChannelId,
        parentChannelId = parentChannelId,
        parentMessageId = knownParentMessageId,
        parentSurfaceKind = resolvedParentSurfaceKind,
        parentChannelKind = resolvedParentChannelKind,
        parentNavigationHint = ThreadParentNavigationPrevious,
        focusedMessageId = focusedMessageId?.takeIf { it.isNotBlank() },
        navigationLabel = "Close",
        selectedTab = "chat",
        showModeTabs = false,
        showOpenInChannelAction = true,
        originMessage = null,
        originParentMessageId = knownParentMessageId,
        originStatus = "none",
        messages = emptyList(),
        canLoadMoreBefore = false,
        canLoadMoreAfter = false,
        emptyTitle = "Loading...",
        emptyDetail = "",
        composer = composer
    )
}

internal fun RaftThreadScreenPayload.parentMessageContextLoadingPayload(
    parentMessageId: String
): RaftThreadScreenPayload {
    val focusedParentMessageId = parentMessageId.takeIf { it.isNotBlank() }
        ?: originParentMessageId?.takeIf { it.isNotBlank() }
        ?: this.parentMessageId?.takeIf { it.isNotBlank() }
    val parentThreadId = parentChannelId?.takeIf { it.isNotBlank() }
    val parentSurfaceKind =
        this.parentSurfaceKind.parsedThreadParentSurfaceKindOrNull()
            ?: if (channelKind.equals("dm", ignoreCase = true) || memberScopedMentions && parentThreadId == null) {
                RaftThreadSurfaceKind.DirectMessage
            } else {
                RaftThreadSurfaceKind.Channel
            }
    val resolvedParentChannelKind =
        if (parentSurfaceKind == RaftThreadSurfaceKind.Channel) {
            parentChannelKind.normalizedThreadChannelKind()
                ?: channelKind.normalizedThreadChannelKind()
                ?: RaftThreadSurfaceKind.Channel
        } else {
            RaftThreadSurfaceKind.DirectMessage
        }
    return copy(
        instanceId = parentThreadId?.let { "shared-shell-thread-$it" }
            ?: focusedParentMessageId?.let { "shared-shell-parent-message-$it" }
            ?: "shared-shell-parent-message",
        title = SharedThreadHeaderContextStore.launchContext(parentSurfaceKind, null).title,
        subtitle = null,
        status = "loading",
        surfaceKind = parentSurfaceKind,
        channelKind = resolvedParentChannelKind,
        sourceThreadId = parentThreadId,
        streamChannelId = parentThreadId,
        parentChannelId = null,
        parentMessageId = null,
        parentSurfaceKind = null,
        parentChannelKind = null,
        originParentMessageId = null,
        focusedMessageId = focusedParentMessageId,
        navigationLabel = "Back",
        selectedTab = RaftThreadTab.Chat.wireValue,
        showModeTabs = true,
        showOpenInChannelAction = false,
        originMessage = null,
        originPreview = null,
        originStatus = "none",
        originErrorCode = null,
        messages = emptyList(),
        canLoadMoreBefore = false,
        canLoadMoreAfter = false,
        errorCode = null,
        composer =
            RaftThreadComposerPayload(
                surfaceKind = parentSurfaceKind,
                enabled = canSend,
                streamChannelId = parentThreadId
            ),
        emptyTitle = "Loading...",
        emptyDetail = ""
    ).withLaunchHeaderContext()
}

/** The task a message's task menu is moving into Backlog, while its reason is asked. */
internal data class ThreadTaskBacklogReasonTarget(val taskId: String, val taskNumber: Int?)

internal fun String.toSharedThreadTaskStatus(): TaskStatus? =
    when (trim().lowercase().replace("-", "").replace("_", "").replace(" ", "")) {
        "backlog" -> TaskStatus.Backlog
        "todo" -> TaskStatus.Todo
        "inprogress" -> TaskStatus.InProgress
        "inreview" -> TaskStatus.InReview
        "done", "complete", "completed" -> TaskStatus.Done
        "closed" -> TaskStatus.Closed
        else -> null
    }











internal fun RaftThreadScreenPayload.threadComposerMemberScopedMentions(): Boolean =
    when (surfaceKind) {
        RaftThreadSurfaceKind.DirectMessage -> false
        RaftThreadSurfaceKind.ReplyThread ->
            typedReplyParentConversationKindOrNull(
                parentSurfaceKind,
                parentChannelKind
            ) == ConversationKind.Joint
        else -> memberScopedMentions
    }

internal fun RaftThreadScreenPayload.threadComposerMemberSuggestionHeadersVisible(): Boolean =
    when (surfaceKind) {
        RaftThreadSurfaceKind.DirectMessage -> false
        RaftThreadSurfaceKind.ReplyThread ->
            parentSurfaceKind.parsedThreadParentSurfaceKindOrNull() != RaftThreadSurfaceKind.DirectMessage
        else -> true
    }

internal fun RealtimeEvent.refreshesThreadMemberSuggestions(mentionChannelId: String?): Boolean =
    when (this) {
        is RealtimeRefreshRequired ->
            when (scope) {
                RealtimeRefreshScope.ChannelMembers ->
                    mentionChannelId != null &&
                        (channelId == null || channelId.value == mentionChannelId)
                RealtimeRefreshScope.ServerMembers -> true
                else -> false
            }
        else -> false
    }

internal fun StoredAuthSession?.threadSelfMentionNames(): Set<String> =
    this?.user?.let { user ->
        listOfNotNull(
            user.displayName,
            user.name,
            user.email.substringBefore("@").ifBlank { null },
            user.bestDisplayName
        )
            .map { it.removePrefix("@").trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
    }.orEmpty()



internal fun PendingMentionAction.toRaftPendingMentionActionItem(): RaftPendingMentionActionItem =
    RaftPendingMentionActionItem(
        resolutionId = resolutionId,
        messageId = messageId?.value,
        channelId = channelId?.value,
        channelName = channelName,
        targetType = targetType,
        targetHandle = targetHandle,
        targetAvatarUrl = targetAvatarUrl,
        reason = reason,
        availableActions = availableActions,
        expiresAt = expiresAt
    )

internal fun RaftPendingMentionActionItem.mentionActionDeliveredNotice(
    action: String,
    channelLabel: String
): String {
    val label = targetLabel()
    return if (action == "invite" || action == "add") {
        "$label was added to $channelLabel"
    } else {
        "Notified $label once - still not in $channelLabel"
    }
}

internal fun mentionActionSucceeded(
    action: String,
    result: PendingMentionActionExecuteResult?
): Boolean =
    when (action) {
        "notify", "notify_only" -> result?.status == "queued"
        "add", "invite" -> result?.status == "delivered"
        else -> false
    }

internal fun formatMentionActionStatusNotice(
    action: String,
    result: PendingMentionActionExecuteResult?,
    strings: PendingMentionActionBatchStrings
): String = result.localizedPendingMentionActionFailure(action = action, strings = strings)

internal fun RaftThreadComposerPayload.withThreadComposerDraftState(
    draftState: SharedThreadComposerDraftState,
    renderedPayload: RaftThreadScreenPayload,
    resolvedAuxiliaryThreadId: String?,
    optimisticPendingAccess: Boolean = false,
): RaftThreadComposerPayload {
    val projectedPendingAttachments = pendingAttachments.map { attachment ->
        SharedThreadComposerAttachment(
            id = attachment.id,
            fileName = attachment.fileName,
            sizeLabel = attachment.sizeLabel,
            sizeBytes = attachment.sizeBytes,
            mimeType = attachment.mimeType,
            previewUri = attachment.previewUri,
            status = attachment.status,
            errorCode = attachment.errorCode,
            source = attachment.source,
            externalShareToken = attachment.externalShareToken,
            externalShareAccountId = attachment.externalShareAccountId,
            externalShareServerId = attachment.externalShareServerId
        )
    }
    // The render-only pending item intentionally has no platform file handle.  Prefer the
    // draft-store row for duplicate ids so a recomposition cannot shadow its real `fileRef`
    // with a projection that only carries a thumbnail/status.  Route-projected rows that are
    // not yet in the store are retained after the canonical session rows.
    val pendingAttachments =
        (draftState.uploadedAttachments + projectedPendingAttachments)
            .distinctBy { attachment -> attachment.id }
            .filterNot { attachment -> attachment.id in draftState.removedAttachmentIds }
    val pendingAttachmentItems =
        pendingAttachments.map { attachment ->
            RaftPendingAttachmentItem(
                id = attachment.id,
                fileName = attachment.fileName,
                sizeLabel = attachment.sizeLabel,
                sizeBytes = attachment.sizeBytes,
                mimeType = attachment.mimeType,
                previewUri = attachment.previewUri,
                status = attachment.status,
                uploadTransfer = attachment.uploadTransfer,
                errorCode = attachment.errorCode,
                source = attachment.source,
                externalShareToken = attachment.externalShareToken,
                externalShareAccountId = attachment.externalShareAccountId,
                externalShareServerId = attachment.externalShareServerId
            )
        }
    return copy(
        instanceId = instanceId.ifBlank { renderedPayload.instanceId },
        seedText = draftState.seedText,
        seedAsTask = draftState.seedAsTask,
        originalImage = draftState.originalImage,
        syncNonce = draftState.syncNonce,
        editorCommand = draftState.editorCommand,
        focusRequestNonce = draftState.focusRequestNonce,
        focusRequestMeta = draftState.focusRequestMeta,
        pendingAttachments = pendingAttachmentItems,
        composerMode =
            when {
                composerMode == "failed" -> "failed"
                pendingAttachmentItems.hasUploadingThreadAttachment() -> "uploading"
                else -> composerMode
            },
        statusLabel = statusLabel,
        pendingMentionActions = draftState.pendingMentionActions,
        unresolvedMentionHandles = draftState.unresolvedMentionHandles,
        mentionActionNoticeTitle = draftState.mentionActionNoticeTitle,
        mentionActionNotice = draftState.mentionActionNotice,
        mentionActionStates = draftState.mentionActionStates,
        removingMentionActionIds = draftState.removingMentionActionIds.toList(),
        executingMentionActionIds = draftState.executingMentionActionIds.toList(),
        executingMentionActionStates = draftState.executingMentionActionStates,
        streamChannelId =
            resolvedAuxiliaryThreadId
                ?: if (optimisticPendingAccess) renderedPayload.optimisticComposerChannelId(null) else null,
        enabled =
            (renderedPayload.canSend || optimisticPendingAccess) &&
                (
                    resolvedAuxiliaryThreadId != null ||
                        (optimisticPendingAccess && renderedPayload.optimisticComposerChannelId(null) != null)
                )
    )
}

internal class ThreadRouteScaffoldSlots(
    val scaffoldPlan: ThreadRouteScaffoldPlan,
    val rootModifier: Modifier = Modifier,
    val header: @Composable () -> Unit,
    val body: @Composable ColumnScope.() -> Unit,
    val overlays: @Composable BoxScope.() -> Unit = {},
    /**
     * Task v2's Tasks / Files cards ([ConversationSideCardsCover]): drawn over the body (the
     * conversation and its composer), under the header. Null keeps the body as it always was.
     */
    val bodyCover: (@Composable BoxScope.() -> Unit)? = null,
    /**
     * Task v2's cards on a wide page ([conversationSideCardsColumn]): the pane grid's columns right
     * of the header and the body, the page's full height. Empty: nothing beside the conversation.
     */
    val sideColumns: List<RaftPaneGridColumn> = emptyList(),
)

@Composable
internal fun ThreadRouteScaffold(
    slots: ThreadRouteScaffoldSlots
) {
    val surfaceColor = RaftColor.SurfaceWhite
    key(slots.scaffoldPlan.rootIdentity) {
        Box(
            modifier =
                slots.rootModifier
                    .fillMaxSize()
                    .background(surfaceColor)
        ) {
            val carriesMainTabs = LocalThreadAssistantPanelEmbed.current == null
            RaftPaneGrid(
                columns = slots.sideColumns,
                modifier = Modifier.fillMaxSize(),
                topInset = if (carriesMainTabs && slots.sideColumns.isNotEmpty()) raftTopSafeAreaPadding() else 0.dp,
                mainHeadCarriesTabs = carriesMainTabs,
            ) {
            Column(
                modifier = Modifier.fillMaxSize().background(surfaceColor)
            ) {
                // In the assistant's panel the panel's head stands for the page's (web ChatPanel `hideHeader`).
                if (carriesMainTabs) slots.header()
                // Only the conversation header carries the main pane's row tabs; body / cover headers do not.
                CompositionLocalProvider(build.raft.designsystem.components.LocalRaftPaneGridHeadTabs provides null) {
                    val bodyCover = slots.bodyCover
                    if (bodyCover == null) {
                        slots.body(this)
                    } else {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Column(Modifier.fillMaxSize()) { slots.body(this) }
                            bodyCover()
                        }
                    }
                }
            }
            }
            slots.overlays(this)
        }
    }
}

/**
 * Maximum time [ThreadDeferredLoadingRouteContent] keeps prefilled (non-interactive) native nav
 * bar buttons on screen while waiting for FeatureSurfaces to mount. Long enough to span the iOS
 * push animation and fast loads without flashing, short enough that a stalled / offline load does
 * not leave dead buttons on the bar indefinitely.
 */
internal const val ThreadNativeNavBarPrefillPreserveTimeoutMs: Long = 1_500L

internal class ThreadLoadingOptimisticComposerHost(
    val accessResolution: ThreadAccessResolution,
    val replyParentAuthority: ReplyParentAuthorityStatus,
    val composerDraftViewModel: SharedThreadComposerDraftViewModel,
    val composerLocked: Boolean = false,
    val externalFrozen: Boolean = false,
    val movableComposer: (@Composable (ThreadComposerRouteArgs) -> Unit)? = null,
    val composerSendKeyEnabled: Boolean = false,
    val composerSendKey: build.raft.app.data.RaftComposerSendKey = build.raft.app.data.RaftComposerSendKey.Default,
    val onSendRequest: (ThreadComposerSendRequest) -> ThreadComposerSendAdmission = { ThreadComposerSendAdmission.Rejected },
    val onEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit = { _, _, _, _ -> },
)

@Composable
internal fun ThreadDeferredLoadingRouteContent(
    scaffold: @Composable (ThreadRouteScaffoldSlots) -> Unit,
    payload: RaftThreadScreenPayload,
    displayModel: ThreadHeaderDisplayModel,
    memberAvatarResolver: RaftMemberAvatarResolver,
    onBack: () -> Unit,
    showLoadingIndicator: Boolean = true,
    lounge: ThreadPageLounge? = null,
    optimisticComposerHost: ThreadLoadingOptimisticComposerHost? = null,
) {
    var preservePrefilledActions by remember(payload.instanceId) {
        androidx.compose.runtime.mutableStateOf(payload.status == "loading")
    }
    LaunchedEffect(payload.instanceId, payload.status) {
        if (payload.status != "loading") {
            preservePrefilledActions = false
            return@LaunchedEffect
        }
        preservePrefilledActions = true
        kotlinx.coroutines.delay(ThreadNativeNavBarPrefillPreserveTimeoutMs)
        preservePrefilledActions = false
    }
    val loadingConversationLounge =
        lounge?.lounge?.takeIf { payload.surfaceKind == RaftThreadSurfaceKind.Channel }
    val loadingLoungeFooterKind =
        agentLoungeFooterKind(
            conversationLounge = loadingConversationLounge,
            pageLounge = lounge?.lounge,
        )
    val loadingOptimisticChannelId = payload.optimisticComposerChannelId(null)
    val showOptimisticComposer =
        optimisticComposerHost != null &&
            loadingOptimisticChannelId != null &&
            threadShowsOptimisticComposer(
                accessResolution = optimisticComposerHost.accessResolution,
                payloadStatus = payload.status,
                surfaceKind = payload.surfaceKind,
                joined = payload.joined,
                showComposer = payload.showComposer,
                selectedTab = payload.selectedTab,
                composerLocked = optimisticComposerHost.composerLocked,
                channelArchived = lounge?.lounge == null && threadFooterShowsArchived(payload),
                externalFrozen = optimisticComposerHost.externalFrozen,
                loungeFooterActive = loadingLoungeFooterKind != null,
                replyParentAuthority = optimisticComposerHost.replyParentAuthority,
            )
    val loadingRemovedAppBot =
        rememberRaftMemberAppBot(displayModel.composerDmAgentPeerId())?.takeIf { it.removed }
    val composerBottomSafeArea = raftBottomSafeAreaPadding()
    val composerKeyboardInsetExcludesBottomSafeArea = raftKeyboardInsetExcludesBottomSafeArea()
    val emptyMemberSuggestionsFlow = remember { MutableStateFlow(SharedMembersMemberSuggestionSlice()) }
    val emptyChannelSuggestionsFlow = remember { MutableStateFlow(SharedMembersChannelSuggestionSlice()) }
    val loadingAssistantPanelContext = LocalThreadAssistantPanelEmbed.current?.context
    scaffold(
        ThreadRouteScaffoldSlots(
            scaffoldPlan = ThreadRouteMountPlan.LoadingShell.toThreadRouteScaffoldPlan(),
            header = {
                ThreadPageHeader(
                    payload = payload,
                    headerDisplay = displayModel,
                    activityMutedIndicatorVisible = false,
                    memberAvatarResolver = memberAvatarResolver,
                    showActions = false,
                    showBack = !threadInSplitDetailPane(),
                    onBack = onBack,
                    onOpenInChannel = {},
                    onOpenMemberProfile = {},
                    onOpenSearch = {},
                    onShowMembers = {},
                    onShowSettings = {},
                    lounge = lounge,
                    preserveNativePrefilledActions = preservePrefilledActions,
                )
            },
            body = {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(RaftColor.SurfaceWhite)
                ) {
                    if (showLoadingIndicator) {
                        ThreadLoadingState()
                    }
                }
                if (showOptimisticComposer && optimisticComposerHost != null && loadingOptimisticChannelId != null) {
                    val loadingBaseComposerState =
                        remember(
                            payload.composer,
                            payload.instanceId,
                            displayModel.title,
                            payload.surfaceKind,
                            payload.sourceThreadId,
                            payload.streamChannelId,
                            payload.parentChannelId,
                            payload.parentMessageId,
                            payload.originParentMessageId,
                            loadingOptimisticChannelId,
                        ) {
                            payload
                                .resolveThreadComposer(displayName = displayModel.title)
                                .copy(streamChannelId = loadingOptimisticChannelId, enabled = true)
                        }
                    val loadingComposerSurface =
                        remember(
                            loadingBaseComposerState,
                            loadingOptimisticChannelId,
                            payload.parentChannelId,
                            displayModel.title,
                            displayModel.subtitle,
                            payload.surfaceKind,
                            payload.parentSurfaceKind,
                            loadingAssistantPanelContext,
                        ) {
                            loadingBaseComposerState
                                .toThreadComposerSurface(
                                    channelId = loadingOptimisticChannelId,
                                    parentChannelId = payload.parentChannelId,
                                    mentionChannelLabel = displayModel.threadComposerMentionScopeChannelLabel(),
                                    memberSuggestionHeadersVisible = payload.threadComposerMemberSuggestionHeadersVisible(),
                                )
                                .withTaskV2()
                                .copy(assistantContext = loadingAssistantPanelContext)
                        }
                    val loadingDraftKey =
                        remember(loadingComposerSurface) { loadingComposerSurface.threadComposerDraftKey() }
                    remember(
                        optimisticComposerHost.composerDraftViewModel,
                        loadingBaseComposerState.instanceId,
                        loadingDraftKey,
                    ) {
                        ensureThreadComposerDraftStarted(
                            composerDraftViewModel = optimisticComposerHost.composerDraftViewModel,
                            composer = loadingBaseComposerState,
                            draftKey = loadingDraftKey,
                        )
                    }
                    val loadingTimelineSlice = remember(payload) { payload.toThreadTimelineSlice() }
                    ThreadComposerDock(
                        edgePadding = if (threadInSplitDetailPane()) ThreadSplitDetailEdgeInset else RaftAppSpacing.Sm,
                        composerModifier =
                            Modifier.raftThreadComposerLiveKeyboardPadding(
                                bottomSafeArea = composerBottomSafeArea,
                                keyboardInsetExcludesBottomSafeArea = composerKeyboardInsetExcludesBottomSafeArea,
                            ),
                        keyboardLiftPx =
                            rememberThreadComposerKeyboardLiftPx(
                                bottomSafeArea = composerBottomSafeArea,
                                keyboardInsetExcludesBottomSafeArea = composerKeyboardInsetExcludesBottomSafeArea,
                            ),
                    ) {
                        val args =
                            ThreadComposerRouteArgs(
                                baseComposerState = loadingBaseComposerState,
                                removedAppBot = loadingRemovedAppBot,
                                composerDraftViewModel = optimisticComposerHost.composerDraftViewModel,
                                renderedPayload = payload,
                                timelineSlice = loadingTimelineSlice,
                                resolvedAuxiliaryThreadId = loadingOptimisticChannelId,
                                surface = loadingComposerSurface,
                                memberSuggestionsFlow = emptyMemberSuggestionsFlow,
                                channelSuggestionsFlow = emptyChannelSuggestionsFlow,
                                agentStatusResolver = EmptyRaftAgentStatusResolver,
                                onEvent = optimisticComposerHost.onEvent,
                                onDropFiles = { it.release() },
                                onSendRequest = optimisticComposerHost.onSendRequest,
                                composerSendKeyEnabled = optimisticComposerHost.composerSendKeyEnabled,
                                composerSendKey = optimisticComposerHost.composerSendKey,
                                onKeyboardHeightChange = {},
                                adaptiveNativeEditingStateReader = LocalThreadAdaptiveNativeEditingReader.current,
                                optimisticPendingAccess = true,
                            )
                        val movable = optimisticComposerHost.movableComposer
                        if (movable != null) {
                            movable(args)
                        } else {
                            ThreadComposerRouteFromArgs(args)
                        }
                    }
                }
            }
        )
    )
}

internal class ThreadEventHandler(
    private val dispatch: (
        action: String,
        itemId: String?,
        attachmentId: String?,
        tab: String?,
        markdownActionId: String?,
        threadChannelId: String?
    ) -> Unit
) {
    operator fun invoke(
        action: String,
        itemId: String? = null,
        attachmentId: String? = null,
        tab: String? = null,
        markdownActionId: String? = null,
        /** The reply thread an action targets, when it is not [itemId] (the unread-thread pill). */
        threadChannelId: String? = null
    ) {
        dispatch(action, itemId, attachmentId, tab, markdownActionId, threadChannelId)
    }
}

@Composable
private fun threadCreateAgentMachinesErrorCopy(code: String): String =
    when (code) {
        "server_id_missing" -> stringResource(Res.string.thread_action_card_error_select_server)
        "auth_required",
        "auth_session_missing",
        "auth_session_expired" -> stringResource(Res.string.thread_action_card_error_sign_in_computers)
        "server_forbidden" -> stringResource(Res.string.thread_action_card_error_computers_permission)
        else -> stringResource(Res.string.thread_action_card_error_load_computers, code)
    }
