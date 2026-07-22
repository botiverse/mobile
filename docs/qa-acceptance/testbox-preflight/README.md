# Android visual Testbox preflight evidence

`testbox-preflight-manifest.v1.schema.json` defines non-terminal development
evidence. Every accepted manifest is permanently fixed to:

```text
evidence_class = testbox_preflight
terminal_eligible = false
matrix_coverage = false
product_verdict = not_allowed
coordinate_id = null
```

The bundle is attachment/link evidence only. It is not a Stamp run or device
coordinate, is ignored by product aggregation, is never published to Pages,
and cannot replace scheduled visual, Hosted, DCO, physical-device, RC, Stamp,
or release evidence.

Build a manifest from a complete capture directory and a reviewed metadata
document:

```bash
python3 scripts/qa/build_testbox_preflight_manifest.py \
  --metadata testbox-preflight-metadata.json \
  --evidence-dir visual-testing-results \
  --output visual-testing-results/testbox-preflight-manifest.v1.json
```

The builder rejects the whole bundle if any file is outside the allowlist, a
required field is missing, an enum is unknown, an APK source/installed hash
differs, the harness is incomplete, sensitive material is detected, or a
derived artifact has no transformation path back to a raw full-frame PNG.
Runner receipt v3 signs each case's exact PNG/metadata bytes and capture wall /
monotonic window, and freezes the app package/UID/PID, boot ID, and random run
generation used by the epoch-scoped logcat. Raw PNG validation is bounded to
the Phase 1 geometry and strict file/chunk/decompressed byte ceilings before
any pixel allocation.
It writes `SHA256SUMS` for evidence files, binds the exact schema SHA-256, runs
the checked-in schema itself, and writes a canonical manifest hash whose
input is compact sorted-key UTF-8 JSON with the canonical hash field replaced
by 64 zeroes. Partial ingestion is forbidden.

The bundle must also include the raw successful `blacksmith testbox stop`
output at `controls/testbox-stop.txt`, plus full v3 runner receipts for the
same-exact known-green and an injected `expected_method_mismatch` known-red.

The first review sample must use the real product route
`components.settings.appearance.page`, preserve the original full-frame PNG,
and bind its fixture, rendered-goal hash, renderer, settle oracle, baseline,
comparison tool, thresholds, and mask. Any crop, annotation, diff, or heatmap
is derived evidence and must name its transformation version, input hashes,
output hash, wall/monotonic times, and exit code.
