package build.raft.app

import build.raft.app.data.SharedMembersLoadStatus
import build.raft.app.model.MemberKind
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ThreadComposerLayoutTest {
    @Test
    fun legacyFirstCharacterAtRepairsCursorAndKeepsMemberTriggerActive() {
        val repaired =
            repairThreadComposerLegacyZeroSelection(
                previous = TextFieldValue("", selection = TextRange.Zero),
                incoming = TextFieldValue("@", selection = TextRange.Zero),
            )

        assertEquals(TextRange(1), repaired.selection)
        assertEquals(
            RaftComposerSuggestionTrigger.MemberTrigger(query = "", triggerStart = 0),
            threadComposerSuggestionTrigger(repaired.text, repaired.selection.start),
        )
    }

    @Test
    fun legacyZeroSelectionPreservesCursorMovesDeletionsCompositionAndUnprovenEdits() {
        val cursorMove = TextFieldValue("@artin", selection = TextRange.Zero)
        val deletion = TextFieldValue("artin", selection = TextRange.Zero)
        val composing =
            TextFieldValue(
                text = "ni",
                selection = TextRange.Zero,
                composition = TextRange(0, 2),
            )
        val unprovenEdit = TextFieldValue("rewritten", selection = TextRange.Zero)

        assertSame(
            cursorMove,
            repairThreadComposerLegacyZeroSelection(
                previous = TextFieldValue("@artin", selection = TextRange(6)),
                incoming = cursorMove,
            ),
        )
        assertSame(
            deletion,
            repairThreadComposerLegacyZeroSelection(
                previous = TextFieldValue("@artin", selection = TextRange(1)),
                incoming = deletion,
            ),
        )
        assertSame(
            composing,
            repairThreadComposerLegacyZeroSelection(
                previous = TextFieldValue("n", selection = TextRange(1), composition = TextRange(0, 1)),
                incoming = composing,
            ),
        )
        assertSame(
            unprovenEdit,
            repairThreadComposerLegacyZeroSelection(
                previous = TextFieldValue("draft", selection = TextRange(2)),
                incoming = unprovenEdit,
            ),
        )
    }

    @Test
    fun stableMemberCallbackUsesLatestDraftAfterInitialEmptyComposition() {
        val member = RaftComposerMemberItem(displayName = "Artin", handle = "artin", memberId = "member-1")
        val committed = mutableListOf<RaftComposerSuggestionEdit>()
        val dispatcher =
            ThreadComposerSuggestionSelectionDispatcher(
                initialText = "",
                initialSelectionStart = 0,
                initialSelectionEnd = 0,
                initialSuggestionsEnabled = true
            )
        val stableCallback = dispatcher::selectMember

        dispatcher.updateDraft(text = "@", selectionStart = 1, selectionEnd = 1)
        dispatcher.updateComposition(
            suggestionsEnabled = true,
            memberSuggestions = listOf(member),
            channelSuggestions = emptyList(),
            onCommit = committed::add
        )
        val context =
            requireNotNull(
                threadComposerSuggestionSelectionContext(
                    text = "@",
                    selectionStart = 1,
                    selectionEnd = 1,
                    suggestionsEnabled = true
                )
            )

        assertTrue(stableCallback(context, member))
        assertEquals(listOf(RaftComposerSuggestionEdit(text = "@artin ", cursor = 7)), committed)
    }

    @Test
    fun stalePresentationIsRejectedAfterTextOrRangeChanges() {
        val channel = RaftComposerChannelItem(name = "design")
        val committed = mutableListOf<RaftComposerSuggestionEdit>()
        val dispatcher =
            ThreadComposerSuggestionSelectionDispatcher(
                initialText = "#d",
                initialSelectionStart = 2,
                initialSelectionEnd = 2,
                initialSuggestionsEnabled = true
            )
        dispatcher.updateComposition(
            suggestionsEnabled = true,
            memberSuggestions = emptyList(),
            channelSuggestions = listOf(channel),
            onCommit = committed::add
        )
        val oldContext =
            requireNotNull(
                threadComposerSuggestionSelectionContext(
                    text = "#d",
                    selectionStart = 2,
                    selectionEnd = 2,
                    suggestionsEnabled = true
                )
            )

        dispatcher.updateDraft(text = "#de", selectionStart = 3, selectionEnd = 3)
        assertFalse(dispatcher.selectChannel(oldContext, channel))
        dispatcher.updateDraft(text = "#d", selectionStart = 1, selectionEnd = 1)
        assertFalse(dispatcher.selectChannel(oldContext, channel))
        assertEquals(emptyList(), committed)
    }

    @Test
    fun mentionSuggestionInsertMovesCursorAfterInsertedMention() {
        val edit = applyRaftComposerSuggestionEdit(
            text = "ping @ar",
            selectionStart = 8,
            selectionEnd = 8,
            triggerStart = 5,
            replacement = "@artin "
        )

        assertEquals(RaftComposerSuggestionEdit(text = "ping @artin ", cursor = 12), edit)
    }

    @Test
    fun mentionSuggestionInsertPreservesSuffixWithoutDoubleSpace() {
        val edit = applyRaftComposerSuggestionEdit(
            text = "ping @ar world",
            selectionStart = 8,
            selectionEnd = 8,
            triggerStart = 5,
            replacement = "@artin "
        )

        assertEquals(RaftComposerSuggestionEdit(text = "ping @artin world", cursor = 12), edit)
    }

    @Test
    fun directMentionInsertUsesCursorInsteadOfAppending() {
        val edit = applyRaftComposerDirectMentionInsert(
            text = "hello  world",
            selectionStart = 6,
            selectionEnd = 6,
            handle = "@artin"
        )

        assertEquals(RaftComposerSuggestionEdit(text = "hello @artin world", cursor = 12), edit)
    }

    @Test
    fun memberSuggestionTriggerShowsLoadingPanelBeforeItemsArrive() {
        assertEquals(
            ThreadComposerSuggestionPanelState.MemberLoading,
            threadComposerSuggestionPanelState(
                focused = true,
                trigger = RaftComposerSuggestionTrigger.MemberTrigger(query = "", triggerStart = 0),
                memberCount = 0,
                memberStatus = SharedMembersLoadStatus.InFlight,
                channelCount = 0,
                channelStatus = SharedMembersLoadStatus.Loaded
            )
        )
    }

    @Test
    fun memberSuggestionTriggerShowsEmptyPanelAfterLoadedEmptyResult() {
        assertEquals(
            ThreadComposerSuggestionPanelState.MemberEmpty,
            threadComposerSuggestionPanelState(
                focused = true,
                trigger = RaftComposerSuggestionTrigger.MemberTrigger(query = "missing", triggerStart = 0),
                memberCount = 0,
                memberStatus = SharedMembersLoadStatus.Loaded,
                channelCount = 0,
                channelStatus = SharedMembersLoadStatus.Loaded
            )
        )
    }

    @Test
    fun enterAcceptsMemberSuggestionBeforeSendWhenItemsAreVisible() {
        assertEquals(
            ThreadComposerEnterAction.AcceptMemberSuggestion,
            threadComposerEnterAction(
                trigger = RaftComposerSuggestionTrigger.MemberTrigger(query = "ar", triggerStart = 5),
                panelState = ThreadComposerSuggestionPanelState.MemberItems,
                memberCount = 1,
                channelCount = 0,
                canSend = true,
                isImeComposing = false
            )
        )
    }

    @Test
    fun visibleMemberCandidateLeavesNewlineModeAlone() {
        // Was: a visible candidate claimed the IME action even with the preference off, so Enter
        // accepted the suggestion. Device measurement showed the action is never withdrawn when
        // the panel closes, after which Enter is swallowed and the composer cannot break lines
        // until the page is re-entered. Newline mode now never claims the action; suggestions are
        // accepted by tapping.
        assertEquals(
            false,
            threadComposerUsesImeAction(
                composerSendKeyEnabled = false,
                panelState = ThreadComposerSuggestionPanelState.MemberItems,
                memberCount = 1,
                channelCount = 0
            )
        )
    }

    @Test
    fun firstMemberSuggestionCandidateMatchesVisualGrouping() {
        val outside =
            RaftComposerMemberItem(
                displayName = "Outside",
                handle = "outside",
                inMentionChannel = false
            )
        val inside =
            RaftComposerMemberItem(
                displayName = "Inside",
                handle = "inside",
                inMentionChannel = true
            )
        val inThread =
            RaftComposerMemberItem(
                displayName = "Thread",
                handle = "thread",
                inMentionChannel = false,
                inThread = true
            )

        assertEquals(inside, listOf(outside, inside).firstComposerMemberSuggestionCandidate())
        assertEquals(inThread, listOf(outside, inside, inThread).firstComposerMemberSuggestionCandidate())
        assertEquals(outside, listOf(outside).firstComposerMemberSuggestionCandidate())
    }

    @Test
    fun memberSuggestionGroupsDeduplicateThreadParticipantsFromChannelSections() {
        val threadCopy =
            RaftComposerMemberItem(
                displayName = "Cindy",
                handle = "cindy",
                memberId = "u-cindy",
                inMentionChannel = true,
                inThread = true
            )
        val channelCopy =
            RaftComposerMemberItem(
                displayName = "Cindy",
                handle = "cindy",
                memberId = "u-cindy",
                inMentionChannel = true
            )
        val outside =
            RaftComposerMemberItem(
                displayName = "Outside",
                handle = "outside",
                inMentionChannel = false
            )

        val groups = listOf(channelCopy, outside, threadCopy).composerMemberSuggestionGroups()

        assertEquals(listOf(threadCopy), groups.inThread)
        assertEquals(emptyList(), groups.inMentionChannel)
        assertEquals(listOf(outside), groups.notInMentionChannel)
        assertEquals(outside, listOf(channelCopy, outside, threadCopy).composerMemberSuggestionCandidateAt(99))
    }

    @Test
    fun memberSuggestionGroupsKeepDistinctKindsAndMembersWithTheSameHandle() {
        val localAgent =
            RaftComposerMemberItem(
                displayName = "Builder",
                handle = "builder",
                memberId = "local-agent",
                kind = MemberKind.Agent,
                inMentionChannel = true
            )
        val remoteAgent =
            RaftComposerMemberItem(
                displayName = "Builder",
                handle = "builder",
                memberId = "remote-agent",
                kind = MemberKind.Agent,
                inMentionChannel = true
            )
        val humanWithCollidingId =
            RaftComposerMemberItem(
                displayName = "Builder Human",
                handle = "builder",
                memberId = "local-agent",
                kind = MemberKind.Human,
                inMentionChannel = true
            )

        val groups = listOf(localAgent, remoteAgent, humanWithCollidingId).composerMemberSuggestionGroups()

        assertEquals(listOf(localAgent, remoteAgent, humanWithCollidingId), groups.inMentionChannel)
    }

    @Test
    fun sendKeyPreferenceStillOwnsImeActionWithoutSuggestions() {
        assertEquals(
            true,
            threadComposerUsesImeAction(
                composerSendKeyEnabled = true,
                panelState = ThreadComposerSuggestionPanelState.Hidden,
                memberCount = 0,
                channelCount = 0
            )
        )
    }

    @Test
    fun onlyHardwareEnterDefersToImeComposition() {
        // Gboard keeps the Latin word in composition: the soft Send key must still send.
        assertFalse(threadComposerEnterDeferredByComposition(fromHardwareEnter = false, composition = TextRange(0, 5)))
        assertTrue(threadComposerEnterDeferredByComposition(fromHardwareEnter = true, composition = TextRange(0, 5)))
        assertFalse(threadComposerEnterDeferredByComposition(fromHardwareEnter = true, composition = null))
        assertEquals(
            ThreadComposerEnterAction.Send,
            threadComposerEnterAction(
                trigger = RaftComposerSuggestionTrigger.None,
                panelState = ThreadComposerSuggestionPanelState.Hidden,
                memberCount = 0,
                channelCount = 0,
                canSend = true,
                isImeComposing = threadComposerEnterDeferredByComposition(false, TextRange(0, 5)),
            )
        )
    }
}
