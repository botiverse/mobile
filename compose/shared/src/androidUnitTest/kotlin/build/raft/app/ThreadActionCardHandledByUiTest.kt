package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadActionCardHandledBy
import build.raft.app.data.ThreadActionCardQuestion
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.Res
import build.raft.app.resources.StringDesc
import build.raft.app.resources.action_card_question_handled_line
import build.raft.app.resources.action_card_question_handled_line_link
import build.raft.app.resources.action_card_question_status_handled
import build.raft.app.resources.desc
import build.raft.app.resources.format
import build.raft.app.resources.resolve
import build.raft.app.thread.LocalThreadActionCardNames
import build.raft.app.thread.LocalThreadActionCardSpecHost
import build.raft.app.thread.LocalThreadViewerServerId
import build.raft.app.thread.RaftActionCardFrameState
import build.raft.app.thread.RaftActionCardView
import build.raft.app.thread.ThreadActionCardEntityKind
import build.raft.app.thread.ThreadActionCardNameLookup
import build.raft.app.thread.ThreadActionCardSpecHost
import build.raft.app.thread.ThreadActionCardSpecView
import build.raft.app.thread.ThreadActionCardView
import build.raft.app.thread.actionCardSpecStatus
import build.raft.app.thread.actionCardSpecToRender
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI behavior for question cards withdrawn as handled by a person's message (`handledBy`,
 * botiverse/slock #17265): "Handled" status badge vs "Withdrawn", localized "Handled via
 * {person}'s message." ended line with clickable link (beside the card or in its thread), member
 * display name + handle fallback, graceful non-linked fallback without a navigator, and 8-locale
 * string parity.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadActionCardHandledByUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val originalLocaleType = StringDesc.localeType

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = originalLocaleType
    }

    private val nowMillis = 1_794_218_580_000L // 2026-11-09 10:03 UTC
    private val futureExpiresAt = "2026-11-16T10:03:00.000Z"
    private val names =
        ThreadActionCardNameLookup { kind, id ->
            when {
                kind == ThreadActionCardEntityKind.Human && id == "u-mei" -> "Mei Lin"
                kind == ThreadActionCardEntityKind.Human && id == "u-maya" -> "Maya"
                else -> null
            }
        }

    private fun buttonRowCard(
        state: String = "withdrawn",
        handledBy: ThreadActionCardHandledBy? = null,
    ) = ThreadActionCard(
        type = "question:ask",
        state = state,
        confirmationVersion = 3,
        presentationCardJson = ActionCardSpecFixtures.questionCardJson(),
        expiresAt = futureExpiresAt,
        sourceServerId = "s-1",
        questionAddresseeUserId = "u-maya",
        questionAddresseeHandle = "maya",
        questions = listOf(ThreadActionCardQuestion("single", 3)),
        handledBy = handledBy,
    )

    private fun slimCard(
        state: String = "withdrawn",
        handledBy: ThreadActionCardHandledBy? = null,
    ): ThreadActionCard {
        val fiveOptions = listOf("Fix all 4 on a branch", "One at a time", "Just list them", "Skip for now", "Ask Ada first")
        return ThreadActionCard(
            type = "question:ask",
            state = state,
            confirmationVersion = 3,
            presentationCardJson = ActionCardSpecFixtures.questionCardJson(options = fiveOptions),
            expiresAt = futureExpiresAt,
            sourceServerId = "s-1",
            questionAddresseeUserId = "u-maya",
            questionAddresseeHandle = "maya",
            questions = listOf(ThreadActionCardQuestion("single", 5)),
            handledBy = handledBy,
        )
    }

    private fun ComposeUiTest.mountSpecCard(
        cardState: () -> ThreadActionCard,
        resolveHandledByOpen: ((String, ThreadActionCardHandledBy) -> (() -> Unit)?)? = null,
    ) {
        val timeContext = RaftTimeContext(localOffsetMinutes = 0, todayEpochDay = raftEpochDayForDate(2026, 11, 9), nowEpochMillis = nowMillis)
        setContent {
            val current = cardState()
            val spec = assertNotNull(current.actionCardSpecToRender(capabilities = RAFT_APP_ACTION_CARD_CAPABILITIES))
            RaftTheme(appearance = RaftAppearance.Light) {
                CompositionLocalProvider(
                    LocalThreadActionCardSpecHost provides
                        ThreadActionCardSpecHost(
                            viewerUserId = "u-maya",
                            resolveHandledByOpen = resolveHandledByOpen,
                        ),
                    LocalThreadViewerServerId provides "s-1",
                    LocalThreadActionCardNames provides names,
                    LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
                ) {
                    ThreadActionCardSpecView(
                        messageId = "m-q",
                        card = current,
                        spec = spec,
                        commitEnabled = true,
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
    fun handledStatusBadgeReadsHandledInsteadOfWithdrawnOnFramedCards() = runRobolectricComposeUiTest {
        val handledBy = ThreadActionCardHandledBy(messageId = "m-2", userId = "u-mei", handle = "mei", inCardThread = false)
        var current by mutableStateOf(buttonRowCard(state = "withdrawn", handledBy = handledBy))
        setContent {
            val card = current
            val spec = assertNotNull(card.actionCardSpecToRender(capabilities = RAFT_APP_ACTION_CARD_CAPABILITIES))
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftActionCardView(
                    card = spec,
                    state = RaftActionCardFrameState(status = card.actionCardSpecStatus()),
                    entityName = { _, _ -> null },
                    onAction = {},
                    displayLocale = "en",
                )
            }
        }

        onNodeWithText("Handled").assertExists()
        onNodeWithText("Withdrawn").assertDoesNotExist()

        current = buttonRowCard(state = "withdrawn", handledBy = null)
        waitForIdle()
        onNodeWithText("Withdrawn").assertExists()
        onNodeWithText("Handled").assertDoesNotExist()
    }

    @Test
    fun fallbackTypedCardShowsHandledBadgeWhenHandledByIsPresent() = runRobolectricComposeUiTest {
        val handledBy = ThreadActionCardHandledBy(messageId = "m-2", userId = "u-mei", handle = "mei", inCardThread = false)
        var current by mutableStateOf(buttonRowCard(state = "withdrawn", handledBy = handledBy))
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                ThreadActionCardView(
                    card = current,
                    pending = false,
                    onCommit = {},
                )
            }
        }

        onNodeWithText("Handled").assertExists()
        onNodeWithText("Withdrawn").assertDoesNotExist()

        current = buttonRowCard(state = "withdrawn", handledBy = null)
        waitForIdle()
        onNodeWithText("Withdrawn").assertExists()
        onNodeWithText("Handled").assertDoesNotExist()
    }

    @Test
    fun buttonRowHandledLineResolvesNameLinksConversationAndThreadAndFallsBackCleanly() = runRobolectricComposeUiTest {
        val opened = mutableListOf<Pair<String, ThreadActionCardHandledBy>>()
        val beside = ThreadActionCardHandledBy(messageId = "m-2", userId = "u-mei", handle = "mei", inCardThread = false)
        val inThread = ThreadActionCardHandledBy(messageId = "m-3", userId = "u-mei", handle = "mei", inCardThread = true)
        val formerMember = ThreadActionCardHandledBy(messageId = "m-4", userId = "u-gone", handle = "lin", inCardThread = false)
        var current by mutableStateOf(buttonRowCard(state = "withdrawn", handledBy = beside))
        mountSpecCard(
            cardState = { current },
            resolveHandledByOpen = { cardId, target -> { opened += cardId to target } },
        )

        // Same-conversation handling message: resolves display name and opens conversation target.
        onNodeWithText("Handled via Mei Lin's message.").assertExists()
        onNodeWithText("Mei Lin's message").performClick()
        assertEquals(listOf("m-q" to beside), opened)

        // In-card-thread handling message: clicking invokes navigation with inCardThread = true.
        current = buttonRowCard(state = "withdrawn", handledBy = inThread)
        waitForIdle()
        onNodeWithText("Handled via Mei Lin's message.").assertExists()
        onNodeWithText("Mei Lin's message").performClick()
        assertEquals(listOf("m-q" to beside, "m-q" to inThread), opened)

        // Former member not in roster: falls back to stored handle.
        current = buttonRowCard(state = "withdrawn", handledBy = formerMember)
        waitForIdle()
        onNodeWithText("Handled via lin's message.").assertExists()
        onNodeWithText("lin's message").performClick()
        assertEquals(listOf("m-q" to beside, "m-q" to inThread, "m-q" to formerMember), opened)

        // Plain withdrawn without handledBy: unchanged copy, no handled link.
        current = buttonRowCard(state = "withdrawn", handledBy = null)
        waitForIdle()
        onNodeWithText("Fixer withdrew this question.").assertExists()
        onNodeWithText("Handled via", substring = true).assertDoesNotExist()
    }

    @Test
    fun slimCardHandledLineLinksInThreadAndSameConversation() = runRobolectricComposeUiTest {
        val opened = mutableListOf<Pair<String, ThreadActionCardHandledBy>>()
        val beside = ThreadActionCardHandledBy(messageId = "m-2", userId = "u-mei", handle = "mei", inCardThread = false)
        val inThread = ThreadActionCardHandledBy(messageId = "m-3", userId = "u-mei", handle = "mei", inCardThread = true)
        var current by mutableStateOf(slimCard(state = "withdrawn", handledBy = beside))
        mountSpecCard(
            cardState = { current },
            resolveHandledByOpen = { cardId, target -> { opened += cardId to target } },
        )

        onNodeWithText("Handled via Mei Lin's message.").assertExists()
        onNodeWithText("Mei Lin's message").performClick()
        assertEquals(listOf("m-q" to beside), opened)

        current = slimCard(state = "withdrawn", handledBy = inThread)
        waitForIdle()
        onNodeWithText("Mei Lin's message").performClick()
        assertEquals(listOf("m-q" to beside, "m-q" to inThread), opened)

        current = slimCard(state = "withdrawn", handledBy = null)
        waitForIdle()
        onNodeWithText("Fixer withdrew this question.").assertExists()
        onNodeWithText("Handled via", substring = true).assertDoesNotExist()
    }

    @Test
    fun handledLineWithoutNavigationCallbackRendersPlainTextWithoutLinkNode() = runRobolectricComposeUiTest {
        val beside = ThreadActionCardHandledBy(messageId = "m-2", userId = "u-mei", handle = "mei", inCardThread = false)
        mountSpecCard(
            cardState = { buttonRowCard(state = "withdrawn", handledBy = beside) },
            resolveHandledByOpen = null,
        )

        onNodeWithText("Handled via Mei Lin's message.").assertExists()
        onNodeWithText("Mei Lin's message").assertDoesNotExist()
    }

    @Test
    fun allEightLocalesMatchSlockHandledStatusAndLinkedLineStrings() {
        val expected =
            mapOf(
                "en" to Triple("Handled", "Handled via Mei Lin's message.", "Mei Lin's message"),
                "zh-CN" to Triple("已处理", "已按 Mei Lin 的消息处理。", "Mei Lin 的消息"),
                "zh-TW" to Triple("已處理", "已依 Mei Lin 的訊息處理。", "Mei Lin 的訊息"),
                "ja" to Triple("対応済み", "Mei Lin のメッセージで対応済みです。", "Mei Lin のメッセージ"),
                "ko" to Triple("처리됨", "Mei Lin의 메시지로 처리했어요.", "Mei Lin의 메시지"),
                "es" to Triple("Atendida", "Atendida con el mensaje de Mei Lin.", "el mensaje de Mei Lin"),
                "pt-BR" to Triple("Resolvida", "Resolvida pela mensagem de Mei Lin.", "mensagem de Mei Lin"),
                "ru" to Triple("Решён", "Решён сообщением Mei Lin.", "сообщением Mei Lin"),
            )
        expected.forEach { (tag, strings) ->
            val (expectedBadge, expectedSentence, expectedLink) = strings
            StringDesc.localeType = StringDesc.LocaleType.Custom(tag)
            assertEquals(expectedBadge, Res.string.action_card_question_status_handled.desc().resolve(), "$tag status_handled")
            val linkText = Res.string.action_card_question_handled_line_link.format("Mei Lin").resolve()
            assertEquals(expectedLink, linkText, "$tag handled_line_link")
            assertEquals(expectedSentence, Res.string.action_card_question_handled_line.format(linkText).resolve(), "$tag handled_line plain")
            val slotted = Res.string.action_card_question_handled_line.format(RAFT_LINK_SLOT).resolve()
            val annotated = raftSentenceWithLink(slotted, linkText) {}
            assertEquals(expectedSentence, annotated.text, "$tag handled_line annotated")
            val linkRange = assertNotNull(annotated.getLinkAnnotations(0, annotated.length).singleOrNull(), "$tag link annotation")
            assertEquals(expectedLink, annotated.text.substring(linkRange.start, linkRange.end), "$tag link span")
        }
    }
}
