const BOUNDS_RE = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/;

function escapeXml(s) {
  return String(s).replace(/[<>&"']/g, (c) => ({
    "<": "&lt;",
    ">": "&gt;",
    "&": "&amp;",
    '"': "&quot;",
    "'": "&apos;",
  }[c]));
}

// Every action is chosen from the screenshot captured at the end of the
// previous turn. A fresh accessibility dump may refine a tap_text coordinate,
// but it does not capture a new frame; the resulting crosshair still belongs
// to that same pre-action screenshot.
export function screenshotPixelTap(turn, x, y) {
  const step = Number(turn) - 1;
  const px = Math.round(Number(x));
  const py = Math.round(Number(y));
  if (!Number.isInteger(step) || step < 0 || !Number.isFinite(px) || !Number.isFinite(py)) {
    throw new Error("invalid screenshot-pixel tap annotation");
  }
  return { step, x: px, y: py };
}

// Tap coordinates and assertion bounds entering this helper are already in
// screenshot pixels on both platforms. Keep this pure and scale-free so an
// iOS point-to-pixel conversion cannot accidentally be applied twice.
export function buildEvidenceOverlays(taps, assertions, redactLabel = String) {
  const byStep = new Map();
  const add = (step, svg) => {
    const n = Number(step);
    if (!Number.isInteger(n) || n < 0) return;
    byStep.set(n, (byStep.get(n) || "") + svg);
  };
  for (const t of taps) {
    const s = 22;
    add(t.step,
      `<line x1="${t.x - s}" y1="${t.y - s}" x2="${t.x + s}" y2="${t.y + s}" stroke="#ffd400" stroke-width="5"/>` +
      `<line x1="${t.x - s}" y1="${t.y + s}" x2="${t.x + s}" y2="${t.y - s}" stroke="#ffd400" stroke-width="5"/>` +
      `<circle cx="${t.x}" cy="${t.y}" r="${s + 8}" fill="none" stroke="#ffd400" stroke-width="3"/>`);
  }
  for (const a of assertions) {
    const m = BOUNDS_RE.exec(String(a.bounds ?? ""));
    if (!m) continue;
    const [x1, y1, x2, y2] = m.slice(1).map(Number);
    if (x2 <= x1 || y2 <= y1) continue;
    const color = a.result === "fail" ? "#ff2d2d" : "#00c853";
    const label = escapeXml(redactLabel(String(a.label ?? "")).slice(0, 60));
    const labelY = y1 >= 34 ? y1 - 10 : y2 + 26;
    add(a.step,
      `<rect x="${x1}" y="${y1}" width="${x2 - x1}" height="${y2 - y1}" fill="none" stroke="${color}" stroke-width="5" rx="4"/>` +
      (label
        ? `<text x="${x1}" y="${labelY}" font-family="sans-serif" font-size="26" font-weight="bold" fill="${color}" stroke="#000000" stroke-width="0.6">${label}</text>`
        : ""));
  }
  return byStep;
}
