#!/usr/bin/env python3
"""Real JDK signing tests using a disposable key; no Android SDK or Gradle."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

SCRIPTS = Path(__file__).resolve().parent


class AabFixture:
    def __init__(self, root):
        self.root = Path(root)
        self.env = dict(os.environ, SLOCK_UPLOAD_KEYSTORE_FILE=str(self.root / 'upload.p12'),
                        SLOCK_UPLOAD_KEYSTORE_PASS='test-only-password', SLOCK_UPLOAD_KEY_ALIAS='upload',
                        SLOCK_UPLOAD_KEY_PASS='test-only-password')
        self.run(['keytool', '-genkeypair', '-alias', 'upload', '-keystore', self.env['SLOCK_UPLOAD_KEYSTORE_FILE'],
                  '-storepass:env', 'SLOCK_UPLOAD_KEYSTORE_PASS', '-keypass:env', 'SLOCK_UPLOAD_KEY_PASS',
                  '-keyalg', 'RSA', '-keysize', '2048', '-validity', '30', '-dname', 'CN=Disposable AAB Test'])
        cert = self.run(['keytool', '-exportcert', '-alias', 'upload', '-keystore', self.env['SLOCK_UPLOAD_KEYSTORE_FILE'],
                         '-storepass:env', 'SLOCK_UPLOAD_KEYSTORE_PASS']).stdout
        self.pin = hashlib.sha256(cert).hexdigest()
        self.input = self.root / 'original.aab'
        self.signed = self.root / 'signed.aab'
        with zipfile.ZipFile(self.input, 'w', zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('BundleConfig.pb', b'bundle config')
            archive.writestr('base/manifest/AndroidManifest.xml', b'protobuf manifest')
            archive.writestr('base/dex/classes.dex', b'dex\x00test')
            archive.writestr('META-INF/services/example.Service', b'application metadata must also be signed')
        self.run([str(SCRIPTS / 'sign_android_aab.sh'), str(self.input), str(self.signed), self.pin,
                  str(self.root / 'signed.json')])

    def run(self, command):
        return subprocess.run(command, env=self.env, capture_output=True, check=True)


class SigningContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.fixture = AabFixture(cls.temp.name)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def verify(self, path, pin=None, original=None):
        command = [str(SCRIPTS / 'verify_android_aab.sh'), str(path), pin or self.fixture.pin,
                   str(self.fixture.root / 'verify.json')]
        if original:
            command.append(str(original))
        return subprocess.run(command, capture_output=True)

    def test_signer_preserves_original_and_all_payload_bytes(self):
        f = self.fixture
        receipt = json.loads((f.root / 'signed.json').read_text())
        self.assertEqual(receipt['input_sha256'], hashlib.sha256(f.input.read_bytes()).hexdigest())
        self.assertEqual(receipt['sha256'], hashlib.sha256(f.signed.read_bytes()).hexdigest())
        self.assertEqual(receipt['upload_key_cert_sha256'], f.pin)
        self.assertEqual(receipt['signed_payload_entries'], 4)
        self.assertTrue(receipt['payload_unchanged'])
        self.assertEqual(self.verify(f.signed, original=f.input).returncode, 0)

    def test_rejects_unsigned_aab_and_wrong_certificate(self):
        self.assertNotEqual(self.verify(self.fixture.input).returncode, 0)
        self.assertNotEqual(self.verify(self.fixture.signed, pin='0' * 64).returncode, 0)

    def test_rejects_tampered_payload_and_unsigned_added_entry(self):
        for mode in ('tamper', 'append'):
            with self.subTest(mode=mode):
                target = self.fixture.root / (mode + '.aab')
                with zipfile.ZipFile(self.fixture.signed) as source, zipfile.ZipFile(target, 'w') as out:
                    for info in source.infolist():
                        data = source.read(info)
                        if mode == 'tamper' and info.filename == 'base/dex/classes.dex':
                            data += b'tampered'
                        out.writestr(info, data)
                    if mode == 'append':
                        out.writestr('META-INF/services/unsigned.Service', b'unsigned application payload')
                self.assertNotEqual(self.verify(target).returncode, 0)

    def test_rejects_payload_change_against_original(self):
        wrong = self.fixture.root / 'wrong-original.aab'
        with zipfile.ZipFile(self.fixture.input) as source, zipfile.ZipFile(wrong, 'w') as out:
            for info in source.infolist():
                out.writestr(info, source.read(info) + b'changed')
        self.assertNotEqual(self.verify(self.fixture.signed, original=wrong).returncode, 0)

    def test_signer_rejects_wrong_key_before_publishing_output(self):
        output = self.fixture.root / 'must-not-exist.aab'
        result = subprocess.run([str(SCRIPTS / 'sign_android_aab.sh'), str(self.fixture.input), str(output),
                                 '0' * 64, str(self.fixture.root / 'must-not-exist.json')],
                                env=self.fixture.env, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
