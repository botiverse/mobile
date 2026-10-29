package build.raft.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.markdown.RaftMarkdownBody
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pointer hover on the page-level surfaces (raft-ui states.md § Hover and press), each shown at
 * rest and then under a real mouse pointer (an Android hover event from a mouse, which Compose
 * reads as [androidx.compose.ui.input.pointer.PointerType.Mouse]):
 *  - a message row: `fill-subtle` across the row (MessageItem `hover:[--mi-row-fill:var(--fill-subtle)]`);
 *  - an Activity row: the list cell's `fill-cell-hover` (steps up in dark);
 *  - the rail's server tile: `sticker-ink-medium` over the ink tile;
 *  - a markdown link: the text deepens toward the ink and its underline takes the full colour.
 * Latin only (the host has no CJK font). Reports land in `build/reports/home-screenshots/`;
 * verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h320dp-xxhdpi")
class PageHoverScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun messageRowLight() = capture("hover.message-row", RaftAppAppearance.Light) { HoverMessageRow(it) }

    @Test
    fun messageRowDark() = capture("hover.message-row.dark", RaftAppAppearance.Dark) { HoverMessageRow(it) }

    @Test
    fun activityRowLight() = capture("hover.activity-row", RaftAppAppearance.Light) { HoverActivityRow(it) }

    @Test
    fun activityRowDark() = capture("hover.activity-row.dark", RaftAppAppearance.Dark) { HoverActivityRow(it) }

    @Test
    fun serverTileLight() = capture("hover.server-tile", RaftAppAppearance.Light) { HoverServerTile(it) }

    @Test
    fun serverTileDark() = capture("hover.server-tile.dark", RaftAppAppearance.Dark) { HoverServerTile(it) }

    @Test
    fun markdownLinkLight() = capture("hover.markdown-link", RaftAppAppearance.Light) { HoverMarkdownLink(it) }

    @Test
    fun markdownLinkDark() = capture("hover.markdown-link.dark", RaftAppAppearance.Dark) { HoverMarkdownLink(it) }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable (HoverTarget) -> Unit) {
        val target = HoverTarget()
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
            interact = { view -> hoverPointer(view, target.point, MotionEvent.TOOL_TYPE_MOUSE) },
        ) {
            PageHoverHost(appearance) { content(target) }
        }
    }
}

/** Where the pointer goes: set from the hovered copy's layout (root px). */
internal class HoverTarget {
    var point: Offset = Offset.Zero
}

/**
 * Sends an Android hover (enter, then a move) at [point] from a pointer of [toolType]: a mouse
 * ([MotionEvent.TOOL_TYPE_MOUSE]) or a finger ([MotionEvent.TOOL_TYPE_FINGER], a platform that
 * reports a touch as hovering).
 */
internal fun hoverPointer(view: View, point: Offset, toolType: Int) {
    val source = if (toolType == MotionEvent.TOOL_TYPE_MOUSE) InputDevice.SOURCE_MOUSE else InputDevice.SOURCE_TOUCHSCREEN
    listOf(MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE).forEach { action ->
        view.dispatchGenericMotionEvent(pointerEvent(action, point, toolType, source))
    }
}

/** A finger's tap at [point]: down, then up. */
internal fun tapFinger(view: View, point: Offset) {
    listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
        view.dispatchTouchEvent(pointerEvent(action, point, MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN))
    }
}

private fun pointerEvent(action: Int, point: Offset, toolType: Int, source: Int): MotionEvent {
    val now = SystemClock.uptimeMillis()
    val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; this.toolType = toolType })
    val coords = arrayOf(MotionEvent.PointerCoords().apply { x = point.x; y = point.y })
    return MotionEvent.obtain(now, now, action, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
}

/** The pixel at [point] of [bitmap] as 0xAARRGGBB. */
internal fun pixelAt(bitmap: Bitmap, point: Offset): Int = bitmap.getPixel(point.x.toInt(), point.y.toInt())

@Composable
internal fun PageHoverHost(appearance: RaftAppAppearance, content: @Composable () -> Unit) {
    val pager =
        remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
    CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
        RaftDesignSystemTheme {
            RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
                Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) { content() }
            }
        }
    }
}

/** Rest above, hovered below; [target] gets the point inside the hovered copy at [at] (0..1 of its box). */
@Composable
private fun RestAndHovered(target: HoverTarget, at: Offset = Offset(0.5f, 0.5f), item: @Composable (Modifier) -> Unit) {
    Caption("Rest")
    item(Modifier)
    Caption("Hover (mouse)")
    item(
        Modifier.onGloballyPositioned { coordinates ->
            val box = coordinates.boundsInRoot()
            target.point = Offset(box.left + box.width * at.x, box.top + box.height * at.y)
        }
    )
}

@Composable
private fun Caption(text: String) {
    RaftText(
        text,
        style = RaftTheme.typography.caption,
        color = RaftTheme.colors.foregroundHint,
        modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp),
    )
}

internal val hoverMessage =
    RaftThreadMessageItem(
        id = "hover-1",
        authorId = "user-alice",
        authorName = "Alice",
        authorKind = "human",
        avatarLabel = "A",
        timestampLabel = "2026-10-21T16:40:00Z",
        body = "Morning! The launch checklist is up, take a look when you can.",
    )

@Composable
internal fun HoverMessageCard(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        ThreadMessageCard(
            message = hoverMessage,
            messageBodyTextStyle = raftMessageBodyTextStyle("md"),
            timestampLabel = "4:40 PM",
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

@Composable
private fun HoverMessageRow(target: HoverTarget) {
    // The pointer rests at the row's end, past its words.
    RestAndHovered(target, at = Offset(0.95f, 0.5f)) { HoverMessageCard(it) }
}

@Composable
internal fun HoverActivityRowContent() {
    val item =
        InboxItem(
            key = "channel:release",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("release"),
            channelName = "release",
            unreadCount = 2,
            lastMessageSenderName = "Volta",
            lastMessageSenderKind = MemberKind.Agent,
            lastMessagePreview = "The preview build is up.",
            lastMessageAt = "2026-10-28T03:20:00Z",
        )
    val context = ActivityConversationListContext(channelSymbolsByChannelId = mapOf("release" to "#"))
    val timeContext =
        RaftTimeContext(localOffsetMinutes = 8 * 60, todayEpochDay = raftEpochDayForDate(2026, 10, 28), nowEpochMillis = 1_793_150_000_000L)
    ActivityConversationListRow(
        item = item,
        model = item.activityConversationRowModel(context, InboxFilter.Unread),
        divider = false,
        selected = false,
        timeContext = timeContext,
        agentStatusResolver = EmptyRaftAgentStatusResolver,
        canMarkDone = true,
        openGestureKey = item.key,
        captureMenuAnchor = false,
        onOpen = {},
        onOpenMenu = {},
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun HoverActivityRow(target: HoverTarget) {
    RestAndHovered(target) { modifier -> Box(modifier.fillMaxWidth()) { HoverActivityRowContent() } }
}

private val hoverServers =
    listOf(
        RaftServerSwitcherItem(id = "s1", name = "Botiverse", slug = "botiverse", activityUnreadCount = 0L),
        RaftServerSwitcherItem(id = "s2", name = "Acme Labs", slug = "acme", activityUnreadCount = 0L),
    )

@Composable
internal fun HoverServerTileContent(open: Boolean = false) {
    RaftAppRailServerTile(RaftServerMenuModel(servers = hoverServers, currentServer = hoverServers.first()), open = open, onClick = {})
}

@Composable
private fun HoverServerTile(target: HoverTarget) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.Top,
    ) {
        listOf("Rest", "Hover (mouse)", "Panel open").forEachIndexed { index, label ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RaftText(label, style = RaftTheme.typography.caption, color = RaftTheme.colors.foregroundHint)
                // The rail's frame behind the tile, as in the app.
                Box(Modifier.padding(top = 6.dp).background(RaftTheme.colors.layerFrame).padding(10.dp)) {
                    Box(
                        if (index == 1) {
                            Modifier.onGloballyPositioned { target.point = it.boundsInRoot().center }
                        } else {
                            Modifier
                        }
                    ) {
                        HoverServerTileContent(open = index == 2)
                    }
                }
            }
        }
    }
}

internal const val HoverMarkdownBody = "https://example.com/docs has the launch steps."

@Composable
internal fun HoverMarkdown(modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = 12.dp)) {
        RaftMarkdownBody(body = HoverMarkdownBody, modifier = Modifier.width(360.dp))
    }
}

@Composable
private fun HoverMarkdownLink(target: HoverTarget) {
    // Over the link's first words, on the first of the two lines (the box starts 12dp in).
    RestAndHovered(target, at = Offset(0.15f, 0.2f)) { HoverMarkdown(it) }
}
