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
    fun `long running test emits heartbeats naming the test`() {
        val rec = Recording()
        val descriptor = FakeDescriptor("build.raft.app.SomeTest", "slowButFine")
        val finishAt = 95L
        val l = listener(rec, timeoutMs = 200, heartbeatMs = 30) { requested, advance, listenerRef ->
            if (rec.now + requested >= finishAt) {
                advance(finishAt - rec.now)
                listenerRef.afterTest(descriptor, FakeResult())
            } else {
                advance(requested)
            }
        }

        l.beforeTest(descriptor)

        assertTrue(rec.exitCodes.isEmpty())
        val heartbeats = rec.lines.filter { it.contains("still running") }
        assertTrue("expected at least two heartbeats, got $heartbeats", heartbeats.size >= 2)
        assertTrue(heartbeats.all { it.contains("build.raft.app.SomeTest.slowButFine") })
        assertFalse(rec.lines.any { it.contains("HANG DETECTED") })
    }

    @Test
    fun `disabled listener never starts a watchdog`() {
        val rec = Recording()
        val l = listener(rec, timeoutMs = 100, heartbeatMs = 30, enabled = false) { requested, advance, _ ->
            advance(requested)
        }

        l.beforeTest(FakeDescriptor("build.raft.app.SomeTest", "anything"))

        assertEquals(0, rec.spawnerCalls)
        assertTrue(rec.lines.isEmpty())
        assertTrue(rec.exitCodes.isEmpty())
    }

    @Test
    fun `aggregate descriptors without a class name are not watched`() {
        val rec = Recording()
        val aggregate = object : TestDescriptor {
            override fun getName(): String = "Gradle Test Run"
            override fun getDisplayName(): String = "Gradle Test Run"
            override fun getClassName(): String? = null
            override fun getParent(): TestDescriptor? = null
            override fun isComposite(): Boolean = true
        }
        val l = listener(rec, timeoutMs = 100, heartbeatMs = 30) { requested, advance, _ ->
            advance(requested)
        }

        l.beforeTest(aggregate)

        assertEquals(0, rec.spawnerCalls)
    }
}
