#!/usr/bin/env python3
"""Download pinned, sealed Hands inputs without logging credentials or presign URLs.

Wire contracts: hands worker/src/routes/android_release_artifacts.ts bundleResponse
and worker/src/routes/builds.ts handleDownloadBuildAsset (presign=1).
"""
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request


class ContractError(ValueError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


USER_AGENT = 'raft-mobile-release/1.0'
HTTP_STAGES = {'app_list', 'sealed_bundle', 'aab_presign', 'apk_presign', 'aab_download', 'apk_download'}


@contextmanager
def open_response(opener, url, stage, *, token=None, timeout=60):
    if stage not in HTTP_STAGES:
        raise ContractError('Invalid HTTP stage')
    # Hands edge rejects urllib's generic default before the API auth layer.
    # Identify this actual release client on both API and presigned-storage reads.
    headers = {'User-Agent': USER_AGENT}
    if token is not None:
        headers.update({'Authorization': 'Bearer ' + token, 'Accept': 'application/json'})
    request = urllib.request.Request(url, headers=headers)
    try:
        with opener.open(request, timeout=timeout) as response:
            yield response
    except urllib.error.HTTPError as error:
        # HTTPError contains URLs, reason strings and response bodies. Only this
        # fixed operation label and numeric status are safe for CI logs.
        raise ContractError(f'{stage} HTTP {error.code}') from None
    except urllib.error.URLError:
        raise ContractError(f'{stage} NETWORK_ERROR') from None


def require(value, pattern, label):
    if not re.fullmatch(pattern, value):
        raise ContractError('Invalid ' + label)
    return value


def validate_receipt(receipt, expected):
    if (receipt.get('schema') != 'hands.android-release-artifacts.v1'
            or receipt.get('build_id') != expected['build_id'] or receipt.get('status') != 'ready'
            or receipt.get('source') != expected['source'] or receipt.get('package_name') != 'build.raft.app'
            or not isinstance(receipt.get('version_code'), int) or receipt['version_code'] <= 0
            or not isinstance(receipt.get('version_name'), str) or not receipt['version_name']):
        raise ContractError('Sealed build identity mismatch')
    artifacts = receipt.get('artifacts', [])
    if len(artifacts) != 2 or {item.get('kind') for item in artifacts} != {'aab', 'apk'}:
        raise ContractError('Expected exactly one AAB and one APK')
    for item in artifacts:
        require(item.get('asset_id', ''), r'[A-Za-z0-9-]+', 'asset ID')
        size = item.get('size_bytes')
        if (item.get('status') != 'sealed' or item.get('sha256') != expected[item['kind'] + '_sha256']
                or item.get('verified_sha256') != item['sha256'] or item.get('verified_size_bytes') != size
                or not isinstance(size, int) or not 0 < size <= 4 * 1024**3):
            raise ContractError('Unsealed artifact or digest/size mismatch')
    return artifacts


def main():
    output = Path(sys.argv[1])
    output.mkdir(parents=True, exist_ok=True)
    env = os.environ
    origin = env.get('HANDS_API', 'https://hands.build').rstrip('/')
    parsed = urllib.parse.urlsplit(origin)
    if parsed.scheme != 'https' or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path:
        raise ContractError('HANDS_API must be an HTTPS origin')
    token = env['HANDS_BEARER_TOKEN']
    opener = urllib.request.build_opener(NoRedirect)

    def api(path, stage):
        with open_response(opener, origin + path, stage, token=token) as response:
            return json.load(response)

    app_id = env.get('HANDS_ANDROID_APP_ID')
    if not app_id:
        matches = [a['id'] for a in api('/api/apps', 'app_list')['apps'] if a.get('slug') == 'raft-android']
        if len(matches) != 1:
            raise ContractError('Cannot uniquely resolve raft-android app')
        app_id = matches[0]
    require(app_id, r'[A-Za-z0-9-]+', 'app ID')
    expected = {
        'build_id': require(env['SOURCE_HANDS_BUILD_ID'], r'[A-Za-z0-9-]+', 'build ID'),
        'source': {'repository': env['SOURCE_REPOSITORY'],
                   'commit_sha': require(env['SOURCE_COMMIT_SHA'], r'[0-9a-f]{40}', 'source SHA'),
                   'ci_run_id': require(env['SOURCE_RUN_ID'], r'[1-9][0-9]*', 'source run ID')},
        'aab_sha256': require(env['INPUT_AAB_SHA256'], r'[0-9a-f]{64}', 'AAB SHA'),
        'apk_sha256': require(env['INPUT_APK_SHA256'], r'[0-9a-f]{64}', 'APK SHA'),
    }
    receipt = api(f"/api/apps/{app_id}/android-release-artifacts/{expected['build_id']}", 'sealed_bundle')
    artifacts = validate_receipt(receipt, expected)
    safe_artifacts = []
    for item in artifacts:
        link = api(f"/api/apps/{app_id}/builds/{expected['build_id']}/assets/{item['asset_id']}/download?presign=1",
                   item['kind'] + '_presign')
        if link.get('asset_id') != item['asset_id'] or link.get('size_bytes') != item['size_bytes']:
            raise ContractError('Download identity mismatch')
        url = link['download_url']
        if urllib.parse.urlsplit(url).scheme != 'https':
            raise ContractError('Download URL must use HTTPS')
        path = output / ('original.' + item['kind'])
        digest = hashlib.sha256()
        size = 0
        # Presign requests are authenticated above. Storage requests get no
        # bearer token, even when hosted outside the Hands origin.
        with open_response(opener, url, item['kind'] + '_download', timeout=120) as response, path.open('wb') as target:
            while chunk := response.read(1024 * 1024):
                size += len(chunk)
                if size > item['size_bytes']:
                    raise ContractError('Downloaded artifact exceeds sealed size')
                digest.update(chunk)
                target.write(chunk)
        if size != item['size_bytes'] or digest.hexdigest() != item['sha256']:
            raise ContractError('Downloaded artifact does not match pinned bytes')
        safe_artifacts.append({key: item[key] for key in ('asset_id', 'kind', 'sha256', 'size_bytes')})
    safe = {key: receipt[key] for key in ('build_id', 'source', 'package_name', 'version_name', 'version_code')}
    safe.update(schema='raft.android-aab-repair-input.v1', artifacts=safe_artifacts, app_id=app_id)
    (output / 'source-receipt.json').write_text(json.dumps(safe, indent=2) + '\n')
    print('Downloaded both sealed artifacts; source identity, sizes and SHA-256 match')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # URLError may include signed URLs; only print locally controlled labels.
        label = str(error) if isinstance(error, ContractError) else type(error).__name__
        raise SystemExit('AAB repair download rejected: ' + label) from None
