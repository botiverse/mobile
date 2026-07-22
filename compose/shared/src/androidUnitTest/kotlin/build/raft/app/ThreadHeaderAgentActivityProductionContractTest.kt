package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadHeaderAgentActivityProductionContractTest {
    @Test
    fun productionDmHeaderSubscribesToThePeerLiveActivityProjection() {
        val source = productionSource()
        val header =
            source.substringAfter("private fun ThreadPageHeader(")
                .substringBefore("@Composable\nprivate fun ThreadChannelTile(")

        assertContains(source, "agentStatusResolver = agentStatusResolver")
        assertContains(header, "headerDisplay.dmAgentActivityMemberId()")
        assertContains(header, "rememberSlockAgentStatusIndicatorState(")
        assertContains(header, "dmAgentActivity?.let { ThreadDmAgentActivity(state = it) }")
        assertTrue(
            header.indexOf("dmAgentActivity?.let { ThreadDmAgentActivity(state = it) }") <
                header.indexOf("showActions &&"),
            "Agent activity must stay beside the DM title, before the independent right-side action group"
        )
        assertContains(header, "text = state.label")
        assertContains(header, "pulsing = state.pulsing")
        assertFalse("Offline" in header, "Missing live state must not invent an offline activity label")
    }

    @Test
    fun visualFixtureUsesTheRealHeaderWithAClippedLongActivityLabel() {
        val source = productionSource()
        val fixture =
            source.substringAfter("internal fun ThreadDmAgentActivityHeaderVisualScreen()")
                .substringBefore("@Composable\ninternal fun ThreadActivityMuteVisualScreen()")

        assertContains(fixture, "ThreadPageHeader(")
        assertContains(fixture, "Reviewing release readiness and visual diffs")
        assertContains(source, "ThreadHeaderDmAgentActivityMaxWidth")
        assertContains(source, "overflow = TextOverflow.Ellipsis")
        assertTrue(File(visualFixturesRoot(), "build/raft/app/VisualFixturePages.kt").readText().contains("compose_thread_dm_agent_activity_header_visual"))
    }

    private fun productionSource(): String {
        val file = File(commonMainRoot(), "build/raft/app/ThreadPage.kt")
        assertTrue(file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private fun commonMainRoot(): File =
        listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
            .firstOrNull(File::isDirectory)
            ?: error("Could not locate shared commonMain Kotlin sources")

    private fun visualFixturesRoot(): File =
        listOf(File("src/visualFixtures/kotlin"), File("shared/src/visualFixtures/kotlin"))
            .firstOrNull(File::isDirectory)
            ?: error("Could not locate shared visual fixture Kotlin sources")
}
