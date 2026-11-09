package build.raft.app.markdown

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose's selection copy (system text menu, Ctrl+C) reaches the clipboard without layout-only characters. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RaftCleanCopyClipboardTest {
    // `reply/channel-markdown/<long token>` as rendered: slash glue, hyphen break mark, padding gaps.
    private val longToken = "averyveryverylongtokenwithoutanybreakopportunity0123456789"
    private val source = "reply/channel-markdown/$longToken"
    private val rendered =
        "￼⁠reply/⁠channel-​markdown/⁠$longToken⁠￼"

    @Test
    fun selectionCopyWritesTheSourceText() {
        val platform = RecordingClipboard()
        runBlocking {
            RaftCleanCopyClipboard(platform).setClipEntry(ClipEntry(ClipData.newPlainText("text", rendered)))
        }
        assertEquals(source, platform.written!!.clipData.getItemAt(0).text.toString())
    }

    @Test
    fun cleanTextIsWrittenUnchanged() {
        val entry = ClipEntry(ClipData.newPlainText("text", source))
        assertSame(entry, raftCleanCopyClipEntry(entry))
    }

    private class RecordingClipboard : Clipboard {
        var written: ClipEntry? = null

        override suspend fun getClipEntry(): ClipEntry? = written

        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            written = clipEntry
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override val nativeClipboard: NativeClipboard
            get() = error("not used")
    }
}
