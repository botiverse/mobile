package build.raft.app

import build.raft.app.data.FontPreference
import build.raft.app.designtokens.RaftUiPalette
import build.raft.app.designtokens.RaftUiThemeColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import build.raft.app.compat.ui.KuiklyFont
import androidx.compose.ui.unit.TextUnit
import build.raft.designsystem.theme.RaftFonts
import build.raft.designsystem.theme.RaftTypography
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// Colours that exist on Web come from the generated tokens (tools/design-tokens), so a Web
// palette change shows up as a regenerated diff instead of silent drift. The remaining
// literals have no Web token.
internal object RaftColor {
    val BrandYellow = RaftUiPalette.paletteYellow
    // Semantic alias for react `--color-palette-yellow` (raft-ui 0.6 `--color-palette-yellow`,
    // == brand yellow), used for the self-mention chip highlight so callsites read intent, not hue.
    val SoftSignal = RaftUiPalette.paletteYellow
    val AccentPink = RaftUiPalette.palettePink
    val AccentCyan = RaftUiPalette.paletteCyan
    val AccentOrange = RaftUiPalette.paletteOrange
    val AccentLavender = RaftUiPalette.paletteLavender
    val AccentLime = RaftUiPalette.paletteLime
    val AccentRed = RaftUiPalette.paletteRed
    val AccentStone = RaftUiPalette.paletteStone
    // raft-ui `--warning` (#FF6900): the warning badge/banner fill. Distinct from AccentOrange.
    val StateWarningBase = RaftUiPalette.warning
    val SurfaceWhite = RaftUiPalette.white
    val SurfaceCream = RaftUiPalette.paletteCream
    val AppBgLightBlue = Color(0xFFE6F8FF)
    val EmptyStateGray = Color(0xFFE7E7E7)
    // Tailwind gray-400 (#99A1AF): the neutral / offline status-dot fill.
    // Not the same gray as EmptyStateGray (#E7E7E7).
    val StatusNeutralGray = RaftUiPalette.gray400
    // Themed tokens below read [RaftAppThemeState], so they follow light/dark at runtime.
    // Web raft `--foreground` (body text; light #141111, a warm near-black).
    val TextBlack: Color get() = RaftAppThemeState.colors.foreground
    // Tailwind neutral-500.
    val TextMutedGray = RaftUiPalette.neutral500
    // Web raft `--line-strong` (control ink).
    val BorderBlack: Color get() = RaftAppThemeState.colors.lineStrong
    // Web raft `--line-hairline` (ink at 15%): quiet separators.
    val BorderHairline: Color get() = RaftAppThemeState.colors.lineHairline
    // Web raft `--line-seam` (ink at 45%): structural seams between panels.
    val BorderSeam: Color get() = RaftAppThemeState.colors.lineSeam
    // Sticker ink (`--elevation-sticker` colour); the only hard shadow left in raft.
    val ShadowBlack: Color get() = RaftAppThemeState.colors.ink
    // Web raft surfaces and secondary ink, for primitives migrating off the fixed
    // SurfaceWhite / TextMutedGray literals.
    val Canvas: Color get() = RaftAppThemeState.colors.layerCanvas
    val Card: Color get() = RaftAppThemeState.colors.layerCard
    val Popover: Color get() = RaftAppThemeState.colors.layerPopover
    val TextMuted: Color get() = RaftAppThemeState.colors.foregroundMuted

    // raft-ui 0.7 semantic roles the primitives draw with (foundation.css). Themed ones
    // follow light/dark through [RaftAppThemeState]; the solid "sticker" fills are the same
    // colour in both modes, so they read straight from the palette.
    // Text and icon ink ladder.
    val ForegroundHint: Color get() = RaftAppThemeState.colors.foregroundHint
    val ForegroundDisabled: Color get() = RaftAppThemeState.colors.foregroundDisabled
    val ForegroundInverse: Color get() = RaftAppThemeState.colors.foregroundInverse
    // Opaque neutral fills: resting/disabled control fill and the 8% pressed wash.
    val FillMuted: Color get() = RaftAppThemeState.colors.fillMuted
    val FillStrong: Color get() = RaftAppThemeState.colors.fillStrong
    val FillPressed: Color get() = RaftAppThemeState.colors.fillPressed
    // Pressed wash on the yellow frame (`fill-frame-pressed`), e.g. header controls.
    val FillFramePressed: Color get() = RaftAppThemeState.colors.fillFramePressed
    // `--line-field` (input ink); card edges and menu separators use [BorderHairline].
    val LineField: Color get() = RaftAppThemeState.colors.lineField
    // The yellow frame (`--layer-frame`) and the overlay backdrop.
    val Frame: Color get() = RaftAppThemeState.colors.layerFrame
    val Backdrop: Color get() = RaftAppThemeState.colors.layerBackdrop
    // State washes (`-soft`) and readable state ink (`-strong`).
    val BrandSoft: Color get() = RaftAppThemeState.colors.brandSoft
    val BrandStrong: Color get() = RaftAppThemeState.colors.brandStrong
    val AttentionSoft: Color get() = RaftAppThemeState.colors.attentionSoft
    val AttentionStrong: Color get() = RaftAppThemeState.colors.attentionStrong
    val InfoSoft: Color get() = RaftAppThemeState.colors.infoSoft
    val InfoStrong: Color get() = RaftAppThemeState.colors.infoStrong
    val SuccessSoft: Color get() = RaftAppThemeState.colors.successSoft
    val SuccessStrong: Color get() = RaftAppThemeState.colors.successStrong
    val WarningSoft: Color get() = RaftAppThemeState.colors.warningSoft
    val WarningStrong: Color get() = RaftAppThemeState.colors.warningStrong
    val DangerSoft: Color get() = RaftAppThemeState.colors.dangerSoft
    val DangerStrong: Color get() = RaftAppThemeState.colors.dangerStrong
    val InactiveSoft: Color get() = RaftAppThemeState.colors.inactiveSoft
    // Sticker solids (`bg-brand`, `bg-attention`, ...): ink type on colour in both modes.
    val Brand = RaftUiPalette.brand
    val Attention = RaftUiPalette.attention
    val Info = RaftUiPalette.info
    val Success = RaftUiPalette.success
    val Warning = RaftUiPalette.warning
    val Danger = RaftUiPalette.danger
    val Inactive = RaftUiPalette.inactive
    val StickerInk = RaftUiPalette.stickerInk
    val StickerPaper = RaftUiPalette.stickerPaper
    // 12% ink laid over a pressed sticker (`press-overlay-sticker`).
    val StickerPressed = RaftUiPalette.stickerPressed
    // Die-cut edge around stickers: transparent in light, a 35% paper ring in dark.
    val StickerEdge: Color get() = RaftAppThemeState.colors.stickerEdge
    // Presence lights (`--presence-*`): fixed meanings, same in both modes.
    val PresenceOnline = RaftUiPalette.presenceOnline
    val PresenceBusy = RaftUiPalette.presenceBusy
    val PresenceError = RaftUiPalette.presenceError
    val PresenceOffline = RaftUiPalette.presenceOffline
    val PresenceExternal = RaftUiPalette.presenceExternal
    // Colour of the overlay float shadow (`--elevation-float`): ink at 32% in light,
    // black at 80% in dark. Only overlays (menus, popovers, dialogs) cast it.
    val OverlayShadow: Color
        get() =
            if (RaftAppThemeState.isDark) {
                RaftUiPalette.black.copy(alpha = 0.8f)
            } else {
                RaftAppThemeState.colors.ink.copy(alpha = 0.32f)
            }

    const val BrandYellowArgb = "#FFFFD440"
    const val SurfaceWhiteArgb = "#FFFFFFFF"
    // Kuikly string-colour callsites; themed ones derive from their Color token.
    val TextBlackArgb: String get() = raftArgbHex(TextBlack)
    const val TextMutedGrayArgb = "#FF737373"
    val BorderBlackArgb: String get() = raftArgbHex(BorderBlack)
    val ShadowBlackArgb: String get() = raftArgbHex(ShadowBlack)
    const val AccentOrangeArgb = "#FFF8A16F"
    const val AccentRedArgb = "#FFF97264"
}

/** `#AARRGGBB` for Kuikly APIs that take a colour string. */
internal fun raftArgbHex(color: Color): String {
    fun channel(value: Float): String = (value * 255f).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()
    return "#" + channel(color.alpha) + channel(color.red) + channel(color.green) + channel(color.blue)
}

internal object RaftAppSpacing {
    val Xxs = 2.dp
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 16.dp
    val Lg = 24.dp
    val Xl = 32.dp
}

// raft-ui 0.6.0 has three line weights: hairline (1dp at 15% ink), seam (1dp at 45%
// ink) and control ink (1.5dp). Default/Strong map onto control ink so existing
// brutal 2dp/3dp borders come out at the raft weight.
internal object RaftAppStroke {
    val Thin = 1.dp
    val Control = 1.5.dp
    val Default = Control
    val Strong = 2.dp
}

// raft-ui 0.6.0 radii (styles.css --radius-*).
internal object RaftAppRadius {
    val None = 0.dp
    val Xs = 2.dp
    val Sm = 4.dp
    val Sticker = 4.dp
    val Chip = 6.dp
    val Control = 8.dp
    val Container = 10.dp
    val Dialog = 16.dp
}

internal object RaftAppSize {
    val MinTouchTarget = 48.dp
    val IconButton = 44.dp
    val HeaderIcon = 36.dp
    // raft panel heads are 56px (48px under 600px window height).
    val PageHeaderHeight = 56.dp
    val PageHeaderContentHeight = PageHeaderHeight - RaftAppStroke.Default
    val PageHeaderCompactHeight = 48.dp
    val BottomBarHeight = 53.dp
}

// raft-ui 0.6.0: controls never cast a shadow (`--theme-shadow-xs/sm/md` are 0); only
// stickers keep a hard 4px offset and only overlays float. The brutal offsets are
// therefore zero; [Sticker] is the one remaining hard offset.
internal object RaftShadow {
    val Brutal = 0.dp
    val BrutalSmall = 0.dp
    val BrutalLarge = 0.dp
    val BrutalActive = 0.dp
    val Sticker = 4.dp
}

// Kuikly serializes FontFamily as comma-joined font names WITHOUT weight info.
// A single FontFamily with multiple weights would always pick the first entry.
// We must use one FontFamily per weight and manually dispatch.
// See KuiklyTextExtension.toKuiklyFontFamily() for the serialization constraint.

/**
 * Task #179: process-scoped, OBSERVABLE current font face.
 *
 * Every type token below resolves its family through this. There are ~1109 callsites of the
 * RaftType / RaftMonoFamily helpers, so the preference cannot be threaded per-call-site.
 * Instead the root reads [RaftFontFaceState.preference] during composition; changing it
 * repaints every text style that re-resolves a token.
 *
 * Deliberately a Compose state, not a bare getter: a plain field would change the value but
 * leave already-composed text on the old face.
 */
internal object RaftFontFaceState {
    var preference: FontPreference by mutableStateOf(FontPreference.Default)
        private set

    /**
     * @return true when the face actually changed, so callers can decide to force a rebuild
     * (the Settings screen uses this to repaint surfaces that cached their own TextStyle).
     */
    fun apply(next: FontPreference): Boolean {
        if (next == preference) return false
        preference = next
        return true
    }
}

/**
 * Regular sans face name of the active font preference (e.g. "HankenGrotesk-Regular"), or null
 * for [FontPreference.System]. Native hosts read this to style editors the Compose text style
 * does not reach, so they follow Settings > Appearance instead of a fixed face.
 */
fun raftInputFontFaceName(): String? = raftSansRegularFaceName(RaftFontFaceState.preference)

internal fun raftSansRegularFaceName(preference: FontPreference): String? =
    preference.sansFamily?.let { base ->
        base.split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } } + "-Regular"
    }

private val SpaceGroteskBold = FontFamily(KuiklyFont("SpaceGrotesk-Bold", FontWeight.Bold, FontStyle.Normal))
private val SpaceGroteskSemiBold = FontFamily(KuiklyFont("SpaceGrotesk-SemiBold", FontWeight.SemiBold, FontStyle.Normal))
private val SpaceGroteskMedium = FontFamily(KuiklyFont("SpaceGrotesk-Medium", FontWeight.Medium, FontStyle.Normal))
private val SpaceGroteskRegular = FontFamily(KuiklyFont("SpaceGrotesk-Regular", FontWeight.Normal, FontStyle.Normal))
private val SpaceGroteskFamilies = setOf(
    SpaceGroteskBold,
    SpaceGroteskSemiBold,
    SpaceGroteskMedium,
    SpaceGroteskRegular
)

/**
 * Task #179: the "is this a family we encoded a weight into?" test used to be a fixed set of the
 * four Space Grotesk faces. With a selectable face the set has to follow the active preference,
 * otherwise a Hanken/Geist/Inter style would silently skip weight resolution and render at the
 * default weight.
 */
private fun raftEncodedFamilies(): Set<FontFamily> {
    val preference = RaftFontFaceState.preference
    if (preference.sansFamily == null) return emptySet()
    val resolved =
        listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
            .mapTo(mutableSetOf()) { fontFamilyFor("sans", preference, it) }
    // The type tokens resolve through fontFamilyFor() for every option, Space Grotesk included, so
    // that set must be recognised; the legacy Space constants stay recognised for older callers.
    return if (preference == FontPreference.SpaceGrotesk) resolved + SpaceGroteskFamilies else resolved
}

private fun isRaftEncodedFamily(fontFamily: FontFamily?): Boolean =
    fontFamily != null && fontFamily in raftEncodedFamilies()

// Task #179: one family set per selectable option. The names match the Android
// `RaftLegacyHostActivity.resolveTypeface()` aliases and the OHOS/iOS registration names.
// Families are cached because this runs on every text style construction; a fresh FontFamily
// per call would allocate on a hot path for no behavioural gain.
private val raftFamilyCache = mutableMapOf<Triple<String, FontPreference, String>, FontFamily>()

private fun fontFamilyFor(role: String, preference: FontPreference, fontWeight: FontWeight): FontFamily {
    val base = when (role) {
        "sans" -> preference.sansFamily
        "heading" -> preference.headingFamily
        else -> preference.monoFamily
    }
    // null means "this role has no bundled family". The right fallback depends on the role:
    // the mono role must land on the platform's MONOSPACE face. Returning FontFamily.Default here
    // would hand back the platform sans face, so code blocks and timestamps would render
    // proportionally even though the option promises the host's own type, mono included.
    if (base == null) {
        return if (role == "mono") FontFamily.Monospace else FontFamily.Default
    }
    val suffix = when {
        // Mono ships only Regular/Bold per family. Asking for SpaceMono-Medium would miss in
        // resolveTypeface() and fall back to the SANS face, i.e. render mono text in a
        // proportional font. Collapse mono to the two weights that actually exist.
        role == "mono" -> if (fontWeight >= FontWeight.Bold) "Bold" else "Regular"
        fontWeight >= FontWeight.Bold -> "Bold"
        fontWeight >= FontWeight.SemiBold -> "SemiBold"
        fontWeight >= FontWeight.Medium -> "Medium"
        fontWeight >= FontWeight.Light && fontWeight < FontWeight.Normal -> "Light"
        else -> "Regular"
    }
    return raftFamilyCache.getOrPut(Triple(role, preference, suffix)) {
        // KuiklyFont names use PascalWithDashes ("HankenGrotesk-SemiBold"); the storage key is
        // snake_case ("hanken_grotesk"), so rebuild the display name from the parts.
        val display = base.split('_').joinToString("") { part ->
            part.replaceFirstChar { it.uppercaseChar() }
        }
        FontFamily(KuiklyFont("$display-$suffix", fontWeight, FontStyle.Normal))
    }
}

internal fun raftFontFamilyForWeight(fontWeight: FontWeight?): FontFamily =
    fontFamilyFor("sans", RaftFontFaceState.preference, fontWeight ?: FontWeight.Normal)

private val raftMultiWeightFamilyCache = mutableMapOf<Pair<String, FontPreference>, FontFamily>()

/**
 * One family holding every bundled weight of the active face for [role] ("sans" or "mono").
 * The per-weight families above encode the weight in the face name; the designsystem
 * module's type tokens pick a weight with `copy(fontWeight = …)` the way CSS does, which
 * needs every weight in one family. Space Mono / Geist Mono ship only Regular and Bold.
 */
internal fun raftMultiWeightFontFamily(role: String, preference: FontPreference = RaftFontFaceState.preference): FontFamily {
    val base = if (role == "mono") preference.monoFamily else preference.sansFamily
    if (base == null) return if (role == "mono") FontFamily.Monospace else FontFamily.Default
    return raftMultiWeightFamilyCache.getOrPut(role to preference) {
        val display = base.split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
        val faces =
            if (role == "mono") {
                listOf(FontWeight.Normal to "Regular", FontWeight.Bold to "Bold")
            } else {
                listOf(
                    FontWeight.Light to "Light",
                    FontWeight.Normal to "Regular",
                    FontWeight.Medium to "Medium",
                    FontWeight.SemiBold to "SemiBold",
                    FontWeight.Bold to "Bold"
                )
            }
        FontFamily(faces.map { (weight, suffix) -> KuiklyFont("$display-$suffix", weight, FontStyle.Normal) })
    }
}

private fun FontFamily.encodedSpaceGroteskWeight(): FontWeight? =
    when (this) {
        SpaceGroteskBold -> FontWeight.Bold
        SpaceGroteskSemiBold -> FontWeight.SemiBold
        SpaceGroteskMedium -> FontWeight.Medium
        SpaceGroteskRegular -> FontWeight.Normal
        else ->
            listOf(FontWeight.Bold, FontWeight.SemiBold, FontWeight.Medium, FontWeight.Normal, FontWeight.Light)
                .firstOrNull { weight -> fontFamilyFor("sans", RaftFontFaceState.preference, weight) == this }
    }

private fun renderedSpaceGroteskWeight(fontFamily: FontFamily, requestedWeight: FontWeight): FontWeight =
    if (raftPlatformPreservesEncodedFontWeightForFallback()) {
        fontFamily.encodedSpaceGroteskWeight() ?: requestedWeight
    } else {
        FontWeight.Normal
    }

// Mono resolves through the same per-weight name encoding: Kuikly drops weight info from the
// serialized family name, so the weight has to live in the font name. Space Mono ships only
// Regular (400) and Bold (700); the Light/Medium/SemiBold slots simply resolve to whichever
// bundled mono weight the host has, and the platform resolver falls back when a name is absent.
internal fun raftMonoFamilyForWeight(fontWeight: FontWeight?): FontFamily =
    fontFamilyFor("mono", RaftFontFaceState.preference, fontWeight ?: FontWeight.Normal)

// Convenience token for the common regular-weight mono callsites (task numbers,
// timestamps, counts, handles, codes). Use raftMonoFamilyForWeight() when a
// callsite needs bold mono. Task #179: read through the preference so mono follows the face choice.
internal val RaftMonoFamily: FontFamily
    get() = raftMonoFamilyForWeight(FontWeight.Normal)

private fun TextStyle.withRaftRenderedFontWeight(): TextStyle {
    val family = fontFamily?.takeIf { isRaftEncodedFamily(it) } ?: return this
    return copy(fontWeight = renderedSpaceGroteskWeight(family, fontWeight ?: FontWeight.Normal))
}

private fun raftTextStyle(
    fontWeight: FontWeight,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    letterSpacing: TextUnit
): TextStyle {
    val fontFamily = fontFamilyFor("sans", RaftFontFaceState.preference, fontWeight)
    return TextStyle(
        fontFamily = fontFamily,
        fontWeight = renderedSpaceGroteskWeight(fontFamily, fontWeight),
        fontSize = fontSize,
        lineHeight = lineHeight,
        letterSpacing = letterSpacing
    ).withRaftTextRendering()
}

internal fun TextStyle.withRaftFontWeight(fontWeight: FontWeight): TextStyle {
    val fontFamily = fontFamilyFor("sans", RaftFontFaceState.preference, fontWeight)
    return copy(
        fontFamily = fontFamily,
        fontWeight = renderedSpaceGroteskWeight(fontFamily, fontWeight)
    ).withRaftTextRendering()
}

// A SpanStyle carries no paragraph attributes (line height style, text motion): spans inherit
// them from the paragraph style of the text they sit in, which the RaftType tokens set.
internal fun raftSpanStyleForWeight(fontWeight: FontWeight): SpanStyle {
    val fontFamily = fontFamilyFor("sans", RaftFontFaceState.preference, fontWeight)
    return SpanStyle(
        fontFamily = fontFamily,
        fontWeight = renderedSpaceGroteskWeight(fontFamily, fontWeight)
    )
}

internal fun TextStyle.withResolvedRaftFontFamily(): TextStyle = resolveRaftFontFamily().withRaftTextRendering()

private fun TextStyle.resolveRaftFontFamily(): TextStyle {
    val requestedWeight = fontWeight
    return when {
        fontFamily == null ->
            raftFontFamilyForWeight(requestedWeight).let { family ->
                copy(
                    fontFamily = family,
                    fontWeight = renderedSpaceGroteskWeight(family, requestedWeight ?: FontWeight.Normal)
                )
            }
        isRaftEncodedFamily(fontFamily) && requestedWeight != null && requestedWeight != FontWeight.Normal ->
            raftFontFamilyForWeight(requestedWeight).let { family ->
                copy(fontFamily = family, fontWeight = renderedSpaceGroteskWeight(family, requestedWeight))
            }
        else ->
            withRaftRenderedFontWeight()
    }
}

/**
 * The raft-design Typography scale (designsystem [RaftTypography], 1:1 with raft-ui styles.css
 * `--text-*`), read here for its numbers only: size, line height, tracking and default weight.
 * The family always comes from the active face ([RaftFontFaceState]), so the families passed in
 * are placeholders.
 */
private val RaftScale: RaftTypography =
    RaftTypography.from(RaftFonts(sans = FontFamily.SansSerif, mono = FontFamily.Monospace))

private fun raftTextStyle(token: TextStyle, fontWeight: FontWeight = token.fontWeight ?: FontWeight.Normal): TextStyle =
    raftTextStyle(fontWeight, token.fontSize, token.lineHeight, token.letterSpacing)

/**
 * The one content text size: message text, the composer, body copy and sidebar conversation
 * names all read from here so they stay the same size. Lead decision 2026-10-19: the type scale
 * follows raft-design Typography, so content is `text-body` 16/24 (the 14sp body is retired).
 */
internal object RaftTextSize {
    val Content: TextUnit get() = RaftScale.body.fontSize
    val ContentLineHeight: TextUnit get() = RaftScale.body.lineHeight
}

/**
 * The legacy role names, each pointing at a raft-design Typography token (weights per the
 * Typography page's "Weights by use"). New code should prefer the designsystem's
 * `RaftTheme.typography` directly.
 */
internal object RaftType {
    // Task #179: these are GETTERS, not vals. A `val` is evaluated once at object initialisation
    // and then keeps whatever face was active at first touch -- pressing a font option would write
    // the preference and repaint nothing, because ~970 call sites read this object. A getter
    // re-resolves against RaftFontFaceState.preference on every read, so a change reaches them all.

    /** `text-title` 22/28 700, -0.01em. */
    val PageTitle: TextStyle
        get() = raftTextStyle(RaftScale.title)
    /**
     * raft-ui sidebar group / section label: `text-footnote font-bold normal-case
     * tracking-normal` (13/18 700), source case, no tracking.
     */
    val SectionLabel: TextStyle
        get() = raftTextStyle(RaftScale.footnote, FontWeight.Bold)
    /** `text-body` 16/24 400. */
    val Body: TextStyle
        get() = raftTextStyle(RaftScale.body)
    /** `text-caption` 12/16 500. */
    val Caption: TextStyle
        get() = raftTextStyle(RaftScale.caption)
    /** `text-callout` 14/20 at 500, the menu-item weight: row titles, menu items, quiet actions. */
    val Button: TextStyle
        get() = raftTextStyle(RaftScale.callout, FontWeight.Medium)
    /** `text-subhead` 15/20 700: buttons. */
    val ButtonBold: TextStyle
        get() = raftTextStyle(RaftScale.subhead, FontWeight.Bold)
    /** `text-label` 11/13 600: tab bar labels. */
    val TabLabel: TextStyle
        get() = raftTextStyle(RaftScale.label)
    /** `--field-*` 16/24 400. Typed text is user content: Simplified Chinese Han forms, like the Web. */
    val Input: TextStyle
        get() = raftTextStyle(RaftScale.field).withRaftUserContentLocale()
    /** Sidebar conversation names: `text-body` at 600 (raft-ui phone sidebar rows). */
    val ListItem: TextStyle
        get() = raftTextStyle(RaftScale.body, FontWeight.SemiBold)
    /** Selected / loud-unread sidebar names: `text-body` at 700. */
    val ListItemBold: TextStyle
        get() = raftTextStyle(RaftScale.body, FontWeight.Bold)
}
