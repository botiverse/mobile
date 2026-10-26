package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ActionCardFormIssue
import build.raft.app.data.ActionCardFormValue
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of the action card form sheet (`action-card.form.*`, A3 #37): the phone
 * sheet's surface (no window) holding the vector's feedback form, all seven field types, empty,
 * filled, with server issues under every field and a form banner, and submitting; light and
 * dark. A tall viewport so every field is on screen. Verify / record as
 * [RaftRobolectricScreenshot]; reports land in `build/reports/home-screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1800dp-xxhdpi")
class ActionCardFormSheetScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val fixtures = ActionCardFormTestFixtures

    @Test
    fun emptyLight() = capture("action-card.form.empty", RaftAppAppearance.Light, fixtures.empty)

    @Test
    fun emptyDark() = capture("action-card.form.empty.dark", RaftAppAppearance.Dark, fixtures.empty)

    @Test
    fun filledLight() = capture("action-card.form.filled", RaftAppAppearance.Light, fixtures.filled)

    @Test
    fun filledDark() = capture("action-card.form.filled.dark", RaftAppAppearance.Dark, fixtures.filled)

    @Test
    fun errorsLight() = capture("action-card.form.errors", RaftAppAppearance.Light, fixtures.empty, issues = fixtures.serverIssues)

    @Test
    fun errorsDark() = capture("action-card.form.errors.dark", RaftAppAppearance.Dark, fixtures.empty, issues = fixtures.serverIssues)

    @Test
    fun submittingLight() = capture("action-card.form.submitting", RaftAppAppearance.Light, fixtures.filled, submitting = true)

    @Test
    fun submittingDark() = capture("action-card.form.submitting.dark", RaftAppAppearance.Dark, fixtures.filled, submitting = true)

    private fun capture(
        name: String,
        appearance: RaftAppAppearance,
        values: Map<String, ActionCardFormValue>,
        issues: List<ActionCardFormIssue> = emptyList(),
        submitting: Boolean = false,
    ) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        RaftActionCardFormSheetSurfaceForFixture(
                            form = fixtures.form,
                            values = values,
                            fieldErrors = issues,
                            submitting = submitting,
                            candidates = fixtures.candidates,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}
