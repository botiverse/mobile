package build.raft.app

import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNull
import kotlin.test.assertSame

// CMP switch: moved from commonTest. The capture handle is now RaftViewCaptureRef (a GraphicsLayer
// handle with an internal constructor, not constructible off-device), so the slot's identity
// contract is exercised with opaque instances allocated without running the constructor.
// Robolectric: loading RaftViewCaptureRef initialises Compose's GraphicsLayer (its `$stable`
// stability field), whose static init reads android.os.Build since Compose UI 1.11; the plain
// android.jar stubs leave Build.FINGERPRINT null.
@RunWith(RobolectricTestRunner::class)
class RaftFilePreviewCaptureRefTest {
    @Test
    fun captureSlotBindsOnlyTheCurrentEditorOwner() {
        val firstOwner = captureOwner(actionId = "action-1", imageUrl = "file:///first.jpg")
        val nextOwner = captureOwner(actionId = "action-1", imageUrl = "file:///next.jpg")
        val firstRef = captureRef(nativeRef = 1)
        val nextRef = captureRef(nativeRef = 2)

        val firstSlot =
            RaftComposerImageEditCaptureSlot(firstOwner)
                .bind(owner = firstOwner, ref = firstRef)
        val nextSlot =
            RaftComposerImageEditCaptureSlot(nextOwner)
                .bind(owner = nextOwner, ref = nextRef)

        assertSame(firstRef, firstSlot.ref)
        assertSame(nextRef, nextSlot.ref)
        assertSame(nextSlot, nextSlot.bind(owner = firstOwner, ref = firstRef))
        assertSame(nextRef, nextSlot.ref)
    }

    @Test
    fun everyRealEditorSessionChangeStartsWithNoCaptureRef() {
        val base = captureOwner()
        val ownerChanges =
            listOf(
                base.copy(actionId = "action-2"),
                base.copy(attachmentId = "attachment-2"),
                base.copy(imageUrl = "file:///next.jpg"),
                base.copy(editMode = false),
            )

        ownerChanges.forEach { owner ->
            assertNull(RaftComposerImageEditCaptureSlot(owner).ref, "owner=$owner")
        }
    }

    private fun captureOwner(
        actionId: String = "action-1",
        attachmentId: String = "attachment-1",
        imageUrl: String = "file:///first.jpg",
        editMode: Boolean = true,
    ): RaftComposerImageEditCaptureOwner =
        RaftComposerImageEditCaptureOwner(
            actionId = actionId,
            attachmentId = attachmentId,
            imageUrl = imageUrl,
            editMode = editMode,
        )

    @Suppress("UNUSED_PARAMETER")
    private fun captureRef(nativeRef: Int): RaftViewCaptureRef {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, RaftViewCaptureRef::class.java) as RaftViewCaptureRef
    }
}
