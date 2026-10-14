# Task68 native image diagnostic signing

This temporary branch adds an artifact-only mode to the existing OHOS Release
workflow. It produces a control/probe pair from the original diagnostic carrier,
without recompiling the application or changing its resources, entry library,
shared library, bundle or version. It is not a release, SDK upgrade or avatar fix.
Do not merge this branch, enable auto-merge, or publish its artifacts to Hands or
AppGallery. Normal dispatches default to the existing build path.

## Fixed sources

| Input | Identity |
| --- | --- |
| Original application | `4fc2ea871da9c5ec101e4c8c6832ebeda412061a` |
| Original artifact | Mobile run `38503177796`, artifact `11868217887` |
| Original signed HAP SHA-256 | `2b19103c939258b0150aa811d35de8f4b1738a2aa8e535321fcca10cca90aeed` |
| Control renderer source | KuiklyUI `8226bb433c1fb97c0ac424979ab77add9d6258e5` |
| Probe renderer source | KuiklyUI PR201 `a76e111b65e1e922e6340f98611672732e590dbf`, run `38595671669` |
| Input ZIP SHA-256 | `f3ac82093a8cb046b5734a472815415c801486fa076bc01b7d9a160bffc6ceb4` |
| Control unsigned HAP SHA-256 | `f92dc0dc03f1280e33991c5730f04a7ce5e6fb47b91e101e5b3a521348ab8bfc` |
| Probe unsigned HAP SHA-256 | `4d2df3e1af344d8f0b5d9bacff07d0e3295a3462acffaa6009b3b4d1cbfd483e` |

The small checked-in ZIP contains only the two native libraries and provenance.
The workflow executes the reviewed helper from this branch, never Python from
the ZIP. Both HAPs retain `build.raft.mobile`, version
`1.13.1-diag-113bb09c` / `11301900`. They differ in one of 264 ZIP entries:
`libs/arm64-v8a/libkuikly.so`. The control includes PR198/199 frame-monitor changes;
it is not the older renderer currently installed on the emulator.

## Dispatch handoff

The task71 operator checks current Stamp permission and independent review of
both the probe and signing driver before dispatch. Set `DRIVER_SHA` below to the
full reviewed commit on this branch. This is the signing driver SHA, **not** the
original application SHA. The workflow refuses differing ref/source SHAs,
publication, or any version other than the fixed diagnostic identity.

```bash
DRIVER_SHA=<full-reviewed-driver-commit>
stamp workflow run ohos-release.yml --repo botiverse/mobile \
  --ref ark/task68-native-diagnostic-signing \
  -f native_diagnostic_pair=true \
  -f source_sha="$DRIVER_SHA" \
  -f publish_to_hands=false \
  -f version_major=1 -f version_minor=13 -f version_patch=1 \
  -f version_build=900 -f version_suffix=diag-113bb09c
```

No signing material is exported to an operator. The job downloads the fixed
original artifact, verifies its file hashes and signature, prepares both unsigned
HAPs, then uses the existing managed repository signing secrets. It verifies each
new signature with the official SDK tool and requires the exported certificate
chain and profile to match the original byte for byte. A rotated identity fails
closed; do not bypass it or uninstall the logged-in application.

Only after all checks succeed does artifact
`task68-native-diagnostic-<driver-sha>` upload the two `.signed.hap` files,
`verification-receipt.json`, and `driver-identity.json`. The receipt records signed
HAP hashes, library hashes, public identity hashes and source provenance.
Signing assets are deleted even after a failed decode or signing operation.
No upload runs on verification failure.

## Validation and device boundary

```bash
python3 scripts/ci/check_ohos_diagnostic_dispatch.py --self-test
python3 -m unittest discover -s scripts/ci -p 'task68_native_diagnostic_test.py'
# Optional local replay with the already downloaded original artifact:
python3 scripts/ci/task68_native_diagnostic_test.py \
  --original-dir /path/to/original-artifact -v
```

The local tests exercise fixed inputs, unsafe archives, payload/identity drift
and dispatch rejection. Public-identity test fixtures do not perform real
signing. Only the CI job's successful official `verify-app` calls establish
signature verification. Successful signing still does not prove installation,
session preservation or runtime behavior. Ark must cover-install onto the owned
16013 slot without clearing data, verify the logged-in session and collect the
paired native event traces. Physical OHOS validation remains separate.
