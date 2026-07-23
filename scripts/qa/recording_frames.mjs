import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

export function frameTimestamps(durationSeconds, count = 3) {
  if (!Number.isFinite(durationSeconds) || durationSeconds <= 0 || count < 1) return [];
  return Array.from({ length: count }, (_, index) =>
    Math.round(durationSeconds * ((index + 1) / (count + 1)) * 1000) / 1000);
}

export function extractRecordingFrames({ bytes, evidence, outDir, run = spawnSync, count = 3 }) {
  fs.mkdirSync(outDir, { recursive: true });
  const input = path.join(outDir, `${evidence.id}.mp4`);
  fs.writeFileSync(input, bytes, { mode: 0o600 });
  const probe = run("ffprobe", ["-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", input], { encoding: "utf8" });
  const duration = Number(probe.stdout);
  const frames = [];
  for (const timestamp of frameTimestamps(duration, count)) {
    const output = path.join(outDir, `${evidence.id}-${String(Math.round(timestamp * 1000)).padStart(8, "0")}.jpg`);
    const result = run("ffmpeg", ["-v", "error", "-ss", String(timestamp), "-i", input, "-frames:v", "1", "-vf", "scale='min(1024,iw)':-2", "-y", output], { encoding: "utf8" });
    if (result.status === 0 && fs.existsSync(output)) {
      frames.push({
        evidence_id: evidence.id,
        source_sha256: evidence.sha256,
        timestamp_ms: Math.round(timestamp * 1000),
        bytes: fs.readFileSync(output),
      });
    }
  }
  fs.rmSync(outDir, { recursive: true, force: true });
  return frames;
}
