package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import build.raft.app.cmp.ProvideRaftPageCover
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.LocalRaftHost
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.compat.ui.RaftProvidePageActive
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftAnnouncementsRemindersApi
import build.raft.app.data.RaftAnnouncementsRemindersApiClient
import build.raft.app.data.SharedAnnouncementStore
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.MemberId
import build.raft.app.resources.StringDesc
import build.raft.designsystem.components.RaftPageCoverSignal
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies [AppShellAnnouncementModalHost] on a real Compose composition (#proj-mobile:#326):
 * - covering the shell unmounts the announcement modal synchronously before the push WITHOUT
 *   dismissing it on the server, and returning to Home shows it again;
 * - an announcement arriving while the shell is covered (`!pageVisible` or `!pageActive`) does not
 *   mount over the covering page;
 * - explicit user dismiss clears the store and persists the dismissal.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class AnnouncementModalCoverUiTest {
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

    private class FakeAnnouncementApi : RaftAnnouncementsRemindersApi {
        val dismissCalls = mutableListOf<String>()

        override suspend fun activeAnnouncements(): HomeNetworkResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText =
                    """{"announcements":[{"id":"ann-1","title":"Release 1.29","pages":[{"body":"New feature"}],"publishedAt":"2026-11-23T00:00:00.000Z"}]}"""
            )

        override suspend fun dismissAnnouncement(announcementId: String): HomeNetworkResponse {
            dismissCalls += announcementId
            return HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")
        }

        override suspend fun reminders(ownerAgentId: MemberId, status: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"reminders":[]}""")
    }

    @Test
    fun pageCoverUnmountsWithoutDismissingAndReappearsOnReturn() =
        runRobolectricComposeUiTest {
            val api = FakeAnnouncementApi()
            val store = SharedAnnouncementStore(RaftAnnouncementsRemindersApiClient(api)) { true }
            runBlocking { store.refresh() }

            var pageVisible by mutableStateOf(true)
            var pageActive by mutableStateOf(true)
            var pageVisibleTick by mutableStateOf(0)
            var hostDidAppearTick by mutableStateOf(0)
            var modalShown = false
            val pushes = mutableListOf<Pair<String, Boolean>>()
            lateinit var pageNavigator: RaftCmpNavigator
            val navigator =
                object : RaftCmpNavigator {
                    override fun openPage(pageName: String, pageData: JsonObject) {
                        pushes += pageName to modalShown
                    }

                    override fun closePage() = Unit
                }
            val transport = HostBridgeTransport { _, _, _ -> }
            val host = RaftCmpHost(BridgeWireHostCapabilities(transport), navigator, bridgeTransport = transport)

            setContent {
                val signal = remember { RaftPageCoverSignal() }
                val modalHostState = rememberRaftUnifiedDialogHostState()
                val pager =
                    remember {
                        object : Pager() {}.apply {
                            pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                        }
                    }
                ProvideRaftPageCover(host, signal) {
                    pageNavigator = LocalRaftHost.current.navigator
                    CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                        RaftProvidePageActive(active = pageActive) {
                            RaftDesignSystemTheme {
                                RaftUnifiedDialogHostProvider(modalHostState) {
                                    Box(Modifier.fillMaxSize()) {
                                        AppShellAnnouncementModalHost(
                                            announcementStore = store,
                                            pageVisible = pageVisible,
                                            pageVisibleTick = pageVisibleTick,
                                            hostDidAppearTick = hostDidAppearTick,
                                            isOnline = true,
                                            onModalShownChanged = { modalShown = it },
                                            onDismissAnnouncement = { id ->
                                                if (store.dismiss(id)) {
                                                    runBlocking { store.persistDismissal(id) }
                                                }
                                            },
                                            onOpenUrl = {},
                                        )
                                        RaftUnifiedDialogHost(state = modalHostState, modifier = Modifier.fillMaxSize())
                                    }
                                }
                            }
                        }
                    }
                }
            }

            onNode(hasText("Release 1.29")).assertExists()
            assertTrue(modalShown)

            // Pushing a page fires the cover signal synchronously before openPage: the modal must
            // unmount without dismissing on the server.
            runOnIdle { pageNavigator.openPage("compose_thread") }
            waitForIdle()
            assertEquals(listOf("compose_thread"), pushes.map { it.first })
            assertFalse(modalShown)
            onNode(hasText("Release 1.29")).assertDoesNotExist()
            assertEquals(emptyList(), api.dismissCalls)
            assertNotNull(store.announcement.value)

            // Shell becomes covered, then user pops back to Home: pending announcement reappears.
            runOnIdle {
                pageVisible = false
                pageActive = false
            }
            waitForIdle()
            onNode(hasText("Release 1.29")).assertDoesNotExist()

            runOnIdle {
                pageVisible = true
                pageActive = true
                pageVisibleTick += 1
                hostDidAppearTick += 1
            }
            waitForIdle()
            assertTrue(modalShown)
            onNode(hasText("Release 1.29")).assertExists()
        }

    @Test
    fun coveredShellDoesNotMountArrivingAnnouncementUntilVisibleAndActive() =
        runRobolectricComposeUiTest {
            val api = FakeAnnouncementApi()
            val store = SharedAnnouncementStore(RaftAnnouncementsRemindersApiClient(api)) { true }

            var pageVisible by mutableStateOf(false)
            var pageActive by mutableStateOf(true)
            var modalShown = false
            val transport = HostBridgeTransport { _, _, _ -> }
            val navigator =
                object : RaftCmpNavigator {
                    override fun openPage(pageName: String, pageData: JsonObject) = Unit
                    override fun closePage() = Unit
                }
            val host = RaftCmpHost(BridgeWireHostCapabilities(transport), navigator, bridgeTransport = transport)

            setContent {
                val signal = remember { RaftPageCoverSignal() }
                val modalHostState = rememberRaftUnifiedDialogHostState()
                val pager =
                    remember {
                        object : Pager() {}.apply {
                            pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                        }
                    }
                ProvideRaftPageCover(host, signal) {
                    CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                        RaftProvidePageActive(active = pageActive) {
                            RaftDesignSystemTheme {
                                RaftUnifiedDialogHostProvider(modalHostState) {
                                    Box(Modifier.fillMaxSize()) {
                                        AppShellAnnouncementModalHost(
                                            announcementStore = store,
                                            pageVisible = pageVisible,
                                            pageVisibleTick = 0,
                                            hostDidAppearTick = 0,
                                            isOnline = true,
                                            onModalShownChanged = { modalShown = it },
                                            onDismissAnnouncement = { id ->
                                                if (store.dismiss(id)) {
                                                    runBlocking { store.persistDismissal(id) }
                                                }
                                            },
                                            onOpenUrl = {},
                                        )
                                        RaftUnifiedDialogHost(state = modalHostState, modifier = Modifier.fillMaxSize())
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Announcement arrives while AppShell is covered (`pageVisible = false`, e.g. under ThreadPage).
            runOnIdle { runBlocking { store.refresh() } }
            waitForIdle()
            assertNotNull(store.announcement.value)
            assertFalse(modalShown)
            onNode(hasText("Release 1.29")).assertDoesNotExist()

            // Even if pageVisible flips true while LocalRaftPageActive is false (mid-transition), stay hidden.
            runOnIdle {
                pageActive = false
                pageVisible = true
            }
            waitForIdle()
            assertFalse(modalShown)
            onNode(hasText("Release 1.29")).assertDoesNotExist()

            // Once both visible and active, mount the modal cleanly.
            runOnIdle { pageActive = true }
            waitForIdle()
            assertTrue(modalShown)
            onNode(hasText("Release 1.29")).assertExists()
        }

    @Test
    fun explicitDismissClearsAnnouncementAndPersistsServerDismissal() =
        runRobolectricComposeUiTest {
            val api = FakeAnnouncementApi()
            val store = SharedAnnouncementStore(RaftAnnouncementsRemindersApiClient(api)) { true }
            runBlocking { store.refresh() }

            var modalShown = false
            val transport = HostBridgeTransport { _, _, _ -> }
            val navigator =
                object : RaftCmpNavigator {
                    override fun openPage(pageName: String, pageData: JsonObject) = Unit
                    override fun closePage() = Unit
                }
            val host = RaftCmpHost(BridgeWireHostCapabilities(transport), navigator, bridgeTransport = transport)

            setContent {
                val signal = remember { RaftPageCoverSignal() }
                val modalHostState = rememberRaftUnifiedDialogHostState()
                val pager =
                    remember {
                        object : Pager() {}.apply {
                            pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                        }
                    }
                ProvideRaftPageCover(host, signal) {
                    CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                        RaftDesignSystemTheme {
                            RaftUnifiedDialogHostProvider(modalHostState) {
                                Box(Modifier.fillMaxSize()) {
                                    AppShellAnnouncementModalHost(
                                        announcementStore = store,
                                        pageVisible = true,
                                        pageVisibleTick = 0,
                                        hostDidAppearTick = 0,
                                        isOnline = true,
                                        onModalShownChanged = { modalShown = it },
                                        onDismissAnnouncement = { id ->
                                            if (store.dismiss(id)) {
                                                runBlocking { store.persistDismissal(id) }
                                            }
                                        },
                                        onOpenUrl = {},
                                    )
                                    RaftUnifiedDialogHost(state = modalHostState, modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }

            onNode(hasText("Release 1.29")).assertExists()
            assertTrue(modalShown)
            onNode(hasText("Got it", ignoreCase = true)).performClick()
            waitForIdle()

            assertFalse(modalShown)
            assertNull(store.announcement.value)
            assertEquals(listOf("ann-1"), api.dismissCalls)
            onNode(hasText("Release 1.29")).assertDoesNotExist()
        }
}
