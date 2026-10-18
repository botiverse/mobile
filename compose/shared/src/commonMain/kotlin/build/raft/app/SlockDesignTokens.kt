package build.raft.app

import build.raft.app.data.FontPreference
import build.raft.app.designtokens.RaftUiPalette
import build.raft.app.designtokens.RaftUiThemeColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.font.KuiklyFont
import com.tencent.kuikly.compose.ui.unit.TextUnit
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

// Colours that exist on Web come from the generated tokens (tools/design-tokens), so a Web
// palette change shows up as a regenerated diff instead of silent drift. The remaining
// literals have no Web token.
internal object SlockColor {
    val BrandYellow = RaftUiPalette.brutalYellow
    // Semantic alias for react `--color-soft-signal` (== brutal/brand yellow #FFD440),
    // used for the self-mention chip highlight so callsites read intent, not hue.
    val SoftSignal = RaftUiPalette.softSignal
    val AccentPink = RaftUiPalette.brutalPink
    val AccentCyan = RaftUiPalette.brutalCyan
    val AccentOrange = RaftUiPalette.brutalOrange
    val AccentLavender = RaftUiPalette.brutalLavender
    val AccentLime = RaftUiPalette.brutalLime
    val AccentRed = RaftUiPalette.brutalRed
    val AccentStone = RaftUiPalette.brutalStone
    // raft-ui `--warning` (#FF6900): the warning badge/banner fill. Distinct from AccentOrange.
    val StateWarningBase = RaftUiPalette.warning
    val SurfaceWhite = RaftUiPalette.white
    val SurfaceCream = RaftUiPalette.brutalCream
    val AppBgLightBlue = Color(0xFFE6F8FF)
    val EmptyStateGray = Color(0xFFE7E7E7)
    // Tailwind gray-400 (#99A1AF): the neutral / offline status-dot fill.
    // Not the same gray as EmptyStateGray (#E7E7E7).
    val StatusNeutralGray = RaftUiPalette.gray400
    // Web brutal `--foreground` (body text, #141110 — a warm near-black, not pure black).
    val TextBlack = RaftUiThemeColors.Brutal.foreground
    // Tailwind neutral-500.
    val TextMutedGray = RaftUiPalette.neutral500
    // Web brutal `--line` (#141110).
    val BorderBlack = RaftUiThemeColors.Brutal.line
    val ShadowBlack = RaftUiPalette.brutalBlack

    const val BrandYellowArgb = "#FFFFD440"
    const val SurfaceWhiteArgb = "#FFFFFFFF"
    // Must equal TextBlack; Kuikly string-colour callsites.
    const val TextBlackArgb = "#FF141110"
    const val TextMutedGrayArgb = "#FF737373"
    const val BorderBlackArgb = "#FF141110"
    const val ShadowBlackArgb = "#FF141111"
    const val AccentOrangeArgb = "#FFF8A16F"
    const val AccentRedArgb = "#FFF97264"
}

internal object SlockSpacing {
    val Xxs = 2.dp
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 16.dp
    val Lg = 24.dp
    val Xl = 32.dp
}

internal object SlockStroke {
    val Thin = 1.dp
    val Default = 2.dp
    val Strong = 3.dp
}

internal object SlockRadius {
    val None = 0.dp
    val Xs = 2.dp
    val Sm = 4.dp
}

internal object SlockSize {
    val MinTouchTarget = 48.dp
    val IconButton = 44.dp
    val HeaderIcon = 36.dp
    val PageHeaderHeight = 62.dp
    val PageHeaderContentHeight = PageHeaderHeight - SlockStroke.Default
    val PageHeaderCompactHeight = 48.dp
    val BottomBarHeight = 53.dp
}

internal object SlockShadow {
    val Brutal = 4.dp
    val BrutalSmall = 2.dp
    val BrutalLarge = 6.dp
    val BrutalActive = 1.dp
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
    if (preference == FontPreference.SpaceGrotesk) return SpaceGroteskFamilies
    if (preference.sansFamily == null) return emptySet()
    return listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
        .mapTo(mutableSetOf()) { fontFamilyFor("sans", preference, it) }
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
        else -> null
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

internal object SlockType {
    // Task #179: these are GETTERS, not vals. A `val` is evaluated once at object initialisation
    // and then keeps whatever face was active at first touch -- pressing a font option would write
    // the preference and repaint nothing, because ~970 call sites read this object. A getter
    // re-resolves against SlockFontFaceState.preference on every read, so a change reaches them all.
    val PageTitle: TextStyle
        get() = slockTextStyle(FontWeight.SemiBold, 20.sp, 24.sp, 0.sp)
    val SectionLabel: TextStyle
        get() = slockTextStyle(FontWeight.SemiBold, 12.sp, 16.sp, 1.2.sp)
    val Body: TextStyle
        get() = slockTextStyle(FontWeight.Normal, 14.sp, 20.sp, 0.sp)
    val Caption: TextStyle
        get() = slockTextStyle(FontWeight.Normal, 12.sp, 16.sp, 0.sp)
    val Button: TextStyle
        get() = slockTextStyle(FontWeight.Medium, 14.sp, 18.sp, 0.sp)
    val ButtonBold: TextStyle
        get() = slockTextStyle(FontWeight.Bold, 14.sp, 18.sp, 0.sp)
    val TabLabel: TextStyle
        get() = slockTextStyle(FontWeight.Bold, 10.sp, 12.sp, 0.sp)
    val Input: TextStyle
        get() = slockTextStyle(FontWeight.Normal, 14.sp, 20.sp, 0.sp)
}
