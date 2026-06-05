package ai.slock.android.shared

import android.content.Context
import android.text.InputType
import com.tencent.kuikly.core.render.android.expand.component.KRTextFieldView

private const val PROP_FONT_FAMILY = "fontFamily"
private const val PROP_KEYBOARD_TYPE = "keyboardType"
private const val KEYBOARD_TYPE_EMAIL = "email"

internal class SlockKuiklyTextFieldView(
    context: Context,
    softInputMode: Int?
) : KRTextFieldView(context, softInputMode) {
    private var resolvedFontFamily: String? = null

    override fun setProp(propKey: String, propValue: Any): Boolean =
        when (propKey) {
            PROP_FONT_FAMILY -> {
                resolvedFontFamily = propValue as? String
                applyResolvedTypeface()
                true
            }
            PROP_KEYBOARD_TYPE ->
                if (propValue == KEYBOARD_TYPE_EMAIL) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    applyResolvedTypeface()
                    true
                } else {
                    super.setProp(propKey, propValue)
                }
            else -> super.setProp(propKey, propValue)
        }

    private fun applyResolvedTypeface() {
        typeface = SlockKuiklyFontAdapter.resolveTypeface(resolvedFontFamily, context)
    }
}
