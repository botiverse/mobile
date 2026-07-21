import assert from "node:assert/strict";
import test from "node:test";
import { createHash } from "node:crypto";
import { captureAndroidScreenshotWithPostProof } from "./android_capture_proof.mjs";

test("Android capture proof is registered only after bytes and digest exist", () => {
  const events = [];
  const bytes = Buffer.from("png-bytes");
  const result = captureAndroidScreenshotWithPostProof(
    () => {
      events.push("capture-bytes");
      return bytes;
    },
    (imageSha256) => {
      events.push(`register:${imageSha256}`);
      assert.equal(imageSha256, createHash("sha256").update(bytes).digest("hex"));
      return { imageSha256 };
    }
  );

  assert.deepEqual(events, ["capture-bytes", `register:${result.imageSha256}`]);
  assert.equal(result.png, bytes);
  assert.equal(result.boundary.imageSha256, result.imageSha256);
});
