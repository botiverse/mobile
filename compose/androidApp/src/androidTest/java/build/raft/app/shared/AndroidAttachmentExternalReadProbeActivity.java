package build.raft.app.shared;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ResultReceiver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Framework-only attachment reader that runs in a dedicated androidTest package process.
 *
 * <p>The external process cannot rely on the target APK's Kotlin or AndroidX classpath. Keeping
 * this fixture on Android/JDK APIs and out of the test package's default process prevents merged
 * library providers from starting before the probe, while preserving the distinct-UID
 * FileProvider grant oracle.
 */
public final class AndroidAttachmentExternalReadProbeActivity extends Activity {
    public static final String EXTRA_RESULT_RECEIVER = "attachment_read_result_receiver";
    public static final String EXTRA_READ_URI = "attachment_read_uri";
    public static final String EXTRA_READ_BYTES = "attachment_read_bytes";
    public static final String EXTRA_READ_ERROR = "attachment_read_error";
    public static final String EXTRA_UNGRANTED_URI = "attachment_ungranted_uri";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent launchIntent = getIntent();
        ResultReceiver receiver = readResultReceiver(launchIntent);
        Bundle result = new Bundle();
        int resultCode = RESULT_CANCELED;
        try {
            Uri uri = resolveUri(launchIntent);
            result.putString(EXTRA_READ_URI, uri.toString());
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    throw new IOException("Content resolver returned no input stream");
                }
                result.putByteArray(EXTRA_READ_BYTES, readAllBytes(input));
            }
            resultCode = RESULT_OK;
        } catch (Throwable throwable) {
            result.putString(EXTRA_READ_ERROR, throwable.getClass().getSimpleName());
        }
        if (receiver != null) {
            receiver.send(resultCode, result);
        }
        finish();
    }

    private static Uri resolveUri(Intent intent) {
        Uri uri = intent.getData();
        ClipData clipData = intent.getClipData();
        if (uri == null && clipData != null && clipData.getItemCount() > 0) {
            uri = clipData.getItemAt(0).getUri();
        }
        if (uri == null) {
            String ungrantedUri = intent.getStringExtra(EXTRA_UNGRANTED_URI);
            if (ungrantedUri != null) {
                uri = Uri.parse(ungrantedUri);
            }
        }
        if (uri == null) {
            throw new IllegalArgumentException("Attachment probe requires a URI");
        }
        return uri;
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    @SuppressWarnings("deprecation")
    private static ResultReceiver readResultReceiver(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver.class);
        }
        return intent.getParcelableExtra(EXTRA_RESULT_RECEIVER);
    }
}
