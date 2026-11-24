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

    @Test
    fun mentionAndChannelTriggersIgnoreImeComposingSyllableSeparators() {
        // iOS Chinese Pinyin / Shuangpin inserts U+2006 SIX-PER-EM SPACE between syllables.
        assertEquals(
            RaftComposerSuggestionTrigger.MemberTrigger(query = "fr", triggerStart = 0),
            detectRaftComposerSuggestionTrigger(
                text = "@f\u2006r",
                cursor = 4,
                selectionEnd = 4,
                composition = TextRange(1, 4),
            ),
        )
        // Android / desktop Pinyin IMEs can separate composing syllables with ASCII spaces or apostrophes.
        assertEquals(
            RaftComposerSuggestionTrigger.MemberTrigger(query = "zhs", triggerStart = 6),
            detectRaftComposerSuggestionTrigger(
                text = "hello @zh s",
                cursor = 11,
                selectionEnd = 11,
                composition = TextRange(7, 11),
            ),
        )
        assertEquals(
            RaftComposerSuggestionTrigger.MemberTrigger(query = "xian", triggerStart = 0),
            detectRaftComposerSuggestionTrigger(
                text = "@xi'an",
                cursor = 6,
                selectionEnd = 6,
                composition = TextRange(1, 6),
            ),
        )
        assertEquals(
            RaftComposerSuggestionTrigger.ChannelTrigger(query = "projm", triggerStart = 0),
            detectRaftComposerSuggestionTrigger(
                text = "#proj\u2006m",
                cursor = 7,
                selectionEnd = 7,
                composition = TextRange(1, 7),
            ),
        )
        // Backspacing a syllable may briefly leave a trailing U+2006 inside the composing span.
        assertEquals(
            RaftComposerSuggestionTrigger.MemberTrigger(query = "f", triggerStart = 0),
            detectRaftComposerSuggestionTrigger(
                text = "@f\u2006",
                cursor = 3,
                selectionEnd = 3,
                composition = TextRange(1, 3),
            ),
        )
        // IME highlighting the full composing span as selection still resolves the full query.
        assertEquals(
            RaftComposerSuggestionTrigger.MemberTrigger(query = "fr", triggerStart = 0),
            detectRaftComposerSuggestionTrigger(
                text = "@f\u2006r",
                cursor = 1,
                selectionEnd = 4,
                composition = TextRange(1, 4),
            ),
        )
        // Caret sitting mid-composition still matches the full composing query.
        assertEquals(
            RaftComposerSuggestionTrigger.MemberTrigger(query = "zhs", triggerStart = 0),
            detectRaftComposerSuggestionTrigger(
                text = "@zh\u2006s",
                cursor = 2,
                selectionEnd = 2,
                composition = TextRange(1, 5),
            ),
        )
        // Leading U+2006 right after @ stays open with empty or stripped query.
        assertEquals(
            RaftComposerSuggestionTrigger.MemberTrigger(query = "", triggerStart = 0),
            detectRaftComposerSuggestionTrigger(
                text = "@\u2006",
                cursor = 2,
                selectionEnd = 2,
                composition = TextRange(1, 2),
            ),
        )
        assertEquals(
            RaftComposerSuggestionTrigger.MemberTrigger(query = "fr", triggerStart = 0),
            detectRaftComposerSuggestionTrigger(
                text = "@\u2006fr",
                cursor = 4,
                selectionEnd = 4,
                composition = TextRange(1, 4),
            ),
        )
        // Committed CJK prefix followed by composing syllables matches the full token at both caret positions.
        assertEquals(
            RaftComposerSuggestionTrigger.MemberTrigger(query = "中fr", triggerStart = 0),
            detectRaftComposerSuggestionTrigger(
                text = "@中f\u2006r",
                cursor = 2,
                selectionEnd = 2,
                composition = TextRange(2, 5),
            ),
        )
        assertEquals(
            RaftComposerSuggestionTrigger.MemberTrigger(query = "中fr", triggerStart = 0),
            detectRaftComposerSuggestionTrigger(
                text = "@中f\u2006r",
                cursor = 5,
                selectionEnd = 5,
                composition = TextRange(2, 5),
            ),
        )
        // Reversed or out-of-bounds selection/composition ranges normalize safely.
        assertEquals(
            RaftComposerSuggestionTrigger.MemberTrigger(query = "fr", triggerStart = 0),
            detectRaftComposerSuggestionTrigger(
                text = "@f\u2006r",
                cursor = 4,
                selectionEnd = 1,
                composition = TextRange(4, 1),
            ),
        )
        assertEquals(
            RaftComposerSuggestionTrigger.MemberTrigger(query = "fr", triggerStart = 0),
            detectRaftComposerSuggestionTrigger(
                text = "@f\u2006r",
                cursor = 99,
                selectionEnd = 99,
                composition = TextRange(0, 99),
            ),
        )
        assertEquals(
            RaftComposerSuggestionTrigger.MemberTrigger(query = "foo", triggerStart = 0),
            detectRaftComposerSuggestionTrigger(
                text = "@foo",
                cursor = 4,
                selectionEnd = 4,
                composition = TextRange(10, 20),
            ),
        )
    }

    @Test
    fun mentionTriggerStillTerminatesOnCommittedSpaceTrailingAsciiSpaceAndNewline() {
        // Committed space before an active composing word must not attach to an earlier @.
        assertEquals(
            RaftComposerSuggestionTrigger.None,
            detectRaftComposerSuggestionTrigger(
                text = "@foo bar",
                cursor = 8,
                selectionEnd = 8,
                composition = TextRange(5, 8),
            ),
        )
        // Committed U+2006 outside composition still acts as whitespace and terminates @.
        assertEquals(
            RaftComposerSuggestionTrigger.None,
            detectRaftComposerSuggestionTrigger(
                text = "@foo\u2006bar",
                cursor = 8,
                selectionEnd = 8,
                composition = TextRange(5, 8),
            ),
        )
        // Trailing ASCII space at the end of composition (including before apostrophe or ZWS) closes suggestions.
        assertEquals(
            RaftComposerSuggestionTrigger.None,
            detectRaftComposerSuggestionTrigger(
                text = "@artin ",
                cursor = 7,
                selectionEnd = 7,
                composition = TextRange(1, 7),
            ),
        )
        assertEquals(
            RaftComposerSuggestionTrigger.None,
            detectRaftComposerSuggestionTrigger(
                text = "@artin '",
                cursor = 8,
                selectionEnd = 8,
                composition = TextRange(1, 8),
            ),
        )
        assertEquals(
            RaftComposerSuggestionTrigger.None,
            detectRaftComposerSuggestionTrigger(
                text = "@artin \u200B",
                cursor = 8,
                selectionEnd = 8,
                composition = TextRange(1, 8),
            ),
        )
        // Leading ASCII space inside composition must not bridge into a preceding token or @.
        assertEquals(
            RaftComposerSuggestionTrigger.None,
            detectRaftComposerSuggestionTrigger(
                text = "@foo bar",
                cursor = 8,
                selectionEnd = 8,
                composition = TextRange(4, 8),
            ),
        )
        assertEquals(
            RaftComposerSuggestionTrigger.None,
            detectRaftComposerSuggestionTrigger(
                text = "@ bar",
                cursor = 5,
                selectionEnd = 5,
                composition = TextRange(1, 5),
            ),
        )
        assertEquals(
            RaftComposerSuggestionTrigger.None,
            detectRaftComposerSuggestionTrigger(
                text = "@ bar",
                cursor = 5,
                selectionEnd = 5,
                composition = TextRange(0, 5),
            ),
        )
        // Tabs, full-width CJK spaces, and line breaks inside composition terminate trigger detection.
        assertEquals(
            RaftComposerSuggestionTrigger.None,
            detectRaftComposerSuggestionTrigger(
                text = "@f\tr",
                cursor = 4,
                selectionEnd = 4,
                composition = TextRange(1, 4),
            ),
        )
        assertEquals(
            RaftComposerSuggestionTrigger.None,
            detectRaftComposerSuggestionTrigger(
                text = "@f\u3000r",
                cursor = 4,
                selectionEnd = 4,
                composition = TextRange(1, 4),
            ),
        )
        assertEquals(
            RaftComposerSuggestionTrigger.None,
            detectRaftComposerSuggestionTrigger(
                text = "@f\nr",
                cursor = 4,
                selectionEnd = 4,
                composition = TextRange(1, 4),
            ),
        )
        // Caret or selection starting before @ does not trigger even if composition includes or touches @.
        assertEquals(
            RaftComposerSuggestionTrigger.None,
            detectRaftComposerSuggestionTrigger(
                text = "@f\u2006r",
                cursor = 0,
                selectionEnd = 0,
                composition = TextRange(0, 4),
            ),
        )
        assertEquals(
            RaftComposerSuggestionTrigger.None,
            detectRaftComposerSuggestionTrigger(
                text = "a@f\u2006r",
                cursor = 1,
                selectionEnd = 1,
                composition = TextRange(0, 5),
            ),
        )
        assertEquals(
            RaftComposerSuggestionTrigger.None,
            detectRaftComposerSuggestionTrigger(
                text = "@f\u2006r",
                cursor = 0,
                selectionEnd = 4,
                composition = TextRange(1, 4),
            ),
        )
        assertEquals(
            RaftComposerSuggestionTrigger.None,
            detectRaftComposerSuggestionTrigger(
                text = "@foo@bar",
                cursor = 4,
                selectionEnd = 4,
                composition = TextRange(4, 8),
            ),
        )
        // Outside composition, ordinary whitespace still terminates trigger detection.
        assertEquals(
            RaftComposerSuggestionTrigger.None,
            detectRaftComposerSuggestionTrigger(
                text = "@f r",
                cursor = 4,
                selectionEnd = 4,
                composition = null,
            ),
        )
    }

    @Test
    fun dispatcherReplacesEntireComposingSpanWithoutLeftoverSyllables() {
        val member = RaftComposerMemberItem(displayName = "E40", handle = "E40", memberId = "agent-e40")
        val committed = mutableListOf<RaftComposerSuggestionEdit>()
        val dispatcher =
            ThreadComposerSuggestionSelectionDispatcher(
                initialText = "ping @f\u2006r world",
                initialSelectionStart = 7,
                initialSelectionEnd = 7,
                initialSuggestionsEnabled = true,
                initialComposition = TextRange(6, 9),
            )
        dispatcher.updateComposition(
            suggestionsEnabled = true,
            memberSuggestions = listOf(member),
            channelSuggestions = emptyList(),
            onCommit = committed::add,
        )
        val context =
            requireNotNull(
                threadComposerSuggestionSelectionContext(
                    text = "ping @f\u2006r world",
                    selectionStart = 7,
                    selectionEnd = 7,
                    suggestionsEnabled = true,
                    composition = TextRange(6, 9),
                )
            )

        assertEquals("fr", context.query)
        assertTrue(dispatcher.selectMember(context, member))
        assertEquals(
            listOf(
                RaftComposerSuggestionEdit(
                    text = "ping @E40 world",
                    cursor = 10,
                    mention = member.composerStructuredMentionOrNull(),
                )
            ),
            committed,
        )
    }
}
