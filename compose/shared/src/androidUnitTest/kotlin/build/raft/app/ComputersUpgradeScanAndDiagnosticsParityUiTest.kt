package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentIssueDiagnosticsBundleRange
import build.raft.app.data.PRODUCT_FEEDBACK_MAX_ZIP_ATTACHMENT_BYTES
import build.raft.app.data.PRODUCT_FEEDBACK_ZIP_CONTENT_TYPE
import build.raft.app.data.PlatformFile
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerBroadcastPolicySnapshot
import build.raft.app.data.SettingsComputerCommandContext
import build.raft.app.data.SettingsComputerDetailSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputerUpgradeDiskFullSnapshot
import build.raft.app.data.SettingsComputerUpgradeRequestSnapshot
import build.raft.app.data.SettingsComputerWorkspaceSnapshot
import build.raft.app.data.SettingsFeedbackType
import build.raft.app.format.RaftFormat
import build.raft.app.resources.*
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 1.31.0 web parity (#wg-mobile-sync:#301):
 * 1. Computer upgrade `diskFull` out-of-space failure copy without manual install fallback (slock #16536).
 * 2. Bounded Computer workspace scan `partial: true` ("at least …") (slock #16552).
 * 3a. Report issue Computer diagnostics bundle opt-in + closed range presets (slock #16580).
 * 3b. My Feedback single `.zip` attachment <= 10 MiB validation & chip rendering (slock #16580).
 * 4. All 8 locales matching upstream web strings word-for-word.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class ComputersUpgradeScanAndDiagnosticsParityUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @After
    fun resetLocale() {
        StringDesc.localeType = StringDesc.LocaleType.System
    }

    private fun computer(
        upgradeRequest: SettingsComputerUpgradeRequestSnapshot? = null,
    ) = SettingsComputerSnapshot(
        id = "m-1",
        name = "Studio",
        daemonVersion = "",
        computerVersion = "1.0.53",
        isComputer = true,
        online = true,
        upgradeAvailable = true,
        remoteUpgradeSupported = true,
        broadcastPolicy = SettingsComputerBroadcastPolicySnapshot(eligibility = "eligible", targetVersion = "1.0.54"),
        latestComputerVersion = "1.0.54",
        upgradeRequest = upgradeRequest,
        canManage = true,
    )

    private fun detail(
        computer: SettingsComputerSnapshot = computer(),
        workspaces: List<SettingsComputerWorkspaceSnapshot> = emptyList(),
        workspacesScanned: Boolean = workspaces.isNotEmpty(),
    ) = SettingsComputerDetailSnapshot(
        computer = computer,
        workspaces = workspaces,
        agents = emptyList(),
        sections = emptyList(),
        workspacesScanned = workspacesScanned,
        canManageMachines = true,
        canEditMachine = true,
        canControlComputer = true,
        commandContext = SettingsComputerCommandContext(serverSlug = "botiverse", deploymentEnv = "production"),
        currentUserId = "user-1",
    )

    private fun ComposeUiTest.showDetail(snapshot: SettingsComputerDetailSnapshot) {
        val model =
            raftComputerDetailModel(
                ScreenLoadState.Content(snapshot),
                machineId = "m-1",
                targetServerId = null,
                currentServerId = "server-1",
            )
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    RaftSettingsComputerDetailContent(
                        model = model,
                        actionState = null,
                        onRetry = {},
                        onIntent = {},
                        onOpenAgent = {},
                    )
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun diskFullUpgradeFailureShowsOutOfSpaceCopyWithoutManualInstallCommand() = runRobolectricComposeUiTest {
        val sizedRequest =
            SettingsComputerUpgradeRequestSnapshot(
                id = "req-disk-sized",
                targetVersion = "1.0.54",
                requestedAt = "2026-11-24T10:00:00.000Z",
                state = "failed",
                observedVersion = "1.0.53",
                reason = "disk_full",
                diskFull = SettingsComputerUpgradeDiskFullSnapshot(neededBytes = 812_000_000L, freeBytes = 300_000_000L),
            )
        showDetail(detail(computer(upgradeRequest = sizedRequest)))

        onNodeWithTag("computer-upgrade-request-outcome").assertIsDisplayed()
        onNodeWithText(
            "This Computer is out of disk space: the upgrade needs about 812.0 MB free, 300.0 MB is left. Free up some space, then click Upgrade again. Nothing was changed."
        ).assertIsDisplayed()
        onNodeWithTag("computer-upgrade-failure-manual").assertDoesNotExist()
        onNodeWithText("Upgrade to v1.0.54").assertIsDisplayed()
    }

    @Test
    fun diskFullUnsizedUpgradeFailureOmitsSizesAndManualInstallCommand() = runRobolectricComposeUiTest {
        val unsizedRequest =
            SettingsComputerUpgradeRequestSnapshot(
                id = "req-disk-unsized",
                targetVersion = "1.0.54",
                requestedAt = "2026-11-24T10:00:00.000Z",
                state = "failed",
                observedVersion = "1.0.53",
                reason = "disk_full",
                diskFull = SettingsComputerUpgradeDiskFullSnapshot(neededBytes = null, freeBytes = null),
            )
        showDetail(detail(computer(upgradeRequest = unsizedRequest)))

        onNodeWithText(
            "This Computer is out of disk space. Free up some space, then click Upgrade again. Nothing was changed."
        ).assertIsDisplayed()
        onNodeWithTag("computer-upgrade-failure-manual").assertDoesNotExist()
    }

    @Test
    fun genericUpgradeFailureStillShowsManualInstallFallback() = runRobolectricComposeUiTest {
        val genericFailure =
            SettingsComputerUpgradeRequestSnapshot(
                id = "req-failed",
                targetVersion = "1.0.54",
                requestedAt = "2026-11-24T10:00:00.000Z",
                state = "failed",
                observedVersion = "1.0.53",
                reason = "install_failed",
                diskFull = null,
            )
        showDetail(detail(computer(upgradeRequest = genericFailure)))

        onNodeWithTag("computer-upgrade-request-outcome").assertIsDisplayed()
        onNodeWithTag("computer-upgrade-failure-manual").assertIsDisplayed()
    }

    @Test
    fun partialWorkspaceScanShowsAtLeastPrefixForSizeAndFileCount() = runRobolectricComposeUiTest {
        val workspaces =
            listOf(
                SettingsComputerWorkspaceSnapshot(
                    id = "agent-big",
                    name = "Big Agent",
                    path = "~/.slock/agents/agent-big/",
                    statusLabel = "active",
                    totalSizeBytes = 2048L,
                    fileCount = 3,
                    partial = true,
                ),
                SettingsComputerWorkspaceSnapshot(
                    id = "agent-small",
                    name = "Small Agent",
                    path = "~/.slock/agents/agent-small/",
                    statusLabel = "active",
                    totalSizeBytes = 1024L,
                    fileCount = 1,
                    partial = false,
                ),
            )
        showDetail(detail(workspaces = workspaces, workspacesScanned = true))

        onNodeWithText("at least 2.0 KB · at least 3 files").assertIsDisplayed()
        onNodeWithText("1.0 KB · 1 file").assertIsDisplayed()
    }

    @Test
    fun reportIssueDiagnosticsBundleOptInShowsRangePresetsAndSubmitsSelectedRangeForAttacher() = runRobolectricComposeUiTest {
        var submitted: MembersAgentIssueReportSelection? = null
        setContent {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    MembersAgentIssueReportDialog(
                        agentName = "Cindy",
                        runtimeTranscriptAvailable = true,
                        canIncludeMachineLog = true,
                        ownAttachedMachine = true,
                        busy = false,
                        errorMessage = null,
                        onSubmit = { submitted = it },
                        onDismiss = {},
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithTag(MEMBERS_AGENT_ISSUE_DIAGNOSTICS_BUNDLE_TAG).assertIsDisplayed().assertIsOff()
        onNodeWithText(
            "Service, install and upgrade logs from this computer, plus this server's runner logs. Logs may contain fragments of messages or prompts; they are sent to the Raft team."
        ).assertIsDisplayed()
        onNodeWithTag(MEMBERS_AGENT_ISSUE_DIAGNOSTICS_BUNDLE_RANGE_TAG).assertDoesNotExist()

        // Opt in: range RadioGroup appears with report_window selected by default.
        onNodeWithTag(MEMBERS_AGENT_ISSUE_DIAGNOSTICS_BUNDLE_TAG).performClick()
        waitForIdle()
        onNodeWithTag(MEMBERS_AGENT_ISSUE_DIAGNOSTICS_BUNDLE_TAG).assertIsOn()
        onNodeWithTag(MEMBERS_AGENT_ISSUE_DIAGNOSTICS_BUNDLE_RANGE_TAG).assertIsDisplayed()
        onNodeWithTag("$MEMBERS_AGENT_ISSUE_DIAGNOSTICS_BUNDLE_RANGE_TAG-report_window").assertIsSelected()

        // Switch range to last_7d, consent, and submit.
        onNodeWithTag("$MEMBERS_AGENT_ISSUE_DIAGNOSTICS_BUNDLE_RANGE_TAG-last_7d").performClick()
        waitForIdle()
        onNodeWithTag("$MEMBERS_AGENT_ISSUE_DIAGNOSTICS_BUNDLE_RANGE_TAG-last_7d").assertIsSelected()

        onNodeWithText("I agree to send this report to the Raft team.").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Report").performClick()
        waitForIdle()

        val result = requireNotNull(submitted)
        assertTrue(result.includeDiagnosticsBundle)
        assertEquals(AgentIssueDiagnosticsBundleRange.Last7d, result.diagnosticsBundleRange)
    }

    @Test
    fun reportIssueDiagnosticsBundleDisablesAndHidesRangeWhenRuntimeTranscriptUnchecked() = runRobolectricComposeUiTest {
        setContent {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    MembersAgentIssueReportDialog(
                        agentName = "Cindy",
                        runtimeTranscriptAvailable = true,
                        canIncludeMachineLog = true,
                        ownAttachedMachine = true,
                        busy = false,
                        errorMessage = null,
                        onSubmit = {},
                        onDismiss = {},
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithTag(MEMBERS_AGENT_ISSUE_DIAGNOSTICS_BUNDLE_TAG).performClick()
        waitForIdle()
        onNodeWithTag(MEMBERS_AGENT_ISSUE_DIAGNOSTICS_BUNDLE_RANGE_TAG).assertIsDisplayed()

        onNodeWithText("Runtime session transcript").performClick()
        waitForIdle()
        onNodeWithTag(MEMBERS_AGENT_ISSUE_DIAGNOSTICS_BUNDLE_TAG).assertIsNotEnabled()
        onNodeWithTag(MEMBERS_AGENT_ISSUE_DIAGNOSTICS_BUNDLE_RANGE_TAG).assertDoesNotExist()
    }

    @Test
    fun reportIssueDiagnosticsBundleIsHiddenForNonAttacherEvenIfOwnerOrAdmin() = runRobolectricComposeUiTest {
        setContent {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    MembersAgentIssueReportDialog(
                        agentName = "Cindy",
                        runtimeTranscriptAvailable = true,
                        canIncludeMachineLog = true,
                        ownAttachedMachine = false,
                        busy = false,
                        errorMessage = null,
                        onSubmit = {},
                        onDismiss = {},
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithText("Machine runner log tail").assertIsDisplayed()
        onNodeWithTag(MEMBERS_AGENT_ISSUE_DIAGNOSTICS_BUNDLE_TAG).assertDoesNotExist()
        onNodeWithTag(MEMBERS_AGENT_ISSUE_DIAGNOSTICS_BUNDLE_RANGE_TAG).assertDoesNotExist()
    }

    @Test
    fun myFeedbackProxyAttachmentFilterEnforcesZipMagicBytesCapAndSingleZipRule() = runTest {
        val validZipBytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x14, 0x00)
        val emptyZipBytes = byteArrayOf(0x50, 0x4b, 0x05, 0x06, 0x00, 0x00)
        val img = PlatformFile("img-1", "shot.png", "image/png", sizeBytes = 1024L, fileRef = "file:///shot.png")
        val zip1 = PlatformFile("zip-1", "computer-diag.zip", "application/x-zip-compressed", sizeBytes = 4096L, bytes = validZipBytes)
        val zip2 = PlatformFile("zip-2", "second.zip", "application/zip", sizeBytes = 2048L, bytes = emptyZipBytes)
        val fakeZip = PlatformFile("zip-fake", "fake.zip", "application/zip", sizeBytes = 64L, bytes = "not-zip".encodeToByteArray())
        val hugeZip =
            PlatformFile(
                "zip-huge",
                "huge.zip",
                "application/zip",
                sizeBytes = PRODUCT_FEEDBACK_MAX_ZIP_ATTACHMENT_BYTES + 1L,
                bytes = validZipBytes,
            )

        // Accepts image + one valid zip (normalized to application/zip), rejects 2nd zip in same batch.
        val firstBatch =
            filterSettingsHandsFeedbackProxyAttachments(
                existing = emptyList(),
                picked = listOf(img, zip1, zip2),
                imageOnly = false,
                attachmentMaxBytes = PRODUCT_FEEDBACK_MAX_ZIP_ATTACHMENT_BYTES,
            )
        assertEquals(2, firstBatch.accepted.size)
        assertEquals(PRODUCT_FEEDBACK_ZIP_CONTENT_TYPE, firstBatch.accepted[1].mimeType)
        assertEquals(1, firstBatch.typeRejectedCount)
        assertEquals(0, firstBatch.tooLargeCount)

        // Rejects another zip when existing already carries one.
        val secondPick =
            filterSettingsHandsFeedbackProxyAttachments(
                existing = firstBatch.accepted,
                picked = listOf(zip2),
                imageOnly = false,
                attachmentMaxBytes = PRODUCT_FEEDBACK_MAX_ZIP_ATTACHMENT_BYTES,
            )
        assertTrue(secondPick.accepted.isEmpty())
        assertEquals(1, secondPick.typeRejectedCount)

        // Rejects fake zip bytes and > 10 MiB zip separately.
        val invalidBatch =
            filterSettingsHandsFeedbackProxyAttachments(
                existing = emptyList(),
                picked = listOf(fakeZip, hugeZip),
                imageOnly = false,
                attachmentMaxBytes = PRODUCT_FEEDBACK_MAX_ZIP_ATTACHMENT_BYTES,
            )
        assertTrue(invalidBatch.accepted.isEmpty())
        assertEquals(1, invalidBatch.typeRejectedCount)
        assertEquals(1, invalidBatch.tooLargeCount)

        // Image-only picker refuses zip even when valid.
        val imageOnlyBatch =
            filterSettingsHandsFeedbackProxyAttachments(
                existing = emptyList(),
                picked = listOf(zip1),
                imageOnly = true,
                attachmentMaxBytes = PRODUCT_FEEDBACK_MAX_ZIP_ATTACHMENT_BYTES,
            )
        assertTrue(imageOnlyBatch.accepted.isEmpty())
        assertEquals(1, imageOnlyBatch.typeRejectedCount)
    }

    @Test
    fun myFeedbackRendersAttachedDiagnosticsZipChipAndUpdatedHint() = runRobolectricComposeUiTest {
        val zipAttachment =
            PlatformFile(
                id = "zip-1",
                fileName = "raft-diagnostics.zip",
                mimeType = PRODUCT_FEEDBACK_ZIP_CONTENT_TYPE,
                sizeBytes = 12_288L,
                fileRef = "draft://raft-diagnostics.zip",
            )
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    val copy = settingsHandsFeedbackCopy(RaftFormat.byteLimit(settingsFeedbackImageMaxBytes()))
                    val editorOwner = remember { ThreadComposerEditorOwner(seedText = "Attached zip", seedAsTask = false) }
                    SettingsHandsFeedbackForm(
                        copy = copy,
                        type = SettingsFeedbackType.Problem,
                        onTypeChange = {},
                        enabled = true,
                        notice = null,
                        onRetry = {},
                        onCopyResult = {},
                        bottomContentPadding = 16.dp,
                    ) {
                        SettingsHandsFeedbackComposer(
                            instanceId = "feedback-zip-test",
                            editorOwner = editorOwner,
                            placeholder = copy.placeholder,
                            enabled = true,
                            submitting = false,
                            attachmentActionsEnabled = true,
                            attachments = listOf(zipAttachment).toFeedbackComposerAttachmentItems(copy),
                            composerSendKeyEnabled = false,
                            onEvent = { _, _, _ -> },
                        )
                    }
                }
            }
        }
        waitForIdle()

        onNodeWithText("raft-diagnostics.zip").assertIsDisplayed()
        onNodeWithText("Up to 3 attachments (images or one diagnostics .zip), 10 MB each.").assertIsDisplayed()
    }

    @Test
    fun allEightLocalesMatchUpstreamParityStringsCleanly() {
        val expectedDiskFull =
            mapOf(
                "en" to "This Computer is out of disk space: the upgrade needs about 812.0 MB free, 300.0 MB is left. Free up some space, then click Upgrade again. Nothing was changed.",
                "es" to "Esta computadora se quedó sin espacio en disco: la actualización necesita unos 812.0 MB libres y quedan 300.0 MB. Libera espacio y vuelve a hacer clic en Actualizar. No se cambió nada.",
                "ja" to "このコンピューターのディスク空き容量が足りません。アップグレードには約 812.0 MB の空きが必要ですが、残りは 300.0 MB です。空き容量を増やしてから、もう一度「アップグレード」をクリックしてください。何も変更されていません。",
                "ko" to "이 컴퓨터의 디스크 공간이 부족해요. 업그레이드에는 약 812.0 MB의 여유 공간이 필요한데 300.0 MB만 남아 있어요. 공간을 확보한 다음 업그레이드를 다시 클릭하세요. 변경된 것은 없어요.",
                "pt-BR" to "Esta máquina está sem espaço em disco: a atualização precisa de cerca de 812.0 MB livres e restam 300.0 MB. Libere espaço e clique em Atualizar de novo. Nada foi alterado.",
                "ru" to "На этом компьютере закончилось место на диске: для обновления нужно около 812.0 MB свободного места, осталось 300.0 MB. Освободите место и снова нажмите «Обновить». Ничего не изменилось.",
                "zh-CN" to "这台电脑磁盘空间不足，升级大约需要 812.0 MB，现在只剩 300.0 MB。清理一些空间后再点「升级」。什么都没有改动。",
                "zh-TW" to "這台電腦磁碟空間不足，升級大約需要 812.0 MB，現在只剩 300.0 MB。清出一些空間後再按「升級」。什麼都沒有變動。",
            )
        val expectedAtLeast =
            mapOf(
                "en" to "at least 2.0 KB",
                "es" to "al menos 2.0 KB",
                "ja" to "2.0 KB 以上",
                "ko" to "최소 2.0 KB",
                "pt-BR" to "pelo menos 2.0 KB",
                "ru" to "не менее 2.0 KB",
                "zh-CN" to "至少 2.0 KB",
                "zh-TW" to "至少 2.0 KB",
            )
        val expectedDiagTitle =
            mapOf(
                "en" to "Computer diagnostics bundle",
                "es" to "Paquete de diagnóstico del Computer",
                "ja" to "Computer 診断パッケージ",
                "ko" to "Computer 진단 번들",
                "pt-BR" to "Pacote de diagnóstico do Computer",
                "ru" to "Диагностический пакет Computer",
                "zh-CN" to "Computer 诊断包",
                "zh-TW" to "Computer 診斷包",
            )
        val expectedRange7d =
            mapOf(
                "en" to "Last 7 days",
                "es" to "Últimos 7 días",
                "ja" to "過去 7 日間",
                "ko" to "최근 7일",
                "pt-BR" to "Últimos 7 dias",
                "ru" to "Последние 7 дней",
                "zh-CN" to "最近 7 天",
                "zh-TW" to "最近 7 天",
            )

        expectedDiskFull.keys.forEach { tag ->
            StringDesc.localeType = StringDesc.LocaleType.Custom(tag)
            val diskFull = Res.string.settings_computers_upgrade_status_disk_full.format("812.0 MB", "300.0 MB").resolve()
            val diskFullUnsized = Res.string.settings_computers_upgrade_status_disk_full_unsized.desc().resolve()
            val atLeast = Res.string.settings_computers_workspace_at_least.format("2.0 KB").resolve()
            val diagTitle = Res.string.member_report_issue_diagnostics_bundle.desc().resolve()
            val diagDisclosure = Res.string.member_report_issue_diagnostics_bundle_disclosure.desc().resolve()
            val diagRange = Res.string.member_report_issue_diagnostics_bundle_range.desc().resolve()
            val diagWindow = Res.string.member_report_issue_diagnostics_bundle_range_report_window.desc().resolve()
            val diag24h = Res.string.member_report_issue_diagnostics_bundle_range_last_24h.desc().resolve()
            val diag7d = Res.string.member_report_issue_diagnostics_bundle_range_last_7d.desc().resolve()
            val fbType = Res.string.settings_feedback_submission_attachment_type_body.desc().resolve()
            val fbHint = Res.string.settings_feedback_attachment_hint.format(3, "10 MB").resolve()

            assertEquals(expectedDiskFull.getValue(tag), diskFull, "$tag diskFull")
            assertEquals(expectedAtLeast.getValue(tag), atLeast, "$tag atLeast")
            assertEquals(expectedDiagTitle.getValue(tag), diagTitle, "$tag diagTitle")
            assertEquals(expectedRange7d.getValue(tag), diag7d, "$tag diag7d")

            listOf(diskFull, diskFullUnsized, atLeast, diagTitle, diagDisclosure, diagRange, diagWindow, diag24h, diag7d, fbType, fbHint)
                .forEach { resolved ->
                    assertTrue(resolved.isNotBlank(), "$tag blank string")
                    assertFalse(resolved.contains("{need}") || resolved.contains("{free}") || resolved.contains("{value}"), "$tag unreplaced web placeholder: $resolved")
                    assertFalse(resolved.contains("%1\$") || resolved.contains("%2\$"), "$tag unreplaced format placeholder: $resolved")
                }
            assertTrue(fbType.contains(".zip"), "$tag feedback type must mention .zip: $fbType")
            assertTrue(fbHint.contains(".zip"), "$tag feedback hint must mention .zip: $fbHint")
        }
    }
}
