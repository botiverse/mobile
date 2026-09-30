# iOS external permalink association handoff

This is a source/configuration delivery for task49 / PR2775, not a deployment or
signed-device acceptance result. The coordinator assigned the iOS project change
to Jony and profile/final-package validation to Codex-Android-DevOPS on 2026-09-30.
The web deployment owner is being resolved by SangLan.

## Native configuration and identity

Both Debug and Release use `iosApp/iosApp.entitlements`. They now declare only
`applinks:app.raft.build`, without a wildcard, path, alternate developer mode, or
webcredentials service. Both configurations use bundle ID `build.raft.app`.
The release workflow obtains `DEVELOPMENT_TEAM` from its signing configuration;
this change does not override it.

The live AASA fetched at 2026-09-30 12:04Z contains application identifier
`XDAPXFY8FZ.build.raft.app`. This is the proposed association identity, not proof
that a new archive/profile is signed by that identity. DevOPS must compare the
final archive's `application-identifier`, team identifier, associated-domains
entitlement and embedded provisioning profile with this value. Enable the
Associated Domains capability for that App ID and refresh the profile through
the existing signing process if necessary.

`app.slock.ai` currently redirects its AASA URL to the canonical host (HTTP301).
`staging.slock.ai` did not resolve from the test Mac. These parser-approved hosts
are not added to the entitlement in this delivery. Each needs its own reachable,
non-redirected association before a future configuration expansion; accepting a
host in the strict parser does not establish system association for that host.

## Proposed web path delta — not published

`ios-external-permalink-association.proposed.json` is an additive review artifact
for the canonical host. It retains the observed `/download` rule and adds:

- `/s/*/channel/*`: channel links, including reply links using `thread` and `msg`.
- `/s/*/dm/*`: direct-message links, including optional message anchors.

The native parser still validates path shape and all supplied anchors. No query
or fragment is synthesized by the association. Authentication, account selection
and server readiness remain shared responsibilities after system delivery.

Do not replace the live file blindly: re-read it at deployment and retain any
other app identities/services/rules added since this snapshot. Serve the agreed
file at `https://app.raft.build/.well-known/apple-app-site-association` as JSON
with no redirect. The web owner publishes via the established deployment lane.

### Existing download path

`/download` is already in the live AASA. This change does not repurpose it as a
conversation and does not add a browser-reopen fallback for rejected URLs.
The current permalink parser rejects `/download`; its handler leaves the normal
app screen alone. Activating the domain capability makes this existing rule
relevant to this signed app too. Include `/download` in device regression and
have the web owner confirm whether it remains an app-launch entry before
releasing the coordinated configuration. No download routing PASS is claimed.

## Verification and limits

Local validation: plist/JSON syntax, Debug/Release entitlement linkage, source
constraints, source-path contract, diff whitespace. Transport tests cover native
queue behavior separately. Hosted product compilation does not verify profile or
AASA matching; a simulator build with code signing disabled may carry no effective
entitlements at all.

Before deployment, an actual system URL-open on the existing QA build58515924c
lands in Safari. That package lacks the new entitlement, and live AASA includes
only `/download`. This does not diagnose the integrated shared navigation chain.
After the coordinated configuration is available, validate a signed install with
an external app's HTTPS link: channel/DM/reply, same URL tapped again, warm/cold
entry, authentication cancellation, and `/download`. Bind results to archive,
profile identity, AASA/CDN contents and the exact source. Keep simulator injected
callbacks and physical system delivery as distinct evidence.

Apple references: [Supporting associated domains](https://developer.apple.com/documentation/xcode/supporting-associated-domains)
and [TN3155: Debugging universal links](https://developer.apple.com/documentation/technotes/tn3155-debugging-universal-links).
