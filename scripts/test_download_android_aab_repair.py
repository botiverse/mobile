#!/usr/bin/env python3
"""Receipt rejection tests against the inspected Hands bundleResponse wire shape."""
import copy
import io
import unittest
from unittest.mock import Mock
import urllib.error
from download_android_aab_repair import ContractError, USER_AGENT, open_response, validate_receipt


class SealedInputContract(unittest.TestCase):
    def setUp(self):
        self.expected = {'build_id': 'original-build',
                         'source': {'repository': 'botiverse/mobile', 'commit_sha': 'a' * 40, 'ci_run_id': '123'},
                         'aab_sha256': 'b' * 64, 'apk_sha256': 'c' * 64}
        self.receipt = {'schema': 'hands.android-release-artifacts.v1', 'build_id': 'original-build',
                        'status': 'ready', 'source': dict(self.expected['source']), 'package_name': 'build.raft.app',
                        'version_name': '1.13.1', 'version_code': 11301004,
                        'artifacts': [{'asset_id': 'asset-' + kind, 'kind': kind, 'filename': 'app.' + kind,
                                       'status': 'sealed', 'sha256': self.expected[kind + '_sha256'],
                                       'verified_sha256': self.expected[kind + '_sha256'],
                                       'size_bytes': 123, 'verified_size_bytes': 123} for kind in ('aab', 'apk')]}

    def test_accepts_exact_verified_pair(self):
        self.assertEqual(len(validate_receipt(self.receipt, self.expected)), 2)

    def test_rejects_different_source_or_run_and_unsealed_or_changed_artifact(self):
        changes = [(['source', 'commit_sha'], 'd' * 40), (['source', 'ci_run_id'], '124'),
                   (['build_id'], 'other-build'), (['package_name'], 'other.package'),
                   (['artifacts', 0, 'status'], 'pending_upload'), (['artifacts', 0, 'sha256'], '0' * 64),
                   (['artifacts', 1, 'verified_sha256'], '0' * 64), (['artifacts', 1, 'verified_size_bytes'], 124),
                   (['artifacts', 1, 'kind'], 'aab')]
        for keys, value in changes:
            with self.subTest(keys=keys):
                receipt = copy.deepcopy(self.receipt)
                target = receipt
                for key in keys[:-1]:
                    target = target[key]
                target[keys[-1]] = value
                with self.assertRaises(ValueError):
                    validate_receipt(receipt, self.expected)


class HttpTransportContract(unittest.TestCase):
    def test_api_identifies_client_and_preserves_existing_bearer(self):
        opener = Mock()
        opener.open.return_value = io.BytesIO(b'{}')
        with open_response(opener, 'https://hands.example/api/apps', 'app_list', token='test-token'):
            pass
        request = opener.open.call_args.args[0]
        self.assertEqual(request.get_header('User-agent'), USER_AGENT)
        self.assertEqual(request.get_header('Authorization'), 'Bearer test-token')
        self.assertEqual(request.get_header('Accept'), 'application/json')

    def test_storage_identifies_client_without_forwarding_bearer(self):
        opener = Mock()
        opener.open.return_value = io.BytesIO(b'bundle bytes')
        with open_response(opener, 'https://storage.example/object?signature=test-secret',
                           'aab_download', timeout=120):
            pass
        request = opener.open.call_args.args[0]
        self.assertEqual(request.get_header('User-agent'), USER_AGENT)
        self.assertIsNone(request.get_header('Authorization'))
        self.assertEqual(opener.open.call_args.kwargs['timeout'], 120)

    def test_http_failure_reports_only_stage_and_numeric_status(self):
        for stage, status in [('app_list', 401), ('sealed_bundle', 403), ('aab_download', 403)]:
            with self.subTest(stage=stage, status=status):
                opener = Mock()
                opener.open.side_effect = urllib.error.HTTPError(
                    'https://storage.example/object?signature=secret', status,
                    'sensitive reason', {}, io.BytesIO(b'sensitive response body'))
                with self.assertRaises(ContractError) as result:
                    with open_response(opener, 'https://storage.example/object?signature=secret', stage):
                        pass
                self.assertEqual(str(result.exception), f'{stage} HTTP {status}')

    def test_network_failure_does_not_echo_url_or_reason(self):
        opener = Mock()
        opener.open.side_effect = urllib.error.URLError('sensitive URL or reason')
        with self.assertRaises(ContractError) as result:
            with open_response(opener, 'https://storage.example/object?signature=secret', 'apk_download'):
                pass
        self.assertEqual(str(result.exception), 'apk_download NETWORK_ERROR')


if __name__ == '__main__':
    unittest.main()
