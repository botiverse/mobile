package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ProPricing
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsBillingAppSumoSnapshot
import build.raft.app.data.SettingsBillingCurrentPlanSnapshot
import build.raft.app.data.SettingsBillingFileUploadQuotaSnapshot
import build.raft.app.data.SettingsBillingSeatUsageSnapshot
import build.raft.app.data.SettingsBillingSnapshot
import build.raft.app.data.SettingsBillingSubscriptionSnapshot
import build.raft.app.resources.*
import build.raft.app.settings.RaftUpgradeDialog
import build.raft.app.settings.RaftUpgradeGate
import build.raft.app.settings.RaftUpgradeMode
import build.raft.app.settings.RaftUpgradeRequest
import build.raft.app.settings.SettingsBillingViewOnlyContent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric Compose UI & 8-locale tests for `#wg-mobile-sync:#366` (botiverse/slock #17191):
 * AppSumo retained storage meter in Settings › Plan & billing ("Storage", `usedBytes + reservedBytes`,
 * no monthly reset date, over-limit display after tier downgrade) and the `AppSumoUploadLimit`
 * recovery dialog for owners and members.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppSumoRetainedStorageUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val originalLocaleType = StringDesc.localeType

    private val mib = 1024L * 1024L
    private val gib = 1024L * mib

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

    private fun appSumoBillingFixture(
        quota: SettingsBillingFileUploadQuotaSnapshot,
    ) = ScreenLoadState.Content(
        SettingsBillingSnapshot(
            currentPlan = SettingsBillingCurrentPlanSnapshot(planName = "", usageRows = emptyList()),
            plans = emptyList(),
            serverPlan = "pro",
            summary =
                SettingsBillingSubscriptionSnapshot(
                    effectivePlan = "pro",
                    source = "appsumo",
                    appsumo =
                        SettingsBillingAppSumoSnapshot(
                            tier = 1,
                            maxHumans = 1,
                            maxAgents = -1,
                            maxHostedJointChannels = 2,
                            hostedJointChannels = 1,
                        ),
                    maxUniversalSeats = -1.0,
                    usage = SettingsBillingSeatUsageSnapshot(humans = 1, agents = 11, universalSeats = 2.1),
                    fileUploadQuota = quota,
                ),
        )
    )

    @Test
    fun billingViewOnlyAppSumoShowsStorageLabelUsedPlusReservedAndOmitsResetDate() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        val fixture =
            appSumoBillingFixture(
                SettingsBillingFileUploadQuotaSnapshot(
                    month = "2026-11",
                    limited = true,
                    limitBytes = gib,
                    remainingBytes = 384L * mib,
                    usedBytes = 512L * mib,
                    reservedBytes = 128L * mib,
                    source = "appsumo",
                )
            )
        setContent {
            PageHost {
                SettingsBillingViewOnlyContent(
                    billing = fixture,
                    sessionPlan = null,
                    onRetry = {},
                    nowEpochMillis = SettingsBillingViewOnlyFixtureNow,
                )
            }
        }
        onNodeWithText("Storage").assertExists()
        onNodeWithText("640 MB of 1 GB used").assertExists()
        onNodeWithText("File uploads this month").assertDoesNotExist()
        onNodeWithText("resets", substring = true).assertDoesNotExist()
    }

    @Test
    fun billingViewOnlyAppSumoShowsOverLimitRetainedStorageAfterTierDowngrade() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        val fixture =
            appSumoBillingFixture(
                SettingsBillingFileUploadQuotaSnapshot(
                    month = "2026-11",
                    limited = true,
                    limitBytes = gib,
                    remainingBytes = 0L,
                    usedBytes = 4L * gib,
                    reservedBytes = 0L,
                    source = "appsumo",
                )
            )
        setContent {
            PageHost {
                SettingsBillingViewOnlyContent(
                    billing = fixture,
                    sessionPlan = null,
                    onRetry = {},
                    nowEpochMillis = SettingsBillingViewOnlyFixtureNow,
                )
            }
        }
        onNodeWithText("Storage").assertExists()
        onNodeWithText("4 GB of 1 GB used").assertExists()
        onNodeWithText("1 GB of 1 GB used").assertDoesNotExist()
        onNodeWithText("resets", substring = true).assertDoesNotExist()
    }

    @Test
    fun appSumoUploadLimitDialogForOwnerShowsManagePlanWithoutProOrMonthlyResetCopy() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        var manageClicks = 0
        var webClicks = 0
        var dismissClicks = 0
        val request =
            RaftUpgradeRequest(
                gate = RaftUpgradeGate.UploadMonthlyQuota,
                mode = RaftUpgradeMode.AppSumoUploadLimit,
                pagerId = "p",
                serverId = "s-1",
                serverName = "Acme",
                owner = true,
                webUrl = "https://raft.build/s/acme/settings/billing?lang=en",
                pricing = ProPricing.PersonV2,
            )
        setContent {
            PageHost {
                RaftUpgradeDialog(
                    request = request,
                    onOpenWeb = { webClicks++ },
                    onDismiss = { dismissClicks++ },
                    onManageAppSumoPlan = { manageClicks++ },
                )
            }
        }
        waitForIdle()
        onNodeWithText("AppSumo storage limit").assertIsDisplayed()
        onNodeWithText("Delete unused files to free space. You can review usage in Settings > Plan & billing.")
            .assertIsDisplayed()
        onNodeWithText("Pro feature").assertDoesNotExist()
        onNodeWithText("Pro has no monthly limit", substring = true).assertDoesNotExist()
        onNodeWithText("resets", substring = true).assertDoesNotExist()
        onNodeWithText("1 GiB of 1 GiB", substring = true).assertDoesNotExist()
        onNodeWithText("$20", substring = true).assertDoesNotExist()
        onNodeWithText("Manage AppSumo plan").assertIsDisplayed().performClick()
        assertEquals(1, manageClicks)
        assertEquals(0, webClicks)
        assertEquals(0, dismissClicks)
    }

    @Test
    fun appSumoUploadLimitDialogForMemberShowsAskOwnerAndCloseAction() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        var manageClicks = 0
        var dismissClicks = 0
        val request =
            RaftUpgradeRequest(
                gate = RaftUpgradeGate.UploadMonthlyQuota,
                mode = RaftUpgradeMode.AppSumoUploadLimit,
                pagerId = "p",
                serverId = "s-1",
                serverName = "Acme",
                owner = false,
                webUrl = "https://raft.build/s/acme/settings/billing?lang=en",
            )
        setContent {
            PageHost {
                RaftUpgradeDialog(
                    request = request,
                    onOpenWeb = {},
                    onDismiss = { dismissClicks++ },
                    onManageAppSumoPlan = { manageClicks++ },
                )
            }
        }
        waitForIdle()
        onNodeWithText("AppSumo storage limit").assertIsDisplayed()
        onNodeWithText("Delete unused files or ask a server owner to review storage usage.").assertIsDisplayed()
        onNodeWithText("Manage AppSumo plan").assertDoesNotExist()
        onNodeWithText("Close").assertIsDisplayed().performClick()
        assertEquals(1, dismissClicks)
        assertEquals(0, manageClicks)
    }

    @Test
    fun allEightLocalesMatchSlockAppSumoStorageCopy() {
        data class ExpectedCopy(
            val storage: String,
            val title: String,
            val managePlan: String,
            val manageHelp: String,
            val askOwner: String,
        )

        val expectedByLocale =
            linkedMapOf(
                "en" to
                    ExpectedCopy(
                        storage = "Storage",
                        title = "AppSumo storage limit",
                        managePlan = "Manage AppSumo plan",
                        manageHelp = "Delete unused files to free space. You can review usage in Settings > Plan & billing.",
                        askOwner = "Delete unused files or ask a server owner to review storage usage.",
                    ),
                "zh-CN" to
                    ExpectedCopy(
                        storage = "存储空间",
                        title = "AppSumo 存储空间上限",
                        managePlan = "管理 AppSumo 套餐",
                        manageHelp = "删除不再需要的文件可释放空间。可在设置中的套餐与计费页面查看用量。",
                        askOwner = "请删除不再需要的文件，或联系服务器所有者查看存储用量。",
                    ),
                "zh-TW" to
                    ExpectedCopy(
                        storage = "儲存空間",
                        title = "AppSumo 儲存空間上限",
                        managePlan = "管理 AppSumo 方案",
                        manageHelp = "刪除不需要的檔案可釋放空間。可在設定的方案與帳單頁面查看用量。",
                        askOwner = "請刪除不需要的檔案，或聯絡伺服器擁有者查看用量。",
                    ),
                "ja" to
                    ExpectedCopy(
                        storage = "ストレージ",
                        title = "AppSumo のストレージ上限",
                        managePlan = "AppSumo プランを管理",
                        manageHelp = "不要なファイルを削除して空き容量を増やしてください。設定のプランと請求で使用量を確認できます。",
                        askOwner = "不要なファイルを削除するか、サーバーの所有者に使用量の確認を依頼してください。",
                    ),
                "ko" to
                    ExpectedCopy(
                        storage = "저장 공간",
                        title = "AppSumo 저장 공간 한도",
                        managePlan = "AppSumo 요금제 관리",
                        manageHelp = "불필요한 파일을 삭제하여 공간을 확보하세요. 설정의 요금제 및 결제에서 사용량을 확인할 수 있습니다.",
                        askOwner = "불필요한 파일을 삭제하거나 서버 소유자에게 사용량 확인을 요청하세요.",
                    ),
                "es" to
                    ExpectedCopy(
                        storage = "Almacenamiento",
                        title = "Límite de almacenamiento de AppSumo",
                        managePlan = "Administrar plan de AppSumo",
                        manageHelp = "Elimina archivos que ya no necesites para liberar espacio. Puedes consultar el uso en los ajustes de facturación.",
                        askOwner = "Elimina archivos que ya no necesites o pide al propietario del servidor que revise el uso.",
                    ),
                "pt-BR" to
                    ExpectedCopy(
                        storage = "Armazenamento",
                        title = "Limite de armazenamento do AppSumo",
                        managePlan = "Gerenciar plano do AppSumo",
                        manageHelp = "Exclua arquivos desnecessários para liberar espaço. Consulte o uso nas configurações de cobrança.",
                        askOwner = "Exclua arquivos desnecessários ou peça ao proprietário do servidor para verificar o uso.",
                    ),
                "ru" to
                    ExpectedCopy(
                        storage = "Хранилище",
                        title = "Лимит хранилища AppSumo",
                        managePlan = "Управлять тарифом AppSumo",
                        manageHelp = "Удалите ненужные файлы, чтобы освободить место. Использование хранилища можно проверить в настройках тарифа.",
                        askOwner = "Удалите ненужные файлы или попросите владельца сервера проверить использование хранилища.",
                    ),
            )

        expectedByLocale.forEach { (tag, expected) ->
            StringDesc.localeType = StringDesc.LocaleType.Custom(tag)
            assertEquals(expected.storage, Res.string.settings_billing_vo_appsumo_storage.desc().resolve(), "$tag storage")
            assertEquals(expected.title, Res.string.upgrade_dialog_appsumo_upload_limit_title.desc().resolve(), "$tag title")
            assertEquals(expected.managePlan, Res.string.upgrade_dialog_appsumo_manage_plan.desc().resolve(), "$tag managePlan")
            assertEquals(expected.manageHelp, Res.string.upgrade_dialog_appsumo_upload_manage_help.desc().resolve(), "$tag manageHelp")
            assertEquals(expected.askOwner, Res.string.upgrade_dialog_appsumo_upload_ask_owner.desc().resolve(), "$tag askOwner")
        }
    }
}
