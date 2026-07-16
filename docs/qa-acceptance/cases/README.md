# QA acceptance — case library

Structured acceptance cases for the Raft app, executed by the agent-in-CI
acceptance executor (`scripts/qa/acceptance_agent.mjs`, workflow
`qa-acceptance.yml` — see #proj-quality tasks #1–#4).

- **Schema**: [`../qa-acceptance-case-schema.md`](../qa-acceptance-case-schema.md)
  (v0.1, owned by QA). Steps are goal + hints — the agent locates elements
  itself (text + bounds; Kuikly exposes no resource ids). Assertions stay
  machine-checkable; hard assertions run before any model-judged check.
- **Operation playbook**: [`../qa-acceptance-operations.md`](../qa-acceptance-operations.md)
  — how to drive the app accurately. After every failed round, the root cause
  is distilled back into the playbook or a case's known-pitfalls notes.
- **Credentials** are never inline: `credentials_ref` resolves in the executor
  from CI secrets (`QA_TEST_EMAIL` / `QA_TEST_PASSWORD`).

| # | Case | Covers |
|---|------|--------|
| 01 | login-email-password | cold start → login → home |
| 02 | send-message-in-channel | channel → compose → send → verify |
| 03 | channel-switch-navigation | home → channel A → back → channel B |
| 04 | task-flow-create-and-claim | message long-press → create task |
| 05 | settings-open-and-navigate | settings subpages load |
