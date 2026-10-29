package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.awaitCancellation
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.data.RaftListPaneKind
import build.raft.app.data.applyVisualCaptureLocale
import build.raft.app.data.restoreVisualCaptureLocale
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat12Hour
import build.raft.app.resources.Res
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftSideColumnKeepOut
import build.raft.designsystem.tokens.RaftThemeId
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.jetbrains.compose.resources.stringResource
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer

/**
 * The Play 8-shot set ([StorePlay8ScreenshotsTest]: same scenes, demo content, Raft yellow) for the
 * App Store (artin + DevOps, 2026-10-29): iPhone and iPhone Duo.
 *
 * - `iphone` (430x836dp: the 6.9" iPhone's 932pt less the 62pt status bar and the 34pt home
 *   indicator, which `frames.py` draws): every scene. The iOS app's tab bar is UIKit's (iOS 26
 *   Liquid Glass capsule plus the server's round button), not Compose's, so the tab scenes render
 *   no bar and let the page run to the bottom; `frames.py` draws the glass bar over it.
 * - `duo-outer` (466x678dp, the folded outer screen): the pushed pages, laid out beside the
 *   side column on the right (Back, Search, Settings there; the camera band at its top), as the
 *   app does on the host's side-column posture ([RaftSideColumnScaffold]).
 * - `duo-inner-landscape` (951x669dp, side column on the right) and `duo-inner-portrait`
 *   (669x907dp, below a 24pt status bar, no column): the apps' desktop mode without the top bar
 *   (#3906 / #3910): the rail with the server tile, Search first and the bell in its footer, then
 *   list + detail.
 *
 * zh-Hant shows the English UI (no Traditional Chinese resources), so its renders are en's:
 * `frames.py` reads `en` for it. Only runs with `RAFT_STORE_SCREENSHOTS=<dir>`; writes
 * `<dir>/<profile>/<lang>/NN-scene.png`. Filters: `RAFT_STORE_PROFILES`, `RAFT_STORE_SCENES`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w430dp-h836dp-xxhdpi")
class StorePlay8AppStoreScreenshotsTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val outDir: String? = System.getenv("RAFT_STORE_SCREENSHOTS")
    private val profileFilter = System.getenv("RAFT_STORE_PROFILES").csv()
    private val sceneFilter = System.getenv("RAFT_STORE_SCENES").csv()

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "store-appstore8"
            pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    private var previousFrameDelay: java.time.Duration? = null
    @BeforeTest
    fun registerPager() {
        // Frames 100 ms apart while captureStoreScreen settles (see [captureAll] for the animations).
        previousFrameDelay = ShadowChoreographer.getFrameDelay()
        ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(100))
        PagerManager.register(livePager)
        val prefs = mutableMapOf<String, String>()
        build.raft.app.data.RaftSharedKeyValueStoreProvider.installAppScopeFactory(build.raft.app.data.RaftLocalPreferenceStore.KuiklyNamespace) {
            object : build.raft.app.data.RaftKeyValueStore {
                override fun getString(key: String): String? = prefs[key]

                override fun putString(key: String, value: String?) {
                    if (value == null) prefs.remove(key) else prefs[key] = value
                }
            }
        }
    }

    @AfterTest
    fun unregisterPager() {
        PagerManager.unregister(livePager)
        previousFrameDelay?.let(ShadowChoreographer::setFrameDelay)
    }

    @Test fun en() = captureAll(P8Lang.En)

    @Test fun zhHans() = captureAll(P8Lang.ZhHans)

    @Test fun ja() = captureAll(P8Lang.Ja)

    // The pulsing agent-status lights and the typing line are infinite animations: they keep
    // Robolectric's settle producing frames for minutes per scene. Hold them at their first frame
    // (the compose-ui-test way: an InfiniteAnimationPolicy that never runs the operation).
    @OptIn(InternalComposeUiApi::class)
    private fun captureAll(lang: P8Lang) =
        WindowRecomposerPolicy.withFactory({ view -> view.createLifecycleAwareWindowRecomposer(StillInfiniteAnimations) }) {
            captureAllStill(lang)
        }

    private fun captureAllStill(lang: P8Lang) {
        assumeTrue("set RAFT_STORE_SCREENSHOTS to render store screenshots", !outDir.isNullOrBlank())
        val copy = lang.copy
        val token = lang.locale.applyVisualCaptureLocale()
        val previousLocale = java.util.Locale.getDefault()
        java.util.Locale.setDefault(lang.javaLocale)
        try {
            val time = if (lang.clock12h) StoreTime.copy(timeFormat = RaftTimeFormat12Hour) else StoreTime
            for (profile in AppStore8Profiles) {
                if (profileFilter != null && profile.key !in profileFilter) continue
                RuntimeEnvironment.setQualifiers("${lang.qualifier}-w${profile.widthDp}dp-h${profile.screenHeightDp}dp-${profile.orientation}-xxhdpi")
                val dir = File(File(outDir!!, profile.key), lang.key)
                fun shot(name: String, content: @Composable () -> Unit) = capture(dir, name, time, content)
                when (profile.key) {
                    "iphone" -> {
                        shot("01-chat") { P8ChatScene(copy) }
                        shot("02-mention") { P8MentionScene(copy) }
                        shot("03-thread") { P8ThreadScene(copy) }
                        shot("04-tasks") { P8TasksScene(copy, bottomBar = false) }
                        shot("05-agent") { P8AgentScene(copy, "profile") }
                        shot("06-activity") { P8ActivityScene(copy, swipe = false, bottomBar = false) }
                        shot("07-swipe") { P8ActivityScene(copy, swipe = true, bottomBar = false) }
                        shot("08-home") { P8HomeScene(copy, bottomBar = false) }
                    }
                    "duo-outer" -> {
                        // Pushed pages: Back and the page's actions in the column. (The tab pages'
                        // column holds the native tab bar, which iOS draws; not in this set.)
                        shot("01-chat") { DuoOuter { P8ChatScene(copy) } }
                        shot("02-mention") { DuoOuter { P8MentionScene(copy) } }
                        shot("03-thread") { DuoOuter { P8ThreadScene(copy) } }
                        shot("05-agent") { DuoOuter { P8AgentScene(copy, "profile") } }
                    }
                    else -> {
                        val landscape = profile.key == "duo-inner-landscape"
                        val home: @Composable () -> Unit = { P8HomeList(copy, Modifier.fillMaxSize(), wide = true, selectedChannelId = "ch-$P8Channel") }
                        shot("01-chat") { DuoInner(landscape, "home", list = home) { P8ChatScene(copy, showBack = false) } }
                        shot("02-mention") { DuoInner(landscape, "home", list = home) { P8MentionScene(copy, showBack = false) } }
                        shot("03-thread") { DuoInner(landscape, "home", list = home) { P8ThreadScene(copy, showBack = false) } }
                        // List, as on the phone: the 4 demo tasks leave the board's columns mostly empty and
                        // upright it cuts the second column.
                        shot("04-tasks") { DuoInner(landscape, "tasks", list = null) { P8TasksScene(copy, bottomBar = false) } }
                        shot("06-activity") {
                            DuoInner(
                                landscape,
                                "activity",
                                list = { P8ActivityScene(copy, swipe = false, bottomBar = false, selectedKey = "channel:$P8Channel") },
                            ) { P8ChatScene(copy, showBack = false) }
                        }
                    }
                }
            }
        } finally {
            java.util.Locale.setDefault(previousLocale)
            restoreVisualCaptureLocale(token)
        }
    }

    private fun capture(dir: File, name: String, time: RaftTimeContext, content: @Composable () -> Unit) {
        if (sceneFilter != null && name !in sceneFilter) return
        captureStoreScreen(activities, livePager, File(dir, "$name.png"), theme = RaftThemeId.Raft, time = time, content = content)
    }
}

/** Infinite animations never start: the capture shows their first frame. */
private object StillInfiniteAnimations : InfiniteAnimationPolicy {
    override suspend fun <R> onInfiniteOperation(block: suspend () -> R): R = awaitCancellation()
}

internal data class AppStore8Profile(val key: String, val widthDp: Int, val heightDp: Int) {
    val orientation: String get() = if (widthDp > heightDp) "land" else "port"

    /**
     * The qualifier's screen height: Robolectric's window loses the system bars' height to the
     * screen's (56dp on a phone, 64dp from 600dp smallest width), so the content is [heightDp] tall.
     */
    val screenHeightDp: Int get() = heightDp + if (minOf(widthDp, heightDp) >= 600) 64 else 56
}

internal val AppStore8Profiles =
    listOf(
        AppStore8Profile("iphone", 430, 836),
        AppStore8Profile("duo-outer", 466, 678),
        AppStore8Profile("duo-inner-portrait", 669, 907),
        AppStore8Profile("duo-inner-landscape", 951, 669),
    )

private fun String?.csv(): Set<String>? =
    this?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()?.takeIf { it.isNotEmpty() }

/** iPhone Duo's side column: 56pt on the right, the camera band at its top (DuoSideColumnScreenshotTest). */
private val DuoOuterPosture =
    RaftSideColumnPosture(sideColumn = true, stripWidth = 56.dp, keepOut = listOf(RaftSideColumnKeepOut(top = 0.dp, bottom = 48.dp)))

private val DuoInnerLandscapePosture =
    RaftSideColumnPosture(sideColumn = true, stripWidth = 56.dp, keepOut = listOf(RaftSideColumnKeepOut(top = 0.dp, bottom = 40.dp)), duoInner = true)

private val DuoInnerUprightPosture = RaftSideColumnPosture(duoInner = true)

@Composable
private fun DuoOuter(content: @Composable () -> Unit) = RaftSideColumnScaffold(posture = DuoOuterPosture, content = content)

private val DuoServers =
    listOf(
        RaftServerSwitcherItem(id = "srv-store", name = "Northwind Labs", slug = "northwind"),
        RaftServerSwitcherItem(id = "srv-side", name = "Side Projects", slug = "side", activityUnreadCount = 2L),
    )

private val DuoServerMenu = RaftServerMenuModel(servers = DuoServers, currentServer = DuoServers.first(), otherServersUnread = true)

/**
 * The inner screen: the shell's rail without the top bar (server tile header, Search first, the
 * bell in the footer above Settings), then [list] + [detail] split as the app's list hosts do, or
 * [detail] alone when [list] is null. Landscape keeps the side column with the history Back.
 */
@Composable
private fun DuoInner(landscape: Boolean, railKey: String, list: (@Composable () -> Unit)?, detail: @Composable () -> Unit) {
    RaftSideColumnScaffold(posture = if (landscape) DuoInnerLandscapePosture else DuoInnerUprightPosture) {
        val railWithoutTopBar = raftAppRailWithoutTopBar(navRailActive = true)
        val topBar = remember { RaftAppShellTopBarState().apply { drawn = !railWithoutTopBar } }
        if (list != null) raftSideColumnFallbackBack(raftSideColumnBackAction(onBack = {}))
        val homeLabel = stringResource(Res.string.tab_home)
        val activityLabel = stringResource(Res.string.tab_activity)
        val tasksLabel = stringResource(Res.string.tab_tasks)
        val membersLabel = stringResource(Res.string.tab_members)
        val settingsLabel = stringResource(Res.string.tab_settings)
        val searchLabel = stringResource(Res.string.home_nav_search)
        CompositionLocalProvider(LocalRaftAppShellTopBar provides topBar) {
            Row(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
                RaftNavigationRailTabBar(
                    items =
                        appShellRailItemsWithSearch(
                            appShellBottomTabs(
                                selectedKey = railKey,
                                orderedTabKeys = AppShellRailTabKeys,
                                activityUnreadCount = if (railKey == "activity") 0 else 4,
                                labelFor = { key ->
                                    when (key) {
                                        "home" -> homeLabel
                                        "activity" -> activityLabel
                                        "tasks" -> tasksLabel
                                        "members" -> membersLabel
                                        else -> key
                                    }
                                },
                            ),
                            searchLabel = searchLabel,
                            searchOpen = false,
                        ),
                    onSelect = {},
                    modifier = Modifier.fillMaxHeight(),
                    bottomItems = listOf(RaftBottomTabItemPayload(key = "settings", label = settingsLabel, iconName = RaftLucideIconName.Settings)),
                    header = { RaftAppRailServerTile(DuoServerMenu, open = false, onClick = {}) },
                    footerTools = { RaftAppRailNotificationBell(topBar, onToggle = {}) },
                )
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (list == null) {
                        detail()
                    } else {
                        RaftAdaptiveListDetailSplit(
                            kind = if (railKey == "home") RaftListPaneKind.Sidebar else RaftListPaneKind.List,
                            widths = remember { RaftListPaneWidths(store = null) },
                            dualPane = true,
                            active = true,
                            hasSelection = true,
                            detailBottomInset = 0.dp,
                            listPane = { list() },
                            detailPane = { modifier -> Box(modifier) { detail() } },
                        )
                    }
                }
            }
        }
    }
}
