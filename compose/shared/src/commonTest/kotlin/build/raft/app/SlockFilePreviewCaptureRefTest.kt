package build.raft.app

import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.ViewRef
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class SlockFilePreviewCaptureRefTest {
    @Test
    fun captureSlotBindsOnlyTheCurrentEditorOwner() {
        val firstOwner = captureOwner(actionId = "action-1", imageUrl = "file:///first.jpg")
        val nextOwner = captureOwner(actionId = "action-1", imageUrl = "file:///next.jpg")
        val firstRef = captureRef(nativeRef = 1)
        val nextRef = captureRef(nativeRef = 2)

        val firstSlot =
            SlockComposerImageEditCaptureSlot(firstOwner)
                .bind(owner = firstOwner, ref = firstRef)
        val nextSlot =
            SlockComposerImageEditCaptureSlot(nextOwner)
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
            assertNull(SlockComposerImageEditCaptureSlot(owner).ref, "owner=$owner")
        }
    }

    private fun captureOwner(
        actionId: String = "action-1",
        attachmentId: String = "attachment-1",
        imageUrl: String = "file:///first.jpg",
        editMode: Boolean = true,
    ): SlockComposerImageEditCaptureOwner =
        SlockComposerImageEditCaptureOwner(
            actionId = actionId,
            attachmentId = attachmentId,
            imageUrl = imageUrl,
            editMode = editMode,
        )

    private fun captureRef(nativeRef: Int): ViewRef<DeclarativeBaseView<*, *>> =
        ViewRef(pagerId = "capture-test", nativeRef = nativeRef)
}
