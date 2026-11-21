package build.raft.app

import android.app.Application
import android.os.StrictMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RaftStrictModeTest {
    private lateinit var originalPolicy: StrictMode.ThreadPolicy

    @Before
    fun savePolicy() {
        originalPolicy = StrictMode.getThreadPolicy()
        RaftStrictMode.resetForTests()
    }

    @After
    fun restorePolicy() {
        RaftStrictMode.resetForTests()
        StrictMode.setThreadPolicy(originalPolicy)
    }

    @Test
    fun installIfDebugRequiresBothBuildConfigDebugAndAppDebuggable() {
        var installs = 0
        assertFalse(RaftStrictMode.installIfDebug(buildConfigDebug = false, appDebuggable = false) { installs += 1 })
        assertFalse(RaftStrictMode.installIfDebug(buildConfigDebug = true, appDebuggable = false) { installs += 1 })
        assertFalse(RaftStrictMode.installIfDebug(buildConfigDebug = false, appDebuggable = true) { installs += 1 })
        assertEquals(0, installs)
        assertFalse(RaftStrictMode.installed)

        assertTrue(RaftStrictMode.installIfDebug(buildConfigDebug = true, appDebuggable = true) { installs += 1 })
        assertEquals(1, installs)
        assertTrue(RaftStrictMode.installed)
    }

    @Test
    fun threadPolicyDetectsDiskAndCustomSlowCallsWithLogOnlyNeverDeath() {
        val actual = RaftStrictMode.buildThreadPolicy()
        val expected =
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build()
        val withDeath =
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectCustomSlowCalls()
                .penaltyLog()
                .penaltyDeath()
                .build()

        assertEquals(expected.toString(), actual.toString())
        assertNotEquals("debug StrictMode must never install penaltyDeath", withDeath.toString(), actual.toString())
    }

    @Test
    fun allowDiskReadsAndWritesTemporarilyRelaxPolicyAndRestoreEvenOnThrow() {
        assertTrue(RaftStrictMode.installIfDebug(buildConfigDebug = true, appDebuggable = true))
        val strictPolicy = StrictMode.getThreadPolicy().toString()

        var insideReadPolicy = ""
        val readValue =
            RaftStrictMode.allowDiskReads {
                insideReadPolicy = StrictMode.getThreadPolicy().toString()
                "ok"
            }
        assertEquals("ok", readValue)
        assertNotEquals("disk reads should be permitted inside allowDiskReads", strictPolicy, insideReadPolicy)
        assertEquals("prior thread policy must be restored after allowDiskReads", strictPolicy, StrictMode.getThreadPolicy().toString())

        var insideWritePolicy = ""
        var caught: IllegalStateException? = null
        try {
            RaftStrictMode.allowDiskWrites {
                insideWritePolicy = StrictMode.getThreadPolicy().toString()
                throw IllegalStateException("expected")
            }
        } catch (error: IllegalStateException) {
            caught = error
        }
        assertEquals("expected", caught?.message)
        assertNotEquals("disk writes should be permitted inside allowDiskWrites", strictPolicy, insideWritePolicy)
        assertEquals("prior thread policy must be restored in finally", strictPolicy, StrictMode.getThreadPolicy().toString())
    }

    @Test
    fun allowDiskHelpersArePassThroughWhenNotInstalled() {
        val sentinel = StrictMode.ThreadPolicy.Builder().detectDiskReads().penaltyLog().build()
        StrictMode.setThreadPolicy(sentinel)
        val sentinelText = StrictMode.getThreadPolicy().toString()

        var readSeen = ""
        var writeSeen = ""
        assertEquals(1, RaftStrictMode.allowDiskReads { readSeen = StrictMode.getThreadPolicy().toString(); 1 })
        assertEquals(2, RaftStrictMode.allowDiskWrites { writeSeen = StrictMode.getThreadPolicy().toString(); 2 })

        assertEquals(sentinelText, readSeen)
        assertEquals(sentinelText, writeSeen)
        assertEquals(sentinelText, StrictMode.getThreadPolicy().toString())
    }
}
