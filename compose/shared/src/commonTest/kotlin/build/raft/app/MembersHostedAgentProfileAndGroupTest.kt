package build.raft.app

import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AgentHostedRuntimeSnapshot
import build.raft.app.data.HostedAgentModelView
import build.raft.app.data.HostedRuntimeModelOption
import build.raft.app.data.MembersAgentMachineGroupNoMachineKey
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cloud (hosted) agents on the Members page and their profile, matched to the web:
 * - AgentProfileInfo renders the Runtime config rows only `!isExternalAgent`; a cloud agent's
 *   runtime and model are the "external" placeholders, its model comes from the provider
 *   (`AgentHostedRuntimeModel`), and a runtime chip names the sentinel "External agent".
 * - SidebarMembersRail keeps every agent without a computer, external and cloud alike, in the one
 *   `__no_machine__` group, labelled "external & cloud".
 */
class MembersHostedAgentProfileAndGroupTest {

    private val hostedRuntime = AgentHostedRuntimeSnapshot(state = "active")

    @Test
    fun aCloudAgentProfileShowsNoRuntimeConfigRows() {
        val cloud = agent("cloud-1").copy(isExternal = true)
        val summary = AgentDetailSummary(id = "cloud-1", name = "cloud-1", runtime = "external", model = "external", isExternal = true, hostedRuntime = hostedRuntime)
        assertFalse(membersAgentProfileShowsRuntimeConfig(cloud, summary))
        // Before the detail lands the roster's flag alone decides; the detail's runtime alone does too.
        assertFalse(membersAgentProfileShowsRuntimeConfig(cloud, null))
        assertFalse(membersAgentProfileShowsRuntimeConfig(agent("cloud-1"), summary.copy(isExternal = false)))
        // A computer agent keeps them.
        val local = AgentDetailSummary(id = "a1", name = "a1", runtime = "claude", model = "claude-sonnet-4-6", machineId = "m1")
        assertTrue(membersAgentProfileShowsRuntimeConfig(agent("a1"), local))
    }

    @Test
    fun theExternalRuntimeSentinelReadsExternalAgentNotTheRawId() {
        assertEquals("External agent", runtimeIdentityDisplayName("external"))
        assertEquals("External agent", runtimeIdentityDisplayName("external", serverId = "server-1"))
    }

    @Test
    fun aCloudAgentModelIsTheProvidersNotThePlaceholder() {
        val offered = listOf(HostedRuntimeModelOption("gpt-5.6-luna", "GPT-5.6 Luna"))
        val chosen = HostedAgentModelView(offered, defaultLabel = "DeepSeek Flash", model = "gpt-5.6-luna", label = null, source = "chosen")
        assertEquals(HostedAgentModelLabel.Named("GPT-5.6 Luna"), hostedAgentModelLabel(chosen), "the offered option's label")
        assertEquals(HostedAgentModelLabel.Named("Luna (custom)"), hostedAgentModelLabel(chosen.copy(label = "Luna (custom)")), "the server's label first")
        assertEquals(HostedAgentModelLabel.Named("openai/gpt-x"), hostedAgentModelLabel(chosen.copy(model = "openai/gpt-x", source = "admin")), "an id no option names: the id")

        val default = HostedAgentModelView(offered, defaultLabel = "DeepSeek Flash", model = "deepseek-flash", label = "DeepSeek Flash", source = "default")
        assertEquals(HostedAgentModelLabel.Default("DeepSeek Flash"), hostedAgentModelLabel(default))
        assertEquals(HostedAgentModelLabel.Default(null), hostedAgentModelLabel(default.copy(defaultLabel = null)))

        val locked = default.copy(locked = true, model = "anthropic/claude-x", label = null, source = "admin")
        assertEquals(HostedAgentModelLabel.Named("anthropic/claude-x"), hostedAgentModelLabel(locked), "locked: label ?? model")

        assertNull(hostedAgentModelLabel(chosen.copy(picker = false)), "Raft decides the model: no Model row")
    }

    @Test
    fun cloudAgentsShareTheExternalAndCloudGroupLast() {
        val agents =
            listOf(
                agent("cloud").copy(isExternal = true),
                agent("on-mac", "computer:My Mac", "computer-id:machine-1"),
                agent("external").copy(isExternal = true)
            )
        val groups = groupedAgentMembers(agents, machineOrder = listOf("machine-1"), noComputerLabel = "外部与云端")
        assertEquals(listOf("machine-1", MembersAgentMachineGroupNoMachineKey), groups.map { it.key })
        val noComputer = groups.last()
        assertEquals("外部与云端", noComputer.label)
        assertEquals(listOf("cloud", "external"), noComputer.members.map { it.id.value })
        // The English default (non-UI callers) is the web's label, not "no computer".
        assertEquals("external & cloud", groupedAgentMembers(agents).last().label)
    }

    private fun agent(id: String, vararg badges: String): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = id,
            handle = id,
            kind = MemberKind.Agent,
            permissionBadges = badges.toList()
        )
}
