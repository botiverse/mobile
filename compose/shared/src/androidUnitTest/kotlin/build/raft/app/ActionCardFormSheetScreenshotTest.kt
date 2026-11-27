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
import build.raft.app.data.ActionCardSpecForm
import build.raft.app.thread.RaftActionCardFormSheetSurfaceForFixture
import build.raft.app.thread.initialActionCardFormValues
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
 * dark; and the vector's form_submit_labels decision form (schemaVersion 7) with Approve chosen,
 * its submit button named by submitLabels ("Approve this call"). A tall viewport so every field is on screen. Verify / record as
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
    fun filledLight() = capture("action-card.form.filled", RaftAppAppearance.Light, fixtures.filled)

    @Test
    fun filledDark() = capture("action-card.form.filled.dark", RaftAppAppearance.Dark, fixtures.filled)

    @Test
    fun errorsLight() = capture("action-card.form.errors", RaftAppAppearance.Light, fixtures.empty, issues = fixtures.serverIssues)

    /** The vector's question card form (schemaVersion 8): three steps, a single and a multiple Choice with Other, and a TextArea. */
    private val questionForm by lazy { checkNotNull(ActionCardSpecFixtures.card("question_card").forms?.get("answers")) }

    private fun questionValues(vararg overrides: Pair<String, ActionCardFormValue>) = initialActionCardFormValues(questionForm) + overrides

    // Step 1: a required single Choice with Other chosen and its own answer typed (radios, Other's input, Next).
    @Test
    fun questionChoiceOtherLight() =
        capture("action-card.question.choice-other", RaftAppAppearance.Light, questionValues("window" to ActionCardFormValue.Choice(emptyList(), "周五上午")), form = questionForm)

    private fun capture(
        name: String,
        appearance: RaftAppAppearance,
        values: Map<String, ActionCardFormValue>,
        issues: List<ActionCardFormIssue> = emptyList(),
        submitting: Boolean = false,
        form: ActionCardSpecForm = fixtures.form,
        page: Int = 0,
        tried: Boolean = false,
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
                            form = form,
                            values = values,
                            fieldErrors = issues,
                            submitting = submitting,
                            candidates = fixtures.candidates,
                            modifier = Modifier.fillMaxSize(),
                            page = page,
                            tried = tried,
                        )
                    }
                }
            }
        }
    }
}
