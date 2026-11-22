package build.raft.app

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

    @Test
    fun noScopesMeansNoSentence() {
        assertEquals(emptyList(), membersAgentAppAbilities(emptyList()))
    }

    @Test
    fun everyAbilityHasItsOwnWords() {
        assertEquals(
            listOf(
                Res.string.member_app_ability_sign_in,
                Res.string.member_app_ability_email,
                Res.string.member_app_ability_agent_directory,
                Res.string.member_app_ability_events,
                Res.string.member_app_ability_notifications,
                Res.string.member_app_ability_action_requests,
            ),
            MembersAgentAppAbility.entries.map { it.short },
        )
        // Named on its own in a grant's details and the revoke confirmation (web `scope.*.long`, #13293).
        assertEquals(
            listOf(
                Res.string.member_app_ability_sign_in_long,
                Res.string.member_app_ability_email_long,
                Res.string.member_app_ability_agent_directory_long,
                Res.string.member_app_ability_events_long,
                Res.string.member_app_ability_notifications_long,
                Res.string.member_app_ability_action_requests_long,
            ),
            MembersAgentAppAbility.entries.map { it.long },
        )
    }
}
