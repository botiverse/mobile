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

    private val decisionForm by lazy { checkNotNull(ActionCardSpecFixtures.card("form_submit_labels").forms?.get("decision")) }

    @Test
    fun submitLabelsLight() =
        capture("action-card.form.submit-labels", RaftAppAppearance.Light, mapOf("decision" to ActionCardFormValue.Text("approve")), form = decisionForm)

    @Test
    fun submitLabelsDark() =
        capture("action-card.form.submit-labels.dark", RaftAppAppearance.Dark, mapOf("decision" to ActionCardFormValue.Text("approve")), form = decisionForm)

    /** The vector's question card form (schemaVersion 8): three steps, a single and a multiple Choice with Other, and a TextArea. */
    private val questionForm by lazy { checkNotNull(ActionCardSpecFixtures.card("question_card").forms?.get("answers")) }

    private fun questionValues(vararg overrides: Pair<String, ActionCardFormValue>) = initialActionCardFormValues(questionForm) + overrides

    // Step 1: a required single Choice with Other chosen and its own answer typed (radios, Other's input, Next).
    @Test
    fun questionChoiceOtherLight() =
        capture("action-card.question.choice-other", RaftAppAppearance.Light, questionValues("window" to ActionCardFormValue.Choice(emptyList(), "周五上午")), form = questionForm)

    @Test
    fun questionChoiceOtherDark() =
        capture("action-card.question.choice-other.dark", RaftAppAppearance.Dark, questionValues("window" to ActionCardFormValue.Choice(emptyList(), "周五上午")), form = questionForm)

    // Step 1 with Next tapped before choosing: the page stays and the line under the list says why.
    @Test
    fun questionPendingLight() = capture("action-card.question.pending", RaftAppAppearance.Light, questionValues(), form = questionForm, tried = true)

    // Step 2 of 3: a multiple Choice (checkboxes) with one option ticked and Other typed, under the back arrow.
    @Test
    fun questionMultipleStepLight() =
        capture(
            "action-card.question.steps-multiple",
            RaftAppAppearance.Light,
            questionValues("envs" to ActionCardFormValue.Choice(listOf("staging"), "preview-eu")),
            form = questionForm,
            page = 1,
        )

    @Test
    fun questionMultipleStepDark() =
        capture(
            "action-card.question.steps-multiple.dark",
            RaftAppAppearance.Dark,
            questionValues("envs" to ActionCardFormValue.Choice(listOf("staging"), "preview-eu")),
            form = questionForm,
            page = 1,
        )

    // Step 3 of 3: the optional free text, and the form's own submit label.
    @Test
    fun questionLastStepLight() = capture("action-card.question.steps-last", RaftAppAppearance.Light, questionValues(), form = questionForm, page = 2)

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
