package build.raft.app

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextMotion
import build.raft.app.data.AppLocale
import build.raft.app.data.currentSystemLanguageTag
import build.raft.designsystem.theme.RaftTypography

/**
 * How Raft text is laid out and rasterised, independent of size and weight: the parts of a
 * [TextStyle] that make the same Space Grotesk files look like the Web.
 *
 * Line height: CSS centres the glyphs in the line box (half-leading above and below) and never
 * trims it. Compose's default is [LineHeightStyle.Alignment.Proportional] with
 * [LineHeightStyle.Trim.Both], which trims the first line's top and the last line's bottom, so a
 * single line sits low and is ~2dp shorter than the Web's box. Every Raft style uses
 * [RaftTypography.CssLineHeightStyle] (Center, Trim.None) instead.
 */
internal val RaftCssLineHeightStyle: LineHeightStyle get() = RaftTypography.CssLineHeightStyle

/**
 * Glyph positioning. Android's default [TextMotion.Static] hints glyphs and snaps every advance
 * to a whole pixel, so Space Grotesk's spacing drifts from the browser's (which positions glyphs
 * at subpixel offsets with no hinting). Android returns [TextMotion.Animated] (linear text,
 * subpixel positioning); other platforms return null and keep their default.
 */
internal expect fun raftPlatformTextMotion(): TextMotion?

/**
 * Han glyph forms. One code point (骨, 直, 戸, ...) has different glyphs in Simplified Chinese,
 * Traditional Chinese and Japanese; the platform picks the CJK fallback font from the text's
 * locale, which defaults to the device locale. The Web's sans stack names Simplified Chinese
 * faces after Space Grotesk ("PingFang SC", "Noto Sans SC", ...), so every Han character on the
 * Web renders in Simplified forms, whatever the browser language.
 *
 * - User content (message and markdown bodies, names, previews, typed text): always zh-Hans,
 *   like the Web. Use [withRaftUserContentLocale].
 * - UI text (the RaftType tokens, [LocalTextStyle]): follows the app UI language. A Japanese UI
 *   ([AppLocale.Japanese], or "System" on a Japanese device) uses `ja`, so its kanji keep
 *   Japanese forms; every other UI language uses zh-Hans, the Web's stack.
 */
internal val RaftUserContentLocaleList: LocaleList = LocaleList("zh-Hans")
private val RaftJapaneseUiLocaleList: LocaleList = LocaleList("ja")

// Never throws: unit-test Android stubs and old platforms lack the locale APIs.
private fun raftSystemLanguageTagOrNull(): String? = runCatching { currentSystemLanguageTag() }.getOrNull()

internal fun raftUiTextLocaleList(
    appLocale: AppLocale? = KmmResourceLocaleRuntime.locale.value,
    systemLanguageTag: () -> String? = ::raftSystemLanguageTagOrNull,
): LocaleList {
    val japaneseUi =
        when (appLocale) {
            AppLocale.Japanese -> true
            // Not seeded yet / "System default": the strings follow the device language.
            null, AppLocale.System -> systemLanguageTag()?.trim()?.lowercase()?.let { it == "ja" || it.startsWith("ja-") || it.startsWith("ja_") } == true
            AppLocale.English, AppLocale.SimplifiedChinese -> false
        }
    return if (japaneseUi) RaftJapaneseUiLocaleList else RaftUserContentLocaleList
}

/**
 * The current UI-text locale, cached: token getters read it on every style construction, and
 * resolving the device language (Android `Resources`, iOS `NSLocale.preferredLanguages`) per
 * read is too expensive. A snapshot [State][androidx.compose.runtime.State], so text composed
 * from the tokens recomposes when it changes. [refresh] recomputes only when the app language or
 * the device language actually changed; [RaftProvideTextRendering] calls it on a Settings
 * language change and whenever a page resumes (the device language can change in between).
 */
internal object RaftUiTextLocaleState {
    private var lastAppLocale: AppLocale? = KmmResourceLocaleRuntime.locale.value
    private var lastSystemTag: String? = raftSystemLanguageTagOrNull()

    var localeList: LocaleList by mutableStateOf(raftUiTextLocaleList(lastAppLocale) { lastSystemTag })
        private set

    fun refresh(
        appLocale: AppLocale? = KmmResourceLocaleRuntime.locale.value,
        systemTag: String? = raftSystemLanguageTagOrNull(),
    ) {
        if (appLocale == lastAppLocale && systemTag == lastSystemTag) return
        lastAppLocale = appLocale
        lastSystemTag = systemTag
        val next = raftUiTextLocaleList(appLocale) { systemTag }
        if (next != localeList) localeList = next
    }
}

/** Marks a style as user content: Han characters render in Simplified Chinese forms. */
internal fun TextStyle.withRaftUserContentLocale(): TextStyle =
    if (localeList == RaftUserContentLocaleList) this else copy(localeList = RaftUserContentLocaleList)

/**
 * Fills in the rendering attributes a style left unspecified. Attributes a call site set
 * explicitly (a different [LineHeightStyle], say) win.
 */
internal fun TextStyle.withRaftTextRendering(): TextStyle {
    val motion = raftPlatformTextMotion()
    val needsLineBox = lineHeightStyle == null
    val needsMotion = textMotion == null && motion != null
    val needsLocale = localeList == null
    if (!needsLineBox && !needsMotion && !needsLocale) return this
    return copy(
        lineHeightStyle = if (needsLineBox) RaftCssLineHeightStyle else lineHeightStyle,
        textMotion = if (needsMotion) motion else textMotion,
        localeList = if (needsLocale) RaftUiTextLocaleState.localeList else localeList,
    )
}

/** The rendering defaults as a style, for [LocalTextStyle] and merges. */
internal fun raftTextRenderingStyle(uiLocaleList: LocaleList = RaftUiTextLocaleState.localeList): TextStyle =
    TextStyle(
        lineHeightStyle = RaftCssLineHeightStyle,
        textMotion = raftPlatformTextMotion(),
        localeList = uiLocaleList,
    )

/**
 * Provides the rendering defaults through [LocalTextStyle], so `Text` calls whose style was not
 * built from a [RaftType] token (an ad-hoc `TextStyle(...)`, or no style at all) render the same
 * way. `material3.Text` merges its `style` over [LocalTextStyle]; unspecified attributes fall
 * back to these.
 */
@Composable
internal fun RaftProvideTextRendering(content: @Composable () -> Unit) {
    // A language change in Settings refreshes the UI locale; so does every resume, since the
    // device language may have changed while the app was in the background.
    val appLocale by KmmResourceLocaleRuntime.locale.collectAsState()
    SideEffect { RaftUiTextLocaleState.refresh(appLocale) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) RaftUiTextLocaleState.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.merge(raftTextRenderingStyle(RaftUiTextLocaleState.localeList)),
        content = content,
    )
}
