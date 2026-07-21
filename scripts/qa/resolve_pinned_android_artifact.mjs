import fs from "node:fs";

const INSTALLABLE_NAME = /^(?:android-release-apk|qa-acceptance-apk|android-alpha-[0-9]+-[0-9a-f]{40})$/;

function verifyArtifact(artifact, runId, exactArtifactId = "") {
  if (!artifact || artifact.expired !== false) throw new Error("installable APK artifact is missing or expired");
  if (exactArtifactId && String(artifact.id || "") !== String(exactArtifactId)) throw new Error("artifact id mismatch");
  if (!INSTALLABLE_NAME.test(String(artifact.name || ""))) throw new Error("artifact id is not an installable Android APK artifact");
  const workflowRun = artifact.workflow_run || {};
  if (String(workflowRun.id || "") !== String(runId)) throw new Error("artifact workflow run id mismatch");
  return {
    artifactId: String(artifact.id || ""),
    artifactName: artifact.name,
    buildRunId: String(runId),
  };
}

export function resolvePinnedAndroidArtifact(payload, runId, exactArtifactId = "") {
  if (exactArtifactId) return verifyArtifact(payload, runId, exactArtifactId);
  const artifacts = Array.isArray(payload?.artifacts) ? payload.artifacts : [];
  const totalCount = Number(payload?.total_count ?? artifacts.length);
  if (!Number.isInteger(totalCount) || totalCount !== artifacts.length) {
    throw new Error("server artifact metadata is incomplete or paginated");
  }
  const candidates = artifacts.filter((artifact) =>
    artifact?.expired === false && INSTALLABLE_NAME.test(String(artifact?.name || ""))
  );
  if (candidates.length !== 1) throw new Error("expected exactly one installable Android APK artifact");
  return verifyArtifact(candidates[0], runId);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  try {
    const [inputPath, outputPath, runId, artifactId = ""] = process.argv.slice(2);
    const result = resolvePinnedAndroidArtifact(JSON.parse(fs.readFileSync(inputPath, "utf8")), runId, artifactId);
    fs.writeFileSync(outputPath, JSON.stringify(result));
    console.log(JSON.stringify(result));
  } catch (error) {
    console.error(`pinned Android artifact metadata error: ${error.message}`);
    process.exit(2);
  }
}
