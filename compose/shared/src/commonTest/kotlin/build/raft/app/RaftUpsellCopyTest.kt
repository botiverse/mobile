package build.raft.app

import build.raft.app.components.raftUpsellCopy
import build.raft.app.data.ActionCardSpecAction
import build.raft.app.data.ActionCardSpecButton
import build.raft.app.data.ActionCardSpecCard
import build.raft.app.data.ActionCardSpecDisplayElement
import build.raft.app.data.ActionCardSpecIntent
import build.raft.app.data.ActionCardSpecMsg
import build.raft.app.data.ActionCardSpecTree
import build.raft.app.data.CardStack
import build.raft.app.data.ScreenError
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.createAgentErrorText
import build.raft.app.data.ActionCardSpecContent
import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals

/** iOS view-only Billing: what a prompt that tells someone to upgrade says and offers instead. */
class RaftUpsellCopyTest {
    @Test
    fun upsellCopyIsNeutralWhereTheAppOffersNoUpgrade() {
        val neutral =
            mapOf(
                Res.string.agent_create_error_limit to Res.string.agent_create_error_limit_neutral,
                Res.string.agent_create_action_upgrade_for_more to Res.string.agent_create_limit_needs_pro,
                Res.string.joint_limit_free_servers to Res.string.joint_limit_free_servers_neutral,
                Res.string.joint_create_free_host_limit to Res.string.joint_create_free_host_limit_neutral,
                Res.string.joint_create_free_host_limit_unnamed to Res.string.joint_create_free_host_limit_unnamed_neutral,
                Res.string.agent_migration_error_pro_plan_required to Res.string.agent_migration_error_pro_plan_required_neutral,
            )
        neutral.forEach { (upsell, statement) ->
            assertEquals(statement.key, raftUpsellCopy(upsell, offersUpgrade = false).key)
            assertEquals(upsell.key, raftUpsellCopy(upsell, offersUpgrade = true).key)
        }
        // Statements with no call to action stay as they are.
        assertEquals(Res.string.joint_billing_locked_detail.key, raftUpsellCopy(Res.string.joint_billing_locked_detail, offersUpgrade = false).key)
    }

    @Test
    fun agentLimitFailureDropsTheServerUpgradeLineWhereTheAppOffersNoUpgrade() {
        val limit = ScreenError(code = "agent_limit_reached", serverMessage = "Agent limit reached (5/5 on Free plan). Upgrade for more.")
        assertEquals("neutral", createAgentErrorText(limit, "generic", limitReachedNeutral = "neutral", offersUpgrade = false))
        assertEquals(limit.serverMessage, createAgentErrorText(limit, "generic", limitReachedNeutral = "neutral", offersUpgrade = true))
        val other = ScreenError(code = "agent_name_invalid", serverMessage = "Name is invalid")
        assertEquals("Name is invalid", createAgentErrorText(other, "generic", limitReachedNeutral = "neutral", offersUpgrade = false))
    }

    @Test
    fun seatRequestButtonsBecomeViewYourPlanWhereTheAppOffersNoUpgrade() {
        val card =
            ActionCardSpecCard(
                title = msg("Add Pro seats"),
                spec =
                    ActionCardSpecTree(
                        root = "root",
                        elements =
                            mapOf(
                                "root" to CardStack(children = listOf("why", "add", "upgrade")),
                                "why" to ActionCardSpecDisplayElement.Text(ActionCardSpecContent.Plain("Ana asks for 1 Pro seat.")),
                                "add" to ActionCardSpecButton(msg("Add Seats"), action = ActionCardSpecAction.Open(ActionCardSpecIntent.AddSeats)),
                                "upgrade" to ActionCardSpecButton(msg("Upgrade"), action = ActionCardSpecAction.Open(ActionCardSpecIntent.UpgradeDialog)),
                            ),
                    ),
            )
        val viewPlan = msg("View your plan")

        val labelled = card.withUpgradeButtonsLabelled(viewPlan)

        assertEquals(viewPlan, (labelled.spec.elements.getValue("add") as ActionCardSpecButton).label)
        assertEquals(viewPlan, (labelled.spec.elements.getValue("upgrade") as ActionCardSpecButton).label)
        val seatRequest = ThreadActionCard(type = "seats:add")
        assertEquals(ThreadActionCardSpecPress.ViewPlan, threadActionCardSpecPress(seatRequest, labelled, "add", offersUpgrade = false))
        assertEquals(ThreadActionCardSpecPress.ViewPlan, threadActionCardSpecPress(seatRequest, labelled, "upgrade", offersUpgrade = false))
        // Android and desktop: the app has no add-seats screen, so the web as before.
        assertEquals(ThreadActionCardSpecPress.OpenInWeb, threadActionCardSpecPress(seatRequest, card, "add", offersUpgrade = true))
    }

    private fun msg(text: String) = ActionCardSpecMsg(key = "test.$text", text = mapOf("en" to text))
}
