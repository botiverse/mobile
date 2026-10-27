package build.raft.app.data

import build.raft.app.RaftAppAppearance
import build.raft.app.RaftAppThemeState
import build.raft.app.model.RaftServerThemeLook
import build.raft.designsystem.components.RaftSystemBarsAppearance
import build.raft.designsystem.components.raftSystemBarsAppearance
import androidx.compose.ui.graphics.toArgb
import kotlin.concurrent.Volatile

/**
 * Settings > Appearance > Theme: Light, Dark or System, saved on this device, the Web's
 * `settings.appearance.theme*` choice (`APP_APPEARANCE_MODES`, default `system`).
 */
interface RaftAppearancePreferenceRepository {
    fun readAppearanceSync(): RaftAppAppearance

    fun saveAppearanceSync(appearance: RaftAppAppearance)
}

class KeyValueRaftAppearancePreferenceRepository(
    private val store: SharedPreferenceStore,
) : RaftAppearancePreferenceRepository {
    override fun readAppearanceSync(): RaftAppAppearance =
        parseRaftAppearance(store.getString(KEY_APPEARANCE_THEME_MODE)) ?: RAFT_APPEARANCE_DEFAULT

    override fun saveAppearanceSync(appearance: RaftAppAppearance) {
        store.putString(KEY_APPEARANCE_THEME_MODE, appearance.storageValue)
    }
}

/** The Web's default (`DEFAULT_APP_THEME_PREFERENCES = { mode: "system" }`). */
val RAFT_APPEARANCE_DEFAULT: RaftAppAppearance = RaftAppAppearance.System

/** Persisted contract string inside the `slockLocal` app-scope store. */
internal const val KEY_APPEARANCE_THEME_MODE = "appearance:theme_mode"

/** The Web's mode values (`light` / `dark` / `system`). */
internal val RaftAppAppearance.storageValue: String
    get() =
        when (this) {
            RaftAppAppearance.Light -> "light"
            RaftAppAppearance.Dark -> "dark"
            RaftAppAppearance.System -> "system"
        }

internal fun parseRaftAppearance(value: String?): RaftAppAppearance? =
    when (value?.trim()?.lowercase()) {
        "light" -> RaftAppAppearance.Light
        "dark" -> RaftAppAppearance.Dark
        "system" -> RaftAppAppearance.System
        else -> null
    }

/**
 * Applies the stored theme choice to the process-wide theme and saves new choices. Hosts call
 * [ensureLoaded] at startup once the app-scope stores are installed (Android
 * `RaftApplication`, iOS `RaftIosHostHooks`), before the first page composes, so the first
 * frame is already in the right theme; the page registry calls it again as a fallback.
 *
 * Hosts that style native chrome from the choice itself (iOS `overrideUserInterfaceStyle`, so
 * keyboards, system menus and the tab bar follow an explicit Light/Dark) read [hostStyleCode]
 * and register through [setHostListener].
 */
object RaftAppAppearanceRuntime {
    @Volatile
    private var loaded = false

    private var repositoryOverride: RaftAppearancePreferenceRepository? = null

    private var hostListener: ((Int) -> Unit)? = null

    private fun repository(): RaftAppearancePreferenceRepository =
        repositoryOverride ?: KeyValueRaftAppearancePreferenceRepository(raftSharedPreferenceStore())

    @Volatile
    private var serverThemeSettled = false

    private var sessionSnapshotOverride: (() -> StoredAuthSession?)? = null

    /**
     * The last committed auth session, lock-free (never waits on the session lock, which the main
     * thread must not do: #3823/#3824); decoded from the store when this process has not read it.
     */
    private fun committedSession(): StoredAuthSession? {
        sessionSnapshotOverride?.let { reader -> return reader() }
        return KeyValueAuthSessionStore(RaftSharedAuthSessionKeyValueStoreProvider.create().store)
            .readCommittedSessionSnapshot()
    }

    /**
     * Idempotent. An unreadable store leaves the default (System). Also seeds the server theme
     * before the first frame: the look of the persisted session's current server (Raft Yellow
     * when signed out), read lock-free; the shell's own look then follows through
     * [applyServerThemeLook].
     */
    fun ensureLoaded() {
        seedServerThemeLook()
        if (loaded) return
        val stored = runCatching { repository().readAppearanceSync() }.getOrNull() ?: return
        loaded = true
        RaftAppThemeState.apply(stored)
        notifyHost()
    }

    val appearance: RaftAppAppearance
        get() = RaftAppThemeState.appearance

    /** Applies [next] at once (every screen repaints) and saves it on this device. */
    fun select(next: RaftAppAppearance) {
        loaded = true
        RaftAppThemeState.apply(next)
        runCatching { repository().saveAppearanceSync(next) }
        notifyHost()
    }

    private fun seedServerThemeLook() {
        if (serverThemeSettled) return
        // An unreadable store (not installed yet) leaves it for the next call or the session flow.
        val session = runCatching { committedSession() }.getOrElse { return }
        serverThemeSettled = true
        val seed = raftSessionThemeLook(session, selectedServerId = null)
        RaftAppThemeState.setRaftTheme(seed.theme, seed.decor)
    }

    /**
     * Wears [look] (the selected server's theme and decor) app-wide: every screen repaints. Main
     * thread.
     */
    fun applyServerThemeLook(look: RaftServerThemeLook) {
        // A look from the session flow outranks a seed still to be read.
        serverThemeSettled = true
        RaftAppThemeState.setRaftTheme(look.theme, look.decor)
    }

    /**
     * Hosts report the OS light/dark setting before the first composition (cold start) and when
     * it changes while running; @return true when the resolved theme changed.
     */
    fun onSystemDarkChanged(dark: Boolean): Boolean = RaftAppThemeState.onSystemDarkChanged(dark)

    /** Resolved dark flag (choice + OS setting). */
    val isDark: Boolean
        get() = RaftAppThemeState.isDark

    /** `layer-canvas` of the resolved theme as ARGB, for native window / container backgrounds. */
    val canvasArgb: Int
        get() = RaftAppThemeState.colors.layerCanvas.toArgb()

    /**
     * `layer-frame` of the resolved theme as ARGB: the frame the top bar and rail sit on (Raft
     * Yellow in light, the server theme's frame otherwise), which heads paint under the status bar.
     */
    val frameArgb: Int
        get() = RaftAppThemeState.colors.layerFrame.toArgb()

    /**
     * The system bar icons before a page reports its own (RaftCmpRoot's onSystemBarsResolved):
     * the status bar's from the frame when the page's head paints it there
     * ([frameUnderStatusBar]: the app shell), else from the canvas; the navigation bar's from the
     * canvas. By luminance: Space and Quest have a dark frame in light mode.
     */
    fun hostSystemBars(frameUnderStatusBar: Boolean): RaftSystemBarsAppearance {
        val colors = RaftAppThemeState.colors
        return raftSystemBarsAppearance(
            statusBarBackdrop = if (frameUnderStatusBar) colors.layerFrame else colors.layerCanvas,
            navigationBarBackdrop = colors.layerCanvas,
        )
    }

    /** iOS: whether the status bar sits on a dark colour (light content), as [hostSystemBars]. */
    fun statusBarBackdropDark(frameUnderStatusBar: Boolean): Boolean =
        !hostSystemBars(frameUnderStatusBar).lightStatusBars

    /** `foreground` / `foreground-muted` of the resolved theme, for native host text. */
    val foregroundArgb: Int
        get() = RaftAppThemeState.colors.foreground.toArgb()

    val foregroundMutedArgb: Int
        get() = RaftAppThemeState.colors.foregroundMuted.toArgb()

    /** 0 = follow the system, 1 = light, 2 = dark (UIUserInterfaceStyle order). */
    val hostStyleCode: Int
        get() =
            when (RaftAppThemeState.appearance) {
                RaftAppAppearance.System -> 0
                RaftAppAppearance.Light -> 1
                RaftAppAppearance.Dark -> 2
            }

    /** Main thread. [listener] gets [hostStyleCode] now and after every change. */
    fun setHostListener(listener: ((Int) -> Unit)?) {
        hostListener = listener
        listener?.invoke(hostStyleCode)
    }

    private fun notifyHost() {
        hostListener?.invoke(hostStyleCode)
    }

    internal fun installRepositoryForTests(repository: RaftAppearancePreferenceRepository?) {
        repositoryOverride = repository
        loaded = false
        serverThemeSettled = false
    }

    /** Tests: the committed session the cold-start seed reads; null restores the store's. */
    internal fun installSessionSnapshotReaderForTests(reader: (() -> StoredAuthSession?)?) {
        sessionSnapshotOverride = reader
        serverThemeSettled = false
    }
}
