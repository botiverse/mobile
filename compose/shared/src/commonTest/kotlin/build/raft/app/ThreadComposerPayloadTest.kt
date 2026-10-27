package build.raft.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.app.compat.ui.TextInputState
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertSame
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
    fun topLevelPageDataUsesLaunchFieldsOnlyWhenPayloadWrapperIsMissing() {
        val payload =
            parseThreadComposerPayload(
                payload = "",
                fallbackPageData =
                    """
                    {
                      "instanceId": "host-2",
                      "seedText": "launch draft",
                      "seedAsTask": true,
                      "streamChannelId": "thread-2",
                      "appId": 1
                    }
                    """.trimIndent(),
                instanceId = "host-2"
            )

        assertEquals("host-2", payload.instanceId)
        assertEquals("launch draft", payload.seedText)
        assertTrue(payload.seedAsTask)
        assertEquals("thread-2", payload.streamChannelId)
        assertEquals(true, payload.showAsTaskToggle)
        assertEquals("Message", payload.placeholder)
        assertEquals(emptyList(), payload.pendingAttachments)
        assertEquals(emptyList(), payload.memberSuggestions)
        assertEquals(emptyList(), payload.channelSuggestions)
        assertEquals(emptyList(), payload.hookLabels)
        assertEquals("idle", payload.composerMode)
        assertNull(payload.statusLabel)
    }

    @Test
    fun rejectsTopLevelFullContentPayload() {
        val payload =
            parseThreadComposerPayload(
                payload = "",
                fallbackPageData =
                    """
                    {
                      "instanceId": "host-top-level-content",
                      "seedText": "launch draft",
                      "showAsTaskToggle": false,
                      "placeholder": "Stale placeholder",
                      "memberSuggestions": [
                        {"displayName":"Stale","handle":"stale"}
                      ],
                      "composerMode": "sending"
                    }
                    """.trimIndent(),
                instanceId = "host-top-level-content"
            )

        assertEquals("host-top-level-content", payload.instanceId)
        assertEquals("", payload.seedText)
        assertEquals("Message", payload.placeholder)
        assertEquals(emptyList(), payload.memberSuggestions)
        assertEquals("failed", payload.composerMode)
        assertEquals("Composer payload parse failed", payload.statusLabel)
    }

    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun composerLaunchWireSchemaExcludesContentFields() {
        val descriptor = WireThreadComposerLaunchPayload.serializer().descriptor
        val names = (0 until descriptor.elementsCount)
            .map { index -> descriptor.getElementName(index) }
            .toSet()

        listOf(
            "placeholder",
            "enabled",
            "attachmentActionsEnabled",
            "showAsTaskToggle",
            "composerMode",
            "statusLabel",
            "pendingAttachments",
            "memberSuggestions",
            "channelSuggestions",
            "hookLabels"
        ).forEach { forbidden ->
            assertFalse(forbidden in names, "$forbidden must stay outside compose_thread_composer launch pageData")
        }
    }

    @Test
    fun treatsInitialEmptyPageDataAsIdleComposer() {
        val payload =
            parseThreadComposerPayload(
                payload = "",
                fallbackPageData = """{"instanceId":"host-3","appId":1}""",
                instanceId = "host-3"
            )

        assertEquals("host-3", payload.instanceId)
        assertEquals("idle", payload.composerMode)
        assertNull(payload.statusLabel)
    }

    @Test
    fun malformedWrappedPayloadStillShowsParseFailure() {
        val payload =
            parseThreadComposerPayload(
                payload = """{"seedText":""",
                fallbackPageData = """{"instanceId":"host-4"}""",
                instanceId = "host-4"
            )

        assertEquals("host-4", payload.instanceId)
        assertEquals("failed", payload.composerMode)
        assertEquals("Composer payload parse failed", payload.statusLabel)
    }

    @Test
    fun composerFlowStateSeparatesLocalTypingFromHostPayloadSync() {
        val initial =
            SharedThreadComposerFlowState.initial(
                SharedThreadComposerSnapshot(value = "hello", asTask = false)
            )

        val typed = initial.applyLocalChange("hello world")
        assertEquals("hello world", typed.liveDraftText)
        assertEquals("hello", typed.payloadTextSeed)
        assertEquals(0, typed.syncNonce)

        val unchangedHost = typed.applyHostSnapshot(SharedThreadComposerSnapshot(value = "hello", asTask = false))
        assertEquals(typed, unchangedHost)

        val submittedHost = typed.applyHostSnapshot(SharedThreadComposerSnapshot(value = "", asTask = false))
        assertEquals("", submittedHost.liveDraftText)
        assertEquals("", submittedHost.payloadTextSeed)
        assertEquals(1, submittedHost.syncNonce)
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
    fun nativeSendSnapshotUsesVisibleTextAndDropsItsDelayedEchoBeforeClear() {
        val owner = ThreadComposerControlledEditorOwner(seedText = "pleas", seedAsTask = false)
        val session = owner.editorSessionId
        val visibleNativeValue = TextFieldValue("please", selection = TextRange(6))
        val fence = ThreadComposerSubmittedEchoFence()

        assertTrue(owner.acceptEditorValue(session, visibleNativeValue))
        val request =
            ThreadComposerSendRequest(
                editorSessionId = session,
                value = visibleNativeValue,
                asTask = false,
            )
        fence.begin(request)
        fence.finishAdmission(ThreadComposerSendAdmission.Started)

        assertEquals("please", request.value.text)
        assertTrue(fence.shouldIgnore(session, visibleNativeValue.copy()))
        assertFalse(fence.shouldIgnore(session, visibleNativeValue.copy()))
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
        assertEquals("", owner.value.text)
    }

    @Test
    fun nativeSendSnapshotStillPreservesGenuinePostSubmitTyping() {
        val owner = ThreadComposerControlledEditorOwner(seedText = "please", seedAsTask = false)
        val session = owner.editorSessionId
        val submitted = TextFieldValue("please", selection = TextRange(6))
        val newer = TextFieldValue("next draft", selection = TextRange(10))
        val fence = ThreadComposerSubmittedEchoFence()
        val request = ThreadComposerSendRequest(session, submitted, asTask = false)

        fence.begin(request)
        fence.finishAdmission(ThreadComposerSendAdmission.Started)
        assertFalse(fence.shouldIgnore(session, newer))
        assertTrue(owner.acceptEditorValue(session, newer))
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
        assertSame(newer, owner.value)
    }

    @Test
    fun rejectedOrDeferredSendDoesNotFenceLaterEditorCallbacks() {
        listOf(ThreadComposerSendAdmission.Rejected, ThreadComposerSendAdmission.Deferred).forEach { admission ->
            val fence = ThreadComposerSubmittedEchoFence()
            val value = TextFieldValue("please", selection = TextRange(6))
            fence.begin(ThreadComposerSendRequest(1L, value, asTask = false))

            fence.finishAdmission(admission)

            assertFalse(fence.shouldIgnore(1L, value.copy()), "admission=$admission")
        }
    }

    @Test
    fun submittedEchoFenceRetiresWhenClearReplacesTheEditorSession() {
        val fence = ThreadComposerSubmittedEchoFence()
        val submitted = TextFieldValue("please", selection = TextRange(6))
        fence.begin(ThreadComposerSendRequest(1L, submitted, asTask = false))
        fence.finishAdmission(ThreadComposerSendAdmission.Started)

        assertFalse(fence.shouldIgnore(2L, submitted.copy()))
        assertFalse(fence.shouldIgnore(1L, submitted.copy()))
    }

    @Test
    fun nativeEditingStateReaderReturnsExactBoundedEditingStateAndFailsClosedAfterUnbind() {
        val reader = RaftTextFieldNativeEditingStateReader()
        val bindingOwner = Any()
        var result: RaftTextFieldNativeEditingStateReadResult? = null
        reader.bind(bindingOwner) { callback ->
            callback(
                RaftTextFieldNativeEditorRead(
                    state =
                        TextInputState(
                            text = "please",
                            selectionStart = 99,
                            selectionEnd = 99,
                            compositionStart = 2,
                            compositionEnd = 99,
                        ),
                    editorNativeRef = 7,
                )
            )
        }

        reader.readCurrentValue { result = it }

        val available = result as RaftTextFieldNativeEditingStateReadResult.Available
        assertEquals("please", available.value.text)
        assertEquals(TextRange(6), available.value.selection)
        assertEquals(TextRange(2, 6), available.value.composition)

        reader.unbind(bindingOwner)
        result = null
        reader.readCurrentValue { result = it }
        assertSame(RaftTextFieldNativeEditingStateReadResult.Unavailable, result)
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
    fun finishedCompositionLeavesCommittedStateUntouched() {
        val committed = TextInputState(text = "done", selectionStart = 4, selectionEnd = 4)
        assertEquals(committed, committed.raftWithFinishedComposition())
    }

    @Test
    fun nativeEditingStateReaderKeepsInFlightReadAcrossSameOwnerRefRefresh() {
        val reader = RaftTextFieldNativeEditingStateReader()
        val bindingOwner = Any()
        var pendingNativeCallback: ((RaftTextFieldNativeEditorRead) -> Unit)? = null
        var result: RaftTextFieldNativeEditingStateReadResult? = null
        reader.bind(bindingOwner) { callback -> pendingNativeCallback = callback }
        reader.readCurrentValue { result = it }

        reader.bind(bindingOwner) { callback -> callback(RaftTextFieldNativeEditorRead(null)) }
        pendingNativeCallback?.invoke(
            RaftTextFieldNativeEditorRead(
                state =
                    TextInputState(
                        text = "please",
                        selectionStart = 6,
                        selectionEnd = 6,
                        compositionStart = TextInputState.NO_COMPOSITION,
                        compositionEnd = TextInputState.NO_COMPOSITION,
                    ),
                editorNativeRef = 7,
            )
        )

        val available = result as RaftTextFieldNativeEditingStateReadResult.Available
        assertEquals("please", available.value.text)
    }

    @Test
    fun sendSnapshotGateUnlocksOnTimeoutAndRejectsItsLateCallbackDuringRetry() {
        val gate = ThreadComposerSendSnapshotGate()
        val timedOut = gate.begin()
        assertTrue(gate.isPending)
        assertTrue(gate.finish(timedOut))
        assertFalse(gate.isPending)

        val retry = gate.begin()
        assertFalse(gate.finish(timedOut))
        assertSame(retry, gate.pendingToken)
        assertTrue(gate.finish(retry))
        assertFalse(gate.isPending)
    }

    @Test
    fun controlledEditorOwnerReturnsExactNativeValueOnNextRender() {
        val owner = ThreadComposerControlledEditorOwner(seedText = "n", seedAsTask = false)
        val composing =
            TextFieldValue(
                text = "ni",
                selection = TextRange(2),
                composition = TextRange(0, 2),
            )

        owner.acceptEditorValue(owner.editorSessionId, composing)

        assertSame(composing, owner.value)
        assertEquals(TextRange(0, 2), owner.value.composition)

        val equalButNewNativeValue = composing.copy()
        owner.acceptEditorValue(owner.editorSessionId, equalButNewNativeValue)
        assertSame(equalButNewNativeValue, owner.value)
    }

    @Test
    fun controlledEditorOwnerRejectsDelayedReplaceAfterTextOrTaskAdvances() {
        val owner = ThreadComposerControlledEditorOwner(seedText = "n", seedAsTask = false)
        owner.acceptEditorValue(
            owner.editorSessionId,
            TextFieldValue("ni", selection = TextRange(2), composition = TextRange(0, 2)),
        )
        val delayed =
            ThreadComposerEditorCommand(
                id = 1,
                kind = ThreadComposerEditorCommandKind.Replace,
                text = "restored",
                expectedText = "n",
                expectedAsTask = false,
            )

        assertEquals(ThreadComposerEditorCommandOutcome.Stale, owner.apply(delayed))
        assertEquals("ni", owner.value.text)
        assertEquals(ThreadComposerEditorCommandOutcome.AlreadyHandled, owner.apply(delayed))

        val taskOwner = ThreadComposerControlledEditorOwner(seedText = "n", seedAsTask = false)
        taskOwner.acceptAsTask(taskOwner.editorSessionId, true)
        assertEquals(ThreadComposerEditorCommandOutcome.Stale, taskOwner.apply(delayed))
        assertEquals("n", taskOwner.value.text)
        assertTrue(taskOwner.asTask)
    }

    @Test
    fun controlledEditorOwnerClearIsAuthoritativeAndInstanceScoped() {
        val owner = ThreadComposerControlledEditorOwner(seedText = "draft", seedAsTask = true)
        val clear = ThreadComposerEditorCommand(id = 4, kind = ThreadComposerEditorCommandKind.Clear)

        assertEquals(ThreadComposerEditorCommandOutcome.Applied, owner.apply(clear))
        assertEquals("", owner.value.text)
        assertFalse(owner.asTask)
        assertEquals(4, owner.handledCommandId)
        assertEquals(ThreadComposerEditorCommandOutcome.AlreadyHandled, owner.apply(clear))

        val nextInstance = ThreadComposerControlledEditorOwner(seedText = "fresh", seedAsTask = false)
        assertEquals("fresh", nextInstance.value.text)
        assertEquals(0, nextInstance.handledCommandId)
    }

    @Test
    fun clearRetiresNativeSessionWithoutDroppingSameTextFromNewSession() {
        val owner = ThreadComposerControlledEditorOwner(seedText = "sent", seedAsTask = true)
        val retiredSession = owner.editorSessionId
        val delayedPreClearState =
            TextFieldValue(
                text = "sent",
                selection = TextRange(4),
                composition = TextRange(0, 4),
            )
        val clear =
            ThreadComposerEditorCommand(
                id = 1,
                kind = ThreadComposerEditorCommandKind.Clear,
                expectedText = "sent",
                expectedAsTask = true,
            )

        assertEquals(ThreadComposerEditorCommandOutcome.Applied, owner.apply(clear))
        val activeSession = owner.editorSessionId
        assertTrue(activeSession > retiredSession)
        assertFalse(owner.acceptEditorValue(retiredSession, delayedPreClearState))
        assertEquals("", owner.value.text)
        assertEquals(null, owner.value.composition)

        val sameTextRetyped = delayedPreClearState.copy()
        assertTrue(owner.acceptEditorValue(activeSession, sameTextRetyped))
        assertSame(sameTextRetyped, owner.value)
        assertEquals(TextRange(0, 4), owner.value.composition)
    }

    @Test
    fun retiredSessionCannotBlurOrToggleTask() {
        val owner = ThreadComposerControlledEditorOwner(seedText = "sent", seedAsTask = true)
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
        var focused = true
        var imeVisible = true

        assertFalse(
            acceptThreadComposerEditorSessionUiEvent(retiredSession, owner::isActiveSession) {
                focused = false
                imeVisible = false
            }
        )
        assertFalse(owner.acceptAsTask(retiredSession, true))
        assertTrue(focused)
        assertTrue(imeVisible)
        assertFalse(owner.asTask)
        assertTrue(owner.acceptAsTask(activeSession, true))
        assertTrue(owner.asTask)
    }

    @Test
    fun keyboardGeometryRemainsHostOwnedAcrossEditorSessionRetirement() {
        val owner = ThreadComposerControlledEditorOwner(seedText = "sent", seedAsTask = false)
        val publishedInsets = mutableListOf<Float>()
        var keyboardInset =
            dispatchThreadComposerHostKeyboardInset(280f, publishedInsets::add)

        assertEquals(
            ThreadComposerEditorCommandOutcome.Applied,
            owner.apply(
                ThreadComposerEditorCommand(
                    id = 1,
                    kind = ThreadComposerEditorCommandKind.Clear,
                    expectedText = "sent",
                    expectedAsTask = false,
                )
            ),
        )
        keyboardInset = dispatchThreadComposerHostKeyboardInset(0f, publishedInsets::add)

        assertEquals(0f, keyboardInset)
        assertEquals(listOf(280f, 0f), publishedInsets)
    }

    @Test
    fun sessionRetirementPreservesVisibleImeButNeverReopensUserDismissedIme() {
        assertEquals(
            ThreadComposerSessionFocusRestorePlan(requestFocus = true, showKeyboard = true),
            threadComposerSessionFocusRestorePlan(
                explicitFocusRequest = false,
                editorSessionReplaced = true,
                editorFocused = true,
                keyboardInsetDp = 280f,
            ),
        )
        assertEquals(
            ThreadComposerSessionFocusRestorePlan(requestFocus = false, showKeyboard = false),
            threadComposerSessionFocusRestorePlan(
                explicitFocusRequest = false,
                editorSessionReplaced = true,
                editorFocused = true,
                keyboardInsetDp = 0f,
            ),
        )
        assertEquals(
            ThreadComposerSessionFocusRestorePlan(requestFocus = true, showKeyboard = true),
            threadComposerSessionFocusRestorePlan(
                explicitFocusRequest = true,
                editorSessionReplaced = false,
                editorFocused = false,
                keyboardInsetDp = 0f,
            ),
        )
    }

    @Test
    fun staleClearDoesNotRetireSessionOrOverwriteFullEditingState() {
        val owner = ThreadComposerControlledEditorOwner(seedText = "sent", seedAsTask = false)
        val session = owner.editorSessionId
        val newer =
            TextFieldValue(
                text = "sent now",
                selection = TextRange(8),
                composition = TextRange(5, 8),
            )
        owner.acceptEditorValue(session, newer)

        assertEquals(
            ThreadComposerEditorCommandOutcome.Stale,
            owner.apply(
                ThreadComposerEditorCommand(
                    id = 1,
                    kind = ThreadComposerEditorCommandKind.Clear,
                    expectedText = "sent",
                    expectedAsTask = false,
                )
            ),
        )
        assertEquals(session, owner.editorSessionId)
        assertSame(newer, owner.value)
        assertEquals(TextRange(5, 8), owner.value.composition)
    }

    @Test
    fun failureRestoreTargetsCurrentSessionAndRetiredSessionStaysRejected() {
        val owner = ThreadComposerControlledEditorOwner(seedText = "sent", seedAsTask = true)
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
        assertEquals("sent", owner.value.text)
        assertTrue(owner.asTask)
        assertFalse(
            owner.acceptEditorValue(
                retiredSession,
                TextFieldValue("old echo", selection = TextRange(8)),
            )
        )
        assertEquals("sent", owner.value.text)
    }

    @Test
    fun buildsThreadSurfaceComposerPayloadFromTypedProps() {
        val payload =
            buildThreadSurfaceComposerPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                displayName = "Threads",
                fallbackId = "fallback-1",
                enabled = false,
                seedText = "draft",
                seedAsTask = true,
                syncNonce = 3,
                focusRequestNonce = 5,
                composerMode = "sending",
                statusLabel = "Sending",
                mentionChannelId = "channel-1",
                parentMessageId = "message-1",
                streamChannelId = "thread-1"
            )

        assertEquals("draft", payload.seedText)
        assertTrue(payload.seedAsTask)
        assertEquals(3, payload.syncNonce)
        assertEquals(5, payload.focusRequestNonce)
        assertFalse(payload.enabled)
        assertEquals("sending", payload.composerMode)
        assertEquals("Sending", payload.statusLabel)
        assertEquals(RaftThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("channel-1", payload.mentionChannelId)
        assertEquals("message-1", payload.parentMessageId)
        assertEquals("thread-1", payload.streamChannelId)
        assertEquals("Reply in thread", payload.placeholder)
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
    fun channelTriggerMatchesReactWhenHashFollowsText() {
        val trigger = detectRaftComposerSuggestionTrigger("发到#product")

        assertTrue(trigger is RaftComposerSuggestionTrigger.ChannelTrigger)
        assertEquals("product", trigger.query)
        assertEquals(2, trigger.triggerStart)
    }

    @Test
    fun composerCanDisableTypedMemberAndChannelSuggestionTriggers() {
        assertEquals(
            RaftComposerSuggestionTrigger.None,
            threadComposerSuggestionTrigger("feedback @owner", suggestionsEnabled = false)
        )
        assertEquals(
            RaftComposerSuggestionTrigger.None,
            threadComposerSuggestionTrigger("feedback #support", suggestionsEnabled = false)
        )
        assertTrue(
            threadComposerSuggestionTrigger("feedback @owner", suggestionsEnabled = true) is
                RaftComposerSuggestionTrigger.MemberTrigger
        )
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
    fun readyUploadedAttachmentsDoNotBlockSend() {
        val attachments =
            listOf(
                RaftPendingAttachmentItem(
                    id = "remote-attachment-1",
                    fileName = "remote.png",
                    status = "ready"
                )
            )

        assertEquals(listOf("remote-attachment-1"), attachments.readyThreadAttachmentIdsForSend())
        assertFalse(attachments.hasBlockingThreadAttachmentUpload())
        assertFalse(attachments.hasUploadingThreadAttachment())
    }

    @Test
    fun composerPreviewOpensEveryReadyRemoteAttachmentAndRejectsUnreadyOrLocalItems() {
        val readyVideo =
            RaftPendingAttachmentItem(
                id = "remote-video-1",
                fileName = "clip.mp4",
                mimeType = "video/mp4",
                status = "ready"
            )
        val readyImageWithoutLocalThumbnail =
            RaftPendingAttachmentItem(
                id = "remote-image-1",
                fileName = "photo.png",
                mimeType = "image/png",
                previewUri = null,
                status = "ready"
            )

        assertTrue(readyVideo.canOpenComposerAttachmentPreview(composerEnabled = true))
        assertTrue(readyImageWithoutLocalThumbnail.canOpenComposerAttachmentPreview(composerEnabled = true))
        assertFalse(readyVideo.canOpenComposerAttachmentPreview(composerEnabled = false))
        assertFalse(readyVideo.copy(status = "uploading").canOpenComposerAttachmentPreview(composerEnabled = true))
        assertFalse(readyVideo.copy(status = "failed").canOpenComposerAttachmentPreview(composerEnabled = true))
        assertFalse(readyVideo.copy(id = "picked-local-video").canOpenComposerAttachmentPreview(composerEnabled = true))
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
    fun activeDraftRecordsIdentifyReplyMessageFooterDrafts() {
        val parentMessageIds = setOf("parent-message-1")

        assertTrue(parentMessageIds.hasReplyComposerDraft("parent-message-1"))
        assertFalse(parentMessageIds.hasReplyComposerDraft("other-message"))
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
    fun pendingMentionActionMatchesCachedMemberSuggestionForAvatar() {
        val action =
            RaftPendingMentionActionItem(
                resolutionId = "res-1",
                targetType = "agent",
                targetHandle = "@outside"
            )
        val suggestion =
            RaftComposerMemberItem(
                displayName = "Outside Agent",
                handle = "outside",
                avatarWireValue = "pixel:outside",
                avatarLabel = "OA",
                memberId = "agent-1"
            )

        assertEquals(
            suggestion,
            action.matchingTargetSuggestion(listOf(suggestion))
        )
    }

    @Test
    fun pendingMentionActionUsesServerAvatarForUsersAndAgents() {
        val agentAction =
            RaftPendingMentionActionItem(
                resolutionId = "res-1",
                targetType = "agent",
                targetHandle = "@outside",
                targetAvatarUrl = "https://cdn.slock.ai/avatars/agents/outside.webp"
            )
        val userAction =
            RaftPendingMentionActionItem(
                resolutionId = "res-2",
                targetType = "user",
                targetHandle = "@outside",
                targetAvatarUrl = "https://cdn.slock.ai/avatars/agents/outside.webp"
            )
        val blankAgentAction =
            RaftPendingMentionActionItem(
                resolutionId = "res-3",
                targetType = "agent",
                targetHandle = "@outside",
                targetAvatarUrl = ""
            )

        assertEquals(
            "https://cdn.slock.ai/avatars/agents/outside.webp",
            agentAction.targetAvatarWireValue()
        )
        assertEquals(
            "https://cdn.slock.ai/avatars/agents/outside.webp",
            userAction.targetAvatarWireValue()
        )
        assertNull(blankAgentAction.targetAvatarWireValue())
    }

    @Test
    fun mentionScopeChannelLabelUsesDisplayNameInsteadOfRawIdentity() {
        assertEquals(
            "#对话流专修",
            ThreadComposerSurface(
                channelId = "707fec3f-7c26-4a00-8f94-1e4201f13f83",
                mentionChannelLabel = "对话流专修"
            ).mentionScopeChannelLabel()
        )
        assertEquals(
            "#对话流专修",
            ThreadComposerSurface(
                channelId = "707fec3f-7c26-4a00-8f94-1e4201f13f83",
                mentionChannelLabel = "#对话流专修"
            ).mentionScopeChannelLabel()
        )
        assertEquals(
            "this channel",
            ThreadComposerSurface(
                channelId = "707fec3f-7c26-4a00-8f94-1e4201f13f83",
                mentionChannelLabel = "707fec3f-7c26-4a00-8f94-1e4201f13f83"
            ).mentionScopeChannelLabel()
        )
    }

    @Test
    fun mentionActionFailureStatusMapsKnownReasons() {
        val strings = pendingMentionActionTestStrings()
        assertEquals(
            "Only channel admins can add members.",
            formatMentionActionStatusNotice(
                "add",
                build.raft.app.data.PendingMentionActionExecuteResult(
                    resolutionId = "res-1",
                    status = "no_permission",
                    reason = "add_requires_member_management_authority"
                ),
                strings
            )
        )
        assertEquals(
            "Delivery is temporarily unavailable.",
            formatMentionActionStatusNotice(
                "notify",
                build.raft.app.data.PendingMentionActionExecuteResult(
                    resolutionId = "res-1",
                    status = "dropped",
                    reason = "delivery_unavailable"
                ),
                strings
            )
        )
        assertEquals(
            "This action is no longer pending.",
            formatMentionActionStatusNotice(
                "add",
                build.raft.app.data.PendingMentionActionExecuteResult(
                    resolutionId = "res-1",
                    status = "stale",
                    reason = "no_longer_pending"
                ),
                strings
            )
        )
        assertEquals(
            "This mention action is no longer available.",
            formatMentionActionStatusNotice("add", null, strings)
        )
        listOf(
            "expired" to "This action expired.",
            "not_found" to "This mention action is no longer available.",
            "ambiguous" to "This action matched multiple targets."
        ).forEach { (status, expected) ->
            assertEquals(
                expected,
                formatMentionActionStatusNotice(
                    "add",
                    build.raft.app.data.PendingMentionActionExecuteResult(
                        resolutionId = "res-1",
                        status = status
                    ),
                    strings
                )
            )
        }
        listOf(
            "add_not_allowed_for_surface" to "Use channel member controls.",
            "add_requires_human_member_authority" to "Ask a human member.",
            "sender_lacks_channel_access" to "You no longer have channel access."
        ).forEach { (reason, expected) ->
            assertEquals(
                expected,
                formatMentionActionStatusNotice(
                    "add",
                    build.raft.app.data.PendingMentionActionExecuteResult(
                        resolutionId = "res-1",
                        status = "no_permission",
                        reason = reason
                    ),
                    strings
                )
            )
        }
        assertEquals(
            "The target was not notified.",
            formatMentionActionStatusNotice(
                "notify",
                build.raft.app.data.PendingMentionActionExecuteResult(
                    resolutionId = "res-1",
                    status = "dropped",
                    reason = "target_unavailable_for_delivery"
                ),
                strings
            )
        )
        assertEquals(
            "The action was not delivered.",
            formatMentionActionStatusNotice(
                "add",
                build.raft.app.data.PendingMentionActionExecuteResult(
                    resolutionId = "res-1",
                    status = "dropped",
                    reason = "target_unavailable_for_delivery"
                ),
                strings
            )
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
