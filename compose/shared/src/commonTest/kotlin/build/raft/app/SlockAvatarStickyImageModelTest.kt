package build.raft.app

import build.raft.app.data.SlockAvatarCommitIdentity
import build.raft.app.data.parseSlockAvatarMountedRenderModel
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * task #402: the sticky render model suppresses re-render when the admitted candidate is only a
 * different encoding of the bytes already on screen (URL -> managed at the pinned commit, or
 * managed at the same commit). A real avatar change — different slot, or a superseding
 * commitIdentity — must still switch, including while a URL frame is on screen.
 */
class SlockAvatarStickyImageModelTest {

    @Test
    fun noCandidateRendersNothing() {
        // Sanity: the hand-built managed models must round-trip through the real parser.
        assertNotNull(parseSlockAvatarMountedRenderModel(managedModel(subject = SubjectA)))
        assertNull(slockAvatarStickyImageModel(candidateImageModel = null, lastRenderedFrame = null))
        assertNull(
            slockAvatarStickyImageModel(
                candidateImageModel = null,
                lastRenderedFrame = managedFrame(subject = SubjectA)
            )
        )
    }

    @Test
    fun noRenderedSuccessYetPicksCandidate() {
        assertEquals(
            RemoteUrl,
            slockAvatarStickyImageModel(candidateImageModel = RemoteUrl, lastRenderedFrame = null)
        )
        val managed = managedModel(subject = SubjectA)
        assertEquals(
            managed,
            slockAvatarStickyImageModel(candidateImageModel = managed, lastRenderedFrame = null)
        )
    }

    @Test
    fun identicalModelKeepsRendered() {
        assertEquals(
            RemoteUrl,
            slockAvatarStickyImageModel(candidateImageModel = RemoteUrl, lastRenderedFrame = urlFrame())
        )
        val managed = managedFrame(subject = SubjectA)
        assertEquals(
            managed.model,
            slockAvatarStickyImageModel(candidateImageModel = managed.model, lastRenderedFrame = managed)
        )
    }

    @Test
    fun urlCandidateAfterManagedSuccessSwitches() {
        // A URL candidate is a real source decision (revert, fallback); stickiness never pins it.
        assertEquals(
            RemoteUrl,
            slockAvatarStickyImageModel(
                candidateImageModel = RemoteUrl,
                lastRenderedFrame = managedFrame(subject = SubjectA)
            )
        )
    }

    @Test
    fun managedCandidateAfterUrlSuccessKeepsUrlWhilePinned() {
        // The logged flash: url=success then a managed model for the same bytes re-renders through
        // a placeholder gap. Keep the already-decoded URL frame while the candidate is the commit
        // this frame belongs to.
        val managed = managedModel(subject = SubjectA)
        val pinnedCommit = parseSlockAvatarMountedRenderModel(managed)!!.commitIdentity
        assertEquals(
            RemoteUrl,
            slockAvatarStickyImageModel(
                candidateImageModel = managed,
                lastRenderedFrame = urlFrame(commit = pinnedCommit)
            )
        )
    }

    @Test
    fun managedCandidateAfterUrlSuccessKeepsUrlBeforeBaselineArmed() {
        // The mount's imageModel can emit after the URL frame succeeded; that first managed
        // candidate belongs to this frame and sticks (the composable then records it as the pin).
        val managed = managedModel(subject = SubjectA)
        assertEquals(
            RemoteUrl,
            slockAvatarStickyImageModel(candidateImageModel = managed, lastRenderedFrame = urlFrame())
        )
    }

    @Test
    fun managedSupersedingCommitAfterUrlSuccessSwitches() {
        // Bounded stickiness: a stable URL cannot pin the old frame once a different commit shows
        // up — that is a real avatar change and must re-render.
        val pinned = managedModel(subject = SubjectA, generation = 1L)
        val superseding = managedModel(subject = SubjectA, generation = 2L)
        val pinnedCommit = parseSlockAvatarMountedRenderModel(pinned)!!.commitIdentity
        assertEquals(
            superseding,
            slockAvatarStickyImageModel(
                candidateImageModel = superseding,
                lastRenderedFrame = urlFrame(commit = pinnedCommit)
            )
        )
    }

    @Test
    fun managedSameCommitKeepsRendered() {
        val rendered = managedFrame(subject = SubjectA, revision = 2L)
        val candidate = managedModel(subject = SubjectA, revision = 3L)
        assertEquals(
            rendered.model,
            slockAvatarStickyImageModel(candidateImageModel = candidate, lastRenderedFrame = rendered)
        )
    }

    @Test
    fun managedNewCommitSwitches() {
        val rendered = managedFrame(subject = SubjectA, generation = 1L)
        val candidate = managedModel(subject = SubjectA, generation = 2L)
        assertEquals(
            candidate,
            slockAvatarStickyImageModel(candidateImageModel = candidate, lastRenderedFrame = rendered)
        )
    }

    @Test
    fun managedDifferentSubjectSwitches() {
        val rendered = managedFrame(subject = SubjectA)
        val candidate = managedModel(subject = SubjectB)
        assertEquals(
            candidate,
            slockAvatarStickyImageModel(candidateImageModel = candidate, lastRenderedFrame = rendered)
        )
    }

    private companion object {
        const val RemoteUrl = "https://cdn.raft.build/avatars/agents/deadbeef.webp"

        const val SubjectA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SubjectB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        const val Partition = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val Bytes = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        const val Nonce = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"

        fun urlFrame(commit: SlockAvatarCommitIdentity? = null) =
            SlockAvatarRenderedFrame(model = RemoteUrl, urlBaselineCommit = commit)

        fun managedFrame(
            subject: String,
            generation: Long = 1L,
            revision: Long = 1L
        ) = SlockAvatarRenderedFrame(model = managedModel(subject, generation, revision))

        @OptIn(ExperimentalEncodingApi::class)
        fun managedModel(subject: String, generation: Long = 1L, revision: Long = 1L): String {
            val json =
                "{\"protocol\":\"slock_avatar_render_v1\"," +
                    "\"nativePartitionKey\":\"$Partition\"," +
                    "\"nativeSubjectKey\":\"$subject\"," +
                    "\"nativeBytesKey\":\"$Bytes\"," +
                    "\"commitGeneration\":\"$generation\"," +
                    "\"commitNonce\":\"$Nonce\"," +
                    "\"modelRevision\":\"$revision\"}"
            return "slock-avatar-render-v1:" + Base64.encode(json.encodeToByteArray())
        }
    }
}
