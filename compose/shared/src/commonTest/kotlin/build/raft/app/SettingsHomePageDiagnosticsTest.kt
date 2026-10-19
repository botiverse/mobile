package build.raft.app

import build.raft.app.data.PlatformFile
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsApiEnvironmentOption
import build.raft.app.data.SharedSettingsAboutSnapshot
import build.raft.app.data.SharedSettingsDeveloperSnapshot
import build.raft.app.data.SharedSettingsDiagnosticsSnapshot
import build.raft.app.data.SharedSettingsDiagnosticFeatureFlagSnapshot
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.DEVELOPER_ACTION_API_ENVIRONMENT
import build.raft.app.data.DEVELOPER_ACTION_HANDS_UPDATE_CHECK
import build.raft.app.data.SERVER_ACTION_MEMBER_REMOVE
import build.raft.app.data.SERVER_ACTION_MEMBER_ROLE
import build.raft.app.data.SharedSettingsOpenSourceLicenseItem
import build.raft.app.data.SharedSettingsOpenSourceLicensesSnapshot
import build.raft.app.data.SharedSettingsUpdateCheckSnapshot
import build.raft.app.data.SettingsServerAdminSnapshot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsHomePageDiagnosticsTest {
    @Test
    fun diagnosticsOverviewRoutesToLocalFeatureOverridesWithoutDumpingPerKeyControls() {
        val payload =
            emptyList<build.raft.app.data.SharedSettingsDiagnosticsEntry>().toDiagnosticsPayload(
                state =
                    ScreenLoadState.Content(
                        SharedSettingsDiagnosticsSnapshot(
                            featureFlagOverrides =
                                listOf(
                                    SharedSettingsDiagnosticFeatureFlagSnapshot(
                                        key = "sync_core_read_state_v1",
                                        supported = true,
                                        scopeKind = "ChannelExact",
                                        entitled = true,
                                        observedScope = "env/account/server/channel",
                                    ),
                                    SharedSettingsDiagnosticFeatureFlagSnapshot(
                                        key = "future_registered_flag_v1",
                                        supported = false,
                                        clearReason = "unsupported_no_effect",
                                    ),
                                ),
                        )
                    ),
                actionState = null,
            )

        assertEquals(
            listOf("Developer tools", "Actions"),
            payload.sections.map { it.title },
        )
        val entry = payload.sections.first().rows.single()
        assertEquals("Local Feature Overrides", entry.title)
        assertTrue(entry.enabled)
        assertFalse(payload.sections.any { it.title == "sync_core_read_state_v1" })
        assertFalse(payload.sections.any { it.title == "future_registered_flag_v1" })
    }

    @Test
    fun appleSignInLocalOverrideSectionRendersOnlyWhenRequestedAndReflectsEnabledState() {
        val snapshot =
            ScreenLoadState.Content(
                SharedSettingsDiagnosticsSnapshot(
                    appleSignInLocalOverride = true,
                    appleSignInLocalOverrideAvailable = true,
                )
            )

        val withOverride =
            emptyList<build.raft.app.data.SharedSettingsDiagnosticsEntry>().toDiagnosticsPayload(
                state = snapshot,
                actionState = null,
                appleSignInLocalOverride = true,
            )
        val overrideSection = withOverride.sections.first { it.title == "Apple sign-in (local test)" }
        val row = overrideSection.rows.single()
        assertEquals(build.raft.app.data.DIAGNOSTICS_ACTION_APPLE_SIGNIN_LOCAL_OVERRIDE, row.key)
        assertEquals("false", row.value)
        assertEquals(true, row.toggleValue)
        assertEquals("On", row.status)
        assertTrue(row.enabled)

        val withoutOverride =
            emptyList<build.raft.app.data.SharedSettingsDiagnosticsEntry>().toDiagnosticsPayload(
                state = snapshot,
                actionState = null,
            )
        assertFalse(withoutOverride.sections.any { it.title == "Apple sign-in (local test)" })
    }

    @Test
    fun localFeatureOverrideListAlwaysShowsEveryEntitledRegisteredKey() {
        val snapshot = localFeatureOverrideSnapshot()

        val rows = snapshot.toLocalFeatureOverridesPayload().sections.single().rows

        assertEquals(
            listOf("mobile_activity_stable_order_v1", "sync_core_read_state_v1"),
            rows.mapNotNull { parseOpenLocalFeatureOverrideAction(it.key) },
        )
        assertEquals(
            listOf("mobile_activity_stable_order_v1", "sync_core_read_state_v1"),
            rows.map { it.detail },
        )
        assertEquals(listOf("OFF", "ON"), rows.map { it.status })
        assertFalse(rows.any { it.title.contains("future registered", ignoreCase = true) })
    }

    @Test
    fun localFeatureOverrideNavigationCarriesOnlyRegisteredKeyIdentity() {
        val key = "sync_core_read_state_v1"
        val route = assertNotNull(SettingsNativeDetailRoute.create(SettingsNativeDetailPage.LocalFeatureOverride, key))
        val diagnosticsRoute = assertNotNull(SettingsNativeDetailRoute.create(SettingsNativeDetailPage.Diagnostics))

        assertEquals(key, route.itemId)
        assertEquals(key, parseOpenLocalFeatureOverrideAction(openLocalFeatureOverrideAction(key)))
        assertTrue(localFeatureOverridePageAllowed(route, available = true))
        assertFalse(localFeatureOverridePageAllowed(route, available = false))
        assertTrue(localFeatureOverridePageAllowed(diagnosticsRoute, available = false))
        assertFalse(
            shouldCloseLocalFeatureOverridePage(
                route = route,
                availabilityResolved = false,
                available = false,
            )
        )
        assertTrue(
            shouldCloseLocalFeatureOverridePage(
                route = route,
                availabilityResolved = true,
                available = false,
            )
        )
        assertFalse(
            shouldCloseLocalFeatureOverridePage(
                route = route,
                availabilityResolved = true,
                available = true,
            )
        )
        assertNull(parseOpenLocalFeatureOverrideAction("openLocalFeatureOverride|unknown-key"))
    }

    @Test
    fun appProcessControlsAreEnabledBeforeAnyConsumerIsObserved() {
        val flag =
            localFeatureOverrideSnapshot().featureFlagOverrides
                .first { it.key == "sync_core_read_state_v1" }

        val rows = flag.toLocalFeatureOverrideDetailPayload(actionState = null).sections.flatMap { it.rows }
        val byTitle = rows.associateBy { it.title }

        assertTrue(byTitle.getValue("Follow Server").enabled)
        assertTrue(byTitle.getValue("Force OFF").enabled)
        assertTrue(byTitle.getValue("Force ON").enabled)
        assertTrue(byTitle.getValue("Force OFF").detail.contains("current app process"))
        assertTrue(byTitle.getValue("Force ON").detail.contains("current app process"))
        assertEquals("ON", byTitle.getValue("Final effective").status)
        assertFalse(rows.any { it.detail.contains("Open the target", ignoreCase = true) })
    }

    @Test
    fun serverOffAndOnIntentsStayIsolatedToTheSelectedKey() {
        val flag = localFeatureOverrideSnapshot().featureFlagOverrides.first().copy(clientOverride = "ForceOff")

        val rows = flag.toLocalFeatureOverrideDetailPayload(actionState = null).sections.flatMap { it.rows }
        val controls = rows.filter { it.title in setOf("Follow Server", "Force OFF", "Force ON") }

        assertTrue(controls.all { it.enabled })
        assertEquals(
            setOf(
                "diagnosticFlagOverride|mobile_activity_stable_order_v1|server",
                "diagnosticFlagOverride|mobile_activity_stable_order_v1|force_off",
                "diagnosticFlagOverride|mobile_activity_stable_order_v1|force_on",
            ),
            controls.map { it.key }.toSet(),
        )
        assertTrue(controls.none { it.key.contains("sync_core_read_state_v1") })
    }

    @Test
    fun localFeatureOverrideSurfaceIsUnreachableWithoutEntitlement() {
        val releaseSnapshot =
            localFeatureOverrideSnapshot().copy(
                featureFlagOverrides =
                    localFeatureOverrideSnapshot().featureFlagOverrides.map { it.copy(entitled = false) }
            )

        assertFalse(releaseSnapshot.localFeatureOverridesAvailable())
        assertTrue(releaseSnapshot.registeredLocalFeatureOverrides().isEmpty())
        val overview =
            emptyList<build.raft.app.data.SharedSettingsDiagnosticsEntry>().toDiagnosticsPayload(
                state = ScreenLoadState.Content(releaseSnapshot),
                actionState = null,
            )
        assertFalse(overview.sections.any { it.title == "Developer tools" })
    }

    private fun localFeatureOverrideSnapshot(): SharedSettingsDiagnosticsSnapshot =
        SharedSettingsDiagnosticsSnapshot(
            featureFlagOverrides =
                listOf(
                    SharedSettingsDiagnosticFeatureFlagSnapshot(
                        key = "mobile_activity_stable_order_v1",
                        supported = true,
                        scopeKind = "ServerExact",
                        entitled = true,
                        serverEffective = false,
                        finalEffective = false,
                    ),
                    SharedSettingsDiagnosticFeatureFlagSnapshot(
                        key = "sync_core_read_state_v1",
                        supported = true,
                        scopeKind = "ChannelExact",
                        entitled = true,
                        clientOverride = "ForceOn",
                    ),
                    SharedSettingsDiagnosticFeatureFlagSnapshot(
                        key = "future_registered_flag_v1",
                        supported = false,
                        entitled = false,
                        clearReason = "unsupported_no_effect",
                    ),
                )
        )

    @Test
    fun settingsVisualDetailHeadersDoNotRenderSubtitle() {
        assertEquals("", settingsVisualHeaderSubtitle("account-provider-linked"))
        assertEquals("", settingsVisualHeaderSubtitle("administration"))
        assertEquals("", settingsVisualHeaderSubtitle("connected-apps"))
    }

    @Test
    fun textFieldsResolveSlockInputFontFamily() {
        assertEquals(
            SlockType.Input.fontFamily,
            slockTextFieldResolvedTextStyle(TextStyle.Default).fontFamily
        )
        assertEquals(
            SlockType.Input.fontFamily,
            slockTextFieldResolvedTextStyle(SlockType.Input.copy(fontFamily = null)).fontFamily
        )
    }

    @Test
    fun settingsAccountInputForegroundUsesSpaceMono() {
        assertEquals(SlockMonoFamily, settingsAccountInputTextStyle().fontFamily)
        assertEquals(SlockMonoFamily, settingsAccountInputTextStyle(SlockColor.TextMutedGray).fontFamily)
    }

    @Test
    fun feedbackComposerBottomPaddingLiftsAboveKeyboard() {
        assertEquals(
            24.dp,
            threadComposerHostKeyboardLiftBottomPadding(
                keyboardInsetDp = 0f,
                bottomContentPadding = 24.dp
            )
        )
        assertEquals(
            292.dp,
            threadComposerHostKeyboardLiftBottomPadding(
                keyboardInsetDp = 280f,
                bottomContentPadding = 24.dp
            )
        )
    }

    @Test
    fun administrationSectionsMatchReactOrder() {
        assertEquals(
            listOf(
                "admins",
                "system_channels",
                "pending_invites",
                "join_links",
                "pre_join_agreement",
                "onboarding_agent",
                "translation",
                "member_permissions",
                "sidebar_order"
            ),
            settingsAdministrationSectionSpecs().map { it.key }
        )
    }

    @Test
    fun routeVisualPageDataFieldsStayIntentOnly() {
        assertEquals(
            setOf(
                "destination",
                "fixtureKey",
                "itemId",
                "machineId",
                "fullHeight",
                "slockVisualFullHeight"
            ),
            SettingsRouteVisualPageDataFields
        )

        val forbiddenProductPayloadFields =
            setOf(
                "server",
                "serverSnapshot",
                "connectedApps",
                "connectedAppsSnapshot",
                "apps",
                "members",
                "plans",
                "computers",
                "accessToken",
                "refreshToken",
                "authorization",
                "clientSecret",
                "oneTimeClientSecret"
            )
        assertTrue(SettingsRouteVisualPageDataFields.none { it in forbiddenProductPayloadFields })
    }

    @Test
    fun routeVisualStatePreseedsFullCaptureDestinations() {
        val administration =
            settingsRouteVisualState(
                SettingsRouteVisualPayload(
                    destination = "administration",
                    itemId = null,
                    fixtureKey = "canonical",
                    fullHeight = true
                )
            )
        assertEquals(SharedSettingsDestination.Administration, administration.route.destination)
        assertTrue(administration.home is ScreenLoadState.Content)
        assertTrue(administration.server is ScreenLoadState.Content)

        val connectedApps =
            settingsRouteVisualState(
                SettingsRouteVisualPayload(
                    destination = "connected-apps",
                    itemId = null,
                    fixtureKey = "canonical",
                    fullHeight = true
                )
            )
        assertEquals(SharedSettingsDestination.ConnectedApps, connectedApps.route.destination)
        assertTrue(connectedApps.home is ScreenLoadState.Content)
        val connectedAppsContent = connectedApps.connectedApps as ScreenLoadState.Content
        assertTrue(connectedAppsContent.data.registered.isNotEmpty())
    }

    @Test
    fun administrationRoleOptionsMatchServerRolePolicy() {
        assertEquals(listOf("owner", "admin"), settingsAdminRoleOptions("owner", "member"))
        assertEquals(listOf("owner"), settingsAdminRoleOptions("owner", "admin"))
        assertEquals(listOf("admin"), settingsAdminRoleOptions("admin", "member"))
        assertEquals(emptyList(), settingsAdminRoleOptions("admin", "admin"))
        assertEquals(emptyList(), settingsAdminRoleOptions("member", "member"))
    }

    @Test
    fun administrationPayloadRowsFilterAdminActionsByActorRole() {
        val rowsForAdmin =
            settingsAdministrationAdminRows(
                members =
                    listOf(
                        SettingsServerAdminSnapshot(id = "owner-1", name = "Owner", role = "owner"),
                        SettingsServerAdminSnapshot(id = "admin-1", name = "Admin", role = "admin"),
                        SettingsServerAdminSnapshot(id = "member-1", name = "Member", role = "member")
                    ),
                section = null,
                sectionTitle = "Admins",
                actorRole = "admin",
                actionState = null,
                errorStrings = englishErrorPresentationStringsForTest()
            )

        assertEquals(listOf("owner-1", "admin-1"), rowsForAdmin.filter { it.key.startsWith("admin:") }.map { it.key.removePrefix("admin:") })
        assertEquals(false, rowsForAdmin.first { it.value == "owner-1:member" }.enabled)
        assertEquals("Last owner", rowsForAdmin.first { it.value == "owner-1:member" }.status)
        assertEquals(false, rowsForAdmin.first { it.value == "admin-1:member" }.enabled)
        assertEquals("Owner only", rowsForAdmin.first { it.value == "admin-1:member" }.status)
        assertEquals(false, rowsForAdmin.any { it.key == SERVER_ACTION_MEMBER_REMOVE })

        val rowsForOwner =
            settingsAdministrationAdminRows(
                members =
                    listOf(
                        SettingsServerAdminSnapshot(id = "owner-1", name = "Owner", role = "owner"),
                        SettingsServerAdminSnapshot(id = "owner-2", name = "Second Owner", role = "owner"),
                        SettingsServerAdminSnapshot(id = "admin-1", name = "Admin", role = "admin")
                    ),
                section = null,
                sectionTitle = "Admins",
                actorRole = "owner",
                actionState = null,
                errorStrings = englishErrorPresentationStringsForTest()
            )

        assertEquals(true, rowsForOwner.first { it.value == "owner-1:member" }.enabled)
        assertEquals(false, rowsForOwner.any { it.key == SERVER_ACTION_MEMBER_REMOVE })

        val rowsForOwnerWhileSaving =
            settingsAdministrationAdminRows(
                members =
                    listOf(
                        SettingsServerAdminSnapshot(id = "owner-1", name = "Owner", role = "owner"),
                        SettingsServerAdminSnapshot(id = "owner-2", name = "Second Owner", role = "owner"),
                        SettingsServerAdminSnapshot(id = "admin-1", name = "Admin", role = "admin")
                    ),
                section = null,
                sectionTitle = "Admins",
                actorRole = "owner",
                actionState = SharedSettingsActionState(SERVER_ACTION_MEMBER_ROLE, "admin-1", inProgress = true),
                errorStrings = englishErrorPresentationStringsForTest()
            )
        assertEquals("Loading", rowsForOwnerWhileSaving.first { it.value == "admin-1:member" }.status)
        assertEquals(false, rowsForOwnerWhileSaving.first { it.value == "owner-1:member" }.enabled)
    }

    @Test
    fun diagnosticsPayloadUsesBasicDetailWithoutProfilerSection() {
        val payload =
            emptyList<build.raft.app.data.SharedSettingsDiagnosticsEntry>().toDiagnosticsPayload(
                state = ScreenLoadState.Content(SharedSettingsDiagnosticsSnapshot()),
                actionState = null
            )

        assertEquals("Auth and sanitized app diagnostics", payload.detail)
        assertEquals(listOf("Actions"), payload.sections.map { it.title })
    }

    @Test
    fun diagnosticsPayloadIncludesMergedAuthEnvironment() {
        val payload =
            emptyList<build.raft.app.data.SharedSettingsDiagnosticsEntry>().toDiagnosticsPayload(
                state =
                    ScreenLoadState.Content(
                        SharedSettingsDiagnosticsSnapshot(
                            about =
                                SharedSettingsAboutSnapshot(
                                    appName = "Raft",
                                    versionLabel = "1.2.3",
                                    versionCodeLabel = "10203",
                                    sourceLabel = "abc1234",
                                    sdkLabel = "36",
                                    installTimeLabel = "2026-07-09T00:00:00Z",
                                    screenLabel = "1080x2400 px",
                                    densityLabel = "2.75 (440 dpi)",
                                    fontScaleLabel = "1.0",
                                    modelLabel = "Pixel",
                                    signedInUserLabel = "Artin",
                                    selectedServerLabel = "Botiverse",
                                    sessionStatusLabel = "Signed in",
                                    socketStatusLabel = "Host active"
                                )
                        )
                    ),
                actionState = null
            )

        assertEquals(
            listOf("Environment", "Actions"),
            payload.sections.map { it.title }
        )
        val environmentRows = payload.sections.first { it.title == "Environment" }.rows.associateBy { it.key }
        assertEquals("10203", environmentRows.getValue("diagnosticsVersionCode").detail)
        assertEquals("36", environmentRows.getValue("diagnosticsVersionCode").status)
        assertEquals("Pixel", environmentRows.getValue("diagnosticsDeviceFacts").detail)
        assertEquals("1080x2400 px", environmentRows.getValue("diagnosticsDeviceFacts").status)
        assertEquals("2.75 (440 dpi)", environmentRows.getValue("diagnosticsDisplay").detail)
        assertEquals("1.0", environmentRows.getValue("diagnosticsDisplay").status)
        assertEquals("2026-07-09T00:00:00Z", environmentRows.getValue("diagnosticsInstallTime").detail)
        assertEquals("Artin", environmentRows.getValue("diagnosticsSession").detail)
        assertEquals("Botiverse", environmentRows.getValue("diagnosticsServer").detail)
    }

    @Test
    fun developerPayloadUsesSingleMergedDiagnosticsEntry() {
        val payload =
            SharedSettingsDeveloperSnapshot().toDeveloperPayload(
                state = ScreenLoadState.Content(SharedSettingsDeveloperSnapshot()),
                actionState = null,
                errorStrings = englishErrorPresentationStringsForTest()
            )
        val rows = payload.sections.first { it.title == "Diagnostics" }.rows

        assertEquals(listOf("API Server", "Diagnostics"), payload.sections.map { it.title })
        assertEquals(listOf("diagnostics", "router"), rows.map { it.key })
        assertEquals("Diagnostics", rows.first().title)
        assertTrue(rows.first().detail.contains("auth"))
        assertFalse(rows.first().detail.contains("network"))
    }

    @Test
    fun developerPayloadOpensApiEnvironmentEditor() {
        val production =
            SharedSettingsApiEnvironmentOption(
                key = "production",
                label = "Production",
                apiBaseUrl = "https://api.raft.build/api/",
                socketBaseUrl = "https://api.raft.build",
                webUrl = "https://app.raft.build"
            )
        val staging =
            SharedSettingsApiEnvironmentOption(
                key = "staging",
                label = "Staging",
                apiBaseUrl = "https://api-aws-staging.botiverse.dev/api/",
                socketBaseUrl = "https://api-aws-staging.botiverse.dev",
                webUrl = "https://raft-app-staging.botiverse.dev/s/botiverse/"
            )
        val snapshot =
            SharedSettingsDeveloperSnapshot(
                environmentKey = production.key,
                environmentLabel = production.label,
                apiBaseUrl = production.apiBaseUrl,
                socketBaseUrl = production.socketBaseUrl,
                webUrl = production.webUrl,
                updateCheck =
                    SharedSettingsUpdateCheckSnapshot(
                        supported = true,
                        statusLabel = "Current",
                        detail = "No app update is available on main."
                    ),
                options = listOf(production, staging)
            )

        val payload =
            snapshot.toDeveloperPayload(
                state = ScreenLoadState.Content(snapshot),
                actionState = null,
                errorStrings = englishErrorPresentationStringsForTest()
            )

        val apiRows = payload.sections.first { it.title == "API Server" }.rows
        val environmentRow = apiRows.first { it.key == DEVELOPER_ACTION_API_ENVIRONMENT }

        assertEquals("API Environment", environmentRow.title)
        assertEquals("Production", environmentRow.status)
        assertTrue(environmentRow.enabled)
        assertTrue(environmentRow.detail.contains("Choose presets"))
        assertFalse(apiRows.any { it.key == DEVELOPER_ACTION_HANDS_UPDATE_CHECK })
    }

    @Test
    fun openSourceLicensesPayloadUsesThreeLineLibraryRows() {
        val payload =
            ScreenLoadState.Content(
                SharedSettingsOpenSourceLicensesSnapshot(
                    sourceConfiguration = "debugRuntimeClasspath",
                    items =
                        listOf(
                            SharedSettingsOpenSourceLicenseItem(
                                id = "com.squareup.okio:okio:3.9.0",
                                group = "com.squareup.okio",
                                name = "okio",
                                version = "3.9.0",
                                displayName = "Okio",
                                licenseName = "Apache-2.0",
                                licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                                sourceUrl = "https://github.com/square/okio"
                            ),
                            SharedSettingsOpenSourceLicenseItem(
                                id = "org.example:source-only",
                                group = "org.example",
                                name = "source-only",
                                displayName = "Source Only",
                                licenseName = "",
                                sourceUrl = "https://github.com/example/source-only"
                            ),
                            SharedSettingsOpenSourceLicenseItem(
                                id = "local:missing-link",
                                name = "missing-link",
                                licenseName = "MIT"
                            )
                        )
                )
            ).toOpenSourceLicensesPayload(englishErrorPresentationStringsForTest())

        val rows = payload.sections.single { it.title == "Libraries" }.rows.associateBy { it.title }

        val okio = rows.getValue("Okio")
        assertEquals("com.squareup.okio:okio:3.9.0", okio.detail)
        assertEquals("Apache-2.0 / License link", okio.supportingDetail)
        assertEquals("License", okio.status)
        assertEquals("https://www.apache.org/licenses/LICENSE-2.0", okio.value)
        assertTrue(okio.enabled)

        val sourceOnly = rows.getValue("Source Only")
        assertEquals("org.example:source-only", sourceOnly.detail)
        assertEquals("License unknown / Source link", sourceOnly.supportingDetail)
        assertEquals("Source", sourceOnly.status)
        assertEquals("https://github.com/example/source-only", sourceOnly.value)
        assertTrue(sourceOnly.enabled)

        val missingLink = rows.getValue("missing-link")
        assertEquals("missing-link", missingLink.detail)
        assertEquals("MIT / No link", missingLink.supportingDetail)
        assertEquals("Unavailable", missingLink.status)
        assertEquals("", missingLink.value)
        assertFalse(missingLink.enabled)
    }

    @Test
    fun openSourceLicensesPayloadUsesInjectedPresentationCopyForStaticStates() {
        val copy =
            SettingsOpenSourceLicensesPayloadCopy(
                title = "开源许可",
                sectionLicenses = "许可",
                loading = "正在加载",
                loadingDetail = "正在加载许可元数据。",
                wait = "请稍候",
                emptyTitle = "未找到许可",
                emptyDetail = "未包含依赖。",
                empty = "为空"
            )

        val loading =
            ScreenLoadState.Loading.toOpenSourceLicensesPayload(
                errorStrings = englishErrorPresentationStringsForTest(),
                copy = copy
            )
        assertEquals("开源许可", loading.title)
        assertEquals("正在加载", loading.sections.single().rows.single().title)
        assertEquals("正在加载许可元数据。", loading.sections.single().rows.single().detail)
        assertEquals("请稍候", loading.sections.single().rows.single().status)

        val empty =
            ScreenLoadState.Content(
                SharedSettingsOpenSourceLicensesSnapshot(items = emptyList())
            ).toOpenSourceLicensesPayload(
                errorStrings = englishErrorPresentationStringsForTest(),
                copy = copy
            )
        assertEquals("许可", empty.sections.single().title)
        assertEquals("未找到许可", empty.sections.single().rows.single().title)
        assertEquals("未包含依赖。", empty.sections.single().rows.single().detail)
        assertEquals("为空", empty.sections.single().rows.single().status)
    }

    @Test
    fun feedbackFailurePresentationKeepsServerCodeOutOfUserFacingCopy() {
        val attachmentMaxLabel = settingsFeedbackAttachmentMaxLabel(50L * 1024L * 1024L)
        val copy =
            SettingsHandsFeedbackCopy(
                submissionFailedTitle = "发送失败",
                submissionFailedBody = "暂时无法发送反馈，请重试。",
                submissionNetworkBody = "无法连接 Hands，请检查网络后重试。",
                submissionAttachmentTooLargeBody =
                    "附件超过 %1${'$'}s 上限，请移除或选择更小的文件。"
                        .replaceLocalizedFormatArgs(attachmentMaxLabel),
                submissionPermissionTitle = "需要权限",
                submissionPermissionBody = "你没有发送反馈的权限。"
            )

        val networkFailure = copy.submissionFailure("settings_feedback_network_failed")
        assertEquals("发送失败", networkFailure.title)
        assertEquals("无法连接 Hands，请检查网络后重试。", networkFailure.composerStatus)
        assertEquals("无法连接 Hands，请检查网络后重试。", networkFailure.body)
        assertTrue(networkFailure.retryable)

        val attachmentFailure = copy.submissionFailure("settings_feedback_attachment_too_large")
        assertEquals("附件超过 50 MB 上限，请移除或选择更小的文件。", attachmentFailure.body)
        assertFalse(attachmentFailure.retryable)

        val permissionFailure = copy.submissionPermissionFailure()
        assertEquals("需要权限", permissionFailure.title)
        assertEquals("你没有发送反馈的权限。", permissionFailure.body)
        assertFalse(permissionFailure.retryable)
    }

    @Test
    fun feedbackRetryRequestMatchesTheRestoredDraftAndAttachments() {
        val attachment = PlatformFile(id = "picked-1", fileName = "screen.png")
        val request = SettingsHandsFeedbackRetryRequest("上传图片失败", listOf(attachment))

        assertTrue(request.matches("  上传图片失败  ", listOf(attachment)))
        assertFalse(request.matches("另一条反馈", listOf(attachment)))
        assertFalse(request.matches("上传图片失败", emptyList()))
    }

    @Test
    fun feedbackBottomAnchorFollowsGenuineConversationAppendWhenParked() {
        assertEquals(
            SettingsFeedbackBottomUpdatePresentation(targetIndex = 2),
            settingsFeedbackBottomUpdatePresentation(
                previousEntryIds = listOf(0, 1),
                currentEntryIds = listOf(0, 1, 2),
                busy = false,
                allowBottomAnchor = true,
                isScrollInProgress = false
            )
        )
    }

    @Test
    fun feedbackBottomAnchorTargetsBusySentinelAfterConversationAppend() {
        assertEquals(
            SettingsFeedbackBottomUpdatePresentation(targetIndex = 3),
            settingsFeedbackBottomUpdatePresentation(
                previousEntryIds = listOf(0, 1),
                currentEntryIds = listOf(0, 1, 2),
                busy = true,
                allowBottomAnchor = true,
                isScrollInProgress = false
            )
        )
    }

    @Test
    fun feedbackBottomAnchorDefersAppendDuringUserScroll() {
        assertEquals(
            SettingsFeedbackBottomUpdatePresentation(targetIndex = null),
            settingsFeedbackBottomUpdatePresentation(
                previousEntryIds = listOf(0),
                currentEntryIds = listOf(0, 1),
                busy = true,
                allowBottomAnchor = true,
                isScrollInProgress = true
            )
        )
        assertFalse(
            resolveSettingsFeedbackBottomAnchorAllowed(
                SettingsFeedbackBottomAnchorAvailabilityState(
                    currentAllow = true,
                    isScrollInProgress = true,
                    isAtBottom = true
                )
            )
        )
    }

    @Test
    fun feedbackBottomAnchorIgnoresBusyToggleAndNonAppendMutation() {
        assertEquals(
            SettingsFeedbackBottomUpdatePresentation(targetIndex = null),
            settingsFeedbackBottomUpdatePresentation(
                previousEntryIds = listOf(0, 1),
                currentEntryIds = listOf(0, 1),
                busy = true,
                allowBottomAnchor = true,
                isScrollInProgress = false
            )
        )
        assertEquals(
            SettingsFeedbackBottomUpdatePresentation(targetIndex = null),
            settingsFeedbackBottomUpdatePresentation(
                previousEntryIds = listOf(0, 1),
                currentEntryIds = listOf(0, 1),
                busy = false,
                allowBottomAnchor = true,
                isScrollInProgress = false
            )
        )
    }

    @Test
    fun feedbackBottomAnchorRecognizesCoalescedRetryOutcomeAsNewTailEntry() {
        assertEquals(
            SettingsFeedbackBottomUpdatePresentation(targetIndex = 1),
            settingsFeedbackBottomUpdatePresentation(
                previousEntryIds = listOf(0, 1),
                currentEntryIds = listOf(0, 2),
                busy = false,
                allowBottomAnchor = true,
                isScrollInProgress = false
            )
        )
    }

    @Test
    fun feedbackBottomAnchorKeepsHistoryViewportUntilUserReturnsToBottom() {
        assertFalse(
            resolveSettingsFeedbackBottomAnchorAllowed(
                SettingsFeedbackBottomAnchorAvailabilityState(
                    currentAllow = false,
                    isScrollInProgress = false,
                    isAtBottom = false
                )
            )
        )
        assertTrue(
            resolveSettingsFeedbackBottomAnchorAllowed(
                SettingsFeedbackBottomAnchorAvailabilityState(
                    currentAllow = false,
                    isScrollInProgress = false,
                    isAtBottom = true
                )
            )
        )
    }

    @Test
    fun indexedLocalizedTemplatesKeepLocaleSpecificArgumentOrder() {
        assertEquals(
            "来自 Android 发布运行时 的 3 个库",
            "来自 %2${'$'}s 的 %1${'$'}d 个库".replaceLocalizedFormatArgs(3, "Android 发布运行时")
        )
    }

    @Test
    fun aboutPayloadPreservesLocalizedRaftUpdateCopy() {
        val payload =
            SharedSettingsAboutSnapshot(
                appName = "Raft",
                versionLabel = "1.0.0",
                buildLabel = "1000000"
            ).toAboutPayload(
                webUrl = "https://app.raft.build",
                errorStrings = englishErrorPresentationStringsForTest(),
                updateCheck =
                    SharedSettingsUpdateCheckSnapshot(
                        supported = true,
                        statusLabel = "已是最新版本",
                        detail = "当前已是 Raft 最新版本，版本代码为 1000000。"
                    ),
                actionState = null
            )

        val rows = payload.sections.first { it.title == "Actions" }.rows
        val updateRow = rows.first { it.key == DEVELOPER_ACTION_HANDS_UPDATE_CHECK }

        assertEquals("Check for update", updateRow.title)
        assertEquals("已是最新版本", updateRow.status)
        assertTrue(updateRow.enabled)
        assertFalse(updateRow.detail.contains("Hands"))
        assertTrue(updateRow.detail.contains("Raft"))
        assertFalse(updateRow.detail.contains("main"))
        assertFalse(updateRow.detail.contains("渠道"))
        assertTrue(updateRow.detail.contains("最新版本"))

        val copyEnvironmentRow = rows.first { it.key == "copyEnvironmentInfo" }
        assertEquals("Copy environment info", copyEnvironmentRow.title)
        assertEquals("Copy", copyEnvironmentRow.status)
        assertTrue(copyEnvironmentRow.enabled)
        assertFalse(rows.any { it.key == "feedback" })
    }

    @Test
    fun aboutPayloadOnlyKeepsVersionBuildStatusInApplicationRows() {
        val payload =
            SharedSettingsAboutSnapshot(
                appName = "Raft",
                versionLabel = "1.0.1",
                buildLabel = "1000100",
                buildTime = "2026-07-03T19:03:28Z",
                gitCommit = "15fb7be3",
                packageName = "build.raft.app"
            ).toAboutPayload(
                webUrl = "https://app.raft.build",
                errorStrings = englishErrorPresentationStringsForTest()
            )

        val rows = payload.sections.first { it.title == "Application" }.rows.associateBy { it.key }

        assertEquals("", rows.getValue("appName").status)
        assertEquals("1000100", rows.getValue("version").status)
        assertEquals("", rows.getValue("buildTime").status)
        assertEquals("", rows.getValue("gitCommit").status)
        assertEquals("", rows.getValue("package").status)
    }

    @Test
    fun aboutAndDiagnosticsRowsCarryLongPressCopyValues() {
        val aboutPayload =
            SharedSettingsAboutSnapshot(
                appName = "Raft",
                versionLabel = "1.0.1",
                buildLabel = "1000100",
                buildTime = "2026-07-03T19:03:28Z",
                gitCommit = "15fb7be3",
                packageName = "build.raft.app"
            ).toAboutPayload(
                webUrl = "https://app.raft.build",
                errorStrings = englishErrorPresentationStringsForTest()
            )

        val aboutRows = aboutPayload.sections.first { it.title == "Application" }.rows.associateBy { it.key }
        assertEquals("Raft", aboutRows.getValue("appName").copyValue)
        assertEquals("1.0.1 1000100", aboutRows.getValue("version").copyValue)
        assertEquals("2026-07-03T19:03:28Z", aboutRows.getValue("buildTime").copyValue)
        assertEquals("15fb7be3", aboutRows.getValue("gitCommit").copyValue)
        assertEquals("build.raft.app", aboutRows.getValue("package").copyValue)

        val diagnosticsPayload =
            emptyList<build.raft.app.data.SharedSettingsDiagnosticsEntry>().toDiagnosticsPayload(
                state =
                    ScreenLoadState.Content(
                        SharedSettingsDiagnosticsSnapshot(
                            about =
                                SharedSettingsAboutSnapshot(
                                    appName = "Raft",
                                    versionLabel = "1.0.1",
                                    gitCommit = "15fb7be3",
                                    packageName = "build.raft.app"
                                )
                        )
                    ),
                actionState = null,
            )
        val environmentRows = diagnosticsPayload.sections.first { it.title == "Environment" }.rows.associateBy { it.key }
        assertEquals("Raft 1.0.1", environmentRows.getValue("diagnosticsApp").copyValue)
        assertEquals("build.raft.app", environmentRows.getValue("diagnosticsPackage").copyValue)
    }

    @Test
    fun aboutPayloadHidesUpdateCheckBeforeHostCapabilityLoads() {
        val payload =
            SharedSettingsAboutSnapshot(appName = "Raft", versionLabel = "1.0.1")
                .toAboutPayload(
                    webUrl = "https://app.raft.build",
                    errorStrings = englishErrorPresentationStringsForTest(),
                    updateCheck = SharedSettingsUpdateCheckSnapshot()
                )

        val actionRows =
            payload.sections
                .first { it.title == "Actions" }
                .rows

        assertFalse(actionRows.any { it.key == DEVELOPER_ACTION_HANDS_UPDATE_CHECK })
        assertTrue(actionRows.any { it.key == "openSlock" })
    }

    @Test
    fun aboutPayloadHidesExplicitUnsupportedUpdateChecks() {
        val payload =
            SharedSettingsAboutSnapshot(appName = "Raft", versionLabel = "1.0.1")
                .toAboutPayload(
                    webUrl = "https://app.raft.build",
                    errorStrings = englishErrorPresentationStringsForTest(),
                    updateCheck =
                        SharedSettingsUpdateCheckSnapshot(
                            statusLabel = "Disabled",
                            detail = "This build does not expose in-app update checks."
                        )
                )

        val actionRows =
            payload.sections
                .first { it.title == "Actions" }
                .rows

        assertFalse(actionRows.any { it.key == DEVELOPER_ACTION_HANDS_UPDATE_CHECK })
        assertTrue(actionRows.any { it.key == "openSlock" })
    }

    @Test
    fun emptyDiagnosticsStillAllowsDiagnosticExportsWithoutLogPreview() {
        val payload =
            emptyList<build.raft.app.data.SharedSettingsDiagnosticsEntry>().toDiagnosticsPayload(
                state =
                    ScreenLoadState.Content(SharedSettingsDiagnosticsSnapshot()),
                actionState = null
            )

        val actionRows = payload.sections.first { it.title == "Actions" }.rows.associateBy { it.key }

        // Copy + share were merged into a single export action (83eba80f).
        assertFalse("copyLogs" in actionRows)
        assertTrue(actionRows.getValue("shareLogs").enabled)
        assertEquals("Export", actionRows.getValue("shareLogs").status)
        assertTrue(actionRows.getValue("feedback").enabled)
        assertFalse("clearLogs" in actionRows)
    }
}
