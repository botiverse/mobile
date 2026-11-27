package android.webkit

/** Package-private constructor bridge for Robolectric tests in `:web-container`. */
class TestWebResourceError(
    private val code: Int,
    private val descriptionText: CharSequence,
) : WebResourceError() {
    override fun getErrorCode(): Int = code

    override fun getDescription(): CharSequence = descriptionText
}
