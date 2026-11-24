package android.webkit

/** Test double for [WebResourceError] (whose constructor is package-private in Android SDK). */
internal class TestWebResourceError(
    private val code: Int,
    private val message: CharSequence,
) : WebResourceError() {
    override fun getErrorCode(): Int = code

    override fun getDescription(): CharSequence = message
}
