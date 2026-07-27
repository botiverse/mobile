package build.raft.app.shared

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver

class AndroidAttachmentExternalReadProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val receiver = intent.readResultReceiver()
        val result = Bundle()
        val readResult =
            runCatching {
                val uri =
                    checkNotNull(
                        intent.data
                            ?: intent.clipData?.getItemAt(0)?.uri
                            ?: intent.getStringExtra(EXTRA_UNGRANTED_URI)?.let(Uri::parse)
                    )
                result.putString(EXTRA_READ_URI, uri.toString())
                result.putByteArray(
                    EXTRA_READ_BYTES,
                    contentResolver.openInputStream(uri)!!.use { input -> input.readBytes() }
                )
            }
        readResult.exceptionOrNull()?.let { result.putString(EXTRA_READ_ERROR, it::class.java.simpleName) }
        receiver?.send(if (readResult.isSuccess) RESULT_OK else RESULT_CANCELED, result)
        finish()
    }

    @Suppress("DEPRECATION")
    private fun android.content.Intent.readResultReceiver(): ResultReceiver? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver::class.java)
        } else {
            getParcelableExtra(EXTRA_RESULT_RECEIVER)
        }

    companion object {
        const val EXTRA_RESULT_RECEIVER = "attachment_read_result_receiver"
        const val EXTRA_READ_URI = "attachment_read_uri"
        const val EXTRA_READ_BYTES = "attachment_read_bytes"
        const val EXTRA_READ_ERROR = "attachment_read_error"
        const val EXTRA_UNGRANTED_URI = "attachment_ungranted_uri"
    }
}
