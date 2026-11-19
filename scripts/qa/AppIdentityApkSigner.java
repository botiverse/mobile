import com.android.apksig.ApkVerifier;
import com.android.apksig.ApkVerifier.Result.V3SchemeSignerInfo;
import com.android.apksig.util.DataSource;
import com.android.apksig.util.DataSources;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;

/**
 * The certificate a RELEASED Android platform of one SDK uses for an APK (what the app's
 * SigningInfo.apkContentsSigners reports there), read from apksig's structured verification
 * result, never from apksigner's printed text. Same selection as Testbed's
 * InspectReleaseSettingsApk (botiverse/testbed #317): verify for exactly that SDK; when the only
 * error is the v3.1 coverage gap below the first v3.1 signer (build-tools 36 on API 33/34),
 * verify again through that boundary; then take the unique certificate whose signed SDK range
 * holds the SDK, v3.1 first. A rotation targeted at a development preview does not apply on the
 * stable release with the same SDK number.
 *
 *   java -cp "$ANDROID_HOME/build-tools/36.0.0/lib/apksigner.jar" \
 *     scripts/qa/AppIdentityApkSigner.java APK ANDROID_SDK
 *
 * Prints {"result":"SIGNER_SELECTED","android_sdk":N,"signer_sha256":"<hex>"} and exits 0, or
 * {"result":"APK_SIGNER_UNVERIFIED"} and exits 1. Usage errors exit 2.
 */
class AppIdentityApkSigner {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("[1-9][0-9]*")) {
            System.err.println("Usage: AppIdentityApkSigner APK ANDROID_SDK");
            System.exit(2);
        }
        int sdk = Integer.parseInt(args[1]);
        List<X509Certificate> signers;
        try (var channel = FileChannel.open(Path.of(args[0]))) {
            signers = releasedPlatformSigners(DataSources.asDataSource(channel), sdk);
        } catch (Exception e) {
            signers = List.of();
        }
        if (signers.size() != 1) {
            System.out.println("{\"result\":\"APK_SIGNER_UNVERIFIED\"}");
            System.exit(1);
        }
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(signers.get(0).getEncoded()));
        System.out.printf("{\"result\":\"SIGNER_SELECTED\",\"android_sdk\":%d,\"signer_sha256\":\"%s\"}%n", sdk, digest);
    }

    static List<X509Certificate> releasedPlatformSigners(DataSource apk, int sdk) throws Exception {
        var result = verifyRange(apk, sdk, sdk);
        if (!result.isVerified()) {
            // Build-tools 36 checks v3.1 coverage even when its first signer starts after the
            // target. Include that boundary so apksig verifies both blocks.
            int rotation = result.getV31SchemeSigners().stream()
                    .mapToInt(V3SchemeSignerInfo::getMinSdkVersion).min().orElse(0);
            var errors = result.getAllErrors();
            if (sdk < 33 || rotation <= sdk || errors.isEmpty()
                    || errors.stream().anyMatch(error -> error.getIssue() != ApkVerifier.Issue.V3_MISSING_SDK_VERSIONS)) {
                return List.of();
            }
            result = verifyRange(apk, sdk, rotation);
            if (!result.isVerified()) return List.of();
        }
        if (!result.isVerifiedUsingV31Scheme() && !result.isVerifiedUsingV3Scheme()) {
            return result.getSignerCertificates();
        }
        if (result.isVerifiedUsingV31Scheme()) {
            var signers = matchingSigners(result.getV31SchemeSigners(), sdk, true);
            if (!signers.isEmpty()) return signers;
        }
        return result.isVerifiedUsingV3Scheme() ? matchingSigners(result.getV3SchemeSigners(), sdk, false) : List.of();
    }

    private static ApkVerifier.Result verifyRange(DataSource apk, int minSdk, int maxSdk) throws Exception {
        return new ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(minSdk)
                .setMaxCheckedPlatformVersion(maxSdk).build().verify();
    }

    private static List<X509Certificate> matchingSigners(List<V3SchemeSignerInfo> signers, int sdk, boolean v31) {
        // On a released platform the preview-targeted key becomes eligible above its lower
        // boundary, while an ordinary rotation includes it.
        return signers.stream()
                .filter(signer -> signer.getMinSdkVersion() <= sdk && sdk <= signer.getMaxSdkVersion())
                .filter(signer -> !v31 || !signer.getRotationTargetsDevRelease() || sdk != signer.getMinSdkVersion())
                .map(V3SchemeSignerInfo::getCertificate).toList();
    }
}
