package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.markdown.SlockMarkdownBody
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.compat.pager.Page
import build.raft.app.model.TaskStatus
import kotlinx.coroutines.delay
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

const val SLOCK_FLOATING_OVERLAY_TOUCH_GUARD_VISUAL_PAGE = "compose_floating_overlay_touch_guard_visual"

@Page(SLOCK_FLOATING_OVERLAY_TOUCH_GUARD_VISUAL_PAGE, supportInLocal = true)
internal class ComposeSlockFloatingOverlayTouchGuardVisualPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        val fixture = pageData.params.optString("fixture")
        val probeGeneration = pageData.params.optString("instanceId")
        if (fixture.startsWith("release-notes-section-gap-")) {
            SlockReleaseNotesSectionGapProbe.begin(probeGeneration)
        }
        setContent {
            when (fixture) {
                FloatingOverlayTouchGuardModalFixture -> SlockModalPointerBarrierVisualScreen()
                FloatingOverlayTouchGuardModalTextFieldFixture -> SlockModalTextFieldPointerVisualScreen()
                FloatingOverlayTouchGuardTaskStatusFixture -> SlockTaskStatusMenuTouchVisualScreen()
                FloatingOverlayTouchGuardAnchorLeaseFixture -> SlockAnchorLeaseLazyReuseVisualScreen()
                ReleaseNotesSectionGapZeroFixture ->
                    SlockReleaseNotesSectionGapVisualScreen(0.dp, probeGeneration)
                ReleaseNotesSectionGapEightFixture ->
                    SlockReleaseNotesSectionGapVisualScreen(SlockSpacing.Sm, probeGeneration)
                ReleaseNotesSectionGapDefaultFixture ->
                    SlockReleaseNotesSectionGapVisualScreen(null, probeGeneration)
                else -> SlockFloatingOverlayTouchGuardVisualScreen()
            }
        }
    }
}

private const val FloatingOverlayTouchGuardModalFixture = "modal"
private const val FloatingOverlayTouchGuardModalTextFieldFixture = "modal-text-field"
private const val FloatingOverlayTouchGuardTaskStatusFixture = "task-status-menu"
private const val FloatingOverlayTouchGuardAnchorLeaseFixture = "anchor-lease-lazy"
private const val ReleaseNotesSectionGapZeroFixture = "release-notes-section-gap-zero"
private const val ReleaseNotesSectionGapEightFixture = "release-notes-section-gap-eight"
private const val ReleaseNotesSectionGapDefaultFixture = "release-notes-section-gap-default"

@Composable
private fun SlockReleaseNotesSectionGapVisualScreen(
    sectionGap: androidx.compose.ui.unit.Dp?,
    probeGeneration: String
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceCream)
                .padding(SlockSpacing.Md)
    ) {
        if (sectionGap == null) {
            SlockMarkdownBody(
                body = ReleaseNotesSectionGapFixtureBody,
                onSectionHeadingPositioned = { bounds ->
                    SlockReleaseNotesSectionGapProbe.record(probeGeneration, bounds)
                }
            )
        } else {
            SlockMarkdownBody(
                body = ReleaseNotesSectionGapFixtureBody,
                sectionHeadingTopPadding = sectionGap,
                onSectionHeadingPositioned = { bounds ->
                    SlockReleaseNotesSectionGapProbe.record(probeGeneration, bounds)
                }
            )
        }
    }
}

private val ReleaseNotesSectionGapFixtureBody =
    """
    # Raft Alpha 1.0.0

    这份精确版本文档用于验证 build **1000002** 的原生 Release Notes 体验，并与消息和文件预览共同复用 `SlockMarkdownBody` 渲染器。

    ## 本次内容
    """.trimIndent()

data class SlockReleaseNotesSectionHeadingBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

object SlockReleaseNotesSectionGapProbe : SynchronizedObject() {
    private var activeGeneration: String? = null
    private var latestBounds: SlockReleaseNotesSectionHeadingBounds? = null

    fun begin(generation: String) = synchronized(this) {
        activeGeneration = generation
        latestBounds = null
    }

    fun record(
        generation: String,
        bounds: androidx.compose.ui.geometry.Rect
    ) = synchronized(this) {
        if (generation != activeGeneration) return@synchronized
        latestBounds =
            SlockReleaseNotesSectionHeadingBounds(
                left = kotlin.math.floor(bounds.left).toInt(),
                top = kotlin.math.floor(bounds.top).toInt(),
                right = kotlin.math.ceil(bounds.right).toInt(),
                bottom = kotlin.math.ceil(bounds.bottom).toInt()
            )
    }

    fun snapshot(generation: String): SlockReleaseNotesSectionHeadingBounds? = synchronized(this) {
        latestBounds.takeIf { generation == activeGeneration }
    }
}

@Composable
internal fun SlockFloatingOverlayTouchGuardVisualScreen() {
    var overlayHit by remember { mutableStateOf(false) }
    var linkHit by remember { mutableStateOf(false) }
    val statusColor =
        when {
            linkHit -> FloatingOverlayTouchGuardLeakRed
            overlayHit -> FloatingOverlayTouchGuardPassGreen
            else -> FloatingOverlayTouchGuardIdleCream
        }

    Box(Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        Box(
            modifier = Modifier
                .offset(x = FloatingOverlayTouchGuardLeft, y = FloatingOverlayTouchGuardTop)
                .width(FloatingOverlayTouchGuardWidth)
                .height(FloatingOverlayTouchGuardHeight)
                .background(FloatingOverlayTouchGuardUnderlayYellow)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            SlockMarkdownBody(
                body = "[OPEN LINK OPEN LINK OPEN LINK](https://slock.test/floating-overlay-touch-guard)",
                textStyle = SlockType.Body.copy(
                    fontSize = 28.sp,
                    lineHeight = 40.sp
                ).withSlockFontWeight(FontWeight.Bold),
                onOpenUrl = { linkHit = true }
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(112.dp)
                .background(statusColor)
        )

        SlockFloatingOverlayHost(onDismiss = {}) {
            Box(
                modifier = Modifier
                    .offset(x = FloatingOverlayTouchGuardLeft, y = FloatingOverlayTouchGuardTop)
                    .width(FloatingOverlayTouchGuardWidth)
                    .height(FloatingOverlayTouchGuardHeight)
                    .slockFloatingContentClickTarget()
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (overlayHit) FloatingOverlayTouchGuardPassGreen else SlockColor.AccentPink)
                        .border(SlockStroke.Default, SlockColor.BorderBlack)
                        .clickable { overlayHit = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "MENU ACTION",
                        style = SlockType.Button.withSlockFontWeight(FontWeight.Bold),
                        color = SlockColor.TextBlack
                    )
                }
            }
        }
    }
}

private val FloatingOverlayTouchGuardLeft = 64.dp
private val FloatingOverlayTouchGuardTop = 184.dp
private val FloatingOverlayTouchGuardWidth = 280.dp
private val FloatingOverlayTouchGuardHeight = 96.dp
private val FloatingOverlayTouchGuardIdleCream = Color(0xFFFFF7E6)
private val FloatingOverlayTouchGuardPassGreen = Color(0xFF10B981)
private val FloatingOverlayTouchGuardLeakRed = Color(0xFFEF4444)
private val FloatingOverlayTouchGuardUnderlayYellow = Color(0xFFFFD440)

@Composable
internal fun SlockTaskStatusMenuTouchVisualScreen() {
    var selectedStatus by remember { mutableStateOf(TaskStatus.Todo) }
    val anchorRef = remember { SlockLiveRootAnchorRef(identity = "floating_overlay_touch_guard_fixture") }
    var anchor by remember { mutableStateOf<SlockPopoverAnchor?>(null) }
    Box(Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(112.dp)
                    .background(
                        if (selectedStatus == TaskStatus.Done) {
                            FloatingOverlayTouchGuardPassGreen
                        } else {
                            FloatingOverlayTouchGuardIdleCream
                        }
                    )
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-12).dp, y = (-32).dp)
                    .width(1.dp)
                    .height(1.dp)
                    .slockCaptureLiveRootAnchor(anchorRef) {
                        anchor = anchorRef.resolve(SlockPopoverLocalPoint.BottomEnd)
                    }
        )
        anchor?.let { resolvedAnchor ->
            SlockTaskStatusMenuOverlay(
                anchor = resolvedAnchor,
                items =
                    TaskStatus.entries.map { status ->
                        SlockBaseMenuItem(id = status.name, label = status.touchFixtureLabel())
                    },
                selectedId = selectedStatus.name,
                onDismiss = {},
                onSelect = { id ->
                    TaskStatus.entries.firstOrNull { it.name == id }?.let { selectedStatus = it }
                }
            )
        }
    }
}

private fun TaskStatus.touchFixtureLabel(): String =
    when (this) {
        TaskStatus.Todo -> "Todo"
        TaskStatus.InProgress -> "In Progress"
        TaskStatus.InReview -> "In Review"
        TaskStatus.Done -> "Done"
        TaskStatus.Closed -> "Closed"
    }

@Composable
internal fun SlockAnchorLeaseLazyReuseVisualScreen() {
    val listState = rememberLazyListState()
    val probeItems = remember { listOf("A") + (1..20).map { index -> "spacer-$index" } + "B" }
    var firstAResolver by remember { mutableStateOf<SlockPopoverAnchorResolver?>(null) }
    var bResolver by remember { mutableStateOf<SlockPopoverAnchorResolver?>(null) }
    var secondAResolver by remember { mutableStateOf<SlockPopoverAnchorResolver?>(null) }
    var probePhase by remember { mutableStateOf(0) }
    var aToBLifecyclePassed by remember { mutableStateOf(false) }
    var leaseResult by remember { mutableStateOf<Boolean?>(null) }
    var leaseFailureCode by remember { mutableStateOf(0) }

    LaunchedEffect(firstAResolver) {
        if (firstAResolver == null || probePhase != 0) return@LaunchedEffect
        probePhase = 1
        listState.scrollToItem(probeItems.lastIndex)
    }
    LaunchedEffect(bResolver) {
        if (bResolver == null || probePhase != 1) return@LaunchedEffect
        delay(300L)
        aToBLifecyclePassed =
            firstAResolver?.resolve() == null &&
                bResolver?.resolve() != null
        probePhase = 2
        listState.scrollToItem(0)
    }
    LaunchedEffect(secondAResolver) {
        if (secondAResolver == null || probePhase != 2) return@LaunchedEffect
        delay(300L)
        leaseFailureCode =
            when {
                !aToBLifecyclePassed -> 1
                bResolver?.resolve() != null -> 2
                secondAResolver === firstAResolver -> 3
                secondAResolver?.resolve() == null -> 4
                else -> 0
            }
        leaseResult = leaseFailureCode == 0
    }

    Box(Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        LazyRow(
            state = listState,
            modifier = Modifier.width(150.dp).height(80.dp)
        ) {
            // Intentionally omit stable keys and traverse A→B→A so the real Lazy lifecycle proves
            // exited items permanently lose their old resolver and remount with a fresh one. The
            // same-LayoutCoordinates takeover path is covered separately by the referential unit gate.
            items(probeItems) { itemId ->
                val anchorRef = remember(itemId) { SlockLiveRootAnchorRef(identity = "lazy:$itemId") }
                val resolver = remember(anchorRef) { anchorRef.resolver(SlockPopoverLocalPoint.BottomStart) }
                Box(
                    modifier =
                        Modifier
                            .width(300.dp)
                            .height(64.dp)
                            .background(if (itemId == "A") SlockColor.BrandYellow else SlockColor.AccentCyan)
                            .slockCaptureLiveRootAnchor(anchorRef) {
                                if (itemId == "A" && firstAResolver == null) {
                                    firstAResolver = resolver
                                } else if (itemId == "A" && probePhase >= 2 && resolver !== firstAResolver) {
                                    secondAResolver = resolver
                                }
                                if (itemId == "B") bResolver = resolver
                            },
                    contentAlignment = Alignment.Center
                ) {
                    Text(itemId, style = SlockType.Button, color = SlockColor.TextBlack)
                }
            }
        }
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(
                        when (leaseResult) {
                            true -> FloatingOverlayTouchGuardPassGreen
                            false ->
                                when (leaseFailureCode) {
                                    2 -> Color(0xFFF97316)
                                    3 -> Color(0xFFD946EF)
                                    4 -> Color(0xFF3B82F6)
                                    else -> FloatingOverlayTouchGuardLeakRed
                                }
                            null -> FloatingOverlayTouchGuardIdleCream
                        }
                    )
        )
    }
}
