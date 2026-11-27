package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.ActionCardCloseOutcome
import build.raft.app.data.ActionCardExecuteOutcome
import build.raft.app.data.DataResult
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.ScreenError
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadActionCardClosedBy
import build.raft.app.data.ThreadActionCardQuestion
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.Res
import build.raft.app.resources.StringDesc
import build.raft.app.resources.action_card_question_closed_line
import build.raft.app.resources.action_card_question_undo
import build.raft.app.resources.common_close
import build.raft.app.resources.desc
import build.raft.app.resources.format
import build.raft.app.resources.resolve
import build.raft.app.resources.thread_action_card_status_closed
import build.raft.app.thread.ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG
import build.raft.app.thread.ACTION_CARD_QUESTION_CLOSE_UNDO_ROW_TAG
import build.raft.app.thread.LocalThreadActionCardNames
import build.raft.app.thread.LocalThreadActionCardSpecHost
import build.raft.app.thread.LocalThreadViewerServerId
import build.raft.app.thread.QUESTION_SUBMIT_UNDO_MS
import build.raft.app.thread.ThreadActionCardEntityKind
import build.raft.app.thread.ThreadActionCardNameLookup
import build.raft.app.thread.ThreadActionCardSpecHost
import build.raft.app.thread.ThreadActionCardSpecState
import build.raft.app.thread.ThreadActionCardSpecStore
import build.raft.app.thread.ThreadActionCardSpecView
import build.raft.app.thread.actionCardSpecToRender
import build.raft.app.thread.dispatch
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI behavior for answerer close (`×` + 1.5 s "Closed · Undo" + `POST /actions/:messageId/close`)
 * on question cards (botiverse/slock #16473, `questionCardClose.behavior.test.tsx`), covering both
 * ButtonRow (C) and SlimCard (B) layouts, permission/state gates, Undo cancellation, concurrent end
 * cancellation, refusal recovery, and 8-locale string parity.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadActionCardQuestionCloseUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val originalLocaleType = StringDesc.localeType

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = originalLocaleType
    }

    private val UNDO_MS = QUESTION_SUBMIT_UNDO_MS
    private val nowMillis = 1_794_218_580_000L // 2026-11-09 10:03 UTC
    private val futureExpiresAt = "2026-11-16T10:03:00.000Z"
    private val pastExpiresAt = "2026-11-01T10:03:00.000Z"
    private val names =
        ThreadActionCardNameLookup { kind, id ->
            when {
                kind == ThreadActionCardEntityKind.Human && id == "u-maya" -> "Maya"
                kind == ThreadActionCardEntityKind.Human && id == "u-ada" -> "Ada"
                else -> null
            }
        }

    private fun ComposeUiTest.advance(millis: Long) {
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeBy(millis)
        waitForIdle()
    }

    private fun buttonRowCard(
        state: String = "prepared",
        answererCanClose: Boolean = true,
        addresseeUserId: String? = "u-maya",
        asksAnyone: Boolean = false,
        expiresAt: String? = futureExpiresAt,
        sourceServerId: String? = "s-1",
        requesterUserId: String? = null,
        closedBy: ThreadActionCardClosedBy? = null,
        executedBy: String? = null,
    ) = ThreadActionCard(
        type = "question:ask",
        state = state,
        confirmationVersion = 3,
        presentationCardJson = ActionCardSpecFixtures.questionCardJson(),
        expiresAt = expiresAt,
        sourceServerId = sourceServerId,
        requesterUserId = requesterUserId,
        questionAddresseeUserId = if (asksAnyone) null else addresseeUserId,
        questionAddresseeHandle = if (asksAnyone) null else "maya",
        questionAsksAnyone = asksAnyone,
        questions = listOf(ThreadActionCardQuestion("single", 3)),
        answererCanClose = answererCanClose,
        closedAt = closedBy?.let { "2026-11-09T10:05:00.000Z" },
        closedBy = closedBy,
        executedByUserName = executedBy,
    )

    private fun slimCard(
        state: String = "prepared",
        answererCanClose: Boolean = true,
        addresseeUserId: String? = "u-maya",
        asksAnyone: Boolean = false,
    ): ThreadActionCard {
        val fiveOptions = listOf("Fix all 4 on a branch", "One at a time", "Just list them", "Skip for now", "Ask Ada first")
        return ThreadActionCard(
            type = "question:ask",
            state = state,
            confirmationVersion = 3,
            presentationCardJson = ActionCardSpecFixtures.questionCardJson(options = fiveOptions),
            expiresAt = futureExpiresAt,
            sourceServerId = "s-1",
            questionAddresseeUserId = if (asksAnyone) null else addresseeUserId,
            questionAddresseeHandle = if (asksAnyone) null else "maya",
            questionAsksAnyone = asksAnyone,
            questions = listOf(ThreadActionCardQuestion("single", 5)),
            answererCanClose = answererCanClose,
        )
    }

    private fun ComposeUiTest.mountCard(
        cardState: () -> ThreadActionCard,
        viewer: () -> String = { "u-maya" },
        viewerServerId: String = "s-1",
        commitEnabled: () -> Boolean = { true },
        visible: () -> Boolean = { true },
        store: ThreadActionCardSpecStore? = null,
    ) {
        val timeContext = RaftTimeContext(localOffsetMinutes = 0, todayEpochDay = raftEpochDayForDate(2026, 11, 9), nowEpochMillis = nowMillis)
        setContent {
            if (!visible()) return@setContent
            val current = cardState()
            val spec = assertNotNull(current.actionCardSpecToRender(capabilities = RAFT_APP_ACTION_CARD_CAPABILITIES))
            RaftTheme(appearance = RaftAppearance.Light) {
                CompositionLocalProvider(
                    LocalThreadActionCardSpecHost provides
                        ThreadActionCardSpecHost(
                            state = store?.state ?: MutableStateFlow(ThreadActionCardSpecState()),
                            onIntent = store?.let { it::dispatch } ?: {},
                            viewerUserId = viewer(),
                        ),
                    LocalThreadViewerServerId provides viewerServerId,
                    LocalThreadActionCardNames provides names,
                    LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
                ) {
                    ThreadActionCardSpecView(
                        messageId = "m-q",
                        card = current,
                        spec = spec,
                        commitEnabled = commitEnabled(),
                        typedPending = false,
                        executedTimeLabel = null,
                        askerName = "Fixer",
                        onAction = {},
                    )
                }
            }
        }
    }

    @Test
    fun closeButtonShowsOnlyWhenServerAllowsAndViewerMayAnswerOpenQuestionCard() = runRobolectricComposeUiTest {
        var current by mutableStateOf(buttonRowCard())
        var viewer by mutableStateOf("u-maya")
        var commitEnabled by mutableStateOf(true)
        mountCard(cardState = { current }, viewer = { viewer }, commitEnabled = { commitEnabled })

        // Open card, server sent answererCanClose, viewer is the person asked -> × visible and enabled.
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).assertExists().assertIsEnabled()
        onNodeWithContentDescription("Close").assertExists()

        // Older server without answererCanClose -> no ×.
        current = buttonRowCard(answererCanClose = false)
        waitForIdle()
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).assertDoesNotExist()

        // Past deadline -> no ×.
        current = buttonRowCard(expiresAt = pastExpiresAt)
        waitForIdle()
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).assertDoesNotExist()

        // Non-prepared states -> no ×.
        for (state in listOf("executed", "closed", "withdrawn", "expired", "frozen", "reconfirm_required")) {
            current = buttonRowCard(state = state)
            waitForIdle()
            onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).assertDoesNotExist()
        }

        // Another card type never shows × even if answererCanClose is true.
        current =
            ThreadActionCard(
                type = "channel:create",
                state = "prepared",
                name = "launch",
                sourceServerId = "s-1",
                presentationCardJson =
                    """
                    {"catalog":"action-card.v1","title":{"key":"actionCard.test.t","text":{"en":"Create channel"}},
                     "spec":{"root":"go","elements":{
                       "go":{"type":"Button","props":{"label":{"key":"actionCard.test.go","text":{"en":"Create"}},"emphasis":"primary","action":{"kind":"execute"}}}
                     }}}
                    """.trimIndent(),
                answererCanClose = true,
            )
        waitForIdle()
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).assertDoesNotExist()

        // Cross-server card -> no ×.
        current = buttonRowCard(sourceServerId = "s-other")
        waitForIdle()
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).assertDoesNotExist()

        // Own request -> no ×.
        current = buttonRowCard(requesterUserId = "u-maya")
        waitForIdle()
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).assertDoesNotExist()

        // Read-only thread -> no ×.
        current = buttonRowCard()
        commitEnabled = false
        waitForIdle()
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).assertDoesNotExist()

        // Another viewer cannot close a card asked of Maya, but CAN close one asked of anyone.
        commitEnabled = true
        viewer = "u-ada"
        waitForIdle()
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).assertDoesNotExist()

        current = buttonRowCard(asksAnyone = true)
        waitForIdle()
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).assertExists().assertIsEnabled()

        // SlimCard (B) also shows × to who may answer and hides it from others.
        current = slimCard(asksAnyone = false)
        waitForIdle()
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).assertDoesNotExist()
        viewer = "u-maya"
        waitForIdle()
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).assertExists().assertIsEnabled()
    }

    @Test
    fun closeWaitsOneAndAHalfSecondsWithUndoDisablesAnswersAndCommitsOnceOnButtonRow() = runRobolectricComposeUiTest {
        val closeCalls = mutableListOf<String>()
        var current by mutableStateOf(buttonRowCard())
        val closedFromServer = buttonRowCard(state = "expired", answererCanClose = false, closedBy = ThreadActionCardClosedBy("u-maya", "maya"))
        val store =
            ThreadActionCardSpecStore(
                scope = CoroutineScope(Dispatchers.Unconfined),
                execute = { _, _, _ -> DataResult.Content(ActionCardExecuteOutcome.Executed(null)) },
                closeQuestion = { messageId, _ ->
                    closeCalls += messageId
                    DataResult.Content(ActionCardCloseOutcome.Closed(closedFromServer))
                },
                applyCard = { _, next -> current = next },
                nowMillis = { nowMillis },
            )
        mainClock.autoAdvance = false
        mountCard(cardState = { current }, store = store)
        advance(50)

        // Tap × -> "Closed · Undo" replaces ×, answer options are disabled, nothing sent while waiting.
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).performClick()
        advance(50)
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).assertDoesNotExist()
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_UNDO_ROW_TAG).assertExists()
        onNodeWithText("Closed ·").assertExists()
        onNodeWithText("Undo").assertExists()
        onNodeWithText("Fix all 4 on a branch").assertIsNotEnabled()
        onNodeWithText("Other").assertIsNotEnabled()
        advance(UNDO_MS - 150)
        assertEquals(emptyList(), closeCalls, "nothing sent during Undo window")

        // Tap Undo -> back to open card with ×, options re-enabled, nothing ever sent.
        onNodeWithText("Undo").performClick()
        advance(50)
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_UNDO_ROW_TAG).assertDoesNotExist()
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).assertExists().assertIsEnabled()
        onNodeWithText("Fix all 4 on a branch").assertIsEnabled()
        advance(UNDO_MS * 3)
        assertEquals(emptyList(), closeCalls, "undone close is never sent")

        // Tap × again and wait out the 1.5 s window -> POST fires once, card turns closed.
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).performClick()
        advance(UNDO_MS - 100)
        assertEquals(emptyList(), closeCalls, "still waiting before 1500ms")
        advance(200)
        assertEquals(listOf("m-q"), closeCalls)
        advance(UNDO_MS * 3)
        assertEquals(1, closeCalls.size, "sent once")
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).assertDoesNotExist()
        onNodeWithText("Maya closed this question.").assertExists()
    }

    @Test
    fun slimCardHeaderShowsCloseControlAndDisablesOptionsDuringUndoWindow() = runRobolectricComposeUiTest {
        val closeCalls = mutableListOf<String>()
        var current by mutableStateOf(slimCard())
        val closedFromServer =
            slimCard(state = "expired", answererCanClose = false)
                .copy(closedAt = "2026-11-09T10:05:00.000Z", closedBy = ThreadActionCardClosedBy("u-maya", "maya"))
        val store =
            ThreadActionCardSpecStore(
                scope = CoroutineScope(Dispatchers.Unconfined),
                execute = { _, _, _ -> DataResult.Content(ActionCardExecuteOutcome.Executed(null)) },
                closeQuestion = { messageId, _ ->
                    closeCalls += messageId
                    DataResult.Content(ActionCardCloseOutcome.Closed(closedFromServer))
                },
                applyCard = { _, next -> current = next },
                nowMillis = { nowMillis },
            )
        mainClock.autoAdvance = false
        mountCard(cardState = { current }, store = store)
        advance(50)

        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).assertExists().performClick()
        advance(50)
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_UNDO_ROW_TAG).assertExists()
        onNodeWithText("Closed ·").assertExists()
        onNodeWithText("Undo").assertExists()
        advance(UNDO_MS)
        assertEquals(listOf("m-q"), closeCalls)
        onNodeWithText("Maya closed this question.").assertExists()
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).assertDoesNotExist()
    }

    @Test
    fun aCardThatEndedWhileCloseWaitedCancelsWithoutSending() = runRobolectricComposeUiTest {
        val closeCalls = mutableListOf<String>()
        var current by mutableStateOf(buttonRowCard())
        val store =
            ThreadActionCardSpecStore(
                scope = CoroutineScope(Dispatchers.Unconfined),
                execute = { _, _, _ -> error("not used") },
                closeQuestion = { messageId, _ ->
                    closeCalls += messageId
                    DataResult.Content(ActionCardCloseOutcome.Closed(null))
                },
                applyCard = { _, next -> current = next },
                nowMillis = { nowMillis },
            )
        mainClock.autoAdvance = false
        mountCard(cardState = { current }, store = store)
        advance(50)

        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).performClick()
        advance(300)
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_UNDO_ROW_TAG).assertExists()

        // Answered by someone else meanwhile.
        current =
            buttonRowCard(state = "executed", answererCanClose = false, executedBy = "Ada")
                .copy(presentationCardJson = ActionCardSpecFixtures.questionCardAnsweredJson())
        advance(50)
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_UNDO_ROW_TAG).assertDoesNotExist()
        advance(UNDO_MS * 2)
        assertEquals(emptyList(), closeCalls, "must not post close after card ended")
    }

    @Test
    fun leavingWhileCloseWaitsFlushesOnceImmediately() = runRobolectricComposeUiTest {
        val closeCalls = mutableListOf<String>()
        var visible by mutableStateOf(true)
        val store =
            ThreadActionCardSpecStore(
                scope = CoroutineScope(Dispatchers.Unconfined),
                execute = { _, _, _ -> error("not used") },
                closeQuestion = { messageId, _ ->
                    closeCalls += messageId
                    DataResult.Content(ActionCardCloseOutcome.Closed(null))
                },
                applyCard = { _, _ -> },
                nowMillis = { nowMillis },
            )
        mainClock.autoAdvance = false
        mountCard(cardState = { buttonRowCard() }, visible = { visible }, store = store)
        advance(50)

        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).performClick()
        advance(200)
        assertEquals(emptyList(), closeCalls)

        // Card leaves composition while "Closed · Undo" is waiting -> flushes immediately once.
        visible = false
        advance(50)
        assertEquals(listOf("m-q"), closeCalls)
        advance(UNDO_MS * 3)
        assertEquals(1, closeCalls.size, "flushed once")
    }

    @Test
    fun serverRefusalShowsBannerAndRecoversAndAlreadyExecutedAppliesWithoutError() = runRobolectricComposeUiTest {
        val withdrawnMessage = "This question was already withdrawn; it was not closed"
        val answers =
            ArrayDeque<DataResult<ActionCardCloseOutcome>>(
                listOf(
                    DataResult.Error(
                        ScreenError(
                            code = "action_close_contract_mismatch",
                            retryable = false,
                            serverCode = "QUESTION_WITHDRAWN",
                            serverMessage = withdrawnMessage,
                        ),
                    ),
                    DataResult.Content(
                        ActionCardCloseOutcome.AlreadyExecuted(
                            card =
                                buttonRowCard(state = "executed", answererCanClose = false, executedBy = "Ada")
                                    .copy(presentationCardJson = ActionCardSpecFixtures.questionCardAnsweredJson()),
                            serverMessage = "This question was already answered; it was not closed",
                        ),
                    ),
                ),
            )
        var current by mutableStateOf(buttonRowCard())
        val store =
            ThreadActionCardSpecStore(
                scope = CoroutineScope(Dispatchers.Unconfined),
                execute = { _, _, _ -> error("not used") },
                closeQuestion = { _, _ -> answers.removeFirst() },
                applyCard = { _, next -> current = next },
                nowMillis = { nowMillis },
            )
        mainClock.autoAdvance = false
        mountCard(cardState = { current }, store = store)
        advance(50)

        // First close fails with QUESTION_WITHDRAWN -> banner shows server reason and × recovers.
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).performClick()
        advance(UNDO_MS + 50)
        onNodeWithText(withdrawnMessage).assertExists()
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).assertExists().assertIsEnabled()

        // Second close hits ALREADY_EXECUTED -> answered card applied, no error banner shown.
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).performClick()
        advance(UNDO_MS + 50)
        onNodeWithText("already answered", substring = true).assertDoesNotExist()
        onNodeWithTag(ACTION_CARD_QUESTION_CLOSE_BUTTON_TAG).assertDoesNotExist()
        onNodeWithText("Ada answered", substring = true).assertExists()
    }

    @Test
    fun allEightLocalesMatchSlockCloseAndUndoStrings() {
        val expected =
            mapOf(
                "en" to listOf("Close", "Closed", "Undo", "Maya closed this question."),
                "zh-CN" to listOf("关闭", "已关闭", "撤销", "Maya 关闭了问题。"),
                "zh-TW" to listOf("關閉", "已關閉", "復原", "Maya 關閉了問題。"),
                "ja" to listOf("閉じる", "クローズ済み", "元に戻す", "Maya が質問をクローズしました。"),
                "ko" to listOf("닫기", "닫힘", "실행 취소", "Maya 님이 질문을 닫았어요."),
                "es" to listOf("Cerrar", "Cerrada", "Deshacer", "Maya cerró esta pregunta."),
                "pt-BR" to listOf("Fechar", "Fechada", "Desfazer", "Maya fechou esta pergunta."),
                "ru" to listOf("Закрыть", "Закрыт", "Отменить", "Вопрос закрыт: Maya."),
            )
        expected.forEach { (tag, strings) ->
            StringDesc.localeType = StringDesc.LocaleType.Custom(tag)
            assertEquals(strings[0], Res.string.common_close.desc().resolve(), "$tag common_close")
            assertEquals(strings[1], Res.string.thread_action_card_status_closed.desc().resolve(), "$tag status_closed")
            assertEquals(strings[2], Res.string.action_card_question_undo.desc().resolve(), "$tag undo")
            assertEquals(strings[3], Res.string.action_card_question_closed_line.format("Maya").resolve(), "$tag closed_line")
        }
    }
}
