# Android Hands Release Input Migration

The Android release workflow now uses `publish_hands` as its canonical publish
input. Callers should send the string value `true` or `false`; leaving it blank
defaults to `false` in the workflow.

The former `publish_quiver` input remains as a compatibility alias through
2026-08-31. The resolver accepts either name, emits a deprecation warning when
the alias is used, and fails closed when both names are set to different
values. The canonical dispatch wrapper sends only `publish_hands`.

Local callers should migrate to:

```bash
SLOCK_PUBLISH_HANDS=true scripts/release_android_hands_github.sh \
  --version 1.8.0 --build 0 --channel main --variant release
```

The old script paths remain executable wrappers during the same compatibility
window. Removing the alias or wrappers requires a separate change after all
external dispatchers have migrated and the repository residual scan is clean.

This migration does not combine publish, share, or activation authority:

- `publish_hands=false` builds the signed artifacts without creating a Hands
  release.
- `publish_hands=true` creates the existing draft-only Hands release action.
- `create_share_page` remains an independent optional action within the publish
  job.
- Activation remains a separate owner-authorized post-smoke operation and is
  never performed by the Android release workflow.
