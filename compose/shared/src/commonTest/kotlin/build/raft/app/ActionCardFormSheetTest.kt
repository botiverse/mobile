package build.raft.app

import build.raft.app.data.*
import build.raft.app.model.MemberKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActionCardFormSheetTest {
    private val form = ActionCardFormTestFixtures.form

    @Test
    fun defaultsAreTheInitialValues() {
        val values = initialActionCardFormValues(form)
        assertEquals(ActionCardFormValue.Text("Export does nothing on Safari"), values["summary"])
        assertEquals(ActionCardFormValue.Text("problem"), values["feedbackType"])
        assertEquals(ActionCardFormValue.Toggle(false), values["contactMe"])
        assertEquals(ActionCardFormValue.Ids(listOf("user-designer", "agent-cindy")), values["cc"])
        assertEquals(ActionCardFormValue.Ids(emptyList()), values["owner"])
    }

    @Test
    fun clientCheckFindsRequiredFieldsInOrder() {
        val issues = checkActionCardFormValues(form, ActionCardFormTestFixtures.empty)
        assertEquals(listOf("summary", "message", "feedbackType"), issues.map { it.field })
        assertTrue(issues.all { it.code == ActionCardFormIssueCode.Required })
        assertEquals("This field is required.", renderActionCardMsgText(issues.first().message, "en"))
        assertTrue(checkActionCardFormValues(form, ActionCardFormTestFixtures.filled).isEmpty())
    }

    @Test
    fun clientCheckTreatsJavaScriptBlanksAsEmpty() {
        val values = ActionCardFormTestFixtures.filled + ("summary" to ActionCardFormValue.Text(" 　﻿\t"))
        assertEquals(listOf(ActionCardFormIssueCode.Required), checkActionCardFormValues(form, values).map { it.code })
        // U+200B is not in JavaScript's trim set: not blank.
        val zeroWidth = ActionCardFormTestFixtures.filled + ("summary" to ActionCardFormValue.Text("​"))
        assertTrue(checkActionCardFormValues(form, zeroWidth).isEmpty())
    }

    @Test
    fun clientCheckCountsCodePoints() {
        val emoji = "😀" // one code point, two UTF-16 units
        val fits = ActionCardFormTestFixtures.filled + ("summary" to ActionCardFormValue.Text(emoji.repeat(80)))
        assertTrue(checkActionCardFormValues(form, fits).isEmpty())
        val over = ActionCardFormTestFixtures.filled + ("summary" to ActionCardFormValue.Text(emoji.repeat(81)))
        val issue = checkActionCardFormValues(form, over).single()
        assertEquals(ActionCardFormIssueCode.TooLong, issue.code)
        assertEquals("Use 80 characters or fewer.", renderActionCardMsgText(issue.message, "en"))
        assertEquals("最多 80 个字符。", renderActionCardMsgText(issue.message, "zh-cn"))
    }

    @Test
    fun clientCheckLimitsPickers() {
        val six = ActionCardFormValue.Ids((1..6).map { "user-$it" })
        val issue = checkActionCardFormValues(form, ActionCardFormTestFixtures.filled + ("cc" to six)).single()
        assertEquals("cc", issue.field)
        assertEquals(ActionCardFormIssueCode.TooMany, issue.code)
        assertEquals("Choose 5 or fewer.", renderActionCardMsgText(issue.message, "en"))
        // A single picker takes one.
        val two = ActionCardFormValue.Ids(listOf("agent-cindy", "agent-lagoon"))
        assertEquals(ActionCardFormIssueCode.TooMany, checkActionCardFormValues(form, ActionCardFormTestFixtures.filled + ("owner" to two)).single().code)
    }

    @Test
    fun clientCheckLeavesWrongTypesToTheServer() {
        val values = ActionCardFormTestFixtures.filled + ("summary" to ActionCardFormValue.Toggle(true)) + ("cc" to ActionCardFormValue.Text("x"))
        assertTrue(checkActionCardFormValues(form, values).isEmpty())
    }

    @Test
    fun pickerToggleKeepsSinglePickersToOne() {
        assertEquals(listOf("b"), actionCardFormPickerToggle(listOf("a"), "b", multiple = false))
        assertEquals(listOf("a", "b"), actionCardFormPickerToggle(listOf("a"), "b", multiple = true))
        assertEquals(emptyList<String>(), actionCardFormPickerToggle(listOf("a"), "a", multiple = true))
    }

    @Test
    fun memberCandidatesFollowScopeAndKinds() {
        val candidates = ActionCardFormTestFixtures.candidates
        val cc = form.fields.first { it.name == "cc" } as ActionCardSpecPickerField
        assertEquals(candidates.channelMembers, actionCardFormMemberCandidates(cc, candidates))
        val owner = form.fields.first { it.name == "owner" } as ActionCardSpecPickerField
        assertTrue(actionCardFormMemberCandidates(owner, candidates).all { it.kind == MemberKind.Agent })
        val humans =
            ActionCardSpecMemberPickerField(
                name = "h",
                label = ActionCardSpecMsg("actionCard.h", mapOf("en" to "H")),
                multiple = true,
                kinds = listOf(ActionCardSpecMemberKind.Human),
                scope = ActionCardSpecMemberScope.Server,
            )
        assertEquals(candidates.serverMembers.filter { it.kind == MemberKind.Human }, actionCardFormMemberCandidates(humans, candidates))
    }

    @Test
    fun channelFilterMatchesEveryWord() {
        val channels = ActionCardFormTestFixtures.candidates.joinedChannels
        assertEquals(channels, actionCardFormFilterChannels(channels, "  "))
        assertEquals(listOf("launch-week"), actionCardFormFilterChannels(channels, "LAUNCH week").map { it.title })
    }
}
