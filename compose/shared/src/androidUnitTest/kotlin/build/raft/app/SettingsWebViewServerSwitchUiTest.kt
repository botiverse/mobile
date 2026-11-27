package build.raft.app

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AuthSessionEvent
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftAuthSessionEventSource
import build.raft.app.data.RaftShellAuthSessionStore
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SelectedServerStore
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.settings.SettingsWebViewAuthSnapshot
import build.raft.app.settings.SettingsWebViewSurface
import build.raft.app.settings.StandaloneSettingsWebViewContent
import build.raft.app.settings.StandaloneSettingsWebViewSpec
import build.raft.app.settings.rememberSettingsWebViewAuthSnapshot
import build.raft.app.settings.settingsBillingWebViewSpec
import build.raft.app.settings.settingsMarketplaceWebViewSpec
import build.raft.app.settings.settingsWebViewAuthEventSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression tests for `#wg-mobile-sync:#247` ("打开着网页的时候，切换 server，网页不会重新加载了").
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h600dp-xxhdpi")
class SettingsWebViewServerSwitchUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var root: View? = null

    private fun session(serverId: String, serverSlug: String): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.test"),
            accessToken = "access-token-1",
            refreshToken = "refresh-token-1",
            selectedServerId = serverId,
            selectedServerSlug = serverSlug,
            servers =
                listOf(
                    StoredAuthServer(id = "srv-a", name = "Alpha", slug = "alpha"),
                    StoredAuthServer(id = "srv-b", name = "Beta", slug = "beta"),
                ),
        )

    @Test
    fun switchingServerWhileSettingsWebPageIsOpenReplacesAndLoadsNewServerWebViewAtIdenticalBounds() =
        runRobolectricComposeUiTest {
            var snapshot by mutableStateOf(
                SettingsWebViewAuthSnapshot(
                    session = session(serverId = "srv-a", serverSlug = "alpha"),
                    event = null,
                    sessionGeneration = 1L,
                ),
            )
            setContent {
                Host {
                    StandaloneSettingsWebViewContent(
                        authSnapshot = snapshot,
                        webBaseUrl = "https://app.raft.build",
                        spec = settingsBillingWebViewSpec,
                    )
                }
            }
            waitForIdle()
            val first = webViews().single()
            assertTrue(
                first.loadedTargetUrl().contains("/s/alpha/settings/billing"),
                "Expected Server A billing URL, got: ${first.loadedTargetUrl()}",
            )

            // Switch server without resizing or moving the host container.
            runOnIdle {
                snapshot =
                    SettingsWebViewAuthSnapshot(
                        session = session(serverId = "srv-b", serverSlug = "beta"),
                        event = AuthSessionEvent.BindingMetadataChanged(generation = 2L),
                        sessionGeneration = 1L,
                    )
            }
            waitForIdle()

            val attachedAfterSwitch = webViews()
            assertEquals(1, attachedAfterSwitch.size, "Server B WebView must attach at identical bounds without relayout")
            val second = attachedAfterSwitch.single()
            assertNotSame(first, second, "Server switch must build a fresh WebView generation")
            assertTrue(
                second.loadedTargetUrl().contains("/s/beta/settings/billing"),
                "Expected Server B billing URL, got: ${second.loadedTargetUrl()}",
            )
        }

    @Test
    fun shellScopedServerSwitchReloadsWebPageEvenWhenProcessStoreAlreadySelectedTargetServer() =
        runRobolectricComposeUiTest {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                // Process store already points to Server B (e.g. another window selected it), while
                // this shell is viewing Server A.
                val processStore = InMemoryRaftAuthSessionStore(session(serverId = "srv-b", serverSlug = "beta"))
                val selectedServerStore =
                    SelectedServerStore(
                        RaftShellStoreScope.create(shellInstanceId = "window-1", initialServerId = "srv-a"),
                    )
                val shellAuthStore =
                    RaftShellAuthSessionStore(
                        delegate = processStore,
                        selectedServerStore = selectedServerStore,
                        eventScope = scope,
                    )
                // StandaloneSettingsWebViewBody selects the shell auth store's event source so it
                // receives shell-local BindingMetadataChanged events even when the process store
                // already points to Server B (and therefore emits nothing).
                val silentProcessEvents =
                    object : RaftAuthSessionEventSource {
                        override val authSessionEvents: SharedFlow<AuthSessionEvent> = MutableSharedFlow()
                    }
                val eventSource: RaftAuthSessionEventSource =
                    settingsWebViewAuthEventSource(
                        shellAuthStore = shellAuthStore,
                        fallbackProcessAuthEvents = silentProcessEvents,
                    )
                assertSame(shellAuthStore, eventSource)
                assertSame(
                    silentProcessEvents,
                    settingsWebViewAuthEventSource(
                        shellAuthStore = processStore,
                        fallbackProcessAuthEvents = silentProcessEvents,
                    ),
                )
                setContent {
                    Host {
                        val authSnapshot = rememberSettingsWebViewAuthSnapshot(shellAuthStore, eventSource)
                        StandaloneSettingsWebViewContent(
                            authSnapshot = authSnapshot,
                            webBaseUrl = "https://app.raft.build",
                            spec = settingsMarketplaceWebViewSpec,
                        )
                    }
                }
                waitForIdle()
                val first = webViews().single()
                assertTrue(
                    first.loadedTargetUrl().contains("/s/alpha/settings/integrations"),
                    "Expected Server A integrations URL, got: ${first.loadedTargetUrl()}",
                )

                runBlocking {
                    shellAuthStore.selectServerIfSessionMatches(
                        expectedAccountId = "user-1",
                        expectedAccessToken = "access-token-1",
                        expectedRefreshToken = "refresh-token-1",
                        expectedSelectedServerId = "srv-a",
                        serverId = "srv-b",
                    )
                }
                waitForIdle()

                val attachedAfterSwitch = webViews()
                assertEquals(1, attachedAfterSwitch.size, "Server B Apps WebView must attach after shell-scoped switch")
                val second = attachedAfterSwitch.single()
                assertNotSame(first, second)
                assertTrue(
                    second.loadedTargetUrl().contains("/s/beta/settings/integrations"),
                    "Expected Server B integrations URL, got: ${second.loadedTargetUrl()}",
                )
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun switchingServerReloadsWebViewEvenWhenUrlHasNoServerSlug() =
        runRobolectricComposeUiTest {
            val fixedUrlSpec =
                StandaloneSettingsWebViewSpec(
                    surface = SettingsWebViewSurface.Billing,
                    buildWebUrl = { webBaseUrl, _, _ ->
                        sharedSettingsHostShellWebUrl(webBaseUrl, "settings/billing")
                    },
                )
            var snapshot by mutableStateOf(
                SettingsWebViewAuthSnapshot(
                    session = session(serverId = "srv-a", serverSlug = "alpha"),
                    event = null,
                    sessionGeneration = 1L,
                ),
            )
            setContent {
                Host {
                    StandaloneSettingsWebViewContent(
                        authSnapshot = snapshot,
                        webBaseUrl = "https://app.raft.build",
                        spec = fixedUrlSpec,
                    )
                }
            }
            waitForIdle()
            val first = webViews().single()
            assertTrue(first.loadedTargetUrl().contains("/settings/billing"))

            runOnIdle {
                snapshot =
                    SettingsWebViewAuthSnapshot(
                        session = session(serverId = "srv-b", serverSlug = "beta"),
                        event = AuthSessionEvent.BindingMetadataChanged(generation = 2L),
                        sessionGeneration = 1L,
                    )
            }
            waitForIdle()

            val second = webViews().single()
            assertNotSame(first, second, "Server switch must rebuild WebView even when URL does not embed server slug")
            assertTrue(second.loadedTargetUrl().contains("srv-b"), "New WebView must inject Server B binding")
        }

    private fun WebView.loadedTargetUrl(): String =
        (shadowOf(this).lastLoadedUrl ?: shadowOf(this).lastLoadDataWithBaseURL?.data ?: "")
            .replace("\\/", "/")

    private fun ComposeUiTest.webViews(): List<WebView> {
        val found = mutableListOf<WebView>()
        runOnUiThread { root?.let { collectWebViews(it, found) } }
        return found
    }

    private fun collectWebViews(view: View, into: MutableList<WebView>) {
        if (view is WebView) into += view
        if (view is ViewGroup) for (index in 0 until view.childCount) collectWebViews(view.getChildAt(index), into)
    }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
        root = LocalView.current.rootView
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftDesignSystemTheme { Box(Modifier.fillMaxSize()) { content() } }
        }
    }
}
