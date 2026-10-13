package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.app.designtokens.RaftUiThemeColors
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp

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
enum class SlockThemeFamily { Brutal, Elegant }

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

/** The product-grade register — generated raft-ui elegant-light tokens. */
val elegantTheme: SlockTheme =
    SlockTheme(
        family = SlockThemeFamily.Elegant,
        ink = RaftUiThemeColors.ElegantLight.foregroundStrong, // oklch(0.145 0 0)
        inkMuted = RaftUiThemeColors.ElegantLight.foregroundMuted,
        surface = SlockColor.SurfaceWhite, // brutal-paper base is shared; muting is secondary
        surfaceMuted = RaftUiThemeColors.ElegantLight.fillMuted,
        line = RaftUiThemeColors.ElegantLight.line,
        lineSubtle = RaftUiThemeColors.ElegantLight.lineMuted,
        lineWidth = SlockStroke.Thin, // 1dp hairline
        cornerRadius = 8.dp, // rounded.md
        shadow =
            SlockShadowSpec(
                offset = 1.dp, // ambient, not structural
                blur = 8.dp, // soft layered
                color = Color(0x26000000), // elegant-veil oklch(0 0 0 / 0.15)
            ),
        accent = SlockColor.BrandYellow, // the one warm accent, shared
        veil = Color(0x26000000), // dims the room (15% black)
        uppercaseLabels = false, // title-case, restrained
    )

/**
 * The current register. Defaults to [brutalTheme] so unmigrated and migrated
 * components alike render the shipping look until the root provider is switched.
 */
val LocalSlockTheme = staticCompositionLocalOf { brutalTheme }

/**
 * Provides [theme] to the composition subtree. Wrap the app root (or a preview
 * fixture) with this; once the key signal is defined, the root chooses
 * [brutalTheme] vs [elegantTheme] and the whole tree follows.
 */
@Composable
fun SlockThemeProvider(
    theme: SlockTheme = brutalTheme,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalSlockTheme provides theme, content = content)
}
