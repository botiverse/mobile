import test from "node:test";
import assert from "node:assert/strict";
import { resolvePinnedAndroidArtifact } from "./resolve_pinned_android_artifact.mjs";

const runId = "29675780713";
const source = "80d4ebcd6af6f7b5b7bda233e39bba130f4a9a58";
const canonical = {
  id: 8438855615,
  name: `android-alpha-588-${source}`,
  expired: false,
  workflow_run: { id: Number(runId), head_sha: source },
};

test("derives source only from a unique server-bound canonical artifact", () => {
  assert.deepEqual(resolvePinnedAndroidArtifact({ total_count: 1, artifacts: [canonical] }, runId), {
    artifactId: "8438855615", artifactName: canonical.name, buildRunId: runId, sourceCommit: source,
  });
});

test("rejects missing, duplicate, expired, or malformed canonical artifacts", () => {
  for (const payload of [
    { total_count: 0, artifacts: [] },
    { total_count: 2, artifacts: [canonical, { ...canonical, id: 2 }] },
    { total_count: 1, artifacts: [{ ...canonical, expired: true }] },
    { total_count: 1, artifacts: [{ ...canonical, name: "qa-acceptance-apk" }] },
  ]) assert.throws(() => resolvePinnedAndroidArtifact(payload, runId), /exactly one/);
});

test("rejects paginated metadata and server-side run or head mismatches", () => {
  assert.throws(() => resolvePinnedAndroidArtifact({ total_count: 2, artifacts: [canonical] }, runId), /exactly one server artifact/);
  assert.throws(() => resolvePinnedAndroidArtifact({ total_count: 1, artifacts: [{ ...canonical, workflow_run: { ...canonical.workflow_run, id: 1 } }] }, runId), /run id mismatch/);
  assert.throws(() => resolvePinnedAndroidArtifact({ total_count: 1, artifacts: [{ ...canonical, workflow_run: { ...canonical.workflow_run, head_sha: "4".repeat(40) } }] }, runId), /name\/source head mismatch/);
});
