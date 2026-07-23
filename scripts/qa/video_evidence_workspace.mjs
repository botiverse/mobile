import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

function safeId(value) {
  return String(value).replace(/[^A-Za-z0-9._-]/g, "_");
}

function commandResult(result, command) {
  if (result.error) throw new Error(`${command}: ${result.error.message}`);
  if (result.status !== 0) throw new Error(`${command}: ${(result.stderr || "failed").trim().slice(0, 500)}`);
  return result;
}

export function createVideoEvidenceWorkspace({ root, recordings, bytesById, run = spawnSync, maxFrames = 18 }) {
  fs.mkdirSync(root, { recursive: true, mode: 0o700 });
  const sources = new Map();
  const toolLog = [];
  let extractedFrames = 0;

  for (const evidence of recordings) {
    const filename = `${safeId(evidence.id)}.mp4`;
    const file = path.join(root, filename);
    fs.writeFileSync(file, bytesById.get(evidence.id), { mode: 0o600 });
    sources.set(evidence.id, { evidence, file, filename, probe: null });
  }

  function source(id) {
    const found = sources.get(String(id));
    if (!found) throw new Error(`unknown recording evidence id: ${id}`);
    return found;
  }

  function inspectVideo(evidenceId) {
    const item = source(evidenceId);
    if (item.probe) return item.probe;
    const args = [
      "-v", "error", "-show_entries",
      "format=duration,size,format_name:stream=index,codec_type,codec_name,width,height,avg_frame_rate",
      "-of", "json", item.file,
    ];
    const result = commandResult(run("ffprobe", args, { encoding: "utf8" }), "ffprobe");
    const parsed = JSON.parse(result.stdout || "{}");
    const durationMs = Math.max(0, Math.round(Number(parsed?.format?.duration || 0) * 1000));
    item.probe = {
      evidence_id: item.evidence.id,
      source_sha256: item.evidence.sha256,
      duration_ms: durationMs,
      size_bytes: Number(parsed?.format?.size || fs.statSync(item.file).size),
      format_name: parsed?.format?.format_name || null,
      streams: Array.isArray(parsed?.streams) ? parsed.streams : [],
    };
    toolLog.push({ tool: "inspect_video", evidence_id: item.evidence.id, source_sha256: item.evidence.sha256, command: ["ffprobe", ...args.slice(0, -1), item.filename] });
    return item.probe;
  }

  function extractFrame(evidenceId, timestampMs) {
    const item = source(evidenceId);
    const probe = inspectVideo(evidenceId);
    const requested = Number(timestampMs);
    if (!Number.isInteger(requested) || requested < 0 || (probe.duration_ms > 0 && requested > probe.duration_ms)) {
      throw new Error(`timestamp_ms must be an integer within 0..${probe.duration_ms}`);
    }
    if (extractedFrames >= maxFrames) throw new Error(`frame extraction limit reached (${maxFrames})`);
    extractedFrames++;
    const outputName = `${safeId(item.evidence.id)}-${String(requested).padStart(10, "0")}.jpg`;
    const output = path.join(root, outputName);
    const seconds = (requested / 1000).toFixed(3);
    const args = [
      "-v", "error", "-i", item.file, "-ss", seconds, "-frames:v", "1",
      "-vf", "scale=min(1024\\,iw):-2", "-q:v", "3", "-y", output,
    ];
    commandResult(run("ffmpeg", args, { encoding: "utf8" }), "ffmpeg");
    if (!fs.existsSync(output) || fs.statSync(output).size === 0) throw new Error("ffmpeg produced no frame");
    const receipt = {
      tool: "extract_video_frame",
      evidence_id: item.evidence.id,
      source_sha256: item.evidence.sha256,
      timestamp_ms: requested,
      command: ["ffmpeg", ...args.slice(0, 3), item.filename, ...args.slice(4, -1), outputName],
    };
    toolLog.push(receipt);
    return { ...receipt, bytes: fs.readFileSync(output) };
  }

  return {
    manifest: recordings.map((evidence) => ({
      evidence_id: evidence.id,
      source_sha256: evidence.sha256,
      filename: sources.get(evidence.id).filename,
      size_bytes: fs.statSync(sources.get(evidence.id).file).size,
    })),
    inspectVideo,
    extractFrame,
    toolLog,
  };
}
