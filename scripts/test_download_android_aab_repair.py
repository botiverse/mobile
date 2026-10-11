#!/usr/bin/env python3
"""Receipt rejection tests against the inspected Hands bundleResponse wire shape."""
import copy
import unittest
from download_android_aab_repair import validate_receipt


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


if __name__ == '__main__':
    unittest.main()
