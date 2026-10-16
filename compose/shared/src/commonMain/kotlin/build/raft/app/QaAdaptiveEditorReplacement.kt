package build.raft.app

import com.tencent.kuikly.core.exception.PagerNotFoundException
import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.views.AutoHeightTextAreaView

/**
 * QA carrier only; never merge into the product branch.
 *
 * Recreates the native renderer view while retaining the real DSL editor, native ref,
 * parent, layout node, attributes, event handlers and measurement shadow. Invoke on the
 * pager's existing context thread after the final shared draft read, immediately before
 * the ordinary asynchronous adaptive commit. The pinned 2.24.0-raft.7 renderer queues
 * these view operations and that module call in the same ordered UI batch; do not flush
 * or suspend between them.
 *
 * A true result means recreation was enqueued, not that UIKit identity or editor state
 * has been verified. Cached attributes/frame are replayed by Kuikly; native QA must
 * separately verify the replacement object, live text, selection and source context.
 */
internal fun qaRecreateAdaptiveEditor(pagerId: String, expectedRef: Int): Boolean {
    if (pagerId.isBlank() || expectedRef <= 0) return false
    val pager = try {
        PagerManager.getPager(pagerId)
    } catch (_: PagerNotFoundException) {
        return false
    }
    val editor = pager.getViewWithNativeRef(expectedRef) as? AutoHeightTextAreaView
        ?: return false
    if (editor.singleLine || editor.pagerId != pagerId) return false
    val parent = editor.parent ?: return false
    val oldRenderView = editor.renderView ?: return false

    // insertSubRenderView supports virtual parents. Check the existing real render
    // ancestor before removal so a detached logical node cannot lose its native view.
    var renderParent = parent
    while (!renderParent.isRenderView()) {
        renderParent = renderParent.parent ?: return false
    }
    if (renderParent.renderView == null ||
        renderParent.renderChildren().none { it === editor }
    ) return false

    editor.removeRenderView()
    parent.insertSubRenderView(editor)

    return editor.renderView != null && editor.renderView !== oldRenderView &&
        editor.nativeRef == expectedRef && pager.getViewWithNativeRef(expectedRef) === editor
}
