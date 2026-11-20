package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.app.base.Utils
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Pager
import build.raft.app.data.RaftShareActionObject
import build.raft.app.data.RaftShareActionSurface
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.formatTaskConversationRef
import build.raft.app.model.ConversationKind
import build.raft.app.model.ServerId
import build.raft.app.model.TaskSummary
import build.raft.app.resources.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource

/*
 * A conversation page's share out (share loop, first batch): what its ⋯, its messages' menus, the
 * multi-select toolbar and the long image's Share do. Null everywhere while the share loop is off
 * ([rememberShareLoopEnabled]) or the host has no share sheet: every surface then stays as it was.
 * The rules are RaftShareOut.kt's; this builds the page's subjects (links, text, titles).
 */

/** The ⋯ menu's Share row and the Copy link under it, for a conversation or a task. */
internal class ThreadHeaderShareEntry(
    val shareLabel: StringResource,
    val onShare: () -> Unit,
    val onCopyLink: () -> Unit,
)

internal const val SHARE_OUT_MENU_ITEM_ID = "share-out"
internal const val SHARE_OUT_COPY_LINK_MENU_ITEM_ID = "share-out-copy-link"

/** The DM rule's subject: this page is a DM, or a thread or task conversation inside one. */
internal fun RaftThreadScreenPayload.threadShareIsDirectMessage(): Boolean =
    surfaceKind == RaftThreadSurfaceKind.DirectMessage ||
        parentSurfaceKind == RaftThreadSurfaceKind.DirectMessage ||
        parseConversationKindOrNull(channelKind) == ConversationKind.DirectMessage ||
        parseConversationKindOrNull(parentChannelKind) == ConversationKind.DirectMessage

/** The messages Share is offered on: sent, a person's or agent's own words, fully loaded, not retracted. */
internal fun threadMessageShareable(message: RaftThreadMessageItem): Boolean =
    !message.hasLocalSendState() && !message.isSystemMessage() && !message.hasActivityCutBody() && !message.isRetracted

/**
 * This page's conversation link for Share / Copy link: the channel's or Lounge's, or a reply
 * thread's (`thread=` on its parent channel). Null in a DM (the DM rule), on a task conversation
 * (the task has its own link) and without the ids.
 */
internal fun RaftThreadScreenPayload.threadConversationShareLink(serverSlug: String?, webBaseUrl: String?): String? {
    if (isTaskConversationSurface()) return null
    val directMessage = threadShareIsDirectMessage()
    return if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
        raftConversationShareLink(
            webBaseUrl = webBaseUrl,
            serverSlug = serverSlug,
            channelId = parentChannelId,
            threadParentMessageId =
                (originParentMessageId?.takeIf { it.isNotBlank() } ?: parentMessageId?.takeIf { it.isNotBlank() }) ?: return null,
            directMessage = directMessage,
        )
    } else {
        raftConversationShareLink(
            webBaseUrl = webBaseUrl,
            serverSlug = serverSlug,
            channelId = sourceThreadId?.takeIf { it.isNotBlank() } ?: streamChannelId,
            threadParentMessageId = null,
            directMessage = directMessage,
        )
    }
}

/** What a conversation's Share row is called and reported as. */
internal fun threadConversationShareObject(replyThread: Boolean, lounge: Boolean): RaftShareActionObject =
    when {
        replyThread -> RaftShareActionObject.Thread
        lounge -> RaftShareActionObject.Lounge
        else -> RaftShareActionObject.Channel
    }

internal fun RaftShareActionObject.threadShareMenuLabel(): StringResource =
    when (this) {
        RaftShareActionObject.Thread -> Res.string.share_out_thread
        RaftShareActionObject.Lounge -> Res.string.share_out_lounge
        RaftShareActionObject.Task -> Res.string.share_out_task
        else -> Res.string.share_out_channel
    }

/**
 * A message menu's availability with the page's share out: Share where the message can be shared,
 * and no Copy link in a DM (the DM rule). Unchanged without [shareOut] (the share loop off).
 */
internal fun ThreadNewMessageMenuActionAvailability.withShareOut(
    shareOut: ThreadShareOut?,
    message: RaftThreadMessageItem,
): ThreadNewMessageMenuActionAvailability =
    if (shareOut == null) this else withShareOut(shareable = threadMessageShareable(message), directMessage = shareOut.directMessage)

internal fun ThreadNewMessageMenuActionAvailability.withShareOut(
    shareable: Boolean,
    directMessage: Boolean,
): ThreadNewMessageMenuActionAvailability = copy(share = shareable, copyLink = copyLink && !directMessage)

/** The Share / Copy link subject for a task (with or without a conversation); null in a DM or without an id. */
internal fun raftTaskHeaderShareSubject(
    webBaseUrl: String?,
    taskId: String?,
    title: String?,
    fallbackTitle: String?,
    directMessage: Boolean,
): RaftShareOutSubject? {
    val link = raftTaskShareLink(webBaseUrl, taskId, directMessage = directMessage) ?: return null
    return RaftShareOutSubject(
        obj = RaftShareActionObject.Task,
        surface = RaftShareActionSurface.TaskMenu,
        title = title?.takeIf { it.isNotBlank() } ?: fallbackTitle.orEmpty(),
        link = link,
    )
}

@Stable
internal class ThreadShareOut(
    private val launcher: RaftShareOutLauncher,
    private val webBaseUrl: () -> String,
) {
    // The page's current facts, written as it composes; read only when a share is asked for.
    var payload: RaftThreadScreenPayload = RaftThreadScreenPayload()
    var serverSlug: String? = null
    var lounge: Boolean = false
    var title: String = ""

    /** Makes the long image of these messages (the page's Share preview), which then shares it. */
    var onGenerateImage: (Set<String>) -> Unit = {}

    /** The DM rule applies here: no link leaves this page (Copy link goes with it). */
    val directMessage: Boolean get() = payload.threadShareIsDirectMessage()

    /** The ⋯ menu's entry: the task's on a task conversation, else the conversation's; null: none. */
    fun headerEntry(task: TaskConversationHeaderChrome?): ThreadHeaderShareEntry? {
        val subject =
            if (payload.isTaskConversationSurface()) {
                raftTaskHeaderShareSubject(
                    webBaseUrl = webBaseUrl(),
                    taskId = task?.taskId,
                    title = task?.title,
                    fallbackTitle = task?.ref,
                    directMessage = task?.directMessage != false || directMessage,
                ) ?: return null
            } else {
                val link = payload.threadConversationShareLink(serverSlug, webBaseUrl()) ?: return null
                RaftShareOutSubject(
                    obj = threadConversationShareObject(payload.surfaceKind == RaftThreadSurfaceKind.ReplyThread, lounge),
                    surface = RaftShareActionSurface.ConversationMenu,
                    title = title,
                    link = link,
                )
            }
        return ThreadHeaderShareEntry(
            shareLabel = subject.obj.threadShareMenuLabel(),
            onShare = { launcher.share(subject) },
            onCopyLink = { launcher.copyLink(subject) },
        )
    }

    /** A message's Share: its link (none in a DM), with its text and the long image beside it. */
    fun shareMessage(message: RaftThreadMessageItem) {
        val author = message.shownAuthorName()
        val link = if (directMessage) null else payload.threadMessagePermalink(message, serverSlug, webBaseUrl())
        val attribution = author.takeIf { it.isNotBlank() }?.let { Res.string.share_out_text_attribution.format(it).resolve() }
        val subject =
            RaftShareOutSubject(
                obj = RaftShareActionObject.Message,
                surface = RaftShareActionSurface.MessageMenu,
                title = Res.string.share_out_title_message.format(title, author).resolve(),
                link = link,
                text = raftShareOutMessageText(message.body, attribution),
                imageAvailable = true,
            )
        val messageId = message.id
        launcher.share(subject, onImage = { onGenerateImage(setOf(messageId)) })
    }

    /** The multi-select toolbar's Share: the messages' links (none in a DM), their text, or [onImage]. */
    fun shareSelection(messages: List<RaftThreadMessageItem>, onImage: () -> Unit) {
        if (messages.isEmpty()) return
        val links =
            if (directMessage) {
                ""
            } else {
                messages.mapNotNull { payload.threadMessagePermalink(it, serverSlug, webBaseUrl()) }.joinToString("\n")
            }
        launcher.share(selectionSubject(messages.size, RaftShareActionSurface.SelectionToolbar, links, messages.threadShareMarkdown()), onImage)
    }

    /** Share preview's Share: the long image file at [path]. */
    fun shareImageFile(path: String, messageCount: Int) {
        launcher.shareImageFile(selectionSubject(messageCount, RaftShareActionSurface.SharePreview, "", ""), path)
    }

    private fun selectionSubject(count: Int, surface: RaftShareActionSurface, links: String, text: String): RaftShareOutSubject =
        RaftShareOutSubject(
            obj = if (count == 1) RaftShareActionObject.Message else RaftShareActionObject.MessageSelection,
            surface = surface,
            title = Res.string.share_out_title_messages.format(title, count).resolve(),
            link = links.takeIf { it.isNotBlank() },
            text = text.takeIf { it.isNotBlank() },
            imageAvailable = true,
        )
}

/** The page's [ThreadShareOut] for the rows under it (the message list reads it; null: off). */
@Stable
internal class ThreadShareOutHandle {
    var shareOut by mutableStateOf<ThreadShareOut?>(null)
}

internal val LocalThreadShareOut = staticCompositionLocalOf<ThreadShareOutHandle?> { null }

@Composable
internal fun ThreadShareOutScope(content: @Composable () -> Unit) {
    val handle = remember { ThreadShareOutHandle() }
    CompositionLocalProvider(LocalThreadShareOut provides handle, content = content)
}

/**
 * This page's share out, or null: the share loop is off for [serverId] (then nothing on the page
 * changes), or the host has no share sheet. Publishes itself to [LocalThreadShareOut].
 */
@Composable
internal fun rememberThreadShareOut(
    payload: RaftThreadScreenPayload,
    serverId: ServerId?,
    serverSlug: String?,
    lounge: Boolean,
    title: String,
    shareState: ThreadShareForwardState,
): ThreadShareOut? {
    val handle = LocalThreadShareOut.current
    val enabled = rememberShareLoopEnabled(serverId)
    val platform = remember { currentRaftShareOutPlatform() }
    val pager = LocalActivity.current.getPager() as Pager
    val scope = rememberCoroutineScope()
    val shareOut =
        if (enabled && platform != null) {
            remember(pager.pagerId, platform) {
                val bridge = Utils.bridgeModule(pager.pagerId)
                ThreadShareOut(
                    launcher = RaftShareOutLauncher(bridge, platform, scope),
                    // Read at each share: the Developer environment's web URL (AGENTS.md "Environment Web Link URL Rule").
                    webBaseUrl = {
                        sharedSettingsResolvedWebBaseUrl(
                            standaloneSettingsWebBaseUrl(RaftSharedStoreRegistry.get(bridge).apiEnvironmentStore)
                        )
                    },
                )
            }
        } else {
            null
        }
    if (shareOut != null) {
        shareOut.payload = payload
        shareOut.serverSlug = serverSlug
        shareOut.lounge = lounge
        shareOut.title = title
        // "Send image": the message becomes the selection, and the toolbar then makes its long image
        // (the page's existing Share preview, whose Share sends it).
        shareOut.onGenerateImage = { ids ->
            shareState.selectedShareMessageIds = ids
            shareState.shareSelectionAnchorId = ids.firstOrNull()
            shareState.shareSelectionMode = true
            shareState.pendingShareImageExport = true
        }
    }
    SideEffect { handle?.shareOut = shareOut }
    return shareOut
}

/**
 * Whether a pending task is subject to the DM share rule: explicit DM channel type is always a DM,
 * explicit public/private/joint channel types are never DM (even if opened via a cross-channel ref
 * from a DM), and missing/thread channel types inherit the hosting page's DM flag.
 */
internal fun taskPendingShareIsDirectMessage(taskChannelType: String?, hostDirectMessage: Boolean): Boolean =
    when (parseConversationKindOrNull(taskChannelType)) {
        ConversationKind.DirectMessage -> true
        ConversationKind.Channel, ConversationKind.Joint -> false
        else -> hostDirectMessage
    }

/**
 * Share task / Copy link for a pending task page (no conversation yet): gated by `share_loop_v0`,
 * host share-sheet support, and the DM rule, reading the Developer environment web URL at share
 * time. Null when the flag is off, the host has no sheet, or the task has no link (a DM task).
 */
@Composable
internal fun rememberTaskHeaderShareEntry(
    task: TaskSummary,
    serverId: String?,
    directMessage: Boolean = false,
    scope: CoroutineScope = rememberCoroutineScope(),
): ThreadHeaderShareEntry? {
    val enabled = rememberShareLoopEnabled(serverId?.takeIf { it.isNotBlank() }?.let(::ServerId))
    val platform = remember { currentRaftShareOutPlatform() }
    val pager = LocalActivity.current.getPager() as Pager
    val isDirectMessage = taskPendingShareIsDirectMessage(task.channelType, hostDirectMessage = directMessage)
    if (!enabled || platform == null || isDirectMessage) return null
    if (raftTaskShareLink(null, task.id.value, directMessage = false) == null) return null
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val launcher = remember(pager.pagerId, platform, scope) { RaftShareOutLauncher(bridge, platform, scope) }
    val currentTask by rememberUpdatedState(task)
    val currentDirectMessage by rememberUpdatedState(isDirectMessage)
    return remember(launcher, bridge) {
        val subject: () -> RaftShareOutSubject? = {
            val latest = currentTask
            val fallbackRef =
                latest.taskNumber?.let { formatTaskConversationRef(it, latest.channelName, parseConversationKindOrNull(latest.channelType)) }
                    ?: latest.title
            val webBaseUrl =
                sharedSettingsResolvedWebBaseUrl(
                    standaloneSettingsWebBaseUrl(RaftSharedStoreRegistry.get(bridge).apiEnvironmentStore)
                )
            raftTaskHeaderShareSubject(
                webBaseUrl = webBaseUrl,
                taskId = latest.id.value,
                title = latest.title,
                fallbackTitle = fallbackRef,
                directMessage = currentDirectMessage,
            )
        }
        ThreadHeaderShareEntry(
            shareLabel = RaftShareActionObject.Task.threadShareMenuLabel(),
            onShare = { subject()?.let { launcher.share(it) } },
            onCopyLink = { subject()?.let { launcher.copyLink(it) } },
        )
    }
}

/**
 * Share preview's Share: the long image as it shows (off screen: written to its PNG first; iOS: the
 * exported file) goes to the system share sheet. The preview stays open under the sheet.
 */
internal fun threadSharePreviewShareImage(
    shareOut: ThreadShareOut,
    shareState: ThreadShareForwardState,
    scope: CoroutineScope,
    pngWriter: RaftPngFileWriter,
    messageCount: Int,
    onFailed: () -> Unit,
) {
    if (!threadShareImageRendersOffscreen) {
        val path = shareState.sharePreviewImage?.path?.takeIf { it.isNotBlank() }
        if (path != null) shareOut.shareImageFile(path, messageCount) else onFailed()
        return
    }
    val image = shareState.shareImageBitmap?.image ?: return
    if (shareState.shareImageSaving || shareState.shareImageRendering) return
    shareState.shareImageSaving = true
    scope.launch {
        val path =
            try {
                withContext(Dispatchers.Default) {
                    runCatching { pngWriter.writePng(image, ThreadShareImageFilePrefix) }.getOrNull()
                }?.let(::threadShareImageFilePath)
            } finally {
                shareState.shareImageSaving = false
            }
        if (path.isNullOrBlank()) onFailed() else shareOut.shareImageFile(path, messageCount)
    }
}
