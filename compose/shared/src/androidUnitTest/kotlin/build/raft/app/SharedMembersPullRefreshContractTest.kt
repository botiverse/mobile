package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hands feedback 078f6bb3 (LX, 2026-10-10): on the shell Members tab a pull only called onRetry(),
 * which bumps the parent's refresh key through a tab lambda that need not recompose, so the local
 * load often never re-ran and the refresh indicator hung or never turned.
 *
 * Production-wiring check: the shell's MembersRouteContent call has exactly one `onRefresh`, wired to
 * the local forced reload (members + computer list), and the reload always ends the refreshing state.
 */
class SharedMembersPullRefreshContractTest {
    private val source =
        repositoryFile("compose/shared/src/commonMain/kotlin/build/raft/app/SettingsHomePage.kt").readText()

    private val membersScreen: String =
        source.substring(source.indexOf("private fun SharedMembersScreen("))
            .let { it.substring(0, it.indexOf("\nprivate fun ", startIndex = 1).takeIf { end -> end > 0 } ?: it.length) }

    @Test
    fun pullToRefreshIsWiredToTheForcedReload() {
        val callStart = membersScreen.indexOf("MembersRouteContent(")
        val call = membersScreen.substring(callStart, membersScreen.indexOf("\n                    )", callStart))
        assertEquals(1, Regex("""\bonRefresh = \{""").findAll(call).count(), "exactly one onRefresh is passed")
        val onRefreshStart = call.indexOf("onRefresh = {")
        val onRefresh = call.substring(onRefreshStart, call.indexOf("\n                        }", onRefreshStart))
        assertTrue("refreshing = true" in onRefresh, "the spinner starts at once")
        assertTrue("membersRetryForce = true" in onRefresh, "the pull forces the members reload")
        assertTrue("membersRetryNonce += 1" in onRefresh, "the pull re-runs the members load")
        assertTrue("retryMachines()" in onRefresh, "the pull refreshes the computer list")
    }

    @Test
    fun theReloadAlwaysEndsTheRefreshingState() {
        val load = membersScreen.substring(membersScreen.indexOf("LaunchedEffect(membersRefreshKey, currentServerId, membersRetryNonce)"))
        val finallyBlock = load.substring(load.indexOf("} finally {"), load.indexOf("} finally {") + 80)
        assertTrue("refreshing = false" in finallyBlock, "success, error and cancellation all stop the spinner")
    }

    private fun repositoryFile(relativePath: String): File {
        val start = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(start) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Unable to resolve $relativePath from ${start.absolutePath}")
    }
}
