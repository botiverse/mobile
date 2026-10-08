package build.raft.app.shared;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.ResultReceiver;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.security.MessageDigest;

/** Framework-only distinct-UID reader for the public Downloads instrumentation contract. */
public final class AndroidAttachmentPublicDownloadsExternalReadProbeActivity extends Activity {
    public static final String EXTRA_RECEIVER = "build.raft.app.extra.PUBLIC_DOWNLOAD_RECEIVER";
    public static final String RESULT_UID = "probeUid";
    public static final String RESULT_SIZE = "sizeBytes";
    public static final String RESULT_SHA256 = "sha256";
    public static final String RESULT_ERROR = "errorType";
    public static final String RESULT_ERROR_MESSAGE = "errorMessage";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ResultReceiver receiver = getIntent().getParcelableExtra(EXTRA_RECEIVER);
        Bundle result = new Bundle();
        result.putInt(RESULT_UID, Process.myUid());
        int resultCode = RESULT_CANCELED;
        try {
            Uri uri = getIntent().getData();
            if (uri == null) throw new FileNotFoundException("missing data URI");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long sizeBytes = 0L;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new FileNotFoundException("missing input stream");
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    digest.update(buffer, 0, read);
                    sizeBytes += read;
                }
            }
            result.putLong(RESULT_SIZE, sizeBytes);
            result.putString(RESULT_SHA256, hex(digest.digest()));
            resultCode = RESULT_OK;
        } catch (Throwable throwable) {
            result.putString(RESULT_ERROR, throwable.getClass().getSimpleName());
            // The message names the denying check (e.g. MediaProvider ownership vs URI grant);
            // the class name alone cannot distinguish them.
            result.putString(RESULT_ERROR_MESSAGE, String.valueOf(throwable.getMessage()));
        }
        if (receiver != null) receiver.send(resultCode, result);
        finish();
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) value.append(String.format("%02x", current & 0xff));
        return value.toString();
    }
}
