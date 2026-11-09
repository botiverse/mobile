package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.ActionCardFormValue
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A required Choice not yet answered says nothing until Next is tried (web, #triage:#261): Next can be
 * tapped, the tap keeps the page and only then shows "Choose an option first."; answering goes on.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionCardFormSheetPendingUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val questionForm by lazy { checkNotNull(ActionCardSpecFixtures.card("question_card").forms?.get("answers")) }

    @Test
    fun theHintShowsOnlyAfterNextIsTried() = runRobolectricComposeUiTest {
        val submitted = mutableListOf<Map<String, ActionCardFormValue>>()
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                var values by remember { mutableStateOf(initialActionCardFormValues(questionForm)) }
                RaftActionCardFormSheet(
                    form = questionForm,
                    values = values,
                    onValuesChange = { values = it },
                    fieldErrors = emptyList(),
                    submitting = false,
                    onSubmit = { submitted += it },
                    onDismiss = {},
                )
            }
        }
        waitForIdle()

        onNodeWithText("Choose an option first.").assertDoesNotExist()
        onNodeWithText("Next").assertIsEnabled().performClick()
        waitForIdle()
        onNodeWithText("Choose an option first.").assertExists()
        onNodeWithText("Question 1 of 3").assertExists()
        assertEquals(emptyList(), submitted)
    }
}
