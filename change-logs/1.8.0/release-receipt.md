# Mobile 1.8.0 Release Receipt

Released: 2026-07-29

## Frozen Source

- Mobile source:
  `7abf25cccd8e95a0e6fbf2ec4904fc62658424f2`
- Immutable carrier ref:
  `linzhou/final-carrier-1.8.0-7abf25cc`
- Release parent:
  `61fdb38e971d644e0343aa40dfe6f9e7c26da9ae`
- Release tree:
  `466eda69b4abeadaf5c4709cf3ca5d77850434a7`
- Final release fix: PR #1723
- Exact-head Hosted run: `30431953737`, terminal `SUCCESS`

The earlier `61fdb38e` carrier, Android `1080001`, iOS `1000009`, and its
TestFlight upload are superseded evidence. They are not the released carrier.

## Approved Release Notes

Hands reads back the exact 4 New / 4 Improved / 6 Fixed notes committed here:

- `change-logs/1.8.0/android/{zh-CN,en,ja}.md`
- `change-logs/1.8.0/ios/{zh-CN,en,ja}.md`

The release owner fixed the first Chinese item to
`新增了日文的支持，优化中文翻译`. Android and iOS intentionally use the
same product release notes for this carrier.

## Android

- Version: `1.8.0 (1080002)`
- GitHub run: `30432845965`, terminal `SUCCESS`
- APK artifact: `8716351992`
- APK size: `34,640,998` bytes
- APK SHA-256:
  `37414e48b2d6c88659751dbf973081d610ade6837e1c346b60cba26535ef9a49`
- Native-symbol artifact: `8716352567`
- Native-symbol SHA-256:
  `39cae556429d4ac783bad169dff734a0755cd5a6f7ba551189f8e13791544a17`
- Hands build: `808f9473-6b82-4de1-a51a-2b361ee5ad61`
- Hands release: `c80ab671-173d-4633-9455-bef3b8395b85`
- Hands installable asset: `5ebaa465-06d3-4b7e-a727-280281c8e468`
- Hands state: `active`, revision `2`, scope `full:all`
- Public share:
  <https://hands.build/share/Mtg9h2xrM8ag1FBAI29Qd-Zo2mDPH0Tv>

Independent verification passed for signing rotation, APK Signature Scheme
v2/v3/v3.1, zip alignment, all six compressed DEX entries, all ten 16 KiB
64-bit ELF entries, and the Hands Android SDK `0.12.4` symbols. The GitHub and
Hands APK bytes are identical.

## iOS

- Version: `1.0.0 (1000010)`
- GitHub run: `30432866658`, terminal `SUCCESS`
- IPA artifact: `8716759455`
- IPA size: `52,365,445` bytes
- IPA SHA-256:
  `e6e2222c9f0990e02df35c44d7c44f08b1a99f0fc8a8eef1bf579c6456ca165b`
- dSYM artifact: `8716760210`
- dSYM size: `30,986,388` bytes
- dSYM SHA-256:
  `9afbb171c17acb19a4f1e5f639645b5adda39170da0056a3395a0919ce64e8cb`
- Hands build: `b19c4b3f-038f-436b-bf42-8e5c8e3739b5`
- Hands release: `ee666563-9a88-4125-aae2-3f2785be1a96`
- Hands IPA asset: `d43632c4-bd94-4773-a6d9-e6ecac8efaae`
- Hands dSYM asset: `7799a8f2-a540-4355-b468-152c2edce92a`
- Hands state: `active`, revision `2`, scope `full:all`

Independent verification passed for bundle/version/minimum iOS, strict
codesign, distribution profile and production entitlements, and app/dSYM UUID
identity. The GitHub and Hands IPA bytes are identical, and the GitHub and
Hands dSYM archives are identical.

Signing receipt:

- Authority: `Apple Distribution: Botiverse, Inc. (XDAPXFY8FZ)`
- Team: `XDAPXFY8FZ`
- Profile UUID: `1bcbfcf9-c6af-4417-9f5e-132506e40020`
- App/dSYM UUID: `90303C2B-39A2-363D-BDC6-D58DA4EB8473`

## TestFlight

- Upload operation: `e4747641-1a9a-4d58-b0d1-ccec894ad052`
- Build Upload / ASC build:
  `945b77fa-24da-40fd-ad5a-345b121d5045`
- Upload terminal: `COMPLETE`, with no Apple errors or warnings
- Processing: `VALID`, `ready`, `APP_STORE_ELIGIBLE`
- External publish operation: `a4656bd8-decb-4809-9b2b-7e8d320d621b`
- External group: `Insiders`
  (`2aab2b71-0fe7-48b0-9b3e-266ccb3b7800`)
- Beta App Review: `APPROVED`
- External state: `IN_BETA_TESTING`
- Notification: scheduled with `auto_notify_enabled=true`
- What to Test: `en-US`, the approved 4/4/6 English notes rendered as plain
  text for Apple's localization field, stored at
  `change-logs/1.8.0/ios/what-to-test/en-US.txt`

The existing internal `Botiverse` group remains assigned through
`has_access_to_all_builds=true`. No App Store production version was created,
submitted, or published.

## Runtime And Accepted Residuals

- Huawei SGT-AL10 Android 12/API31 runtime closed the original #897 blank
  viewport backstop for the release carrier.
- Feedback `975edf60` exposed an existing low-probability refresh-rotation
  process-death window: the old process died while refresh was in flight, and
  the next cold start reused the consumed token and received a genuine 401.
  The release owner explicitly accepted this residual for 1.8.0.
- A brief first-keyboard white flash remains a separate main-only follow-up.
  It was not backported after the final carrier was frozen.

These release receipts do not by themselves close every broader product
runtime task.

## Merge Back

- Release-to-main PR: #1724
- Merge commit: `ad4df6b746e589ace70657c936eb8be2309695f8`
- Intermediate merge repair PRs: #1728, #1730, #1731, and #1732
- Canonical two-parent cleanup PR: #1735
- Verified cleanup merge:
  `fd99b509ae005f1f48c293bb2822d30ae96a6a76`

PR #1735 restored the reviewed Settings ranking and server-identity contract,
the common Kuikly gitlink `588ecd3d`, and the complete ranking fixtures. Its
landed tree equals the reviewed exact, the shared Kotlin/XML tree has no
conflict markers, and Android full compile/tests plus OHOS shared/host passed.
The owner explicitly allowed merge with one standard connected-test timeout
in the unrelated attachment external-reader fixture after the other four
emulator jobs passed; its failed-job rerun continued after merge.

Future release merge-backs must scan the landed tree for conflict markers and
run affected resource generation and shared compile/test gates before the
merge is considered complete.
