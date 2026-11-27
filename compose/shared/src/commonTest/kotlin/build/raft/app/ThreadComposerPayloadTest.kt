package build.raft.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.app.thread.mentionActionDeliveredNotice
import build.raft.app.thread.mentionActionSucceeded
import build.raft.app.thread.threadComposerDraftKey
import build.raft.app.threadComposerDraftKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadComposerPayloadTest {
    @Test
    fun parsesWrappedLaunchPayload() {
        val payload =
            parseThreadComposerPayload(
                payload = """{"seedText":"hello","syncNonce":2,"streamChannelId":"thread-1"}""",
                fallbackPageData = """{"instanceId":"host-1"}""",
                instanceId = "host-1"
            )

        assertEquals("host-1", payload.instanceId)
        assertEquals("hello", payload.seedText)
        assertEquals("Message", payload.placeholder)
        assertEquals(2, payload.syncNonce)
        assertEquals("thread-1", payload.streamChannelId)
        assertNull(payload.statusLabel)
    }

    @Test
    fun rejectsWrappedFullContentPayload() {
        val payload =
            parseThreadComposerPayload(
                payload =
                    """
                    {
                      "seedText": "hello",
                      "placeholder": "Reply",
                      "pendingAttachments": [
                        {"id":"a1","fileName":"stale.txt"}
                      ]
                    }
                    """.trimIndent(),
                fallbackPageData = """{"instanceId":"host-content"}""",
                instanceId = "host-content"
            )

        assertEquals("host-content", payload.instanceId)
        assertEquals("", payload.seedText)
        assertEquals("Message", payload.placeholder)
        assertEquals(emptyList(), payload.pendingAttachments)
        assertEquals("failed", payload.composerMode)
        assertEquals("Composer payload parse failed", payload.statusLabel)
    }

    @Test
    fun staleEditorCommandCannotOverwriteNewerLocalCompositionText() {
        val delayedClear =
            ThreadComposerEditorCommand(
                id = 1,
                kind = ThreadComposerEditorCommandKind.Clear,
                expectedText = "n",
            )

        assertFalse(
            shouldApplyThreadComposerEditorCommand(localText = "ni", localAsTask = false, command = delayedClear)
        )
        assertTrue(
            shouldApplyThreadComposerEditorCommand(localText = "n", localAsTask = false, command = delayedClear)
        )
        assertFalse(
            shouldApplyThreadComposerEditorCommand(
                localText = "n",
                localAsTask = true,
                command = delayedClear.copy(expectedAsTask = false),
            )
        )
    }

    @Test
    fun singleSourceEditorStateSendsVisibleTextAndClearsOnMatchingCommand() {
        val owner = ThreadComposerEditorOwner(seedText = "pleas", seedAsTask = false)
        val session = owner.editorSessionId
        owner.textFieldState.edit {
            replace(0, length, "please")
            selection = TextRange(6)
        }

        val visibleValue = owner.finishCompositionForActionBoundary()
        val request =
            ThreadComposerSendRequest(
                editorSessionId = session,
                value = visibleValue,
                asTask = false,
            )

        assertEquals("please", request.value.text)
        assertEquals(TextRange(6), request.value.selection)
        assertNull(request.value.composition)
        assertEquals(
            ThreadComposerEditorCommandOutcome.Applied,
            owner.apply(
                ThreadComposerEditorCommand(
                    id = 1,
                    kind = ThreadComposerEditorCommandKind.Clear,
                    expectedText = request.value.text,
                    expectedAsTask = request.asTask,
                )
            ),
        )
        assertEquals("", owner.text)
        assertEquals(TextRange.Zero, owner.selection)
    }

    @Test
    fun singleSourceEditorStatePreservesGenuinePostSubmitTypingAgainstStaleClear() {
        val owner = ThreadComposerEditorOwner(seedText = "please", seedAsTask = false)
        val submitted = owner.value
        owner.textFieldState.edit {
            replace(0, length, "next draft")
            selection = TextRange(10)
        }

        assertEquals(
            ThreadComposerEditorCommandOutcome.Stale,
            owner.apply(
                ThreadComposerEditorCommand(
                    id = 1,
                    kind = ThreadComposerEditorCommandKind.Clear,
                    expectedText = submitted.text,
                    expectedAsTask = false,
                )
            ),
        )
        assertEquals("next draft", owner.text)
        assertEquals(TextRange(10), owner.selection)
    }

    @Test
    fun sameTextReplaceCommandPreservesMidSentenceSelection() {
        val owner = ThreadComposerEditorOwner(seedText = "hello world", seedAsTask = false)
        owner.textFieldState.edit { selection = TextRange(5) }

        assertEquals(
            ThreadComposerEditorCommandOutcome.Applied,
            owner.apply(
                ThreadComposerEditorCommand(
                    id = 1,
                    kind = ThreadComposerEditorCommandKind.Replace,
                    text = "hello world",
                    asTask = true,
                    expectedText = "hello world",
                    expectedAsTask = false,
                )
            ),
        )
        assertEquals("hello world", owner.text)
        assertEquals(TextRange(5), owner.selection)
        assertTrue(owner.asTask)
    }

    @Test
    fun programmaticReplacePlacesSelectionAtEndOnceAndPreservesLaterMidSentenceEdits() {
        val owner = ThreadComposerEditorOwner(seedText = "", seedAsTask = false)
        assertEquals(
            ThreadComposerEditorCommandOutcome.Applied,
            owner.apply(
                ThreadComposerEditorCommand(
                    id = 1,
                    kind = ThreadComposerEditorCommandKind.Replace,
                    text = "今天天气",
                    asTask = false,
                    expectedText = "",
                    expectedAsTask = false,
                )
            ),
        )
        assertEquals("今天天气", owner.text)
        assertEquals(TextRange(4), owner.selection)

        // Re-applying the same command id is a no-op and never pulls a mid-sentence cursor back to end.
        owner.textFieldState.edit {
            selection = TextRange(2)
            replace(2, 2, "好")
            selection = TextRange(3)
        }
        assertEquals(
            ThreadComposerEditorCommandOutcome.AlreadyHandled,
            owner.apply(
                ThreadComposerEditorCommand(
                    id = 1,
                    kind = ThreadComposerEditorCommandKind.Replace,
                    text = "今天天气",
                    asTask = false,
                    expectedText = "",
                    expectedAsTask = false,
                )
            ),
        )
        assertEquals("今天好天气", owner.text)
        assertEquals(TextRange(3), owner.selection)
    }

    @Test
    fun sendSnapshotCommitsOpenImeCompositionWithoutDroppingCharacters() {
        // Mid-dictation / mid-CJK composition: the composing characters are part of the text the
        // user sees. The action-boundary read must hand Send that text with the composition
        // finished (Kuikly's finishComposingText barrier), never an open composing region.
        val reader = RaftTextFieldNativeEditingStateReader()
        val bindingOwner = Any()
        val live =
            TextFieldValue(
                text = "hello nihao",
                selection = TextRange(11),
                composition = TextRange(6, 11),
            )
        reader.bind(bindingOwner) { callback ->
            callback(
                RaftTextFieldNativeEditorRead(
                    state = live.toRaftTextInputState().raftWithFinishedComposition(),
                    editorNativeRef = -1,
                )
            )
        }
        var result: RaftTextFieldNativeEditingStateReadResult? = null

        reader.readCurrentValue { result = it }

        val available = result as RaftTextFieldNativeEditingStateReadResult.Available
        assertEquals("hello nihao", available.value.text)
        assertEquals(TextRange(11), available.value.selection)
        assertEquals(null, available.value.composition)
        // Text and selection unchanged, composition cleared: the write-back is a
        // composition-only change, which is what makes Compose restart input and end the IME's
        // composition.
        assertEquals(live.copy(composition = null), available.value)
    }

    @Test
    fun sendKeepsAnOpenKeyboardButNeverRaisesADismissedOne() {
        // Send (keep-only request) with the keyboard put away: no focus grab, no IME.
        assertEquals(
            ThreadComposerSessionFocusRestorePlan(requestFocus = false, showKeyboard = false),
            threadComposerSessionFocusRestorePlan(
                explicitFocusRequest = true,
                editorSessionReplaced = true,
                editorFocused = true,
                keyboardInsetDp = 0f,
                explicitRequestShowsKeyboard = false,
            ),
        )
        // Send with the keyboard open: it stays open across the clear.
        assertEquals(
            ThreadComposerSessionFocusRestorePlan(requestFocus = true, showKeyboard = true),
            threadComposerSessionFocusRestorePlan(
                explicitFocusRequest = true,
                editorSessionReplaced = false,
                editorFocused = true,
                keyboardInsetDp = 280f,
                explicitRequestShowsKeyboard = false,
            ),
        )
        // Deferred system focus (e.g. image edit completion): focus only, never raises the IME.
        assertEquals(
            ThreadComposerSessionFocusRestorePlan(requestFocus = true, showKeyboard = false),
            threadComposerSessionFocusRestorePlan(
                explicitFocusRequest = true,
                editorSessionReplaced = false,
                editorFocused = false,
                keyboardInsetDp = 0f,
                explicitKeyboardMode = ThreadComposerFocusRequestKeyboardMode.FocusOnly,
            ),
        )
        assertEquals(
            ThreadComposerSessionFocusRestorePlan(requestFocus = true, showKeyboard = false),
            threadComposerSessionFocusRestorePlan(
                explicitFocusRequest = true,
                editorSessionReplaced = false,
                editorFocused = false,
                keyboardInsetDp = 280f,
                explicitKeyboardMode = ThreadComposerFocusRequestKeyboardMode.FocusOnly,
            ),
        )
    }

    @Test
    fun failureRestoreTargetsCurrentSessionAndRetiredSessionStaysRejected() {
        val owner = ThreadComposerEditorOwner(seedText = "sent", seedAsTask = true)
        val retiredSession = owner.editorSessionId
        assertEquals(
            ThreadComposerEditorCommandOutcome.Applied,
            owner.apply(
                ThreadComposerEditorCommand(
                    id = 1,
                    kind = ThreadComposerEditorCommandKind.Clear,
                    expectedText = "sent",
                    expectedAsTask = true,
                )
            ),
        )
        val activeSession = owner.editorSessionId
        assertEquals(
            ThreadComposerEditorCommandOutcome.Applied,
            owner.apply(
                ThreadComposerEditorCommand(
                    id = 2,
                    kind = ThreadComposerEditorCommandKind.Replace,
                    text = "sent",
                    asTask = true,
                    expectedText = "",
                    expectedAsTask = false,
                )
            ),
        )

        assertEquals(activeSession, owner.editorSessionId)
        assertEquals("sent", owner.text)
        assertTrue(owner.asTask)
        assertFalse(
            owner.applyProgrammaticText(
                sourceSessionId = retiredSession,
                nextText = "old echo",
                cursor = 8,
            )
        )
        assertEquals("sent", owner.text)
    }

    @Test
    fun mentionTriggerMatchesReactWhenAtFollowsText() {
        val chineseTrigger = detectRaftComposerSuggestionTrigger("你好@")
        assertTrue(chineseTrigger is RaftComposerSuggestionTrigger.MemberTrigger)
        assertEquals("", chineseTrigger.query)
        assertEquals(2, chineseTrigger.triggerStart)

        val latinTrigger = detectRaftComposerSuggestionTrigger("hello@cind")
        assertTrue(latinTrigger is RaftComposerSuggestionTrigger.MemberTrigger)
        assertEquals("cind", latinTrigger.query)
        assertEquals(5, latinTrigger.triggerStart)
    }

    @Test
    fun sendAttachmentIdsExcludeLocalPickedAttachmentsUntilUploadFinishes() {
        val attachments =
            listOf(
                RaftPendingAttachmentItem(
                    id = "picked-123-0",
                    fileName = "local.png",
                    status = "ready"
                ),
                RaftPendingAttachmentItem(
                    id = "remote-attachment-1",
                    fileName = "remote.png",
                    status = "ready"
                ),
                RaftPendingAttachmentItem(
                    id = "remote-attachment-2",
                    fileName = "uploading.png",
                    status = "uploading"
                )
            )

        assertEquals(listOf("remote-attachment-1"), attachments.readyThreadAttachmentIdsForSend())
        assertTrue(attachments.hasBlockingThreadAttachmentUpload())
        assertTrue(attachments.hasUploadingThreadAttachment())
        assertFalse(attachments.hasFailedThreadAttachmentUpload())
    }

    @Test
    fun failedAttachmentMustBeRetriedBeforeComposerSendCanQueue() {
        val attachments =
            listOf(
                RaftPendingAttachmentItem(
                    id = "picked-local-1",
                    fileName = "failed.png",
                    status = "failed",
                    errorCode = "network_error"
                )
            )

        assertTrue(attachments.hasBlockingThreadAttachmentUpload())
        assertFalse(attachments.hasUploadingThreadAttachment())
        assertTrue(attachments.hasFailedThreadAttachmentUpload())
        assertEquals(emptyList(), attachments.readyThreadAttachmentIdsForSend())
    }

    @Test
    fun composerSurfaceUsesStreamChannelForSendTargetAndParentForMentionScope() {
        val surface =
            RaftThreadComposerPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                streamChannelId = "reply-thread-1",
                mentionChannelId = "parent-channel-1",
                parentMessageId = "parent-message-1",
                showAsTaskToggle = false
            ).toThreadComposerSurface(
                channelId = "route-thread-1",
                parentChannelId = "parent-channel-1"
            )

        assertEquals(RaftThreadSurfaceKind.ReplyThread, surface.kind)
        assertEquals("route-thread-1", surface.channelId)
        assertEquals("reply-thread-1", surface.sendChannelId)
        assertEquals("parent-channel-1", surface.mentionScopeChannelId)
        assertEquals("parent-message-1", surface.parentMessageId)
        assertFalse(surface.showTaskButton)
    }

    @Test
    fun pendingReplyComposerSurfaceHasNoSendTargetUntilStreamExists() {
        val surface =
            RaftThreadComposerPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                streamChannelId = null,
                mentionChannelId = "parent-channel-1",
                parentMessageId = "parent-message-1"
            ).toThreadComposerSurface(
                channelId = "",
                parentChannelId = "parent-channel-1"
            )

        assertNull(surface.sendChannelId)
        assertEquals("parent-channel-1", surface.mentionScopeChannelId)
    }

    @Test
    fun composerDraftKeySeparatesReplyThreadFromParentChannelComposer() {
        val channelSurface =
            ThreadComposerSurface(
                kind = RaftThreadSurfaceKind.Channel,
                channelId = "parent-channel-1"
            )
        val replySurface =
            ThreadComposerSurface(
                kind = RaftThreadSurfaceKind.ReplyThread,
                channelId = "parent-channel-1",
                streamChannelId = "reply-thread-1",
                parentChannelId = "parent-channel-1",
                parentMessageId = "parent-message-1"
            )

        assertEquals(
            "send=parent-channel-1|parent=_|mention=parent-channel-1",
            channelSurface.threadComposerDraftKey()
        )
        assertEquals(
            "send=reply-thread-1|parent=parent-message-1|mention=parent-channel-1",
            replySurface.threadComposerDraftKey()
        )
    }

    @Test
    fun pendingMentionActionsExposeActionSupportAndTargetLabel() {
        val action =
            RaftPendingMentionActionItem(
                resolutionId = "res-1",
                targetType = "agent",
                targetHandle = "outside",
                availableActions = listOf("add", "notify_only")
            )

        assertTrue(action.supportsMentionAction("invite"))
        assertTrue(action.supportsMentionAction("notify"))
        assertEquals("@outside", action.targetLabel())
        assertEquals(
            "@outside isn't in #channel. Add them to #channel or send a one-time notify.",
            action.pendingMentionActionDescription("#channel")
        )
        assertEquals(
            "@outside was added to #channel",
            action.mentionActionDeliveredNotice("add", "#channel")
        )
    }

    @Test
    fun mentionActionSuccessClassificationMatchesCanonicalActionStatusPairs() {
        val queued =
            build.raft.app.data.PendingMentionActionExecuteResult(
                resolutionId = "res-1",
                status = "queued"
            )
        val alreadyQueued = queued.copy(reason = "already_queued")
        val delivered = queued.copy(status = "delivered")
        val dropped = queued.copy(status = "dropped", reason = "delivery_unavailable")
        val stale = queued.copy(status = "stale", reason = "no_longer_pending")

        assertTrue(mentionActionSucceeded("notify", queued))
        assertTrue(mentionActionSucceeded("notify", alreadyQueued))
        assertFalse(mentionActionSucceeded("notify", delivered))
        assertTrue(mentionActionSucceeded("add", delivered))
        assertTrue(mentionActionSucceeded("invite", delivered))
        assertFalse(mentionActionSucceeded("add", queued))
        assertFalse(mentionActionSucceeded("notify", dropped))
        assertFalse(mentionActionSucceeded("notify", stale))
        assertFalse(mentionActionSucceeded("notify", null))
    }
}
