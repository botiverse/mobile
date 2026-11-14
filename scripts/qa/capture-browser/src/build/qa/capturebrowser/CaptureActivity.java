package build.qa.capturebrowser;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Records the URL an app asked the browser to open, then closes. Writes files/captured_url
 * atomically (tmp + rename) so a reader never sees half a URL. Logs nothing: the URL names a
 * pending sign-in request.
 */
public class CaptureActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        capture(getIntent());
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        capture(intent);
        finish();
    }

    private void capture(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri data = intent.getData();
        if (data == null) return;
        String scheme = data.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return;
        File dir = getFilesDir();
        File tmp = new File(dir, "captured_url.tmp");
        File out = new File(dir, "captured_url");
        try (FileOutputStream stream = new FileOutputStream(tmp)) {
            stream.write(data.toString().getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        } catch (Exception ignored) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(out);
    }
}
