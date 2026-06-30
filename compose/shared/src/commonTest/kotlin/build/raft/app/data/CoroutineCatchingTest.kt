package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class CoroutineCatchingTest {
    @Test
    fun preservesCancellationException() {
        runBlocking {
            assertFailsWith<CancellationException> {
                runCatchingPreservingCancellation<String> {
                    throw CancellationException("route changed")
                }
            }
        }
    }

    @Test
    fun capturesNonCancellationFailure() {
        runBlocking {
            val result =
                runCatchingPreservingCancellation<String> {
                    error("network failed")
                }

            assertTrue(result.isFailure)
        }
    }

    @Test
    fun returnsSuccessValue() {
        runBlocking {
            val result = runCatchingPreservingCancellation { "ok" }

            assertEquals("ok", result.getOrNull())
        }
    }
}
