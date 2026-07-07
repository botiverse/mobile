package build.raft.app

import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageType
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import build.raft.app.model.avatarWireValueForRenderedAvatar
import build.raft.app.model.conversationSafeDisplayTitleOrNull
import build.raft.app.model.threadIdentity
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.semantics.Role
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.compose.ui.zIndex

private const val ForegroundBannerAutoDismissMs = 5_000L
private const val ForegroundBannerPreviewMaxLength = 96
private const val ForegroundBannerZIndex = SlockFloatingOverlayHostZIndex + 10f
private val ForegroundBannerTwoLineHeight = 70.dp
private val ForegroundBannerThreadReplyHeight = 90.dp
private val ForegroundBannerAvatarSize = 30.dp
private val ForegroundBannerReplyGlyphSize = 13.dp

internal fun slockForegroundBannerAutoDismissMs(): Long = ForegroundBannerAutoDismissMs

internal enum class SlockForegroundMessageBannerIconKind {
    Channel,
    DirectMessage
}

internal data class SlockForegroundMessageBanner(
    val id: String,
    val serverId: String,
    val focusedMessageId: String,
    val conversation: ThreadLaunchConversationIdentity,
    val openRequest: HomeMessageOpenRequest,
    val title: String,
    val context: String,
    val threadContext: String?,
    val body: String,
    val iconKind: SlockForegroundMessageBannerIconKind,
    val iconName: SlockLucideIconName?,
    val senderMemberId: String?,
    val avatarWireValue: String?,
    val avatarLabel: String
)

@Composable
internal fun SlockForegroundBannerHost(
    banner: SlockForegroundMessageBanner?,
    topSafeArea: Dp,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    onBannerClick: (SlockForegroundMessageBanner) -> Unit,
    onBannerDismiss: (SlockForegroundMessageBanner) -> Unit = {},
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        content()
        if (banner != null) {
            SlockForegroundMessageBannerView(
                banner = banner,
                topSafeArea = topSafeArea,
                memberAvatarResolver = memberAvatarResolver,
                onClick = { onBannerClick(banner) },
                onDismiss = { onBannerDismiss(banner) }
            )
        }
    }
}

@Composable
internal fun SlockForegroundMessageBannerView(
    banner: SlockForegroundMessageBanner,
    topSafeArea: Dp,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    onClick: () -> Unit,
    onDismiss: () -> Unit = {}
) {
    val bannerHeight =
        if (banner.threadContext.isNullOrBlank()) {
            ForegroundBannerTwoLineHeight
        } else {
            ForegroundBannerThreadReplyHeight
        }
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
        ) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .offset(x = 3.dp, y = 3.dp)
                        .background(SlockColor.ShadowBlack)
            )
            Row(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(SlockColor.SurfaceWhite)
                        .border(SlockStroke.Default, SlockColor.BorderBlack)
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
                    SlockForegroundBannerAvatar(banner, memberAvatarResolver)
                    Spacer(Modifier.width(10.dp))
                    SlockForegroundBannerText(banner, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.width(8.dp))
                SlockForegroundBannerActionIcon(
                    iconName = SlockLucideIconName.X,
                    onClick = onDismiss
                )
            }
        }
    }
}

@Composable
private fun SlockForegroundBannerText(
    banner: SlockForegroundMessageBanner,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        SlockForegroundBannerTitleRow(banner)
        banner.threadContext?.takeIf { it.isNotBlank() }?.let { threadContext ->
            SlockForegroundBannerThreadContextRow(threadContext)
        }
        Text(
            text = banner.body,
            style =
                SlockType.Caption
                    .copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextBlack)
                    .withSlockFontWeight(FontWeight.Medium),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SlockForegroundBannerThreadContextRow(threadContext: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SlockLucideIcon(
            SlockLucideIconName.MessageSquare,
            modifier = Modifier.size(ForegroundBannerReplyGlyphSize),
            color = SlockColor.TextMutedGrayArgb
        )
        Text(
            text = threadContext,
            style =
                SlockType.Caption
                    .copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray)
                    .withSlockFontWeight(FontWeight.Medium),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SlockForegroundBannerTitleRow(banner: SlockForegroundMessageBanner) {
    val inlineContext = banner.inlineContextLabel()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = banner.title,
            style = SlockType.ButtonBold.copy(fontSize = 13.sp, lineHeight = 16.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (inlineContext.isNotBlank()) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = inlineContext,
                style =
                    SlockType.ButtonBold
                        .copy(fontSize = 13.sp, lineHeight = 17.sp, color = SlockColor.TextMutedGray)
                        .withSlockFontWeight(FontWeight.Medium),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SlockForegroundBannerActionIcon(
    iconName: SlockLucideIconName,
    onClick: () -> Unit
) {
    Box(
        modifier =
            Modifier
                .size(24.dp)
                .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        SlockLucideIcon(
            iconName,
            modifier = Modifier.size(15.dp),
            color = SlockColor.TextBlackArgb
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

internal fun slockConversationDirectoryStateFromProjections(
    projections: Collection<SharedConversationDirectoryProjection>
): SharedConversationDirectoryState =
    projections.toSharedConversationDirectoryState()

internal fun slockForegroundMessageBannerProjection(
    serverId: String,
    currentServerId: String?,
    message: Message,
    currentMemberId: String?,
    visibleConversationKey: String?,
    visibleRouteIdentity: ThreadRouteIdentity? = null,
    conversationDirectory: SharedConversationDirectoryState = SharedConversationDirectoryState()
): SlockForegroundMessageBanner? =
    slockForegroundMessageBannerProjectionResult(
        serverId = serverId,
        currentServerId = currentServerId,
        message = message,
        currentMemberId = currentMemberId,
        visibleConversationKey = visibleConversationKey,
        visibleRouteIdentity = visibleRouteIdentity,
        conversationDirectory = conversationDirectory
    ).banner

internal data class SlockForegroundMessageBannerProjectionResult(
    val banner: SlockForegroundMessageBanner?,
    val sharedProjection: SharedConversationDirectoryProjection?,
    val suppressReason: String? = null
) {
    val resolvedKind: ConversationKind?
        get() = banner?.conversation?.kind
}

internal fun slockForegroundMessageBannerProjectionResult(
    serverId: String,
    currentServerId: String?,
    message: Message,
    currentMemberId: String?,
    visibleConversationKey: String?,
    visibleRouteIdentity: ThreadRouteIdentity? = null,
    conversationDirectory: SharedConversationDirectoryState = SharedConversationDirectoryState()
): SlockForegroundMessageBannerProjectionResult {
    val normalizedServerId =
        serverId.takeIf { it.isNotBlank() }
            ?: return SlockForegroundMessageBannerProjectionResult(null, null, "server_missing")
    val normalizedCurrentServerId =
        currentServerId?.takeIf { it.isNotBlank() }
            ?: return SlockForegroundMessageBannerProjectionResult(null, null, "current_server_missing")
    if (normalizedCurrentServerId != normalizedServerId) {
        return SlockForegroundMessageBannerProjectionResult(null, null, "server_mismatch")
    }
    if (!currentMemberId.isNullOrBlank() && message.author.id.value == currentMemberId) {
        return SlockForegroundMessageBannerProjectionResult(null, null, "own_message")
    }
    if (message.messageType == MessageType.System || message.author.kind == MemberKind.System) {
        return SlockForegroundMessageBannerProjectionResult(null, null, "system_message")
    }
    val projection = message.resolveForegroundBannerProjection(conversationDirectory)
    val conversation =
        message.resolveForegroundBannerConversation(conversationDirectory)
            ?: return SlockForegroundMessageBannerProjectionResult(null, projection, "conversation_missing")
    if (conversation.matchesForegroundVisibleRoute(visibleConversationKey, visibleRouteIdentity)) {
        return SlockForegroundMessageBannerProjectionResult(null, projection, "visible_conversation")
    }
    val sender = message.author.displayName.ifBlank { message.author.handle.ifBlank { "Someone" } }
    val context = message.resolveForegroundBannerContext(projection, conversationDirectory)
    val threadContext = projection.resolveForegroundBannerThreadContext(conversation)
    val iconKind = projection.resolveForegroundBannerIconKind(conversation)
    val iconName = projection.resolveForegroundBannerIconName(conversation)
    val body = message.body.toForegroundBannerPreview().ifBlank { "Sent a new message." }
    val openRequest = message.resolveForegroundBannerOpenRequest(projection, conversation)
    val senderMemberId =
        projection.resolveForegroundBannerSenderMemberId(message)
            ?: message.author.id.value.takeIf { it.isNotBlank() }
    return SlockForegroundMessageBannerProjectionResult(
        banner =
            SlockForegroundMessageBanner(
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
                        ?: sender.take(2).uppercase()
            ),
        sharedProjection = projection
    )
}

private fun SlockForegroundMessageBanner.inlineContextLabel(): String {
    if (iconKind == SlockForegroundMessageBannerIconKind.DirectMessage) return ""
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
        this?.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread ||
            conversation.kind == ConversationKind.Thread
    if (!isReplyThread) return null
    return this?.subtitle?.takeIf { it.isNotBlank() }
}

@Composable
private fun SlockForegroundBannerAvatar(
    banner: SlockForegroundMessageBanner,
    memberAvatarResolver: SlockMemberAvatarResolver
) {
    val memberAvatarWireValue = rememberSlockMemberAvatarWireValue(memberAvatarResolver, banner.senderMemberId)
    SlockAvatar(
        avatarWireValue = memberAvatarWireValue ?: banner.avatarWireValue,
        label = banner.avatarLabel,
        avatarSize = ForegroundBannerAvatarSize,
        borderWidth = SlockStroke.Default
    )
}

private fun Message.resolveForegroundBannerConversation(
    directory: SharedConversationDirectoryState
): ThreadLaunchConversationIdentity? {
    val projection = resolveForegroundBannerProjection(directory)
    if (projection != null) return projection.toForegroundThreadLaunchConversationIdentity(directory)
    val parentMessage = parentMessageId
    val parentChannel = channelId ?: threadChannelId
    if (parentMessage != null && parentChannel != null) {
        val streamChannel = threadChannelId?.takeIf { it.value.isNotBlank() }
        return ThreadLaunchConversationIdentity(
            threadId = threadIdentity(parentChannel, parentMessage),
            channelId = parentChannel,
            channelType = null,
            thread =
                ThreadConversation(
                    parentChannelId = parentChannel,
                    parentMessageId = parentMessage,
                    streamChannelId = streamChannel,
                    parentSurfaceKind = KuiklyThreadSurfaceKind.Channel,
                    parentChannelKind = null
                ),
            kind = ConversationKind.Thread
        )
    }
    val streamChannel = threadChannelId ?: channelId ?: ChannelId(threadId.value)
    return ThreadLaunchConversationIdentity(
        threadId = ThreadId(streamChannel.value),
        channelId = streamChannel,
        channelType = KuiklyThreadSurfaceKind.Channel,
        kind = ConversationKind.Channel
    )
}

private fun Message.resolveForegroundBannerContext(
    projection: SharedConversationDirectoryProjection?,
    directory: SharedConversationDirectoryState
): String {
    val source =
        projection
            ?.toForegroundBannerSourceLabel(directory)
    val surface = projection?.toForegroundBannerSurfaceLabel() ?: fallbackForegroundBannerSurfaceLabel()
    return listOfNotNull(
        source,
        surface
    ).distinct().joinToString(" - ")
}

private fun SharedConversationDirectoryProjection.toForegroundBannerSourceLabel(
    directory: SharedConversationDirectoryState
): String? =
    when (surfaceKind) {
        KuiklyThreadSurfaceKind.DirectMessage ->
            safeForegroundBannerTitle(identityIds = setOf(canonicalChannelId, peerMemberId.orEmpty()) + aliases)
                ?.let { "@$it" }
        KuiklyThreadSurfaceKind.ReplyThread ->
            parentChannelId
                ?.let(directory::resolve)
                ?.safeForegroundBannerTitle(identityIds = setOfNotNull(parentChannelId, canonicalChannelId, parentMessageId))
                ?.let { "#$it" }
                ?: safeForegroundBannerTitle(identityIds = setOfNotNull(parentChannelId, canonicalChannelId, parentMessageId))
                    ?.let { "#$it" }
        else ->
            safeForegroundBannerTitle(identityIds = setOf(canonicalChannelId) + aliases)
                ?.let { "#$it" }
    }

private fun SharedConversationDirectoryProjection.safeForegroundBannerTitle(
    identityIds: Set<String> = emptySet()
): String? =
    conversationSafeDisplayTitleOrNull(title, identityIds + canonicalChannelId + aliases)

private fun SharedConversationDirectoryProjection.toForegroundBannerSurfaceLabel(): String? =
    when (surfaceKind) {
        KuiklyThreadSurfaceKind.DirectMessage -> "direct message"
        KuiklyThreadSurfaceKind.ReplyThread -> "thread"
        else -> null
    }

private fun SharedConversationDirectoryProjection?.resolveForegroundBannerIconKind(
    conversation: ThreadLaunchConversationIdentity
): SlockForegroundMessageBannerIconKind =
    when {
        this?.surfaceKind == KuiklyThreadSurfaceKind.DirectMessage -> SlockForegroundMessageBannerIconKind.DirectMessage
        conversation.kind == ConversationKind.DirectMessage -> SlockForegroundMessageBannerIconKind.DirectMessage
        else -> SlockForegroundMessageBannerIconKind.Channel
    }

private fun SharedConversationDirectoryProjection?.resolveForegroundBannerIconName(
    conversation: ThreadLaunchConversationIdentity
): SlockLucideIconName? =
    when {
        this?.surfaceKind == KuiklyThreadSurfaceKind.DirectMessage -> null
        conversation.kind == ConversationKind.DirectMessage -> null
        this?.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread -> SlockLucideIconName.MessageSquare
        conversation.kind == ConversationKind.Thread -> SlockLucideIconName.MessageSquare
        else -> this?.channelKind.threadChannelHeaderIcon()
    }

private fun Message.fallbackForegroundBannerSurfaceLabel(): String? =
    when {
        parentMessageId != null -> "thread"
        else -> null
    }

private fun Message.resolveForegroundBannerProjection(
    directory: SharedConversationDirectoryState
): SharedConversationDirectoryProjection? =
    foregroundBannerLookupIds()
        .firstNotNullOfOrNull(directory::resolve)

private fun Message.resolveForegroundBannerOpenRequest(
    projection: SharedConversationDirectoryProjection?,
    conversation: ThreadLaunchConversationIdentity
): HomeMessageOpenRequest =
    HomeMessageOpenRequest(
        conversationIdentity = conversation,
        anchorMessageId = id.value.takeIf { it.isNotBlank() },
        originFallback = projection.toForegroundThreadLaunchOriginFallback()
    )

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

private fun SharedConversationDirectoryProjection?.toForegroundThreadLaunchOriginFallback(): ThreadLaunchOriginFallback? {
    if (this?.surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) return null
    val parentId = parentMessageId?.takeIf { it.isNotBlank() } ?: return null
    return ThreadLaunchOriginFallback(
        parentChannelId = parentChannelId?.takeIf { it.isNotBlank() },
        parentMessageId = parentId,
        preview = subtitle?.takeIf { it.isNotBlank() },
        senderId = null,
        senderKind = null,
        expected = true
    )
}

private fun Message.foregroundBannerLookupIds(): List<String> =
    listOfNotNull(
        threadChannelId?.value,
        channelId?.value,
        threadId.value,
        parentMessageId?.value
    ).map { it.trim() }.filter { it.isNotBlank() }.distinct()

private fun SharedConversationDirectoryProjection.toForegroundThreadLaunchConversationIdentity(
    directory: SharedConversationDirectoryState
): ThreadLaunchConversationIdentity {
    val isReplyThread = surfaceKind == KuiklyThreadSurfaceKind.ReplyThread
    val parentChannel = parentChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
    val parentMessage = parentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId)
    val streamChannel = canonicalChannelId.takeIf { it.isNotBlank() }?.let(::ChannelId)
    val normalizedChannelKind = channelKind.takeIf { it.isNotBlank() }
    val normalizedParentChannelKind =
        if (isReplyThread) {
            parentChannelId
                ?.let(directory::resolve)
                ?.channelKind
                ?.takeIf { it.isNotBlank() }
                ?.takeUnless { it == KuiklyThreadSurfaceKind.ReplyThread }
                ?: KuiklyThreadSurfaceKind.Channel
        } else {
            null
        }
    return ThreadLaunchConversationIdentity(
        threadId =
            if (isReplyThread && parentChannel != null && parentMessage != null) {
                threadIdentity(parentChannel, parentMessage)
            } else {
                ThreadId(canonicalChannelId)
            },
        channelId = if (isReplyThread) parentChannel else streamChannel,
        channelType = if (isReplyThread) null else normalizedChannelKind,
        thread =
            if (isReplyThread && parentChannel != null && parentMessage != null) {
                ThreadConversation(
                    parentChannelId = parentChannel,
                    parentMessageId = parentMessage,
                    streamChannelId = streamChannel,
                    parentSurfaceKind = KuiklyThreadSurfaceKind.Channel,
                    parentChannelKind = normalizedParentChannelKind
                )
            } else {
                null
            },
        kind =
            when (surfaceKind) {
                KuiklyThreadSurfaceKind.DirectMessage -> ConversationKind.DirectMessage
                KuiklyThreadSurfaceKind.ReplyThread -> ConversationKind.Thread
                else -> if (channelKind.equals("joint", ignoreCase = true)) ConversationKind.Joint else ConversationKind.Channel
            }
    )
}

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

private fun String.toForegroundBannerPreview(maxLength: Int = ForegroundBannerPreviewMaxLength): String =
    trim()
        .replace(Regex("\\s+"), " ")
        .let { value ->
            if (value.length <= maxLength) {
                value
            } else {
                value.take(maxLength - 1).trimEnd() + "…"
            }
        }
