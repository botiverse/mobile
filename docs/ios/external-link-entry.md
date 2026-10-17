# iOS HTTPS external-link entry

The scene delegate passes `NSUserActivityTypeBrowsingWeb` activities to the
shared external-link coordinator before creating the initial page, and again on
warm `scene(_:continue:)` delivery. It passes the original HTTPS URL, a native
activation identifier and the receiving scene/shell identity. Parsing,
authentication generations, pending links and route consumption belong to shared.
Custom-scheme OAuth callbacks and internal shell-window activities keep their
existing handlers.

The first page receives the registered scene context directly. Its `slockSceneId`
is the actual `UISceneSession.persistentIdentifier`; `slockShellInstanceId` is the
existing shared shell scope. Shared readiness must match both values. Closing or
retargeting a scene retires only that scene's pending external-link ownership;
reactivating its unchanged shell does not discard links. A repeated delivery of
the same activity to the same owner keeps its activation identifier. A new tap
or a different scene remains a distinct activation.

Web login, legacy providers and Apple sign-in take their presentation window from
the requesting render view, then freeze scene, shell and browser-attempt identity. The attempt identity is a
native UUID, not the server's OAuth request ID. Started/cancelled/failed/superseded
notifications use that same snapshot. A late callback from a replaced attempt
cannot cancel or clear the current attempt. Only `login_cancelled` is a user
cancellation; `browser_failed` and `browser_superseded` retire the request without
discarding a link as if the user had cancelled it. No raw URLs or credentials are
written to native diagnostics. Account-link operations do not acquire external-link
login ownership. A native revision also fences deferred cold callbacks, retries
and asynchronous cancellation results when a new login begins; it does not undo
a shared authentication exchange already performed.

## Integration dependency

This native change consumes the task 479 shared singleton
`SlockExternalLinkCoordinator.shared`:

- `offer(rawURL:activationId:sceneId:shellScopeId:requestId:)`; the native cold
  entry passes nil request identity and shared binds it when login starts.
- `loginStarted(sceneId:shellScopeId:requestId:)`.
- `cancel(sceneId:shellScopeId:requestId:reason:)`.
- `disconnect(sceneId:)`.

Shared supplies readiness and destination consumption. The native change cannot
be compiled against a framework that predates this API. Verify the real exported
Swift signatures and run the full iOS build after integrating the shared change.

## Verification and system-delivery prerequisites

Run `bash compose/iosApp/scripts/test_external_link_ingress.sh` for the actual
Foundation ingress implementation. The focused macOS workflow executes the same
suite. It covers raw URL preservation, immediate delivery, duplicate/new
activations, separate scene ownership, rejected activity kinds and disconnect
isolation. It does not exercise UIKit browser presentation or route consumption.
Use `ios-simulator-app.yml` with the integrated exact source SHA for the full app
compile. Do not treat the ordinary PR jobs' skipped iOS lane as compilation.

The source entitlement declares `app.raft.build` and
`raft-app-staging.botiverse.dev`. A distributable build also needs a provisioning
profile with Associated Domains and matching AASA entries. The Oct 16, 2026 live
audit found both sites' AASA app ID `XDAPXFY8FZ.build.raft.app` matched the bundle,
but their paths only covered `/download`; the audited signed 1.14.0/11400009
profile and entitlements did not include Associated Domains. These are distinct
website/signing updates, not evidence of current HTTPS message delivery. Website
PR botiverse/slock#10031 subsequently merged the channel/DM association into
staging; deployment and Apple CDN uptake remain separate verification steps.

After both prerequisites are updated, install a newly signed carrier and open a
real message/thread link from another app. Test cold and warm delivery, signed-out
login/resume, explicit login cancel, replacing a login, re-opening a consumed link,
and an independent scene. Record source/build/IPA identity and actual device or
simulator separately. Unit tests and source review are not that system test.
