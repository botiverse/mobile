package build.raft.app

import build.raft.app.markdown.markdownToPreviewText
import build.raft.app.components.raftLookOverlaySurface
import build.raft.app.data.PushPreviewMode
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.ComposeLocalDataStore
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MemberKind
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageType
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import build.raft.app.model.avatarWireValueForRenderedAvatar
import build.raft.app.model.conversationSafeDisplayTitleOrNull
import build.raft.app.model.threadIdentity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

private const val ForegroundBannerAutoDismissMs = 5_000L
private const val ForegroundBannerPreviewMaxLength = 96
private const val ForegroundBannerZIndex = RaftFloatingOverlayHostZIndex + 10f
private val ForegroundBannerTwoLineHeight = 70.dp
private val ForegroundBannerThreadReplyHeight = 90.dp
private val ForegroundBannerAvatarSize = 30.dp
private val ForegroundBannerReplyGlyphSize = 13.dp

internal fun raftForegroundBannerAutoDismissMs(): Long = ForegroundBannerAutoDismissMs

// Swiping the banner up dismisses it (matching the platform notification
// gesture). The banner follows the finger upward only; releasing past the
// threshold dismisses, otherwise it snaps back.
private val ForegroundBannerSwipeDismissThreshold = 24.dp

internal fun shouldDismissForegroundBannerOnSwipeRelease(
    dragOffsetYDp: Float,
    thresholdDp: Float = ForegroundBannerSwipeDismissThreshold.value
): Boolean = dragOffsetYDp <= -thresholdDp

internal fun foregroundBannerDragOffsetAfterDelta(
    currentOffsetYDp: Float,
    deltaYDp: Float
): Float = (currentOffsetYDp + deltaYDp).coerceAtMost(0f)

internal enum class RaftForegroundMessageBannerIconKind {
    Channel,
    DirectMessage
}

internal data class RaftForegroundMessageBanner(
    val id: String,
    val serverId: String,
    val focusedMessageId: String,
    val conversation: ThreadLaunchConversationIdentity,
    val openRequest: HomeMessageOpenRequest,
    val title: String,
    val context: String,
    val threadContext: String?,
    val body: String,
    val iconKind: RaftForegroundMessageBannerIconKind,
    val iconName: RaftLucideIconName?,
    val senderMemberId: String?,
    val avatarWireValue: String?,
    val avatarLabel: String,
    /**
     * Set for another workspace's notification (contract §4.5: shown like any other): its tap goes
     * through window navigation, which switches to [serverId] first and then opens it.
     */
    val crossServerSource: ConversationOpenSourceMessageIdentity? = null
)

/** One banner model, route owner and expiry clock for one AppShell/window. */
internal class SharedForegroundBannerStore(
    private val scope: CoroutineScope,
    private val awaitAutoDismiss: suspend () -> Unit = { delay(ForegroundBannerAutoDismissMs) }
) {
    private val lock = SynchronizedObject()
    private val mutableBanner = MutableStateFlow<RaftForegroundMessageBanner?>(null)
    private var autoDismissJob: Job? = null
    private var visibleRouteOwnerId: String? = null
    private var visibleRoute: ThreadRouteIdentity? = null

    val banner: StateFlow<RaftForegroundMessageBanner?> = mutableBanner.asStateFlow()

    fun show(next: RaftForegroundMessageBanner) {
        synchronized(lock) {
            if (mutableBanner.value?.id == next.id) return
            autoDismissJob?.cancel()
            mutableBanner.value = next
            autoDismissJob =
                scope.launch {
                    awaitAutoDismiss()
                    dismiss(next.id)
                }
        }
    }

    fun dismiss(expectedId: String? = null) {
        synchronized(lock) {
            val current = mutableBanner.value ?: return
            if (expectedId != null && current.id != expectedId) return
            autoDismissJob?.cancel()
            autoDismissJob = null
            mutableBanner.value = null
        }
    }

    fun clear() {
        synchronized(lock) {
            autoDismissJob?.cancel()
            autoDismissJob = null
            mutableBanner.value = null
            visibleRouteOwnerId = null
            visibleRoute = null
        }
    }

    fun setVisibleRoute(ownerId: String, route: ThreadRouteIdentity) {
        synchronized(lock) {
            visibleRouteOwnerId = ownerId
            visibleRoute = route
        }
    }

    fun clearVisibleRoute(ownerId: String) {
        synchronized(lock) {
            if (visibleRouteOwnerId != ownerId) return
            visibleRouteOwnerId = null
            visibleRoute = null
        }
    }

    fun visibleRouteIdentity(): ThreadRouteIdentity? = synchronized(lock) { visibleRoute }
}

@Composable
internal fun RaftForegroundBannerHost(
    banner: RaftForegroundMessageBanner?,
    topSafeArea: Dp,
    serverMembersStore: SharedServerMembersStore? = null,
    currentServerStore: ComposeLocalDataStore? = null,
    currentServerIdOverride: ServerId? = null,
    onBannerClick: (RaftForegroundMessageBanner) -> Unit,
    onBannerDismiss: (RaftForegroundMessageBanner) -> Unit = {},
    content: @Composable () -> Unit
) {
    val currentSessionFlow =
        remember(currentServerStore) {
            currentServerStore?.observeSession() ?: flowOf(null)
        }
    val currentSession by currentSessionFlow.collectAsState(null)
    val currentServerId = currentServerIdOverride ?: currentSession?.currentServerId
    Box(modifier = Modifier.fillMaxSize()) {
        content()
        if (banner != null) {
            RaftForegroundMessageBannerView(
                banner = banner,
                topSafeArea = topSafeArea,
                serverMembersStore = serverMembersStore,
                currentServerId = currentServerId,
                onClick = { onBannerClick(banner) },
                onDismiss = { onBannerDismiss(banner) }
            )
        }
    }
}

@Composable
internal fun RaftForegroundMessageBannerView(
    banner: RaftForegroundMessageBanner,
    topSafeArea: Dp,
    serverMembersStore: SharedServerMembersStore? = null,
    currentServerId: ServerId? = null,
    onClick: () -> Unit,
    onDismiss: () -> Unit = {}
) {
    val bannerHeight =
        if (banner.threadContext.isNullOrBlank()) {
            ForegroundBannerTwoLineHeight
        } else {
            ForegroundBannerThreadReplyHeight
        }
    // Upward-only drag offset in dp. Reset per banner id so a new banner
    // always starts seated; on a dismissing release the offset is left as-is
    // (the host unmounts the view) to avoid a one-frame snap-back flash.
    var swipeOffsetYDp by remember(banner.id) { mutableStateOf(0f) }
    val gestureOwner =
        rememberRaftGestureOwner(
            surface = "foreground-banner",
            semanticIdentity = banner.id,
            callbacks =
                ForegroundBannerGestureCallbacks(
                    currentOffsetYDp = { swipeOffsetYDp },
                    onOffsetYDpChange = { swipeOffsetYDp = it },
                    onDismiss = onDismiss
                )
        )
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .zIndex(ForegroundBannerZIndex)
                .padding(start = 12.dp, top = topSafeArea + 8.dp, end = 12.dp)
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .align(Alignment.TopCenter)
                    .height(bannerHeight)
                    .offset(y = swipeOffsetYDp.dp)
                    // Keep the complete DOWN..UP sequence on the banner's own hit path.
                    .raftNativeDispatchCapture()
                    .raftGesturePointerInput(gestureOwner) { owner ->
                        var gesture: RaftGestureSession<ForegroundBannerGestureCallbacks>? = null
                        detectVerticalDragGestures(
                            onDragStart = { gesture = owner.beginGesture() },
                            onDragEnd = {
                                val callbacks =
                                    (gesture ?: owner.beginGesture()).currentCallbacksOrNull()
                                        ?: return@detectVerticalDragGestures
                                if (shouldDismissForegroundBannerOnSwipeRelease(callbacks.currentOffsetYDp())) {
                                    callbacks.onDismiss()
                                } else {
                                    callbacks.onOffsetYDpChange(0f)
                                }
                            },
                            onDragCancel = {
                                (gesture ?: owner.beginGesture())
                                    .currentCallbacksOrNull()
                                    ?.onOffsetYDpChange(0f)
                            },
                            onVerticalDrag = { _, dragAmount ->
                                (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.let { callbacks ->
                                    callbacks.onOffsetYDpChange(
                                    foregroundBannerDragOffsetAfterDelta(
                                        currentOffsetYDp = callbacks.currentOffsetYDp(),
                                        deltaYDp = dragAmount.toDp().value
                                    )
                                    )
                                }
                            }
                        )
                    }
        ) {
            // raft overlay surface (toast/popover): container radius, popover layer, seam
            // ring and the float shadow; no ink frame or hard offset.
            Row(
                modifier =
                    Modifier
                        .matchParentSize()
                        .raftLookOverlaySurface()
                        .padding(start = 10.dp, top = 6.dp, end = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clickable(role = Role.Button, onClick = onClick),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RaftForegroundBannerAvatar(
                        banner = banner,
                        serverMembersStore = serverMembersStore,
                        currentServerId = currentServerId
                    )
                    Spacer(Modifier.width(10.dp))
                    RaftForegroundBannerText(banner, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.width(8.dp))
                RaftForegroundBannerActionIcon(
                    iconName = RaftLucideIconName.X,
                    onClick = onDismiss
                )
            }
        }
    }
}

private data class ForegroundBannerGestureCallbacks(
    val currentOffsetYDp: () -> Float,
    val onOffsetYDpChange: (Float) -> Unit,
    val onDismiss: () -> Unit
)

@Composable
private fun RaftForegroundBannerText(
    banner: RaftForegroundMessageBanner,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        RaftForegroundBannerTitleRow(banner)
        banner.threadContext?.takeIf { it.isNotBlank() }?.let { threadContext ->
            RaftForegroundBannerThreadContextRow(threadContext)
        }
        Text(
            text = banner.body,
            style =
                RaftType.Caption
                    .copy(fontSize = 12.sp, lineHeight = 16.sp, color = RaftColor.TextBlack)
                    .withRaftFontWeight(FontWeight.Medium),
            color = RaftColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RaftForegroundBannerThreadContextRow(threadContext: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        RaftLucideIcon(
            RaftLucideIconName.MessageSquare,
            modifier = Modifier.size(ForegroundBannerReplyGlyphSize),
            color = RaftColor.TextMutedGrayArgb
        )
        Text(
            text = threadContext,
            style =
                RaftType.Caption
                    .copy(fontSize = 12.sp, lineHeight = 16.sp, color = RaftColor.TextMutedGray)
                    .withRaftFontWeight(FontWeight.Medium),
            color = RaftColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun RaftForegroundBannerTitleRow(banner: RaftForegroundMessageBanner) {
    val inlineContext = banner.inlineContextLabel()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = banner.title,
            style = RaftType.ButtonBold.copy(fontSize = 13.sp, lineHeight = 16.sp, color = RaftColor.TextBlack),
            color = RaftColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (inlineContext.isNotBlank()) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = inlineContext,
                style =
                    RaftType.ButtonBold
                        .copy(fontSize = 13.sp, lineHeight = 17.sp, color = RaftColor.TextMutedGray)
                        .withRaftFontWeight(FontWeight.Medium),
                color = RaftColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun RaftForegroundBannerActionIcon(
    iconName: RaftLucideIconName,
    onClick: () -> Unit
) {
    Box(
        modifier =
            Modifier
                .size(24.dp)
                .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        RaftLucideIcon(
            iconName,
            modifier = Modifier.size(15.dp),
            color = RaftColor.TextBlackArgb
        )
    }
}

internal fun appShellVisibleForegroundConversationKey(
    selectedTab: String,
    selectedHomeDestination: String?,
    selectedConversationId: String?
): String? =
    selectedConversationId
        ?.takeIf { it.isNotBlank() }
        ?.takeIf {
            retainedAppShellSelectedTab(selectedTab) == "home" &&
                selectedHomeDestination == null
        }

@Suppress("UNUSED_PARAMETER")
internal fun appShellForegroundVisibleHomeConversationId(
    homeShellSelectedConversationId: String?,
    homeStoreSelectedConversationId: String?
): String? =
    // The Home store keeps its last selected conversation while Home is retained off-screen.
    // Foreground-banner visibility must use the AppShell route host instead.
    homeShellSelectedConversationId
        ?.takeIf { it.isNotBlank() }

internal fun raftConversationDirectoryStateFromProjections(
    projections: Collection<SharedConversationDirectoryProjection>
): SharedConversationDirectoryState =
    projections.toSharedConversationDirectoryState()



internal fun raftForegroundMessageBannerProjection(
    serverId: String,
    currentServerId: String?,
    message: Message,
    currentMemberId: String?,
    visibleConversationKey: String?,
    visibleRouteIdentity: ThreadRouteIdentity? = null,
    conversationDirectory: SharedConversationDirectoryState = SharedConversationDirectoryState()
): RaftForegroundMessageBanner? =
    raftForegroundMessageBannerProjectionResult(
        serverId = serverId,
        currentServerId = currentServerId,
        message = message,
        currentMemberId = currentMemberId,
        visibleConversationKey = visibleConversationKey,
        visibleRouteIdentity = visibleRouteIdentity,
        conversationDirectory = conversationDirectory
    ).banner

internal data class RaftForegroundMessageBannerProjectionResult(
    val banner: RaftForegroundMessageBanner?,
    val sharedProjection: SharedConversationDirectoryProjection?,
    val suppressReason: String? = null
) {
    val resolvedKind: ConversationKind?
        get() = banner?.conversation?.kind
}

internal fun raftForegroundMessageBannerProjectionResult(
    serverId: String,
    currentServerId: String?,
    message: Message,
    currentMemberId: String?,
    visibleConversationKey: String?,
    visibleRouteIdentity: ThreadRouteIdentity? = null,
    conversationDirectory: SharedConversationDirectoryState = SharedConversationDirectoryState()
): RaftForegroundMessageBannerProjectionResult {
    val normalizedServerId =
        serverId.takeIf { it.isNotBlank() }
            ?: return RaftForegroundMessageBannerProjectionResult(null, null, "server_missing")
    val normalizedCurrentServerId =
        currentServerId?.takeIf { it.isNotBlank() }
            ?: return RaftForegroundMessageBannerProjectionResult(null, null, "current_server_missing")
    if (normalizedCurrentServerId != normalizedServerId) {
        return RaftForegroundMessageBannerProjectionResult(null, null, "server_mismatch")
    }
    if (!currentMemberId.isNullOrBlank() && message.author.id.value == currentMemberId) {
        return RaftForegroundMessageBannerProjectionResult(null, null, "own_message")
    }
    if (message.messageType == MessageType.System || message.author.kind == MemberKind.System) {
        return RaftForegroundMessageBannerProjectionResult(null, null, "system_message")
    }
    val projection = message.resolveForegroundBannerProjection(conversationDirectory)
    val openRequest =
        message.resolveForegroundBannerOpenRequest(normalizedServerId, projection, conversationDirectory)
            ?: return RaftForegroundMessageBannerProjectionResult(null, projection, "conversation_missing")
    val conversation = openRequest.conversationIdentity
    if (conversation.matchesForegroundVisibleRoute(visibleConversationKey, visibleRouteIdentity)) {
        return RaftForegroundMessageBannerProjectionResult(null, projection, "visible_conversation")
    }
    val sender = message.shownAuthorName().ifBlank { message.author.handle.ifBlank { "Someone" } }
    val context = conversation.resolveForegroundBannerContext(projection, conversationDirectory)
    val threadContext = projection.resolveForegroundBannerThreadContext(conversation)
    val iconKind = projection.resolveForegroundBannerIconKind(conversation)
    val iconName = projection.resolveForegroundBannerIconName(conversation)
    val body = markdownToPreviewText(message.body, ForegroundBannerPreviewMaxLength).ifBlank { "Sent a new message." }
    val senderMemberId =
        projection.resolveForegroundBannerSenderMemberId(message)
            ?: message.author.id.value.takeIf { it.isNotBlank() }
    return RaftForegroundMessageBannerProjectionResult(
        banner =
            RaftForegroundMessageBanner(
                id = "foreground:$normalizedServerId:${message.id.value}",
                serverId = normalizedServerId,
                focusedMessageId = message.id.value,
                conversation = conversation,
                openRequest = openRequest,
                title = sender,
                context = context,
                threadContext = threadContext,
                body = body,
                iconKind = iconKind,
                iconName = iconName,
                senderMemberId = senderMemberId,
                avatarWireValue =
                    projection.resolveForegroundBannerSenderAvatarWireValue(message)
                        ?: message.author.avatarWireValueForRenderedAvatar(),
                avatarLabel =
                    projection.resolveForegroundBannerSenderAvatarLabel(message)
                        ?: message.author.avatarLabel.takeIf { it.isNotBlank() }
                        ?: sender
            ),
        sharedProjection = projection
    )
}

internal fun RaftForegroundMessageBanner.inlineContextLabel(): String {
    if (iconKind == RaftForegroundMessageBannerIconKind.DirectMessage) return ""
    val parts = context.split(" - ").map { it.trim() }.filter { it.isNotBlank() }
    val compactParts = parts.filterNot { it == "thread" || it == "direct message" || it == "now" }
    return if (compactParts.size == 2) {
        "${compactParts[0]} ${compactParts[1]}"
    } else if (compactParts.size == parts.size) {
        context
    } else {
        compactParts.joinToString(" - ")
    }
}

private fun SharedConversationDirectoryProjection?.resolveForegroundBannerThreadContext(
    conversation: ThreadLaunchConversationIdentity
): String? {
    val isReplyThread =
        this?.surfaceKind == RaftThreadSurfaceKind.ReplyThread ||
            conversation.kind == ConversationKind.Thread
    if (!isReplyThread) return null
    // A reply thread's subtitle is its root message's Markdown.
    return this?.subtitle?.let(::markdownToPreviewText)?.takeIf { it.isNotBlank() }
}

@Composable
private fun RaftForegroundBannerAvatar(
    banner: RaftForegroundMessageBanner,
    serverMembersStore: SharedServerMembersStore?,
    currentServerId: ServerId?
) {
    val profileFlow =
        remember(serverMembersStore, currentServerId, banner.senderMemberId) {
            serverMembersStore?.memberProfileFlow(currentServerId, banner.senderMemberId) ?: flowOf(null)
        }
    val profile by profileFlow.collectAsState(
        remember(serverMembersStore, currentServerId, banner.senderMemberId) {
            currentServerId?.let { serverId -> serverMembersStore?.currentMemberProfile(serverId, banner.senderMemberId) }
        }
    )
    val memberAvatarWireValue = profile?.explicitForegroundBannerAvatarWireValue()
    val avatarLabel = profile?.raftAvatarName()?.takeIf { it.isNotBlank() } ?: banner.avatarLabel
    RaftAppAvatar(
        avatarWireValue = memberAvatarWireValue ?: banner.avatarWireValue,
        label = avatarLabel,
        avatarSize = ForegroundBannerAvatarSize,
        cacheSubject = profile?.raftAvatarCacheSubject(),
        cacheServerId = profile?.serverId?.value ?: currentServerId?.value,
        fallbackFill = raftAvatarFallbackFill(profile?.kind)
    )
}

private fun MemberProfile.explicitForegroundBannerAvatarWireValue(): String? =
    avatar
        ?.wireValue
        ?.takeIf { it.isNotBlank() }

private fun Message.resolveForegroundBannerOpenRequest(
    serverId: String,
    projection: SharedConversationDirectoryProjection?,
    directory: SharedConversationDirectoryState
): HomeMessageOpenRequest? {
    val context = conversationContext
    val contextKind = context?.channelKind
    val projectedKind = projection?.conversationKind
    val kind = contextKind ?: projectedKind ?: return null
    val resolvedChannelId =
        when (kind) {
            ConversationKind.Thread ->
                threadChannelId?.value?.takeIf { it.isNotBlank() }
                    ?: channelId?.value?.takeIf { it.isNotBlank() }
                    ?: projection?.canonicalChannelId?.takeIf { it.isNotBlank() }
            else ->
                channelId?.value?.takeIf { it.isNotBlank() }
                    ?: threadChannelId?.value?.takeIf { it.isNotBlank() }
                    ?: projection?.canonicalChannelId?.takeIf { it.isNotBlank() }
                    ?: threadId.value.takeIf { it.isNotBlank() }
        }
    val sourceIdentity =
        ConversationOpenSourceMessageIdentity(
            source = ConversationOpenSource.ForegroundBanner,
            messageId = foregroundBannerAnchorMessageId(),
            channelId = resolvedChannelId,
            threadChannelId =
                if (kind == ConversationKind.Thread) {
                    threadChannelId?.value?.takeIf { it.isNotBlank() }
                        ?: channelId?.value?.takeIf { it.isNotBlank() }
                        ?: projection?.canonicalChannelId?.takeIf { it.isNotBlank() }
                } else {
                    null
                },
            parentChannelId =
                context?.parentChannelId?.value?.takeIf { it.isNotBlank() }
                    ?: projection?.parentChannelId?.takeIf { it.isNotBlank() },
            parentMessageId =
                context?.parentMessageId?.value?.takeIf { it.isNotBlank() }
                    ?: parentMessageId?.value?.takeIf { it.isNotBlank() }
                    ?: projection?.parentMessageId?.takeIf { it.isNotBlank() },
            conversationKind = kind,
            parentConversationKind = context?.parentChannelKind,
            originFallback = projection.toThreadLaunchOriginFallback()
        )
    return (resolveOpenRequest(serverId, sourceIdentity, directory) as? ConversationOpenResolution.Resolved)
        ?.request
}

// task #437 SSOT: banner display labels derive from ONE chain — the resolved
// conversation identity plus the directory rows its ids address (parent channel for a
// thread, peer/self row for a DM or channel). The message's own stream projection is
// no longer a private entry point for labels; it is just an addressable row, used only
// as a title fallback when the identity's ids resolve nothing. Whether that stream row
// exists (Activity snapshot) or the identity came from a typed conversationContext,
// the label path is identical — display enrichment only, identity untouched (§5).
private fun ThreadLaunchConversationIdentity.resolveForegroundBannerContext(
    streamProjection: SharedConversationDirectoryProjection?,
    directory: SharedConversationDirectoryState
): String =
    listOfNotNull(
        foregroundBannerSourceLabel(streamProjection, directory),
        foregroundBannerSurfaceLabel()
    ).distinct().joinToString(" - ")

private fun ThreadLaunchConversationIdentity.foregroundBannerSourceLabel(
    streamProjection: SharedConversationDirectoryProjection?,
    directory: SharedConversationDirectoryState
): String? =
    when (kind) {
        ConversationKind.DirectMessage ->
            foregroundBannerSelfRow(streamProjection, directory)
                ?.let { row ->
                    row.safeForegroundBannerTitle(
                        identityIds = setOfNotNull(channelId?.value, threadId.value, row.peerIdentity?.memberId?.value)
                    )
                }
                ?.let { "@$it" }
        ConversationKind.Thread -> {
            val parentChannelId = thread?.parentChannelId?.value ?: channelId?.value
            // The parent id can alias the reply thread's own row; a parent is never a
            // reply thread — treat that as an unresolved parent (same rule as
            // toThreadLaunchConversationIdentity).
            val parentRow =
                directory.resolve(parentChannelId)
                    ?.takeUnless { it.surfaceKind == RaftThreadSurfaceKind.ReplyThread }
            val identityIds = setOfNotNull(parentChannelId, thread?.parentMessageId?.value, threadId.value)
            val title =
                parentRow?.safeForegroundBannerTitle(identityIds = identityIds)
                    ?: streamProjection?.safeForegroundBannerTitle(identityIds = identityIds)
            title?.let { value ->
                if (parentRow?.surfaceKind == RaftThreadSurfaceKind.DirectMessage) {
                    "@${value.removePrefix("@")}"
                } else {
                    "#${value.removePrefix("#")}"
                }
            }
        }
        else ->
            foregroundBannerSelfRow(streamProjection, directory)
                ?.let { row ->
                    row.safeForegroundBannerTitle(identityIds = setOfNotNull(channelId?.value, threadId.value))
                }
                ?.let { "#$it" }
    }

private fun ThreadLaunchConversationIdentity.foregroundBannerSelfRow(
    streamProjection: SharedConversationDirectoryProjection?,
    directory: SharedConversationDirectoryState
): SharedConversationDirectoryProjection? =
    streamProjection
        ?: directory.resolve(channelId?.value)
        ?: directory.resolve(threadId.value)

private fun SharedConversationDirectoryProjection.safeForegroundBannerTitle(
    identityIds: Set<String> = emptySet()
): String? =
    conversationSafeDisplayTitleOrNull(title, identityIds + canonicalChannelId + aliases)

private fun ThreadLaunchConversationIdentity.foregroundBannerSurfaceLabel(): String? =
    when (kind) {
        ConversationKind.DirectMessage -> "direct message"
        ConversationKind.Thread -> "thread"
        else -> null
    }

private fun SharedConversationDirectoryProjection?.resolveForegroundBannerIconKind(
    conversation: ThreadLaunchConversationIdentity
): RaftForegroundMessageBannerIconKind =
    when {
        this?.surfaceKind == RaftThreadSurfaceKind.DirectMessage -> RaftForegroundMessageBannerIconKind.DirectMessage
        conversation.kind == ConversationKind.DirectMessage -> RaftForegroundMessageBannerIconKind.DirectMessage
        else -> RaftForegroundMessageBannerIconKind.Channel
    }

private fun SharedConversationDirectoryProjection?.resolveForegroundBannerIconName(
    conversation: ThreadLaunchConversationIdentity
): RaftLucideIconName? =
    when {
        this?.surfaceKind == RaftThreadSurfaceKind.DirectMessage -> null
        conversation.kind == ConversationKind.DirectMessage -> null
        this?.surfaceKind == RaftThreadSurfaceKind.ReplyThread -> RaftLucideIconName.MessageSquare
        conversation.kind == ConversationKind.Thread -> RaftLucideIconName.MessageSquare
        else -> this?.channelKind.threadChannelHeaderIcon()
    }

private fun Message.resolveForegroundBannerProjection(
    directory: SharedConversationDirectoryState
): SharedConversationDirectoryProjection? =
    foregroundBannerLookupIds()
        .firstNotNullOfOrNull(directory::resolve)

/**
 * The foreground banner's anchor strategy: the tapped banner IS a specific new message, so
 * it anchors on that message id — nothing to infer. This is the third named anchor branch
 * alongside `ConversationOpenSourceMessageIdentity.resolveOpenAnchorMessageId`'s
 * ActivityRow/Notification strategies (ConversationOpenResolver.kt); the banner request is
 * built outside the resolver, so the branch lives here with a cross-link instead of a
 * source enum case (t2b/#419 — the three anchor strategies are now declared side by side).
 */
private fun Message.foregroundBannerAnchorMessageId(): String? =
    id.value.takeIf { it.isNotBlank() }

private fun SharedConversationDirectoryProjection?.resolveForegroundBannerSenderMemberId(message: Message): String? =
    this?.takeIf { projection -> projection.matchesForegroundBannerSenderMessage(message) }
        ?.senderMemberId
        ?.takeIf { it.isNotBlank() }

private fun SharedConversationDirectoryProjection?.resolveForegroundBannerSenderAvatarWireValue(message: Message): String? =
    this?.takeIf { projection -> projection.matchesForegroundBannerSenderMessage(message) }
        ?.senderAvatarWireValue
        ?.takeIf { it.isNotBlank() }

private fun SharedConversationDirectoryProjection?.resolveForegroundBannerSenderAvatarLabel(message: Message): String? =
    this?.takeIf { projection -> projection.matchesForegroundBannerSenderMessage(message) }
        ?.senderAvatarLabel
        ?.takeIf { it.isNotBlank() }

private fun SharedConversationDirectoryProjection.matchesForegroundBannerSenderMessage(message: Message): Boolean {
    val ids = senderMessageIds
    return ids.isNotEmpty() && message.id.value in ids
}

// Order IS the contract: the message's own stream/channel ids resolve before thread/parent
// anchors — the banner must land on the conversation the message belongs to before any
// parent association can claim it (t432 family ③).
private fun Message.foregroundBannerLookupIds(): List<String> =
    listOfNotNull(
        threadChannelId?.value,
        channelId?.value,
        threadId.value,
        parentMessageId?.value
    ).map { it.trim() }.filter { it.isNotBlank() }.distinct()

private fun ThreadLaunchConversationIdentity.matchesForegroundVisibleRoute(
    visibleConversationKey: String?,
    visibleRouteIdentity: ThreadRouteIdentity?
): Boolean {
    val targetRoute = resolveThreadRouteIdentity()
    val selectedKey = visibleConversationKey?.takeIf { it.isNotBlank() }
    if (selectedKey != null && selectedKey in targetRoute.foregroundRouteMatchKeys(threadId.value)) return true
    val route = visibleRouteIdentity ?: return false
    if (targetRoute.instanceKey == route.instanceKey) return true
    val targetTimeline = targetRoute.timelineChannelId
    if (targetTimeline != null && targetTimeline == route.timelineChannelId) return true
    val targetParent = targetRoute.parentKey
    if (targetParent != null && targetParent == route.parentKey) return true
    return false
}

private fun ThreadRouteIdentity.foregroundRouteMatchKeys(threadId: String): Set<String> =
    buildSet {
        threadId.takeIf { it.isNotBlank() }?.let(::add)
        timelineChannelId?.takeIf { it.isNotBlank() }?.let(::add)
        parentKey?.takeIf { it.isNotBlank() }?.let(::add)
        instanceKey.takeIf { it.isNotBlank() }?.let(::add)
    }

/**
 * task #459: foreground banner from the server-qualified notification stream
 * (`notification:push`, server PR #4233). The server has already decided this
 * event deserves attention (scope/mention/follow/mute) and excluded the
 * sender, so the only suppressions left are the LOCAL facts it cannot know:
 * the current server and the currently visible conversation. Identity is
 * typed on the payload — there is nothing to defer or guess, so the pending /
 * retry machinery of the message-driven path does not apply here.
 */
internal fun raftNotificationPushBannerOrNull(
    event: NotificationPushReceived,
    currentServerId: String?,
    visibleConversationKey: String?,
    visibleRouteIdentity: ThreadRouteIdentity? = null,
    conversationDirectory: SharedConversationDirectoryState = SharedConversationDirectoryState(),
    onSuppressed: (String) -> Unit = {}
): RaftForegroundMessageBanner? {
    val normalizedCurrentServerId = currentServerId?.takeIf { it.isNotBlank() }
        ?: return null.also { onSuppressed("current_server_missing") }
    // Contract §4.5: another workspace's notification is shown like any other; its open target is
    // resolved under its own server (this window's conversation directory is the current server's).
    val otherServer = normalizedCurrentServerId != event.serverId.value
    val targetServerId = event.serverId.value
    val directory = if (otherServer) SharedConversationDirectoryState() else conversationDirectory
    val sourceIdentity =
        ConversationOpenSourceMessageIdentity(
            source = ConversationOpenSource.ForegroundBanner,
            messageId = event.messageId.value,
            channelId =
                if (event.kind == ConversationKind.Thread) {
                    event.threadId?.value
                } else {
                    event.channelId?.value
                },
            threadChannelId = event.threadId?.value,
            parentChannelId = event.parentChannelId?.value,
            parentMessageId = event.parentMessageId?.value,
            conversationKind = event.kind
        )
    val openRequest =
        (resolveOpenRequest(targetServerId, sourceIdentity, directory)
            as? ConversationOpenResolution.Resolved)
            ?.request
            ?: return null.also { onSuppressed("identity_incomplete") }
    val conversation = openRequest.conversationIdentity
    if (!otherServer && conversation.matchesForegroundVisibleRoute(visibleConversationKey, visibleRouteIdentity)) {
        onSuppressed("visible_conversation")
        return null
    }
    return RaftForegroundMessageBanner(
        id = "push:${event.serverId.value}:${event.messageId.value}",
        serverId = event.serverId.value,
        focusedMessageId = event.messageId.value,
        conversation = conversation,
        openRequest =
            openRequest,
        title = event.foregroundBannerTitle(),
        context = "",
        threadContext = null,
        body = event.foregroundBannerBody(),
        iconKind = (null as SharedConversationDirectoryProjection?).resolveForegroundBannerIconKind(conversation),
        iconName = (null as SharedConversationDirectoryProjection?).resolveForegroundBannerIconName(conversation),
        senderMemberId = event.senderId?.takeIf { event.senderKind != null }?.value,
        avatarWireValue = null,
        avatarLabel = (event.senderName ?: event.title).trim().take(2).uppercase().ifBlank { "??" },
        crossServerSource = sourceIdentity.takeIf { otherServer }
    )
}

/**
 * The push preview texts, resolved in composition (MR strings need it) and applied later from the
 * realtime collector. The sender_only templates keep [SENDER_PLACEHOLDER] where the name goes.
 */
internal data class RaftPushPreviewBannerStrings(
    val hiddenTitle: String,
    val hiddenMessage: String,
    val hiddenMention: String,
    val senderOnlyMessageTemplate: String,
    val senderOnlyMentionTemplate: String
) {
    fun hiddenBody(mentioned: Boolean): String = if (mentioned) hiddenMention else hiddenMessage

    fun senderOnlyBody(sender: String, mentioned: Boolean): String =
        (if (mentioned) senderOnlyMentionTemplate else senderOnlyMessageTemplate).replace(SENDER_PLACEHOLDER, sender)

    companion object {
        const val SENDER_PLACEHOLDER = "\uE000sender\uE000"
    }
}

/**
 * The in-app foreground banner follows the device push preview choice too: it is built from the raw
 * notification:push event, so without this a sender_only/hidden device would still show message text
 * while the app is open. sender_only keeps the conversation title and names only the sender; hidden
 * also drops the conversation, server and sender (avatar included).
 */
internal fun RaftForegroundMessageBanner.redactedForPushPreview(
    mode: PushPreviewMode,
    senderName: String?,
    mentioned: Boolean,
    strings: RaftPushPreviewBannerStrings
): RaftForegroundMessageBanner =
    when (mode) {
        PushPreviewMode.Full -> this
        PushPreviewMode.SenderOnly -> {
            val sender = senderName?.trim()?.takeIf { it.isNotEmpty() }
            copy(
                threadContext = null,
                body = sender?.let { strings.senderOnlyBody(it, mentioned) } ?: strings.hiddenBody(mentioned)
            )
        }
        PushPreviewMode.Hidden ->
            copy(
                title = strings.hiddenTitle,
                context = "",
                threadContext = null,
                body = strings.hiddenBody(mentioned),
                senderMemberId = null,
                avatarWireValue = null,
                avatarLabel = strings.hiddenTitle.take(2).uppercase()
            )
    }

private fun NotificationPushReceived.foregroundBannerTitle(): String {
    val serverLabel = serverName?.trim()?.takeIf { it.isNotBlank() }
        ?: return title.ifBlank { "New message" }
    val conversationLabel = channelName?.trim()?.takeIf { it.isNotBlank() }
    return when (kind) {
        ConversationKind.DirectMessage -> "DM · $serverLabel"
        ConversationKind.Channel,
        ConversationKind.Joint -> conversationLabel?.let { "#${it.removePrefix("#")} · $serverLabel" }
        ConversationKind.Thread ->
            when (parentChannelKind) {
                ConversationKind.DirectMessage -> "Thread in DM · $serverLabel"
                ConversationKind.Channel,
                ConversationKind.Joint ->
                    conversationLabel?.let { "Thread in #${it.removePrefix("#")} · $serverLabel" }
                else -> null
            }
        else -> null
    } ?: title.ifBlank { "New message" }
}

private fun NotificationPushReceived.foregroundBannerBody(): String {
    val senderLabel = senderName?.trim()?.takeIf { it.isNotBlank() }
    val preview = messagePreview?.trim()?.takeIf { it.isNotBlank() }
    if (senderLabel == null || preview == null) return body.ifBlank { "Sent a new message." }
    return if (mentioned == true) {
        "$senderLabel mentioned you: $preview"
    } else {
        "$senderLabel: $preview"
    }
}
