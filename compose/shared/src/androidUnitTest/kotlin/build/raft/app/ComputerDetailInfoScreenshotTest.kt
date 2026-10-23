package build.raft.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerAgentScopesSnapshot
import build.raft.app.data.SettingsComputerCreatorSnapshot
import build.raft.app.data.SettingsComputerDetailSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ViewRootForTest
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import java.io.File
import java.time.Duration
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.fail
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of a computer's detail page (`settings.computer.info`): the Info section's
 * detected runtimes as chips, its created date and its creator, as on the web; and
 * `settings.computer.memory_limit_not_applied`, the same page with the "configured but not applied"
 * per-agent memory-limit warning (botiverse/slock #10860). Same capture, tolerance and
 * [RobolectricComposeActivityRule] as [ActivityConversationRowsScreenshotTest]; CI uploads the
 * report folder as `shared-home-screenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h760dp-xxhdpi")
class ComputerDetailInfoScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val previousAppearance = RaftAppThemeState.appearance

    @After
    fun restoreAppearance() {
        RaftAppThemeState.apply(previousAppearance)
    }

    @Test
    fun computerInfoLight() = capture("settings.computer.info", RaftAppAppearance.Light)

    @Test
    fun computerInfoDark() = capture("settings.computer.info.dark", RaftAppAppearance.Dark)

    @Test
    fun memoryLimitNotAppliedLight() = capture("settings.computer.memory_limit_not_applied", RaftAppAppearance.Light, MEMORY_LIMIT_NOT_APPLIED)

    @Test
    fun memoryLimitNotAppliedDark() = capture("settings.computer.memory_limit_not_applied.dark", RaftAppAppearance.Dark, MEMORY_LIMIT_NOT_APPLIED)

    private fun capture(name: String, appearance: RaftAppAppearance, agentScopes: SettingsComputerAgentScopesSnapshot? = null) {
        RaftAppThemeState.apply(appearance)
        val activity = activities.launch()
        // Moko string resources read the app context through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        val pager =
            object : Pager() {}.apply {
                pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
            }
        activity.setContent {
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { ComputerDetailVisualContent(appearance, agentScopes) }
        }
        settle()
        val composeView = findComposeRoot(activity.window.decorView)
        assertNotNull(composeView, "no compose root")
        val bitmap = Bitmap.createBitmap(composeView.width, composeView.height, Bitmap.Config.ARGB_8888)
        composeView.draw(android.graphics.Canvas(bitmap))
        verifyOrRecord(name, bitmap)
    }

    private fun verifyOrRecord(name: String, actual: Bitmap) {
        val reportDir = File("build/reports/home-screenshots").apply { mkdirs() }
        File(reportDir, "$name.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val golden = File(GOLDEN_DIR, "$name.png")
        if (record) {
            golden.parentFile.mkdirs()
            golden.outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("recorded ${golden.path} (${actual.width}x${actual.height})")
            return
        }
        val actualCopy = File(reportDir, "$name.actual.png")
        if (!golden.exists()) {
            actualCopy.outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fail("missing golden ${golden.path}; record with RAFT_SCREENSHOT_RECORD=true (actual: ${actualCopy.path})")
        }
        val expected = BitmapFactory.decodeFile(golden.path) ?: fail("unreadable golden ${golden.path}")
        if (expected.width != actual.width || expected.height != actual.height) {
            actualCopy.outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fail("$name: size ${actual.width}x${actual.height} != golden ${expected.width}x${expected.height}")
        }
        val diff = Bitmap.createBitmap(actual.width, actual.height, Bitmap.Config.ARGB_8888)
        val mismatched = screenshotMismatches(expected, actual, diff)
        val ratio = mismatched.toDouble() / (actual.width * actual.height)
        if (ratio > MAX_MISMATCH_RATIO) {
            actualCopy.outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(reportDir, "$name.diff.png").outputStream().use { diff.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fail("$name: $mismatched pixels (${"%.4f".format(ratio * 100)}%) differ from ${golden.path}; actual and diff in ${reportDir.path}")
        }
    }

    private fun settle() {
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200)) }
    }

    private fun findComposeRoot(view: View): View? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findComposeRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private companion object {
        const val GOLDEN_DIR = "src/androidUnitTest/screenshots"
        const val CHANNEL_DELTA = 8
        const val MAX_MISMATCH_RATIO = 0.001

        val MEMORY_LIMIT_NOT_APPLIED =
            SettingsComputerAgentScopesSnapshot(
                state = "unavailable",
                limitsConfigured = true,
                limitsNotApplied = true,
                reason = "memory_controller_not_delegated",
                memoryMax = "6G",
            )

        val record: Boolean =
            System.getProperty("raft.screenshot.record").toBoolean() ||
                System.getenv("RAFT_SCREENSHOT_RECORD").toBoolean()

        /** Pixels outside ±[CHANNEL_DELTA] on any ARGB channel; paints them red in [diff]. */
        fun screenshotMismatches(expected: Bitmap, actual: Bitmap, diff: Bitmap): Int {
            var mismatched = 0
            for (y in 0 until actual.height) {
                for (x in 0 until actual.width) {
                    val a = actual.getPixel(x, y)
                    val e = expected.getPixel(x, y)
                    val same = (0 until 32 step 8).all { shift -> abs(((a ushr shift) and 0xFF) - ((e ushr shift) and 0xFF)) <= CHANNEL_DELTA }
                    if (same) {
                        diff.setPixel(x, y, (a and 0x00FFFFFF) or 0x40000000)
                    } else {
                        mismatched++
                        diff.setPixel(x, y, 0xFFFF0000.toInt())
                    }
                }
            }
            return mismatched
        }
    }
}

/** A fixed computer and a fixed clock, so the capture is deterministic. */
@Composable
private fun ComputerDetailVisualContent(appearance: RaftAppAppearance, agentScopes: SettingsComputerAgentScopesSnapshot?) {
    val timeContext =
        RaftTimeContext(
            localOffsetMinutes = 8 * 60,
            todayEpochDay = raftEpochDayForDate(2026, 10, 22),
            nowEpochMillis = 1_792_635_000_000L,
        )
    val snapshot =
        SettingsComputerDetailSnapshot(
            computer =
                SettingsComputerSnapshot(
                    id = "m-1",
                    name = "armbian-gx",
                    daemonVersion = "",
                    computerVersion = "1.0.51",
                    isComputer = true,
                    online = true,
                    hostname = "armbian-gx",
                    platform = "linux arm64",
                    runtimes = listOf("claude", "builtin", "kimi-sdk", "kimi", "pi"),
                    createdAt = "2026-10-08T09:14:00Z",
                    // No photo or Gravatar, so the capture draws the placeholder person without a fetch.
                    creator = SettingsComputerCreatorSnapshot(id = "user-1", name = "artin", displayName = "Artin"),
                    agentScopes = agentScopes,
                ),
            workspaces = emptyList(),
            agents = emptyList(),
            sections = emptyList(),
        )
    val model = raftComputerDetailModel(ScreenLoadState.Content(snapshot), machineId = "m-1", targetServerId = null, currentServerId = "server-1")
    RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
        CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext)) {
            RaftComputersTheme {
                RaftSettingsComputerDetailContent(
                    model = model,
                    actionState = null,
                    onRetry = {},
                    onIntent = {},
                    onOpenAgent = {},
                )
            }
        }
    }
}
