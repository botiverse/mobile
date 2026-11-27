package build.raft.app

import build.raft.app.data.AppLinkActionResult
import build.raft.app.data.AppLinkApp
import build.raft.app.data.AppLinkBlockCard
import build.raft.app.data.AppLinkExecuted
import build.raft.app.data.AppLinkOperationStatus
import build.raft.app.data.AppLinkResult
import build.raft.app.data.CardStack
import build.raft.app.data.RaftAppLinkCardV2
import build.raft.app.data.RaftLinkCardButtonAction
import build.raft.app.data.RaftLinkCardButtonVariant
import build.raft.app.data.RaftLinkCardOperationHint
import build.raft.app.data.RaftLinkCardV2Element
import build.raft.app.thread.ThreadAppLinkCardActionHost
import build.raft.app.thread.ThreadAppLinkCardPress
import build.raft.app.thread.appLinkStateHintText
import build.raft.app.thread.appLinkStateToastTone
import build.raft.app.thread.button
import build.raft.designsystem.components.RaftToastIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #proj-mobile:#342: pressing an App link card action whose execute answers `accepted` (e.g. Stamp
 * PR approval) must keep the pressed button loading and disable sibling execute/read buttons until
 * the operation reaches a terminal receipt and the post-terminal reread completes, combine live
 * `link_card.operation_changed` hints with status polling without dropping hints that arrive during
 * execute/reread, unlock immediately on terminal completion instead of waiting 22s, and localize
 * Raft's operation state hints rather than flashing the server's English fallback text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadAppLinkCardPressOpenOperationTest {
    private val url = "https://stamp.build/github/botiverse/infrastructure/pull/1832"
    private val approveAction = RaftLinkCardButtonAction.Execute("approve", confirm = false, undoWindowMs = 1000)
    private val mergeAction = RaftLinkCardButtonAction.Execute("merge", confirm = false, undoWindowMs = 1000)
    private val approve = RaftLinkCardV2Element.Button("Approve too", RaftLinkCardButtonVariant.Default, approveAction)
    private val merge = RaftLinkCardV2Element.Button("Merge", RaftLinkCardButtonVariant.Ghost, mergeAction)
    private val refresh = RaftLinkCardV2Element.Button("Refresh", RaftLinkCardButtonVariant.Ghost, RaftLinkCardButtonAction.Read)

    private val card =
        AppLinkResult.Card(
            url = url,
            app = AppLinkApp("Stamp", null),
            title = "#1832",
            block =
                AppLinkBlockCard(
                    "#1832",
                    RaftAppLinkCardV2(
                        "root",
                        mapOf(
                            "root" to CardStack(children = listOf("approve", "merge", "refresh")),
                            "approve" to approve,
                            "merge" to merge,
                            "refresh" to refresh,
                        ),
                    ),
                    businessRevision = "br-1",
                ),
            chip = false,
            handles = mapOf("approve" to "h-approve", "merge" to "h-merge"),
        )

    private val localizedHints: (String) -> String? = { state ->
        appLinkStateHintText(
            state = state,
            sent = "已发送，正在等待应用。",
            accepted = "应用已接受，正在完成。",
            done = "已完成。打开应用查看结果。",
            notCarriedOut = "操作没有执行。",
            unconfirmed = "结果尚未确认。",
            resolvedManually = "无法确认结果。",
        )
    }

    @Test
    fun acceptedOperationKeepsPressedButtonLoadingBlocksDuplicatesAndUnlocksImmediatelyOnHint() = runTest {
        val hints = MutableSharedFlow<RaftLinkCardOperationHint>(extraBufferCapacity = 8)
        val toasts = mutableListOf<Pair<String, RaftToastIntent?>>()
        var executes = 0
        var rereads = 0
        var statusCalls = 0
        var statusAnswer: AppLinkOperationStatus = AppLinkOperationStatus.Open

        val host =
            ThreadAppLinkCardActionHost(
                prepare = { _, _ -> error("unused") },
                execute = { _, _, _ ->
                    executes += 1
                    AppLinkActionResult.Ok(AppLinkExecuted("op-1", "accepted", "Accepted. The app is finishing it.", "info"))
                },
                reread = {
                    rereads += 1
                    true
                },
                toast = { toasts += it to null },
                toastWithIntent = { text, intent -> toasts += text to intent },
                status = {
                    statusCalls += 1
                    statusAnswer
                },
                operationHints = hints,
            )

        val press =
            ThreadAppLinkCardPress(
                card = card,
                actions = host,
                scope = this,
                failedText = "failed",
                undoLabel = "Undo",
                refreshFailedText = "refresh failed",
                workingLabel = "处理中…",
                stateHintText = localizedHints,
            )

        // Start undo window and let it expire into execute().
        assertNotNull(press.button(approve, approveAction)).onClick()
        advanceTimeBy(1_000L)
        runCurrent()

        // Execute returned "accepted": button must stay loading, siblings disabled, duplicate presses ignored.
        assertEquals(1, executes)
        assertEquals(1, rereads)
        assertEquals(listOf<Pair<String, RaftToastIntent?>>("应用已接受，正在完成。" to RaftToastIntent.Info), toasts)
        assertTrue(press.busy, "press stays busy while operation is open")

        val approveBtnWhileOpen = assertNotNull(press.button(approve, approveAction))
        val mergeBtnWhileOpen = assertNotNull(press.button(merge, mergeAction))
        val refreshBtnWhileOpen = assertNotNull(press.readButton(refresh))
        assertTrue(approveBtnWhileOpen.loading, "pressed button stays loading while accepted operation runs")
        assertFalse(approveBtnWhileOpen.enabled, "pressed button cannot re-fire while operation is open")
        assertFalse(mergeBtnWhileOpen.enabled, "sibling execute button disabled while operation is open")
        assertFalse(refreshBtnWhileOpen.enabled, "read button disabled while operation is open")

        // Attempting duplicate clicks while open must be ignored (prevents 409 VIEW_PENDING).
        approveBtnWhileOpen.onClick()
        mergeBtnWhileOpen.onClick()
        refreshBtnWhileOpen.onClick()
        runCurrent()
        assertEquals(1, executes)
        assertEquals(1, rereads)

        // Operation completes at 400ms via realtime hint: must unlock immediately instead of waiting 22s.
        statusAnswer = AppLinkOperationStatus.Receipt("op-1", "done", receiptRevision = 1L, hint = "Done.", tone = "success", appToast = "Approved")
        advanceTimeBy(400L)
        hints.tryEmit(RaftLinkCardOperationHint("srv-1", "op-1", receiptRevision = 1L))
        runCurrent()

        assertEquals(1, statusCalls)
        assertEquals(2, rereads, "terminal receipt immediately rereads the card")
        assertEquals(
            listOf<Pair<String, RaftToastIntent?>>(
                "应用已接受，正在完成。" to RaftToastIntent.Info,
                "Stamp: Approved" to RaftToastIntent.Success,
            ),
            toasts,
        )
        assertFalse(press.busy, "press unlocks immediately once terminal reread finishes")
        assertFalse(assertNotNull(press.button(approve, approveAction)).loading)
        assertTrue(assertNotNull(press.button(approve, approveAction)).enabled)
        assertTrue(assertNotNull(press.button(merge, mergeAction)).enabled)
        assertTrue(assertNotNull(press.readButton(refresh)).enabled)

        // Advancing 30s must NOT trigger legacy 2s/5s/15s extra blind rereads.
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(2, rereads)
    }

    @Test
    fun hintArrivingDuringExecuteOrInitialRereadIsBufferedAndNotDropped() = runTest {
        val hints = MutableSharedFlow<RaftLinkCardOperationHint>(extraBufferCapacity = 8)
        val initialRereadGate = CompletableDeferred<Boolean>()
        val toasts = mutableListOf<String>()
        var rereads = 0
        var statusCalls = 0

        val host =
            ThreadAppLinkCardActionHost(
                prepare = { _, _ -> error("unused") },
                execute = { _, _, _ ->
                    // Simulate fast app emitting operation_changed before execute() response returns.
                    hints.tryEmit(RaftLinkCardOperationHint("srv-1", "op-fast", receiptRevision = 1L))
                    AppLinkActionResult.Ok(AppLinkExecuted("op-fast", "accepted", "Accepted. The app is finishing it.", "info"))
                },
                reread = {
                    rereads += 1
                    if (rereads == 1) initialRereadGate.await() else true
                },
                toast = { toasts += it },
                toastWithIntent = { text, _ -> toasts += text },
                status = {
                    statusCalls += 1
                    AppLinkOperationStatus.Receipt("op-fast", "done", receiptRevision = 1L, hint = "Done.", tone = "success", appToast = null)
                },
                operationHints = hints,
            )

        val press =
            ThreadAppLinkCardPress(
                card = card,
                actions = host,
                scope = this,
                failedText = "failed",
                undoLabel = "Undo",
                refreshFailedText = "refresh failed",
                stateHintText = localizedHints,
            )

        assertNotNull(press.button(approve, approveAction)).onClick()
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(press.busy)

        // Release initial reread: buffered hint must be drained immediately with zero timer delay.
        initialRereadGate.complete(true)
        runCurrent()

        assertEquals(1, statusCalls, "buffered hint triggers status immediately without waiting for poll timer")
        assertEquals(2, rereads)
        assertEquals(listOf("应用已接受，正在完成。", "已完成。打开应用查看结果。"), toasts)
        assertFalse(press.busy)
    }

    @Test
    fun missedHintFallsBackToStatusPollingAndUnlocksOnTerminalReceipt() = runTest {
        val hints = MutableSharedFlow<RaftLinkCardOperationHint>(extraBufferCapacity = 8)
        val toasts = mutableListOf<Pair<String, RaftToastIntent?>>()
        var rereads = 0
        var statusCalls = 0

        val host =
            ThreadAppLinkCardActionHost(
                prepare = { _, _ -> error("unused") },
                execute = { _, _, _ ->
                    AppLinkActionResult.Ok(AppLinkExecuted("op-poll", "accepted", "Accepted. The app is finishing it.", "info"))
                },
                reread = {
                    rereads += 1
                    true
                },
                toast = { toasts += it to null },
                toastWithIntent = { text, intent -> toasts += text to intent },
                status = {
                    statusCalls += 1
                    if (statusCalls == 1) {
                        AppLinkOperationStatus.Receipt("op-poll", "accepted", receiptRevision = 1L, hint = "Accepted.", tone = "info", appToast = null)
                    } else {
                        AppLinkOperationStatus.Receipt("op-poll", "refused", receiptRevision = 2L, hint = "Refused.", tone = "warning", appToast = null)
                    }
                },
                operationHints = hints,
            )

        val press =
            ThreadAppLinkCardPress(
                card = card,
                actions = host,
                scope = this,
                failedText = "failed",
                undoLabel = "Undo",
                refreshFailedText = "refresh failed",
                stateHintText = localizedHints,
            )

        assertNotNull(press.button(approve, approveAction)).onClick()
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(0, statusCalls)
        assertTrue(press.busy)

        // 1st poll at +1s: still "accepted" (no duplicate toast, stays locked).
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, statusCalls)
        assertEquals(1, toasts.size, "same state does not re-toast")
        assertTrue(press.busy)

        // 2nd poll at +2s: terminal "refused" -> localized warning toast, final reread, unlock.
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(2, statusCalls)
        assertEquals(2, rereads)
        assertEquals(
            listOf<Pair<String, RaftToastIntent?>>(
                "应用已接受，正在完成。" to RaftToastIntent.Info,
                "操作没有执行。" to RaftToastIntent.Warning,
            ),
            toasts,
        )
        assertFalse(press.busy)
    }

    @Test
    fun immediateTerminalExecuteStaysLoadingUntilPostExecuteRereadFinishes() = runTest {
        val rereadGate = CompletableDeferred<Boolean>()
        val host =
            ThreadAppLinkCardActionHost(
                prepare = { _, _ -> error("unused") },
                execute = { _, _, _ ->
                    AppLinkActionResult.Ok(AppLinkExecuted("op-sync", "done", "Done.", "success"))
                },
                reread = { rereadGate.await() },
                toast = {},
            )

        val press =
            ThreadAppLinkCardPress(
                card = card,
                actions = host,
                scope = this,
                failedText = "failed",
                undoLabel = "Undo",
                refreshFailedText = "refresh failed",
                stateHintText = localizedHints,
            )

        assertNotNull(press.button(approve, approveAction)).onClick()
        advanceTimeBy(1_000L)
        runCurrent()

        // While post-execute reread is still in flight, button stays loading & disabled (no stale flash).
        assertTrue(press.busy)
        assertTrue(assertNotNull(press.button(approve, approveAction)).loading)
        assertFalse(assertNotNull(press.button(merge, mergeAction)).enabled)

        rereadGate.complete(true)
        advanceUntilIdle()

        assertFalse(press.busy)
        assertFalse(assertNotNull(press.button(approve, approveAction)).loading)
        assertTrue(assertNotNull(press.button(merge, mergeAction)).enabled)
    }

    @Test
    fun closedStatusEndsFollowingAndRespectsRereadFlag() = runTest {
        var rereads = 0
        val host =
            ThreadAppLinkCardActionHost(
                prepare = { _, _ -> error("unused") },
                execute = { _, _, _ ->
                    AppLinkActionResult.Ok(AppLinkExecuted("op-closed", "sent", "Sent.", "info"))
                },
                reread = {
                    rereads += 1
                    true
                },
                toast = {},
                status = { AppLinkOperationStatus.Closed(reread = true) },
            )

        val press =
            ThreadAppLinkCardPress(
                card = card,
                actions = host,
                scope = this,
                failedText = "failed",
                undoLabel = "Undo",
                refreshFailedText = "refresh failed",
                stateHintText = localizedHints,
            )

        assertNotNull(press.button(approve, approveAction)).onClick()
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(press.busy)
        assertEquals(1, rereads)

        advanceTimeBy(1_000L)
        runCurrent()
        assertFalse(press.busy)
        assertEquals(2, rereads)
    }

    @Test
    fun stateHintMappingMatchesAllProtocolStatesAndFallsBackForUnknown() {
        assertEquals("已发送，正在等待应用。", localizedHints("sent"))
        assertEquals("应用已接受，正在完成。", localizedHints("accepted"))
        assertEquals("已完成。打开应用查看结果。", localizedHints("done"))
        assertEquals("操作没有执行。", localizedHints("refused"))
        assertEquals("操作没有执行。", localizedHints("failed_not_sent"))
        assertEquals("结果尚未确认。", localizedHints("unknown"))
        assertEquals("结果尚未确认。", localizedHints("needs_reconciliation"))
        assertEquals("无法确认结果。", localizedHints("resolved_manually"))
        assertEquals(null, localizedHints("future_state"))
        assertEquals("info", appLinkStateToastTone("accepted"))
        assertEquals("success", appLinkStateToastTone("done"))
        assertEquals("warning", appLinkStateToastTone("refused"))
    }
}
