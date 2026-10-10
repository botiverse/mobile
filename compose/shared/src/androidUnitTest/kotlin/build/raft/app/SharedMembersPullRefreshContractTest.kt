package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Hands feedback 078f6bb3 (LX, 2026-10-10): on the shell Members tab the pull-to-refresh gesture
 * showed but nothing reloaded and the spinner never turned, because SharedMembersScreen passed
 * `refreshing` to MembersRouteContent but no `onRefresh`, so the default no-op ran.
 *
 * Production-wiring check: the shell's MembersRouteContent call wires `onRefresh` to the same forced
 * reload as Retry (members + computer list), and the reload always ends the refreshing state.
 */
class SharedMembersPullRefreshContractTest {
    private val source =
        repositoryFile("compose/shared/src/commonMain/kotlin/build/raft/app/SettingsHomePage.kt").readText()

    private val membersScreen: String =
        source.substring(source.indexOf("private fun SharedMembersScreen("))
            .let { it.substring(0, it.indexOf("\nprivate fun ", startIndex = 1).takeIf { end -> end > 0 } ?: it.length) }

    @Test
    fun pullToRefreshIsWiredToTheForcedReload() {
        val call = membersScreen.substring(membersScreen.indexOf("MembersRouteContent("))
        val onRefresh = call.substring(call.indexOf("onRefresh = {"), call.indexOf("paneLayout = paneLayout"))
        assertTrue(call.indexOf("onRefresh = {") in 0 until call.indexOf("paneLayout = paneLayout"), "onRefresh is passed")
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
