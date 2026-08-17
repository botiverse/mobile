package build.raft.app.shared

import android.content.Context
import android.text.InputType
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
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
    private var suppressReturnKeySelectionChange = false

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
            PROP_RETURN_KEY_TYPE -> setReturnKeyType(propValue)
            else -> super.setProp(propKey, propValue)
        }

    override fun initSingleLine() {
        isSingleLine = false
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        if (suppressReturnKeySelectionChange) return
        super.onSelectionChanged(selStart, selEnd)
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

    private fun setReturnKeyType(value: Any): Boolean {
        val selectionStartBeforeReconfiguration = selectionStart
        val selectionEndBeforeReconfiguration = selectionEnd
        val method = transformationMethod
        // Android resets the live selection to 0 while setSingleLine reconfigures the
        // editor. Kuikly forwards onSelectionChanged synchronously, so an async
        // loading -> visible suggestion transition would otherwise publish that
        // transient value back into the controlled composer before we can restore it.
        suppressReturnKeySelectionChange = true
        try {
            isSingleLine = true
            transformationMethod = method
            maxLines = Integer.MAX_VALUE
            setHorizontallyScrolling(false)
        } finally {
            suppressReturnKeySelectionChange = false
        }
        restoreSelectionAfterReturnKeyReconfiguration(
            selectionStartBeforeReconfiguration,
            selectionEndBeforeReconfiguration
        )
        imeOptions =
            when (value as String) {
                "none" -> EditorInfo.IME_ACTION_NONE
                "search" -> EditorInfo.IME_ACTION_SEARCH
                "send" -> EditorInfo.IME_ACTION_SEND
                "done" -> EditorInfo.IME_ACTION_DONE
                "next" -> EditorInfo.IME_ACTION_NEXT
                "go" -> EditorInfo.IME_ACTION_GO
                "previous" -> EditorInfo.IME_ACTION_PREVIOUS
                else -> EditorInfo.IME_NULL
            }
        return true
    }

    private fun restoreSelectionAfterReturnKeyReconfiguration(start: Int, end: Int) {
        if (start < 0 || end < 0) return
        val textLength = text?.length ?: 0
        val boundedStart = start.coerceIn(0, textLength)
        val boundedEnd = end.coerceIn(0, textLength)
        if (selectionStart != boundedStart || selectionEnd != boundedEnd) {
            setSelection(boundedStart, boundedEnd)
        }
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
