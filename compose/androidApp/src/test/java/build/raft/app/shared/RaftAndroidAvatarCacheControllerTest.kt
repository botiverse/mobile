package build.raft.app.shared

import build.raft.app.data.RaftAvatarCommitIdentity
import build.raft.app.data.RaftAvatarMountedRenderDescriptor
import build.raft.app.data.RaftAvatarNativeOperationResult
import build.raft.app.data.RaftAvatarNativeReservation
import build.raft.app.data.RaftAvatarNativeReservationRequest
import build.raft.app.data.RaftAvatarNativeReserveResult
import build.raft.app.data.RaftAvatarNativeSlot
import build.raft.app.data.RaftAvatarNativeStageFence
import build.raft.app.data.RaftAvatarNativeStageFinish
import build.raft.app.data.RaftAvatarNativeStageOpen
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftAndroidAvatarCacheControllerTest {
    @Test
    fun sameFenceReservationIsIdempotentAndFinalizedBytesRenderExactFromDisk() = runBlocking {
        val rig = AvatarKernelTestRig()
        val staging = Files.createTempDirectory("avatar-controller-stage").toFile()
        val controller = controller(rig, staging)
        val slot = avatarSlot()
        val fence = avatarFence(slot, "first-flight")
        val staged = finishStage(controller, fence, "first-avatar".toByteArray())
        val request = RaftAvatarNativeReservationRequest(fence, candidateModelRevision = 1L)

        val first = controller.reserve(request) as RaftAvatarNativeReserveResult.Reserved
        val retry = controller.reserve(request) as RaftAvatarNativeReserveResult.Reserved

        assertEquals(first.reservation, retry.reservation)
        assertEquals(1L, first.reservation.commitIdentity.commitGeneration)
        assertEquals(
            RaftAvatarNativeOperationResult.Accepted,
            controller.bindStage(staged, first.reservation)
        )
        assertEquals(
            RaftAvatarNativeOperationResult.Accepted,
            controller.bindStage(staged, first.reservation)
        )
        assertEquals(
            RaftAvatarNativeOperationResult.Accepted,
            controller.finalize(first.reservation)
        )
        assertEquals(
            RaftAvatarNativeOperationResult.Accepted,
            controller.finalize(first.reservation)
        )
        assertFalse(staging.walkTopDown().any { it.isFile })

        val inspection = controller.inspect(slot)
        assertTrue(inspection.supported)
        assertTrue(inspection.bytesAvailable)
        assertTrue(inspection.bytesDecodable)
        val read =
            controller.beginRender(
                RaftAvatarMountedRenderDescriptor(
                    slot = slot,
                    commitIdentity = first.reservation.commitIdentity,
                    modelRevision = 1L
                )
            )
        assertNotNull(read)
        assertArrayEquals("first-avatar".toByteArray(), read!!.bytes)
        assertTrue(read.registerDecoded(40, 40, "40x40:false:aspect_fit"))
        read.close()
        read.close()
        assertNull(
            controller.beginRender(
                RaftAvatarMountedRenderDescriptor(
                    slot = slot,
                    commitIdentity =
                        RaftAvatarCommitIdentity(
                            first.reservation.commitIdentity.commitGeneration,
                            avatarOpaque("wrong-render-nonce")
                        ),
                    modelRevision = 1L
                )
            )
        )
    }

    @Test
    fun durableRollbackConsumesFlightAndNextReservationAdvancesHighWater() = runBlocking {
        val rig = AvatarKernelTestRig()
        val staging = Files.createTempDirectory("avatar-controller-rollback").toFile()
        val controller = controller(rig, staging)
        val slot = avatarSlot()
        val committed = commit(controller, avatarFence(slot, "commit-one"), 1L, "one")
        val secondFence =
            avatarFence(
                slot = slot,
                nonceSeed = "rollback-two",
                expected = committed.commitIdentity,
                expectedRevision = 1L
            )
        val secondStaged = finishStage(controller, secondFence, "two".toByteArray())
        val second =
            (controller.reserve(
                RaftAvatarNativeReservationRequest(secondFence, candidateModelRevision = 2L)
            ) as RaftAvatarNativeReserveResult.Reserved).reservation
        assertEquals(2L, second.commitIdentity.commitGeneration)
        assertEquals(RaftAvatarNativeOperationResult.Accepted, controller.bindStage(secondStaged, second))

        assertEquals(RaftAvatarNativeOperationResult.Accepted, controller.rollback(second))
        assertEquals(RaftAvatarNativeOperationResult.Accepted, controller.rollback(second))
        assertFalse(staging.walkTopDown().any { it.isFile })

        val thirdFence =
            avatarFence(
                slot = slot,
                nonceSeed = "flight-three",
                expected = committed.commitIdentity,
                expectedRevision = 1L
            )
        finishStage(controller, thirdFence, "three".toByteArray())
        val third =
            (controller.reserve(
                RaftAvatarNativeReservationRequest(thirdFence, candidateModelRevision = 2L)
            ) as RaftAvatarNativeReserveResult.Reserved).reservation

        assertEquals(3L, third.commitIdentity.commitGeneration)
        assertNotEquals(second.commitIdentity.nonce, third.commitIdentity.nonce)
        assertEquals(
            RaftAvatarNativeReserveResult.Stale,
            controller.reserve(
                RaftAvatarNativeReservationRequest(secondFence, candidateModelRevision = 2L)
            )
        )
    }

    @Test
    fun bodylessAbortKeepsIdentityUnreservedAndDeletesTransportTemp() = runBlocking {
        val staging = Files.createTempDirectory("avatar-controller-abort").toFile()
        val controller = controller(AvatarKernelTestRig(), staging)
        val slot = avatarSlot()
        val abortedFence = avatarFence(slot, "not-modified-flight")
        val opened = controller.beginStage(abortedFence) as RaftAvatarNativeStageOpen.Opened

        opened.sink.abort()

        assertFalse(opened.sink.write("late-after-dispose".toByteArray()))
        assertEquals(RaftAvatarNativeStageFinish.InvalidContent, opened.sink.finish())
        assertFalse(staging.walkTopDown().any { it.isFile })
        val nextFence = avatarFence(slot, "modified-flight")
        finishStage(controller, nextFence, "modified".toByteArray())
        val reserved =
            (controller.reserve(
                RaftAvatarNativeReservationRequest(nextFence, candidateModelRevision = 1L)
            ) as RaftAvatarNativeReserveResult.Reserved).reservation
        assertEquals(1L, reserved.commitIdentity.commitGeneration)
    }

    @Test
    fun rollbackAfterFinalizeFailClosesTheUnpublishableNativeIdentity() = runBlocking {
        val controller = controller(AvatarKernelTestRig())
        val slot = avatarSlot()
        val reservation = commit(controller, avatarFence(slot, "late-fence-loss"), 1L, "late")

        assertEquals(RaftAvatarNativeOperationResult.Accepted, controller.rollback(reservation))
        assertEquals(RaftAvatarNativeOperationResult.Accepted, controller.rollback(reservation))
        val inspection = controller.inspect(slot)
        assertTrue(inspection.supported)
        assertFalse(inspection.bytesAvailable)
        assertNull(
            controller.beginRender(
                RaftAvatarMountedRenderDescriptor(slot, reservation.commitIdentity, 1L)
            )
        )
    }

    @Test
    fun invalidBodyIsNeverBoundAsASecondRenderAuthority() = runBlocking {
        val staging = Files.createTempDirectory("avatar-controller-invalid").toFile()
        val rig = AvatarKernelTestRig()
        val controller =
            RaftAndroidAvatarCacheController(
                kernel = rig.kernel(),
                stagingRoot = staging,
                startupStagingRecovered = true,
                contentInspector = RaftAndroidAvatarContentInspector { null }
            )
        val opened = controller.beginStage(avatarFence(avatarSlot(), "invalid"))
            as RaftAvatarNativeStageOpen.Opened
        assertTrue(opened.sink.write("not-an-image".toByteArray()))

        assertEquals(RaftAvatarNativeStageFinish.InvalidContent, opened.sink.finish())
        assertFalse(staging.walkTopDown().any { it.isFile })
        assertTrue(rig.files.paths().isEmpty())
    }

    @Test
    fun terminalRetirementRevokesAndPhysicallyDeletesAnOpenTransportStage() = runBlocking {
        val staging = Files.createTempDirectory("avatar-controller-open-terminal").toFile()
        val controller = controller(AvatarKernelTestRig(), staging)
        val sink =
            (controller.beginStage(avatarFence(avatarSlot(), "open-terminal"))
                as RaftAvatarNativeStageOpen.Opened).sink
        assertTrue(sink.write("partial".toByteArray()))
        assertTrue(staging.walkTopDown().any { it.isFile })

        assertEquals(RaftAvatarNativeOperationResult.Accepted, controller.clearAll())

        assertFalse(staging.exists())
        assertFalse(sink.write("late".toByteArray()))
        assertEquals(RaftAvatarNativeStageFinish.StorageFailure, sink.finish())
    }

    @Test
    fun terminalRetirementMakesAnAlreadyReturnedReservationFailClosed() = runBlocking {
        val controller = controller(AvatarKernelTestRig())
        val fence = avatarFence(avatarSlot(), "reserved-before-terminal")
        val staged = finishStage(controller, fence, "candidate".toByteArray())
        val request = RaftAvatarNativeReservationRequest(fence, candidateModelRevision = 1L)
        val reservation =
            (controller.reserve(request) as RaftAvatarNativeReserveResult.Reserved).reservation

        assertEquals(RaftAvatarNativeOperationResult.Accepted, controller.clearAll())

        assertEquals(RaftAvatarNativeReserveResult.Stale, controller.reserve(request))
        assertEquals(RaftAvatarNativeOperationResult.Stale, controller.bindStage(staged, reservation))
        assertEquals(RaftAvatarNativeOperationResult.Accepted, controller.rollback(reservation))
    }

    @Test
    fun clearAllWaitsForRenderDeliveryThenPhysicallyZerosKernelAndTransport() = runBlocking {
        val rig = AvatarKernelTestRig()
        val staging = Files.createTempDirectory("avatar-controller-clear").toFile()
        val terminalAdmitted = CountDownLatch(1)
        val controller =
            controller(rig, staging, terminalAdmissionObserver = { terminalAdmitted.countDown() })
        val slot = avatarSlot()
        val committed = commit(controller, avatarFence(slot, "clear-commit"), 1L, "clear")
        val read =
            controller.beginRender(
                RaftAvatarMountedRenderDescriptor(slot, committed.commitIdentity, 1L)
            )!!
        val result = AtomicReference<RaftAvatarNativeOperationResult>()
        val terminal =
            Thread {
                result.set(runBlocking { controller.clearAll() })
            }.apply { start() }
        assertTrue(terminalAdmitted.await(2, TimeUnit.SECONDS))
        assertTrue("terminal must drain active render delivery", terminal.isAlive)
        val duringTerminal = controller.inspect(slot)
        assertTrue("recognized managed descriptors remain supported while admission is closed", duringTerminal.supported)
        assertFalse(duringTerminal.bytesAvailable)
        assertNull(
            controller.beginRender(
                RaftAvatarMountedRenderDescriptor(slot, committed.commitIdentity, 1L)
            )
        )

        read.close()
        terminal.join(2_000L)

        assertFalse(terminal.isAlive)
        assertEquals(RaftAvatarNativeOperationResult.Accepted, result.get())
        assertTrue(rig.files.paths().isEmpty())
        assertFalse(staging.exists())
        val after = controller.inspect(slot)
        assertTrue(after.supported)
        assertFalse(after.bytesAvailable)
    }

    @Test
    fun callbackFirstKeepsTerminalIncompleteUntilReturnAndCallbackThrowStillReleases() = runBlocking {
        val rig = AvatarKernelTestRig()
        val terminalAdmitted = CountDownLatch(1)
        val physicalZero = CountDownLatch(1)
        val controller =
            controller(
                rig,
                terminalAdmissionObserver = { terminalAdmitted.countDown() },
                terminalObserver =
                    RaftAndroidAvatarTerminalObserver { _, cut ->
                        if (cut == RaftAndroidAvatarTerminalCut.PhysicalZero) {
                            physicalZero.countDown()
                        }
                    }
            )
        val slot = avatarSlot()
        val committed = commit(controller, avatarFence(slot, "callback-commit"), 1L, "callback")
        val descriptor = RaftAvatarMountedRenderDescriptor(slot, committed.commitIdentity, 1L)
        val read = controller.beginRender(descriptor)!!
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val delivery =
            Thread {
                withRaftAvatarRenderDeliveryLease(read) {
                    callbackEntered.countDown()
                    assertTrue(releaseCallback.await(2, TimeUnit.SECONDS))
                }
            }.apply { start() }
        assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
        val terminalResult = AtomicReference<RaftAvatarNativeOperationResult>()
        val terminalCompleted = CountDownLatch(1)
        Thread {
            try {
                terminalResult.set(runBlocking { controller.clearAll() })
            } finally {
                terminalCompleted.countDown()
            }
        }.start()
        assertTrue(terminalAdmitted.await(2, TimeUnit.SECONDS))
        assertFalse(
            "terminal future must stay incomplete while callback(drawable) is blocked",
            terminalCompleted.await(250, TimeUnit.MILLISECONDS)
        )
        assertEquals("physical zero cannot precede callback return", 1L, physicalZero.count)
        assertTrue("managed bytes must remain until callback return", rig.files.paths().isNotEmpty())

        releaseCallback.countDown()
        delivery.join(2_000L)
        assertTrue(terminalCompleted.await(2, TimeUnit.SECONDS))
        assertTrue(physicalZero.await(2, TimeUnit.SECONDS))

        assertFalse(delivery.isAlive)
        assertEquals(RaftAvatarNativeOperationResult.Accepted, terminalResult.get())
        assertTrue(rig.files.paths().isEmpty())

        val throwingController = controller(AvatarKernelTestRig())
        val throwingSlot = avatarSlot(bytes = "throwing-callback-bytes")
        val throwingCommit =
            commit(
                throwingController,
                avatarFence(throwingSlot, "throwing-callback-commit"),
                1L,
                "throwing"
            )
        val throwingRead =
            throwingController.beginRender(
                RaftAvatarMountedRenderDescriptor(throwingSlot, throwingCommit.commitIdentity, 1L)
            )!!
        val expected = IllegalStateException("callback failure")
        var observed: Throwable? = null
        try {
            withRaftAvatarRenderDeliveryLease(throwingRead) { throw expected }
        } catch (throwable: Throwable) {
            observed = throwable
        }
        assertEquals(expected, observed)
        assertEquals(RaftAvatarNativeOperationResult.Accepted, throwingController.clearAll())
    }

    @Test
    fun terminalFirstPhysicalZeroPreventsLateDrawableCallback() = runBlocking {
        val rig = AvatarKernelTestRig()
        val physicalZero = CountDownLatch(1)
        val controller =
            controller(
                rig,
                terminalObserver =
                    RaftAndroidAvatarTerminalObserver { _, cut ->
                        if (cut == RaftAndroidAvatarTerminalCut.PhysicalZero) {
                            physicalZero.countDown()
                        }
                    }
            )
        val slot = avatarSlot(bytes = "terminal-first-bytes")
        val committed = commit(controller, avatarFence(slot, "terminal-first-commit"), 1L, "old")
        val descriptor = RaftAvatarMountedRenderDescriptor(slot, committed.commitIdentity, 1L)
        val allowLateDelivery = CountDownLatch(1)
        val lateDeliveryCompleted = CountDownLatch(1)
        val lateRead = AtomicReference<RaftAndroidAvatarCacheController.RaftAndroidAvatarRenderRead?>()
        val drawableCallbacks = AtomicInteger(0)
        val lateDeliveryFailure = AtomicReference<Throwable?>()
        Thread {
            try {
                check(allowLateDelivery.await(2, TimeUnit.SECONDS))
                controller.beginRender(descriptor).also(lateRead::set)?.let { read ->
                    withRaftAvatarRenderDeliveryLease(read) {
                        drawableCallbacks.incrementAndGet()
                    }
                }
            } catch (throwable: Throwable) {
                lateDeliveryFailure.set(throwable)
            } finally {
                lateDeliveryCompleted.countDown()
            }
        }.start()

        assertEquals(RaftAvatarNativeOperationResult.Accepted, controller.clearAll())
        assertTrue(physicalZero.await(2, TimeUnit.SECONDS))
        assertTrue(rig.files.paths().isEmpty())

        allowLateDelivery.countDown()
        assertTrue(lateDeliveryCompleted.await(2, TimeUnit.SECONDS))
        assertNull(lateDeliveryFailure.get())
        assertNull(lateRead.get())
        assertEquals("retired drawable must never reach the callback", 0, drawableCallbacks.get())
    }

    @Test
    fun subjectAndOwnerRetirementStayInsideTheOpaqueMappedScope() = runBlocking {
        val controller = controller(AvatarKernelTestRig())
        val first = avatarSlot(owner = "shared-owner", subject = "subject-one", bytes = "bytes-one")
        val second = avatarSlot(owner = "shared-owner", subject = "subject-two", bytes = "bytes-two")
        commit(controller, avatarFence(first, "subject-one-flight"), 1L, "one")
        commit(controller, avatarFence(second, "subject-two-flight"), 1L, "two")

        assertEquals(RaftAvatarNativeOperationResult.Accepted, controller.retireSubject(first))
        assertFalse(controller.inspect(first).bytesAvailable)
        assertTrue(controller.inspect(second).bytesAvailable)

        assertEquals(
            RaftAvatarNativeOperationResult.Accepted,
            controller.retireOwner(second.nativePartitionKey)
        )
        assertFalse(controller.inspect(second).bytesAvailable)
    }

    @Test
    fun processRestartOrphanRecoveryDeletesOnlyTransportStaging() {
        val staging = Files.createTempDirectory("avatar-controller-orphan").toFile()
        val orphan = staging.resolve("${avatarOpaque("orphan")}.tmp")
        orphan.writeText("partial")

        assertTrue(recoverRaftAndroidAvatarTransportStaging(staging))
        assertFalse(staging.exists())
    }

    @Test
    fun failedRestartOrphanCleanupDisablesProviderInsteadOfAdmittingASecondAuthority() = runBlocking {
        val controller =
            RaftAndroidAvatarCacheController(
                kernel = AvatarKernelTestRig().kernel(),
                stagingRoot = Files.createTempDirectory("avatar-controller-unrecovered").toFile(),
                startupStagingRecovered = false,
                contentInspector = RaftAndroidAvatarContentInspector { "image/png" }
            )
        val slot = avatarSlot()

        val inspection = controller.inspect(slot)
        assertTrue(inspection.supported)
        assertFalse(inspection.bytesAvailable)
        assertFalse(inspection.bytesDecodable)
        assertEquals(
            RaftAvatarNativeStageOpen.StorageFailure,
            controller.beginStage(avatarFence(slot, "unrecovered"))
        )
    }

    @Test
    fun kernelReadFailureRemainsRecognizedManagedAndFailClosed() = runBlocking {
        val rig = AvatarKernelTestRig()
        val controller = controller(rig)
        val slot = avatarSlot(bytes = "read-failure-bytes")
        commit(controller, avatarFence(slot, "read-failure-commit"), 1L, "read-failure")
        rig.files.failNext(FakeAvatarFileOperation.Read)

        val inspection = controller.inspect(slot)

        assertTrue(inspection.supported)
        assertFalse(inspection.bytesAvailable)
        assertFalse(inspection.bytesDecodable)
    }

    @Test
    fun bindAbortAndRollbackStayBusyUntilTransportDeleteIsVerified() = runBlocking {
        val deletion = ControlledTransportDeletion()
        val controller = controller(AvatarKernelTestRig(), deleteTransportFile = deletion::delete)
        val bindSlot = avatarSlot(bytes = "bind-delete-bytes")
        val bindFence = avatarFence(bindSlot, "bind-delete")
        val bindStaged = finishStage(controller, bindFence, "bind-delete".toByteArray())
        val bindReservation =
            (controller.reserve(
                RaftAvatarNativeReservationRequest(bindFence, candidateModelRevision = 1L)
            ) as RaftAvatarNativeReserveResult.Reserved).reservation

        deletion.mode = ControlledTransportDeletion.Mode.ReturnFalse
        assertEquals(
            RaftAvatarNativeOperationResult.BusyCleanup,
            controller.bindStage(bindStaged, bindReservation)
        )
        deletion.mode = ControlledTransportDeletion.Mode.Throw
        assertEquals(
            RaftAvatarNativeOperationResult.BusyCleanup,
            controller.bindStage(bindStaged, bindReservation)
        )
        deletion.mode = ControlledTransportDeletion.Mode.Delete
        assertEquals(
            RaftAvatarNativeOperationResult.Accepted,
            controller.bindStage(bindStaged, bindReservation)
        )
        assertEquals(RaftAvatarNativeOperationResult.Accepted, controller.finalize(bindReservation))

        val abortSlot = avatarSlot(bytes = "abort-delete-bytes")
        val abortFence = avatarFence(abortSlot, "abort-delete")
        val abortStaged = finishStage(controller, abortFence, "abort-delete".toByteArray())
        deletion.mode = ControlledTransportDeletion.Mode.ReturnFalse
        assertEquals(RaftAvatarNativeOperationResult.BusyCleanup, controller.abortStage(abortStaged))
        deletion.mode = ControlledTransportDeletion.Mode.Delete
        assertEquals(RaftAvatarNativeOperationResult.Accepted, controller.abortStage(abortStaged))

        val rollbackSlot = avatarSlot(bytes = "rollback-delete-bytes")
        val rollbackFence = avatarFence(rollbackSlot, "rollback-delete")
        val rollbackStaged = finishStage(controller, rollbackFence, "rollback-delete".toByteArray())
        val rollbackReservation =
            (controller.reserve(
                RaftAvatarNativeReservationRequest(rollbackFence, candidateModelRevision = 1L)
            ) as RaftAvatarNativeReserveResult.Reserved).reservation
        deletion.mode = ControlledTransportDeletion.Mode.ReturnFalse
        assertEquals(
            RaftAvatarNativeOperationResult.BusyCleanup,
            controller.bindStage(rollbackStaged, rollbackReservation)
        )
        assertEquals(
            RaftAvatarNativeOperationResult.BusyCleanup,
            controller.rollback(rollbackReservation)
        )
        deletion.mode = ControlledTransportDeletion.Mode.Delete
        assertEquals(
            RaftAvatarNativeOperationResult.Accepted,
            controller.rollback(rollbackReservation)
        )
    }

    @Test
    fun exactAndScopeTerminalKeepAdmissionClosedUntilTransportZeroRetry() = runBlocking {
        val exactDeletion = ControlledTransportDeletion()
        val exactController =
            controller(AvatarKernelTestRig(), deleteTransportFile = exactDeletion::delete)
        val exactSlot = avatarSlot(bytes = "exact-delete-bytes")
        val exactSink =
            (exactController.beginStage(avatarFence(exactSlot, "exact-delete"))
                as RaftAvatarNativeStageOpen.Opened).sink
        assertTrue(exactSink.write("partial".toByteArray()))
        exactDeletion.mode = ControlledTransportDeletion.Mode.ReturnFalse
        assertEquals(
            RaftAvatarNativeOperationResult.BusyCleanup,
            exactController.retireExact(exactSlot, null)
        )
        assertEquals(
            RaftAvatarNativeStageOpen.AdmissionClosed,
            exactController.beginStage(avatarFence(exactSlot, "exact-delete-late"))
        )
        exactDeletion.mode = ControlledTransportDeletion.Mode.Delete
        assertEquals(
            RaftAvatarNativeOperationResult.Accepted,
            exactController.retireExact(exactSlot, null)
        )
        assertTrue(
            exactController.beginStage(avatarFence(exactSlot, "exact-delete-retry"))
                is RaftAvatarNativeStageOpen.Opened
        )

        val scopeDeletion = ControlledTransportDeletion()
        val scopeController =
            controller(AvatarKernelTestRig(), deleteTransportFile = scopeDeletion::delete)
        val scopeSlot = avatarSlot(bytes = "scope-delete-bytes")
        val scopeSink =
            (scopeController.beginStage(avatarFence(scopeSlot, "scope-delete"))
                as RaftAvatarNativeStageOpen.Opened).sink
        assertTrue(scopeSink.write("partial".toByteArray()))
        scopeDeletion.mode = ControlledTransportDeletion.Mode.Throw
        assertEquals(RaftAvatarNativeOperationResult.BusyCleanup, scopeController.clearAll())
        assertEquals(
            RaftAvatarNativeStageOpen.AdmissionClosed,
            scopeController.beginStage(avatarFence(scopeSlot, "scope-delete-late"))
        )
        scopeDeletion.mode = ControlledTransportDeletion.Mode.Delete
        assertEquals(RaftAvatarNativeOperationResult.Accepted, scopeController.clearAll())
        assertTrue(
            scopeController.beginStage(avatarFence(scopeSlot, "scope-delete-retry"))
                is RaftAvatarNativeStageOpen.Opened
        )
    }

    @Test
    fun staleExactMismatchWithoutCleanupReopensTheKey() = runBlocking {
        val controller = controller(AvatarKernelTestRig())
        val slot = avatarSlot(bytes = "stale-exact-bytes")
        val committed = commit(controller, avatarFence(slot, "stale-exact-commit"), 1L, "current")
        val staleIdentity =
            RaftAvatarCommitIdentity(
                committed.commitIdentity.commitGeneration,
                avatarOpaque("stale-exact-nonce")
            )

        assertEquals(
            RaftAvatarNativeOperationResult.ExpectedIdentityMismatch,
            controller.retireExact(slot, staleIdentity)
        )
        val reopened =
            controller.beginStage(
                avatarFence(
                    slot = slot,
                    nonceSeed = "stale-exact-reopened",
                    expected = committed.commitIdentity,
                    expectedRevision = 1L
                )
            )
        assertTrue(reopened is RaftAvatarNativeStageOpen.Opened)
        (reopened as RaftAvatarNativeStageOpen.Opened).sink.abort()
    }

    @Test
    fun successfulGlobalTerminalClearsAContainedExactCleanupHold() = runBlocking {
        val deletion = ControlledTransportDeletion()
        val controller = controller(AvatarKernelTestRig(), deleteTransportFile = deletion::delete)
        val slot = avatarSlot(bytes = "exact-then-global-bytes")
        val sink =
            (controller.beginStage(avatarFence(slot, "exact-then-global"))
                as RaftAvatarNativeStageOpen.Opened).sink
        assertTrue(sink.write("partial".toByteArray()))

        deletion.mode = ControlledTransportDeletion.Mode.ReturnFalse
        assertEquals(RaftAvatarNativeOperationResult.BusyCleanup, controller.retireExact(slot, null))
        assertEquals(
            RaftAvatarNativeStageOpen.AdmissionClosed,
            controller.beginStage(avatarFence(slot, "exact-before-global"))
        )

        deletion.mode = ControlledTransportDeletion.Mode.Delete
        assertEquals(RaftAvatarNativeOperationResult.Accepted, controller.clearAll())
        val reopened = controller.beginStage(avatarFence(slot, "exact-after-global"))
        assertTrue(reopened is RaftAvatarNativeStageOpen.Opened)
        (reopened as RaftAvatarNativeStageOpen.Opened).sink.abort()
    }

    @Test
    fun successfulOwnerTerminalClearsContainedSubjectAndKeyCleanupHolds() = runBlocking {
        val deletion = ControlledTransportDeletion()
        val controller = controller(AvatarKernelTestRig(), deleteTransportFile = deletion::delete)
        val slot = avatarSlot(bytes = "subject-then-owner-bytes")
        val sink =
            (controller.beginStage(avatarFence(slot, "subject-then-owner"))
                as RaftAvatarNativeStageOpen.Opened).sink
        assertTrue(sink.write("partial".toByteArray()))

        deletion.mode = ControlledTransportDeletion.Mode.ReturnFalse
        assertEquals(RaftAvatarNativeOperationResult.BusyCleanup, controller.retireSubject(slot))
        deletion.mode = ControlledTransportDeletion.Mode.Delete
        assertEquals(
            RaftAvatarNativeOperationResult.Accepted,
            controller.retireOwner(slot.nativePartitionKey)
        )

        val reopened = controller.beginStage(avatarFence(slot, "subject-after-owner"))
        assertTrue(reopened is RaftAvatarNativeStageOpen.Opened)
        (reopened as RaftAvatarNativeStageOpen.Opened).sink.abort()
    }

    @Test
    fun failedWiderTerminalKeepsContainedHoldsUntilSuccessfulGlobalRetry() = runBlocking {
        val deletion = ControlledTransportDeletion()
        val controller = controller(AvatarKernelTestRig(), deleteTransportFile = deletion::delete)
        val slot = avatarSlot(bytes = "subject-global-retry-bytes")
        val sink =
            (controller.beginStage(avatarFence(slot, "subject-global-retry"))
                as RaftAvatarNativeStageOpen.Opened).sink
        assertTrue(sink.write("partial".toByteArray()))

        deletion.mode = ControlledTransportDeletion.Mode.Throw
        assertEquals(RaftAvatarNativeOperationResult.BusyCleanup, controller.retireSubject(slot))
        assertEquals(RaftAvatarNativeOperationResult.BusyCleanup, controller.clearAll())
        assertEquals(
            RaftAvatarNativeStageOpen.AdmissionClosed,
            controller.beginStage(avatarFence(slot, "subject-global-still-closed"))
        )

        deletion.mode = ControlledTransportDeletion.Mode.Delete
        assertEquals(RaftAvatarNativeOperationResult.Accepted, controller.clearAll())
        val reopened = controller.beginStage(avatarFence(slot, "subject-global-reopened"))
        assertTrue(reopened is RaftAvatarNativeStageOpen.Opened)
        (reopened as RaftAvatarNativeStageOpen.Opened).sink.abort()
    }

    private suspend fun commit(
        controller: RaftAndroidAvatarCacheController,
        fence: RaftAvatarNativeStageFence,
        candidateRevision: Long,
        payload: String
    ): RaftAvatarNativeReservation {
        val staged = finishStage(controller, fence, payload.toByteArray())
        val reservation =
            (controller.reserve(
                RaftAvatarNativeReservationRequest(fence, candidateRevision)
            ) as RaftAvatarNativeReserveResult.Reserved).reservation
        assertEquals(RaftAvatarNativeOperationResult.Accepted, controller.bindStage(staged, reservation))
        assertEquals(RaftAvatarNativeOperationResult.Accepted, controller.finalize(reservation))
        return reservation
    }

    private fun finishStage(
        controller: RaftAndroidAvatarCacheController,
        fence: RaftAvatarNativeStageFence,
        bytes: ByteArray
    ): build.raft.app.data.RaftAvatarNativeStagedCandidate {
        val opened = controller.beginStage(fence) as RaftAvatarNativeStageOpen.Opened
        assertTrue(opened.sink.write(bytes.copyOfRange(0, bytes.size / 2)))
        assertTrue(opened.sink.write(bytes.copyOfRange(bytes.size / 2, bytes.size)))
        val finished = opened.sink.finish() as RaftAvatarNativeStageFinish.Ready
        assertEquals(bytes.size.toLong(), finished.candidate.sizeBytes)
        return finished.candidate
    }

    private fun controller(
        rig: AvatarKernelTestRig,
        staging: java.io.File = Files.createTempDirectory("avatar-controller").toFile(),
        deleteTransportFile: (java.io.File) -> Boolean = { file ->
            !file.exists() || file.delete()
        },
        terminalAdmissionObserver: (RaftAndroidAvatarTerminalScope) -> Unit = {},
        terminalObserver: RaftAndroidAvatarTerminalObserver =
            RaftAndroidAvatarTerminalObserver { _, _ -> }
    ): RaftAndroidAvatarCacheController =
        RaftAndroidAvatarCacheController(
            kernel = rig.kernel(terminalObserver = terminalObserver),
            stagingRoot = staging,
            startupStagingRecovered = true,
            contentInspector = RaftAndroidAvatarContentInspector { "image/png" },
            deleteTransportFile = deleteTransportFile,
            terminalAdmissionObserver = terminalAdmissionObserver
        )

    private class ControlledTransportDeletion {
        enum class Mode { Delete, ReturnFalse, Throw }

        @Volatile
        var mode: Mode = Mode.Delete

        fun delete(file: java.io.File): Boolean =
            when (mode) {
                Mode.Delete -> !file.exists() || file.delete()
                Mode.ReturnFalse -> false
                Mode.Throw -> throw IllegalStateException("simulated transport delete failure")
            }
    }

    private fun avatarSlot(
        owner: String = "owner-a",
        subject: String = "subject-a",
        bytes: String = "bytes-a"
    ): RaftAvatarNativeSlot =
        RaftAvatarNativeSlot(
            nativePartitionKey = avatarOpaque(owner),
            nativeSubjectKey = avatarOpaque(subject),
            nativeBytesKey = avatarOpaque(bytes)
        )

    private fun avatarFence(
        slot: RaftAvatarNativeSlot,
        nonceSeed: String,
        expected: RaftAvatarCommitIdentity? = null,
        expectedRevision: Long = 0L
    ): RaftAvatarNativeStageFence =
        RaftAvatarNativeStageFence(
            slot = slot,
            requestNonce = avatarOpaque(nonceSeed),
            expectedCommitIdentity = expected,
            expectedModelRevision = expectedRevision
        )
}
