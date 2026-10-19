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
 *  - [SlockThemeFamily.Brutal]  — the source-faithful register the app has always
 *    shipped: square corners, hard black hairlines, structural offset shadows,
 *    brand-yellow as an inherited block of identity.
 *  - [SlockThemeFamily.Elegant] — the product-grade register: rounded corners,
 *    soft oklch neutrals, ambient layered shadows, brand-yellow as the one warm
 *    accent on otherwise controlled surfaces.
 *
 * The whole point (per @artin 2026-07-31) is a *key-driven runtime switch*: a
 * single value at the app root selects the register and every component follows,
 * exactly like react-ui's `data-theme` / ThemeProvider. On Compose the idiomatic
 * carrier is a [staticCompositionLocalOf] ("React Context"-style passthrough): the
 * root provides [LocalSlockTheme], components read `LocalSlockTheme.current`, no
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
enum class SlockThemeFamily {
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
val SlockTheme.isAmbient: Boolean get() = family != SlockThemeFamily.Brutal

/**
 * Register-varying elevation. Brutal depth is structural (a hard offset that reads
 * like physical displacement, zero blur); Elegant depth is ambient (a small offset
 * with a soft blur and low-opacity color that reads like a layer breathing).
 */
data class SlockShadowSpec(
    val offset: Dp,
    val blur: Dp,
    val color: Color,
)

/**
 * The set of tokens whose *visual treatment* changes between registers while their
 * *semantic meaning* stays fixed (the Dual-Register Rule). Semantic constants that
 * mean the same hue in both registers (accents, status colors) stay in
 * [SlockColor]; only the register-varying surface/ink/line/shape/elevation live
 * here.
 */
data class SlockTheme(
    val family: SlockThemeFamily,
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
    /** Elevation spec (see [SlockShadowSpec]). */
    val shadow: SlockShadowSpec,
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
val brutalTheme: SlockTheme =
    SlockTheme(
        family = SlockThemeFamily.Brutal,
        ink = SlockColor.TextBlack,
        inkMuted = SlockColor.TextMutedGray,
        surface = SlockColor.SurfaceWhite,
        surfaceMuted = SlockColor.SurfaceCream,
        line = SlockColor.BorderBlack,
        lineSubtle = SlockColor.BorderBlack,
        lineWidth = SlockStroke.Default, // 2dp hard hairline
        cornerRadius = SlockRadius.None, // square
        shadow =
            SlockShadowSpec(
                offset = 4.dp, // structural offset (the classic brutal displacement)
                blur = 0.dp, // hard edge, no blur
                color = SlockColor.ShadowBlack,
            ),
        accent = SlockColor.BrandYellow,
        veil = Color(0xB3000000), // opaque-ish block
        uppercaseLabels = true,
    )

/**
 * The former product-grade register. raft-ui 0.6.0 removed the elegant theme (its
 * tokens are gone); the raft look replaces it, so this is kept as an alias for the
 * callers and fixtures that still name it.
 */
val elegantTheme: SlockTheme get() = raftLightTheme

/**
 * raft-ui 0.6.0 "raft" light: paper canvas, warm near-black ink, 1.5dp control
 * ink, hairline separators, 8dp control radius, and no shadows on controls
 * (only overlays float). Tokens: tools/design-tokens (raft-ui 0.6.0).
 */
val raftLightTheme: SlockTheme =
    SlockTheme(
        family = SlockThemeFamily.Raft,
        ink = RaftUiThemeColors.RaftLight.foreground,
        inkMuted = RaftUiThemeColors.RaftLight.foregroundMuted,
        surface = RaftUiThemeColors.RaftLight.layerCanvas,
        surfaceMuted = RaftUiThemeColors.RaftLight.fillMuted,
        line = RaftUiThemeColors.RaftLight.lineStrong,
        lineSubtle = RaftUiThemeColors.RaftLight.lineHairline,
        lineWidth = SlockStroke.Control,
        cornerRadius = SlockRadius.Control,
        shadow = SlockShadowSpec(offset = 0.dp, blur = 0.dp, color = Color.Transparent),
        accent = RaftUiThemeColors.RaftLight.layerFrame,
        veil = RaftUiThemeColors.RaftLight.layerBackdrop,
        uppercaseLabels = false,
    )

/** raft-ui 0.6.0 "raft" dark (`.dark`): same shapes, dark paper `#1B1B19`. */
val raftDarkTheme: SlockTheme =
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
enum class SlockAppearance {
    Light,
    Dark,
    System,
}

/**
 * Process-scoped, OBSERVABLE current theme, the colour counterpart of
 * [SlockFontFaceState]. [SlockColor]'s themed tokens and [LocalSlockTheme] resolve
 * through it, so flipping [appearance] (or the OS setting under
 * [SlockAppearance.System]) repaints everything composed from those tokens without
 * threading a theme through ~thousands of callsites.
 *
 * Defaults to [SlockAppearance.Light] until every surface reads themed tokens; dark
 * is reserved (artin, task #7) and only selectable once screens are migrated.
 */
internal object SlockThemeState {
    var appearance: SlockAppearance by mutableStateOf(SlockAppearance.Light)
        private set
    var systemDark: Boolean by mutableStateOf(false)
        private set

    val isDark: Boolean
        get() =
            when (appearance) {
                SlockAppearance.Light -> false
                SlockAppearance.Dark -> true
                SlockAppearance.System -> systemDark
            }

    val colors: RaftUiThemeColors
        get() = if (isDark) RaftUiThemeColors.RaftDark else RaftUiThemeColors.RaftLight

    val theme: SlockTheme
        get() = if (isDark) raftDarkTheme else raftLightTheme

    /** @return true when the resolved theme changed. */
    fun apply(next: SlockAppearance): Boolean {
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

/** Explicit subtree override (fixtures, previews); null = follow [SlockThemeState]. */
internal val LocalSlockThemeOverride = staticCompositionLocalOf<SlockTheme?> { null }

/**
 * The current register: a [SlockThemeProvider] override if one encloses the caller,
 * otherwise [SlockThemeState.theme]. Callers keep reading `LocalSlockTheme.current`.
 */
object LocalSlockTheme {
    val current: SlockTheme
        @Composable
        @ReadOnlyComposable
        get() = LocalSlockThemeOverride.current ?: SlockThemeState.theme
}

/**
 * Pins [theme] for the composition subtree (visual fixtures, previews). App screens
 * don't need it: they follow [SlockThemeState].
 */
@Composable
fun SlockThemeProvider(
    theme: SlockTheme = raftLightTheme,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalSlockThemeOverride provides theme, content = content)
}
