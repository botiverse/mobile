const BOUNDS_RE = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/;
const LABEL_RE = /"([^"]*)"/;

function normalizeLabel(value) {
  return String(value || "").replace(/\s/g, "").toLowerCase();
}

export function parseDumpNodes(dump) {
  return String(dump || "")
    .split("\n")
    .map((line) => {
      const label = LABEL_RE.exec(line)?.[1] ?? "";
      const match = BOUNDS_RE.exec(line);
      if (!match) return null;
      const [x1, y1, x2, y2] = match.slice(1).map(Number);
      if (![x1, y1, x2, y2].every(Number.isFinite) || x2 <= x1 || y2 <= y1) return null;
      return {
        line,
        label,
        normalizedLabel: normalizeLabel(label),
        x1,
        y1,
        x2,
        y2,
        x: Math.round((x1 + x2) / 2),
        y: Math.round((y1 + y2) / 2),
      };
    })
    .filter(Boolean);
}

// Dumps are useful runtime evidence, but an editable node may contain an email,
// password, token, or other typed value. Keep geometry/flags while replacing
// every editable value before the artifact leaves the runner.
export function redactEditableValues(dump) {
  return String(dump || "")
    .split("\n")
    .map((line) => {
      if (!line.includes("[INPUT]")) return line;
      const firstQuote = line.indexOf('"');
      const lastQuote = line.lastIndexOf('"');
      if (firstQuote < 0 || lastQuote <= firstQuote) return line;
      return `${line.slice(0, firstQuote)}"[REDACTED]"${line.slice(lastQuote + 1)}`;
    })
    .join("\n");
}

// The first image-button tap on a clean simulator can present the system-owned
// Photos permission alert. XCUITest's app-scoped accessibility dump does not
// include that SpringBoard alert, so accept either app dump markers (picker or
// permission UI) or the corresponding post-tap unified-log title. Keep this
// pure so the evidence parser is covered by offline fixtures.
export function hasPhotoPickerGate({ appDump = "", systemLog = "" } = {}) {
  const dumpMarkers = /photos|recents|photo library|选择|照片|允许|allow|cancel|取消|select/i;
  const systemMarkers = /(?:would like|request(?:ed)?|controllerTitle).*photo library|photo library.*(?:access|permission)/i;
  return dumpMarkers.test(String(appDump)) || systemMarkers.test(String(systemLog));
}

// Locate the #all row only inside the first channel-row band beneath the
// CHANNELS heading. iOS may expose it as one "#all" node or split the icon and
// text into adjacent "#" + "all" nodes. Exact labels and uniqueness are
// deliberate: message text containing "all" must never become a tap target.
export function findAllChannelTarget(dump, { channelBandPx = 360, rowTolerancePx = 80 } = {}) {
  const nodes = parseDumpNodes(dump);
  const headings = nodes.filter((node) => normalizeLabel(node.label).startsWith("channels"));
  if (headings.length !== 1) {
    throw new Error(`expected exactly one CHANNELS heading, found ${headings.length}`);
  }

  const heading = headings[0];
  const inChannelBand = (node) => node.y >= heading.y2 && node.y <= heading.y2 + channelBandPx;
  const direct = nodes.filter((node) => node.normalizedLabel === "#all" && inChannelBand(node));
  const splitText = nodes.filter((node) => node.normalizedLabel === "all" && inChannelBand(node));
  const candidates = [...direct];

  for (const textNode of splitText) {
    const adjacentHash = nodes.find((node) => (
      node.normalizedLabel === "#"
      && inChannelBand(node)
      && node.x2 <= textNode.x1
      && Math.abs(node.y - textNode.y) <= rowTolerancePx
    ));
    candidates.push({ ...textNode, splitHashLine: adjacentHash?.line ?? null });
  }

  if (candidates.length !== 1) {
    throw new Error(`expected exactly one #all channel target in CHANNELS band, found ${candidates.length}`);
  }

  const target = candidates[0];
  return {
    x: target.x,
    y: target.y,
    label: target.label,
    mode: target.normalizedLabel === "#all" ? "single-node" : "split-node",
    line: target.line,
    splitHashLine: target.splitHashLine ?? null,
    channelsHeadingLine: heading.line,
  };
}

// The Kuikly composer currently exposes its whole input surface as one
// "Message #all" node while the image/paperclip icons may have no independent
// accessibility nodes. Prefer a real contained button when one exists. When it
// does not, derive the first (image) square from the composer bounds rather
// than hard-coding a device coordinate. The image action is the leftmost icon
// in the composer's bottom row; the paperclip is immediately to its right.
export function findComposerImageTarget(dump) {
  const nodes = parseDumpNodes(dump);
  const composers = nodes.filter((node) => node.label.trim() === "Message #all");
  if (composers.length !== 1) {
    throw new Error(`expected exactly one Message #all composer, found ${composers.length}`);
  }

  const composer = composers[0];
  const width = composer.x2 - composer.x1;
  const height = composer.y2 - composer.y1;
  if (width < 600 || height < 120) {
    throw new Error(`Message #all composer bounds are implausible: ${composer.line}`);
  }

  const containedButtons = nodes
    .filter((node) => (
      node.line.includes("[BTN]")
      && node.x1 >= composer.x1
      && node.x2 <= composer.x2
      && node.y1 >= composer.y1
      && node.y2 <= composer.y2
      && node.x <= composer.x1 + Math.min(260, width * 0.3)
    ))
    .sort((a, b) => a.x - b.x);

  if (containedButtons.length > 0) {
    if (containedButtons.length > 1 && Math.abs(containedButtons[0].x - containedButtons[1].x) < 12) {
      throw new Error("composer image target is ambiguous: leftmost buttons overlap");
    }
    const target = containedButtons[0];
    return { x: target.x, y: target.y, mode: "semantic-button", line: target.line, composerLine: composer.line };
  }

  const inset = Math.round(Math.min(72, Math.max(54, height * 0.32)));
  const x = composer.x1 + inset;
  const y = composer.y2 - inset;
  if (x <= composer.x1 || x >= composer.x2 || y <= composer.y1 || y >= composer.y2) {
    throw new Error("composer-relative image target fell outside composer bounds");
  }
  return { x, y, mode: "composer-relative", line: null, composerLine: composer.line };
}

// A REAL PhotosUI picker is proven by the COMBINATION of (a) an exact picker
// header (Recents or All Photos), (b) picker chrome (Cancel/Select), and (c)
// at least one plausible grid cell below the header (task #26 review: a line-
// count heuristic or a lone generic word like "select" is NOT sufficient —
// those match ordinary app panels). The system permission marker must never
// be used as picker evidence (it only proves the ALERT).
export function hasRealPhotoPicker(dump) {
  const nodes = parseDumpNodes(dump);
  const header = nodes.find((node) => /^(recents|all photos)$/i.test(node.label.trim()));
  if (!header) return false;
  const chrome = nodes.some((node) => /^(cancel|select)$/i.test(node.label.trim()));
  if (!chrome) return false;
  return nodes.some((node) => isPlausiblePhotoTile(node, header));
}

function isPlausiblePhotoTile(node, header) {
  const w = node.x2 - node.x1;
  const h = node.y2 - node.y1;
  if (node.y1 < header.y2) return false;          // grid lives below the header
  if (w < 60 || h < 60 || w > 400 || h > 400) return false;
  const aspect = w / h;
  return aspect >= 0.7 && aspect <= 1.4;           // photo tiles are near-square
}

// Locate the first photo cell in a REAL PhotosUI picker dump (task #26).
// The staged fixture is the most recent photo, i.e. the top-left tile in the
// Recents grid. Hard rules from review: the picker header must exist (no
// whole-dump fallback), tiles only count below it, and an ambiguous top-left
// (two overlapping candidates) fails closed.
export function findPhotoCellTarget(dump) {
  const nodes = parseDumpNodes(dump);
  const header = nodes.find((node) => /^(recents|all photos)$/i.test(node.label.trim()));
  if (!header) throw new Error("picker header (Recents/All Photos) not found — not a proven PhotosUI picker");

  const tiles = nodes
    .filter((node) => isPlausiblePhotoTile(node, header))
    .sort((a, b) => (a.y1 - b.y1) || (a.x1 - b.x1));
  if (!tiles.length) throw new Error("no plausible photo tile below the picker header");

  const first = tiles[0];
  const overlapping = tiles.filter((t) => t !== first
    && Math.abs(t.x - first.x) < 12 && Math.abs(t.y - first.y) < 12);
  if (overlapping.length) {
    throw new Error(`ambiguous top-left photo tile: ${overlapping.length + 1} overlapping candidates`);
  }
  return { x: first.x, y: first.y, line: first.line };
}

// Composer preview proof: a NEW node strictly inside the composer bounds that
// did not exist before the selection (task #26 review: a closed picker alone
// is NOT a preview — that is exactly the no-attachment product path we must
// catch). Compare node sets inside the composer rectangle.
export function composerHasNewPreview(beforeDump, afterDump, composerLine) {
  const line = composerLine || '"Message #all"';
  const before = new Set(nodesInside(beforeDump, line).map((n) => `${n.label}@${n.x},${n.y}`));
  return nodesInside(afterDump, line).some((n) => !before.has(`${n.label}@${n.x},${n.y}`));
}

function nodesInside(dump, composerLine) {
  const nodes = parseDumpNodes(dump);
  const composer = nodes.find((node) => node.label.trim() === "Message #all") || { line: composerLine, x1: 0, y1: 0, x2: 99999, y2: 99999 };
  return nodes.filter((node) => node.x1 >= composer.x1 && node.x2 <= composer.x2 && node.y1 >= composer.y1 && node.y2 <= composer.y2);
}

// Readback proof by before/after diff: count large image-ish nodes in the
// thread area ABOVE the composer before send and after send; the new image
// message must increase that count (task #26 review: any historical large
// node must not satisfy readback).
export function countThreadImageNodes(dump) {
  const nodes = parseDumpNodes(dump);
  const composer = nodes.find((node) => node.label.trim() === "Message #all");
  if (!composer) return 0;
  return nodes.filter((node) => {
    const w = node.x2 - node.x1;
    const h = node.y2 - node.y1;
    return node.y2 < composer.y1 && w > 150 && h > 150;
  }).length;
}
