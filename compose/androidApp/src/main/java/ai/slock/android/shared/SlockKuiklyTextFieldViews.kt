package ai.slock.android.shared

import android.content.Context
import android.text.InputType
import android.view.Gravity
import com.tencent.kuikly.core.render.android.expand.component.KRTextProps
import com.tencent.kuikly.core.render.android.expand.component.KRTextFieldView

private const val PROP_FONT_FAMILY = "fontFamily"
private const val PROP_KEYBOARD_TYPE = "keyboardType"
private const val KEYBOARD_TYPE_EMAIL = "email"

internal class SlockKuiklyTextFieldView(
    context: Context,
    softInputMode: Int?
) : KRTextFieldView(context, softInputMode) {
    private var resolvedFontFamily: String? = null
    private var allowsMultipleLines = false

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
            KRTextProps.PROP_KEY_NUMBER_OF_LINES -> {
                allowsMultipleLines = (propValue as? Int ?: 1) != 1
                super.setProp(propKey, propValue).also {
                    applySlockInputMetrics()
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

    private fun applyResolvedTypeface() {
        typeface = SlockKuiklyFontAdapter.resolveTypeface(resolvedFontFamily, context)
    }

    private fun applySlockInputMetrics() {
        setIncludeFontPadding(false)
        val horizontalGravity = gravity and Gravity.HORIZONTAL_GRAVITY_MASK
        val verticalGravity = if (allowsMultipleLines) Gravity.TOP else Gravity.CENTER_VERTICAL
        gravity = horizontalGravity or verticalGravity
    }
}
