package build.raft.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.markdown.LocalRaftMessagePermalinkLabelResolver
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ForwardMessageAttachment
import build.raft.app.model.ForwardMessageBundle
import build.raft.app.model.ForwardMessageItem
import build.raft.app.model.ForwardMessageSource
import build.raft.app.model.MessageId
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePainter
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import coil3.compose.asPainter
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin #20 lane I (gaps 17, 18): a quoted message and a forwarded bundle as the web draws them,
 * light and dark.
 *  - `thread.quoted-message`: a channel message, a thread reply in an archived channel with
 *    files, loading and unavailable.
 *  - `thread.forwarded-bundle`: a bundle from a public channel with a long first message (the
 *    list folds at 8 lines with a fade), and one with a gallery of projected images and a file.
 * Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class ThreadQuotedForwardedScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    // Avatars and glyphs log through the live page's bridge: give them one.
    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-quoted-forwarded-screenshot"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    @Test fun quotedLight() = capture("thread.quoted-message", RaftAppAppearance.Light) { QuotedRows() }

    @Test fun quotedDark() = capture("thread.quoted-message.dark", RaftAppAppearance.Dark) { QuotedRows() }

    @Test fun forwardedLight() = capture("thread.forwarded-bundle", RaftAppAppearance.Light) { ForwardedRows() }

    @Test fun forwardedDark() = capture("thread.forwarded-bundle.dark", RaftAppAppearance.Dark) { ForwardedRows() }

    @OptIn(ExperimentalCoilApi::class)
    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) {
        runCatching { build.raft.app.image.raftInstallImageLoader() }
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val clock =
                remember {
                    RaftTimeContextProvider(
                        RaftTimeContext(
                            localOffsetMinutes = 8 * 60,
                            todayEpochDay = raftEpochDayForDate(2026, 10, 22),
                            nowEpochMillis = 1_792_635_000_000L,
                        ),
                    )
                }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(livePager),
                LocalRaftTimeContextProvider provides clock,
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides QuotedForwardedFixtureImages,
                LocalRaftMessagePermalinkLabelResolver provides { target ->
                    if (target.channelId.value == "c-launch") "launch-week" else "old-launch"
                },
                LocalThreadInlineImageFallbackHost provides
                    ThreadInlineImageFallbackHost(
                        state =
                            ThreadInlineImageFallbackState(
                                resolvedUrls = mapOf("img-1" to "fixture://sky", "img-2" to "fixture://sand", "img-3" to "fixture://moss"),
                            ),
                        requestFallback = {},
                    ),
            ) {
                RaftDesignSystemTheme {
                    Column(
                        Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) { content() }
                }
            }
        }
    }
}

@OptIn(ExperimentalCoilApi::class)
private val QuotedForwardedFixtureImages =
    AsyncImagePreviewHandler { _, request ->
        val color =
            when ((request.data as? String)?.removePrefix("fixture://")) {
                "sky" -> 0xFF8EC9F0.toInt()
                "sand" -> 0xFFE9C98B.toInt()
                "moss" -> 0xFF9CC79A.toInt()
                else -> null
            }
        if (color == null) {
            AsyncImagePainter.State.Error(null, ErrorResult(null, request, IllegalStateException("fixture: no image")))
        } else {
            val image = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.asImage()
            AsyncImagePainter.State.Success(image.asPainter(request.context), SuccessResult(image, request))
        }
    }

private fun permalink(channel: String, thread: Boolean = false) =
    RaftMessagePermalinkTarget(
        rawUrl = "https://raft.example/s/acme/c/$channel/m/msg-1",
        serverSlug = "acme",
        routeKind = ConversationKind.Channel,
        channelId = ChannelId(channel),
        messageId = MessageId("msg-1"),
        threadParentMessageId = if (thread) MessageId("msg-0") else null,
    )

@Composable
private fun QuotedRows() {
    ThreadQuotedPermalinkCard(
        state =
            ThreadQuotedPermalinkUiState.Loaded(
                ThreadQuotedPermalinkMessage(
                    RaftThreadMessageItem(
                        id = "msg-1",
                        authorId = "agent-alpha",
                        authorName = "Alpha",
                        authorKind = "agent",
                        timestampLabel = "2026-10-22T00:24:00Z",
                        body = "Rolled back the **migration**; the error rate is flat again. I'll keep watching the dashboard for an hour before the next step.",
                    ),
                ),
            ),
        target = permalink("c-launch"),
        memberAvatarResolver = EmptyRaftMemberAvatarResolver,
        onOpen = {},
    )
    ThreadQuotedPermalinkCard(
        state =
            ThreadQuotedPermalinkUiState.Loaded(
                ThreadQuotedPermalinkMessage(
                    RaftThreadMessageItem(
                        id = "msg-2",
                        authorId = "user-carol",
                        authorName = "Carol",
                        authorKind = "human",
                        timestampLabel = "2026-10-20T07:16:00Z",
                        body = "Release notes and screenshots from the last run.",
                        attachments =
                            listOf(
                                RaftThreadAttachmentItem(id = "a", name = "notes.md", mimeType = "text/markdown"),
                                RaftThreadAttachmentItem(id = "b", name = "shot.png", mimeType = "image/png"),
                            ),
                    ),
                    channelArchived = true,
                ),
            ),
        target = permalink("c-old", thread = true),
        memberAvatarResolver = EmptyRaftMemberAvatarResolver,
        onOpen = {},
    )
    ThreadQuotedPermalinkCard(
        state = ThreadQuotedPermalinkUiState.Loading,
        target = null,
        memberAvatarResolver = EmptyRaftMemberAvatarResolver,
        onOpen = {},
    )
    ThreadQuotedPermalinkCard(
        state = ThreadQuotedPermalinkUiState.Unavailable,
        target = null,
        memberAvatarResolver = EmptyRaftMemberAvatarResolver,
        onOpen = {},
    )
}

@Composable
private fun ForwardedRows() {
    val bodyStyle = raftMessageBodyTextStyle("md")
    ThreadForwardBundleCard(
        bundle =
            ForwardMessageBundle(
                title = "Forwarded",
                sourceTitle = "launch-week",
                totalCount = 3,
                visibleSource = ForwardMessageSource("#launch-week"),
                messages =
                    listOf(
                        ForwardMessageItem(
                            id = "f-1",
                            authorId = "agent-alpha",
                            authorName = "@alpha",
                            timestampLabel = "2026-10-22T00:24:00Z",
                            body =
                                "The rollout plan: first the **canary**, then a tenth of the fleet, then everyone. " +
                                    "Each step waits for an hour of clean error rates before the next one starts, " +
                                    "and any alert pages the on-call engineer and stops the rollout. " +
                                    "We keep `v0.30.0` warm for a quick rollback and post every step in this channel.",
                        ),
                        ForwardMessageItem(
                            id = "f-2",
                            authorId = "user-carol",
                            authorName = "@carol",
                            timestampLabel = "2026-10-22T00:31:00Z",
                            body = "Sounds right. Who is on call this week?",
                        ),
                        ForwardMessageItem(
                            id = "f-3",
                            authorId = "user-dana",
                            authorName = "@dana",
                            timestampLabel = "2026-10-22T00:33:00Z",
                            body = "Me.",
                        ),
                    ),
            ),
        messageBodyTextStyle = bodyStyle,
        onOpenAll = {},
    )
    ThreadForwardBundleCard(
        bundle =
            ForwardMessageBundle(
                title = "Forwarded",
                totalCount = 1,
                messages =
                    listOf(
                        ForwardMessageItem(
                            id = "f-4",
                            authorId = "user-carol",
                            authorName = "@carol",
                            timestampLabel = "2026-10-21T09:05:00Z",
                            body = "Screenshots from the run.",
                            attachmentCount = 4,
                            attachmentsProjected = true,
                            attachments =
                                listOf(
                                    ForwardMessageAttachment("home.png", "image/png", id = "img-1"),
                                    ForwardMessageAttachment("thread.png", "image/png", id = "img-2"),
                                    ForwardMessageAttachment("settings.png", "image/png", id = "img-3"),
                                    ForwardMessageAttachment("release-notes.md", "text/markdown", id = "doc-1"),
                                ),
                        ),
                    ),
            ),
        messageBodyTextStyle = bodyStyle,
        onOpenAll = {},
    )
}
