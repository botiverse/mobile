import android.net.Uri;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import com.android.uiautomator.core.UiObject;
import com.android.uiautomator.core.UiSelector;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Runs only on the job-owned Android emulator. Input stays on stdin and in the browser form. */
public final class RaftQaLoginForm extends UiAutomatorTestCase {
    private static final String CHROME = "com.android.chrome";

    private static final class ChromeNode extends UiObject {
        ChromeNode(String resourceId) {
            super(new UiSelector().packageName(CHROME).resourceId(resourceId));
        }

        AccessibilityNodeInfo current() {
            return findAccessibilityNodeInfo(0);
        }
    }

    private static boolean inWebContent(AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo parent = node.getParent(); parent != null; parent = parent.getParent()) {
            if ("android.webkit.WebView".equals(parent.getClassName())) return true;
        }
        return false;
    }

    // Compare the native toolbar to the configured QA site. Chrome can elide the scheme and path.
    private boolean atExpectedSite(int window) {
        AccessibilityNodeInfo address = new ChromeNode(CHROME + ":id/url_bar").current();
        if (address == null || address.getWindowId() != window || address.getText() == null || inWebContent(address)) return false;
        Uri expected = Uri.parse(getParams().getString("origin"));
        String displayed = address.getText().toString().trim();
        Uri actual = Uri.parse(displayed.contains("://") ? displayed : expected.getScheme() + "://" + displayed);
        return expected.getScheme().equals(actual.getScheme()) && expected.getEncodedAuthority().equals(actual.getEncodedAuthority());
    }

    private boolean fill(String id, boolean password, String value) {
        AccessibilityNodeInfo field = new ChromeNode(id).current();
        if (field == null || !field.isEditable() || !field.isEnabled() || !field.isVisibleToUser()
                || field.isPassword() != password || !inWebContent(field)) return false;
        if (!atExpectedSite(field.getWindowId())) {
            System.out.println("QA_LOGIN_SITE_UNVERIFIED");
            return false;
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        // The action targets this exact accessibility node. A moved keyboard or a stale tap cannot
        // direct the password into the address bar, the email field, or a different window.
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false;
        if (password) return true; // A password field can deliberately conceal its value.
        AccessibilityNodeInfo populated = new ChromeNode(id).current();
        return populated != null && populated.getWindowId() == field.getWindowId()
                && populated.getText() != null && value.contentEquals(populated.getText());
    }

    public void testFill() throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII));
        String email = input.readLine();
        String password = input.readLine();
        if (email == null || password == null) return;
        if (!fill("login-email", false, email)) return;
        System.out.println("QA_LOGIN_EMAIL_SET");
        if (fill("login-password", true, password)) System.out.println("QA_LOGIN_PASSWORD_SET");
    }
}
