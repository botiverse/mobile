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
