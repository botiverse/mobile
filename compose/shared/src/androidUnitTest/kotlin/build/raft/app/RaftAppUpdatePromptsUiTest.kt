package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.cmp.ProvideRaftPageCover
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.compat.ui.RaftProvidePageActive
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.APP_UPDATE_HOME_NOTIFICATION_ID
import build.raft.app.data.AppLocale
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.RaftAnnouncementsRemindersApi
import build.raft.app.data.RaftAnnouncementsRemindersApiClient
import build.raft.app.data.RaftAppUpdateCoordinator
import build.raft.app.data.RaftAppUpdatePromptStore
import build.raft.app.data.RaftAppUpdateStrongPromptReason
import build.raft.app.data.RaftMinBuildGateDecision
import build.raft.app.data.SharedAnnouncementStore
import build.raft.app.data.SharedSettingsUpdateCheckSnapshot
import build.raft.app.data.SharedSettingsUpdateTransactionState
import build.raft.app.data.stringDescLocaleType
import build.raft.app.data.toSharedHomeAppUpdateNotification
import build.raft.app.model.MemberId
import build.raft.app.resources.StringDesc
import build.raft.app.settings.AppShellUpdatePromptHost
import build.raft.app.settings.RAFT_APP_UPDATE_STRONG_DIALOG_LATER_TAG
import build.raft.app.settings.RAFT_APP_UPDATE_STRONG_DIALOG_TAG
import build.raft.app.settings.RAFT_APP_UPDATE_STRONG_DIALOG_UPDATE_TAG
import build.raft.app.settings.RaftAppUpdateStrongPromptDialog
import build.raft.app.settings.toHomeNotificationItem
import build.raft.app.toHomeNotificationItem
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftPageCoverSignal
import build.raft.designsystem.components.RaftToastActionTimeoutMillis
import build.raft.designsystem.components.RaftToastHost
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.components.RaftToastIntent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1024dp-h800dp-mdpi")
class RaftAppUpdatePromptsUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    private fun testServer(): RaftServerSwitcherItem =
        RaftServerSwitcherItem(id = "s1", name = "Botiverse", slug = "botiverse")

    private fun testPager(): Pager =
        object : Pager() {}.apply {
            pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
        }

    private fun testHost(): RaftCmpHost {
        val transport = HostBridgeTransport { _, _, _ -> }
        val navigator =
            object : RaftCmpNavigator {
                override fun openPage(pageName: String, pageData: JsonObject) = Unit
                override fun closePage() = Unit
            }
        return RaftCmpHost(BridgeWireHostCapabilities(transport), navigator, bridgeTransport = transport)
    }

    private class FakeAnnouncementApi : RaftAnnouncementsRemindersApi {
        override suspend fun activeAnnouncements(): HomeNetworkResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText =
                    """{"announcements":[{"id":"ann-1","title":"Release Notes Announcement","pages":[{"body":"Body"}],"publishedAt":"2026-11-23T00:00:00.000Z"}]}"""
            )

        override suspend fun dismissAnnouncement(announcementId: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")

        override suspend fun reminders(ownerAgentId: MemberId, status: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"reminders":[]}""")
    }

    @Test
    fun topBarUpdateButtonRendersLabelsCompactModeAndHandlesClick() =
        runRobolectricComposeUiTest {
            var width by mutableStateOf(1024.dp)
            var action by mutableStateOf<RaftAppTopBarUpdateAction?>(null)
            var clicks = 0

            setContent {
                val pager = remember { testPager() }
                val topBar = remember { RaftAppShellTopBarState() }
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), LocalRaftAppShellTopBar provides topBar) {
                    RaftAppShellTopBar(
                        currentServer = testServer(),
                        servers = listOf(testServer()),
                        serverMenuOpen = false,
                        onWorkspaceClick = {},
                        onServerPick = {},
                        searchLabel = "Search",
                        onSearchClick = {},
                        windowWidth = width,
                        modifier = Modifier.width(width),
                        bell = topBar,
                        updateAction = action,
                    )
                }
            }

            // Hidden when updateAction is null.
            onNode(hasTestTag(RAFT_TOP_BAR_UPDATE_BUTTON_TAG)).assertDoesNotExist()

            // Wide (>= 920dp): shows label + pink attention dot + invokes click callback.
            runOnIdle {
                action = RaftAppTopBarUpdateAction(label = "Update", tooltip = "Update Raft to 1.30.0", onClick = { clicks += 1 })
            }
            waitForIdle()
            onNode(hasTestTag(RAFT_TOP_BAR_UPDATE_BUTTON_TAG)).assertExists()
            onNode(hasTestTag(RAFT_TOP_BAR_UPDATE_DOT_TAG)).assertExists()
            onNode(hasText("Update")).assertExists()
            onNode(hasTestTag(RAFT_TOP_BAR_UPDATE_BUTTON_TAG)).performClick()
            waitForIdle()
            assertEquals(1, clicks)

            // Switches label cleanly for Install and Restart to update.
            runOnIdle {
                action = RaftAppTopBarUpdateAction(label = "Install", tooltip = "Install Raft 1.30.0", onClick = { clicks += 1 })
            }
            waitForIdle()
            onNode(hasText("Install")).assertExists()

            runOnIdle {
                action =
                    RaftAppTopBarUpdateAction(
                        label = "Restart to update",
                        tooltip = "Restart to update Raft to 1.30.0",
                        onClick = { clicks += 1 },
                    )
            }
            waitForIdle()
            onNode(hasText("Restart to update")).assertExists()

            // Compact (< 920dp): icon-only square button with tooltip contentDescription and pink dot, no text label.
            runOnIdle { width = 840.dp }
            waitForIdle()
            onNode(hasTestTag(RAFT_TOP_BAR_UPDATE_BUTTON_TAG)).assertExists()
            onNode(hasTestTag(RAFT_TOP_BAR_UPDATE_DOT_TAG)).assertExists()
            onNode(hasText("Restart to update")).assertDoesNotExist()
            onNode(hasContentDescription("Restart to update Raft to 1.30.0")).assertExists()
            onNode(hasTestTag(RAFT_TOP_BAR_UPDATE_BUTTON_TAG)).performClick()
            waitForIdle()
            assertEquals(2, clicks)
        }

    @Test
    fun wideModeHomeSidebarDeduplicatesAppUpdateBannerWhenTopBarIsDrawn() =
        runRobolectricComposeUiTest {
            var topBarState by mutableStateOf<RaftAppShellTopBarState?>(RaftAppShellTopBarState().apply { drawn = true })
            val updateNotification =
                RaftHomeNotificationItem(
                    id = APP_UPDATE_HOME_NOTIFICATION_ID,
                    title = "App update available",
                    body = "Current version 1.29.0. Latest version 1.30.0.",
                )

            setContent {
                val pager = remember { testPager() }
                CompositionLocalProvider(
                    LocalActivity provides RaftPageActivity(pager),
                    LocalRaftAppShellTopBar provides topBarState,
                ) {
                    RaftStructuralHomeShell(
                        currentServerName = "Botiverse",
                        currentServerId = "s1",
                        servers = listOf(testServer()),
                        notifications = listOf(updateNotification),
                        serverSwitcherInTopBar = topBarState != null,
                        showBottomBar = false,
                        applyTopSafeArea = false,
                    )
                }
            }

            // Wide mode with drawn top bar: TopBarUpdateButton owns L1 entry, sidebar hides duplicate banner.
            onNode(hasTestTag(HOME_APP_UPDATE_BANNER_TAG)).assertDoesNotExist()

            // Wide layout without drawn top bar (e.g. Duo inner screen): sidebar keeps HomeAppUpdateBanner.
            runOnIdle { topBarState = RaftAppShellTopBarState().apply { drawn = false } }
            waitForIdle()
            onNode(hasTestTag(HOME_APP_UPDATE_BANNER_TAG)).assertExists()

            // Narrow/phone layout (no top bar): sidebar keeps HomeAppUpdateBanner.
            runOnIdle { topBarState = null }
            waitForIdle()
            onNode(hasTestTag(HOME_APP_UPDATE_BANNER_TAG)).assertExists()
        }

    @Test
    fun homeAppUpdateBannerSwitchesCopyBetweenAvailableReadyToInstallAndReadyToRestart() =
        runRobolectricComposeUiTest {
            var snapshot by mutableStateOf(
                SharedSettingsUpdateCheckSnapshot(
                    supported = true,
                    updateAvailable = true,
                    currentVersionLabel = "1.29.0",
                    currentVersionCode = 12_900_000L,
                    latestVersionLabel = "1.30.0",
                    latestVersionCode = 13_000_000L,
                    patchDownloadSizeBytes = 29_696L,
                    transactionState = SharedSettingsUpdateTransactionState.Idle,
                )
            )
            var clicks = 0

            setContent {
                RaftDesignSystemTheme {
                    val notice = snapshot.toSharedHomeAppUpdateNotification()!!.toHomeNotificationItem()
                    HomeAppUpdateBanner(notification = notice, onClick = { clicks += 1 })
                }
            }

            // Idle: "App update available" + "View", zero size numbers.
            onNode(hasTestTag(HOME_APP_UPDATE_BANNER_TAG)).assertExists()
            onNode(hasText("App update available")).assertExists()
            onNode(hasText("View")).assertExists()
            onNode(hasText("29.0 KB", substring = true)).assertDoesNotExist()
            onNode(hasTestTag(HOME_APP_UPDATE_BANNER_TAG)).performClick()
            waitForIdle()
            assertEquals(1, clicks)

            // ReadyToInstall: "Update ready to install" + "Install".
            runOnIdle {
                snapshot = snapshot.copy(transactionState = SharedSettingsUpdateTransactionState.ReadyToInstall)
            }
            waitForIdle()
            onNode(hasText("Update ready to install")).assertExists()
            onNode(hasText("Install")).assertExists()
            onNode(hasText("View")).assertDoesNotExist()

            // ReadyToRestart: "Update ready to install" + "Restart to update".
            runOnIdle {
                snapshot = snapshot.copy(transactionState = SharedSettingsUpdateTransactionState.ReadyToRestart)
            }
            waitForIdle()
            onNode(hasText("Update ready to install")).assertExists()
            onNode(hasText("Restart to update")).assertExists()
        }

    @Test
    fun strongPromptDialogRendersStrippedMarkdownDownloadSizeAndReadyState() =
        runRobolectricComposeUiTest {
            var updateClicks = 0
            var laterClicks = 0
            var dismissClicks = 0
            var snapshot by mutableStateOf(
                SharedSettingsUpdateCheckSnapshot(
                    supported = true,
                    updateAvailable = true,
                    currentVersionLabel = "1.28.0",
                    currentVersionCode = 12_800_000L,
                    latestVersionLabel = "1.30.0",
                    latestVersionCode = 13_000_000L,
                    latestChangelog =
                        "### ✨ **New**\n- **New update bar**: Faster `install` ([docs](https://raft.build))\n- Better reminders\n- Fourth line hidden",
                    patchDownloadSizeBytes = 29_696L,
                    fullDownloadSizeBytes = 94_411_540L,
                    forceUpdate = true,
                )
            )
            var reason by mutableStateOf(RaftAppUpdateStrongPromptReason.ForceUpdate)

            setContent {
                RaftDesignSystemTheme {
                    RaftAppUpdateStrongPromptDialog(
                        snapshot = snapshot,
                        reason = reason,
                        onUpdateNow = { updateClicks += 1 },
                        onRemindLater = { laterClicks += 1 },
                        onDismiss = { dismissClicks += 1 },
                    )
                }
            }

            onNode(hasTestTag(RAFT_APP_UPDATE_STRONG_DIALOG_TAG)).assertExists()
            onNode(hasText("Update Raft to 1.30.0")).assertExists()
            // Not ready: shows actual download size (patch wins over full) next to version pair.
            onNode(hasText("1.28.0 → 1.30.0 · 29.0 KB")).assertExists()
            onNode(hasText("✨ New")).assertExists()
            onNode(hasText("• New update bar: Faster install (docs)")).assertExists()
            onNode(hasText("• Fourth line hidden")).assertDoesNotExist()
            onNode(hasText("Update now")).assertExists()
            onNode(hasText("Later (24h)")).assertExists()

            // When no patch exists, falls back to full download size cleanly.
            runOnIdle {
                snapshot = snapshot.copy(patchDownloadSizeBytes = 0L)
            }
            waitForIdle()
            onNode(hasText("1.28.0 → 1.30.0 · 90.0 MB")).assertExists()

            onNode(hasTestTag(RAFT_APP_UPDATE_STRONG_DIALOG_UPDATE_TAG)).performClick()
            waitForIdle()
            assertEquals(1, updateClicks)

            onNode(hasTestTag(RAFT_APP_UPDATE_STRONG_DIALOG_LATER_TAG)).performClick()
            waitForIdle()
            assertEquals(1, laterClicks)
            assertEquals(0, dismissClicks)

            // ReadyToRestart omits download size and switches title/CTA to Ready + Restart to update.
            runOnIdle {
                snapshot =
                    snapshot.copy(
                        patchDownloadSizeBytes = 29_696L,
                        transactionState = SharedSettingsUpdateTransactionState.ReadyToRestart,
                    )
                reason = RaftAppUpdateStrongPromptReason.ReadyToRestart
            }
            waitForIdle()
            onNode(hasText("Update 1.30.0 is ready")).assertExists()
            onNode(hasText("1.28.0 → 1.30.0")).assertExists()
            onNode(hasText("29.0 KB", substring = true)).assertDoesNotExist()
            onNode(hasText("Restart to update")).assertExists()
        }

    @Test
    fun updatePromptHostDoesNotFlickerDismissOnPresentAndSnoozesOnLater() =
        runRobolectricComposeUiTest {
            val nowMs = 1_700_000_000_000L
            val kvStore = InMemoryRaftKeyValueStore()
            val promptStore = RaftAppUpdatePromptStore(kvStore)
            val coordinator =
                RaftAppUpdateCoordinator(
                    store = promptStore,
                    now = { nowMs },
                    minBuildGateDecision = { RaftMinBuildGateDecision.None },
                )
            coordinator.recordAppLaunch(12_800_000L, "1.28.0")
            coordinator.publishSnapshot(
                SharedSettingsUpdateCheckSnapshot(
                    supported = true,
                    updateAvailable = true,
                    currentVersionLabel = "1.28.0",
                    currentVersionCode = 12_800_000L,
                    latestVersionLabel = "1.30.0",
                    latestVersionCode = 13_000_000L,
                    forceUpdate = true,
                )
            )
            assertTrue(coordinator.state.value.decision.showStrongPromptCandidate)

            val announcementStore = SharedAnnouncementStore(RaftAnnouncementsRemindersApiClient(FakeAnnouncementApi())) { true }
            runBlocking { announcementStore.refresh() }

            var openedPage = 0
            var snoozedEvents = 0
            val host = testHost()

            setContent {
                val signal = remember { RaftPageCoverSignal() }
                val pager = remember { testPager() }
                ProvideRaftPageCover(host, signal) {
                    CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                        RaftProvidePageActive(active = true) {
                            RaftDesignSystemTheme {
                                Box(Modifier.fillMaxSize()) {
                                    AppShellUpdatePromptHost(
                                        pageVisible = true,
                                        userDialogActive = false,
                                        announcementStore = announcementStore,
                                        announcementModalShown = false,
                                        onOpenUpdatePage = { openedPage += 1 },
                                        onCompleteRestart = {},
                                        onSnoozed = { snoozedEvents += 1 },
                                        coordinator = coordinator,
                                        minBuildGateDecision = RaftMinBuildGateDecision.None,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // P2 Announcement is pending -> L2 update strong prompt must wait and NOT consume cooldown.
            onNode(hasTestTag(RAFT_APP_UPDATE_STRONG_DIALOG_TAG)).assertDoesNotExist()
            assertEquals(0L, promptStore.readState().lastStrongPromptAtMs)

            // Dismiss announcement -> L2 strong prompt presents, records cooldown, and stays open (no flicker-close).
            runOnIdle { announcementStore.dismiss("ann-1") }
            waitForIdle()
            onNode(hasTestTag(RAFT_APP_UPDATE_STRONG_DIALOG_TAG)).assertExists()
            assertEquals(nowMs, promptStore.readState().lastStrongPromptAtMs)
            assertFalse(coordinator.state.value.decision.showStrongPromptCandidate)

            // Clicking Later (24h) dismisses modal, persists 24h snooze, and notifies listeners.
            onNode(hasTestTag(RAFT_APP_UPDATE_STRONG_DIALOG_LATER_TAG)).performClick()
            waitForIdle()
            onNode(hasTestTag(RAFT_APP_UPDATE_STRONG_DIALOG_TAG)).assertDoesNotExist()
            assertEquals(1, snoozedEvents)
            assertEquals(0, openedPage)
            assertTrue(coordinator.state.value.decision.isSnoozed)
            assertEquals(13_000_000L, promptStore.readState().snoozedVersionCode)
        }

    @Test
    fun postUpdateWhatsNewToastFiresOnceWith8sDurationOpensReleaseNotesAndRespectsHardBlock() =
        runRobolectricComposeUiTest {
            val kvStore = InMemoryRaftKeyValueStore()
            val promptStore = RaftAppUpdatePromptStore(kvStore)
            var gate: RaftMinBuildGateDecision =
                RaftMinBuildGateDecision.Block(
                    threshold = 13_100_000L,
                    latestBuild = 13_100_000L,
                    updateUrl = "https://example.test/raft.apk",
                )
            val coordinator =
                RaftAppUpdateCoordinator(
                    store = promptStore,
                    now = { 1_700_000_000_000L },
                    minBuildGateDecision = { gate },
                )
            // Simulate upgrading from 1.29.0 -> 1.30.0 while L3 hard block is active.
            coordinator.recordAppLaunch(12_900_000L, "1.29.0")
            coordinator.recordAppLaunch(13_000_000L, "1.30.0")

            val toastHostState = RaftToastHostState()
            val openedVersions = mutableListOf<String>()
            var activeGate by mutableStateOf<RaftMinBuildGateDecision>(gate)
            val host = testHost()

            setContent {
                val signal = remember { RaftPageCoverSignal() }
                val pager = remember { testPager() }
                ProvideRaftPageCover(host, signal) {
                    CompositionLocalProvider(
                        LocalActivity provides RaftPageActivity(pager),
                        LocalRaftToastHostState provides toastHostState,
                    ) {
                        RaftProvidePageActive(active = true) {
                            RaftDesignSystemTheme {
                                Box(Modifier.fillMaxSize()) {
                                    AppShellUpdatePromptHost(
                                        pageVisible = true,
                                        userDialogActive = false,
                                        announcementStore = null,
                                        announcementModalShown = false,
                                        onOpenUpdatePage = {},
                                        onCompleteRestart = {},
                                        onSnoozed = {},
                                        onOpenReleaseNotes = { openedVersions += it },
                                        coordinator = coordinator,
                                        minBuildGateDecision = activeGate,
                                    )
                                    RaftToastHost(toastHostState)
                                }
                            }
                        }
                    }
                }
            }

            // While L3 hard block is active, the L0 post-update toast must NOT fire or dismiss itself.
            assertTrue(toastHostState.visible.isEmpty())
            assertEquals(0L, promptStore.readState().dismissedPostUpdateVersionCode)

            // Once L3 clears, the 8-second dismissible Info toast fires once and persists dismissal.
            runOnIdle {
                gate = RaftMinBuildGateDecision.None
                activeGate = RaftMinBuildGateDecision.None
                coordinator.onMinBuildGateDecisionChanged(RaftMinBuildGateDecision.None)
            }
            waitForIdle()

            val toast = toastHostState.visible.single()
            assertEquals("Updated to 1.30.0", toast.title)
            assertEquals("What's new", toast.actionLabel)
            assertEquals(RaftToastIntent.Info, toast.intent)
            assertEquals(RaftToastActionTimeoutMillis, toast.durationMillis)
            assertEquals(13_000_000L, promptStore.readState().dismissedPostUpdateVersionCode)

            onNode(hasText("Updated to 1.30.0")).assertExists()
            onNode(hasText("What's new")).performClick()
            waitForIdle()

            assertEquals(listOf("13000000"), openedVersions)
            assertTrue(toastHostState.visible.isEmpty())
        }
}
