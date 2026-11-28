package build.raft.app.data

import build.raft.app.thread.VoiceDictationTextLayoutMetrics
import build.raft.app.thread.VoiceDictationUnderlineSegment
import build.raft.app.thread.computeVoiceDictationUnderlineSegments
import build.raft.app.thread.resolveActiveVoiceDictationProvisionalRange
import build.raft.realtime.socket.RaftWebSocketFrame
import build.raft.realtime.socket.RaftWebSocketSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceDictationProvisionalRangeTest {
    private val validChannelUuid = "550e8400-e29b-41d4-a716-446655440000"

    // -------------------------------------------------------------------------
    // 1. Range trimming & bounds clamping (mirrors web splitProvisionalText)
    // -------------------------------------------------------------------------

    @Test
    fun trimProvisionalRangeClampsBoundsTrimsJoiningWhitespaceAndRejectsEmptyOrWhitespaceOnly() {
        assertNull(trimVoiceDictationProvisionalRange("", VoiceDictationTextRange(0, 5)))
        assertNull(trimVoiceDictationProvisionalRange("hello", null))
        assertNull(trimVoiceDictationProvisionalRange("hello", VoiceDictationTextRange(3, 3)))
        assertNull(trimVoiceDictationProvisionalRange("hello", VoiceDictationTextRange(4, 2)))
        assertNull(trimVoiceDictationProvisionalRange("hello", VoiceDictationTextRange(10, 20)))
        assertNull(trimVoiceDictationProvisionalRange("hello   ", VoiceDictationTextRange(5, 8)))
        assertNull(trimVoiceDictationProvisionalRange("hello \n\t ", VoiceDictationTextRange(5, 9)))

        // Trims leading/trailing joining space strictly outside spoken words.
        assertEquals(
            VoiceDictationTextRange(6, 11),
            trimVoiceDictationProvisionalRange("Draft hello", VoiceDictationTextRange(5, 11)),
        )
        assertEquals(
            VoiceDictationTextRange(5, 14),
            trimVoiceDictationProvisionalRange("已有内容 正在说还没定稿的字", VoiceDictationTextRange(4, 14)),
        )
        assertEquals(
            VoiceDictationTextRange(1, 5),
            trimVoiceDictationProvisionalRange(" word ", VoiceDictationTextRange(0, 6)),
        )

        // Clamps negative / overflowing endpoints cleanly without throwing.
        assertEquals(
            VoiceDictationTextRange(0, 5),
            trimVoiceDictationProvisionalRange("hello", VoiceDictationTextRange(-4, 99)),
        )
    }

    // -------------------------------------------------------------------------
    // 2. VoiceDictationComposerSpan provisionalRange & Escape discard
    // -------------------------------------------------------------------------

    @Test
    fun spanTracksProvisionalRangeAcrossEmptyAsciiCjkMidDraftAndMultiUtteranceLifecycle() {
        // A) Empty draft -> partial expands & shrinks -> identical-text final clears range.
        val emptySpan = VoiceDictationComposerSpan(baseText = "")
        val p1 = assertNotNull(emptySpan.applyText("hel", stableLen = 0))
        assertEquals("hel", p1.composerText)
        assertEquals(VoiceDictationTextRange(0, 3), p1.provisionalRange)
        assertEquals(VoiceDictationTextRange(0, 3), emptySpan.lastProvisionalRange)

        val p2 = assertNotNull(emptySpan.applyText("hello world", stableLen = 0))
        assertEquals("hello world", p2.composerText)
        assertEquals(VoiceDictationTextRange(0, 11), p2.provisionalRange)

        // Partial shrinks cleanly.
        val p3 = assertNotNull(emptySpan.applyText("hello", stableLen = 0))
        assertEquals("hello", p3.composerText)
        assertEquals(VoiceDictationTextRange(0, 5), p3.provisionalRange)

        // Identical text finalized -> clears provisionalRange to null.
        val f1 = assertNotNull(emptySpan.applyText("hello", stableLen = 5))
        assertEquals("hello", f1.composerText)
        assertNull(f1.provisionalRange)
        assertNull(emptySpan.lastProvisionalRange)

        // Utterance 2 partial -> covers ONLY utterance 2 (never utterance 1!).
        val u2Partial = assertNotNull(emptySpan.applyText("hello there", stableLen = 5))
        assertEquals("hello there", u2Partial.composerText)
        assertEquals(VoiceDictationTextRange(5, 11), u2Partial.provisionalRange)
        assertEquals(
            VoiceDictationTextRange(6, 11),
            trimVoiceDictationProvisionalRange(u2Partial.composerText, u2Partial.provisionalRange),
        )

        // Utterance 1 refined while utterance 2 live partial is still in flight -> shifts provisionalRange cleanly.
        val u1RefinedWhileU2Live =
            assertNotNull(emptySpan.applyText("Hello, there", stableLen = 6, refined = true))
        assertEquals("Hello, there", u1RefinedWhileU2Live.composerText)
        assertTrue(u1RefinedWhileU2Live.refined)
        assertEquals(VoiceDictationTextRange(6, 12), u1RefinedWhileU2Live.provisionalRange)

        // Session refined -> stableLen == text.length -> clears provisionalRange to null.
        val sessionRefined =
            assertNotNull(emptySpan.applyText("Hello there.", stableLen = 12, refined = true))
        assertEquals("Hello there.", sessionRefined.composerText)
        assertNull(sessionRefined.provisionalRange)

        // B) Appending after ASCII draft (leading space excluded from provisionalRange).
        val asciiAppend = VoiceDictationComposerSpan(baseText = "Ship", insertStart = 4, insertEnd = 4)
        val asciiPartial = assertNotNull(asciiAppend.applyText("it today", stableLen = 2))
        assertEquals("Ship it today", asciiPartial.composerText)
        assertEquals(VoiceDictationTextRange(7, 13), asciiPartial.provisionalRange)
        assertEquals(
            VoiceDictationTextRange(8, 13),
            trimVoiceDictationProvisionalRange(asciiPartial.composerText, asciiPartial.provisionalRange),
        )

        // C) Appending after CJK draft (no boundary space inserted).
        val cjkAppend = VoiceDictationComposerSpan(baseText = "已有内容", insertStart = 4, insertEnd = 4)
        val cjkPartial = assertNotNull(cjkAppend.applyText("正在说话", stableLen = 0))
        assertEquals("已有内容正在说话", cjkPartial.composerText)
        assertEquals(VoiceDictationTextRange(4, 8), cjkPartial.provisionalRange)
        val cjkFinal = assertNotNull(cjkAppend.applyText("正在说话。", stableLen = 5))
        assertNull(cjkFinal.provisionalRange)
        val cjkU2 = assertNotNull(cjkAppend.applyText("正在说话。第二句", stableLen = 5))
        assertEquals(VoiceDictationTextRange(9, 12), cjkU2.provisionalRange)

        // D) Inserting in middle of ASCII draft (both left & right boundary spaces excluded from provisionalRange).
        val midAscii = VoiceDictationComposerSpan(baseText = "hello.world", insertStart = 6, insertEnd = 6)
        val midPartial = assertNotNull(midAscii.applyText("new draft", stableLen = 3))
        assertEquals("hello. new draft world", midPartial.composerText)
        assertEquals(VoiceDictationTextRange(10, 16), midPartial.provisionalRange)
        assertEquals(
            VoiceDictationTextRange(11, 16),
            trimVoiceDictationProvisionalRange(midPartial.composerText, midPartial.provisionalRange),
        )

        // E) Retracting partial back to empty string clears provisionalRange cleanly.
        val retracted = assertNotNull(emptySpan.applyText("", stableLen = 0))
        assertEquals("", retracted.composerText)
        assertNull(retracted.provisionalRange)
    }

    @Test
    fun spanDiscardProvisionalRestoresBaseSelectionOrKeepsConfirmedPrefix() {
        // 1) Nothing confirmed yet with non-collapsed initial selection -> restores exact base draft & selection.
        val selectedSpan = VoiceDictationComposerSpan(baseText = "hello bad world", insertStart = 6, insertEnd = 9)
        assertNotNull(selectedSpan.applyText("draft", stableLen = 0))
        val restored = assertNotNull(selectedSpan.discardProvisional())
        assertEquals("hello bad world", restored.composerText)
        assertEquals(6, restored.caretOffset)
        assertEquals(9, restored.selectionEndOffset)
        assertNull(restored.provisionalRange)
        assertNull(selectedSpan.lastProvisionalRange)
        assertNull(selectedSpan.discardProvisional(), "second discard must be a no-op")

        // 2) Confirmed utterance + live partial -> keeps confirmed prefix, drops only uncommitted tail.
        val multiSpan = VoiceDictationComposerSpan(baseText = "Note:", insertStart = 5, insertEnd = 5)
        assertNotNull(multiSpan.applyText("Confirmed. draft", stableLen = 10))
        val trimmed = assertNotNull(multiSpan.discardProvisional())
        assertEquals("Note: Confirmed.", trimmed.composerText)
        assertEquals(16, trimmed.caretOffset)
        assertEquals(16, trimmed.selectionEndOffset)
        assertNull(trimmed.provisionalRange)

        // 3) Already-confirmed span has nothing provisional to discard.
        val confirmedSpan = VoiceDictationComposerSpan(baseText = "")
        assertNotNull(confirmedSpan.applyText("Done.", stableLen = 5))
        assertNull(confirmedSpan.discardProvisional())

        // 4) Detached by manual user edit -> never overwrites user text.
        val editedSpan = VoiceDictationComposerSpan(baseText = "")
        assertNotNull(editedSpan.applyText("draft", stableLen = 0))
        editedSpan.onUserEditedComposer("draft edited")
        assertNull(editedSpan.lastProvisionalRange)
        assertNull(editedSpan.discardProvisional())
    }

    // -------------------------------------------------------------------------
    // 3. VoiceDictationV2Controller provisionalRange full lifecycle
    // -------------------------------------------------------------------------

    @Test
    fun controllerUpdatesAndClearsProvisionalRangeAcrossPartialFinalStopCancelSubmitErrorEditAndOwnerSwitch() = runTest {
        // A) Partial -> Utterance 1 Final -> Utterance 2 Partial -> Stop (clears immediately & stays null on bg partial)
        val ws1 = FakeWsSession()
        val rec1 = FakeRecorder()
        val updates1 = mutableListOf<VoiceDictationComposerUpdate>()
        val controller =
            VoiceDictationV2Controller(
                permitApi = FakePermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> ws1 },
                scope = backgroundScope,
                audioRecorderProvider = { rec1 },
            )

        controller.start(serverId = "srv-1", channelId = validChannelUuid, baseText = "Hi", onComposerUpdate = { updates1 += it })
        runCurrent()
        assertNull(controller.state.value.provisionalRange)

        ws1.pushText("""{"deck":"ready"}""")
        ws1.pushText("""{"deck":"text","text":"team","stable_len":0}""")
        runCurrent()
        assertEquals("Hi team", updates1.last().composerText)
        assertEquals(VoiceDictationTextRange(3, 7), controller.state.value.provisionalRange)

        // Finalizing identical text clears provisionalRange immediately.
        ws1.pushText("""{"deck":"text","text":"team","stable_len":4}""")
        runCurrent()
        assertNull(controller.state.value.provisionalRange)

        // Utterance 2 partial sets range strictly on utterance 2.
        ws1.pushText("""{"deck":"text","text":"team ship today","stable_len":4}""")
        runCurrent()
        assertEquals("Hi team ship today", updates1.last().composerText)
        assertEquals(VoiceDictationTextRange(7, 18), controller.state.value.provisionalRange)

        // Non-blocking stop returns foreground to Idle immediately while RETAINING provisionalRange
        // throughout background refinement until a settled/refined snapshot or drain completion.
        controller.stop()
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
        assertEquals(0f, controller.state.value.audioLevel)
        assertTrue(controller.state.value.hasBackgroundDrain)
        assertEquals(VoiceDictationTextRange(7, 18), controller.state.value.provisionalRange)

        // Intermediate post-stop partial updates both draft text and retained provisionalRange.
        ws1.pushText("""{"deck":"text","text":"team ship today please","stable_len":4}""")
        runCurrent()
        assertEquals("Hi team ship today please", updates1.last().composerText)
        assertEquals(VoiceDictationTextRange(7, 25), controller.state.value.provisionalRange)

        // Settled/refined post-stop snapshot (stable_len == text.length) clears provisionalRange immediately.
        ws1.pushText("""{"deck":"text","text":"team ship today please.","stable_len":23,"refined":true}""")
        runCurrent()
        assertEquals("Hi team ship today please.", updates1.last().composerText)
        assertNull(controller.state.value.provisionalRange)

        ws1.pushText("""{"deck":"done"}""")
        runCurrent()
        assertFalse(controller.state.value.hasBackgroundDrain)
        assertNull(controller.state.value.provisionalRange)

        // A2) Post-stop provisionalRange also clears cleanly on:
        //   - `done` while text is still provisional (e.g. unrefined)
        //   - background stream error
        //   - manual user edit during background refine
        //   - finishForSubmit() (WYSIWYG submit)
        //   - starting a new dictation session
        //   - owner switch during background refine
        val wsDone = FakeWsSession()
        val cDone =
            VoiceDictationV2Controller(
                permitApi = FakePermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsDone },
                scope = backgroundScope,
                audioRecorderProvider = { FakeRecorder() },
            )
        cDone.start(serverId = "srv-1", baseText = "", onComposerUpdate = {})
        runCurrent()
        wsDone.pushText("""{"deck":"ready"}""")
        wsDone.pushText("""{"deck":"text","text":"unrefined draft","stable_len":0}""")
        runCurrent()
        cDone.stop()
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Idle, cDone.state.value.phase)
        assertEquals(VoiceDictationTextRange(0, 15), cDone.state.value.provisionalRange)
        wsDone.pushText("""{"deck":"done"}""")
        runCurrent()
        assertNull(cDone.state.value.provisionalRange)
        assertFalse(cDone.state.value.hasBackgroundDrain)

        val wsBgErr = FakeWsSession()
        val cBgErr =
            VoiceDictationV2Controller(
                permitApi = FakePermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsBgErr },
                scope = backgroundScope,
                audioRecorderProvider = { FakeRecorder() },
            )
        cBgErr.start(serverId = "srv-1", baseText = "", onComposerUpdate = {})
        runCurrent()
        wsBgErr.pushText("""{"deck":"ready"}""")
        wsBgErr.pushText("""{"deck":"text","text":"bg error draft","stable_len":0}""")
        runCurrent()
        cBgErr.stop()
        runCurrent()
        assertEquals(VoiceDictationTextRange(0, 14), cBgErr.state.value.provisionalRange)
        wsBgErr.pushText("""{"deck":"error","code":"unavailable"}""")
        runCurrent()
        assertNull(cBgErr.state.value.provisionalRange)
        assertFalse(cBgErr.state.value.hasBackgroundDrain)

        val wsBgEdit = FakeWsSession()
        val cBgEdit =
            VoiceDictationV2Controller(
                permitApi = FakePermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsBgEdit },
                scope = backgroundScope,
                audioRecorderProvider = { FakeRecorder() },
            )
        cBgEdit.start(serverId = "srv-1", baseText = "", onComposerUpdate = {})
        runCurrent()
        wsBgEdit.pushText("""{"deck":"ready"}""")
        wsBgEdit.pushText("""{"deck":"text","text":"post stop edit","stable_len":0}""")
        runCurrent()
        cBgEdit.stop()
        runCurrent()
        assertEquals(VoiceDictationTextRange(0, 14), cBgEdit.state.value.provisionalRange)
        // Programmatic echo preserves underline; genuine manual edit clears it immediately.
        cBgEdit.onUserEditedComposer("post stop edit")
        assertEquals(VoiceDictationTextRange(0, 14), cBgEdit.state.value.provisionalRange)
        cBgEdit.onUserEditedComposer("post stop edit!")
        assertNull(cBgEdit.state.value.provisionalRange)
        wsBgEdit.pushText("""{"deck":"text","text":"post stop edit refined","stable_len":0}""")
        runCurrent()
        assertNull(cBgEdit.state.value.provisionalRange)
        cBgEdit.cancel()
        runCurrent()

        val wsBgSubmit = FakeWsSession()
        val cBgSubmit =
            VoiceDictationV2Controller(
                permitApi = FakePermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsBgSubmit },
                scope = backgroundScope,
                audioRecorderProvider = { FakeRecorder() },
            )
        cBgSubmit.start(serverId = "srv-1", baseText = "", onComposerUpdate = {})
        runCurrent()
        wsBgSubmit.pushText("""{"deck":"ready"}""")
        wsBgSubmit.pushText("""{"deck":"text","text":"send now","stable_len":0}""")
        runCurrent()
        cBgSubmit.stop()
        runCurrent()
        assertEquals(VoiceDictationTextRange(0, 8), cBgSubmit.state.value.provisionalRange)
        cBgSubmit.finishForSubmit()
        runCurrent()
        assertNull(cBgSubmit.state.value.provisionalRange)
        assertFalse(cBgSubmit.state.value.hasBackgroundDrain)

        val wsBgRestartA = FakeWsSession()
        val wsBgRestartB = FakeWsSession()
        var restartIdx = 0
        val cBgRestart =
            VoiceDictationV2Controller(
                permitApi = FakePermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> if (restartIdx++ == 0) wsBgRestartA else wsBgRestartB },
                scope = backgroundScope,
                audioRecorderProvider = { FakeRecorder() },
            )
        cBgRestart.start(serverId = "srv-1", baseText = "", onComposerUpdate = {})
        runCurrent()
        wsBgRestartA.pushText("""{"deck":"ready"}""")
        wsBgRestartA.pushText("""{"deck":"text","text":"first draft","stable_len":0}""")
        runCurrent()
        cBgRestart.stop()
        runCurrent()
        assertEquals(VoiceDictationTextRange(0, 11), cBgRestart.state.value.provisionalRange)
        cBgRestart.start(serverId = "srv-1", baseText = "first draft", onComposerUpdate = {})
        runCurrent()
        assertNull(cBgRestart.state.value.provisionalRange)
        cBgRestart.cancel()
        runCurrent()

        val wsBgOwner = FakeWsSession()
        val cBgOwner =
            VoiceDictationV2Controller(
                permitApi = FakePermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsBgOwner },
                scope = backgroundScope,
                audioRecorderProvider = { FakeRecorder() },
            )
        cBgOwner.start(serverId = "srv-1", channelId = validChannelUuid, baseText = "", onComposerUpdate = {})
        runCurrent()
        wsBgOwner.pushText("""{"deck":"ready"}""")
        wsBgOwner.pushText("""{"deck":"text","text":"switch room","stable_len":0}""")
        runCurrent()
        cBgOwner.stop()
        runCurrent()
        assertEquals(VoiceDictationTextRange(0, 11), cBgOwner.state.value.provisionalRange)
        cBgOwner.onOwnerChanged(serverId = "srv-1", channelId = "660e8400-e29b-41d4-a716-446655440000")
        runCurrent()
        assertNull(cBgOwner.state.value.provisionalRange)
        assertFalse(cBgOwner.state.value.hasBackgroundDrain)

        // B) Escape cancel(discardProvisional = true) clears range AND strips uncommitted tail.
        val ws2 = FakeWsSession()
        val updates2 = mutableListOf<VoiceDictationComposerUpdate>()
        val controller2 =
            VoiceDictationV2Controller(
                permitApi = FakePermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> ws2 },
                scope = backgroundScope,
                audioRecorderProvider = { FakeRecorder() },
            )
        controller2.start(serverId = "srv-1", baseText = "", onComposerUpdate = { updates2 += it })
        runCurrent()
        ws2.pushText("""{"deck":"ready"}""")
        ws2.pushText("""{"deck":"text","text":"First. uncommitted","stable_len":6}""")
        runCurrent()
        assertEquals(VoiceDictationTextRange(6, 18), controller2.state.value.provisionalRange)

        controller2.cancel(discardProvisional = true)
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Idle, controller2.state.value.phase)
        assertNull(controller2.state.value.provisionalRange)
        assertEquals("First.", updates2.last().composerText)

        // C) Plain cancel() (default keep) clears provisionalRange while preserving already-landed text.
        val ws3 = FakeWsSession()
        val updates3 = mutableListOf<VoiceDictationComposerUpdate>()
        val controller3 =
            VoiceDictationV2Controller(
                permitApi = FakePermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> ws3 },
                scope = backgroundScope,
                audioRecorderProvider = { FakeRecorder() },
            )
        controller3.start(serverId = "srv-1", baseText = "", onComposerUpdate = { updates3 += it })
        runCurrent()
        ws3.pushText("""{"deck":"ready"}""")
        ws3.pushText("""{"deck":"text","text":"keep me","stable_len":0}""")
        runCurrent()
        assertEquals(VoiceDictationTextRange(0, 7), controller3.state.value.provisionalRange)

        controller3.cancel()
        runCurrent()
        assertNull(controller3.state.value.provisionalRange)
        assertEquals(listOf("keep me"), updates3.map { it.composerText })

        // D) User manual edit immediately clears provisionalRange (while programmatic echo preserves it).
        val ws4 = FakeWsSession()
        val updates4 = mutableListOf<VoiceDictationComposerUpdate>()
        val controller4 =
            VoiceDictationV2Controller(
                permitApi = FakePermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> ws4 },
                scope = backgroundScope,
                audioRecorderProvider = { FakeRecorder() },
            )
        controller4.start(serverId = "srv-1", baseText = "", onComposerUpdate = { updates4 += it })
        runCurrent()
        ws4.pushText("""{"deck":"ready"}""")
        ws4.pushText("""{"deck":"text","text":"speaking","stable_len":0}""")
        runCurrent()
        assertEquals(VoiceDictationTextRange(0, 8), controller4.state.value.provisionalRange)

        // Echo of programmatic text preserves provisionalRange.
        controller4.onUserEditedComposer("speaking")
        assertEquals(VoiceDictationTextRange(0, 8), controller4.state.value.provisionalRange)

        // Genuine manual edit clears provisionalRange and ignores subsequent frames.
        controller4.onUserEditedComposer("speaking!")
        assertNull(controller4.state.value.provisionalRange)
        ws4.pushText("""{"deck":"text","text":"speaking loudly","stable_len":0}""")
        runCurrent()
        assertNull(controller4.state.value.provisionalRange)
        assertEquals(listOf("speaking"), updates4.map { it.composerText })
        controller4.finishForSubmit()
        runCurrent()
        assertNull(controller4.state.value.provisionalRange)

        // E) Server error & owner switch both clear provisionalRange cleanly.
        val ws5 = FakeWsSession()
        val controller5 =
            VoiceDictationV2Controller(
                permitApi = FakePermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> ws5 },
                scope = backgroundScope,
                audioRecorderProvider = { FakeRecorder() },
            )
        controller5.start(serverId = "srv-1", channelId = validChannelUuid, baseText = "", onComposerUpdate = {})
        runCurrent()
        ws5.pushText("""{"deck":"ready"}""")
        ws5.pushText("""{"deck":"text","text":"partial before error","stable_len":0}""")
        runCurrent()
        assertEquals(VoiceDictationTextRange(0, 20), controller5.state.value.provisionalRange)
        ws5.pushText("""{"deck":"error","code":"unavailable"}""")
        runCurrent()
        assertNull(controller5.state.value.provisionalRange)

        val ws6 = FakeWsSession()
        val controller6 =
            VoiceDictationV2Controller(
                permitApi = FakePermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> ws6 },
                scope = backgroundScope,
                audioRecorderProvider = { FakeRecorder() },
            )
        controller6.start(serverId = "srv-1", channelId = validChannelUuid, baseText = "", onComposerUpdate = {})
        runCurrent()
        ws6.pushText("""{"deck":"ready"}""")
        ws6.pushText("""{"deck":"text","text":"switching rooms","stable_len":0}""")
        runCurrent()
        assertEquals(VoiceDictationTextRange(0, 15), controller6.state.value.provisionalRange)
        controller6.onOwnerChanged(serverId = "srv-1", channelId = "660e8400-e29b-41d4-a716-446655440000")
        runCurrent()
        assertNull(controller6.state.value.provisionalRange)
    }

    // -------------------------------------------------------------------------
    // 4. Pure line-segment geometry & guard helpers
    // -------------------------------------------------------------------------

    @Test
    fun computeUnderlineSegmentsHandlesSingleLineWrappedMultiLineBlankLinesRtlAndOutOfBoundsSafely() {
        // Single-line: "Draft hello" (each char 10px wide, line 0..22px, baseline 17px).
        val singleMetrics = FakeLayoutMetrics(lines = listOf("Draft hello"), charWidth = 10f, lineHeight = 22f, baselineOffset = 17f)
        val singleSegments =
            computeVoiceDictationUnderlineSegments(
                text = "Draft hello",
                provisionalRange = VoiceDictationTextRange(5, 11), // " hello" -> trims to "hello" (6..11)
                metrics = singleMetrics,
                underlineOffsetPx = 2f,
                strokeWidthPx = 1f,
            )
        assertEquals(
            listOf(VoiceDictationUnderlineSegment(lineIndex = 0, startX = 60f, endX = 110f, y = 19f)),
            singleSegments,
        )

        // Multi-line wrapped with explicit blank line:
        // line 0: "Note: one " (0..10, visibleEnd 9)
        // line 1: "\n"         (10..11, visibleEnd 10 -> blank, skipped!)
        // line 2: "two three"  (11..20, visibleEnd 20)
        val multiText = "Note: one \ntwo three"
        val multiMetrics =
            FakeLayoutMetrics(
                lines = listOf("Note: one ", "\n", "two three"),
                charWidth = 8f,
                lineHeight = 20f,
                baselineOffset = 16f,
            )
        val multiSegments =
            computeVoiceDictationUnderlineSegments(
                text = multiText,
                provisionalRange = VoiceDictationTextRange(5, multiText.length), // " one \ntwo three"
                metrics = multiMetrics,
                underlineOffsetPx = 2f,
                strokeWidthPx = 1f,
            )
        assertEquals(
            listOf(
                VoiceDictationUnderlineSegment(lineIndex = 0, startX = 48f, endX = 72f, y = 18f),
                VoiceDictationUnderlineSegment(lineIndex = 2, startX = 0f, endX = 72f, y = 58f),
            ),
            multiSegments,
        )

        // Underline Y clamps cleanly inside [lineTop + stroke/2, lineBottom - stroke/2] when offset exceeds lineBottom.
        val clampedSegments =
            computeVoiceDictationUnderlineSegments(
                text = "Draft hello",
                provisionalRange = VoiceDictationTextRange(6, 11),
                metrics = singleMetrics,
                underlineOffsetPx = 12f, // baseline 17 + 12 = 29 > bottom 22 - 1 = 21
                strokeWidthPx = 2f,
            )
        assertEquals(21f, clampedSegments.single().y)

        // RTL visual glyph boxes (ordered right-to-left) compute exact visual minLeft..maxRight union.
        val rtlMetrics =
            FakeLayoutMetrics(
                lines = listOf("مرحبا"),
                charWidth = 10f,
                lineHeight = 22f,
                baselineOffset = 17f,
                rtlLineWidth = 100f,
            )
        val rtlSegments =
            computeVoiceDictationUnderlineSegments(
                text = "مرحبا",
                provisionalRange = VoiceDictationTextRange(0, 5),
                metrics = rtlMetrics,
                underlineOffsetPx = 2f,
                strokeWidthPx = 1f,
            )
        assertEquals(
            listOf(VoiceDictationUnderlineSegment(lineIndex = 0, startX = 50f, endX = 100f, y = 19f)),
            rtlSegments,
        )

        // Degenerate zero-width glyph boxes fall back to horizontalPosition safely.
        val degenerateMetrics =
            FakeLayoutMetrics(
                lines = listOf("abc"),
                charWidth = 10f,
                lineHeight = 20f,
                baselineOffset = 16f,
                degenerateCharBoxes = true,
            )
        val fallbackSegments =
            computeVoiceDictationUnderlineSegments(
                text = "abc",
                provisionalRange = VoiceDictationTextRange(0, 3),
                metrics = degenerateMetrics,
                underlineOffsetPx = 2f,
                strokeWidthPx = 1f,
            )
        assertEquals(
            listOf(VoiceDictationUnderlineSegment(lineIndex = 0, startX = 0f, endX = 30f, y = 18f)),
            fallbackSegments,
        )

        // Stale layout length mismatch & out-of-bounds range never throw and return emptyList / clamped range.
        assertTrue(
            computeVoiceDictationUnderlineSegments(
                text = "longer than layout",
                provisionalRange = VoiceDictationTextRange(0, 18),
                metrics = singleMetrics,
                underlineOffsetPx = 2f,
                strokeWidthPx = 1f,
            ).isEmpty(),
        )
        assertEquals(
            listOf(VoiceDictationUnderlineSegment(lineIndex = 0, startX = 0f, endX = 110f, y = 19f)),
            computeVoiceDictationUnderlineSegments(
                text = "Draft hello",
                provisionalRange = VoiceDictationTextRange(-10, 500),
                metrics = singleMetrics,
                underlineOffsetPx = 2f,
                strokeWidthPx = 1f,
            ),
        )
    }

    @Test
    fun resolveActiveProvisionalRangeGuardsPhaseImeAndTextMismatch() {
        val activeState =
            VoiceDictationV2State(
                phase = VoiceDictationV2Phase.Listening,
                lastUpdate =
                    VoiceDictationComposerUpdate(
                        composerText = "Ship it today",
                        spanStart = 4,
                        spanEnd = 13,
                        textStart = 5,
                        stableEnd = 7,
                        textEnd = 13,
                        caretOffset = 13,
                        stableLen = 2,
                        refined = false,
                        provisionalRange = VoiceDictationTextRange(7, 13),
                    ),
                provisionalRange = VoiceDictationTextRange(7, 13),
            )
        assertEquals(
            VoiceDictationTextRange(8, 13),
            resolveActiveVoiceDictationProvisionalRange(activeState, "Ship it today", imeComposing = false),
        )
        // Never clashes with active system IME composition.
        assertNull(resolveActiveVoiceDictationProvisionalRange(activeState, "Ship it today", imeComposing = true))
        // Never paints over diverged draft text.
        assertNull(resolveActiveVoiceDictationProvisionalRange(activeState, "Ship it tomorrow", imeComposing = false))
        // Renders composing underline while Idle during post-stop background refinement.
        assertEquals(
            VoiceDictationTextRange(8, 13),
            resolveActiveVoiceDictationProvisionalRange(
                activeState.copy(phase = VoiceDictationV2Phase.Idle, hasBackgroundDrain = true),
                "Ship it today",
                imeComposing = false,
            ),
        )
        // Still respects IME composition and text divergence while refining in background.
        assertNull(
            resolveActiveVoiceDictationProvisionalRange(
                activeState.copy(phase = VoiceDictationV2Phase.Idle, hasBackgroundDrain = true),
                "Ship it today",
                imeComposing = true,
            ),
        )
        assertNull(
            resolveActiveVoiceDictationProvisionalRange(
                activeState.copy(phase = VoiceDictationV2Phase.Idle, hasBackgroundDrain = true),
                "Ship it edited",
                imeComposing = false,
            ),
        )
        // Never paints when fully Idle (no active session and no background drain).
        assertNull(
            resolveActiveVoiceDictationProvisionalRange(
                activeState.copy(phase = VoiceDictationV2Phase.Idle, hasBackgroundDrain = false),
                "Ship it today",
                imeComposing = false,
            ),
        )
        // Never paints when provisionalRange is null.
        assertNull(
            resolveActiveVoiceDictationProvisionalRange(
                activeState.copy(provisionalRange = null),
                "Ship it today",
                imeComposing = false,
            ),
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private class FakeLayoutMetrics(
        private val lines: List<String>,
        private val charWidth: Float,
        private val lineHeight: Float,
        private val baselineOffset: Float,
        private val rtlLineWidth: Float? = null,
        private val degenerateCharBoxes: Boolean = false,
    ) : VoiceDictationTextLayoutMetrics {
        private val starts: IntArray =
            IntArray(lines.size).also { arr ->
                var acc = 0
                for (i in lines.indices) {
                    arr[i] = acc
                    acc += lines[i].length
                }
            }

        override val textLength: Int = lines.sumOf { it.length }
        override val lineCount: Int = lines.size

        override fun lineForOffset(offset: Int): Int {
            for (i in lines.indices) {
                val s = starts[i]
                val e = s + lines[i].length
                if (offset in s until e) return i
            }
            return (lines.size - 1).coerceAtLeast(0)
        }

        override fun lineStart(lineIndex: Int): Int = starts[lineIndex]

        override fun lineVisibleEnd(lineIndex: Int): Int {
            val line = lines[lineIndex]
            var end = line.length
            while (end > 0 && line[end - 1].isWhitespace()) end -= 1
            return starts[lineIndex] + end
        }

        override fun lineTop(lineIndex: Int): Float = lineIndex * lineHeight

        override fun lineBaseline(lineIndex: Int): Float = lineIndex * lineHeight + baselineOffset

        override fun lineBottom(lineIndex: Int): Float = (lineIndex + 1) * lineHeight

        override fun charLeft(offset: Int): Float {
            if (degenerateCharBoxes) return 0f
            val line = lineForOffset(offset)
            val col = offset - starts[line]
            return if (rtlLineWidth != null) {
                rtlLineWidth - (col + 1) * charWidth
            } else {
                col * charWidth
            }
        }

        override fun charRight(offset: Int): Float {
            if (degenerateCharBoxes) return 0f
            val line = lineForOffset(offset)
            val col = offset - starts[line]
            return if (rtlLineWidth != null) {
                rtlLineWidth - col * charWidth
            } else {
                (col + 1) * charWidth
            }
        }

        override fun horizontalPosition(offset: Int, usePrimaryDirection: Boolean): Float {
            val clamped = offset.coerceIn(0, textLength)
            val line = if (clamped == textLength) (lines.size - 1).coerceAtLeast(0) else lineForOffset(clamped)
            val col = clamped - starts[line]
            return if (rtlLineWidth != null) {
                rtlLineWidth - col * charWidth
            } else {
                col * charWidth
            }
        }
    }

    private fun defaultPermitOutcome(): VoiceDictationV2PermitOutcome =
        VoiceDictationV2PermitOutcome.Issued(
            permit =
                VoiceDictationV2Permit(
                    permit = "hv2.k1.payload.sig",
                    url = "wss://live.holt.botiverse.build/v1/stream",
                    expiresAtIso = "2026-11-26T20:01:00.000Z",
                    expiresAtEpochMs = 1_795_723_260_000L,
                    maxDurationSeconds = 600,
                    quota =
                        VoiceDictationV2Quota(
                            usedSeconds = 120,
                            limitSeconds = 7200,
                            remainingSeconds = 7080,
                            resetsAtIso = "2026-11-27T00:00:00.000Z",
                            resetsAtEpochMs = 1_795_737_600_000L,
                        ),
                ),
        )

    private class FakePermitApi(
        private val outcome: VoiceDictationV2PermitOutcome,
    ) : VoiceDictationV2PermitApi {
        override suspend fun requestPermit(
            serverId: String,
            language: String?,
            channelId: String?,
        ): VoiceDictationV2PermitOutcome = outcome
    }

    private class FakeRecorder : RaftVoiceDictationAudioRecorder {
        override suspend fun start(
            onPcmChunk: (ByteArray) -> Unit,
            onAudioLevel: (Float) -> Unit,
        ): VoiceDictationAudioStartResult = VoiceDictationAudioStartResult.Started

        override suspend fun stop() = Unit
    }

    private class FakeWsSession : RaftWebSocketSession {
        private val toClient = Channel<RaftWebSocketFrame>(Channel.UNLIMITED)
        override val incoming: ReceiveChannel<RaftWebSocketFrame> = toClient

        override suspend fun send(text: String) = Unit

        override suspend fun sendBinary(bytes: ByteArray) = Unit

        override suspend fun close(code: Int, reason: String) {
            toClient.close()
        }

        fun pushText(text: String) {
            toClient.trySend(RaftWebSocketFrame.Text(text))
        }
    }
}
