package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.network.sync.initialThreadRepliesReadModel
import build.raft.app.resources.MR
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A message's footer and thread summary, light and dark (artin task #20, gaps 6, 7, 23):
 *  - the first message is saved, has your reaction (pale pink) and someone else's, the add
 *    chip, and four replies with one new and a draft;
 *  - the second has one reaction and two read replies.
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 *
 * The reaction sprite atlas is decoded here, synchronously, instead of through
 * [ThreadReactionAtlasProvider]: that provider loads it asynchronously, so whether the glyphs were
 * drawn by the time of the capture depended on what earlier tests in the same JVM had already
 * loaded, and the golden flipped when the unit tests were spread over several forks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h460dp-xxhdpi")
class ThreadMessageFooterScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    // The reaction glyphs log through the live page's bridge: give them one.
    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-message-footer-screenshot"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    @Test fun footerLight() = capture("thread.message-footer", RaftAppAppearance.Light)

    @Test fun footerDark() = capture("thread.message-footer.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = livePager
            val atlas =
                remember {
                    it.assets.open(MR.assets.reactions.reaction_atlas_v1.originalPath).use { stream ->
                        BitmapFactory.decodeStream(stream).asImageBitmap()
                    }
                }
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
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftTimeContextProvider provides clock,
            ) {
                RaftDesignSystemTheme {
                    CompositionLocalProvider(LocalThreadReactionAtlasImage provides atlas) {
                        Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(top = RaftAppSpacing.Sm)) {
                            FooterRows()
                        }
                    }
                }
            }
        }
}

private fun reply(id: String, seq: Long, name: String, content: String, createdAt: String) =
    ReplyPreview(
        messageId = id,
        seq = seq,
        content = content,
        senderId = "user-$name".lowercase(),
        senderType = "user",
        senderName = name.lowercase(),
        senderAvatarUrl = null,
        createdAt = createdAt,
        senderDisplayName = name,
    )

@Composable
private fun FooterRows() {
    val rows =
        listOf(
            RaftThreadMessageItem(
                id = "m-1",
                authorId = "agent-alpha",
                authorName = "Alpha",
                authorKind = "agent",
                avatarLabel = "A",
                seq = 1,
                timestampLabel = "2026-10-22T01:30:00Z",
                body = "Deploying v0.30.1 to staging after lunch: the new DM notification logic and the agent restart fix.",
                isSaved = true,
                reactions =
                    listOf(
                        RaftThreadReactionItem(emoji = "👍", count = 3, selected = true),
                        RaftThreadReactionItem(emoji = "🎉", count = 1),
                    ),
                replyCount = 4,
                unreadReplyCount = 1,
                inlineReplies =
                    initialThreadRepliesReadModel(
                        replies =
                            listOf(
                                reply("r-1", 2, "Carol", "Is the migration safe to run twice?", "2026-10-22T01:52:00Z"),
                                reply("r-2", 3, "Alpha", "Yes, no breaking changes. The new column has a default value.", "2026-10-22T02:08:00Z"),
                            ),
                        replyCount = 4,
                        limit = 3,
                    ),
            ),
            RaftThreadMessageItem(
                id = "m-2",
                authorId = "user-me",
                authorName = "Me",
                authorKind = "human",
                isOwn = true,
                avatarLabel = "M",
                seq = 5,
                timestampLabel = "2026-10-22T02:00:00Z",
                body = "Thanks, I'll watch the dashboards.",
                reactions = listOf(RaftThreadReactionItem(emoji = "👀", count = 2)),
                replyCount = 2,
                inlineReplies =
                    initialThreadRepliesReadModel(
                        replies = listOf(reply("r-3", 6, "Carol", "Ping me if the error rate moves.", "2026-10-22T02:05:00Z")),
                        replyCount = 2,
                        limit = 3,
                    ),
            ),
        )
    rows.forEach { message ->
        ThreadMessageCard(
            message = message,
            messageBodyTextStyle = raftMessageBodyTextStyle("md"),
            hasReplyDraft = message.id == "m-1",
            showReactionAddAction = true,
            timestampLabel = "9:${message.timestampLabel.substring(14, 16)} AM",
            onShowActions = {},
            onOpenMember = {},
            onOpenThread = {},
            onOpenTask = {},
            onOpenTaskStatusMenu = {},
            onToggleReaction = {},
            onOpenAttachment = {},
            onOpenMarkdownTag = { _, _ -> },
            onOpenMarkdownUrl = {},
        )
    }
}
