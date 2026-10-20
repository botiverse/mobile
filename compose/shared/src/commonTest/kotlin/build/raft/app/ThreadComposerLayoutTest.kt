package build.raft.app

import build.raft.app.data.SettingsDiagnosticsFeedbackDraft
import build.raft.app.data.SharedMembersLoadStatus
import build.raft.app.model.MemberKind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
            KuiklyComposerSuggestionTrigger.MemberTrigger(query = "", triggerStart = 0),
            threadComposerSuggestionTrigger(repaired.text, repaired.selection.start),
        )
    }

    @Test
    fun legacyZeroSelectionRepairsInsertionAndSelectionReplacementAtTheirEditRange() {
        val inserted =
            repairThreadComposerLegacyZeroSelection(
                previous = TextFieldValue("ping world", selection = TextRange(5)),
                incoming = TextFieldValue("ping @world", selection = TextRange.Zero),
            )
        val replaced =
            repairThreadComposerLegacyZeroSelection(
                previous = TextFieldValue("hello middle world", selection = TextRange(6, 12)),
                incoming = TextFieldValue("hello @artin world", selection = TextRange.Zero),
            )

        assertEquals(TextRange(6), inserted.selection)
        assertEquals(TextRange(12), replaced.selection)
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
    fun nativeLegacyRepairUsesLatestAcceptedSelectionInsteadOfRenderSnapshot() {
        val owner = ThreadComposerControlledEditorOwner(seedText = "aa", seedAsTask = false)
        val sessionId = owner.editorSessionId

        assertNotNull(
            owner.acceptNativeEditorValue(
                sourceSessionId = sessionId,
                incoming = TextFieldValue("aa", selection = TextRange.Zero),
            ),
        )
        assertNotNull(
            owner.acceptNativeEditorValue(
                sourceSessionId = sessionId,
                incoming = TextFieldValue("aa", selection = TextRange(2)),
            ),
        )
        val accepted =
            assertNotNull(
                owner.acceptNativeEditorValue(
                    sourceSessionId = sessionId,
                    incoming = TextFieldValue("aaa", selection = TextRange.Zero),
                ),
            )

        assertEquals(TextRange(3), accepted.selection)
        assertSame(accepted, owner.value)

        assertNull(
            owner.acceptNativeEditorValue(
                sourceSessionId = sessionId + 1,
                incoming = TextFieldValue("stale", selection = TextRange.Zero),
            ),
        )
        assertSame(accepted, owner.value)
    }

    @Test
    fun suggestionPortalLeavesTheComposerAndToolbarHitPathUncovered() {
        assertFalse(ThreadComposerSuggestionOutsidePointerMode.installsOutsideDismissBarrier)
        assertTrue(SlockFloatingOverlayOutsidePointerMode.Dismiss.installsOutsideDismissBarrier)
    }

    @Test
    fun suggestionFocusLossFencesStalePortalShowUntilEditingResumes() {
        val owner = ThreadComposerSuggestionPortalOwner()
        val otherOwner = ThreadComposerSuggestionPortalOwner()
        val anchorRef = SlockLiveRootAnchorRef(owner)
        val generationOne = 0L
        val presentationOne =
            ThreadComposerSuggestionPresentation(
                owner = owner,
                generation = generationOne,
                anchorResolver = anchorRef.resolver(SlockPopoverLocalPoint.Exact(Offset.Zero)),
                selectionContext =
                    requireNotNull(
                        threadComposerSuggestionSelectionContext(
                            text = "@",
                            selectionStart = 1,
                            selectionEnd = 1,
                            suggestionsEnabled = true
                        )
                    ),
                contentSize = Size(320f, 240f),
                panelState = ThreadComposerSuggestionPanelState.MemberLoading,
                surface = ThreadComposerSurface(kind = KuiklyThreadSurfaceKind.Channel, channelId = "channel-1"),
                memberSuggestions = emptyList(),
                memberSelectedIndex = 0,
                memberErrorCode = null,
                channelSuggestions = emptyList(),
                channelSelectedIndex = 0,
                channelErrorCode = null,
                agentStatusResolver = EmptySlockAgentStatusResolver,
                onDismiss = {},
                onMemberClick = { _, _ -> },
                onChannelClick = { _, _ -> }
            )
        val portal = ThreadComposerSuggestionPortalState()

        portal.show(presentationOne)
        portal.clear(otherOwner, generationOne)
        assertSame(presentationOne, portal.presentation)

        var focused = true
        dismissThreadComposerSuggestionsForFocusLoss(
            portalState = portal,
            owner = owner,
            generation = generationOne,
            onFocusedChanged = { focused = it }
        )

        assertFalse(focused)
        assertNull(portal.presentation)

        // A final native value callback and an already-composed SideEffect may both arrive after
        // blur. Neither may reactivate or republish the retired generation.
        continueThreadComposerSuggestionsForEditing(
            portalState = portal,
            owner = owner,
            generation = generationOne,
            onFocusedChanged = { focused = it }
        )
        portal.show(presentationOne)
        assertFalse(focused)
        assertNull(portal.presentation)

        val generationTwo =
            resumeThreadComposerSuggestionsForEditing(
                portalState = portal,
                owner = owner,
                generation = generationOne,
                onFocusedChanged = { focused = it }
            )
        val presentationTwo = presentationOne.copy(generation = generationTwo)

        // Even after refocus, a delayed publication or clear from the retired composition cannot
        // replace or remove the newly activated generation.
        portal.show(presentationOne)
        assertNull(portal.presentation)
        portal.show(presentationTwo)
        portal.clear(owner, generationOne)
        portal.suspendForFocusLoss(otherOwner, generationOne)

        assertTrue(focused)
        assertSame(presentationTwo, portal.presentation)

        // Committing a raw token hides the current panel without blurring the native editor.
        // Its next real value change may continue editing, but the old generation stays retired.
        val generationThree =
            hideThreadComposerSuggestionsForEditingContinuation(
                portalState = portal,
                owner = owner,
                generation = generationTwo,
                onFocusedChanged = { focused = it }
            )
        portal.show(presentationTwo)

        assertFalse(focused)
        assertNull(portal.presentation)

        continueThreadComposerSuggestionsForEditing(
            portalState = portal,
            owner = owner,
            generation = generationThree,
            onFocusedChanged = { focused = it }
        )
        val presentationThree = presentationOne.copy(generation = generationThree)
        portal.show(presentationThree)

        assertTrue(focused)
        assertSame(presentationThree, portal.presentation)
    }

    @Test
    fun stableChannelCallbackUsesLatestDraftAfterInitialEmptyComposition() {
        val channel = KuiklyComposerChannelItem(name = "design")
        val committed = mutableListOf<KuiklyComposerSuggestionEdit>()
        val dispatcher =
            ThreadComposerSuggestionSelectionDispatcher(
                initialText = "",
                initialSelectionStart = 0,
                initialSelectionEnd = 0,
                initialSuggestionsEnabled = true
            )
        val stableCallback = dispatcher::selectChannel

        dispatcher.updateDraft(text = "#", selectionStart = 1, selectionEnd = 1)
        dispatcher.updateComposition(
            suggestionsEnabled = true,
            memberSuggestions = emptyList(),
            channelSuggestions = listOf(channel),
            onCommit = committed::add
        )
        val context =
            requireNotNull(
                threadComposerSuggestionSelectionContext(
                    text = "#",
                    selectionStart = 1,
                    selectionEnd = 1,
                    suggestionsEnabled = true
                )
            )

        assertTrue(stableCallback(context, channel))
        assertEquals(listOf(KuiklyComposerSuggestionEdit(text = "#design ", cursor = 8)), committed)
    }

    @Test
    fun stableMemberCallbackUsesLatestDraftAfterInitialEmptyComposition() {
        val member = KuiklyComposerMemberItem(displayName = "Artin", handle = "artin", memberId = "member-1")
        val committed = mutableListOf<KuiklyComposerSuggestionEdit>()
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
        assertEquals(listOf(KuiklyComposerSuggestionEdit(text = "@artin ", cursor = 7)), committed)
    }

    @Test
    fun stalePresentationIsRejectedAfterTextOrRangeChanges() {
        val channel = KuiklyComposerChannelItem(name = "design")
        val committed = mutableListOf<KuiklyComposerSuggestionEdit>()
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
    fun candidateRemovedFromLatestCompositionIsRejectedByIdentity() {
        val member = KuiklyComposerMemberItem(displayName = "Artin", handle = "artin", memberId = "member-1")
        val replacement = KuiklyComposerMemberItem(displayName = "Other", handle = "other", memberId = "member-2")
        val committed = mutableListOf<KuiklyComposerSuggestionEdit>()
        val dispatcher =
            ThreadComposerSuggestionSelectionDispatcher(
                initialText = "@",
                initialSelectionStart = 1,
                initialSelectionEnd = 1,
                initialSuggestionsEnabled = true
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
        dispatcher.updateComposition(
            suggestionsEnabled = true,
            memberSuggestions = listOf(replacement),
            channelSuggestions = emptyList(),
            onCommit = committed::add
        )

        assertFalse(dispatcher.selectMember(context, member))
        assertEquals(emptyList(), committed)
    }

    @Test
    fun threadComposerBottomPaddingIncludesClosedKeyboardSafeArea() {
        assertEquals(38.dp, threadComposerBottomPadding(keyboardInsetDp = 0f, bottomSafeArea = 24.dp))
    }

    @Test
    fun threadComposerBottomPaddingDoesNotDoubleCountSafeAreaWhenKeyboardOpen() {
        assertEquals(134.dp, threadComposerBottomPadding(keyboardInsetDp = 120f, bottomSafeArea = 24.dp))
    }

    @Test
    fun threadComposerBottomPaddingRestoresSafeAreaExcludedByAndroidKeyboardWatcher() {
        assertEquals(
            158.dp,
            threadComposerBottomPadding(
                keyboardInsetDp = 120f,
                bottomSafeArea = 24.dp,
                keyboardInsetExcludesBottomSafeArea = true
            )
        )
    }

    @Test
    fun threadComposerBottomPaddingDoesNotAddAnExtraGapAboveOpenKeyboard() {
        val closedPadding = threadComposerBottomPadding(keyboardInsetDp = 0f, bottomSafeArea = 0.dp)
        val openPadding = threadComposerBottomPadding(keyboardInsetDp = 1f, bottomSafeArea = 0.dp)
        assertEquals(0.dp, openPadding - closedPadding - 1.dp)
    }

    @Test
    fun threadComposerBottomPaddingKeepsSmallLiftWithoutSafeArea() {
        assertEquals(14.dp, threadComposerBottomPadding(keyboardInsetDp = -1f, bottomSafeArea = 0.dp))
    }

    @Test
    fun mentionSuggestionInsertMovesCursorAfterInsertedMention() {
        val edit = applyKuiklyComposerSuggestionEdit(
            text = "ping @ar",
            selectionStart = 8,
            selectionEnd = 8,
            triggerStart = 5,
            replacement = "@artin "
        )

        assertEquals(KuiklyComposerSuggestionEdit(text = "ping @artin ", cursor = 12), edit)
    }

    @Test
    fun mentionSuggestionInsertPreservesSuffixWithoutDoubleSpace() {
        val edit = applyKuiklyComposerSuggestionEdit(
            text = "ping @ar world",
            selectionStart = 8,
            selectionEnd = 8,
            triggerStart = 5,
            replacement = "@artin "
        )

        assertEquals(KuiklyComposerSuggestionEdit(text = "ping @artin world", cursor = 12), edit)
    }

    @Test
    fun suggestionTokenCommitAddsTrailingSpaceAndMovesCursor() {
        val edit = commitKuiklyComposerSuggestionToken(
            text = "ping @ar",
            selectionStart = 8,
            selectionEnd = 8,
            triggerStart = 5
        )

        assertEquals(KuiklyComposerSuggestionEdit(text = "ping @ar ", cursor = 9), edit)
    }

    @Test
    fun suggestionTokenCommitDoesNotDoubleExistingSpace() {
        val edit = commitKuiklyComposerSuggestionToken(
            text = "ping @ar world",
            selectionStart = 8,
            selectionEnd = 8,
            triggerStart = 5
        )

        assertEquals(KuiklyComposerSuggestionEdit(text = "ping @ar world", cursor = 9), edit)
    }

    @Test
    fun directMentionInsertUsesCursorInsteadOfAppending() {
        val edit = applyKuiklyComposerDirectMentionInsert(
            text = "hello  world",
            selectionStart = 6,
            selectionEnd = 6,
            handle = "@artin"
        )

        assertEquals(KuiklyComposerSuggestionEdit(text = "hello @artin world", cursor = 12), edit)
    }

    @Test
    fun directMentionInsertReplacesSelectionAtCursor() {
        val edit = applyKuiklyComposerDirectMentionInsert(
            text = "hello middle world",
            selectionStart = 6,
            selectionEnd = 12,
            handle = "Android Developer"
        )

        assertEquals(KuiklyComposerSuggestionEdit(text = "hello @android-developer world", cursor = 24), edit)
    }

    @Test
    fun directMentionInsertAddsSeparatingSpacesWhenNeeded() {
        val edit = applyKuiklyComposerDirectMentionInsert(
            text = "helloworld",
            selectionStart = 5,
            selectionEnd = 5,
            handle = "artin"
        )

        assertEquals(KuiklyComposerSuggestionEdit(text = "hello @artin world", cursor = 13), edit)
    }

    @Test
    fun memberSuggestionTriggerShowsLoadingPanelBeforeItemsArrive() {
        assertEquals(
            ThreadComposerSuggestionPanelState.MemberLoading,
            threadComposerSuggestionPanelState(
                focused = true,
                trigger = KuiklyComposerSuggestionTrigger.MemberTrigger(query = "", triggerStart = 0),
                memberCount = 0,
                memberStatus = SharedMembersLoadStatus.InFlight,
                channelCount = 0,
                channelStatus = SharedMembersLoadStatus.Loaded
            )
        )
    }

    @Test
    fun memberSuggestionTriggerShowsItemsDuringRefreshWhenCandidatesExist() {
        assertEquals(
            ThreadComposerSuggestionPanelState.MemberItems,
            threadComposerSuggestionPanelState(
                focused = true,
                trigger = KuiklyComposerSuggestionTrigger.MemberTrigger(query = "", triggerStart = 0),
                memberCount = 1,
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
                trigger = KuiklyComposerSuggestionTrigger.MemberTrigger(query = "missing", triggerStart = 0),
                memberCount = 0,
                memberStatus = SharedMembersLoadStatus.Loaded,
                channelCount = 0,
                channelStatus = SharedMembersLoadStatus.Loaded
            )
        )
    }

    @Test
    fun memberSuggestionTriggerShowsEmptyWhenNoRequestIsInFlight() {
        assertEquals(
            ThreadComposerSuggestionPanelState.MemberEmpty,
            threadComposerSuggestionPanelState(
                focused = true,
                trigger = KuiklyComposerSuggestionTrigger.MemberTrigger(query = "", triggerStart = 0),
                memberCount = 0,
                memberStatus = SharedMembersLoadStatus.Idle,
                channelCount = 0,
                channelStatus = SharedMembersLoadStatus.Loaded
            )
        )
    }

    @Test
    fun channelSuggestionTriggerShowsItemsWhenFilteredChannelsExist() {
        assertEquals(
            ThreadComposerSuggestionPanelState.ChannelItems,
            threadComposerSuggestionPanelState(
                focused = true,
                trigger = KuiklyComposerSuggestionTrigger.ChannelTrigger(query = "home", triggerStart = 0),
                memberCount = 0,
                memberStatus = SharedMembersLoadStatus.Loaded,
                channelCount = 1,
                channelStatus = SharedMembersLoadStatus.InFlight
            )
        )
    }

    @Test
    fun channelSuggestionTriggerShowsEmptyWhenNoRequestIsInFlight() {
        assertEquals(
            ThreadComposerSuggestionPanelState.ChannelEmpty,
            threadComposerSuggestionPanelState(
                focused = true,
                trigger = KuiklyComposerSuggestionTrigger.ChannelTrigger(query = "", triggerStart = 0),
                memberCount = 0,
                memberStatus = SharedMembersLoadStatus.Loaded,
                channelCount = 0,
                channelStatus = SharedMembersLoadStatus.Idle
            )
        )
    }

    @Test
    fun renderedSuggestionsKeepRetainedCandidatesWhileRefreshIsInFlight() {
        assertEquals(
            listOf("@artin"),
            threadComposerRenderedSuggestions(
                loadedSuggestions = emptyList(),
                retainedSuggestions = listOf("@artin"),
                fallbackSuggestions = emptyList(),
                status = SharedMembersLoadStatus.InFlight
            )
        )
    }

    @Test
    fun renderedSuggestionsReplaceRetainedCandidatesWhenRefreshCompletesEmpty() {
        assertEquals(
            emptyList(),
            threadComposerRenderedSuggestions(
                loadedSuggestions = emptyList(),
                retainedSuggestions = listOf("@artin"),
                fallbackSuggestions = listOf("@old"),
                status = SharedMembersLoadStatus.Loaded
            )
        )
    }

    @Test
    fun renderedSuggestionsUseFreshCandidatesWhenRefreshReturnsItems() {
        assertEquals(
            listOf("@kmp"),
            threadComposerRenderedSuggestions(
                loadedSuggestions = listOf("@kmp"),
                retainedSuggestions = listOf("@artin"),
                fallbackSuggestions = emptyList(),
                status = SharedMembersLoadStatus.Loaded
            )
        )
    }

    @Test
    fun suggestionPanelContentHeightReservesRoomForMultipleRows() {
        // p-1.5 inset (2 x 6) + 28dp group label + 3 x 38.
        assertEquals(
            154.dp,
            composerSuggestionPanelContentHeight(
                itemCount = 3,
                rowMinHeight = 38.dp
            )
        )
    }

    @Test
    fun suggestionPanelContentHeightCapsAtPanelMaxHeight() {
        assertEquals(
            240.dp,
            composerSuggestionPanelContentHeight(
                itemCount = 7,
                rowMinHeight = 38.dp
            )
        )
    }

    @Test
    fun suggestionPanelContentHeightCapsForLargeServerMemberLists() {
        assertEquals(
            240.dp,
            composerSuggestionPanelContentHeight(
                itemCount = 5_000,
                rowMinHeight = 38.dp
            )
        )
    }

    @Test
    fun suggestionPanelHeightCanOmitMemberHeaderForDirectMessages() {
        // p-1.5 inset (2 x 6) + 3 x 36 (min-h-9).
        assertEquals(
            120.dp,
            composerSuggestionPanelHeight(
                panelState = ThreadComposerSuggestionPanelState.MemberItems,
                memberCount = 3,
                channelCount = 0,
                includeMemberHeader = false
            )
        )
    }

    @Test
    fun suggestionPanelHeightMatchesMemberPanelHeight() {
        assertEquals(
            240.dp,
            composerSuggestionPanelHeight(
                panelState = ThreadComposerSuggestionPanelState.MemberItems,
                memberCount = 6,
                channelCount = 0
            )
        )
    }

    @Test
    fun suggestionPanelHeightIncludesMemberSectionHeaders() {
        assertEquals(
            220.dp,
            composerSuggestionPanelHeight(
                panelState = ThreadComposerSuggestionPanelState.MemberItems,
                memberCount = 3,
                channelCount = 0,
                includeMemberHeader = true,
                memberPanelEntryCount = 5
            )
        )
    }

    @Test
    fun suggestionPanelContentHeightCanOmitHeaderForChannelRows() {
        assertEquals(
            114.dp,
            composerSuggestionPanelContentHeight(
                itemCount = 3,
                rowMinHeight = 34.dp,
                includeHeader = false
            )
        )
    }

    @Test
    fun suggestionRefreshRequestTracksOpenedMentionAndChannelPanels() {
        assertEquals(
            ThreadComposerSuggestionRefreshRequest.Member,
            threadComposerSuggestionRefreshRequest(
                focused = true,
                trigger = KuiklyComposerSuggestionTrigger.MemberTrigger(query = "", triggerStart = 0)
            )
        )
        assertEquals(
            ThreadComposerSuggestionRefreshRequest.Channel,
            threadComposerSuggestionRefreshRequest(
                focused = true,
                trigger = KuiklyComposerSuggestionTrigger.ChannelTrigger(query = "", triggerStart = 0)
            )
        )
    }

    @Test
    fun suggestionRefreshRequestSkipsClosedOrInactivePanels() {
        assertEquals(
            null,
            threadComposerSuggestionRefreshRequest(
                focused = false,
                trigger = KuiklyComposerSuggestionTrigger.MemberTrigger(query = "", triggerStart = 0)
            )
        )
        assertEquals(
            null,
            threadComposerSuggestionRefreshRequest(
                focused = true,
                trigger = KuiklyComposerSuggestionTrigger.None
            )
        )
    }

    @Test
    fun suggestionSearchDebounceDoesNotTurnLoadedSourcesBackIntoLoading() {
        assertEquals(
            SharedMembersLoadStatus.Loaded,
            threadComposerEffectiveSuggestionStatus(
                triggerActive = true,
                rankingReady = false,
                usesImmediateSuggestions = false,
                sourceStatus = SharedMembersLoadStatus.Loaded
            )
        )
    }

    @Test
    fun suggestionSearchKeepsRealSourceLoadingVisible() {
        assertEquals(
            SharedMembersLoadStatus.InFlight,
            threadComposerEffectiveSuggestionStatus(
                triggerActive = true,
                rankingReady = false,
                usesImmediateSuggestions = false,
                sourceStatus = SharedMembersLoadStatus.InFlight
            )
        )
    }

    @Test
    fun enterAcceptsMemberSuggestionBeforeSendWhenItemsAreVisible() {
        assertEquals(
            ThreadComposerEnterAction.AcceptMemberSuggestion,
            threadComposerEnterAction(
                trigger = KuiklyComposerSuggestionTrigger.MemberTrigger(query = "ar", triggerStart = 5),
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
    fun multilineComposerUsesNativeReturnKeyWhenImeActionIsUnowned() {
        assertEquals(ImeAction.Default, threadComposerImeAction(composerUsesImeAction = false))
        assertEquals(ImeAction.Send, threadComposerImeAction(composerUsesImeAction = true))
    }

    @Test
    fun firstMemberSuggestionCandidateMatchesVisualGrouping() {
        val outside =
            KuiklyComposerMemberItem(
                displayName = "Outside",
                handle = "outside",
                inMentionChannel = false
            )
        val inside =
            KuiklyComposerMemberItem(
                displayName = "Inside",
                handle = "inside",
                inMentionChannel = true
            )
        val inThread =
            KuiklyComposerMemberItem(
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
    fun suggestionSelectedIndexCoercesToVisibleItemRange() {
        assertEquals(0, threadComposerSuggestionSelectedIndex(selectedIndex = 3, itemCount = 0))
        assertEquals(0, threadComposerSuggestionSelectedIndex(selectedIndex = -1, itemCount = 3))
        assertEquals(2, threadComposerSuggestionSelectedIndex(selectedIndex = 4, itemCount = 3))
    }

    @Test
    fun suggestionSelectedIndexMovesWithWraparound() {
        assertEquals(
            1,
            threadComposerSuggestionSelectedIndexAfterMove(
                selectedIndex = 0,
                itemCount = 3,
                move = ThreadComposerSuggestionSelectionMove.Down
            )
        )
        assertEquals(
            0,
            threadComposerSuggestionSelectedIndexAfterMove(
                selectedIndex = 1,
                itemCount = 3,
                move = ThreadComposerSuggestionSelectionMove.Up
            )
        )
        assertEquals(
            2,
            threadComposerSuggestionSelectedIndexAfterMove(
                selectedIndex = 0,
                itemCount = 3,
                move = ThreadComposerSuggestionSelectionMove.Up
            )
        )
        assertEquals(
            0,
            threadComposerSuggestionSelectedIndexAfterMove(
                selectedIndex = 2,
                itemCount = 3,
                move = ThreadComposerSuggestionSelectionMove.Down
            )
        )
    }

    @Test
    fun selectedMemberSuggestionCandidateSkipsSectionHeader() {
        val outside =
            KuiklyComposerMemberItem(
                displayName = "Outside",
                handle = "outside",
                inMentionChannel = false
            )
        val insideOne =
            KuiklyComposerMemberItem(
                displayName = "Inside One",
                handle = "inside-one",
                inMentionChannel = true
            )
        val insideTwo =
            KuiklyComposerMemberItem(
                displayName = "Inside Two",
                handle = "inside-two",
                inMentionChannel = true
            )
        val threadParticipant =
            KuiklyComposerMemberItem(
                displayName = "Thread Participant",
                handle = "thread-participant",
                inMentionChannel = false,
                inThread = true
            )
        val suggestions = listOf(outside, insideOne, threadParticipant, insideTwo)

        assertEquals(threadParticipant, suggestions.composerMemberSuggestionCandidateAt(0))
        assertEquals(insideOne, suggestions.composerMemberSuggestionCandidateAt(1))
        assertEquals(insideTwo, suggestions.composerMemberSuggestionCandidateAt(2))
        assertEquals(outside, suggestions.composerMemberSuggestionCandidateAt(3))
        assertEquals(outside, suggestions.composerMemberSuggestionCandidateAt(99))
    }

    @Test
    fun memberSuggestionGroupsDeduplicateThreadParticipantsFromChannelSections() {
        val threadCopy =
            KuiklyComposerMemberItem(
                displayName = "Cindy",
                handle = "cindy",
                memberId = "u-cindy",
                inMentionChannel = true,
                inThread = true
            )
        val channelCopy =
            KuiklyComposerMemberItem(
                displayName = "Cindy",
                handle = "cindy",
                memberId = "u-cindy",
                inMentionChannel = true
            )
        val outside =
            KuiklyComposerMemberItem(
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
            KuiklyComposerMemberItem(
                displayName = "Builder",
                handle = "builder",
                memberId = "local-agent",
                kind = MemberKind.Agent,
                inMentionChannel = true
            )
        val remoteAgent =
            KuiklyComposerMemberItem(
                displayName = "Builder",
                handle = "builder",
                memberId = "remote-agent",
                kind = MemberKind.Agent,
                inMentionChannel = true
            )
        val humanWithCollidingId =
            KuiklyComposerMemberItem(
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
    fun selectedMemberSuggestionCandidateCanUseFlatDirectMessageOrder() {
        val outside =
            KuiklyComposerMemberItem(
                displayName = "Outside",
                handle = "outside",
                inMentionChannel = false
            )
        val inside =
            KuiklyComposerMemberItem(
                displayName = "Inside",
                handle = "inside",
                inMentionChannel = true
            )
        val suggestions = listOf(outside, inside)

        assertEquals(outside, suggestions.composerMemberSuggestionCandidateAt(0, groupByMentionChannel = false))
        assertEquals(inside, suggestions.composerMemberSuggestionCandidateAt(1, groupByMentionChannel = false))
    }

    @Test
    fun selectedChannelSuggestionCandidateUsesClampedIndex() {
        val channels =
            listOf(
                KuiklyComposerChannelItem(name = "general"),
                KuiklyComposerChannelItem(name = "design")
            )

        val selectedIndex = threadComposerSuggestionSelectedIndex(selectedIndex = 4, itemCount = channels.size)

        assertEquals(KuiklyComposerChannelItem(name = "design"), channels.getOrNull(selectedIndex))
    }

    @Test
    fun enterAcceptsChannelSuggestionBeforeSendWhenItemsAreVisible() {
        assertEquals(
            ThreadComposerEnterAction.AcceptChannelSuggestion,
            threadComposerEnterAction(
                trigger = KuiklyComposerSuggestionTrigger.ChannelTrigger(query = "des", triggerStart = 5),
                panelState = ThreadComposerSuggestionPanelState.ChannelItems,
                memberCount = 0,
                channelCount = 1,
                canSend = true,
                isImeComposing = false
            )
        )
    }

    // Updated per the note that used to sit here: device measurement did show the raise causes
    // harm (HuaTuo, cell 4c — the stale Send survives the panel closing and swallows Enter), so
    // newline mode no longer claims the action and this assertion flipped as that note predicted.
    @Test
    fun visibleChannelCandidateLeavesNewlineModeAlone() {
        assertEquals(
            false,
            threadComposerUsesImeAction(
                composerSendKeyEnabled = false,
                panelState = ThreadComposerSuggestionPanelState.ChannelItems,
                memberCount = 0,
                channelCount = 1
            )
        )
    }

    @Test
    fun newlinePreferenceRemainsWhenSuggestionPanelHasNoCandidate() {
        val inactivePanels =
            listOf(
                ThreadComposerSuggestionPanelState.Hidden,
                ThreadComposerSuggestionPanelState.MemberLoading,
                ThreadComposerSuggestionPanelState.MemberEmpty,
                ThreadComposerSuggestionPanelState.MemberError,
                ThreadComposerSuggestionPanelState.ChannelLoading,
                ThreadComposerSuggestionPanelState.ChannelEmpty,
                ThreadComposerSuggestionPanelState.ChannelError
            )

        inactivePanels.forEach { panelState ->
            assertEquals(
                false,
                threadComposerUsesImeAction(
                    composerSendKeyEnabled = false,
                    panelState = panelState,
                    memberCount = 0,
                    channelCount = 0
                ),
                "panelState=$panelState"
            )
        }
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
    fun enterCommitsOpenLoadingSuggestionTokenInsteadOfSending() {
        assertEquals(
            ThreadComposerEnterAction.CommitSuggestionToken,
            threadComposerEnterAction(
                trigger = KuiklyComposerSuggestionTrigger.MemberTrigger(query = "ar", triggerStart = 5),
                panelState = ThreadComposerSuggestionPanelState.MemberLoading,
                memberCount = 0,
                channelCount = 0,
                canSend = true,
                isImeComposing = false
            )
        )
    }

    @Test
    fun enterSendsWhenNoSuggestionTriggerIsActive() {
        assertEquals(
            ThreadComposerEnterAction.Send,
            threadComposerEnterAction(
                trigger = KuiklyComposerSuggestionTrigger.None,
                panelState = ThreadComposerSuggestionPanelState.Hidden,
                memberCount = 0,
                channelCount = 0,
                canSend = true,
                isImeComposing = false
            )
        )
    }

    @Test
    fun enterDefersWhileImeCompositionIsActive() {
        assertEquals(
            ThreadComposerEnterAction.Noop,
            threadComposerEnterAction(
                trigger = KuiklyComposerSuggestionTrigger.MemberTrigger(query = "pin", triggerStart = 0),
                panelState = ThreadComposerSuggestionPanelState.MemberItems,
                memberCount = 1,
                channelCount = 0,
                canSend = true,
                isImeComposing = true
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
                trigger = KuiklyComposerSuggestionTrigger.None,
                panelState = ThreadComposerSuggestionPanelState.Hidden,
                memberCount = 0,
                channelCount = 0,
                canSend = true,
                isImeComposing = threadComposerEnterDeferredByComposition(false, TextRange(0, 5)),
            )
        )
    }

    @Test
    fun composerAttachmentRowsUseReactImageAndFileWidths() {
        val rows =
            composerAttachmentRows(
                listOf(
                    KuiklyPendingAttachmentItem(
                        id = "image",
                        fileName = "composer-preview.png",
                        mimeType = "image/png",
                        previewUri = "data:image/png;base64,"
                    ),
                    KuiklyPendingAttachmentItem(
                        id = "report",
                        fileName = "release-notes.pdf",
                        mimeType = "application/pdf"
                    ),
                    KuiklyPendingAttachmentItem(
                        id = "logs",
                        fileName = "android-smoke-log.zip",
                        mimeType = "application/zip"
                    ),
                    KuiklyPendingAttachmentItem(
                        id = "trace",
                        fileName = "oversized-trace.jsonl",
                        mimeType = "application/json"
                    )
                )
            )

        assertEquals(listOf(listOf("image", "report"), listOf("logs", "trace")), rows.map { row -> row.map { it.id } })
        assertEquals(64, rows.first().first().composerAttachmentPreviewWidthDp())
        assertEquals(128, rows.first()[1].composerAttachmentPreviewWidthDp())
    }

    @Test
    fun diagnosticsFeedbackComposerMessageDoesNotInlineDiagnosticsText() {
        val draft =
            SettingsDiagnosticsFeedbackDraft(
                message =
                    """
                    ## Diagnostics

                    App, device, server, and recent logs should stay in the zip attachment.
                    """.trimIndent(),
                fileName = "slock-feedback.zip",
                mimeType = "application/zip",
                sizeBytes = 12L,
                bytes = byteArrayOf(1, 2, 3)
            )

        assertEquals(
            "Diagnostics zip attached.\n\n<!-- Describe what happened here. -->",
            draft.diagnosticsComposerMessage()
        )
        assertEquals(
            "已附加诊断压缩包。\n\n<!-- 请在这里描述发生了什么。 -->",
            draft.diagnosticsComposerMessage("已附加诊断压缩包。\n\n<!-- 请在这里描述发生了什么。 -->")
        )
    }
}
