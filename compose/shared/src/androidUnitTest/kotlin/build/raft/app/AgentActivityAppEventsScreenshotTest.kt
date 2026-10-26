package build.raft.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentActivityAppEvent
import build.raft.app.data.AgentActivityOverviewState
import build.raft.app.data.AgentActivityOverviewStatus
import build.raft.app.data.AgentActivityTurn
import build.raft.app.data.AgentActivityTurnOutcome
import build.raft.app.data.AgentActivityTurnTrigger
import build.raft.app.model.MemberId
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.theme.RaftTheme
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePainter
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import coil3.compose.asPainter
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin DM "应用头像有个问题": the app notifications of a turn on the agent Activity Overview
 * (web AgentActivityOverview `AppEventRow`), light and dark. Each logo is web ConnectedAppLogo
 * `xs`, raft-ui's 27dp Avatar sticker: an opaque square logo (Stamp's, white with its marks),
 * a logo with a transparent background (on the avatar's lavender fill), a logo that fails to
 * load (the App's initial) and an App with no logo (the generic App IconTile).
 *
 * Coil's preview handler stands in for the network: `fixture://stamp` and `fixture://glyph`
 * decode to drawn bitmaps, anything else fails. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class AgentActivityAppEventsScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun appEventsLight() = capture("members.agent-activity.app-events", RaftAppAppearance.Light)

    @Test
    fun appEventsDark() = capture("members.agent-activity.app-events.dark", RaftAppAppearance.Dark)

    @OptIn(ExperimentalCoilApi::class)
    private fun capture(name: String, appearance: RaftAppAppearance) {
        // As ThreadMessageAttachmentsScreenshotTest: install the app's loader before the first request.
        runCatching { build.raft.app.image.raftInstallImageLoader() }
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            val binding = remember { MembersAgentActivityOverviewBinding(state = MutableStateFlow(appEventsState()), onIntent = {}) }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides AppLogoImages,
            ) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
                        MembersAgentActivityOverview(
                            binding = binding,
                            now = MembersAgentActivityNow(label = "Working", indicator = null),
                            hasRawActivity = true,
                            hasComputer = true,
                            timeContext =
                                RaftTimeContext(
                                    localOffsetMinutes = 8 * 60,
                                    todayEpochDay = raftEpochDayForDate(2026, 6, 19),
                                    nowEpochMillis = 1_781_870_700_000L,
                                ),
                            onShowRaw = {},
                            onOpenComputer = null,
                            onOpenUrl = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

private fun appEventsState(): AgentActivityOverviewState =
    AgentActivityOverviewState(
        agentId = MemberId("agent-activity-app-events"),
        status = AgentActivityOverviewStatus.Loaded,
        turns =
            listOf(
                AgentActivityTurn(
                    id = "00000000-0000-4000-8000-000000000011",
                    startedAtEpochMillis = 1781869200000L,
                    endedAtEpochMillis = 1781869207000L,
                    outcome = AgentActivityTurnOutcome.Completed,
                    trigger =
                        AgentActivityTurnTrigger.Visible(
                            target = "agent-event:1a2b3c4d",
                            channelId = null,
                            sender = null,
                            messageId = null,
                            appEvent = AgentActivityTurnTrigger.AppEvent(appName = "Stamp", summary = "botiverse/stamp #1592 was merged"),
                        ),
                    actions = emptyList(),
                    toolCalls = 0,
                    problems = emptyList(),
                    problemCount = 0,
                    messagesSent = 0,
                    lastReplyMessageId = null,
                    appEvents =
                        listOf(
                            AgentActivityAppEvent("e1", "Stamp", "stamp", "fixture://stamp", "GitHub botiverse/stamp #1592 was merged by @stamp-build[bot] as 1521b3053cdc.", 1781869201000L),
                            AgentActivityAppEvent("e2", "Linear", "linear", "fixture://glyph", "RAFT-812 moved to In Review", 1781869202000L),
                            AgentActivityAppEvent("e3", "Sentry", "sentry", "fixture://fail", "New issue in raft-web", 1781869203000L),
                            AgentActivityAppEvent("e4", "Reminders", "system.reminders", null, "Check the nightly build", 1781869204000L),
                        ),
                    appEventCount = 4,
                ),
            ),
    )

@OptIn(ExperimentalCoilApi::class)
private val AppLogoImages =
    AsyncImagePreviewHandler { _, request ->
        val bitmap =
            when (request.data as? String) {
                "fixture://stamp" -> stampLogo()
                "fixture://glyph" -> glyphLogo()
                else -> null
            }
        if (bitmap == null) {
            AsyncImagePainter.State.Error(null, ErrorResult(null, request, IllegalStateException("fixture: no image")))
        } else {
            val image = bitmap.asImage()
            AsyncImagePainter.State.Success(image.asPainter(request.context), SuccessResult(image, request))
        }
    }

/** An opaque square logo, as Stamp's: a white square with a green bar over two grey ones. */
private fun stampLogo(): Bitmap =
    Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
        eraseColor(0xFFFFFFFF.toInt())
        val canvas = Canvas(this)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFF9AA0A6.toInt()
        canvas.drawRoundRect(18f, 52f, 70f, 66f, 4f, 4f, paint)
        canvas.drawRoundRect(18f, 40f, 74f, 54f, 4f, 4f, paint)
        paint.color = 0xFF2E9E5B.toInt()
        canvas.drawRoundRect(24f, 22f, 80f, 40f, 4f, 4f, paint)
    }

/** A logo with a transparent background: a violet disc. */
private fun glyphLogo(): Bitmap =
    Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
        eraseColor(0x00000000)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5E6AD2.toInt() }
        Canvas(this).drawCircle(48f, 48f, 30f, paint)
    }
