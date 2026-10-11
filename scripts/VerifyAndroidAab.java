import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/** JDK 17 source launcher. Consume every entry so JarFile checks its signature. */
class VerifyAndroidAab {
    static String digest(InputStream stream) throws Exception {
        try (stream) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (int n; (n = stream.read(buffer)) != -1;) digest.update(buffer, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    static boolean signatureMetadata(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) return false;
        String base = upper.substring(9);
        return !base.contains("/") && (base.equals("MANIFEST.MF") || base.endsWith(".SF")
            || base.endsWith(".RSA") || base.endsWith(".DSA") || base.endsWith(".EC")
            || base.startsWith("SIG-"));
    }

    static Map<String, String> payload(Path path, String pin, Path certFile) throws Exception {
        Map<String, String> hashes = new TreeMap<>();
        var names = new HashSet<String>();
        try (JarFile jar = new JarFile(path.toFile(), true, ZipFile.OPEN_READ)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!names.add(entry.getName())) throw new SecurityException("Duplicate ZIP entry");
                String hash = digest(jar.getInputStream(entry));
                if (entry.isDirectory() || signatureMetadata(entry.getName())) continue;
                hashes.put(entry.getName(), hash);
                if (pin == null) continue;
                CodeSigner[] signers = entry.getCodeSigners();
                if (signers == null || signers.length != 1)
                    throw new SecurityException("Unsigned entry or multiple signers: " + entry.getName());
                Certificate certificate = signers[0].getSignerCertPath().getCertificates().get(0);
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
                if (!actual.equals(pin)) throw new SecurityException("AAB signer certificate mismatch");
                Files.write(certFile, certificate.getEncoded());
            }
        }
        if (!hashes.containsKey("BundleConfig.pb") || !hashes.containsKey("base/manifest/AndroidManifest.xml"))
            throw new SecurityException("Missing required AAB entries");
        return hashes;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 5 || !args[1].matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Expected AAB, cert SHA-256, cert output, receipt, optional original AAB");
        Path aab = Path.of(args[0]);
        Map<String, String> hashes = payload(aab, args[1], Path.of(args[2]));
        String original = "";
        if (args.length == 5) {
            Path input = Path.of(args[4]);
            if (!hashes.equals(payload(input, null, null)))
                throw new SecurityException("Signing changed AAB payload entries");
            original = ",\n  \"input_sha256\": \"" + digest(Files.newInputStream(input)) + "\",\n  \"payload_unchanged\": true";
        }
        Files.writeString(Path.of(args[3]), "{\n  \"schema\": \"raft.android-aab-signature.v1\",\n"
            + "  \"sha256\": \"" + digest(Files.newInputStream(aab)) + "\",\n"
            + "  \"size_bytes\": " + Files.size(aab) + ",\n"
            + "  \"upload_key_cert_sha256\": \"" + args[1] + "\",\n"
            + "  \"signed_payload_entries\": " + hashes.size() + original + "\n}\n");
    }
}
