package build.raft.app.core.data.auth

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QaSessionBootstrapContractTest {
    @Test
    fun releaseDefaultHasNoEnabledBootstrapSurface() {
        val manifest = projectFile("androidApp/src/main/AndroidManifest.xml").readText()
        val defaults = projectFile("androidApp/src/main/res/values/booleans.xml").readText()
        val alpha = projectFile("androidApp/src/alpha/res/values/booleans.xml").readText()
        val connected = projectFile("androidApp/src/connectedTest/res/values/booleans.xml").readText()
        val gradle = projectFile("androidApp/build.gradle.kts").readText()

        assertTrue(defaults.contains("<bool name=\"qa_session_bootstrap_enabled\">false</bool>"))
        assertTrue(alpha.contains("<bool name=\"qa_session_bootstrap_enabled\">true</bool>"))
        assertTrue(connected.contains("<bool name=\"qa_session_bootstrap_enabled\">true</bool>"))
        assertTrue(gradle.contains("buildConfigField(\"Boolean\", \"QA_SESSION_BOOTSTRAP_ENABLED\", \"false\")"))
        assertTrue(manifest.contains("android:name=\".core.data.auth.QaSessionBootstrapActivity\""))
        assertTrue(manifest.contains("android:enabled=\"@bool/qa_session_bootstrap_enabled\""))
        assertFalse(manifest.contains("raft-dev://qa/bootstrap"))
    }

    @Test
    fun privateTicketAndCaptureRequestsNeverTravelInIntentOrShellArgv() {
        val activity = projectFile("androidApp/src/main/java/build/raft/app/core/data/auth/QaSessionBootstrapActivity.kt").readText()
        val capture = projectFile("../../scripts/qa/acceptance_agent.mjs").readText()
        val workflow = projectFile("../../.github/workflows/qa-acceptance.yml").readText()
        val adapter = projectFile("../../scripts/qa/mobile_login_bootstrap.mjs").readText()

        assertTrue(activity.contains("QaSessionBootstrapInbox.consume"))
        assertTrue(activity.contains("consumeQaLoginBootstrap"))
        assertTrue(activity.contains("\"session\""))
        assertTrue(activity.contains("EXTRA_ENVELOPE_DIGEST"))
        assertFalse(activity.contains("getStringExtra(\"ticket\")"))
        assertFalse(activity.contains("getStringExtra(\"accessToken\")"))
        assertFalse(activity.contains("getStringExtra(\"refreshToken\")"))
        assertFalse(workflow.contains("QA_TEST_EMAIL: \${{ secrets.QA_TEST_EMAIL }}"))
        assertFalse(workflow.contains("QA_TEST_PASSWORD: \${{ secrets.QA_TEST_PASSWORD }}"))
        assertTrue(workflow.contains("steps.stamp-claim.outputs.bootstrap_required != 'true'"))
        assertTrue(adapter.contains("[\"exec-out\", \"run-as\", applicationId"))
        assertTrue(adapter.contains("{ input: bytes }"))
        assertFalse(adapter.contains("console.log(envelope"))
        assertFalse(adapter.contains("console.log(response"))
        assertTrue(capture.contains("input: request"))
        assertTrue(capture.contains("qaCaptureRequestDigest"))
        assertTrue(capture.contains("producerCommandGeneration"))
        assertTrue(capture.contains("imageSha256"))
        assertFalse(capture.contains("--es\", \"captureWindowNonce"))
    }

    private fun projectFile(relative: String): File {
        val roots = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.take(8)
        return roots.map { File(it, relative) }.firstOrNull(File::isFile)
            ?: error("Could not locate $relative from ${System.getProperty("user.dir")}")
    }
}
