package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MembersHumanRoleProductionContractTest {
    @Test
    fun humanDetailUsesTheSettingsMemberRoleApiAndNotTheAgentProfileApi() {
        val source = membersSource()
        val providerMethod = block(
            source,
            "    suspend fun updateHumanRole(",
            "    suspend fun cachedContext("
        )

        assertContains(providerMethod, "settingsRepository.updateServerMemberRole(")
        assertContains(providerMethod, "membersHumanRoleUpdateServerMatches(expectedServerId, selectedServerStore.selectedServerId.value)")
        assertContains(providerMethod, "ScreenError(\"member_role_server_changed\", retryable = false)")
        assertContains(providerMethod, "SettingsServerMemberRoleUpdateRequest(memberId = memberId, role = role.wireValue)")
        assertFalse("updateAgentProfile(" in providerMethod)
    }

    @Test
    fun productHumanRoutePassesActorTargetAndOwnerContextToTheEditor() {
        val humanRoute = block(
            membersSource(),
            "        is MemberDetailRouteState.Human -> {",
            "private fun KuiklyMemberDetailPayload.toMemberDetailVisualSeedOrNull()"
        )

        assertContains(humanRoute, "humanActorRole = current.server.role")
        assertContains(humanRoute, "humanOwnerCount = ownerCount.takeIf { it > 0 }")
        assertContains(humanRoute, "humanAccessMode = current.accessMode")
        assertContains(humanRoute, "onUpdateHumanRole = { memberId, role ->")
        assertContains(humanRoute, "provider.updateHumanRole(current.server.id.value, memberId, role)")
    }

    @Test
    fun humanLookupPreservesRemoteAccessModeAndCanonicalProfileServerIdentity() {
        val source = membersSource()
        val loadDispatch = block(
            source,
            "        return identity.dispatchMemberDetailLookup(",
            "    private suspend fun loadAgentProfileOnly("
        )
        val humanLoader = block(
            source,
            "    private suspend fun loadHumanMember(",
            "    fun updateCachedMemberProfile("
        )

        assertContains(loadDispatch, "loadHuman = { selectedId, sourceServerId, accessMode ->")
        assertContains(loadDispatch, "accessMode = accessMode")
        assertContains(humanLoader, "val member = memberResult.data.copy(serverId = profileServerId)")
        assertContains(humanLoader, "MemberDetailRouteState.Human(server, currentUserId, member, accessMode)")
    }

    @Test
    fun humanVisualFixtureIsARealEditableServerRoleCase() {
        val source = membersSource()
        val fixture = block(
            source,
            "private val membersHumanDetailVisualMember = MemberProfile(",
            "private val membersAgentDetailVisualScopeSet"
        )
        val visualHost = block(
            source,
            "private fun MemberDetailVisualSeedContent(",
            "private fun MemberDetailLoadingPageShell("
        )

        assertContains(fixture, "serverRole = \"member\"")
        assertContains(fixture, "serverId = ServerId(\"visual-server\")")
        assertContains(visualHost, "humanActorRole = \"owner\"")
        assertContains(visualHost, "onUpdateHumanRole =")
    }

    private fun block(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start + startMarker.length)
        assertTrue(start >= 0, "Missing production start marker: $startMarker")
        assertTrue(end > start, "Missing production end marker after $startMarker: $endMarker")
        return source.substring(start, end)
    }

    private fun membersSource(): String {
        val commonMain =
            listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                .firstOrNull(File::isDirectory)
                ?: error("Could not locate shared commonMain Kotlin sources")
        val file = File(commonMain, "build/raft/app/MembersPage.kt")
        assertTrue(file.isFile, "Missing production source MembersPage.kt")
        return file.readText().replace("\r\n", "\n")
    }
}
