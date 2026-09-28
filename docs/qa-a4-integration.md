# Test-only A4 integration revision 4 — DO NOT MERGE

Shared PR2730: f61a8c517197eb8572b96d487ec89e067505b1dd (rebased main includes layout 2727/2728).
Native PR2731: 6c31dfbfec76b59cee54bc7b16aa6d9bc3791ebe.

This QA branch alone sets the source initiation default and nativeGuardedCommitAvailable true.
Product branches retain disabled gates. Launch AppShell workspace/Rail flags on in the existing isolated iPad.
No messages or attachments are sent. The original carrier 6d6d884e4 proved the real native-push path was excluded by its inherited shell scope.
This revision uses the explicit embedded-child marker instead, retaining shell ownership.
Re-run real Home Compact channel → Wide, then draft editing/replacement/cancel/retry/commit-order cases.
Debug simulator results do not establish TestFlight or physical-device acceptance.

Revision 3 additionally accepts an unchanged Change receipt only for the same draft identity and exact native text. Revision 2 reached initiation but stopped at DraftCaptureFailed; no native prepare/commit was covered. Re-run that real entry first.

Revision 4 includes the test-class brace correction; product logic is identical to revision 3. Rebuilt against the final shared head.
