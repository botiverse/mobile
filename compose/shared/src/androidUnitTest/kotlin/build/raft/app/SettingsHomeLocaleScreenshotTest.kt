package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.applyVisualCaptureLocale
import build.raft.app.data.restoreVisualCaptureLocale
import kotlin.test.AfterTest
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone Settings home in each of the four later UI languages (the web's es, pt-br, ko and
 * zh-tw): its rows and section labels come from that language's catalog, and the UI text locale
 * follows it (Traditional Han forms for zh-TW, a Korean face for Hangul). A 390dp-wide phone;
 * verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h800dp-xxhdpi")
class SettingsHomeLocaleScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @AfterTest
    fun clearSeededHostInsets() {
        RaftHostWindowInsets.remove("")
    }

    @Test
    fun settingsHomeSpanish() = capture("settings.home.es", AppLocale.Spanish)

    @Test
    fun settingsHomeBrazilianPortuguese() = capture("settings.home.pt-BR", AppLocale.BrazilianPortuguese)

    @Test
    fun settingsHomeKorean() = capture("settings.home.ko", AppLocale.Korean)

    @Test
    fun settingsHomeTraditionalChinese() = capture("settings.home.zh-TW", AppLocale.TraditionalChinese)

    private fun capture(name: String, locale: AppLocale) {
        val token = locale.applyVisualCaptureLocale()
        RaftUiTextLocaleState.refresh(locale, listOf("en-US"))
        try {
            RaftRobolectricScreenshot.capture(name, RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
                val pager =
                    remember {
                        // xxhdpi: 72px is a 24dp status bar.
                        object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("statusBarHeight", 72) }) }
                    }
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                    RaftDesignSystemTheme {
                        SettingsHomeContent(
                            payload = appShellSettingsHomePayload(session = null, appPreferencesUnderAppearance = false, billingOpensExternally = false),
                            onBack = {},
                            onSelect = {},
                        )
                    }
                }
            }
        } finally {
            restoreVisualCaptureLocale(token)
            RaftUiTextLocaleState.refresh(token.runtimeLocale, listOf("en-US"))
        }
    }
}
