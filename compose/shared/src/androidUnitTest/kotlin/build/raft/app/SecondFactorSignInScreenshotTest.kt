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
import build.raft.app.data.SecondFactorSignInEnd
import build.raft.app.data.SecondFactorSignInState
import build.raft.app.data.SecondFactorStepUpFieldError
import build.raft.app.data.SecondFactorStepUpMethod
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
 * The second step of a sign-in (web SecondFactorSignInStep, slock v1.21.44 #12259): an
 * authenticator code that didn't work, with "Use a Backup Code" under "or" (light), and an
 * expired challenge that leaves only Back to Sign In (dark). Brand marks resolve to "no image"
 * as in [WebLoginContentScreenshotTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h640dp-xxhdpi")
class SecondFactorSignInScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val codeState =
        SecondFactorSignInState(
            maskedEmail = "a***@example.com",
            expiresAtEpochMillis = 0L,
            methods = listOf(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode),
            method = SecondFactorStepUpMethod.Totp,
            input = "000000",
            fieldError = SecondFactorStepUpFieldError.CodeInvalid,
        )

    @Test fun codeWrongLight() = capture("auth.second-factor.code-wrong", RaftAppAppearance.Light, codeState)

    @Test fun expiredDark() =
        capture(
            "auth.second-factor.expired.dark",
            RaftAppAppearance.Dark,
            codeState.copy(input = "", fieldError = null, ended = SecondFactorSignInEnd.Expired),
        )

    private fun capture(name: String, appearance: RaftAppAppearance, state: SecondFactorSignInState) =
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
                LocalAsyncImagePreviewHandler provides SecondFactorNoImages,
            ) {
                RaftDesignSystemTheme {
                    SecondFactorSignInContent(state = state, onIntent = {}, formatClock = { "16:05" })
                }
            }
        }
}

@OptIn(ExperimentalCoilApi::class)
private val SecondFactorNoImages =
    AsyncImagePreviewHandler { _, request ->
        AsyncImagePainter.State.Error(null, ErrorResult(null, request, IllegalStateException("fixture: no image")))
    }
