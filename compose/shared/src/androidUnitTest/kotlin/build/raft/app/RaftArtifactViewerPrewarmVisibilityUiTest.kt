package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import build.raft.app.data.AuthSessionEvent
import build.raft.app.data.ChannelId
import build.raft.app.data.InboxItem
import build.raft.app.data.InboxItemKind
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftAuthSessionEventSource
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #wg-mobile-feedback:#257 (Hands 45eccbd9): Activity's Artifact viewer prewarm must run only
 * while Activity itself is active, its hosting pager is appeared, and the app is foregrounded.
 * Previously `SharedInboxPanel` omitted `active`, so when a 43-minute background resume refreshed
 * the access token while the user was in a Thread, the covered Activity tab constructed and loaded
 * a hidden `raft-app-warm` WebView on the main thread right as the user started scrolling.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftArtifactViewerPrewarmVisibilityUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class RefreshableAuthStore(initialSession: StoredAuthSession) :
        RaftAuthSessionStore,
        RaftAuthSessionEventSource {
        private val inner = InMemoryRaftAuthSessionStore(initialSession)
        private val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 8)
        private var generation = 1L

        override val authSessionEvents: SharedFlow<AuthSessionEvent> = events.asSharedFlow()

        override suspend fun readSession(): StoredAuthSession? = inner.readSession()

        override suspend fun readOrCreateAuthInstallationId(candidate: String): String =
            inner.readOrCreateAuthInstallationId(candidate)

        override suspend fun saveSession(session: StoredAuthSession) = inner.saveSession(session)

        override suspend fun clearSession() = inner.clearSession()

        suspend fun rotateAccessToken(nextAccessToken: String) {
            val current = checkNotNull(inner.readSession())
            inner.saveSession(current.copy(accessToken = nextAccessToken))
            generation += 1L
            events.emit(AuthSessionEvent.TokensRotated(generation))
        }
    }

    @AfterTest
    fun tearDown() {
        RaftArtifactViewerMemory.clear()
    }

    @Test
    fun hiddenActivityTokenRefreshDoesNotBuildPrewarmWebView() =
        runRobolectricComposeUiTest {
            mainClock.autoAdvance = false
            val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
            val pager = object : Pager() {}
            pager.onReceivePagerEvent(Pager.PAGER_EVENT_DID_APPEAR, JSONObject())
            val authStore = RefreshableAuthStore(testSession("tok-1"))
            val scope =
                RaftArtifactViewerScope(
                    webOrigin = "https://app.raft.build",
                    environmentId = "https://api.raft.build/api",
                    accountId = "user-1",
                    serverId = "server-1",
                )
            val objectItems =
                listOf(
                    InboxItem(
                        key = "object:art-1",
                        kind = InboxItemKind.Object,
                        channelId = ChannelId("ch-1"),
                        channelName = "general",
                    )
                )

            var active by mutableStateOf(false)
            var keptWebView by mutableStateOf(false)
            val prewarmedConfigs = mutableListOf<RaftWebViewConfig>()

            setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    RaftArtifactViewerPrewarmRuntime(
                        enabled = sharedInboxShouldPrewarmArtifactViewer(active = active, items = objectItems),
                        pager = pager,
                        authStore = authStore,
                        processAuthEvents = authStore,
                        webBaseUrl = "https://app.raft.build",
                        scope = scope,
                        conversationId = null,
                        desktopMode = false,
                        keptDocumentPresent = { keptWebView },
                        prewarmEffect = { config ->
                            if (config != null) {
                                prewarmedConfigs += config
                                keptWebView = true
                            }
                        },
                    )
                }
            }

            // 1. Activity starts hidden behind a Thread: even past settle + token refresh, no WebView is created.
            mainClock.advanceTimeBy(RAFT_ARTIFACT_VIEWER_PREWARM_DELAY_MS + 500L)
            waitForIdle()
            kotlinx.coroutines.runBlocking { authStore.rotateAccessToken("tok-2") }
            mainClock.advanceTimeBy(RAFT_ARTIFACT_VIEWER_PREWARM_DELAY_MS + 500L)
            waitForIdle()
            assertEquals(0, prewarmedConfigs.size, "inactive Activity must not prewarm WebView on token refresh")
            assertFalse(RaftArtifactViewerMemory.cache.keptDocumentUsable(scope, "tok-2", null))

            // 2. Returning to Activity (active = true) prewarms once after the settle delay.
            runOnIdle { active = true }
            mainClock.advanceTimeBy(RAFT_ARTIFACT_VIEWER_PREWARM_DELAY_MS + 500L)
            waitForIdle()
            assertEquals(1, prewarmedConfigs.size, "active Activity prewarms once after settling")
            assertTrue(RaftArtifactViewerMemory.cache.keptDocumentUsable(scope, "tok-2", null))

            // 3. Reproduce Hands 45eccbd9: open a Thread (active = false), parked WebView expires
            //    after 5 minutes, then resume refreshes the expired access token ("tok-2" -> "tok-3").
            runOnIdle {
                active = false
                keptWebView = false
                RaftArtifactViewerMemory.clear()
            }
            waitForIdle()
            kotlinx.coroutines.runBlocking { authStore.rotateAccessToken("tok-3") }
            mainClock.advanceTimeBy(RAFT_ARTIFACT_VIEWER_PREWARM_DELAY_MS + 500L)
            waitForIdle()
            assertEquals(
                1,
                prewarmedConfigs.size,
                "token refresh while covered by a Thread must not construct a new WebView",
            )
            assertFalse(RaftArtifactViewerMemory.cache.keptDocumentUsable(scope, "tok-3", null))

            // 4. Returning to Activity prewarms cleanly with the refreshed token.
            runOnIdle { active = true }
            mainClock.advanceTimeBy(RAFT_ARTIFACT_VIEWER_PREWARM_DELAY_MS + 500L)
            waitForIdle()
            assertEquals(2, prewarmedConfigs.size, "active Activity prewarms again once visible")
            assertTrue(RaftArtifactViewerMemory.cache.keptDocumentUsable(scope, "tok-3", null))
        }

    @Test
    fun coveredPagerOrBackgroundLifecycleBlocksPrewarmOnTokenRefresh() =
        runRobolectricComposeUiTest {
            mainClock.autoAdvance = false
            val owner = Owner().apply { registry.currentState = Lifecycle.State.CREATED }
            val pager = object : Pager() {}
            // Pager not appeared yet AND app in background.
            val authStore = RefreshableAuthStore(testSession("tok-a"))
            val scope =
                RaftArtifactViewerScope(
                    webOrigin = "https://app.raft.build",
                    environmentId = "https://api.raft.build/api",
                    accountId = "user-1",
                    serverId = "server-1",
                )
            val prewarmedConfigs = mutableListOf<RaftWebViewConfig>()

            setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    RaftArtifactViewerPrewarmRuntime(
                        enabled = true,
                        pager = pager,
                        authStore = authStore,
                        processAuthEvents = authStore,
                        webBaseUrl = "https://app.raft.build",
                        scope = scope,
                        desktopMode = false,
                        keptDocumentPresent = { false },
                        prewarmEffect = { config -> if (config != null) prewarmedConfigs += config },
                    )
                }
            }

            // Backgrounded + not appeared -> token refresh does nothing.
            kotlinx.coroutines.runBlocking { authStore.rotateAccessToken("tok-b") }
            mainClock.advanceTimeBy(RAFT_ARTIFACT_VIEWER_PREWARM_DELAY_MS + 500L)
            waitForIdle()
            assertEquals(0, prewarmedConfigs.size)

            // Foregrounded, but pager still covered (DID_DISAPPEAR) -> token refresh still does nothing.
            runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            kotlinx.coroutines.runBlocking { authStore.rotateAccessToken("tok-c") }
            mainClock.advanceTimeBy(RAFT_ARTIFACT_VIEWER_PREWARM_DELAY_MS + 500L)
            waitForIdle()
            assertEquals(0, prewarmedConfigs.size)

            // Pager appears while foregrounded -> prewarms after settle delay.
            runOnIdle { pager.onReceivePagerEvent(Pager.PAGER_EVENT_DID_APPEAR, JSONObject()) }
            mainClock.advanceTimeBy(RAFT_ARTIFACT_VIEWER_PREWARM_DELAY_MS + 500L)
            waitForIdle()
            assertEquals(1, prewarmedConfigs.size)
        }

    @Test
    fun inboxPrewarmGateRequiresActiveAndArtifactRow() {
        val messageRow =
            InboxItem(
                key = "channel:ch-1",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("ch-1"),
                channelName = "general",
            )
        val objectRow =
            InboxItem(
                key = "object:art-1",
                kind = InboxItemKind.Object,
                channelId = ChannelId("ch-1"),
                channelName = "general",
            )

        assertFalse(sharedInboxShouldPrewarmArtifactViewer(active = false, items = listOf(objectRow)))
        assertFalse(sharedInboxShouldPrewarmArtifactViewer(active = true, items = listOf(messageRow)))
        assertTrue(sharedInboxShouldPrewarmArtifactViewer(active = true, items = listOf(messageRow, objectRow)))
    }

    private fun testSession(accessToken: String): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user-1@example.test"),
            accessToken = accessToken,
            refreshToken = "refresh-1",
            selectedServerId = "server-1",
            selectedServerSlug = "server-1",
            servers = listOf(StoredAuthServer(id = "server-1", name = "server-1", slug = "server-1")),
        )
}
