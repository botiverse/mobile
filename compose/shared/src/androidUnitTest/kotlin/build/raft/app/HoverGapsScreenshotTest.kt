package build.raft.app

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.model.TaskStatus
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The hover / press gaps closed in #wg-mobile-sync:#268 (raft-ui states.md § Hover and press), each at
 * rest above and in its state below, under a real pointer:
 *  - a task status meta chip ([RaftTaskStatusChip], raft-ui TaskChip as a button) under a mouse: the
 *    medium overlay over its soft fill (`[a,button]:hover:overlay-medium`);
 *  - a reaction chip that lists who reacted ([ThreadReactionChip] with reactors, whose host owns the
 *    tap and long press) held down by a finger: the chip's press overlay, as the plain chip's toggle;
 *  - the attachment comments panel's head: raft-ui PanelHeader (`header-height`, title and meta).
 * Each state test also checks the chip's fill changed. Reports land in `build/reports/home-screenshots/`;
 * verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h240dp-xxhdpi")
class HoverGapsScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    // The reaction glyph logs once through the page's bridge: a live, registered page.
    private val livePager =
        object : Pager() {}.apply {
            pagerId = "hover-gaps-screenshot"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    @Test
    fun metaChipHoverLight() = metaChipHover("hover.meta-chip", RaftAppAppearance.Light)

    @Test
    fun metaChipHoverDark() = metaChipHover("hover.meta-chip-dark", RaftAppAppearance.Dark)

    @Test
    fun reactionChipWithReactorsPressLight() = reactionPress("press.reaction-chip-reactors", RaftAppAppearance.Light)

    @Test
    fun reactionChipWithReactorsPressDark() = reactionPress("press.reaction-chip-reactors-dark", RaftAppAppearance.Dark)

    @Test
    fun attachmentCommentsHeadLight() {
        RaftRobolectricScreenshot.capture(
            "files.attachment-comments-head",
            RaftAppAppearance.Light,
            reportDir = ReportDir,
            activities = activities,
        ) {
            PageHoverHost(RaftAppAppearance.Light) {
                RaftAttachmentCommentsPanel(
                    state =
                        ThreadAttachmentCommentsDialogState(
                            attachment =
                                RaftThreadAttachmentItem(
                                    id = "att-head-1",
                                    name = "launch-checklist-review.pdf",
                                    mimeType = "application/pdf",
                                    sizeLabel = "",
                                ),
                            status = "loaded",
                        ),
                    onDraftChange = {},
                    onRetry = {},
                    onSend = {},
                    onClose = {},
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
            }
        }
    }

    private fun metaChipHover(name: String, appearance: RaftAppAppearance) {
        val boxes = HoverGapBoxes()
        RaftRobolectricScreenshot.render(
            appearance,
            activities,
            interact = { view -> hoverPointer(view, boxes.target.center, MotionEvent.TOOL_TYPE_MOUSE) },
            content = {
                PageHoverHost(appearance) {
                    RestAndState(boxes, "Hover (mouse)") { modifier ->
                        RaftTaskStatusChip(status = TaskStatus.InProgress, onClick = {}, modifier = modifier)
                    }
                }
            },
        ) { _, bitmap ->
            RaftRobolectricScreenshot.verifyOrRecord(name, bitmap, File(ReportDir))
            assertNotEquals(fillAt(bitmap, boxes, boxes.rest), fillAt(bitmap, boxes, boxes.target), "the meta chip shows no hover under a mouse")
        }
    }

    private fun reactionPress(name: String, appearance: RaftAppAppearance) {
        val boxes = HoverGapBoxes()
        val reaction = RaftThreadReactionItem(emoji = "👍", count = 2, selected = false, reactorIds = listOf("u1", "u2"), reactorNames = listOf("Alice", "Bob"))
        RaftRobolectricScreenshot.render(
            appearance,
            activities,
            interact = { view -> fingerDown(view, boxes.target.center) },
            content = {
                PageHoverHost(appearance) {
                    RestAndState(boxes, "Pressed (finger)") { modifier ->
                        Box(modifier) { ThreadReactionChip(reaction, onClick = {}, showReactors = true) }
                    }
                }
            },
        ) { _, bitmap ->
            RaftRobolectricScreenshot.verifyOrRecord(name, bitmap, File(ReportDir))
            assertNotEquals(fillAt(bitmap, boxes, boxes.rest), fillAt(bitmap, boxes, boxes.target), "the reaction chip shows no press")
        }
    }

    /** The chip's fill: 3dp in from its start edge (inside its padding, before the content), mid-height. */
    private fun fillAt(bitmap: android.graphics.Bitmap, boxes: HoverGapBoxes, box: Rect): Int =
        bitmap.getPixel((box.left + 3f * boxes.density).toInt(), box.center.y.toInt())

    private companion object {
        const val ReportDir = "build/reports/home-screenshots"
    }
}

/** Where the rest copy and the state copy were laid out (root px), and the density. */
private class HoverGapBoxes {
    var rest: Rect = Rect.Zero
    var target: Rect = Rect.Zero
    var density: Float = 1f
}

/** Rest above, the state below; [boxes] get both copies' bounds. */
@Composable
private fun RestAndState(boxes: HoverGapBoxes, stateLabel: String, item: @Composable (Modifier) -> Unit) {
    boxes.density = LocalDensity.current.density
    Column(Modifier.padding(horizontal = 16.dp)) {
        GapCaption("Rest")
        item(Modifier.onGloballyPositioned { boxes.rest = it.boundsInRoot() })
        GapCaption(stateLabel)
        item(Modifier.onGloballyPositioned { boxes.target = it.boundsInRoot() })
    }
}

@Composable
private fun GapCaption(text: String) {
    RaftText(
        text,
        style = RaftTheme.typography.caption,
        color = RaftTheme.colors.foregroundHint,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

/** A finger held down at [point] (no up): the press a capture can hold. */
private fun fingerDown(view: View, point: Offset) {
    val now = SystemClock.uptimeMillis()
    val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
    val coords = arrayOf(MotionEvent.PointerCoords().apply { x = point.x; y = point.y })
    view.dispatchTouchEvent(
        MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    )
}
