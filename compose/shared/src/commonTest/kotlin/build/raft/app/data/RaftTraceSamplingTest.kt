package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The App's mirror of botiverse/slock `packages/shared/src/traceSampling.ts`. The first five cases
 * are the vectors of slock `traceSampling.test.ts` (#15673 at 624b7701b), case for case.
 */
class RaftTraceSamplingTest {
    private fun missing(key: String) = TraceSampleEvaluation(key, enabled = false, reason = "missing_flag")

    @Test
    fun listsTheKeysForATraceMostSpecificFirst() {
        assertEquals(
            listOf("trace_sample.mobile.thread_open", "trace_sample.mobile", "trace_sample"),
            traceSampleKeys("mobile.thread_open"),
        )
    }

    @Test
    fun readsRatesAndRefusesAnythingThatIsNotOne() {
        assertEquals(0.1, parseTraceSampleRate("0.1"))
        assertEquals(1.0, parseTraceSampleRate(" 1 "))
        assertEquals(0.0, parseTraceSampleRate("0"))
        for (bad in listOf("1.5", "-0.1", "0.l", "", "10%", "NaN", "1e-1", null)) {
            assertNull(parseTraceSampleRate(bad), "variant <$bad>")
        }
    }

    @Test
    fun theMostSpecificConfiguredKeyDecides() {
        val evaluations =
            listOf(
                missing("trace_sample.mobile.thread_open"),
                TraceSampleEvaluation("trace_sample.mobile", enabled = true, reason = "platform_rule", variant = "0.25"),
                TraceSampleEvaluation("trace_sample", enabled = true, reason = "default", variant = "1"),
            )
        assertEquals(0.25, resolveTraceSampleRate("mobile.thread_open", evaluations, 0.5))
    }

    @Test
    fun offIsZeroOnWithoutAVariantIsOneAndNothingConfiguredOrAnInvalidVariantIsTheDefault() {
        val cases =
            listOf(
                listOf(TraceSampleEvaluation("trace_sample.web.x", enabled = false, reason = "flag_disabled")) to 0.0,
                listOf(TraceSampleEvaluation("trace_sample.web.x", enabled = false, reason = "kill_switch")) to 0.0,
                listOf(TraceSampleEvaluation("trace_sample.web.x", enabled = true, reason = "default")) to 1.0,
                listOf(missing("trace_sample.web.x"), missing("trace_sample.web")) to 0.5,
                emptyList<TraceSampleEvaluation>() to 0.5,
                // An invalid variant is not "on with no variant": the App default applies, not 1.
                listOf(TraceSampleEvaluation("trace_sample.web.x", enabled = true, reason = "default", variant = "0.l")) to 0.5,
                listOf(TraceSampleEvaluation("trace_sample.web.x", enabled = true, reason = "default", variant = "10%")) to 0.5,
            )
        for ((evaluations, expected) in cases) {
            assertEquals(expected, resolveTraceSampleRate("web.x", evaluations, 0.5), "$evaluations")
        }
    }

    @Test
    fun samplesWholeUnitsByRate() {
        assertFalse(traceSampled(0.0) { 0.0 })
        assertTrue(traceSampled(1.0) { 0.99 })
        assertTrue(traceSampled(0.1) { 0.05 })
        assertFalse(traceSampled(0.1) { 0.5 })
    }

    @Test
    fun theDefaultIsEveryOpenOnAlphaAndDebugAndOneInTenOnRelease() {
        assertEquals(1.0, defaultTraceSampleRate(buildType = "alpha", debugBuild = false))
        assertEquals(1.0, defaultTraceSampleRate(buildType = "release", debugBuild = true))
        assertEquals(0.1, defaultTraceSampleRate(buildType = "release", debugBuild = false))
        assertEquals(0.1, defaultTraceSampleRate(buildType = null, debugBuild = false))
    }

    /** The evaluate answer as staging gave it for mobile (2026-11-19): a platform rule with variant "0.1". */
    @Test
    fun theFlagStoreKeepsTheReasonSoTheSnapshotResolvesTheRate() = runTest {
        val server = ServerId("s1")
        assertTrue(traceSampleKeys(MOBILE_THREAD_OPEN_TRACE_NAME).all { it in REGISTERED_TRACE_SAMPLE_FEATURE_FLAG_KEYS })
        assertTrue(traceSampleKeys(MOBILE_THREAD_OPEN_TRACE_NAME).none { it in REGISTERED_SERVER_FEATURE_FLAG_KEYS })
        val api =
            object : RaftFeatureFlagsApi {
                override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
                    HomeNetworkResponse(
                        200,
                        """{"evaluations":[""" +
                            """{"key":"trace_sample.mobile.thread_open","enabled":true,"reason":"platform_rule","variant":"0.1"},""" +
                            """{"key":"trace_sample.mobile","enabled":false,"reason":"missing_flag"},""" +
                            """{"key":"trace_sample","enabled":false,"reason":"missing_flag"}]}""",
                    )
            }
        val flags = SharedServerFeatureFlags(api)
        assertTrue(flags.traceSampleSnapshotFor(server).traceSampleEvaluations(MOBILE_THREAD_OPEN_TRACE_NAME).isEmpty(), "unresolved: the default applies")
        val snapshot = flags.prefetchTraceSampling(server)
        assertEquals("platform_rule", snapshot.reasons["trace_sample.mobile.thread_open"])
        assertEquals(0.1, resolveTraceSampleRate(MOBILE_THREAD_OPEN_TRACE_NAME, snapshot.traceSampleEvaluations(MOBILE_THREAD_OPEN_TRACE_NAME), 1.0))
        assertEquals(snapshot, flags.traceSampleSnapshotFor(server))
    }

    @Test
    fun clientErrorAndWebViewTraceSampleFamiliesAreRegisteredWithExpectedDefaults() {
        assertEquals(REGISTERED_SERVER_FEATURE_FLAG_KEYS.distinct(), REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        assertEquals(REGISTERED_TRACE_SAMPLE_FEATURE_FLAG_KEYS.distinct(), REGISTERED_TRACE_SAMPLE_FEATURE_FLAG_KEYS)
        for (name in listOf(
            MOBILE_HTTP_ERROR_TRACE_NAME,
            MOBILE_UI_ERROR_TRACE_NAME,
            MOBILE_WEBVIEW_TRACE_NAME,
            MOBILE_WEB_CONTAINER_TRACE_NAME,
        )) {
            assertTrue(
                traceSampleKeys(name).all { it in REGISTERED_TRACE_SAMPLE_FEATURE_FLAG_KEYS },
                "missing trace_sample keys for $name",
            )
            assertTrue(
                traceSampleKeys(name).none { it in REGISTERED_SERVER_FEATURE_FLAG_KEYS },
                "trace_sample keys must not be in product flag batch for $name",
            )
        }
        assertEquals(1.0, TRACE_SAMPLE_ERROR_DEFAULT_RATE)
        assertEquals(0.1, defaultWebViewForegroundTraceSampleRate(buildType = "release", debugBuild = false))
        assertEquals(1.0, defaultWebViewForegroundTraceSampleRate(buildType = "alpha", debugBuild = false))
        assertEquals(1.0, defaultWebViewForegroundTraceSampleRate(buildType = "release", debugBuild = true))
        assertEquals(0.02, defaultWebViewPrewarmTraceSampleRate(buildType = "release", debugBuild = false))
        assertEquals(1.0, defaultWebViewPrewarmTraceSampleRate(buildType = "alpha", debugBuild = false))
        assertEquals(1.0, defaultWebViewPrewarmTraceSampleRate(buildType = "release", debugBuild = true))

        val snapshot =
            ServerFeatureFlagSnapshot(
                serverId = ServerId("s1"),
                resolved = true,
                values = mapOf(
                    "trace_sample.mobile" to true,
                    "trace_sample.mobile.http_error" to false,
                    "trace_sample.mobile.web_container" to true,
                ),
                reasons = mapOf(
                    "trace_sample.mobile" to "platform_rule",
                    "trace_sample.mobile.http_error" to "kill_switch",
                    "trace_sample.mobile.web_container" to "platform_rule",
                ),
                variants = mapOf(
                    "trace_sample.mobile" to "0.5",
                    "trace_sample.mobile.web_container" to "0.25",
                ),
            )
        assertEquals(0.0, resolveActiveServerTraceSampleRate("s1", MOBILE_HTTP_ERROR_TRACE_NAME, 1.0, flags = snapshot))
        assertEquals(0.5, resolveActiveServerTraceSampleRate("s1", MOBILE_UI_ERROR_TRACE_NAME, 1.0, flags = snapshot))
        assertEquals(0.25, resolveActiveServerTraceSampleRate("s1", MOBILE_WEB_CONTAINER_TRACE_NAME, 1.0, flags = snapshot))
    }
}
