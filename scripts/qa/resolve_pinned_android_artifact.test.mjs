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

test("selects one legacy installable artifact without deriving source from its name", () => {
  assert.deepEqual(resolvePinnedAndroidArtifact({ total_count: 1, artifacts: [canonical] }, runId), {
    artifactId: "8438855615", artifactName: canonical.name, buildRunId: runId,
  });
});

test("selects the frozen production release APK by exact artifact id", () => {
  const release = {
    id: 8495589060,
    name: "android-release-apk",
    expired: false,
    workflow_run: { id: 29831259094, head_sha: "fb3f7ac5b3c0dbd12a910bdfd528e1374fd5a7a9" },
  };
  assert.deepEqual(resolvePinnedAndroidArtifact(release, "29831259094", "8495589060"), {
    artifactId: "8495589060", artifactName: "android-release-apk", buildRunId: "29831259094",
  });
  assert.throws(() => resolvePinnedAndroidArtifact({ ...release, id: 8495589779 }, "29831259094", "8495589060"), /artifact id mismatch/);
  assert.throws(() => resolvePinnedAndroidArtifact({ ...release, name: "android-release-native-symbols" }, "29831259094", "8495589060"), /not an installable/);
});

test("ignores mapping and native-symbol side artifacts from the same release run", () => {
  const mapping = {
    id: 8438855616,
    name: `android-alpha-mapping-588-${source}`,
    expired: false,
    workflow_run: { id: Number(runId), head_sha: source },
  };
  const symbols = {
    id: 8438855617,
    name: `android-alpha-native-symbols-588-${source}`,
    expired: false,
    workflow_run: { id: Number(runId), head_sha: source },
  };
  assert.deepEqual(
    resolvePinnedAndroidArtifact({ total_count: 3, artifacts: [mapping, canonical, symbols] }, runId),
    {
      artifactId: "8438855615",
      artifactName: canonical.name,
      buildRunId: runId,
    },
  );
});

test("rejects missing, duplicate, expired, or malformed canonical artifacts", () => {
  for (const payload of [
    { total_count: 0, artifacts: [] },
    { total_count: 2, artifacts: [canonical, { ...canonical, id: 2 }] },
    { total_count: 1, artifacts: [{ ...canonical, expired: true }] },
    { total_count: 1, artifacts: [{ ...canonical, name: "android-release-mapping" }] },
  ]) assert.throws(() => resolvePinnedAndroidArtifact(payload, runId), /exactly one/);
});

test("rejects paginated metadata and server-side run mismatches", () => {
  assert.throws(() => resolvePinnedAndroidArtifact({ total_count: 2, artifacts: [canonical] }, runId), /incomplete or paginated/);
  assert.throws(() => resolvePinnedAndroidArtifact({ total_count: 1, artifacts: [{ ...canonical, workflow_run: { ...canonical.workflow_run, id: 1 } }] }, runId), /run id mismatch/);
});
