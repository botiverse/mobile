package build.raft.app

import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
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
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.compose.ui.zIndex

private const val ForegroundBannerAutoDismissMs = 5_000L
private const val ForegroundBannerPreviewMaxLength = 96
private const val ForegroundBannerZIndex = SlockFloatingOverlayHostZIndex + 10f

internal fun slockForegroundBannerAutoDismissMs(): Long = ForegroundBannerAutoDismissMs

internal data class SlockForegroundMessageBanner(
    val id: String,
    val serverId: String,
    val focusedMessageId: String,
    val conversation: ThreadLaunchConversationIdentity,
    val title: String,
    val body: String
)

@Composable
internal fun SlockForegroundBannerHost(
    banner: SlockForegroundMessageBanner?,
    topSafeArea: Dp,
    onBannerClick: (SlockForegroundMessageBanner) -> Unit,
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        content()
        if (banner != null) {
            SlockForegroundMessageBannerView(
                banner = banner,
                topSafeArea = topSafeArea,
                onClick = { onBannerClick(banner) }
            )
        }
    }
}

@Composable
internal fun SlockForegroundMessageBannerView(
    banner: SlockForegroundMessageBanner,
    topSafeArea: Dp,
    onClick: () -> Unit
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .zIndex(ForegroundBannerZIndex)
                .padding(start = 12.dp, top = topSafeArea + 10.dp, end = 12.dp)
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .align(Alignment.TopCenter)
                    .height(74.dp)
                    .clickable(role = Role.Button, onClick = onClick)
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
                        .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(38.dp)
                            .background(SlockColor.BrandYellow)
                            .border(SlockStroke.Default, SlockColor.BorderBlack),
                    contentAlignment = Alignment.Center
                ) {
                    SlockLucideIcon(SlockLucideIconName.MessageSquare, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = banner.title,
                        style = SlockType.ButtonBold.copy(fontSize = 15.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = banner.body,
                        style = SlockType.Caption.copy(fontSize = 13.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                SlockLucideIcon(
                    SlockLucideIconName.ChevronRight,
                    modifier = Modifier.size(18.dp),
                    color = SlockColor.TextBlackArgb
                )
            }
        }
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
): SharedConversationDirectoryState {
    val byCanonicalId =
        projections
            .filter { it.canonicalChannelId.isNotBlank() }
            .associateBy { it.canonicalChannelId }
    val aliasesById =
        buildMap<String, MutableSet<String>> {
            byCanonicalId.values.forEach { projection ->
                fun put(alias: String?) {
                    val id = alias?.takeIf { it.isNotBlank() } ?: return
                    getOrPut(id) { linkedSetOf() }.add(projection.canonicalChannelId)
                }
                put(projection.canonicalChannelId)
                projection.aliases.forEach(::put)
                put(projection.parentChannelId)
                put(projection.parentMessageId)
            }
        }.mapValues { (_, values) -> values.toSet() }
    return SharedConversationDirectoryState(
        projectionsByCanonicalId = byCanonicalId,
        aliasesById = aliasesById,
        duplicateAliases = aliasesById.filterValues { it.size > 1 }
    )
}

internal fun slockForegroundMessageBannerProjection(
    serverId: String,
    currentServerId: String?,
    message: Message,
    currentMemberId: String?,
    visibleConversationKey: String?,
    visibleRouteIdentity: ThreadRouteIdentity? = null,
    conversationDirectory: SharedConversationDirectoryState = SharedConversationDirectoryState()
): SlockForegroundMessageBanner? {
    val normalizedServerId = serverId.takeIf { it.isNotBlank() } ?: return null
    if (!currentServerId.isNullOrBlank() && currentServerId != normalizedServerId) return null
    if (!currentMemberId.isNullOrBlank() && message.author.id.value == currentMemberId) return null
    val conversation = message.resolveForegroundBannerConversation(conversationDirectory) ?: return null
    if (conversation.matchesForegroundVisibleRoute(visibleConversationKey, visibleRouteIdentity)) return null
    val sender = message.author.displayName.ifBlank { message.author.handle.ifBlank { "Someone" } }
    val title =
        message.resolveForegroundBannerConversationTitle(conversationDirectory)
            ?.let { "$sender in $it" }
            ?: sender
    val body = message.body.toForegroundBannerPreview().ifBlank { "Sent a new message." }
    return SlockForegroundMessageBanner(
        id = "foreground:$normalizedServerId:${message.id.value}",
        serverId = normalizedServerId,
        focusedMessageId = message.id.value,
        conversation = conversation,
        title = title,
        body = body
    )
}

private fun Message.resolveForegroundBannerConversation(
    directory: SharedConversationDirectoryState
): ThreadLaunchConversationIdentity? {
    val projection = resolveForegroundBannerProjection(directory)
    if (projection != null) return projection.toForegroundThreadLaunchConversationIdentity()
    val parentMessage = parentMessageId
    val parentChannel = channelId ?: threadChannelId
    if (parentMessage != null && parentChannel != null) {
        val streamChannel = threadChannelId?.takeIf { it.value.isNotBlank() }
        return ThreadLaunchConversationIdentity(
            threadId = threadIdentity(parentChannel, parentMessage),
            channelId = parentChannel,
            channelType = "thread",
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

private fun Message.resolveForegroundBannerConversationTitle(
    directory: SharedConversationDirectoryState
): String? =
    resolveForegroundBannerProjection(directory)
        ?.title
        ?.takeIf { it.isNotBlank() }
        ?.let { title ->
            if (title.startsWith("#")) title else "#$title"
        }

private fun Message.resolveForegroundBannerProjection(
    directory: SharedConversationDirectoryState
): SharedConversationDirectoryProjection? =
    foregroundBannerLookupIds()
        .firstNotNullOfOrNull(directory::resolve)

private fun Message.foregroundBannerLookupIds(): List<String> =
    listOfNotNull(
        threadChannelId?.value,
        channelId?.value,
        threadId.value,
        parentMessageId?.value
    ).map { it.trim() }.filter { it.isNotBlank() }.distinct()

private fun SharedConversationDirectoryProjection.toForegroundThreadLaunchConversationIdentity(): ThreadLaunchConversationIdentity {
    val isReplyThread = surfaceKind == KuiklyThreadSurfaceKind.ReplyThread
    val parentChannel = parentChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
    val parentMessage = parentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId)
    val streamChannel = canonicalChannelId.takeIf { it.isNotBlank() }?.let(::ChannelId)
    val normalizedChannelKind = channelKind.takeIf { it.isNotBlank() }
    return ThreadLaunchConversationIdentity(
        threadId =
            if (isReplyThread && parentChannel != null && parentMessage != null) {
                threadIdentity(parentChannel, parentMessage)
            } else {
                ThreadId(canonicalChannelId)
            },
        channelId = if (isReplyThread) parentChannel else streamChannel,
        channelType = normalizedChannelKind,
        thread =
            if (isReplyThread && parentChannel != null && parentMessage != null) {
                ThreadConversation(
                    parentChannelId = parentChannel,
                    parentMessageId = parentMessage,
                    streamChannelId = streamChannel,
                    parentSurfaceKind = KuiklyThreadSurfaceKind.Channel,
                    parentChannelKind = normalizedChannelKind?.takeUnless { it == "thread" }
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
