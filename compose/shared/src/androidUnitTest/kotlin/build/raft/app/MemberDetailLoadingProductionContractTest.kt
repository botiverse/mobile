package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemberDetailLoadingProductionContractTest {
    @Test
    fun productionMemberDetailUsesScopedSeedAndDoesNotWaitForTabOrderFullscreen() {
        val members = source("build/raft/app/MembersPage.kt")
        val page =
            block(
                members,
                "private fun MemberDetailPageContent()",
                "private fun MemberDetailVisualSeedContent("
            )

        assertContains(page, "memberDetailInitialRouteState(")
        assertContains(page, "activeServerId = provider.selectedServerStore.currentServerId.value")
        assertContains(page, "agentDetailState = provider.agentDetailStore.state.value")
        assertContains(page, "serverMembersState = provider.serverMembersStore.state.value")
        assertContains(page, "if (member == null)")
        assertContains(
            page,
            "initialAgentTab =\n" +
                "                        if (current.accessMode.allowsPrivateAgentSurfaces) {\n" +
                "                            initialAgentTabForRender"
        )
        assertContains(members, "var selectedTabOverride by remember(member.id.value, initialAgentTab)")
        assertFalse("remember(member.id.value, initialAgentTab, agentPanelTabOrder)" in members)
        assertFalse("waitForAgentPanelTabOrder" in page)
        assertFalse("member == null ||" in page)
    }

    @Test
    fun productionActivityTabKeepsLoadingAndFailureInsideThePanel() {
        val members = source("build/raft/app/MembersPage.kt")
        val page =
            block(
                members,
                "private fun MemberDetailPageContent()",
                "private fun MemberDetailVisualSeedContent("
            )
        val activity =
            block(
                members,
                "private fun MembersAgentActivityTab(",
                "private fun MembersAgentActivityRow("
            )

        assertContains(page, "MembersAgentActivityRequestFence(")
        assertContains(page, "fence.matches(memberIdentity, routeServerId, agentActivityRequestGeneration)")
        assertContains(page, "agentActivityLoadState = membersAgentActivityLoadStart(hasCachedActivity)")
        assertContains(page, "agentActivityLoadState = agentActivityLoadState")
        assertContains(activity, "MembersAgentActivityLoadNotice(loadState)")
        assertContains(activity, "SlockCenteredLoading(")
        assertContains(activity, "member_state_loading_activity")
        assertContains(activity, "member_error_load_activity")
        assertTrue(activity.indexOf("MembersAgentActivityLoadNotice(loadState)") < activity.indexOf("LazyColumn("))
    }

    private fun block(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start + startMarker.length)
        assertTrue(start >= 0, "Missing production start marker: $startMarker")
        assertTrue(end > start, "Missing production end marker after $startMarker: $endMarker")
        return source.substring(start, end)
    }

    private fun source(path: String): String {
        val commonMain =
            listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                .firstOrNull(File::isDirectory)
                ?: error("Could not locate shared commonMain Kotlin sources")
        val file = File(commonMain, path)
        assertTrue(file.isFile, "Missing production source $path")
        return file.readText().replace("\r\n", "\n")
    }
}
