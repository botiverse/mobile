package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeServerSwitcherAvatarSourceContractTest {
    @Test
    fun visibleServerAvatarDoesNotReuseThePrewarmPainter() {
        val source =
            sourceFile("shared/src/commonMain/kotlin/build/raft/app/SlockHomeComponents.kt")
                .readText()
        val tile = source.substringAfter("private fun ServerSwitcherAvatarTile")
            .substringBefore("internal data class ServerSwitcherAvatarPresentation")

        assertTrue(tile.contains("SlockAvatarHttpImage("))
        assertFalse(tile.contains("avatarPainter: AsyncImagePainter"))
        assertTrue(source.contains("ServerSwitcherAvatarPrewarmSurface(serverSwitcherAvatarPainters)"))
        assertTrue(source.contains("key(server.id, source)"))
        assertTrue(source.contains("server-\${order[index]}"))
    }

    private fun sourceFile(relativePath: String): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        var current = File(userDir).absoluteFile
        repeat(8) {
            File(current, relativePath).takeIf(File::isFile)?.let { return it }
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate $relativePath from ${System.getProperty("user.dir")}")
    }
}
