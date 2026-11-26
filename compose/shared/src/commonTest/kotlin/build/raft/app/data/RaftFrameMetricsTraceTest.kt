package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class RaftFrameMetricsTraceTest {
    @Test
    fun registeredFlagsIncludeAllThreeFrameMetricsTraceHierarchiesWithoutDuplicates() {
        assertEquals(REGISTERED_SERVER_FEATURE_FLAG_KEYS.distinct(), REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        assertEquals(REGISTERED_TRACE_SAMPLE_FEATURE_FLAG_KEYS.distinct(), REGISTERED_TRACE_SAMPLE_FEATURE_FLAG_KEYS)
        for (name in listOf(
            MOBILE_FRAMES_NAV_TRANSITION_TRACE_NAME,
            MOBILE_FRAMES_RESUME_TRACE_NAME,
            MOBILE_FRAMES_SCROLL_TRACE_NAME,
        )) {
            assertTrue(
                traceSampleKeys(name).all { it in REGISTERED_TRACE_SAMPLE_FEATURE_FLAG_KEYS },
                "missing trace-sample flag keys for $name",
            )
            assertTrue(
                traceSampleKeys(name).none { it in REGISTERED_SERVER_FEATURE_FLAG_KEYS },
                "trace-sample flag keys must not be in product flag batch for $name",
            )
        }
        assertEquals(
            listOf(
                "trace_sample.mobile.frames.scroll",
                "trace_sample.mobile.frames",
                "trace_sample.mobile",
                "trace_sample",
            ),
            traceSampleKeys(MOBILE_FRAMES_SCROLL_TRACE_NAME),
        )
    }

    @Test
    fun scrollReleaseDefaultIsTwoPercentWhileAlphaAndDebugStayFull() {
        assertEquals(0.02, defaultScrollFrameTraceSampleRate(buildType = "release", debugBuild = false))
        assertEquals(0.02, defaultScrollFrameTraceSampleRate(buildType = null, debugBuild = false))
        assertEquals(1.0, defaultScrollFrameTraceSampleRate(buildType = "alpha", debugBuild = false))
        assertEquals(1.0, defaultScrollFrameTraceSampleRate(buildType = "release", debugBuild = true))
    }

    @Test
    fun jankBoostsToRateOneWhileSmoothUsesBaseRateAndKillSwitchStopsAll() {
        val unresolved = ServerFeatureFlagSnapshot.Unresolved

        // Smooth record uses base rate and record_condition = trace_sample.
        val smoothHit =
            decideFrameMetricsTraceSampling(
                traceName = MOBILE_FRAMES_SCROLL_TRACE_NAME,
                flags = unresolved,
                fallbackBaseRate = 0.02,
                hasJankOrLongFrame = false,
                random = { 0.01 },
            )
        assertEquals(FrameMetricsTraceSampleDecision(0.02, FRAME_METRICS_CONDITION_TRACE_SAMPLE), smoothHit)

        val smoothMiss =
            decideFrameMetricsTraceSampling(
                traceName = MOBILE_FRAMES_SCROLL_TRACE_NAME,
                flags = unresolved,
                fallbackBaseRate = 0.02,
                hasJankOrLongFrame = false,
                random = { 0.05 },
            )
        assertNull(smoothMiss)

        // Jank / long-frame record emits at sample_rate = 1.0 with record_condition = jank.
        val jankHit =
            decideFrameMetricsTraceSampling(
                traceName = MOBILE_FRAMES_SCROLL_TRACE_NAME,
                flags = unresolved,
                fallbackBaseRate = 0.02,
                hasJankOrLongFrame = true,
                random = { 0.99 },
            )
        assertEquals(FrameMetricsTraceSampleDecision(1.0, FRAME_METRICS_CONDITION_JANK), jankHit)

        // Flag override on mobile.frames applies to all three; more specific scroll flag overrides it.
        val flagged =
            ServerFeatureFlagSnapshot(
                serverId = ServerId("srv-1"),
                resolved = true,
                values = mapOf(
                    "trace_sample.mobile.frames" to true,
                    "trace_sample.mobile.frames.scroll" to true,
                ),
                reasons = mapOf(
                    "trace_sample.mobile.frames" to "platform_rule",
                    "trace_sample.mobile.frames.scroll" to "platform_rule",
                ),
                variants = mapOf(
                    "trace_sample.mobile.frames" to "0.25",
                    "trace_sample.mobile.frames.scroll" to "0.05",
                ),
            )
        assertEquals(
            FrameMetricsTraceSampleDecision(0.25, FRAME_METRICS_CONDITION_TRACE_SAMPLE),
            decideFrameMetricsTraceSampling(MOBILE_FRAMES_NAV_TRANSITION_TRACE_NAME, flagged, 0.1, false) { 0.1 },
        )
        assertEquals(
            FrameMetricsTraceSampleDecision(0.05, FRAME_METRICS_CONDITION_TRACE_SAMPLE),
            decideFrameMetricsTraceSampling(MOBILE_FRAMES_SCROLL_TRACE_NAME, flagged, 0.02, false) { 0.01 },
        )

        // Explicit kill switch (flag off or rate 0) suppresses even jank records.
        val disabled =
            ServerFeatureFlagSnapshot(
                serverId = ServerId("srv-1"),
                resolved = true,
                values = mapOf("trace_sample.mobile.frames.scroll" to false),
                reasons = mapOf("trace_sample.mobile.frames.scroll" to "kill_switch"),
                variants = emptyMap(),
            )
        assertNull(
            decideFrameMetricsTraceSampling(MOBILE_FRAMES_SCROLL_TRACE_NAME, disabled, 0.02, hasJankOrLongFrame = true) { 0.0 },
        )

        // Enabled flag with rate "0" or "0.0" also suppresses even jank records.
        val zeroRateFlag =
            ServerFeatureFlagSnapshot(
                serverId = ServerId("srv-1"),
                resolved = true,
                values = mapOf("trace_sample.mobile.frames.nav_transition" to true),
                reasons = mapOf("trace_sample.mobile.frames.nav_transition" to "default"),
                variants = mapOf("trace_sample.mobile.frames.nav_transition" to "0.0"),
            )
        assertNull(
            decideFrameMetricsTraceSampling(MOBILE_FRAMES_NAV_TRANSITION_TRACE_NAME, zeroRateFlag, 0.1, hasJankOrLongFrame = true) { 0.0 },
        )

        // Parent-level kill switch suppresses child traces whose leaf key is missing_flag.
        val parentDisabled =
            ServerFeatureFlagSnapshot(
                serverId = ServerId("srv-1"),
                resolved = true,
                values = mapOf("trace_sample.mobile.frames" to false),
                reasons = mapOf(
                    "trace_sample.mobile.frames.scroll" to FEATURE_FLAG_REASON_MISSING_FLAG,
                    "trace_sample.mobile.frames" to "kill_switch",
                ),
                variants = emptyMap(),
            )
        assertNull(
            decideFrameMetricsTraceSampling(MOBILE_FRAMES_SCROLL_TRACE_NAME, parentDisabled, 0.02, hasJankOrLongFrame = true) { 0.0 },
        )

        // Defensive bounds: NaN suppresses; > 1.0 clamps sample_rate to 1.0.
        assertNull(
            decideFrameMetricsTraceSampling(MOBILE_FRAMES_NAV_TRANSITION_TRACE_NAME, unresolved, Double.NaN, hasJankOrLongFrame = true) { 0.0 },
        )
        assertEquals(
            FrameMetricsTraceSampleDecision(1.0, FRAME_METRICS_CONDITION_TRACE_SAMPLE),
            decideFrameMetricsTraceSampling(MOBILE_FRAMES_NAV_TRANSITION_TRACE_NAME, unresolved, 2.5, hasJankOrLongFrame = false) { 0.99 },
        )
    }

    @Test
    fun jankAndLongFramePredicatesEnforceThresholdAndScrollScopeRules() {
        // Nav transition: 99ms smooth vs 100ms long frame or jankFrames > 0.
        assertFalse(isNavTransitionFrameMetricsJankOrLong(jankFrames = 0, longestFrameMs = 99L, firstFrameMs = 99L))
        assertTrue(isNavTransitionFrameMetricsJankOrLong(jankFrames = 1, longestFrameMs = 16L, firstFrameMs = 16L))
        assertTrue(isNavTransitionFrameMetricsJankOrLong(jankFrames = 0, longestFrameMs = 100L, firstFrameMs = 16L))
        assertTrue(isNavTransitionFrameMetricsJankOrLong(jankFrames = 0, longestFrameMs = 16L, firstFrameMs = 100L))

        // Resume: checks jankFrames, longestFrameMs, blockedMs, and firstFrameMs at 100ms.
        assertFalse(isResumeFrameMetricsJankOrLong(jankFrames = 0, longestFrameMs = 99L, blockedMs = 99L, firstFrameMs = 99L))
        assertTrue(isResumeFrameMetricsJankOrLong(jankFrames = 0, longestFrameMs = 16L, blockedMs = 100L, firstFrameMs = 16L))
        assertTrue(isResumeFrameMetricsJankOrLong(jankFrames = 0, longestFrameMs = 16L, blockedMs = 0L, firstFrameMs = 100L))

        // Scroll: ignores skipped fields when skippedSessions == 0, and ignores primary fields on paused_skipped.
        assertFalse(
            isScrollFrameMetricsJankOrLong(
                reason = "stopped",
                jankFrames = 0,
                longestFrameMs = 99L,
                firstFrameMs = 99L,
                skippedSessions = 0,
                skippedJankFrames = 5,
                skippedLongestFrameMs = 250L,
            ),
        )
        assertTrue(
            isScrollFrameMetricsJankOrLong(
                reason = "stopped",
                jankFrames = 0,
                longestFrameMs = 16L,
                firstFrameMs = 16L,
                skippedSessions = 1,
                skippedJankFrames = 0,
                skippedLongestFrameMs = 100L,
            ),
        )
        assertFalse(
            isScrollFrameMetricsJankOrLong(
                reason = "paused_skipped",
                jankFrames = 3,
                longestFrameMs = 200L,
                firstFrameMs = 150L,
                skippedSessions = 0,
                skippedJankFrames = 0,
                skippedLongestFrameMs = 0L,
            ),
        )
        assertTrue(
            isScrollFrameMetricsJankOrLong(
                reason = "paused_skipped",
                jankFrames = 0,
                longestFrameMs = 0L,
                firstFrameMs = -1L,
                skippedSessions = 2,
                skippedJankFrames = 1,
                skippedLongestFrameMs = 45L,
            ),
        )
    }

    @Test
    fun sanitizersAllowlistRoutesListsAndExtractMajorOsVersionOnly() {
        assertEquals("compose_thread", normalizeFrameTracePageName("compose_thread"))
        assertEquals("compose_slock_app_shell", normalizeFrameTracePageName("compose_slock_app_shell"))
        assertEquals("other", normalizeFrameTracePageName("channel-9f8a7b6c"))
        assertEquals("other", normalizeFrameTracePageName("Secret Project Thread"))
        assertEquals("other", normalizeFrameTracePageName(""))

        assertEquals("thread", normalizeFrameTraceListName("thread"))
        assertEquals("inbox", normalizeFrameTraceListName("inbox"))
        assertEquals("home", normalizeFrameTraceListName("home"))
        assertEquals("other", normalizeFrameTraceListName("channel_12345"))

        assertEquals("14", normalizeFrameTraceOsMajorVersion("14"))
        assertEquals("14", normalizeFrameTraceOsMajorVersion("14.2.1"))
        assertEquals("18", normalizeFrameTraceOsMajorVersion("iOS 18.4.1"))
        assertNull(normalizeFrameTraceOsMajorVersion(""))
        assertNull(normalizeFrameTraceOsMajorVersion("unknown"))

        assertEquals("23117RK66C", normalizeFrameTraceDeviceModel(" 23117RK66C\n "))
        assertNull(normalizeFrameTraceDeviceModel("   "))

        assertEquals(60, frameIntervalNanosToRefreshRateHz(16_666_666L))
        assertEquals(90, frameIntervalNanosToRefreshRateHz(11_111_111L))
        assertEquals(120, frameIntervalNanosToRefreshRateHz(8_333_333L))
        assertNull(frameIntervalNanosToRefreshRateHz(0L))
    }

    @Test
    fun navTransitionSpanCarriesAgreedFieldsAndDropsVsyncAndMinFrame() {
        val span =
            buildNavTransitionFrameTraceSpan(
                navKind = "push",
                outcome = "completed",
                fromPage = "compose_slock_app_shell",
                toPage = "compose_thread",
                firstFrameMs = 36L,
                durationMs = 545L,
                frames = 9,
                jankFrames = 2,
                longestFrameMs = 99L,
                p95FrameMs = 100L,
                refreshRateHz = 60,
                refreshRateEndHz = 90,
                deviceModel = "23117RK66C",
                osVersion = "14.1",
                sampleRate = 1.0,
                recordCondition = FRAME_METRICS_CONDITION_JANK,
                endEpochMillis = 1_763_784_000_545L,
                random = Random(1),
            )
        assertEquals(MOBILE_FRAMES_NAV_TRANSITION_TRACE_NAME, span.name)
        assertEquals("push", span.attrs["nav_kind"]?.jsonPrimitive?.content)
        assertNull(span.attrs["kind"], "must use nav_kind, never kind")
        assertNull(span.attrs["vsync_ms"], "vsync_ms dropped in favor of refresh_rate_hz")
        assertNull(span.attrs["min_frame_ms"], "min_frame_ms dropped")
        assertEquals("completed", span.attrs["outcome"]?.jsonPrimitive?.content)
        assertEquals("compose_slock_app_shell", span.attrs["from_page"]?.jsonPrimitive?.content)
        assertEquals("compose_thread", span.attrs["to_page"]?.jsonPrimitive?.content)
        assertEquals(36L, span.attrs["first_frame_ms"]?.jsonPrimitive?.longOrNull)
        assertEquals(545L, span.attrs["duration_ms"]?.jsonPrimitive?.longOrNull)
        assertEquals(9, span.attrs["frames"]?.jsonPrimitive?.intOrNull)
        assertEquals(2, span.attrs["jank_frames"]?.jsonPrimitive?.intOrNull)
        assertEquals(99L, span.attrs["longest_frame_ms"]?.jsonPrimitive?.longOrNull)
        assertEquals(100L, span.attrs["p95_frame_ms"]?.jsonPrimitive?.longOrNull)
        assertEquals(60, span.attrs["refresh_rate_hz"]?.jsonPrimitive?.intOrNull)
        assertEquals(90, span.attrs["refresh_rate_end_hz"]?.jsonPrimitive?.intOrNull)
        assertEquals("23117RK66C", span.attrs["device_model"]?.jsonPrimitive?.content)
        assertEquals("14", span.attrs["os_version"]?.jsonPrimitive?.content)
        assertEquals(1.0, span.attrs["sample_rate"]?.jsonPrimitive?.doubleOrNull)
        assertEquals("jank", span.attrs["record_condition"]?.jsonPrimitive?.content)
        assertEquals(32, span.traceId.length)
        assertEquals(16, span.spanId.length)
    }

    @Test
    fun resumeSpanCarriesResumeAttributesWithoutMissingFirstFrame() {
        val span =
            buildResumeFrameTraceSpan(
                page = "compose_slock_app_shell",
                reason = "window",
                backgroundMs = 12_500L,
                startToResumeMs = 107L,
                firstFrameMs = -1L,
                windowMs = 3_000L,
                frames = 178,
                jankFrames = 0,
                blockedMs = 0L,
                longestFrameMs = 18L,
                refreshRateHz = 60,
                refreshRateEndHz = 90,
                deviceModel = "Pixel 8",
                osVersion = "15",
                sampleRate = 0.1,
                recordCondition = FRAME_METRICS_CONDITION_TRACE_SAMPLE,
                endEpochMillis = 1_763_784_003_000L,
                random = Random(2),
            )
        assertEquals(MOBILE_FRAMES_RESUME_TRACE_NAME, span.name)
        assertEquals("compose_slock_app_shell", span.attrs["page"]?.jsonPrimitive?.content)
        assertEquals("window", span.attrs["reason"]?.jsonPrimitive?.content)
        assertEquals(12_500L, span.attrs["background_ms"]?.jsonPrimitive?.longOrNull)
        assertEquals(107L, span.attrs["start_to_resume_ms"]?.jsonPrimitive?.longOrNull)
        assertEquals(3_000L, span.attrs["window_ms"]?.jsonPrimitive?.longOrNull)
        assertNull(span.attrs["duration_ms"], "resume uses window_ms and span start/end, not duplicate duration_ms")
        assertEquals(0L, span.attrs["blocked_ms"]?.jsonPrimitive?.longOrNull)
        assertNull(span.attrs["first_frame_ms"], "absent (-1) first frame should be omitted")
        assertEquals(60, span.attrs["refresh_rate_hz"]?.jsonPrimitive?.intOrNull)
        assertEquals(90, span.attrs["refresh_rate_end_hz"]?.jsonPrimitive?.intOrNull)
        assertEquals("15", span.attrs["os_version"]?.jsonPrimitive?.content)
        assertEquals(0.1, span.attrs["sample_rate"]?.jsonPrimitive?.doubleOrNull)
        assertEquals("trace_sample", span.attrs["record_condition"]?.jsonPrimitive?.content)

        // Negative background/startToResume and null device/OS metadata are omitted cleanly.
        val minimal =
            buildResumeFrameTraceSpan(
                page = "compose_thread",
                reason = "paused",
                backgroundMs = -1L,
                startToResumeMs = -1L,
                firstFrameMs = 12L,
                windowMs = 200L,
                frames = 10,
                jankFrames = 0,
                blockedMs = 0L,
                longestFrameMs = 16L,
                refreshRateHz = null,
                deviceModel = null,
                osVersion = null,
                sampleRate = 0.1,
                recordCondition = FRAME_METRICS_CONDITION_TRACE_SAMPLE,
                endEpochMillis = 1_763_784_005_000L,
                random = Random(5),
            )
        assertNull(minimal.attrs["background_ms"])
        assertNull(minimal.attrs["start_to_resume_ms"])
        assertNull(minimal.attrs["device_model"])
        assertNull(minimal.attrs["os_version"])
        assertNull(minimal.attrs["refresh_rate_hz"])
        assertEquals(12L, minimal.attrs["first_frame_ms"]?.jsonPrimitive?.longOrNull)
    }

    @Test
    fun scrollSpanHandlesNormalSessionsAndPausedSkippedFlushes() {
        val normal =
            buildScrollFrameTraceSpan(
                page = "compose_thread",
                list = "thread",
                reason = "stopped",
                firstFrameMs = 8L,
                durationMs = 450L,
                frames = 27,
                jankFrames = 1,
                longestFrameMs = 36L,
                p95FrameMs = 22L,
                refreshRateHz = 60,
                refreshRateEndHz = 60,
                skippedSessions = 2,
                skippedJankFrames = 1,
                skippedLongestFrameMs = 44L,
                deviceModel = "23117RK66C",
                osVersion = "14",
                sampleRate = 1.0,
                recordCondition = FRAME_METRICS_CONDITION_JANK,
                endEpochMillis = 1_763_784_000_450L,
                random = Random(3),
            )
        assertEquals(MOBILE_FRAMES_SCROLL_TRACE_NAME, normal.name)
        assertEquals("thread", normal.attrs["list"]?.jsonPrimitive?.content)
        assertEquals(2, normal.attrs["skipped_sessions"]?.jsonPrimitive?.intOrNull)
        assertEquals(1, normal.attrs["skipped_jank_frames"]?.jsonPrimitive?.intOrNull)
        assertEquals(44L, normal.attrs["skipped_longest_frame_ms"]?.jsonPrimitive?.longOrNull)
        assertNotNull(normal.attrs["p95_frame_ms"])

        val skippedOnly =
            buildScrollFrameTraceSpan(
                page = "compose_slock_app_shell",
                list = "inbox",
                reason = "paused_skipped",
                firstFrameMs = -1L,
                durationMs = 0L,
                frames = 0,
                jankFrames = 0,
                longestFrameMs = 0L,
                p95FrameMs = 0L,
                refreshRateHz = null,
                refreshRateEndHz = null,
                skippedSessions = 3,
                skippedJankFrames = 2,
                skippedLongestFrameMs = 68L,
                deviceModel = "23117RK66C",
                osVersion = "14",
                sampleRate = 1.0,
                recordCondition = FRAME_METRICS_CONDITION_JANK,
                endEpochMillis = 1_763_784_001_000L,
                random = Random(4),
            )
        assertEquals("paused_skipped", skippedOnly.attrs["reason"]?.jsonPrimitive?.content)
        assertNull(skippedOnly.attrs["frames"])
        assertNull(skippedOnly.attrs["p95_frame_ms"])
        assertEquals(3, skippedOnly.attrs["skipped_sessions"]?.jsonPrimitive?.intOrNull)
        assertEquals(2, skippedOnly.attrs["skipped_jank_frames"]?.jsonPrimitive?.intOrNull)
        assertEquals(68L, skippedOnly.attrs["skipped_longest_frame_ms"]?.jsonPrimitive?.longOrNull)
        assertFalse("min_frame_ms" in skippedOnly.attrs)

        // When skippedSessions == 0, skipped_jank_frames and skipped_longest_frame_ms are omitted.
        val noSkipped =
            buildScrollFrameTraceSpan(
                page = "compose_thread",
                list = "thread",
                reason = "stopped",
                firstFrameMs = -1L,
                durationMs = 200L,
                frames = 12,
                jankFrames = 0,
                longestFrameMs = 17L,
                p95FrameMs = 17L,
                refreshRateHz = 60,
                refreshRateEndHz = 60,
                skippedSessions = 0,
                skippedJankFrames = 9,
                skippedLongestFrameMs = 120L,
                deviceModel = null,
                osVersion = null,
                sampleRate = 0.02,
                recordCondition = FRAME_METRICS_CONDITION_TRACE_SAMPLE,
                endEpochMillis = 1_763_784_002_000L,
                random = Random(6),
            )
        assertEquals(0, noSkipped.attrs["skipped_sessions"]?.jsonPrimitive?.intOrNull)
        assertNull(noSkipped.attrs["skipped_jank_frames"])
        assertNull(noSkipped.attrs["skipped_longest_frame_ms"])
        assertNull(noSkipped.attrs["first_frame_ms"])
        assertNull(noSkipped.attrs["device_model"])
        assertNull(noSkipped.attrs["os_version"])

        // Cancelled nav transition with 0 frames and absent first frame omits first_frame_ms and nulls.
        val cancelledNav =
            buildNavTransitionFrameTraceSpan(
                navKind = "predictive_back",
                outcome = "cancelled",
                fromPage = "compose_thread",
                toPage = "compose_slock_app_shell",
                firstFrameMs = -1L,
                durationMs = 40L,
                frames = 0,
                jankFrames = 0,
                longestFrameMs = 0L,
                p95FrameMs = 0L,
                refreshRateHz = 120,
                refreshRateEndHz = 120,
                deviceModel = null,
                osVersion = null,
                sampleRate = 0.1,
                recordCondition = FRAME_METRICS_CONDITION_TRACE_SAMPLE,
                endEpochMillis = 1_763_784_002_500L,
                random = Random(7),
            )
        assertEquals("predictive_back", cancelledNav.attrs["nav_kind"]?.jsonPrimitive?.content)
        assertEquals("cancelled", cancelledNav.attrs["outcome"]?.jsonPrimitive?.content)
        assertEquals(0, cancelledNav.attrs["frames"]?.jsonPrimitive?.intOrNull)
        assertNull(cancelledNav.attrs["first_frame_ms"])
        assertNull(cancelledNav.attrs["device_model"])
        assertNull(cancelledNav.attrs["os_version"])
    }
}
