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
import build.raft.app.resources.format
import build.raft.app.resources.resolve
import build.raft.app.resources.upgrade_dialog_upload_quota_agent_hits_limit_one
import build.raft.app.resources.upgrade_dialog_upload_quota_agent_hits_limit_other
import build.raft.app.resources.upgrade_dialog_upload_quota_agent_hits_used_up_one
import build.raft.app.resources.upgrade_dialog_upload_quota_agent_hits_used_up_other
import build.raft.app.resources.upgrade_dialog_upload_quota_hint_files
import build.raft.app.resources.upgrade_dialog_upload_quota_hint_files_with_agent_hits
import build.raft.app.resources.upgrade_dialog_upload_quota_hint_used_up_with_agent_hits
import build.raft.app.settings.RaftUpgradeDialog
import build.raft.app.settings.RaftUpgradeGate
import build.raft.app.settings.RaftUpgradeMode
import build.raft.app.settings.RaftUpgradeRequest
import build.raft.app.settings.RaftUpgradeUploadContext
import build.raft.app.settings.RaftUpgradeUploadFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric Compose UI & 8-locale tests for #wg-mobile-sync:#345 (botiverse/slock #14968):
 * the contextual monthly upload quota upgrade dialog reports how often this server's agents were
 * refused by the monthly upload limit in the past 7 days (`agentUploadQuotaBlocks7d`), while
 * 0 / null, non-contextual dialogs, and iOS `LimitNotice` omit it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadQuotaUpgradeAgentHitsUiTest {
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
        mode: RaftUpgradeMode = RaftUpgradeMode.Upgrade,
        uploadContext: RaftUpgradeUploadContext? = null,
        agentUploadQuotaBlocks7d: Int? = null,
    ) = RaftUpgradeRequest(
        gate = RaftUpgradeGate.UploadMonthlyQuota,
        mode = mode,
        pagerId = "p",
        serverId = "s-1",
        serverName = "Acme",
        owner = true,
        webUrl = "https://raft.build/s/acme/settings/billing?upgrade=upload_monthly_quota&upgradeCanceled=1&lang=en",
        uploadContext = uploadContext,
        agentUploadQuotaBlocks7d = agentUploadQuotaBlocks7d,
    )

    private val mib = 1024L * 1024L

    @Test
    fun usedUpLineAddsSingularAndPluralAgentRefusalsAndOmitsZero() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        setContent {
            PageHost {
                RaftUpgradeDialog(
                    request =
                        baseRequest(
                            uploadContext =
                                RaftUpgradeUploadContext.MonthlyQuota(
                                    month = "2026-11",
                                    limitBytes = 100L * mib,
                                    remainingBytes = 0L,
                                    files = emptyList(),
                                ),
                            agentUploadQuotaBlocks7d = 6,
                        ),
                    onOpenWeb = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText("This month's uploads are used up").assertIsDisplayed()
        onNodeWithText(
            "Acme has used this month's 100 MB of uploads, and your agents ran into this 6 times in the past 7 days; it resets on Dec 1. Pro has no monthly limit."
        ).assertIsDisplayed()
    }

    @Test
    fun usedUpLineShowsSingularAgentRefusalAndDecemberRollsOverToJanuary() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        setContent {
            PageHost {
                RaftUpgradeDialog(
                    request =
                        baseRequest(
                            uploadContext =
                                RaftUpgradeUploadContext.MonthlyQuota(
                                    month = "2026-12",
                                    limitBytes = 100L * mib,
                                    remainingBytes = 0L,
                                    files = emptyList(),
                                ),
                            agentUploadQuotaBlocks7d = 1,
                        ),
                    onOpenWeb = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText(
            "Acme has used this month's 100 MB of uploads, and your agents ran into this once in the past 7 days; it resets on Jan 1. Pro has no monthly limit."
        ).assertIsDisplayed()
    }

    @Test
    fun singleFileShortLineSwitchesTitleAndAppendsAgentSentence() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        setContent {
            PageHost {
                RaftUpgradeDialog(
                    request =
                        baseRequest(
                            uploadContext =
                                RaftUpgradeUploadContext.MonthlyQuota(
                                    month = "2026-11",
                                    limitBytes = 100L * mib,
                                    remainingBytes = 2L * mib,
                                    files = listOf(RaftUpgradeUploadFile("demo.mp4", 12L * mib)),
                                ),
                            agentUploadQuotaBlocks7d = 6,
                        ),
                    onOpenWeb = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText("Not enough uploads left this month").assertIsDisplayed()
        onNodeWithText(
            "demo.mp4 is 12 MB, and Acme has 2 MB left of this month's 100 MB of uploads; it resets on Dec 1. Your agents ran into this limit 6 times in the past 7 days. Pro has no monthly limit."
        ).assertIsDisplayed()
    }

    @Test
    fun multipleFilesLineAppendsSingularAgentSentenceAndOmitsWhenZeroOrNull() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        setContent {
            PageHost {
                RaftUpgradeDialog(
                    request =
                        baseRequest(
                            uploadContext =
                                RaftUpgradeUploadContext.MonthlyQuota(
                                    month = "2026-11",
                                    limitBytes = 100L * mib,
                                    remainingBytes = 2L * mib,
                                    files =
                                        listOf(
                                            RaftUpgradeUploadFile("a.png", 3L * mib),
                                            RaftUpgradeUploadFile("b.png", 4L * mib),
                                            RaftUpgradeUploadFile("c.png", 5L * mib),
                                        ),
                                ),
                            agentUploadQuotaBlocks7d = 1,
                        ),
                    onOpenWeb = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText("Not enough uploads left this month").assertIsDisplayed()
        onNodeWithText(
            "Acme has 2 MB left of this month's 100 MB of uploads, not enough for these 3 files; it resets on Dec 1. Your agents ran into this limit once in the past 7 days. Pro has no monthly limit."
        ).assertIsDisplayed()
    }

    @Test
    fun zeroNullAndNonContextualDialogsOmitAgentRefusalClause() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        setContent {
            PageHost {
                RaftUpgradeDialog(
                    request =
                        baseRequest(
                            uploadContext =
                                RaftUpgradeUploadContext.MonthlyQuota(
                                    month = "2026-11",
                                    limitBytes = 100L * mib,
                                    remainingBytes = 0L,
                                    files = emptyList(),
                                ),
                            agentUploadQuotaBlocks7d = 0,
                        ),
                    onOpenWeb = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText(
            "Acme has used this month's 100 MB of uploads; it resets on Dec 1. Pro has no monthly limit."
        ).assertIsDisplayed()
        onNodeWithText("ran into this", substring = true).assertDoesNotExist()
    }

    @Test
    fun nonContextualDialogNeverShowsAgentRefusalClause() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        setContent {
            PageHost {
                RaftUpgradeDialog(
                    request = baseRequest(uploadContext = null, agentUploadQuotaBlocks7d = 6),
                    onOpenWeb = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText("Free uploads up to 100 MB a month. Pro has no monthly limit.").assertIsDisplayed()
        onNodeWithText("ran into this", substring = true).assertDoesNotExist()
    }

    @Test
    fun iosLimitNoticeNeverShowsProCopyOrAgentRefusalCount() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        setContent {
            PageHost {
                RaftUpgradeDialog(
                    request =
                        baseRequest(
                            mode = RaftUpgradeMode.LimitNotice,
                            uploadContext =
                                RaftUpgradeUploadContext.MonthlyQuota(
                                    month = "2026-11",
                                    limitBytes = 100L * mib,
                                    remainingBytes = 2L * mib,
                                    files = listOf(RaftUpgradeUploadFile("demo.mp4", 12L * mib)),
                                ),
                            agentUploadQuotaBlocks7d = 6,
                        ),
                    onOpenWeb = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText("Pro has no monthly limit", substring = true).assertDoesNotExist()
        onNodeWithText("ran into this", substring = true).assertDoesNotExist()
    }

    @Test
    fun localizedPluralsAndClausePlacementMatchWebCopy() {
        // Russian CLDR one / few / many / 21 for both agent tries and file count.
        StringDesc.localeType = StringDesc.LocaleType.Custom("ru")
        val ruOne = Res.string.upgrade_dialog_upload_quota_agent_hits_used_up_one.format(1).resolve()
        val ruFew = Res.string.upgrade_dialog_upload_quota_agent_hits_used_up_other.format(2).resolve()
        val ruMany = Res.string.upgrade_dialog_upload_quota_agent_hits_used_up_other.format(6).resolve()
        val ruTwentyOne = Res.string.upgrade_dialog_upload_quota_agent_hits_used_up_other.format(21).resolve()
        assertEquals("За последние 7 дней ваши агенты наткнулись на этот лимит 1 раз.", ruOne)
        assertEquals("За последние 7 дней ваши агенты наткнулись на этот лимит 2 раза.", ruFew)
        assertEquals("За последние 7 дней ваши агенты наткнулись на этот лимит 6 раз.", ruMany)
        assertEquals("За последние 7 дней ваши агенты наткнулись на этот лимит 21 раз.", ruTwentyOne)
        assertEquals(
            "За последние 7 дней ваши агенты наткнулись на этот лимит 1 раз.",
            Res.string.upgrade_dialog_upload_quota_agent_hits_limit_one.format(1).resolve(),
        )
        assertEquals(
            "За последние 7 дней ваши агенты наткнулись на этот лимит 3 раза.",
            Res.string.upgrade_dialog_upload_quota_agent_hits_limit_other.format(3).resolve(),
        )
        assertEquals(
            "У сервера Acme осталось 2 МБ из месячного лимита загрузок (100 МБ) — этого не хватит для 21 файла; лимит обновится 1 дек.. На Pro месячного лимита нет.",
            Res.string.upgrade_dialog_upload_quota_hint_files.format("Acme", "2 МБ", "100 МБ", 21, "1 дек.").resolve(),
        )
        assertEquals(
            "У сервера Acme осталось 2 МБ из месячного лимита загрузок (100 МБ) — этого не хватит для 3 файлов; лимит обновится 1 дек.. За последние 7 дней ваши агенты наткнулись на этот лимит 6 раз. На Pro месячного лимита нет.",
            Res.string.upgrade_dialog_upload_quota_hint_files_with_agent_hits
                .format("Acme", "2 МБ", "100 МБ", 3, "1 дек.", ruMany)
                .resolve(),
        )

        // Simplified Chinese embeds usedUp clause before reset date without extra spaces.
        StringDesc.localeType = StringDesc.LocaleType.Custom("zh-CN")
        val zhUsedUp = Res.string.upgrade_dialog_upload_quota_agent_hits_used_up_other.format(6).resolve()
        assertEquals(
            "Acme 本月 100 MB 的上传额度已用完，过去 7 天你的 Agent 已经有 6 次因此没传上去，12月1日 重置。升级到 Pro，没有每月上限。",
            Res.string.upgrade_dialog_upload_quota_hint_used_up_with_agent_hits
                .format("Acme", "100 MB", "12月1日", zhUsedUp)
                .resolve(),
        )

        // Japanese places agent refusal sentence after reset date with no ASCII space before Pro.
        StringDesc.localeType = StringDesc.LocaleType.Custom("ja")
        val jaUsedUp = Res.string.upgrade_dialog_upload_quota_agent_hits_used_up_one.format(1).resolve()
        assertEquals(
            "Acme は今月のアップロード容量 100 MB を使い切りました。12月1日 にリセットされます。過去 7 日間にエージェントが 1 回この制限に引っかかりました。Pro には月間の上限がありません。",
            Res.string.upgrade_dialog_upload_quota_hint_used_up_with_agent_hits
                .format("Acme", "100 MB", "12月1日", jaUsedUp)
                .resolve(),
        )

        // Korean places agent refusal sentence after reset date with a space before Pro.
        StringDesc.localeType = StringDesc.LocaleType.Custom("ko")
        val koUsedUp = Res.string.upgrade_dialog_upload_quota_agent_hits_used_up_other.format(4).resolve()
        assertEquals(
            "Acme의 이번 달 업로드 한도 100 MB을 모두 사용했어요. 12월 1일에 초기화돼요. 지난 7일 동안 에이전트가 이 한도 때문에 4번 막혔어요. Pro는 월 한도가 없어요.",
            Res.string.upgrade_dialog_upload_quota_hint_used_up_with_agent_hits
                .format("Acme", "100 MB", "12월 1일", koUsedUp)
                .resolve(),
        )
    }
}
