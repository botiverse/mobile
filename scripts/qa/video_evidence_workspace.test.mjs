import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { createVideoEvidenceWorkspace } from "./video_evidence_workspace.mjs";

test("video workspace preserves original bytes and lets the agent choose exact frame times", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "video-evidence-"));
  const commands = [];
  const run = (command, args) => {
    commands.push([command, ...args]);
    if (command === "ffprobe") {
      return { status: 0, stdout: JSON.stringify({ format: { duration: "8", size: "5", format_name: "mov,mp4" }, streams: [{ codec_type: "video", width: 720, height: 1280 }] }), stderr: "" };
    }
    fs.writeFileSync(args.at(-1), Buffer.from(`jpeg-${args[5]}`));
    return { status: 0, stdout: "", stderr: "" };
  };
  const workspace = createVideoEvidenceWorkspace({
    root,
    recordings: [{ id: "recording-1", sha256: "abc123" }],
    bytesById: new Map([["recording-1", Buffer.from("video")]]),
    run,
  });

  assert.equal(fs.readFileSync(path.join(root, "recording-1.mp4"), "utf8"), "video");
  assert.equal(workspace.inspectVideo("recording-1").duration_ms, 8000);
  const frame = workspace.extractFrame("recording-1", 1375);
  assert.equal(frame.timestamp_ms, 1375);
  assert.equal(frame.source_sha256, "abc123");
  assert.equal(frame.bytes.toString(), "jpeg-1.375");
  assert.equal(commands.filter(([name]) => name === "ffmpeg").length, 1);
  assert.match(commands.find(([name]) => name === "ffmpeg").join(" "), /scale=min\(1024\\,iw\):-2/);
  assert.deepEqual(workspace.toolLog.map(({ command: _command, ...entry }) => entry), [
    { tool: "inspect_video", evidence_id: "recording-1", source_sha256: "abc123" },
    { tool: "extract_video_frame", evidence_id: "recording-1", source_sha256: "abc123", timestamp_ms: 1375 },
  ]);
  fs.rmSync(root, { recursive: true, force: true });
});

test("video workspace rejects platform-selected or out-of-range extraction", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "video-evidence-"));
  const run = (command) => command === "ffprobe"
    ? { status: 0, stdout: JSON.stringify({ format: { duration: "2" }, streams: [] }), stderr: "" }
    : { status: 1, stdout: "", stderr: "unexpected" };
  const workspace = createVideoEvidenceWorkspace({
    root,
    recordings: [{ id: "r", sha256: "sha" }],
    bytesById: new Map([["r", Buffer.from("video")]]),
    run,
  });
  assert.throws(() => workspace.extractFrame("r", -1), /within 0\.\.2000/);
  assert.throws(() => workspace.extractFrame("r", 2001), /within 0\.\.2000/);
  fs.rmSync(root, { recursive: true, force: true });
});
