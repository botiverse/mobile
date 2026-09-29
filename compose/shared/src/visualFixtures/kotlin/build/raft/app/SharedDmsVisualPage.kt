package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.base.BasePager
import build.raft.app.model.SlockTimeContext
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.annotations.Page

/**
 * Visual fixture for Task #332: WeChat-style Direct Messages page in Slack-nav mode.
 *
 * Demonstrates:
 * - 48dp rounded avatars with online/agent presence status dots
 * - Red numeric unread badges (e.g. "3", "99+") and muted unread red dots
 * - WeChat-style timestamp formatting (Today: HH:mm, Yesterday, MM-DD, YYYY-MM-DD)
 * - Last message snippets, sender prefixes ("Bob: ..."), and red [Draft] indicator
 * - Mute bell icon indicator for muted conversations
 * - 72dp row height and text-aligned dividers
 */
@Page("compose_shared_dms_visual", supportInLocal = true)
internal class ComposeSharedDmsVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SharedDmsVisualContent() }
    }
}

@Composable
internal fun SharedDmsVisualContent() {
    // Fixed reference time: 2026-05-20 16:00:00 UTC (epochDay = 20593)
    val fixedTimeContext = remember {
        SlockTimeContext(
            localOffsetMinutes = 0,
            todayEpochDay = 20593L,
            nowEpochMillis = 1779292800000L
        )
    }

    var lastTappedDm by remember { mutableStateOf<String?>(null) }
    var selectedTabKey by remember { mutableStateOf("dms") }

    val tabItems = remember(selectedTabKey) {
        listOf(
            SlockBottomTabItemPayload("home", "Home", SlockLucideIconName.House, selected = selectedTabKey == "home"),
            SlockBottomTabItemPayload("dms", "DMs", SlockLucideIconName.MessageCircle, selected = selectedTabKey == "dms"),
            SlockBottomTabItemPayload("activity", "Activity", SlockLucideIconName.Bell, selected = selectedTabKey == "activity"),
            SlockBottomTabItemPayload("more", "More", SlockLucideIconName.Ellipsis, selected = selectedTabKey == "more")
        )
    }

    val mockDms = remember {
        listOf(
            SlockHomeConversationItem(
                id = "dm-1",
                name = "artin",
                kind = "dm",
                peerType = "human",
                unreadCount = 3,
                lastMessageAt = "2026-05-20T14:25:00Z", // Today
                lastMessagePreview = "Slack 底栏模式下的 DM 列表视觉需要对齐微信单行规范",
                lastMessageSenderName = "artin",
                avatarWireValue = "pixel:random:artin",
                avatarLabel = "AR"
            ),
            SlockHomeConversationItem(
                id = "dm-2",
                name = "辣条",
                kind = "dm",
                peerType = "human",
                unreadCount = 5,
                activityMuted = true,
                activityMuteSupported = true,
                lastMessageAt = "2026-05-19T22:18:00Z", // Yesterday
                lastMessagePreview = "PR2 已交付：PR #2447，SharedDmsScreen 落地后一行替换",
                lastMessageSenderName = "辣条",
                avatarWireValue = "pixel:random:latiao",
                avatarLabel = "LT"
            ),
            SlockHomeConversationItem(
                id = "dm-3",
                name = "Nathan",
                kind = "dm",
                peerType = "human",
                hasDraft = true,
                unreadCount = 0,
                lastMessageAt = "2026-05-12T09:30:00Z", // Earlier this year
                lastMessagePreview = "I've reviewed the task assignee handler changes.",
                avatarWireValue = "pixel:random:nathan",
                avatarLabel = "NA"
            ),
            SlockHomeConversationItem(
                id = "dm-4",
                name = "沙琪玛",
                kind = "dm",
                peerType = "agent",
                unreadCount = 28,
                lastMessageAt = "2026-04-10T11:00:00Z", // Earlier this year
                lastMessagePreview = "All unit tests passed for SharedDmsPageTest",
                avatarWireValue = "pixel:robot",
                avatarLabel = "SQ"
            ),
            SlockHomeConversationItem(
                id = "dm-5",
                name = "Mobile Architecture Group",
                kind = "dm",
                peerType = "human",
                unreadCount = 1,
                lastMessageAt = "2025-11-20T18:00:00Z", // Previous year
                lastMessagePreview = "Please check the KMP launch boundary RFC",
                lastMessageSenderName = "Mahua", // Sender prefix
                avatarWireValue = null,
                avatarLabel = "MA"
            ),
            SlockHomeConversationItem(
                id = "dm-6",
                name = "怪味胡豆",
                kind = "dm",
                peerType = "human",
                unreadCount = 0,
                description = "Android / Performance Engineer",
                createdAt = "2025-01-01T00:00:00Z",
                avatarWireValue = "pixel:random:guaiweihudou",
                avatarLabel = "GW"
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        SharedDmsScreen(
            directMessages = mockDms,
            onOpenConversation = { identity ->
                lastTappedDm = identity.threadId.value
            },
            timeContext = fixedTimeContext,
            showHeader = true,
            bottomContentPadding = 60.dp
        )
        SlockAppBottomTabBarHost(
            items = tabItems,
            onSelect = { selectedTabKey = it },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            bottomSafeArea = 0.dp
        )
    }
}
