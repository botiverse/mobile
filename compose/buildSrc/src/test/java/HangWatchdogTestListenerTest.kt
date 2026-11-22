package build.raft.ci

import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task #32 teeth: HangWatchdogTestListener behavior under a virtual clock.
 * The spawner override drives the watchdog loop synchronously on the test
 * thread; the fake exiter records instead of killing the JVM.
 */
class HangWatchdogTestListenerTest {

    private class FakeDescriptor(
        private val cls: String,
        private val testName: String,
    ) : TestDescriptor {
        override fun getName(): String = testName
        override fun getDisplayName(): String = testName
        override fun getClassName(): String = cls
        override fun getParent(): TestDescriptor? = null
        override fun isComposite(): Boolean = false
    }

    private class FakeResult : TestResult {
        override fun getResultType(): TestResult.ResultType = TestResult.ResultType.SUCCESS
        override fun getSuccessfulTestCount(): Long = 1
        override fun getFailedTestCount(): Long = 0
        override fun getSkippedTestCount(): Long = 0
        override fun getTestCount(): Long = 1
        @Suppress("DEPRECATION")
        override fun getException(): Throwable? = null
        override fun getExceptions(): List<Throwable> = emptyList()
        override fun getFailures(): List<org.gradle.api.tasks.testing.TestFailure> = emptyList()
        override fun getAssumptionFailure(): org.gradle.api.tasks.testing.TestFailure? = null
        override fun getEndTime(): Long = 0
        override fun getStartTime(): Long = 0
    }

    private class Recording {
        var now: Long = 0L
        val exitCodes = mutableListOf<Int>()
        val lines = mutableListOf<String>()
        var spawnerCalls = 0
    }

    private fun listener(
        rec: Recording,
        timeoutMs: Long,
        heartbeatMs: Long,
        enabled: Boolean = true,
        sleeperBehavior: (requested: Long, advance: (Long) -> Unit, listener: HangWatchdogTestListener) -> Unit,
    ): HangWatchdogTestListener {
        lateinit var l: HangWatchdogTestListener
        l = HangWatchdogTestListener(
            timeoutMs = timeoutMs,
            heartbeatMs = heartbeatMs,
            enabled = enabled,
            clock = { rec.now },
            sleeper = { requested -> sleeperBehavior(requested, { ms -> rec.now += ms }, l) },
            exiter = { code -> rec.exitCodes.add(code) },
            out = { line -> rec.lines.add(line) },
            spawner = { body -> rec.spawnerCalls++; body.run() },
        )
        return l
    }

    @Test
    fun `hung test is killed with a named report and exit code 73`() {
        val rec = Recording()
        val l = listener(rec, timeoutMs = 100, heartbeatMs = 1_000) { requested, advance, _ ->
            advance(requested)
        }

        l.beforeTest(FakeDescriptor("build.raft.app.SomeTest", "hangsForever"))

        assertEquals(listOf(HangWatchdogTestListener.EXIT_CODE_HANG), rec.exitCodes)
        val hangLines = rec.lines.filter { it.contains("HANG DETECTED") }
        assertEquals(1, hangLines.size)
        assertTrue(hangLines[0].contains("build.raft.app.SomeTest.hangsForever"))
        assertTrue(hangLines[0].contains("exit code 73"))
        assertTrue(hangLines[0].contains("terminating the build JVM"))
        assertTrue(rec.lines.any { it.contains("thread \"") })
        assertTrue(rec.lines.any { it.contains("at ") })
    }

    @Test
    fun `test finishing before timeout does not trigger the watchdog`() {
        val rec = Recording()
        val descriptor = FakeDescriptor("build.raft.app.SomeTest", "quick")
        var sleeps = 0
        val l = listener(rec, timeoutMs = 100, heartbeatMs = 500) { requested, advance, listenerRef ->
            sleeps++
            advance(minOf(requested, 30L))
            if (sleeps >= 2) {
                listenerRef.afterTest(descriptor, FakeResult())
            }
        }

        l.beforeTest(descriptor)

        assertTrue(rec.exitCodes.isEmpty())
        assertFalse(rec.lines.any { it.contains("HANG DETECTED") })
    }

    @Test
    fun `parallel forks - a later test starting or finishing does not unwatch a hung earlier test`() {
        val rec = Recording()
        val bodies = mutableListOf<Runnable>()
        val hung = FakeDescriptor("build.raft.app.ForkOneTest", "hangsForever")
        val quick = FakeDescriptor("build.raft.app.ForkTwoTest", "quick")
        val l = HangWatchdogTestListener(
            timeoutMs = 100,
            heartbeatMs = 1_000,
            clock = { rec.now },
            sleeper = { requested -> rec.now += requested },
            exiter = { code -> rec.exitCodes.add(code) },
            out = { line -> rec.lines.add(line) },
            spawner = { body -> bodies.add(body) },
        )

        // Interleaved events, as two forks deliver them.
        l.beforeTest(hung)
        l.beforeTest(quick)
        l.afterTest(quick, FakeResult())
        bodies.forEach { it.run() }

        assertEquals(listOf(HangWatchdogTestListener.EXIT_CODE_HANG), rec.exitCodes)
        val hangLines = rec.lines.filter { it.contains("HANG DETECTED") }
        assertEquals(1, hangLines.size)
        assertTrue(hangLines[0].contains("build.raft.app.ForkOneTest.hangsForever"))
    }
}
