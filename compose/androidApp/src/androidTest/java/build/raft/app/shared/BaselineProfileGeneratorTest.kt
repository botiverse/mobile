package build.raft.app.shared

import android.content.Intent
import android.os.SystemClock
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Profile generator for the Thread first-open journey (task #25 v1).
 *
 * Drives the auth-free visual fixture [PageName] through a cold-ish launch and a
 * short scroll so the captured profile covers Activity creation, Kuikly
 * container/composer setup, the fixture's initial 16-row compose/layout/draw,
 * and the first pagination render path. The collected profile is written to the
 * device and pulled into `src/main/baseline-prof.txt`, which AGP compiles and
 * packs into non-debuggable APKs (`assets/dexopt/baseline.prof`) for install-time
 * AOT. Home cold start is intentionally out of scope for v1 (needs auth) — see v2.
 *
 * Generate + pull (connectedTest variant, fixtures enabled):
 * ```
 * ./gradlew :androidApp:assembleConnectedTest :androidApp:assembleConnectedTestAndroidTest \
 *     -PslockVisualFixtures=true
 * adb install -r androidApp/build/outputs/apk/connectedTest/androidApp-connectedTest.apk
 * adb install -r androidApp/build/outputs/apk/androidTest/connectedTest/androidApp-connectedTest-androidTest.apk
 * adb shell am instrument -w -r \
 *     -e class build.raft.app.shared.BaselineProfileGeneratorTest \
 *     -e androidx.benchmark.profiling.enable true \
 *     build.raft.app.connectedtest.test/androidx.test.runner.AndroidJUnitRunner
 * adb pull /sdcard/Android/media/build.raft.app.connectedtest/baseline-prof.txt \
 *     androidApp/src/main/baseline-prof.txt
 * ```
 *
 * The fixture renders through an opaque Kuikly surface, so this waits on idle +
 * a fixed settle rather than depending on UiAutomator reading Kuikly text; the
 * AUTO-MSG wait is best-effort only and never fails the run.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGeneratorTest {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generateThreadFirstOpenProfile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetPackage = instrumentation.targetContext.packageName
        rule.collect(
            packageName = targetPackage,
            includeInStartupProfile = true,
        ) {
            repeat(JourneyIterations) { iteration ->
                val nonce = "bp-gen-$iteration-${SystemClock.uptimeMillis()}"
                val intent =
                    Intent()
                        .setClassName(targetPackage, KuiklyActivityClassName)
                        .putExtra(SlockCmpActivity.KEY_PAGE_NAME, PageName)
                        .putExtra(
                            SlockCmpActivity.KEY_PAGE_DATA,
                            JSONObject().put("runNonce", nonce).toString(),
                        )
                        .putExtra(SlockCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivityAndWait(intent)

                // Best-effort wait for the fixture's initial focused history to
                // render (seq 8 is within the initial 16 rows). Never fatal: the
                // fixed settle below still captures the launch + compose path.
                device.wait(Until.findObject(By.text(InitialContentMarker)), ContentWaitMs)
                device.waitForIdle()
                SystemClock.sleep(ContentSettleMs)

                // One upward swipe to exercise the first newer-pagination render
                // path and additional row compose/layout beyond the initial set.
                val width = device.displayWidth
                val height = device.displayHeight
                device.swipe(width / 2, (height * 0.78).toInt(), width / 2, (height * 0.30).toInt(), ScrollSteps)
                device.waitForIdle()
                SystemClock.sleep(ScrollSettleMs)

                // Return home so the next iteration starts from a fresh foreground
                // transition rather than an in-page onNewIntent.
                device.pressHome()
                device.waitForIdle()
                SystemClock.sleep(HomeSettleMs)
            }
        }
    }

    private companion object {
        private const val PageName = "compose_thread_newer_auto_pagination_visual"
        private const val KuiklyActivityClassName = "build.raft.app.shared.SlockKuiklyActivity"
        private const val InitialContentMarker = "AUTO-MSG-0008"
        private const val JourneyIterations = 3
        private const val ContentWaitMs = 8_000L
        private const val ContentSettleMs = 3_000L
        private const val ScrollSteps = 40
        private const val ScrollSettleMs = 1_500L
        private const val HomeSettleMs = 1_000L
    }
}
