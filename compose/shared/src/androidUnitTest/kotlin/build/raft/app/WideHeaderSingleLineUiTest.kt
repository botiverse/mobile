package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.RaftEventBus
import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.app.thread.LocalThreadHeaderMenuEventBus
import build.raft.app.thread.LocalThreadHeaderOriginalState
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ThreadHeaderDisplayModel
import build.raft.app.thread.ThreadHeaderOriginal
import build.raft.app.thread.ThreadHeaderOriginalState
import build.raft.app.thread.ThreadPageHeader
import build.raft.app.thread.ThreadReplyPageTitle
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * raft-design desktop-density rule 2 & panel.md (#proj-mobile:#330): from 768px (`RaftScale.Wide`,
 * including iPhone Duo inner landscape 951x669) every head lays its title and subtitle on one
 * baseline; only a compact phone stacks the subtitle under the title.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w951dp-h669dp-mdpi")
class WideHeaderSingleLineUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    private object QuietEventBus : RaftEventBus {
        override fun observe(eventName: String, crossProcess: Boolean, listener: (JsonObject?) -> Unit) = RealtimeSubscription {}

        override fun post(eventName: String, eventData: JsonObject, crossProcess: Boolean, source: String) = Unit
    }

    private fun ComposeUiTest.renderInScale(scale: RaftScale, content: @Composable () -> Unit) {
        setContent {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalThreadHeaderMenuEventBus provides QuietEventBus,
            ) {
                RaftTheme(appearance = RaftAppearance.Light, scale = scale) {
                    RaftDesignSystemTheme {
                        Column(Modifier.fillMaxSize()) { content() }
                    }
                }
            }
        }
    }

    @Test
    fun wideChannelHeaderPlacesSubtitleInlineAfterTitle() =
        runRobolectricComposeUiTest {
            val payload =
                RaftThreadScreenPayload(
                    instanceId = "wide-channel",
                    sourceThreadId = "proj-mobile",
                    title = "proj-mobile",
                    subtitle = "Mobile app releases and Duo layout",
                    status = "content",
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    selectedTab = "chat",
                    showModeTabs = true,
                    showSettingsAction = true,
                )
            renderInScale(RaftScale.Wide) {
                ThreadPageHeader(
                    payload = payload,
                    headerDisplay =
                        ThreadHeaderDisplayModel(
                            title = "proj-mobile",
                            subtitle = "Mobile app releases and Duo layout",
                            surfaceKind = RaftThreadSurfaceKind.Channel,
                            channelKind = "channel",
                        ),
                    applyTopSafeArea = false,
                    onBack = {},
                    onOpenInChannel = {},
                    onOpenMemberProfile = {},
                    onOpenSearch = {},
                    onShowMembers = {},
                    onShowSettings = {},
                )
            }
            val titleBounds = onNodeWithText("proj-mobile").getUnclippedBoundsInRoot()
            val subtitleBounds = onNodeWithText("Mobile app releases and Duo layout").getUnclippedBoundsInRoot()
            assertTrue(
                subtitleBounds.left >= titleBounds.right,
                "expected subtitle right of title on Wide, got title=$titleBounds subtitle=$subtitleBounds",
            )
            val titleMidY = (titleBounds.top.value + titleBounds.bottom.value) / 2f
            val subMidY = (subtitleBounds.top.value + subtitleBounds.bottom.value) / 2f
            assertTrue(
                abs(titleMidY - subMidY) <= 6f,
                "expected title and subtitle on one line on Wide, got title=$titleBounds subtitle=$subtitleBounds",
            )
        }

    @Test
    fun wideSwappedReplyThreadHeaderPlacesChannelLinkInlineAndPreservesRoomForLongPreview() =
        runRobolectricComposeUiTest {
            val longOriginalBody =
                "This is a deliberately very long original message preview that should truncate before squeezing out the parent channel link and reply count"
            val origin =
                RaftThreadMessageItem(
                    id = "msg-1",
                    authorId = "agent-1",
                    authorName = "Arnold",
                    authorHandle = "arnold",
                    authorKind = "agent",
                    body = longOriginalBody,
                )
            val state =
                ThreadHeaderOriginalState().apply {
                    original = ThreadHeaderOriginal.Shown(messageId = "msg-1", sender = "Arnold", text = longOriginalBody)
                    originOutOfView = true
                }
            val payload =
                RaftThreadScreenPayload(
                    instanceId = "wide-reply-thread",
                    title = ThreadReplyPageTitle,
                    status = "content",
                    surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                    parentChannelId = "ch-1",
                    parentMessageId = "msg-1",
                    sourceThreadId = "thread-1",
                    originStatus = "loaded",
                    originMessage = origin,
                )
            renderInScale(RaftScale.Wide) {
                CompositionLocalProvider(LocalThreadHeaderOriginalState provides state) {
                    ThreadPageHeader(
                        payload = payload,
                        headerDisplay =
                            ThreadHeaderDisplayModel(
                                title = ThreadReplyPageTitle,
                                subtitle = "#engineering",
                                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                                channelKind = "channel",
                            ),
                        originMessage = origin,
                        originReplyCount = 8,
                        applyTopSafeArea = false,
                        onBack = {},
                        onOpenInChannel = {},
                        onOpenMemberProfile = {},
                        onOpenSearch = {},
                        onShowMembers = {},
                        onShowSettings = {},
                    )
                }
            }
            val titleBounds = onNodeWithText("Arnold:\u00A0$longOriginalBody").getUnclippedBoundsInRoot()
            val channelBounds = onNodeWithText("#engineering").getUnclippedBoundsInRoot()
            val countBounds = onNodeWithText(" · 8 replies").getUnclippedBoundsInRoot()
            assertTrue(
                channelBounds.left >= titleBounds.right,
                "expected channel link inline after swapped title on Wide, got title=$titleBounds channel=$channelBounds",
            )
            assertTrue(
                channelBounds.right > channelBounds.left && countBounds.right > countBounds.left,
                "expected swapped title not to erase channel link or reply count: channel=$channelBounds count=$countBounds",
            )
            val titleMidY = (titleBounds.top.value + titleBounds.bottom.value) / 2f
            val channelMidY = (channelBounds.top.value + channelBounds.bottom.value) / 2f
            assertTrue(
                abs(titleMidY - channelMidY) <= 6f,
                "expected swapped title and channel link on one line on Wide, got title=$titleBounds channel=$channelBounds",
            )
        }

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h600dp-xxhdpi")
    fun compactPhoneChannelHeaderKeepsSubtitleStackedUnderTitle() =
        runRobolectricComposeUiTest {
            val payload =
                RaftThreadScreenPayload(
                    instanceId = "compact-channel",
                    sourceThreadId = "proj-mobile",
                    title = "proj-mobile",
                    subtitle = "Mobile app releases",
                    status = "content",
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    selectedTab = "chat",
                    showModeTabs = true,
                    showSettingsAction = true,
                )
            renderInScale(RaftScale.Compact) {
                ThreadPageHeader(
                    payload = payload,
                    headerDisplay =
                        ThreadHeaderDisplayModel(
                            title = "proj-mobile",
                            subtitle = "Mobile app releases",
                            surfaceKind = RaftThreadSurfaceKind.Channel,
                            channelKind = "channel",
                        ),
                    applyTopSafeArea = false,
                    onBack = {},
                    onOpenInChannel = {},
                    onOpenMemberProfile = {},
                    onOpenSearch = {},
                    onShowMembers = {},
                    onShowSettings = {},
                )
            }
            val titleBounds = onNodeWithText("proj-mobile").getUnclippedBoundsInRoot()
            val subtitleBounds = onNodeWithText("Mobile app releases").getUnclippedBoundsInRoot()
            assertTrue(
                subtitleBounds.top.value >= titleBounds.bottom.value - 1f,
                "expected subtitle stacked under title on Compact phone, got title=$titleBounds subtitle=$subtitleBounds",
            )
        }

    @Test
    fun wideTopAndPanelBackHeadersDefaultToInlineSubtitle() =
        runRobolectricComposeUiTest {
            renderInScale(RaftScale.Wide) {
                RaftTopPageHeader(
                    title = "Saved",
                    subtitle = "12 items",
                    applyTopSafeArea = false,
                    showBodyTopBanner = false,
                )
                RaftPanelBackHeader(
                    title = "Files",
                    subtitle = "4 attachments",
                    onBack = {},
                )
            }
            val savedTitle = onNodeWithText("Saved").getUnclippedBoundsInRoot()
            val savedSub = onNodeWithText("12 items").getUnclippedBoundsInRoot()
            assertTrue(
                savedSub.left >= savedTitle.right,
                "expected RaftTopPageHeader subtitle inline on Wide: title=$savedTitle sub=$savedSub",
            )
            val filesTitle = onNodeWithText("Files").getUnclippedBoundsInRoot()
            val filesSub = onNodeWithText("4 attachments").getUnclippedBoundsInRoot()
            assertTrue(
                filesSub.left >= filesTitle.right,
                "expected RaftPanelBackHeader subtitle inline on Wide: title=$filesTitle sub=$filesSub",
            )
        }
}
