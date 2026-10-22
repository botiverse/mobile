package build.raft.app.data

import build.raft.app.RaftAppAppearance
import build.raft.app.RaftAppThemeState
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

    /** Idempotent. An unreadable store leaves the default (System). */
    fun ensureLoaded() {
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
    }
}
