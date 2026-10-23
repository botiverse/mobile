#!/usr/bin/env python3
"""Exercise the uploader against the Hands android-release-artifacts v1 wire contract."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_android_aab_signing import AabFixture

CURL = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
a=sys.argv[1:]; state=Path(os.environ['MOCK_STATE'])
if '--upload-file' in a: sys.exit(0)
if '--data-binary' in a:
 d=json.loads(a[a.index('--data-binary')+1]); state.write_text(json.dumps(d))
 d.update(build_id='build-test',status='uploading')
 for v in d['artifacts']:
  v.update(asset_id=v['kind'],upload={'method':'PUT','url':'https://uploads.example/'+v['kind'],'headers':{'content-type':'application/octet-stream'}},complete_url='https://hands.example/complete/'+v['kind'])
  if os.environ.get('MODE')=='foreign': v['complete_url']='https://foreign.example/complete'
 print(json.dumps(d)); sys.exit(0)
if '-X' in a: print('{}'); sys.exit(0)
d=json.loads(state.read_text()); d.update(build_id='build-test',status='ready')
for v in d['artifacts']: v.update(status='sealed',verified_sha256=v['sha256'],verified_size_bytes=v['size_bytes'])
if os.environ.get('MODE')=='digest': d['artifacts'][0]['verified_sha256']='0'*64
if os.environ.get('MODE')=='source': d['source']['commit_sha']='0'*40
print(json.dumps(d))
'''

class UploadContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.fixture = AabFixture(cls.temp.name)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_case(self, mode):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            (root/'curl').write_text(CURL); (root/'curl').chmod(0o755)
            (root/'app.apk').write_bytes(b'apk')
            env=dict(os.environ, PATH=f'{td}:'+os.environ['PATH'], MOCK_STATE=f'{td}/request', MODE=mode,
                     HANDS_API='https://hands.example', HANDS_BEARER_TOKEN='test', HANDS_ANDROID_APP_ID='app',
                     SOURCE_REPOSITORY='botiverse/mobile',SOURCE_COMMIT_SHA='a'*40,CI_RUN_ID='123',
                     PACKAGE_NAME='build.raft.app',VERSION_NAME='1.0.0',VERSION_CODE='123',
                     UPLOAD_KEY_CERT_SHA256=self.fixture.pin,AAB_PATH=str(self.fixture.input if mode == 'unsigned' else self.fixture.signed),APK_PATH=f'{td}/app.apk')
            result = subprocess.run(['bash',str(Path(__file__).with_name('upload_android_hands_artifacts.sh'))],env=env,capture_output=True,text=True)
            if mode == 'unsigned':
                self.assertFalse((root/'request').exists(), 'unsigned AAB reached network upload')
            return result
    def test_workflow_uploads_signed_apk(self):
        workflow=Path(__file__).resolve().parent.parent / ".github/workflows/android-release.yml"
        block=workflow.read_text().split("- name: Upload immutable AAB + APK to Hands",1)[1].split("- name: Upload APK as workflow artifact",1)[0]
        self.assertIn("APK_PATH: ${{ steps.sign.outputs.apk_path }}",block)

    def test_current_wire_contract(self):
        result=self.run_case('ok')
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertIn('status=ready',result.stdout)
    def test_rejects_bad_receipt_and_foreign_completion(self):
        for mode in ('digest','source','foreign','unsigned'):
            with self.subTest(mode=mode): self.assertNotEqual(self.run_case(mode).returncode,0)

if __name__=='__main__': unittest.main()
