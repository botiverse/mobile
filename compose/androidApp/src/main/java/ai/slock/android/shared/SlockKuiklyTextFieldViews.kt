package ai.slock.android.shared

import android.content.Context
import android.text.InputType
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import com.tencent.kuikly.core.render.android.expand.component.KRTextProps
import com.tencent.kuikly.core.render.android.expand.component.KRTextFieldView

private const val PROP_FONT_FAMILY = "fontFamily"
private const val PROP_KEYBOARD_TYPE = "keyboardType"
private const val PROP_SYSTEM_NEWLINE_ACTION = "slockSystemNewlineAction"
private const val KEYBOARD_TYPE_EMAIL = "email"
private const val MENU_ITEM_INSERT_NEWLINE = 0x534C0A

internal class SlockKuiklyTextFieldView(
    context: Context,
    softInputMode: Int?
) : KRTextFieldView(context, softInputMode) {
    private var resolvedFontFamily: String? = null
    private var allowsMultipleLines = false
    private var systemNewlineActionEnabled = false
    private val newlineActionModeCallback = NewlineActionModeCallback()

    init {
        applySlockInputMetrics()
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
                applySystemNewlineActionMode()
                true
            }
            KRTextProps.PROP_KEY_NUMBER_OF_LINES -> {
                allowsMultipleLines = (propValue as? Int ?: 1) != 1
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
        editable.replace(minOf(start, end), maxOf(start, end), "\n")
    }

    private inner class NewlineActionModeCallback : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            ensureNewlineMenuItem(menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            ensureNewlineMenuItem(menu)
            return false
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            if (item?.itemId != MENU_ITEM_INSERT_NEWLINE) return false
            insertNewlineAtSelection()
            mode?.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode?) = Unit

        private fun ensureNewlineMenuItem(menu: Menu?) {
            if (!systemNewlineActionEnabled || !allowsMultipleLines || menu == null) return
            if (menu.findItem(MENU_ITEM_INSERT_NEWLINE) != null) return
            menu.add(Menu.NONE, MENU_ITEM_INSERT_NEWLINE, Menu.NONE, "换行")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
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
