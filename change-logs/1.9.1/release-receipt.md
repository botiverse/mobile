# Mobile 1.9.1 Release Receipt

Released/distributed: 2026-08-09

Receipt refreshed: 2026-08-10 13:32 Asia/Shanghai

## Process Exception

1.9.1 reused `release-1.9.0` instead of creating a dedicated
`release-1.9.1` branch, and it did not have a version-scoped release plan before
branch freeze. This receipt records that exception; it does not make family
branch reuse the standard. Future semantic versions follow
`docs/mobile-paired-release-sop.md` and use one dedicated branch per version.

## Frozen Carrier Source

- Branch/source:
  `release-1.9.0@26bb45829e0f21de35613d965f39d38b48244d8d`
- Tree: `3cb20b36aa1f22f9f5f77dc6bfb101b1460885f3`
- Kuikly gitlink in the distributed carrier:
  `d1ae9e290a66fc93a7040e4c00d4bc59e513d623`
- Push Sign-off run `31269792810`: `SUCCESS`
- Push Test run `31269792785`, attempt 2: `SUCCESS`
- Attempt 1 stopped on a transient API31 attachment-emulator distinct-UID
  `SecurityException`; failed-job-only retry recovered on the same source.

The accepted 1.9.1 user-visible scope was:

- channel join state remains canonical across refresh and re-entry;
- restored unsent composer drafts are not overwritten by placeholder/empty
  callbacks;
- iOS keeps the final message visible when opening the composer keyboard.

Reviewed notes are committed under
`change-logs/1.9.1/{android,ios}/{zh-CN,en,ja}.md`. PR #2000 and other
unapproved work were excluded from this train.

## Android

- Workflow run `31270997524`: `SUCCESS`
- Version: `1.9.1 (1090101)`
- Package: `build.raft.app`; minSdk 29; targetSdk 36
- APK artifact `9025662111`: 35,390,806 bytes
- APK SHA-256:
  `b51daf5d8868e108ce5c4a0e84bf29c84ece2e71735df32ff93ea5eb92653bbf`
- Symbols artifact `9025662387`: 94,599 bytes
- Symbols SHA-256:
  `39cae556429d4ac783bad169dff734a0755cd5a6f7ba551189f8e13791544a17`
- Hands build: `aeba016e-985e-4d39-a8f2-17be29540d56`
- Hands release: `5fee3723-4c98-4a21-86b0-8f9b0fad7779`
- Hands installable asset: `606f4e19-7185-460e-839c-f0bb4f9d990b`
- Hands symbols asset: `27e9865d-adfe-438f-92f6-ffc9541b44cf`
- Signing: APK Signature Scheme v2/v3/v3.1; API 33+ signer
  `5f08bbe82a27ccdad26c13523a60b5670d3aa7633e8ed2fd71fa6b5751f9b0b1`;
  API 24-32 signer
  `b4919381d8f9cede4f8eb8f6d132f6dfe0d6d15bf6d1f63057d5eba045161a45`

GitHub and Hands installable/symbol bytes were independently downloaded and
matched. Hands also generated immutable deltas from 1090001, 1080002, and
1070002 to the admitted APK hash.

Fresh Hands readback on 2026-08-10:

- `active`, revision `1`, scope `full:all`
- source exact unchanged
- zh-CN/en/ja notes match the checked-in Android notes
- no external targets

## iOS

- Workflow run `31271021860`: `SUCCESS`
- Version: `1.0.0 (1000015)`
- `release_notes_version=1.9.1`
- Bundle: `build.raft.app`; minimum iOS 15.0
- IPA artifact `9025849941`: 55,274,231 bytes
- IPA SHA-256:
  `a21e891c90c1db7388828d1433578241645e8a74915d49d06d46c84fd83a5354`
- dSYM artifact `9025850332`: 32,539,688 bytes
- dSYM SHA-256:
  `a6cee82bb97776497a8ead29bb602aec63240ee0d1e61cfcd5d72e547af452a2`
- Diagnostics artifact: `9025850507`
- Hands build: `a57cc7bd-c51d-4d3f-97ae-f98aa36790cf`
- Hands release: `f8454344-9644-4ca0-b853-fd16177d8e5a`
- Hands IPA asset: `6790e988-99a8-421a-9e78-ae23c7d72110`
- Hands dSYM asset: `9edd1814-c327-4dac-a13e-5f250021597e`
- App/dSYM UUID: `959454C3-865B-3EFB-BBDE-049F6EE7655A`
- Signing authority: `Apple Distribution: Botiverse, Inc. (XDAPXFY8FZ)`
- Profile UUID: `1bcbfcf9-c6af-4417-9f5e-132506e40020`
- Production APNS and Apple Sign-In enabled; `get-task-allow=false`

GitHub and Hands IPA/dSYM bytes were independently downloaded and matched.
Fresh Hands readback on 2026-08-10 remains `draft`, revision `0`, scope
`full:all`, exact source unchanged, with matching zh-CN/en/ja notes. This is
intentional: iOS Hands was not activated as an update lane.

## TestFlight Boundary

- ASC build: `28cebef9-b5c3-46e6-9aff-4d77750c26c7`
- Apple processing: `VALID`, `ready`, `APP_STORE_ELIGIBLE`, not expired
- Internal group `Botiverse`: `IN_BETA_TESTING` through its all-builds policy
- External group `Insiders`: assigned
- Beta App Review: `APPROVED`
- External state: `IN_BETA_TESTING`
- `auto_notify_enabled=true`
- English What to Test contains the reviewed three iOS fixes as plain text

No App Store production version was submitted or published. No iOS Hands
activation occurred. No 1.9.1 community announcement was posted.

## Product Runtime Status

Packaging and distribution evidence is complete, but iOS product status is
**RED**.

Hands tickets `b623e012`/`3ed99e7e` and
`146811f7`/`aa9e4b4f` are two physical `iPhone18,1` crash events on the exact
1000015 IPA. They raise:

```text
-[KRRichTextView hrv_callWithMethod:params:callback:]: unrecognized selector
```

Task #224 proved the official IPA/source/app UUID/renderer UUID binding and
proved the release-only Kuikly `respondsToSelector:` guard is in the admitted
renderer bytes. Disassembly and crash PC show that the predicate admitted the
call and the immediate dispatch still failed. Therefore:

- the old claim that 1000015 stopped this crash family is void;
- the release-only guard is product-invalid containment, not a root-cause fix;
- root-cause task #131 must explain receiver creation, tag/handler lookup,
  selector generation, and Objective-C resolution/forwarding/isa behavior;
- no rollout expansion or product PASS is implied by this receipt.

Task #132 then read the same official renderer's Objective-C metadata. The
`KRRichTextView` and `KRLabel` method lists, every category in all eight IPA
Mach-O images, and the release binary's DEBUG-only CSS inspector path contain
no three-argument implementation or reachable isa replacement for this class.
The selector is only an optional protocol declaration with no IMP on
`KRRichTextView`. This excludes static category pollution and the DEBUG
inspector as causes, but does not close task #131's live forwarding, dynamic
resolution, tag binding, or receiver-lifetime investigation.

The earlier source-equivalent Simulator IME matrix remains valid only for its
own keyboard behavior. It cannot override the physical crash evidence.

## Merge Back

The integration candidate starts from
`origin/main@e027d136a81d216d94d758b206e556621ac54be3` and records
`release-1.9.0@26bb45829e0f21de35613d965f39d38b48244d8d` as its real second
parent.

Conflict resolution intentionally preserves current main's Kuikly
`c7a021270847434a5f5b9aa3e94e44ce17726519` pin and matching OHOS HAR instead
of importing the known-invalid release-only `d1ae9e290...` guard. The shipped
carrier identity above remains immutable; task #131 owns the causal successor.
Other reviewed release-only changes, notes, minimum-iOS update, and iOS
release-note contract are preserved.

- Merge-back PR: #2052
- Landed main exact: pending protected merge

Repository closeout is not complete until the merge-back PR has terminal exact
checks, independent review, a clean conflict-marker/resource scan, and an
actual landed main SHA. Product iOS RED remains open after repository closeout
until task #131's lift condition is met.
