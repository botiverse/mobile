package build.raft.app

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.io.File
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Home › Joint Channels + opens the create-joint-channel dialog with its form: the name and
 * description fields are laid out inside the dialog body at a usable height, above the footer.
 *
 * Regression (Android 1.15.3): the body was wrapped in a second `weight(1f, fill = false)` +
 * `verticalScroll` column inside [build.raft.designsystem.components.RaftDialog]'s own scrolling
 * body. A weighted child of a column measured with an unbounded height gets no space, so the
 * dialog showed only its title and footer. Reads the dialog window's semantics bounds (clipped
 * to their parents, so a collapsed body reads as height 0) and writes the dialog to
 * `build/reports/joint-dialog/` for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CreateJointChannelDialogLayoutTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun theFormFieldsAreLaidOutInTheDialogBody() {
        val activity = activities.launch()
        // Moko string resources read the app context through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        activity.setContent {
            CreateJointChannelDialog(
                state = CreateJointChannelState(),
                freePlan = true,
                currentServerSlug = "mobile",
                selectableMembers = emptyList(),
                membersLoading = false,
                onIntent = {},
                onDismiss = {},
                onOpenBilling = {},
            )
        }
        settle()
        val dialog = assertNotNull(ShadowDialog.getLatestDialog(), "the dialog window did not open")
        val root = assertNotNull(findComposeRoot(dialog.window!!.decorView), "no compose root in the dialog window")
        val nodes = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::walk)
        }
        walk((root as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)

        val out = File("build/reports/joint-dialog").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
        File(out, "create_joint_channel_dialog.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val density = activity.resources.displayMetrics.density
        // In tree order: the name field, the description textarea, then the invite draft's fields.
        val editable = nodes.filter { it.config.getOrNull(SemanticsProperties.EditableText) != null }
        assertTrue(editable.size >= 2, "editable fields in the dialog: ${editable.size}")
        val fields = editable.take(2)
        val buttons = nodes.filter { it.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Button }
        val footerTop = buttons.maxOf { it.boundsInRoot.top }
        fields.forEach { field ->
            val bounds = field.boundsInRoot
            assertTrue(bounds.height / density >= 32f, "field collapsed: height ${bounds.height / density}dp, bounds $bounds")
            assertTrue(bounds.bottom <= footerTop, "field $bounds overlaps the footer at $footerTop")
        }
    }

    private fun settle() {
        repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200)) }
    }

    private fun findComposeRoot(view: View): View? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findComposeRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
