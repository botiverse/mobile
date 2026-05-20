package ai.slock.android.visual

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.model.withUnreadReplyCount
import ai.slock.android.feature.thread.ThreadMessageItem
import ai.slock.android.ui.components.SlockButton
import ai.slock.android.ui.components.SlockButtonVariant
import ai.slock.android.ui.components.SlockComposer
import ai.slock.android.ui.components.SlockMemberKind
import ai.slock.android.ui.components.SlockMemberRow
import ai.slock.android.ui.components.SlockPresence
import ai.slock.android.ui.components.SlockSegmentOption
import ai.slock.android.ui.components.SlockServerSwitcherItem
import ai.slock.android.ui.components.SlockTextField
import ai.slock.android.ui.icons.LucideIcons
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow

data class VisualScreenshotCaseSpec(
    val id: String,
    val title: String
)

object VisualScreenshotCases {
    const val CaseWidthDp = 342

    val All =
        listOf(
            VisualScreenshotCaseSpec("tokens.palette", "Shared Tokens"),
            VisualScreenshotCaseSpec("components.buttons.states", "Button States"),
            VisualScreenshotCaseSpec("components.input.states", "Input States"),
            VisualScreenshotCaseSpec("components.composer.states", "Composer States"),
            VisualScreenshotCaseSpec("components.member-row.data", "Member Row Data"),
            VisualScreenshotCaseSpec("home.server-switcher.unread", "Server Switcher"),
            VisualScreenshotCaseSpec("thread.message.markdown-chips", "Thread Message"),
            VisualScreenshotCaseSpec("conversation.flow.message-stack", "Conversation Flow")
        )

    fun require(id: String): VisualScreenshotCaseSpec =
        All.firstOrNull { it.id == id } ?: error("Unknown visual screenshot case: $id")
}

@Composable
fun VisualScreenshotCase(
    spec: VisualScreenshotCaseSpec,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
        SlockTheme {
            when (spec.id) {
                "tokens.palette" -> TokenPaletteCase(spec, modifier)
                "components.buttons.states" -> ButtonStatesCase(spec, modifier)
                "components.input.states" -> InputStatesCase(spec, modifier)
                "components.composer.states" -> ComposerStatesCase(spec, modifier)
                "components.member-row.data" -> MemberRowDataCase(spec, modifier)
                "home.server-switcher.unread" -> HomeServerSwitcherCase(spec, modifier)
                "thread.message.markdown-chips" -> ThreadMessageCase(spec, modifier)
                "conversation.flow.message-stack" -> ConversationFlowCase(spec, modifier)
                else -> UnknownCase(spec, modifier)
            }
        }
    }
}

@Composable
private fun TokenPaletteCase(
    spec: VisualScreenshotCaseSpec,
    modifier: Modifier
) {
    VisualCaseShell(spec, modifier) {
        val swatches =
            listOf(
                "Yellow" to SlockColor.BrandYellow,
                "Pink" to SlockColor.AccentPink,
                "Lavender" to SlockColor.AccentLavender,
                "Cyan" to SlockColor.AccentCyan,
                "Orange" to SlockColor.AccentOrange,
                "Lime" to SlockColor.AccentLime,
                "Red" to SlockColor.AccentRed,
                "Stone" to SlockColor.EmptyStateGray
            )
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
            swatches.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
                    row.forEach { (name, color) ->
                        TokenSwatch(name = name, color = color, modifier = Modifier.weight(1f))
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SlockColor.SurfaceCream)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(SlockSpacing.Md)
            ) {
                Text(
                    text = "Shadow",
                    style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextBlack
                )
                Text(
                    text = "${SlockShadow.Default.offsetX.value.toInt()}dp ${SlockShadow.Default.offsetY.value.toInt()}dp ${SlockShadow.Default.color}",
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray
                )
            }
        }
    }
}

@Composable
private fun ButtonStatesCase(
    spec: VisualScreenshotCaseSpec,
    modifier: Modifier
) {
    VisualCaseShell(spec, modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                SlockButton(
                    text = "Primary",
                    onClick = {},
                    variant = SlockButtonVariant.Yellow,
                    leadingContent = { CaseIcon(LucideIcons.MessageSquare) }
                )
                SlockButton(
                    text = "Neutral",
                    onClick = {},
                    variant = SlockButtonVariant.Outline,
                    leadingContent = { CaseIcon(LucideIcons.Settings) }
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                SlockButton(
                    text = "Accent",
                    onClick = {},
                    variant = SlockButtonVariant.Pink,
                    leadingContent = { CaseIcon(LucideIcons.BellRing) }
                )
                SlockButton(
                    text = "Disabled",
                    onClick = {},
                    enabled = false,
                    variant = SlockButtonVariant.Outline
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
                SlockSegmentOption("Chat 12", selected = true, onSelected = {})
                SlockSegmentOption("Tasks 3", selected = false, onSelected = {})
                SlockSegmentOption("Saved", selected = false, enabled = false, onSelected = {})
            }
        }
    }
}

@Composable
private fun InputStatesCase(
    spec: VisualScreenshotCaseSpec,
    modifier: Modifier
) {
    VisualCaseShell(spec, modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
            SlockTextField(
                value = "",
                onValueChange = {},
                label = "Empty",
                placeholder = "Message title",
                reserveErrorSpace = false
            )
            SlockTextField(
                value = "Focused value",
                onValueChange = {},
                label = "Focused",
                forceFocused = true,
                reserveErrorSpace = false
            )
            SlockTextField(
                value = "https://",
                onValueChange = {},
                label = "Disabled / Error",
                enabled = false,
                isError = true,
                errorText = "Server URL is required",
                reserveErrorSpace = true
            )
            SlockTextField(
                value = "Composer draft with multiple lines\nand #design context.",
                onValueChange = {},
                label = "Multiline",
                singleLine = false,
                minLines = 3,
                maxLines = 4,
                reserveErrorSpace = false
            )
        }
    }
}

@Composable
private fun ComposerStatesCase(
    spec: VisualScreenshotCaseSpec,
    modifier: Modifier
) {
    VisualCaseShell(spec, modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
            SlockComposer(
                value = "",
                onValueChange = {},
                onSend = {},
                imageIcon = LucideIcons.ImagePlus,
                attachmentIcon = LucideIcons.Paperclip,
                modifier = Modifier.padding(0.dp)
            )
            SlockComposer(
                value = "Composer draft with #design context.",
                onValueChange = {},
                onSend = {},
                imageIcon = LucideIcons.ImagePlus,
                attachmentIcon = LucideIcons.Paperclip,
                modifier = Modifier.padding(0.dp)
            )
            SlockComposer(
                value = "Composer draft with multiple lines\nand shared fixture text.",
                onValueChange = {},
                onSend = {},
                imageIcon = LucideIcons.ImagePlus,
                attachmentIcon = LucideIcons.Paperclip,
                modifier = Modifier.padding(0.dp)
            )
        }
    }
}

@Composable
private fun MemberRowDataCase(
    spec: VisualScreenshotCaseSpec,
    modifier: Modifier
) {
    VisualCaseShell(spec, modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
            SlockMemberRow(
                name = "Android Developer",
                role = "codex",
                kind = SlockMemberKind.Agent,
                presence = SlockPresence.Online,
                avatarLabel = "A",
                detail = "8 unread"
            )
            SlockMemberRow(
                name = "Artin",
                role = "artin@slock.test",
                kind = SlockMemberKind.Human,
                presence = SlockPresence.Unknown,
                avatarLabel = "A"
            )
            SlockMemberRow(
                name = "Product UX Designer",
                role = "reviewing components",
                kind = SlockMemberKind.Agent,
                presence = SlockPresence.Offline,
                avatarLabel = "P",
                detail = "draft"
            )
        }
    }
}

@Composable
private fun HomeServerSwitcherCase(
    spec: VisualScreenshotCaseSpec,
    modifier: Modifier
) {
    VisualCaseShell(spec, modifier) {
        VisualServerSwitcherMenu(servers = visualServers())
    }
}

@Composable
private fun ThreadMessageCase(
    spec: VisualScreenshotCaseSpec,
    modifier: Modifier
) {
    VisualCaseShell(spec, modifier) {
        ThreadMessageItem(
            message =
                message(
                    id = "android-thread-message",
                    body = "收到，统一 MessageItem / ThreadMessageItem 的正文、回复数和任务状态。关联 #design 和 @Product-UX-Designer。",
                    replyCount = 5,
                    task = taskSummary()
                ).withUnreadReplyCount(2),
            onOpenThread = {}
        )
    }
}

@Composable
private fun ConversationFlowCase(
    spec: VisualScreenshotCaseSpec,
    modifier: Modifier
) {
    VisualCaseShell(spec, modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(430.dp)
                .background(SlockColor.SurfaceCream)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
        ) {
            Text(
                text = "BEGINNING OF MESSAGES",
                style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SlockColor.BrandYellow)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm)
            )
            Column(
                modifier = Modifier.padding(SlockSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                visualMessages().forEach { item ->
                    ThreadMessageItem(message = item, onOpenThread = {})
                }
            }
        }
    }
}

@Composable
private fun UnknownCase(
    spec: VisualScreenshotCaseSpec,
    modifier: Modifier
) {
    VisualCaseShell(spec, modifier) {
        Text(
            text = "No Android visual case registered for ${spec.id}.",
            style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack,
            modifier = Modifier
                .fillMaxWidth()
                .background(SlockColor.AccentRed.copy(alpha = 0.2f))
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(SlockSpacing.Md)
        )
    }
}

@Composable
private fun VisualCaseShell(
    spec: VisualScreenshotCaseSpec,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .width(VisualScreenshotCases.CaseWidthDp.dp)
            .wrapContentHeight()
            .background(SlockColor.SurfaceWhite)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        content()
    }
}

@Composable
private fun TokenSwatch(
    name: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(SlockSpacing.Sm)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(color)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
        )
        Text(
            text = name,
            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack,
            modifier = Modifier.padding(top = SlockSpacing.Xs)
        )
    }
}

@Composable
private fun CaseIcon(imageVector: ImageVector) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        tint = SlockColor.TextBlack,
        modifier = Modifier.size(16.dp)
    )
}

@Composable
private fun VisualServerSwitcherMenu(servers: List<SlockServerSwitcherItem>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = SlockShadow.Small.offsetX, bottom = SlockShadow.Small.offsetY)
            .slockHardShadow(SlockShadow.Small)
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
    ) {
        servers.forEachIndexed { index, server ->
            VisualServerSwitcherRow(server = server, selected = index == 0)
        }
        VisualServerSwitcherAction(icon = "+", label = "Join community server")
        VisualServerSwitcherAction(icon = "+", label = "Switch or create server")
    }
}

@Composable
private fun VisualServerSwitcherRow(
    server: SlockServerSwitcherItem,
    selected: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (selected) "✓" else "",
            style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack,
            modifier = Modifier.width(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name,
                style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "/${server.slug}",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (server.unreadCount > 0 && !selected) {
            Text(
                text = server.unreadCount.toString(),
                style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack,
                modifier = Modifier
                    .background(SlockColor.AccentPink)
                    .border(SlockStroke.Thin, SlockColor.BorderBlack)
                    .padding(horizontal = SlockSpacing.Xs, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun VisualServerSwitcherAction(
    icon: String,
    label: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = icon,
            style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack
        )
        Text(
            text = label,
            style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack
        )
    }
}

private fun visualServers() =
    listOf(
        SlockServerSwitcherItem(
            id = "srv_design",
            name = "Slock Design",
            slug = "slock-design",
            unreadCount = 8
        ),
        SlockServerSwitcherItem(
            id = "srv_android",
            name = "Android Artifacts",
            slug = "android-artifacts",
            unreadCount = 12
        ),
        SlockServerSwitcherItem(
            id = "srv_feedback",
            name = "Feedback",
            slug = "feedback",
            unreadCount = 3
        )
    )

private fun visualMessages() =
    listOf(
        message(
            id = "android-human-request",
            author = humanAuthor(),
            body = "对话流组件要用同一份 fixture 截图，别让文案差异污染 diff。",
            replyCount = 0
        ),
        message(
            id = "android-agent-reply",
            body = "收到，统一 MessageItem / ThreadMessageItem 的正文、回复数和任务状态。关联 #design 和 @Product-UX-Designer。",
            replyCount = 4
        ).withUnreadReplyCount(2),
        message(
            id = "android-task-message",
            body = "Visual testing 现在用 raw content 截图，再由 compositor 统一拼接和计算相似度。",
            replyCount = 2,
            task = taskSummary()
        )
    )

private fun message(
    id: String,
    author: MemberProfile = agentAuthor(),
    body: String,
    replyCount: Int,
    task: TaskSummary? = null
) = Message(
    id = MessageId(id),
    threadId = ThreadId("visual-thread"),
    channelId = ChannelId("visual-channel"),
    threadChannelId = ChannelId("visual-thread-channel-$id"),
    author = author,
    timestampLabel = "12:48",
    body = body,
    task = task,
    replyCount = replyCount
)

private fun agentAuthor() =
    MemberProfile(
        id = MemberId("agent-android"),
        displayName = "Android Developer",
        handle = "Android-Developer",
        kind = MemberKind.Agent,
        roleDescription = "Agent",
        avatarLabel = "A"
    )

private fun humanAuthor() =
    MemberProfile(
        id = MemberId("user-artin"),
        displayName = "artin",
        handle = "artin",
        kind = MemberKind.Human,
        roleDescription = "Owner",
        avatarLabel = "A"
    )

private fun taskSummary() =
    TaskSummary(
        id = TaskId("visual-task-10"),
        title = "React Visual Testing prototype",
        status = TaskStatus.InReview,
        taskNumber = 10,
        assignee = agentAuthor(),
        updatedAtLabel = "12:48"
    )
