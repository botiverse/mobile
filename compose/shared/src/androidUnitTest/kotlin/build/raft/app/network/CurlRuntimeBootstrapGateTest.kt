package build.raft.app.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * task #252 review P1 locks:
 * 1. a failed curl runtime bootstrap is typed and fail-closed — the executor
 *    returns [CURL_RUNTIME_BOOTSTRAP_FAILED_DETAIL] without ever building or
 *    sending a request, and every subsequent request retries the gate;
 * 2. the install gate is single-flight — a cold-start burst serializes into
 *    exactly one install attempt, and a failed install stays retryable.
 */
class CurlRuntimeBootstrapGateTest {

    @Test
    fun executorFailsClosedAndTypedWithoutTouchingTheTransport() = runBlocking {
        val gateCalls = AtomicInteger(0)
        val executor =
            NetworkKmmSlockHttpExecutor(
                warningLogger = {},
                curlRuntimeGate = {
                    gateCalls.incrementAndGet()
                    false
                }
            )

        val first =
            executor.execute(
                priority = SlockRequestPriority.Foreground,
                // Unroutable by construction: a request that escaped the gate
                // would surface as a connect error, not our typed detail.
                url = "https://curl-bootstrap.invalid/api/first",
                method = "GET",
                headers = emptyMap(),
                bodyText = null,
                jsonBody = false
            )
        val second =
            executor.executeMultipart(
                priority = SlockRequestPriority.Foreground,
                url = "https://curl-bootstrap.invalid/api/second",
                fields = emptyMap(),
                files = emptyList(),
                headers = emptyMap()
            )

        assertEquals(CURL_RUNTIME_BOOTSTRAP_FAILED_DETAIL, first.errorBodyText)
        assertEquals(CURL_RUNTIME_BOOTSTRAP_FAILED_DETAIL, second.errorBodyText)
        assertEquals(0, first.code)
        assertEquals(0, second.code)
        // Both entry points consulted the gate again — the failure is retryable.
        assertEquals(2, gateCalls.get())
    }

    @Test
    fun coldStartBurstSerializesIntoASingleInstall() {
        val installed = AtomicBoolean(false)
        val gate = CurlRuntimeInstallGate(isInstalled = { installed.get() })
        val installCalls = AtomicInteger(0)
        val installStarted = CountDownLatch(1)
        val releaseInstall = CountDownLatch(1)
        val results = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()

        val burst =
            (1..8).map {
                thread(name = "curl-gate-burst-$it", isDaemon = true) {
                    results +=
                        gate.ensure {
                            installCalls.incrementAndGet()
                            installStarted.countDown()
                            releaseInstall.await()
                            installed.set(true)
                            true
                        }
                }
            }

        assertTrue(installStarted.await(5, TimeUnit.SECONDS))
        releaseInstall.countDown()
        burst.forEach { it.join(5_000) }

        assertEquals(1, installCalls.get())
        assertEquals(8, results.size)
        assertTrue(results.all { it })
        // Once installed, the gate short-circuits without another install.
        assertTrue(gate.ensure { error("must not reinstall") })
    }

    @Test
    fun failedInstallStaysRetryable() {
        val installed = AtomicBoolean(false)
        val gate = CurlRuntimeInstallGate(isInstalled = { installed.get() })

        assertFalse(gate.ensure { false })
        // Next request retries the install; success flips the gate closed.
        assertTrue(
            gate.ensure {
                installed.set(true)
                true
            }
        )
        assertTrue(gate.ensure { error("must not reinstall") })
    }
}
