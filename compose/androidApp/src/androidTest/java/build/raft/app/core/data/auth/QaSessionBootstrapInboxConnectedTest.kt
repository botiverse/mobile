package build.raft.app.core.data.auth

import android.system.Os
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
    fun consumesOnlyExact0600RegularFiles() {
        val file = requestFile("mode")
        file.writeBytes("ticket".encodeToByteArray())
        Os.chmod(file.absolutePath, 0b110000000)

        assertArrayEquals("ticket".encodeToByteArray(), QaSessionBootstrapInbox.consumeFile(file))
        assertFalse(file.exists())

        val readOnly = requestFile("mode-read-only")
        readOnly.writeBytes("ticket".encodeToByteArray())
        Os.chmod(readOnly.absolutePath, 0b100000000)
        assertNull(QaSessionBootstrapInbox.consumeFile(readOnly))

        val ownerExecutable = requestFile("mode-owner-executable")
        ownerExecutable.writeBytes("ticket".encodeToByteArray())
        Os.chmod(ownerExecutable.absolutePath, 0b111000000)
        assertNull(QaSessionBootstrapInbox.consumeFile(ownerExecutable))
    }

    @Test
    fun rejects0600FilesWithAnySpecialModeBit() {
        listOf(
            0x980, // POSIX 04600: setuid + 0600
            0x580, // POSIX 02600: setgid + 0600
            0x380, // POSIX 01600: sticky + 0600
        ).forEachIndexed { index, mode ->
            val file = requestFile("mode-special-$index")
            file.writeBytes("ticket".encodeToByteArray())
            Os.chmod(file.absolutePath, mode)

            assertNull("mode=${mode.toString(8)}", QaSessionBootstrapInbox.consumeFile(file))
            assertTrue(file.exists())
        }
    }

    @Test
    fun rejectsOriginalPathSymlinkEvenWhenTargetIsA0600Sibling() {
        val target = requestFile("symlink-target")
        target.writeBytes("ticket".encodeToByteArray())
        Os.chmod(target.absolutePath, 0b110000000)
        val link = requestFile("symlink")
        Os.symlink(target.name, link.absolutePath)

        assertNull(QaSessionBootstrapInbox.consumeFile(link))
        assertArrayEquals("ticket".encodeToByteArray(), target.readBytes())
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
