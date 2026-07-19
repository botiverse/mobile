package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MembersProductionVisualCallsiteContractTest {
    @Test
    fun realMembersRowsAndAddActionsConsumeTheApprovedTokens() {
        val members = source("build/raft/app/MembersPage.kt")
        val row = block(members, "private fun MembersListRow(", "private fun MembersAvatar(")
        val avatar = block(members, "private fun MembersAvatar(", "private fun AgentStatus?.membersStatusColor(")
        val list = block(members, "internal fun MembersListContent(", "internal fun membersLazyItemCount(")

        assertContains(row, ".heightIn(min = MembersListRowMinHeight)")
        assertContains(row, "Arrangement.spacedBy(MembersListContentGap)")
        assertContains(row, "style = MembersListNameTextStyle")
        assertContains(row, "style = MembersListDescriptionTextStyle")
        assertContains(row, "maxWidth * MembersListNameMaxWidthFraction")
        assertContains(avatar, "avatarSize: Dp = MembersListAvatarSize")
        assertEquals(2, list.count("size = MembersListSectionAddActionSize"))
        assertEquals(2, list.count("iconSize = MembersListSectionAddActionIconSize"))
    }

    @Test
    fun membersLoadingErrorAndContentShareTheProductionMembersHeader() {
        val members = source("build/raft/app/MembersPage.kt")
        val settings = source("build/raft/app/SettingsHomePage.kt")
        val route = block(members, "internal fun MembersRouteContent(", "internal fun MembersListContent(")
        val header = block(members, "internal fun MembersPageHeader(", "private fun MembersListRow(")
        val shellStates = block(settings, "private fun SharedMembersScreen(", "internal fun buildHostMembersPayload(")
        val error = block(settings, "private fun MembersPageErrorContent(", "private fun PageErrorBody(")

        assertContains(route, "MembersPageHeader(title = payload.title")
        assertContains(header, "SlockMainTabPageHeader(")
        assertContains(header, "surface = SlockMainTabHeaderSurface.Members")
        assertContains(shellStates, "SharedMembersUiState.Loading ->")
        assertContains(shellStates, "MembersPageHeader(title = \"Members\")")
        assertContains(shellStates, "is SharedMembersUiState.Error ->")
        assertContains(shellStates, "MembersPageErrorContent(")
        assertContains(shellStates, "is SharedMembersUiState.Content ->")
        assertContains(shellStates, "MembersRouteContent(")
        assertContains(error, "MembersPageHeader(title = \"Members\")")
        assertTrue("Workspace members" !in shellStates && "Workspace members" !in error)
    }

    @Test
    fun threeRootTabSurfacesUseTheSharedHeaderWiringWithTheirOwnIcons() {
        val members = source("build/raft/app/MembersPage.kt")
        val tasks = source("build/raft/app/SharedTasksPage.kt")
        val settings = source("build/raft/app/SettingsHomePage.kt")
        val headerPrimitive = source("build/raft/app/SlockSectionHeader.kt")

        assertContains(
            block(members, "internal fun MembersPageHeader(", "private fun MembersListRow("),
            "surface = SlockMainTabHeaderSurface.Members"
        )
        assertContains(
            block(tasks, "private fun SharedTasksHeader(", "private fun localizedTasksHeaderSubtitle("),
            "surface = SlockMainTabHeaderSurface.Tasks"
        )
        assertContains(
            settings.substringAfter("internal fun SettingsHomeContent("),
            "surface = SlockMainTabHeaderSurface.Settings"
        )
        val sharedHeader = block(headerPrimitive, "internal fun SlockMainTabPageHeader(", "internal fun SlockHeaderRefreshIndicator(")
        assertContains(sharedHeader, "iconName = surface.iconName")
        assertContains(sharedHeader, "contentStartPadding = SlockMainTabHeaderStartPadding")
        assertContains(sharedHeader, "titleStyle = SlockMainTabHeaderTitleStyle")
    }

    private fun String.count(needle: String): Int = split(needle).size - 1

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
