package build.raft.app.shared

import android.content.Context
import android.text.InputType
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
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

    init {
        gravity = Gravity.LEFT or Gravity.TOP
    }

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? =
        when (method) {
            KRTextAreaView.METHOD_GET_INNER_CONTENT_HEIGHT -> getContentHeight(callback)
            else -> super.call(method, params, callback)
        }

    override fun setProp(propKey: String, propValue: Any): Boolean =
        when (propKey) {
            PROP_IME_ACTION_REQUESTED -> {
                val requested = propValue.toSlockBoolean()
                if (requested != imeActionRequested && returnKeyStructureConfigured) {
                    // The structural pass already ran with the previous value, and re-running it
                    // would flip isSingleLine after the controlled editing callbacks are live —
                    // exactly the transient 0..0 selection the one-time guard exists to prevent.
                    //
                    // Ordering ahead of numberOfLines is guaranteed: both are setProp nodes on the
                    // same modifier chain, applied in chain order. Ordering ahead of returnKeyType
                    // is NOT — Kuikly emits that one from keyboardOptions.imeAction through a
                    // different path, and its branch also runs the structural pass. If it lands
                    // first the mode is fixed at the default, so this warning is the detector for
                    // that case rather than proof it cannot happen.
                    SlockAppLog.w(
                        LOG_TAG,
                        "imeActionRequested changed to $requested after structure was configured " +
                            "with $imeActionRequested; return key mode is stale"
                    )
                }
                imeActionRequested = requested
                SlockAppLog.d(
                    LOG_TAG,
                    "setProp imeActionRequested raw=${propValue.describeSlockPropValue()} " +
                        "configured=$returnKeyStructureConfigured"
                )
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
