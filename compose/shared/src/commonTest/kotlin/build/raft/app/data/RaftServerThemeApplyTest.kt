package build.raft.app.data

import build.raft.app.RaftAppAppearance
import build.raft.app.RaftAppThemeState
import build.raft.app.model.RaftServerThemeLook
import build.raft.designsystem.tokens.RaftThemeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Step 4 of the per-server theme: the selected server's look is worn app-wide and seeds a cold start. */
@OptIn(ExperimentalCoroutinesApi::class)
class RaftServerThemeApplyTest {
    private val serverA = "11111111-1111-4111-8111-111111111111"
    private val serverB = "22222222-2222-4222-8222-222222222222"

    private class MapPreferenceStore : SharedPreferenceStore {
        val values = mutableMapOf<String, String?>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            values[key] = value
        }
    }

    private lateinit var preferences: MapPreferenceStore

    @BeforeTest
    fun installPreferences() {
        preferences = MapPreferenceStore()
        RaftAppAppearanceRuntime.installRepositoryForTests(KeyValueRaftAppearancePreferenceRepository(preferences))
        // Nothing persisted unless a test says so; never the real store.
        RaftAppAppearanceRuntime.installSessionSnapshotReaderForTests { null }
        RaftAppThemeState.setRaftTheme(RaftThemeId.Raft, decor = false)
    }

    @AfterTest
    fun restore() {
        RaftAppAppearanceRuntime.installRepositoryForTests(null)
        RaftAppAppearanceRuntime.installSessionSnapshotReaderForTests(null)
        RaftAppThemeState.apply(RaftAppAppearance.Light)
        RaftAppThemeState.onSystemDarkChanged(false)
        RaftAppThemeState.setRaftTheme(RaftThemeId.Raft, decor = false)
    }

    private fun session(
        selectedServerId: String? = serverA,
        serverATheme: String? = null,
        serverAPlan: String? = "pro",
        serverADecor: Boolean = true,
        serverBTheme: String? = null,
        serverBPlan: String? = "pro",
    ): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user-1@raft.test"),
            accessToken = "token-a",
            refreshToken = "token-r",
            selectedServerId = selectedServerId,
            servers = listOf(
                StoredAuthServer(
                    id = serverA,
                    name = "Acme",
                    slug = "acme",
                    plan = serverAPlan,
                    theme = serverATheme,
                    themeDecor = serverADecor,
                ),
                StoredAuthServer(id = serverB, name = "Side", slug = "side", plan = serverBPlan, theme = serverBTheme),
            ),
        )

    private fun TestScope.follower(): RaftServerThemeFollower =
        RaftServerThemeFollower(
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            apply = RaftAppAppearanceRuntime::applyServerThemeLook,
        )

    private fun assertWorn(theme: RaftThemeId, decor: Boolean) {
        assertEquals(theme, RaftAppThemeState.raftTheme)
        assertEquals(decor, RaftAppThemeState.decor)
    }

    // --- Apply ---------------------------------------------------------------------------------

    @Test
    fun theAppWearsTheSelectedServerThemeAcrossSwitchesAndSignsOutToRaft() = runTest {
        val sessions = MutableStateFlow<StoredAuthSession?>(session(serverATheme = "bots", serverBTheme = "space"))
        val selected = MutableStateFlow<String?>(null)
        follower().follow("shell-1", raftServerThemeLookFlow(sessions, selected))

        // Cold start: no shell selection yet, the session's own current server.
        assertWorn(RaftThemeId.Bots, decor = true)
        selected.value = serverB
        runCurrent()
        assertWorn(RaftThemeId.Space, decor = true)
        // appearance:updated / a settings save land on the stored server.
        sessions.value = session(serverATheme = "bots", serverBTheme = "quest")
        runCurrent()
        assertWorn(RaftThemeId.Quest, decor = true)
        selected.value = serverA
        runCurrent()
        assertWorn(RaftThemeId.Bots, decor = true)
        // Sign-out: back to Raft Yellow, the default.
        sessions.value = null
        runCurrent()
        assertWorn(RaftThemeId.Raft, decor = true)
    }

    @Test
    fun aProThemeOnAFreeServerShowsAsRaftUntilThePlanChanges() = runTest {
        val sessions =
            MutableStateFlow<StoredAuthSession?>(session(serverATheme = "boring", serverAPlan = "free", serverADecor = false))
        follower().follow("shell-1", raftServerThemeLookFlow(sessions, MutableStateFlow(serverA)))

        assertWorn(RaftThemeId.Raft, decor = false)
        // The server list refresh brings the paid plan: the member's choice comes back.
        sessions.value = session(serverATheme = "boring", serverAPlan = "pro", serverADecor = false)
        runCurrent()
        assertWorn(RaftThemeId.Boring, decor = false)
        sessions.value = session(serverATheme = "boring", serverAPlan = "free", serverADecor = false)
        runCurrent()
        assertWorn(RaftThemeId.Raft, decor = false)
    }

    @Test
    fun aShellTakesOverFromTheProcessLookAndStopsIt() = runTest {
        val sessions = MutableStateFlow<StoredAuthSession?>(session(serverATheme = "desk", serverBTheme = "plants"))
        val follower = follower()
        follower.follow("process", raftServerThemeLookFlow(sessions, flowOf(null)))
        assertWorn(RaftThemeId.Desk, decor = true)

        // A second window's shell on server B takes over.
        follower.follow("shell-2", raftServerThemeLookFlow(sessions, MutableStateFlow(serverB)))
        assertWorn(RaftThemeId.Plants, decor = true)
        assertEquals("shell-2", follower.currentKey)
        // The process flow no longer applies: a change on A does not repaint.
        sessions.value = session(serverATheme = "food", serverBTheme = "plants")
        runCurrent()
        assertWorn(RaftThemeId.Plants, decor = true)
        // Following the same shell again is a no-op (no restart, no re-apply needed).
        follower.follow("shell-2", raftServerThemeLookFlow(sessions, MutableStateFlow(serverA)))
        assertWorn(RaftThemeId.Plants, decor = true)
    }

    // --- Cold-start seed -----------------------------------------------------------------------

    @Test
    fun aColdStartWearsThePersistedSessionLookBeforeTheFirstFrame() {
        RaftAppAppearanceRuntime.installSessionSnapshotReaderForTests {
            session(selectedServerId = serverB, serverBTheme = "bots")
        }
        RaftAppAppearanceRuntime.ensureLoaded()
        assertWorn(RaftThemeId.Bots, decor = true)

        // The shell's flow then confirms or corrects it; a later ensureLoaded (the page registry
        // fallback) never re-seeds over it.
        RaftAppAppearanceRuntime.applyServerThemeLook(RaftServerThemeLook(RaftThemeId.Space, decor = false))
        RaftAppAppearanceRuntime.ensureLoaded()
        assertWorn(RaftThemeId.Space, decor = false)
    }

    @Test
    fun theColdStartSeedAppliesTheProGateAndDecor() {
        RaftAppAppearanceRuntime.installSessionSnapshotReaderForTests {
            session(serverATheme = "boring", serverAPlan = "free", serverADecor = false)
        }
        RaftAppAppearanceRuntime.ensureLoaded()
        assertWorn(RaftThemeId.Raft, decor = false)
    }

    @Test
    fun aSignedOutColdStartSeedsRaft() {
        RaftAppThemeState.setRaftTheme(RaftThemeId.Quest, decor = false)
        RaftAppAppearanceRuntime.installSessionSnapshotReaderForTests { null }
        RaftAppAppearanceRuntime.ensureLoaded()
        assertWorn(RaftThemeId.Raft, decor = true)
    }

    @Test
    fun theSeedNeverOverridesALookTheSessionFlowAlreadyApplied() {
        RaftAppAppearanceRuntime.installSessionSnapshotReaderForTests { session(serverATheme = "quest") }
        RaftAppAppearanceRuntime.applyServerThemeLook(RaftServerThemeLook(RaftThemeId.Weather, decor = false))
        RaftAppAppearanceRuntime.ensureLoaded()
        assertWorn(RaftThemeId.Weather, decor = false)
    }

    @Test
    fun anUnreadableSessionStoreLeavesTheSeedForLater() {
        RaftAppAppearanceRuntime.installSessionSnapshotReaderForTests { error("store not installed") }
        RaftAppAppearanceRuntime.ensureLoaded()
        assertWorn(RaftThemeId.Raft, decor = false)
        RaftAppAppearanceRuntime.installSessionSnapshotReaderForTests { session(serverATheme = "plants") }
        RaftAppAppearanceRuntime.ensureLoaded()
        assertWorn(RaftThemeId.Plants, decor = true)
    }

    // --- Host status bar before the page reports -----------------------------------------------

    @Test
    fun hostStatusBarIconsFollowTheFrameOnTheShellAndTheCanvasElsewhere() {
        RaftAppThemeState.apply(RaftAppAppearance.Light)
        RaftAppThemeState.setRaftTheme(RaftThemeId.Space)
        // Space light: a dark purple frame on a white canvas.
        assertTrue(RaftAppAppearanceRuntime.statusBarBackdropDark(frameUnderStatusBar = true))
        assertFalse(RaftAppAppearanceRuntime.statusBarBackdropDark(frameUnderStatusBar = false))
        assertTrue(RaftAppAppearanceRuntime.hostSystemBars(frameUnderStatusBar = true).lightNavigationBars)

        RaftAppThemeState.setRaftTheme(RaftThemeId.Bots)
        assertFalse(RaftAppAppearanceRuntime.statusBarBackdropDark(frameUnderStatusBar = true), "Bots light blue: dark icons")
        RaftAppThemeState.setRaftTheme(RaftThemeId.Raft)
        assertFalse(RaftAppAppearanceRuntime.statusBarBackdropDark(frameUnderStatusBar = true), "Raft Yellow: dark icons")

        RaftAppThemeState.apply(RaftAppAppearance.Dark)
        assertTrue(RaftAppAppearanceRuntime.statusBarBackdropDark(frameUnderStatusBar = true))
        assertTrue(RaftAppAppearanceRuntime.statusBarBackdropDark(frameUnderStatusBar = false))
        assertFalse(RaftAppAppearanceRuntime.hostSystemBars(frameUnderStatusBar = false).lightNavigationBars)
    }
}
