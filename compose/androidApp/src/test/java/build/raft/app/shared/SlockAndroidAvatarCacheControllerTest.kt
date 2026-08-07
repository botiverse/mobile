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
        val controller = controller(rig, staging)
        val slot = avatarSlot()
        val committed = commit(controller, avatarFence(slot, "clear-commit"), 1L, "clear")
        val read =
            controller.beginRender(
                SlockAvatarMountedRenderDescriptor(slot, committed.commitIdentity, 1L)
            )!!
        val started = CountDownLatch(1)
        val result = AtomicReference<SlockAvatarNativeOperationResult>()
        val terminal =
            Thread {
                started.countDown()
                result.set(runBlocking { controller.clearAll() })
            }.apply { start() }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        Thread.sleep(100L)
        assertTrue("terminal must drain active render delivery", terminal.isAlive)

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

        assertFalse(controller.inspect(slot).supported)
        assertEquals(
            SlockAvatarNativeStageOpen.StorageFailure,
            controller.beginStage(avatarFence(slot, "unrecovered"))
        )
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
        staging: java.io.File = Files.createTempDirectory("avatar-controller").toFile()
    ): SlockAndroidAvatarCacheController =
        SlockAndroidAvatarCacheController(
            kernel = rig.kernel(),
            stagingRoot = staging,
            startupStagingRecovered = true,
            contentInspector = SlockAndroidAvatarContentInspector { "image/png" }
        )

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
