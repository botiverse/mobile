package build.raft.app

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import build.raft.app.model.threadIdentity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ForegroundBannerVisualContent() {
    var bannerClicked by remember { mutableStateOf(false) }
    var underlayClicked by remember { mutableStateOf(false) }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(RaftColor.SurfaceWhite)
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 18.dp, end = 12.dp)
                    .height(70.dp)
                    .clickable { underlayClicked = true }
        )
        RaftForegroundMessageBannerView(
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
                    context = "#visual-testing",
                    threadContext = null,
                    body = "Updated the capture manifest and fixture seed.",
                    iconKind = RaftForegroundMessageBannerIconKind.Channel,
                    iconName = RaftLucideIconName.Hash,
                    avatarWireValue = "pixel:agent-cindy",
                    avatarLabel = "CI"
                ),
            topSafeArea = 10.dp,
            onClick = { bannerClicked = true }
        )
        RaftForegroundMessageBannerView(
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
                                    parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                                    parentChannelKind = "channel"
                                ),
                            kind = ConversationKind.Thread
                        ),
                    title = "JingKe",
                    context = "#native - thread",
                    threadContext = "Retry Hands OHPM publish after package-group auth",
                    body = "HAR ref bump is ready; waiting on device validation.",
                    iconKind = RaftForegroundMessageBannerIconKind.Channel,
                    iconName = RaftLucideIconName.MessageSquare,
                    avatarWireValue = "pixel:agent-jingke",
                    avatarLabel = "JK"
                ),
            topSafeArea = 86.dp,
            onClick = {}
        )
        RaftForegroundMessageBannerView(
            banner =
                foregroundBannerVisualBanner(
                    id = "dm",
                    conversation =
                        ThreadLaunchConversationIdentity(
                            threadId = ThreadId("dm-artin"),
                            channelId = ChannelId("dm-artin"),
                            channelType = RaftThreadSurfaceKind.DirectMessage,
                            kind = ConversationKind.DirectMessage
                        ),
                    title = "artin",
                    context = "@artin - direct message",
                    threadContext = null,
                    body = "Can you check this connected test result?",
                    iconKind = RaftForegroundMessageBannerIconKind.DirectMessage,
                    iconName = null,
                    avatarWireValue = "pixel:robot",
                    avatarLabel = "AR"
                ),
            topSafeArea = 188.dp,
            onClick = {}
        )
        if (bannerClicked) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(0.5f)
                        .height(20.dp)
                        .background(RaftColor.AccentLime)
            )
        }
        if (underlayClicked) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .fillMaxWidth(0.5f)
                        .height(20.dp)
                        .background(RaftColor.AccentRed)
            )
        }
    }
}

private fun foregroundBannerVisualBanner(
    id: String,
    conversation: ThreadLaunchConversationIdentity,
    title: String,
    context: String,
    threadContext: String?,
    body: String,
    iconKind: RaftForegroundMessageBannerIconKind,
    iconName: RaftLucideIconName?,
    avatarWireValue: String? = null,
    avatarLabel: String = ""
): RaftForegroundMessageBanner =
    RaftForegroundMessageBanner(
        id = "visual-foreground-$id",
        serverId = VisualFixtureData.Server.ID,
        focusedMessageId = "visual-message-$id",
        conversation = conversation,
        openRequest =
            HomeMessageOpenRequest(
                conversationIdentity = conversation,
                anchorMessageId = "visual-message-$id"
            ),
        title = title,
        context = context,
        threadContext = threadContext,
        body = body,
        iconKind = iconKind,
        iconName = iconName,
        // Fixture supplies the avatar directly via avatarWireValue/avatarLabel,
        // so no member-store lookup is needed (task #396: #213 added this
        // required param but the fixture builder was not updated).
        senderMemberId = null,
        avatarWireValue = avatarWireValue,
        avatarLabel = avatarLabel
    )
