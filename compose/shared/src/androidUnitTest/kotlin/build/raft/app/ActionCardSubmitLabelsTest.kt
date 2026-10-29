package build.raft.app

import build.raft.app.data.ActionCardFormValue
import build.raft.app.data.ActionCardSpecForm
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.renderActionCardMsgText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `action-card.v1` schemaVersion 7 (slock #11937): `forms[].submitLabels`, as the web form sheet
 * reads it (ActionCardFormSheet.tsx): the submit button names the value chosen in the labelled
 * field; nothing chosen, or a value without a label, is the form's own `submitLabel`. And
 * `agent:approve_call`, a spec-only type, draws its spec with the `action_card_spec` flag off.
 */
class ActionCardSubmitLabelsTest {
    private fun decisionForm(card: String): ActionCardSpecForm = assertNotNull(ActionCardSpecFixtures.card(card).forms?.get("decision"))

    private fun ActionCardSpecForm.submitFor(vararg values: Pair<String, ActionCardFormValue>): String =
        renderActionCardMsgText(submitLabelFor(initialActionCardFormValues(this) + values), "en")

    private fun text(value: String) = ActionCardFormValue.Text(value)

    @Test
    fun theLabelFollowsTheChosenValue() {
        val form = decisionForm("form_submit_labels")
        assertEquals("decision", form.submitLabels?.field)
        assertEquals("Submit decision", form.submitFor(), "no default: nothing chosen yet")
        assertEquals("Approve this call", form.submitFor("decision" to text("approve")))
        assertEquals("Deny this call", form.submitFor("decision" to text("deny")))
        assertEquals("批准这次调用", renderActionCardMsgText(form.submitLabelFor(mapOf("decision" to text("approve"))), "zh-cn"))
    }

    @Test
    fun aValueWithoutALabelFallsBackToTheFormsOwnLabel() {
        // labels kept as read (a "maybe" no option has); a value it has no label for is submitLabel.
        val form = decisionForm("form_submit_labels_unknown_option")
        assertEquals(setOf("approve", "deny", "maybe"), form.submitLabels?.labels?.keys)
        assertEquals("Approve this call", form.submitFor("decision" to text("approve")))
        val unlabelled = form.copy(submitLabels = form.submitLabels?.copy(labels = form.submitLabels!!.labels - "deny"))
        assertEquals("Submit decision", unlabelled.submitFor("decision" to text("deny")))
        // No submitLabels at all: the fixed label whatever is chosen.
        assertEquals("Submit decision", form.copy(submitLabels = null).submitFor("decision" to text("approve")))
    }

    @Test
    fun onlyTheNamedFieldDrivesTheLabel() {
        // submitLabels.field names "note" (read keeps it): the decision Select no longer names the button.
        val form = decisionForm("form_submit_labels_not_select")
        assertEquals("Submit decision", form.submitFor("decision" to text("approve")))
    }

    @Test
    fun anApproveCallCardDrawsItsSpecWithTheFlagOff() {
        val raw = ActionCardSpecFixtures.rawCard("form_submit_labels").toString()
        val approveCall = ThreadActionCard(type = "agent:approve_call", presentationCardJson = raw)
        val drawn = assertNotNull(approveCall.actionCardSpecToRender(specEnabled = false, capabilities = RAFT_APP_ACTION_CARD_CAPABILITIES))
        assertNotNull(drawn.forms?.get("decision")?.submitLabels)
        // Any other type still waits for the flag.
        assertNull(approveCall.copy(type = "channel:create").actionCardSpecToRender(specEnabled = false, capabilities = RAFT_APP_ACTION_CARD_CAPABILITIES))
    }
}
