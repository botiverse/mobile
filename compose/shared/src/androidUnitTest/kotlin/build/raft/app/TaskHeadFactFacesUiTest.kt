package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.model.MemberKind
import build.raft.app.data.taskStatusOptions
import build.raft.app.model.TaskStatus
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Created by face draws as the Source face does (product feedback, #wg-mobile-feedback:#244:
 * the creator's face looked cropped beside the same person's on Source). The row that opens the
 * creator's profile once clipped itself to a rounded rect; the face sits on that row's edge, so
 * the clip cut its left corners. Both rows tappable, both faces the same person's: the two
 * captures must match, corners included.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class TaskHeadFactFacesUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun createdByFaceIsDrawnWholeAsTheSourceFace() =
        runRobolectricComposeUiTest {
            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(16.dp)) {
                        TaskHeadContent(model = SamePersonFacesModel, onOpenSource = {}, onOpenCreator = {})
                    }
                }
            }
            val source = onNodeWithTag(TaskHeadSourceFaceTestTag, useUnmergedTree = true).captureToImage()
            val creator = onNodeWithTag(TaskHeadCreatorFaceTestTag, useUnmergedTree = true).captureToImage()
            assertEquals(source.width to source.height, creator.width to creator.height, "the two faces are the same size")
            val differing = differingPixels(source, creator)
            assertTrue(differing.isEmpty(), "the Created by face differs from the Source face at ${differing.take(12)} (of ${differing.size})")
        }

    /** Pixels (x, y) whose any channel differs by more than 8/255. */
    private fun differingPixels(a: ImageBitmap, b: ImageBitmap): List<Pair<Int, Int>> {
        val pa = a.toPixelMap()
        val pb = b.toPixelMap()
        val out = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                val ca = pa[x, y]
                val cb = pb[x, y]
                val d = maxOf(abs(ca.red - cb.red), abs(ca.green - cb.green), abs(ca.blue - cb.blue), abs(ca.alpha - cb.alpha))
                if (d > 8f / 255f) out += x to y
            }
        }
        return out
    }
}

/** The same agent wrote the source message and created the task: one face, drawn twice. */
private val SamePersonFacesModel =
    TaskConversationHeadModel(
        bar =
            TaskConversationHeaderBarModel(
                status = TaskStatus.InProgress,
                statusOptions = taskStatusOptions(TaskStatus.InProgress, v2Task = true),
                statusUpdating = false,
                assigneeName = "Lagoon",
                assigneeEditable = true,
                followed = true,
                followPending = false,
            ),
        title = "Avatars on the task page",
        description = null,
        source =
            TaskHeadSource(
                authorName = "Niko",
                authorKind = MemberKind.Agent,
                authorAvatarWireValue = "pixel:antenna",
                preview = "The creator's avatar looks cropped",
                time = "10:36",
                authorId = "agent-niko",
            ),
        createdBy = "Niko",
        creator = TaskHeadPerson(MemberKind.Agent, "agent-niko", avatarWireValue = "pixel:antenna"),
        avatarServerId = "server-1",
        createdAt = TaskHeadDateTime(2026, 11, 17, 10, 36),
        earlierDiscussion = null,
        history = TaskHeadHistory.Rows(emptyList()),
    )
