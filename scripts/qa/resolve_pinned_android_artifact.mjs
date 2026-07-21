import fs from "node:fs";

export function resolvePinnedAndroidArtifact(payload, runId) {
  const artifacts = Array.isArray(payload?.artifacts) ? payload.artifacts : [];
  const totalCount = Number(payload?.total_count ?? artifacts.length);
  if (!Number.isInteger(totalCount) || totalCount !== artifacts.length) {
    throw new Error("server artifact metadata is incomplete or paginated");
  }
  const candidates = artifacts.filter((artifact) =>
    artifact?.expired === false && /^android-alpha-[0-9]+-[0-9a-f]{40}$/.test(String(artifact?.name || ""))
  );
  if (candidates.length !== 1) throw new Error("expected exactly one canonical android-alpha artifact");
  const artifact = candidates[0];
  const sourceCommit = artifact.name.slice(artifact.name.lastIndexOf("-") + 1);
  const workflowRun = artifact.workflow_run || {};
  if (String(workflowRun.id || "") !== String(runId)) throw new Error("artifact workflow run id mismatch");
  if (String(workflowRun.head_sha || "") !== sourceCommit) throw new Error("artifact name/source head mismatch");
  return {
    artifactId: String(artifact.id || ""),
    artifactName: artifact.name,
    buildRunId: String(runId),
    sourceCommit,
  };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  try {
    const [inputPath, outputPath, runId] = process.argv.slice(2);
    const result = resolvePinnedAndroidArtifact(JSON.parse(fs.readFileSync(inputPath, "utf8")), runId);
    fs.writeFileSync(outputPath, JSON.stringify(result));
    console.log(JSON.stringify(result));
  } catch (error) {
    console.error(`pinned Android artifact metadata error: ${error.message}`);
    process.exit(2);
  }
}
