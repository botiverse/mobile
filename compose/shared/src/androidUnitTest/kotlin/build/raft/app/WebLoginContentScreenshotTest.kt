package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalInspectionMode
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImagePainter
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import coil3.request.ErrorResult
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sign-in page (`auth.web-login.*`), light and dark: ready (terms accepted) and, after
 * "Sign in to Raft" (artin 10-27), pending — the button keeps its pink and ink with a spinner
 * before "Signing in…", and the self-hosted entry is disabled. Verify / record as
 * [RaftRobolectricScreenshot] (which freezes the spinner on its first step).
 *
 * The page's brand marks (top bar, centre) load through Coil's process-wide async loader, so
 * whether they had arrived by capture time depended on which tests ran earlier in the JVM (CI
 * 40553889793). Coil's preview handler resolves every image here to "no image" synchronously:
 * the goldens are about the form, and they come out the same on every run.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h640dp-xxhdpi")
class WebLoginContentScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun readyLight() = capture("auth.web-login.ready", RaftAppAppearance.Light, pending = false)

    @Test fun pendingLight() = capture("auth.web-login.pending", RaftAppAppearance.Light, pending = true)

    @Test fun pendingDark() = capture("auth.web-login.pending.dark", RaftAppAppearance.Dark, pending = true)

    private fun capture(name: String, appearance: RaftAppAppearance, pending: Boolean) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides NoImages,
            ) {
                RaftDesignSystemTheme {
                    WebLoginContent(
                        consentAccepted = true,
                        pending = pending,
                        error = null,
                        hintHost = "app.raft.build",
                        onConsentChange = {},
                        onOpenTerms = {},
                        onOpenPrivacy = {},
                        onSignIn = {},
                        onOpenPrivateDeployment = {},
                    )
                }
            }
        }
}

@OptIn(ExperimentalCoilApi::class)
private val NoImages =
    AsyncImagePreviewHandler { _, request ->
        AsyncImagePainter.State.Error(null, ErrorResult(null, request, IllegalStateException("fixture: no image")))
    }
