package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotFeedbackPromptPolicyTest {
    @Test
    fun requiresBuildRuntimeAndAndroid14Gates() {
        assertTrue(
            shouldEnableScreenshotFeedback(
                buildEnabled = true,
                runtimeEnabled = true,
                sdkInt = 34,
            )
        )
        assertFalse(shouldEnableScreenshotFeedback(false, true, 34))
        assertFalse(shouldEnableScreenshotFeedback(true, false, 34))
        assertFalse(shouldEnableScreenshotFeedback(true, true, 33))
    }

    @Test
    fun suppressesDuplicateCallbacksInsideMinimumInterval() {
        val gate = ScreenshotFeedbackEventGate(minimumIntervalMillis = 1_500L)

        assertTrue(gate.accept(nowMillis = 10_000L))
        assertFalse(gate.accept(nowMillis = 11_499L))
        assertTrue(gate.accept(nowMillis = 11_500L))
    }

    @Test
    fun rejectsStoppedAndPreviousStartCallbacks() {
        val gate = ScreenshotFeedbackListeningGate()
        val firstGeneration = gate.start()

        assertTrue(gate.accepts(firstGeneration))
        gate.stop()
        assertFalse(gate.accepts(firstGeneration))

        val secondGeneration = gate.start()
        assertFalse(gate.accepts(firstGeneration))
        assertTrue(gate.accepts(secondGeneration))
    }

    @Test
    fun screenCaptureRegistrationFailsClosedAndAllowsRetry() {
        var registerAttempts = 0
        var unregisterAttempts = 0
        val registration =
            FailClosedScreenCaptureRegistration<String>(
                register = {
                    registerAttempts += 1
                    if (registerAttempts == 1) throw SecurityException("permission denied")
                },
                unregister = { unregisterAttempts += 1 },
            )

        val failedStart = registration.start("first")
        assertTrue(failedStart.isFailure)
        assertTrue(failedStart.exceptionOrNull() is SecurityException)
        assertEquals(1, registerAttempts)

        assertFalse(registration.stop().getOrThrow())
        assertEquals(0, unregisterAttempts)

        assertTrue(registration.start("retry").getOrThrow())
        assertEquals(2, registerAttempts)
        assertTrue(registration.stop().getOrThrow())
        assertEquals(1, unregisterAttempts)
    }

    @Test
    fun deferredRegistrationWaitsUntilAfterFirstFrameAndUnregistersOnStop() {
        val registered = mutableListOf<String>()
        val unregistered = mutableListOf<String>()
        var pendingTask: (() -> Unit)? = null
        val deferred =
            DeferredScreenCaptureRegistration(
                registration =
                    FailClosedScreenCaptureRegistration<String>(
                        register = { registered += it },
                        unregister = { unregistered += it },
                    ),
                schedule = { task ->
                    pendingTask = task
                    val cancel: () -> Unit = { pendingTask = null }
                    cancel
                },
            )

        deferred.start("first")
        assertEquals(emptyList<String>(), registered)

        requireNotNull(pendingTask).invoke()
        assertEquals(listOf("first"), registered)
        assertEquals(emptyList<String>(), unregistered)

        deferred.stop()
        assertEquals(listOf("first"), unregistered)
    }

    @Test
    fun deferredRegistrationCancelledOnStopBeforeFirstFrameNeverRegistersOrUnregisters() {
        var registerAttempts = 0
        var unregisterAttempts = 0
        var cancelled = false
        var scheduledTask: (() -> Unit)? = null
        val deferred =
            DeferredScreenCaptureRegistration(
                registration =
                    FailClosedScreenCaptureRegistration<String>(
                        register = { registerAttempts += 1 },
                        unregister = { unregisterAttempts += 1 },
                    ),
                schedule = { task ->
                    scheduledTask = task
                    val cancel: () -> Unit = { cancelled = true }
                    cancel
                },
            )

        deferred.start("first")
        deferred.stop()
        assertTrue(cancelled)
        assertEquals(0, registerAttempts)
        assertEquals(0, unregisterAttempts)

        // Even if a late already-posted callback fires after stop, it must not register.
        requireNotNull(scheduledTask).invoke()
        assertEquals(0, registerAttempts)
        assertEquals(0, unregisterAttempts)
    }

    @Test
    fun deferredRegistrationRestartSupersedesStaleCallbackAndRespectsCanRegister() {
        val registered = mutableListOf<String>()
        val scheduled = mutableListOf<() -> Unit>()
        var allowRegister = true
        val deferred =
            DeferredScreenCaptureRegistration(
                registration =
                    FailClosedScreenCaptureRegistration<String>(
                        register = { registered += it },
                        unregister = {},
                    ),
                schedule = { task ->
                    scheduled += task
                    val cancel: () -> Unit = {}
                    cancel
                },
                canRegister = { allowRegister },
            )

        deferred.start("first")
        deferred.stop()
        deferred.start("second")

        // Stale first callback is ignored.
        scheduled[0].invoke()
        assertEquals(emptyList<String>(), registered)

        // Active second callback registers.
        scheduled[1].invoke()
        assertEquals(listOf("second"), registered)

        // If activity is finishing before callback fires, registration is skipped.
        deferred.stop()
        allowRegister = false
        deferred.start("finishing")
        scheduled[2].invoke()
        assertEquals(listOf("second"), registered)
    }
}
