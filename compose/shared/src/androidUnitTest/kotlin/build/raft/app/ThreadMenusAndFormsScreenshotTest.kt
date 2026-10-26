package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.PagerManager
import build.raft.app.resources.MR
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ThreadAttachmentCommentItem
import build.raft.app.data.ThreadAttachmentCommentReaction
import build.raft.app.data.ThreadAttachmentCommentViewer
import build.raft.app.data.ThreadAttachmentCommentsPage
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * The conversation page's menus and forms, light and dark:
 * - `thread.message-menu`: a message's long-press menu (quick reactions, then every action group)
 *   above the select-mode toolbar.
 * - `thread.attachment-comments`: a file's comments (filter, a comment with its time and anchor,
 *   a resolved one, the composer).
 * - `thread.add-member`: the inline Add Members panel with its search, picks and create-agent row.
 * - `thread.settings-modal` / `thread.forward-note`: the Edit Channel and forward-note dialogs.
 * Verify / record as [RaftRobolectricScreenshot]; reports land in `build/reports/home-screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ThreadMenusAndFormsScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    // The reaction glyphs log through the live page's bridge: give them one.
    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-menus-and-forms-screenshot"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    @Test
    fun messageMenuLight() = capture("thread.message-menu", RaftAppAppearance.Light) { MessageMenuAndToolbar() }

    @Test
    fun messageMenuDark() = capture("thread.message-menu.dark", RaftAppAppearance.Dark) { MessageMenuAndToolbar() }

    @Test
    fun attachmentCommentsLight() = capture("thread.attachment-comments", RaftAppAppearance.Light) { AttachmentComments() }

    @Test
    fun attachmentCommentsDark() = capture("thread.attachment-comments.dark", RaftAppAppearance.Dark) { AttachmentComments() }

    @Test
    fun addMemberLight() = capture("thread.add-member", RaftAppAppearance.Light) { AddMemberInline() }

    @Test
    fun addMemberDark() = capture("thread.add-member.dark", RaftAppAppearance.Dark) { AddMemberInline() }

    @Test
    fun settingsModalLight() = capture("thread.settings-modal", RaftAppAppearance.Light, dialog = true) { ChannelSettingsPanelVisualScreen(pinned = true) }

    @Test
    fun settingsModalDark() = capture("thread.settings-modal.dark", RaftAppAppearance.Dark, dialog = true) { ChannelSettingsPanelVisualScreen(pinned = true) }

    @Test
    fun forwardNoteLight() = capture("thread.forward-note", RaftAppAppearance.Light, dialog = true) { ForwardNote() }

    @Test
    fun forwardNoteDark() = capture("thread.forward-note.dark", RaftAppAppearance.Dark, dialog = true) { ForwardNote() }

    private fun capture(name: String, appearance: RaftAppAppearance, dialog: Boolean = false, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
            root = if (dialog) ({ ShadowDialog.getLatestDialog()?.window?.decorView }) else ({ it.window.decorView }),
        ) {
            // Decoded here, synchronously (see ThreadMessageFooterScreenshotTest).
            val atlas =
                remember {
                    it.assets.open(MR.assets.reactions.reaction_atlas_v1.originalPath).use { stream ->
                        BitmapFactory.decodeStream(stream).asImageBitmap()
                    }
                }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(livePager),
                LocalRaftTimeContextProvider provides RaftTimeContextProvider(pickerTimeContext),
                LocalThreadReactionAtlasImage provides atlas,
            ) {
                RaftDesignSystemTheme { content() }
            }
        }
}

private val menuMessage =
    RaftThreadMessageItem(
        id = "msg-1",
        authorId = "user-alice",
        authorName = "Alice",
        authorKind = "human",
        avatarLabel = "A",
        timestampLabel = "2026-10-22T01:42:00Z",
        body = "Launch checklist is in the doc.",
    )

@Composable
private fun MessageMenuAndToolbar() {
    val actions =
        threadMessageMenuActions(
            message = menuMessage,
            showReplyAction = true,
            showSaveAction = true,
            showTaskStatusAction = false,
            showConvertToTaskAction = true,
            showCopyLinkAction = true,
            showShareMessagesAction = true,
            showCancelAction = false,
            showDeleteAction = true,
            showTranslateAction = true,
            showForwardAction = true,
            onReply = {},
            onCopyLink = {},
            onCopy = {},
            onSelectText = {},
            onShareMessages = {},
            onSave = {},
            onFollowThread = {},
            onUnfollowThread = {},
            onTaskStatusChange = {},
            onConvertToTask = {},
            onCancel = {},
            onDelete = {},
        )
    Column(
        Modifier.fillMaxSize().background(RaftColor.Canvas),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.padding(16.dp)) {
            ThreadMessageMenuChrome(
                actions = actions,
                showReactionActions = true,
                reactionAtlasImage = null,
                onReact = {},
            )
        }
        ThreadShareSelectionToolbar(
            selectedCount = 2,
            selectableCount = 6,
            onCancel = {},
            onSelectAll = {},
            onForward = {},
            onExportImage = {},
            onCopyLinks = {},
            onCopyMarkdown = {},
            bottomSafeArea = 0.dp,
        )
    }
}

private fun comment(id: String, name: String, minute: Int, content: String, anchor: String? = null, resolved: Boolean = false) =
    ThreadAttachmentCommentItem(
        id = id,
        channelId = "thread-file",
        senderType = "human",
        senderId = "user-$id",
        senderName = name,
        content = content,
        createdAt = "2026-10-22T01:$minute:00.000Z",
        reactions = if (resolved) listOf(ThreadAttachmentCommentReaction("✅", "human", "user-alice")) else emptyList(),
        anchorLabel = anchor,
    )

@Composable
private fun AttachmentComments() {
    val state =
        ThreadAttachmentCommentsDialogState(
            attachment = RaftThreadAttachmentItem(id = "att-plan", name = "launch-plan.md", mimeType = "text/markdown", sizeLabel = "4 KB"),
            status = "loaded",
            page =
                ThreadAttachmentCommentsPage(
                    comments =
                        listOf(
                            comment("c1", "Alice", 30, "Can we move the beta date to Friday?", anchor = "Timeline"),
                            comment("c2", "Bob", 48, "Done, updated the table.", resolved = true),
                        ),
                    threadChannelId = null,
                    viewer = ThreadAttachmentCommentViewer(canComment = true, reason = ""),
                ),
            draft = TextFieldValue("Looks good"),
        )
    Box(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp)) {
        ThreadAttachmentCommentsContent(
            state = state,
            onFilterChange = {},
            onDraftChange = {},
            onRetry = {},
            onSend = {},
            onResolve = {},
            onQuote = {},
        )
    }
}

private val addMemberCandidates =
    listOf(
        RaftThreadMemberItem(id = "agent-ux", displayName = "Product UX Designer", handle = "Product-UX-Designer", avatarUrl = "pixel:paint", kind = "agent"),
        RaftThreadMemberItem(id = "agent-cindy", displayName = "Cindy", handle = "Cindy", avatarUrl = "pixel:robot", kind = "agent"),
        RaftThreadMemberItem(id = "user_artin", displayName = "artin", handle = "artin", kind = "human"),
        RaftThreadMemberItem(id = "user_designer", displayName = "Designer", handle = "designer", kind = "human"),
    )

@Composable
private fun AddMemberInline() {
    Box(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        ThreadAddMemberModal(
            members = listOf(RaftThreadMemberItem(id = "agent-cindy", displayName = "Cindy", handle = "Cindy", kind = "agent")),
            candidates = addMemberCandidates,
            canAddMembers = true,
            addMemberStatus = "idle",
            addMemberErrorCode = null,
            inline = true,
            onDismiss = {},
            onSubmit = {},
            onSubmitBatch = {},
            canCreateAgents = true,
        )
    }
}

@Composable
private fun ForwardNote() {
    Box(Modifier.fillMaxWidth()) {
        ThreadForwardNoteDialog(
            messageCount = 2,
            selectedTargetLabel = "#design",
            note = TextFieldValue(""),
            sending = false,
            onNoteChange = {},
            onDismiss = {},
            onSend = {},
        )
    }
}
