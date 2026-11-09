package build.raft.app.nav

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The content fields a page key drops ([RaftPageKeyContentFields]) and the iOS router's
 * (RaftRouterIdentityPageData's blockedKeys) must always agree: one page, one route rule.
 */
class RaftPageKeyContentFieldsSyncTest {
    @Test fun `page key content fields match the iOS router's blocked keys`() {
        val source = String(Files.readAllBytes(routerSource()))
        val body = Regex("""blockedKeys = \[NSSet setWithArray:@\[(.*?)\]\];""", RegexOption.DOT_MATCHES_ALL)
            .find(source)?.groupValues?.get(1) ?: error("blockedKeys not found in RaftPageRouter.m")
        val iosKeys = Regex("""@"([^"]+)"""").findAll(body).map { it.groupValues[1] }.toSet()
        assertEquals(iosKeys, RaftPageKeyContentFields)
    }

    private fun routerSource(): Path {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return listOf("..", ".", "compose")
            .map { workingDirectory.resolve(it).resolve("iosApp/iosApp/Cmp/RaftPageRouter.m").normalize() }
            .firstOrNull(Files::isRegularFile) ?: error("Unable to locate RaftPageRouter.m from $workingDirectory")
    }
}
