package build.raft.app.shared

import android.text.Selection
import android.text.Spannable
import android.text.method.ArrowKeyMovementMethod
import android.text.method.MetaKeyKeyListener
import android.view.MotionEvent
import android.widget.TextView
import build.raft.app.core.data.diagnostics.SlockAppLog

/** Keeps Android's native cursor/selection behavior, recovering only its missing-touch-anchor bug. */
internal object SlockArrowKeyMovementMethod : ArrowKeyMovementMethod() {
    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        try {
            return super.onTouchEvent(widget, buffer, event)
        } catch (error: IndexOutOfBoundsException) {
            // Android 14 ArrowKeyMovementMethod reads its private LAST_TAP_DOWN span on
            // MOVE/UP while selecting. Entering selection mode after DOWN (or replacing
            // the Editable during the gesture) can leave that anchor absent: -1. The
            // current cursor can still be perfectly valid. Clamping setSelection(Int)
            // on the EditText cannot help: the framework calls Selection directly.
            //
            // Do not hide arbitrary app/renderer failures. Match the observed negative
            // span range AND the framework origin, and only at the touch-movement boundary.
            if ((event.actionMasked != MotionEvent.ACTION_UP &&
                    event.actionMasked != MotionEvent.ACTION_MOVE) ||
                error.message != "setSpan (-1 ... -1) starts before 0" ||
                !error.hasDirectFrameworkSelectionFailure()
            ) {
                throw error
            }

            // End only the invalid selection gesture; never replay the touch, replace
            // text, clear composition, move focus, or restart the IME. Keep a valid
            // selection (including direction) exactly as it was.
            MetaKeyKeyListener.resetMetaState(buffer)
            val start = Selection.getSelectionStart(buffer).coerceIn(0, buffer.length)
            val end = Selection.getSelectionEnd(buffer).coerceIn(0, buffer.length)
            Selection.setSelection(buffer, start, end)
            SlockAppLog.w(
                "SlockTextSelection",
                "touch_selection_anchor_missing action=${event.actionMasked} length=${buffer.length} " +
                    "selection=$start..$end"
            )
            return true
        }
    }
}

/** Reject callback re-entry: a SpanWatcher can throw the same negative-span error. */
private fun IndexOutOfBoundsException.hasDirectFrameworkSelectionFailure(): Boolean {
    val frames = stackTrace
    var index = 0
    fun at(className: String, methodName: String): Boolean =
        frames.getOrNull(index)?.let { it.className == className && it.methodName == methodName } == true

    if (!at("android.text.SpannableStringBuilder", "checkRange")) return false
    index++
    val spanStart = index
    while (at("android.text.SpannableStringBuilder", "setSpan")) index++
    if (index == spanStart) return false
    val selectionStart = index
    while (at("android.text.Selection", "setSelection")) index++
    if (index == selectionStart) return false
    return at("android.text.method.ArrowKeyMovementMethod", "onTouchEvent")
}
