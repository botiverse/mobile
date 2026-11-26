package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.resources.Res
import build.raft.app.resources.StringDesc
import build.raft.app.resources.desc
import build.raft.app.resources.format
import build.raft.app.resources.resolve
import build.raft.app.resources.upgrade_dialog_history_agent_hits_none
import build.raft.app.resources.upgrade_dialog_history_agent_hits_one
import build.raft.app.resources.upgrade_dialog_history_agent_hits_other
import build.raft.app.resources.upgrade_dialog_history_hint_channel_other
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric Compose UI tests for #wg-mobile-sync:#45 residual (botiverse/slock #14512):
 * the contextual 30-day history upgrade dialog line reports how often this server's agents ran
 * into the history window in the past 7 days (`agentHistoryBlocks7d`), in the same sentence,
 * while 0 / null and non-contextual `History30d` dialogs omit the clause.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryUpgradeAgentHitsUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val originalLocaleType = StringDesc.localeType

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = originalLocaleType
    }

    @Composable
    private fun PageHost(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply {
                    pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftDesignSystemTheme { content() }
        }
    }

    private fun baseRequest(
        historyContext: RaftUpgradeHistoryContext? = null,
        agentHistoryBlocks7d: Int? = null,
    ) = RaftUpgradeRequest(
        gate = RaftUpgradeGate.History30d,
        mode = RaftUpgradeMode.Upgrade,
        pagerId = "p",
        serverId = "s-1",
        serverName = "Acme",
        owner = true,
        webUrl = "https://raft.build/s/acme/settings/billing?upgrade=history_30d&upgradeCanceled=1&lang=en",
        historyContext = historyContext,
        agentHistoryBlocks7d = agentHistoryBlocks7d,
    )

    @Test
    fun contextualChannelDialogShowsPluralAgentHitCount() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        setContent {
            PageHost {
                RaftUpgradeDialog(
                    request =
                        baseRequest(
                            historyContext = RaftUpgradeHistoryContext.Channel("#roadmap", count = 50, capped = false),
                            agentHistoryBlocks7d = 6,
                        ),
                    onOpenWeb = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText(
            "#roadmap has 50 messages older than 30 days that you and your agents can't read on Free, and your agents ran into this 6 times in the past 7 days."
        ).assertIsDisplayed()
    }

    @Test
    fun contextualThreadDialogShowsSingularAgentHitCount() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        setContent {
            PageHost {
                RaftUpgradeDialog(
                    request =
                        baseRequest(
                            historyContext = RaftUpgradeHistoryContext.Thread(count = 3, capped = false),
                            agentHistoryBlocks7d = 1,
                        ),
                    onOpenWeb = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText(
            "This thread has 3 replies older than 30 days that you and your agents can't read on Free, and your agents ran into this once in the past 7 days."
        ).assertIsDisplayed()
    }

    @Test
    fun contextualDialogOmitsHitCountWhenZeroOrNull() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        setContent {
            PageHost {
                RaftUpgradeDialog(
                    request =
                        baseRequest(
                            historyContext = RaftUpgradeHistoryContext.Conversation(count = 1000, capped = true),
                            agentHistoryBlocks7d = 0,
                        ),
                    onOpenWeb = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText(
            "This conversation has over 1,000 messages older than 30 days that you and your agents can't read on Free."
        ).assertIsDisplayed()
        onNodeWithText("ran into this", substring = true).assertDoesNotExist()
    }

    @Test
    fun contextualDialogOmitsHitCountWhenNull() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        setContent {
            PageHost {
                RaftUpgradeDialog(
                    request =
                        baseRequest(
                            historyContext = RaftUpgradeHistoryContext.Channel("#general", count = 1, capped = false),
                            agentHistoryBlocks7d = null,
                        ),
                    onOpenWeb = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText(
            "#general has 1 message older than 30 days that you and your agents can't read on Free."
        ).assertIsDisplayed()
        onNodeWithText("ran into this", substring = true).assertDoesNotExist()
    }

    @Test
    fun nonContextualHistoryGateNeverShowsAgentHitCount() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        setContent {
            PageHost {
                RaftUpgradeDialog(
                    request = baseRequest(historyContext = null, agentHistoryBlocks7d = 6),
                    onOpenWeb = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText(
            "On Free, you and your agents can only read the last 30 days of messages, while on Pro all of Acme's history can be read and searched."
        ).assertIsDisplayed()
        onNodeWithText("ran into this", substring = true).assertDoesNotExist()
    }

    @Test
    fun contextualTargetDialogShowsAgentHitCountInSameSentence() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        setContent {
            PageHost {
                RaftUpgradeDialog(
                    request =
                        baseRequest(
                            historyContext = RaftUpgradeHistoryContext.Target("2026-09-16T12:00:00.000Z"),
                            agentHistoryBlocks7d = 4,
                        ),
                    onOpenWeb = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText(
            "The message you opened was sent on",
            substring = true,
        ).assertIsDisplayed()
        onNodeWithText(
            "so you and your agents can't read it on Free, and your agents ran into this 4 times in the past 7 days.",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun localizedPluralsAndVerbConjugationsMatchWebCopy() {
        // Russian CLDR one / few / many for both count and agent hit count.
        StringDesc.localeType = StringDesc.LocaleType.Custom("ru")
        val ruOne = Res.string.upgrade_dialog_history_agent_hits_one.format(1).resolve()
        val ruFew = Res.string.upgrade_dialog_history_agent_hits_other.format(2).resolve()
        val ruMany = Res.string.upgrade_dialog_history_agent_hits_other.format(6).resolve()
        assertEquals("; за последние 7 дней ваши агенты наткнулись на это 1 раз.", ruOne)
        assertEquals("; за последние 7 дней ваши агенты наткнулись на это 2 раза.", ruFew)
        assertEquals("; за последние 7 дней ваши агенты наткнулись на это 6 раз.", ruMany)
        assertEquals(
            "В канале #roadmap 2 сообщения старше 30 дней, которые вы и ваши агенты не можете прочитать на тарифе Free; за последние 7 дней ваши агенты наткнулись на это 6 раз.",
            Res.string.upgrade_dialog_history_hint_channel_other.format("#roadmap", "2", 30, ruMany).resolve(),
        )

        // Japanese changes verb ending from 読めません。 to 読めず、...。
        StringDesc.localeType = StringDesc.LocaleType.Custom("ja")
        val jaNone = Res.string.upgrade_dialog_history_agent_hits_none.desc().resolve()
        val jaHits = Res.string.upgrade_dialog_history_agent_hits_other.format(6).resolve()
        assertEquals(
            "#roadmap には 30 日より前のメッセージが 50 件あり、無料プランではあなたもエージェントも読めません。",
            Res.string.upgrade_dialog_history_hint_channel_other.format("#roadmap", "50", 30, jaNone).resolve(),
        )
        assertEquals(
            "#roadmap には 30 日より前のメッセージが 50 件あり、無料プランではあなたもエージェントも読めず、過去 7 日間にエージェントが 6 回この制限に引っかかりました。",
            Res.string.upgrade_dialog_history_hint_channel_other.format("#roadmap", "50", 30, jaHits).resolve(),
        )

        // Korean changes verb ending from 없어요. to 없고, ...
        StringDesc.localeType = StringDesc.LocaleType.Custom("ko")
        val koNone = Res.string.upgrade_dialog_history_agent_hits_none.desc().resolve()
        val koOne = Res.string.upgrade_dialog_history_agent_hits_one.format(1).resolve()
        assertEquals(
            "#roadmap에 30일이 지난 메시지가 50개 있는데, 무료 요금제에서는 나와 내 에이전트 모두 읽을 수 없어요.",
            Res.string.upgrade_dialog_history_hint_channel_other.format("#roadmap", "50", 30, koNone).resolve(),
        )
        assertEquals(
            "#roadmap에 30일이 지난 메시지가 50개 있는데, 무료 요금제에서는 나와 내 에이전트 모두 읽을 수 없고, 지난 7일 동안 에이전트가 이 때문에 한 번 막혔어요.",
            Res.string.upgrade_dialog_history_hint_channel_other.format("#roadmap", "50", 30, koOne).resolve(),
        )
    }
}
