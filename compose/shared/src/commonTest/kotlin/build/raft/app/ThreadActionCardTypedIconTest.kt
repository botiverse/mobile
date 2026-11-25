package build.raft.app

import build.raft.app.data.ThreadActionCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A typed action card (drawn without its spec) shows the header icon the web's spec builder gives its
 * type: slock packages/shared/src/actionCardSpecBuilder.test.ts "each type's header icon is the one the
 * web shows for it" (staging 87ab82603). AppFather 11-23: `integration:connect_mcp` drew the default zap.
 */
class ThreadActionCardTypedIconTest {
    private val web: Map<String, RaftLucideIconName> =
        mapOf(
            "channel:create" to RaftLucideIconName.Hash,
            "agent:create" to RaftLucideIconName.Bot,
            "agent:migrate" to RaftLucideIconName.Bot,
            "channel:add_member" to RaftLucideIconName.UserPlus,
            "integration:approve_agent_login" to RaftLucideIconName.KeyRound,
            "integration:install_marketplace_app" to RaftLucideIconName.Package,
            "integration:register_app" to RaftLucideIconName.AppWindow,
            "integration:update_app_registration" to RaftLucideIconName.AppWindow,
            "integration:recover_app_owner" to RaftLucideIconName.ShieldAlert,
            "integration:connect_mcp" to RaftLucideIconName.Plug,
            "feedback:submit" to RaftLucideIconName.Message,
            "plan:upgrade" to RaftLucideIconName.CreditCard,
            "question:ask" to RaftLucideIconName.Message,
        )

    @Test
    fun everyTypeShowsTheWebsHeaderIcon() {
        for ((type, icon) in web) {
            assertEquals(icon, ThreadActionCard(type = type, state = "prepared", name = "x").actionCardIconName(), type)
        }
    }

    @Test
    fun anUnknownTypeKeepsTheDefaultIcon() {
        assertNull(ThreadActionCard(type = "future:thing", state = "prepared", name = "x").actionCardIconName())
    }
}
