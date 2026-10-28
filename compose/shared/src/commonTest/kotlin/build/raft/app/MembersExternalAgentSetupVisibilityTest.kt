package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class MembersExternalAgentSetupVisibilityTest {
    @Test
    fun `setup card follows the web rule - external and manageable and not an App Bot`() {
        data class Case(val external: Boolean, val canManage: Boolean, val appInstall: String?, val shown: Boolean)
        listOf(
            Case(external = true, canManage = true, appInstall = null, shown = true),
            Case(external = true, canManage = true, appInstall = "", shown = true),
            Case(external = true, canManage = true, appInstall = "inst_1", shown = false),
            Case(external = true, canManage = false, appInstall = null, shown = false),
            Case(external = false, canManage = true, appInstall = null, shown = false),
        ).forEach { case ->
            assertEquals(
                case.shown,
                membersShowsExternalAgentSetup(case.external, case.canManage, case.appInstall),
                "case=$case",
            )
        }
    }
}
