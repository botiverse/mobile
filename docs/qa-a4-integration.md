# Test-only A4 iPad integration carrier — DO NOT MERGE

Native PR2731: 78f72b3990e3facc2606367f39856b66368b632d.
Shared PR2730: f05909f83 (merged exact ref from origin/qa-pr2730).
Layout integration: 1c0f5f3bd43edd281225f963862a9730735ca138 (PR2727/2728).

Only this QA branch enables nativeGuardedCommitAvailable and the source initiation default.
Product branches retain disabled gates. Use only the existing isolated Jony iPad QA account.
Launch AppShell with slockAdaptiveHomeWorkspaceEnabled=true and slockAdaptiveNavRailEnabled=true.
Enter a real channel in Compact, rotate to Wide; inspect native commit receipt and preservation.
No messages or attachments should be sent. This is a Debug simulator carrier, not a release.

First matrix: normal adoption, draft changed during wait, editor replaced, cancellation/retry,
and no source dismissal on shared ACK/READY/dispatch alone. Report any uncovered interleavings.
