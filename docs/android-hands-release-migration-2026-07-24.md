# Android Hands Release Input Migration

The Android release workflow now separates draft creation from activation.
Every manual release dispatch uploads the signed carrier and creates a Hands
draft. Manual callers cannot skip that step.

Reusable QA callers remain build-only. They must explicitly pass
`create_hands_draft: false`, and the draft job additionally requires a manual
`workflow_dispatch` event, so a reusable caller cannot enter the production
Hands environment even if its inputs drift.

Local release callers should use the canonical wrapper:

```bash
scripts/release_android_hands_github.sh \
  --version 1.8.0 --build 0 --channel main --variant release
```

The old script-path wrappers were removed with the repository-wide identifier
migration. External callers must use the canonical Hands paths above.

This migration does not combine draft, share, or activation authority:

- Manual release dispatches always upload assets and create a draft.
- Reusable QA workflows explicitly remain build-only.
- `create_share_page` remains an independent optional action within the draft
  job.
- Activation remains a separate owner-authorized post-smoke operation and is
  never performed by the Android release workflow.
