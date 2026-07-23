import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { extractRecordingFrames, frameTimestamps } from "./recording_frames.mjs";

test("recording frame timestamps are bounded and deterministic", () => {
  assert.deepEqual(frameTimestamps(20, 3), [5, 10, 15]);
  assert.deepEqual(frameTimestamps(0, 3), []);
});

test("extracted frames retain source evidence SHA and exact timestamp", () => {
  const outDir = fs.mkdtempSync(path.join(os.tmpdir(), "recording-frames-"));
  const run = (command, args) => {
    if (command === "ffprobe") return { status: 0, stdout: "8\n" };
    const output = args.at(-1);
    fs.writeFileSync(output, Buffer.from(`jpeg-at-${args[3]}`));
    return { status: 0, stdout: "" };
  };
  const frames = extractRecordingFrames({
    bytes: Buffer.from("video"),
    evidence: { id: "recording-1", sha256: "abc123" },
    outDir,
    run,
    count: 3,
  });
  assert.deepEqual(frames.map(({ bytes, ...frame }) => frame), [
    { evidence_id: "recording-1", source_sha256: "abc123", timestamp_ms: 2000 },
    { evidence_id: "recording-1", source_sha256: "abc123", timestamp_ms: 4000 },
    { evidence_id: "recording-1", source_sha256: "abc123", timestamp_ms: 6000 },
  ]);
  assert.equal(frames.every((frame) => frame.bytes.length > 0), true);
});
