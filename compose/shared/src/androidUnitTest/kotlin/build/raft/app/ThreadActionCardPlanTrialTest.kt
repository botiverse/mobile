package build.raft.app

import androidx.compose.ui.test.ExperimentalTestApi
import build.raft.app.data.ActionCardSpecButton
import build.raft.app.data.PlanTrialActionCardType
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.WireActionCardMetadata
import build.raft.app.data.toThreadActionCard
import build.raft.app.resources.Res
import build.raft.app.resources.thread_action_card_plan_trial_mobile_line
import build.raft.app.thread.actionCardMobileLine
import build.raft.app.thread.actionCardSpecToRender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `plan:trial` (Cindy's Pro free trial in the owner's DM): the card's intent is the existing
 * `upgrade_dialog`, but a phone (Android, iOS) shows none of its controls, only the web mobile layout's one line
 * "You can try Pro for free. Start it from Raft on your computer.". The card here is the vendored
 * vector's `open_upgrade_dialog_gate` (a reason, a gate notice and an Open button for
 * `upgrade_dialog`), sent as the wire `metadata` (`kind: "action-card"`, `action.type`).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadActionCardPlanTrialTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun card(type: String, state: String = "prepared"): ThreadActionCard =
        assertNotNull(
            json.decodeFromString(
                WireActionCardMetadata.serializer(),
                """
                {"kind":"action-card","state":"$state","sourceServerId":"7c9e6679-7425-40de-944b-e07fc1f90ae7",
                 "action":{"type":"$type"},
                 "presentation":{"title":"Upgrade to Pro","card":${ActionCardSpecFixtures.rawCard("open_upgrade_dialog_gate")}},
                 "confirmationVersion":1}
                """.trimIndent(),
            ).toThreadActionCard(),
        )

    @Test
    fun aPlanTrialCardIsTheOneLineAndNoCard() {
        val trial = card(PlanTrialActionCardType)
        assertEquals("plan:trial", trial.type)
        // The Android unit host is a phone (raftPlatformHasFinePointer() is false).
        assertEquals(Res.string.thread_action_card_plan_trial_mobile_line, trial.actionCardMobileLine())
        // Done or ended, still the line: a phone never draws this card.
        assertEquals(Res.string.thread_action_card_plan_trial_mobile_line, card(PlanTrialActionCardType, state = "executed").actionCardMobileLine())
        assertEquals(Res.string.thread_action_card_plan_trial_mobile_line, card(PlanTrialActionCardType, state = "expired").actionCardMobileLine())
    }

    @Test
    fun theDesktopAppDrawsThePlanTrialCard() {
        val trial = card(PlanTrialActionCardType)
        // Phones (Android, iOS): the line. The Compose desktop app, like the web on a computer: the card.
        assertEquals(Res.string.thread_action_card_plan_trial_mobile_line, trial.actionCardMobileLine(desktopHost = false))
        assertNull(trial.actionCardMobileLine(desktopHost = true))
        val spec = assertNotNull(trial.actionCardSpecToRender(RAFT_APP_ACTION_CARD_CAPABILITIES))
        assertTrue(spec.spec.elements.values.any { it is ActionCardSpecButton }, "the desktop card keeps its button")
    }
}
