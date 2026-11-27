package build.raft.app

import build.raft.app.members.MembersAgentAppAbility
import build.raft.app.members.MembersAgentAppAbilityItem
import build.raft.app.members.membersAgentAppAbilities
import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Web `appAbilities` (packages/web/src/components/agent/agentAppGrants.ts, botiverse/slock
 * ae2c99ce1aac7b495e8c0646aa67d41862a90572): an Apps row says what the App can do, not its raw scopes.
 */
class MembersAgentAppAbilitiesTest {
    private fun known(vararg abilities: MembersAgentAppAbility) =
        abilities.map { MembersAgentAppAbilityItem(ability = it, rawScope = null) }

    @Test
    fun theIdentityScopesTogetherAreOneAbilitySigningIn() {
        assertEquals(
            known(MembersAgentAppAbility.SignIn, MembersAgentAppAbility.Notifications),
            membersAgentAppAbilities(listOf("openid", "profile", "agent:notification:write", "identity")),
        )
    }

    @Test
    fun knownAbilitiesFollowTheWebOrderWhateverTheScopeOrder() {
        assertEquals(
            known(
                MembersAgentAppAbility.SignIn,
                MembersAgentAppAbility.Email,
                MembersAgentAppAbility.AgentDirectory,
                MembersAgentAppAbility.Events,
                MembersAgentAppAbility.Notifications,
                MembersAgentAppAbility.ActionRequests,
            ),
            membersAgentAppAbilities(
                listOf(
                    "agent:action_request:write", "agent:notification:write", "agent:event:write",
                    "agent:read", "email", "openid", "email",
                )
            ),
        )
    }

    @Test
    fun anUnknownScopeIsShownAsItselfAfterTheKnownOnes() {
        assertEquals(
            known(MembersAgentAppAbility.SignIn) +
                MembersAgentAppAbilityItem(ability = null, rawScope = "linear:issues:write"),
            membersAgentAppAbilities(listOf("linear:issues:write", "openid", "linear:issues:write")),
        )
    }
}
