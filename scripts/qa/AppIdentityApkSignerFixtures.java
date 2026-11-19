import com.android.apksig.ApkSigner;
import com.android.apksig.SigningCertificateLineage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Test-only: signs four manifest-only APKs with real keys (a.p12, b.p12, c.p12 in DIR, alias
 * "k", password PASS) under the rotation policies the identity runner must handle. Used by
 * scripts/qa/test_app_identity_apk_signer.sh; never by a release build.
 *
 *   rotation-35      a -> b, v3.1 rotation at 35
 *   dev-release-35   a -> b, v3.1 rotation at 35 targeting a development preview
 *   rotations-33-35  a; b from 33; c from 35 (SDK-targeted signers)
 *   rotations-35-36  a; b from 35; c from 36 (SDK-targeted signers)
 */
class AppIdentityApkSignerFixtures {
    record Key(PrivateKey key, X509Certificate cert) {}

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        char[] pass = args[1].toCharArray();
        Key a = load(new File(dir, "a.p12"), pass), b = load(new File(dir, "b.p12"), pass), c = load(new File(dir, "c.p12"), pass);
        File unsigned = new File(dir, "unsigned.apk");
        try (var zip = new ZipOutputStream(new FileOutputStream(unsigned))) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write(new byte[] {0});
            zip.closeEntry();
        }
        var ab = new SigningCertificateLineage.Builder(lineageSigner(a), lineageSigner(b)).build();
        var abc = ab.spawnDescendant(lineageSigner(b), lineageSigner(c));

        sign(unsigned, new File(dir, "rotation-35.apk"), List.of(signer("a", a), signer("b", b)), ab, 35, false);
        sign(unsigned, new File(dir, "dev-release-35.apk"), List.of(signer("a", a), signer("b", b)), ab, 35, true);
        sign(unsigned, new File(dir, "rotations-33-35.apk"),
                List.of(signer("a", a), targeted("b", b, ab, 33), targeted("c", c, abc, 35)), null, 0, false);
        sign(unsigned, new File(dir, "rotations-35-36.apk"),
                List.of(signer("a", a), targeted("b", b, ab, 35), targeted("c", c, abc, 36)), null, 0, false);
    }

    private static void sign(File in, File out, List<ApkSigner.SignerConfig> signers, SigningCertificateLineage lineage,
                             int rotationMinSdk, boolean devRelease) throws Exception {
        var builder = new ApkSigner.Builder(new ArrayList<>(signers)).setInputApk(in).setOutputApk(out)
                .setMinSdkVersion(24).setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(true)
                .setV4SigningEnabled(false);
        if (lineage != null) {
            builder.setSigningCertificateLineage(lineage).setMinSdkVersionForRotation(rotationMinSdk)
                    .setRotationTargetsDevRelease(devRelease);
        }
        builder.build().sign();
    }

    private static Key load(File file, char[] pass) throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        try (var input = new FileInputStream(file)) {
            store.load(input, pass);
        }
        return new Key((PrivateKey) store.getKey("k", pass), (X509Certificate) store.getCertificate("k"));
    }

    private static SigningCertificateLineage.SignerConfig lineageSigner(Key k) {
        return new SigningCertificateLineage.SignerConfig.Builder(k.key(), k.cert()).build();
    }

    private static ApkSigner.SignerConfig signer(String name, Key k) {
        return new ApkSigner.SignerConfig.Builder(name, k.key(), List.of(k.cert())).build();
    }

    private static ApkSigner.SignerConfig targeted(String name, Key k, SigningCertificateLineage lineage, int minSdk) {
        return new ApkSigner.SignerConfig.Builder(name, k.key(), List.of(k.cert()))
                .setLineageForMinSdkVersion(lineage, minSdk).build();
    }
}
