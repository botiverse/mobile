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
kind='put' if '--upload-file' in a else 'create' if '--data-binary' in a else 'complete' if '-X' in a else 'get'
calls=Path(str(state)+'.'+kind); n=int(calls.read_text()) if calls.exists() else 0; calls.write_text(str(n+1))
if kind==os.environ.get('FAIL_ON') and n<int(os.environ.get('FAIL_TIMES','1')):
 print('<html>edge '+os.environ['FAIL_CODE']+' https://uploads.example/x?X-Amz-Signature=presigned-secret</html>')
 sys.stderr.write('curl: (22) The requested URL returned error: '+os.environ['FAIL_CODE']+'\n'); sys.exit(22)
if kind=='put': sys.exit(0)
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

    def run_case(self, mode, **extra):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            (root/'curl').write_text(CURL); (root/'curl').chmod(0o755)
            (root/'app.apk').write_bytes(b'apk')
            env=dict(os.environ, PATH=f'{td}:'+os.environ['PATH'], MOCK_STATE=f'{td}/request', MODE=mode,
                     HANDS_API='https://hands.example', HANDS_BEARER_TOKEN='test', HANDS_ANDROID_APP_ID='app',
                     SOURCE_REPOSITORY='botiverse/mobile',SOURCE_COMMIT_SHA='a'*40,CI_RUN_ID='123',
                     PACKAGE_NAME='build.raft.app',VERSION_NAME='1.0.0',VERSION_CODE='123',
                     UPLOAD_KEY_CERT_SHA256=self.fixture.pin,AAB_PATH=str(self.fixture.input if mode == 'unsigned' else self.fixture.signed),APK_PATH=f'{td}/app.apk',
                     HANDS_CURL_RETRY_DELAYS='0 0 0', **extra)
            result = subprocess.run(['bash',str(Path(__file__).with_name('upload_android_hands_artifacts.sh'))],env=env,capture_output=True,text=True)
            if mode == 'unsigned':
                self.assertFalse((root/'request').exists(), 'unsigned AAB reached network upload')
            result.calls={k:int((root/f'request.{k}').read_text()) for k in ('create','put','complete','get') if (root/f'request.{k}').exists()}
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
    def test_retries_transient_5xx(self):
        for fail_on,code,calls in (('create','503',2),('put','503',3),('complete','502',3),('get','504',2)):
            with self.subTest(fail_on=fail_on):
                result=self.run_case('ok',FAIL_ON=fail_on,FAIL_CODE=code)
                self.assertEqual(result.returncode,0,result.stderr)
                self.assertEqual(result.stdout.count('build_id=build-test'),1)
                self.assertEqual(result.calls[fail_on],calls)
                self.assertIn('attempt 1/3 failed (transient): HTTP '+code,result.stderr)
    def test_create_fails_fast_on_non_gateway_errors(self):
        for code in ('400','500'):
            with self.subTest(code=code):
                result=self.run_case('ok',FAIL_ON='create',FAIL_CODE=code)
                self.assertNotEqual(result.returncode,0)
                self.assertEqual(result.calls,{'create':1})
                self.assertIn('HTTP '+code,result.stderr)
    def test_final_failure_logs_redacted_body(self):
        result=self.run_case('ok',FAIL_ON='put',FAIL_CODE='503',FAIL_TIMES='9')
        self.assertNotEqual(result.returncode,0)
        self.assertEqual(result.calls['put'],3)
        self.assertIn('Hands aab upload failed: HTTP 503',result.stderr)
        self.assertIn('<html>edge 503',result.stderr)
        self.assertNotIn('presigned-secret',result.stderr)

if __name__=='__main__': unittest.main()
