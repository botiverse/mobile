package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentDownloadCoordinatorTest {
    @Test
    fun knownLengthStreamsThroughSinkAndPublishesOnlyAfterAtomicCommit() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val observer = RecordingObserver()
        var now = 1_000L
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { now })

        val token = engine.start(request(), observer)
        val call = transport.calls.single()
        call.start(statusCode = 200, contentLength = 10L)
        assertNull(observer.progress.single().percent, "response wait must not invent 0%")
        now += 100L
        call.chunk(byteArrayOf(1, 2, 3, 4))
        now += 300L
        call.chunk(byteArrayOf(5, 6, 7, 8, 9, 10))
        call.complete(successResponse())

        val sink = sinks.created.single()
        assertEquals(token, sink.attemptToken)
        assertEquals(10, sink.bytes.size)
        assertEquals(1, sink.commitCount)
        assertEquals(0, sink.abortCount)
        assertEquals(listOf("response_wait", "receiving", "receiving", "complete"), observer.progress.map { it.phase })
        // Every byte staged is 99%: 100 belongs to the host's completion, after the atomic commit.
        assertEquals(listOf(null, 40, 99, 99), observer.progress.map { it.percent })
        assertEquals("complete", observer.results.single().status)
    }

    @Test
    fun unknownLengthNeverInventsPercentButStillCompletes() {
        val transport = FakeTransport()
        val observer = RecordingObserver()
        val engine = AttachmentDownloadCoordinatorEngine(transport, FakeSinkFactory(), nowMillis = { 1_000L })

        engine.start(request(), observer)
        transport.calls.single().apply {
            start(statusCode = 200, contentLength = null)
            chunk(byteArrayOf(1, 2, 3))
            complete(successResponse())
        }

        assertTrue(observer.progress.all { it.percent == null })
        assertEquals(3L, observer.results.single().downloadedBytes)
    }

    @Test
    fun receivingSamplesComeEveryWholePercentOrEveryHundredMillis() {
        data class Case(val contentLength: Long?, val chunkMillis: Long, val expectedPercents: List<Int?>)
        // 20 chunks of 5 bytes: 0.5% each of a known 1000-byte body, 10 ms apart, so each whole
        // percent is a sample; with no length, 30 ms apart, so the 100 ms floor is the cadence.
        val cases =
            listOf(
                // 5 bytes rounds up to 1%; then 20, 30, ... 100 bytes are 2% .. 10%.
                Case(1_000L, 10L, (1..10).toList()),
                // Samples at 30, 150, 270, 390 and 510 ms.
                Case(null, 30L, List(5) { null })
            )
        for (case in cases) {
            val transport = FakeTransport()
            val observer = RecordingObserver()
            var now = 1_000L
            val engine = AttachmentDownloadCoordinatorEngine(transport, FakeSinkFactory(), nowMillis = { now })
            engine.start(request(), observer)
            val call = transport.calls.single()
            call.start(statusCode = 200, contentLength = case.contentLength)
            repeat(20) {
                now += case.chunkMillis
                call.chunk(ByteArray(5))
            }
            val receiving = observer.progress.filter { it.phase == "receiving" }
            assertEquals(case.expectedPercents, receiving.map { it.percent }, "case=$case")
            val gaps = receiving.zipWithNext { a, b -> b.downloadedBytes - a.downloadedBytes }
            assertTrue(gaps.all { it > 0L }, "samples must advance, case=$case gaps=$gaps")
        }
    }

    @Test
    fun sameIdReplacementAbortsOldAttemptAndRejectsAllLateCallbacks() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val firstObserver = RecordingObserver()
        val secondObserver = RecordingObserver()
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })

        val firstToken = engine.start(request(), firstObserver)
        val first = transport.calls.single()
        val secondToken = engine.start(request(), secondObserver)
        val second = transport.calls.last()

        assertTrue(first.cancelled)
        assertEquals("cancelled", firstObserver.results.single().status)
        assertEquals(1, sinks.created.first().abortCount)
        first.start(200, 4L)
        first.chunk(byteArrayOf(1, 2, 3, 4))
        first.complete(successResponse())
        assertTrue(firstObserver.progress.isEmpty(), "late old-attempt callbacks must be fenced")

        second.start(200, 2L)
        second.chunk(byteArrayOf(8, 9))
        second.complete(successResponse())
        assertEquals("complete", secondObserver.results.single().status)
        assertEquals(1, sinks.created.last().commitCount)
        assertFalse(firstToken == secondToken)
    }

    @Test
    fun cancelReturnsAfterAbortAndLateTransportCallbacksStaySilent() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val observer = RecordingObserver()
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })

        engine.start(request(), observer)
        val call = transport.calls.single()
        call.start(200, 4L)
        call.chunk(byteArrayOf(1, 2))
        assertEquals("cancelled", engine.control("download-1", null, "cancel"))
        assertTrue(call.cancelled)
        assertEquals(1, sinks.created.single().abortCount)
        val progressCount = observer.progress.size

        call.chunk(byteArrayOf(3, 4))
        call.complete(successResponse())
        assertEquals(progressCount, observer.progress.size)
        assertEquals(listOf("cancelled"), observer.results.map { it.status })
        assertEquals(0, sinks.created.single().commitCount)
    }

    @Test
    fun progressCallbackFailureCancelsTransportAndCleansStagingExactlyOnce() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val observer = RecordingObserver(throwOnProgress = true)
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })

        engine.start(request(), observer)
        val call = transport.calls.single()
        call.start(200, 5L)

        assertTrue(call.cancelled)
        assertEquals(1, sinks.created.single().abortCount)
        assertEquals("failed", observer.results.single().status)
        assertEquals("progress_callback", observer.results.single().errorKind)
        call.complete(successResponse())
        assertEquals(1, observer.results.size)
    }

    @Test
    fun sinkCommitFailureNeverPublishesSuccessAndAlwaysCleansStaging() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory(commitSucceeds = false)
        val observer = RecordingObserver()
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })

        engine.start(request(), observer)
        transport.calls.single().apply {
            start(200, 1L)
            chunk(byteArrayOf(1))
            complete(successResponse())
        }

        assertEquals("failed", observer.results.single().status)
        assertEquals("storage_publish", observer.results.single().errorKind)
        assertEquals(1, sinks.created.single().commitCount)
        assertEquals(1, sinks.created.single().abortCount)
    }

    @Test
    fun coordinatorPassesWholeAndPhaseTimeoutPolicyToNetworkOwner() {
        val transport = FakeTransport()
        val engine = AttachmentDownloadCoordinatorEngine(transport, FakeSinkFactory(), nowMillis = { 1_000L })
        val request =
            request().copy(
                connectTimeoutMillis = 1_111L,
                responseHeadersTimeoutMillis = 2_222L,
                interChunkIdleTimeoutMillis = 3_333L,
                wholeTransferTimeoutMillis = 4_444L
            )

        engine.start(request, RecordingObserver())

        assertEquals(request, transport.requests.single())
    }

    @Test
    fun idleOrWholeTimeoutFailsOnceAndAbortsStaging() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val observer = RecordingObserver()
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })

        engine.start(request(), observer)
        val call = transport.calls.single()
        call.start(200, 100L)
        call.chunk(byteArrayOf(1, 2, 3))
        call.complete(
            AttachmentDownloadCompletion(statusCode = null, headers = emptyMap(), errorKind = "timeout", errorMessage = "inter-chunk idle timeout")
        )

        assertEquals(1, observer.results.size)
        assertEquals("failed", observer.results.single().status)
        assertEquals("timeout", observer.results.single().errorKind)
        assertEquals(1, sinks.created.single().abortCount)
        call.complete(successResponse())
        assertEquals(1, observer.results.size)
    }

    @Test
    fun knownLengthMustMatchExactlyIncludingDeclaredZero() {
        listOf(5L to 4, 5L to 6).forEach { (declared, actual) ->
            val transport = FakeTransport()
            val sinks = FakeSinkFactory()
            val observer = RecordingObserver()
            val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })
            engine.start(request(), observer)
            transport.calls.single().apply {
                start(200, declared)
                chunk(ByteArray(actual))
                complete(successResponse())
            }
            assertEquals("failed", observer.results.single().status)
            assertEquals("content_length_mismatch", observer.results.single().errorKind)
            assertEquals(0, sinks.created.single().commitCount)
            assertEquals(1, sinks.created.single().abortCount)
        }

        val zeroTransport = FakeTransport()
        val zeroObserver = RecordingObserver()
        val zeroSinks = FakeSinkFactory()
        val zeroEngine = AttachmentDownloadCoordinatorEngine(zeroTransport, zeroSinks, nowMillis = { 1_000L })
        zeroEngine.start(request(), zeroObserver)
        zeroTransport.calls.single().apply {
            start(200, 0L)
            complete(successResponse())
        }
        assertEquals("complete", zeroObserver.results.single().status)
        assertEquals(1, zeroSinks.created.single().commitCount)
        assertEquals("complete", zeroObserver.progress.last().phase)
        assertNull(zeroObserver.progress.last().percent, "an empty body has no percent before the host completes it")
    }

    @Test
    fun successWithoutResponseStartFailsClosed() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val observer = RecordingObserver()
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })

        engine.start(request(), observer)
        transport.calls.single().complete(successResponse())

        assertEquals("failed", observer.results.single().status)
        assertEquals("missing_response_start", observer.results.single().errorKind)
        assertEquals(0, sinks.created.single().commitCount)
        assertEquals(1, sinks.created.single().abortCount)
    }

    @Test
    fun byteArithmeticSaturatesAndPercentCannotOverflow() {
        assertEquals(Long.MAX_VALUE, saturatingAdd(Long.MAX_VALUE - 2L, 10L))
        val meter = build.raft.app.transfer.RaftTransferMeter(bytesTotal = Long.MAX_VALUE)
        assertEquals(99, meter.bytes(Long.MAX_VALUE - 1L).percent)
        assertEquals(99, meter.bytes(Long.MAX_VALUE).percent)
    }

    @Test
    fun finalProgressFailureWinsOverCommitAndSuccessTerminal() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val observer = RecordingObserver(throwOnPhase = "complete")
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })

        engine.start(request(), observer)
        transport.calls.single().apply {
            start(200, 1L)
            chunk(byteArrayOf(1))
            complete(successResponse())
        }

        assertEquals(listOf("failed"), observer.results.map { it.status })
        assertEquals("progress_callback", observer.results.single().errorKind)
        assertEquals(0, sinks.created.single().commitCount)
        assertEquals(1, sinks.created.single().abortCount)
    }

    @Test
    fun progressObserverCanReenterCancelWithoutDeadlockOrDoubleTerminal() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        lateinit var engine: AttachmentDownloadCoordinatorEngine
        val observer =
            object : AttachmentDownloadObserver {
                val results = mutableListOf<AttachmentDownloadResult>()

                override fun onProgress(progress: AttachmentDownloadProgress) {
                    if (progress.phase == "receiving") {
                        assertEquals("cancelled", engine.control("download-1", progress.attemptToken, "cancel"))
                    }
                }

                override fun onComplete(result: AttachmentDownloadResult) {
                    results += result
                }
            }
        engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })

        engine.start(request(), observer)
        transport.calls.single().apply {
            start(200, 2L)
            chunk(byteArrayOf(1))
            complete(successResponse())
        }

        assertEquals(1, observer.results.size)
        assertEquals("cancelled", observer.results.single().status)
        assertEquals(1, sinks.created.single().abortCount)
        assertEquals(0, sinks.created.single().commitCount)
    }

    @Test
    fun monotonicWatchdogCancelsNoncooperativeHeaderIdleAndWholeTimeouts() {
        fun runTimeout(
            request: AttachmentDownloadRequest,
            prepare: (FakeTransportCall) -> Unit,
            now: Long,
            expectedKind: String
        ) {
            val transport = FakeTransport()
            val sinks = FakeSinkFactory()
            val observer = RecordingObserver()
            var clock = 0L
            val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { clock })
            engine.start(request, observer)
            val call = transport.calls.single()
            prepare(call)
            clock = now
            engine.checkTimeouts(clock)
            assertTrue(call.cancelled, expectedKind)
            assertEquals(expectedKind, observer.results.single().errorKind)
            assertEquals(1, sinks.created.single().abortCount)
            call.complete(successResponse())
            assertEquals(1, observer.results.size)
        }

        runTimeout(
            request().copy(connectTimeoutMillis = 100L, responseHeadersTimeoutMillis = 200L, wholeTransferTimeoutMillis = 0L),
            prepare = {},
            now = 300L,
            expectedKind = "response_headers_timeout"
        )
        runTimeout(
            request().copy(interChunkIdleTimeoutMillis = 250L, wholeTransferTimeoutMillis = 0L),
            prepare = { it.start(200, null) },
            now = 250L,
            expectedKind = "idle_timeout"
        )
        runTimeout(
            request().copy(wholeTransferTimeoutMillis = 150L),
            prepare = {},
            now = 150L,
            expectedKind = "whole_timeout"
        )
    }

    @Test
    fun staleAttemptTokenCannotControlSameIdReplacement() {
        val transport = FakeTransport()
        val engine = AttachmentDownloadCoordinatorEngine(transport, FakeSinkFactory(), nowMillis = { 1_000L })
        val first = engine.start(request(), RecordingObserver())
        val second = engine.start(request(), RecordingObserver())

        assertEquals("stale", engine.control("download-1", first, "cancel"))
        assertEquals("cancelled", engine.control("download-1", second, "cancel"))
    }

    @Test
    fun preStartCancelTombstonePreventsSinkBeginAndTransportStart() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val observer = RecordingObserver()
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })

        assertEquals("cancelled", engine.control("download-prestart", null, "cancel"))
        engine.start(request().copy(downloadId = "download-prestart"), observer)

        assertTrue(sinks.created.isNotEmpty())
        assertEquals(0, sinks.created.single().beginCount)
        assertTrue(transport.calls.isEmpty())
        assertEquals("cancelled", observer.results.single().status)
    }

    @Test
    fun expiredPreStartCancelDoesNotKillAReusedDownloadId() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val observer = RecordingObserver()
        var clock = 0L
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { clock })

        assertEquals("cancelled", engine.control("download-prestart", null, "cancel"))
        clock = 60_000L
        engine.start(request().copy(downloadId = "download-prestart"), observer)

        assertEquals(1, sinks.created.single().beginCount)
        assertEquals(1, transport.calls.size)
        assertTrue(observer.results.isEmpty())
    }

    @Test
    fun existingOnlyCancelWithoutAttemptDoesNotPoisonSameIdSuccessor() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val observer = RecordingObserver()
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })

        assertEquals("missing", engine.controlExisting("download-prestart", null, "cancel"))
        engine.start(request().copy(downloadId = "download-prestart"), observer)

        assertEquals(1, sinks.created.single().beginCount)
        assertEquals(1, transport.calls.size)
        assertTrue(observer.results.isEmpty())
    }

    @Test
    fun existingOnlyCancelReturnsAfterSinkCleanup() {
        val transport = FakeTransport()
        var abortCompleted = false
        val sinks = FakeSinkFactory(onAbort = { abortCompleted = true })
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })
        val attemptToken = engine.start(request(), RecordingObserver())

        val status = engine.controlExisting("download-1", attemptToken, "cancel")

        assertEquals("cancelled", status)
        assertTrue(abortCompleted)
        assertEquals(1, sinks.created.single().abortCount)
    }

    @Test
    fun targetCleanupReservationRejectsNewStartsUntilHostDeletionFinishes() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })
        engine.start(request(), RecordingObserver())

        val cleanup = engine.beginTargetCleanup("attachment-1")
        assertEquals("cancelled", cleanup.status)
        val rejectedObserver = RecordingObserver()
        engine.start(request().copy(downloadId = "download-2"), rejectedObserver)
        assertEquals("cancelled", rejectedObserver.results.single().status)
        assertEquals("target_deleted", rejectedObserver.results.single().errorKind)
        assertEquals(1, sinks.created.count { it.beginCount > 0 })

        engine.finishTargetCleanup("attachment-1", cleanup.token)
        val acceptedObserver = RecordingObserver()
        engine.start(request().copy(downloadId = "download-3"), acceptedObserver)
        assertEquals(2, sinks.created.count { it.beginCount > 0 })
        assertTrue(acceptedObserver.results.isEmpty())
    }

    @Test
    fun targetCleanupReservationAlsoClosesTheMissingToStartRace() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })

        val cleanup = engine.beginTargetCleanup("attachment-1")
        assertEquals("missing", cleanup.status)
        val rejectedObserver = RecordingObserver()
        engine.start(request(), rejectedObserver)
        assertEquals("cancelled", rejectedObserver.results.single().status)
        assertEquals(0, sinks.created.count { it.beginCount > 0 })

        engine.finishTargetCleanup("attachment-1", cleanup.token)
        val acceptedObserver = RecordingObserver()
        engine.start(request().copy(downloadId = "download-2"), acceptedObserver)
        assertEquals(1, sinks.created.count { it.beginCount > 0 })
        assertTrue(acceptedObserver.results.isEmpty())
    }

    @Test
    fun directoryCleanupSnapshotsActiveTargetsAndRequiresPerTargetCleanupWhileBlockingNewStarts() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })
        val prefix = "/cache/slock-attachments/"
        val firstTarget = "${prefix}first.pdf"
        engine.start(
            request().copy(targetKey = firstTarget, targetPath = firstTarget),
            RecordingObserver()
        )

        val directory = engine.beginDirectoryCleanup(prefix)
        assertEquals(setOf(firstTarget), directory.targetKeys)
        val target = engine.beginTargetCleanup(directory.targetKeys.single())
        assertEquals("cancelled", target.status)

        val rejectedObserver = RecordingObserver()
        val secondTarget = "${prefix}second.pdf"
        engine.start(
            request().copy(downloadId = "download-2", targetKey = secondTarget, targetPath = secondTarget),
            rejectedObserver
        )
        assertEquals("cancelled", rejectedObserver.results.single().status)
        assertEquals("target_directory_deleted", rejectedObserver.results.single().errorKind)

        engine.finishTargetCleanup(firstTarget, target.token)
        engine.finishDirectoryCleanup(prefix, directory.token)
        val acceptedObserver = RecordingObserver()
        engine.start(
            request().copy(downloadId = "download-3", targetKey = secondTarget, targetPath = secondTarget),
            acceptedObserver
        )
        assertTrue(acceptedObserver.results.isEmpty())
        assertEquals(2, sinks.created.count { it.beginCount > 0 })
    }

    @Test
    fun overlappingTargetCleanupOwnersCannotReleaseEachOther() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })

        val first = engine.beginTargetCleanup("attachment-1")
        val second = engine.beginTargetCleanup("attachment-1")
        engine.finishTargetCleanup("attachment-1", second.token)
        engine.finishTargetCleanup("attachment-1", "stale-token")
        val stillRejected = RecordingObserver()
        engine.start(request(), stillRejected)
        assertEquals("cancelled", stillRejected.results.single().status)

        engine.finishTargetCleanup("attachment-1", first.token)
        val accepted = RecordingObserver()
        engine.start(request().copy(downloadId = "download-2"), accepted)
        assertTrue(accepted.results.isEmpty())
        assertEquals(1, sinks.created.count { it.beginCount > 0 })
    }

    @Test
    fun staleAttemptCleanupDoesNotCancelSameTargetSuccessor() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })
        val firstObserver = RecordingObserver()
        val first = engine.start(request(), firstObserver)
        assertEquals("cancelled", engine.control("download-1", first, "cancel"))
        val successorObserver = RecordingObserver()
        engine.start(request().copy(downloadId = "download-2"), successorObserver)

        val staleCleanup = engine.beginTargetCleanup("attachment-1", expectedAttemptToken = first)

        assertEquals("stale", staleCleanup.status)
        assertTrue(staleCleanup.token.isEmpty())
        assertTrue(successorObserver.results.isEmpty())
        assertEquals(2, sinks.created.count { it.beginCount > 0 })
    }

    @Test
    fun admittedProgressSurvivesAsyncDrainAfterFastCompletion() {
        val pendingDrains = mutableListOf<() -> Unit>()
        val transport = FakeTransport()
        val observer = RecordingObserver()
        val engine =
            AttachmentDownloadCoordinatorEngine(
                transport = transport,
                sinkFactory = FakeSinkFactory(),
                observerDispatcher = { pendingDrains += it },
                nowMillis = { 1_000L }
            )
        engine.start(request(), observer)
        transport.calls.single().apply {
            start(200, 1L)
            chunk(byteArrayOf(1))
            complete(successResponse())
        }

        assertTrue(observer.progress.isEmpty())
        assertTrue(observer.results.isEmpty())
        assertEquals(1, pendingDrains.size)
        pendingDrains.single().invoke()

        assertEquals(listOf("response_wait", "receiving", "complete"), observer.progress.map { it.phase })
        assertEquals(listOf("complete"), observer.results.map { it.status })
    }

    @Test
    fun admittedProgressStaysBeforeCancelTerminalWithAsyncDrain() {
        val pendingDrains = mutableListOf<() -> Unit>()
        val transport = FakeTransport()
        val observer = RecordingObserver()
        val engine =
            AttachmentDownloadCoordinatorEngine(
                transport = transport,
                sinkFactory = FakeSinkFactory(),
                observerDispatcher = { pendingDrains += it },
                nowMillis = { 1_000L }
            )
        val token = engine.start(request(), observer)
        transport.calls.single().apply {
            start(200, 1L)
            chunk(byteArrayOf(1))
        }
        assertEquals("cancelled", engine.control("download-1", token, "cancel"))

        pendingDrains.single().invoke()

        assertEquals(listOf("response_wait", "receiving"), observer.progress.map { it.phase })
        assertEquals(listOf("cancelled"), observer.results.map { it.status })
    }

    @Test
    fun admittedOldAttemptProgressStaysBeforeReplacementTerminal() {
        val pendingDrains = mutableListOf<() -> Unit>()
        val transport = FakeTransport()
        val oldObserver = RecordingObserver()
        val engine =
            AttachmentDownloadCoordinatorEngine(
                transport = transport,
                sinkFactory = FakeSinkFactory(),
                observerDispatcher = { pendingDrains += it },
                nowMillis = { 1_000L }
            )
        engine.start(request(), oldObserver)
        transport.calls.first().apply {
            start(200, 1L)
            chunk(byteArrayOf(1))
        }
        engine.start(request(), RecordingObserver())

        pendingDrains.single().invoke()

        assertEquals(listOf("response_wait", "receiving"), oldObserver.progress.map { it.phase })
        assertEquals(listOf("cancelled"), oldObserver.results.map { it.status })
    }

    @Test
    fun admittedProgressFailureBeatsQueuedSuccessTerminal() {
        val pendingDrains = mutableListOf<() -> Unit>()
        val transport = FakeTransport()
        val observer = RecordingObserver(throwOnPhase = "receiving")
        val engine =
            AttachmentDownloadCoordinatorEngine(
                transport = transport,
                sinkFactory = FakeSinkFactory(),
                observerDispatcher = { pendingDrains += it },
                nowMillis = { 1_000L }
            )
        engine.start(request(), observer)
        transport.calls.single().apply {
            start(200, 1L)
            chunk(byteArrayOf(1))
            complete(successResponse())
        }

        pendingDrains.single().invoke()

        assertEquals(listOf("failed"), observer.results.map { it.status })
        assertEquals("progress_callback", observer.results.single().errorKind)
    }

    @Test
    fun wholeDeadlineCanStillWinWhileFinalProgressIsQueuedBeforePublishing() {
        val pendingDrains = mutableListOf<() -> Unit>()
        val transport = FakeTransport()
        val observer = RecordingObserver()
        var clock = 0L
        val engine =
            AttachmentDownloadCoordinatorEngine(
                transport = transport,
                sinkFactory = FakeSinkFactory(),
                observerDispatcher = { pendingDrains += it },
                nowMillis = { clock }
            )
        engine.start(request().copy(wholeTransferTimeoutMillis = 100L), observer)
        transport.calls.single().apply {
            start(200, 1L)
            chunk(byteArrayOf(1))
            complete(successResponse())
        }
        clock = 100L
        engine.checkTimeouts(clock)
        pendingDrains.single().invoke()

        assertEquals(listOf("failed"), observer.results.map { it.status })
        assertEquals("whole_timeout", observer.results.single().errorKind)
    }

    @Test
    fun pausedTimeDoesNotConsumeIdleBudgetButWholeDeadlineStillRuns() {
        val transport = FakeTransport()
        val observer = RecordingObserver()
        var clock = 0L
        val engine = AttachmentDownloadCoordinatorEngine(transport, FakeSinkFactory(), nowMillis = { clock })
        val token = engine.start(request().copy(interChunkIdleTimeoutMillis = 250L), observer)
        transport.calls.single().start(200, null)
        assertEquals("paused", engine.control("download-1", token, "pause"))

        clock = 1_000L
        engine.checkTimeouts(clock)
        assertTrue(observer.results.isEmpty())
        assertEquals("resumed", engine.control("download-1", token, "resume"))
        clock = 1_249L
        engine.checkTimeouts(clock)
        assertTrue(observer.results.isEmpty())
        clock = 1_250L
        engine.checkTimeouts(clock)
        assertEquals("idle_timeout", observer.results.single().errorKind)
    }

    @Test
    fun preStartLedgerCapacityEvictsOnlyTheOldestReceipt() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 0L })
        repeat(257) { index ->
            assertEquals("cancelled", engine.control("receipt-$index", null, "cancel"))
        }

        val oldestObserver = RecordingObserver()
        engine.start(request().copy(downloadId = "receipt-0"), oldestObserver)
        assertTrue(oldestObserver.results.isEmpty())
        val newestObserver = RecordingObserver()
        engine.start(request().copy(downloadId = "receipt-256", targetKey = "attachment-256"), newestObserver)
        assertEquals("cancelled", newestObserver.results.single().status)
    }

    private fun request(): AttachmentDownloadRequest =
        AttachmentDownloadRequest(
            downloadId = "download-1",
            targetKey = "attachment-1",
            url = "https://example.test/file.bin",
            targetPath = "/cache/file.bin"
        )

    private fun successResponse(): AttachmentDownloadCompletion =
        AttachmentDownloadCompletion(statusCode = 200, headers = mapOf("Content-Type" to listOf("application/octet-stream")))
}

private class FakeTransport : AttachmentDownloadTransport {
    val requests = mutableListOf<AttachmentDownloadRequest>()
    val calls = mutableListOf<FakeTransportCall>()

    override fun start(
        request: AttachmentDownloadRequest,
        callbacks: AttachmentDownloadTransportCallbacks
    ): AttachmentDownloadTransportCall {
        requests += request
        return FakeTransportCall(callbacks).also(calls::add)
    }
}

private class FakeTransportCall(
    private val callbacks: AttachmentDownloadTransportCallbacks
) : AttachmentDownloadTransportCall {
    var cancelled = false

    override fun cancel() {
        cancelled = true
    }

    fun start(statusCode: Int?, contentLength: Long?) {
        callbacks.onResponseStart(statusCode, contentLength, emptyMap())
    }

    fun chunk(bytes: ByteArray) {
        callbacks.onChunk(bytes)
    }

    fun complete(response: AttachmentDownloadCompletion) {
        callbacks.onComplete(response)
    }
}

private class FakeSinkFactory(
    private val beginSucceeds: Boolean = true,
    private val writeSucceeds: Boolean = true,
    private val commitSucceeds: Boolean = true,
    private val onAbort: () -> Unit = {}
) : AttachmentDownloadSinkFactory {
    val created = mutableListOf<FakeSink>()

    override fun create(targetPath: String, attemptToken: String): AttachmentDownloadAtomicSink =
        FakeSink(
            attemptToken,
            beginSucceeds,
            writeSucceeds,
            commitSucceeds,
            onAbort
        ).also(created::add)
}

private class FakeSink(
    val attemptToken: String,
    private val beginSucceeds: Boolean,
    private val writeSucceeds: Boolean,
    private val commitSucceeds: Boolean,
    private val onAbort: () -> Unit
) : AttachmentDownloadAtomicSink {
    var bytes = ByteArray(0)
    var commitCount = 0
    var abortCount = 0
    var beginCount = 0

    override fun begin(): Boolean {
        beginCount += 1
        return beginSucceeds
    }

    override fun write(bytes: ByteArray): Boolean {
        if (writeSucceeds) this.bytes += bytes
        return writeSucceeds
    }

    override fun prepare(): Boolean = true

    override fun publish(): Boolean {
        commitCount += 1
        return commitSucceeds
    }

    override fun abort() {
        onAbort()
        abortCount += 1
    }
}

private class RecordingObserver(
    private val throwOnProgress: Boolean = false,
    private val throwOnPhase: String? = null
) : AttachmentDownloadObserver {
    val progress = mutableListOf<AttachmentDownloadProgress>()
    val results = mutableListOf<AttachmentDownloadResult>()

    override fun onProgress(progress: AttachmentDownloadProgress) {
        if (throwOnProgress || progress.phase == throwOnPhase) error("observer failed")
        this.progress += progress
    }

    override fun onComplete(result: AttachmentDownloadResult) {
        results += result
    }
}
