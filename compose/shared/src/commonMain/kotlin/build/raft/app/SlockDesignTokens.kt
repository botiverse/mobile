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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// Colours that exist on Web come from the generated tokens (tools/design-tokens), so a Web
// palette change shows up as a regenerated diff instead of silent drift. The remaining
// literals have no Web token.
internal object SlockColor {
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
    // Themed tokens below read [SlockThemeState], so they follow light/dark at runtime.
    // Web raft `--foreground` (body text; light #141111, a warm near-black).
    val TextBlack: Color get() = SlockThemeState.colors.foreground
    // Tailwind neutral-500.
    val TextMutedGray = RaftUiPalette.neutral500
    // Web raft `--line-strong` (control ink).
    val BorderBlack: Color get() = SlockThemeState.colors.lineStrong
    // Web raft `--line-hairline` (ink at 15%): quiet separators.
    val BorderHairline: Color get() = SlockThemeState.colors.lineHairline
    // Web raft `--line-seam` (ink at 45%): structural seams between panels.
    val BorderSeam: Color get() = SlockThemeState.colors.lineSeam
    // Sticker ink (`--elevation-sticker` colour); the only hard shadow left in raft.
    val ShadowBlack: Color get() = SlockThemeState.colors.ink
    // Web raft surfaces and secondary ink, for primitives migrating off the fixed
    // SurfaceWhite / TextMutedGray literals.
    val Canvas: Color get() = SlockThemeState.colors.layerCanvas
    val Card: Color get() = SlockThemeState.colors.layerCard
    val Popover: Color get() = SlockThemeState.colors.layerPopover
    val TextMuted: Color get() = SlockThemeState.colors.foregroundMuted

    // raft-ui 0.7 semantic roles the primitives draw with (foundation.css). Themed ones
    // follow light/dark through [SlockThemeState]; the solid "sticker" fills are the same
    // colour in both modes, so they read straight from the palette.
    // Text and icon ink ladder.
    val ForegroundHint: Color get() = SlockThemeState.colors.foregroundHint
    val ForegroundDisabled: Color get() = SlockThemeState.colors.foregroundDisabled
    val ForegroundInverse: Color get() = SlockThemeState.colors.foregroundInverse
    // Opaque neutral fills: resting/disabled control fill and the 8% pressed wash.
    val FillMuted: Color get() = SlockThemeState.colors.fillMuted
    val FillStrong: Color get() = SlockThemeState.colors.fillStrong
    val FillPressed: Color get() = SlockThemeState.colors.fillPressed
    // `--line-field` (input ink); card edges and menu separators use [BorderHairline].
    val LineField: Color get() = SlockThemeState.colors.lineField
    // The yellow frame (`--layer-frame`) and the overlay backdrop.
    val Frame: Color get() = SlockThemeState.colors.layerFrame
    val Backdrop: Color get() = SlockThemeState.colors.layerBackdrop
    // State washes (`-soft`) and readable state ink (`-strong`).
    val BrandSoft: Color get() = SlockThemeState.colors.brandSoft
    val BrandStrong: Color get() = SlockThemeState.colors.brandStrong
    val AttentionSoft: Color get() = SlockThemeState.colors.attentionSoft
    val AttentionStrong: Color get() = SlockThemeState.colors.attentionStrong
    val InfoSoft: Color get() = SlockThemeState.colors.infoSoft
    val InfoStrong: Color get() = SlockThemeState.colors.infoStrong
    val SuccessSoft: Color get() = SlockThemeState.colors.successSoft
    val SuccessStrong: Color get() = SlockThemeState.colors.successStrong
    val WarningSoft: Color get() = SlockThemeState.colors.warningSoft
    val WarningStrong: Color get() = SlockThemeState.colors.warningStrong
    val DangerSoft: Color get() = SlockThemeState.colors.dangerSoft
    val DangerStrong: Color get() = SlockThemeState.colors.dangerStrong
    val InactiveSoft: Color get() = SlockThemeState.colors.inactiveSoft
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
    val StickerEdge: Color get() = SlockThemeState.colors.stickerEdge
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
            if (SlockThemeState.isDark) {
                RaftUiPalette.black.copy(alpha = 0.8f)
            } else {
                SlockThemeState.colors.ink.copy(alpha = 0.32f)
            }

    const val BrandYellowArgb = "#FFFFD440"
    const val SurfaceWhiteArgb = "#FFFFFFFF"
    // Kuikly string-colour callsites; themed ones derive from their Color token.
    val TextBlackArgb: String get() = slockArgbHex(TextBlack)
    const val TextMutedGrayArgb = "#FF737373"
    val BorderBlackArgb: String get() = slockArgbHex(BorderBlack)
    val ShadowBlackArgb: String get() = slockArgbHex(ShadowBlack)
    const val AccentOrangeArgb = "#FFF8A16F"
    const val AccentRedArgb = "#FFF97264"
}

/** `#AARRGGBB` for Kuikly APIs that take a colour string. */
internal fun slockArgbHex(color: Color): String {
    fun channel(value: Float): String = (value * 255f).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()
    return "#" + channel(color.alpha) + channel(color.red) + channel(color.green) + channel(color.blue)
}

internal object SlockSpacing {
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
internal object SlockStroke {
    val Thin = 1.dp
    val Control = 1.5.dp
    val Default = Control
    val Strong = 2.dp
}

// raft-ui 0.6.0 radii (styles.css --radius-*).
internal object SlockRadius {
    val None = 0.dp
    val Xs = 2.dp
    val Sm = 4.dp
    val Sticker = 4.dp
    val Chip = 6.dp
    val Control = 8.dp
    val Container = 10.dp
    val Dialog = 16.dp
}

internal object SlockSize {
    val MinTouchTarget = 48.dp
    val IconButton = 44.dp
    val HeaderIcon = 36.dp
    // raft panel heads are 56px (48px under 600px window height).
    val PageHeaderHeight = 56.dp
    val PageHeaderContentHeight = PageHeaderHeight - SlockStroke.Default
    val PageHeaderCompactHeight = 48.dp
    val BottomBarHeight = 53.dp
}

// raft-ui 0.6.0: controls never cast a shadow (`--theme-shadow-xs/sm/md` are 0); only
// stickers keep a hard 4px offset and only overlays float. The brutal offsets are
// therefore zero; [Sticker] is the one remaining hard offset.
internal object SlockShadow {
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
 * SlockType / SlockMonoFamily helpers, so the preference cannot be threaded per-call-site.
 * Instead the root reads [SlockFontFaceState.preference] during composition; changing it
 * repaints every text style that re-resolves a token.
 *
 * Deliberately a Compose state, not a bare getter: a plain field would change the value but
 * leave already-composed text on the old face.
 */
internal object SlockFontFaceState {
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
fun slockInputFontFaceName(): String? = slockSansRegularFaceName(SlockFontFaceState.preference)

internal fun slockSansRegularFaceName(preference: FontPreference): String? =
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
private fun slockEncodedFamilies(): Set<FontFamily> {
    val preference = SlockFontFaceState.preference
    if (preference.sansFamily == null) return emptySet()
    val resolved =
        listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
            .mapTo(mutableSetOf()) { fontFamilyFor("sans", preference, it) }
    // The type tokens resolve through fontFamilyFor() for every option, Space Grotesk included, so
    // that set must be recognised; the legacy Space constants stay recognised for older callers.
    return if (preference == FontPreference.SpaceGrotesk) resolved + SpaceGroteskFamilies else resolved
}

private fun isSlockEncodedFamily(fontFamily: FontFamily?): Boolean =
    fontFamily != null && fontFamily in slockEncodedFamilies()

// Task #179: one family set per selectable option. The names match the Android
// `SlockKuiklyActivity.resolveTypeface()` aliases and the OHOS/iOS registration names.
// Families are cached because this runs on every text style construction; a fresh FontFamily
// per call would allocate on a hot path for no behavioural gain.
private val slockFamilyCache = mutableMapOf<Triple<String, FontPreference, String>, FontFamily>()

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
    return slockFamilyCache.getOrPut(Triple(role, preference, suffix)) {
        // KuiklyFont names use PascalWithDashes ("HankenGrotesk-SemiBold"); the storage key is
        // snake_case ("hanken_grotesk"), so rebuild the display name from the parts.
        val display = base.split('_').joinToString("") { part ->
            part.replaceFirstChar { it.uppercaseChar() }
        }
        FontFamily(KuiklyFont("$display-$suffix", fontWeight, FontStyle.Normal))
    }
}

internal fun slockFontFamilyForWeight(fontWeight: FontWeight?): FontFamily =
    fontFamilyFor("sans", SlockFontFaceState.preference, fontWeight ?: FontWeight.Normal)

private fun FontFamily.encodedSpaceGroteskWeight(): FontWeight? =
    when (this) {
        SpaceGroteskBold -> FontWeight.Bold
        SpaceGroteskSemiBold -> FontWeight.SemiBold
        SpaceGroteskMedium -> FontWeight.Medium
        SpaceGroteskRegular -> FontWeight.Normal
        else ->
            listOf(FontWeight.Bold, FontWeight.SemiBold, FontWeight.Medium, FontWeight.Normal, FontWeight.Light)
                .firstOrNull { weight -> fontFamilyFor("sans", SlockFontFaceState.preference, weight) == this }
    }

private fun renderedSpaceGroteskWeight(fontFamily: FontFamily, requestedWeight: FontWeight): FontWeight =
    if (slockPlatformPreservesEncodedFontWeightForFallback()) {
        fontFamily.encodedSpaceGroteskWeight() ?: requestedWeight
    } else {
        FontWeight.Normal
    }

// Mono resolves through the same per-weight name encoding: Kuikly drops weight info from the
// serialized family name, so the weight has to live in the font name. Space Mono ships only
// Regular (400) and Bold (700); the Light/Medium/SemiBold slots simply resolve to whichever
// bundled mono weight the host has, and the platform resolver falls back when a name is absent.
internal fun slockMonoFamilyForWeight(fontWeight: FontWeight?): FontFamily =
    fontFamilyFor("mono", SlockFontFaceState.preference, fontWeight ?: FontWeight.Normal)

// Convenience token for the common regular-weight mono callsites (task numbers,
// timestamps, counts, handles, codes). Use slockMonoFamilyForWeight() when a
// callsite needs bold mono. Task #179: read through the preference so mono follows the face choice.
internal val SlockMonoFamily: FontFamily
    get() = slockMonoFamilyForWeight(FontWeight.Normal)

private fun TextStyle.withSlockRenderedFontWeight(): TextStyle {
    val family = fontFamily?.takeIf { isSlockEncodedFamily(it) } ?: return this
    return copy(fontWeight = renderedSpaceGroteskWeight(family, fontWeight ?: FontWeight.Normal))
}

private fun slockTextStyle(
    fontWeight: FontWeight,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    letterSpacing: TextUnit
): TextStyle {
    val fontFamily = fontFamilyFor("sans", SlockFontFaceState.preference, fontWeight)
    return TextStyle(
        fontFamily = fontFamily,
        fontWeight = renderedSpaceGroteskWeight(fontFamily, fontWeight),
        fontSize = fontSize,
        lineHeight = lineHeight,
        letterSpacing = letterSpacing
    )
}

internal fun TextStyle.withSlockFontWeight(fontWeight: FontWeight): TextStyle {
    val fontFamily = fontFamilyFor("sans", SlockFontFaceState.preference, fontWeight)
    return copy(
        fontFamily = fontFamily,
        fontWeight = renderedSpaceGroteskWeight(fontFamily, fontWeight)
    )
}

internal fun slockSpanStyleForWeight(fontWeight: FontWeight): SpanStyle {
    val fontFamily = fontFamilyFor("sans", SlockFontFaceState.preference, fontWeight)
    return SpanStyle(
        fontFamily = fontFamily,
        fontWeight = renderedSpaceGroteskWeight(fontFamily, fontWeight)
    )
}

internal fun TextStyle.withResolvedSlockFontFamily(): TextStyle {
    val requestedWeight = fontWeight
    return when {
        fontFamily == null ->
            slockFontFamilyForWeight(requestedWeight).let { family ->
                copy(
                    fontFamily = family,
                    fontWeight = renderedSpaceGroteskWeight(family, requestedWeight ?: FontWeight.Normal)
                )
            }
        isSlockEncodedFamily(fontFamily) && requestedWeight != null && requestedWeight != FontWeight.Normal ->
            slockFontFamilyForWeight(requestedWeight).let { family ->
                copy(fontFamily = family, fontWeight = renderedSpaceGroteskWeight(family, requestedWeight))
            }
        else ->
            withSlockRenderedFontWeight()
    }
}

/**
 * The one content text size (artin 2026-10-18, 1.14.1): message text, the composer, body copy and
 * sidebar conversation names all read from here so they stay the same size. 16sp was tried on
 * device and reverted to the shipped 14sp.
 */
internal object SlockTextSize {
    val Content = 14.sp
    val ContentLineHeight = 20.sp
    /** Single-line list rows (sidebar names) keep the tighter line height buttons always had. */
    val ListItemLineHeight = 18.sp
}

internal object SlockType {
    // Task #179: these are GETTERS, not vals. A `val` is evaluated once at object initialisation
    // and then keeps whatever face was active at first touch -- pressing a font option would write
    // the preference and repaint nothing, because ~970 call sites read this object. A getter
    // re-resolves against SlockFontFaceState.preference on every read, so a change reaches them all.
    val PageTitle: TextStyle
        get() = slockTextStyle(FontWeight.SemiBold, 20.sp, 24.sp, 0.sp)
    // raft-ui group/section labels are source case at `tracking-normal`; the 1.2sp
    // tracking only ever served the retired ALL-CAPS brutal labels.
    val SectionLabel: TextStyle
        get() = slockTextStyle(FontWeight.SemiBold, 12.sp, 16.sp, 0.sp)
    val Body: TextStyle
        get() = slockTextStyle(FontWeight.Normal, SlockTextSize.Content, SlockTextSize.ContentLineHeight, 0.sp)
    val Caption: TextStyle
        get() = slockTextStyle(FontWeight.Normal, 12.sp, 16.sp, 0.sp)
    val Button: TextStyle
        get() = slockTextStyle(FontWeight.Medium, 14.sp, 18.sp, 0.sp)
    val ButtonBold: TextStyle
        get() = slockTextStyle(FontWeight.Bold, 14.sp, 18.sp, 0.sp)
    val TabLabel: TextStyle
        get() = slockTextStyle(FontWeight.Bold, 10.sp, 12.sp, 0.sp)
    val Input: TextStyle
        get() = slockTextStyle(FontWeight.Normal, SlockTextSize.Content, SlockTextSize.ContentLineHeight, 0.sp)
    /** Sidebar conversation names: content size, button weights. */
    val ListItem: TextStyle
        get() = slockTextStyle(FontWeight.Medium, SlockTextSize.Content, SlockTextSize.ListItemLineHeight, 0.sp)
    val ListItemBold: TextStyle
        get() = slockTextStyle(FontWeight.Bold, SlockTextSize.Content, SlockTextSize.ListItemLineHeight, 0.sp)
}
