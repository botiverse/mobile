package build.raft.app.shared

import build.raft.app.data.SlockAvatarCommitIdentity
import build.raft.app.data.SlockAvatarMountedRenderDescriptor
import build.raft.app.data.SlockAvatarNativeOperationResult
import build.raft.app.data.SlockAvatarNativeReservation
import build.raft.app.data.SlockAvatarNativeReservationRequest
import build.raft.app.data.SlockAvatarNativeReserveResult
import build.raft.app.data.SlockAvatarNativeSlot
import build.raft.app.data.SlockAvatarNativeStageFence
import build.raft.app.data.SlockAvatarNativeStageFinish
import build.raft.app.data.SlockAvatarNativeStageOpen
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

class SlockAndroidAvatarCacheControllerTest {
    @Test
    fun sameFenceReservationIsIdempotentAndFinalizedBytesRenderExactFromDisk() = runBlocking {
        val rig = AvatarKernelTestRig()
        val staging = Files.createTempDirectory("avatar-controller-stage").toFile()
        val controller = controller(rig, staging)
        val slot = avatarSlot()
        val fence = avatarFence(slot, "first-flight")
        val staged = finishStage(controller, fence, "first-avatar".toByteArray())
        val request = SlockAvatarNativeReservationRequest(fence, candidateModelRevision = 1L)

        val first = controller.reserve(request) as SlockAvatarNativeReserveResult.Reserved
        val retry = controller.reserve(request) as SlockAvatarNativeReserveResult.Reserved

        assertEquals(first.reservation, retry.reservation)
        assertEquals(1L, first.reservation.commitIdentity.commitGeneration)
        assertEquals(
            SlockAvatarNativeOperationResult.Accepted,
            controller.bindStage(staged, first.reservation)
        )
        assertEquals(
            SlockAvatarNativeOperationResult.Accepted,
            controller.bindStage(staged, first.reservation)
        )
        assertEquals(
            SlockAvatarNativeOperationResult.Accepted,
            controller.finalize(first.reservation)
        )
        assertEquals(
            SlockAvatarNativeOperationResult.Accepted,
            controller.finalize(first.reservation)
        )
        assertFalse(staging.walkTopDown().any { it.isFile })

        val inspection = controller.inspect(slot)
        assertTrue(inspection.supported)
        assertTrue(inspection.bytesAvailable)
        assertTrue(inspection.bytesDecodable)
        val read =
            controller.beginRender(
                SlockAvatarMountedRenderDescriptor(
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
                SlockAvatarMountedRenderDescriptor(
                    slot = slot,
                    commitIdentity =
                        SlockAvatarCommitIdentity(
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
                SlockAvatarNativeReservationRequest(secondFence, candidateModelRevision = 2L)
            ) as SlockAvatarNativeReserveResult.Reserved).reservation
        assertEquals(2L, second.commitIdentity.commitGeneration)
        assertEquals(SlockAvatarNativeOperationResult.Accepted, controller.bindStage(secondStaged, second))

        assertEquals(SlockAvatarNativeOperationResult.Accepted, controller.rollback(second))
        assertEquals(SlockAvatarNativeOperationResult.Accepted, controller.rollback(second))
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
                SlockAvatarNativeReservationRequest(thirdFence, candidateModelRevision = 2L)
            ) as SlockAvatarNativeReserveResult.Reserved).reservation

        assertEquals(3L, third.commitIdentity.commitGeneration)
        assertNotEquals(second.commitIdentity.nonce, third.commitIdentity.nonce)
        assertEquals(
            SlockAvatarNativeReserveResult.Stale,
            controller.reserve(
                SlockAvatarNativeReservationRequest(secondFence, candidateModelRevision = 2L)
            )
        )
    }

    @Test
    fun bodylessAbortKeepsIdentityUnreservedAndDeletesTransportTemp() = runBlocking {
        val staging = Files.createTempDirectory("avatar-controller-abort").toFile()
        val controller = controller(AvatarKernelTestRig(), staging)
        val slot = avatarSlot()
        val abortedFence = avatarFence(slot, "not-modified-flight")
        val opened = controller.beginStage(abortedFence) as SlockAvatarNativeStageOpen.Opened

        opened.sink.abort()

        assertFalse(opened.sink.write("late-after-dispose".toByteArray()))
        assertEquals(SlockAvatarNativeStageFinish.InvalidContent, opened.sink.finish())
        assertFalse(staging.walkTopDown().any { it.isFile })
        val nextFence = avatarFence(slot, "modified-flight")
        finishStage(controller, nextFence, "modified".toByteArray())
        val reserved =
            (controller.reserve(
                SlockAvatarNativeReservationRequest(nextFence, candidateModelRevision = 1L)
            ) as SlockAvatarNativeReserveResult.Reserved).reservation
        assertEquals(1L, reserved.commitIdentity.commitGeneration)
    }

    @Test
    fun rollbackAfterFinalizeFailClosesTheUnpublishableNativeIdentity() = runBlocking {
        val controller = controller(AvatarKernelTestRig())
        val slot = avatarSlot()
        val reservation = commit(controller, avatarFence(slot, "late-fence-loss"), 1L, "late")

        assertEquals(SlockAvatarNativeOperationResult.Accepted, controller.rollback(reservation))
        assertEquals(SlockAvatarNativeOperationResult.Accepted, controller.rollback(reservation))
        val inspection = controller.inspect(slot)
        assertTrue(inspection.supported)
        assertFalse(inspection.bytesAvailable)
        assertNull(
            controller.beginRender(
                SlockAvatarMountedRenderDescriptor(slot, reservation.commitIdentity, 1L)
            )
        )
    }

    @Test
    fun invalidBodyIsNeverBoundAsASecondRenderAuthority() = runBlocking {
        val staging = Files.createTempDirectory("avatar-controller-invalid").toFile()
        val rig = AvatarKernelTestRig()
        val controller =
            SlockAndroidAvatarCacheController(
                kernel = rig.kernel(),
                stagingRoot = staging,
                startupStagingRecovered = true,
                contentInspector = SlockAndroidAvatarContentInspector { null }
            )
        val opened = controller.beginStage(avatarFence(avatarSlot(), "invalid"))
            as SlockAvatarNativeStageOpen.Opened
        assertTrue(opened.sink.write("not-an-image".toByteArray()))

        assertEquals(SlockAvatarNativeStageFinish.InvalidContent, opened.sink.finish())
        assertFalse(staging.walkTopDown().any { it.isFile })
        assertTrue(rig.files.paths().isEmpty())
    }

    @Test
    fun terminalRetirementRevokesAndPhysicallyDeletesAnOpenTransportStage() = runBlocking {
        val staging = Files.createTempDirectory("avatar-controller-open-terminal").toFile()
        val controller = controller(AvatarKernelTestRig(), staging)
        val sink =
            (controller.beginStage(avatarFence(avatarSlot(), "open-terminal"))
                as SlockAvatarNativeStageOpen.Opened).sink
        assertTrue(sink.write("partial".toByteArray()))
        assertTrue(staging.walkTopDown().any { it.isFile })

        assertEquals(SlockAvatarNativeOperationResult.Accepted, controller.clearAll())

        assertFalse(staging.exists())
        assertFalse(sink.write("late".toByteArray()))
        assertEquals(SlockAvatarNativeStageFinish.StorageFailure, sink.finish())
    }

    @Test
    fun terminalRetirementMakesAnAlreadyReturnedReservationFailClosed() = runBlocking {
        val controller = controller(AvatarKernelTestRig())
        val fence = avatarFence(avatarSlot(), "reserved-before-terminal")
        val staged = finishStage(controller, fence, "candidate".toByteArray())
        val request = SlockAvatarNativeReservationRequest(fence, candidateModelRevision = 1L)
        val reservation =
            (controller.reserve(request) as SlockAvatarNativeReserveResult.Reserved).reservation

        assertEquals(SlockAvatarNativeOperationResult.Accepted, controller.clearAll())

        assertEquals(SlockAvatarNativeReserveResult.Stale, controller.reserve(request))
        assertEquals(SlockAvatarNativeOperationResult.Stale, controller.bindStage(staged, reservation))
        assertEquals(SlockAvatarNativeOperationResult.Accepted, controller.rollback(reservation))
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
                SlockAvatarMountedRenderDescriptor(slot, committed.commitIdentity, 1L)
            )!!
        val result = AtomicReference<SlockAvatarNativeOperationResult>()
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
                SlockAvatarMountedRenderDescriptor(slot, committed.commitIdentity, 1L)
            )
        )

        read.close()
        terminal.join(2_000L)

        assertFalse(terminal.isAlive)
        assertEquals(SlockAvatarNativeOperationResult.Accepted, result.get())
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
                    SlockAndroidAvatarTerminalObserver { _, cut ->
                        if (cut == SlockAndroidAvatarTerminalCut.PhysicalZero) {
                            physicalZero.countDown()
                        }
                    }
            )
        val slot = avatarSlot()
        val committed = commit(controller, avatarFence(slot, "callback-commit"), 1L, "callback")
        val descriptor = SlockAvatarMountedRenderDescriptor(slot, committed.commitIdentity, 1L)
        val read = controller.beginRender(descriptor)!!
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val delivery =
            Thread {
                withSlockAvatarRenderDeliveryLease(read) {
                    callbackEntered.countDown()
                    assertTrue(releaseCallback.await(2, TimeUnit.SECONDS))
                }
            }.apply { start() }
        assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
        val terminalResult = AtomicReference<SlockAvatarNativeOperationResult>()
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
        assertEquals(SlockAvatarNativeOperationResult.Accepted, terminalResult.get())
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
                SlockAvatarMountedRenderDescriptor(throwingSlot, throwingCommit.commitIdentity, 1L)
            )!!
        val expected = IllegalStateException("callback failure")
        var observed: Throwable? = null
        try {
            withSlockAvatarRenderDeliveryLease(throwingRead) { throw expected }
        } catch (throwable: Throwable) {
            observed = throwable
        }
        assertEquals(expected, observed)
        assertEquals(SlockAvatarNativeOperationResult.Accepted, throwingController.clearAll())
    }

    @Test
    fun terminalFirstPhysicalZeroPreventsLateDrawableCallback() = runBlocking {
        val rig = AvatarKernelTestRig()
        val physicalZero = CountDownLatch(1)
        val controller =
            controller(
                rig,
                terminalObserver =
                    SlockAndroidAvatarTerminalObserver { _, cut ->
                        if (cut == SlockAndroidAvatarTerminalCut.PhysicalZero) {
                            physicalZero.countDown()
                        }
                    }
            )
        val slot = avatarSlot(bytes = "terminal-first-bytes")
        val committed = commit(controller, avatarFence(slot, "terminal-first-commit"), 1L, "old")
        val descriptor = SlockAvatarMountedRenderDescriptor(slot, committed.commitIdentity, 1L)
        val allowLateDelivery = CountDownLatch(1)
        val lateDeliveryCompleted = CountDownLatch(1)
        val lateRead = AtomicReference<SlockAndroidAvatarCacheController.SlockAndroidAvatarRenderRead?>()
        val drawableCallbacks = AtomicInteger(0)
        val lateDeliveryFailure = AtomicReference<Throwable?>()
        Thread {
            try {
                check(allowLateDelivery.await(2, TimeUnit.SECONDS))
                controller.beginRender(descriptor).also(lateRead::set)?.let { read ->
                    withSlockAvatarRenderDeliveryLease(read) {
                        drawableCallbacks.incrementAndGet()
                    }
                }
            } catch (throwable: Throwable) {
                lateDeliveryFailure.set(throwable)
            } finally {
                lateDeliveryCompleted.countDown()
            }
        }.start()

        assertEquals(SlockAvatarNativeOperationResult.Accepted, controller.clearAll())
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

        assertEquals(SlockAvatarNativeOperationResult.Accepted, controller.retireSubject(first))
        assertFalse(controller.inspect(first).bytesAvailable)
        assertTrue(controller.inspect(second).bytesAvailable)

        assertEquals(
            SlockAvatarNativeOperationResult.Accepted,
            controller.retireOwner(second.nativePartitionKey)
        )
        assertFalse(controller.inspect(second).bytesAvailable)
    }

    @Test
    fun processRestartOrphanRecoveryDeletesOnlyTransportStaging() {
        val staging = Files.createTempDirectory("avatar-controller-orphan").toFile()
        val orphan = staging.resolve("${avatarOpaque("orphan")}.tmp")
        orphan.writeText("partial")

        assertTrue(recoverSlockAndroidAvatarTransportStaging(staging))
        assertFalse(staging.exists())
    }

    @Test
    fun failedRestartOrphanCleanupDisablesProviderInsteadOfAdmittingASecondAuthority() = runBlocking {
        val controller =
            SlockAndroidAvatarCacheController(
                kernel = AvatarKernelTestRig().kernel(),
                stagingRoot = Files.createTempDirectory("avatar-controller-unrecovered").toFile(),
                startupStagingRecovered = false,
                contentInspector = SlockAndroidAvatarContentInspector { "image/png" }
            )
        val slot = avatarSlot()

        val inspection = controller.inspect(slot)
        assertTrue(inspection.supported)
        assertFalse(inspection.bytesAvailable)
        assertFalse(inspection.bytesDecodable)
        assertEquals(
            SlockAvatarNativeStageOpen.StorageFailure,
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
                SlockAvatarNativeReservationRequest(bindFence, candidateModelRevision = 1L)
            ) as SlockAvatarNativeReserveResult.Reserved).reservation

        deletion.mode = ControlledTransportDeletion.Mode.ReturnFalse
        assertEquals(
            SlockAvatarNativeOperationResult.BusyCleanup,
            controller.bindStage(bindStaged, bindReservation)
        )
        deletion.mode = ControlledTransportDeletion.Mode.Throw
        assertEquals(
            SlockAvatarNativeOperationResult.BusyCleanup,
            controller.bindStage(bindStaged, bindReservation)
        )
        deletion.mode = ControlledTransportDeletion.Mode.Delete
        assertEquals(
            SlockAvatarNativeOperationResult.Accepted,
            controller.bindStage(bindStaged, bindReservation)
        )
        assertEquals(SlockAvatarNativeOperationResult.Accepted, controller.finalize(bindReservation))

        val abortSlot = avatarSlot(bytes = "abort-delete-bytes")
        val abortFence = avatarFence(abortSlot, "abort-delete")
        val abortStaged = finishStage(controller, abortFence, "abort-delete".toByteArray())
        deletion.mode = ControlledTransportDeletion.Mode.ReturnFalse
        assertEquals(SlockAvatarNativeOperationResult.BusyCleanup, controller.abortStage(abortStaged))
        deletion.mode = ControlledTransportDeletion.Mode.Delete
        assertEquals(SlockAvatarNativeOperationResult.Accepted, controller.abortStage(abortStaged))

        val rollbackSlot = avatarSlot(bytes = "rollback-delete-bytes")
        val rollbackFence = avatarFence(rollbackSlot, "rollback-delete")
        val rollbackStaged = finishStage(controller, rollbackFence, "rollback-delete".toByteArray())
        val rollbackReservation =
            (controller.reserve(
                SlockAvatarNativeReservationRequest(rollbackFence, candidateModelRevision = 1L)
            ) as SlockAvatarNativeReserveResult.Reserved).reservation
        deletion.mode = ControlledTransportDeletion.Mode.ReturnFalse
        assertEquals(
            SlockAvatarNativeOperationResult.BusyCleanup,
            controller.bindStage(rollbackStaged, rollbackReservation)
        )
        assertEquals(
            SlockAvatarNativeOperationResult.BusyCleanup,
            controller.rollback(rollbackReservation)
        )
        deletion.mode = ControlledTransportDeletion.Mode.Delete
        assertEquals(
            SlockAvatarNativeOperationResult.Accepted,
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
                as SlockAvatarNativeStageOpen.Opened).sink
        assertTrue(exactSink.write("partial".toByteArray()))
        exactDeletion.mode = ControlledTransportDeletion.Mode.ReturnFalse
        assertEquals(
            SlockAvatarNativeOperationResult.BusyCleanup,
            exactController.retireExact(exactSlot, null)
        )
        assertEquals(
            SlockAvatarNativeStageOpen.AdmissionClosed,
            exactController.beginStage(avatarFence(exactSlot, "exact-delete-late"))
        )
        exactDeletion.mode = ControlledTransportDeletion.Mode.Delete
        assertEquals(
            SlockAvatarNativeOperationResult.Accepted,
            exactController.retireExact(exactSlot, null)
        )
        assertTrue(
            exactController.beginStage(avatarFence(exactSlot, "exact-delete-retry"))
                is SlockAvatarNativeStageOpen.Opened
        )

        val scopeDeletion = ControlledTransportDeletion()
        val scopeController =
            controller(AvatarKernelTestRig(), deleteTransportFile = scopeDeletion::delete)
        val scopeSlot = avatarSlot(bytes = "scope-delete-bytes")
        val scopeSink =
            (scopeController.beginStage(avatarFence(scopeSlot, "scope-delete"))
                as SlockAvatarNativeStageOpen.Opened).sink
        assertTrue(scopeSink.write("partial".toByteArray()))
        scopeDeletion.mode = ControlledTransportDeletion.Mode.Throw
        assertEquals(SlockAvatarNativeOperationResult.BusyCleanup, scopeController.clearAll())
        assertEquals(
            SlockAvatarNativeStageOpen.AdmissionClosed,
            scopeController.beginStage(avatarFence(scopeSlot, "scope-delete-late"))
        )
        scopeDeletion.mode = ControlledTransportDeletion.Mode.Delete
        assertEquals(SlockAvatarNativeOperationResult.Accepted, scopeController.clearAll())
        assertTrue(
            scopeController.beginStage(avatarFence(scopeSlot, "scope-delete-retry"))
                is SlockAvatarNativeStageOpen.Opened
        )
    }

    @Test
    fun staleExactMismatchWithoutCleanupReopensTheKey() = runBlocking {
        val controller = controller(AvatarKernelTestRig())
        val slot = avatarSlot(bytes = "stale-exact-bytes")
        val committed = commit(controller, avatarFence(slot, "stale-exact-commit"), 1L, "current")
        val staleIdentity =
            SlockAvatarCommitIdentity(
                committed.commitIdentity.commitGeneration,
                avatarOpaque("stale-exact-nonce")
            )

        assertEquals(
            SlockAvatarNativeOperationResult.ExpectedIdentityMismatch,
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
        assertTrue(reopened is SlockAvatarNativeStageOpen.Opened)
        (reopened as SlockAvatarNativeStageOpen.Opened).sink.abort()
    }

    @Test
    fun successfulGlobalTerminalClearsAContainedExactCleanupHold() = runBlocking {
        val deletion = ControlledTransportDeletion()
        val controller = controller(AvatarKernelTestRig(), deleteTransportFile = deletion::delete)
        val slot = avatarSlot(bytes = "exact-then-global-bytes")
        val sink =
            (controller.beginStage(avatarFence(slot, "exact-then-global"))
                as SlockAvatarNativeStageOpen.Opened).sink
        assertTrue(sink.write("partial".toByteArray()))

        deletion.mode = ControlledTransportDeletion.Mode.ReturnFalse
        assertEquals(SlockAvatarNativeOperationResult.BusyCleanup, controller.retireExact(slot, null))
        assertEquals(
            SlockAvatarNativeStageOpen.AdmissionClosed,
            controller.beginStage(avatarFence(slot, "exact-before-global"))
        )

        deletion.mode = ControlledTransportDeletion.Mode.Delete
        assertEquals(SlockAvatarNativeOperationResult.Accepted, controller.clearAll())
        val reopened = controller.beginStage(avatarFence(slot, "exact-after-global"))
        assertTrue(reopened is SlockAvatarNativeStageOpen.Opened)
        (reopened as SlockAvatarNativeStageOpen.Opened).sink.abort()
    }

    @Test
    fun successfulOwnerTerminalClearsContainedSubjectAndKeyCleanupHolds() = runBlocking {
        val deletion = ControlledTransportDeletion()
        val controller = controller(AvatarKernelTestRig(), deleteTransportFile = deletion::delete)
        val slot = avatarSlot(bytes = "subject-then-owner-bytes")
        val sink =
            (controller.beginStage(avatarFence(slot, "subject-then-owner"))
                as SlockAvatarNativeStageOpen.Opened).sink
        assertTrue(sink.write("partial".toByteArray()))

        deletion.mode = ControlledTransportDeletion.Mode.ReturnFalse
        assertEquals(SlockAvatarNativeOperationResult.BusyCleanup, controller.retireSubject(slot))
        deletion.mode = ControlledTransportDeletion.Mode.Delete
        assertEquals(
            SlockAvatarNativeOperationResult.Accepted,
            controller.retireOwner(slot.nativePartitionKey)
        )

        val reopened = controller.beginStage(avatarFence(slot, "subject-after-owner"))
        assertTrue(reopened is SlockAvatarNativeStageOpen.Opened)
        (reopened as SlockAvatarNativeStageOpen.Opened).sink.abort()
    }

    @Test
    fun failedWiderTerminalKeepsContainedHoldsUntilSuccessfulGlobalRetry() = runBlocking {
        val deletion = ControlledTransportDeletion()
        val controller = controller(AvatarKernelTestRig(), deleteTransportFile = deletion::delete)
        val slot = avatarSlot(bytes = "subject-global-retry-bytes")
        val sink =
            (controller.beginStage(avatarFence(slot, "subject-global-retry"))
                as SlockAvatarNativeStageOpen.Opened).sink
        assertTrue(sink.write("partial".toByteArray()))

        deletion.mode = ControlledTransportDeletion.Mode.Throw
        assertEquals(SlockAvatarNativeOperationResult.BusyCleanup, controller.retireSubject(slot))
        assertEquals(SlockAvatarNativeOperationResult.BusyCleanup, controller.clearAll())
        assertEquals(
            SlockAvatarNativeStageOpen.AdmissionClosed,
            controller.beginStage(avatarFence(slot, "subject-global-still-closed"))
        )

        deletion.mode = ControlledTransportDeletion.Mode.Delete
        assertEquals(SlockAvatarNativeOperationResult.Accepted, controller.clearAll())
        val reopened = controller.beginStage(avatarFence(slot, "subject-global-reopened"))
        assertTrue(reopened is SlockAvatarNativeStageOpen.Opened)
        (reopened as SlockAvatarNativeStageOpen.Opened).sink.abort()
    }

    private suspend fun commit(
        controller: SlockAndroidAvatarCacheController,
        fence: SlockAvatarNativeStageFence,
        candidateRevision: Long,
        payload: String
    ): SlockAvatarNativeReservation {
        val staged = finishStage(controller, fence, payload.toByteArray())
        val reservation =
            (controller.reserve(
                SlockAvatarNativeReservationRequest(fence, candidateRevision)
            ) as SlockAvatarNativeReserveResult.Reserved).reservation
        assertEquals(SlockAvatarNativeOperationResult.Accepted, controller.bindStage(staged, reservation))
        assertEquals(SlockAvatarNativeOperationResult.Accepted, controller.finalize(reservation))
        return reservation
    }

    private fun finishStage(
        controller: SlockAndroidAvatarCacheController,
        fence: SlockAvatarNativeStageFence,
        bytes: ByteArray
    ): build.raft.app.data.SlockAvatarNativeStagedCandidate {
        val opened = controller.beginStage(fence) as SlockAvatarNativeStageOpen.Opened
        assertTrue(opened.sink.write(bytes.copyOfRange(0, bytes.size / 2)))
        assertTrue(opened.sink.write(bytes.copyOfRange(bytes.size / 2, bytes.size)))
        val finished = opened.sink.finish() as SlockAvatarNativeStageFinish.Ready
        assertEquals(bytes.size.toLong(), finished.candidate.sizeBytes)
        return finished.candidate
    }

    private fun controller(
        rig: AvatarKernelTestRig,
        staging: java.io.File = Files.createTempDirectory("avatar-controller").toFile(),
        deleteTransportFile: (java.io.File) -> Boolean = { file ->
            !file.exists() || file.delete()
        },
        terminalAdmissionObserver: (SlockAndroidAvatarTerminalScope) -> Unit = {},
        terminalObserver: SlockAndroidAvatarTerminalObserver =
            SlockAndroidAvatarTerminalObserver { _, _ -> }
    ): SlockAndroidAvatarCacheController =
        SlockAndroidAvatarCacheController(
            kernel = rig.kernel(terminalObserver = terminalObserver),
            stagingRoot = staging,
            startupStagingRecovered = true,
            contentInspector = SlockAndroidAvatarContentInspector { "image/png" },
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
    ): SlockAvatarNativeSlot =
        SlockAvatarNativeSlot(
            nativePartitionKey = avatarOpaque(owner),
            nativeSubjectKey = avatarOpaque(subject),
            nativeBytesKey = avatarOpaque(bytes)
        )

    private fun avatarFence(
        slot: SlockAvatarNativeSlot,
        nonceSeed: String,
        expected: SlockAvatarCommitIdentity? = null,
        expectedRevision: Long = 0L
    ): SlockAvatarNativeStageFence =
        SlockAvatarNativeStageFence(
            slot = slot,
            requestNonce = avatarOpaque(nonceSeed),
            expectedCommitIdentity = expected,
            expectedModelRevision = expectedRevision
        )
}
