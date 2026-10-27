import assert from "node:assert/strict";
import test from "node:test";
import { buildEvidenceOverlays, screenshotPixelTap } from "./evidence_annotations.mjs";

test("direct tap and fresh-bounds tap_text both annotate the pre-action screenshot", () => {
  const directTap = screenshotPixelTap(4, 120.4, 300.6);
  const tapText = screenshotPixelTap(4, 987, 654);

  assert.deepEqual(directTap, { step: 3, x: 120, y: 301 });
  assert.deepEqual(tapText, { step: 3, x: 987, y: 654 });
});

test("tap and assertion geometry remains in screenshot pixels without rescaling", () => {
  const overlays = buildEvidenceOverlays(
    [screenshotPixelTap(2, 900, 1800)],
    [{ step: 1, bounds: "[120,240][960,1920]", result: "pass", label: "Visible" }],
  );
  const svg = overlays.get(1);

  assert.match(svg, /circle cx="900" cy="1800"/);
  assert.match(svg, /rect x="120" y="240" width="840" height="1680"/);
  assert.doesNotMatch(svg, /2700|5400|2520|5040/);
});
