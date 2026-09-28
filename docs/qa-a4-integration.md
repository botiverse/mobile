# Test-only A4 integration revision 10 — DO NOT MERGE

Shared PR2730: d1421b4a7040e5c20b350d6337dc2bf0d35931cd (rebased main includes layout 2727/2728).
Native PR2731: 6c31dfbfec76b59cee54bc7b16aa6d9bc3791ebe.

This QA branch alone sets the source initiation default and nativeGuardedCommitAvailable true.
Product branches retain disabled gates. Launch AppShell workspace/Rail flags on in the existing isolated iPad.
No messages or attachments are sent. The original carrier 6d6d884e4 proved the real native-push path was excluded by its inherited shell scope.
This revision uses the explicit embedded-child marker instead, retaining shell ownership.
Re-run real Home Compact channel → Wide, then draft editing/replacement/cancel/retry/commit-order cases.
Debug simulator results do not establish TestFlight or physical-device acceptance.

Revision 3 additionally accepts an unchanged Change receipt only for the same draft identity and exact native text. Revision 2 reached initiation but stopped at DraftCaptureFailed; no native prepare/commit was covered. Re-run that real entry first.

Revision 4 includes the test-class brace correction; product logic is identical to revision 3. Rebuilt against the final shared head.

Revision 5 carries numeric native editor refTag across the shared bridge. Revision 4 reached prepare but native rejected the JSON string refTag as editor_drift. Re-run that same real entry before attempting commit timing cases.

Revision 6 includes real JSON serialization tests and portable omission of absent refTag. Revision 5 did not build: the Kuikly JSONObject API has no NULL constant. Native strict validation is unchanged.

Revision 7 adds persistent shell OPEN/READY/ACK and destination effect/payload/layout/READY stage diagnostics. Revision 6 progressed through native prepare, then timed out awaiting ACK; manual Back subsequently exposed the embedded detail and is not a successful migration. No native guard changes. Missing-stage absence is not proof of non-execution without a logging and lifecycle control.

Revision 8 moves destination READY above the empty-state return and permits successful empty timelines, retaining loading/error rejection and identity/generation checks. Both effects now key on payload status. Revision 7 accepted OPEN but skipped every detail effect for an empty channel and timed out; re-run the same empty channel before the remaining commit matrix. Native lifecycle and commit guards are unchanged.

Revision 9 moves empty READY above the outer ThreadChatTabContent status dispatch and uses actual parser terminal states empty/content. Revision 8 still bypassed the entire list in empty_state and accepted an unused success status. Shared posted state now connects outer empty readiness to inner nonempty restore readiness; state declarations/import are included. Repeat real compact Home → empty channel → wide before any broader acceptance.

Revision 10 adds Debug-only native stage diagnostics for adaptive detail child hosts (pageData, frame, layout, attach, load and visibility state). Revision 9 times out while covered and posts READY/ACK only after manual Back. No lifecycle, mounting or commit behavior is changed; probes distinguish missing layout/attachment from post-attachment lifecycle suspension. Shared source remains d1421b4a7.
