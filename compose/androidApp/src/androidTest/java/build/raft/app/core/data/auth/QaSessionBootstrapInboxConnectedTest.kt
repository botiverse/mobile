package build.raft.app.core.data.auth

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QaSessionBootstrapInboxConnectedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val inbox = File(context.filesDir, "qa-bootstrap/inbox")

    @Test
    fun sessionEnvelopeUsesTheSameConsumeOnce0600Boundary() {
        inbox.mkdirs()
        val file = File(inbox, "login-envelope.session")
        file.writeBytes("canonical-envelope".encodeToByteArray())
        Os.chmod(file.absolutePath, 0b110000000)

        assertArrayEquals(
            "canonical-envelope".encodeToByteArray(),
            QaSessionBootstrapInbox.consume(context, "login-envelope", "session")
        )
        assertFalse(file.exists())
        assertNull(QaSessionBootstrapInbox.consume(context, "login-envelope", "session"))
        assertNull(QaSessionBootstrapInbox.consume(context, "login-envelope", "unknown"))
    }

    @Test
    fun rejectsReplacementBetweenLstatAndNoFollowOpen() {
        val original = requestFile("replace")
        original.writeBytes("original".encodeToByteArray())
        Os.chmod(original.absolutePath, 0b110000000)
        val target = requestFile("replace-target")
        target.writeBytes("replacement".encodeToByteArray())
        Os.chmod(target.absolutePath, 0b110000000)

        val result = QaSessionBootstrapInbox.consumeFile(original) {
            assertTrue(original.delete())
            Os.symlink(target.name, original.absolutePath)
        }

        assertNull(result)
        assertArrayEquals("replacement".encodeToByteArray(), target.readBytes())
    }

    private fun requestFile(name: String): File {
        inbox.mkdirs()
        return File(inbox, "${name}-${System.nanoTime()}.ticket").also { it.delete() }
    }
}
