package build.raft.app.core.data.diagnostics

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftGeneralLogRingTest {
    @Test
    fun relativeLogPathWithinRejectsPrefixConfusableAndSymlinkEscapes() {
        val parent = Files.createTempDirectory("raft-diagnostics-prefix-safety").toFile()
        val root = parent.resolve("root-a").apply { mkdirs() }
        val confusable = parent.resolve("root-ab/raft-diagnostics.jsonl").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("{}\n")
        }
        val outside = parent.resolve("outside/raft-diagnostics.jsonl").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("{}\n")
        }
        val linkedDirectory = root.resolve("launch-linked")
        Files.createSymbolicLink(linkedDirectory.toPath(), requireNotNull(outside.parentFile).toPath())

        assertEquals(null, RaftGeneralLogRing.relativeLogPathWithin(root, confusable))
        assertEquals(
            null,
            RaftGeneralLogRing.relativeLogPathWithin(root, linkedDirectory.resolve(outside.name)),
        )
    }
}
