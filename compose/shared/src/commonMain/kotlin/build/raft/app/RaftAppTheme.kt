package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.app.designtokens.RaftUiThemeColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Mobile theme axis mirroring raft-ui's dual register (rui/packages/ui DESIGN.md):
 *
 *  - [RaftThemeFamily.Brutal]  — the source-faithful register the app has always
 *    shipped: square corners, hard black hairlines, structural offset shadows,
 *    brand-yellow as an inherited block of identity.
 *  - [RaftThemeFamily.Elegant] — the product-grade register: rounded corners,
 *    soft oklch neutrals, ambient layered shadows, brand-yellow as the one warm
 *    accent on otherwise controlled surfaces.
 *
 * The whole point (per @artin 2026-07-31) is a *key-driven runtime switch*: a
 * single value at the app root selects the register and every component follows,
 * exactly like react-ui's `data-theme` / ThemeProvider. On Compose the idiomatic
 * carrier is a [staticCompositionLocalOf] ("React Context"-style passthrough): the
 * root provides [LocalRaftTheme], components read `LocalRaftTheme.current`, no
 * prop threading.
 *
 * **Default is [brutalTheme]** so introducing this axis is a zero-visual-change
 * refactor — components migrated to read the theme render byte-identically until
 * the (still-to-be-defined) key flips the root provider to [elegantTheme].
 *
 * Elegant colors come from the generated raft-ui elegant-light tokens
 * (tools/design-tokens): warm neutrals at hue 106 since raft-ui moved off the zinc ramp.
 * Source Yellow is shared across both registers by design.
 */
enum class RaftThemeFamily {
    Brutal,
    Elegant,

    /**
     * raft-ui 0.6.0's single "raft" look (slock #10148), Light or Dark: the Web's only
     * look since 2026-10-18. Structurally it follows the non-brutal path (rounded
     * controls, no hard shadows); colours come from the generated raft tokens.
     */
    Raft,
}

/** True for every register that is not the legacy brutal one (rounded, no hard shadow). */
val RaftAppTheme.isAmbient: Boolean get() = family != RaftThemeFamily.Brutal

/**
 * Register-varying elevation. Brutal depth is structural (a hard offset that reads
 * like physical displacement, zero blur); Elegant depth is ambient (a small offset
 * with a soft blur and low-opacity color that reads like a layer breathing).
 */
data class RaftShadowSpec(
    val offset: Dp,
    val blur: Dp,
    val color: Color,
)

/**
 * The set of tokens whose *visual treatment* changes between registers while their
 * *semantic meaning* stays fixed (the Dual-Register Rule). Semantic constants that
 * mean the same hue in both registers (accents, status colors) stay in
 * [RaftColor]; only the register-varying surface/ink/line/shape/elevation live
 * here.
 */
data class RaftAppTheme(
    val family: RaftThemeFamily,
    /** Primary text/ink. */
    val ink: Color,
    /** Secondary/muted text. */
    val inkMuted: Color,
    /** Base surface a component paints on. */
    val surface: Color,
    /** Secondary/inset surface (cards, wells, hovered rows). */
    val surfaceMuted: Color,
    /** Border/separator hairline color. */
    val line: Color,
    /** Even quieter separators (elegant only; == [line] in brutal). */
    val lineSubtle: Color,
    /** Border/hairline thickness. */
    val lineWidth: Dp,
    /** Corner rounding applied to blocks (buttons, cards, inputs, badges). */
    val cornerRadius: Dp,
    /** Elevation spec (see [RaftShadowSpec]). */
    val shadow: RaftShadowSpec,
    /** The single primary-action accent (Source Yellow — shared by both registers). */
    val accent: Color,
    /** Overlay backdrop. Brutal blocks the room; Elegant dims it. */
    val veil: Color,
    /**
     * Whether the register forces uppercase on structural labels (tabs, eyebrows,
     * section headers). Brutal leans on uppercase force; Elegant is title-case and
     * restrained. Callers must not hard-code uppercase — gate it on this.
     */
    val uppercaseLabels: Boolean,
)

/** The source-faithful register — byte-identical to the current hard-coded look. */
val brutalTheme: RaftAppTheme =
    RaftAppTheme(
        family = RaftThemeFamily.Brutal,
        ink = RaftColor.TextBlack,
        inkMuted = RaftColor.TextMutedGray,
        surface = RaftColor.SurfaceWhite,
        surfaceMuted = RaftColor.SurfaceCream,
        line = RaftColor.BorderBlack,
        lineSubtle = RaftColor.BorderBlack,
        lineWidth = RaftAppStroke.Default, // 2dp hard hairline
        cornerRadius = RaftAppRadius.None, // square
        shadow =
            RaftShadowSpec(
                offset = 4.dp, // structural offset (the classic brutal displacement)
                blur = 0.dp, // hard edge, no blur
                color = RaftColor.ShadowBlack,
            ),
        accent = RaftColor.BrandYellow,
        veil = Color(0xB3000000), // opaque-ish block
        uppercaseLabels = true,
    )

/**
 * The former product-grade register. raft-ui 0.6.0 removed the elegant theme (its
 * tokens are gone); the raft look replaces it, so this is kept as an alias for the
 * callers and fixtures that still name it.
 */
val elegantTheme: RaftAppTheme get() = raftLightTheme

/**
 * raft-ui 0.6.0 "raft" light: paper canvas, warm near-black ink, 1.5dp control
 * ink, hairline separators, 8dp control radius, and no shadows on controls
 * (only overlays float). Tokens: tools/design-tokens (raft-ui 0.6.0).
 */
val raftLightTheme: RaftAppTheme =
    RaftAppTheme(
        family = RaftThemeFamily.Raft,
        ink = RaftUiThemeColors.RaftLight.foreground,
        inkMuted = RaftUiThemeColors.RaftLight.foregroundMuted,
        surface = RaftUiThemeColors.RaftLight.layerCanvas,
        surfaceMuted = RaftUiThemeColors.RaftLight.fillMuted,
        line = RaftUiThemeColors.RaftLight.lineStrong,
        lineSubtle = RaftUiThemeColors.RaftLight.lineHairline,
        lineWidth = RaftAppStroke.Control,
        cornerRadius = RaftAppRadius.Control,
        shadow = RaftShadowSpec(offset = 0.dp, blur = 0.dp, color = Color.Transparent),
        accent = RaftUiThemeColors.RaftLight.layerFrame,
        veil = RaftUiThemeColors.RaftLight.layerBackdrop,
        uppercaseLabels = false,
    )

/** raft-ui 0.6.0 "raft" dark (`.dark`): same shapes, dark paper `#1B1B19`. */
val raftDarkTheme: RaftAppTheme =
    raftLightTheme.copy(
        ink = RaftUiThemeColors.RaftDark.foreground,
        inkMuted = RaftUiThemeColors.RaftDark.foregroundMuted,
        surface = RaftUiThemeColors.RaftDark.layerCanvas,
        surfaceMuted = RaftUiThemeColors.RaftDark.fillMuted,
        line = RaftUiThemeColors.RaftDark.lineStrong,
        lineSubtle = RaftUiThemeColors.RaftDark.lineHairline,
        accent = RaftUiThemeColors.RaftDark.layerFrame,
        veil = RaftUiThemeColors.RaftDark.layerBackdrop,
    )

/** The user's appearance choice. [System] follows the OS light/dark setting. */
enum class RaftAppAppearance {
    Light,
    Dark,
    System,
}

/**
 * Process-scoped, OBSERVABLE current theme, the colour counterpart of
 * the type tokens. [RaftColor]'s themed tokens and [LocalRaftTheme] resolve
 * through it, so flipping [appearance] (or the OS setting under
 * [RaftAppAppearance.System]) repaints everything composed from those tokens without
 * threading a theme through ~thousands of callsites.
 *
 * Defaults to [RaftAppAppearance.Light] until every surface reads themed tokens; dark
 * is reserved (artin, task #7) and only selectable once screens are migrated.
 */
internal object RaftAppThemeState {
    var appearance: RaftAppAppearance by mutableStateOf(RaftAppAppearance.Light)
        private set
    var systemDark: Boolean by mutableStateOf(false)
        private set

    val isDark: Boolean
        get() =
            when (appearance) {
                RaftAppAppearance.Light -> false
                RaftAppAppearance.Dark -> true
                RaftAppAppearance.System -> systemDark
            }

    val colors: RaftUiThemeColors
        get() = if (isDark) RaftUiThemeColors.RaftDark else RaftUiThemeColors.RaftLight

    val theme: RaftAppTheme
        get() = if (isDark) raftDarkTheme else raftLightTheme

    /** @return true when the resolved theme changed. */
    fun apply(next: RaftAppAppearance): Boolean {
        val wasDark = isDark
        appearance = next
        return wasDark != isDark
    }

    /** Hosts report the OS light/dark setting here; @return true when the resolved theme changed. */
    fun onSystemDarkChanged(dark: Boolean): Boolean {
        val wasDark = isDark
        systemDark = dark
        return wasDark != isDark
    }
}

/** Explicit subtree override (fixtures, previews); null = follow [RaftAppThemeState]. */
internal val LocalRaftThemeOverride = staticCompositionLocalOf<RaftAppTheme?> { null }

/**
 * The current register: a [RaftThemeProvider] override if one encloses the caller,
 * otherwise [RaftAppThemeState.theme]. Callers keep reading `LocalRaftTheme.current`.
 */
object LocalRaftTheme {
    val current: RaftAppTheme
        @Composable
        @ReadOnlyComposable
        get() = LocalRaftThemeOverride.current ?: RaftAppThemeState.theme
}

/**
 * Pins [theme] for the composition subtree (visual fixtures, previews). App screens
 * don't need it: they follow [RaftAppThemeState].
 */
@Composable
fun RaftThemeProvider(
    theme: RaftAppTheme = raftLightTheme,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalRaftThemeOverride provides theme, content = content)
}
