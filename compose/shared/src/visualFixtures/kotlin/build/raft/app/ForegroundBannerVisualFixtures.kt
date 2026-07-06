package build.raft.app

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import build.raft.app.model.threadIdentity
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.unit.dp

@Composable
internal fun ForegroundBannerVisualContent() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceWhite)
    ) {
        SlockForegroundMessageBannerView(
            banner =
                foregroundBannerVisualBanner(
                    id = "channel",
                    conversation =
                        ThreadLaunchConversationIdentity(
                            threadId = ThreadId("visual-testing"),
                            channelId = ChannelId("visual-testing"),
                            channelType = "channel",
                            kind = ConversationKind.Channel
                        ),
                    title = "Cindy",
                    context = "#visual-testing - now",
                    threadContext = null,
                    body = "Updated the capture manifest and fixture seed.",
                    iconKind = SlockForegroundMessageBannerIconKind.Channel,
                    iconName = SlockLucideIconName.Hash
                ),
            topSafeArea = 10.dp,
            onClick = {}
        )
        SlockForegroundMessageBannerView(
            banner =
                foregroundBannerVisualBanner(
                    id = "thread",
                    conversation =
                        ThreadLaunchConversationIdentity(
                            threadId = threadIdentity(ChannelId("native"), MessageId("origin-message")),
                            channelId = ChannelId("native"),
                            channelType = "thread",
                            thread =
                                ThreadConversation(
                                    parentChannelId = ChannelId("native"),
                                    parentMessageId = MessageId("origin-message"),
                                    streamChannelId = ChannelId("thread-stream"),
                                    parentSurfaceKind = KuiklyThreadSurfaceKind.Channel,
                                    parentChannelKind = "channel"
                                ),
                            kind = ConversationKind.Thread
                        ),
                    title = "JingKe",
                    context = "#native - thread - now",
                    threadContext = "Retry Quiver OHPM publish after package-group auth",
                    body = "HAR ref bump is ready; waiting on device validation.",
                    iconKind = SlockForegroundMessageBannerIconKind.Channel,
                    iconName = SlockLucideIconName.MessageSquare
                ),
            topSafeArea = 112.dp,
            onClick = {}
        )
        SlockForegroundMessageBannerView(
            banner =
                foregroundBannerVisualBanner(
                    id = "dm",
                    conversation =
                        ThreadLaunchConversationIdentity(
                            threadId = ThreadId("dm-artin"),
                            channelId = ChannelId("dm-artin"),
                            channelType = KuiklyThreadSurfaceKind.DirectMessage,
                            kind = ConversationKind.DirectMessage
                        ),
                    title = "artin",
                    context = "@artin - direct message - now",
                    threadContext = null,
                    body = "Can you check this connected test result?",
                    iconKind = SlockForegroundMessageBannerIconKind.DirectMessage,
                    iconName = null,
                    avatarWireValue = "pixel:robot",
                    avatarLabel = "AR"
                ),
            topSafeArea = 214.dp,
            onClick = {}
        )
    }
}

private fun foregroundBannerVisualBanner(
    id: String,
    conversation: ThreadLaunchConversationIdentity,
    title: String,
    context: String,
    threadContext: String?,
    body: String,
    iconKind: SlockForegroundMessageBannerIconKind,
    iconName: SlockLucideIconName?,
    avatarWireValue: String? = null,
    avatarLabel: String = ""
): SlockForegroundMessageBanner =
    SlockForegroundMessageBanner(
        id = "visual-foreground-$id",
        serverId = VisualFixtureData.Server.ID,
        focusedMessageId = "visual-message-$id",
        conversation = conversation,
        title = title,
        context = context,
        threadContext = threadContext,
        body = body,
        iconKind = iconKind,
        iconName = iconName,
        avatarWireValue = avatarWireValue,
        avatarLabel = avatarLabel
    )
