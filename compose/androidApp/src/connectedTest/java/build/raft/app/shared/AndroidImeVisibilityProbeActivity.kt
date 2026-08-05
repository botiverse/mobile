package build.raft.app.shared

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import android.os.SystemClock
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

class AndroidImeVisibilityProbeActivity : Activity() {
    private lateinit var editor: EditText
    private var showRequests = 0
    private var acceptedRequests = 0
    private var showRequested = false
    private var resultSent = false
    private val deadline = SystemClock.uptimeMillis() + ProbeTimeoutMs
    private val observe = Runnable { requestOnceAndObserveIme() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editor =
            EditText(this).apply {
                isSingleLine = true
                hint = "IME visibility probe"
            }
        setContentView(editor)
        editor.requestFocus()
        editor.post(observe)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::editor.isInitialized && !resultSent) {
            editor.removeCallbacks(observe)
            editor.post(observe)
        }
    }

    override fun onDestroy() {
        if (::editor.isInitialized) {
            editor.removeCallbacks(observe)
        }
        super.onDestroy()
    }

    private fun requestOnceAndObserveIme() {
        if (resultSent) return
        if (!showRequested && editor.hasFocus() && hasWindowFocus()) {
            showRequested = true
            showRequests += 1
            val inputMethodManager = getSystemService(InputMethodManager::class.java)
            if (inputMethodManager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)) {
                acceptedRequests += 1
            }
        }

        val imeVisible =
            window.decorView.rootWindowInsets
                ?.isVisible(WindowInsets.Type.ime()) == true
        if (showRequested && imeVisible) {
            sendResult(visible = true)
            return
        }
        if (SystemClock.uptimeMillis() >= deadline) {
            sendResult(visible = false)
            return
        }

        editor.postDelayed(observe, ObserveDelayMs)
    }

    private fun sendResult(visible: Boolean) {
        if (resultSent) return
        resultSent = true
        editor.removeCallbacks(observe)
        val result =
            Bundle().apply {
                putBoolean(ResultImeVisible, visible)
                putInt(ResultShowRequests, showRequests)
                putInt(ResultAcceptedRequests, acceptedRequests)
                putBoolean(ResultEditorFocused, editor.hasFocus())
                putBoolean(ResultWindowFocused, hasWindowFocus())
            }
        readResultReceiver(intent)?.send(if (visible) RESULT_OK else RESULT_CANCELED, result)
        if (visible) {
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun readResultReceiver(intent: Intent): ResultReceiver? =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(ExtraResultReceiver, ResultReceiver::class.java)
        } else {
            intent.getParcelableExtra(ExtraResultReceiver)
        }

    companion object {
        const val ExtraResultReceiver = "ime_visibility_probe_result_receiver"
        const val ResultImeVisible = "ime_visibility_probe_visible"
        const val ResultShowRequests = "ime_visibility_probe_show_requests"
        const val ResultAcceptedRequests = "ime_visibility_probe_accepted_requests"
        const val ResultEditorFocused = "ime_visibility_probe_editor_focused"
        const val ResultWindowFocused = "ime_visibility_probe_window_focused"
        private const val ProbeTimeoutMs = 15_000L
        private const val ObserveDelayMs = 100L
    }
}
