package build.raft.app

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * [runComposeUiTest] under Robolectric: semantics finders, actions and assertions on a real
 * composition. Use it with [RobolectricComposeActivityRule] as the test's rule (it revives a wedged
 * Compose dispatcher before the test). AGENTS.md "Compose UI tests".
 *
 * runComposeUiTest launches a [ComponentActivity] through ActivityScenario, which needs the activity
 * in the manifest. androidx ui-test-manifest would declare it, but with a NoActionBar theme that
 * also applies to the activities the screenshot tests build, changing every golden's size; so it
 * is registered here, for this test only (Robolectric resets the package manager per test).
 */
@OptIn(ExperimentalTestApi::class)
fun runRobolectricComposeUiTest(block: ComposeUiTest.() -> Unit) {
    val context = RuntimeEnvironment.getApplication()
    shadowOf(context.packageManager).addOrUpdateActivity(
        ActivityInfo().apply {
            name = ComponentActivity::class.java.name
            packageName = context.packageName
        }
    )
    runComposeUiTest(block = block)
}
