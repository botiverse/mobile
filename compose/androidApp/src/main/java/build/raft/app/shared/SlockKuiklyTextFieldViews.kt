package build.raft.app.shared

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.method.MovementMethod
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import build.raft.app.core.data.diagnostics.SlockAppLog
import build.raft.app.localizedThreadComposerInsertNewlineTitle
import com.tencent.kuikly.core.render.android.css.ktx.toDpF
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import com.tencent.kuikly.core.render.android.expand.component.KRTextAreaView
import com.tencent.kuikly.core.render.android.expand.component.KRTextProps
import com.tencent.kuikly.core.render.android.expand.component.KRTextFieldView

private const val PROP_FONT_FAMILY = "fontFamily"
private const val PROP_KEYBOARD_TYPE = "keyboardType"
private const val PROP_RETURN_KEY_TYPE = "returnKeyType"
private const val PROP_SYSTEM_NEWLINE_ACTION = "slockSystemNewlineAction"
private const val PROP_IME_ACTION_REQUESTED = "slockImeActionRequested"

// KRTextFieldView dispatches this through call(method, params, callback) but keeps its own
// constant private, so the literal is duplicated here. Verified against the pinned artifact
// core-render-android-2.28.0-raft.1-2.1.21: KRTextFieldView.METHOD_GET_TEXT_INPUT_STATE
// = "getTextInputState", handled in the call(String, String, Function1) overload this class
// overrides. If a Kuikly bump renames it, this interception silently stops running — the
// connected test asserts the composition is finished, which is what would catch that.
private const val METHOD_GET_TEXT_INPUT_STATE = "getTextInputState"
private const val KEYBOARD_TYPE_EMAIL = "email"
private const val MENU_ITEM_INSERT_NEWLINE = 0x534C0A
private const val LOG_TAG = "SlockComposerNewline"

internal open class SlockKuiklyTextFieldView(
    context: Context,
    softInputMode: Int?
) : KRTextFieldView(context, softInputMode) {
    private var resolvedFontFamily: String? = null
    private var allowsMultipleLines = false
    private var systemNewlineActionEnabled = false
    private val newlineActionModeCallback = NewlineActionModeCallback()

    init {
        applySlockInputMetrics()
        // Kuikly's BasicTextField does not forward the compose textStyle fontFamily
        // to the native field, and the container-scoped applySlockInputTypeface()
        // traversal (SlockKuiklyActivity) only reaches inputs under
        // R.id.slock_kuikly_container — it misses fields rendered in dialog/popup
        // roots (e.g. the create-channel dialog). Apply the brand input typeface
        // (Space Grotesk) at construction so every field defaults to it regardless
        // of where it is mounted; an explicit fontFamily prop still overrides via
        // applyResolvedTypeface().
        applyResolvedTypeface()
    }

    override fun getDefaultMovementMethod(): MovementMethod = SlockArrowKeyMovementMethod

    override fun setProp(propKey: String, propValue: Any): Boolean =
        when (propKey) {
            PROP_FONT_FAMILY -> {
                resolvedFontFamily = propValue as? String
                applyResolvedTypeface()
                true
            }
            PROP_SYSTEM_NEWLINE_ACTION -> {
                systemNewlineActionEnabled = propValue.toSlockBoolean()
                logState("setProp systemNewlineAction raw=${propValue.describeSlockPropValue()}")
                applySystemNewlineActionMode()
                true
            }
            KRTextProps.PROP_KEY_NUMBER_OF_LINES -> {
                val lineCount = propValue.toSlockLineCount()
                allowsMultipleLines = lineCount != 1
                logState(
                    "setProp numberOfLines raw=${propValue.describeSlockPropValue()} parsed=$lineCount"
                )
                super.setProp(propKey, propValue).also {
                    applySlockInputMetrics()
                    applySystemNewlineActionMode()
                }
            }
            PROP_KEYBOARD_TYPE ->
                if (propValue == KEYBOARD_TYPE_EMAIL) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    applySlockInputMetrics()
                    applyResolvedTypeface()
                    true
                } else {
                    super.setProp(propKey, propValue).also {
                        applySlockInputMetrics()
                    }
                }
            else -> super.setProp(propKey, propValue).also {
                applySlockInputMetrics()
            }
        }

    override fun resetProp(propKey: String): Boolean =
        when (propKey) {
            PROP_SYSTEM_NEWLINE_ACTION -> {
                systemNewlineActionEnabled = false
                logState("resetProp systemNewlineAction")
                applySystemNewlineActionMode()
                true
            }
            else -> super.resetProp(propKey)
        }

    private fun applyResolvedTypeface() {
        typeface = SlockKuiklyFontAdapter.resolveTypeface(resolvedFontFamily, context)
    }

    private fun applySlockInputMetrics() {
        val horizontalGravity = gravity and Gravity.HORIZONTAL_GRAVITY_MASK
        val verticalGravity = if (allowsMultipleLines) Gravity.TOP else Gravity.CENTER_VERTICAL
        gravity = horizontalGravity or verticalGravity
    }

    private fun applySystemNewlineActionMode() {
        val callback = newlineActionModeCallback.takeIf { systemNewlineActionEnabled && allowsMultipleLines }
        customInsertionActionModeCallback = callback
        customSelectionActionModeCallback = callback
        logState("applyActionMode installed=${callback != null}")
    }

    private fun insertNewlineAtSelection() {
        val editable = editableText ?: return
        val length = editable.length
        val fallback = when {
            selectionStart >= 0 -> selectionStart
            selectionEnd >= 0 -> selectionEnd
            else -> length
        }.coerceIn(0, length)
        val start = selectionStart.takeIf { it >= 0 }?.coerceIn(0, length) ?: fallback
        val end = selectionEnd.takeIf { it >= 0 }?.coerceIn(0, length) ?: fallback
        logState("insertNewline start=$start end=$end textLength=$length")
        editable.replace(minOf(start, end), maxOf(start, end), "\n")
    }

    override fun performLongClick(): Boolean {
        logState("performLongClick")
        return super.performLongClick() || startNewlineActionModeFallback("performLongClick")
    }

    override fun performLongClick(x: Float, y: Float): Boolean {
        logState("performLongClick x=$x y=$y")
        return super.performLongClick(x, y) || startNewlineActionModeFallback("performLongClickAt")
    }

    private fun startNewlineActionModeFallback(source: String): Boolean {
        if (!systemNewlineActionEnabled || !allowsMultipleLines) {
            logState("actionModeFallback skipped source=$source")
            return false
        }
        val started = startActionMode(newlineActionModeCallback) != null
        logState("actionModeFallback source=$source started=$started")
        return started
    }

    private fun logState(message: String) {
        SlockAppLog.d(
            LOG_TAG,
            "$message enabled=$systemNewlineActionEnabled multiline=$allowsMultipleLines " +
                "maxLines=$maxLines singleLine=$isSingleLine selection=$selectionStart..$selectionEnd"
        )
    }

    private inner class NewlineActionModeCallback : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            logState("onCreateActionMode before menu=${menu.describeSlockMenu()}")
            ensureNewlineMenuItem(menu)
            logState("onCreateActionMode after menu=${menu.describeSlockMenu()}")
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            logState("onPrepareActionMode before menu=${menu.describeSlockMenu()}")
            ensureNewlineMenuItem(menu)
            logState("onPrepareActionMode after menu=${menu.describeSlockMenu()}")
            return false
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            logState(
                "onActionItemClicked item=${item?.itemId} title=${item?.title?.toString().orEmpty()}"
            )
            if (item?.itemId != MENU_ITEM_INSERT_NEWLINE) return false
            insertNewlineAtSelection()
            mode?.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            logState("onDestroyActionMode")
        }

        private fun ensureNewlineMenuItem(menu: Menu?) {
            if (!systemNewlineActionEnabled || !allowsMultipleLines || menu == null) {
                logState("ensureNewlineMenuItem skipped menuNull=${menu == null}")
                return
            }
            if (menu.findItem(MENU_ITEM_INSERT_NEWLINE) != null) {
                logState("ensureNewlineMenuItem already-present")
                return
            }
            val title = newlineMenuTitle()
            menu.add(Menu.NONE, MENU_ITEM_INSERT_NEWLINE, Menu.NONE, title)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            logState("ensureNewlineMenuItem added title=$title")
        }

        private fun newlineMenuTitle(): String =
            localizedThreadComposerInsertNewlineTitle(context)
    }
}

internal class SlockKuiklyTextAreaView(
    context: Context,
    softInputMode: Int?
) : SlockKuiklyTextFieldView(context, softInputMode) {
    private var returnKeyStructureConfigured = false

    // A multi-line EditText suppresses the IME action key, so claiming the single-line
    // declaration is the only way to show one. Default to true so any caller that has not
    // migrated to PROP_IME_ACTION_REQUESTED keeps the previous behaviour; the composer sends
    // false whenever its "return key sends" preference is off, which is what lets Enter insert
    // a newline instead of rendering Done.
    private var imeActionRequested = true

    // The structural pass may only flip isSingleLine before the editor is wired to the IME;
    // afterwards the reset publishes a transient same-text 0..0 selection to the composer. This
    // tracks that boundary so the pass can be corrected during setup without reopening that hole.
    private var editingConnected = false

    // Retained so a state read can finish composition on the live connection. The base class does
    // not expose one, and creating a throwaway BaseInputConnection would not reach the IME that
    // actually owns the composition.
    private var activeInputConnection: InputConnection? = null

    init {
        gravity = Gravity.LEFT or Gravity.TOP
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        editingConnected = true
        return super.onCreateInputConnection(outAttrs).also { activeInputConnection = it }
    }

    // The IME is gone once the view leaves the window, but the field survives: Kuikly pins and
    // reuses editor views across mounts, so without this the next read would call
    // finishComposingText() on a connection whose IME no longer exists. That call returns false
    // rather than throwing, which is precisely the silent "barrier ran" reading this class exists
    // to prevent.
    override fun onDetachedFromWindow() {
        activeInputConnection = null
        super.onDetachedFromWindow()
    }

    override fun onDestroy() {
        activeInputConnection = null
        super.onDestroy()
    }

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? =
        when (method) {
            KRTextAreaView.METHOD_GET_INNER_CONTENT_HEIGHT -> getContentHeight(callback)
            METHOD_GET_TEXT_INPUT_STATE -> {
                // Send reads the editor through this method (ThreadComposerPage's
                // sendComposerMessage -> readCurrentValue), and that reader documents itself as
                // "an explicit barrier at an action boundary". It was not one: an IME that still
                // holds an unfinished composition keeps that text in the Editable, so the value
                // handed to Send included characters the user never committed — the reported
                // voice-input defect.
                //
                // Finishing composition here makes the barrier real for every caller of this
                // method at once, instead of asking each call site to remember. The renderer
                // cannot do it for us: in the pinned core-render-android artifact,
                // KRTextFieldView.createTextInputStateParamMap() builds the reply from
                // getText()/getSelectionStart()/getSelectionEnd() and writes compositionStart and
                // compositionEnd as the literal -1 (TextInputState.NO_COMPOSITION). The composition
                // fields exist in the payload but are never populated, so a shared-side gate on
                // TextFieldValue.composition would be dead code on Android — it can only ever be
                // null. The editor is the only layer that can see the composing spans.
                //
                // The result is deliberately not used to refuse the read. The reply channel cannot
                // express failure: TextInputState.decode(null) returns TextInputState(text = "")
                // rather than null, so a "fail closed" that skips super.call() would hand the
                // composer an empty value and wipe the user's draft, and one that never invokes the
                // callback would leave sendSnapshotGate open and disable sending. Refusal is more
                // destructive than the defect, so the barrier is made to always succeed instead and
                // a survived composition is reported as the invariant violation it would be.
                finishComposingBeforeStateRead()
                super.call(method, params, callback)
            }
            else -> super.call(method, params, callback)
        }

    /**
     * Ends any in-flight IME composition so the next state read sees no composing region.
     *
     * The authority is the editor's own composing spans, not the return value of
     * [InputConnection.finishComposingText] and not the presence of a connection: a dropped or
     * refused connection reports failure the same way it reports "nothing to do", so trusting
     * either would let an unfinished composition through as if the barrier had run.
     *
     * [InputConnection.finishComposingText] is attempted first because it also tells the IME the
     * composition is over. [BaseInputConnection.removeComposingSpans] is the fallback rather than
     * the primary: it ends the composition in the editor but cannot notify an IME, so a live IME
     * would be free to re-apply spans over text Send has already taken. It is safe exactly where
     * it is used — after the connection is absent or has refused, i.e. when no live IME owns the
     * composition. Neither call removes characters; both make the composing text ordinary text.
     *
     * @return true when the editor holds no composing region on return.
     */
    private fun finishComposingBeforeStateRead(): Boolean {
        val editable = editableText ?: return true
        if (!hasComposingRegion(editable)) return true

        activeInputConnection?.let { connection ->
            if (!connection.finishComposingText()) {
                // A stale connection returns false rather than throwing. Drop it so a later read
                // does not keep calling a dead object and silently believe composition was ended.
                activeInputConnection = null
                SlockAppLog.w(
                    LOG_TAG,
                    "finishComposingText returned false before state read; connection dropped"
                )
            }
        }
        if (!hasComposingRegion(editable)) return true

        BaseInputConnection.removeComposingSpans(editable)
        val ended = !hasComposingRegion(editable)
        if (!ended) {
            SlockAppLog.e(
                LOG_TAG,
                "composing region survived both finishComposingText and removeComposingSpans"
            )
        }
        return ended
    }

    private fun hasComposingRegion(editable: Editable): Boolean {
        val start = BaseInputConnection.getComposingSpanStart(editable)
        val end = BaseInputConnection.getComposingSpanEnd(editable)
        return start >= 0 && end > start
    }

    override fun setProp(propKey: String, propValue: Any): Boolean =
        when (propKey) {
            PROP_IME_ACTION_REQUESTED -> {
                val requested = propValue.toSlockBoolean()
                if (requested != imeActionRequested && returnKeyStructureConfigured && editingConnected) {
                    // The structural pass already ran and the editor is connected, so re-running it
                    // would flip isSingleLine while the controlled editing callbacks are live —
                    // exactly the transient 0..0 selection the guard exists to prevent. Before the
                    // connection exists the pass is simply redone, so only this late case warns.
                    //
                    // Do not reintroduce an ordering assumption here. Chain position was once
                    // believed to order these props; device logcat disproved it (arrival is
                    // numberOfLines -> systemNewlineAction -> imeActionRequested, the reverse of
                    // the chain), and that assumption is what shipped the regression.
                    //
                    // Keep this warning. A late preference change is otherwise invisible: it
                    // throws nothing, fails no test, and shows up only as an imeOptions value on
                    // a device. This line is the sole grep-able trace of it, and it has already
                    // been the thing that identified the arrival-order defect in one run instead
                    // of a rebuild-and-guess cycle.
                    SlockAppLog.w(
                        LOG_TAG,
                        "imeActionRequested changed to $requested after structure was configured " +
                            "with $imeActionRequested; return key mode is stale"
                    )
                }
                val changed = requested != imeActionRequested
                imeActionRequested = requested
                SlockAppLog.d(
                    LOG_TAG,
                    "setProp imeActionRequested raw=${propValue.describeSlockPropValue()} " +
                        "configured=$returnKeyStructureConfigured connected=$editingConnected"
                )
                // The host cannot order this ahead of numberOfLines: measured prop arrival is
                // numberOfLines -> systemNewlineAction -> imeActionRequested, the reverse of the
                // modifier chain, and numberOfLines is what triggers the structural pass. So the
                // first pass necessarily runs on the default. Re-run it while the editor is still
                // unconnected, which is the window the pass actually has to land in.
                if (changed && !editingConnected) {
                    returnKeyStructureConfigured = false
                    configureReturnKeyStructure()
                }
                true
            }
            PROP_RETURN_KEY_TYPE -> {
                // Keep the one-time structural setup separate from the renderer's return-key
                // contract. KRTextFieldView preserves the non-action IME flags and restarts an
                // active input connection after changing the action; delegating here keeps a
                // replacement editor's first Send connection in sync without rebuilding the
                // TextView or leaking a transient selection callback.
                configureReturnKeyStructure()
                super.setProp(propKey, propValue)
            }
            KRTextProps.PROP_KEY_NUMBER_OF_LINES -> {
                val applied = super.setProp(propKey, propValue)
                if (applied && propValue.toSlockLineCount() != 1) {
                    configureReturnKeyStructure()
                }
                applied
            }
            else -> super.setProp(propKey, propValue)
        }

    override fun initSingleLine() {
        isSingleLine = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            computeVerticalScrollRange() > computeVerticalScrollExtent()
        ) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.onTouchEvent(event)
    }

    private fun getContentHeight(callback: KuiklyRenderCallback?) {
        callback?.invoke(
            mapOf("height" to kuiklyRenderContext.toDpF(layout.height.toFloat()))
        )
    }

    /**
     * Invariant: the line mode is a function of the current preference, re-derived on every
     * relevant prop until the input connection is created, and frozen afterwards.
     *
     * That makes the outcome independent of prop arrival order rather than dependent on a
     * favourable one. Measured arrival is numberOfLines -> systemNewlineAction ->
     * imeActionRequested, the reverse of the modifier chain, and all three land within ~34ms and
     * well before the connection exists, so every order converges on the same result.
     */
    private fun configureReturnKeyStructure() {
        if (returnKeyStructureConfigured) return
        val method = transformationMethod
        // Android resets the editor's structure when isSingleLine changes. Do this once, before
        // controlled editing callbacks are connected, so a later IME-action-only update cannot
        // publish a transient same-text selection 0..0 to the composer.
        //
        // The single-line declaration is also what makes the IME offer an action key: a
        // multi-line field suppresses it. Claiming it unconditionally is what made Enter render
        // as Done with no way to insert a newline, because the composer declares numberOfLines=4
        // and that very declaration routes here. Take the mode from the host instead, still
        // exactly once, so "return key sends" off leaves the field multi-line.
        isSingleLine = imeActionRequested
        transformationMethod = method
        maxLines = Integer.MAX_VALUE
        setHorizontallyScrolling(false)
        returnKeyStructureConfigured = true
    }
}

private fun Any?.toSlockBoolean(): Boolean =
    when (this) {
        is Boolean -> this
        is Number -> toInt() != 0
        is String -> this == "1" || equals("true", ignoreCase = true)
        else -> false
    }

internal fun Any?.toSlockLineCount(defaultValue: Int = 1): Int =
    when (this) {
        is Number -> toInt()
        is String -> {
            val value = trim()
            value.toIntOrNull() ?: value.toDoubleOrNull()?.toInt() ?: defaultValue
        }
        else -> defaultValue
    }

private fun Any?.describeSlockPropValue(): String =
    when (this) {
        null -> "null"
        else -> "${this::class.simpleName}:$this"
    }

private fun Menu?.describeSlockMenu(): String {
    if (this == null) return "null"
    return buildString {
        append("size=")
        append(size())
        append(" [")
        for (index in 0 until size()) {
            if (index > 0) append(", ")
            val item = getItem(index)
            append(item.itemId)
            append(":")
            append(item.title)
        }
        append("]")
    }
}
