# Test-only A4 integration revision 2 — DO NOT MERGE

Shared PR2730: ebf0a27ee08e2d9e0b828b8b50d0b8c1a3911b7a (rebased main includes layout 2727/2728).
Native PR2731: 6c31dfbfec76b59cee54bc7b16aa6d9bc3791ebe.

This QA branch alone sets the source initiation default and nativeGuardedCommitAvailable true.
Product branches retain disabled gates. Launch AppShell workspace/Rail flags on in the existing isolated iPad.
No messages or attachments are sent. The original carrier 6d6d884e4 proved the real native-push path was excluded by its inherited shell scope.
This revision uses the explicit embedded-child marker instead, retaining shell ownership.
Re-run real Home Compact channel → Wide, then draft editing/replacement/cancel/retry/commit-order cases.
Debug simulator results do not establish TestFlight or physical-device acceptance.
