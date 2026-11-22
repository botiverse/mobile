package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.designsystem.components.RaftSegmentedPager
import build.raft.designsystem.components.RaftTab
import build.raft.designsystem.components.RaftTabStrip
import build.raft.designsystem.components.rememberRaftSegmentedPagerState
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * Frames for the #proj-mobile:#257 review video (not a golden): an agent's Activity tab mock-up
 * (Overview / Raw Log / Sub-agents) driven by real touch input on an offscreen scene with a fixed
 * 30 fps clock, written as PNGs to `<reportDir>/segmented-pager-frames/{after,before}/`, which CI
 * uploads with the design system screenshot reports.
 *
 * - after: [RaftSegmentedPager]: a slow drag past halfway (the content and sticker follow the
 *   finger, then snap), a short drag that springs back, a flick, and a tap that slides back.
 * - before: the same finger path over the switch as it was (a re-enactment of
 *   `raftSwipesBetweenSegments`: the segment turns at once when the swipe is decided, 2x the slop).
 */
@OptIn(ExperimentalComposeUiApi::class)
class SegmentedPagerFramesTest {
    private val reportDir = File(System.getProperty("raft.screenshot.reportDir") ?: "build/reports/screenshots")
    private val views = listOf("Overview", "Raw Log", "Subagents")

    private var finger by mutableStateOf<Offset?>(null)
    private val segmentCenters = arrayOfNulls<Offset>(3)

    @Test
    fun recordsTheFingerFollowingSwitchAndTheJumpBeforeIt() {
        val after = record("after", followsFinger = true)
        val before = record("before", followsFinger = false)
        assertTrue(after > 100 && before > 100, "frames: $after after, $before before")
    }

    private fun record(name: String, followsFinger: Boolean): Int {
        val dir = File(reportDir, "segmented-pager-frames/$name").apply {
            deleteRecursively()
            mkdirs()
        }
        finger = null
        var beforeSelected by mutableIntStateOf(0)
        val density = Density(Scale)
        var frame = 0
        var timeNanos = 0L
        ImageComposeScene(width = (WidthDp * Scale).toInt(), height = (HeightDp * Scale).toInt(), density = density) {
            RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).drawFinger()) {
                    if (followsFinger) After() else Before(beforeSelected) { beforeSelected = it }
                }
            }
        }.use { scene ->
            fun shoot(count: Int = 1) {
                repeat(count) {
                    val image = scene.render(timeNanos)
                    val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encode failed")
                    File(dir, "frame-%04d.png".format(frame++)).writeBytes(png)
                    timeNanos += FrameNanos
                }
            }
            val millis = { timeNanos / 1_000_000 }

            fun send(type: PointerEventType, at: Offset) {
                finger = if (type == PointerEventType.Release) null else at
                scene.sendPointerEvent(type, at * Scale, timeMillis = millis(), type = PointerType.Touch)
            }

            // [dxPerFrame] dp a frame for [frames] frames from [from]; the before re-enactment turns
            // the segment once the finger is 2x the slop (16dp on a phone) in.
            fun drag(from: Offset, dxPerFrame: Float, frames: Int, holdBeforeRelease: Int = 0) {
                send(PointerEventType.Press, from)
                shoot()
                var at = from
                var turned = false
                repeat(frames) {
                    at += Offset(dxPerFrame, 0f)
                    send(PointerEventType.Move, at)
                    if (!followsFinger && !turned && kotlin.math.abs(at.x - from.x) >= 16f) {
                        turned = true
                        val next = beforeSelected + if (dxPerFrame < 0) 1 else -1
                        if (next in views.indices) beforeSelected = next
                    }
                    shoot()
                }
                repeat(holdBeforeRelease) {
                    send(PointerEventType.Move, at)
                    shoot()
                }
                send(PointerEventType.Release, at)
                shoot()
            }

            fun tap(index: Int) {
                val at = segmentCenters[index] ?: error("segment $index not laid out")
                send(PointerEventType.Press, at)
                shoot()
                send(PointerEventType.Release, at)
                shoot()
            }

            shoot(12)
            // A slow drag past halfway: the content and the sticker follow, then it snaps to Raw Log.
            drag(Offset(330f, 430f), dxPerFrame = -7f, frames = 32, holdBeforeRelease = 3)
            shoot(18)
            // A short drag that comes back: under halfway and slow, it springs back.
            drag(Offset(330f, 430f), dxPerFrame = -4f, frames = 18, holdBeforeRelease = 3)
            shoot(18)
            // A flick: short but fast, it goes on to Sub-agents.
            drag(Offset(300f, 430f), dxPerFrame = -22f, frames = 5)
            shoot(20)
            // A tap on Overview slides back across both.
            tap(0)
            shoot(24)
        }
        return frame
    }

    /** The finger: a translucent disc where it touches. */
    private fun Modifier.drawFinger(): Modifier =
        drawWithContent {
            drawContent()
            val at = finger ?: return@drawWithContent
            val center = Offset(at.x.dp.toPx(), at.y.dp.toPx())
            drawCircle(Color.Black.copy(alpha = 0.16f), radius = 22.dp.toPx(), center = center)
            drawCircle(Color.White.copy(alpha = 0.9f), radius = 22.dp.toPx(), center = center, style = Stroke(2.dp.toPx()))
        }

    @Composable
    private fun Shell(body: @Composable () -> Unit) {
        val colors = RaftTheme.colors
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
                RaftText("Ottie", style = RaftTheme.typography.headline, color = colors.foreground)
                RaftText("Agent · Claude Code", style = RaftTheme.typography.footnote, color = colors.foregroundMuted)
            }
            // The profile's own tabs (the outer TabPager), Activity selected.
            RaftTabStrip {
                RaftTab("Profile", selected = false, onClick = {})
                RaftTab("Activity", selected = true, onClick = {})
                RaftTab("Chat", selected = false, onClick = {})
            }
            Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam.compositeOver(colors.layerCanvas)))
            body()
        }
    }

    @Composable
    private fun Header(switch: @Composable () -> Unit) {
        val colors = RaftTheme.colors
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.weight(1f)) { switch() }
                RaftButton("Copy info", onClick = {})
            }
            Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam.compositeOver(colors.layerCanvas)))
        }
    }

    @Composable
    private fun Switch(selected: Int, position: (() -> Float)?, onSelect: (Int) -> Unit) {
        RaftSegmentedControl(selectionPosition = position) {
            views.forEachIndexed { index, label ->
                RaftSegment(
                    text = label,
                    selected = index == selected,
                    onClick = { onSelect(index) },
                    count = if (index == 2) "2" else null,
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        val box = coordinates.boundsInRoot()
                        segmentCenters[index] = Offset(box.center.x / Scale, box.center.y / Scale)
                    },
                )
            }
        }
    }

    @Composable
    private fun After() {
        var selected by remember { mutableIntStateOf(0) }
        val state = rememberRaftSegmentedPagerState(initialIndex = 0) { views.size }
        Shell {
            RaftSegmentedPager(
                state = state,
                selectedIndex = selected,
                onSelectedIndexChange = { selected = it },
                modifier = Modifier.fillMaxSize(),
                header = { Header { Switch(selected, { state.position }) { selected = it } } },
            ) { page -> Page(page) }
        }
    }

    @Composable
    private fun Before(selected: Int, onSelect: (Int) -> Unit) {
        Shell {
            Column(Modifier.fillMaxSize()) {
                Header { Switch(selected, null, onSelect) }
                Box(Modifier.fillMaxSize()) { Page(selected) }
            }
        }
    }

    @Composable
    private fun Page(page: Int) {
        val colors = RaftTheme.colors
        Column(Modifier.fillMaxSize().background(colors.layerCanvas).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (page) {
                0 -> {
                    Card("Now", "Working · reviewing PR #5241")
                    Card("Today", "14 runs · 2 sub-agents · 3h 12m")
                    Card("Last message", "#proj-mobile · \"Pushed the fix, CI is green.\"")
                }
                1 ->
                    listOf(
                        "10:42:07" to "Tool: Read compose/designsystem/README.md",
                        "10:42:09" to "Tool: Grep raftSwipesBetweenSegments",
                        "10:42:15" to "Thinking",
                        "10:42:31" to "Tool: Edit RaftSegmentedPager.kt",
                        "10:42:58" to "Tool: Bash git push",
                        "10:43:02" to "Sent a message to #proj-mobile",
                    ).forEach { (time, title) -> LogRow(time, title) }
                else -> {
                    Card("Explore the gesture code", "Finished · 4 min · 31 tool calls")
                    Card("Record the review video", "Working · 1 min")
                }
            }
        }
    }

    @Composable
    private fun Card(title: String, body: String) {
        val colors = RaftTheme.colors
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.fillMuted.compositeOver(colors.layerCanvas), RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RaftText(title, style = RaftTheme.typography.callout, color = colors.foreground)
            RaftText(body, style = RaftTheme.typography.footnote, color = colors.foregroundMuted)
        }
    }

    @Composable
    private fun LogRow(time: String, title: String) {
        val colors = RaftTheme.colors
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(colors.foregroundMuted, RoundedCornerShape(2.dp)))
            RaftText(title, style = RaftTheme.typography.monoMeta, color = colors.foreground, modifier = Modifier.weight(1f))
            RaftText(time, style = RaftTheme.typography.monoMeta, color = colors.foregroundMuted, modifier = Modifier.width(64.dp))
        }
    }

    private companion object {
        const val WidthDp = 390f
        const val HeightDp = 640f
        const val Scale = 2f
        const val FrameNanos = 1_000_000_000L / 30
    }
}
