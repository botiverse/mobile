package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.base.BasePager
import build.raft.app.cmp.host.LocalRaftHost
import build.raft.app.compat.pager.Page
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import androidx.compose.material3.Text

/*
 * PROTOTYPE (agent/lagoon/ios-native-nav-proto, not for merge): fixture pages for the "fully
 * native iOS navigation" experiment. They render production composables from fixture data (no
 * session), so the iOS demo can show Home -> #design -> Members with and without the host's
 * UINavigationBar (RaftNativeNavBar). The page decides nothing about the bar itself: the shared
 * RaftTopPageHeader publishes to the host bar when the host registered one.
 */

internal const val NativeNavProtoThreadPageName = "compose_native_nav_proto_thread"
internal const val NativeNavProtoMembersPageName = "compose_channel_members_parity_visual"
internal const val NativeNavProtoChannelSettingsPageName = "compose_native_nav_proto_channel_settings"
internal const val NativeNavProtoSettingsDetailPageName = "compose_native_nav_proto_settings_detail"

/**
 * Home root without its own tab bar (the host's UITabBarController draws it, as in the app).
 * With a host bar Home's head publishes the server title menu and the bell to it.
 */
@Page("compose_native_nav_proto_home", supportInLocal = true)
internal class ComposeNativeNavProtoHomePage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent {
            val host = LocalRaftHost.current
            HomeChannelListVisualContent(
                showBottomBar = false,
                // Phase 2: Home publishes its own head (server title menu + bell) to the host bar.
                serverSwitcherInTopBar = false,
                onChannelClick = { host.navigator.openPage(NativeNavProtoThreadPageName, JsonObject(emptyMap())) },
            )
        }
    }
}

/** A channel conversation: the production head, mode tabs, fixture messages and composer. */
@Page("compose_native_nav_proto_thread", supportInLocal = true)
internal class ComposeNativeNavProtoThreadPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { NativeNavProtoThreadScreen() }
    }
}

private fun nativeNavProtoThreadPayload(): RaftThreadScreenPayload {
    val instanceId = "native-nav-proto"
    val base = threadVisualFixture(THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT, instanceId)
    val messages =
        listOf(
            THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT,
            THREAD_MESSAGE_VISUAL_FIXTURE_INLINE_REPLIES,
            THREAD_MESSAGE_VISUAL_FIXTURE_RICH_CONTENT,
            THREAD_MESSAGE_VISUAL_FIXTURE_MD_LATEST_RELEASE,
        ).flatMap { threadVisualFixture(it, instanceId).messages }
            .distinctBy { it.id }
    return base.copy(
        subtitle = "Design reviews and UI polish",
        showModeTabs = true,
        showMembersAction = true,
        showSettingsAction = true,
        membersCount = 5,
        // Shows the head's refresh spinner (the native title view's activity indicator).
        backgroundRefreshing = true,
        messages = messages,
    )
}

@Composable
private fun NativeNavProtoThreadScreen() {
    val host = LocalRaftHost.current
    val payload = remember { nativeNavProtoThreadPayload() }
    val messages = remember(payload) { payload.toThreadTimelineSlice().messages }
    val replyMetadataByParentId = remember(payload) { payload.toThreadReplyMetadataMap() }
    val messageBodyTextStyle = remember { raftMessageBodyTextStyle("") }
    ThreadReactionAtlasProvider {
        Column(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
            ThreadPageHeader(
                payload = payload,
                onBack = { host.navigator.closePage() },
                onOpenInChannel = {},
                onOpenMemberProfile = {},
                onOpenSearch = {},
                onShowMembers = { host.navigator.openPage(NativeNavProtoMembersPageName, JsonObject(emptyMap())) },
                onShowSettings = { host.navigator.openPage(NativeNavProtoChannelSettingsPageName, JsonObject(emptyMap())) },
                // The More menu's rows, so the native ⋯ has them.
                onToggleFocusMode = {},
                threadFollowed = true,
                onToggleThreadFollow = {},
                onMarkThreadUnread = {},
            )
            ThreadNativeNavProtoModeTabs()
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // Start at the newest message, like the conversation.
                        .verticalScroll(rememberScrollState(), reverseScrolling = true)
                        .padding(start = RaftAppSpacing.Md, top = RaftAppSpacing.Md, end = RaftAppSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                messages.forEach { message ->
                    ThreadMessageCard(
                        memberAvatarResolver = ThreadVisualMemberAvatarResolver,
                        message = message,
                        messageBodyTextStyle = messageBodyTextStyle,
                        replyMetadata = replyMetadataByParentId.threadReplyMetadataFor(message),
                        alignOwnMessageRight = payload.alignOwnMessagesRight && message.isOwn,
                        messageAlignmentModeEnabled = payload.alignOwnMessagesRight,
                        timestampLabel = message.timestampLabel,
                        onShowActions = {},
                        onOpenMember = {},
                        onOpenThread = {},
                        onOpenTask = {},
                        onOpenTaskStatusMenu = {},
                        onToggleReaction = {},
                        onOpenAttachment = {},
                        onOpenMarkdownTag = { _, _ -> },
                        onOpenMarkdownUrl = {},
                        outerPadding = RaftAppSpacing.Sm,
                    )
                }
            }
            ThreadComposerContent(
                payload = RaftThreadComposerPayload(instanceId = "native-nav-proto", placeholder = "Message #design"),
                attachmentPreviewEnabled = false,
                onEvent = { _, _, _, _ -> },
            )
            Spacer(modifier = Modifier.height(raftBottomSafeAreaPadding()))
        }
    }
}

// --- Phase 2: the other shell tabs and pushed pages, each through its production head ---------

/** The Activity tab (production SharedHomeActivityScreen, its main-tab head). */
@Page("compose_native_nav_proto_activity", supportInLocal = true)
internal class ComposeNativeNavProtoActivityPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftActivityTabVisualContent(protoFullScreen = true) }
    }
}

/** The Tasks tab (production SharedTasksPanel, its main-tab head). */
@Page("compose_native_nav_proto_tasks", supportInLocal = true)
internal class ComposeNativeNavProtoTasksPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { TasksPanelVisualContent(protoFullScreen = true) }
    }
}

/** The More / Settings tab (production SettingsHomeContent); a row opens its Settings page. */
@Page("compose_native_nav_proto_more", supportInLocal = true)
internal class ComposeNativeNavProtoMorePage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent {
            val host = LocalRaftHost.current
            SettingsHomeContent(
                payload = settingsTabBarSmokePayload,
                // The developer unlock's hidden title tap, a no-op here.
                onHeaderClick = {},
                onSelect = { key ->
                    host.navigator.openPage(
                        NativeNavProtoSettingsDetailPageName,
                        JsonObject(mapOf("variant" to JsonPrimitive(if (key == "server") "server-profile" else "account"))),
                    )
                },
            )
        }
    }
}

/** A Settings detail page (production SettingsDetailShell). */
@Page("compose_native_nav_proto_settings_detail", supportInLocal = true)
internal class ComposeNativeNavProtoSettingsDetailPage : BasePager() {
    override fun willInit() {
        super.willInit()
        val variant = pageData.params.optString("variant").ifBlank { "account" }
        setContent {
            val host = LocalRaftHost.current
            SettingsPageVisualContent(protoVariant = variant, protoOnBack = { host.navigator.closePage() })
        }
    }
}

/** Saved (production SharedHomeSavedPanel, its panel head with Back). */
@Page("compose_native_nav_proto_saved", supportInLocal = true)
internal class ComposeNativeNavProtoSavedPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent {
            val host = LocalRaftHost.current
            HomeSavedVisualContent(protoOnBack = { host.navigator.closePage() })
        }
    }
}

/** An agent's profile (production MembersDetailContent: Message and the management ⋯). */
@Page("compose_native_nav_proto_agent", supportInLocal = true)
internal class ComposeNativeNavProtoAgentPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent {
            val host = LocalRaftHost.current
            MembersAgentDetailVisualContent(protoOnBack = { host.navigator.closePage() })
        }
    }
}

/** Channel settings: the production scaffold's head (Back, title, refresh mark) over a stand-in body. */
@Page("compose_native_nav_proto_channel_settings", supportInLocal = true)
internal class ComposeNativeNavProtoChannelSettingsPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent {
            val host = LocalRaftHost.current
            ChannelSettingsScaffold(refreshing = false, onBack = { host.navigator.closePage() }) {
                Column(modifier = Modifier.fillMaxSize().padding(RaftAppSpacing.Lg), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("#design", style = RaftType.PageTitle, color = RaftColor.TextBlack)
                    Text("Design reviews and UI polish", style = RaftType.Body, color = RaftColor.TextMutedGray)
                    Text("(prototype stand-in body: the head is the production one)", style = RaftType.Caption, color = RaftColor.ForegroundHint)
                }
            }
        }
    }
}
