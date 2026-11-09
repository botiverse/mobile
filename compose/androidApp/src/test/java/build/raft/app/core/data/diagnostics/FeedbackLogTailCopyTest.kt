package build.raft.app.core.data.diagnostics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackLogTailCopyTest {
    private val log = (1..5).joinToString("") { """{"n":$it}""" + "\n" }.toByteArray()

    private fun tail(bytes: Long): String =
        ByteArrayOutputStream().also { out ->
            copyTailFromLineStart(ByteArrayInputStream(log), log.size.toLong(), bytes, out)
        }.toString(Charsets.UTF_8.name())

    @Test
    fun aTruncatedShardStartsAtTheFirstFullLine() {
        // 12 bytes start inside line 4: its partial head is dropped; 16 start exactly at it.
        assertEquals("{\"n\":5}\n", tail(12))
        assertEquals("{\"n\":4}\n{\"n\":5}\n", tail(16))
    }

    @Test
    fun aTailCoveringTheWholeFileCopiesItAll() {
        assertEquals(String(log), tail(log.size.toLong()))
        assertEquals(String(log), tail(log.size + 10L))
    }
}
