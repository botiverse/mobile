package build.raft.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * task #312: the coroutine keep-alive property must be pinned early enough that the
 * kotlinx.coroutines DefaultScheduler never enters tryTerminateWorker, making the
 * setName-inside-synchronized(workers) SuspendThreadByPeer deadlock path unreachable.
 *
 * `IDLE_WORKER_KEEP_ALIVE_NS` is read once at `TasksKt` class init, so proving the
 * scheduler consumed the pin requires a JVM where the property is set before any coroutine
 * use. That ordering can't be guaranteed inside the shared test JVM (any earlier test that
 * touches Dispatchers latches the field at the 60s default), so the consume-check runs in a
 * spawned child JVM that controls its own init order — independent of forkEvery ordering.
 */
class RaftCoroutineWorkerKeepAliveTest {
    @After
    fun restoreProperty() {
        System.clearProperty(COROUTINE_WORKER_KEEP_ALIVE_PROPERTY)
    }

    @Test
    fun keepAlivePropertyIsPinnedBeforeCoroutinesInitialize() {
        installCoroutineWorkerKeepAlive()
        assertEquals(
            COROUTINE_WORKER_KEEP_ALIVE_SEC,
            System.getProperty(COROUTINE_WORKER_KEEP_ALIVE_PROPERTY)
        )
    }

    @Test
    fun schedulerConsumesThePinnedKeepAliveInAFreshJvm() {
        val javaBin =
            File(System.getProperty("java.home"), "bin/java").absolutePath
        val classPath = System.getProperty("java.class.path")
        val process =
            ProcessBuilder(
                    javaBin,
                    "-cp",
                    classPath,
                    "build.raft.app.RaftCoroutineWorkerKeepAliveProbe"
                )
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        assertEquals("probe exited=$exit output=$output", 0, exit)
        assertEquals(
            TimeUnit.SECONDS.toNanos(COROUTINE_WORKER_KEEP_ALIVE_SEC.toLong()).toString(),
            output.trim()
        )
    }
}

/**
 * Spawned in a fresh JVM by [RaftCoroutineWorkerKeepAliveTest]. Sets the pin before any
 * coroutine class loads, then prints the keep-alive the scheduler actually consumed.
 */
internal object RaftCoroutineWorkerKeepAliveProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        installCoroutineWorkerKeepAlive()
        val tasksClass = Class.forName("kotlinx.coroutines.scheduling.TasksKt")
        val field = tasksClass.getDeclaredField("IDLE_WORKER_KEEP_ALIVE_NS")
        field.isAccessible = true
        print(field.getLong(null))
    }
}
