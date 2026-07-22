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

        assertTrue(activity.contains("QaSessionBootstrapInbox.consume"))
        assertFalse(activity.contains("getStringExtra(\"ticket\")"))
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
